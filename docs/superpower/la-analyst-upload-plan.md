# LA analyst upload - bring your own spreadsheet (i2-gap 2, roadmap D4) - PLAN

Status: DESIGN ONLY (2026-10-18). Nothing built: every code slice depends on decision D-UP1 (see "Slice analysis").
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

## 7. Slice analysis (why nothing was built)

Every slice is decision-bound: the route needs a landing target (D-UP1); size cap and quota are policy numbers (D-UP2); the
capability is a role-model choice (D-UP3). A pure helper (name validator, counting limiter, zip sniff) would be speculative
code with no caller until D-UP1 lands. Decision-free slice: none. Once D-UP1..D-UP3 are answered the build is one
`UploadRoutes` class plus the checklist, roughly M.

## 8. Decisions owed (operator)

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
