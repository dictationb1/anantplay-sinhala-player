package lk.techworld.anantplay.sicast;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

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

    /** The one live session (one player per engine — a second open replaces). */
    private ExoPlayer player;
    private TextureRegistry.SurfaceProducer producer;
    private int textureId = -1;

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

    // the 4 Hz frame pump
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            pushFrame();
            main.postDelayed(this, 250);
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
                    String su = call.argument("url");
                    result.success(loadSubsFromUrl(su == null ? "" : su));
                    break;
                }
                case "loadFile": {
                    String uri = call.argument("uri");
                    result.success(loadSubsFromFile(uri == null ? "" : uri));
                    break;
                }
                case "shift": {
                    Object d = call.argument("deltaMs");
                    cueShiftMs += d instanceof Number ? ((Number) d).longValue() : 0L;
                    subState = "sync " + (cueShiftMs >= 0 ? "+" : "") + cueShiftMs + "ms";
                    pushFrame();
                    result.success(true);
                    break;
                }
                case "status": {
                    result.success(subState);
                    break;
                }
                case "dispose": {
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
        player.addListener(this);
        Surface surface = producer.getSurface();
        player.setVideoSurface(surface);
        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        if (mime != null && !mime.isEmpty()) item = item.buildUpon().setMimeType(mime).build();
        player.setMediaItem(item);
        player.prepare();
        if (startMs > 10000) player.seekTo(startMs);
        player.setPlayWhenReady(true);

        // 5 — the pipeline bookkeeping.
        if (testMode) {
            applyTestCues();
        } else {
            subState = "fetching…";
            maybeFetchSubs();
        }
        recordHistory(startMs);
        main.post(ticker);
        PlayActivity.adoptBridgeSession(title, url);
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
        if (cues.isEmpty() || player == null) return "";
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

    private void findSubs(String t, final String imdb, int season, int episode) {
        if (fetchingSubs) return;
        fetchingSubs = true;
        final int myGen = ++subGen;
        subState = "fetching…";
        new Thread(() -> {
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
                    Map<String, Object> r = api.resolve(t, series, season, episode);
                    if (r == null) {
                        finishSubs(myGen, "no Cinemeta match — try Search Title");
                        return;
                    }
                    id = String.valueOf(r.get("imdb"));
                }
                // the worker job (up to 4 rounds: cold-token re-ensures)
                for (int round = 0; round < 4 && myGen == subGen; round++) {
                    Map<String, Object> job = api.subtitles(type, id, season, episode, t,
                            Langs.DEFAULT, null);
                    String status = String.valueOf(job.get("status"));
                    Map<String, Object> si = (Map<String, Object>) MiniJson.objGet(job, "si");
                    String token = si == null ? null : String.valueOf(si.get("token"));
                    if ("done".equals(status) && token != null && !token.isEmpty()) {
                        String text = api.srt(token);
                        List<Cue> got = SrtLoader.load(
                                text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                        if (myGen != subGen) return;
                        if (got != null && !got.isEmpty()
                                && SubLangDetect.looksSinhala(SubLangDetect.sampleOf(got))) {
                            applyCues(got, String.valueOf(job.get("source")));
                            store.save(key, got);
                        } else {
                            finishSubs(myGen, "result not Sinhala — try Free Translate");
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
                                if (got != null && !got.isEmpty()
                                        && SubLangDetect.looksSinhala(SubLangDetect.sampleOf(got))) {
                                    applyCues(got, String.valueOf(job.get("provider")));
                                    store.save(key, got);
                                } else {
                                    finishSubs(myGen, "result not Sinhala");
                                }
                                return;
                            } catch (BridgeApi.HttpException he) {
                                if (he.status == 404) break;   // cold isolate — re-ensure
                                if (he.status == 429 || he.status >= 500) {
                                    finishSubs(myGen, "worker busy — free translator available");
                                    return;
                                }
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
        cues.clear();
        cues.addAll(list);
        subState = "ready · " + list.size() + " cues · " + source;
        pushFrame();
    }

    private void finishSubs(int gen, String state) {
        if (gen != subGen) return;
        subState = state;
        pushFrame();
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
