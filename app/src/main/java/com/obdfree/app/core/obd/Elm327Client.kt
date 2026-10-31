package com.obdfree.app.core.obd

import com.obdfree.app.core.transport.ObdTransport
import com.obdfree.app.model.ClearHistory
import com.obdfree.app.model.DtcStatus
import com.obdfree.app.model.LiveDataSnapshot
import com.obdfree.app.model.TroubleCode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

data class ElmInitialization(
    val identity: String,
    val protocol: String?,
    val protocolNumber: String? = null,
    val adapterVoltage: Float? = null,
)

class Elm327Client(
    private val transport: ObdTransport,
) {
    private var protocolNumber: String? = null
    private var protocolDescription: String? = null
    private var adapterVoltage: Float? = null
    private var recovering = false

    suspend fun initialize(): ElmInitialization {
        val identity = command("ATZ", timeoutMs = 7_000)
        delay(700)

        configureAdapter()
        adapterVoltage = readAdapterVoltage()

        establishVehicleProtocol()
        refreshProtocolIdentity()

        return ElmInitialization(
            identity = identity.cleanHumanResponse().ifBlank { "ELM327 compatible" },
            protocol = protocolDescription,
            protocolNumber = protocolNumber,
            adapterVoltage = adapterVoltage,
        )
    }

    suspend fun readTroubleCodes(): List<TroubleCode> {
        val monitorResponse = runCatching {
            retryCommand("0101", attempts = 2, timeoutMs = 10_000)
        }.getOrNull()
        val monitorStatuses = monitorResponse
            ?.let(ObdDecoder::decodeMonitorStatuses)
            .orEmpty()

        // PID 01 can be answered by more than one ECU. A strict count comparison is only
        // valid when a single ECU supplied the count; otherwise duplicated DTCs across ECUs
        // can make a de-duplicated list look falsely incomplete.
        val expectedStoredCount = monitorStatuses.singleOrNull()?.storedDtcCount
        val canCountByte = detectCanCountByte()

        var stored = readDtcMode(
            command = "03",
            responseMode = 0x43,
            expectedCount = expectedStoredCount,
            hasCanCountByte = canCountByte,
            status = DtcStatus.STORED,
        )

        if (expectedStoredCount != null && expectedStoredCount > 0 && stored.size < expectedStoredCount) {
            val headerResponse = readWithHeaders("03")
            stored = ObdDecoder.decodeTroubleCodes(
                response = headerResponse,
                positiveResponse = 0x43,
                expectedCount = expectedStoredCount,
                hasCanCountByte = canCountByte,
            ).map { it.copy(statuses = setOf(DtcStatus.STORED)) }
        }

        if (expectedStoredCount != null && expectedStoredCount > 0 && stored.size < expectedStoredCount) {
            error(
                "Le calculateur annonce $expectedStoredCount défaut(s), " +
                    "mais la réponse de l’ELM327 n’a pas pu être décodée correctement.",
            )
        }

        val pending = runCatching {
            readDtcMode(
                command = "07",
                responseMode = 0x47,
                expectedCount = null,
                hasCanCountByte = canCountByte,
                status = DtcStatus.PENDING,
            )
        }.getOrDefault(emptyList())

        val permanent = runCatching {
            readDtcMode(
                command = "0A",
                responseMode = 0x4A,
                expectedCount = null,
                hasCanCountByte = canCountByte,
                status = DtcStatus.PERMANENT,
            )
        }.getOrDefault(emptyList())

        return mergeStatuses(stored + pending + permanent)
    }

    suspend fun readClearHistory(): ClearHistory {
        val warmups = readMode01Byte("0130", 0x30)
        val distance = readMode01Word("0131", 0x31)
        val minutes = readMode01Word("014E", 0x4E)

        return ClearHistory(
            warmupsSinceClear = warmups,
            distanceSinceClearKm = distance,
            minutesSinceClear = minutes,
        )
    }

    suspend fun clearTroubleCodes(): Boolean {
        val response = retryCommand("04", attempts = 2, timeoutMs = 10_000)
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

    private suspend fun configureAdapter() {
        requiredAt("ATE0")
        requiredAt("ATL0")
        requiredAt("ATS0")
        requiredAt("ATH0")

        bestEffortAt("ATCAF1")
        bestEffortAt("ATCFC1")
        bestEffortAt("ATFCSM0")
        bestEffortAt("ATAL")
        bestEffortAt("ATAT1")
        bestEffortAt("ATSTFF")
    }

    private suspend fun establishVehicleProtocol() {
        requiredAt("ATSP0")

        val automaticProbe = runCatching {
            command("0100", timeoutMs = AUTO_PROTOCOL_TIMEOUT_MS)
        }.getOrNull()

        if (automaticProbe != null && containsMode01PidResponse(automaticProbe, 0x00)) {
            return
        }

        bestEffortAt("ATPC")

        for (protocol in PROTOCOL_TRY_ORDER) {
            val selected = runCatching { command("ATTP$protocol", timeoutMs = 5_000) }
                .getOrNull()
                ?: continue

            if (selected.contains("?")) continue

            val probe = runCatching {
                command("0100", timeoutMs = MANUAL_PROTOCOL_TIMEOUT_MS)
            }.getOrNull() ?: continue

            if (containsMode01PidResponse(probe, 0x00)) {
                protocolNumber = protocol
                protocolDescription = PROTOCOL_NAMES[protocol]
                return
            }
        }

        val voltageText = adapterVoltage?.let { " (${formatVoltage(it)})" }.orEmpty()
        error(
            "L’ELM327 répond$voltageText, mais aucun calculateur OBD-II ne répond. " +
                "Mets le contact, puis vérifie que le véhicule est compatible OBD-II.",
        )
    }

    private suspend fun refreshProtocolIdentity() {
        protocolDescription = runCatching { command("ATDP") }
            .getOrNull()
            ?.cleanHumanResponse()
            ?.takeIf(String::isNotBlank)
            ?: protocolDescription

        protocolNumber = runCatching { command("ATDPN") }
            .getOrNull()
            ?.cleanHumanResponse()
            ?.takeIf(String::isNotBlank)
            ?: protocolNumber
    }

    private suspend fun readAdapterVoltage(): Float? {
        val response = runCatching { command("ATRV", timeoutMs = 3_000) }.getOrNull()
            ?: return null

        val voltage = VOLTAGE_REGEX.find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?: return null

        if (voltage in 0f..6f) {
            error("Tension OBD anormale (${formatVoltage(voltage)}). Vérifie la prise et l’alimentation du dongle.")
        }

        return voltage
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

                if (hasRecoverableAdapterFault(response) && attempt < attempts - 1) {
                    recoverAdapterSession()
                    delay(180)
                    return@repeat
                }

                if (!containsTransientFailure(response) || attempt == attempts - 1) {
                    return response
                }
            } catch (error: Throwable) {
                lastError = error
                if (attempt == attempts - 1) throw error

                if (error !is TimeoutCancellationException) {
                    runCatching { recoverAdapterSession() }
                }
            }

            delay(180)
        }

        throw lastError ?: IllegalStateException("Aucune réponse ELM327")
    }

    private suspend fun recoverAdapterSession() {
        if (recovering) return
        recovering = true

        try {
            runCatching { command("ATWS", timeoutMs = 7_000) }
            delay(350)
            configureAdapter()

            val knownProtocol = normalizedProtocolNumber()
            if (knownProtocol != null) {
                runCatching { command("ATTP$knownProtocol", timeoutMs = 5_000) }
            } else {
                runCatching { command("ATSP0", timeoutMs = 5_000) }
            }
        } finally {
            recovering = false
        }
    }

    private suspend fun readDtcMode(
        command: String,
        responseMode: Int,
        expectedCount: Int?,
        hasCanCountByte: Boolean?,
        status: DtcStatus,
    ): List<TroubleCode> {
        val response = retryCommand(command, attempts = 2, timeoutMs = 12_000)
        if (response.contains("NO DATA", ignoreCase = true)) return emptyList()
        throwIfAdapterError(response)

        return ObdDecoder.decodeTroubleCodes(
            response = response,
            positiveResponse = responseMode,
            expectedCount = expectedCount,
            hasCanCountByte = hasCanCountByte,
        ).map { it.copy(statuses = setOf(status)) }
    }

    private suspend fun readMode01Byte(command: String, pid: Int): Int? {
        val response = runCatching { retryCommand(command, attempts = 2, timeoutMs = 7_000) }
            .getOrNull() ?: return null
        if (response.contains("NO DATA", ignoreCase = true)) return null
        return ObdDecoder.decodeMode01Byte(response, pid)
    }

    private suspend fun readMode01Word(command: String, pid: Int): Int? {
        val response = runCatching { retryCommand(command, attempts = 2, timeoutMs = 7_000) }
            .getOrNull() ?: return null
        if (response.contains("NO DATA", ignoreCase = true)) return null
        return ObdDecoder.decodeMode01Word(response, pid)
    }

    private suspend fun readWithHeaders(value: String): String {
        return try {
            bestEffortAt("ATS1")
            bestEffortAt("ATH1")
            retryCommand(value, attempts = 2, timeoutMs = 12_000)
        } finally {
            bestEffortAt("ATH0")
            bestEffortAt("ATS0")
        }
    }

    private suspend fun requiredAt(value: String) {
        val response = command(value, timeoutMs = 5_000)
        if (response.contains("?")) {
            error("Commande ELM327 non supportée : $value")
        }
    }

    private suspend fun bestEffortAt(value: String): Boolean =
        runCatching {
            val response = command(value, timeoutMs = 5_000)
            !response.contains("?") && !response.contains("ERROR", ignoreCase = true)
        }.getOrDefault(false)

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

    private fun containsMode01PidResponse(response: String, pid: Int): Boolean {
        val marker = "41" + pid.toString(16).padStart(2, '0').uppercase()
        return response.lineSequence()
            .map(::hexOnly)
            .any { marker in it }
    }

    private fun containsTransientFailure(response: String): Boolean {
        val upper = response.uppercase()
        return upper.contains("STOPPED") || upper.contains("BUS INIT: ERROR")
    }

    private fun hasRecoverableAdapterFault(response: String): Boolean {
        val upper = response.uppercase()
        return upper.contains("BUFFER FULL") ||
            upper.contains("DATA ERROR") ||
            upper.contains("RX ERROR")
    }

    private fun throwIfAdapterError(response: String) {
        val upper = response.uppercase()
        val failure = listOf(
            "UNABLE TO CONNECT",
            "BUS ERROR",
            "CAN ERROR",
            "STOPPED",
            "BUS INIT: ERROR",
            "BUFFER FULL",
            "DATA ERROR",
            "RX ERROR",
            "?",
        ).firstOrNull(upper::contains)

        if (failure != null) error("Réponse OBD: $failure")
    }

    private fun detectCanCountByte(): Boolean? {
        normalizedProtocolNumber()?.toIntOrNull(16)?.let { protocol ->
            return protocol in 6..9
        }

        val description = protocolDescription?.uppercase().orEmpty()
        if ("CAN" in description || "ISO 15765" in description) return true
        if (description.isNotBlank() && "AUTO" !in description && "?" !in description) return false
        return null
    }

    private fun normalizedProtocolNumber(): String? {
        val normalized = protocolNumber
            ?.uppercase()
            ?.replace(" ", "")
            ?.removePrefix("A")
            .orEmpty()

        return normalized.firstOrNull()
            ?.takeIf { it.digitToIntOrNull(16) != null }
            ?.toString()
    }

    private fun mergeStatuses(codes: List<TroubleCode>): List<TroubleCode> {
        val merged = LinkedHashMap<String, TroubleCode>()
        codes.forEach { code ->
            val existing = merged[code.code]
            merged[code.code] = if (existing == null) {
                code
            } else {
                existing.copy(statuses = existing.statuses + code.statuses)
            }
        }
        return merged.values.toList()
    }

    private fun String.cleanHumanResponse(): String =
        lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.equals("OK", ignoreCase = true) }
            .joinToString(" · ")

    private fun hexOnly(value: String): String =
        value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }

    private fun formatVoltage(voltage: Float): String = "%.1f V".format(voltage)

    private companion object {
        const val AUTO_PROTOCOL_TIMEOUT_MS = 30_000L
        const val MANUAL_PROTOCOL_TIMEOUT_MS = 12_000L

        val VOLTAGE_REGEX = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*[Vv]?")
        val PROTOCOL_TRY_ORDER = listOf("6", "8", "1", "7", "9", "2", "3", "4", "5")

        val PROTOCOL_NAMES = mapOf(
            "1" to "SAE J1850 PWM",
            "2" to "SAE J1850 VPW",
            "3" to "ISO 9141-2",
            "4" to "ISO 14230-4 KWP (5 baud)",
            "5" to "ISO 14230-4 KWP (fast init)",
            "6" to "ISO 15765-4 CAN 11/500",
            "7" to "ISO 15765-4 CAN 29/500",
            "8" to "ISO 15765-4 CAN 11/250",
            "9" to "ISO 15765-4 CAN 29/250",
        )
    }
}
