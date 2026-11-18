package com.obdfree.app.core.obd

import com.obdfree.app.core.transport.ObdTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiatPanda169AdapterTest {
    /**
     * With a green A1 K-line adapter, the generic engine pin is physically
     * absent. An adapter-only connection MUST NOT require engine PID 0100.
     */
    @Test fun pandaEpsHandshakeDoesNotProbeAnEngineEcu() = runBlocking {
        val transport = ScriptedElmTransport()
        val info = Elm327Client(transport).initializeAdapterOnly()
        assertEquals("ELM327 v1.5", info.identity)
        assertEquals(12.6f, info.adapterVoltage ?: 0f, 0.01f)
        assertEquals(null, info.protocol)
        assertTrue(transport.commands.contains("ATZ"))
        assertTrue(transport.commands.contains("ATRV"))
        assertFalse(transport.commands.contains("0100"))
        assertFalse(transport.commands.any { it.startsWith("ATTP") || it == "ATSP0" })
    }

    private class ScriptedElmTransport : ObdTransport {
        override val isConnected: Boolean = true
        val commands = mutableListOf<String>()
        private var pending: ByteArray = byteArrayOf()

        override suspend fun connect(address: String) = Unit
        override suspend fun disconnect() = Unit

        override suspend fun write(data: ByteArray) {
            val cmd = data.decodeToString().trim()
            commands += cmd
            val answer = when (cmd) {
                "ATZ" -> "ELM327 v1.5"
                "ATRV" -> "12.6V"
                else -> "OK"
            }
            pending = (answer + "\r>").toByteArray()
        }

        override suspend fun readAvailable(): ByteArray {
            val reply = pending
            pending = byteArrayOf()
            return reply
        }
    }
}
