# Backlog — every OPEN item, one page

**Updated:** 2026-09-07 — §4–§7 re-grounded and drained (see each section's note).
**2026-09-06 — consolidated.** The previous page (505 KB, 3,288 lines, roughly half of its rows
already closed) is frozen as
[`archived-documents/backlog-snapshot-2026-09-06.md`](archived-documents/backlog-snapshot-2026-09-06.md);
every closed row's as-built narrative, commit SHAs and refuted premises live there and in git history.
That rewrite also folded in the open items that had been living outside this page: the gate register's
pending decisions and "simply unbuilt" list (§3/§4, 2026-08-29 — that register was itself archived
2026-09-07, its retirement trigger having fired), the remainders of every plan still in
`docs/superpower/`, and the last handoff's next steps.

**What this page is.** The single board of open work, grouped by what has to happen next. Each row is
one line of *what remains* plus a pointer to the document that owns the detail. It lists **open work
only** — nothing here is done.

**Rules of use.**
- A row's stated cause and severity are a **hypothesis** recorded when the row was filed. Ground it in the
  code before building; the archived snapshot records how often that grounding overturned a row.
- When an item ships: mark it in its *source* doc first (that stays authoritative), then **delete the row
  here**. No strikethrough as-built narrative — that is what the OKF concept docs and git history are for.
- New pending items discovered mid-shift get a row at handoff time (see the `handoff` skill). Keep the row
  to one line of remaining work; the detail goes to the owning OKF concept or plan.
- ⛔ marks a refusal or a precondition that must not be bypassed. 🔴 marks a live defect or a trap.
- §6 lists standing refusals so nobody re-files them. They are not work.

---

## 0. Priority order

**P1** = do next: a live defect, or decided work with user-visible value. **P2** = build after P1, or once its §1
decision lands. **P3** = demand-gated; build only when someone asks by name. Every §3/§4 row carries its rank.

**P1 is drained (2026-09-06).** The shift that consolidated this page took every P1 row: three were built
(join-reference and schema-drift refusals at save, the audit over every input, the CI examples smoke), three
were already shipped and only the row was stale (AUTHORING-WIDE-1, the torn multi-file read, the auditor
evidence note), and four were re-ranked because the row hid a gate — a design pass, an operator decision, or
a new dependency — not a build. The rule that fell out: **a P1 must name the file it changes.** A row that
cannot is a decision (§1) or a design (P2).

Do next, in order (refreshed 2026-09-10 — **no P1 is queued**: `MAPPING-GEN-1` shipped the day it was decided; `SBOM-RESOLVE-1`, GUARD-SWEEP-1 and DAT-6-CI-1 done):
1. ~~**`SBOM-RESOLVE-1` (§4)**~~ ✅ **SHIPPED 2026-09-09** — `release.yml` now installs the reactor under `-Pedition-enterprise` before the packaging steps. Was the only queued P1. The bill of materials cannot generate on a clean
   runner, so the first tag fails at packaging; it names the file it changes (`release.yml`). Filed
   2026-09-09 while fixing the generator's module table, and it is **not** a regression from that fix —
   Enterprise failed identically before it. Everything else below was already drained: MERGE-ATTRS-1,
   filed hours earlier on 2026-09-07, was **refuted on grounding**
   within one shift of being written and moved to §6 — `transform.merge` is deliberately not authorable, so
   declaring attributes for it would give a config pane to a node that refuses to save. Pick from §3/§5 by
   rank, or take the two §2 rows whose first action is **not** external (the SOC-2 window start date and
   the completeness-KPI query).
2. **Release notes for the next MAJOR** — keep appending (§2).
3. **Step Processor catalog** — pick a partial by name (§3).

⚠ **`NAME-DIRS-1` was deleted unbuilt on 2026-09-07: it had already shipped** in `70473c94` the day
before ("dirs from the slug"), implemented AND pinned by a spec asserting no space, dot or dash reaches
any dir. It was filed because `pipeline-identity.md` cited a §3 row that did not exist — the *row* was
missing, the *work* was not. ⛔ A decision that "unblocks" a row does not mean the row is open: grep the
code before filing, not just the board.

## 1. Operator decisions pending

*(2026-09-10, later: `CONSUMER-PAIRS-1` decided **per row, in one sitting** — seven ADOPT, one KEEP-as-API, two RETIRE; verdicts in each owning spec's §2, work grouped into five §3 rows: `CLIENT-HALVES-1`, `EXPECTATIONS-UI-1`, `STUDIO-HALVES-1`, `AGT-ARTIFACT-1`, `RETIRE-HALVES-1`. **§1 is now EMPTY** — the first time since the board was consolidated.)*

*(2026-09-10: six decisions closed in one sitting — `MAPPING-SPELLING-1` both halves → §3 `MAPPING-GEN-1` + §6; OpenAPI posture → §4 `OPENAPI-GEN-1`; Enterprise self-identification → §3 `STANDARD-BUNDLE-1`; `AGT-SEGMENT-1` keeps its caveat with a named trigger; drift refusal → §6; `CONTRACT-ORPHAN-1` was moot — its "no producer, no consumer" premise was a false negative, the producer test and consumer had existed since 2026-08-15.)*

*(Previously:)* All 28 rows were decided on 2026-09-06 in one sitting; every answer is recorded in its owning doc (grep
`Decision 2026-09-06` / `Decided 2026-09-06` / `Ratified 2026-09-06`), and the work each unblocked is ranked below — P1 where it was
decided and buildable, §2 where it became an org action, §6 where the answer was "keep as designed". Three rows
turned out to be already answered by shipped code (`description`, `duplicate_check` owner, `engine: auto`) and one
half of a fourth (the id slug). New decisions get a row here at handoff time.

## 2. Externally gated

Nothing a shift can close from this checkout. Listed so the gate is named, not guessed.

**Gates RUN on 2026-09-07** (second pass), not just read. 🔴 **Tally corrected the same day — the first
version of this line said "13 of 15" and was wrong twice.** This table has **16** rows, and **4 of them
cannot be checked from this checkout at all**, so counting them as "run" was the same over-claim the pass
was supposed to remove.

**Swept again 2026-09-08 — and one gate FELL.** **Accurate: 14 of the 15 gates were RUN here — all 14
still gated.** Plus 2 gates outside this section (§3 Platform Services Stage 2, §5 D8-SUPPRESS-1), also
run, also holding. The 1 not run, and why: **Completeness KPI hold** has nothing to run, because its gate
turned out to be an engineering action, not a check.

🔴 **AGT-5's gate was DISCHARGED and the row moved to §3** — the upstream seam it waited on has shipped.
And 🔴 **the command this section told a shift to run could never have found it.** `gh search code
'DryRunProvider' --repo jotder/inspect-agent` returns **zero hits for every term**, including a control
search for `class` against a repo with 375 `.java` files — GitHub's code-search index does not answer for
that repo, so "no hit ⇒ still gated" was **unfalsifiable by construction**. This is the *second* instance
of that defect in this very section (Row 15's was the first). ✅ **Replacement check that CAN return a true
positive** — the git-tree API, which needs only read access:
`gh api 'repos/jotder/inspect-agent/git/trees/main?recursive=1' -q '.tree[].path' | grep -c DryRunProvider`
(control: `| grep -c '\.java$'` must be ~375, else the probe itself is broken).
⚠ Both AGT rows' `gh search code` checks are replaced below. **Re-run every gate with a probe that can
succeed before trusting a 0.**
⚠ A gate you cannot run is not a gate that holds — it is a gate with no evidence either way, and the two
must not be summed.

**This arithmetic is now enforced.** `tools/check-gate-tally.mjs` fails the build when the sentence above
disagrees with the table below — it caught this very paragraph going stale within minutes of being
written. It counts rows, counts `NOT RUN` markers, and requires run + not-run to account for every row
exactly once. ⛔ Fix the SENTENCE to match the rows, never the rows to match the sentence.

🔴 **Two of the four "unrunnable" rows were fixed rather than accepted** (2026-09-07). **NFR-7** was the
only gate on the board with no repo-side check at all; it now has one landing row per sub-item in
`compliance/controls-matrix.md` §4, checked by `grep -c '^| NFR-7 ·.*⬜ open'` (7 today, closes at 0).
**AGT-5/AGT-6b** were never "external" — the upstream is a public repo CI clones on every run; they were
merely un-run, and now name the command. ⚠ The anchoring in that grep is load-bearing: unanchored it
returns **9** for **7** rows, because the prose stating the check matches the check's own pattern.

There was **1** correction, not 2 (Row 15's, below). The `git tag` gates are unmoved (newest master-ancestor tag is `v3.11.0`;
`v3.12.0` is on the retired `3.x` line). OPS-5's outcome log exists but says `_(empty — awaiting the first
live deployment)_`. `rto-rpo-statement.md` still carries 2 `<OPERATOR TO STATE>` placeholders. The
controls-matrix has **no** SOC-2 window start date and **no** dated CC6.1 line for the SEC-INCIDENT-1
carry-forwards. `PROJECT_NOTES.md` §DATA-GOV-1 records the decision but no archive location or fetch-script
path. `ci.yml` still builds `jotder/inspect-agent` from source (3 references). The interview plan has no
session record. No prospect is named anywhere. 🔴 **The correction that matters: Row 15's gate was
unfalsifiable BY CONSTRUCTION** — see its row. Writing a gate as a command is not enough; the command has to
be one that CAN return 0.

**Rewritten 2026-09-07.** Every gate now states something a shift can CHECK from this repo. Before, most
named an event nobody watches for ("a live deployment", "demand", "a client policy") — unfalsifiable by
construction, so the row could never move and nobody could tell whether it should. Where the trigger is
genuinely external, it is now phrased as **"when X is recorded in \<file\>"**: naming the landing place
turns an unwatchable event into a file check. ⚠ Two rows also cited evidence that was not where they said
it was; both are corrected below.

| Item | Remains | Gate — how a shift CHECKS it |
|---|---|---|
| **Row 15 — ELT Phase 6 deletion half** | Delete the legacy flat read path (amendment §6 step 4). 🔴 **Two earlier §6 steps were never on this board and are added 2026-09-10 (Sprint 7.5): step 1, the one-shot converter, is UNBUILT** — the plan claimed *"✅ the converter exists (`inspecto migrate-configs`)"* and that command appears in **no source file**; the commit it cited was a `RecipeConverter` projection fix. **And step 2, the parity gate, is unverified** — ⛔ do not read `RecipeConverterTest` as meeting it: that proves *round-trip parity of the projection*, while step 2 asks for the full suite **EXECUTING** through the compiled-recipe path. Different claims, only the first evidenced. The `-Dingest.lane=auto\|graph\|flat` flag exists (`ConsignmentIngestStrategy.admittedLift`, 2026-09-02); the verification minor must SHIP first. ⛔ Not closable by code; ⛔ do not start it on momentum. Then `withMappingContext` → `PipelineLift` comes due only if the graph lane executes the map node. The waves board is 16 of 17. Also absorbs RECORD-TRANSFORMER-1 (d): the ingest lane runs exactly one projection slot, so a second `transform.sql` cascades only once the graph lane carries ingest. | 🔴 **Gate CORRECTED 2026-09-07 (second pass) — the one I wrote a few hours earlier was itself unfalsifiable.** `git merge-base --is-ancestor v3.12.0 master` can never return 0: **`v3.12.0` is a tag on `origin/3.x`**, a retired line, cut 2026-06-05 — it is not an ancestor of master and never will be. The newest master-ancestor tag is **`v3.11.0`**. D-2 (`elt-final-amendment-plan.md` §9) says "Converter + **one flagged verification minor**, then the legacy readers are deleted", so the gate is a MINOR **on master** after v3.11.0 that ships the converter and the `-Dingest.lane` flag. **Check: `git tag --merged master --sort=-v:refname \| head -1` — still `v3.11.0` on 2026-09-07 ⇒ gated.** |
| **Release notes for the next MAJOR** | **Drafted** in `okf/backend/control-plane/api-stability.md` §Release notes; append there with every further `feat!:`. ⚠ The `batch_id` rename trio rides this release and is NOT enumerated in that list — see §7. | `git tag` — the next MAJOR tag. |
| **X5 cross-lane drill-down + StepInfo envelope** | One-Consignment drill-down across lanes; ~1 KB pointer+schema+diagnostics envelope, failure routed by PORT. Phase-7 convergence. | `git tag` — the next MAJOR (pipeline-spec §13 D2). → `okf/backend/pipeline-graph/execution-lanes.md` |
| **X-Actor full removal** | Remove the header path entirely (already rejected outright on Standard/Enterprise). | 🔴 **Gate restated 2026-09-07.** It used to read "client migration with the API-v1 sunset" — but that apparatus was **deleted 2026-07-25**, and `api-v1.md` contains **zero** occurrences of "Actor", so the gate pointed at something that no longer exists. The only remaining exposure is Personal; a MAJOR is the sanctioned break. **Gate: the next MAJOR tag.** → `okf/backend/editions/auth-security.md` · `EDITIONS.md` SEC-11 |
| **OPS-5 provenance conservation** | Live-feed soak only — no code left; feature built, off by default. The discharge criterion is well written (`docs/ops/provenance-conservation-verification.md` steps 1–3, ground-truth `recordsIn`/`recordsOut`). | **Close when that file gains a dated results section.** Nothing in-repo would otherwise show the soak had been run — the work could be done and the row would still read open. |
| **Deployment topology live validation** | T2/T3/T4 reference deployments, the D8 IAM pair, GAP-7 blueprints, grammar-config live smoke. ⚠ **Evidence pointer corrected 2026-09-07:** the RTO/RPO statement with its `<OPERATOR TO STATE>` placeholders and empty drill table is `compliance/evidence/rto-rpo-statement.md`, **not** `docs/ops/backup-restore-runbook.md`, which the row cited and which contains none of it. | A reference deployment. **Repo-side half is checkable now:** close it when `rto-rpo-statement.md` carries stated targets (⚠ the signed per-tier D6 numbers are recorded in `okf/capabilities/editions/editions.md` §3.14 — transcribe from there; the drill record still has to be produced) and ≥1 drill row. → `archived-documents/plans-archive/deployment-topology-plan.md` (§10 D1–D8 signed 2026-09-06) |
| **Compliance program (NFR-7)** | External only: C1 applicability statements, ISMS boundary, auditor engagement, pen test, C5 policy content, C6 FedRAMP package + FIPS leg (demand-gated), the ISO 8.8 advisory-watch process. | ✅ **Gate MADE RUNNABLE 2026-09-07 (second pass).** It was the one row with no repo-side check at all, so it could never be evidenced either way. It now has a landing place per sub-item: **each of the seven closes when its own row in `compliance/controls-matrix.md` §4 carries a dated line.** **Check: `grep -c '^| NFR-7 ·.*⬜ open' compliance/controls-matrix.md`** — **7** today; the row closes at 0. 🔴 The check must be **line-anchored**: the unanchored `grep -c "NFR-7 ·"` returns **9**, because the surrounding prose (including the sentence stating the check) matches its own pattern. A check that counts its own documentation is not a check. ⚠ The five REPO-SIDE artifacts this row used to carry (customer verification runbook, CI-evidence doc, recorded restore drill, G8 RBAC evidence, G9 FIPS) are file-existence checks, not external gates — they moved to §5 on the first pass. → `compliance/controls-matrix.md` §4 |
| **SOC 2 Type II window** | Opening the 6-month observation window was **decided 2026-09-06**. The HIPAA/PCI framework choice stays deferred until a prospect is named. | 🔴 **No start date is recorded anywhere**, so the 6-month end cannot be computed and nobody can tell whether the window is running. **First action is not external: record the start date in `compliance/controls-matrix.md`.** The gate then becomes `start + 6 months` — arithmetic. |
| **D13 parser field tiers** | Run the onboarding-observation session. Second question for the session: every `tier:'required'` field ships `required:false` validators — should "required" validate? ⛔ Explicitly NOT an engineering guess. The **pre-agreed analysis rule** (M / T-in-3-lanes → required; never-touched-and-never-asked → advanced; one **M** on a required field files a validator, two decide it) is in `okf/frontend/features/grammar-config.md` — ⛔ do not re-derive it after the session, that destroys the whole point of agreeing it in advance. The kit is archived at `archived-documents/plans-archive/parser-field-tiers-interview-plan.md` and is still runnable, but 🔴 **re-ground its inventory and task script first** — both predate `d012f721` (2026-09-04), which dissolved the `files` section and turned tabs into sections. | A real onboarding user. **Close when `parsing-attributes.ts` carries `tier:` values annotated with the observations that earned them, plus a question-2 decision note in `okf/frontend/features/grammar-config.md`.** 🔴 Restated 2026-09-09: this read "close when `superpower/…interview-plan.md` gains a dated session record", which archiving would have made uncheckable — no shift may maintain a file in the archive tier. The deliverable is the plan's own, and it is checkable in the current tier. |
| **SEC-INCIDENT-1 carry-forwards** | Incident CLOSED BY DECOMMISSION 2026-08-29. 🔴 Due on closure and NOT done: delete the off-repo pre-rewrite backup bundle (five cleartext secrets; retention condition lapsed); confirm none of the five values was reused elsewhere. Internal hostnames/IPs still published in-repo (lower severity). | Operator, off-repo. **Close when `compliance/controls-matrix.md` CC6.1 carries a dated line confirming the deletion and the no-reuse check.** ⚠ Deleting an off-repo bundle leaves no repo trace, so without that line this overdue item can never be marked done or chased. |
| **DATA-GOV-1 archive** | Move the real carrier corpus to an encrypted out-of-band archive on company storage with a fetch script; access held by the data-agreement owner. | Org action. **Close when `PROJECT_NOTES.md` §DATA-GOV-1 carries the dated archive location and the fetch-script path.** |
| **EOI-7b** | Publish eoiagent `0.1.0` artifacts to a registry; CI rebuilds from tag meanwhile. | ✅ **Checkable in one grep:** close when `.github/workflows/ci.yml` no longer checks out and builds `jotder/inspect-agent` from source (3 references today). |
| **AGT-6b multi-step agent graphs** | First cut = generalize `RunbookActions`, never free-form ReAct over mutating tools. | ✅ **RUN 2026-09-08 (network), and it HOLDS — but only on its second precondition.** ⚠ The old check (`gh search code`) was unfalsifiable — see the section header. Probed instead via the git-tree API. **(1) The per-tool `DryRunProvider` seam HAS SHIPPED** upstream: `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunProvider.java` + `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunResult.java` + four per-tool `*DryRun` tools (`AuthorPipelineDryRun`, `EditConfigDryRun`, `RunPipelineDryRun`, `TriggerJobDryRun`), under an **Accepted** ADR-0008 ("all mutating actions require an ApprovalGate + dry-run, enforced in the runtime", 2026-06-19). **(2) The approval gate is STILL synchronous per-call** — `eoiagent-core/.../host/ApprovalHandler.java` is a single blocking `ApprovalDecision onApprovalRequested(ApprovalRequest)`, no future/callback — so nested gates still deadlock and this row stays gated on that alone. **Check: `gh api 'repos/jotder/inspect-agent/contents/eoiagent-core/src/main/java/com/eoiagent/host/ApprovalHandler.java' -q .content | base64 -d`** — reopen when that signature stops being synchronous. → `archived-documents/plans-archive/agt-6-plan.md` §4 |
| **E1 Enterprise distributed tier / Stage-2 streaming** | **Design SIGNED 2026-09-10** — T5 partitioned scale-out by Space (`superpower/enterprise-scale-out-plan.md` §9 D1′–D13); phases A–B are also Standard's T4 DR. | **Spikes S1–S5 next (plan §10); phase A starts when they report.** Stage-2 streaming stays unscoped (`STREAM-CONSUMER-1` is its first design pass). |
| **Completeness KPI hold** | K2 wiring / K4 / K5 held: whether `{seq}` restarts per hour is a carrier fact. | ⬜ **NOT RUN — there is nothing to run.** 🔴 **Gate CORRECTED 2026-09-07 (second pass) — my own first-pass annotation was wrong.** It said "a query over one month of landed filenames answers it … roughly half an hour of work". **There is no such query to run:** the plan's own §2b records that **no default-on durable store holds processed filenames** — `-Dfile.stages.backend` defaults to `none` (`ServiceStores:141`), the acquisition ledger to `memory` (`OperationalDb:92`), and the status CSV is buffered, un-fsync'd and one file per run. Analysing `file_stages` unflagged would report "no gaps" on a stock deployment — the `ConservationCheck` trap. ⚠ So this row is **not** carrier-gated and **not** query-gated: **its first action is engineering** — give the filename history a durable default-on home (or make the analysis REFUSE when the store is `none`). Only then does Q2 (`{seq}` per-bucket vs continuous) become answerable from data. → `archived-documents/plans-archive/completeness-kpi-plan.md` §2b |

## 3. Product features — decided or unblocked, simply unbuilt

Grouped by area. A row with lettered items keeps the letters of its source doc so the two stay aligned.

### Authoring (Parse / Transform / pipeline editor)

- **P2** · **AUTHORING-REDESIGN-1** — open letters (⚠ the old "(j)(l)(n2)(o) are in §1" clause was stale in all four: (o) SHIPPED 2026-09-07 as WORKBENCH-S4 — all three slices, (l) and (n2) SHIPPED, (j) `engine: auto` was already answered by shipped code; (f)(g)(m) SHIPPED 2026-09-06 — `JOIN_REFERENCE_MISSING`/`JOIN_ON_MISSING`/`UNKNOWN_JOIN_REFERENCE` at save, `SchemaMappingDrift` on all three schema save paths, `?pipeline=` sent by the UI): (c) v2 structured AST table over the SQL for WHERE/JOIN editing — ✅ **precondition DISCHARGED 2026-09-07: it does.** `json` is statically linked into the DuckDB JDBC artifact, so nothing is installed or auto-loaded and the seal is irrelevant to it: `json_extract`, `json_structure` and — the one that matters — **`json_serialize_sql`**, which returns the whole parsed AST as JSON, all work on a sealed connection while `INSTALL excel` and re-opening `enable_external_access` still fail. Pinned by `SqlSandboxTest.jsonWorksOnASealedConnection`. ⚠ So (c) reads an engine-produced AST rather than re-implementing a SQL parser in TypeScript — the same refusal the step workbench made for reference detection; (d) v3 macros as the UDF registry (per-connection re-creation in `EnrichmentEngine`, `PipelineJobRunner`, `ConsignmentIngestStrategy`, preview) — demand-gated; (e) column metadata editing on the Transform pane (Parse D2) — needs a backend home for metadata on a `transform.sql` node first; (i) per-row "sample resolves to" line — no host resolves a sample against an `AttributeSpec`. Still open on (f): which COLUMNS the reference carries is the dry-run's question (it reads the store); the save checks existence and `on` presence only. → `okf/frontend/features/schema-mapping-authoring.md` §0
- **P2** · **Step Processor catalog** — 119 processors: **35**<!--count:processors-delivered--> delivered / **17**<!--count:processors-partial--> partial / 67 planned (`processor-catalog.contract.json`, counted 2026-09-10 — `quality.schema.drift` DELIVERED 2026-09-10 as a per-batch `quality.schema_drift` Signal; the earlier count was 34/18 on 2026-09-08 — the earlier "69 planned" was a grep artefact) (`transform.lookup` DELIVERED 2026-09-06). Each partial is a product decision (Kafka consumer, XPath grammar, drift report, profiler, resampler, KPI layer, Jinja, graph tagging, commit controller, SLA object, view/email/webhook sinks…) — pick one by name. → `EDITIONS.md` §Step Processors · `okf/backend/pipeline-graph/step-catalog.md`
- **P3** · **P4 Test mapping on a generic `parser` node** — **RE-SCOPED and DEMOTED P2→P3 2026-09-09**, which is what the row's own "re-scope this row before building" asked for. Grounded against source:
  • ⚠ **The (l) discharge does not unblock THIS row.** What shipped 2026-09-06 unblocked Test-mapping on a **dangling per-format** grammar binding (`isDrawerParse` admits it, the pane flags "template missing"). A **generic** `parser` is the case the owner doc calls **unmappable**, and it still falls to `GrammarEditorDialog`. The gate is discharged for a *different* node.
  • 🔴 **Demand-gated because the authoring surface is gone.** Test mapping is **read-path only since 2026-09-05** — the Load pane was deleted and nothing authors a parse node's mapping any more (measured: **zero** files match `pipeline-load-definition`). Mappings *are* still authored, but as the standalone **Mapping component** (`mapping-editor.dialog.ts`), which is a different surface with its own editor. So building this would test a mapping an operator cannot author on that node. ⛔ Per §0's rule, that is P3: build only when someone asks by name.
  • ✅ **The offline caveat is deleted, not carried:** "non-`DIRECT` types show blank (mock has no SQL engine)" cited the offline mock backend, which was **removed 2026-08-31**. There is no mock to be blank.
  → `okf/frontend/features/pipeline-editor.md`
- **P2** · **Canonical-pipeline selective bundle export/import** — the metadata bundle's `authored-pipeline` kind still targets the RETIRED `*_flow.toon` `PipelineStore`; a canonical `*_pipeline.toon` transfers only via the datasource zip or the client-side stream-config bundle. Wanted: one selective export/import with dependency closure (schemas, per-segment schemas, grammar/enrichment companions, Connection as secret-free requirement) and retire/repoint the `authored-pipeline` kind. **Decided 2026-09-06 (operator): in bundle manifests `schema` = the REGISTRY id (`registry/schemas/<id>`); a pipeline-owned `<name>_schema.toon` (+ its `_mapping.csv`/`_structure.csv` siblings) travels under its own kind, not as `schema`.** Apply this in `BundleRoutes`/`transfer/bundle.ts` when the row is built → `okf/frontend/features/onboarding.md`

- **P3** · **AI drafting has no applicable component kind** — restore `<inspecto-ai-assist>`/`component_draft` for a kind: either give `grammar`/`transform`/`sink` a backend `ConfigSpec` (none has one; `ConfigSpecs.TYPES` excludes them) or rework `SchemaEditorDialog`. No low-risk slice survives — design first. → `okf/frontend/features/inline-ai-authoring.md`
- **P3** · **`AGT-SEGMENT-1` — the assistant's commercial framing is an unvalidated product read.** The tier packaging (A Explain / B Author-with-approval / C Bounded autonomy), the "Tier A is the wedge" moat argument and the SHADOW-first on-ramp were written as a read of the codebase + roadmap and **never validated against a client segment** — the archived plan's own words. Telecom vs general regulated enterprise changes the emphasis. ⛔ **Decided 2026-09-10: keep the caveat — reopen on the first customer conversation**, not before; guessing a segment now would replace one unvalidated read with another. ⚠ Needs **product input**, not engineering; nothing in the product depends on it, but the framing is now quoted in a stakeholder-facing doc, so it must carry its caveat until this closes. (Was `agt-6-plan.md` D6, which had no board home at all.) → `stakeholders/PRODUCT_CAPABILITIES.md` §"How the ladder is packaged" · `archived-documents/plans-archive/agt-6-plan.md` §2
### Onboarding, Catalog, Parsing


- **P3** · **Unpack (11) absent codecs** — (xz/zstd need a new decompression library: a dependency sign-off, not a build) — xz and zstd have no plugin; `.Z` has no round-trip test; multi-part/split archives (`.z01`, `.part1.rar`) unhandled — arrival-completeness is a Collector question. ⚠ The UI cannot author the explicit empty-list `data_extensions[0]:` opt-out (schema-form `list` writes empty as `null`). 🔴 Stale `META-INF/services` "ORDER MATTERS" header. → `okf/backend/engine/unpack-stage.md`
- **P2** · **Onboarding (Stream/Reference)** — D5-ref: how a `delete` tombstone *enters* the reference store (reserved column? Decision Rule consequence?) — wait for a real delete-feed; D6-ref: within-batch same-key tie-break is arbitrary — add an optional latest-by-`order_by` column only when needed; optional templates entry (space-template-gallery precedent). ⚠ Enrichment/job configs still derive identity from name. ⚠ Do not implement name-deferral by holding the draft client-side. → `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md`
- **P2** · **Onboarding ↔ Pipeline unification W4/W5** — (W0 PROVEN 2026-09-06: `LiftLowerFixtureSweepTest` runs every `spaces/**/*_pipeline.toon` through the editor's own seam — `PipelineEditable.toMap` → codec → STRICT lower — and all 22 survive verbatim; it stays as the standing gate.) W4: `EnrichmentService` incremental-vs-full recompute — never silently convert one into the other. W5 promotion-grade export: extend `BundleExporter`/`DataSourceBundleResolver` to decision rules + reference datasets; import-time referential integrity (a missing connection is not caught until first poll). Engine has no grouping transform (rollup honestly = `sink.materialized`). ⛔ `PipelineCompiler.toConfigMap` deliberately not migrated. → `archived-documents/plans-archive/onboarding-pipeline-unification.md` · `okf/backend/pipeline-graph/editable-round-trip.md`
- **P2** · **Parsing (Stage-1)** — ASN.1 grammar source: a reference to a stored schema module instead of pasted module text (also the prerequisite for a per-vendor transform config home); drop-in `plugins/` jar directory (JobPackManager classloader precedent) so a customer parser deploys without a rebuild. ⚠ `asn-parser/src/main/java` is NOT dead (compiled by `legacy-code/pom.xml`); corpus tests are opt-in and data-gated (DATA-GOV-1). → `okf/backend/engine/parser-plugins.md`
- **P3** · **Problem-files view: `logical_name`** — carry `logical_name` beside `origin` so a re-delivery groups to its earlier compression spelling. Additive; on demand. → `okf/backend/control-plane/control-api.md`

### Execution, Consignments, Pipeline graph


- **P2** · **Branch-aware executor residuals** — ((b) and (c) are design passes before code; (d)–(g) wait for a real need) — (b) multi-schema + route needs a **segment-scoped lift** (`writeAndTrace` runs once per segment while the divert lifts the whole graph) — ⛔ do NOT just lift the refusal; (c) mid-branch transforms in the recipe route verb — no per-branch scaffolding in `RecipeCompiler.route()` / `PipelineLift.branch()`, design pass written; (d) still unimplemented anywhere: `adapter`, `alert`, `event`; still refused at lowering as flat homes: `transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest; (e) acquisition-side "listed remotely, not yet fetched" gauge — name it first; (f) `acquire.maxFilesPerCycle` — only if overshoot is real; (g) `sinks:` follow-ups: per-sink `ducklake` block in flat `.toon`, decision-rule routing with `sinks>1`, versioned reference store with `sinks>1`, and a `ConfigSpecs`/`ConfigJsonSchema` structural spec for `sinks:`. ((a) `mode: clone` **shipped 2026-09-06** — `RouteArming` no longer refuses it.) → `okf/backend/engine/branch-aware-ingest.md` · `okf/backend/engine/output-sinks.md` · `archived-documents/plans-archive/mid-branch-transforms-design.md`
- **P2** · **Platform Services Stage 2 / 3** — (gated on the at-rest execution decision + the S2-2 spike) — Stage 2 open Step-kind registry (`StepTypeProvider` with `LOWERED`/`EXECUTED`, `StepContext`, failure mapping, watchdog) — ✅ **gate RUN 2026-09-07: holds**, no `StepTypeProvider` exists in any module: needs the decision to execute an intervening node at rest (the `graphLaneCarries` boundary = Phase 6 precondition) plus the S2-2 bridge spike (rows/s through a no-op `EXECUTED` Step vs fused) before GA; ⛔ third-party `LOWERED` stays closed until a SQL-fragment guard exists. Stage 3 pack-contributed services (`ServiceProvider` SPI; collision fails the pack atomically; reference-tracked quiesce). `DatasetAccess` after the Consignment Selector. No Job-side watchdog (R1) — a hanging Job is a recorded gap. Filtered `services()` on `ProcessorContext` (D4) and a devkit jar (D5) only on demand. → `okf/backend/control-plane/platform-services.md`
- **P2** · **Consignment addressing** — (torn multi-file reads: CLOSED 2026-08-29 by the pinned `ConsignmentSelector` list — this row said "open" for a week; the two readers that still re-globbed, `DbBrowserRoutes.browseStore` and `ExpectationEvaluator`, were pinned 2026-09-06) `generation` is a dead field (always 0, never read); ⚠ `retire_superseded` must be configured or every full recompute leaves a complete extra copy on disk; ingest-side Consignment-scoped accessor waits for a consumer; ⚠ `DatasetRelation.temporalColumn` has no caller and cannot safely gain one on a write path. → `okf/backend/engine/consignment-addressing.md`
- **P2** · **EXECUTION-RESIDUALS X4 + X1 deferrals** — X4 record-level replay from quarantine: sidecar error manifests (offset/reason), all-or-nothing vs eject-and-continue as per-pipeline CONFIG — ⛔ no build without a driver (same item as the run-detail "reprocess is whole-batch only" note). X1 deferrals: per-pipeline `processing.retry` block (regenerate node-attributes + step-types contracts); operator cancel / retry-now affordance (today: delete the sidecar under `<status_dir>/retries/`, or `reprocess`). → `okf/backend/pipeline-graph/execution-lanes.md` · `archived-documents/plans-archive/execution-residuals-plan.md`
- **P2** · **`STREAM-CONSUMER-1` — adapter stream-consumer runtime** (filed 2026-09-10 — it was committed in `roadmap/ROADMAP.md` §3.4 and listed in `okf/capabilities/acquisition/acquisition.md` §"Open elsewhere on the board" with **no board row**, the id column pointing back at the ROADMAP paragraph). The land-then-ack seam exists (a source-side `post` that deletes the remote original runs only after the local copy is committed); the **consumer loop** that keeps an adapter draining a streaming source with at-least-once semantics does not. Not demand-gated: the ROADMAP commits to it. First action is a design pass on where the loop lives (Collector scan vs a long-running job), not code. → `okf/capabilities/acquisition/acquisition.md`
- **P2** · **Pipeline graph** — flip the intake cap on by default (needs a soak); a pre-materialise cap to save remote-fetch bandwidth (cap applies post-dedup); 🔴 **THREE** kinds still last-one-wins, deliberately out of A2 scope: `acquisition`, `gap`, `dedup.marker` — *corrected 2026-09-09: `parser` was in this list and does NOT belong; a second parser is REFUSED by name (`MULTI_PARSER`, `PipelineEditable.java:65,696`), which is the opposite of last-one-wins. `pipeline-editor.md` §Multiplicity states it correctly.*; 🔴 ~~`BatchGraphRunner` has zero production callers~~ **WRONG ON BOTH COUNTS — corrected 2026-09-09.** (a) **There is no class of that name** — it was renamed in the 2026-08-31 Consignment commit. (b) The class that DOES exist, `ConsignmentGraphRunner`, has **production callers**: `engages()` drives the live lane admission (`ConsignmentIngestStrategy.admittedLift`) and **`run(...)` executes on the ingest path** (`ConsignmentIngestStrategy:355`). ⛔ This row was cited as Row 15's parity blocker, so re-derive that gate before using it. What IS still owed is §6 step 2, the parity gate through the compiled-recipe path. Owner: `okf/capabilities/pipeline-execution/pipeline-execution.md` §2.3; → `okf/backend/pipeline-graph/pipeline-graph-design.md` §14
- **P2** · **Consignment ELT** — (three items added 2026-09-07 from the archived plan's §11.2/§11.7/§15, which BACKLOG never carried: **`batches` is structurally singular** — its `schema_name`/`output_table` are one-per-row while a Consignment's EL emits a row set *per schema*, so this needs either one row per `(consignment, schema)` or a child table, an open decision; whether a **durable `DeliveryReceiptStore`** exists beyond the in-memory one is a one-grep check still owed; and §8.4's SLA config object is dropped with sealing, not pending.) `generation` is on the registry but compaction does not stage generations; `run_id` is `null` everywhere; §7.4 rollup cache deliberately unbuilt until read-time aggregation is measurably slow; §7.3 unpartitioned fallback stands by operator call — revisit if flat summary targets appear. → `okf/backend/engine/db-layer.md` §3.9
- **P2** · **Completeness KPI (when the hold lifts)** — K2 wiring (`FileSequenceGaps` analysis shipped `14c6ef0e`, wiring not built, needs `SeqScope`); K4 `kpi.completeness` job type (`JobTypeProvider` + descriptor + `ParameterDecl`s, cron'd, one config per pipeline, signal + deduped Incident on breach, must refuse loudly when `-Dconsignment.outputs.backend=none`). ✅ **K5 SHIPPED 2026-09-07** — 🔴 corrected 2026-09-09: this row and `INDEX.md` both listed K5 as remaining while the plan's own slice table and §5 recorded it done, a three-way split. Non-blocking: signal type naming `kpi.completeness.evaluated`/`.breached` (⚠ do not grow the `EventType` enum; a constants class should land before ~10 string literals do), K3 baseline-window default as a job parameter. ⚠ `VolumeBaseline`/`FileSequenceGaps` have no production caller today. 🔴 **Three items had no board home at all until 2026-09-09**, found when archiving the plan: (a) **`KPI-UNKNOWN-1`** — a null-`bounds` sink's daily count is **UNKNOWN, not zero**, and the KPI must carry that end to end (only the registry-off trap was ever filed); (b) where the sequence **template** itself comes from — the Collector's existing one, a job parameter, or the Collector's with an override — still undecided; (c) K1's and K3's acceptance criteria, now in `okf/capabilities/observability/observability.md` §3.9. → `okf/capabilities/observability/observability.md` §3.9 · `archived-documents/plans-archive/completeness-kpi-plan.md`
- **P3** · **`PROCESSOR-CATALOG-ROUTE-1` — nothing serves `ConsignmentProcessor` ids to the UI** (filed
  2026-09-10 by Sprint 7.6; a deliberate deferral that had no board row). The post-sync chain editor takes
  processor ids as **free text**, the way `on_signal` takes signal types, because no catalog route exists. A
  route plus a picker is the natural follow-on. ⛔ It was deliberately not invented alongside the editor —
  this row records the deferral, not a defect. → `okf/backend/engine/post-sync-step-chains.md`
- **P3** · **`PACK-UNLOAD-EXPOSURE-1` — unloading a pack makes a stored pipeline unloadable** (filed
  2026-09-10 by Sprint 7.6). A pipeline naming a pack-contributed node type stops loading once that pack is
  unloaded — the same exposure a Job typed on an unloaded pack already has, and the reason a pack is normally
  *replaced* rather than removed. **Stated, not fixed**, in the plan that shipped the overlay; decide whether
  this is accepted posture or work. → `okf/backend/control-plane/job-vs-step.md`
- **P2** · **`TYPEFLOW-CONSUMERS-1` — three declared consumers of the type-flow description were never
  built** (filed 2026-09-10 by Sprint 7.6; the amendment's own P2 S2 deferred the wiring to "S3+" and
  nothing recorded it landing). `TypeFlow.describe` exists; what does not: **(a) save-time cell-level
  validation** — a Mapping over a nonexistent field, **a route predicate over a dropped column**, a
  summarize over a non-numeric measure; **(b) Dataset auto-registration**, where the sink's derived schema
  becomes the Dataset's `columns{name,type,role}` instead of a hand-authored column list; **(c) the plugin
  contract** — a plugin Step cannot be SQL-described, so the SPI must declare its output schema. →
  `okf/backend/engine/catalog-vs-executors.md`
- **P3** · **`TOKEN-VOCAB-STEPS-1` — the token sequence's steps 2 and 3 are unblocked TODAY** (filed
  2026-09-10 by Sprint 7.6). Delete the five non-edges in favour of Signals, and collapse the four reject
  relations to `reject:<reason>`. ⚠ **These two are documentation and vocabulary and need no runtime
  decision** — unlike steps 4 and 5, which need D2's runtime half (X5, next MAJOR). ⛔ Do not bundle them
  with the runtime work; that is what has kept them unstarted. → `okf/backend/engine/node-types.md`
- **P3** · **`GLOSSARY-CASE-1` — split the two `Case`s in code** (filed 2026-09-10 by Sprint 7.3, `SPEC-GLOSSARY-1`). The
  glossary now defines both senses and says which keeps the word: `ObjectType.CASE` (groups Incidents — the pane, the
  user guide and the controls matrix all use it) stays `Case`; the Assistant's `com.gamma.intelligence.investigation.Case`
  — one RCA playbook run against one Incident, and the **only** type in the repo actually named `Case` — becomes
  `Investigation`. The code's own package is already `…intelligence.investigation`, so the rename moves toward the
  code's naming rather than away from it. ⚠ **Four published routes are in scope** (`/agent/cases*`), so this needs an
  alias or a deprecation window, not a silent flip — that is why 7.3 filed it instead of applying it. Touchpoint list in
  `GLOSSARY.md` §13. ⛔ Do **not** also rename `mode: case` (route branching) or `caseType` (line of business): different
  words that merely look alike, and `caseType` feeds RBAC data scopes.
- **P3** · **D-8 XLSX export** — zero groundwork (no spreadsheet library in any pom); gated only by a bare label — state the operator question before answering it. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §9 D-8
- **P3** · **D-11 hand-authored `relations` component** — deferred until a business relation exists that no Pipeline exercises. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §3.4

### Control plane, jobs, notifications, queries

- **P2** · **`CLIENT-HALVES-1` — the SPA adopts three shipped server halves** (⛔ decided 2026-09-10 per row, `CONSUMER-PAIRS-1`):
  (a) both config-writing panes send **`If-Match`** and surface a 412 as "changed underneath you" (`API-3`); (b) the shell
  interceptor stops discarding the v1 envelope so **`permissions[]`** reaches the panes, then affordances gate on it — the
  interceptor change comes FIRST, it is the reason the field has been unreachable; (c) the Events pane subscribes to
  **`GET /signals/stream`** with polling as the fallback for buffering proxies. One row, three client changes, no server
  change. → `okf/capabilities/control-api/control-api.md` §2 `API-3` · `security.md` · `observability.md` · `surfaces.md`
- **P2** · **`EXPECTATIONS-UI-1` — the Expectations pane** (`ING-6` is a Must with NO UI — zero SPA files mention it; decided
  2026-09-10: ADOPT, the Must stands): define `non_null | range | regex | referential | condition` checks per Dataset, run
  evaluate, show `lastResult`; reuse `<inspecto-query-panel>`; results already open Incidents and `expectation.violated`
  Signals, so triage needs nothing new. → `okf/capabilities/ingestion/ingestion.md` §3.7
- **P2** · **`STUDIO-HALVES-1` — Run and Materialize** (decided 2026-09-10): a **Run** action per saved Query Library entry
  calling `POST /queries/{id}/run` with results in the same panel; a **Materialize** action on the Dataset page plus ONE
  committed example job that schedules a materialization, so the path is exercised by something shipped.
  → `okf/capabilities/data-plane/data-plane.md` · `okf/capabilities/studio/studio.md`
- **P2** · **`AGT-ARTIFACT-1` — produce `AgentAskResult.artifact`** (the inverse pair: a live client consumer, no producer;
  decided 2026-09-10: BUILD): the draft skills (`component_draft`, `pipeline_author`, `query_author`, `projection_author`,
  `kpi_report_builder`) return their draft as the artifact the assistant UI already renders, so an answer is actionable
  rather than prose. → `okf/capabilities/assistant/assistant.md` §3
- **P2** · **`RETIRE-HALVES-1` — delete two server halves with no consumer** (decided 2026-09-10): **`GET /bi/datasets`**
  (Studio keeps its own discovery) and the **`INC-4` queue / watcher / escalation-policy** route families — routes, TOON,
  tests and OpenAPI entries; `INC-4` (a Should) is WITHDRAWN in its spec. ⚠ Pagination is NOT retired: kept as API surface,
  the SPA adopts a cursor when a list outgrows a page. → `studio.md` · `okf/capabilities/incidents/incidents.md` §2 `INC-4`

- **P2** · **API v1** — adopt the cursor-pagination seam on further list families as demanded (4 adopters live); adopt `ETags.respond` on further singleton reads as demanded; Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed). → `okf/backend/control-plane/api-v1.md`
- **P2** · **Bundle / Exchange** — `requires` present-but-different classification; per-editor "load as draft" import — design first, likely multi-session (`BundleTransferService.write` commits straight through; no generic draft seam). ⛔ Do not fake it with a cross-kind `enabled:false` stamp. → `okf/backend/control-plane/exchange-sharing.md`
- **P2** · **Notifications** — D8 residuals: soft-bounce retry scheduling (distinction recorded, nothing retries); SES/SNS adapter (needs SNS subscription confirmation + a cert-chain fetch from a validated `amazonaws.com` URL — ⚠ outbound fetch from an unauthenticated callback path deserves its own review); GeoIP; auth-gated per-user prefs / security triggers. (Auto-disable policy is a §1 decision.) → `okf/backend/control-plane/events-metrics.md`

- **P3** · **Job framework** — Maintenance COULD tier: space-to-space comparison; predictive maintenance (AGT-5 territory) deliberately deferred. → `okf/backend/control-plane/jobs.md`
- **P3** · **D6 spec-authoring UI** — a matrix/editor for `findings-spec`; today authored as TOON through generic `/components` CRUD. Nothing broken without it. → `okf/frontend/features/objects.md`
- **P3** · **Signal / Decision networks** — optional S8 (connector-direct emission + cross-space controller); a general event-triggered consequence policy gate (still `/apply`-only); RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet). → `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md`
- **P3** · **Queries / BI** — `graph`/`spatial`/`search`/`api` QueryTypes; more `$`-resolvers. (DuckDB `spatial` extension itself: zero demand re-verified 2026-08-26 — do not re-open on speculation.) → `okf/backend/control-plane/queries.md`
- **P3** · **EXPORT-1 outbound object-storage export (S3 / HDFS)** — sequence of record: operator `aws s3 sync`/rclone of `data/<store>/database/` first (zero code); build the push post-action (outbound mirror of the connector SPI reusing `AwsSigV4`) only on demand; HDFS only via an S3-compatible gateway — ⛔ never `hadoop-client`. → `okf/backend/engine/object-storage-export.md`
- **P3** · **Security: policy-authoring UX** — a matrix/create editor beyond hand-authored TOON (seed visibility, "why denied?" endpoint and read-only Policies tab already shipped). Non-blocking. → `okf/backend/editions/auth-security.md`
### Deployment & packaging

- **P2** · **D8-SUPPRESS-1** — per-recipient suppression list (TTL for hard bounces, permanent for complaints). ✅ **Its gate — a DB-backed `DeliveryReceiptStore` — was DISCHARGED 2026-09-07** (the same day it was verified still holding): `DbDeliveryReceiptStore` shipped in `inspecto-engine/.../notify/`, wired `SpaceRoot.deliveryReceiptsDbUrl` → `OperationalDb.Family.DELIVERY_RECEIPTS` → `ServiceStores.openDeliveryReceiptStore` → `CollectorService`, behind `-Ddelivery.receipts.backend`. ⛔ Default `none` — an absent receipt DB is the shipped behaviour, not degraded correctness, and a default-ON family creates a DB file in the CWD for every Personal install. Schema + rationale: `okf/backend/engine/db-layer.md` §3.12. ✅ **The suppression policy SHIPPED the same day** — `SuppressionList` (complaint ⇒ permanent · hard bounce ⇒ `-Dnotify.suppression.bounce.ttl`, default `P30D` · ⛔ soft bounce never · off via `-Dnotify.suppression=off`), consulted in `NotificationService`'s ChannelConfig delivery loop. 🔴 It **arms only over a durable store** (`DeliveryReceiptStore.durable()`) and WARNs when a TTL is set over one that cannot honour it — suppressing nothing while appearing configured is the `ConservationCheck` trap. ✅ **`GET/DELETE /notifications/suppressions` SHIPPED too** — the 2026-09-06 decision is fully discharged. `DELETE` records an **override** (operator call 2026-09-07) that forgives history up to its timestamp; a later bounce re-suppresses on its own, and the receipts survive as the audit trail. ⛔ Rejected: pruning the target's receipts — audit loss AND a permanent mask over a dead address. **What remains on D8: soft-bounce retry scheduling and the SES/SNS adapter** (the latter needs subscription confirmation + an outbound cert fetch from a callback path — its own review). Covers EDITIONS `CP-15` (Standard+). → `okf/backend/control-plane/events-metrics.md` §Decision

- **P2** · **AGT-5 per-tool dry-run seam — GATE DISCHARGED 2026-09-08, now actionable.** This sat in §2 as externally gated on eoiagent shipping a per-tool `DryRunProvider`. **It has shipped**: `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunProvider.java`, `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunResult.java`, and four per-tool dry-run tools, under an **Accepted** ADR-0008 enforcing approval + dry-run in the runtime (upstream `jotder/inspect-agent`, verified via the git-tree API 2026-09-08). ⚠ The gate was not "waiting" — it was **held shut by a broken check**: the `gh search code` probe it named returns 0 for every term in that repo, control included. **What this unblocks:** inspecto can now drop its parallel `AgentApprovals` previewer and consume the upstream per-tool seam on `PlatformBuilder`. ⛔ Still separately gated: `incident_explain` waits on the eoiagent **host** seam, and the local-models-only scope cut stands. → `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2
- **P2** · **Deployment topology gaps** — GAP-3 service wrappers (SCR-3) · GAP-4 DuckDB `memory_limit` default · GAP-5 T15 surge admission · GAP-6 Vault/KMS (SEC-8) · GAP-10 bundle missing 13 archived docs (SCR-10). Phases 0–5 all unbuilt. *(Re-grounded 2026-09-08. The "(after §1 D1–D8 are signed)" gate is dropped — §1 records all 28 decided 2026-09-06, which §7 already flagged. **GAP-2 and GAP-8 were shipped work this row had inherited as open** and are struck: Enterprise is a real `package.ps1` flavour (EDG-01) and the Postgres driver rides the bundle as `postgresql.jar` (PG-1). ⚠ **GAP-4 verified STILL OPEN** — D11 shipped as a pair and only the concurrency half is on by default; `DuckDbUtil.memoryLimit(null)` is `null`, no `scheduler.toon` ships, and the committed corpus sets `memory_limit: ""`. Do not close it off the D11 row.)* → `archived-documents/plans-archive/deployment-topology-plan.md` §11
- **P3** · **Postgres multi-user** — ⛔ **PARKED by §6** until a multi-operator install exists; the old "(after the §1 decision)" heading outlived its decision, which was *park it*. Kept for the shape when it lifts: P1 pool behind `JdbcDrivers` (each `Db*Store` holds ONE `synchronized` connection); P2 replace `browseConnection()` (F2: it hands out the store's long-lived connection, a pool has no such thing); P3 **schema**-per-space URL wiring (NOT db-per-space); P4 `CaseStore` interface + PG impl (JSONL ring today); `PostgresStateStoreTest` over the three uncovered stores + a concurrency test. Keep events on Parquet. ⚠ Not the same work as `OperationalDb`/PG-1 (shipped). → `archived-documents/plans-archive/postgres-multi-user-plan.md` §5–6


### Filed from the 17-spec consolidation, 2026-09-09 (Sprint 2)

- **P2** · **`SPEC-GREENCELL-1` — a board cell is green over something absent or bounded.** Five instances,
  and the pattern matters more than any one: **no Standard artifact is built** at all while both supply-chain
  controls show Standard green; **the trimmed runtime ships in nothing** while `OPS-07` is green in all three
  editions (every release passes `-NoRuntime`); **intake caps** are advertised by `JOB-04` and are off by
  default; `BI-4`/`BI-6`/`BI-7` promise Standard+ for **ungated code**, so a Personal install can mint a
  public share link; and replay was green and uncaveated over an in-memory map (fixed 2026-09-09, `2cd1661b`).
  → the rule this needs is one line: **a green cell must name the evidence that makes it true**, and a cell
  whose evidence is "the script can do it" is not describing the bundle a customer receives.
- **P2** · **`SPEC-AGT-EDITIONS-1` — seven `AGT` rows claim edition `All` and no bundle carries the code.**
  The assistant and intelligence modules are plain reactor modules, built and tested on every run and staged
  by nothing, so `/assist/*` and `/agent/*` answer 503 in **every** artifact `package.ps1` produces —
  deliberately (`CP-14`, `PKG-5`) but not as `All` says. Either the cells become "built, not bundled" or the
  packaging switch lands. Blocked by the same Java-floor question as `PKG-5`, which itself has no owner.
  → `okf/capabilities/assistant/assistant.md` §2.
- **P2** · **`SPEC-DEPLOY-ROWS-1` — FIFTEEN deployment items have no board row.** 🔴 **Recounted 2026-09-09: this row said fourteen and its own enumeration missed `SCR-4` (the nginx/IIS proxy + TLS reference configs — TLS, HSTS, static-UI gzip, and restricting `/metrics` and `/health/details` to the monitoring network). A row that exists to catch items with no home had an item with no home.** Its only statement anywhere is its acceptance line in `okf/capabilities/editions/editions.md` §5.2. Thirteen from the deployment
  plan (the preflight tool, the acceptance script, the off-site backup copy, upgrade/rollback automation, the
  sizing table, the disaster-recovery pack, phases 0–5, the platform list, the government-variant refusal)
  plus two board cells with no home at all (distributed scheduler coordination, the shared object store) —
  despite the board's closing claim that every planned row has a backlog home. ⚠ The words `preflight`,
  `standby` and `disaster recovery` appear **nowhere** in this file. Their only durable home today is
  `okf/capabilities/editions/editions.md` §3.9–§3.13, which is why that spec had to be written before the
  plan could move — ✅ **and it did move, 2026-09-09**, with the six tables the narrative could not carry
  now in that spec's §3.14 (sizing, failure→tier, the signed RPO/RTO SLOs, the nine remaining preflight
  rows, VER-1…VER-12, and the phase sequencing incl. the T4 promote order).
  → `okf/capabilities/editions/editions.md` §3.14 · `archived-documents/plans-archive/deployment-topology-plan.md` §11.
## 4. Engineering / tech-debt

- **P2** · **`OPENAPI-GEN-1` — generate the OpenAPI path/method skeleton from the route table** (⛔ decided 2026-09-10
  over "exemplar coverage, deliberately" and "document the rest by hand"). `openapi-v1.json` documents 24 operations
  against 266 live registrations (9.0 %, measured and ratcheted by `ApiContractTest`). Derive every path + method from
  the registrations so structural coverage is 100 % and a route can never be undocumented; the hand-written exemplars
  keep the request/response schemas. → `okf/capabilities/control-api/control-api.md` §2 `API-2` / §5

*(Drained 2026-09-07. Three of the five rows here were standing refusals wearing a tech-debt label — a
"LEAVE unless someone is already in the file" is not work — and moved to §6. A fourth was already closed by
a test that post-dates it. What was left was one release-gated wire change; `SBOM-RESOLVE-1` joined it
2026-09-09.)*

- ~~**P1** · **`SBOM-RESOLVE-1`**~~ ✅ **SHIPPED 2026-09-09.** `release.yml` gained a reactor
  `mvn -DskipTests -Pedition-enterprise install` before the packaging steps — the profile matters, because
  the nine edition modules are profile-scoped and a plain install leaves `inspecto-ops` absent, which is
  the artifact Enterprise fails on. Enterprise is the superset (9 vs Standard's 8), so one pass covers
  every edition packaged below. Verified: `mvn -o -DskipTests -Pedition-enterprise install` BUILD SUCCESS
  over 32 modules, with `inspecto-ops` and `inspecto-policy` jars refreshed in `~/.m2`. ⚠ A truly clean
  BEFORE could not be reproduced on this sandbox (a prior build had primed `~/.m2`); the clean-runner
  failure is the one recorded when this row was filed. ⛔ `release.yml` does **not** invoke the generator —
  `package.ps1:555` does, after staging — so there is no second staging gap here; that was checked and
  refuted. Original diagnosis below.
- **(shipped, kept for the reasoning)** **the bill of materials could not be generated on a clean runner, so the first
  tag failed at packaging.** `tools/sbom.mjs` resolves through `mvn dependency:list`, and Maven will **not**
  resolve a sibling reactor module from a jar built in an *earlier* invocation — only from the local
  repository, or from an artifact produced in the same session. `inspecto/package.ps1` runs `mvn … package`
  and then invokes the generator as a **separate** process, so every reactor dependency must already be
  installed. `.github/workflows/release.yml:45-46` runs its only `mvn … install` under
  `working-directory: eoiagent-src` — it installs the **agent** dependency and never this reactor. A
  non-zero exit throws by design, so packaging stops. ⚠ **Enterprise fails first and most visibly**:
  `inspecto-policy` gained a *test-scoped* dependency on `inspecto-ops` in EDG-01 cell 7, and resolution
  covers every scope regardless of the generator's `-DincludeScope=runtime`. Verified 2026-09-09 on this
  sandbox: with `inspecto-ops` absent from `~/.m2`, Enterprise failed `Could not find artifact
  com.gamma.inspector:inspecto-ops` **both before and after** that day's module-table fix — it is not a
  regression from it — while Personal and Standard passed only because older reactor artifacts happened to
  be installed here. → Either add a reactor `mvn -DskipTests install` to `release.yml` before the packaging
  steps (simplest, and what a dev machine already does), or have the generator resolve inside a build
  phase. ⛔ Do not "fix" it by narrowing the scope filter: the failure is in **resolution**, which precedes
  filtering. Context: `docs/okf/capabilities/editions/editions.md` §5.3 item 1.

- ✅ **`MAP_AUTHORED` drift FIXED + PINNED 2026-09-09** — it was the FOURTH hand-mirrored map here to
  drift, and it is now the first to be held by a test rather than a fifth hand-edit.
  `pipeline-editable.ts` carries `fields`, and `MapNodeKeyContractTest.theClientMirrorMatchesTheServerSets`
  **parses that TypeScript file** and asserts both sets against the Java ones — the Java side is the source
  of truth, so the mirror can no longer drift silently. Mutation-proven in both languages: removing
  `fields` again fails exactly 1 of 4 Java tests naming the missing key, and exactly 1 of 74 UI tests
  (`lowers an authored fields projection instead of refusing it`, exit 1). ⚠ There is no shared artifact to
  compare against and inventing one for two short lists would cost more than it saves, so a cross-language
  pin parses the other side's source — the same idiom `MapNodeKeyContractTest` already used on RowShaper.
  🔴 **The live cost, now measured:** for four days the client REFUSED a key the server accepts (`lower`
  reported `UNSUPPORTED_MAP_KEY` and advertised `[columns, rules]` as the accepted set) and dropped it on
  the round trip — exactly the failure `MAP_AUTHORED`'s own Java comment says the constant exists to make
  impossible, reproduced on the client because the list was a hand copy.

  *The original row:* **`MAP_AUTHORED` has drifted between its two homes — the FOURTH hand-mirrored map to do so.**
  `pipeline-editable.ts:197` declares `['columns', 'rules']`; `PipelineEditable.java:89` declares
  `Set.of("columns", "rules", "fields")` — `fields` was added 2026-09-05 and the mirror was not. The TS
  comment at `:191` says outright that it "mirrors `PipelineEditable.MAP_AUTHORED`", and the client's job is
  to refuse exactly what the server refuses, so the two now disagree about whether a map node may carry
  `fields`. ⚠ Verified against both files 2026-09-08 (docs-consolidation duplication drain, group 1). ⛔ Do
  not fix by hand-editing the list a fifth time — pin it: the repo has already recorded a derived map
  drifting three times. → generate or contract-test the pair, as `RecordTransformContractTest` does for
  `sql-functions`.
- **P3** · **Two author-facing messages name things that do not exist.** Both verified 2026-09-08 in the
  same sweep. (a) `pipeline-editor.component.ts:673` warns *"Only filter, dedup and summarize Steps can run
  inside a branch"*, but the real gate `BRANCH_STEP_TYPES` (`pipeline-graph.ts:794-799`) also contains
  **`transform.sql`**, added by SQL-BRANCH-1 on 2026-09-06 — so the toast tells an author a Step cannot do
  something it can. (b) `RemoteAcquisitionHandler.java:285` logs *"Unknown source.post_action.on_success"*
  and `:299` builds an operator-facing message reading `source.post_action.on_success=…`, for a key that is
  actually **`collector.post_action`** — `post_action` is read via `castMapAt(src, "post_action")` where
  `src = castMapAt(raw, "collector")` (`PipelineConfigParser.java:376,483`). 🔴 The vocabulary guard has a
  rule for exactly this class (an operator-read message must use the canonical term) and it did not fire —
  worth checking whether the rule covers the Source→Collector case at all, not just Flow→Pipeline. <!-- vocab-allow: names both renames themselves -->
- **P3** · **Vocabulary rollout, Tier 3 — the release-gated remainder.** The **UI half SHIPPED 2026-09-07**:
  every emitter was verified to dual-emit (`LineageRoutes:112/113`, `ViewRoutes:100/101`,
  `PipelineProjection:198/207/242`), so the DTO fields now read the canonical key — `DownstreamPipeline.pipeline`,
  `PipelineViewSummary.pipeline`, `CombinedNode.pipeline` / `CombinedEdge.pipeline` / `PipelineCombined.pipelines`.
  What remains is **wire and needs the MAJOR** (§2 release notes): dropping `flow` from the Java JSON
  (`ViewDefinition.toMap:52`, `LineageRoutes:113`, `PipelineProjection:199/208/243`) and renaming the
  `ViewDefinition.flow` record component, which carries `@PublicApi(since="4.0.0")` — GLOSSARY §13 already
  puts an `@PublicApi` member rename at Tier 2 as "a tier boundary on its own". ⛔ Do **not** rename the
  `"flow"` test literals: `ViewStoreTest:66/71` and `ControlApiViewsTest:98` are the only proof the dual-read
  compatibility path works, and `PipelineJobRunnerTest`/`JobServiceTest` cover the legacy job param. The
  agent-tool `flow` argument stays until it dual-accepts (`SOURCE_ALLOW` records it as tracked debt).
  ⚠ When the dual-emit ends, delete `CONFIG_ALLOW['spaces/ucc/config/views/sites_active_view.toon::flow-key']`
  — the guard's stale-allowlist rule fails the build until you do, which is the rename announcing itself.
  → `GLOSSARY.md` §13 · `PROJECT_NOTES.md`


### Filed from the 17-spec consolidation, 2026-09-09 (Sprint 2)

These four are the **classes** behind roughly half of the consolidation's findings. Each is one guard, not N
fixes — that is the point, and it is Sprint 3 of `superpower/post-consolidation-sprints.md`.

- ✅ **`SPEC-STALEREF-1` — the class is CLOSED 2026-09-09; the guard is `tools/check-doc-citations.mjs`.**
  Committed and wired into `ci.yml` and `.githooks/pre-push`, falsified in both directions (a seeded dead
  path and a seeded bare old type name each failed with their line number; each allow-rule turned the same
  line green; the clean corpus passed; a run from the wrong directory FAILED on its emptiness floor rather
  than passing over nothing). 🔴 **It found 152 stale citations, not 23** — 39 dead paths and 113 dead type
  citations across 37 files, all repaired in the same commit. Three lessons worth more than the fixes:
  **(a)** the first design — "every backticked CamelCase name must exist in the tree" — was killed by
  measurement: 4,654 such citations, 56 distinct absences, and most were legitimate (third-party types,
  deliberately-unbuilt designs, renames recorded on purpose), so it would have shipped as ~45 entries of
  allowlist and 11 of rule. The guard narrowed to the one objective invariant available: the rename map the
  repo itself commits, **parsed** from `tools/rename-batch-to-consignment.mjs`, never mirrored.
  **(b)** Both checks needed an *allow* rule, and the same one twice: a dead path is fine on a line that
  states the absence, an old name is fine on a line that also names its replacement — that is what
  RECORDING history looks like, and it is what let the guard ship with **no waiver list at all**.
  **(c)** ⛔ **A rename codemod over prose inverts any sentence whose subject is the old name's absence.**
  The bulk repair rewrote "the phantom `BatchGraphRunner`" into "the phantom `ConsignmentGraphRunner`" —
  the live class — in this very row, and one more like it; both were caught on diff review and restored.
  The codemod's own header had warned of exactly this ("DOCS ARE NOT SWEPT … the canon carries deliberate
  history that a rename would falsify"). Read the diff, not just the guard's exit code.

  *The original row, kept because it names the instances:* **twenty-plus dead class citations** surviving the
  2026-08-31 Consignment rename (five of them in the active pipeline plan's "what actually runs" section, two
  cited *by line number*); a **deleted component named as the current mapping UI**; every `RowShaper` line
  citation wrong **and its package path wrong**; `pipeline-graph-design.md` §14 cited when the file has eleven
  sections; `BUNDLE-SCHEMA-1` cited as a §6 row by two current docs and present only in an archive snapshot;
  `docs/okf/agentic/` pointing at a local path that does not exist; a dead archive pointer; and a tracked hook
  comment naming a hook that does not exist. 🔴 **The fix is one guard**: a citation check over each
  document's backticked symbols and paths against `git ls-files` and the module tree — which is exactly the
  §7 pointer check every capability spec already describes, and which caught the phantom `BatchGraphRunner` (the live class is `ConsignmentGraphRunner`)
  the moment it ran. Committing that checker is the enabler. → `okf/capabilities/tooling/tooling.md` §5.
- **P2** · **`SPEC-COUNTS-1` — eight facts, each counted two to six ways, and the narrative doc is wrong every
  time.** Measured 2026-09-09: builtin node types **30** (docs said 20/28/20/29 — five ways); parser frontends
  **six ways** (3/5/6/7/9/10 across five pages and the user guide); maintenance tasks **19+4** (docs said
  4/16/13, and the *served descriptor* was one of the wrong ones — fixed `f0e4dee2`); job types **12** (docs
  said 4/9/4/10); transform functions **23** (24, and "~20" in the same file); processors **119** (121); the
  dependency count **95 across 25 modules** generated against **94** in four prose sites; and the staged-jar
  set stated in **eight** places with five wrong. ⚠ In every case the *generated or catalogued* artifact
  was right. **The fix is to cite the generated file, and to add a counting guard only where no generated
  artifact exists.**
  ✅ **CLASS CLOSED 2026-09-09 for every count a contract owns** — `tools/check-doc-counts.mjs`, wired
  into `ci.yml` + `.githooks/pre-push`, falsified in four directions. Twenty statements across eleven
  documents now carry a `<!--count:ID-->` marker and are **derived** from the owning contract at run
  time: processors **119** (8 sites), step types **16** (5), node types **30** (3, from the
  `BuiltinNodeType` enum — no contract owns the roster), SQL mapping functions **23** (2), processor
  families **8**, and node types carrying an attribute spec **11**.
  🔴 **The ambiguity was the root cause, not the arithmetic.** "Node types" denoted THREE sets — 30 in
  the enum, 11 in `node-attributes.contract.json`, 16 in `step-types.contract.json` — so a single
  number could not be right; that distinction is now stated in `pipeline-editor.md`. Likewise
  "transform functions" denotes two unrelated registries (23 SQL mapping vs 30 ASN vendor), which is
  why the marker id is `sql-mapping-functions` and not the noun.
  ⛔ **`job types` and `maintenance tasks` are deliberately NOT guarded**, and this is a measurement
  result: both are assembled from a built-in list plus `ServiceLoader` discovery, so both totals are
  **edition-dependent** (job types 10 on Personal, 12 with `inspecto-ops`; maintenance 20 built-in ids
  across 19 switch arms plus 4 contributed). "The count" does not exist until the classpath is fixed,
  so a guard asserting one number would assert a falsehood in the name of ending wrong counts. A doc
  stating either must say which shape it means — a writing rule, not something a guard can settle.
  ✅ **Parser frontends CLOSED 2026-09-09** — the "six ways" were FOUR sets sharing one noun: **10**<!--count:parsing-frontend-tokens-->
  `parsing.frontend` tokens (`PipelineConfigParser.FRONTENDS`), **6**<!--count:builtin-parsers--> DuckDB-native built-ins
  (`BuiltinParsers.IDS`), **7**<!--count:parser-node-types--> `parser.*` node types (`step-types.contract.json`), and three
  byte→row *mechanisms* — a prose taxonomy with no owner in code, so written as *mechanisms* and not guarded.
  "Eight formats" (tokens minus two aliases) is not derivable without mirroring the alias pairs, so prose binds
  it to the marked ten. → `okf/capabilities/ingestion/ingestion.md` §3.3 / §7.
  ⚠ Still hand-typed and unguarded, for want of a generated artifact: the dependency count and the
  staged-jar set. → `tools/check-doc-counts.mjs` · the owning specs' §2
  tables, which carry the measured number.
- **P3** · **`SPEC-DEADSEAM-1` — four declared seams with no implementation or no caller.**
  `ExpressionProvider` has no registration in any module; `DatasetRelation.temporalColumn` has no caller; a
  vendor-transform plugin registers **30** legacy functions through a real seam (🔴 this said "~40" until 2026-09-09; counted from `LegacyVendorFunctions`' 30 `f.put(` registrations — ⚠ and note this is a DIFFERENT set from the 23 SQL mapping functions, which is why `check-doc-counts.mjs` names its id `sql-mapping-functions` rather than the ambiguous noun) and **reaches no bundle and no
  document**; `AssistDialog` is dead code whose doc comment describes an unwired flow. Each needs a
  keep-or-delete verdict, not a build. ⛔ Demand-gated: do not "tidy" them without one, because at
  least one (the vendor plugin) may be deliberately operator-side.
## 5. Docs & hygiene

- **Doc-lifecycle violations** (shipped work still in `docs/superpower/`; the rule is distil → `git mv` to
  `plans-archive/` → update `INDEX.md`). Re-grounded 2026-09-07 — **two of the four listed rows were wrong**:
  - ✅ `living-operational-system.md` — **DISTILLED + ARCHIVED 2026-09-07.** The north star is now the OKF
    concept `okf/living-operational-system.md`; every citation (`GLOSSARY.md`, `REQUIREMENTS.md` ×2, this
    page) was repointed in the same change. 🔴 Its "what exists today" column was deliberately **not**
    carried — it described gaps its own R4/R5 slices had closed and cited two files deleted with the mock
    backend, which is the general lesson: a north star states shape, a state column rots.
  - ✅ `consignment-elt-architecture.md` — **DISTILLED + ARCHIVED 2026-09-07**, and **completeness-KPI K5 is
    discharged with it**. 🔴 K5's filed blocker was itself wrong: §11.4 had carried a SUPERSEDED banner since
    `51ca57f7`, the same commit as §8's. The real debt was the ~12 OTHER sites still reading as live sealing
    design (§8.4's SLA object — which §8's own banner never named — §9.3's `sealed-complete` baseline rule,
    §10.2's seal-policy 422, §11.5's `partition.sealed` signals, §11.6's two rows, §13's three, the header
    status table). Discharged by ONE authoritative archive banner enumerating every superseded site, which is
    more robust than a dozen scattered edits that can each miss one. Thirteen durable facts distilled into six
    OKF concepts.
  - ~~`compliance-certifications-plan.md`~~ — **NOT a violation.** Only C2 of six workstreams is delivered; C1/C3/C5/C6
    are open and org-gated (§2). It stays live; `INDEX.md` already records the C2 half correctly.
  - ~~`step-workbench-design.md`~~ — **was already archived 2026-09-06.** Row was doubly stale (file moved; decision made).
  - ✅ `pipeline-spec.md` + `pipeline-waves-drain-plan.md` + `elt-final-amendment-plan.md` — **ARCHIVED
    2026-09-10** (operator: archive now, after a distillation diff; row 15 stays on this board). ⚠ The
    earlier rule here — *archive together when Row 15 closes* — was superseded: a plan is archived when its
    durable content is distilled, not when the last release gate clears.
  - ✅ `gate-register.md` — **ARCHIVED 2026-09-07.** Its own retirement trigger had fired and it had become
    actively misleading (§3.5 and §3.3 still framed items resolved weeks earlier as open calls). Its one durable
    note is now `okf/index.md` §*How to read this tier*.
- ✅ **`INDEX.md` CONSOLIDATED 2026-09-07** — 879 lines → 150. It had become an archive log: **46
  struck-through per-plan narratives (443 lines)** for plans already in `plans-archive/`, under a heading
  saying plans live there only while active, against 12 real ones. The narratives are frozen in
  `archived-documents/index-snapshot-2026-09-07.md`; the part worth keeping — which OKF concept each
  archived plan's truth went into — is now a 46-row routing table. Verified no live-doc pointer was lost
  (every dropped link is reachable from its own OKF sub-index). The `DOC_ALLOW['docs/INDEX.md::bare-flow']`
  waiver was **deleted rather than kept** — the guard's own preferred outcome — and the removal was
  falsified in both directions (the guard fires on a bare banned word in INDEX, and passes when clean).
- **REQUIREMENTS MoSCoW / edition columns** — §3.1 ACQ-4, §3.9 SPC-5 and §3.15 UI-8 were **fixed 2026-09-07**
  (all three were contradicted by their own §5 and by the code; UI-8 had read "IN-FLIGHT, uncommitted, another
  session" for two months over a pane that shipped 2026-07-07). An authority note now says `EDITIONS.md`'s matrix
  wins for the Edition column. Still open, same root cause — the column predates the 2026-09-02 "not for Personal"
  decisions: ~~SEC-8~~ (reconciled 2026-09-08 — `okf/capabilities/security/security.md` §2 owns the row),
  ~~**OPS-2**~~ (reconciled 2026-09-08 — `okf/capabilities/observability/observability.md` §2 owns the row; its cell said `S/E`, not `All`), ~~**INV-2**~~ (reconciled 2026-09-08 — `okf/capabilities/studio/studio.md` §2 owns the row; like `OPS-2` its cell said `S/E`, not `All`, and EDITIONS `CP-09` had already gated it; ~~INC-2/3/4~~ reconciled 2026-09-08 — `okf/capabilities/incidents/incidents.md` §2 owns those rows), and ~~**DAT-6** wants a caveat that
  the multi-user half is unbuilt~~ (the caveat is in the REQUIREMENTS cell; retired 2026-09-08). ⚠ ACQ-4's *other* half is unresolved and needs grounding, not a doc edit:
  EDITIONS' generated board marks `SP-ACQ-06`/`SP-ACQ-08` (S3/GCS) planned while the **connectors** ship with tests
  — check whether the *Step processor* exists before flipping `ProcessorCatalog`, because a connector is not a Step.
  → `REQUIREMENTS.md` · `EDITIONS.md`
- **Compliance repo-side artifacts (moved out of §2, 2026-09-07)** — these are file-existence checks, not
  external gates, and sitting in "externally gated" made them look unactionable: the **customer
  verification runbook** (G2 half), the **CI-evidence doc**, a **recorded restore drill** (G6 — the
  statement exists at `compliance/evidence/rto-rpo-statement.md` with operator-fill targets and an empty
  drill table), **G8** RBAC R5 evidence and **G9** FIPS. Each closes when its file says so.
  → `compliance/controls-matrix.md` §4
- **Template seed-pack enrichment (frontend C7)** — ongoing, not a discrete item: `kpi-overview`,
  `quality-monitor`, `trend-monitor` today. → `okf/frontend/features/studio.md`
- **GRAPHIFY-1 tool sync** — ⚠ the row's own check is blind: `.graphify_version` and `graphify --version` both
  read `0.9.53` while `.claude/skills/graphify/SKILL.md` differs from the installed package's copy by ~300 lines
  (the repo copy carries a uv/pipx detection block labelled "fixes #831" that 0.9.53 does not). Either re-sync from
  the package or record why the repo copy deliberately diverges — comparing version markers will never tell you.
  (The optional `graphifyy[sql]` half is **already satisfied** — `tree-sitter-sql 0.3.11` is installed.)


### Filed from the 17-spec consolidation, 2026-09-09 (Sprint 2)

- **P2** · **`SPEC-MOCKRESIDUE-1` — the deleted mock backend's documentation residue is repo-wide and
  unowned.** **Twenty current-tier documents** still describe the offline mock layer that was deleted
  2026-08-31, and the residue **spans areas**, so each spec filed it and none owns it: the Studio spec filed
  it, the Surfaces spec filed the ripple as unfinished, the Assistant spec found its own instance. Two of
  those documents tell a contributor to **edit a file that does not exist** — one of them as the definition of
  done for adding a page. → one sweep, one owner; the count is the acceptance test.
  ✅ **CLOSED 2026-09-10 (Sprint 7.4).** 🔴 **The count was 27, not twenty** — the row undercounted by seven,
  and the residue reached three places a doc sweep would have missed: two buyer-facing `stakeholders/` pages
  (one asserting the UI "is developable and demoable with no backend"), the `frontend-explorer` **agent
  definition** in `.claude/`, and `REQUIREMENTS.md`'s `MOCK-FIRST` **status value**, still legal in the
  legend and cited by no row (retired). 🔴 **The row's own "two documents" clause was STALE** — the pair it
  meant (the architecture page and the routing convention, both naming a deleted nav data file) was repaired
  on 2026-09-08 in `82cbed96`. Two *different* instruct-to-edit-a-missing-file cases were live and are now
  fixed: `tags.md` told a contributor to widen `TAG_TARGET_KINDS` alongside `AnnotationKinds.KINDS`, a
  constant that exists nowhere (widening is a one-site change now), and `inline-ai-authoring.md` made
  "add the term to `agent.handler.ts`" the definition of done for declaring a glossary term — **120 lines
  below that same page's own "Offline — GONE" banner**. ⚠ **The stated acceptance test cannot be met
  literally and was refined**: `git grep mock` must return only (a) the archive tier, (b) an explicit
  removal or history record, (c) the capability specs' own gap rows — which *are* the finding — and (d)
  `okf/frontend/log.md`, a dated journal whose entries are true as of their heading; that file now says so
  in a tier banner, because an unstated tier is an unaudited one. Every lesson learned on the mock was kept
  and re-pointed at its general form (*a stand-in more lenient than the server is worse than no stand-in*),
  rather than deleted with the subsystem.
- **P2** · **`SPEC-PLANSTALE-1` — active plans stale against their own content.** ✅ **The compliance plan's clause is DISCHARGED 2026-09-09 by archiving it** — which is the resolution this row itself named ("before step 7 of the consolidation moves it"). Its three defects (three closed gaps called open, one "confirmed still a gap"; a pipeline accessibility step that does not exist; four declared
  subdirectories of which three are absent) are now frozen as provenance behind an ARCHIVED banner that
  enumerates them, and its durable content is in `okf/capabilities/compliance/compliance.md` §3.10.
  ⚠ What remains open here is the OTHER plan. The pipeline spec's "what actually runs"
  names five dead classes and says a new Step type cannot be added, contradicted by its own later row. The
  waves plan's conclusion contradicted its own table (fixed 2026-09-09, `2cd1661b`). ⚠ A plan in
  `superpower/` is the *design of record* for in-flight work, so a stale one is worse than a stale concept
  page. → each plan's own header, before step 7 of the consolidation moves it.
  ✅ **CLOSED 2026-09-10 (Sprint 7.5), corrected before the plans move.** The dead classes were **four, not
  five** — `BatchProcessor`→`ConsignmentIngestor`, `BatchIngestStrategy`→`ConsignmentIngestStrategy`,
  `CsvBatchStrategy`→`CsvIngestStrategy`, `StreamingPluginBatchStrategy`→`StreamingPluginIngestStrategy`,
  over six mentions plus `graphLaneCarries`'s owner — and they had been dead for **ten days** in the same
  document whose §12 records the rename that killed them. 🔴 **The "new Step type" claim was wrong in every
  clause, not just one**: a hot-deployed pack jar *can* add a Step type (`JobPackManager` →
  `PipelineNodeTypes.register(type, owner)`, the spec's own **gap 7**, shipped), and "the pack path is gated
  behind unshipped platform-services work" was false. The correction also records the asymmetry the plan
  never stated: a **pack may not redefine a built-in** (refused, and the whole pack rejected) while a
  **classpath provider may** — an edition specialising the core at build time. 🔴 **A third defect this row
  never named, and the worst of the three: a FALSE ✅ on a release gate's precondition.** Both plans recorded
  §6 step 1's converter as existing; `inspecto migrate-configs` is in no source file, and the commit they
  cited was a `RecipeConverter` projection fix. Steps 1 and 2 are now on row 15. ⛔ Nobody re-checks a tick,
  so a false ✅ is worse than a blank.
- **P2** · **`SPEC-GLOSSARY-1` — five load-bearing words undefined, or defined twice.** `Segment` (the
  plugin-ingest spine) has no entry; `Control` and `Evidence` — the two nouns every compliance sentence turns
  on — are undefined while `Compliance` is defined; **"Case" means two different things one layer apart** (the
  agent's 256-entry investigation ring and the operational object); the field-classification vocabulary
  (PII/INTERNAL) has no owner and is a free string in tests and the UI; and two `Stream` read models sit under
  one word. 🔴 The glossary is **binding** (`CLAUDE.md`), so this is not cosmetic: one word meaning two
  things is the exact failure its ban list exists to prevent. → `docs/GLOSSARY.md`.
  ✅ **CLOSED 2026-09-10 (Sprint 7.3).** Six entries written — **Segment**, **Field Classification**,
  **Streaming Ingest Mode**, **Investigation Case**, **Compliance Control**, **Compliance Evidence**,
  **Tool Evidence** — plus ⛔ boundary lines on the `Stream` and `Case` entries. 🔴 **Three of the five
  words had MORE senses than this row claimed**: `Control` has five live uses (Control API · Compliance
  Control · the `CTL` Step family · the `NodeAttribute` widget vocabulary · a viz config key), `Stream`
  five, `Case` four — a two-way collision is the floor, not the finding. Two factual errors fell out of
  the grounding and are fixed in the same change: §14 called `CMP-01…03` "the shipped controls" when
  `EDITIONS.md` has **eight** such rows and they are edition *feature* rows, not control ids (the matrix
  contains no `CMP-nn` at all); and the **Watermark** entry said "completeness for one stream" when the
  code keys it on the **output table** — the entry asserted completeness of a Catalog Stream, which is not
  what is computed. 🔴 A sixth collision the row never named: `INTERNAL` is both a classification label
  and an API error code. The one thing 7.3 did **not** do is rename anything — the `Case` split is code
  over four published routes, filed as `GLOSSARY-CASE-1` (§3) with a §13 touchpoint list.
- **P3** · **`SPEC-ORPHANPAGE-1` — shipped surfaces with no concept page.** Roughly twenty across areas: nine
  panes in the shell tier (two of them the very rows that area owns), eleven shared components and six shared
  libraries, three Ops Lens screens (audit log, processing status, the scheduler), the Notification Center,
  the Catalog read model (`com.gamma.catalog`, mentioned by eight files and owned by none), the operational
  objects domain, and a guarantees panel whose own docblock cites a plan its page does not link. ⚠ Filed
  as P3 deliberately — an undocumented pane is a smaller problem than a *wrongly* documented one, and
  `SPEC-STALEREF-1` is the same budget better spent.
## 6. Standing refusals and won't-do (not work — keep so nobody re-files)

One line each; the reasoning is in the pointer. Reopen only on the stated trigger.
*(Triggers audited 2026-09-07 — every countable one was recounted against the code; none had fired.)*

- **Mapping sidecars for the committed schemas** — ⛔ decided 2026-09-10: inline `mapping:` is the norm; **0** of 24
  schemas use a `*_mapping.csv` and nothing depends on one (the single sidecar in the tree is untracked evidence).
  Trigger: an operator picks the sidecar form in the mapping editor for a committed pipeline. → `MAPPING-GEN-1` (§3)
- **`quality.schema.drift` refusing a file** — ⛔ decided 2026-09-10: **detection only**. A width change already
  rejects rows or quarantines; refusing a header renamed at equal width would block feeds that parse fine. Trigger:
  an operator asks for a refuse policy by name. → `okf/backend/pipeline-graph/step-catalog.md` DQ row

- **ARCH-OPS-SCC** LEAVE (85-file ripple — recounted 2026-09-07, still **exactly** 85) · **ARCH-F-CARVEOUT**
  LEAVE (148 refs / 28 files — recounted, 27 files, module split unchanged) · `{etl, etl.unpack}` and
  `{agent.kernel.*}` SCCs LEAVE · intra-module `ops↔ops.link/workflow`, `catalog↔catalog.spi` cycles are
  same-family · **M2** `CollectorService` decomposition → `okf/backend/modules/reactor.md`
- **C2** store-pair base — reopen at the **7th** store; recounted 2026-09-07: **4 true `InMemory*`/`Db*` pairs**
  (Object, Link, Note, TagAssignment), 5 counting `DbStatusStore` by shape. Not close. · **C4** BOM — reopen on
  an external consumer; there is none and **nothing is published as a Maven artifact** (releases ship zip
  bundles; eoiagent is an upstream dependency, not a consumer). · **C6** connection reuse — warm open **24 ms**
  (min 23 / max 27, n=20), no contradicting measurement exists. ✅ **HOMED 2026-09-10 (Sprint 7.2)** in
  [`okf/capabilities/editions/editions.md`](okf/capabilities/editions/editions.md) §6.1 — they were archived
  without being distilled, and `reactor.md`, which this row pointed at, never held them. 🔴 **Two counts here
  were wrong and are corrected at the new home:** `C2` is **half shipped** — the `Db*` side landed 2026-08-18
  as `AbstractJdbcStore` (`JAVA-5`), *20 days before* the recount that called it untouched, with **five**
  subclasses; and the true-pair count is **5**, not 4 (`DeliveryReceiptStore` became a pair on 2026-09-07, the
  recount's own date). What is actually left is the **`InMemory*` half** — all ten implement their interface
  with no shared base. ⛔ Anchor any future reading on the item's TEXT: that plan uses `C2` for two different
  items and `reactor.md` uses `C2`/`C4` for unrelated things.
- **PATH-2 residual** (moved from §4 2026-09-07 — it is a LEAVE, not work) — the `BackupTask.restore` zip-slip
  jail is PINNED by `MaintenanceLibraryTest.restoreRefusesAnArchiveEntryThatEscapesTheTargetBeforeWritingAnything`
  (the page cited a `…ASidecarEntry…` variant that does not exist — a tampered sidecar is refused a layer
  earlier). Family (a), the three store `fileFor` helpers — `ViewStore:100`, `PipelineStore:101`, `ComponentStore:351`
  and `PipelineWatermarkStore:56` — **four sites, not three** (recounted 2026-09-07), none importing `PathJail` — LEAVE unless someone is in those files anyway; their line
  numbers have now drifted three times, which is itself the argument. ⛔ Routing `ControlApi.serveStatic:866`
  through `PathJail.contains` is a posture change needing an operator call — grounded 2026-08-26 "do not build it".
  → `okf/backend/config/config-safety.md`
- **Unpack (10) crash mid-archive** (moved from §4) — re-ingests committed members; relies on
  `OVERWRITE_OR_IGNORE` idempotence (`PartitionWriter:186`, documented at `UnpackOrigins:32` and
  `ConsignmentIngestor:284/508`). By design; revisit only with a measured cost. ⚠ X1's `CommitRetry` does **not**
  cover this — it records only a *returned* FAILED, and a crash writes no attempt record.
  → `okf/backend/engine/unpack-stage.md`
- **`inspecto-query`/`inspecto-job`/`inspecto-enrich` module extraction** (moved from §4; the row said
  `fp-*`, stale since the artifactIds became `inspecto-*`) — build only on explicit request. Measured
  2026-09-07: `job` → `signal` + `ops`, but **`query` → `signal` only** and **`enrich` → neither**, so the
  old "`query`/`job` depend on `signal` + `ops`" claim was over-broad and `enrich` is the one clean
  candidate. `SharedDottedPathGrammarTest` still up-imports `notify` and would still need cutting.
  → `okf/backend/modules/reactor.md`
- **Vocabulary: the living-system terms are ADOPTED, not proposed** (row corrected 2026-09-07) — `GLOSSARY.md`
  already carries **Signal**, **Consequence**, **Decision Engine** and **Result Set** as binding (the last three
  annotated "§6-proposed → binding (R5)"). Only *Query* and *Parameter* were never formally adopted; use them
  as ordinary words, not as capitalized concepts. → `okf/living-operational-system.md` §Vocabulary
- **PKG-5** agent-absent is the intended shipped default; ⛔ no `package.ps1` switch until the JDK 25+ vs Java 24+ floor is resolved → `okf/backend/build-run/build-test.md`
- **D11 caps** — `max_temp_directory_size` gets no default; preview/dry-run connections stay uncapped; the semaphore-computed cap is rejected → `okf/backend/engine/duckdb.md`
- **D7 startup backfill** full object scan (`ObjectService.backfillTagAssignments:479`, called from
  `CollectorService:475`) — deliberately unfixed. ⚠ **The stated trigger cannot fire as written**: "shows up in
  measured startup time", but nothing in the repo measures startup — no JMH, no timing test, no recorded
  baseline. Reopening it means *first* adding a startup measurement. → `okf/backend/control-plane/tags.md`
- **MNT-14** — no UI surface / no shipped Job instance (operator opts in); retention derived not stamped; scoped to `ObjectType.INCIDENT`; ⚠ `ObjectQuery`'s 9-arg constructor is load-bearing → `okf/backend/control-plane/jobs.md`
- **`transform.merge` attributes — REFUSED 2026-09-07, the same day MERGE-ATTRS-1 was filed as a defect.**
  The row was right that `RowShaper.merge` reads `type` (`union`|`inner`|`left`) and `on` off the node config
  and that `NodeAttributes` declares neither. Its **cause and severity were both wrong.** `transform.merge`
  is absent from `PipelineEditable.LOWERABLE` and from `RECIPE_VERBS` **by decision** — the same set as
  `transform.split`/`select`/`derive`/`validate`, and `PipelineEditable:196` says admitting them would
  "silently reverse all of those". A graph carrying one therefore **refuses at save** with
  `UNSUPPORTED_NODE` (422; pinned by `ControlApiPipelineCrudTest` on the sibling `transform.derive`), and
  neither `PipelineJobRunner` graph source can carry one: the flat `pipeline_config:` path has no home for
  it, and the `pipeline:` path reads the store that refused it. So the node is executable code with **no
  authoring or persistence route at all** — it is not "stuck on union", it is unreachable. ⛔ Declaring
  attributes would hand a config pane to a node that cannot be saved, which is the exact defect the
  `transform.sql` flat-config-home lesson records. The authorable successor is the PLANNED Step Processor
  `transform.join.merge`, whose catalog entry already calls `transform.merge` "the grandfathered … read-only
  ancestor". Reopen only as an operator decision to make merge authorable — that is the four-registration
  recipe plus a deliberate reversal of a standing refusal, not a one-line attribute table.
- **`mail.send` has no true CC** — needs a CC-aware `@PublicApi` SPI overload, not until a second caller asks; ⛔ never a second SMTP transport
- **WRITE-1** — the implicit adoption ambiguity stays, documented at the code; ⛔ never teach the server the UI slug rule
- **`AiDraft.prerequisites` shared applier** — single producer; extract only when a second tool gains prerequisites
- **AGT-6a tool `args` runtime validation** declined (contract test instead) — revisit after all **23** tool
  schemas are audited; still 23 (`InspectoPackTest:61` pins the count) and the audit has **not** run: the
  2026-07-27 cross-adopter pass covers **5 of 23** — its 6 payloads span 5 distinct tools (`query_author`
  twice), and the test asserts nothing about its own list's size. Precondition unmet.
- **AGT-5 embedding recall** parked (`CaseStore` is a 256-cap ring)
- **D8** digest deliveries correlate to the digest, not per notification; `deliverWithReceipt` escape hatch only if a provider won't echo `Message-ID`
- **Time zone of incoming data (a)** — no editor for `raw.fields[].timezone_column` by decision; a fifth "data offset wins" tier is a separate build; ⛔ never reached by relaxing the `%z`/`%Z` gate → `okf/backend/engine/duckdb.md`
- **Unpack (5)(8)** — a partial archive never fails its Consignment; nested archives refused (`depth` = 1); with `processing.unpack.enabled: false` the same-file engine divergence returns (operator opt-out)
- **Collector rename residual** — the pipeline TOON `source:` block stays (renaming breaks authored TOON); `'SOURCE'` stage category unchanged → `okf/backend/gotchas/cross-cutting.md`
- **Geo map** — DuckDB `spatial` extension deferred (zero `ST_*` demand); progressive loading obsoleted by `GEO_POINT_CAP = 5000` → `okf/frontend/features/geo-map.md`
- **Catalog** — offline `/db/query` returns 501 (honest degrade); `EntityProjectionGraphSource`, Geo point/route sources, `ReconExecService` stay offline arms; ⛔ "backfill the `table` attr" REFUTED — do not re-file → `okf/frontend/features/catalog.md`
- **`endSessionUrl` / server-published OIDC config** — not buildable as scoped; 🔴 if ever built, `session.service.ts` uses `??` so a server-sent empty string beats `environment.oidc` → `okf/backend/editions/auth-security.md`
- **The connector sidecar is NOT edition-gated** (CONNECTORS-BUNDLE-1, shipped 2026-09-07) — remote
  acquisition is a core capability and `EDITIONS.md` marks SFTP shipped in all three editions, so
  `inspecto-connectors.jar` rides every bundle. ⚠ It costs ~32 MB (BouncyCastle via sshj, plus
  kafka-clients). Reopen ONLY if Personal must be leaner than that — the copy is one `if` in
  `package.ps1`, but gating it means correcting the SP-ACQ rows to match.
- **`mail.send` returns SUCCESS when no channel is configured** — `JobResult.ok("no email channel
  configured — nothing sent")`. Deliberate and already in the pending-MAJOR release notes; ⛔ do not
  "fix" it to a failure without an operator call, but know that a scheduled mail job reads green
  while delivering nothing.
- **Kafka is not the data path** — decided in the consignment-ELT design and never re-opened: urgency is a *parameter on one node*, not a second execution model. ⛔ Do not re-file "add a Kafka lane"; a Kafka **Collector** (SP-ACQ-09) is a different, open question.
- **EXPR-1** — expression interpolation inside a longer string only ever per-declaration opt-in, never global
- **BUNDLE-1 perf question** — `no-cache` on content-hashed chunks vs `immutable`; unmeasured; only if a revalidation storm is observed
- **Decided 2026-09-06, keep as designed:** the fetch lane stays FIFO (revisit on the first observed fetch-lane wait) · `requireTopLevelSinks` is a depth rule, not a jail · bounce/complaint handling stays manual until receipts persist · JAVA-SIMP-2 stops at seam #2 (no defect hangs on the sink casts) · the `batch_id` trio rides the MAJOR (release notes hold it) · D-7 `materialized` is done-by-absence · **Postgres multi-user is PARKED** until a multi-operator install exists · unpack roll-up + entry grain ratified · SEC-07 Vault/KMS only when a client policy requires it · deployment D1–D8 signed as recommended (🔴 **D3 was signed as a 2GB default that DOES NOT EXIST** — corrected 2026-09-09; `DuckDbUtil.memoryLimit(null)` returns `null`, no `scheduler.toon` ships, and this file's own GAP-4 row says so. See `okf/capabilities/editions/editions.md` §5.3 and `okf/capabilities/pipeline-execution/pipeline-execution.md` §2.4).
- **Working as designed** (from the archived gate register §5): write-root 503 · `ConfigSafetyValidator` 422 · `PathJail` 403 · 409 conflict · `ExpressionGuard` · `SqlGuard` · BI share tokens · active-pipeline delete refusal · Incident resolution backend-gated · editions = build flavors · `AuditTrail` has no *authentication* events — sign-in/sign-out are not audited; *authorization* decisions ARE (`access.denied`/`access.granted`, ABAC A5) (⚠ disclose the first half; corrected 2026-09-08) · air-gap CI · append-only registry · manifest owns existence · nothing prunes by default · 🔴 ~~`-Djobs.maxConcurrentRuns` is the only bound~~ **STALE — refuted by D11 and by a second bound** (corrected 2026-09-09): the Run cap is **ON by default at 4** in code (`JobService.DEFAULT_MAX_CONCURRENT_RUNS`), owned by `scheduler.toon`, with the flag a bootstrap default only; and the INGEST engine has its **own** semaphore plus a per-pipeline `PipelineRunGuard`. Two bounds, neither governed solely by that flag — owner §2.5/§3.3 · refused: Spring/Quarkus, distributed-by-default, per-record lineage, Lens-as-permission, PIP-1, sink-owned `partitions`, Decision-Rule + `route:`, raw `Connection`, `CREATE MACRO` outside AUTHORING-REDESIGN-1 (d), step-workbench S3.

## 7. Duplicate map (same work, several names — update all when closing)

*(Rebuilt 2026-09-07. The previous table carried six dead aliases, two canonical rows pointing at the now-empty
§1, and four duplicate pairs it never recorded. A dead alias is worse than none: it makes a closed row look open.)*

| Canonical row | Also appears as |
|---|---|
| Row 15 — ELT Phase 6 deletion half (§2) | pipeline-spec §12 row 15 · Platform Services Stage 2 precondition (§3) · 🔴 ~~`BatchGraphRunner` parity blocker~~ **THAT CLASS DOES NOT EXIST** (the live one is `ConsignmentGraphRunner`) (corrected 2026-09-09 — see §3) · §5 "archive pipeline-spec + waves-plan when Row 15 closes" |
| D13 parser field tiers (§2) | `archived-documents/plans-archive/parser-field-tiers-interview-plan.md` (the interview-#2 kit, archived 2026-09-09) · `okf/frontend/features/grammar-config.md` (the pre-agreed analysis rule) |
| AGT-5 `DryRunProvider` (§2) | AGT-6b row (§2) · `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2 |
| EXECUTION-RESIDUALS X4 record-level replay (§3) | `okf/frontend/features/run-detail.md` + `USER_GUIDE.md` "reprocess is whole-batch only" · `INDEX.md`'s `EXECUTION-RESIDUALS-SKETCHES` pointer (which cites §4 — the row is in §3) |
| `batch_id` rename trio — §6 (decided: rides the MAJOR) + §2 release notes | `okf/backend/control-plane/api-stability.md` §Release notes (D-12) · `archived-documents/plans-archive/consignment-elt-architecture.md` deferred renames · `elt-final-amendment-plan.md` D-12 / Phase 7 · `okf/backend/engine/db-layer.md` (cites §4 — no such row) |
| X-Actor full removal (§2) | `okf/backend/editions/auth-security.md` §Still-open · `EDITIONS.md` SEC-11 · `REQUIREMENTS.md` R4. 🔴 The §2 row's stated gate ("the API-v1 sunset") names apparatus **deleted 2026-07-25**, and `api-v1.md` never mentions X-Actor — re-state the gate before working it |
| Completeness KPI hold (§2) | Completeness KPI K2/K4/K5 (§3) · `archived-documents/plans-archive/completeness-kpi-plan.md` |
| Compliance program NFR-7 (§2) | SOC 2 Type II window (§2) — the same observation window, twice in one table |
| Deployment topology live validation (§2) | Deployment topology gaps (§3) · §6 "D1–D8 signed as recommended" — the §3 row's gate is already discharged |
| Postgres multi-user — §6 PARKED | §3 Postgres multi-user row — now says PARKED and points at `plans-archive/` (this row's "contradicts §6 / dead pointer" note was stale by 2026-09-08); `EDITIONS.md` OPS-03 |

**Deleted 2026-09-07:** *Three disagreeing name rules* — resolved **2026-08-17**
(`okf/backend/control-plane/pipeline-identity.md`), not on 2026-09-06, and all three of its aliases were dead or
stale. Its live successor is the new §3 row **NAME-DIRS-1**.

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays authoritative),
then **delete the row here**. Do not leave a strikethrough as-built narrative behind — the previous page
grew to 505 KB that way before the 2026-09-06 consolidation. This page lists **open work only**.
