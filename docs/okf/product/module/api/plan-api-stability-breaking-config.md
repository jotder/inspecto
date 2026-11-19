---
type: Reference
title: API stability policy — Release notes — breaking configuration and CLI changes onward
description: Part 2 of 3 of API stability policy: 
tags: [api, stability, publicapi, semver, release]
timestamp: 2026-07-16T00:00:00Z
item: module.api
audience: internal
status: plan
verified: 2026-11-19 last-edit
code: platform/inspecto-api
---

# API stability policy — Release notes — breaking configuration and CLI changes onward

Part 2 of 3 of [API stability policy](plan-api-stability.md) · previous: [The marker, the release baseline and the pending release notes](plan-api-stability-marker-baseline.md) · next: [The public surface and what is internal](plan-api-stability-public-surface.md).

## Release notes — the pending MAJOR (draft, kept current until the tag is cut) (continued)

**Breaking — configuration and CLI**
- `-Dauth.oidc.tokenEndpoint` is **required** under `authMode: oidc` (D15, 2026-07-25); there is no IdP
  vendor of record and `OidcTokenRelay` will not guess the endpoint.
- `transform.map` is **deleted** (`42fa41fe`, 2026-09-05): the projection slot is always a Record
  Transformer (`transform.sql` with `fields[]`). Stored `mapping.rules[]` stay readable; `MappingMigrator`
  rewrites them to `fields[]` (`--dry-run` first).
- Dedup is folded into acquisition (`61dc8280`): `transform.dedup.fingerprint` is gone; the marker-based
  `duplicate_check` and the windowed `dedup` ledger are the two surviving mechanisms.
- `$`-token evaluation in job parameters: an authored value that is a whole `$`-token now resolves at fire time
  (`8504b782`), and a token nothing registered **REJECTS the Run** — at fire time, not at save (`e8a8a755`,
  `8504b782`); a literal dollar is written `$$` (`e8a8a755`). *(This line said "REJECTED at save" and gave `$$` to
  `8504b782` until 2026-11-15.)*
- `consignment.outputs.backend` defaults to `duckdb`; `ReprocessCommand` refuses a batch younger than
  `min_age_days` (§6.2).
- A sink whose `partitions[].column` is blank **fails the sink branch** instead of writing unpartitioned.
- The pipeline TOON acquisition block is **`collector:`**, renamed from `source:` with no alias (`a3180790`), <!-- vocab-allow: names the rename itself -->
  and every Java/TS/API name says **Collector** (GLOSSARY §13); since `2c310d1c` a `source:` key is refused at
  `/config/write` as a key nothing reads. `?flow=` query params are dual-read, `?pipeline=` is canonical.
  *(This line said the `source:` block was unchanged until 2026-11-15.)*
- **Editions renamed** (`f1b64ce0`, 2026-10-17): Personal → Basic, Professional → Standard, Enterprise → Advanced.
  The Maven profiles `edition-professional` and `edition-enterprise` are removed (use `edition-standard`,
  `edition-advanced`, `edition-preview`), and `package.ps1 -Edition` takes `Basic|Standard|Advanced|Preview`.
- `package.ps1`'s `-Ui` and `-DemoAuth` are removed, no alias (`728dbd60`, 2026-11-10): use `-Product inspecto|la`
  and `-Distribution production|demo`. An Inspecto LA bundle ships only the `la-*` templates.
- **Bundles ship no Spaces** (`eeb8524f`, 2026-09-25): a fresh bundle boots with zero Spaces until one is attached
  or created from the `spaces/_templates` gallery.
- **Advanced needs PostgreSQL** (`6b169d17`, 2026-09-25): operational objects are durable by default, and an
  Advanced bundle without `INSPECTO_DB_URL` does not boot.
- A Schema type the engine cannot cast is **refused at config load** instead of stored as text (`82c947ef`,
  2026-08-22); a declared `INTEGER` now lands as `INTEGER`, which changes that store's Parquet schema on the next
  write and can trip the schema BACKWARD gate.
- A `chain_config` value that is a JSON object, array or `null` is refused at parse, naming the key (`c2a07aeb`,
  2026-09-01); every value reaches the processor as text.
- An active Pipeline with `processing.dedup` refuses to arm unless it authors an `output_store:` (`04004af8`,
  2026-08-11): record dedup is a Stage-2 transform. Keep the Pipeline inactive or run it at rest.
- `derive` no longer knows `group_by`; a config that still names it fails closed (`cd900f7d`, 2026-10-12).
- JSON selectors are dotted paths (`ff14b6e9`, 2026-07-31): a JSON key that contains a literal `.` must be escaped
  (`odd\.key`, written `odd\\.key` in TOON).
- The Decision Rule consequence `invoke-api` takes `params.connection` (an `https` Connection id), not
  `params.url`; it proposes a pending Action Request and no longer emits the `decision-rule.invoke-api` signal
  (`24c5c171`, 2026-09-27).

- The job key `batch_id` is now `consignment_id` (D-12, 2026-11-17): see *Breaking — the `batch_id` rename trio* below.

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
- `/inv/entity-lists…` moved to **`/entity-lists…`**, no alias (`2a739d06`, an unmarked `refactor`, 2026-10-01):
  the list, one list, create, `members`, `retire` and `match`. Gates and handlers are unchanged; the routes now
  come from the `inspecto-entity-list` module.

**Breaking — Java `@PublicApi` (binds only within a released major; none of this was published in 3.x)**
- Three store interfaces gained abstract methods for `incident_purge` (MNT-14, 2026-07-27).
- `com.gamma.ops.NoteTargets` → `com.gamma.ops.AnnotationKinds` (no alias). ⚠ Relocated again in EDG-01 cell 7 (2026-09-08) to **`com.gamma.objects.AnnotationKinds`** — it is core vocabulary with no store coupling, so it stayed in the mandatory build when `com.gamma.ops` became an optional module.
- Maven artifactIds `file-processor-*` → `inspecto-*` (2026-08-10); the deployment bundle name is
  unchanged — renaming it is a separate, unmade decision.
- `CollectorService.PipelineView`, `.PipelineRun` and `.InboxStatus` are top-level records in `com.gamma.service`
  (`7eab72d4`). `CollectorService.objects()` returns `Optional<ObjectAccess>`, because operational objects moved
  to the optional `inspecto-ops` module (`e8d98918`). `ConsignmentOutput` loses its `generation` component and
  every constructor's `int generation` parameter (`09740d56`). For the types that did ship in `v3.11.0`, see the
  first two groups above.

**Operator-visible behaviour**
- **Behaviour change (2026-10-25, `DATASET-CALC-NAME-COLLISION-1`):** a Dataset's `calculated` column may not reuse a
  base or declared column's name (any case), repeat another calculated name, or start with `__`: save answers 422, and an
  existing Dataset that collided stops reading (422) until the calculated column is renamed. **Report Jobs and
  attachments approved before this change on a Dataset WITH calculated columns need re-approval** (re-save the Job): the
  approval fingerprints the relation SQL, and the calculated-column wrap's text changed, so the old approval no longer
  matches and the send is held — it fails closed. Datasets without calculated columns are unaffected.
- **Breaking (2026-09-28, `ASSURE-IMPACT-LEDGER-RESIDUALS-1`):** an object read's `impact` block carries its
  amounts (`suspected`, `confirmed`, `recovered`, `prevented`, `outstanding`) as **decimal strings**
  (`"1200.5"`, plain — never exponent form), no longer JSON numbers, so a client can send a read value back
  unchanged; `PUT /objects/{id}/impact` still accepts a number or a string. `GET /objects/analytics`
  `impact.byCurrency` totals stay numbers.
- **Breaking + new route (2026-09-26, WS-10 `ASSURE-IMPACT-LEDGER-1`):** `PUT /objects/{id}/impact
  {impact:{suspected?, confirmed?, recovered?, prevented?, currency?, period?, basis?}}` (`canWorkIncidents`)
  records an Incident's or Case's typed financial impact; every object read gains a top-level `impact` block
  with the derived `outstanding`. **`GET /objects/analytics` `impact.impactAmount` is gone** — replaced by
  `impact.byCurrency.<ISO 4217>` (count + four amounts + outstanding); `recordsAffected` stays. The built-in
  Findings spec no longer has an `impactAmount` section and `PUT /objects/{id}/findings` no longer writes an
  `impactAmount` flat copy (it writes `recordsAffected` only). The `ops_analytics` sample replaces axis
  `impact`/key `impact_amount` with axes `impact.<ISO>`. `409` only on an ARCHIVED Incident, or on a RESOLVED
  Incident / CLOSED Case for any change but `recovered` / `prevented` (late recoveries). **`PATCH /objects/{id}`
  now refuses `attributes.impact` and `attributes.disposition` with 422.**
- **Breaking (2026-09-26, WS-10):** resolving an **Incident** (`POST /objects/{id}/resolve`, `/transition`)
  now needs a **Disposition** — body `disposition` from `CONFIRMED · FALSE_POSITIVE · RECOVERED · WRITTEN_OFF ·
  INCONCLUSIVE · DUPLICATE · ACCEPTED_RISK`, or one already on `attributes.disposition` — else **422** naming
  `disposition`. A `disposition` on any other move → 422. A reopen clears the Disposition. A per-entity Alert
  heal no longer resolves its Incident (only the Alert). The Findings default ladder gains the last two values.
- **New route (additive, 2026-09-25, operator decision — Findings values are collaboration):**
  `PUT /objects/{id}/findings {findings:{key: scalar…}}` saves a Case's Findings values, **open to anyone who
  can see the object** (the SEC-7d/ABAC scope guard still answers 404) and recorded as a `collaboration`
  exemption in `CapabilityManifest.EXEMPTIONS`. It writes only the `attributes.findings` blob plus the flat
  `recordsAffected` copy (derived **server-side**, `""` when absent; the `impactAmount` copy retired with WS-10
  on 2026-09-26), is judged by the
  same findings-spec gate as the PATCH (**422**), refuses any other body key (**422**) and a missing
  `findings` (**400**), and emits an `OBJECT_ACTIVITY` event with `action: findings` and the request's actor.
  `PATCH /objects/{id}` is **unchanged** (still `canAdminister`, still accepts `attributes.findings`). The
  SPA Findings panel now saves through the new route and sends team + target date on the PATCH only when
  they were edited. [objects](../ops/user-guide-objects.md)
- **New route (additive, 2026-09-25, EXECUTION-RESIDUALS X4):** `POST /runs/{name}/replay-rejects {file}`
  (`canOperateRuns`) replays ONE file's rejected records from its reject sidecar as a new Consignment,
  without re-ingesting the file's good records; a second replay of the same sidecar is **409**.
  [execution-lanes](../elt/spec-pipeline-execution.md)
- **New routes (additive, 2026-09-25, X1 deferrals — the COMMIT retry affordance):**
  `GET /runs/{name}/retries[?limit=]` (open read) lists the files waiting on a bounded COMMIT retry, by
  poll-relative path, with `keepsRetryState: false` when the pipeline has no `dirs.status_dir` (no retry
  state at all — NOT the same answer as an empty list). `POST /runs/{name}/retries/retry-now {file}` and
  `POST /runs/{name}/retries/cancel {file}` (`canOperateRuns`) act on ONE file: retry-now clears the backoff
  **only — the attempt count is kept** (the response says so); cancel **quarantines the file now under the
  new reason `retry_cancelled`**. A file already quarantined, a pipeline with no retry state, or a pipeline
  mid-cycle is a **409** that says so; no record / not in the inbox is **404**.
  [execution-lanes](../elt/spec-pipeline-execution.md)
- **New config block (additive, 2026-09-25, X1 deferral):** a Pipeline's `processing.retry:
  {max_attempts, initial_backoff, max_backoff}` overrides the `-Dingest.retry.*` COMMIT-retry globals per
  key; unset keys (and an absent block) inherit them, so existing configs behave exactly as before.
  `max_attempts` outside `[0, 1000]` or a negative/non-duration backoff is a **422** at `/config/write`.
  The `sink.persistent` node advertises `retry__max_attempts` / `retry__initial_backoff` /
  `retry__max_backoff` in `node-attributes.contract.json`.
  [execution-lanes](../elt/spec-pipeline-execution.md)
- **New config key (additive, 2026-09-28, X4 deferral):** `processing.reject_mode: eject | all_or_nothing`
  (absent = `eject`, today's behaviour). Any other value is a **422** at `/config/write`. Under
  `all_or_nothing` a file with a rejected record is quarantined whole under the new reason
  `rejects_all_or_nothing`, and `POST /runs/{name}/replay-rejects` for it is a **422**.
  [execution-lanes](../elt/spec-pipeline-execution.md)
- **Breaking — the reject sidecar no longer holds `raw_line`** (2026-10-03, never store values): its columns are
  `line_number,columns,reason,value_fingerprint,source_file,source_size,source_sha256`, so
  `GET /runs/{name}/errors?file=` rows carry a fingerprint, not the line; `line_number` is now the PHYSICAL start
  line on both engines. `POST /runs/{name}/replay-rejects` re-reads the source and adds **410** (source gone)
  and a second **409** cause (source changed). Ledger / retry / event failure text fingerprints every quoted
  value. [execution-lanes](../elt/spec-pipeline-execution.md)
- **Behaviour change — the reject sidecar's `raw_line` is now byte-exact** (2026-09-25, superseded 2026-10-03): both CSV ingesters
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
  [editable round-trip §22](../elt/spec-pipeline-model.md)
- `DELETE /spaces/{id}?purge=true` answers **409** when it would remove the **last Space directory on disk** (D4); deregister-only on the last Space stays allowed. *(This line stated the rule backwards — "409 unless `?purge=true`" — until 2026-09-08; `SpaceRoutes.java`.)*
- Full recomputes write a sibling `<pipeline>_<batchId>` table and supersede the old revision in the
  catalog; **nothing deletes the bytes** until a `retire_superseded` maintenance job is configured. 3.x
  overwrote in place, so after the upgrade every full recompute adds a copy on disk: enable a copy of
  `spaces/demo/config/jobs/retire_superseded_job.toon` (it stays off by default, MoSCoW C7, 2026-11-17).
- Run artifacts carry `event_time_min` / `event_time_max` instead of `timeRange`.
- `mail.send` on a deployment with **no email channel configured** answers **`SKIPPED`**, not SUCCESS — it is
  inert, not green. With **no recipients** (a `to` that resolves entirely blank) it answers **FAILED**.
  *(This line said "no recipients logs 'SUCCESS, nothing sent'" until 2026-09-17, conflating the two cases and
  naming the status the `ad29e683` reversal removed; `MailSendJob.java` — `JobResult.skipped` / `JobResult.failed`.)*
- The token picker's preview is the server's evaluation, not a client-side guess.
- New default-on cap: `-Djobs.maxConcurrentRuns=4` (D11), editable under Settings ▸ Scheduler ▸ Resource caps, where
  DuckDB `memory_limit` is also served (`2GB` is the measured recommendation, **not a shipped default** — GAP-4;
  this line said "default-on caps … `memory_limit=2GB`" until 2026-09-08). Since 2026-09-26 (GAP-4) an
  unconfigured `duckdbMemoryLimit` is served as the computed code default (40 % RAM ÷ 4, ≥ 1 GiB), not `null`.
- The event store prunes by whole day partitions once an `event_prune` maintenance job exists (COMPLY-3);
  releases are SBOM'd and signed in CI only (COMPLY-1/2).
- **Breaking (2026-10-09, `ecd21833`):** `GET /runs` serves `configPath` relative to the Space config root (the bare
  file name for a config outside it), never the host's absolute path.
- **Breaking (2026-10-09, `6d901200`):** a Reconciliation side whose Dataset has no temporal column is a **422**
  naming the Dataset (mark a column `role: temporal` or set its `dateField`), whether or not the other sides are
  dated, and a `recon.run` Job fails with the same message. There is no whole-period fallback.
- **Breaking (`e242ace5`, `738461e4`):** the operational object types are `INCIDENT`, `CASE` and `TASK`. An object
  read or `?type=` filter says `"INCIDENT"` where it said `"ISSUE"`, no alias. `ALERT` is no longer an object type:
  it is refused like any unknown type, stored `ALERT` rows load inert, and the Alert lifecycle lives in
  `com.gamma.alert`.
- **Breaking (2026-10-10, `c084e0fd`):** a pending Alert Rule names its trigger `afterScore: {kind: risk-score |
  anomaly-model, model}`, in the pending-rules file and in `GET /alerts/rules/pending`; the flat
  `afterRiskScore: <model>` form is refused.
- **Breaking (Link Analysis):** `graph_run.threads` / `graph_run.queue` and `index.threads` / `index.queue` are
  install-wide limits, no longer Space settings — `PUT /settings/link-analysis` refuses them as unknown keys
  (`5b839765`, 2026-10-15). A value-Measure Alert Rule bound to an Investigation is not evaluated until its owner
  enables standing detection (`POST /inv/investigations/{id}/standing-detection`) (`cf13f2c3`, 2026-10-04). Under
  the default `typed` masking mode, subscriber, IMEI and wallet ids and seeds of unknown `entityType` are masked
  (`0d74028c`; its `masked: false` override is narrowed by `MASKING-TWO-VOCABULARIES-1` above).
- **Behaviour change (2026-10-03, `13dee729`):** the engine's in-memory DuckDB connections have extension autoload
  off and read only the directories their caller declares, so a hand-written view that reads over `http://` or
  outside those roots now fails its Run. The spill default moved from the temp dir to `<tmpdir>/.duckdb_tmp`.
- **Behaviour change (2026-10-04, `62af7a99`, `85295c56`):** for an inbox file whose name carries a long digit run
  (not a timestamp), the output file, the reject sidecar, the chunk scratch and the replay input are named by the
  fingerprinted stem (`fp-<hex>` in place of the digits). Names without such a run are unchanged.
- **New defaults (no refusal):** `processing.threads` follows the host's logical cores, not 4 (`84d2c975`);
  `processing.chunking.max_file_bytes` is 8 GiB, not off (`30c80bd5`); `-Dingest.maxFilesPerCycle` caps intake at
  500 files per cycle, and `0` keeps it unbounded (`eef4d010`).

⛔ **The `consignment.process` contract stays FROZEN, including its correlation identifier** — plugins
already depend on it, so the token work must not change that shape as a side effect. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*

**Additive — every public OpenAPI operation is described (2026-11-17, `OPENAPI-SKELETON-SUMMARIES-1`, MoSCoW C3)**
- `docs/api/openapi-v1.json` (also served at `GET /api/v1/openapi.json`) had 440 operations that were generated
  skeletons: the summary `<METHOD> <path> (generated skeleton)` and a stock description. 439 of them now carry a
  summary and a one- or two-sentence description read from the handler: what the route does, the capability it
  needs, and its main refusals. The text lives in each module's `openapi.fragment.json`.
- **Public** means every `/api/v1` operation registered by shipped (main) code. The server has no internal or
  UI-only route class, and the console calls the same surface an integrator does. The one skeleton left,
  `GET /test-discovered/ping`, is registered only by the test RouteModule `TestDiscoveredRoutes`.
- Those operations keep `x-generated: true`: they still have no request or response schemas, so the schema
  coverage `ApiContractTest` prints is unchanged.
- Guard: `tools/check-openapi-summaries.mjs` (CI `guards` job) fails on a skeleton summary or the stock description
  outside its allow-list. The allow-list may only shrink: an entry that gets a real summary, or whose route is
  gone, also fails.

**Breaking — the `X-Actor` header is removed (2026-11-17, MoSCoW S4)**
- The `X-Actor` request header carries no identity on any edition. The actor is the authenticated Subject; on
  Basic, with no Subject, it is the local default `appUser` (or `agent:<session>` for a call carrying
  `X-Agent-Session`). Before, a server with no Authenticator (Basic) took the header's value as the audit actor and as the reader of
  notification read state.
- A request that still sends a non-blank `X-Actor` is refused with `403 PERMISSION_DENIED` on **every** edition, and
  the refusal is audited as `access.denied` under the request's actor, never under the claimed name. A server with an
  Authenticator active already answered 403 (SEC-7a); Basic now does the same. Refused rather than ignored so a
  client that relied on it fails loudly instead of having its writes filed under `appUser`. The console, `tools/`
  and the Space seed scripts never sent it.
- Migrate: drop the header. To tell callers apart, move to Standard (a Subject per caller); a script that needs
  its own audit name on Basic has none.

**Breaking for Standard installs that read ASN.1 — the data layer by edition (2026-11-17, `DATA-TIERS-1`, MoSCoW S8)**
- **The ASN.1 and telecom decoders moved from Standard to Advanced** (DT-12): module `telecom-asn1` and the add-on
  `telecomIndustryPack` (`INS-AO-TELECOM-INDUSTRY-PACK`) are in `offerings/advanced.toon`, and a Standard bundle no
  longer carries `inspecto-telecom-asn1.jar`. After a Standard upgrade an ASN.1 Pipeline (`parsing.frontend: asn1`)
  still loads - it is listed `hosted:false` with `missingModule: telecom-asn1` - but every file it picks up fails at
  run with *"…the ASN.1 decoder is the optional inspecto-telecom-asn1 module (Telecom industry pack, Advanced - not in
  Basic or Standard)"* (`PluginIngesters`). Migrate: move the install to Advanced (with the Telecom industry pack), or
  switch the Pipeline off before upgrading. No shipped Space Template uses ASN.1.
- **The `la-daily-feed` Space Template moved from Basic to Standard** (DT-18: `minEdition: standard`,
  `requiresModules: ops`): a Basic install no longer offers it. A Space created from it earlier keeps its config and
  files; its schedules and Alert Rules need Standard to run as designed.
- Not breaking: Basic's screens now lock transformation Steps, remote systems and outbound-delivery Jobs with a
  reason (`/bootstrap` `features.pipelineTransform`, `pipelineEgress`, `remoteSystems`, `pipelineAutomation` and
  their `featureReasons`); a configuration that already uses them loads, runs and stays editable.

**Breaking — the `batch_id` rename trio (D-12, 2026-11-17, MoSCoW S4)**

The unit of work is a Consignment (GLOSSARY §13); the three places that still persisted it as `batch_id` now say
`consignment_id`. Existing installs keep reading their old data: each surface either migrates itself once on open
or is read under both names.
- **Database columns → `consignment_id`, migrated on open.** `inspecto_status_commits` and
  `inspecto_status_lineage` (`DbStatusStore`), `inspecto_pipeline_provenance` (`DbProvenanceStore`) and
  `file_stages` (`DbFileStageStore`). The first 4.0.0 open of a 3.x database renames the column. It is one-shot and
  idempotent: the database catalog is the record, so a second open finds `consignment_id` and does nothing. The status DB
  also rewrites the `"batch_id"` key inside its stored `payload` JSON, and drops and recreates its lineage index
  (now `inspecto_status_lineage_by_pipeline_consignment`). `file_stages` is rebuilt (rename, create, copy, drop) in
  one transaction, the same way its unique constraint was added. ⚠ A raw SQL query or BI tool reading these tables by
  the old column name must change. ⚠ No downgrade: a 3.x binary over a migrated database finds no `batch_id` column.
- **Reference store system column `__batch_id` → `__consignment_id`, read under both names.** A `produces:
  reference` Pipeline with `load: upsert|scd2` stamps `__consignment_id` on new versions. Files already landed keep
  `__batch_id`; no Parquet file is rewritten. The current and as-of views strip both names, so an enrichment or a
  join sees the same columns as before. ⚠ A raw read of a store with files of both ages (`union_by_name`) shows
  **both** columns, each `NULL` on the other age's rows. A query of the raw store that selects `__batch_id` must
  read `coalesce(__consignment_id, __batch_id)`. `ReferenceCompactor` keeps both columns when it merges such a store.
- **Job key `batch_id` → `consignment_id` (the `pipeline` Job Type).** The pinned Consignment id is read from
  `consignment_id`. `batch_id` is still read when `consignment_id` is absent, with a WARN on every run, until
  5.0.0. The Job Type descriptor declares `consignment_id`, and the undeclared-key warning does not flag `batch_id`
  while it is still read. No shipped config used the key.
- **API, deprecated alias kept.** `GET /provenance/batches` returns `consignmentId` on each row. `batchId` is still
  sent with the same value, deprecated, removed in 5.0.0. `GET /provenance` takes `?consignment=`; `?batch=` is
  still read, deprecated, removed in 5.0.0, and its 400 names `consignment`. The console reads the new names.
- Cosmetic, nothing to migrate: a new `BranchCommitLog` CSV gets the header `recorded_at,consignment_id,branch,phase`.
  An existing one keeps `batch_id`, because rows are read by position. The ingest ledgers and the manifest were renamed
  in 2026-08 with a read alias (`Csv.LEGACY_HEADERS`, `@SerializedName(alternate)`), and that alias stays.
- **Java records `ProvenanceRow` and `ConsignmentEvent`: component `batchId` → `consignmentId`** (MoSCoW S4, decided
  with X5 D1–D10). The accessors are `ProvenanceRow.consignmentId()` and `ConsignmentEvent.consignmentId()`; the
  constructors keep their argument order. Both records are `@PublicApi` since 4.0.0 and were not public in 3.12.0
  (`ConsignmentEvent` was the internal `BatchEvent`), so no released API changes. Neither record is serialised whole,
  so no wire field moves with it: `GET /provenance/batches` keeps its deprecated `batchId` alias (it comes from the
  store's SQL), and the `pipeline.commit` Signal payload, the `BATCH_COMMITTED` / `BATCH_FAILED` event attribute and
  the record-replay claim file keep their `batchId` key, because `$signal.batchId` binds (`ConsignmentProcessJobType`
  among them) and the ledgers already written read that name.
- ⚠ **Not renamed:** the route paths `/runs/{n}/batches` and `/provenance/batches`, the `batchId` fields of the
  run, reprocess, drain and lineage routes, and the `batch_max_*` keys (those mean grouping, not a Consignment).
  None of them is part of the trio decided on 2026-09-06.

**Breaking — one word for rejected rows (X5-a, 2026-11-17, MoSCoW S4)**

A Step that does not pass rows on now says why in one way: the **reject outlet** `reject:<reason>`
([`spec-pipeline-model.md`](../elt/spec-pipeline-model.md) § *The token model*).
- **Reject relations are `reject:dropped`, `reject:invalid`, `reject:duplicate` and `reject:unmatched`**, replacing
  `dropped`, `invalid`, `duplicate` and `unmatched` in `GET /pipelines/node-types` (`emits`), `GET /provenance`
  (`rel`), a Pipeline graph's edges (the lifted parse → quarantine edge reads `reject:unmatched`), a dry run's and a
  run-to-here's `relations[]`. Saved Pipeline graphs and stored provenance rows are read under both names; a graph is
  saved with the new names the next time it is saved, and no file or stored row is rewritten. ⚠ A client or BI query
  that matches the old values must change. The `PipelineRel` constants keep their names (`PipelineRel.DROPPED` is
  now `"reject:dropped"`); new `PipelineRel.canonical(rel)` maps an old spelling to the new one and
  `PipelineRel.reason(rel)` reads the reason. `ConservationCheck.RelCount` gains `reason`.
- **The `success`, `failure` and `on_commit` relations are removed.** Nothing produced them. `PipelineRel.SUCCESS`,
  `FAILURE` and `ON_COMMIT` and `PipelineValidator.ON_COMMIT_SAME_GRAPH` are deleted; the sinks and `acquisition`
  no longer list them in `emits`, and `enrichment` no longer lists `on_commit` in `accepts`. An edge that names one
  is dropped when its graph loads, with a `RETIRED_RELATION` warning naming it (`PipelineCodec.decode`; logged by
  `fromMap`; a `pipeline_author` finding). A Consignment's outcome is the `pipeline.batch.committed`,
  `pipeline.batch.failed` or `pipeline.batch.parked` Signal.
- **A contributed Step declares the new spellings.** A Step whose `emits()` lists a bare `dropped` (or `invalid`,
  `duplicate`, `unmatched`) is refused `ILLEGAL_EMIT`, because its authored edge now reads `reject:dropped`. Migrate:
  declare `PipelineRel.DROPPED` (or `"reject:<reason>"`). The Step SPI never shipped in a release.
- The control-plane save and validate routes drop a retired edge without returning the warning; it is logged.
- Not changed: `data` (the main outlet), `gap` (still an edge to the `gap` node), `route:<key>`, and the authored
  `*_pipeline.toon` config, which holds no relation name.

**Added — a record of every Step, and one read for one Consignment (X5-b / X5-c, 2026-11-17, MoSCoW S4)**

Additive: nothing existing changes shape.
- **New: a Step Receipt for every Step a Consignment passes through** (GLOSSARY *Step Receipt*): rows in, rows per
  outlet, where the data rests, a schema fingerprint and, when it failed, the Step and its code. At most 1 KB each,
  in the new `consignment_steps` table beside `consignment_outputs` in the default-on Consignment registry. On by
  default (`-Dconsignment.steps=false` switches the writes off); kept 30 days (`-Dconsignment.steps.retentionDays`,
  pruned by `db_maintenance`). Written by every durable lane, success or failure: the at-rest job lane, the ingest
  lanes (flat as phases, the graph fork per Step), park and drain. A receipt never stores a cell value: its message is
  scrubbed as failure Signals are (`FailureText`). Java: `com.gamma.pipeline.exec.StepReceipt` (`@PublicApi` 4.0.0),
  `PipelineExecutor.WalkObserver`, and `DbConsignmentOutputStore.recordSteps` / `steps` / `stepCount` /
  `outputCount` / `consignmentsOfRuns`; beside them `DbJobRunStore.run` / `countRunsDerivedFrom` and the bounded
  overloads `outputs(id, limit)`, `steps(id, limit)`, `runsDerivedFrom(id, limit)`, and two default methods on the
  `StatusStore` SPI, `consignment(cfg, id)` (the newest ledger row of the id) and `consignmentFiles(cfg, id, limit)` (the newest row per
  member file), with the static helper `StatusStore.latestPerFile` (additive: an existing implementation inherits
  them).
- **New: `GET /consignments/{consignmentId}`** returns one Consignment across lanes — status, member files, its
  Step Receipts, outputs, the job runs that read it (each with the Consignment that run wrote), what its own run read,
  and its Signals; `known.{steps,runs,outputs}` says which stores could answer, and a store that cannot leaves its
  key absent (receipts off ⇒ no `steps`, never `[]`). Every list is capped with its true total beside it. An open
  read, like `GET /runs/{name}/batches`. OpenAPI: `ConsignmentTrail`,
  `StepReceipt`.
- **Changed: `pipeline.batch.failed` names the failing Step, and a failed at-rest job run raises it.** The Signal
  gains `failedStep` and `code` when a Step owns the failure. A `type: pipeline` Job Run that ends FAILED now raises
  exactly one `pipeline.batch.failed` (source `job:<name>`; not for a cancelled Run, nor for an attempt a retry
  recovered) and no `ConsignmentEvent`, so `on_signal: pipeline.commit` Jobs see nothing new. ⚠ An `on_signal:
  pipeline.batch.failed` Job or Alert Rule now also fires for job-lane failures; filter on `source` to keep the old
  behaviour. OpsMonitor's `batch_rerun` skips them.

**Deferred to this same release by decision:** the token *runtime* model (D2 —
[`node-types.md`](../elt/spec-pipeline-model.md) § *The token model*). X5-a, the token vocabulary, and X5-b / X5-c
(Step Receipts on every durable lane and the read route) landed (entries above).

**Deferred pending the operator (2026-11-17): the ELT Phase 6 deletion of the flat read path** (BACKLOG §2 Row 15,
MoSCoW S3). The operator decided 2026-11-16 to delete it in 4.0.0 itself; on 2026-11-17 that was found infeasible,
and nothing was deleted. The parity gate is red since D-11 (2026-10-04): 31 shipped `output_store:` + step-chain
Pipelines still write on the flat lane. And `inspecto migrate-configs` writes `pipelines/*.toon` recipes that no
runtime loads. **Re-scoped 2026-11-17 (option 3, a deprecation note only; taken under the operator's instruction for
the MoSCoW run, reversible):** 4.0.0 keeps both lanes and `-Dingest.lane`, and ships this entry:

**Deprecated in 4.0.0, removal planned for 5.0.0 — the flat ingest write lane (`-Dingest.lane=flat`)**
- Nothing changes in 4.0.0: every Pipeline writes as it did in 3.x, and `-Dingest.lane` keeps `auto`, `flat` and
  `graph`.
- In a later MAJOR the graph lane becomes the only ingest write lane. It will land only after three prerequisites
  (board §2 *Row 15*): the graph lane carries an `output_store:` Pipeline's raw write; configs without a scratch
  directory (`dirs.temp`) get a durable home for the branch-commit ledger; and a graph-only run of the whole test
  suite is green.
- Prepare: give each Pipeline config a `dirs.temp`. A Pipeline written for `-Dingest.lane=flat` behaviour should be
  tested with `-Dingest.lane=graph`.
- Not deprecated: `*_pipeline.toon` config files. Deleting that reader needs a runtime for `pipelines/*.toon`
  recipes, and none is planned.

**Decided 2026-11-16 to land before the tag (operator, MoSCoW S4 / C5; each adds its own entry above when it
lands):** the X-Actor header path removed (landed 2026-11-17, entry above), the `batch_id` rename trio (landed
2026-11-17, entry above) and X5 with the StepInfo envelope (X5-a landed 2026-11-17, entry above).
- **`AlertMigration` stays in 4.0.0 (operator, MoSCoW C5 restated 2026-11-16).** No released version carries the Alert
  store (it landed on trunk 2026-10-07, after `v3.11.0`), so 4.0.0 is the first release with it and adopts a 3.x
  install's still-active ALERT objects on upgrade. `AlertMigration` / `LegacyAlertObjects` are deleted in 5.0.0, whose
  notes must say an install upgrading from 3.x goes through a 4.x release first.
