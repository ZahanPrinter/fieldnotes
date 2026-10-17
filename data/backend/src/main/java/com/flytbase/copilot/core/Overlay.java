package com.flytbase.copilot.core;

import java.time.Instant;
import java.util.*;

/**
 * Append-only knowledge layer: corrections and user-supplied notes. Source data is never edited;
 * every effective value is "dataset value + latest applicable correction", and each entry keeps the
 * original, author, reason and scope so answers can explain themselves.
 */
public final class Overlay {
  public enum Field { WELLHEAD_TEMP_C, AMBIENT_TEMP_C, SPILL_VOLUME_BBL, SPILL_VALID }

  public record Correction(String id, String inspectionId, Field field, String originalValue, String newValue,
                           String authorUserId, String reason, Instant at, String source) {}

  /** A shared statement from a user. scope = well id or "type:T350". status = unconfirmed until verified. */
  public record SharedNote(String id, String scope, String authorUserId, String text, String status, Instant at) {}

  private final List<Correction> corrections = new ArrayList<>();
  private final List<SharedNote> notes = new ArrayList<>();
  private final Map<String, List<Correction>> correctionsByInspection = new HashMap<>();
  private final Map<String, EnumMap<Field, Correction>> latestByInspection = new HashMap<>();
  private final Map<String, List<SharedNote>> notesByScope = new HashMap<>();
  private long correctionSequence = 1, noteSequence = 1;
  private java.util.function.Consumer<Correction> correctionSink = ignored -> {};

  public synchronized void setCorrectionSink(java.util.function.Consumer<Correction> sink) { this.correctionSink = sink == null ? ignored -> {} : sink; }

  public synchronized String nextCorrectionId() { return "COR-" + correctionSequence++; }
  public synchronized String nextNoteId() { return "USR-" + noteSequence++; }

  public synchronized void add(Correction c) {
    correctionSink.accept(c);
    correctionSequence = advanceSequence(c.id(), "COR-", correctionSequence);
    corrections.add(c);
    correctionsByInspection.computeIfAbsent(c.inspectionId(), ignored -> new ArrayList<>()).add(c);
    EnumMap<Field, Correction> byField = latestByInspection.computeIfAbsent(c.inspectionId(), ignored -> new EnumMap<>(Field.class));
    Correction current = byField.get(c.field());
    if (current == null || !c.at().isBefore(current.at())) byField.put(c.field(), c);
  }

  public synchronized void addNote(SharedNote n) {
    noteSequence = advanceSequence(n.id(), "USR-", noteSequence);
    notes.add(n);
    notesByScope.computeIfAbsent(n.scope(), ignored -> new ArrayList<>()).add(n);
  }

  public synchronized Optional<Correction> latest(String inspectionId, Field f) {
    EnumMap<Field, Correction> byField = latestByInspection.get(inspectionId);
    return Optional.ofNullable(byField == null ? null : byField.get(f));
  }

  public synchronized List<Correction> correctionsForInspection(String inspectionId) {
    return List.copyOf(correctionsByInspection.getOrDefault(inspectionId, List.of()));
  }

  public synchronized List<Correction> all() { return List.copyOf(corrections); }

  public synchronized List<SharedNote> notesFor(String wellId, String wellType) {
    List<SharedNote> out = new ArrayList<>(notesByScope.getOrDefault(wellId, List.of()));
    out.addAll(notesByScope.getOrDefault("type:" + wellType, List.of()));
    return out;
  }

  public synchronized int size() { return corrections.size() + notes.size(); }

  private static long advanceSequence(String id, String prefix, long current) {
    if (!id.startsWith(prefix)) return current;
    try { return Math.max(current, Math.addExact(Long.parseLong(id.substring(prefix.length())), 1)); }
    catch (NumberFormatException | ArithmeticException ignored) { return current; }
  }
}
