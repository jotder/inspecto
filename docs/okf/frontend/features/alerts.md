---
type: Feature
title: Alerts
description: Fired operational alerts in a pro data-table.
resource: inspecto-ui/src/app/modules/admin/alerts/alerts.routes.ts
tags: [feature, alerts, operations, pro]
timestamp: 2026-09-27T00:00:00Z
---

# Alerts

Route `/alerts` (Operations nav group). Lists fired alerts in a **pro** [data-table](../design-system/data-table.md)
(offline SQL editor + filter builder + the option to save a [rule](../design-system/rule.md)). Backed by
`AlertsService`.

**Alert Rules** are authored on this pane (schema-form dialog, `canAuthorAlertRules`-gated; rules persist
as `alert-rule` components under `<write-root>/registry/alert-rules/`). Vocabulary: an Alert Rule's `metric` field is an *engine/observability counter*
(`failed_batches` …), not a BI **Measure** — the ⛔ *Metric→Measure* ban targets the BI sense only, so the
operational column name stays.

**The Alert Rule editor authors two kinds** (`alert-rule-form.dialog` over `ALERT_RULE_ATTRIBUTES`, 2026-09-27,
`ASSURE-PER-ENTITY-ALERTS-RESIDUALS-1` item 1). A form-only **Watch** choice (`kind`, never written — the
engine's `AlertRule.fromMap` tells the shapes apart by `dataset`) picks a **Pipeline metric** (`metric` +
`window`, optional `when` over ledger columns) or a **Dataset Measure** (`dataset` via `datasetOptionLoader`,
`measure` checked by the shared `inspecto/query/measure-grammar.ts`, optional per-entity **One Alert per**
`by` — a `list` whose entries are suggested by `datasetColumnOptionLoader('dataset')` (`studio/datasets/dataset-column-option-loader.ts`: resolves the Dataset id, then `DatasetRowsService.columns` — declared columns, else a 1-row probe of its `physicalRef`/view/query; the old `columnOptionLoader` probed the Dataset id as a store name and 404'd, so no suggestion ever appeared — same fix for KPI `timeField` and Expectation `refColumn`) and checked as bare
identifiers — and **Storm cap** `stormCap`). Each kind's fields hang off `kind` by `dependsOn`, so a hidden
field is neither validated nor saved; the `when` tree is host-rendered and follows a `kind` signal fed from
the control's `valueChanges`. Rules of the save:
- A metric rule re-saves **unchanged** — no `kind`, `dataset`, `measure`, `by` or `stormCap` key is added.
- Both kinds' keys count as edited, so switching kind on an edit drops the other kind's keys from the PUT;
  keys the form does not model (a newer field) are still carried over.
- `stormCap` travels **only with a non-empty `by`** (the engine refuses it alone); a cleared `by` (`null`, or
  the list's "explicitly none" `[]`) removes both.
- The engine refuses `when` and `maximumAge` on a Measure rule, so the Measure kind offers neither. A
  freshness (`maximumAge`) or Investigation rule stays **edit-only** here: its target is shown read-only and
  kept as stored.
- A server refusal renders in the dialog (`<inspecto-alert variant="error">` with `apiErrorMessage`) — the
  save-time 422 for a `by` column the Dataset's Schema lacks names the column there. A 503 still shows
  the writes-disabled banner.

⚠ `type: 'list'` gained optionLoader suggestions for this (`schema-form`, non-flat rows only): an open
panel defers the list's blur-commit to the panel's `closed` event, because clicking an option blurs the
draft first and would otherwise commit the half-typed text as a second entry. Without a loader the draft
input has no combobox role and behaves as before.
🔴 That deferral once LOST an entry: with the panel open, a Save click runs before Material's outside-click
closes it, so the draft was never committed. `validate()` now runs `commitListDrafts()` first; `value()`
deliberately does not (hosts read it from template bindings every change detection, which would chip each
keystroke) — a host that saves without `validate()` must call `commitListDrafts()` itself.
**Threshold** has no default and must be > 0 (the engine's rule for every kind but freshness, whose
comparator/threshold are fixed and hidden); the comparator offers only `gt | gte | lt | lte`.

**Acknowledge / Resolve (MODULE-REORG-P7-INCIDENTS slice 3).** The fired-Alerts grid carries a **State** badge
(text, via the shared status badge) and per-row icon actions named `Acknowledge alert <title>` /
`Resolve alert <title>` (OPEN → both; ACKNOWLEDGED → Resolve only; none once RESOLVED or for an entry with
no `id` — an Alert the store de-duplicated into an open record). They are gated on `canWorkIncidents`
(`LensService`), ask no confirmation (like the Incident lifecycle verbs), and use `optimisticMutate`: the row
flips at once and rolls back on failure. Outcomes go to ONE polite live region above the grid
(`"<title> acknowledged"`; 403/404/422 as plain-language lines) which also **takes focus** after an action,
because the clicked button may have just left the row. A 403 drops the pane to read-only for the session; a
subject without the capability sees a permission note instead of the buttons.
