package com.obdfree.app.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecoderTest {

    @Test
    fun `decodes CAN mode 03 count byte correctly`() {
        val codes = ObdDecoder.decodeTroubleCodes(
            response = "43 02 03 00 01 30",
            positiveResponse = 0x43,
            expectedCount = 2,
            hasCanCountByte = true,
        )
        assertEquals(listOf("P0300", "P0130"), codes.map { it.code })
    }

    @Test
    fun `decodes compact ELM response without spaces`() {
        val codes = ObdDecoder.decodeTroubleCodes(
            response = "430201330234",
            positiveResponse = 0x43,
            expectedCount = 2,
            hasCanCountByte = true,
        )
        assertEquals(listOf("P0133", "P0234"), codes.map { it.code })
    }

    @Test
    fun `uses monitor count when clone does not report protocol`() {
        val codes = ObdDecoder.decodeTroubleCodes(
            response = "43 02 03 00 04 20",
            positiveResponse = 0x43,
            expectedCount = 2,
            hasCanCountByte = null,
        )
        assertEquals(listOf("P0300", "P0420"), codes.map { it.code })
    }

    @Test
    fun `does not mistake non CAN DTC high byte for count`() {
        val codes = ObdDecoder.decodeTroubleCodes(
            response = "43 02 03 00 00 00 00",
            positiveResponse = 0x43,
            expectedCount = 1,
            hasCanCountByte = false,
        )
        assertEquals(listOf("P0203"), codes.map { it.code })
    }

    @Test
    fun `reassembles ISO TP multi frame response with CAN headers`() {
        val response = """
            7E8 10 08 43 03 01 33 03 00
            7E8 21 04 20 00 00 00 00 00
        """.trimIndent()

        val codes = ObdDecoder.decodeTroubleCodes(
            response = response,
            positiveResponse = 0x43,
            expectedCount = 3,
            hasCanCountByte = true,
        )
        assertEquals(listOf("P0133", "P0300", "P0420"), codes.map { it.code })
    }

    @Test
    fun `reassembles native ELM CAF1 segmented response`() {
        val response = """
            008
            0: 43 03 01 33 03 00
            1: 04 20
        """.trimIndent()

        val codes = ObdDecoder.decodeTroubleCodes(
            response = response,
            positiveResponse = 0x43,
            expectedCount = 3,
            hasCanCountByte = true,
        )
        assertEquals(listOf("P0133", "P0300", "P0420"), codes.map { it.code })
    }

    @Test
    fun `does not double parse response marker inside CAN continuation data`() {
        val response = """
            7E8 10 08 43 03 01 33 03 00
            7E8 21 43 21 00 00 00 00 00
        """.trimIndent()

        val codes = ObdDecoder.decodeTroubleCodes(
            response = response,
            positiveResponse = 0x43,
            expectedCount = 3,
            hasCanCountByte = true,
        )
        assertEquals(listOf("P0133", "P0300", "C0321"), codes.map { it.code })
    }

    @Test
    fun `ignores ELM searching noise and padding`() {
        val response = """
            SEARCHING...
            43 01 04 20 00 00 00 00
        """.trimIndent()

        val codes = ObdDecoder.decodeTroubleCodes(
            response = response,
            positiveResponse = 0x43,
            expectedCount = 1,
            hasCanCountByte = true,
        )
        assertEquals(listOf("P0420"), codes.map { it.code })
    }

    @Test
    fun `decodes pending and permanent response modes`() {
        val pending = ObdDecoder.decodeTroubleCodes(
            response = "47 01 03 01",
            positiveResponse = 0x47,
            hasCanCountByte = true,
        )
        val permanent = ObdDecoder.decodeTroubleCodes(
            response = "4A 01 04 20",
            positiveResponse = 0x4A,
            hasCanCountByte = true,
        )

        assertEquals(listOf("P0301"), pending.map { it.code })
        assertEquals(listOf("P0420"), permanent.map { it.code })
    }

    @Test
    fun `decodes MIL and stored DTC count from PID 0101`() {
        val status = requireNotNull(ObdDecoder.decodeMonitorStatus("41 01 82 07 E1 00"))
        assertTrue(status.milOn)
        assertEquals(2, status.storedDtcCount)

        val clear = requireNotNull(ObdDecoder.decodeMonitorStatus("41 01 00 07 E1 00"))
        assertFalse(clear.milOn)
        assertEquals(0, clear.storedDtcCount)
    }

    @Test
    fun `keeps monitor counts from multiple ECUs`() {
        val statuses = ObdDecoder.decodeMonitorStatuses(
            """
                41 01 81 07 E1 00
                41 01 02 07 E1 00
            """.trimIndent(),
        )

        assertEquals(2, statuses.size)
        assertEquals(3, statuses.sumOf { it.storedDtcCount })
    }

    @Test
    fun `decodes counters since last clear`() {
        assertEquals(7, ObdDecoder.decodeMode01Byte("41 30 07", 0x30))
        assertEquals(300, ObdDecoder.decodeMode01Word("41 31 01 2C", 0x31))
        assertEquals(90, ObdDecoder.decodeMode01Word("41 4E 00 5A", 0x4E))
    }
}
