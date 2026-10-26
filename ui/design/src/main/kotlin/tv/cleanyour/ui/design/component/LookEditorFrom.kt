package tv.cleanyour.ui.design.component

import tv.cleanyour.core.flows.LookDraft
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.core.flows.PictureOption
import tv.cleanyour.core.flows.ThemeOption
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.spec.DockLayers

// The one mapping from the flows' Look choice (LookDrafts makes it for both machines) to what the
// Look editor shows, for the Look sheet after Setup and Setup's look step alike
// (RFC-LOOK-SETUP-2). A caller hands in only what its own state holds: the looks, the gallery, the
// person's photo kept for this TV and a photo just made here with their dims, and the apps of the
// dock and those waiting beyond it.

// The choice as the editor shows it. `dock` and `waitingApps`: the apps of the dock as the TV shows
// it and those waiting for more apps in a row, counted together; `waiting`: how many of them wait
// at the draft's apps in a row, counted here unless the caller's machine counted it already.
fun LookDraft.toEditor(
    themes: List<ThemeOption>,
    pictures: List<PictureOption>,
    ownPicture: BlobRef?,
    ownDim: Int,
    newPicture: BlobRef?,
    newDim: Int,
    dock: Int,
    waitingApps: Int,
    waiting: Int = DockLayers.waiting(dock + waitingApps, columns),
): LookEditorState = LookEditorState(
    looks = themes.map { LookEditorLook(it.id, it.name, it.description, it.wallpaperPath) },
    lookId = themeId, preview = preview?.toEditor(), columns = columns,
    apps = dock + waitingApps, waiting = waiting,
    picture = picture, ownPictureOnly = ownPictureOnly,
    pictures = pictures.map { LookEditorPicture(it.id, it.name, it.path) },
    minePath = ownPicture?.path, newPath = newPicture?.path, photoDim = if (newPicture != null) newDim else ownDim,
    minColumns = DockLayers.MIN_OFFERED_COLUMNS, maxColumns = DockLayers.MAX_OFFERED_COLUMNS,
)

// The preview as the flows read it back from the bytes, in the editor's plain values.
fun LookPreview.toEditor() = LookEditorPreview(
    backgroundArgb = backgroundArgb, picturePath = picturePath, dim = dim, sky = sky, columns = columns, rowsBelowDock = rowsBelowDock,
    dockRow = dockRow, dockTitle = dockTitle, fillDock = fillDock, cinematic = cinematic, festive = festive, accentArgb = accentArgb,
    greeting = greeting, motion = LookEditorMotion.valueOf(motion.name), dock = dock, minuteOfDay = minuteOfDay,
)
