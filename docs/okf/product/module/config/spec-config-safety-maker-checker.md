---
type: Concept
title: Config Safety Validator — Maker-checker and Safety Policy tier files
description: Part 4 of 4 of Config Safety Validator: Maker-checker — Pending Changes (2026-09-26); Safety Policy tier files — loading and the unreadable refusal (2026-10-03/04, DUCKLE-C6-POLICY-NARROWING-1 S2a + S2b)
tags: [config, safety, validation, path-jail, security]
timestamp: 2026-06-28T00:00:00Z
---

# Config Safety Validator — Maker-checker and Safety Policy tier files

Part 4 of 4 of [Config Safety Validator](spec-config-safety.md) · previous: [Job config decisions, the accepted-key census and the classification vocabulary](spec-config-safety-keys-vocabulary.md).

## Maker-checker — Pending Changes (2026-09-26)

*`ASSURE-MAKER-CHECKER-1` S1–S3.* Glossary: **Pending Change**, **Approval Policy**. Security side:
[auth & security](../access/admin-guide-auth-security-imports-maker-checker.md#maker-checker-a-held-config-change-needs-a-different-person-assure-maker-checker-1-2026-09-26).

**The policy** — `approval.toon` in the Space config root, `GET|PUT /settings/approval`:
`{approval: {<kind>: {required, approverCapability, fourEyes}}, expiresAfterHours}` (defaults: off, the
`canApproveChanges` capability, four-eyes on, 168 h). Validated fail-closed at save: a kind outside
`ApprovalPolicy.GOVERNABLE`, a capability no route demands, an unknown key or a bad value is 422. A file that
is present but unreadable is **not** read as off: every governable kind is held until it is fixed
(`failedClosed: true` on the GET).
**The route is refused** (operator, 2026-09-28): an empty or absent body is 422 — turning four-eyes off must be
explicit, `{"approval": {}}`; and with no Authenticator configured `PUT /settings/approval` is 403 before any
other gate, because the `canAdminister` wrap is a no-op without a Subject and the policy must never be switched
off unauthenticated. **Personal edition therefore cannot change the approval policy through the API.**

**Governable** — `pipeline`, `schema`, `enrichment`, `meta` (the `/config/write` + `/config/patch` types) and
every `ComponentStore` kind except `requirement` — `channel` and `notification-rule` too since 2026-10-06
(their six `/notifications/channels*` + `/notifications/rules*` writers hold after their 422/404/409 gates; the
replay applies on approval, so a held channel edit never re-points a destination early) — and `job` (operator,
2026-09-28): the six `/jobs` writers — create, update, delete, enable, disable, reschedule — hold after their
gates (the Job spec + `checkJob`, the `event_prune`/`restore` `canAdminister` check, `If-Match`), keyed by the
Job name; the replay applies as the author, so `JobAuthority.stamp` writes the AUTHOR as `updatedBy`, never the
approver, and the run-time `canAdminister` re-check (MAINT-TASK-AUTHORITY-1) judges the author. A Pipeline rename
refuses to rewrite a governed Job's `on_pipeline`. ⛔ A Job's RUN-time writes (`MaterializeTask` restating its
target Dataset, engine writes) are NOT held, by decision (operator, 2026-09-28): holding them would stall
scheduled runs. ⛔ Not governable: Connections, Tags and Case Rules, the Space settings documents — the
approval policy above all, which could otherwise never be lifted.

**The hold** — `PendingChanges.hold(api, ex, kind, name, proposed, current)`, called by each authoring route
after every validation it runs and before any side effect: `/config/write`, `/config/patch`,
`DELETE /config/{type}/{name}`, `PUT /pipelines/{name}/graph`, the history restore, `/label`, `/settings`,
`/save-as-template` (its schema copy is taken back while held), `/rename` (before the journal's step 0), and
the component writers — `/components/*` create / update / restore / delete, `/alerts/rules*`,
`/decision-rules*`, `/expectations*`, `/access/catalog`, `/access/profiles*`. With no rule for the kind it
returns and the route writes exactly as before. With one, it stores the Pending Change and throws
`PendingChanges.Held`, which `ControlApi.routeDispatch` answers `202 {status: pending, written: false,
pendingChange}`. One pending change per kind + name at a time (a second is 409). The author may send
`X-Change-Reason`.

Writers that cannot be ONE Pending Change **refuse** under a policy (409) instead —
`PendingChanges.holdRefusing`: `/bundle/import`, `/pipelines/import` (a binary bundle a replay cannot
carry), BI template apply, and the Investigation Alert Rule bind (owner-only, so no approver could apply it).
Added after the 2026-09-26 verification:
- **`POST /import`** refuses (409) when any zip entry is a governed kind or cannot be classified
  (`PendingChanges.holdRefusingPaths` / `kindOfConfigPath`); and every import refuses the reserved files
  whatever the policy (see [auth & security](../access/admin-guide-auth-security.md)).
- **A Pipeline rename** that would rewrite a dependent (Expectation / Decision Rule target, Dataset store ref,
  Alert Rule `onPipeline`, Enrichment trigger) of a governed kind is refused (409) naming it
  (`refuseGovernedDependents`, rename and resume, before step 0).
- **Tag routes** (assign / unassign / rename / delete) that would re-project a widget's `tags` refuse under a
  policy on `widget`, before the edge moves (`WidgetTags.refuseUnderPolicy`).
- **The agent's fix drafts** never overwrite an existing component of a governed kind (`PendingChanges.governs`).
- `ConfigWriteFunnelTest#everyConfigWriterRepoWideReachesTheHoldOrIsOnTheInventory` scans EVERY module —
  not only routes — for ComponentStore-shaped writes, bundle unpacks and Entity Fact log appends; each site's
  method must reach the hold or sit on its `WRITERS` inventory with a reason (shared helpers, result stamps,
  non-governable kinds, Job/engine writes, the Identity Fact log). Removing the `/import` refusal turns it red.
  `PendingAlertRules#ensureLatestDataset` is on it as a background template materializer (operator, 2026-10-06) —
  see [spaces §3.5.2](plan-spaces.md); `DecisionRuleWritersTest.GUARDED_BY` lists it and
  `#onScoreProduced`, which runs `DecisionRuleGuard.refuseUnattended` before writing.

**The apply** — approve replays the stored request (method, route path + query, body, `If-Match`) through
`ApiContext.replay`, with the Pending Change stamped on the replay (`ATTR_APPROVED_CHANGE`). Every gate of that
route runs again. When the replay reaches the hold it lets the write through only if it is the same write:
the same kind + name, the content it replaces still at the **base version** (else 409, closed as `stale` —
the route's own `If-Match` 409 counts too), and the content it produces still the approved one. Versions are
content hashes that leave out write-time and result stamps (`createdAt`, `updatedAt`, `lastResult`,
`lastSimulation`), so a routine evaluation does not make a waiting change stale. Any other refusal of the
replay comes back as its status and leaves the change pending.

**Storage** — one JSON document per change at `<write-root>/pending-changes/<id>.json` (ids
`pc-<yyyyMMddHHmmss>-<6 hex>`), the `ReconStateStore` pattern: atomic temp + move, jailed, an unreadable
document is an error, never "absent". Not an OperationalDb family, so there is no backup / bundle-staging
lockstep; it sits in the config tree. Expiry is recorded lazily — on the next list, read or decide.
Each record carries a MAC (HMAC-SHA256); a record that fails it reads as `status: invalid`, is never re-saved
and cannot be decided. **The key** (`PendingChanges.keyFile`) lives OUTSIDE the config tree, in the sibling
`<config root>.secrets/.pending-changes.key` — `<space>/config.secrets/` for a hosted Space,
`<assist.write.root>.secrets/` for the default Space — so no export (`/export` walks the config tree), import
(writes only under the config root) or Exchange share (`<spaces-root>/_shared/`) can reach it; `BackupTask`
skips any `*.secrets` directory and the key file by name; `.gitignore` ignores `spaces/*/config.secrets/` and
`spaces/*/config/pending-changes/`. It is created with `CREATE_NEW` on every first use (no exists() pre-check),
so exactly one writer ever creates it and a racer reads the winner's key — a replaced key would invalidate every
record the first one signed. Owner-only where the platform allows: POSIX `rw-------`; on Windows an ACL with one
entry, the owner. Pinned by `PendingChangesKeyTest` (location, a two-thread race, permissions, git-ignore) and
`BackupTaskTest#aBackupNeverCarriesAPendingChangeKey`. ⚠ The store stays in the config
root rather than a data directory because the default Space has no reliable per-Space data dir (its data dir is
the CWD-relative `database`); the whole-Space export skips `pending-changes/`, and every import refuses it.
Approve dispatches only a route on `PendingChanges.REPLAYABLE` (the routes that hold before they write).

**Reads** — `GET /pending-changes[?status=&kind=]` (capped at 500, `total` + `truncated`),
`GET /pending-changes/{id}` (with `current` and `proposed`), `GET /pending-changes/{id}/diff` — the Pipeline
history's line diff (`PipelineHistory.diff`) over both contents encoded as TOON.

`ConfigWriteFunnelTest#everyConfigWritingRouteReachesTheMakerCheckerHoldOrIsExempt` fails a config-writing
route that reaches neither `hold` nor `holdRefusing` unless it is on `NO_HOLD` with a reason. Pinned over
real HTTP, with an armed Authenticator, by `ControlApiPendingChangesTest`.

⚠ Known limits: the guard's closure stops at the file boundary (a write in another class is invisible). (Four-eyes still forbids self-decline, as in
Link Analysis; since 2026-09-28 the author instead WITHDRAWS their own change — `POST /pending-changes/{id}/withdraw`,
status `withdrawn`, author-only, see [auth-security](../access/admin-guide-auth-security.md).)

**The inbox (UI)** — `/pending-changes` (`modules/admin/pending-changes/`, nav *Operations ▸ Pending
Changes*, beside the agent *Approvals Inbox*, which is untouched): the waiting (or all) changes in a
data-table; selecting one shows author, reason, expiry, decision and the diff through the shared
`<inspecto-line-diff>` (extracted from the Pipeline config-history dialog, which now uses it too). Approve /
Decline, with an optional reason, show only with `LensService.canApproveChanges` (action node
`changes.approve`); a server refusal — four-eyes, a stale base, the route's own 403/422 — is shown in place.
**Withdraw** shows only to the change's author (`SessionService.actor` equals `author`) while it waits,
behind `confirmDestructive`; a refusal toasts the server's message.

## Safety Policy tier files — loading and the unreadable refusal (2026-10-03/04, `DUCKLE-C6-POLICY-NARROWING-1` S2a + S2b)

Design: [`policy-narrowing-design.md`](../../../../archived-documents/plans-archive/policy-narrowing-design.md). `SafetyPolicyFiles` (`inspecto-config`, `config/safety/`) reads `safety-policy.toon` from `-Dsystem.config.dir` (server tier) and `<space>/config/` (Space tier), strictly, and folds them through `SafetyPolicyTier.fold`. `SafetyPolicy.forSpace` — hence `defaultPolicy()` and all 15 `ConfigSafetyValidator.check` callers — now takes its roots, thread/batch caps and formats from that effective tier, so a Space file can only narrow the operator-declared roots + the Space base.
- **Durable decisions (operator, 2026-09-28; the plan is archived 2026-10-06).** D1 the name is **Safety Policy**, the existing `SafetyPolicy` record extended, never a second type (⛔ bare *Policy*). D2 tiers are **server + Space**; a Pipeline tier is safe under the same fold but nothing asks for it. D3 the server file is `safety-policy.toon` in the server config dir; `-Dassist.safety.roots` stands in for `allow.roots` when the file omits them. D4 a Space's default roots are **its own base + the operator's declared roots**, never every hosted Space's. D5 `mode: enforce | audit`, server file only, default `enforce`; `audit` logs the would-refuse and proceeds. D10 `permit.rewind_state` is separate from `permit.advance_state` (default `true`), so "may not advance" never blocks a reprocess. D11 it is **core, every edition** (config safety, not ABAC) so the unreadable refusal cannot vanish on a classpath without `inspecto-policy`. D14 caps fold by **MIN**; the validator's existing caps are the server tier's defaults. Fold law: booleans AND, sets intersect (allow) / union (deny), numbers MIN, prefixes matched at a path or dot boundary — a lower tier can only narrow. ⚠ Absent ≠ empty: an absent `allow.*` does not constrain, `allow.*: []` permits nothing.
- 🔴 **Unreadable ⇒ throws, never "no policy".** TOON damage, a stat/IO failure (a *directory* named `safety-policy.toon` forces it), an unknown key (a mistyped `alow:` would otherwise silently drop the narrowing), a bad value, a relative root, `mode` or `require_spaces` in a Space file → `SafetyPolicyUnreadableException` (`ERR_SAFETY_POLICY_UNREADABLE`). `require_spaces[n]` in the server file (D7) makes a named Space with no file unreadable too; an un-named absent file narrows nothing.
- **`links: report | refuse-activation` (Space file only, 2026-11-13, `SPACE-LINK-CENSUS-1` LC-4).** Absent =
  `report`. Read by the Control API's link checks, not folded into the tier: `refuse-activation` makes this Space's
  LC-3 save and delete link checks refuse where the JVM mode is `shadow` (Standard and Advanced only, D-LC5; `shadow` is
  the JVM default until the operator's D-LC3 flip). Any
  other value, or the key in the server file, is unreadable like every bad key. `SafetyPolicyFiles.links`,
  `SafetyPolicy.linksForSpace`; behaviour in [spec-space-lock.md](spec-space-lock.md) §10.
- **`columns: report | refuse-breaking` (Space file only, 2026-11-13, SE-3b).** Absent = `report`. Read by the
  Control API's Schema save check, not folded into the tier: `refuse-breaking` stops a Schema save that removes,
  renames or narrows a column a consumer uses with 409 and the consumers listed; `?force=true` saves (Standard and
  Advanced only, D-LC5; Basic warns). Any other value, or the key in the server file, is unreadable like every bad
  key. `SafetyPolicyFiles.columns`, `SafetyPolicy.columnsForSpace`; behaviour in
  [spec-column-contract.md](spec-column-contract.md) §9.
- Parsed files are cached by their exact text (an (mtime, size) stamp missed a same-size rewrite inside one mtime tick, `f2110d781`); a failure is cached too and re-evaluated when the text changes.
- **S2b (2026-10-04) — the refusal surfaces, fail-closed everywhere.**
  - *Plan-time gates:* `ControlApi.errorBoundary` maps `SafetyPolicyUnreadableException` to **422** with `errorCode ERR_SAFETY_POLICY_UNREADABLE` (a new `ErrorCodes` entry + the contract's `ErrorCode` enum) and a `findings` array whose ERROR finding's `fieldPath` is the file. One boundary mapping covers every `ConfigSafetyValidator.check` caller that runs inside a route (SaveGate, RunRoutes, JobRoutes, DataSourceRoutes, EnrichmentRoutes, ComponentRoutes, BundleRoutes), because `defaultPolicy()` is what throws. The three agent-tool callers (`ComponentActions.apply`/`preview`, `InspectoTools` validate) catch it themselves and fail closed as a refusal/ERROR finding. Audit status is 422, not 500 (`failureStatus`).
  - *Runs:* `SafetyPolicy.pinnedForRun(Supplier)` resolves the Space's effective policy **once, before the run claims anything**, throws if unreadable, and pins the snapshot (D8) in a `ScopedValue` that `defaultPolicy()` returns for the rest of the run on that thread; a file tightened mid-run applies to the next run. Wired at `CollectorService.runPipeline` (async trigger records a `FAILED` Run whose message is the reason; the sync off-thread path rethrows into the 422 mapping), `PipelineScheduler.runOne` (poll failed, counted) and `JobService.runJob` (a `FAILED` job Run naming the file; the job body never starts).
  - *Health (D6):* `GET /health` stays 200 and serves, but reports `{"status":"DEGRADED","safetyPolicy":[...]}` while any file in scope (server file, each hosted Space) is unreadable; `SafetyPolicy.unreadable()` backs it.
  - ✅ **The load-time vanish is closed (S3, 2026-10-04).** `PathJail.allowedRoots()` is a third consumer of `defaultPolicy()`, so an unreadable file used to make `ConfigRegistry` log "Could not load config … ERR_SAFETY_POLICY_UNREADABLE" and drop the pipeline. The one load-time jail (`PipelineConfigParser.resolveSchemaRef`) now catches the exception and judges the ref against `SafetyPolicy.baseRoots(space)` (the operator roots + the Space base, no policy file read, never wider than the pre-narrowing posture); the pipeline stays registered and its **run** is the visible refusal (failed Run + `ERR_SAFETY_POLICY_UNREADABLE`, `/health` DEGRADED, 422 at every gate). Loading is not running; every act-time gate still throws.
  - ⚠ The pin is a `ScopedValue`: visible on the planning thread, **not** on a worker pool. S3 hands it over where a run fans out: `SafetyPolicy.pinned()` captures the snapshot, `SafetyPolicy.runWithPinned(policy, run)` rebinds it on the worker (`MultiCollectorProcessor.runAll` / `runConfigs`). A new act-site on its own pool must do the same, or it re-reads the files (a mid-run change) and, with no Space MDC, reads the `default` Space's policy.
  - Tests: `SafetyPolicyUnreadableRoutesTest` (T5, T6 at run level, the 422 + `/health` over HTTP), `JobServiceTest.anUnreadableSafetyPolicyFileRefusesTheRunAsAFailedRun`, `SafetyPolicyFilesTest` (pin + refusal-before-start). Mutation-checked: disabling `pinnedForRun` turns T5/T6 red with `SUCCESS` where `FAILED` is expected.
- 🔴 **`safety-policy.toon` is a RESERVED config file (2026-10-04).** `ReservedConfigPaths.FILES` lists it, so every import door (`POST /import`, `/spaces/import`, bundle/template seed) refuses it by name, whatever its case or Windows alias. An imported copy could narrow or brick a Space, and a *tightened* Space file is operator intent that no bundle may rewrite. The file is authored on disk by the operator only. `ImportLoaderInventoryTest` caught the unreserved read; `ControlApiImportReservedPathsTest.ALIASES` pins the refusal on every door. `POST /spaces/import` now has an armed-Authenticator literal in `ControlApiSpacesTest` (the guard matches the bare path, not `?id=`).
- **S3 (2026-10-04) - paths.** `allow.roots` already narrowed `SafetyPolicy.allowedRoots` (S2a); `deny.roots` is now `SafetyPolicy.denyRoots` (a new record component, from the folded tier). `PathJail.requireUnderAny(roots, deny, value, field)` refuses a contained path that also lies under a deny root (deny beats allow; boundary-correct, `secrets2` is not under `secrets`); the 3-arg form and therefore `requireJobPathUnderAny` and every act site in design 1.2 take the deny set from `PathJail.deniedRoots()` = the calling thread's `defaultPolicy()`. `ConfigSafetyValidator.checkPathValue` gives the same verdict as a finding. P0-c twin: an absolute path into a string-prefix sibling Space (`s10` vs `s1`) is refused, the Space's own path passes. Tests: `SafetyPolicyPathsTest` (7; the deny loop mutation-checked: 3 red) and `SafetyPolicyUnreadableRoutesTest.s3_...` (the load fallback, mutation-checked). Hooks note: a bare unit test has no Space MDC (no slf4j binding), so the unit tests use the `default` Space.
- **S4 (2026-10-04) — egress: `EgressGate`.** `EgressGate.require(host, port, purpose)` (`config/safety/`) refuses on `permit.network false`, an `allow.hosts` miss, a `deny.hosts` hit, and (D13) a permitted *name* that resolves into a denied CIDR (DNS is resolved only when a CIDR deny exists); `mode: audit` logs and proceeds. `EgressGate.current()` reads the calling thread's pinned tier (`SafetyPolicy.effectiveTier()`, new — the `Pin` now carries the tier); `EgressGate.of(tier)` is the explicit hand-off, `EgressGate.server()` the server-tier-only gate (D16). Helpers `requireJdbcUrl` (every host of a multi-host URL; in-process `duckdb`/`sqlite`/`h2` pass; a URL whose host cannot be read is **refused**, not waved) and `requireHostList` (Kafka `bootstrap.servers`).
  - *Act sites:* **N1** `CollectorConnectors.forConfig` gates the whole profile before any connector exists — target, SSH bastion, proxy, `options.jdbc_url`, `options.bootstrap_servers` — so SFTP/FTP/DB-export hops (N2/N3/N6) are covered at the one seam and **T14** (a pipeline placed on disk, no save gate) refuses there. **N4** object stores: `AbstractHttpObjectStoreConnector.send` gates every request, which includes the GCS token endpoint (it sends through `sendReadingBody`). **N5** `KafkaConnector` checks every partition **leader** host after each `partitionsFor` (discover, backlog probe, `fetchTo`) before any offset lookup or fetch (D12). **N7** `ConnectionTester` gates its probe socket. **N8** `POST /system/operational-db/test` passes the **server** tier (422 + the refusal) before the password is resolved.
  - 🔴 **The design's "N4 redirect rewrite" was already shipped, stronger.** `Redirect.NORMAL` no longer exists: `PinnedObjectStoreHttp` (OBJECT-STORE-EGRESS-POLICY, 2026-09-27) never follows a 3xx, so the `Location` host is never dialled — T7's "B allowed → followed" half is superseded on purpose (a SigV4 signature covers `host`, so a followed redirect would fail anyway). `ObjectStoreEgressTest.aRedirectIsNeverFollowed` pins it.
  - ⚠ **Worker threads:** connectors capture `EgressGate.current()` in a field when they are *built* (planning thread, inside the run pin); a component that dials from a pool must do the same, never call `current()` there — off the pin it re-resolves the thread's Space, which on a pool thread is the `default` Space.
  - 🔴 **Host spelling, refused before any policy verdict (`SCALEOUT-OBJSTORE-DATABASE-DIR-1` (e), 2026-11-13).**
    A host that reads as an address must be a canonical IPv4 dotted quad or a valid IPv6 literal
    (`EgressPolicy.checkAddressSpelling`, the numeric half of `checkHost`). Decimal `2130706433`, octal `0177.0.0.1`,
    hex `0x7f.1` / `0x7f000001`, short `127.1`, leading zeros `127.0.0.01`, a trailing dot (`127.0.0.1.`,
    `name.example.`) and an IPv6 zone id are refused in **every mode and under every tier, `NONE` and `audit`
    included**: they are malformed hosts, not policy verdicts, and a native dialler (`inet_aton` in libcurl /
    DuckDB `httpfs`) reads them as an address the `allow.hosts` / `deny.hosts` patterns never compared. **Refuse,
    don't normalise** (decided 2026-11-13): normalising would make the gate one more parser that must agree with
    every dialler's; refusing leaves exactly one spelling per address. A name is not checked here (IDN names keep
    passing), but the rule runs on the raw host AND on its IDNA form (`HostPattern.normalizeHost`, which maps
    `。` U+3002, U+FF0E and U+FF61 to `.`), so `0177。0。0。1` is judged as `0177.0.0.1`. ⚠ An all-digit last
    label (`kafka.01`) reads as numeric and is refused too. The D13 CIDR check also classifies an IPv6 answer by the IPv4 it carries (mapped, compatible, NAT64
    `64:ff9b::/96` and `64:ff9b:1::/48`, 6to4 — `EgressPolicy.embeddedIpv4`), so `64:ff9b::7f00:1` hits a
    `127.0.0.0/8` deny. Since `EgressGate.require` is the one chokepoint, every act site above and the object-store
    `dirs.database` gate inherit the rule.
  - Tests: `EgressGateTest` (14; the four item-(e) tests each pair a refused spelling with an accepted twin one
    character away and went red against the pre-fix gate), `SafetyPolicyEgressTest` in `inspecto-connectors` (T14, T8, T7 request gate, T12 leader hosts — each with an allowed twin and an observed socket/request count), `ConnectionTesterTest`, `ControlApiSystemRoutesTest`. Mutation-checked: removing the N1 gate turns T14 and T8 red.
  - Not gated by S4 (by design or deferred): N10–N12 (server-configured, D15); `allow.connectors` (scheme gate, not a host); the DuckDB-side egress N13–N15 is S5.
- **S5 (2026-10-04):** `DuckDbExtension` honours `allow.extensions` and refuses the `INSTALL` fallback under `permit.install_extensions: false`; `DuckLakeRegistrar` gates the catalog host and a remote data path through `EgressGate` before its catch-all. Still open: the `IndexBuilder` file-access opt-in.
- **S7 (2026-10-04) - explain.** `GET /settings/safety-policy` (`SafetyPolicyRoutes`; `canAdminister`, in `CapabilityManifest`) returns the Space's effective policy, one row per field with `effective` + `constrainedBy` (`server`/`space`/`default`), the files consulted, and the D15 `exempt` surfaces. `SafetyPolicy.tiersForSpace` -> `SafetyPolicyFiles.Tiers` (server as stated, Space as stated, fold) -> `SafetyPolicyExplain`. An unreadable file answers 200 `readable:false` + the reason (a diagnostic must name the broken file; the gates still 422). Test `SafetyPolicyExplainRoutesTest`.
- ⚠ **Not yet done (S8 onward):** the explain UI (its own design). The sentinel format set `{"-"}` stands for "allow.formats ∩ defaults is empty" because the record constructor treats an empty set as "use defaults".

### S6 - state: `StateGate` (2026-10-04, `DUCKLE-C6-POLICY-NARROWING-1`)

`StateGate.requireAdvance(what)` / `requireRewind(what)` (`config/safety/`) read the run's pinned tier (`permit.advance_state` / `permit.rewind_state`); refusal is `StateRefusedException`, `mode: audit` logs and proceeds. Wired at: batch start in `ConsignmentIngestor.process` (before `strategy.ingest`, so nothing is written - M1-M3 run after outputs are durable), the M1 marker, M2 fingerprint-ledger and M3 DB/Kafka-watermark sites in `finalizeSource` (backstop), M4 `RemoteAcquisitionHandler.land` (only when a frontier exists), M5 `PipelineJobRunner` (before the sink writer for an incremental, non-dry run, and again in `advanceWatermarks`), M6 `RowShaper.windowedDedup` (before the first dedup claim), M8 `ReprocessCommand.run`, `DedupPruneTask` and `LedgerPruneTask` (`requireRewind`). The batch-start check fires when markers or the duplicate check are on, **or** any member carries a stashed watermark / durable slice-frontier record (2026-10-06 - a watermark-only Kafka/DB-export Pipeline otherwise wrote outputs and was refused only at M3, stranding them). Tests: `StateGateTest`, `KafkaSliceAdvanceStateTest` (T12), `PipelineJobRunnerTest` (T11), `PipelineExecutorDedupWindowTest` (M6), `MaintenanceLibraryTest` (prune).

✅ **D9 plan-time refusal (2026-10-06):** `ConfigSafetyValidator` (every save/register gate, like S2b/S3) refuses, under an effective `permit.advance_state: false` at save time, each key that implies an advance with `ERR_SAFETY_STATE_ADVANCE_REFUSED`: `collector.connector` other than `local`/`dataset`, `collector.duplicate.mode` other than `path`, `processing.duplicate_check.enabled` with `dirs.markers`, a `window(...)` scope on `processing.dedup` or a `processing.steps[n].dedup`, and a job's `incremental_column` (operator, 2026-10-06). Carried as `SafetyPolicy.advanceState` (a `mode: audit` policy reads as permitted). A policy tightened after the save is caught only by the act-time gates (operator, 2026-10-06). Tests: `StateAdvancePlanTimeTest`.
