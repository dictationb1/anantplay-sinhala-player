package lk.techworld.anantplay.sicast;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * v3.4.2 — WATCH HISTORY / CONTINUE WATCHING (pure Java, JSON-persisted).
 *
 * <p>The user's request, in their words: "a sleek Continue Watching history section
 * right on the Home screen showing previously watched movies, a progress bar, the
 * exact timestamp (0:42:15 / 1:58:00) and 1-Tap Resume from where they left off".
 *
 * <p>Everything in this class is deterministic and framework-free (only
 * {@link SharedPreferences} for the two persistence calls), which is exactly why the
 * lab can drive it: {@code bash lab/run_lab.sh} proves add / update / dedupe /
 * cap-20 / resume-ms / clear-one / clear-all / JSON round-trip byte-for-byte.
 *
 * <p>Storage shape (one JSON array under {@link #K_HISTORY}):
 * <pre>[{"title":"…","url":"…","headers":"…","source":"…",
 *       "pos":2535000,"dur":7080000,"at":1763900000000}, …]</pre>
 *
 * <p>Privacy: a history entry is a MOVIE, never a person. Nothing here is device
 * identifying, and {@link PrivacyScrubber} still runs over the Troubleshoot dump.
 * The store is capped at {@link #MAX} entries so the JSON stays under a few KB.
 */
public final class WatchHistory {

    /** The SharedPreferences key (also listed in AppPrefs.BACKUP_KEYS). */
    public static final String K_HISTORY = "watchHistory";

    /** The hard cap: 20 most-recent titles, exactly as the brief asked. */
    public static final int MAX = 20;

    private WatchHistory() { }

    /** One remembered playback. Mutable fields, immutable identity (url, else title). */
    public static final class Item {
        public String title = "";
        public String url = "";
        public String headers = "";      // the Cast JSON headers blob, SECRETS STRIPPED (v3.4.3)
        public String source = "";       // cast | web | test | url
        public long pos;                 // ms watched
        public long dur;                 // ms total (0 = unknown live stream)
        public long at;                  // lastWatchedAt, epoch ms
        /** v3.4.3 — true when {@link #url} is an EPHEMERAL AnantPlay LAN relay
         *  ({@code http://192.168.x.x:34783/…}) that will not exist tomorrow. */
        public boolean ephemeralRelay;
        /** v3.4.3 — the canonical upstream URL decoded out of that relay ("" when
         *  the relay carried none). Never a LAN address, so it survives a reboot. */
        public String canonical = "";

        public Item() { }

        public Item(String title, String url, String headers, String source,
                    long pos, long dur, long at) {
            this.title = nz(title);
            this.url = nz(url);
            this.headers = nz(headers);
            this.source = nz(source);
            this.pos = Math.max(0, pos);
            this.dur = Math.max(0, dur);
            this.at = at;
        }

        /** The dedupe identity: the stream URL when there is one, else the title. */
        public String key() {
            String u = url.trim();
            if (!u.isEmpty()) return u;
            return "t:" + title.trim().toLowerCase(java.util.Locale.ROOT);
        }

        /** 0..100, 0 when the duration is unknown. */
        public int percent() { return WatchHistory.percentOf(pos, dur); }

        /** "0:42:15 / 1:58:00" — the exact string the Home card shows. */
        public String progressLabel() { return WatchHistory.progressLabel(pos, dur); }

        /** True when this is worth showing as "Continue watching" (not finished). */
        public boolean resumable() { return pos > 0 && (dur <= 0 || pos < dur - 5000); }

        /**
         * v3.4.3 — TRUE when tapping this row must offer the "Session expired" action
         * instead of pretending a dead LAN relay can be resumed.
         */
        public boolean needsReopen() { return ephemeralRelay && resumable(); }
    }

    /** The exact 1-tap label the Home row shows for an expired relay entry. */
    public static final String EXPIRED_LABEL = "Session expired — Re-open in AnantPlay";

    /**
     * v3.4.3 — CREDENTIAL HYGIENE + RELAY EXPIRY (the gap Replit's audit missed).
     *
     * <p>Two things were wrong with v3.4.2's history store:
     *
     * <ol>
     *   <li>It persisted the raw cast {@code headers} blob — {@code Cookie} and
     *       {@code Authorization} included — into SharedPreferences <b>and</b> into the
     *       user's backup export. A watch-history row is a movie; a session token is a
     *       credential, and credentials do not belong in a history table.</li>
     *   <li>It stored AnantPlay's ephemeral DLNA relay URL verbatim. That URL is a LAN
     *       address with a port that dies with the cast, so "Continue watching" a day
     *       later pointed at nothing — a bare 403/refused connection with no
     *       explanation.</li>
     * </ol>
     *
     * <p>{@link #sanitize(Item)} fixes both in one place, and it is applied on the way
     * IN (record/upsert), on the way OUT (save) and on the way BACK (fromJson — which
     * also migrates rows written by an older version).
     */
    public static Item sanitize(Item it) {
        if (it == null) return null;
        it.title = nz(it.title);
        it.url = nz(it.url);
        it.source = nz(it.source);
        it.canonical = nz(it.canonical);
        // 1. NEVER persist a credential
        if (!it.headers.isEmpty()) it.headers = StreamHttpConfig.stripSecretsJson(it.headers);
        // 2. flag (and where possible unwrap) the ephemeral LAN relay
        if (StreamHttpConfig.isRelay(it.url)) {
            it.ephemeralRelay = true;
            if (it.canonical.isEmpty()) {
                StreamHttpConfig.Relay r = StreamHttpConfig.relay(it.url);
                if (r.upstream != null) it.canonical = r.upstream;
            }
        }
        return it;
    }

    /** True when a stored row still carries a credential (legacy store, pre-v3.4.3). */
    public static boolean hasSecrets(Item it) {
        return it != null && StreamHttpConfig.hasSecrets(it.headers);
    }

    // ── formatting ────────────────────────────────────────────────────────────

    /** "0:42:15" (h:mm:ss) or "4:07" (m:ss) — never "0:00:04". */
    public static String clock(long ms) {
        long total = Math.max(0, ms) / 1000L;
        long h = total / 3600L, m = (total % 3600L) / 60L, s = total % 60L;
        if (h > 0) return h + ":" + two(m) + ":" + two(s);
        return m + ":" + two(s);
    }

    private static String two(long v) { return v < 10 ? "0" + v : String.valueOf(v); }

    /** "0:42:15 / 1:58:00", or "0:42:15" when the duration is unknown. */
    public static String progressLabel(long pos, long dur) {
        if (dur <= 0) return clock(pos);
        // A feature-length title shows BOTH sides with the hour, so the pair lines up
        // exactly like the brief's "0:42:15 / 1:58:00" instead of "42:15 / 1:58:00".
        if (dur >= 3600000L) return clockH(pos) + " / " + clockH(dur);
        return clock(pos) + " / " + clock(dur);
    }

    /** Always h:mm:ss (0:42:15) — used when the pair must line up. */
    public static String clockH(long ms) {
        long total = Math.max(0, ms) / 1000L;
        return (total / 3600L) + ":" + two((total % 3600L) / 60L) + ":" + two(total % 60L);
    }

    /** 0..100 (0 when dur is unknown — the UI then draws an indeterminate bar). */
    public static int percentOf(long pos, long dur) {
        if (dur <= 0) return 0;
        long p = Math.round(pos * 100.0 / dur);
        return (int) Math.max(0, Math.min(100, p));
    }

    // ── the pure list operations (the lab's whole surface) ────────────────────

    /**
     * Insert-or-update, newest first, capped at {@link #MAX}. Identity is
     * {@link Item#key()}: a second watch of the same stream UPDATES its position
     * instead of adding a duplicate row (the "deduped by title/URL" requirement).
     */
    public static List<Item> upsert(List<Item> current, Item add) {
        List<Item> out = new ArrayList<>();
        if (current != null) out.addAll(current);
        if (add == null || (add.title.trim().isEmpty() && add.url.trim().isEmpty())) return out;
        sanitize(add);                                  // v3.4.3 — no secrets, relay flagged
        String k = add.key();
        Item prev = null;
        for (Item it : out) {
            if (it != null && k.equals(it.key())) { prev = it; break; }
        }
        if (prev != null) out.remove(prev);
        // an updated entry inherits the previous headers/title when the new one lacks them
        if (prev != null) {
            if (add.title.trim().isEmpty()) add.title = prev.title;
            if (add.headers.trim().isEmpty()) add.headers = prev.headers;
            if (add.canonical.isEmpty()) add.canonical = prev.canonical;
            if (!add.ephemeralRelay) add.ephemeralRelay = prev.ephemeralRelay;
            if (add.dur <= 0) add.dur = prev.dur;
            if (add.source.trim().isEmpty()) add.source = prev.source;
        }
        out.add(0, add);                                     // newest first
        while (out.size() > MAX) out.remove(out.size() - 1);  // cap 20
        return out;
    }

    /** Remove one entry by its key (url, else title). Returns the new list. */
    public static List<Item> remove(List<Item> current, String key) {
        List<Item> out = new ArrayList<>();
        if (current == null) return out;
        for (Item it : current) {
            if (it == null) continue;
            if (key != null && key.equals(it.key())) continue;
            out.add(it);
        }
        return out;
    }

    /** The most recent N resumable entries (what the Home card renders). */
    public static List<Item> continueWatching(List<Item> current, int limit) {
        List<Item> out = new ArrayList<>();
        if (current == null) return out;
        List<Item> sorted = new ArrayList<>(current);
        Collections.sort(sorted, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a == null || b == null) return 0;
                return Long.compare(b.at, a.at);
            }
        });
        for (Item it : sorted) {
            if (it == null || !it.resumable()) continue;
            out.add(it);
            if (limit > 0 && out.size() >= limit) break;
        }
        return out;
    }

    /** Drop everything already finished (pos within 5 s of the end). */
    public static List<Item> dropFinished(List<Item> current) {
        List<Item> out = new ArrayList<>();
        if (current == null) return out;
        for (Item it : current) if (it != null && it.resumable()) out.add(it);
        return out;
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    public static String toJson(List<Item> items) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        if (items != null) {
            boolean first = true;
            for (Item it : items) {
                if (it == null) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('{')
                  .append("\"title\":").append(MiniJson.quote(it.title)).append(',')
                  .append("\"url\":").append(MiniJson.quote(it.url)).append(',')
                  .append("\"headers\":").append(MiniJson.quote(it.headers)).append(',')
                  .append("\"source\":").append(MiniJson.quote(it.source)).append(',')
                  .append("\"pos\":").append(it.pos).append(',')
                  .append("\"dur\":").append(it.dur).append(',')
                  .append("\"at\":").append(it.at).append(',')
                  .append("\"relay\":").append(it.ephemeralRelay).append(',')
                  .append("\"canonical\":").append(MiniJson.quote(it.canonical))
                  .append('}');
            }
        }
        return sb.append(']').toString();
    }

    /** Never throws: a corrupt blob yields an empty list (history is never critical). */
    public static List<Item> fromJson(String json) {
        List<Item> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            Object root = MiniJson.parse(json);
            List<Object> arr = MiniJson.arr(root);
            if (arr == null) return out;
            for (Object o : arr) {
                if (o == null) continue;
                Item it = new Item(
                    MiniJson.str(o, "title"),
                    MiniJson.str(o, "url"),
                    MiniJson.str(o, "headers"),
                    MiniJson.str(o, "source"),
                    MiniJson.lng(o, "pos", 0L),
                    MiniJson.lng(o, "dur", 0L),
                    MiniJson.lng(o, "at", 0L));
                // v3.4.3 — a row written by an older version may still carry a Cookie:
                // sanitize() strips it and flags the relay on the way back in.
                it.ephemeralRelay = Boolean.TRUE.equals(MiniJson.objGet(o, "relay"));
                it.canonical = nz(MiniJson.str(o, "canonical"));
                sanitize(it);
                // keep anything with a title or a URL; drop only a blank row
                if (!it.title.trim().isEmpty() || !it.url.trim().isEmpty()) out.add(it);
            }
        } catch (Throwable t) {
            Diag.w("history", "corrupt store ignored: " + t);
        }
        while (out.size() > MAX) out.remove(out.size() - 1);
        return out;
    }

    // ── persistence (two calls, no caching: the store is the source of truth) ──

    public static List<Item> load(SharedPreferences p) {
        if (p == null) return new ArrayList<>();
        try { return fromJson(p.getString(K_HISTORY, "")); }
        catch (Throwable t) { return new ArrayList<>(); }
    }

    public static void save(SharedPreferences p, List<Item> items) {
        if (p == null) return;
        // v3.4.3 — belt and braces: nothing reaches disk unsanitized, whoever built the list.
        if (items != null) for (Item it : items) sanitize(it);
        try { p.edit().putString(K_HISTORY, toJson(items)).apply(); }
        catch (Throwable t) { Diag.w("history", "save: " + t); }
    }

    /** One-shot: record/refresh an entry and persist. Returns the new list. */
    public static List<Item> record(SharedPreferences p, Item add) {
        List<Item> next = upsert(load(p), add);
        save(p, next);
        return next;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
