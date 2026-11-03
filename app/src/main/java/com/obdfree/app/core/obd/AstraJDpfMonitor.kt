package com.obdfree.app.core.obd

import com.obdfree.app.model.AstraJEcuFamily
import com.obdfree.app.model.AstraJEcuIdentity
import com.obdfree.app.model.DpfSnapshot
import kotlin.math.abs

class AstraJDpfMonitor(
    private val client: Elm327Client,
) {

    data class RegenerationStatus(
        val active: Boolean,
        val progressPercent: Float?,
        val stateLabel: String? = null,
    )

    data class DeviceControlProbe(
        val supported: Boolean,
        val message: String,
        val rawResponse: String,
    )

    private val detector = AstraJEcuDetector(client)
    private var cachedIdentity: AstraJEcuIdentity? = null

    suspend fun detectIdentity(force: Boolean = false): AstraJEcuIdentity {
        if (!force) cachedIdentity?.let { return it }
        return detector.detect().also { cachedIdentity = it }
    }

    suspend fun readSnapshot(): DpfSnapshot {
        val identity = runCatching { detectIdentity() }
            .getOrElse { AstraJEcuIdentity() }

        return withAstraJHeader {
            val values = when (identity.family) {
                AstraJEcuFamily.DENSO_DECE01 -> readDensoProfile()
                AstraJEcuFamily.GM_E87 -> readE87Profile()
                AstraJEcuFamily.UNKNOWN -> readAdaptiveProfile()
            }

            val coolantResponse = runCatching { client.command("0105", 5_000) }.getOrNull()
            val oilResponse = runCatching { client.command("015C", 5_000) }.getOrNull()
            val rpmResponse = runCatching { client.command("010C", 5_000) }.getOrNull()

            val interlock = readDid("22326D", 0x326D, 1)
            val deviceStatus = readDid("2232EB", 0x32EB, 1)
            val deviceReject = readDid("2232EC", 0x32EC, 1)

            if (!values.supported) {
                error(
                    "Le calculateur répond mais aucun profil FAP Astra J 1.7 connu ne correspond. " +
                        "Identité ECU: ${identity.systemIdentification ?: "?"} / ${identity.engineCode ?: "?"}.",
                )
            }

            DpfSnapshot(
                profile = profileName(identity, values.profile),
                supported = true,
                ecuIdentity = identity,
                sootPercent = values.sootPercent,
                regenerationActive = values.regenerationActive,
                regenerationProgressPercent = values.regenerationProgressPercent,
                exhaustTemperatureC = values.exhaustTemperatureC,
                differentialPressureMbar = values.differentialPressureMbar,
                distanceSinceRegenerationKm = values.distanceSinceRegenerationKm,
                averageDistanceBetweenRegenerationsKm = values.averageDistanceBetweenRegenerationsKm,
                averageRegenerationDurationSeconds = values.averageRegenerationDurationSeconds,
                interruptedRegenerations = values.interruptedRegenerations,
                coolantTemperatureC = coolantResponse
                    ?.let { ObdDecoder.decodeMode01Byte(it, 0x05) }
                    ?.minus(40),
                oilTemperatureC = oilResponse
                    ?.let { ObdDecoder.decodeMode01Byte(it, 0x5C) }
                    ?.minus(40),
                engineRpm = rpmResponse
                    ?.let { ObdDecoder.decodeMode01Word(it, 0x0C) }
                    ?.div(4),
                regenerationInterlockRaw = AstraJGmlanCodec.hex(interlock),
                deviceControlStatusRaw = AstraJGmlanCodec.hex(deviceStatus),
                deviceControlRejectRaw = AstraJGmlanCodec.hex(deviceReject),
            )
        }
    }

    suspend fun readRegenerationStatus(): RegenerationStatus {
        val identity = runCatching { detectIdentity() }.getOrNull()
        return withAstraJHeader {
            readRegenerationStatusInternal(identity?.family ?: AstraJEcuFamily.UNKNOWN)
        }
    }

    /**
     * Non-destructive GMLAN capability probe.
     *
     * GMW3110 defines 10 03 as InitiateDiagnosticOperation / enable DTC algorithms while
     * DeviceControl is active. AE 00 releases all devices rather than commanding a new state,
     * so the sequence can test the service path without guessing the DPF CPID. 20 always puts
     * the module back in normal mode afterwards.
     */
    suspend fun probeDeviceControl(): DeviceControlProbe = withAstraJHeader {
        val cleanupBefore = runCatching { client.command("20", 4_000) }.getOrNull()
        val diagnosticMode = runCatching { client.command("1003", 6_000) }.getOrNull()
        val aeResponse = runCatching { client.command("AE00", 6_000) }.getOrNull()
        val cleanupAfter = runCatching { client.command("20", 4_000) }.getOrNull()

        val modeCompact = compactHex(diagnosticMode)
        val aeCompact = compactHex(aeResponse)
        val modeNrc = diagnosticMode?.let { ObdDecoder.negativeResponseCode(it, 0x10) }
        val aeNrc = aeResponse?.let { ObdDecoder.negativeResponseCode(it, 0xAE) }

        val diagnosticModeAccepted = "5003" in modeCompact ||
            (modeNrc != null && modeNrc !in setOf(0x11, 0x12))
        val aePositive = "EE00" in aeCompact
        val aeRecognized = aeNrc != null && aeNrc !in setOf(0x11, 0x12)
        val aeNoData = aeResponse.isNullOrBlank() || aeResponse.contains("NO DATA", ignoreCase = true)
        val supported = aePositive || aeRecognized

        val message = when {
            aePositive -> "GMLAN DeviceControl confirmé: 10 03 puis AE 00 acceptés par l’ECU."
            aeRecognized ->
                "Le service AE est reconnu par l’ECU mais refusé avec NRC ${hexByte(aeNrc)}. " +
                    if (diagnosticModeAccepted) "Le mode diagnostic 10 03 est accepté." else "Le mode 10 03 reste à confirmer."
            aeNoData && diagnosticModeAccepted ->
                "Le mode GMLAN 10 03 est accepté, mais AE 00 ne renvoie rien. " +
                    "Le DeviceControl peut demander une condition/session supplémentaire sur cet E87."
            aeNoData ->
                "Aucune réponse à AE 00 et 10 03 non confirmé. Test GMLAN non concluant."
            modeNrc != null ->
                "10 03 refusé avec NRC ${hexByte(modeNrc)}; AE non confirmé."
            else -> "Réponses GMLAN reçues mais aucun acquittement DeviceControl exploitable."
        }

        val raw = buildList {
            cleanupBefore?.let { add("20(pre)=${singleLine(it)}") }
            diagnosticMode?.let { add("1003=${singleLine(it)}") }
            aeResponse?.let { add("AE00=${singleLine(it)}") }
            cleanupAfter?.let { add("20(post)=${singleLine(it)}") }
        }.joinToString(" | ").take(360)

        DeviceControlProbe(
            supported = supported,
            message = message,
            rawResponse = raw,
        )
    }

    private data class ProfileValues(
        val profile: String,
        val supported: Boolean,
        val sootPercent: Float? = null,
        val regenerationActive: Boolean? = null,
        val regenerationProgressPercent: Float? = null,
        val exhaustTemperatureC: Float? = null,
        val differentialPressureMbar: Float? = null,
        val distanceSinceRegenerationKm: Int? = null,
        val averageDistanceBetweenRegenerationsKm: Int? = null,
        val averageRegenerationDurationSeconds: Int? = null,
        val interruptedRegenerations: Int? = null,
    )

    private suspend fun readDensoProfile(): ProfileValues {
        val regenProgressRaw = readDid("223274", 0x3274, 1)?.firstOrNull()
        val regenState = readRegenState20F2()
        val regenRequest = readDid("2220FA", 0x20FA, 1)?.firstOrNull()
        val soot = readDid("223275", 0x3275, 1)?.firstOrNull()
        val temperature = readDid("223279", 0x3279, 1)?.firstOrNull()
        val pressure = readDid("223035", 0x3035, 1)?.firstOrNull()
            ?: readDid("223273", 0x3273, 1)?.firstOrNull()
        val distance = readDid("223277", 0x3277, 3)
        val avgDistance = readDid("223278", 0x3278, 3)
        val avgDuration = readDid("22327A", 0x327A, 2)
        val interrupted = readDid("223047", 0x3047, 2)

        val regenProgress = regenProgressRaw?.let(AstraJDpfDecoder::regenerationProgress)
        val active = regenState?.let(::isActiveRegenState)
            ?: regenProgress?.let { it > 0f }
            ?: regenRequest?.let { it and 0x01 != 0 }

        return ProfileValues(
            profile = "Denso A17DTJ/A17DTR",
            supported = listOf(soot, regenProgressRaw, regenState, temperature, pressure, distance).any { it != null },
            sootPercent = soot?.let(AstraJDpfDecoder::directOrScaledPercent),
            regenerationActive = active,
            regenerationProgressPercent = regenProgress,
            exhaustTemperatureC = temperature?.let(AstraJDpfDecoder::temperatureFiveDegreeByte),
            // A17DTJ projects using DID 3035 compensate the sensor zero point by 20 counts.
            differentialPressureMbar = pressure?.let { (it - 20).coerceAtLeast(0).toFloat() },
            distanceSinceRegenerationKm = AstraJDpfDecoder.unsigned24(distance.orEmpty()),
            averageDistanceBetweenRegenerationsKm = AstraJDpfDecoder.unsigned24(avgDistance.orEmpty()),
            averageRegenerationDurationSeconds = AstraJDpfDecoder.unsigned16(avgDuration.orEmpty()),
            interruptedRegenerations = AstraJDpfDecoder.unsigned16(interrupted.orEmpty()),
        )
    }

    private suspend fun readE87Profile(): ProfileValues {
        val aa = if (cachedIdentity?.engineCode?.contains("A17DTS", ignoreCase = true) == true) readA17DtsAaPackets() else Pair(null, null)
        val regenState = readRegenState20F2()
        val regenRequest = readDid("2220FA", 0x20FA, 1)?.firstOrNull()
        val legacyProgress = readDid("223274", 0x3274, 1)?.firstOrNull()

        // For Astra J 1.7 CDTI, prefer the Astra-J-specific 32xx/30xx identifiers.
        // The broader E87 identifiers stay as fallbacks because E87 calibrations differ.
        val sootAstraJ = readDid("223275", 0x3275, 1)?.firstOrNull()
        val sootE87Fallback = readDid("22336A", 0x336A, 1)?.firstOrNull()

        val tempAstraJ = readDid("223279", 0x3279, 1)?.firstOrNull()
        val tempE87Fallback = readDid("2220F8", 0x20F8, 2)

        // 3035 is the voltage of the differential-pressure sensor, not pressure in mbar.
        val pressureAstraJ = readDid("223273", 0x3273, 1)?.firstOrNull()
        val pressureE87Fallback = readDid("2220F4", 0x20F4, 1)?.firstOrNull()

        val distanceAstraJ = readDid("223277", 0x3277, 3)
        val distanceE87Fallback = readDid("223039", 0x3039, 2)

        val active = aa.second?.regenerationProgressPercent?.let { it > 0f }
            ?: regenState?.let(::isActiveRegenState)
            ?: regenRequest?.let { it and 0x01 != 0 }
            ?: legacyProgress?.let { it > 0 }

        val astraDistance = AstraJDpfDecoder.unsigned24(distanceAstraJ.orEmpty())
        val fallbackDistance = AstraJDpfDecoder.unsigned16(distanceE87Fallback.orEmpty())
            ?.takeIf { it > 0 }

        return ProfileValues(
            profile = "GM E87 • Astra J 1.7",
            supported = listOf(
                aa.first,
                aa.second,
                regenState,
                regenRequest,
                sootAstraJ,
                sootE87Fallback,
                tempAstraJ,
                tempE87Fallback,
                pressureAstraJ,
                pressureE87Fallback,
                distanceAstraJ,
            ).any { it != null },
            sootPercent = aa.first?.sootPercent
                ?: sootAstraJ?.let(AstraJDpfDecoder::directOrScaledPercent)
                ?: sootE87Fallback?.let(AstraJDpfDecoder::directOrScaledPercent),
            regenerationActive = active,
            regenerationProgressPercent = aa.second?.regenerationProgressPercent
                ?: legacyProgress?.let(AstraJDpfDecoder::regenerationProgress),
            exhaustTemperatureC = tempAstraJ?.let(AstraJDpfDecoder::temperatureFiveDegreeByte)
                ?: tempE87Fallback?.let(AstraJDpfDecoder::temperatureTenthDegreeWord),
            differentialPressureMbar = aa.first?.differentialPressureMbar
                ?: pressureAstraJ?.let { it * 10f }
                ?: pressureE87Fallback
                    ?.let(AstraJDpfDecoder::signedByte)
                    ?.let { abs(it) * 10f },
            distanceSinceRegenerationKm = aa.second?.distanceSinceRegenerationKm ?: astraDistance ?: fallbackDistance,
        )
    }

    private suspend fun readAdaptiveProfile(): ProfileValues {
        val denso = readDensoProfile()
        val e87 = readE87Profile()
        return when {
            e87.supported && !denso.supported -> e87
            denso.supported && !e87.supported -> denso
            e87.sootPercent != null || e87.exhaustTemperatureC != null -> e87.copy(profile = "Auto • E87 compatible")
            else -> denso.copy(profile = "Auto • Denso compatible")
        }
    }

    private suspend fun readRegenerationStatusInternal(family: AstraJEcuFamily): RegenerationStatus {
        // An A17DTS-specific AA packet is more meaningful than a borrowed Astra-K DID.
        val aa = if (family == AstraJEcuFamily.GM_E87 &&
            cachedIdentity?.engineCode?.contains("A17DTS", ignoreCase = true) == true
        ) readA17DtsAaPackets().second else null
        val state = readRegenState20F2()
        val request = readDid("2220FA", 0x20FA, 1)?.firstOrNull()
        val legacy = readDid("223274", 0x3274, 1)?.firstOrNull()

        val stateCode = state?.and(0x07)
        val active = when {
            aa != null -> aa.regenerationProgressPercent > 0f
            stateCode != null -> isActiveRegenState(stateCode)
            family == AstraJEcuFamily.DENSO_DECE01 && legacy != null -> legacy > 0
            request != null -> request and 0x01 != 0
            legacy != null -> legacy > 0
            else -> error("Aucun PID de statut de régénération Astra J ne répond sur cet ECU.")
        }

        return RegenerationStatus(
            active = active,
            progressPercent = aa?.regenerationProgressPercent ?: legacy?.let(AstraJDpfDecoder::regenerationProgress),
            stateLabel = stateCode?.let(::regenStateLabel),
        )
    }

    /**
     * Opel GM AA diagnostic packets use response ID 5E8 rather than the usual 7E8.
     * These are read-only requests. Always restore the response filter before Mode 01/22.
     */
    private suspend fun readA17DtsAaPackets(): Pair<GmAaDpfCodec.Packet29?, GmAaDpfCodec.Packet2A?> {
        val headers = runCatching { client.command("ATH1", 4_000) }.getOrNull()
        val filter = runCatching { client.command("ATCRA5E8", 4_000) }.getOrNull()
        if (headers == null || filter == null || headers.contains("?") || filter.contains("?")) {
            runCatching { client.command("ATCRA7E8", 4_000) }
            runCatching { client.command("ATH0", 4_000) }
            return Pair(null, null)
        }
        return try {
            val p29 = runCatching { client.command("AA0329", 5_000) }.getOrNull()
                ?.let(GmAaDpfCodec::packet29)
            val p2a = runCatching { client.command("AA032A", 5_000) }.getOrNull()
                ?.let(GmAaDpfCodec::packet2A)
            Pair(p29, p2a)
        } finally {
            runCatching { client.command("ATCRA7E8", 4_000) }
            runCatching { client.command("ATH0", 4_000) }
        }
    }

    private suspend fun readRegenState20F2(): Int? =
        readDid("2220F2", 0x20F2, 1)?.firstOrNull()?.and(0x07)

    private fun isActiveRegenState(state: Int): Boolean = state in 1..4 || state == 6 || state == 7

    private fun regenStateLabel(state: Int): String = when (state) {
        0 -> "Arrêtée"
        1 -> "Phase 1"
        2 -> "Phase 2"
        3 -> "Phase 3"
        4 -> "Phase 4"
        6 -> "Montée en température"
        7 -> "Régénération active"
        else -> "État $state"
    }

    private fun profileName(identity: AstraJEcuIdentity, profile: String): String {
        val engine = identity.engineCode?.takeIf(String::isNotBlank)?.let { " • $it" }.orEmpty()
        val confidence = identity.confidence.takeIf { it > 0 }?.let { " • $it%" }.orEmpty()
        return "${identity.displayName}$engine$confidence • $profile"
    }

    private suspend fun <T> withAstraJHeader(block: suspend () -> T): T {
        val protocolResponse = client.command("ATSP6", 5_000)
        if (protocolResponse.contains("?")) {
            error("L’adaptateur ELM327 ne permet pas de sélectionner le CAN 11 bits / 500 kbit/s.")
        }

        val headerResponse = client.command("ATSH7E0", 5_000)
        if (headerResponse.contains("?")) {
            error("L’adaptateur ELM327 refuse l’adressage du calculateur moteur 7E0.")
        }
        runCatching { client.command("ATCAF1", 5_000) }

        return try {
            block()
        } finally {
            runCatching { client.command("ATCRA", 4_000) }
            runCatching { client.command("ATSH7DF", 4_000) }
            runCatching { client.command("ATSP0", 4_000) }
        }
    }

    private suspend fun readDid(
        request: String,
        did: Int,
        minimumBytes: Int,
    ): List<Int>? {
        val response = runCatching { client.command(request, 6_000) }.getOrNull()
            ?: return null

        if (response.contains("NO DATA", ignoreCase = true)) return null
        if (ObdDecoder.negativeResponseCode(response, 0x22) != null) return null

        return AstraJGmlanCodec.bytesAfterMarker(
            response = response,
            marker = listOf(0x62, (did shr 8) and 0xFF, did and 0xFF),
            minimumBytes = minimumBytes,
        )
    }

    private fun compactHex(value: String?): String = value.orEmpty()
        .uppercase()
        .filter { it in '0'..'9' || it in 'A'..'F' }

    private fun singleLine(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(90)

    private fun hexByte(value: Int?): String = value
        ?.toString(16)
        ?.uppercase()
        ?.padStart(2, '0')
        ?: "??"
}
