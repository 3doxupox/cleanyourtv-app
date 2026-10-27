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

    // TA1 and TA5 run the same board (PH1M_WW_9972) and Google TV 14 build; the Android 12 TA1 stays out.
    @Test fun philipsGoogleTvTa1() = check(
        facts("Philips Google TV TA1", "PH1M_WW_9972", "Philips", 34, "Philips/PH1M_WW_9972/PH1M_WW_9972:14/UKN2.241117.001/3000518:user/release-keys", manufacturer = "TPV") to "philips-google-tv-ta1", // #1223
        facts("Philips Google TV TA5", "PH1M_WW_9972", "Philips", 34, "Philips/PH1M_WW_9972_20/PH1M_WW_9972:14/UKN2.241117.001/3000669:user/release-keys", manufacturer = "TPV") to "philips-google-tv-ta1", // #1095
        facts("Philips Google TV TA1", "PH1M_WW_9972", "Philips", 31, "Philips/PH1M_WW_9972/PH1M_WW_9972:12/STT2.230831.001/20826:user/release-keys", manufacturer = "TPV") to null, // #1231
    )

    @Test fun philipsGoogleTvTa7() = check(
        facts("Philips Google TV TA7", "PH3M_AL_T32", "Philips", 34, "Philips/PH3M_LT_T32/PH3M_AL_T32:14/UTT1.250308.001/101000072000:user/release-keys", manufacturer = "TPV") to "philips-google-tv-ta7", // #1213
    )

    // The G08 EU Android 12 build by its product G08_4K_GB; the US and NF products and Android 14 stay out. The
    // AU list reports the same fingerprint, so an AU set resolves here (its list switches off the same packages).
    @Test fun tclG08EuAndroid12() = check(
        facts("Smart TV Pro", "G08", "TCL", 31, "TCL/G08_4K_GB/G08:12/STT2.221228.001/AS34:user/release-keys") to "tcl-g08-eu-android12", // #1228
        facts("Smart TV Pro", "G08", "TCL", 31, "TCL/G08_4K_GB/G08:12/STT2.221228.001/AS18:user/release-keys") to "tcl-g08-eu-android12", // #953
        facts("Smart TV Pro", "G08", "TCL", 31, "TCL/G08_4K_US/G08:12/STT2.230203.001/AS95:user/release-keys") to null, // #574
        facts("Smart TV", "G08", "TCL", 31, "TCL/G08_4K_GB_NF/G08:12/STT2.221228.001/AS19:user/release-keys") to null, // #406
        facts("Smart TV Pro", "G08", "TCL", 34, "TCL/G08_4K_GB/G08:14/UTT2.250416.001/AU02:user/release-keys") to "tcl-c755-google-tv", // #1062
    )

    // tcl_eu is a broad TCL device name, so the Percee TV build is the fingerprint prefix.
    @Test fun tcl50dp660() = check(
        facts("Percee TV", "tcl_eu", "TCL", 26, "TCL/tcl_eu/tcl_eu:8.0.0/OTT1.180130.001/yangkx08252231:user/release-keys") to "tcl-50dp660", // #1240
        facts("Percee TV", "tcl_eu", "TCL", 26, "TCL/tcl_eu/tcl_eu:8.0.0/OTHER/x") to null,
    )

    // Device darcy only: the Pro 2019 also reports the model "SHIELD Android TV" and keeps its own list.
    @Test fun nvidiaShieldTv2017() = check(
        facts("SHIELD Android TV", "darcy", "NVIDIA", 30, "NVIDIA/darcy/darcy:11/RQ1A.210105.003/13961458_4387.0822:user/release-keys") to "nvidia-shield-tv-2017", // #938
        facts("SHIELD Android TV", "mdarcy", "NVIDIA", 30) to "nvidia-shield-tv-pro-2019",
        facts("SHIELD Android TV", "sif", "NVIDIA", 30) to "nvidia-shield-tv-2019",
    )

    @Test fun oneplusTvDosa() = check(
        facts("Oneplus_Dosa_IN", "Oneplus_Dosa_IN", "Oneplus", 30, "Oneplus/Oneplus_Dosa_IN/Oneplus_Dosa_IN:11/RTM5.220609.299/2312290405:user/release-keys") to "oneplus-tv-dosa", // #1162
    )

    // The Android 10 Cebu build only; the Android 11 Cebu sets wait for their own list (upstream PR #1605).
    @Test fun oneplusTvCebu() = check(
        facts("C00", "Cebu", "OnePlus", 29, "OnePlus/OnePlusTV/Cebu:10/QTG3.201207.001/2302040008:user/release-keys") to "oneplus-tv-cebu", // #1243
        facts("C00", "Cebu", "OnePlus", 30, "OnePlus/OnePlusTV/Cebu:11/RTM8.240401.135/2408080101:user/release-keys") to null, // #973
    )
}
