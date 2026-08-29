# NFR-7 — Compliance certifications plan (SOC 2 Type I/II → ISO 27001 → FedRAMP alignment; HIPAA/PCI scoped)

**Status:** DRAFT 2026-07-23 — **sequencing ANSWERED 2026-07-25 (§6 Q1: parallel, SOC 2 is not a gate).**
Expanded same day to
control-level coverage for SOC 2 / ISO 27001 / FedRAMP (§2b, C6). · **Owner:** enterprise-PM track
(org-side controls are Gamma Analytics', not this repo's — §5 boundary). · **Companions:**
`../REQUIREMENTS.md` NFR-7 (PARTIAL: posture shipped, certifications not started) ·
`rbac-abac-plan.md` (CC6 access-control dependency) · `../EDITIONS.md`.

## 1. What NFR-7 actually requires

Certifications certify **the organization operating the product**, not the codebase alone. This plan
covers the *product-side* controls + evidence (what this repo can ship); org-side items are named in
§5 so they're visible, but not planned here. Recommended sequence (Q1 to confirm):

1. **SOC 2 Type I** first — fastest to attain, the one prospects ask for, and ~90% of its technical
   controls are already shipped product posture.
2. **SOC 2 Type II** — same controls + a 3–12-month observation window; starts automatically once
   Type I controls operate.
3. **ISO 27001** — heavy control overlap with SOC 2 (one control matrix, two mappings); org-ISMS
   dominated. Product-side work = the Annex A technological controls (§2b-2).
4. **FedRAMP — 800-53 control *alignment* + ATO-support package (C6), not authorization.**
   FedRAMP authorizes *cloud services*; Inspecto is self-hosted software, so the honest posture is
   "FedRAMP-ready / supports your ATO": NIST 800-53 rev 5 control implementation statements for
   the controls the product implements or inherits (§2b-3), a hardening guide, FIPS-mode crypto
   option. Actual authorization only enters scope if a hosted SaaS offering ever exists (§6 Q5).
5. **HIPAA / PCI — scoping statements only, demand-gated.** One-page applicability statement each
   (self-hosted: no PHI/PAN leaves the customer's deployment; Inspecto is in the data path only
   where the customer routes it), NOT certification work.

## 2. Control inventory — what's already shipped (map, don't rebuild)

The pitch: Inspecto's architecture *is* the control set. C2 turns these into an auditor-readable
matrix (SOC 2 trust-services criteria ↔ ISO 27001 Annex A ↔ product feature):

| Shipped posture | Requirement row | SOC 2 criteria (indicative) |
|---|---|---|
| Immutable who-did-what **Audit Log** (3-layer: file/batch, provenance, audit) | OPS-3 | CC4/CC7 monitoring |
| OIDC resource-server auth, BFF session, no-browser refresh token, TLS | SEC-5/6, W6 | CC6 logical access |
| Data-scoped grants (SEC-7d caseType), X-Actor spoof rejection | SEC-7 | CC6 |
| Secrets: env/file/JCEKS keystore, no plaintext in config | SEC-8 | CC6.1 |
| Write-root fail-closed gate (503), ConfigSafetyValidator, path jail | SEC-9 | CC8 change control |
| **Lean SBOM by design** — framework-free core, SDK-free connectors, network deps isolated | PKG-2, NFR-4 | CC9 / supply chain |
| Air-gap operation (no egress, offline basemap/AI absent) | NFR-4 | CC6.7 |
| Quality gates: GAUNTLET, axe CI, token lint, branch-policy CI, Conventional Commits | NFR-9 | CC8 SDLC |

**Known control gaps** (product work, C4): SBOM as a *generated artifact* (the leanness is real but
unattested) · log/audit retention configuration + documented backup/restore · vulnerability-
management evidence (deps are few by design, but there's no scan/attestation step) · access-review
support (needs `rbac-abac-plan` R5 effective-grants view) · full RBAC enforcement (CC6 wants
role-based access — currently capability gates exist, role enforcement is the R-workstreams).

## 2b. Framework coverage — control-level detail

### 2b-1 · SOC 2 Type I & Type II (AICPA Trust Services Criteria)

**Type I** attests control *design* at a point in time; **Type II** attests *operating
effectiveness* over a window (target 6 months; 3 is the floor auditors accept). Same controls —
Type II adds the burden that every control must leave **time-stamped evidence continuously**,
which is why C3 (evidence automation) is sequenced before the observation window opens.

Scope: the **Security** (common criteria) category mandatory; add **Availability** and
**Processing Integrity** (Inspecto's pitch — conservation invariants, batch-atomic commits,
quarantine — *is* processing integrity); defer Confidentiality/Privacy to demand.

| TSC | What it wants | Product coverage | Gap → workstream |
|---|---|---|---|
| CC1–CC3 (governance, risk) | Org structures, risk assessment | — org-side (§5) | — |
| CC4 (monitoring) | Ongoing control monitoring | Signals ledger, Metrics, Alert Rules, Audit Log | evidence runbook (C3) |
| CC5 (control activities) | Controls tied to objectives | controls-matrix itself (C2) | C2 |
| CC6 (logical access) | AuthN/AuthZ, least privilege, access reviews | OIDC + BFF session, capability gates, SEC-7d data scopes; **full RBAC = rbac-abac-plan R1–R5** | R-workstreams land before the Type II window; access-review view (R5) |
| CC7 (system ops) | Anomaly detection, incident mgmt | Alert Rules, Incidents/Case Manager, notification feed | incident-response policy doc (C5) |
| CC8 (change mgmt) | Authorized, tested, tracked changes | Conventional Commits + branch-policy CI + GAUNTLET + all-editions builds; release checksums/signing (C3) | document the SDLC as a control narrative (C2) |
| CC9 (risk mitigation / vendors) | Vendor + business-disruption risk | lean SBOM, air-gap, no runtime SaaS deps | SBOM artifact (C3); backup/restore runbook (C4) |
| A1 (Availability) | Capacity, recovery | single-node by design (NFR-8), crash-isolated idempotent Runs | documented RTO/RPO + restore drill (C4) |
| PI1 (Processing Integrity) | Complete, accurate, timely processing | Expectations, quarantine semantics, provenance + conservation invariant (OPS-5), ContentHash parity | OPS-5 live verification (already BACKLOG §2) |

### 2b-2 · ISO 27001:2022 (Annex A — 93 controls, 4 themes)

Organizational (5.x), People (6.x), Physical (7.x) are org-/deployment-side (§5; physical is
inherited from the customer's site in self-hosted deployments — state it, don't own it). The
product-side theme is **Technological (8.x)**:

| Annex A (8.x) | Product coverage / gap |
|---|---|
| 8.2–8.5 privileged access, restriction, secure auth | OIDC + Roles/Capabilities (R-workstreams); privileged = Admin role + `canConfigureAccess` |
| 8.8 vulnerability management | **gap** → C4 offline dep-review step + advisory-watch process (few deps by design helps) |
| 8.9 configuration management | TOON config + ConfigSafetyValidator + write-root gate; hardening guide (C6) doubles here |
| 8.12 data-leakage prevention | air-gap/no-egress posture (NFR-4) — a genuine differentiator, write it up |
| 8.13 backup | **gap** → C4 backup/restore runbook + restore drill evidence |
| 8.15–8.17 logging, monitoring, clock sync | Audit Log + Signals + Metrics; clock-sync = deployment note in hardening guide |
| 8.24 cryptography | TLS, JCEKS secrets, release signing; crypto policy doc (C5); FIPS option (C6) |
| 8.25–8.31 secure SDLC, testing, environments | NFR-9 gates + branch policy + editions CI — mostly narrative work (C2) |
| 8.32 change management | same CC8 evidence as SOC 2 — one narrative, two mappings |

**Statement of Applicability (SoA)** — the ISO deliverable enumerating all 93 controls with
applicable/excluded + justification — is generated *from* the C2 matrix (add an SoA export column
rather than writing a second document).

### 2b-3 · FedRAMP (NIST 800-53 rev 5, Moderate baseline as the working target)

Deliverable = **C6: control implementation statements** (SSP-ready language an agency can lift
into their ATO package) covering the families the product implements, plus explicit
inheritance/customer-responsibility statements for the rest — the standard "customer
responsibility matrix" shape:

| Family | Posture |
|---|---|
| AC (access control) | product-implemented: RBAC/ABAC (R+A workstreams), session mgmt, least privilege; AC-2 account lifecycle = *inherited from customer IdP* (document the claim contract) |
| AU (audit) | product-implemented: Audit Log, signals; **gaps**: AU-4/AU-11 retention config (C4), AU-9 audit-record protection statement (append-only guarantees — verify + document what the store actually guarantees, don't overclaim) |
| IA (identification & authN) | delegated to customer IdP via OIDC — IA-2 MFA etc. are *inherited*; product statement = "enforces authenticated subjects when an Authenticator is present" |
| SC (system & comms protection) | TLS in transit; **gap**: SC-13 *FIPS-validated* crypto — add a documented FIPS mode (run on a FIPS-enabled JVM/provider; verify Nimbus/JCEKS paths under it) (C6) |
| CM (config mgmt) | ConfigSafetyValidator, write-root gate, TOON-as-config-baseline; hardening guide (C6) = CM-6 baseline |
| SI (system integrity) | Expectations/quarantine (SI-10 input validation is literally the product), SI-2 flaw remediation = release/backport process (BRANCHING merge-forward is the evidence) |
| RA (risk assessment) | RA-5 vuln scanning: offline constraint → pinned-deps diff + SBOM-against-advisory check (C4); org owns the risk-assessment process |
| CA / CP / IR / PE / PS / SA-9 etc. | org- or customer-side; named in the responsibility matrix, not product work |

**Not undertaken:** 3PAO assessment, ConMon program, POA&M operation — those exist only if a
hosted offering makes Inspecto a CSP (§6 Q5).

## 3. Workstreams

- **C1 — scoping decisions + framework applicability statements.** Confirm §1 sequence with
  product; write the four one-page applicability statements (SOC 2 in-scope services; ISO 27001
  ISMS boundary; FedRAMP/HIPAA/PCI applicability given self-hosted deployment). →
  `compliance/scope/`.
- **C2 — control matrix. ✅ DELIVERED 2026-08-28 → [`compliance/controls-matrix.md`](../../compliance/controls-matrix.md).**
  One table: control id → SOC 2 TSC ↔ ISO 27001 Annex A ↔ NIST 800-53 → implementing product
  feature (file/route/gate) → evidence source → responsibility (product / org / customer /
  IdP-inherited). Seeded from §2 + §2b, then **grounded row by row against the code** rather than
  copied. The ISO **SoA** and the FedRAMP **customer-responsibility matrix** are *exports of this
  table*, never separate documents. Living doc — update a control's row in the same change that
  touches its implementation.
  - ⚠ **`compliance/**` was outside every vocabulary-guard pass on the day it was created.** It is
    tracked product documentation shipped in the deploy bundle (§4), so it is as user-facing as
    `docs/okf` — added to the guard's `DOC_TREES` in the same change, and the guard's summary label
    is now DERIVED from that list instead of restating it.
  - Its §4 consolidates every gap the matrix found (G1–G9) so C3/C4 schedule from one place.
- **C3 — evidence automation (product work).**
  - **Release integrity (CC8): ✅ THE PRODUCT HALF IS ALREADY SHIPPED — corrected 2026-08-28.**
    `inspecto/package.ps1` **always** writes a `sha256sum`-compatible `<artifact>.sha256` (`:845-856`),
    and `-Sign` emits a GPG detached `<artifact>.asc` (`:869`) with the key supplied via
    `-SigningKey`/`$env:INSPECTO_SIGNING_KEY` and never baked in. 🔴 This row read as unbuilt work for
    months. What actually remains: the **customer verification runbook** (unblocked, G2), and making
    signing *routine* rather than merely possible, which is §6 Q3 (where the org's key lives, G3).
  - **SBOM artifact — CONFIRMED STILL A GAP (2026-08-28, G1):** a repo-wide search finds no
    CycloneDX/SPDX generation anywhere, only prose references. The leanness is real and
    **unattested**. Emit CycloneDX JSON at package time from the offline Maven reactor's
    resolved dependency list (hand-rolled step in `package.ps1`/a small Maven exec — no new online
    plugin; the dep list is tiny by design, which is the NFR-7 selling point). Ship it inside the
    deploy bundle next to the .sha256/.asc.
  - **Audit-log export: ✅ RUNBOOK WRITTEN 2026-08-28** →
    [`compliance/evidence/audit-log-extraction.md`](../../compliance/evidence/audit-log-extraction.md)
    (`/events/export`, `/events/search`, cursor-paged `/api/v1/events`, the `AuditAttrs` field list,
    the two deliberate exclusions, chain of custody). 🔴 Writing it surfaced **AUDIT-CSV-1**: the CSV
    export drops EVERY audit attribute, so a `format=csv` pull of `type=AUDIT` looks complete and
    records nothing — the runbook mandates JSON. Retention statement still owed (C4/G5).
  - **CI evidence:** branch-policy + all-editions build + GAUNTLET runs are the CC8 evidence —
    document where an auditor finds them.
- **C4 — close the product control gaps** (each its own small change, normal release flow):
  retention config for audit/notification/signal stores (partially exists: `notification_prune`,
  `ledger_prune`, `incident_purge` — document + fill gaps) · ~~backup/restore runbook for the write
  root + state stores~~ 🔴 **that runbook ALREADY EXISTS** (`docs/ops/backup-restore-runbook.md` —
  Backup / Verify / Restore through the `maintenance` Job Type, with the path-containment preamble;
  corrected 2026-08-28). The real remainder is narrower: **an RTO/RPO statement and a recorded
  restore drill**, neither of which the runbook carries · **AUDIT-CSV-1** (see C3 above) · ~~dependency-review step (offline: a pinned-versions diff check in CI, not a
  scanner SaaS)~~ **✅ SHIPPED 2026-08-28** — `tools/check-dependencies.mjs` diffs the resolved
  runtime graph against `tools/dependencies.lock`, wired into `ci.yml`; the reactor's own modules are
  excluded by a set **derived from the build** (this repo builds two groups, and a one-group constant
  silently treated seven in-repo artifacts as third-party). 🔴 Building it MEASURED the "lean SBOM"
  claim: **94 third-party artifacts**, dominated by the optional AI stack — leanness belongs to the
  lean core / per-edition bundle, **not** the reactor, so **G1's SBOM must be generated per packaged
  bundle** or it attests a set no customer installs · the RBAC dependencies stay in
  `rbac-abac-plan` (R1/R2/R5) — this plan only *consumes*
  them; do not partially implement access control here (BACKLOG §6 rule).
- **C5 — policy pack.** The written security policies auditors require (access control, crypto,
  change management, incident response, retention) — templates live in `compliance/policies/`;
  content is org-owned (§5) but versioned here so every deployment ships with its policy set.
- **C6 — FedRAMP ATO-support package (§2b-3).** 800-53 control implementation statements
  (SSP-ready) + the customer-responsibility matrix (C2 export) + a **hardening guide** (TLS
  config, IdP claims contract, write-root/permissions, clock sync, CM-6 baseline) + a documented
  **FIPS mode** (run + verify the Nimbus/JCEKS/TLS paths on a FIPS-enabled JVM provider — a test
  matrix leg, not new crypto code). → `compliance/fedramp/`. Demand-gated start; sequenced after
  C2 exists to export from.

## 4. Repo layout

```
compliance/
  scope/                      # C1 applicability statements (soc2, iso27001, fedramp, hipaa, pci)
  controls-matrix.md          # C2 — the single mapping table
  policies/                   # C5 — numbered policy docs (access, crypto, change mgmt, …)
  evidence/                   # C3 runbooks (release verification, audit-log export, CI pointers)
  fedramp/                    # C6 — 800-53 implementation statements, responsibility matrix,
                              #      hardening guide, FIPS-mode notes
```

Tracked in git (it's product documentation, not secrets); shipped in the deploy bundle's docs.

## 5. Boundary — org-side (named, NOT planned here)

Auditor selection + engagement, the ISMS itself, HR/vendor/asset management policies in force,
penetration test engagement, risk assessments, the Type II observation window, BAA legal templates.
None of these are repo work; the repo's job is to make the product-side answer "yes, and here's
the evidence" for every technical control an auditor asks about.

## 6. Open questions

1. ~~**Sequence sign-off (product):**~~ **ANSWERED 2026-07-25 (BACKLOG D1): no strict sequence — NFR-7 work
   proceeds in parallel; SOC 2 is not a gate on the rest.** The plan is *not* re-ordered (SOC 2 Type I → II →
   ISO 27001 remains the expected certification order where certifications interact), but engineering
   execution is explicitly unblocked from waiting on it. Rationale: most of C1–C6 is shared control evidence
   that every framework consumes — an SBOM, release signing, audit-log coverage, access-control evidence do
   not become different artifacts because SOC 2 goes first — so serializing behind one certification would
   idle work that all of them need. Consequence: **C1 is no longer a blocking predecessor.** Two caveats that
   survive the parallelism:
   * Q6 stands unchanged — the SOC 2 **Type II observation window** still cannot open until the RBAC/ABAC
     R-workstreams are live, because CC6 controls must *operate* during the window (they are now complete, so
     this is ready to schedule, but it is a real ordering constraint, not a preference).
   * Anything requiring an **external party** (3PAO, auditor, certification body) is still sequential in
     practice and paced by them, not us — parallelism applies to the evidence work we own.
2. ~~**SBOM format:**~~ **ANSWERED 2026-08-30 (operator): BOTH CycloneDX and SPDX**, emitted side by
   side. Rationale: neither format is dropped, so no downstream procurement demand forces a re-cut.
   ⚠ The cost is that the generation step and the thing that can drift are both doubled — the two
   documents must be produced from the **same** resolved set in the same packaging run, never
   independently. 🔴 **Scope, unchanged and load-bearing (CC9): generate PER PACKAGED BUNDLE, not
   from the reactor** — the reactor resolves 94 artifacts dominated by the optional AI stack, so a
   reactor SBOM attests a set no customer installs. `tools/dependencies.lock` is a review baseline,
   not an SBOM. ⇒ G1 is unblocked; what remains is build work.
3. ~~**Where does the GPG release key live**~~ **ANSWERED 2026-08-30 (operator): the CI secret
   store.** The private key is held in the CI provider's encrypted secrets and **no shift holds it
   locally**; signing therefore becomes a mandatory step of the release pipeline rather than an
   optional `package.ps1 -Sign` flag a release can forget. ⚠ Two consequences follow and are not
   optional: a release cut **outside** CI cannot be signed at all (that is the intended trade), and
   the key's own custody — rotation, who can read the secret, what happens on CI-provider
   compromise — becomes a CC6 access-control question rather than a personal one. ⇒ G3 is
   unblocked; what remains is wiring signing into the release pipeline.
4. ~~Does any near-term prospect actually need HIPAA/PCI language beyond the applicability
   statement?~~ **ANSWERED 2026-08-30 (operator): YES — HIPAA and/or PCI demand exists.** C1's
   one-pagers therefore **grow controls**; an applicability statement alone is no longer the
   deliverable. ⛔ **Do not generate those statements from the controls matrix** (standing
   instruction, gate-register §2 cluster C) — the matrix records what is built, and an applicability
   statement asserts what is *in scope*, which is an org claim. This one still needs the operator to
   say which of the two, and for which prospect, before C1 can be scoped.
5. ~~**Is a hosted SaaS offering ever planned?**~~ **ANSWERED 2026-08-30 (operator): NO — self-hosted
   only.** The previously *assumed* answer is now a stated one. Consequences, both negative and both
   worth keeping explicit: FedRAMP does **not** become a real authorization program (see Q7), and
   SOC 2 scope does **not** grow the Availability category's infra controls. The ISMS boundary stays
   at the product we ship, not a service we operate.
6. **SOC 2 Type II observation window length** (3 vs 6 months) and when to open it — gated on the
   rbac-abac-plan R-workstreams landing, since CC6 controls must operate during the window.
7. ~~**FedRAMP baseline target** for C6 statements~~ **ANSWERED 2026-08-30 (operator): Moderate.**
   The assumed baseline is now stated. 🔴 **Read together with Q5:** Moderate is the baseline C6
   *statements* are written against — it does **not** make FedRAMP an authorization program, because
   Q5 answered self-hosted-only and authorization only enters scope if a hosted offering exists.
   C6 stays **alignment at the Moderate baseline**. No 3PAO, no ConMon, no POA&M.
