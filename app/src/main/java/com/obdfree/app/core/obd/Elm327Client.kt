package com.obdfree.app.core.obd

import com.obdfree.app.core.transport.ObdTransport
import com.obdfree.app.model.LiveDataSnapshot
import com.obdfree.app.model.TroubleCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class ElmInitialization(
    val identity: String,
    val protocol: String?,
)

class Elm327Client(
    private val transport: ObdTransport,
) {
    suspend fun initialize(): ElmInitialization {
        val identity = command("ATZ", timeoutMs = 5_000)
        delay(250)

        command("ATE0")
        command("ATL0")
        command("ATS0")
        command("ATH0")
        command("ATAT1")
        command("ATSP0")

        // Force auto protocol detection before asking the adapter which one won.
        runCatching { command("0100", timeoutMs = 8_000) }
        val protocol = runCatching { command("ATDP") }
            .getOrNull()
            ?.cleanHumanResponse()

        return ElmInitialization(
            identity = identity.cleanHumanResponse()
                .ifBlank { "ELM327 compatible" },
            protocol = protocol,
        )
    }

    suspend fun readTroubleCodes(): List<TroubleCode> {
        val response = command("03", timeoutMs = 8_000)
        throwIfAdapterError(response)
        return ObdDecoder.decodeTroubleCodes(response)
    }

    suspend fun clearTroubleCodes(): Boolean {
        val response = command("04", timeoutMs = 8_000)
        throwIfAdapterError(response)
        val compact = response.uppercase().filterNot(Char::isWhitespace)
        return compact.contains("44") || compact.contains("OK")
    }

    suspend fun readLiveData(): LiveDataSnapshot {
        val rpm = command("010C")
        val speed = command("010D")
        val coolant = command("0105")
        val throttle = command("0111")

        return ObdDecoder.decodeLiveData(
            rpmResponse = rpm,
            speedResponse = speed,
            coolantResponse = coolant,
            throttleResponse = throttle,
        )
    }

    suspend fun command(
        value: String,
        timeoutMs: Long = 4_000,
    ): String {
        drainInput()
        transport.write((value.trim() + "\r").encodeToByteArray())

        return withTimeout(timeoutMs) {
            val buffer = StringBuilder()

            while (true) {
                val bytes = transport.readAvailable()
                if (bytes.isNotEmpty()) {
                    val text = bytes.decodeToString()
                    buffer.append(text)

                    if ('>' in text || '>' in buffer) {
                        return@withTimeout sanitize(value, buffer.toString())
                    }
                } else {
                    delay(18)
                }
            }

            @Suppress("UNREACHABLE_CODE")
            ""
        }
    }

    private suspend fun drainInput() {
        repeat(8) {
            val bytes = transport.readAvailable()
            if (bytes.isEmpty()) return
            delay(10)
        }
    }

    private fun sanitize(command: String, response: String): String =
        response
            .replace(">", "")
            .replace("\u0000", "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.equals(command.trim(), ignoreCase = true) }
            .joinToString("\n")

    private fun throwIfAdapterError(response: String) {
        val upper = response.uppercase()
        val failure = listOf(
            "NO DATA",
            "UNABLE TO CONNECT",
            "BUS ERROR",
            "CAN ERROR",
            "STOPPED",
            "?",
        ).firstOrNull(upper::contains)

        if (failure != null) {
            error("Réponse OBD: $failure")
        }
    }

    private fun String.cleanHumanResponse(): String =
        lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.equals("OK", ignoreCase = true) }
            .joinToString(" · ")
}
