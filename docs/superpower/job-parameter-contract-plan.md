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
