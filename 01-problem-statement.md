# Asset Intelligence Copilot

_FlytBase local challenge · Hacktoberfest 2026 Hack Day_

## The problem

An oil company runs an oil field with many wells spread over a large area. Every
week someone inspects each well (often with a drone), crews repair whatever breaks, and
regulators step in when oil is spilled. All of this produces a lot of data, in many forms:
records, sensor readings, long manuals, repair reports and notes people write.

Today an operator who wants to know _"Which wells in my area were down the longest
this summer, and why?"_ has to dig through several systems and documents.
**Build an Asset Intelligence Copilot: a chat assistant that answers operators'
plain-English questions about their wells, correctly and with evidence, using all of
this data.**

We are not asking you to train models or analyse images. We are asking you to engineer
the **agentic AI layer in the middle**: how the data is stored and found again, which tools
the assistant can use, how it combines numbers with documents, and how it remembers what
people tell it.

## Why this matters

Today the information about a well is scattered: inspection results in one system,
repair records in another, rules in long manuals, and important context in people's
notes and heads. To answer even a simple question, someone has to get access to each of
these, pull the data together by hand, read through reports and then work out what it
means. That takes hours or days, so a well that has quietly stopped producing oil or started
leaking can go unnoticed for weeks.

Every day it stays down is lost oil, and every day a
leak goes unfixed means more damage, bigger clean-up costs and fines. A copilot that
anyone can simply ask, and that answers in seconds with the evidence, lets the team spot
these problems early and fix them before they get worse.

## The domain, in plain words

- An **oil well** is a hole drilled deep into the ground. Oil is pumped up by a **pump
  jack**, the machine that nods up and down. At the surface the well is capped by a
  **wellhead**, the set of valves where the oil comes out.
- **Is the well producing oil?** Nobody writes this down. You can tell from temperature: oil
  comes up warm, so a wellhead that is producing oil is much warmer than the air around it. A stopped
  one cools to air temperature. The exact rule ("how much warmer") is in the manual for
  each well type. A well stopped on purpose is "shut in".
- **Downtime** is how long a well was not producing oil. Each well is inspected once a
  week, so every inspection that finds the well not producing oil counts as one week
  (7 days) of downtime. Two such inspections in a row mean 14 days. A missed inspection
  adds no downtime.
- **Is oil leaking?** Inspections check for oil spilled on the ground around the well.
  A spill of 5 barrels or more must be reported to the regulator within 24 hours, or the
  company is fined. These rules are in the safety procedures (SOPs). People sometimes
  correct an inspection later ("that was a puddle, not oil"), and the correction wins.
- **Repairs** happen whenever something breaks, and on a schedule. Each repair has a
  cost and keeps the well stopped for a while. (Downtime still comes from the weekly
  inspections, not from how long each repair took.)

## The data you get

Data for **200 wells over 4 years** in the sample (the test dataset adds more) (one inspection per well per week, about
40,000 inspections in the sample). The separate **Dataset Guide** explains every file
and field.

| Source           | What it contains                                                                      |
| ---------------- | ------------------------------------------------------------------------------------- |
| Well details     | type, area, team, location, equipment, recorded status (may be out of date)           |
| Inspections      | weekly wellhead and air temperature, spills found (area and volume), photo references |
| Repair history   | issue, fix, technician, time stopped, cost, plus some free-text repair reports        |
| Regulator events | spill reports, violation notices, fines, site visits                                  |
| Human notes      | operator feedback, corrections, planned shutdowns                                     |
| Manuals and SOPs | rules per well type, spill and compliance procedures                                  |

The data is realistic and messy. Records go stale, a "spill" may later be
corrected as a mud puddle, and the rule you need may be deep inside a manual.

## What you build

1. **A copilot chat UI with generative UI.** A chat interface where the user asks a
   question and the copilot answers in the chat with the right visual, shown inline: a
   status card for one well, a table for a list, a line chart for change over time, a bar
   chart for comparisons, a map for locations, a heatmap for two dimensions such as area ×
   month. If the user names a visual ("show it as a table"), use it; otherwise the copilot
   picks the one that fits the answer. The answer is generated for the question, not
   chosen from fixed screens. We will show a basic prototype at kickoff.
   We do not provide photos; if your copilot needs images for an answer, use or generate
   any. We judge how the copilot uses them, not what is in them.
2. **Answers grounded in the data.** Questions range from single facts to analysis across
   the whole field that combines several sources. Every answer cites the records and
   documents it used.
3. **Memory at two levels.**
   - **Shared knowledge about a well:** if one user says _"WELL-0007 is down for planned
     maintenance"_, other users asking about that well later get that context.
   - **Private memory per user:** _"the three wells we discussed yesterday"_ refers to
     _that_ user's conversation, never someone else's. Users are listed in the data; a
     simple user picker in your UI is enough to switch between them.
   - **Learning from feedback:** users correct wrong raw data ("that reading was wrong,
     the real value is…") and wrong AI analysis ("the spill was 5.4 bbl, not 4.5"). The
     copilot keeps the original, records who corrected it and why, and uses the
     correction in every later answer, for every user. Feedback can apply to one well or
     to every well of a type, and must be applied at that scope only.
4. **Honesty about conflicts.** If what a user says contradicts the data, or two users
   disagree, the copilot says so instead of silently picking one.
5. **A system that holds up on new data.** It must handle data and questions it has not
   seen (see below). A system tuned to the sample questions will break.

The Example Questions document (`03-evaluation-queries.md`) has 18 questions with expected
answers, so you can check your progress.

## How the day runs

- **Build** with the sample dataset and example questions.
- **Final 2 hours:** we release a **test dataset** in the same format. Load it into
  your system. We then release the **final questions**. Run them through your copilot
  and record the answers.
- **Submit** before the deadline.

## What to submit

We are evaluating **you, not the AI's output**. Use any tools, agents and models you
like: open-source, open-weight or proprietary. What matters is how you designed the
system and how well you understand it. Submit through **OrganizerHQ** (the event
portal):

| Item                            | What it contains                                                                                                                                                                                                                                              |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Write-up: page 1, system design | Your architecture: how the data is stored, indexed and retrieved; the tools the copilot uses; how it combines numbers, documents and notes; how both memory levels work; how it handles conflicts and new data; models used and cost; what you would do next. |
| Write-up: page 2, results       | Your copilot's answers to the final questions, with what worked, what didn't and why.                                                                                                                                                                         |
| Video, 5 minutes at most        | In your own voice: how you built the system, then one real use case, showing a question running end to end in your copilot UI.                                                                                                                                |
| Repository link                 | A public GitHub repository with an open-source license, needed for event approval.                                                                                                                                                                            |

Keep to 2-3 pages; writing them clearly is part of the challenge. The work must be your own.

## How we judge

1. **Results:** we check the answers on your results page against our expected answers
   and use the submitted video to understand the system that produced them.
2. **System design walkthrough:** shortlisted individuals walk us through their design
   and the decisions behind it.

A sound, general design that answers 12 questions reliably beats a fragile one that gets
20 by luck.
