package lk.techworld.anantplay.sicast;

import java.util.LinkedHashMap;
import java.util.Map;

/* v3.1.0 — SETTINGS BACKUP & RESTORE ("Yours to Keep" — the Mornye pattern,
 * github.com/mornyeapp/Mornye lib/screens/settings/backup_restore_page.dart:
 * toJson()/fromJson() round-trip with SELECTION and VALIDATION, translated to
 * pure Java over the existing MiniJson).
 *
 * The export is ONE clean, human-readable JSON document:
 *   { "app": "AnantPlay Sinhala Player",
 *     "schema": 1,
 *     "exportedAt": 1732278000000,
 *     "version": "3.1.0",
 *     "settings": { "themeMode": "amoled", "accent": 0, "fontSp": 26, … } }
 *
 * The import is STRICT — a corrupted or hand-edited file can never poison the
 * app: every key must be a known AppPrefs key, every value passes its
 * normalizer, the schema must be exactly the supported one, and any parse
 * error, wrong app, wrong schema, missing settings object or empty-after-
 * validation map is a named rejection with a bilingual error the UI shows.
 * Pure Java — the lab round-trips the whole model and proves every rejection. */
public final class SettingsBackup {

    public static final int SCHEMA = 1;
    public static final String APP = "AnantPlay Sinhala Player";

    private SettingsBackup() { }

    /** Build the export document (settings must already be normalized). */
    public static String export(String appVersion, long exportedAtMs, Map<String, Object> settings) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("app", APP);
        doc.put("schema", SCHEMA);
        doc.put("exportedAt", exportedAtMs);
        doc.put("version", appVersion == null ? "" : appVersion);
        Map<String, Object> clean = new LinkedHashMap<>();
        if (settings != null) {
            for (String k : AppPrefs.BACKUP_KEYS) {
                if (settings.containsKey(k)) clean.put(k, settings.get(k));
            }
        }
        doc.put("settings", clean);
        return writeDoc(doc);
    }

    /** MiniJson.write is FLAT by design (protocol safety); the backup document
     *  is the ONE place that needs a nested object — this tiny writer handles
     *  Map/Boolean/Number/String/List, ordered and human-readable. */
    static String writeDoc(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(MiniJson.quote(e.getKey())).append(':');
            appendValue(sb, e.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean || v instanceof Number) sb.append(String.valueOf(v));
        else if (v instanceof Map) appendValue(sb, (Map<String, Object>) v);
        else if (v instanceof java.util.List) {
            sb.append('[');
            boolean f2 = true;
            for (Object x : (java.util.List<?>) v) {
                if (!f2) sb.append(',');
                f2 = false;
                if (x instanceof Boolean || x instanceof Number) sb.append(String.valueOf(x));
                else sb.append(MiniJson.quote(String.valueOf(x)));
            }
            sb.append(']');
        } else sb.append(MiniJson.quote(String.valueOf(v)));
    }

    private static void appendValue(StringBuilder sb, Map<String, Object> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(MiniJson.quote(e.getKey())).append(':');
            appendValue(sb, e.getValue());
        }
        sb.append('}');
    }

    /** The import outcome: ok + the validated values, or ok=false + a named
     *  bilingual error. `rejected` counts unknown keys / unparsable values that
     *  were dropped (informational — the rest can still import). */
    public static final class Import {
        public boolean ok;
        public String error = "";
        public String errorSi = "";
        public Map<String, Object> values = new LinkedHashMap<>();
        public int rejected;
    }

    /** Parse + STRICTLY validate an import document. Never throws. */
    public static Import parseImport(String json) {
        Import r = new Import();
        if (json == null || json.trim().isEmpty()) {
            r.error = "empty file";
            r.errorSi = "හිස් ගොනුවකි";
            return r;
        }
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (Throwable t) {
            root = null;
        }
        if (!(root instanceof Map)) {
            r.error = "not valid JSON";
            r.errorSi = "වලංගු JSON එකක් නොවේ";
            return r;
        }
        String app = MiniJson.str(root, "app");
        if (!APP.equals(app)) {
            r.error = "not an AnantPlay Sinhala Player backup (app=\"" + app + "\")";
            r.errorSi = "මෙය AnantPlay සිංහල Player backup එකක් නොවේ";
            return r;
        }
        Number schema = MiniJson.num(root, "schema");
        if (schema == null || schema.intValue() != SCHEMA) {
            r.error = "unsupported schema " + (schema == null ? "?" : schema.intValue()) + " (this app reads " + SCHEMA + ")";
            r.errorSi = "මෙම යෙදුම නොහඳුනන schema එකකි";
            return r;
        }
        Object settingsObj = MiniJson.objGet(root, "settings");
        if (!(settingsObj instanceof Map)) {
            r.error = "no settings object in the backup";
            r.errorSi = "backup එකේ settings කොටස නැත";
            return r;
        }
        Map<?, ?> raw = (Map<?, ?>) settingsObj;
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object v = AppPrefs.normalizeValue(key, e.getValue());
            if (v == null) r.rejected++;                      // unknown key or bad value
            else r.values.put(key, v);
        }
        if (r.values.isEmpty()) {
            r.error = "no valid settings found in the backup";
            r.errorSi = "backup එකේ වලංගු setting කිසිවක් නැත";
            return r;
        }
        r.ok = true;
        r.error = r.values.size() + " settings validated" + (r.rejected > 0 ? " (" + r.rejected + " rejected)" : "");
        r.errorSi = "setting " + r.values.size() + "ක් වලංගුයි";
        return r;
    }
}
