package com.obdfree.app.model

data class DpfSnapshot(
    val profile: String? = null,
    val supported: Boolean = false,
    val sootPercent: Float? = null,
    val regenerationActive: Boolean? = null,
    val regenerationProgressPercent: Float? = null,
    val exhaustTemperatureC: Float? = null,
    val differentialPressureMbar: Float? = null,
    val distanceSinceRegenerationKm: Int? = null,
    val averageDistanceBetweenRegenerationsKm: Int? = null,
    val averageRegenerationDurationSeconds: Int? = null,
    val interruptedRegenerations: Int? = null,
    val coolantTemperatureC: Int? = null,
    val oilTemperatureC: Int? = null,
    val engineRpm: Int? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val hasData: Boolean
        get() = sootPercent != null ||
            regenerationActive != null ||
            regenerationProgressPercent != null ||
            exhaustTemperatureC != null ||
            differentialPressureMbar != null ||
            distanceSinceRegenerationKm != null ||
            averageDistanceBetweenRegenerationsKm != null ||
            averageRegenerationDurationSeconds != null ||
            interruptedRegenerations != null ||
            coolantTemperatureC != null ||
            oilTemperatureC != null ||
            engineRpm != null
}

data class DpfUiState(
    val autoDetectionEnabled: Boolean = false,
    val snapshot: DpfSnapshot? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val serviceRegenerationAvailable: Boolean = false,
    val serviceRegenerationReason: String =
        "Routine constructeur non validée pour cet ECU : lecture FAP disponible, commande forcée bloquée.",
)
