package tv.cleanyour.core.spec.upstream;

import java.util.List;

// What upstream Config.load computed, copied out of its package-private fields by the bridges.
// v24 holds the keys launcher v2.4.0 added and v25 the keys v2.5.0 added; both are null for the
// 1bbf0c9 Config.java (launcher 2.0). v27 holds the look keys launcher 2.7 reads that the codec
// carries (ConfigCodecImpl.CARRIED_27); null for 2.5 and 2.0. v30 holds the 25 keys launcher 3.0
// added (ConfigCodecImpl.CARRIED_30); null before 3.0.
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
    public final V24 v24;
    public final V25 v25;
    public final V27 v27;
    public final V30 v30;

    // The keys launcher 3.0 added as its Config keeps them: glass_style through Config.glassStyle
    // ("glass", "rim" or "halo"), single_row already and-ed with dock_row, clock_wallpaper_dim
    // clamped to 0 to 90, and the lists as Config.stringList made them (top_bar_apps is also added to
    // hiddenApps).
    public static final class V30 {
        public final List<String> inputSources, topBarApps;
        public final boolean lunarDate, openOnStartChoice, showLibraryPill, libraryOkOpens, uniformTiles,
                consumeVolumeKeys, powerOnHome, homeLongPressHome, powerOnHomeAlways, dockBand, wifiStatusWarnings,
                dateBelowClock, vpnPill, showGreeting, headerWhite, clockWallpaper, clockGlow, singleRow, dockBandAll;
        public final int lunarUtcOffset, clockWallpaperDim;
        public final String locale, glassStyle;

        public V30(List<String> inputSources, boolean lunarDate, int lunarUtcOffset, boolean openOnStartChoice,
                   boolean showLibraryPill, boolean libraryOkOpens, boolean uniformTiles, boolean consumeVolumeKeys,
                   boolean powerOnHome, boolean homeLongPressHome, boolean powerOnHomeAlways, boolean dockBand,
                   boolean wifiStatusWarnings, boolean dateBelowClock, boolean vpnPill, String locale,
                   List<String> topBarApps, boolean showGreeting, boolean headerWhite, boolean clockWallpaper,
                   int clockWallpaperDim, boolean clockGlow, boolean singleRow, boolean dockBandAll, String glassStyle) {
            this.inputSources = inputSources;
            this.lunarDate = lunarDate;
            this.lunarUtcOffset = lunarUtcOffset;
            this.openOnStartChoice = openOnStartChoice;
            this.showLibraryPill = showLibraryPill;
            this.libraryOkOpens = libraryOkOpens;
            this.uniformTiles = uniformTiles;
            this.consumeVolumeKeys = consumeVolumeKeys;
            this.powerOnHome = powerOnHome;
            this.homeLongPressHome = homeLongPressHome;
            this.powerOnHomeAlways = powerOnHomeAlways;
            this.dockBand = dockBand;
            this.wifiStatusWarnings = wifiStatusWarnings;
            this.dateBelowClock = dateBelowClock;
            this.vpnPill = vpnPill;
            this.locale = locale;
            this.topBarApps = topBarApps;
            this.showGreeting = showGreeting;
            this.headerWhite = headerWhite;
            this.clockWallpaper = clockWallpaper;
            this.clockWallpaperDim = clockWallpaperDim;
            this.clockGlow = clockGlow;
            this.singleRow = singleRow;
            this.dockBandAll = dockBandAll;
            this.glassStyle = glassStyle;
        }
    }

    // The carried look keys as launcher 2.7's Config keeps them (glow_style already through
    // Config.glowStyle, so "radial" or "horizon"; dock_opacity after the on-TV choice).
    public static final class V27 {
        public final boolean ambientGlow, appGlow, dockGlass, dockEvenPadding, alwaysShowNames, iconOnlyButtons,
                iconOnlyInputs, topMenu, wifiStatusColor, focusOvershoot, cinematicDots, cinematicWhiteFocus;
        public final String glowStyle;
        public final int dockOpacity, cinematicArt, cinematicScrim;

        public V27(boolean ambientGlow, String glowStyle, boolean appGlow, boolean dockGlass, int dockOpacity,
                   boolean dockEvenPadding, boolean alwaysShowNames, boolean iconOnlyButtons, boolean iconOnlyInputs,
                   boolean topMenu, boolean wifiStatusColor, boolean focusOvershoot, boolean cinematicDots,
                   boolean cinematicWhiteFocus, int cinematicArt, int cinematicScrim) {
            this.ambientGlow = ambientGlow;
            this.glowStyle = glowStyle;
            this.appGlow = appGlow;
            this.dockGlass = dockGlass;
            this.dockOpacity = dockOpacity;
            this.dockEvenPadding = dockEvenPadding;
            this.alwaysShowNames = alwaysShowNames;
            this.iconOnlyButtons = iconOnlyButtons;
            this.iconOnlyInputs = iconOnlyInputs;
            this.topMenu = topMenu;
            this.wifiStatusColor = wifiStatusColor;
            this.focusOvershoot = focusOvershoot;
            this.cinematicDots = cinematicDots;
            this.cinematicWhiteFocus = cinematicWhiteFocus;
            this.cinematicArt = cinematicArt;
            this.cinematicScrim = cinematicScrim;
        }
    }

    // The values v2.5.0 reads beyond v2.4.0's keys. Maps as Config.packageMap keeps them; each race
    // as "round|name|place|SESSION@start;SESSION@start", the sessions in RaceWeekend's order.
    public static final class V25 {
        public final java.util.Map<String, String> launchActions, appLabels, appBanners;
        public final List<Integer> inputKeyKeycodes;
        public final List<String> appKeys;
        public final boolean cinematicHome;
        public final int wallpaperRotationMinutes, wallpaperDim;
        public final List<String> raceCalendar;

        public V25(java.util.Map<String, String> launchActions, java.util.Map<String, String> appLabels,
                   java.util.Map<String, String> appBanners, List<Integer> inputKeyKeycodes, List<String> appKeys,
                   boolean cinematicHome, int wallpaperRotationMinutes, int wallpaperDim, List<String> raceCalendar) {
            this.launchActions = launchActions;
            this.appLabels = appLabels;
            this.appBanners = appBanners;
            this.inputKeyKeycodes = inputKeyKeycodes;
            this.appKeys = appKeys;
            this.cinematicHome = cinematicHome;
            this.wallpaperRotationMinutes = wallpaperRotationMinutes;
            this.wallpaperDim = wallpaperDim;
            this.raceCalendar = raceCalendar;
        }
    }

    // The values v2.4.0 reads beyond 1bbf0c9's keys. The festival is as Festival keeps it: the
    // lower-case name ("" for none), the last day as ISO text ("" for none), the cleaned greeting and
    // the accent (0 for the diya gold).
    public static final class V24 {
        public final boolean fillDock, animations, advancedEffects, reducedMotion, dockTitle, dockRow, reorderButton,
                showStats, appLibrary, settingsKeyPanel;
        public final String greetingName;
        public final int skyIntensity;
        public final List<Integer> settingsKeyKeycodes;
        public final String festival, festivalUntil, greeting;
        public final int accent;

        public V24(boolean fillDock, boolean animations, boolean advancedEffects, String greetingName, boolean reducedMotion,
                   boolean dockTitle, boolean dockRow, boolean reorderButton, boolean showStats, boolean appLibrary,
                   int skyIntensity, List<Integer> settingsKeyKeycodes, boolean settingsKeyPanel, String festival,
                   String festivalUntil, String greeting, int accent) {
            this.fillDock = fillDock;
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.greetingName = greetingName;
            this.reducedMotion = reducedMotion;
            this.dockTitle = dockTitle;
            this.dockRow = dockRow;
            this.reorderButton = reorderButton;
            this.showStats = showStats;
            this.appLibrary = appLibrary;
            this.skyIntensity = skyIntensity;
            this.settingsKeyKeycodes = settingsKeyKeycodes;
            this.settingsKeyPanel = settingsKeyPanel;
            this.festival = festival;
            this.festivalUntil = festivalUntil;
            this.greeting = greeting;
            this.accent = accent;
        }
    }

    public UpstreamConfig(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                          String sourcePanelService, String sourcePanelAction, String inputAction, int columns,
                          int rowsBelowDock, boolean shamsiDate, Boolean moduleCards, String vpnApp,
                          boolean showHomeSettingsShortcut, int backgroundColor, boolean wallpaperShown,
                          boolean overlayApplied, V24 v24) {
        this(dockApps, hiddenApps, liveTvPackage, liveTvAction, sourcePanelService, sourcePanelAction, inputAction, columns,
                rowsBelowDock, shamsiDate, moduleCards, vpnApp, showHomeSettingsShortcut, backgroundColor, wallpaperShown,
                overlayApplied, v24, null);
    }

    public UpstreamConfig(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                          String sourcePanelService, String sourcePanelAction, String inputAction, int columns,
                          int rowsBelowDock, boolean shamsiDate, Boolean moduleCards, String vpnApp,
                          boolean showHomeSettingsShortcut, int backgroundColor, boolean wallpaperShown,
                          boolean overlayApplied, V24 v24, V25 v25) {
        this(dockApps, hiddenApps, liveTvPackage, liveTvAction, sourcePanelService, sourcePanelAction, inputAction, columns,
                rowsBelowDock, shamsiDate, moduleCards, vpnApp, showHomeSettingsShortcut, backgroundColor, wallpaperShown,
                overlayApplied, v24, v25, null);
    }

    public UpstreamConfig(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                          String sourcePanelService, String sourcePanelAction, String inputAction, int columns,
                          int rowsBelowDock, boolean shamsiDate, Boolean moduleCards, String vpnApp,
                          boolean showHomeSettingsShortcut, int backgroundColor, boolean wallpaperShown,
                          boolean overlayApplied, V24 v24, V25 v25, V27 v27) {
        this(dockApps, hiddenApps, liveTvPackage, liveTvAction, sourcePanelService, sourcePanelAction, inputAction, columns,
                rowsBelowDock, shamsiDate, moduleCards, vpnApp, showHomeSettingsShortcut, backgroundColor, wallpaperShown,
                overlayApplied, v24, v25, v27, null);
    }

    public UpstreamConfig(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                          String sourcePanelService, String sourcePanelAction, String inputAction, int columns,
                          int rowsBelowDock, boolean shamsiDate, Boolean moduleCards, String vpnApp,
                          boolean showHomeSettingsShortcut, int backgroundColor, boolean wallpaperShown,
                          boolean overlayApplied, V24 v24, V25 v25, V27 v27, V30 v30) {
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
        this.v24 = v24;
        this.v25 = v25;
        this.v27 = v27;
        this.v30 = v30;
    }
}
