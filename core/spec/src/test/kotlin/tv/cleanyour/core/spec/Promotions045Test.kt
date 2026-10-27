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
}
