# LA analyst upload - bring your own spreadsheet (i2-gap 2, roadmap D4) - PLAN

Status: BUILT 2026-10-18 after the decisions below were assistant-decided on the operator's delegation (**pending operator confirmation**). See "As built" at the end.
Roadmap D4 stance: "templates first, upload route later". This is the "later". Vocabulary per docs/GLOSSARY.md
(Collector, Pipeline, Dataset, Investigation, Incident, Expectation).

## 1. Goal and non-goals

An analyst with `canManageIncidents` brings a CSV (an XLSX exported as CSV) into the product and can then use it in an
Investigation: as a Dataset for Entity import (`POST /inv/entity-identities/import`) and as a graph input.
Non-goals: no parallel ingest path, no native XLSX parser, no Hive/db upload, no UI in the first cut, no editing of the file.

## 2. Reuse (the only allowed path)

The route lands the file; it does not parse or index it. Everything after landing is existing machinery:

1. Bytes land in a Collector inbox (file Collector, `spaces/_templates/la-daily-feed` style, T2) - the same door a
   delivered daily file uses.
2. The Collector's Pipeline (delimited frontend, schema, quarantine, partitionKey) writes Parquet to the Dataset's store
   through `ConsignmentIngestor` (markers, lineage, per-day `DayManifest`, T8).
3. The Dataset is then readable by `InvRoutes.relationFor` (R3 view gate), so identity import
   (`EntityIdentityRoutes.importDataset`) and `la.index.build` (T5, `job.dataset.produced`) work unchanged.
4. Provenance on identity facts is already `dataset:<id>@<fingerprint>`; the upload adds the landed file name + sha256 to the audit event.

## 3. Route (proposed)

`POST /inv/uploads/{collector}?name=<file>.csv`, body = raw bytes (`text/csv`), `Content-Length` required.
Lives in `la/inspecto-la-api` (new `UploadRoutes`), edition-gated with the other LA routes. Response 201 envelope:
`{file, bytes, sha256, collector, landedAt}`. Ingest is asynchronous (Collector poll); the route never parses.
A status read (`GET /inv/uploads/{collector}/{file}`) maps onto the existing DayManifest / quarantine lookup (second cut).

## 4. Gates, in fail-closed order

| # | Gate | Result | Notes |
|---|------|--------|-------|
| 0 | Edition | 503 "not installed" on Basic | route listed in `AbsentGeoLinkRoutes.SURFACE`; `GeoLinkAbsentSurfaceParityTest` guards it |
| 1 | Capability `canManageIncidents` (or a new `canUploadData`, D-UP3) | 401 / 403 | `ApiContext.withCapability` + `CapabilityManifest` entry |
| 2 | Write root | 503 | `WriteGates.requireWriteRoot` |
| 3 | Rate class | 429 | `LinkAnalysisRateClasses` entry (expensive, write) |
| 4 | Size cap | 413 | `Content-Length` checked BEFORE reading; body streamed through a counting limiter (a lying length cannot exceed it); cap from `SpaceLimits` (new `LaLimit.UPLOAD_MAX_BYTES`, default 25 MiB, D-UP2); per-Space daily quota (D-UP2) |
| 5 | Name / extension / content sniff | 422 | `[A-Za-z0-9._-]{1,100}`, extension `.csv` only, reject NUL bytes and XLSX/zip magic (`PK`) with "export as CSV", header row decodable as UTF-8 |
| 6 | Collector resolve | 404 (unknown or not upload-enabled) | only a Collector flagged `accepts_upload: true` is a target (D-UP1) - never a free path |
| 7 | Path jail | 403 | `resolve().normalize().startsWith(inboxRoot)` on the final path; name already charset-restricted, jail anyway |
| 8 | Classification / masking | 403 four-eyes where the target schema marks sensitive columns | `InvRoutes.refuseIfSensitive` analogue; the Pipeline schema, not the file header, decides masking - the analyst cannot self-declare a column non-sensitive |
| 9 | Conflict | 409 | same name already pending in the inbox or already ingested (T4 feed rule: a re-delivery keeps the SAME name; a correction needs explicit `replace=true`, D-UP4) |
| 10 | Act atomically | 201 | write `<name>.part` in a temp dir on the same filesystem, fsync, `Files.move(ATOMIC_MOVE)`; the Collector must never see a partial file (verify the inbox ignores temp names, test it); delete the temp on any failure |

Audit event (every outcome after gate 1, refusals included): `la.upload` with subject, Space, Collector, file name, bytes,
sha256, outcome, refusing gate. Metadata only: never row content, never a header value.

## 5. Security notes

- Content is untrusted. The analyst cannot choose the Pipeline, schema, store, Dataset id or path: only the Collector from an
  allow-list. That is what makes the route safe: a malicious upload can at worst land in quarantine.
- Parse failure lands in the Pipeline's quarantine (existing behaviour); the status read surfaces it in words.
- Classification decided by the schema (gate 8); masking applies at read exactly as for any Dataset (`MaskTokens.spaceKey`).
- No amplification: the route stores bytes only; row caps stay on identity import (`identity.import_max`).
- CSV formula injection matters for any later export, not for ingest; the export path's escaping is unchanged here.

## 6. Test plan (real HTTP, modelled on `ControlApiEntityIdentityImportTest`, REAL Subject per request)

One test per gate: 401, 403 (no capability), 503 (no write root), 429, 413 (declared length and a lying length), 422 (bad name,
bad extension, zip magic, NUL byte, undecodable), 404 (unknown / non-upload Collector), 403 (encoded traversal such as `%2e%2e`;
four-eyes on a sensitive schema), 409 (duplicate), 201 happy path (file in inbox, sha256 equals sent bytes, audit event written),
atomicity (no `.part` left after a failed write), edition 503 stub. Plus one end-to-end: upload, run Collector + Pipeline, then
identity import of the landed Dataset (DuckDB). Checklist items: `CapabilityManifest`, route fragment +
`node tools/openapi-merge.mjs --write`, `AbsentGeoLinkRoutes.SURFACE`, `tools/route-gating-report.mjs`.
Unit-level only: `-pl la/inspecto-la-api -Dtest=...`; the full reactor only at GAUNTLET.

## 7. Slice analysis (superseded: built once D-UP1..D-UP3 were decided)

Every slice is decision-bound: the route needs a landing target (D-UP1); size cap and quota are policy numbers (D-UP2); the
capability is a role-model choice (D-UP3). A pure helper (name validator, counting limiter, zip sniff) would be speculative
code with no caller until D-UP1 lands. Decision-free slice: none. Once D-UP1..D-UP3 are answered the build is one
`UploadRoutes` class plus the checklist, roughly M.

## 8. Decisions (assistant-decided 2026-10-18 on the operator's delegation; pending operator confirmation)

| ID | Question | Recommendation |
|----|----------|----------------|
| D-UP1 | Where does an upload land? (a) a Collector flagged `accepts_upload` with a fixed Pipeline + schema (the `la-daily-feed` template made analyst-fed); (b) a per-Investigation scratch Dataset with an inferred schema; (c) one shared "analyst-uploads" Collector. | (a): reuses T2-T9 unchanged; the schema decides masking and typing. (b) needs schema inference + lifecycle = a new path. |
| D-UP2 | Size cap per file, and per-Space daily quota. | 25 MiB per file, 200 MiB per Space per day, both `SpaceLimits` keys. |
| D-UP3 | Capability: reuse `canManageIncidents` or a new `canUploadData`. | New `canUploadData`: ingesting data is not incident management. |
| D-UP4 | Re-upload of the same name: refuse (409) or `replace=true` per the T4 stable-name rule. | 409 by default; `replace=true` only for the same subject, audited. |
| D-UP5 | XLSX: CSV only, or a server-side XLSX-to-CSV converter. | CSV only; a converter is a parser attack surface and a new dependency. |
| D-UP6 | Free-form sheets: may the analyst map columns in the UI? | No in the first cut: the Collector schema defines columns; mismatches quarantine. |
| D-UP7 | Retention / deletion of landed upload files. | Same as feed files; no analyst delete in the first cut. |
| D-UP8 | UI scope: route-only first (like `/import`) or an Investigation upload pane. | Route first, pane second under `angular-ui`. |

Decided as recommended above, with: D-UP3 granted by default to the roles that hold `canBuildLinkIndex` (operations, support, power, admin; super holds all);
D-UP7 no extra retention logic in v1 (landed files follow the Collector's existing retention - FOLLOW-UP); D-UP8 route first, UI pane later.

## 9. As built (2026-10-18)

- Route `POST /inv/uploads/{collector}?name=<file>.csv[&replace=true]`, `UploadRoutes` (la-api); capability `canUploadData` (Roles + `CapabilityManifest`); rate class EXPENSIVE (429); module.toon route + openapi fragment merged; basic edition: 503 with the other Investigation routes.
- **Collector flag:** `collector.accepts_upload: true` (new `ConfigSpecs` field, `PipelineConfig.Collector.acceptsUpload`). Only a LOCAL poll Collector, not Dataset-fed. The bridge `HostUploadLandingPort` (geo-link, `UploadLandingPort` in la-core) resolves it by Collector id or Pipeline name. Unknown / not opted in / not local all answer one 404.
- **Gate order as built** (cheap refusals before the body is read): capability 401/403 -> write root 503 -> name missing 422 -> path-shaped name 403 (`PATH_JAIL_VIOLATION`) -> Collector 404 -> charset / `.csv` / the Collector's include patterns 422 -> size 413 (declared length refused before reading; chunked refused after cap+1 bytes) -> empty / zip-XLSX / NUL / non-UTF-8 422 -> resolved path jailed 403 -> four-eyes 403 -> same name in inbox 409 (`replace=true` opts in) -> daily quota 413 -> atomic temp + move (`AtomicFiles`).
- **Deviation from section 4 gate 8:** classification/masking is NOT re-judged at upload (the Pipeline's schema and the Dataset read still mask exactly as for any Dataset). The gate is the existing D-U7 four-eyes ROW budget (`four_eyes_budget_above`, rows = lines in the file) via `InvRoutes.refuseIfSensitive`; no threshold set means no gate, as for every other D-U7 read.
- Limits: `LaLimit.UPLOAD_MAX_BYTES` (`upload.max_bytes`, 25 MiB) and `UPLOAD_DAILY_BYTES` (`upload.daily_bytes`, 200 MiB). Daily total is a per-Space ledger `<config root>/audit/la-uploads/<UTC day>.bytes` (counts landed bytes only; read-modify-write under one JVM lock, so a multi-node deployment would not share it - FOLLOW-UP).
- Audit: `EventType.LA_UPLOAD` per attempt after the capability gate (collector, file, bytes, sha256, outcome, refusing gate); never row content.
- Residuals / follow-ups: (1) the 409 sees only a file still in the inbox - a same-name file already ingested is caught by the Collector's own duplicate mode (checksum), not by the route; ~~(2) retention and analyst delete~~ DONE 2026-10-19; ~~(3) editor round-trip of `accepts_upload`~~ DONE 2026-10-19 (it was already preserved; now pinned); ~~(4) UI pane~~ DONE 2026-10-19; ~~(5) GET status~~ DONE 2026-10-19; (6) the lying-Content-Length case is covered by the declared-length and chunked tests, not by a raw-socket test.

## 10. As built: status, delete, retention and the pane (2026-10-19, `LA-I2-GAPS-RESIDUALS-1` item 1)

- **Records.** Each landed upload writes `<config root>/audit/la-uploads/records/<collector>/<file>.json` (file, bytes, lines, sha256, uploader id, landedAt, replaced; no content), pruned after 90 days. This is what "own uploads" and "who may delete" key on.
- **`GET /inv/uploads`**: the upload-enabled Collectors (id, Pipeline, include patterns, `maxBytes`; no paths). **`GET /inv/uploads/{collector}`**: the caller's uploads (all for `canAdminister`, or with no Subject), newest first, max 200, each with `state` (`pending` | `ingested` | `quarantined` | `consumed`), `rejectedRows`, `quarantineReason`; read through `UploadLandingPort.ingest` (marker, `_errors.csv` ledger, quarantine folder). Metadata only, so nothing to mask. Rate-classed expensive.
- **`DELETE /inv/uploads/{collector}/{file}`**: uploader or `canAdminister`; only while `pending`; 409 after pickup (ingested rows are never removed by this route); no record is a 404; audited as `LA_UPLOAD` (`la.upload.delete`, outcome `deleted` / `refused`, gate). The daily quota is not credited back.
- **Retention (D-UP7 stands).** The landed file follows the Collector's own retention after ingest; a pending file stays until ingested or deleted; only the record has its own 90-day retention.
- **Editor round-trip.** `PipelineEditable.toMap` / `lower` (the editor save path) carry every `collector:` key verbatim, so `accepts_upload` survives; `CollectorAcceptsUploadLiftLowerTest` pins it (unedited, after editing another Collector key, strict lower, absent and false). `PipelineLift` / `PipelineCompiler` do not carry the key but are not on the save path.
- **Pane.** `LinkAnalysisUploadsComponent` in the Investigation tab (below Identity resolution); capability `canUploadData` (`LensService.canUploadData`); wording and client checks in `upload-state.ts`; specs `link-analysis-uploads.component.spec.ts` (with axe), `inv-uploads.service.spec.ts`, `lens.service.spec.ts`.
- **Review fixes (2026-10-19, same day).** (1) `replace=true` is the uploader's or an administrator's act: another analyst gets 403 and the record keeps its ORIGINAL uploader (no takeover); the pane offers *Replace* only for your own waiting file. (2) "Picked up" also reads the fingerprint ledger (a checksum/metadata-mode Collector writes no marker and, with no backup dir, leaves the file in the inbox). DELETE now runs under the Pipeline's run claim (`CollectorService.whileIdle`, non-blocking: a run in flight answers 409 "being ingested"), re-checks the state, claims the file by an atomic rename to a `.tmp` name the poll never picks up, then deletes; every filesystem fault is an audited 409, never a 500. (3) Another analyst's upload answers DELETE with the same 404 as an unknown one; the record is matched by exact (case-sensitive) name and the stored spelling is audited; the status read sorts and cuts the page before probing, the quarantine walk is capped (20,000 entries), and records are pruned on read as well as on upload. (4) The SAFE_NAME gate audits as `name`, pinned by a test.
- **Showcase (operator 2026-10-19):** `spaces/_templates/la-showcase/config/telecom/crm_kyc_pipeline.toon` sets `collector.accepts_upload: true`, pinned by `LaShowcaseTemplateTest.theCrmKycCollectorAcceptsAnalystUploads`.
- **Still open:** item 2 of the backlog row (live HTTP drive in a browser); the lying-Content-Length raw-socket test; a multi-node quota ledger.
- Tests: `ControlApiAnalystUploadTest` (24), `CollectorAcceptsUploadLiftLowerTest` (3).
- Tests: `ControlApiAnalystUploadTest` (geo-link, 14 real-HTTP tests: capability 401/403/403, 503, 422 name, 403 jail, 404 x2, 422 pattern, 422 content, 413 declared + chunked, 413 quota, 403 four-eyes, 409 + replace, 429, audit + sha, atomic landing under a concurrent reader, end-to-end upload -> ingest -> identity import) plus the touched guards (`CapabilityManifestTest`, `AbsentModuleRoutesTest`, `LaLimitCatalogueParityTest`, `LinkAnalysisRateClassCoverageTest`, `ContractFragmentsTest`, `OpenApiPathsContractTest`, `ControlApiBasicEditionLinkAnalysisTest`).
