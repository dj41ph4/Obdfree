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
    private data class DtcReadAttempt(
        val label: String,
        val response: String,
    )

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

        // PID 01 may be answered by several ECUs. Only a single responder gives us a
        // trustworthy exact count, but any non-zero responder is still a strong signal that
        // Mode 03 must not be accepted as empty without a second representation attempt.
        val expectedStoredCount = monitorStatuses.singleOrNull()?.storedDtcCount
        val anyStoredAnnounced = monitorStatuses.any { it.storedDtcCount > 0 }
        val canCountByte = detectCanCountByte()

        val stored = readDtcMode(
            commandValue = "03",
            responseMode = 0x43,
            expectedCount = expectedStoredCount,
            hasCanCountByte = canCountByte,
            status = DtcStatus.STORED,
            verifyExplicitEmpty = anyStoredAnnounced || expectedStoredCount == null,
            optionalService = false,
        )

        if (expectedStoredCount != null && expectedStoredCount > 0 && stored.size < expectedStoredCount) {
            error(
                "Le calculateur annonce $expectedStoredCount défaut(s), " +
                    "mais seulement ${stored.size} ont pu être décodé(s).",
            )
        }

        val pendingResult = runCatching {
            readDtcMode(
                commandValue = "07",
                responseMode = 0x47,
                expectedCount = null,
                hasCanCountByte = canCountByte,
                status = DtcStatus.PENDING,
                verifyExplicitEmpty = false,
                optionalService = true,
            )
        }

        val permanentResult = runCatching {
            readDtcMode(
                commandValue = "0A",
                responseMode = 0x4A,
                expectedCount = null,
                hasCanCountByte = canCountByte,
                status = DtcStatus.PERMANENT,
                verifyExplicitEmpty = false,
                optionalService = true,
            )
        }

        val result = mergeStatuses(
            stored +
                pendingResult.getOrDefault(emptyList()) +
                permanentResult.getOrDefault(emptyList()),
        )

        // Do not silently turn an unknown Mode 07/0A reply into "no faults" when there was
        // nothing readable in Mode 03 either. Unsupported services are handled as valid empty
        // replies inside readDtcMode; only genuinely undecodable failures reach this point.
        if (result.isEmpty()) {
            pendingResult.exceptionOrNull()?.let { throw it }
            permanentResult.exceptionOrNull()?.let { throw it }
        }

        return result
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
        commandValue: String,
        responseMode: Int,
        expectedCount: Int?,
        hasCanCountByte: Boolean?,
        status: DtcStatus,
        verifyExplicitEmpty: Boolean,
        optionalService: Boolean,
    ): List<TroubleCode> {
        val requestedService = commandValue.toInt(16)
        val attempts = mutableListOf<DtcReadAttempt>()

        suspend fun inspect(label: String, response: String): List<TroubleCode>? {
            attempts += DtcReadAttempt(label, response)
            throwIfAdapterError(response)

            val nrc = ObdDecoder.negativeResponseCode(response, requestedService)
            if (nrc != null) {
                if (optionalService && nrc in UNSUPPORTED_SERVICE_NRCS) return emptyList()
                if (nrc != NRC_RESPONSE_PENDING) {
                    error("Le calculateur refuse le mode $commandValue (NRC ${nrc.toString(16).uppercase().padStart(2, '0')}).")
                }
                return null
            }

            val explicitEmpty = ObdDecoder.isExplicitNoDtcResponse(
                response = response,
                positiveResponse = responseMode,
                hasCanCountByte = hasCanCountByte,
            )

            if (expectedCount == 0 && explicitEmpty) return emptyList()

            val decoded = decodeDtcResponse(
                response = response,
                responseMode = responseMode,
                expectedCount = expectedCount,
                hasCanCountByte = hasCanCountByte,
            )
            if (decoded.isNotEmpty()) {
                return decoded.map { it.copy(statuses = setOf(status)) }
            }

            if (explicitEmpty && !verifyExplicitEmpty) return emptyList()
            return null
        }

        val normalResponse = retryCommand(commandValue, attempts = 2, timeoutMs = 12_000)
        inspect("normal", normalResponse)?.let { return it }

        val headerResponse = runCatching { readWithHeaders(commandValue) }.getOrNull()
        if (headerResponse != null) {
            inspect("headers", headerResponse)?.let { return it }
        }

        if (hasCanCountByte != false) {
            val rawCanResponse = runCatching { readWithRawCan(commandValue) }.getOrNull()
            if (rawCanResponse != null) {
                inspect("can-brut", rawCanResponse)?.let { return it }
            }
        }

        val allExplicitlyEmpty = attempts.isNotEmpty() && attempts.all { attempt ->
            ObdDecoder.isExplicitNoDtcResponse(
                response = attempt.response,
                positiveResponse = responseMode,
                hasCanCountByte = hasCanCountByte,
            )
        }

        if (allExplicitlyEmpty && expectedCount != null && expectedCount == 0) {
            return emptyList()
        }
        if (allExplicitlyEmpty && !verifyExplicitEmpty) {
            return emptyList()
        }

        val protocolText = protocolDescription ?: protocolNumber ?: "inconnu"
        val trace = attempts.joinToString(" • ") { attempt ->
            "${attempt.label}=${diagnosticSnippet(attempt.response)}"
        }
        error("Réponse DTC ELM non décodable (mode $commandValue, protocole $protocolText). $trace")
    }

    private fun decodeDtcResponse(
        response: String,
        responseMode: Int,
        expectedCount: Int?,
        hasCanCountByte: Boolean?,
    ): List<TroubleCode> {
        val hints = mutableListOf<Boolean?>()
        hints += hasCanCountByte
        hints += null
        if (hasCanCountByte != null) hints += !hasCanCountByte

        val candidates = hints.distinct().map { hint ->
            ObdDecoder.decodeTroubleCodes(
                response = response,
                positiveResponse = responseMode,
                expectedCount = expectedCount,
                hasCanCountByte = hint,
            )
        }

        if (expectedCount != null && expectedCount > 0) {
            candidates.firstOrNull { it.size == expectedCount }?.let { return it }
        }

        return candidates.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private suspend fun readMode01Byte(command: String, pid: Int): Int? {
        val response = runCatching { retryCommand(command, attempts = 2, timeoutMs = 7_000) }
            .getOrNull() ?: return null
        if (isNoData(response)) return null
        return ObdDecoder.decodeMode01Byte(response, pid)
    }

    private suspend fun readMode01Word(command: String, pid: Int): Int? {
        val response = runCatching { retryCommand(command, attempts = 2, timeoutMs = 7_000) }
            .getOrNull() ?: return null
        if (isNoData(response)) return null
        return ObdDecoder.decodeMode01Word(response, pid)
    }

    private suspend fun readWithHeaders(value: String): String {
        return try {
            bestEffortAt("ATS1")
            bestEffortAt("ATH1")
            bestEffortAt("ATCAF1")
            retryCommand(value, attempts = 2, timeoutMs = 12_000)
        } finally {
            bestEffortAt("ATH0")
            bestEffortAt("ATS0")
        }
    }

    private suspend fun readWithRawCan(value: String): String {
        return try {
            bestEffortAt("ATS1")
            bestEffortAt("ATH1")
            bestEffortAt("ATCFC1")
            bestEffortAt("ATCAF0")
            retryCommand(value, attempts = 2, timeoutMs = 12_000)
        } finally {
            bestEffortAt("ATCAF1")
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

    private fun isNoData(response: String): Boolean =
        response.uppercase().filterNot(Char::isWhitespace).contains("NODATA")

    private fun diagnosticSnippet(response: String): String =
        response
            .replace('\r', ' ')
            .replace('\n', '|')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "<vide>" }
            .take(180)

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
        const val NRC_RESPONSE_PENDING = 0x78

        val UNSUPPORTED_SERVICE_NRCS = setOf(0x11, 0x12, 0x31)
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
