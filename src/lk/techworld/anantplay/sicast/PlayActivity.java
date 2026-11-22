package lk.techworld.anantplay.sicast;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/* OUR OWN PLAYER — where the zero-accessibility architecture pays off completely:
 *
 * The video AnantPlay handed over via /play (same URL, same headers, same cookie —
 * Cast.describe's payload) plays in OUR ExoPlayer, the SAME media3 1.11.1 engine and
 * the same DataSource recipe official AnantPlay itself uses (PlayerActivity.java:
 * 1087-1140). Because the player is ours:
 *
 *   · Sinhala subtitles render from the worker's cue list against the player's OWN
 *     position — frame-exact by construction, zero clock drift, zero cue matching;
 *     ±0.25 s / ±1 s nudges are a pure display offset;
 *   · the Sinhala font (NotoSansSinhala), size, mask and position are ours to style;
 *   · AnantPlay's Remote (and its own player) keep working: /status, /do and the
 *     resume-on-stop loop are driven from the SAME timeline;
 *   · no AccessibilityService, no overlay permission for the video itself, and while
 *     a movie plays WE are the foreground app — the one state HyperOS never kills. */
@UnstableApi   // ExoPlayer's API surface (like AnantPlay's own PlayerActivity)
public class PlayActivity extends Activity {

    static final String TEST_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";

    // ── the cast protocol surface (read by ReceiverService, never null-safe blocks) ──
    private static volatile PlayActivity currentInstance;
    private static volatile String snapshot = ReceiverServer.idleJson();

    public static PlayActivity current() { return currentInstance; }
    /** /status — the never-blocking snapshot the player keeps fresh. */
    public static String statusSnapshot() { return snapshot; }

    public static boolean canReadSharedMedia(Context c) {
        String p = Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** A file:// cast arrived without the media permission: a one-tap notification. */
    public static void notifyNeedsMedia(Context c, String title) {
        post(c, 9, c.getString(R.string.need_media_title),
            c.getString(R.string.need_media_text, title == null ? "" : title),
            new Intent(c, MainActivity.class).putExtra("needMedia", true));
    }

    /**
     * A cast arrived but Android refuses our background activity launch (no overlay
     * permission — the XIAOMI default). The cast still succeeded: this high-priority
     * notification carries the whole payload and opens the player on one tap.
     */
    public static void notifyCastReady(Context c, String title, String castJson) {
        post(c, 10, c.getString(R.string.cast_ready_title, title == null ? "AnantPlay cast" : title),
            c.getString(R.string.cast_ready_text),
            new Intent(c, PlayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("castJson", castJson));
    }

    private static void post(Context c, int id, String title, String text, Intent target) {
        try {
            if (!Miui.notifGranted(c)) {          // user revoked notifications: nothing to show
                Diag.w("notify", "POST_NOTIFICATIONS not granted — prompt skipped");
                return;
            }
            PendingIntent open = PendingIntent.getActivity(c, id, target,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            Notification.Builder b = new Notification.Builder(c, App.CHANNEL_CAST)
                .setSmallIcon(R.drawable.ic_stat_si)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_EVENT)
                .setContentIntent(open)
                .setAutoCancel(true);
            nm.notify(id, b.build());
        } catch (Throwable t) {
            Diag.w("notify", "prompt failed: " + t);
        }
    }

    // ── instance state ──
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private SubtitleStore store;
    private BridgeApi api;

    private ExoPlayer player;
    private SurfaceView surface;
    private TextView subView;         // the Sinhala (or fallback English) line
    private TextView notice;          // transient toasts ("+10 s", "Brightness 40%")
    private TextView hudTitle, hudStatus;
    private TextView timeNow, timeTotal;
    private SeekBar seek;
    private View playPause;
    private View spinner;
    private LinearLayout hud, panel;
    private TextView errorBox;
    /** v2.2: the language chip row + register/Option-C row (shown with the pill). */
    private LinearLayout langChips;
    private LinearLayout extraActions;

    /** v2.1: the never-silent subtitle status pill + its 1-tap action row. */
    private TextView statusPill;
    private LinearLayout subActions;
    private Typeface siFont;

    private String url, title, headersJson, cookie, mime;
    private long startAt;
    private boolean live, testMode, dragging, dashFlag;
    private int season, episode;
    private String imdb;
    /** AnantPlay's cast payload `subs` URLs — side-loaded subtitle files the phone
     *  player already had (Cast.describe forwards every StringArrayList extra). */
    private java.util.ArrayList<String> castSubs;

    private volatile List<Cue> cues = new ArrayList<>();
    private volatile long subDelayMs;                  // the ±nudge display offset
    private volatile boolean fetching, enFallbackOn;
    private volatile int fetchGen;                     // a re-cast invalidates the in-flight fetch
    private String state = "idle";                     // idle|fetching|ready|local|not-found|needs-key|error
    private String stateDetail = "";
    /** v2.2: the active subtitle language (registry code, si default) + the
     *  register override (null = auto: the worker picks by movie genre). */
    private volatile String activeLang = Langs.DEFAULT;
    private volatile String registerOverride = null;
    /** v2.2 two-stage: set when a base-nmt .srt is showing and a silent cinema
     *  upgrade may still land (the delayed re-check hot-swaps it). */
    private volatile String polishWatchToken = null;
    private volatile boolean polishHotSwapped = false;
    private boolean hudVisible = true;
    private boolean finished;
    private volatile boolean expectTakeover = true;   // cleared ~3 s after open: PiP waits for the hand-over

    // ───────────────────────────────────────────────────────────────────────────
    // lifecycle
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        currentInstance = this;
        snapshot = ReceiverServer.loadingJson();
        prefs = getSharedPreferences("sicast", MODE_PRIVATE);
        // v2.2: the user's default subtitle language (si default)
        activeLang = Langs.isKnown(prefs.getString("defaultLang", null)) ? prefs.getString("defaultLang", Langs.DEFAULT) : Langs.DEFAULT;
        store = new SubtitleStore(getFilesDir());
        api = new BridgeApi(prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL));
        try {
            siFont = Typeface.createFromAsset(getAssets(), "fonts/NotoSansSinhala-Regular.ttf");
        } catch (Throwable t) {
            Diag.w("font", "asset font failed: " + t);
            siFont = Typeface.DEFAULT;
        }
        parseIntent();
        if (url == null) {          // nothing playable arrived — leave cleanly, no half UI
            finish();
            return;
        }
        buildUi();
        startPlayback();
        startTicker();
        // v3.0.0 — the never-silent launch pill: the user sees the subtitle
        // pipeline is ALIVE from the very first frame, before any network
        // round-trip completes ("✨ … ලබාගනිමින්…" → "✓ සූදානම්" once cues land).
        if (!testMode) setState("fetching", "✨ සිංහල උපසිරැසි ලබාගනිමින්… · fetching Sinhala subtitles…");
        main.postDelayed(() -> { expectTakeover = false; armAutoPip(); }, 3000);   // PiP only after the hand-over
        if (testMode || prefs.getBoolean("autoFetch", true)) fetchSinhala();
        Diag.i("player", "open: " + title + " · " + url);
    }

    private void parseIntent() {
        Intent i = getIntent();
        testMode = i.getBooleanExtra("test", false);
        title = i.getStringExtra("title");
        url = i.getStringExtra("url");
        if (testMode) {
            url = TEST_URL;
            if (title == null) title = "Sinhala Player Test";
            applyTestCues();
            return;
        }
        if (url != null) {   // manual play from MainActivity / the WebView sniffer
            title = title == null ? url : title;
            headersJson = i.getStringExtra("headers");   // v3.0.0: the sniffer's Referer/Cookie/UA
            return;
        }
        // v3.0.0 — share ANY video link straight into the Sinhala player:
        //   ACTION_VIEW (a http(s) URL opened with "සිංහල Player" from any
        //   browser's link sheet) and ACTION_SEND text/plain (AnantPlay's own
        //   Share ⋮ → "Copy link" or any app's share sheet). Both land here with
        //   automatic subtitles — zero Cast, zero WebView needed.
        String action = i.getAction();
        if (Intent.ACTION_VIEW.equals(action) && i.getData() != null) {
            String u = String.valueOf(i.getData());
            if (u.startsWith("http://") || u.startsWith("https://")) {
                url = u;
                title = u;
                Diag.i("player", "ACTION_VIEW play: " + u);
                return;
            }
        }
        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence cs = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String found = cs == null ? null : firstUrlIn(cs.toString());
            if (found != null) {
                url = found;
                CharSequence subj = i.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                title = subj != null && subj.toString().trim().length() >= 2
                    ? subj.toString().trim() : url;
                Diag.i("player", "ACTION_SEND play: " + url);
                return;
            }
        }
        String json = i.getStringExtra("castJson");   // the real /play payload
        if (json != null) {
            Map<String, Object> m = null;
            try { m = Cue.statusOf(json); } catch (Exception e) { Diag.w("player", "cast json: " + e); }
            if (m != null) {
                url = MiniJson.str(m, "url");
                title = MiniJson.str(m, "title");
                if (title == null || title.isEmpty()) title = MiniJson.str(m, "pageTitle");
                if (title == null || title.isEmpty()) title = "AnantPlay cast";
                headersJson = MiniJson.str(m, "headers");
                cookie = MiniJson.str(m, "cookie");
                mime = MiniJson.str(m, "mime");
                imdb = MiniJson.str(m, "imdb");
                Number at = MiniJson.num(m, "at");
                startAt = at == null ? 0 : at.longValue();
                Number s = MiniJson.num(m, "season"), e = MiniJson.num(m, "episode");
                season = s == null ? 0 : s.intValue();
                episode = e == null ? 0 : e.intValue();
                Boolean lv = (Boolean) MiniJson.objGet(m, "live");
                live = lv != null && lv;
                Boolean dash = (Boolean) MiniJson.objGet(m, "dash");
                dashFlag = dash != null && dash;
                Object subsObj = MiniJson.objGet(m, "subs");
                if (subsObj instanceof List) {
                    castSubs = new java.util.ArrayList<>();
                    for (Object so : (List<?>) subsObj) if (so != null) castSubs.add(String.valueOf(so));
                }
            }
        }
        if (url == null) {   // nothing playable arrived
            Diag.e("player", "no url in intent");
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // singleTask: a second cast (or the takeover reorder) lands here on the live
        // instance. A bare intent (the takeover) just brings us forward; only a
        // genuinely new payload rebuilds the player — no stale stream, no double audio.
        String newCast = i.getStringExtra("castJson");
        String newUrl = i.getStringExtra("url");
        boolean newTest = i.getBooleanExtra("test", false);
        // v3.0.0: a re-shared link (ACTION_VIEW data / ACTION_SEND text) counts too
        String action = i.getAction();
        if (newCast == null && newUrl == null && !newTest) {
            if (Intent.ACTION_VIEW.equals(action) && i.getData() != null
                && String.valueOf(i.getData()).startsWith("http")) newUrl = String.valueOf(i.getData());
            else if (Intent.ACTION_SEND.equals(action) && i.getStringExtra(Intent.EXTRA_TEXT) != null) {
                String u = firstUrlIn(String.valueOf(i.getCharSequenceExtra(Intent.EXTRA_TEXT)));
                if (u != null) newUrl = u;
            }
        }
        if (newCast == null && newUrl == null && !newTest) return;   // takeover / re-delivery
        String oldCast = getIntent().getStringExtra("castJson");
        setIntent(i);
        if (newCast != null && newCast.equals(oldCast == null ? "" : oldCast)) return;   // same cast again
        // v3.1.0 (Bug 2 hardening): a malformed re-cast must never kill the
        // player that is already running — parse defensively, keep the old
        // stream alive when the new payload does not produce a playable url.
        try {
            Diag.i("player", "re-targeted by a new cast — reloading");
            String prevUrl = url;
            if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
            cues = new ArrayList<>();
            enFallbackOn = false;
            subDelayMs = 0;
            parseIntent();
            if (url != null) {
                startPlayback();
                fetchSinhala();
            } else {
                Diag.w("player", "re-cast had no playable url — resuming the previous stream");
                url = prevUrl;
                startPlayback();   // rebuild the previous stream; no half-dead activity
            }
        } catch (Throwable t) {
            // absolute last resort: the player may be torn down — finish cleanly
            // instead of a crash dialog (CrashLog still records the reason).
            Diag.e("player", "re-target failed: " + t);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        finished = true;
        saveResumePoint();   // v3.1.0 — Auto-Resume: keep the last position before teardown
        // v2.2 leak fix: the two-stage Cinema Polish re-check (and the 4 Hz ticker,
        // the HUD/pill hide timers, the delayed PiP arming) all live on `main`.
        // Clearing the queue here is what makes the re-check "cancelled in
        // onDestroy()" true rather than merely no-op'd by the `finished` flag —
        // nothing keeps the Activity, its ExoPlayer or its store reachable after
        // the screen is gone, and no late hot-swap can touch a dead view.
        if (main != null) main.removeCallbacksAndMessages(null);
        if (currentInstance == this) {
            currentInstance = null;
            snapshot = ReceiverServer.idleJson();
        }
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        Diag.i("player", "closed");
        super.onDestroy();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        if (hud != null) hud.setVisibility(inPip || !hudVisible ? View.GONE : View.VISIBLE);
        if (panel != null) panel.setVisibility(View.GONE);      // never a floating subs panel
    }

    /** Android 12+: let the system enter PiP itself (no onUserLeaveHint race).
     *  v3.1.0: gated by the Playback settings' Auto-PiP toggle. */
    private void armAutoPip() {
        if (Build.VERSION.SDK_INT < 31) return;
        if (!AppPrefs.autoPip(prefs)) return;
        try {
            setPictureInPictureParams(new android.app.PictureInPictureParams.Builder()
                .setAutoEnterEnabled(true).build());
        } catch (Throwable t) {
            Diag.w("pip", "auto-enter: " + t);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        // keep watching in the small window instead of going under something else — but
        // never during the cast hand-over (AnantPlay's Remote opens on top of us for
        // ~0.8 s before our takeover brings the player back)
        if (expectTakeover) return;
        if (!AppPrefs.autoPip(prefs)) return;                   // v3.1.0: the user's choice
        try {
            if (player != null && player.isPlaying() && Build.VERSION.SDK_INT >= 26)
                enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
        } catch (Throwable t) {
            Diag.w("pip", t.toString());
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // the player (same recipe as AnantPlay's PlayerActivity.java:1087-1140)
    // ───────────────────────────────────────────────────────────────────────────

    private void startPlayback() {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            if (headersJson != null && !headersJson.isEmpty()) {
                try {
                    Map<String, Object> h = Cue.statusOf(headersJson);
                    for (String k : h.keySet()) headers.put(k, String.valueOf(h.get(k)));
                } catch (Exception e) { Diag.w("player", "headers json: " + e); }
            }
            if (cookie != null && !cookie.isEmpty() && !headers.containsKey("Cookie"))
                headers.put("Cookie", cookie);
            if (!headers.containsKey("User-Agent"))
                headers.put("User-Agent", "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36");

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
                .setAllowCrossProtocolRedirects(true);
            DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, http);

            // Resolve the mime BEFORE building the source: an extensionless HLS/DASH URL
            // and a progressive .mp4 must not be handed to the wrong parser.
            mime = StreamHint.mime(url, mime, dashFlag);

            player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .setHandleAudioBecomingNoisy(true)
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
            player.setVideoSurfaceView(surface);
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());   // our cues first, like AnantPlay
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int s) {
                    spinner.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    if (s == Player.STATE_READY) onReady();
                    updateSnapshot();
                }
                @Override public void onIsPlayingChanged(boolean p) {
                    if (p) scheduleHudHide();
                    updateSnapshot();
                }
                @Override public void onPlayerError(PlaybackException e) {
                    Diag.e("player", "error: " + e.getErrorCodeName());
                    final long pos = player == null ? 0 : player.getCurrentPosition();
                    showError(e.getErrorCodeName() + (e.getCause() == null ? "" : " · " + e.getCause().getMessage()));
                    diagnoseStreamFailure(e, pos);   // v2.3.0: DoH ISP-block check + retry advice
                    updateSnapshot();
                }
                @Override public void onCues(CueGroup g) {
                    if (enFallbackOn && cues.isEmpty()) renderFallbackEnglish(g);   // the stream's own CC
                }
            });
            MediaItem item = MediaItem.fromUri(Uri.parse(url));
            if (mime != null && !mime.isEmpty())
                item = item.buildUpon().setMimeType(mime).build();
            player.setMediaItem(item);
            player.prepare();
            if (startAt > 10000 && !live) player.seekTo(startAt);   // Cast.play's castAt carry-on
            else if (!live && AppPrefs.autoResume(prefs)) {
                // v3.1.0 — Auto-Resume Last Position (Playback settings): a
                // re-opened URL jumps to where it was left (never near the end,
                // which would look like a broken replay).
                try {
                    long saved = prefs.getLong(resumeKey(), 0);
                    long dur = player.getDuration();
                    if (saved > 10000 && (dur <= 0 || saved < dur - 15000)) {
                        player.seekTo(saved);
                        startAt = saved;
                        flash("↻ " + ReceiverServer.clock(saved) + " සිට · resumed");
                    }
                } catch (Throwable ignored) { }
            }
            player.setPlayWhenReady(true);
        } catch (Throwable t) {
            Diag.e("player", "start: " + t);
            showError(String.valueOf(t));
        }
    }

    // ── /status + /do (the cast surface; called on the main thread by the service) ──

    String statusJson() {
        if (player == null) return ReceiverServer.idleJson();
        long dur = player.getDuration();
        live = dur == C.TIME_UNSET || player.isCurrentMediaItemLive();
        return ReceiverServer.statusJson(title, player.getCurrentPosition(), dur, player.getPlayWhenReady(), live);
    }

    String castAction(String a, long to) {
        if (player == null) return ReceiverServer.idleJson();
        if ("toggle".equals(a)) player.setPlayWhenReady(!player.getPlayWhenReady());
        else if ("seek".equals(a)) { player.seekTo(to); showControls(); }
        else if ("stop".equals(a)) { finish(); return ReceiverServer.idleJson(); }
        return statusJson();
    }

    /** STATE_READY: the seek bar learns the duration; live shows AnantPlay's ● LIVE (PlayerActivity.java:1445-1448). */
    private void onReady() {
        if (player == null) return;
        long dur = player.getDuration();
        live = dur == C.TIME_UNSET || player.isCurrentMediaItemLive();
        if (live) {
            seek.setVisibility(View.INVISIBLE);
            timeTotal.setText("● LIVE");
        } else {
            seek.setVisibility(View.VISIBLE);
            seek.setMax((int) Math.max(1, dur));
            timeTotal.setText(ReceiverServer.clock(dur));
        }
        hudTitle.setText(title);
        showStatus();
        scheduleHudHide();
    }

    private void updateSnapshot() {
        PlayActivity p = currentInstance;
        if (p == null) return;
        snapshot = p.statusJson();
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Sinhala subtitles — the worker pipeline (bg thread) + the native renderer
    // ───────────────────────────────────────────────────────────────────────────

    /** The subtitle pipeline v2.1 — the full chain, never silent:
     *   0. local cache (instant, offline)
     *   1. CAST-SUPPLIED SUBS (v2.1): AnantPlay's own `subs` URLs — the English
     *      .srt the phone player already had is handed to the worker's
     *      /api/subs-direct for instant translation (no IMDb needed, cached forever)
     *   2. the worker's /subtitles job: human Sinhala first, else multi-provider AI
     *      translation (azure → google_v3 → gemini → workers_ai), polled on the
     *      deterministic token URL (200 .srt | 202 progress | 404 → re-ensure).
     * Every state change lands in the status pill; every failure names its cause. */
    private void fetchSinhala() {
        if (fetching || testMode) return;
        fetching = true;
        final int myGen = ++fetchGen;   // a re-cast bumps this: the in-flight fetch goes stale
        new Thread(() -> {
            try {
                boolean series = episode > 0 || season > 0;
                String type = series ? "series" : "movie";
                String key = type + ":t:" + (title == null ? "" : title.toLowerCase(Locale.ROOT)) + ":" + season + ":" + episode + ":" + activeLang;
                List<Cue> cached = store.load(key);
                if (cached != null) {
                    applyCues(cached, "cached");
                    return;
                }
                setState("fetching", BridgeApi.searchingText(), myGen);
                // ── 1. cast-supplied subs first (v2.1): instant, no IMDb needed ──
                if (castSubs != null && !castSubs.isEmpty()) {
                    for (String subUrl : castSubs) {
                        if (myGen != fetchGen) return;
                        if (subUrl == null || !(subUrl.startsWith("http://") || subUrl.startsWith("https://"))) continue;
                        try {
                            setState("fetching", "cast subtitle ලබාගනිමින්… · fetching the cast's own subtitle…", myGen);
                            byte[] raw = httpGet(subUrl, null);
                            String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                            List<Cue> enCues = SrtLoader.load(raw, "cast.srt");
                            if (enCues.size() < 8) continue;   // not a real track
                            setState("fetching", "cast subtitle පරිවර්තනය වෙමින්… · translating the cast's subtitle…", myGen);
                            Map<String, Object> job = api.subsDirect(text, title, activeLang, registerOverride);
                            String token = tokenOf(job);
                            if (pollToDone(token, myGen)) {
                                List<Cue> got = SrtLoader.load(api.srt(token).getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                                if (myGen != fetchGen) return;
                                applyCues(got, "cast-subs → " + String.valueOf(job.get("provider")));
                                store.save(key, got);
                                return;
                            }
                        } catch (BridgeApi.HttpException he) {
                            Diag.w("subs", "cast subs leg: HTTP " + he.status);
                            if (he.status == 429 || he.status >= 500) continue;  // try the next leg
                        } catch (Exception e) {
                            Diag.w("subs", "cast subs leg: " + e);
                        }
                    }
                }
                // ── 2. the worker job (human Sinhala → multi-provider AI) ──
                String id = imdb;
                if (id == null || id.isEmpty()) {
                    // v2.3.0: NOISY-TITLE CLEANING before Cinemeta (Phase 2, Killer #2).
                    // Arbitrary sites send page titles like "Watch The Odyssey 2026 Full
                    // Movie Free HD - OnlyFlix" — the raw string makes /api/resolve fail
                    // or mismatch. The cleaned form feeds resolve + the cache key; the
                    // HUD keeps showing the original. Season/episode extracted here fix
                    // series lookups where the cast payload had none.
                    TitleCleaner.Clean cleaned = TitleCleaner.clean(title);
                    String lookupTitle = cleaned.title;
                    if (cleaned.season != null && season == 0) season = cleaned.season;
                    if (cleaned.episode != null && episode == 0) episode = cleaned.episode;
                    if (cleaned.changed) {
                        Diag.i("subs", "title cleaned for lookup: \"" + title + "\" → \"" + lookupTitle + "\""
                            + (cleaned.year != null ? " (" + cleaned.year + ")" : ""));
                    }
                    Map<String, Object> r = api.resolve(lookupTitle, series, season, episode);
                    if (r == null && cleaned.changed) {
                        // the cleaned form failed → one honest retry with the raw title
                        Diag.w("subs", "cleaned title did not resolve — retrying with the raw title");
                        r = api.resolve(title, series, season, episode);
                    }
                    if (r == null) {
                        setState("not-found", "Cinemeta couldn't match \"" + title + "\" — try the title override", myGen);
                        fallbackEnglishTrack();
                        return;
                    }
                    id = String.valueOf(r.get("imdb"));
                }
                for (int round = 0; round < 4; round++) {
                    Map<String, Object> job = api.subtitles(type, id, season, episode, title, activeLang, registerOverride);
                    String status = String.valueOf(job.get("status"));
                    if ("needs-key".equals(status)) {
                        setState("needs-key", "සිංහල උපසිරැසි නැහැ — Worker එකේ provider key එකක් set කරන්න "
                            + "(AZURE_TRANSLATOR_KEY / GOOGLE_V3_KEY / GEMINI_API_KEY) · no translation provider configured on the worker", myGen);
                        fallbackEnglishTrack();
                        return;
                    }
                    if ("not-found".equals(status)) {
                        setState("not-found", "මෙම තේමාවට උපසිරැසි හමු නොවීය — local .srt loader එක බාවිතා කරන්න · "
                            + "no subtitles found — use the local .srt loader", myGen);
                        fallbackEnglishTrack();
                        return;
                    }
                    Map<String, Object> si = (Map<String, Object>) MiniJson.objGet(job, "si");
                    String token = si == null ? null : String.valueOf(si.get("token"));
                    if ("done".equals(status) && token != null && !token.isEmpty()) {
                        // v2.2 two-stage: read the quality headers with the .srt
                        java.util.Map<String, String> withQ = api.srtWithQuality(token);
                        String text = withQ.get("text");
                        List<Cue> got = SrtLoader.load(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                        if (myGen != fetchGen) return;   // re-cast happened meanwhile
                        String quality = withQ.containsKey("X-Sub-Quality") ? withQ.get("X-Sub-Quality") : "";
                        String polish = withQ.containsKey("X-Polish-Status") ? withQ.get("X-Polish-Status") : "";
                        String source = String.valueOf(job.get("source"));
                        if ("base-nmt".equals(quality) && "pending".equals(polish)) {
                            polishWatchToken = token;      // the delayed re-check hot-swaps it
                            polishHotSwapped = false;
                            schedulePolishRecheck(key, myGen);
                        } else if ("cinema-polished".equals(quality)) {
                            polishHotSwapped = true;       // already upgraded
                        }
                        applyCues(got, source + ("cinema-polished".equals(quality) ? " · ✨ cinema" : ""));
                        store.save(key, got);            // save THIS list, not the shared field
                        return;
                    }
                    if ("translating".equals(status) && token != null && !token.isEmpty()) {
                        if (pollToDone(token, myGen)) {
                            List<Cue> got = SrtLoader.load(api.srt(token).getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                            if (myGen != fetchGen) return;
                            applyCues(got, String.valueOf(job.get("provider")));
                            store.save(key, got);
                            return;
                        }
                        continue;   // token went cold — re-ensure the job on a warm isolate
                    }
                    setState("error", "worker status: " + status, myGen);
                    fallbackEnglishTrack();
                    return;
                }
                setState("error", "subtitles did not become ready — try Refetch", myGen);
                fallbackEnglishTrack();
            } catch (BridgeApi.HttpException he) {
                // 429 (quota) / 5xx / 401 — said out loud in the status bar, logged
                Diag.e("subs", "worker HTTP " + he.status + ": " + BridgeApi.brief(he.body, 300));
                setState("error", BridgeApi.explain(he.status) + " · " + BridgeApi.brief(he.body, 90), myGen);
                fallbackEnglishTrack();
            } catch (java.io.IOException io) {
                Diag.w("subs", "network: " + io);
                setState("error", BridgeApi.explain(io), myGen);
                fallbackEnglishTrack();
            } catch (Exception ex) {
                Diag.e("subs", "fetch: " + ex);
                setState("error", BridgeApi.explain(ex), myGen);
                fallbackEnglishTrack();
            } finally {
                fetching = false;
            }
        }, "si-subs").start();
    }

    /** Extract the si token from a job body ({si:{token}} in both paths). */
    private static String tokenOf(Map<String, Object> job) {
        Map<String, Object> si = (Map<String, Object>) MiniJson.objGet(job, "si");
        return si == null ? null : String.valueOf(si.get("token"));
    }

    /** Poll the deterministic /sub/<token>.srt URL until it answers a real .srt.
     *  Returns true with the text in `polledSrt`, false when the token went cold
     *  (isolate miss → caller re-ensures the job) or the job errored. */
    private String polledSrt;
    private boolean pollToDone(String token, int myGen) throws Exception {
        for (int poll = 0; poll < 240; poll++) {
            if (myGen != fetchGen) return false;
            try {
                String body = api.srt(token);
                if (body.trim().startsWith("{")) {
                    Map<String, Object> p = null;
                    try { p = Cue.statusOf(body); } catch (Exception ignored) { }
                    if (p != null && "translating".equals(p.get("status"))) {
                        setState("fetching", BridgeApi.progressText(p), myGen);
                        SystemClock.sleep(4000);
                        continue;
                    }
                    if (p != null && "error".equals(p.get("status"))) {
                        setState("error", "worker job error: " + BridgeApi.brief(String.valueOf(p.get("error")), 120), myGen);
                        return false;
                    }
                }
                polledSrt = body;
                return true;
            } catch (BridgeApi.HttpException he) {
                if (he.status == 404) return false;      // isolate miss → caller re-ensures
                if (he.status == 429) {
                    Diag.w("subs", "worker 429 (quota) at poll " + poll + " — backing off 15 s");
                    setState("fetching", BridgeApi.explain(429), myGen);
                    SystemClock.sleep(15000);
                    continue;
                }
                if (he.status == 410 || he.status >= 500) {
                    Diag.e("subs", "worker HTTP " + he.status + ": " + BridgeApi.brief(he.body, 300));
                    setState("error", BridgeApi.explain(he.status) + " · " + BridgeApi.brief(he.body, 90), myGen);
                    return false;
                }
                SystemClock.sleep(4000);
            }
        }
        return false;
    }

    /** Manual fetch with a title override (the subs panel's Go button). */
    private void manualFetch(String t, int s, int e) {
        title = t;
        season = s;
        episode = e;
        imdb = null;
        fetchGen++;                 // the in-flight fetch (if any) goes stale
        fetching = false;
        cues = new ArrayList<>();
        fetchSinhala();
    }

    private void applyCues(List<Cue> loaded, String how) {
        cues = loaded;
        boolean hasEn = false;
        for (Cue c : loaded) if (c.en != null && !c.en.isEmpty()) { hasEn = true; break; }
        setState("ready", "සිංහල උපසිරැසි සූදානම් · " + loaded.size() + " cues · " + how
            + (hasEn ? "" : " · human Sinhala"));
        Diag.i("subs", "applied " + loaded.size() + " cues (" + how + ")");
    }

    /** Nothing in Sinhala: offer the stream's own text track (the "CC English" habit). */
    private void fallbackEnglishTrack() {
        if (enFallbackOn || finished || player == null) return;
        main.post(() -> {
            try {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
                enFallbackOn = true;
                setState(state, stateDetail + " · English CC (stream)");
                Diag.i("subs", "Sinhala unavailable — stream text track on (English CC)");
            } catch (Throwable t) {
                Diag.w("subs", "fallback track: " + t);
            }
        });
    }

    private void renderFallbackEnglish(CueGroup g) {
        StringBuilder sb = new StringBuilder();
        for (androidx.media3.common.text.Cue c : g.cues) {
            if (c.text == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.text);
        }
        main.post(() -> {
            if (subView != null) {
                // the stream's own CC track: system font, base direction from the language
                applyLangStyle();
                subView.setText(sb);
                subView.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
    }

    /** A local .srt/.zip the user picked (SAF) — human Sinhala from Baiscope/Zoom/etc. */
    void onLocalCues(List<Cue> loaded, String name) {
        cues = loaded;
        enFallbackOn = false;
        setState("local", name + " · " + loaded.size() + " cues");
        Diag.i("subs", "local " + name + ": " + loaded.size() + " cues");
    }

    /**
     * v2.2 two-stage: ONE delayed re-check (worker docs: polish lands in
     * ~15–30 s). If the SAME token now serves cinema-polished quality, the
     * SubtitleStore hot-swaps silently mid-playback — playback never pauses,
     * the status pill flashes "✨ Cinema Polish Ready". Runs at most twice.
     */
    private void schedulePolishRecheck(final String key, final int myGen) {
        if (!AppPrefs.cinemaPolish(prefs)) return;   // v3.1.0: Cinema Polish toggle OFF
        main.postDelayed(() -> {
            if (myGen != fetchGen || finished) return;
            new Thread(() -> {
                try {
                    String token = polishWatchToken;
                    if (token == null || polishHotSwapped) return;
                    java.util.Map<String, String> withQ = api.srtWithQuality(token);
                    if (!"cinema-polished".equals(withQ.get("X-Sub-Quality"))) {
                        // still pending → one more polite look, then stop asking
                        if (!polishHotSwapped) main.postDelayed(() -> {
                            if (myGen == fetchGen && !finished && !polishHotSwapped) {
                                new Thread(() -> {
                                    try {
                                        String t2 = polishWatchToken;
                                        if (t2 == null) return;
                                        java.util.Map<String, String> w2 = api.srtWithQuality(t2);
                                        if ("cinema-polished".equals(w2.get("X-Sub-Quality"))) hotSwapCues(w2.get("text"), key);
                                    } catch (Exception ignored) { }
                                }).start();
                            }
                        }, 20000);
                        return;
                    }
                    hotSwapCues(withQ.get("text"), key);
                } catch (Exception ignored) { }
            }).start();
        }, 20000);
    }

    /** The silent upgrade: same timestamps contract, fresh wording. */
    private void hotSwapCues(String text, String key) {
        try {
            List<Cue> got = SrtLoader.load(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
            if (got.isEmpty()) return;
            polishHotSwapped = true;
            polishWatchToken = null;
            main.post(() -> {
                cues = got;                       // SubtitleStore swap without touching playback
                setState(state, "✨ Cinema Polish Ready · සිනමා ප්‍රඥප්තිය සූදානම්");
                Diag.i("subs", "cinema polish hot-swapped (" + got.size() + " cues)");
            });
            store.save(key, got);
        } catch (Exception e) {
            Diag.w("subs", "polish swap skipped: " + e);
        }
    }

    private void applyTestCues() {
        List<Cue> demo = new ArrayList<>();
        demo.add(new Cue(0, 500, 4000, "00:00:00,500 --> 00:00:04,000", "", "", "ආයුබෝවන්! සිංහල Player ක්‍රියාත්මකයි ✓"));
        demo.add(new Cue(1, 4600, 9000, "00:00:04,600 --> 00:00:09,000", "", "", "මෙය AnantPlay Cast මඟින් එවන වීඩියෝවකි — උපසිරැසි සිංහලෙන්."));
        demo.add(new Cue(2, 9500, 15000, "00:00:09,500 --> 00:00:15,000", "", "", "ඔබේ චිත්‍රපටිය AnantPlay තුළ විවෘත කර Cast ඔබන්න — මෙය ස්වයංක්‍රීයව විවෘත වේ."));
        cues = demo;
        setState("ready", "test cues");
    }

    // ───────────────────────────────────────────────────────────────────────────
    // the native renderer — our cues against the player's own position, 4 Hz
    // ───────────────────────────────────────────────────────────────────────────

    private final Runnable ticker = new Runnable() {
        int tick;                                   // 4 Hz — Auto-Resume saves at ~1 Hz
        @Override public void run() {
            if (finished) return;
            if (player != null && !enFallbackOn) {
                long pos = player.getCurrentPosition() - subDelayMs;
                Cue hit = null;
                List<Cue> cs = cues;
                for (Cue c : cs) {
                    if (pos >= c.t0 && pos < c.t1) { hit = c; break; }
                    if (c.t0 > pos) break;
                }
                if (hit != null) {
                    String text = hit.si == null || hit.si.isEmpty() ? hit.en : hit.si;
                    // v2.2 script-safe: the bundled NotoSansSinhala ONLY for si;
                    // every other language renders with the system font stack,
                    // and RTL languages (ar/ur) get RTL text direction.
                    applyLangStyle();
                    subView.setText(text == null ? "" : text);
                    subView.setVisibility(View.VISIBLE);
                } else {
                    subView.setVisibility(View.GONE);
                }
                if (hudVisible) {
                    timeNow.setText(ReceiverServer.clock(player.getCurrentPosition()));
                    if (!dragging) seek.setProgress((int) Math.max(0, player.getCurrentPosition()));
                }
                if (++tick % 4 == 0) saveResumePoint();   // v3.1.0: Auto-Resume
            }
            updateSnapshot();
            main.postDelayed(this, 250);
        }
    };

    private void startTicker() { main.post(ticker); }

    private void setState(String s, String detail) {
        setState(s, detail, -1);
    }

    /** v2.3.0: gen-guarded setState — a STALE fetch thread (the user switched
     *  language / re-cast mid-poll) can no longer flip the status pill after the
     *  fact. gen < 0 = unconditional (lifecycle/UI-originated states). */
    private void setState(String s, String detail, int gen) {
        if (gen >= 0 && gen != fetchGen) return;   // a stale thread stays quiet
        state = s;
        stateDetail = detail == null ? "" : detail;
        main.post(this::showStatus);
        if ("error".equals(s) || "not-found".equals(s) || "needs-key".equals(s)) {
            main.post(this::showSubActions);   // failures expose their 1-tap fixes
        }
    }

    /** The status pill state → bilingual line + color (the never-silent HUD). */
    private void showStatus() {
        if (hudStatus != null) {
            boolean good = "ready".equals(state) || "local".equals(state);
            boolean warn = "fetching".equals(state);
            hudStatus.setText((good ? "✓ " : warn ? "… " : "⚠ ") + stateDetail);
            hudStatus.setTextColor(good ? 0xFF8CFF8C : warn ? 0xFFF59E0B : 0xFFFFB3B3);
            hudStatus.setVisibility(View.VISIBLE);
            if (good) main.postDelayed(hudStatusHide, 3000);   // success auto-hides after 3 s
        }
        if (statusPill != null) {
            boolean good = "ready".equals(state) || "local".equals(state);
            statusPill.setText((good ? "✓ " : "fetching".equals(state) ? "… " : "⚠ ") + stateDetail);
            statusPill.setTextColor(good ? 0xFF8CFF8C : "fetching".equals(state) ? 0xFFFDE68A : 0xFFFFB3B3);
            statusPill.setVisibility(View.VISIBLE);
            if (good) main.postDelayed(pillHide, 3000);
        }
    }

    private final Runnable hudStatusHide = new Runnable() {
        @Override public void run() { if (hudStatus != null && ("ready".equals(state) || "local".equals(state))) hudStatus.setVisibility(View.INVISIBLE); }
    };

    private final Runnable pillHide = new Runnable() {
        @Override public void run() {
            if (statusPill != null && ("ready".equals(state) || "local".equals(state))) {
                statusPill.setVisibility(View.GONE);
            }
        }
    };

    // ───────────────────────────────────────────────────────────────────────────
    // the UI — built in code (the project's own idiom; no XML layouts anywhere)
    // ───────────────────────────────────────────────────────────────────────────

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        // v3.1.0 — Playback settings (the Mornye-style Playback page): Keep Screen
        // On is a user choice now (still the sensible default).
        if (AppPrefs.keepScreenOn(prefs))
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // PlayerActivity.java:157
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.getHolder().setFormat(PixelFormat.OPAQUE);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        // the subtitle view — bottom center, script-correct font, styled by the
        // v3.1.0 Subtitle Styling Studio (Settings → Subtitles & Style Studio):
        // color preset, pill/outline background + opacity, bold, bottom position.
        subView = new TextView(this);
        // v2.2: the bundled NotoSansSinhala is loaded ONCE (onCreate) and applied
        // only when the active language is Sinhala; every other script uses the
        // system font stack (the OS already covers Tamil/Devanagari/CJK/Arabic).
        // applyLangStyle() is the ONE place that derives both the typeface and the
        // base direction from `activeLang` — startup, language switch and every
        // rendered cue all go through it, so the two can never disagree.
        applyLangStyle();
        subView.setGravity(Gravity.CENTER);
        applyStudioStyle();
        root.addView(subView, subLayoutParams());

        notice = new TextView(this);
        notice.setTextColor(Color.WHITE);
        notice.setTextSize(14);
        notice.setBackgroundResource(0x88000000);
        notice.setPadding(dp(14), dp(8), dp(14), dp(8));
        notice.setGravity(Gravity.CENTER);
        notice.setVisibility(View.GONE);
        root.addView(notice, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // ── v2.1: the subtitle status pill — the never-silent HUD ──
        // Auto-hides 3 s after success; on error it STAYS with the exact reason
        // and the action row below carries the 1-tap fixes.
        statusPill = new TextView(this);
        statusPill.setTextColor(0xFFFDE68A);
        statusPill.setTextSize(12.5f);
        statusPill.setMaxLines(3);
        statusPill.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xE60E1424);
        pillBg.setCornerRadius(dp(14));
        pillBg.setStroke(dp(1), 0x33FFFFFF);
        statusPill.setBackground(pillBg);
        statusPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pillLp.topMargin = dp(64);
        root.addView(statusPill, pillLp);

        // ── v2.1: the subtitle action row (pill's fixes, one tap each) ──
        subActions = new LinearLayout(this);
        subActions.setOrientation(LinearLayout.HORIZONTAL);
        subActions.setGravity(Gravity.CENTER);
        subActions.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(0xE60E1424);
        rowBg.setCornerRadius(dp(16));
        subActions.setBackground(rowBg);
        subActions.setVisibility(View.GONE);
        subActions.addView(pillBtn("↻ Retry", v -> { subActions.setVisibility(View.GONE); fetchGen++; fetching = false; fetchSinhala(); }));
        subActions.addView(pillBtn("⇄ Provider", v -> { subActions.setVisibility(View.GONE); fetchGen++; fetching = false; prefs.edit().putBoolean("preferCastSubs", true).apply(); fetchSinhala(); }));
        subActions.addView(pillBtn("📄 Local .srt", v -> { subActions.setVisibility(View.GONE); pickLocal(); }));
        subActions.addView(pillBtn("⏱ ±0.25s", v -> nudge(250)));

        // ── v2.2: the language chip row (8 registry languages + More) ──
        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langRow.setGravity(Gravity.CENTER);
        langRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable langBg = new GradientDrawable();
        langBg.setColor(0xE60E1424);
        langBg.setCornerRadius(dp(16));
        langRow.setBackground(langBg);
        for (final Langs.Lang l : Langs.chips()) {
            langRow.addView(pillBtn(l.nativeName, v -> switchLang(l.code)));
        }
        langRow.addView(pillBtn("+ More", v -> showLangSheet()));
        FrameLayout.LayoutParams langLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        langLp.topMargin = dp(104);
        root.addView(langRow, langRowLp(langRow));
        langChips = langRow;

        // ── v2.2: register toggle + Option-C power-user item (shown with the pill) ──
        LinearLayout extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        extraRow.setGravity(Gravity.CENTER);
        extraRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable extraBg = new GradientDrawable();
        extraBg.setColor(0xE60E1424);
        extraBg.setCornerRadius(dp(16));
        extraRow.setBackground(extraBg);
        extraRow.addView(pillBtn("🗣 Spoken/Formal", v -> toggleRegister()));
        extraRow.addView(pillBtn("✨ AI Studio", v -> openAiStudioBridge()));
        FrameLayout.LayoutParams extraLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        extraLp.topMargin = dp(144);
        root.addView(extraRow, extraLp);
        extraActions = extraRow;

        FrameLayout.LayoutParams actLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        actLp.topMargin = dp(64);
        root.addView(subActions, actLp);

        spinner = new android.view.View(this);
        try {
            android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
            pb.setIndeterminate(true);
            spinner = pb;
        } catch (Throwable ignored) { }
        spinner.setVisibility(View.GONE);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));

        errorBox = new TextView(this);
        errorBox.setTextColor(0xFFFFB3B3);
        errorBox.setTextSize(14);
        errorBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        errorBox.setVisibility(View.GONE);
        GradientDrawable eb = new GradientDrawable();
        eb.setColor(0xE6220F1F);
        eb.setCornerRadius(dp(12));
        errorBox.setBackground(eb);
        // v2.3.0: the error box is the RETRY button — one tap rebuilds the player
        // at the last known position (fresh DataSource, same cast payload).
        errorBox.setOnClickListener(v -> retryPlayback());
        root.addView(errorBox, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        hud = buildHud();
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        panel = buildPanel();
        root.addView(panel, new FrameLayout.LayoutParams(dp(320), -2, Gravity.END | Gravity.CENTER_VERTICAL));
        panel.setVisibility(View.GONE);

        wireGestures(root);
        setContentView(root);
    }

    private LinearLayout buildHud() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0x88000000);

        // top bar: back · title · status
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(12), dp(12), dp(8));
        View back = icon("‹", 26, v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(46), dp(42)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        hudTitle = new TextView(this);
        hudTitle.setTextColor(Color.WHITE);
        hudTitle.setTextSize(16);
        hudTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hudTitle.setMaxLines(1);
        hudTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titles.addView(hudTitle);
        hudStatus = new TextView(this);
        hudStatus.setTextColor(0xFF9FB0D8);
        hudStatus.setTextSize(12);
        hudStatus.setMaxLines(2);
        titles.addView(hudStatus);
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(top);

        // spacer keeps the middle tap-transparent (the FrameLayout eats nothing)
        View gap = new View(this);
        box.addView(gap, new LinearLayout.LayoutParams(-1, 0, 1f));

        // bottom bar: time · seek · total · ±10 · play/pause · subs
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), 0, dp(12), dp(14));
        seek = new SeekBar(this);
        seek.setPadding(dp(2), dp(6), dp(2), dp(6));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user && player != null) timeNow.setText(ReceiverServer.clock(v));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (player != null) { player.seekTo(s.getProgress()); updateSnapshot(); }
            }
        });
        bottom.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        timeNow = new TextView(this);
        timeNow.setTextColor(Color.WHITE);
        timeNow.setTextSize(13);
        // v2.3.1: both time labels share the free space (weight) — on a narrow
        // 360dp Xiaomi screen the fixed icon buttons alone take 228dp, so
        // non-weighted labels could push the row past the right edge.
        row.addView(timeNow, new LinearLayout.LayoutParams(0, -2, 0.5f));
        final long stepMs = AppPrefs.seekStepMs(prefs);   // v3.1.0: 5/10/15/30 s from Playback settings
        final int stepLabel = (int) (stepMs / 1000);
        View minus = icon("−" + stepLabel, 14, v -> skip(-stepMs));
        row.addView(minus, new LinearLayout.LayoutParams(dp(58), dp(44)));
        playPause = icon("⏸", 20, v -> toggle());
        row.addView(playPause, new LinearLayout.LayoutParams(dp(54), dp(44)));
        View plus = icon("+" + stepLabel, 14, v -> skip(stepMs));
        row.addView(plus, new LinearLayout.LayoutParams(dp(58), dp(44)));
        timeTotal = new TextView(this);
        timeTotal.setTextColor(0xFFAAAAAA);
        timeTotal.setTextSize(13);
        timeTotal.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(timeTotal, new LinearLayout.LayoutParams(0, -2, 0.5f));
        View subs = icon("සිං", 14, v -> togglePanel());
        row.addView(subs, new LinearLayout.LayoutParams(dp(58), dp(44)));
        bottom.addView(row, new LinearLayout.LayoutParams(-1, -2));
        box.addView(bottom);
        return box;
    }

    private LinearLayout buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF20E1424);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setPadding(dp(14), dp(12), dp(14), dp(12));

        p.addView(label("අකුරේ ප්‍රමාණය · Font size"));
        p.addView(step("−", "+", () -> fontSp(-1), () -> fontSp(1)));
        p.addView(label("පසුබිම් අඳුන · Mask"));
        p.addView(step("−", "+", () -> mask(-10), () -> mask(10)));
        p.addView(label("කාල ගැලපුම · Sync nudge"));
        p.addView(step("−1s", "+1s", () -> nudge(-1000), () -> nudge(1000)));
        final long syncStep = AppPrefs.syncStepMs(prefs);      // v3.1.0: 0.1/0.25/0.5 s
        final String syncLabel = syncStep >= 1000 ? "1s" : (syncStep / 1000f) + "s";
        p.addView(step("−" + syncLabel, "+" + syncLabel, () -> nudge(-syncStep), () -> nudge(syncStep)));

        Button retry = btn("නැවත ලබාගන්න · Refetch subs");
        retry.setOnClickListener(v -> { panel.setVisibility(View.GONE); fetchSinhala(); });
        p.addView(retry);

        final EditText in = new EditText(this);
        in.setHint("title override · නම අතින්");
        in.setTextSize(13);
        in.setText(title);
        p.addView(in, new LinearLayout.LayoutParams(-1, -2));
        Button go = btn("Go — worker එකෙන් හාන්ගතකරන්න");
        go.setOnClickListener(v -> {
            String t = in.getText().toString().trim();
            if (t.length() >= 2) { panel.setVisibility(View.GONE); manualFetch(t, season, episode); }
        });
        p.addView(go);

        Button load = btn("local .srt / .zip load");
        load.setOnClickListener(v -> pickLocal());
        p.addView(load);

        Button close = btn("වසන්න · Close");
        close.setOnClickListener(v -> panel.setVisibility(View.GONE));
        p.addView(close);
        return p;
    }

    private TextView label(String t) {
        TextView l = new TextView(this);
        l.setText(t);
        l.setTextColor(0xFF9FB0D8);
        l.setTextSize(11.5f);
        l.setPadding(dp(2), dp(8), 0, dp(2));
        return l;
    }

    private LinearLayout step(String l, String r, Runnable minus, Runnable plus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button a = btn(l);
        a.setOnClickListener(v -> minus.run());
        row.addView(a, new LinearLayout.LayoutParams(0, -2, 1f));
        Button b = btn(r);
        b.setOnClickListener(v -> plus.run());
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private Button btn(String t) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(t);
        b.setTextSize(12.5f);
        b.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1A2440);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    /** A plain text icon-button (the project's zero-asset idiom). */
    private View icon(String glyph, float sp, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(siFont, Typeface.BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x26FFFFFF);
        g.setCornerRadius(dp(12));
        t.setBackground(g);
        t.setOnClickListener(click);
        return t;
    }

    /** v2.1: a compact pill button for the subtitle action row. */
    private View pillBtn(String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFE2E8F0);
        t.setTextSize(12f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1E2A4A);
        g.setCornerRadius(dp(14));
        t.setBackground(g);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = lp.rightMargin = dp(4);
        t.setLayoutParams(lp);
        t.setOnClickListener(click);
        return t;
    }

    /** v2.1: show the pill's action row (called on error / long-press states). */
    private void showSubActions() {
        if (subActions != null) subActions.setVisibility(View.VISIBLE);
        if (statusPill != null) statusPill.setVisibility(View.VISIBLE);
        if (langChips != null) langChips.setVisibility(View.VISIBLE);
        if (extraActions != null) extraActions.setVisibility(View.VISIBLE);
        main.postDelayed(() -> {
            if (subActions != null) subActions.setVisibility(View.GONE);
            if (langChips != null) langChips.setVisibility(View.GONE);
            if (extraActions != null) extraActions.setVisibility(View.GONE);
        }, 12000);
    }

    private FrameLayout.LayoutParams langRowLp(LinearLayout row) { return new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); }

    /** v2.2 script-safe styling — the ONE place the subtitle view's typeface and
     *  base text direction are derived from the active language.
     *
     *  Fix (v2.2.0 audit): the subtitle TextView must NOT be initialised in
     *  NotoSansSinhala unconditionally. The bundled font is a Sinhala-only asset —
     *  applied ONLY for `si`; every other language uses the system stack
     *  (`Typeface.DEFAULT_BOLD`, resolved once, never re-loaded per cue), and the
     *  RTL languages `ar`/`ur` get `TEXT_DIRECTION_ANY_RTL` while everything else
     *  is explicitly LTR. Called on startup (buildUi), on every language switch
     *  (switchLang) and before each rendered cue, so a saved `ta`/`ar`/`ja`
     *  default can never open in the Sinhala font or the wrong direction. */
    private void applyLangStyle() {
        if (subView == null) return;
        subView.setTypeface(Langs.useBundledSinhalaFont(activeLang) ? siFont : Typeface.DEFAULT_BOLD);
        subView.setTextDirection(Langs.isRtl(activeLang)
            ? android.view.View.TEXT_DIRECTION_ANY_RTL : android.view.View.TEXT_DIRECTION_LTR);
    }

    // ── v3.1.0: the Subtitle Styling Studio application (Settings → Subtitles) ──
    // Color preset · pill/outline background + opacity · bold · bottom position
    // (4–28% of screen height) — every value pre-clamped by AppPrefs.

    /** (Re)apply every studio dimension to the live subtitle view. */
    private void applyStudioStyle() {
        if (subView == null) return;
        subView.setTextSize(AppPrefs.fontSp(prefs));
        subView.setTextColor(ThemeEngine.SUB_COLORS[AppPrefs.subColor(prefs)]);
        subView.setTypeface(subView.getTypeface(),
            AppPrefs.subBold(prefs) ? Typeface.BOLD : Typeface.NORMAL);
        if (AppPrefs.BG_OUTLINE.equals(AppPrefs.subBg(prefs))) {
            // crisp black edge/outline only — no pill background
            subView.setBackgroundColor(Color.TRANSPARENT);
            subView.setShadowLayer(dp(4), 0, 0, 0xFF000000);
        } else {
            subView.setBackgroundColor(ThemeEngine.pillColor(AppPrefs.subBgOpacity(prefs)));
            subView.setShadowLayer(dp(2), 0, 0, 0x99000000);
        }
        subView.setPadding(dp(10), dp(4), dp(10), dp(4));
        subView.setVisibility(subView.getVisibility());
        FrameLayout.LayoutParams sl = subLayoutParams();
        subView.setLayoutParams(sl);
    }

    /** The subtitle view's bottom position: 4–28% of the screen height. */
    private FrameLayout.LayoutParams subLayoutParams() {
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        sl.bottomMargin = Math.round(h * AppPrefs.subBottomPct(prefs) / 100f);
        return sl;
    }

    /** The panel's font-size steppers now clamp through the studio model (14–36). */
    private void fontSp(int d) {
        int sp = AppPrefs.normalizeFontSp(AppPrefs.fontSp(prefs) + d);
        prefs.edit().putInt(AppPrefs.K_FONT_SP, sp).apply();
        applyStudioStyle();
        flash("Aa " + sp + " sp");
    }

    /** The panel's background stepper drives the studio opacity (0–90%). */
    private void mask(int d) {
        int pct = AppPrefs.normalizeSubBgOpacity(AppPrefs.subBgOpacity(prefs) + d);
        prefs.edit().putInt(AppPrefs.K_SUB_BG_OPACITY, pct).apply();
        applyStudioStyle();
        flash("BG " + pct + "%");
    }

    /** v2.2: 1-tap language switch — refetch in the new language (cached jobs
     *  return instantly; the store keys per language). */
    void switchLang(String code) {
        if (!Langs.isKnown(code) || code.equals(activeLang)) return;
        activeLang = code;
        applyLangStyle();                 // font + RTL direction follow the switch immediately
        polishWatchToken = null;
        polishHotSwapped = false;
        fetchGen++;
        fetching = false;
        cues = new ArrayList<>();
        setState("fetching", Langs.nativeOf(code) + " උපසිරැසි ලබාගනිමින්… · fetching " + Langs.englishOf(code) + " subtitles…");
        fetchSinhala();
        if (subActions != null) subActions.setVisibility(View.GONE);
        if (langChips != null) langChips.setVisibility(View.GONE);
        if (extraActions != null) extraActions.setVisibility(View.GONE);
    }

    /** v2.2: the full registry sheet (the "+ More" chip). */
    private void showLangSheet() {
        String[] labels = new String[Langs.ALL.size()];
        for (int i = 0; i < Langs.ALL.size(); i++) {
            Langs.Lang l = Langs.ALL.get(i);
            labels[i] = l.nativeName + " (" + l.english + ")";
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Subtitle language · උපසිරැසි භාෂාව")
            .setItems(labels, (d, which) -> switchLang(Langs.ALL.get(which).code))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** v2.2: Spoken ⇄ Formal override (null = auto by genre). */
    private void toggleRegister() {
        registerOverride = registerOverride == null ? "spoken" : ("spoken".equals(registerOverride) ? "formal" : null);
        String label = registerOverride == null ? "auto (genre)" : registerOverride;
        setState(state, "Register: " + label + " — refetching… · රටාව: " + label);
        fetchGen++; fetching = false;
        fetchSinhala();
    }

    /**
     * v2.2 Option-C — the SAFE power-user bridge (zero automation, zero ToS
     * risk): build the cinema-grade prompt + a ≤40 KB chunk of the English
     * cues, copy it to the clipboard, and open the user's REAL browser on
     * AI Studio (a Custom-Tab-style ACTION_VIEW — NEVER a WebView). The user
     * pastes, gets the polished result, and imports it via the .srt loader.
     *
     * Two hard guarantees the audit asked for, both enforced here:
     *   · the clipboard payload can never exceed AI_STUDIO_MAX_BYTES (40 KB) —
     *     the budget is measured in UTF-8 bytes as we append, so a pathological
     *     cue list cannot produce a truncated/corrupt paste;
     *   · a device with no browser at all raises ActivityNotFoundException, which
     *     is caught by name and reported as "prompt copied, install a browser"
     *     instead of a stack trace or a crash.
     */
    private static final int AI_STUDIO_MAX_BYTES = 40 * 1024;   // Binder transaction-safe

    private void openAiStudioBridge() {
        StringBuilder prompt = new StringBuilder();
        int n = 0;
        try {
            String head = "You are a professional cinema subtitle localizer. Movie: "
                + (title == null ? "this movie" : title)
                + ". Polish the machine draft below into natural SPOKEN movie dialogue — "
                + "meaning, names and dashes kept, ≤42 chars/line.\n\n";
            prompt.append(head);
            for (Cue c : cues) {
                String en = c.en == null || c.en.isEmpty() ? c.si : c.en;
                if (en == null || en.isEmpty()) continue;
                String line = (n + 1) + "|EN: " + en + " | DRAFT: " + (c.si == null ? "" : c.si) + '\n';
                // measure the REAL payload, not the character count
                if (java.nio.charset.StandardCharsets.UTF_8.encode(prompt + line).remaining() > AI_STUDIO_MAX_BYTES) break;
                prompt.append(line);
                n++;
            }
        } catch (Throwable t) {
            Diag.w("subs", "ai studio prompt build: " + t);
        }
        try {
            String payload = prompt.toString();
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("AI Studio prompt (" + n + " cues)", payload));
            }
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://aistudio.google.com/")));
                setState(state, "✅ " + n + " cues copied — paste into AI Studio, then 📄 Local .srt to import · "
                    + n + " පේළි පිටපත් — AI Studio එකට paste කරන්න");
            } catch (android.content.ActivityNotFoundException anf) {
                // no browser installed — the prompt is already on the clipboard, so the
                // user can still paste it anywhere; say exactly that instead of crashing
                Diag.w("subs", "ai studio bridge: no browser (" + anf + ")");
                setState(state, "✅ " + n + " cues copied — but this phone has no browser to open AI Studio · "
                    + n + " පේළි පිටපත් විය, නමුත් browser එකක් නැත");
            }
        } catch (Throwable e) {
            Diag.w("subs", "ai studio bridge: " + e);
            setState(state, "AI Studio bridge failed: " + e.getMessage());
        }
    }

    // ── gestures: tap = HUD, double-tap sides = ±seek-step, left/right edge swipe = brightness/volume ──
    // v3.1.0: the ±step and the swipe gestures come from the Playback settings
    // (Seek Step Duration 5/10/15/30 s · Swipe Gestures toggle).

    private void wireGestures(FrameLayout root) {
        final boolean swipes = AppPrefs.gestures(prefs);
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                long step = AppPrefs.seekStepMs(prefs);
                skip(e.getX() > root.getWidth() / 2f ? step : -step);
                return true;
            }
            @Override public boolean onScroll(MotionEvent d, MotionEvent u, float dx, float dy) {
                if (!swipes) return false;                       // gestures OFF in Settings
                if (Math.abs(dy) < Math.abs(dx) || Math.abs(dy) < dp(24)) return false;
                boolean right = d.getX() > root.getWidth() / 2f;
                if (right) volume(dy);
                else brightness(dy);
                return true;
            }
        });
        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE)
                scheduleHudHide();
            return gd.onTouchEvent(e);
        });
    }

    private void volume(float dy) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int step = Math.round(-dy / dp(28));
            int v = Math.max(0, Math.min(max, cur + step));
            if (v != cur) am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            flash("🔊 " + Math.round(v * 100f / max) + "%");
        } catch (Throwable ignored) { }
    }

    private void brightness(float dy) {
        try {
            float cur = getWindow().getAttributes().screenBrightness;
            if (cur < 0) cur = 0.5f;
            float v = Math.max(0.02f, Math.min(1f, cur - dy / dp(220)));
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = v;
            getWindow().setAttributes(lp);
            flash("☀ " + Math.round(v * 100) + "%");
        } catch (Throwable ignored) { }
    }

    private void flash(String s) {
        notice.setText(s);
        notice.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideNotice);
        main.postDelayed(hideNotice, 700);
    }

    private final Runnable hideNotice = () -> notice.setVisibility(View.GONE);

    // ── controls ──

    void toggle() {
        if (player == null) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
        updateSnapshot();
        if (!player.getPlayWhenReady()) showControls();
    }

    void skip(long ms) {
        if (player == null) return;
        player.seekTo(Math.max(0, player.getCurrentPosition() + ms));
        flash((ms > 0 ? "+" : "") + ms / 1000 + " s");
        updateSnapshot();
    }

    void showControls() {
        hudVisible = true;
        hud.setVisibility(View.VISIBLE);
        scheduleHudHide();
    }

    void toggleControls() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); return; }
        if (hudVisible) { hudVisible = false; hud.setVisibility(View.GONE); }
        else showControls();
    }

    private void togglePanel() {
        boolean open = panel.getVisibility() == View.VISIBLE;
        panel.setVisibility(open ? View.GONE : View.VISIBLE);
        if (!open) showStatus();
    }

    private void scheduleHudHide() {
        main.removeCallbacks(hudHide);
        main.postDelayed(hudHide, AppPrefs.hudHideMs(prefs));   // v3.1.0: 2/4/6 s from Playback settings (was fixed 3.5 s)
    }

    private final Runnable hudHide = new Runnable() {
        @Override public void run() {
            if (player != null && player.getPlayWhenReady() && !dragging
                && (panel == null || panel.getVisibility() != View.VISIBLE)) {
                hudVisible = false;
                hud.setVisibility(View.GONE);
            }
        }
    };

    private void nudge(long d) {
        subDelayMs -= d;   // +1s shows subs a second later, like AnantPlay's Timing
        flash((d > 0 ? "+" : "") + (d / 1000f) + " s · " + (subDelayMs == 0 ? "0" : String.format(Locale.US, "%+.1fs", -subDelayMs / 1000f)));
    }

    private void showError(String msg) {
        errorBox.setText("▶ Playback error\n\n" + msg + "\n\nBack returns to AnantPlay; the cast stays connected.");
        errorBox.setVisibility(View.VISIBLE);
        setState("error", msg);
    }

    // ── v2.3.0: stream-failure diagnostics (the WARP/ISP-block honest answer) ──

    /** Last known good position, for the retry button. */
    private volatile long retryAtMs = 0;

    /**
     * When playback fails, check whether the stream HOST is ISP-blocked: compare the
     * system resolver against DoH (Cloudflare 1.1.1.1 / AdGuard). AnantPlay would have
     * played this same URL through its phone-side WARP proxy (PlayerActivity.java:1079
     * + Warp.proxyFor) — we cannot ship WARP, so we give the honest diagnosis and a
     * one-tap retry that re-resolves through DoH-clean DNS where possible.
     * Runs on a background thread; updates the error box on the main thread.
     */
    private void diagnoseStreamFailure(PlaybackException e, long posMs) {
        retryAtMs = Math.max(0, posMs);
        if (!AppPrefs.dohDiag(prefs)) return;   // v3.1.0: DoH diagnostics toggle OFF
        final String host = hostOf(url);
        if (host == null || host.isEmpty()) return;
        final int code = e == null ? -1 : e.errorCode;
        // Only network-ish failures are worth a DNS diagnosis (403/404 = server-side)
        boolean networkish = code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            || code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            || code == PlaybackException.ERROR_CODE_TIMEOUT;
        if (!networkish) return;
        new Thread(() -> {
            try {
                DohResolver.Verdict v = DohResolver.blocked(host);
                Diag.i("doh", "verdict for " + host + ": suspect=" + v.suspect
                    + " system=[" + v.systemIps + "] doh=[" + v.dohIps + "]");
                if (v.suspect) {
                    main.post(() -> {
                        if (isFinishing() || finished) return;
                        showError(errorBox.getText().toString()
                            + "\n\n⚠ " + v.summaryBilingual);
                    });
                }
            } catch (Throwable t) {
                Diag.w("doh", "diagnose: " + t);
            }
        }, "si-doh").start();
    }

    /** v3.1.0 — the Auto-Resume store key for the playing URL. */
    private String resumeKey() {
        return "resume:" + Integer.toHexString(url == null ? 0 : url.hashCode());
    }

    /** v3.1.0 — persist the last position (called from the ticker ~1 Hz; also
     *  on destroy). Skips the last 15 s so a finished movie restarts fresh. */
    private void saveResumePoint() {
        try {
            if (player == null || finished || live) return;
            long pos = player.getCurrentPosition(), dur = player.getDuration();
            if (pos < 10000 || dur <= 0) return;
            if (pos > dur - 15000) { prefs.edit().remove(resumeKey()).apply(); return; }
            prefs.edit().putLong(resumeKey(), pos).apply();
        } catch (Throwable ignored) { }
    }

    /** The one-tap retry: rebuild the player at the last known position (fresh
     *  DataSource, same URL/headers — survives expired HLS tokens when the CDN
     *  re-issues them, and picks up any DoH-clean route). */
    private void retryPlayback() {
        errorBox.setVisibility(View.GONE);
        if (player != null) {
            try { retryAtMs = Math.max(retryAtMs, player.getCurrentPosition()); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        long at = retryAtMs;
        startAt = at;                    // resume where it died
        flash("↻ retrying from " + ReceiverServer.clock(at));
        startPlayback();
    }

    /** The host part of the playing URL (null-safe, no throw). */
    static String hostOf(String u) {
        if (u == null) return null;
        try {
            java.net.URL parsed = new java.net.URL(u);
            return parsed.getHost();
        } catch (Throwable t) {
            return null;
        }
    }

    /** v3.0.0 — the first http(s) URL inside a shared text blob (ACTION_SEND),
     *  or null. Whitespace-delimited; a trailing ')' or '"' is trimmed. */
    static String firstUrlIn(String text) {
        if (text == null) return null;
        int at = -1;
        for (String scheme : new String[]{"http://", "https://"}) {
            int p = text.indexOf(scheme);
            if (p >= 0 && (at < 0 || p < at)) at = p;
        }
        if (at < 0) return null;
        int end = at;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        String u = text.substring(at, end);
        while (u.endsWith(")") || u.endsWith("\"") || u.endsWith("'") || u.endsWith(",")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    // ── the local .srt/.zip picker (SAF — our OWN activity, zero accessibility) ──

    private void pickLocal() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(Intent.createChooser(i, getString(R.string.pick_srt)), 41);
        } catch (Throwable t) {
            Diag.w("pick", t.toString());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 41 || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                List<Cue> cues = SrtLoader.load(o.toByteArray(), u.getLastPathSegment());
                main.post(() -> onLocalCues(cues, String.valueOf(u.getLastPathSegment())));
            } catch (Exception e) {
                Diag.e("pick", "load: " + e);
                setState("error", "load failed: " + e.getMessage());
            }
        }, "si-pick").start();
    }

    // ── a tiny URL-fetch helper for the site's own subtitle files (subs extra) ──

    static byte[] httpGet(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    o.write(buf, 0, n);
                    if (o.size() > 4 * 1024 * 1024) throw new Exception("subtitle too large");
                }
                return o.toByteArray();
            }
        } finally { c.disconnect(); }
    }

}
