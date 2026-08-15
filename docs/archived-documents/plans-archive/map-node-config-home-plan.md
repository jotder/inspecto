# Plan — a flat-config home for an authored `transform.map` node

**Status: ✅ SHIPPED and ARCHIVED 2026-08-15 — option 1, §2.3 (a) refuse the conflict, §5a hand-rolled
validation, all of §6 verified. As-built:
[`okf/backend/pipeline-graph/pipeline-graph-design.md`](../../okf/backend/pipeline-graph/pipeline-graph-design.md) §16.
Kept for provenance only — never linked as current.**

**What the build added beyond this design:** a third refusal, `MULTI_MAP_CONFIG` (a multi-schema graph
whose map nodes drifted apart — one `processing.map` serves them all); `csv` as a *second* derived key
beside `schema` (`PipelineDryRun` puts it on the map node, so refusing it would break a dry-run graph);
and the explicit decision that `processing.map` is **not** mutually exclusive with `steps:` — §2.1's
reasoning implies it, but the parser's exclusivity list is a separate place that had to be left alone.
Created 2026-08-15 (AUTHOR-1 follow-on (a)). Option 1 of the two the grounding surfaced; option 2
(relocate authoring to the parser node via a `mapping` component) is recorded in §7 and NOT chosen here.

---

## 1. The defect, precisely

`PipelineEditable.lower()` has **no branch for `transform.map`**. The node is skipped in the main loop
with the comment *"transform.map + enrichment: derived / companion-persisted — nothing to lower"*
([`PipelineEditable.java:334`](../../inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java)).
So an author who types into a map node's dialog gets `200 written:true` and loses the input.

The map node's dialog is free-form (map has no `AttributeSpec`), so the keys an author can type are
whatever they type. Two of them are **executable**:

| Key | Read by | Meaning |
|---|---|---|
| `columns` | `RowShaper.columnsOf` ([`RowShaper.java:348`](../../inspecto-engine/src/main/java/com/gamma/pipeline/exec/RowShaper.java)) | explicit projection list, `[{name, expr}]` |
| `rules` | `RowShaper.mappingSchemaOf` ([`RowShaper.java:397`](../../inspecto-engine/src/main/java/com/gamma/pipeline/exec/RowShaper.java)) | mapping-component rule list, no declared field types |

⚠ **This is not authoring-only.** The graph executor that reads them is **armed in production
today**: `JobService` → `PipelineJobRunner.run`
([`PipelineJobRunner.java:265`](../../inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java))
→ `PipelineExecutor` → `RowShaper.shape`. It is a *different* thing from the still-unbuilt
**branch-aware executor** (that plan is about non-blocking ingest scheduling, not about whether
`columns` executes). So a preserved `columns` changes what a production run projects, on the next run.
That is the whole reason this needs a decision rather than a patch.

### 1.1 Why a blanket rule is wrong

⛔ **"map has config ⇒ refuse" would refuse every existing pipeline's save.** `PipelineLift` puts
exactly one key on a lifted map node — `schema`, the legacy config's schema map, carried wholesale
([`PipelineLift.java:266-270`](../../inspecto-engine/src/main/java/com/gamma/pipeline/PipelineLift.java)) —
and `lower` drops it **on purpose**, because it is lift-derived, not authored. Any rule here must
distinguish *derived* keys from *authored* ones. That is the narrow shape of the problem.

---

## 2. What option 1 proposes

Give `transform.map` a home in the flat `*_pipeline.toon` under **`processing.map`**:

```toon
processing:
  map:
    columns:
      - name: amount_major
        expr: CAST(amount_minor AS DOUBLE) / 100
      - name: event_day
        expr: CAST(event_time AS DATE)
```

`lower` writes `processing.map` from the map node's authored keys; the read side lifts it back onto the
map node so the round-trip closes.

### 2.1 Why `processing.map` and not a `steps:` entry

⛔ **`transform.map` must stay out of `STEP_KIND`.** The class javadoc is explicit
([`PipelineEditable.java:60-64`](../../inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java)):
giving map a `steps:` kind would change **when `steps:` is emitted at all**. The chain is written in one
of two spellings — legacy singular keys, or a `steps:` list — and `isLegacyShaped` decides which. A map
node entering the chain would flip files that today round-trip byte-for-byte into `steps:` files. That
is a silent rewrite of every existing pipeline, which is exactly the property the multiplicity slice
was careful to preserve. `processing.map` sits beside `processing.dedup` / `processing.join` /
`processing.summarize` instead, none of which are chain steps either.

### 2.2 The derived-vs-authored rule

`lower` writes `processing.map` from **only** the keys in an allow-list — `columns`, `rules` — and
continues to ignore `schema` (lift-derived) and anything else. Stated as an invariant:

> A map node's `schema` key is never lowered. A map node's `columns`/`rules` are lowered verbatim.
> An unknown key on a map node is **refused** with `UNSUPPORTED_BINDING`'s sibling code (§4), not
> silently dropped — the AUTHOR-1 lesson.

⚠ The allow-list is the load-bearing piece, and it must be derived from what `RowShaper` actually
reads, not from what the dialog can type. If a future key becomes executable in `RowShaper`, it must
join this list in the same change — the same "two sides, one committed vocabulary" failure mode the
`AttributeSpec` contract test (`b061ff41`) was built to catch. §6 proposes pinning it the same way.

### 2.3 Precedence against the existing mapping inputs

There are already three ways rules reach a map node, and this adds a fourth. The existing precedence,
from `PipelineConfigParser`
([`applyMappingFile`, `PipelineConfigParser.java:882-901`](../../inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java)):

1. `processing.mapping_file` (explicit ref — fails fast if unresolvable)
2. the sibling mapping CSV (best-effort, by presence)
3. inline `mapping.rules` on the schema

All three land in the **same place**: `schema.mapping.rules`, which `PipelineLift` then puts on the map
node nested under `schema`. `RowShaper.mappingSchemaOf` prefers `schema` over a bare `rules`
([`RowShaper.java:392-399`](../../inspecto-engine/src/main/java/com/gamma/pipeline/exec/RowShaper.java)),
and `columnsOf` prefers `columns` over everything.

**⚠ This is the sharpest question in the design and it needs an explicit answer, not a default.** As
the code stands, an authored `columns` would **silently outrank** a `mapping_file` the operator
declared explicitly — because `columnsOf` checks `columns` first and never consults the schema when it
finds one. Two defensible answers:

- **(a) Authored wins, loudly.** Keep `columnsOf`'s order, but make `lower` **refuse** a save where the
  map node has authored `columns` *and* the file declares `processing.mapping_file` — the author must
  delete one. No silent precedence, no new runtime rule.
- **(b) Explicit reference wins.** `mapping_file` outranks authored `columns`; the editor shows the
  authored list as overridden. Requires a `RowShaper` change, i.e. a behaviour change to a live
  production path.

**Recommendation: (a).** It needs no runtime change, it cannot alter an existing run, and it makes the
conflict visible at authoring time rather than at 3am. (b) is the better end state if the product wants
`mapping_file` to be authoritative, but it should be a separate, deliberate change with its own
migration note.

---

## 3. Scope — what this touches

| Layer | Change | Risk |
|---|---|---|
| `PipelineEditable.lower` | write `processing.map` from the allow-listed keys | med — the one function every graph save goes through |
| `PipelineEditable` (read half) | lift `processing.map` back onto the map node | med — must not disturb the lift-derived `schema` |
| `PipelineConfigParser` | parse `processing.map` into whatever `PipelineLift` reads | **high** — the legacy engine's own path also reads `processing` |
| `ConfigSafetyValidator` | validate the new list-of-objects at the 422 gate | low, but see §5 |
| `ConfigSpecs.pipeline()` | declare the key | **blocked** — see §5 |
| `docs/GLOSSARY.md` §13 | new config key in the touchpoint table | low |
| UI | map node dialog stops being free-form? (out of scope; see §7) | — |

---

## 4. Refusal codes

Reuse the AUTHOR-1 idiom — a named code the UI renders next to the offending node:

- `UNSUPPORTED_MAP_KEY` — a map node carries a key that is neither lift-derived (`schema`) nor
  executable (`columns`, `rules`). Message names the key and lists what is accepted.
- `MAPPING_CONFLICT` — (only under §2.3 option (a)) authored `columns` alongside a declared
  `processing.mapping_file`.

Both are `PipelineCompileException.Refusal`s raised in `lower`, so they arrive at the editor exactly the
way `UNSUPPORTED_BINDING` does. ⚠ The offline mock
(`inspecto-ui/src/app/inspecto/mock/pipeline-editable.ts`) must refuse the same things **in the same
commit** — the mock is contract, not fixture.

---

## 5. ⚠ The spec-layer blocker

`ConfigSpecs`/`FieldSpec` has **no list-of-objects primitive**. `FieldType`
([`FieldType.java:11-22`](../../inspecto-config/src/main/java/com/gamma/config/spec/FieldType.java))
offers scalar `LIST` and untyped `MAP` only. Every existing list-of-objects in the config
(`processing.schemas`, top-level `sinks`) is validated by **hand-rolled code walking raw `List<?>`** in
`ConfigSafetyValidator` ([lines 102, 150](../../inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java)),
never through a declared `FieldSpec`.

So option 1 has two sub-options:

- **5a — follow the precedent.** Hand-roll `processing.map.columns` validation in
  `ConfigSafetyValidator`, declare nothing in `ConfigSpecs`. Ships without new spec-layer work; adds a
  third hand-rolled list walker.
- **5b — build the primitive.** Add a real list-of-objects notion to the spec layer and migrate
  `schemas`/`sinks` onto it. This is the *"a real map-of-objects notion in the spec layer would subsume
  both this and `checkSink`"* item already recorded in `BACKLOG.md` as waiting-for, with nothing
  scheduling it.

**Recommendation: 5a for this plan, and let 5b stay its own scheduled item.** Bundling the primitive
into this change would make a medium-risk config-format addition into a large spec-layer refactor
touching two existing validated shapes. But note the honest cost: 5a means the author gets a 422 with a
hand-written message and **no generated form control**, because a form control comes from a `FieldSpec`.

---

## 6. Verification

Success criteria, in the order I would build them:

1. **Round-trip** — a pipeline with an authored `processing.map.columns` lifts to a map node carrying
   `columns` and lowers back **byte-identical**. → `PipelineEditableTest`.
2. **No existing file changes shape** — every fixture pipeline still round-trips byte-for-byte, and no
   file that is legacy-shaped today starts emitting `steps:`. → the existing round-trip suites
   (`PipelineStepsFileRoundTripTest`, `PipelineSinksFileRoundTripTest`).
3. **The derived key is still dropped** — a lifted node's `schema` does not appear in
   `processing.map`. → `PipelineEditableTest`.
4. **The refusals fire** — unknown map key → `UNSUPPORTED_MAP_KEY`; conflict → `MAPPING_CONFLICT`; and
   the **offline mock refuses identically**. → `PipelineEditableTest` + `pipeline-editable.spec.ts`.
5. **It executes** — an authored `columns` actually projects, through the real
   `PipelineExecutor`/`RowShaper` path, not a `fromMap` test. ⚠ **A config-format slice is NOT verified
   by a `fromMap` test** (the multiplicity plan's durable rule).
6. **The allow-list is pinned** — a contract test asserting the `lower` allow-list equals the keys
   `RowShaper` actually reads, so the two cannot drift (the `b061ff41` idiom).

Full reactor baseline to beat: **3279 tests, 0 failures, 5 skipped** (`efe7b460`), plus UI **2415
passed / 5 skipped**.

---

## 7. Option 2, recorded and NOT chosen

Require an authored mapping to go through a saved `mapping` component and a reference. Rejected as the
*primary* answer because `processing.mapping_file` is **`PARSER_OWNED`**
([`PipelineEditable.java:127-128`](../../inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java)) —
the key belongs to the parser node, so this relocates the authoring surface from the map node to the
parser node. That is a different UX from what AUTHOR-1(a) describes (an author typing into the *map*
node's dialog), and it does not fix the silent drop; it only makes the map dialog's input permanently
meaningless, which argues for the map dialog losing its free-form rows entirely.

⚠ Option 2 is **not worthless** — it is arguably the cleaner product answer, and it costs no new config
key. If the operator prefers it, the work becomes: make the map node's dialog read-only/derived, and
route authoring to the parser's Mapping reference. That is a UI plan, not a config-format plan.

---

## 8. The decision asked for

1. **Option 1 or option 2** (§2 vs §7) — new config key, or relocate authoring to the parser.
2. If option 1: **§2.3 (a) or (b)** — refuse the conflict, or make `mapping_file` authoritative.
3. If option 1: **§5a or §5b** — hand-rolled validation now, or build the spec-layer primitive first.

**Operator answered 2026-08-15: option 1 · (a) · 5a — the recommendation, taken as written.**

My recommendation: **option 1, (a), 5a** — the smallest change that makes an authored map node honest,
with no behaviour change to any live run, and the two larger items (the spec primitive, `mapping_file`
authority) left as their own scheduled decisions.
