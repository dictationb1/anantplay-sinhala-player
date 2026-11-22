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
import java.util.Locale;

/* The Xiaomi HyperOS guided setup + live diagnostics — every requirement shows its
 * real state with a one-tap jump to the exact screen that fixes it. Nothing here can
 * die silently: the receiver's whole life is in the Diagnostics log below, and the
 * Self-test exercises the actual end-to-end chain (HTTP loopback → same-device mDNS
 * discovery → the Cloudflare bridge) on the user's own phone.
 *
 * v2.1 (this upgrade) — the UX overhaul for real HyperOS phones:
 *   · THE INVISIBLE-TITLES BUG: permRow() built the title TextView but never called
 *     setText(title) — the permission cards showed only "✓ granted" with a BLANK
 *     title. Fixed: every card has a bold bilingual title + a one-line WHY.
 *   · EXPLICIT HIGH-CONTRAST COLORS on EVERY text: titles #F1F5F9, descriptions
 *     #94A3B8, granted #22C55E, warning #F59E0B, error #EF4444 — MIUI Force Dark
 *     Mode can no longer eat a single label (styles.xml also sets
 *     android:forceDarkAllowed=false on the theme).
 *   · BOTTOM GESTURE-BAR CLIPPING: the ScrollView now pads by the real navigation
 *     bar insets (WindowInsets) so "Play URL in Sinhala Player" is never cut off.
 *   · CLEAR BILINGUAL HELPERS under every setting: Worker URL, Auto-fetch subs,
 *     AdGuard/VPN card (the 2-step exclusion), Self-test, Test player.
 *   · SELF-TEST v2: mDNS is proven by the RAW-SOCKET PROBE (NsdAnnouncer.probeSelf)
 *     + legacy discovery, and a failure now says WHY — Wi-Fi off vs router/VPN
 *     blocking multicast vs filtered — in Sinhala + English with the exact fix. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout checkList;
    private TextView selfTestLine, logView;
    private final List<Runnable> refreshers = new ArrayList<>();

    /** READ_MEDIA_VIDEO on 33+, READ_EXTERNAL_STORAGE below — only used for file:// casts. */
    private static final String MEDIA_PERM = Build.VERSION.SDK_INT >= 33
        ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";

    // ── the v2.1 palette: explicit, never inherited from a theme ──────────────
    static final int COL_TITLE = 0xFFF1F5F9;     // card/section titles
    static final int COL_TEXT = 0xFFE2E8F0;      // body text + buttons
    static final int COL_DESC = 0xFF94A3B8;      // one-line explanations
    static final int COL_OK = 0xFF22C55E;        // granted / pass
    static final int COL_WARN = 0xFFF59E0B;      // recommended
    static final int COL_ERR = 0xFFEF4444;       // failed / error
    static final int COL_ACCENT = 0xFF7FE7FF;    // self-test line

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
        // v2.3.1 — THE RIGHT-EDGE CLIPPING FIX: the default scrollbar style is
        // INSIDE_OVERLAY, and on HyperOS/MIUI the scrollbar is a fat translucent
        // band drawn OVER the content — the screenshot showed every long line of
        // the update card sliced at the same x ("GitHub Relea", "…whe",
        // "…automatical"). INSIDE_INSET reserves layout space for the bar instead
        // of letting it cover text, and right padding gives text a clear gutter.
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        scroll.setVerticalFadingEdgeEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(24));
        root.setBackgroundColor(0xFF0B1020);
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));
        // THE GESTURE-BAR FIX: pad the scroll content by the real bottom insets so
        // the last button is never half-swallowed by Xiaomi's navigation bar.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            int top = insets.getSystemWindowInsetTop();
            v.setPadding(v.getPaddingLeft(), top + dp(4), v.getPaddingRight(), bottom + dp(16));
            return insets;
        });
        scroll.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        scroll.setFitsSystemWindows(false);

        TextView h = head("AnantPlay · සිංහල Player", 21);
        root.addView(h, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        root.addView(cap("THE ZERO-ACCESSIBILITY PATH: this app is an AnantPlay CAST RECEIVER. "
            + "Official AnantPlay stays untouched — inside its player tap the Cast button and pick "
            + "\"සිංහල Player - AnantPlay (Recommended)\". The movie then plays HERE with native, "
            + "frame-exact Sinhala subtitles. No Accessibility permission is ever requested."));
        root.addView(cap("මෙය AnantPlay CAST RECEIVER එකකි — AnantPlay එක තුළ Cast ඔබා \"සිංහල Player\" "
            + "තෝරන්න. චිත්‍රපටිය මෙතැනින් විවෘත වී සිංහල උපසිරැසි සමඟ පානවා."));

        // ── what AnantPlay will see ──
        root.addView(head("Cast status · Cast තත්ත්වය", 16));
        checkList = new LinearLayout(this);
        checkList.setOrientation(LinearLayout.VERTICAL);
        root.addView(checkList, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width

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

        // ── Xiaomi permission wizard (v2.1: real titles + WHY + status badge) ──
        root.addView(head("Xiaomi HyperOS permissions · MIUI අවසර", 16));
        if (!Miui.isXiaomi()) root.addView(cap("Not a Xiaomi device — the battery and overlay toggles still apply."));
        LinearLayout pm = new LinearLayout(this);
        pm.setOrientation(LinearLayout.VERTICAL);
        root.addView(pm, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width

        final int iAuto = permRow(pm,
            "1. Autostart · ස්වයංක්‍රීය ආරම්භය",
            "MIUI පමණි — boot වූ විට receiver එක ස්වයංක්‍රීයව ඇරෙන්නට (Other permissions තුළත් තිබේ) · MIUI only — lets the receiver auto-start at boot",
            Miui.isXiaomi(), v -> Miui.openAutostart(this), v -> Miui.openOtherPermissions(this));
        final int iBatt = permRow(pm,
            "2. Battery saver → No restrictions · බැටරි සීමා නැති",
            "powerkeeper එක idle receiver එක මරා නොදමන්නට · stops MIUI's powerkeeper from killing the idle receiver",
            Miui.batteryUnrestricted(this), v -> Miui.requestBattery(this), null);
        final int iOverlay = permRow(pm,
            "3. Display over other apps · වෙනත් ඇප් මත පෙන්වීම",
            "Cast ආව විගස චිත්‍රපටය ස්වයංක්‍රීයව තිරයට ගෙන ඒමට · one-tap takeover when the cast arrives; without it the cast still lands as a tap-to-open notification",
            Miui.overlayGranted(this), v -> Miui.openOverlay(this), null);
        final int iNotif = permRow(pm,
            "4. Notifications · දැනුම්දීම්",
            "receiver එකේ status + cast දැනුම්දීම් (Android 13+ runtime) · the receiver's status + cast prompts (Android 13+ runtime permission)",
            Miui.notifGranted(this), v -> {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
                else Miui.openAppDetails(this);
            }, null);
        final int iMedia = permRow(pm,
            "5. Videos access · වීඩියෝ ප්‍රවේශය",
            "බාගත කළ වීඩියෝ cast සඳහා පමණි (file://) · only needed for file:// casts of AnantPlay-downloaded videos",
            mediaGranted(), v -> requestMediaPermission(), null);
        refreshers.add(() -> {
            bind(iAuto, Miui.isXiaomi(), Miui.isXiaomi() ? "✓ MIUI/HyperOS — open Autostart + Other permissions" : "— not needed on stock Android");
            bind(iBatt, Miui.batteryUnrestricted(this), Miui.batteryUnrestricted(this) ? "✓ No restrictions" : "✗ restricted");
            bind(iOverlay, Miui.overlayGranted(this), Miui.overlayGranted(this) ? "✓ granted" : "✗ not granted — casts arrive as a tap-to-open notification (still works)");
            bind(iNotif, Miui.notifGranted(this), Miui.notifGranted(this) ? "✓ granted" : "✗ not granted — tap Open setting / ⋮ Allow restricted settings");
            bind(iMedia, mediaGranted(), mediaGranted() ? "✓ granted" : "— tap to allow (file:// casts only)");
        });

        // ── the AdGuard / VPN help card (v2.1) ──
        root.addView(head("AdGuard / VPN help · AdGuard උදව්", 16));
        root.addView(cap("• Private DNS (dns.adguard-dns.com / DoT 853) mDNS සඳහා බාධාවක් නොවේ — එය unicast DNS පමණක් සංකේතනය කරයි. "
            + "· Private DNS (DoT) does NOT affect casting: it only encrypts unicast DNS, never the UDP 5353 multicast mDNS uses."));
        root.addView(cap("• AdGuard ANDROID APP (local VPN) mDNS multicast බ්ලොක් කළ හැක. එවිට 2 පියවර: "
            + "(1) AdGuard → Settings → Network → \"AnantPlay සිංහල Player\" එකේ Route through AdGuard OFF කරන්න; "
            + "(2) නැතහොත් AdGuard → Protection method → \"Local VPN\" වෙනුවට \"Manual proxy/HTTPS filtering\" තෝරන්න. "
            + "· The AdGuard ANDROID APP (local VPN) can block mDNS multicast. Two-step fix: (1) AdGuard → Settings → Network → turn OFF \"Route through AdGuard\" for this app; "
            + "(2) or switch AdGuard's protection method from \"Local VPN\" to manual proxy mode."));

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
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        root.addView(cap("Self-test: loopback HTTP → mDNS (raw probe + discovery) → the Cloudflare bridge. "
            + "· තත්පර 10ක් පමණ ගත වේ — නොසඟවා ප්‍රතිඵලය පෙන්වයි."));

        Button player = btn(getString(R.string.btn_testplayer), true);
        player.setOnClickListener(v -> startActivity(new Intent(this, PlayActivity.class)
            .putExtra("test", true).putExtra("title", "Sinhala Player Test")));
        root.addView(player, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        root.addView(cap("Test player · පරීක්ෂණ වීඩියෝව — Sinhala rendering + cast protocol ඔබේම දුරකථනයේ පරීක්ෂා කරයි."));

        Button anant = btn(getString(R.string.btn_openanant), false);
        anant.setOnClickListener(v -> Miui.openAnantPlay(this));
        root.addView(anant, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        root.addView(cap("Open AnantPlay · AnantPlay විවෘත කරන්න — එහි player එකේ Cast බොත්තම ඔබන්න."));

        // ── v2.4.0: the exact Cast-icon guide (the Android 16 field question) ──
        root.addView(head("Where is the Cast button? · Cast බොත්තම කොහෙද?", 16));
        root.addView(cap("AnantPlay player එකේ පහළ chip පේළියේ (quality / Subs / servers අසල) \"⌐▷ Cast\" chip "
            + "එක පෙනේ — එය TV එකක් හමු වූ විටම පෙන්වයි. එය නොපෙනේ නම් = AnantPlay එකට අපේ receiver "
            + "එක තවම නොපෙනේ (Self-test එක run කරන්න). Chip එක ඔබා \"සිංහල Player - AnantPlay (Recommended)\" "
            + "තෝරන්න — වීඩියෝව මෙතැනින් විවෘත වේ. · The Cast chip appears in AnantPlay's player chip row "
            + "(next to quality/Subs/servers) as soon as ANY receiver is discoverable — it is hidden "
            + "while nothing is found. Tap it, pick \"සිංහල Player - AnantPlay (Recommended)\", and the "
            + "video opens here with Sinhala subtitles."));

        // ── v2.3.0: the GitHub update checker (the Obtainium loop, in-app) ──
        Button update = btn("⬆ Check for updates (GitHub)", false);
        update.setOnClickListener(v -> checkForUpdates());
        root.addView(update, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        root.addView(cap("Check for updates · යාවත්කාලීන පරීක්ෂාව — GitHub Release එකක් ඇත්නම් විවෘත කර පෙන්වයි "
            + "(Obtainium එකෙන් ස්වයංක්‍රීයවද ලැබේ). · Opens the latest GitHub Release when one is newer "
            + "than this build (Obtainium updates automatically too)."));

        // ── v2.3.0: the zero-mDNS fallback card (Mobile Data / hostile routers) ──
        root.addView(head("No-Wi-Fi fallback · Wi-Fi නැති විට", 16));
        root.addView(cap("• Mobile Data එකේ හෝ රවුටරය Cast බ්ලොක් කළත්: AnantPlay එකේ වීඩියෝවේ Share ⋮ → "
            + "\"Copy link\" ගෙන පහළ \"Play URL\" කොටුවට paste කරන්න — සිංහල උපසිරැසි සමඟ මෙතැනින්ම විවෘත වේ. "
            + "· On Mobile Data or a router that blocks casting: in AnantPlay use Share ⋮ → \"Copy link\" "
            + "on the video, then paste it into the \"Play URL\" box below — it opens here with Sinhala subs. "
            + "The cast receiver and the bridge both work on any network."));

        selfTestLine = new TextView(this);
        selfTestLine.setTextColor(COL_ACCENT);
        selfTestLine.setTextSize(13);
        selfTestLine.setPadding(0, dp(6), 0, dp(4));
        selfTestLine.setText(getString(R.string.selftest_idle));
        root.addView(selfTestLine, new LinearLayout.LayoutParams(-1, -2));

        // ── manual URL + settings (v2.1: helpers under every setting) ──
        root.addView(head("Worker & manual play · Worker සැකසුම්", 16));
        root.addView(cap("Worker URL · Cloudflare bridge ලිපිනය — සිංහල උපසිරැසි සොයන/පරිවර්තනය කරන සේවාදායකය. "
            + "· The Cloudflare bridge that finds/translates Sinhala subtitles. Default works as-is."));
        final EditText url = new EditText(this);
        url.setText(prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL));
        url.setTextSize(12.5f);
        url.setTextColor(COL_TEXT);
        url.setHintTextColor(COL_DESC);
        url.setBackground(rounded(0xFF0E1526));
        url.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(url, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
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
        root.addView(srow, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        root.addView(cap("Auto-fetch subs · ස්වයංක්‍රීය උපසිරැසි ලබාගැනීම — ON නම් video එක විවෘත වූ විගස "
            + "සිංහල උපසිරැසි සොයා/පරිවර්තනය කර පෙන්වයි. OFF නම් player එකේ \"සිං\" බොත්තමෙන් අතින් ලබාගන්න. "
            + "· ON: Sinhala subs are fetched/translated automatically as soon as a video opens. OFF: fetch them yourself from the player's \"සිං\" panel."));

        // ── v2.2: Default Subtitle Language (the 18-language registry) ──
        root.addView(cap("Default subtitle language · පෙරනිමි උපසිරැසි භාෂාව — වීඩියෝ විවෘත වූ විගස ලබාගන්න භාෂාව "
            + "(සිංහල පෙරනිමියයි; player එකේදී භාෂා chip එකකින් ඕනෑම වෙලාවක මාරු කළ හැක). "
            + "· The language fetched automatically when a video opens (Sinhala by default; switch any time from the player's language chips)."));
        final Button langBtn = btn(Langs.nativeOf(prefs.getString("defaultLang", Langs.DEFAULT)) + " · " + Langs.englishOf(prefs.getString("defaultLang", Langs.DEFAULT)), false);
        langBtn.setOnClickListener(v -> {
            String[] labels = new String[Langs.ALL.size()];
            for (int i = 0; i < Langs.ALL.size(); i++) {
                Langs.Lang l = Langs.ALL.get(i);
                labels[i] = l.nativeName + " (" + l.english + ")";
            }
            new android.app.AlertDialog.Builder(this)
                .setTitle("Default subtitle language · පෙරනිමි භාෂාව")
                .setItems(labels, (d, which) -> {
                    prefs.edit().putString("defaultLang", Langs.ALL.get(which).code).apply();
                    langBtn.setText(Langs.ALL.get(which).nativeName + " · " + Langs.ALL.get(which).english);
                    toast(Langs.ALL.get(which).nativeName + " ✓");
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        });
        root.addView(langBtn, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width

        final EditText play = new EditText(this);
        play.setHint("paste any stream URL to play here (m3u8/mpd/mp4)");
        play.setTextSize(12.5f);
        play.setTextColor(COL_TEXT);
        play.setHintTextColor(COL_DESC);
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
        root.addView(playBtn, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width

        // ── diagnostics ──
        root.addView(head("Diagnostics · Crash log", 16));
        Button copy = btn("📋 Copy diagnostics log", false);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", Diag.dump()));
            toast("copied ✓");
        });
        root.addView(copy, new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10.5f);
        logView.setTextColor(0xFF8FA5C8);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        // v2.3.1: inset scrollbar here too — the overlay band covered the log's
        // right edge exactly like the update card's clipped text.
        logView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
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
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COL_TITLE);
        t.setTextSize(15);
        row.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView d = new TextView(this);
        d.setTextColor(COL_DESC);
        d.setTextSize(12.5f);
        d.setPadding(dp(4), dp(2), 0, 0);
        row.addView(d, new LinearLayout.LayoutParams(-1, -2));
        checkList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        rowIcons.add(t);
        rowTexts.add(d);
    }

    private void bind(int i, boolean ok, String detail) {
        if (i >= rowIcons.size()) return;
        rowIcons.get(i).setText((ok ? "✅ " : "❌ ") + rowIcons.get(i).getText().toString().replace("✅ ", "").replace("❌ ", ""));
        rowTexts.get(i).setText(detail);
    }

    /** A permission card: BOLD bilingual title + WHY line + status badge + fix buttons.
     *  (v1 bug fixed: the title was never set — the cards showed blank titles.) */
    private int permRow(LinearLayout box, String title, String note, boolean ok,
                        View.OnClickListener fix, View.OnClickListener fix2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));   // v2.3.1: explicit width
        TextView t = new TextView(this);
        t.setText(title);                       // ← THE FIX: the title is actually set
        t.setTextColor(COL_TITLE);
        t.setTextSize(14.5f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView n = new TextView(this);
        n.setText(note);
        n.setTextColor(COL_DESC);
        n.setTextSize(11.5f);
        n.setPadding(dp(4), dp(1), 0, dp(4));
        row.addView(n, new LinearLayout.LayoutParams(-1, -2));
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

    // ── the self-test v2: loopback HTTP → mDNS (probe + discovery) → the bridge ─

    private String selfTestDiscoveryText() {
        Boolean d = NsdAnnouncerTest.result;
        if (d == null) return "… run the Self-test";
        return d ? "✓ our cast entry is discoverable from this phone — AnantPlay will list it"
                 : "✗ not seen — run the Self-test for the exact reason (Wi-Fi / router / VPN)";
    }

    /** v2.3.1 — the re-entry guard: v2.3.0 let every tap of the Self-test button
     *  spawn ANOTHER probe thread (more sockets, more storm — the field log's
     *  two simultaneous probe ports 41138 + 43882 were exactly this). */
    private final java.util.concurrent.atomic.AtomicBoolean selfTestRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    private void runSelfTest() {
        if (!selfTestRunning.compareAndSet(false, true)) {
            toast("Self-test already running — දැනට ක්‍රියාත්මකයි");
            return;
        }
        selfTestLine.setText("running…");
        Diag.i("selftest", "start");
        final StringBuilder report = new StringBuilder();
        final boolean[] pass = {true};

        new Thread(() -> {
            try {
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

            // 2. mDNS — v2.3.0 THREE honest layers, router-independent by design:
            //    L1 the DAEMON probe: a unicast QU question to the phone's own mdnsd
            //       (loopback + Wi-Fi IP). An answer proves the record is in the SAME
            //       daemon AnantPlay's NsdManager discovery reads — the real cast path.
            //    L2 the WIRE probe against OUR OWN responder (MdnsResponder): both
            //       sockets joined to 224.0.0.251, question + answer travel through
            //       the kernel's multicast loopback — 100% inside this phone, the old
            //       router's multicast filtering is irrelevant.
            //    L3 legacy NsdManager discoverServices (bonus: some OEM stacks allow
            //       same-uid discovery). PASS = L1 or L2 or L3.
            boolean daemonOk = NsdAnnouncer.probeDaemon(MainActivity.this, 2500);
            Diag.i("selftest", "mdns daemon probe (mdnsd unicast): " + daemonOk);

            // the live service's responder when it is up (what other apps actually
            // see); a TEMPORARY responder when the service is cold — the self-test
            // must stay runnable from a fresh app open either way.
            MdnsResponder responder = ReceiverService.liveResponder();
            boolean tempResponder = false;
            if (responder == null || !responder.isRunning()) {
                try {
                    java.net.InetAddress ip = NsdAnnouncer.wifiLanIp();
                    if (ip == null) ip = java.net.InetAddress.getByName("127.0.0.1");
                    int rport = ReceiverService.port();
                    responder = new MdnsResponder(NsdAnnouncer.TYPE, NsdAnnouncer.OLD_TYPE,
                        NsdAnnouncer.RESPONDER_NAME, rport > 0 ? rport : ReceiverServer.PREFERRED_PORT, ip);
                    if (responder.start()) tempResponder = true;
                    else responder = null;
                } catch (Throwable t) {
                    Diag.w("selftest", "temp responder: " + t);
                    responder = null;
                }
            }
            MdnsResponder.ProbeResult wire = MdnsResponder.probeLocal(responder, 2500);
            boolean wireOk = wire.answered;
            Diag.i("selftest", "mdns wire probe (own responder, kernel loopback): answered=" + wire.answered
                + " responder=" + wire.responderUp + " sent=" + wire.packetsSent + " recv=" + wire.packetsReceived
                + (wire.detail.isEmpty() ? "" : " — " + wire.detail));
            if (tempResponder && responder != null) { try { responder.stop(); } catch (Throwable ignored) { } }

            // 2c. legacy NsdManager discovery — a bonus when the OEM stack allows it
            boolean legacy = NsdAnnouncerTest.discoverSelf(MainActivity.this, 2000);
            Diag.i("selftest", "mdns legacy discovery: " + legacy);

            boolean mDnsOk = daemonOk || wireOk || legacy;
            NsdAnnouncerTest.result = mDnsOk;
            // v2.4.0 — the REGISTRATION truth, shown as its own signal: "announced"
            // is the real NsdManager onServiceRegistered state. The Android 16 log
            // had it false for 35 s while L1/L2 were green-ish — that divergence is
            // exactly the sabotage signature and must never be hidden again.
            boolean regOk = ReceiverService.isUp() && ReceiverService.isAnnounced();
            if (mDnsOk) {
                // v2.3.1: show EVERY layer's state (L1 daemon · L2 responder · L3 NSD),
                // never a bare aggregate — the field paradox was a red FAILED while
                // the responder was actively answering, and nobody could tell why.
                report.append("mDNS ok [")
                    .append(daemonOk ? "L1 daemon ✓" : "L1 daemon ✗").append(" · ")
                    .append(wireOk ? "L2 responder ✓" : "L2 responder ✗").append(" · ")
                    .append(legacy ? "L3 NSD ✓" : "L3 NSD ✗")
                    .append(regOk ? "" : " ⚠ daemon-announce pending").append("], ");
            } else {
                pass[0] = false;
                report.append("mDNS FAILED (no-answer), ");
                // THE WHY: a per-cause bilingual instruction line, appended to the report
                if (!NsdAnnouncer.onWifiLikeNetwork(MainActivity.this)) {
                    report.append("[Wi-Fi ක්‍රියාත්මක නැත — Wi-Fi on කර නැවත උත්සාහ කරන්න · Turn Wi-Fi on and retry] ");
                } else if (NsdAnnouncer.vpnActive(MainActivity.this)) {
                    report.append("[VPN (AdGuard app?) multicast බ්ලොක් කරයි — AdGuard → Settings → Network → මෙම ඇප් එකෙන් Route through AdGuard OFF කරන්න · "
                        + "A VPN (the AdGuard app?) is blocking multicast — AdGuard → Settings → Network → turn OFF Route-through-AdGuard for this app] ");
                } else {
                    report.append("[mdnsd පිළිතුරු නැත — නමුත් AnantPlay Cast තවමත් වැඩ කරයි: AnantPlay එක අපව mdnsd daemon හරහා සොයයි. "
                        + "AnantPlay එකේම Cast බොත්තමෙන් පරීක්ෂා කරන්න · the daemon did not answer — but AnantPlay casting still works: "
                        + "it discovers via the system mDNS daemon. Test from AnantPlay's own Cast button] ");
                }
            }

            // 3. the Cloudflare bridge — v4: the storage warning is surfaced too
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(
                    prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL) + "/health").openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                int code = c.getResponseCode();
                String body = code == 200 ? new String(readAll(c.getInputStream()), StandardCharsets.UTF_8) : "";
                c.disconnect();
                report.append("bridge ").append(code == 200 ? "ok" : "HTTP " + code).append(".");
                Diag.i("selftest", "bridge: " + code + " " + BridgeApi.brief(body, 200));
                if (body.contains("cache+memory")) {
                    report.append(" [⚠ Worker KV නැත (storage=cache+memory) — jobs isolate මාරුවේදී නැති වේ; "
                        + "wrangler kv namespace create SUBS_KV කර bind කරන්න · the worker has NO KV bound — jobs vanish on isolate restarts; bind SUBS_KV]");
                }
            } catch (Exception e) {
                report.append("bridge unreachable (").append(e.getMessage()).append(").");
                Diag.w("selftest", "bridge: " + e);
            }
            Diag.i("selftest", (pass[0] ? "PASS" : "FAIL") + " — " + report);
            final boolean p = pass[0];
            main.post(() -> {
                selfTestLine.setText((p ? "✓ " : "✗ ") + report);
                selfTestLine.setTextColor(p ? COL_OK : COL_ERR);
                refresh();
            });
            } finally {
                // v2.3.1: ALWAYS release the re-entry guard — even on an unexpected
                // exception the next tap must be able to run the Self-test again.
                selfTestRunning.set(false);
            }
        }, "si-selftest").start();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    // ── v2.3.0: the in-app GitHub update check (renders UpdateChecker.Result) ──

    private void checkForUpdates() {
        toast("…");
        new Thread(() -> {
            UpdateChecker.Result r = UpdateChecker.check();
            main.post(() -> {
                if (r.offline) {
                    android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this)
                        .setTitle("Update check · යාවත්කාලීන පරීක්ෂාව")
                        .setMessage((r.error == null || r.error.isEmpty() ? "unknown" : r.error)
                            + "\n\nනැවත උත්සාහ කරන්න · please retry")
                        .setPositiveButton(android.R.string.ok, null);
                    try { d.show(); } catch (Throwable ignored) { }
                    return;
                }
                if (!r.newer) {
                    toast("✓ අලුත්ම — v" + AppInfo.VERSION_NAME + " is the latest release");
                    return;
                }
                String msg = "🆕 v" + r.latest + " ඇතුළත් · a new release is available\n\n"
                    + (r.notes.isEmpty() ? "" : r.notes + "\n\n")
                    + "Download: AnantPlay-Sinhala-Player-v" + r.latest + ".apk\n"
                    + "Obtainium: app එක update කරන්නේ එකෙන්ම විවෘත වේ · Obtainium updates it in place.";
                android.app.AlertDialog.Builder d = new android.app.AlertDialog.Builder(this)
                    .setTitle("Update available · යාවත්කාලීන")
                    .setMessage(msg)
                    .setPositiveButton("Open release", (dd, w) -> {
                        try {
                            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(r.releaseUrl.isEmpty() ? UpdateChecker.REPO_URL + "/releases/latest" : r.releaseUrl)));
                        } catch (Throwable t) {
                            toast("browser එකක් නැත — " + UpdateChecker.REPO_URL);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null);
                try { d.show(); } catch (Throwable ignored) { }
            });
        }, "si-update").start();
    }

    // ── small helpers ──────────────────────────────────────────────────────────

    private TextView head(String t, float sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(COL_TITLE);
        v.setTextSize(sp);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setPadding(0, dp(14), 0, dp(4));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private TextView cap(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(COL_DESC);
        v.setTextSize(12.5f);
        v.setLineSpacing(dp(2), 1f);
        v.setPadding(0, dp(2), 0, dp(6));
        // v2.3.1: explicit MATCH_PARENT — never rely on generated defaults, so no
        // OEM layout pass can shrink a card narrower than the parent.
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private Button btn(String t, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t);
        b.setTextSize(13);
        b.setTextColor(COL_TEXT);
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

    /** Runs NSD discovery from THIS phone — the LEGACY self-test layer (v2). On
     *  HyperOS this can legitimately NOT see our own registration (the OEM daemon
     *  filters same-uid services), so it is a bonus signal only; the raw probe is
     *  the authoritative proof. */
    static final class NsdAnnouncerTest {
        static volatile Boolean result;                 // last self-test outcome (probe OR legacy)
        private static volatile Boolean lastKnown;      // announced + discovered at least once

        static boolean lastKnown() {
            Boolean r = result;
            return r != null && r;
        }

        static boolean discoverSelf(Context c, long timeoutMs) {
            try {
                final Object done = new Object();
                final boolean[] found = {false};
                android.net.nsd.NsdManager nsd = c.getSystemService(android.net.nsd.NsdManager.class);
                if (nsd == null) return false;
                android.net.nsd.NsdManager.DiscoveryListener l = new android.net.nsd.NsdManager.DiscoveryListener() {
                    @Override public void onServiceFound(android.net.nsd.NsdServiceInfo i) {
                        String n = i.getServiceName() == null ? "" : i.getServiceName();
                        Diag.i("nsdtest", "found: " + n);
                        if (n.contains(NsdAnnouncer.NAME)) found[0] = true;
                    }
                    @Override public void onServiceLost(android.net.nsd.NsdServiceInfo i) { }
                    @Override public void onDiscoveryStarted(String t) { }
                    @Override public void onDiscoveryStopped(String t) { }
                    @Override public void onStartDiscoveryFailed(String t, int e) { Diag.w("nsdtest", "start fail " + e); }
                    @Override public void onStopDiscoveryFailed(String t, int e) { }
                };
                nsd.discoverServices(NsdAnnouncer.TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l);
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
