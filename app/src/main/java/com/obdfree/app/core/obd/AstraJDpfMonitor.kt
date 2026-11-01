package com.obdfree.app.core.obd

import com.obdfree.app.model.DpfSnapshot
import kotlin.math.abs

class AstraJDpfMonitor(
    private val client: Elm327Client,
) {

    data class RegenerationStatus(
        val active: Boolean,
        val progressPercent: Float,
    )

    suspend fun readSnapshot(): DpfSnapshot = withAstraJHeader {
        val regenerationRaw = readDid("223274", 0x3274, 1)?.firstOrNull()
        val sootPrimary = readDid("22336A", 0x336A, 1)?.firstOrNull()
        val sootFallback = if (sootPrimary == null) {
            readDid("223275", 0x3275, 1)?.firstOrNull()
        } else {
            null
        }

        val temperaturePrimary = readDid("223279", 0x3279, 1)?.firstOrNull()
        val temperatureFallback = if (temperaturePrimary == null) {
            readDid("2220F8", 0x20F8, 2)
        } else {
            null
        }

        val pressurePrimary = readDid("2220F4", 0x20F4, 1)?.firstOrNull()
        val pressureFallback = if (pressurePrimary == null) {
            readDid("223273", 0x3273, 1)?.firstOrNull()
        } else {
            null
        }

        val distance = readDid("223277", 0x3277, 3)
        val averageDistance = readDid("223278", 0x3278, 3)
        val averageDuration = readDid("22327A", 0x327A, 2)
        val interrupted = readDid("223047", 0x3047, 2)

        val coolantResponse = runCatching { client.command("0105", 5_000) }.getOrNull()
        val oilResponse = runCatching { client.command("015C", 5_000) }.getOrNull()
        val rpmResponse = runCatching { client.command("010C", 5_000) }.getOrNull()

        val supported = listOf(
            regenerationRaw,
            sootPrimary,
            sootFallback,
            temperaturePrimary,
            pressurePrimary,
            distance,
        ).any { it != null }

        if (!supported) {
            error(
                "Le calculateur moteur ne répond à aucun PID FAP Astra J connu. " +
                    "Le profil moteur/ECU devra être identifié avant d’ajouter d’autres DIDs.",
            )
        }

        val regenProgress = regenerationRaw?.let(AstraJDpfDecoder::regenerationProgress)
        val profile = when {
            sootPrimary != null -> "Astra J GM • profil 22336A"
            sootFallback != null -> "Astra J GM • profil 223275"
            else -> "Astra J GM • profil FAP partiel"
        }

        DpfSnapshot(
            profile = profile,
            supported = true,
            sootPercent = sootPrimary?.let(AstraJDpfDecoder::percentFromByte255)
                ?: sootFallback?.let(AstraJDpfDecoder::directOrScaledPercent),
            regenerationActive = regenProgress?.let { it > 0f },
            regenerationProgressPercent = regenProgress,
            exhaustTemperatureC = temperaturePrimary
                ?.let(AstraJDpfDecoder::temperatureFiveDegreeByte)
                ?: temperatureFallback?.let(AstraJDpfDecoder::temperatureTenthDegreeWord),
            differentialPressureMbar = pressurePrimary
                ?.let { abs(AstraJDpfDecoder.signedByte(it)).toFloat() }
                ?: pressureFallback?.let { it * 10f },
            distanceSinceRegenerationKm = AstraJDpfDecoder.unsigned24(distance.orEmpty()),
            averageDistanceBetweenRegenerationsKm = AstraJDpfDecoder.unsigned24(averageDistance.orEmpty()),
            averageRegenerationDurationSeconds = AstraJDpfDecoder.unsigned16(averageDuration.orEmpty()),
            interruptedRegenerations = AstraJDpfDecoder.unsigned16(interrupted.orEmpty()),
            coolantTemperatureC = coolantResponse
                ?.let { ObdDecoder.decodeMode01Byte(it, 0x05) }
                ?.minus(40),
            oilTemperatureC = oilResponse
                ?.let { ObdDecoder.decodeMode01Byte(it, 0x5C) }
                ?.minus(40),
            engineRpm = rpmResponse
                ?.let { ObdDecoder.decodeMode01Word(it, 0x0C) }
                ?.div(4),
        )
    }

    suspend fun readRegenerationStatus(): RegenerationStatus = withAstraJHeader {
        val value = readDid("223274", 0x3274, 1)?.firstOrNull()
            ?: error("Le PID de détection de régénération 223274 ne répond pas sur cet ECU.")
        val progress = AstraJDpfDecoder.regenerationProgress(value)
        RegenerationStatus(
            active = progress > 0f,
            progressPercent = progress,
        )
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

        return try {
            block()
        } finally {
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

        return AstraJDpfDecoder.extractDidBytes(
            response = response,
            did = did,
            minimumBytes = minimumBytes,
        )
    }
}
