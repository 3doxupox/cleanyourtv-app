package tv.cleanyour.core.planner

import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.KnownLaunchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 0.4.5 promotions (upstream b06ab21): the default plan of each promoted list switches off nothing the
// switch-off rule refuses (the denylist, the profile's never-disable list, the home screens and the setup
// wizards), so no line a person did not choose is dropped on the TV; and the packages each header says
// to keep are kept.
class Promotions045SafetyTest {
    private val shipped: Map<String, Profile> = BundledProfileSource.bundled.profiles.associateBy { it.id }
    private fun p(s: String) = PackageName(s)

    private fun defaultOff(id: String): Set<PackageName> =
        shipped.getValue(id).groups.filter { it.default == GroupDefault.DISABLE }.flatMap { g -> g.packages.map { it.name } }.toSet()

    private fun inAnyGroup(id: String, pkg: String) = shipped.getValue(id).groups.any { g -> g.packages.any { it.name == p(pkg) } }

    // Every default-off line passes SwitchOffRule on its own and is in no denylist.
    private fun defaultsPass(id: String): Profile {
        val pr = shipped.getValue(id)
        val refused = defaultOff(id).mapNotNull { pkg ->
            (SwitchOffRule.reason(pkg, null, pr, null, pr.stockLaunchers) ?: Denylist.staticReason(pkg.value))?.let { "$id ${pkg.value}: $it" }
        }
        assertEquals(emptyList(), refused)
        assertTrue(defaultOff(id).isNotEmpty(), id)
        return pr
    }

    private fun keeps(id: String, vararg pkgs: String) {
        val pr = shipped.getValue(id)
        for (s in pkgs) assertTrue(p(s) in pr.neverDisable && p(s) !in defaultOff(id), "$id keeps $s")
    }

    @Test fun sonyBraviaVu1() {
        val pr = defaultsPass("sony-bravia-vu1")
        assertEquals(KnownLaunchers.SETUPWRAITH, pr.setupWraith)
        keeps("sony-bravia-vu1", "com.android.tv", "com.realtek.dtv", "com.realtek.tv.atv", "com.sony.dtv.smartui", "com.google.android.tv.remote.service")
        for (s in listOf("com.google.android.tts", "com.google.android.katniss", "com.google.android.tvrecommendations")) assertTrue(p(s) !in defaultOff("sony-bravia-vu1"), s)
    }

    @Test fun philipsGoogleTvTa1() {
        defaultsPass("philips-google-tv-ta1")
        keeps("philips-google-tv-ta1", "org.droidtv.playtv", "org.droidtv.hdmiservice", "org.droidtv.GlobalKey", "com.google.android.apps.mediashell")
        // The owner's picks and the broadcast features are ask rows.
        for (s in listOf("com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "org.droidtv.hbbtvopapp", "com.mediatek.airplaydaemon", "org.droidtv.control4app"))
            assertTrue(p(s) !in defaultOff("philips-google-tv-ta1"), s)
    }

    @Test fun philipsGoogleTvTa7() {
        defaultsPass("philips-google-tv-ta7")
        keeps("philips-google-tv-ta7", "com.mediatek.wwtv.tvcenter", "com.mediatek.tvinput", "com.mediatek.autopair", "com.mediatek.tv.service")
        for (s in listOf("com.google.android.apps.mediashell", "com.mediatek.opapp", "com.netflix.ninja")) assertTrue(p(s) !in defaultOff("philips-google-tv-ta7"), s)
    }

    @Test fun tclG08EuAndroid12() {
        val pr = defaultsPass("tcl-g08-eu-android12")
        // The setup wizard is a kept line in the list; the header says to switch it off just before the
        // launcher goes in, which is the home screen switch, with the Wi-Fi guard.
        assertEquals(KnownLaunchers.SETUPWRAITH, pr.setupWraith)
        keeps("tcl-g08-eu-android12", "com.tcl.autopair", "com.tcl.suspension", "com.tcl.interactive", "com.tcl.ttvs", "com.tcl.tv", "com.tcl.globalkeyoverlay")
        for (s in listOf("com.mediatek.network", "com.mediatek.backgrounddetection", "com.mediatek.airplaydaemon", "com.tcl.dashboard", "com.tcl.browser", "com.tcl.hearaid", "com.google.android.tts"))
            assertTrue(p(s) !in defaultOff("tcl-g08-eu-android12"), s)
    }
}
