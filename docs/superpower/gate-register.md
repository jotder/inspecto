# Gate register — everything currently gated, blocked or restricted (2026-08-29)

**Status:** discussion input, not an approved plan. Built by a four-way sweep of `docs/BACKLOG.md`,
`docs/superpower/`, the root canon + `compliance/`, and `docs/okf/`. Nothing here is new work — it is
the existing record, deduplicated and sorted by **who can actually lift the gate**.

⚠ Not yet linked from `docs/INDEX.md` — add the line if this file is kept past the discussion.

---

## 0. The headline

Three findings dominate:

1. **A large share of "blocked" is doc-rot, not blockage.** Items recorded as gated whose gate has
   since been removed, answered, or refuted — and nobody went back to the row. These cost nothing to
   unblock and several are auditor- or security-facing. §1.
2. **The real external gates collapse into five clusters, not fifty rows.** One decision per cluster
   unblocks between three and eight items. §2.
3. **The restriction register is healthy and should be left alone.** Write-root gate, PathJail,
   ExpressionGuard, SqlGuard, edition boundaries, air-gap posture, append-only registry semantics —
   each carries an explicit reason. These are design, not debt. §5.

---

## 1. False blockers — the gate is already gone (free to close)

**All verified against the repo, and all now CORRECTED in place (2026-08-29).**

| Item | Recorded as | Reality | Fixed |
|---|---|---|---|
| **Platform Services Stage 2** (BACKLOG §4) | "Hard-gated on the branch-aware executor becoming the armed production path" | ⚠ **My first reading — "gate spent" — was too strong.** The *premise* is spent (executor closed 2026-08-26; Phase 6 A–C2 extended the graph lane to every non-route shape, and the reactor now runs simple pipelines through it). **But the substantive gate survives, transformed.** `BatchIngestStrategy.graphLaneCarries` admits a pipeline only when EVERY sink hangs directly off the `transform.map` seed — *"one straggler behind another node would be executed by the walk, which is new behaviour rather than the same write"*. An `EXECUTED` Step **is** that intervening node. The ELT plan names the identical boundary from the other side: carrying a node between map and sink *"means EXECUTING them at rest, which is Stage-2 work and a different decision"*. ⇒ One boundary, two docs, no cross-reference. Stage 2 needs **that decision**, not the executor. | ✅ row re-grounded |
| **ELT Phase 1 remainder** (schema *structure* CSV shape) | "revisit with Phase 2's type flow" | Phase 2 **fully closed** (P2 S5, 2026-08-06). Unblocked work wearing a spent prerequisite. | ✅ plan corrected |
| **open-dag §5 Q2** (does a chained step see the Consignment as the previous step left it?) | open question | **Answered by §8**: registry re-read per step, falsified by hoisting the read out of the loop. The question outlived its answer by a day. | ✅ marked answered |
| **open-dag §4 stage table** | the staged path | **Superseded by §6.4's renumbering**, which makes §7/§8/§9 read against the wrong stage numbers. | ✅ warning added |
| **ELT Phase 4 S4c/S4d** | plan says "in flight" | `bb7a3225` (S4c) + `5f0d9637` (S4d) shipped — verified as real commits. Two places in the plan said "remain". | ✅ both corrected |
| **G6** (compliance) | "backup/restore runbook missing" | ⚠ **WRONG as written** — `docs/ops/backup-restore-runbook.md` exists. Only an RTO/RPO statement + a recorded drill are owed. | — cluster B |
| **G10 / AUDIT-CSV-1** | open defect | **Fixed 2026-08-28**; the evidence doc contradicted itself for three weeks. | ✅ §6.2 corrected |
| ~~**SOC 2 Type II window**~~ | "dep: RBAC/ABAC R-workstreams" | ⚠ **NOT doc-rot — my row was wrong.** Plan §6 Q6 already says the R-workstreams "are now complete, so this is ready to schedule". Nothing to fix; it is an unscheduled **org action** → cluster C, not a false blocker. | n/a |

### 1b. Documentation that actively contradicts itself

🔴 **`compliance/evidence/audit-log-extraction.md` §6.2** still asserts the CSV-drops-audit-attributes
defect that **§3 of the same file** declares fixed. An auditor reading §6 is told JSON is the only
complete extraction. Auditor-facing; fix first.

Others, lower stakes but each misleads a shift:

- **MOCK-LIFT-1** (BACKLOG §4) — header says CLOSED "found and fixed the same day"; body says the
  naive fix "introduces a WORSE corruption — scoped and **not attempted** for that reason" and then
  prescribes a multi-part design. Treat the sink-model split (lift + lower + round-trip spec) as open.
- **BUILDER-1** (BACKLOG §5) — headed 🔴 while (a)(b)(c) are fixed and (d) refuted. Only the
  three-disagreeing-name-rules observation survives.
- **`consignment-elt-architecture.md`** — its header says §7.5's "no mergeable sketch state" premise is
  unverified; **§7.5 records it VERIFIED 2026-08-28**. BACKLOG §4 repeats the stale version.
- **`REQUIREMENTS.md`** §3.1 (ACQ-4 GCS-native "demand-gated"), §3.9 (SPC-5 `PLANNED`), §3.15 (UI-8
  `IN-FLIGHT`) — each contradicted by `EDITIONS.md`, `INDEX.md` or §5 of the same file. The MoSCoW
  status columns are behind the narrative.
- ~~**SEC-INCIDENT-1** — §1 vs §5 phrasing~~ — moot; closed by decommission (§2 cluster A).

### 1c. Doc-lifecycle violations (shipped work still in `docs/superpower/`)

`living-operational-system.md` (R1–R6 all shipped) · `geo-map-case-studies.md` (CS1–CS5 built and
pinned) · `parser-plugin-framework.md` (P1–P4 on disk, plan carries **no as-built annotation at all**)
· `compliance-certifications-plan.md` C2 (delivered; its home is now `compliance/controls-matrix.md`)
· `agt-6-plan.md` (borderline — residue is one host row + one cosmetic defect + a parked `Could`).

~~`4x-public-pkce-plan.md`~~ — **archived 2026-08-29** (cluster A).

---

## 2. The real external gates, as five clusters

### Cluster A — ~~the leaked secrets~~ CLOSED BY DECOMMISSION (operator, 2026-08-29)
**SEC-INCIDENT-1** · `4x-pkce` P2 / §4 Q2 / Q3 / Q4 · `controls-matrix` CC6.1

**The system no longer exists**, so rotation at the issuer is moot and this is not a gate. Operator
call, 2026-08-29.

**Applied 2026-08-29** — BACKLOG §1 priority-0 removed and §5 rewritten as a closed record;
`controls-matrix.md` CC6.1 closed with its reason rather than erased (the exposure is independently
discoverable, so a silent matrix reads worse than a disclosed one); `4x-public-pkce-plan.md` and the two
incident-only runbooks archived; `INDEX.md` updated. `BRANCHING.md` and `PROJECT_NOTES.md` keep it as the
worked example of why the pre-push secrets guard exists — the lesson outlives the system.

Two things deliberately carried forward rather than deleted:
- 🔴 **The pre-rewrite backup bundle is now due for deletion.** It holds all five secrets in cleartext
  and was retained by operator call 2026-07-27 *only while the incident was open*. That condition has
  lapsed. It lives outside the repo, so no shift can close it from the checkout.
- ⚠ **The leaked values remain in public git history and `refs/pull/*`.** Immaterial only if none of the
  five was reused against another system. One confirmation, not a workstream.

### Cluster B — no live deployment exists
**OPS-5** · `deployment-topology` T2/T3/T4 + D8 + GAP-7 · **G6** drill + RTO/RPO targets · grammar-config live smoke

Every one of these is waiting on the same thing: a reference deployment. The provenance-conservation
protocol is written and signed and has only ever run against synthetic data; the RTO/RPO statement
carries literal `<OPERATOR TO STATE>` placeholders and its drill table has **zero rows** ("a target
with no drill behind it is the document an auditor disproves first"); the IAM reference pair is
unvalidated. **One action unblocks the cluster.**

### Cluster C — four short org answers unblock repo work
| Q | Ask | Blocks |
|---|---|---|
| §6 Q2 | SBOM format — CycloneDX or SPDX? (CycloneDX recommended) | **G1**. ⚠ Must be generated **per packaged bundle**, not from the reactor — the reactor resolves 94 third-party artifacts dominated by the *optional* AI stack, so a reactor SBOM "attests a set no customer installs". `tools/dependencies.lock` is a review baseline, **not** an SBOM. |
| §6 Q3 | Where does the GPG release key live? | **G3** — signing is *possible* (`package.ps1 -Sign`) but not *routine*, so a release can ship unsigned. |
| — | What audit-retention window? | **G5** — the Parquet event store is **unbounded**. "Never a default to invent." Once stated, a shift builds the partition-delete prune task. |
| §6 Q4/Q5/Q7 | HIPAA/PCI demand? · hosted SaaS ever? · FedRAMP Moderate or Low? | C1 scope, whether FedRAMP becomes a real authorization program, baseline assumption |

Also org-only, no repo path: C1 applicability statements (⛔ "do not generate them from this table"),
ISMS boundary, auditor engagement, pen test, incident-response and crypto policy content.

### Cluster D — cross-repo (eoiagent)
**AGT-6b** blocked by two upstream items: the approval gate is **synchronous per-call** so nesting
gated calls deadlocks, and there is **no per-tool `DryRunProvider` seam** — reclassified from
"low-priority refactor" to a stated **prerequisite**, because per-step preview is what makes a
model-composed graph approvable at all. Plus **EOI-7b** (publish `0.1.0` to a registry — an infra
call) and **AGT-5 `incident_explain`**.

### Cluster E — needs a real user, not a teammate
**D13 parser field tiers.** The session kit is **READY TO RUN** (2026-08-28); the gate is scheduling a
real onboarding observation. Explicitly *not* an engineering guess. ⚠ Grounded correction already
recorded: every `tier:'required'` field carries `required: false` validators, so "required" today
means *visible in the top tier*, never *enforced*.

Related and unresolved: **DATA-GOV-1** — ~57 MB of real carrier data lives only in each shift's
working tree, so parity runs are unreproducible on a fresh checkout, and there is "no sanctioned way
to obtain it".

---

## 3. Operator decisions — cheap calls that unblock work

Ordered by what they unblock.

1. **`open-dag` §9 Q1 — is a comma-separated `processor` string the authoring surface?** Blocks the
   editor surface (§6.4 stage 4). The string is the smallest thing that works with the existing
   `ParameterDecl` vocabulary (there is no `LIST` `ParamType`) and is honest about ordering, but it
   **carries no per-step configuration** — a step needing parameters has nowhere to put them.
2. **`open-dag` §9 Q2 — should a mid-chain failure retire what earlier steps wrote?** Today: no
   (append-only, "a registered table is a fact"). The alternative is expressible with existing state
   but changes that contract to "a fact only if its chain finished".
3. **Consignment rename trio** (ELT Phase 7 / D-12) — three *different* classes of decision, gated
   separately on purpose: `batch_id` DDL columns (a real `ALTER TABLE` migration incl. a `payload`
   blob literal) · `__batch_id` data-plane column (**a user-visible output-schema break**;
   accept-both-on-read is impossible) · the `.toon` config key (an operator **config-key contract**
   break).
4. **PARK-1 (a)** no manifest-level park detail route · **(b)** expansion-member batches are refused
   rather than drained (the shared archive original can't move without stranding sibling batches).
5. **Three-disagreeing-name-rules** — `id` is slugged, `path` and every `dirs.*` keep the raw spaced
   name. ⛔ Don't widen the pattern without checking the third rule (filename derivation).
6. **`mode: clone` arming** — the row's own stated reason is ⚠ stale by its own admission; the real
   gate is that **nothing surfaces partial-commit state to an operator**, which is a product call.
7. **`deployment-topology` D1–D8** — whole plan is DRAFT pending stakeholder sign-off; "nothing here
   is schedulable until those are answered", including Phases 0–1 that look like plain build work.
8. Smaller: delimited-robustness tri-state knobs · what `description` on the onboarding dialog is FOR
   · which stage owns `processing.duplicate_check.*` · blank-`output_table` → Catalog link · D8
   auto-disable/suppression blast radius · SAMPLE-1 retired seed arms + corpora.

---

## 4. Unblocked engineering — nothing gates these, they are simply unbuilt

**Defects and correctness first:**
- ~~🔴 `DbAcquisitionLedger.record()` DELETE+INSERT atomicity~~ — ⚠ **THIS ROW WAS WRONG. Already fixed
  2026-08-15** (postgres plan F1, "P0 shipped"). Verified in code 2026-08-29: `record()` sets
  `autoCommit(false)`, runs both statements, commits, rolls back on failure and restores autocommit in a
  `finally`; pinned by `AcquisitionLedgerTest.aFailedReplaceRollsBackAndKeepsThePriorFingerprint`. The
  sweep quoted F1's original finding text and missed its struck-through FIXED annotation — a caution for
  reading that plan, whose findings are annotated in place rather than rewritten. What genuinely remains
  is **F2** (`browseConnection()` hands out the store's long-lived connection; a pool has no such thing),
  and F1 was its prerequisite.
- ~~🔴 **GAP-1 bind-all**~~ — **CLOSED 2026-08-29.** `-Dcontrol.bind` now ships on both transports and
  fails the boot on an unresolvable value. ⚠ The **default is deliberately unchanged** (every
  interface) — narrowing it would strand deployed Standard/Enterprise installs on upgrade — so
  `EDITIONS.md` now states the exposure instead of denying it, which was the actual defect.
- ~~🔴 **Torn multi-file reads across a recompute** — open defect; subtraction fixed stale inclusion,
  not tearing.~~ **SHIPPED 2026-08-29** — `ConsignmentSelector` now always pins the enumerated file list
  to an explicit array once a registry exists, rather than falling back to a live glob string DuckDB
  re-expands at scan time. Accepted tradeoff: the SQL always carries a file array now, and a pinned
  list can fail loudly (not silently) if `retire_superseded` deletes one of its files mid-read. See
  `consignment-addressing.md` §3.
- ~~⚠ **`retire_superseded` must be configured by an operator** or every full recompute leaves a
  permanent extra copy on disk. No default exists, deliberately — but nothing surfaces the cost.~~
  **SHIPPED 2026-08-29** — `PipelineJobRunner.supersedeEarlierRevisions` now warns, naming the affected
  store(s), the moment a recompute actually supersedes something with no enabled `retire_superseded` job
  configured (checked live off `configSnapshot()`, not cached). The default itself is unchanged and stays
  deliberately absent — only the silence is gone. See `operations-reference.md` and
  `consignment-addressing.md`.
- ~~**`sql-only` §6 step 1** — `EXPR` drops out of the cast-failure audit **silently**.~~ **SHIPPED
  2026-08-29** (`9f14a960`) — `MappingRules.validate` now emits a WARNING `Finding` for every `EXPR`
  rule. ⚠ The load-bearing half wasn't the warning: `ComponentRoutes`' preview `clean` field and its
  save gate both treated "any finding" as unclean and would have silently blocked every `EXPR` save the
  moment it stopped being finding-free — both now key off `Severity.ERROR` specifically.

**Then the substantial builds:**
- ~~`open-dag` **stage 5** — open `RecipeCompiler`'s closed 8-verb switch + palette `authorable`.~~
  **SHIPPED 2026-08-29** (`4dc63079`, before this register was written) — the premise itself was wrong
  (`RecipeCompiler` has no production caller); what shipped instead is a contributed node type lowering
  to a `steps:` entry via `PipelineLift`. See `open-dag-pipeline-design.md` §11. Its own two follow-on
  §9 questions (per-step chain config; mid-chain-failure semantics) are also now decided and shipped
  (`6bf92b1b`, 2026-08-29): `chain_config` JSON parameter + `ProcessorContext.config()`; failure stays
  append-only, no code needed.
- `open-dag` **stage 4** — the editor surface. ⚠ **This row was half-stale (checked 2026-08-30):**
  the design's §6.4 already marks stage 4 SHIPPED (`aa777782`), but what shipped is the **read-only**
  registered-outputs list in Batch detail — *authoring* the post-sync chain is still hand-edited TOON.
  There is also no "decision 3.1" anywhere in `open-dag-pipeline-design.md`; §5 Q1 is closed and §9's
  two decisions shipped, so nothing is decision-gated here. 🔴 Grounded gap: `chain_config` ships as
  `ParamType.JSON` (published, tier ADVANCED) and the UI had **no `json` widget**.
  ~~`widgetFor()` and `schema-form`'s `@switch` both fall through to a bare single-line text box.~~
  **AUTHORING HALF SHIPPED 2026-08-30** — `JSON` maps to the `multiline` widget (JSON travels as TEXT:
  `chainConfigsOf` parses a **String** off the wire) plus `jsonParameterValidator` via the existing
  `[extraValidators]` seam. ⛔ **Deliberately NOT a new `AttributeType`**: that would drag in
  `NodeAttribute.TYPES`, `FindingsSpec.TYPES` and `attribute-spec.contract.json` — the whole node
  vocabulary — for one parameter, when `multiline` is already the right control.
  🔴 **Driving it in the preview found a defect no unit test would have**: `chain_config` is ADVANCED,
  and `validate()` marked controls touched without OPENING the collapsed section, so the refusal
  rendered nowhere and Continue silently did nothing. That was never JSON-specific — every validator on
  an optional/advanced field had it. `validate()` now opens the section holding an invalid control.
  ⚠ The mock served **no `consignment.process` type at all**, so this could not be rehearsed offline;
  it now mirrors the real descriptor.
  **Still open (the real stage-4 remainder):** an ordered **chain-authoring surface** — today the chain
  is a comma-separated `processor` string and a positionally-aligned JSON array the author must keep
  aligned by hand. That is a UX design question, not a gap to fill by reflex.
- ~~`open-dag` **stage 6** — parser output-schema publication.~~ **REFUTED 2026-08-29** (before this
  register was written) — the seam already exists and is already wired end to end (`ParserPlugin
  .preview()`'s `columnTypes`, forwarded unconditionally by `POST /parsers/{id}/preview`). Nothing to
  build. See `open-dag-pipeline-design.md` §12.
- ~~**Parsing Stage-1 (b)** tree→segments ingest bridge — "**THE** gating slice"; without it
  hierarchical parsers (XML, `ingestable:false`) are preview-only and the UI says so.~~
  **SHIPPED 2026-08-30** — `com.gamma.ingester.XmlRecordIngester`, XML now `ingestable: true`.
  🔴 There was never a structural blocker: `Parsers.ingestable()` is a DISPLAY flag derived from
  `ingesterClass()`, and no config/validation/dispatch path ever consulted it — XML was preview-only
  purely because no ingester existed. The load-bearing decision was that preview and ingest share ONE
  StAX walker (`XmlRecordReader`): an operator authors selectors against the labels the preview shows,
  so a second walker would resolve them to `NULL` at load while the preview still looked right.
  ⚠ XML's grammar keys moved `xml.*` → `ingester_config.*` (one spelling for preview and load; no
  operator config used the old one). ⚠ The UI needed NO change — every gate reads the served flags.
  See `okf/backend/engine/parser-plugins.md`.
- **W0 lossless lift↔lower proof** — "a hard gate": prove lossless over the 16 configs, or NAME the
  supported subset, before W4/W5.
- **Consignment** §8 end-of-period summary pass (the only remaining dependency of anything already
  built; representation already decided as a fixed-bucket histogram) · §11.4 `partition_state`.
- **ELT** D-9 windowed keyed dedup ledger (⛔ "never faked with unbounded history") · D-8 XLSX export
  · branch-commit-log housekeeping.
- **step-workbench S5** — `TypeFlow` behind a read route; "the one that ends the restating".
- **G1 SBOM** per bundle, once Q2 is answered · **AU-9** audit-record protection (⛔ "do not assert
  immutability the storage layer does not enforce") · ISO 8.8 advisory-watch process · ISO 8.12
  air-gap writeup ("a genuine differentiator; write it up").
- **agt-6 `kpi_report_builder` host** — it has **no viable host pane**; a new flow, not an adoption.
  This single row is what keeps that plan out of the archive.

---

## 5. Working as designed — do not "resolve" these

Deliberate restrictions, each with a stated reason. Listed so they are never mistaken for debt.

**Fail-closed gates:** write-root 503 (separate from auth, and stays) → ConfigSafetyValidator 422 →
PathJail 403 (UNC paths rejected by design) → 409 conflict. `ExpressionGuard` closed token alphabet
(kills subquery smuggling and `read_parquet`/UDFs). `SqlGuard` + DuckDB sandbox. BI share tokens inert
without `-Dbi.share.secret`. Server refuses to delete an active pipeline. Incident resolution
hard-gated backend-side.

**Boundaries:** editions are **build flavors, never branches**; Personal is auth-free and localhost;
core carries no `if (edition == …)`. Identity is delegated to the IdP — so AuditTrail deliberately
does **not** capture auth events (⚠ must be *disclosed* to an auditor, not left to be found). Air-gap /
no-egress enforced by CI over sources **and** the resolved dependency tree.

**Semantics:** append-only registry ("a registered table is a fact") · manifest owns *existence*, the
catalog only *state* · nothing prunes by default (no hidden built-in cron) · `-Djobs.maxConcurrentRuns`
is the only bound, and intake is deliberately **not** the throttle input ("capping intake raises lag,
so throttling on it is positive feedback").

**Refusals that are end states, not deferrals:** Spring/Quarkus migration (the framework-free core is
"a deliberate compliance asset") · distributed-by-default · per-record lineage/replay · Lens as a
permission · PIP-1 pipeline nesting · sink-owned `partitions` knob · EXPR-1 blanket `$` interpolation
· Decision-Rule routing combined with `route:` branches (permanent posture — "one version history is
ill-defined across branches") · widening `ProcessorContext` to a raw `Connection` · `CREATE MACRO` UDF
surface · step-workbench S3 (refuted after a grid was built and reverted).

---

## 6. Two structural notes for whoever reads the plans next

**`open-dag-pipeline-design.md` carries two stage numberings.** §4's table (1 decide · 2 chain ·
3 editor · 4 recipe verb · 5 parser schema) is **superseded by §6.4** (1 ~~decide~~ · 2 derived-table
emitter · 3 ordered chain · 4 editor · 5 recipe verb · 6 parser schema). Consequently §7 "Stage 2
as-built" describes a write seam with **no counterpart in §4's table at all**, §8 "Stage 3 as-built"
describes what §4 calls stage 2, and §9's "before stage 4" means the **editor**, not the recipe verb.
Read §4 only for its ordering rationale, which is itself now spent.

**The `okf/` tier is a constraint register, not a backlog.** Its ~45 invariants and ~20 traps are the
rules future work must not break — e.g. every `writeAndTrace` caller must declare a write scope; the
UI must offer the Step switch on the `route:<key>` relation and never the lift's `sink__d<i>` spelling;
`supersedeOtherRevisions` is full-recompute-only and `keep` is required; a stale
`branch_commit_<batchId>.log` in a shared `%TEMP%` makes a batch write **nothing**. Several are
recorded precisely because the repo has already paid for violating them.
