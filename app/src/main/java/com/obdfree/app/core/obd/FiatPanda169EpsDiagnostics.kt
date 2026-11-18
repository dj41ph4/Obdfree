package com.obdfree.app.core.obd

/**
 * Fiat Panda II (169), Delphi EPS, ligne K sur la broche OBD 9 (A1 vert).
 *
 * IMPORTANT: 0x10 n'est qu'une adresse KWP candidate issue de conventions
 * d'adressage generales, pas une adresse Delphi EPS confirmee sur le vehicule.
 * La liaison devra etre validee contre un diagnostic Fiat de reference.
 *
 * Lecture seule. Pas de scan d'adresses, de routine, d'effacement ni de codage.
 */
class FiatPanda169EpsDiagnostics(
    private val send: suspend (String, Long) -> String,
) {
    suspend fun readFaults(): FiatKwpReport {
        // Referentiel de session volontairement fixe: pas de balayage KWP.
        // Meme la configuration doit etre sous finally pour liberer la ligne
        // si un clone refuse AT IIA ou AT SI.
        try {
            required("ATPC")
            required("ATSP4") // ISO 14230-4 KWP, 5-baud slow init
            required("ATIIA10") // PROVISOIRE, adresse non validee sur Delphi EPS
            required("ATSH8010F1")
            required("ATH1")
            required("ATSTFF")

            val init = send("ATSI", 15_000)
            if (isError(init)) {
                error("Initialisation K-Line EPS impossible : " + init.take(180))
            }

            val response = send("1800FF00", 15_000)
            if (isError(response)) {
                error("Lecture EPS impossible : " + response.take(180))
            }
            return FiatKwpDtcDecoder.decode(response)
        } finally {
            // Le ViewModel reinitialise ensuite l'ELM SANS sonder le moteur
            // puisque le cable A1 redirige physiquement la broche 7.
            runCatching { send("ATPC", 5_000) }
        }
    }

    private suspend fun required(command: String) {
        val response = send(command, 6_000)
        if (isError(response)) {
            error("Commande ELM327 non supportee : $command (${response.take(100)})")
        }
    }

    private fun isError(reply: String): Boolean {
        val upper = reply.uppercase()
        return reply.isBlank() || listOf(
            "?", "ERROR", "NO DATA", "UNABLE TO CONNECT",
            "STOPPED", "BUS INIT", "BUS ERROR", "RX ERROR",
        ).any(upper::contains)
    }
}
