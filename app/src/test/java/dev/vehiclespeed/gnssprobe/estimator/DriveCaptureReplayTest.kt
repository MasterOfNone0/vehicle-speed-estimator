package dev.vehiclespeed.gnssprobe.estimator

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class DriveCaptureReplayTest {
    @Test
    fun replaysPrivateDriveCaptureWhenProvided() {
        val source = System.getenv("DRIVE_CAPTURE_CSV")?.let(::File)
        assumeTrue("Set DRIVE_CAPTURE_CSV to run the private-log replay", source?.isFile == true)

        val calibrationManager = MountCalibrationManager()
        val filter = VelocityBiasKalmanFilter()
        var gpsRows = 0
        var acceptedGpsRows = 0
        var initializedAccelRows = 0
        var maximumGpsSpeedMps = 0.0
        var maximumEstimatedSpeedMps = 0.0
        var mountHoldRows = 0
        var maximumHeldSpeedMps = 0.0
        var lastGpsSpeedMps: Double? = null
        var lastGpsTimestampNs = 0L
        var firstTimestampNs = 0L
        var previousMountHold = false
        val stationaryPredictionSpeedsMps = mutableListOf<Double>()
        val acceptedInnovationMagnitudesMps = mutableListOf<Double>()
        val currentEstimateVsGpsDifferencesMps = mutableListOf<Double>()
        val acceptedCorrectionStepsMps = mutableListOf<Double>()
        val mountHoldStarts = mutableListOf<String>()

        source!!.bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            val header = iterator.next().split(',')
            val column = header.withIndex().associate { it.value to it.index }
            iterator.forEach { line ->
                val fields = line.split(',')
                val type = fields[column.getValue("record_type")]
                val timestampNs = fields[column.getValue("event_elapsed_ns")].toLong()
                if (firstTimestampNs == 0L) firstTimestampNs = timestampNs
                val state = when (type) {
                    "ACCEL" -> {
                        val x = fields[column.getValue("accel_x_mps2")].toDouble()
                        val y = fields[column.getValue("accel_y_mps2")].toDouble()
                        val z = fields[column.getValue("accel_z_mps2")].toDouble()
                        val calibration = calibrationManager.onAcceleration(
                            timestampNs,
                            x,
                            y,
                            z,
                        )
                        val longitudinalAcceleration = calibration.activeTransform
                            .longitudinalAccelerationMps2(x, y, z)
                        filter.onAcceleration(
                            timestampNs,
                            longitudinalAcceleration,
                            forceStationaryHold = calibration.holdSpeedAtZero,
                        ).also {
                            if (it.initialized) initializedAccelRows++
                            if (calibration.holdSpeedAtZero && !previousMountHold) {
                                mountHoldStarts += String.format(
                                    "%.2fs(lastGps=%.2fmps)",
                                    (timestampNs - firstTimestampNs) / 1_000_000_000.0,
                                    lastGpsSpeedMps ?: Double.NaN,
                                )
                            }
                            previousMountHold = calibration.holdSpeedAtZero
                            if (calibration.holdSpeedAtZero) {
                                mountHoldRows++
                                maximumHeldSpeedMps = maxOf(maximumHeldSpeedMps, it.velocityMps)
                            }
                            if (
                                lastGpsSpeedMps?.let { speed -> speed <= 0.30 } == true &&
                                timestampNs - lastGpsTimestampNs in 0..1_500_000_000L &&
                                it.initialized
                            ) {
                                stationaryPredictionSpeedsMps += it.velocityMps
                            }
                        }
                    }
                    "GPS" -> {
                        gpsRows++
                        val speedMps = fields[column.getValue("gps_speed_mps")].toDouble()
                        maximumGpsSpeedMps = maxOf(maximumGpsSpeedMps, speedMps)
                        lastGpsSpeedMps = speedMps
                        lastGpsTimestampNs = timestampNs
                        val estimateBeforeGps = filter.state().velocityMps
                        val accuracyText = fields[column.getValue("gps_speed_accuracy_mps")]
                        calibrationManager.onGpsSpeed(
                            timestampNs,
                            speedMps,
                            accuracyText.toDoubleOrNull(),
                        )
                        filter.onGpsSpeed(
                            timestampNs,
                            speedMps,
                            accuracyText.toDoubleOrNull(),
                        ).also {
                            if (it.gpsMeasurementAccepted) {
                                acceptedGpsRows++
                                it.innovationMps?.let { innovation ->
                                    acceptedInnovationMagnitudesMps += abs(innovation)
                                }
                                currentEstimateVsGpsDifferencesMps += abs(it.velocityMps - speedMps)
                                acceptedCorrectionStepsMps += abs(it.velocityMps - estimateBeforeGps)
                            }
                        }
                    }
                    else -> filter.state()
                }
                if (state.initialized) {
                    assertTrue(state.velocityMps.isFinite())
                    assertTrue(state.speedVarianceMps2.isFinite())
                    assertTrue(state.velocityMps >= 0.0)
                    maximumEstimatedSpeedMps = maxOf(maximumEstimatedSpeedMps, state.velocityMps)
                }
            }
        }

        val finalState = filter.state()
        val sortedInnovations = acceptedInnovationMagnitudesMps.sorted()
        val p95Innovation = sortedInnovations[
            (sortedInnovations.size * 0.95).toInt().coerceAtMost(sortedInnovations.lastIndex)
        ]
        val sortedCurrentDifferences = currentEstimateVsGpsDifferencesMps.sorted()
        val p95CurrentDifference = sortedCurrentDifferences[
            (sortedCurrentDifferences.size * 0.95).toInt()
                .coerceAtMost(sortedCurrentDifferences.lastIndex)
        ]
        val sortedCorrectionSteps = acceptedCorrectionStepsMps.sorted()
        val p95CorrectionStep = sortedCorrectionSteps[
            (sortedCorrectionSteps.size * 0.95).toInt()
                .coerceAtMost(sortedCorrectionSteps.lastIndex)
        ]
        val maximumCorrectionStep = sortedCorrectionSteps.last()
        val sortedStationarySpeeds = stationaryPredictionSpeedsMps.sorted()
        val p95StationarySpeed = if (sortedStationarySpeeds.isEmpty()) {
            0.0
        } else {
            sortedStationarySpeeds[
                (sortedStationarySpeeds.size * 0.95).toInt()
                    .coerceAtMost(sortedStationarySpeeds.lastIndex)
            ]
        }
        println(
            "Replay summary: gps=$gpsRows accepted=$acceptedGpsRows " +
                "initializedAccel=$initializedAccelRows maxGpsSpeed=$maximumGpsSpeedMps " +
                "maxEstimatedSpeed=$maximumEstimatedSpeedMps " +
                "p95HistoricalInnovation=$p95Innovation " +
                "p95CurrentEstimateVsDelayedGps=$p95CurrentDifference " +
                "p95CorrectionStep=$p95CorrectionStep " +
                "maxCorrectionStep=$maximumCorrectionStep " +
                "mountHoldRows=$mountHoldRows holdStarts=$mountHoldStarts " +
                "maxHeldSpeed=$maximumHeldSpeedMps p95StationarySpeed=$p95StationarySpeed " +
                "calibration=${calibrationManager.state()} " +
                "final=$finalState",
        )

        assertTrue(gpsRows > 600)
        assertTrue(acceptedGpsRows > 600)
        assertTrue(initializedAccelRows > 60_000)
        assertTrue(maximumEstimatedSpeedMps <= maximumGpsSpeedMps + 5.0)
        assertTrue(p95Innovation < 4.0)
        assertTrue(p95CorrectionStep < 3.0)
        assertTrue(maximumHeldSpeedMps == 0.0)
        assertTrue(finalState.velocityMps < 2.0)
    }
}
