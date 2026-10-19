// FROZEN INTERFACE (PRODUCT-SPEC section 5.7, after gate R0). Names and signatures are binding;
// a change needs a one-paragraph RFC in the repo and Fable sign-off. Copied verbatim by W1; every
// edit to the copied text is listed in DECISIONS-W1.md. Owner: W4.

package tv.cleanyour.core.spec

import tv.cleanyour.core.model.*
import kotlinx.serialization.json.JsonElement

data class LauncherConfigDoc(val dockApps: List<PackageName>? = null, val hiddenApps: List<PackageName>? = null,
    val liveTvPackage: String? = null, val liveTvAction: String? = null, val sourcePanelService: String? = null,
    val sourcePanelAction: String? = null, val columns: Int? = null, val rowsBelowDock: Int? = null, val shamsiDate: Boolean? = null,
    val vpnApp: String? = null, val showHomeSettingsShortcut: Boolean? = null, val backgroundColor: String? = null,
    val wallpaper: Boolean? = null, val unknown: Map<String, JsonElement> = emptyMap())
data class EffectiveConfig(val dockApps: List<String>, val hiddenApps: List<String>, val liveTvPackage: String, val liveTvAction: String,
    val sourcePanelService: String, val sourcePanelAction: String, val columns: Int, val rowsBelowDock: Int, val shamsiDate: Boolean,
    val vpnApp: String, val showHomeSettingsShortcut: Boolean, val backgroundColorArgb: Int, val wallpaper: Boolean)
sealed interface DecodeResult { data class Ok(val doc: LauncherConfigDoc, val warnings: List<String>) : DecodeResult; data class Rejected(val reason: String) : DecodeResult }
object LauncherConfigCodec {
    fun decode(bytes: ByteArray): DecodeResult = ConfigCodecImpl.decode(bytes)                       // Config.java semantics: 64 KB cap, BOM, per-key type and range
    fun encode(doc: LauncherConfigDoc): ByteArray = ConfigCodecImpl.encode(doc)                     // canonical key order, UTF-8, no BOM, no unknown keys unless carried
    fun effective(doc: LauncherConfigDoc?, defaults: EffectiveConfig): EffectiveConfig = ConfigCodecImpl.effective(doc, defaults)
    val builtInDefaults: EffectiveConfig get() = ConfigCodecImpl.BUILT_IN }
data class Theme(val id: String, val name: String, val description: String, val doc: LauncherConfigDoc, val wallpaper: BlobRef?)
interface ThemeSource { fun bundled(): List<Theme> }
