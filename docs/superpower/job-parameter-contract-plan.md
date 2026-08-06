# Job Parameter Contract & Runtime Expressions — plan

**Status:** proposed · **Owner:** shift · **Created:** 2026-08-06 · **Refined:** 2026-08-06
(grounded pass, UI + backend — every §1 evidence row verified against source; corrections marked ✎)

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

| Layer | Source | Evaluated? |
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
| `$upstream(<job>).artifact(<name>).<attr>` | function | `ref` \| `rows` \| `bytes` \| `watermark` \| `time_range` |

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

### 4.3 Catalog

`GET /jobs/expressions` serves the registry — **generated from it, never a parallel hand-maintained
list.** Each `contextFree` entry also carries a **`preview`, evaluated by the registry itself at
request time** ("`$yesterday` → 2026-08-05"); context-bound tokens return their declared `example`
instead. The UI never re-implements evaluation — step 13's "preview matches backend" is then true by
construction. This is what makes the token picker (§8.5) possible and keeps it correct as packs load.

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

Final call recorded at step 4, jointly with the Event Day plan, since they share the use case.

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
(Subject; Body as a textarea). An author may type `Daily report for $yesterday` into Subject and have
it resolve per Run; `to: $signal.recipient` is accepted at author time and email-validated after
resolution. No key/value row anywhere.

## 10. Delivery

| # | Step | Verify |
|---|---|---|
| 0 | ✅ **Done with this refinement (2026-08-06)** — un-ban recorded: GLOSSARY §6-A entry + §13 row, amendment-plan supersession note; INDEX link already present | docs consistent; `graphify update .` run |
| 1 | `ExpressionDecl` / `ExpressionProvider` / `ExpressionRegistry`; port the §2 tokens + the `pipeline`/`flow` shim (§1.2 note) to a built-in provider; GLOSSARY gains **Expression** | `mvn -o test` green — every §2 token resolves exactly as before |
| 2 | `$$` literal escape + unknown-token `REJECTED` | Unit tests; existing configs unaffected |
| 3 | Evaluate expressions in layers 1 + 3 through the registry | A `$today` typed as a config param resolves at fire time |
| 4 | Resolve the §6.1 `sql.template` collision (recommended: scoped evaluation) | Decision recorded; `sql.template` tests green under the chosen rule |
| 5 | ServiceLoader + Job Pack expression providers; **collisions fail-closed in all three load paths** (§4.2) | A pack contributes a token; a colliding provider load fails loudly |
| 6 | `GET /jobs/expressions` catalog + `contextFree` previews | Real-HTTP test; previews equal evaluator output by construction |
| 7 | `ParamType.TEXT`/`EMAIL`; `ParameterDecl` components + builder (precedent: `EventQuery` et al.) | Built-ins compile untouched |
| 8 | `ParameterResolver` enforces pattern/min/max/options/multi (per item), pre + post resolution | Each violation ⇒ `REJECTED`; expression re-validated after resolution |
| 9 | `JobTypeDescriptor` provenance + `toMap()`; `secret` masking at the `GET /jobs/{name}` boundary (never in `JobConfig.toMap()` — §7.2) | `GET /jobs/types/{id}` returns provenance; detail route masks; bundle export unchanged |
| 10 | Widen the `JobParameterDecl` mirror (`app/inspecto/api/jobs.service.ts:27-37`); rewrite `paramDeclToSpec` per §7.4 (incl. DATE/INSTANT presets, DATASET_REF autocomplete) | UI unit tests: one case per §7.4 row |
| 11 | Renderer extensions: `group` headings, `secret` input, per-item `list` validation (+ `FindingsSpec.TYPES` widened in the same change) | schema-form specs; axe-core green |
| 12 | Type picker from catalog; description + provenance panel | A type absent from the old hardcoded list is selectable and self-describing |
| 13 | Token picker + resolution preview | Preview text is the catalog's own (§4.3) |
| 14 | Free key/value demoted to the explicit descriptor-missing fallback + warning | axe-core + a11y gate green; dirty-guard holds |
| 15 | `mail.send` as the reference plugin exercising the whole contract | Renders as §9 without UI changes |
| 16 | `on_signal` trigger authoring in UI + `JobUpsert` widening; refresh the stale `JobService` class javadoc (omits `on_signal` — `JobService.java:49-55`) | A signal-triggered Job authorable end-to-end |

Steps 1–9 are backend-only and ship independently of 10–15 (the UI tolerates unknown fields).
Step 16 depends only on step 3 (for `$signal.*` in params) and is otherwise independent. Ordering
constraints: step 1 precedes everything (it is the seam); step 2 precedes step 3 (evaluating authored
values without an escape breaks existing configs); step 4 precedes step 3 *shipping to users*.

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
