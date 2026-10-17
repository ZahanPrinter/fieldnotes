package com.flytbase.copilot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.flytbase.copilot.core.Models.*;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * General-purpose, read-mostly tools the LLM can call. They are parameterised over scope
 * (well / list / area / type) and time window, so any question that fits the data model can be
 * answered by composing them. All numbers are computed here; the model only reads results.
 */
public final class Tools {
  public record Visual(String type, String title, List<String> columns, List<List<Object>> rows) {}
  public record Result(JsonNode data, List<String> citations, Visual visual) {}
  public record Ctx(String userId) {}
  public interface Fn { Result call(JsonNode args, Ctx ctx) throws Exception; }
  public record Tool(String name, String description, String schemaJson, Fn fn) {}

  static final int MAX_ROWS = 60;
  private final Domain d;
  private final Dataset ds;
  private final Overlay ov;
  private final DocIndex docs;
  public final LocalDate asOf;
  public final Map<String, Tool> tools = new LinkedHashMap<>();

  public Tools(Domain d, DocIndex docs, LocalDate asOf) {
    this.d = d; this.ds = d.ds; this.ov = d.overlay; this.docs = docs; this.asOf = asOf;
    reg("list_wells", "List wells with type, area, location and the (possibly stale) recorded status. Filter by area or well_type.",
        "{\"area\":{\"type\":\"string\",\"description\":\"area name or id\"},\"well_type\":{\"type\":\"string\"}}", this::listWells);
    reg("well_map", "Map wells as geographic points with latitude/longitude axes, area, well type, and latest computed production status. Use for requests to map or locate wells; accepts the standard well_id, well_ids, area, and well_type scope filters.",
        SCOPE, this::wellMap);
    reg("pressure_map", "Search maintenance reports across the whole field for numeric pressure evidence and map it by well. Distinguish reservoir/formation pressure from flowline test readings and flowline operating limits. If reservoir pressure is absent but related pressure records exist, map the related values and explicitly explain they are not reservoir pressure.",
        "{\"area\":{\"type\":\"string\",\"description\":\"Optional area filter; omit to search all areas\"},\"kind\":{\"type\":\"string\",\"enum\":[\"all\",\"reservoir\",\"flowline_test\",\"flowline_limit\"]}}", this::pressureMap);
    reg("well_status", "Current status per well from its LATEST inspection: producing or not, wellhead-minus-air delta, the production rule used, recorded status, and any notes/corrections that apply. Use for 'is X producing', 'which wells are down now'. Filter with well_id, well_ids, area, well_type; only_not_producing=true to list only stopped wells.",
        SCOPE + ",\"only_not_producing\":{\"type\":\"boolean\"}", this::wellStatus);
    reg("inspections", "Weekly inspection rows for ONE well in a date range (temperatures, producing flag, spill, inspector, notes). Default: last 30.",
        "{\"well_id\":{\"type\":\"string\"},\"from\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD\"},\"to\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}}", this::inspections, "well_id");
    reg("production_activity", "Rank wells by producing-status consistency during a date range, derived from inspection temperatures and the well-type manual rule. This is not oil volume or a production-rate estimate. Use when a user asks about production but the dataset has no volume totals; report it as related operational evidence only.",
        SCOPE + ",\"from\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD inclusive\"},\"to\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD inclusive\"},\"top_n\":{\"type\":\"integer\"}", this::productionActivity);
    reg("downtime", "Downtime days per well in a date range (each non-producing weekly inspection = 7 days). Ranked high to low; top_n limits rows. Filter by well_id, well_ids, area, well_type.",
        SCOPE + ",\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"top_n\":{\"type\":\"integer\"}", this::downtime);
    reg("spills", "Confirmed oil spills (corrected/false detections excluded) in a date range, with volume, date, location and inspection id. Optional min_bbl.",
        SCOPE + ",\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"min_bbl\":{\"type\":\"number\"}", this::spills);
    reg("spill_heatmap", "Aggregate confirmed spills by geographic area and calendar month for a date range. Corrected-away detections are excluded. Returns every area-month cell including zeroes and area totals, with a heatmap visual. Use this instead of raw spills when the user asks for a spill heatmap by area and month.",
        "\"from\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD; defaults to start of current year\"},\"to\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD; defaults to today\"}", this::spillHeatmap);
    reg("spill_compliance", "For every confirmed spill in an area/date range, returns the linked report's SR-02 containment duration and compliance alongside the SOP time limit. Use for questions asking which spills missed the SOP containment deadline; this performs the join in one call.",
        SCOPE + ",\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"}", this::spillCompliance);
    reg("repairs", "Maintenance records (selected by START date) with cost. Filter by scope, maintenance_type (corrective|scheduled), equipment_kind. group_by: none|equipment_kind|well.",
        SCOPE + ",\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"maintenance_type\":{\"type\":\"string\"},\"equipment_kind\":{\"type\":\"string\"},\"group_by\":{\"type\":\"string\"}", this::repairs);
    reg("repair_escalations", "Find wells whose recent corrective repairs meet their well-type manual or explicit shared user rule for a root-cause analysis. Counts are computed by well and equipment kind over the requested period; a scoped active user rule overrides the manual. Use for maintenance escalation / repeat-failure obligations.",
        SCOPE + ",\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"equipment_kind\":{\"type\":\"string\"}", this::repairEscalations);
    reg("regulatory_events", "Regulator events (spill reports, violations, fines, site inspections) with hours_to_report, fine and open/closed status.",
        SCOPE + ",\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"event_type\":{\"type\":\"string\"}", this::regulatory);
    reg("notes", "Human notes, formal corrections, and notes/corrections users added in chat for one well.",
        "{\"well_id\":{\"type\":\"string\"}}", this::notes, "well_id");
    reg("search_documents", "Search manuals, SOPs and repair reports (BM25). Use for rules, deadlines, thresholds and facts found only in reports (e.g. containment time). Optional well_id restricts reports to that well.",
        "{\"query\":{\"type\":\"string\"},\"well_id\":{\"type\":\"string\"},\"k\":{\"type\":\"integer\"}}", this::search, "query");
    reg("search_records", "Search imported JSON collections with their original fields intact. Use when a question refers to information or a collection not covered by the standard well, inspection, spill, repair, or regulatory tools. Optional entity_type narrows the collection.",
        "{\"query\":{\"type\":\"string\",\"description\":\"Words, ID, well ID, or phrase to find; leave blank to browse records\"},\"entity_type\":{\"type\":\"string\",\"description\":\"Optional imported collection name\"},\"limit\":{\"type\":\"integer\"}}", this::searchRecords);
    reg("search_everywhere", "Search the entire selected database across all imported record types, all wells and areas, plus manuals, SOPs and repair reports. Uses token and word-form matching and returns related evidence grouped by source and area. Use this before saying a requested fact is unavailable, especially when its location or collection is unknown.",
        "{\"query\":{\"type\":\"string\",\"description\":\"Question or key concepts to search globally\"},\"limit\":{\"type\":\"integer\",\"description\":\"Maximum matching records to return, default 40\"}}", this::searchEverywhere);
    reg("dataset_catalog", "Discover the imported dataset before answering a request that is not fully covered by a standard tool. Returns actual entity types, row counts, and JSON field paths (including nested fields). This is dataset-driven; do not assume a fixed schema.",
        "{}", this::datasetCatalog);
    reg("query_records", "Query original imported records using discovered entity types and field paths. Supports well scope, date range, exact/contains/numeric comparison, sorting, grouping and count/sum/average. Use after dataset_catalog for unfamiliar or cross-collection questions; never assume absent fields without checking the catalog.",
        "{\"entity_type\":{\"type\":\"string\"},\"well_id\":{\"type\":\"string\"},\"area\":{\"type\":\"string\",\"description\":\"Optional explicit area filter; omit to search across the full field\"},\"search\":{\"type\":\"string\"},\"field\":{\"type\":\"string\"},\"operator\":{\"type\":\"string\",\"enum\":[\"eq\",\"contains\",\"gt\",\"gte\",\"lt\",\"lte\"]},\"value\":{\"type\":\"string\"},\"date_field\":{\"type\":\"string\"},\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"},\"sort_by\":{\"type\":\"string\"},\"descending\":{\"type\":\"boolean\"},\"group_by\":{\"type\":\"string\"},\"aggregate\":{\"type\":\"string\",\"enum\":[\"count\",\"sum\",\"avg\"]},\"aggregate_field\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\"}}", this::queryRecords);
    reg("record_correction", "Record a user's correction to a raw reading or to an AI estimate. Keeps the original. field: wellhead_temp_c | ambient_temp_c | spill_volume_bbl. Only call when the user states a correction.",
        "{\"inspection_id\":{\"type\":\"string\",\"description\":\"Use when known\"},\"well_id\":{\"type\":\"string\"},\"date\":{\"type\":\"string\",\"description\":\"YYYY-MM-DD or a date such as 24 Sep; a yearless date resolves against the current dataset year\"},\"field\":{\"type\":\"string\"},\"new_value\":{\"type\":\"number\"},\"reason\":{\"type\":\"string\"}}", this::correct, "field", "new_value", "reason");
    reg("record_note", "Store a statement a user wants shared (planned shutdown, rule for a well type...). scope = well id or 'type:T350'. It is stored as UNCONFIRMED. Always check well_status first and tell the user if it conflicts with the data.",
        "{\"scope\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"}}", this::note, "scope", "text");
    reg("present", "Change how the last data result is displayed when the user names a visual: table | bar | line | map | heatmap | card.",
        "{\"type\":{\"type\":\"string\"}}", (a, c) -> new Result(M().createObjectNode().put("ok", true), List.of(), new Visual(a.path("type").asText("table"), null, null, null)), "type");
  }

  private static final String SCOPE = "\"well_id\":{\"type\":\"string\"},\"well_ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"area\":{\"type\":\"string\",\"description\":\"area name or id\"},\"well_type\":{\"type\":\"string\"}";
  private static com.fasterxml.jackson.databind.ObjectMapper M() { return Dataset.M; }

  private void reg(String n, String desc, String props, Fn fn, String... required) {
    StringBuilder req = new StringBuilder();
    for (String r : required) req.append(req.length() == 0 ? "" : ",").append('"').append(r).append('"');
    String inner = props.startsWith("{") ? props.substring(1, props.length() - 1) : props; // props may be given with or without outer braces
    String schema = "{\"type\":\"object\",\"properties\":{" + inner + "},\"required\":[" + req + "]}";
    tools.put(n, new Tool(n, desc, schema, fn));
  }

  /** Ollama /api/chat "tools" array. */
  public ArrayNode ollamaSpecs() throws Exception {
    ArrayNode arr = M().createArrayNode();
    for (Tool t : tools.values()) {
      ObjectNode f = arr.addObject().put("type", "function").putObject("function");
      f.put("name", t.name()).put("description", t.description()).set("parameters", M().readTree(t.schemaJson()));
    }
    return arr;
  }

  // ---------- helpers ----------
  private LocalDate date(JsonNode a, String k, LocalDate def) {
    String s = a.path(k).asText("");
    return s.isBlank() ? def : LocalDate.parse(s.substring(0, 10));
  }

  List<String> scope(JsonNode a) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    if (a.hasNonNull("well_id")) ids.add(a.get("well_id").asText().toUpperCase());
    if (a.has("well_ids")) for (JsonNode n : a.get("well_ids")) ids.add(n.asText().toUpperCase());
    if (ids.isEmpty()) {
      if (a.hasNonNull("area") && !a.get("area").asText().isBlank()) {
        ids.addAll(d.wellsInArea(a.get("area").asText()));
        if (ids.isEmpty()) throw new IllegalArgumentException("Unknown area '" + a.get("area").asText() + "'. Known areas: " + ds.areaNames);
      } else ids.addAll(ds.wells.keySet());
    }
    for (String id : ids) if (!ds.wells.containsKey(id)) throw new IllegalArgumentException("Unknown well " + id);
    if (a.hasNonNull("well_type")) ids.removeIf(id -> !ds.wells.get(id).well_type().equalsIgnoreCase(a.get("well_type").asText()));
    return new ArrayList<>(ids);
  }

  private ObjectNode row(Object... kv) {
    ObjectNode o = M().createObjectNode();
    for (int i = 0; i < kv.length; i += 2) {
      String k = (String) kv[i]; Object v = kv[i + 1];
      if (v == null) o.putNull(k);
      else if (v instanceof Integer x) o.put(k, x);
      else if (v instanceof Long x) o.put(k, x);
      else if (v instanceof Double x) o.put(k, Math.round(x * 100) / 100.0);
      else if (v instanceof Boolean x) o.put(k, x);
      else o.put(k, v.toString());
    }
    return o;
  }

  private Visual visual(String type, String title, List<String> cols, List<ObjectNode> rows) {
    List<List<Object>> out = new ArrayList<>();
    for (ObjectNode r : rows.stream().limit(MAX_ROWS).toList()) { List<Object> l = new ArrayList<>(); for (String c : cols) l.add(M().convertValue(r.get(c), Object.class)); out.add(l); }
    return new Visual(type, title, cols, out);
  }

  private Result result(String key, List<ObjectNode> rows, Map<String, Object> extra, List<String> cites, Visual v) {
    return result(key, rows, extra, cites, v, rows.size());
  }

  private Result result(String key, List<ObjectNode> rows, Map<String, Object> extra, List<String> cites, Visual v, int totalRows) {
    ObjectNode o = M().createObjectNode();
    o.put("as_of", asOf.toString());
    o.put("row_count", totalRows);
    if (totalRows > MAX_ROWS) o.put("truncated_to", MAX_ROWS);
    for (var e : extra.entrySet()) o.set(e.getKey(), M().valueToTree(e.getValue()));
    ArrayNode arr = o.putArray(key);
    rows.stream().limit(MAX_ROWS).forEach(arr::add);
    if (rows.isEmpty()) o.put("note", "No matching records in the dataset.");
    return new Result(o, cites.stream().distinct().limit(40).toList(), v);
  }

  private String name(String uid) { User u = ds.users.get(uid); return u == null ? uid : u.name(); }

  // ---------- tools ----------
  private Result listWells(JsonNode a, Ctx c) {
    List<ObjectNode> rows = new ArrayList<>();
    List<String> ids = scope(a);
    for (String id : ids.stream().limit(MAX_ROWS).toList()) { Well w = ds.wells.get(id);
      rows.add(row("well", id, "type", w.well_type(), "area", ds.areaNames.getOrDefault(w.area_id(), w.area_id()),
          "recorded_status", w.recorded_status(), "lat", w.location().lat(), "lon", w.location().lon())); }
    return result("wells", rows, Map.of(), rows.stream().map(r -> r.get("well").asText()).toList(),
        visual("table", "Wells", List.of("well", "type", "area", "recorded_status"), rows), ids.size());
  }

  private Result wellMap(JsonNode a,Ctx c){
    List<ObjectNode> rows=new ArrayList<>();List<String> cites=new ArrayList<>(),withoutLocation=new ArrayList<>();List<String> ids=scope(a);
    for(String id:ids){Well w=ds.wells.get(id);if(w.location()==null){withoutLocation.add(id);continue;}Optional<Inspection> latest=d.latestInspection(id);Boolean producing=null;String inspection="";if(latest.isPresent()){inspection=latest.get().inspection_id();if(ds.productionRules.containsKey(w.well_type()))producing=d.producing(id,d.effective(latest.get()));if(inspection!=null)cites.add(inspection);}rows.add(row("well",id,"area",ds.areaNames.getOrDefault(w.area_id(),w.area_id()),"type",w.well_type(),"producing",producing,"last_inspection",inspection,"lat",w.location().lat(),"lon",w.location().lon()));cites.add(id);}
    Map<String,Object> extra=new LinkedHashMap<>();extra.put("mapped_wells",rows.size());extra.put("wells_without_coordinates",withoutLocation);
    return result("locations",rows,extra,cites,visual("map","Well locations and latest status",List.of("well","area","type","producing","last_inspection","lat","lon"),rows),rows.size());
  }

  private record PressurePoint(String well,String area,String areaId,String kind,double psi,String date,String source,double lat,double lon) {}

  private Result pressureMap(JsonNode a,Ctx c) throws Exception {
    String requested=a.path("kind").asText("all").toLowerCase(Locale.ROOT),areaFilter=a.path("area").asText("").trim();
    Map<String,PressurePoint> latestByWell=new HashMap<>();int reservoirCount=0,testCount=0,limitCount=0;
    Pattern patterns=Pattern.compile("(?i)(pressure[- ]tested[^\\n]{0,120}?at\\s+|maximum operating pressure of\\s+|(?:reservoir|formation) pressure[^\\n]{0,80}?(?:at\\s+|of\\s+)?)([0-9]+(?:\\.[0-9]+)?)\\s*(psi|kpa|mpa|bar)\\b");
    Pattern generic=Pattern.compile("(?i)((?:pressure[^\\n]{0,100}?|[^\\n]{0,100}?pressure)[^\\n]{0,100}?)([0-9]+(?:\\.[0-9]+)?)\\s*(psi|kpa|mpa|bar)\\b");
    Map<String,Maintenance> maintenanceById=new HashMap<>();for(var list:ds.maintenance.values())for(Maintenance m:list)maintenanceById.put(m.maintenance_id(),m);
    for(var entry:ds.reportFiles.entrySet()){
      String maintenanceId=entry.getKey();Maintenance m=maintenanceById.get(maintenanceId);if(m==null)continue;Well well=ds.wells.get(m.asset_id());if(well==null||well.location()==null)continue;
      String areaName=ds.areaNames.getOrDefault(well.area_id(),well.area_id());if(!areaFilter.isBlank()&&!areaName.equalsIgnoreCase(areaFilter)&&!well.area_id().equalsIgnoreCase(areaFilter))continue;
      String report=Files.readString(entry.getValue());PressurePoint best=null;
      for(String line:report.split("\\R")){
        if(!line.toLowerCase(Locale.ROOT).contains("pressure"))continue;
        Matcher matcher=patterns.matcher(line);boolean typed=matcher.find();if(!typed){matcher=generic.matcher(line);if(!matcher.find())continue;}
        do{
          String excerpt=matcher.group(0).toLowerCase(Locale.ROOT),unit=matcher.group(3).toLowerCase(Locale.ROOT),kind;
          if(excerpt.contains("reservoir")||excerpt.contains("formation")){kind="reservoir";reservoirCount++;}
          else if(excerpt.contains("maximum operating pressure")){kind="flowline_limit";limitCount++;}
          else if(excerpt.contains("pressure-tested")||excerpt.contains("pressure tested")){kind="flowline_test";testCount++;}
          else kind="other";
          double value=Double.parseDouble(matcher.group(2));value=switch(unit){case "kpa"->value/6.894757;case "mpa"->value*145.037738;case "bar"->value*14.503774;default->value;};
          boolean take=requested.equals("all")||requested.equals(kind)||(requested.equals("reservoir")&&(kind.equals("flowline_test")||kind.equals("flowline_limit")));
          if(take){String date=m.started_at()==null?"":m.started_at().toString().substring(0,10);PressurePoint p=new PressurePoint(well.asset_id(),areaName,well.area_id(),kind,Math.round(value*10.0)/10.0,date,maintenanceId,well.location().lat(),well.location().lon());if(best==null||p.date().compareTo(best.date())>0||p.date().equals(best.date())&&p.kind().equals("flowline_test"))best=p;}
        }while(matcher.find());
      }
      if(best!=null){PressurePoint old=latestByWell.get(well.asset_id());if(old==null||best.date().compareTo(old.date())>0||best.date().equals(old.date())&&best.kind().equals("flowline_test"))latestByWell.put(well.asset_id(),best);}
    }
    List<PressurePoint> points=latestByWell.values().stream().sorted(Comparator.comparing(PressurePoint::area).thenComparing(PressurePoint::well)).toList();
    List<ObjectNode> outputRows=new ArrayList<>();for(PressurePoint p:points.stream().limit(MAX_ROWS).toList())outputRows.add(row("well",p.well(),"area",p.area(),"area_id",p.areaId(),"pressure_type",p.kind(),"pressure_psi",p.psi(),"repair_date",p.date(),"maintenance_report",p.source(),"lat",p.lat(),"lon",p.lon()));
    List<List<Object>> mapRows=new ArrayList<>();for(PressurePoint p:points){List<Object> values=new ArrayList<>();Collections.addAll(values,p.well(),p.area(),p.kind(),p.psi(),p.date(),p.source(),p.lat(),p.lon());mapRows.add(values);}
    ObjectNode summary=M().createObjectNode().put("requested_kind",requested).put("wells_mapped",points.size()).put("reservoir_pressure_readings_found",reservoirCount).put("flowline_test_pressure_mentions",testCount).put("flowline_operating_limit_mentions",limitCount);if(requested.equals("reservoir")&&reservoirCount==0)summary.put("interpretation","No numeric reservoir or formation pressure readings were found. The map shows related flowline pressure evidence and must not be interpreted as reservoir pressure.");ArrayNode records=summary.putArray("latest_pressure_evidence_by_well");outputRows.forEach(records::add);
    Visual map=mapRows.isEmpty()?null:new Visual("map",requested.equals("reservoir")&&reservoirCount==0?"Flowline pressure evidence (not reservoir pressure)":"Pressure evidence by well",List.of("well","area","pressure_type","pressure_psi","repair_date","maintenance_report","lat","lon"),mapRows);
    return new Result(summary,points.stream().limit(MAX_ROWS).map(PressurePoint::source).distinct().toList(),map);
  }

  private Result wellStatus(JsonNode a, Ctx c) {
    boolean onlyDown = a.path("only_not_producing").asBoolean(false);
    List<ObjectNode> rows = new ArrayList<>(); List<String> cites = new ArrayList<>();
    int matched = 0;
    for (String id : scope(a)) {
      Well w = ds.wells.get(id);
      Optional<Inspection> li = d.latestInspection(id);
      if (li.isEmpty()) continue;
      Domain.Effective e = d.effective(li.get());
      boolean prod = d.producing(id, e);
      if (onlyDown && prod) continue;
      matched++;
      if (rows.size() >= MAX_ROWS) continue;
      Dataset.Rule r = ds.productionRules.get(w.well_type());
      LocalDate day = Domain.day(li.get());
      List<String> ctx = new ArrayList<>();
      for (Note n : ds.notes.getOrDefault(id, List.of())) {
        if ("correction".equals(n.note_type())) continue;
        LocalDate vf = n.valid_from() == null ? LocalDate.MIN : LocalDate.parse(n.valid_from());
        LocalDate vt = n.valid_to() == null ? LocalDate.MAX : LocalDate.parse(n.valid_to());
        if (!day.isBefore(vf) && !day.isAfter(vt)) { ctx.add(n.note_id() + " (" + name(n.author_user_id()) + "): " + n.text()); cites.add(n.note_id()); }
      }
      for (Overlay.SharedNote n : ov.notesFor(id, w.well_type())) ctx.add(n.id() + " [" + n.status() + ", " + name(n.authorUserId()) + "]: " + n.text());
      for (Overlay.SharedNote n : ov.notesFor(id, w.well_type())) cites.add(n.id());
      List<String> corr = e.applied().stream().map(x -> x.id() + ": " + x.field() + " " + x.originalValue() + " -> " + x.newValue() + " by " + name(x.authorUserId()) + " (" + x.reason() + ")").toList();
      for (Overlay.Correction correction : e.applied()) cites.add(correction.id());
      rows.add(row("well", id, "type", w.well_type(), "producing", prod, "wellhead_c", e.wellheadC(), "ambient_c", e.ambientC(),
          "delta_c", e.delta(), "required_delta_c", r.value(), "last_inspection", li.get().inspection_id(), "inspection_method", li.get().method(),
          "inspected_on", day.toString(), "inspector", name(li.get().inspector_user_id()), "inspector_note", li.get().notes(),
          "recorded_status", w.recorded_status(), "context_notes", String.join(" | ", ctx), "corrections_applied", String.join(" | ", corr)));
      cites.add(li.get().inspection_id()); cites.add(r.source());
    }
    return result("wells", rows, Map.of("note", "'producing' derives from the inspection vs the manual rule; recorded_status can be stale; the inspection is the truth."), cites,
        visual(matched == 1 ? "card" : "table", "Well status", List.of("well", "producing", "delta_c", "required_delta_c", "inspected_on", "recorded_status"), rows), matched);
  }

  private Result inspections(JsonNode a, Ctx c) {
    String id = a.path("well_id").asText().toUpperCase();
    if (!ds.wells.containsKey(id)) throw new IllegalArgumentException("Unknown well " + id);
    LocalDate from = date(a, "from", LocalDate.MIN), to = date(a, "to", asOf);
    int limit = a.path("limit").asInt(30);
    List<Inspection> all = d.inspectionsInRange(id, from, to);
    int take = Math.max(0, Math.min(limit, MAX_ROWS));
    List<Inspection> sel = all.subList(Math.max(0, all.size() - take), all.size());
    List<ObjectNode> rows = new ArrayList<>();
    for (Inspection i : sel) { Domain.Effective e = d.effective(i);
      rows.add(row("inspection", i.inspection_id(), "date", Domain.day(i).toString(), "wellhead_c", e.wellheadC(), "ambient_c", e.ambientC(),
          "delta_c", e.delta(), "producing", d.producing(id, e), "spill", e.spill(), "spill_bbl", e.spill() ? e.spillBbl() : null,
          "spill_corrected_away", e.spillCorrectedAway() ? e.correctionNoteId() : null, "inspector", name(i.inspector_user_id()), "note", i.notes())); }
    return result("inspections", rows, Map.of("total_in_range", all.size()), rows.stream().map(r -> r.get("inspection").asText()).toList(),
        visual("line", id + " wellhead vs air temperature", List.of("date", "wellhead_c", "ambient_c", "producing"), rows));
  }

  private Result productionActivity(JsonNode a, Ctx c) {
    LocalDate from = date(a, "from", LocalDate.of(asOf.getYear(), 1, 1));
    LocalDate to = date(a, "to", asOf);
    int top = Math.max(1, Math.min(a.path("top_n").asInt(20), MAX_ROWS));
    List<ObjectNode> rows = new ArrayList<>();
    List<String> cites = new ArrayList<>();
    for (String id : scope(a)) {
      Well w = ds.wells.get(id);
      List<Inspection> inspections = d.inspectionsInRange(id, from, to);
      int producing = 0, observed = 0;
      List<String> examples = new ArrayList<>();
      for (Inspection i : inspections) {
        Domain.Effective e = d.effective(i);
        if (!Double.isFinite(e.wellheadC()) || !Double.isFinite(e.ambientC())) continue;
        observed++;
        boolean isProducing = d.producing(id, e);
        if (isProducing) producing++;
        if (examples.size() < 3) {
          examples.add(i.inspection_id() + " (" + Domain.day(i) + "): " + (isProducing ? "producing" : "not producing"));
          cites.add(i.inspection_id());
        }
      }
      double pct = observed == 0 ? 0 : 100.0 * producing / observed;
      rows.add(row("well", id, "area", ds.areaNames.getOrDefault(w.area_id(), w.area_id()),
          "producing_inspections", producing, "inspections_with_readings", observed,
          "producing_status_pct", Math.round(pct * 10.0) / 10.0,
          "inspection_evidence", String.join("; ", examples),
          "measure_note", "Inspection status frequency only; not oil volume or production rate."));
    }
    rows.sort(Comparator.<ObjectNode>comparingDouble(r -> -r.path("producing_status_pct").asDouble())
        .thenComparingInt(r -> -r.path("producing_inspections").asInt())
        .thenComparing(r -> r.path("well").asText()));
    int total = rows.size();
    List<ObjectNode> selected = rows.stream().limit(top).toList();
    return result("ranking", selected, Map.of("window", from + " to " + to,
        "wells_considered", total,
        "metric_definition", "Share and count of inspections whose temperature readings met the production threshold in the well-type manual.",
        "limitation", "The dataset has no cumulative or per-period oil production volumes, so this ranking cannot identify the highest-volume producer."),
        cites, visual("bar", "Producing-status consistency (not oil volume)",
            List.of("well", "area", "producing_status_pct", "producing_inspections", "inspections_with_readings"), selected), total);
  }

  private Result downtime(JsonNode a, Ctx c) {
    LocalDate from = date(a, "from", LocalDate.of(2000, 1, 1)), to = date(a, "to", asOf);
    List<Domain.Downtime> l = new ArrayList<>();
    for (String id : scope(a)) l.add(d.downtime(id, from, to));
    l.sort(Comparator.comparingInt(Domain.Downtime::days).reversed().thenComparing(Domain.Downtime::wellId));
    int top = Math.max(0, Math.min(a.path("top_n").asInt(10), MAX_ROWS));
    List<ObjectNode> rows = new ArrayList<>(); List<String> cites = new ArrayList<>();
    for (Domain.Downtime x : l.stream().limit(top).toList()) {
      Well w = ds.wells.get(x.wellId());
      rows.add(row("well", x.wellId(), "type", w.well_type(), "downtime_days", x.days(), "non_producing_inspections", x.inspectionCount()));
      cites.add(x.wellId()); cites.addAll(x.sampleInspectionIds());
    }
    return result("ranking", rows, Map.of("window", from + " to " + to, "wells_considered", l.size(), "note", "Ties share the same value; a well beyond top_n may tie with the last row."), cites,
        visual("bar", "Downtime days", List.of("well", "downtime_days"), rows));
  }

  private Result spills(JsonNode a, Ctx c) {
    LocalDate from = date(a, "from", LocalDate.of(2000, 1, 1)), to = date(a, "to", asOf);
    double min = a.path("min_bbl").asDouble(0);
    double total = 0; int removed = 0, spillCount = 0;
    List<String> removedInspections = new ArrayList<>(), correctionNotes = new ArrayList<>();
    Comparator<Domain.Spill> earliestFirst = Comparator.comparing(Domain.Spill::date)
        .thenComparing(Domain.Spill::wellId).thenComparing(Domain.Spill::inspectionId);
    PriorityQueue<Domain.Spill> selected = new PriorityQueue<>(MAX_ROWS, earliestFirst.reversed());
    for (String id : scope(a)) {
      Domain.SpillSummary summary = d.spillSummary(id, from, to, min, MAX_ROWS);
      removed += summary.correctedAway();
      for (String iid : summary.correctedInspectionIds()) if (removedInspections.size() < MAX_ROWS) removedInspections.add(iid);
      for (String noteId : summary.correctionNoteIds()) if (correctionNotes.size() < MAX_ROWS) correctionNotes.add(noteId);
      spillCount += summary.spillCount();
      total += summary.totalBbl();
      for (Domain.Spill spill : summary.spills()) {
        if (selected.size() < MAX_ROWS) selected.add(spill);
        else if (earliestFirst.compare(spill, selected.peek()) < 0) { selected.poll(); selected.add(spill); }
      }
    }
    List<ObjectNode> rows = selected.stream().sorted(earliestFirst).map(spill -> {
      Well w = ds.wells.get(spill.wellId());
      return row("well", spill.wellId(), "date", spill.date().toString(), "bbl", spill.bbl(), "inspection", spill.inspectionId(),
          "area", ds.areaNames.getOrDefault(w.area_id(), w.area_id()), "lat", w.location().lat(), "lon", w.location().lon());
    }).toList();
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("spill_count", spillCount); summary.put("total_bbl", Math.round(total * 100) / 100.0);
    summary.put("detections_removed_by_correction", removed); summary.put("window", from + " to " + to);
    if (!removedInspections.isEmpty()) { summary.put("corrected_away_inspections", removedInspections); summary.put("correction_note_ids", correctionNotes); }
    List<String> citations = new ArrayList<>(rows.stream().map(r -> r.get("inspection").asText()).toList());
    citations.addAll(removedInspections); citations.addAll(correctionNotes);
    return result("spills", rows, summary, citations,
        visual("table", "Spills", List.of("well", "date", "bbl", "area", "inspection", "lat", "lon"), rows), spillCount);
  }

  private Result spillCompliance(JsonNode a, Ctx c) throws Exception {
    LocalDate from = date(a, "from", asOf.minusDays(89)), to = date(a, "to", asOf);
    double limitHours = Double.NaN;
    for (var e : ds.docFiles.entrySet()) if (e.getKey().startsWith("fleet/sops/") && e.getKey().toLowerCase(Locale.ROOT).contains("spill")) {
      String sop = Files.readString(e.getValue());
      Matcher limit = Pattern.compile("(?is)SR-02.{0,300}?contained within\\s+\\*{0,2}([0-9]+(?:\\.[0-9]+)?)\\s*hours").matcher(sop);
      if (limit.find()) { limitHours = Double.parseDouble(limit.group(1)); break; }
    }
    if (!Double.isFinite(limitHours)) throw new IllegalStateException("Could not find the SR-02 containment limit in the spill SOP.");

    List<ObjectNode> rows = new ArrayList<>(); List<String> cites = new ArrayList<>();
    Pattern durationPattern = Pattern.compile("(?i)contained about\\s+([0-9]+(?:\\.[0-9]+)?)\\s*hours after");
    Pattern resultPattern = Pattern.compile("(?i)\\|\\s*SR-02\\s*\\|\\s*(?:\\*\\*)?(Met|Not met)(?:\\*\\*)?\\s*\\|");
    for (String id : scope(a)) {
      Domain.SpillSummary summary = d.spillSummary(id, from, to, Double.NEGATIVE_INFINITY, MAX_ROWS);
      for (Domain.Spill spill : summary.spills()) {
        String reportId = null, reportPath = null; Double hours = null; String compliance = "report unavailable";
        for (Maintenance m : ds.maintenance.getOrDefault(id, List.of())) {
          if (!spill.inspectionId().equals(m.triggered_by_inspection_id())) continue;
          var file = ds.reportFiles.get(m.maintenance_id());
          if (file == null) continue;
          reportId = m.maintenance_id(); reportPath = "assets/" + id + "/maintenance-reports/" + file.getFileName();
          String report = Files.readString(file);
          Matcher duration = durationPattern.matcher(report);
          if (duration.find()) hours = Double.parseDouble(duration.group(1));
          Matcher verdict = resultPattern.matcher(report);
          if (verdict.find()) compliance = verdict.group(1).equalsIgnoreCase("Met") ? "Met" : "Not met";
          else if (hours != null) compliance = hours <= limitHours ? "Met" : "Not met";
          break;
        }
        rows.add(row("well", id, "date", spill.date().toString(), "bbl", spill.bbl(), "inspection", spill.inspectionId(),
            "maintenance_report", reportId, "containment_hours", hours, "sop_limit_hours", limitHours, "SR-02", compliance,
            "report_source", reportPath));
        cites.add(spill.inspectionId()); if (reportPath != null) cites.add(reportPath);
      }
    }
    String window = from + " to " + to;
    return result("spill_compliance", rows, Map.of("confirmed_spills", rows.size(), "sop", "fleet/sops/spill-response-sop.md#4. Protocols",
        "SR-02_limit_hours", limitHours, "window", window), cites,
        visual("table", "Spill containment compliance", List.of("well", "date", "bbl", "inspection", "maintenance_report", "containment_hours", "sop_limit_hours", "SR-02"), rows), rows.size());
  }

  private Result spillHeatmap(JsonNode a, Ctx c) {
    LocalDate from = date(a, "from", asOf.withDayOfYear(1)), to = date(a, "to", asOf);
    if (to.isBefore(from)) throw new IllegalArgumentException("The end date must be on or after the start date.");
    List<YearMonth> months = new ArrayList<>();
    for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) months.add(m);
    List<ObjectNode> rows = new ArrayList<>(); List<String> cites = new ArrayList<>();
    int grandTotal = 0, correctedRemoved = 0;
    for (var area : ds.areaNames.entrySet()) {
      List<String> wells = d.wellsInArea(area.getValue());
      if (wells.isEmpty()) continue;
      int areaTotal = 0;
      for (YearMonth month : months) {
        LocalDate monthFrom = month.atDay(1).isBefore(from) ? from : month.atDay(1);
        LocalDate monthTo = month.atEndOfMonth().isAfter(to) ? to : month.atEndOfMonth();
        int count = 0;
        for (String id : wells) {
          Domain.SpillSummary summary = d.spillSummary(id, monthFrom, monthTo, Double.NEGATIVE_INFINITY, Integer.MAX_VALUE);
          count += summary.spillCount();
          correctedRemoved += summary.correctedAway();
          for (Domain.Spill spill : summary.spills()) cites.add(spill.inspectionId());
          cites.addAll(summary.correctionNoteIds());
        }
        rows.add(row("area", area.getValue(), "month", month.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), "spill_count", count));
        areaTotal += count;
      }
      rows.add(row("area", area.getValue(), "month", "Total", "spill_count", areaTotal));
      grandTotal += areaTotal;
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("confirmed_spills", grandTotal); summary.put("window", from + " to " + to);
    summary.put("areas", ds.areaNames.size()); summary.put("corrected_detections_removed", correctedRemoved);
    return result("spill_heatmap", rows, summary, cites,
        visual("heatmap", "Confirmed spills by area and month", List.of("area", "month", "spill_count"), rows), rows.size());
  }

  private Result repairs(JsonNode a, Ctx c) {
    LocalDate from = date(a, "from", LocalDate.of(2000, 1, 1)), to = date(a, "to", asOf);
    String type = a.path("maintenance_type").asText(""), kind = a.path("equipment_kind").asText(""), group = a.path("group_by").asText("none");
    List<ObjectNode> rows = new ArrayList<>(); List<String> cites = new ArrayList<>();
    double total = 0; int n = 0; Map<String, double[]> g = new TreeMap<>();
    for (String id : scope(a)) for (Maintenance m : ds.maintenance.getOrDefault(id, List.of())) {
      LocalDate s = m.started_at().atZone(ZoneOffset.UTC).toLocalDate();
      if (s.isBefore(from) || s.isAfter(to)) continue;
      if (!type.isBlank() && !type.equalsIgnoreCase(m.maintenance_type())) continue;
      if (!kind.isBlank() && !kind.equalsIgnoreCase(m.equipment_kind())) continue;
      double cost = m.cost_usd() == null ? 0 : m.cost_usd().total();
      total += cost; n++;
      String key = group.equals("equipment_kind") ? m.equipment_kind() : group.equals("well") ? id : null;
      if (key != null) { double[] x = g.computeIfAbsent(key, k -> new double[2]); x[0]++; x[1] += cost; }
      if (cites.size() < 40) cites.add(m.maintenance_id());
      if (group.equals("none") && rows.size() < MAX_ROWS)
        rows.add(row("maintenance", m.maintenance_id(), "well", id, "well_type", ds.wells.get(id).well_type(), "area", ds.areaNames.getOrDefault(ds.wells.get(id).area_id(), ds.wells.get(id).area_id()),
            "started", s.toString(), "completed", m.completed_at() == null ? null : m.completed_at().atZone(ZoneOffset.UTC).toLocalDate().toString(),
            "status", m.completed_at() == null ? "open" : "closed", "type", m.maintenance_type(), "equipment", m.equipment_kind(),
            "issue", m.issue(), "triggered_by_inspection", m.triggered_by_inspection_id(), "cost_usd", cost, "report", m.report_file()));
    }
    List<ObjectNode> out = rows; List<String> cols = List.of("maintenance", "well", "started", "status", "type", "equipment", "issue", "cost_usd");
    if (!g.isEmpty()) { out = new ArrayList<>(); for (var e : g.entrySet().stream().limit(MAX_ROWS).toList()) { String id = e.getKey(); Well w = ds.wells.get(id); out.add(row("group", id, "well_type", w.well_type(), "area", ds.areaNames.getOrDefault(w.area_id(), w.area_id()), "repairs", (int) e.getValue()[0], "cost_usd", e.getValue()[1])); } cols = List.of("group", "well_type", "area", "repairs", "cost_usd"); }
    int outputCount = g.isEmpty() ? n : g.size();
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("repair_count", n); summary.put("total_cost_usd", Math.round(total * 100) / 100.0); summary.put("window", from + " to " + to);
    if (!g.isEmpty()) summary.put("sample_maintenance_ids", cites);
    return result("repairs", out, summary,
        cites, visual(g.isEmpty() ? "table" : "bar", "Repairs", cols, out), outputCount);
  }

  private Result repairEscalations(JsonNode a, Ctx c) throws Exception {
    LocalDate from = date(a, "from", asOf.minusYears(1)), to = date(a, "to", asOf);
    String requestedEquipment = normalizeEquipment(a.path("equipment_kind").asText(""));
    Map<String, List<Maintenance>> counts = new TreeMap<>();
    for (String id : scope(a)) for (Maintenance m : ds.maintenance.getOrDefault(id, List.of())) {
      LocalDate started = m.started_at().atZone(ZoneOffset.UTC).toLocalDate();
      if (started.isBefore(from) || started.isAfter(to) || !"corrective".equalsIgnoreCase(m.maintenance_type())) continue;
      String equipment = normalizeEquipment(m.equipment_kind());
      if (!requestedEquipment.isBlank() && !requestedEquipment.equals(equipment)) continue;
      counts.computeIfAbsent(id + "\t" + equipment, ignored -> new ArrayList<>()).add(m);
    }
    List<ObjectNode> rows = new ArrayList<>(); List<String> cites = new ArrayList<>();
    for (var entry : counts.entrySet()) {
      String[] key = entry.getKey().split("\t", 2); String wellId = key[0], equipment = key[1];
      Well well = ds.wells.get(wellId);
      RcaThreshold rule = rcaThreshold(well, equipment);
      List<Maintenance> repairs = entry.getValue();
      if (repairs.size() < rule.count()) continue;
      boolean actionRequired = false;
      List<String> ids = new ArrayList<>();
      for (Maintenance m : repairs) {
        ids.add(m.maintenance_id()); cites.add(m.maintenance_id());
        var report = ds.reportFiles.get(m.maintenance_id());
        if (report != null) {
          String text = Files.readString(report);
          if (Pattern.compile("(?is)Manual\\s*§\\s*10.{0,100}Action required|Action required.{0,100}root.cause").matcher(text).find()) actionRequired = true;
        }
      }
      rows.add(row("well", wellId, "well_type", well.well_type(), "area", ds.areaNames.getOrDefault(well.area_id(), well.area_id()),
          "equipment", equipment, "corrective_repairs", repairs.size(), "threshold", rule.count(), "RCA_required", true,
          "RCA_status", actionRequired ? "Action required in repair report" : "Required before next repair is closed",
          "maintenance_ids", String.join(", ", ids), "rule_source", rule.source(), "user_rule", rule.userText()));
      cites.add(rule.source()); if (rule.noteId() != null) cites.add(rule.noteId());
    }
    return result("repair_escalations", rows, Map.of("window", from + " to " + to, "qualifying_well_equipment_pairs", rows.size()), cites,
        visual("table", "Repair escalation obligations", List.of("well", "well_type", "area", "equipment", "corrective_repairs", "threshold", "RCA_status", "maintenance_ids", "rule_source"), rows), rows.size());
  }

  private record RcaThreshold(int count, String source, String noteId, String userText) {}

  private RcaThreshold rcaThreshold(Well well, String equipment) throws Exception {
    for (Overlay.SharedNote note : ov.notesFor(well.asset_id(), well.well_type())) {
      if (!"active_user_rule".equals(note.status()) || thresholdFromText(note.text()) == null) continue;
      String noteEquipment = equipmentFromText(note.text());
      if (!noteEquipment.isBlank() && !noteEquipment.equals(equipment)) continue;
      return new RcaThreshold(thresholdFromText(note.text()), note.id(), note.id(), note.text());
    }
    String filename = well.well_type() + ".md";
    for (var doc : ds.docFiles.entrySet()) if (doc.getKey().equalsIgnoreCase("fleet/well-types/" + filename)) {
      Integer threshold = thresholdFromText(Files.readString(doc.getValue()));
      if (threshold != null) return new RcaThreshold(threshold, doc.getKey(), null, null);
    }
    throw new IllegalStateException("No RCA threshold was found in the " + well.well_type() + " manual.");
  }

  private static Integer thresholdFromText(String text) {
    text = text.replace("**", "");
    Matcher m = Pattern.compile("(?i)\\b(one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+(?:or more\\s+)?(?:corrective\\s+)?(?:maintenance\\s+)?(?:events?|repairs?)\\b").matcher(text);
    if (!m.find()) return null;
    String n = m.group(1).toLowerCase(Locale.ROOT);
    return switch (n) { case "one" -> 1; case "two" -> 2; case "three" -> 3; case "four" -> 4; case "five" -> 5; case "six" -> 6; case "seven" -> 7; case "eight" -> 8; case "nine" -> 9; case "ten" -> 10; default -> Integer.parseInt(n); };
  }

  private static String normalizeEquipment(String value) {
    return value.toLowerCase(Locale.ROOT).trim().replaceAll("[ -]+", "_");
  }

  private static String equipmentFromText(String text) {
    String normalized = normalizeEquipment(text);
    if (normalized.contains("pump_jack")) return "pump_jack";
    if (normalized.contains("flowline")) return "flowline";
    if (normalized.contains("wellhead")) return "wellhead";
    return "";
  }

  private Result regulatory(JsonNode a, Ctx c) {
    LocalDate from = date(a, "from", LocalDate.of(2000, 1, 1)), to = date(a, "to", asOf);
    String et = a.path("event_type").asText("");
    List<ObjectNode> rows = new ArrayList<>(); double fines = 0;
    for (String id : scope(a)) for (RegEvent e : ds.regEvents.getOrDefault(id, List.of())) {
      LocalDate x = LocalDate.parse(e.date().substring(0, 10));
      if (x.isBefore(from) || x.isAfter(to) || (!et.isBlank() && !et.equalsIgnoreCase(e.event_type()))) continue;
      fines += e.fine_usd() == null ? 0 : e.fine_usd();
      rows.add(row("event", e.event_id(), "well", id, "date", x.toString(), "type", e.event_type(), "protocol", e.protocol_id(),
          "inspection", e.related_inspection_id(), "hours_to_report", e.hours_to_report(), "fine_usd", e.fine_usd(), "status", e.status(), "detail", e.detail()));
    }
    return result("events", rows, Map.of("total_fines_usd", fines), rows.stream().map(r -> r.get("event").asText()).toList(),
        visual("table", "Regulator events", List.of("event", "well", "date", "type", "protocol", "hours_to_report", "fine_usd", "status"), rows));
  }

  private Result notes(JsonNode a, Ctx c) {
    String id = a.path("well_id").asText().toUpperCase();
    if (!ds.wells.containsKey(id)) throw new IllegalArgumentException("Unknown well " + id);
    List<ObjectNode> rows = new ArrayList<>();
    for (Note n : ds.notes.getOrDefault(id, List.of()))
      rows.add(row("note", n.note_id(), "kind", n.note_type(), "author", name(n.author_user_id()), "date", n.created_at().toString().substring(0, 10),
          "inspection", n.inspection_id(), "valid_from", n.valid_from(), "valid_to", n.valid_to(), "text", n.text()));
    for (Overlay.SharedNote n : ov.notesFor(id, ds.wells.get(id).well_type()))
      rows.add(row("note", n.id(), "kind", "chat_note/" + n.status(), "scope", n.scope(), "author", name(n.authorUserId()), "date", n.at().toString().substring(0, 10), "text", n.text()));
    for (Inspection inspection : ds.inspections.getOrDefault(id, List.of()))
      for (Overlay.Correction x : ov.correctionsForInspection(inspection.inspection_id()))
        rows.add(row("note", x.id(), "kind", "chat_correction", "author", name(x.authorUserId()), "inspection", x.inspectionId(),
            "text", x.field() + ": original " + x.originalValue() + " -> " + x.newValue() + ". Reason: " + x.reason()));
    return result("notes", rows, Map.of(), rows.stream().map(r -> r.get("note").asText()).toList(), visual("table", "Notes", List.of("note", "kind", "scope", "author", "date", "text"), rows));
  }

  private Result search(JsonNode a, Ctx c) {
    String well = a.hasNonNull("well_id") ? a.get("well_id").asText().toUpperCase() : null;
    List<ObjectNode> rows = new ArrayList<>(); List<String> cites = new ArrayList<>();
    int k = Math.max(0, Math.min(a.path("k").asInt(5), MAX_ROWS));
    for (DocIndex.Hit h : docs.search(a.path("query").asText(), well, k)) {
      String t = h.chunk().text(); if (t.length() > 900) t = t.substring(0, 900) + "...";
      rows.add(row("source", h.chunk().ref(), "text", t)); cites.add(h.chunk().ref());
    }
    return result("passages", rows, Map.of(), cites, null);
  }

  private Result searchRecords(JsonNode a, Ctx c) {
    String query=a.path("query").asText("").trim().toLowerCase(Locale.ROOT);
    String requested=a.path("entity_type").asText("").trim().toLowerCase(Locale.ROOT).replace('-','_');
    if(requested.endsWith("ies"))requested=requested.substring(0,requested.length()-3)+"y";else if(requested.endsWith("s")&&!requested.endsWith("ss"))requested=requested.substring(0,requested.length()-1);
    int limit=Math.max(1,Math.min(a.path("limit").asInt(20),MAX_ROWS));
    List<JsonNode> matching=new ArrayList<>();List<String> citations=new ArrayList<>();int matches=0;
    if(query.isBlank()){
      for(var collection:ds.importedRecords.entrySet())if(requested.isBlank()||collection.getKey().equals(requested))matches+=collection.getValue().size();
      outer:for(var collection:ds.importedRecords.entrySet())if(requested.isBlank()||collection.getKey().equals(requested))for(JsonNode record:collection.getValue()){ObjectNode row=M().createObjectNode().put("entity_type",collection.getKey());row.set("record",record);matching.add(row);if(matching.size()>=limit)break outer;}
    }else for(var collection:ds.importedRecords.entrySet()){
      if(!requested.isBlank()&&!collection.getKey().equals(requested))continue;
      for(JsonNode record:collection.getValue())if(record.toString().toLowerCase(Locale.ROOT).contains(query)){matches++;ObjectNode row=M().createObjectNode().put("entity_type",collection.getKey());row.set("record",record);matching.add(row);}
    }
    List<JsonNode> ordered=query.isBlank()?matching:rankAndSpread(matching,query);
    List<ObjectNode> rows=new ArrayList<>();for(JsonNode row:ordered.stream().limit(limit).toList()){rows.add((ObjectNode)row);JsonNode record=row.path("record");for(String key:List.of("record_id","id","event_id","inspection_id","maintenance_id","note_id","asset_id","well_id","area_id"))if(record.hasNonNull(key)){citations.add(record.get(key).asText());break;}}
    ObjectNode output=M().createObjectNode().put("query",query).put("row_count",matches).put("limit",limit);ArrayNode found=output.putArray("records");rows.forEach(found::add);if(matches==0)output.put("note","No matching records in the imported non-standard collections.");
    return new Result(output,citations,null);
  }

  private Result searchEverywhere(JsonNode a,Ctx c){
    String query=a.path("query").asText("").trim();int limit=Math.max(1,Math.min(a.path("limit").asInt(40),MAX_ROWS));List<String> terms=searchTerms(query);
    if(terms.isEmpty())throw new IllegalArgumentException("Give one or more meaningful words, a well ID, or a phrase to search.");
    List<JsonNode> matching=new ArrayList<>();Map<String,Integer> byType=new TreeMap<>(),byArea=new TreeMap<>();int total=0;
    for(var collection:ds.importedRecords.entrySet())for(JsonNode record:collection.getValue()){
      int score=searchScore(record.toString(),terms);if(score==0)continue;total++;byType.merge(collection.getKey(),1,Integer::sum);
      String area=text(queryField(record,"area"));if(area.isBlank())area="(area not linked)";byArea.merge(area,1,Integer::sum);
      ObjectNode hit=M().createObjectNode().put("entity_type",collection.getKey()).put("area",area).put("relevance",score);hit.set("record",record);matching.add(hit);
    }
    List<JsonNode> ranked=rankAndSpread(matching,query);ArrayNode records=M().createArrayNode();List<String> citations=new ArrayList<>();
    for(JsonNode hit:ranked.stream().limit(limit).toList()){
      records.add(hit);JsonNode record=hit.path("record");for(String key:List.of("record_id","id","event_id","inspection_id","maintenance_id","note_id","asset_id","well_id","area_id"))if(record.hasNonNull(key)){citations.add(record.get(key).asText());break;}
    }
    Map<String,DocIndex.Hit> docHits=new LinkedHashMap<>();String expanded=expandedSearchQuery(terms);
    for(DocIndex.Hit h:docs.search(expanded,null,Math.min(30,Math.max(10,limit))))docHits.put(h.chunk().ref(),h);
    List<DocIndex.Hit> docsFound=docHits.values().stream().sorted(Comparator.comparingDouble(DocIndex.Hit::score).reversed()).limit(20).toList();ArrayNode documents=M().createArrayNode();
    for(DocIndex.Hit h:docsFound){String excerpt=h.chunk().text();if(excerpt.length()>650)excerpt=excerpt.substring(0,650)+"…";ObjectNode doc=documents.addObject().put("source",h.chunk().ref()).put("well_id",h.chunk().wellId()==null?"":h.chunk().wellId()).put("text",excerpt);citations.add(h.chunk().ref());}
    ObjectNode out=M().createObjectNode().put("query",query).put("search_scope","all imported record types, every area, and all indexed documents").put("matching_record_count",total).put("returned_record_count",records.size()).set("matching_records_by_type",M().valueToTree(byType));
    out.set("matching_records_by_area",M().valueToTree(byArea));out.set("records",records);out.put("returned_document_passages",documents.size()).set("document_passages",documents);
    if(total==0&&documents.isEmpty())out.put("note","No related record or document matches were found in the selected database.");
    return new Result(out,citations.stream().distinct().toList(),null);
  }

  private Result datasetCatalog(JsonNode a, Ctx c) {
    ObjectNode out=M().createObjectNode();ArrayNode entities=out.putArray("entities");
    for(var entry:ds.importedRecords.entrySet()){
      ObjectNode entity=entities.addObject().put("entity_type",entry.getKey()).put("row_count",entry.getValue().size());
      TreeSet<String> paths=new TreeSet<>();for(JsonNode row:entry.getValue())collectPaths(row,"",paths,3);
      if(entry.getKey().equals("inspection")||entry.getKey().equals("maintenance")||entry.getKey().equals("note")||entry.getKey().equals("regulatory_event")){paths.add("area_id (joined from asset_id)");paths.add("area (joined from asset_id)");paths.add("well_type (joined from asset_id)");}
      ArrayNode fields=entity.putArray("fields");paths.forEach(fields::add);
      if(!entry.getValue().isEmpty())entity.set("example",entry.getValue().get(0));
    }
    return new Result(out,List.of(),null);
  }

  private static void collectPaths(JsonNode node,String prefix,Set<String> paths,int depth){
    if(node==null||depth<0)return;
    if(node.isObject()){node.fields().forEachRemaining(e->{String p=prefix.isEmpty()?e.getKey():prefix+"."+e.getKey();paths.add(p);if(e.getValue().isContainerNode())collectPaths(e.getValue(),p,paths,depth-1);});}
    else if(node.isArray()&&!node.isEmpty())collectPaths(node.get(0),prefix+"[]",paths,depth-1);
  }

  private Result queryRecords(JsonNode a,Ctx c){
    String type=a.path("entity_type").asText("").trim().toLowerCase(Locale.ROOT).replace('-','_');
    if(type.isBlank())throw new IllegalArgumentException("Choose entity_type from dataset_catalog.");
    if(type.endsWith("ies"))type=type.substring(0,type.length()-3)+"y";else if(type.endsWith("s")&&!type.endsWith("ss"))type=type.substring(0,type.length()-1);
    List<JsonNode> source=ds.importedRecords.get(type);if(source==null)throw new IllegalArgumentException("Unknown entity_type '"+type+"'. Call dataset_catalog to see available collections.");
    String well=a.path("well_id").asText("").trim().toLowerCase(Locale.ROOT),area=a.path("area").asText("").trim(),search=a.path("search").asText("").trim().toLowerCase(Locale.ROOT);
    String field=a.path("field").asText(""),op=a.path("operator").asText("eq"),value=a.path("value").asText("");
    String dateField=a.path("date_field").asText(""),from=a.path("from").asText(""),to=a.path("to").asText("");
    List<JsonNode> found=new ArrayList<>();
    for(JsonNode row:source){
      String rowWell=firstText(row,"well_id","asset_id");if(!well.isBlank()&&!rowWell.equalsIgnoreCase(well))continue;
      if(!area.isBlank()&&!text(queryField(row,"area")).equalsIgnoreCase(area)&&!text(queryField(row,"area_id")).equalsIgnoreCase(area))continue;
      if(!search.isBlank()&&!row.toString().toLowerCase(Locale.ROOT).contains(search))continue;
      if(!field.isBlank()&&!matchesValue(queryField(row,field),op,value))continue;
      if(!from.isBlank()||!to.isBlank()){
        String date=dateField.isBlank()?firstText(row,"date","event_date","created_at","inspected_at","started_at"):text(queryField(row,dateField));
        if(date.isBlank())continue;String day=date.length()>=10?date.substring(0,10):date;
        if(!from.isBlank()&&day.compareTo(from.substring(0,Math.min(10,from.length())))<0)continue;
        if(!to.isBlank()&&day.compareTo(to.substring(0,Math.min(10,to.length())))>0)continue;
      }
      found.add(row);
    }
    String groupBy=a.path("group_by").asText(""),aggregate=a.path("aggregate").asText(""),aggregateField=a.path("aggregate_field").asText("");
    if(!groupBy.isBlank()){
      Map<String,double[]> groups=new TreeMap<>();for(JsonNode row:found){String key=text(queryField(row,groupBy));if(key.isBlank())key="(missing)";double[] acc=groups.computeIfAbsent(key,k->new double[2]);acc[0]++;if(!aggregateField.isBlank())try{acc[1]+=Double.parseDouble(text(queryField(row,aggregateField)));}catch(Exception ignored){}}
      ObjectNode out=M().createObjectNode().put("entity_type",type).put("matched_records",found.size());ArrayNode rows=out.putArray("groups");
      List<Map.Entry<String,double[]>> grouped=new ArrayList<>(groups.entrySet());if(a.path("descending").asBoolean(false))grouped.sort((x,y)->Double.compare(metric(y.getValue(),aggregate),metric(x.getValue(),aggregate)));else if(!aggregate.isBlank())grouped.sort((x,y)->Double.compare(metric(x.getValue(),aggregate),metric(y.getValue(),aggregate)));
      for(var e:grouped){ObjectNode r=rows.addObject().put("group",e.getKey()).put("count",(long)e.getValue()[0]);if(aggregate.equals("sum"))r.put("sum",e.getValue()[1]);else if(aggregate.equals("avg"))r.put("average",e.getValue()[0]==0?0:e.getValue()[1]/e.getValue()[0]);}
      return new Result(out,List.of(),null);
    }
    String sort=a.path("sort_by").asText("");if(!sort.isBlank()){Comparator<JsonNode> cmp=(x,y)->compareValues(text(queryField(x,sort)),text(queryField(y,sort)));found.sort(a.path("descending").asBoolean()?cmp.reversed():cmp);}
    else if(!search.isBlank())found=rankAndSpread(found,search);
    int limit=Math.max(1,Math.min(a.path("limit").asInt(30),MAX_ROWS));ObjectNode out=M().createObjectNode().put("entity_type",type).put("matched_records",found.size()).put("returned_records",Math.min(found.size(),limit));ArrayNode rows=out.putArray("records");
    for(int i=0;i<Math.min(limit,found.size());i++)rows.add(found.get(i));
    return new Result(out,List.of(),null);
  }

  private static JsonNode path(JsonNode root,String field){JsonNode n=root;for(String part:field.split("\\.")){if(n==null)return null;if(part.endsWith("[]")){n=n.path(part.substring(0,part.length()-2));if(n.isArray())n=n.isEmpty()?null:n.get(0);}else n=n.path(part);}return n;}
  private JsonNode queryField(JsonNode row,String field){
    if(field.equalsIgnoreCase("area")||field.equalsIgnoreCase("area_id")||field.equalsIgnoreCase("area_name")||field.equalsIgnoreCase("well_type")){
      String id=firstText(row,"well_id","asset_id");Well well=ds.wells.get(id);if(well!=null){if(field.equalsIgnoreCase("well_type"))return M().getNodeFactory().textNode(well.well_type());if(field.equalsIgnoreCase("area_id"))return M().getNodeFactory().textNode(well.area_id());return M().getNodeFactory().textNode(ds.areaNames.getOrDefault(well.area_id(),well.area_id()));}
    }
    return path(row,field);
  }
  private static String text(JsonNode n){return n==null||n.isMissingNode()||n.isNull()?"":n.isValueNode()?n.asText():n.toString();}
  private static int compareValues(String x,String y){try{return Double.compare(Double.parseDouble(x),Double.parseDouble(y));}catch(Exception ignored){return x.compareToIgnoreCase(y);}}
  private static double metric(double[] values,String aggregate){return aggregate.equals("sum")?values[1]:aggregate.equals("avg")?(values[0]==0?0:values[1]/values[0]):values[0];}
  private List<JsonNode> rankAndSpread(List<JsonNode> rows,String query){
    List<String> terms=searchTerms(query);Map<String,List<JsonNode>> byArea=new TreeMap<>();
    for(JsonNode row:rows){JsonNode data=row.has("record")?row.get("record"):row;String area=text(queryField(data,"area"));if(area.isBlank())area="(area not linked)";byArea.computeIfAbsent(area,k->new ArrayList<>()).add(row);}
    Comparator<JsonNode> relevance=(x,y)->Integer.compare(relevance(y.has("record")?y.get("record"):y,terms),relevance(x.has("record")?x.get("record"):x,terms));byArea.values().forEach(xs->xs.sort(relevance));
    List<JsonNode> out=new ArrayList<>();int depth=0;boolean remaining=true;while(remaining){remaining=false;for(List<JsonNode> xs:byArea.values())if(depth<xs.size()){out.add(xs.get(depth));remaining=true;}depth++;}return out;
  }
  private static final Set<String> SEARCH_STOP_WORDS=Set.of("which","what","who","when","where","why","how","the","a","an","and","or","of","to","in","on","at","by","for","from","with","across","all","our","me","show","map","data","field","well","wells","does","did","do","is","are","was","were","has","have","had","most","much","many","every","any","current","right","now");
  private static List<String> searchTerms(String query){return DocIndex.tok(query).stream().map(Tools::stem).filter(t->t.length()>1&&!SEARCH_STOP_WORDS.contains(t)).distinct().limit(12).toList();}
  private static String stem(String token){String t=token.toLowerCase(Locale.ROOT);if(t.length()>6&&t.endsWith("tion"))return t.substring(0,t.length()-4);if(t.length()>5&&t.endsWith("ing"))return t.substring(0,t.length()-3);if(t.length()>4&&t.endsWith("ed"))return t.substring(0,t.length()-2);if(t.length()>4&&t.endsWith("ies"))return t.substring(0,t.length()-3)+"y";if(t.length()>4&&t.endsWith("s"))return t.substring(0,t.length()-1);return t;}
  private static int searchScore(String text,List<String> terms){Set<String> tokens=new HashSet<>();for(String token:DocIndex.tok(text))tokens.add(stem(token));int score=0;for(String term:terms)if(tokens.contains(term))score+=term.matches("20\\d{2}")?1:3;return score;}
  private static int relevance(JsonNode row,List<String> terms){return searchScore(row.toString(),terms);}
  private static String expandedSearchQuery(List<String> terms){LinkedHashSet<String> expanded=new LinkedHashSet<>();for(String term:terms){expanded.add(term);if(term.length()>4){expanded.add(term+"ed");expanded.add(term+"ing");expanded.add(term+"tion");}}return String.join(" ",expanded.stream().limit(24).toList());}
  private static String firstText(JsonNode n,String...keys){for(String k:keys){String s=text(n.path(k));if(!s.isBlank())return s;}return "";}
  private static boolean matchesValue(JsonNode node,String op,String expected){String actual=text(node);if(actual.isBlank())return false;if(op.equals("contains"))return actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));if(op.equals("eq"))return actual.equalsIgnoreCase(expected);try{double x=Double.parseDouble(actual),y=Double.parseDouble(expected);return switch(op){case "gt"->x>y;case "gte"->x>=y;case "lt"->x<y;case "lte"->x<=y;default->false;};}catch(NumberFormatException e){return false;}}

  private static final Map<String, Overlay.Field> FIELDS = Map.of("wellhead_temp_c", Overlay.Field.WELLHEAD_TEMP_C,
      "ambient_temp_c", Overlay.Field.AMBIENT_TEMP_C, "spill_volume_bbl", Overlay.Field.SPILL_VOLUME_BBL);

  private Result correct(JsonNode a, Ctx c) {
    String iid = a.path("inspection_id").asText("").trim();
    Overlay.Field f = FIELDS.get(a.path("field").asText().toLowerCase());
    if (f == null) throw new IllegalArgumentException("field must be one of " + FIELDS.keySet());
    if (iid.isBlank()) {
      String wellId = a.path("well_id").asText("").trim().toUpperCase(Locale.ROOT);
      if (wellId.isBlank() || !ds.wells.containsKey(wellId)) throw new IllegalArgumentException("Provide a valid well_id and date, or the inspection_id.");
      LocalDate target = correctionDate(a.path("date").asText(""));
      List<Inspection> matches = ds.inspections.getOrDefault(wellId, List.of()).stream().filter(i -> Domain.day(i).equals(target)).toList();
      if (matches.size() != 1) {
        String found = matches.stream().map(Inspection::inspection_id).toList().toString();
        throw new IllegalArgumentException(matches.isEmpty() ? "No inspection for " + wellId + " on " + target + "." : "Multiple inspections for " + wellId + " on " + target + ": " + found + ". Provide an inspection_id.");
      }
      iid = matches.get(0).inspection_id();
    }
    Inspection ins = ds.inspectionById.get(iid);
    if (ins == null) throw new IllegalArgumentException("Unknown inspection " + iid);
    Domain.Effective before = d.effective(ins);
    String orig = String.valueOf(f == Overlay.Field.WELLHEAD_TEMP_C ? ins.wellhead_temp_c() : f == Overlay.Field.AMBIENT_TEMP_C ? ins.ambient_temp_c() : ins.spill_est_volume_bbl());
    String correctionId = ov.nextCorrectionId();
    ov.add(new Overlay.Correction(correctionId, iid, f, orig, a.path("new_value").asText(), c.userId(), a.path("reason").asText(), Instant.now(), "chat"));
    Domain.Effective after = d.effective(ins);
    Well w = ds.wells.get(ins.asset_id());
    ObjectNode o = M().createObjectNode();
    o.put("recorded", true).put("inspection", iid).put("inspected_at", ins.inspected_at().toString())
        .put("inspector", name(ins.inspector_user_id())).put("original_value", orig).put("new_value", a.path("new_value").asText())
        .put("reason", a.path("reason").asText()).put("author", name(c.userId()));
    o.put("producing_before", d.producing(w.asset_id(), before)).put("producing_after", d.producing(w.asset_id(), after)).put("delta_c_after", Math.round(after.delta() * 10) / 10.0);
    o.put("required_delta_c", ds.productionRules.get(w.well_type()).value());
    o.put("spill_volume_after_bbl", after.spillBbl() == null ? null : after.spillBbl());
    if (f == Overlay.Field.SPILL_VOLUME_BBL && after.spillBbl() != null && after.spillBbl() >= 5) {
      final String resolvedInspectionId = iid;
      List<RegEvent> related = ds.regEvents.getOrDefault(w.asset_id(), List.of()).stream()
          .filter(e -> resolvedInspectionId.equals(e.related_inspection_id()) && ("SR-01".equalsIgnoreCase(e.protocol_id()) || e.event_type().toLowerCase(Locale.ROOT).contains("spill_report") || e.event_type().toLowerCase(Locale.ROOT).contains("notice"))).toList();
      var events = M().createArrayNode();
      for (RegEvent e : related) events.add(row("event", e.event_id(), "date", e.date(), "type", e.event_type(), "protocol", e.protocol_id(), "status", e.status(), "detail", e.detail()));
      o.put("reportability", "Reportable under the Spill Response SOP at 5 bbl or more.");
      o.put("sr01_written_notice_deadline_utc", ins.inspected_at().plus(Duration.ofHours(24)).toString());
      o.put("sr01_related_notices_recorded", !related.isEmpty()).set("related_sr01_events", events);
      o.put("possible_sr01_breach", related.isEmpty() ? "No related SR-01 filing or notice is recorded; written notice was due within 24 hours of discovery." : "Review the related SR-01 events against the 24-hour deadline.");
      o.put("sr03_agency_verification_deadline", Domain.day(ins).plusDays(60).toString());
      o.put("sop_source", "fleet/sops/spill-response-sop.md");
    }
    if (ins.notes() != null && !ins.notes().isBlank()) o.put("inspector_note_on_same_inspection", ins.notes());
    List<String> citations = new ArrayList<>(List.of(iid, correctionId));
    if (f == Overlay.Field.SPILL_VOLUME_BBL) for (Maintenance m : ds.maintenance.getOrDefault(w.asset_id(), List.of())) {
      if (!iid.equals(m.triggered_by_inspection_id())) continue;
      var report = ds.reportFiles.get(m.maintenance_id());
      if (report == null) continue;
      citations.add(m.maintenance_id());
      o.put("linked_report", m.maintenance_id());
      try {
        List<String> evidence = Files.readAllLines(report).stream()
            .filter(line -> line.matches("(?i).*estimated.*bbl.*") || line.matches("(?i).*contained about.*") || line.matches("(?i).*\\|\\s*(SR-0[1234]|HSE notice).*"))
            .limit(12).toList();
        o.set("linked_report_evidence", M().valueToTree(evidence));
      } catch (java.io.IOException ignored) { }
      break;
    }
    if (f == Overlay.Field.SPILL_VOLUME_BBL) citations.add("fleet/sops/spill-response-sop.md");
    o.put("instruction", "Tell the user the correction is stored for everyone and the source estimate is preserved. If the inspector note conflicts, state the note and ask for confirmation. For a reportable spill, report the 24-hour SR-01 status and 60-day SR-03 deadline from this result, and explain when the linked report's classification or compliance check used the old volume.");
    return new Result(o, citations, null);
  }

  private LocalDate correctionDate(String value) {
    String s = value == null ? "" : value.trim();
    if (s.isBlank()) throw new IllegalArgumentException("Provide a date with the well_id to resolve an inspection.");
    try { return LocalDate.parse(s); } catch (java.time.format.DateTimeParseException ignored) { }
    java.time.format.DateTimeFormatter[] formats = {
        java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH),
        java.time.format.DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH),
        java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH),
        java.time.format.DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH),
        java.time.format.DateTimeFormatter.ofPattern("MM-dd", Locale.ENGLISH),
        java.time.format.DateTimeFormatter.ofPattern("MM/dd", Locale.ENGLISH)
    };
    for (var formatter : formats) try {
      java.time.temporal.TemporalAccessor parsed = formatter.parse(s);
      int month = parsed.isSupported(java.time.temporal.ChronoField.MONTH_OF_YEAR) ? parsed.get(java.time.temporal.ChronoField.MONTH_OF_YEAR) : 0;
      int day = parsed.isSupported(java.time.temporal.ChronoField.DAY_OF_MONTH) ? parsed.get(java.time.temporal.ChronoField.DAY_OF_MONTH) : 0;
      if (month > 0 && day > 0) {
        LocalDate date = LocalDate.of(asOf.getYear(), month, day);
        return date.isAfter(asOf) ? date.minusYears(1) : date;
      }
    } catch (java.time.DateTimeException ignored) { }
    throw new IllegalArgumentException("Could not parse date '" + s + "'. Use YYYY-MM-DD or a date such as 24 Sep.");
  }

  private Result note(JsonNode a, Ctx c) {
    String scope = a.path("scope").asText();
    if (!scope.startsWith("type:") && !ds.wells.containsKey(scope.toUpperCase())) throw new IllegalArgumentException("scope must be a well id or type:<TYPE>");
    String sc = scope.startsWith("type:") ? scope : scope.toUpperCase();
    String id = ov.nextNoteId();
    String noteText = a.path("text").asText();
    boolean explicitRcaRule = Pattern.compile("(?is).*(?:root[ -]?cause|\\bRCA\\b).*").matcher(noteText).matches()
        && thresholdFromText(noteText) != null;
    String status = explicitRcaRule ? "active_user_rule" : "unconfirmed";
    ov.addNote(new Overlay.SharedNote(id, sc, c.userId(), noteText, status, Instant.now()));
    ObjectNode o = M().createObjectNode().put("recorded", true).put("note_id", id).put("status", status).put("author", name(c.userId()));
    if (explicitRcaRule) o.put("rule_override", true).put("scope", sc);
    if (!sc.startsWith("type:")) { d.latestInspection(sc).ifPresent(i -> { Domain.Effective e = d.effective(i);
      o.put("current_data_producing", d.producing(sc, e)).put("latest_inspection", i.inspection_id()).put("delta_c", Math.round(e.delta() * 10) / 10.0); }); }
    if (explicitRcaRule) o.put("instruction", "This explicit scoped RCA threshold is active for matching equipment and supersedes the manual threshold. Cite the note and identify it as the user's rule.");
    else if (sc.startsWith("type:")) o.put("instruction", "Confirm this statement was stored as a shared unconfirmed note; do not treat it as a policy override.");
    else o.put("instruction", "Compare the user's statement with current_data_producing. If they contradict, say so explicitly, keep it unconfirmed and ask for confirmation. Otherwise confirm it is recorded and shared.");
    return new Result(o, List.of(id), null);
  }
}
