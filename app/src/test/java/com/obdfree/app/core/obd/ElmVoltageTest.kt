package com.obdfree.app.core.obd

import com.obdfree.app.core.transport.ObdTransport
import com.obdfree.app.model.VoltageSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmVoltageTest {
    @Test fun acceptsRealistic12And24VoltReadings() {
        assertEquals(12.6f, ElmVoltage.parse("12.6V"), 0.01f)
        assertEquals(14.25f, ElmVoltage.parse("14,25 V"), 0.01f)
        assertEquals(28.1f, ElmVoltage.parse("28.1V"), 0.01f)
        assertEquals(12.5f, ElmVoltage.parse("OK\n12.5V"), 0.01f)
    }

    @Test fun rejectsMalformedAndDangerouslyMisleadingReadings() {
        listOf(
            "", "OK", "NO DATA", "BUS ERROR", "?", "BUFFER FULL",
            "12.4V\nBUFFER FULL", "12.4V\n13.3V", "V12.5",
            "0.0V", "38.3V", "NaN", "12.4V extra",
        ).forEach { assertNull("Should reject: $it", ElmVoltage.parse(it)) }
    }

    @Test fun keepsOnlyRecentNinetySamples() {
        val initial = (0L..98L).map { VoltageSample(it, 12.6f) }
        val result = ElmVoltage.append(initial, VoltageSample(99L, 13.1f))
        assertEquals(ElmVoltage.MAX_SAMPLES, result.size)
        assertEquals(10L, result.first().timestampMs)
        assertEquals(99L, result.last().timestampMs)
    }

    @Test fun elmClientCanReadVoltageWithoutAnyEcuProbe() = runBlocking {
        val transport = TestElmTransport()
        val client = Elm327Client(transport)
        assertEquals(12.7f, client.readInputVoltage(), 0.01f)
        assertEquals(listOf("ATRV"), transport.commands)
    }

    @Test fun simultaneousCommandsAreSerialized() = runBlocking {
        val transport = TestElmTransport()
        val client = Elm327Client(transport)
        val responses = (1..12).map {
            async(Dispatchers.Default) { client.readInputVoltage() }
        }.awaitAll()

        assertTrue(responses.all { it == 12.7f })
        assertEquals(12, transport.commands.size)
        assertFalse(transport.overlap)
    }

    private class TestElmTransport : ObdTransport {
        override val isConnected: Boolean = true
        val commands = mutableListOf<String>()
        var overlap: Boolean = false
        private var pending: ByteArray? = null

        override suspend fun connect(address: String) = Unit
        override suspend fun disconnect() = Unit

        override suspend fun write(data: ByteArray) {
            if (pending != null) overlap = true
            commands += data.decodeToString().trim()
            pending = "12.7V\r>".encodeToByteArray()
        }

        override suspend fun readAvailable(): ByteArray {
            delay(5)
            val result = pending ?: return byteArrayOf()
            pending = null
            return result
        }
    }
}
