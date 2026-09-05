package dev.vehiclespeed.gnssprobe

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
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
import kotlin.math.sqrt

class AccelerometerProbeActivity : Activity(), SensorEventListener {
    private val sensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val accelerometer by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private lateinit var statusText: TextView
    private lateinit var sensorText: TextView
    private lateinit var requestedRateText: TextView
    private lateinit var actualRateText: TextView
    private lateinit var intervalText: TextView
    private lateinit var delayText: TextView
    private lateinit var xText: TextView
    private lateinit var yText: TextView
    private lateinit var zText: TextView
    private lateinit var magnitudeText: TextView
    private lateinit var meanVectorText: TextView
    private lateinit var noiseText: TextView
    private lateinit var countText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: Button

    private val rateButtons = mutableListOf<Button>()
    private val rollingTimestampsNs = ArrayDeque<Long>()
    private val intervalStatsMs = RunningStats()
    private val delayStatsMs = RunningStats()
    private val xStats = RunningStats()
    private val yStats = RunningStats()
    private val zStats = RunningStats()

    private var selectedRateHz = 100
    private var running = false
    private var eventCount = 0L
    private var firstTimestampNs: Long? = null
    private var lastTimestampNs: Long? = null
    private var lastUiUpdateNs = 0L
    private var sensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var logWriter: BufferedWriter? = null
    private var logFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(createContentView())
        showSensorInformation()
    }

    override fun onStop() {
        super.onStop()
        if (running) stopTest("Stopped because the app left the foreground")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        val timestampNs = event.timestamp
        val previousTimestampNs = lastTimestampNs
        if (firstTimestampNs == null) firstTimestampNs = timestampNs
        if (previousTimestampNs != null && timestampNs > previousTimestampNs) {
            intervalStatsMs.add((timestampNs - previousTimestampNs) / NS_PER_MS)
        }
        lastTimestampNs = timestampNs
        eventCount++

        rollingTimestampsNs.addLast(timestampNs)
        val cutoff = timestampNs - RATE_WINDOW_NS
        while (rollingTimestampsNs.size > 2 && rollingTimestampsNs.first() < cutoff) {
            rollingTimestampsNs.removeFirst()
        }

        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val magnitude = sqrt(x * x + y * y + z * z)
        val deliveryDelayMs = (arrivalNs - timestampNs).coerceAtLeast(0L) / NS_PER_MS

        delayStatsMs.add(deliveryDelayMs)
        xStats.add(x)
        yStats.add(y)
        zStats.add(z)
        writeEvent(arrivalNs, timestampNs, x, y, z, magnitude)

        if (arrivalNs - lastUiUpdateNs >= UI_UPDATE_NS) {
            lastUiUpdateNs = arrivalNs
            updateLiveDisplay(x, y, z, magnitude)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ACCELEROMETER) sensorAccuracy = accuracy
    }

    private fun createContentView(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        content.addView(TextView(this).apply {
            text = "ACCELEROMETER PROBE"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Actual rate, timing, gravity vector, and stationary noise"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        })

        statusText = addMetric(content, "STATUS", "Ready")
        sensorText = addMetric(content, "SENSOR", "Checking…")
        requestedRateText = addMetric(content, "REQUESTED RATE", "$selectedRateHz Hz")

        content.addView(TextView(this).apply {
            text = "SELECT TEST RATE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            RATE_OPTIONS_HZ.forEach { rate ->
                addView(createRateButton(rate), LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
            }
        })

        actualRateText = addMetric(content, "ACTUAL EVENT RATE (5 s)", "—")
        intervalText = addMetric(content, "SENSOR INTERVAL / JITTER", "—")
        delayText = addMetric(content, "DELIVERY DELAY", "—")
        xText = addMetric(content, "ACCEL X", "—")
        yText = addMetric(content, "ACCEL Y", "—")
        zText = addMetric(content, "ACCEL Z", "—")
        magnitudeText = addMetric(content, "MAGNITUDE", "—")
        meanVectorText = addMetric(content, "SESSION MEAN / GRAVITY VECTOR", "—")
        noiseText = addMetric(content, "SESSION AXIS STD DEV", "—")
        countText = addMetric(content, "SESSION SAMPLES", "0")
        logText = addMetric(content, "SESSION CSV", "Created when the test starts")

        actionButton = Button(this).apply {
            text = "START TEST"
            textSize = 18f
            setOnClickListener {
                if (running) stopTest("Test stopped; reconnect by USB to collect the CSV")
                else startTest()
            }
        }
        content.addView(actionButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) })

        content.addView(TextView(this).apply {
            text = "For a noise test, leave the tablet completely still for 60 seconds. " +
                "Use a separate session for deliberate tilts or gentle movements."
            textSize = 13f
            setPadding(0, dp(14), 0, dp(8))
        })

        updateRateButtons()
        return ScrollView(this).apply { addView(content) }
    }

    private fun createRateButton(rateHz: Int): Button = Button(this).also { button ->
        button.textSize = 14f
        button.setOnClickListener {
            if (!running) {
                selectedRateHz = rateHz
                requestedRateText.text = "$selectedRateHz Hz"
                updateRateButtons()
            }
        }
        rateButtons.add(button)
    }

    private fun updateRateButtons() {
        rateButtons.forEachIndexed { index, button ->
            val rate = RATE_OPTIONS_HZ[index]
            button.text = if (rate == selectedRateHz) "✓ $rate Hz" else "$rate Hz"
            button.isEnabled = !running
            button.alpha = if (rate == selectedRateHz) 1f else 0.65f
        }
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

    private fun showSensorInformation() {
        val sensor = accelerometer
        if (sensor == null) {
            statusText.text = "No accelerometer is exposed by Android"
            sensorText.text = "Unavailable"
            actionButton.isEnabled = false
            return
        }

        val fastestHz = if (sensor.minDelay > 0) 1_000_000.0 / sensor.minDelay else 0.0
        val slowestHz = if (sensor.maxDelay > 0) 1_000_000.0 / sensor.maxDelay else 0.0
        sensorText.text = format(
            "%s / %s\nadvertised %.2f–%.1f Hz, resolution %.6f m/s²",
            sensor.name,
            sensor.vendor,
            slowestHz,
            fastestHz,
            sensor.resolution,
        )
    }

    private fun startTest() {
        val sensor = accelerometer ?: return
        resetSession()

        try {
            openLog()
        } catch (exception: IOException) {
            statusText.text = "Could not create session CSV: ${exception.message}"
            return
        }

        running = true
        val samplingPeriodUs = 1_000_000 / selectedRateHz
        val registered = sensorManager.registerListener(
            this,
            sensor,
            samplingPeriodUs,
            0,
        )
        if (!registered) {
            running = false
            closeLog()
            statusText.text = "Android rejected the accelerometer request"
            return
        }

        updateRateButtons()
        actionButton.text = "STOP TEST"
        statusText.text = "Collecting at requested $selectedRateHz Hz; keep tablet still"
    }

    private fun stopTest(message: String) {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        closeLog()
        updateRateButtons()
        actionButton.text = "START NEW TEST"
        statusText.text = message
    }

    private fun updateLiveDisplay(x: Double, y: Double, z: Double, magnitude: Double) {
        actualRateText.text = if (rollingTimestampsNs.size >= 2) {
            val durationSeconds =
                (rollingTimestampsNs.last() - rollingTimestampsNs.first()) / NS_PER_SECOND
            val rollingRate = (rollingTimestampsNs.size - 1) / durationSeconds
            val first = firstTimestampNs
            val last = lastTimestampNs
            val sessionRate = if (first != null && last != null && last > first) {
                (eventCount - 1) / ((last - first) / NS_PER_SECOND)
            } else {
                0.0
            }
            format("%.2f Hz rolling / %.2f Hz session", rollingRate, sessionRate)
        } else {
            "Waiting for samples"
        }

        intervalText.text = if (intervalStatsMs.count > 0) {
            format(
                "%.3f ms mean / %.3f ms σ / %.3f ms max",
                intervalStatsMs.mean,
                intervalStatsMs.standardDeviation,
                intervalStatsMs.maximum,
            )
        } else {
            "Waiting for samples"
        }
        delayText.text = if (delayStatsMs.count > 0) {
            format("%.3f ms mean / %.3f ms max", delayStatsMs.mean, delayStatsMs.maximum)
        } else {
            "Waiting for samples"
        }
        xText.text = format("%+.5f m/s²", x)
        yText.text = format("%+.5f m/s²", y)
        zText.text = format("%+.5f m/s²", z)
        magnitudeText.text = format("%.5f m/s²", magnitude)

        val meanMagnitude = sqrt(
            xStats.mean * xStats.mean + yStats.mean * yStats.mean + zStats.mean * zStats.mean,
        )
        meanVectorText.text = format(
            "[%+.4f, %+.4f, %+.4f] m/s²  |g| %.4f",
            xStats.mean,
            yStats.mean,
            zStats.mean,
            meanMagnitude,
        )
        noiseText.text = format(
            "σx %.5f / σy %.5f / σz %.5f m/s²",
            xStats.standardDeviation,
            yStats.standardDeviation,
            zStats.standardDeviation,
        )
        countText.text = "$eventCount / ${accuracyLabel(sensorAccuracy)} accuracy"
    }

    @Throws(IOException::class)
    private fun openLog() {
        val documents = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw IOException("external files directory unavailable")
        val directory = File(documents, "accelerometer-sessions")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("could not create ${directory.absolutePath}")
        }
        val timestamp = FILE_TIME_FORMAT.format(Instant.now())
        logFile = File(directory, "accel-${selectedRateHz}hz-$timestamp.csv")
        logWriter = BufferedWriter(FileWriter(logFile, false)).apply {
            write(
                "arrival_elapsed_ns,sensor_timestamp_ns,requested_rate_hz,accuracy," +
                    "accel_x_mps2,accel_y_mps2,accel_z_mps2,magnitude_mps2\n",
            )
            flush()
        }
        logText.text = logFile?.absolutePath ?: "—"
    }

    private fun writeEvent(
        arrivalNs: Long,
        timestampNs: Long,
        x: Double,
        y: Double,
        z: Double,
        magnitude: Double,
    ) {
        val writer = logWriter ?: return
        try {
            writer.write(
                format(
                    "%d,%d,%d,%d,%.8f,%.8f,%.8f,%.8f",
                    arrivalNs,
                    timestampNs,
                    selectedRateHz,
                    sensorAccuracy,
                    x,
                    y,
                    z,
                    magnitude,
                ),
            )
            writer.newLine()
            if (eventCount % LOG_FLUSH_INTERVAL == 0L) writer.flush()
        } catch (exception: IOException) {
            closeLog()
            statusText.text = "Sensor continues, but CSV logging failed: ${exception.message}"
        }
    }

    private fun closeLog() {
        try {
            logWriter?.close()
        } catch (_: IOException) {
            // Live measurements remain valid even if closing the log fails.
        } finally {
            logWriter = null
        }
    }

    private fun resetSession() {
        rollingTimestampsNs.clear()
        intervalStatsMs.reset()
        delayStatsMs.reset()
        xStats.reset()
        yStats.reset()
        zStats.reset()
        eventCount = 0L
        firstTimestampNs = null
        lastTimestampNs = null
        lastUiUpdateNs = 0L
        actualRateText.text = "—"
        intervalText.text = "—"
        delayText.text = "—"
        xText.text = "—"
        yText.text = "—"
        zText.text = "—"
        magnitudeText.text = "—"
        meanVectorText.text = "—"
        noiseText.text = "—"
        countText.text = "0"
    }

    private fun accuracyLabel(accuracy: Int): String = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
        else -> "unreliable/unknown"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun format(pattern: String, vararg values: Any): String =
        String.format(Locale.US, pattern, *values)

    private class RunningStats {
        var count: Long = 0
            private set
        var mean: Double = 0.0
            private set
        private var squaredDifferenceSum: Double = 0.0
        var minimum: Double = Double.POSITIVE_INFINITY
            private set
        var maximum: Double = Double.NEGATIVE_INFINITY
            private set

        val standardDeviation: Double
            get() = if (count > 0) sqrt(squaredDifferenceSum / count) else 0.0

        fun add(value: Double) {
            count++
            val delta = value - mean
            mean += delta / count
            squaredDifferenceSum += delta * (value - mean)
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
        }

        fun reset() {
            count = 0
            mean = 0.0
            squaredDifferenceSum = 0.0
            minimum = Double.POSITIVE_INFINITY
            maximum = Double.NEGATIVE_INFINITY
        }
    }

    companion object {
        private val RATE_OPTIONS_HZ = intArrayOf(50, 100, 200)
        private const val NS_PER_MS = 1_000_000.0
        private const val NS_PER_SECOND = 1_000_000_000.0
        private const val RATE_WINDOW_NS = 5_000_000_000L
        private const val UI_UPDATE_NS = 100_000_000L
        private const val LOG_FLUSH_INTERVAL = 200L
        private val FILE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withLocale(Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}
