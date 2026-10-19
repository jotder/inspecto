---
type: Spec
title: Work tracking — one board, tagged rows, generated hub views
description: How open work and defects are tracked against Product Map Items, and the path a feature takes from plan to shipped docs.
tags: [backlog, product-item, docs, process]
---

# Work tracking — one board, tagged rows, generated hub views

Operator decision 2026-10-19 (`PRODUCT-DOCS-HUB-1`): keep **one** board of open work,
[`docs/BACKLOG.md`](../../../../BACKLOG.md), tag every row with the Product Map Item(s) it belongs to, and
**generate** each hub's view of it. A second hand-kept list per hub was rejected: hand-kept mirrors of real
state drift, every time.

## The row tag

Every ranked row in the board's §3–§5 ends its first line with:

```
[items: module.reconciliation, layer.product · kind: defect · hold: operator]
```

| Field | Required | Values |
|---|---|---|
| `items` | yes, ≥ 1 | Product Map Item ids — `<kind>.<name>` of a file `product-map/<kind>/<name>.toon`. Cross-cutting backend work uses `layer.platform`; UI-wide or whole-product work uses `layer.product`. |
| `kind` | no | `feature` (new or missing capability) · `defect` (something built behaves wrongly) · `chore` (measurement, live run, validation, upkeep) |
| `hold` | no — only when the row is not startable now | `operator` (waits on an operator call) · `external` (an upstream release, a real account, a real user) · `demand` (waits for a customer or consumer to ask) |

Defects live on the same board as rows with `kind: defect`. Never call one an "Issue" — in the product that
word means an Incident (`docs/GLOSSARY.md`).

The tag sits at the end of the line so it does not disturb the other board parsers: the row-head guard
(`check-board-heads.mjs`) reads only the head clause, the owning-doc guard (`check-backlog-homes.mjs`) reads
only the backticked `→` pointer list, and the count markers (`check-doc-counts.mjs`) count only the
`- **Pn**` prefix.

## Guard and generator

- `node tools/check-backlog-items.mjs` (CI guards job, with `check-backlog-items.test.mjs`): every §3–§5 row
  has the tag with at least one item, every item exists in `product-map/`, `kind` and `hold` are from the
  closed lists, and no other field appears. A floor on the parsed row count keeps a grammar change from
  silently passing over nothing.
- `node tools/render-product-docs.mjs` writes an **Open work** section into every hub `index.md` (row, priority,
  kind, title, hold) and an open-row count per hub into `docs/okf/product/index.md`. The title is the row's bold
  headline, or its first clause. `--check` fails when any of it is stale, so a board edit that is not followed by
  a re-render goes red in CI.

## The feature path

1. **Plan** — write the plan in `docs/superpower/` and file its board rows, each tagged with its item(s).
2. **Build** — commit with the item's name as the Conventional Commit scope (`feat(reconciliation): …`).
3. **Ship** — distil the as-built facts into the hub's `plan` / `spec` / `architecture` / guide slots, delete
   the closed rows from the board (still-open leftovers become new tagged rows), and `git mv` the plan to
   `docs/archived-documents/plans-archive/`.
4. **Regenerate** — `node tools/render-product-docs.mjs`; the hub index, its Open work section and the hub list
   refresh themselves. Never hand-edit a hub `index.md`.
