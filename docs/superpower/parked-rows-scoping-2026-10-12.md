# Parked rows chosen for BUILD: scoping (2026-10-12)

**Status 2026-10-12: decisions D1-D14 TAKEN (section 9a). Built since: batch same-stem guard (stage 1), `group_by` removed from `derive`, Parsing D9 slices 2-3, Risk Score calculated-column note, D6 script. Parked: batch stage 2, BI-query masking, preview rows, Space-comparison hygiene, the think-aloud run.** Originally scoping only. The operator chose to build seven parked
BACKLOG rows. Every claim below was re-checked against the tree at `origin/master` `299becc07` (grep / read, no
Maven, no `ng`). Effort: S = under a day, M = 1-3 days, L = a week or more. "SAFE" = an agent can build it now with no
operator call; "DECISION" = blocked on an item in the numbered list in section 9.

Plans live here only while in flight: when a row ships, distill the as-built facts into the matching OKF concept,
move leftovers to `docs/BACKLOG.md`, and archive this doc (CLAUDE.md, documentation lifecycle).

## 1. Verdict table

| Row | Verdict | One line |
|---|---|---|
| `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1` | **needs decision** | The fix is real and bounded, but it changes the output file name of every multi-file batch (D1, D2). |
| `Parsing (Stage-1) - residuals` | **partly ready** | D9 slice 2 and slice 3 are specified and approved; two leftovers are one-line decisions (D5, D6). |
| `ANOMALY-DETECTION-RESIDUALS-1` | **mixed** | (a) tile is blocked on BI masking (D7); (b) goldens are ready but need an authoring decision (D8); (d) hour buckets is L and needs a scope decision (D9). |
| `ASSURE-RISK-SCORE-RESIDUALS-1` (7) | **needs decision** | Premise still true (a Measure has no server home); the arithmetic half is buildable, the saved-Measure half is a product call (D10, D11). |
| `Bundle / Exchange - "load as draft" residuals` | **ready, tiny** | Only the browser pass over six editors is left; it is a verification, not a build. |
| `Job framework - space-to-space comparison (residuals)` | **needs decision** | (a)(b)(c) are all security or residency calls; only two small API-hygiene items are safe (D12, D13). |
| `D6-REAL-USER-SESSION-1` | **ready (prepare)** | Script in section 8. The session itself needs a human lead (D14). |

## 2. `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1`

**What is really open (verified).** `ConsignmentIngestStrategy.consolidatedBaseName` (`platform/inspecto-engine/.../inspector/ConsignmentIngestStrategy.java:748`)
returns the lone member's file stem for a one-member batch, else `batch.batchId()`. Three ingest paths call it
(`CsvIngestStrategy:235`, `NativeCsvStreamingEngine:157`, `UnionModeIngester:163`) and `DrainCommand:144` calls it
again to resume a parked commit, so any change has four call sites that must agree. The pinned hole is
`KillBeforeAckDuplicateTest.knownHoleMultiFileBatchReplannedAfterKillDuplicates` (asserts 4 rows, 2 are correct).

**The shape of the fix is bigger than the row says.** The consolidated write is ONE output file per partition for
the whole batch (`ConsignmentOutputs` javadoc: *"One output file per partition per write ... `PartitionWriter.reveal()`
renames every staged file of a partition onto a single `<baseName>_out.<ext>`"*), holding rows from all members
tagged by `__src_id`. "Name every output per member" therefore means **splitting the write per member**
(one `COPY ... WHERE __src_id = n` per member, or a per-member pass), not renaming. Consequences to price:

* Output file count per partition goes from 1 to N per batch (N = batch size). This is the intended cost of the
  fix; it is also the "consolidation semantics" change the row warns about. Small-file pressure on partitions is
  real for large batches (the engine has `PartitionCompactor` / `ReferenceCompactor`, so the answer may be to
  compact, see D2).
* `LineageCollector` computes a per `(srcId, partition)` matrix and `ConsignmentOutputs.fromLineage` sums it per
  output file. With one file per member the matrix gets simpler (each file has exactly one srcId); the code that
  assumes "key counts by partition" (`fromPartitionCounts`, enrichment / Pipeline-sink paths) is unaffected because
  those paths do not use `consolidatedBaseName`.
* Two members with the same stem from different directories collide on `<stem>_out.<ext>` (today a 1-member batch
  has the same exposure). `FileNames.outputStem` is the seam to check; it must stay unique per origin path or the
  second member silently overwrites the first. This is the main correctness risk and needs its own test.
* Versioned reference stores write `baseName + "__v_" + batchId` ("batch-unique, never overwrite") and must keep
  doing so; the fix must not touch them.
* It does NOT help node-local output trees (documented at-least-once contract, `okf/backend/engine/ingestion.md`
  section *Failover is at-least-once*).

**Alternative to the row's option (a), cheaper.** Keep one file per batch but make the batch id stable under
re-planning: derive it from the sorted member identity set only when no extra members joined. That cannot work, the
hole is precisely that the member SET differs. So the real alternatives are (a) per-member naming, or (c) an
output-existence check keyed per member (skip a member whose `<stem>_out` for this partition already exists and
whose lineage row is committed). (c) is invasive in the commit/lineage ledger and weaker on node-local trees.
Recommendation: (a).

**Smallest slices.**

| # | Slice | Size | Class |
|---|---|---|---|
| 1 | Per-member output split behind a pipeline setting `processing.output_per_member` (default OFF, then flip), one shared helper used by the 4 call sites | M | DECISION (D1) |
| 2 | Stem-collision guard: reject / disambiguate two surviving members with the same output stem, fail closed | S | SAFE (can land first; also protects today's single-file path) |
| 3 | Flip the pinned test to assert 2 and add: per-member naming survives a re-planned superset; drain resume uses the same names; lineage rows sum per file; reference-store naming unchanged | S | follows slice 1 |
| 4 | Docs: `okf/backend/engine/ingestion.md`, HA/DR runbook paragraph | S | follows slice 1 |

**Gate / security.** No new route, no gate. Data-path change only: it touches the commit ordering that
`CommitFence` and the lease protect, so run `KillBeforeAckDuplicateTest`, the lease/failover tests and the lineage
tests, plus a drain-resume test (`DrainCommand`). Output names appear in lineage and in `consignment_outputs`
registry rows; any reader that globs `<batchId>_out*` must be found by grep for `_out` before merge
(`DbConsignmentOutputStore` documents a stable `<baseName>_out.<ext>`, so most readers are name-agnostic).

**Tests.** The flipped hole test; same-stem-different-dir collision (red before slice 2); multi-file batch with
two partitions and one member present in both; kill-between-output-and-ack with a re-planned superset; parked
commit resumed by `DrainCommand`. Unit level (`-pl platform/inspecto-engine -Dtest=...`); the full reactor only at
handoff.

## 3. `Parsing (Stage-1) - residuals`

**What is really open (verified).** `DeriveSpec` and `DecodeProfile` validation and `Asn1RecordIngester` wiring exist
(slice 1, `9c07a0aeb`). Not present anywhere in the tree: a `children` explode, `ASN1-CHILD-UNKNOWN-SEGMENT`,
`max_child_rows_per_record`, any UI mention of `derive` (the Parse drawer in
`inspecto-ui/.../pipeline-parse-definition.component.ts` has none). So:

| # | Open item | Size | Class |
|---|---|---|---|
| P1 | **Slice 2** `children` explode with `$parent.*`, `$index`, `max_child_rows_per_record`, new `ASN1-CHILD-*` codes, multi-segment corpus case. Approved 2026-10-11 with the recommended answers, so the design questions 2, 3, 5, 6 of `parsing-repeated-child-design.md` section 11 are answered. | M | SAFE (approved) |
| P2 | **Slice 3** drawer read-only display of `derive` (the drawer already has the effective-value-with-origin pattern over `GET /parsers/asn1/profile`) | S | SAFE |
| P3 | Preview computes derived columns (the preview path does not today) | S-M | SAFE, but touch the pack-parser preview gate: derive runs over the same decoded map, no new read; confirm it never echoes file content (the design's never-echo rule) |
| P4 | `ASN1-DERIVE-NOT-IN-GRAMMAR`: a walk over the compiled grammar schema to confirm a path resolves to a SEQUENCE OF | M | DECISION (D5): worth it only if profiles are hand-authored often |
| P5 | Surfaced counters channel (`RecordSink` has none; `derive_non_numeric` / `derive_no_match` are INFO logs) | M | DECISION (D5): it widens the `RecordSink` SPI |
| P6 | `group_by` is effectively a no-op | S | DECISION (D6): remove the key from the closed vocabulary, or implement per-group output (that is explode) |
| P7 | Run `asn-golden` `SyntheticCorpusTest` on a profile where `surefire-junit-platform:3.5.3` resolves; `corpus-synthetic/repeated_child/expected.jsonl` is hand-written and unconfirmed | S | SAFE but needs a machine with the jar; not now (no Maven allowed on this lane) |
| P8 | D8 out-of-process parser host, T2 signer anchoring | L | stays deferred, no change |

**Gate / security.** No new route. Slice 2 multiplies rows per record: the `max_child_rows_per_record` cap (default
10 000, engine ceiling the profile cannot raise) must FAIL the file as `QUARANTINED_UNREADABLE`, never truncate
(a truncated child list is a wrong charge). Child rows carry parent leaves, so a classified parent leaf (MSISDN)
lands in a second Dataset: classification must propagate to the child segment's columns (check the way
`derive` columns after `raw.fields` are classified today). Corpus tests stay opt-in and DATA-GOV-1 data-gated;
only fabricated values in tracked fixtures.

**Tests.** `RepeatedChildTest` extension for every explode op and NULL case; one red `DecodeProfile` test per new code;
a multi-segment `corpus-synthetic` case with per-segment row counts and a cap breach; regression that profiles
without `children` produce byte-identical rows (`backtoback`, `huawei_framed`).

**Doc correction found.** `docs/INDEX.md` line for `parsing-repeated-child-design.md` still says "DESIGNED 2026-10-11,
awaiting operator approval, not built"; the design header says APPROVED and slice 1 BUILT. Fix when this row ships
(not edited here, out of scope).

## 4. `ANOMALY-DETECTION-RESIDUALS-1`

Items (c), (e), (f), (g), (h) and the Feature `filters` / `unit` follow-up are shipped (row text). Verified open:

**(a) Dashboard tile.** The row's blocker is **still true**: `BiRoutes` vets only `orderBy` against classified
columns (`vetHiddenOrderColumns`, `EvidenceMasker.sensitiveColumns`); nothing masks the entity key in `/bi/query`
result rows, and `/queries/{id}/run` shares the path. The Query Core spec does carry `filters` and `limit`
(`MeasureCompiler.Spec`), so the claim "Widget layer lacks a Widget-level filter and a table row limit" is about the
**Widget configuration** (SPA side), not the wire; confirm in `inspecto-ui` Widget model before sizing (I found
`limit` only in `bi-query.service.ts`, not in a Widget field).

| # | Slice | Size | Class |
|---|---|---|---|
| A1 | Mask classified columns in `/bi/query` / `/queries/{id}/run` result rows under the same `canRevealLinkEntities`-style capability the scores routes use (`com.gamma.mask.EntityKeyMasking`, `keyMasked`) | L | DECISION (D7): it is a platform-wide BI behaviour change that affects every Dataset with a classified column |
| A2 | A "top N" Widget: Widget-level filter + row limit fields in the Widget config and builder | M | SAFE for the field plumbing, but useless to ship as a template tile before A1 |
| A3 | The tile in the three pack templates (`spaces/_templates/{telco-fraud,mobile-money,aml}`) | S | after A1 and A2 |

**(b) Pack goldens.** Verified: `AnomalyTemplateModelsTest` checks each pack model is storable and wired against
ZERO-row seed Datasets, and `AnomalyGoldenCorpusTest` / `AnomalyPeerGoldenCorpusTest` use synthetic corpora in the
module, not the packs. "Per pack, drift-pinned" means a multi-week synthetic corpus per pack plus the expected
scores, which breaks (by design) whenever a pack model changes.

| # | Slice | Size | Class |
|---|---|---|---|
| B1 | A synthetic 5-week corpus generator + expected-scores file for `tf_subscriber_usage` and a test that evaluates the shipped pack model over it | M | SAFE (fabricated data only) |
| B2 | Same for mobile-money and AML | S each | SAFE, after B1 proves the pattern |
| B3 | Decide whether a pack-model edit must regenerate the golden (drift pin) or the test only pins the "known anomaly day is elevated" property | S | DECISION (D8) |

**(d) Hour buckets.** Verified: `AnomalyModel.java:225` refuses `bucket: hour` ("not built yet");
`Seasonality` already implements HOUR and WEEKDAY_HOUR slots and fallback chains (library-complete);
`AnomalyScoreEvaluator` builds day-grained bucket specs (`from + " 00:00:00"`) and the `anomaly.score` Job takes a
day `asOf`. Real work: an hourly time grain through the bucket spec, hourly `asOf` and window arithmetic, the
MAX_BUCKET_ROWS cap (24x the rows per entity-day), hourly Job scheduling, UI bucket picker, the preview `series`
shape, golden corpora for hour seasonality, and the D-AD9 performance re-measure (`PeerMedianBench`). Size L.
DECISION (D9): scope (hour only, or also weekday_hour), and whether to build before any pack needs it.

**(i) LA panel gate.** The Link Analysis anomaly panel shows only once a Risk Score model is named; the row
says it is LA's call. No work for this lane; keep as parked.

## 5. `ASSURE-RISK-SCORE-RESIDUALS-1`, item (7)

**Verified premise.** `RiskScoreModel.Factor` holds `measure` as a string (`count` or `agg(field)`) compiled by
`MeasureCompiler` (`RiskScoreModel.java:76-100`), and `okf/backend/control-plane/risk-scores.md:306` says there is
no arithmetic and no saved-Measure reference "because none exists". `okf/capabilities/studio/studio.md` item 4 confirms
"a Measure has no server home": the Dataset editor's `NamedMeasure {id, label, expression}` is client-side only and
the wire carries validated `{agg, field}` pairs. So the premise holds; the second half of item (7) is a **platform**
decision (making a Measure a server concept), not a Risk Score change.

**Two separable sub-items.**

* **7a. Free-form arithmetic per factor** (e.g. `sum(a) / nullif(sum(b), 0)`). There is a reusable guard:
  `ExpressionGuard` (closed token alphabet, keyword deny-set, function whitelist, 500 chars, no subqueries) already
  protects Dataset `calculated` columns. Cheapest safe route: let the Dataset own the arithmetic (a calculated
  column) and the factor aggregate it, which is **possible today with zero code**. Building 7a means admitting an
  expression inside the `measure` string, which `MeasureCompiler.splitShorthand` does not parse. Size M-L, and
  every new surface needs the three-round adversarial review the row says the original needed.
* **7b. Saved-Measure reference** (`measure: ref:avg_duration`). Needs a server home for Measures (a new
  component kind or a field on the Dataset), then BI / Alert Rule / Risk Score consumers. Size L, cross-cutting.

**Recommendation.** Do not build 7a or 7b now. Do the S "document the Dataset-calculated-column workaround" slice,
and decide Measures-as-a-server-concept once, in the studio area, where it blocks BI-3 / BI-5 as well (D10, D11).

| # | Slice | Size | Class |
|---|---|---|---|
| R1 | Doc the calculated-column workaround in `risk-scores.md` and the authoring pane's factor help text | S | SAFE |
| R2 | Server-side Measure kind (own plan, own review) | L | DECISION (D10) |
| R3 | Arithmetic in a factor via `ExpressionGuard` | M-L | DECISION (D11) |

**Gate / security.** Any expression reaching SQL is the highest-risk surface in the repo (SqlGuard, masking via
`EvidenceMasker`, reproducibility of stored factors: a stored factor records its `measure` text, so a saved-Measure
reference must be resolved and FROZEN at run time or the score stops being reproducible). Tests: the adversarial
cases from the original review (deny-set, subquery smuggling, `read_parquet(`), reproducibility after a Measure
edit, and a classified column inside an expression.

## 6. `Bundle / Exchange - "load as draft" residuals`

**Verified.** Every slice shipped 2026-09-25, plus the 2026-10-04 and 2026-10-06 follow-ups (the row says so and
`okf/backend/control-plane/metadata-bundle.md` carries the as-built). The only open bullet is **"a browser pass
over the six editors"** (Dashboard, Widget, Dataset, authored Pipeline, Link Analysis view, Geo map view).

| # | Slice | Size | Class |
|---|---|---|---|
| E1 | Drive **Import as draft...** in each of the six editors against a demo build: banner shows, Save goes through the editor's own route with `If-Match` and a diff for an existing id, integrity findings re-checked just before Save, LA / Geo Save shows `LaTransfer.recheck` findings, an unauthorised caller sees no Import-as-draft. Record pass or fail per editor in `okf/backend/control-plane/metadata-bundle.md`. | M (browser, one build) | SAFE; needs a running demo build and the Browser pane, so it is not for a docs-only lane |

Gate implications: none new (writes go through each editor's existing gated route). Close the row when E1 is
recorded; file any finding as its own row (do not widen this one). Rank: lowest of the seven; do it alongside
D6 (same demo build, same pane skills).

## 7. `Job framework - space-to-space comparison (residuals)`

**Verified.** (d) the UI is shipped (`b584c0506`); `JobsService.run` typed read shipped. Open and verified:

* (a) scheduled / authored cross-Space comparison, (b) compare every registered Space, (c) persist comparison rows:
  all three are **operator decisions**, not plumbing (the row and the archived design agree). No build for any of them
  until D12 is answered.
* "no axis list is served, so the axes filter is free text": still true (`SpaceComparisonRoutes:75` takes `axes`
  free-form; `SpaceComparisonJob` filters by name). **Slice C1, S, SAFE**: serve the known axes from the storage
  series (`StorageSeries.AxisTrend` keys) as a read on the same `canAdminister` gate, and make the UI field a picker.
* "the 202 body / `Location` are not described in the OpenAPI": **partly stale**. The fragment
  (`inspecto/src/main/resources/META-INF/inspecto/openapi.fragment.json`, `/space-comparisons`) describes 202 +
  `runId` + `Location` in prose and says "202 carries runId, spaces, status", but has only a `default` response with no
  typed 202 schema. **Slice C2, S, SAFE**: add a typed 202 response. A route's contract edit must keep the
  `docs/api/openapi-v1.json` mirror and the openapi contract test green (memory: 13 modules go SKIPPED when it drifts).

| # | Slice | Size | Class |
|---|---|---|---|
| C1 | Axes list read + UI picker | S | SAFE |
| C2 | Typed 202 in OpenAPI fragment and docs mirror | S | SAFE |
| C3 | Authored / scheduled comparison with a persisted, attributable, revocable grant | L | DECISION (D12) |
| C4 | Enumerate every registered Space (needs a `SpaceConfigRoot` enumeration call) | M | DECISION (D12) |
| C5 | Persist comparison rows | M | DECISION (D13): data-residency call |

**Gate / security.** The whole row is about one security boundary: today's comparison runs with the requester's
own-Space grant and `canAdminister`. C3 and C4 create a cross-Space read with no live human Subject; that needs the
grant object (who granted, which Spaces, expiry, revoke, audit) and a refusal when the grant lapses. C5 moves
per-Space numbers into one Space's store: classification and retention follow the data, so a Space that did not
consent would see its storage figures in another Space's ledger. Keep the Signal-only result until D13.

## 8. `D6-REAL-USER-SESSION-1`: think-aloud script for a real Case-desk lead

**Verified.** The row is open; the archived design (`archived-documents/plans-archive/findings-spec-authoring-ui-design.md`
section 1 and section 8) defines the persona (case-management lead, not a developer), tasks T1-T8 and the real
criterion: **T2, T6, T7 and T5 done unaided, pass or fail per task** (D10 there). The proxy session
(`okf/frontend/features/objects.md`, *Proxy acceptance session 2026-09-29*) found five defects; **all five are closed**
on the board (`FINDINGS-EDITOR-CHOICE-BINDING-1` 2026-09-29, `CASE-DETAIL-DOCUMENT-SCROLL-1` 2026-09-30,
`CASE-FIRST-OBJECT-DEAD-END-1` 2026-10-03 with the empty-state message, `FINDINGS-EDITOR-ADD-CHOICE-FOCUS-1` per
the 2026-09-29 board note). **`FINDINGS-PER-CASE-TYPE-1`** (the form applies to every Case) is named in the board note
and `okf` but is not a row on the board today; the real lead will hit it at once, so file or decide it before the
session (D14 sub-point).

**Before the session (setup, 30 min, SAFE for an agent).**

1. A build of current `master` with the `demo` Space and a bundle-local `demo-users.toon` holding a lead who has
   `canManageIncidents` and a second user who has not (the proxy used `package.ps1 -Edition Enterprise -DemoAuth`,
   port 8097 because 8080 was held; use a clean port).
2. **Seed one Incident and one Case in the Space** (via a `create-incident` Decision Rule over the API, as the proxy
   did) so task 5 does not dead-end on an empty Space. State openly in the notes that this is seeded.
3. Reset the `findings-spec` for the Space to the built-in (delete any custom component) so every participant starts
   from the same four fields.
4. A second person as note-taker; screen + audio recording with the participant's consent; no real customer data on
   screen (DATA-GOV-1).

**Opening (read aloud, do not demonstrate).** "You are the lead of a fraud desk. Your team writes down what they
find when they close a Case. We are testing the product, not you. Please say out loud whatever you are thinking,
including what you expect a button to do before you click it. I will not help unless you ask to stop. There are
no wrong answers." Ask: role, how long on a desk, whether they have ever changed what the team records.

**Tasks (verbatim wording to read; the facilitator never says field, dialog, key, or Findings spec).**

| Task | Read aloud | Pass (all unaided) | Fail signals to log |
|---|---|---|---|
| T2 Add | "Your team never records how much money was recovered. Add a way to record that, as a number." | Opens the editor from Case Manager, adds a numeric field named by them, marks it required if asked "must it always be filled", sees it in the preview | Cannot find the editor; types over the wrong box; confuses name and label; gives up or asks for help |
| T6 Conditional | "Your team also wants a free-text note, but only when the Case was written off." | Adds a text field; sets it to show only for the right answer of the choice field; verifies in the preview that it appears and disappears | Cannot find "show only when"; reads the condition word wrongly; sets it on the wrong field |
| T7 Preview | "Before you save, show me what your team will see." | Uses the preview without being told it exists; chooses the answer that triggers the note | Saves first and checks on a real Case; does not notice the preview |
| T5 Remove | "Last year's field 'Root cause' is no longer wanted. Remove it. What do you think happens to Cases that already have an answer?" | Removes it; reads the warning (if any) and says correctly that old answers stay stored or are hidden, per the product | Removes it and expects old answers deleted or does not question it; cannot find the control; unclear whether it was saved |

Run order is T2, T6, T7, T5 (the design's order). After T7, have them save, then open a real Case and fill the
form once as the check that "what I designed is what my team sees". Optional T1 and T8 only if time remains.
Then the view-only user: "Sign in as your colleague, who is not a lead. What can they do here?"

**Facilitator rules.** Silence for 20 s before any prompt; only "What are you thinking?" / "What did you expect
to happen?"; log every click and every verbal "I expected X"; time each task; stop a task at 5 minutes and mark it
FAIL with the reason; read the browser console after the session for errors (count click events before filing a
dead-button defect: the viewport-emulation trap in project memory).

**After the session.** Ask: what was confusing, what would you change, would you use this. Record per task
PASS / PASS-with-help / FAIL, the verbatim quotes, and any new defect as a row in the same objects.md section
(*Real-lead session YYYY-MM-DD*). Compare against the proxy floor: any PASS the proxy gave that the lead failed
is the headline. The row closes when T2, T6, T7, T5 are recorded; a FAIL becomes its own P2 row.

| # | Slice | Size | Class |
|---|---|---|---|
| S1 | Seed script + checklist (the setup above) as a doc under `docs/ops/` or inside `objects.md` | S | SAFE |
| S2 | Run the session | S (human time) | DECISION (D14): a real lead must be found; an agent cannot do this |

## 9. OPEN DECISIONS (the operator interview list)

Each has options and a recommendation. Titles are the ones to quote.

**D1. Per-member output naming: default ON, opt-in, or never.**
(a) default ON for every multi-file batch (cleanest, changes every existing multi-file output name on next write);
(b) opt-in `processing.output_per_member`, default OFF, flip later; (c) do not build, keep the documented
at-least-once contract. *Recommend (b), then flip the default after one release.* Nothing after 3.x is in
production (project memory), so the compatibility cost is low and (a) is acceptable if the operator prefers one
behaviour.

**D2. Many small output files per partition: compact or accept.**
A batch of N files writes N files per partition instead of 1. (a) accept; (b) let `PartitionCompactor` merge them
later (an existing component); (c) cap batch size. *Recommend (b) and measure on a 50-file batch before flipping D1's default.*

**D3. Same-stem different-directory members.**
Two members `a/x.csv` and `b/x.csv` collide on `x_out`. (a) fail the second member closed (quarantine with a
reason); (b) disambiguate the stem with a short hash of the origin path. *Recommend (a): one code path, one error
code, and a loud failure beats a renamed file nobody can predict.*

**D4. Stage the work: collision guard first.**
Land slice 2 (collision guard) before D1's change so single-file batches are protected today? *Recommend yes.*

**D5. Parsing residuals worth building: `ASN1-DERIVE-NOT-IN-GRAMMAR` and a surfaced counters channel.**
(a) both now; (b) the grammar walk only; (c) neither, log-only. *Recommend (c) for the counters (they widen the
`RecordSink` SPI for INFO-level data) and (b) deferred until a profile authoring UI exists; build D9 slices 2 and 3
first.*

**D6. `group_by` in the `derive` vocabulary.**
(a) remove the key (it does nothing; the closed vocabulary then tells the truth); (b) implement it as per-group
output (that is `children` explode, so redundant). *Recommend (a); breaking changes are free here.*

**D7. Mask classified entity keys in `/bi/query` result rows.**
A prerequisite for the anomaly top-N tile and a general leak surface (a Dataset with a classified column returns it
raw to any viewer). (a) mask in `/bi/query` and `/queries/{id}/run` unless the caller holds the reveal capability;
(b) keep unmasked, never put entity keys in templates; (c) mask only for Datasets flagged as scores outputs.
*Recommend (a) but scoped as its own gated, reviewed row (it changes every BI consumer: dashboards, reports,
shares), not a slice of this one. Until then the tile stays out.*

**D8. Pack golden policy.**
(a) per-pack multi-week synthetic corpus with exact expected scores (drift-pinned, regenerate on any model edit);
(b) per-pack corpus asserting properties only (a known anomalous day is elevated, a normal day is normal).
*Recommend (b) for packs (stable under tuning), keeping exact goldens in the module's own corpora.*

**D9. Hour buckets: build now, later, or never.**
(a) build `bucket: hour` with hour and weekday_hour seasonality (L); (b) defer until a pack or customer needs
sub-day scoring; (c) refuse permanently and say so. *Recommend (b): the evaluator is day-shaped end to end, nothing
in the shipped packs needs it, and it carries the D-AD9 performance re-measure.*

**D10. Does a Measure become a server concept.**
(a) yes, a `measure` component kind (Dataset-scoped, validated, versioned) consumed by BI, Alert Rules and Risk
Scores; (b) no, keep Measures as `{agg, field}` pairs and expressions as Dataset calculated columns. *Recommend
(b) now; it is a studio-wide product decision (studio.md item 4) and the calculated-column route already covers
Risk Score arithmetic. Revisit only if a customer needs one named Measure in three places.*

**D11. Arithmetic inside a Risk Score factor.**
(a) build it behind `ExpressionGuard`; (b) no, the Dataset calculated column is the supported way (document it, slice R1).
*Recommend (b).*

**D12. Cross-Space comparison without a live Subject (scheduled, authored, or every Space).**
(a) never, keep it an on-demand, own-Space, `canAdminister` action; (b) a persisted grant object (grantor, Space
list, expiry, revoke, audit) enabling scheduled comparisons; (c) a server-wide "compare all" mode for the platform
administrator only. *Recommend (a) until a named customer asks; if built, (b) and not (c).*

**D13. Persisting comparison rows.**
(a) no, Signal ledger only; (b) persist into the requesting Space with the retention of Signals; (c) a platform-level
store. *Recommend (a); revisit with D12.*

**D14. The real Case-desk lead.**
Who runs it, with whom, and when; and file `FINDINGS-PER-CASE-TYPE-1` as a board row before the session (the lead
will hit it first). (a) the operator recruits a lead from a pilot desk; (b) the session is run internally with a
non-engineer colleague as an interim (explicitly labelled interim). *Recommend (a); do (b) only if (a) slips more
than a month, and say so in `objects.md`.*

## 10. Recommended build order

Safe items an agent can start immediately, in this order, one lane each (unit tests per change, no full reactor):

1. **Parsing slice 3 (P2)** then **P3 preview** (S): UI only plus the preview seam; approved; no gate change.
2. **Parsing slice 2 (P1)** (M): approved; the heaviest safe item; independent of the others.
3. **Space comparison C1 and C2** (S each): axes list and the typed 202; one lane, one openapi mirror update.
4. **Collision guard (INGEST slice 2)** (S): protects single-file batches today.
5. **Anomaly B1** (M): the synthetic pack golden for telco-fraud, then B2 if D8 is answered.
6. **Risk Score R1** (S): the doc and help-text workaround.
7. **Bundle E1** and **D6 S1** (browser pass + session setup) together on one demo build.

Needs the operator first (in this order of leverage): **D7** (unblocks the anomaly tile and fixes a general leak),
**D1-D3** (unblocks the multi-file batch fix, then build slices 1, 3, 4), **D14** (schedules the lead session),
then D8, D5, D6, D9-D13 (each a yes/no that mostly resolves to "not now").

## 11. Stale, already done, or no longer true

* **`ANOMALY-DETECTION-RESIDUALS-1` (a) wording**: "the Widget layer lacks a Widget-level filter and a table row
  limit" conflates the wire and the Widget model: `MeasureCompiler.Spec` already carries `filters` and `limit`.
  What is missing is the Widget configuration and the BI masking.
* **Space comparison, "the 202 body / `Location` are not described in the OpenAPI"**: partly stale; the route
  description names them and a `default` response notes the 202 fields. Only a typed 202 schema is missing.
* **`docs/INDEX.md` row for `parsing-repeated-child-design.md`**: says "awaiting operator approval, not built";
  the design says APPROVED with slice 1 BUILT.
* **D6 proxy findings**: four of the five follow-up defects are closed (see section 8); only
  `FINDINGS-PER-CASE-TYPE-1` is not tracked as a row.
* **`Bundle / Exchange` row**: all build slices are done; only a verification pass remains. It is a candidate to
  close as "verified" rather than carry as a build row.
* **Not verifiable here**: see the report; no test, build, browser, or data run was possible on this lane.

## 9a. Decisions taken 2026-10-12 (operator interview)

* **D1-D4** multi-file batch re-plan duplicate: stage 1 = the same-stem collision guard (fail the second member closed); stage 2 = an opt-in `processing.output_per_member` (default OFF) with `PartitionCompactor` merging the many small files, a 50-file measurement, then flip the default after one release. Stage 1 builds now; stage 2 is designed, not built.
* **D5** Parsing: build D9 slices 2 (`children` explode) and 3 (drawer display) and the derived-columns-in-preview slice; the counters channel stays log-only; the grammar-walk error code is deferred. **D6** remove the no-op `group_by` key from the `derive` vocabulary.
* **D7** mask classified entity keys in `/bi/query` and `/queries/{id}/run` result rows unless the caller holds the reveal capability, as its OWN gated, reviewed row (it changes every BI consumer); the anomaly top-N tile stays out until then.
* **D8** pack goldens assert properties; exact goldens stay in the module's own corpora. **D9** hour buckets deferred until a pack or customer needs sub-day scoring.
* **D10** Measures stay `{agg, field}` pairs (no server `measure` kind). **D11** no free-form arithmetic in a Risk Score factor; the supported way is a Dataset calculated column (documented). **D12/D13** Space comparison: only the two API-hygiene slices (serve the axes list, typed 202); no scheduled/authored cross-Space comparison and no persisted comparison rows.
* **D14** the D6 think-aloud session is prepared (script in `ui/d6-think-aloud-script.md`); running it needs a real Case-desk lead.

## References

`docs/BACKLOG.md` (the seven rows); `okf/backend/engine/ingestion.md`; `okf/backend/engine/parser-plugins.md`;
`superpower/parsing-repeated-child-design.md`; `okf/backend/control-plane/anomaly-scores.md`;
`okf/backend/control-plane/risk-scores.md`; `okf/capabilities/studio/studio.md`;
`okf/backend/control-plane/metadata-bundle.md`; `okf/backend/control-plane/jobs.md`;
`okf/frontend/features/objects.md`; `archived-documents/plans-archive/findings-spec-authoring-ui-design.md`;
`archived-documents/plans-archive/space-comparison-design.md`.
