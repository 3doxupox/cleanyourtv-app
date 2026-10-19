package tv.cleanyour.core.spec

import com.gathin.tvhome.LatestConfigBridge
import com.gathin.tvhome.pinned.PinnedConfigBridge
import tv.cleanyour.core.spec.upstream.UpstreamConfig
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// Runs the real upstream Config.java (latest main 1bbf0c9 vendored in the test sources, and the
// pinned 7205769 copied from the submodule at build time) against a folder that plays the
// launcher's external files dir, with resource values read from the matching config.xml.
class UpstreamLauncher(private val latest: Boolean, private val dir: File) {
    val values: Map<String, Any> = parseConfigXml(
        if (latest) File(javaClass.getResource("/upstream/config-1bbf0c9.xml")!!.toURI())
        else File(Fixtures.upstreamDir, "launcher/app/src/main/res/values/config.xml"),
    )

    sealed interface Outcome {
        data class Loaded(val config: UpstreamConfig) : Outcome
        data class Crashed(val error: Throwable) : Outcome
    }

    fun load(configJson: ByteArray?, wallpaper: Boolean): Outcome {
        val cfg = File(dir, "config.json")
        val wp = File(dir, "wallpaper.jpg")
        if (configJson == null) cfg.delete() else cfg.writeBytes(configJson)
        if (wallpaper) wp.writeBytes(byteArrayOf(1, 2, 3)) else wp.delete()
        return try {
            Outcome.Loaded(if (latest) LatestConfigBridge.load(dir, values) else PinnedConfigBridge.load(dir, values))
        } catch (e: RuntimeException) {
            Outcome.Crashed(e)
        }
    }

    // The EffectiveConfig the launcher starts from with no overlay (Config.load's resource reads).
    fun defaults(): EffectiveConfig {
        @Suppress("UNCHECKED_CAST")
        fun list(k: String) = (values.getValue(k) as Array<String>).toList()
        fun s(k: String) = (values.getValue(k) as String).javaTrim()
        return EffectiveConfig(
            dockApps = list("dock_apps"), hiddenApps = list("hidden_apps"), liveTvPackage = s("live_tv_package"),
            liveTvAction = s("live_tv_action"), sourcePanelService = s("source_panel_service"), sourcePanelAction = s("source_panel_action"),
            columns = values.getValue("columns") as Int, rowsBelowDock = values.getValue("rows_below_dock") as Int,
            shamsiDate = values.getValue("shamsi_date") as Boolean, vpnApp = s("vpn_app"),
            showHomeSettingsShortcut = values.getValue("show_home_settings_shortcut") as Boolean,
            backgroundColorArgb = 0xFF000000.toInt(), wallpaper = true,
            // The pinned 7205769 has neither key and ignores both; its values are the built-in ones.
            inputAction = if (latest) s("input_action") else "",
            moduleCards = if (latest) values.getValue("module_cards") as Boolean else true,
        )
    }

    companion object {
        // aapt keeps string resources trimmed; these values are plain words, so text content is enough.
        fun parseConfigXml(f: File): Map<String, Any> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val out = LinkedHashMap<String, Any>()
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                out[name] = when (e.tagName) {
                    "string-array" -> {
                        val items = e.getElementsByTagName("item")
                        Array(items.length) { items.item(it).textContent.trim() }
                    }
                    "string" -> e.textContent.trim()
                    "integer" -> e.textContent.trim().toInt()
                    "bool" -> e.textContent.trim().toBooleanStrict()
                    else -> continue
                }
            }
            return out
        }
    }
}
