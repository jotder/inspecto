---
type: Reference
title: API stability policy
description: What the `@PublicApi` marker means and does not mean, the release baseline (nothing after 3.x has shipped), and the running draft of release notes for the pending MAJOR.
resource: inspecto-api/src/main/java/com/gamma/api/PublicApi.java
tags: [api, stability, publicapi, semver, release]
timestamp: 2026-07-16T00:00:00Z
---

# API Stability Policy
> **Deep reference — the detail tier.** Start at [Control-plane section index](index.md) for the summary; this page is the long form it points to. *(Moved from the retired root-level `api-stability.md` (docs consolidation, 2026-07-16).)*

> Part of the [Inspecto](../../../../inspecto/README.md) documentation.

The framework distinguishes its **stable public API** from internal implementation.
Types, methods, and constructors marked [`@com.gamma.api.PublicApi`](../../../../inspecto-api/src/main/java/com/gamma/api/PublicApi.java)
are the surface external code may depend on; everything else is internal and may
change in any release.

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
([BRANCHING.md](../../../BRANCHING.md) §1). Trunk is `4.0.0-SNAPSHOT`.

Two consequences, and they govern every relocation and rename question on this codebase:

1. **`since()` is informational, and a value above 3.x means "will become public API in 4.0.0" —
   not "has been public API since".** Every `@PublicApi` element introduced after 3.x therefore
   carries `since = "4.0.0"`. Values of 4.1.0 … 5.8.0 were previously written against versions that
   never existed; they were corrected to `4.0.0` in one sweep (200 sites).
2. **An element whose `since` is `4.0.0` has never been published in any release, so it may still be
   moved, renamed, or changed freely** — the stability promise binds *within a released major*, and
   4.0.0 is not released. ⛔ **`@PublicApi` alone does not make a change breaking.** Check whether the
   element exists in the newest ancestor release before treating a relocation as an API break:
   `git ls-tree -r --name-only v3.11.0 | grep -E '/<Class>\.java$'`.
   This premise (`@PublicApi` ⇒ breaking ⇒ bump) has now been written down and refuted **three**
   times — the Source→Collector rename ([GLOSSARY](../../../GLOSSARY.md) §13), the <!-- vocab-allow: names the Source→Collector rename itself -->
   `ConsignmentProcessor` SPI widening ([BACKLOG](../../../BACKLOG.md) §4), and the architecture
   plan's Phase C cycle cuts. It is *inherited*, not measured. Measure it.

## Release notes — the pending MAJOR (draft, kept current until the tag is cut)

Everything below is already on `master` and is either a `feat!:` or an operator-visible behaviour change
relative to `v3.11.0`, the newest ancestor release. The list is the BACKLOG §2 "Release notes for the next
MAJOR" row, moved here so it accrues in one place; add a line in the same commit as any further `feat!:`.
The GitHub release is cut by `.github/workflows/release.yml` with `--generate-notes`; paste this section
above the generated commit list.

**Security fixes — behaviour an integrator can see (SEC review, 2026-09-24)**
- `POST /public/delivery-status/{adapterId}` refuses a body over **256 KiB** with **413
  `PAYLOAD_TOO_LARGE`** (a new, additive `ErrorCode`) and is throttled per caller IP (burst 60, then 5/s)
  with **429 `RATE_LIMITED`**. Providers retry a 429; a batch over 256 KiB must be split provider-side.
- **Breaking (Professional/Enterprise):** `DELETE /notifications/{id}` and `PUT /notifications/preferences`
  now require **`canAdminister`** (403 `PERMISSION_DENIED` otherwise). They were exempt as per-caller, but
  the feed archive and the preference grid are one shared state per Space. Personal (no authenticator) is
  unchanged.
- **Behaviour change (every edition, 2026-09-25):** notification **read state is per caller**. `POST
  /notifications/{id}/read` and `POST /notifications/read-all` stay open to any authenticated caller (no
  capability) and mark only the caller's own state; `GET /notifications`, the `/notifications/stream` frames
  and `/notifications/unread-count` report `state` / `readAt` as the caller sees them, plus a new additive
  boolean **`read`**. New route **`POST /notifications/{id}/unread`** (404 unknown id). A client that read
  another user's mark through the shared `state` no longer can. On Personal the caller is `appUser` (or the
  honour-system `X-Actor`).
- **Breaking (every edition):** `X-Forwarded-For` is **ignored by default** — the client IP recorded in the
  audit trail and used as the unauthenticated rate-limit key is the socket peer unless the peer is listed
  in the new `-Dcontrol.trustedProxies` (IPs/CIDRs), and then the right-most untrusted hop wins, not the
  first. A deployment behind a proxy must list it, or it audits and throttles every caller as the proxy.
  An unparseable entry fails the boot.

**Error-code corrections (`ERRORCODE-DEFAULTED-1`, 2026-09-25)**
- `POST /rule-templates/{id}/simulate` with no write root bound now answers 503 with `errorCode`
  **`CONTROL_PLANE_READ_ONLY`** (was the 503 default `CAPABILITY_UNAVAILABLE`), matching every other
  write-root refusal. Status and message are unchanged; a client keying on the old code must switch.

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

**Parsers from Job Packs (2026-09-25, parser-plugins trust design slices P2–P4)**
- A `ParserPlugin` in an allowlisted Job Pack now registers (the fifth pack kind). It appears in
  `GET /parsers` after every built-in and classpath parser, and leaves it when the pack unloads or its hash
  is revoked. A pack whose parser id collides with a built-in, classpath or other pack's parser, or names
  an ingester class another parser names, is refused whole (`job.pack.rejected`).
- `GET /parsers` rows gain an additive **`source`**: `builtin`, `classpath` or `pack:<jar filename>`.

**Whole-pipeline dry run (2026-09-20, `PIPELINE-DRYRUN-1` step 5)**
- `POST /runs/{name}/trigger?dryRun=true` runs a pipeline and **lands nothing** — no outputs, no audit or
  commit-log rows, no provenance row, no markers, no backup/quarantine moves — logging each suppressed
  mutation as `dry run: would …`. It **implies** `?skipPostAction=true`, which remains the separate,
  narrower capability (fetch without acking; the ingest write still happens for real). The v1 response
  body gains a `dryRun` field alongside `skipPostAction`.
- `com.gamma.etl.ConsignmentEvent` gains a **`boolean dryRun`** component (11 in total). Additive, on a
  type whose `since = "4.0.0"` has never shipped (absent from `v3.11.0`), so no bump of its own. A
  published event that carries it MUST be honoured or refused by every consumer; `JobService`'s
  `on_pipeline` and `on_signal` firings now inherit it instead of hardcoding `false`, which narrowly
  overturns the old *"cron/event/signal fires are always real"* rule — a fire is dry exactly when the
  batch that caused it was simulated.

**Breaking — the Assistant's RCA record is a Triage Run, not a Case (2026-09-24, `GLOSSARY-CASE-1`)**
- Routes renamed, **no alias**: `GET /agent/cases` → `GET /agent/triage-runs` (list key `cases` →
  `triageRuns`), `GET /agent/cases/{id}` → `/agent/triage-runs/{id}`, `GET /agent/cases/{id}/similar` →
  `/agent/triage-runs/{id}/similar`, `POST /agent/cases/{id}/feedback` → `/agent/triage-runs/{id}/feedback`
  (still `canAdminister`). Feedback views key on `triageRunId` (was `caseId`); the 404 reads
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
  point of the change; see `PROJECT_NOTES` for the silent-config-loss defects it closes.
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
- The remaining **six** config types (`enrichment`, `job`, `schema`, `expectation`, `widget`,
  `dashboard`) are still fail-open by omission. ⛔ They cannot simply be switched on: four of them have
  confirmed undeclared-but-engine-read keys, and `job` funnels any unrecognised key into an open `params`
  bag. ⚠ `widget` and `dashboard` are a different case again — they never reach `/config/write` at all
  (the UI saves them through `POST`/`PUT /components/{kind}`), so a census here would change nothing.
  See [config safety](../config/config-safety.md) §"The accepted-key census" for the per-type blockers.
- Unaffected: `PipelineGraphRoutes` (needs a migration pass first) and `RecipeCompiler`, where the intended
  run-time WARNING has nowhere to go until a non-fatal diagnostic channel exists.

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

**Breaking — configuration and CLI**
- `-Dauth.oidc.tokenEndpoint` is **required** under `authMode: oidc` (D15, 2026-07-25); there is no IdP
  vendor of record and `OidcTokenRelay` will not guess the endpoint.
- `transform.map` is **deleted** (`42fa41fe`, 2026-09-05): the projection slot is always a Record
  Transformer (`transform.sql` with `fields[]`). Stored `mapping.rules[]` stay readable; `MappingMigrator`
  rewrites them to `fields[]` (`--dry-run` first).
- Dedup is folded into acquisition (`61dc8280`): `transform.dedup.fingerprint` is gone; the marker-based
  `duplicate_check` and the windowed `dedup` ledger are the two surviving mechanisms.
- `$`-token evaluation in job parameters: an unknown or malformed token is **REJECTED** at save
  (`e8a8a755`); a literal dollar is written `$$` (`8504b782`).
- `consignment.outputs.backend` defaults to `duckdb`; `ReprocessCommand` refuses a batch younger than
  `min_age_days` (§6.2).
- A sink whose `partitions[].column` is blank **fails the sink branch** instead of writing unpartitioned.
- The pipeline TOON `source:` block is unchanged, but every Java/TS/API name says **Collector** <!-- vocab-allow: names the rename itself -->
  (GLOSSARY §13); `?flow=` query params are dual-read, `?pipeline=` is canonical.

- **Consignment rename, persisted surfaces (D-12, confirmed 2026-09-06):** `batch_id` DDL columns in `DbProvenanceStore` /
  `DbStatusStore` → `consignment_id` (versioned migration, `payload` literal rewritten); the `__batch_id` system column in
  output Parquet/CSV → `__consignment_id` (output-schema break, documented); the `.toon` key `batch_id` → `consignment_id`
  with the old key accepted on read for the major. All three ride this MAJOR, none earlier.

**Breaking — HTTP routes REMOVED (`RETIRE-HALVES-1`, 2026-09-14)**
- `GET /bi/datasets` is **gone**. Dataset listing is the generic component registry
  (`GET /components?type=dataset`), which is the only path any client ever used.
- The work-queue family is **gone**: `GET /queues`, `GET /queues/{id}`, `POST /queues`, the
  `*_queue.toon` config kind, and `QueueStore`/`QueueRouter`. Nothing ever called them.
- `POST /objects/{id}/watch`, `POST /objects/{id}/unwatch` and `GET /objects/{id}/watchers` are **gone**.
  ⚠ The `watchers` **attribute** survives — `POST /objects/{id}/merge` unions it — so the data is still
  there with no route to read it.
- `POST /objects/{id}/assign` and `POST /objects/{id}/split` **no longer accept a `queue` parameter**;
  `assign` now requires `assignee` and answers **400** without one (it previously accepted either).
- The **SLA escalation engine is retired**: the `*_escalation.toon` config kind and the policy applied on
  breach are deleted. A breach still stamps the Incident and emits `OBJECT_SLA_BREACH`, but no longer
  bumps severity or re-routes. `OBJECT_ESCALATED` is **emitted by nothing** (the `@PublicApi` constant
  stays, since stored events carry the type); its builtin notification rule is deleted.
- ⚠ **Provenance note:** these removals are commit `519673a7`, whose message describes only a whitepaper
  change — a concurrent session committed the staged tree under its own heading, so the commit is typed
  `docs:` and carries **no SemVer signal for a breaking change**. Tests followed in `ba51a27b`. This entry
  is the record; ⛔ do not conclude from `git log` alone that this MAJOR has no route removals.
- `GET /pipelines/step-types` is **gone** (`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`, 2026-09-24, `d5f6353be`),
  with `PipelineProjection.stepCatalog()` / `RECIPE_VERBS`, its `step-types.contract.json` and the
  `step-types` count guard. Its last reader was the Recipe view (removed in `6d3c68fa`); the palette's one
  source is `GET /pipelines/node-types` (+ `/pipelines/processor-catalog`). The recipe grammar itself is
  unchanged — `RecipeCompiler`'s verb switch is the vocabulary.

**Breaking — Java `@PublicApi` (binds only within a released major; none of this was published in 3.x)**
- Three store interfaces gained abstract methods for `incident_purge` (MNT-14, 2026-07-27).
- `com.gamma.ops.NoteTargets` → `com.gamma.ops.AnnotationKinds` (no alias). ⚠ Relocated again in EDG-01 cell 7 (2026-09-08) to **`com.gamma.objects.AnnotationKinds`** — it is core vocabulary with no store coupling, so it stayed in the mandatory build when `com.gamma.ops` became an optional module.
- Maven artifactIds `file-processor-*` → `inspecto-*` (2026-08-10); the deployment bundle name is
  unchanged — renaming it is a separate, unmade decision.

**Operator-visible behaviour**
- **New route (additive, 2026-09-25, EXECUTION-RESIDUALS X4):** `POST /runs/{name}/replay-rejects {file}`
  (`canOperateRuns`) replays ONE file's rejected records from its reject sidecar as a new Consignment,
  without re-ingesting the file's good records; a second replay of the same sidecar is **409**.
  [execution-lanes](../pipeline-graph/execution-lanes.md)
- **New routes (additive, 2026-09-25, X1 deferrals — the COMMIT retry affordance):**
  `GET /runs/{name}/retries[?limit=]` (open read) lists the files waiting on a bounded COMMIT retry, by
  poll-relative path, with `keepsRetryState: false` when the pipeline has no `dirs.status_dir` (no retry
  state at all — NOT the same answer as an empty list). `POST /runs/{name}/retries/retry-now {file}` and
  `POST /runs/{name}/retries/cancel {file}` (`canOperateRuns`) act on ONE file: retry-now clears the backoff
  **only — the attempt count is kept** (the response says so); cancel **quarantines the file now under the
  new reason `retry_cancelled`**. A file already quarantined, a pipeline with no retry state, or a pipeline
  mid-cycle is a **409** that says so; no record / not in the inbox is **404**.
  [execution-lanes](../pipeline-graph/execution-lanes.md)
- **New config block (additive, 2026-09-25, X1 deferral):** a Pipeline's `processing.retry:
  {max_attempts, initial_backoff, max_backoff}` overrides the `-Dingest.retry.*` COMMIT-retry globals per
  key; unset keys (and an absent block) inherit them, so existing configs behave exactly as before.
  `max_attempts` outside `[0, 1000]` or a negative/non-duration backoff is a **422** at `/config/write`.
  The `sink.persistent` node advertises `retry__max_attempts` / `retry__initial_backoff` /
  `retry__max_backoff` in `node-attributes.contract.json`.
  [execution-lanes](../pipeline-graph/execution-lanes.md)
- **Behaviour change — the reject sidecar's `raw_line` is now byte-exact** (2026-09-25): both CSV ingesters
  escape an embedded `"` RFC-4180-style (`""`) in `<errors>/<file>_errors.csv` instead of rewriting it to
  `'`, so `GET /runs/{name}/errors?file=` now shows the line's real quotes. A reader that split the column
  itself on `'` must use an RFC-4180 reader. Sidecars written before the change keep the apostrophes.
- `POST /spaces/{id}/import` (data-source / Space bundle) **no longer refuses a bundle that names a connection
  the target Space lacks** (operator, 2026-09-25). It was a **422** that registered nothing; now it is a
  **200** — every config needing the missing connection is written **switched off by its own switch**
  (a Pipeline's `active: false`, a job's `job.enabled: false`) and the response carries a new
  `connectionWarnings: [{connection, code: WARN_UNRESOLVED_CONNECTION, message: "connect X to enable …",
  disabled: [{kind, name, file}]}]`. `POST /spaces/{id}/import/preview` agrees: the finding is a **WARNING**
  (`valid` stays `true`) and the same `connectionWarnings` is returned. A client that treated the 422 as
  "nothing imported" must now read `connectionWarnings` instead.
  [editable round-trip §22](../pipeline-graph/editable-round-trip.md)
- `DELETE /spaces/{id}?purge=true` answers **409** when it would remove the **last Space directory on disk** (D4); deregister-only on the last Space stays allowed. *(This line stated the rule backwards — "409 unless `?purge=true`" — until 2026-09-08; `SpaceRoutes.java:120-127`.)*
- Full recomputes write a sibling `<pipeline>_<batchId>` table and supersede the old revision in the
  catalog; **nothing deletes the bytes** until a `retire_superseded` maintenance job is configured.
- Run artifacts carry `event_time_min` / `event_time_max` instead of `timeRange`.
- `mail.send` on a deployment with **no email channel configured** answers **`SKIPPED`**, not SUCCESS — it is
  inert, not green. With **no recipients** (a `to` that resolves entirely blank) it answers **FAILED**.
  *(This line said "no recipients logs 'SUCCESS, nothing sent'" until 2026-09-17, conflating the two cases and
  naming the status the `ad29e683` reversal removed; `MailSendJob.java` — `JobResult.skipped` / `JobResult.failed`.)*
- The token picker's preview is the server's evaluation, not a client-side guess.
- New default-on cap: `-Djobs.maxConcurrentRuns=4` (D11), editable under Settings ▸ Scheduler ▸ Resource caps, where
  DuckDB `memory_limit` is also served (`2GB` is the measured recommendation, **not a shipped default** — GAP-4;
  this line said "default-on caps … `memory_limit=2GB`" until 2026-09-08).
- The event store prunes by whole day partitions once an `event_prune` maintenance job exists (COMPLY-3);
  releases are SBOM'd and signed in CI only (COMPLY-1/2).

⛔ **The `consignment.process` contract stays FROZEN, including its correlation identifier** — plugins
already depend on it, so the token work must not change that shape as a side effect. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*

**Deferred to this same release by decision:** the token *runtime* model (D2 —
[`node-types.md`](../engine/node-types.md) § *The token model*), the ELT
Phase 6 deletion of the flat read path (Row 15; the `-Dingest.lane` flag ships, the deletion waits for the
verification minor), and X5's cross-lane StepInfo envelope.

## The public surface (as it stands for the pending 4.0.0; unchanged since 2.0.0 except where noted)

Two audiences depend on the framework from outside:

**Plugin authors** (implementing a custom ingester):

| Type | Since | Role |
|---|---|---|
| `com.gamma.etl.StreamingFileIngester` | 3.10.0 | **The** plugin ingester SPI — emit records into a sink; the framework owns tables/transform/write/lineage and picks union vs generation mode by file size. (Sole SPI since 3.11.0.) |
| `com.gamma.etl.RecordSink` | 3.10.0 | Framework-provided callback a `StreamingFileIngester` writes records into (`define`/`emit`/`reject`/`junk`) |
| `com.gamma.etl.IngestResult` | 1.0.0 | Row counts (CSV path); plugin counts flow through `RecordSink` |
| `com.gamma.etl.PipelineConfig` (+ nested records `Identity`, `Dirs`, `Processing`, `CsvSettings`, `Output`, `Schemas`, `DuckDbSettings`, `Chunking`) | 1.0.0 / records 2.0.0 (`DuckDbSettings`/`Chunking` 3.10.0) | Passed to `ingest(...)`; read for paths, settings, `ingesterConfig`; `Processing` adds `largeFileBytes`/`flushRecords` streaming controls (3.11.0) |

> **Removed in 3.11.0 (breaking):** the whole-file `com.gamma.etl.FileIngester` and its nested `Segment` (both since 1.3.0). The plugin SPI is unified on `StreamingFileIngester`; the framework now runs the same ingester in *union mode* (many small files → one transform/write) or *generation mode* (huge single files → bounded scratch), chosen per batch by `processing.streaming.large_file_bytes`. Port `FileIngester` plugins to `StreamingFileIngester` (see [plugins.md](../engine/plugins.md)). This is a **deliberate exception** to the within-major-version stability promise above, made to consolidate the plugin SPI before it had wide external adoption.

> **Pending major 4.0.0 — NOT YET RELEASED (the agent-module reshape):** the **core ETL `@PublicApi` surface above is unchanged** — Stage-1/Stage-2/control/embedder types and signatures carry over from 3.x untouched. The bump, when 4.0.0 ships, is driven by two things: (1) a **runtime-floor bump for the agent modules**, and (2) the optional `inspecto-agent` module was reshaped onto shared orchestrator primitives (`SyncOrchestrator` / `Capability` / confidence-escalation / `AuditSink`), and `com.gamma.assist.AssistResult.confidence` became a numeric `double` (was a `String`). Only code driving the assist module's result type was affected.
>
> **Current Java floor (moved 2026-09-17):** the core compiles at `maven.compiler.release=27` and is built and bundled on JDK 27. The **agent modules require a JDK 27 runtime** (their model-transport jars are class-file v69), which a 27 floor clears by construction — so the two-floor split this paragraph documented from 2026-07-07 (core 27 / agent 27+, reconciled only by the bundled runtime) no longer exists. **agent-kernel itself was replaced 2026-07-07**: its reasoning layer is vendored into `inspecto-agent` (`com.gamma.agent.kernel.*`) and the model transport is **eoiagent** (`com.eoiagent:eoiagent-core|-model`) — the assist SPI (`com.gamma.assist.spi.AssistAgent`) is unchanged. The Standard edition adds three control-plane SPIs — `com.gamma.control.Authenticator` / `Subject` / `TokenRelay` (implemented by the `inspecto-security` module) — which follow the same stability policy.

**Embedders** (driving the ETL from Java instead of the CLI):

| Type | Since | Role |
|---|---|---|
| `com.gamma.inspector.CollectorProcessor` | 1.0.0 | Run one source (`run(cfg)` / `main`) |
| `com.gamma.inspector.MultiCollectorProcessor` | 1.6.0 | Run many sources concurrently (`runAll` / `main`) |
| `com.gamma.etl.PipelineConfig.load(String)` | 1.0.0 | Parse a pipeline `.toon` into a config |

**Service & control line** (long-running host, Stage-2 enrichment, REST control plane):

| Type | Since | Role |
|---|---|---|
| `com.gamma.service.CollectorService` | 2.2.0 | Always-on host: registry, poll schedule, event bus, control surface (`fromArgs`, `runAllOnce`, `runPipeline`, `pause`/`resume`, `pipelines`, `statusStore`) |
| `com.gamma.service.EnrichmentService` | 2.3.0 | Orchestrates Stage-2 enrichment against batch-commit events + schedules; exposes the run-audit read surface (`configs`, `views`, `runs`, `lineage`) backing `GET /enrichment[...]` (v2.9.0) |
| `com.gamma.enrich.EnrichmentAuditReader` | 2.9.0 | Read side of the Stage-2 audit — reads back the `<job>_enrich_runs.csv` / `_enrich_lineage.csv` ledgers as JSON-ready rows |
| `com.gamma.control.ControlApi` | 2.4.0 | Embedded REST control plane over a running `CollectorService`. History: v3.0 added scoped token auth; the **editions realignment (2026-06-16) removed token auth from the core** — the common core is auth-free (Personal), and Standard re-adds OIDC via the `Authenticator`/`Subject`/`TokenRelay` SPIs. 2026-07-06: versioned `/api/v1` envelope (W1); 2026-07-25: the unversioned business surface retired (API-5). *(This cell said "v4.8 … alongside byte-for-byte legacy routes" until 2026-09-08 — no v4.8 ever existed and the legacy routes are gone.)* |
| `com.gamma.assist.spi.AssistAgent` | 3.0.0 | SPI for the optional embedded assist agent; discovered via `ServiceLoader` (or `CollectorService.registerAgent`) and wired in-process before `start()`. Implemented in the optional `inspecto-agent` module (M0) |
| `com.gamma.service.DbStatusStore` | 2.6.0 | Database-backed `StatusStore` (engine-neutral JDBC; DuckDB by default — zero extra dep; Postgres for a future distributed deployment, bring-your-own driver) — selected via `-Dstatus.backend=db` |
| `com.gamma.report.ReportService` | 2.8.0 | Rolls `StatusStore` audit into a live status snapshot + a historical batch-audit report (backs `GET /status`, `/report`, `/pipelines/{name}/report`); rolls the Stage-2 run audit into `enrichmentReport` (backs `GET /enrichment/{job}/report`, v2.9.0); reports accept a `Window` date range + report duration percentiles p50/p95/p99 (v2.10.0) |
| `com.gamma.job.JobService` | 2.8.0 | Registry + scheduler for config-driven jobs (cron / event / manual) hosting ingest, enrichment, report and maintenance work uniformly (backs `GET /jobs`, `/jobs/{name}/runs`, `POST /jobs/{name}/trigger`) |
| `com.gamma.job.JobConfig` | 2.8.0 | A `*_job.toon` definition: name, `type`, `cron`, `on_pipeline`, `enabled`, type-specific params |

### Agent-consumed core surface (frozen for the pending 4.0.0, M3)

The optional `inspecto-agent` (and `-intelligence`) modules are an L5 consumer that, per
[architecture-layers.md](../architecture-layers.md), must touch core **only through SPI interfaces +
`@PublicApi` types**. The read-only surface they actually consume — captured once in
`UccAgentContext` and reached by the capabilities/skills — is now `@PublicApi`-frozen `since = "4.0.0"`
so this contract can't drift silently. It is deliberately a *freeze*, not a wrapping facade: a parallel
`agent.spi` DTO layer was rejected because the modularization is reactor-internal (one deployable — no
build against an API jar), so wrapping would duplicate data shapes for no payoff. The frozen surface:

| Package | Types |
|---|---|
| `com.gamma.catalog` (+`.spi`) | `ConfigSource`, `Description`, `IdScheme`, `MetadataGraph`, `MetadataGraphService`, `MetadataNode`, `NodeKind`, `OperationalOverlay`, `Provenance`, `DescriptionProvider` |
| `com.gamma.config` (`.io`/`.safety`/`.spec`) | `ConfigCodec`, `ConfigLoader`, `ConfigSafetyValidator`, `SafetyPolicy`, `ConfigSpec`, `ConfigSpecs`, `FieldSpec`, `Finding`, `RawConfig`, `Severity` |
| `com.gamma.sql` | `SqlOracle`, `SqlSandboxPolicy` |
| `com.gamma.signal` | `Ref`, `Severity`, `Signal` |
| `com.gamma.util` | `CronExpression` (relocated from `com.gamma.service`, WS-D §1.7) |
| `com.gamma.service` | (`CollectorService` already public) |
| `com.gamma.etl` / `com.gamma.enrich` / `com.gamma.job` / `com.gamma.event` / `com.gamma.report` | `ConsignmentEvent`, `StatusStore` (StatusStore relocated from `com.gamma.service`, WS-D §1.7); `EnrichmentAuditReader`, `EnrichmentConfig`; `JobConfig`; `EventLog`; `ReportService.Window` |

## Explicitly internal (do not depend on)

`CsvIngester`, `DuckDbCsvIngester`, `DataTransformer`, `TransformCompiler`,
`PartitionWriter`, `OutputFormat`, `LineageCollector`, `SchemaSelector`,
`ConsignmentIngestor`, `BatchPlanner`, `MarkerManager`, `QuarantineManager`,
`DuckLakeRegistrar`, `CommitLog`, `ConsignmentAuditWriter`, the `com.gamma.util.*` CLI
tools, and all of
`ManifestStore`/`Batch`/`PartitionDef`/`PartitionOutput`/`LineageRow` are
implementation detail. (The `com.gamma.inspector` batch-ingest strategy seam —
`ConsignmentIngestStrategy`, `CsvIngestStrategy`, `StreamingPluginIngestStrategy`, `DuckDbRecordSink`,
`IngestOutcome`, `MemberAudit`, all package-private — is likewise internal.) They are stable enough for the framework's own use but
carry no cross-version guarantee. Plugin authors interact with them only
indirectly (e.g. you emit records that `DataTransformer` later reads —
that contract is documented on `StreamingFileIngester`/`RecordSink`, not on `DataTransformer`).

## What is *not* covered by code-level stability — but is still stable

- **Pipeline `.toon` config format** — treated as a stable user-facing contract;
  changes are additive and documented in [configuration.md](../config/configuration.md).
- **On-disk output** (Hive-partitioned Parquet/CSV layout, audit CSVs, commit log
  format) — forward-compatible; a format change would be called out as breaking.

These carry forward across the 1.x → 2.0 boundary unchanged; 2.0 broke only the
**Java embedding API** (the `PipelineConfig` field → nested-record move).

## Why no `module-info.java`

A JPMS module descriptor exporting only the public packages was considered and
**declined**: the project ships a fat, shaded JAR that bundles several
automatic-module dependencies (DuckDB JDBC, univocity, JToon, Jackson, SLF4J,
commons-*, gson, opencsv). Layering JPMS over that combination is high-risk
(split packages, automatic-module naming, shade interactions) for little gain
over the annotation + this policy. The `@PublicApi` marker is the lighter,
sufficient mechanism. Revisit only if the project ever stops shading.
