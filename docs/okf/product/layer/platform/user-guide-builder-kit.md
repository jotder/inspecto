---
type: Feature
title: "Platform — the builder kit"
description: "For builders, integrators, OEMs and partner App authors: every way to extend Inspecto - Studio, the Pipeline editor, the Step, Collector, Consequence and Job type extension points, the Control API and its OpenAPI document, the Java embedding API, the App kit and the Add-on picker - with where each lives and the doc to read next."
tags: [platform, user-guide, builder-kit, extension, partner]
timestamp: 2026-11-11T00:00:00Z
---

# Platform — the builder kit

The **Platform product** (`products/inspecto.toon`) is Inspecto for people who build on it: builders, integrators,
OEMs and partners who make their own Apps. This page lists every part of the kit they use. For each part it gives
what you do with it, where it lives, and the page to read next. The deeper pages are not repeated here.

Pick the lowest level that does the job:

| You want to… | Use | Code to write |
|---|---|---|
| land a feed, transform it, check it, chart it | Studio and the Pipeline editor, in the console | none (TOON config) |
| ship that as a reusable, tested package | the **App kit** (`tools/inspecto-app.mjs`) | none (an App folder) |
| sell the console with only some Add-ons | the **Add-on picker** (`tools/pick-addons.mjs`) | none |
| add a step, a Job type, a Consequence or a processor | a **pack** or a classpath jar (`tools/scaffold.mjs`) | Java |
| reach a new kind of system | a **Collector** connector | Java, a provider jar |
| drive Inspecto from outside | the **Control API** (`/api/v1`, OpenAPI document) | any language |

## 1. In the console: Studio and the Pipeline editor

Both live in the **Builder Lens**. Its home page is `/pipelines`. The Lens only chooses where you start and what is
emphasised; your Role decides what you may do (see the [platform user guide](user-guide.md)).

- **The Pipeline editor** (`/pipelines`, open a Pipeline) edits a Pipeline's graph: its steps and their config, the
  parse and transform definitions, and the Collector it reads from.
  - Saving writes the graph through the Control API, past the shared config-safety check (`SaveGate`): a graph
    save with any ERROR finding is refused, and `/validate` reports the same findings without saving. (A smaller
    edit, such as a label or a rename, is refused only for an ERROR it introduces.)
  - A **dry run** executes the graph and writes nothing.
  - Read [the Pipeline editor guide](../../module/etl/user-guide-pipeline-editor.md), then
    [Pipelines](../../module/etl/user-guide-pipelines.md).
- **Studio** (`/studio`) is BI authoring: the Query Library, the Viz Library and Widget builder, the Dashboard
  builder, and dashboard templates. It also hosts Link Analysis, Entities and the Geo map.
  - Datasets are a Catalog asset now (`/catalog/datasets`); a bare `/studio` and `/studio/datasets` redirect there.
  - Read [the Studio guide](../product/user-guide-studio.md).

What you build here is TOON config in a Space. To reuse it elsewhere, put it in a Space Template, and to ship it
as a product, put it in an App (§5).

## 2. Extension points (Java)

Each extension point is a Java interface the core finds with `java.util.ServiceLoader`. You implement it and name
your class in `META-INF/services/<interface>` in your jar. Only some of these interfaces are marked `@PublicApi` yet
(§4); the table says which.

| Extension point | Interface | Registered in | Example in the repo |
|---|---|---|---|
| **Step** (a node a Pipeline runs) — `@PublicApi` | `com.gamma.pipeline.exec.StepExecutor` (runs it) + `com.gamma.pipeline.PipelineNodeType` (describes it: palette, mode, relations) | both service files | the scaffold `tools/templates/step/` |
| **Job type** — not marked yet | `com.gamma.job.JobTypeProvider` | its service file, plus `provides.jobTypes` in the module manifest | `recon.run`, `risk.score`, `screening.run`, `anomaly.score` in `features/` |
| **Consequence** (what a Decision Rule does) — not marked yet | `com.gamma.decision.ConsequenceProvider` | its service file | `invoke-api` (`features/inspecto-action-requests/…/InvokeApiConsequence.java`); built-ins in `BuiltInConsequences.java` |
| **Collector connector** — the factory is `@PublicApi`, the connector not yet | `com.gamma.acquire.CollectorConnectorFactory`, which makes a `CollectorConnector` | its service file, plus `provides.contracts` in the module manifest | Kafka (`providers/inspecto-connectors-kafka/…/KafkaConnectorFactory.java`); SFTP, FTP(S), S3, Azure Blob, GCS and DB export in `providers/inspecto-connectors` |

**Scaffolds: `tools/scaffold.mjs`.** `node tools/scaffold.mjs new <job|step|service|processor|nodetype> --id acme.score
--name "Acme Score"` writes a standalone, offline-buildable Maven project from `tools/templates/<kind>/`. It is pinned
to the engine version it was scaffolded from, and comes with tests that run your code the way the engine does. Each
template's `README.md` gives its commands. The kinds deploy in two ways:
- **Packs (`job`, `step`, `service`), no engine restart.**
  - The jar is dropped in the packs directory (`-Djobs.packs.dir`).
  - Its SHA-256 must be listed in the allowlist file (`-Djobs.packs.allowlist`); an unlisted jar is refused.
  - The engine loads it in an isolated classloader on a rescan.
  - A Job pack deleted from the folder is unloaded once its in-flight Runs finish; a service pack has no unload
    protection yet.
- **Classpath jars (`processor`, `nodetype`).** They run raw SQL on the batch connection, so they are not packs: the
  jar goes on the engine classpath and is picked up at the next restart, with no isolation and no unload.

A **module** is the in-tree form, for code that ships with an Edition: a Maven module with
`META-INF/inspecto/module.toon`. The manifest declares its `id`, its `offeringRole`, its `requires.modules`, and
what it `provides`: features, config kinds, contracts, Job types and routes. `ModuleManifestGuardTest` holds the
manifests to the code. Read [the module taxonomy](architecture-module-taxonomy.md).

Deeper reading:
- [node types](../../module/engine/spec-node-types.md) (Steps);
- [Decision Rules](../../module/processor/spec-decision-rules.md) and the
  [Action Requests spec](../../module/action-requests/spec.md) (Consequences);
- [the data plane plan](plan-data-plane.md) (Collectors).

## 3. The Control API and its OpenAPI document

Everything the console does goes through the **Control API** (`com.gamma.control.ControlApi`), served only under
`/api/v1`.

The contract is [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (OpenAPI 3.1).
- It is generated, never hand-edited: the merge of the core fragment and one `openapi.fragment.json` per
  route-owning module.
- To add a route, add its operation to your module's fragment and run `node tools/openapi-merge.mjs --write`.
- `OpenApiPathsContractTest` fails when a live route has no operation. `tools/check-openapi-fragments.mjs` and
  `ContractFragmentsTest` check the merge and each module's `provides.routes`.

Read [`docs/api/README.md`](../../../../api/README.md).

## 4. The Java embedding API (`@PublicApi`)

`com.gamma.api.PublicApi` (`platform/inspecto-api`) marks the types, methods and constructors that are promised to
code outside the repo. Its `since` attribute names the Platform version that introduced each one.
- **Marked today** among the §2 extension points: `StepExecutor`, `PipelineNodeType` and `CollectorConnectorFactory`
  (all `since = "4.0.0"`).
- **Not marked yet:** `JobTypeProvider`, `ConsequenceProvider` and `CollectorConnector`. A Job pack or a Consequence
  necessarily implements an unmarked interface, so pin your pack to the Platform version it was scaffolded from and
  re-run its tests on each upgrade.
- **Everything else unmarked** is internal and may change in any release.
- **No guard yet** holds the marked surface stable between releases.

Read [the layers architecture](architecture-layers.md).

## 5. The App kit: `tools/inspecto-app.mjs`

An **App** is a package of Space Templates, nav entries, docs and golden data that rides on a Product. A partner
makes one the way the core team does, in any folder outside `apps/` (D-PA18).

```bash
node tools/inspecto-app.mjs new acme-spend --dir ../acme-spend --title "Acme Spend Watch"   # scaffold
node tools/inspecto-app.mjs validate --app-root ../acme-spend                               # the Product guard's App rules
node tools/inspecto-app.mjs test --app-root ../acme-spend                                   # static conformance, every Edition
node tools/inspecto-app.mjs test --app-root ../acme-spend --bundle inspecto-deploy          # + boot and golden data (D-PA19)
node tools/inspecto-app.mjs keygen --key-id acme-signing-1 --out ../keys                    # your signing key (once)
node tools/inspecto-app.mjs package --app-root ../acme-spend --key ../keys/acme-signing-1.pem # signed <id>-<version>.zip
node tools/inspecto-app.mjs verify --package ../acme-spend/dist/acme-spend-0.1.0.zip --trust ../keys/acme-signing-1.properties
```

- **`new`** writes a working App in ten files:
  - `app.toon`;
  - a template with one feed (a Pipeline, its schema and its Dataset);
  - a `sql.template` detection Job with its findings Dataset;
  - synthetic golden data that plants three findings among look-alikes;
  - `conformance.toon`;
  - `docs/index.md`.
- **`conformance.toon`** names the template, the corpus day, the Jobs to run, and the exact keys each findings
  Dataset must hold.
- **`test --bundle`** boots a built bundle on loopback over a scratch Space, ingests the golden data, runs the Jobs and
  compares the key sets exactly.
  - On a Standard or Advanced bundle it leaves the OIDC sign-in jar off that test boot: conformance tests the App,
    not sign-in.
- **To build a bundle with your App:** `pwsh -File inspecto/package.ps1 -AppRoot ../acme-spend …`. The App rides on
  the Platform product as an optional App, and its templates are staged from its own folder.
- **The worked sample** is [`examples/partner-app/`](../../../../../examples/partner-app/) (App `acme-spend`). It is the
  kit's unedited output, and CI holds its conformance.
- **Not in the kit yet:**
  - installing a package on a running server (PA-7d). A server will install only a signed package whose key it trusts: the
    issuer key for first-party Apps, or a partner key listed in the install's `conf/app-signers.properties` (the line
    `keygen` writes to `<id>.properties`);
  - an Alert Rule in the starter (`APP-KIT-ALERT-RULE-1` on the board).

## 6. The Add-on picker: `tools/pick-addons.mjs`

For an integrator or OEM who sells the console with chosen Add-ons (D-PA21).

```bash
node tools/pick-addons.mjs                                                          # every Add-on: closure, Edition floor, price
node tools/pick-addons.mjs --pick incidents,screening --id acme-console --title "Acme Console" --write
pwsh -File inspecto/package.ps1 -ProductFile product-map/private/products/acme-console.toon -Edition Standard
```

- **The catalog** shows each Add-on with:
  - the other Add-ons its modules need (its closure);
  - the lowest Edition that builds it and that closure (its floor);
  - its list price, when `product-map/private/prices.csv` is present.
- **A pick** grows by its closure. It is priced as Edition + Add-ons and written to
  `product-map/private/products/<id>.toon`, which is gitignored.
- **The written file** is a picked Platform product (`narrows: inspecto`). It keeps the console, its templates and an
  optional App only when the pick holds that App's Add-ons. It builds in Inspecto's place, on the Editions from the
  floor up.
- **Not in the picker yet:**
  - a page (`ADDON-PICKER-PAGE-1` on the board);
  - trimming jars and templates to the pick, which stays with `customer-build`.

Read [the Product Map spec](spec-product-map.md).

## References

- The design and its decisions: the product assembly plan, `docs/superpower/2026-11-10-product-assembly-plan.md` (§8 and §11
  PA-6, D-PA18…D-PA21).
- Building and testing: [spec-build-test](spec-build-test.md).
