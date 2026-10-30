# Unsaved Investigation and the simpler Investigation tab (`LA-UNSAVED-INVESTIGATION-1`)

**Status:** SHIPPED 2026-10-30 (`f83bce424`…`226571957`). Item `module.la-core`. Backlog row P2 feature
`LA-UNSAVED-INVESTIGATION-1`.

## 1. The operator's request and decisions

The operator asked to simplify how an Investigation starts and the Investigation tab's forms: one place for Dataset /
Start from / Entity Type; no Name and Purpose to start; Time column and Time zone as two plain pickers; Degrees as
`− n +`; one Expand control instead of Expand plus "Expand next degree"; the "Show the full graph to pick start
entities" checkbox gone; the "More steps" menu replaced by visible groups.

| # | Decision (operator, 2026-10-29) |
|---|---|
| D1 | The start form lives ONLY in the Investigation tab. On the Investigations level the centre start box is a pointer, "Start from the Investigation tab →", which opens the tab. The Explorer level (no Investigation tab form) keeps its centre box. |
| D2 | Work **unsaved**. Start and Expand need no Name or Purpose. **Save** asks for them, creates the Investigation and records the steps taken so far, in order. |
| D3 | The checkbox "Show the full graph to pick start entities" and its canvas notice (with its "Show the query graph" action) are removed. Start entities come from the canvas selection. |

## 2. Design (UI only; no server route)

**Unsaved state.** `InvestigationSessionStore.unsaved` holds what Save needs: the binding (projection, time column and
zone, events column, profile, presets, max degree, window end), the default Name and Purpose
(`newInvestigationDefaults`, computed by the start form), the Save dialog's last edits, and the ordered step list.
Nothing is written to the server while unsaved.

**Running steps while unsaved.** The canvas is the explorer graph, drawn with the existing calls:

| Unsaved action | Canvas (existing call) | Step recorded |
|---|---|---|
| Start (one entity, Entity List, pasted values) | `drawStart`: the values plus one ring, busy neighbours held | `seed` of the values found, then `expand` 1 degree from the outer ring |
| Expand on start, Degrees n > 1 | `growBasic` from the nodes not expanded yet | `expand` n-1 degrees from the outer ring |
| Start (whole Dataset / graph on screen) | the Dataset is drawn / the graph stays | nothing yet (a seedless start) |
| Start from selected | `expandNode` for each selected node | `seed` of their values, then `expand` 1 degree from them |
| Expand (the tab's one Expand control) | `growBasic` | `expand` with From (outer ring, or the named values), Degrees, Direction, Link kinds, Hold |

Every other step (Keep, Hide, Exclude, Annotate, Time window, Threshold, Snapshot marker, Compare, Connect start
entities, Scope list) needs a recorded Working Set: while unsaved the tab says "Save first" with a Save button.
An explorer Undo of an unsaved Expand also drops its step.

**Save = create, then replay.** Save opens a dialog with Name and Purpose, pre-filled with the defaults; edits are kept
when the dialog is cancelled. Save then calls the existing `POST /inv/investigations` (create) and replays the steps
through the existing step route `POST /inv/investigations/{id}/ops`, in order:

- `seed` → one `seed` op with the values and the Entity Type.
- `expand` → per degree, one `expand` op naming the values that degree expanded from on screen (`walked`, capped at
  `selectionExpandCap` and stopped where the screen stopped), with the profile presets laid under (`withPresets`) and
  the recorded Direction, Link kinds and Hold (`growExpandBody`). Each list is first spoken in the Investigation's own
  ids (the Working Set's `exploreAliases`, so a masked Space's aliases are resolved) and kept to what the Working Set
  holds; a degree whose values the server never admitted expands from what the server's previous degree admitted
  (the first degree from the outer ring, `degreeState`). The server never gets an id it would refuse (422).
- **The unsaved screen is the server's evaluation** (operator, 2026-10-29): every unsaved degree (the start's ring, each
  Expand, Start from selected's ring) is drawn from `POST /inv/expand/preview`, which takes the create's binding and the
  unsaved `seed` / `expand` bodies and evaluates them with the step route's own code (`params`, `requireBindings`,
  `expandFrontier`, `sensitivity`, the shared `expandRead`, the evaluator): presets (window, minimum events, row budget,
  fan-out, busy limit) and the fold filters apply, so the drawing equals what Save records. Read-shaped and ungated like
  the explorer's neighbours reads, expensive rate class, aliases in and out as exploration; a four-eyes-sensitive degree
  answers `needsApproval` and cannot run unsaved. Parity is pinned by `ControlApiUnsavedPreviewTest`. Security review
  (2026-10-30): on a masked Space the route takes only aliases its reads served (a raw id is 422 and nothing is minted or
  written to the alias book, so guesses cannot be matched in bulk; the Studio seeds from the alias the explorer drew);
  each expand read is audited as `LINK_EXPANDED`; one request holds at most 4 steps and reads at most
  `investigation.expand_budget_max` rows; the Studio sends the Working Set as one seed plus the next degree and merges
  the answer (`mergeWorkingSets`), so the cost does not grow with the history; no hash is returned. The Save fallback
  above stays as a safety net (data that changed between the preview and Save).

The server evaluates every replayed step, so the saved Working Set is the server's (window presets, row budgets, busy
limit, exclusion lists in force). It can differ from the unsaved explorer drawing where those presets cut: that is the
recorded truth, and the replay is not atomic. A server route that creates and records in one call was considered
(the fallback) and **not needed**: each op is already sealed one by one by the existing routes, and the failure modes
below are said plainly.

## 3. Edge cases

| Case | Behaviour |
|---|---|
| Create refused | Nothing is created; the unsaved steps stay; the error is shown in the dialog's place (toast + tab error). |
| A step refused mid-replay | The replay stops. The Investigation exists with the steps before it; the message names the refused step and the steps not recorded. The tab then shows the saved Investigation. |
| A replayed expand held for four-eyes | The replay stops there: the held step is on record waiting for approval; the steps after it are named as not recorded. |
| Four-eyes while unsaved | An unsaved Expand whose presets would be held (`expandApproval` over the profile presets and the Space's limits) is refused before it runs: "This Expand needs approval from a second person, so it cannot run unsaved. Save first, then Expand." |
| Masking | Start values are the values the analyst typed or chose (as today's New form seeds). Expand lists are sent as value lists: each value is mapped to the Investigation's own id through the Working Set's `exploreAliases` before it is sent; one the Working Set does not hold falls back to the server's own walk, never a refused id. |
| Busy entities | An unsaved Expand with Hold unticked asks the same override confirmation as the saved path; the start ring and Start from selected run the four-eyes check up front. |
| Reader without Incident management | Explores unsaved (start, Expand); Save is disabled with "Needs Incident management." |
| Close, File ▸ New, File ▸ Open | Ask "Save, Discard or Cancel" while unsaved. |
| Reload or leaving the browser tab | The browser's leave prompt (`beforeunload`). Unsaved work is held in memory only, so a reload loses it. Moving to another page of the same Space keeps it (the page is kept alive, `KEEP_ALIVE`). |

## 4. The Investigation tab after this change

Start (no Investigation): the start form only (Start from · Dataset · Entity Type and value · Time column · Time
zone · Degrees `− n +` · Start).

Unsaved: header with the **Unsaved** marker, Save and Close · the step list · Expand (the one control) · Start
entities (Start from selected) · "Save first" for the other steps.

Saved, in order of importance: header · Working Set counts · Expand (degree reached, results, the one form) · Selected
entity · Busy entities · Steps, in visible groups with their actions as buttons (Connect: Connect start entities ·
Time: Time window, Snapshot marker · Compare: Threshold, Compare) · Scope list · links (Step log, settings).

## 5. Test plan

- Store: the unsaved step list (record, pop on Undo, discard); Save replays in order (create, seed, expand); a refusal
  partway stops the replay and names what was not recorded; a held step stops it; a refused create keeps the steps.
- Save dialog: pre-filled with the defaults; edits kept after Cancel.
- Page: the unsaved guard on Close / New (Save · Discard · Cancel); four-eyes refused while unsaved; the pointer
  replaces the centre start box on the Investigations level.
- Start form: no Name / Purpose; Time column and Time zone pickers; Degrees stepper; a reader can start.
- Tab: Unsaved marker, Save disabled with the reason for a reader; one Expand control (no "Expand next degree"); the
  grouped steps open each step in place; the checkbox is gone; axe on the start form, the unsaved tab and the dialog.

## 6. Deferred

- Unsaved work survives a reload only if it is kept in the browser; not built (D2 says in memory; the leave prompt warns).
- Keep / Hide / Exclude / Annotate and the other steps while unsaved.
- On a saved Investigation, **Add start entities…** opens the start form in add mode and records one `seed` step
  (replaces what the removed checkbox offered: start entities outside the Working Set).
