---
type: Tutorial
title: "Tutorial: ship it — from a Space to an installed App"
description: "Turn the working Order-to-cash Space into a signed App with the App kit, prove it against its golden data, install it on a second server, then upgrade it to version 2 without losing the Space's data."
item: layer.platform
audience: external
---

# Tutorial: ship it — from a Space to an installed App

You have a working Order-to-cash Space on your own server: two feeds, Expectations, a Reconciliation, an Alert Rule,
a Case Rule and a Dashboard. In this tutorial you package that Space as an **App**, prove the package against its own
golden data, install it on a second server, and then ship version 2 of it to that server.

> **Today this is a command-line step.** The console cannot yet turn a Space into a Space Template or an App
> (`SPACE-TO-APP-CONSOLE-1`); you do it with the App kit. Installing and upgrading the App on a server is one request
> each.

**What you need**

| | Where | Edition |
|---|---|---|
| A **build server** with the Order-to-cash Space (the builder tutorials make it, or create it from the *Order-to-cash assurance* template with sample data) | the server you develop on | Standard or above |
| A **second server**, the one the App is installed on, signed in as an administrator | any server | Standard or above: the App needs the Incidents, Case Management and Reconciliation Add-ons |
| The **App kit** (`inspecto-app.mjs`) with Node.js, run from the Inspecto developer kit folder | your workstation | — |
| A Standard bundle folder on your workstation (unzipped, not running) | for the conformance test | Standard |

Read [App](../product/user-guide-concept-app.md) and [Space Template](../../module/config/user-guide-concept-space-template.md)
first if the words are new. The commands below are PowerShell; on Linux use `cp -r` and `mkdir -p` for the copies.

In the examples the build server's Space folder is `C:\inspecto\spaces\o2c`, the App goes into `C:\work\o2c-assurance`,
the second server is `https://inspecto-2.example.com`, and the API calls carry an administrator's access token from
your identity provider:

```powershell
$api = 'https://inspecto-2.example.com/api/v1'
$h   = @{ Authorization = "Bearer $token" }   # $token: an administrator's access token
```

## 1. Scaffold the App

From the developer kit folder:

```powershell
node tools/inspecto-app.mjs new o2c-assurance --dir C:\work\o2c-assurance --title "Order-to-cash assurance" --addons incidents,caseManagement,reconciliation
```

You see `✓ App 'o2c-assurance' scaffolded in C:\work\o2c-assurance (10 files)`. The folder holds:

- `app.toon` — the App's id, title, version, the Add-ons it requires, its templates and its menu entry;
- `templates\o2c-assurance\` — one Space Template, for now a one-feed starter;
- `conformance.toon` — what the golden data must produce;
- `docs\index.md` — the App's own page.

The template id is the App id, `o2c-assurance`. It must differ from every template the server already ships, which is
why it is not `order-to-cash`.

## 2. Put your Space into the template

Replace the starter with your Space's configuration and sample files:

```powershell
$space = 'C:\inspecto\spaces\o2c'
$tpl   = 'C:\work\o2c-assurance\templates\o2c-assurance'
Remove-Item -Recurse -Force "$tpl\config", "$tpl\data"
Copy-Item -Recurse "$space\config" "$tpl\config"
Remove-Item "$tpl\config\egress.toon"
New-Item -ItemType Directory "$tpl\data\o2c_open_items" | Out-Null
Copy-Item -Recurse "$space\data\samples" "$tpl\data\samples"
Copy-Item 'C:\inspecto\spaces\_templates\order-to-cash\data\o2c_open_items\seed.parquet' "$tpl\data\o2c_open_items\"
```

- `egress.toon` is the Space's own setting: the server writes one for every new Space, and an upgrade never ships it.
- `seed.parquet` is an empty open-items file. The Alert Rule reads the open items, and a Space cannot be created from
  a template whose Alert Rule reads a Dataset with no file yet. Take it from the bundle's own *Order-to-cash assurance*
  template (the `spaces\_templates` folder of your installation), not from your Space, whose open items hold your
  last Run.
- Look through the copied `config` and remove anything that belongs to this one Space rather than to the App, such
  as backup Jobs (`config\jobs\config_backup_job.toon`) and the `maintenance_backups` Dataset they write.

Then describe the template. Replace `templates\o2c-assurance\template.toon` with:

```toon
name: "Order-to-cash assurance"
version: 1.0.0
tagline: "Orders against payments: Expectations, a Reconciliation, Incidents, collections Cases and a Dashboard"
description: "Two CSV feeds (orders, payments) land through their Pipelines. Expectations check them, the Reconciliation o2c_orders_vs_payments finds unpaid, short-paid, orphan and duplicate orders, the Alert Rule o2c_unmatched_value raises one Incident per order with an unmatched value of 100 or more, and the Case Rule o2c_collections_case groups them into one collections Case per customer. Needs the Standard edition or above."
minEdition: standard
requiresModules[2]: case-management,ops
icon: heroicons_outline:banknotes
contents[4]: "2 Pipelines (orders, payments)", "4 Expectations and 1 Reconciliation", "1 Alert Rule and 1 Case Rule", "The o2c_overview Dashboard"
```

The template's `version` is what each Space records when it is created from it; the upgrade in step 7 compares
against it. In `app.toon`, set `version: 1.0.0`, and give the menu entry your own words:

```toon
nav:
  findings:
    path: data-browser
    label: Order-to-cash
    icon: heroicons_outline:magnifying-glass
    blurb: Orders, payments and their open items.
```

## 3. Say what the golden data must produce

The sample files are the App's golden data. Replace `conformance.toon` with:

```toon
template: o2c-assurance
day: 2026-07-01
jobs[1]: o2c_open_items
expectations:
  o2c_open_items:
    key: status
    expected[6]: paid,paid_late,short_paid,unpaid,duplicate_order,orphan_payment
```

The test lands every sample file through its Pipeline, runs the `o2c_open_items` Job, and requires the open items to
hold **exactly** these six statuses: a planted defect that stopped being found, or a new status, fails it.

## 4. Validate and test

```powershell
node tools/inspecto-app.mjs validate --app-root C:\work\o2c-assurance
node tools/inspecto-app.mjs test --app-root C:\work\o2c-assurance
node tools/inspecto-app.mjs test --app-root C:\work\o2c-assurance --bundle C:\bundles\inspecto-deploy
```

| Command | What it proves | You see |
|---|---|---|
| `validate` | the App's files are complete and consistent | `✓ …: the App validates` |
| `test` | the App assembles on every edition that has its Add-ons | `✓ conformance: assembled on standard, advanced, preview` |
| `test --bundle` | a real Standard server, started on loopback with a scratch Space from your template, lands the golden data and produces exactly the expected keys | `…; golden data held on C:\bundles\inspecto-deploy` |

The `--bundle` test leaves sign-in off for its private, loopback-only start: it tests the App, not sign-in. If you
change an expectation so it no longer matches, it fails and names what the Dataset holds instead.

## 5. Sign the package

An installation installs only a signed App whose signer it trusts. Make your signing key once, then package:

```powershell
node tools/inspecto-app.mjs keygen --key-id o2c-signing-1 --out C:\work\keys
node tools/inspecto-app.mjs package --app-root C:\work\o2c-assurance --key C:\work\keys\o2c-signing-1.pem
node tools/inspecto-app.mjs verify --package C:\work\o2c-assurance\dist\o2c-assurance-1.0.0.zip --trust C:\work\keys\o2c-signing-1.properties
```

- `keygen` writes the private key `o2c-signing-1.pem` (keep it private, never put it in the App folder or a
  repository) and `o2c-signing-1.properties`, the one line an installation adds to trust you.
- `package` writes `dist\o2c-assurance-1.0.0.zip` and says `signed`. Without `--key` it says the package is *NOT
  signed*, and no server installs it.
- `verify` checks the package the way a server will: `✓ …: App 'o2c-assurance' 1.0.0, … files, signed by 'o2c-signing-1'`.

## 6. Install it on the second server

On the second server, an administrator adds your signer once. Append the line from `o2c-signing-1.properties` to
`conf\app-signers.properties` in the installation folder (create `conf\` if it does not exist), then restart the
server. Then preview the install, and install:

```powershell
$v1 = 'C:\work\o2c-assurance\dist\o2c-assurance-1.0.0.zip'
Invoke-RestMethod -Method Post -Uri "$api/apps?dryRun=true" -Headers $h -ContentType 'application/zip' -InFile $v1
Invoke-RestMethod -Method Post -Uri "$api/apps" -Headers $h -ContentType 'application/zip' -InFile $v1
```

Both answer the App, its version, `signedBy: o2c-signing-1` and every template file it adds. The dry run changes
nothing. A package that does not verify, or whose signer is not trusted, is refused with 422 and the reason.

### Check it worked

The server lists the App as installed and your key among its trusted signers:

```check
GET /apps
as admin
expect status 200
expect data.installed[*].id has o2c-assurance
expect data.signers has o2c-signing-1
```

Now create a Space from it: **Settings → Spaces → New from template**, pick *Order-to-cash assurance* (the App's
template), give it an id, and **Create space**. Its sample data is loaded and each Pipeline runs once. The Space
records that it was made from version 1.0.0 of the App's template:

```check
POST /spaces
as admin
body {"id": "{space}", "template": "o2c-assurance", "loadSamples": true}
expect status 200|409
```

```check
GET /spaces/{space}/origin
as admin
expect status 200
expect data.template = "o2c-assurance"
```

## 7. Ship version 2

Version 2 adds a check that payments arrive on time and a tile that shows who pays late. In the App folder:

1. Add `templates\o2c-assurance\config\registry\expectations\payments_on_time.toon`:

   ```toon
   name: payments_on_time
   description: "Every order is paid by its due date. Read on the open items, where days_late counts the days after the due date"
   targetType: job
   target: o2c_open_items
   column: days_late
   kind: range
   max: 0
   severity: MINOR
   ```

2. Add `templates\o2c-assurance\config\registry\widgets\o2c_days_late_by_customer.toon`:

   ```toon
   vizType: bar
   datasetId: o2c_open_items
   controls:
     x[1]{field}:
       customer_id
     y[1]{field,agg}:
       days_late,max
   options:
     title: Days late by customer
   ```

3. Add it as the fourth tile of `templates\o2c-assurance\config\registry\dashboards\o2c_overview.toon`:

   ```toon
   name: o2c_overview
   tiles[4]{widgetId,span}:
     o2c_unmatched_total,1
     o2c_unmatched_by_status,2
     o2c_unmatched_by_customer,3
     o2c_days_late_by_customer,3
   ```

4. Set `version: 2.0.0` in both `app.toon` and `templates\o2c-assurance\template.toon`.

Then validate, test and package again, exactly as in steps 4 and 5. The package is now `o2c-assurance-2.0.0.zip`.

## 8. Upgrade the installed App

An upgrade replaces the App's template and menu on the server. It **never changes an existing Space**; it tells you,
for each Space made from the App, what the new version would change:

```powershell
$v2 = 'C:\work\o2c-assurance\dist\o2c-assurance-2.0.0.zip'
Invoke-RestMethod -Method Put -Uri "$api/apps/o2c-assurance" -Headers $h -ContentType 'application/zip' -InFile $v2
```

Add `?dryRun=true` to the address first to see the answer without changing anything.

The answer lists, per Space, each file as `new` (the Expectation and the Widget), `update` (the Dashboard, which your
Space has not changed since it was created), `customised` (a file your Space changed, left alone) or `dropped`.

```check
GET /apps
as admin
expect status 200
expect data.installed[*].version has 2.0.0
```

Now bring the Space up to version 2 (here the Space `o2c-live`). Read its proposal, then apply it:

```powershell
(Invoke-RestMethod -Uri "$api/spaces/o2c-live/origin/upgrade" -Headers $h).data
Invoke-RestMethod -Method Post -Uri "$api/spaces/o2c-live/origin/upgrade" -Headers $h -ContentType 'application/json' -Body '{"toVersion":"2.0.0"}'
```

The proposal says `fromVersion 1.0.0`, `toVersion 2.0.0` and lists each file. Applying it writes every `update` and
`add` through the same safety checks and audit trail as any configuration change, and answers `applied` (the
Dashboard, the Expectation and the Widget) and `recordedVersion: 2.0.0`. The Space's own `egress.toon` is listed as
`template-removed` and left as it is. A `customised` file is never overwritten:
change it yourself, through its own screen. To apply only some files, add `"files": [...]` to the body.

```check
POST /spaces/{space}/origin/upgrade
as admin
body {"toVersion": "2.0.0"}
expect status 200|409
```

```check
GET /spaces/{space}/origin
as admin
expect status 200
expect data.templateVersion = "2.0.0"
```

### Check it worked

The Space kept its data: nothing was re-ingested. Run the open items once and evaluate the new Expectation. It finds
exactly one late payment, order O-1012, paid 15 days after its due date:

```check
POST /spaces/{space}/jobs/o2c_open_items/trigger
as admin
expect status 202
save data.runId as run
```

```check
GET /spaces/{space}/jobs/runs/{run}
as admin
expect data.status = "SUCCESS"
retry 30 every 2s
```

```check
POST /spaces/{space}/expectations/payments_on_time/evaluate
as admin
expect status 200
expect data.lastResult.violations = 1
```

On the Dashboard `o2c_overview` the fourth tile, *Days late by customer*, shows the customer of O-1012.

## Which edition each step needs

| Step | Edition |
|---|---|
| 1–5, the App kit | none: it runs on your workstation; `test --bundle` needs a Standard bundle folder |
| 6, install; 8, upgrade | Standard or above, signed in with a role that may administer the server |
| Spaces from the App | Standard or above: the App requires Incidents, Case Management and Reconciliation |

## What you have now

A signed, tested App that any Standard server you trust can install, and a way to ship each new version to Spaces
already in use without touching their data or the files their builders changed.

## Read next

- [App](../product/user-guide-concept-app.md)
- [Space Template](../../module/config/user-guide-concept-space-template.md)
- [Edition and Add-on](../product/user-guide-concept-edition-addon.md)
- [The editions on one page](../../../../EDITIONS.md)
