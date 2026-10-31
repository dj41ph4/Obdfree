package com.obdfree.app.core.obd

import com.obdfree.app.core.transport.ObdTransport
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

        // Do not trust the state left by another application. These are defaults on a
        // genuine ELM327, but explicitly restoring them prevents broken multi-frame CAN
        // replies when a previous tool disabled formatting or flow control.
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

        // Some clones claim to support automatic protocol detection but either return
        // protocol 0 or never actually initialise the vehicle bus. python-OBD uses the
        // same strategy: fall back to trying the standard protocols one by one.
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

                // A simple timeout is often only a slow ECU. Do not reset the adapter on
                // the first timeout; just give the same request another chance.
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
            // AndrOBD uses a warm start for BUFFER FULL / RX ERROR / DATA ERROR class
            // failures. It is less invasive than dropping Bluetooth and reconnecting.
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
        normalizedProtocolNumber()?.digitToIntOrNull(16)?.let { protocol ->
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

        // Same pragmatic fallback order used by python-OBD after automatic mode fails.
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
