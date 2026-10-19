package tv.cleanyour.adb.session

import kotlinx.coroutines.test.TestScope
import tv.cleanyour.adb.session.AdbTvSession
import tv.cleanyour.adb.wire.SoftwareRsaAdbSigner
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Endpoint
import tv.cleanyour.core.model.RawShell
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.ShellLine
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.session.OpenResult
import tv.cleanyour.core.store.Clock
import tv.cleanyour.testing.fakeadbd.FakeAdbd
import tv.cleanyour.testing.fakeadbd.FakeAdbdSocketFactory
import tv.cleanyour.testing.fakeadbd.FakeTv
import tv.cleanyour.testing.fakeadbd.Faults
import tv.cleanyour.testing.fakeadbd.Personas
import tv.cleanyour.testing.fakeadbd.sha256Hex
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.test.assertIs

object Keys {
    val signer: SoftwareRsaAdbSigner by lazy { SoftwareRsaAdbSigner(SoftwareRsaAdbSigner.generateKeyPair()) }
}

// A Clock over the test scheduler so AwaitingAllow.sinceMs and the boot deadline use virtual time.
class TestClock(private val scope: TestScope) : Clock {
    override fun nowMs(): Long = 1_760_000_000_000L + scope.testScheduler.currentTime
    override fun monotonicMs(): Long = scope.testScheduler.currentTime
}

const val HOST = "192.0.2.10"

class Rig(scope: TestScope, tv: FakeTv = Personas.miTv(), val faults: Faults = Faults(), trusted: Boolean = true) {
    val adbd = FakeAdbd(tv, faults, scope.backgroundScope).also { if (trusted) it.trust(Keys.signer) }
    val factory = FakeAdbdSocketFactory(mapOf(HOST to adbd))
    val clock = TestClock(scope)
    val manager = AdbSessionManager(factory, Keys.signer, null, clock, scope.backgroundScope)
    val endpoint = Endpoint.PlainTcp(HOST)
    val tv: FakeTv get() = adbd.tv

    suspend fun open(expected: TvId? = null, onAllow: suspend (String) -> Unit = {}): AdbTvSession =
        assertIs<OpenResult.Ok>(manager.open(endpoint, expected, onAllow)).session as AdbTvSession
}

@OptIn(RawShell::class)
fun line(text: String): ShellLine = ShellLine(text)

// A temp file with the given bytes and its BlobRef.
fun blob(bytes: ByteArray, size: Long = bytes.size.toLong(), sha: String = sha256Hex(bytes)): BlobRef {
    val f = createTempFile("cyt-blob").toFile()
    f.deleteOnExit()
    f.writeBytes(bytes)
    return BlobRef(Sha256(sha), size, f.absolutePath)
}

fun BlobRef.file(): File = File(path)
