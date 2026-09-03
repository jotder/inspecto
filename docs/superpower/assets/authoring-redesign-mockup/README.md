# Authoring redesign — clickable mockup (working files)

Working files for the design canvas **"Pipeline Authoring Redesign"**
(https://claude.ai/code/artifact/b5e7ec6c-5bb2-467a-a513-93bfda987f3d), the visual companion to
[`parse-pane-redesign-plan.md`](../../parse-pane-redesign-plan.md) and
[`sql-transform-v1-plan.md`](../../sql-transform-v1-plan.md). Decided 2026-09-03.

| File | Artboard |
|---|---|
| `Steps.dc.html` | The five steps — Collect · Parse · Transform · Filter · Store, one question each |
| `Main.dc.html` | Parse step (Delimited): Sample / Parsed tabs, collapsible property sections with sample values, columns table with filename toggle |
| `Transform.dc.html` | Transform step: Simple (Fields grid → generated SQL, five verbs, search / filters / pagination 10-20-100, lock on hand edit) and Advanced (SQL) |
| `canvas.json` | Layout + the sticky notes recording what is decided vs. proposed |

These are Design Component files (`.dc.html`) for the Claude Design canvas editor. They are the
**editable source** — to change the canvas, edit these, re-seed with the `design` skill's helper, and
republish to the same artifact URL. Never edit the assembled `.html` (it is generated and ~2 MB; it is
deliberately not committed).

Styling matches the app's own system (`inspecto-ui/tailwind.config.js`: Inter + IBM Plex Mono, indigo
primary, slate greys, 14 / 12 / 10 px type, 6 px radii, the definition-drawer header anatomy).
