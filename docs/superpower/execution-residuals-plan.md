# Execution residuals — solution specification (stage-2 close-out)

**Status: ACTIVE (X-items); the build package SHIPPED 2026-09-02** — the default-on orphan gate
(`JobService.auditOrphanOutputStores`, transition-debounced, `-Djobs.orphan.audit` kill switch),
the D-9 remainder (wave row 14 COMPLETE: `RowShaper.ExecutionContext` seam · `scope:` parse ·
`ERR/WARN_DEDUP_WINDOW_UNARMABLE` at all four save gates + bundle import · retract-on-supersede ·
`dedup_prune` maintenance task · both contracts regenerated), and `POST /jobs/runs/{runId}/replay`
(`replay:<runId>` linkage; run ledger persists no params — replay = documented re-trigger with
configured defaults). As-built: `execution-lanes.md` (recovery-per-lane block),
`stage1-architecture.md` §Step 3, `db-layer.md` §3.11. ⚠ Known accepted behavior: a dry-run of a
windowed dedup REFUSES (NONE context, fail-loud by design) — a read-only preview context is a
possible follow-up, filed under X5's envelope thinking. Companion to
[`authoring-residuals-plan.md`](authoring-residuals-plan.md); this plan owns what was SPEC'D
rather than built. Prior
art: the DataForge wiki (`C:\sandbox\incubetor\docs\wiki` modules 02/03/08 — uncompiled design
spec; its threading doc uses a superseded preview API, so treat every mechanism as unproven).
Board of record for open execution rows stays `BACKLOG.md`; this plan is the solution shape.

**Shared precondition, named once (the "analyses nothing" trap):** provenance
(`-Dprovenance.backend`), `file_stages`, and the acquisition ledger all default off/memory — any
verification built on their counts (conservation, completeness K2, D-9 audits) reports clean on a
stock deployment while measuring nothing. Every item below that consumes those stores must state
its default-off behavior explicitly; flipping any default is an operator decision.

---

## X1. Persistent retry queue (the recovery architecture)

**Problem.** Recovery is binary today: crash-idempotent commits + manual whole-Consignment
`reprocess` (ingest) / `drain` (parked) / `replay` (at-rest runs, shipped this shift). Transient
failures (remote hiccup, DB lock) retry only by the next poll cycle re-encountering the work;
nothing distinguishes transient from fatal at the throw site.

**Solution (wiki-informed, adapted).**
- Classification at the throw site: transient (`IOException` timeouts, 503s, DuckDB lock) AND
  `attempts < max` → a durable `retry_queue` row; fatal (parse/schema) OR exhausted → today's
  quarantine, unchanged. **Poison never drops silently — exhaustion hands off to quarantine with
  an alert** (the wiki's one non-negotiable).
- Durability: one table beside the status store (DuckDB), keyed `retry_id`, FK consignment;
  `next_retry_at`, per-row `attempt/max/backoff`, `last_error_type`, status ∈
  PENDING_RETRY/EXECUTING/MAX_RETRIES_EXCEEDED/CANCELLED; ONE index `(status, next_retry_at)` —
  the poller is a single range scan. Backoff `min(initial × mult^(n−1), max) ± 10% jitter`.
- **Granularity decision (must be taken before build): CONSIGNMENT-level, recommended.** The wiki's
  own two docs disagree (its state machine recovers whole-consignment; its queue stores
  `target_step_id` implying mid-DAG resume). Inspecto's commit model is `(consignment, branch)`
  with idempotent re-runs — consignment-grain fits it exactly; step-grain requires the StepInfo-
  style envelope (X5) first and stays future. What is persisted is the retry INTENT (ids + params,
  ~the replay route's linkage), never data.
- Restart: reload `PENDING_RETRY`, re-arm timers ("zero lost retries across restarts"). Add the
  heartbeat/lease column the wiki's own DDL forgot — the orphan-rewind recovery depends on it and
  it is retrofit-expensive.
- Operator surface: rows visible on the Runs pane; cancel; the exhaustion alert.
- ⛔ Do NOT adopt the wiki's `remainingStepsCount` barrier (redundant second completion signal) or
  its untyped `Map<String,Object>` step metadata.

## X2. Cross-lane provenance — one Consignment, one trail

**Problem.** The trail breaks at the Stage-1 → Stage-2 boundary: the `pipeline_config:` job's run
is linked to its input consignments by convention (`output_store:` + the store path), not by
recorded identity — an operator cannot ask "which chain runs consumed consignment X".

**Solution.** Record the linkage where it already has a home: the at-rest job's run record (and
`consignment_outputs` rows it writes) carries `derivedFrom: [<source consignment ids>]` — the ids
the `SourceStoreReader` view actually read (derivable from the registry rows / `__batch_id` column
it scans; ground the cheapest source before building). The replay route's `replayOf` linkage
(shipped this shift) is the same shape run-to-run; this is the run-to-consignment half. Surface:
the Run Detail lineage tab follows the link both ways. ⚠ Subject to the shared precondition — the
registry (`consignment_outputs`) is the one default-ON store, which is why it is the carrier.

## X3. PARK-1(a) — manifest park detail, premise re-grounded first

The row predates this shift's read routes. Before designing a NEW route, re-drive the premise
(PARK-1(c)'s closure found a real defect exactly this way): check whether `GET /runs/{name}`'s
manifest projection, `/runs/{name}/outputs`, or the drain 409 bodies now carry enough
(`parkedAt`/`parkedTables`) for the UI. If not: extend the existing run-detail read (never a new
top-level route) with the manifest's park block. PARK-1(b) (unpack-expansion drain) stays
decision-gated on the shared-original lifecycle — not this plan's to take.

## X4. Record-level replay from quarantine (unprioritized — shape only)

Whole-consignment reprocess stays the primary affordance (matches the commit model). If record
grain is ever prioritized: the wiki's quarantine shape is the right prior art — sidecar error
manifests naming the precise offset/reason next to the quarantined file, and the **A/B policy
switch** (all-or-nothing vs eject-and-continue) as per-pipeline CONFIG rather than a fixed rule.
Inspecto already has the B-side behavior for parse rejects (`errors/*_errors.csv` + kept rows);
what would be new is the A-side policy and the replay of an ejected subset. No build without a
driver.

## X5. One-Consignment drill-down + the StepInfo envelope (design sketch, feeds Phase 7)

The wiki's four-route drill-down (list → detail → lineage → remediate) exists in inspecto in
pieces (runs list, Run Detail tabs, provenance counts, signals-by-causationId, reprocess/replay).
The missing piece is the CROSS-LANE view (X2 is its data half). The larger idea worth carrying
into the Phase-7 design: the **StepInfo-style envelope** — steps exchange a ~1KB record carrying a
content POINTER + schema + diagnostics, never rows — is precisely the token model's concrete
shape, and `ProcessorContext` (the post-sync SPI) already implements the read side. Phase-7's
convergence spec should adopt: an immutable per-hop envelope, additive accumulation, bounded
diagnostics (truncate payloads, keep codes/counts), failure routed by PORT not exception. ⛔ This
plan does not schedule Phase 7 — it stays major-bump-window gated (spec §13 D2).

## X6. Consignment identity in artifacts — already held (recorded, no work)

The wiki's "retrofit-expensive" warning (stamp the consignment id into every sink artifact + row)
is **already satisfied here**: outputs carry the materialized `__batch_id` column and the
default-on `consignment_outputs` registry keys every artifact by consignment — which is exactly
what makes reprocess/retire/supersede single-predicate operations. ⚠ The pending rename trio
(BACKLOG §4: DDL column · `__batch_id` output column · `.toon` key) touches this surface; X1/X2
must read those spellings via the existing accessors, never new literals.

## R3 unblock (recorded verdict, 2026-09-01)

MIDBRANCH-1's gate ("the stage-2 execution analysis landing first") was MET by this analysis, and
**R3 SHIPPED 2026-09-02**. ⚠ The boundary this verdict originally named was directionally wrong —
route pipelines never reach `graphLaneCarries` (that predicate serves NON-route pipelines); the
real seam was `ConsignmentGraphRunner.engages`/`dataFedSinkCount`, extended so a sink's feeding
relation traces upstream through chain transforms to its `route:*` edge, and a route-fed chain
always engages the fork. Kept as a worked example: an unblock verdict is itself a hypothesis —
the builder must re-ground the named boundary before extending it. (Platform Services S2-2's
bridge spike interest stands unchanged.)

---

Gated items deliberately NOT here: wave row 15 (release event), the Consignment rename trio
(operator ×3), completeness KPI (⏸ on hold + two owed answers), Phase-7 runtime convergence
(major-bump), full ingest graph vocabulary (`adapter`/`alert`/`event` — own design), EXPORT-1.
