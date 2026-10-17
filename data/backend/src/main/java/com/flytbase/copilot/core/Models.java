package com.flytbase.copilot.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/** Immutable source records, mirroring the dataset files. Never mutated: corrections live in the overlay. */
public final class Models {
  private Models() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Location(double lat, double lon) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Well(String asset_id, String name, String well_type, String area_id, String team_id,
                     String in_charge_user_id, Location location, String recorded_status) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Inspection(String inspection_id, String asset_id, Instant inspected_at, String inspector_user_id,
                           String method, Double ambient_temp_c, Double wellhead_temp_c, boolean spill_detected,
                           Double spill_area_pct, Double spill_est_volume_bbl, List<String> evidence_images,
                           String notes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Maintenance(String maintenance_id, String asset_id, Instant started_at, Instant completed_at,
                            String maintenance_type, String equipment_kind, String issue, String action_taken,
                            String technician_user_id, Double downtime_hours, Cost cost_usd,
                            String triggered_by_inspection_id, String report_file) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Cost(double labor, double parts, double total) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RegEvent(String event_id, String asset_id, String date, String agency, String event_type,
                         String protocol_id, String related_inspection_id, Double hours_to_report,
                         String detail, Double fine_usd, String status) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Note(String note_id, String asset_id, Instant created_at, String author_user_id, String note_type,
                     String inspection_id, String text, String valid_from, String valid_to) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record User(String user_id, String name, String role, String team_id) {}
}
