package com.gathin.tvhome.v27;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tv.cleanyour.core.spec.upstream.UpstreamConfig;

// Runs launcher 2.7's own Config.load (v2.7.0, 3e34f40, versionCode 8), frozen: a TV updated to
// 2.7 and not since (the app bundles 3.0, V30ConfigBridge). Config.java and the classes it calls are vendored in this package, launcher 2.7's
// files with only the package line changed (LookDifferentialTest pins their digests). Every resource id in
// R is answered from config.xml by its name. `privateDir` plays the app's private files dir, where
// 2.7 keeps a copy of the last config.json it read; `prefs` plays launcher_settings (the choices
// made on the TV), null for an empty one.
public final class V27ConfigBridge {
    private V27ConfigBridge() {}

    public static UpstreamConfig load(File dir, File privateDir, Map<String, Object> values, SharedPreferences prefs) {
        Config c = Config.load(new Context(dir, privateDir, resources(values), prefs));
        List<Integer> keycodes = new ArrayList<>();
        for (int k : c.settingsKeycodes) keycodes.add(k);
        Festival f = c.festival;
        UpstreamConfig.V24 v24 = new UpstreamConfig.V24(c.fillDock, c.animations, c.advancedEffects, c.greetingName,
                c.reducedMotion, c.dockTitle, c.dockRow, c.reorderButton, c.showStats, c.appLibrary, c.skyIntensity,
                keycodes, c.settingsKeyPanel, f.name, f.until == null ? "" : f.until.toString(), f.greeting, f.accent);
        List<Integer> inputKeycodes = new ArrayList<>();
        for (int k : c.inputKeycodes) inputKeycodes.add(k);
        List<String> races = new ArrayList<>();
        for (RaceWeekend.Race r : c.raceCalendar) {
            StringBuilder b = new StringBuilder().append(r.round).append('|').append(r.name).append('|').append(r.place).append('|');
            for (int i = 0; i < r.sessions.size(); i++) {
                if (i > 0) b.append(';');
                b.append(r.sessions.get(i).name).append('@').append(r.sessions.get(i).start);
            }
            races.add(b.toString());
        }
        UpstreamConfig.V25 v25 = new UpstreamConfig.V25(new HashMap<>(c.launchActions), new HashMap<>(c.appLabels),
                new HashMap<>(c.appBanners), inputKeycodes, new ArrayList<>(c.appKeys), c.cinematicHome,
                c.wallpaperRotationMinutes, c.wallpaperDim, races);
        UpstreamConfig.V27 v27 = new UpstreamConfig.V27(c.ambientGlow, c.glowStyle, c.appGlow, c.dockGlass, c.dockOpacity,
                c.dockEvenPadding, c.alwaysShowNames, c.iconOnlyButtons, c.iconOnlyInputs, c.topMenu, c.wifiStatusColor,
                c.focusOvershoot, c.cinematicDots, c.cinematicWhiteFocus, c.cinematicArt, c.cinematicScrim);
        return new UpstreamConfig(c.dockApps, c.hiddenApps, c.liveTvPackage, c.liveTvAction, c.sourcePanelService,
                c.sourcePanelAction, c.inputAction, c.columns, c.rowsBelowDock, c.shamsiDate, c.moduleCards, c.vpnApp,
                c.showHomeSettingsShortcut, c.backgroundColor, c.wallpaper != null, c.overlayApplied, v24, v25, v27);
    }

    // Each R id to the config.xml value of the same name; a name config.xml lacks stays out, so
    // reading it fails as a missing resource would.
    static Resources resources(Map<String, Object> v) {
        Map<Integer, Object> m = new HashMap<>();
        for (Class<?> type : R.class.getDeclaredClasses()) {
            for (Field field : type.getDeclaredFields()) {
                Object value = v.get(field.getName());
                if (value == null) continue;
                try {
                    m.put(field.getInt(null), value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return new Resources(m);
    }
}
