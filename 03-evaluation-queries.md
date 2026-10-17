# Example Questions

These are 18 example questions with the answers your copilot should give on the sample
dataset (200 wells). "Today" is **16 Oct 2026**, and "right now" means each well's latest
inspection. Periods such as "last 4 months" end today and are compared by date. A repair
belongs to a period by its start date. The final questions, released with the test
dataset in the last 2 hours, follow the same style.

Run each scenario on the original sample data, unless it says it follows another one.

## Before you start: how the data fits together

The data is more than the record files. The **well-type manuals** and the **SOPs** in
`fleet/` are part of it: they hold the rules that decide most answers, such as when a
well counts as producing oil and which deadlines apply to a spill. How downtime is counted
is in the Dataset Guide. Your system has to find and apply those rules; we do not repeat
them here.

Everything starts from a well. Its type decides which manual applies, and its weekly
inspections are the main record of what happened. When an inspection finds a problem, the
follow-up records point back to that inspection's ID: the repair, its free-text report,
and, for larger spills, the regulator events. Some facts live only in the reports. To
judge whether something was handled correctly, compare these records with the SOPs. Human
notes explain why the data looks the way it does, and the recorded status can be out of
date.

## Choosing the visual

When a question names a visual (a card, table, chart, map or heatmap), the copilot shows
that visual. When it doesn't, the copilot picks the one that fits the answer:

| The answer is about… | Show |
|---|---|
| One well right now | A status card |
| A list of wells, events or repairs | A table |
| A value changing over time | A line chart |
| Comparing a few wells or areas | A bar chart |
| Where things are | A map |
| Two dimensions at once, such as area × month | A heatmap |

## How we check answers

- **Facts:** numbers, names, IDs and dates must match.
- **Evidence:** the answer cites the records and documents it used.
- **Visual:** the visual the question asks for, or a fitting one if it doesn't ask.

Wells are referred to by their ID (for example **WELL-0001**). Geographic areas, teams
and people are referred to by name (for example **Northeast Block 7**).

---

# Part A: One user

**What these questions test:**
- **A1:** the copilot answers any kind of question about the data (one well, a well over
  time, an area, many wells, and records combined with documents) correctly, with
  evidence and the right visual.
- **A2:** the copilot remembers the user's past conversations, and it learns from the
  user's feedback: corrections to raw data, corrections to the AI's analysis, and rules
  for a whole well type. It applies each one at the right scope.

## A1. Asking about the data

**Q1. Is WELL-0001 producing oil right now? When was it last inspected, and by whom? Show me its status card.**
- Expected (status card): **No, it is not producing oil.**
  - Latest inspection INS-0001-0203, on 15 Oct 2026, by drone, by Ryan Brooks.
  - The wellhead was only 3.2 °C warmer than the air, below the T200 limit.
  - Reason: note CTX-0001-003 by Jake Morales, a planned shut-in from 7 Oct for rod string replacement, expected back around 20 Oct. It is not yet in the maintenance system.
  - The recorded status still says `active`, which is out of date.

**Q2. Which wells in Northeast Block 8 are not producing oil right now, and why? Show them in a table.**
- Expected (table): four T200 wells, all last inspected on 15 Oct 2026. Two have no recorded reason.

  | Well | Wellhead above air | Recorded status | Reason |
  |---|---|---|---|
  | WELL-0164 | 4.1 °C | active (out of date) | **No recorded reason.** The inspector noted "Pump jack not moving." |
  | WELL-0170 | 2.9 °C | down | Repair MNT-0170-031, pump jack walking beam bearing wear, started 19 Sep, still open |
  | WELL-0172 | 5.7 °C | active (out of date) | Repair MNT-0172-028, flowline pinhole leak, started 3 Oct, still open |
  | WELL-0175 | 0.4 °C | active (out of date) | **No recorded reason** |

  Where nothing explains the stop, the copilot says so and suggests a check. It must not invent a cause.

**Q3. Show a line chart of WELL-0001's wellhead temperature against air temperature for the last 13 weeks, and mark when it was not producing oil.**
- Expected (line chart): 13 readings (23 Jul to 15 Oct 2026). Not producing oil on 8 Oct and 15 Oct.

**Q4. How many oil spills has WELL-0001 had in the last 4 years, and how much oil in total? List them in a table.**
- Expected (table): **6 spills, 15.1 barrels.**
  - There were 7 detections. INS-0001-0070 (7 Mar 2024) was corrected as rainwater (CTX-0001-001) and does not count.
  - Spills: 16 Mar 2023 (0.5 bbl), 27 Apr 2023 (2.6), 28 Sep 2023 (3.3), 19 Oct 2023 (0.3), 11 Jun 2026 (6.8), 1 Oct 2026 (1.6).

**Q5. How many days was WELL-0001 down in 2025?**
- Expected (a short answer with its evidence; no visual needed): **21 days**, from three weekly inspections where it was not producing oil.
  - **24 Jul 2025:** the wellhead read 51.6 °C, which looks hot, but the air was 43.1 °C. That is only 8.5 °C warmer, so the well was not producing oil. Cause: the prime mover tripped (MNT-0001-021).
  - **16 and 23 Oct 2025:** stuffing box packing leak (MNT-0001-023).

**Q6. Which 3 wells in Northeast Block 7 had the most downtime in the last 4 months? Show a bar chart.**
- Window: 16 Jun to 16 Oct 2026.
- Expected (bar chart): **WELL-0121: 49 days** (T200), **WELL-0138: 42 days** (T350), **WELL-0107: 42 days** (T350). Next is WELL-0119 at 28.

**Q7. Show on a map every well in Northeast Block 8 with a real spill in the last 90 days.**
- Expected (map with 4 pins):

  | Well | Date | Barrels | Location (lat, lon) |
  |---|---|---|---|
  | WELL-0151 | 24 Sep 2026 | 4.5 | 32.12246, −102.18445 |
  | WELL-0165 | 30 Jul 2026 | 1.2 | 32.15665, −102.14522 |
  | WELL-0175 | 30 Jul 2026 | 3.9 | 32.18020, −102.14790 |
  | WELL-0186 | 23 Jul 2026 | 0.6 | 32.17552, −102.17775 |

**Q8. Show a heatmap of 2026 spills by geographic area and month.**
- Expected (heatmap of areas × Jan–Oct, with corrected detections removed):

  | Area | Jan | Feb | Mar | Apr | May | Jun | Jul | Aug | Sep | Oct | Total |
  |---|---|---|---|---|---|---|---|---|---|---|---|
  | Southwest Block 23 | 4 | 3 | 3 | 6 | 3 | 4 | 4 | 3 | 3 | 2 | 35 |
  | Southwest Block 24 | 7 | 4 | 5 | 6 | 5 | 5 | 3 | 1 | 5 | 2 | **43** |
  | Northeast Block 7 | 5 | 3 | 2 | 4 | 6 | 6 | 2 | 4 | 4 | 1 | 37 |
  | Northeast Block 8 | 4 | 4 | 4 | 3 | 2 | 4 | 4 | 0 | 1 | 0 | 26 |

**Q9. Which rules has WELL-0001 broken in the last 12 months, and what is still open?**
- Expected (table or list): four breaches, all from the 11 Jun 2026 spill of 6.8 bbl. USD 5,000 in fines, still open.
  - **SR-01:** reported after 41 hours (REG-0001-001), against a 24-hour limit. This led to notice of violation REG-0001-002 with a USD 5,000 fine, still open.
  - **SR-02:** contained after about 7 hours, against a 4-hour limit. This is found only in the June repair report.
  - **SR-03:** cleanup verification was due by 10 Aug 2026 and is still open (REG-0001-003).
  - **RC-04:** the notice needed a response by 30 Jul 2026, and none is recorded.

  Also still pending (open actions, not counted as breaches because the deadlines have not passed or the work is still under way):
  - A root-cause analysis is due on the flowline: 3 corrective repairs in the last 12 months (MNT-0001-028, -029, -032). The report for the October repair (MNT-0001-032) marks it as "Action required".
  - The planned shut-in from 7 Oct is not yet recorded in the maintenance system, which the T200 manual requires before work starts (note CTX-0001-003).

  The yearly flowline test (RC-02) was done on 7 Mar 2026, so that rule was met.

**Q10. In Southwest Block 24, which spills in the last 90 days were not contained within the SOP time limit?**
- Expected (table): 2 spills. The limit is 4 hours, and the containment time exists only inside the repair reports.
  - WELL-0082: contained after about 7.1 hours (report MNT-0082-028, inspection INS-0082-0198).
  - WELL-0100: contained after about 8.1 hours (report MNT-0100-034, inspection INS-0100-0196).

## A2. Memory and feedback from one user

**Q11. Remembering past conversations**
- Day 1, Michael Turner asks Q6.
- Day 2, Michael Turner, in a new chat: *"For the three wells we discussed yesterday, what have repairs cost this year?"*
- Expected: WELL-0121: USD 25,794 · WELL-0138: USD 46,942 · WELL-0107: USD 5,176.

**Q12. Correcting wrong raw data**
- Michael Turner: *"The 15 Oct reading for WELL-0164 is wrong. The handheld thermal gun used in that inspection was faulty; the field crew re-measured the wellhead at 41.0 °C."*
- Expected:
  - The copilot records the correction against inspection INS-0164-0205. It keeps the original value (24.0 °C) and notes who corrected it and why.
  - With 41.0 °C against 19.9 °C air (21.1 °C warmer, above the T200 limit), WELL-0164 **is producing oil**.
  - The copilot also points out that the same inspection has the note "Pump jack not moving." (Tyler Hughes), which disagrees with the correction, and asks Michael Turner to confirm.
- Then Michael Turner asks Q2 again.
  - Expected: **three wells** (WELL-0170, WELL-0172, WELL-0175). WELL-0164 is no longer listed, and the answer says why.

**Q13. Correcting the AI's analysis**
- Michael Turner: *"The AI estimated the 24 Sep spill at WELL-0151 as 4.5 bbl. The field crew measured 5.4 bbl."*
- Expected:
  - The copilot records the corrected volume against inspection INS-0151-0201, keeping the AI's estimate for reference.
  - At 5.4 bbl the spill is **reportable** under the Spill Response SOP. A written notice was due within 24 hours of the 24 Sep 15:30 inspection, and **none is recorded**. The copilot flags this as a possible SR-01 breach.
  - Repair report MNT-0151-025 classed the spill as "moderate" and listed no reporting duty. That was based on the old estimate and no longer holds. (The same report already shows containment took about 6.4 hours, an SR-02 breach.)
  - Because it is now a reportable spill, agency verification of the clean-up (SR-03) is also due within 60 days, by 23 Nov 2026.

**Q14. A rule for a whole well type**
- Michael Turner first asks: *"Which wells in Northeast Block 7 need a root-cause analysis on their pump jack?"*
  - Expected: **WELL-0108, WELL-0121 and WELL-0138**, with 3 corrective pump jack repairs each in the last 12 months (T200 manual section 10; the T350 manual refers to it).
- Michael Turner: *"Note for all T350 wells: our team now wants a pump jack root-cause analysis after 2 repairs, not 3."* Then, in a new chat, he asks the same question again.
  - Expected: **WELL-0108, WELL-0120, WELL-0121 and WELL-0138.**
    - WELL-0120 is a T350 well with 2 repairs, so it is now added.
    - WELL-0102 also has 2 repairs, but it is a T200 well, so it must **not** be added.
    - The answer says this follows Michael Turner's rule, not the manual.

---

# Part B: Several users

Part B adds more people:

| Person | Role | Team |
|---|---|---|
| Michael Turner | Production supervisor | Northeast Production |
| Tyler Hughes | Field operator | Northeast Production |
| Jessica Reed | Field inspector | Northeast Production |
| Jake Morales | Field operator | Southwest Production |
| Laura Simmons | HSE officer | Health, Safety & Environment |

**What these questions test:** facts and corrections that one user gives reach everyone
else; each user's private conversations stay private; and when people disagree with each
other or with the data, the copilot shows the conflict instead of quietly picking a side.

**Q15. A correction reaches other users** (follows Q12 and Q13)
- Jessica Reed, in her own chat: *"Is WELL-0164 producing oil right now?"*
  - Expected: **yes**, based on the corrected reading of 41.0 °C. The answer says the 15 Oct value was corrected by Michael Turner, and mentions the inspector's "Pump jack not moving." note that disagrees with it.
- Laura Simmons: *"Which spills in Northeast Block 8 in the last 90 days needed a report to the regulator?"*
  - Expected: **WELL-0151**, because its volume was corrected to 5.4 bbl. No written notice is on record, so it is flagged as overdue. No other spill in that period reached 5 bbl.

**Q16. Shared knowledge, private memory**
- Jake Morales: *"FYI for everyone: WELL-0007 will be shut in from 17 Oct for a 10-day planned workover."*
- Later, Michael Turner, in a new chat: *"Is any downtime planned for WELL-0007?"*
  - Expected: yes, from 17 Oct for about 10 days, according to Jake Morales.
- Then Jessica Reed: *"For the three wells we discussed yesterday, what have repairs cost this year?"* (This follows Q11.)
  - Expected: the copilot says it has no such earlier conversation with Jessica Reed. It must **not** use Michael Turner's history from Q11.

**Q17. People disagree with the data**
- Michael Turner: *"WELL-0175 has been shut in for planned work since 14 Oct."*
- Later, Tyler Hughes: *"WELL-0175 is running fine."*
  - Expected: the copilot tells Tyler Hughes that the 15 Oct inspection (by Jessica Reed) shows the wellhead only 0.4 °C above the air, so the well is not producing oil. That matches Michael Turner's note, not Tyler Hughes's claim. The copilot keeps both statements on record, marks Tyler Hughes's as unconfirmed, and asks for confirmation.
- Then Jake Morales: *"WELL-0001 is back online since 14 Oct."*
  - Expected: the copilot points out that the 15 Oct inspection shows WELL-0001 not producing oil, and that note CTX-0001-003 expects a return around 20 Oct. It records the claim as unconfirmed.

**Q18. People disagree about the AI's analysis**
- Any user: *"Was the 20 Aug spill at WELL-0101 real oil?"*
- Expected:
  - The AI flagged a 1.0 bbl spill at inspection INS-0101-0197 ("Oil sheen around cellar").
  - Two notes disagree: Ryan Brooks thought it was produced water (CTX-0101-002), but Chris Dalton (HSE) sampled the stain and confirmed crude (CTX-0101-003).
  - Neither note is a formal correction, and a spill repair (MNT-0101-029) was done, so the detection stands.
  - The copilot answers **yes** but shows the disagreement and both sources.
