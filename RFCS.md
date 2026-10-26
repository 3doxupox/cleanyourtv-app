# RFCs

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

## RFC-LOOK2-ENGINE: the codec reads with launcher 2.7's own Config.java

Launcher 2.7 (3e34f40, versionCode 8) is now the pinned reader of `LauncherConfigCodec`: its
Config.java (and the files it needs) is vendored under `core/spec/src/test/java/com/gathin/tvhome/v27`
with small Android stubs, and the differential test runs it, with the 2.5 and 2.0 readers kept for
their keys. `CARRIED_27` grows to 16 keys (the dock band, glow, names, top bar, focus spring and
cinematic shelf keys), read by `LauncherKeys` with Config.java's rules; `LauncherConfigDoc` is
unchanged. `LookKeys.build` writes every look2 push from scratch, and 43,050 distinct docs agree
with 2.7 with no warning. The codec refuses a config.json with NaN or Infinity in a list
(`CRASH_27_NON_FINITE`): 2.7 crashes Home on every start when it keeps its copy of such a file.

## RFC-LOOK2-MODEL: a saved look is a legacy choice or a look2 choice

A saved look is either a legacy 0.4.0 choice (style null), encoded by the frozen `LegacyLook` path
byte for byte, or a look2 choice (style, picture, columns, movement, lift, names, details), encoded
by `LookKeys.build` from the rules resolved on this TV (`LookRules`). The first send of a migrated
draft writes the new form, with `columns=` always. home.txt gains style, movement, lift, names,
prevpic and d.* lines, and every line stays optional. One earlier look per TV is kept in
tvs/<id>/earlier.txt, plus earlier.jpg when that look showed the person's photo; Forget this TV
deletes both with the TV's folder.

## RFC-LOOK2-PREVIEW: the preview reads every look2 field from the bytes

`LookPreview.of` reads the codec's effective config plus `LauncherKeys.look`, so the phone draws
what the TV will read and never the choice itself. The sky is a port of Sky.java (`Sky`), the focus
colour comes from a small table of app colours (`AppColours`), and `TvPreview` follows launcher
2.7's own layout code (activity_main.xml, item_app.xml, AppAdapter tile sizing, DockDecoration).
`TvLook` and `LookPreview` only gained defaulted fields, so 0.4.0 callers draw as before.

## RFC-LOOK2-UI: one Look editor with three pages, in two places

`LookEditor` in `:ui:design` holds the Look, More options and Advanced pages, the pinned preview,
the leave question and the notes; `LookEditorFrom.kt` stays the one mapping from `LookDraft`. The
Look sheet gives it "Put it on the TV" with the way back under it, and Setup's look step gives it
Done (and no leave question, as closing keeps the choice). System Back, the predictive gesture,
swipe and the scrim all go through the modal sheet's `confirmValueChange` into `LookEditorNav`, so
no activity-compose dependency is added. No module, layer or dependency changes.
