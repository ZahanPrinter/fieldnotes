import com.flytbase.copilot.core.*;
import com.flytbase.copilot.core.Models.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class Verify {
  static int fails = 0;
  static void check(String name, Object got, Object want) {
    boolean ok = Objects.equals(String.valueOf(got), String.valueOf(want));
    if (!ok) fails++;
    System.out.println((ok ? "PASS " : "FAIL ") + name + " -> " + got + (ok ? "" : "   (expected " + want + ")"));
  }
  public static void main(String[] a) throws Exception {
    Dataset ds = Dataset.load(Path.of(a[0]));
    Domain d = new Domain(ds, new Overlay());
    System.out.println("wells=" + ds.wells.size() + " rules=" + ds.productionRules + " users=" + ds.users.size());

    // Q1
    Inspection last = d.latestInspection("WELL-0001").get();
    Domain.Effective e = d.effective(last);
    check("Q1 latest inspection", last.inspection_id(), "INS-0001-0203");
    check("Q1 producing", d.producing("WELL-0001", e), false);
    check("Q1 delta", String.format("%.1f", e.delta()), "3.2");
    check("Q1 inspector", ds.users.get(last.inspector_user_id()).name(), "Ryan Brooks");

    // Q4
    List<Domain.Spill> sp = d.spills("WELL-0001", LocalDate.of(2022,10,20), LocalDate.of(2026,10,17));
    check("Q4 spill count", sp.size(), 6);
    check("Q4 total bbl", String.format("%.1f", sp.stream().mapToDouble(Domain.Spill::bbl).sum()), "15.1");

    // Q5
    check("Q5 downtime 2025", d.downtime("WELL-0001", LocalDate.of(2025,1,1), LocalDate.of(2025,12,31)).days(), 21);

    // Q6
    List<String> ne7 = d.wellsInArea("Northeast Block 7");
    List<Domain.Downtime> r = new ArrayList<>();
    for (String w : ne7) r.add(d.downtime(w, LocalDate.of(2026,6,16), LocalDate.of(2026,10,16)));
    r.sort(Comparator.comparingInt(Domain.Downtime::days).reversed());
    check("Q6 top3 (ties compared as a set)", r.subList(0,3).stream().map(x -> x.wellId()+":"+x.days()).sorted().toList(), "[WELL-0107:42, WELL-0121:49, WELL-0138:42]");
    check("Q6 4th", r.get(3).wellId()+":"+r.get(3).days(), "WELL-0119:28");

    // Q2: Northeast Block 8, not producing at latest inspection
    List<String> ne8 = new ArrayList<>();
    for (String w : d.wellsInArea("Northeast Block 8")) {
      Inspection li = d.latestInspection(w).get();
      if (!d.producing(w, d.effective(li))) ne8.add(w);
    }
    check("Q2 wells", ne8, "[WELL-0164, WELL-0170, WELL-0172, WELL-0175]");

    // Q12: runtime correction keeps original, flips status for everyone
    Overlay ov = new Overlay();
    Domain d2 = new Domain(ds, ov);
    ov.add(new Overlay.Correction("COR-1", "INS-0164-0205", Overlay.Field.WELLHEAD_TEMP_C, "24.0", "41.0",
        "U-201", "Faulty handheld thermal gun; crew re-measured", java.time.Instant.now(), "chat"));
    Domain.Effective e164 = d2.effective(d2.latestInspection("WELL-0164").get());
    check("Q12 producing after correction", d2.producing("WELL-0164", e164), true);
    check("Q12 delta", String.format("%.1f", e164.delta()), "21.1");
    check("Q12 original kept", e164.raw().wellhead_temp_c(), 24.0);
    List<String> ne8b = new ArrayList<>();
    for (String w : d2.wellsInArea("Northeast Block 8")) if (!d2.producing(w, d2.effective(d2.latestInspection(w).get()))) ne8b.add(w);
    check("Q12 NE8 list after", ne8b, "[WELL-0170, WELL-0172, WELL-0175]");

    // Q13: corrected volume makes WELL-0151 spill reportable (>=5 bbl)
    ov.add(new Overlay.Correction("COR-2", "INS-0151-0201", Overlay.Field.SPILL_VOLUME_BBL, "4.5", "5.4",
        "U-201", "Field crew measured", java.time.Instant.now(), "chat"));
    check("Q13 effective bbl", d2.spills("WELL-0151", LocalDate.of(2026,9,24), LocalDate.of(2026,9,24)).get(0).bbl(), 5.4);

    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
