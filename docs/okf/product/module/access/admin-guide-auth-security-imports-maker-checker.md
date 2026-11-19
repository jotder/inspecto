---
type: Concept
title: Auth & Security — Imports, identity files and maker-checker
description: Part 3 of 3 of Auth & Security: No import may write an identity, access or settings file (SEC-IMPORT-ROLES-ESCALATION-1, 2026-09-26); Maker-checker: a held config change needs a different person (ASSURE-MAKER-CHECKER-1, 2026-09-26)
tags: [auth, security, spi, oidc, keycloak, wso2, bff, write-gate, rbac, abac, policy-engine, edition-standard, edition-advanced]
timestamp: 2026-07-24T00:00:00Z
item: module.access
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-access
---

# Auth & Security — Imports, identity files and maker-checker

Part 3 of 3 of [Auth & Security](admin-guide-auth-security.md) · previous: [Fail-closed boot, Space and config guards](admin-guide-auth-security-boot-guards.md).

## No import may write an identity, access or settings file (`SEC-IMPORT-ROLES-ESCALATION-1`, 2026-09-26)

**The escalation it closed.** `POST /import` (gated `canAuthorWorkbench` only) unpacked every zip entry under
the Space config root (`DataSourceRoutes` → `BundleImporter.writeConfig`). A builder importing a `roles.toon`
got 200, and `Roles.effective` then gave their `developer` role `canAdminister`. The sibling doors were
`POST /pipelines/import?conflict=overwrite` (satellites land in the REGISTERED pipeline's directory, which
may be the config root itself) and `POST /bundle/import` (`access-profile` / `access-catalog` items, and
`pipeline` items whose closure carries satellites). Two more fixed-name loaders were reachable the same way:
`demo-users.toon` (`inspecto-demo-auth` `DemoUsers.FILE`, read from every Space's config root, so a
`roles: super` Demo User) and `agent/` (`AgentWriteRoot` → `agent/policy.json`, `agent/approvals.jsonl`).

**One judge, four layers, every door** — `com.gamma.service.ImportPaths`, called by `/import` (all entries
up front), `/pipelines/import` (every satellite, every companion, the pipeline file), `/bundle/import`
(`PipelineBundleRoutes.judgeClosure` over every `pipeline` item BEFORE any item is written) and the
`BundleImporter.writeConfig` backstop:

1. **Segment rules**, on every platform: each segment matches `[A-Za-z0-9][A-Za-z0-9._-]*`, never ends in
   `.`, never contains `..`, is never a Windows device name (`CON`, `NUL`, `AUX`, `PRN`, `COM1-9`,
   `LPT1-9`, with or without an extension). So no `~` (8.3 short names like `ROLES~1.TOO`, `PENDIN~1`),
   `:`, space, control character or leading dot. The manifest's own spelling is judged before any `Path`
   is built from it (`roles.toon ` does not even parse as a Windows path).
2. **A shape allowlist**: a `.toon` / `.csv` config; at the config ROOT only `_pipeline.toon`,
   `_enrich.toon`, `_schema.toon`, `_job.toon`, `_connection.toon`, `_mapping.csv`, `_structure.csv`,
   `.grammar.toon`, `_grammar.toon`, `_profile.toon`; under `registry/` exactly `registry/<kind dir>/<name>`
   for an importable kind (never the access config); in any OTHER subdirectory the same data-source
   suffixes plus `_view.toon` (`ImportPaths.SUBDIR_SUFFIXES`), and never a reserved root-level name one
   directory down (a `roles.toon` in any subfolder; `registry/` excepted, where a name is a component id). A file a carried
   config names through a **real reference key** may also carry any plain name at the root or in a
   subdirectory and may be `.asn` / `.asn1` / `.sql` — that is all a reference buys. ⛔ **No
   suffix-scanned ops or semantic config, anywhere, referenced or not** (`ImportPaths.REFUSED_SUFFIXES`,
   `SEC-IMPORT-OPS-CONFIGS-1`, 2026-09-26): `*_workflow.toon`, `*_caserule.toon`, `*_tagrule.toon`,
   `*_tag.toon` (`OpsEngineProvider.loadConfigs`), `*_meta.toon`, `*_rca.toon`, `*_job_template.toon`
   (`ServiceBootstrap.buildFrom`), and the loader-less `*_escalation.toon` / `*_queue.toon`. The boot scans
   (`ServiceBootstrap.resolveBySuffix`, a recursive `Files.walk`) read them from ANY depth (only the component store's
   `registry/` is skipped, since 2026-11-13, `JOB-LOADER-SAVED-VIEW-SUFFIX-1`), so a directory is
   no containment: an `incident_workflow.toon` in a zip subfolder returned 200 and the last workflow per object type wins,
   which replaced the Incident lifecycle the Disposition gate is keyed on. None travels in a data-source or
   Pipeline bundle; each has its own validated route (`POST /cases/rules`, `/tags*`). The real keys (`ImportPaths.referenceValues`) are the loader's own:
   `processing.schema_file`, `schemas[].schema_file`, `parsing.grammar` / `processing.grammar`,
   `processing.mapping_file`, the `segments` values, `ingester_config.grammar`,
   `parsing.asn1|plugin.grammar_file` / `grammar` / `profile_file` and, inside a Decode Profile,
   `asn1.grammar_file` / `asn1.segments`; for an Enrichment, `transform_file`. ⚠ Before this, ANY string
   of a carried pipeline counted, so `notes.r0: "demo-users.toon"` wrote the Demo User table; and every
   `/pipelines/import` satellite counted as referenced.
3. **The denylist** `ReservedConfigPaths`: `roles.toon`, `demo-users.toon`, `access-policies.toon`,
   `approval.toon`, `offers.toon`, `grants.toon`, every settings document (`branding`, `geo`,
   `link-analysis`, `pipeline-history`, `icon-map`, `scheduler`, `nav-menus`, `notification-preferences`,
   `partition`, `space`), `rename.journal`, `dataset-publications.tsv`, `day-manifest.tsv` (LA-DAILY-INGEST-1 T8 delivery manifest, operator 2026-10-06); the directories `pending-changes/`,
   `recon-state/`, `.history/` (anywhere), `audit/`, `agent/`, `expectation-baselines/`,
   `registry/access-catalog/`, `registry/access-profiles/`. `approval.toon` and `pending-changes/` hold
   maker-checker's policy and held changes (shipped 2026-09-26, below). The list above is not complete:
   `ReservedConfigPaths.FILES` / `DIRS` (34 files and 12 directories on 2026-11-17) are the authority, and
   `ImportLoaderInventoryTest` fails when a loader reads a fixed name the list neither covers nor allow-lists.
4. **A real-path check**: the nearest existing parent is resolved with `toRealPath` and compared against
   every reserved file and directory (`isSameFile` / real-path prefix), failing closed on any I/O error.

⚠ For a DIRECTORY alias on a Space where the directory does not exist yet (`audit./x.toon`,
`pending-changes./x.toon`), the trailing-dot rule is the only layer that fires: the string is not
`audit/`, and the real path has nothing to compare against. `ImportPathsTest` pins that the refusal comes
from that rule.

**All-or-nothing.** Every write goes through `ImportJournal`, which records each target's prior bytes (or
its absence) and the directories it creates. `/import` runs every refusal it can before the first write
(the judge, the id conflict, and a pipeline id registered from a different file); a later refusal (the
integrity gate's 422, a registration's 422/409) unregisters what the import registered, restores the
connections it replaced, rolls the tree back byte for byte, and re-reads the configs. `/pipelines/import`
parses, judges and conflict-checks its companions before writing anything, and a SaveGate ERROR now restores
an overwritten satellite (the old cleanup deleted every satellite, pre-existing ones included).
**A rollback never clobbers a newer write** (`IMPORT-RESIDUALS-1` (3), 2026-09-28). There is no config write
lock, so another request can save a file between an import's write and its rollback. `ImportJournal` also keeps
a SHA-256 of what the import last wrote to each file and restores (or deletes) a file only while it still holds
exactly those bytes; a file changed or deleted since is left as the other writer made it, logged, and named in
the refusal — a `notRolledBack` list on `/import`'s 422 body and on a `/pipelines/import` SaveGate refusal,
`; changed concurrently, not rolled back: [...]` appended to any other refusal's message (a `/bundle/import`
pipeline item's `failed` row, and the 500 of a write failure — `ImportRollback`). The list lives on
`ImportJournal.notRolledBack()`, so it survives a rollback that throws and one run inside
`BundleImporter.writeConfig`. **A rollback that itself fails never replaces the refusal**: the 4xx (or the original
failure) stays the answer, with `rollbackIncomplete` / `; rollback incomplete: …` and the failure attached as
suppressed and logged. `SpaceManager.createFromBundle` does not report the list — it seeds a brand-new Space no
other request can write yet. Chosen over serialising imports behind a lock because none exists to reuse (`ComponentStore`
and `/config/write` write through `AtomicFiles` unlocked) and a new one would have to be taken by every config
writer to help. ⚠ The compare and the restore are two steps, not an atomic swap: a write landing in that
sub-millisecond gap is still overwritten. Tests: `ImportJournalTest` (interleaved writes),
`ControlApiImportConcurrentRollbackTest` (real HTTP; the `ImportJournal.beforeRollback` test seam saves the file
between the write and the rollback).

**A whole-Space export is not importable into an existing Space** (operator 2026-09-29, `IMPORT-RESIDUALS-1`
(1) decided). The zip `GET /spaces/{id}/export` produces (manifest `kind: space`) carries the suffix-scanned ops
configs no import may write, so `POST /spaces/{id}/import` refuses it up front with **403** and one message: *a
whole-Space export cannot be imported into an existing Space; import data sources one at a time, or create a new
Space from it* (`DataSourceRoutes.WHOLE_SPACE_IMPORT_REFUSED`, pinned by `ControlApiImportReservedPathsTest`).
The two supported flows are per-data-source export → import, and seeding a NEW Space from the zip through Space
creation (`SpaceManager.createFromBundle`, a separate path this check does not touch). Splitting a whole-Space
zip into its kinds' own validated routes is filed, not built (P3 `IMPORT-WHOLE-SPACE-SPLIT-1`).

**The loader guard.** `ImportLoaderInventoryTest` scans every reactor module's `src/main/java` for fixed
names: literal `.resolve("a").resolve("b")` chains, `AgentWriteRoot.resolve("x")` (as `agent/x`), and any
string literal naming a `.toon` / `.json` / `.jsonl` / `.tsv` / `.journal` / `.key` file. Each must be
reserved, be a name the segment rules refuse (a suffix literal), or sit on its `ALLOWED` table with a written
reason. Un-reserving `demo-users.toon` or `agent/` turns it red. Its SUFFIX half scans for every suffix
literal (`"_x.toon"`, `".grammar.toon"`, `"_x.csv"`): each must be refused by `ImportPaths` at the root, in a
subdirectory and under `registry/`, referenced or not, or sit on `SUFFIX_ALLOWED` (the data-source shapes, and
the data-dir output sidecars) with a reason. Un-refusing `_workflow.toon` turns it red.

**An import is never a way around a kind's own route gate** (`IMPORT-CONNECTION-JOB-GATE-1`, session decision
2026-09-27, overturnable). Every import door is `canAuthorWorkbench` (a new Space: `canAdminister`), but some
kinds it can carry are written on their own route under a different capability. `ImportCapabilityGuard`
requires THAT capability per carried item, at all four doors — `/import` and `/spaces/import` over the raw
entries, `/pipelines/import` over its zip, `/bundle/import` over its items and each `pipeline` item's closure
files — before the first write: the first missing capability is 403 naming the kind and the capability, and
nothing is written.

| Carried kind | Recognised by | Needs (its own route) |
|---|---|---|
| Connection | a `*_connection.toon` file, a `connection` item | `canOnboardConnections` (`/connections`) |
| Alert Rule | `registry/alert-rules/`, an `alert-rule` item | `canAuthorAlertRules` (`/alerts/rules`) |
| Findings Spec | `registry/findings-specs/`, a `findings-spec` item | `canManageIncidents` (`/components/findings-spec`) |
| `event_prune` Job | a `*_job.toon` file or `job` item parsed by `JobConfig` as `maintenance`/`event_prune` | `canAdminister` (`/jobs`) |

Every other Job, and every other importable kind, is `canAuthorWorkbench` on its own route too — no mismatch
(swept 2026-09-27 over `CapabilityManifest` and the in-handler `requireCapability` calls). The access config,
role table and settings documents are not on the table because no import writes them at all (below). ⚠ A
`/bundle/import` `event_prune` Job is still refused per item even for an administrator (`JobBundleSource`,
`ASSURE-AUDIT-CHAIN-1`); the guard only makes the non-administrator case atomic. Tests:
`ControlApiImportCapabilityGateTest` (real HTTP, armed Subject; mutating the check to a no-op turns all 7 red,
403 → 200).

**Dedicated-only kinds** (`IMPORT-DEDICATED-ONLY-KINDS-1`, operator decision 2026-09-27). `access-profile`,
`access-catalog` and `requirement` are written through their dedicated routes ONLY (`/access/*`, which validates
under `canConfigureAccess`; `/requirements`, whose triage lifecycle only `canTriageRequirements` advances).
`ImportCapabilityGuard.DEDICATED_ONLY` refuses them, 403 for ANY caller, on the generic
`/components/{kind}` POST/PUT/DELETE/version-restore (before: a `canAuthorWorkbench` builder could DELETE an
Access Profile — which WIDENS that subject's access — or save a Requirement with `status: delivered`) and on
every import door except Space creation (`/spaces/import`, which may seed them). ⚠ **Breaking change:** a
`/bundle/import` or `/import` bundle carrying a `requirement` (item or `registry/requirements/` file) is
refused WHOLE — nothing is written; move Requirements with `POST /requirements` instead.
⚠ **`POST /spaces/import` is `canAdminister` always** (operator 2026-10-03; the `recovery-route` exemption and `SpaceRoutes.requireAdministerUnlessRecovering` are gone, so the blank-server hole where ANY authenticated caller could seed `access-profile` / `access-catalog` / `requirement` files is closed). Pinned by `ControlApiSpacesTest` (a no-capability Subject gets 403 on an empty container). ⚠ `checkFiles` classifies an entry by its FIRST `/registry/` segment — safe only while no import door re-roots entry paths (a door that strips or re-prefixes paths before writing must re-classify). `POST /spaces` is the same since 2026-10-03 (`TEMPLATE-RECOVERY-IMPORT-GATE-1`). A fresh install is not bricked: with no Space hosted `ControlApi.authenticate` binds no roles root, so `Roles.effective` serves the seed table and the IdP `admin` role holds `canAdminister`.
**Classification is spelling-proof:** each raw entry is classified after `ImportCapabilityGuard.normalizedPath`
(case, `.`/empty segments, the trailing dots and spaces Windows drops), and a file under `registry/` whose kind
cannot be told is refused (defence in depth behind `ImportPaths`' shape rule); `DecisionRuleGuard.guardImport`
uses the same normalisation. **Completeness is derived:** `ImportCapabilityGuardTest` walks
`CapabilityManifest` for every `ComponentStore.WRITABLE_TYPES` kind (plus `connection`) and fails when a kind's own
write route is stricter than `canAuthorWorkbench` and neither `KIND_CAPABILITY` nor `DEDICATED_ONLY` covers it
(mutation-checked: changing the alert-rule entry turns two cases red). Real-HTTP tests:
`ControlApiDedicatedOnlyKindsTest`. A `/bundle/import` `kpi` item also meets the `/components/kpi` save gate
(`KpiRoutes.requireMeasure`: Dataset exists and is readable, fields in its Schema, `timeField` a DATE/TIMESTAMP).
`SpaceManager.createFromBundle` (a new Space, `canAdminister`) keeps only the segment rules.

Tests: `ControlApiImportReservedPathsTest` (real HTTP, armed Subject; every alias at all three doors on a
Space with and without a `roles.toon`, 403 and a byte-identical config tree; and every ops suffix in a
subdirectory, under `registry/`, at the root and through a real reference key, at `/import`, as a
`/pipelines/import` satellite and a `/bundle/import` closure file), `ImportPathsTest`,
`ImportLoaderInventoryTest`. The Incident gate's own half (a workflow with a `CLOSED` terminal state) is in
the incidents concept, [`incidents.md`](../../addon/incidents/plan-incidents.md).

## Maker-checker: a held config change needs a different person (`ASSURE-MAKER-CHECKER-1`, 2026-09-26)

A Space's **Approval Policy** (`approval.toon`, `PUT /settings/approval`, gated `canAdminister`) can require
approval per config kind. Under it an authoring write becomes a **Pending Change** (answered `202`) and is
applied only when `POST /pending-changes/{id}/approve` succeeds. The decide gates follow the Link Analysis
four-eyes precedent (D-U7): `canApproveChanges` on the route (a literal gate, in `CapabilityManifest`) → an
authenticated Subject, else 403 → already decided or expired 409 → the kind's `approverCapability` (default
`canApproveChanges`) → with `fourEyes` (the default) the author deciding their own change is 403, whether
approving or declining. `canApproveChanges` is seeded to `admin` only (`super` holds everything).

- **Access settings hold too (`LA-PRODUCT-NEXT-1` phase 1, 2026-10-29).** The role table (`PUT /access/roles`,
  kind `access-roles`) and the Access Policies (`PUT /access/policies`, kind `access-policies`) are governable like
  the Access Catalog and Access Profiles always were. Before this, a policy could hold a deny overlay but not the
  write that grants capabilities, `canApproveChanges` included. The hold is the platform's one Pending Change hold,
  so a save from the console's Settings ▸ Access and from the Link Analysis app's Access page is held alike.
  Every Access screen (Roles, Policies, and the Catalog / Profile matrix) reads a `202` as *held*, not saved: it
  says "Held for approval as `<id>`", keeps or re-reads the stored state, and the change waits under Pending Changes.
  The check is the shared `heldChange()` (`inspecto/api/pending-changes.service.ts`); a new screen that writes a
  governable kind uses it too. Each document is ONE
  target (name `roles` / `policies`): a second proposal while one waits is 409, and an edit made after a proposal
  makes it stale (409 at approve, propose again). The would-lock-out check on policies runs at propose time AND at
  apply, as the author. ⚠ **Recovery:** if no approver is left to decide a held access change, the host administrator
  edits `approval.toon` or the access file on disk, as for any other held kind.

- **A policy that could never approve is refused.** Requiring approval on a build with no Authenticator is
  422 at save: without Subjects an author and an approver cannot be told apart.
- **The apply runs AS THE AUTHOR, re-checked at apply time** (D-P13, operator 2026-09-26 — a checker need not
  be a builder). Approve replays the author's original request in-process through the same route
  (`ApiContext.replay`) with a Subject for the author: their recognised roles, recorded at propose time, are
  re-resolved against the Space's role table and Access Profiles as they stand NOW
  (`PendingChanges.authorNow`), exactly as `OidcAuthenticator` resolves them. The approver needs only the
  approver capability, so a checker who is no builder approves a builder's change (the seeded `admin` also authors
  since 2026-11-15, but never approves its own change). An author
  who has since lost the grant gets 403 with that reason, and the change stays pending. ⚠ Role MEMBERSHIP is
  the IdP's view as of the proposal — a server cannot re-ask the IdP about a user who is not in the request.
  🔴 With an Authenticator that stamps no roles the apply FAILS CLOSED (403, "the author's roles are
  unknown"); a propose-time capability snapshot is recorded for the reviewer and never trusted.
- **Approve replays only a maker-checker route, from a record this server wrote.** Two checks run BEFORE
  anything is dispatched: the record's MAC (HMAC-SHA256 over its canonical JSON, a per-Space key) must
  verify, else the record lists as `invalid` and approve / decline are 409; and the recorded method + path must
  be on `PendingChanges.REPLAYABLE` — exactly the routes that reach the hold before they write, pinned to the
  scanned inventory by `ConfigWriteFunnelTest#theReplayAllowlistIsExactlyTheRoutesThatHold` — else 409. Found
  by re-verification: a forged record naming `PUT /access/roles` had rewritten `roles.toon` while approve
  reported "not applied". ⚠ The MAC defends against a record written through any door that cannot READ the
  key (an import, an upload); a local administrator can read both.
- **The author may withdraw their own change** (`POST /pending-changes/{id}/withdraw`,
  `ASSURE-MAKER-CHECKER-RESIDUALS-1` (2), 2026-09-28). It closes `withdrawn` — terminal, `decidedBy` = the
  author, the optional `reason` as `decisionReason`, MAC-signed and audited as a decline is. No capability on
  the route (a recorded `self-service` exemption in `CapabilityManifest`): the only person who may withdraw is
  the Subject who proposed it, so the author check IS the gate. Gates: no authenticated Subject 403 (Basic
  is refused, as for decide) → write root 503 → `reason` over 500 chars 422 → unsafe id 422 → unknown 404 →
  integrity 409 → not pending (decided, withdrawn, expired) 409 → not the author 403. It runs under the same
  store lock as decide, so a withdraw racing an approve leaves one outcome (`PendingChangesMultiPodTest`). The
  SPA offers **Withdraw** on the Pending Changes inbox to the session Subject (`SessionService.actor`) who
  authored a waiting change.
- **Every step is audited** as an `AUDIT` event, `actionCategory: configuration`: `pending-change.proposed`,
  `.approved`, `.declined`, `.withdrawn`, `.expired`, `.stale`, `.apply-refused`, and `approval-policy.changed` (the actor
  and the policy before and after, JSON). ⚠ Know which row says what: the approve REQUEST's own route-level
  row (`AuditTrail`) has the **approver** as actor; the replayed WRITE's row has the **author** as actor plus
  `approvedBy` and `pendingChange`; `pending-change.approved` has the approver as actor and the author as an
  attribute.
- **An import writes only an allowlist of shapes** (re-verification finding 1, `ImportPaths`) — a string
  denylist alone was walked by Windows aliases (`roles.toon.`, `ROLES~1.TOO`, `PENDIN~1/`, `audit./`). Three
  layers, all required, on `/import`, `/pipelines/import` satellites and the `BundleImporter` backstop: segment
  rules on every platform (plain names only — no trailing `.`, `~`, `:`, space, control character, leading dot,
  `..` or Windows device name); the allowlist (a file another carried config references, or a `.toon` / `.csv`
  of a known shape — at the config root only the conventional suffixes, under `registry/` only
  `registry/<importable kind>/<name>`); and the denylist below plus a real-path check (`toRealPath` +
  `isSameFile` / prefix against every reserved file and directory, fail closed on I/O errors). A carried kind
  whose own route is stricter needs that route's capability (`IMPORT-CONNECTION-JOB-GATE-1`, above).
- **An import may never write the files a narrower gate owns** (D-P14, `ReservedConfigPaths`): `roles.toon`,
  `access-policies.toon`, the Access Catalog and Access Profiles, `approval.toon`, `pending-changes/`, every
  Space settings document, `rename.journal`, `recon-state/`, `.history/`, `audit/`. `/import` and a
  `/pipelines/import` satellite refuse them (403), `/bundle/import` refuses `access-*` items, and
  `BundleImporter.writeConfig` refuses them as a backstop. 🔴 **This closed a privilege escalation older than
  maker-checker**: `POST /import` needs only `canAuthorWorkbench`, and until 2026-09-26 a zip carrying
  `roles.toon` rewrote the role table the Authenticator resolves every Subject from (reproduced: 200, the
  builder's `developer` role gained `canAdminister`); `/pipelines/import ?conflict=overwrite` could land it as
  a satellite, and `/bundle/import` could carry `access-profile` items. `SpaceManager.createFromBundle` (a NEW
  Space, `canAdminister`) still writes them — there is nothing there yet to overwrite.
- **Deciding is a cross-process compare-and-set** (`ASSURE-MAKER-CHECKER-MULTIPOD-1`, 2026-09-27). Every
  read-check-write on the Pending Change store — propose (the one-pending-per-target check), list/read (expiry),
  approve, decline, withdraw — runs under `PendingChanges.underStoreLock`: the JVM monitor, then an OS-level
  `FileChannel.tryLock()` on `<write-root>/pending-changes/.lock`, so two Pods sharing a Space's directory
  serialise. The file-lock wait is BOUNDED (default 5000 ms, `-Dinspecto.pendingChanges.lockWaitMs`): a Pod
  that stalls holding it yields 503 `STORE_BUSY` (retryable) on the others, never a hung request thread
  (`PendingChangesLockTimeoutTest`). Deciding re-reads the record under the lock and moves `status` off `pending` only if it is still
  `pending`; the APPLY (the replay) runs inside the same lock, so a loser can never apply. The loser gets 409
  `pending change '<id>' is already approved (decided by <who> at <when>)`. Records are saved atomically (temp
  + `ATOMIC_MOVE`). ⚠ There is no OperationalDb-backed Pending Change store, so no conditional-UPDATE variant
  exists; the lock needs a filesystem whose locks are honoured across hosts (a local disk or a correctly
  configured shared volume — NFSv3 without lockd is not). Proven by `PendingChangesMultiPodTest` (two
  control planes over one directory racing approve/approve and approve/decline over real HTTP, and two JVM
  processes racing on the lock).
  **(operator, 2026-09-28): kept closed — deciding a Pending Change takes a file lock and re-checks the record is
  still pending (proven by `PendingChangesMultiPodTest`); a live two-pod test is owed at the first HA deployment** (tracked on `BACKLOG.md` §2, *Deployment topology live validation*).
- The AI-agent approvals inbox (`/agent/approvals*`) is a different thing and is unchanged.

As-built detail (the hold, the funnels, what is governable): [config safety](../config/spec-config-safety-maker-checker.md#maker-checker--pending-changes-2026-09-26).
