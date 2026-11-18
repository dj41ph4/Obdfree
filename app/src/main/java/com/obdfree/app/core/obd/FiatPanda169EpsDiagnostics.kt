package com.obdfree.app.core.obd

/**
 * Fiat Panda II (169) / Delphi EPS - lecture experimentalement prise en charge.
 *
 * La direction communique sur une ligne K physique (OBD pin 9, adaptateur A1).
 * L'ELM327 utilise normalement la broche 7; le logiciel ne peut pas router les
 * broches. L'initialisation KWP a l'adresse 10 est un point de depart et DOIT
 * etre validee contre la reference ECU equipee sur le vehicule.
 *
 * Strictement lecture seule : pas de clear DTC, calibration, actionneur, ni codage.
 */
class FiatPanda169EpsDiagnostics(
    private val send: suspend (String, Long) -> String,
) {
    suspend fun readFaults(): FiatKwpReport {
        // Ne jamais faire de scan d'adresses au hasard et ne jamais faire de
        // broadcast sur le reseau de la voiture.
        required("ATPC")
        required("ATM0")
        required("ATTP4")
        required("ATIIA10")
        required("ATSH8010F1")
        required("ATH1")
        required("ATSTFF")

        try {
            // ATSI effectue un init 5 baud vers l'adresse configuree.
            val init = send("ATSI", 15_000)
            if (isError(init)) {
                error("L'ELM327 n'a pas pu initialiser le calculateur de direction : " + init.take(180))
            }
            val response = send("1800FF00", 15_000)
            if (isError(response)) error("Lecture EPS impossible : " + response.take(180))
            return FiatKwpDtcDecoder.decode(response)
        } finally {
            // Ferme la session KWP. Le moteur OBD-II generique est restaure
            // par le ViewModel lors de la sortie de ce diagnostic.
            runCatching { send("ATPC", 5_000) }
        }
    }

    private suspend fun required(command: String) {
        val reply = send(command, 6_000)
        if (isError(reply)) error("Commande ELM327 non supportee : $command ($reply)")
    }

    private fun isError(response: String): Boolean {
        val upper = response.uppercase()
        return response.isBlank() ||
            upper.contains("?") ||
            upper.contains("ERROR") ||
            upper.contains("NO DATA") ||
            upper.contains("UNABLE TO CONNECT") ||
            upper.contains("STOPPED")
    }
}
