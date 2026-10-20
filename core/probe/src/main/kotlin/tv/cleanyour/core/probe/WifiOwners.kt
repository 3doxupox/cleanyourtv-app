package tv.cleanyour.core.probe

import tv.cleanyour.core.session.TvSession

// Not part of the frozen Prober (section 5.5), like LaunchableApps: who saved the TV's Wi-Fi
// networks, as `dumpsys wifi` names them (cname=<package or shared uid>:<uid>). Owner decision D3,
// launcher v2.4.0's tools/disable.sh guard: a network saved during the first-run setup wizard
// belongs to the wizard, and Android deletes it the moment the wizard is switched off, taking the
// TV off Wi-Fi mid-run. One read-only call; only the cname words leave the TV, never a network name.
// An empty set means the TV could not say (dumpsys failed, Wi-Fi off, no network saved): a caller
// must then treat the wizard as the owner. Fails with AdbException like the Prober.
fun interface SavedWifiOwners {
    suspend fun savedWifiOwners(session: TvSession): Set<String>
}

object WifiOwnersParser {
    // A package name or shared user id, then an optional :uid. Anything else is dropped, so nothing
    // but an owner can come through.
    private val OWNER = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+(:[0-9]{1,7})?$")

    fun parse(lines: Sequence<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (raw in skipLong(lines)) {
            val t = raw.trim()
            if (!t.startsWith("cname=")) continue
            val owner = t.removePrefix("cname=").substringBefore(' ')
            if (OWNER.matches(owner)) out += owner
        }
        return out
    }

    // Whether `wizard` (or any setupwraith, the check upstream makes) owns a saved network, or the
    // owners are unknown. Either way the wizard must stay on.
    fun wizardMayOwnWifi(owners: Set<String>, wizard: String): Boolean =
        owners.isEmpty() || owners.any { o -> o.substringBefore(':') == wizard || "setupwraith" in o.lowercase() }
}
