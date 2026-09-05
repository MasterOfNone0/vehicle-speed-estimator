package dev.vehiclespeed.gnssprobe.estimator

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

data class Axis3(
    val x: Double,
    val y: Double,
    val z: Double,
)

class VehicleFrameTransform(
    forwardAxis: Axis3,
    val calibrationName: String,
) {
    val forwardAxis: Axis3 = forwardAxis.normalized()

    fun longitudinalAccelerationMps2(
        accelX: Double,
        accelY: Double,
        accelZ: Double,
    ): Double =
        accelX * forwardAxis.x +
            accelY * forwardAxis.y +
            accelZ * forwardAxis.z

    /**
     * Applies the minimum rotation that maps the old stationary gravity vector to the new one.
     * Rotation around gravity (mount yaw) is not observable from an accelerometer alone.
     */
    fun reorientedForGravityChange(
        previousGravity: Axis3,
        currentGravity: Axis3,
        calibrationName: String,
    ): VehicleFrameTransform = VehicleFrameTransform(
        forwardAxis = rotateBetweenDirections(
            vector = forwardAxis,
            fromDirection = previousGravity,
            toDirection = currentGravity,
        ),
        calibrationName = calibrationName,
    )

    companion object {
        /**
         * Provisional transform inferred from the first driving capture. It is intentionally
         * isolated here so a final rigid-mount calibration can replace it without changing the
         * estimator or Android sensor source.
         */
        val DEVELOPMENT_DRIVE = VehicleFrameTransform(
            forwardAxis = Axis3(
                x = -0.2757504943,
                y = -0.0199408730,
                z = -0.9610223861,
            ),
            calibrationName = "Provisional drive calibration",
        )
    }
}

internal fun Axis3.magnitude(): Double = sqrt(x * x + y * y + z * z)

internal fun Axis3.normalized(): Axis3 {
    val magnitude = magnitude()
    require(magnitude > 1e-9) { "Forward axis must have non-zero magnitude" }
    return Axis3(x / magnitude, y / magnitude, z / magnitude)
}

internal fun Axis3.dot(other: Axis3): Double =
    x * other.x + y * other.y + z * other.z

internal fun Axis3.cross(other: Axis3): Axis3 = Axis3(
    x = y * other.z - z * other.y,
    y = z * other.x - x * other.z,
    z = x * other.y - y * other.x,
)

internal operator fun Axis3.plus(other: Axis3): Axis3 =
    Axis3(x + other.x, y + other.y, z + other.z)

internal operator fun Axis3.minus(other: Axis3): Axis3 =
    Axis3(x - other.x, y - other.y, z - other.z)

internal operator fun Axis3.times(scale: Double): Axis3 =
    Axis3(x * scale, y * scale, z * scale)

internal fun angleDegrees(first: Axis3, second: Axis3): Double {
    val denominator = first.magnitude() * second.magnitude()
    if (denominator <= 1e-9) return 0.0
    val cosine = (first.dot(second) / denominator).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
}

private fun rotateBetweenDirections(
    vector: Axis3,
    fromDirection: Axis3,
    toDirection: Axis3,
): Axis3 {
    val from = fromDirection.normalized()
    val to = toDirection.normalized()
    val cross = from.cross(to)
    val sineSquared = cross.dot(cross)
    val cosine = from.dot(to).coerceIn(-1.0, 1.0)
    if (sineSquared <= 1e-12) {
        require(cosine > 0.0) { "A 180-degree gravity change has no unique rotation axis" }
        return vector
    }

    // Rodrigues' formula using the unnormalized cross product.
    return vector * cosine +
        cross.cross(vector) +
        cross * (cross.dot(vector) * (1.0 - cosine) / max(sineSquared, 1e-12))
}
