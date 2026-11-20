---
type: How-to
title: "On Inspecto — Test: test your App"
description: "The fifth page of the developer track: prove an App with its golden data — the App kit's static conformance on every edition, the boot test against a built bundle, a real Space created from the template, the Expectations and Alert Rules conformance does not cover, dry runs, and the tests of a Java extension."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-read
---

# On Inspecto — Test: test your App

## Goal

Prove, before you release, that your App finds exactly its planted findings on its golden data, on every edition it
claims, and that a Space created from its template works — not just that its files are well-formed.

## Before you start

| You need | Notes |
|---|---|
| an App folder that passes `validate` | [Build](user-guide-develop-build.md) |
| golden data and its ground truth in `conformance.toon` | [Design](user-guide-develop-design.md) §7 |
| the **App kit** and Node.js | run from the Inspecto developer kit folder |
| one **built bundle folder per edition you claim** (unzipped, not running) | for the boot test |
| a **development server**, as a builder, and an administrator there | for the Space test and the checks the kit does not run; running Jobs and Expectations needs the run-operator capability (the seed `power` role grants it), creating a Space the administer capability |

## Steps

### 1. Static conformance on every edition

```bash
node tools/inspecto-app.mjs test --app-root ../acme-spend
```

It runs `validate`, then works out every edition whose build carries all of the App's required Add-ons and, for each,
assembles the product that would carry your App and checks it: the plan, the descriptor, and that each of your
templates is staged from your folder. It fails when *no* edition builds all of your Add-ons. It starts nothing.

### 2. The boot test: golden data against a real server

```bash
node tools/inspecto-app.mjs test --app-root ../acme-spend --bundle ../inspecto-deploy
```

With `--bundle`, the kit also boots that bundle on loopback over a scratch Space made from your conformance template,
with the golden data in its inboxes, and then:

1. triggers every Pipeline of the Space and waits for each run to succeed;
2. triggers each Job in `conformance.toon` for the corpus `day` (with the parameters `window_start`, `window_end` and
   `day` set to that day);
3. reads each findings Dataset and compares the set of values in its key column with `expected` — **exactly**: a
   missing key fails, and so does an extra one.

On a Standard or Advanced bundle it leaves sign-in off for this boot: the test proves the App, not sign-in. Run it once
per edition you claim, each with that edition's bundle.

### 3. Create a real Space from the template

The boot test builds its scratch Space by copying files, so it does not pass through the checks a server makes when a
Space is created from a template. Have an administrator create one on your development server too — **Settings →
Spaces → New from template**, with sample data — or over the API:

```check
POST /spaces
as admin
body {"id": "{space}", "template": "order-to-cash", "loadSamples": true}
expect status 200|409
```

A refusal here (for example an Alert Rule over a Dataset that does not exist yet) is a defect in your App, even when
the boot test passed.

### 4. Test what conformance does not hold

Conformance holds the **findings Datasets** only. Run the App's Expectations and Alert Rules yourself on the golden
data and compare them with your ground truth. On the worked example, once the golden payment files have landed:

```check
GET /spaces/{space}/runs/payments/files
as builder
expect status 200
expect data.length = 23
retry 30 every 2s
```

the Expectations over the feeds report the planted defects — one negative payment, two payments for unknown orders:

```check
POST /spaces/{space}/expectations/evaluate
as builder
expect status 200
expect data[2].name = "payments_amount_non_negative"
expect data[2].lastResult.violations = 1
expect data[3].name = "payments_order_known"
expect data[3].lastResult.violations = 2
```

and the Job that writes the findings runs over all of the golden data:

```check
POST /spaces/{space}/jobs/o2c_open_items/trigger
as builder
expect status 200|202
save data.runId as run
```

```check
GET /spaces/{space}/jobs/runs/{run}
as builder
expect data.status = "SUCCESS"
expect data.message matches /122 row/
retry 30 every 2s
```

```check
GET /spaces/{space}/datasets/o2c_open_items/rows?limit=500
as builder
expect status 200
expect data.columns[*].name has status
expect data.columns[*].name has days_late
```

[Tutorial: detect and respond](../../addon/incidents/user-guide-tutorial-detect-respond.md) shows the same for an
Alert Rule, its Incidents and a Case.

### 5. Dry-run Pipeline changes

While you change a Pipeline, use **Dry run** in the Pipeline editor: it runs sample rows through the Pipeline on a
throwaway database and reports row counts per Step and per output, writing nothing.

### 6. Test a Java extension

A scaffolded extension comes with tests that run your code the way the engine does; run them with Maven in its
project (`mvn -o test`, as its `README.md` says). Re-run them on every Platform upgrade.

## Check it worked

- `test` exits 0 without `--bundle`, naming the editions it checked.
- `test --bundle` exits 0 for each edition's bundle you claim.
- A Space created from the template on the development server is accepted, its Pipelines and Jobs succeed, and its
  Expectations and Alert Rules report exactly your ground truth.

## What can go wrong

| Symptom | Cause | Fix |
|---|---|---|
| *no Edition builds all of its Add-ons* | an Add-on no edition carries, or Add-ons no single edition carries together | check the Add-on ids and [The editions on one page](../../../../EDITIONS.md) |
| *Dataset x: key holds A, B; conformance expects exactly A, B, C* | the logic misses a planted finding, or catches a look-alike | fix the logic, or the golden data if the ground truth was wrong — never loosen `expected` to pass |
| *the bundle asks for sign-in (401)* | the boot reached a bundle with sign-in on | use a Basic bundle, or one with no identity provider configured |
| *Pipeline x: trigger answered …* or a run that is not `SUCCESS` | the feed's golden data does not parse with its schema | read the run's message; look at the quarantined rows in **Runs** |
| the boot test passes but creating a Space is refused | conformance copies files and skips the create-time checks | fix the template (step 3), then re-test |

## Next

- [Release your App](user-guide-develop-release.md)
- [Tutorial: ship it](user-guide-tutorial-ship-an-app.md)
