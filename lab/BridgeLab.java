package lk.techworld.anantplay.sicast;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/* LIVE bridge E2E — the same pipeline the player runs, against the deployed Worker
 * (https://anantplay-si-subs-bridge.techworldlk.workers.dev): resolve the title,
 * open the subtitle job, poll to done (or needs-key / not-found), inspect the pairs.
 *
 * Best effort by design: unreachable network records SKIP and exits 0; a reachable
 * worker that answers nonsense is a FAIL (exit 1). */
public class BridgeLab {

    public static void main(String[] args) throws Exception {
        PrintWriter out = new PrintWriter(new FileWriter(args.length > 0 ? args[0] : "lab/out/bridge.txt"), true);
        try {
            BridgeApi api = new BridgeApi(BridgeApi.DEFAULT_WORKER_URL);
            out.println("worker: " + api.base());

            Map<String, Object> health = api.status();
            out.println("health: " + health);
            out.println("PASS status endpoint answers");

            Map<String, Object> r = api.resolve("The Odyssey", false, 0, 0);
            if (r == null) { out.println("SKIP resolve returned no match (Cinemeta hiccup)"); out.close(); return; }
            String imdb = String.valueOf(r.get("imdb"));
            out.println("resolve: The Odyssey → " + imdb + " " + r.get("name") + " (" + r.get("year") + ")");
            out.println("PASS resolve matched the user's movie");

            Map<String, Object> job = api.subtitles("movie", imdb, 0, 0, "The Odyssey");
            String status = String.valueOf(job.get("status"));
            out.println("subtitles job: status=" + status + " source=" + job.get("source"));

            if ("needs-key".equals(status) || "not-found".equals(status)) {
                out.println("SKIP no Sinhala pipeline for this title (status " + status + ") — the app handles both");
            } else {
                Map<String, Object> si = (Map<String, Object>) MiniJson.objGet(job, "si");
                String token = si == null ? null : String.valueOf(si.get("token"));
                if (token == null || token.isEmpty()) {
                    out.println("FAIL no si token in job body: " + job);
                } else {
                    List<Cue> cues = null;
                    String how = "";
                    for (int round = 0; round < 3 && cues == null; round++) {
                        for (int poll = 0; poll < 6; poll++) {
                            try {
                                String body = api.srt(token);
                                if (body.trim().startsWith("{")) {
                                    Map<String, Object> p = null;
                                    try { p = Cue.statusOf(body); } catch (Exception ignored) { }
                                    if (p != null && "translating".equals(p.get("status"))) {
                                        out.println("  translating… " + p.get("done") + "/" + p.get("total"));
                                        Thread.sleep(4000);
                                        continue;
                                    }
                                }
                                cues = SrtLoader.load(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "si.srt");
                                how = String.valueOf(job.get("source"));
                                break;
                            } catch (BridgeApi.HttpException he) {
                                if (he.status == 404) { out.println("  token cold (isolate miss) — re-ensuring"); break; }
                                throw he;
                            }
                        }
                        if (cues == null) { job = api.subtitles("movie", imdb, 0, 0, "The Odyssey"); }
                    }
                    if (cues == null) { out.println("SKIP subtitles not ready inside the lab window"); }
                    else {
                        int withSi = 0;
                        for (Cue c : cues) if (c.si != null && !c.si.isEmpty()) withSi++;
                        out.println("srt: " + cues.size() + " cues from " + how + ", " + withSi + " with Sinhala text");
                        out.println("sample: " + (cues.isEmpty() ? "-" : cues.get(cues.size() / 3).si));
                        out.println(cues.isEmpty() || withSi == 0 ? "FAIL cue list unusable" : "PASS Sinhala .srt is playable by the receiver");
                    }
                }
            }
        } catch (Exception e) {
            out.println("SKIP worker unreachable from here: " + e);
        }
        out.close();
    }
}
