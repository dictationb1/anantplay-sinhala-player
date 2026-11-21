package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/* The Xiaomi HyperOS guided setup + live diagnostics — every requirement shows its
 * real state with a one-tap jump to the exact screen that fixes it. Nothing here can
 * die silently: the receiver's whole life is in the Diagnostics log below, and the
 * Self-test exercises the actual end-to-end chain (HTTP loopback → same-device mDNS
 * discovery → the Cloudflare bridge) on the user's own phone. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout checkList;
    private TextView selfTestLine, logView;
    private final List<Runnable> refreshers = new ArrayList<>();

    /** READ_MEDIA_VIDEO on 33+, READ_EXTERNAL_STORAGE below — only used for file:// casts. */
    private static final String MEDIA_PERM = Build.VERSION.SDK_INT >= 33
        ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("sicast", MODE_PRIVATE);
        setContentView(buildUi());
        ReceiverService.arm(this, "main");
        // Android 13+ runtime notification permission (sideloaded apps need the
        // "Allow restricted settings" unlock first — step 1 of the checklist).
        // Without it the persistent receiver notification is invisible on HyperOS.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7); } catch (Throwable ignored) { }
        }
        if (getIntent().getBooleanExtra("needMedia", false)) requestMediaPermission();
        if (getIntent().getBooleanExtra("runTest", false)) runSelfTest();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        int granted = res != null && res.length > 0 ? res[0] : -1;
        Diag.i("perm", "request " + req + " (" + (perms != null && perms.length > 0 ? perms[0] : "?")
            + ") → " + (granted == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "denied"));
        if (req == 8 && granted == PackageManager.PERMISSION_GRANTED) {
            toast("වීඩියෝ අවසරය ලැබුණි ✓ — AnantPlay එකෙන් නැවත Cast කරන්න");
        }
        refresh();
    }

    private boolean mediaGranted() {
        try { return checkSelfPermission(MEDIA_PERM) == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    private void requestMediaPermission() {
        if (mediaGranted()) { toast("video access ✓"); return; }
        try { requestPermissions(new String[]{MEDIA_PERM}, 8); }
        catch (Throwable t) { Diag.w("perm", "media request: " + t); Miui.openAppDetails(this); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        main.postDelayed(this::refresh, 1200);   // let the receiver/nsd state settle
    }

    private void refresh() { for (Runnable r : refreshers) r.run(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ── UI ─────────────────────────────────────────────────────────────────────

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(24));
        root.setBackgroundColor(0xFF0B1020);
        scroll.addView(root);

        TextView h = head("AnantPlay · සිංහල Player", 21);
        root.addView(h);
        root.addView(cap("THE ZERO-ACCESSIBILITY PATH: this app is an AnantPlay CAST RECEIVER. "
            + "Official AnantPlay stays untouched — inside its player tap the Cast button and pick "
            + "\"සිංහල Player - AnantPlay (Recommended)\". The movie then plays HERE with native, "
            + "frame-exact Sinhala subtitles. No Accessibility permission is ever requested."));

        // ── what AnantPlay will see ──
        root.addView(head("Cast status · Cast තත්ත්වය", 16));
        checkList = new LinearLayout(this);
        checkList.setOrientation(LinearLayout.VERTICAL);
        root.addView(checkList);

        check(getString(R.string.chk_service), () -> ReceiverService.isUp()
                ? "✓ සක්‍රියයි · port " + ReceiverService.port() : "✗ නොක්‍රියයි",
            ReceiverService.isUp(), null);
        check(getString(R.string.chk_announced), () -> NsdAnnouncerTest.lastKnown()
                ? "✓ mDNS ප්‍රසිද්ධයි" : "… checking / not yet", false, null);
        check(getString(R.string.chk_discovery), () -> selfTestDiscoveryText(),
            NsdAnnouncerTest.lastKnown(), null);
        check(getString(R.string.chk_wifi), () -> NsdAnnouncer.onWifiLikeNetwork(this)
                ? "✓ Wi-Fi on (same-device cast needs it)" : "✗ Wi-Fi off — turn it on", 
            NsdAnnouncer.onWifiLikeNetwork(this), null);
        refreshers.add(() -> {
            bind(0, ReceiverService.isUp(), ReceiverService.isUp()
                ? "✓ සක්‍රියයි · port " + ReceiverService.port() : "✗ start it below");
            bind(1, NsdAnnouncerTest.lastKnown(), NsdAnnouncerTest.lastKnown()
                ? "✓ " + getString(R.string.announced_ok) : "… " + getString(R.string.announced_wait));
            bind(2, NsdAnnouncerTest.lastKnown(), selfTestDiscoveryText());
            bind(3, NsdAnnouncer.onWifiLikeNetwork(this), NsdAnnouncer.onWifiLikeNetwork(this)
                ? "✓ Wi-Fi on" : "✗ Wi-Fi off — turn it on");
        });

        // ── Xiaomi permission wizard ──
        root.addView(head("Xiaomi HyperOS permissions · MIUI අවසර", 16));
        if (!Miui.isXiaomi()) root.addView(cap("Not a Xiaomi device — the battery and overlay toggles still apply."));
        LinearLayout pm = new LinearLayout(this);
        pm.setOrientation(LinearLayout.VERTICAL);
        root.addView(pm);

        final int iAuto = permRow(pm, "Autostart", "MIUI only — best effort (also under Other permissions)",
            Miui.isXiaomi(), v -> Miui.openAutostart(this), v -> Miui.openOtherPermissions(this));
        final int iBatt = permRow(pm, "Battery saver → No restrictions", "stops powerkeeper from killing the idle receiver",
            Miui.batteryUnrestricted(this), v -> Miui.requestBattery(this), null);
        final int iOverlay = permRow(pm, "Display over other apps", "one-tap takeover when the cast arrives; WITHOUT it a cast still lands as a tap-to-open notification",
            Miui.overlayGranted(this), v -> Miui.openOverlay(this), null);
        final int iNotif = permRow(pm, "Notifications", "the receiver's status + cast prompts (Android 13+ runtime permission)",
            Miui.notifGranted(this), v -> {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
                else Miui.openAppDetails(this);
            }, null);
        final int iMedia = permRow(pm, "Videos access", "only needed for file:// casts of AnantPlay-downloaded videos",
            mediaGranted(), v -> requestMediaPermission(), null);
        refreshers.add(() -> {
            bind(iAuto, Miui.isXiaomi(), Miui.isXiaomi() ? "✓ MIUI/HyperOS — open Autostart + Other permissions" : "— not needed on stock Android");
            bind(iBatt, Miui.batteryUnrestricted(this), Miui.batteryUnrestricted(this) ? "✓ No restrictions" : "✗ restricted");
            bind(iOverlay, Miui.overlayGranted(this), Miui.overlayGranted(this) ? "✓ granted" : "✗ not granted — casts arrive as a tap-to-open notification (still works)");
            bind(iNotif, Miui.notifGranted(this), Miui.notifGranted(this) ? "✓ granted" : "✗ not granted — tap Open setting / ⋮ Allow restricted settings");
            bind(iMedia, mediaGranted(), mediaGranted() ? "✓ granted" : "— tap to allow (file:// casts only)");
        });

        // ── actions ──
        root.addView(head("Actions", 16));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button arm = btn(getString(R.string.btn_arm), true);
        arm.setOnClickListener(v -> { ReceiverService.arm(this, "user"); toast("arming…"); main.postDelayed(this::refresh, 800); });
        row.addView(arm, new LinearLayout.LayoutParams(0, -2, 1f));
        Button test = btn(getString(R.string.btn_selftest), false);
        test.setOnClickListener(v -> runSelfTest());
        row.addView(test, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(row);

        Button player = btn(getString(R.string.btn_testplayer), true);
        player.setOnClickListener(v -> startActivity(new Intent(this, PlayActivity.class)
            .putExtra("test", true).putExtra("title", "Sinhala Player Test")));
        root.addView(player);

        Button anant = btn(getString(R.string.btn_openanant), false);
        anant.setOnClickListener(v -> Miui.openAnantPlay(this));
        root.addView(anant);

        selfTestLine = new TextView(this);
        selfTestLine.setTextColor(0xFF7FE7FF);
        selfTestLine.setTextSize(13);
        selfTestLine.setPadding(0, dp(6), 0, dp(4));
        selfTestLine.setText(getString(R.string.selftest_idle));
        root.addView(selfTestLine);

        // ── manual URL + settings ──
        root.addView(head("Worker & manual play", 16));
        final EditText url = new EditText(this);
        url.setText(prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL));
        url.setTextSize(12.5f);
        url.setBackground(rounded(0xFF0E1526));
        url.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(url);
        LinearLayout srow = new LinearLayout(this);
        srow.setOrientation(LinearLayout.HORIZONTAL);
        Button save = btn(getString(R.string.btn_save), false);
        save.setOnClickListener(v -> {
            prefs.edit().putString("workerUrl", url.getText().toString().trim()).apply();
            toast("සුරැකිණි ✓");
        });
        srow.addView(save, new LinearLayout.LayoutParams(0, -2, 1f));
        Button auto = btn(getString(R.string.btn_autofetch, prefs.getBoolean("autoFetch", true)), false);
        auto.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("autoFetch", true);
            prefs.edit().putBoolean("autoFetch", on).apply();
            auto.setText(getString(R.string.btn_autofetch, on));
        });
        srow.addView(auto, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(srow);

        final EditText play = new EditText(this);
        play.setHint("paste any stream URL to play here (m3u8/mpd/mp4)");
        play.setTextSize(12.5f);
        play.setBackground(rounded(0xFF0E1526));
        play.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(play, new LinearLayout.LayoutParams(-1, -2));
        Button playBtn = btn("Play URL in Sinhala Player", false);
        playBtn.setOnClickListener(v -> {
            String u = play.getText().toString().trim();
            if (u.startsWith("http://") || u.startsWith("https://"))
                startActivity(new Intent(this, PlayActivity.class).putExtra("url", u));
            else toast("http(s) URL only");
        });
        root.addView(playBtn);

        // ── diagnostics ──
        root.addView(head("Diagnostics · Crash log", 16));
        Button copy = btn("📋 Copy diagnostics log", false);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", Diag.dump()));
            toast("copied ✓");
        });
        root.addView(copy);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10.5f);
        logView.setTextColor(0xFF8FA5C8);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        GradientDrawable lg = rounded(0xFF0E1526);
        logView.setBackground(lg);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(logView, new LinearLayout.LayoutParams(-1, dp(220)));
        refreshers.add(() -> logView.setText(tail(Diag.dump(), 120)));

        root.addView(cap(String.format(java.util.Locale.US,
            "Sinhala Player v%s · zero fork, zero accessibility · official AnantPlay (me.champdev.weblock) stays untouched.",
            AppInfo.VERSION_NAME)));
        return scroll;
    }

    // ── the check rows ─────────────────────────────────────────────────────────

    private final List<TextView> rowIcons = new ArrayList<>();
    private final List<TextView> rowTexts = new ArrayList<>();

    private void check(String title, java.util.function.Supplier<String> detail, boolean ok, Runnable fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        row.addView(t);
        TextView d = new TextView(this);
        d.setTextColor(0xFF9FB0D8);
        d.setTextSize(12.5f);
        d.setPadding(dp(4), dp(2), 0, 0);
        row.addView(d);
        checkList.addView(row);
        rowIcons.add(t);
        rowTexts.add(d);
    }

    private void bind(int i, boolean ok, String detail) {
        if (i >= rowIcons.size()) return;
        rowIcons.get(i).setText((ok ? "✅ " : "❌ ") + rowIcons.get(i).getText().toString().replace("✅ ", "").replace("❌ ", ""));
        rowTexts.get(i).setText(detail);
    }

    private int permRow(LinearLayout box, String title, String note, boolean ok,
                         View.OnClickListener fix, View.OnClickListener fix2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14.5f);
        row.addView(t);
        TextView n = new TextView(this);
        n.setText(note);
        n.setTextColor(0xFF7C8DB5);
        n.setTextSize(11.5f);
        n.setPadding(dp(4), dp(1), 0, dp(4));
        row.addView(n);
        rowIcons.add(t);
        rowTexts.add(n);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        if (fix != null) {
            Button b = btn("Open setting", false);
            b.setOnClickListener(fix);
            btns.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (fix2 != null) {
            Button b2 = btn("Other permissions", false);
            b2.setOnClickListener(fix2);
            btns.addView(b2, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        if (fix != null || fix2 != null) row.addView(btns);
        box.addView(row);
        return rowIcons.size() - 1;
    }

    // ── the self-test: loopback HTTP → same-device mDNS → the bridge ──────────

    private String selfTestDiscoveryText() {
        Boolean d = NsdAnnouncerTest.result;
        if (d == null) return "… run the Self-test";
        return d ? "✓ our cast entry is discoverable from this phone — AnantPlay will list it"
                 : "✗ not seen yet — Wi-Fi on? run again / see log";
    }

    private void runSelfTest() {
        selfTestLine.setText("running…");
        Diag.i("selftest", "start");
        final StringBuilder report = new StringBuilder();
        final boolean[] pass = {true};

        new Thread(() -> {
            // 1. loopback HTTP against our own receiver (the /play path AnantPlay uses)
            int port = ReceiverService.port();
            if (port < 0) { pass[0] = false; report.append("receiver not running. "); }
            else {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/info").openConnection();
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(1500);
                    String body = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
                    c.disconnect();
                    boolean ok = body.contains("\"ok\":true") && body.contains("Sinhala");
                    report.append(ok ? "loopback ok, " : "loopback BAD, ");
                    if (!ok) pass[0] = false;
                    Diag.i("selftest", "loopback: " + body);
                } catch (Exception e) {
                    pass[0] = false;
                    report.append("loopback failed (").append(e.getMessage()).append("), ");
                    Diag.w("selftest", "loopback: " + e);
                }
            }
            // 2. same-device mDNS discovery — the make-or-break of the cast path
            boolean discovered = NsdAnnouncerTest.discoverSelf(MainActivity.this, 6000);
            NsdAnnouncerTest.result = discovered;
            report.append(discovered ? "mDNS self-discovery ok, " : "mDNS self-discovery FAILED, ");
            if (!discovered) pass[0] = false;
            // 3. the Cloudflare bridge
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                    prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL) + "/health").openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                int code = c.getResponseCode();
                c.disconnect();
                report.append("bridge ").append(code == 200 ? "ok" : "HTTP " + code).append(".");
                Diag.i("selftest", "bridge: " + code);
            } catch (Exception e) {
                report.append("bridge unreachable (").append(e.getMessage()).append(").");
                Diag.w("selftest", "bridge: " + e);
            }
            Diag.i("selftest", (pass[0] ? "PASS" : "FAIL") + " — " + report);
            final boolean p = pass[0];
            main.post(() -> {
                selfTestLine.setText((p ? "✓ " : "✗ ") + report);
                selfTestLine.setTextColor(p ? 0xFF8CFF8C : 0xFFFF9F9F);
                refresh();
            });
        }, "si-selftest").start();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    // ── small helpers ──────────────────────────────────────────────────────────

    private TextView head(String t, float sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, dp(14), 0, dp(4));
        return v;
    }

    private TextView cap(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(0xFF9FB0D8);
        v.setTextSize(12.5f);
        v.setLineSpacing(dp(2), 1f);
        v.setPadding(0, dp(2), 0, dp(6));
        return v;
    }

    private Button btn(String t, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(primary ? 0xFF3B82F6 : 0xFF1A2440));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String tail(String s, int lines) {
        String[] l = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, l.length - lines); i < l.length; i++) sb.append(l[i]).append('\n');
        return sb.toString();
    }

    /** Runs NSD discovery from THIS phone to prove AnantPlay's own path end to end. */
    static final class NsdAnnouncerTest {
        static volatile Boolean result;                 // last self-test outcome
        private static volatile Boolean lastKnown;      // announced + discovered at least once

        static boolean lastKnown() {
            Boolean r = result;
            return r != null && r;
        }

        static boolean discoverSelf(Context c, long timeoutMs) {
            try {
                final Object done = new Object();
                final boolean[] found = {false};
                NsdManager nsd = c.getSystemService(NsdManager.class);
                if (nsd == null) return false;
                NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                    @Override public void onServiceFound(NsdServiceInfo i) {
                        String n = i.getServiceName() == null ? "" : i.getServiceName();
                        Diag.i("nsdtest", "found: " + n);
                        if (n.contains(NsdAnnouncer.NAME)) found[0] = true;
                    }
                    @Override public void onServiceLost(NsdServiceInfo i) { }
                    @Override public void onDiscoveryStarted(String t) { }
                    @Override public void onDiscoveryStopped(String t) { }
                    @Override public void onStartDiscoveryFailed(String t, int e) { Diag.w("nsdtest", "start fail " + e); }
                    @Override public void onStopDiscoveryFailed(String t, int e) { }
                };
                nsd.discoverServices(NsdAnnouncer.TYPE, NsdManager.PROTOCOL_DNS_SD, l);
                long end = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < end && !found[0]) Thread.sleep(150);
                try { nsd.stopServiceDiscovery(l); } catch (Throwable ignored) { }
                if (found[0]) lastKnown = true;
                return found[0];
            } catch (Throwable t) {
                Diag.w("nsdtest", "discover: " + t);
                return false;
            }
        }
    }
}
