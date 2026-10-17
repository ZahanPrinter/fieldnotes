# Dataset Guide

## Overview

The sample dataset describes a fictional oil field with **200 wells in 4 geographic
areas** (blocks of land, 50 wells each), observed from **20 Oct 2022 to 16 Oct 2026**.
Treat **16 Oct 2026** as "today".

| What | Count |
|---|---|
| Wells | 200 (50 per area) |
| Weekly inspections | about 39,600 (up to 209 per well) |
| Repair records | about 5,700 |
| Free-text repair reports | about 1,500 (one for every corrective repair) |
| Regulator events | 90 |
| Human notes | about 140 |

A few things make the data behave like a real field:

- Some wells were drilled during these 4 years, so their history starts later.
- About 2% of weekly inspections were missed.
- Older wells, and equipment that already failed, break more often.

In the final 2 hours of the event we release a **test dataset** in exactly the same
format (more wells and more history). Your system has to load it alongside this one.

**WELL-0001** is the reference well. It has every kind of record and appears in many of
the example questions.

All data is synthetic and created for this challenge. Manuals and SOPs are simplified,
fictional documents, not real operating or legal guidance.

## Folder layout

All paths are inside the `sample-data/` folder.

**Fleet-wide files (`fleet/`)**

| Path | What it contains |
|---|---|
| `fleet/areas.json` | Geographic areas (blocks of land), their teams, and which wells belong where |
| `fleet/users.json` | People who use the copilot, with their roles |
| `fleet/well-types/T200.md` | Operating manual for T200 wells |
| `fleet/well-types/T350.md` | Operating manual for T350 wells |
| `fleet/sops/spill-response-sop.md` | Spill classes and protocols SR-01 to SR-04 |
| `fleet/sops/regulatory-compliance-sop.md` | Compliance protocols RC-01 to RC-05 |
| `fleet/sops/field-work-sop.md` | Crew safety and maintenance protocols WS-01 to WS-07 |

**One folder per well (`assets/WELL-0001/` … `assets/WELL-0200/`)**

| File | What it contains |
|---|---|
| `metadata.json` | Well details and equipment |
| `inspections.jsonl` | One line per weekly inspection |
| `maintenance.jsonl` | One line per maintenance job |
| `maintenance-reports/*.md` | A free-text report for every corrective repair: what happened, root cause, safety steps, work done, tests, clean-up, parts and cost, and a compliance check |
| `regulatory-events.jsonl` | Agency reports, notices and fines |
| `context.jsonl` | Human notes, feedback and corrections |

`.jsonl` files hold one JSON object per line.

Every record in `assets/` also carries the `asset_id` of its well.

## Areas (fleet/areas.json)

A **geographic area** is a block of land in the oil field, for example SW-23 (Southwest
Block 23). Each area belongs to one production team and lists the wells in it.

```json
{
  "areas": [
    {
      "area_id": "SW-23",
      "name": "Southwest Block 23",
      "team_id": "TEAM-SW",
      "center": { "lat": 31.842, "lon": -102.512 },
      "asset_ids": ["WELL-0001", "..."]
    }
  ],
  "teams": [
    {
      "team_id": "TEAM-SW",
      "name": "Southwest Production",
      "lead_user_id": "U-101"
    }
  ]
}
```

## Users (fleet/users.json)

| Field | Meaning |
|---|---|
| `user_id` | Unique ID, for example `U-201` |
| `name` | Full name |
| `role` | `production_supervisor`, `field_operator`, `field_inspector`, `maintenance_lead`, `maintenance_technician` or `hse_officer` |
| `team_id` | Team the person belongs to |

## Well details (assets/WELL-XXXX/metadata.json)

| Field                                     | Meaning                                                                                                           |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `asset_id`, `name`                        | Unique ID and display name                                                                                        |
| `asset_category`                          | Always `oil_well`                                                                                                 |
| `well_type`                               | `T200` or `T350`; selects the manual and its rules                                                                |
| `area_id`, `team_id`, `in_charge_user_id` | Ownership                                                                                                         |
| `location`                                | `lat`, `lon`                                                                                                      |
| `installed_on`                            | Installation date                                                                                                 |
| `equipment[]`                             | `equipment_id`, `kind` (`wellhead`, `pump_jack`, `rod_string`, `downhole_pump`, `flowline`, `separator`), `model` |
| `last_inspection`                         | ID, time and inspector of the latest inspection                                                                   |
| `recorded_status`                         | `active` or `down` in the system of record, refreshed at the end of each month. **Can be out of date**; the inspections are the truth. |

## Inspections (assets/WELL-XXXX/inspections.jsonl)

| Field                         | Unit / values      | Meaning                                                  |
| ----------------------------- | ------------------ | -------------------------------------------------------- |
| `inspection_id`               | `INS-<well>-<n>`   | Unique ID                                                |
| `inspected_at`                | ISO 8601 UTC       | Time of the inspection (also the spill _discovery_ time) |
| `inspector_user_id`, `method` | `drone` / `manual` | Who and how                                              |
| `ambient_temp_c`              | °C                 | Air temperature                                          |
| `wellhead_temp_c`             | °C                 | Thermal reading of the wellhead                          |
| `spill_detected`              | bool               | A spill was detected on the pad                          |
| `spill_area_pct`              | % of pad area      | Null when no spill                                       |
| `spill_est_volume_bbl`        | barrels            | Crude volume estimated by the AI analysis of the inspection images; null when no spill |
| `evidence_images[]`           | paths              | Image references (files not supplied)                    |
| `notes`                       | text               | Inspector's remark                                       |

**Producing oil or not** is never stored. Derive it from the well-type manual: a well is
producing oil when `wellhead_temp_c - ambient_temp_c` is at least the threshold for its type.
**Downtime** is how long a well was not producing oil. Each well is inspected once a week,
so every inspection in a period that finds the well not producing oil counts as **7 days**
of downtime. A missed inspection adds no downtime. Do not use
`maintenance.downtime_hours` for this; that field is how long one repair job took.

## Repairs (assets/WELL-XXXX/maintenance.jsonl)

| Field                              | Meaning                                 |
| ---------------------------------- | --------------------------------------- |
| `maintenance_id`                   | `MNT-<well>-<n>`, in date order         |
| `started_at`, `completed_at`       | ISO 8601 UTC. For repairs still in progress, `completed_at` is the planned end and can be after today. |
| `maintenance_type`                 | `scheduled` or `corrective`             |
| `equipment_kind`                   | Equipment worked on                     |
| `issue`, `action_taken`            | What was wrong and what was done        |
| `technician_user_id`, `contractor` | Who did it                              |
| `downtime_hours`                   | Hours the well was stopped for this job |
| `cost_usd`                         | `labor`, `parts`, `total`               |
| `triggered_by_inspection_id`       | Inspection that led to the job, if any  |
| `report_file`                      | Path of a free-text report, if any. File names vary, so always follow this field. |

## Regulator events (assets/WELL-XXXX/regulatory-events.jsonl)

| Field                   | Meaning                                                                    |
| ----------------------- | -------------------------------------------------------------------------- |
| `event_id`, `date`      | ID and date                                                                |
| `agency`                | `State Oil & Gas Commission` or `Regional Environmental Protection Office` |
| `event_type`            | `spill_report_filed`, `notice_of_violation`, `site_inspection`, `nov_response_filed` |
| `protocol_id`           | SOP protocol involved (e.g. `SR-01`)                                       |
| `related_inspection_id` | Inspection that discovered the spill                                       |
| `hours_to_report`       | Hours from discovery to the report being filed                             |
| `detail`                | Short description of the event                                             |
| `fine_usd`, `status`    | Fine amount; `open` or `closed`                                            |

## Human notes (assets/WELL-XXXX/context.jsonl)

| Field                                     | Meaning                                                    |
| ----------------------------------------- | ---------------------------------------------------------- |
| `note_id`, `created_at`, `author_user_id` | Who wrote it and when                                      |
| `note_type`                               | `operator_feedback`, `operator_note`, `correction`         |
| `inspection_id`                           | Only on `correction` notes: the inspection being corrected |
| `text`                                    | The note itself                                            |
| `valid_from`, `valid_to`                  | Period the note applies to (`valid_to` may be null)        |

A `correction` overrides the detection of the inspection named in its `inspection_id`,
for example a "spill" that was really rainwater. Notes exist on many wells, not only
WELL-0001. Feedback your copilot collects at run time belongs to the same kind of knowledge.

## Things to watch for

- On a hot day an idle wellhead can read over 50 °C. Only the difference from ambient matters.
- The latest record can disagree with `recorded_status` or with a recent human note.
- Some facts appear only in free-text reports, such as when containment actually happened.
- Thresholds and deadlines live in the manuals and SOPs, not in the data files.
- People can disagree in their notes about the same inspection; neither note is a formal correction.
