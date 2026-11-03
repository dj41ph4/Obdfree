package com.obdfree.app.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AstraJDpfDecoderTest {

    @Test
    fun `extracts DID bytes from normal response`() {
        val bytes = AstraJDpfDecoder.extractDidBytes(
            response = "62 32 74 80",
            did = 0x3274,
        )
        assertEquals(listOf(0x80), bytes)
    }

    @Test
    fun `extracts DID bytes from CAN header response`() {
        val bytes = AstraJDpfDecoder.extractDidBytes(
            response = "7E8 06 62 32 77 00 01 2C",
            did = 0x3277,
            minimumBytes = 3,
        )
        assertEquals(listOf(0x00, 0x01, 0x2C), bytes?.take(3))
    }

    @Test
    fun `rejects truncated DID response`() {
        val bytes = AstraJDpfDecoder.extractDidBytes(
            response = "62 32 77 00 01",
            did = 0x3277,
            minimumBytes = 3,
        )
        assertNull(bytes)
    }

    @Test
    fun `decodes Astra J DPF formulas`() {
        assertEquals(50f, AstraJDpfDecoder.percentFromByte255(128), 0.3f)
        assertEquals(50f, AstraJDpfDecoder.directOrScaledPercent(50), 0.01f)
        assertEquals(-1, AstraJDpfDecoder.signedByte(0xFF))
        assertEquals(300, AstraJDpfDecoder.unsigned24(listOf(0x00, 0x01, 0x2C)))
        assertEquals(90, AstraJDpfDecoder.unsigned16(listOf(0x00, 0x5A)))
        assertEquals(600f, AstraJDpfDecoder.temperatureFiveDegreeByte(128), 0.01f)
    }
}
