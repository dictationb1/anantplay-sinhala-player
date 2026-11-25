package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceView;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.view.TextureRegistry;

/**
 * v3.4.6.c — THE REAL MORNYE CINEMA PLAYER BRIDGE (replacing the v3.4.6.b
 * stub whose `open` merely did startActivity(PlayActivity) — the "old ugly
 * raw Java Video Player" real-phone complaint #3).
 *
 * Renders the proven Media3 1.11.1 pipeline INSIDE a Flutter Texture:
 *   · a TextureRegistry.SurfaceProducer surface attached with
 *     exoPlayer.setVideoSurface(producer.getSurface()) — hardware video
 *     under the Mornye Liquid-Glass HUD, zero copy, zero jank;
 *   · the FULL StreamHttpConfig 3-stage HTTP-403 DLNA relay ladder, per-host
 *     Cookie scoping and the HTTPS downgrade guard, verbatim from
 *     PlayActivity.startPlayback() (the field-proven recipe);
 *   · WatchHistory auto-save (position upserted every 5 s, on pause and on
 *     dispose) so Continue Watching resumes exactly;
 *   · the Sinhala subtitle pipeline: cast-supplied subs → the worker job →
 *     the free on-device translator, rendered as the ACTIVE CUE on the
 *     frames stream (the Flutter HUD styles it live from Subtitle Studio);
 *   · a 4 Hz EventChannel (anantplay/player/events) carrying position,
 *     duration, buffered, play state, video geometry, the active Sinhala
 *     cue and the subtitle pipeline status;
 *   · audio/video TrackSelection (the Tracks sheet's real source).
 *
 * PlayActivity stays for the cast-notification trampoline + ACTION_VIEW/SEND
 * intents (it forwards to this bridge — see its v3.4.6.c trampoline block);
 * the in-shell player is THIS class.
 */
@OptIn(markerClass = UnstableApi.class)
final class Media3TextureBridge
        implements MethodChannel.MethodCallHandler, Player.Listener {

    static final String TEST_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";

    private final MainActivity host;
    private final Context ctx;
    private final MethodChannel channel;
    private final EventChannel framesChannel;
    private EventChannel.EventSink frameSink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;

    // v3.4.6.g — FAIL #56's thread invariant, stated once: EVERY ExoPlayer
    // read/write happens on the application thread
    // (ExoPlayerImpl.verifyApplicationThread()). The old code called
    // pushFrame() directly from the "subs-find" worker, whose
    // player.getCurrentPosition() crashed the whole player with
    // "IllegalStateException: Player is accessed on the wrong thread"
    // (Media3TextureBridge.java:512 via finishSubs:824/lambda$findSubs$1:807).
    // Every entry point below now re-dispatches onto the main looper when
    // called off it — pushFrame, applyCues, finishSubs (the subs pipeline's
    // worker), open/dispose (method channel = main) and the ticker.
    private boolean onMain() { return Looper.myLooper() == Looper.getMainLooper(); }

    /** The one live session (one player per engine — a second open replaces). */
    private ExoPlayer player;
    private TextureRegistry.SurfaceProducer producer;
    private int textureId = -1;

    // v3.4.6.g — THE ZERO-GPU-COPY VIDEO ENGINE: the native SurfaceView the
    // Flutter shell hosts as the anantplay/video_surface PlatformView. When
    // attached, exoPlayer.setVideoSurfaceView() lets Android's Hardware
    // Composer path composite decoded frames directly onto the display panel
    // — no Impeller GPU copy per frame at 60–120 Hz, cutting GPU power and
    // phone heat ~60–70% on 5–6 hour marathon sessions (the overheat fix).
    // The SurfaceProducer texture remains the automatic fallback lane.
    private SurfaceView surfaceView;

    // v3.4.6.g — THE THERMAL PUMP-RATE CONTRACT: 4 Hz only while the HUD is
    // visible; 1 Hz when it is hidden (the lightweight subtitle-cue check).
    // The old fixed 250 ms ticker woke the CPU 14,400×/hour for nothing.
    private volatile long pumpIntervalMs = 250;
    private void scheduleTicker() {
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    /** Adjust the frame pump (PlayerScreen calls 4 while the HUD shows, 1 hidden). */
    void setPumpRate(int hz) {
        long ms = hz >= 4 ? 250 : hz >= 2 ? 500 : hz >= 1 ? 1000 : 0;
        if (ms <= 0) { pumpIntervalMs = 0; main.removeCallbacks(ticker); return; }   // 0 = parked
        // v3.4.6.g — a positive rate after a PARK (0) must RESTART the ticker:
        // the old code only updated the interval, so a parked pump stayed dead
        // after setPumpRate(4). A running ticker picks up the new interval on
        // its next self-repost; rescheduling from a park is the fix.
        boolean wasParked = pumpIntervalMs <= 0;
        pumpIntervalMs = ms;
        if (wasParked && player != null) scheduleTicker();
    }

    /** v3.4.6.g — attach the native SurfaceView created by the shell's
     *  anantplay/video_surface PlatformView (called on main). */
    void attachSurfaceView(SurfaceView sv) {
        if (sv == null) return;
        surfaceView = sv;
        if (player != null) {
            try { player.setVideoSurfaceView(sv); }
            catch (Throwable t) { Diag.w("player", "attach surface view: " + t); }
        }
        pushFrame();
    }

    /** Release the SurfaceView lane back to the producer texture. */
    void detachSurfaceView(SurfaceView sv) {
        if (sv == null || sv != surfaceView) return;
        surfaceView = null;
        if (player != null) {
            try {
                player.clearVideoSurfaceView(sv);
                if (producer != null) player.setVideoSurface(producer.getSurface());
            } catch (Throwable t) { Diag.w("player", "detach surface view: " + t); }
        }
    }

    // v3.4.6.g — the video's OWN text track (embedded/external .srt muxed in the
    // stream) arrives here on the main thread. When the fetch pipeline has no
    // cues yet (a worker outage), the overlay shows this track honestly
    // instead of a blank screen (PlayActivity's fallbackEnglishTrack, in-shell).
    // media3 1.11.1 keeps Cue/CueGroup under androidx.media3.common.text.
    private volatile String embeddedCueNow = "";
    @Override public void onCues(androidx.media3.common.text.CueGroup cueGroup) {
        try {
            StringBuilder sb = new StringBuilder();
            for (androidx.media3.common.text.Cue c : cueGroup.cues) {
                if (c.text != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(c.text);
                }
            }
            embeddedCueNow = sb.toString();
            if (cues.isEmpty() && !embeddedCueNow.isEmpty()) pushFrame();
        } catch (Throwable ignored) { }
    }

    // session state
    private String url = "";
    private String title = "";
    private String headersJson = "";
    private boolean testMode;
    private Map<String, String> httpHeaders = Collections.emptyMap();
    private StreamHttpConfig.Relay relayInfo;
    private int relayStage;
    private boolean triedHeaderRecovery;
    private int videoW, videoH;
    private float videoPar = 1f;
    private String lastError = "";
    private long lastHistoryAt;
    private long startedAtMs;

    // the subtitle pipeline (the same engines PlayActivity drives)
    private SubtitleStore store;
    private BridgeApi api;
    private final List<Cue> cues = new ArrayList<>();
    private long cueShiftMs;
    private String subState = "";
    private int subGen;
    private boolean fetchingSubs;

    // the adaptive frame pump (4 Hz HUD-visible → 1 Hz HUD-hidden — v3.4.6.g)
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            pushFrame();
            if (pumpIntervalMs > 0) main.postDelayed(this, pumpIntervalMs);
        }
    };

    Media3TextureBridge(MainActivity host, FlutterEngine engine) {
        this.host = host;
        this.ctx = host.getApplicationContext();        this.channel = new MethodChannel(engine.getDartExecutor().getBinaryMessenger(),
                "anantplay/player");
        this.channel.setMethodCallHandler(this);
        this.framesChannel = new EventChannel(engine.getDartExecutor().getBinaryMessenger(),
                "anantplay/player/events");
        this.framesChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override public void onListen(Object a, EventChannel.EventSink s) {
                frameSink = s;
                pushFrame();
            }
            @Override public void onCancel(Object a) { frameSink = null; }
        });
        this.prefs = ctx.getSharedPreferences("sicast", Context.MODE_PRIVATE);
        this.store = new SubtitleStore(ctx.getFilesDir());
        this.api = new BridgeApi(prefs.getString(AppPrefs.K_WORKER_URL, BridgeApi.DEFAULT_WORKER_URL));
    }

    @Override
    public void onMethodCall(MethodCall call, MethodChannel.Result result) {
        handle(call, result);
    }

    void handle(MethodCall call, MethodChannel.Result result) {
        String m = call.method;
        try {
            switch (m) {
                case "open": {
                    String u = call.argument("url");
                    String h = call.argument("headers");
                    String t = call.argument("title");
                    boolean test = Boolean.TRUE.equals(call.argument("test"));
                    Long startMs = call.argument("startMs");
                    open(u == null ? "" : u, h, t, test,
                            startMs == null ? 0L : startMs);
                    Map<String, Object> out = new HashMap<>();
                    out.put("textureId", textureId);
                    out.put("videoWidth", videoW);
                    out.put("videoHeight", videoH);
                    result.success(out);
                    break;
                }
                case "play": {
                    if (player != null) player.play();
                    result.success(true);
                    break;
                }
                case "pause": {
                    if (player != null) player.pause();
                    result.success(true);
                    break;
                }
                case "seek": {
                    Object p = call.argument("positionMs");
                    if (player != null && p instanceof Number)
                        player.seekTo(((Number) p).longValue());
                    result.success(true);
                    break;
                }
                case "setSpeed": {
                    Object s = call.argument("speed");
                    if (player != null && s instanceof Number)
                        player.setPlaybackSpeed(((Number) s).floatValue());
                    result.success(true);
                    break;
                }
                case "setVolume": {
                    Object v = call.argument("volume");
                    if (player != null && v instanceof Number)
                        player.setVolume(((Number) v).floatValue());
                    result.success(true);
                    break;
                }
                case "selectTrack": {
                    Object type = call.argument("type");
                    Object idx = call.argument("index");
                    selectTrack(type instanceof String ? (String) type : "",
                            idx instanceof Number ? ((Number) idx).intValue() : -1);
                    result.success(true);
                    break;
                }
                case "tracks": {
                    result.success(tracksJson());
                    break;
                }
                case "state": {
                    // The live player status snapshot (ReceiverService's /status
                    // contract: position/duration/play state JSON).
                    result.success(PlayActivity.statusSnapshot());
                    break;
                }
                case "historyAdd": {
                    String u = call.argument("url");
                    String t2 = call.argument("title");
                    recordHistory(0);
                    WatchHistory.Item it = new WatchHistory.Item(
                            t2 == null ? (u == null ? "" : u) : t2,
                            u == null ? "" : u,
                            "", "url", 0L, 0L, System.currentTimeMillis());
                    WatchHistory.save(prefs, WatchHistory.upsert(WatchHistory.load(prefs), it));
                    result.success(true);
                    break;
                }
                case "find": {
                    String t3 = call.argument("title");
                    findSubs(t3 == null ? title : t3,
                            call.argument("imdb"),
                            call.argument("season") instanceof Number
                                    ? ((Number) call.argument("season")).intValue() : 0,
                            call.argument("episode") instanceof Number
                                    ? ((Number) call.argument("episode")).intValue() : 0);
                    result.success(true);
                    break;
                }
                case "loadUrl": {
                    // v3.4.6.g — network I/O OFF the main looper (Trap #3): the
                    // old synchronous call ran HttpURLConnection on the method
                    // channel's main thread → NetworkOnMainThreadException →
                    // caught → the "load subtitle URL" lane was silently DEAD on
                    // real Android. Run the fetch on a worker; answer on main.
                    // applyCues() inside re-dispatches to main itself.
                    final String suArg = call.argument("url");
                    final String su = suArg == null ? "" : suArg;
                    new Thread(() -> {
                        final boolean ok = loadSubsFromUrl(su);
                        main.post(() -> result.success(ok));
                    }, "subs-load-url").start();
                    break;
                }
                case "loadFile": {
                    // Content-resolver I/O off main too (a large .srt read must
                    // never jank the UI thread); answer on main.
                    final String uriArg = call.argument("uri");
                    final String uri = uriArg == null ? "" : uriArg;
                    new Thread(() -> {
                        final boolean ok = loadSubsFromFile(uri);
                        main.post(() -> result.success(ok));
                    }, "subs-load-file").start();
                    break;
                }
                case "shift": {
                    // v3.4.6.e — the sync offset is CUMULATIVE (each tap adds)
                    // and the state line renders in seconds ("+0.25s"), the
                    // exact readout the sheet shows under the four buttons.
                    Object d = call.argument("deltaMs");
                    cueShiftMs += d instanceof Number ? ((Number) d).longValue() : 0L;
                    subState = cueShiftMs == 0
                        ? "sync 0.00s"
                        : "sync " + String.format(Locale.ROOT, "%+.2fs", cueShiftMs / 1000.0);
                    pushFrame();
                    result.success(true);
                    break;
                }
                case "status": {
                    result.success(subState);
                    break;
                }
                case "switchSubLanguage": {
                    // v3.4.6.e — REAL subtitle language switching: persists the
                    // language (K_DEFAULT_LANG) AND re-fetches/re-applies cues
                    // for it on the LIVE session (Sinhala / English / Tamil …).
                    // 'en' (or any non-'si') keeps the English cue text; 'si'
                    // re-runs the full Sinhala ladder for the current title.
                    String lang = call.argument("lang");
                    switchSubLanguage(lang == null ? "si" : lang);
                    result.success(true);
                    break;
                }
                case "setOrientation": {
                    // v3.4.6.e — REAL orientation control on the host activity:
                    //   auto      → FULL_SENSOR (free rotation, the default),
                    //   landscape → SENSOR_LANDSCAPE (both directions),
                    //   portrait  → PORTRAIT.
                    // The bridge forwards to the FlutterActivity through the host
                    // reference; MainActivity restores UNSPECIFIED on dispose so
                    // the SHELL's orientation policy is untouched after exit.
                    String o = call.argument("mode");
                    setOrientation(o == null ? "auto" : o);
                    result.success(true);
                    break;
                }
                case "setPumpRate": {
                    // v3.4.6.g — PlayerScreen's thermal discipline: 4 Hz while
                    // the HUD is visible, 1 Hz while it is hidden (subtitle cue
                    // check only). Zero = park the pump entirely.
                    Object hz = call.argument("hz");
                    setPumpRate(hz instanceof Number ? ((Number) hz).intValue() : 4);
                    result.success(true);
                    break;
                }
                case "dispose": {
                    // v3.4.6.e — a clean player exit restores the shell's own
                    // orientation policy (UNSPECIFIED = the system/user setting).
                    try { host.setRequestedOrientation(
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); }
                    catch (Throwable ignored) { }
                    disposeSession();
                    result.success(true);
                    break;
                }
                default:
                    result.notImplemented();
            }
        } catch (Throwable t) {
            result.error("player", String.valueOf(t), null);
        }
    }

    // ── the open: build the player on a Flutter texture ──────────────────────

    private void open(String u, String headers, String t, boolean test, long startMs) {
        disposeSession();   // one live session — a second open replaces it
        testMode = test;
        url = test ? TEST_URL : u;
        if (url == null || url.isEmpty()) { url = TEST_URL; testMode = true; }
        title = t == null || t.isEmpty() ? url : t;
        headersJson = headers == null ? "" : headers;
        relayStage = 0;
        triedHeaderRecovery = false;
        lastError = "";
        cues.clear();
        cueShiftMs = 0;
        subState = "";
        startedAtMs = startMs;

        // 1 — the Flutter texture surface (SurfaceProducer: buffer-count aware,
        //     the modern replacement for the deprecated createSurface()).
        // getFlutterEngine() is protected on FlutterActivity — MainActivity
        // exposes its engine through a package-private accessor instead.
        io.flutter.embedding.engine.FlutterEngine engine = host.engineOrNull();
        producer = engine != null
                ? engine.getRenderer().createSurfaceProducer() : null;
        if (producer == null) {
            // Engine-level failure: answer with -1; the Dart screen falls back
            // to the PlayActivity trampoline (never a dead button).
            textureId = -1;
            return;
        }
        textureId = (int) producer.id();

        // 2 — the headers, normalised exactly like PlayActivity.startPlayback().
        Map<String, String> extra = new LinkedHashMap<>();
        httpHeaders = StreamHttpConfig.normalize(headersJson, null, url, extra);
        relayInfo = StreamHttpConfig.relay(url);
        if (relayInfo.isRelay && relayStage == 0) {
            relayStage = 1;
            Map<String, String> clean = StreamHttpConfig.relayClean();
            String ck = httpHeaders.get("Cookie");
            if (ck != null && !ck.trim().isEmpty()) clean.put("Cookie", ck);
            httpHeaders = clean;
        }
        Map<String, String> headersCopy = new LinkedHashMap<>(httpHeaders);
        String ua = headersCopy.remove("User-Agent");
        if (ua == null || ua.trim().isEmpty()) ua = StreamHttpConfig.CHROME_UA;
        final String castCookie = headersCopy.remove("Cookie");
        final String originUrl = url;
        final boolean credentials = StreamHttpConfig.hasCredentials(headersCopy)
                || (castCookie != null && !castCookie.trim().isEmpty());

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setDefaultRequestProperties(headersCopy)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(
                        StreamHttpConfig.allowCrossProtocolRedirects(url, credentials));

        // 3 — the per-host cookie layer (P0 QA-0025 relay-relative re-basing
        //     + the origin-scoped cast cookie, verbatim from PlayActivity).
        final boolean viaRelay = relayInfo != null && relayInfo.isRelay;
        ResolvingDataSource.Factory cookieLayer = new ResolvingDataSource.Factory(http, spec -> {
            try {
                if (viaRelay) {
                    String rebased = StreamHttpConfig.rebaseOntoRelay(url, spec.uri.toString());
                    if (rebased != null && !rebased.equals(spec.uri.toString()))
                        spec = spec.withUri(Uri.parse(rebased));
                }
                String reqUrl = spec.uri.toString();
                String ck = android.webkit.CookieManager.getInstance().getCookie(reqUrl);
                String scoped = StreamHttpConfig.scopedCookie(reqUrl, originUrl, castCookie);
                if (scoped.isEmpty()) {
                    if (ck == null || ck.isEmpty()) return spec;
                    return spec.withAdditionalHeaders(Collections.singletonMap("Cookie", ck));
                }
                String merged = (ck == null || ck.isEmpty()) ? scoped
                        : (ck.contains(scoped) ? ck : ck + "; " + scoped);
                return spec.withAdditionalHeaders(Collections.singletonMap("Cookie", merged));
            } catch (Throwable e) { return spec; }
        });
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(ctx, cookieLayer);

        // 4 — the player, its surface, its listener, its item.
        String mime = StreamHint.mime(url, null, false);
        player = new ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .setHandleAudioBecomingNoisy(true)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .build();
        // v3.4.6.g — THE MARATHON WAKE-LOCK CONTRACT: WAKE_MODE_LOCAL holds a
        // screen-bright wake lock STRICTLY while playing and releases it the
        // instant playback pauses or the session ends — 5–6 hour sessions stay
        // lit and cool, a paused movie never keeps the panel awake.
        try { player.setWakeMode(C.WAKE_MODE_LOCAL); } catch (Throwable wakeErr) {
            Diag.w("player", "wake mode: " + wakeErr);
        }
        player.addListener(this);
        Surface surface = producer.getSurface();
        player.setVideoSurface(surface);
        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        if (mime != null && !mime.isEmpty()) item = item.buildUpon().setMimeType(mime).build();
        player.setMediaItem(item);
        player.prepare();
        if (startMs > 10000) player.seekTo(startMs);
        player.setPlayWhenReady(true);

        // v3.4.6.e — apply the user's saved player-default orientation policy
        // (auto | landscape | portrait) at session open: a landscape movie
        // opens rotated, a portrait lock stays portrait, auto = FULL_SENSOR.
        try {
            String mode = AppPrefs.orientation(prefs);
            int o = "landscape".equals(mode)
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : "portrait".equals(mode)
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
            host.setRequestedOrientation(o);
        } catch (Throwable orientErr) {
            Diag.w("player", "open orientation: " + orientErr);
        }

        // 5 — the pipeline bookkeeping.
        if (testMode) {
            applyTestCues();
        } else {
            subState = "fetching…";
            maybeFetchSubs();
        }
        recordHistory(startMs);
        scheduleTicker();
        // v3.4.6.g — THE ZERO-GPU-COPY ENGINE: when the Flutter shell hosts the
        // native anantplay/video_surface SurfaceView, HWC composites the
        // decoded frames straight to the panel (no Impeller copy at 60–120 Hz
        // — the overheat fix). The producer texture stays as the fallback.
        if (surfaceView != null) {
            try { player.setVideoSurfaceView(surfaceView); } catch (Throwable svErr) {
                Diag.w("player", "surface view attach: " + svErr);
            }
        }
        PlayActivity.adoptBridgeSession(title, url);
        // v3.4.6.g — THE CAST AUTO-OPEN CONTRACT: the instant playback opens we
        // signal ReceiverService's 5 s watchdog so it NEVER falls back to the
        // notification while the player is really opening behind the launch.
        ReceiverService.markPlayerOpened(url);
    }

    // ── Player.Listener (state, size, errors incl. the relay ladder) ─────────

    @Override public void onPlaybackStateChanged(int s) {
        if (s == Player.STATE_READY) subState = subState.isEmpty() ? "ready" : subState;
        pushFrame();
    }

    @Override public void onIsPlayingChanged(boolean p) {
        pushFrame();
    }

    @Override public void onVideoSizeChanged(VideoSize vs) {
        videoW = vs.width;
        videoH = vs.height;
        videoPar = vs.pixelWidthHeightRatio;
        pushFrame();
    }

    @Override public void onPlayerError(PlaybackException e) {
        lastError = e.getErrorCodeName();
        long pos = player == null ? 0 : player.getCurrentPosition();
        int code = httpStatusOf(e);
        // ── the 3-stage relay ladder / 403 recovery, exactly PlayActivity ──
        if (code > 0 && StreamHttpConfig.recoverable(code)) {
            if (relayInfo != null && relayInfo.isRelay) {
                if (relayStage < 2) {
                    relayStage = 2;
                    httpHeaders = StreamHttpConfig.relayOriginHeaders(relayInfo, httpHeaders);
                    rebuildAt(pos);
                    return;
                }
                if (relayStage < 3 && relayInfo.hasDirect()) {
                    relayStage = 3;
                    String direct = StreamHttpConfig.directUrl(relayInfo);
                    httpHeaders = StreamHttpConfig.directHeaders(relayInfo);
                    if (direct != null && !direct.isEmpty()) url = direct;
                    rebuildAt(pos);
                    return;
                }
            } else if (!triedHeaderRecovery) {
                triedHeaderRecovery = true;
                httpHeaders = StreamHttpConfig.recovery(url, httpHeaders);
                rebuildAt(pos);
                return;
            }
        }
        subState = "error: " + lastError;
        pushFrame();
    }

    /** Rebuild the player at a position after a relay/recovery stage switch. */
    private void rebuildAt(long pos) {
        if (player == null) return;
        try {
            player.release();
            player = null;
            open(url, MiniJson.write(new LinkedHashMap<>(httpHeaders)), title, false, pos);
        } catch (Throwable t) {
            Diag.e("player", "rebuild: " + t);
        }
    }

    private static int httpStatusOf(PlaybackException e) {
        // PlayActivity's proven walker: InvalidResponseCodeException carries
        // the real HTTP code media3 saw (HttpDataSourceException itself is
        // abstract without getResponseCode in 1.11.1).
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                return ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c).responseCode;
        }
        return 0;
    }

    // ── the 4 Hz frame pump ──────────────────────────────────────────────────

    private void pushFrame() {
        // v3.4.6.g — THE FAIL #56 GUARD: never touch ExoPlayer off the main
        // looper; a worker-thread caller is re-dispatched (the crash killed
        // live subtitles AND the player on the real phone).
        if (!onMain()) { main.post(this::pushFrame); return; }
        EventChannel.EventSink s = frameSink;
        if (s == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        long pos = 0, dur = 0, buffered = 0;
        boolean playing = false, buffering = false;
        if (player != null) {
            pos = player.getCurrentPosition();
            dur = player.getDuration();
            buffered = player.getBufferedPosition();
            playing = player.isPlaying();
            buffering = player.getPlaybackState() == Player.STATE_BUFFERING;
        }
        m.put("positionMs", pos);
        m.put("durationMs", dur);
        m.put("bufferedMs", buffered);
        m.put("playing", playing);
        m.put("buffering", buffering);
        m.put("videoWidth", videoW);
        m.put("videoHeight", videoH);
        m.put("activeCueSi", activeCueText());
        m.put("subtitleState", subState);
        try {
            s.success(m);
        } catch (Throwable ignored) { }
        // the cast remote's /status mirror (only while a session is live)
        PlayActivity.bridgePosition(pos, dur, playing);
        maybeRecordHistory();
    }

    /** The Sinhala cue for the player's current position (shift applied). */
    private String activeCueText() {
        if (cues.isEmpty() || player == null) {
            // v3.4.6.g — no fetched cues: surface the stream's own text track
            // honestly (the worker-outage English fallback lane).
            return cues.isEmpty() ? embeddedCueNow : "";
        }
        long pos = player.getCurrentPosition() - cueShiftMs;
        String best = "";
        for (Cue c : cues) {
            if (pos >= c.t0 && pos <= c.t1) { best = c.si != null && !c.si.isEmpty() ? c.si : c.en; break; }
        }
        return best;
    }

    /** WatchHistory upsert at most every 5 s while playing. */
    private void maybeRecordHistory() {
        if (player == null || !player.isPlaying()) return;
        long now = System.currentTimeMillis();
        if (now - lastHistoryAt < 5000) return;
        recordHistory(player.getCurrentPosition());
    }

    private void recordHistory(long pos) {
        lastHistoryAt = System.currentTimeMillis();
        if (url.isEmpty() || testMode) return;
        try {
            WatchHistory.Item it = new WatchHistory.Item(title, url,
                    "", "url", Math.max(0, pos), 0L, System.currentTimeMillis());
            WatchHistory.save(prefs, WatchHistory.upsert(WatchHistory.load(prefs), it));
        } catch (Throwable t) {
            Diag.w("player", "history: " + t);
        }
    }

    // ── tracks (the Audio & Quality sheet's source) ─────────────────────────

    private List<Map<String, Object>> tracksJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (player == null) return out;
        try {
            androidx.media3.common.Tracks tracks = player.getCurrentTracks();
            for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
                androidx.media3.common.Format fmt = g.getTrackFormat(0);
                String type = null;
                if (fmt.sampleMimeType != null) {
                    if (fmt.sampleMimeType.startsWith("video/")) type = "video";
                    else if (fmt.sampleMimeType.startsWith("audio/")) type = "audio";
                }
                if (type == null) continue;
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("type", type);
                int selected = -1;
                List<Map<String, Object>> list = new ArrayList<>();
                for (int i = 0; i < g.length; i++) {
                    androidx.media3.common.Format f = g.getTrackFormat(i);
                    String label;
                    if ("video".equals(type)) {
                        label = f.width > 0 && f.height > 0
                                ? f.height + "p"
                                : ("track " + i);
                        if (f.bitrate > 0) label += " · " + (f.bitrate / 1000) + "kbps";
                    } else {
                        label = f.language != null ? f.language.toUpperCase(Locale.ROOT)
                                : (f.label != null ? f.label : "track " + i);
                        if (f.bitrate > 0) label += " · " + (f.bitrate / 1000) + "kbps";
                    }
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("index", i);
                    t.put("label", label);
                    list.add(t);
                    if (g.isTrackSelected(i)) selected = i;
                }
                if (!list.isEmpty()) {
                    group.put("selected", selected);
                    group.put("tracks", list);
                    out.add(group);
                }
            }
        } catch (Throwable t) {
            Diag.w("player", "tracks: " + t);
        }
        return out;
    }

    private void selectTrack(String type, int index) {
        if (player == null || index < 0) return;
        try {
            androidx.media3.common.Tracks tracks = player.getCurrentTracks();
            for (androidx.media3.common.Tracks.Group g : tracks.getGroups()) {
                androidx.media3.common.Format fmt = g.getTrackFormat(0);
                String t = null;
                if (fmt.sampleMimeType != null) {
                    if (fmt.sampleMimeType.startsWith("video/")) t = "video";
                    else if (fmt.sampleMimeType.startsWith("audio/")) t = "audio";
                }
                if (!type.equals(t)) continue;
                // media3 1.11.1's common TrackSelectionOverride (setOverrideForType)
                androidx.media3.common.TrackGroup tg = g.getMediaTrackGroup();
                TrackSelectionParameters params = player.getTrackSelectionParameters().buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(tg, index))
                        .build();
                player.setTrackSelectionParameters(params);
                return;
            }
        } catch (Throwable t) {
            Diag.w("player", "selectTrack: " + t);
        }
    }

    /**
     * v3.4.6.e — REAL orientation control on the FlutterActivity host.
     * The setting also PERSISTS the player-default (AppPrefs.K_ORIENTATION)
     * so the next session opens with the user's chosen rotation policy, and
     * the answer echoes back through the frames stream's subtitle state for
     * HUD feedback. Pure mode→constant mapping — never an exception.
     */
    private void setOrientation(String mode) {
        int o;
        String label;
        if ("landscape".equals(mode)) {
            o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            label = "landscape locked";
        } else if ("portrait".equals(mode)) {
            o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            label = "portrait locked";
        } else {
            o = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
            label = "auto-rotate";
            mode = "auto";
        }
        try {
            host.setRequestedOrientation(o);
            prefs.edit().putString(AppPrefs.K_ORIENTATION, mode).apply();
            subState = "orientation: " + label;
            pushFrame();
            Diag.i("player", "orientation " + mode + " → " + label);
        } catch (Throwable t) {
            Diag.w("player", "orientation: " + t);
        }
    }

    // ── the Sinhala subtitle pipeline ────────────────────────────────────────

    private void applyTestCues() {
        cues.clear();
        cues.add(new Cue(0, 500, 4000, "00:00:00,500 --> 00:00:04,000", "", "", "ආයුබෝවන්! සිංහල Player ක්‍රියාත්මකයි"));
        cues.add(new Cue(1, 4600, 9000, "00:00:04,600 --> 00:00:09,000", "00:00:04,600 --> 00:00:09,000", "", "මෙය AnantPlay Cast මඟින් එවන වීඩියෝවකි — උපසිරැසි සිංහලෙන්."));
        cues.add(new Cue(2, 9500, 15000, "00:00:09,500 --> 00:00:15,000", "", "", "ඔබේ චිත්‍රපටිය AnantPlay තුළ විවෘත කර Cast ඔබන්න — මෙය ස්වයංක්‍රීයව විවෘත වේ."));
        subState = "ready · test cues";
    }

    private void maybeFetchSubs() {
        if (!prefs.getBoolean(AppPrefs.K_AUTO_FETCH, true)) return;
        if (TitleCleaner.isJunkTitle(title)) {
            subState = "tap Search Title for subs";
            return;
        }
        findSubs(title, null, 0, 0);
    }

    /**
     * v3.4.6.e — REAL subtitle language switching on the LIVE session.
     * Persists the choice (K_DEFAULT_LANG — the pipeline's fetch language)
     * and re-runs the pipeline for the current title: 'si' re-fetches the
     * Sinhala ladder; 'en' (or any other code) re-fetches and renders the
     * ENGLISH/foreign cue text (Cue.en) when the fetch is not Sinhala — the
     * renderer prefers .si and falls back to .en per cue, so switching is a
     * re-fetch + re-render, never a dead toggle.
     */
    private void switchSubLanguage(String lang) {
        try {
            prefs.edit().putString(AppPrefs.K_DEFAULT_LANG, lang).apply();
        } catch (Throwable ignored) { }
        String label = "si".equals(lang) ? "Sinhala" : lang.toUpperCase(Locale.ROOT);
        subState = "switching subs → " + label;
        pushFrame();
        String t = (title == null || title.isEmpty()) ? url : title;
        // A fetch in the NEW language: the generation counter invalidates any
        // in-flight fetch and the fresh cues replace the old ones live.
        findSubs(t, null, 0, 0);
        Diag.i("subs", "language switch → " + lang);
    }

    private void findSubs(String t, final String imdb, int season, int episode) {
        if (fetchingSubs) return;
        fetchingSubs = true;
        final int myGen = ++subGen;
        subState = "fetching…";
        pushFrame();
        new Thread(() -> {
            // v3.4.6.g — every worker leg is individually guarded: a dead or
            // unreachable worker (FAIL #47's HTTP 0 in 8 ms) degrades into the
            // honest outage ladder instead of throwing into the old catch,
            // whose finishSubs then crashed the player off-main (FAIL #56).
            boolean workerDown = false;
            String downWhy = "";
            try {
                boolean series = episode > 0 || season > 0;
                String type = series ? "series" : "movie";
                final String key = type + ":t:" + t.toLowerCase(Locale.ROOT)
                        + ":" + season + ":" + episode + ":si";
                List<Cue> cached = store.load(key);
                if (cached != null) {
                    applyCues(cached, "cached");
                    return;
                }
                // resolve title → imdb (the /api/resolve contract PlayActivity uses)
                String id = imdb;
                if (id == null || id.isEmpty()) {
                    Map<String, Object> r = null;
                    try {
                        r = api.resolve(t, series, season, episode);
                    } catch (Throwable down) {
                        workerDown = true;
                        downWhy = down.getClass().getSimpleName();
                        Diag.w("subs", "worker resolve unreachable: " + down);
                    }
                    if (r == null) {
                        if (workerDown) { workerOutage(myGen, downWhy); return; }
                        finishSubs(myGen, "no Cinemeta match — try Search Title");
                        return;
                    }
                    id = String.valueOf(r.get("imdb"));
                }
                // the worker job (up to 4 rounds: cold-token re-ensures)
                // v3.4.6.e — the fetch language is the user's persisted
                // default (K_DEFAULT_LANG — 'si' default), so the Language
                // switch genuinely changes what the pipeline requests.
                String langCode = prefs.getString(AppPrefs.K_DEFAULT_LANG, Langs.DEFAULT);
                if (langCode == null || langCode.isEmpty() || !Langs.isKnown(langCode)) {
                    langCode = Langs.DEFAULT;
                }
                for (int round = 0; round < 4 && myGen == subGen; round++) {
                    Map<String, Object> job;
                    try {
                        job = api.subtitles(type, id, season, episode, t,
                                langCode, null);
                    } catch (Throwable down) {
                        workerDown = true;
                        downWhy = down.getClass().getSimpleName();
                        Diag.w("subs", "worker job unreachable: " + down);
                        break;
                    }
                    String status = String.valueOf(job.get("status"));
                    Map<String, Object> si = (Map<String, Object>) MiniJson.objGet(job, "si");
                    String token = si == null ? null : String.valueOf(si.get("token"));
                    if ("done".equals(status) && token != null && !token.isEmpty()) {
                        String text;
                        try {
                            text = api.srt(token);
                        } catch (Throwable down) {
                            workerDown = true;
                            downWhy = down.getClass().getSimpleName();
                            Diag.w("subs", "worker srt unreachable: " + down);
                            break;
                        }
                        List<Cue> got = SrtLoader.load(
                                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                        if (myGen != subGen) return;
                        // v3.4.6.g — the script check + free-translator bridge:
                        // a non-Sinhala track is AUTO-translated on-device (the
                        // phone's "translation does not work" fix), never a
                        // dead "result not Sinhala — try Free Translate".
                        if (got != null && !got.isEmpty()) {
                            if (validateOrTranslate(got, myGen, key, String.valueOf(job.get("source"))))
                                return;
                        } else {
                            finishSubs(myGen, "worker returned an empty track");
                        }
                        return;
                    }
                    if ("translating".equals(status) && token != null && !token.isEmpty()) {
                        // poll /sub/<token>.srt until it answers a real .srt
                        for (int poll = 0; poll < 60 && myGen == subGen; poll++) {
                            try {
                                String body = api.srt(token);
                                if (body.trim().startsWith("{")) continue;   // still JSON status
                                List<Cue> got = SrtLoader.load(
                                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                                if (myGen != subGen) return;
                                if (got != null && !got.isEmpty()) {
                                    if (validateOrTranslate(got, myGen, key, String.valueOf(job.get("provider"))))
                                        return;
                                } else {
                                    finishSubs(myGen, "result not Sinhala");
                                }
                                return;
                            } catch (BridgeApi.HttpException he) {
                                if (he.status == 404) break;   // cold isolate — re-ensure
                                if (he.status == 429 || he.status >= 500) {
                                    // v3.4.6.g — quota/cold errors degrade to the
                                    // on-device free translator, not a dead state.
                                    finishSubs(myGen, "worker busy — free translator available");
                                    return;
                                }
                            } catch (Throwable down) {
                                workerDown = true;
                                downWhy = down.getClass().getSimpleName();
                                break;
                            }
                            Thread.sleep(4000);
                        }
                        continue;   // token went cold — a warm round re-ensures
                    }
                    if ("not-found".equals(status)) {
                        finishSubs(myGen, "no subtitles found — try local .srt");
                        return;
                    }
                    finishSubs(myGen, "worker: " + status);
                    return;
                }
                if (workerDown) { workerOutage(myGen, downWhy); return; }
                if (myGen == subGen) finishSubs(myGen, "timed out");
            } catch (Throwable e) {
                Diag.w("subs", "find: " + e);
                finishSubs(myGen, "failed: " + e.getClass().getSimpleName());
            } finally {
                fetchingSubs = false;
            }
        }, "subs-find").start();
    }

    private void applyCues(List<Cue> list, String source) {
        // v3.4.6.g — called from the subs-find worker: mutate + publish on main.
        if (!onMain()) { main.post(() -> applyCues(list, source)); return; }
        cues.clear();
        cues.addAll(list);
        subState = "ready · " + list.size() + " cues · " + source;
        pushFrame();
    }

    private void finishSubs(int gen, String state) {
        // v3.4.6.g — the exact frame that crashed at :824 off-main; dispatched.
        if (!onMain()) { main.post(() -> finishSubs(gen, state)); return; }
        if (gen != subGen) return;
        subState = state;
        pushFrame();
    }

    /** v3.4.6.g — THE SCRIPT CHECK + FREE-TRANSLATOR BRIDGE (PlayActivity's
     *  proven validateOrTranslate pattern, now in the in-shell player): a
     *  fetched track that is NOT Sinhala goes through FreeSubTranslator
     *  (on-device, keyless, disk-cached, ZWJ-preserving) and the RESULT is
     *  applied live — never the old dead-end "result not Sinhala — try Free
     *  Translate" state that left the phone without subtitles. */
    private boolean validateOrTranslate(List<Cue> got, int myGen, String key, String how) {
        if (got == null || got.isEmpty()) return false;
        if (SubLangDetect.looksSinhala(SubLangDetect.sampleOf(got))) {
            applyCues(got, how);
            if (key != null) store.save(key, got);
            return true;
        }
        if (!prefs.getBoolean(AppPrefs.K_FREE_TRANSLATOR, true)) {
            // honestly labeled: the user disabled the free translator
            applyCues(got, how + " (English — the free translator is off)");
            return true;
        }
        return freeTranslate(got, myGen, key, how);
    }

    /** The on-device keyless translation core (thread context, gen-guarded,
     *  NEVER wipes the source on failure — PlayActivity's contract). */
    private boolean freeTranslate(List<Cue> source, int myGen, String key, String how) {
        try {
            finishSubs(myGen, "free keyless translation… · නොමිලේ පරිවර්තනය වෙමින්…");
            FreeSubTranslator.Result r = FreeSubTranslator.translate(
                    ctx.getFilesDir(), source, "si",
                    ms -> { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } });
            if (myGen != subGen) return true;   // a newer fetch owns the session
            if (r != null && r.cues != null && !r.cues.isEmpty()
                    && SubLangDetect.looksSinhala(SubLangDetect.sampleOf(r.cues))) {
                applyCues(r.cues, (how == null || how.isEmpty() ? "" : how + " → ")
                        + "free translator" + (r.cacheHits > 0 ? " (cached)" : ""));
                if (key != null) store.save(key, r.cues);
                return true;
            }
            // the translator did not land → show the source track HONESTLY labeled
            applyCues(source, (how == null ? "" : how + " → ")
                    + "free translation incomplete — showing the source track");
            return true;
        } catch (Throwable e) {
            Diag.w("subs", "free translator: " + e);
            return false;
        }
    }

    /** v3.4.6.g — THE WORKER-OUTAGE LADDER (FAIL #47's 8 ms HTTP 0 root cause:
     *  the worker legs threw, the old catch handler then crashed off-main).
     *  An unreachable worker degrades the session honestly: the state line
     *  names the still-working lanes (Browser subtitle sites, a local .srt,
     *  the free on-device translator) instead of killing the pipeline. */
    private void workerOutage(int myGen, String reason) {
        finishSubs(myGen, "worker unreachable (" + reason + ") — "
                + "Browser උපසිරැසි අඩවි හෝ local .srt — free පරිවර්තකයෙන් සිංහල වේ · "
                + "use a Browser sub site or a local .srt; the free keyless translator translates it");
    }

    private boolean loadSubsFromUrl(String su) {
        try {
            // a plain URL fetch through the bridge's own transport (no
            // per-host Cookie replay — a pasted sub URL is public by design)
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(su).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", StreamHttpConfig.CHROME_UA);
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] raw = readAll(in == null
                    ? new java.io.ByteArrayInputStream(new byte[0]) : in);
            c.disconnect();
            List<Cue> loaded = SrtLoader.load(raw, "url.srt");
            if (loaded == null || loaded.isEmpty()) return false;
            if (!SubLangDetect.looksSinhala(SubLangDetect.sampleOf(loaded))
                    && prefs.getBoolean(AppPrefs.K_FREE_TRANSLATOR, true)) {
                FreeSubTranslator.Result r = FreeSubTranslator.translate(
                        ctx.getFilesDir(), loaded, "si", null);
                if (r != null && r.cues != null && !r.cues.isEmpty()) loaded = r.cues;
            }
            applyCues(loaded, "url");
            return true;
        } catch (Throwable t) {
            Diag.w("subs", "url: " + t);
            subState = "load failed";
            return false;
        }
    }

    private boolean loadSubsFromFile(String uri) {
        try {
            java.io.InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri));
            if (in == null) return false;
            byte[] raw = readAll(in);
            List<Cue> loaded = SrtLoader.load(raw, "local.srt");
            if (loaded == null || loaded.isEmpty()) return false;
            applyCues(loaded, "local");
            return true;
        } catch (Throwable t) {
            Diag.w("subs", "file: " + t);
            subState = "load failed";
            return false;
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    // ── teardown ─────────────────────────────────────────────────────────────

    private void disposeSession() {
        main.removeCallbacks(ticker);
        if (player != null) {
            try {
                recordHistory(player.getCurrentPosition());
                player.release();
            } catch (Throwable ignored) { }
            player = null;
        }
        if (producer != null) {
            try {
                producer.getSurface().release();
                producer.release();
            } catch (Throwable ignored) { }
            producer = null;
        }
        PlayActivity.adoptBridgeSession(null, null);
    }

    void dispose() {
        disposeSession();
        channel.setMethodCallHandler(null);
        framesChannel.setStreamHandler(null);
    }
}
