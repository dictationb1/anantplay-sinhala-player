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
import android.view.ScaleGestureDetector;
import android.webkit.CookieManager;
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
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
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
 *   · the Sinhala font (NotoSansSinhala), size, mask and position are ours to style;
 *   · AnantPlay's Remote (and its own player) keep working: /status, /do and the
 *     resume-on-stop loop are driven from the SAME timeline;
 *   · no AccessibilityService, no overlay permission for the video itself, and while
 *     a movie plays WE are the foreground app — the one state HyperOS never kills.
 *
 * v3.2.0 — THE FLAWLESS SINHALA SUBTITLE + HUD OVERHAUL (the field screenshot's
 * five visible defects, all fixed at the source):
 *
 *   1. EDGE-TO-EDGE INSETS (the "ArrowTV Player" title sat UNDER the 20:52 status
 *      bar clock): the root now has a real OnApplyWindowInsetsListener — the HUD's
 *      top bar pads by statusBars()|displayCutout() and the bottom control bar by
 *      navigationBars(), so system chrome and our controls can never overlap.
 *   2. ONE-PLACE TYPEFACE (the garbled-bold bug): applyLangStyle() is now the ONLY
 *      place that touches subView's typeface, and it applies BOLD the Android-correct
 *      way — Typeface.create(sinhalaFamily, bold?BOLD:NORMAL) — never the
 *      setTypeface(tf, style) second-arg form that silently dropped the studio bold
 *      on every language change / cue render.
 *   3. SCRIPT-RATIO VALIDATION (wrong language shown as "සිංහල"): a fetched cue
 *      list is validated by SubLangDetect.looksSinhala before it is ever announced
 *      as Sinhala; a non-Sinhala list is routed to FreeSubTranslator (the on-device
 *      keyless engine) instead of mislabeled.
 *   4. THE STATUS PILL IS A SEARCH BUTTON (raw JSON banners): junk titles
 *      ("ArrowTV Player"…) never reach Cinemeta — ONE subtle pill opens the 1-tap
 *      movie-title search dialog (type a name + optional S/E → /api/resolve →
 *      instant Sinhala subs).
 *   5. URL-PASTED .srt/.zip SUBS + the free translator everywhere: the subs panel
 *      accepts a pasted subtitle URL (auto-downloads, unzips, detects language,
 *      translates when not Sinhala) — plus "Free translate now" for whatever is
 *      showing. Worker 404 "job not on this isolate" is retried silently, never
 *      painted across the video. */
@UnstableApi   // ExoPlayer's API surface (like AnantPlay's own PlayerActivity)
public class PlayActivity extends Activity {

    static final String TEST_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";

    // ── the cast protocol surface (read by ReceiverService, never null-safe blocks) ──
    private static volatile PlayActivity currentInstance;
    private static volatile String snapshot = ReceiverServer.idleJson();

    public static PlayActivity current() { return currentInstance; }
    /** /status — the never-blocking snapshot the player keeps fresh. */
    public static String statusSnapshot() { return snapshot; }

    // ══ v3.4.6.c — THE BRIDGE SESSION MIRROR ═════════════════════════════════
    // The Mornye Cinema Player (Media3TextureBridge) also owns /status while a
    // session is live: ReceiverService answers the cast remote from this
    // static snapshot even though PlayActivity itself is not on screen.
    // adoptBridgeSession(title,url) claims it; (null,null) releases it.
    private static volatile String bridgeTitle;
    private static volatile long bridgePos;
    private static volatile long bridgeDur;
    private static volatile boolean bridgePlaying;

    /** Claim the /status surface for the in-shell bridge player (null title = release). */
    static void adoptBridgeSession(String title, String url) {
        bridgeTitle = title;
        bridgePos = 0;
        bridgeDur = 0;
        bridgePlaying = false;
    }

    /** The bridge's 4 Hz position refresh (called from its frame pump). */
    static void bridgePosition(long pos, long dur, boolean playing) {
        if (bridgeTitle == null) return;
        bridgePos = pos;
        bridgeDur = dur;
        bridgePlaying = playing;
        if (currentInstance == null) {
            // No PlayActivity is alive — the bridge IS the player the cast
            // remote talks to; publish its snapshot directly.
            snapshot = ReceiverServer.statusJson(
                    bridgeTitle, bridgePos, bridgeDur, bridgePlaying, false);
        }
    }

    /** True while the in-shell bridge player holds a live session. */
    static boolean bridgeSessionLive() { return bridgeTitle != null; }

    /**
     * v3.4.6.c — THE TRAMPOLINE. A cast /play, a DLNA SetAVTransportURI, an
     * ACTION_VIEW/SEND share or a Test tap now routes INTO the Flutter
     * shell's Mornye Cinema Player (PlayerScreen over Media3TextureBridge).
     * The payload is handed to ShellRoot through the anantplay/shell/events
     * EventChannel and the MainActivity task is brought forward — see
     * MainActivity.shellRoute. PlayActivity's own engine remains the
     * fallback ONLY for a failed bridge texture creation.
     */
    static void trampolineToShell(android.content.Context c, String url, String title,
                                  String headersJson, boolean test) {
        try {
            MainActivity.shellRoute(c, "play", url, title, headersJson, test, 0L);
        } catch (Throwable t) {
            Diag.w("player", "trampoline: " + t);
        }
    }

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
    private ScrubBar seek;
    private CapsuleBar brightCapsule, volCapsule;
    private View playPause;
    private View spinner;
    private LinearLayout hud, panel, sheet;
    private LinearLayout hudTopBar, hudBottomBar;
    private TextView errorBox;
    /** v2.2: the language chip row + register/Option-C row (shown with the pill). */
    // v3.4.2 — langChips / extraActions / subActions are DELETED: their content moved
    // into the Subtitles sheet, off the video surface entirely.

    /** v2.1: the never-silent subtitle status pill + its 1-tap action row.
     *  v3.2.0: the pill is also the JUNK-TITLE search entry (tapping it on a
     *  junk-title state opens the movie-title search dialog). */
    private TextView statusPill;

    private Typeface siFont;
    /** v3.4.0 - the REAL 600 weight (was a synthetic bold of the 400 face). */
    private Typeface siFontSemi;

    private String url, title, headersJson, cookie, mime;
    /** v3.3.0 — the cast's top-level ua/referer/origin (Cast.describe forwards them). */
    private String uaHeader, refererHeader, originHeader;
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
    // v3.4.2 — Continue watching: the millisecond to resume at, applied once on ready
    private long resumeMs = 0;
    private boolean resumeApplied = false;
    private boolean finished;
    private volatile boolean expectTakeover = true;   // cleared ~3 s after open: PiP waits for the hand-over
    /** v3.2.0 — the junk-title verdict for THIS playback (cached at parse). */
    private boolean titleIsJunk;

    // ── v3.3.0 — THE CINEMA PLAYER STATE ──────────────────────────────────────
    /** The black host the surface is CENTERED in (VideoViewport sizes the child). */
    private FrameLayout videoHost, subLayer;
    /** v3.3.1 — the root FrameLayout, kept so transient overlays (the ⏪/⏩
     *  double-tap ripples) mount on a FRAME parent, never on hudCenter's
     *  LinearLayout parent (the v3.3.0 ClassCastException). */
    private FrameLayout rootView;
    /** The subtitle pill column (below the top bar, never on top of it). */
    private LinearLayout pillBox, hudCenter, unlockPill;
    /** v3.3.1 — FrameLayout.LayoutParams, because pillBox's parent is the root
     *  FrameLayout. This was a LinearLayout.LayoutParams in v3.3.0: every
     *  measure pass died in FrameLayout.onMeasure(FrameLayout.java:195). */
    private FrameLayout.LayoutParams pillLp;
    /** The five fixed, accessible bottom-dock controls. */
    private View aspectDockButton, speedDockButton;
    private View subtitleDockDot;
    private TextView speedChip;
    /** The compact "සිංහල ✓" badge in the top bar (ONE, never duplicated). */
    private TextView badgeSub;
    /** The gesture feedback pill + the double-tap ⏪/⏩ ripples. */
    private TextView gesturePill, dblLeft, dblRight;
    /** The decoded video geometry (Media3 onVideoSizeChanged). */
    private int videoW, videoH;
    private float videoPar = 1f;
    private int aspectMode = VideoViewport.FIT;
    /** v3.4.1 — proof that at least one cue has actually been painted this track. */
    private boolean firstCueRendered;
    /** v3.4.1 — one-shot: the playhead is outside the cue range and we said so once. */
    private boolean cueRangeWarned;

    // ── v3.4.1 — LAYER 4: the stream's OWN captions, translated live ─────────
    /** True while Layer 4 is translating the embedded track line by line. */
    private volatile boolean liveTranslateOn;
    /** The English line currently on screen (so a late translation can replace it). */
    private volatile String liveLine = "";
    /** line → Sinhala, an LRU of 400 lines: zero re-translation, zero lag on rewind. */
    private final java.util.Map<String, String> liveCache =
        new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) {
                return size() > 400;
            }
        };
    /** Lines with a translation in flight (never two requests for the same line). */
    private final java.util.Set<String> livePending =
        java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** Lock: every touch is swallowed except the tiny unlock pill. */
    private boolean locked;
    /** The measured window insets (status bar / gesture bar), in px. */
    private int statusInset, navInset;
    /** The 403 recovery leg: one automatic retry, then the honest error card. */
    private boolean triedHeaderRecovery;
    private Map<String, String> httpHeaders;

    // ── v3.4.1 — the DLNA relay ladder + the TRANSITIONING hold (defects #1 & #2) ──
    /** The decoded AnantPlay relay for THIS playback (null / !isRelay for a normal stream). */
    private StreamHttpConfig.Relay relayInfo;
    /** 0 = not on the ladder, 1 = clean relay, 2 = decoded-upstream origin, 3 = direct unwrap. */
    private int relayStage;
    /**
     * Uptime millis until which {@link #statusJson()} must answer {@code {"loading":true}}
     * (→ UPnP {@code TRANSITIONING}) instead of the media shape.
     *
     * <p>WHY THIS EXISTS (the field takeover at 237.250): a fresh Media3 player is
     * {@code STATE_IDLE} with {@code playWhenReady == false}, so the v3.4.0 status JSON
     * looked like "the TV stopped" and {@code DlnaEndpoint.transportState()} mapped it to
     * {@code NO_MEDIA_PRESENT} — AnantPlay's remote then tore the cast down and popped its
     * own player over ours. The hold keeps the remote on {@code TRANSITIONING} through
     * buffering, every automatic 403 stage, and the 15 s startup grace, so a retry can
     * never look like a stop.
     */
    private long holdUntil;
    /** The v3.4.1 startup / per-stage grace: long enough for a 403 ladder to finish. */
    private static final long HOLD_MS = 15000L;
    /** The error card + the last exact message (for 📋 Copy). */
    private LinearLayout errorCard;
    private String lastErrorMsg = "";

    // ══ v3.4.3 — CAST SESSION CORRELATION (Replit QA-0016 / QA-0024) ══════════
    /** The duplicate-retry window: the same Cast/DLNA payload inside this many
     *  milliseconds is a RETRY, not a new stream (official AnantPlay re-posts /play
     *  and the DLNA SetAVTransportURI arrives twice on some renderers). */
    static final long DUP_CAST_MS = CastPolicy.DUP_CAST_MS;

    /** Monotonic across the whole process: every accepted cast opens a new session. */
    private static final java.util.concurrent.atomic.AtomicInteger CAST_SESSIONS =
        new java.util.concurrent.atomic.AtomicInteger(0);

    /** THIS playback's session id (0 = not yet correlated). */
    private volatile int castSessionId;
    /** The last accepted cast payload identity + when it landed. */
    private volatile String lastCastKey = "";
    private volatile long lastCastAtMs;

    /**
     * v3.4.3 — IS THIS CAST A DUPLICATE RETRY? (pure, so the lab proves it)
     *
     * <p>The identity is the payload itself (cast JSON, else URL). The SAME identity
     * inside {@link #DUP_CAST_MS} is the remote retrying itself — v3.4.2 released the
     * live player and rebuilt it for that, which is the audible "double audio / stutter
     * on every cast" the audit recorded. A different identity, or the same one after
     * the window, is a real new cast.
     */
    static boolean isDuplicateCast(String key, long nowMs, String lastKey, long lastAtMs) {
        // the rule itself lives in the pure CastPolicy so the lab can prove it
        return CastPolicy.isDuplicateCast(key, nowMs, lastKey, lastAtMs);
    }

    /** The payload identity used for duplicate detection (cast JSON, else URL). */
    private static String castKeyOf(String castJson, String url) {
        if (castJson != null && !castJson.trim().isEmpty()) return "c:" + castJson.trim();
        if (url != null && !url.trim().isEmpty()) return "u:" + url.trim();
        return "";
    }

    /**
     * Accept or reject an incoming cast for THIS activity instance.
     *
     * <p>On acceptance it opens a NEW session id — which is what immediately makes every
     * in-flight subtitle fetch, recovery retry and delayed task from the previous
     * session stale (they all compare {@code fetchGen} / the session they started in).
     */
    private boolean acceptCast(String key) {
        long now = android.os.SystemClock.uptimeMillis();
        if (isDuplicateCast(key, now, lastCastKey, lastCastAtMs)) {
            Diag.i("player", "duplicate cast ignored (retry within " + DUP_CAST_MS + " ms)");
            Troubleshooter.record("CAST", "duplicate cast retry ignored — session " + castSessionId);
            return false;
        }
        lastCastKey = key;
        lastCastAtMs = now;
        castSessionId = CAST_SESSIONS.incrementAndGet();
        Diag.i("player", "cast session " + castSessionId + " accepted");
        Troubleshooter.record("CAST", "session " + castSessionId + " accepted");
        return true;
    }

    /** This playback's cast session id (0 before the first accepted cast). */
    int castSessionId() { return castSessionId; }

    /** The path part of a URL ("…/x/y.m4s"), for a log line that carries no query. */
    private static String pathOf(String url) {
        if (url == null) return "";
        int se = url.indexOf("://");
        int slash = url.indexOf('/', se < 0 ? 0 : se + 3);
        if (slash < 0) return "/";
        int q = url.indexOf('?', slash);
        return q < 0 ? url.substring(slash) : url.substring(slash, q);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // lifecycle
    // ───────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        currentInstance = this;
        snapshot = ReceiverServer.loadingJson();
        prefs = getSharedPreferences("sicast", MODE_PRIVATE);
        // v3.4.3 — the player's chrome follows the UI language too (zero Sinhala leaks).
        Chrome.setUiLang(AppPrefs.uiLang(prefs));
        // v2.2: the user's default subtitle language (si default)
        activeLang = Langs.isKnown(prefs.getString("defaultLang", null)) ? prefs.getString("defaultLang", Langs.DEFAULT) : Langs.DEFAULT;
        store = new SubtitleStore(getFilesDir());
        api = new BridgeApi(prefs.getString("workerUrl", BridgeApi.DEFAULT_WORKER_URL));
        // v3.4.0 - real weight 400 from the subset; falls back safely, never crashes.
        siFont = UiTokens.si(this, false);
        siFontSemi = UiTokens.si(this, true);
        parseIntent();
        // v3.4.3 — the FIRST cast of this instance opens session 1 (a later /play retry
        // is then correctly classified as a duplicate instead of rebuilding the player).
        acceptCast(castKeyOf(getIntent() == null ? null : getIntent().getStringExtra("castJson"),
            url));
        recoverTitleFromCast();   // v3.4.1 — a junk title may still carry the real slug
        if (url == null) {          // nothing playable arrived — leave cleanly, no half UI
            finish();
            return;
        }
        buildUi();
        if (titleIsJunk) promptJunkTitle();   // v3.4.1 — the 1-tap intro prompt
        startPlayback();
        startTicker();
        applyVolumeBoost();
        armSleepTimer(AppPrefs.sleepTimerMs(prefs));
        // v3.1.2 — the Troubleshooter timeline: the launch source + the open fact
        Troubleshooter.record("PLAYER", "launch source=" + launchSource()
            + " · title=\"" + (title == null ? "" : title) + "\" · state=open");
        // v3.0.0 — the never-silent launch pill: the user sees the subtitle
        // pipeline is ALIVE from the very first frame, before any network
        // round-trip completes ("✨ … ලබාගනිමින්…" → "✓ සූදානම්" once cues land).
        // v3.2.0 — a JUNK title (the "ArrowTV Player" field case) never goes to
        // Cinemeta: the pill immediately becomes the 1-tap title-search entry.
        if (!testMode) {
            if (titleIsJunk) {
                setState("not-found", Chrome.pick("චිත්‍රපටයේ නම හඳුනාගත නොහැක — තට්ටු කර සොයන්න · tap to search the movie name"));
            } else {
                setState("fetching", Chrome.pick("සිංහල උපසිරැසි ලබාගනිමින්… · fetching Sinhala subtitles…"));
            }
        }
        main.postDelayed(() -> { expectTakeover = false; armAutoPip(); }, 3000);   // PiP only after the hand-over
        if (!titleIsJunk && (testMode || prefs.getBoolean("autoFetch", true))) fetchSinhala();
        Diag.i("player", "open: " + title + " · " + url);
    }

    /** v3.1.2 — the structured launch source for the Troubleshooter timeline. */
    private String launchSource() {
        Intent i = getIntent();
        if (i == null) return "unknown";
        if (i.getBooleanExtra("test", false)) return "test-player";
        String u = i.getStringExtra("url");
        if (u != null) return "manual-url";
        String action = i.getAction();
        if (Intent.ACTION_VIEW.equals(action)) return "webview";
        if (Intent.ACTION_SEND.equals(action)) return "share";
        if (i.getStringExtra("castJson") != null) return "cast";
        return "unknown";
    }

    private void parseIntent() {
        Intent i = getIntent();
        testMode = i.getBooleanExtra("test", false);
        title = i.getStringExtra("title");
        url = i.getStringExtra("url");
        resumeMs = Math.max(0, i.getLongExtra("startMs", 0));   // v3.4.2 Continue watching
        if (testMode) {
            url = TEST_URL;
            if (title == null) title = "Sinhala Player Test";
            applyTestCues();
            return;
        }
        if (url != null) {   // manual play from MainActivity / the WebView sniffer
            title = title == null ? url : title;
            headersJson = i.getStringExtra("headers");   // v3.0.0: the sniffer's Referer/Cookie/UA
            cookie = i.getStringExtra("cookie");
            uaHeader = i.getStringExtra("ua");
            refererHeader = i.getStringExtra("referer");
            originHeader = i.getStringExtra("origin");
            titleIsJunk = TitleCleaner.isJunkTitle(title);
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
                titleIsJunk = TitleCleaner.isJunkTitle(title);
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
                titleIsJunk = TitleCleaner.isJunkTitle(title);
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
                // v3.3.0 — AnantPlay's cast can carry the UA/Referer/Origin either inside
                // the nested "headers" object OR as top-level keys (Cast.describe copies
                // the phone player's intent extras verbatim, and both shapes occur in
                // the wild). Read BOTH; StreamHttpConfig merges them.
                uaHeader = MiniJson.str(m, "ua");
                if (uaHeader == null) uaHeader = MiniJson.str(m, "userAgent");
                refererHeader = MiniJson.str(m, "referer");
                if (refererHeader == null) refererHeader = MiniJson.str(m, "referrer");
                originHeader = MiniJson.str(m, "origin");
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
                titleIsJunk = TitleCleaner.isJunkTitle(title);
            }
        }
        if (url == null) {   // nothing playable arrived
            Diag.e("player", "no url in intent");
            finish();
        }
    }

    /** v3.4.2 — persist the playhead whenever the player leaves the foreground. */
    @Override
    protected void onPause() {
        super.onPause();
        saveHistory();
        if (boosting) boostOff();
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
        // ══ v3.4.3 — P0 FIX #5: STRICT IDEMPOTENCE FOR A CAST RETRY (QA-0016) ══════
        // AnantPlay's Remote re-posts the SAME /play payload (and a DLNA renderer can
        // send SetAVTransportURI twice). v3.4.2 released the live player and rebuilt it
        // for that retry — a visible stutter, a fresh subtitle fetch and a new session.
        // A retry of the SAME payload inside 2 s is now a no-op: the stream that is
        // already playing stays untouched.
        if (!acceptCast(castKeyOf(newCast, newUrl))) return;
        // v3.1.0 (Bug 2 hardening): a malformed re-cast must never kill the
        // player that is already running — parse defensively, keep the old
        // stream alive when the new payload does not produce a playable url.
        try {
            Diag.i("player", "re-targeted by a new cast (session " + castSessionId + ") — reloading");
            String prevUrl = url;
            // v3.4.3 — CANCEL EVERY TASK FROM THE OLDER SESSION FIRST (QA-0016): the
            // in-flight subtitle fetch, the Cinema-Polish re-check, the delayed HUD/PiP
            // armers and any pending recovery retry all belong to the previous cast.
            // Bumping fetchGen + clearing the handler queue is what makes "cancelled"
            // true rather than merely no-op'd by a flag.
            fetchGen++;
            fetching = false;
            polishWatchToken = null;
            polishHotSwapped = false;
            triedHeaderRecovery = false;
            relayStage = 0;
            holdUntil = 0;
            resumeApplied = false;
            // Clearing the queue kills the 4 Hz ticker too, so it is re-armed right
            // after (the HUD clock must keep running across a re-cast).
            if (main != null) { main.removeCallbacksAndMessages(null); startTicker(); }
            if (player != null) { try { player.release(); } catch (Throwable ignored) { } player = null; }
            cues = new ArrayList<>();
            enFallbackOn = false;
            subDelayMs = 0;
            parseIntent();
            recoverTitleFromCast();   // v3.4.1 — re-cast: recover the real name again
            if (url != null) {
                startPlayback();
                if (!titleIsJunk) fetchSinhala();
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
        saveHistory();       // v3.4.2 — the Home Continue-watching row learns this title
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
        if (sheet != null) sheet.setVisibility(View.GONE);    // v3.2.0: never a floating settings sheet
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

    /** Explicit PiP action; the separate Auto-PiP preference still controls auto-entry. */
    private void enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < 26) {
            flash("Picture-in-picture requires Android 8 or later");
            return;
        }
        try {
            enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder().build());
        } catch (Throwable t) {
            Diag.w("pip", "manual PiP: " + t);
            flash("Picture-in-picture is unavailable");
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
            // ══ v3.3.0 — THE 403 KILLER (see StreamHttpConfig's class docs for the
            // four line-by-line differences from official PlayerActivity.java:1087-1103).
            // 1. the headers are NORMALISED (browser UA, Origin derived from Referer,
            //    Accept, dedupe, hop-by-hop dropped) in ONE pure, lab-proven place;
            // 2. the UA goes through setUserAgent() — never into the property map, so
            //    nothing can silently overwrite it (official AnantPlay does the same);
            // 3. a ResolvingDataSource adds the WebView's cookie FOR EACH REQUEST'S OWN
            //    HOST (segments live on a different CDN host than the playlist);
            // 4. a 401/403/451 error triggers ONE automatic header-recovery retry.
            hold();   // v3.4.1 — never report "stopped" while the first frame loads
            relayInfo = StreamHttpConfig.relay(url);
            if (httpHeaders == null) {
                Map<String, String> extra = new LinkedHashMap<>();
                if (uaHeader != null) extra.put("User-Agent", uaHeader);
                if (refererHeader != null) extra.put("Referer", refererHeader);
                if (originHeader != null) extra.put("Origin", originHeader);
                httpHeaders = StreamHttpConfig.normalize(headersJson, cookie, url, extra);
            }
            // ══ v3.4.1 — THE RELAY LADDER (defect #1) ═══════════════════════════
            // This cast came from AnantPlay's DLNA relay (http://<lan-ip>:<port>/<b64>/…).
            // Stage 1 is a CLEAN pass-through: Chrome UA + Accept only. The v3.4.0 engine
            // sent `Origin: http://192.168.x.x:34783` here, which the relay discards on
            // arrival anyway (Chromecast.java:330-341) — so it could never have helped,
            // and it is the fake header the field log recorded.
            if (relayInfo.isRelay) {
                if (relayStage == 0) relayStage = 1;
                if (relayStage == 1) {
                    Map<String, String> clean = StreamHttpConfig.relayClean();
                    String ck = httpHeaders.get("Cookie");
                    if (ck != null && !ck.trim().isEmpty()) clean.put("Cookie", ck);
                    httpHeaders = clean;
                }
            }
            Map<String, String> headers = new LinkedHashMap<>(httpHeaders);
            String ua = headers.remove("User-Agent");
            if (ua == null || ua.trim().isEmpty()) ua = StreamHttpConfig.CHROME_UA;
            // ══ v3.4.3 — P0 FIX #1 (Replit QA-0017): THE COOKIE IS NEVER A GLOBAL DEFAULT ══
            // A DefaultHttpDataSource "default request property" is attached to EVERY
            // request the factory makes — playlist, segments, and the segments a
            // DIFFERENT CDN host serves. v3.4.2 therefore replayed the cast's Origin-A
            // Cookie to whatever Host B the manifest pointed at. The cookie is lifted
            // out of the defaults here and re-attached per request below, and only
            // when the request host really is the origin host.
            final String castCookie = headers.remove("Cookie");
            final String originUrl = url;                       // the host the cookie belongs to
            final boolean credentials = StreamHttpConfig.hasCredentials(headers)
                || (castCookie != null && !castCookie.trim().isEmpty());
            Troubleshooter.record("HTTP", (relayInfo.isRelay ? "relay stage " + relayStage :
                    triedHeaderRecovery ? "recovery" : "first") + " attempt · url="
                + hostOf(url) + " · headers=" + StreamHttpConfig.describe(httpHeaders)
                + " · cookie=" + (castCookie == null || castCookie.trim().isEmpty()
                    ? "none" : "origin-scoped to " + hostOf(originUrl)));

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setDefaultRequestProperties(headers)       // v3.4.3: NO Cookie in here, ever
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                // v3.4.3 — P0 FIX #2 (Replit QA-0018): an authenticated HTTPS request may
                // not be redirected across protocols, so it can never be silently
                // downgraded to plaintext HTTP with its Cookie/Authorization attached.
                // The plaintext LAN relay (http://192.168.x.x:port) keeps the ability to
                // upgrade to the real upstream, which is what makes it work at all.
                .setAllowCrossProtocolRedirects(
                    StreamHttpConfig.allowCrossProtocolRedirects(url, credentials));
            // v3.4.3 — THE PER-HOST COOKIE LAYER (the leak fix, in one place):
            //   · the WebView's own cookie for THIS request host, always (official
            //     PlayerActivity.java:1099-1103 behaviour, unchanged);
            //   · the cast's origin cookie ONLY when this request host is the validated
            //     origin host or a same-site suffix of it (StreamHttpConfig.scopedCookie),
            //     so Origin A's credential can never reach an unrelated Host B.
            final boolean viaRelay = relayInfo != null && relayInfo.isRelay;
            DataSource.Factory cookieLayer = new ResolvingDataSource.Factory(http, spec -> {
                try {
                    // v3.4.3 — QA-0025: a DASH/HLS segment the manifest referenced
                    // RELATIVELY lands on the relay host WITHOUT the base64 origin
                    // segment, so the relay cannot know what to fetch. Re-insert it.
                    if (viaRelay) {
                        String rebased = StreamHttpConfig.rebaseOntoRelay(url, spec.uri.toString());
                        if (rebased != null && !rebased.equals(spec.uri.toString())) {
                            spec = spec.withUri(Uri.parse(rebased));
                            Diag.i("player", "relay-relative segment re-based: "
                                + hostOf(rebased) + pathOf(rebased));
                        }
                    }
                    final String reqUrl = spec.uri.toString();
                    String ck = CookieManager.getInstance().getCookie(reqUrl);
                    String scoped = StreamHttpConfig.scopedCookie(reqUrl, originUrl, castCookie);
                    if (scoped.isEmpty()) {
                        if (ck == null || ck.isEmpty()) return spec;
                        return spec.withAdditionalHeaders(Collections.singletonMap("Cookie", ck));
                    }
                    String merged = (ck == null || ck.isEmpty()) ? scoped
                        : (ck.contains(scoped) ? ck : ck + "; " + scoped);
                    return spec.withAdditionalHeaders(Collections.singletonMap("Cookie", merged));
                } catch (Throwable t) { return spec; }
            });
            DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, cookieLayer);

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
                    // v3.4.1 — buffering IS a transition: keep the remote on TRANSITIONING
                    if (s == Player.STATE_BUFFERING) hold();
                    if (s == Player.STATE_READY) holdUntil = 0;   // a real frame is up
                    spinner.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                    // v3.1.2 — the Media3 transition lands in the Troubleshooter timeline
                    Troubleshooter.record("PLAYER", "media3 state=" + stateName(s));
                    if (s == Player.STATE_READY) onReady();
                    updateSnapshot();
                }
                @Override public void onIsPlayingChanged(boolean p) {
                    setPlayPauseIcon(p);
                    if (p) scheduleHudHide();
                    updateSnapshot();
                }
                @Override public void onVideoSizeChanged(VideoSize vs) {
                    // v3.3.0 — the decoded geometry: width, height AND the anamorphic
                    // ratio. This is the callback v3.2.0 never had, which is why a
                    // 16:9 movie was stretched to fill a 20:9 portrait screen.
                    setVideoSize(vs.width, vs.height, vs.pixelWidthHeightRatio);
                }
                @Override public void onPlayerError(PlaybackException e) {
                    Diag.e("player", "error: " + e.getErrorCodeName());
                    lastErrorName = e.getErrorCodeName();   // v3.1.2 — the diag accessor backing
                    final long pos = player == null ? 0 : player.getCurrentPosition();
                    int code = httpStatusOf(e);
                    Troubleshooter.record("PLAYER", "media3 ERROR " + e.getErrorCodeName()
                        + (code > 0 ? " · HTTP " + code : "")
                        + (e.getCause() == null ? "" : " · cause " + e.getCause()));
                    // ── v3.4.1: THE 3-STAGE RELAY LADDER / 403 RECOVERY LEG ──────
                    if (code > 0 && StreamHttpConfig.recoverable(code)) {
                        if (relayInfo != null && relayInfo.isRelay) {
                            // STAGE 1 → STAGE 2: the real upstream origin, decoded out of
                            // the base64 segment. NEVER the phone's LAN address.
                            if (relayStage < 2) {
                                relayStage = 2;
                                httpHeaders = StreamHttpConfig.relayOriginHeaders(relayInfo, httpHeaders);
                                hold();
                                Diag.i("player", "HTTP " + code + " → relay stage 2 (upstream origin "
                                    + relayInfo.upstreamOrigin + ")");
                                Troubleshooter.record("HTTP", "HTTP " + code
                                    + " → relay stage 2: decoded upstream Origin/Referer "
                                    + StreamHttpConfig.describe(httpHeaders));
                                flash("HTTP " + code + " — relay stage 2…");
                                rebuildAt(pos);
                                updateSnapshot();
                                return;
                            }
                            // STAGE 2 → STAGE 3: leave the relay entirely. The signed
                            // url= stream + the token's Edge-Cache-Cookie, which also
                            // fixes the relay's DASH pass-through hole (it rewrites HLS
                            // only — Chromecast.java:348-354).
                            if (relayStage < 3 && relayInfo.hasDirect()) {
                                relayStage = 3;
                                String direct = StreamHttpConfig.directUrl(relayInfo);
                                httpHeaders = StreamHttpConfig.directHeaders(relayInfo);
                                if (direct != null && !direct.isEmpty()) {
                                    url = direct;                       // the real stream
                                    mime = null;                        // re-derive from .mpd/.m3u8
                                    dashFlag = false;
                                }
                                hold();
                                Diag.i("player", "HTTP " + code + " → relay stage 3 (direct upstream "
                                    + hostOf(url) + ")");
                                Troubleshooter.record("HTTP", "HTTP " + code
                                    + " → relay stage 3: direct upstream unwrap with the token's "
                                    + "Edge-Cache-Cookie · headers=" + StreamHttpConfig.describe(httpHeaders));
                                flash("HTTP " + code + " — direct upstream…");
                                rebuildAt(pos);
                                updateSnapshot();
                                return;
                            }
                        } else if (!triedHeaderRecovery) {
                            triedHeaderRecovery = true;
                            httpHeaders = StreamHttpConfig.recovery(url, httpHeaders);
                            hold();
                            Diag.i("player", "HTTP " + code + " → retrying ONCE with recovery headers: "
                                + StreamHttpConfig.describe(httpHeaders));
                            Troubleshooter.record("HTTP", "HTTP " + code + " → automatic recovery retry with "
                                + StreamHttpConfig.describe(httpHeaders));
                            flash("HTTP " + code + " — retrying with browser headers…");
                            rebuildAt(pos);
                            updateSnapshot();
                            return;
                        }
                    }
                    showError(e.getErrorCodeName() + (code > 0 ? " · HTTP " + code : "")
                        + (e.getCause() == null ? "" : " · " + e.getCause().getMessage()));
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
                        flash("↻ " + ReceiverServer.clock(saved) + Chrome.pick(" සිට · resumed"));
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
        // v3.4.1 — THE TRANSITIONING HOLD. While the startup grace, a buffer or an
        // automatic 403 stage is in flight, answer the LOADING shape: the remote then
        // renders TRANSITIONING and never decides the TV stopped (defect #2).
        long now = SystemClock.uptimeMillis();
        // v3.4.3 — BOUNDED recovery (QA-0024): TRANSITIONING is honest while a stage is
        // running, but never past RECOVERY_CAP_MS. After that the stream really is gone.
        if (recovering() && recoveryDeadline > 0 && now > recoveryDeadline) {
            Diag.w("player", "recovery exceeded " + RECOVERY_CAP_MS + " ms — reporting idle");
            Troubleshooter.record("HTTP", "recovery bound reached — reporting NO_MEDIA_PRESENT");
            recoveryDeadline = 0;
            return ReceiverServer.idleJson();
        }
        if (now < holdUntil) return ReceiverServer.loadingJson();
        if (player == null) return ReceiverServer.idleJson();
        // a player that is IDLE *after* the hold means every stage genuinely failed
        if (player.getPlaybackState() == Player.STATE_IDLE) return ReceiverServer.idleJson();
        long dur = player.getDuration();
        live = dur == C.TIME_UNSET || player.isCurrentMediaItemLive();
        return ReceiverServer.statusJson(title, player.getCurrentPosition(), dur, player.getPlayWhenReady(), live);
    }

    /** Extend the TRANSITIONING hold (called on every stage start and on BUFFERING). */
    private void hold() { holdUntil = SystemClock.uptimeMillis() + HOLD_MS; }

    /** v3.4.3 — the hard bound on the whole recovery ladder (stage 2 → 3 → retry). */
    static final long RECOVERY_CAP_MS = 60000L;
    private long recoveryDeadline;

    /** True while an automatic 403 stage or header-recovery retry is in flight. */
    private boolean recovering() { return relayStage >= 2 || triedHeaderRecovery; }

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
            seek.setDuration((long) Math.max(1, dur));
            timeTotal.setText(ReceiverServer.clock(dur));
        }
        hudTitle.setText(title);
        showStatus();
        scheduleHudHide();
        // v3.4.2 — 1-tap resume from Continue watching: seek to the saved millisecond
        // exactly once, as soon as the real duration is known.
        if (resumeMs > 0 && !resumeApplied) {
            resumeApplied = true;
            long to = dur > 0 ? Math.min(resumeMs, dur - 1000) : resumeMs;
            if (to > 0) {
                player.seekTo(to);
                flash("Resumed · " + WatchHistory.clock(to));
            }
        }
    }

    /** v3.1.2 — Media3 state int → the exact name for the timeline. */
    static String stateName(int s) {
        if (s == Player.STATE_IDLE) return "IDLE";
        if (s == Player.STATE_BUFFERING) return "BUFFERING";
        if (s == Player.STATE_READY) return "READY";
        if (s == Player.STATE_ENDED) return "ENDED";
        return "state-" + s;
    }

    // ── v3.1.2 — the privacy-safe diagnostic accessors (booleans + counters only) ──

    /** The last Media3 error-code name, "" while none (v3.1.2 diag accessor backing). */
    private volatile String lastErrorName = "";

    /** The player's own state name (idle|buffering|ready|ended|error). */
    public String playerStateForDiag() {
        if (player == null) return "idle";
        try {
            if (!lastErrorName.isEmpty()) return "error";
            if (player.getPlaybackState() == Player.STATE_READY) return "ready";
            if (player.getPlaybackState() == Player.STATE_BUFFERING) return "buffering";
            if (player.getPlaybackState() == Player.STATE_ENDED) return "ended";
            return "idle";
        } catch (Throwable t) { return "idle"; }
    }

    /** The last Media3 error-code name ("" while none). */
    public String playerErrorForDiag() { return lastErrorName; }

    /** The subtitle pipeline state (idle|fetching|ready|local|not-found|needs-key|error). */
    public String subsStateForDiag() { return state; }

    /** The last subtitle status detail line. */
    public String subsDetailForDiag() { return stateDetail; }

    /** The loaded cue count. */
    public int cueCountForDiag() { List<Cue> c = cues; return c == null ? 0 : c.size(); }

    private void updateSnapshot() {
        PlayActivity p = currentInstance;
        if (p == null) return;
        snapshot = p.statusJson();
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Sinhala subtitles — the v3.2.0 three-layer zero-quota pipeline
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * The subtitle pipeline v3.2.0 — the full chain, never silent, never
     * mislabeled, never quota-dead:
     *   0. local cache (instant, offline)
     *   1. CAST-SUPPLIED SUBS: AnantPlay's own `subs` URLs — the English .srt the
     *      phone player already had, translated by the worker's /api/subs-direct
     *      (no IMDb needed) OR by FreeSubTranslator when the worker quota refuses;
     *   2. the worker's /subtitles job: human Sinhala first, else multi-provider
     *      AI translation (azure → google_v3 → gemini → workers_ai), polled on the
     *      deterministic token URL (200 .srt | 202 progress | 404 → re-ensure);
     *   3. v3.2.0 VALIDATION + FREE FALLBACK: every cue list destined for the
     *      Sinhala slot is SCRIPT-CHECKED (SubLangDetect.looksSinhala over the
     *      middle-cue sample); a list that is NOT Sinhala is routed to
     *      FreeSubTranslator (on-device, keyless, cached) instead of being
     *      displayed as "සිංහල උපසිරැසි සූදානම්" — the exact wrong-language
     *      field failure, fixed at the source.
     * Every state change lands in the status pill; every failure names its cause. */
    /**
     * v3.4.1 — RECOVER THE REAL MOVIE NAME FROM THE CAST URL (defect #4.1).
     *
     * <p>The field log's title was {@code "ArrowTV Player"} — the page's own branding —
     * but the relay URL in the very same cast carried
     * {@code "slug":"influencers-MAyBtWlRaw8"} inside its {@code t=} token. That slug is
     * the movie. Recovering it means the user never sees the "pick a movie" prompt at
     * all, and the cache key becomes the real name, so the right cues are found.
     *
     * <p>Falls back to a slug-looking path segment when the token has none.
     */
    private void recoverTitleFromCast() {
        if (!(titleIsJunk || TitleCleaner.isJunkTitle(title))) return;
        final String raw = title;
        try {
            StreamHttpConfig.Relay r = StreamHttpConfig.relay(url);
            String slug = r.slug;
            if (slug == null) slug = slugFromUrl(url);
            String real = TitleCleaner.fromSlug(slug);
            if (real != null && !real.isEmpty()) {
                title = real;
                titleIsJunk = false;
                Diag.i("subs", "junk title \"" + raw + "\" → real movie \"" + real + "\" (cast slug)");
                Troubleshooter.record("SUBS", "junk title \"" + raw + "\" recovered from the cast slug: "
                    + real);
            }
        } catch (Throwable t) {
            Diag.w("subs", "slug recovery: " + t);
        }
    }

    /** The last dash-bearing path segment of a URL — a scraper's movie slug, or null. */
    static String slugFromUrl(String u) {
        if (u == null) return null;
        String s = u;
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        int hash = s.indexOf('#');
        if (hash > 0) s = s.substring(0, hash);
        String best = null;
        for (String seg : s.split("/")) {
            if (seg.length() < 6 || seg.indexOf('-') < 0) continue;
            if (seg.contains("://") || seg.contains(":")) continue;
            boolean letters = false;
            for (int i = 0; i < seg.length(); i++) if (Character.isLetter(seg.charAt(i))) { letters = true; break; }
            if (letters) best = seg;
        }
        return best;
    }

    /**
     * v3.4.1 — THE 1-TAP INTRO PROMPT (defect #4.1).
     *
     * <p>When the cast reports only the player's branding, the subtitle pill becomes a
     * single friendly tap target during the opening minutes: one tap opens the movie
     * title search, and the 5-layer engine takes over from there. Drawn with a vector
     * (UiIcons) — never an emoji glyph, which is what made the v3.4.0 UI look messy.
     */
    private void promptJunkTitle() {
        try {
            setState("junk", t2("චිත්‍රපටයේ නම දෙන්න — තත්පර 5න් සිංහල උපසිරැසි · Tap to pick the movie title"), -1);
            if (statusPill != null) {
                UiIcons.setCompound(statusPill, R.drawable.ic_search, 0xFFFDE68A, 16, Gravity.START);
                statusPill.setClickable(true);
                statusPill.setOnClickListener(v -> openTitleSearch());
            }
            Troubleshooter.record("SUBS", "junk title: the 1-tap movie-title prompt is on screen");
        } catch (Throwable t) {
            Diag.w("subs", "junk prompt: " + t);
        }
    }

    /**
     * v3.4.1 — is THIS playback's title junk for caching purposes?
     *
     * <p>Recomputed live rather than read from the {@code titleIsJunk} field, because a
     * later real title (typed by the user, or recovered from the cast slug) must
     * immediately make the cache usable again.
     */
    private boolean isJunkForCache() {
        if (title == null) return true;
        return titleIsJunk || TitleCleaner.isJunkTitle(title);
    }

    /**
     * v3.4.1 — the ONLY writer into the subtitle cache.
     *
     * <p>Refuses to persist anything under a junk title: that is exactly how
     * "ArrowTV Player" ended up owning 906 cues belonging to some other movie.
     */
    private void saveCues(String key, List<Cue> list) {
        if (key == null || list == null || list.isEmpty()) return;
        if (isJunkForCache()) {
            Diag.i("subs", "cache write refused: junk title \"" + title + "\"");
            return;
        }
        try {
            store.save(key, list);
        } catch (Throwable t) {
            Diag.w("subs", "cache write: " + t);
        }
    }

    private void fetchSinhala() {
        if (fetching || testMode) return;
        fetching = true;
        final int myGen = ++fetchGen;   // a re-cast bumps this: the in-flight fetch goes stale
        new Thread(() -> {
            try {
                boolean series = episode > 0 || season > 0;
                String type = series ? "series" : "movie";
                String key = type + ":t:" + (title == null ? "" : title.toLowerCase(Locale.ROOT)) + ":" + season + ":" + episode + ":" + activeLang;
                // ══ v3.4.1 — NEVER TRUST A JUNK TITLE'S CACHE (defect #4.1) ═════════
                // The field log: title="ArrowTV Player" → "cues loaded: 906 · source=cached".
                // The cache key was "movie:t:arrowtv player:0:0:si", so every cast that
                // reported the player's own branding loaded whatever movie happened to
                // be cached under that key first. A junk title now never READS a cache
                // entry (and saveCues() never writes one).
                final boolean junkKey = isJunkForCache();
                if (!junkKey) {
                    List<Cue> cached = store.load(key);
                    if (cached != null) {
                        applyCues(cached, "cached");
                        return;
                    }
                }
                setState("fetching", BridgeApi.searchingText(), myGen);
                // ── 1. cast-supplied subs first (v2.1): instant, no IMDb needed ──
                if (castSubs != null && !castSubs.isEmpty()) {
                    for (String subUrl : castSubs) {
                        if (myGen != fetchGen) return;
                        if (subUrl == null || !(subUrl.startsWith("http://") || subUrl.startsWith("https://"))) continue;
                        try {
                            setState("fetching", Chrome.pick("cast subtitle ලබාගනිමින්… · fetching the cast's own subtitle…"), myGen);
                            byte[] raw = httpGet(subUrl, null);
                            List<Cue> enCues = SrtLoader.load(raw, "cast.srt");
                            if (enCues.size() < 8) continue;   // not a real track
                            // v3.2.0 — the script check FIRST: the cast's sub may
                            // already BE Sinhala (a Baiscope link!) — then it just shows.
                            if ("si".equals(activeLang) && SubLangDetect.looksSinhala(SubLangDetect.sampleOf(enCues))) {
                                applyCues(enCues, "cast-subs (human Sinhala)");
                                saveCues(key, enCues);
                                return;
                            }
                            setState("fetching", Chrome.pick("cast subtitle පරිවර්තනය වෙමින්… · translating the cast's subtitle…"), myGen);
                            Map<String, Object> job = api.subsDirect(CueText.of(enCues), title, activeLang, registerOverride);
                            String token = tokenOf(job);
                            if (pollToDone(token, myGen)) {
                                List<Cue> got = SrtLoader.load(api.srt(token).getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                                if (myGen != fetchGen) return;
                                // v3.2.0 — validate before announcing Sinhala
                                if (validateOrTranslate(got, key, myGen, "cast-subs → " + String.valueOf(job.get("provider")))) return;
                            }
                        } catch (BridgeApi.HttpException he) {
                            Diag.w("subs", "cast subs leg: HTTP " + he.status);
                            if (he.status == 429 || he.status >= 500) {
                                // v3.2.0 — the WORKER quota leg is dead → the FREE
                                // keyless on-device translator takes the SAME cues.
                                if (freeTranslateCastSubs(key, myGen)) return;
                                continue;  // try the next leg
                            }
                        } catch (Exception e) {
                            Diag.w("subs", "cast subs leg: " + e);
                        }
                    }
                }
                // ── 2. the worker job (human Sinhala → multi-provider AI) ──
                String id = imdb;
                if (id == null || id.isEmpty()) {
                    // v2.3.0: NOISY-TITLE CLEANING before Cinemeta (Phase 2, Killer #2).
                    // v3.2.0: JUNK TITLES NEVER REACH CINEMETA (the raw-JSON banner
                    // fix): "ArrowTV Player" and friends open the title-search pill.
                    if (titleIsJunk || TitleCleaner.isJunkTitle(title)) {
                        setState("not-found", Chrome.pick("චිත්‍රපටයේ නම හඳුනාගත නොහැක — තට්ටු කර සොයන්න · tap to search the movie name"), myGen);
                        return;   // NEVER "no Cinemeta match for: ArrowTV Player"
                    }
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
                    Troubleshooter.record("SUBS", "TitleCleaner \"" + title + "\" → \"" + lookupTitle + "\""
                        + (cleaned.year != null ? " (" + cleaned.year + ")" : "")
                        + " · /api/resolve " + (r == null ? "no-match" : "ok imdb=" + String.valueOf(r.get("imdb"))));
                    if (r == null) {
                        setState("not-found", "Cinemeta couldn't match \"" + lookupTitle + Chrome.pick("\" — the title search helps · නම සොයන්න"), myGen);
                        fallbackEnglishTrack();
                        return;
                    }
                    id = String.valueOf(r.get("imdb"));
                }
                for (int round = 0; round < 4; round++) {
                    Map<String, Object> job = api.subtitles(type, id, season, episode, title, activeLang, registerOverride);
                    String status = String.valueOf(job.get("status"));
                    if ("needs-key".equals(status)) {
                        // v3.2.0 — no provider keys on the worker AND nothing cached:
                        // the free keyless translator is the honest fallback…
                        if (freeTranslateCurrentCues(key, myGen, "free-fallback (no worker keys)")) return;
                        setState("needs-key", "සිංහල උපසිරැසි නැහැ — Worker එකේ provider key එකක් set කරන්න "
                            + "(AZURE_TRANSLATOR_KEY / GOOGLE_V3_KEY / GEMINI_API_KEY) · no translation provider configured on the worker", myGen);
                        fallbackEnglishTrack();
                        return;
                    }
                    if ("not-found".equals(status)) {
                        setState("not-found", Chrome.pick("මෙම තේමාවට උපසිරැසි හමු නොවීය — local .srt loader එක බාවිතා කරන්න · ")
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
                        if (validateOrTranslate(got, key, myGen, source + ("cinema-polished".equals(quality) ? " · cinema" : ""))) return;
                        continue;   // validation sent it to the free translator and that failed → next round
                    }
                    if ("translating".equals(status) && token != null && !token.isEmpty()) {
                        if (pollToDone(token, myGen)) {
                            List<Cue> got = SrtLoader.load(api.srt(token).getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                            if (myGen != fetchGen) return;
                            if (validateOrTranslate(got, key, myGen, String.valueOf(job.get("provider")))) return;
                            continue;
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
                // 429 (quota) / 5xx / 401 — v3.2.0: the FREE keyless translator is
                // tried BEFORE the English fallback paints the screen
                Diag.e("subs", "worker HTTP " + he.status + ": " + BridgeApi.brief(he.body, 300));
                if ((he.status == 429 || he.status >= 500) && freeTranslateCurrentCues(null, myGen, "free-fallback (worker " + he.status + ")")) return;
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

    /**
     * v3.2.0 — THE SCRIPT VALIDATION + FREE-TRANSLATOR BRIDGE. A cue list
     * fetched for the Sinhala slot must actually BE Sinhala (SubLangDetect over
     * the middle-cue sample). When it is: normal apply+cache. When it is NOT
     * (the mislabeled "English CC" / SubDL track of the field report): the list
     * goes to FreeSubTranslator.translate (on-device, keyless, disk-cached) and
     * the RESULT is what shows — never a false "සිංහල උපසිරැසි සූදානම්".
     * Returns true when something was applied (the caller stops). */
    private boolean validateOrTranslate(List<Cue> got, String key, int myGen, String how) {
        if (got == null || got.isEmpty()) return false;
        if (!"si".equals(activeLang) || SubLangDetect.looksSinhala(SubLangDetect.sampleOf(got))) {
            applyCues(got, how);
            if (key != null) saveCues(key, got);
            return true;
        }
        // the list is NOT Sinhala but was fetched FOR Sinhala → free-translate it
        Diag.w("subs", "fetched track is not Sinhala (" + SubLangDetect.describe(
            SubLangDetect.sinhalaRatio(SubLangDetect.sampleOf(got))) + ") — routing to the free keyless translator");
        Troubleshooter.record("SUBS", "script check: fetched track NOT Sinhala → free on-device translator engaged");
        return freeTranslate(got, key, myGen, how);
    }

    /** The free translator core (thread context, gen-guarded). */
    private boolean freeTranslate(List<Cue> source, String key, int myGen, String how) {
        try {
            setState("fetching", Chrome.pick("නොමිලේ පරිවර්තනය (බිලක් නැති) වෙමින්… · free keyless translation…"), myGen);
            FreeSubTranslator.Result r = FreeSubTranslator.translate(getFilesDir(), source, activeLang, ms -> {
                try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
            });
            if (myGen != fetchGen) return true;   // a re-cast owns the screen now
            if (r.cues != null && !r.cues.isEmpty() && SubLangDetect.looksSinhala(SubLangDetect.sampleOf(r.cues))) {
                applyCues(r.cues, (how == null || how.isEmpty() ? "" : how + " → ") + "free translator"
                    + (r.cacheHits > 0 ? " (cached)" : ""));
                if (key != null) saveCues(key, r.cues);
                return true;
            }
            // the translator did not land → show the source text HONESTLY labeled
            applyCues(source, "free translation incomplete — showing the source track");
            return true;
        } catch (Exception e) {
            Diag.w("subs", "free translator: " + e);
            return false;
        }
    }

    /** Free-translate the cast's own English subs (the 429/5xx leg's fallback). */
    private boolean freeTranslateCastSubs(String key, int myGen) {
        try {
            for (String subUrl : castSubs) {
                if (subUrl == null || !(subUrl.startsWith("http://") || subUrl.startsWith("https://"))) continue;
                byte[] raw = httpGet(subUrl, null);
                List<Cue> enCues = SrtLoader.load(raw, "cast.srt");
                if (enCues.size() >= 8 && !SubLangDetect.looksSinhala(SubLangDetect.sampleOf(enCues))) {
                    return freeTranslate(enCues, key, myGen, "cast-subs");
                }
                if (enCues.size() >= 8) {
                    applyCues(enCues, "cast-subs (free fallback)");
                    return true;
                }
            }
        } catch (Exception e) {
            Diag.w("subs", "free cast fallback: " + e);
        }
        return false;
    }

    /** Free-translate whatever cue list is CURRENTLY loaded (the subs panel's
     *  "Free translate now" + the worker needs-key/429 fallback). */
    private boolean freeTranslateCurrentCues(String key, int myGen, String how) {
        List<Cue> current = cues;
        if (current == null || current.isEmpty()) return false;
        // the English source is the en side when present
        List<Cue> source = new ArrayList<>(current.size());
        for (Cue c : current) source.add(new Cue(c.i, c.t0, c.t1, c.ts, c.en, c.enf, c.en));
        return freeTranslate(source, key, myGen, how);
    }

    /** The manual "Free translate now" button (the subs panel). */
    private void manualFreeTranslate() {
        final int myGen = fetchGen;
        new Thread(() -> {
            if (freeTranslateCurrentCues(null, myGen, "manual")) return;
            setState("error", Chrome.pick("පරිවර්තනය අසාර්ථක — නැවත උත්සාහ කරන්න · free translation failed — retry"), myGen);
        }, "si-free").start();
    }

    /** Extract the si token from a job body ({si:{token}} in both paths). */
    private static String tokenOf(Map<String, Object> job) {
        Map<String, Object> si = (Map<String, Object>) MiniJson.objGet(job, "si");
        return si == null ? null : String.valueOf(si.get("token"));
    }

    /** Poll the deterministic /sub/<token>.srt URL until it answers a real .srt.
     *  Returns true with the text in `polledSrt`, false when the token went cold
     *  (isolate miss → caller re-ensures the job) or the job errored.
     *  v3.2.0: the 404 "job not on this isolate" retry is SILENT (a Diag line,
     *  never a pill) — the field screenshot's raw "404 job not on this isolate"
     *  banners across the video died here. */
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
                if (he.status == 404) {
                    // the isolate forgot the job — SILENT re-ensure (the caller
                    // retries the /subtitles ensure; no JSON banner on the video)
                    Diag.i("subs", "404 isolate miss at poll " + poll + " — silently re-ensuring the job");
                    return false;
                }
                if (he.status == 429) {
                    Diag.w("subs", "worker 429 (quota) at poll " + poll + " — free translator takes over");
                    return false;   // v3.2.0: the caller's catch routes to FreeSubTranslator
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

    /** Manual fetch with a title override (the subs panel's Go button + the
     *  v3.2.0 in-player title-search dialog). */
    private void manualFetch(String t, int s, int e) {
        title = t;
        season = s;
        episode = e;
        imdb = null;
        titleIsJunk = false;
        fetchGen++;                 // the in-flight fetch (if any) goes stale
        fetching = false;
        cues = new ArrayList<>();
        fetchSinhala();
    }

    private void applyCues(List<Cue> loaded, String how) {
        // ══ v3.4.1 — THE CUE-MATCH GUARD (defect #4.2) ═══════════════════════════
        // The 4 Hz ticker walks the list and BREAKS at the first cue whose t0 is past
        // the playhead, which is only correct on a list sorted by t0. A translated or
        // merged track can arrive unsorted, and then cues silently never matched —
        // "loaded but not rendering". Sorting here makes the ticker's contract true.
        List<Cue> sorted = loaded;
        boolean ordered = true;
        for (int i = 1; i < loaded.size(); i++) {
            if (loaded.get(i - 1).t0 > loaded.get(i).t0) { ordered = false; break; }
        }
        if (!ordered) {
            sorted = new ArrayList<>(loaded);
            try {
                java.util.Collections.sort(sorted, (a, b) -> Long.compare(a.t0, b.t0));
            } catch (Throwable t) {
                Diag.w("subs", "sort: " + t);
            }
            Diag.i("subs", "cue list was UNSORTED — repaired (" + sorted.size() + " cues)");
            Troubleshooter.record("SUBS", "cue list was unsorted — repaired before rendering");
        }
        cues = sorted;
        // a fresh track always resets the "first cue rendered" proof + the offset guard
        firstCueRendered = false;
        paintSubtitleDock();
        cueRangeWarned = false;
        boolean hasEn = false;
        for (Cue c : sorted) if (c.en != null && !c.en.isEmpty()) { hasEn = true; break; }
        setState("ready", "සිංහල උපසිරැසි සූදානම් · " + sorted.size() + " cues · " + how
            + (hasEn ? "" : " · human Sinhala"));
        Diag.i("subs", "applied " + sorted.size() + " cues (" + how + ")");
        Troubleshooter.record("SUBS", "cues loaded: " + sorted.size() + " · source=" + how + " · render=on");
        // …and prove the view can actually paint them
        main.post(this::repairSubtitleView);
    }

    /**
     * v3.4.1 — SUBTITLE VISIBILITY GUARD (defect #4.2).
     *
     * <p>Once cues exist the ONLY acceptable state for the subtitle view is "paintable":
     * visible-capable, alpha 1, and above the video surface. v3.4.0 could leave it with
     * alpha 0 or buried behind the surface (both from the Cinema-Polish pass), so a
     * perfectly loaded track rendered nothing at all. This repairs the three properties
     * and logs the repair, so the Troubleshooter shows it instead of the user guessing.
     */
    private void repairSubtitleView() {
        if (subView == null) return;
        boolean fixed = false;
        try {
            if (subView.getAlpha() < 1f) { subView.setAlpha(1f); fixed = true; }
            if (subLayer != null) {
                if (subLayer.getAlpha() < 1f) { subLayer.setAlpha(1f); fixed = true; }
                if (subLayer.getVisibility() != View.VISIBLE) { subLayer.setVisibility(View.VISIBLE); fixed = true; }
                {
                    // v3.4.1 — indexOfChild/getChildCount live on ViewGroup, not View
                    if (subLayer.getParent() instanceof android.view.ViewGroup) {
                        android.view.ViewGroup host = (android.view.ViewGroup) subLayer.getParent();
                        if (host.indexOfChild(subLayer) < host.getChildCount() - 1) {
                            subLayer.bringToFront(); fixed = true;   // above the video surface
                        }
                    }
                }
            }
            if (subView.getTextSize() < dp(8)) { applyStudioStyle(); fixed = true; }
        } catch (Throwable t) {
            Diag.w("subs", "subtitle guard: " + t);
        }
        if (fixed) {
            Diag.i("subs", "subtitle view repaired (alpha/visibility/z-order)");
            Troubleshooter.record("SUBS", "subtitle view repaired: alpha/visibility/z-order");
        }
    }

    /** Nothing in Sinhala: offer the stream's own text track (the "CC English" habit).
     *  v3.2.0 — the fallback is HONESTLY LABELED as the stream's own CC, never as
     *  Sinhala, and auto-translate stays one tap away in the subs panel. */
    private void fallbackEnglishTrack() {
        if (enFallbackOn || finished || player == null) return;
        main.post(() -> {
            try {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
                enFallbackOn = true;
                // ══ v3.4.1 — LAYER 4 TURNS ON HERE (defect #4.3) ══════════════════
                // Layers 1-3 found no external SRT, but the stream itself may carry an
                // English CC track (WebVTT / CEA-608 / SubRip inside HLS-DASH). The text
                // track is now enabled and onCues() feeds every line through
                // FreeSubTranslator, so Sinhala appears WITHOUT any provider, key or
                // download. This is the layer that makes "never fail" literally true.
                liveTranslateOn = true;
                setState(state, stateDetail
                    + Chrome.pick(" · stream CC → සිංහල live (Layer 4) · tap for the subtitles sheet"));
                Diag.i("subs", "Layer 4 ON — embedded stream captions will be translated live");
                Troubleshooter.record("SUBS", "Layer 4 enabled: the stream's own text track is on, "
                    + "each line translated to Sinhala on the fly (in-memory cache, 400 lines)");
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
        String clean = Cue.stripMarkup(sb.toString());
        if (!liveTranslateOn) {
            main.post(() -> {
                if (subView != null) {
                    // the stream's own CC track: system font, base direction from the language
                    applyLangStyle();
                    subView.setText(clean);
                    subView.setVisibility(clean.isEmpty() ? View.GONE : View.VISIBLE);
                }
            });
            return;
        }
        // ── v3.4.1 — LAYER 4: Sinhala if we already have it, English until we do ──
        liveLine = clean;
        String known;
        synchronized (liveCache) { known = liveCache.get(clean); }
        final String show = known != null ? known : clean;
        main.post(() -> {
            if (subView == null) return;
            applyLangStyle();
            subView.setText(show);
            subView.setVisibility(show.isEmpty() ? View.GONE : View.VISIBLE);
            if (!firstCueRendered && !show.isEmpty()) {
                firstCueRendered = true;
                paintSubtitleDock();
                Diag.i("subs", "first cue rendered \u2713 (layer 4, stream CC)");
                Troubleshooter.record("SUBS", "[SUBS] first cue rendered \u2713 · source=layer4 stream CC");
            }
        });
        if (known == null && !clean.isEmpty()) queueLiveTranslation(clean);
    }

    /**
     * v3.4.1 — translate ONE caption line to Sinhala, off the UI thread.
     *
     * <p>FreeSubTranslator is the on-device, keyless, disk-cached engine, so a repeated
     * line costs nothing; the in-memory {@link #liveCache} then makes a rewind instant.
     * Only the line that is still on screen is replaced — a late answer for an earlier
     * line is cached but never painted over the current one.
     */
    private void queueLiveTranslation(final String line) {
        if (line == null || line.isEmpty() || !livePending.add(line)) return;
        new Thread(() -> {
            try {
                List<Cue> one = new ArrayList<>(1);
                one.add(new Cue(1, 0, 2000, "00:00:00,000 --> 00:00:02,000", line, null, null));
                FreeSubTranslator.Result r = FreeSubTranslator.translate(getFilesDir(), one, activeLang,
                    ms -> { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } });
                String si = null;
                if (r != null && r.cues != null && !r.cues.isEmpty()) {
                    Cue c = r.cues.get(0);
                    si = c.si != null && !c.si.isEmpty() ? c.si : c.en;
                }
                if (si != null && !si.isEmpty()) {
                    synchronized (liveCache) { liveCache.put(line, si); }
                    if (line.equals(liveLine)) {
                        final String out = si;
                        main.post(() -> {
                            if (subView != null) {
                                subView.setText(out);
                                subView.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                Diag.w("subs", "layer 4 translate: " + t);
            } finally {
                livePending.remove(line);
            }
        }, "layer4-sub").start();
    }

    /** A local .srt/.zip the user picked (SAF) — human Sinhala from Baiscope/Zoom/etc.
     *  v3.2.0 — the script check routes non-Sinhala local files to the free
     *  translator automatically (Auto-Translate Non-Sinhala toggle, default ON). */
    void onLocalCues(List<Cue> loaded, String name) {
        cues = loaded;
        enFallbackOn = false;
        if ("si".equals(activeLang) && prefs.getBoolean("autoTranslateNonSi", true)
            && !loaded.isEmpty() && !SubLangDetect.looksSinhala(SubLangDetect.sampleOf(loaded))) {
            Diag.i("subs", "local " + name + " is not Sinhala — free-translating");
            manualFreeTranslateWith(loaded, name);
            return;
        }
        setState("local", name + " · " + loaded.size() + " cues");
        Diag.i("subs", "local " + name + ": " + loaded.size() + " cues");
    }

    /** Free-translate a SPECIFIC cue list (the local-file path). */
    private void manualFreeTranslateWith(List<Cue> source, String name) {
        final int myGen = fetchGen;
        setState("fetching", Chrome.pick("නොමිලේ පරිවර්තනය වෙමින්… · free keyless translation…"));
        new Thread(() -> {
            if (freeTranslate(source, null, myGen, "local:" + name)) return;
            applyCues(source, "local " + name + " (source track)");
        }, "si-free-local").start();
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
                setState(state, Chrome.pick("Cinema Polish Ready · සිනමා ප්‍රඥප්තිය සූදානම්"));
                Diag.i("subs", "cinema polish hot-swapped (" + got.size() + " cues)");
            });
            saveCues(key, got);
        } catch (Exception e) {
            Diag.w("subs", "polish swap skipped: " + e);
        }
    }

    private void applyTestCues() {
        List<Cue> demo = new ArrayList<>();
        demo.add(new Cue(0, 500, 4000, "00:00:00,500 --> 00:00:04,000", "", "", "ආයුබෝවන්! සිංහල Player ක්‍රියාත්මකයි"));
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
                    // v3.4.1 — the first painted cue is a PROOF, not an assumption
                    if (!firstCueRendered && text != null && !text.trim().isEmpty()) {
                        firstCueRendered = true;
                        paintSubtitleDock();
                        if (subView != null && (subView.getAlpha() < 1f
                                || (subLayer != null && subLayer.getVisibility() != View.VISIBLE))) {
                            main.post(PlayActivity.this::repairSubtitleView);
                        }
                        Diag.i("subs", "first cue rendered \u2713 at " + pos + " ms ("
                            + (hit.si != null && !hit.si.isEmpty() ? "si" : "en") + ")");
                        Troubleshooter.record("SUBS", "[SUBS] first cue rendered \u2713 · cue t0="
                            + hit.t0 + " · playhead=" + pos + " ms · offset=" + subDelayMs + " ms");
                    }
                    // v3.2.0 — the text was ALREADY stripped at parse time
                    // (Cue.stripMarkup); the renderer only types the view.
                    // v3.2.0 — ONE-place typeface: applyLangStyle() (called on
                    // lang switch + studio change) already set family+bold; the
                    // per-cue render does NOT re-touch the typeface (the v3.1.x
                    // per-cue reset was the bold/garble bug).
                    subView.setText(text == null ? "" : text);
                    subView.setVisibility(View.VISIBLE);
                } else {
                    subView.setVisibility(View.GONE);
                    // v3.4.1 — "loaded but nothing shows" has exactly one more cause we
                    // can name: the playhead sits outside every cue's window (a wrong
                    // track, or a track for a different cut). Say it ONCE, with numbers,
                    // instead of leaving the user staring at a silent screen.
                    if (!cueRangeWarned && !cs.isEmpty()) {
                        Cue first = cs.get(0), last = cs.get(cs.size() - 1);
                        if (pos > last.t1 + 5000 || pos < first.t0 - 60000) {
                            cueRangeWarned = true;
                            Troubleshooter.record("SUBS", "cues loaded but the playhead is OUTSIDE the "
                                + "cue range · playhead=" + pos + " ms · cues " + first.t0
                                + "–" + last.t1 + " ms · adjust the subtitle offset in the sheet");
                        }
                    }
                }
                if (hudVisible) {
                    timeNow.setText(ReceiverServer.clock(player.getCurrentPosition()));
                    if (!dragging) seek.setPosition(Math.max(0, player.getCurrentPosition()));
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

    /** The status pill state → bilingual line + color (the never-silent HUD).
     *  v3.2.0: the junk-title search state ("🔍 … තට්ටු කර සොයන්න") tints as a
     *  gentle action color and tapping it opens the title-search dialog. */
    private void showStatus() {
        if (hudStatus != null) {
            boolean good = "ready".equals(state) || "local".equals(state);
            boolean warn = "fetching".equals(state) || "junk".equals(state);   // v3.4.1
            hudStatus.setText(stateDetail);
            hudStatus.setTextColor(good ? 0xFF8CFF8C : warn ? 0xFFF59E0B : 0xFFFFB3B3);
            hudStatus.setVisibility(View.VISIBLE);
            if (good) main.postDelayed(hudStatusHide, 3000);   // success auto-hides after 3 s
        }
        if (statusPill != null) {
            boolean good = "ready".equals(state) || "local".equals(state);
            statusPill.setText(stateDetail);
            statusPill.setTextColor(good ? 0xFF8CFF8C : "fetching".equals(state) ? 0xFFFDE68A : 0xFFFFB3B3);
            statusPill.setVisibility(View.VISIBLE);
            statusPill.setAlpha(1f);
            // v3.4.2 — the pill auto-fades after 2.5 s (screenshot 2: it was stuck over
            // an actor's forehead forever because nothing ever faded it).
            main.removeCallbacks(pillHide);
            if (good) main.postDelayed(pillHide, 2500);
        }
    }

    private final Runnable hudStatusHide = new Runnable() {
        @Override public void run() { if (hudStatus != null && ("ready".equals(state) || "local".equals(state))) hudStatus.setVisibility(View.INVISIBLE); }
    };

    private final Runnable pillHide = new Runnable() {
        @Override public void run() {
            if (statusPill != null && ("ready".equals(state) || "local".equals(state))) {
                statusPill.animate().alpha(0f).setDuration(220)
                    .withEndAction(() -> statusPill.setVisibility(View.GONE)).start();
            }
        }
    };

    // ───────────────────────────────────────────────────────────────────────────
    // the UI — built in code (the project's zero-XML-layout idiom)
    // ───────────────────────────────────────────────────────────────────────────

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /**
     * v3.3.0 — THE CINEMA UI (rebuilt from scratch; the v3.2.0 hand-rolled HUD is
     * gone). Five strict layers, bottom to top:
     *
     *   1. videoHost — black, MATCH_PARENT; the SurfaceView inside it is sized by
     *      {@link VideoViewport} on every onVideoSizeChanged/onLayoutChange, so a
     *      16:9 movie is a 1080×608 letterbox in portrait and NEVER a 2.4× vertical
     *      stretch (v3.2.0 screenshot 2/3's exact defect).
     *   2. subLayer — the subtitle text, padded ABOVE the bottom control zone.
     *   3. pillBox — the never-silent subtitle status/actions, BELOW the top bar
     *      (fixed reserve = statusInset + 50dp, so it can never collide with ← Back).
     *   4. hud — the 3-zone control overlay (top WRAP · centre weight=1 · bottom WRAP):
     *      three stacked zones in a LinearLayout(VERTICAL), which makes an overlap
     *      between the top bar and the bottom chips PHYSICALLY IMPOSSIBLE.
     *   5. errorCard / sheets — centred, never under the bars.
     */
    private void buildUi() {
        if (AppPrefs.keepScreenOn(prefs))
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // v3.4.2 — the orientation mode (auto sensor / lock landscape / lock portrait).
        // "Auto" is FULL_SENSOR, so the picture genuinely follows the phone.
        applyOrientation();
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wl = getWindow().getAttributes();
            wl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wl);
        }
        aspectMode = VideoViewport.modeFrom(AppPrefs.aspectMode(prefs));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        rootView = root;                      // v3.3.1 — the overlay parent

        // ── LAYER 1 — the video host (centred, aspect-correct) ────────────────
        videoHost = new FrameLayout(this);
        videoHost.setBackgroundColor(Color.BLACK);
        root.addView(videoHost, new FrameLayout.LayoutParams(-1, -1));
        surface = new SurfaceView(this);
        surface.getHolder().setFormat(PixelFormat.OPAQUE);
        videoHost.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoHost.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyVideoSize());

        // ── LAYER 2 — the subtitles (a padded layer, so the chips can never eat them)
        subLayer = new FrameLayout(this);
        subLayer.setClipChildren(false);
        root.addView(subLayer, new FrameLayout.LayoutParams(-1, -1));
        subView = new TextView(this);
        UiFonts.markSubtitle(subView);
        applyLangStyle();
        // v3.4.2 — CENTER_HORIZONTAL only: the line is centred horizontally and anchored
        // by subLayoutParams() at Gravity.BOTTOM. The v3.4.1 Gravity.CENTER here is part
        // of why screenshot 2's subtitle rendered across the actors' faces.
        subView.setGravity(Gravity.CENTER_HORIZONTAL);
        subView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        subView.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        subView.setLineSpacing(dp(2), 1.2f);   // v3.2.0 contract; applyStudioStyle() refines it
        applyStudioStyle();
        subLayer.addView(subView, subLayoutParams());

        // ── LAYER 3 — the never-silent pill column (BELOW the top bar) ────────
        pillBox = new LinearLayout(this);
        pillBox.setOrientation(LinearLayout.VERTICAL);
        pillBox.setGravity(Gravity.CENTER_HORIZONTAL);
        // v3.3.1 — FrameLayout.LayoutParams: pillBox is a child of the root
        // FrameLayout, so LinearLayout.LayoutParams here threw
        // ClassCastException inside FrameLayout.onMeasure on the first layout.
        pillLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        pillLp.topMargin = dp(50);
        root.addView(pillBox, pillLp);

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
        statusPill.setOnClickListener(v -> { if (titleIsJunk || "not-found".equals(state)) openTitleSearch(); });
        LinearLayout.LayoutParams pillTextLp = new LinearLayout.LayoutParams(-2, -2);
        pillTextLp.topMargin = dp(6);
        pillBox.addView(statusPill, pillTextLp);

        // the pill's 1-tap fixes (they STACK under the pill — v3.2.0 floated them at a
        // fixed 64dp top margin, which is exactly how "< Back" ended up on top of them)
        // ── v3.4.2 — THE OVERLAPPING-FLOATING-ROWS DELETION (screenshot 3) ──────
        //
        // v3.4.1 built three absolutely-positioned pill rows here (Retry / Provider /
        // Local .srt / ±0.25s, the language chips with the squished vertical box, and
        // Spoken/Formal + AI Studio). They sat in the same overlay band as the centre
        // −10 / Pause / +10 transport and collided with it on every phone. They are
        // GONE from the video surface: every one of those controls now lives inside the
        // Subtitles sheet (showSubtitleToolsSheet()), which is opened from the Subtitles
        // chip. ONE status indicator remains — the pill above — never two.

        // v3.1.2 CRASH FIX, KEPT FOREVER (the audit trail the lab asserts): the notice
        // background used to be View.setBackgroundResource(0x88000000), which treats a
        // bare int as a RESOURCE id → Resources$NotFoundException thrown from buildUi()
        // inside PlayActivity.onCreate, surfacing as the Android 16
        // TransactionExecutor.executeNonLifecycleItem "keeps stopping" crash on Test
        // Player, Play Video URL and EVERY cast arrival. setBackgroundColor(int) is the
        // ARGB API — same translucent black, zero resource lookup, zero crash.
        // ── the transient gesture/toast pill (centre, above the chips) ────────
        notice = new TextView(this);
        notice.setTextColor(Color.WHITE);
        notice.setTextSize(14);
        notice.setBackgroundColor(0x88000000);
        notice.setPadding(dp(14), dp(8), dp(14), dp(8));
        notice.setGravity(Gravity.CENTER);
        notice.setVisibility(View.GONE);
        FrameLayout.LayoutParams noticeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        noticeLp.bottomMargin = dp(150);
        root.addView(notice, noticeLp);

        // ── the buffering spinner (centre) ────────────────────────────────────
        spinner = new android.view.View(this);
        try {
            android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
            pb.setIndeterminate(true);
            spinner = pb;
        } catch (Throwable ignored) { }
        spinner.setVisibility(View.GONE);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));

        // ── LAYER 4 — the 3-zone HUD ──────────────────────────────────────────
        hud = buildHud();
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        panel = buildPanel();
        root.addView(panel, new FrameLayout.LayoutParams(dp(320), -2, Gravity.END | Gravity.CENTER_VERTICAL));
        panel.setVisibility(View.GONE);
        sheet = buildSettingsSheet();
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        sheet.setVisibility(View.GONE);

        // ── LAYER 5 — the non-intrusive error card (never under the top bar) ──
        errorCard = buildErrorCard();
        root.addView(errorCard, new FrameLayout.LayoutParams(dp(300), -2, Gravity.CENTER));
        errorCard.setVisibility(View.GONE);

        // ── the LOCK pill: the ONLY thing touchable while locked ──────────────
        unlockPill = new LinearLayout(this);
        unlockPill.setOrientation(LinearLayout.HORIZONTAL);
        unlockPill.setGravity(Gravity.CENTER_VERTICAL);
        unlockPill.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable ub = new GradientDrawable();
        ub.setColor(0xE60E1424);
        ub.setCornerRadius(dp(18));
        ub.setStroke(dp(1), 0x55FFFFFF);
        unlockPill.setBackground(ub);
        ImageView unlockIcon = new ImageView(this);
        unlockIcon.setImageDrawable(UiIcons.tinted(this, R.drawable.ic_lock, 0xFFE2E8F0, 14));
        unlockPill.addView(unlockIcon, new LinearLayout.LayoutParams(-2, -2));
        TextView unlockText = new TextView(this);
        unlockText.setText(Chrome.pick("Tap to unlock · අගුළු හරින්න"));
        unlockText.setTextColor(0xFFE2E8F0);
        unlockText.setTextSize(11.5f);
        unlockPill.addView(unlockText, new LinearLayout.LayoutParams(-2, -2));
        unlockPill.setOnClickListener(v -> setLocked(false));
        unlockPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams unlockLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        unlockLp.bottomMargin = dp(24);
        unlockLp.rightMargin = dp(16);
        root.addView(unlockPill, unlockLp);

        // ── the insets: measured ONCE, applied to the four bars that matter ───
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets top = insets.getInsets(
                android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.displayCutout());
            android.graphics.Insets bottom = insets.getInsets(
                android.view.WindowInsets.Type.navigationBars() | android.view.WindowInsets.Type.displayCutout());
            statusInset = top.top;
            navInset = bottom.bottom;
            if (hudTopBar != null) hudTopBar.setPadding(dp(6), statusInset + dp(6), dp(6), dp(6));
            if (hudBottomBar != null) hudBottomBar.setPadding(dp(10), dp(4), dp(10), navInset + dp(8));
            if (pillLp != null) { pillLp.topMargin = statusInset + dp(50); pillBox.setLayoutParams(pillLp); }
            if (subLayer != null) subLayer.setPadding(0, statusInset + dp(40), 0, navInset + dp(96));
            if (unlockPill != null) {
                FrameLayout.LayoutParams ul = (FrameLayout.LayoutParams) unlockPill.getLayoutParams();
                ul.bottomMargin = navInset + dp(24);
                unlockPill.setLayoutParams(ul);
            }
            if (sheet != null) sheet.setPadding(dp(16), dp(12), dp(16), navInset + dp(16));
            if (panel != null) panel.setPadding(dp(14), dp(12), dp(14), dp(12));
            applyVideoSize();
            return insets;
        });

        wireGestures(root);
        setContentView(root);
        UiFonts.applyTree(root, prefs);
        applyMidnightDimmer();          // v3.4.2 — the dark-room veil, if enabled
        applyImmersive(false);          // bars visible while the HUD is up
        if (AppPrefs.lockOnStart(prefs)) setLocked(true);
    }

    /**
     * v3.3.0 — THE 3-ZONE CINEMA HUD.
     *
     * A LinearLayout(VERTICAL) with THREE stacked zones — top WRAP_CONTENT, centre
     * height 0 + weight 1, bottom WRAP_CONTENT. Because the zones are siblings in a
     * vertical LinearLayout, the top bar and the bottom chips can never be laid out
     * on top of each other, on ANY screen size or aspect ratio. Every zone is padded
     * by the REAL window insets (statusBars|displayCutout on top, navigationBars at
     * the bottom) in buildUi's inset listener.
     */
    private LinearLayout buildHud() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // ══ ZONE 1 — TOP BAR ═══════════════════════════════════════════════════
        hudTopBar = new LinearLayout(this);
        hudTopBar.setOrientation(LinearLayout.HORIZONTAL);
        hudTopBar.setGravity(Gravity.CENTER_VERTICAL);
        hudTopBar.setMinimumHeight(dp(48));
        GlassSurface.apply(hudTopBar, prefs, 0xFF101A30, 22);

        View back = icon(R.drawable.ic_back, 20, v -> finish());   // v3.4.2 — 20dp glyph, 40dp plate
        hudTopBar.addView(back, new LinearLayout.LayoutParams(dp(ICON_BOX_DP), dp(ICON_BOX_DP)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        hudTitle = new TextView(this);
        hudTitle.setTextColor(Color.WHITE);
        hudTitle.setTextSize(14.5f);
        hudTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hudTitle.setMaxLines(1);
        hudTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(hudTitle, new LinearLayout.LayoutParams(-1, -2));
        hudStatus = new TextView(this);
        hudStatus.setTextColor(0xFF9FB0D8);
        hudStatus.setTextSize(10.5f);
        hudStatus.setMaxLines(1);
        hudStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hudStatus.setVisibility(View.GONE);          // v3.3.0: ONE badge, not two banners
        titles.addView(hudStatus, new LinearLayout.LayoutParams(-1, -2));
        hudTopBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        badgeSub = new TextView(this);
        badgeSub.setText("CC");
        badgeSub.setContentDescription("Sinhala subtitles loaded");
        badgeSub.setTextColor(0xFF86EFAC);
        badgeSub.setTextSize(10.5f);
        badgeSub.setPadding(dp(7), dp(3), dp(7), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0x3316A34A);
        badgeBg.setCornerRadius(dp(9));
        badgeSub.setBackground(badgeBg);
        badgeSub.setVisibility(View.GONE);
        hudTopBar.addView(badgeSub, new LinearLayout.LayoutParams(-2, -2));

        // Every top-right control has a 44dp touch target with a small vector glyph.
        View lock = icon(R.drawable.ic_lock, 20, v -> setLocked(true));
        hudTopBar.addView(lock, topIconParams());
        View rotate = icon(R.drawable.ic_rotate, 20, v -> showOrientationSheet());
        hudTopBar.addView(rotate, topIconParams());
        View pip = icon(R.drawable.ic_pip, 20, v -> enterPictureInPicture());
        hudTopBar.addView(pip, topIconParams());
        View vlc = icon(R.drawable.ic_vlc_external, 20, v -> openExternalPlayer());
        hudTopBar.addView(vlc, topIconParams());
        View gear = icon(R.drawable.ic_settings, 20, v -> toggleSheet());
        hudTopBar.addView(gear, topIconParams());
        box.addView(hudTopBar, new LinearLayout.LayoutParams(-1, -2));

        // ══ ZONE 2 — CENTRE (transport + gesture feedback) ═════════════════════
        hudCenter = new LinearLayout(this);
        hudCenter.setOrientation(LinearLayout.VERTICAL);
        hudCenter.setGravity(Gravity.CENTER);

        final long stepMs = AppPrefs.seekStepMs(prefs);
        final int stepLabel = (int) (stepMs / 1000);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER);
        View minus = icon(R.drawable.ic_replay_10, 20, v -> skip(-stepMs));
        transport.addView(minus, new LinearLayout.LayoutParams(dp(64), dp(52)));
        playPause = icon(R.drawable.ic_play_filled, 24, v -> toggle());   // v3.4.1 — centre disc = 24dp
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        playLp.leftMargin = playLp.rightMargin = dp(18);
        transport.addView(playPause, playLp);
        View plus = icon(R.drawable.ic_forward_10, 20, v -> skip(stepMs));
        transport.addView(plus, new LinearLayout.LayoutParams(dp(64), dp(52)));
        hudCenter.addView(transport, new LinearLayout.LayoutParams(-2, -2));

        gesturePill = new TextView(this);
        gesturePill.setTextColor(Color.WHITE);
        gesturePill.setTextSize(13);
        gesturePill.setGravity(Gravity.CENTER);
        gesturePill.setPadding(dp(14), dp(7), dp(14), dp(7));
        gesturePill.setBackground(GlassSurface.drawable(this, prefs, 0xFF0E1424, 14,
            AppPrefs.glass(prefs)));
        gesturePill.setVisibility(View.GONE);
        LinearLayout.LayoutParams gpl = new LinearLayout.LayoutParams(-2, -2);
        gpl.topMargin = dp(14);
        hudCenter.addView(gesturePill, gpl);

        // v3.4.0 - the Apple-style capsules FLANK the transport inside zone 2, so they are
        // always where the thumb already is and never need a second overlay parent.
        hudCenter.removeView(transport);
        LinearLayout capsuleRow = new LinearLayout(this);
        capsuleRow.setOrientation(LinearLayout.HORIZONTAL);
        capsuleRow.setGravity(Gravity.CENTER_VERTICAL);
        capsuleRow.setPadding(dp(10), 0, dp(10), 0);
        brightCapsule = new CapsuleBar(this, R.drawable.ic_brightness, 0xFF0A0A14, 26, 104);
        brightCapsule.setVisibility(View.GONE);
        capsuleRow.addView(brightCapsule, new LinearLayout.LayoutParams(-2, -2));
        capsuleRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        capsuleRow.addView(transport, new LinearLayout.LayoutParams(-2, -2));
        capsuleRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        volCapsule = new CapsuleBar(this, R.drawable.ic_volume, 0xFF0A0A14, 26, 104);
        volCapsule.setVisibility(View.GONE);
        capsuleRow.addView(volCapsule, new LinearLayout.LayoutParams(-2, -2));
        hudCenter.addView(capsuleRow, new LinearLayout.LayoutParams(-1, -2));

        box.addView(hudCenter, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ══ ZONE 3 — BOTTOM (seek + times + the action chips) ══════════════════
        hudBottomBar = new LinearLayout(this);
        hudBottomBar.setOrientation(LinearLayout.VERTICAL);
        GlassSurface.apply(hudBottomBar, prefs, 0xFF101A30, 24);

        // v3.4.0 - the custom cinema scrub bar (3dp -> 6dp, buffered layer, drag preview)
        seek = new ScrubBar(this, ThemeEngine.palette(
            AppPrefs.themeMode(prefs), 0, AppPrefs.accent(prefs), AppPrefs.accentHex(prefs),
            AppPrefs.cardRadius(prefs)).accent);
        seek.setListener(new ScrubBar.Listener() {
            @Override public void onScrubStart() { dragging = true; }
            @Override public void onScrub(long positionMs) {
                if (timeNow != null) timeNow.setText(ReceiverServer.clock(positionMs));
            }
            @Override public void onScrubEnd(long positionMs) {
                dragging = false;
                if (player != null) { player.seekTo(positionMs); updateSnapshot(); }
            }
        });
        hudBottomBar.addView(seek, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);
        timeNow = new TextView(this);
        timeNow.setTextColor(Color.WHITE);
        timeNow.setTextSize(11.5f);
        timeNow.setText("0:00");
        times.addView(timeNow, new LinearLayout.LayoutParams(0, -2, 1f));
        timeTotal = new TextView(this);
        timeTotal.setTextColor(0xFFAAAAAA);
        timeTotal.setTextSize(11.5f);
        timeTotal.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        timeTotal.setText("0:00");
        times.addView(timeTotal, new LinearLayout.LayoutParams(0, -2, 1f));
        hudBottomBar.addView(times, new LinearLayout.LayoutParams(-1, -2));

        // Fixed five-icon dock: each action keeps a 48dp touch target and no
        // horizontal scrolling or duplicate rotate/external-player controls.
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(2), dp(2), dp(2), dp(2));
        dock.addView(dockIcon(R.drawable.ic_subtitles, "Subtitles", v -> togglePanel()),
            dockWeight());
        dock.addView(dockIcon(R.drawable.ic_audio_track, "Audio tracks and video quality",
            v -> showTrackSheet()), dockWeight());
        aspectDockButton = dockIcon(R.drawable.ic_aspect_fit,
            "Aspect ratio: " + VideoViewport.modeLabel(aspectMode), v -> cycleAspect());
        dock.addView(aspectDockButton, dockWeight());
        dock.addView(speedDockControl(), dockWeight());
        dock.addView(dockIcon(R.drawable.ic_search, "Search for a title",
            v -> openTitleSearch()), dockWeight());
        hudBottomBar.addView(dock, new LinearLayout.LayoutParams(-1, dp(52)));
        box.addView(hudBottomBar, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    /** Legacy helper retained for the scrollable in-sheet tool rows. */
    private TextView chip(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFE2E8F0);
        t.setTextSize(11.5f);
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

    /** v3.3.1 — the ONE card radius the player paints (the user's Customize choice:
     *  Sharp 6dp / Rounded 16dp / Apple Pill 28dp). v3.3.0 hard-coded dp(16) here,
     *  so the Card-corner-radius setting had no effect inside the player. */
    private int radiusCard() { return dp(ThemeEngine.radii(AppPrefs.cardRadius(prefs))[1]); }
    private int radiusSheet() { return dp(ThemeEngine.radii(AppPrefs.cardRadius(prefs))[1]); }

    private LinearLayout buildPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radiusCard());
        ThemeEngine.Palette palette = pal();
        bg.setColor(ThemeEngine.cardFill(palette, AppPrefs.glass(prefs)));
        if (AppPrefs.glass(prefs)) {
            bg.setStroke(dp(1), ThemeEngine.hairline(palette, AppPrefs.borderAlpha(prefs)));
        }
        p.setBackground(bg);
        p.setPadding(dp(14), dp(12), dp(14), dp(12));

        p.addView(label(Chrome.pick("අකුරේ ප්‍රමාණය · Font size")));
        p.addView(step("−", "+", () -> fontSp(-1), () -> fontSp(1)));
        p.addView(label(Chrome.pick("පසුබිම් අඳුන · Mask")));
        p.addView(step("−", "+", () -> mask(-10), () -> mask(10)));
        p.addView(label(Chrome.pick("කාල ගැලපුම · Sync nudge")));
        p.addView(step("−1s", "+1s", () -> nudge(-1000), () -> nudge(1000)));
        final long syncStep = AppPrefs.syncStepMs(prefs);      // v3.1.0: 0.1/0.25/0.5 s
        final String syncLabel = syncStep >= 1000 ? "1s" : (syncStep / 1000f) + "s";
        p.addView(step("−" + syncLabel, "+" + syncLabel, () -> nudge(-syncStep), () -> nudge(syncStep)));

        Button retry = btn(Chrome.pick("නැවත ලබාගන්න · Refetch subs"));
        retry.setOnClickListener(v -> { panel.setVisibility(View.GONE); fetchSinhala(); });
        p.addView(retry);

        // v3.2.0 — the FREE keyless translator button (the zero-quota guarantee:
        // whatever is showing can be translated RIGHT NOW, worker or not).
        Button free = btn(R.drawable.ic_translate, t2("නොමිලේ පරිවර්තනය · Free translate now"));
        free.setOnClickListener(v -> { panel.setVisibility(View.GONE); manualFreeTranslate(); });
        p.addView(free);

        // v3.2.0 — the URL-pasted .srt/.zip loader (Baiscope/Zoom/SubDL links:
        // auto-download → auto-unzip → script check → auto-translate → attach).
        final EditText urlIn = new EditText(this);
        urlIn.setHint(Chrome.pick("subtitle URL (.srt/.zip) paste · සබ් URL"));
        urlIn.setTextSize(13);
        p.addView(urlIn, new LinearLayout.LayoutParams(-1, -2));
        Button loadUrl = btn(R.drawable.ic_link,
            Chrome.pick("Load subtitles from URL · URL උපසිරැසි එක ලබාගන්න"));
        loadUrl.setOnClickListener(v -> {
            String u = urlIn.getText().toString().trim();
            if (u.startsWith("http://") || u.startsWith("https://")) {
                panel.setVisibility(View.GONE);
                loadSubsFromUrl(u);
            }
        });
        p.addView(loadUrl);

        final EditText in = new EditText(this);
        in.setHint(Chrome.pick("Title override · නම අතින්"));
        in.setTextSize(13);
        in.setText(title);
        p.addView(in, new LinearLayout.LayoutParams(-1, -2));
        Button go = btn(Chrome.pick("Search for subtitles · Go — worker එකෙන් හාන්ගතකරන්න"));
        go.setOnClickListener(v -> {
            String t = in.getText().toString().trim();
            if (t.length() >= 2) { panel.setVisibility(View.GONE); manualFetch(t, season, episode); }
        });
        p.addView(go);

        // v3.3.0 — THE SRI LANKAN QUICK-FINDER (Baiscope / Zoom.lk / PirateLK /
        // SubDL / OpenSubtitles + a DuckDuckGo fallback). One tap opens the app's
        // Web tab on that site's real search for THIS movie; every .zip/.srt the
        // user then taps is captured, unzipped and attached to this very player.
        Button lk = btn(R.drawable.ic_srilanka_sub, Chrome.pick("Find Sinhala Sub (SL sites) · සිංහල සොයන්න"));
        lk.setOnClickListener(v -> { panel.setVisibility(View.GONE); openLkFinder(); });
        p.addView(lk);

        Button load = btn("local .srt / .zip load");
        load.setOnClickListener(v -> pickLocal());
        p.addView(load);

        Button close = btn(Chrome.pick("වසන්න · Close"));
        close.setOnClickListener(v -> panel.setVisibility(View.GONE));
        p.addView(close);
        return p;
    }

    // ── v3.2.0 — THE IN-PLAYER SETTINGS SHEET (the ⚙ button) ──────────────────

    /** The translucent bottom sheet: playback speed · aspect ratio · LIVE
     *  subtitle style · sync · free-translate · the language quick switch —
     *  everything customizable WITHOUT leaving the movie. */
    private LinearLayout buildSettingsSheet() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        ThemeEngine.Palette p = pal();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(radiusSheet());
        bg.setColor(ThemeEngine.cardFill(p, AppPrefs.glass(prefs)));
        if (AppPrefs.glass(prefs)) {
            bg.setStroke(dp(1), ThemeEngine.hairline(p, AppPrefs.borderAlpha(prefs)));
        } else {
            bg.setStroke(0, 0);
        }
        s.setBackground(bg);
        s.setPadding(dp(16), dp(12), dp(16), dp(16));

        TextView head = new TextView(this);
        head.setText(Chrome.pick("Player settings · ධාවක සැකසුම්"));
        UiIcons.setCompound(head, R.drawable.ic_settings, 0xFFE2E8F0, 15, Gravity.START);
        head.setTextColor(0xFFE2E8F0);
        head.setTextSize(14);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        s.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // playback speed — the LIVE parameter (0.5x..2.0x, applied instantly)
        s.addView(label(Chrome.pick("ධාවන වේගය · Playback speed")));
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView speedVal = new TextView(this);
        speedVal.setTextColor(0xFF9BE8FF);
        speedVal.setTextSize(12.5f);
        float cur = 1f;
        try { cur = player == null ? 1f : player.getPlaybackParameters().speed; } catch (Throwable ignored) { }
        final float curSpeed = cur;
        speedVal.setText(String.format(Locale.US, "%.2fx", curSpeed));
        s.addView(speedVal, new LinearLayout.LayoutParams(-1, -2));
        for (final float f : new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f}) {
            Button b = btn(f == 1f ? "1x" : String.format(Locale.US, "%.2gx", f));
            b.setTextSize(11.5f);
            b.setOnClickListener(v -> {
                try {
                    if (player != null) player.setPlaybackSpeed(f);
                    prefs.edit().putInt(AppPrefs.K_PLAY_SPEED, Math.round(f * 100)).apply();
                    speedVal.setText(String.format(Locale.US, "%.02fx", f));
                    flash(String.format(Locale.US, "%.02fx", f));
                } catch (Throwable t) { Diag.w("sheet", "speed: " + t); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(3);
            speedRow.addView(b, lp);
        }
        s.addView(speedRow, new LinearLayout.LayoutParams(-1, -2));

        // Exact aspect modes, available in one touch.
        s.addView(label(Chrome.pick("රූප පරිමාණය · Aspect")));
        s.addView(UiSegmented.make(this, p, prefs,
            new String[]{"Fit", "Fill", "16:9", "Stretch"}, aspectMode,
            this::setAspect), new LinearLayout.LayoutParams(-1, -2));

        s.addView(label(Chrome.pick("නිදාගැනීමේ කාලය · Sleep timer")));
        long sleepMs = AppPrefs.sleepTimerMs(prefs);
        int sleepChoice = sleepMs >= 3600000L ? 3 : (sleepMs >= 1800000L ? 2
            : (sleepMs >= 900000L ? 1 : 0));
        s.addView(UiSegmented.make(this, p, prefs,
            new String[]{"Off", "15m", "30m", "60m"}, sleepChoice, i -> {
                long[] values = {0L, 900000L, 1800000L, 3600000L};
                prefs.edit().putLong(AppPrefs.K_SLEEP_TIMER_MS, values[i]).apply();
                armSleepTimer(values[i]);
            }), new LinearLayout.LayoutParams(-1, -2));

        // LIVE subtitle style sliders (the same studio knobs, applied instantly)
        s.addView(label(Chrome.pick("අකුරු ප්‍රමාණය · Font size")));
        s.addView(step("−", "+", () -> fontSp(-1), () -> fontSp(1)));
        s.addView(label(Chrome.pick("පසුබිම් අඳුන · Mask")));
        s.addView(step("−", "+", () -> mask(-10), () -> mask(10)));
        s.addView(label(Chrome.pick("කාල ගැලපුම · Sync nudge")));
        s.addView(step("−0.25s", "+0.25s", () -> nudge(-250), () -> nudge(250)));

        // the language quick switch (8 chips, the "subs" essentials)
        s.addView(label(Chrome.pick("භාෂාව · Language")));
        LinearLayout langQuick = new LinearLayout(this);
        langQuick.setOrientation(LinearLayout.HORIZONTAL);
        for (final Langs.Lang l : Langs.chips()) {
            Button b = btn(l.nativeName);
            b.setTextSize(11.5f);
            b.setOnClickListener(v -> switchLang(l.code));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.rightMargin = dp(3);
            langQuick.addView(b, lp);
        }
        s.addView(langQuick, new LinearLayout.LayoutParams(-1, -2));

        Button close = btn(Chrome.pick("වසන්න · Done"));
        close.setOnClickListener(v -> sheet.setVisibility(View.GONE));
        s.addView(close);
        return s;
    }

    private UiSheet.Handle settingsSheet;
    private UiSheet.Handle trackSheet;
    private UiSheet.Handle subtitleSheet;

    /** v3.4.0 - Player settings as a real modal sheet (60% height: the video stays visible). */
    private void toggleSheet() {
        if (settingsSheet != null) { settingsSheet.dismiss(); settingsSheet = null; return; }
        ThemeEngine.Palette p = ThemeEngine.palette(
            AppPrefs.themeMode(prefs), 0, AppPrefs.accent(prefs), AppPrefs.accentHex(prefs),
            AppPrefs.cardRadius(prefs));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(UiSheet.header(this, p, t2("Player settings"),
            t2("වේගය · රූප පරිමාණය · නිදාගැනීම · හඬ")), new LinearLayout.LayoutParams(-1, -2));

        // speed slider with detents
        float cur = 1f;
        try { cur = player == null ? 1f : player.getPlaybackParameters().speed; } catch (Throwable ignored) { }
        body.addView(UiSlider.make(this, p, prefs, "ධාවන වේගය", "Playback speed",
            25, 250, Math.round(cur * 100), "%", v -> {
                try {
                    if (player != null) player.setPlaybackSpeed(v / 100f);
                    flash(String.format(Locale.US, "%.2fx", v / 100f));
                } catch (Throwable t) { Diag.w("sheet", "speed: " + t); }
            }), new LinearLayout.LayoutParams(-1, -2));

        body.addView(UiSegmented.row(this, p, prefs, "රූප පරිමාණය", "Aspect",
            new String[]{t2("Fit"), t2("Fill"), "16:9", t2("Stretch")},
            aspectIndex(), i -> {
                String mode = new String[]{AppPrefs.ASPECT_FIT, AppPrefs.ASPECT_FILL,
                    AppPrefs.ASPECT_169, AppPrefs.ASPECT_STRETCH}[i];
                prefs.edit().putString(AppPrefs.K_ASPECT_MODE, mode).apply();
                aspectMode = VideoViewport.modeFrom(mode);
                applyVideoSize();
                paintAspectDock();
            }), new LinearLayout.LayoutParams(-1, -2));

        body.addView(UiSegmented.row(this, p, prefs, "නිදාගැනීමේ කාලය", "Sleep timer",
            new String[]{t2("Off"), "15m", "30m", "60m"}, sleepIndex(), i -> {
                long ms = new long[]{0L, 900000L, 1800000L, 3600000L}[i];
                prefs.edit().putLong(AppPrefs.K_SLEEP_TIMER_MS, ms).apply();
                armSleepTimer(ms);
            }), new LinearLayout.LayoutParams(-1, -2));

        body.addView(UiSlider.make(this, p, prefs, "හඬ වැඩි කිරීම", "Volume boost",
            0, 150, AppPrefs.volumeBoost(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_VOLUME_BOOST, v).apply();
                applyVolumeBoost();
            }), new LinearLayout.LayoutParams(-1, -2));

        UiGroup.switchRow(this, body, p, prefs, R.drawable.ic_speed,
            "දෙවරක් තට්ටු", "Double-tap to seek", AppPrefs.K_DOUBLE_TAP_SEEK, true);
        UiGroup.switchRow(this, body, p, prefs, R.drawable.ic_brightness,
            "තිරය දැල්වී", "Keep the screen on", AppPrefs.K_KEEP_SCREEN_ON, true);

        Button done = btn(R.drawable.ic_check_circle, t2("Done"));
        done.setOnClickListener(v -> { if (settingsSheet != null) settingsSheet.dismiss(); settingsSheet = null; });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(10);
        body.addView(done, dlp);

        settingsSheet = UiSheet.show(this, p, prefs, body, 62, () -> settingsSheet = null);
        showStatus();
    }

    /**
     * v3.3.0 — THE ASPECT-RATIO APPLIER (replaces v3.2.0's resize method, which
     * only ever set MATCH_PARENT and therefore stretched every video).
     *
     * The surface's box is computed by {@link VideoViewport#compute} from the DECODED
     * size (onVideoSizeChanged) and the host's real measured size (onLayoutChange),
     * then centred on black. Fit = letterbox, Fill = proportional crop, 16:9 = a
     * forced 16:9 box, Stretch = the only distorting mode and only on request.
     */
    private void applyVideoSize() {
        if (surface == null || videoHost == null) return;
        int cw = videoHost.getWidth(), ch = videoHost.getHeight();
        if (cw <= 0 || ch <= 0) return;
        int[] size = VideoViewport.compute(videoW, videoH, videoPar, cw, ch, aspectMode);
        // v3.3.1 — never an unchecked cast: if the surface ever gets re-parented
        // or re-typed, we rebuild a correct FrameLayout.LayoutParams instead of
        // throwing ClassCastException on the UI thread.
        FrameLayout.LayoutParams lp = surface.getLayoutParams() instanceof FrameLayout.LayoutParams
            ? (FrameLayout.LayoutParams) surface.getLayoutParams()
            : new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        lp.width = size[0];
        lp.height = size[1];
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
        surface.requestLayout();
    }

    /** Media3 tells us the real decoded geometry — remember it and re-size. */
    private void setVideoSize(int w, int h, float par) {
        videoW = w;
        videoH = h;
        videoPar = par;
        applyVideoSize();
    }

    /** The 📐 chip: Fit → Fill → 16:9 → Stretch → Fit. */
    private void cycleAspect() {
        aspectMode = VideoViewport.next(aspectMode);
        prefs.edit().putString(AppPrefs.K_ASPECT_MODE, VideoViewport.modeName(aspectMode)).apply();
        paintAspectDock();
        applyVideoSize();
        flash(VideoViewport.modeLabel(aspectMode));
    }

    /** The ⏩ chip: 0.75x → 1.0x → 1.25x → 1.5x → 2.0x → 0.75x. */
    private void cycleSpeed() {
        // v3.4.2 — 0.5× … 2.5× (the brief's range), not the old 0.75–2.0
        float[] steps = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f};
        float cur = 1f;
        try { if (player != null) cur = player.getPlaybackParameters().speed; } catch (Throwable ignored) { }
        int next = 1;
        for (int i = 0; i < steps.length; i++) if (Math.abs(steps[i] - cur) < 0.01f) next = (i + 1) % steps.length;
        float f = steps[next];
        try { if (player != null) player.setPlaybackSpeed(f); } catch (Throwable ignored) { }
        prefs.edit().putInt(AppPrefs.K_PLAY_SPEED, Math.round(f * 100)).apply();
        if (speedChip != null) speedChip.setText(String.format(Locale.US, "%.2gx", f));
        paintSpeedDock();
        flash(String.format(Locale.US, "%.2gx", f));
    }

    /** 1-tap Portrait ↔ Landscape (setRequestedOrientation, like VLC's rotate button). */
    private void rotateScreen() {
        int cur = getResources().getConfiguration().orientation;
        setRequestedOrientation(cur == Configuration.ORIENTATION_LANDSCAPE
            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        flash(cur == Configuration.ORIENTATION_LANDSCAPE ? "Portrait" : "Landscape");
        main.postDelayed(this::applyVideoSize, 350);
    }

    /** Lock every touch; the single unlock pill is the only way back. */
    /** v3.4.0 - the sleep timer: 0 = off, -1 = end of video, else a one-shot pause. */
    private void armSleepTimer(long ms) {
        main.removeCallbacks(sleepRunnable);
        if (ms > 0) {
            main.postDelayed(sleepRunnable, ms);
            flash("Sleep timer " + (ms / 60000L) + "m");
        } else if (ms < 0) {
            flash("Sleep at end of video");
        } else {
            flash("Sleep timer off");
        }
    }

    private final Runnable sleepRunnable = new Runnable() {
        @Override public void run() {
            try {
                if (player != null) player.pause();
                flash("Sleep timer - paused");
            } catch (Throwable t) {
                Diag.w("player", "sleep: " + t);
            }
        }
    };

    /** v3.4.0 - the volume boost (0..150%) applied through player.setVolume. No FFmpeg. */
    private void applyVolumeBoost() {
        try {
            if (player != null) player.setVolume(AppPrefs.volumeBoost(prefs) / 100f);
        } catch (Throwable t) {
            Diag.w("player", "volume boost: " + t);
        }
    }

    private int aspectIndex() {
        String v = AppPrefs.aspectMode(prefs);
        if (AppPrefs.ASPECT_FILL.equals(v)) return 1;
        if (AppPrefs.ASPECT_169.equals(v)) return 2;
        if (AppPrefs.ASPECT_STRETCH.equals(v)) return 3;
        return 0;
    }

    private int sleepIndex() {
        long v = AppPrefs.sleepTimerMs(prefs);
        if (v == 900000L) return 1;
        if (v == 1800000L) return 2;
        if (v >= 3600000L) return 3;
        return 0;
    }

    private int registerIndex() {
        String r = prefs.getString(AppPrefs.K_REGISTER, null);
        return "spoken".equals(r) ? 1 : ("formal".equals(r) ? 2 : 0);
    }

    private void setLocked(boolean on) {
        locked = on;
        if (unlockPill != null) unlockPill.setVisibility(on ? View.VISIBLE : View.GONE);
        if (hud != null) hud.setVisibility(on ? View.GONE : View.VISIBLE);
        hudVisible = !on;
        if (!on) scheduleHudHide();
        flash(on ? "Locked" : "Unlocked");
    }

    // ── v3.2.0 — THE 1-TAP MOVIE TITLE SEARCH (the junk-title pill's action) ──

    /** The in-player title search dialog: type the movie/series name (+ optional
     *  S/E) → instant /api/resolve → instant Sinhala subtitles. This is the
     *  REPLACEMENT for the raw "no Cinemeta match for: ArrowTV Player" JSON
     *  banners — junk titles route HERE, never to Cinemeta. */
    private void openTitleSearch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(4), dp(6), 0);
        final EditText nameIn = new EditText(this);
        nameIn.setHint(t2("චිත්‍රපට / කතා මාලාවේ නම · movie / series name"));
        nameIn.setTextSize(13);
        nameIn.setTextColor(0xFFE2E8F0);
        box.addView(nameIn, new LinearLayout.LayoutParams(-1, -2));
        final EditText seIn = new EditText(this);
        seIn.setHint(t2("අදාළ නම්: S01E02 (හිස් = චිත්‍රපටයක්) · optional S01E02"));
        seIn.setTextSize(13);
        seIn.setTextColor(0xFFE2E8F0);
        box.addView(seIn, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this)
            .setTitle(t2("නම සොයන්න · Title search"))
            .setView(box)
            .setPositiveButton(t2("සිංහල උපසිරැසි ලබාගන්න · Get subs"), (d, w) -> {
                String name = nameIn.getText().toString().trim();
                if (name.length() < 2) return;
                int s = season, e = episode;
                String se = seIn.getText().toString().trim().toLowerCase(Locale.ROOT);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("s(\\d{1,2})\\s*e(\\d{1,3})").matcher(se);
                if (m.find()) { s = Integer.parseInt(m.group(1)); e = Integer.parseInt(m.group(2)); }
                hudTitle.setText(name);
                flash(name);
                manualFetch(name, s, e);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** The tiny bilingual helper (PlayActivity has no AppPrefs.pick dependency loop). */
    private String t2(String s) { return Chrome.pick(s); }

    // ── v3.2.0 — THE URL SUBTITLE LOADER (paste a .srt/.zip/.gz link) ──────────

    /** Download a subtitle URL (HTTPS-only, ≤5 MB zip-bomb safe), extract via
     *  SrtLoader (.zip/.gz/.srt all supported), script-check, auto-translate
     *  non-Sinhala, attach to the playing video. */
    private void loadSubsFromUrl(String subUrl) {
        final int myGen = fetchGen;
        setState("fetching", Chrome.pick("URL උපසිරැසි ලබාගනිමින්… · fetching the subtitle URL…"), myGen);
        new Thread(() -> {
            try {
                byte[] raw = httpGet(subUrl, null);
                List<Cue> loaded = SrtLoader.load(raw, subUrl);
                if (myGen != fetchGen) return;
                main.post(() -> onLocalCues(loaded, StreamSniffer.hostOf(subUrl)));
            } catch (Exception e) {
                Diag.e("subs", "url subs: " + e);
                setState("error", "URL උපසිරැසි බාගැනීම අසාර්ථක — " + e.getMessage(), myGen);
            }
        }, "si-urlsubs").start();
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
    /**
     * v3.4.2 — THE GIANT-ICON FIX (screenshots 1 and 3).
     *
     * <p>v3.4.1 drew a 24dp-intrinsic vector into a 36dp plate with <b>zero padding</b>:
     * the glyph touched the plate's edge and read as "icons bursting out of their
     * circles". Every HUD icon is now a {@value #ICON_BOX_DP}dp container with
     * {@code (44 - size)/2}dp of real internal padding, so a 20dp vector sits crisply
     * centred inside an accessible 44dp touch target.
     */
    private static final int ICON_BOX_DP = 48;

    private View icon(int resId, float sizeDp, View.OnClickListener click) {
        return UiGlassIcon.create(this, prefs, resId, Color.WHITE, Math.round(sizeDp),
            ICON_BOX_DP, iconDescription(resId), click);
    }

    private String iconDescription(int resId) {
        if (resId == R.drawable.ic_back) return "Back";
        if (resId == R.drawable.ic_lock) return "Lock player controls";
        if (resId == R.drawable.ic_rotate) return "Change screen orientation";
        if (resId == R.drawable.ic_pip) return "Enter picture-in-picture";
        if (resId == R.drawable.ic_vlc_external) return "Open in external player";
        if (resId == R.drawable.ic_settings) return "Player settings";
        if (resId == R.drawable.ic_replay_10) return "Seek back";
        if (resId == R.drawable.ic_forward_10) return "Seek forward";
        if (resId == R.drawable.ic_play_filled) return "Play";
        if (resId == R.drawable.ic_pause_filled) return "Pause";
        return "Player control";
    }

    private LinearLayout.LayoutParams dockWeight() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private View dockIcon(int resId, String description, View.OnClickListener click) {
        FrameLayout button = UiGlassIcon.create(this, prefs, resId, Color.WHITE, 20,
            48, description, click);
        if (resId == R.drawable.ic_subtitles) {
            subtitleDockDot = new View(this);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFF4ADE80);
            subtitleDockDot.setBackground(dot);
            subtitleDockDot.setVisibility(firstCueRendered ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(7), dp(7),
                Gravity.END | Gravity.BOTTOM);
            dotLp.rightMargin = dotLp.bottomMargin = dp(9);
            button.addView(subtitleDockDot, dotLp);
        }
        return button;
    }

    private View speedDockControl() {
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.VERTICAL);
        control.setGravity(Gravity.CENTER);
        control.setMinimumWidth(dp(48));
        control.setMinimumHeight(dp(48));
        control.setFocusable(true);
        control.setClickable(true);
        control.setOnClickListener(v -> cycleSpeed());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1AFFFFFF);
        bg.setCornerRadius(dp(14));
        control.setBackground(bg);
        ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(UiIcons.tinted(this, R.drawable.ic_speed, Color.WHITE, 18));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        control.addView(glyph, new LinearLayout.LayoutParams(dp(22), dp(22)));
        speedChip = new TextView(this);
        speedChip.setTextColor(Color.WHITE);
        speedChip.setGravity(Gravity.CENTER);
        speedChip.setTextSize(10.5f);
        speedChip.setTypeface(Typeface.DEFAULT_BOLD);
        float initial = 1f;
        try { if (player != null) initial = player.getPlaybackParameters().speed; }
        catch (Throwable ignored) { }
        speedChip.setText(String.format(Locale.US, "%.2gx", initial));
        control.addView(speedChip, new LinearLayout.LayoutParams(-1, -2));
        speedDockButton = control;
        paintSpeedDock();
        return control;
    }

    private void paintSubtitleDock() {
        if (subtitleDockDot != null) {
            subtitleDockDot.setVisibility(firstCueRendered ? View.VISIBLE : View.GONE);
        }
    }

    private void paintAspectDock() {
        if (aspectDockButton != null) {
            aspectDockButton.setContentDescription("Aspect ratio: " + VideoViewport.modeLabel(aspectMode));
        }
    }

    private void paintSpeedDock() {
        if (speedDockButton == null) return;
        String value = speedChip == null ? "1x" : speedChip.getText().toString();
        speedDockButton.setContentDescription("Playback speed: " + value);
    }

    /** The transport disc swaps between play and pause - it never did before v3.4.0. */
    private void setPlayPauseIcon(boolean playing) {
        if (!(playPause instanceof ImageView)) return;
        // v3.4.1 — the transport disc is 24dp, the same as the button that was built.
        // v3.4.0 drew it at 26dp and the first play/pause swap silently resized it.
        ((ImageView) playPause).setImageDrawable(UiIcons.tinted(this,
            playing ? R.drawable.ic_pause_filled : R.drawable.ic_play_filled, Color.WHITE, 24));
        playPause.setContentDescription(playing ? "Pause playback" : "Play playback");
    }

    /** A pill button with a leading vector (replaces pillBtn("\uD83D\uDCAC Subtitles")). */
    private View pillBtn(int resId, String label, View.OnClickListener click) {
        View v = pillBtn(label, click);
        if (v instanceof TextView) UiIcons.setCompound((TextView) v, resId, 0xFFE2E8F0, 13, Gravity.START);
        return v;
    }

    /** A mode chip with a leading vector (aspect / speed). */
    private TextView chip(int resId, String text, View.OnClickListener click) {
        TextView t = chip(text, click);
        UiIcons.setCompound(t, resId, 0xFFE2E8F0, 13, Gravity.START);
        return t;
    }

    /** A full-width sheet button with a leading vector. */
    private Button btn(int resId, String text) {
        Button b = btn(text);
        UiIcons.setCompound(b, resId, Color.WHITE, 15, Gravity.START);
        return b;
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

    /**
     * v3.4.2 — the old floating action rows are deleted, so "expose the 1-tap fixes" now
     * means exactly two things: the single status pill stays readable, and the
     * Subtitles chip is one tap away. Nothing is ever laid over the transport.
     */
    private void showSubActions() {
        if (statusPill != null) {
            statusPill.setVisibility(View.VISIBLE);
            main.removeCallbacks(pillHide);
            main.postDelayed(pillHide, 6000);   // failures stay a little longer
        }
    }

    /**
     * v2.2 script-safe styling — the ONE place the subtitle view's typeface and
     * base text direction are derived from the active language.
     *
     * v3.2.0 — THE BOLD/GARBLE FIX (the field screenshot's broken Sinhala): the
     * old body had TWO competing setters — applyLangStyle() set the family and
     * applyStudioStyle() re-called setTypeface(getTypeface(), BOLD), which
     * DROPPED the studio bold whenever a language switch or per-cue render ran
     * first. NOW this method is the ONLY typeface setter and it applies BOLD the
     * Android-correct way — Typeface.create(family, style) — merging family +
     * the studio's bold flag in ONE call, on every switch AND every studio
     * change. setTypeface(tf, style) with a null family NEVER happens again. */
    private void applyLangStyle() {
        if (subView == null) return;
        // Subtitle family is independent from the app chrome. The Noto choice uses
        // the bundled regular/semibold Sinhala files; Cinema Bold uses bundled Inter.
        boolean bold = AppPrefs.subBold(prefs);
        Typeface tf;
        String family = AppPrefs.fontFamily(prefs);
        if (AppPrefs.FONT_CINEMA.equals(family)) {
            tf = UiFonts.cinemaTypeface(this, bold);
        } else if (AppPrefs.FONT_SERIF.equals(family)) {
            tf = Typeface.create(Typeface.SERIF, bold ? Typeface.BOLD : Typeface.NORMAL);
        } else if (AppPrefs.FONT_SYSTEM.equals(family)) {
            tf = Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        } else {
            tf = bold ? siFontSemi : siFont;
        }
        subView.setTypeface(tf);
        subView.setTextDirection(Langs.isRtl(activeLang)
            ? android.view.View.TEXT_DIRECTION_ANY_RTL : android.view.View.TEXT_DIRECTION_LTR);
    }

    // ── v3.1.0: the Subtitle Styling Studio application (Settings → Subtitles) ──
    // Color preset · pill/outline background + opacity · bold · bottom position
    // (4–28% of screen height) — every value pre-clamped by AppPrefs.

    /** (Re)apply every studio dimension to the live subtitle view.
     *  v3.2.0 — NEVER touches the typeface (applyLangStyle owns family+bold);
     *  re-calls applyLangStyle() so a studio BOLD flip lands immediately. */
    private void applyStudioStyle() {
        if (subView == null) return;
        // v3.3.0 — the balanced cinema size: 18 sp portrait / 20 sp landscape
        // (v3.2.0's 22 sp default is what the field screenshots called oversized).
        subView.setTextSize(AppPrefs.fontSpFor(prefs,
            getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE));
        subView.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.88f));
        subView.setLineSpacing(dp(2), AppPrefs.subLineSpacing(prefs) / 100f);   // 100..150 % studio spacing
        subView.setTextColor(AppPrefs.subColorArgb(prefs));
        subView.setShadowLayer(dp(Math.max(1, AppPrefs.subOutline(prefs))), 0, 0, 0xFF000000);
        applyLangStyle();                                      // the ONE typeface setter
        if (AppPrefs.BG_OUTLINE.equals(AppPrefs.subBg(prefs))) {
            // crisp black edge/outline only — no pill background
            subView.setBackgroundColor(Color.TRANSPARENT);
            subView.setShadowLayer(dp(4), 0, 0, 0xFF000000);
        } else if (AppPrefs.BG_BOX.equals(AppPrefs.subBg(prefs))) {
            subView.setBackgroundColor(ThemeEngine.pillColor(Math.max(90, AppPrefs.subBgOpacity(prefs))));
            subView.setShadowLayer(dp(2), 0, 0, 0x99000000);
        } else {
            subView.setBackgroundColor(ThemeEngine.pillColor(AppPrefs.subBgOpacity(prefs)));
            subView.setShadowLayer(dp(2), 0, 0, 0x99000000);
        }
        subView.setPadding(dp(12), dp(4), dp(12), dp(4));
        subView.setVisibility(subView.getVisibility());
        FrameLayout.LayoutParams sl = subLayoutParams();
        subView.setLayoutParams(sl);
    }

    /**
     * v3.4.2 — THE SUBTITLE GEOMETRY (screenshot 2).
     *
     * <p>THE FIELD BUG: the Sinhala line rendered DEAD-CENTRE, straight across the
     * actors' faces, because the view's own gravity and the layer padding fought each
     * other. It is now anchored strictly {@code Gravity.BOTTOM | CENTER_HORIZONTAL} with
     * a compact cinema margin: <b>5%</b> of the height in landscape and <b>8%</b> in
     * portrait (the studio's own slider still overrides it), and it is never allowed
     * above 20% so a subtitle can never climb into the picture.
     */
    private FrameLayout.LayoutParams subLayoutParams() {
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int h = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        boolean land = getResources().getConfiguration().orientation
            == Configuration.ORIENTATION_LANDSCAPE;
        int stored = AppPrefs.subBottomPct(prefs);
        // 10 is the untouched default → use the clean cinema pair (5% / 8%).
        int pct = stored == 10 ? (land ? 5 : 8) : Math.min(20, stored);
        sl.bottomMargin = Math.round(h * pct / 100f);
        sl.leftMargin = sl.rightMargin = dp(8);
        return sl;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // v3.4.2 — HUD plates, immersive fullscreen, orientation, speed boost,
    //          watch history, midnight dimmer and the AI subtitle helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** A 44dp top-bar touch target with a 3dp gutter. */
    private LinearLayout.LayoutParams topIconParams() {
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(dp(ICON_BOX_DP), dp(ICON_BOX_DP));
        lp.leftMargin = lp.rightMargin = dp(1);
        return lp;
    }

    // ── TRUE IMMERSIVE FULLSCREEN (screenshot 2: the status bar and the gesture bar) ──

    /**
     * Hide BOTH system bars while the HUD is hidden (the only honest "fullscreen" on
     * Android 16), and bring them back the moment the HUD is tapped. A swipe from the
     * edge still reveals them transiently, so nothing is ever unreachable.
     */
    private void applyImmersive(boolean immersive) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c == null) return;
                if (immersive) {
                    c.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
                } else {
                    c.show(android.view.WindowInsets.Type.statusBars()
                        | android.view.WindowInsets.Type.navigationBars());
                }
            } else {
                int flags = immersive
                    ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                       | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                    : 0;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            Diag.w("player", "immersive: " + t);
        }
    }

    // ── ORIENTATION: auto-rotate (sensor) vs locked ───────────────────────────

    /**
     * The 1-tap orientation sheet: Auto sensor / Lock landscape / Lock portrait.
     * "Auto" uses SCREEN_ORIENTATION_FULL_SENSOR so the phone genuinely turns with the
     * device even when the system rotation lock is on — a video player is the one place
     * that behaviour is wanted.
     */
    private void showOrientationSheet() {
        final String[] vals = {AppPrefs.ORIENT_AUTO, AppPrefs.ORIENT_LANDSCAPE, AppPrefs.ORIENT_PORTRAIT};
        String cur = AppPrefs.orientation(prefs);
        int sel = AppPrefs.ORIENT_LANDSCAPE.equals(cur) ? 1
            : (AppPrefs.ORIENT_PORTRAIT.equals(cur) ? 2 : 0);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Orientation")
            .setSingleChoiceItems(new String[]{"Auto-rotate (sensor)", "Lock landscape", "Lock portrait"},
                sel, (d, which) -> {
                    prefs.edit().putString(AppPrefs.K_ORIENTATION, vals[which]).apply();
                    applyOrientation();
                    d.dismiss();
                    flash(which == 0 ? "Auto-rotate" : (which == 1 ? "Locked landscape" : "Locked portrait"));
                })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Apply the stored orientation mode to the window (never throws). */
    private void applyOrientation() {
        try {
            String o = AppPrefs.orientation(prefs);
            if (AppPrefs.ORIENT_LANDSCAPE.equals(o))
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            else if (AppPrefs.ORIENT_PORTRAIT.equals(o))
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            else
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        } catch (Throwable t) {
            Diag.w("player", "orientation: " + t);
        }
    }

    // ── HOLD-TO-2x SPEED BOOST (YouTube / VLC) ────────────────────────────────

    private boolean boosting;
    private float speedBeforeBoost = 1f;

    private final Runnable boostStart = new Runnable() {
        @Override public void run() {
            if (locked || player == null) return;
            try {
                speedBeforeBoost = player.getPlaybackParameters().speed;
                player.setPlaybackSpeed(2f);
                boosting = true;
                flash("2.0× Speed");
            } catch (Throwable t) { Diag.w("player", "boost: " + t); }
        }
    };

    /** Release the boost and restore exactly the speed the user had. */
    private void boostOff() {
        if (!boosting) return;
        boosting = false;
        try {
            if (player != null) player.setPlaybackSpeed(speedBeforeBoost);
        } catch (Throwable ignored) { }
        if (gesturePill != null) gesturePill.setVisibility(View.GONE);
    }

    /** One speed setter for the picker AND the chip cycle (0.5× … 2.5×). */
    private void setSpeed(float f) {
        try {
            if (player != null) player.setPlaybackSpeed(f);
        } catch (Throwable ignored) { }
        prefs.edit().putInt(AppPrefs.K_PLAY_SPEED, Math.round(f * 100)).apply();
        if (speedChip != null) speedChip.setText(String.format(Locale.US, "%.2g×", f));
        paintSpeedDock();
        flash(String.format(Locale.US, "%.2g×", f));
    }

    // ── WATCH HISTORY ─────────────────────────────────────────────────────────

    /**
     * Persist the current position. Called on pause/stop/close, on every seek and when
     * the player goes to the background — the Continue-watching row on Home is always
     * accurate to the last second the user actually watched.
     */
    void saveHistory() {
        try {
            if (player == null) return;
            String u = url == null ? "" : url.trim();
            String tt = title == null ? "" : title.trim();
            if (u.isEmpty() && tt.isEmpty()) return;
            long pos = Math.max(0, player.getCurrentPosition());
            long dur = player.getDuration();
            if (dur == C.TIME_UNSET || dur < 0) dur = 0;
            if (pos < 3000) return;                       // 3 s of a title is not a watch
            if (dur > 0 && pos > dur - 5000) pos = dur;   // finished → the row drops away
            WatchHistory.record(prefs, new WatchHistory.Item(
                tt.isEmpty() ? u : tt, u, headersJson, testMode ? "test" : "player", pos, dur,
                System.currentTimeMillis()));
        } catch (Throwable t) {
            Diag.w("player", "history: " + t);
        }
    }

    // ── MIDNIGHT NIGHT DIMMER (in the player) ─────────────────────────────────

    private View dimView;

    /** The same dark-room veil the shell uses, laid over the video (taps still pass). */
    private void applyMidnightDimmer() {
        try {
            if (rootView == null) return;
            if (dimView != null) { rootView.removeView(dimView); dimView = null; }
            boolean on = AppPrefs.midnightDimmer(prefs);
            int pct = AppPrefs.midnightDimPct(prefs);
            if (!on) return;
            View v = new View(this);
            v.setBackgroundColor(Color.argb(Math.round(pct * 2.55f), 0, 0, 0));
            v.setClickable(false);
            v.setFocusable(false);
            rootView.addView(v, new FrameLayout.LayoutParams(-1, -1));
            if (hud != null) hud.bringToFront();
            dimView = v;
        } catch (Throwable t) {
            Diag.w("player", "dimmer: " + t);
        }
    }

    // ── the AI subtitle helpers ───────────────────────────────────────────────

    /** The 1-tap NewZone batch translator (opens in the app's own Chromium tab). */
    private void openNewZoneTranslator() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                .putExtra("openWeb", true)
                .putExtra("webUrl", "https://tools.newzone.top/en/subtitle-translator"));
            flash("NewZone translator");
        } catch (Throwable t) {
            Diag.w("player", "newzone: " + t);
            flash("Could not open the translator");
        }
    }

    /**
     * THE AUDIO → SRT AI FALLBACK, as a 1-tap helper (never a 100 MB on-device model).
     *
     * <p>When no subtitle exists anywhere, the honest options are: (1) the user's own
     * Cloudflare Worker running {@code @cf/openai/whisper}, (2) the user's AI Studio / Gemini
     * workflow with the prompt pre-filled, or (3) a local file the user brings back. All
     * three end in the SAME place: a timestamped {@code .srt} attached live to this player.
     */
    private void showAudioToSrtHelper() {
        final String[] items = {
            "1 · Whisper via my Worker (auto)",
            "2 · Copy the AI Studio transcript prompt",
            "3 · Pick a finished .srt file"
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle("Audio → SRT (AI fallback)")
            .setItems(items, (d, which) -> {
                if (which == 0) {
                    fetchGen++;
                    fetching = false;
                    fetchSinhala();          // the Worker path already speaks Whisper
                    flash("Trying Whisper via the Worker");
                } else if (which == 1) {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        String prompt = "Transcribe the attached audio into a timestamped SRT"
                            + " subtitle file. Keep the original timing, one cue per sentence,"
                            + " then translate each cue into Sinhala. Output ONLY the .srt file."
                            + " Movie: " + (title == null ? "" : title);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("subtitle-prompt", prompt));
                        flash("Prompt copied — paste it in AI Studio");
                    } catch (Throwable t) { Diag.w("player", "prompt: " + t); }
                } else {
                    pickLocal();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
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
        setState("fetching", Langs.nativeOf(code) + Chrome.pick(" උපසිරැසි ලබාගනිමින්… · fetching ") + Langs.englishOf(code) + " subtitles…");
        fetchSinhala();
        // v3.4.2 — the floating rows are gone; nothing to hide any more.
    }

    /** v2.2: the full registry sheet (the "+ More" chip). */
    private void showLangSheet() {
        String[] labels = new String[Langs.ALL.size()];
        for (int i = 0; i < Langs.ALL.size(); i++) {
            Langs.Lang l = Langs.ALL.get(i);
            labels[i] = l.nativeName + " (" + l.english + ")";
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle(Chrome.pick("Subtitle language · උපසිරැසි භාෂාව"))
            .setItems(labels, (d, which) -> switchLang(Langs.ALL.get(which).code))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** v2.2: Spoken ⇄ Formal override (null = auto by genre). */
    private void toggleRegister() {
        registerOverride = registerOverride == null ? "spoken" : ("spoken".equals(registerOverride) ? "formal" : null);
        String label = registerOverride == null ? "auto (genre)" : registerOverride;
        setState(state, "Register: " + label + Chrome.pick(" — refetching… · රටාව: ") + label);
        fetchGen++; fetching = false;
        fetchSinhala();
    }

    /**
     * v2.2 Option-C — the SAFE power-user bridge (zero automation, zero ToS
     * risk): build the cinema-grade prompt + a ≤40 KB chunk of the English
     * cues, copy it to the clipboard, and open the user's REAL browser on
     * AI Studio (a Custom-Tab-style ACTION_VIEW — NEVER a WebView). The user
     * pastes, gets the polished result, and imports it via the .srt loader.
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
                setState(state, n + " cues copied — paste into AI Studio, then import a local .srt · "
                    + n + " පේළි පිටපත් — AI Studio එකට paste කරන්න");
            } catch (android.content.ActivityNotFoundException anf) {
                // no browser installed — the prompt is already on the clipboard, so the
                // user can still paste it anywhere; say exactly that instead of crashing
                Diag.w("subs", "ai studio bridge: no browser (" + anf + ")");
                setState(state, n + " cues copied — but this phone has no browser to open AI Studio · "
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

    /**
     * v3.3.0 — THE VLC/YouTube GESTURE SET, with feedback that never covers the HUD.
     *
     *   · single tap        → toggle the HUD
     *   · double tap L/R    → −/+ the user's seek step, with a ⏪/⏩ ripple
     *   · vertical swipe L  → brightness pill (☀ 68%)
     *   · vertical swipe R  → media volume pill (🔊 75%)
     *   · horizontal swipe  → seek preview (01:14:22 (+15s)) then seek on release
     *   · pinch             → Fit ⇄ Fill
     * While LOCKED only the unlock pill is touchable (the overlay is GONE).
     */
    private void wireGestures(FrameLayout root) {
        final boolean swipes = AppPrefs.gestures(prefs);
        final ScaleGestureDetector pinch = new ScaleGestureDetector(this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (d.getScaleFactor() > 1.15f) { setAspect(VideoViewport.FILL); return true; }
                    if (d.getScaleFactor() < 0.87f) { setAspect(VideoViewport.FIT); return true; }
                    return false;
                }
            });

        // the swipe state
        final float[] startY = {0f}, startX = {0f};
        final long[] startPos = {0L}, previewTo = {-1L};
        final int[] mode = {0};   // 0 none · 1 brightness · 2 volume · 3 seek

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                toggleControls();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (!AppPrefs.doubleTapSeek(prefs)) { toggleControls(); return true; }
                long step = AppPrefs.seekStepMs(prefs);
                boolean right = e.getX() > root.getWidth() / 2f;
                skip(right ? step : -step);
                ripple(right, (right ? "+" : "−") + (step / 1000) + "s");
                return true;
            }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });

        root.setOnTouchListener((v, e) -> {
            if (locked) return true;                       // 🔒 swallow everything
            pinch.onTouchEvent(e);
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = e.getX();
                    startY[0] = e.getY();
                    startPos[0] = player == null ? 0 : player.getCurrentPosition();
                    previewTo[0] = -1;
                    mode[0] = 0;
                    scheduleHudHide();
                    // v3.4.2 — arm the YouTube/VLC-style HOLD-TO-2x speed boost
                    main.removeCallbacks(boostStart);
                    main.postDelayed(boostStart, 550);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - startX[0], dy = e.getY() - startY[0];
                    if (mode[0] == 0) {
                        if (Math.abs(dy) > dp(18) && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                            boolean right = startX[0] > root.getWidth() / 2f;
                            if (right && AppPrefs.swipeVolume(prefs)) mode[0] = 2;
                            else if (!right && AppPrefs.swipeBrightness(prefs)) mode[0] = 1;
                            else if (swipes) mode[0] = right ? 2 : 1;
                        } else if (Math.abs(dx) > dp(24) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                            if (AppPrefs.swipeSeek(prefs)) mode[0] = 3;
                        }
                    }
                    // any real movement means the user is swiping, not holding
                    if (Math.abs(dx) > dp(16) || Math.abs(dy) > dp(16)) {
                        main.removeCallbacks(boostStart);
                        boostOff();
                    }
                    if (mode[0] == 1) brightness(dy - dp(40));
                    else if (mode[0] == 2) volume(dy - dp(40));
                    else if (mode[0] == 3 && player != null) {
                        long dur = player.getDuration();
                        long delta = (long) ((dx / Math.max(1f, root.getWidth())) * 120000L);   // full width = 2 min
                        long to = Math.max(0, Math.min(dur > 0 ? dur : Long.MAX_VALUE, startPos[0] + delta));
                        previewTo[0] = to;
                        gesture((delta >= 0 ? "+" : "−") + ReceiverServer.clock(to)
                            + "  (" + (delta >= 0 ? "+" : "") + (delta / 1000) + "s)");
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mode[0] == 3 && previewTo[0] >= 0 && player != null) player.seekTo(previewTo[0]);
                    if (mode[0] != 0) hideGesture();
                    mode[0] = 0;
                    main.removeCallbacks(boostStart);
                    boostOff();                       // v3.4.2 — release → normal speed
                    return true;
                default:
                    return false;
            }
        });
    }

    private void setAspect(int mode) {
        aspectMode = mode;
        prefs.edit().putString(AppPrefs.K_ASPECT_MODE, VideoViewport.modeName(mode)).apply();
        paintAspectDock();
        applyVideoSize();
        flash(VideoViewport.modeLabel(mode));
    }

    /** The centre gesture pill (brightness / volume / seek preview). */
    private void gesture(String text) {
        if (gesturePill == null) return;
        gesturePill.setText(text);
        gesturePill.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideGestureRunnable);
        main.postDelayed(hideGestureRunnable, 900);
    }

    private void hideGesture() {
        if (gesturePill != null) gesturePill.setVisibility(View.GONE);
    }

    private final Runnable hideGestureRunnable = this::hideGesture;

    /** The ⏪/⏩ double-tap ripple (a translucent pill on the tapped half). */
    private void ripple(boolean right, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x59FFFFFF);
        g.setCornerRadius(dp(40));
        t.setBackground(g);
        t.setPadding(dp(18), dp(12), dp(18), dp(12));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(120), dp(72),
            Gravity.CENTER_VERTICAL | (right ? Gravity.END : Gravity.START));
        lp.leftMargin = lp.rightMargin = dp(24);
        // v3.3.1 — THE CRASH #1 SOURCE. v3.3.0 mounted the ripple on
        // hudCenter.getParent() — a LinearLayout — with FrameLayout.LayoutParams,
        // so LinearLayout.measureVertical threw ClassCastException on the very
        // next frame. The ripple is a full-screen overlay: it belongs on the ROOT
        // FrameLayout, which is exactly the parent its LayoutParams describe.
        final FrameLayout parent = rootView;
        if (parent == null) return;
        parent.addView(t, lp);
        main.postDelayed(() -> { try { parent.removeView(t); } catch (Throwable ignored) { } }, 550);
    }

    private void volume(float dy) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int step = Math.round(-dy / dp(140));
            int v = Math.max(0, Math.min(max, cur + step));
            if (v != cur) am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            gesture("Volume " + Math.round(v * 100f / max) + "%");
            if (volCapsule != null) {
                volCapsule.setValue(max <= 0 ? 0 : v / max);
                volCapsule.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) { }
    }

    private void brightness(float dy) {
        try {
            float cur = getWindow().getAttributes().screenBrightness;
            if (cur < 0) cur = 0.5f;
            float v = Math.max(0.02f, Math.min(1f, cur - dy / dp(900)));
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = v;
            getWindow().setAttributes(lp);
            gesture("Brightness " + Math.round(v * 100) + "%");
            if (brightCapsule != null) {
                brightCapsule.setValue(Math.max(0f, Math.min(1f, v / 255f)));
                brightCapsule.setVisibility(View.VISIBLE);
            }
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
        applyImmersive(false);          // v3.4.2 — bars come back with the HUD
        scheduleHudHide();
    }

    void toggleControls() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); return; }
        if (sheet != null && sheet.getVisibility() == View.VISIBLE) { sheet.setVisibility(View.GONE); return; }
        if (hudVisible) {
            hudVisible = false;
            hud.setVisibility(View.GONE);
            applyImmersive(true);       // v3.4.2 — TRUE fullscreen once the HUD is gone
        } else showControls();
    }

    /** v3.4.0 - the Subtitles sheet: the video (and the live subtitle) stay visible above it. */
    private void togglePanel() {
        if (subtitleSheet != null) { subtitleSheet.dismiss(); subtitleSheet = null; return; }
        ThemeEngine.Palette p = ThemeEngine.palette(
            AppPrefs.themeMode(prefs), 0, AppPrefs.accent(prefs), AppPrefs.accentHex(prefs),
            AppPrefs.cardRadius(prefs));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(UiSheet.header(this, p, t2("Subtitles"),
            t2("භාෂාව · විලාසය · සමමුහුර්තකරණය")), new LinearLayout.LayoutParams(-1, -2));

        // 18 language chips
        LinearLayout langRow = UiChip.stripRow(this);
        for (final Langs.Lang l : Langs.chips()) {
            langRow.addView(UiChip.make(this, p, prefs, 0, l.nativeName,
                l.code.equals(activeLang), v -> { switchLang(l.code); }));
        }
        langRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_plus, t2("More"),
            false, v -> showLangSheet()));
        android.widget.HorizontalScrollView hs = UiChip.strip(this);
        hs.addView(langRow, new FrameLayout.LayoutParams(-2, -2));
        body.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        body.addView(UiSegmented.row(this, p, prefs, "වහර", "Register",
            new String[]{t2("Auto"), t2("Spoken"), t2("Formal")}, registerIndex(), i -> {
                prefs.edit().putString(AppPrefs.K_REGISTER,
                    i == 1 ? "spoken" : (i == 2 ? "formal" : null)).apply();
                fetchGen++;
                fetchSinhala();
            }), new LinearLayout.LayoutParams(-1, -2));

        // SL sub sites quick launch
        LinearLayout slRow = UiChip.stripRow(this);
        slRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_srilanka_sub,
            t2("SL Sub Sites"), true, v -> { subtitleSheet.dismiss(); subtitleSheet = null; openLkFinder(); }));
        slRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_folder_zip,
            "Local .srt", false, v -> { subtitleSheet.dismiss(); subtitleSheet = null; pickLocal(); }));
        slRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_search,
            t2("Title search"), false, v -> { subtitleSheet.dismiss(); subtitleSheet = null; openTitleSearch(); }));
        android.widget.HorizontalScrollView hs2 = UiChip.strip(this);
        hs2.addView(slRow, new FrameLayout.LayoutParams(-2, -2));
        body.addView(hs2, new LinearLayout.LayoutParams(-1, -2));

        // Keep the sync strip and live style controls visible in the primary sheet.
        LinearLayout syncRow = UiChip.stripRow(this);
        syncRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_replay_10, "−0.25s", false, v -> nudge(-250)));
        syncRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_forward_10, "+0.25s", false, v -> nudge(250)));
        syncRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_replay_10, "−1s", false, v -> nudge(-1000)));
        syncRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_forward_10, "+1s", false, v -> nudge(1000)));
        body.addView(syncRow, new LinearLayout.LayoutParams(-1, -2));

        // Live style studio — every change repaints the subtitle behind the sheet.
        body.addView(UiSlider.make(this, p, prefs, "අකුරේ ප්‍රමාණය", "Font size",
            12, 36, AppPrefs.fontSp(prefs), "sp", v -> {
                prefs.edit().putInt(AppPrefs.K_FONT_SP, v).apply();
                applyStudioStyle();
            }), new LinearLayout.LayoutParams(-1, -2));
        body.addView(UiSlider.make(this, p, prefs, "පසුබිම් අඳුන", "Mask opacity",
            0, 95, AppPrefs.subBgOpacity(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_SUB_BG_OPACITY, v).apply();
                applyStudioStyle();
            }), new LinearLayout.LayoutParams(-1, -2));
        body.addView(UiSlider.make(this, p, prefs, "පේළි පරතරය", "Line spacing",
            100, 150, AppPrefs.subLineSpacing(prefs), "%", v -> {
                prefs.edit().putInt(AppPrefs.K_SUB_LINE_SPACING, v).apply();
                applyStudioStyle();
            }), new LinearLayout.LayoutParams(-1, -2));

        // Secondary providers, AI helpers and speed choices stay available but are
        // collapsed on first open so the subtitle sheet remains short and focused.
        LinearLayout moreWrap = new LinearLayout(this);
        moreWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout moreHeader = new LinearLayout(this);
        moreHeader.setGravity(Gravity.CENTER_VERTICAL);
        moreHeader.setPadding(dp(4), dp(8), dp(4), dp(4));
        moreHeader.setFocusable(true);
        moreHeader.setClickable(true);
        moreHeader.setContentDescription("More subtitle tools, collapsed");
        TextView moreTitle = new TextView(this);
        moreTitle.setText(t2("තවත් මෙවලම් · More tools"));
        moreTitle.setTextColor(p.title);
        UiTokens.cardTitle(moreTitle);
        moreHeader.addView(moreTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView moreChevron = new ImageView(this);
        moreChevron.setImageDrawable(UiIcons.tinted(this, R.drawable.ic_chevron_down, p.desc, 18));
        moreChevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        moreHeader.addView(moreChevron, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout moreBody = new LinearLayout(this);
        moreBody.setOrientation(LinearLayout.VERTICAL);
        moreBody.setVisibility(View.GONE);

        LinearLayout toolsRow = UiChip.stripRow(this);
        toolsRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_refresh, t2("Retry"),
            false, v -> { subtitleSheet.dismiss(); subtitleSheet = null;
                fetchGen++; fetching = false; fetchSinhala(); }));
        toolsRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_globe, t2("Provider"),
            false, v -> { subtitleSheet.dismiss(); subtitleSheet = null;
                fetchGen++; fetching = false;
                prefs.edit().putBoolean("preferCastSubs", true).apply(); fetchSinhala(); }));
        toolsRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_translate, t2("Spoken/Formal"),
            false, v -> toggleRegister()));
        toolsRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_translate, t2("Free translate"),
            false, v -> { subtitleSheet.dismiss(); subtitleSheet = null; manualFreeTranslate(); }));
        toolsRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_sun, "AI Studio",
            false, v -> { subtitleSheet.dismiss(); subtitleSheet = null; openAiStudioBridge(); }));
        android.widget.HorizontalScrollView toolsScroll = UiChip.strip(this);
        toolsScroll.addView(toolsRow, new FrameLayout.LayoutParams(-2, -2));
        moreBody.addView(toolsScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout aiRow = UiChip.stripRow(this);
        aiRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_globe, t2("NewZone translator"),
            false, v -> { subtitleSheet.dismiss(); subtitleSheet = null; openNewZoneTranslator(); }));
        aiRow.addView(UiChip.make(this, p, prefs, R.drawable.ic_audio_track, t2("Audio → SRT (AI)"),
            false, v -> { subtitleSheet.dismiss(); subtitleSheet = null; showAudioToSrtHelper(); }));
        android.widget.HorizontalScrollView aiScroll = UiChip.strip(this);
        aiScroll.addView(aiRow, new FrameLayout.LayoutParams(-2, -2));
        moreBody.addView(aiScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout speedRow = UiChip.stripRow(this);
        for (final float f : new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f}) {
            float cur = 1f;
            try { cur = player == null ? 1f : player.getPlaybackParameters().speed; }
            catch (Throwable ignored) { }
            speedRow.addView(UiChip.make(this, p, prefs, 0,
                String.format(Locale.US, "%.2g×", f), Math.abs(cur - f) < 0.01f,
                v -> setSpeed(f)));
        }
        android.widget.HorizontalScrollView speedScroll = UiChip.strip(this);
        speedScroll.addView(speedRow, new FrameLayout.LayoutParams(-2, -2));
        moreBody.addView(speedScroll, new LinearLayout.LayoutParams(-1, -2));

        moreHeader.setOnClickListener(v -> {
            boolean open = moreBody.getVisibility() != View.VISIBLE;
            moreBody.setVisibility(open ? View.VISIBLE : View.GONE);
            moreChevron.setImageDrawable(UiIcons.tinted(this,
                open ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down, p.desc, 18));
            moreHeader.setContentDescription(open
                ? "More subtitle tools, expanded" : "More subtitle tools, collapsed");
        });
        moreWrap.addView(moreHeader, new LinearLayout.LayoutParams(-1, -2));
        moreWrap.addView(moreBody, new LinearLayout.LayoutParams(-1, -2));
        body.addView(moreWrap, new LinearLayout.LayoutParams(-1, -2));

        subtitleSheet = UiSheet.show(this, p, prefs, body, 66, () -> subtitleSheet = null);
        showStatus();
    }

    private void scheduleHudHide() {
        main.removeCallbacks(hudHide);
        main.postDelayed(hudHide, AppPrefs.hudHideMs(prefs));   // v3.1.0: 2/4/6/8 s from Playback settings
    }

    private final Runnable hudHide = new Runnable() {
        @Override public void run() {
            if (player != null && player.getPlayWhenReady() && !dragging
                && (panel == null || panel.getVisibility() != View.VISIBLE)
                && (sheet == null || sheet.getVisibility() != View.VISIBLE)) {
                hudVisible = false;
                hud.setVisibility(View.GONE);
                applyImmersive(true);   // v3.4.2 — hide status + navigation bars too
            }
        }
    };

    private void nudge(long d) {
        subDelayMs -= d;   // +1s shows subs a second later, like AnantPlay's Timing
        flash((d > 0 ? "+" : "") + (d / 1000f) + " s · " + (subDelayMs == 0 ? "0" : String.format(Locale.US, "%+.1fs", -subDelayMs / 1000f)));
    }

    private void showError(String msg) {
        // v3.4.1 — every recovery attempt has genuinely finished: the hold is released,
        // so the remote finally sees the honest state instead of TRANSITIONING forever.
        holdUntil = 0;
        lastErrorMsg = msg == null ? "" : msg;
        if (errorBox != null) {
            errorBox.setText(lastErrorMsg);
            errorBox.setVisibility(View.VISIBLE);
        }
        if (errorCard != null) errorCard.setVisibility(View.VISIBLE);
        setState("error", lastErrorMsg);
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
                            + "\n\n" + v.summaryBilingual);
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

    // ═══════════════════════════════════════════════════════════════════════════
    // v3.3.0 — THE CINEMA EXTRAS: error card, track picker, external player, rebuild
    // ═══════════════════════════════════════════════════════════════════════════

    /** The exact HTTP status inside a Media3 error chain (0 when there is none). */
    static int httpStatusOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c).responseCode;
        }
        return 0;
    }

    /** Rebuild the player at {@code pos} with the CURRENT httpHeaders (the retry leg). */
    private void rebuildAt(long pos) {
        hold();   // v3.4.1 — a rebuild is a TRANSITION, never a stop (defect #2)
        // v3.4.3 — BOUNDED recovery: the ladder may never hold the remote on
        // TRANSITIONING forever (Replit QA-0024). statusJson() reports idle once this
        // deadline passes, so a genuinely dead stream ends instead of hanging.
        recoveryDeadline = android.os.SystemClock.uptimeMillis() + RECOVERY_CAP_MS;
        try {
            if (player != null) { player.release(); player = null; }
        } catch (Throwable ignored) { }
        startAt = Math.max(0, pos);
        startPlayback();
    }

    /** The non-intrusive error card: exact error + 4 one-tap fixes. */
    private LinearLayout buildErrorCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF20E1424);
        bg.setCornerRadius(radiusCard());
        bg.setStroke(dp(1), 0x44FFFFFF);
        card.setBackground(bg);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));

        TextView head = new TextView(this);
        head.setText(t2("Playback error"));
        UiIcons.setCompound(head, R.drawable.ic_alert, 0xFFFF6B6B, 15, Gravity.START);
        head.setTextColor(0xFFE2E8F0);
        head.setTextSize(14);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        errorBox = new TextView(this);
        errorBox.setTextColor(0xFFFFB3B3);
        errorBox.setTextSize(12.5f);
        errorBox.setPadding(0, dp(6), 0, dp(8));
        card.addView(errorBox, new LinearLayout.LayoutParams(-1, -2));

        Button retry = btn(R.drawable.ic_refresh, t2("Retry"));
        retry.setOnClickListener(v -> {
            card.setVisibility(View.GONE);
            triedHeaderRecovery = false;
            relayStage = 0;            // v3.4.1 — a manual retry restarts the ladder
            httpHeaders = null;
            rebuildAt(retryAtMs);
        });
        card.addView(retry);

        Button alt = btn(R.drawable.ic_settings, t2("Retry with alternate headers"));
        alt.setOnClickListener(v -> {
            card.setVisibility(View.GONE);
            httpHeaders = StreamHttpConfig.recovery(url, httpHeaders);
            triedHeaderRecovery = true;
            rebuildAt(retryAtMs);
        });
        card.addView(alt);

        Button vlc = btn(R.drawable.ic_vlc_external, t2("Open in VLC"));
        vlc.setOnClickListener(v -> openExternalPlayer());
        card.addView(vlc);

        Button copy = btn(R.drawable.ic_copy, t2("Copy error"));
        copy.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("error",
                    "v" + AppInfo.VERSION_NAME + " · " + title + "\n" + lastErrorMsg));
                flash("copied");
            } catch (Throwable ignored) { }
        });
        card.addView(copy);
        return card;
    }

    /** 🔊 Audio / Quality — the REAL Media3 track picker (audio languages + video qualities). */
    private void showTrackSheet() {
        if (player == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(UiSheet.header(this, pal(), t2("Audio / Quality"),
            t2("ධාවන පථ · විභේදනය · භාෂාව")), new LinearLayout.LayoutParams(-1, -2));
        boolean any = false;
        try {
            Tracks tracks = player.getCurrentTracks();
            for (final Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_AUDIO && g.getType() != C.TRACK_TYPE_VIDEO) continue;
                for (int i = 0; i < g.length; i++) {
                    final TrackGroup tg = g.getMediaTrackGroup();
                    final int idx = i;
                    String label = trackLabel(g.getType(), g.getTrackFormat(i));
                    Button b = btn(label);
                    if (g.isTrackSelected(i)) UiIcons.setCompound(b, R.drawable.ic_check_circle, 0xFF86EFAC, 15, Gravity.START);
                    b.setOnClickListener(v -> {
                        try {
                            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                .setOverrideForType(new TrackSelectionOverride(tg, idx)).build());
                            flash(label);
                        } catch (Throwable t) { Diag.w("tracks", "select: " + t); }
                    });
                    box.addView(b, new LinearLayout.LayoutParams(-1, -2));
                    any = true;
                }
            }
        } catch (Throwable t) {
            Diag.w("tracks", "read: " + t);
        }
        if (!any) {
            box.addView(UiGroup.empty(this, pal(),
                R.drawable.ic_audio_track, t2("One track only"),
                t2("This stream carries no alternate audio or quality tracks.")),
                new LinearLayout.LayoutParams(-1, -2));
        }
        trackSheet = UiSheet.show(this, pal(), prefs, box, 60, () -> trackSheet = null);
    }

    /** The palette for a sheet (the player is always dark, so this is cheap and safe). */
    private ThemeEngine.Palette pal() {
        return ThemeEngine.palette(AppPrefs.themeMode(prefs), 0, AppPrefs.accent(prefs),
            AppPrefs.accentHex(prefs), AppPrefs.cardRadius(prefs));
    }

    /** A human label for one Media3 track (language + quality + bitrate). */
    static String trackLabel(int type, Format f) {
        StringBuilder sb = new StringBuilder();
        if (type == C.TRACK_TYPE_AUDIO) {
            String lang = f.language == null || f.language.isEmpty() ? "" : languageName(f.language);
            if (!lang.isEmpty()) sb.append(lang);
            if (f.label != null && !f.label.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(f.label);
            if (sb.length() == 0) sb.append("Audio");
            if (f.bitrate > 0) sb.append(" · ").append(f.bitrate / 1000).append(" kbps");
        } else {
            if (f.height > 0) sb.append(f.height).append("p");
            if (f.width > 0) sb.append(sb.length() > 0 ? " · " : "").append(f.width).append("×").append(f.height);
            if (sb.length() == 0) sb.append(f.label == null || f.label.isEmpty() ? "Video" : f.label);
            if (f.bitrate > 0) sb.append(" · ").append(f.bitrate / 1000).append(" kbps");
        }
        return sb.toString();
    }

    /** "ta" → "Tamil" for the four languages this player meets most. */
    static String languageName(String code) {
        String c = code == null ? "" : code.toLowerCase(Locale.US);
        if (c.startsWith("si")) return "සිංහල";
        if (c.startsWith("ta")) return "Tamil";
        if (c.startsWith("hi")) return "Hindi";
        if (c.startsWith("en")) return "English";
        if (c.startsWith("te")) return "Telugu";
        if (c.startsWith("ml")) return "Malayalam";
        return code;
    }

    /**
     * 📺 THE ULTIMATE ESCAPE HATCH: hand the stream (and the Sinhala .srt we already
     * hold) to VLC / MX / mpv at the exact current position. This is what makes the
     * app useful even on a codec Media3 cannot decode — and it is why the app never
     * needs libVLC's 50 MB of .so files to be "VLC-grade".
     */
    private void openExternalPlayer() {
        // v3.3.0 AUDIT — this whole hand-off is external-process code: a missing player,
        // a vendor ROM that refuses the Intent, a SecurityException from a work profile or
        // a malformed extra must NEVER crash the player. Everything is inside ONE
        // try/catch (Throwable) with a clean toast fallback.
        String subUrl = null;
        Map<String, String> extras = new LinkedHashMap<>();
        try {
            subUrl = publishSubsForExternal();
            extras = LkSubFinder.externalExtras(url, subUrl, title,
                player == null ? 0 : player.getCurrentPosition());
            Intent base = new Intent(Intent.ACTION_VIEW);
            base.setDataAndType(Uri.parse(url), "video/*");
            // harmless for an http(s) data URI, and required the day this becomes a
            // content:// FileProvider — never a file:// Uri (FileUriExposedException).
            base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            for (Map.Entry<String, String> e : extras.entrySet()) base.putExtra(e.getKey(), e.getValue());
            // 1. the preferred player, if installed; 2. any of the others; 3. the chooser.
            java.util.List<String> order = new java.util.ArrayList<>();
            String pref = AppPrefs.externalPlayer(prefs);
            String want = AppPrefs.EXT_VLC.equals(pref) ? "org.videolan.vlc"
                : (AppPrefs.EXT_MX.equals(pref) ? "com.mxtech.videoplayer.ad"
                : (AppPrefs.EXT_MPV.equals(pref) ? "is.air.tv.mpv" : null));
            if (want != null) order.add(want);
            for (String pkg : LkSubFinder.EXTERNAL_PLAYERS) if (!order.contains(pkg)) order.add(pkg);
            for (String pkg : order) {
                try {
                    Intent i = new Intent(base);
                    i.setPackage(pkg);
                    if (getPackageManager().resolveActivity(i, 0) != null) {
                        startActivity(i);
                        flash(subUrl == null
                            ? pkg + " · subs off (receiver not running)"
                            : pkg + " · subs attached");
                        return;
                    }
                } catch (Throwable t) {
                    Diag.w("vlc", pkg + ": " + t);          // try the next player
                }
            }
            try {
                startActivity(Intent.createChooser(base, "Play with…"));
                if (subUrl == null) flash("no receiver — subtitles not attached");
            } catch (Throwable t) {
                Diag.w("vlc", "chooser: " + t);
                flash(Chrome.pick("No external player installed · බාහිර player එකක් නැත"));
            }
        } catch (Throwable t) {
            // the ONLY thing the user sees: an honest message, never a crash dialog
            Diag.e("vlc", "external launch failed: " + t);
            flash("Could not open an external player · " + t.getClass().getSimpleName());
        }
    }

    /**
     * v3.3.0 AUDIT FIX — the Sinhala .srt an EXTERNAL player can actually read.
     *
     * <p>NEVER a private file path and NEVER a {@code file:} Uri built by
     * {@code Uri.fromFile}:
     * <ul>
     *   <li>{@code file://} in an outgoing Intent = {@code FileUriExposedException} on
     *       Android 7–16 (a hard crash on the tap of {@code 📺 VLC});</li>
     *   <li>a raw {@code /data/user/0/…/cache/<name>.srt} path does not crash us
     *       but VLC gets {@code EACCES} — the user silently gets no subtitles.</li>
     * </ul>
     * Instead the cues are published to our already-running loopback receiver
     * ({@code GET http://127.0.0.1:<port>/subs/current.srt}, UTF-8 text/plain, gated by
     * LocalNet to this device) and THAT URL is handed over. When the receiver is not
     * running we return null and omit the extra entirely — a missing subtitle beats a
     * crash or a dead link.
     */
    private String publishSubsForExternal() {
        try {
            List<Cue> cur = cues;
            if (cur == null || cur.isEmpty()) { ReceiverService.setSubsSrt(null); return null; }
            String body = CueText.of(cur);
            if (body == null || body.trim().isEmpty()) { ReceiverService.setSubsSrt(null); return null; }
            ReceiverService.setSubsSrt(body);
            String u = ReceiverService.subsUrl();
            if (u == null) Diag.w("vlc", "receiver not running — external player gets no subtitle extra");
            else Diag.i("vlc", "subtitle route armed for the external player: " + u);
            return u;
        } catch (Throwable t) {
            Diag.w("vlc", "subs publish: " + t);
            return null;
        }
    }

    /** v3.3.0 — the 🇱🇰 mini web-picker (Baiscope / Zoom.lk / PirateLK / SubDL / OS). */
    private void openLkFinder() {
        final String q = LkSubFinder.query(title);
        final List<LkSubFinder.Site> sites = LkSubFinder.sites();
        final String[] names = new String[sites.size() + 1];
        for (int i = 0; i < sites.size(); i++)
            names[i] = sites.get(i).name + (sites.get(i).sriLankan ? "  (Sri Lanka)" : "");
        names[sites.size()] = "DuckDuckGo — \"" + q + "\" sinhala subtitles";
        new android.app.AlertDialog.Builder(this)
            .setTitle(t2("Find Sinhala subtitles") + " — " + q)
            .setItems(names, (d, which) -> {
                String target = which < sites.size()
                    ? sites.get(which).urlFor(q)
                    : "https://duckduckgo.com/?q=" + LkSubFinder.enc(q + " sinhala subtitles srt");
                openInWebTab(target);
            })
            .setNegativeButton("Close", null)
            .show();
    }

    /** Open the app's Web tab on {@code target}; any .zip/.srt it downloads is captured. */
    private void openInWebTab(String target) {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("webUrl", target);
            i.putExtra("openWeb", true);
            startActivity(i);
            flash("opening " + StreamSniffer.hostOf(target));
        } catch (Throwable t) {
            Diag.w("web", "open tab: " + t);
        }
    }

    /** Called by WebBrowserView when a captured .srt/.zip arrived while we are playing. */
    void attachCapturedSubs(byte[] bytes, String name) {
        try {
            List<Cue> loaded = SrtLoader.load(bytes, name);
            if (loaded == null || loaded.isEmpty()) { flash("no cues in " + name); return; }
            onLocalCues(loaded, name);
            flash(loaded.size() + " cues attached");
        } catch (Throwable t) {
            flash("subtitle error: " + t.getMessage());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyStudioStyle();     // 18 sp portrait → 20 sp landscape
        main.postDelayed(this::applyVideoSize, 60);
    }

    /** The English text of a cue list, as one .srt-shaped body (the subs-direct input). */
    static final class CueText {
        static String of(List<Cue> cues) {
            StringBuilder sb = new StringBuilder(cues.size() * 48);
            for (Cue c : cues) {
                sb.append(c.i + 1).append('\n')
                  .append(FreeSubTranslator.srtTs(c.t0)).append(" --> ").append(FreeSubTranslator.srtTs(c.t1)).append('\n')
                  .append(c.en == null || c.en.isEmpty() ? (c.si == null ? "" : c.si) : c.en).append('\n').append('\n');
            }
            return sb.toString();
        }
    }
}
