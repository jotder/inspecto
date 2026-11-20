---
type: Reference
title: API stability policy — The marker, the release baseline and the pending release notes
description: Part 1 of 3 of API stability policy: What the marker means; Release baseline — nothing after 3.x has shipped; The baseline guard (PUBLIC-API-SURFACE-1, 2026-11-12); Release notes — the pending MAJOR (draft, kept current until the tag is cut)
tags: [api, stability, publicapi, semver, release]
timestamp: 2026-07-16T00:00:00Z
item: module.api
audience: internal
status: plan
verified: 2026-11-20 code-reviewed
code: platform/inspecto-api
---

# API stability policy — The marker, the release baseline and the pending release notes

Part 1 of 3 of [API stability policy](plan-api-stability.md) · next: [Release notes — breaking configuration and CLI changes onward](plan-api-stability-breaking-config.md).

## What the marker means

| | Marked `@PublicApi` | Unmarked (internal) |
|---|---|---|
| Removed / changed incompatibly | Only on a **major** version bump, noted in release notes | Any release, no notice |
| New members added | Allowed (minor bump) | Anytime |
| Safe to depend on from plugins / embedders | **Yes** | No |

Within a major version, `@PublicApi` elements follow semantic versioning. The
annotation has `CLASS` retention — visible to tooling and Javadoc, not required
at runtime.

## Release baseline — nothing after 3.x has shipped

**The newest release on `master`'s ancestry is `v3.11.0`** (2026-06-03). `v3.12.0` exists as a tag but
is **not an ancestor of `master`** (divergent lineage), and the `v4.0.0` / `v4.0.0-RC1` tags were
**deleted 2026-08-17** with the `4.x` branch because 4.0.0 never reached production
([BRANCHING.md](../../../../BRANCHING.md) §1). Trunk is `4.0.0-SNAPSHOT`.

Two consequences, and they govern every relocation and rename question on this codebase:

1. **`since()` is informational, and a value above 3.x means "will become public API in 4.0.0" —
   not "has been public API since".** Every `@PublicApi` element introduced after 3.x therefore
   carries `since = "4.0.0"`. Values of 4.1.0 … 5.8.0 were previously written against versions that
   never existed; they were corrected to `4.0.0` in one sweep (200 sites). Three written after that sweep
   — `MailAttachment` and `DbEventStore` (`"5.0.0"`) and `AcceptedConfigKeys` (`"5.2.0"`) — were corrected
   to `4.0.0` on 2026-11-16 (`PUBLICAPI-SINCE-VERSIONS-1`); no mark names a version above 4.0.0 now
   (checked 2026-11-17). *(This item said the three were still wrong until 2026-11-17.)*
2. **An element whose `since` is `4.0.0` has never been published in any release, so it may still be
   moved, renamed, or changed freely** — the stability promise binds *within a released major*, and
   4.0.0 is not released. ⛔ **`@PublicApi` alone does not make a change breaking.** Check whether the
   element exists in the newest ancestor release before treating a relocation as an API break:
   `git ls-tree -r --name-only v3.11.0 | grep -E '/<Class>\.java$'`.
   This premise (`@PublicApi` ⇒ breaking ⇒ bump) has now been written down and refuted **three**
   times — the Source→Collector rename ([GLOSSARY](../../../../GLOSSARY.md) §13), the <!-- vocab-allow: names the Source→Collector rename itself -->
   `ConsignmentProcessor` SPI widening ([BACKLOG](../../../../BACKLOG.md) §4), and the architecture
   plan's Phase C cycle cuts. It is *inherited*, not measured. Measure it.

## The baseline guard (`PUBLIC-API-SURFACE-1`, 2026-11-12)

[`platform/inspecto-api/public-api.txt`](../../../../../platform/inspecto-api/public-api.txt) records the
marked surface: one line per `@PublicApi` type header and per member a caller outside the package can see
(`<type> | <signature>`, parameter names left out). Marking a type covers its visible nested types, so
`CollectorConnector.Readiness` and `ConsequenceProvider.Result` need no mark of their own. One exposed type
is left unmarked on purpose: the deprecated enum `com.gamma.job.JobType`, which two `JobConfig` constructors
still take. Marking it would promise a type that is on its way out.
[`tools/check-public-api.mjs`](../../../../../tools/check-public-api.mjs) reads every tracked `.java` file
(no module list) and fails on any difference from that file. It runs in CI's `guards` job and so in
pre-commit. An intended change is recorded by `node tools/check-public-api.mjs --update` in the same commit,
where the reviewer sees the changed lines. The guard does not judge whether a change is allowed: under the
rule above, a line whose type was not in `v3.11.0` may still change before 4.0.0 ships.
`since` is not part of a line; the same guard fails a `since` that is not `X.Y.Z` or is above the pending
version (the root `pom.xml` version without `-SNAPSHOT`), so a version that never existed cannot be written
(`PUBLICAPI-SINCE-VERSIONS-1`, 2026-11-16: three marks said `5.0.0` / `5.2.0`). It does not check that the value
is a released tag: `1.0.0`, `2.2.0` and `2.4.0` are in use and have none.

## Release notes — the pending MAJOR (draft, kept current until the tag is cut)

Everything below is already on `master` and is either a `feat!:` or an operator-visible behaviour change
relative to `v3.11.0`, the newest ancestor release. The list is the BACKLOG §2 "Release notes for the next
MAJOR" row, moved here so it accrues in one place; add a line in the same commit as any further `feat!:`.
The GitHub release is cut by `.github/workflows/release.yml` with `--generate-notes`; paste this section
above the generated commit list.

**Which release 4.0.0 follows.** The newest tag is `v3.12.0` (2026-06-05). It is one version-bump commit on top of
`070778aa0`, which is on `master`'s history, so `v3.12.0` is not itself an ancestor but everything it shipped is:
its six 3.12 performance commits (the auto-derived `duckdb_threads`, the parallel duplicate-check scan and partition
reveal, the plugin `UNION ALL` view, the `sources.max` guidance) are still on `master`. These notes therefore cover
an upgrade from `v3.11.0` or `v3.12.0` alike. Neither tag has `docs/api/openapi-v1.json` or `public-api.txt`; the
3.x HTTP surface is the 32 routes of `ControlApi.registerRoutes`, and every one is still served under `/api/v1`
(the `/pipelines/{name}/…` run routes as `/runs/{name}/…`, below). Re-audited 2026-11-17 against `v3.12.0`'s tree.

**`@PublicApi(since = "4.0.0")` types are new in 4.0.0, not breaking changes.** About 220 marks carry `since =
"4.0.0"`; none of those elements was in a release, so they are additions in this MAJOR, and a change made to one
before the tag (a rename, a moved package, a new record component) needs no entry of its own. Where an entry
below names one, it is because the route, file or behaviour it serves changed for users of trunk builds. What
binds from 4.0.0 on is `platform/inspecto-api/public-api.txt` at the tag: within 4.x only additions are allowed.
The breaking Java entries are those for the 17 types marked in `v3.12.0` (the first two groups below).

**Breaking — surfaces that shipped in `v3.11.0` (M4 audit, 2026-11-15)**
*Found by diffing the `v3.11.0` route table, `-D` reads, `main()` classes and `@PublicApi` types against `master`.
Most of these commits are not marked `!`, so a commit-type scan misses them.*
- **Every business route is under `/api/v1`** and answers the v1 envelope (`{data, metadata, diagnostics}`; an
  error is `{error}`). The unversioned routes are removed, and `-Dapi.legacy.routes` / `-Dapi.legacy.sunset` no
  longer exist (`a2c2d983`, API-5, 2026-07-25). Only `/health`, `/ready` and `/metrics` stay unversioned. A 3.x
  client must change its base URL and read `data`.
- **The run routes moved from `/pipelines/{name}/…` to `/runs/{name}/…`, no alias** (`2c6f0abf`, an unmarked
  `refactor`, 2026-06-30): `trigger`, `pause`, `resume`, `commits`, `batches`, `files`, `lineage`, `quarantine`,
  `reprocess` and `report`. The 3.x pipeline list is `GET /runs` (now paged); `GET /pipelines` lists registered
  Pipeline definitions, a different shape. `reprocess` still takes `batchId` and `lineage` still `?batchId=`, but
  `batches` / `files` rows key on `consignment_id` (`8c4cb568`).
- **No bearer tokens:** `-Dcontrol.token`, `-Dassist.read.token` and `-Dassist.write.token` are removed with the
  core's authentication (`b64da585`, 2026-06-16). Basic has no authentication; Standard and Advanced authenticate
  through OIDC (`authMode: oidc`). A 3.x deployment that relied on the token must put Basic behind its own gateway
  or move to Standard.
- **The catalog speaks the new vocabulary, no alias** (`05f6c35d`, `a3180790`): `GET /catalog/graph` node kinds
  `SOURCE` → `STREAM`, `EVENT_TABLE` → `TABLE`, `TRANSFORMED_TABLE` → `DERIVED_TABLE`, `REFERENCE_TABLE` →
  `REFERENCE_DATASET`; edge kind `USES` → `CONSUMES`; the id token `source:` → `stream:`. A `kinds=` / `edgeKinds=`
  filter or a stored node id with an old value matches nothing.
- **Job Types are string ids** (`1826905b`, 2026-07-09): `GET /jobs` and run records report `type` in lower case
  (`"maintenance"`, was `"MAINTENANCE"`). **The `ingest` Job Type is removed** (`f787862a`, 2026-06-17): make a
  `type: ingest` Job an `active: true` Pipeline.
- **A Job's relative `dir` / `data_dir` / `backup_dir` / `archive` / `target_dir` resolves against the Space config
  root**, not the process working directory, and is refused where the two disagree (`a7ab607b`, 2026-09-16).
- **The assistant:** `POST /assist/{intent}` returns `confidence` as a number in [0, 1], not the string `"local"`
  (`0052240a`, 2026-06-05). Removed, no longer read: `-Dassist.enabled`, `-Dassist.profile`, `-Dassist.ollama.baseUrl`
  (`6b0f8418`). The model is set in the file named by `-Dassist.settings.file` ([agent admin
  guide](../agent/admin-guide-agent.md)), and its host must be on the Space's model endpoint allowlist (`7e113110`, below).
- **CLI:** the processor jar has no `Main-Class`, so `java -jar` on it no longer runs (`f13479dc`, 2026-10-08); run
  `com.gamma.inspector.CollectorProcessor` on the bundle's classpath, as the bundle's run scripts do. The URA File
  Management Suite (ura.sh / ura.bat — `search`, `copy`, `organize`, `copy-tars`, `extract`, `backup`,
  `prepare-inbox`, `create-schema`, `move-by-date`, `extract-unknown`, `extract-move`) is removed (`5863853d`,
  2026-11-07); Add data and Collectors replace it, `reprocess` is `POST /api/v1/runs/{name}/reprocess`, and
  `MainApp` keeps only `migrate-configs`.
- **Java, no alias** (`a3180790`): `com.gamma.inspector.SourceProcessor` → `CollectorProcessor`, <!-- vocab-allow: names the rename itself -->
  `MultiSourceProcessor` → `MultiCollectorProcessor`, `com.gamma.service.SourceService` → `CollectorService`. <!-- vocab-allow: names the rename itself -->
  `AssistAgent.init(SourceService)` → `init(ReadModel)` (`c23489da`); `AssistResult.confidence` `String` → <!-- vocab-allow: names the rename itself -->
  `double` (`0052240a`); `JobConfig.type()` returns `String`, not `JobType` (two deprecated `JobType`
  constructors remain) (`1826905b`).

**Breaking — more surfaces that shipped in `v3.12.0` (M4 re-audit, 2026-11-17)**
*Found by diffing `v3.12.0`'s tree: its `-D` reads, Job task parameters, `@PublicApi` signatures, `pom.xml` and bundle
scripts. None of these commits is marked `!` except `757add5e`.*
- **Java 27.** The core compiles at `maven.compiler.release=27`; 3.x targeted Java 24 (`ec49b7bd`, an unmarked commit
  titled as an Edition rename, 2026-09-18). Bundles embed a Java 27 runtime unless `package.ps1 -NoRuntime` is used, and
  then the host must provide Java 27 or later. An embedder must run on Java 27; a plugin jar built for an older
  release still loads.
- **DuckDB 2.0** (`2.0.0.0`; 3.x shipped `1.5.2.1`; `35e8def1`, 2026-10-22, `DUCKDB-2-MIGRATION-1`). SQL a user wrote
  (a Dataset, a `sql.template` Job, a View, a calculated column) that uses the arrow lambda `x -> …` fails with a
  Binder error: write `lambda x: …`. A stored `a ± b` of two `DECIMAL(18,s)` columns is now `DECIMAL(19,s)`, which
  changes a sink's Parquet column type; `CAST` it back where the stored type matters. Values are unchanged. Parquet
  files 1.5 wrote read the same on 2.0. Detail: [DuckDB integration](../engine/spec-duckdb.md).
- **Job task paths are jailed** (`86c0306f`, 2026-08-14, unmarked; `b1556b75`). A path a Job task writes or deletes
  under — the `cleanup` task's `dir` and `archive_dir`, `backup` / `backup_verify` / `restore` paths, `storage_report`
  `dir`, a report's `out_dir` — must lie under `-Dassist.safety.roots` or the Space's own folder or data root, or the
  Run fails. A 3.x `maintenance` Job whose `cleanup` `dir` is an absolute path elsewhere (`/mnt/…`) must have that
  root added to `-Dassist.safety.roots`. No Job path may name a `*.secrets` directory. Relative paths: see the
  config-root entry above.
- **The 3.x bundle layout.** `run.sh <adapter>` / `run.bat` moved to `bin/` and look for
  `spaces/*/config/<adapter>/*_pipeline.toon`, not `config/<adapter>/`, and the server launcher starts from `spaces/`.
  Move a 3.x flat install into a Space once with `java -cp … com.gamma.service.SpaceMigrator [--dry-run] <configDir>`
  ([configuration reference](../config/admin-guide-configuration-reference.md), *Migrating a flat deployment*); a
  config that names a schema by an absolute path keeps pointing at the old place. Starting `ControlApi` with config
  arguments and no `-Dspaces.root` still runs the 3.x single-tenant layout unchanged.
- **The status ledgers are served from a database by default** (`757add5e`, 2026-08-31). `-Dstatus.backend` defaults
  to `db`, not `file`: the status, batches and lineage reads come from a DuckDB projection (`inspecto_status_*`),
  refreshed after every Run, and `/db/catalog` lists it as an `ops:` group. The CSV ledgers stay the durable record.
  `-Dstatus.backend=file` keeps the 3.x behaviour.
- **`POST /validate`** (`57432516`, 2026-09-23; `ccda98a8`). A `configPath` outside the allowed roots is **403** and a
  path that is not a file **404**; a relative one resolves against the write root, not the working directory; a
  config that does not load is **422**. A draft (`type` + `config`) always runs the full save gate: the `safety` flag
  is ignored and `safetyChecked` is always `true`, so a draft 3.x reported clean can now carry findings.
- **Java, the other `v3.12.0` `@PublicApi` types.** `ControlApi(SourceService, int, String)` and `(SourceService, int, <!-- vocab-allow: names the 3.x type -->
  Tokens)` → `ControlApi(CollectorService, int)` and `ControlApi(SpaceManager, int)`; the nested `Tokens` and `Scope`
  are removed. `ReportService(SourceService)` → `ReportService(ReadModel)`; <!-- vocab-allow: names the 3.x type -->
  `ReportService.BatchAuditReport` → `ConsignmentAuditReport`, same components, so the `/runs/{name}/report` JSON is
  unchanged. `JobService` and
  `EnrichmentService` constructors take a `ConsignmentEventBus`, not a `BatchEventBus` (`ff33246a`). Records gained
  components, so their canonical constructors changed while every 3.x accessor stays: `JobService.JobView` (`onSignal`
  after `onPipeline`, five at the end) and `PipelineConfig.Dirs`, `.Processing`, `.CsvSettings`, `.Output` and
  `.Schemas`. Unchanged: the plugin SPI `StreamingFileIngester`, `RecordSink` and `IngestResult`, `AssistRequest`,
  `Diagnosis`, `DbStatusStore` (members added only) and `EnrichmentService.JobView`.

**Deprecated, still read — 3.x config keys**
- `processing.batch.{max_files, max_bytes}` → `collector.consignment.{max_files, max_bytes, order}` (`6478737b`,
  2026-09-02). The old block is still read when the new one is absent, and a save through the editor moves it.

**Security fixes — behaviour an integrator can see (SEC review, 2026-09-24)**
- `POST /public/delivery-status/{adapterId}` refuses a body over **256 KiB** with **413
  `PAYLOAD_TOO_LARGE`** (a new, additive `ErrorCode`) and is throttled per caller IP (burst 60, then 5/s)
  with **429 `RATE_LIMITED`**. Providers retry a 429; a batch over 256 KiB must be split provider-side.
- **Breaking (Standard/Advanced):** `DELETE /notifications/{id}` and `PUT /notifications/preferences`
  now require **`canAdminister`** (403 `PERMISSION_DENIED` otherwise). They were exempt as per-caller, but
  the feed archive and the preference grid are one shared state per Space. Basic (no authenticator) is
  unchanged.
- **Behaviour change (every edition, 2026-09-25):** notification **read state is per caller**. `POST
  /notifications/{id}/read` and `POST /notifications/read-all` stay open to any authenticated caller (no
  capability) and mark only the caller's own state; `GET /notifications`, the `/notifications/stream` frames
  and `/notifications/unread-count` report `state` / `readAt` as the caller sees them, plus a new additive
  boolean **`read`**. New route **`POST /notifications/{id}/unread`** (404 unknown id). A client that read
  another user's mark through the shared `state` no longer can. On Basic the caller is `appUser` (the
  honour-system `X-Actor` it also took is removed, below).
- **Behaviour change (Standard/Advanced, 2026-09-25 — ses-sns §7):** notification **preferences are
  per caller**. `PUT /notifications/preferences` is open to any authenticated caller again and writes only
  that caller's override (a `null` cell resets it); the deployment default moved to the **new `PUT
  /notifications/preferences/default` (`canAdminister`)** and new `GET /notifications/preferences/default`.
  `GET /notifications/preferences` returns the caller's effective grid, and every grid row gains additive
  `source` (`inherited` | `overridden`) and `editable` maps per channel. An admin client that edited the
  shared grid through `PUT /notifications/preferences` must move to `/default`. The feed, SSE frames and
  unread count hide a category the caller turned in-app off for. Basic is unchanged (the PUT writes the
  single grid).
- **Breaking (Standard/Advanced, 2026-09-26):** `POST /objects/{id}/ack`, `/resolve`, `/transition` and
  `/assign` require the new **`canWorkIncidents`** instead of `canAdminister` (403 `PERMISSION_DENIED`
  otherwise). The seeded `operations`, `support`, `power` and `admin` roles hold it; an **authored
  `roles.toon`** that gave a role `canAdminister` so it could work Incidents must add `canWorkIncidents`.
  With an authenticated Subject those four routes record the Subject as the actor and ignore a body `actor`.
  Merge, split, `PATCH /objects/{id}` and the Case-Rule evaluate are unchanged (`canAdminister`). Basic
  (no authenticator) is unchanged.
- **Breaking (every edition):** `X-Forwarded-For` is **ignored by default** — the client IP recorded in the
  audit trail and used as the unauthenticated rate-limit key is the socket peer unless the peer is listed
  in the new `-Dcontrol.trustedProxies` (IPs/CIDRs), and then the right-most untrusted hop wins, not the
  first. A deployment behind a proxy must list it, or it audits and throttles every caller as the proxy.
  An unparseable entry fails the boot.
- **Breaking (Standard/Advanced) — tighter route gates, all unmarked `fix:` commits:** `POST /alerts/evaluate`,
  `/expectations/evaluate` and `/expectations/{id}/evaluate` need `canOperateRuns`; `POST /connections/test`,
  `/connections/{id}/test` and `/connections/{id}/probe` need `canOnboardConnections` (`ad29e683`, 2026-09-17);
  `POST /enrichment/preview` needs `canAuthorWorkbench` (`d4f4ced7`); `POST /db/query` and `GET /db/table` on an
  operational (`ops:*`) group need `canAuthorWorkbench` (`718f4003`); `POST /spaces` and `POST /spaces/import` need
  `canAdminister`, even with no Space hosted (`b552029f`, `62905386`); `POST /assist/settings` needs
  `canAdminister`, not `canAuthorWorkbench` (`7e113110`). Each answers 403 `PERMISSION_DENIED` otherwise. Basic
  is unchanged.
- **Breaking (every edition, 2026-09-30, `7e113110`):** the assistant calls only a model host named in the Space's
  `egress.toon` `models` list (`PUT /settings/egress`, `canAdminister`). The list is empty by default, so even a
  local Ollama must be listed as `localhost` or `127.0.0.1`; a refused endpoint falls back to the offline stub and
  is logged.
- **Breaking (2026-09-27, `558b248d`):** a `POST /bundle/import` or `POST /import` bundle carrying a Requirement is
  refused whole (403); create Requirements through `POST /requirements`. Writes to `/components/requirement`,
  `/components/access-profile` and `/components/access-catalog` are 403 for every caller, and
  `/components/alert-rule` needs `canAuthorAlertRules`.
- **Behaviour change (2026-09-17, `94db59b4`):** with `notify.smtp.starttls` set to `true`, a relay that does not
  offer STARTTLS fails the send instead of falling back to plain text.

**Breaking — Metadata Bundle Pipelines (2026-09-25, BUNDLE-AUTHORED-PIPELINE-STORE-1 option B)**
- New bundle kind **`pipeline`**: a REGISTERED `*_pipeline.toon`. `POST /bundle/export` returns its content
  as the editable graph (the `GET …/graph/raw` shape) plus `closure: {manifest, files}` — the
  `GET /pipelines/{name}/bundle` manifest (without `exported_at`) and every entry it names as UTF-8 text
  (`{base64}` for non-UTF-8 bytes). `POST /bundle/import` writes `closure` alone through the
  `POST /pipelines/import` core (same gates, same `<write-root>/<id>/` layout, always `active: false`,
  registered); an existing id is skipped unless `actions` says `overwrite`; a refusal fails that item.
  [metadata-bundle](../processor/spec-metadata-bundle.md) · [editable round-trip §21](../elt/spec-pipeline-model.md).
- **Breaking:** `POST /bundle/import` **422s** (`CONFIG_VALIDATION_FAILED`, before any write) when the
  envelope carries any **`authored-pipeline`** item — the kind is export-only (W5: grandfathered
  `PipelineStore` graphs are never newly written). Bundles saved by the SPA before this change carry their
  Pipelines as `authored-pipeline` and must be re-exported (they now come out as `pipeline`).
- **Behaviour change:** `POST /pipelines/authored/{id}/run` (Run to here) runs the REGISTERED pipeline's
  graph, and `…/dry-run` prefers it, even when a `PipelineStore` graph has the same id; the store answers a
  dry-run only for an unregistered, grandfathered id.

**Breaking — Access Policy save-time guards (2026-09-25, policy-authoring UX S1–S4, operator D1)**
- `PUT /access/policies` now **422s** (`CONFIG_VALIDATION_FAILED`, the message naming the policy and a
  bracketed check code): an unknown key on a policy or its `target` (`[unknown-key]`; both `resourceKinds`
  and `resource_kinds` = `[ambiguous-key]`); a `when` reference the engine never binds (`[unknown-ref]` —
  a root other than `subject.`/`env.`/`resource.`, `subject.<k>` outside `id, capabilities, dataScopes,
  roles` + the `roles.toon identity.attributeClaims` allowlist, `env.<k>` outside `action, route, space`);
  an untargeted `deny` with no `when` (`[deny-everything]`); and, on Advanced, a draft that would deny the
  saver's own next `PUT /access/policies` (`[would-lock-out]` — nothing is written).
- ⚠ **Upgrade:** the same checks run on the on-disk `access-policies.toon`. A doc that loaded before with a
  stray key (`wen:`), or that references a claim not allowlisted in `roles.toon`, is now **unreadable** —
  on Advanced every authenticated request is denied (fail-closed) until it is fixed on disk. The
  `GET /access/policies` `error` names the policy and the check for `canConfigureAccess` holders. Fix the
  doc (or allowlist the claim) before upgrading.
- `PUT /access/roles` 422s a claim-allowlist change that would make the policies doc unreadable.
- `GET`/`PUT /access/policies` gain additive `warnings: [{policy, code, message}]` (`unknown-capability`,
  `unknown-role`, `unknown-resource-kind`, `resource-ref-at-route-level`, `seed-override`) and
  `resourceKinds` (the kind vocabulary). A client that round-trips GET rows into a PUT must strip the
  read-only `source` key — it is now an unknown key.
- New route **`POST /access/policies/preview`** (gated `canConfigureAccess`): the draft's role × action
  (× kind) impact, before → after. New SPI default `AccessDecider.simulate(…)`; the `@PublicApi`
  record `AccessPolicies.Doc` gains an `error` component (the unreadable reason), and `AccessPolicies`
  gains `RESOURCE_KINDS` and the `Warning` record.

**Error-code corrections (`ERRORCODE-DEFAULTED-1`, 2026-09-25)**
- `POST /rule-templates/{id}/simulate` with no write root bound: the 503's `errorCode` moves from the 503
  default `CAPABILITY_UNAVAILABLE` to **`CONTROL_PLANE_READ_ONLY`**, matching every other
  write-root refusal. Status and message are unchanged; a client keying on the old code must switch.
- The per-Space data-source bundle routes (`GET /datasources`, `GET /datasources/{ds}/export`,
  `GET /export`, `POST /import`, `POST /import/preview`) with no write root bound: the 503's `errorCode`
  moves from `CAPABILITY_UNAVAILABLE` to **`CONTROL_PLANE_READ_ONLY`**, for the same reason. Status and
  message are unchanged.

**Breaking — Job Packs need a SHA-256 allowlist (2026-09-25, parser-plugins trust design slice P1)**
- With `-Djobs.packs.dir` set, a pack jar loads **only** when the SHA-256 of its bytes is listed in the
  operator-owned file named by the new **`-Djobs.packs.allowlist`** (one `<sha256>  <file>  [note]` line per
  jar; `sha256sum` output works as-is). **No allowlist configured ⇒ every jar is refused**, Job Packs
  included; today's unconditional load is gone, with no compatibility switch. An unreadable file or one
  malformed line refuses every jar too. Refusals emit `job.pack.rejected` with a `not trusted: …` cause.
- The allowlist is re-read on every rescan: add a line + `POST /jobs/packs/rescan` approves; remove it +
  rescan **unloads** a loaded pack. **Boot fails** (the Space does not load) if the allowlist sits inside the
  packs dir or under `assist.write.root`, `spaces.root` or an `assist.safety.roots` root.
- `GET /jobs/packs` now also lists refused jars as `{file, hash, state: "rejected", cause}` beside the
  `state: "loaded"` rows. A client that treated every row as loaded must filter on `state`.
- **Staged copies moved out of the system temp dir (slice P0).** They live under
  `<auditDir>/job-packs-staging`, or the new **`-Djobs.packs.stagingDir`**. A staging dir inside the packs
  dir **fails boot**.

**Additive — the Decode Profile (2026-09-25, parser-plugins trust design C1–C4)**
- New Pipeline key **`parsing.asn1.profile_file`**: a `.toon` satellite holding one `asn1:` block that
  several Pipelines share. Pipeline keys win; `segments` is replaced whole. `GET /parsers` serves the field
  in the ASN.1 `grammarSchema`, `POST /parsers/asn1/preview` honours it (403 on an escaping ref), and a
  Pipeline bundle carries the profile as its own satellite. See
  [parser-plugins.md](../elt/spec-parsing-decode-profile.md), *Decode Profile*.

**Parsers from Job Packs (2026-09-25, parser-plugins trust design slices P2–P4)**
- A `ParserPlugin` in an allowlisted Job Pack now registers (the fifth pack kind). It appears in
  `GET /parsers` after every built-in and classpath parser, and leaves it when the pack unloads or its hash
  is revoked. A pack whose parser id collides with a built-in, classpath or other pack's parser, or names
  an ingester class another parser names, is refused whole (`job.pack.rejected`).
- `GET /parsers` rows gain an additive **`source`**: `builtin`, `classpath` or `pack:<jar filename>`.
- A Pipeline naming a pack parser's ingester now ingests (the class resolves through the pack's loader,
  and the pack stays pinned until the batch ends). A Pipeline whose ingester class nothing loaded provides
  now fails its Run with `streaming ingester <fqcn> is not on the classpath and no loaded parser names it…`
  instead of `Cannot instantiate streaming ingester` wrapping a `ClassNotFoundException`.
- **New refusal (Standard/Advanced):** `POST /parsers/{id}/preview` for a parser a Job Pack
  contributed returns **403 `PERMISSION_DENIED`** without **`canAuthorWorkbench`**. Built-in and classpath
  parsers stay open; Basic (no authenticator) is unchanged. `CapabilityManifest` records the route under
  the new exemption category `provenance-gated`.

**Whole-pipeline dry run (2026-09-20, `PIPELINE-DRYRUN-1` step 5)**
- `POST /runs/{name}/trigger?dryRun=true` runs a pipeline and **lands nothing** — no outputs, no audit or
  commit-log rows, no provenance row, no markers, no backup/quarantine moves — logging each suppressed
  mutation as `dry run: would …`. It **implies** `?skipPostAction=true`, which remains the separate,
  narrower capability (fetch without acking; the ingest write still happens for real). The v1 response
  body gains a `dryRun` field alongside `skipPostAction`.
- `com.gamma.etl.ConsignmentEvent` gains a **`boolean dryRun`** component (11 in total then; 12 since
  `outputFiles` was added). Additive, on a
  type whose `since = "4.0.0"` has never shipped (absent from `v3.11.0`), so no bump of its own. A
  published event that carries it MUST be honoured or refused by every consumer; `JobService`'s
  `on_pipeline` and `on_signal` firings now inherit it instead of hardcoding `false`, which narrowly
  overturns the old *"cron/event/signal fires are always real"* rule — a fire is dry exactly when the
  batch that caused it was simulated.
- ⚠ Before 2026-09-20 `?dryRun=true` meant only *skip the post-action*. That meaning was renamed
  `?skipPostAction` (`9e101cdc`) the same day `?dryRun` took the one above (`5af44d2f`), so a caller that sent
  `?dryRun=true` to poll without acking now lands nothing and must send `?skipPostAction=true`.

**Breaking — the Assistant's RCA record is a Triage Run, not a Case (2026-09-24, `GLOSSARY-CASE-1`)**
- Routes renamed, **no alias**: `GET /agent/cases` → `GET /agent/triage-runs` (list key `cases` →
  `triageRuns`), `GET /agent/cases/{id}` → `/agent/triage-runs/{id}`, `GET /agent/cases/{id}/similar` →
  `/agent/triage-runs/{id}/similar`, `POST /agent/cases/{id}/feedback` → `/agent/triage-runs/{id}/feedback`
  (still `canAdminister`). Feedback views key `caseId` → `triageRunId`; the 404 reads
  `unknown triage run: '<id>'`. None of the four existed in `v3.11.0`.
- Java: package `com.gamma.intelligence.investigation` → `com.gamma.intelligence.triage`; `Case` →
  `TriageRun`, `CaseStore` → `TriageRunStore`, `CaseSimilarity` → `TriageRunSimilarity`. SPI
  `IntelligenceAgent`: `recentCases` / `caseById` / `similarCases` / `recordCaseFeedback` /
  `recentCaseFeedback` → `recentTriageRuns` / `triageRunById` / `similarTriageRuns` /
  `recordTriageRunFeedback` / `recentTriageRunFeedback`.
- On disk: `<assist.write.root>/agent/cases.jsonl` → `agent/triage-runs.jsonl`. The old file is **not
  read** (the Triage Run ring starts empty), and a `feedback.jsonl` whose rows key on `caseId` is ignored
  with a load warning and overwritten on the next rating.

**Breaking — route registration (2026-09-16, `ROUTE-UNGATED-DEFAULT-1` step 3)**
- A **mutating route** (`POST`/`PUT`/`PATCH`/`DELETE`) that declares neither a capability
  (`ApiContext.withCapability`) nor a recorded `CapabilityManifest` exemption now **fails the server's
  boot**, naming the route. ⚠ This is breaking for **route modules discovered from other jars**
  (EDG-01 cell 3a): a module that registered an undeclared mutating route used to run it open to every
  authenticated caller, and now refuses to start. ⛔ There is deliberately **no warn-only switch** — a
  control with an off switch is not a control. Reads are unaffected and stay open by policy.
- `ApiContext.withCapability` returns a marked `Gated` handler rather than a bare lambda. Call sites are
  unchanged; anything that *wrapped or unwrapped* the returned handler by identity is not.

**Breaking — config writes refuse unknown keys (2026-09-16, `DUCKLE-C3-DEAD-PROPERTY-1`)**
- `POST /config/write` and `PATCH /config/patch` now return **422** for a key no component reads, with the
  stable code `ERR_UNKNOWN_CONFIG_KEY` and a near-name suggestion when one is close (no suggestion when
  nothing is). ⚠ Breaking for configs that **previously saved silently**: `version:` and `source:` — the two
  keys `pipeline-config-keys.md` itself lists under *"appear in docs, read by nothing"* — are now refused,
  and `source:` in particular looked like it still worked. Turning that silent loss into a refusal is the
  point of the change; see `module/config/spec-config-gotchas.md` for the silent-config-loss defects it closes.
- Keys prefixed `x-` are accepted and round-trip untouched — that is the escape hatch.
- ⛔ Granularity is the **block**, not the leaf: `dirs`, `collector`, `parsing`, `output` and `reference` are
  accepted whole, because they carry engine-read keys with no `FieldSpec`. For `pipeline`, only top-level
  and `processing.*` are censused.
- **Extended to the `alert` config type (2026-09-16).** ⚠ Also breaking, in the same way: a dead key inside
  an `alert:` block — say `thresold:` for `threshold:` — now returns **422** where it previously saved
  silently, which for an Alert Rule meant a threshold that never took effect. The census descends one level
  into `alert:` (the whole file is that one block, so a top-level-only census would catch nothing), and the
  three parser-only leaves `alert.dataset`, `alert.measure`, `alert.when` are accepted — refusing those
  would have broken every BI-5 measure rule authored today.
- **Extended to the `meta` config type (2026-09-16).** ⚠ Also breaking: a key outside
  `name` / `version` / `tables` / `kpis` / `reports` / `domain` in a `*_meta.toon` now returns **422**.
  ⛔ The census stops at the **top level** for this type — author-chosen KPI, table and report names one
  level down are never checked, because `SemanticModel.load` iterates them by `entrySet()` and they are an
  unbounded namespace. Both committed `*_meta.toon` files pass unchanged.
- Of the remaining six config types, four gained a check later: `enrichment` has a census in
  `AcceptedConfigKeys`, and `widget`, `dashboard` and `expectation` are refused at the top level in
  `ComponentRoutes.refuseUnknownComponentKeys` (checked in code 2026-11-14). `job` and `schema` are still
  fail-open by omission. ⛔ They cannot simply be switched on: both have
  confirmed undeclared-but-engine-read keys, and `job` funnels any unrecognised key into an open `params`
  bag. ⚠ `widget` and `dashboard` are a different case again — they never reach `/config/write` at all
  (the UI saves them through `POST`/`PUT /components/{kind}`), so a census here would change nothing.
  See [config safety](../config/spec-config-safety.md) §"The accepted-key census" for the per-type blockers.
- Unaffected: `PipelineGraphRoutes` (needs a migration pass first) and `RecipeCompiler`, where the intended
  run-time WARNING has nowhere to go until a non-fatal diagnostic channel exists.
- **Extended to Expectations, Notification Rules and Channels (2026-10-07, `3fc16cfb`, unmarked):** an unmodelled
  key other than an `x-` key now returns **422**; it used to be dropped behind a 200.

**Breaking — one masking rule (2026-11-14, `MASKING-TWO-VOCABULARIES-1`)**
- A column is masked for a viewer who cannot reveal if its class is `MSISDN`, `IMSI`, `ACCOUNT`, `PII`, a class a
  masked Entity Type claims, or a masked Entity Type's id — at every reader. So a BI result in the default `typed` mode
  now masks `PII` columns, Risk Score evidence masks `WALLET` / `IMEI` / `ID_DOCUMENT` columns, and an Entity Type's
  `masked: false` no longer unmasks one of the four sensitive classes. `maskingMode: none` is unchanged.
- `publish.postgres` drops or refuses those columns unless listed in `include_sensitive`; four-eyes approvals over
  such Datasets must be given again.
- Detail: [config safety](../config/spec-config-safety.md), "One masking rule".

**Breaking — an entitlement withholds a left-out module's edition features (2026-11-14, `ENTITLEMENT-UNLISTED-FEATURES-1`)**
- With a signed entitlement installed, a module it leaves out now also withholds its edition features: a save that
  uses an archive or DuckLake sink (`inspecto-backup`), a `mask` Step (`inspecto-policy`) or an Alert Rule
  (`inspecto-ops`) returns **422** `ERR_EDITION_FEATURE`, "not in this installation's entitlement". They saved with
  200 before. ⚠ A Standard entitlement with none of the Incidents, Workflow & SLA and LA Investigations Add-ons
  therefore has no Alert Rules (decided by the assistant under the operator's delegation, operator-confirmed
  2026-11-14). Without an entitlement file nothing is refused; `/modules` and Settings > Modules now list
  these ids among the module's features.
- The refusal of a feature whose providing modules are all left out now names the entitlement instead of an edition.
- Detail: [entitlements](../../layer/platform/admin-guide-entitlements.md).

**Breaking — a save refuses an unknown column classification (2026-11-14, `CLASSIFICATION-UNMASKED-CLASS-1`)**
- A `classification` on a Schema field, a Dataset column or a Step's output column must be `MSISDN`, `IMSI`,
  `ACCOUNT`, `PII`, a class an Entity Type of the Space claims, `INTERNAL`, or empty. Any other word is refused with
  `ERR_UNKNOWN_CLASSIFICATION` (a finding code on the config routes; in the message of a
  `CONFIG_VALIDATION_FAILED` 422 on the component routes; a `failed` item in a bundle import's 200) on `POST /config/write`, `POST /config/patch`,
  `POST`/`PUT /components/dataset` and `/components/schema`, a bundle import and the onboarding column route. A Dataset or schema component keeps a class it already stores; only a new one is refused.
- ⚠ Breaking for a config that **previously saved silently**: `CONFIDENTIAL` (and any other invented label)
  masked nothing and is now refused. Reclassify it: an identifier to the class that masks it, anything else to
  `INTERNAL`. A file already on disk still loads; it is refused the next time it is saved.
- Detail: [config safety](../config/spec-config-safety.md), "The classification vocabulary".

**Breaking — `flow` → `pipeline` on the wire (vocabulary Tier 3 cutover, 2026-09-24)**
- The Tier 3 dual-emit is **over**: JSON responses carry only the canonical key. `GET /views` and
  `GET /views/{store}` summaries drop `flow` (keep `pipeline`); the `/lineage` `downstream[]` entries drop
  `flow`; the combined topology `GET /pipelines/combined` (`PipelineProjection.combined`) drops the top-level `flows` array
  (keep `pipelines`) and each node's / edge's `flow` field (keep `pipeline`).
- Persisted view definitions (`<write-root>/views/*_view.toon`) are written with `pipeline:` only, and
  **`flow:` is no longer read** — a `flow`-only file resolves no producing pipeline. No such file existed
  (`spaces/`, `examples/`), and the one file carrying both keys was migrated.
- `com.gamma.pipeline.ViewDefinition` (`@PublicApi(since = "4.0.0")`): record component **`flow` →
  `pipeline`** (accessor `flow()` → `pipeline()`). No alias.
- The `pipeline_author` agent tool: argument **`flow` → `pipeline`**, result key `flow` → `pipeline`, and the
  whole-graph finding `fieldPath` `flow` → `pipeline`. The one caller (the pipeline editor) moved in the
  same change; `flow` is not accepted.
- ⚠ **Not in this cutover** (still dual-read, separate debt): the `flow:` key of a `type: pipeline`
  `*_job.toon` and the `?flow=` query param.
