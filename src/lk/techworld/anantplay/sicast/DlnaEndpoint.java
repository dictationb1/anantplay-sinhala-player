package lk.techworld.anantplay.sicast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/* v3.0.0 — THE UPnP/DLNA MEDIARENDERER CORE (pure Java — zero Android imports,
 * driven end-to-end by the verification lab on a bare JVM).
 *
 * WHY THIS EXISTS — the SECOND, NsdManager-INDEPENDENT cast path (Mystery 3 of
 * the 2026-11-22 15:15 Android 16 log): official AnantPlay's cast discovery is
 * not mDNS-only. Its Cast.discover() ALSO fires Dlna.search() (AnantPlay
 * src/me/champdev/weblock/Cast.java:293: "DLNA renderers answer an SSDP search,
 * not mDNS"), and every DLNA renderer found counts toward Cast.any()
 * (Cast.java:290: !tvs.isEmpty() || !chromecasts.isEmpty() || !renderers.
 * isEmpty()) — so the Cast chip in PlayerActivity lights up from the DLNA list
 * alone, with ZERO involvement of NsdManager/MdnsAdvertiser (the exact stack
 * that hung on the Xiaomi Android 16 field device).
 *
 * THE PROTOCOL, mirrored line-for-line from official AnantPlay's Dlna.java
 * (fetched from github.com/AnantPlay/android, src/me/champdev/weblock/, read-only
 * research — NEVER a fork):
 *
 *   · SSDP M-SEARCH (Dlna.java:39-63): "M-SEARCH * HTTP/1.1", multicast to
 *     239.255.255.250:1900, "MAN: \"ssdp:discover\"", "MX: 2", and the exact
 *     search target ST: urn:schemas-upnp-org:device:MediaRenderer:1 — sent 3×
 *     (0 s / 1 s / 2 s), reading replies for ~4 s from a PLAIN unicast socket
 *     with an ephemeral source port. ONLY the LOCATION header of a 200-OK
 *     reply is read (Dlna.java:65-71: header(text, "LOCATION")); replies are
 *     deduped by LOCATION. A UNICAST reply to the searcher's ephemeral port is
 *     the only delivery that matters — no 5353-style port stealing can occur.
 *
 *   · Device description (Dlna.java:74-88): GET the LOCATION URL, regex-parse
 *     friendlyName + every <service> block's serviceType + controlURL; the
 *     renderer is accepted ONLY when an AVTransport serviceType exists
 *     (RenderingControl optional — "rc" may stay ""). URLBase is honoured but
 *     absent is fine (absolute controlURLs).
 *
 *   · SOAP control (Dlna.java:119-253): POST controlURL with SOAPAction
 *     "<urn>#<Action>", Content-Type text/xml. Playback = Stop (ignored on
 *     failure) → SetAVTransportURI(CurrentURI + DIDL-Lite CurrentURIMetaData)
 *     → Play. Remote = GetTransportInfo (CurrentTransportState), GetPositionInfo
 *     (TrackDuration + RelTime, "H:MM:SS" — ms() regex \d+:\d{1,2}:\d{1,2}),
 *     Pause/Play toggle, Seek(Unit=REL_TIME, Target=H:MM:SS), Stop, and
 *     RenderingControl Get/SetVolume(Channel=Master) when an RC controlURL
 *     exists. HTTP >= 400 → the cast fails with cast_dlna_refused.
 *
 * The player bridge (open/act/status/volume) is the SAME pipeline the AnantPlay
 * mDNS cast receiver uses (/play, /do, /status) — one engine, two transports. */
public final class DlnaEndpoint {

    /** The UPnP service URNs AnantPlay's soap() sends (Dlna.java:34). */
    public static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    public static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    /** The exact ST AnantPlay searches for (Dlna.java:42). */
    public static final String ST_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1";
    public static final String SSDP_GROUP = "239.255.255.250";
    public static final int SSDP_PORT = 1900;
    /** UPnP 1.0: NOTIFY/M-SEARCH multicast packets travel with IP TTL 4. */
    public static final int SSDP_TTL = 4;
    public static final int SSDP_MAX_AGE = 1800;

    /** What the endpoint needs from its host: the SAME operations the mDNS
     *  cast receiver exposes, so DLNA and AnantPlay-cast share one engine. */
    public interface Player {
        /** SetAVTransportURI → the /play pipeline (opens the Sinhala player).
         *  May block a few seconds; true when the player took the URI. */
        boolean open(String url, String title);
        /** toggle | seek | stop — the exact /do whitelist. Returns the /status JSON. */
        String act(String action, long toMs);
        /** The current /status JSON ({"title","pos","dur","playing","live"} |
         *  {"loading":true} | {"idle":true}) — never blocks. */
        String statusJson();
        /** RenderingControl Master volume, 0..100. */
        int getVolume();
        void setVolume(int volume);
    }

    /** A full HTTP response (the receiver server writes it verbatim). */
    public static final class Response {
        public final int code;
        public final String contentType;
        public final String body;
        Response(int code, String contentType, String body) {
            this.code = code; this.contentType = contentType; this.body = body;
        }
        public static Response ok(String contentType, String body) { return new Response(200, contentType, body); }
        public static Response fault(int code, String description) {
            return new Response(code, "text/xml; charset=utf-8", soapFault(description));
        }
    }

    private final Player player;
    private final String uuid;          // stable per install (e.g. "SIcast-1a2b3c4d")
    private final String friendlyName;  // what AnantPlay's cast list shows ("සිංහල Player - DLNA")
    private final String serverString;  // the SSDP SERVER: header
    private final Random random = new Random();
    /** SetAVTransportURI state (GetPositionInfo's TrackURI / TrackMetaData). */
    private volatile String currentUri = "";
    private volatile String currentTitle = "";
    /** RenderingControl's tracked volume (0..100, AnantPlay ±5 steps around it). */
    private volatile int volume = 50;

    public DlnaEndpoint(Player player, String uuid, String friendlyName, String serverString) {
        this.player = player;
        this.uuid = uuid == null ? "SIcast-0000" : uuid;
        this.friendlyName = friendlyName == null ? "Sinhala Player" : friendlyName;
        this.serverString = serverString == null ? "UPnP/1.0 AnantPlaySinhalaPlayer/3.1" : serverString;
    }

    public String uuid() { return uuid; }
    public String friendlyName() { return friendlyName; }
    public String currentUri() { return currentUri; }
    public String currentTitle() { return currentTitle; }

    // ── the HTTP surface (routed by ReceiverServer under /dlna/*) ───────────────

    /** Handle one HTTP request. path is the request path; soapAction is the
     *  SOAPAction header (may be null); body is the (possibly empty) request
     *  body. Returns null when the path is not ours (→ 404). */
    public Response handle(String path, String method, String soapAction, String body, String baseUrl) {
        if (path == null) return null;
        if (path.equals("/dlna/desc.xml")) {
            return Response.ok("text/xml; charset=utf-8", descXml(baseUrl));
        }
        if (path.equals("/dlna/avt.xml")) return Response.ok("text/xml; charset=utf-8", avtScpd());
        if (path.equals("/dlna/rc.xml")) return Response.ok("text/xml; charset=utf-8", rcScpd());
        if (path.equals("/dlna/avt") && "POST".equals(method))
            return soap(AVT, actionOf(soapAction, body), body);
        if (path.equals("/dlna/rc") && "POST".equals(method))
            return soap(RC, actionOf(soapAction, body), body);
        return null;
    }

    // ── the SOAP state machine ─────────────────────────────────────────────────

    private Response soap(String service, String action, String bodyXml) {
        if (action == null || action.isEmpty())
            return Response.fault(400, "no action (SOAPAction header missing?)");
        try {
            if (AVT.equals(service)) {
                if ("SetAVTransportURI".equals(action)) return setAvTransportUri(bodyXml);
                if ("Play".equals(action)) {
                    if (!playing(statusMap())) player.act("toggle", 0);   // pause → play (a fresh URI is already playing)
                    return soapOk(AVT, "Play", "");
                }
                if ("Pause".equals(action)) {
                    if (playing(statusMap())) player.act("toggle", 0);
                    return soapOk(AVT, "Pause", "");
                }
                if ("Stop".equals(action)) {
                    player.act("stop", 0);
                    currentUri = "";
                    return soapOk(AVT, "Stop", "");
                }
                if ("Seek".equals(action)) {
                    long ms = hmsToMs(arg(bodyXml, "Target"));
                    player.act("seek", ms);
                    return soapOk(AVT, "Seek", "<Unit>REL_TIME</Unit>");
                }
                if ("GetTransportInfo".equals(action)) {
                    Map<String, Object> st = statusMap();
                    return soapOk(AVT, "GetTransportInfo",
                        "<CurrentTransportState>" + transportState(st) + "</CurrentTransportState>"
                        + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                        + "<CurrentSpeed>1</CurrentSpeed>");
                }
                if ("GetPositionInfo".equals(action)) {
                    Map<String, Object> st = statusMap();
                    boolean live = Boolean.TRUE.equals(st.get("live"));
                    String dur = live ? "NOT_IMPLEMENTED" : clock(num(st, "dur"));
                    String pos = live ? "NOT_IMPLEMENTED" : clock(num(st, "pos"));
                    String title = currentTitle == null || currentTitle.isEmpty()
                        ? String.valueOf(st.get("title")) : currentTitle;
                    return soapOk(AVT, "GetPositionInfo",
                        "<Track>1</Track>"
                        + "<TrackDuration>" + dur + "</TrackDuration>"
                        + "<TrackMetaData>" + escape(minimalDidl(title, currentUri)) + "</TrackMetaData>"
                        + "<TrackURI>" + escape(currentUri) + "</TrackURI>"
                        + "<RelTime>" + pos + "</RelTime>"
                        + "<AbsTime>" + pos + "</AbsTime>"
                        + "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>");
                }
                if ("GetMediaInfo".equals(action)) {
                    Map<String, Object> st = statusMap();
                    boolean live = Boolean.TRUE.equals(st.get("live"));
                    return soapOk(AVT, "GetMediaInfo",
                        "<NrTracks>1</NrTracks>"
                        + "<MediaDuration>" + (live ? "NOT_IMPLEMENTED" : clock(num(st, "dur"))) + "</MediaDuration>"
                        + "<CurrentURI>" + escape(currentUri) + "</CurrentURI>"
                        + "<CurrentURIMetaData></CurrentURIMetaData>"
                        + "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                        + "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                        + "<WriteStatus>NOT_WRITABLE</WriteStatus>");
                }
                if ("GetDeviceCapabilities".equals(action)) {
                    return soapOk(AVT, "GetDeviceCapabilities",
                        "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia>"
                        + "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>");
                }
                if ("GetTransportSettings".equals(action)) {
                    return soapOk(AVT, "GetTransportSettings",
                        "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>");
                }
            } else if (RC.equals(service)) {
                if ("GetVolume".equals(action))
                    return soapOk(RC, "GetVolume", "<CurrentVolume>" + player.getVolume() + "</CurrentVolume>");
                if ("SetVolume".equals(action)) {
                    int v = clampVolume(intArg(bodyXml, "DesiredVolume"));
                    player.setVolume(v);
                    return soapOk(RC, "SetVolume", "");
                }
                if ("GetMute".equals(action)) return soapOk(RC, "GetMute", "<CurrentMute>0</CurrentMute>");
                if ("SetMute".equals(action)) return soapOk(RC, "SetMute", "");
            }
            return Response.fault(500, "Unsupported action " + action + " on " + service);
        } catch (Throwable t) {
            return Response.fault(501, "renderer error: " + t);
        }
    }

    /** SetAVTransportURI → the SAME /play pipeline the AnantPlay mDNS cast uses. */
    private Response setAvTransportUri(String bodyXml) {
        String uri = arg(bodyXml, "CurrentURI");
        if (uri == null || uri.isEmpty()) return Response.fault(402, "CurrentURI missing");
        if (!CastPolicy.allowed(uri, true)) return Response.fault(402, "bad link");
        // AnantPlay sends the URL already wrapped in its own phone-side relay
        // (Dlna.start: Chromecast.Relay) — we simply play what arrives. The
        // title rides inside the DIDL-Lite metadata.
        String title = didlTitle(arg(bodyXml, "CurrentURIMetaData"));
        boolean ok = player.open(uri, title);
        if (!ok) return Response.fault(501, "the renderer did not open the URI");
        currentUri = uri;
        currentTitle = title == null ? "" : title;
        return soapOk(AVT, "SetAVTransportURI", "");
    }

    // ── SSDP: the wire strings + the M-SEARCH classifier (shared with
    //    DlnaRenderer, proven by the lab) ───────────────────────────────────────

    /** True when a UDP payload is an SSDP M-SEARCH we should answer. */
    public static boolean isMSearch(String packet) {
        return packet != null && packet.startsWith("M-SEARCH");
    }

    /** A case-insensitive SSDP/HTTP header value ("" when absent). */
    public static String ssdpHeader(String packet, String name) {
        if (packet == null) return "";
        for (String line : packet.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            if (line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return "";
    }

    /** The STs we advertise for a given search ST (ssdp:all expands to the full
     *  set; unknown STs answer nothing). */
    public static List<String> answerTargets(String st) {
        List<String> out = new ArrayList<>();
        if (st == null || st.isEmpty()) return out;
        String t = st.trim();
        if (t.equalsIgnoreCase("ssdp:all")) {
            out.add("upnp:rootdevice");
            out.add(ST_RENDERER);
            out.add(AVT);
            out.add(RC);
        } else if (t.equalsIgnoreCase(ST_RENDERER)
            || t.equalsIgnoreCase("upnp:rootdevice")
            || t.equalsIgnoreCase(AVT)
            || t.equalsIgnoreCase(RC)) {
            out.add(t);
        }
        return out;
    }

    /** The 200-OK reply to one M-SEARCH ST — the ONLY header AnantPlay reads is
     *  LOCATION (Dlna.java:65-71), the rest is UPnP-correct politeness. */
    public String searchResponse(String st, String location) {
        return "HTTP/1.1 200 OK\r\n"
            + "CACHE-CONTROL: max-age=" + SSDP_MAX_AGE + "\r\n"
            + "EXT:\r\n"
            + "LOCATION: " + location + "\r\n"
            + "SERVER: " + serverString + "\r\n"
            + "ST: " + st + "\r\n"
            + "USN: " + usnFor(st) + "\r\n"
            + "\r\n";
    }

    /** An ssdp:alive NOTIFY for one NT (multicast to the SSDP group). */
    public String aliveNotify(String nt, String location) {
        return "NOTIFY * HTTP/1.1\r\n"
            + "HOST: " + SSDP_GROUP + ":" + SSDP_PORT + "\r\n"
            + "CACHE-CONTROL: max-age=" + SSDP_MAX_AGE + "\r\n"
            + "LOCATION: " + location + "\r\n"
            + "NT: " + nt + "\r\n"
            + "NTS: ssdp:alive\r\n"
            + "SERVER: " + serverString + "\r\n"
            + "USN: " + usnFor(nt) + "\r\n"
            + "\r\n";
    }

    /** An ssdp:byebye NOTIFY for one NT (sent before the responder goes down). */
    public String byebyeNotify(String nt) {
        return "NOTIFY * HTTP/1.1\r\n"
            + "HOST: " + SSDP_GROUP + ":" + SSDP_PORT + "\r\n"
            + "NT: " + nt + "\r\n"
            + "NTS: ssdp:byebye\r\n"
            + "USN: " + usnFor(nt) + "\r\n"
            + "\r\n";
    }

    /** uuid:<uuid>::<st> — the USN form AnantPlay's searchResponse dedup expects. */
    public String usnFor(String st) {
        return "uuid:" + uuid + "::" + st;
    }

    /** The random answer delay for one M-SEARCH: 0..min(MX s, 1200 ms) — UPnP
     *  politeness (spread answers), but ALWAYS inside AnantPlay's 4 s window
     *  even for its last (2 s) search. */
    public long answerDelayMs(String packet) {
        int mx = 2;
        try { mx = Math.max(1, Integer.parseInt(ssdpHeader(packet, "MX").trim())); } catch (Throwable ignored) { }
        long cap = Math.min(mx * 1000L, 1200L);
        return cap <= 0 ? 0 : (long) (random.nextDouble() * cap);
    }

    /** The device description XML AnantPlay's describe() parses (Dlna.java:74-88):
     *  friendlyName + serviceType/controlURL per <service> block; AVTransport
     *  REQUIRED, RenderingControl advertised so vol± works too. */
    public String descXml(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl;
        StringBuilder sb = new StringBuilder(1600);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
          .append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\">\n")
          .append("  <specVersion><major>1</major><minor>0</minor></specVersion>\n")
          .append("  <device>\n")
          .append("    <deviceType>").append(ST_RENDERER).append("</deviceType>\n")
          .append("    <friendlyName>").append(escape(friendlyName)).append("</friendlyName>\n")
          .append("    <manufacturer>TechWorld LK</manufacturer>\n")
          .append("    <manufacturerURL>https://github.com/dictationb1/anantplay-sinhala-player</manufacturerURL>\n")
          .append("    <modelDescription>AnantPlay cast receiver with Sinhala subtitles</modelDescription>\n")
          .append("    <modelName>AnantPlay Sinhala Player</modelName>\n")
          .append("    <modelNumber>3.0.0</modelNumber>\n")
          .append("    <UDN>uuid:").append(uuid).append("</UDN>\n")
          .append("    <serviceList>\n");
        for (String[] svc : new String[][]{
                {AVT, "urn:upnp-org:serviceId:AVTransport", "/dlna/avt"},
                {RC, "urn:upnp-org:serviceId:RenderingControl", "/dlna/rc"}}) {
            sb.append("      <service>\n")
              .append("        <serviceType>").append(svc[0]).append("</serviceType>\n")
              .append("        <serviceId>").append(svc[1]).append("</serviceId>\n")
              .append("        <controlURL>").append(base).append(svc[2]).append("</controlURL>\n")
              .append("        <eventSubURL>").append(base).append(svc[2]).append("</eventSubURL>\n")
              .append("        <SCPDURL>").append(base).append(svc[2]).append(".xml</SCPDURL>\n")
              .append("      </service>\n");
        }
        sb.append("    </serviceList>\n")
          .append("  </device>\n")
          .append("</root>\n");
        return sb.toString();
    }

    /** A minimal-but-valid AVTransport SCPD (fetched by generic control points;
     *  AnantPlay never reads it). */
    public static String avtScpd() {
        StringBuilder sb = new StringBuilder(1200);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
          .append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n")
          .append("  <specVersion><major>1</major><minor>0</minor></specVersion>\n")
          .append("  <actionList>\n");
        String[][] actions = {
            {"SetAVTransportURI", "InstanceID|CurrentURI|CurrentURIMetaData"},
            {"Play", "InstanceID|Speed"},
            {"Pause", "InstanceID"},
            {"Stop", "InstanceID"},
            {"Seek", "InstanceID|Unit|Target"},
            {"GetTransportInfo", "InstanceID"},
            {"GetPositionInfo", "InstanceID"},
            {"GetMediaInfo", "InstanceID"},
        };
        for (String[] a : actions) {
            sb.append("    <action>\n      <name>").append(a[0]).append("</name>\n      <argumentList>\n");
            for (String argName : a[1].split("\\|")) {
                // minimal SCPD: declare every argument in-bound (AnantPlay never
                // fetches this file; generic stacks only need it well-formed)
                sb.append("        <argument><name>").append(argName).append("</name>")
                  .append("<direction>in</direction><relatedStateVariable>A_ARG_TYPE_").append(argName)
                  .append("</relatedStateVariable></argument>\n");
            }
            sb.append("      </argumentList>\n    </action>\n");
        }
        sb.append("  </actionList>\n</scpd>\n");
        return sb.toString();
    }

    /** A minimal RenderingControl SCPD. */
    public static String rcScpd() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\n"
            + "  <specVersion><major>1</major><minor>0</minor></specVersion>\n"
            + "  <actionList>\n"
            + "    <action><name>GetVolume</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>"
            + "<argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "    <action><name>SetVolume</name><argumentList>"
            + "<argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>"
            + "<argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>"
            + "<argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>"
            + "</argumentList></action>\n"
            + "  </actionList>\n</scpd>\n";
    }

    // ── SOAP string helpers (pure, lab-proven) ────────────────────────────────

    /** Extract the action from the SOAPAction header ("<urn>#Action>") or, when
     *  the header is missing, from the body's <u:Action element. */
    public static String actionOf(String soapAction, String bodyXml) {
        if (soapAction != null) {
            String s = soapAction.trim();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
            int h = s.lastIndexOf('#');
            if (h >= 0 && h + 1 < s.length()) return s.substring(h + 1).trim();
        }
        if (bodyXml != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<(?:\\w+:)?(\\w+)\\s+xmlns:u=").matcher(bodyXml);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    /** One XML text element's UNESCAPED content (handles the five XML entities).
     *  AnantPlay builds args with the same tag names we read here. */
    public static String arg(String xml, String name) {
        if (xml == null || name == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("<(?:\\w+:)?" + java.util.regex.Pattern.quote(name) + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + java.util.regex.Pattern.quote(name) + ">",
                java.util.regex.Pattern.DOTALL).matcher(xml);
        if (!m.find()) return null;
        return unescape(m.group(1).trim());
    }

    /** The <dc:title> inside a DIDL-Lite CurrentURIMetaData blob. */
    public static String didlTitle(String didl) {
        String t = arg(didl, "dc:title");
        if (t == null) t = arg(didl, "title");
        return t == null || t.isEmpty() ? null : t;
    }

    /** XML-escape a text node (AnantPlay's escape() writes the same five). */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** XML-unescape (&amp; &lt; &gt; &quot; &apos;). */
    public static String unescape(String s) {
        if (s == null) return null;
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&");
    }

    /** "H:MM:SS" (AnantPlay's own clock shape; its ms() regex demands \d+:\d{1,2}:\d{1,2}). */
    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s / 3600 + ":" + String.format(Locale.US, "%02d:%02d", s / 60 % 60, s % 60);
    }

    /** "H:MM:SS" → ms (0 when unparsable). */
    public static long hmsToMs(String hms) {
        if (hms == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d+))?").matcher(hms.trim());
        if (!m.find()) return 0;
        try {
            long h = Long.parseLong(m.group(1)), mi = Long.parseLong(m.group(2)), se = Long.parseLong(m.group(3));
            return h * 3600_000L + mi * 60_000L + se * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static int clampVolume(int v) { return Math.max(0, Math.min(100, v)); }

    private static int intArg(String xml, String name) {
        String v = arg(xml, name);
        try { return v == null ? 0 : (int) Math.round(Double.parseDouble(v.trim())); }
        catch (NumberFormatException e) { return 0; }
    }

    /** The /status JSON → a map ({idle}/{loading} shapes stay empty-keyed). */
    private Map<String, Object> statusMap() {
        try {
            Map<String, Object> m = Cue.statusOf(player.statusJson());
            return m == null ? new LinkedHashMap<String, Object>() : m;
        } catch (Throwable t) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private static boolean playing(Map<String, Object> status) {
        // idle/loading have no "playing" key → not playing (a Play action then toggles)
        return Boolean.TRUE.equals(status.get("playing"));
    }

    /** The UPnP transport state AnantPlay's remote renders (Dlna.java:218-221). */
    public static String transportState(Map<String, Object> status) {
        if (status.containsKey("idle")) return "NO_MEDIA_PRESENT";
        if (status.containsKey("loading")) return "TRANSITIONING";
        if (Boolean.TRUE.equals(status.get("playing"))) return "PLAYING";
        if (status.containsKey("playing")) return "PAUSED_PLAYBACK";
        return "NO_MEDIA_PRESENT";
    }

    private static long num(Map<String, Object> m, String key) {
        Number n = MiniJson.num(m, key);
        return n == null ? 0 : n.longValue();
    }

    /** A successful SOAP response envelope wrapped as an HTTP 200 Response. */
    private static Response soapOk(String serviceUrn, String action, String argsXml) {
        return Response.ok("text/xml; charset=utf-8", soapEnvelopeOk(serviceUrn, action, argsXml));
    }

    /** A successful SOAP response envelope (AnantPlay's soap() only checks the
     *  HTTP code and regex-reads the answer fields). */
    public static String soapEnvelopeOk(String serviceUrn, String action, String argsXml) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
            + "<s:Body><u:" + action + " xmlns:u=\"" + serviceUrn + "\">" + argsXml + "</u:" + action + ">"
            + "</s:Body></s:Envelope>";
    }

    /** A SOAP fault envelope (AnantPlay surfaces errorDescription on HTTP >= 400). */
    public static String soapFault(String description) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
            + "<s:Body><s:Fault>"
            + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
            + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
            + "<errorCode>501</errorCode><errorDescription>" + escape(description) + "</errorDescription>"
            + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    /** The DIDL-Lite AnantPlay itself sends (Dlna.java:143-148) — mirrored for
     *  our GetPositionInfo TrackMetaData so generic control points stay happy. */
    public static String minimalDidl(String title, String uri) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
            + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
            + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
            + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
            + "<dc:title>" + escape(title) + "</dc:title>"
            + "<upnp:class>object.item.videoItem</upnp:class>"
            + "<res protocolInfo=\"http-get:*:*:*\">" + escape(uri) + "</res>"
            + "</item></DIDL-Lite>";
    }
}
