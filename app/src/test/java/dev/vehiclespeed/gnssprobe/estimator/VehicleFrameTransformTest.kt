package dev.vehiclespeed.gnssprobe.estimator

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class VehicleFrameTransformTest {
    @Test
    fun normalizesForwardAxis() {
        val transform = VehicleFrameTransform(Axis3(3.0, 0.0, 4.0), "test")

        val magnitude = sqrt(
            transform.forwardAxis.x * transform.forwardAxis.x +
                transform.forwardAxis.y * transform.forwardAxis.y +
                transform.forwardAxis.z * transform.forwardAxis.z,
        )

        assertEquals(1.0, magnitude, 1e-12)
        assertEquals(5.0, transform.longitudinalAccelerationMps2(3.0, 0.0, 4.0), 1e-12)
    }

    @Test
    fun developmentTransformRejectsStationaryGravityReference() {
        val transform = VehicleFrameTransform.DEVELOPMENT_DRIVE

        val projected = transform.longitudinalAccelerationMps2(
            accelX = -9.1491060294,
            accelY = 0.4148026274,
            accelZ = 2.6165873134,
        )

        assertEquals(0.0, projected, 0.01)
    }

    @Test
    fun reorientationPreservesForwardProjectionAcrossGravityChange() {
        val transform = VehicleFrameTransform(Axis3(1.0, 0.0, 0.0), "test")
        val oldGravity = Axis3(0.0, 0.0, 9.80665)
        val newGravity = Axis3(3.35407, 0.0, 9.21524)

        val adjusted = transform.reorientedForGravityChange(
            previousGravity = oldGravity,
            currentGravity = newGravity,
            calibrationName = "adjusted",
        )

        assertEquals(0.0, adjusted.longitudinalAccelerationMps2(
            newGravity.x,
            newGravity.y,
            newGravity.z,
        ), 1e-5)
        assertEquals("adjusted", adjusted.calibrationName)
    }
}
