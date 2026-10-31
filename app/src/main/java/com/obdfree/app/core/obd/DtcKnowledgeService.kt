package com.obdfree.app.core.obd

import android.content.Context
import com.obdfree.app.model.TroubleCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DtcKnowledgeService(
    private val context: Context,
) {
    suspend fun enrich(codes: List<TroubleCode>): List<TroubleCode> = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext emptyList()

        val wanted = codes
            .filter { LOCAL_FRENCH[it.code] == null && it.description == null }
            .map { it.code }
            .toSet()

        val remoteTitles = if (wanted.isEmpty()) {
            emptyMap()
        } else {
            runCatching { loadTitles(wanted) }.getOrDefault(emptyMap())
        }

        codes.map { code ->
            val french = LOCAL_FRENCH[code.code]
                ?: code.description
                ?: remoteTitles[code.code]?.let { FrenchDtcTranslator.translate(it, code) }
                ?: fallbackFrench()

            code.copy(description = french)
        }
    }

    private fun loadTitles(wanted: Set<String>): Map<String, String> {
        val json = readDatabase()
        val array = JSONArray(json)
        val result = LinkedHashMap<String, String>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val code = item.optString("code").uppercase()
            if (code !in wanted) continue

            val title = item.optJSONObject("title")?.optString("en").orEmpty()
            if (title.isNotBlank()) result[code] = title
            if (result.size == wanted.size) break
        }

        return result
    }

    private fun readDatabase(): String {
        val cache = File(context.cacheDir, CACHE_FILE)
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS

        if (fresh) return cache.readText()

        return try {
            val connection = URL(OBDEX_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            connection.inputStream.bufferedReader().use { it.readText() }
                .also { cache.writeText(it) }
        } catch (error: Throwable) {
            if (cache.exists()) cache.readText() else throw error
        }
    }

    private fun fallbackFrench(): String =
        "Description non disponible dans la base OBD-II générique. Ce code peut dépendre du constructeur."

    private companion object {
        const val OBDEX_URL = "https://foerbsnavi.github.io/OBDex/generic.min.json"
        const val CACHE_FILE = "obdex-generic.min.json"
        const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

        val LOCAL_FRENCH = mapOf(
            "P0100" to "Défaut du circuit du débitmètre d’air.",
            "P0101" to "Plage ou performances incorrectes du débitmètre d’air.",
            "P0102" to "Signal faible du débitmètre d’air.",
            "P0103" to "Signal élevé du débitmètre d’air.",
            "P0113" to "Signal élevé du capteur de température d’air d’admission.",
            "P0128" to "Température du liquide de refroidissement trop basse par rapport à la régulation attendue.",
            "P0130" to "Défaut du circuit de la sonde lambda, rangée 1 capteur 1.",
            "P0135" to "Défaut du circuit de chauffage de la sonde lambda, rangée 1 capteur 1.",
            "P0171" to "Mélange air/carburant trop pauvre, rangée 1.",
            "P0172" to "Mélange air/carburant trop riche, rangée 1.",
            "P0300" to "Ratés d’allumage aléatoires ou sur plusieurs cylindres détectés.",
            "P0301" to "Ratés d’allumage détectés sur le cylindre 1.",
            "P0302" to "Ratés d’allumage détectés sur le cylindre 2.",
            "P0303" to "Ratés d’allumage détectés sur le cylindre 3.",
            "P0304" to "Ratés d’allumage détectés sur le cylindre 4.",
            "P0325" to "Défaut du circuit du capteur de cliquetis 1.",
            "P0335" to "Défaut du circuit du capteur de position du vilebrequin A.",
            "P0380" to "Défaut du circuit de préchauffage.",
            "P0401" to "Débit de recirculation des gaz d’échappement (EGR) insuffisant.",
            "P0402" to "Débit de recirculation des gaz d’échappement (EGR) excessif.",
            "P0420" to "Efficacité du catalyseur inférieure au seuil, rangée 1.",
            "P0430" to "Efficacité du catalyseur inférieure au seuil, rangée 2.",
            "P0440" to "Défaut du système de récupération des vapeurs de carburant (EVAP).",
            "P0442" to "Petite fuite détectée dans le système EVAP.",
            "P0455" to "Fuite importante détectée dans le système EVAP.",
            "P0500" to "Défaut du capteur de vitesse du véhicule.",
            "P0562" to "Tension du système trop faible.",
            "P0563" to "Tension du système trop élevée.",
            "P0700" to "Le calculateur de transmission demande l’allumage du voyant défaut.",
        )
    }
}

private object FrenchDtcTranslator {
    private val replacements = listOf(
        "Catalyst System Efficiency Below Threshold" to "Efficacité du catalyseur inférieure au seuil",
        "Random/Multiple Cylinder Misfire Detected" to "Ratés d’allumage aléatoires ou sur plusieurs cylindres détectés",
        "System Too Lean" to "Mélange air/carburant trop pauvre",
        "System Too Rich" to "Mélange air/carburant trop riche",
        "Exhaust Gas Recirculation" to "Recirculation des gaz d’échappement (EGR)",
        "Evaporative Emission System" to "Système de récupération des vapeurs de carburant (EVAP)",
        "Mass or Volume Air Flow" to "Débit d’air massique ou volumique",
        "Intake Air Temperature" to "Température d’air d’admission",
        "Engine Coolant Temperature" to "Température du liquide de refroidissement moteur",
        "Crankshaft Position" to "Position du vilebrequin",
        "Camshaft Position" to "Position de l’arbre à cames",
        "O2 Sensor" to "Sonde lambda",
        "Oxygen Sensor" to "Sonde lambda",
        "Vehicle Speed Sensor" to "Capteur de vitesse du véhicule",
        "Fuel Pressure" to "Pression de carburant",
        "Fuel Level" to "Niveau de carburant",
        "Throttle Position" to "Position du papillon",
        "Control Module" to "Calculateur",
        "Circuit Range/Performance" to "Plage / performances du circuit",
        "Range/Performance" to "Plage / performances",
        "Circuit High" to "Signal élevé du circuit",
        "Circuit Low" to "Signal faible du circuit",
        "Sensor" to "Capteur",
        "Bank 1" to "rangée 1",
        "Bank 2" to "rangée 2",
        "Sensor 1" to "capteur 1",
        "Sensor 2" to "capteur 2",
        "Heater" to "chauffage",
        "Malfunction" to "dysfonctionnement",
        "Detected" to "détecté",
        "Insufficient" to "insuffisant",
        "Excessive" to "excessif",
        "Low" to "faible",
        "High" to "élevé",
        "Voltage" to "tension",
        "Pressure" to "pression",
        "Temperature" to "température",
        "Flow" to "débit",
        "Input" to "entrée",
        "Output" to "sortie",
        "Open" to "ouvert",
        "Short" to "court-circuit",
    )

    fun translate(english: String, code: TroubleCode): String {
        var translated = english
        var changes = 0

        replacements.forEach { (source, target) ->
            if (translated.contains(source, ignoreCase = true)) {
                translated = translated.replace(source, target, ignoreCase = true)
                changes++
            }
        }

        if (changes == 0) {
            return "Description OBD-II disponible en anglais uniquement pour ${code.code}."
        }

        return translated
            .replace("  ", " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .let { if (it.endsWith('.')) it else "$it." }
    }
}
