package com.obdfree.app.core.obd

import com.obdfree.app.core.transport.ObdTransport
import com.obdfree.app.model.LiveDataSnapshot
import com.obdfree.app.model.TroubleCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class ElmInitialization(
    val identity: String,
    val protocol: String?,
    val protocolNumber: String? = null,
)

class Elm327Client(
    private val transport: ObdTransport,
) {
    private var protocolNumber: String? = null
    private var protocolDescription: String? = null

    suspend fun initialize(): ElmInitialization {
        val identity = command("ATZ", timeoutMs = 7_000)
        delay(700)

        command("ATE0")
        command("ATL0")
        command("ATS0")
        command("ATH0")

        runCatching { command("ATAL") }
        runCatching { command("ATAT1") }
        runCatching { command("ATSTFF") }
        command("ATSP0")

        val probe = retryCommand("0100", attempts = 2, timeoutMs = 15_000)
        throwIfAdapterError(probe)

        protocolDescription = runCatching { command("ATDP") }
            .getOrNull()
            ?.cleanHumanResponse()
            ?.takeIf(String::isNotBlank)

        protocolNumber = runCatching { command("ATDPN") }
            .getOrNull()
            ?.cleanHumanResponse()
            ?.takeIf(String::isNotBlank)

        return ElmInitialization(
            identity = identity.cleanHumanResponse().ifBlank { "ELM327 compatible" },
            protocol = protocolDescription,
            protocolNumber = protocolNumber,
        )
    }

    suspend fun readTroubleCodes(): List<TroubleCode> {
        val monitorResponse = runCatching { retryCommand("0101", attempts = 2, timeoutMs = 10_000) }
            .getOrNull()
        val monitor = monitorResponse?.let(ObdDecoder::decodeMonitorStatus)
        val canCountByte = detectCanCountByte()

        var stored = readDtcMode(
            command = "03",
            responseMode = 0x43,
            expectedCount = monitor?.storedDtcCount,
            hasCanCountByte = canCountByte,
        )

        if (monitor != null && monitor.storedDtcCount > 0 && stored.size < monitor.storedDtcCount) {
            val headerResponse = readWithHeaders("03")
            stored = ObdDecoder.decodeTroubleCodes(
                response = headerResponse,
                positiveResponse = 0x43,
                expectedCount = monitor.storedDtcCount,
                hasCanCountByte = canCountByte,
            )
        }

        if (monitor != null && monitor.storedDtcCount > 0 && stored.size < monitor.storedDtcCount) {
            error(
                "Le calculateur annonce ${monitor.storedDtcCount} défaut(s), " +
                    "mais la réponse de l’ELM327 n’a pas pu être décodée correctement.",
            )
        }

        val pending = runCatching {
            readDtcMode(
                command = "07",
                responseMode = 0x47,
                expectedCount = null,
                hasCanCountByte = canCountByte,
            )
        }.getOrDefault(emptyList())

        val permanent = runCatching {
            readDtcMode(
                command = "0A",
                responseMode = 0x4A,
                expectedCount = null,
                hasCanCountByte = canCountByte,
            )
        }.getOrDefault(emptyList())

        return (stored + pending + permanent).distinctBy { it.code }
    }

    suspend fun clearTroubleCodes(): Boolean {
        val response = command("04", timeoutMs = 10_000)
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
        timeoutMs: Long = 5_000,
    ): String {
        drainInput()
        transport.write((value.trim() + "\r").encodeToByteArray())

        return withTimeout(timeoutMs) {
            val buffer = StringBuilder()
            while (true) {
                val bytes = transport.readAvailable()
                if (bytes.isNotEmpty()) {
                    buffer.append(bytes.decodeToString())
                    if ('>' in buffer) return@withTimeout sanitize(value, buffer.toString())
                } else {
                    delay(15)
                }
            }

            @Suppress("UNREACHABLE_CODE")
            ""
        }
    }

    private suspend fun retryCommand(
        value: String,
        attempts: Int,
        timeoutMs: Long,
    ): String {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val response = command(value, timeoutMs)
                if (!containsTransientFailure(response) || attempt == attempts - 1) return response
            } catch (error: Throwable) {
                lastError = error
                if (attempt == attempts - 1) throw error
            }
            delay(180)
        }
        throw lastError ?: IllegalStateException("Aucune réponse ELM327")
    }

    private suspend fun readDtcMode(
        command: String,
        responseMode: Int,
        expectedCount: Int?,
        hasCanCountByte: Boolean?,
    ): List<TroubleCode> {
        val response = retryCommand(command, attempts = 2, timeoutMs = 12_000)
        if (response.contains("NO DATA", ignoreCase = true)) return emptyList()
        throwIfAdapterError(response)

        return ObdDecoder.decodeTroubleCodes(
            response = response,
            positiveResponse = responseMode,
            expectedCount = expectedCount,
            hasCanCountByte = hasCanCountByte,
        )
    }

    private suspend fun readWithHeaders(value: String): String {
        return try {
            runCatching { command("ATS1") }
            runCatching { command("ATH1") }
            retryCommand(value, attempts = 2, timeoutMs = 12_000)
        } finally {
            runCatching { command("ATH0") }
            runCatching { command("ATS0") }
        }
    }

    private suspend fun drainInput() {
        repeat(12) {
            val bytes = transport.readAvailable()
            if (bytes.isEmpty()) return
            delay(8)
        }
    }

    private fun sanitize(command: String, response: String): String =
        response
            .replace(">", "")
            .replace("\u0000", "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { normalizeCommand(it) == normalizeCommand(command) }
            .joinToString("\n")

    private fun normalizeCommand(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }

    private fun containsTransientFailure(response: String): Boolean {
        val upper = response.uppercase()
        return upper.contains("STOPPED") || upper.contains("BUS INIT")
    }

    private fun throwIfAdapterError(response: String) {
        val upper = response.uppercase()
        val failure = listOf(
            "UNABLE TO CONNECT",
            "BUS ERROR",
            "CAN ERROR",
            "STOPPED",
            "BUS INIT: ERROR",
            "?",
        ).firstOrNull(upper::contains)

        if (failure != null) error("Réponse OBD: $failure")
    }

    private fun detectCanCountByte(): Boolean? {
        val normalizedNumber = protocolNumber
            ?.uppercase()
            ?.replace(" ", "")
            ?.removePrefix("A")
            .orEmpty()

        normalizedNumber.firstOrNull()?.digitToIntOrNull(16)?.let { protocol ->
            return protocol in 6..9
        }

        val description = protocolDescription?.uppercase().orEmpty()
        if ("CAN" in description || "ISO 15765" in description) return true
        if (description.isNotBlank() && "AUTO" !in description && "?" !in description) return false
        return null
    }

    private fun String.cleanHumanResponse(): String =
        lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.equals("OK", ignoreCase = true) }
            .joinToString(" · ")
}
