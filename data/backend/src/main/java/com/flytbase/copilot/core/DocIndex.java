package com.flytbase.copilot.core;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Small BM25 index over manuals, SOPs and repair reports, chunked by markdown heading. No external deps. */
public final class DocIndex {
  public record Chunk(String path, String wellId, String heading, String text) {
    public String ref() { return path + (heading.isBlank() ? "" : "#" + heading); }
  }
  public record Hit(Chunk chunk, double score) {}
  private record Posting(int document, int frequency) {}
  private record ScoredDocument(int document, double score) {}

  private final List<Chunk> chunks = new ArrayList<>();
  private final Map<String, List<Posting>> postings = new HashMap<>();
  private final Map<String, Double> idf = new HashMap<>();
  private int[] lengths = new int[0];
  private double avgLen;
  private static final Pattern WELL = Pattern.compile("assets/(WELL-\\d+)/");

  static List<String> tok(String s) {
    List<String> out = new ArrayList<>();
    for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (t.length() >= 2) out.add(t);
    return out;
  }

  public DocIndex(Dataset ds) throws IOException {
    for (var e : ds.docFiles.entrySet()) {
      Matcher m = WELL.matcher(e.getKey());
      String well = m.find() ? m.group(1) : null;
      String heading = ""; StringBuilder sb = new StringBuilder();
      try (BufferedReader reader = Files.newBufferedReader(e.getValue())) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("#")) { flush(e.getKey(), well, heading, sb); heading = line.replaceAll("^#+\\s*", "").trim(); }
          else sb.append(line).append('\n');
        }
      }
      flush(e.getKey(), well, heading, sb);
    }
    long total = 0;
    lengths = new int[chunks.size()];
    for (int doc = 0; doc < chunks.size(); doc++) {
      Chunk c = chunks.get(doc);
      Map<String, Integer> f = new HashMap<>();
      List<String> toks = tok(c.heading() + " " + c.text());
      for (String t : toks) f.merge(t, 1, Integer::sum);
      lengths[doc] = toks.size();
      total += toks.size();
      for (var term : f.entrySet()) postings.computeIfAbsent(term.getKey(), ignored -> new ArrayList<>())
          .add(new Posting(doc, term.getValue()));
    }
    avgLen = chunks.isEmpty() ? 1 : (double) total / chunks.size();
    int n = chunks.size();
    for (var entry : postings.entrySet()) {
      int frequency = entry.getValue().size();
      idf.put(entry.getKey(), Math.log(1 + (n - frequency + 0.5) / (frequency + 0.5)));
    }
  }

  private void flush(String path, String well, String heading, StringBuilder sb) {
    String t = sb.toString().trim();
    if (!t.isEmpty()) chunks.add(new Chunk(path, well, heading, t));
    sb.setLength(0);
  }

  public int size() { return chunks.size(); }

  public List<Hit> search(String query, String wellId, int k) {
    if (k <= 0 || chunks.isEmpty()) return List.of();
    LinkedHashSet<String> terms = new LinkedHashSet<>(tok(query));
    Map<Integer, Double> scores = new HashMap<>();
    for (String term : terms) {
      List<Posting> list = postings.get(term);
      if (list == null) continue;
      double termIdf = idf.get(term);
      for (Posting p : list) {
        Chunk c = chunks.get(p.document());
        if (wellId != null && c.wellId() != null && !c.wellId().equals(wellId)) continue;
        double frequency = p.frequency(), length = lengths[p.document()];
        double score = termIdf * (frequency * 2.5) / (frequency + 1.5 * (0.25 + 0.75 * length / avgLen));
        scores.merge(p.document(), score, Double::sum);
      }
    }

    Comparator<ScoredDocument> worstFirst = Comparator.comparingDouble(ScoredDocument::score)
        .thenComparing(Comparator.comparingInt(ScoredDocument::document).reversed());
    PriorityQueue<ScoredDocument> best = new PriorityQueue<>(Math.max(1, Math.min(k, scores.size())), worstFirst);
    for (var entry : scores.entrySet()) {
      ScoredDocument candidate = new ScoredDocument(entry.getKey(), entry.getValue());
      if (best.size() < k) best.add(candidate);
      else if (worstFirst.compare(candidate, best.peek()) > 0) { best.poll(); best.add(candidate); }
    }
    List<ScoredDocument> ranked = new ArrayList<>(best);
    ranked.sort(Comparator.comparingDouble(ScoredDocument::score).reversed().thenComparingInt(ScoredDocument::document));
    return ranked.stream().map(x -> new Hit(chunks.get(x.document()), x.score())).toList();
  }
}
