package dev.vehiclespeed.gnssprobe

import kotlin.math.roundToInt

object RealDashCanEncoder {
    const val TEST_FRAME_ID = 0x700

    /** Encodes one fixed-size RealDash-CAN 44 frame. */
    fun speedFrame(
        estimatedSpeedKph: Double,
        rawGpsSpeedKph: Double,
        gpsAgeMs: Int,
        estimatorMode: Int,
        flags: Int,
    ): ByteArray {
        val frame = ByteArray(16)
        frame[0] = 0x44
        frame[1] = 0x33
        frame[2] = 0x22
        frame[3] = 0x11
        putUInt32LittleEndian(frame, 4, TEST_FRAME_ID)

        putUInt16LittleEndian(frame, 8, speedToWireValue(estimatedSpeedKph))
        putUInt16LittleEndian(frame, 10, speedToWireValue(rawGpsSpeedKph))
        putUInt16LittleEndian(frame, 12, gpsAgeMs.coerceIn(0, 65_535))
        frame[14] = estimatorMode.coerceIn(0, 255).toByte()
        frame[15] = flags.coerceIn(0, 255).toByte()
        return frame
    }

    private fun speedToWireValue(speedKph: Double): Int {
        if (!speedKph.isFinite()) return 0
        return (speedKph.coerceIn(0.0, 655.35) * 100.0).roundToInt()
    }

    private fun putUInt16LittleEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun putUInt32LittleEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }
}
