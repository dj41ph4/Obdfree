package com.obdfree.app.core.obd

import com.obdfree.app.model.LiveDataSnapshot
import com.obdfree.app.model.TroubleCode

object ObdDecoder {

    data class MonitorStatus(
        val milOn: Boolean,
        val storedDtcCount: Int,
    )

    fun decodeMonitorStatus(response: String): MonitorStatus? =
        decodeMonitorStatuses(response).firstOrNull()

    fun decodeMonitorStatuses(response: String): List<MonitorStatus> {
        val result = mutableListOf<MonitorStatus>()

        response.lineSequence().forEach { rawLine ->
            val compact = hexOnly(rawLine)
            var marker = compact.indexOf("4101")

            while (marker >= 0) {
                val data = compact.drop(marker + 4)
                if (data.length >= 2) {
                    val statusByte = data.substring(0, 2).toIntOrNull(16)
                    if (statusByte != null) {
                        result += MonitorStatus(
                            milOn = statusByte and 0x80 != 0,
                            storedDtcCount = statusByte and 0x7F,
                        )
                    }
                }
                marker = compact.indexOf("4101", startIndex = marker + 4)
            }
        }

        return result
    }

    fun decodeMode01Byte(response: String, pid: Int): Int? =
        pidBytes(response, pid, 1)?.firstOrNull()

    fun decodeMode01Word(response: String, pid: Int): Int? =
        pidBytes(response, pid, 2)?.let { bytes -> (bytes[0] shl 8) or bytes[1] }

    fun decodeTroubleCodes(
        response: String,
        positiveResponse: Int = 0x43,
        expectedCount: Int? = null,
        hasCanCountByte: Boolean? = null,
    ): List<TroubleCode> {
        val payloads = buildList {
            addAll(extractElmFormattedPayloads(response, positiveResponse))
            addAll(extractIsoTpPayloads(response, positiveResponse))
            addAll(extractSimplePayloads(response, positiveResponse))
        }

        val codes = LinkedHashMap<String, TroubleCode>()

        payloads.forEach { fullPayload ->
            val modeIndex = fullPayload.indexOf(positiveResponse)
            if (modeIndex < 0 || modeIndex >= fullPayload.lastIndex) return@forEach

            var payload = fullPayload.drop(modeIndex + 1)
            if (payload.isEmpty()) return@forEach

            val shouldStripCount = when (hasCanCountByte) {
                true -> true
                false -> false
                null -> looksLikeCountPrefixedPayload(payload, expectedCount)
            }

            if (shouldStripCount) {
                val advertised = payload.firstOrNull() ?: 0
                payload = payload.drop(1)
                if (advertised == 0) return@forEach
                if (payload.size >= advertised * 2) {
                    payload = payload.take(advertised * 2)
                }
            } else if (expectedCount != null && expectedCount > 0 && payload.size >= expectedCount * 2) {
                payload = payload.take(expectedCount * 2)
            }

            payload.chunked(2)
                .filter { it.size == 2 }
                .mapNotNull { decodeDtcBytes(it[0], it[1]) }
                .forEach { codes.putIfAbsent(it.code, it) }
        }

        return codes.values.toList()
    }

    fun decodeLiveData(
        rpmResponse: String,
        speedResponse: String,
        coolantResponse: String,
        throttleResponse: String,
    ): LiveDataSnapshot = LiveDataSnapshot(
        rpm = pidBytes(rpmResponse, 0x0C, 2)?.let { (a, b) -> ((a * 256) + b) / 4 },
        speedKph = pidBytes(speedResponse, 0x0D, 1)?.firstOrNull(),
        coolantCelsius = pidBytes(coolantResponse, 0x05, 1)?.firstOrNull()?.minus(40),
        throttlePercent = pidBytes(throttleResponse, 0x11, 1)?.firstOrNull()?.let { it * 100f / 255f },
    )

    private fun extractElmFormattedPayloads(
        response: String,
        positiveResponse: Int,
    ): List<List<Int>> {
        val lines = response.lines()
        val completed = mutableListOf<List<Int>>()
        var index = 0

        while (index < lines.size) {
            val lengthLine = lines[index].trim()
            if (!ELM_LENGTH_LINE.matches(lengthLine)) {
                index++
                continue
            }

            val expectedLength = lengthLine.toIntOrNull(16) ?: run {
                index++
                continue
            }

            val bytes = mutableListOf<Int>()
            var segmentIndex = index + 1
            var sawSegment = false

            while (segmentIndex < lines.size && bytes.size < expectedLength) {
                val match = ELM_SEGMENT_LINE.matchEntire(lines[segmentIndex].trim()) ?: break
                sawSegment = true

                val segmentData = match.groupValues[2]
                    .uppercase()
                    .filter { it in HEX_CHARS }
                    .chunked(2)
                    .filter { it.length == 2 }
                    .mapNotNull { it.toIntOrNull(16) }

                bytes += segmentData
                segmentIndex++
            }

            if (sawSegment && bytes.size >= expectedLength) {
                val payload = bytes.take(expectedLength)
                if (positiveResponse in payload) completed += payload
                index = segmentIndex
            } else {
                index++
            }
        }

        return completed
    }

    private fun extractSimplePayloads(response: String, positiveResponse: Int): List<List<Int>> {
        val marker = positiveResponse.toString(16).padStart(2, '0').uppercase()
        val result = mutableListOf<List<Int>>()

        response.lineSequence().forEach { rawLine ->
            if (looksLikeSpacedCanHeader(rawLine) || looksLikeElmSegmentLine(rawLine)) {
                return@forEach
            }

            val compact = hexOnly(rawLine)
            var index = compact.indexOf(marker)
            while (index >= 0) {
                val tail = compact.drop(index)
                val bytes = tail.chunked(2)
                    .filter { it.length == 2 }
                    .mapNotNull { it.toIntOrNull(16) }
                if (bytes.isNotEmpty()) result += bytes
                index = compact.indexOf(marker, startIndex = index + marker.length)
            }
        }

        return result
    }

    private fun extractIsoTpPayloads(response: String, positiveResponse: Int): List<List<Int>> {
        data class Buffer(val expectedLength: Int, val bytes: MutableList<Int>)

        val buffers = LinkedHashMap<String, Buffer>()
        val completed = mutableListOf<List<Int>>()

        response.lineSequence().forEach { rawLine ->
            val tokens = rawLine.trim().split(Regex("\\s+"))
            if (tokens.size < 2) return@forEach

            val canId = tokens.first().uppercase()
            if ((canId.length != 3 && canId.length != 8) || canId.any { it !in HEX_CHARS }) {
                return@forEach
            }

            val bytes = tokens.drop(1).mapNotNull { token ->
                val clean = token.trim().trim(':').uppercase()
                clean.takeIf { it.length == 2 && it.all { c -> c in HEX_CHARS } }?.toIntOrNull(16)
            }
            if (bytes.isEmpty()) return@forEach

            val pci = bytes.first()
            when (pci ushr 4) {
                0 -> {
                    val length = pci and 0x0F
                    if (length > 0 && bytes.size >= length + 1) {
                        completed += bytes.drop(1).take(length)
                    }
                }

                1 -> {
                    if (bytes.size < 3) return@forEach
                    val length = ((pci and 0x0F) shl 8) or bytes[1]
                    val buffer = Buffer(length, bytes.drop(2).toMutableList())
                    buffers[canId] = buffer
                    if (buffer.bytes.size >= length) {
                        completed += buffer.bytes.take(length)
                        buffers.remove(canId)
                    }
                }

                2 -> {
                    val buffer = buffers[canId] ?: return@forEach
                    buffer.bytes += bytes.drop(1)
                    if (buffer.bytes.size >= buffer.expectedLength) {
                        completed += buffer.bytes.take(buffer.expectedLength)
                        buffers.remove(canId)
                    }
                }

                else -> {
                    if (bytes.contains(positiveResponse)) completed += bytes
                }
            }
        }

        return completed
    }

    private fun looksLikeSpacedCanHeader(rawLine: String): Boolean {
        val first = rawLine.trim().substringBefore(' ').uppercase()
        return (first.length == 3 || first.length == 8) && first.all { it in HEX_CHARS }
    }

    private fun looksLikeElmSegmentLine(rawLine: String): Boolean =
        ELM_SEGMENT_LINE.matches(rawLine.trim())

    private fun looksLikeCountPrefixedPayload(payload: List<Int>, expectedCount: Int?): Boolean {
        val first = payload.firstOrNull() ?: return false
        if (expectedCount != null) return first == expectedCount && payload.size >= 1 + expectedCount * 2
        if (first == 0) return payload.drop(1).all { it == 0 }
        val required = first * 2
        if (required <= 0 || payload.size < required + 1) return false
        return payload.drop(1 + required).all { it == 0 }
    }

    private fun decodeDtcBytes(first: Int, second: Int): TroubleCode? {
        if (first == 0 && second == 0) return null

        val family = when ((first shr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }

        val code = buildString {
            append(family)
            append((first shr 4) and 0x03)
            append((first and 0x0F).toString(16).uppercase())
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
        value.uppercase().filter { it in HEX_CHARS }

    private val HEX_CHARS = ('0'..'9') + ('A'..'F')
    private val ELM_LENGTH_LINE = Regex("^[0-9A-Fa-f]{3}$")
    private val ELM_SEGMENT_LINE = Regex("^([0-9A-Fa-f]):\\s*(.*)$")

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
