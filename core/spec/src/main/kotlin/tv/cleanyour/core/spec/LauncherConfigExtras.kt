package tv.cleanyour.core.spec

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Upstream main 1bbf0c9 added two keys to Config.java that section 5.7 predates: `input_action`
// (string, the action sent for an HDMI or AV row where nothing answers ACTION_VIEW) and
// `module_cards` (bool, whether the launcher looks for module cards). The frozen
// LauncherConfigDoc and EffectiveConfig have no field for them, so the codec carries them in
// `LauncherConfigDoc.unknown` under their real names, writes them at their Config.java position,
// and these helpers read them with Config.java semantics. RFC-W4-1 proposes promoting them to
// fields. The launcher pinned at 7205769 ignores both keys, which is harmless.
object LauncherConfigExtras {
    const val INPUT_ACTION = "input_action"
    const val MODULE_CARDS = "module_cards"

    data class Effective(val inputAction: String, val moduleCards: Boolean)

    // res/values/config.xml at 1bbf0c9.
    val builtInDefaults = Effective(inputAction = "", moduleCards = true)

    // The value the launcher uses, or null when the key is missing or of the wrong type.
    fun inputAction(doc: LauncherConfigDoc): String? =
        (doc.unknown[INPUT_ACTION] as? JsonPrimitive)?.takeIf { it.isString }?.content?.javaTrim()

    fun moduleCards(doc: LauncherConfigDoc): Boolean? =
        (doc.unknown[MODULE_CARDS] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    fun effective(doc: LauncherConfigDoc?, defaults: Effective = builtInDefaults): Effective =
        if (doc == null) defaults
        else Effective(inputAction(doc) ?: defaults.inputAction, moduleCards(doc) ?: defaults.moduleCards)

    fun withInputAction(doc: LauncherConfigDoc, value: String?): LauncherConfigDoc =
        doc.copy(unknown = doc.unknown.with(INPUT_ACTION, value?.let { JsonPrimitive(it) }))

    fun withModuleCards(doc: LauncherConfigDoc, value: Boolean?): LauncherConfigDoc =
        doc.copy(unknown = doc.unknown.with(MODULE_CARDS, value?.let { JsonPrimitive(it) }))

    private fun Map<String, JsonElement>.with(key: String, value: JsonElement?): Map<String, JsonElement> =
        LinkedHashMap(this).apply { if (value == null) remove(key) else put(key, value) }
}
