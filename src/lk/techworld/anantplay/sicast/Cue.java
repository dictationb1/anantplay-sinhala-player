package lk.techworld.anantplay.sicast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/* One subtitle cue, exactly the worker's /api/paired/<jobId>.json shape plus the local
 * .srt shape. Plain Java — shared by the app and the verification lab.
 *
 * Fields:
 *   i   cue index (0-based, the file order)
 *   t0  start in ms, t1 end in ms (parsed from the ORIGINAL timestamp line)
 *   ts  the raw timestamp line ("00:00:01,000 --> 00:00:03,500") — kept so a locally
 *       loaded .srt and the worker's pair are interchangeable
 *   en  the original English text (multi-line, '\n' inside)
 *   enf the English text flattened with AnantPlay's EXACT showCues() rule
 *       (PlayerActivity.java:356-358: inner '\n' → space unless the next line starts
 *       with '-' or '–'); this is the precise string AnantPlay paints into its
 *       subtitle TextView, so cue-lock compares live text against this
 *   si  the Sinhala text (worker-paired or, for human subs / local files, the only text)
 *   normenf/normen — precomputed normalized forms for O(1) matching (see CueMatcher) */
public final class Cue {
    public final int i;
    public final long t0;
    public final long t1;
    public final String ts;
    public final String en;
    public final String enf;
    public final String si;
    String normenf;   // package-private: filled once by CueMatcher
    String normen;

    public Cue(int i, long t0, long t1, String ts, String en, String enf, String si) {
        this.i = i;
        this.t0 = t0;
        this.t1 = t1;
        this.ts = ts;
        this.en = en == null ? "" : en;
        this.enf = enf == null ? flatten(this.en) : enf;
        this.si = si == null ? "" : si;
    }

    /** AnantPlay's showCues() flattening (PlayerActivity.java:349-362). */
    public static String flatten(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next != '-' && next != '\u2013') sb.append(' ');
                else sb.append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Parse "00:00:01,000 --> 00:00:03,500" (also '.' fractions, 1-3 digits — the same
     * shapes AnantPlay's SubSync.java:26-48 accepts). Returns [t0, t1] or null. */
    public static long[] parseTs(String line) {
        if (line == null) return null;
        java.util.regex.Matcher m = TS_RE.matcher(line);
        if (!m.find()) return null;
        return new long[]{ ms(m.group(1), m.group(2), m.group(3), m.group(4)),
                           ms(m.group(5), m.group(6), m.group(7), m.group(8)) };
    }

    private static final java.util.regex.Pattern TS_RE = java.util.regex.Pattern.compile(
        "(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})");

    private static long ms(String h, String m, String s, String frac) {
        long f = Long.parseLong(frac);
        if (frac.length() == 1) f *= 100;
        else if (frac.length() == 2) f *= 10;
        return ((Long.parseLong(h) * 60 + Long.parseLong(m)) * 60 + Long.parseLong(s)) * 1000 + f;
    }

    // ── builders: the worker's paired JSON and a plain SRT both become List<Cue> ──

    /** Parse the worker's /api/paired/<jobId>.json body: {status, cues:[{i,t0,t1,en,enf,si}]}. */
    @SuppressWarnings("unchecked")
    public static List<Cue> fromPairedJson(String json) {
        Object root = MiniJson.parse(json);
        List<Object> arr = MiniJson.arr(MiniJson.objGet(root, "cues"));
        if (arr == null) return Collections.emptyList();
        List<Cue> out = new ArrayList<>(arr.size());
        for (Object o : arr) {
            long t0 = MiniJson.lng(o, "t0", 0), t1 = MiniJson.lng(o, "t1", 0);
            out.add(new Cue(
                (int) MiniJson.lng(o, "i", out.size()),
                t0, t1, null,
                MiniJson.str(o, "en"),
                MiniJson.str(o, "enf"),
                MiniJson.str(o, "si")));
        }
        return out;
    }

    /** Parse an .srt body (BOM tolerant, CRLF tolerant) into cues; si = the text. */
    public static List<Cue> fromSrt(String text) {
        if (text == null) return Collections.emptyList();
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        List<Cue> out = new ArrayList<>();
        for (String block : text.split("\\n\\n+")) {
            String[] lines = block.split("\\n");
            int first = 0;
            java.util.List<String> body = new ArrayList<>();
            for (String l : lines) { if (!l.trim().isEmpty()) body.add(l); }
            if (body.isEmpty()) continue;
            if (body.get(0).trim().matches("\\d+")) first = 1;
            if (body.size() <= first) continue;
            long[] t = parseTs(body.get(first));
            if (t == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = first + 1; i < body.size(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(body.get(i));
            }
            String txt = sb.toString();
            out.add(new Cue(out.size(), t[0], t[1], body.get(first), txt, null, txt));
        }
        return out;
    }

    /** {"subtitles"}-shaped /api/paired status body → {status, progress} (used by the service). */
    public static Map<String, Object> statusOf(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) return Collections.emptyMap();
        return (Map<String, Object>) root;
    }
}
