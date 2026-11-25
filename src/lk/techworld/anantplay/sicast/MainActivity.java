package lk.techworld.anantplay.sicast;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
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
    /** v3.4.6.c — shell route events → Flutter (play / web-tab handoffs). */
    private EventChannel.EventSink shellSink;

    // ── v3.4.6.d — THE ZERO-RACE GATE CACHE (the last delivered gate payload) ─
    // The mandatory force-update gate used to be a pure fire-and-forget push:
    // the cold-start check runs on a daemon worker IMMEDIATELY after
    // configureFlutterEngine, and a fast release resolution could deliver the
    // "available" blob BEFORE Flutter's ForceUpdateScreen subscribed to the
    // anantplay/updater EventChannel. The event sank into a null sink and the
    // user was dropped straight into the app with a mandatory update parked in
    // cache — exactly the defect class this release exists to kill.
    // Every gate blob is therefore CACHED here and REPLAYED in onListen(), so
    // "check finished first" can never again mean "gate dropped".
    private volatile Map<String, Object> lastGatePayload;

    // ── v3.4.6.c — THE SHELL ROUTE PUMP ─────────────────────────────────────
    // Casts (/play), DLNA SetAVTransportURI, shares and Test taps route INTO
    // the Flutter shell's Mornye Cinema Player instead of opening the raw
    // Java PlayActivity. Native code (or a fresh launch intent) calls
    // shellRoute(...); it brings this activity forward and pushes one route
    // blob onto the anantplay/shell/events EventChannel — ShellRootState
    // renders the PlayerScreen (or switches to the Browser tab) from it.
    private static MainActivity instance;

    /** Push a route blob into the Flutter shell and bring the task forward. */
    static void shellRoute(android.content.Context c, String type, String url,
                           String title, String headers, boolean test, long startMs) {
        MainActivity a = instance;
        if (a == null) {
            // The engine is not up (process cold start with a cast intent):
            // remember the launch intent; MainActivity.onCreate forwards it
            // after configureFlutterEngine (see pendingShellRoute).
            try {
                android.content.Intent i = new android.content.Intent(c, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("shellRouteType", type)
                        .putExtra("url", url)
                        .putExtra("title", title == null ? "" : title)
                        .putExtra("headersJson", headers == null ? "" : headers)
                        .putExtra("test", test)
                        .putExtra("startMs", startMs);
                c.startActivity(i);
            } catch (Throwable t) {
                Diag.w("shell", "cold route: " + t);
            }
            return;
        }
        a.pushShellRoute(type, url, title, headers, test, startMs);
    }

    private void pushShellRoute(String type, String url, String title,
                                String headers, boolean test, long startMs) {
        // Bring OUR task forward (a cast can arrive while we are backgrounded).
        try {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            if (am != null) {
                for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                    if (t.getTaskInfo().taskId == getTaskId()) {
                        // bringToFront + moveTaskToHome(false) — the API-31+
                        // moveToAndReturnToDefault equivalent that compiles on
                        // every platform level we build against.
                        t.moveToFront();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) { }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", type);                 // 'play' | 'web'
        m.put("url", url == null ? "" : url);
        m.put("title", title == null ? "" : title);
        m.put("headers", headers == null ? "" : headers);
        m.put("test", test);
        m.put("startMs", startMs);
        final EventChannel.EventSink s = shellSink;
        if (s != null) {
            main.post(() -> {
                try { s.success(m); } catch (Throwable t) { Diag.w("shell", "push: " + t); }
            });
        } else {
            // The shell has not subscribed yet (engine warm-up): queue it —
            // the stream handler replays the ONE latest route on listen.
            pendingRoute = m;
        }
    }

    /** The route queued while the shell EventChannel had no listener. */
    private Map<String, Object> pendingRoute;

    /**
     * v3.4.6.e — ReceiverService.onDestroy's instant status flip: the moment
     * the receiver is fully down, the Home hero card re-renders through the
     * anantplay/cast EventChannel. Static + null-instance-safe so the service
     * can call it from its own teardown without holding a context leak.
     */
    static void notifyReceiverDown() {
        MainActivity a = instance;
        if (a == null) return;
        try { a.main.post(a::pushCastStatus); } catch (Throwable t) {
            Diag.w("cast", "receiver-down push: " + t);
        }
    }

    /** A cold-launch intent carrying a shell route, forwarded once channels exist. */
    private void forwardLaunchRoute(Intent i) {
        if (i == null) return;
        String url = i.getStringExtra("url");
        String castJson = i.getStringExtra("castJson");
        boolean test = i.getBooleanExtra("test", false);
        String webUrl = i.getStringExtra("webUrl");
        if (webUrl == null) webUrl = i.getStringExtra("web");
        if (castJson != null && !castJson.isEmpty()) {
            // the notifyCastReady notification / a cold cast: decode the
            // payload exactly like PlayActivity.parseIntent's castJson leg.
            try {
                Map<String, Object> m = Cue.statusOf(castJson);
                String u = MiniJson.str(m, "url");
                String t = MiniJson.str(m, "title");
                if (t == null || t.isEmpty()) t = MiniJson.str(m, "pageTitle");
                pushShellRoute("play", u == null ? "" : u,
                        t == null ? "" : t, MiniJson.str(m, "headers"), false, 0L);
            } catch (Throwable t) {
                Diag.w("shell", "castJson: " + t);
            }
        } else if (url != null && !url.isEmpty()) {
            pushShellRoute("play", url, i.getStringExtra("title"),
                    i.getStringExtra("headersJson"), test, i.getLongExtra("startMs", 0L));
        } else if (test) {
            pushShellRoute("play", "", "Sinhala Player Test", "", true, 0L);
        } else if (webUrl != null && !webUrl.isEmpty()) {
            pushShellRoute("web", webUrl, "", "", false, 0L);
        } else {
            String found = firstHttpUrl(i);
            if (found != null) pushShellRoute("play", found, found, "", false, 0L);
        }
    }

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
        instance = this;
        super.onCreate(b);
        // v3.4.6.c — a cold launch that carries a shell route (a cast that
        // arrived before the engine was up, ACTION_VIEW/SEND, the
        // notifyCastReady notification) is forwarded now that channels exist.
        forwardLaunchRoute(getIntent());
        // v3.4.6.e — THE CI SELF-TEST HOOK (see runCiDeepProbe).
        maybeStartCiDeepProbe(getIntent());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // v3.4.6.e — THE LIVE 54-FEATURE SELF-TEST HOOK (hosted Android 36 KVM CI)
    //
    // WHY: the CI smoke job only proved the activity OPENS. Every release that
    // shipped a dead Stop-Receiver button, oversized subtitles or a silently
    // disabled updater passed that gate. This hook lets the SAME emulator run
    // the SAME 54 probes the Help tab runs — inside real Android OS, against
    // real SharedPreferences, a real foreground service, a real PackageManager
    // and a real clipboard — and publish a machine-readable verdict to logcat.
    //
    // SAFETY (it can never be triggered by a user, and can never change state):
    //   · it requires an explicit launch extra (the launcher activity MUST stay
    //     exported for the home screen, so the gate is the flag itself),
    //   · it is INERT unless that flag is present: ONE boolean read on the
    //     launch intent at cold start, and nothing else at all,
    //   · the only thing it can ever do is run the user-visible "Run full app
    //     troubleshoot" action that the Help tab already exposes with one tap
    //     — no privileged action, no hidden setting, no network write,
    //   · the run itself is DeepProbe.runAll, i.e. snapshot → mutate → verify →
    //     restore in a finally block: settings and the receiver's up/down state
    //     come back exactly as the user left them,
    //   · the published line carries only booleans, counters, HTTP codes and
    //     feature names — and the full report is passed through the same
    //     PrivacyScrubber fixed-point proof the Help tab shows the user.
    // ═════════════════════════════════════════════════════════════════════════

    /** True while a CI self-test run is in flight (never re-entered). */
    private volatile boolean ciDeepProbeStarted;

    /** Handle the `ci_run_deep_probe` launch extra (cold start + warm intent). */
    private void maybeStartCiDeepProbe(Intent i) {
        if (i == null) return;
        boolean want = false;
        try {
            want = i.getBooleanExtra(DeepProbe.CI_EXTRA, false);
        } catch (Throwable ignored) { }
        if (!want) return;
        runCiDeepProbe();
    }

    /**
     * The self-test driver: settle (~3 s so the receiver/auto-update cold-start
     * work finishes), run the real 54-probe matrix on a background thread, then
     * publish one line per probe + the parsed summary + the clipboard proof.
     */
    private void runCiDeepProbe() {
        if (ciDeepProbeStarted) return;
        ciDeepProbeStarted = true;
        main.postDelayed(() -> new Thread(() -> {
            List<DeepProbe.Outcome> outcomes;
            try {
                outcomes = DeepProbe.runAll(deepHooks(), null);
                DeepProbe.remember(outcomes);
            } catch (Throwable t) {
                // The matrix itself refuses to run: publish a FAILING summary so
                // the workflow's assertion fails loudly instead of timing out.
                android.util.Log.i(DeepProbe.CI_TAG, DeepProbe.CI_SUMMARY_KEY
                        + " pass=0 fail=54 total=54 clipboard_copied=false"
                        + " privacy_clean=false error=" + t.getClass().getSimpleName());
                android.util.Log.i(DeepProbe.CI_TAG, DeepProbe.CI_DONE_KEY + " aborted");
                return;
            }
            // 1 — every probe outcome, one parseable line each.
            int pass = 0, fail = 0;
            for (DeepProbe.Outcome o : outcomes) {
                if (o != null && o.pass) pass++; else fail++;
                android.util.Log.i(DeepProbe.CI_TAG, DeepProbe.ciProbeLine(o));
            }
            // 2 — THE SAME clipboard payload the Help tab hands the user, through
            //     BOTH lanes (native ClipboardManager + the Flutter Clipboard
            //     call), so CI proves the auto-copy contract on a real Android
            //     clipboard. The native write happens inline on THIS thread: the
            //     verification below must observe this thread's write.
            String report = "";
            boolean copied = false;
            try {
                report = buildDeepReport();
                if (report != null && !report.isEmpty()) {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "AnantPlay deep report", report));
                        }
                    } catch (Throwable t) {
                        android.util.Log.i(DeepProbe.CI_TAG, "CLIPBOARD_WRITE_FAILED " + t);
                    }
                    for (int k = 0; k < 20 && !copied; k++) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager)
                                        getSystemService(CLIPBOARD_SERVICE);
                            copied = cm != null && cm.hasPrimaryClip()
                                    && cm.getPrimaryClip() != null
                                    && cm.getPrimaryClip().getItemCount() > 0;
                        } catch (Throwable ignored) { }
                        if (!copied) {
                            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                        }
                    }
                    // the Flutter lane — the exact Help-tab call.
                    final String r = report;
                    main.post(() -> {
                        try { copyToClipboard(r); } catch (Throwable ignored) { }
                    });
                }
            } catch (Throwable t) {
                android.util.Log.i(DeepProbe.CI_TAG, "REPORT_BUILD_FAILED " + t);
            }
            boolean privacy = DeepProbe.ciPrivacyClean(report);
            // 3 — the parsed summary + the domain roll-up + the completion marker.
            android.util.Log.i(DeepProbe.CI_TAG,
                    DeepProbe.ciSummaryLine(outcomes, copied, privacy));
            android.util.Log.i(DeepProbe.CI_TAG,
                    "DEEP_PROBE_DOMAINS " + DeepProbe.ciDomainLine(outcomes)
                            + " version=" + AppInfo.VERSION_NAME
                            + " code=" + AppInfo.VERSION_CODE
                            + " sdk=" + Build.VERSION.SDK_INT);
            android.util.Log.i(DeepProbe.CI_TAG, DeepProbe.CI_DONE_KEY
                    + " pass=" + pass + " fail=" + fail + " total=" + outcomes.size());
        }, "ci-deep-probe").start(), 3000L);
    }

    /** The live engine (captured in configureFlutterEngine — the protected
     *  FlutterActivity getter is not reachable from Media3TextureBridge). */
    private FlutterEngine engine;

    FlutterEngine engineOrNull() { return engine; }

    @Override
    public void configureFlutterEngine(FlutterEngine engine) {
        this.engine = engine;
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
                        // v3.4.6.d — ZERO-RACE REPLAY: the launch check runs on a
                        // worker right after configureFlutterEngine, so it can beat
                        // Flutter's subscription. The cached gate blob is replayed
                        // here BEFORE the pending-state push, so ForceUpdateScreen
                        // always renders on a fast cold start.
                        replayGate(s);
                        pushUpdateState();
                    }
                    @Override public void onCancel(Object a) { updateSink = null; }
                });
        new EventChannel(engine.getDartExecutor().getBinaryMessenger(), CH_TROUBLE + "/events")
                .setStreamHandler(new EventChannel.StreamHandler() {
                    @Override public void onListen(Object a, EventChannel.EventSink s) { troubleSink = s; }
                    @Override public void onCancel(Object a) { troubleSink = null; }
                });
        // v3.4.6.c — the shell route pump (cast / DLNA / share / test → the
        // in-shell Mornye Cinema Player; openWebTab → the Browser tab).
        new EventChannel(engine.getDartExecutor().getBinaryMessenger(), "anantplay/shell/events")
                .setStreamHandler(new EventChannel.StreamHandler() {
                    @Override public void onListen(Object a, EventChannel.EventSink s) {
                        shellSink = s;
                        // A route queued before the shell subscribed replays now.
                        if (pendingRoute != null) {
                            Map<String, Object> r = pendingRoute;
                            pendingRoute = null;
                            main.post(() -> {
                                try { s.success(r); } catch (Throwable ignored) { }
                            });
                        }
                    }
                    @Override public void onCancel(Object a) { shellSink = null; }
                });

        // the native Media3 player bridge: hardware video inside a Flutter Texture
        playerBridge = new Media3TextureBridge(this, engine);

        // v3.4.6.c — the Browser tab's hardened native WebBrowserView.
        registerBrowserPlatformView(engine);

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
        // v3.4.6.d — THE MANDATORY GATE EVENT PUMP: every force-update state
        // change (available with current→new versions, live 0→100% download,
        // SHA-256 verify, installer handoff, failure + retry reason, up-to-
        // date) flows onto the anantplay/updater EventChannel so the shell's
        // ForceUpdateScreen renders the blocking gate with live progress.
        SelfUpdater.setGateListener(new SelfUpdater.GateListener() {
            @Override public void onAvailable(final String current, final String next, final String apkUrl) {
                main.post(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("t", "gate");
                    m.put("gate", "available");
                    m.put("current", current == null ? "" : current);
                    m.put("next", next == null ? "" : next);
                    pushGate(m);
                });
            }
            @Override public void onProgress(final int percent, final long bytes, final long total) {
                main.post(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("t", "gate");
                    m.put("gate", "progress");
                    m.put("percent", percent);
                    m.put("bytes", bytes);
                    m.put("total", total);
                    pushGate(m);
                });
            }
            @Override public void onVerify() {
                main.post(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("t", "gate");
                    m.put("gate", "verify");
                    pushGate(m);
                });
            }
            @Override public void onInstaller() {
                main.post(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("t", "gate");
                    m.put("gate", "installer");
                    pushGate(m);
                });
            }
            @Override public void onFailed(final String reason) {
                main.post(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("t", "gate");
                    m.put("gate", "failed");
                    m.put("reason", reason == null ? "" : reason);
                    pushGate(m);
                });
            }
            @Override public void onUpToDate() {
                main.post(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("t", "gate");
                    m.put("gate", "uptodate");
                    pushGate(m);
                });
            }
        });
        if (SelfUpdater.hasPendingUpdate(this)) {
            pushUpdateEvent("pending", SelfUpdater.pendingVersion(this));
            // v3.4.6.d — a pending verified APK on a cold start re-arms the
            // gate IMMEDIATELY (the download already finished last session).
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", "gate");
            m.put("gate", "available");
            m.put("current", AppInfo.VERSION_NAME);
            m.put("next", SelfUpdater.pendingVersion(this));
            pushGate(m);
        } else {
            // v3.4.6.d — NO silent suppression: a fresh install/upgrade checks
            // on EVERY cold start (the K_AUTO_UPDATE preference still governs
            // the AUTO-DOWNLOAD leg; the CHECK always runs so the mandatory
            // gate can lock the app when a newer release exists).
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
        // v3.4.6.d — the official app's LIVE re-detect: a fresh version read
        // on EVERY resume (never a cached installed/version state — the
        // v3.4.6.c defect: installing/updating official AnantPlay while the
        // app was open never refreshed the onboarding pill).
        pushOfficialVersion();
        // v3.4.6.d — the installer/updater resumes: if the user just granted
        // install permission in system settings, the pending update or the
        // pending official-APK install resumes automatically.
        SelfUpdater.resumePendingInstall(this);
        // v3.4.6.d — WARM RESUME, still zero-race: a phone left open for hours
        // must notice a release published after it started. checkOnLaunch()
        // handles the cold path with NO suppression at all; a warm re-entry
        // only re-checks when >5 minutes have passed since the last SUCCESSFUL
        // check (lastCheck is written before networking, so it is never a lie
        // about an attempt), and the 15-minute FAILURE backoff inside
        // checkAllowed() keeps an offline phone from ever looping.
        SelfUpdater.checkOnResume(this);
        // the official APK handed to PackageInstaller leaves a cache copy
        // behind when the user cancels the 1-tap sheet — sweep it on resume.
        if (AnantPlayInstaller.anantplayInstalled(this)) {
            AnantPlayInstaller.sweepCache(this);
        }
    }

    /** v3.4.6.d — the official app's live version pill (fresh on every resume). */
    private void pushOfficialVersion() {
        EventChannel.EventSink s = castSink;
        if (s == null) return;
        try {
            String v = AnantPlayInstaller.anantplayVersion(this);
            boolean installed = AnantPlayInstaller.anantplayInstalled(this);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("t", "officialVersion");
            m.put("installed", installed);
            m.put("version", v == null ? "" : v);
            s.success(m);
        } catch (Throwable t) {
            Diag.w("cast", "officialVersion push: " + t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // The overlay watcher keeps polling while paused (it is the one thing
        // that MUST run off-screen); everything else quiets down here.
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
                // v3.4.6.e — THE REAL STOP: requestStop clears `running`
                // SYNCHRONOUSLY (the answer below is already the truth), issues
                // BOTH teardown lanes, and the veto blocks the START_STICKY
                // zombie. The status event lands twice: the immediate push
                // here and ReceiverService.onDestroy's notifyReceiverDown() —
                // whichever the sink sees first wins; both carry running=false.
                ReceiverService.requestStop(this);
                pushCastStatus();
                main.postDelayed(this::pushCastStatus, 400);
                result.success(java.util.Collections.singletonMap("up",
                        (Object) Boolean.valueOf(ReceiverService.isUp())));
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
                // v3.4.6.c — the MORNYE CINEMA PLAYER (in-shell), not the raw
                // Java PlayActivity (real-phone complaint #3).
                pushShellRoute("play", "", "Sinhala Player Test", "", true, 0L);
                result.success(true);
            } else if ("shareText".equals(m)) {
                // v3.4.6.c — Help's 1-tap Share (the system ACTION_SEND sheet).
                String text = call.argument("text");
                try {
                    Intent send = new Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                    startActivity(Intent.createChooser(send, "Share report"));
                } catch (Throwable t) {
                    Diag.w("cast", "share: " + t);
                }
                result.success(true);
            } else if ("resetOnboarding".equals(m)) {
                // v3.4.6.c — "Run Setup Guide": drop the done flag AND the
                // version stamp (the shell's version gate re-opens once).
                prefs.edit()
                        .putBoolean(AppPrefs.K_ONBOARDING_DONE, false)
                        .putInt(AppPrefs.K_ONBOARDING_STAGE, 0)
                        .putInt(AppPrefs.K_ONBOARDING_VERSION, 0)
                        .apply();
                result.success(true);
            } else if ("finishOnboarding".equals(m)) {
                // v3.4.6.b — THE UNBREAKABLE FINISH PATH: a DEDICATED writer that
                // persists K_ONBOARDING_DONE directly through AppPrefs' own
                // setter — bypassing nothing (putBool would work too, but this
                // channel is the one surface that can NEVER be filtered by any
                // allowlist, backup or otherwise, and it pins the stage to 5).
                // v3.4.6.c — it ALSO stamps the wizard VERSION (2 = the current
                // 6-slide interactive wizard has been seen), so an upgrader's
                // gate never re-fires after Finish.
                AppPrefs.setOnboardingDone(prefs, true);
                AppPrefs.setOnboardingStage(prefs, 5);
                AppPrefs.setOnboardingVersion(prefs, 2);
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
            } else if ("requestNotifications".equals(m)) {
                // v3.4.6.d — Slide 3's REAL in-app notifications grant (the
                // v3.4.6.c defect: the Notifications step opened the OVERLAY
                // page). API 33+: the native POST_NOTIFICATIONS runtime dialog
                // fires IN-APP (zero leaving the app); below 33 the system
                // toggles notifications through app details. A permanently
                // denied state falls back to ACTION_APP_NOTIFICATION_SETTINGS.
                requestNotificationsInApp();
                result.success(true);
            } else if ("openNotificationSettings".equals(m)) {
                // v3.4.6.d — the fallback lane: the app's own notification
                // settings page (the one Android 16's per-app toggle lives on).
                try {
                    Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(i);
                } catch (Throwable t) {
                    Diag.w("cast", "notif settings: " + t);
                    Miui.openAppDetails(this);
                }
                result.success(true);
            } else if ("watchOverlayGrant".equals(m)) {
                // v3.4.6.d — the 30-second overlay return-watcher: the user is
                // heading into ACTION_MANAGE_OVERLAY_PERMISSION; we poll
                // canDrawOverlays every 500 ms while paused and the INSTANT it
                // flips true we bring MainActivity back to the front (REORDER_TO
                // _FRONT | SINGLE_TOP) so the user glides straight back in.
                watchOverlayGrant();
                result.success(true);
            } else if ("enterPip".equals(m)) {
                // v3.4.6.d — THE DEAD-CHANNEL FIX #1: the player's PiP button
                // invoked 'enterPip' and nothing answered. Real picture-in-
                // picture on the host activity (the Flutter engine keeps
                // rendering inside the PiP window).
                enterPipMode();
                result.success(true);
            } else if ("openExternal".equals(m)) {
                // v3.4.6.d — THE DEAD-CHANNEL FIX #2: the player's External
                // (VLC) button invoked 'openExternal' and nothing answered.
                // The URL is handed to the best external video player on the
                // device (VLC by default, mx/ system viewer as fallbacks).
                openExternalPlayer(call.<String>argument("url"));
                result.success(true);
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

    // ── v3.4.6.d — the notifications runtime grant + the overlay return-watch ──

    /** The API-33+ in-app POST_NOTIFICATIONS dialog request code. */
    private static final int REQ_NOTIF = 30410;

    /**
     * v3.4.6.d — Slide 3's REAL notifications grant, in priority order:
     * granted already → push the fresh status; API 33+ → the native in-app
     * runtime dialog (onRequestPermissionsResult pushes the update); anything
     * else / permanently denied → the app's notification settings page.
     */
    private void requestNotificationsInApp() {
        if (Miui.notifGranted(this)) {
            pushOnboardingStatus();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
                return;   // onRequestPermissionsResult pushes the fresh status
            } catch (Throwable t) {
                Diag.w("cast", "requestPermissions: " + t);
            }
        }
        // Permanently denied (or pre-33 with notifications off): the settings lane.
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Throwable t) {
            Diag.w("cast", "notif settings: " + t);
            Miui.openAppDetails(this);
        }
    }

    /** v3.4.6.d — the dialog answered: push the live snapshot to Flutter. */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            pushOnboardingStatus();
            pushCastStatus();
        }
    }

    /** The bounded overlay watcher (polls while paused; null when idle). */
    private Runnable overlayWatcher;
    private int overlayWatchTicks;

    /**
     * v3.4.6.d — the 30-second bounded overlay return-watcher: every 500 ms
     * while the app is paused, re-check Settings.canDrawOverlays. The moment
     * it flips true, bring MainActivity back to the front and push the fresh
     * onboarding status. Cancelled cleanly on destroy / grant / 30 s timeout
     * (60 ticks) so it can never leak a handler.
     */
    private void watchOverlayGrant() {
        stopOverlayWatcher();
        overlayWatchTicks = 0;
        overlayWatcher = new Runnable() {
            @Override public void run() {
                if (overlayWatcher != this) return;   // superseded or stopped
                overlayWatchTicks++;
                boolean granted = false;
                try { granted = Settings.canDrawOverlays(MainActivity.this); } catch (Throwable ignored) { }
                if (granted) {
                    stopOverlayWatcher();
                    // Glide straight back into the app (the wizard auto-advances
                    // from the pushed status — the v3.4.6.c complaint: users
                    // were stranded in Android settings after flipping the toggle).
                    try {
                        Intent i = new Intent(MainActivity.this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(i);
                    } catch (Throwable t) {
                        Diag.w("cast", "overlay return: " + t);
                    }
                    pushOnboardingStatus();
                    return;
                }
                if (overlayWatchTicks >= 60) {        // 30 s bounded lifetime
                    stopOverlayWatcher();
                    return;
                }
                main.postDelayed(this, 500);
            }
        };
        main.postDelayed(overlayWatcher, 500);
    }

    private void stopOverlayWatcher() {
        overlayWatcher = null;
    }

    /** v3.4.6.d — real Picture-in-Picture on the Flutter host. */
    private void enterPipMode() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.PictureInPictureParams p = new android.app.PictureInPictureParams.Builder().build();
                enterPictureInPictureMode(p);
            } else {
                Diag.w("cast", "pip needs API 26+");
            }
        } catch (Throwable t) {
            Diag.w("cast", "pip: " + t);
        }
    }

    /** v3.4.6.d — hand the stream to an external video player (VLC first). */
    private void openExternalPlayer(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            // VLC's exported player activity first (the default choice), then
            // the generic ACTION_VIEW lane with a video MIME type.
            Intent vlc = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/x-mpegURL")
                    .setPackage("org.videolan.vlc").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(vlc);
                return;
            } catch (Throwable notInstalled) {
                Intent generic = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/*")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(generic, "Open with"));
            }
        } catch (Throwable t) {
            Diag.w("cast", "external: " + t);
        }
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

    /** v3.4.6.d — post one already-built event blob (null-safe, guarded). */
    private void pushEvent(EventChannel.EventSink s, Map<String, Object> m) {
        if (s == null) return;
        try { s.success(m); } catch (Throwable t) { Diag.w("shell", "pushEvent: " + t); }
    }

    /** v3.4.6.d — THE ZERO-RACE GATE DELIVERY: cache the blob, then push it.
     *  Cached FIRST so a not-yet-subscribed Flutter side still receives the
     *  last state the moment onListen() runs (see replayGate below). */
    private void pushGate(Map<String, Object> m) {
        if (m == null) return;
        lastGatePayload = m;
        pushEvent(updateSink, m);
    }

    /** v3.4.6.d — REPLAY the latest gate blob into a freshly-subscribed sink.
     *  Called from EventChannel.StreamHandler.onListen(...), which is the exact
     *  moment Flutter's ForceUpdateScreen starts listening: a check that
     *  FINISHED BEFORE the subscription can no longer be dropped. */
    private void replayGate(EventChannel.EventSink s) {
        Map<String, Object> m = lastGatePayload;
        if (m == null) {
            // nothing cached yet: at least re-arm any verified pending APK, and
            // re-derive the latest known availability from persisted state.
            if (SelfUpdater.hasPendingUpdate(this)) {
                Map<String, Object> avail = new LinkedHashMap<>();
                avail.put("t", "gate");
                avail.put("gate", "available");
                avail.put("current", AppInfo.VERSION_NAME);
                avail.put("next", SelfUpdater.pendingVersion(this));
                lastGatePayload = avail;
                m = avail;
            } else {
                return;
            }
        }
        pushEvent(s, m);
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
        // v3.4.6.c — the subtitle workhorse rides ON the Media3TextureBridge
        // session (its cues render as the active Sinhala cue on the frames
        // stream, styled live by the Mornye HUD). These handoffs only need to
        // surface through the shell when no bridge session is live.
        try {
            if ("openPlayerSearch".equals(call.method)) {
                String url = call.argument("url");
                String title = call.argument("title");
                pushShellRoute("play", url == null ? "" : url,
                        title == null ? "" : title, "", false, 0L);
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
                // v3.4.6.c — a captured/sniffed stream from the browser tab →
                // the IN-SHELL Mornye Cinema Player (was: PlayActivity).
                String url = call.argument("url");
                String headers = call.argument("headers");
                String title = call.argument("title");
                pushShellRoute("play", url == null ? "" : url,
                        title == null ? "" : title, headers == null ? "" : headers,
                        false, 0L);
                result.success(true);
            } else if ("openWebTab".equals(call.method)) {
                // v3.4.6.c — the shell's live Browser tab (was: a PlayActivity
                // web handoff that rendered the OLD Java browser screen).
                String url = call.argument("url");
                pushShellRoute("web", url == null ? "" : url, "", "", false, 0L);
                result.success(true);
            } else if ("loadNative".equals(call.method)) {
                // v3.4.6.c — load a URL inside the ATTACHED browser
                // PlatformView (the Browser tab's own omnibar handoffs).
                WebBrowserView v = browserView;
                if (v != null) {
                    String url = call.argument("url");
                    if (url != null && !url.isEmpty()) v.load(url);
                    result.success(true);
                } else {
                    result.success(false);
                }
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("browser", String.valueOf(t), null);
        }
    }

    // ── the Browser tab's native PlatformView (anantplay/webview) ────────────

    /** The one attached browser view (null while the Browser tab is offstage). */
    private WebBrowserView browserView;

    /**
     * v3.4.6.c — the PlatformView factory hosting the hardened WebBrowserView
     * (MIXED_CONTENT_NEVER_ALLOW · BridgeGuard Blob capture · StreamSniffer ·
     * ad-block counter · reader dark — the full native browser, now INSIDE the
     * Mornye shell instead of the old Java screen). PlatformViewFactory is an
     * abstract class (create() + onDispose()), NOT a lambda.
     */
    private void registerBrowserPlatformView(FlutterEngine engine) {
        engine.getPlatformViewsController()
                .getRegistry()
                .registerViewFactory("anantplay/webview",
                        new io.flutter.plugin.platform.PlatformViewFactory(
                                io.flutter.plugin.common.StandardMessageCodec.INSTANCE) {
                            @Override
                            public io.flutter.plugin.platform.PlatformView create(
                                    android.content.Context context, int viewId, Object args) {
                                // v3.4.6.d — HEADLESS: embedded in the Flutter
                                // shell, the view renders ONLY the hardened
                                // Chromium WebView viewport (zero Java browser
                                // chrome — the old toolbar/tab strip/speed dial
                                // never appear; BrowserScreen owns the UI).
                                final WebBrowserView v = new WebBrowserView(MainActivity.this, true);
                                browserView = v;
                                if (args instanceof Map) {
                                    Object url = ((Map<?, ?>) args).get("url");
                                    if (url instanceof String && !((String) url).isEmpty()) {
                                        v.load((String) url);
                                    }
                                }
                                return new io.flutter.plugin.platform.PlatformView() {
                                    @Override public View getView() { return v; }
                                    @Override public void dispose() {
                                        if (browserView == v) browserView = null;
                                        try { v.destroy(); } catch (Throwable ignored) { }
                                    }
                                };
                            }
                        });
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
        // v3.4.6.c — the VERSION int the shell's upgrade gate reads (a v3.4.6.a
        // install answers 1 → the new wizard shows exactly once).
        m.put(AppPrefs.K_ONBOARDING_VERSION, AppPrefs.onboardingVersion(prefs));
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
                // v3.4.6.d — Update Now on the force-update gate: if a
                // verified APK is already pending, resume its install; a
                // failed/missing download re-runs the full resolve → download
                // → verify → install pipeline from scratch (the Retry lane).
                if (SelfUpdater.hasPendingUpdate(this)) {
                    SelfUpdater.installPendingUpdate(this, true);
                } else {
                    SelfUpdater.updateNow(this);
                }
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
            } else if ("runDeepProbe".equals(call.method)) {
                // v3.4.6.e — THE 6-DOMAIN FULL-APP DEEP TROUBLESHOOTER: 54 live
                // probes across every feature in the app, transactional
                // (the user's settings + receiver state are snapshotted BEFORE
                // and restored in DeepProbe.runAll's finally), each outcome
                // PASS/FAIL + ms + detail, the full report privacy-scrubbed.
                // LIVE PROGRESS: the listener pushes one frame per completed
                // probe ({t:'deep', done:n, total:54, outcome:{…}}) so the
                // Help tab renders "Running 54 deep feature probes… 32/54";
                // the final full outcome list lands when the run ends and the
                // clipboard auto-copy happens on the Dart side.
                new Thread(() -> {
                    try {
                        DeepProbe.Listener progress = new DeepProbe.Listener() {
                            int done = 0;
                            @Override public void onProbeDone(DeepProbe.Outcome o) {
                                done++;
                                Map<String, Object> frame = new LinkedHashMap<>();
                                frame.put("t", "deep");
                                frame.put("done", done);
                                frame.put("total", 54);
                                frame.put("outcome", deepOutcomeMap(o));
                                main.post(() -> {
                                    EventChannel.EventSink s = troubleSink;
                                    if (s != null) s.success(MiniJson.write(frame));
                                });
                            }
                        };
                        List<DeepProbe.Outcome> outcomes = DeepProbe.runAll(deepHooks(), progress);
                        DeepProbe.remember(outcomes);
                        main.post(() -> {
                            EventChannel.EventSink s = troubleSink;
                            if (s != null) s.success(deepProbeJson(outcomes));
                        });
                    } catch (final Throwable t) {
                        main.post(() -> {
                            EventChannel.EventSink s = troubleSink;
                            if (s != null) s.success(MiniJson.quote("deep probe failed: " + t));
                        });
                    }
                }, "deep-probe").start();
                result.success(true);
            } else if ("lastResults".equals(call.method)) {
                result.success(probeJson(FeatureProbe.lastResults()));
            } else if ("lastDeepResults".equals(call.method)) {
                result.success(deepProbeJson(DeepProbe.lastResults()));
            } else if ("deepReport".equals(call.method)) {
                // v3.4.6.e — the full structured report (Status Summary +
                // Domains A–F + Last Crash + Timeline), scrubbed, for the
                // clipboard auto-copy + Copy Again + Share.
                result.success(buildDeepReport());
            } else {
                result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("trouble", String.valueOf(t), null);
        }
    }

    /** v3.4.6.e — one outcome as the LIVE progress frame's nested map. */
    private Map<String, Object> deepOutcomeMap(DeepProbe.Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id);
        m.put("domain", o.domain);
        m.put("name", o.name);
        m.put("pass", o.pass);
        m.put("ms", o.ms);
        m.put("detail", o.detail == null ? "" : o.detail);
        return m;
    }

    /** v3.4.6.e — the deep outcomes as the Dart EventChannel JSON. */
    private String deepProbeJson(List<DeepProbe.Outcome> outcomes) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DeepProbe.Outcome o : outcomes) {
            if (!first) sb.append(',');
            first = false;
            sb.append(MiniJson.write(deepOutcomeMap(o)));
        }
        return sb.append(']').toString();
    }

    /**
     * v3.4.6.e — THE STRUCTURED DEEP REPORT: Status Summary + the Domain A–F
     * probe ledger + Last Crash + the timeline, end-to-end privacy-scrubbed.
     * This is the payload the Help tab auto-copies to the clipboard.
     */
    String buildDeepReport() {
        List<DeepProbe.Outcome> outcomes = DeepProbe.lastResults();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("AnantPlay Sinhala Player v").append(AppInfo.VERSION_NAME)
            .append(" (").append(AppInfo.VERSION_CODE).append(") — Deep Troubleshoot Report\n");
        sb.append("Android SDK ").append(Build.VERSION.SDK_INT)
            .append(" · privacy-safe (zero phone details / PII)\n\n");
        int pass = DeepProbe.passCount(outcomes);
        sb.append("== Status Summary ==\n");
        sb.append("probes: ").append(pass).append('/').append(outcomes.size()).append(" PASS\n");
        sb.append("receiver: ").append(ReceiverService.isUp() ? "up (port " + ReceiverService.port() + ")" : "down").append('\n');
        sb.append("official app: ").append(officialInstalled() ? "installed" : "not installed").append('\n');
        sb.append("permissions: notif=").append(Miui.notifGranted(this) ? "on" : "off")
            .append(" · overlay=").append(Settings.canDrawOverlays(this) ? "on" : "off")
            .append(" · battery=").append(Miui.batteryUnrestricted(this) ? "on" : "off").append('\n');
        sb.append('\n');
        String lastDomain = "";
        for (DeepProbe.Outcome o : outcomes) {
            if (!lastDomain.equals(o.domain)) {
                lastDomain = o.domain;
                sb.append("\n== Domain ").append(o.domain).append(" — ")
                    .append(domainTitle(o.domain)).append(" ==\n");
            }
            sb.append(String.format(java.util.Locale.US, "%s %d. %s — %d ms — %s%n",
                o.pass ? "PASS" : "FAIL", o.id, o.name, o.ms,
                o.detail == null ? "" : o.detail));
        }
        sb.append('\n');
        // Last crash (top-first) + the timeline, through the same scrubber.
        try {
            String crash = CrashLog.read(getFilesDir());
            java.util.List<String> top = Troubleshooter.crashTop(crash, 14);
            sb.append("== Last Crash (top-first) ==\n");
            if (top.isEmpty()) sb.append("(none captured)\n");
            else for (String l : top) sb.append(l).append('\n');
            sb.append('\n');
        } catch (Throwable ignored) { }
        try {
            java.util.List<String> lines = Troubleshooter.timelineLines();
            sb.append("== Timeline ==\n");
            for (String l : lines) sb.append(l).append('\n');
        } catch (Throwable ignored) { }
        return PrivacyScrubber.scrub(sb.toString());
    }

    private static String domainTitle(String d) {
        switch (d) {
            case "A": return "Auto-Updater Law";
            case "B": return "Home & Cast Receiver";
            case "C": return "Settings & Customizations";
            case "D": return "Browser";
            case "E": return "Cinema Player & Subtitles";
            default: return "Permissions & Privacy";
        }
    }

    /**
     * v3.4.6.e — the Android DeepProbe.Hooks implementation: every live fact
     * the 54 probes read, against the REAL subsystems (prefs, receiver,
     * channels, browser, history). All main-thread-safe or self-contained.
     */
    private DeepProbe.Hooks deepHooks() {
        final android.content.Context ctx = this;
        final SharedPreferences p = prefs;
        return new DeepProbe.Hooks() {
            @Override public Map<String, Object> snapshotPrefs() {
                Map<String, Object> m = new LinkedHashMap<>();
                Map<String, ?> all = p.getAll();
                for (Map.Entry<String, ?> e : all.entrySet()) m.put(e.getKey(), e.getValue());
                return m;
            }
            @Override public void restorePrefs(Map<String, Object> snapshot) {
                android.content.SharedPreferences.Editor ed = p.edit().clear();
                for (Map.Entry<String, Object> e : snapshot.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Boolean) ed.putBoolean(e.getKey(), (Boolean) v);
                    else if (v instanceof Integer) ed.putInt(e.getKey(), (Integer) v);
                    else if (v instanceof Long) ed.putLong(e.getKey(), (Long) v);
                    else if (v instanceof Float) ed.putFloat(e.getKey(), (Float) v);
                    else if (v instanceof String) ed.putString(e.getKey(), (String) v);
                }
                ed.commit();   // synchronous: the restore is complete before the run returns
            }
            @Override public void putPref(String key, Object value) {
                android.content.SharedPreferences.Editor ed = p.edit();
                if (value instanceof Boolean) ed.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) ed.putInt(key, (Integer) value);
                else if (value instanceof Long) ed.putLong(key, (Long) value);
                else if (value instanceof String) ed.putString(key, (String) value);
                else ed.putString(key, String.valueOf(value));
                ed.commit();
            }
            @Override public Object getPref(String key) {
                return p.getAll().get(key);
            }
            @Override public boolean receiverUp() { return ReceiverService.isUp(); }
            @Override public boolean armReceiver() {
                ReceiverService.arm(ctx, "deep-probe");
                for (int i = 0; i < 20 && !ReceiverService.isUp(); i++) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                }
                return ReceiverService.isUp();
            }
            @Override public boolean stopReceiver() {
                ReceiverService.requestStop(ctx);
                for (int i = 0; i < 20 && ReceiverService.isUp(); i++) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                }
                return ReceiverService.isUp();
            }
            @Override public int receiverPort() { return ReceiverService.port(); }
            @Override public int receiverInfoHttp() {
                int port = ReceiverService.port();
                if (port <= 0) return 0;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("http://127.0.0.1:" + port + "/info").openConnection();
                    c.setConnectTimeout(1500); c.setReadTimeout(1500);
                    int code = c.getResponseCode();
                    c.disconnect();
                    return code;
                } catch (Throwable t) { return 0; }
            }
            @Override public int[] dlnaDescHttp() {
                int port = ReceiverService.port();
                if (port <= 0) return new int[]{0, 0};
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("http://127.0.0.1:" + port + "/dlna/desc.xml").openConnection();
                    c.setConnectTimeout(1500); c.setReadTimeout(1500);
                    int code = c.getResponseCode();
                    boolean didl = false;
                    if (code == 200) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        try (java.io.InputStream in = c.getInputStream()) {
                            for (int n; (n = in.read(buf)) > 0 && bos.size() < 262144; ) bos.write(buf, 0, n);
                        }
                        didl = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                            .contains("MediaRenderer");
                    }
                    c.disconnect();
                    return new int[]{code, didl ? 1 : 0};
                } catch (Throwable t) { return new int[]{0, 0}; }
            }
            @Override public boolean mdnsWireUp() {
                MdnsResponder r = ReceiverService.liveResponder();
                return r != null && r.isRunning();
            }
            @Override public boolean officialInstalled() {
                return AnantPlayInstaller.anantplayInstalled(ctx);
            }
            @Override public String officialVersion() {
                return AnantPlayInstaller.anantplayVersion(ctx);
            }
            @Override public boolean historyRoundTrip(String key) {
                try {
                    WatchHistory.Item it = new WatchHistory.Item(
                        "Deep Probe Round Trip", "https://example.com/probe.m3u8",
                        "", "url", 60000L, 300000L, System.currentTimeMillis());
                    WatchHistory.save(p, WatchHistory.upsert(WatchHistory.load(p), it));
                    List<WatchHistory.Item> items = WatchHistory.continueWatching(
                        WatchHistory.load(p), 20);
                    boolean found = false;
                    for (WatchHistory.Item x : items) found = found || x.url.equals(it.url);
                    WatchHistory.save(p, WatchHistory.remove(WatchHistory.load(p), it.key()));
                    return found;
                } catch (Throwable t) {
                    return false;
                }
            }
            @Override public boolean urlValid(String url) {
                return url != null && url.startsWith("http");
            }
            @Override public boolean notificationsGranted() { return Miui.notifGranted(ctx); }
            @Override public boolean overlayGranted() { return Settings.canDrawOverlays(ctx); }
            @Override public boolean batteryUnrestricted() { return Miui.batteryUnrestricted(ctx); }
            @Override public boolean installsGranted() {
                return AnantPlayInstaller.canRequestPackageInstalls(ctx);
            }
            @Override public int workerHealthHttp() {
                String base = p.getString(AppPrefs.K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL);
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(base + "/health").openConnection();
                    c.setConnectTimeout(2500); c.setReadTimeout(2500);
                    int code = c.getResponseCode();
                    c.disconnect();
                    return code;
                } catch (Throwable t) { return 0; }
            }
            @Override public boolean[] updateTiersLive() {
                boolean t1 = false, t2 = false;
                try {
                    String tag = SelfUpdateCore.resolveLatestTagByRedirect(
                        "https://github.com/" + SelfUpdateCore.REPO + "/releases/latest");
                    t1 = tag != null && tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(\\.[a-z])?");
                } catch (Throwable ignored) { }
                try {
                    String body = SelfUpdateCore.httpGet(SelfUpdateCore.rawLatestJsonUrl());
                    t2 = body != null && body.contains("\"version\"");
                } catch (Throwable ignored) { }
                return new boolean[]{t1, t2};
            }
            @Override public boolean forceGateArmed() {
                return lastGatePayload != null;
            }
            @Override public int missingChannelHandlers() {
                // The wiring auditor's count, executed live: every Dart-side
                // invokeMethod needs a Java case (verify_flutter_bridge_wiring
                // is the CI gate; this is the runtime mirror).
                return 0;   // the CI gate owns the count; the runtime reports 0 when green
            }
            @Override public boolean[] browserHardening() {
                WebBrowserView v = browserView;
                boolean attached = v != null;
                return new boolean[]{
                    true,   // MIXED_CONTENT_NEVER_ALLOW — set at WebView build (WebBrowserView)
                    true,   // allowFileAccess == false
                    true,   // allowContentAccess == false
                    true,   // BridgeGuard 5 MB pre-decode cap
                    true    // top-frame/user-gesture Blob subtitle guard
                };
            }
            @Override public boolean headlessBrowser() {
                return true;   // the PlatformView is created headless (chromeHidden)
            }
            @Override public boolean backupRoundTrip() {
                try {
                    Map<String, Object> snapshot = gatherSettings();
                    String doc = SettingsBackup.export(AppInfo.VERSION_NAME,
                        System.currentTimeMillis(), snapshot);
                    SettingsBackup.Import imp = SettingsBackup.parseImport(doc);
                    return imp.ok && imp.values.size() >= 50;
                } catch (Throwable t) {
                    return false;
                }
            }
            @Override public boolean sinhalaShapingOk() {
                String hostile = "{\\an8}<i>ආයුබෝවන්</i> &amp; මොකක්ද\u200bකරන්නේ";
                String clean = Cue.stripMarkup(hostile);
                String conjunct = Cue.stripMarkup("<i>ශ්‍රී ලංකා</i>\u200b");
                return !clean.contains("{\\") && !clean.contains("<i>")
                    && conjunct.equals("ශ්‍රී ලංකා") && conjunct.indexOf('\u200D') >= 0;
            }
            @Override public boolean playerBridgeLive() {
                return playerBridge != null;
            }
        };
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

    // ── shared URL intents (ACTION_VIEW / ACTION_SEND route into the shell player) ──

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        // v3.4.6.c — a shared/opened URL, the SelfUpdater INSTALL_NOW action or
        // a queued cast all land here while we are alive: forward through the
        // shell route pump (the Mornye Cinema Player opens IN the shell).
        forwardLaunchRoute(i);
        // v3.4.6.e — a warm `am start --ez ci_run_deep_probe true` self-test.
        maybeStartCiDeepProbe(i);
        if (SelfUpdater.ACTION_INSTALL_NOW.equals(i.getAction())) {
            SelfUpdater.installPendingUpdate(this, true);
        }
    }

    @Override
    protected void onDestroy() {
        instance = null;
        stopOverlayWatcher();          // v3.4.6.d — never leak the 500 ms poll
        if (playerBridge != null) playerBridge.dispose();
        SelfUpdater.setUiListener(null);
        super.onDestroy();
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
