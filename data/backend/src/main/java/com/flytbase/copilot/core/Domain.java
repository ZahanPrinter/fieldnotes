package com.flytbase.copilot.core;

import com.flytbase.copilot.core.Models.*;
import java.time.*;
import java.util.*;

/**
 * Deterministic business logic. The LLM never computes any of this: it only chooses which
 * operation to call. Every result carries the record ids it used so citations come from here,
 * not from model output.
 */
public final class Domain {
  public final Dataset ds;
  public final Overlay overlay;
  private final Map<String, String> formalCorrectionByInspection = new HashMap<>();

  public Domain(Dataset ds, Overlay overlay) {
    this.ds = ds;
    this.overlay = overlay;
    for (List<Note> wellNotes : ds.notes.values()) for (Note note : wellNotes)
      if ("correction".equals(note.note_type()) && note.inspection_id() != null)
        formalCorrectionByInspection.put(note.inspection_id(), note.note_id());
  }

  /** An inspection after corrections are applied, with the corrections that changed it. */
  public record Effective(Inspection raw, double wellheadC, double ambientC, boolean spill, Double spillBbl,
                          List<Overlay.Correction> applied, boolean spillCorrectedAway, String correctionNoteId) {
    public double delta() { return wellheadC - ambientC; }
  }

  public Effective effective(Inspection i) {
    List<Overlay.Correction> applied = new ArrayList<>();
    double wh = i.wellhead_temp_c() == null ? Double.NaN : i.wellhead_temp_c();
    double amb = i.ambient_temp_c() == null ? Double.NaN : i.ambient_temp_c();
    Double vol = i.spill_est_volume_bbl();
    boolean spill = i.spill_detected();

    Optional<Overlay.Correction> c;
    if ((c = overlay.latest(i.inspection_id(), Overlay.Field.WELLHEAD_TEMP_C)).isPresent()) { wh = Double.parseDouble(c.get().newValue()); applied.add(c.get()); }
    if ((c = overlay.latest(i.inspection_id(), Overlay.Field.AMBIENT_TEMP_C)).isPresent()) { amb = Double.parseDouble(c.get().newValue()); applied.add(c.get()); }
    if ((c = overlay.latest(i.inspection_id(), Overlay.Field.SPILL_VOLUME_BBL)).isPresent()) { vol = Double.parseDouble(c.get().newValue()); applied.add(c.get()); }

    // Formal corrections in the dataset (note_type=correction with inspection_id) override the detection.
    String noteId = formalCorrectionByInspection.get(i.inspection_id());
    boolean away = spill && noteId != null;
    if (away) spill = false;
    return new Effective(i, wh, amb, spill, vol, applied, away, noteId);
  }

  public boolean producing(String wellId, Effective e) {
    Well w = ds.wells.get(wellId);
    Dataset.Rule r = ds.productionRules.get(w.well_type());
    if (r == null) throw new IllegalStateException("No production rule found in the manual for type " + w.well_type());
    return e.delta() >= r.value();
  }

  public Optional<Inspection> latestInspection(String wellId) {
    List<Inspection> l = ds.inspections.getOrDefault(wellId, List.of());
    return l.isEmpty() ? Optional.empty() : Optional.of(l.get(l.size() - 1));
  }

  static LocalDate day(Inspection i) { return i.inspected_at().atZone(ZoneOffset.UTC).toLocalDate(); }

  /** Inclusive date range over the already-sorted inspections, using binary search to skip older history. */
  public List<Inspection> inspectionsInRange(String wellId, LocalDate from, LocalDate to) {
    List<Inspection> l = ds.inspections.getOrDefault(wellId, List.of());
    if (l.isEmpty() || to.isBefore(from)) return List.of();
    int start = lowerBound(l, from, false), end = lowerBound(l, to, true);
    return start >= end ? List.of() : l.subList(start, end);
  }

  private static int lowerBound(List<Inspection> l, LocalDate date, boolean afterDate) {
    int lo = 0, hi = l.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      int cmp = day(l.get(mid)).compareTo(date);
      if (cmp < 0 || (afterDate && cmp == 0)) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  /** Inclusive date range. Each non-producing inspection = 7 days; missed inspections add nothing. */
  public record Downtime(String wellId, int days, int inspectionCount, List<String> sampleInspectionIds) {}

  public Downtime downtime(String wellId, LocalDate from, LocalDate to) {
    int count = 0;
    List<String> samples = new ArrayList<>(4);
    for (Inspection i : inspectionsInRange(wellId, from, to)) {
      if (!producing(wellId, effective(i))) {
        count++;
        if (samples.size() < 4) samples.add(i.inspection_id());
      }
    }
    return new Downtime(wellId, count * 7, count, List.copyOf(samples));
  }

  public record Spill(String wellId, String inspectionId, LocalDate date, double bbl) {}
  public record SpillSummary(List<Spill> spills, int correctedAway, List<String> correctedInspectionIds, List<String> correctionNoteIds,
                             int spillCount, double totalBbl) {}

  public List<Spill> spills(String wellId, LocalDate from, LocalDate to) {
    return spillSummary(wellId, from, to).spills();
  }

  public SpillSummary spillSummary(String wellId, LocalDate from, LocalDate to) {
    return spillSummary(wellId, from, to, Double.NEGATIVE_INFINITY, Integer.MAX_VALUE);
  }

  /** Computes totals while retaining only the earliest requested rows for the response. */
  public SpillSummary spillSummary(String wellId, LocalDate from, LocalDate to, double minimumBbl, int sampleLimit) {
    List<Spill> out = new ArrayList<>();
    int correctedAway = 0, spillCount = 0;
    List<String> correctedInspectionIds = new ArrayList<>(), correctionNoteIds = new ArrayList<>();
    double totalBbl = 0;
    for (Inspection i : inspectionsInRange(wellId, from, to)) {
      LocalDate d = day(i);
      Effective e = effective(i);
      if (e.spill()) {
        double bbl = e.spillBbl() == null ? 0 : e.spillBbl();
        if (bbl >= minimumBbl) {
          spillCount++;
          totalBbl += bbl;
          if (out.size() < sampleLimit) out.add(new Spill(wellId, i.inspection_id(), d, bbl));
        }
      }
      else if (e.spillCorrectedAway()) { correctedAway++; correctedInspectionIds.add(i.inspection_id()); if (e.correctionNoteId() != null) correctionNoteIds.add(e.correctionNoteId()); }
    }
    return new SpillSummary(List.copyOf(out), correctedAway, List.copyOf(correctedInspectionIds), List.copyOf(correctionNoteIds), spillCount, totalBbl);
  }

  public List<String> wellsInArea(String areaName) {
    String id = ds.areaNames.entrySet().stream().filter(x -> x.getValue().equalsIgnoreCase(areaName) || x.getKey().equalsIgnoreCase(areaName))
        .map(Map.Entry::getKey).findFirst().orElse(null);
    if (id == null) return List.of();
    return ds.wellsForArea(id);
  }
}
