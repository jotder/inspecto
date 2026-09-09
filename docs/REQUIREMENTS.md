# Inspecto — Platform Requirements & MoSCoW Analysis

> **Status: CURRENT requirements-of-record** · Compiled 2026-07-07 · **Re-reconciled 2026-07-08 after the
> ship-sweep session** (ACQ/PIP/BI/INV/MET closures; every pending item now carries a scope line) ·
> Reconciled against the shipped code (`master`, W1–W7 API contracts + R1–R6 metadata rework included).
>
> This document supersedes the archived requirements snapshot
> (`archived-documents/consolidated-2026-06-13/02-Product-Requirements.md`) and **reconciles** the planning-time
> MoSCoW in [`archived-documents/plans-archive/feature-matrix-editions.md`](archived-documents/plans-archive/feature-matrix-editions.md) (2026-07-02)
> with what has actually shipped since — several of that matrix's MUST items (security module, Studio
> persistence, live query path, pipeline-authoring wiring) are now delivered and appear here as baseline.
> Vocabulary is binding per [`GLOSSARY.md`](GLOSSARY.md).

---

## 1. Product definition & scope

**Inspecto** is a lean, configuration-driven **data acquisition + data management + BI + investigation
platform**: one ~90 MB self-contained artifact (single JVM, embedded DuckDB, zero external runtime
services) that replaces a NiFi-style collection/pipeline layer, a Tableau/Superset-style BI layer, a
Jira-style ops incident layer, and the surrounding glue scripts. Its wedge is **leanness *with*
operability** — for regulated, air-gapped, and resource-constrained buyers.

Three convictions anchor every requirement:

1. **One declarative config onboards a feed** — no code deploy, no DAG project, no cluster.
2. **Lean & self-contained** — runs on a laptop, an air-gapped server, or a container.
3. **Operable by design** — every Run is crash-isolated and idempotent; everything is captured as
   Signals, Metrics, audit, and managed objects.

The north star ([`okf/living-operational-system.md`](okf/living-operational-system.md))
frames the platform as **seven cooperating networks over one Component metamodel** (Data · Signal ·
Decision · Execution · Metadata · Presentation · Security), so it can evolve from deterministic rules to
AI-driven autonomy without redesign.

### 1.1 The three application sections

| Section | What it is | Where |
|---|---|---|
| **Angular UI** | The one operator console (all Lenses), mock-first + live | `inspecto-ui/` |
| **Java backend** | Engine + control plane + connectors + agent + security modules | `inspecto/`, `inspecto-connectors/`, `inspecto-agent/`, `inspecto-agent-hosted/`, `inspecto-security/` |
| **Agentic framework** | **eoiagent** — reusable, embeddable agent platform (separate repo); Inspecto's model transport since 2026-07-07; the kernel reasoning layer is vendored in `inspecto-agent` | `C:/sandbox/agent-brainstorm` (`com.eoiagent:*`) |

### 1.2 Personas (Lenses) and editions

- **Lens** = self-selected persona view of the one console (never a permission): **Business** (consume,
  investigate, raise Requirements) · **Builder** (author in Workbench + Studio) · **Ops** (operate runs,
  Signals, Incidents). **Role** = assigned, server-enforced authorization (security module); Roles project
  onto Lenses through **Capabilities** — panes gate on a Capability, never on Lens identity.
- **Editions are build flavors, never branches**: **Personal** (auth-free, local, free tier) ·
  **Standard** (adds `inspecto-security`: HTTPS, OIDC via external IAM, RBAC/ABAC, attributed audit — the
  free→paid line) · **Enterprise** (future: shared state, distributed scheduling, per-tenant ABAC;
  demand-gated).

---

## 2. Conventions used below

- **MoSCoW** — **Must** (product is not sellable/coherent without it) · **Should** (high value, not
  gating) · **Could** (valuable, opportunistic) · **Won't (now)** (explicitly out of this horizon).
  Ratings reflect **current product strategy as of 2026-07-07**, not the 2026-07-02 planning matrix.
- **Status** — `SHIPPED` (real backend, on `master`) · `PARTIAL` (shipped with named caveats) ·
  `MOCK-FIRST` (UI complete against the mock backend; backend pending) · `IN-FLIGHT` (uncommitted work in
  progress) · `DESIGN` (design-of-record exists, no code) · `PLANNED` (agreed, not started).
- **Edition** — `P` Personal · `S` Standard · `E` Enterprise · `All` edition-neutral.
- IDs are stable handles for traceability (`ACQ-3`, `NFR-2`, …); they do not imply sequence.
- ⚠ **`EDITIONS.md`'s feature × edition matrix is AUTHORITATIVE for the Edition column**; §3 mirrors it.
  Several rows here predate the 2026-09-02 "not for Personal" gating decisions and the EDG-01 gating debt,
  so where the two disagree the matrix wins and this table is the one to correct.

---

## 3. Functional requirements — an INDEX; the capability specs are the requirement of record

> **Stripped to this index on 2026-09-09** (docs-consolidation plan step 5). Until then this section
> carried the full status text for all sixteen areas *while* seventeen capability specs each claimed to
> be the requirement-of-record for their rows — so **every one of the 101 IDs had two owners**, which
> is the exact condition the consolidation existed to end. Each area's §2 states its rows as they
> actually hold, and **corrects this file** where the two disagreed; those corrections are why the
> duplicate could not simply be left alone.
>
> ⚠ **Nothing was deleted unchecked.** Every ID was confirmed to resolve to a spec that names it
> (101/101), and each row's distinctive facts — backticked identifiers, dates, commit hashes —
> were diffed against its owner. **11 facts that lived only here were migrated into their owner spec
> first**: identifiers each spec described in prose but never named, so they were ungreppable from the
> page that owns them (`PipelineCodec`, `postgresql.jar`, `jlink`/`-NoRuntime`, `*_job_template.toon`,
> `backup_verify`, `AccessDecider`, `ShareDashboardDialog`, `ComponentHistoryDialog`,
> `SettingsDrawerComponent`, `EgressGuardTest`, two archived designs of record). 🔴 The diff also
> caught this file **naming the wrong module** for the notification channels and citing **two commit
> hashes that are not valid objects** in this repository — both corrected in the owning specs rather
> than carried over.
>
> ⛔ **Do not re-add status text here.** A second home for a requirement is how the seventeen
> contradictions in `superpower/docs-consolidation-plan.md` §5.5 were created. State it once, in the
> owning spec.

| Area | IDs | Requirement of record |
|---|---|---|
| `ACQ` — **Acquisition & connectivity** | `ACQ-1` … `ACQ-7` | [`acquisition/acquisition.md`](okf/capabilities/acquisition/acquisition.md) §2 |
| `ING` — **Ingestion & parsing** | `ING-1` … `ING-6` | [`ingestion/ingestion.md`](okf/capabilities/ingestion/ingestion.md) §2 |
| `PIP` — **Pipeline authoring** | `PIP-1` | [`pipeline-authoring/pipeline-authoring.md`](okf/capabilities/pipeline-authoring/pipeline-authoring.md) §2 |
| `PIP` — **Pipeline execution** | `PIP-2` … `PIP-7` | [`pipeline-execution/pipeline-execution.md`](okf/capabilities/pipeline-execution/pipeline-execution.md) §2 |
| `DAT` — **Data plane** | `DAT-1` … `DAT-6` | [`data-plane/data-plane.md`](okf/capabilities/data-plane/data-plane.md) §2 |
| `BI · INV` — **Studio** | `BI-1` … `BI-8` · `INV-1` … `INV-4` | [`studio/studio.md`](okf/capabilities/studio/studio.md) §2 |
| `OPS` — **Observability & maintenance** | `OPS-1` … `OPS-6` | [`observability/observability.md`](okf/capabilities/observability/observability.md) §2 |
| `INC` — **Alerts & Incidents** | `INC-1` … `INC-5` | [`incidents/incidents.md`](okf/capabilities/incidents/incidents.md) §2 |
| `SPC` — **Spaces & tenancy** | `SPC-1` … `SPC-5` | [`spaces/spaces.md`](okf/capabilities/spaces/spaces.md) §2 |
| `MET` — **Component metamodel & Catalog** | `MET-1` … `MET-5` | [`metamodel/metamodel.md`](okf/capabilities/metamodel/metamodel.md) §2 |
| `API` — **Control API** | `API-1` … `API-7` | [`control-api/control-api.md`](okf/capabilities/control-api/control-api.md) §2 |
| `SEC` — **Security** | `SEC-1` … `SEC-9` | [`security/security.md`](okf/capabilities/security/security.md) §2 |
| `AGT · EOI` — **Assistant** | `AGT-1`, `AGT-2`, `AGT-3`, `AGT-4`, `AGT-5`, `AGT-6a`, `AGT-6b` · `EOI-1` … `EOI-7` | [`assistant/assistant.md`](okf/capabilities/assistant/assistant.md) §2 |
| `UI` — **Surfaces & Lenses** | `UI-1` … `UI-8` | [`surfaces/surfaces.md`](okf/capabilities/surfaces/surfaces.md) §2 |
| `PKG` — **Editions & packaging** | `PKG-1` … `PKG-4` | [`editions/editions.md`](okf/capabilities/editions/editions.md) §2 |

⚠ **Two of the seventeen areas have no rows here, and that is correct.** `CMP` (**Compliance**) was
orphaned across `EDITIONS.md` and `compliance/controls-matrix.md` and never had a `REQUIREMENTS.md`
section; `TOOL` (**Guards & repository tooling**) owns §4 `NFR-9`/`NFR-10` rather than functional rows.
Both are areas by [`GLOSSARY.md` §14](GLOSSARY.md#14-capability-areas-the-functional-spine), which is
the authority for what an area is called and where it lives — ⛔ never this table.

⚠ **Citations of the old `§3.1`…`§3.16` subsections resolve HERE.** Roughly thirty-five documents cite
them, and the useful ones are citations of *origin* — a spec recording which board rows it superseded, which
is exactly why those subsections are gone. Ten sentences that made a present-tense claim about this
section's *content* ("six requirements in §3.4, all recorded shipped") were repaired in the same change;
`supersedes-rows:` frontmatter was deliberately left alone, because it records what WAS superseded.

**`PIP` is one ID range across two specs**, which is why it takes two rows: authoring owns the DAG, the
Step vocabulary and author-time validation; execution owns Runs, Jobs, triggers and the maintenance
library. The split is fixed by `GLOSSARY.md` §14, not chosen here.

## 4. Non-functional requirements

| ID | Requirement | Target / evidence | Status |
|---|---|---|---|
| NFR-1 | **Throughput** | DuckDB `Appender` ingest ≈75× JDBC batch (~510k rows/s on the 1M-row bench); auto-derived `duckdb_threads` | SHIPPED |
| NFR-2 | **Footprint** | ~90 MB artifact; zero external runtime services; laptop-to-container | SHIPPED |
| NFR-3 | **Resilience** | Crash-isolated, idempotent Runs; batch-atomic commit; quarantine semantics | SHIPPED |
| NFR-4 | **Air-gap operation** | Full function without egress; hosted-AI SDKs physically absent; offline basemap/geocoder | SHIPPED |
| NFR-5 | **Accessibility** | WCAG 2.2 AA; axe-core CI gate | SHIPPED |
| NFR-6 | **API compatibility** | SemVer; versioned `/api/v1` as the sole business surface (unversioned routes retired 2026-07-25, API-5); `@PublicApi` embedding policy | SHIPPED |
| NFR-7 | **Compliance posture (Standard)** | SOC2 / ISO27001 / FedRAMP / HIPAA / PCI scope; small SBOM as a deliberate compliance asset | PARTIAL (module shipped; certifications not started) — ⚠ **owner 2026-09-08: [`okf/capabilities/compliance/compliance.md`](okf/capabilities/compliance/compliance.md)**. This is not one undifferentiated gap: it is **seven named items** in `compliance/controls-matrix.md` §4, six needing an external party or org authorship and **one needing only a named owner and a cadence**; the board's check is a line-anchored count that reads 7 and closes at 0. ⚠ The "small SBOM" claim is qualified by measurement — the reactor resolves **94** third-party artifacts, which is why generation moved per-bundle |
| NFR-8 | **Scale ceiling** | Single-node by design; Enterprise distributed tier is the opt-in escape hatch | ACCEPTED CONSTRAINT |
| NFR-9 | **Quality gates** | GAUNTLET (full reactor tests + UI lint/test/build), token lint, ~~a11y gate~~, ~~live smoke~~ | 🟡 SHIPPED — ⚠ **understated and misstated (corrected 2026-09-09; owner [`okf/capabilities/tooling/tooling.md`](okf/capabilities/tooling/tooling.md) §2)**: there is **no a11y gate step** (axe rides unit specs), and **live smoke exists only in `release.yml`, which has never executed**. The row names none of the **nine repo guards** nor the coverage floors — the substance of what is enforced. ⚠ Load-bearing: `compliance/controls-matrix.md` ISO 8.25–8.31 cites NFR-9 as its only evidence |
| NFR-10 | **Vocabulary discipline** | One concept → one word; banned synonyms never appear in UI/model/API/docs (GLOSSARY §0) | 🔴 SHIPPED — **both halves of this cell were wrong (corrected 2026-09-09)**. (a) Not "enforced in review": it is `tools/check-vocabulary.mjs`, wired into `ci.yml` **and** `.githooks/pre-push`, and cited by name as CC8 evidence. (b) The scope claim far exceeds the guard — of the **seven** hard bans in `CLAUDE.md`, **three have NO rule at all** (bare *Rule*, *Metric* BI, *Data Source*), *Issue* is enforced only in config KEYS and *Source* only in prose. Owner: [`okf/capabilities/tooling/tooling.md`](okf/capabilities/tooling/tooling.md) §2 | <!-- vocab-allow: names the banned term in order to state whether a rule covers it -->

---

## 5. MoSCoW summary — the reconciled backlog (as of 2026-07-08, post ship-sweep)

> **2026-07-08 ship-sweep:** one session closed ACQ-6/ACQ-7 · PIP-6/PIP-7 · the whole BI section
> (BI-4/5/6/7/8 shipped) · INV-1 · MET-4 · the DAT-3 caveat · ACQ-4's S3/MinIO/NFS/SMB
> half — each verified by the full reactor (1,271+ tests). **Everything still pending below carries a
> concrete scope line (size + blocker + owner), not a bare PLANNED.**

**Delivered baseline (former MUSTs, now shipped):** acquisition framework · Stage-1 ingest · medallion
ELT · authored Pipelines · scheduler/jobs + async runs · Datasets/Queries + live DuckDB execution ·
Studio persistence · component metamodel + R1–R6 rework · multi-space · `/api/v1` contract ·
`inspecto-security` (OIDC/HTTPS/BFF) · UI on v1 with OIDC · Assistant on eoiagent · packaging/editions.

### MUST (remaining — the release-gating set, each scoped)

1. **ACQ-4** — *FULLY CLOSED 2026-07-22*. `connector: azure` (SDK-free SharedKey) closed the tier on
   2026-07-08; the last residual, **GCS-native** (`connector: gcs`: the GCS JSON API + service-account
   OAuth2, beyond the S3-interop mode), shipped 2026-07-22 — SDK-free (RS256 JWT→bearer on JDK crypto),
   stub-tested offline. The prior "defer, offline-blocked" note was based on a stale assumption that
   native GCS needed a Google SDK jar; it does not. See `okf/backend/acquisition/connectors.md`.
2. **SEC-7** — *CLOSED 2026-07-08* (SEC-7d data-scoped grants shipped: attribute-scope model signed by
   product in-session; `caseType` visibility enforced server-side across list/read/mutate/graph on the
   Objects surface; scopes resolved by the security module). Boundary noted: the event/audit streams
   stay capability-gated, not row-scoped — they are ops surfaces, not case data.
3. **EOI-7** eoiagent `0.1.0` — *(a) cut + pin* **DONE 2026-07-08** (v0.1.0 tagged, Inspecto pinned,
   R2 closed — no SNAPSHOT dependency remains). Remaining: *(b) publish artifacts* — the registry
   decision (internal Nexus? GitHub Packages?) — an infra/product call, not code. Owner: product/infra;
   until then CI reproduces the pin from the tag (`git checkout v0.1.0 && mvn -o clean install`).

*Closed 2026-07-07: ING-5 (unified parsing + json/text_regex frontends), **ING-6** (Expectation engine:
`com.gamma.expectation` + `/expectations*`, real DuckDB violation counting + deduped-Incident/notify
consequence chain), INC-3 (webhook + SMTP delivery channels), PKG-4 (jlink/Nimbus verified), PIP-1
caveat (live e2e via `examples/06-serve/pipeline-job`).*

### SHOULD (remaining — each scoped)

- **OPS-5** Provenance conservation on live data — *not code: the verification protocol is written
  and signed (`docs/ops/provenance-conservation-verification.md`); running it needs the first live
  deployment. Owner: ops, first live deployment.*
- **AGT-6a** AI behind every screen (inline NL authoring) — *promoted Could→Should 2026-07-25 and scoped
  in `superpower/agt-6-plan.md` §3: phases A1 (one shared inline surface) → A2 (four-pane adoption wave:
  Pipelines/Expectations/Dashboards/Queries) → A3 (pane-context grounding) → A4 (read-only "explain this
  screen" breadth). **No new backend capability** — it reuses the shipped L1 draft tools, so the risk is
  UI-side only and drafts persist nothing. Ready to schedule pending decision asks D1–D4.*

*Closed 2026-07-08: **ACQ-7** etag/version dedup · **ACQ-6** push discovery (notify + watch) ·
**PIP-7** maintenance library (ledger_prune/db_maintenance/compact) · **PIP-6** job templates ·
**BI-4** scheduled report/export delivery · **INV-1** Link Analysis backend (Entity Projection +
server-side saved views) · **MET-4** Streams read-model (`GET /catalog/streams`) · **DAT-3** caveat
(structured evaluation = `/bi/query`).*

*Closed 2026-07-07: **DAT-6** Postgres state store (all 6 JDBC stores verified vs real Postgres) ·
**SEC-8** secrets (file + JCEKS keystore scopes; Vault still deferred) · **UI-8** Settings drawer
(landed by the parallel session, commits `7e06463`/`12ead9c`) · **SPC-4** Metadata Bundle v2 backend
(`BundleRoutes` export/preview/import over the `ComponentStore` kinds; connection/pipeline/job/view
kinds deferred to their own stores) · **UI-6** Requirements triage backend (`RequirementRoutes` submit/
decision/deliver + UI on the dedicated routes; shipped alongside SEC-7(c)) · **AGT-5 P0** embedded
intelligence spine (new `inspecto-intelligence` module: `IntelligenceAgent` SPI, `/agent/sessions`
+ `/agent/sessions/{id}/ask` control-plane routes, `InspectoPack` on the eoiagent platform with a
3-tool read belt + navigation catalog + policy/prompt profiles; QA-only, OFFLINE-only, no RAG corpus
yet — see `docs/archived-documents/plans-archive/embedded-intelligence-plan.md` §8 for the documented P0
scope cuts and the P1–P5 phasing, all since shipped).*

### COULD (remaining — each scoped)

- ~~**BI-6 Share dialog**~~ (residual UX) — **DONE 2026-07-09**: `ShareDashboardDialog` + a Share button
  on the dashboard editor (edit mode) mint the link in-app via `POST /dashboards/{id}/share` and show the
  `/share/{token}` viewer URL with copy + expiry; 503 (no `-Dbi.share.secret`) → writes-disabled notice.
  BI-6 (embed viewer) + BI-8 (template gallery) shipped 2026-07-08. **BI-6 is now fully shipped.**
- **MET-5** Component version history — *scoped (see §3.10 row): ~1 shift, no migration.*
- ~~**AGT-5 P1–P5**~~ — **COMPLETE 2026-07-21** (+ polish): phased per the now-archived
  `plans-archive/embedded-intelligence-plan.md` §8; the EOI-7(a) gate lifted 2026-07-08 (pinned v0.1.0, no
  moving SNAPSHOT) and P1–P5 all shipped. As-built in `okf/backend/agent/embedded-intelligence.md`; only the
  follow-ons in `BACKLOG.md` §2 remain open (embedding recall PARKED, eoiagent `DryRunProvider` seam, the
  QA-only/local-models-only scope cuts).
- **AGT-6b** Multi-step agent graphs — *scoped 2026-07-25 in `superpower/agt-6-plan.md` §4 and kept
  demand-gated. Today's `runbook_operator` already runs **code-defined** seeded sequences as one
  approval-gated unit; 6b is the model-**composed** graph. Two upstream blockers recorded: the eoiagent
  approval gate is synchronous per-call (nesting gated calls deadlocks — hence one approval per plan),
  and there is no per-tool `DryRunProvider` seam, without which a model-composed plan cannot be previewed
  per step (that seam is therefore a **prerequisite**, not just a refactor). Recommended first cut when
  demand lands: "authored graph, approved whole, executed stepwise" — generalize `RunbookActions`, never
  free-form ReAct over mutating tools. Trigger: a named client needing orchestration beyond the three
  seeded runbooks.*
- **E1** Enterprise distributed tier · Stage-2 streaming — *demand-gated strategy items, unchanged.*

*Closed 2026-07-08: **BI-5** measure alerts · **BI-7** headless BI API · **PIP-6** job templates ·
**ACQ-6** push discovery; **BI-6**/**BI-8** backends shipped (fail-closed share tokens; curated
template gallery + apply).*

### WON'T (this horizon — explicit non-goals)

- **Spring/Quarkus migration** — the framework-free core is a deliberate compliance asset.
- **Distributed-by-default** — clustering stays opt-in Enterprise; the single-JVM ethos holds.
- **Per-record lineage/replay** (OPS-6) — per-batch ancestry is the accepted grain.
- **Auth in the common core** — security stays an edition module behind SPIs.
- **Lens as a permission** — Lenses are self-selected views; enforcement is Roles only.
- **Login/user management inside Inspecto** — identity is delegated to external IAM.

---

## 6. Sequencing & dependencies

1. **Security-first ordering holds**: SEC-7 + PKG-4 close out the Standard revenue gate that W6 opened.
2. **Connectors before streaming**: ACQ-4's object-storage half shipped 2026-07-08 (SDK-free s3);
   ACQ-5 (Kafka) shipped the same day on the same SPI once `kafka-clients` landed in the cache.
3. **Parsing unification (ING-5) before Expectation engine (ING-6)** — Expectations bind to the unified
   schema surface.
4. **Backend catch-up for mock-first UI**: ~~INV-1, SPC-4, MET-4, DAT-4~~ — the whole set closed by
   2026-07-08; the mock-first UI surfaces now all have real backends.
5. **AGT-5 (embedded intelligence)** — P0 shipped 2026-07-07 on product-owner sign-off; **EOI-7(a)**
   landed 2026-07-08 (pinned v0.1.0), so P1 no longer builds on a moving SNAPSHOT.
6. **API-5 unversioned surface — CLOSED 2026-07-25**: the surface itself is gone (business routes
   require `/api/v1`, infra probes stay unversioned), and the sunset mechanism, its metric and its
   runbook went with it. Nothing is left pending per deployment.

---

## 7. Risks & open questions

| # | Risk / question | Mitigation / owner signal |
|---|---|---|
| R1 | ~~Authored-Pipeline go-live verified only against synthetic data~~ **RESOLVED 2026-07-07** — live seeded `type: pipeline` run verified (`examples/06-serve/pipeline-job`) | — |
| R2 | ~~eoiagent SNAPSHOT churn (moving dependency)~~ **RESOLVED 2026-07-08** — v0.1.0 cut + pinned (EOI-7a); CI builds the tag | — |
| R3 | ~~Standard jlink runtime unverified vs Nimbus~~ **RESOLVED 2026-07-07** — module set verified sufficient (PKG-4) | — |
| R4 | ~~Per-resource permissions & X-Actor rejection incomplete on Standard~~ **RETIRED 2026-09-08** — both halves shipped 2026-07-07/08 (`X-Actor` rejected outright when an `Authenticator` is active; `permissions[]` emitted); see `okf/capabilities/security/security.md` §2 | — |
| R5 | Provenance conservation checks unproven on live data | OPS-5 live verification alongside R1's seeded run |
| R6 | ~~Structured (non-SQL) Queries still client-compiled~~ **ACCEPTED AS DESIGN 2026-07-22** (product sign-off) — not a risk. The builder UI emits valid SQL that `QueryExecutor` runs; a server-side structured compiler would duplicate that for no functional gain. Server 422 on non-SQL bodies stays the explicit, deliberate contract. Revisit only if an external API consumer must submit structured bodies directly. | Closed — `okf/backend/control-plane/queries.md` |
| R7 | Prompt injection / data egress once intelligence deepens | AGT-5 design: context-as-data, privacy classes P0–P3, approval gates, kill switch |
| R8 | ~~Open product questions: case-type data-scoped grants, `canOnboardConnections` split, sunset timing~~ **RESOLVED 2026-07-22** (product sign-off): case-type grants = SEC-7d attribute-scope model (shipped, no further per-type role UI); `canOnboardConnections` = split out + implemented (Admin-only grant on the connection write routes); sunset timing = moot — the unversioned surface was **retired outright** 2026-07-25 (API-5), mechanism and all. | Closed — `okf/backend/editions/auth-security.md` |
| R9 | Feature matrix (2026-07-02) drifting from reality | This document is the reconciled view; update **both** on the next planning pass |

---

## 8. Traceability

| Document | What it grounds |
|---|---|
| [`api/README.md`](api/README.md) · [`okf/backend/control-plane/api-v1.md`](okf/backend/control-plane/api-v1.md) | The `/api/v1` contract as built — envelope, error catalog, OpenAPI, ETag concurrency, `/bootstrap` (API-1…5) |
| [`okf/living-operational-system.md`](okf/living-operational-system.md) | Seven-network north star (the R1–R6 rework shipped 2026-07-06; plan archived 2026-09-07) |
| [`BACKLOG.md`](BACKLOG.md) | Every open row, ranked; §6 the standing refusals; §7 the duplicate map |
| [`okf/backend/agent/embedded-intelligence.md`](okf/backend/agent/embedded-intelligence.md) | The Assistant's autonomy ladder and tool surface as built (AGT-5, complete 2026-07-21) |
| [`roadmap/ROADMAP.md`](roadmap/ROADMAP.md) · [`roadmap/STAKEHOLDER_OVERVIEW.md`](roadmap/STAKEHOLDER_OVERVIEW.md) | Horizons; value proposition; maturity |
| [`EDITIONS.md`](EDITIONS.md) | Edition capability tiers |
| [`GLOSSARY.md`](GLOSSARY.md) | Binding vocabulary (§0 rules; §13 rename status) |
| [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) | Per-feature TOON shapes + runnability constraints |

*Provenance, not authority (2026-09-08):* the planning documents these rows were first derived from —
`feature-matrix-editions.md` (the 2026-07-02 H/M/S/N ratings), `api-contract-design.md` (the 33 API
guidelines and the W1–W7 worklog), `backend-backlog.md` and `embedded-intelligence-plan.md` — are in
`archived-documents/plans-archive/` and are listed in [`INDEX.md`](INDEX.md) §Archived plans. ⛔ Read them for
*why* a row was written, never for what is built; the archive is not maintained.
