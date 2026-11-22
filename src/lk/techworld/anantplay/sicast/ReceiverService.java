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

    private static volatile ReceiverService running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ReceiverServer server;
    private NsdAnnouncer nsd;
    private DlnaEndpoint dlnaEndpoint;
    private DlnaRenderer dlnaRenderer;
    /** The DLNA RenderingControl's tracked volume (AnantPlay's remote ±5 steps it). */
    private volatile int dlnaVolume = 50;
    private volatile boolean nexusTakeover;   // bring our player over AnantPlay's Remote

    public static boolean isUp() { return running != null; }
    public static int port() { ReceiverService s = running; return s == null ? -1 : (s.server == null ? -1 : s.server.port()); }
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

    /** Best-effort arm from any entry point (app start, boot, tile). */
    static void arm(Context c, String why) {
        Diag.i("service", "arm (" + why + ") from " + c.getClass().getSimpleName());
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

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        // FIRST: satisfy the Android 12+/14+ foreground-service contract inside the 5 s
        // window. Nothing below may delay this — not port scanning, not mDNS.
        startInForeground();
        server = new ReceiverServer(this);
        if (!server.start()) {
            Diag.e("service", "could not bind any port — receiver NOT armed");
            postState(R.string.notif_failed);
            stopSelf();
            return;
        }
        // v3.1.0 (Bug 1) — prime the local-address snapshot the moment the server
        // exists: isSelf() then accepts our own wlan0 IP even if a LATER HyperOS
        // enumeration transiently fails (the 2026-11-22 refusal scenario).
        server.setLocalSnapshot(LocalNet.enumerateOwn());
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
        if (ACTION_STOP.equals(a)) {
            Diag.i("service", "stop by user");
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
            arm(this, "apply-prefs");
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
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Diag.i("service", "task removed — staying up (receiver)");
    }

    // ---- ReceiverServer.Backend ----

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

        String castJson = MiniJson.write(body);
        Intent i = new Intent(this, PlayActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("castJson", castJson);
        boolean overlay = Settings.canDrawOverlays(this);
        try {
            startActivity(i);   // works with the overlay grant; blocked (silently) without it
        } catch (Throwable t) {
            Diag.w("play", "startActivity: " + t);
            Troubleshooter.record("CAST", "/play launch failed: " + t + " — falling back to the notification");
        }
        // Without the overlay grant Android 10+ refuses the background activity launch, so
        // wait a shorter window and then hand the user a tap-to-open notification. The cast
        // still SUCCEEDS (ok:true) — AnantPlay keeps its Remote and nothing crashes.
        int waits = overlay ? 50 : 12;
        for (int n = 0; n < waits; n++) {           // Cast.play's wait loop (Cast.java:171-176)
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            if (PlayActivity.current() != null) {
                Diag.i("play", "player opened" + (overlay ? "" : " (no overlay perm — it worked anyway)"));
                Troubleshooter.record("CAST", "player opened ✓" + (overlay ? "" : " (no overlay perm)"));
                if (overlay) scheduleTakeover();  // hand the screen from AnantPlay's Remote to our player
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

    /** After a successful cast AnantPlay opens its Remote on top; ~0.8 s later we bring
     *  our player back to the front (the user's intent was to WATCH here). The Remote
     *  keeps working — Cast.Remote polls only while resumed, and on our finish it
     *  sees {"idle":true} and hands the position back to AnantPlay's own player. */
    private void scheduleTakeover() {
        main.postDelayed(() -> {
            try {
                PlayActivity p = PlayActivity.current();
                if (p == null) return;
                Diag.i("takeover", "bringing the Sinhala player to the front");
                startActivity(new Intent(ReceiverService.this, PlayActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Throwable t) {
                Diag.w("takeover", "not allowed now: " + t + " — the user can tap the notification");
            }
        }, 800);
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
