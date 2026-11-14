---
type: Reference
title: API stability policy
description: What the `@PublicApi` marker means and does not mean, the release baseline (nothing after 3.x has shipped), and the running draft of release notes for the pending MAJOR.
resource: platform/inspecto-api/src/main/java/com/gamma/api/PublicApi.java
tags: [api, stability, publicapi, semver, release]
timestamp: 2026-07-16T00:00:00Z
---

# API Stability Policy
> **Deep reference — the detail tier.** Start at [Control-plane section index](../../index.md) for the summary; this page is the long form it points to. *(Moved from the retired root-level `plan-api-stability.md` (docs consolidation, 2026-07-16).)*

> Part of the [Inspecto](../../../../../inspecto/README.md) documentation.

The framework distinguishes its **stable public API** from internal implementation.
Types, methods, and constructors marked [`@com.gamma.api.PublicApi`](../../../../../platform/inspecto-api/src/main/java/com/gamma/api/PublicApi.java)
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
([BRANCHING.md](../../../../BRANCHING.md) §1). Trunk is `4.0.0-SNAPSHOT`.

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

## Release notes — the pending MAJOR (draft, kept current until the tag is cut)

Everything below is already on `master` and is either a `feat!:` or an operator-visible behaviour change
relative to `v3.11.0`, the newest ancestor release. The list is the BACKLOG §2 "Release notes for the next
MAJOR" row, moved here so it accrues in one place; add a line in the same commit as any further `feat!:`.
The GitHub release is cut by `.github/workflows/release.yml` with `--generate-notes`; paste this section
above the generated commit list.

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
  guide](../agent/admin-guide.md)), and its host must be on the Space's model endpoint allowlist (`7e113110`, below).
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
  another user's mark through the shared `state` no longer can. On Basic the caller is `appUser` (or the
  honour-system `X-Actor`).
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
  [metadata-bundle](../processor/spec-metadata-bundle.md) · [editable round-trip §21](../engine/spec-editable-round-trip.md).
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
- `POST /rule-templates/{id}/simulate` with no write root bound now answers 503 with `errorCode`
  **`CONTROL_PLANE_READ_ONLY`** (was the 503 default `CAPABILITY_UNAVAILABLE`), matching every other
  write-root refusal. Status and message are unchanged; a client keying on the old code must switch.
- The per-Space data-source bundle routes (`GET /datasources`, `GET /datasources/{ds}/export`,
  `GET /export`, `POST /import`, `POST /import/preview`) with no write root bound now answer 503 with
  `errorCode` **`CONTROL_PLANE_READ_ONLY`** (was `CAPABILITY_UNAVAILABLE`), for the same reason. Status and
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
  [parser-plugins.md](../engine/spec-parser-plugins.md), *Decode Profile*.

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
- `com.gamma.etl.ConsignmentEvent` gains a **`boolean dryRun`** component (11 in total). Additive, on a
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
  first group above.

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
  [execution-lanes](../engine/spec-execution-lanes.md)
- **New routes (additive, 2026-09-25, X1 deferrals — the COMMIT retry affordance):**
  `GET /runs/{name}/retries[?limit=]` (open read) lists the files waiting on a bounded COMMIT retry, by
  poll-relative path, with `keepsRetryState: false` when the pipeline has no `dirs.status_dir` (no retry
  state at all — NOT the same answer as an empty list). `POST /runs/{name}/retries/retry-now {file}` and
  `POST /runs/{name}/retries/cancel {file}` (`canOperateRuns`) act on ONE file: retry-now clears the backoff
  **only — the attempt count is kept** (the response says so); cancel **quarantines the file now under the
  new reason `retry_cancelled`**. A file already quarantined, a pipeline with no retry state, or a pipeline
  mid-cycle is a **409** that says so; no record / not in the inbox is **404**.
  [execution-lanes](../engine/spec-execution-lanes.md)
- **New config block (additive, 2026-09-25, X1 deferral):** a Pipeline's `processing.retry:
  {max_attempts, initial_backoff, max_backoff}` overrides the `-Dingest.retry.*` COMMIT-retry globals per
  key; unset keys (and an absent block) inherit them, so existing configs behave exactly as before.
  `max_attempts` outside `[0, 1000]` or a negative/non-duration backoff is a **422** at `/config/write`.
  The `sink.persistent` node advertises `retry__max_attempts` / `retry__initial_backoff` /
  `retry__max_backoff` in `node-attributes.contract.json`.
  [execution-lanes](../engine/spec-execution-lanes.md)
- **New config key (additive, 2026-09-28, X4 deferral):** `processing.reject_mode: eject | all_or_nothing`
  (absent = `eject`, today's behaviour). Any other value is a **422** at `/config/write`. Under
  `all_or_nothing` a file with a rejected record is quarantined whole under the new reason
  `rejects_all_or_nothing`, and `POST /runs/{name}/replay-rejects` for it is a **422**.
  [execution-lanes](../engine/spec-execution-lanes.md)
- **Breaking — the reject sidecar no longer holds `raw_line`** (2026-10-03, never store values): its columns are
  `line_number,columns,reason,value_fingerprint,source_file,source_size,source_sha256`, so
  `GET /runs/{name}/errors?file=` rows carry a fingerprint, not the line; `line_number` is now the PHYSICAL start
  line on both engines. `POST /runs/{name}/replay-rejects` re-reads the source and adds **410** (source gone)
  and a second **409** cause (source changed). Ledger / retry / event failure text fingerprints every quoted
  value. [execution-lanes](../engine/spec-execution-lanes.md)
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
  [editable round-trip §22](../engine/spec-editable-round-trip.md)
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

**Deferred to this same release by decision:** the token *runtime* model (D2 —
[`node-types.md`](../engine/spec-node-types.md) § *The token model*), the ELT
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

> **Removed in 3.11.0 (breaking):** the whole-file `com.gamma.etl.FileIngester` and its nested `Segment` (both since 1.3.0). The plugin SPI is unified on `StreamingFileIngester`; the framework now runs the same ingester in *union mode* (many small files → one transform/write) or *generation mode* (huge single files → bounded scratch), chosen per batch by `processing.streaming.large_file_bytes`. Port `FileIngester` plugins to `StreamingFileIngester` (see [plugins.md](../engine/spec-plugin-ingester.md)). This is a **deliberate exception** to the within-major-version stability promise above, made to consolidate the plugin SPI before it had wide external adoption.

> **Pending major 4.0.0 — NOT YET RELEASED (the agent-module reshape):** the **core ETL `@PublicApi` surface above is unchanged** — Stage-1/Stage-2/control/embedder types and signatures carry over from 3.x untouched. The bump, when 4.0.0 ships, is driven by two things: (1) a **runtime-floor bump for the agent modules**, and (2) the optional `inspecto-agent` module was reshaped onto shared orchestrator primitives (`SyncOrchestrator` / `Capability` / confidence-escalation / `AuditSink`), and `com.gamma.assist.AssistResult.confidence` became a numeric `double` (was a `String`). Only code driving the assist module's result type was affected.
>
> **Current Java floor (moved 2026-09-17):** the core compiles at `maven.compiler.release=27` and is built and bundled on JDK 27. The **agent modules require a JDK 27 runtime** (their model-transport jars are class-file v69), which a 27 floor clears by construction — so the two-floor split this paragraph documented from 2026-07-07 (core 27 / agent 27+, reconciled only by the bundled runtime) no longer exists. **agent-kernel itself was replaced 2026-07-07**: its reasoning layer is vendored into `inspecto-agent` (`com.gamma.agent.kernel.*`) and the model transport is **eoiagent** (`com.eoiagent:eoiagent-core|-model`) — the assist SPI (`com.gamma.assist.spi.AssistAgent`) is unchanged. The Standard edition adds three control-plane SPIs — `com.gamma.spi.auth.Authenticator` / `Subject` / `TokenRelay` (implemented by the `inspecto-oidc` module) — which follow the same stability policy.

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
[architecture.md](../../layer/platform/architecture.md), must touch core **only through SPI interfaces +
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
  changes are additive and documented in [configuration.md](../config/admin-guide-configuration-reference.md).
- **On-disk output** (Hive-partitioned Parquet/CSV layout, audit CSVs, commit log
  format) — forward-compatible; a format change would be called out as breaking.

These carry forward across the 1.x → 2.0 boundary unchanged; 2.0 broke only the
**Java embedding API** (the `PipelineConfig` field → nested-record move).

## Why no `module-info.java`

A JPMS module descriptor exporting only the public packages was considered and
**declined**: the project ships one thin jar per first-party module beside a shaded `inspecto.jar`
(the processor) that bundles several automatic-module dependencies (DuckDB JDBC, univocity, JToon, Jackson, SLF4J,
commons-*, gson, opencsv). Layering JPMS over that combination is high-risk
(split packages, automatic-module naming, shade interactions) for little gain
over the annotation + this policy. The `@PublicApi` marker is the lighter,
sufficient mechanism. Revisit only if the project ever stops shading.
