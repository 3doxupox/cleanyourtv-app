package com.gathin.tvhome.pinned;

import android.content.Context;
import android.content.res.Resources;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import tv.cleanyour.core.spec.upstream.UpstreamConfig;

// Runs upstream Config.load at the pinned submodule commit (1bbf0c9). Its source is copied from the
// third_party/cleanyourtv submodule into this package at build time (core/spec/build.gradle.kts).
public final class PinnedConfigBridge {
    private PinnedConfigBridge() {}

    public static UpstreamConfig load(File dir, Map<String, Object> values) {
        Config c = Config.load(new Context(dir, resources(values)));
        return new UpstreamConfig(c.dockApps, c.hiddenApps, c.liveTvPackage, c.liveTvAction, c.sourcePanelService,
                c.sourcePanelAction, c.inputAction, c.columns, c.rowsBelowDock, c.shamsiDate, c.moduleCards, c.vpnApp,
                c.showHomeSettingsShortcut, c.backgroundColor, c.wallpaper != null, c.overlayApplied);
    }

    static Resources resources(Map<String, Object> v) {
        Map<Integer, Object> m = new HashMap<>();
        m.put(R.array.dock_apps, v.get("dock_apps"));
        m.put(R.array.hidden_apps, v.get("hidden_apps"));
        m.put(R.string.live_tv_package, v.get("live_tv_package"));
        m.put(R.string.live_tv_action, v.get("live_tv_action"));
        m.put(R.string.source_panel_service, v.get("source_panel_service"));
        m.put(R.string.source_panel_action, v.get("source_panel_action"));
        m.put(R.string.input_action, v.get("input_action"));
        m.put(R.string.vpn_app, v.get("vpn_app"));
        m.put(R.integer.columns, v.get("columns"));
        m.put(R.integer.rows_below_dock, v.get("rows_below_dock"));
        m.put(R.bool.shamsi_date, v.get("shamsi_date"));
        m.put(R.bool.module_cards, v.get("module_cards"));
        m.put(R.bool.show_home_settings_shortcut, v.get("show_home_settings_shortcut"));
        m.values().removeIf(x -> x == null);
        return new Resources(m);
    }
}
