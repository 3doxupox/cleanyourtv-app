package tv.cleanyour.core.planner

import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.Denylist

// RF-SAFETY-250, owner decisions for 0.2.0: TVs where the home screen switch is skipped up front, so
// Setup is the clean-up alone (like RF-PROFILES-63 P1 and P2). SetupMachine asks before it plans and
// S3 says why; the planner refuses a switch asked for anyway. Not a frozen interface.
object HomeSwitchRisk {
    enum class Risk(val reason: String) {
        // The switch needs the setup wizard off, the Wi-Fi guard (D3) keeps it on, and with the wizard
        // on and the TV's own home screen off its RecoveryActivity takes Home and the system can
        // crash-loop (upstream #506, #422). OWNER: the wizard saved the Wi-Fi; UNKNOWN: the TV could not tell.
        WIZARD_WIFI("this TV's setup wizard saved its Wi-Fi, so the wizard has to stay on, and with it on the home screen switch can crash the TV"),
        WIZARD_WIFI_UNKNOWN("the TV could not say who saved its Wi-Fi, so its setup wizard has to stay on, and with it on the home screen switch can crash the TV"),
    }

    // The first risk that applies, or null when the switch may be planned. `profile` is the exact
    // profile (null on a brand sure set or the generic path); `wizardWifi` the D3 answer.
    @Suppress("UNUSED_PARAMETER")
    fun of(facts: DeviceFacts, profile: Profile?, snapshot: Snapshot, wizardWifi: WizardWifi): Risk? = wizardWifiRisk(profile, snapshot, wizardWifi)

    // With an exact profile, the wizard the switch would switch off (its setupWraith with the takeover
    // quirk) while D3 keeps it on: the TV said the wizard saved the Wi-Fi, or could not tell. Without
    // one the switch never touches the wizard, so only a TV that says outright that its wizard saved
    // the Wi-Fi is held back (decided at probe time; a failed read keeps 0.1.0's behaviour there).
    private fun wizardWifiRisk(profile: Profile?, snapshot: Snapshot, wizardWifi: WizardWifi): Risk? {
        if (wizardWifi == WizardWifi.NOT_OWNER) return null
        val wizard: PackageName = if (profile != null) {
            profile.setupWraith?.takeIf { Denylist.isWizardAllowed(it, profile) } ?: return null
        } else {
            if (wizardWifi != WizardWifi.OWNER) return null
            snapshot.packages.keys.firstOrNull { it.value in Denylist.setupWizards } ?: return null
        }
        val state = snapshot.packages[wizard] ?: return null
        if (state.enabled == EnabledState.DISABLED_USER || state.enabled == EnabledState.DISABLED) return null
        return if (wizardWifi == WizardWifi.OWNER) Risk.WIZARD_WIFI else Risk.WIZARD_WIFI_UNKNOWN
    }
}
