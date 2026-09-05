package dev.vehiclespeed.gnssprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.sqrt

class CombinedCaptureActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var gpsSpeedText: TextView
    private lateinit var gpsRateText: TextView
    private lateinit var gpsAgeText: TextView
    private lateinit var gpsAccuracyText: TextView
    private lateinit var estimatedSpeedText: TextView
    private lateinit var estimatorModeText: TextView
    private lateinit var estimatorRateText: TextView
    private lateinit var longitudinalAccelText: TextView
    private lateinit var accelBiasText: TextView
    private lateinit var innovationText: TextView
    private lateinit var speedUncertaintyText: TextView
    private lateinit var calibrationText: TextView
    private lateinit var mountStateText: TextView
    private lateinit var accelRateText: TextView
    private lateinit var accelVectorText: TextView
    private lateinit var accelMagnitudeText: TextView
    private lateinit var countsText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContentView())
    }

    override fun onStart() {
        super.onStart()
        handler.post(refreshRunnable)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (hasFineLocationPermission()) startCapture()
            else statusText.text = "Precise location permission is required"
        }
    }

    private fun createContentView(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        content.addView(TextView(this).apply {
            text = "COMBINED SENSOR CAPTURE"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Direct GPS plus a provisional 100 Hz velocity/bias estimator"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })

        statusText = addMetric(content, "STATUS", "Ready")
        gpsSpeedText = addMetric(content, "RAW GPS SPEED", "—")
        gpsRateText = addMetric(content, "GPS FIX RATE (10 s)", "—")
        gpsAgeText = addMetric(content, "GPS FIX AGE", "—")
        gpsAccuracyText = addMetric(content, "GPS SPEED ACCURACY", "—")
        estimatedSpeedText = addMetric(content, "ESTIMATED SPEED", "Waiting for GPS initialization")
        estimatorModeText = addMetric(content, "ESTIMATOR MODE", "UNINITIALIZED")
        estimatorRateText = addMetric(content, "ESTIMATOR PROPAGATION RATE", "—")
        longitudinalAccelText = addMetric(content, "LONGITUDINAL ACCELERATION", "—")
        accelBiasText = addMetric(content, "EFFECTIVE ACCELERATION BIAS", "—")
        innovationText = addMetric(content, "LAST GPS INNOVATION", "—")
        speedUncertaintyText = addMetric(content, "ESTIMATED SPEED SIGMA", "—")
        calibrationText = addMetric(content, "VEHICLE-FRAME CALIBRATION", "—")
        mountStateText = addMetric(content, "MOUNT CALIBRATION STATE", "READY")
        accelRateText = addMetric(content, "ACCELEROMETER RATE (10 s)", "—")
        accelVectorText = addMetric(content, "ACCELEROMETER X / Y / Z", "—")
        accelMagnitudeText = addMetric(content, "ACCELEROMETER MAGNITUDE", "—")
        countsText = addMetric(content, "SESSION COUNTS", "0 GPS / 0 accelerometer")
        logText = addMetric(content, "COMBINED SESSION CSV", "Created when capture starts")

        actionButton = Button(this).apply {
            text = "START COMBINED CAPTURE"
            textSize = 18f
            setOnClickListener {
                if (CombinedCaptureRuntime.snapshot.active) stopCapture() else requestStart()
            }
        }
        content.addView(
            actionButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        content.addView(TextView(this).apply {
            text = "Raw GPS and estimated speed remain separate. If the tablet moves while " +
                "parked, speed is held at zero until its orientation stabilizes and the mount " +
                "transform is adjusted. The CSV contains no latitude or longitude."
            textSize = 13f
            setPadding(0, dp(14), 0, dp(8))
        })

        return ScrollView(this).apply { addView(content) }
    }

    private fun addMetric(parent: LinearLayout, label: String, initialValue: String): TextView {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, 0)
        })
        return TextView(this).also { value ->
            value.text = initialValue
            value.textSize = 19f
            value.gravity = Gravity.START
            parent.addView(value)
        }
    }

    private fun requestStart() {
        if (!hasFineLocationPermission()) {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST)
            return
        }
        startCapture()
    }

    private fun startCapture() {
        val intent = Intent(this, CombinedCaptureService::class.java).apply {
            action = CombinedCaptureService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopCapture() {
        stopService(Intent(this, CombinedCaptureService::class.java))
    }

    private fun refreshUi() {
        val snapshot = CombinedCaptureRuntime.snapshot
        statusText.text = snapshot.status
        gpsSpeedText.text = snapshot.gpsSpeedMps?.let {
            format("%.2f mph  (%.3f m/s)", it * MPS_TO_MPH, it)
        } ?: "Waiting for GPS speed"
        gpsRateText.text = formatRate(snapshot.gpsRateHz, snapshot.gpsCount)
        gpsAgeText.text = if (snapshot.lastGpsElapsedNs > 0L) {
            val ageMs = (SystemClock.elapsedRealtimeNanos() - snapshot.lastGpsElapsedNs)
                .coerceAtLeast(0L) / NS_PER_MS
            format("%.0f ms", ageMs)
        } else {
            "Waiting for GPS fix"
        }
        gpsAccuracyText.text = snapshot.gpsSpeedAccuracyMps?.let {
            format("±%.2f mph  (±%.3f m/s)", it * MPS_TO_MPH, it)
        } ?: "Not supplied"
        estimatedSpeedText.text = snapshot.estimatedSpeedMps?.let {
            format("%.2f mph  (%.3f m/s)", it * MPS_TO_MPH, it)
        } ?: "Waiting for GPS initialization"
        estimatorModeText.text = snapshot.estimatorMode.name
        estimatorRateText.text = if (snapshot.estimatorRateHz > 0.0) {
            format("%.2f Hz", snapshot.estimatorRateHz)
        } else {
            "Waiting for GPS initialization"
        }
        longitudinalAccelText.text = if (snapshot.accelCount > 0L) {
            format(
                "%+.3f m/s² raw / %+.3f corrected",
                snapshot.longitudinalAccelerationMps2,
                snapshot.correctedAccelerationMps2,
            )
        } else {
            "Waiting for accelerometer"
        }
        accelBiasText.text = if (snapshot.estimatedSpeedMps != null) {
            format("%+.4f m/s²", snapshot.estimatedAccelBiasMps2)
        } else {
            "Waiting for GPS initialization"
        }
        innovationText.text = snapshot.innovationMps?.let {
            format("%+.2f mph  (%+.3f m/s)", it * MPS_TO_MPH, it)
        } ?: "No GPS correction yet"
        speedUncertaintyText.text = snapshot.stateVarianceSpeedMps2?.let {
            val sigma = sqrt(it.coerceAtLeast(0.0))
            format("±%.2f mph  (±%.3f m/s)", sigma * MPS_TO_MPH, sigma)
        } ?: "Waiting for GPS initialization"
        calibrationText.text = snapshot.calibrationName
        mountStateText.text = format(
            "%s • %.1f° change • %d adjustment%s",
            snapshot.mountCalibrationMode.name,
            snapshot.mountOrientationChangeDegrees,
            snapshot.mountRecalibrationCount,
            if (snapshot.mountHoldSpeedAtZero) " • HOLDING ZERO" else "",
        )
        accelRateText.text = formatRate(snapshot.accelRateHz, snapshot.accelCount)
        accelVectorText.text = if (snapshot.accelCount > 0L) {
            format(
                "%+.4f / %+.4f / %+.4f m/s²",
                snapshot.accelX,
                snapshot.accelY,
                snapshot.accelZ,
            )
        } else {
            "Waiting for accelerometer"
        }
        accelMagnitudeText.text = if (snapshot.accelCount > 0L) {
            format("%.4f m/s²", snapshot.accelMagnitude)
        } else {
            "—"
        }
        countsText.text = "${snapshot.gpsCount} GPS / ${snapshot.accelCount} accelerometer"
        logText.text = snapshot.logPath.ifBlank { "Created when capture starts" }
        actionButton.text = if (snapshot.active) {
            "STOP AND SAVE CAPTURE"
        } else {
            "START COMBINED CAPTURE"
        }
    }

    private fun formatRate(rateHz: Double, count: Long): String =
        if (count >= 2L && rateHz > 0.0) format("%.2f Hz", rateHz) else "Waiting for samples"

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun format(pattern: String, vararg values: Any): String =
        String.format(Locale.US, pattern, *values)

    companion object {
        private const val PERMISSION_REQUEST = 300
        private const val UI_REFRESH_MS = 100L
        private const val MPS_TO_MPH = 2.2369363
        private const val NS_PER_MS = 1_000_000.0
    }
}
