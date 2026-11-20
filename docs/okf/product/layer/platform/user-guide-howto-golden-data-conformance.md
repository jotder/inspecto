---
type: How-to
title: "Add golden data and conformance to an App"
description: "Put sample files with a known answer into an App's template, state in conformance.toon exactly which records its Jobs must find, and hold the App to it with the App kit — on paper, and on a real bundle."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [tools/inspecto-app.mjs]
---

# Add golden data and conformance to an App

## Goal

Give your App **golden data** — sample files whose correct answer you know — and a **conformance** file that states
that answer, so every change to the App is tested against it before it ships.

## Before you start

- **Edition:** none for `validate` and `test`. `test --bundle` needs an unzipped bundle of any edition.
- **Who:** the App's developer.
- **What you need:** an App folder ([Create an App with the App kit](user-guide-howto-create-an-app.md)) and
  Node.js. For the bundle test, an unzipped Inspecto bundle on the same machine.

## Steps

1. Put the golden files in `templates/<template>/data/samples/<feed>/`, one folder per feed the template's Pipelines
   read. Use synthetic data with the mistakes you want found planted in it, and write down which records they are.
2. State the answer in `conformance.toon` at the App's root:

   ```toon
   template: o2c-assurance
   day: 2026-07-01
   jobs[1]: o2c_open_items
   expectations:
     o2c_open_items:
       key: status
       expected[6]: paid,paid_late,short_paid,unpaid,duplicate_order,orphan_payment
   ```

   `jobs` are run for `day` after the samples land; each entry under `expectations` is a findings Dataset, the
   column that identifies a record (`key`), and **exactly** the keys it must hold — no more, no fewer.
   `expected[0]:` means the Dataset must find nothing.
3. Test on paper — the App is assembled on every edition whose Add-ons cover it:

   ```powershell
   node tools/inspecto-app.mjs test --app-root C:\work\o2c-assurance
   ```

   It answers, for example, `✓ conformance: assembled on standard, advanced, preview`.
4. Test on a real bundle — the kit starts the bundle on a local port, lands the samples through each Pipeline, runs
   the listed Jobs and compares each findings Dataset with `expected`:

   ```powershell
   node tools/inspecto-app.mjs test --app-root C:\work\o2c-assurance --bundle C:\inspecto\inspecto-standard
   ```

   It ends with `golden data held on <bundle>`.

## Check it worked

Both `test` runs exit with 0. Once the App is installed, a Space made from its template holds the same findings after
the Job's run — here the open items of the Order-to-cash App:

```check
GET /spaces/{space}/datasets/o2c_open_items/rows?limit=500
as admin
expect status 200
expect data.columns[*].name has status
expect data.statistics.rowCount > 0
```

## What can go wrong

- `no conformance.toon - the golden expectations conformance holds the App to` — add the file at the App's root.
- `template '<x>' is not one of the App's templates` — `template:` must name a template listed in `app.toon`.
- `Job '<j>' has no config/jobs/<j>_job.toon in template '<t>'` — a listed Job does not exist in the template.
- `no data/samples/<feed>/` or an empty feed folder — every feed needs at least one golden file.
- `Dataset <ds>: <key> holds a, b; conformance expects exactly …` — the App finds something different from what you
  stated: fix the App, or the golden answer if it was wrong.
- `<dir>: not a built bundle (no lib/modules.list)`, or the bundle does not answer `/health` within about two minutes
  — point `--bundle` at the unzipped bundle folder, and free its port.

Conformance compares findings Datasets only. Expectations, Reconciliations, Alert Rules, Cases and Dashboards are not
compared, and the test builds its Space by copying the template's files — so also create one Space from the
installed App's template ([Install an App](user-guide-howto-install-an-app.md)) before you ship.

## Next

- [Package and sign an App](user-guide-howto-package-and-sign-an-app.md)
- [Tutorial: ship it](user-guide-tutorial-ship-an-app.md) — golden data for the Order-to-cash App, end to end.
