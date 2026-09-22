---
type: Plan
title: Pipelines workbench — MoSCoW analysis
description: Must / Should / Could / Won't for the Pipelines workbench, prioritised against one goal — a data engineer authoring and TRUSTING a production ingest Pipeline without leaving the UI — and grounded in a from-scratch build driven through the workbench on 2026-09-22.
tags: [pipelines-workbench, moscow, prioritisation, pipeline-graph, evaluation]
timestamp: 2026-09-22T00:00:00Z
---

# Pipelines workbench — MoSCoW analysis

## 0. The question this prioritises against

MoSCoW is meaningless without a stated goal and a timebox, so:

> **Goal.** A data engineer can author a production ingest Pipeline in the workbench, convince
> themselves it is correct, arm it, and afterwards tell whether it is still correct — **without
> leaving the UI and without reading TOON by hand.**
>
> **Timebox.** The next release cycle. "Won't" means *not this cycle*, never *not ever*.

The goal is deliberately about **trust**, not features. The workbench is already feature-rich; what
the build in §1 exposed is that the parts which would let you *believe* a Pipeline are the thin ones.

## 1. Evidence base

Three independent readings, not opinion:

1. **A from-scratch build, run and verified** — a post-mediated telecom xDR Pipeline over a local
   inbox, 1200 records, output read back out of Parquet and reconciled;
   [`postmed-xdr-pipeline-build.md`](postmed-xdr-pipeline-build.md) (findings `F1`–`F4`, `W1`–`W9`).
2. **A capability inventory of the workbench** — 30 files under
   `inspecto-ui/src/app/modules/admin/pipelines/`, 28 spec files, the routes and API surface.
3. **The served processor taxonomy** — `ProcessorCatalog`: **119 processors, 36 DELIVERED,
   16 PARTIAL, 67 PLANNED** across eight families.

⚠ Two items below are marked *(reported, not verified here)*: they come from reading code comments
rather than from driving the product, and should be confirmed before anyone plans against them.

## 2. As-built baseline — what already works

Worth stating plainly, because the Musts in §3 are almost all *gaps on top of a working product*, and
a MoSCoW that reads as a list of complaints misrepresents the surface.

| Area | State |
|---|---|
| Lifecycle | create (name + format picker), open (multi-tab), rename, change id, delete (with impact check + cascade), duplicate, save-as-template, activate/deactivate, park a Step |
| Authoring | G6 canvas, drag-drop and two-click connect, route/branch edge relationships, **schema-driven typed config panes** per Step kind, Record Transformer with a Fields \| SQL peer view |
| Safety | validate-before-save, live debounced re-validation, **fail-closed save** (422 with named refusals, `written: false`), unsaved-change guards (beforeunload, tab-close, tab-switch), undo/redo, read-only lens |
| Round-trip | a hand-authored **flat** config lifts into a correct graph and saves back with **every key preserved**, including the four undeclared `dirs` leaves — verified byte-for-byte on keys |
| Transfer | export config / bundle / document, import bundle |
| Palette | server-served taxonomy, undelivered processors visible-but-inactive with a *"Not yet available"* label |

That is a credible workbench. `W1`, `W2`, `W6`'s fail-closed behaviour and `W7`'s key preservation
were each *tested* and each held.

## 3. MUST HAVE — the goal fails without these

### M1 · Test the parse stage on real bytes, in the UI — **GAP** (`W4`)

Dry-run seeds **after** the parse (*"a bounded sample through the transform→sink subgraph"*). So the
delimiter, header handling, comment character, quoting and `null_strings` — for a delimited feed, the
only decisions that are actually risky — cannot be exercised in the builder at all. In the §1 build
those settings were the entire difficulty; everything downstream was 1:1 mapping.

A builder that cannot test the stage where the mistakes live is not a builder, it is a diagram editor.
**Needed:** pick a file from the inbox (or an uploaded sample), run the real parse frontend over the
first N lines, and show the resulting typed columns plus the rejects — before save.

### M2 · Seed a test from real data, not hand-typed JSON — **GAP** (`W4`)

Today the dry-run sample is a JSON array the author types by hand. For the 18-column feed in §1 that
is ~850 characters of hand-written JSON, while 1200 correctly-typed rows sat in the Pipeline's own
inbox and store. **Needed:** "seed from inbox file" / "seed from last batch" / "seed from the Dataset".
M1 and M2 are one change in practice and should ship together.

### M3 · Tell the truth about what has run — **GAP** (`W3`)

Every node read *"not yet tested."* on a Pipeline with three completed runs, four `SUCCESS` batches and
1219 rows in its own status store. The per-node signal is dry-run state only and never reconciles with
`/provenance`. A trust surface that reports *untested* for a Pipeline in production teaches the
engineer to ignore it — which costs more than showing nothing.

### M4 · Reject accounting the builder can trust — **GAP** (`F2`, bug)

A 20-row file lost one truncated record. The batch audit reported **`rejected_count = 0`** and
`total_input_rows = 19` — the dropped row is counted as neither input nor reject, and appears only in
an errors CSV under the undeclared `dirs.errors`, which nothing in the audit row points at. Counters
that reconcile (19 = 19) while a record is missing are worse than absent counters. Fix the accounting,
then surface rejects in the workbench (today the builder has no incident/log view of its own; `/runs`
is a separate surface).

### M5 · Keep the two properties that already hold — **SHIPPED, and must stay**

- **Save is fail-closed.** An invalid graph is refused 422 with named refusals and `written: false`;
  the config was byte-identical afterwards.
- **Save preserves keys it does not model,** including engine-read/authoring-invisible ones.

These are the reason the §1 build survived being driven by a stranger. 🔴 They are also exactly the
kind of property that regresses silently, and the round-trip test that would catch it
(`GET /graph/raw` → `PUT /graph` → assert the key set and the re-read values) **does not exist as a
guard**. Adding it is a Must, not a Should.

### M6 · Full authoring lifecycle with typed config — **SHIPPED**

Listed so the Must tier is honest about what it already gets: §2's lifecycle and authoring rows.

## 4. SHOULD HAVE — painful, with a workaround

| # | Item | Evidence | Workaround today |
|---|---|---|---|
| S1 | **Insert a Step into a chain in one gesture.** Palette add drops a *disconnected* node; inserting mid-chain is three graph operations and there is no drop-onto-an-edge affordance. | `W5` | do the three operations; validation catches the orphan, so it fails safe |
| S2 | **Format-preserving writer.** Save is semantically lossless but rewrites the whole file — blank lines, alignment, quoting, key order, trailing newline. Every UI save is a noisy git diff. | `W7` | hand-restore formatting, or accept the churn |
| S3 | **Created Pipelines in a per-Pipeline directory.** The scaffold lands flat at `spaces/<space>/config/<name>_pipeline.toon`; all eight existing Pipelines in that space use `config/<name>/`. Two layouts, and a `<name>_schema.toon` sibling would land at the root. | `W8` | move the file by hand |
| S4 | **One shape per URL.** `GET …/graph` is a display projection that is **not** a valid `PUT …/graph` body (422 `NO_PERSISTENT_SINK`, `PARSER_NO_SCHEMA`); the editor knows to use `/graph/raw`. Any other client round-tripping the obvious pair fails. | `W6` | use `/graph/raw`; document it |
| S5 | **Read-only lens disables the palette.** 36 `Add …` controls render `disabled: false` in the `View` lens. The handler guard holds — clicking mutates nothing — so this is presentation, but a keyboard/screen-reader user gets no cue. | `W9` | none needed; cosmetic + a11y |
| S6 | **Run history, not just the last run.** The canvas overlays the most recent batch only; there is no per-node history or trend. | inventory | `/runs` |
| S7 | **A responsive floor for the 3-pane shell.** At ~660px the canvas collapses to a sliver while Properties holds half the width. | observed | widen, or collapse the docks by hand |
| S8 | **Make `duplicate_check`'s grain obvious.** It is file/marker-grain; record-grain duplicates pass through. The key name reads as if it covered both. | `F3` | add a dedup Step (needs `output_store:`) |
| S9 | **Errors report one row per bad line.** One truncated line produced nine rows (`c9`…`c17`), each repeating the whole raw line. | `F4` | read past the repetition |

## 5. COULD HAVE — real value, clearly deferrable

- **Config versioning and diff.** Undo/redo is in-session and capped at 50, lost on reload; there is no
  persisted history or "what changed since the last save". Mitigated by the configs being in git.
- **A genuine "Run to here".** The button is wired to `POST …/run?to=`, but the `to=` cutoff is
  *(reported, not verified here)* unbuilt server-side — the whole graph runs and the dialog reports
  that via `warnings`. Either build the cutoff or rename the affordance.
- **Persisted node coordinates.** Layout is recomputed; authored Pipelines store no positions. Fine for
  a 4-node chain, less fine for a routed graph with branches.
- **Telecom processors.** `transform.telecom.rating`, `transform.telecom.roaming` (TAP3/CIBER
  surcharge), `transform.telecom.simbox` and `parser.asn1.per` are all `PLANNED`. Note what this
  means for positioning: **post-mediated** ingestion is entirely on the delivered path — local
  Collector + delimited parser + mapping + persistent sink, which is why the §1 build worked first run
  — and `parser.asn1.ber`, the decoder for *pre*-mediation switch CDRs, is delivered. The gap is
  telecom *business logic*, not telecom *ingestion*.
- **Assist-authored Pipelines.** The right dock has an `assist` tab; not exercised in this evaluation,
  so deliberately unranked rather than guessed at.

## 6. WON'T HAVE — this cycle, with the reason

- **The 67 `PLANNED` processors as a programme.** 36 delivered / 16 partial / 67 planned is a
  multi-year taxonomy, and the palette already handles it honestly (visible, inactive, labelled *Not
  yet available*). Pull individual processors forward on demand; do not treat the backlog as a wave.
- **The Analytics / Time-Series / Semantic family inside the Pipeline builder** (0 delivered,
  3 partial, 11 planned). Measures and LOD aggregation belong to the Studio/measure-grammar surface;
  building them into the ingest canvas would put two products on one screen.
- **Graph-store-backed lineage in the workbench.** The Topology lens already joins Pipelines at shared
  stores, which covers the question this would answer.
- **Collaborative / multi-author editing.** No demand recorded, and the unsaved-change guards
  currently assume a single author per tab.

## 7. The one-screen summary

| Tier | Count | Of which shipped | The single sentence |
|---|---|---|---|
| **Must** | 6 | 2 (`M5`, `M6`) | You can build a Pipeline here; you cannot yet **test the parse stage** or **trust the run and reject signals**. |
| **Should** | 9 | 0 | Mostly one-change fixes: orphan insert, file placement, formatting churn, a11y, response to the API shape trap. |
| **Could** | 5 | 0 | Versioning, a real partial run, and telecom business logic. |
| **Won't** | 4 | — | The processor backlog as a wave, and analytics-in-the-ingest-canvas. |

**If only three things ship:** `M1`+`M2` together (parse-stage test seeded from real data), `M4`
(reject accounting, a genuine bug), `M3` (reconcile node state with run provenance). Those three turn
a diagram editor with a good save path into something an engineer can trust. `M5`'s missing round-trip
guard is the cheapest of the five and protects what already works.

## 8. Decisions owed

| # | Decision | Why it blocks |
|---|---|---|
| D1 | Does the parse-stage test (`M1`) run the **real** frontend server-side over real bytes, or a client-side approximation? | An approximation that disagrees with the engine is worse than nothing — it would manufacture false confidence, the same failure mode as `M3`. |
| D2 | Is `rejected_count` (`F2`) defined as *records the parser refused* or *records not persisted*? | The fix differs, and the errors CSV and the batch row currently answer differently. |
| D3 | Do UI-created Pipelines move to `config/<name>/` (`S3`), or do the eight existing ones flatten? | Either is defensible; two layouts is not. Whichever wins needs a guard. |
| D4 | Is the formatting churn (`S2`) accepted as the cost of UI authoring, or is a format-preserving writer funded? | Decides whether Pipeline configs stay comfortably hand-editable. |

## References

- [`postmed-xdr-pipeline-build.md`](postmed-xdr-pipeline-build.md) — the build this analysis is grounded in (`F1`–`F4`, `W1`–`W9`)
- [`pipeline-config-keys.md`](../okf/backend/pipeline-graph/pipeline-config-keys.md) — the accepted-block census and the passthrough contract behind `M5`
- `inspecto-engine/src/main/java/com/gamma/pipeline/ProcessorCatalog.java` — the 119-processor taxonomy the palette and the EDITIONS board both render
