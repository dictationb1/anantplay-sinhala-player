package lk.techworld.anantplay.sicast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/* The emergency local loader: a .srt / .zip / .gz the user picked by hand (a Baiscope /
 * Zoom / PirateLK download) becomes a clean List<Cue> for Clock-Sync mode.
 *
 * Plain Java (java.util.zip only), so the lab exercises the exact same code. The Android
 * service hands it the file's bytes from its ContentResolver.
 *
 * SAFETY (hardened after the audit):
 *   · ≤ 5 MB input, ≤ 5 MB *total* decompressed output (zip-bomb cap), ≤ 64 entries;
 *   · every entry name is vetted — absolute paths, "..", "C:\" drive letters and
 *     Windows separators are rejected (we never write entries out, but a hostile
 *     archive must not be able to steer any future extraction either);
 *   · __MACOSX/ and dot-files are ignored, nested folders are fine (real downloads
 *     ship "Movie.2024/subs/sinhala.srt");
 *   · UTF-8 first, UTF-8/UTF-16 BOMs stripped, CP1252 fallback for old Baiscope files. */
public final class SrtLoader {

    public static final int MAX_BYTES = 5 * 1024 * 1024; // matches the worker's zip-bomb cap
    public static final int MAX_ENTRIES = 64;

    /** Parse any of .srt / .vtt-ish text, a .zip holding one, or a .gz. Returns cues or throws. */
    public static List<Cue> load(byte[] bytes, String nameHint) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("empty file");
        if (bytes.length > MAX_BYTES) throw new IOException("file too large (5 MB max)");
        if (looksZip(bytes)) return fromZip(bytes);
        if (looksGzip(bytes)) return Cue.fromSrt(decode(inflateGzip(bytes)));
        return Cue.fromSrt(decode(bytes));
    }

    static boolean looksZip(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0x50 && (b[1] & 0xFF) == 0x4B && (b[2] & 0xFF) == 0x03 && (b[3] & 0xFF) == 0x04;
    }

    static boolean looksGzip(byte[] b) {
        return b.length > 1 && (b[0] & 0xFF) == 0x1f && (b[1] & 0xFF) == 0x8b;
    }

    /**
     * True when a zip entry name is safe to even look at: relative, no parent hops, no
     * drive letters, no backslashes, no absolute paths. (Classic Zip Slip input.)
     */
    static boolean safeEntryName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.indexOf('\\') >= 0) return false;                 // C:\subs\x.srt
        if (name.startsWith("/")) return false;                    // /etc/passwd
        if (name.length() > 1 && name.charAt(1) == ':') return false;   // C:x.srt
        for (String part : name.split("/")) {
            if (part.equals("..")) return false;
        }
        return true;
    }

    private static List<Cue> fromZip(byte[] bytes) throws IOException {
        List<Cue> best = null;
        int entries = 0;
        int budget = MAX_BYTES;   // one shared budget for every decompressed entry
        ZipInputStream zs = new ZipInputStream(new ByteArrayInputStream(bytes));
        try {
            for (ZipEntry e; (e = zs.getNextEntry()) != null; ) {
                if (++entries > MAX_ENTRIES) throw new IOException("too many zip entries");
                String raw = e.getName() == null ? "" : e.getName();
                String n = raw.toLowerCase(java.util.Locale.ROOT);
                if (n.contains("__macosx") || n.contains("/.") || n.startsWith(".")) continue;
                if (!safeEntryName(raw)) continue;                 // rejected, never parsed
                if (!n.endsWith(".srt") && !n.endsWith(".vtt")) continue;
                byte[] data = readAll(zs, budget);
                budget -= data.length;
                List<Cue> cues = Cue.fromSrt(decode(data));
                if (best == null || cues.size() > best.size()) best = cues; // the biggest real track wins
            }
        } finally {
            try { zs.close(); } catch (IOException ignored) { }
        }
        if (best == null || best.isEmpty()) throw new IOException("no .srt/.vtt inside the zip");
        return best;
    }

    private static byte[] inflateGzip(byte[] bytes) throws IOException {
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes));
        try {
            return readAll(gz, MAX_BYTES);
        } finally {
            try { gz.close(); } catch (IOException ignored) { }
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (out.size() + n > max) throw new IOException("decompressed file too large (5 MB max)");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * UTF-8 first (BOM stripped); a CP1252 fallback covers old Baiscope encodings.
     * UTF-16 (LE/BE) with a BOM is honoured, because a few Windows tools save .srt that way.
     */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE)
            return stripBom(new String(bytes, StandardCharsets.UTF_16LE));
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF)
            return stripBom(new String(bytes, StandardCharsets.UTF_16BE));
        String utf8 = stripBom(new String(bytes, StandardCharsets.UTF_8));
        if (!utf8.contains("\uFFFD")) return utf8; // no replacement chars → valid UTF-8
        Charset cp1252 = Charset.isSupported("windows-1252") ? Charset.forName("windows-1252") : null;
        if (cp1252 != null) {
            String alt = new String(bytes, cp1252);
            if (!alt.contains("\uFFFD")) return stripBom(alt);
        }
        return utf8;
    }

    private static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }
}
