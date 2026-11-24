package lk.techworld.anantplay.sicast;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * v3.4.6 — OPTION A: THE MORNYE LIQUID-GLASS FLUTTER HOST.
 *
 * MainActivity is now a FlutterActivity hosting the ui/ module (the Mornye
 * v5.4.0 Liquid-Glass visual engine, vendored under LGPL-3.0-only — see
 * ui/lib/mornye_ui/LICENSE) rendering the 100% 1:1 Mornye UI over the SAME,
 * UNTOUCHED pure-Java backend: ReceiverService (mDNS + DLNA + cast server),
 * SelfUpdater, FeatureProbe, Troubleshooter, WatchHistory, StreamSniffer,
 * WebBrowserView (a native PlatformView), AppPrefs, SettingsBackup — zero
 * behavioral change below the bridge line.
 *
 * THE 8 CHANNELS (all handlers on the platform main thread):
 *   anantplay/cast          receiver arm/stop + live status + overlay/OEM
 *                           battery/autostart intents + official app check
 *   anantplay/player        Media3TextureBridge — native Media3 ExoPlayer
 *                           rendered into a Flutter Texture (SurfaceProducer)
 *   anantplay/subs          URL/title handoff into the subtitle workhorse
 *   anantplay/browser       the WebBrowserView PlatformView surface
 *   anantplay/settings      the full AppPrefs mirror + SettingsBackup
 *   anantplay/history       WatchHistory list/remove
 *   anantplay/updater       SelfUpdater surface + live progress events
 *   anantplay/troubleshoot  FeatureProbe 10-point run + report build
 *
 * The old v3.4.5 Java tabs/HUD are replaced by the Flutter UI. Everything
 * else — ReceiverService, PlayActivity's static notification contracts
 * (notifyCastReady / notifyNeedsMedia / statusSnapshot), BootReceiver,
 * CastTileService, UpdateActionReceiver — keeps firing unchanged.
 */
public class MainActivity extends FlutterActivity {

    private static final String CH_CAST = "anantplay/cast";
    private static final String CH_PLAYER = "anantplay/player";
    private static final String CH_SUBS = "anantplay/subs";
    private static final String CH_BROWSER = "anantplay/browser";
    private static final String CH_SETTINGS = "anantplay/settings";
    private static final String CH_HISTORY = "anantplay/history";
    private static final String CH_UPDATER = "anantplay/updater";
    private static final String CH_TROUBLE = "anantplay/troubleshoot";
    // v3.4.6.a — THE DART-SIDE CRASH REPORTER. Uncaught Dart errors (a null
    // deref in a widget build, a broken asset path, anything FlutterError /
    // PlatformDispatcher surfaces during startup) are forwarded INTO the same
    // persistent CrashLog the native layer uses — so a "won't start" report
    // is on disk even when logcat was never attached. Inverse of a crash loop:
    // the forwarder itself is fully guarded and CANNOT throw back into Dart.
    private static final String CH_CRASHLOG = "anantplay/crashlog";

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** The single native player bridge (one per engine). */
    private Media3TextureBridge playerBridge;
    /** Live receiver-status events → Flutter. */
    private EventChannel.EventSink castSink;
    /** Live updater progress/state events → Flutter. */
    private EventChannel.EventSink updateSink;
    /** Probe completion events → Flutter. */
    private EventChannel.EventSink troubleSink;

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        // v3.4.6.a — THE REAL v3.4.6 STARTUP ROOT CAUSE (live KVM emulator
        // logcat, Android 16 / API 36): FlutterActivity.onCreate(...) runs
        // configureFlutterEngine(...) INSIDE super.onCreate(...), and that
        // method reads `prefs` (the auto-update check:
        // prefs.getBoolean(AppPrefs.K_AUTO_UPDATE, true)). With the original
        // order — super.onCreate(b) FIRST, prefs assigned AFTER — `prefs` was
        // still null at that moment:
        //   FATAL EXCEPTION: main
        //   java.lang.RuntimeException: Unable to start activity ComponentInfo
        //     {…/.MainActivity}: java.lang.NullPointerException: Attempt to
        //     invoke interface method 'boolean android.content.SharedPreferences
        //     .getBoolean(java.lang.String, boolean)' on a null object reference
        // → the activity never opened → the build was dead on arrival.
        // getSharedPreferences(...) is legal here: attachBaseContext has
        // already run by onCreate, so the ContextWrapper is live.
        prefs = getSharedPreferences("sicast", MODE_PRIVATE);
        super.onCreate(b);
    }

    @Override
    public void configureFlutterEngine(FlutterEngine engine) {
        super.configureFlutterEngine(engine);

        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_CAST)
                .setMethodCallHandler(this::onCastCall);
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_PLAYER)
                .setMethodCallHandler(this::onPlayerCall);
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_SUBS)
                .setMethodCallHandler(this::onSubsCall);
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_BROWSER)
                .setMethodCallHandler(this::onBrowserCall);
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_SETTINGS)
                .setMethodCallHandler(this::onSettingsCall);
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_HISTORY)
                .setMethodCallHandler(this::onHistoryCall);
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_UPDATER)
                .setMethodCallHandler(this::onUpdaterCall);
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_TROUBLE)
                .setMethodCallHandler(this::onTroubleCall);
        // v3.4.6.a — the Dart-side crash reporter channel (see CH_CRASHLOG).
        new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CH_CRASHLOG)
                .setMethodCallHandler(this::onCrashlogCall);

        // ── EventChannels ──
        new EventChannel(engine.getDartExecutor().getBinaryMessenger(), CH_CAST + "/events")
                .setStreamHandler(new EventChannel.StreamHandler() {
                    @Override public void onListen(Object a, EventChannel.EventSink s) {
                        castSink = s;
                        pushCastStatus();
                    }
                    @Override public void onCancel(Object a) { castSink = null; }
                });
        new EventChannel(engine.getDartExecutor().getBinaryMessenger(), CH_UPDATER + "/events")
                .setStreamHandler(new EventChannel.StreamHandler() {
                    @Override public void onListen(Object a, EventChannel.EventSink s) {
                        updateSink = s;
                        pushUpdateState();
                    }
                    @Override public void onCancel(Object a) { updateSink = null; }
                });
        new EventChannel(engine.getDartExecutor().getBinaryMessenger(), CH_TROUBLE + "/events")
                .setStreamHandler(new EventChannel.StreamHandler() {
                    @Override public void onListen(Object a, EventChannel.EventSink s) { troubleSink = s; }
                    @Override public void onCancel(Object a) { troubleSink = null; }
                });

        // the native Media3 player bridge: hardware video inside a Flutter Texture
        playerBridge = new Media3TextureBridge(this, engine);

        // THE immediate on-launch auto-updater (the v3.2.0 contract, unchanged):
        // direct Tier-1/Tier-2 check with live progress into the glass banner.
        SelfUpdater.setUiListener(new SelfUpdater.UiListener() {
            @Override public void onUpdateAvailable(final String version) {
                main.post(() -> pushUpdateEvent("available", version == null ? "" : version));
            }
            @Override public void onDownloadProgress(final int percent) {
                main.post(() -> pushUpdateEvent("progress", String.valueOf(percent)));
            }
            @Override public void onUpdateState(final String bilingual) {
                main.post(() -> pushUpdateEvent("state", bilingual));
            }
        });
        if (SelfUpdater.hasPendingUpdate(this)) {
            pushUpdateEvent("pending", SelfUpdater.pendingVersion(this));
        } else if (prefs.getBoolean(AppPrefs.K_AUTO_UPDATE, true)) {
            SelfUpdater.checkOnLaunch(this);
        }
        if (SelfUpdater.ACTION_INSTALL_NOW.equals(getIntent().getAction())) {
            SelfUpdater.installPendingUpdate(this, true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        pushCastStatus();          // the auto-open overlay re-check reads this live
        // v3.4.6.b — the onboarding resume contract: a fresh status blob ALSO
        // carries the live permission/install bits (overlay, notifications,
        // battery, unknown-sources, official-app presence), so the wizard's
        // WidgetsBindingObserver re-query resolves from ONE native push.
        pushOnboardingStatus();
        // the official APK handed to PackageInstaller leaves a cache copy
        // behind when the user cancels the 1-tap sheet — sweep it on resume.
        if (AnantPlayInstaller.anantplayInstalled(this)) {
            AnantPlayInstaller.sweepCache(this);
        }
    }

    /** v3.4.6.b — the permission/install snapshot the wizard auto-advance reads. */
    private Map<String, Object> onboardingStatusMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "onboardingStatus");
        m.put("overlay", Settings.canDrawOverlays(this));
        m.put("notif", Miui.notifGranted(this));
        m.put("battery", Miui.batteryUnrestricted(this));
        m.put("installs", AnantPlayInstaller.canRequestPackageInstalls(this));
        m.put("official", AnantPlayInstaller.anantplayInstalled(this));
        return m;
    }

    private void pushOnboardingStatus() {
        EventChannel.EventSink s = castSink;
        if (s != null) s.success(onboardingStatusMap());
    }

    @Override
    protected void onDestroy() {
        if (playerBridge != null) playerBridge.dispose();
        SelfUpdater.setUiListener(null);
        super.onDestroy();
    }

    // ── anantplay/cast ────────────────────────────────────────────────────────

    private void onCastCall(MethodCall call, MethodChannel.Result result) {
        String m = call.method;
        try {
            if ("status".equals(m)) {
                result.success(castStatusMap());
            } else if ("arm".equals(m)) {
                ReceiverService.arm(this, "flutter-ui");
                main.postDelayed(this::pushCastStatus, 400);
                result.success(true);
            } else if ("stop".equals(m)) {
                stopService(new Intent(this, ReceiverService.class));
                main.postDelayed(this::pushCastStatus, 400);
                result.success(true);
            } else if ("overlayGranted".equals(m)) {
                result.success(Settings.canDrawOverlays(this));
            } else if ("openOverlay".equals(m)) {
                Miui.openOverlay(this);
                result.success(true);
            } else if ("notifGranted".equals(m)) {
                result.success(Miui.notifGranted(this));
            } else if ("requestBattery".equals(m)) {
                Miui.requestBattery(this);
                result.success(true);
            } else if ("batteryUnrestricted".equals(m)) {
                result.success(Miui.batteryUnrestricted(this));
            } else if ("autostartState".equals(m)) {
                result.success(Miui.autostartState(this).name());
            } else if ("confirmAutostart".equals(m)) {
                Miui.confirmAutostart(this, true);
                result.success(true);
            } else if ("openAutostart".equals(m)) {
                Miui.openAutostart(this);
                result.success(true);
            } else if ("openAppDetails".equals(m)) {
                Miui.openAppDetails(this);
                result.success(true);
            } else if ("officialInstalled".equals(m)) {
                result.success(officialInstalled());
            } else if ("officialVersion".equals(m)) {
                result.success(AnantPlayInstaller.anantplayVersion(this));
            } else if ("onboardingStatus".equals(m)) {
                // v3.4.6.b — the on-demand permission/install snapshot (the
                // same blob resume pushes; the wizard's initState re-query).
                result.success(onboardingStatusMap());
            } else if ("officialVersion".equals(m)) {
                result.success(AnantPlayInstaller.anantplayVersion(this));
            } else if ("openOfficial".equals(m)) {
                Miui.openAnantPlay(this);
                result.success(true);
            } else if ("testPlayer".equals(m)) {
                startActivity(new Intent(this, PlayActivity.class).putExtra("test", true));
                result.success(true);
            } else if ("finishOnboarding".equals(m)) {
                // v3.4.6.b — THE UNBREAKABLE FINISH PATH: a DEDICATED writer that
                // persists K_ONBOARDING_DONE directly through AppPrefs' own
                // setter — bypassing nothing (putBool would work too, but this
                // channel is the one surface that can NEVER be filtered by any
                // allowlist, backup or otherwise, and it pins the stage to 5).
                AppPrefs.setOnboardingDone(prefs, true);
                AppPrefs.setOnboardingStage(prefs, 5);
                result.success(true);
            } else if ("canRequestPackageInstalls".equals(m)) {
                result.success(AnantPlayInstaller.canRequestPackageInstalls(this));
            } else if ("openUnknownSources".equals(m)) {
                result.success(AnantPlayInstaller.openUnknownSources(this));
            } else if ("installOfficialAnantPlay".equals(m)) {
                // v3.4.6.b — Slide 2's in-app installer: resolve the official
                // release → stream with live percent events → PackageInstaller
                // 1-tap sheet. Progress arrives on this channel's EventChannel.
                AnantPlayInstaller.installOfficial(this, new AnantPlayInstaller.Listener() {
                    @Override public void onProgress(final int percent, final String version) {
                        main.post(() -> pushInstallEvent("installProgress", percent, version));
                    }
                    @Override public void onState(final String state) {
                        main.post(() -> pushInstallEvent("installState", 0, state));
                    }
                });
                result.success(true);
            } else if ("copyToClipboard".equals(m)) {
                // v3.4.6.b — Slide 6's 1-tap Copy Index URL (the Sources &
                // Index Helper: paste into AnantPlay's Settings → Sources).
                copyToClipboard(call.<String>argument("text"));
                result.success(true);
            } else if ("saveIndexUrl".equals(m)) {
                // v3.4.6.b — the editable Saved Index URL box (one URL, trimmed;
                // K_INDEX_URL is a normal backed-up pref so it survives reset).
                String url = call.argument("url");
                String clean = url == null ? "" : url.trim();
                prefs.edit().putString(AppPrefs.K_INDEX_URL, clean).apply();
                result.success(clean);
            } else if ("openBatteryOptimization".equals(m)) {
                // v3.4.6.b — the universal background-stability deep link (every
                // device), with the OEM-aware page Miui already resolves.
                Miui.requestBattery(this);
                result.success(true);
            } else if ("oemName".equals(m)) {
                result.success(String.valueOf(Build.MANUFACTURER));
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("cast", String.valueOf(t), null);
        }
    }

    private boolean officialInstalled() {
        try {
            getPackageManager().getPackageInfo(OnboardingWizard.ANANTPLAY_PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The receiver status blob the Home hero card renders. */
    private Map<String, Object> castStatusMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("up", ReceiverService.isUp());
        m.put("port", ReceiverService.port());
        m.put("discoverable", ReceiverService.castDiscoverable());
        m.put("mdns", ReceiverService.isAnnounced());
        return m;
    }

    private void pushCastStatus() {
        EventChannel.EventSink s = castSink;
        if (s != null) s.success(castStatusMap());
    }

    /** v3.4.6.b — Slide 2's live installer stream (progress/state on the cast events channel). */
    private void pushInstallEvent(String t, int v, String extra) {
        EventChannel.EventSink s = castSink;
        if (s == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", t);
        m.put("v", String.valueOf(v));
        m.put("x", extra == null ? "" : extra);
        s.success(m);
    }

    /** v3.4.6.b — the 1-tap Copy Index URL (system clipboard, guarded). */
    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && text != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AnantPlay index", text));
            }
        } catch (Throwable t) {
            Diag.w("cast", "clipboard refused: " + t);
        }
    }

    private void pushUpdateEvent(String t, String v) {
        EventChannel.EventSink s = updateSink;
        if (s == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", t);
        m.put("v", v);
        s.success(m);
    }

    private void pushUpdateState() {
        if (SelfUpdater.hasPendingUpdate(this)) {
            pushUpdateEvent("pending", SelfUpdater.pendingVersion(this));
        }
    }

    // ── anantplay/player ─────────────────────────────────────────────────────

    private void onPlayerCall(MethodCall call, MethodChannel.Result result) {
        if (playerBridge == null) {
            result.error("player", "bridge not ready", null);
            return;
        }
        playerBridge.handle(call, result);
    }

    // ── anantplay/subs ────────────────────────────────────────────────────────

    private void onSubsCall(MethodCall call, MethodChannel.Result result) {
        // The subtitle workhorse (search → download → translate → polish) stays
        // 100% inside PlayActivity's proven engine; the Flutter shell hands it
        // URLs/titles and renders results through the player screen.
        try {
            if ("openPlayerSearch".equals(call.method)) {
                Intent i = new Intent(this, PlayActivity.class);
                String url = call.argument("url");
                String title = call.argument("title");
                if (url != null && !url.isEmpty()) i.putExtra("url", url);
                if (title != null && !title.isEmpty()) i.putExtra("title", title);
                startActivity(i);
                result.success(true);
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("subs", String.valueOf(t), null);
        }
    }

    // ── anantplay/browser ────────────────────────────────────────────────────

    private void onBrowserCall(MethodCall call, MethodChannel.Result result) {
        try {
            if ("openInPlayer".equals(call.method)) {
                // a captured/sniffed stream from the browser tab → the player
                Intent i = new Intent(this, PlayActivity.class);
                String url = call.argument("url");
                String headers = call.argument("headers");
                String title = call.argument("title");
                if (url != null && !url.isEmpty()) i.putExtra("url", url);
                if (headers != null && !headers.isEmpty()) i.putExtra("headersJson", headers);
                if (title != null && !title.isEmpty()) i.putExtra("title", title);
                startActivity(i);
                result.success(true);
            } else if ("openWebTab".equals(call.method)) {
                // the v3.4.5 Web tab handoff: open the cinema browser on a URL
                Intent i = new Intent(this, PlayActivity.class);
                String url = call.argument("url");
                if (url != null && !url.isEmpty()) i.putExtra("web", url);
                startActivity(i);
                result.success(true);
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("browser", String.valueOf(t), null);
        }
    }

    // ── anantplay/settings ───────────────────────────────────────────────────

    private void onSettingsCall(MethodCall call, MethodChannel.Result result) {
        String m = call.method;
        try {
            if ("getAll".equals(m)) {
                result.success(gatherSettings());
            } else if ("putString".equals(m)) {
                prefs.edit().putString(call.<String>argument("key"),
                        orEmpty(call.argument("value"))).apply();
                result.success(true);
            } else if ("putBool".equals(m)) {
                prefs.edit().putBoolean(call.<String>argument("key"),
                        Boolean.TRUE.equals(call.argument("value"))).apply();
                result.success(true);
            } else if ("putInt".equals(m)) {
                Object v = call.argument("value");
                prefs.edit().putInt(call.<String>argument("key"),
                        v instanceof Number ? ((Number) v).intValue() : 0).apply();
                result.success(true);
            } else if ("getBool".equals(m)) {
                result.success(prefs.getBoolean(call.<String>argument("key"),
                        Boolean.TRUE.equals(call.argument("def"))));
            } else if ("getInt".equals(m)) {
                Object d = call.argument("def");
                result.success(prefs.getInt(call.<String>argument("key"),
                        d instanceof Number ? ((Number) d).intValue() : 0));
            } else if ("getString".equals(m)) {
                Object d = call.argument("def");
                result.success(prefs.getString(call.<String>argument("key"),
                        d == null ? null : String.valueOf(d)));
            } else if ("exportBackup".equals(m)) {
                result.success(SettingsBackup.export(AppInfo.VERSION_NAME,
                        System.currentTimeMillis(), gatherSettings()));
            } else if ("importPreview".equals(m)) {
                SettingsBackup.Import r = SettingsBackup.parseImport(orEmpty(call.argument("json")));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", r.ok);
                out.put("error", r.error);
                out.put("errorSi", r.errorSi);
                out.put("rejected", r.rejected);
                out.put("count", r.values.size());
                result.success(out);
            } else if ("resetAll".equals(m)) {
                prefs.edit().clear().apply();
                result.success(true);
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("settings", String.valueOf(t), null);
        }
    }

    private static String orEmpty(Object o) { return o == null ? "" : String.valueOf(o); }

    /** The exact BACKUP_KEYS surface v3.4.5 exported (typed readers). */
    private Map<String, Object> gatherSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        // v3.4.6.b — THE FINISH-BUTTON ROOT CAUSE FIX: the shell decides
        // Onboarding vs Home from this map, and onboardingDone/onboardingStage
        // were NEVER included (deliberately kept out of the BACKUP surface —
        // but gatherSettings() is the LIVE mirror, not the backup export!).
        // The wizard tapped Finish → putBool persisted the flag → refresh()
        // re-read this map → the key was still missing → ShellRoot kept
        // rendering OnboardingScreen forever (the "dead Finish" lockout).
        // Live values are read through AppPrefs' own typed readers so a
        // corrupt stored value is normalized exactly like every other key.
        m.put(AppPrefs.K_ONBOARDING_DONE, AppPrefs.onboardingDone(prefs));
        m.put(AppPrefs.K_ONBOARDING_STAGE, AppPrefs.onboardingStage(prefs));
        m.put(AppPrefs.K_THEME_MODE, AppPrefs.themeMode(prefs));
        m.put(AppPrefs.K_DYNAMIC_COLOR, AppPrefs.dynamicColor(prefs));
        m.put(AppPrefs.K_ACCENT, AppPrefs.accent(prefs));
        m.put(AppPrefs.K_UI_LANG, AppPrefs.uiLang(prefs));
        m.put(AppPrefs.K_DENSITY, AppPrefs.compact(prefs) ? AppPrefs.DENSITY_COMPACT : AppPrefs.DENSITY_COMFORTABLE);
        m.put(AppPrefs.K_SHOW_WEB_TAB, AppPrefs.showWebTab(prefs));
        m.put(AppPrefs.K_DEFAULT_LANG, prefs.getString(AppPrefs.K_DEFAULT_LANG, Langs.DEFAULT));
        String reg = prefs.getString(AppPrefs.K_REGISTER, null);
        if (reg != null) m.put(AppPrefs.K_REGISTER, reg);
        m.put(AppPrefs.K_AUTO_FETCH, prefs.getBoolean(AppPrefs.K_AUTO_FETCH, true));
        m.put(AppPrefs.K_CINEMA_POLISH, AppPrefs.cinemaPolish(prefs));
        m.put(AppPrefs.K_FONT_SP, AppPrefs.fontSp(prefs));
        m.put(AppPrefs.K_SUB_COLOR, AppPrefs.subColor(prefs));
        m.put(AppPrefs.K_SUB_BG, AppPrefs.subBg(prefs));
        m.put(AppPrefs.K_SUB_BG_OPACITY, AppPrefs.subBgOpacity(prefs));
        m.put(AppPrefs.K_SUB_BOLD, AppPrefs.subBold(prefs));
        m.put(AppPrefs.K_SUB_BOTTOM_PCT, AppPrefs.subBottomPct(prefs));
        m.put(AppPrefs.K_SYNC_STEP_MS, AppPrefs.syncStepMs(prefs));
        m.put(AppPrefs.K_SEEK_STEP_MS, AppPrefs.seekStepMs(prefs));
        m.put(AppPrefs.K_GESTURES, AppPrefs.gestures(prefs));
        m.put(AppPrefs.K_AUTO_PIP, AppPrefs.autoPip(prefs));
        m.put(AppPrefs.K_KEEP_SCREEN_ON, AppPrefs.keepScreenOn(prefs));
        m.put(AppPrefs.K_AUTO_RESUME, AppPrefs.autoResume(prefs));
        m.put(AppPrefs.K_HUD_HIDE_MS, AppPrefs.hudHideMs(prefs));
        m.put(AppPrefs.K_DOH_DIAG, AppPrefs.dohDiag(prefs));
        m.put(AppPrefs.K_RECEIVER_NAME, AppPrefs.receiverName(prefs));
        m.put(AppPrefs.K_DLNASTART, AppPrefs.dlnaEnabled(prefs));
        m.put(AppPrefs.K_MDNS, AppPrefs.mdnsEnabled(prefs));
        m.put(AppPrefs.K_WORKER_URL, prefs.getString(AppPrefs.K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL));
        m.put(AppPrefs.K_AUTO_UPDATE, prefs.getBoolean(AppPrefs.K_AUTO_UPDATE, true));
        m.put(AppPrefs.K_AUTO_OPEN_SNIFFED, prefs.getBoolean(AppPrefs.K_AUTO_OPEN_SNIFFED, true));
        m.put(AppPrefs.K_WEB_DESKTOP_UA, prefs.getBoolean(AppPrefs.K_WEB_DESKTOP_UA, false));
        m.put(AppPrefs.K_AD_BLOCK, prefs.getBoolean(AppPrefs.K_AD_BLOCK, true));
        m.put(AppPrefs.K_WEB_LINKS, prefs.getString(AppPrefs.K_WEB_LINKS, ""));
        m.put(AppPrefs.K_FREE_TRANSLATOR, prefs.getBoolean(AppPrefs.K_FREE_TRANSLATOR, true));
        m.put(AppPrefs.K_SURFACE_STYLE, prefs.getString(AppPrefs.K_SURFACE_STYLE, AppPrefs.SURFACE_GLASS));
        m.put(AppPrefs.K_BORDER_ALPHA, prefs.getInt(AppPrefs.K_BORDER_ALPHA, 30));
        m.put(AppPrefs.K_BOTTOM_BAR_STYLE, prefs.getString(AppPrefs.K_BOTTOM_BAR_STYLE, AppPrefs.BAR_FLOATING));
        m.put(AppPrefs.K_BOTTOM_BAR_LABELS, prefs.getBoolean(AppPrefs.K_BOTTOM_BAR_LABELS, true));
        m.put(AppPrefs.K_SHOW_TROUBLE_TAB, prefs.getBoolean(AppPrefs.K_SHOW_TROUBLE_TAB, true));
        m.put(AppPrefs.K_MOTION, prefs.getBoolean(AppPrefs.K_MOTION, true));
        m.put(AppPrefs.K_ACCENT_GLOW, prefs.getBoolean(AppPrefs.K_ACCENT_GLOW, true));
        m.put(AppPrefs.K_GLASS_CLARITY, AppPrefs.glassClarity(prefs));
        m.put(AppPrefs.K_GLASS_OUTLINES, AppPrefs.glassOutlines(prefs));
        m.put(AppPrefs.K_ICON_GLASS, AppPrefs.iconGlass(prefs));
        m.put(AppPrefs.K_UI_FONT, AppPrefs.uiFont(prefs));
        m.put(AppPrefs.K_SUB_COLOR_HEX, prefs.getString(AppPrefs.K_SUB_COLOR_HEX, ""));
        m.put(AppPrefs.K_SUB_OUTLINE, prefs.getInt(AppPrefs.K_SUB_OUTLINE, 0));
        m.put(AppPrefs.K_SUB_SHADOW, prefs.getBoolean(AppPrefs.K_SUB_SHADOW, false));
        m.put(AppPrefs.K_OMNIBAR_POS, prefs.getString(AppPrefs.K_OMNIBAR_POS, "bottom"));
        m.put(AppPrefs.K_SPEEDDIAL_COLS, prefs.getInt(AppPrefs.K_SPEEDDIAL_COLS, 3));
        m.put(AppPrefs.K_SEARCH_ENGINE, prefs.getString(AppPrefs.K_SEARCH_ENGINE, "ddg"));
        m.put(AppPrefs.K_WEB_CUSTOM_UA, prefs.getString(AppPrefs.K_WEB_CUSTOM_UA, ""));
        m.put(AppPrefs.K_READER_DARK, prefs.getBoolean(AppPrefs.K_READER_DARK, true));
        m.put(AppPrefs.K_FAVICONS, prefs.getBoolean(AppPrefs.K_FAVICONS, true));
        m.put(AppPrefs.K_SHOW_BLOCK_COUNT, prefs.getBoolean(AppPrefs.K_SHOW_BLOCK_COUNT, true));
        m.put(AppPrefs.K_ORIENTATION, prefs.getString(AppPrefs.K_ORIENTATION, "auto"));
        m.put(AppPrefs.K_EXTERNAL_PLAYER, prefs.getString(AppPrefs.K_EXTERNAL_PLAYER, "vlc"));
        m.put(AppPrefs.K_VOLUME_BOOST, prefs.getInt(AppPrefs.K_VOLUME_BOOST, 100));
        m.put(AppPrefs.K_SLEEP_TIMER_MS, prefs.getLong(AppPrefs.K_SLEEP_TIMER_MS, -1L));
        m.put(AppPrefs.K_SKIP_VERSION, prefs.getString(AppPrefs.K_SKIP_VERSION, ""));
        m.put(AppPrefs.K_WEB_TABS, prefs.getString(AppPrefs.K_WEB_TABS, ""));
        m.put(AppPrefs.K_MIDNIGHT_DIMMER, AppPrefs.midnightDimmer(prefs));
        m.put(AppPrefs.K_MIDNIGHT_DIMMER, AppPrefs.midnightDimmer(prefs));
        m.put(AppPrefs.K_MIDNIGHT_DIM_PCT, AppPrefs.midnightDimPct(prefs));
        // v3.4.6.b — the Sources & Index Helper's saved URL (backed-up key).
        m.put(AppPrefs.K_INDEX_URL, prefs.getString(AppPrefs.K_INDEX_URL, AppPrefs.DEFAULT_INDEX_URL));
        return m;
    }

    // ── anantplay/history ────────────────────────────────────────────────────

    private void onHistoryCall(MethodCall call, MethodChannel.Result result) {
        try {
            if ("list".equals(call.method)) {
                Object lim = call.argument("limit");
                int limit = lim instanceof Number ? ((Number) lim).intValue() : 20;
                List<WatchHistory.Item> items = WatchHistory.continueWatching(
                        WatchHistory.load(prefs), Math.max(1, Math.min(limit, WatchHistory.MAX)));
                result.success(WatchHistory.toJson(items));
            } else if ("remove".equals(call.method)) {
                WatchHistory.save(prefs, WatchHistory.remove(WatchHistory.load(prefs),
                        call.<String>argument("key")));
                result.success(true);
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("history", String.valueOf(t), null);
        }
    }

    // ── anantplay/updater ────────────────────────────────────────────────────

    private void onUpdaterCall(MethodCall call, MethodChannel.Result result) {
        try {
            if ("checkNow".equals(call.method)) {
                SelfUpdater.checkNow(this);
                result.success(true);
            } else if ("updateNow".equals(call.method)) {
                SelfUpdater.installPendingUpdate(this, true);
                result.success(true);
            } else if ("pending".equals(call.method)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pending", SelfUpdater.hasPendingUpdate(this));
                m.put("version", SelfUpdater.pendingVersion(this));
                result.success(m);
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("updater", String.valueOf(t), null);
        }
    }

    // ── anantplay/troubleshoot ───────────────────────────────────────────────

    // v3.4.6.a — the Dart-side crash reporter (see CH_CRASHLOG). The payload is
    // {kind: 'FlutterError'|'PlatformError', zone: str, error: str, stack: str}.
    // The report lands in the SAME CrashLog file the native recorder writes,
    // stamped with THIS build's versionCode so stale reports auto-clear on the
    // next update. Every branch is guarded: a crash inside the crash reporter
    // must never itself crash the app — the worst case is a Diag ring entry.
    private void onCrashlogCall(MethodCall call, MethodChannel.Result result) {
        try {
            if (!"report".equals(call.method)) { result.notImplemented(); return; }
            final Object kindArg = call.argument("kind");
            final Object zoneArg = call.argument("zone");
            final Object errorArg = call.argument("error");
            final Object stackArg = call.argument("stack");
            final String kind = kindArg instanceof String ? (String) kindArg : "Dart";
            final String zone = zoneArg instanceof String ? (String) zoneArg : "main";
            final String error = errorArg instanceof String ? (String) errorArg : "unknown";
            final String stack = stackArg instanceof String ? (String) stackArg : "";
            final String deviceInfo = "sdk " + Build.VERSION.SDK_INT + " (Dart " + kind + ", zone " + zone + ")";
            final String report = CrashLog.format(
                    System.currentTimeMillis(), AppInfo.VERSION_NAME, AppInfo.VERSION_CODE,
                    deviceInfo, "flutter-main",
                    new IllegalStateException(error + "\n" + stack));
            new Thread(() -> {
                try {
                    boolean ok = CrashLog.writeAtomically(getFilesDir(), report);
                    Diag.e("crashlog", "dart-side crash recorded (" + kind + ", persisted=" + ok + ")");
                } catch (Throwable t) {
                    Diag.e("crashlog", "dart-side crash persist refused: " + t);
                }
            }, "crashlog").start();
            result.success(true);
        } catch (Throwable t) {
            Diag.e("crashlog", "report handler refused: " + t);
            try { result.success(false); } catch (Throwable ignored) { }
        }
    }

    private void onTroubleCall(MethodCall call, MethodChannel.Result result) {
        try {
            if ("runProbe".equals(call.method)) {
                final android.content.Context ctx = this;
                final int port = ReceiverService.port();
                final String worker = prefs.getString(AppPrefs.K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL);
                new Thread(() -> {
                    try {
                        List<FeatureProbe.Outcome> outcomes = FeatureProbe.runAll(
                                port, worker, ctx.getFilesDir(), false, null);
                        FeatureProbe.remember(outcomes);
                        main.post(() -> {
                            EventChannel.EventSink s = troubleSink;
                            if (s != null) s.success(probeJson(outcomes));
                        });
                    } catch (final Throwable t) {
                        main.post(() -> {
                            EventChannel.EventSink s = troubleSink;
                            if (s != null) s.success(MiniJson.quote("probe failed: " + t));
                        });
                    }
                }, "probe").start();
                result.success(true);
            } else if ("lastResults".equals(call.method)) {
                result.success(probeJson(FeatureProbe.lastResults()));
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("trouble", String.valueOf(t), null);
        }
    }

    private String probeJson(List<FeatureProbe.Outcome> outcomes) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (FeatureProbe.Outcome o : outcomes) {
            if (!first) sb.append(',');
            first = false;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id);
            m.put("name", o.name);
            m.put("pass", o.pass);
            m.put("ms", o.ms);
            m.put("detail", o.detail);
            sb.append(MiniJson.write(m));
        }
        return sb.append(']').toString();
    }

    // ── shared URL intents (ACTION_VIEW / ACTION_SEND still route to the player) ──

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String url = firstHttpUrl(i);
        if (url != null) {
            startActivity(new Intent(this, PlayActivity.class).putExtra("url", url));
        }
    }

    private String firstHttpUrl(Intent i) {
        if (i == null) return null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            String s = String.valueOf(i.getData());
            if (s.startsWith("http")) return s;
        }
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence cs = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (cs != null) {
                java.util.regex.Matcher mm = HTTP_URL.matcher(String.valueOf(cs));
                if (mm.find()) return mm.group();
            }
        }
        return null;
    }

    private static final java.util.regex.Pattern HTTP_URL =
            java.util.regex.Pattern.compile("https?://\\S+");
}
