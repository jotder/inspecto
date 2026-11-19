---
type: Capability
title: Editions & packaging (PKG) — capability spec — Refused, as-built pointers and verification
description: Part 4 of 4 of Editions & packaging (PKG) — capability spec: Refused & superseded; As-built pointers; Verification
status: current
---

# Editions & packaging (PKG) — capability spec — Refused, as-built pointers and verification

Part 4 of 4 of [Editions & packaging (PKG) — capability spec](plan-editions.md) · previous: [Specification — the plan's tables; decisions and not built](plan-editions-tables-decisions.md).

## 6. Refused & superseded

| Item | Verdict | Why |
|---|---|---|
| **Launch-flag capability switches** as the gating mechanism | ⛔ **REFUSED** 2026-09-07 | A switch leaves the code in the Basic bundle. Basic has no authenticator and binds every interface, so a mis-set flag re-exposes an unauthenticated surface. Packaging boundary, not policy boundary |
| **Edition conditionals in the core** | ⛔ **BANNED**, standing | An edition difference is a build decision — a module or a flag — never core logic. This is what forced the events surface to move whole (§4) |
| **Edition git branches** | ⛔ **REFUSED**, standing | Versions are branches; editions are flavours of one commit. There is no Basic or Standard branch and never will be |
| **A Basic Maven profile** | **NEVER EXISTED** | The parent declares three edition profiles (`edition-standard`, `edition-advanced`, `edition-preview`) plus `coverage`, and no Basic one. Maven warns on an unknown one and builds the default, so the wrong instruction produced the right result |
| **"Basic binds localhost only"** | **SUPERSEDED** 2026-08-29 | The code never enforced it. The claim was the defect, not the behaviour |
| **A framework migration** | ⛔ **REFUSED** | At five to fifteen users a framework buys nothing the identity provider and a few small libraries do not, and a lean dependency tree is a compliance asset |
| **In-app login, user management, directory or assertion integration** | ⛔ **REFUSED** | Identity is delegated to the customer's provider; the product only validates the resulting token (§3.9) |
| **Bundling the assistant modules** (`PKG-5`) | ✅ **REVERSED and SHIPPED 2026-09-12** | The refusal rested on "bundling would raise the bundle's Java floor". It does not: the floor only binds a host that actually loads the module, and `OptionalSpi` now makes an unloadable optional module an ABSENCE rather than a boot failure. Staged in Standard and Advanced; the stated requirement stays Java 24 (§2, §3.6) |
| **Gating the connector module** | ⛔ **REFUSED** 2026-09-07 | Remote acquisition is a core capability, and the board marks it shipped in all three editions; gating it would mean correcting the acquisition rows |
| **A per-edition capability-vocabulary validator** | ⛔ **REFUSED** | It would break role-file portability — a file authored on Standard would fail validation on Basic — and dead vocabulary has no route behind it |
| **Active/active deployment** | ⚠ **SUPERSEDED 2026-09-10** for Advanced | Active/active becomes **partitioned scale-out** (shared-nothing by Space — a pod owns Spaces; no replicated active/active), per the signed `superpower/enterprise-scale-out-plan.md` §4. Basic/Standard: still not offered; Standard gets the T4 active/**passive** standby instead (§3.9) |
| **Orchestration platform support** | ⚠ **That decision changed 2026-09-10** | Kubernetes is the **Advanced** orchestrator (T5, §3.9); the container image is the Advanced unit of deployment. Basic/Standard remain orchestrator-free single artifacts. → `superpower/enterprise-scale-out-plan.md` §4, §9 D1′ |
| **A government cryptographic variant** | 🔭 **DEFERRED**, demand-gated | The pattern is documented against the same transport seam; the work starts only against a concrete opportunity |
| **Multi-user relational deployment** | ⛔ **PARKED** 2026-09-06 → park **LIFTED** 2026-09-15 → ⏸ **POSTPONED** 2026-10-23 | The trigger (a multi-operator install) fired 2026-09-15; pool, schema-per-Space and the two-node soak are built; the rest waits until after the release that ships without Postgres (board row *Postgres multi-user*) |
| **The profiles varying the shaded fat JAR** | **NEVER TRUE** — corrected in this commit | One unconditional shade configuration, no child profiles; optional modules ship as sidecars (§3.2) |
| **"Framework-free core, network dependencies isolated"** as a shipped property | **PARTLY SUPERSEDED** 2026-09-07 | The connector sidecar now ships in every edition. The isolation is real in the reactor and absent in the bundle (§2, `PKG-2`) |

### 6.1 The modularization COULDs (landed here 2026-09-10 — Sprint 7.2)

Three items from the 2026-07-21 modularization pass had **no current-tier home at all** — they survived only
in `archived-documents/plans-archive/modularization-optimization-plan.md` and a `BACKLOG.md` §6 row that
pointed at it. Anchor them on their *text*, never on the letter: that plan uses `C2` for two different items
and `okf/product/layer/platform/architecture-modules.md` uses `C2`/`C4` for unrelated things.

| Item | Verdict | Why / measured |
|---|---|---|
| **A generic base for the `InMemory*` / `Db*` store pairs** *(the plan's COULD `C2`)* | 🟡 **HALF SHIPPED — reopen only for the `InMemory*` half** | 🔴 The Db side **was built** as `AbstractJdbcStore` on 2026-08-18 (`JAVA-5`), **20 days before** the recount that still called this untouched; five stores extend it (`DbObjectStore`, `DbLinkStore`, `DbNoteStore`, `DbTagAssignmentStore`, `DbDeliveryReceiptStore`), and it was relocated to `inspecto-util` on 2026-09-08 so an optional edition module cannot hold a core store hostage. What is left: **all ten `InMemory*` stores implement their interface directly with no shared base**. The pair count is **5**, not the 4 the board row recorded (`DeliveryReceiptStore` became a true pair on 2026-09-07 — the recount's own date), or 6 counting `DbStatusStore` by shape |
| **A BOM for external consumers** *(the plan's `C4`)* | 🔭 **DEFERRED, demand-gated — still true** | Reopen on an external consumer; there is none, and **nothing here is publishable**: no `distributionManagement` and no `maven-deploy-plugin` anywhere in the tree, releases ship zip bundles, and the upstream agent library is a dependency this repo *consumes*, not a consumer. ⛔ Not the **SBOM** (§3.3) — a software bill of materials is a different artifact that happens to share three letters |
| **DuckDB connection reuse for the per-run opens** *(the plan's `C6`)* | 🔭 **DEFERRED on measurement — still true** | A warm open is **24 ms** (min 23 / max 27, n=20), so the open cost is not worth pooling; `PipelineJobRunner` and `EnrichmentEngine` still open per run and delete their temp database after. ⛔ **Never pool the `SqlSandbox` HTTP path** — ephemeral-per-request *is* its security boundary (`close()` drops the connection, the temp database and its WAL) |
| **Rewriting the store pairs onto an ORM or repository framework** *(the plan's non-goal `W3`)* | ⛔ **BANNED**, standing | Working, consistent and tested; churn without payoff — and it contradicts the framework refusal above |

## 7. As-built pointers

Paths and identifiers, with the gap named where one exists. ⚠ **Cite the identifier, not the line number** —
this area has three sites whose line citations drifted (§3.2).

| Concern | Where it lives | Gap |
|---|---|---|
| The edition switch and staging | `inspecto/package.ps1` — the edition guard, the module list, and the per-jar shape assertions | ⚠ Its header names three of twelve staged jars |
| The profiles | `pom.xml` — three edition profile identifiers (module lists only) plus `coverage` | — |
| The bill of materials | `tools/sbom.mjs`, over the set in `tools/bundle-modules.mjs` | ⚠ Needs the reactor installed to resolve (§5.3 item 1) |
| Its drift guard | `tools/check-sbom-modules.mjs`, wired into `ci.yml` | — |
| The dependency lock | `tools/dependencies.lock`, written by `tools/check-dependencies.mjs` | ⚠ **89**<!--count:locked-dependencies--> locked coordinates (this cell read 95 until 2026-09-15 — the lock had moved and nothing derived the prose); originally 95 across 25 modules against four prose sites saying 94 |
| The release pipeline | `.github/workflows/release.yml` | 🔴 No Standard step; every bundle skips the runtime |
| The test pipeline | `.github/workflows/ci.yml` | ⚠ One profile pass; no Basic-with-tests, no Standard |
| The reported edition and feature flags | `BootstrapRoutes` | 🔴 Two-valued edition string; four modules with no flag (§3.5) |
| Absence at the route | the five `Absent*Routes` classes in the control package | ⚠ No drift guard over their path lists |
| The singular seam helper | `SpiSlot` in the control package | — |
| Route contribution | the public `RouteModule` interface | — |
| Maintenance task contribution | `MaintenanceTaskProvider` in the engine's job package | — |
| Object engine contribution | `ObjectEngineProvider` in the service package, deliberately not beside its seam (§4) | — |
| The memory-limit resolver | `DuckDbUtil.memoryLimit` — falls through to nothing when nothing is installed | 🔴 Contradicts a signed decision (§5.3 item 18) |
| The falsification tests | four `No*ShipsInThePersonalBuild*Test` classes, plus module-side halves for events and metrics | 🔴 Gating cell 7 has none |
| The examples suite | `inspecto/examples/` — 49 examples in seven groups (144 `.toon` files), tracked and staged | ⚠ One runs in any pipeline |
| The assembly concept | [`editions-model.md`](architecture-editions.md) | ⚠ Says "five" then lists seven; the shade claim is corrected in this commit |
| The authentication story | [`auth-security.md`](../../module/access/admin-guide-auth-security.md) | — |
| Bundling and reactor sizes | [`build-test.md`](spec-build-test.md) | ⚠ Its jar *table* omits what its own prose gets right |
| The feature board | `docs/EDITIONS.md` | See §2 |
| The deployment design | this spec §3.9–§3.13 (narrative) and **§3.14 (the six tables verbatim)** | current |
| The build plan's provenance | `docs/archived-documents/plans-archive/deployment-topology-plan.md` | ⛔ **ARCHIVED 2026-09-09**; Phases 0–5 remain unbuilt and are tracked on §3 P2 + `SPEC-DEPLOY-ROWS-1` |
| The backup runbook | `docs/okf/product/module/backup/admin-guide-backup-restore.md` | — |

## 8. Verification

**What is actually enforced today**

* **The Basic-side refusal of cell 7's extraction** — `NoOperationalObjectsShipInThePersonalBuildTest`
  (added 2026-09-09, closing part of `SPEC-NOPROOF-1`). All **49** stubbed operational-object paths answer
  **503 naming `inspecto-ops`** rather than 404, `features.ops` stays present-and-false, and a source
  cross-check pins the stub surface against the test's own list. ⚠ That cross-check is not redundant: a
  path shadowed by one of the two catch-alls is **invisible** to an over-the-wire check, proven by
  mutation. Cell 7 was the only one of the seven gating cells without such a test.

* **Per-jar shape assertions** on every staged artifact, reading inside the zip for classes and provider
  registrations — the strongest control in this area, and the one that caught the unshaded authentication
  jar.
* **A boot smoke** that runs last, re-derives the classpath from what was staged, launches the control
  plane on a free port, polls health, and **throws** on failure. It is the only check that proves a bundle
  can start.
* **Fail-closed signing** — a missing binary, a missing key, or a non-zero exit all throw.
* **A bill of materials that must generate** or the bundle does not ship — and, since 2026-09-09, one whose
  module set is **held against the packaging script in CI** by `tools/check-sbom-modules.mjs`, over the
  script's three independent enumerations (§3.7).
* **Six falsification tests** proving the gated features are absent from the default build, plus two
  module-side halves proving they are present with the module.
* **A dependency lock diffed on every run**, distinguishing drift from could-not-run.

**Falsify, don't read — five probes worth running**

1. **Unzip a Standard bundle's bill of materials and count its first-party entries.** Four means the defect
   is live; **ten** means it was fixed (eleven for Advanced). Counting *staged jars* instead gives 11 and
   12 — the driver sidecar is third-party, and conflating the two is what made the original counts confusing.
2. **Grep a released Advanced bundle's launcher for the policy jar**, then start it and read the reported
   edition. It will say `standard` while enabling Advanced policy.
3. **Ask a Basic build for a gated path** — an object route, say. A 503 naming the module is correct; a
   404 means the stub list drifted.
4. **Look for `runtime/` in any published artifact.** Its absence falsifies the runtime row in all three
   editions.
5. **Build with the Standard profile alone.** It is the only profile that proves an optional module is
   self-contained, and no pipeline runs it.

**A capability-pointer check over this file** — it is **`tools/check-doc-citations.mjs`, committed 2026-09-09**
and wired into `ci.yml` and `.githooks/pre-push`. It checks this file's backticked paths and
class names against `git ls-files` and the module tree.


#### Service wrappers — acceptance still owed

`package.ps1` stages `inspecto.service` + `install-service.sh` (systemd) and `install-service.ps1` (a Windows Scheduled Task at boot as SYSTEM, with restart-on-failure). ⛔ The `sc.exe` route was REFUSED on grounding: a Windows service binary must reach the service control dispatcher shortly after start and `java.exe` never does, so an `sc.exe` service fails every start with error 1053 — that installer would have looked installed and restarted nothing. WinSW is the documented alternative. ⚠ **The unit renders and both installers parse, but the acceptance test — `kill -9` → back on `/health`, plus the reboot leg — is UNRUN**, because it needs a systemd host and an elevated Windows box. Tracked as `DEPLOY-SERVICE-WRAPPER-1` in `BACKLOG.md`; it is evidence-blocked, not code-blocked, and ⛔ SCR-3's acceptance must not be marked met until someone runs both legs.

### `QUEUES-USER-FACING-COPY-1` (CLOSED 2026-09-24 — the copy was already fixed 2026-09-18)

The work-queue family was retired on 2026-09-14 (created `a5b89a89`, moved to `inspecto-ops` `e8d98918`,
deleted `519673a7`), and EDG-01 cell 7 is now **three** route families, not four. Three shipped strings still
promise it, and none is test-asserted: `AbsentModuleRoutes.java` — the Basic-edition **503 body** — plus
`inspecto-ui/.../admin/objects/object-mail.component.html` and `inspecto-ui/.../admin/tags/tags.component.html`, each
reading "notes, links, tags **and queues** are provided by the…".

⚠ A 503 telling an operator that a deleted feature is available in Standard is a small but real defect. It was
kept out of the comment-correcting change deliberately: shipped copy plus two Angular templates is a different
change class and needs the `angular-ui` skill.

**As-built (closed 2026-09-24).** All three strings had **already** been corrected the day after filing, inside
`ec49b7bda` (the Standard→Professional rename, 2026-09-18 — itself superseded by LA-EDITIONS-1's Basic / Standard / Advanced, 2026-10-17), which rewrote each sentence and dropped "and queues" in
passing — so the row sat open over fixed copy for six days. They now read "…notes, links and tags are provided by
the…". A repo-wide grep for "and queues" finds no other shipped copy. The closing change fixed the one leftover:
the `SURFACE` array's Javadoc in `AbsentModuleRoutes.java` still said "four moved route families"; it lists three
(Object/Note/Tag). ⚠ Lesson: a copy row names a STRING, so grep the string before editing — the cited line numbers
still matched, only the words had moved.

⚠ Provenance trap: `git log --grep="RETIRE-HALVES"` does **not** find the commit that removed the routes — it
landed under a whitepaper message when a concurrent session committed the staged tree. Search the symbol, or
`--diff-filter=D`.

## Changes

- 2026-09-07: the Basic Maven profile row corrected — that profile never existed.
