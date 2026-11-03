package com.obdfree.app.core.obd

/**
 * Read-only GMLAN AA packets reported on Opel Astra J A17DTS.
 * Unlike UDS Mode 22 replies, AA packet answers can arrive on CAN ID 5E8.
 * A packet number is not an actuator CPID and must never be used with AE.
 */
object GmAaDpfCodec {
    data class Packet29(
        val sootPercent: Float,
        val differentialPressureMbar: Float,
        val pressureSensorVoltage: Float,
        val regenerationsSinceReplacement: Int,
    )
    data class Packet2A(
        val distanceSinceReplacementKm: Int,
        val distanceSinceRegenerationKm: Int,
        val regenerationProgressPercent: Float,
    )

    fun packet29(response: String): Packet29? {
        val data = payload(response, 0x29) ?: return null
        if (data.size < 7) return null
        val soot = data[0] * 200f / 255f
        val pressureKpa = data[2] - 5
        if (soot > 200f || pressureKpa < -5) return null
        return Packet29(
            sootPercent = soot,
            differentialPressureMbar = pressureKpa * 10f,
            pressureSensorVoltage = data[1] * 5f / 255f,
            regenerationsSinceReplacement = (data[3] shl 8) or data[4],
        )
    }

    fun packet2A(response: String): Packet2A? {
        val data = payload(response, 0x2A) ?: return null
        if (data.size < 7) return null
        return Packet2A(
            distanceSinceReplacementKm = u24(data, 0),
            distanceSinceRegenerationKm = u24(data, 3),
            regenerationProgressPercent = data[6] * 100f / 255f,
        )
    }

    private fun u24(values: List<Int>, start: Int) =
        (values[start] shl 16) or (values[start + 1] shl 8) or values[start + 2]

    /**
     * Accept 5E8 29 ..., 5E8 08 29 ... and compact 5E829... frames.
     * Never search arbitrary hex inside diagnostic text: NO DATA contains hex letters.
     */
    private fun payload(raw: String, packet: Int): List<Int>? {
        for (line in raw.lines()) {
            var compact = line.trim().replace(" ", "").replace("\t", "").uppercase()
            if (!compact.startsWith("5E8") || compact.length <= 5) continue
            compact = compact.drop(3)
            if (compact.length % 2 != 0 || !compact.all { it in '0'..'9' || it in 'A'..'F' }) continue
            val bytes = compact.chunked(2).map { it.toInt(16) }
            val start = when {
                bytes.firstOrNull() == packet -> 1
                bytes.size >= 2 && bytes[0] in 7..15 && bytes[1] == packet -> 2
                else -> continue
            }
            return bytes.drop(start)
        }
        return null
    }
}
