# Branch-aware executor — segment-scoped lift (design pass)

**State: DESIGN — nothing built.** BACKLOG row *Branch-aware executor residuals*, clause (b): multi-schema +
`route:` needs a segment-scoped lift; ⛔ do not just lift the refusal. Written 2026-09-24 against `425ee239`.

## 1. The problem

`RouteArming.refusals` clause (4) refuses `route:` on any multi-schema Pipeline (selector `schemas[]` or
plugin `segments`) — `inspecto-etl/src/main/java/com/gamma/etl/RouteArming.java:126-137`, raised by
`PipelineConfig.prepare()` at `inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java:1872-1875`.

### 1.1 The refusal's own rationale is partly STALE

The comment (written 2026-08-26) says arming "would execute EVERY schema's route tree against EVERY segment's
table". Since `f2b21616` (2026-09-16) that is no longer true for the **segments** path:

- `UnionModeIngester` calls `writeAndTrace` once per segment with `writeScope = segKey`
  (`inspecto-engine/src/main/java/com/gamma/inspector/UnionModeIngester.java:122-157`).
- `writeAndTrace` resolves `segKey = segmentWrite(cfg, writeScope)` and seeds the walk at `map_<segKey>`
  (`ConsignmentIngestStrategy.java:158-159`, `seedOfWrite` at `:517-519`).
- `PipelineExecutor` skips every node with no live inbound relation
  (`inspecto-engine/src/main/java/com/gamma/pipeline/exec/PipelineExecutor.java:221-222`), so a walk seeded at
  `map_receipt` reaches only `route_receipt → sink_receipt__d*`.

So the *walk* is already segment-scoped by accident of the seed. What is **not** scoped is everything that
reasons about the graph before and after the walk. That is why lifting the refusal alone is still wrong.

### 1.2 What would actually break if the refusal were deleted

| # | Defect | Where | Effect |
|---|---|---|---|
| D1 | **Selector path seeds the wrong schema.** A CSV `schemas[]` batch is ONE schema, written with `writeScope = ""` (`CsvIngestStrategy.java:172-182`), so `segKey = null` and `seedFeedingTheWrite` picks the **first** `transform.route` node in the lifted graph (`ConsignmentIngestStrategy.java:528-546`). | seed choice | **Mis-route + mis-trace.** A batch of schema B is seeded into `map_A`, routed by `route_A`, written through `sink_A__d*` — whose `store`/`table`/`schema` config (`PipelineLift.java:465-477`) names schema A. Lineage and the Catalog attribute B's rows to A's Dataset. |
| D2 | **The route admission ignores `segKey`.** `admittedLift` lifts and tests `engages()` on the WHOLE graph when `routeConfig() != null` (`ConsignmentIngestStrategy.java:195-197`); only the non-route branch is segment-scoped (`:198`). | admission | Engagement is decided on N schemas' trees at once. Today harmless (any multi-schema + route count is > 1), but `-Dingest.lane=graph` `flatReason` and every diagnostic then describe the whole graph, not this write. |
| D3 | **One `route:` block, N mapped rows.** The lift copies the SAME `routeCfg` into every schema's subtree (`PipelineLift.java:445-451`). Arming validates each branch's `where:` once (`RouteArming.java:88-101`) and the save-time check judges ONE mapped row (`425ee239`). | arming | A predicate on a column only `receipt` has **fails at bind time on the `dispatch` segment** — mid-run, after arming, the exact failure class clause (2b) exists to convert into an authoring-time answer. |
| D4 | **Crash resume refuses the shape.** `DrainCommand.soleSchema` assumes "multi-schema × route is a standing refusal" and throws for `schemas[] > 1` and for every plugin path (`inspecto-engine/src/main/java/com/gamma/inspector/DrainCommand.java:206-224`). | drain | A parked or half-committed multi-schema route batch has **no completion path** — the branch-commit ledger records the rows as owed and nothing can pay them. |
| D5 | **Partial batch across segments.** Each per-segment call commits its own branches under scope `segKey` (`BranchCommitCoordinator` scope). If segment 2 throws after segment 1 committed, the batch is FAILED with segment 1's branch files already durable. | commit | Not a double-write (the ledger scope prevents it on retry), but the retry semantics must be stated and tested, not inherited. |

Branch landing is NOT a defect but must be decided: `IngestSinkWriter.write` re-roots `dbDir`'s suffix under the
branch's database (`IngestSinkWriter.java:72-75`), so segment `receipt`'s branch `hot` lands at
`<hot.database>/receipt/…` — one store per (branch × segment).

### 1.3 Concrete failing example

`spaces/demo/config/warehouse/stock_movements_pipeline.toon` (plugin `XmlRecordIngester`, segments
`receipt` / `dispatch` / `transfer`) plus:

```
route:
  mode: case
  default: normal
  branches[2]:
    - key: bulk     where: "qty_received > 1000"   database: data/stock_movements/bulk
    - key: normal   where: "true"                  database: data/stock_movements/normal
```

With clause (4) deleted: `receipt` routes correctly; `dispatch` has no `qty_received` column → the
`route_dispatch` CASE fails to bind → the batch FAILS after `receipt`'s two branches committed (D3 + D5); a
restart cannot drain it (D4). The CSV `schemas[]` twin mis-files every non-first schema's rows (D1).

## 2. Constraints

- `writeAndTrace` stays the ONE lane fork; no earlier divert (`ConsignmentIngestStrategy.java:127-133`).
- The walk seeds the node the flat lane already executed — never a second parse/map (`:383-388`).
- Decision Rules run once, above the fork; rule routing + route branches stays refused (`:356-363`).
- Versioned reference store + route stays refused permanently (`:372-375`).
- Branch↔sink pairing is by database; `PipelineEditable.lower` must round-trip whatever the lift emits
  (`PipelineLift.java:483-491`) — a scoped lift must be a *view* over the one lift, not a second emitter.
- Node-id grammar is decided once in `PipelineLift` and mirrored byte-for-byte in `pipeline-editable.ts`.
- `ConsignmentGraphRunner.engages` / `dataFedSinkCount` deliberately exclude a multi-schema parser's
  `route:<key>` dispatch edges (`ConsignmentGraphRunner.java:160-162`, `:205-208`) — keep that.

## 3. Options

**A. Scoped view of the one lift (recommended).** Add `PipelineLift.scope(graph, key)` → the sub-graph
reachable from `map_<key>` (plus that node). `admittedLift` uses it on BOTH branches whenever a write key is
known; `seedOfWrite` is then unambiguous. The selector caller passes the batch's schema key as `writeScope`
(and `segmentWrite` learns selector keys via `PipelineLift.routeKey`). Arming replaces clause (4) with a
per-schema check: every branch predicate must bind against every schema's mapped row. `DrainCommand` resolves
the schema from the manifest's segment/table instead of refusing.
*Cost:* ~5 small slices; no config-shape change; round-trip untouched (the stored graph is still the full lift).

**B. Fix only the seed (D1) and lift the refusal.** Rely on the executor's inbound-skip for scoping.
*Rejected:* leaves D2–D5; scoping stays an emergent property of a skip rule nobody tests for this purpose.

**C. Per-schema `route:` blocks** (`segments.<key>.route` / `schemas[i].route`). Solves D3 by construction.
*Cost:* a new config home, ConfigSpecs/JSON-schema, editor and lowering changes, recipe grammar. Only worth it
if the operator wants per-segment routing semantics (Q1).

## 4. Recommendation

Option A. One `route:` block applies to every schema; arming fails closed unless every predicate binds in every
schema; the walk runs on the scoped view; drain becomes schema-aware. C stays available later as an additive
config home if Q1 answers "per segment".

## 5. Open questions for the operator

1. **Shared or per-schema routing?** Should one `route:` block apply to every segment (predicates must bind in all of them), or does the product need a route per segment (Option C)?
2. **Landing layout:** accept `<branch database>/<segKey>/…` (one store per branch × segment, today's re-rooting) — or must a branch collect all segments into one store?
3. **A predicate that binds in some segments only:** refuse arming (fail closed, recommended), or treat the segment as unrouted and send it all to `default:`?
4. **Scope of the slice:** ship the plugin `segments` path and the CSV `schemas[]` selector path together, or segments first?
5. **Partial batch (D5):** is "segment 1's branches durable, batch FAILED, retry skips what the ledger holds" the accepted semantics, or must a multi-segment batch be all-or-nothing?

## 6. Build plan (ordered slices)

Each slice: unit tests of the touched module only (`-pl <module> -Dtest=A,B`, commas); full reactor at the end.

1. **S1 — pin the defects (test only).** In `inspecto-engine`: a selector `schemas[2]` + route config →
   assert `seedOfWrite(admittedLift(...), null)` is `map_<first>` for a second-schema batch (D1, currently
   green-for-the-wrong-reason); a segments + route config seeded at `map_dispatch` → assert the walk touches no
   `route_receipt` node (documents the existing scoping). → verify: both run and state today's behaviour.
2. **S2 — `PipelineLift.scope(graph, key)`** + `PipelineLiftTest` cases: scope of a segments lift, a selector
   lift, a single-schema lift (identity), a branch with `steps[]` chain (chain nodes kept).
3. **S3 — admission + seed.** `admittedLift` route branch uses the scoped graph when `segKey != null`;
   `CsvIngestStrategy` passes the batch's schema key; `segmentWrite` recognises selector keys. Flip S1's D1 test
   to assert the correct seed. → `GraphLaneSegmentAdmissionTest`, `FlatVsGraphLaneParityTest`.
4. **S4 — arming.** Replace clause (4) with a per-schema predicate-bind check in `RouteArming` (fed each
   schema's mapped columns), shared by `ConfigRoutes.routeArmingFindings`. Mutation check: re-insert the blanket
   refusal → the new positive test goes red. → `RouteArmingTest`, `ControlApiRouteArmingTest`.
5. **S5 — drain.** `DrainCommand.soleSchema` → schema-by-manifest; update `DrainCommandRefusalTest` (the
   multi-schema refusal becomes a completion test; plugin refusal stays until Q4 says otherwise).
6. **S6 — end-to-end.** A test-fixture copy of the stock_movements Pipeline with the §1.3 `route:` (predicate
   on a column all three segments share): assert per-(branch × segment) row counts, lineage attributing each
   file to its own segment, and a kill-after-segment-1 restart that completes without duplicates. ⛔ Fixture
   under test resources, never `spaces/demo`.

**Regression set that must stay green throughout:** `RouteArmingTest`, `ControlApiRouteArmingTest`,
`GraphLaneSegmentAdmissionTest`, `FlatVsGraphLaneParityTest`, `PipelineLiftTest`, `PipelineEditableTest`,
`ConsignmentGraphRunnerTest`, `ConsignmentGraphRunnerLiftEngagementTest`, `ConsignmentGraphRunnerFinalizeTest`,
`BranchCommitTest`, `DrainCommandRefusalTest`, plus the UI `pipeline-editable` spec (id grammar) — and the full
`-Pedition-enterprise` reactor before push (shared seam: `writeAndTrace`).
