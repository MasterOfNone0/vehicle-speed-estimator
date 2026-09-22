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
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class RealDashLiveActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var connectionText: TextView
    private lateinit var estimatedSpeedText: TextView
    private lateinit var rawGpsSpeedText: TextView
    private lateinit var gpsAgeText: TextView
    private lateinit var modeText: TextView
    private lateinit var validityText: TextView
    private lateinit var mountStateText: TextView
    private lateinit var flagsText: TextView
    private lateinit var packetCountText: TextView
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
            if (hasFineLocationPermission()) startLiveOutput()
            else statusText.text = "Precise location permission is required"
        }
    }

    private fun createContentView(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        content.addView(TextView(this).apply {
            text = "LIVE REALDASH OUTPUT"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "GNSS + accelerometer estimator published to RealDash at 60 Hz"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })

        statusText = addMetric(content, "STATUS", "Stopped")
        connectionText = addMetric(content, "REALDASH CONNECTION", "Not connected")
        addMetric(
            content,
            "REALDASH ADDRESS",
            "${RealDashLiveService.LOOPBACK_ADDRESS}:${RealDashLiveService.PORT}",
        )
        addMetric(content, "REALDASH-CAN FRAME", "0x700 at 60 Hz")
        estimatedSpeedText = addMetric(content, "PUBLISHED SPEED", "—")
        rawGpsSpeedText = addMetric(content, "RAW GPS SPEED", "—")
        gpsAgeText = addMetric(content, "RAW GPS AGE", "—")
        modeText = addMetric(content, "ESTIMATOR MODE", "UNINITIALIZED (0)")
        validityText = addMetric(content, "OUTPUT SOURCE", "UNAVAILABLE")
        mountStateText = addMetric(content, "MOUNT CALIBRATION", "READY")
        flagsText = addMetric(content, "FRAME FLAGS", "0x08")
        packetCountText = addMetric(content, "PACKETS SENT", "0")
        logText = addMetric(content, "COMBINED SESSION CSV", "Created when output starts")

        actionButton = Button(this).apply {
            text = "START LIVE OUTPUT"
            textSize = 18f
            setOnClickListener {
                if (isSessionActive()) stopLiveOutput() else requestStart()
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
            text = "Start while safely parked, then switch to RealDash. Use the existing " +
                "127.0.0.1:35000 connection, XML, and Estimated Speed gauge binding. " +
                "Wait for GPS; park with the tablet mounted for fusion. GPS is used during recovery. Return here to stop " +
                "both publishing and CSV capture."
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
        startLiveOutput()
    }

    private fun startLiveOutput() {
        stopService(Intent(this, RealDashTestService::class.java))
        startForeground(
            Intent(this, CombinedCaptureService::class.java).apply {
                action = CombinedCaptureService.ACTION_START
            },
        )
        startForeground(
            Intent(this, RealDashLiveService::class.java).apply {
                action = RealDashLiveService.ACTION_START
            },
        )
    }

    private fun startForeground(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopLiveOutput() {
        stopService(Intent(this, RealDashLiveService::class.java))
        stopService(Intent(this, CombinedCaptureService::class.java))
    }

    private fun isSessionActive(): Boolean =
        RealDashLiveRuntime.snapshot.active || CombinedCaptureRuntime.snapshot.active

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshUi() {
        val live = RealDashLiveRuntime.snapshot
        val capture = CombinedCaptureRuntime.snapshot
        statusText.text = live.status
        connectionText.text = if (live.connected) "Connected" else "Not connected"
        estimatedSpeedText.text = formatSpeed(live.estimatedSpeedKph)
        rawGpsSpeedText.text = formatSpeed(live.rawGpsSpeedKph)
        gpsAgeText.text = if (live.gpsAgeMs < 65_535) "${live.gpsAgeMs} ms" else "Unavailable"
        modeText.text = "${capture.estimatorMode.name} (${live.estimatorModeCode})"
        validityText.text = "${live.source} • ${if (live.valid) "VALID" else "UNAVAILABLE"}"
        mountStateText.text = format(
            "%s • %.1f° • %d adjustment%s",
            if (capture.fusionReady) capture.mountCalibrationMode.name else "WAITING FOR STABLE MOUNT",
            capture.mountOrientationChangeDegrees,
            capture.mountRecalibrationCount,
            if (capture.mountHoldSpeedAtZero) " • HOLDING ZERO" else "",
        )
        flagsText.text = format("0x%02X", live.flags)
        packetCountText.text = live.packetsSent.toString()
        logText.text = capture.logPath.ifBlank { "Created when output starts" }
        actionButton.text = if (isSessionActive()) "STOP LIVE OUTPUT" else "START LIVE OUTPUT"
    }

    private fun formatSpeed(speedKph: Double): String = format(
        "%.2f mph  (%.2f km/h)",
        speedKph / MPH_TO_KPH,
        speedKph,
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun format(pattern: String, vararg values: Any): String =
        String.format(Locale.US, pattern, *values)

    companion object {
        private const val PERMISSION_REQUEST = 300
        private const val UI_REFRESH_MS = 100L
        private const val MPH_TO_KPH = 1.609344
    }
}
