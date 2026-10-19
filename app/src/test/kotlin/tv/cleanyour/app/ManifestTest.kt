package tv.cleanyour.app

import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// W8 definition of done: one exported component, the permissions exactly D16, no backup.
// Robolectric reads the merged manifest, so library additions are covered too.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ManifestTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Suppress("DEPRECATION")
    private val info = context.packageManager.getPackageInfo(context.packageName,
        PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS)

    @Test fun permissionsAreExactlyD16() {
        assertEquals(
            setOf(
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.CHANGE_WIFI_MULTICAST_STATE",
                "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE", "android.permission.POST_NOTIFICATIONS",
            ),
            info.requestedPermissions.orEmpty().toSet(),
        )
    }

    @Test fun exactlyOneExportedComponentAndItIsTheLauncherActivity() {
        val exported = info.activities.orEmpty().filter { it.exported }.map { it.name } +
            info.services.orEmpty().filter { it.exported }.map { it.name } +
            info.receivers.orEmpty().filter { it.exported }.map { it.name } +
            info.providers.orEmpty().filter { it.exported }.map { it.name }
        assertEquals(listOf("tv.cleanyour.app.MainActivity"), exported)
        val main = info.activities!!.single { it.name == "tv.cleanyour.app.MainActivity" }
        assertEquals("", main.taskAffinity.orEmpty())
    }

    @Test fun theForegroundServiceIsConnectedDeviceAndNotExported() {
        val s = info.services.orEmpty().single { it.name == "tv.cleanyour.android.work.OperationService" }
        assertFalse(s.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, s.foregroundServiceType)
    }

    @Test fun noBackupAndNoCleartext() {
        val app = info.applicationInfo!!
        assertEquals(0, app.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, app.flags and android.content.pm.ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }

    // F2: cloud backup and device transfer exclude every domain.
    @Test fun dataExtractionRulesExcludeEverything() {
        val root = java.io.File(System.getProperty("cleanyourtv.repoRoot") ?: error("no repo root"))
        val xml = java.io.File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = xml.substringAfter("<$section>").substringBefore("</$section>")
            for (d in listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")) {
                kotlin.test.assertTrue("<exclude domain=\"$d\" />" in body, "$section keeps $d")
            }
        }
    }
}
