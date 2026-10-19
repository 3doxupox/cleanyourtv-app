package tv.cleanyour.core.spec.upstream;

import java.util.List;

// What upstream Config.load computed, copied out of its package-private fields by the bridges.
// inputAction and moduleCards are null for the pinned Config.java, which predates them.
public final class UpstreamConfig {
    public final List<String> dockApps;
    public final List<String> hiddenApps;
    public final String liveTvPackage;
    public final String liveTvAction;
    public final String sourcePanelService;
    public final String sourcePanelAction;
    public final String inputAction;
    public final int columns;
    public final int rowsBelowDock;
    public final boolean shamsiDate;
    public final Boolean moduleCards;
    public final String vpnApp;
    public final boolean showHomeSettingsShortcut;
    public final int backgroundColor;
    public final boolean wallpaperShown;
    public final boolean overlayApplied;

    public UpstreamConfig(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                          String sourcePanelService, String sourcePanelAction, String inputAction, int columns,
                          int rowsBelowDock, boolean shamsiDate, Boolean moduleCards, String vpnApp,
                          boolean showHomeSettingsShortcut, int backgroundColor, boolean wallpaperShown,
                          boolean overlayApplied) {
        this.dockApps = dockApps;
        this.hiddenApps = hiddenApps;
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.columns = columns;
        this.rowsBelowDock = rowsBelowDock;
        this.shamsiDate = shamsiDate;
        this.moduleCards = moduleCards;
        this.vpnApp = vpnApp;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.backgroundColor = backgroundColor;
        this.wallpaperShown = wallpaperShown;
        this.overlayApplied = overlayApplied;
    }
}
