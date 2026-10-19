package tv.cleanyour.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import tv.cleanyour.android.platform.AndroidCredentialGate
import tv.cleanyour.core.flows.resolveStart

// The one exported component (SECURITY 4.2). It reads no extras and no data URI: every start
// opens the destination decided from disk.
class MainActivity : ComponentActivity() {
    private val graph get() = (application as CytApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph.credentialGate.attach(this)
        setContent {
            PendingAppTheme {
                var start by remember { mutableStateOf<Dest?>(null) }
                LaunchedEffect(Unit) {
                    graph.tvs.load()
                    start = startKey(resolveStart(graph.tvs.tvs.value, graph.seams.journal))
                }
                val s = start
                if (s == null) Box(Modifier.fillMaxSize().background(Color.Black))
                else AppRoot(graph, s, onExit = { finish() })
            }
        }
    }

    override fun onStart() { super.onStart(); graph.foreground.set(true) }
    override fun onStop() { graph.foreground.set(false); super.onStop() }

    override fun onDestroy() {
        graph.credentialGate.attach(null)
        super.onDestroy()
    }

    // API 28's keyguard confirm-credential screen (AndroidCredentialGate).
    @Deprecated("Activity result API is used everywhere else; the keyguard intent needs this on API 28")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AndroidCredentialGate.REQUEST_CONFIRM) graph.credentialGate.onConfirmCredentialResult(resultCode == RESULT_OK)
    }
}

// TODO(W7 merge): CytTheme(darkTheme = true). The dark tokens of DESIGN-SYSTEM 2.1 mapped onto M3.
@Composable
fun PendingAppTheme(content: @Composable () -> Unit) {
    val dark = darkColorScheme(
        background = Color(0xFF000000), onBackground = Color(0xFFF5F5F7),
        surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF5F5F7),
        surfaceVariant = Color(0xFF2A2A2E), onSurfaceVariant = Color(0xFFA1A1A6),
        primary = Color(0xFFF5F5F7), onPrimary = Color(0xFF000000),
        tertiary = Color(0xFF8C9BFF), outline = Color(0x33FFFFFF), error = Color(0xFFFF6961),
    )
    MaterialTheme(colorScheme = dark, content = content)
}
