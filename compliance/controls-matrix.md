# Control matrix — the single mapping table (C2)

**Status:** LIVING DOCUMENT, first cut 2026-08-28. · **Plan:**
[`docs/superpower/compliance-certifications-plan.md`](../docs/superpower/compliance-certifications-plan.md)
workstream **C2**. · **Owner:** enterprise-PM track; product rows are this repo's.

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
| CC6 | Logical access: authN/authZ, least privilege, access review | OIDC resource-server authN (`inspecto-security/.../OidcAuthenticator.java`) behind the `Authenticator` SPI (`inspecto/.../control/Authenticator.java`) so the core stays identity-agnostic; capability gates + `Roles`/`AccessDecider` (`inspecto/.../control/`); published capability set pinned by `CapabilityManifestTest` | `OidcAuthenticatorTest`, `CapabilityManifestTest`, `ExchangeAttributeScopeTest` (per-request subject isolation) | product + IdP | Account **lifecycle** (joiner/mover/leaver) is IdP-inherited — document the claim contract. Access-**review** view = `rbac-abac-plan` R5. |
| CC6.1 | Credentials are not stored in the clear | `SecretResolver` (`inspecto-acquire/`) resolves references only — `${ENV:NAME}`, `${FILE:/path}`, JCEKS keystore via `secrets.keystore.path`/`.type`/`.password`; a connection profile never holds a secret. `SecretScrubber` (`inspecto-event/`) keeps them out of events | `SecretResolverTest`, `SecretScrubberTest`; **`tools/check-secrets.mjs`** fails the build on a reintroduced literal (CI `ci.yml` + `.githooks/pre-push`) | product | ⚠ **SEC-INCIDENT-1**: rotation at the issuer is still outstanding (BACKLOG §5). The guard prevents recurrence; it does not remediate history. |
| CC6.7 | Restrict transmission / movement of data | Air-gap operation by design: no runtime SaaS dependency, offline basemap, optional AI modules absent from the lean core. CI **enforces** the core's leanness (`ci.yml` "lean core stays kernel-free" — greps sources AND the resolved `dependency:tree`) | The CI step's exit code, per run | product | — |
| CC7 | System operations: anomaly detection, incident management | Alert Rules → Incidents/Case Manager; notification feed; `incident_purge` maintenance task (`inspecto-engine/.../job/IncidentPurgeTask.java`) | Route suites; the task's own dry-run report | product | **Incident-response policy** document → C5 (org-owned content) |
| CC8 | Change management: authorized, tested, tracked | Conventional Commits + retired-branch rejection (`.github/workflows/branch-policy.yml`); full reactor tests (`ci.yml`); UI gate — design-token lint, unit tests, production build (`.github/workflows/ui.yml`); vocabulary guard (`tools/check-vocabulary.mjs`). Config change is gated at the write path: `ConfigSafetyValidator` + `PathJail` (`inspecto-config/.../safety/`), write-root fail-closed 503 | Per-run CI logs; `PathJailTest`, `JobPathContainmentTest` | product | Write the SDLC as a **control narrative** (this table's CC8 row is the seed, not the narrative) |
| CC8 (release integrity) | Released artifacts are verifiable | ✅ **SHIPPED** — `inspecto/package.ps1` **always** writes a `sha256sum`-compatible `<artifact>.sha256` (`:845-856`); `-Sign` additionally emits a GPG detached `<artifact>.asc` (`:869`), key supplied via `-SigningKey`/`$env:INSPECTO_SIGNING_KEY`, never baked in | The `.sha256`/`.asc` files shipped beside each artifact | product | 🔴 **The plan's C3 lists this as work to do — it is already built.** What remains of C3 here is the *customer verification runbook*, and making signing **routine** depends on plan §6 Q3 (where the org's GPG key lives). |
| CC9 | Risk mitigation, vendor risk | Lean dependency set by design (framework-free core, SDK-free connectors, network deps isolated); air-gap posture | `dependency:tree` per build | product | 🔴 **SBOM is a real gap** — repo-wide search finds no CycloneDX/SPDX generation, only prose. The leanness is real and **unattested**. → C3 |
| A1 | Availability: capacity, recovery | Single-node by design (NFR-8); crash-isolated idempotent Runs (`OVERWRITE_OR_IGNORE` output idempotence) | Engine suites | product + customer | Runbook EXISTS (`docs/ops/backup-restore-runbook.md`); what is missing is an **RTO/RPO statement + a recorded restore drill** → C4 (G6) |
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
| 8.15–8.17 logging, monitoring, clock synchronisation | Audit trail (`inspecto/.../control/AuditTrail.java`) captures state-changing Control API requests as append-only `AUDIT` events from a **single dispatch seam**, so every current and future mutating route is covered without per-handler wiring; signals + metrics; operations time zone is explicit (`-Dops.timezone`) | product + customer | Applicable. ⚠ **Stated boundary, do not overclaim:** `AuditTrail` covers mutating `POST`/`PUT`/`DELETE`, `GET …/export`, and access-denied attempts; **auth-gated events (login/MFA/password, true 401/403) are out of its scope** — they arrive with the security module. Clock sync itself is a deployment note (C6). |
| 8.24 use of cryptography | TLS in transit; JCEKS secret storage; release signing (`package.ps1 -Sign`) | product + org | Applicable. Crypto **policy** → C5; FIPS mode → C6 |
| 8.25–8.31 secure development, testing, environments | NFR-9 quality gates; branch policy; per-edition builds | product | Applicable — mostly narrative work off this table |
| 8.32 change management | Same evidence as SOC 2 CC8 — **one narrative, two mappings** | product | Applicable |

## 3. NIST 800-53 rev 5 — families (FedRAMP alignment, C6 input)

Posture: **"FedRAMP-ready / supports your ATO"**, not authorization — Inspecto is self-hosted
software, so there is no CSP boundary to authorize (plan §2b-3). Baseline target Moderate is an
*assumption* pending plan §6 Q7.

| Family | Product statement | Resp. | Gap |
|---|---|---|---|
| AC — access control | Session management, capability gates, least privilege; data-scoped grants | product | **AC-2 account lifecycle is inherited from the customer IdP** — the deliverable is the documented claim contract, not an implementation |
| AU — audit | Append-only audit events from one dispatch seam; signal ledger; provenance/lineage | product | **AU-4/AU-11 retention configuration** → C4. **AU-9 audit-record protection**: verify and state what the store *actually* guarantees — ⛔ do not assert immutability the storage layer does not enforce |
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
| G1 | SBOM is not generated (CycloneDX from the offline reactor's resolved dependency list) | C3 | Plan §6 Q2 (CycloneDX vs SPDX) — recommendation stands at CycloneDX |
| G2 | ~~Customer **verification runbook** for `.sha256`/`.asc`~~ | C3 | ✅ **CLOSED 2026-08-28** → [`evidence/release-verification.md`](evidence/release-verification.md) |
| G3 | Signing is *possible* but not *routine* | C3 | Plan §6 Q3 — where the org's GPG key lives |
| G4 | ~~Auditor **audit-log extraction runbook**~~ | C3 | ✅ **CLOSED 2026-08-28** → [`evidence/audit-log-extraction.md`](evidence/audit-log-extraction.md). It surfaced **G10** below. |
| G5 | Retention configuration for audit / notification / signal stores | C4 | Partially exists (`notification_prune`, `ledger_prune`, `incident_purge`) — the work is documenting what exists and filling the rest |
| G6 | ~~Backup/restore runbook~~ + **RTO/RPO statement + a restore drill record** | C4 | 🔴 **NARROWED 2026-08-28 — this row was wrong as written.** `docs/ops/backup-restore-runbook.md` **already exists** (Backup / Verify / Restore, path-containment preamble, all through the `maintenance` Job Type). The plan's C4 called the whole runbook a gap; the real remainder is that the runbook states **no RTO/RPO** and no drill has been recorded (zero hits for either). Write those two, do not rewrite the runbook. |
| G7 | Offline dependency-review step in CI (pinned-versions diff) | C4 | Nothing |
| G8 | Access-review (effective-grants) view | C4 | `rbac-abac-plan` R5 |
| G9 | FIPS-mode documentation + verification leg | C6 | Demand-gated; sequenced after this table exists |
| G10 | 🔴 **`GET /events/export?format=csv` drops every audit attribute** — its seven columns (`timestamp,level,type,source,pipeline,correlationId,message`) carry no `actor`, `action`, `target`, `ip` or `policy`, so a CSV export of `type=AUDIT` looks complete and omits everything the audit records. JSON (`Event.toMap`) carries `attributes` whole | C4 (product) | Nothing. Found writing G4's runbook, which now says JSON-only in bold. Fix = an audit-shaped CSV projection, or refusing `format=csv` for `type=AUDIT` |

**Not gaps — deliberate positions.** Single-node availability (NFR-8), no hosted offering
(plan §6 Q5 assumed self-hosted only), no 3PAO/ConMon/POA&M program. Each is a *stated posture*;
restating it as a gap is how a matrix grows work nobody chose.

## 5. What this file does NOT cover

**C1's applicability statements are not written here and are not derivable from the repo.** The
SOC 2 in-scope service list, the ISO 27001 ISMS boundary, and the HIPAA/PCI answers depend on org
facts (which services the org operates, what it commits to) plus plan §6 Q4/Q5. ⛔ Do not generate
them from this table — a scope statement invented from the code is exactly the document an auditor
disproves first. They are org input; this table is ready to be cited by them once they exist.
