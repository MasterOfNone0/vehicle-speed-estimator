package dev.vehiclespeed.gnssprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.vehiclespeed.gnssprobe.estimator.EstimatorMode
import dev.vehiclespeed.gnssprobe.estimator.GuardedSpeedEstimator
import dev.vehiclespeed.gnssprobe.estimator.MountCalibrationMode
import dev.vehiclespeed.gnssprobe.estimator.MountCalibrationState
import dev.vehiclespeed.gnssprobe.estimator.VehicleFrameTransform
import dev.vehiclespeed.gnssprobe.estimator.VelocityEstimatorState
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class CombinedCaptureSnapshot(
    val active: Boolean = false,
    val status: String = "Capture stopped",
    val gpsSpeedMps: Double? = null,
    val gpsSpeedAccuracyMps: Double? = null,
    val lastGpsElapsedNs: Long = 0L,
    val gpsRateHz: Double = 0.0,
    val accelRateHz: Double = 0.0,
    val accelX: Double = 0.0,
    val accelY: Double = 0.0,
    val accelZ: Double = 0.0,
    val accelMagnitude: Double = 0.0,
    val longitudinalAccelerationMps2: Double = 0.0,
    val correctedAccelerationMps2: Double = 0.0,
    val estimatedSpeedMps: Double? = null,
    val estimatedAccelBiasMps2: Double = 0.0,
    val innovationMps: Double? = null,
    val stateVarianceSpeedMps2: Double? = null,
    val estimatorMode: EstimatorMode = EstimatorMode.UNINITIALIZED,
    val estimatorTimestampNs: Long = 0L,
    val lastAcceptedGpsElapsedNs: Long = 0L,
    val estimatorRateHz: Double = 0.0,
    val calibrationName: String = VehicleFrameTransform.DEVELOPMENT_DRIVE.calibrationName,
    val mountCalibrationMode: MountCalibrationMode = MountCalibrationMode.READY,
    val mountOrientationChangeDegrees: Double = 0.0,
    val mountGravityNoiseRmsMps2: Double = 0.0,
    val mountHoldSpeedAtZero: Boolean = false,
    val mountRecalibrationCount: Int = 0,
    val gpsCount: Long = 0L,
    val accelCount: Long = 0L,
    val logPath: String = "",
    val fusionReady: Boolean = false,
    val fusionReason: String = "Waiting for GPS",
    val recoveryCount: Int = 0,
)

object CombinedCaptureRuntime {
    @Volatile
    var snapshot = CombinedCaptureSnapshot()
}

class CombinedCaptureService : Service(), SensorEventListener, LocationListener {
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val sensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val accelerometer by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    private val estimator = GuardedSpeedEstimator()

    private val running = AtomicBoolean(false)
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var captureExecutor: Executor
    private val gpsTimestampsNs = ArrayDeque<Long>()
    private val accelTimestampsNs = ArrayDeque<Long>()

    private var gpsCount = 0L
    private var accelCount = 0L
    private var sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var writer: BufferedWriter? = null
    private var logFile: File? = null
    private var recordsSinceFlush = 0
    private var loggingError: String? = null
    private var failureMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("combined-sensor-capture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        captureExecutor = Executor { command -> captureHandler.post(command) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())
        if (running.compareAndSet(false, true)) {
            CombinedCaptureRuntime.snapshot = CombinedCaptureSnapshot(
                active = true,
                status = "Starting GPS and accelerometer",
            )
            captureHandler.post(::startCapture)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        try {
            locationManager.removeUpdates(this)
        } catch (_: SecurityException) {
            // Permission may have been revoked while capture was active.
        }
        sensorManager.unregisterListener(this)
        captureHandler.post {
            closeLog()
            captureThread.quitSafely()
        }
        CombinedCaptureRuntime.snapshot = CombinedCaptureRuntime.snapshot.copy(
            active = false,
            status = failureMessage ?: "Capture stopped; CSV saved",
        )
        Log.i(TAG, "Capture stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture() {
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failAndStop("Precise location permission is required")
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            failAndStop("GPS provider is disabled")
            return
        }
        val sensor = accelerometer
        if (sensor == null) {
            failAndStop("No accelerometer is exposed by Android")
            return
        }

        try {
            resetSession()
            openLog()
            val registered = sensorManager.registerListener(
                this,
                sensor,
                ACCEL_SAMPLING_PERIOD_US,
                0,
                captureHandler,
            )
            if (!registered) throw IOException("Android rejected the accelerometer request")
            requestGpsUpdates()
        } catch (exception: SecurityException) {
            failAndStop("Location permission was rejected")
            return
        } catch (exception: IOException) {
            failAndStop("Could not start capture: ${exception.message}")
            return
        }

        updateSnapshot(status = "Capturing GPS and 100 Hz accelerometer")
        Log.i(TAG, "Combined capture started: ${logFile?.absolutePath}")
    }

    @SuppressLint("MissingPermission")
    private fun requestGpsUpdates() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = LocationRequest.Builder(0L)
                .setMinUpdateIntervalMillis(0L)
                .setMinUpdateDistanceMeters(0f)
                .setMaxUpdateDelayMillis(0L)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build()
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                request,
                captureExecutor,
                this,
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                this,
                captureThread.looper,
            )
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!running.get()) return
        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        val eventNs = location.elapsedRealtimeNanos
        if (eventNs <= CombinedCaptureRuntime.snapshot.lastGpsElapsedNs || eventNs > arrivalNs) return
        gpsCount++
        addTimestamp(gpsTimestampsNs, eventNs)
        val gpsSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else null
        val gpsSpeedAccuracyMps = if (location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond.toDouble()
        } else {
            null
        }
        val guarded = estimator.onGpsSpeed(eventNs, gpsSpeedMps ?: Double.NaN, gpsSpeedAccuracyMps, arrivalNs)
        val calibrationState = guarded.calibration
        val estimatorState = guarded.estimator
        writeRecord(
            type = "GPS",
            arrivalNs = arrivalNs,
            eventNs = eventNs,
            eventUtcMs = location.time,
            gpsSpeedMps = gpsSpeedMps,
            gpsSpeedAccuracyMps = gpsSpeedAccuracyMps,
            horizontalAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            bearingAccuracyDeg = if (location.hasBearingAccuracy()) {
                location.bearingAccuracyDegrees.toDouble()
            } else {
                null
            },
            estimatorState = estimatorState,
            calibrationState = calibrationState,
        )

        val current = CombinedCaptureRuntime.snapshot
        CombinedCaptureRuntime.snapshot = current.copy(
            status = currentStatus("Capturing; receiving GPS and accelerometer"),
            gpsSpeedMps = gpsSpeedMps,
            gpsSpeedAccuracyMps = gpsSpeedAccuracyMps,
            lastGpsElapsedNs = eventNs,
            gpsRateHz = calculateRate(gpsTimestampsNs),
            gpsCount = gpsCount,
            lastAcceptedGpsElapsedNs = if (estimatorState.gpsMeasurementAccepted) {
                eventNs
            } else {
                current.lastAcceptedGpsElapsedNs
            },
        ).withEstimator(estimatorState, calibrationState)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running.get() || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        val guarded = estimator.onAcceleration(event.timestamp, x, y, z)
        val calibrationState = guarded.calibration
        val estimatorState = guarded.estimator
        accelCount++
        addTimestamp(accelTimestampsNs, event.timestamp)
        writeRecord(
            type = "ACCEL",
            arrivalNs = arrivalNs,
            eventNs = event.timestamp,
            accelX = x,
            accelY = y,
            accelZ = z,
            accelAccuracy = sensorAccuracy,
            estimatorState = estimatorState,
            calibrationState = calibrationState,
        )

        val current = CombinedCaptureRuntime.snapshot
        val status = if (current.lastGpsElapsedNs > 0L) {
            "Capturing; receiving GPS and accelerometer"
        } else {
            "Capturing; waiting for first GPS fix"
        }
        CombinedCaptureRuntime.snapshot = current.copy(
            status = currentStatus(status),
            accelRateHz = calculateRate(accelTimestampsNs),
            accelX = x,
            accelY = y,
            accelZ = z,
            accelMagnitude = magnitude,
            accelCount = accelCount,
            estimatorRateHz = if (estimatorState.initialized) {
                calculateRate(accelTimestampsNs)
            } else {
                0.0
            },
        ).withEstimator(estimatorState, calibrationState)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ACCELEROMETER) sensorAccuracy = accuracy
    }

    private fun addTimestamp(window: ArrayDeque<Long>, timestampNs: Long) {
        window.addLast(timestampNs)
        val cutoff = timestampNs - RATE_WINDOW_NS
        while (window.size > 2 && window.first() < cutoff) window.removeFirst()
    }

    private fun calculateRate(window: ArrayDeque<Long>): Double {
        if (window.size < 2) return 0.0
        val durationSeconds = (window.last() - window.first()) / NS_PER_SECOND
        return if (durationSeconds > 0.0) (window.size - 1) / durationSeconds else 0.0
    }

    @Throws(IOException::class)
    private fun openLog() {
        val documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw IOException("external files directory unavailable")
        val directory = File(documents, "combined-sessions")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("could not create ${directory.absolutePath}")
        }
        val timestamp = FILE_TIME_FORMAT.format(Instant.now())
        logFile = File(directory, "combined-$timestamp.csv")
        writer = BufferedWriter(FileWriter(logFile, false), LOG_BUFFER_SIZE).apply {
            write(
                "record_type,arrival_elapsed_ns,event_elapsed_ns,event_utc_ms," +
                    "gps_speed_mps,gps_speed_accuracy_mps,horizontal_accuracy_m," +
                    "bearing_deg,bearing_accuracy_deg,accel_x_mps2,accel_y_mps2," +
                    "accel_z_mps2,accel_accuracy,longitudinal_accel_mps2," +
                    "corrected_longitudinal_accel_mps2,estimated_speed_mps," +
                    "estimated_accel_bias_mps2,innovation_mps," +
                    "state_variance_speed_mps2,estimator_mode," +
                    "gps_measurement_accepted,mount_calibration_mode," +
                    "mount_orientation_change_deg,mount_gravity_noise_rms_mps2," +
                    "mount_hold_speed_at_zero,mount_recalibration_count," +
                    "active_forward_x,active_forward_y,active_forward_z," +
                    "fusion_ready,fusion_reason,recovery_count\n",
            )
            flush()
        }
        updateSnapshot(logPath = logFile?.absolutePath.orEmpty())
    }

    private fun writeRecord(
        type: String,
        arrivalNs: Long,
        eventNs: Long,
        eventUtcMs: Long? = null,
        gpsSpeedMps: Double? = null,
        gpsSpeedAccuracyMps: Double? = null,
        horizontalAccuracyM: Double? = null,
        bearingDeg: Double? = null,
        bearingAccuracyDeg: Double? = null,
        accelX: Double? = null,
        accelY: Double? = null,
        accelZ: Double? = null,
        accelAccuracy: Int? = null,
        estimatorState: VelocityEstimatorState? = null,
        calibrationState: MountCalibrationState? = null,
    ) {
        val activeWriter = writer ?: return
        try {
            val fields = listOf(
                type,
                arrivalNs,
                eventNs,
                eventUtcMs,
                gpsSpeedMps,
                gpsSpeedAccuracyMps,
                horizontalAccuracyM,
                bearingDeg,
                bearingAccuracyDeg,
                accelX,
                accelY,
                accelZ,
                accelAccuracy,
                estimatorState?.longitudinalAccelerationMps2,
                estimatorState?.correctedAccelerationMps2,
                estimatorState?.velocityMps?.takeIf { estimatorState.initialized },
                estimatorState?.accelerationBiasMps2?.takeIf { estimatorState.initialized },
                estimatorState?.innovationMps,
                estimatorState?.speedVarianceMps2?.takeIf {
                    estimatorState.initialized && it.isFinite()
                },
                estimatorState?.mode?.name,
                estimatorState?.gpsMeasurementAccepted,
                calibrationState?.mode?.name,
                calibrationState?.orientationChangeDegrees,
                calibrationState?.gravityNoiseRmsMps2,
                calibrationState?.holdSpeedAtZero,
                calibrationState?.recalibrationCount,
                calibrationState?.activeTransform?.forwardAxis?.x,
                calibrationState?.activeTransform?.forwardAxis?.y,
                calibrationState?.activeTransform?.forwardAxis?.z,
                estimator.state().fusionReady,
                estimator.state().reason,
                estimator.state().recoveryCount,
            )
            activeWriter.write(fields.joinToString(",", transform = ::formatCsvValue))
            activeWriter.newLine()
            recordsSinceFlush++
            if (recordsSinceFlush >= LOG_FLUSH_RECORDS) {
                activeWriter.flush()
                recordsSinceFlush = 0
            }
        } catch (exception: IOException) {
            loggingError = exception.message ?: "unknown CSV error"
            closeLog()
            Log.e(TAG, "Combined capture logging failed", exception)
        }
    }

    private fun formatCsvValue(value: Any?): String = when (value) {
        null -> ""
        is Double -> String.format(Locale.US, "%.8f", value)
        else -> value.toString()
    }

    private fun currentStatus(normalStatus: String): String =
        loggingError?.let { "Sensors active; CSV logging failed: $it" } ?: normalStatus

    private fun updateSnapshot(
        status: String? = null,
        logPath: String? = null,
    ) {
        val current = CombinedCaptureRuntime.snapshot
        CombinedCaptureRuntime.snapshot = current.copy(
            active = true,
            status = status ?: current.status,
            logPath = logPath ?: current.logPath,
        )
    }

    private fun resetSession() {
        gpsTimestampsNs.clear()
        accelTimestampsNs.clear()
        gpsCount = 0L
        accelCount = 0L
        recordsSinceFlush = 0
        loggingError = null
        failureMessage = null
        estimator.reset()
        CombinedCaptureRuntime.snapshot = CombinedCaptureSnapshot(
            active = true,
            status = "Opening combined session CSV",
        )
    }

    private fun closeLog() {
        try {
            writer?.close()
        } catch (_: IOException) {
            // The capture result remains usable up to the last successful flush.
        } finally {
            writer = null
        }
    }

    private fun CombinedCaptureSnapshot.withEstimator(
        estimatorState: VelocityEstimatorState,
        calibrationState: MountCalibrationState,
    ): CombinedCaptureSnapshot = copy(
        fusionReady = estimator.state().fusionReady,
        fusionReason = estimator.state().reason,
        recoveryCount = estimator.state().recoveryCount,
        longitudinalAccelerationMps2 = estimatorState.longitudinalAccelerationMps2,
        correctedAccelerationMps2 = estimatorState.correctedAccelerationMps2,
        estimatedSpeedMps = estimatorState.velocityMps.takeIf { estimatorState.initialized },
        estimatedAccelBiasMps2 = estimatorState.accelerationBiasMps2,
        innovationMps = estimatorState.innovationMps,
        stateVarianceSpeedMps2 = estimatorState.speedVarianceMps2.takeIf {
            estimatorState.initialized && it.isFinite()
        },
        estimatorMode = estimatorState.mode,
        estimatorTimestampNs = estimatorState.timestampNs,
        calibrationName = calibrationState.activeTransform.calibrationName,
        mountCalibrationMode = calibrationState.mode,
        mountOrientationChangeDegrees = calibrationState.orientationChangeDegrees,
        mountGravityNoiseRmsMps2 = calibrationState.gravityNoiseRmsMps2,
        mountHoldSpeedAtZero = calibrationState.holdSpeedAtZero,
        mountRecalibrationCount = calibrationState.recalibrationCount,
    )

    private fun failAndStop(message: String) {
        failureMessage = message
        closeLog()
        CombinedCaptureRuntime.snapshot = CombinedCaptureRuntime.snapshot.copy(
            active = false,
            status = message,
        )
        Log.e(TAG, message)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Combined sensor capture",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createNotification(): Notification {
        val activityIntent = Intent(this, CombinedCaptureActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Vehicle sensor capture active")
            .setContentText("Recording direct GPS speed and acceleration")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.vehiclespeed.gnssprobe.action.START_COMBINED_CAPTURE"
        const val ACTION_STOP = "dev.vehiclespeed.gnssprobe.action.STOP_COMBINED_CAPTURE"

        private const val TAG = "CombinedCapture"
        private const val NOTIFICATION_CHANNEL_ID = "combined_capture"
        private const val NOTIFICATION_ID = 7_002
        private const val ACCEL_SAMPLING_PERIOD_US = 10_000
        private const val RATE_WINDOW_NS = 10_000_000_000L
        private const val NS_PER_SECOND = 1_000_000_000.0
        private const val LOG_BUFFER_SIZE = 64 * 1024
        private const val LOG_FLUSH_RECORDS = 200
        private val FILE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withLocale(Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}
