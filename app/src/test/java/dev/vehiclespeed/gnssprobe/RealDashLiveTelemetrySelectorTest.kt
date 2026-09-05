package dev.vehiclespeed.gnssprobe

import dev.vehiclespeed.gnssprobe.estimator.EstimatorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDashLiveTelemetrySelectorTest {
    @Test
    fun uninitializedCapturePublishesZeroWithInvalidMetadata() {
        val telemetry = RealDashLiveTelemetrySelector.select(
            input(
                captureActive = true,
                rawGpsSpeedMps = null,
                lastRawGpsElapsedNs = 0L,
                estimatedSpeedMps = null,
                estimatorTimestampNs = 0L,
                lastAcceptedGpsElapsedNs = 0L,
                estimatorMode = EstimatorMode.UNINITIALIZED,
            ),
        )

        assertFalse(telemetry.valid)
        assertEquals(0.0, telemetry.estimatedSpeedKph, 0.0)
        assertEquals(65_535, telemetry.gpsAgeMs)
        assertEquals(0, telemetry.estimatorModeCode)
        assertEquals(
            RealDashLiveTelemetrySelector.FLAG_CAPTURE_ACTIVE or
                RealDashLiveTelemetrySelector.FLAG_CALIBRATION_PROVISIONAL or
                RealDashLiveTelemetrySelector.FLAG_MOUNT_READY,
            telemetry.flags,
        )
    }

    @Test
    fun freshPredictingStateIsValidAndConvertedToKph() {
        val telemetry = RealDashLiveTelemetrySelector.select(input())

        assertTrue(telemetry.valid)
        assertEquals(36.0, telemetry.estimatedSpeedKph, 1e-9)
        assertEquals(32.4, telemetry.rawGpsSpeedKph, 1e-9)
        assertEquals(100, telemetry.gpsAgeMs)
        assertEquals(2, telemetry.estimatorModeCode)
        assertEquals(
            RealDashLiveTelemetrySelector.FLAG_VALID or
                RealDashLiveTelemetrySelector.FLAG_RAW_GPS_FRESH or
                RealDashLiveTelemetrySelector.FLAG_CAPTURE_ACTIVE or
                RealDashLiveTelemetrySelector.FLAG_CALIBRATION_PROVISIONAL or
                RealDashLiveTelemetrySelector.FLAG_ESTIMATOR_FRESH or
                RealDashLiveTelemetrySelector.FLAG_MOUNT_READY,
            telemetry.flags,
        )
    }

    @Test
    fun staleAcceptedGpsCorrectionMakesOutputInvalid() {
        val telemetry = RealDashLiveTelemetrySelector.select(
            input(lastAcceptedGpsElapsedNs = NOW_NS - 3_001_000_000L),
        )

        assertFalse(telemetry.valid)
        assertEquals(0, telemetry.flags and RealDashLiveTelemetrySelector.FLAG_VALID)
        assertTrue(
            telemetry.flags and RealDashLiveTelemetrySelector.FLAG_ESTIMATOR_FRESH != 0,
        )
    }

    @Test
    fun degradedModeIsInvalidEvenWithFreshData() {
        val telemetry = RealDashLiveTelemetrySelector.select(
            input(estimatorMode = EstimatorMode.GPS_DEGRADED),
        )

        assertFalse(telemetry.valid)
        assertEquals(3, telemetry.estimatorModeCode)
    }

    @Test
    fun mountMovementMakesOtherwiseFreshOutputInvalid() {
        val telemetry = RealDashLiveTelemetrySelector.select(
            input(mountCalibrationReady = false),
        )

        assertFalse(telemetry.valid)
        assertEquals(0, telemetry.flags and RealDashLiveTelemetrySelector.FLAG_VALID)
        assertEquals(0, telemetry.flags and RealDashLiveTelemetrySelector.FLAG_MOUNT_READY)
    }

    @Test
    fun modeCodesRemainStableForTheXmlContract() {
        assertEquals(0, RealDashLiveTelemetrySelector.modeCode(EstimatorMode.UNINITIALIZED))
        assertEquals(1, RealDashLiveTelemetrySelector.modeCode(EstimatorMode.GPS_CORRECTION))
        assertEquals(2, RealDashLiveTelemetrySelector.modeCode(EstimatorMode.PREDICTING))
        assertEquals(3, RealDashLiveTelemetrySelector.modeCode(EstimatorMode.GPS_DEGRADED))
        assertEquals(4, RealDashLiveTelemetrySelector.modeCode(EstimatorMode.STATIONARY))
    }

    private fun input(
        captureActive: Boolean = true,
        nowElapsedNs: Long = NOW_NS,
        rawGpsSpeedMps: Double? = 9.0,
        lastRawGpsElapsedNs: Long = NOW_NS - 100_000_000L,
        lastAcceptedGpsElapsedNs: Long = NOW_NS - 100_000_000L,
        estimatedSpeedMps: Double? = 10.0,
        estimatorTimestampNs: Long = NOW_NS - 10_000_000L,
        estimatorMode: EstimatorMode = EstimatorMode.PREDICTING,
        mountCalibrationReady: Boolean = true,
    ) = RealDashLiveInput(
        captureActive = captureActive,
        nowElapsedNs = nowElapsedNs,
        rawGpsSpeedMps = rawGpsSpeedMps,
        lastRawGpsElapsedNs = lastRawGpsElapsedNs,
        lastAcceptedGpsElapsedNs = lastAcceptedGpsElapsedNs,
        estimatedSpeedMps = estimatedSpeedMps,
        estimatorTimestampNs = estimatorTimestampNs,
        estimatorMode = estimatorMode,
        mountCalibrationReady = mountCalibrationReady,
    )

    companion object {
        private const val NOW_NS = 10_000_000_000L
    }
}
