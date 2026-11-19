---
type: Capability
title: Observability & maintenance (OPS) — capability spec — Purpose, scope and requirements of record
description: Part 1 of 4 of Observability & maintenance (OPS) — capability spec: Purpose & scope; Requirements of record
status: current
item: module.observability
audience: internal
verified: 2026-11-19 last-edit
code: features/inspecto-observability
---

# Observability & maintenance (OPS) — capability spec — Purpose, scope and requirements of record

Part 1 of 4 of [Observability & maintenance (OPS) — capability spec](plan-observability.md) · next: [Specification](plan-observability-specification.md).

## 1. Purpose & scope

`OPS` is the platform's ability to **see what it did, prove who did it, bound what it keeps, and keep
itself healthy** — without shell scripts, OS cron, or an external monitoring stack being mandatory.

**In scope**

* **The signal ledger** — one append-only `EventStore` fed by a synchronous `EventLog`; the canonical
  `Signal` envelope riding on it as `EventType.SIGNAL`; the `/events*` read surface (search, live tail,
  saved views, CSV export) and the `/signals*` surface (query, causation tree, SSE stream).
* **Metrics** — the core `MetricRegistry` and the two exposure surfaces: the Prometheus text scrape
  (`/metrics`, an optional module) and the JSON acquisition snapshot (`/metrics/acquisition`, core).
* **The three-layer audit** — (1) per-run status/batch-audit ledgers, (2) per-run data-plane provenance
  rows, (3) the who-did-what `AUDIT`/`ACCESS_DENIED` trail captured by one dispatch interceptor, read
  through core `/audit/*` in every edition.
* **Provenance & conservation** — per-(node, relationship) record counts per run, the conservation
  invariant over them, the editor's edge-weight overlay, and `GET /lineage`.
* **Run reporting** — `/status`, `/report`, `/runs/{name}/report` (p50/p95/p99 over a date window), the
  live inbox/step gauges, `/health/details`, and the optional job-run reporting projection.
* **Maintenance & retention** — the `maintenance` Job Type's task library (core + the `inspecto-backup`
  and `inspecto-ops` contributions through `MaintenanceTaskProvider`), backup/restore, and the retention
  policy "nothing forgets by default".
* **Scheduler caps, health and the ops-plane knobs** — the D11 concurrency cap, the Consignment broker's
  weighted shares, `GET|PUT /system/scheduler`, `/system/operational-db`, `-Dops.timezone`, and the
  launch flags this layer reads.

**Out of scope (owned elsewhere)**

* Alert Rules, Notifications (channels, digests, receipts, suppression) and Incidents → `INC`
  ([`incidents/incidents.md`](../../addon/incidents/plan-incidents.md)). The ledger *carries* their events; the rules
  are theirs.
* The Job framework's authoring model — triggers, `when:` guards, the parameter contract, `$` tokens,
  Job Packs, `consignment.process` → `PIP` (execution) via
  [`okf/product/module/processor/spec-jobs.md`](../processor/spec-jobs.md). `OPS` owns only the
  *maintenance* tasks and the scheduler's *caps*.
* The agent-facing projections of the Signal Backbone (AG-UI, A2UI, context fabric, gated agentic write,
  S3–S7) → `AGT`. §3.1 stops at the envelope and the HTTP surface.
* Edition packaging (jlink runtime, `package.ps1`, the ServiceLoader mechanism itself) → `PKG`; the
  timezone *rules* for data → `DAT`; the operational stores' engines and roster → `DAT`
  ([`data-plane/data-plane.md`](../../layer/platform/plan-data-plane.md) §3).
* The Ops Lens as a surface (navigation, lens homes, read-only semantics) → `UI`.

## 2. Requirements of record

The board rows were `docs/okf/product/layer/platform/spec-requirements.md` §3.7 (that section was stripped to an index on 2026-09-09 — this file is their only home now) `OPS-1`…`OPS-6` plus the `EDITIONS.md` rows `OPS-01`…`OPS-08`,
`JOB-03`, `CP-13`, `SEC-09`. Below, each row is restated as the requirement actually holds, with the
correction the board needs. **Where a cell below says CORRECTION, the board was wrong on 2026-09-08 and
has been amended in the same commit as this file.**

| ID | Requirement (as it holds) | MoSCoW | Status of record | Edition of record |
|---|---|---|---|---|
| **OPS-1** | One **Signal** ledger; Events, Alerts and Notifications are *views* over it; live tail, saved views, CSV export | Must | ✅ SHIPPED (R4 unification 2026-07; Signal-primary flip 2026-07-18). **CORRECTION:** "live tail" is **client polling** (2–60 s, paused while the tab is hidden), not a push stream, with the server's SSE `GET /signals/stream` now consumed too — the Events pane subscribes through `EventsService.stream()` and keeps polling as the fallback (`CONSUMER-PAIRS-1`); there is no `/events/stream` | **Recording: All. Reading: S/E.** The feed routes (`/events*`) are the optional `inspecto-observability` module (EDG-01 cell 6, 2026-09-08); Basic answers `503` naming the module and the SPA shows an explained state. The board's bare `All` is half true |
| **OPS-2** | **Metrics**, Prometheus-compatible — throughput, error rate, lag, run durations | Must | ✅ SHIPPED. **CORRECTION:** the cell said the exposition is "core and ungated in every bundle; the gating is EDG-01 debt". **False since 2026-09-07** — EDG-01 cell 5 moved `GET /metrics` into `inspecto-observability`; on Basic the path answers `503` with no `# HELP` leak. `MetricRegistry` stays core. "Deliberately unauthenticated" is *true* (`ControlApi.PUBLIC_PATHS`) and is precisely **why** the ungated Basic exposure was the P1 that started EDG-01 | **S/E** for the scrape. `GET /metrics/acquisition` (JSON, core, **authenticated** — not in `PUBLIC_PATHS`) is **All**. ⚠ `BACKLOG.md` §5 said this row "says `All`" — it says `S/E`; struck 2026-09-08 |
| **OPS-3** | Three-layer audit: file/batch audit ledgers, provenance rows, an **append-only who-did-what Audit Log** | Must | ✅ SHIPPED. **CORRECTIONS (three):** (a) the log is **append-only by construction of the write path, and tamper-EVIDENT since 2026-09-27** (`ASSURE-AUDIT-CHAIN-1`: a per-Space hash chain plus MAC-signed daily anchors, checked by `GET /audit/verify`, `AuditVerifier`) — but not tamper-proof or immutable (`compliance/controls-matrix.md` AU-9). (b) **Sign-ins are audited**: besides mutating `POST/PUT/PATCH/DELETE`, export `GET`s and access-denied attempts, the `/auth/*` session routes write typed rows through `AuditTrail.authentication()` (category `authentication`, actions `auth.exchange` / `refresh` / `logout`). (c) The audit CSV export is **core** `GET /audit/export?format=csv` since 2026-09-08 (`AuditLogRoutes`), no longer a branch of `/events/export` | **All** (recording *and* reading — the one observability read that stays on Basic; EDITIONS §Audit) |
| **OPS-4** | Durable **Run reporting** — success rate, p50/p95 | Should | ✅ SHIPPED. **CORRECTION:** "off by default" is true of **one of three** ledgers only. The **batch-audit report** (`GET /status`, `/report`, `/runs/{name}/report`, p50/p95/**p99**, `?from&to`) reads the status store, whose backend default is **`db` since 2026-08-31** — always on. The **file run log** (`jobs_audit/jobs_runs.csv` + JSONL) is always on. Only the **job-run DB projection** (`/jobs/metrics|runs|failures`, T27) is off (`-Djobs.backend` default `none`, `404` until set) | All |
| **OPS-5** | Per-edge **Provenance** + conservation invariant → Alerts + an edge-weight overlay | Should | 🟡 PARTIAL — built, tested, **default off** (`-Dprovenance.backend` default `none`; `/provenance*` answers `404` until `duckdb`); verified against synthetic data only; the live soak is BACKLOG §2. **CORRECTION:** there is **no Sankey component** — the pipeline editor paints per-edge record counts as weight labels on the canvas edges (T17). The wording "Sankey overlay" is the design's name for that; the row keeps it, this file names what shipped | All |
| **OPS-6** | Record-level lineage & replay | **Won't (now)** | — per-batch ancestry is the accepted grain (`/runs/{name}/lineage`, `/lineage?store=`) | — |

**Edition-board corrections carried in this commit**

* `EDITIONS.md` **`JOB-03`** promised the whole task library on Basic (✅). Three tasks are *not* on
  Basic by design — `backup`/`backup_verify`/`restore` (`inspecto-backup`, cell 2) and `incident_purge`
  (`inspecto-ops`, cell 7) are **unknown tasks, refused loudly** there. The P cell is now 🟡.
* `EDITIONS.md` **`SEC-09`** "tamper-evident" → "append-only (not tamper-evident)". The `SEC` spec §5 had
  already recorded the mismatch and assigned it to `OPS`.
* `REQUIREMENTS.md` §3.7's heading "Observability & operations … UI Ops" carried the retired name; renamed
  to the §14 term.

**Non-functional requirements this capability carries** (from `REQUIREMENTS.md` §4, restated as they
hold): NFR-7's audit-retention window is **one year** (operator, 2026-08-30), applied by `event_prune`
**only when an operator schedules it** — "stated policy the code does not apply" until then (G5); a purged
Incident's history is exempt from that window by decision (MNT-14 G3, §4); no store is pruned by default.

## Changes

- 2026-11-01: OPS-3 corrections (a) and (b) — this cell said neither the tamper evidence nor the sign-in audit existed.
