package tv.cleanyour.core.spec

import org.junit.Test
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.testing.fixtures.Fixtures
import kotlin.test.assertEquals

// 0.4.5 (upstream b06ab21): each list promoted or re-keyed in this release resolves from a TV report in
// one of its issues on our repo, and the sets its keys are meant to leave out (another board, Android
// version or product) do not. A null winner is the brand sure set or the generic plan.
class Promotions045Test {
    private val source = BundledProfileSource()

    private fun facts(model: String, device: String, brand: String, sdk: Int, fingerprint: String = "", manufacturer: String = brand): DeviceFacts =
        Fixtures.miTv().facts().copy(model = model, device = device, brand = brand, manufacturer = manufacturer, sdk = sdk, fingerprint = fingerprint)

    private fun winner(f: DeviceFacts): String? = (source.resolveNow(f) as? ProfileResolution.Exact)?.profile?.id

    private fun check(vararg reports: Pair<DeviceFacts, String?>) {
        for ((f, id) in reports) assertEquals(id, winner(f), "${f.brand} ${f.model} ${f.device} ${f.sdk} ${f.fingerprint}")
    }

    // Every BF1 report says device BRAVIA_BF1; BRAVIA_BF1_4K_A, which the header calls the device, is
    // the product (with BRAVIA_BF1_4K_M_A), so the product is the fingerprint prefix.
    @Test fun sonyBraviaBf1() = check(
        facts("BRAVIA BF1", "BRAVIA_BF1", "Sony", 31, "Sony/BRAVIA_BF1_4K_M_A/BRAVIA_BF1:12/STT2.230811.001/612080030:user/release-keys") to "sony-bravia-bf1", // #1088
        facts("BRAVIA BF1", "BRAVIA_BF1", "Sony", 31, "Sony/BRAVIA_BF1_4K_A/BRAVIA_BF1:12/STT2.230811.001/612090020:user/release-keys") to "sony-bravia-bf1", // #700
        facts("unknown", "BRAVIA_BF1_4K_A", "Sony", 31) to null,
        facts("BRAVIA BF1", "BRAVIA_BF1", "Sony", 34, "Sony/BRAVIA_BF1_4K_A/BRAVIA_BF1:14/x") to null,
    )

    // The README's second unit ran Android 11 (RTM7.230903.136) with the same list; the SKYWORTH and SHARP
    // badges of the umeda board report the maker skyworth, which the brand key matches.
    @Test fun skyworthSwtv22aeFhdOnAndroid11() = check(
        facts("SWTV-22AE-FHD", "umeda", "skyworth", 30, "skyworth/SW7_TG/umeda:11/RTM7.230903.136/11261145:user/release-keys") to "skyworth-swtv-22ae-fhd", // #608
        facts("SWTV-22AE-FHD", "umeda", "skyworth", 30, "skyworth/SW7_TG/umeda:11/RTM5.220609.088/8986834:user/release-keys") to "skyworth-swtv-22ae-fhd", // #955
        facts("SWTV-22NA-FHD", "umeda", "skyworth", 30, "skyworth/SW7_N/umeda:11/RTM5.220609.171/9213794:user/release-keys") to "skyworth-swtv-22ae-fhd", // #860
        facts("SHARP 2K Smart TV", "umeda", "SHARP", 30, "SHARP/SW7_SHARP_APEU/umeda:11/RTMA.250416.075/13648504:user/release-keys", manufacturer = "skyworth") to "skyworth-swtv-22ae-fhd", // #1033
        facts("SWTV-22AE-FHD", "umeda", "skyworth", 34, "skyworth/SW7_TG/umeda:14/UKNV.260211.001/14872057:user/release-keys") to "skyworth-swtv-22ae-fhd", // #526
        facts("SWTV-22AE-FHD", "umeda", "skyworth", 29) to null,
    )

    // The VU1 list was made on the US product BRAVIA_VU1_UC, the only one reported; the VU2 (another device) stays out.
    @Test fun sonyBraviaVu1() = check(
        facts("BRAVIA VU1", "BRAVIA_VU1", "Sony", 30, "Sony/BRAVIA_VU1_UC/BRAVIA_VU1:11/RTT7.210105.001/366.60436:user/release-keys") to "sony-bravia-vu1", // #1155
        facts("BRAVIA VU2", "BRAVIA_VU1_2K", "Sony", 30, "Sony/BRAVIA_VU2/BRAVIA_VU1_2K:11/RTT7.210923.001/146-60522:user/release-keys") to null, // #1005
    )
}
