package dev.vehiclespeed.gnssprobe.estimator

import dev.vehiclespeed.gnssprobe.RealDashLiveInput
import dev.vehiclespeed.gnssprobe.RealDashLiveTelemetrySelector
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Optional local replay; never embed a private capture in test fixtures. */
class DriveCaptureReplayTest {
    @Test fun replaysPrivateDriveCaptureWhenProvided() {
        val source = System.getenv("DRIVE_CAPTURE_CSV")?.let(::File)
        assumeTrue("Set DRIVE_CAPTURE_CSV to run private telemetry", source?.isFile == true)
        val controller = GuardedSpeedEstimator()
        var gps: Double? = null
        var sigma: Double? = null
        var lastGps = 0L
        var lastAccepted = 0L
        var rows = 0
        var gpsRows = 0
        var fusedRows = 0
        var maxPublishedMps = 0.0
        var maxGpsMps = 0.0
        source!!.bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            val column = iterator.next().split(',').withIndex().associate { it.value to it.index }
            iterator.forEach { line ->
                val fields = line.split(',')
                fun value(name: String) = fields[column.getValue(name)]
                val time = value("event_elapsed_ns").toLong()
                val arrival = value("arrival_elapsed_ns").toLong()
                val guarded = when (value("record_type")) {
                    "ACCEL" -> controller.onAcceleration(time,
                        value("accel_x_mps2").toDouble(), value("accel_y_mps2").toDouble(),
                        value("accel_z_mps2").toDouble())
                    "GPS" -> {
                        gpsRows++
                        gps = value("gps_speed_mps").toDoubleOrNull()
                        sigma = value("gps_speed_accuracy_mps").toDoubleOrNull()
                        lastGps = time
                        if (SpeedObservation.usable(gps, sigma)) maxGpsMps = maxOf(maxGpsMps, gps!!)
                        controller.onGpsSpeed(time, gps ?: Double.NaN, sigma, arrival).also {
                            if (it.estimator.gpsMeasurementAccepted) lastAccepted = time
                        }
                    }
                    else -> controller.state()
                }
                val state = guarded.estimator
                if (guarded.fusionReady) fusedRows++
                val output = RealDashLiveTelemetrySelector.select(RealDashLiveInput(
                    captureActive = true, nowElapsedNs = arrival, rawGpsSpeedMps = gps,
                    lastRawGpsElapsedNs = lastGps, lastAcceptedGpsElapsedNs = lastAccepted,
                    estimatedSpeedMps = state.velocityMps.takeIf { state.initialized },
                    estimatorTimestampNs = state.timestampNs, estimatorMode = state.mode,
                    mountCalibrationReady = guarded.calibration.mode == MountCalibrationMode.READY,
                    fusionReady = guarded.fusionReady, rawGpsSigmaMps = sigma,
                ))
                assertTrue(state.velocityMps.isFinite() && state.velocityMps in 0.0..150.0)
                assertTrue(state.accelerationBiasMps2.isFinite())
                assertTrue(output.estimatedSpeedKph.isFinite() && output.estimatedSpeedKph in 0.0..540.0)
                if (output.source == "GPS_ONLY") {
                    assertTrue(abs(output.estimatedSpeedKph - gps!! * 3.6) < 1e-8)
                }
                if (!output.valid) assertTrue(output.estimatedSpeedKph == 0.0)
                maxPublishedMps = maxOf(maxPublishedMps, output.estimatedSpeedKph / 3.6)
                rows++
            }
        }
        println("Replay: rows=$rows gps=$gpsRows fusedRows=$fusedRows " +
            "maxGpsMps=$maxGpsMps maxPublishedMps=$maxPublishedMps recoveries=${controller.state().recoveryCount}")
        assertTrue(rows > 0 && gpsRows > 0)
        assertTrue(maxPublishedMps <= maxGpsMps + 30.0)
    }
}
