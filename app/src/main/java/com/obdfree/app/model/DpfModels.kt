package com.obdfree.app.model

enum class AstraJEcuFamily {
    DENSO_DECE01,
    GM_E87,
    UNKNOWN,
}

data class AstraJEcuIdentity(
    val family: AstraJEcuFamily = AstraJEcuFamily.UNKNOWN,
    val confidence: Int = 0,
    val vin: String? = null,
    val systemIdentification: String? = null,
    val engineCode: String? = null,
    val programmingDate: String? = null,
    val supplierIdentification: String? = null,
) {
    val displayName: String
        get() = when (family) {
            AstraJEcuFamily.DENSO_DECE01 -> "Denso DECe01"
            AstraJEcuFamily.GM_E87 -> "GM / Delco E87"
            AstraJEcuFamily.UNKNOWN -> "ECU Astra J non identifié"
        }
}

data class DpfSnapshot(
    val profile: String? = null,
    val supported: Boolean = false,
    val ecuIdentity: AstraJEcuIdentity? = null,
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
    val regenerationInterlockRaw: String? = null,
    val deviceControlStatusRaw: String? = null,
    val deviceControlRejectRaw: String? = null,
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
            engineRpm != null ||
            ecuIdentity != null
}

data class DpfUiState(
    val autoDetectionEnabled: Boolean = false,
    val snapshot: DpfSnapshot? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val serviceRegenerationAvailable: Boolean = false,
    val serviceRegenerationReason: String =
        "Détecte d’abord l’ECU. OBDfree activera uniquement une commande constructeur identifiée.",
    val serviceProbeBusy: Boolean = false,
    val serviceProbeResult: String? = null,
    val idleAdjustmentRecognized: Boolean = false,
    val idleAdjustmentReason: String = "Détection ECU requise.",
)
