# RFCs (stream up037)

## RFC-UP037-1: the config differential test keeps launcher 2.5's reader

The app bundles launcher 2.7 (v2.7.0, 3e34f40). ConfigDifferentialTest compared the codec with the
upstream Config.java of the pinned submodule, copied in at build time. Launcher 2.7's Config.java
reads 67 new keys and needs Android stubs (android.os.Environment) and resources (R entries for
the new keys, WallpaperMood, ThemeFiles, WallpaperRotation.ORDER_SHUFFLE) the test harness does not
have. Smallest change: the 2.5 reader (v2.5.0, 9071ec8) is vendored in core/spec/src/test/java/
com/gathin/tvhome/pinned with its digests pinned, so the differential test still runs. The read code
of the keys the codec models is unchanged from 2.5 to 2.7 (checked for the bump), so the codec still
agrees with the bundled launcher on them. Open: port the 2.7 reader (stubs and resources), model the
new keys the app sets or shows, and carry the rest without the "the launcher ignores it" warning.

## RFC-LOOK-SETUP-1: Setup's look step has the Look sheet's choices

0.4.0 made apps in a row and the picture choices of the Look sheet, but Setup's look step still
offered the list of looks only, and a Setup run again cleared the saved apps in a row. Smallest
change: the Look sheet's content moves to `:ui:design` as `LookEditor` (with `PhotoPicker`, the
photo crop and `OwnPhoto`), used by both `:feature:launcher` and `:feature:setup`; a feature still
sees only `:core:flows`, `:core:model` and `:ui:design`. Frozen signatures grow, nothing is removed:
`SetupIntent` gets `SelectColumns`, `SelectPicture` and `SetOwnPicture`; `WizardDraft` gets
`columns` and `picture` (with defaults, so a draft written before loads as before); `SetupDeps` gets
`documents` (where the person's photo is kept) and `pictures`. One helper in `:core:flows`
(`LookDrafts`) makes the draft and its preview for both machines, and Setup encodes its look with
the same picture and apps in a row as the Look sheet, so the same choice is the same bytes.

## RFC-LOOK-SETUP-2: one mapping from the Look choice to the Look editor

After RFC-LOOK-SETUP-1 each feature mapped the flows' Look choice into `LookEditorState` itself,
and the preview mapping (`LookPreview` to `LookEditorPreview`) was the same code in both. Smallest
change: `:ui:design` gets one edge, `implementation(project(":core:flows"))`, allowed by rule 1
(layer 7 over layer 5), and `LookEditorFrom.kt` holds the one mapping (`LookDraft.toEditor`,
`LookPreview.toEditor`). Each feature hands in only its own state fields and keeps its own button
under the editor. No layer, allowlist or feature rule changes; a feature still sees only
`:core:flows`, `:core:model` and `:ui:design`, and the components stay plain values. Not chosen: a
new shared module (a new layer and a rule 3 change) or `:core:flows` (it cannot see `:ui:design`).
