package com.obdfree.app.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdDecoderTest {

    @Test
    fun `decodes CAN mode 03 count byte correctly`() {
        val response = "43 02 03 00 01 30"

        val codes = ObdDecoder.decodeTroubleCodes(
            response = response,
            positiveResponse = 0x43,
            expectedCount = 2,
            hasCanCountByte = true,
        )

        assertEquals(listOf("P0300", "P0130"), codes.map { it.code })
    }

    @Test
    fun `does not mistake non CAN DTC high byte for count`() {
        val response = "43 02 03 00 00 00 00"

        val codes = ObdDecoder.decodeTroubleCodes(
            response = response,
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
}
