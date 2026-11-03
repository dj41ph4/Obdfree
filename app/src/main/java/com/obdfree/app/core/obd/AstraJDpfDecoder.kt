package com.obdfree.app.core.obd

object AstraJDpfDecoder {

    fun extractDidBytes(
        response: String,
        did: Int,
        minimumBytes: Int = 1,
    ): List<Int>? {
        val marker = "62" + did.toString(16).padStart(4, '0').uppercase()

        response.lineSequence().forEach { rawLine ->
            val compact = hexOnly(rawLine)
            var index = compact.indexOf(marker)
            while (index >= 0) {
                val data = compact.drop(index + marker.length)
                val bytes = data.chunked(2)
                    .filter { it.length == 2 }
                    .mapNotNull { it.toIntOrNull(16) }

                if (bytes.size >= minimumBytes) return bytes
                index = compact.indexOf(marker, startIndex = index + marker.length)
            }
        }

        return null
    }

    fun percentFromByte255(value: Int): Float =
        (value.coerceIn(0, 255) * 100f / 255f).coerceIn(0f, 100f)

    fun directOrScaledPercent(value: Int): Float =
        if (value in 0..100) value.toFloat() else percentFromByte255(value)

    fun regenerationProgress(value: Int): Float = percentFromByte255(value)

    fun signedByte(value: Int): Int =
        if (value and 0x80 != 0) value - 256 else value

    fun unsigned16(bytes: List<Int>): Int? =
        bytes.takeIf { it.size >= 2 }?.let { (it[0] shl 8) or it[1] }

    fun unsigned24(bytes: List<Int>): Int? =
        bytes.takeIf { it.size >= 3 }?.let { (it[0] shl 16) or (it[1] shl 8) or it[2] }

    fun temperatureFiveDegreeByte(value: Int): Float = value * 5f - 40f

    fun temperatureTenthDegreeWord(bytes: List<Int>): Float? =
        unsigned16(bytes)?.let { it * 0.1f - 40f }

    private fun hexOnly(value: String): String =
        value.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
}
