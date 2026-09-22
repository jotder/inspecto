<!-- ARCHIVED 2026-09-22 — consolidated into docs/superpower/link-analysis-backlog-plan.md. Provenance only; never read for current state. -->

<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-09-22. Companion to link-analysis-spec.md (target state) and
  link-analysis-advancement-plan.md (Sprints 9-11 engineering roadmap).
  Retire per the three-tier lifecycle in CLAUDE.md when the work ships.
-->

# Link Analysis — Enquiry Model Plan

**The investigation becomes an object with a goal, not a query with filters.**

| | |
|---|---|
| Status | ACTIVE PLAN — nothing built |
| Raised by | Operator, 2026-09-22, from a criminal-fraud call-records scenario |
| Depends on | `link-analysis-spec.md` §7 open decisions **2** (where traversal runs) and **4** (entity identity) |
| Relates to | `link-analysis-advancement-plan.md` S1.2, S1.3, S2.1, S2.2, S3.2 |
| Edition | Professional / Enterprise only — `CP-09` / `EDG-01`, module `inspecto-geo-link` |

---

## 0. How to read this

This plan does **not** restate the Link Analysis target state; `link-analysis-spec.md` owns that,
clause by clause, with SHIPPED / PARTIAL / NOT BUILT tags grounded in a code read. This plan adds one
thing the spec does not model at all: **the investigation as a first-class, persisted, ordered
object**, and the capabilities that only become expressible once it exists.

Everything here is NOT BUILT unless it cites a shipped seam.

⚠ **`Enquiry` is a placeholder name awaiting operator ratification.** `Investigation` is unavailable:
`GLOSSARY-CASE-1` reserves it for the Assistant's RCA-playbook run, and `Case` is taken by
`ObjectType.CASE` (a group of Incidents). Coining a third sense would violate the one-word/one-concept
lock in `docs/GLOSSARY.md`. See §12 decision E1.

---

## 1. Why — the workflow today's model cannot express

The motivating scenario, stated as the operator posed it:

> Start from one subscriber or a few. Expand to 2nd degree. Remove the marketing and
> non-suspect numbers. Expand the survivors to 3rd degree. At each level hide some kinds of
> connection. Choose a whole time range, or a recurring slot each day (22:00 → 04:00). Filter
> on minimum and maximum number of connections in the selected range.

Mapped against what ships:

| Step | Today | Evidence |
|---|---|---|
| Seed from chosen identifiers | 🔴 no entry point — a projection loads a whole Dataset | `InvRoutes.java:170-241` |
| Expand to 2nd degree | 🟡 one entity, one hop, client-merged | `POST /inv/projection/neighbors`, `entity-projection.ts:221-236` |
| Remove numbers, keep going | 🔴 no exclusion set; no delete-from-canvas | grep: no degree or threshold control in the feature |
| 3rd degree | 🔴 no recursive traversal endpoint | spec §3.1 NOT BUILT |
| Hide connection kinds per level | 🟡 global display state only | node-kind checkboxes, per-kind edge styling |
| Time range / intraday slot | 🔴 one-sided client cutoff only | `link-analysis.component.ts:409-427` |
| Min/max connections in range | 🔴 no degree computation exposed | `graph-analysis.ts` is client-side, post-hoc |

**The common cause is not seven missing features. It is one missing object.** Every step above is an
*operation applied to a prior result*. A projection is a pure function of a query, so it cannot
represent "what the analyst did next". Undo/redo confirms the shape of the gap: it covers
presentation state only, never the graph.

🔴 **Consequence already recorded in the spec:** projections re-run on every call, so *a saved view is
not evidence* — reopen it against a grown Dataset and the graph silently differs.

---

## 2. The model

### 2.1 Three objects

| Object | What it is | Mutability |
|---|---|---|
| **Enquiry** | The goal, the ordered operation log, and the bindings (Dataset + pinned version, projection mapping, scope). The *program*. | Append-only log; header fields versioned |
| **Working Set** | The materialized Entities and Links produced by evaluating the log to a given position. The *state*. | Derived; may be cached or discarded |
| **Artifact** | An immutable thing produced *at* a log position — a snapshot, an exhibit, an analyst note, an exported dossier. | Immutable, anchored to `opSeq` |

An Enquiry belongs to a Case (`ObjectType.CASE`), which already exists and already owns evidence
attachment. The Enquiry is the graph-side working object inside the matter; the Case remains the
matter.

**Why Artifacts anchor to a log position rather than to the Enquiry:** an exhibit must be traceable to
the exact state that produced it. If an artifact floated free of the log, appending op #8 would
silently change what exhibit #3 "shows".

### 2.2 The operation vocabulary — closed, not open

⛔ **Do not build a general query language.** The op list is a **closed, small, reviewable
vocabulary** that compiles to SQL. This is the single most important constraint in this plan; §2.7
explains why.

| Op | Kind | Parameters |
|---|---|---|
| `seed` | extensional | entity ids, entity type |
| `seedBy` | intensional | predicate over the Dataset |
| `expand` | intensional | one hop-ladder rung (§3) |
| `exclude` | extensional | entity ids + reason code |
| `excludeBy` | intensional | predicate, or a named reference list |
| `keep` | extensional | entity ids — protected from later filters |
| `threshold` | intensional | measure, min, max, evaluation scope |
| `window` | intensional | time range and/or recurring intraday slot (§4) |
| `hide` | intensional | display-only; never affects traversal or measures |
| `annotate` | extensional | entity/link id, note, confidence |
| `snapshot` | — | freezes the Working Set as an Artifact |

Eleven ops. If a twelfth is proposed, it needs a decision entry — vocabulary creep here becomes an
unreviewable query language by increments.

### 2.3 An ordered program, not a bag of filters

Filters accumulate commutatively. Investigations do not.

```
A:  exclude(marketing) → expand(hop 3)
B:  expand(hop 3)      → exclude(marketing)
```

**A and B produce different graphs.** In A the marketing shortcode is removed from the traversal
graph, so 3rd-degree entities reachable *only through it* never enter. In B they are already in, and
removing the shortcode leaves them behind as orphans — entities that exist in the chart for no reason
the analyst would endorse.

🔴 **A is what the analyst means. B is what a filter-bag model produces, and it looks entirely
plausible on screen.** This is the most likely way this feature produces a wrong chart in a criminal
matter, and no amount of UI polish surfaces it.

Therefore: the log is **ordered and non-commutative**, and re-ordering it is an explicit edit that
invalidates downstream Artifacts.

### 2.4 Intensional and extensional operations are both first-class

- **Intensional** — a predicate over data: `degree < 3`, `AMOUNT >= 5000`. Replays correctly against
  new data. Compiles to SQL.
- **Extensional** — an explicit set the analyst asserted: *these 37 numbers, judged to be marketing*.
  Has no expression as a condition over columns. Carries a reason code and an author.

A model that admits only predicates cannot record analyst judgement. A model that admits only
explicit sets cannot replay against refreshed data. **Both are required**, and the log must keep them
distinguishable — an exhibit has to be able to state which entities were removed by a stated rule and
which by a human decision, because the two are challenged differently.

### 2.5 Three scopes of removal

`hide` ≠ `exclude`. Conflating them is a correctness bug, not a UX wrinkle.

| Op | Display | Traversed through | Counted in measures |
|---|---|---|---|
| `hide` | gone | **yes** | **yes** |
| `exclude` | gone | no | no |
| `keep` | pinned | yes | yes |

Excluded entities remain **inspectable** ("37 excluded — show"), so an analyst cannot lose track of
what they removed, and a reviewer can audit it.

### 2.6 Two evaluators, one spec

Re-running a 3-hop expansion on every click is too slow to interact with. Exact full re-run is
mandatory for evidence. Both are needed, so build both — over one spec:

- **Incremental** — apply the new op as a delta against the materialized Working Set. Drives the UI.
- **Full replay** — evaluate the log from `seed` against the pinned Dataset version. Drives
  verification, evidence, and re-running the Enquiry against a new time range.

Plus a **equivalence check** that full replay reproduces the incremental state, run before any
snapshot is sealed.

✅ The product already has this two-evaluator shape and a name for it: the two-stage filter loop
(Stage 1 local in `graph-filter.ts`, Stage 2 pushed to the server). ⚠ It also already has the trap —
today Stage 2 round-trips with **no effect**, because `InvRoutes.project()` never reads the `filter`
key, and the UI honestly says so rather than pretending. Generalising the loop without closing that
gap would make divergence invisible.

### 2.7 Why the op vocabulary stays closed

The operator's instinct — a JSON spec describing the scenario, compiled to SQL by an engine near the
data — is right, and is the only design that clears the scale ceilings in §8. The failure mode is
letting "complex query parameters describing scenarios" drift into an open expression language:

1. **Security.** The shipped backend is safe precisely because it is narrow: column identifiers must
   match `SAFE_IDENT` and values bind as JDBC `?` (`InvRoutes.java:61,204-210`), so no free-text SQL
   reaches the query. An open language re-opens that surface.
2. **Auditability.** A closed vocabulary can be rendered as a numbered list of plain-language steps
   for a court. An expression tree cannot.
3. **It becomes SQL with extra steps.** Every open query language converges on SQL, badly.

The engine may be sophisticated. The vocabulary it accepts must not be.

---

## 3. The hop ladder

A single "degree" number is insufficient — the scenario wants different rules per level. Each rung is
one `expand` op, so the ladder is replayable and auditable by construction.

| Field | Meaning |
|---|---|
| `direction` | `out` · `in` · `either` · `reciprocal` |
| `linkKinds` | which Link kinds may be **traversed** at this rung (distinct from display) |
| `window` | inherit the Enquiry window, or override for this rung |
| `minEvents`, `minDistinctDays` | Link strength required to traverse |
| `candidateDegreeMin/Max` | reject hubs and singletons — evaluated **within the window** |
| `maxFanOut` | per-entity cap, strongest Links first |
| `budget` | per-rung total cap; on breach set `truncated` and say so |

Worked example for the motivating scenario:

| Hop | Direction | Link kinds | Window | Min events | Min days | Cand. degree | Fan-out | Budget |
|---|---|---|---|---|---|---|---|---|
| 1 | either | all | full | 1 | 1 | — | 200 | 500 |
| 2 | either | voice, sms | 22:00–04:00 | 3 | 2 | 2–150 | 50 | 2 000 |
| 3 | out | transfer | 22:00–04:00 | 5 | 3 | 2–40 | 20 | 5 000 |

🔴 **Truncation must reach the analyst.** `truncated` ships server-side, but the spec records that
whether the SPA surfaces it is **unverified**. In investigative use an unsurfaced truncation is a
false negative presented as a finding.

---

## 4. Time

The weakest area today, and central to the scenario. Required, in order:

1. **Two-handle absolute range**, server-side — a query parameter, not a client cutoff.
2. **Recurring intraday window crossing midnight** (22:00 → 04:00), plus day-of-week masks and
   calendar exclusions.
   🔴 **Needs an explicit timezone contract.** Standing trap in this codebase: *DuckDB's session
   TimeZone is the host, not UTC*. Call records add network time vs local time vs roaming time on
   top. Get this wrong and the night window silently shifts — with no visible symptom.
3. **Thresholds evaluated inside the window.** "Min 3 contacts" must mean 3 contacts *in the slot*,
   computed on the filtered Link set — never the Dataset total. Silently wrong otherwise.
4. **Comparison mode** — two windows, diffed: new Links, dropped Links, changed weight.
5. **Time-respecting paths** — A→B at t₁, B→C at t₂ > t₁, optional max gap.
   🔴 The shipped `layering-chain` and `pass-through` pattern packs have **no temporal ordering
   constraint**, so they can match sequences that ran backwards in time. For a money-laundering
   finding that is a false positive wearing a confident label.
6. **Timeline scrubber with playback**; burst and periodicity detection (regular intervals imply
   automation, not a relationship).

---

## 5. Entity identity

Blocked on spec §7 decision 4. Today an Entity *is* a raw column value, `kind: 'entity'` hardcoded
(`entity-projection.ts:103`); `entityType` only namespaces the id (`entity:<type>:<value>`) so
same-valued entities from different mappings do not merge — it is not queryable or enforced. Domain
profiles are cosmetic labels only.

Without a real entity model this plan's `exclude` lists cannot persist across Enquiries, enrichment
has nowhere to live, and — as the spec already warns — centrality results are only as meaningful as
the raw column values, **with nothing in the product warning the analyst**.

Required: typed Entities (subscriber, IMSI, IMEI, wallet account, agent/till, handset, cell);
resolution across identifiers (shared device, shared KYC, sequential SIM issuance); enrichment as
filterable attributes; and asserted fact kept structurally distinct from analyst inference.

---

## 6. Measures for the money verticals

`maxFlow`, `circular-flow`, `layering-chain`, `pass-through` and the suspicion score ship already.
What is missing is value semantics: value-weighted Links (amount, currency, fee, channel, agent);
pass-through ratio and retention; structuring detection across sub-threshold transfers; velocity and
time-to-cash-out; cash-out concentration by agent; benefit-transfer patterns (many beneficiaries on
one device, agent or cell; duplicate KYC; dormant-then-active).

Express these as named **Decision Rules** with visible thresholds, never an opaque score — an analyst
must be able to state why the tool flagged an account.

🔴 The demo already proves the failure mode: filtering `mule_large_transfers` to ≥ 5 000 makes the
smurf deposits vanish, *which is exactly what structuring is designed to do*.

⚠ **No call-records Dataset exists.** `roaming_tap` is operator↔operator settlement;
`mule_transfers` is account↔account. Neither carries A-party/B-party subscriber numbers, duration,
device or cell. The motivating scenario cannot currently be demonstrated, and a synthetic call-records
feed is a prerequisite for acceptance §13.

---

## 7. Controls

Non-optional in a criminal matter, and the difference between an analysis tool and an evidential one.

| Control | Seam |
|---|---|
| **Scope binding** — Case carries authorization ref, permitted date range, permitted identifiers; expansion beyond is refused or marked out-of-scope | new |
| **Per-query audit trail** — every projection, expansion, exclusion, reveal, export | ✅ mechanism ships (`EventLog.current().emit(...)`, `AuditLogRoutes`); ⛔ never called from `InvRoutes`/`GeoRoutes` |
| **Minimisation** — non-suspect identifiers masked by default; reveal is explicit, justified, logged | new |
| **Chain of custody** — dossier with SHA-256 over exhibit + records + op log + pinned Dataset version | planned as S3.2 |
| **Four-eyes** — approval for sensitive expansions and bulk de-masking | new |
| **Retention and purge** of Working Sets on matter closure | new |
| **Per-entity/per-link annotation** | ⛔ today annotation is per-saved-view comments only |
| **Coverage indicator** — which days and Collectors are missing for the window | new |

🔴 **Coverage is the highest-value control on this list.** A gap in the data is visually identical to
innocence. Nothing in the product currently warns about it.

---

## 8. Scale — four ceilings, **none of them G6 rendering**

Diagnosed 2026-09-22 by direct code read. 🔴 **The canvas is not the bottleneck and WebGL is not the
fix.** G6's own public demos draw far larger graphs than we ever hand it.

### 8.1 The caps, tightest first

| # | Cap | Value | Where | Kind |
|---|---|---|---|---|
| 1 | `PROJECTION_NODE_CAP` | **500** | `entity-projection.ts:32`, applied `:99,:150` | fetch — **the real governor** |
| 2 | `ANALYSIS_NODE_CAP` | 2 000 | `graph-analysis.ts:11`, `requireUnderCap` `:836-840` | algorithm; ⚠ **throws** |
| 3 | `DEFAULT_LIMIT` / `MAX_LIMIT` | 2 000 / 20 000 | `InvRoutes.java:62-63,178-179` | server rows |
| 4 | Main-thread execution | — | zero `Worker` in `inspecto-ui/src/app` | latency |

⛔ **Raising 2 or 3 alone changes nothing an analyst sees** — entity projection truncates at 500
first. Capacity work must target cap 1 and §8.2 together.

Cap 2 guards the super-linear set: `betweennessCentrality` (Brandes, O(V·E)), `louvainCommunities`,
`closenessCentrality`, `eigenvectorCentrality`, `katzCentrality`, `hits`, `cliques`
(Bron–Kerbosch, worst-case exponential), `maxFlow` (Edmonds–Karp, O(V·E²)), `linkPrediction`,
`suspicionScore`.

### 8.2 🔴 The actual slowness: full teardown and relayout on every interaction

`rebuild()` (`graph-view.component.ts:362-489`) unconditionally calls `graph.destroy()` (`:363`) →
`new Graph({...})` (`:393`) → `graph.render()` (`:488`). There is **no incremental path** — zero uses
of `setData` / `updateNodeData` / `changeData` in the file.

It fires from `ngOnChanges` on **any** `@Input` change (`:306-308`). Every bound input is an Angular
`computed()` signal returning a **new object reference** on each recompute — `canvasPlugins`
(`link-analysis.component.ts:268`), `displayOptions` (`:491-499`), plus `data`, `emphasis`, `layout`
(`link-analysis.component.html:707-717`).

**So toggling node labels, recolouring an edge kind, editing the predicate, or nudging the timeline
cutoff destroys the graph and re-runs the layout from scratch, synchronously.** When the active
layout is `force`, `force-cluster` or `mds` — all iterative `d3-force`/`force-atlas2`, none
configuring `iterations`, none setting `workerEnabled` — the entire simulation reruns on the main
thread for a cosmetic change.

Secondary and negligible next to the above: the tooltip `querySelectorAll('.tooltip')` sweep on
pointer/wheel events (`:293-304`) is a DOM query, not O(graph); per-element style callbacks including
the log2 edge width (`:447-452`) are O(N+E) arithmetic; `animation: false` is already set (`:400`),
so tween cost is ruled out.

Level of detail (`92032f78`) is **label suppression only** — `LOD_LABEL_CAP = 300`
(`link-analysis.component.ts:133`) folds into `displayOptions` (`:485-493`). It does not thin
elements, skip layout, or change renderer. The render footer prints the caps as text.

### 8.3 What to do, in order

1. **Make `rebuild()` incremental.** Diff and apply via G6's update APIs; recreate only when the
   layout id or renderer actually changes. Memoize the `computed()` inputs so cosmetic changes do not
   produce new references. *This is the single highest-value fix and it is bug-shaped, not
   architecture-shaped.*
2. **Separate cosmetic from structural.** A display change must never touch layout.
3. **Persist layout positions** across rebuilds so a relayout never silently rearranges the
   analyst's mental map mid-investigation.
4. **Then** raise cap 1 with the server-side Working Set behind it, move algorithms to a Worker
   (S1.1), and only after measuring consider the WebGL renderer — `package.json:33` installs
   `@antv/g6` alone, no `@antv/g6-plugin-webgl`, no `@antv/layout-gpu`.

### 8.4 Required of the Enquiry model

Server-side multi-hop traversal (recursive CTE) with the Working Set materialized so pruning does not
re-query; a pre-aggregated contact-pair Dataset (A, B, window, count, duration, value) as the
expansion substrate with raw records only on drill-down; a published, enforced, **graceful** supported
size (spec decision 3); progressive, cancellable expansion.

---

## 9. Reporting, reuse, and the standing-detection loop

Raised by the operator 2026-09-22: once an analyst judges a finding reportable, they should be able
to **save it as a Widget**, and **the scenario itself should be readable from the JSON** by someone
who was not in the room.

This is the payoff of §2.7. A closed op vocabulary can be rendered as numbered plain-language steps;
an open expression language cannot. The reporting requirement is therefore not a bolt-on — it is the
reason the vocabulary constraint earns its keep.

⚠ **"Report" is the wrong word for the narrative.** In this repo a **Report** is a *scheduled
delivery of rendered output* (a Dashboard exported on a schedule). The human-readable evidential
document is the **Dossier** (planned as S3.2). Using "report" for the narrative would collide.

### 9.1 The Working Set is a relation *defined by the log* — not a stored copy

⇒ **Operator decision, 2026-09-22: a Widget does not bind to a materialized resultset; the finding is
reproducible from the queries.** This is the better design, for three reasons:

- **No second copy of case data**, so no second access model to get wrong. An investigation's entity
  list is among the most sensitive data the product will hold; not duplicating it is worth a lot.
- **Nothing to keep in sync** with the log — the log is the only definition.
- It **matches how the product already works**: projections re-run on every call.

So the Enquiry exposes its entities and links as a **derived relation** — a definition the engine
evaluates on demand, cached and invalidatable, never a stored table. A **Widget** binds to that
relation, and Measures, Dashboards, Reports and Alert Rules follow, because a relation is a relation.
The relation carries the provenance columns (`opSeq` that admitted the entity, `seedId`, `hop`,
reason codes), which also gives the analyst the record-level table investigators want beside the
canvas.

🔴 **One condition makes or breaks this: "reproducible from the queries" holds only if the queries
read the same data.** Re-running against a grown Dataset reproduces the *method*, not the *finding* —
which is precisely the "a saved view is not evidence" defect the spec already records. Therefore:

- **If the data layer supports version-addressable reads** (DuckLake snapshots, per
  `enterprise-scale-out-plan.md`), pin the version in the Enquiry header and the derived relation is
  genuinely frozen. Nothing needs materializing, and the operator's position holds in full.
- **If it does not**, freezing one Evidence Artifact requires materializing *that Artifact* — the
  narrow exception, never the general mechanism.

⇒ This and **E3** (pin at creation vs per op) are one decision wearing two hats. Resolve together,
before Phase D.

⚠ **Re-evaluation is not free.** Spec §6 already records that projections re-run on every call, so
cost scales with usage rather than graph size. A Dashboard with six Enquiry tiles re-runs six
investigations per view. The cache is a **functional requirement** here, not an optimisation.

### 9.2 Two kinds of saved Widget — and they must never be confused

Both re-evaluate the same log. The difference is **which version of the data they read**.

| | **Evidence Widget** | **Monitoring Widget** |
|---|---|---|
| Reads | the Dataset version pinned at the seal | the current Dataset version |
| Shows | exactly what the analyst saw | current state **and drift** from the sealed finding |
| Changes over time | never | by design |
| For | authority, disclosure, court | ongoing operations |

🔴 **A Monitoring Widget handed to a reporting authority is a hazard.** The figures move under the
reader and the document stops matching the finding it cites. Pinned must be the default, and the
kind must be legible on the face of the tile — not buried in its config.

The drift line is what makes the monitoring kind worth having:
*"sealed 2026-09-22: 12 accounts · now: 19 · 7 admitted since"*.

### 9.3 One log, three renderings, three audiences

| Audience | Rendering | Carries |
|---|---|---|
| Analyst | the JSON log itself | re-runnable; loads straight back into an Enquiry |
| Reviewer / supervisor | numbered plain-language steps | every op **including exclusions**, with author and reason code |
| Authority / court | **method statement** inside the Dossier | what was done, by whom, when, against which pinned Dataset version, with the custody hash |

The middle rendering, worked through for the motivating scenario:

```
1. Seeded from 3 subscriber numbers            (J. Okafor, 2026-09-22 09:14)
2. Window 2026-09-01 → 2026-09-30, recurring 22:00–04:00 Africa/Lagos
3. Expanded 1 hop · either direction · voice+sms · min 1 event      →   214 entities
4. Excluded 37 entities — marketing-shortcode (reference list v4)
5. Expanded 1 hop from survivors · voice+sms · min 3 events, 2+ days → 1 190 entities
6. Threshold: in-window degree 2–40                                  →   402 entities
7. Excluded 4 entities — analyst judgement, "known family contacts"  (J. Okafor)
8. Expanded 1 hop · outbound · transfers only · min 5 events         →    61 entities
   🔴 rung budget 5 000 REACHED — result truncated
```

Because every line is one op from a closed vocabulary, this renders mechanically. No prose is
authored, so none can drift from what actually ran.

### 9.4 🔴 Negative space is part of the report

A chart showing 61 entities after 4 000 were removed is honest **only if the removals are stated**.
Every rendering must carry:

- what was excluded, how many, by which op, and under which reason code — **separating stated rules
  from analyst judgement** (§2.4), because the two are challenged differently
- every rung that hit `truncated`
- coverage gaps for the window (§7) — which days and which Collectors are missing
- which measures were computed over which entity set, and when they were last recomputed

Reporting only the surviving entities is how link charts mislead. This is the reporting counterpart
of the coverage indicator, and it is the clause most likely to be quietly dropped under delivery
pressure — so gate 10 pins it.

### 9.5 Enquiry Template — the method outlives the case

Per the glossary's Type/Instance rule, an **Enquiry Template** is the op log with seeds and window
left as parameters; an **Enquiry** is one binding of it to specific seeds and dates. Saving a template
turns a one-off investigation into a repeatable method a second analyst can run against a new
subject — and makes two findings comparable, because they were produced the same way.

### 9.6 The loop closes on machinery that already ships

Once §9.1 exists:

```
Enquiry → Enquiry Template → Measure over the Working Set relation
        → Alert Rule (threshold) → Alert → Incident → Case
```

Every noun after the first two already ships. A template scheduled over new data that raises an
**Incident** when the pattern reappears is the difference between an investigation tool and a
detection capability — and it needs **no new concepts**, only the relation in §9.1.

---

## 10. API contracts

Slotting into the planned endpoints rather than inventing parallel ones.

| Endpoint | Status | Purpose |
|---|---|---|
| `POST /inv/enquiries` | new | create; returns id + pinned Dataset version |
| `POST /inv/enquiries/{id}/ops` | new | append one op; returns the Working Set delta + `truncated` |
| `POST /inv/enquiries/{id}/replay` | new | full evaluation from `seed`; used for verification and evidence |
| `GET /inv/enquiries/{id}/log` | new | the ordered op log, renderable as plain-language steps |
| `POST /inv/traversal/recursive-paths` | planned S2.1 | the multi-hop primitive `expand` compiles to |
| `POST /inv/snapshots` | planned S1.3 | seals an Artifact; replaces the mock session store |
| `POST /inv/projection` + `filter` | planned S1.4 | ⛔ body ignores `filter` today; `ConditionSql.predicate()` already exists and is used by Decision Rules, Expectations and alerting — this is wiring, blocked only on spec decision 5 |

---

## 11. Phases

**Phase A — the object exists.** Enquiry + ordered op log + Working Set materialization + incremental
evaluator. Ops: `seed`, `expand` (one hop, reusing `neighbors`), `exclude`, `hide`, `keep`. Real
undo. Replaces the mock snapshot store. *Delivers: prune-then-expand, which is the scenario's
blocking step.*

**Phase B — the ladder and the clock.** Server-side multi-hop traversal; hop ladder; absolute range +
intraday window + timezone contract; in-window thresholds; `truncated` surfaced. *Delivers: the
motivating scenario end to end.*

**Phase C — identity and value.** Entity model (spec decision 4); resolution; reference lists that
persist across Enquiries; value measures and temporal ordering on the pattern packs.

**Phase D — evidence and reporting.** Scope binding, audit emit, minimisation, coverage indicator,
four-eyes. The Working Set exposed as a log-defined derived relation with a cache (§9.1, gated on
E3+E7), Evidence and Monitoring Widgets (§9.2), the three renderings and the chain-of-custody
Dossier (§9.3), negative space in all of them (§9.4).

**Phase E — the method outlives the case.** Enquiry Templates, then Measures and Alert Rules over the
Working Set relation, closing the loop to Incident and Case (§9.5–9.6). Cheap once Phase D lands,
because every noun in that chain already ships.

Phase A is a precondition for everything. Phases C and D are what make the output usable as evidence
rather than as analysis. Phase E is what turns one investigation into a detection capability.

---

## 12. Open decisions

⛔ None should be answered by an implementer in passing.

- **E1. What is the object called?** `Investigation` is reserved by `GLOSSARY-CASE-1`; `Case` is
  `ObjectType.CASE`. `Enquiry` is this plan's placeholder. Blocks every touchpoint, so decide first.
- **E2. Where does a Working Set live?** In-memory per session, a DuckDB temp relation, or a durable
  store? Durability is what makes replay and evidence possible; it is also a new persistence seam.
- **E3. Is the Dataset version pinned at Enquiry creation, or per op?** Pinning at creation is simpler
  and matches evidence needs; per-op allows an Enquiry to follow live data. They are incompatible.
- **E4. Does re-ordering the log invalidate downstream Artifacts, or fork the Enquiry?** Forking is
  safer and costs a branching model.
- **E6. Frozen or live by default for a saved Widget,** and may a live one leave the Space at all?
  §9.2 recommends frozen; the opposite default is defensible for operations and dangerous for
  disclosure.
- **E7. Who may evaluate an Enquiry's derived relation?** §9.1's decision removes the worst version
  of this — case data is never copied into the Dataset registry — but a relation that BI can bind a
  Widget to still needs an access model of its own, and it must inherit the Case's scope rather than
  the Space's Dataset permissions. 🔴 Otherwise a Dashboard tile becomes a side channel around
  §7's scope binding.
- **E8. Does an Enquiry Template carry its exclusion lists?** A reference list of marketing
  shortcodes should travel with the method; a list of "known family contacts" from case 1 must not
  leak into case 2. Likely: named reference lists travel, analyst-judgement sets do not.
- **E5. Inherited:** spec §7 decisions 2 (traversal location), 3 (supported size), 4 (entity
  identity), 5 (bind vs escape).

---

## 13. Acceptance gates

Falsifiable, in house style — each states what would have to be observed.

1. **Order is respected.** `exclude(m) → expand(3)` and `expand(3) → exclude(m)` on the same Dataset
   produce **different** Working Sets, and the difference is exactly the entities reachable only
   through `m`.
2. **Replay reproduces.** Full replay of a log against the pinned Dataset version yields byte-identical
   Working Set membership to the incremental evaluation.
3. **Replay diverges when it should.** The same log against a *later* Dataset version is detected and
   reported, never silently served.
4. **Hide is not exclude.** A `hide`d hub still yields its downstream entities on the next `expand`; an
   `exclude`d one does not.
5. **Thresholds respect the window.** An entity with 50 lifetime contacts but 2 inside the window fails
   a `minDegree: 3` threshold.
6. **The midnight-crossing window is correct.** A 22:00–04:00 slot includes 23:30 and 01:30 of the
   following calendar day, under a stated timezone, pinned by test.
7. **Truncation reaches the analyst.** A rung that breaches its budget sets `truncated` **and** the SPA
   displays it.
8. **Nothing is silently arbitrary.** Every entity in a Working Set can be traced to the op that
   admitted it and the seed it descends from.
9. **No op can be coerced into arbitrary SQL.** The closed vocabulary compiles through `SAFE_IDENT` +
   bind params; pinned by test as `InvRoutes` already is.
10. **A narrative cannot omit an exclusion.** A log containing `exclude` ops renders to a step list
    and a Dossier that both state every excluded set, its count and its reason code — pinned by a
    test that adds an exclusion and asserts it appears in all three renderings of §9.3.
11. **An Evidence Widget does not move.** Reopened after the Dataset has grown, it re-evaluates its
    log against the **pinned** version and shows figures identical to the sealed Artifact; a
    Monitoring Widget over the same log shows the new figures **and** the drift against the seal.
    ⚠ This gate fails outright if version-addressable reads are unavailable — which is what makes
    §9.1 / E3 a precondition rather than a detail.
12. **A template carries the method, not the case.** An Enquiry Template re-bound to new seeds
    produces a structurally identical op log, and carries no analyst-judgement exclusion set from
    the Enquiry it was derived from (E8).

---

## References

- [`link-analysis-spec.md`](link-analysis-spec.md) — target state, clause-tagged
- [`link-analysis-advancement-plan.md`](link-analysis-advancement-plan.md) — Sprints 9-11
- [`link-analysis-ui-mockup.html`](link-analysis-ui-mockup.html) — clickable target-state design
- [`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) — as-built mechanism
- [`../okf/capabilities/studio/studio.md`](../okf/capabilities/studio/studio.md) §2 — `INV-1`..`INV-4`
- [`../GLOSSARY.md`](../GLOSSARY.md) §11 — graph vocabulary lock; `GLOSSARY-CASE-1`
