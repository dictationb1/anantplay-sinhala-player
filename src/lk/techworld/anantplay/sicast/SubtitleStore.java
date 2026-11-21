package lk.techworld.anantplay.sicast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/* The permanent local subtitle cache: one Sinhala .srt per movie/episode, kept under
 * the app's filesDir — a replay starts instantly and works offline. Pure Java (File
 * based, no Context) so the lab round-trips the exact same code.
 *
 * The stored file is plain SRT: the cue's ORIGINAL timestamps with the Sinhala text
 * (English falls back), so reloading it through Cue.fromSrt() gives a ready cue list
 * for the timeline renderer. */
public final class SubtitleStore {

    private static final int MAX_CACHED = 300;   // newest kept, like Subs.remember()'s 300

    private final File dir;

    public SubtitleStore(File baseDir) {
        dir = new File(baseDir, "subs");
        dir.mkdirs();
    }

    /** key like "movie:t:the odyssey:0:0" or "series:t:name:1:3" — same shape as v1. */
    static String safeName(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (char c : key.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ':' || c == '.') sb.append(c);
            else if (c >= 'A' && c <= 'Z') sb.append((char) (c + 32));
            else sb.append('_');
        }
        return sb.length() > 120 ? sb.substring(0, 120) : sb.toString();
    }

    File fileFor(String key) { return new File(dir, safeName(key) + ".srt"); }

    /** The cached cues for a key, null when not cached (or unreadable). */
    public List<Cue> load(String key) {
        File f = fileFor(key);
        if (!f.exists()) return null;
        try {
            List<Cue> cues = SrtLoader.load(read(f), f.getName());
            return cues.isEmpty() ? null : cues;
        } catch (Exception e) {
            Diag.w("store", "load " + key + " failed: " + e);
            f.delete();
            return null;
        }
    }

    /** Store a cue list (Sinhala text, original timestamps). */
    public void save(String key, List<Cue> cues) {
        try {
            File f = fileFor(key);
            File tmp = new File(f.getPath() + ".tmp");
            StringBuilder sb = new StringBuilder(cues.size() * 96);
            for (Cue c : cues) {
                String text = c.si == null || c.si.isEmpty() ? c.en : c.si;
                if (text == null || text.isEmpty()) continue;
                sb.append(c.i + 1).append("\n").append(c.ts).append("\n").append(text).append("\n\n");
            }
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) throw new IOException("rename");
            trim();
        } catch (Exception e) {
            Diag.w("store", "save " + key + " failed: " + e);
        }
    }

    /** Keep only the newest MAX_CACHED files (save() refreshes lastModified). */
    private void trim() {
        File[] all = dir.listFiles();
        if (all == null || all.length <= MAX_CACHED) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_CACHED; i < all.length; i++) all[i].delete();
    }

    private static byte[] read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }
}
