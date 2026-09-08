---
type: Capability
area: CMP
title: Compliance (CMP) — capability spec
description: The requirement-of-record and as-built specification for the compliance posture — the one control matrix and its honesty rules, the three framework mappings, the four shipped supply-chain and retention controls, the seven-file evidence pack, the gap ledger, and the externally-gated certification program with its measurement.
status: current
written: 2026-09-08
supersedes-rows: EDITIONS CMP-01..CMP-08 and REQUIREMENTS NFR-7 (this file corrects them, see §2)
---

# Compliance (`CMP`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/EDITIONS.md`'s `CMP-*` rows or `docs/REQUIREMENTS.md` `NFR-7`, **this file wins** and the
> disagreement is stated in place. §3 is the as-built specification, §4 the dated decisions, §5 what is
> not built, §6 what was refused or superseded, §7 the pointers, §8 how it is verified.
>
> ⛔ **This is a new area whose rows were orphaned.** `docs/GLOSSARY.md` §14: the compliance rows had no
> functional home and hung under `NFR-7`; `EDITIONS.md` already numbered the shipped controls, so the
> prefix is **inherited, not invented**.
>
> ✅ **This is the most disciplined register in the repo — and its discipline has three specific
> failure modes.** `compliance/controls-matrix.md` requires that every product row name a real
> artifact, and on 2026-09-08 **every class it cites exists at the path it names** — after twelve areas
> of stale rows, that is the exception. But its rules cannot catch what §2 records: **a line number
> that moved**, **a promise whose precondition later landed**, and **an evidence file whose central
> claim its own tripwire predicted would expire**. Trust the artifacts, re-derive the line numbers, and
> re-read any row whose dependency has shipped since it was written.

## 1. Purpose & scope

Compliance here is **one table and the discipline that keeps it honest**. The product's job is not to
be certified; it is to make a certification *possible* by stating, per control, what the software
actually does, where the boundary is, and whose responsibility the remainder is.

**In scope**

* **The control matrix** — the single mapping table, its four honesty rules, the responsibility
  vocabulary, and the exports other deliverables are derived from.
* **The three framework mappings** — SOC 2 Trust Services Criteria, ISO 27001:2022 Annex A, and NIST
  800-53 families for federal alignment — plus the scope boundary that self-hosted software imposes.
* **The four shipped product controls** — the per-bundle software bill of materials, release signing,
  the dependency-review baseline, and audit retention.
* **The evidence pack** — seven auditor-facing runbooks and statements.
* **The gap ledger** — ten numbered gaps, their workstreams, and what each is blocked on.
* **The externally-gated program** — the seven certification items, each with a landing place and a
  measurement that a shift can run.
* **What this area deliberately does not cover**, and why saying so is part of the control.

**Out of scope (owned elsewhere)**

* **Every control's underlying mechanism.** The audit trail and retention are `OPS`
  ([`observability/observability.md`](../observability/observability.md)); authentication, the
  authorization gate, secrets and the access review are `SEC`
  ([`security/security.md`](../security/security.md)); bundling, signing mechanics and the runtime are
  `PKG`; the quality gates and the colour and accessibility checks are `UI`
  ([`surfaces/surfaces.md`](../surfaces/surfaces.md)) and `TOOL`. This area **cites** them and states
  the boundary; it does not own them.
* **The organisation's own program** — applicability statements, the information-security management
  boundary, policy content, auditor engagement, penetration testing. These are org input by decision,
  and ⛔ **must not be generated from the repo** (§3.7).
* **Certification itself.** No audit has been performed and none is claimed.

## 2. Requirements of record

| ID | Requirement (as it holds) | Status of record | Edition of record |
|---|---|---|---|
| **CMP-01** | Software bill of materials per bundle, in both common formats, inside the zip | ✅ SHIPPED 2026-09-02. Generated **per packaged bundle, not from the reactor** — a scope correction that matters, because the reactor set includes optional modules an edition does not ship. Both formats come from **one** dependency resolution in the same packaging run, deliberately, so the two documents cannot drift. Each component carries a hash, its declared licence and a package URL; first-party jars are hashed as shipped | All |
| **CMP-02** | Signed releases with a checksum and a detached signature, fail-closed | ✅ SHIPPED 2026-09-02 — and 🔴 **never executed.** The workflow triggers on a version tag and landed 2026-09-06; twenty-eight version tags exist but the newest is 2026-06-03 on the retired line, so **no tag has been cut since the workflow existed**. The editions row says "unexercised until the first tag" and is right; the gap ledger records it closed without that caveat | All |
| **CMP-03** | Dependency-review guard in continuous integration | ✅ SHIPPED. ⛔ **It is a review device, not a scanner** — it proves nobody looked, never that a version is safe. ⚠ **Scope corrected 2026-09-07**: until then it resolved with no edition profile, so the security module's cryptography tree — the dependency a security reviewer most wants to see — **was never under review while this row already said closed**. It now resolves the enterprise profile, 25 modules rather than 23 | All |
| **CMP-04** | Audit retention, a one-year window | ✅ SHIPPED 2026-09-02, **operator-scheduled**. A deployment that never authors the job has stated policy only, and the row says so. ⚠ The window is an *upper bound on the store*; it does not override the decision that a purged incident's own history is retained | All |
| **CMP-05** | Control matrix + auditor evidence | ✅ SHIPPED | — / ✅ / ✅ — "Personal has no compliance scope by definition". ⚠ **That is a product stance, not a build fact**: the matrix and the evidence pack are plain files in the repository, so nothing withholds them from a Personal deployment. The gating is about who is *promised* the posture |
| **CMP-06** | Federal cryptography mode | 🔲 Open — gap G9, demand-gated and deliberately sequenced after the matrix existed | — / 🔲 / 🔲 |
| **CMP-07** | The access-review residual | ✅ SHIPPED. ✅ **Row corrected 2026-09-07 — it had been stale**: it read planned and cited its gap as open, when that gap had closed on 2026-08-28 and the capability it waited on had shipped five weeks earlier | — / ✅ / ✅ |
| **CMP-08** | Certifications | 🔲 Not started, **Enterprise only** (operator, 2026-09-02), org-paced | — / — / 🔲 |
| **NFR-7** | Compliance posture | 🟡 PARTIAL, and the cell's phrasing is right: the module shipped, certifications are not started. ⚠ **CORRECTION to how it is read:** the row is not one undifferentiated gap. It is **seven named items**, six of which need an external party or org authorship, and **one of which needs only a named owner and a cadence** (§3.7) | — |

🔴 **One row promises something the code never delivered.** The logging control says auth-gated events
— sign-in, token refresh, sign-out — *"arrive with the security module"*. **The security module shipped**,
and those three routes still emit **nothing** to the audit trail. So the caveat is not a narrow boundary
awaiting work; it is an **unfulfilled promise stated as a pending one**, which is the one failure mode
rule 2 cannot catch — the row was honest when written and quietly became false when its dependency
landed. Sign-in is unaudited; *authorization* decisions are audited. Corrected in the matrix in this
commit.

🔴 **The line numbers are stale in two documents, which is rule 1's blind spot.** The release-integrity
row and the verification runbook both cite the packaging script at lines in the 840s–880s for the
checksum, the signature and the bill of materials. Those functions now live near lines 1285, 1299 and
549; the 840s hold embedded helper-script text. Rule 1 says a row must name a real artifact **that
exists at the commit the row was written**, and it did — so a citation can satisfy the rule on the day
and mislead a reader a week later. **Cite the symbol, not the line.**

🔴 **The signing pipeline covers two editions, not three.** The workflow's own step is titled for every
edition and is followed by exactly two packaging steps, Personal and Enterprise — the Enterprise one
labelled a superset of Standard. **No Standard bundle is built, checksummed, signed, given a bill of
materials or published**, while the editions board marks both supply-chain controls green for Standard.
That is a column with no artifact behind it.

**One internal inconsistency, and it is the file catching itself.** The availability criterion still
says what is missing is a recovery-objective statement and a drill record. The consolidated gap ledger,
in the same file, says correctly that the statement **exists** with two operator-fill fields, and that
what remains is the committed targets and the first drill. The criterion row is stale against its own
gap row; that is precisely what rule 4 exists to prevent.

## 3. Specification

### 3.1 The matrix and the rules that make it usable

`compliance/controls-matrix.md` is **one table that every other compliance deliverable is an export
of, never a rewrite**. Three exports are named: the statement of applicability is the table filtered to
the ISO theme with its applicability column; the federal customer-responsibility matrix is the table
filtered to rows whose responsibility is not the product; the SOC 2 control narrative is the
implementation and evidence columns per criterion. **"Add a column before you add a document."**

Four rules keep it honest, and they are the area's real content:

1. **Every product row names a real artifact** — a file, a route, a continuous-integration step, a
   launch property — that existed when the row was written. A row with no artifact **is a gap and says
   so**.
2. **Do not overclaim.** Where the implementation is narrower than the control, the implementation
   column states the boundary and the gap column carries the remainder. *"An auditor finding a caveat we
   wrote ourselves is a good day; an auditor finding one we did not is the bad one."*
3. **Responsibility is explicit**, and takes exactly four values: **product** (this repository
   implements it), **org** (the company operates it), **customer** (the deploying organisation), and
   **identity provider** (inherited).
4. **Verify before you cite.** *"A row here is exactly as trustworthy as the last person who ran the
   grep."* Touching a control's implementation means updating its row in the same change.

⚠ **The scope boundary is stated once and does real work.** This is self-hosted software, not a hosted
service. Physical, environmental, personnel and vendor controls belong to the customer's site or to the
company, and they appear in the table **marked as such so an auditor sees they were considered** —
not so the product claims them.

### 3.2 The three framework mappings

**SOC 2.** Security's common criteria are mandatory; availability and processing integrity are
included; confidentiality and privacy are deferred until demand. Governance, risk assessment and
organisational structure are entirely org-side and are listed **only so the boundary is visible**. The
product-side rows map to real machinery: the signal ledger and alert rules for ongoing monitoring; the
authentication seam, capability gates and data-scoped grants for logical access; reference-only secret
resolution for credential storage; the no-egress posture for restricting data movement — ⚠ **which is a *packaging*
guarantee, not a runtime network control.** A test asserts the hosted model libraries are absent from
the classpath, and the evidence file names the boundary itself: there is no firewall, proxy or name
resolution control anywhere in the product, and a message-broker connector makes real outbound
⛔ connections to whatever an operator configures. **Do not conflate this with the pipeline's
"lean core" guard**, which bars the core from depending on the agent kernel and is a different control; alerts into
incidents for system operations; conventional commits, the branch policy, the reactor tests, the UI
gate and the vocabulary guard for change management, plus the write-path gates for configuration
change — and those gates are **one shared implementation**, not a convention: a single class holds the
four stages in order (write-root 503, name and shape 422, path jail 403, conflict 409) and **thirteen
route modules import it**, so the order is structural rather than remembered. ⚠ There is **no test that
asserts the order as a sequence**; it holds by call-site construction and is exercised only indirectly.
The pinned dependency diff covers vendor risk; single-node design with idempotent runs for
availability; and — the strongest row in the table — **input validation, which is literally the
product**: expectations, quarantine semantics, per-file status, provenance and lineage ledgers, and the
conservation invariant.

**ISO 27001:2022.** Organisational, people and physical themes are excluded **with justification** in
the applicability export rather than implemented. The product theme is the technological one, and it
maps privileged access, configuration management, data-leakage prevention, logging and monitoring,
cryptography, secure development, and change management — the last sharing one narrative with its SOC 2
twin, deliberately: **one narrative, two mappings**.

**NIST 800-53.** The posture is stated carefully as **"federal-ready, supports your authorisation"**,
not authorisation itself, because self-hosted software has no service boundary to authorise. The
moderate baseline is flagged as an **assumption pending an org answer**, not a commitment. Account
lifecycle is inherited from the customer's identity provider; validated cryptography is an open gap;
and flaw remediation is the release cadence.

### 3.3 The four shipped product controls

| Control | What it is | The caveat that ships with it |
|---|---|---|
| **Bill of materials** | Two formats from one resolution per packaged bundle, with hashes, declared licences and package URLs, inside the archive | The "lean" claim is **qualified by measurement**: the reactor resolves 94 third-party artifacts, dominated by the optional modules — which is exactly why generation moved to the bundle |
| **Release signing** | A checksum always, no key needed; a detached signature when signing is requested, with the key held in the pipeline's secret store. Fail-closed three ways: no signing binary, no key, or a non-zero signing exit all **throw** — fixed from an earlier silent downgrade to a warning | **Two consequences were accepted on the record**: a release cut outside the pipeline cannot be signed at all, and key custody becomes an access-control question rather than a personal one. And 🔴 the path has never run — every mechanism here is 4.x-era work and **no 4.x tag exists** (§2) |
| **Dependency review** | A committed lock of coordinates, diffed on every run, resolved under the enterprise profile | ⛔ Not a vulnerability scanner. The lock lists coordinates only — no licences, no hashes — which is why it is *not* the bill of materials. ✅ Its exit codes distinguish **drift** from **could not run**, so a broken build never reads as clean |
| **Audit retention** | A whole-day-partition delete over the durable event store, a one-year window. The window is **required with no default** — the task refuses to run without one | Operator-scheduled like every retention task; the in-memory backend has nothing durable to prune. ⛔ The task's own contract states the compliance consequence in one line: *until it is scheduled the window is stated policy only — an auditor must not be told it is enforced* |

### 3.4 The evidence pack

Seven auditor-facing documents under `compliance/evidence/`, each the answer to a gap: the access
review, the air-gap posture, audit-log extraction, audit-record protection, release verification,
retention configuration, and the recovery-objective statement with its drill record.

One control the pack rests on runs in **two** places, which is worth knowing before trusting it: the
secret scan is wired both into continuous integration and into the **pre-push hook** — a whole-tree scan
and an added-lines scan over the push range — and this clone has the hook path active, so a secret is
refused locally before it can reach a branch.

🔴 **One file's central claim is false, and the file itself predicted the day it would break.** The
audit-record protection statement asserts that no delete, overwrite or truncate path exists against the
event directory, naming the absence of a delete call anywhere in the event module. The retention task
shipped on 2026-09-02 and **deletes partition files**. The document had written its own tripwire — that
task *"will be the first code path that deletes audit data… and this document must be revised when it
lands, because the sentence 'no code path deletes a written audit file' stops being true on that day"* —
and it was edited on 2026-09-08 for an unrelated note **without** revising the claim. A self-aware
document missed its own trigger, which is the strongest argument in this area for re-reading a row when
its dependency lands rather than when someone happens to open the file.

🔴 **One file is an orphan.** The air-gap write-up is grounded, honest about its boundary, and **cited
by the matrix nowhere at all** — the data-leakage row names the posture with an empty evidence column,
and the transmission row cites a pipeline exit code instead. An evidence pack is only as good as its
index, and this file is unreachable from the table that is supposed to be the index.

⚠ **One file contradicts itself** — the retention statement says in one paragraph that the store is
unbounded *"in code until the prune task is built"* and a few lines later that the task shipped. And
⚠ **one file uses retired URLs**: the access-review runbook's three commands use the unversioned
business path that stopped being served on 2026-07-25, while its sibling runbook correctly uses the
versioned one.

Two things distinguish this pack from a marketing folder. First, **operator-fill fields are left
explicitly empty**: the recovery statement carries its committed targets as fields for the operator,
because *an org commitment must not be invented from the repository*. Second, the pack is **honest
where the product is weak** — the audit-record protection document is the one that records that the
trail is append-only **but not tamper-evident**, that no permission hardening exists, and that buffered
events are dropped past a threshold on sustained flush failure.

### 3.5 Where the identifiers live

⚠ **The matrix contains no `CMP-nn` identifier anywhere.** It is keyed on framework identifiers — the
trust-services criteria, ISO clause numbers, NIST families — plus its own gap and program numbering.
The `CMP-01` through `CMP-08` numbering exists **only on the editions board**, which is exactly what
the glossary meant by calling the prefix *inherited, not invented*. So a reader chasing `CMP-04` finds
it in one file, its control in another under a different name, and its mechanism in a third. This spec
is the first place the three are joined.

### 3.6 The gap ledger

Ten numbered gaps in one list, so scheduling happens from a single place. Eight are closed with dates
and evidence pointers: the bill of materials, the verification runbook, routine signing, the extraction
runbook, retention configuration, the dependency-review step, the access-review view, and an audit-CSV
export defect that the extraction runbook itself surfaced. Two remain: the federal cryptography leg,
demand-gated; and the recovery gap, where the repository half is closed and **the first drill is
operator-owned**.

⚠ **Two of those closures carry corrections worth keeping.** The dependency-review gap was marked
closed while the security module's dependencies were outside the resolve. And the audit-export defect's
route **moved** when the events feed became an optional module — the audit projection was deliberately
kept in the core precisely so this control stays satisfiable on the edition that has no events module.

**Not gaps — deliberate positions.** Single-node availability, no hosted offering, and no third-party
assessment or continuous-monitoring program. Each is a *stated posture*, and the file says why the
distinction matters: **restating a posture as a gap is how a matrix grows work nobody chose.**

### 3.7 The externally-gated program, and how it is measured

The certification row was once **the only gate on the whole board with no repository-side check at
all** — it named "org action and external parties" and nothing else, so no shift could tell whether any
of it had moved. It is now seven rows, each closing when **its own row carries a dated line**.

| Item | Needs |
|---|---|
| Applicability statements: the in-scope service list and the management-system boundary | org input |
| Auditor engagement | org action. ⚠ The observation window has **no recorded start date**, so its end cannot be computed — and recording that date is a separate board row whose first action is **not** external |
| Penetration test | an external party |
| Policy content — the written policies, not the controls | org authorship |
| The federal package | demand-gated; ⛔ do not start unscoped |
| The federal cryptography leg | demand-gated |
| The advisory-watch process — who watches, how often, where recorded | **org process, and the cheapest of the seven: the only one with no external dependency.** It needs a named owner and a cadence, not a vendor |

🔴 **The measurement is deliberately fragile in one specific way, and the file warns about it.** The
board's check is a **line-anchored** count of the open rows in this file. Renaming the row prefix
**silently disarms** it, and running it unanchored matches the explanatory paragraph too and reports
nine for seven rows. On 2026-09-08 it reads **seven**, and it closes at zero.

⚠ **The warning's own arithmetic is off by one, in two files.** Both say the unanchored count reports
nine; measured, it reports **eight** — the seven rows plus the one paragraph. The lesson survives the
slip, and the slip is a good illustration of it.

### 3.8 What this area deliberately does not cover

The applicability statements are **not written here and are not derivable from the repository**. The
in-scope service list, the management-system boundary and the healthcare and payment-card answers all
depend on facts about the organisation — which services it operates, what it commits to.

⛔ **Do not generate them from the table.** The stated reason is the best sentence in the file: *a scope
statement invented from the code is exactly the document an auditor disproves first.* The table is
ready to be **cited by** those statements once they exist. The fix for a missing answer is a place to
record one, not a way to invent one.

### 3.9 Three controls that do not exist, found from the other side

Three capability specs written in this consolidation each reported a control-shaped mechanism with no
control. All three are **confirmed** — the matrix and all seven evidence files contain no occurrence of
share, anonymous, approval, autonomy, kill switch, accessibility, or any spelling of the accessibility
tooling. The matrix's incidental hits on "agent" and "dry run" are its dependency check, a maintenance
task and the secret resolver.

| Mechanism, built and unmapped | Nearest existing control |
|---|---|
| **The anonymous public dashboard embed** — inert unless a secret of at least 16 characters is set, a signed token verified in constant time, expiring, one indistinguishable failure code, the query fenced to the shared board's own datasets, and issuing a link gated on a capability | the logical-access criteria, which describe authenticated access only. Nothing covers a token-scoped anonymous read — and 🔴 **there is no rate limit on token guessing and no address allowlist**, which the control plane documents as a deliberate exemption because the token carries its own credential |
| **The agent's governance** — and it is **more built than "a hole" implies**: an approval bridge that turns a synchronous tool gate into an operator inbox and is fail-closed on timeout or a thrown handler, an injectable dry-run preview, an act tier off unless set, a kill switch checked before mode and mode before budget, and real call sites in the autonomous driver | the system-operations criterion in spirit, and change management by rights. Neither mentions it, and a named project risk cites exactly these as its mitigation. ⚠ Note the compounding fact from the Assistant spec: **the module holding all of it is never packaged**, so the controls are real, unmapped *and* unreachable |
| **The accessibility gate** — asserted in roughly 204 of 334 specification files, but 🔴 **adoption is opt-in per file and nothing enforces it**: no step requires a new specification to add the assertion, and seven page-level rules plus contrast are disabled under the test renderer | the change-management row, whose implementation column names the colour-token lint, the unit tests and the build, and stops there |

⚠ **This is what "found from the other side" is worth.** Each hole was invisible from inside the
matrix, because a matrix can only be audited against controls someone thought to list. Reading the
product capability-first surfaced three in three areas.

## 4. Decisions (dated one-liners)

| Date | Decision | Where |
|---|---|---|
| 2026-07-25 | **No strict certification sequence: the work proceeds in parallel and SOC 2 is not a gate on the rest**, because the bill of materials, signing, audit and access evidence are shared across all frameworks | product |
| 2026-08-28 | **The matrix is one table, and every other deliverable is an export of it.** "Add a column before you add a document" | the matrix header |
| 2026-08-28 | The four honesty rules, including **every product row names a real artifact** and **do not overclaim** | the matrix's reading rules |
| 2026-08-28 | Responsibility takes **exactly four values**; physical, personnel and vendor controls appear **marked as someone else's** so an auditor sees they were considered | the scope boundary |
| 2026-08-28 | Bill-of-materials generation is scoped **per packaged bundle, not the reactor**, because the reactor includes optional modules an edition does not ship | gap ledger, scope correction |
| 2026-08-28 | The dependency lock is a **review baseline, not a bill of materials** — coordinates only, no licences or hashes | gap ledger |
| 2026-08-30 | **Emit both formats from the same resolved set in the same run**, never independently, or the two documents drift | operator, decision gate lifted |
| 2026-08-30 | **The release key lives in the pipeline's secret store**, held by no shift locally, making signing a mandatory pipeline step rather than an optional flag — with two consequences accepted: an out-of-pipeline release cannot be signed, and key custody becomes an access-control question | operator |
| 2026-08-30 | **The audit-retention window is one year** — implemented as a partition delete, never a row-level delete | operator |
| 2026-09-02 | Three controls ship together: the bill of materials, routine signing, and audit retention | editions rows |
| 2026-09-02 | **Certifications are Enterprise-only** and org-paced | operator |
| 2026-08-30 | **No hosted offering, ever — self-hosted only.** Federal work therefore never becomes an authorisation program, and the availability scope does not grow | operator |
| 2026-08-30 | **Healthcare and payment-card demand exists**, so the scope one-pagers grow controls — but as **scoping statements only**, never certification work | operator |
| 2026-08-30 | The audit-record statement is written **deliberately narrow**, with a standing rule: *do not assert immutability the storage layer does not enforce* | — |
| 2026-09-06 | **Opening the observation window was decided**; the healthcare and payment-card framework choice **stays deferred until a prospect is named** | operator |
| 2026-09-07 | The certification row gets a **landing place with a runnable measurement** — seven rows, each closing on its own dated line — because it was the only board gate that could not be run in either direction | gate sweep |
| 2026-09-07 | The dependency review's **scope is corrected to the enterprise profile**, after the security module's cryptography tree was found outside a resolve the row already called closed | gate sweep |
| 2026-09-07 | The access-review edition cell is corrected; it had been stale for weeks against its own closed gap | gate sweep |
| 2026-09-08 | The audit export is **kept in the core** when the events feed becomes optional, precisely so this control stays satisfiable on the edition with no events module | edition gating, cell 6 |
| 2026-09-08 | This spec: the never-executed signing path recorded; the availability row's staleness against its own gap ledger recorded; the three unmapped mechanisms recorded with their nearest controls; the "Personal has no compliance scope" stance distinguished from a build fact | this file §2, §3.8 |

## 5. Not built

### 5.1 Tracked — externally gated (a board row exists, and its first action is not ours)

| Item | Row |
|---|---|
| The certification program's six external items: applicability statements, auditor engagement, penetration test, policy content, the federal package, the federal cryptography leg | §2 *Compliance program*, now landing in the matrix's own seven rows |
| The observation window's **start date** — decided, unrecorded | §2 *SOC 2 Type II window*. ⚠ Its first action is **not** external: someone writes down a date |
| The first restore drill and the committed recovery targets | §2 *Deployment topology live validation*; gap G6 |
| The federal cryptography mode and its verification leg | gap G9; `CMP-06` |

### 5.2 Tracked — actionable now

| Item | Row |
|---|---|
| 🔴 **The security-incident carry-forwards.** The incident was closed by decommission on 2026-08-29, and two actions due on closure are **not done**: deleting an off-repository pre-rewrite backup bundle that contains five cleartext secrets whose retention condition has lapsed, and confirming that none of those five values was reused | §2 *SEC-INCIDENT-1 carry-forwards* |
| The advisory-watch process — a named owner and a cadence | the matrix's seventh certification row |

### 5.3 UNTRACKED — found 2026-09-08, no board row yet

1. 🔴 **Three built mechanisms have no control** (§3.9): the anonymous public embed, the agent's
   approval gate and kill switch, and the accessibility gate. Each needs one row with an evidence
   pointer, and the third needs a decision about whether accessibility is a compliance control here at
   all — two non-functional requirements already cite it as evidence of one.
2. 🔴 **The signing control has never executed** (§2). A control whose path has never run is a control
   with no evidence in either direction. Cutting one throwaway tag would convert an assertion into an
   artifact, and the editions row already flags it while the gap ledger does not.
3. **The availability criterion is stale against its own gap ledger** (§2). A one-line fix, listed
   because rule 4 is the file's own standard.
4. **"Personal has no compliance scope" is a stance stated as a build fact.** The matrix and the
   evidence pack are files in the repository; nothing withholds them. Either the row says "not promised
   for Personal", or the distinction stays invisible to a reader comparing it with the module-gated
   rows above it.
5. **The evidence pack has no index and no dating convention.** Seven files, each cited from the matrix,
   none listing when it was last verified against the code. The matrix dates its rows; the pack does
   not, so a reader cannot tell which runbook is oldest.
6. **This area has no concept tier**, and unlike every other area that is *appropriate* — the matrix is
   the concept. But it means `docs/INDEX.md` and the capability index are the only routes in, and the
   matrix's own plan pointer is the only route out.
7. 🔴 **A row can rot when its dependency lands.** The logging row's "arrives with the security module"
   became false the day that module shipped, and nothing re-read it. Rules 1 and 2 catch *absent* and
   *overclaimed* artifacts; neither catches a **promise whose precondition is now met**. A fifth rule —
   when a dependency lands, re-read every row that was waiting on it — would close the class.
8. **The order of the four write gates has no test.** Thirteen modules depend on it holding; it is
   verified by reading call sites. One test over one route would make it a control rather than a habit.
9. 🔴 **The evidence pack needs a maintenance trigger, not a review.** One file's central claim is
   already false (§3.4) and it had written the exact condition that would break it. The fix is a rule:
   **when a gap closes, re-read every evidence file that described the world before it.**
10. 🔴 **The Standard edition is unrepresented in the release pipeline** (§2). Either the workflow
    builds and signs it, or both supply-chain rows lose their Standard column.
11. **Line-number citations should be symbol citations.** Two documents point into the packaging script
    at lines that moved by roughly 400. Rule 1 permits this; rule 4 cannot survive it.
12. **The live plan still calls three closed gaps open**, one of them with the words *confirmed still a
    gap*, and still cites a route that moved and a runbook rule that was reversed. The plan is in flight
    and correctly not archived, but its status text is a month behind its own deliverables.
13. **The plan claims a quality gate that does not exist**: an accessibility step in the pipeline, cited
    as change-management evidence. No workflow has one — the matrix's own row is accurate and the plan's
    is not. The plan also calls the audit log *immutable*, the exact word its evidence file forbids.
14. **The plan declares four subdirectories and three do not exist** — scope, policies and federal.
    Either create them when their content arrives or stop declaring them.
15. **The roadmap still promises a tamper-evident audit log.** Four sibling documents were corrected on
    2026-09-08 and this one was missed, so the strongest overclaim now lives in the most externally
    facing file.
16. **The stakeholder overview sells the certifications as a Standard-edition scope with no qualifier**,
    is stamped current as of a date three months before the program began, and makes four unbounded
    small-dependency claims of precisely the kind the matrix forbids without naming the boundary.
17. **The index still says the event store has no retention**, which stopped being true on 2026-09-02.
18. **The glossary defines Compliance but not Control and not Evidence** — the two nouns this area's
    every sentence turns on.
19. **The board's own gate table disagrees with itself** on how many rows it has, sixteen against a
    fifteen-row table, and a guard exists to enforce exactly that arithmetic.
20. **The boundary between this area and `SEC` is asserted, not mapped.** Most product rows cite
   security machinery, and the security spec owns those mechanisms. A short table saying which control
   maps to which capability spec would stop the next reader duplicating either.

## 6. Refused & superseded

| Item | Verdict | Why / where |
|---|---|---|
| Generating the applicability statements from the repository | **⛔ Refused** | a scope statement invented from the code is the first document an auditor disproves |
| Restating a deliberate posture as a gap | **⛔ Refused** | it is how a matrix grows work nobody chose |
| Adding a document instead of a column | **Refused** | one table, exports only |
| Treating the dependency lock as a bill of materials | **Refused** | coordinates only, no licences or hashes |
| Treating the dependency review as a vulnerability scanner | **⛔ Refused** | it proves nobody looked, never that a version is safe |
| Generating the bill of materials from the reactor | **Superseded 2026-08-28** | the reactor includes optional modules an edition does not ship |
| Emitting the two formats independently | **Refused 2026-08-30** | they would drift; one resolution, one run |
| Keeping the release key with a shift locally | **Superseded 2026-08-30** | it moves to the pipeline's secret store, accepting that an out-of-pipeline release cannot be signed |
| A row-level delete for audit retention | **Refused** | a partition delete, so retention is a file operation |
| Overriding the decision that a purged incident's history survives | **⛔ Not overridden** | the one-year window is an upper bound on the store, not a licence to erase the trail |
| Claiming federal authorisation | **Refused** | self-hosted software has no service boundary to authorise; the posture is "supports your authorisation" |
| Claiming the moderate baseline as a commitment | **Refused** | it is flagged as an assumption pending an org answer |
| Confidentiality and privacy criteria | **Deferred to demand** | scope decision |
| A third-party assessment, continuous-monitoring or remediation-plan program | **Stated posture, not a gap** | no hosted offering |
| Starting the federal package unscoped | **⛔ Refused** | demand-gated |
| Claiming tamper-evidence for the audit trail | **⛔ Refused, in writing** | append-only by construction of the write path is the whole claim; the evidence pack says so itself |

## 7. As-built pointers

| Concern | Artifact | Owned by |
|---|---|---|
| The matrix | `compliance/controls-matrix.md` | this file |
| The evidence pack | `compliance/evidence/access-review.md`, `air-gap-posture.md`, `audit-log-extraction.md`, `audit-record-protection.md`, `release-verification.md`, `retention-configuration.md`, `rto-rpo-statement.md` | this file |
| The live plan | `docs/superpower/compliance-certifications-plan.md` (workstreams C1–C6) | in flight |
| Bill of materials · signing · dependency review | `tools/sbom.mjs`, `tools/check-dependencies.mjs`, `tools/dependencies.lock`, `.github/workflows/release.yml`, `.github/workflows/ci.yml`, `inspecto/package.ps1` | `PKG`, `TOOL` |
| Audit trail · retention | `inspecto/src/main/java/com/gamma/control/AuditTrail.java`; `inspecto-event/src/main/java/com/gamma/event/EventStore.java` | `OPS` |
| Access control · secrets | `inspecto/src/main/java/com/gamma/control/CapabilityManifest.java`, `Roles.java`; `inspecto-acquire/src/main/java/com/gamma/acquire/SecretResolver.java` | `SEC` |
| Configuration safety | `inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java`, `PathJail.java` | `PIP`, `SEC` |
| Processing integrity | `inspecto/src/main/java/com/gamma/expectation/ExpectationEvaluator.java`; `inspecto-etl/src/main/java/com/gamma/etl/QuarantineManager.java`; `inspecto-engine/src/main/java/com/gamma/consignment/GuardedSummaryEmitter.java` | `ING`, `PIP` |
| Change-management gates | `.github/workflows/branch-policy.yml`, `ui.yml`; `tools/check-vocabulary.mjs` | `TOOL`, `UI` |

**Gap rows.** This area has **no `okf/` concept tier, and that is correct** — the matrix *is* the
concept, and a distilled copy would be a second source of truth for a file whose whole value is being
the only one. What is missing is smaller: an **index for the evidence pack**, a **last-verified date per
evidence file**, and a **control-to-capability map** so the boundary with the other areas is written
rather than assumed (§5.3).

## 8. Verification

* **Pointer check** — a capability-pointer check over this file (⚠ **the checker is NOT in this repo** — it was a session scratch script (recorded 2026-09-09 as `TOOL` §5.2 item 1). Until it is committed to `tools/`, re-derive the check by grepping this file's backticked paths and class names against `git ls-files`.).
* **The area's own measurement** — `grep -c '^| NFR-7 ·.*⬜ open' compliance/controls-matrix.md` reads
  **7** and closes at **0**. ⚠ Keep the anchor and the row prefix: unanchored it reports 9, and renaming
  the prefix disarms it silently.
* **Rule 1, run as a test.** Every class the matrix cites was checked against the tree on 2026-09-08
  and all exist. That check is worth repeating whenever a module is renamed — it is the cheapest way to
  falsify the file, and the only one that scales.
* **Coverage floors are enforced and deliberately set below the measured baseline** — backend 78% of
  instructions and 64% of branches against 81.01 and 67.19 measured; the client 70% of statements and
  66% of branches against 73.82 and 70.04. A floor below the baseline is a ratchet, not a target.
* **Falsify, don't read** — three probes worth running. Cut a throwaway version tag and confirm the
  signing workflow refuses to publish without both a checksum and a signature. Run the dependency check
  after bumping one pinned version and confirm it fails. And grep the matrix for `share`, `approval` and
  `accessib` — all three still return nothing, which is finding 1 of §5.3.
* **Guards** — `node tools/check-vocabulary.mjs`, `node tools/check-doc-links.mjs`,
  `node tools/check-gate-tally.mjs`, from the repo root. ⚠ Stage a new document (`git add -N`) before
  trusting a green vocabulary run.
