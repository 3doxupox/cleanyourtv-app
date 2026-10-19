package tv.cleanyour.e2e.scenarios

import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.cleanyour.core.engine.RunState
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.e2e.E2eScenario
import tv.cleanyour.testing.fixtures.Fixtures

// D15 on a real adbd: the Aurora theme from the pinned upstream themes goes through the config
// codec, the planner and FileOverlayTransport; the overlay on the TV then holds exactly the
// bytes the phone pushed and the launcher was restarted to read them.
class S06ThemePush : E2eScenario() {

    @After fun tidy() {
        oracle.uninstall(LAUNCHER_PKG)
    }

    @Test fun auroraLandsOnTheOverlayAndTheLauncherRestarts() = e2e(timeoutMs = 600_000) {
        val engine = engine()
        val apk = need(cfg.launcherApk, "launcher APK (CYT_E2E_LAUNCHER_APK)")
        assertTrue(oracle.install(apk).ok)
        oracle.shell("am start -W -n $LAUNCHER_PKG/.MainActivity")
        val pidBefore = oracle.shell("pidof $LAUNCHER_PKG").stdout.trim()

        val aurora = Fixtures.upstreamThemes().first { it.name == "aurora" }
        val decoded = LauncherConfigCodec.decode(aurora.configJson)
        assertTrue("aurora config rejected: $decoded", decoded is DecodeResult.Ok)
        val doc = (decoded as DecodeResult.Ok).doc
        val config = blobs.add(LauncherConfigCodec.encode(doc))
        val wallpaper = aurora.wallpaperJpg?.let { blobs.add(it) }

        val session = open()
        try {
            val base = baseline(session)
            val p = plan(PlanRequest.ApplyTheme(doc, config, wallpaper), base)
            val end = runToEnd(engine, p, session, pass(session.tvId))
            assertTrue("run ended $end", end is RunState.Finished && end.status == RunStatus.COMPLETED)

            assertEquals(config.sha256.hex, oracle.sha256("${RemoteDir.OVERLAY.path}/config.json"))
            wallpaper?.let { assertEquals(it.sha256.hex, oracle.sha256("${RemoteDir.OVERLAY.path}/wallpaper.jpg")) }
            delay(2_000)
            val pidAfter = oracle.shell("pidof $LAUNCHER_PKG").stdout.trim()
            assertTrue("launcher not running after the push", pidAfter.isNotEmpty())
            assertNotEquals("launcher was not restarted", pidBefore, pidAfter)
        } finally {
            session.close()
        }
    }
}
