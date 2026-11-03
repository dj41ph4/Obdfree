package com.obdfree.app.core.obd

import com.obdfree.app.model.AstraJEcuFamily
import com.obdfree.app.model.AstraJEcuIdentity

class AstraJEcuDetector(
    private val client: Elm327Client,
) {

    suspend fun detect(): AstraJEcuIdentity = withEngineHeader {
        val vin = readTextLocalIdentifier(0x90, maxBytes = 17)
            ?: readMode09Text("0902", 0x02)
        val systemId = readTextLocalIdentifier(0x92, maxBytes = 24)
        val engineCode = readTextLocalIdentifier(0x97, maxBytes = 24)
        val programmingDate = readDateLocalIdentifier(0x99)
        val supplier = readTextLocalIdentifier(0xDA, maxBytes = 24)

        classify(
            vin = vin,
            systemId = systemId,
            engineCode = engineCode,
            programmingDate = programmingDate,
            supplier = supplier,
        )
    }

    fun classify(
        vin: String?,
        systemId: String?,
        engineCode: String?,
        programmingDate: String?,
        supplier: String?,
    ): AstraJEcuIdentity {
        val signature = listOfNotNull(systemId, engineCode, supplier)
            .joinToString(" ")
            .uppercase()

        val densoEngines = listOf("A17DTJ", "A17DTR")
        val e87Engines = listOf(
            "A17DTC", "A17DTE", "A17DTF", "A17DTS", "A17DTN", "A17DTL",
            "LUE", "LKR", "LUD", "PL1",
        )

        val family: AstraJEcuFamily
        val confidence: Int

        when {
            "DENSO" in signature -> {
                family = AstraJEcuFamily.DENSO_DECE01
                confidence = 98
            }

            "E87" in signature || "DELCO" in signature -> {
                family = AstraJEcuFamily.GM_E87
                confidence = 98
            }

            "BOSCH0100" in signature -> {
                // Astra J E87 captures exposed by diagnostic tools report BOSCH0100
                // as system identification even though the controller family is E87.
                family = AstraJEcuFamily.GM_E87
                confidence = 90
            }

            densoEngines.any(signature::contains) -> {
                family = AstraJEcuFamily.DENSO_DECE01
                confidence = 88
            }

            e87Engines.any(signature::contains) -> {
                family = AstraJEcuFamily.GM_E87
                confidence = 82
            }

            else -> {
                family = AstraJEcuFamily.UNKNOWN
                confidence = 0
            }
        }

        return AstraJEcuIdentity(
            family = family,
            confidence = confidence,
            vin = vin,
            systemIdentification = systemId,
            engineCode = engineCode,
            programmingDate = programmingDate,
            supplierIdentification = supplier,
        )
    }

    private suspend fun readTextLocalIdentifier(id: Int, maxBytes: Int): String? {
        val command = "1A" + id.toString(16).padStart(2, '0').uppercase()
        val response = runCatching { client.command(command, 7_000) }.getOrNull() ?: return null
        if (response.contains("NO DATA", ignoreCase = true)) return null
        if (ObdDecoder.negativeResponseCode(response, 0x1A) != null) return null

        val marker = listOf(0x5A, id)
        val data = AstraJGmlanCodec.bytesAfterMarker(response, marker, minimumBytes = 1)
            ?.take(maxBytes)
            ?: return null

        return data
            .takeWhile { it != 0 && it != 0xFF }
            .map { it.toChar() }
            .joinToString("")
            .trim()
            .takeIf { value -> value.isNotBlank() && value.all { it.code in 32..126 } }
    }

    private suspend fun readDateLocalIdentifier(id: Int): String? {
        val command = "1A" + id.toString(16).padStart(2, '0').uppercase()
        val response = runCatching { client.command(command, 7_000) }.getOrNull() ?: return null
        val data = AstraJGmlanCodec.bytesAfterMarker(response, listOf(0x5A, id), minimumBytes = 4)
            ?.take(4)
            ?: return null

        if (data.any { ((it shr 4) and 0x0F) > 9 || (it and 0x0F) > 9 }) return null
        val digits = data.joinToString("") {
            "${(it shr 4) and 0x0F}${it and 0x0F}"
        }
        return "${digits.substring(0, 4)}-${digits.substring(4, 6)}-${digits.substring(6, 8)}"
    }

    private suspend fun readMode09Text(command: String, pid: Int): String? {
        val response = runCatching { client.command(command, 8_000) }.getOrNull() ?: return null
        val marker = listOf(0x49, pid)
        val bytes = AstraJGmlanCodec.bytesAfterMarker(response, marker, minimumBytes = 2) ?: return null

        // Mode 09 commonly places a message/sequence byte before the ASCII payload.
        val data = bytes.dropWhile { it !in 32..126 }
        return data
            .takeWhile { it in 32..126 }
            .map { it.toChar() }
            .joinToString("")
            .trim()
            .takeIf(String::isNotBlank)
    }

    private suspend fun <T> withEngineHeader(block: suspend () -> T): T {
        runCatching { client.command("ATSP6", 5_000) }
        val header = client.command("ATSH7E0", 5_000)
        if (header.contains("?")) error("L’ELM327 refuse l’adressage moteur 7E0.")
        runCatching { client.command("ATCAF1", 5_000) }

        return try {
            block()
        } finally {
            runCatching { client.command("ATSH7DF", 4_000) }
            runCatching { client.command("ATSP0", 4_000) }
        }
    }
}

object AstraJGmlanCodec {
    fun bytesAfterMarker(
        response: String,
        marker: List<Int>,
        minimumBytes: Int = 1,
    ): List<Int>? {
        val streams = extractStreams(response)
        for (bytes in streams) {
            val index = indexOf(bytes, marker)
            if (index < 0) continue
            val data = bytes.drop(index + marker.size)
            if (data.size >= minimumBytes) return data
        }
        return null
    }

    fun hex(bytes: List<Int>?): String? = bytes
        ?.takeIf(List<Int>::isNotEmpty)
        ?.joinToString(" ") { it.toString(16).uppercase().padStart(2, '0') }

    private fun extractStreams(response: String): List<List<Int>> {
        val streams = mutableListOf<List<Int>>()
        val segmented = mutableListOf<Int>()
        var collectingSegments = false

        response.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@forEach

            if (Regex("^[0-9A-Fa-f]{3}$").matches(line)) {
                if (segmented.isNotEmpty()) {
                    streams += segmented.toList()
                    segmented.clear()
                }
                collectingSegments = true
                return@forEach
            }

            val segment = Regex("^[0-9A-Fa-f]:\\s*(.*)$").matchEntire(line)
            if (segment != null) {
                collectingSegments = true
                segmented += parseBytes(segment.groupValues[1])
                return@forEach
            }

            if (collectingSegments && segmented.isNotEmpty()) {
                streams += segmented.toList()
                segmented.clear()
                collectingSegments = false
            }

            val parsed = parseBytes(line)
            if (parsed.isNotEmpty()) streams += parsed
        }

        if (segmented.isNotEmpty()) streams += segmented.toList()

        // Also inspect a normalized stream for compact clone replies without line structure.
        val compact = response.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        val compactBytes = compact.chunked(2)
            .filter { it.length == 2 }
            .mapNotNull { it.toIntOrNull(16) }
        if (compactBytes.isNotEmpty()) streams += compactBytes

        return streams
    }

    private fun parseBytes(value: String): List<Int> = value
        .trim()
        .split(Regex("\\s+"))
        .flatMap { token ->
            val clean = token.trim().trim(':').uppercase()
            when {
                clean.length == 2 && clean.all { it in '0'..'9' || it in 'A'..'F' } ->
                    listOfNotNull(clean.toIntOrNull(16))
                clean.length > 2 && clean.length % 2 == 0 && clean.all { it in '0'..'9' || it in 'A'..'F' } ->
                    clean.chunked(2).mapNotNull { it.toIntOrNull(16) }
                else -> emptyList()
            }
        }

    private fun indexOf(haystack: List<Int>, needle: List<Int>): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        for (start in 0..haystack.size - needle.size) {
            if (needle.indices.all { offset -> haystack[start + offset] == needle[offset] }) return start
        }
        return -1
    }
}
