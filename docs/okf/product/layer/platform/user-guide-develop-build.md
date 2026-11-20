---
type: How-to
title: "On Inspecto — Build: build an App"
description: "The fourth page of the developer track: scaffold an App with the App kit, write its Space Template as configuration (or build it in a console Space and copy it in), validate it, drive Inspecto through the Control API, and add a Java extension only when configuration cannot do the job."
item: layer.platform
audience: external
status: current
verified: 2026-11-20 code-read
---

# On Inspecto — Build: build an App

## Goal

Turn your design ([Design](user-guide-develop-design.md)) into an App folder that the App kit's `validate` accepts,
whose Space Template runs on a development server — adding Java only where configuration cannot do the job.

## Before you start

| You need | Notes |
|---|---|
| your App's design | the finding and its key, the Datasets, the logic, the template tree, the Roles, the golden data |
| the **App kit** (`inspecto-app.mjs`) and Node.js | run from the Inspecto developer kit folder; today the kit is not shipped in customer bundles |
| a **development server** where you work as a builder who may also run Jobs (the run-operator capability, which the seed `power` role grants), and an administrator who creates Spaces there | any edition your App's Add-ons reach; Standard or above if it needs Incidents, Cases or Reconciliation |
| for Java extensions only: JDK and Maven | the scaffolds build offline |

The examples use the App id `acme-spend` in the folder `../acme-spend`; replace both with yours.

## Steps

### 1. Scaffold the App

```bash
node tools/inspecto-app.mjs new acme-spend --dir ../acme-spend --title "Acme Spend Watch" --addons incidents
```

Add `--industry telecom` for an App of an Industry. You see `✓ App 'acme-spend' scaffolded in … (10 files)`. The
starter is a working App: `app.toon`, one Space Template with one feed (a Pipeline, its schema and its Dataset), a
`sql.template` detection Job with its findings Dataset, golden data that plants three findings among look-alikes,
`conformance.toon`, and `docs/index.md`. It passes `validate` and `test` as created — keep it passing as you change
it.

### 2. Write the Space Template as configuration

Everything in `templates/<id>/config/` is TOON configuration — the same files a Space holds. There are two ways to
write it, and you can mix them:

- **Build it in the console, then copy it.** Create a Space on your development server, build the Pipelines,
  Expectations, Jobs, Alert Rules and Dashboards there with the console's editors — every save passes the safety
  check — and copy the Space's `config/` files into the template. Copy only what the App ships: leave out files that
  belong to one Space, such as backup Jobs and their Datasets. [Tutorial: ship it](user-guide-tutorial-ship-an-app.md)
  does this end to end.
- **Write the files directly**, following the starter's files and the layout in [Design](user-guide-develop-design.md)
  §4.

Then:

- add each Add-on you used to `requires.addons` in `app.toon`;
- put the Space's role table in `config/roles.toon`;
- replace the starter's golden data in `data/samples/<feed>/` with your own, and update `conformance.toon`: the
  `day`, the `jobs` and, per findings Dataset, its `key` and `expected` keys;
- add a zero-row `data/<dataset>/seed.parquet` for each Job-written Dataset an Alert Rule reads (copy one from a
  shipped template that has one) — the starter ships no Alert Rule.

### 3. Validate the App

```bash
node tools/inspecto-app.mjs validate --app-root ../acme-spend
```

It holds the App to the rules in [Requirements](user-guide-develop-requirements.md): the `app.toon` keys, the
templates' layout, and `conformance.toon` against the template's Jobs, Datasets and golden data. It exits 1 and names
every finding when something does not hold.

### 4. Run the template on your development server

Create a Space from your template on the development server — or keep working in the Space you built it in — and let
its Pipelines and Jobs run over the golden data. Two console tools help while you iterate:

- **Validate** in the Pipeline editor reports a graph's findings without saving it.
- **Dry run** runs sample rows through a Pipeline on a throwaway database and writes nothing.

### 5. Drive Inspecto through the Control API

Scripts, tests and integrations use the **Control API** under `/api/v1` — the same API the console uses. Its contract
is served by the server itself at `/api/v1/openapi.json` (OpenAPI 3.1); generate a client from it or read it. A
Space's routes are under `/api/v1/spaces/{id}/…`, and every answer carries its payload in `data`.

For example, list the Pipelines of a Space made from the worked example, *Order-to-cash assurance* — a Pipeline whose file does not load is still listed, with its `loadError`:

```check
GET /spaces/{space}/pipelines
as builder
expect status 200
expect data[*].name has orders
expect data[*].name has payments
```

On Standard and above, send an access token from your identity provider (`Authorization: Bearer …`); Basic has no
sign-in.

### 6. Add Java only where configuration cannot do it

Configuration covers most Apps. When yours needs a new *kind* of thing — a Step, a file format, a system to collect
from, a Job type — write a Java extension against the `@PublicApi` interfaces listed in
[Architecture](user-guide-develop-architecture.md). Start from a scaffold:

```bash
node tools/scaffold.mjs new step --id acme.redact --name "Acme Redact"
```

The kinds are `job`, `step`, `service`, `processor` and `nodetype`. Each writes a standalone Maven project, pinned to
the Platform version it was scaffolded from, with tests that run your code the way the engine does; its `README.md`
gives the commands. They load in one of two ways:

- **Packs — `job`, `step`, `service` — load without a restart.** Drop the jar into the packs folder
  (`-Djobs.packs.dir`) and list its SHA-256 in the allowlist file (`-Djobs.packs.allowlist`); an unlisted jar is
  refused. The engine loads it in its own class loader.
- **Classpath jars — `processor`, `nodetype` — load at the next restart.** They go on the server's class path, with
  no isolation.

Build against marked (`@PublicApi`) types only: they keep their shape within a major Platform version, and anything
unmarked may change in any release.

## Check it worked

- `validate` exits 0 and prints no finding.
- On the development server, the Space created from your template lists its Pipelines with no `loadError`, and its
  Jobs run to `SUCCESS` over the golden data. On the worked example:

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
retry 30 every 2s
```

## What can go wrong

| Symptom | Cause | Fix |
|---|---|---|
| `validate`: *requires Add-on 'x', which no Offering names* | a misspelt Add-on id | use the id the editions name, such as `caseManagement`, not `case-management` |
| `validate`: *nav entry … keys on feature …, which no module of its Add-ons provides* | a menu entry needs an Add-on the App does not require | add the Add-on to `requires.addons`, or drop the entry's `feature` |
| `validate`: *template 'x' … is a first-party template* | the template id clashes with a shipped one | rename the template folder and every reference to it |
| a Pipeline is listed with a `loadError` | the file does not load | read the message: it names the file and, where it can, the line |
| creating the Space is refused for an Alert Rule | the Alert Rule reads a Dataset no Job has written yet | ship the Dataset's zero-row `seed.parquet` (step 2) |
| a pack jar is not loaded | its SHA-256 is not in the allowlist | add it to the allowlist file |

## Next

- [Test your App](user-guide-develop-test.md)
- [Release your App](user-guide-develop-release.md)
