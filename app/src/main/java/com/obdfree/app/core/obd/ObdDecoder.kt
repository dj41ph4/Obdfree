package com.obdfree.app.core.obd

import com.obdfree.app.model.LiveDataSnapshot
import com.obdfree.app.model.TroubleCode

object ObdDecoder {

    fun decodeTroubleCodes(response: String): List<TroubleCode> {
        val frames = response
            .lineSequence()
            .map(::hexOnly)
            .filter { it.length >= 2 }
            .toList()

        val pairs = buildList {
            frames.forEach { frame ->
                val start = frame.indexOf("43")
                if (start < 0) return@forEach

                val payload = frame.substring(start + 2)
                payload.chunked(4)
                    .filter { it.length == 4 && it != "0000" }
                    .forEach(::add)
            }
        }

        return pairs
            .mapNotNull(::decodeDtcPair)
            .distinctBy { it.code }
    }

    fun decodeLiveData(
        rpmResponse: String,
        speedResponse: String,
        coolantResponse: String,
        throttleResponse: String,
    ): LiveDataSnapshot = LiveDataSnapshot(
        rpm = pidBytes(rpmResponse, 0x0C, 2)?.let { (a, b) ->
            ((a * 256) + b) / 4
        },
        speedKph = pidBytes(speedResponse, 0x0D, 1)?.firstOrNull(),
        coolantCelsius = pidBytes(coolantResponse, 0x05, 1)
            ?.firstOrNull()
            ?.minus(40),
        throttlePercent = pidBytes(throttleResponse, 0x11, 1)
            ?.firstOrNull()
            ?.let { it * 100f / 255f },
    )

    private fun decodeDtcPair(pair: String): TroubleCode? {
        val raw = pair.toIntOrNull(16) ?: return null
        if (raw == 0) return null

        val first = (raw shr 8) and 0xFF
        val second = raw and 0xFF

        val family = when ((first shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }

        val digit2 = (first shr 4) and 0x03
        val digit3 = first and 0x0F
        val code = buildString {
            append(family)
            append(digit2)
            append(digit3.toString(16).uppercase())
            append(second.toString(16).padStart(2, '0').uppercase())
        }

        return TroubleCode(
            code = code,
            category = when (family) {
                'P' -> "Groupe motopropulseur"
                'C' -> "Châssis"
                'B' -> "Carrosserie"
                else -> "Réseau"
            },
            description = COMMON_DTC[code],
        )
    }

    private fun pidBytes(response: String, pid: Int, count: Int): List<Int>? {
        val needle = "41" + pid.toString(16).padStart(2, '0').uppercase()

        response.lineSequence().forEach { rawLine ->
            val line = hexOnly(rawLine)
            val index = line.indexOf(needle)
            if (index < 0) return@forEach

            val data = line.drop(index + needle.length)
            if (data.length < count * 2) return@forEach

            return (0 until count).map { offset ->
                data.substring(offset * 2, offset * 2 + 2).toInt(16)
            }
        }

        return null
    }

    private fun hexOnly(value: String): String =
        value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }

    private val COMMON_DTC = mapOf(
        "P0100" to "Circuit du débitmètre d'air",
        "P0101" to "Plage/performance du débitmètre d'air",
        "P0113" to "Signal élevé du capteur de température d'air d'admission",
        "P0128" to "Température du liquide de refroidissement sous la régulation",
        "P0171" to "Mélange trop pauvre, banc 1",
        "P0172" to "Mélange trop riche, banc 1",
        "P0300" to "Ratés d'allumage aléatoires ou multiples",
        "P0301" to "Ratés d'allumage cylindre 1",
        "P0302" to "Ratés d'allumage cylindre 2",
        "P0303" to "Ratés d'allumage cylindre 3",
        "P0304" to "Ratés d'allumage cylindre 4",
        "P0420" to "Efficacité du catalyseur sous le seuil, banc 1",
        "P0430" to "Efficacité du catalyseur sous le seuil, banc 2",
        "P0442" to "Petite fuite du système EVAP",
        "P0455" to "Fuite importante du système EVAP",
    )
}
