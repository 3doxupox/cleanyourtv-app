package tv.cleanyour.core.flows

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.ArtifactRef
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.ModuleVersion
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.registry.IndexState
import tv.cleanyour.core.trust.VerifiedIndex
import tv.cleanyour.testing.fixtures.FakeArtifactFetcher
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// STORES-PLAN 1: the machines under the Play build's rules (no update check, no add-ons) offer
// neither, and run neither when asked; the full build's rules are the defaults and change nothing.
@OptIn(ExperimentalCoroutinesApi::class)
class StoreRulesTest {

    @Test fun theRulesOfTheTwoBuilds() {
        assertEquals(StoreRules(updateCheck = true, addOns = true), StoreRules.FULL)
        assertEquals(StoreRules(updateCheck = false, addOns = false), StoreRules.PLAY)
        assertEquals(StoreRules.FULL, StoreRules())
    }

    @Test fun thePlayBuildHasNoUpdateCheck() = runTest(timeout = 30.seconds) {
        val h = Harness()
        var asked = 0
        val deps = h.manageDeps(store = StoreRules.PLAY, updates = UpdateChecker { asked++; "9.9.9" })
        val m = AppSettingsMachine(deps, backgroundScope)
        settle()
        assertFalse(m.state.value.updateCheck)
        assertFalse(m.state.value.addOns)
        m.dispatch(AppSettingsIntent.CheckUpdates); settle()
        assertEquals(UpdateCheck.Idle, m.state.value.update)
        assertEquals(0, asked)

        val full = AppSettingsMachine(h.manageDeps(), backgroundScope)
        settle()
        assertTrue(full.state.value.updateCheck && full.state.value.addOns)
        full.dispatch(AppSettingsIntent.CheckUpdates); settle()
        assertEquals(UpdateCheck.Available("0.2.0"), full.state.value.update)
    }

    @Test fun thePlayBuildDoesNotOpenCardsAndModules() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val tv = h.knownTv()
        val play = ManageMachine(h.manageDeps(store = StoreRules.PLAY), backgroundScope, null)
        settle()
        assertFalse(play.state.value.addOns)
        play.effects.test {
            play.dispatch(ManageIntent.Open(ManageSection.MODULES)); settle()
            assertTrue(drain().isEmpty())
        }
        // Every other section still opens.
        play.effects.test {
            play.dispatch(ManageIntent.Open(ManageSection.SWITCHED_OFF)); settle()
            assertEquals(ManageEffect.Navigate("switchedoff/${tv.id.value}"), drain().single())
        }
        val full = ManageMachine(h.manageDeps(), backgroundScope, null)
        settle()
        assertTrue(full.state.value.addOns)
        full.effects.test {
            full.dispatch(ManageIntent.Open(ManageSection.MODULES)); settle()
            assertEquals(ManageEffect.Navigate("modules/${tv.id.value}"), drain().single())
        }
    }

    @Test fun thePlayBuildNeverRefreshesTheListOrDownloadsAnAddOn() = runTest(timeout = 30.seconds) {
        val h = Harness()
        val apk = "retro apk bytes".encodeToByteArray()
        val signer = sha256Of("signer".encodeToByteArray())
        val ref = ArtifactRef("https://github.com/x/retro.apk", apk.size.toLong(), sha256Of(apk), signer)
        val version = ModuleVersion(1, "0.1.0", "2026-10-03", ref, emptyList(), "", 21, emptyList(), emptyList())
        val retro = Module("retro", "Retro", "NES", "A card", PackageName("io.github.example.retro"), "card", Tier.COMMUNITY,
            "example", "https://github.com/example/retro", "MIT", null, null, listOf(version))
        h.registry.onRefresh = { IndexState.Ready(VerifiedIndex(1, Long.MAX_VALUE, listOf(retro), emptyList()), stale = false, fetchedAtMs = 0) }
        val tv = h.knownTv()
        val fetcher = FakeArtifactFetcher(h.blobs, mapOf(ref.url to apk))
        val m = ModulesMachine(h.manageDeps(fetcher, store = StoreRules.PLAY), backgroundScope, tv.id)
        settle()
        m.dispatch(ModulesIntent.Refresh); settle()
        m.dispatch(ModulesIntent.Add("retro")); settle()
        m.dispatch(ModulesIntent.Update("retro")); settle()
        assertEquals(0, h.registry.refreshes)
        assertTrue(fetcher.fetched.isEmpty())
        assertTrue(h.planner.requests.none { it is PlanRequest.AddModule })
        assertEquals(null, m.state.value.install)
    }
}
