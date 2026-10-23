package tv.cleanyour.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// F13: logcat of a full flow contains no token, key, serial or full IP.
class RedactorTest {
    @Test fun addressesKeepOnlyTheLastOctet() = assertEquals("connect x.x.x.50:5555", Redactor.redact("connect 192.168.1.50:5555"))

    @Test fun keysTokensAndFingerprintsGo() {
        val out = Redactor.redact("key QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB cleanyourtv fp AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89")
        assertFalse("QUFB" in out)
        assertFalse("AB:CD" in out)
    }

    @Test fun serialsIdsAndCodesGo() {
        val out = Redactor.redact("[ro.serialno]: [12345ABCDE] tv 0123456789abcdef code 482913 ssid=HomeNet")
        assertFalse("12345ABCDE" in out)
        assertFalse("0123456789abcdef" in out)
        assertFalse("482913" in out)
        assertFalse("HomeNet" in out)
    }

    // 0.3.2 B3: the new log lines stay readable after the redactor (codes, models, codenames and the
    // failed op's package), while an address or a serial in the TV's own stderr still goes.
    @Test fun theReadableLogLinesSurviveTheRedactor() {
        val probe = "setup: probe done in 5464 ms, 269 packages, 27 launchable, profile brand set Amazon, tv Insignia NS-55F501NA26 (mantis) sdk 30"
        assertEquals(probe, Redactor.redact(probe))
        val home = "setup: home switch blocked WIZARD_STAYS_ON"
        assertEquals(home, Redactor.redact(home))
        val pause = Redactor.redact("setup: run paused STEP_FAILED at step 72: undo step failed: Enable com.amazon.tv.launcher: " +
            "nonzero_exit(1) Error: package com.amazon.tv.launcher is protected, serialno=G070VM1234567 from 192.168.1.20")
        for (kept in listOf("paused STEP_FAILED at step 72", "undo step failed: Enable com.amazon.tv.launcher", "nonzero_exit(1)")) {
            kotlin.test.assertTrue(kept in pause, "$kept lost in $pause")
        }
        assertFalse("G070VM1234567" in pause)
        assertFalse("192.168.1.20" in pause)
    }
}
