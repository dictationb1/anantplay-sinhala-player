package lk.techworld.anantplay.sicast;

import android.media.MediaCodecList;

import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v3.4.6.m — THE THERMAL / HW-CODEC ENGINE (the thermal audit, Part_02
 * 00:55–03:24: software-decode heat, marathon battery drain and the
 * "safe" resolution downgrades the old pipeline silently made).
 *
 * <p>THE CONTRACT (pure static surface, zero state on the UI thread):
 *
 * <ul>
 *   <li><b>Inventory</b> — {@link #hardwareVideoDecoders()} scans
 *       {@code MediaCodecList(REGULAR_CODECS)} ONCE per process and collects every
 *       hardware video decoder it exposes (below API 29 — where
 *       {@code isHardwareAccelerated()} does not exist — the same result is
 *       approximated by EXCLUDING the known software prefixes
 *       {@code c2.android.} / {@code OMX.google.} / {@code c2.mtk.sw} /
 *       {@code OMX.SEC.}). The inventory is logged per mime as
 *       {@code "hw video decoders: avc=1(c2.qti.avc.decoder), hevc=1(...), vp9=0, av1=0"}.
 *       NO device identifier is ever logged (never Build.MODEL, never
 *       Build.DEVICE, never a serial) — Diag's redaction stays the last gate.</li>
 *
 *   <li><b>Hardware-first selection</b> — {@link #hardwareFirstSelector()} wraps
 *       {@link MediaCodecSelector#DEFAULT} and STABLY re-orders every decoder
 *       list so {@code hardwareAccelerated} entries come FIRST. It NEVER drops
 *       a decoder: a device with only software decoders still plays (the
 *       software entries simply keep their original relative order), and there
 *       is NO resolution downgrade — the caps only change when the caller
 *       explicitly asks for Low Data Mode.</li>
 *
 *   <li><b>Low Data Mode</b> — {@link #applyLowDataMode(TrackSelectionParameters.Builder, boolean)}
 *       caps the track selector at 1.2 Mbps / 854×480 (480p) when the caller
 *       passes {@code true}, and CLEARS the caps (full optimal
 *       hardware-decoded quality) when the caller passes {@code false}. The
 *       ON/OFF decision is the CALLER's — this method only paints the values,
 *       so PlayActivity can re-apply it live whenever the preference flips.
 *       (NOTE: the audit brief called this class
 *       {@code DefaultTrackSelector.SelectionParameters}; media3 1.11.1 has no
 *       such class — the real lane every caller (ExoPlayer's
 *       {@code setTrackSelectionParameters}) uses is
 *       {@link TrackSelectionParameters}, whose Builder carries
 *       {@code setMaxVideoBitrate}/{@code setMaxVideoSize}; that is what this
 *       method accepts, DefaultTrackSelector.Parameters extends it.)</li>
 *
 *   <li><b>Wiring</b> — {@link #applyHardwareFirst(DefaultRenderersFactory)} is
 *       the effective lane in media3 1.11.1 (the selector rides the renderers
 *       factory, not the builder); {@link #applyHardwareFirst(ExoPlayer.Builder)}
 *       is kept as the requested public surface and applies the same selector
 *       through reflection when a future media3 exposes it, falling back
 *       silently (Diag.w only) when it does not.</li>
 * </ul>
 */
@UnstableApi   // mirrors PlayActivity's declaration: media3's API surface
final class CodecPolicy {

    private CodecPolicy() { }

    /** The video mimes the inventory tracks (decode order of preference). */
    private static final String[] VIDEO_MIMES = {
        "video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01", "video/dolby-vision"
    };

    /** The short tags the inventory log line uses, aligned with {@link #VIDEO_MIMES}. */
    private static final String[] MIME_TAGS = { "avc", "hevc", "vp9", "av1", "dovi" };

    /** Below API 29 a decoder whose name starts with one of these is software. */
    private static final String[] SOFTWARE_PREFIXES = { "c2.android.", "OMX.google.", "c2.mtk.sw", "OMX.SEC." };

    /** Low Data Mode caps (480p at 1.2 Mbps — the audit's numbers). */
    static final int LOW_DATA_MAX_BITRATE = 1_200_000;
    static final int LOW_DATA_MAX_WIDTH = 854;
    static final int LOW_DATA_MAX_HEIGHT = 480;

    // ── the inventory (computed once per process, immutable after) ───────────

    /** mime → hardware decoder names (stable scan order); never null. */
    private static volatile Map<String, List<String>> hwInventory;

    private static Map<String, List<String>> inventory() {
        Map<String, List<String>> cached = hwInventory;
        if (cached != null) return cached;
        synchronized (CodecPolicy.class) {
            cached = hwInventory;
            if (cached != null) return cached;
            Map<String, List<String>> found = new LinkedHashMap<>();
            for (String m : VIDEO_MIMES) found.put(m, new ArrayList<>());
            try {
                MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                android.media.MediaCodecInfo[] infos = list.getCodecInfos();
                for (android.media.MediaCodecInfo info : infos) {
                    if (info == null) continue;
                    String name = safeName(info);
                    boolean hw;
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        try { hw = info.isHardwareAccelerated(); }
                        catch (Throwable t) { hw = !softwareName(name); }
                    } else {
                        hw = !softwareName(name);
                    }
                    if (!hw) continue;
                    for (String mime : info.getSupportedTypes()) {
                        List<String> bucket = found.get(mime);
                        if (bucket != null && !bucket.contains(name)) bucket.add(name);
                    }
                }
            } catch (Throwable t) {
                Diag.w("codec", "media-codec inventory failed: " + t);
            }
            hwInventory = Collections.unmodifiableMap(found);
            Diag.i("codec", "hw video decoders: " + inventoryLine(found));
            return found;
        }
    }

    /** "avc=1(c2.qti.avc.decoder), hevc=1(c2.exo.hevc.decoder), vp9=0, av1=0". */
    private static String inventoryLine(Map<String, List<String>> inv) {
        StringBuilder sb = new StringBuilder(96);
        for (int i = 0; i < VIDEO_MIMES.length; i++) {
            if (sb.length() > 0) sb.append(", ");
            List<String> names = inv.get(VIDEO_MIMES[i]);
            sb.append(MIME_TAGS[i]).append('=').append(names == null ? 0 : names.size());
            if (names != null && !names.isEmpty() && names.get(0) != null) {
                sb.append('(').append(names.get(0)).append(')');
            }
        }
        return sb.toString();
    }

    private static String safeName(android.media.MediaCodecInfo info) {
        try {
            String n = info.getName();
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean softwareName(String name) {
        if (name == null || name.isEmpty()) return true;
        for (String p : SOFTWARE_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    // ── the public static surface ────────────────────────────────────────────

    /**
     * Every hardware video decoder the device exposes for the tracked mimes
     * (avc / hevc / vp9 / av01 / dolby-vision), names only — no device
     * identifiers. The per-mime inventory line is logged once per process.
     */
    static String[] hardwareVideoDecoders() {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> bucket : inventory().values()) names.addAll(bucket);
        return names.toArray(new String[0]);
    }

    /** True when the device has a HARDWARE decoder for {@code mime}. */
    static boolean hasHardwareDecoder(String mime) {
        if (mime == null || mime.isEmpty()) return false;
        List<String> bucket = inventory().get(mime);
        return bucket != null && !bucket.isEmpty();
    }

    /**
     * A MediaCodecSelector whose decoder lists are STABLY re-ordered so every
     * {@code hardwareAccelerated} entry precedes every software entry. NO
     * decoder is ever dropped — a software-only device keeps playing, at full
     * resolution (the only caps anywhere are the explicit Low Data Mode ones).
     * Any failure returns the untouched {@link MediaCodecSelector#DEFAULT}.
     */
    static MediaCodecSelector hardwareFirstSelector() {
        try {
            return new MediaCodecSelector() {
                @Override public List<MediaCodecInfo> getDecoderInfos(String mimeType,
                        boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
                        throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
                    List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                    return hardwareFirst(infos);
                }
            };
        } catch (Throwable t) {
            Diag.w("codec", "hardware-first selector unavailable — default order: " + t);
            return MediaCodecSelector.DEFAULT;
        }
    }

    /**
     * The stable hardware-first reorder: hardware entries keep their relative
     * order first, software entries keep theirs after. Never null, never
     * shorter than the input; a null/empty input passes through untouched.
     */
    private static List<MediaCodecInfo> hardwareFirst(List<MediaCodecInfo> infos) {
        if (infos == null || infos.size() < 2) return infos;
        try {
            List<MediaCodecInfo> hw = new ArrayList<>(infos.size());
            List<MediaCodecInfo> sw = new ArrayList<>(infos.size());
            for (MediaCodecInfo i : infos) {
                boolean isHw;
                try { isHw = i != null && i.hardwareAccelerated; }
                catch (Throwable t) { isHw = false; }
                (isHw ? hw : sw).add(i);
            }
            if (hw.isEmpty() || sw.isEmpty()) return infos;   // already uniform: no reorder
            List<MediaCodecInfo> out = new ArrayList<>(infos.size());
            out.addAll(hw);
            out.addAll(sw);
            return out;
        } catch (Throwable t) {
            Diag.w("codec", "hardware-first reorder skipped: " + t);
            return infos;
        }
    }

    /**
     * The EFFECTIVE lane in media3 1.11.1: the codec selector rides the
     * RenderersFactory. Any vendor failure is swallowed — the factory keeps
     * its default selector and playback proceeds.
     */
    static void applyHardwareFirst(DefaultRenderersFactory factory) {
        if (factory == null) return;
        try {
            factory.setMediaCodecSelector(hardwareFirstSelector());
            Diag.i("codec", "hardware-first decoder selector applied to the renderers factory");
        } catch (Throwable t) {
            Diag.w("codec", "selector apply failed — default order kept: " + t);
        }
    }

    /**
     * The builder-lane surface the audit asked for. media3 1.11.1's
     * {@link ExoPlayer.Builder} has no {@code setMediaCodecSelector} (the lane
     * moved to the RenderersFactory — see {@link #applyHardwareFirst(DefaultRenderersFactory)}),
     * so this applies the selector reflectively when a future media3 exposes
     * it and otherwise logs one Diag.w and returns. Never throws.
     */
    static void applyHardwareFirst(ExoPlayer.Builder builder) {
        if (builder == null) return;
        try {
            java.lang.reflect.Method m = builder.getClass()
                .getMethod("setMediaCodecSelector", MediaCodecSelector.class);
            m.invoke(builder, hardwareFirstSelector());
            Diag.i("codec", "hardware-first decoder selector applied to the player builder");
        } catch (Throwable t) {
            // v3.4.6.m — media3 1.11.1: the selector rides DefaultRenderersFactory;
            // the reflective builder lane is a no-op here by design, not a defect.
            Diag.w("codec", "builder has no setMediaCodecSelector (media3 1.11.1) — "
                + "the hardware-first selector rides the RenderersFactory lane");
        }
    }

    /**
     * Low Data Mode caps. The CALLER decides ON/OFF:
     * <ul>
     *   <li>{@code lowData = true} — {@code setMaxVideoBitrate(1_200_000)} +
     *       {@code setMaxVideoSize(854, 480)} (480p);</li>
     *   <li>{@code lowData = false} — the caps are CLEARED (max int), restoring
     *       full optimal hardware-decoded quality.</li>
     * </ul>
     * Only the builder's caps change; every other parameter the caller built
     * (overrides, text-track disables, …) is preserved untouched.
     */
    static void applyLowDataMode(TrackSelectionParameters.Builder p, boolean lowData) {
        if (p == null) return;
        try {
            if (lowData) {
                p.setMaxVideoBitrate(LOW_DATA_MAX_BITRATE)
                    .setMaxVideoSize(LOW_DATA_MAX_WIDTH, LOW_DATA_MAX_HEIGHT);
                Diag.i("codec", "low-data caps on: 480p / "
                    + (LOW_DATA_MAX_BITRATE / 1000) + " kbps max");
            } else {
                p.setMaxVideoBitrate(Integer.MAX_VALUE)
                    .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
                Diag.i("codec", "low-data caps cleared — full quality");
            }
        } catch (Throwable t) {
            Diag.w("codec", "low-data caps apply failed: " + t);
        }
    }

    /**
     * The one-line engine summary for the playback-start log:
     * {@code "hw-decode: avc,hevc,vp9 · sw-fallback:off"} — empty when the
     * device exposes no hardware video decoders at all.
     */
    static String summary() {
        try {
            Map<String, List<String>> inv = inventory();
            StringBuilder sb = new StringBuilder(48);
            for (int i = 0; i < VIDEO_MIMES.length; i++) {
                List<String> bucket = inv.get(VIDEO_MIMES[i]);
                if (bucket == null || bucket.isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(MIME_TAGS[i]);
            }
            if (sb.length() == 0) return "";
            return "hw-decode: " + sb + " · sw-fallback:off";
        } catch (Throwable t) {
            return "";
        }
    }
}
