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
import dev.vehiclespeed.gnssprobe.estimator.MountCalibrationMode
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class RealDashLiveSnapshot(
    val active: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Live publisher stopped",
    val estimatedSpeedKph: Double = 0.0,
    val rawGpsSpeedKph: Double = 0.0,
    val gpsAgeMs: Int = 65_535,
    val estimatorModeCode: Int = 0,
    val flags: Int = RealDashLiveTelemetrySelector.FLAG_CALIBRATION_PROVISIONAL,
    val valid: Boolean = false,
    val packetsSent: Long = 0L,
)

object RealDashLiveRuntime {
    @Volatile
    var snapshot = RealDashLiveSnapshot()
}

/** Publishes the combined capture service's current estimator state to RealDash. */
class RealDashLiveService : Service() {
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { command ->
        Thread(command, "realdash-live-publisher")
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
            RealDashLiveRuntime.snapshot = RealDashLiveSnapshot(
                active = true,
                status = "Opening $LOOPBACK_ADDRESS:$PORT",
            )
            worker.execute(::runServer)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        closeSockets()
        worker.shutdownNow()
        RealDashLiveRuntime.snapshot = RealDashLiveSnapshot()
        Log.i(TAG, "Live publisher stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runServer() {
        try {
            val server = ServerSocket().also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), PORT))
                it.soTimeout = ACCEPT_TIMEOUT_MS
            }
            serverSocket = server
            updateWaitingStatus("Waiting for RealDash on $LOOPBACK_ADDRESS:$PORT")

            while (running.get()) {
                try {
                    val client = server.accept()
                    clientSocket = client
                    client.tcpNoDelay = true
                    publishTo(client)
                } catch (_: SocketTimeoutException) {
                    // Wake periodically so a stop request closes promptly.
                } catch (exception: IOException) {
                    if (running.get()) {
                        updateWaitingStatus("RealDash disconnected; waiting to reconnect")
                    }
                } finally {
                    closeClientSocket()
                }
            }
        } catch (exception: IOException) {
            if (running.get()) {
                RealDashLiveRuntime.snapshot = RealDashLiveRuntime.snapshot.copy(
                    active = false,
                    connected = false,
                    status = "TCP server error: ${exception.message ?: "unknown error"}",
                )
                Log.e(TAG, "Live TCP server failed", exception)
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
        var nextSendNs = SystemClock.elapsedRealtimeNanos()
        var packetsSent = 0L

        Log.i(TAG, "RealDash connected from ${client.inetAddress.hostAddress}")
        while (running.get() && !client.isClosed) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val capture = CombinedCaptureRuntime.snapshot
            val telemetry = RealDashLiveTelemetrySelector.select(
                RealDashLiveInput(
                    captureActive = capture.active,
                    nowElapsedNs = nowNs,
                    rawGpsSpeedMps = capture.gpsSpeedMps,
                    lastRawGpsElapsedNs = capture.lastGpsElapsedNs,
                    lastAcceptedGpsElapsedNs = capture.lastAcceptedGpsElapsedNs,
                    estimatedSpeedMps = capture.estimatedSpeedMps,
                    estimatorTimestampNs = capture.estimatorTimestampNs,
                    estimatorMode = capture.estimatorMode,
                    mountCalibrationReady =
                        capture.mountCalibrationMode == MountCalibrationMode.READY,
                ),
            )

            output.write(
                RealDashCanEncoder.speedFrame(
                    estimatedSpeedKph = telemetry.estimatedSpeedKph,
                    rawGpsSpeedKph = telemetry.rawGpsSpeedKph,
                    gpsAgeMs = telemetry.gpsAgeMs,
                    estimatorMode = telemetry.estimatorModeCode,
                    flags = telemetry.flags,
                ),
            )
            output.flush()
            packetsSent++

            while (input.available() > 0) input.read()

            RealDashLiveRuntime.snapshot = RealDashLiveSnapshot(
                active = true,
                connected = true,
                status = connectedStatus(capture, telemetry),
                estimatedSpeedKph = telemetry.estimatedSpeedKph,
                rawGpsSpeedKph = telemetry.rawGpsSpeedKph,
                gpsAgeMs = telemetry.gpsAgeMs,
                estimatorModeCode = telemetry.estimatorModeCode,
                flags = telemetry.flags,
                valid = telemetry.valid,
                packetsSent = packetsSent,
            )

            nextSendNs += PUBLISH_INTERVAL_NS
            if (nextSendNs <= nowNs) nextSendNs = nowNs + PUBLISH_INTERVAL_NS
            sleepUntil(nextSendNs)
        }
    }

    private fun connectedStatus(
        capture: CombinedCaptureSnapshot,
        telemetry: RealDashLiveTelemetry,
    ): String = when {
        !capture.active -> "RealDash connected; combined capture is stopped"
        capture.estimatedSpeedMps == null -> "RealDash connected; waiting for GPS initialization"
        capture.mountCalibrationMode != MountCalibrationMode.READY ->
            "RealDash connected; mount moved, holding zero while stationary"
        telemetry.valid -> "RealDash connected; publishing live estimate"
        else -> "RealDash connected; estimate is stale or degraded"
    }

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

    private fun updateWaitingStatus(status: String) {
        RealDashLiveRuntime.snapshot = RealDashLiveRuntime.snapshot.copy(
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
                "Live RealDash output",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RealDashLiveActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Vehicle speed output active")
            .setContentText("Publishing the live estimate to RealDash at 60 Hz")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.vehiclespeed.gnssprobe.action.START_REALDASH_LIVE"
        const val ACTION_STOP = "dev.vehiclespeed.gnssprobe.action.STOP_REALDASH_LIVE"
        const val PORT = 35_000
        const val LOOPBACK_ADDRESS = "127.0.0.1"

        private const val TAG = "RealDashLive"
        private const val NOTIFICATION_CHANNEL_ID = "realdash_live"
        private const val NOTIFICATION_ID = 7_003
        private const val ACCEPT_TIMEOUT_MS = 500
        private const val PUBLISH_RATE_HZ = 60L
        private const val PUBLISH_INTERVAL_NS = 1_000_000_000L / PUBLISH_RATE_HZ
    }
}
