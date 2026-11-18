package com.obdfree.app.core.obd

/**
 * Decodeur de la reponse Fiat KWP2000 readDTCByStatus (18 00 FF 00).
 *
 * Distinct du mode OBD-II 03 : 58 <count> (<DTC-hi> <DTC-lo> <status>)*.
 * Lecture seule. Le status constructeur est conserve brut : aucun drapeau
 * "actif" ou "historique" n'est invente sans definition de l'ECU.
 */
data class FiatKwpFault(
    val code: String,
    val statusHex: String,
)

data class FiatKwpReport(
    val faults: List<FiatKwpFault>,
    val rawResponse: String,
)

object FiatKwpDtcDecoder {
    fun decode(raw: String): FiatKwpReport {
        val upper = raw.uppercase()
        if (listOf(
                "NO DATA", "UNABLE TO CONNECT", "BUS INIT", "BUS ERROR",
                "RX ERROR", "DATA ERROR", "BUFFER FULL", "STOPPED",
            ).any(upper::contains)
        ) {
            error("Lecture EPS invalide ou incomplete : verifie la liaison et l'adaptateur A1 (broche 9).")
        }

        val packets = raw.lineSequence().mapNotNull(::decodeLine).toList()
        val negative = packets.firstOrNull { it.size >= 3 && it[0] == 0x7F && it[1] == 0x18 }
        if (negative != null) {
            error("ECU EPS : service de lecture refuse (KWP 7F 18 %02X).".format(negative[2]))
        }
        val positive = packets.firstOrNull { it.isNotEmpty() && it[0] == 0x58 }
            ?: error("Reponse EPS non reconnue : impossible de confirmer la lecture des defauts.")

        require(positive.size >= 2) { "Reponse EPS tronquee : compteur absent." }
        val count = positive[1]
        val expected = 2 + count * 3
        require(positive.size >= expected) {
            "Reponse EPS incomplete : $count defaut(s) annonces, ${positive.size - 2} octets recus."
        }
        val faults = (0 until count).map { index ->
            val start = 2 + index * 3
            FiatKwpFault(
                code = decodeDtc(positive[start], positive[start + 1]),
                statusHex = "%02X".format(positive[start + 2]),
            )
        }
        return FiatKwpReport(faults = faults, rawResponse = raw)
    }

    /** Supporte la trame avec entete KWP ATH1 et la charge utile ATH0. */
    internal fun decodeLine(line: String): List<Int>? {
        val trimmed = line.trim().uppercase()
        if (trimmed.isEmpty() || trimmed.any { it !in "0123456789ABCDEF \t" }) return null
        val compact = trimmed.filterNot(Char::isWhitespace)
        if (compact.length < 2 || compact.length % 2 != 0) return null
        val bytes = compact.chunked(2).map { it.toIntOrNull(16) ?: return null }
        if (bytes.first() == 0x58 || bytes.first() == 0x7F) return bytes

        // Physically addressed KWP response, destined to tester F1.
        // Source 10 is ONLY the provisional ECU address selected by the
        // diagnostic module. Ignore other senders to avoid false "0 faults".
        if (bytes.size < 5 || (bytes[0] and 0xC0) != 0x80 ||
            bytes[1] != 0xF1 || bytes[2] != 0x10
        ) return null

        var payloadLength = bytes[0] and 0x3F
        var offset = 3
        if (payloadLength == 0) {
            if (bytes.size < 6) return null
            payloadLength = bytes[3]
            offset = 4
        }
        if (payloadLength <= 0) return null
        val expectedWithoutChecksum = offset + payloadLength
        if (bytes.size != expectedWithoutChecksum &&
            bytes.size != expectedWithoutChecksum + 1
        ) return null

        // Certain ELMs include KWP checksum when ATH1 is enabled, others
        // strip it. If present, never silently accept corrupted frames.
        if (bytes.size == expectedWithoutChecksum + 1) {
            val checksum = bytes.dropLast(1).sum() and 0xFF
            if (checksum != bytes.last()) return null
        }
        return bytes.subList(offset, expectedWithoutChecksum)
    }

    internal fun decodeDtc(first: Int, second: Int): String {
        val family = "PCBU"[(first shr 6) and 0x03]
        val digit = (first shr 4) and 0x03
        val nibble = first and 0x0F
        return "$family$digit${"%X".format(nibble)}${"%02X".format(second)}"
    }
}
