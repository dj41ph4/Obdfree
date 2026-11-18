package com.obdfree.app.core.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiatPanda169EpsTest {
    @Test fun readsTwoFiatKwpFaultsWithRawStatus() {
        val result = FiatKwpDtcDecoder.decode("58 02 50 01 12 90 05 85")
        assertEquals(listOf("C1001", "B1005"), result.faults.map { it.code })
        assertEquals(listOf("12", "85"), result.faults.map { it.statusHex })
    }

    @Test fun emptyEcuResponseIsNotAnError() {
        assertTrue(FiatKwpDtcDecoder.decode("58 00").faults.isEmpty())
    }

    @Test fun readsThreeByteAddressedKwpFrameWithChecksum() {
        // 82 F1 10 = physically addressed KWP, length 2.
        assertTrue(FiatKwpDtcDecoder.decode("82 F1 10 58 00 DB").faults.isEmpty())
    }

    @Test fun rejectsTruncatedFaultsInsteadOfReportingCleanEcu() {
        var rejected = false
        try { FiatKwpDtcDecoder.decode("58 02 50 01 12") } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun rejectsNoDataInsteadOfReturningZeroFaults() {
        var rejected = false
        try { FiatKwpDtcDecoder.decode("NO DATA") } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun rejectsNegativeKwpResponse() {
        var rejected = false
        try { FiatKwpDtcDecoder.decode("7F 18 11") } catch (error: IllegalStateException) {
            rejected = error.message?.contains("7F 18 11") == true
        }
        assertTrue(rejected)
    }

    @Test fun onlyExpectedReadOnlyCommandsAreSent() = runBlocking {
        val sent = mutableListOf<String>()
        val probe = FiatPanda169EpsDiagnostics { command, _ ->
            sent += command
            when (command) {
                "1800FF00" -> "58 01 50 01 12"
                else -> "OK"
            }
        }
        val report = probe.readFaults()
        assertEquals("C1001", report.faults.single().code)
        assertEquals(
            listOf("ATPC", "ATM0", "ATTP4", "ATIIA10", "ATSH8010F1", "ATH1", "ATSTFF", "ATSI", "1800FF00", "ATPC"),
            sent,
        )
    }

    @Test fun closesSessionAfterFailedEcuResponse() = runBlocking {
        val sent = mutableListOf<String>()
        val probe = FiatPanda169EpsDiagnostics { command, _ ->
            sent += command
            if (command == "1800FF00") "NO DATA" else "OK"
        }
        var failed = false
        try { probe.readFaults() } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals("ATPC", sent.last())
    }
}
