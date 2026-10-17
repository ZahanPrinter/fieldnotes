import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.flytbase.copilot.core.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Runs the real agent + real tools + real OllamaClient HTTP path against a scripted fake Ollama. */
public class AgentTest {
  static int fails = 0;
  static void check(String n, boolean ok, Object info) { if (!ok) fails++; System.out.println((ok ? "PASS " : "FAIL ") + n + (ok ? "" : " -> " + info)); }

  static HttpServer fake(Function<JsonNode, String> script) throws Exception {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/api/chat", ex -> {
      JsonNode req = Dataset.M.readTree(ex.getRequestBody());
      byte[] out = script.apply(req).getBytes();
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close();
    });
    s.start(); return s;
  }
  static String call(String name, String argsJson) { return "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}}]}}"; }
  static String say(String t) { return "{\"message\":{\"role\":\"assistant\",\"content\":" + Dataset.M.valueToTree(t) + "}}"; }
  static int toolMsgs(JsonNode req) { int n = 0; for (JsonNode m : req.get("messages")) if ("tool".equals(m.path("role").asText())) n++; return n; }
  static String lastTool(JsonNode req) { String s = ""; for (JsonNode m : req.get("messages")) if ("tool".equals(m.path("role").asText())) s = m.path("content").asText(); return s; }

  public static void main(String[] a) throws Exception {
    try { run(a); } catch (Throwable e) { e.printStackTrace(); System.exit(2); }
  }
  static void run(String[] a) throws Exception {
    Dataset ds = Dataset.load(Path.of(a[0]));
    Domain dom = new Domain(ds, new Overlay());
    Tools tools = new Tools(dom, new DocIndex(ds), LocalDate.of(2026, 10, 17));
    System.out.println("tools=" + tools.tools.keySet() + " docChunks=" + new DocIndex(ds).size());

    // 1) happy path: tool call -> grounded answer + bar visual + citations
    HttpServer s1 = fake(req -> toolMsgs(req) == 0
        ? call("downtime", "{\"area\":\"Northeast Block 7\",\"from\":\"2026-06-16\",\"to\":\"2026-10-16\",\"top_n\":3}")
        : say("WELL-0121 had the most downtime in the window: 49 days."));
    Agent ag = new Agent(new OllamaClient("http://127.0.0.1:" + s1.getAddress().getPort(), "gemma4:e4b", 16384), tools, ds);
    Agent.Answer r = ag.run("U-201", "", List.of(), "Which wells in Northeast Block 7 had the most downtime in the last 4 months?");
    check("happy: grounded", r.grounded(), r);
    check("happy: visual is bar", r.visual() != null && r.visual().type().equals("bar") && r.visual().rows().size() == 3, r.visual());
    check("happy: cites inspection ids", r.citations().contains("WELL-0121"), r.citations());
    check("happy: top row 49 days", r.visual().rows().get(0).get(1).toString().equals("49"), r.visual().rows());
    s1.stop(0);

    // 2) hallucinated number -> gate forces a rewrite
    int[] n = {0};
    HttpServer s2 = fake(req -> { n[0]++; int t = toolMsgs(req);
      if (t == 0) return call("downtime", "{\"well_id\":\"WELL-0121\",\"from\":\"2026-06-16\",\"to\":\"2026-10-16\"}");
      boolean retried = req.toString().contains("not found in the tool results");
      return say(retried ? "WELL-0121 was down 49 days." : "WELL-0121 was down 4999 days."); });
    ag = new Agent(new OllamaClient("http://127.0.0.1:" + s2.getAddress().getPort(), "gemma4:e4b", 16384), tools, ds);
    r = ag.run("U-201", "", List.of(), "How long was WELL-0121 down?");
    check("gate: hallucination replaced", r.text().contains("49") && !r.text().contains("4999") && r.grounded(), r.text());
    s2.stop(0);

    // 3) answering with facts but no tool call -> nudged to call tools
    HttpServer s3 = fake(req -> toolMsgs(req) == 0 && !req.toString().contains("without calling any tool")
        ? say("WELL-0001 is producing at 80 degrees.")
        : toolMsgs(req) == 0 ? call("well_status", "{\"well_id\":\"WELL-0001\"}") : say("WELL-0001 is not producing; delta 3.2 C. [INS-0001-0203]"));
    ag = new Agent(new OllamaClient("http://127.0.0.1:" + s3.getAddress().getPort(), "gemma4:e4b", 16384), tools, ds);
    r = ag.run("U-201", "", List.of(), "Is WELL-0001 producing?");
    check("nudge: forced tool use, answer grounded", r.text().contains("not producing") && r.grounded() && r.visual().type().equals("card"), r);
    s3.stop(0);

    // 4) tool errors are returned to the model so it can recover
    HttpServer s4 = fake(req -> toolMsgs(req) == 0 ? call("well_status", "{\"well_id\":\"WELL-9999\"}")
        : lastTool(req).contains("Unknown well") ? say("That well does not exist in the dataset.") : say("?"));
    ag = new Agent(new OllamaClient("http://127.0.0.1:" + s4.getAddress().getPort(), "gemma4:e4b", 16384), tools, ds);
    r = ag.run("U-201", "", List.of(), "status of WELL-9999");
    check("error recovery", r.text().contains("does not exist"), r.text());
    s4.stop(0);

    // 5) correction + conflict tools directly (what the model would call)
    Tools.Ctx mt = new Tools.Ctx("U-201");
    var c1 = tools.tools.get("record_correction").fn().call(Dataset.M.readTree("{\"inspection_id\":\"INS-0164-0205\",\"field\":\"wellhead_temp_c\",\"new_value\":41.0,\"reason\":\"faulty thermal gun\"}"), mt);
    check("correction: flips to producing, keeps original, flags inspector note", c1.data().get("producing_after").asBoolean() && c1.data().get("original_value").asText().equals("24.0") && c1.data().has("inspector_note_on_same_inspection"), c1.data());
    var st = tools.tools.get("well_status").fn().call(Dataset.M.readTree("{\"area\":\"Northeast Block 8\",\"only_not_producing\":true}"), new Tools.Ctx("U-202"));
    check("correction visible to another user (3 wells)", st.data().get("row_count").asInt() == 3, st.data());
    var c2 = tools.tools.get("record_note").fn().call(Dataset.M.readTree("{\"scope\":\"WELL-0175\",\"text\":\"WELL-0175 is running fine\"}"), new Tools.Ctx("U-202"));
    check("note: carries current data so model can flag conflict", c2.data().get("current_data_producing").asBoolean() == false && c2.data().get("status").asText().equals("unconfirmed"), c2.data());
    var sd = tools.tools.get("search_documents").fn().call(Dataset.M.readTree("{\"query\":\"written notice within 24 hours reportable spill\"}"), mt);
    check("doc search finds spill SOP", sd.data().toString().contains("spill-response-sop"), sd.data().toString().substring(0, 200));
    var sp = tools.tools.get("spills").fn().call(Dataset.M.readTree("{\"area\":\"Northeast Block 8\",\"from\":\"2026-07-19\",\"to\":\"2026-10-17\"}"), mt);
    check("spills include coordinates for map", sp.visual().columns().contains("lat"), sp.visual().columns());

    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED"); System.exit(fails == 0 ? 0 : 1);
  }
}
