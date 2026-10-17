package com.flytbase.copilot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/**
 * Tool-calling agent loop with a grounding gate. The model decides which tools to call; tools compute
 * everything; the gate rejects answers that contain figures not present in tool output.
 */
public final class Agent {
  public interface Llm { JsonNode chat(ArrayNode messages, ArrayNode tools) throws Exception; }
  public record Answer(String text, List<String> citations, Tools.Visual visual, List<String> trace, List<String> wells, boolean grounded) {}

  private static final int MAX_STEPS = 12, MAX_TOOL_CHARS = 9000;
  private static final Pattern NUM = Pattern.compile("(?<![A-Za-z])\\d+(?:\\.\\d+)?");
  private static final Pattern WELL = Pattern.compile("WELL-\\d{4}");
  private final Llm llm; private final Tools tools; private final Dataset ds;

  public Agent(Llm llm, Tools tools, Dataset ds) { this.llm = llm; this.tools = tools; this.ds = ds; }

  String systemPrompt(String userId, String memory) {
    var u = ds.users.get(userId);
    return """
You are the Asset Intelligence Copilot for an oil field. Today is %s. You are talking to %s (%s).

RULES
1. Answer ONLY from tool results. Never use outside knowledge about wells, rules or numbers. For any question about wells, readings, repairs, spills, regulators, rules or documents, call tools first.
1b. Infer the task from the requested outcome, not exact wording. Compose tools by intent: current production status uses well_status; historical readings use inspections; spill counts and locations use spills; downtime rankings use downtime; repeat repair obligations use repair_escalations; repair costs/history use repairs; compliance combines events, linked reports, SOPs and notes. For oil-production rankings, check the dataset for actual volume fields; if absent, use production_activity for the requested period and explain it is inspection-derived status frequency, not oil volume or rate. For temporary bypass questions, search all imported collections and documents for bypass records AND approval memos; distinguish completed/current work from future plans. “In place now” means bypass-in date <= today and bypass-out is absent or >= today. Exclude future start dates from active bypasses, and never pair unrelated in/out dates. Report current and historical bypasses that are expired or unapproved only when the record evidence establishes their dates/status; show missing memo as “None found” and unknown validity as “Not recorded”. Do not infer “allowed” solely from operational dates. Return the requested columns in a properly formatted Markdown table. For a geographic map of wells, use well_map. For pressure maps, use pressure_map across the whole field, label each pressure source/type, and never present flowline pressure as reservoir pressure. For spill maps, use spill_heatmap. For unfamiliar questions, search_everywhere globally; use dataset_catalog/query_records for field discovery and exact aggregation. Unless the user names a scope, search globally. When a metric is missing, state that and report related evidence with its definition; never end with bare “no data” if related evidence exists. Do not present a proxy as the requested metric. Use search_documents for SOPs and prose. Respect dates, scope, ranking and requested visual dimensions.
1a. For questions asking which rules were broken or what remains open, regulatory_events alone is not sufficient. For the named well and time window, retrieve regulatory_events, search_documents for the relevant well's linked incident/maintenance reports and the applicable SOP/manual protocol limits (request several relevant passages in one search), and notes for current operator or pending-work context. For spill containment, use spill_compliance to join all spills to their reports and return SR-02 verdicts in one call. Check notice-response events before calling a response missing; distinguish recorded open events, overdue requirements, and unverified/in-progress actions. Do not conclude there were no breaches based only on an empty or partial source. Never claim an SOP is inaccessible unless the document tool reports an error.
2. If tools return nothing relevant, say it is not in the dataset. Never guess or invent a cause; if no reason is recorded, say so and suggest a check.
3. Copy numbers, ids and dates exactly from tool results. Do not do arithmetic yourself: use the totals tools return.
4. A well is producing/not producing as computed by tools from the inspection and the manual rule. recorded_status can be stale; say so when it disagrees.
5. Cite record ids (inspection, maintenance, note, event ids) and document paths you used, inline in brackets.
6. If a user's statement conflicts with the data or another note, say so plainly, show both sides, and do not silently pick one. Use record_note for statements to share and record_correction for corrections; both keep originals. When the user gives a measured value that replaces an AI estimate, that is an explicit correction: record it immediately. If the inspection_id is not stated but the user gives a well and date, call record_correction with well_id and date to resolve the unique inspection. For a date without a year, use the current dataset year when that date has already passed; do not invent an older year or ask the user for an ID before trying this lookup. After correcting a spill volume, check the returned reportability, linked report evidence and related regulatory events, and explain any newly applicable notice/cleanup deadlines and stale report classification. If a source inspection note conflicts with a correction, state the conflict and ask the user to confirm.
7. Select visuals by semantic dimensions and honor the requested type. For two-dimensional spill summaries by area and time, use spill_heatmap, which returns aggregated area-month cells including zeroes and totals and excludes corrected-away detections. For other named visuals, call present with that type after the appropriate data tool. Otherwise choose a visual that fits the result.
7a. Treat explicit shared policy statements as scoped user rules, not as private memory. Apply them only to the stated well or well type and equipment, cite the user note, identify them as user-provided, and say when they override the manual. Use notes and repair_escalations to find and apply active scoped rules. Keep ordinary factual notes unconfirmed and show conflicts between users or notes and readings.
8. Be concise. Lead with the answer.

WHAT YOU REMEMBER ABOUT THIS USER (private, from earlier chats):
%s
""".formatted(tools.asOf, u == null ? userId : u.name(), u == null ? "user" : u.role(), memory.isBlank() ? "(nothing yet)" : memory);
  }

  public Answer run(String userId, String memory, List<JsonNode> history, String question) throws Exception {
    ArrayNode msgs = Dataset.M.createArrayNode();
    msgs.addObject().put("role", "system").put("content", systemPrompt(userId, memory));
    history.forEach(msgs::add);
    msgs.addObject().put("role", "user").put("content", question);
    ArrayNode specs = tools.ollamaSpecs();
    Tools.Ctx ctx = new Tools.Ctx(userId);
    StringBuilder evidence = new StringBuilder(question).append(' ').append(memory);
    history.forEach(h -> evidence.append(' ').append(h.path("content").asText()));
    List<String> cites = new ArrayList<>(), trace = new ArrayList<>();
    Tools.Visual visual = null;
    int toolCalls = 0; boolean nudgedTools = false; int groundRetries = 0;

    for (int step = 0; step < MAX_STEPS; step++) {
      JsonNode m = llm.chat(msgs, specs);
      msgs.add(m);
      JsonNode calls = m.path("tool_calls");
      if (calls.isArray() && calls.size() > 0) {
        for (JsonNode call : calls) {
          String name = call.path("function").path("name").asText();
          JsonNode args = call.path("function").path("arguments");
          if (args.isTextual()) args = Dataset.M.readTree(args.asText());
          toolCalls++; trace.add(name + " " + args);
          String out;
          try {
            Tools.Tool t = tools.tools.get(name);
            if (t == null) throw new IllegalArgumentException("Unknown tool " + name + ". Available: " + tools.tools.keySet());
            Tools.Result r = t.fn().call(args, ctx);
            cites.addAll(r.citations());
            if (r.visual() != null) {
              if (r.visual().columns() == null && visual != null) visual = new Tools.Visual(r.visual().type(), visual.title(), visual.columns(), visual.rows());
              else if (r.visual().columns() != null && !r.visual().rows().isEmpty()) visual = r.visual();
            }
            out = r.data().toString();
          } catch (Exception e) { out = "{\"error\":" + Dataset.M.writeValueAsString(String.valueOf(e.getMessage())) + "}"; trace.add("  error: " + e.getMessage()); }
          if (out.length() > MAX_TOOL_CHARS) out = out.substring(0, MAX_TOOL_CHARS) + "...[truncated]";
          evidence.append(' ').append(out);
          msgs.addObject().put("role", "tool").put("tool_name", name).put("content", out);
        }
        continue;
      }
      String text = m.path("content").asText("").trim();
      // Gate 1: factual-looking answer with zero tool calls.
      if (toolCalls == 0 && !nudgedTools && (WELL.matcher(text).find() || NUM.matcher(text).find())) {
        nudgedTools = true;
        msgs.addObject().put("role", "user").put("content", "You answered without calling any tool. Call the appropriate tools and answer only from their results.");
        continue;
      }
      // Gate 2: every figure must appear in the evidence.
      List<String> bad = unsupported(text, evidence.toString());
      if (!bad.isEmpty() && groundRetries < 1) {
        groundRetries++;
        msgs.addObject().put("role", "user").put("content", "Your draft contains figures not found in the tool results: " + bad + ". Rewrite using only values from tool results, or call a tool to get them.");
        continue;
      }
      boolean grounded = bad.isEmpty();
      if (!grounded) text += "\n\n⚠ Unverified figures (not found in the data returned): " + String.join(", ", bad);
      Set<String> wells = new LinkedHashSet<>(); Matcher w = WELL.matcher(text); while (w.find()) wells.add(w.group());
      return new Answer(text, cites.stream().distinct().toList(), visual, trace, new ArrayList<>(wells), grounded);
    }
    return new Answer("I could not complete this within the allowed number of steps. Please narrow the question.", cites, visual, trace, List.of(), false);
  }

  /** Numbers in the answer that never occur in the evidence (small integers and years are tolerated). */
  static List<String> unsupported(String answer, String evidence) {
    Set<String> seen = new HashSet<>();
    Matcher e = NUM.matcher(evidence);
    while (e.find()) seen.add(norm(e.group()));
    List<String> bad = new ArrayList<>();
    Matcher a = NUM.matcher(answer);
    while (a.find()) {
      String raw = a.group(); String n = norm(raw);
      if (seen.contains(n)) continue;
      if (!raw.contains(".") && Integer.parseInt(raw.length() > 9 ? "999999999" : raw) <= 12) continue;
      if (!bad.contains(raw)) bad.add(raw);
    }
    return bad;
  }
  private static String norm(String s) { return new BigDecimal(s).stripTrailingZeros().toPlainString(); }
}
