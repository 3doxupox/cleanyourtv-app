package tv.cleanyour.core.flows

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.store.BlobStore

// The dock and the look as the one config.json the launcher reads: the theme's doc (or the
// launcher's defaults) with dock_apps the dock and its rows in order and rows_below_dock the rows
// under the dock that hold an app (DockLayers), encoded and stored in the BlobStore. Setup's plan
// (RFC-DOCK-1) and Manage's ApplyTheme both push what this makes, so one choice is the same bytes
// wherever it is sent from. The wallpaper is the theme's own, when it has one.
internal class Look(val doc: LauncherConfigDoc, val digest: Sha256, val config: BlobRef, val wallpaper: BlobRef?) {
    companion object {
        suspend fun encode(encoder: ConfigEncoder, blobs: BlobStore, theme: Theme?, dock: List<PackageName>): Look {
            val doc = DockLayers.applyTo(theme?.doc ?: LauncherConfigDoc(), dock)
            val bytes = encoder.encode(doc)
            return Look(doc, sha256Of(bytes), blobs.putBytes(bytes), theme?.wallpaper)
        }
    }
}
