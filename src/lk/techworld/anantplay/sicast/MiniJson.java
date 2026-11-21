package lk.techworld.anantplay.sicast;

/* A minimal JSON parser for the bridge's compact payloads — plain Java, zero Android
 * dependencies, so the SAME class runs inside the app and inside the verification lab
 * (lab/verify_companion_sync.java) on a bare JDK.
 *
 * Supports everything the worker emits: objects, arrays, strings (with quote, backslash,
 * slash, b/f/n/r/t and backslash-u-XXXX escapes), numbers, true/false/null. Not a
 * general-purpose library — the worker's shapes are known — but defensive against
 * malformed input (throws IllegalArgumentException with a position, never returns
 * half-parsed data). */
public final class MiniJson {

    /** Nesting cap: a hostile 100k-deep body must not StackOverflow the receiver thread. */
    static final int MAX_DEPTH = 64;

    private final String src;
    private int at;
    private int depth;

    private MiniJson(String src) {
        this.src = src == null ? "" : src;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.at != p.src.length()) throw p.err("trailing characters");
        return v;
    }

    // ---- typed accessors (null/absent tolerant) ----

    public static String str(Object o, String key) {
        Object v = objGet(o, key);
        return v instanceof String ? (String) v : null;
    }

    public static Number num(Object o, String key) {
        Object v = objGet(o, key);
        return v instanceof Number ? (Number) v : null;
    }

    public static long lng(Object o, String key, long dflt) {
        Number n = num(o, key);
        return n == null ? dflt : n.longValue();
    }

    public static Object objGet(Object o, String key) {
        if (!(o instanceof java.util.Map)) return null;
        Object v = ((java.util.Map<?, ?>) o).get(key);
        return v;
    }

    public static java.util.List<Object> arr(Object o) {
        if (o instanceof java.util.List) return (java.util.List<Object>) o;
        return null;
    }

    // ---- parser ----

    private Object value() {
        if (at >= src.length()) throw err("unexpected end");
        char c = src.charAt(at);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("unexpected '" + c + "'");
        }
    }

    private java.util.Map<String, Object> object() {
        if (++depth > MAX_DEPTH) throw err("nesting too deep");
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        at++; // {
        ws();
        if (peek() == '}') { at++; depth--; return m; }
        for (;;) {
            ws();
            if (peek() != '"') throw err("expected object key");
            String key = string();
            ws();
            if (peek() != ':') throw err("expected ':'");
            at++;
            ws();
            m.put(key, value());
            ws();
            char c = peek();
            if (c == ',') { at++; continue; }
            if (c == '}') { at++; depth--; return m; }
            throw err("expected ',' or '}'");
        }
    }

    private java.util.List<Object> array() {
        if (++depth > MAX_DEPTH) throw err("nesting too deep");
        java.util.ArrayList<Object> l = new java.util.ArrayList<>();
        at++; // [
        ws();
        if (peek() == ']') { at++; depth--; return l; }
        for (;;) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { at++; continue; }
            if (c == ']') { at++; depth--; return l; }
            throw err("expected ',' or ']'");
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        at++; // "
        for (;;) {
            if (at >= src.length()) throw err("unterminated string");
            char c = src.charAt(at++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (at >= src.length()) throw err("bad escape");
                char e = src.charAt(at++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (at + 4 > src.length()) throw err("bad \\u escape");
                        try {
                            sb.append((char) Integer.parseInt(src.substring(at, at + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err("bad \\u escape");
                        }
                        at += 4;
                        break;
                    default: throw err("bad escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Number number() {
        int start = at;
        if (peek() == '-') at++;
        while (at < src.length() && Character.digit(src.charAt(at), 10) >= 0) at++;
        boolean dbl = false;
        if (at < src.length() && src.charAt(at) == '.') {
            dbl = true;
            at++;
            while (at < src.length() && Character.digit(src.charAt(at), 10) >= 0) at++;
        }
        if (at < src.length() && (src.charAt(at) == 'e' || src.charAt(at) == 'E')) {
            dbl = true;
            at++;
            if (at < src.length() && (src.charAt(at) == '+' || src.charAt(at) == '-')) at++;
            while (at < src.length() && Character.digit(src.charAt(at), 10) >= 0) at++;
        }
        String t = src.substring(start, at);
        if (t.isEmpty() || t.equals("-")) throw err("bad number");
        try {
            return dbl ? (Number) Double.parseDouble(t) : (Number) Long.parseLong(t);
        } catch (NumberFormatException ex) {
            throw err("bad number '" + t + "'");
        }
    }

    private void expect(String word) {
        if (!src.startsWith(word, at)) throw err("expected '" + word + "'");
        at += word.length();
    }

    private char peek() {
        if (at >= src.length()) throw err("unexpected end");
        return src.charAt(at);
    }

    private void ws() {
        while (at < src.length()) {
            char c = src.charAt(at);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') at++;
            else break;
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("JSON error at " + at + ": " + msg);
    }

    // ---- writing (the receiver's responses) ----

    /** JSON-escape one string (no surrounding quotes). */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** A quoted JSON string. */
    public static String quote(String s) { return "\"" + escape(s == null ? "" : s) + "\""; }

    /** A flat Map (String/Number/Boolean/List values) → JSON text. */
    public static String write(java.util.Map<String, Object> m) {
        StringBuilder sb = new StringBuilder(64);
        sb.append('{');
        boolean first = true;
        for (java.util.Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Boolean || v instanceof Number) sb.append(String.valueOf(v));
            else if (v instanceof java.util.List) {
                sb.append('[');
                boolean f2 = true;
                for (Object x : (java.util.List<?>) v) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    if (x instanceof Boolean || x instanceof Number) sb.append(String.valueOf(x));
                    else sb.append(quote(String.valueOf(x)));
                }
                sb.append(']');
            } else sb.append(quote(String.valueOf(v)));
        }
        sb.append('}');
        return sb.toString();
    }
}
