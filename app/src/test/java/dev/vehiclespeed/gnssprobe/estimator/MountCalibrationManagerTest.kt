package dev.vehiclespeed.gnssprobe.estimator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MountCalibrationManagerTest {
    @Test
    fun stationaryMountChangeIsDetectedHeldAndReorientedAfterItStabilizes() {
        val transform = VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test")
        val manager = MountCalibrationManager(transform)
        val originalGravity = Axis3(0.0, 0.0, GRAVITY)
        val changedGravity = rotateAboutY(originalGravity, 20.0)
        var sawHold = false

        feed(manager, 0.0, 4.0, originalGravity, gpsSpeedMps = 0.0)
        assertEquals(MountCalibrationMode.READY, manager.state().mode)
        assertTrue(manager.state().stationaryConfirmed)

        feed(manager, 4.0, 11.0, changedGravity, gpsSpeedMps = 0.0) { state ->
            sawHold = sawHold || state.holdSpeedAtZero
        }

        val state = manager.state()
        assertTrue(sawHold)
        assertEquals(MountCalibrationMode.READY, state.mode)
        assertEquals(1, state.recalibrationCount)
        assertEquals(
            0.0,
            state.activeTransform.longitudinalAccelerationMps2(
                changedGravity.x,
                changedGravity.y,
                changedGravity.z,
            ),
            0.01,
        )
    }

    @Test
    fun gravityChangeThatExistsBeforeStoppingIsTreatedAsRoadGrade() {
        val transform = VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test")
        val manager = MountCalibrationManager(transform)
        val hillGravity = rotateAboutY(Axis3(0.0, 0.0, GRAVITY), 20.0)

        feed(manager, 0.0, 4.0, hillGravity, gpsSpeedMps = 8.0)
        feed(manager, 4.0, 9.0, hillGravity, gpsSpeedMps = 0.0)

        val state = manager.state()
        assertEquals(MountCalibrationMode.READY, state.mode)
        assertEquals(0, state.recalibrationCount)
        assertFalse(state.holdSpeedAtZero)
        assertEquals(1.0, state.activeTransform.forwardAxis.x, 1e-12)
    }

    @Test
    fun apparentGravityChangeWhileGpsIsMovingDoesNotRecalibrate() {
        val manager = MountCalibrationManager(
            VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test"),
        )
        val baseline = Axis3(0.0, 0.0, GRAVITY)
        val accelerationTilt = rotateAboutY(baseline, 25.0)

        feed(manager, 0.0, 4.0, baseline, gpsSpeedMps = 0.0)
        feed(manager, 4.0, 9.0, accelerationTilt, gpsSpeedMps = 10.0)

        val state = manager.state()
        assertEquals(MountCalibrationMode.READY, state.mode)
        assertEquals(0, state.recalibrationCount)
        assertFalse(state.holdSpeedAtZero)
    }

    @Test
    fun movingGpsCancelsAStationaryMountSuspicionAsALaunch() {
        val manager = MountCalibrationManager(
            VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test"),
        )
        val baseline = Axis3(0.0, 0.0, GRAVITY)
        val apparentLaunchTilt = rotateAboutY(baseline, 25.0)

        feed(manager, 0.0, 4.0, baseline, gpsSpeedMps = 0.0)
        feed(manager, 4.0, 6.5, apparentLaunchTilt, gpsSpeedMps = null)
        assertEquals(MountCalibrationMode.READY, manager.state().mode)
        assertFalse(manager.state().holdSpeedAtZero)

        manager.onGpsSpeed(seconds(6.6), 5.0, 0.3)

        val state = manager.state()
        assertEquals(MountCalibrationMode.READY, state.mode)
        assertEquals(0, state.recalibrationCount)
        assertFalse(state.holdSpeedAtZero)
    }

    @Test
    fun mountChangeWaitsForALaterStationaryGpsFixBeforeHoldingZero() {
        val manager = MountCalibrationManager(
            VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test"),
        )
        val baseline = Axis3(0.0, 0.0, GRAVITY)
        val changedGravity = rotateAboutY(baseline, 20.0)

        feed(manager, 0.0, 4.0, baseline, gpsSpeedMps = 0.0)
        feed(manager, 4.0, 6.5, changedGravity, gpsSpeedMps = null)

        assertEquals(MountCalibrationMode.READY, manager.state().mode)
        assertFalse(manager.state().holdSpeedAtZero)

        manager.onGpsSpeed(seconds(6.6), 0.0, 0.3)
        val confirmed = manager.onAcceleration(
            seconds(6.61),
            changedGravity.x,
            changedGravity.y,
            changedGravity.z,
        )

        assertEquals(MountCalibrationMode.MOUNT_MOVED, confirmed.mode)
        assertTrue(confirmed.holdSpeedAtZero)
    }

    @Test
    fun confirmedMountChangeIsNotCancelledByLaterVehicleMotion() {
        val manager = MountCalibrationManager(
            VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test"),
        )
        val baseline = Axis3(0.0, 0.0, GRAVITY)
        val changedGravity = rotateAboutY(baseline, 20.0)

        feed(manager, 0.0, 4.0, baseline, gpsSpeedMps = 0.0)
        feed(manager, 4.0, 6.5, changedGravity, gpsSpeedMps = null)
        manager.onGpsSpeed(seconds(6.6), 0.0, 0.3)
        manager.onAcceleration(
            seconds(6.61),
            changedGravity.x,
            changedGravity.y,
            changedGravity.z,
        )
        assertEquals(MountCalibrationMode.MOUNT_MOVED, manager.state().mode)

        manager.onGpsSpeed(seconds(6.7), 5.0, 0.3)

        assertEquals(MountCalibrationMode.MOUNT_MOVED, manager.state().mode)
        assertEquals(0, manager.state().recalibrationCount)
        assertFalse(manager.state().holdSpeedAtZero)
    }

    @Test
    fun passingThroughOriginalAngleWhileBeingHandledDoesNotBecomeReady() {
        val manager = MountCalibrationManager(
            VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test"),
        )
        val baseline = Axis3(0.0, 0.0, GRAVITY)
        val changedGravity = rotateAboutY(baseline, 20.0)

        feed(manager, 0.0, 4.0, baseline, gpsSpeedMps = 0.0)
        feed(manager, 4.0, 7.0, changedGravity, gpsSpeedMps = 0.0)
        assertEquals(MountCalibrationMode.MOUNT_MOVED, manager.state().mode)

        val startSample = (7.0 * RATE_HZ).toInt()
        val endSample = (10.0 * RATE_HZ).toInt()
        for (sample in startSample..endSample) {
            val timestampNs = seconds(sample / RATE_HZ)
            if (sample % RATE_HZ.toInt() == 0) {
                manager.onGpsSpeed(timestampNs, 0.0, 0.3)
            }
            val noisy = if (sample % 2 == 0) 4.0 else -4.0
            manager.onAcceleration(timestampNs, noisy, 0.0, GRAVITY)
        }

        assertEquals(MountCalibrationMode.MOUNT_MOVED, manager.state().mode)
        assertTrue(manager.state().holdSpeedAtZero)

        feed(manager, 10.0, 18.0, baseline, gpsSpeedMps = 0.0)
        assertEquals(MountCalibrationMode.READY, manager.state().mode)
        assertEquals(0, manager.state().recalibrationCount)
    }

    private fun feed(
        manager: MountCalibrationManager,
        startSeconds: Double,
        endSeconds: Double,
        acceleration: Axis3,
        gpsSpeedMps: Double?,
        onState: (MountCalibrationState) -> Unit = {},
    ) {
        val startSample = (startSeconds * RATE_HZ).toInt()
        val endSample = (endSeconds * RATE_HZ).toInt()
        for (sample in startSample..endSample) {
            val seconds = sample / RATE_HZ
            val timestampNs = (seconds * 1_000_000_000L).toLong() + 1L
            if (gpsSpeedMps != null && sample % RATE_HZ.toInt() == 0) {
                manager.onGpsSpeed(timestampNs, gpsSpeedMps, 0.3)
            }
            onState(
                manager.onAcceleration(
                    timestampNs,
                    acceleration.x,
                    acceleration.y,
                    acceleration.z,
                ),
            )
        }
    }

    private fun rotateAboutY(vector: Axis3, degrees: Double): Axis3 {
        val radians = Math.toRadians(degrees)
        return Axis3(
            x = vector.x * cos(radians) + vector.z * sin(radians),
            y = vector.y,
            z = -vector.x * sin(radians) + vector.z * cos(radians),
        )
    }

    private fun seconds(value: Double): Long = (value * 1_000_000_000L).toLong() + 1L

    companion object {
        private const val GRAVITY = 9.80665
        private const val RATE_HZ = 100.0
    }
}
