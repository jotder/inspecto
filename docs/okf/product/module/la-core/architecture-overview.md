# Link Analysis

> **Level 3 module page** (`SOLUTION-SUITE-DOCS-1`). Layer: Link Analysis.
> Offering: every edition from Basic (`linkAnalysis` add-on in `offerings/basic.toon`: `la-storage`, `la-core`, `la-api`, `geo-link`);
> Advanced adds `la-store-pg`. Basic withholds the Investigation surface (see `docs/EDITIONS.md`). Checked against the code on 2026-10-24.

## 1. What it is

Link Analysis (LA) lets an analyst explore who is connected to whom in platform Datasets (calls, transfers,
devices, accounts), record that exploration as an **Investigation**, run graph algorithms over it, and hand the
result on as a sealed dossier, a Case link or a standing detection. It is an optional, ServiceLoader-discovered
extension of the one host process - no LA server, no LA main class.

The full backend structure is in [Link Analysis backend architecture](architecture.md);
behaviour and decisions in the [feature concept](user-guide.md). This page is the
module-level summary.

## 2. Who uses it

- **Fraud and AML investigators** expand from a seed entity, pin evidence, and export a dossier.
- **Supervisors** approve sensitive expansions (four-eyes) and promote Drafts.
- **Space administrators** tune limits, masking and the link index.
- **External reviewers** read a time-limited public embed of an Investigation.

## 3. Where it sits

| Module (manifest id) | Path | Role |
|---|---|---|
| `la-core` | `la/inspecto-la-core` | Domain and ports: op-log evaluator, `InvestigationStore` port + filesystem store, Drafts, Graph Run service |
| `la-storage` | `la/inspecto-la-storage` | Versioned Parquet link index, `SqlGraphEngine` / `RoutingGraphEngine` |
| `la-api` | `la/inspecto-la-api` | The `/inv` and `/geo` routes (87), masking, Draft orchestration, gates |
| `geo-link` | `la/inspecto-geo-link` | Bridge to the host: `DatasetProvider`, `CasePort`, `CollectorCoveragePort`, `LinkIndexBuilder`, measures and alert-rule routes |
| `la-store-pg` | `la/inspecto-la-store-pg` | Postgres `InvestigationStoreProvider` (Advanced) |

They sit on two platform-base modules, `graph` (algorithms) and `entity-store` (Entity Types, Entity Lists,
identity facts, `LinkAnalysisSettings`). The engine names no `la-*` class; it reaches LA only through SPIs it owns.

## 4. How it works

![Link Analysis modules and read path](../../assets/link-analysis.svg)

1. **Investigation = op log.** Each action (`POST /inv/investigations/{id}/ops`) is appended to a hash-sealed log
   and folded deterministically into a **Working Set**. Undo, reorder and replay re-fold the log.
2. **Reads** come from the Dataset (flat recursive CTE in sandboxed DuckDB) or, when `index.enabled` (default on)
   and a fresh index exists, from the link index; a fallback always carries a closed `Reason`.
3. **Link index** (`IndexBuilder`, `IndexBuildService`): `la-index/<ds>/<mapping>/vN/` bucketed Parquet plus a
   manifest and `CURRENT` pointer; `full | append | compact` builds via `POST /inv/index/builds` or the
   `la.index.build` Job.
4. **Graph Run** (`GraphRunService`): algorithms from `GET /inv/graph/algorithms` over the Working Set
   (`InMemoryGraphEngine`) or the index (`SqlGraphEngine`), in a bounded pool.
5. **Drafts**: fork, ops, rebase, promote - parallel work on one Investigation without blocking the main log.
6. **Masking**: `EntityMasking` sends tokens out; raw values stay inside; reveal is audited.
7. **Outputs**: dossier and bundle (SHA-256 root, verify routes), Case link, Working Set measures, standing
   detection via the `la.detect` Job into [Alerts](../../addon/incidents/architecture-alerts.md), public embeds.
8. **Store**: filesystem by default; Postgres with `-Dinvestigations.backend=db` and a `jdbc:postgresql:` URL.


### 4.1 Added since 2026-10-19

- **Exploration alias book, persisted.** An exploration alias survives a restart or an eviction: each minted alias is
  appended to the Space's alias book in one write through an `APPEND` channel (two processes lose no line), a line is
  believed only when its alias is the token of its raw value, a per-file offset index makes a miss one line read, and a
  repeated miss on a book of unchanged size answers from a bounded negative cache ([architecture §3.4](architecture.md)).
- **Case history refuses raw keys under masking** (`LA-CASE-HISTORY-RAW-KEY-1`). `POST /inv/case-history` resolves a
  raw key only under masking mode `none`; under any other mode it answers `resolved:false`, the same as an unknown
  alias, so the route is no raw-id oracle.
- **Edition gates D-LAE1, D-LAE2, D-LAE5** (Standard and up). Case history moved to its own route module
  (`CaseHistoryRoutes`) and answers 503 on Basic; the Geo map, routes and co-location sit behind their own feature
  `geoMap`; the Link Analysis and Geo map dashboard Widget Types are hidden on Basic by the `/bootstrap` flag `laWidgets`
  (a UI flag only, not access control). See [`docs/EDITIONS.md`](../../../../EDITIONS.md).
- **Grow, Held tray and Exclude** (`LA-GROW-EXCLUDE-1`). Grow previews what the next step adds
  (`POST /inv/investigations/{id}/expand/preview`, `POST /inv/projection/neighbors/preview`), busy nodes are held in a
  tray, Exclude removes from this Investigation or adds to a list, and every recorded change is an existing op
  (`expand`, `exclude`, `excludeBy`, `undo`). **Category exclusion lists** kept by an administrator with `autoApply` become
  `excludeBy` steps when an Investigation is created; a restricted list's members are hidden from callers who may not
  read them, the Dossier included ([architecture §6.8, §6.9](architecture.md)).
- **Onboarding** (`LA-ONBOARD-1`, closed 2026-10-22): a **Getting started** page with a sample Space and a Space guide;
  **Add data** (the core `/onboard/files` import, every edition) writing `primaryEntityType` and `defaultLinkMapping`
  on the Dataset; **Start from...** one entity, a list, a paste or the whole Dataset through the neighbours read;
  the **Entities** page with its **Columns** tab to set what a column holds; **My work** (saved cases and what changed
  in imported data) and *Find something interesting*; help on every screen (*What can I do next?*, terms on first use)
  and local onboarding counters (`GET /onboarding/metrics`). Investigations need the runtime add-on
  `laInvestigations`; without it LA runs at the Explorer level with locked, not hidden, actions
  ([architecture §3.4, §6.10, §6.11](architecture.md)).
- **Analyst upload status and delete.** `GET /inv/uploads` (the Collectors that accept uploads),
  `GET /inv/uploads/{collector}` (own uploads with their ingest state; an administrator sees every upload) and `DELETE /inv/uploads/{collector}/{file}` (withdraw a file not yet picked up, under the Pipeline's
  run claim); a metadata record per upload is kept 90 days ([architecture §6.6](architecture.md)).

## 5. Configuration

- **Space**: `link-analysis.toon` in the Space write root (masking mode, index switch, SPACE-layer limits), edited
  through `PUT /settings/link-analysis` and `PUT /settings/link-analysis/limits`.
- **Install**: `la-limits.toon` (INSTALL-layer limits: Graph Run pool and ceilings, index threads, rate buckets),
  edited through `PUT /system/link-analysis/limits` (`canAdminister`). Catalogue: `LaLimit` in
  `platform/inspecto-entity-store`.
- **JVM**: `-Dinvestigations.backend=db` (Postgres store), `-Dla.embed.enabled` (public embeds).
- **Enablement**: Installed (jar) -> Enabled (`geoLink` per Space) -> Permitted (capability, membership, policy).
- Full limit table: [backend architecture section 9](architecture.md).

## 6. Screens

Moved to [the user guide](user-guide.md#screens).

## 7. API

Moved to [Link Analysis — API](api-link-analysis.md).

## 8. Interactions

- [Graph library](../graph/architecture-overview.md) - algorithms; [Scoring and Entity Lists](../scoring/architecture-scoring-entity-lists.md) - identity.
- [Case management](../case-management/architecture-overview.md) - Case link; [Alerts](../../addon/incidents/architecture-alerts.md) - standing detection.
- [Jobs scheduler](../processor/architecture-jobs-scheduler.md) - `la.index.build`, `la.detect`; [Storage](../entity-store/architecture-storage.md) - Datasets.
- [Exchange](../exchange/architecture-overview.md) - saved views shared across Spaces; [AI assist](../intelligence/architecture-overview.md).
- [Link Analysis solution](../../solution-pack/la-showcase/architecture.md).

## 9. Limits and gotchas

- Every bound is a configuration item; a stored value outside its range reads as the default.
- Expensive exploration POSTs are rate-limited per subject (burst 30, 1 per 2 s by default); Investigation routes
  are exempt.
- A view-backed Dataset cannot be fingerprinted, so the index serves it only while younger than
  `index.max_age_hours` (default 24).
- A stale-served `expand` may count hub degree over newer rows than it shows (documented exception).
- An index build waits while ingest runs and fails after 6 h of continuous ingest.
- The `la/inspecto-la-graph` directory in the working tree is untracked and not part of any Offering; not covered.
