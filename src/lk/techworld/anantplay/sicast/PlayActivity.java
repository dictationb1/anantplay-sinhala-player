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
    private Typeface siFont;

    private String url, title, headersJson, cookie, mime;
    private long startAt;
    private boolean live, testMode, dragging, dashFlag;
    private int season, episode;
    private String imdb;

    private volatile List<Cue> cues = new ArrayList<>();
    private volatile long subDelayMs;                  // the ±nudge display offset
    private volatile boolean fetching, enFallbackOn;
    private volatile int fetchGen;                     // a re-cast invalidates the in-flight fetch
    private String state = "idle";                     // idle|fetching|ready|local|not-found|needs-key|error
    private String stateDetail = "";
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
        if (url != null) {   // manual play from MainActivity (a pasted link / a test)
            title = title == null ? url : title;
            return;
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
        if (newCast == null && newUrl == null && !newTest) return;   // takeover / re-delivery
        String oldCast = getIntent().getStringExtra("castJson");
        setIntent(i);
        if (newCast != null && newCast.equals(oldCast == null ? "" : oldCast)) return;   // same cast again
        Diag.i("player", "re-targeted by a new cast — reloading");
        if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
        cues = new ArrayList<>();
        enFallbackOn = false;
        subDelayMs = 0;
        parseIntent();
        if (url != null) {
            startPlayback();
            fetchSinhala();
        }
    }

    @Override
    protected void onDestroy() {
        finished = true;
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

    /** Android 12+: let the system enter PiP itself (no onUserLeaveHint race). */
    private void armAutoPip() {
        if (Build.VERSION.SDK_INT < 31) return;
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
                    showError(e.getErrorCodeName() + (e.getCause() == null ? "" : " · " + e.getCause().getMessage()));
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

    /** The subtitle pipeline, tuned to the deployed worker's memory-mode reality
     *  (jobs live per-isolate): poll the DETERMINISTIC /sub/<token>.srt endpoint
     *  (200 .srt | 202 progress | 404 → re-request /subtitles to resume), never
     *  the isolate-sensitive /api/paired path. The .srt is all our timeline
     *  renderer needs — cue-lock pairing was the overlay era's requirement. */
    private void fetchSinhala() {
        if (fetching || testMode) return;
        fetching = true;
        final int myGen = ++fetchGen;   // a re-cast bumps this: the in-flight fetch goes stale
        new Thread(() -> {
            try {
                boolean series = episode > 0 || season > 0;
                String type = series ? "series" : "movie";
                String key = type + ":t:" + (title == null ? "" : title.toLowerCase(Locale.ROOT)) + ":" + season + ":" + episode;
                List<Cue> cached = store.load(key);
                if (cached != null) {
                    applyCues(cached, "cached");
                    return;
                }
                setState("fetching", "worker…");
                String id = imdb;
                if (id == null || id.isEmpty()) {
                    Map<String, Object> r = api.resolve(title, series, season, episode);
                    if (r == null) {
                        setState("not-found", "Cinemeta couldn't match \"" + title + "\" — try the title override");
                        fallbackEnglishTrack();
                        return;
                    }
                    id = String.valueOf(r.get("imdb"));
                }
                for (int round = 0; round < 4; round++) {
                    Map<String, Object> job = api.subtitles(type, id, season, episode, title);
                    String status = String.valueOf(job.get("status"));
                    if ("needs-key".equals(status)) {
                        setState("needs-key", "සිංහල උපසිරැසි නැහැ — Worker එකේ `wrangler secret put GEMINI_API_KEY` කරන්න");
                        fallbackEnglishTrack();
                        return;
                    }
                    if ("not-found".equals(status)) {
                        setState("not-found", "මෙම තේමාවට උපසිරැසි හමු නොවීය — local .srt loader එක බාවිතා කරන්න");
                        fallbackEnglishTrack();
                        return;
                    }
                    Map<String, Object> si = (Map<String, Object>) MiniJson.objGet(job, "si");
                    String token = si == null ? null : String.valueOf(si.get("token"));
                    if ("done".equals(status) && token != null && !token.isEmpty()) {
                        String text = api.srt(token);   // 200 straight away
                        List<Cue> got = SrtLoader.load(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                        if (myGen != fetchGen) return;   // re-cast happened meanwhile
                        applyCues(got, String.valueOf(job.get("source")));
                        store.save(key, got);            // save THIS list, not the shared field
                        return;
                    }
                    if ("translating".equals(status) && token != null && !token.isEmpty()) {
                        // poll the deterministic token URL: 202 progress → 200 .srt
                        for (int poll = 0; poll < 240; poll++) {
                            try {
                                String body = api.srt(token);
                                if (body.trim().startsWith("{")) {
                                    Map<String, Object> p = null;
                                    try { p = Cue.statusOf(body); } catch (Exception ignored) { }
                                    if (p != null && "translating".equals(p.get("status"))) {
                                        setState("fetching", BridgeApi.progressText(p));
                                        SystemClock.sleep(4000);
                                        continue;
                                    }
                                }
                                List<Cue> got = SrtLoader.load(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                                if (myGen != fetchGen) return;   // re-cast happened meanwhile
                                applyCues(got, String.valueOf(job.get("source")));
                                store.save(key, got);            // save THIS list, not the shared field
                                return;
                            } catch (BridgeApi.HttpException he) {
                                if (he.status == 404) break;      // isolate miss → /subtitles again resumes it
                                if (he.status == 429) {
                                    // Gemini quota: never spin silently — say it, back off, keep going
                                    Diag.w("subs", "worker 429 (quota) at poll " + poll + " — backing off 15 s");
                                    setState("fetching", BridgeApi.explain(429));
                                    SystemClock.sleep(15000);
                                    continue;
                                }
                                if (he.status == 410 || he.status >= 500) {
                                    Diag.e("subs", "worker HTTP " + he.status + ": " + BridgeApi.brief(he.body, 300));
                                    setState("error", BridgeApi.explain(he.status)
                                        + " · " + BridgeApi.brief(he.body, 90));
                                    fallbackEnglishTrack();
                                    return;
                                }
                                SystemClock.sleep(4000);
                            }
                        }
                        continue;   // token went cold — re-ensure the job on a warm isolate
                    }
                    setState("error", "worker status: " + status);
                    fallbackEnglishTrack();
                    return;
                }
                setState("error", "subtitles did not become ready — try Refetch");
                fallbackEnglishTrack();
            } catch (BridgeApi.HttpException he) {
                // 429 (Gemini quota) / 5xx / 401 — said out loud in the status bar, logged
                Diag.e("subs", "worker HTTP " + he.status + ": " + BridgeApi.brief(he.body, 300));
                setState("error", BridgeApi.explain(he.status) + " · " + BridgeApi.brief(he.body, 90));
                fallbackEnglishTrack();
            } catch (java.io.IOException io) {
                Diag.w("subs", "network: " + io);
                setState("error", BridgeApi.explain(io));
                fallbackEnglishTrack();
            } catch (Exception ex) {
                Diag.e("subs", "fetch: " + ex);
                setState("error", BridgeApi.explain(ex));
                fallbackEnglishTrack();
            } finally {
                fetching = false;
            }
        }, "si-subs").start();
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
        setState("ready", loaded.size() + " cues · " + how + (hasEn ? "" : " · human Sinhala"));
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
                subView.setTypeface(Typeface.DEFAULT_BOLD);
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
                    subView.setTypeface(siFont);
                    subView.setText(text == null ? "" : text);
                    subView.setVisibility(View.VISIBLE);
                } else {
                    subView.setVisibility(View.GONE);
                }
                if (hudVisible) {
                    timeNow.setText(ReceiverServer.clock(player.getCurrentPosition()));
                    if (!dragging) seek.setProgress((int) Math.max(0, player.getCurrentPosition()));
                }
            }
            updateSnapshot();
            main.postDelayed(this, 250);
        }
    };

    private void startTicker() { main.post(ticker); }

    private void setState(String s, String detail) {
        state = s;
        stateDetail = detail == null ? "" : detail;
        main.post(this::showStatus);
    }

    private void showStatus() {
        if (hudStatus != null) {
            boolean good = "ready".equals(state) || "local".equals(state);
            hudStatus.setText((good ? "✓ " : "… ") + stateDetail);
            hudStatus.setTextColor(good ? 0xFF8CFF8C : 0xFF9FB0D8);
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // the UI — built in code (the project's own idiom; no XML layouts anywhere)
    // ───────────────────────────────────────────────────────────────────────────

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // PlayerActivity.java:157
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.getHolder().setFormat(PixelFormat.OPAQUE);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        // the subtitle view — bottom center, Sinhala font, mask, like AnantPlay's own (PlayerActivity.java:396-401)
        subView = new TextView(this);
        subView.setTypeface(siFont);
        subView.setGravity(Gravity.CENTER);
        subView.setTextColor(Color.WHITE);
        subView.setTextSize(prefs.getInt("fontSp", 22));
        applyMask(prefs.getInt("maskPct", 85));
        subView.setShadowLayer(dp(4), 0, 0, 0xFF000000);
        subView.setPadding(dp(10), dp(4), dp(10), dp(4));
        subView.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = dp(24);
        root.addView(subView, sl);

        notice = new TextView(this);
        notice.setTextColor(Color.WHITE);
        notice.setTextSize(14);
        notice.setBackgroundResource(0x88000000);
        notice.setPadding(dp(14), dp(8), dp(14), dp(8));
        notice.setGravity(Gravity.CENTER);
        notice.setVisibility(View.GONE);
        root.addView(notice, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

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
        row.addView(timeNow, new LinearLayout.LayoutParams(-2, -2));
        View minus = icon("−10", 14, v -> skip(-10000));
        row.addView(minus, new LinearLayout.LayoutParams(dp(58), dp(44)));
        playPause = icon("⏸", 20, v -> toggle());
        row.addView(playPause, new LinearLayout.LayoutParams(dp(54), dp(44)));
        View plus = icon("+10", 14, v -> skip(10000));
        row.addView(plus, new LinearLayout.LayoutParams(dp(58), dp(44)));
        timeTotal = new TextView(this);
        timeTotal.setTextColor(0xFFAAAAAA);
        timeTotal.setTextSize(13);
        timeTotal.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(timeTotal, new LinearLayout.LayoutParams(-2, -2, 1f));
        View subs = icon("සිං", 14, v -> togglePanel());
        row.addView(subs, new LinearLayout.LayoutParams(dp(58), dp(44)));
        bottom.addView(row);
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
        p.addView(step("−0.25s", "+0.25s", () -> nudge(-250), () -> nudge(250)));

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

    // ── gestures: tap = HUD, double-tap sides = ±10 s, left/right edge swipe = brightness/volume ──

    private void wireGestures(FrameLayout root) {
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                skip(e.getX() > root.getWidth() / 2f ? 10000 : -10000);
                return true;
            }
            @Override public boolean onScroll(MotionEvent d, MotionEvent u, float dx, float dy) {
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
        main.postDelayed(hudHide, 3500);   // AnantPlay's own hide window (PlayerActivity.java:1421)
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

    private void fontSp(int d) {
        int sp = Math.max(14, Math.min(40, prefs.getInt("fontSp", 22) + d));
        prefs.edit().putInt("fontSp", sp).apply();
        subView.setTextSize(sp);
    }

    private void mask(int d) {
        int pct = Math.max(0, Math.min(100, prefs.getInt("maskPct", 85) + d));
        prefs.edit().putInt("maskPct", pct).apply();
        applyMask(pct);
        flash("Mask " + pct + "%");
    }

    private void applyMask(int pct) {
        subView.setBackgroundColor((Math.max(0, Math.min(100, pct)) * 255 / 100) << 24);
    }

    private void nudge(long d) {
        subDelayMs -= d;   // +1s shows subs a second later, like AnantPlay's Timing
        flash((d > 0 ? "+" : "") + (d / 1000f) + " s · " + (subDelayMs == 0 ? "0" : String.format(Locale.US, "%+.1fs", -subDelayMs / 1000f)));
    }

    private void showError(String msg) {
        errorBox.setText("▶ Playback error\n\n" + msg + "\n\nBack returns to AnantPlay; the cast stays connected.");
        errorBox.setVisibility(View.VISIBLE);
        setState("error", msg);
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
