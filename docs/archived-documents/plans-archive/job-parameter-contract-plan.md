# Job Parameter Contract & Runtime Expressions — plan

**Status:** ✅ **SHIPPED and ARCHIVED 2026-08-10** — all 17 steps delivered; kept for provenance, never
maintained. As-built lives in [`okf/backend/control-plane/jobs.md`](../../okf/backend/control-plane/jobs.md)
*§The parameter contract & runtime Expressions* and
[`okf/frontend/features/jobs.md`](../../okf/frontend/features/jobs.md); the deliberate deferrals (`mail.send`
CC, UI-unreachable INTEGER tokens) are in [`BACKLOG.md`](../../BACKLOG.md) §4. · **Owner:** shift ·
**Created:** 2026-08-06 · **Refined:** 2026-08-06
(grounded pass, UI + backend — every §1 evidence row verified against source; corrections marked ✎)

> **Read the delivery table (§10), not the body, for what was actually built.** Grounding refuted a
> number of this plan's own premises as it went — each step's row records the correction. The largest:
> "widen `JobUpsert`" (step 16) was not enough, because the write body had always been flat snake_case
> and the server absorbs unknown keys as *parameters*; and step 13's `expressions` flag has no
> `AttributeSpec` home by design, not by omission.

Make a Job Type's declaration the *complete and only* source of its authoring form, make
`$`-expressions resolvable wherever an author can type a value, and make the expression vocabulary
itself **extensible by plugins** rather than a hardcoded switch.

## 0. Design principle

**Versatility over built-ins.** The framework should let a small number of generic Job Types
(`sql.template`, `report`, `mail.send`) cover most needs through a rich declaration contract and a
rich runtime vocabulary — instead of accumulating bespoke hardcoded Job Types for each new use case.
Every decision below is taken in that direction: capability is *added by registration*, never by
editing a `switch` in the engine.

## 0-A. Decision — 'Job' is user-facing again (un-banned 2026-08-06)

Operator decision: **Job returns as a first-class user-facing concept** — any scheduled Executable
the user needs to run freely over data at rest: maintenance, periodic report/enrich, and dataset
operations. This reverses the amendment v1.0 (2026-08-05) user-surface retirement ("Job survives as
engine-internal scheduling vocabulary only"). Why the reversal is sound, not churn:

- The retirement's replacement path — table-entry Pipelines — hangs on amendment Phase 3 S3, which
  the amendment's own design spike deferred as "genuine new design, not a slicing choice" (no
  executable counterpart; `dirs.poll` load-bearing four ways). The retirement had no near-term
  vehicle.
- Jobs meanwhile are the shipped at-rest surface: **9 registered Job Types**, four trigger kinds
  (`cron` · `on_pipeline` · `on_signal` + `when:`/`bind:` · manual — `JobConfig.java:42-45`), plus
  Runs/artifacts/metrics routes. What they lack is a complete authoring contract — exactly this plan.
- Nothing else reopens: a Job is still not a Step (GLOSSARY §5); table-entry Pipelines remain an
  *additive complement* (the S3 design of record proceeds on its own thread), never a replacement.

Recorded 2026-08-06 in `docs/GLOSSARY.md` (§6-A Job entry + §13 row) and as a supersession note in
`superpower/elt-final-amendment-plan.md` (Phases 3/6 user-surface scope). Done with this refinement;
delivery starts at step 1.

## 1. Problem

### 1.1 The declaration contract is narrower than the renderer

| Evidence | File |
|---|---|
| Widget type guessed from the parameter *name* (`name === 'sql' ? 'multiline' : 'string'`) | `inspecto-ui/src/app/modules/admin/jobs/job-parameter-specs.ts:20` |
| Job Type dropdown hardcodes 5 ids; the registry registers **9** (`enrich` `report` `maintenance` `pipeline` `sql.template` `consignment.process` `recon.run` `caserule.evaluate` `objects.analytics`) | `job-attributes.ts:15-21` vs `JobService.java:229-327` |
| `DATE` / `INSTANT` / `DATASET_REF` params all render as a bare text input (default branch) | `job-parameter-specs.ts:19-21` |
| `describeType()` fetches `title`/`description`/`emits`/`artifacts`, uses only `parameters` | `job-form.dialog.ts:168` |
| Descriptor fetch failure silently degrades to key/value, no message | `job-form.dialog.ts:182-186` |
| Free key/value editor accepts any key, unvalidated | `job-form.dialog.html:34-58` |
| Trigger model: UI authors cron/event/manual only; the backend also runs `on_signal` (+`when:`/`bind:`) | `job-attributes.ts:24-30` vs `JobConfig.java:42-45` |

Root cause: **`AttributeSpec` (UI) is strictly richer than `ParameterDecl` (backend).** The decl
already carries `description`/`deduce`/`default` and the UI already surfaces them (help, placeholder,
"Deduced as … when unset" — `job-parameter-specs.ts:32-43`); everything else the renderer can do —
`options`, `pattern`, `min`/`max`, `placeholder`, `dependsOn`, and the `select`/`multiline`/`list`/
`autocomplete` widgets — is undeclarable. The gap is filled by guessing and by the free editor. Note
`datasetOptionLoader()` already exists (`entity-option-loaders.ts:32`, used by the expectation form),
so the `DATASET_REF` picker is wiring, not new machinery.

### 1.2 Authored values are never evaluated as expressions

`ParameterResolver.value()` (`inspecto-engine/.../ParameterResolver.java:105-127`) walks five layers.
**Only two of them evaluate `$`-expressions:**

| Layer | Origin | Evaluated? |
|---|---|---|
| 1. trigger `args` | manual `POST` body / Trigger `args:` | ❌ returned raw |
| 2. signal `bind` | Trigger's `bind:` map | ✅ `deduce()` |
| 3. authored `config` | the `*_job.toon` `params:` block — **what the UI writes** | ❌ returned raw |
| 4. declaration `deduce` | the Job Type's own default expression | ✅ `deduce()` |
| 5. `defaultValue` | literal | ❌ (literal by definition) |

So an author who types `$yesterday` into a parameter field in the UI gets the **literal ten-character
string** `$yesterday` handed to their Job. The runtime vocabulary exists but is unreachable from the
place authors actually type. This is the blocker for the requested behaviour, and it also decides §6:
"a `$` value skips literal validation" is only coherent once those values are genuinely evaluated.

✎ Grounding note: `value()` also carries a sixth, legacy micro-layer — a `pipeline`/`flow` config-key
dual-read shim (`ParameterResolver.java:116-121`) that fires only for the parameter literally named
`pipeline`. Step 1 ports it verbatim into the built-in provider's era; don't design around it.

### 1.3 The vocabulary is hardcoded, undiscoverable, and typos fail silently

`deduce()` is a hardcoded `switch` (`ParameterResolver.java:130-164`). Three consequences:

- **Not extensible** — a plugin cannot contribute a token, so every new capability means editing the
  engine. Directly against §0.
- **Not discoverable** — nothing exposes the token set, so the UI cannot offer a picker or validate.
- **Not fail-closed** — an unknown token returns `null`, falls through to the next layer, and surfaces
  (if at all) as a confusing *missing required parameter*. `$Yesterdy` is indistinguishable from an
  unset field.

## 2. What already exists

Verified 2026-08-06 against `ParameterResolver.java:130-176` — the table is exact (ten literals, one
prefix, two function families). Any plan must extend this set, not duplicate it:

| Token | Form | Yields |
|---|---|---|
| `$today` · `$yesterday` · `$tomorrow` | literal | `LocalDate` at fire time, in the Job's zone |
| `$now` · `$now.epoch_seconds` · `$now.epoch_millis` | literal | fire-time `Instant` |
| `$run.id` · `$run.fire_time` · `$run.actor` | literal | this Run's identity / timing / attribution |
| `$job.last_success_time` | literal | success watermark — the incremental-window anchor |
| `$signal.<dotted.path>` | prefix | a field of the firing Signal's payload |
| `$day(n)` · `$month(n)` · `$year(n)` | function | fire-time date ± `n` (negative = past) |
| `$upstream(<job>).artifact(<name>).<attr>` | function | `ref` \| `rows` \| `bytes` \| `watermark` \| `event_time_min` \| `event_time_max` — the last two resolved **live** from the Consignment registry (§5-B, shipped 2026-08-10; they replaced a dead `time_range`) |

**The requested tokens mostly already exist under different names:** `$SysDate` ≈ `$today`,
`$User` ≈ `$run.actor`, `$EventDayMinus1` ≈ `$day(-1)`. The one genuinely new concept is **Event Day**
(§5). Note the three *forms* — literal, prefix, function — the SPI in §4 must support all three.

## 3. Vocabulary decision — settled

**Canonical style is lowercase-dotted** (`$today`, `$run.actor`, `$event_day`). Decided 2026-08-06;
PascalCase (`$EventDay`, `$SysDate`, `$User`) is **not** adopted, not even as an alias — `CLAUDE.md`
forbids one concept under two names, and ten shipped tokens plus authored `*_job.toon` files already
use the lowercase form. New tokens read `$event_day`, `$event_day(-1)`.

Register **Expression** in `docs/GLOSSARY.md` as the canonical term for a `$`-token resolved at fire
time (verified free 2026-08-06 — the word appears nowhere in the glossary today; lands with step 1).
The code currently calls the same thing "deduce", "`$`-expression" and "token" interchangeably.

## 4. Extensible expressions — the architectural change

Replace the `deduce()` switch with a registry fed by an SPI, mirroring the Job Type design exactly
(`JobTypeProvider` → `JobTypeRegistry` → ServiceLoader + Job Packs). Capability is then added by
*registration*, never by editing the engine.

### 4.1 `ExpressionProvider` SPI

```java
public interface ExpressionProvider {
    List<ExpressionDecl> declarations();
    Optional<String> evaluate(String expr, ExpressionContext ctx);
}
```

`ExpressionDecl` carries the catalog metadata — and doubles as the UI's picker entry:

| Component | Purpose |
|---|---|
| `token` | `$today`, `$signal.`, `$day(n)` — the declared surface |
| `form` | `LITERAL` \| `PREFIX` \| `FUNCTION` — matching the three shapes in §2 |
| `yields` | the `ParamType` it resolves to, so the UI can offer only valid tokens per field |
| `description`, `example` | picker text and a worked sample |
| `availableIn` | which Trigger kinds it is meaningful for — `$signal.*` is meaningless for a cron fire |
| `contextFree` | resolvable from fire time alone (`$today`) vs needs a firing context (`$signal.*`, `$upstream(…)`) — drives the catalog's live preview (§4.3) and author-time resolution checks |

### 4.2 Registry and loading

`ExpressionRegistry` mirrors `JobTypeRegistry`'s loading order: built-ins → classpath providers via
`ServiceLoader` → hot-deployable Job Packs (that *is* the real order today — the ServiceLoader loop
runs as the last step inside `registerBuiltins()`, `JobService.java:213-326`).

✎ Corrected against source — today's duplicate-id handling is **not uniformly fail-closed**:
`JobTypeRegistry.register` throws (`JobTypeRegistry.java:34-35`) and a Job Pack pre-checks every id
so one collision fails the whole pack atomically (`JobPackManager.java:189-201`), but the
ServiceLoader path merely `log.warn`s and skips that provider (`JobService.java:320-326`).
`ExpressionRegistry` adopts the **pack posture in all three paths**: a colliding token fails that
provider's load loudly — built-in, classpath, or pack. Packs' isolated classloader and
unload-quiesce machinery is inherited unchanged.

#### 4.2-A As built (step 5, 2026-08-07)

- **"Loudly" for a classpath provider means startup fails.** The Job Type loop's warn-and-skip was
  deliberately *not* copied: a colliding token leaves the deployment's `$`-vocabulary ambiguous, and an
  authored value would resolve differently depending on which provider won. `JobService` construction
  throws with the offending provider named. ⚠ A mis-packaged expression plugin therefore prevents boot —
  taken on purpose, and consistent with the built-in path, which has always thrown.
- **A pack may contribute only tokens.** The "no `JobTypeProvider` in `META-INF/services`" rejection now
  spans both SPIs, so a pure-vocabulary pack is valid rather than blocked by an incidental requirement.
- **Atomicity spans both registries**: pack tokens register under the same owner as its types, the reject
  path deregisters both, and unload takes tokens back with the types. A pack whose token clashes is
  rejected whole, never half-in.
- The convenience `JobPackManager` constructor was **not** given a default `ExpressionRegistry.withBuiltins()`
  — a throwaway second registry would silently diverge from the one Runs resolve against. Callers pass it.

### 4.3 Catalog

`GET /jobs/expressions` serves the registry — **generated from it, never a parallel hand-maintained
list.** Each `contextFree` entry also carries a **`preview`, evaluated by the registry itself at
request time** ("`$yesterday` → 2026-08-05"); context-bound tokens return their declared `example`
instead. The UI never re-implements evaluation — step 13's "preview matches backend" is then true by
construction. This is what makes the token picker (§8.5) possible and keeps it correct as packs load.

### 4.4 As built (step 1, 2026-08-07) — four calls §4 did not specify

1. **One context type, not two.** The SPI needs a public context, and `ParameterResolver.Context` was the
   package-private record already carrying exactly the right seven components — so it *moved* to public
   `ExpressionContext` rather than being mirrored. `ParameterResolver.Context` is gone; three test classes
   and `JobService` follow the rename.
2. **The registry is threaded, never static.** `ParameterResolver.resolve(...)` takes the
   `ExpressionRegistry` (`JobService` owns one field). No static convenience default exists on purpose: a
   default built-ins-only registry would silently bypass a Job Pack's tokens at the one call site that
   matters.
3. **`ExpressionDecl.form` drives routing, and longest match wins.** `LITERAL` matches exactly,
   `PREFIX`/`FUNCTION` match on a head (`$signal.`, `$day(`). Longest-match is what lets a plugin declare
   `$signal.tenant` without the built-in `$signal.` prefix capturing it — pinned by a test.
4. **Registration is fail-closed now, not at step 5.** §4.2's "pack posture in all three paths" needed no
   waiting: `register` throws on a colliding token and is *atomic* (a collision on any one of a provider's
   tokens leaves the registry untouched). Step 5 only adds the ServiceLoader/pack call sites. An *unknown*
   token still falls through to the next parameter layer — that is step 2's change, and step 1 deliberately
   preserved it so this slice is behaviour-neutral.

### 4.5 As built (step 6, 2026-08-07) — and a step-1 defect it exposed

- ⚠ **Three tokens were wrongly marked `contextFree` in step 1** and are now corrected: `$run.id`,
  `$run.actor` and `$job.last_success_time` need a firing Run/Job. Building the catalog is what surfaced
  it — a "live preview" for `$run.actor` would have shown the author `preview` (the request-time context's
  actor), i.e. a value their Job will never see. Only `$run.fire_time` stays context-free among the
  Run-shaped tokens, because it *is* fire time. The rule the catalog enforces: **no preview is better than
  a fabricated one**; context-bound entries serve their declared worked sample.
- **A FUNCTION token cannot preview itself.** `$day(n)` is a shape, not an expression — `ExpressionDecl`
  gained `sampleExpression()` (a literal's own token, a shaped token's typeable `example`) so the preview
  evaluates `$day(-1)`. This also fixes the meaning of `example`: for PREFIX/FUNCTION it is what an author
  types, for a LITERAL it is what the token resolves to. Both answer the picker's one question; the record's
  javadoc now says so instead of leaving it to be inferred.
- **The preview context is honest about being a preview**: `runId`/`actor` are literally `"preview"` and the
  Run-bound suppliers are empty — safe precisely because only `contextFree` tokens are ever evaluated, so
  none of those components is reachable.
- The route is a plain read (no write root, no payload, no path input), so the `endpoint` skill's write
  gates don't apply; what *does* apply is registration order — `/jobs/expressions` must precede the
  single-segment `/jobs/{name}` regex, and a test asserts the 200 rather than trusting the ordering comment.

## 5. Event Day — deferred, scoped here

**Deferred to its own plan** (decided 2026-08-06). Recorded now so the SPI in §4 is shaped to
accommodate it rather than retrofitted.

The concept: the **business date a Run is *for*, which is not the date it *runs on***. Backfills,
late-arriving data and replay all need it; every existing token derives from fire time and so cannot
express it.

- `$event_day` resolves from, in order: an explicit Run override (making backfill a first-class
  operation), the firing Signal's event date, then the system date at fire time — so cron Jobs behave
  exactly as today.
- **`$event_day(n)` arithmetic is required, not optional** — the driving use case is composing SQL
  predicates in a `sql.template` Job (`WHERE dt BETWEEN $event_day(-7) AND $event_day`). It ships with
  the base token, not after it.
- Its Signal payload field must come from the Signal vocabulary — which now has a design of record:
  the **`dataset.write` Signal** (amendment plan, "Phase 3 S3 DESIGN 2026-08-06"; payload
  `{dataset, rows, at, producer}`). Settle `$event_day`'s Signal field jointly with that thread; that
  unresolved dependency is why this stays deferred rather than in the delivery table.

## 5-A. Jobs on datasets — the trigger side (aligned, mostly out of scope)

The contract above covers *what* a Job does; "run it **on a dataset write**" is a *when*, and its
machinery is already designed elsewhere:

- Backend Jobs already run `on_signal` with `when:` guards and `bind:` (P1c). What's missing is the
  Signal itself: **no Dataset-write Signal exists today** (verified — all three commit mechanisms are
  pipeline-shaped; the Dataset write sites publish nothing). Its design of record is amendment slice
  **S3a**: Signal `dataset.write`, payload `{dataset, rows, at, producer}`, published where a
  Dataset's data becomes visible.
- Once S3a ships, a dataset-driven Job is **pure configuration** — `on_signal: dataset.write`,
  `when: $signal.dataset == 'premium_cdr_view'`, params like `dataset: $signal.dataset` — zero new
  machinery in this plan. That is §0 working as intended.
- In scope here (step 16): make `on_signal` **authorable in the UI** — today `JobUpsert` /
  `JOB_ATTRIBUTES` model only cron/event/manual, so signal-triggered Jobs cannot be authored at all.
  Minimal form: signal id + optional `when:`. A `bind:` editor is unnecessary once step 3 ships —
  authored params evaluate `$signal.*` directly (`bind:` stays supported in TOON).
- Out of scope: publishing `dataset.write` itself (amendment S3a, its own thread).

## 5-B. `time_range` — the attr is dead; replace it with two typed scalars (settled 2026-08-10)

Inherited from the consignment-addressing thread, whose **step 8 is hereby closed as specified**. That plan
(archived, §7-B) framed the open question as *where should the range be stored* — a `RunArtifact` field, or a
Consignment-scoped accessor. **Both options are wrong, because the value has no consumer either way.**

**Grounded 2026-08-10, against source:**

| Claim | Evidence |
|---|---|
| The attr is **always `null`** in every real run | `RunArtifact.timeRange` is written at exactly two production sites, `RunContext.java:81-82` and `:86-87`, both a literal `null`. `ArtifactRecorder.dataset(...)` (`ArtifactRecorder.java:20`) has **no parameter** through which a caller could supply one |
| Its format is fixed only by a **test fixture** | `"<min>..<max>"`, e.g. `"2026-07-01..2026-07-07"` — `ParameterResolverTest.java:74,84-85`. Nothing in `main/` defines or documents it |
| **Nothing splits on `..`** | The only SQL consumer, `SqlParamScanner.substitute` (`:45-56`), wraps the *entire* resolved string in one single-quoted literal. `WHERE event_time >= $time_range` yields `>= '2026-07-01..2026-07-07'` — not a valid DuckDB literal |
| It is **rejected** by the types that would want it | `ParameterResolver.matchesType` (`:139-152`) requires `LocalDate.parse`/`Instant.parse` to succeed on the whole string, so a `DATE`/`INSTANT` param refuses it. Only `STRING`/`TEXT`/`DATASET_REF` accept it — the types that cannot use it |
| **Zero live consumers** | Repo-wide, `time_range` appears in 3 code files (2 main, 1 test) and docs. No `spaces/**` config, no UI, no user guide references it. Changing it breaks nothing |

**Decision — the attr splits into two scalars, resolved live:**

1. **`event_time_min` / `event_time_max` replace `time_range`.** Two scalars, each substituting into SQL
   directly — which is what an incremental window actually needs, and what the opaque `"a..b"` string can
   never be. Non-breaking (row 5 above). `time_range` is **removed**, not aliased: one concept, one word
   (§3, `CLAUDE.md`).
   ⚠ **Correction, found while building (2026-08-10): they yield `STRING`, not `INSTANT`.** This section
   first claimed each end "types cleanly as `INSTANT`". It does not. `EventTimeBounds.min`/`max` are
   deliberately **zone-less** ISO local date-times (`2026-08-04T00:12:30`) — the registry refuses to stamp a
   zone it does not know — so `Instant.parse` fails on them and `ParameterResolver.matchesType` would reject
   an `INSTANT`-typed parameter. `DATE` fails too (it is not a bare date). What actually matters is unchanged
   and is now pinned by a test: each end is a valid **SQL timestamp literal** in the single-quoted form
   `SqlParamScanner` produces, ISO `T` separator and all. Converting to a real instant would mean asserting a
   zone, which is the one thing this layer must not do.
2. **Resolve from the Consignment registry at read time — never from a stored field.** Reuse
   `producerHighWater`'s predicate verbatim, `WHERE table_name = ? AND coalesce(state,'LIVE') <> 'SUPERSEDED'`
   (`DbConsignmentOutputStore.java:229`), folding `min(event_time_min)`/`max(event_time_max)`. No such
   aggregate exists yet — `producerHighWater` (`:226-247`) exposes the **max half only**.
   ⛔ **A stored snapshot is the rejected design.** Addressing step 6 shipped revisions +
   `supersedeOtherRevisions`, so a recompute produces a new revision with different bounds and any frozen
   copy then describes a **superseded** one — the stale-inclusion class that thread spent itself eliminating
   on the read path, reintroduced on the metadata path. A stored field also costs a signature change to
   `ArtifactRecorder.dataset(...)` across its 4 call sites, and can never serve ingest (below).
3. **Key on the sink `store` name**, which requires `PipelineJobRunner` to record a `RunArtifact` per sink
   with `ref = store`. It records **none at all** today; the identifier is already in scope via
   `PipelineStores.produced(g)` (`PipelineJobRunner.java:234`) and is the same string `PartitionSinkWriter`
   uses as the registry `table_name` (`:92`, the sink node's `store` config key).
   ⚠ **This is the only namespace where `RunArtifact.ref` and `consignment_outputs.table_name` coincide.**
   Of the 4 existing recorders, 3 write synthetic catalog labels (`"maintenance_backups"`,
   `"maintenance_storage"`, `"ops_analytics"`) and the 4th writes a job-authored `sink_dataset`; more
   decisively, **none of the four writes registry rows at all**, so there is nothing to join against. They
   return `null` honestly. ⛔ Do **not** bridge the gap with a store→dataset reverse lookup — it is ambiguous
   by construction (`putIfAbsent`, first-scan-wins) and is already a documented rejected design
   (`okf/backend/engine/consignment-addressing.md:41-44`).
4. **Ingest stays out.** `BatchProcessor`/`BatchIngestStrategy` live in `com.gamma.inspector` with no
   `JobContext` in scope — the `runId` it stamps into the registry is `null` structurally, not by oversight.
   A Consignment-scoped accessor for ingest bounds is **deferred until a consumer demands one**; adding a
   second surface now would leave two tokens for one concept.

**Why this lands here and not in the addressing thread:** the `$upstream` attr set is this plan's §2 surface
and the SPI in §4 owns it. The addressing plan is archived; its BACKLOG row and OKF concept point here.

## 6. Expression evaluation — behaviour

1. **Evaluate layers 1 and 3.** Route authored `config` values and trigger `args` through the
   registry, so a `$`-token typed in the UI resolves at fire time. (Layer-1 entry point:
   `POST /jobs/{name}/trigger` body `params` → `JobRoutes.java:120-142`.)
2. **Escape literals.** `$$` yields a literal `$`, so a value that genuinely starts with `$` (a shell
   string, a currency amount) stays expressible. Must ship *before* §6.1, or existing configs holding
   a literal `$` break.
3. **Unknown tokens fail loudly.** A value matching `^\$` whose token is unregistered fails the Run
   `REJECTED` with "unknown expression `$Yesterdy`" — never silent fall-through.
4. **Validation order.** Literal values validate against §7.2 (`pattern`/`min`/`max`/`options`) up
   front — for `multi` values, per item. Expression values validate as *expressions* up front (is the
   token registered? does it yield a compatible `ParamType`?), then re-validate against the contract
   **after resolution** — so `to: $signal.recipient` is accepted at author time and still enforced as
   an email at fire time.
5. **Enforce in `ParameterResolver`, not only the browser.** The UI is not a gate; a violation joins
   the existing `invalidType()` path and fails `REJECTED` before user code runs.

### 6-A. As built (step 2, 2026-08-07) — the three-way outcome §6.3 implies

The fail-closed gate only works because **"unregistered token" and "declared token with no value here" are
different outcomes**, and today's code conflated both as `null`:

- `ExpressionRegistry` gained the `$`-grammar — `isExpression` (`$`-led and not `$$`), `unescape`,
  `declares` — so the rule is stated once, not at each position an author can type a value.
- `ParameterResolver.value()` now returns a private `Layered(value, unknownExpr)`: an unregistered token
  **stops the ladder** and is reported, where a declared-but-absent `$signal.<field>` still falls through
  (the existing `bindToAnAbsentSignalFieldFallsThroughToConfig` test is the guard on that distinction).
  A `deduce:` typo therefore no longer silently uses the declaration's own `defaultValue`.
- `Resolution` gained `unknownExpression`; `JobService` joins it into the REJECTED reason and the
  `job.run.rejected` payload alongside `missing`/`invalidType`.
- ⚠ The escape is live in **expression positions only** (`bind:`, `deduce:`) because layers 1 and 3 are
  still literal. When step 3 evaluates them, an authored value of `$$x` starts unescaping to `$x` — that
  is the intended §6.2 ordering, and the only value affected is one deliberately written as `$$`.

### 6.1 ⚠ Hazard — `$` namespace collision in `sql.template`

`sql.template` already uses `$name` tokens **as its parameter contract**. ✎ Grounded 2026-08-06: the
scanner turns each distinct `$name` (regex `\$([A-Za-z_]\w*+)(?![(.])`) into a *required STRING*
declaration (`SqlParamScanner.java:24,27-37`, wired at `SqlTemplateJobType.parameters:33-39`); the
values then resolve through the same five layers as any declared parameter (single shared call site,
`JobService.java:771`) and substitute as single-quote-escaped SQL literals
(`SqlParamScanner.substitute:45-56` — its own escaping, not `com.gamma.query.Parameters`). Once
authored values evaluate expressions, `WHERE dt = $event_day` in a SQL body is ambiguous: a template
parameter named `event_day`, or a runtime Expression?

Options:

- **Namespace separation** — Expressions keep `$`, template parameters move to a distinct sigil.
  Cleanest on paper, but a breaking change to authored SQL.
- **Precedence rule** — a registered Expression token wins; anything unregistered is a template
  parameter. No migration, but a newly registered token could silently capture an existing template
  parameter name — exactly the fail-open behaviour §6.3 sets out to remove.
- **Scoped evaluation** — the SQL body is exempt from Expression evaluation; expressions resolve only
  in parameter *values*. **Recommended.** The previously claimed cost ("blocks `$event_day(-7)`
  inside SQL, the very thing §5 wants") is not real — the driving use case is served by indirection:

  ```
  sql:    … WHERE dt BETWEEN $from AND $to
  params: { from: "$event_day(-7)", to: "$event_day" }
  ```

  The scanner makes `from`/`to` declared parameters; step 3 evaluates their *values* at fire time;
  the resolved dates substitute into the SQL. `$` inside SQL keeps exactly one meaning (template
  parameter), zero migration, no capture hazard — and the token picker works where the contract
  lives, on the parameter fields.

**FINAL CALL 2026-08-07 — scoped evaluation, forced by the shipped code, not chosen on taste.** Grounding
the collision before building step 3 refuted the framing above: the choice was never between three live
options, because **the SQL body is itself a declared parameter value** (`ParameterDecl.required("sql", …)`,
`SqlTemplateJobType:17`) fed from layer 3. So "exempt the SQL body" can only mean one thing mechanically —
**evaluate a whole value that IS a token, never a substring**:

- Substring interpolation is **refuted**, not merely dispreferred. Combined with step 2's fail-closed rule
  it would REJECT every existing `sql.template` Job: the body's `$name` tokens are template parameters, not
  registered Expressions. `spaces/demo/config/jobs/orders_summary_sql_job.toon` (`WHERE STATUS = $status`)
  is a live instance that would break on the next Run.
- The exemption is also stated **in the declaration itself** — `sql` now carries `expressions: false` (and
  `TEXT`, retiring the name-sniff for the same field) — so the rule is visible where the contract lives
  rather than implied by evaluator internals.
- The token grammar tightened to `$` + letter/underscore, which makes three things literals rather than
  typos: `$$today` (escape), `${ENV:…}` (this codebase's *other* `$` convention — a secret reference), and
  `$100` (a currency amount needs no escape after all).

⚠ **Cost, and it is a real one:** §9's worked example — typing `Daily report for $yesterday` into a Subject
field — **does not work** and is not scheduled. Interpolation inside a longer string is a separate feature;
if it is ever wanted it must be **per-declaration opt-in**, never blanket, or it re-opens exactly the
capture hazard this decision closes. Tracked in `docs/BACKLOG.md`.

## 7. The declaration contract

### 7.1 `ParamType` — widen, and render what already exists

Today: `STRING, INTEGER, DECIMAL, BOOLEAN, DATE, INSTANT, DATASET_REF` (`ParamType.java:8`). Add
`TEXT` (multiline — retires the `sql` name-sniff) and `EMAIL` (required by the first real consumer,
§9). Nothing else is missing from the *enum* — but three shipped members render as bare text today
(§1.1): the fix for `DATE`/`INSTANT`/`DATASET_REF` is the mapping table (§7.4), not new members.
Choice-valued parameters need no enum member — a non-empty `options` list makes a parameter a choice.

### 7.2 `ParameterDecl` — carry the rendering + validation contract

Today's components: `name, type, required, deduce, defaultValue, description`
(`ParameterDecl.java:13-14`) — so per-field help text **already exists**; existing components keep
their meaning, and the new ones default to today's behaviour so every built-in compiles unchanged:

| Component | Purpose |
|---|---|
| `label` | Human field label; falls back to the humanised `name` |
| `tier` | `REQUIRED` \| `OPTIONAL` \| `ADVANCED` — disclosure, decoupled from `required` |
| `options` | Allowed values ⇒ renders a select, validated as a choice |
| `pattern` | Regex the literal value must fully match |
| `min` / `max` | Numeric bounds |
| `placeholder` | Field hint |
| `group` | Section heading (e.g. `Recipients`, `Message`) — orders the form |
| `multi` | List cardinality — value is a list of `type`; per-item validation |
| `secret` | Mask on input; masked in API reads (see below) |
| `expressions` | Whether `$`-tokens are accepted here (default **true**); `false` forces a literal |

**`secret` masking hooks the route boundary, not the model.** `GET /jobs/{name}` echoes every param
verbatim via `JobConfig.toMap()` (`JobConfig.java:173`) — but `toMap()` also feeds bundle
export/import (`BundleRoutes.java:446-455`), so masking inside it would corrupt exports. Mask where
the API response is assembled (`JobRoutes.jobDetail`, `:145-147`); the list route is already safe
(`JobView` carries no params). House precedent: `ConnectionProfile` masks inline literal secrets as
`***` while showing `${ENV:…}` refs (`ConnectionProfileTest.java:63-68`).

✎ Corrected: a builder here is **not** a deviation from house style — builder-on-record is the
established pattern for many-optional-component records: `EventQuery`, `Event` (inspecto-event),
`ObjectQuery`, `OperationalObject` (inspecto-engine ops), each a record with a `Builder`. Add
`ParameterDecl.of(name, type)….build()` citing that precedent and keep the existing `required(...)`/
`optional(...)` factories as-is. (Today a decl with `deduce` set can only be built through the raw
6-arg constructor — e.g. `ConsignmentProcessJobType.java:83` — the builder retires that wart too.)

#### 7.2-A As built (step 7, 2026-08-07)

- **The 6-arg constructor is the compatibility seam.** Widening the record changes the canonical
  constructor's arity, so a 6-component delegating constructor was added and all 16 raw call sites — plus
  `required(...)`/`optional(...)` — compile and behave unchanged. That is what made "built-ins compile
  untouched" true rather than aspirational.
- **`tier(REQUIRED)` also sets `required`.** Two components that must agree are one fact; letting a
  declaration say `tier(REQUIRED)` without `required()` would have created a state where the form demands a
  value the resolver does not.
- **`min`/`max` stay `null` when unbounded, over the wire too** — `0` is a meaningful bound, so the
  empty-string-for-unset convention the other keys use would have been a bug here.
- **The multi-select constraint is enforced at construction**, not at descriptor registration as §7.4
  suggested: it is the earliest point, needs no registry involvement, and a declaration that cannot render
  should not exist. `ParameterDeclContractTest` pins the message.
- **`EMAIL` is enforced in `matchesType`, not deferred to step 8's pattern work.** It is a *type*, so it
  belongs beside `DATE`/`INSTANT`; the regex is deliberately permissive (`local@domain.tld`, no spaces)
  because only delivery truly validates an address and a strict regex rejects valid ones.

### 7.3 `JobTypeDescriptor` — expose provenance

Today: `id, title, description, parameters, emits, artifacts` (`JobTypeDescriptor.java:13-15`;
`toMap()` at `:29-48`). Add `implClass`, `version`, and `source` (`builtin` \| `classpath` \|
`pack:<id>`). `JobService` already tracks pack ownership in `jobPackOwner` (`JobService.java:123`);
this surfaces it. Answers "what is this job, where did it come from" — currently unanswerable from
the UI. Served by the existing `GET /jobs/types[/{id}]` (`JobRoutes.java:54-57`).

### 7.4 Decl → widget mapping — the generation contract

This is the heart of "field metadata generates the UI": `paramDeclToSpec` becomes this table and
nothing else — deterministic, unit-testable, no guesses:

| Declaration | Renders as |
|---|---|
| `STRING` | `string` input |
| `TEXT` | `multiline` |
| `INTEGER` / `DECIMAL` | `number` + `min`/`max` |
| `BOOLEAN` | `boolean` |
| `DATE` / `INSTANT` | `string` + ISO pattern preset + placeholder (`2026-08-06` / `2026-08-06T00:00:00Z`); a real date-picker widget is a renderer extension, deferred until a consumer demands it |
| `EMAIL` | `string` + email pattern (applied per item under `multi`) |
| `DATASET_REF` | `autocomplete` + `datasetOptionLoader()` — suggestions assist, never constrain; the server stays the gate |
| non-empty `options` | `select` (wins over the type-derived widget) |
| `multi` | `list` chips; `pattern`/type checks apply per item |
| `secret` | masked input (renderer extension) |
| `tier` | 1:1 (`required`/`optional`/`advanced` already exist) |
| `label` / `placeholder` | direct; `help` = `description` + "Deduced as `<deduce>` when unset" (as today) |
| `group` | section headings within a tier (renderer extension — `AttributeSpec` has no grouping today) |
| `expressions: true` | token-picker adornment on the field (§8.5) |

Renderer extensions (`group`, `secret`, per-item list validation) ride step 11. ⚠ Any new
`AttributeType` member must widen backend `FindingsSpec.TYPES` in the same change, or
server-published specs 422 (`attribute-spec.ts:26-29` documents the coupling). v1 constraint:
`options` + `multi` together (a multi-select) has no renderer support and no declared consumer —
rejected at descriptor registration for now, honestly, rather than half-rendered.

### 7.5 As built (steps 8 + 9, 2026-08-07)

- **One validation path, not "pre + post".** The delivery row said "pre + post resolution"; at fire time
  that collapses to a single check on the value the Run will use, which is what makes a literal and an
  Expression result provably subject to the same contract. The §6.4 *pre*-resolution check — does the
  token's `yields` suit the field — is deliberately **not** in the resolver: it could only reject values
  post-resolution validation already judges on the evidence (a `STRING`-yielding `$signal.<field>`
  legitimately carries a date). It earns its keep at *author* time, in the picker and a dry-validate route.
- **`multi` is CSV**, the house convention for list-valued job params (`objects.analytics`'s `types`).
  Every item is validated, and an empty item is a violation rather than a silently dropped blank.
- ⚠ **Provenance cannot live on `JobTypeDescriptor`.** §7.3 proposed adding `implClass`/`version`/`source`
  as components, but a descriptor is authored by the provider and **a provider cannot know its own
  provenance** — the pack owner is the registry's knowledge. So `JobTypeRegistry` records the source at
  registration (`register` / `registerClasspath` / `register(_, owner)`) and `JobService.jobTypeView(id)`
  assembles descriptor + provenance. The descriptor record is unchanged, and no provider had to be touched.
- **`implClass` for a built-in reports the registering class, not the anonymous wrapper.** Every built-in
  goes through `JobTypeProvider.of(...)`, whose anonymous class name is the *same useless string* for all
  nine; the lambda's declaring class (`com.gamma.job.JobService`) is the honest answer. A real provider
  class or a pack's reports itself, which is the case that matters.
- ⚠ **`JobConfig.toMap()` is FLAT** — `params` are flattened in beside `name`/`type`/`cron`, there is no
  `job:` wrapper key. The masking pass was first written against a wrapped shape and would have masked
  **nothing**; its test caught it. Anyone touching this view should check the shape rather than assume.

## 8. UI changes

1. `paramDeclToSpec` becomes the **§7.4 table** — every guess deleted.
2. Job Type picker options are built from `GET /jobs/types` (9 built-ins today vs the 5 hardcoded),
   labelled by `title` — the dialog composes the `type` spec after the fetch (specs are data; no
   renderer change needed). Deployed plugins and Job Packs then appear with no UI change.
3. Selected type renders a **"what this does" panel**: `description`, `emits`, `artifacts`, provenance.
4. **Free key/value editor is removed from the normal path** — it survives only as an explicit escape
   hatch when the descriptor is absent, and that state is *surfaced as a warning*.
5. Every expression-accepting field gets a **token picker** fed by `GET /jobs/expressions`, filtered
   by the field's `ParamType` (`yields`), the Job's trigger kind (`availableIn`), and
   `expressions: true` — with the resolution preview text coming from the catalog (§4.3), never a
   client-side evaluator.
6. `group` renders as section headings; `secret` masks.
7. `on_signal` authoring (§5-A): `scheduleMode` gains 'On signal' (signal id + optional `when:`);
   `JobUpsert` (`app/inspecto/api/jobs.service.ts:16-24`) widens accordingly.

## 9. Worked example — a mail Job

```java
new JobTypeDescriptor("mail.send", "Send Mail",
    "Composes and sends an email to the configured recipients.",
    List.of(
        ParameterDecl.of("to", ParamType.EMAIL).label("To").tier(REQUIRED)
            .multi().group("Recipients").build(),
        ParameterDecl.of("cc", ParamType.EMAIL).label("Cc").tier(OPTIONAL)
            .multi().group("Recipients").build(),
        ParameterDecl.of("subject", ParamType.STRING).label("Subject").tier(REQUIRED)
            .group("Message").build(),
        ParameterDecl.of("body", ParamType.TEXT).label("Body").tier(REQUIRED)
            .group("Message").build()),
    List.of("mail.sent"), List.of());
```

Renders as a draft form: **Recipients** (To — required email chips; Cc — optional) then **Message**
(Subject; Body as a textarea). `to: $signal.recipient` is accepted at author time and email-validated
after resolution. No key/value row anywhere.

⚠ **Corrected 2026-08-07:** this example previously claimed an author could type
`Daily report for $yesterday` into Subject and have it resolve per Run. **That does not work** —
step 4 settled on whole-value evaluation (§6.1), so a `$`-token inside a longer string stays literal.
A per-Run subject must be a whole-value parameter (`subject_date: $yesterday`) composed by the Job, until
opt-in interpolation is designed.

## 10. Delivery

| # | Step | Verify |
|---|---|---|
| 0 | ✅ **Done with this refinement (2026-08-06)** — un-ban recorded: GLOSSARY §6-A entry + §13 row, amendment-plan supersession note; INDEX link already present | docs consistent; `graphify update .` run |
| 1 | ✅ **SHIPPED 2026-08-07** — `ExpressionDecl` / `ExpressionProvider` / `ExpressionContext` / `ExpressionRegistry` + `BuiltinExpressions` carrying the §2 tokens; `ParameterResolver.deduce()`'s switch deleted, the `pipeline`/`flow` shim kept verbatim in `value()`; GLOSSARY §6-A gained **Expression** | `mvn -o -pl inspecto-engine test` → 989 tests, 0 failures (`ExpressionRegistryTest` 5, `ParameterResolverTest` 12); every §2 token resolves exactly as before |
| 2 | ✅ **SHIPPED 2026-08-07** — `$$` literal escape + unknown-token `REJECTED` (`Resolution.unknownExpression`, reported by `JobService`'s reject path and the `job.run.rejected` Signal) | `mvn -o -pl inspecto-engine test` → 994 tests, 0 failures; existing configs unaffected — layers 1/3 still literal (pinned by `authoredConfigValuesAreStillLiteralsAtThisStep`) |
| 3 | ✅ **SHIPPED 2026-08-07** — layers 1 (trigger `args`) + 3 (authored `config`, incl. the `flow` legacy read) evaluate through the registry, honouring `expressions: false` | `$today` typed as a config param resolves at fire time; `ParameterResolverTest` 20/20; engine 1005, `inspecto` 685, 0 failures |
| 4 | ✅ **DECIDED + SHIPPED 2026-08-07 — scoped evaluation, and it is no longer a judgement call** (§6.1) | `sql.template` tests green under the rule: `SqlParamScannerTest` 4/4, `SqlTemplateJobTest` 4/4 |
| 5 | ✅ **SHIPPED 2026-08-07** — ServiceLoader (`JobService.registerBuiltins`) + Job Pack (`JobPackManager.load`/`unload`) expression providers; owner-tagged registration + `deregister(owner)`; collisions fail-closed in all three paths | `JobPackManagerTest` 6/6 — a real compiled pack jar contributes `$tenant.id` and unload takes it back; a pack declaring `$today` is rejected whole. Engine 1001, 0 failures |
| 6 | ✅ **SHIPPED 2026-08-07** — `GET /jobs/expressions` (`JobRoutes`, fixed sub-path before the `/jobs/{name}` regex) + `contextFree` previews via `ExpressionRegistry.catalog(ctx)` / `JobService.expressionCatalog()` | `ControlApiJobExpressionsTest` 4/4 real-HTTP; `mvn -o -pl inspecto test` → 685, 0 failures. Previews equal evaluator output by construction |
| 7 | ✅ **SHIPPED 2026-08-07** — `ParamType.TEXT`/`EMAIL`; all eleven `ParameterDecl` components + `of(name,type)….build()`; `JobTypeDescriptor.toMap()` serves them | Built-ins compiled untouched (a 6-arg delegating constructor keeps all 16 raw call sites); `ParameterDeclContractTest` 5/5; engine 999, `inspecto` 685, 0 failures |
| 8 | ✅ **SHIPPED 2026-08-07** — `ParameterResolver` enforces `options`/`pattern`/`min`/`max` and `multi` per item, on the resolved value | Each violation ⇒ `REJECTED`; an expression's *result* is held to the contract (`anExpressionResultIsHeldToTheSameContract`); `ParameterResolverTest` 23/23, engine 1008 |
| 9 | ✅ **SHIPPED 2026-08-07** — provenance (`implClass`/`source`/`version`) served by `GET /jobs/types[/{id}]` from the registry, not the descriptor; `secret` masking in `JobRoutes.maskSecrets` at the response boundary | `ControlApiJobProvenanceTest` 5/5; `JobConfig.toMap()` untouched ⇒ bundle export unchanged; `inspecto` 690, 0 failures |
| 10 | ✅ **SHIPPED 2026-08-10** (carried in `4c272c90`) — the mirror carries all eleven §7.2 components, declared **optional** because this client also talks to servers predating the contract; `paramDeclToSpec` is now the §7.4 table with one case per row. Three things grounding forced: the `sql` name-sniff is genuinely retired (`SqlTemplateJobType` already declares `TEXT`, so nothing regressed); a `multi` default is **CSV** and must be split into chips or a two-item default renders as one comma-bearing chip; and placeholder precedence is explicit `placeholder` → type format example → `deduce`, because `help` already states the deduce in words and the one hint slot is better spent on the format | `job-parameter-specs.spec.ts`, one case per §7.4 row + a CSV round-trip that is stable across repeated edits |
| 11 | ✅ **SHIPPED 2026-08-10** (`339e91c6`) — `group` headings within a tier (coalesced by name, which also keeps the `@for` track key unique), `secret` as a masked input, and `pattern` applied **per list item**. ⚠ **`FindingsSpec.TYPES` was NOT widened, and must not be**: no `AttributeType` member was added, so nothing server-published can 422 that could not before. `group`/`secret` are spec **fields**, and adding them to `SECTION_KEYS` without adding them to the `Section` record would accept-and-drop them silently | schema-form specs + axe-core green; the per-item rule pinned against the array's `toString()`, which is what `Validators.pattern` was really testing |
| 12 | ✅ **SHIPPED 2026-08-10** (`3c599363`) — options built from `GET /jobs/types` (the hardcoded five vs the registry's ten), degrading to the declared list rather than blanking the picker; the selected type renders description / `emits` / `artifacts` / provenance (`source`, `version`, `implClass`). Mock descriptors gained the provenance keys so the offline panel shows what the server would. ⚠ Fixed a latent bug this would otherwise have triggered: the type-change subscription sat on the `type` **control**, and reassigning `specs` rebuilds every control — a dynamic option list would have silently killed it and switching type would have stopped reloading parameters. It is on the **FormGroup** now, which survives a spec swap (the swap also re-seeds live values) | `npm run test:ci` **2235 passed / 5 skipped, exit 0**; a packaged type absent from the old list is selectable and self-describing (`builds the type options from GET /jobs/types, not the hardcoded list`) |
| 13 | ✅ **SHIPPED 2026-08-10** — `JobsService.expressions()` + the mock's `/jobs/expressions` (all fifteen tokens, previews evaluated per request as the server does); a shared `<inspecto-token-picker>` carried `matSuffix` on every whole-value widget, driven by the schema form's new `[tokens]` (per-key offers) and `[tokenSyntax]`; the three §8.5 filters live in `tokensForParam`. **Four things grounding forced.** (a) **`expressions` rides the DESCRIPTOR, not the `AttributeSpec`** — the flag is a Jobs policy, `AttributeSpec`'s unions are server-published for Findings, and giving it a spec home would drag `FindingsSpec` along exactly as step 11 warned; the dialog already holds the decls, so it filters there. (b) **A token must be EXEMPT from the field's `pattern`, or the picker is decorative** — every field it matters on (date, instant, email) carries a preset pattern, so the form marked invalid the value it had just authored and Save refused it; `tokenSyntax` (`^\$(?!\$)`, mirroring `ExpressionRegistry.isExpression`) is the exemption, and unset it changes nothing for existing adopters. (c) **On a `multi` field the token replaces EVERY entry**: `ParameterResolver.authored()` evaluates the whole raw value and the CSV split is post-resolution *validation* only, so a token beside other chips is part of a longer literal and never resolves — the renderer therefore also keeps holding a 2+-entry list to the literal format, deliberately. (d) **Number widgets get no picker at all** — a `type="number"` input cannot display `$now.epoch_seconds`, so it would read as blank while the control held the token; the INTEGER tokens are consequently unreachable from the UI (recorded in BACKLOG §4, not fixed here) | `test:ci` **2263 passed / 5 skipped, exit 0** (318 files); `lint:tokens` + `tsc --noEmit` (app *and* spec configs) clean. Preview (offline mock): the picker sits in the suffix slot, `$today` lands as the whole value and clears the required error, `sql`'s `expressions:false` withholds it while its sibling `sink_dataset` keeps it, an EMAIL field on a cron trigger offers only the three STRING-yielding tokens, and two committed chips collapse to one `$run.actor` with **no** format error — saved through to the store as `to: "$run.actor"`. ⚠ **A preview check caught what a green unit test could not**: `matSuffix` on markup inside an `<ng-template>` instantiated by `*ngTemplateOutlet` never projects (Material resolves projection statically at the declaration site), so the button rendered in `…-infix` beside the text. Asserting the button *exists* passes either way — hence the extracted component and a spec that asserts the **slot** |
| 14 | ✅ **SHIPPED 2026-08-10** (`3c599363`) — the editor renders only when the type publishes **no** descriptor (with a warning naming the type, because silently offering untyped fields reads as if that were the intended path) **or** when the job already carries untyped params — that second condition is not in §8.4 and is required: hiding it on a legacy job would strand those values invisibly while `save()` still wrote them | axe-core green (a11y specs pass); dirty-guard untouched; all three states pinned, incl. `stays visible for a job that already carries untyped params` |
| 15 | ✅ **SHIPPED 2026-08-10** (`2a891dad`) — `MailSendJobType`/`MailSendJob` + a new `mail` Platform Service (`MailAccess`). The descriptor is §9 verbatim and `MailSendJobTypeTest` pins it field by field **including through `toMap()`**, since a component that never reaches the wire cannot render. Delivery reuses the `NotificationChannel` seam (`deliver(n, target)` already addresses an explicit destination and `SmtpEmailChannel` honours it), so no second mail client and SMTP stays in one place. Three corrections grounding forced: the descriptor declares **`requires: [mail]`** which §9's sketch omitted (a Job that mails outward must declare that reach); a **bare registry still ACCEPTS it** — `register(provider)` is the built-in path and S1-7 tolerates an unsatisfiable `requires` on purpose, so the test asserts that rule rather than the refusal I first assumed; and the channel seam has **no CC concept**, so `cc` is appended to the one recipient list (deferred, BACKLOG §4). `mail` is deliberately NOT `notifications`: that one lets the user's preferences pick the recipient, this one uses the addresses the Job's author named | Full reactor `mvn -o clean test` → 23 modules, **2625 tests, 0 failures**; `MailSendJobTest` 6, `MailSendJobTypeTest` 9. Recipients are logged/signalled by **count**, never address — pinned by `itEmitsTheDeclaredSignalWithARecipientCountAndNoAddresses` |
| 16 | ✅ **FULLY SHIPPED 2026-08-10** — UI half in `4c272c90`; the backend half (`JobService.JobView.onSignal` + the javadoc's Signal bullet + `ControlApiJobCrudTest`) landed in `ec5f05f6` once step 15 compiled and the whole reactor was green (2625 tests). Without the `JobView` component the UI reads `onSignal` from `GET /jobs`, the server never sends it, and a signal Job renders as "manual" against a real backend while looking right offline — the same mock-is-nicer-than-the-server shape that hid the write-body bug. Original slice: `scheduleMode` gained 'On signal' (signal id + optional `when:` guard), `JobUpsert` widened, and `JobView` gained `onSignal` so a signal job stops reading as manual in the list. **Grounding refuted the step's own framing**: "widen `JobUpsert`" was not enough, because the body the endpoints accept is the flat snake_case `job:` section and the UI had been sending camelCase with nested `params` since the endpoint landed — `fromMap` absorbs unknown keys as parameters rather than 422ing, so **every** UI-authored event trigger and every declared parameter was already being silently dropped. Adding `onSignal` in the same spelling would have produced a fourth dead field. Fixed at the seam (`jobToWire`/`jobFromWire`), with the mock re-mirrored on `fromMap` independently | `ControlApiJobCrudTest` 3/3 real-HTTP (snake_case triggers + flat params round-trip; a signal job end-to-end incl. `prefix.*`; a camelCase key proven inert). UI: `jobs.service.spec.ts` 8, `jobs.handler.spec.ts` +3 mirroring the Java cases, dialog + display specs, axe green. `test:ci` **2229 passed, exit 0**; build + `lint:tokens` green. Preview: authored a signal Job end-to-end and read the store back — `on_signal`/`when` landed, `config` stayed a param, the row reads "on signal dataset.write" |
| 17 | ✅ **SHIPPED 2026-08-10** — §5-B built as decided: `time_range` and `RunArtifact.timeRange` removed; `DbConsignmentOutputStore.bounds(table)` folds min/max over the `:229` predicate; `ConsignmentOutputStores.bounds` is the fail-open static read; `ExpressionContext` gained a `bounds` lookup (plus a 7-arg delegating constructor, so the 10 pre-existing call sites were untouched); `PipelineJobRunner` overrides `run(JobContext)` and records one artifact per store it wrote, `ref` = the store. Two things grounding forced: the attrs yield **`STRING`, not `INSTANT`** (§5-B correction), and `RunArtifactStore`'s mapper now ignores unknown properties — Jackson's default would have failed every artifact file written while `timeRange` existed | Full reactor `mvn -o clean test`: **23 modules SUCCESS, 0 failures** (engine 1119 → 1122). `eventTimeBoundsResolveLiveSoARecomputeMovesThem` proves the value follows the registry, which a stored field could not; `boundsExcludeSupersededButKeepCompactedAway`; `eachEndSubstitutesIntoSqlAsATimestampLiteral` casts both ends in real DuckDB; `recordsOneRunArtifactPerStoreItWroteRefdByTheStoreName` pins per-store rows across two sinks |

Steps 1–9 are backend-only and ship independently of 10–15 (the UI tolerates unknown fields).
Step 16 depends only on step 3 (for `$signal.*` in params) and is otherwise independent.

Two follow-ons step 16 deliberately did **not** build, both of which want their own decision:
**(a) a signal-type catalog** — `GET /signals` is the ledger of *observed* signals, not a catalog of
declarable types, and there is no `SignalsService` in the UI at all, so `on_signal` is free text with
a placeholder rather than an autocomplete. A job must stay armable for a signal that has never fired
(`dataset.write` before amendment S3a publishes it), so any catalog must suggest, never constrain.
**(b) author-time `when:` validation** — `WhenGuard` is a package-private *runtime* evaluator in
`com.gamma.job` with no authoring-time validator, so `com.gamma.control` cannot reach it and there is
no published grammar to pin a client-side check against. A bad guard is currently caught fail-closed at
fire time. Hand-rolling a second grammar in TypeScript is the failure mode to avoid. Ordering
constraints: step 1 precedes everything (it is the seam); step 2 precedes step 3 (evaluating authored
values without an escape breaks existing configs); step 4 precedes step 3 *shipping to users*.
Step 17 is backend-only and independent of 10–16 — its only precondition (step 1's registry) shipped
2026-08-07; it is a `feat:` on `master` (the consignment registry it reads does not exist on `4.x`).

## 11. Non-goals

- **Event Day** — deferred to its own plan (§5), including `$event_day(n)`.
- **Publishing `dataset.write`** — the trigger-side Signal for dataset-driven Jobs is amendment
  slice S3a, its own thread (§5-A).
- No cron **picker widget** — presets exist; a visual builder is separate scope.
- No `dependsOn` in `ParameterDecl` yet. The renderer supports it, but no declared consumer needs
  conditional parameters; add it when one does.
- No dry-validate route (`POST /jobs/validate`). Still a real gap — tracked in `docs/BACKLOG.md`.
- **No consolidation of the three `$`-evaluators.** `ParameterResolver`,
  `com.gamma.query.Parameters` (SQL-literal output; its vocabulary is even richer — `$current_user`,
  `$role`, caller-declared `Def`s, `Parameters.java:68-98`) and `WhenGuard` (`$signal.*` only) each
  have their own token set; `ParameterResolver`'s class doc (`:39-42`) already names unifying them as
  deliberate future work. The §4 registry is the natural home for that consolidation later — worth a
  BACKLOG entry, out of scope here.
