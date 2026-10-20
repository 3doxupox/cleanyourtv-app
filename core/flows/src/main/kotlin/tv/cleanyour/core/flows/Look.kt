package tv.cleanyour.core.flows

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.store.BlobStore

// The dock and the look as the one config.json the launcher reads: the theme's doc (or the
// launcher's defaults) with dock_apps the dock and its rows in order and rows_below_dock the rows
// under the dock that hold an app (DockLayers), encoded and stored in the BlobStore. Setup's plan
// (RFC-DOCK-1), Manage's ApplyTheme and the Modules add (RFC-CARDS-1) all push what this makes, so
// one choice is the same bytes wherever it is sent from. The wallpaper is the theme's own, when it
// has one. module_cards is always written: `cards` is this TV's cards choice (Cards).
internal class Look(val doc: LauncherConfigDoc, val digest: Sha256, val config: BlobRef, val wallpaper: BlobRef?) {
    companion object {
        suspend fun encode(encoder: ConfigEncoder, blobs: BlobStore, theme: Theme?, dock: List<PackageName>, cards: Boolean): Look {
            val doc = DockLayers.applyTo(theme?.doc ?: LauncherConfigDoc(), dock).copy(moduleCards = cards)
            val bytes = encoder.encode(doc)
            return Look(doc, sha256Of(bytes), blobs.putBytes(bytes), theme?.wallpaper)
        }
    }
}

// The launcher's module cards (module_cards). With them on, the launcher asks on the TV about every
// installed app that declares a card ("New card from ... Keep hidden / Show card"), so they stay
// off until the person asks for a card in Cards and modules; a first Setup never turns them on.
// The choice is per TV and kept in HomePrefs. A TV without a kept choice keeps what the overlay
// this phone last pushed says: the bytes decoded when the TV's copy is still ours, else on when
// this phone pushed one at all (an older version never wrote module_cards, and the launcher's
// default is on). A TV this phone never pushed a look to starts with them off.
internal object Cards {
    fun of(saved: Boolean?, overlay: ByteArray?, ours: Sha256?): Boolean {
        if (saved != null) return saved
        if (ours == null) return false
        if (overlay != null && sha256Of(overlay) == ours) {
            val doc = (LauncherConfigCodec.decode(overlay) as? DecodeResult.Ok)?.doc
            if (doc != null) return LauncherConfigCodec.effective(doc, LauncherConfigCodec.builtInDefaults).moduleCards
        }
        return true
    }

    // The cards choice for a TV in Manage, from what is kept and the TV's overlay (the last stored
    // snapshot's, unless a fresher one is given).
    suspend fun current(d: ManageDeps, tvId: TvId, choice: HomeChoice?, overlay: ByteArray? = null): Boolean =
        of(choice?.cards, overlay ?: d.snapshots.latest(tvId)?.launcherOverlay, d.tv(tvId)?.launcher?.ourOverlayDigest)
}

// D15: the digest of the config.json Manage just pushed, so the next connect can warn on drift.
internal suspend fun rememberPushedLook(d: ManageDeps, tvId: TvId, digest: Sha256) {
    val tv = d.tv(tvId) ?: return
    val pin = d.launcherPin.bundled()
    val l = tv.launcher ?: LauncherState(pin.versionCode, pin.signer, null, null)
    d.tvs.upsert(tv.copy(launcher = l.copy(overlayDigest = digest, ourOverlayDigest = digest)))
}
