package com.obdfree.app.core.obd

import com.obdfree.app.model.VoltageSample

/**
 * ELM327 AT RV reads the dongle input (normally vehicle OBD pin 16).
 * It is not an ECU voltage sensor and its precision depends on the adapter.
 */
object ElmVoltage {
    const val POLL_INTERVAL_MS = 1_000L
    const val MAX_SAMPLES = 90

    private val VALUE = Regex("""^([0-9]{1,2}(?:[.,][0-9]{1,3})?)\s*[Vv]?$""")
    private val ERRORS = listOf("?", "ERROR", "NO DATA", "STOPPED", "BUS ERROR", "BUFFER FULL", "RX ERROR")

    fun parse(response: String): Float? {
        val lines = response.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty() || lines.any { line -> ERRORS.any { error -> error in line.uppercase() } }) return null
        val meaningful = lines.filterNot { it.equals("OK", ignoreCase = true) }
        if (meaningful.size != 1) return null
        val digits = VALUE.matchEntire(meaningful.single())?.groupValues?.get(1) ?: return null
        val voltage = digits.replace(',', '.').toFloatOrNull() ?: return null
        return voltage.takeIf { it.isFinite() && it in 2f..32f }
    }

    fun append(history: List<VoltageSample>, sample: VoltageSample): List<VoltageSample> =
        (history + sample).takeLast(MAX_SAMPLES)
}
