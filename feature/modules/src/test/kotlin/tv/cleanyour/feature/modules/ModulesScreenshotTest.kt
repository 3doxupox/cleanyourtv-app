package tv.cleanyour.feature.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.cleanyour.core.flows.CardAnswer
import tv.cleanyour.core.flows.IndexView
import tv.cleanyour.core.flows.InstallStep
import tv.cleanyour.core.flows.ModuleDetail
import tv.cleanyour.core.flows.ModuleRow
import tv.cleanyour.core.flows.ModulesState
import tv.cleanyour.core.model.ArtifactRef
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.ModuleVersion
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PermissionStatement
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.registry.FetchFailure
import tv.cleanyour.core.trust.IndexFailure

// Roborazzi screenshots of every screen state of :feature:modules (PRODUCT-SPEC 4: M3, M3a, M3b,
// M3c and the install narration). Record with recordRoborazziDebug, compare with
// verifyRoborazziDebug. Images land in src/test/screenshots. Sheets (the update re-consent, the
// "Who made each one" tiers sheet, the "Remove from TV" confirmation) are ModalBottomSheet windows
// that Robolectric does not capture, so they have no screenshot here.

@Composable
private fun ShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF000000), onBackground = Color(0xFFF5F5F7),
            surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF5F5F7),
            surfaceVariant = Color(0xFF2A2A2E), onSurfaceVariant = Color(0xFFA1A1A6),
            primary = Color(0xFFF5F5F7), onPrimary = Color(0xFF000000),
            tertiary = Color(0xFF8C9BFF), outline = Color(0x33FFFFFF),
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF000000))) { content() }
    }
}

private fun ComposeContentTestRule.shot(name: String, content: @Composable () -> Unit) {
    setContent { ShotTheme(content) }
    waitForIdle()
    onRoot().captureRoboImage("src/test/screenshots/$name.png")
}

// M3a is reached from the installed list by its "Add" button.
private fun ComposeContentTestRule.shotAdd(name: String, state: ModulesState) {
    setContent { ShotTheme { ModulesFlow(state, {}, {}, {}) } }
    waitForIdle()
    onNodeWithText("Add").performClick()
    waitForIdle()
    onRoot().captureRoboImage("src/test/screenshots/$name.png")
}

// Sample data: Retro v0.1.0 from the signed index (D6), key 6966 ed15 1f52 (D1).

private const val RETRO_KEY = "6966 ed15 1f52"
private val signer = Sha256("6966ed151f52" + "0".repeat(52))
private val zeroHash = Sha256("0".repeat(64))

private val retroVersion = ModuleVersion(
    versionCode = 1, versionName = "0.1.0", releasedAt = "3 Oct 2026",
    artifact = ArtifactRef("https://github.com/example/retro/releases/download/v0.1.0/retro.apk", 4_000_000, zeroHash, signer),
    permissions = listOf(
        PermissionStatement("android.permission.INTERNET", "Uses the internet to fetch game files from its own list"),
        PermissionStatement("android.permission.READ_EXTERNAL_STORAGE", "Reads USB storage, for your own game files"),
    ),
    networkStatement = "", minSdk = 28, abis = emptyList(), postInstall = emptyList(),
)

private val retro = Module(
    id = "retro", name = "Retro", oneLine = "NES games on the TV",
    description = "Adds a \"Retro\" card to the home screen that opens a library of free, legally shared NES games. Works with the TV remote. Needs about 40 MB for game files.",
    pkg = PackageName("io.github.udhaykumarbala.retro"), type = "card", tier = Tier.COMMUNITY, publisher = "udhaykumarbala",
    sourceUrl = "https://github.com/udhaykumarbala/retro", licence = "MIT", accent = "#E5484D", artwork = null, versions = listOf(retroVersion),
)
private val airplay = retro.copy(
    id = "airplay", name = "AirPlay receiver", oneLine = "Runs in the background", type = "service",
    description = "Lets phones and tablets send video to this TV.", pkg = PackageName("io.example.airplay"),
    versions = listOf(retroVersion.copy(permissions = listOf(PermissionStatement("android.permission.INTERNET", "Uses the local network to find senders")))),
)
private val pausedModule = retro.copy(tier = Tier.QUARANTINED, description = "The author asked us to pause it while a bug is fixed.")

private fun row(id: String, name: String, oneLine: String, size: Long?, tier: Tier = Tier.COMMUNITY, installed: Boolean = false,
    card: CardAnswer? = null, update: String? = null) =
    ModuleRow(id, name, oneLine, size, licence = "MIT", tier = tier, accent = null, installed = installed, card = card, updateTo = update)

private val retroAvailable = row("retro", "Retro", "NES games on the TV.", 4_000_000)
private val stremio = row("stremio", "Stremio shelf", "Your Stremio library as a row.", 6_500_000)
private val airplayAvailable = row("airplay", "AirPlay receiver", "Runs in the background.", 3_000_000)
private val allRows = listOf(retroAvailable, stremio, airplayAvailable)

private fun state(
    index: IndexView = IndexView.Ready(stale = false), installed: List<ModuleRow> = emptyList(), available: List<ModuleRow> = allRows,
    query: String = "", detail: ModuleDetail? = null, install: InstallStep? = null,
) = ModulesState(tvName = "Mi TV 4A", index = index, installed = installed, available = available, query = query, detail = detail, install = install)

private fun detail(module: Module = retro, installed: Boolean = false) = ModuleDetail(module, module.versions.first(), RETRO_KEY, installed)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ModulesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun fixedTimeZone() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private fun flow(name: String, state: ModulesState) = compose.shot(name) { ModulesFlow(state, {}, {}, {}) }

    // M3 Cards and modules
    @Test fun modules_installed_list() = flow("modules_installed_list", state(installed = listOf(
        row("retro", "Retro", "NES games on the TV.", 4_000_000, installed = true, card = CardAnswer.SHOWN),
        row("stremio", "Stremio shelf", "Your Stremio library as a row.", 6_500_000, installed = true, card = CardAnswer.HIDDEN, update = "0.2.0"),
        row("airplay", "AirPlay receiver", "Runs in the background.", 3_000_000, tier = Tier.QUARANTINED, installed = true),
    )))
    @Test fun modules_empty() = flow("modules_empty", state(installed = emptyList()))

    // M3a Add to Mi TV 4A
    @Test fun addmodule_list() = compose.shotAdd("addmodule_list", state())
    @Test fun addmodule_stale_index() = compose.shotAdd("addmodule_stale_index", state(index = IndexView.Ready(stale = true)))
    @Test fun addmodule_loading() = compose.shotAdd("addmodule_loading", state(index = IndexView.Loading, available = emptyList()))
    @Test fun addmodule_no_match() = compose.shotAdd("addmodule_no_match", state(query = "zzz"))
    @Test fun addmodule_failed_bad_signature() = compose.shotAdd("addmodule_failed_bad_signature",
        state(index = IndexView.Failed(IndexFailure.BAD_SIGNATURE, null, hasLast = false), available = emptyList()))
    @Test fun addmodule_failed_expired() = compose.shotAdd("addmodule_failed_expired",
        state(index = IndexView.Failed(IndexFailure.EXPIRED, null, hasLast = true)))
    @Test fun addmodule_failed_offline() = compose.shotAdd("addmodule_failed_offline",
        state(index = IndexView.Failed(null, FetchFailure.OFFLINE, hasLast = true)))
    @Test fun addmodule_failed_unknown_key() = compose.shotAdd("addmodule_failed_unknown_key",
        state(index = IndexView.Failed(IndexFailure.UNKNOWN_KEY, null, hasLast = false), available = emptyList()))

    // M3b Module: Retro
    @Test fun module_detail_retro() = flow("module_detail_retro", state(detail = detail()))
    @Test fun module_detail_retro_installed() = flow("module_detail_retro_installed", state(detail = detail(installed = true)))
    @Test fun module_detail_service() = flow("module_detail_service", state(detail = detail(airplay)))
    @Test fun module_detail_paused_installed() = flow("module_detail_paused_installed", state(detail = detail(pausedModule, installed = true)))

    // Install narration and M3c Look at the TV (card prompt)
    @Test fun install_downloading() = flow("install_downloading", state(detail = detail(), install = InstallStep.Downloading(1_500_000, 4_000_000)))
    @Test fun install_checking() = flow("install_checking", state(detail = detail(), install = InstallStep.Checking))
    @Test fun install_installing() = flow("install_installing", state(detail = detail(), install = InstallStep.Installing(60)))
    @Test fun cardprompt_asking() = flow("cardprompt_asking", state(detail = detail(),
        install = InstallStep.Asking("Retro", "io.github.udhaykumarbala.retro", RETRO_KEY)))
    @Test fun cardprompt_shown() = flow("cardprompt_shown", state(detail = detail(), install = InstallStep.Shown("Retro")))
    @Test fun cardprompt_hidden() = flow("cardprompt_hidden", state(detail = detail(), install = InstallStep.Hidden("Retro")))
    @Test fun install_failed_signer_mismatch() = flow("install_failed_signer_mismatch",
        state(detail = detail(), install = InstallStep.Failed("Retro", FetchFailure.SIGNER_MISMATCH)))
    @Test fun install_failed_hash_mismatch() = flow("install_failed_hash_mismatch",
        state(detail = detail(), install = InstallStep.Failed("Retro", FetchFailure.HASH_MISMATCH)))
    @Test fun install_failed_offline() = flow("install_failed_offline",
        state(detail = detail(), install = InstallStep.Failed("Retro", FetchFailure.OFFLINE)))
    @Test fun install_failed_generic() = flow("install_failed_generic", state(detail = detail(), install = InstallStep.Failed("Retro", null)))
}
