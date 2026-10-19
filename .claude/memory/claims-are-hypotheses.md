---
name: claims-are-hypotheses
description: "A BACKLOG row's cause, fix, severity, status and file list — and a plan/INDEX/decision-register status — are hypotheses; most older rows were wrong. Ground in code (grep the symbol/claim) before building or filing"
metadata:
  type: feedback
---

Base rate: over many drains, a third to two thirds of grounded rows had a premise that did not survive the code
(wrong file, defect 7x wider, fix unbuildable due to module graph/cycle, "blocker" that was false, "small deferred
polish" that was live data loss, row already shipped).

**How to apply:**
- Grep the SYMBOL / behaviour, not the file list a row names (a 3-file row was 12 sites). Counts understate as
  often as overstate. Grep for CALLERS — a well-tested mirror can be dead code (and "dead" code can be a load-bearing
  test vehicle).
- Status is a guess: SHIPPED rows hold stale residuals; OPEN rows can be fully shipped; INDEX once called a shipped
  plan "nothing built". Check the code, not the header.
- `docs/BACKLOG.md` closed rows keep their original prose as an indented child under a struck header — grepping a
  row id lands on open-sounding text. Assign only from top-level unstruck rows; build worklists from rows, not from
  summary narrative.
- A row DERIVED from a decision inherits no grounding; read the decision record / concept docs before filing a gap
  (three "Musts" were recorded decisions or shipped features).
- Decision-register citations rot; re-ground every cite before costing an answer. Measure how often a risk
  actually occurs before costing its fix.
- A doc rewrite can silently retract the claim a row tracks.
- Record a refutation IN the row so the next shift inherits it.

Related: [[subagent-claims-are-hypotheses]].
