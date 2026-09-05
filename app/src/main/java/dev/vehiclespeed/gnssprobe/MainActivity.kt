package dev.vehiclespeed.gnssprobe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale

class MainActivity : Activity() {
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private lateinit var statusText: TextView
    private lateinit var speedText: TextView
    private lateinit var fixRateText: TextView
    private lateinit var callbackRateText: TextView
    private lateinit var fixDeltaText: TextView
    private lateinit var ageText: TextView
    private lateinit var speedAccuracyText: TextView
    private lateinit var horizontalAccuracyText: TextView
    private lateinit var satellitesText: TextView
    private lateinit var countsText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: Button

    private val callbackArrivalsNs = ArrayDeque<Long>()
    private val uniqueFixTimesNs = ArrayDeque<Long>()
    private var callbackCount = 0L
    private var uniqueFixCount = 0L
    private var duplicateTimestampCount = 0L
    private var lastFixElapsedNs: Long? = null
    private var running = false
    private var logWriter: BufferedWriter? = null
    private var logFile: File? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            statusText.text = "Waiting for a new GPS fix"
        }

        override fun onProviderDisabled(provider: String) {
            statusText.text = "GPS provider is disabled"
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            statusText.text = "GNSS started; waiting for a fix"
        }

        override fun onStopped() {
            if (running) statusText.text = "GNSS stopped"
        }

        override fun onFirstFix(ttffMillis: Int) {
            statusText.text = "GPS fix acquired in ${ttffMillis} ms"
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (index in 0 until status.satelliteCount) {
                if (status.usedInFix(index)) used++
            }
            satellitesText.text = "$used used / ${status.satelliteCount} visible"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContentView())
        refreshPermissionState()
    }

    override fun onStop() {
        super.onStop()
        if (running) stopTest("Stopped because the app left the foreground")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) refreshPermissionState()
    }

    private fun createContentView(): ScrollView {
        val padding = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = "GNSS RATE PROBE"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Direct Android GPS-provider speed characterization"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        })
        content.addView(Button(this).apply {
            text = "OPEN ACCELEROMETER TEST"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AccelerometerProbeActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(12)
        })
        content.addView(Button(this).apply {
            text = "OPEN COMBINED SENSOR CAPTURE"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, CombinedCaptureActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(12)
        })
        content.addView(Button(this).apply {
            text = "OPEN LIVE REALDASH OUTPUT"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, RealDashLiveActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(12)
        })
        content.addView(Button(this).apply {
            text = "OPEN REALDASH CONNECTION TEST"
            textSize = 16f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, RealDashTestActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(12)
        })

        statusText = addMetric(content, "STATUS", "Ready")
        speedText = addMetric(content, "RAW GPS SPEED", "—")
        fixRateText = addMetric(content, "UNIQUE FIX RATE (30 s)", "—")
        callbackRateText = addMetric(content, "CALLBACK RATE (30 s)", "—")
        fixDeltaText = addMetric(content, "LAST UNIQUE FIX INTERVAL", "—")
        ageText = addMetric(content, "FIX AGE ON ARRIVAL", "—")
        speedAccuracyText = addMetric(content, "SPEED ACCURACY", "—")
        horizontalAccuracyText = addMetric(content, "HORIZONTAL ACCURACY", "—")
        satellitesText = addMetric(content, "SATELLITES", "—")
        countsText = addMetric(content, "SESSION COUNTS", "0 callbacks / 0 unique fixes")
        logText = addMetric(content, "SESSION CSV", "Created when the test starts")

        actionButton = Button(this).apply {
            text = "START TEST"
            textSize = 18f
            isAllCaps = true
            setOnClickListener { onActionButtonPressed() }
        }
        content.addView(
            actionButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        content.addView(TextView(this).apply {
            text = "Start and stop only while parked. Keep this screen visible during the test. " +
                "The CSV contains timing and speed data but no coordinates."
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
            value.textSize = 20f
            value.gravity = Gravity.START
            parent.addView(value)
        }
    }

    private fun onActionButtonPressed() {
        if (running) {
            stopTest("Test stopped; reconnect by USB to collect the CSV")
            return
        }

        if (!hasFineLocationPermission()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                LOCATION_PERMISSION_REQUEST,
            )
            return
        }

        startTest()
    }

    private fun startTest() {
        resetSession()

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            statusText.text = "Enable Location/GPS, then start again"
            startActivity(android.content.Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        try {
            openLog()
            registerGnssStatus()
            requestFastGpsUpdates()
        } catch (securityException: SecurityException) {
            closeLog()
            statusText.text = "Precise location permission is required"
            refreshPermissionState()
            return
        } catch (exception: IOException) {
            closeLog()
            statusText.text = "Could not create session CSV: ${exception.message}"
            return
        }

        running = true
        actionButton.text = "STOP TEST"
        statusText.text = "GNSS requested; move outdoors and wait for a fix"
    }

    private fun stopTest(message: String) {
        if (!running) return
        locationManager.removeUpdates(locationListener)
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        closeLog()
        running = false
        actionButton.text = "START NEW TEST"
        statusText.text = message
    }

    @SuppressLint("MissingPermission")
    private fun requestFastGpsUpdates() {
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
                mainExecutor,
                locationListener,
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                locationListener,
                Looper.getMainLooper(),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssStatusCallback(mainExecutor, gnssStatusCallback)
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssStatusCallback(
                gnssStatusCallback,
                Handler(Looper.getMainLooper()),
            )
        }
    }

    private fun handleLocation(location: Location) {
        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        val fixNs = location.elapsedRealtimeNanos
        callbackCount++
        addToWindow(callbackArrivalsNs, arrivalNs)

        val previousFixNs = lastFixElapsedNs
        val isUniqueFix = previousFixNs == null || fixNs != previousFixNs
        if (isUniqueFix) {
            uniqueFixCount++
            addToWindow(uniqueFixTimesNs, fixNs)
            lastFixElapsedNs = fixNs
            writeLocation(location, arrivalNs)
        } else {
            duplicateTimestampCount++
        }

        val speed = if (location.hasSpeed()) {
            format("%.2f mph  (%.3f m/s)", location.speed * MPS_TO_MPH, location.speed)
        } else {
            "Not supplied on this fix"
        }
        speedText.text = speed
        fixRateText.text = formatRate(uniqueFixTimesNs)
        callbackRateText.text = formatRate(callbackArrivalsNs)
        fixDeltaText.text = if (isUniqueFix && previousFixNs != null) {
            format("%.1f ms", (fixNs - previousFixNs) / NS_PER_MS)
        } else if (!isUniqueFix) {
            "Duplicate fix timestamp"
        } else {
            "Waiting for the second fix"
        }
        ageText.text = format("%.1f ms", (arrivalNs - fixNs).coerceAtLeast(0L) / NS_PER_MS)
        speedAccuracyText.text = if (location.hasSpeedAccuracy()) {
            format("±%.2f mph  (±%.3f m/s)",
                location.speedAccuracyMetersPerSecond * MPS_TO_MPH,
                location.speedAccuracyMetersPerSecond,
            )
        } else {
            "Not supplied"
        }
        horizontalAccuracyText.text = if (location.hasAccuracy()) {
            format("±%.1f m", location.accuracy)
        } else {
            "Not supplied"
        }
        countsText.text = "$callbackCount callbacks / $uniqueFixCount unique fixes / " +
            "$duplicateTimestampCount duplicate timestamps"
        statusText.text = if (location.hasSpeed()) {
            "Receiving direct GPS-provider speed"
        } else {
            "Receiving GPS fixes without speed"
        }
    }

    private fun addToWindow(window: ArrayDeque<Long>, timestampNs: Long) {
        window.addLast(timestampNs)
        val cutoff = timestampNs - RATE_WINDOW_NS
        while (window.size > 2 && window.first() < cutoff) window.removeFirst()
    }

    private fun formatRate(window: ArrayDeque<Long>): String {
        if (window.size < 2) return "Waiting for more samples"
        val durationSeconds = (window.last() - window.first()) / NS_PER_SECOND
        if (durationSeconds <= 0.0) return "—"
        return format("%.3f Hz  (%d samples)", (window.size - 1) / durationSeconds, window.size)
    }

    @Throws(IOException::class)
    private fun openLog() {
        val documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw IOException("external files directory unavailable")
        val directory = File(documents, "gnss-sessions")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("could not create ${directory.absolutePath}")
        }
        val timestamp = FILE_TIME_FORMAT.format(Instant.now())
        logFile = File(directory, "gnss-$timestamp.csv")
        logWriter = BufferedWriter(FileWriter(logFile, false)).apply {
            write(
                "arrival_elapsed_ns,fix_elapsed_ns,fix_utc_ms,provider," +
                    "has_speed,speed_mps,has_speed_accuracy,speed_accuracy_mps," +
                    "horizontal_accuracy_m,bearing_deg,bearing_accuracy_deg\n",
            )
            flush()
        }
        logText.text = logFile?.absolutePath ?: "—"
    }

    private fun writeLocation(location: Location, arrivalNs: Long) {
        val writer = logWriter ?: return
        try {
            writer.write(
                listOf(
                    arrivalNs,
                    location.elapsedRealtimeNanos,
                    location.time,
                    location.provider ?: "",
                    location.hasSpeed(),
                    if (location.hasSpeed()) location.speed else "",
                    location.hasSpeedAccuracy(),
                    if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else "",
                    if (location.hasAccuracy()) location.accuracy else "",
                    if (location.hasBearing()) location.bearing else "",
                    if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else "",
                ).joinToString(","),
            )
            writer.newLine()
            writer.flush()
        } catch (exception: IOException) {
            statusText.text = "GPS continues, but CSV logging failed: ${exception.message}"
            closeLog()
        }
    }

    private fun closeLog() {
        try {
            logWriter?.close()
        } catch (_: IOException) {
            // The on-screen measurements remain usable even if closing the log fails.
        } finally {
            logWriter = null
        }
    }

    private fun resetSession() {
        callbackArrivalsNs.clear()
        uniqueFixTimesNs.clear()
        callbackCount = 0L
        uniqueFixCount = 0L
        duplicateTimestampCount = 0L
        lastFixElapsedNs = null
        speedText.text = "—"
        fixRateText.text = "—"
        callbackRateText.text = "—"
        fixDeltaText.text = "—"
        ageText.text = "—"
        speedAccuracyText.text = "—"
        horizontalAccuracyText.text = "—"
        satellitesText.text = "—"
        countsText.text = "0 callbacks / 0 unique fixes"
    }

    private fun refreshPermissionState() {
        if (hasFineLocationPermission()) {
            statusText.text = "Ready; precise location permission granted"
            actionButton.text = "START TEST"
        } else {
            statusText.text = "Precise location permission is required"
            actionButton.text = "GRANT LOCATION"
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun format(pattern: String, vararg values: Any): String =
        String.format(Locale.US, pattern, *values)

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 100
        private const val MPS_TO_MPH = 2.2369363f
        private const val NS_PER_MS = 1_000_000.0
        private const val NS_PER_SECOND = 1_000_000_000.0
        private const val RATE_WINDOW_NS = 30_000_000_000L
        private val FILE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withLocale(Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}
