package com.flytbase.copilot.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.flytbase.copilot.core.Models.*;
import java.io.IOException;
import java.io.BufferedReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Schema-driven loader. Discovers wells by scanning assets/* (no hard-coded IDs) and reads the
 * temperature thresholds out of the well-type manuals, so a new dataset with new types just works.
 * Several datasets can be loaded into one instance (sample + test).
 */
public final class Dataset {
  public static final ObjectMapper M = JsonMapper.builder()
      .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();

  public final Map<String, Well> wells = new TreeMap<>();
  public final Map<String, List<Inspection>> inspections = new HashMap<>();
  /** Direct lookup for correction requests; avoids rescanning the full inspection history. */
  public final Map<String, Inspection> inspectionById = new HashMap<>();
  private final Map<String, List<String>> wellsByArea = new HashMap<>();
  public final Map<String, List<Maintenance>> maintenance = new HashMap<>();
  public final Map<String, List<RegEvent>> regEvents = new HashMap<>();
  public final Map<String, List<Note>> notes = new HashMap<>();
  public final Map<String, User> users = new LinkedHashMap<>();
  public final Map<String, String> areaNames = new LinkedHashMap<>();
  /** Complete JSON payloads for imported collections that do not match a built-in entity type. */
  public final Map<String, List<JsonNode>> additionalRecords = new LinkedHashMap<>();
  /** Original SQL snapshots, including fields outside the built-in Java model. */
  public final Map<String, List<JsonNode>> importedRecords = new LinkedHashMap<>();
  public final List<String> importWarnings = new ArrayList<>();
  /** well_type -> °C above ambient required to count as producing, with the manual it came from. */
  public final Map<String, Rule> productionRules = new HashMap<>();
  public final Map<String, Path> reportFiles = new HashMap<>(); // maintenance_id -> report path
  /** display path -> file, for every manual, SOP and repair report (the searchable document set). */
  public final Map<String, Path> docFiles = new LinkedHashMap<>();

  public record Rule(String id, double value, String source) {}

  public static Dataset load(Path root) throws IOException {
    Dataset d = new Dataset();
    d.addRoot(root);
    return d;
  }

  public void addRoot(Path root) throws IOException {
    Path fleet = root.resolve("fleet");
    readKnownCollection(fleet.resolve("users.json"), "users", User.class, n -> {
      if (n.hasNonNull("user_id")) users.put(n.path("user_id").asText(), M.treeToValue(n, User.class));
    });
    readKnownCollection(fleet.resolve("areas.json"), "areas", JsonNode.class, n -> {
      if (n.hasNonNull("area_id")) areaNames.put(n.path("area_id").asText(), n.path("name").asText(""));
    });
    Path types = fleet.resolve("well-types");
    if (Files.isDirectory(types))
      try (Stream<Path> s = Files.list(types)) {
        for (Path p : s.filter(x -> x.toString().toLowerCase(Locale.ROOT).endsWith(".md")).toList()) try { parseManual(p); docFiles.put(root.relativize(p).toString().replace('\\','/'), p); } catch (Exception e) { importWarnings.add("Could not read manual " + p.getFileName() + ": " + e.getMessage()); }
      }
    Path sops = fleet.resolve("sops");
    if (Files.isDirectory(sops))
      try (Stream<Path> s = Files.list(sops)) {
        for (Path p : s.filter(x -> x.toString().toLowerCase(Locale.ROOT).endsWith(".md")).sorted().toList()) docFiles.put(root.relativize(p).toString().replace('\\','/'), p);
      }
    Path assets = root.resolve("assets");
    if (Files.isDirectory(assets)) try (Stream<Path> s = Files.list(assets)) {
      for (Path dir : s.filter(Files::isDirectory).sorted().toList()) try { loadWell(dir); } catch (Exception e) { importWarnings.add("Skipped incomplete asset folder " + dir.getFileName() + ": " + e.getMessage()); }
    }
    // Index documents recursively so added SOPs, manuals, and report files are not tied to fixed names.
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.filter(Files::isRegularFile).filter(x -> x.toString().toLowerCase(Locale.ROOT).endsWith(".md")).toList())
        docFiles.putIfAbsent(root.relativize(p).toString().replace('\\','/'), p);
    }
  }

  @FunctionalInterface private interface JsonConsumer { void accept(JsonNode node) throws Exception; }
  private <T> void readKnownCollection(Path path, String wrapper, Class<T> type, JsonConsumer consumer) {
    if (!Files.isRegularFile(path)) return;
    try {
      JsonNode root = M.readTree(path.toFile()); JsonNode rows = root.isArray() ? root : root.path(wrapper);
      if (!rows.isArray() && root.isObject()) for (JsonNode value : iterable(root)) if (value.isArray()) { rows = value; break; }
      if (rows.isArray()) for (JsonNode row : rows) try { consumer.accept(row); } catch (Exception e) { importWarnings.add("Skipped malformed row in " + path.getFileName() + ": " + e.getMessage()); }
      else if (root.isObject()) try { consumer.accept(root); } catch (Exception e) { importWarnings.add("Skipped malformed row in " + path.getFileName() + ": " + e.getMessage()); }
    } catch (Exception e) { importWarnings.add("Could not read " + path.getFileName() + ": " + e.getMessage()); }
  }
  private static Iterable<JsonNode> iterable(JsonNode node) { List<JsonNode> values=new ArrayList<>();node.fields().forEachRemaining(e->values.add(e.getValue()));return values; }

  private static final Pattern THRESH = Pattern.compile(
      "at least\\s+\\*\\*(\\d+(?:\\.\\d+)?)\\s*°C\\s+above\\s+ambient", Pattern.CASE_INSENSITIVE);

  private void parseManual(Path p) throws IOException {
    String type = p.getFileName().toString().replace(".md", "");
    String text = Files.readString(p).replaceAll("\\s+", " ").replace("** ", "**");
    // Manuals wrap bold markers across lines; normalise whitespace first.
    Matcher m = THRESH.matcher(text.replace("**producing (active)** only when its wellhead temperature is at least **", "at least **")
        .replace("**producing (active)** when its wellhead temperature is at least **", "at least **"));
    if (m.find()) productionRules.put(type, new Rule("production-temp-delta", Double.parseDouble(m.group(1)),
        "fleet/well-types/" + type + ".md (production temperature rule)"));
  }

  private void loadWell(Path dir) throws IOException {
    Path meta = dir.resolve("metadata.json");
    if (!Files.exists(meta)) return;
    Well w = M.readValue(meta.toFile(), Well.class);
    if (w.asset_id() == null || w.asset_id().isBlank()) throw new IOException("metadata is missing asset_id");
    wells.put(w.asset_id(), w);
    List<Inspection> wellInspections = readLines(dir.resolve("inspections.jsonl"), Inspection.class);
    inspections.put(w.asset_id(), wellInspections);
    for (Inspection i : wellInspections) inspectionById.put(i.inspection_id(), i);
    if (w.area_id() != null) wellsByArea.computeIfAbsent(w.area_id(), ignored -> new ArrayList<>()).add(w.asset_id());
    List<Maintenance> mnt = readLines(dir.resolve("maintenance.jsonl"), Maintenance.class);
    maintenance.put(w.asset_id(), mnt);
    for (Maintenance x : mnt)
      if (x.report_file() != null && !x.report_file().isBlank()) {
        Path rp = dir.resolve(x.report_file()); // file names vary: always follow report_file
        if (!Files.exists(rp)) rp = dir.resolve("maintenance-reports").resolve(Path.of(x.report_file()).getFileName());
        if (Files.exists(rp)) { reportFiles.put(x.maintenance_id(), rp); docFiles.put("assets/" + w.asset_id() + "/maintenance-reports/" + rp.getFileName(), rp); }
      }
    regEvents.put(w.asset_id(), readLines(dir.resolve("regulatory-events.jsonl"), RegEvent.class));
    notes.put(w.asset_id(), readLines(dir.resolve("context.jsonl"), Note.class));
    inspections.get(w.asset_id()).sort(Comparator.comparing(Inspection::inspected_at, Comparator.nullsLast(Comparator.naturalOrder())));
  }

  private static <T> List<T> readLines(Path p, Class<T> c) throws IOException {
    List<T> out = new ArrayList<>();
    if (!Files.exists(p)) return out;
    try (BufferedReader reader = Files.newBufferedReader(p)) {
      String line;
      int number=0; while ((line = reader.readLine()) != null) { number++; if (!line.isBlank()) try { out.add(M.readValue(line, c)); } catch (Exception e) { /* one bad row must not discard neighboring rows */ } }
    }
    return out;
  }

  public List<String> wellsForArea(String areaId) {
    return List.copyOf(wellsByArea.getOrDefault(areaId, List.of()));
  }

  /** Replaces operational records with the SQL snapshot; filesystem files remain document sources only. */
  public void clearStructuredRecords() {
    wells.clear(); inspections.clear(); inspectionById.clear(); wellsByArea.clear(); maintenance.clear();
    regEvents.clear(); notes.clear(); users.clear(); areaNames.clear(); additionalRecords.clear(); importedRecords.clear();
  }

  public void addSqlRecord(String type, String json) throws IOException {
    JsonNode n = M.readTree(json);
    importedRecords.computeIfAbsent(type,k->new ArrayList<>()).add(n);
    switch (type) {
      case "well" -> { Well x=M.treeToValue(n,Well.class); if(x.asset_id()!=null){wells.put(x.asset_id(),x);if(x.area_id()!=null)wellsByArea.computeIfAbsent(x.area_id(),k->new ArrayList<>()).add(x.asset_id());} }
      case "inspection" -> { Inspection x=M.treeToValue(n,Inspection.class); if(x.asset_id()!=null)inspections.computeIfAbsent(x.asset_id(),k->new ArrayList<>()).add(x); if(x.inspection_id()!=null)inspectionById.put(x.inspection_id(),x); }
      case "maintenance" -> { Maintenance x=M.treeToValue(n,Maintenance.class); maintenance.computeIfAbsent(x.asset_id(),k->new ArrayList<>()).add(x); }
      case "regulatory_event" -> { RegEvent x=M.treeToValue(n,RegEvent.class); regEvents.computeIfAbsent(x.asset_id(),k->new ArrayList<>()).add(x); }
      case "note" -> { Note x=M.treeToValue(n,Note.class); notes.computeIfAbsent(x.asset_id(),k->new ArrayList<>()).add(x); }
      case "user" -> { User x=M.treeToValue(n,User.class); users.put(x.user_id(),x); }
      case "area" -> areaNames.put(n.path("area_id").asText(),n.path("name").asText());
      default -> additionalRecords.computeIfAbsent(type,k->new ArrayList<>()).add(n);
    }
  }

  public void finishSqlLoad() {
    inspections.values().forEach(xs->xs.sort(Comparator.comparing(Inspection::inspected_at,Comparator.nullsLast(Comparator.naturalOrder()))));
    maintenance.values().forEach(xs->xs.sort(Comparator.comparing(Maintenance::started_at,Comparator.nullsLast(Comparator.naturalOrder()))));
    regEvents.values().forEach(xs->xs.sort(Comparator.comparing(RegEvent::date,Comparator.nullsLast(Comparator.naturalOrder()))));
  }
}
