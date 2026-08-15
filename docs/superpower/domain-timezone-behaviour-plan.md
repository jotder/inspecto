# Plan — the `meta.domain.timezone` behaviour half

**Status:** design only, **nothing built**. Written 2026-08-15 by grounding the BACKLOG §4 *Job framework*
row (D6, reclassified 2026-08-04). **⚠ The row's framing is refuted below — read §2 before §5.**

The validation half shipped 2026-08-14 (`ConfigSpecs.meta()`'s `domain-timezone-resolvable` ERROR rule).
This plan covers only the remaining half: **making a configured zone govern cron firing and the
`$today`/`$yesterday`/`$day(-1)` date macros.**

---

## 1. What is actually true today (grounded, not assumed)

| Claim | Verdict | Evidence |
|---|---|---|
| Two consumers hardcode `ZoneId.systemDefault()` | **TRUE** | `PipelineScheduler:117` (read at `:373`, `:375` — the cron "is it due" comparison) · `JobService:246` (read at `:471` `scheduler.cron`, `:541`/`:1107` `ZonedDateTime.now(zone)`, `:546` next-fire, `:930`/`:1206` `ExpressionContext`) |
| `meta.domain.timezone` is parsed and served | **TRUE** | `SemanticModel:74` (`DomainNotes`), `:133` (parsed from the `domain:` block), `:93` (one model per `*_meta.toon`) → `MetadataGraphService:148-160` → `/catalog/kpis`, `ExplainEntitySkill:104` |
| A **per-pipeline** timezone is "pinned (§5.6)" | **FALSE — plan reference only** | `EventTimeBounds:11` and `ConsignmentOutputs:257` both assert it, but `PipelineConfig` and `ConfigSpecs.pipeline()` have **zero** zone/timezone keys. Nothing pins anything. |
| A **per-job** zone key exists | **FALSE** | `JobConfig` has zero zone references; `JobService.zone` is one field for the whole service |

**🔴 The finding the row does not mention.** `meta.domain.timezone` is **not a single value**. A space may
hold any number of `*_meta.toon` files (`ServiceBootstrap:60` scans by suffix, no cardinality limit), and
`MetadataGraphService.domain()` merges them **last-non-blank-wins in iteration order**
(`MetadataGraphService:155`). So with two meta files naming different zones, the winner is decided by
**directory scan order**. Today that is unobservable — the repo's two spaces hold exactly one meta file
each, both `timezone: "UTC"` — but promoting this value to a control input would make cron firing depend
on file iteration order. ⛔ That alone disqualifies "just read `domain.timezone` in the scheduler".

---

## 2. The refutation: the row conflates two different zones

The row treats `domain.timezone` as *the* zone and asks only whether to wire it up. Grounding says these
are two distinct concepts that happen to share a word:

- **Data zone** — *what wall-clock zone the DATA's timestamps are in.* This is genuinely a **domain**
  note, and it is exactly what `domain.timezone` is documented as (`ConfigSpecs:555`, "cross-cutting
  domain context"). Its real future consumer is the consignment event-time cut — `record_day` "cut with
  the pipeline's pinned timezone" (§5.6/§10.1), which is **per-pipeline data semantics**, not scheduling.
- **Operations zone** — *what wall-clock zone the OPERATOR's schedule is expressed in.* "Run at 06:00"
  means 06:00 where the operator sits. This is what `PipelineScheduler.triggerZone` and `JobService.zone`
  actually need.

They are routinely different: data landed in UTC, operator in `Asia/Kolkata` wanting a 06:00 local run.
Wiring `domain.timezone` into the scheduler forces one value to answer both questions, and silently makes
a **catalog annotation** load-bearing for execution — the same category error as the registry `schema`
kind in unification W1 (a ref that loads and does nothing) run in reverse.

**⚠ Note what this dissolves.** The row is gated on "a behaviour change on existing schedules … needs
operator sign-off and a migration story". That blocker is an artefact of the wrong source: an **operations
zone that is unset by default and falls back to `systemDefault()` shifts nothing**, because every existing
deployment leaves it unset. The migration story becomes "there is no migration".

---

## 3. Proposed shape (the recommendation)

**Split the concept. Leave `domain.timezone` descriptive; give scheduling its own explicit key.**

1. **`domain.timezone` is untouched** and stays a catalog annotation. Its `domain-timezone-resolvable`
   validation stays (a descriptive value should still be a real zone). ⛔ **No timezone picker** — the
   row's existing ban survives intact and for the same reason.
2. **One new operations key**, resolved once at service construction, defaulting to `systemDefault()`.
   Both `PipelineScheduler.triggerZone` and `JobService.zone` read it instead of calling
   `ZoneId.systemDefault()` directly. Unset ⇒ byte-identical behaviour to today.
3. **The date macros follow the operations zone**, i.e. the same field feeds `ExpressionContext`
   (`JobService:930`, `:1206`). Rationale: a job that fires at 00:30 ops-local and then resolves `$today`
   in a *different* zone is an off-by-one-day bug generator. Firing and "today" must agree. (See Q3 — this
   is the one part I am least certain about.)
4. **Determinism, not last-wins.** Whatever the key's scope, its value must not depend on scan order. If
   it is ever sourced from multiple files, **two differing non-blank values are a config ERROR**, not a
   silent merge.

### Verifiable success criteria

1. Unset key → `PipelineScheduler` and `JobService` fire exactly as today → verify: existing scheduler /
   job tests pass unchanged, and a test asserts the resolved zone **is** `systemDefault()` when unset.
2. Key set to a zone N hours off local → a cron due-check crosses at the configured zone's wall clock →
   verify: a test drives `:373-375`'s comparison across a day boundary in a fixed non-local zone.
3. Same key set → `$today` resolves to the configured zone's date, not the JVM's → verify: an
   `ExpressionContext` test at an instant where the two zones disagree on the date.
4. ⚠ Falsify each of the three: flip the configured zone and confirm the test fails. A zone test that
   passes on a machine whose `systemDefault()` already equals the fixture zone proves nothing — pin the
   fixture to a zone that can never be this box's default, or assert on the delta.

---

## 4. Blast radius, measured

- On disk today: **2 meta files, both `timezone: "UTC"`** (`spaces/default/config/events/events_meta.toon:27`,
  `spaces/demo/config/orders/orders_meta.toon:27`). Under the row's original proposal on a non-UTC host,
  **both sample spaces' every cron and every `$today` would shift on the next boot**. Under §3's proposal,
  nothing shifts until someone sets the new key.
- Read sites to change: **2 assignments** (`PipelineScheduler:117`, `JobService:246`). Every downstream
  read already goes through those fields — no call-site sweep.
- ⚠ **Out of scope, but adjacent:** five other `ZoneId.systemDefault()` call sites exist
  (`AlertService:374`, `ReferenceCompactor:142`, `EventRoutes:108`, `PackTestHarness:164`,
  `InspectoTools:385`). The row names only two. Each needs its own judgement — some are display, some are
  data cuts — and lumping them in is how a 2-line change becomes a sweep. **Decide them separately.**

---

## 5. Open questions — the operator's call

**Q1 — Source of truth.** (a) **Split: a new operations key, `domain.timezone` stays descriptive**
*(recommended, §2)* · (b) make `domain.timezone` itself behavioural, plus a determinism fix for the
multi-file merge · (c) do nothing, close the row as won't-fix and delete the claim from the docs.

**Q2 — Scope of the operations key, if Q1 = (a).** (a) **service/JVM-wide** *(recommended — smallest
honest thing; matches that both consumers are already single-field)* · (b) per-space · (c) per-job /
per-pipeline override on top of a default *(the most capable, and the only one that serves the
consignment §5.6 debt at the same time — but the largest build)*.

**Q3 — Do the date macros follow the ops zone?** (a) **yes, one zone for firing and `$today`**
*(recommended, §3.3)* · (b) no — firing follows ops, `$today` follows the data zone *(defensible, and
strictly more correct for a job whose parameters address data partitions; costs a second resolution path
and a way to say which data zone)*.

⚠ Q3 is the one where my recommendation is weakest: (b) is more principled, (a) is more predictable. If
job parameters are mostly addressing partitioned data by date, (b) is the better answer.

---

## 6. Related

- BACKLOG §4 *Job framework* (the D6 row) — update it in place with §1-§2's refutation when this is decided.
- `okf/backend/engine/consignment-addressing.md` — the §5.6 "pinned per-pipeline" claim its docs make is
  **not built**; whoever takes Q2(c) closes both.
- Durable rule this reinforces: **a backlog row's stated cause is a hypothesis** — here the row's *source*
  was the unexamined premise, not its *fix*.
