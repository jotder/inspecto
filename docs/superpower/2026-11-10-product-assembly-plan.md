# Product assembly — one Inspecto Platform, Industry Apps, finished Products (plan)

*Status: APPROVED 2026-11-10. The operator affirmed the direction ("the plan holds") and signed every decision in §12
as recommended. ✅ PA-0 done 2026-11-10 (the words are in `GLOSSARY.md` §15 *Product assembly*); ✅ PA-1 done 2026-11-10 (§11
as-built note); ✅ PA-2 done 2026-11-10 (§11 as-built note, with the package runs that prove it); ✅ PA-3 done 2026-11-10 (§11
design and proof); ✅ PA-4 done 2026-11-10 (§11 design and proof); PA-5 is next. Product Map Items: `layer.platform`, `layer.product`, `edition.basic`,
`module.config`, `module.entity-store`. Board row: `PRODUCT-ASSEMBLY-1`.*

## 1. Goal

The operator, 2026-11-10:

> We have an Inspecto Platform, capable of all ELT, operations, pipelining, the Event → Alert → Incident/Case trio, Link
> Analysis, Reconciliation, BI and more. There would be Apps for a vertical domain like Telco (RA, FM, LA, CM, CEP …).
> Be ambitious about how we can assemble and package a **finished domain Product for the end customer**, and **the
> Platform with chosen capabilities**.

Done means:
- **A finished domain Product** (for example *Inspecto Telco* with Revenue Assurance, Fraud Management, Link Analysis
  and Case Management) is assembled from **definition files**: one Product, its Apps, one Industry. It is packaged for
  every Distribution (demo, trial, production; zip, container, air-gapped) and booted in CI. No new Java, and no
  `if product == …` anywhere.
- **The Platform with chosen Add-ons** is assembled from the same machinery, together with what a builder, systems
  integrator or OEM partner needs to make their own Apps.
- A customer build is the same assembly with that customer's entitlements. It cannot drift from the Product it came from.

## 2. What exists today (measured 2026-11-10)

The platform already composes builds from parts. Each layer works; what is missing is the level above them.

| Layer | Where | What it decides |
|---|---|---|
| Module manifest | `META-INF/inspecto/module.toon` per module (48) | what a module provides and requires; boot resolves the graph, an unmet module stays inert |
| Offering | `offerings/{basic,standard,advanced,preview}.toon` | modules, Add-ons, content packs and posture (auth mode, stores, Postgres sidecar) per Edition; `includes` chains Basic → Standard → Advanced → Preview |
| Industry pack | `platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs/{telecom,mobile-money}/` | terms, Entity Types, link kinds and record types of a vertical; today used by Link Analysis only (a Space's **Domain Profile**) |
| Space Templates | `spaces/_templates/*` (30) | the content: `telco-ra`, `telco-fraud`, `telco-bypass`, `partner-settlement`, `telco-rerating`, `business-assurance`, `aml`, `payment-fraud`, `mobile-money`, 20 `la-*` showcases, … |
| Product Map | `product-map/<kind>/<name>.toon` | the commercial tree: billable items, Order Codes, billing model, support tier |
| Packaging | `inspecto/package.ps1` (2,850 lines), `tools/offering-classpath.mjs`, `tools/bundle-modules.mjs` | one bundle per `-Edition`, plus `-Ui gamma\|la-app` and `-DemoAuth` |
| Customer build | `tools/customer-build.mjs` | trims an Edition bundle to what one customer bought |
| Shells | `inspecto-ui/src` (the console, "gamma") and `inspecto-ui/projects/la-app` | two hand-built Angular hosts |

**A second Product already exists, but only by convention.** *Inspecto LA* (`GLOSSARY.md`: *Inspecto LA* is the
product, *Link Analysis* the capability) is assembled out of pieces that each encode it separately:

- `offerings/basic.toon` lists the LA modules and **20 `la-*` content packs**, so the **Basic Edition is in practice
  the LA Product**. "Basic" therefore means two things: the smallest tier, and Link Analysis. `DATA-TIERS-1` is now
  making Basic a data tier as well, which adds a third meaning.
- `package.ps1:109` hard-codes `[ValidateSet('gamma', 'la-app')]`. The pre-seeded demo Space is special-cased to
  `-DemoAuth -Ui la-app` (`package.ps1:2584`, `tools/check-bundle-spaces.mjs`).
- The brand is TypeScript (`la-brand.ts`, `AppEnvironment.productName`). The nav is a TypeScript constant
  (`la-nav.ts` `LA_APP_NAV`), gated by `/bootstrap` features.
- `la-app/app.config.ts` copies the console's provider set by hand ("mirrors the gamma host's `app.config`").

**The build machinery is per-Edition and partly kept twice:**

- **Two module tables kept in lockstep.** `tools/offering-classpath.mjs` resolves an Offering (its `includes` plus the
  `requires` closure) and **throws unless the result equals `tools/bundle-modules.mjs`**. That second, hand-kept
  table also holds the `from:` floors, `CORE_MODULES`, the `EDITIONS` enum, the demo jar swap and when the Postgres
  sidecar ships.
- **The Java runtime never reads an Offering.** At run time an Edition is just the jars present plus
  `lib/edition.properties`. `EditionFeatures` refusal texts name Editions in literal strings, and the launchers
  hard-code posture by Edition name (OIDC unless Basic, Postgres objects for Advanced).
- **`contentPacks` stage nothing.** Only `tools/check-offerings.mjs` reads them. `package.ps1` copies **every** template
  under `spaces/_templates` into every bundle.
- **`customer-build.mjs` derives a template's needs from two folders only** (`config/jobs/*` and `config/registry/`).
  It misses Case Rules in `config/ops/` and report templates in `config/regulatory-report-templates/`.
- The four Edition names are repeated in about eight places: the `bundle-modules` enum, the `package.ps1`
  `ValidateSet`, the pom profiles, the launcher `case`, the jlink lock sections, `check-offerings` `EDITION_OF`, the
  `EditionFeatures` strings and the docs.
- **Every telco template brings its own feeds.** `telco-ra` ships "canonical synthetic schemas" for switch, mediated and
  rated xDRs, invoices and tariffs, and the other telco templates carry theirs. The 2026-11-08 industry-Spaces plan found
  `company`, `payment` and `mule` feeds duplicated across the banking showcases. Nothing lets two Apps share one
  ingest of the same CDRs.

## 3. The model — five layers, two Product shapes

```
                       ┌───────────────────────── Product ─────────────────────────┐
  Domain product:      │ Inspecto Telco = Industry telecom + Apps {RA, FM, LA, CM}  │  × Edition × Distribution
  Platform product:    │ Inspecto Platform = Base + chosen Add-ons + builder kit    │    × Entitlement
                       └────────────────────────────────────────────────────────────┘
  App          RA · FM · LA · CM · CEP · Partner settlement · Re-rating · Bypass …   (finished job, one Industry)
  Industry     telecom: collectors, ASN.1 parsers, canonical Datasets, Entity Types, link kinds, terms
  Add-ons      Incidents · Case Management · Link Analysis & Investigations · Reconciliation · Scoring & Lists ·
               Screening · Anomaly · Predictive · AI Assist · Regulatory Reporting · Multi-entity · Workflow & SLA ·
               Premium Connectors · (new) Streaming
  Base         acquisition · ingestion · Pipelines · data plane · Studio/BI · observability · Jobs · Control API · security
```

| Layer | What it is | Today | New |
|---|---|---|---|
| **Base** | the modules every build has (Offering role *Base*) | the `base` / `internal` modules | nothing |
| **Add-on** | a horizontal, domain-neutral Optional module set | `addons:` in `offerings/*.toon`, Product Map `addon/` | `Streaming` (for CEP) |
| **Industry** | a vertical's shared spine: Collectors and record formats, parsers, **canonical Datasets**, Entity Types, link kinds, reference data, terms | Industry packs `telecom`, `mobile-money` (LA-only; no Datasets) | widened to own the canonical Datasets every App of that Industry reads |
| **App** *(new)* | a finished job for one Industry: content mapped onto the Industry's Datasets, its own landing and nav, personas mapped to Roles, golden data, and the Add-ons it needs | scattered: Space Templates (`telco-ra`, `telco-fraud`, …) plus, for LA only, a hand-built shell | `apps/<id>/app.toon` |
| **Product** *(new)* | what a customer receives. **Domain product**: one Industry plus chosen Apps. **Platform product**: the Base modules plus chosen Add-ons plus the builder kit | implicit (`-Ui`, Basic = LA) | `products/<id>.toon` |

Three axes run across every Product:
- **Edition**: the posture tier (Basic · Standard · Advanced). It no longer carries content.
- **Distribution**: how a build ships. The kind is `demo` (golden data, Demo Users, guided tour), `trial` (time-boxed),
  `pilot` (customer Collectors pre-wired, no data) or `production`. The target is `windows` · `linux` · `container`
  (OCI image + Helm chart) · `airgap`, and later a SaaS tenant template.
- **Entitlement**: what one customer's build may switch on, signed, checked at run time.

**Vocabulary (binding; ✅ in `GLOSSARY.md` §15 *Product assembly* since 2026-11-10).** *Capability* is already taken
twice: an RBAC permission (§1-A) and the fifteen capability areas (§14). So **the horizontal sellable unit stays
*Add-on***, and the always-present layer is the **Base** modules (the existing Offering role). The first draft of this
plan said *kernel*; PA-0 replaced it, because *kernel* belongs to the **Decision Kernel**. Sales copy may say
"capabilities" only as plain English, never as a name. The vertical is **Industry**, matching the existing *Industry
pack*. The new terms are **App**, **Product** (with its two shapes **Domain product** and **Platform product**),
**Suite**, **Distribution**, **Entitlement**, **Assembly Plan** and **Product lock**. ⛔ An App is not a Space Template
(an App installs one or more), not an Add-on (horizontal, no Industry) and not a Function pack (content only, no
landing or nav). An item outside a customer's Entitlement fails the **Installed** gate, so the gates stay three.

### 3.1 The first Domain product: Inspecto Telco

| App | Job | Built today | Add-ons it needs | Gap before it is an App |
|---|---|---|---|---|
| **RA** Revenue Assurance | completeness, re-rating, roll-forward and settlement controls | `telco-ra` (8 Pipelines, 2 Reconciliations, 11 Jobs, 2 Alert Rules), `business-assurance` | Reconciliation, Incidents | read the Industry's Datasets instead of its own feeds; landing (leakage, open Incidents, KPI roll-up) |
| **FM** Fraud Management | subscription, usage and interconnect fraud | `telco-fraud`, `telco-bypass` (B0–B3) | Incidents, Scoring & Lists, Anomaly | `ASSURE-PACK-TELCO-FRAUD-RESIDUALS-1`; `TELCO-BYPASS-SIMBOX-1` B4–B7; IRSF/Wangiri (`PACK-IRSF-WANGIRI-1`, plan only) |
| **LA** Link Analysis | investigate how subscribers, devices, cells and partners link | Inspecto LA, `la-telco-ra` showcase, `telecom` Industry pack | Link Analysis & Investigations | its shell becomes an App inside the suite (§7); the 14 Basic UI defects |
| **CM** Case Management | one Case desk for every App's Incidents | `case-management` module, Case Rules | Incidents, Case Management, Workflow & SLA | Case Rules in `config/ops/` must be declared (`PRODUCT-MAP-CUSTOMER-BUILD-GAPS-1`) |
| **CEP** Complex event processing | detect patterns on live streams in seconds | Kafka Collector (`connectors-kafka`); the engine is batch only | **Streaming** (new) | the largest gap: `REALTIME-DECISIONING-1` has no design yet (PA-9) |
| Partner settlement | interconnect and roaming settlement | `partner-settlement` S0–S8 | Reconciliation | W1–W3 |
| Re-rating | tariff validation | `telco-rerating` R0–R3 | Reconciliation, Incidents | R4–R7 |
| Group & regulator roll-up | one view across operating companies, MVNOs and a regulator | Multi-entity Add-on, Regulatory Reporting | Multi-entity, Regulatory Reporting | a roll-up App; a proposal exists in a local working set |
| 5G / IoT assurance | slice and device-fleet assurance | — | Reconciliation, Anomaly | the U0 spike (`PACK-5G-IOT-ASSURANCE-1`) |

The same shape gives **Inspecto Financial Crime**: AML, Screening, payment and mobile-money fraud, Case Management and
LA over the `mobile-money` Industry. **Inspecto LA** stays a single-App Domain product for buyers who want only
investigations.

## 4. The definitions

Each definition names ids that tools resolve; none copies a technical fact (the Product Map's decisions D-PM1 and D-PM7).

**Industry** (`industries/telecom/industry.toon`, absorbing the entity-store Industry pack):

```
id: telecom
title: Telecom
entityTypes: industry-pack-entity-types.toon
linkKinds: industry-pack-link-kinds.toon
recordTypes: industry-pack-record-types.toon
terms: industry-pack-terms.toon
collectors[3]: switch-xdr,mediation-xdr,billing-invoice
datasets[6]: voice_xdr,data_xdr,sms_xdr,rated_usage,invoice,tariff
requires:
  modules[1]: telecom-asn1
```

**App** (`apps/telco-ra/app.toon`):

```
id: telco-ra
title: Revenue Assurance
industry: telecom
version: 1.4.0
platform: ">=5.0 <6"
requires:
  addons[2]: reconciliation,incidents
consumes:
  datasets[5]: voice_xdr,data_xdr,rated_usage,invoice,tariff
templates[1]: telco-ra
handoffs[1]{on,to}:
  incident.opened,case-management
personas[2]{role,landing}:
  ra-analyst,ra-overview
  ra-manager,ra-kpis
nav[4]{path,label,icon,feature}:
  ra/overview,Overview,scale,
  ra/controls,Controls,check-badge,
  ra/reconciliations,Reconciliations,arrows-right-left,
  ra/leakage,Leakage,banknotes,
golden: golden/
```

**Product** (`products/telco.toon`):

```
id: telco
title: Inspecto Telco
shape: domain
industry: telecom
apps:
  required[2]: telco-ra,case-management
  optional[6]: telco-fraud,link-analysis,cep,partner-settlement,telco-rerating,group-rollup
editions[2]: standard,advanced
brand:
  name: Inspecto Telco
  logo: brand/telco.svg
distributions[4]: demo,trial,pilot,production
targets[4]: windows,linux,container,airgap
docs: docs/okf/product/product/telco/index.md
```

A **Platform product** names Add-ons instead of Apps (`shape: platform`, `addons: {required, optional}`) and adds the
builder kit (§8).

Constraints the guard enforces:
- **An App's `consumes` must be provided by its Industry.** That is what lets RA, FM and LA share one ingest of the
  same CDRs.
- **An Edition the Product does not list is refused** at assembly, with the reason. No silent subset is built.
- **A nav entry is offered when it is in the Product, Installed, Enabled and Permitted** (`GLOSSARY.md` §15's three
  gates, plus the Product). `/bootstrap` returns the resolved nav, so no shell holds a nav constant.

## 5. The assembler

```
node tools/assemble.mjs --product telco --apps telco-ra,telco-fraud,link-analysis,case-management \
     --edition advanced --distribution production --target container [--customer <id>]
```

It resolves **Product → Apps → Industry + Add-ons → modules** like a package manager. Version ranges are checked
(`platform: ">=5.0 <6"`), the module closure is computed from the manifests, and an unmet need is a refusal that
names it. The output goes to `target/assembly/`:

- **An Assembly Plan** (JSON): modules, classpath order, the UI host and App libraries to build, templates and golden data
  to stage, Spaces to seed, docs and the SBOM scope. `package.ps1` **executes a plan** and no longer decides one.
- **A Product lock** (`products/<id>.lock`, committed for every released Product): the exact versions and content
  hashes of every module, App, Industry and template. It gives reproducible builds, an exact per-Product SBOM, and an
  **upgrade diff** per customer ("going 5.2 → 5.3 changes these Apps, these Datasets, these Alert Rules").
- **The bundle per target**: a Windows or Linux zip, an OCI image plus a Helm chart per Product, or an air-gapped bundle
  (jlink runtime, DuckDB extensions, everything offline).

The duplication in §2 ends here:
- **The Offering becomes the only module table.** `bundle-modules.mjs` is generated or deleted.
- **Content is staged from the Product and its Apps.** A Telco bundle stops shipping the LA showcases, and the reverse.
- **Posture comes from the Offering's `posture:` block,** written into `edition.properties` and read by the launchers.
- **The Edition names live once,** in the Offerings.
- `-Ui`, `-DemoAuth` and the la-app demo-bake branch become plan fields. There are no aliases (the operator's standing
  "breaking changes free, no shims").
- **`customer-build.mjs` becomes `--customer`**: the same resolution, restricted to the customer's entitlement, then
  signed (§6).

## 6. Run time — Apps share one spine and hand off through the trio

- **Ingest once, use in every App.** The Industry owns the Collectors and canonical Datasets; Apps read them through
  their `consumes:` contract. Installing FM next to RA adds Alert Rules and views, not a second copy of the xDR feeds.
- **Hand-offs ride the Event → Alert → Incident → Case trio.** An App publishes Alert Rules and Consequences, CM owns
  the Cases, and LA opens an Investigation from a Case (the inbound Investigation route `POST
  /inv/inbound/investigations` is the first such contract). RA reads the Incident's Impact ledger for value at risk.
  The Product declares which hand-offs it wires; `check-products.mjs` proves both ends are present.
- **Apps are versioned packages that install into a running Platform as well as at build time.** An admin-only
  `POST /apps` takes a signed App package. It runs a compatibility check and a dry-run diff (Datasets, Alert Rules,
  Jobs it adds), then installs. Upgrade carries migrations. Removal uses the existing inert-config semantics, so nothing
  the customer authored is dropped. The **Installed · Enabled · Permitted** gates extend to Apps.
- **Entitlements become real.** A signed entitlement file (the reserved `entitlementKey`) lists the Apps, Add-ons,
  Edition, node count and expiry. It is checked at boot and on each gated route, which generalises today's single
  `runtime` enforcement (`-Daddons.laInvestigations`). A `trial` Distribution is simply an entitlement with an expiry.

## 7. Experience — one suite shell per Product

- **A suite shell per Domain product**: an App switcher; one **My work** inbox across Apps (Alerts, Incidents, Cases,
  Investigations); unified search over the Industry's Entities; each App's own landing; personas mapped to Roles; the
  Product's brand. Today's `la-app` "My work" and landing pages are the seed.
- **One shared shell library**, with a **generated thin host per Product**. Each App is a lazy-loaded library, so a
  build ships only the chosen Apps' code. `la-app` and the console become the first two generated hosts; `la-nav.ts`
  disappears into the LA App's definition.
- **The Platform product's shell is today's console**: the Builder and Ops Lenses, Studio and the Pipeline editor.
- **Suite mode**: several Products in one install over the same Spaces, as Standard does today with the console plus
  Link Analysis. That is the same shell with more than one Product enabled.

## 8. The Platform product — chosen Add-ons for builders, integrators and OEMs

- **An Add-on picker** (a page and a CLI) shows the closure, the Edition floor and the price from the Product Map, then
  calls the assembler. The output is a Platform product build.
- **A builder kit**: Studio, the Pipeline editor, the Step and Collector SPIs, the Consequence registry, Job types, the
  Control API with its OpenAPI document, and the Java embedding API (`@PublicApi`).
- **An App authoring kit**, so partners make Apps the way the core team does: `inspecto app new | validate | test |
  package | sign | publish`. It includes a **conformance kit**: golden data with ground truth (the LA showcases'
  golden tests are the model), boot on every listed Edition, the axe check on the landing, and the `consumes` contract
  against the Industry.
- **A private App catalog** per install, and later a partner catalog. This is where an ecosystem starts.
- **A headless Distribution** (no UI) for OEMs who embed the engine.

## 9. Quality and release

- **Guards** (CI guards job): `check-products.mjs`, `check-apps.mjs` and `check-industries.mjs`, covering ids,
  closures, `consumes` contracts, hand-off ends, brand assets and docs hubs. They replace the two-folder guess in
  `customer-build` with **declared** needs, which closes `PRODUCT-MAP-CUSTOMER-BUILD-GAPS-1`.
- **A Product matrix**: for each Product × listed Edition, assemble without the UI, boot, check `/health`, every
  resolved nav entry, and every App's golden data. Nightly, also build the UI and run axe on each landing page.
  `tools/affected-modules.mjs` maps a changed `products/`, `apps/` or `industries/` file to its matrix cells.
- **Three release trains.** The **Platform** follows semver (`BRANCHING.md`). **Apps** version on their own within a
  Platform range, so an App fix ships without a Platform release. A **Product** release is a new Product lock. Release
  notes are generated per Product from commits scoped by Product Map Item.

## 10. Commercial and docs

- Product Map gains the kinds **`industry`**, **`app`** and **`product`**. Order Codes go per App × Edition. A Domain
  product is priced as Edition + Apps; a Platform product as Edition + Add-ons.
- Each Product, App and Industry gets a hub under `docs/okf/product/`. `render-product-docs.mjs` composes, per
  Product, the brochure, datasheet, user guide, admin guide, demo script and video script from the hubs it names.
- A customer build writes the bill of materials, the signed entitlement and the upgrade diff.
- `offerings/basic.toon` loses its 20 `la-*` packs, which move into the LA App. Whether Basic stays sold only as Inspecto
  LA is a commercial call (D-PA2), no longer a side effect of a file.

## 11. Phases

Each phase leaves the build green. Nothing changes for a customer until PA-4.

| Phase | Builds | Proof |
|---|---|---|
| **PA-0** Words and decisions ✅ 2026-11-10 | `GLOSSARY.md` §15 *Product assembly*: Product (Domain / Platform), App, Industry, Suite, Distribution, Entitlement, Assembly Plan, Product lock, Base; §13 rename-map row; §12 signed | vocabulary guard green |
| **PA-1** Definitions describe today ✅ 2026-11-10 | `products/{inspecto,la}.toon`, `apps/link-analysis/app.toon`, `industries/{telecom,mobile-money}/`; one guard, `tools/check-products.mjs` (rules in `tools/products.mjs`, shared with the assembler); the Offering as the only module table | ✅ guard + 21 tests green; the derived module table equals the hand-kept one row for row in every Edition and the core set (only the row order of Standard and up changed, to `CLASSPATH_ORDER`); all 735 tool tests green |
| **PA-2** Assembler ✅ 2026-11-10 | `tools/assemble.mjs`, Assembly Plan, Product lock; `package.ps1` as executor; content staged per Product; generic demo seeding | both bundles rebuilt through plans; `check-bundle-spaces.mjs` generalised (✅; package runs in the as-built note) |
| **PA-3** Server ✅ 2026-11-10 | `product=` / `apps=` stamp; `/bootstrap` with Product, Apps and resolved nav; App gates (regenerate `openapi-v1.json`) | a real-HTTP test per gate combination (✅; proof in the PA-3 note) |
| **PA-4** Suite shell ✅ 2026-11-10 | one shell provider set (`provideInspectoShell`); generated host data (`product.generated.ts`); the LA shell draws the Product's nav (the App switcher and *My work* across Apps moved to PA-5) | both shells' specs and axe green; bundle sizes within 5 % (✅; proof in the PA-4 note) |
| **PA-5** **Inspecto Telco** as proof | the `telecom` Industry owns the xDR Datasets; RA, FM, LA and CM as Apps over **one** ingest; the App switcher and *My work* across Apps; demo Distribution with golden data; **no new Java** | the Product matrix green for Telco × Standard/Advanced; the demo boots seeded |
| **PA-6** Platform product | the Add-on picker; builder-kit docs; `inspecto app new/validate/test/package`; the conformance kit | a sample partner App built only with the kit passes conformance |
| **PA-7** Entitlements and live App install | signed entitlements; `POST /apps` install / upgrade / remove; the trial Distribution | install, upgrade and remove an App on a running server with no lost config |
| **PA-8** Container and air-gap | OCI image plus Helm chart per Product (builds on `SCALEOUT-HELM-NPOD-1`); air-gapped bundle | Telco installs on a cluster and on an offline host |
| **PA-9** CEP App | the **Streaming** Add-on (Kafka in, windowed conditions on the Decision Kernel, Alerts out); the CEP App | a pattern on a live stream raises an Alert within a stated latency |
| **PA-10** Go-to-market | Product Map kinds; generated Product documents; `customer-build` through the assembler | a customer build of the pilot customer matches today's, plus an entitlement |

**PA-1 as built (2026-11-10).**
- **The two Products partition the 30 templates** (the first draft said 31; corrected after review). The LA App owns the 20 `la-*` templates, including
  `la-showcase-off`, the demo's companion Space, which no Offering listed. The console (`inspecto`, Platform product)
  owns the other 10 and takes the LA App as optional. The guard refuses a template that no Product ships.
- **Industry-neutral Apps.** Link Analysis serves any Industry (`industries[0]`), so Inspecto LA names no Industry
  (the glossary follow-up of 2026-11-10).
- **`tools/bundle-modules.mjs` keeps its API but derives its table** from the Offerings and the manifests
  (`readModuleSources`, `deriveTables`). `CLASSPATH_ORDER` moved there; `offering-classpath.mjs` re-exports it.
  `parseToon` moved to `tools/toon-subset.mjs`; `check-offerings.mjs` re-exports it for its dozen importers.
- **Deliberately not done in PA-1:** `nav` and `brand` beyond the name (the TOON subset has no tabular arrays; they
  arrive with `/bootstrap` in PA-3). `products.mjs` resolves Add-ons, but the module set is still the whole Edition's;
  trimming per Product is customer-build's job until PA-10.

**PA-2 as built (2026-11-10).**
- **`tools/assemble.mjs`** writes the Assembly Plan (`inspecto.assembly-plan/1`): host, demo, Apps, Add-ons, the
  Edition's Offering classpath and `-pl` list, templates, seedSpace + seed tool + presenter text, posture and the
  `edition.properties` stamp. It refuses, with the reason, an edition, distribution or target the Product does not offer,
  a demo outside Advanced/Preview, a chosen App whose Add-ons the edition lacks, and any `check-products` finding.
  `--lock` writes the Product lock (Platform and App versions, Industry packs and templates by git tree hash, each
  edition's classpath); `--diff-lock` prints the upgrade diff. No lock is committed yet: no Product has been released.
- **`package.ps1` executes the plan.** `-Product` (default `inspecto`) and `-Distribution` (default `production`)
  replace `-Ui` and `-DemoAuth` with no aliases (D-PA5). Step 0 runs the assembler, and `$Ui` / `$DemoAuth` are set from
  the plan, so every downstream step and `tools/check-demo-auth-isolation.mjs` (which reads `if ($DemoAuth)` blocks) keep
  their shape. `-Edition` keeps its four-name `ValidateSet`; the Edition names still live in about eight places.
- **Content per Product.** `Copy-SpaceTemplates -Only` stages the plan's templates and names the rest. An LA bundle
  now ships the 20 `la-*` templates, no longer all 30; the console still ships all 30.
- **Generic demo seeding.** Step 6f runs for any demo plan with a `seedSpace`; `package-demo-bake.ps1` takes `-Space`
  and `-SeedTool` (`tools/seed-<space>.mjs`, the `--scaffold` / `--apply` contract), and keeps only that Space from the
  scaffold. The la-showcase presenter text moved out of `package.ps1` into `products/la.demo-build.txt`.
  `bundle-spaces-rule.mjs` allows exactly the plan's seedSpace (`--seed-space`). Only la-showcase's seed tool is proven
  against the contract; the other `tools/seed-la-*.mjs` exist but are not checked against it.
- **The release guard follows the flag.** `check-demo-auth-isolation.mjs` refuses a workflow passing
  `-Distribution demo` as it refused `-DemoAuth` (a RED case proves it).
- **Docs.** 40 docs moved to `-Product` / `-Distribution`. Lines that record a past build (a commit, a date, a
  rehearsal) keep their wording with a removal note, which the doc-flag guard accepts.
- **Left as is:** Java and pom comments that say "the `-DemoAuth` build" (changing them would rebuild modules for a
  comment; `OperationalDb.java`, `ObjectsBackendEditionBootTest.java`, two poms, `.gitignore`).
- **Proven by real package runs (2026-11-10, JDK 27, offline Maven, `-NoUi`; the Angular build is unchanged and was
  not exercised).** Inspecto LA × Advanced × demo: `edition=Advanced`, `variant=demo`, `product=la`,
  `apps=link-analysis`; exactly the 20 `la-*` templates; `la-showcase` baked (268 files); `DEMO-BUILD.txt` is
  `products/la.demo-build.txt`; the Space guard passes with `--seed-space`. Console × Standard: 30 templates,
  `product=inspecto`, boots. Inspecto LA × Basic: 20 templates, boots. Inspecto LA × Basic × demo: refused before any
  staging, with the assembler's reason. The five Java tests that read `package.ps1` pass (30 tests).
- **The bake only works from a real console.** An agent session has none, so the graceful Ctrl+C stop times out
  there. In a console one bake in three (same jars, flags and seed) left `.wal` files and was refused; a rerun and the
  pre-PA-2 code baked cleanly. That is the shutdown race `DEMO-BAKE-WAL-RACE-1`, not PA-2.

**PA-3 design (2026-11-10).** The server learns which Product it is and answers `/bootstrap` with the Product, its Apps
and the nav the shells will draw in PA-4. Nothing changes for a user yet: no shell reads the new block until PA-4.
- **Nav is App data.** An App declares `nav:` in `app.toon`: one entry per area (`path`, `label`, `icon`, optional
  `feature`, optional `capability`), in drawing order. The LA App's six entries are `la-nav.ts`'s, unchanged, so PA-4
  is a swap; until then a parity test holds the two equal field for field. `path` is relative to where the host mounts
  the App (PA-4 decides mounts). The guard refuses a `feature` that none of the App's Add-on modules provides, two
  entries sharing a path, and an entry with no path or label. ⚠ It cannot see a repeated entry id: ids are TOON map keys
  and the parser keeps the last of two. A `capability` is not checked against the capability catalogue (no entry uses
  one yet); a misspelt one would hide the entry from every signed-in caller.
- **The bundle carries a resolved descriptor.** `tools/assemble.mjs --descriptor <file>` writes the Product build's
  `product.json` (`inspecto.product/1`): id, title, shape, host, brand, Edition, Distribution, and per App its version,
  the modules its Add-ons need in this Edition, and its nav. `package.ps1` places it at `lib/product.json`.
- **The server finds it beside its own jar.** `ProductDescriptor` reads `-Dproduct.descriptor` when set (tests), else
  `product.json` in the folder of the jar that holds it (`lib/` of a bundle). None found: `product` is `null` (a
  target/classes run, a test, a bundle built before PA-3). A descriptor that does not parse fails the boot, naming the
  file: it can only come from a broken build, and a half-read Product is worse than none.
- **`/bootstrap.product`.** Identity (`id`, `title`, `shape`, `host`, `brand`) always, like `branding`: the sign-in
  page shows the Product's name. `apps` and `nav` only where the Space roster is shown (signed in, or no
  Authenticator). An App is `installed` when every module it needs is ACTIVE (the set `GET /modules` reports), else it
  lists what is `missing`; and `enabled` when, in addition, this Space has switched none of those modules off, else it
  lists them as `switchedOff`. Only an enabled App draws nav, so an entry with no `feature` of its own still disappears
  when the Space switches Link Analysis off (added after review). A nav entry is drawn when its App is enabled, its
  `feature` is true in this response's `features` (installed, enabled in this Space, in this Edition), and, where an
  Authenticator is active, the caller holds its `capability`. These are the three gates of `GLOSSARY.md` §15
  (Installed · Enabled · Permitted), plus the Product; Entitlement joins Installed in PA-7.
- **Proof (as built, 2026-11-10).** `ProductDescriptorTest` (9: each gate alone, the reader's refusals) and
  `ControlApiBootstrapProductTest` (5, real HTTP: no descriptor, Basic, pre-sign-in identity only, a caller with and
  without the capability, a malformed descriptor failing the boot before a port is bound); the nav parity test against
  `la-nav.ts`; the OpenAPI `Bootstrap` schema regenerated. The affected-module run (`-Pedition-advanced`): the processor
  2,669 tests and 21 downstream modules 3,033 tests, no failure; it caught `ImportLoaderInventoryTest`, which
  requires `product.json` (a fixed name a loader reads) to be allow-listed with its reason. Real bundles, booted:
  Inspecto LA × Basic answers `product.id` `la`, `apps[0]` installed, nav `linkAnalysis, data, entityLists` (geoMap,
  entities and alerts hidden because Basic reports those features false - exactly what `la-nav.ts` draws on Basic);
  the console × Basic answers `product.id` `inspecto`, shape `platform`, host `gamma`, the same three entries.

**PA-4 design (2026-11-10).** The shells stop holding the Product in code. Scope of this phase, and what moves:
- **Generated host data.** `tools/render-product-hosts.mjs` writes one `product.generated.ts` per Product into its
  Angular host (`inspecto-ui/projects/<host>/src/app/` for `la-app`, `inspecto-ui/src/app/` for the console): the
  brand (`name`, and the sign-in pitch when the Product declares one) and each App's nav, from `products/` and
  `apps/`. `--check` fails when a file is stale; it runs in the Product guard's CI step, so an edited `app.toon`
  cannot ship without its host data. The PA-3 parity test against `la-nav.ts` retires with that file.
- **The session knows the Product.** `SessionService.product` holds `/bootstrap.product`, refreshed on every
  re-read (`init`, after sign-in, and after a Space's Module settings change), because its nav depends on the caller
  and the Space.
- **The LA shell draws the Product's nav.** When the server resolved one (`/bootstrap.product.nav`), the rail and the
  landing page draw it as is - the server already applied the four gates. When it did not (a backend built before
  PA-3, `ng serve` against a dev server with no descriptor), they draw the generated nav under the same feature rule
  `la-nav.ts` applied. Settings stays a shell link, not an App area. `la-nav.ts` and `la-brand.ts` are deleted.
- **One shell provider set.** `provideInspectoShell()` (`@inspecto/core`) holds what both hosts configure alike
  (HTTP and its four interceptors in order, animations, toasts, the router with its scrolling and input binding, the
  keep-alive reuse strategy, the date adapter and formats, modal dialogs); each host adds only its own routes,
  initialisers, host services and theme.
- **Moved to later phases, deliberately.** The App switcher and *My work* across Apps need a Product with more than
  one App, so they land with Inspecto Telco (PA-5). The console's nav as data is the Platform product's work (PA-6):
  its 4-group, Lens-gated nav is a different shape from an App's areas.
- **Proof:** the specs of both hosts and of `SessionService`, axe on the shell, `lint`, `lint:tokens`, `lint:forms`,
  the three typechecks, both production builds within 5 % of today's bundle sizes, and the LA shell in the preview
  drawing a server-resolved nav and the fallback.
- **Proof (as built, 2026-11-10).** `product-host.spec.ts` (5: the server's nav as is, an empty server nav, the feature
  rule with no Product, identity only, another Product's nav ignored), two `SessionService` cases (the Product read and
  re-read; a server with no descriptor reads as none), two la-shell cases (server nav with axe; another Product's nav
  falls back); every la-app spec plus those: 24 files, 218 tests. `render-product-hosts.test.mjs` (4) and two new guard
  cases: 66 tool tests. `typecheck` (all three configs), `lint:tokens` and `lint:forms` green. Production builds against
  `c08499967`: the console 32,552,672 → 32,554,223 bytes, la-app 29,716,271 → 29,717,139 (both +0.005 %). Preview,
  `ng serve la-app` from the lane: (a) a target/classes server with the Inspecto LA descriptor answers `product.id` `la`
  with the App not installed (its modules are not on that classpath) and nav `[]`, and the rail draws Settings only;
  (b) the same server with no descriptor answers `product: null` and the rail draws the generated nav under the
  feature rule (Link Analysis, Data, Entity Lists, Settings); (c) a real Inspecto LA × Basic bundle packaged from the
  lane answers nav `linkAnalysis, data, entityLists`, the session holds it and the rail draws it; no console errors.
  ⚠ Not PA-4, already on master: `npm run lint` failed on `link-analysis.component.ts` (a restricted
  `../la-rail.service` import from `d634b6a7d`; fixed right after PA-4 by importing it through the library alias)
  and `format:check` reports 251 drifted files (left alone).

## 12. Decisions (operator, all signed 2026-11-10 as recommended)

| Id | Call | Signed |
|---|---|---|
| D-PA1 | The word for what a customer receives: **Product** (*Inspecto LA* is already named a product) | Product |
| D-PA2 | Does **Basic** stay sold only as Inspecto LA, or become a tier every Product can use? | a tier for every Product; Inspecto LA × Basic stays the entry Order Code |
| D-PA3 | One suite shell per Product, with Apps as lazy libraries (PA-4), before a single run-time multi-Product SPA? | yes |
| D-PA4 | The proof Product (PA-5) | **Inspecto Telco** with RA, FM, LA and CM: the most content built, and an RFP demo exists |
| D-PA5 | Remove `-Ui` / `-DemoAuth` outright when the assembler lands (no aliases)? | yes, per the standing no-shims preference |
| D-PA6 | Order Codes per App × Edition (Domain product) and per Add-on × Edition (Platform product)? | yes |
| D-PA7 | The word **App** for a finished job in one Industry (RA, FM, LA, CM, CEP) | App |
| D-PA8 | The vertical is an **Industry**, and the Industry pack widens to own the canonical Datasets every App reads | yes |
| D-PA9 | "CM" in the Telco App list means **Case Management** | confirmed |
| D-PA10 | Apps install into a running Platform (PA-7) as well as at build time | yes, admin-only and signed |
| D-PA11 | Entitlements: a signed file checked offline at boot and on gated routes | yes; no call-home |
| D-PA12 | Apps version on their own within a Platform range | yes |

## 13. The open board, re-checked 2026-11-10

**Method.** All 89 open rows (17 P2, 72 P3; changelog lines excluded) were checked against `master` at `8b1da0587`.
Each row's named symbols were looked up in the code (CodeGraph, grep, `git log -S`); no build was run. Verdicts are
grounded hypotheses: four claims were checked by hand and all held (rows 613 and 714, the module-table lockstep, and
`contentPacks` staging nothing). The rest still need the usual check before a row is closed. **The board itself is not
edited yet** (§14).

| Verdict | Rows | Meaning |
|---|---|---|
| DONE | 1 | `SCREENING-1`: every buildable item shipped; only the operator's confirmation of D6/D8 is left |
| OBSOLETE | 2 | the feature or query type no longer exists |
| PARTLY DONE | 13 | the headline is ahead of or behind the code; trim to what is left |
| REWRITE | 6 | still needed, but the cause, trigger or dependency named is wrong |
| VALID | 67 | still true; about 40 of them need the operator, a person, a live host or a customer, or wait on demand |

**The code moved less than it looks for these rows.** About 420 commits since 2026-11-02 were almost all Link Analysis
showcase work. The board has drifted mainly into *build logs*: rows that narrate what shipped instead of stating what is
left (`SCREENING-1`, `ANALYTICS-CORE-1`, `TELCO-BYPASS-SIMBOX-1`, `SCALEOUT-OBJSTORE-DATABASE-DIR-1`,
`MODULE-REORG-P3-THIN-JARS`).

### 13.1 Close, merge or re-rank

| Row | Change | Evidence |
|---|---|---|
| P4 Test mapping on a generic `parser` node (P3) | **delete**, obsolete | the Load pane that held Test mapping was deleted in `42fa41fe56`; no `Test mapping` in `inspecto-ui/`. Also fix `user-guide-pipeline-editor.md:645`, which still describes it |
| Queries / BI `graph`/`spatial`/`search`/`api` (P3) | **delete**, obsolete | `spec-queries.md:51` records them as deliberately not built |
| `SCREENING-1` (P2) | **close** once D6/D8 are confirmed | every buildable item shipped |
| `PREDICTIVE-ANALYTICS-1` (P3) | **merge** into `PREDICTIVE-STUDIO-PREVIEW-1` | its four slices shipped 2026-11-01; "design-only" is stale |
| `ANALYTICS-CORE-1` C7 | **move** into `DUCKDB-2-LOOP-QUERIES-1` | same work |
| `AUTHORING-REDESIGN-1` (P2) | **P3, `hold: demand`** | only (d), v3 macros, is left, and it is parked |
| Platform Services Stage 2/3 residuals (P3) | add **`hold: demand`** | says "on demand" with no tag |

### 13.2 Rewrite (the row says something the code contradicts)

| Row | Correct statement |
|---|---|
| `JOB-SIGNAL-OVERLAP-SKIP-1` (P2) | **wider than filed:** `TriggerCoalescer` hands its work to `submitRun`, which only queues and returns, so the coalescer never holds a signal back; overlap is prevented only by `runExclusiveOrSkip` (`JobService.java:1541`), which records SKIPPED. **Every** coalescing `on_signal` Job can lose its last landing, not only the LA showcases. S-sized |
| `LA-INDEX-SCALE-MEASURE-1` | the hold "until default-on is decided" has passed (`index.enabled` default ON since 2026-10-10); hold on a customer sizing |
| `LA-DAILY-INGEST-1` | the CSV/Parquet part is complete; narrow to Hive JDBC/Kerberos, the `db` Collector and SCD2/as-of join, held for a named feed; the Edition items belong to `DATA-TIERS-1` |
| `LA-INVESTIGATION-STORE-DESIGN-1` | S7 is closed on both backends and both Postgres suites run green nightly; only the Postgres mutation checks (design 13.4/13.5) are left |
| `TEST-ENV-PG-ASN-UNRUN-1` | the stated asn cause looks wrong (the surefire 3.5.3 jar is in the local repository); one offline Maven run will say what is missing |
| `PACK-SALES-MARKETING-1` | churn and forecast have shipped; the only blocker is D-SM1…D-SM12 |
| `SCALEOUT-LIVE-HALVES-1` | bundles already stage `httpfs` and `aws`, and MinIO is running; only `pg_duckdb` is owed |

Also: `enterprise-scale-out-plan.md:82` still says D14–D16 are unsigned; D14 and D16 were signed 2026-10-20.

### 13.3 Startable now, no decision owed, each S-sized

These are the parallel lanes. Each is code-only, needs no host, and touches files no other lane touches.

| Lane | Rows | Serves |
|---|---|---|
| Jobs runtime | `JOB-SIGNAL-OVERLAP-SKIP-1` (P2), `LA-INDEX-BUILD-REFUSED-NPE-1`, `JOB-LOADER-SAVED-VIEW-SUFFIX-1` | Base |
| Alerting | `ALERT-EVALUATE-NO-BREACH-WORDING-1`, `LA-TELCO-RA-FIRST-BOOT-RESOLVED-ALERT-1` | Base |
| Mapping | `MAPPING-EPOCH-TIMESTAMP-1` | Base |
| Egress security | `SCALEOUT-OBJSTORE-DATABASE-DIR-1` (e): an all-digits host such as `2130706433` passes `BARE_HOST` (`ObjectStoreDatabase.java:39`) | Base |
| Inspecto LA UI | the 14 defects from the 2026-11-10 UI test (`LA-BASIC-PLACEHOLDER-CASE-1` … `LA-UI-TEXT-PASS-2`); `LA-SAMPLE-EMPTY-DATASET-1`'s likely cause is a Space created without `loadSamples` | LA App |
| Showcases | `LA-OWNERSHIP-NO-CRON-FALLBACK-1`, `LA-UTILITY-LOOKALIKE-CHECKS-1` | LA App |

### 13.4 The board read by Product

| Product / layer | Rows that stand between it and "sellable" |
|---|---|
| Base (every Product) | §13.3 Jobs, Alerting, Mapping and Egress lanes; `ANALYTICS-CORE-1` (the SqlIdent sweep has not started; all 15 named classes still hand-quote identifiers); `DATA-TIERS-1` P2/P4–P7 |
| **Assembly** (this plan) | `PRODUCT-MAP-CUSTOMER-BUILD-GAPS-1` (absorbed by §9), `MODULE-REORG-P3-THIN-JARS` defect (2) (`package.ps1` runs `npm ci` in the shared `inspecto-ui`), `PRODUCT-MAP-S6-1`, `DOCS-FUNCTIONALITY-HUBS-1`, and `DATA-TIERS-1` DT-18, which is really D-PA2 |
| **Inspecto Telco** | RA: `PACK-RERATING-1` R4–R7, `PACK-PARTNER-SETTLEMENT-1` W1–W3. FM: `TELCO-BYPASS-SIMBOX-1` B4–B7, `PACK-IRSF-WANGIRI-1`, `ASSURE-PACK-TELCO-FRAUD-RESIDUALS-1`. CEP: `REALTIME-DECISIONING-1`. 5G/IoT: `PACK-5G-IOT-ASSURANCE-1` U0 |
| Inspecto LA | the 14 UI defects; showcase residuals; `LA-A11Y-AUDIT-1` (person-only); `LA-SHOWCASE-RELEASE-RESIDUALS-1` (Linux zip) |
| Inspecto Financial Crime | `SCREENING-1` (close), `REGULATORY-SAR-STR-TEMPLATES-1`, `AGENTIC-INVESTIGATION-1` (S1 only; no SPA caller of `/briefs`) |
| Platform product (builders) | `REPORT-PDF-CHARTS-1` (the PDF is one table image with no text layer), `PREDICTIVE-STUDIO-PREVIEW-1`, `PACK-SALES-MARKETING-1`, `ONBOARDING-REMOTE-SYSTEMS-1` |
| Distribution (every Product) | `DEPLOY-SERVICE-WRAPPER-1`, `ASSURE-OPERABILITY-1` (D2 now proven by the green Postgres nightly), Postgres multi-user, `SCALEOUT-HELM-NPOD-1` (feeds PA-8); all need a host or the operator |

## 14. Next steps

1. ✅ The operator signed §12 (D-PA1 … D-PA12), 2026-11-10.
2. ✅ Board row `PRODUCT-ASSEMBLY-1` filed (P2 feature, §3.10). Still to do: apply §13.1 and §13.2 to the board. Each
   closure is marked in its owning hub first, and the counts are re-derived (`tools/check-doc-counts.mjs`).
3. Run §13.3 as parallel lanes (at most three at a time), one push per lane.
4. Build PA-0 → PA-5. PA-5 is the proof: Inspecto Telco, four Apps over one ingest, with no new Java.
