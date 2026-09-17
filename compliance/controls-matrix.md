# Control matrix — the single mapping table (C2)

**Status:** LIVING DOCUMENT, first cut 2026-08-28. · **Plan:**
[`docs/archived-documents/plans-archive/compliance-certifications-plan.md`](../docs/archived-documents/plans-archive/compliance-certifications-plan.md)
workstream **C2**. · **Owner:** enterprise-PM track; product rows are this repo's.
**Requirement-of-record:** [`docs/okf/capabilities/compliance/compliance.md`](../docs/okf/capabilities/compliance/compliance.md) — the `CMP` capability spec (area #13, 2026-09-08). It carries the edition rows, the decisions, and 🔴 **three built mechanisms this table does not cover**: the anonymous public dashboard embed, the agent's approval gate and kill switch, and the accessibility gate.

This is the one table the other compliance deliverables are **exports of**, never re-writes:

- the ISO 27001 **Statement of Applicability** = this table filtered to Annex A with the
  *Applicability* column;
- the FedRAMP **customer-responsibility matrix** (C6) = this table filtered to
  *Responsibility ≠ product*;
- the SOC 2 **control narrative** = the *Implementation* + *Evidence* columns per criterion.

Add a column before you add a document.

## How to read it, and the rules that keep it honest

1. **Every product row names a real artifact** — a file, a route, a CI step, a JVM property — that
   exists at the commit this row was written. A row with no artifact is a **gap**, and says so.
2. **Do not overclaim.** Where the implementation is narrower than the control, the *Implementation*
   column states the boundary and the *Gap* column carries the remainder. An auditor finding a
   caveat we wrote ourselves is a good day; an auditor finding one we did not is the bad one.
3. **Responsibility is explicit**, and only four values: `product` (this repo implements it),
   `org` (Gamma Analytics operates it — §5 of the plan), `customer` (the deploying organization),
   `IdP` (inherited from the customer's identity provider).
4. **Verify before you cite.** A row here is exactly as trustworthy as the last person who ran the
   grep. When you touch a control's implementation, update its row in the same change.

⚠ **Scope boundary.** Inspecto is **self-hosted software**, not a cloud service. Physical,
environmental, HR and vendor controls are the *customer's* (site) or the *org's* (company) — they
appear here marked as such so an auditor sees they were considered, not so we claim them.

---

## 1. SOC 2 — Trust Services Criteria

Category scope per plan §2b-1: **Security** (common criteria) mandatory; **Availability** and
**Processing Integrity** included; Confidentiality/Privacy deferred to demand.

| TSC | Control intent | Implementation (grounded) | Evidence source | Resp. | Gap → workstream |
|---|---|---|---|---|---|
| CC1–CC3 | Governance, risk assessment, org structure | — | — | org | Entirely org-side (plan §5). Not product work; listed so the boundary is visible. |
| CC4 | Ongoing monitoring of controls | Signal ledger (`SignalRoutes`, `GET /signals`, `/signals/stream`); Alert Rules (`inspecto-engine/.../alert/AlertRule.java`); provenance read views (`JobRoutes`, `GET /provenance`, `/provenance/batches`) | The routes themselves + `ControlApi*Test` real-HTTP suites | product | ✅ Extraction runbook written 2026-08-28 → [`evidence/audit-log-extraction.md`](evidence/audit-log-extraction.md) |
| CC5 | Control activities tied to objectives | **This document** | — | product | — (C2 is this file; keep it current) |
| CC6 | Logical access: authN/authZ, least privilege, access review | OIDC resource-server authN (`inspecto-security/.../OidcAuthenticator.java`) behind the `Authenticator` SPI (`inspecto/.../control/Authenticator.java`) so the core stays identity-agnostic; capability gates + `Roles`/`AccessDecider` (`inspecto/.../control/`); published capability set pinned by `CapabilityManifestTest`. ✅ **The gating is now ENFORCED, not merely reviewed (2026-09-16):** a mutating route (`POST`/`PUT`/`PATCH`/`DELETE`) that declares neither a capability nor a recorded exemption **fails the server's boot** (`ControlApi.register`), with no warn-only escape hatch — so “an undeclared mutating route” is a state that cannot reach production, and an auditor can test the control by trying to add one. ✅ **Reads are open BY POLICY** (operator, affirmed for compliance 2026-09-16): reads are not capability-gated, so the boot check covers mutating methods only. ⚠ **Stated precisely (2026-09-17), because the earlier wording over-claimed:** cross-Space read confidentiality is enforced by the ABAC policy engine (`inspecto-policy`, `PolicyEngine` seed `space-isolation`), which ships in **Enterprise only** and isolates a subject only when the IdP maps a `space` home claim; `AccessDeciders` reads an absent engine as ALLOW. On **Standard**, every authenticated subject can read every hosted Space — authentication without read isolation. On Personal nothing authenticates. If reads are ever capability-gated, this line moves with the code | `OidcAuthenticatorTest`, `CapabilityManifestTest`, `ExchangeAttributeScopeTest` (per-request subject isolation), **`UndeclaredMutatingRouteTest`** (the boot refusal itself: every mutating method refused when undeclared, a gated route and a recorded exemption accepted, and a read deliberately needing no declaration) | product + IdP | Account **lifecycle** (joiner/mover/leaver) is IdP-inherited — document the claim contract. Access-**review** procedure: ✅ [`evidence/access-review.md`](evidence/access-review.md) (role-level grants from the shipped R5 view + `/access/*` JSON; subject→role joins the IdP export). Route-gating evidence, including the **derived** mutating-route inventory (generated by `tools/route-gating-report.mjs`, CI-enforced against drift, never hand-typed): ✅ [`evidence/route-gating.md`](evidence/route-gating.md). ✅ **Framework ANSWERED 2026-09-16** (§4a): that document serves SOC 2 **CC6.1**/**CC6.3** and ISO 27001:2022 **A.5.15**/**A.5.18**/**A.8.3**, declared on one header line per the `release-verification.md` pattern, with the Annex A mapping owned **here** and not restated there. |
| CC6.1 | Credentials are not stored in the clear | `SecretResolver` (`inspecto-acquire/`) resolves references only — `${ENV:NAME}`, `${FILE:/path}`, JCEKS keystore via `secrets.keystore.path`/`.type`/`.password`; a connection profile never holds a secret. `SecretScrubber` (`inspecto-event/`) keeps them out of events | `SecretResolverTest`, `SecretScrubberTest`; **`tools/check-secrets.mjs`** fails the build on a reintroduced literal (CI `ci.yml` + `.githooks/pre-push`) | product | ✅ **SEC-INCIDENT-1 — CLOSED BY DECOMMISSION 2026-08-29** (BACKLOG §5 holds the full record). Five OAuth client secrets were public on GitHub 2026-06-12 → 2026-07-25; a history rewrite followed but `refs/pull/*` defeated it, so rotation at the issuer was the standing fix. **That system has since been decommissioned, so there is no issuer entry left to rotate.** Disclosed here rather than dropped: the values remain in public git history, which is immaterial only because none is in use. The guard prevents recurrence; it never remediated history. |
| CC6.7 | Restrict transmission / movement of data | Air-gap operation by design: no runtime SaaS dependency, offline basemap, optional AI modules absent from the lean core. CI **enforces** the core's leanness (`ci.yml` "lean core stays kernel-free" — greps sources AND the resolved `dependency:tree`) | The CI step's exit code, per run | product | — |
| CC7 | System operations: anomaly detection, incident management | Alert Rules → Incidents/Case Manager; notification feed; `incident_purge` maintenance task (`inspecto-ops/.../opsjob/IncidentPurgeTask.java` — Standard+; moved out of core 2026-09-08) | Route suites; the task's own dry-run report | product | **Incident-response policy** document → C5 (org-owned content) |
| CC8 | Change management: authorized, tested, tracked | Conventional Commits + retired-branch rejection (`.github/workflows/branch-policy.yml`); full reactor tests (`ci.yml`); UI gate — design-token lint, unit tests, production build (`.github/workflows/ui.yml`); vocabulary guard (`tools/check-vocabulary.mjs`). Config change is gated at the write path: `ConfigSafetyValidator` + `PathJail` (`inspecto-config/.../safety/`), write-root fail-closed 503 | Per-run CI logs; `PathJailTest`, `JobPathContainmentTest` | product | Write the SDLC as a **control narrative** (this table's CC8 row is the seed, not the narrative) |
| CC8 (release integrity) | Released artifacts are verifiable | ✅ **SHIPPED** — `inspecto/package.ps1` **always** writes a `sha256sum`-compatible `<artifact>.sha256` (`:845-856`); `-Sign` additionally emits a GPG detached `<artifact>.asc` (`:869`), key supplied via `-SigningKey`/`$env:INSPECTO_SIGNING_KEY`, never baked in | The `.sha256`/`.asc` files shipped beside each artifact | product | 🔴 **The plan's C3 lists this as work to do — it is already built.** What remains of C3 here is the *customer verification runbook*, and making signing **routine** depends on plan §6 Q3 (where the org's GPG key lives). |
| CC9 | Risk mitigation, vendor risk | Dependency graph pinned and diffed per build — `tools/check-dependencies.mjs` vs `tools/dependencies.lock`, wired into `ci.yml`, so nothing enters or moves un-reviewed; air-gap posture | The lock's own diff, per CI run | product | 🔴 **The "lean SBOM" claim needs qualifying — measured 2026-08-28.** The reactor resolves **96<!--count:locked-dependencies--> third-party artifacts**, dominated by the OPTIONAL AI stack (langchain4j ×14, DJL, onnxruntime, OkHttp, Kotlin stdlib, Kafka, OpenNLP) plus 13 `com.eoiagent:*`. Leanness is a property of the **lean core / what each edition PACKAGES**, not of the reactor — and CI enforces exactly that narrower claim (the kernel-free guard on `inspecto`). ⛔ Do not tell an auditor "we have few dependencies" without naming the boundary. Consequence for **G1**: the SBOM must be generated **per packaged bundle**, not from the reactor, or it will attest a set no customer installs. |
| A1 | Availability: capacity, recovery | Single-node by design (NFR-8); crash-isolated idempotent Runs (`OVERWRITE_OR_IGNORE` output idempotence) | Engine suites | product + customer | Runbook EXISTS (`docs/ops/backup-restore-runbook.md`) and so does the **RTO/RPO statement** (`evidence/rto-rpo-statement.md`) — ⚠ *this cell said the statement was missing until 2026-09-08, which G6 below already contradicted*. What remains is the **committed targets** (operator-fill fields) **+ a recorded restore drill** → C4 (G6) |
| PI1 | Processing integrity: complete, accurate, timely | Expectations (`inspecto/.../expectation/ExpectationEvaluator.java`, `ExpectationRoutes`); quarantine semantics (`inspecto-etl/.../QuarantineManager.java`); per-file status + provenance + lineage ledgers; conservation invariant (`GuardedSummaryEmitter`, `PipelineJobRunner`) | `ControlApiExpectationTest`; the four run ledgers (status / batches / lineage / unpack) | product | **OPS-5 live soak** verifies the invariant on real volume — externally gated (needs a deployment target) |

## 2. ISO 27001:2022 — Annex A

Organizational (5.x), People (6.x) and Physical (7.x) themes are org- or customer-side and are
**excluded with justification** in the SoA export, not implemented here. The product theme is
**Technological (8.x)**.

| Annex A | Implementation (grounded) | Resp. | Applicability / gap |
|---|---|---|---|
| 8.2–8.5 privileged access, access restriction, secure authentication | OIDC authN; `Roles` + capability gates; privileged = Admin role + `canConfigureAccess` | product + IdP | Applicable. Full role enforcement = `rbac-abac-plan` R-workstreams |
| 8.8 vulnerability management | — | product + org | **Applicable, GAP.** Offline by constraint, so the answer is a pinned-dependency diff check plus an advisory-watch process, not a scanner SaaS → C4 |
| 8.9 configuration management | TOON config as the baseline; `ConfigSafetyValidator`; `PathJail`; write-root fail-closed gate | product | Applicable. Hardening guide (C6) doubles as the CM baseline |
| 8.12 data-leakage prevention | Air-gap / no-egress posture, CI-enforced (see CC6.7) | product | Applicable — a genuine differentiator; write it up rather than treating it as background |
| 8.13 backup | — | customer | **Applicable, GAP** → C4 runbook + drill evidence. Self-hosted: the customer backs up, we document *what* to back up (write root + state stores) |
| 8.15–8.17 logging, monitoring, clock synchronisation | Audit trail (`inspecto/.../control/AuditTrail.java`) captures state-changing Control API requests as append-only `AUDIT` events from a **single dispatch seam**, so every current and future mutating route is covered without per-handler wiring; signals + metrics; operations time zone is explicit (`-Dops.timezone`) | product + customer | Applicable. ⚠ **Stated boundary, do not overclaim:** `AuditTrail` covers mutating `POST`/`PUT`/`DELETE`, `GET …/export`, and access-denied attempts; **auth-gated events (login/MFA/password, true 401/403) are out of its scope** — they ~~arrive with the security module~~ 🔴 **that promise is UNFULFILLED, not pending (grounded 2026-09-08): `inspecto-security`/`OidcAuthenticator` is real and shipped, and `/auth/exchange`, `/auth/refresh` and `/auth/logout` still emit NOTHING to the audit trail.** Sign-in is not audited; *authorization* decisions are. Clock sync itself is a deployment note (C6). |
| 8.24 use of cryptography | TLS in transit; JCEKS secret storage; release signing (`package.ps1 -Sign`) | product + org | Applicable. Crypto **policy** → C5; FIPS mode → C6 |
| 8.25–8.31 secure development, testing, environments | NFR-9 quality gates; branch policy; per-edition builds | product | Applicable — mostly narrative work off this table |
| 8.32 change management | Same evidence as SOC 2 CC8 — **one narrative, two mappings** | product | Applicable |

## 3. NIST 800-53 rev 5 — families (FedRAMP alignment, C6 input)

Posture: **"FedRAMP-ready / supports your ATO"**, not authorization — Inspecto is self-hosted
software, so there is no CSP boundary to authorize (plan §2b-3). Baseline target Moderate is an
baseline **DECIDED: Moderate** (operator, 2026-08-30) — corrected here 2026-09-09, having read as an open assumption for ten days. ⛔ It is the baseline C6's statements are written against, **not** a commitment to authorization (self-hosted only; no 3PAO, ConMon or POA&M). Definitions and rationale: `docs/okf/capabilities/compliance/compliance.md` §3.2, §3.10.

| Family | Product statement | Resp. | Gap |
|---|---|---|---|
| AC — access control | Session management, capability gates, least privilege; data-scoped grants | product | **AC-2 account lifecycle is inherited from the customer IdP** — the deliverable is the documented claim contract, not an implementation |
| AU — audit | Append-only audit events from one dispatch seam; signal ledger; provenance/lineage | product | **AU-4/AU-11 retention configuration** → C4. **AU-9 audit-record protection** ✅ **STATED 2026-08-30** → [`evidence/audit-record-protection.md`](evidence/audit-record-protection.md) — append-only **by construction of the write path** and one dispatch seam; 🔴 explicitly NOT tamper-evident, NOT permission-hardened (no `PosixFilePermission` call exists repo-wide) and buffered events ARE dropped after 50k on sustained flush failure. ⛔ The document exists to keep the claim narrow — do not assert immutability the storage layer does not enforce |
| IA — identification & authentication | Enforces authenticated subjects when an `Authenticator` is present; MFA and identity proofing are the IdP's | IdP | Statement only; nothing to build |
| SC — system & communications protection | TLS in transit | product + customer | **SC-13 FIPS-validated crypto** — needs a documented FIPS mode verified on a FIPS-enabled JVM provider (a test-matrix leg, ⛔ not new crypto code) → C6 |
| CM — configuration management | `ConfigSafetyValidator`, `PathJail`, write-root gate, TOON as the config baseline | product | CM-6 baseline = the hardening guide → C6 |
| SI — system & information integrity | **SI-10 input validation is literally the product** (Expectations, quarantine, schema gates); SI-2 flaw remediation = the release/backport process | product | Merge-forward propagation (`docs/BRANCHING.md`) is the evidence — ⚠ currently a single supported line (`master`), which is a *fact to state*, not a gap to hide |
| RA — risk assessment | — | product + org | **RA-5**: offline constraint ⇒ pinned-deps diff + SBOM-against-advisory check → C3/C4. The risk-assessment *process* is org-owned |
| CA / CP / IR / PE / PS / SA-9 | — | org / customer | Named in the responsibility matrix; not product work |

## 4. Open gaps, consolidated

Everything above marked GAP, in one list, so C3/C4 can be scheduled from a single place:

| # | Gap | Workstream | Blocked on |
|---|---|---|---|
| G1 | ~~SBOM is not generated~~ | C3 | ✅ **CLOSED 2026-09-02 (COMPLY-1)** — `tools/sbom.mjs`, run by `package.ps1` per bundle: CycloneDX 1.5 + SPDX 2.3 from ONE `dependency:list` over the shipped modules, per-component SHA-256 + POM-declared licence + purl, first-party jars hashed as shipped; inside the zip (`sbom/`) and attached to the release. Evidence: [`evidence/release-verification.md` §5](evidence/release-verification.md). *(Decision record:)* ✅ **DECISION-GATE LIFTED 2026-08-30 (operator, plan §6 Q2): emit BOTH CycloneDX and SPDX**, from the same resolved set in the same packaging run — never independently, or the two documents drift. Remaining is **build work, not a decision**. ⚠ **Scope correction 2026-08-28: generate it PER PACKAGED BUNDLE, not from the reactor** (see CC9) — the reactor set includes optional modules a given edition does not ship. `tools/dependencies.lock` is the review baseline, **not** an SBOM: it lists coordinates, no licences or hashes. |
| G2 | ~~Customer **verification runbook** for `.sha256`/`.asc`~~ | C3 | ✅ **CLOSED 2026-08-28** → [`evidence/release-verification.md`](evidence/release-verification.md) |
| G3 | ~~Signing is *possible* but not *routine*~~ | C3 | ✅ **CLOSED 2026-09-02 (COMPLY-2)** — `.github/workflows/release.yml` on every `v*` tag: key from the CI secret store, `package.ps1 -Sign` per edition (now fail-closed on a missing gpg/key), publish refused without `.sha256` + `.asc`, public-half verification before `gh release create`. 🔴 **The path has never executed** (recorded 2026-09-08): the workflow landed 2026-09-06 and the newest `v*` tag is 2026-06-03 on the retired `3.x` line, so no tag has been cut since it existed — `EDITIONS.md` `CMP-02` carries this caveat and this row did not. Evidence: [`evidence/release-verification.md` §4](evidence/release-verification.md). *(Decision record:)* ✅ **DECISION-GATE LIFTED 2026-08-30 (operator, plan §6 Q3): the release key lives in the CI secret store**, held by no shift locally, so signing becomes a mandatory release-pipeline step rather than an optional `package.ps1 -Sign` flag. Remaining is **build work**: wire signing into the pipeline. ⚠ Two accepted consequences — a release cut OUTSIDE CI cannot be signed at all, and key custody (rotation, who can read the secret, CI-provider compromise) becomes a **CC6 access-control** question, not a personal one. |
| G4 | ~~Auditor **audit-log extraction runbook**~~ | C3 | ✅ **CLOSED 2026-08-28** → [`evidence/audit-log-extraction.md`](evidence/audit-log-extraction.md). It surfaced **G10** below. |
| G5 | ~~Retention configuration for audit / notification / signal stores~~ | C4 | ✅ **CLOSED 2026-09-02 (COMPLY-3)** — the `event_prune` maintenance task (`EventStore.prune`, a whole-day-partition delete over `level/year/month/day`, UTC) applies the one-year window when scheduled with `retention_days: 365`; the in-memory backend reports nothing durable. ⚠ Still operator-authored like every prune — a deployment that never schedules it has stated policy only; say so. Evidence: [`evidence/retention-configuration.md` §3](evidence/retention-configuration.md). *(History:)* ⚠ **Documentation half CLOSED 2026-08-28** → [`evidence/retention-configuration.md`](evidence/retention-configuration.md) — SEVEN maintenance tasks exist (the row's three understated it), all operator-authored (`maintenance` Jobs; nothing prunes by default). 🔴 **Grounded remainder: the Parquet event store — AUDIT events and Signals included — has NO retention at all** (partly a stated decision, MNT-14 G3: the audit trail survives what it describes; the unbounded growth half is undecided). ✅ **DECISION-GATE LIFTED 2026-08-30 (operator): the audit-retention window is ONE YEAR.** Remaining is **build work**: a partition-delete prune task over the `level/year/month/day` layout (a file delete by partition, **not** a SQL DELETE). ⚠ The MNT-14 G3 stance is NOT overridden by this — a purged Incident's history is still deliberately retained; the one-year bound is an *upper* bound on the store, which previously had none. |
| G6 | ~~Backup/restore runbook~~ + RTO/RPO statement + **a restore drill record** | C4 | ⚠ **Repo half CLOSED 2026-08-28** → [`evidence/rto-rpo-statement.md`](evidence/rto-rpo-statement.md): capability-grounded RPO (= the backup cron interval) and RTO (= provision+restore+boot, measured by drill), with the **committed targets left as explicit operator-fill fields** — an org commitment must not be invented from the repo (the C1 rule). Remaining, operator-owned: run the first drill, record it in the statement's §4 table, then state the targets. *(History: the original row called the whole runbook a gap — `docs/ops/backup-restore-runbook.md` already existed.)* |
| G7 | ~~Offline dependency-review step in CI (pinned-versions diff)~~ | C4 | ✅ **CLOSED 2026-08-28** — `tools/check-dependencies.mjs` + `tools/dependencies.lock`, wired into `ci.yml`. ⚠ **Scope corrected 2026-09-07:** until then the resolve ran with no edition profile, so the lock covered only the default reactor and `inspecto-security`'s Nimbus JOSE+JWT tree — the dependency a security reviewer most wants to see — was never under review, while this row already said CLOSED. It now resolves `-Pedition-enterprise` (25 modules, not 23). ⛔ It is a REVIEW device, not a scanner: it proves nobody looked, never that a version is safe. |
| G8 | ~~Access-review (effective-grants) view~~ | C4 | ✅ **CLOSED 2026-08-28** → [`evidence/access-review.md`](evidence/access-review.md). Grounded against the shipped R5 view: the **Roles** tab renders role-level effective grants (profile-deny overlay), **Policies** lists the policy layer, and the review's artifacts are the `/access/{roles,profiles,policies}` JSON plus the IdP's role-membership export (subject→role is IdP-owned by design — the CC6 boundary). Known limits recorded in the runbook §3; no product work filed unless a real review finds the JSON path insufficient. *(History: the row's cited blocker, rbac-abac R5, had already shipped 2026-07-23.)* |
| G9 | FIPS-mode documentation + verification leg | C6 | Demand-gated; sequenced after this table exists |
| G10 | ~~`GET /events/export?format=csv` drops every audit attribute~~ | C4 (product) | ✅ **CLOSED 2026-08-28** — audit-shaped CSV projection: `type=AUDIT`/`type=ACCESS_DENIED` exports append one column per `AuditAttrs` key, derived from `AuditAttrs.ALL` (reflection-pinned). Runbook §3 rewritten — CSV and JSON are both audit-complete for a `type=AUDIT` extraction. ⚠ **Route moved 2026-09-08 (EDG-01 cell 6): the audit export is now core `GET /audit/export?format=csv`**, not `/events/export` — the general events feed became the optional `inspecto-events` module, so the audit projection was kept in core (`AuditLogRoutes`, fail-closed to `AUDIT`/`ACCESS_DENIED`) precisely so this control stays satisfiable on the Personal edition |

**Not gaps — deliberate positions.** Single-node availability (NFR-8), no hosted offering
(plan §6 Q5 assumed self-hosted only), no 3PAO/ConMon/POA&M program. Each is a *stated posture*;
restating it as a gap is how a matrix grows work nobody chose.

### NFR-7 — the externally-gated program items, each with a landing place

BACKLOG §2's **Compliance program (NFR-7)** row was the one gate on the whole board with **no repo-side
check at all**: it named "org action + external parties" and nothing else, so a shift could never tell
whether any of it had moved, and on 2026-09-07 it was the only §2 row that could not be run in either
direction. These seven rows are that landing place. Each closes when **its own row here carries a dated
line** — the same shape every other §2 gate now uses.

⛔ §5 below still stands: nothing here may be **generated** from the repo. The fix is a place to record an
answer, not a way to invent one. ⚠ Keep the literal row prefix — BACKLOG §2's stated check is
`grep -c '^| NFR-7 ·.*⬜ open'` on this file, and renaming the prefix silently disarms it. 🔴 That check is
**line-anchored on purpose**: unanchored, it also matches this paragraph and reports 9 for 7 rows.

| # | Item | Workstream | State |
|---|---|---|---|
| NFR-7 · N1 | SOC 2 in-scope service list + ISO 27001 ISMS boundary (applicability statements) | C1 | ⬜ open — org input, plan §6 Q4/Q5; see §5 |
| NFR-7 · N2 | Auditor engagement (firm named, window agreed) | C1 | ⬜ open — org action: name the firm, agree the window. The observation window's own state (start date, end-date arithmetic, preconditions) is recorded ONCE, in **[§4a below](#4a-soc-2-type-ii-observation-window--not-started)** — ⛔ do not restate it here or in BACKLOG §2, or the two copies drift |
| NFR-7 · N3 | Penetration test (scope, vendor, report) | C3 | ⬜ open — external party |
| NFR-7 · N4 | C5 policy content (the written policies themselves, not the controls) | C5 | ⬜ open — org authorship |
| NFR-7 · N5 | C6 FedRAMP package | C6 | ⬜ open — demand-gated; ⛔ do not start unscoped |
| NFR-7 · N6 | C6 FIPS-mode leg (the verification half is G9 above) | C6 | ⬜ open — demand-gated |
| NFR-7 · N7 | ISO 27001 A.8.8 advisory-watch process (who watches, how often, where recorded) | C3 | ⬜ open — org process. ⚠ Cheapest of the seven and the only one with no external dependency: it needs a named owner and a cadence, not a vendor |

### 4a. SOC 2 Type II observation window — NOT STARTED

**This is the single record of the window's state.** BACKLOG §2 and NFR-7 · N2 above point here and must not
restate it — §7 of BACKLOG already records those two rows as the same window described twice, and a third
copy is how the three drift apart.

| Field | Value (2026-09-16) |
|---|---|
| State | ⬜ **NOT STARTED.** Recorded by the operator 2026-09-15. No observation window is running and no evidence is being collected against one. |
| Start date | *(none — operator to fill: `YYYY-MM-DD`)*. ⛔ **Do not backdate it to the 2026-09-06 decision to open a window.** An auditor asks what was being observed on day one; on 2026-09-06 the route-gating control did not yet exist. The date to record is the day observation actually begins. |
| End date | **= start + 6 months.** Not computable today because no start date exists; the moment the field above is filled in it is arithmetic, not a judgement call. |
| Category scope, when it opens | As §1 of this table: Security (common criteria) mandatory; Availability and Processing Integrity included; Confidentiality/Privacy deferred to demand (plan §2b-1). |

**What must be true before a start date is written here**

1. ✅ **MET 2026-09-16 — the route-gating control is in place, not in flight.** This was the stated blocker
   (`docs/archived-documents/plans-archive/route-gating-compliance-plan.md` §5: the window should observe the control for its whole
   duration, not watch it being introduced). Step 3 shipped: a mutating route declaring neither a capability
   nor a recorded exemption fails the server's boot, with a CI scan ahead of it — see the CC6 row above and
   [`evidence/route-gating.md`](evidence/route-gating.md).
2. ⬜ **OPEN, external — auditor engaged: firm named and window agreed** (NFR-7 · N2 above, org action, C1).
   The start date is theirs to agree, not ours to assert.
3. ✅ **ANSWERED 2026-09-16 — which framework(s) the route-gating evidence is written against.** It serves
   **SOC 2 CC6.1/CC6.3** *and* **ISO 27001:2022 A.5.15 / A.5.18 / A.8.3**, declared on a single `Control:`
   header line exactly as [`evidence/release-verification.md`](evidence/release-verification.md) already
   does (SOC 2 CC8 · ISO 27001 8.24 · NIST SI-2/SR). ⛔ **The per-control Annex A mapping lives in §2 of
   this file and is not restated in the evidence document** — §2 already declares that the ISO Statement of
   Applicability is an *export* of this table, so a second copy would drift against it.
   🔴 **The question had effectively been answered here for weeks and nobody noticed.** The multi-framework
   posture was declared at the top of this file and practised in `release-verification.md`; route-gating
   was the lone document still calling it an assumption. ⚠ It also cited **"ISO 27001 A.9"** — the retired
   **2013** numbering, contradicting §2's own *"ISO 27001:2022 — Annex A"* heading. Both are corrected.
   ⚠ **Not settled by this, and deliberately still open:** the HIPAA/PCI *certification* choice (§5, BACKLOG
   §2), which is gated on a named prospect. Which frameworks the **evidence is written against** and which
   the org **certifies to** are different questions; only the first is answered here.
4. ⚠ **Related, not asserted as a gate:** the C1 applicability statements (NFR-7 · N1 — in-scope service list,
   ISMS boundary) are org input per §5 below. Whether an auditor requires them *before* the window opens is
   the auditor's call; this table cannot settle it.

⚠ **Why "not started" is written down at all.** Until this entry existed, a window that had never begun and a
window nobody had checked read identically from the repo. Stating NOT STARTED converts an invisible gap into a
**checkable state**: the BACKLOG §2 row stays open either way, but it can now be run in both directions.

## 5. What this file does NOT cover

**C1's applicability statements are not written here and are not derivable from the repo.** The
SOC 2 in-scope service list, the ISO 27001 ISMS boundary, and the HIPAA/PCI answers depend on org
facts (which services the org operates, what it commits to) plus plan §6 Q4/Q5. ⛔ Do not generate
them from this table — a scope statement invented from the code is exactly the document an auditor
disproves first. They are org input; this table is ready to be cited by them once they exist.
