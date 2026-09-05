package dev.vehiclespeed.gnssprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

data class RealDashTestSnapshot(
    val active: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Publisher stopped",
    val estimatedSpeedKph: Double = 0.0,
    val rawGpsSpeedKph: Double = 0.0,
    val packetsSent: Long = 0,
)

object RealDashTestRuntime {
    @Volatile
    var snapshot = RealDashTestSnapshot()
}

class RealDashTestService : Service() {
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { command ->
        Thread(command, "realdash-loopback-test")
    }
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        if (running.compareAndSet(false, true)) {
            RealDashTestRuntime.snapshot = RealDashTestSnapshot(
                active = true,
                status = "Opening 127.0.0.1:$PORT",
            )
            worker.execute(::runServer)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        closeSockets()
        worker.shutdownNow()
        RealDashTestRuntime.snapshot = RealDashTestSnapshot(status = "Publisher stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runServer() {
        try {
            val server = ServerSocket().also {
                it.reuseAddress = true
                // Android commonly resolves getLoopbackAddress() to IPv6 ::1.
                // RealDash is configured with the IPv4 address 127.0.0.1, so
                // bind that address explicitly rather than relying on a
                // platform-dependent loopback-family choice.
                it.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), PORT))
                it.soTimeout = ACCEPT_TIMEOUT_MS
            }
            serverSocket = server
            updateStatus("Waiting for RealDash on $LOOPBACK_ADDRESS:$PORT")

            while (running.get()) {
                try {
                    val client = server.accept()
                    clientSocket = client
                    client.tcpNoDelay = true
                    publishTo(client)
                } catch (_: SocketTimeoutException) {
                    // Periodically wake so stopping the foreground service is immediate.
                } catch (exception: IOException) {
                    if (running.get()) {
                        updateStatus("RealDash disconnected; waiting to reconnect")
                    }
                } finally {
                    closeClientSocket()
                }
            }
        } catch (exception: IOException) {
            if (running.get()) {
                RealDashTestRuntime.snapshot = RealDashTestRuntime.snapshot.copy(
                    active = false,
                    connected = false,
                    status = "TCP server error: ${exception.message ?: "unknown error"}",
                )
                Log.e(TAG, "TCP server failed", exception)
                stopSelf()
            }
        } finally {
            closeSockets()
        }
    }

    @Throws(IOException::class)
    private fun publishTo(client: Socket) {
        val output = BufferedOutputStream(client.getOutputStream())
        val input = client.getInputStream()
        val connectedAtNs = SystemClock.elapsedRealtimeNanos()
        var nextSendNs = connectedAtNs
        var lastGpsSecond = -1L
        var rawGpsSpeedKph = 0.0
        var packetsSent = 0L

        RealDashTestRuntime.snapshot = RealDashTestRuntime.snapshot.copy(
            active = true,
            connected = true,
            status = "RealDash connected; publishing simulated speed",
        )
        Log.i(TAG, "Client connected from ${client.inetAddress.hostAddress}")

        while (running.get() && !client.isClosed) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val elapsedSeconds = (nowNs - connectedAtNs) / NS_PER_SECOND
            val estimatedSpeedKph = simulatedSpeedKph(elapsedSeconds)
            val gpsSecond = floor(elapsedSeconds).toLong()
            if (gpsSecond != lastGpsSecond) {
                rawGpsSpeedKph = simulatedRawGpsSpeedKph(
                    estimatedSpeedKph = estimatedSpeedKph,
                    sampleNumber = gpsSecond,
                )
                lastGpsSecond = gpsSecond
            }
            val gpsAgeMs = ((elapsedSeconds - gpsSecond) * 1000.0).toInt().coerceIn(0, 999)

            output.write(
                RealDashCanEncoder.speedFrame(
                    estimatedSpeedKph = estimatedSpeedKph,
                    rawGpsSpeedKph = rawGpsSpeedKph,
                    gpsAgeMs = gpsAgeMs,
                    estimatorMode = MODE_PREDICTING,
                    flags = FLAG_VALID,
                ),
            )
            output.flush()
            packetsSent++

            // RealDash may send setup or SET VALUE data. It is irrelevant to this
            // read-only test, but draining available bytes prevents buffer buildup.
            while (input.available() > 0) input.read()

            RealDashTestRuntime.snapshot = RealDashTestSnapshot(
                active = true,
                connected = true,
                status = "RealDash connected; publishing simulated speed",
                estimatedSpeedKph = estimatedSpeedKph,
                rawGpsSpeedKph = rawGpsSpeedKph,
                packetsSent = packetsSent,
            )

            nextSendNs += PUBLISH_INTERVAL_NS
            sleepUntil(nextSendNs)
        }
    }

    private fun simulatedSpeedKph(elapsedSeconds: Double): Double {
        val phase = elapsedSeconds % DRIVE_CYCLE_SECONDS
        val speedMph = when {
            phase < 3.0 -> 0.0
            phase < 9.0 -> smoothTransition(
                start = 0.0,
                end = 30.0,
                progress = (phase - 3.0) / 6.0,
            )
            phase < 14.0 -> 30.0 + cruiseVariation(
                elapsed = phase - 9.0,
                duration = 5.0,
                amplitudeMph = 0.25,
            )
            phase < 22.0 -> smoothTransition(
                start = 30.0,
                end = 58.0,
                progress = (phase - 14.0) / 8.0,
            )
            phase < 28.0 -> 58.0 + cruiseVariation(
                elapsed = phase - 22.0,
                duration = 6.0,
                amplitudeMph = 0.2,
            )
            phase < 35.0 -> smoothTransition(
                start = 58.0,
                end = 0.0,
                progress = (phase - 28.0) / 7.0,
            )
            else -> 0.0
        }
        return speedMph.coerceAtLeast(0.0) * MPH_TO_KPH
    }

    private fun simulatedRawGpsSpeedKph(
        estimatedSpeedKph: Double,
        sampleNumber: Long,
    ): Double {
        if (estimatedSpeedKph < STATIONARY_THRESHOLD_KPH) return 0.0
        val deterministicErrorKph = GPS_ERROR_AMPLITUDE_MPH * MPH_TO_KPH *
            sin(sampleNumber * GPS_ERROR_PHASE_STEP)
        return (estimatedSpeedKph + deterministicErrorKph).coerceAtLeast(0.0)
    }

    private fun smoothTransition(start: Double, end: Double, progress: Double): Double {
        val clamped = progress.coerceIn(0.0, 1.0)
        val smoothStep = clamped * clamped * (3.0 - 2.0 * clamped)
        return start + (end - start) * smoothStep
    }

    private fun cruiseVariation(
        elapsed: Double,
        duration: Double,
        amplitudeMph: Double,
    ): Double = amplitudeMph * sin(2.0 * PI * elapsed / duration)

    private fun sleepUntil(targetNs: Long) {
        val remainingNs = targetNs - SystemClock.elapsedRealtimeNanos()
        if (remainingNs <= 0L) return
        try {
            Thread.sleep(
                remainingNs / 1_000_000L,
                (remainingNs % 1_000_000L).toInt(),
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun updateStatus(status: String) {
        RealDashTestRuntime.snapshot = RealDashTestRuntime.snapshot.copy(
            active = true,
            connected = false,
            status = status,
        )
        Log.i(TAG, status)
    }

    private fun closeClientSocket() {
        try {
            clientSocket?.close()
        } catch (_: IOException) {
            // Socket is already unusable.
        } finally {
            clientSocket = null
        }
    }

    private fun closeSockets() {
        closeClientSocket()
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Socket is already unusable.
        } finally {
            serverSocket = null
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "RealDash connection test",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createNotification(): Notification {
        val activityIntent = Intent(this, RealDashTestActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Vehicle Speed Estimator test")
            .setContentText("Publishing simulated speed for RealDash")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.vehiclespeed.gnssprobe.action.START_REALDASH_TEST"
        const val ACTION_STOP = "dev.vehiclespeed.gnssprobe.action.STOP_REALDASH_TEST"
        const val PORT = 35_000
        const val LOOPBACK_ADDRESS = "127.0.0.1"

        private const val NOTIFICATION_CHANNEL_ID = "realdash_test"
        private const val NOTIFICATION_ID = 7_001
        private const val TAG = "RealDashTest"
        private const val ACCEPT_TIMEOUT_MS = 500
        private const val PUBLISH_RATE_HZ = 60L
        private const val PUBLISH_INTERVAL_NS = 1_000_000_000L / PUBLISH_RATE_HZ
        private const val NS_PER_SECOND = 1_000_000_000.0
        private const val MPH_TO_KPH = 1.609344
        private const val DRIVE_CYCLE_SECONDS = 39.0
        private const val STATIONARY_THRESHOLD_KPH = 0.5
        private const val GPS_ERROR_AMPLITUDE_MPH = 0.35
        private const val GPS_ERROR_PHASE_STEP = 1.7
        private const val MODE_PREDICTING = 2
        private const val FLAG_VALID = 1
    }
}
