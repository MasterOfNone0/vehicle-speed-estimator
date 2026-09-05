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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class RealDashTestActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var estimatedSpeedText: TextView
    private lateinit var rawGpsSpeedText: TextView
    private lateinit var packetCountText: TextView
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
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) startPublisher()
    }

    private fun createContentView(): ScrollView {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = "REALDASH LOOPBACK TEST"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Scripted drive estimate at 60 Hz and simulated raw GPS at 1 Hz"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })

        statusText = addMetric(content, "STATUS", "Publisher stopped")
        addMetric(
            content,
            "REALDASH ADDRESS",
            "${RealDashTestService.LOOPBACK_ADDRESS}:${RealDashTestService.PORT}",
        )
        addMetric(content, "REALDASH-CAN FRAME", "0x700")
        estimatedSpeedText = addMetric(content, "SIMULATED ESTIMATE", "0.00 mph")
        rawGpsSpeedText = addMetric(content, "SIMULATED 1 HZ GPS", "0.00 mph")
        packetCountText = addMetric(content, "PACKETS SENT", "0")

        actionButton = Button(this).apply {
            textSize = 18f
            setOnClickListener {
                if (RealDashTestRuntime.snapshot.active) stopPublisher() else requestStart()
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
            text = "Start the publisher, leave it running, and switch to RealDash. " +
                "The persistent notification keeps this test alive while RealDash is visible. " +
                "Return here and stop it when finished."
            textSize = 14f
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
            value.textSize = 20f
            value.gravity = Gravity.START
            parent.addView(value)
        }
    }

    private fun requestStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        } else {
            startPublisher()
        }
    }

    private fun startPublisher() {
        // Both publishers intentionally use the same RealDash connection and CAN ID.
        stopService(Intent(this, RealDashLiveService::class.java))
        stopService(Intent(this, CombinedCaptureService::class.java))
        val intent = Intent(this, RealDashTestService::class.java).apply {
            action = RealDashTestService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopPublisher() {
        stopService(Intent(this, RealDashTestService::class.java))
    }

    private fun refreshUi() {
        val snapshot = RealDashTestRuntime.snapshot
        statusText.text = snapshot.status
        estimatedSpeedText.text = format("%.2f mph  (%.2f km/h)", snapshot.estimatedSpeedKph / MPH_TO_KPH, snapshot.estimatedSpeedKph)
        rawGpsSpeedText.text = format("%.2f mph  (%.2f km/h)", snapshot.rawGpsSpeedKph / MPH_TO_KPH, snapshot.rawGpsSpeedKph)
        packetCountText.text = snapshot.packetsSent.toString()
        actionButton.text = if (snapshot.active) "STOP PUBLISHER" else "START PUBLISHER"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun format(pattern: String, vararg values: Any): String =
        String.format(Locale.US, pattern, *values)

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 200
        private const val UI_REFRESH_MS = 100L
        private const val MPH_TO_KPH = 1.609344
    }
}
