package lk.techworld.anantplay.sicast;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/* THE receiver — the ESET survival pattern applied to the cast bridge:
 *
 *  - a connectedDevice FOREGROUND SERVICE (exactly AnantPlay's own Cast$Keep type,
 *    Cast.java:209-222, proven on MIUI by their whole user base) that keeps the
 *    ReceiverServer and the mDNS announcement alive after the app is left;
 *  - an IMPORTANCE_MIN notification ("අ — ready for AnantPlay cast") with quick
 *    actions: Open, Self-test, Stop;
 *  - the Backend wiring: /play starts PlayActivity (waits for it like Cast.play's
 *    50 × 100 ms loop), /do drives the player on the main thread (Cast.onMain's
 *    FutureTask pattern) or the device volume, /status reads the player snapshot.
 *
 * NO AccessibilityService anywhere. If HyperOS ever kills this idle service, nothing
 * shows "Not working": the user reopens the app or taps the tile, and while a movie
 * is playing the app IS the foreground app — unkillable by powerkeeper policy.
 *
 * Android 14–16 / HyperOS hardening (audit Gate 1):
 *   · startForeground() runs FIRST in onCreate — always inside the 5 s window, even when
 *     port binding fails (a ForegroundServiceDidNotStartInTimeException would otherwise
 *     kill the app on Android 12+);
 *   · every background start path (boot, app start, tile) tolerates
 *     ForegroundServiceStartNotAllowedException and records exactly why;
 *   · a cast that arrives WITHOUT the overlay permission still succeeds: the player is
 *     opened with a high-priority tap-to-open notification instead of the (blocked)
 *     background activity launch — never an exception, never a dead cast;
 *   · the service stops its own foreground state on destroy (no ghost notification). */
public class ReceiverService extends Service implements ReceiverServer.Backend {

    static final String ACTION_STOP = "lk.techworld.anantplay.sicast.STOP";
    static final String ACTION_TEST = "lk.techworld.anantplay.sicast.TEST";
    /** v3.1.0 — re-arm with fresh prefs (the Cast & Network settings page). */
    static final String ACTION_APPLY_PREFS = "lk.techworld.anantplay.sicast.APPLY_PREFS";
    /** v3.4.6.f — GAP #3: DEDICATED state file, deliberately NOT "sicast" so
     *  SettingsBackup / resetAll / DeepProbe prefs snapshots never touch it. */
    static final String STATE_PREFS = "receiver_state";
    static final String KEY_USER_STOP = "userStop";

    private static volatile ReceiverService running;
    /** v3.3.0 audit — the Sinhala .srt the loopback /subs/current.srt route serves to
     *  external players (VLC/MX/mpv). Kept here, NOT in an Activity, so a rotation or
     *  a finished PlayActivity can never leak an Activity through the service. */
    private static volatile String subsSrt;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** v3.4.3 — the last bring-to-front takeover (Replit QA-0024: never a launch loop). */
    private volatile long lastTakeoverAtMs;
    private ReceiverServer server;
    private NsdAnnouncer nsd;
    private DlnaEndpoint dlnaEndpoint;
    private DlnaRenderer dlnaRenderer;
    /** The DLNA RenderingControl's tracked volume (AnantPlay's remote ±5 steps it). */
    private volatile int dlnaVolume = 50;
    private volatile boolean nexusTakeover;   // bring our player over AnantPlay's Remote

    public static boolean isUp() { return running != null; }
    public static int port() { ReceiverService s = running; return s == null ? -1 : (s.server == null ? -1 : s.server.port()); }

    /**
     * v3.3.0 audit — publish (or clear) the active Sinhala .srt for external players.
     * Thread-safe and idempotent: the value is kept statically so a later re-arm of the
     * server (self-heal, preference change, process restart of the service) restores the
     * route without PlayActivity doing anything.
     */
    static void setSubsSrt(String srt) {
        subsSrt = (srt == null || srt.trim().isEmpty()) ? null : srt;
        ReceiverService s = running;
        if (s != null && s.server != null) s.server.setSubsSrt(subsSrt);
    }

    /** The URL an external player can read, or null when the receiver is not running. */
    static String subsUrl() {
        ReceiverService s = running;
        return s == null || s.server == null ? null : s.server.subsUrl();
    }
    /** v2.3.0 — the live announcer's own 5353 responder (null when the service or the responder is down). */
    public static MdnsResponder liveResponder() {
        ReceiverService s = running;
        return s == null || s.nsd == null ? null : s.nsd.responder();
    }
    /** v3.0.0 — the live SSDP responder (null when the service or the DLNA renderer is down). */
    public static DlnaRenderer liveDlnaRenderer() {
        ReceiverService s = running;
        return s == null ? null : s.dlnaRenderer;
    }
    /** v3.0.0 — TRUE when either cast-discovery pillar is answering on the wire
     *  (our own mDNS responder OR the DLNA SSDP responder): AnantPlay's Cast chip
     *  lights up from either, regardless of the NsdManager daemon's state. */
    public static boolean castDiscoverable() {
        MdnsResponder r = liveResponder();
        DlnaRenderer d = liveDlnaRenderer();
        return (r != null && r.isRunning() && r.isPortShared()) || (d != null && d.isRunning());
    }
    /** v2.4.0 — the REAL NsdManager registration state (onServiceRegistered fired). */
    public static boolean isAnnounced() {
        ReceiverService s = running;
        return s != null && s.nsd != null && s.nsd.isAnnounced();
    }
    /** v2.3.0 — a defensive self-heal: re-check the server + announcement and re-arm
     *  both when a HyperOS powerkeeper pass left them half-dead. Runs every 15 min. */
    private final Handler heal = new Handler(Looper.getMainLooper());
    private final Runnable healTask = new Runnable() {
        @Override public void run() {
            try {
                boolean serverOk = server != null && server.isRunning();
                boolean announceOk = (nsd != null && (nsd.isAnnounced() || (nsd.responder() != null && nsd.responder().isRunning())))
                    || (dlnaRenderer != null && dlnaRenderer.isRunning());   // v3.0.0: either discovery pillar is enough
                if (userStop) {
                    // v3.4.6.f — the 15-minute healer must NEVER undo an
                    // explicit user stop (it used to call arm() and clear it).
                    Diag.i("service", "self-heal skipped: standing user stop");
                    stopSelf();
                    return;
                }
                if (!serverOk || !announceOk) {
                    Diag.w("service", "self-heal: re-arming (server=" + serverOk + " announce=" + announceOk + ")");
                    stopSelf();
                    android.os.SystemClock.sleep(300);
                    arm(ReceiverService.this, "self-heal");
                    return;
                }
                heal.postDelayed(this, 15 * 60 * 1000L);
            } catch (Throwable t) {
                Diag.w("service", "self-heal: " + t);
                heal.postDelayed(this, 15 * 60 * 1000L);
            }
        }
    };

    /** v3.4.6.f — GAP #3: the persisted veto. commit() (not apply()) makes a
     *  user stop survive an immediate process death / reboot / 15-min heal. */
    static void setUserStop(Context c, boolean stopped) {
        userStop = stopped;
        try {
            c.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USER_STOP, stopped).commit();
        } catch (Throwable t) {
            Diag.w("service", "userStop persist failed: " + t);
        }
    }

    /** Restore before the app's auto-arm decision (Application.onCreate). */
    static void restoreUserStop(Context c) {
        try {
            userStop = c.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_STOP, false);
        } catch (Throwable t) {
            Diag.w("service", "userStop restore failed: " + t);
        }
    }

    /** The ONLY surface allowed to clear the veto: the explicit Start tap. */
    static void armFromUser(Context c) {
        userStop = false;   // the explicit-start clear (source contract)
        setUserStop(c, false);
        arm(c, "user-start");
    }

    /** Best-effort arm from any entry point (app start, boot, tile). */
    static void arm(Context c, String why) {
        Diag.i("service", "arm (" + why + ") from " + c.getClass().getSimpleName());
        // v3.4.6.f — a STANDING user stop vetoes every non-user caller: app
        // start, boot, apply-prefs, self-heal, deep-probe restore. Only
        // armFromUser() (Home's Start Receiver) may clear it.
        if (userStop) {
            Diag.i("service", "arm (" + why + ") refused: standing user stop");
            return;
        }
        try {
            c.startForegroundService(new Intent(c, ReceiverService.class));
        } catch (Throwable t) {
            // Android 12+ / HyperOS: a background app may not start a foreground service.
            // Class-name check (not a direct catch) so this file stays API-26-safe.
            String n = t.getClass().getSimpleName();
            if (n.contains("ForegroundServiceStartNotAllowed") || n.contains("BackgroundServiceStartNotAllowed")) {
                Diag.w("service", "background FGS start blocked on this Android build (" + why
                    + ") — the app UI, the Quick Settings tile or the notification will arm the receiver");
            } else {
                Diag.w("service", "arm failed (" + why + "): " + t);
            }
        }
    }

    /**
     * v3.4.6.e — THE REAL STOP (the Home-tab "Stop Receiver" dead-button fix).
     *
     * <p>The v3.4.6.d path was `stopService(new Intent(...))` from MainActivity:
     * an ASYNCHRONOUS ask that races the one 400 ms postDelayed status push —
     * if the service teardown lands after that push, Flutter keeps rendering
     * "Active · Ready for Cast" and the button looks dead. This entry is the
     * deterministic contract the Home hero card needs:
     *
     * <ul>
     *   <li><b>userStop = true</b> — the process-sticky restart gets a clean
     *       veto: {@link #onStartCommand} and {@link #onTaskRemoved} refuse to
     *       re-arm while it is set, and onCreate() aborts a resurrected start
     *       (the START_STICKY zombie a HyperOS restart can deliver);</li>
     *   <li><b>both lanes</b> — the explicit ACTION_STOP start (onStartCommand:
     *       stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf()) AND the plain
     *       stopService() are issued together, so whichever one the vendor
     *       build honors first wins and the other is a no-op;</li>
     *   <li><b>running is cleared HERE</b> — port()/isUp() answer "down" the
     *       moment this call returns, not whenever onDestroy happens to land.</li>
     * </ul>
     *
     * <p>Re-arming (the Home Start button, {@link #arm}) clears the veto.
     *
     * @param c any context (the activity or the app)
     * @return the ACTION_STOP intent that was issued (never null)
     */
    static android.content.Intent requestStop(Context c) {
        Diag.i("service", "stop by user (requestStop)");
        setUserStop(c, true);   // v3.4.6.f — persisted across restarts/reboots
        try { Troubleshooter.record("APP", "receiver stop requested (user)"); } catch (Throwable ignored) { }
        android.content.Intent stop = new Intent(c, ReceiverService.class).setAction(ACTION_STOP);
        try { c.startService(stop); } catch (Throwable t) {
            // A background start may be refused; the stopService lane below is
            // still there, and running is already null (the honest state).
            Diag.w("service", "ACTION_STOP start refused: " + t);
        }
        try { c.stopService(new Intent(c, ReceiverService.class)); } catch (Throwable ignored) { }
        ReceiverService s = running;
        if (s != null) {
            // Synchronous local teardown so isUp()/port() answer "down" NOW.
            try { s.stopNow(); } catch (Throwable ignored) { }
        }
        running = null;   // the honest state the moment the user asked
        return stop;
    }

    /** v3.4.6.e — the in-object synchronous teardown (requestStop's core). */
    private void stopNow() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) { }
        if (running == this) running = null;   // v3.4.6.h — no ghost registration
        stopSelf();
    }

    /** v3.4.6.h — a live server socket owned by THIS instance (port published). */
    private boolean serverLive() {
        return server != null && server.port() > 0;
    }

    /** v3.4.6.h — TRUE when SOME live instance is actually serving HTTP. */
    private static boolean armedAndServing() {
        ReceiverService s = running;
        return s != null && s.serverLive();
    }

    /** v3.4.6.f — the user's explicit stop veto (persisted in receiver_state;
     *  cleared ONLY by armFromUser, or transiently by DeepProbe's restore). */
    private static volatile boolean userStop;

    /** The veto state (App, BootReceiver, lab and troubleshooter read it). */
    static boolean userStopped() { return userStop; }

    @Override
    public void onCreate() {
        super.onCreate();
        restoreUserStop(this);   // process restart / START_STICKY resurrection
        armCore();
    }

    /**
     * v3.4.6.h — THE IDEMPOTENT ARMING CORE (the live Android 36 re-arm defect).
     *
     * <p>Android can deliver a fresh startService()/startForegroundService() to an
     * instance that is still tearing down from a stopSelf(). In that case
     * onCreate() does NOT run again, so the old code left {@code running == null}
     * with a closed socket: isUp() answered false, port() answered -1, and the
     * receiver was silently dead until the process restarted. That is exactly the
     * "Stop Receiver, then Start again → DLNA/mDNS down" defect the live probes
     * caught on the Android 36 emulator.
     *
     * <p>This core is safe to call any number of times: it refuses on a standing
     * user stop, no-ops when ANOTHER live instance is already serving, and on
     * re-entry it recycles a stale socket and binds a fresh server before
     * re-announcing. onCreate() and onStartCommand() both route here.
     */
    private synchronized void armCore() {
        if (userStop) {
            // v3.4.6.e — the zombie veto: a START_STICKY resurrection after the
            // user's explicit stop must NOT re-announce on the LAN. Tear down
            // quietly; a future arm() clears the veto and re-arms for real.
            Diag.w("service", "armCore refused: standing user stop — refusing to re-arm");
            if (running == this) running = null;
            try { stopSelf(); } catch (Throwable ignored) { }
            return;
        }
        if (running != null && running != this && running.serverLive()) {
            Diag.i("service", "armCore: another live instance already serving — no-op");
            return;
        }
        running = this;
        // FIRST: satisfy the Android 12+/14+ foreground-service contract inside the 5 s
        // window. Nothing below may delay this — not port scanning, not mDNS.
        startInForeground();
        // v3.4.6.h — RE-ENTRY RECYCLE: a socket left over from a half-torn-down
        // instance is closed and replaced, so the published port always belongs to
        // a live accept loop (ReceiverServer.start() walks preferredPort..+25).
        if (server != null) {
            try { server.stop(); } catch (Throwable ignored) { }
            server = null;
        }
        server = new ReceiverServer(this);
        if (!server.start()) {
            Diag.e("service", "could not bind any port — receiver NOT armed");
            postState(R.string.notif_failed);
            if (running == this) running = null;
            try { stopSelf(); } catch (Throwable ignored) { }
            return;
        }
        // v3.1.0 (Bug 1) — prime the local-address snapshot the moment the server
        // exists: isSelf() then accepts our own wlan0 IP even if a LATER HyperOS
        // enumeration transiently fails (the 2026-11-22 refusal scenario).
        server.setLocalSnapshot(LocalNet.enumerateOwn());
        // v3.3.0 audit — restore the external-player subtitle route across re-arms.
        server.setSubsSrt(subsSrt);
        // v3.1.0 — the customization prefs: receiver display name + the two
        // discovery-pillar toggles (Cast & Network settings page).
        android.content.SharedPreferences prefs = getSharedPreferences("sicast", MODE_PRIVATE);
        String displayName = AppPrefs.receiverName(prefs);
        boolean dlnaOn = AppPrefs.dlnaEnabled(prefs);
        boolean mdnsOn = AppPrefs.mdnsEnabled(prefs);
        // v3.0.0 — THE SECOND CAST PILLAR: the UPnP/DLNA MediaRenderer. AnantPlay's
        // Cast.discover() SSDP-searches for MediaRenderer:1 on every app start and
        // Re-scan, and Cast.any() counts the result exactly like an mDNS TV — so
        // the Cast chip works even while NsdManager hangs (the Android 16 field
        // failure). The endpoint shares the /play /do /status engine; the SSDP
        // responder answers M-SEARCH on UDP 1900. UUID stays stable per install.
        // v3.1.0: friendlyName = the user's Custom Receiver Display Name.
        dlnaEndpoint = new DlnaEndpoint(new DlnaBridge(), dlnaUuid(), displayName,
            "Android/" + Build.VERSION.RELEASE + " UPnP/1.0 AnantPlaySinhalaPlayer/" + AppInfo.VERSION_NAME);
        server.setDlna(dlnaEndpoint);
        // v3.4.6.h — a re-armed instance must not double-announce: drop any
        // pillar object left over from the previous arming before restarting.
        if (dlnaRenderer != null) {
            try { dlnaRenderer.stop(); } catch (Throwable ignored) { }
            dlnaRenderer = null;
        }
        if (nsd != null) {
            try { nsd.stop(); } catch (Throwable ignored) { }
            nsd = null;
        }
        if (dlnaOn) {
            dlnaRenderer = new DlnaRenderer(dlnaEndpoint, () -> {
                java.net.InetAddress ip = NsdAnnouncer.wifiLanIp();
                return ip == null ? "" : "http://" + ip.getHostAddress() + ":" + server.port() + "/dlna/desc.xml";
            });
            dlnaRenderer.start();
        } else {
            Diag.i("service", "DLNA renderer disabled in Settings (Cast & Network)");
        }
        if (mdnsOn) {
            nsd = new NsdAnnouncer(this, server.port());
            nsd.start();
        } else {
            Diag.i("service", "mDNS announcer disabled in Settings (Cast & Network)");
        }
        heal.removeCallbacks(healTask);
        heal.postDelayed(healTask, 15 * 60 * 1000L);
        Diag.i("service", "up on port " + server.port()
            + (nsd == null ? "" : nsd.isAnnounced() ? " (announced)" : " (announce pending)")
            + (dlnaRenderer != null && dlnaRenderer.isRunning() ? " (dlna up)" : " (dlna off)")
            + " (name \"" + displayName + "\")");
    }

    /** A stable per-install UUID for the DLNA UDN (generated once, kept forever). */
    private String dlnaUuid() {
        android.content.SharedPreferences p = getSharedPreferences("sicast", MODE_PRIVATE);
        String u = p.getString("dlnaUuid", null);
        if (u == null || u.isEmpty()) {
            u = "SIcast-" + Integer.toHexString((int) (System.currentTimeMillis() & 0xFFFFFFFF)).toLowerCase(java.util.Locale.ROOT)
                + Integer.toHexString(new java.util.Random().nextInt(0xFFFF));
            p.edit().putString("dlnaUuid", u).apply();
        }
        return u;
    }

    /** v3.0.0 — the DlnaEndpoint bridge: DLNA actions drive the SAME engine the
     *  AnantPlay mDNS cast uses (/play → PlayActivity, /do, /status snapshot). */
    private class DlnaBridge implements DlnaEndpoint.Player {
        @Override public boolean open(String url, String title) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("url", url);
            if (title != null && !title.isEmpty()) body.put("title", title);
            String res = onPlay(body, "dlna:" + url);
            return res != null && res.contains("\"ok\":true");
        }
        @Override public String act(String action, long toMs) { return onAction(action, toMs); }
        @Override public String statusJson() { return status(); }
        @Override public int getVolume() { return dlnaVolume; }
        @Override public void setVolume(int v) {
            dlnaVolume = DlnaEndpoint.clampVolume(v);
            // bridge to the real media volume as a percentage of STREAM_MUSIC
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(dlnaVolume / 100f * max), 0);
            } catch (Throwable t) {
                Diag.w("dlna", "volume bridge: " + t);
            }
        }
    }

    /** Swap the persistent notification's text without re-posting the foreground service. */
    private void postState(int textRes) {
        try {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm == null) return;
            Notification.Builder b = new Notification.Builder(this, App.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle(getString(textRes))
                .setOngoing(true)
                .setShowWhen(false);
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            nm.notify(1, b.build());
        } catch (Throwable ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        // v3.4.6.h — THE RE-ATTACH LEG: a start delivered to an instance whose
        // onCreate() will not run again (stopSelf still in flight) must re-arm
        // here, or the receiver stays dead with running == null (the live
        // Android 36 defect behind probes 9/10). armCore() is idempotent.
        if (!ACTION_STOP.equals(a) && !armedAndServing()) {
            restoreUserStop(this);
            armCore();
        }
        if (ACTION_STOP.equals(a)) {
            Diag.i("service", "stop by user");
            setUserStop(this, true);   // the notification Stop is also explicit
            // v3.4.6.e — REMOVE the foreground notification (the old bare
            // stopSelf() left the persistent "ready for cast" notification
            // alive after the receiver was down — the ghost a user reads as
            // "the stop did nothing").
            try {
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } catch (Throwable ignored) { }
            if (running == this) running = null;   // v3.4.6.h — the honest state NOW
            stopSelf();
            return START_NOT_STICKY;
        }
        // v3.1.0 — the Cast & Network settings page pokes this after a change
        // (receiver name / DLNA / mDNS toggles): a clean stop→arm re-reads the
        // prefs without a full process restart.
        if (ACTION_APPLY_PREFS.equals(a)) {
            Diag.i("service", "apply prefs — re-arming");
            stopSelf();
            android.os.SystemClock.sleep(200);
            // v3.4.6.f — a settings change must not resurrect a stopped receiver.
            if (!userStop) arm(this, "apply-prefs");
            else Diag.i("service", "apply-prefs: standing user stop honored");
            return START_NOT_STICKY;
        }
        if (ACTION_TEST.equals(a)) {
            try {
                startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("runTest", true));
            } catch (Throwable t) {
                Diag.w("service", "test launch: " + t);
            }
        }
        // keep alive through task removal: the receiver must survive AnantPlay swipes
        return START_STICKY;
    }

    private void startInForeground() {
        Notification n;
        try {
            PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent test = PendingIntent.getService(this, 2,
                new Intent(this, ReceiverService.class).setAction(ACTION_TEST), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, ReceiverService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(this, App.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_test), test).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build());
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            n = b.build();
        } catch (Throwable t) {
            Diag.w("service", "notification: " + t);
            n = new Notification.Builder(this, App.CHANNEL).setSmallIcon(R.drawable.ic_stat_si).build();
        }
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(1, n);
        } catch (Throwable t) {
            // The connectedDevice type is what AnantPlay itself uses; if a vendor build
            // refuses it, fall back to the untyped call (still a foreground service).
            Diag.w("service", "typed startForeground failed, falling back: " + t);
            try { startForeground(1, n); } catch (Throwable t2) { Diag.e("service", "startForeground: " + t2); }
        }
    }

    @Override
    public void onDestroy() {
        running = null;
        heal.removeCallbacks(healTask);
        if (dlnaRenderer != null) { try { dlnaRenderer.stop(); } catch (Throwable ignored) { } dlnaRenderer = null; }
        if (nsd != null) nsd.stop();
        if (server != null) server.stop();
        try { stopForeground(true); } catch (Throwable ignored) { }   // no ghost notification
        Diag.i("service", "down");
        super.onDestroy();
        // v3.4.6.e — THE INSTANT STATUS FLIP: the Home hero card re-renders the
        // MOMENT the receiver is down (no 400 ms race with a postDelayed push).
        // MainActivity.pushCastStatus is idempotent + null-sink-safe; a static
        // forwarder keeps this file free of the activity reference.
        try { MainActivity.notifyReceiverDown(); } catch (Throwable ignored) { }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Diag.i("service", "task removed — staying up (receiver)");
    }

    // ---- ReceiverServer.Backend ----

    // ── v3.4.6.g — THE CAST AUTO-OPEN CONTRACT ──────────────────────────────
    // The real-phone timeline (3/3 casts missed for 5.18 s each) showed the
    // watchdog waiting on PlayActivity.current()/bridgeSessionLive(), both
    // of which flip only AFTER the whole engine warms up — while the launch
    // itself was being BAL-blocked. markPlayerOpened() is now signalled THE
    // INSTANT the bridge session opens (Media3TextureBridge.open →
    // adoptBridgeSession + markPlayerOpened), so the 5 s watchdog knows the
    // open is in flight even mid-warm-up, and onPlay launches through the
    // BAL-ALLOWED PendingIntent lane (MainActivity.shellRoute).
    private static volatile String playerOpenedSession;
    private static volatile long playerOpenedAtMs;

    /** Signalled the instant the in-shell player opens playback (never a lie). */
    static void markPlayerOpened(String sessionId) {
        playerOpenedSession = sessionId == null ? "" : sessionId;
        playerOpenedAtMs = android.os.SystemClock.uptimeMillis();
    }

    /** True when a bridge session signalled open during THIS cast window. */
    static boolean playerOpenedSignalled() { return playerOpenedSession != null; }

    /** The /play wait loop's full open contract (bridge session OR signal). */
    private static boolean playerOpenObserved() {
        return PlayActivity.current() != null || PlayActivity.bridgeSessionLive()
                || playerOpenedSignalled();
    }

    /** Reset the open signal at the START of a cast (a fresh 5 s window). */
    private static void resetPlayerOpenSignal() { playerOpenedSession = null; }

    /** /play: open our player, wait for it (Cast.play's pattern, 5 s), answer. */
    @Override
    public String onPlay(Map<String, Object> body, String sourceAddr) {
        String url = MiniJson.str(body, "url");
        String title = MiniJson.str(body, "title");
        Diag.i("play", "from " + sourceAddr + " url=" + url + " title=" + title);
        boolean media = PlayActivity.canReadSharedMedia(this);
        if (!CastPolicy.allowed(url, media)) {
            if (CastPolicy.allowedScheme(url)) {   // file:// cast we may not read yet
                Diag.w("play", "file cast without media permission");
                Troubleshooter.record("CAST", "/play refused — file cast without media permission (notification posted)");
                PlayActivity.notifyNeedsMedia(this, title);
            } else {
                Diag.w("play", "refused link: " + url);
                Troubleshooter.record("CAST", "/play refused — bad link scheme");
            }
            return ReceiverServer.whyJson(CastPolicy.rejectWhy(url));   // Cast.play's wording
        }
        Troubleshooter.record("CAST", "/play accepted · opening the Sinhala player");

        // v3.4.6.g — a FRESH 5 s window per cast: the previous session's open
        // signal must never satisfy THIS cast's watchdog (the 3/3 timeline's
        // "did not open despite the overlay grant" was the launch being
        // BAL-blocked, not a slow player).
        resetPlayerOpenSignal();
        String castJson = MiniJson.write(body);
        // ══ v3.4.6.k — THE DLNA-CAST SHOWSTOPPER FIX (Part_05 02:06–02:12) ══
        // v3.4.6.j routed /play through PlayActivity.launchNative()'s PLAIN
        // startActivity() on this (HTTP worker) thread. Backgrounded, Android
        // 14–16 silently drops that, and the v3.4.6.d-era 800 ms takeover then
        // brought the FLUTTER SHELL (MainActivity = HomeScreen) to the front on
        // top of the blank player — the user saw Home, never the movie. The
        // cast now rides the BAL-ALLOWED PendingIntent lane targeting
        // PLAYACTIVITY itself (MODE_BACKGROUND_ACTIVITY_START_ALLOWED on API
        // 34+, NEW_TASK | SINGLE_TOP | CLEAR_TOP | REORDER_TO_FRONT).
        boolean overlay = Settings.canDrawOverlays(this);
        boolean opened = false;
        try {
            Map<String, Object> m = Cue.statusOf(castJson);
            String castUrl = MiniJson.str(m, "url");
            String castTitle = MiniJson.str(m, "title");
            if (castTitle == null || castTitle.isEmpty()) castTitle = MiniJson.str(m, "pageTitle");
            String headersJson = MiniJson.str(m, "headers");
            if (castUrl == null || castUrl.isEmpty()) castUrl = url;
            if (castUrl == null) castUrl = "";
            PlayActivity.launchBal(this, castUrl,
                castTitle == null ? (title == null ? "" : title) : castTitle,
                headersJson == null ? "" : headersJson, false, 0L);
            opened = true;
            Diag.i("play", "BAL-allowed launch into the native cinema player");
        } catch (Throwable t) {
            Diag.w("play", "bal native launch: " + t);
        }
        if (!opened) {   // the BAL lane refused: the classic direct lane, then the tap notification
            try {
                PlayActivity.trampolineToShell(this, url == null ? "" : url,
                    title == null ? "" : title, "", false);
            } catch (Throwable t) {
                Diag.w("play", "direct native launch: " + t);
            }
        }
        // The wait loop still proves the player session took the screen (the
        // shell's PlayerScreen claims PlayActivity's /status mirror through
        // adoptBridgeSession, so current() answers from the bridge session).
        int waits = overlay ? 50 : 12;
        for (int n = 0; n < waits; n++) {           // Cast.play's wait loop (Cast.java:171-176)
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            if (playerOpenObserved()) {
                Diag.i("play", "player opened" + (overlay ? "" : " (no overlay perm — it worked anyway)"));
                Troubleshooter.record("CAST", "player opened ✓" + (overlay ? "" : " (no overlay perm)"));
                if (overlay) scheduleTakeover();
                return ReceiverServer.okJson();
            }
        }
        if (!overlay) {
            Diag.i("play", "no overlay permission — posting the tap-to-open cast notification");
            Troubleshooter.record("CAST", "no overlay permission — tap-to-open notification posted");
            PlayActivity.notifyCastReady(this, title, castJson);
            return ReceiverServer.okJson();       // cast accepted: tap the notification to watch
        }
        Diag.e("play", "player did not open despite the overlay grant");
        Troubleshooter.record("CAST", "player did not open despite the overlay grant — notification posted");
        PlayActivity.notifyCastReady(this, title, castJson);   // last resort, still recoverable
        return ReceiverServer.whyJson("did not open");
    }

    // v3.4.6.g — the takeover also rides the BAL-ALLOWED lane: after a cast,
    // AnantPlay's Remote may be in front, which backgrounds us and a plain
    // startActivity() is then silently dropped by Android 14–16.
    private void launchShellBal(String why) {
        try {
            MainActivity.shellLaunchBal(this, why);
        } catch (Throwable t) {
            Diag.w("takeover", "bal launch: " + t);
        }
    }

    /** After a successful cast AnantPlay opens its Remote on top; ~0.8 s later we bring
     *  our player back to the front (the user's intent was to WATCH here). The Remote
     *  keeps working — Cast.Remote polls only while resumed, and on our finish it
     *  sees {"idle":true} and hands the position back to AnantPlay's own player. */
    private void scheduleTakeover() {
        // v3.4.3 — Replit QA-0024: ONE takeover per 5 s. A cast retry (or a Remote the
        // user closed on purpose) must never re-steal the screen in a launch loop.
        long now = android.os.SystemClock.uptimeMillis();
        if (!CastPolicy.takeoverAllowed(now, lastTakeoverAtMs)) {
            Diag.i("takeover", "skipped — another takeover was less than "
                + CastPolicy.TAKEOVER_MIN_INTERVAL_MS + " ms ago (cast retry)");
            Troubleshooter.record("CAST", "takeover skipped (retry within "
                + CastPolicy.TAKEOVER_MIN_INTERVAL_MS + " ms)");
            return;
        }
        lastTakeoverAtMs = now;
        main.postDelayed(() -> {
            try {
                // ══ v3.4.6.k — THE TAKEOVER TARGETS THE NATIVE PLAYER ═════════
                // v3.4.6.d brought the FLUTTER SHELL (MainActivity) forward —
                // right when the player lived INSIDE the shell, WRONG since
                // v3.4.6.j: it opened HomeScreen ON TOP of the just-launched
                // PlayActivity (the Part_05 02:06 DLNA-cast defect). The
                // takeover now reorders PLAYACTIVITY itself forward through
                // the BAL-allowed lane; the shell stays paused at 0% GPU. Only
                // when PlayActivity has died does it fall back to the shell.
                if (PlayActivity.current() != null) {
                    Diag.i("takeover", "bringing the native cinema player to the front");
                    PlayerTakeover.launchBal(ReceiverService.this);
                    return;
                }
                Diag.i("takeover", "no live player — bringing the shell forward");
                launchShellBal("takeover");
            } catch (Throwable t) {
                Diag.w("takeover", "not allowed now: " + t + " — the user can tap the notification");
            }
        }, 800);
    }

    /** v3.4.6.k — the BAL-allowed bring-forward of the LIVE native player. */
    static class PlayerTakeover {
        static void launchBal(android.content.Context c) {
            try {
                android.content.Intent i = new android.content.Intent(c, PlayActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        c, 0x6B71, i,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE,
                        MainActivity.balAllowedOptions());
                pi.send();
            } catch (Throwable t) {
                Diag.w("takeover", "player bal: " + t);
            }
        }
    }

    /** /do — the player actions on the main thread (Cast.onMain's FutureTask), volume direct. */
    @Override
    public String onAction(String action, long to) {
        if (!CastPolicy.isAllowedAction(action)) return "{\"ok\":false}";
        if ("vol+".equals(action) || "vol-".equals(action)) {
            try {
                ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    "vol+".equals(action) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            } catch (Throwable t) {
                Diag.w("do", "volume: " + t);
            }
            return PlayActivity.statusSnapshot();
        }
        PlayActivity p = PlayActivity.current();
        if (p == null) return ReceiverServer.idleJson();
        try {
            FutureTask<String> f = new FutureTask<>(() -> p.castAction(action, to));
            main.post(f);
            return f.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            Diag.w("do", action + ": " + e);
            return p.statusJson();
        }
    }

    /** /status — the never-blocking snapshot path. */
    @Override
    public String status() {
        return PlayActivity.statusSnapshot();
    }

    /** /info extras. */
    @Override
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("announced", nsd != null && nsd.isAnnounced());
        MdnsResponder rsp = nsd == null ? null : nsd.responder();
        m.put("mdnsResponder", rsp == null ? "down" : rsp.isPortShared() ? "up(5353)" : "degraded(mdnsd-only)");
        m.put("mdnsAnswered", rsp == null ? 0 : rsp.answeredCount());
        m.put("dlnaRenderer", dlnaRenderer == null ? "disabled (Settings)" : dlnaRenderer.isRunning() ? "up(1900)" : "bind-failed");
        m.put("dlnaSearches", dlnaRenderer == null ? 0 : dlnaRenderer.searchesSeen());
        m.put("mdnsAnnouncer", nsd == null ? "disabled (Settings)" : "on");
        m.put("receiverName", AppPrefs.receiverName(getSharedPreferences("sicast", MODE_PRIVATE)));
        m.put("fgsType", "connectedDevice");
        m.put("notifPerm", Miui.notifGranted(this));
        m.put("castPolicy", "local-device-only; http/https + permitted file://");
        m.put("playerOpen", PlayActivity.current() != null);
        m.put("overlayPerm", Settings.canDrawOverlays(this));
        m.put("batteryUnrestricted", ((android.os.PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()));
        m.put("wifi", NsdAnnouncer.wifiLanIp() != null);
        m.put("xiaomi", isXiaomi());
        return m;
    }

    static boolean isXiaomi() {
        String m = Build.MANUFACTURER;
        return m != null && (m.equalsIgnoreCase("xiaomi") || m.equalsIgnoreCase("redmi"));
    }
}
