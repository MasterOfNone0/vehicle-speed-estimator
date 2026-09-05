package dev.vehiclespeed.gnssprobe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RealDashCanEncoderTest {
    @Test
    fun encodesLiveTelemetryUsingDocumentedLittleEndianLayout() {
        val frame = RealDashCanEncoder.speedFrame(
            estimatedSpeedKph = 72.35,
            rawGpsSpeedKph = 71.12,
            gpsAgeMs = 427,
            estimatorMode = 2,
            flags = 0x1f,
        )

        assertEquals(16, frame.size)
        assertArrayEquals(
            byteArrayOf(0x44, 0x33, 0x22, 0x11),
            frame.copyOfRange(0, 4),
        )
        assertEquals(0x700, uint32LittleEndian(frame, 4))
        assertEquals(7_235, uint16LittleEndian(frame, 8))
        assertEquals(7_112, uint16LittleEndian(frame, 10))
        assertEquals(427, uint16LittleEndian(frame, 12))
        assertEquals(2, frame[14].toInt() and 0xff)
        assertEquals(0x1f, frame[15].toInt() and 0xff)
    }

    @Test
    fun clampsInvalidAndOutOfRangeValues() {
        val frame = RealDashCanEncoder.speedFrame(
            estimatedSpeedKph = Double.NaN,
            rawGpsSpeedKph = 1_000.0,
            gpsAgeMs = 100_000,
            estimatorMode = -1,
            flags = 999,
        )

        assertEquals(0, uint16LittleEndian(frame, 8))
        assertEquals(65_535, uint16LittleEndian(frame, 10))
        assertEquals(65_535, uint16LittleEndian(frame, 12))
        assertEquals(0, frame[14].toInt() and 0xff)
        assertEquals(255, frame[15].toInt() and 0xff)
    }

    private fun uint16LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32LittleEndian(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
