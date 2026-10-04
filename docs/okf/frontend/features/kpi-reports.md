---
type: Feature
title: KPI & Reports
description: The operational KPI tiles + Reports gallery — run health, freshness, SLA — kept distinct from analytical Dashboards.
resource: inspecto-ui/src/app/modules/admin/kpi-reports/
tags: [feature, kpi, report, operations]
timestamp: 2026-07-07T00:00:00Z
---

# KPI & Reports

The gallery of **operational** reporting: **KPIs** (single-number Measures with a target/threshold,
rendered as headline tiles mini → standard → max) and **Reports** (run health, freshness, SLA).
Vocabulary ([`GLOSSARY.md`](../../../GLOSSARY.md) §7): a **Report** is an operational deliverable —
distinct from the analytical **Dashboards** authored in the [Studio](studio.md); a BI aggregation is a
**Measure**, never a "metric".

* KPI tiles bind to Datasets/Result Sets like any Widget; the gallery lists and previews them.
* **Target ownership (product sign-off 2026-07-22):** a KPI **target/threshold is a business acceptance
  criterion, authored by Business ON the Requirement** — not by the Builder inside the Component/dashboard
  that implements it (mirrors the "Business submits a Requirement, Builder delivers against it" pattern).
  A `kind: 'kpi'` Requirement (`POST /requirements`) carries optional `target` (+ `comparator`/`unit`)
  fields alongside title/description; the KPI Widget that satisfies it renders against that agreed bar.
* **KPI definitions** (`ASSURE-KPI-DEFINITIONS-1`, 2026-09-27): the pane lists the `kpi` components and its
  *New KPI definition* action (`canAuthorWorkbench`) opens `kpi-definition.dialog.ts`, a schema-form editor that
  saves through `/components/kpi` and shows the server's fail-closed refusal in place. A KPI tile binds to one
  with `options.kpi.kpiId` and then reads its numbers, target, direction, format and RAG band from
  `GET /kpis/{id}/value` — never the hand-set inputs, which apply only with no `kpiId` (a null value reads
  "No data", a failed read "KPI unavailable"). Contract: [Studio](../../capabilities/studio/studio.md) §3.6.
* ⚠ **Scheduled dashboard export is DISABLED (2026-10-04, `SCHEDULE-EXPORT-DIALOG-DEAD-1`).** The card's *Schedule export* button is `aria-disabled` with the tooltip "Scheduled dashboard export is not available yet": `ReportJob` never reads `reportKind`/`dashboardId` and its scope switch accepts only `status|batch|dataset`, so a saved schedule delivered nothing. Existing schedules still list/edit/delete. Follow-up: the dialog picks one Dataset (scope `dataset` + `out_dir`, csv/xlsx only); the real fix is a dashboard scope in `ReportJob`. The paragraph below describes the dialog's intended shape, not a working delivery path.
* **Scheduled exports** (C6, 2026-07-04): a schedule IS a [Job](jobs.md) — `type: 'report'` with
  `params: {reportKind, dashboardId, format, recipients}`; no separate entity. Dispatch keys on
  `params.dashboardId` presence, *not* on `type === 'report'` (that type predates C6 and covers other
  report jobs). CSV export is real (serializes the dashboard's tiles); PNG is real since BI-4 (backend
  `TablePngRenderer`, JDK-native Graphics2D — a 50-row table snapshot; CSV stays the full export).
  **PDF shipped 2026-07-20** as the backlog's PNG-wrapped-in-PDF fallback (no PDF library on the
  classpath, offline-blocked — see `PdfRenderer`, `inspecto-engine/src/main/java/com/gamma/job/`): reuses
  `TablePngRenderer.renderImage` for layout, then hand-writes a minimal single-page PDF (one Image
  XObject, FlateDecode + DeviceRGB, no text layer/fonts) — a snapshot like PNG, not a general-purpose
  PDF export. `ReportJob` dispatches `format: pdf` alongside `csv`/`png`/`json`.
* This pane is the **Business lens's home route** (`LENS_HOME.business = 'kpi-reports'`).
