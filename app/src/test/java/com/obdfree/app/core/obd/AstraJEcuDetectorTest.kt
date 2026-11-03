package com.obdfree.app.core.obd

import com.obdfree.app.core.transport.ObdTransport
import com.obdfree.app.model.AstraJEcuFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AstraJEcuDetectorTest {

    private val detector = AstraJEcuDetector(
        Elm327Client(
            object : ObdTransport {
                override val isConnected: Boolean = true
                override suspend fun connect(address: String) = Unit
                override suspend fun disconnect() = Unit
                override suspend fun write(data: ByteArray) = Unit
                override suspend fun readAvailable(): ByteArray = byteArrayOf()
            },
        ),
    )

    @Test
    fun `reassembles ELM segmented GMLAN identification response`() {
        val response = """
            014
            0: 5A 90 57 30 4C 50
            1: 30 30 30 30 30 30 30
            2: 30 30 30 30 30 30 31
        """.trimIndent()

        val bytes = requireNotNull(
            AstraJGmlanCodec.bytesAfterMarker(response, listOf(0x5A, 0x90), minimumBytes = 17),
        )
        val vin = bytes.take(17).map { it.toChar() }.joinToString("")
        assertEquals("W0LP0000000000001", vin)
    }

    @Test
    fun `detects Denso DECe01 from real style Astra J identifiers`() {
        val identity = detector.classify(
            vin = "W0LP0000000000001",
            systemId = "DENSO0100",
            engineCode = "A17DTJ",
            programmingDate = "2011-10-25",
            supplier = null,
        )

        assertEquals(AstraJEcuFamily.DENSO_DECE01, identity.family)
        assertTrue(identity.confidence >= 95)
    }

    @Test
    fun `detects E87 profile from Astra J Bosch system identification`() {
        val identity = detector.classify(
            vin = null,
            systemId = "BOSCH0100",
            engineCode = "A17DTE",
            programmingDate = null,
            supplier = null,
        )

        assertEquals(AstraJEcuFamily.GM_E87, identity.family)
        assertTrue(identity.confidence >= 85)
    }
}
