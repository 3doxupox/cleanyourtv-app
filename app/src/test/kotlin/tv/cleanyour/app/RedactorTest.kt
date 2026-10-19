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
}
