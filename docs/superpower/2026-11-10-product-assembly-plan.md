# Product assembly — one Inspecto Platform, Industry Apps, finished Products (plan)

*Status: APPROVED 2026-11-10. The operator affirmed the direction ("the plan holds") and signed every decision in §12
as recommended. ✅ PA-0 done 2026-11-10 (the words are in `GLOSSARY.md` §15 *Product assembly*); ✅ PA-1 done 2026-11-10 (§11
as-built note); ✅ PA-2 done 2026-11-10 (§11 as-built note, with the package runs that prove it); ✅ PA-3 done 2026-11-10 (§11
design and proof); ✅ PA-4 done 2026-11-10 (§11 design and proof); PA-5 designed and sliced (§11, D-PA13…D-PA16 signed), ✅ PA-5a and
PA-5b and PA-5c done 2026-11-10 (PA-5c: one usage feed in the Composed Space Template, D-PA17); ✅ PA-5d done 2026-11-11 (the Telco demo Distribution and Product matrix), so PA-5 is done; PA-6 designed and sliced 2026-11-11 (§11, D-PA18…D-PA20 signed); ✅ PA-6a done 2026-11-11 (the App kit); ✅ PA-6b done 2026-11-11 (the sample partner App); ✅ PA-6c done 2026-11-11 (the Add-on picker CLI); ✅ PA-6d done 2026-11-11 (the builder-kit guide), so PA-6 is done; PA-7 designed and sliced 2026-11-11 (§11, D-PA22…D-PA25 signed); ✅ PA-7a done 2026-11-11 (signed entitlements); ✅ PA-7b done 2026-11-11 (expiry, read-only, the trial Distribution); ✅ PA-7c done 2026-11-11 (signed App packages); ✅ PA-7d done 2026-11-11 (live App install), so PA-7 is done; PA-8 designed and sliced 2026-11-12 (§11, D-PA26…D-PA31 decided, operator away). PA-10 designed and sliced 2026-11-12 (§11, D-PA32…D-PA37 decided, operator away). Product Map Items: `layer.platform`, `layer.product`, `edition.basic`,
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
| **PA-5** **Inspecto Telco** as proof (lanes 5a ✅ … 5d, §11) | the `telecom` Industry owns the xDR Datasets; RA, FM, LA and CM as Apps over **one** ingest; the App switcher and *My work* across Apps; demo Distribution with golden data; **no new Java** | the Product matrix green for Telco × Standard/Advanced; the demo boots seeded |
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
  The sign-in page reads the pitch before sign-in from this host data (`APP_ENVIRONMENT.signInPitch`): an SSO
  sign-in shows its title and body; a demo sign-in shows its title alone, or the bare Product name when the
  Product declares no pitch (`SIGNIN-DEMO-HEADLINE-1`, 2026-11-12, replacing the hard-coded "`<Product>` MFS" from
  `52aa6e7a7`; Inspecto LA's demo now reads "Follow every link, one case at a time.").
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

**PA-5 design (2026-11-10; D-PA13…D-PA16 signed).** Inspecto Telco: RA, FM, LA and CM over one ingest, with no new Java.
What the survey found decides the shape:
- **A Space is created from exactly one Space Template** (`POST /spaces {template}` copies one folder;
  `SpaceManager.createFromTemplate`). With no new Java, Apps that share a Space can only do so through a template made
  of theirs at build time (D-PA14).
- **The telco templates share no ingest.** RA (`telco-ra`) reads switch, mediated and rated xDRs (`XDR_ID`,
  `SUBSCRIBER_ID`, `B_NUMBER`, `SERVICE`, `PARTNER`, `USAGE_UNITS`); FM's `telco-fraud` reads `cdr` (`a_number`,
  `b_number`, `direction`, `duration_s`, `charge`, `roaming`, `cell_id`); `telco-bypass` reads `voice_cdr` (the same plus
  `answered`, `release_cause`, `imei`, `imsi`); the LA showcase `la-telco-ra` reads `network_usage`. Re-rating shares
  RA's `rated_usage` schema field for field. Java golden tests pin each corpus (`TelcoRaGoldenTest`,
  `TelcoFraudTemplateGoldenTest`, `BypassFeatureGoldenTest`, `TelcoRerateGoldenTest`, `BypassBoxCaseRuleTest`, …).
- **RA, FM and CM pages exist only in the console**; la-app holds Link Analysis and *My work* (LA cases, data changes).

The slices, each its own lane (D-PA16):
- **PA-5a — definitions and the composed Space.** `apps/telco-ra` (Revenue Assurance: Reconciliation, Incidents),
  `apps/telco-fraud` (Fraud Management: `telco-fraud` + `telco-bypass`; Incidents, Case Management, Scoring & Lists,
  Anomaly), `apps/case-management` (Case Management, Industry-neutral, no content of its own yet) and
  `products/telco.toon` (Standard, Advanced; RA and CM required, FM and LA optional; `space: telco`). The 20 LA showcase
  templates move from the LA App to the Products that ship them (`la`, `inspecto`: both stage exactly what they did):
  they are Inspecto LA's gallery, and a Telco build must not carry them (§5). LA's telecom content returns in PA-5c, over
  the Industry's feeds. **A shared host (D-PA13):** a host builds several Products; one is its default build, every other
  names a `hostConfiguration` whose `fileReplacements` swap in `product.<id>.generated.ts` (la-app `-c telco`);
  `package.ps1` builds the UI with it. **The composed Space (D-PA14):** `tools/compose-space.mjs` writes
  `spaces/_templates/<space>` in the bundle from the staged parts (each selected App's templates): their files side by
  side, plus the two it owns, `template.toon` (named for the Product, each part's contents under its App's name) and
  `config/examples/sla-escalation-examples.md` (each part's examples under its App's heading). Any other file two parts
  ship is refused, statically by the guard and again by the composer. RA, FM and CM declare no nav yet: their areas come
  with their pages in PA-5b, so the Telco shell draws LA's areas only until then. la-app's `index.html` names no Product
  any more (`<title>Inspecto</title>`); `provideInspectoShell()` sets the tab title from the Product's brand.
  **Proof (as built, 2026-11-10).** Tool tests 79/79 (`compose-space.test.mjs` 4: every committed `template.toon` reads
  with its declared counts, the Telco Space composed byte for byte from the three parts, the refusals; 9 new guard cases;
  the Telco plan on Standard and Advanced); `render-product-hosts --check`; `typecheck` and `lint` green; the shell and
  product-host specs plus every la-app spec, 24 files / 176 tests; the five Java classes that read a changed file
  (`ImportLoaderInventoryTest`, `EventStoreDurabilityTest`, `EnrichmentEngineTest`, `ConsignmentOutputRegistrationTest`,
  `TriggerVocabularyContractTest`) 47 tests green. After review: a collision is keyed on the lower-cased path (on
  Windows `X.toon` and `x.toon` are one file and the second silently overwrote the first), and the `template.toon`
  reader takes top-level keys only. ⚠ `inspecto` now stages the 20 `la-*` templates as its own, so a programmatic
  `assemble({apps: []})` stages them although no App asks; `package.ps1` and the CLI never narrow its one optional App,
  so no build changes. `package.ps1 -Product telco -Edition Standard` built la-app with
  `-c production,telco`, composed `telco` from `telco-ra`, `telco-fraud`, `telco-bypass` (247 files) and passed its boot
  check. Booted as shipped (OIDC, placeholder issuer): the sign-in page shows Inspecto Telco's pitch, `/bootstrap.product`
  names `telco`. Booted auth-free (a scratch copy with `inspecto-oidc.jar` off `modules.list`): all four Apps installed
  and enabled, nav = LA's six areas; the gallery offers `telco`, creatable, with samples and 29 contents (9 + 10 + 10);
  `POST /spaces {template: telco, loadSamples: true}` loaded all 11 feeds (RA's 8, FM's 3), none quarantined; the Space
  holds 35 Jobs (11 + 18 + 6), 19 Alert Rules (2 + 14 + 3), 2 Reconciliations, 44 Datasets (17 + 18 + 9), as the parts
  ship them; `ra_xdr_completeness`, `ra_rated_vs_billed`, `fraud_irsf`, `fraud_simbox` and `tb_features` ran to SUCCESS
  side by side; the la-app Telco build titles the tab and the rail *Inspecto Telco* and draws the server's nav. Not run:
  the Advanced package (the assembler test covers its plan; the Product matrix is PA-5d) and the parts' golden values in
  the composed Space (the Java golden tests pin each part; the composer copies bytes, proven by the test above).
- **PA-5b — the suite shell (D-PA13).** la-app becomes the suite host: an App switcher, each App's areas lazy-loading the
  console's existing pages (Reconciliation, Incidents, Cases, Alerts, Dashboards), and *My work* across Apps (Alerts,
  Incidents and Cases assigned to me, plus LA's cases), composed client-side from existing routes.
  **As built (2026-11-10).** The Apps' areas are `nav:` in `app.toon`: Revenue Assurance `reconciliation`, `kpi-reports`;
  Case Management `alerts`, `incidents`, `cases`; Fraud Management `risk-scores`, `anomaly-models`; each keyed on its
  module's feature (`reconciliation`, `ops`, `cases`, `scoring`, `anomaly`), so a Space that switches the module off
  loses the area. **A page is drawn once** (`productNavByApp`, `tools/products.mjs`): an entry whose path an earlier App
  in Product order already declared is left out, in the assembler's descriptor and in the generated host data alike, so
  in Inspecto Telco Case Management's Alerts wins over Link Analysis' (one page); Inspecto LA keeps its own. Dashboards
  were left out: the Studio dashboard viewer is not a mountable page yet, and RA's KPIs page shows its KPIs. The
  generated data gains `apps` (id, title: the switcher's fallback) and `brand.tagline` (the landing line, now from
  `products/<id>.toon`). In la-app each console page is mounted whole (`area(path, loadChildren)`), and **one
  `areaGuard`** replaces `investigationsGuard`: an App-area route opens only when `productNav(...)` offers its path, else
  the landing page. That covers the edition (Basic: no Alerts), the build (Inspecto LA: no Reconciliations) and a
  switched-off module. ⚠ `/entities` stays unguarded (found in review): the Space guide's mandatory "What the columns
  hold" step links `/entities?tab=columns` on every edition, Basic included, where the Entities area is not offered.
  ⚠ *My work* reads the 50 newest Incidents and Cases of each type (the server has no open-only filter over HTTP), so an
  older open one behind 50 newer closed ones is not listed. **The App switcher** shows when `appAreas(...)` returns more than one App; the rail then shows
  the current App's areas plus Settings. The current App is the one whose area the URL opens (`appOfUrl`), else the
  last one chosen (kept in `localStorage`, `la-app.app.<product>`), else the first. The landing page groups the cards
  under each App's name. *My work* gains **Incidents and Cases** where the nav offers those areas: assigned to the
  caller when someone is signed in (`ObjectsService.list({type, assignee})`), else the open ones, five at most, each
  opening its page. Alerts are assigned to nobody, so they stay in the Alerts area, not in *My work*. Inspecto LA (one
  App) has no switcher, no groups and no Incidents section.
  **Proof (as built).** Tool tests 80/80 (the descriptor and generated nav drop the duplicate Alerts; the generator
  follows Prettier's short-key rule for long strings). UI: `lint`, `lint:tokens`, `lint:forms`, `typecheck` green; the
  product-host, shell and every la-app spec, 24 files / 184 tests, among them the switcher with axe, the area guard,
  every nav entry of both la-app Products routed, the six console route files resolving, and *My work*'s Incidents and
  Cases with axe. Builds against `df2a7cc33`: console 32,554,268 → 32,554,318 bytes; la-app 29,717,435 → 30,437,778 and
  la-app `-c telco` 29,717,398 → 30,436,409 (+2.4 %: the console pages' lazy chunks now compile into both la-app
  builds; trimming routes per Product is later); initial transfer 142.88 → 143.53 kB (LA), 142.94 → 143.86 kB (Telco).
  Preview: Inspecto Telco × Standard packaged from the lane, booted auth-free (a scratch copy with `inspecto-oidc.jar`
  off `modules.list`); `/bootstrap.product.nav` has all twelve areas; a Space from the composed template with its
  samples, RA's two Reconciliations, `fraud_irsf` and `fraud_alert_evaluate` run. The rail shows the switcher
  (Revenue Assurance) and RA's areas; the landing page groups four Apps under the tagline; *My work* lists the open
  Incidents the Reconciliation breaches opened. Choosing Case Management opens Alerts (100 fired); Incidents shows
  395, Cases the Case Manager, Risk Scores, Anomaly Models, Reconciliation (both Reconciliations) and KPI & Reports
  all render, the switcher following the URL each time; no console error from the shell. The same bundle with the
  Inspecto LA build: no switcher, LA's flat rail, no groups, no Incidents section, and `/reconciliation` lands on the
  home page.
- **PA-5c — one ingest (D-PA15).** `telecom` owns canonical feeds (switch, mediated and rated xDR, subscriber, invoice,
  tariff, …) with ONE generated golden corpus that plants RA's leakages and FM's typologies, with ground truth; each App
  maps them onto its own names through views, so its Jobs and Alert Rules stay; the golden tests are re-pointed; LA's
  telecom link Job reads the same feeds.
  **Re-scoped 2026-11-10 (D-PA17, operator).** The survey found the telco corpora hand-made (no generator for
  `telco-ra`, `telco-fraud`, `telco-bypass`, `telco-rerating`) and pinned by about 650 assertions in four Java golden
  tests (`TelcoRaGoldenTest` 67, `TelcoFraudTemplateGoldenTest` 105, `BypassFeatureGoldenTest` 132,
  `TelcoRerateGoldenTest` 353; 4,100 lines). Re-deriving them all was judged the wrong risk, so **one ingest happens in
  the Composed Space Template only**: each App template keeps its own feeds and golden tests (it still installs alone),
  and `tools/compose-space.mjs` swaps the parts' usage-feed Pipelines for the `telecom` Industry's one canonical
  feed when it composes a Product's Space. Facts and open questions for the build lane:
  - The overlap is the network usage record: RA's `switch_xdr` (`XDR_ID`, `SUBSCRIBER_ID`, `B_NUMBER`, `SERVICE`,
    `PARTNER`, `USAGE_UNITS`), FM's `cdr` (`record_id`, `a_number`, `b_number`, `direction`, `duration_s`, `charge`,
    `roaming`, `cell_id`) and bypass's `voice_cdr` (the same plus `answered`, `release_cause`, `imei`, `imsi`). RA's
    mediated, rated, billed, tariff and interconnect feeds and FM's subscriber and payment feeds have no second
    reader in Telco, so they stay the parts' own.
  - Mechanism, to decide first: a `config/views/*.toon` view is a Pipeline `sink.view` read through `ViewQuery`; it is
    not yet shown that a Reconciliation side, a `sql.template` Job's `FROM cdr` and an Alert Rule resolve a view as
    they resolve a store. The alternative is ONE canonical Pipeline that fans out (one sink per App store:
    `switch_xdr`, `cdr`, `voice_cdr`), so every consumer keeps reading a real store.
  - The composed corpus: the union of the parts' usage samples converted to the canonical shape. RA's three-way
    completeness and rated-vs-billed controls would then flag FM's and bypass's traffic as lost or unbilled, so the
    converter must also emit their mediated, rated and billed rows (rated at the tariff; billed per subscriber and
    month), or the composed Space plants false leakage. FM's typologies must stay quiet on RA's rows (fictional
    `+1-555-01xx` numbers, no IRSF prefixes).
  - Proof: one new golden test of the composed Space (each App's planted findings, nothing new), the four existing
    golden tests untouched, and the Telco bundle seeded from the composed corpus.

  **As built (PA-5c done 2026-11-10).**
  - **Mechanism: one canonical Pipeline that fans out, not views.** A `sql.template` Job registers its `sources` as
    views over the store folder (`<dataDir>/<store>/**/*.parquet`, `SqlTemplateJob`), never over a Dataset or a
    `config/views` view, so FM's 14 detection Jobs and bypass's Jobs can read only a real store. A Reconciliation side
    would resolve a `view:` Dataset (`DatasetRelation`), but one feed has to serve all three readers. So the
    `telecom_usage` Pipeline uses `route: mode: clone` (armed since CLONE-ARM-1) with one sink per App store
    (`data/switch_xdr/database`, `data/cdr/database`, `data/voice_cdr/database`), and each branch's `sql` step
    (SQL-BRANCH-1) projects the canonical columns onto that App's names. Every Job, Reconciliation, Expectation,
    Dataset and Alert Rule keeps reading the store it read before.
  - **Where it lives.** `tools/telecom-usage.mjs`, beside the composer: the canonical columns (FM's and bypass's names,
    plus RA's `usage_units` and `partner`), the three readers, the converter (`convert`), the generated Pipeline and
    schema, and `applyUsageFeed`. `tools/compose-space.mjs` calls it when the Assembly Plan's `space.industry` is
    `telecom` (`tools/assemble.mjs` now carries it) and two or more parts read usage. It removes each part's usage
    Pipeline (`config/<feed>/`) and sample (`data/samples/<feed>/`), writes `config/telecom_usage/` and the converted
    samples, and retargets what named a removed Pipeline: FM's Decision Rule `tf_long_roaming_call_escalate`
    (`target: cdr`) and RA's `ra_switch_kpi_completeness` Job (`pipeline: switch_xdr`). The stores' zero-row seeds stay.
    Each App template, its own feeds and its golden tests are unchanged: the swap happens only in the composed copy.
  - **The composed corpus.** 3,959 records: RA's 600 switch records (direction `MO`, the subscriber as `a_number`, the
    charge RA's rating gave them) and FM's 3,359 call records as they are, sorted by time. RA reads only originated
    records (`where: direction = 'MO'`), so FM's 2,672 MO records are bridged into RA's chain: a mediated row (same
    units), a rated row on `PLAN_B` at the one tariff row in force (VOICE seconds, SMS one event, DATA one MB per
    session minute, rounded up; cents rounded half away from zero, as DuckDB rounds the DECIMAL product) and one invoice
    line per subscriber and month equal to the rated sum (332 lines, `INVTU00001`…). The bridge refuses an ambiguous
    tariff, a record id two parts share, and a subscriber RA already bills. Bypass ships no usage sample (its corpus is
    generated in-test by `BypassCorpus`), so it adds no records.
  - **One cross-App finding no template plants alone (operator 2026-11-10: KEEP it; the test pins it).** FM's five planted
    premium-rate callers (`999700012xx`) all originate on one cell (`C500`), each dialling a new number almost every
    call, briefly, receiving nothing. In the composed Space bypass sees that traffic, and its cluster rule
    `tb_box_suspected` opens one Incident for `cell:C500` on the first night. No SIM-level bypass rule fires (one day of
    traffic never meets the persistence gate). The SIM-day signal `box_like` marks those five lines and one more of
    FM's, `99970001211` (a sequential dialler on cell `C501`), which reaches no Alert; the test pins all six. Recommended: keep it. It is a plausible true positive (artificial
    premium-rate traffic from a SIM farm), and hiding it would need FM's corpus or the converter to move those calls to
    other cells, which would change what FM's golden test pins. If the operator prefers no cross-App findings, the
    converter can spread those five lines over five cells in the composed copy only (FM's typologies do not read
    `cell_id` for premium rate).
  - **Deliberately not done.** FM's `tf_anomaly_score` (the `anomaly.score` Job) is not run by either composed test: RA's
    40 subscribers join the population that the per-subscriber usage model scores, so scores move. That is expected for a
    population model and is not a planted finding. The four Expectations whose `target` names a usage Pipeline
    (`tf_cdr_*`, `ra_switch_xdr_id_non_null`) still evaluate their store (`ExpectationEvaluator` reads
    `data/<target>`), but `MetadataValidateTask` would list them as broken Pipeline references, because `target` serves
    as both the store and the Pipeline id. Gap detection on FM's and bypass's feed file names is dropped with their
    Pipelines.
  - **Gotcha.** A clone branch must keep the ingest lane's lineage column `__src_id`. A branch that projects only its
    own columns fails the Consignment with "Referenced column `__src_id` not found". So each branch is generated as
    `SELECT * EXCLUDE (<every canonical column>), <the App's columns> FROM input`. Two files in one inbox are one
    Consignment, so `runPipeline` reports `RunResult(1, 0)` for RA's feeds that gain a bridging file.
  **Proof (as built).** Node: `tools/telecom-usage.test.mjs` 6/6 (union and order, the bridge agreeing with RA's
  re-rating, rounding and units, the generated Pipeline, the swap and its refusals), with `compose-space.test.mjs` and
  `assemble.test.mjs` 23/23 in all. Java: `TelcoComposedSpaceGoldenTest` (inspecto-reconciliation) runs
  `tools/assemble.mjs` and `tools/compose-space.mjs` exactly as the bundle does (`node` on the PATH), creates the
  composed Space and a `telco-fraud` Space beside it, and ingests every composed sample through the Space's own
  Pipelines. It checks that `switch_xdr` holds every MO record and `cdr` and `voice_cdr` every record. RA: every
  control's planted set equals `TelcoRaCorpus.planted` (xDR lost, re-rating, roll-forward, settlement, both
  Reconciliations), with 17 leakage and 17 data-quality rows. FM: the 14 typologies' breached keys and every detection
  sink's rows are identical to the `telco-fraud` Space's, for each of 07-01, 07-02 and 07-03. `TelcoComposedBypassTest` (inspecto-scoring) runs bypass's
  whole nightly chain for 07-01…07-03 and pins the one cross-App Incident above, with no SIM finding at `alert`. The
  four existing golden tests (`TelcoRaGoldenTest`, `TelcoFraudTemplateGoldenTest`, `BypassFeatureGoldenTest`,
  `TelcoRerateGoldenTest`) are unchanged and green (8 + 12 + 26 + 6 tests); `spaces/_templates/` has no diff. Bundle:
  `package.ps1 -Product telco -Edition Standard -NoRuntime` composes `telco` (245 files; "one usage feed,
  telecom_usage -> switch_xdr, cdr, voice_cdr (3959 records, 2672 bridged into RA's chain)"), and the bundle's
  `spaces/_templates/telco` ships `config/telecom_usage/` and the converted samples, with no `config/switch_xdr`,
  `config/cdr` or `config/voice_cdr`. The bundle was not booted: a Space created from it is what the two Java tests
  build. Seeding a demo Space from it is PA-5d.
- **PA-5d — the demo Distribution.** `tools/seed-telco.mjs`, `products/telco.demo-build.txt`, `demoSpace: telco`, and
  the Telco Product matrix (Standard, Advanced) in CI.

  **As built (PA-5d done 2026-11-11).**
  - **The Product.** `products/telco.toon` offers `distributions[2]: production,demo` with `demoSpace: telco`. A demo
    build is Advanced or Preview (`DEMO_EDITIONS`); Telco lists Standard and Advanced, so its demo is Telco × Advanced.
  - **The seed, `tools/seed-telco.mjs`** (the bake's contract). `--scaffold <spacesRoot>` composes the template exactly
    as the bundle does (`assemble` + `composeSpace` over copies of the committed parts). It then lays down
    `spaces/telco` with each sample feed also in `data/inbox/<feed>/`, the Demo Users (`admin`, `ra.analyst`,
    `fm.analyst`, `fm.manager`, `viewer`; built-in roles only) and a `space.toon`. `--apply` signs in as Demo Users and
    over HTTP triggers every Pipeline, then RA's Jobs, then FM's 14 detection Jobs and `tf_anomaly_score`, then bypass's
    six nightly Jobs, each for 2026-07-01…03. It waits on each run and ends with the Alert sweep. It fails unless one
    Alert each of `ra_leakage_found`, `fraud_irsf` and `tb_box_suspected` exists. A Job its own `on_signal` chain is
    already running (a 409, or a run recorded SKIPPED "previous run still in flight") is fired again. In the demo Space
    only, the gap-detection block is switched off (`enabled: false`): the corpus is fixed to July 2026, so FM's `payments` and
    `subscriber_events` raised 262 `sequence_gap` Alerts (one per missing day), and one more each day the bundle aged.
    It was removed until `GAP-DETECTION-ENABLED-IGNORED-1` (fixed 2026-11-12) made the file-template check honour the
    switch. The templates keep it on.
  - **The presenter text**, `products/telco.demo-build.txt` (the bundle's `DEMO-BUILD.txt`): what each App shows, the
    Demo Users, the App switcher, the July 2026 dating, and the reset.
  - **The Product matrix.** `tools/check-product-build.mjs` assembles a Product × Edition × Distribution without
    building anything and checks the plan and descriptor: the Apps (required plus optional), each App's Add-on modules
    on the classpath, nav present and no path drawn twice, the composed Space with its one usage feed, and the demo's
    seed tool and presenter text. CI's `product-matrix` job runs Telco × Standard, × Advanced and × Advanced demo. A
    boot step is left out: it needs the reactor build of the `test` job, and the bake uses Windows console calls.
  - ⚠ **Three defects the bake found, fixed here.**
    (1) A packaging pwsh started by a process that ignores Ctrl+C (an IDE's or agent's shell) handed that to the bake
    server, whose JVM then never ran its shutdown hook. It failed the same way with `la-showcase`. `Start-BakeServer`
    now clears the inherited flag before `CreateProcess`.
    (2) Every masked notification re-parsed the whole registry about twice per Dataset:
    `AuditReadMasking.classifiedColumns` read each Dataset and its lineage, and each read re-scanned the registry. With the
    composed Space's ~60 Datasets the notification queue stayed busy for over 15 minutes after the seed. Now one
    registry snapshot serves the walk (`ComponentStore.list` reads a snapshot's one scan, as `get` already did). After
    the seed the server is quiet in about 215 s, and in 15 s once the 262 gap Alerts below are gone.
    (3) DEMO-BAKE-WAL-RACE-1's mechanism: the bake stopped the server while it still worked through what the seed set
    off, so the Space close abandoned that work after its 10 s budget and left 8 `.wal` files. The bake now waits until
    the server is quiet (under 0.5 s of CPU in a 5 s window, at most 10 minutes) before the Ctrl+C.
  - **Found, not fixed (a board row each):** a Collector's file-template gap check ignores `gap_detection.enabled: false`
    (`GAP-DETECTION-ENABLED-IGNORED-1`). The demo sign-in headline is hard-coded `{{ product }} MFS`
    (`sign-in.component.ts`, from `52aa6e7a7`), so the Telco demo reads "Inspecto Telco MFS". The engine's Space close
    still abandons a busy Space after 10 s without a checkpoint; the bake now avoids it, but a production stop does not.
  **Proof (as built).** Node: `seed-telco.test.mjs` 6/6 (plan order and windows, dry run, Demo User sign-in, the
  SKIPPED and 409 re-fire, a failed run and a missing App Alert fail the seed, the real scaffold), and
  `check-product-build.test.mjs` 3/3 (the seed test also fails a crashed Pipeline run: FAILED with no failed file). With the Product tools, 99/99 across `check-products`, `assemble`,
  `render-product-hosts`, `compose-space`, `telecom-usage`, `check-bundle-spaces` and `check-seed-paths`. Java:
  `ComponentStoreTest` (a snapshot's `list` and `get` re-scan nothing) and `NotificationContentMaskingTest` (the
  classified-column walk is one scan over 20 Datasets). The full reactor gate `mvn -o install -Pedition-advanced` is
  green: 12,059 tests, 0 failures, 148 skipped, 1 h 10 min. `package.ps1 -Product telco -Edition Advanced -Distribution
  demo` bakes `spaces\telco` (seed: 85 calls, none failed; one Alert each of RA, FM and bypass; quiet after 215 s; no
  `.wal`; no build-time path embedded). The unzipped demo bundle boots with `serve-demo.bat`, and `/bootstrap` reports
  `authMode: demo` with the five Demo Users. Signed in as Demo Admin, the landing page carries the Telco tagline, and
  *My work* lists FM's data-charging bypass Incidents. The rail's App switcher lists Revenue Assurance, Case
  Management, Fraud Management and Link Analysis and follows the URL. Reconciliation lists both of RA's
  Reconciliations with their seeded runs, Risk Scores lists `tb_sim`, and Alerts lists the seeded Alerts: the 14 FM
  typologies, `ra_leakage_found`, `ra_data_quality` and `tb_box_suspected`. There is no console error. The final bake
  (gap detection removed) boots with exactly those 17 Alerts and 36 Incidents (the first bake also had 262 `sequence_gap`
  Alerts and their Incidents).

**PA-6 designed and sliced (2026-11-11; D-PA18…D-PA20 signed).** The Platform product's proof is a sample partner App
built only with the kit that passes conformance (§8). Four lanes, in this order (D-PA20):
- **PA-6a — the App authoring kit, `tools/inspecto-app.mjs`.**
  - **An App lives in any folder** (D-PA18): `<app-root>/app.toon`, its Space Templates under `<app-root>/templates/<id>/`,
    its golden data and expectations in `<app-root>/conformance.toon`, and its docs under `<app-root>/docs/`.
    `tools/products.mjs` `gatherModel(root, { appRoots })` reads such folders beside `apps/` (`apps/` stays
    first-party). The assembler takes `--app-root`, and the bundle stages a partner template from its App's folder. An id,
    or a template name, that a first-party App already uses is refused.
  - `inspecto app new <id> --dir <folder> [--industry] [--addons]` scaffolds an App that passes `validate` and `test` as
    created: one Space Template with a sample feed, a Pipeline, a Dataset, a `sql.template` detection Job, an Alert Rule
    and the expected findings.
  - `validate` applies the guard's App rules (`checkModel`) plus the layout and the conformance file.
  - `test` is the conformance kit. Statically: validate, then for each Edition where the App's Add-ons are built,
    assemble a Platform product carrying it and check the plan and descriptor (`check-product-build`). With
    `--bundle <built bundle>` (D-PA19), it also boots that bundle on loopback with a Space from each of the App's
    templates, triggers its Pipelines and the Jobs `conformance.toon` lists, and checks each expectation's keys exactly,
    as `seed-telco.mjs` checks the Telco demo.
  - `package` writes `<id>-<version>.zip` with a SHA-256 manifest. Signing and installing are PA-7.
- **PA-6b — the sample partner App.** `examples/partner-app/`, made only with `inspecto app new` and edited through
  the kit, outside `apps/`. CI runs its static conformance, and a local run proves `test --bundle` against a Standard
  bundle.
- **PA-6c — the Add-on picker.** A CLI first, `tools/pick-addons.mjs`: the Add-ons with their closure and Edition
  floor, and the price when `product-map/private/` is present. It writes a Platform product definition and runs the
  assembler. The page comes next.
- **PA-6d — the builder-kit docs**, in the `layer/platform` hub: Studio, the Pipeline editor, the Step and Collector SPIs,
  the Consequence registry, Job types, the Control API and its OpenAPI document, the Java embedding API (`@PublicApi`),
  and the App kit.

**PA-6a as built (2026-11-11).**
- **The model takes App roots.** `gatherModel(root, { appRoots })` reads each `<app-root>/app.toon` and its
  `templates/<id>/` beside `apps/`, and keeps them apart: `partnerApps` (id → folder), `templateDirs` (template →
  folder). An App id that a first-party App uses, or a template name that a first-party template uses, is a finding. A
  partner App is not held to "id equals folder name", and its `docs` resolve inside its own folder. `carryPartnerApps`
  adds them to a Platform product's optional Apps and their Add-ons to its own. A Domain product is refused, because
  its Apps are its definition.
- **The assembler and the bundle.** `assemble.mjs --app-root <folder>` (repeatable) carries the App. The plan gains
  `partnerTemplates` (`{id, dir}`). `package.ps1 -AppRoot <folder>` passes it on, stages first-party templates from git as
  before, and copies a partner template from its folder (a name collision throws).
- **The kit, `tools/inspecto-app.mjs`.** `new` writes ten files: `app.toon` (one nav entry onto `data-browser`), a
  template with a feed (Pipeline, schema, Dataset), the `<id>_detect` `sql.template` Job and its findings Dataset, a
  sample with three accounts over the threshold and five look-alikes at or under it (two exactly at it), `conformance.toon` and
  `docs/index.md`. `validate` runs `checkModel` with the App on the Platform product, then the layout and
  `conformance.toon` (template, day, Jobs, each expectation's Dataset, key and `expected[N]` list, and the golden data
  present). `test` holds the static conformance on every Edition that builds the App's Add-ons
  (`check-product-build`'s `checkPlan`, plus the template staged from the App's folder). With `--bundle` it boots the
  bundle on loopback, with no sign-in, over a scaffolded Space (`kit-<template>`). It triggers every Pipeline, then the
  listed Jobs for the corpus day, then reads each Dataset's `rows` and compares the key set exactly. A bundle that
  answers 401 or 403 is named as one that needs sign-in. `package` writes a deterministic
  `<id>-<version>.zip` (Node's own deflate and CRC-32, a fixed entry time) with `MANIFEST.sha256`, and refuses an App
  that does not validate.
- **Deliberately not done.** The starter ships no Alert Rule. A rule over a `sql.template` sink needs the sink's
  zero-row seed parquet at template create (the Telco templates ship one), and the kit, being Node, does not write
  parquet. `config/pending/alert-rules/` defers only rules over score outputs. Boot conformance needs a bundle without
  sign-in: it does not sign in to an OIDC bundle. Both go to PA-6b, with the sample App.
**Proof (as built).** `tools/inspecto-app.test.mjs` 7/7 covers: the scaffold passes validate and static conformance on
all four Editions; the starter's planted set; the assembler stages the partner template from its folder; validate's
refusals (a reused App id, a reused template, a broken conformance file, missing golden data); golden conformance on a
mock (Job windows, every Pipeline triggered, an extra key, a failed Job, a 401); the scaffolded Space; and the
deterministic zip with its manifest. With the Product tools it is 90/90. `assemble.mjs --app-root` refuses the Telco
Domain product. `package.ps1 -Product inspecto -Edition Basic -NoRuntime -AppRoot <app>` stages the partner template
from its folder beside the 30 first-party ones. On that bundle, `inspecto-app.mjs test --bundle inspecto-deploy` boots
it, ingests the golden data, runs the Job and holds `acme_limits_findings` to exactly A-001, A-002, A-003. With the
expectation changed to A-001, A-104 the same run fails, naming the keys it found.

**PA-6b as built (2026-11-11).**
- **The sample partner App, `examples/partner-app/`** (App `acme-spend`, "Acme Spend Watch"), is the unedited output
  of `node tools/inspecto-app.mjs new acme-spend --dir examples/partner-app --title "Acme Spend Watch"`, outside
  `apps/`. The kit's own `new` was fixed to write an empty Add-on list as `addons[0]:`, with no trailing space.
- **CI holds it.** A guards step, "the sample partner App holds its conformance", runs `validate` and `test` on it.
  `tools/inspecto-app.test.mjs` also checks it validates and assembles on all four Editions.
- **Boot conformance on a bundle with sign-in (decided).** A Standard or Advanced bundle carries `inspecto-oidc.jar`.
  Without an identity provider configured, its Authenticator does not construct and the server exits 1, and Demo Auth
  ships only in demo builds. So `test --bundle` leaves `inspecto-oidc.jar` off the classpath of its loopback boot over
  the scratch Space and runs without sign-in (`isOidcJar`). Conformance tests the App, not sign-in. The bundle itself
  is not changed.
- **The starter's Alert Rule stays out (decided):** `APP-KIT-ALERT-RULE-1` on the board, held for demand. A rule over
  a `sql.template` sink needs its zero-row seed parquet, which the Node kit does not write.

**Proof (as built).**
- `tools/inspecto-app.test.mjs`: 9/9.
- `package.ps1 -Product inspecto -Edition Standard -NoRuntime -NoUi -AppRoot examples/partner-app` built and passed its
  boot smoke, staging `acme-spend` beside the first-party templates.
- On that bundle, the PA-6a kit's `test --bundle inspecto-deploy` fails: the server exits 1.
- PA-6b's kit boots it without the OIDC jar and holds `acme_spend_findings` to exactly A-001, A-002, A-003. With the
  expectation changed to A-001, A-104, the same run fails, naming the keys it found.

**PA-6c as built (2026-11-11; D-PA21).**
- **The picker, `tools/pick-addons.mjs`.** With no arguments it lists the Inspecto Platform product's 17 Add-ons, each
  with:
  - its Order Code;
  - its closure: the Add-ons that own a module its modules require, transitively, leaving out base and internal modules;
  - its Edition floor: the lowest Edition that builds it and its closure;
  - its list price, when `product-map/private/prices.csv` is present.
- **A pick.** `--pick a,b --id <id> [--title T]` grows the pick by its closure until the floor stops moving, prices it as
  Edition + Add-ons, and assembles it on every Edition from the floor up. It prints the definition; `--write` writes it
  to `product-map/private/products/<id>.toon`.
  - Example: Screening needs Incidents, LA Investigations, Scoring Lists and Workflow SLA, so its floor is Standard.
  - Refused: an unknown Add-on, an id that is already a Product, a malformed id, an empty pick, and a pick no Edition
    builds.
- **The picked Platform product** (`narrows: inspecto`) keeps Inspecto's host, templates and docs. It keeps an optional
  App only when the pick holds every Add-on the App requires, so Link Analysis rides only with `linkAnalysis`.
- **Building it in Inspecto's place.** `narrowModel` (in `tools/products.mjs`) swaps it into the model where the Platform
  product was, so the guard's "one default build per host" and "every committed template is shipped" rules still hold.
  `narrowModel` refuses a file that:
  - narrows nothing, or narrows a Domain product;
  - moves to another host;
  - names an Add-on its base lacks;
  - takes another Product's id.
- **Building it from the file.** `assemble.mjs --product-file <f>` and `package.ps1 -ProductFile <f>` take it; `-Product`
  is then the file's id, and passing both is refused.
- **Deliberately not done.**
  - The page, which is `ADDON-PICKER-PAGE-1` on the board, held for demand.
  - Trimming jars and templates to the pick: the module set is still the whole Edition's classpath, as in every
    Product build since PA-2, and trimming stays with `customer-build` until PA-10. The pick decides the plan's
    `addons`, the Editions and the Apps.

**Proof (as built).**
- `tools/pick-addons.test.mjs`: 6/6. It covers:
  - every Add-on's floor builds its closure;
  - a synthetic transitive closure;
  - a pick assembling on each Edition and refused below its floor;
  - the optional-App rule;
  - the written file reading back and building through `loadProductFile`;
  - priced and unpriced totals;
  - every refusal.
- With the other Product tools: 104/104 plus the picker's 6.
- `package.ps1 -ProductFile <acme-console.toon> -Edition Standard -NoRuntime -NoUi` (Incidents + Screening, Standard
  floor) built and passed its boot smoke. Its `lib/product.json` and `edition.properties` name `acme-console` /
  "Acme Console" with no Apps. Passing `-Product` with it is refused.

**PA-6d as built (2026-11-11).** The builder-kit guide is
[`okf/product/layer/platform/user-guide-builder-kit.md`](../okf/product/layer/platform/user-guide-builder-kit.md), in the
`layer/platform` hub. It opens with a table of which part to use for which job, then covers:
- Studio and the Pipeline editor, in the Builder Lens;
- the four extension points (Step, Job type, Consequence, Collector connector), with their interfaces, service files
  and in-repo examples;
- packs from `tools/scaffold.mjs` and the module manifest;
- the Control API and its generated OpenAPI document;
- `@PublicApi`;
- the App kit and the Add-on picker.

It links to the deeper pages rather than repeating them. The verification review checked its interfaces, paths
and manifest keys against the code at 189c72a26, and corrected two points:
- only `StepExecutor`, `PipelineNodeType` and `CollectorConnectorFactory` carry `@PublicApi`;
- `processor` and `nodetype` scaffolds are classpath jars, not packs.

**Deliberately not done.** Marking `JobTypeProvider`, `ConsequenceProvider` and `CollectorConnector` `@PublicApi`, and a
guard that holds the marked surface stable: `PUBLIC-API-SURFACE-1` on the board, held for demand. The guide says
which interfaces are marked and tells a pack author to pin the Platform version.

**PA-7 designed and sliced (2026-11-11; D-PA22…D-PA25 signed).** Proof (§11 table): install, upgrade and remove an
App on a running server with no lost config, and a trial build that runs, warns, then turns read-only.

What exists to build on:
- **The Add-on gate.** `EditionFeatures.holdsInvestigationsAddon()` is the one runtime check today, and its comment
  marks it as "the hook a signed entitlement replaces". `-Daddons.laInvestigations` is an honour-system setting.
- **The entitlement key.** `ModuleManifest.entitlementKey` is reserved and never read (D-MR3, now superseded by
  D-PA22).
- **Pack trust.** The pack loader already trusts jars by SHA-256 allowlist and can require a jar signature
  (`JobPackManager`).
- **Live Product data.** The server learns its Product and Apps from `lib/product.json` (`ProductDescriptor`), read
  once at boot.
- **No read-only mode exists.**
- **Signing needs nothing new.** The JDK verifies Ed25519 and Node's `crypto` signs it.

**Decisions (operator, 2026-11-11, all as recommended).**
- **D-PA22: an offline signed file.** An Ed25519-signed entitlement file, with no phone-home. It holds:
  - customer, Product, Edition;
  - the Apps and Add-ons it allows;
  - a node count, which is recorded and shown but not enforced;
  - `notBefore` and `expires`, and the key id.
  - It is checked at boot and by every gate that reads the Installed state. This supersedes D-MR3 "no licence
    engine".
- **D-PA23: no file means today's behaviour.** A production bundle with no entitlement runs as it does now: every
  built Add-on on, and Home telling the admin none is installed. Only a `trial` build must carry one, so nothing
  installed today breaks.
- **D-PA24: expiry gives grace, then read-only.**
  - For 14 days the admin and Home show warnings.
  - Then the server is read-only: ingest, Jobs, Pipeline runs and config saves are refused (`423 Locked`, naming the
    expiry), while reads, dashboards and exports keep working.
  - No data is removed, and a new file restores everything with no restart.
- **D-PA25: an upgrade changes the template and offers the diff.** It replaces the App's templates and nav, so new
  Spaces get the new version. Each Space already made from the App is offered a dry-run diff, applied through the
  normal config-change path (SaveGate, audit), so nothing a customer authored changes without an admin's yes.

**Decided here, not asked** (the standing preference to decide and record):
- **Key custody.** The issuer's private key stays in `product-map/private/keys/`, which is gitignored. The trusted
  public keys are compiled into the core jar as resources, by key id, so an installed bundle cannot be re-keyed by
  editing a file beside it. Rotation adds a key id, and old ids stay trusted until they are removed in a release.
- **The file is canonical TOON plus a detached signature.** `entitlement.toon` and `entitlement.sig` (base64 Ed25519
  over the file's bytes) live in the install's `conf/` folder, or wherever `-Dentitlement.file` points. A file that
  does not verify fails the boot, naming the file and the key id. It is never treated as "no file".
- **An entitlement narrows; it never adds.** It cannot switch on an Add-on or App the build lacks. With a file
  present, an Add-on the build has but the file omits is absent, through the existing Installed gate, with the
  `/bootstrap` `featureReasons` wording "not in this installation's entitlement".
- **The pilot Distribution moves to PA-10.** It is customer Collectors pre-wired, which needs the customer record.
  `PLANNED_DISTRIBUTIONS.pilot` is re-pointed there.

**Slices (one lane each, in this order):**
- **PA-7a: signed entitlements.**
  - `tools/entitle.mjs`: `issue` (from a private customer record, writing `entitlement.toon` + `.sig`), `verify` and
    `show`.
  - Server side, an `Entitlements` class (in `inspecto-util` or `inspecto-etl`, beside `EditionFeatures`):
    - it verifies against the compiled-in keys;
    - the Installed gate reads it;
    - `holdsInvestigationsAddon()` reads it;
    - `/bootstrap` gives admins a summary: customer, expiry, days left, and the node count.
  - Tests: a real-HTTP gate test for an omitted Add-on, a tampered file failing the boot, an unknown key id, and no
    file meaning today's behaviour.
- **PA-7b: expiry and the trial Distribution.**
  - The grace window and the read-only state, one check in the route dispatcher plus the scheduler and ingest.
  - `423 Locked` on write routes, with an `/api/v1` error shape and an OpenAPI entry.
  - A renewal file picked up without a restart.
  - `trial` becomes a built Distribution: `package.ps1 -Distribution trial -EntitlementFile <f>` refuses a file with
    no expiry and stages it into `conf/`. `PLANNED_DISTRIBUTIONS.trial` goes.
  - Proof: a trial bundle with a clock pinned through a test property, in grace and then read-only.
- **PA-7c: signed App packages.**
  - `inspecto-app.mjs sign` (an Ed25519 signature over `MANIFEST.sha256`, with the signer's key id) and `verify`.
  - A partner key is trusted by an install through an allowlist of key ids, like the pack allowlist. First-party Apps
    are signed with the issuer key.
  - The App kit's `package` output gains the signature.
- **PA-7d: live App install.**
  - Admin-only routes, through the `endpoint` skill's gate order:
    - `GET /apps`;
    - `POST /apps?dryRun=true` and `POST /apps` (a signed package);
    - `PUT /apps/{id}` for an upgrade;
    - `DELETE /apps/{id}`.
  - Install verifies the signature and the manifest hashes. It checks the Platform range, the Add-ons it needs, and the
    entitlement's Apps when a file is present. It refuses a template name collision.
  - The dry-run diff lists the templates, Datasets, Jobs, Alert Rules and nav entries the App adds, changes or removes.
  - Install unpacks the App to an install-owned `apps/` folder and registers its templates for Space create.
  - The descriptor's Apps and nav become reloadable, so `/bootstrap` shows them at once.
  - Upgrade follows D-PA25. Remove takes the App's templates and nav away, while Spaces made from it keep their config
    (the existing inert-config semantics) and are listed in the answer.
  - Proof: the sample partner App, installed, upgraded to 0.2.0 and removed on a running Standard bundle, with every
    Space's config byte-identical before and after.

**PA-7a as built (2026-11-11).** Guide: [`okf/product/layer/platform/admin-guide-entitlements.md`](../okf/product/layer/platform/admin-guide-entitlements.md).
- **The file lists modules, so the server needs no Add-on map.** `tools/entitle.mjs issue` resolves the Product,
  Edition, Apps and Add-ons (grown by their closure) and writes the optional modules the customer may run: the
  Edition's own platform (modules no Add-on owns) plus each entitled Add-on's modules, as `customer-build` keeps them.
  The server then decides by each module manifest's `offeringRole` and id: base and internal modules always run, and
  an optional or provider module runs only when listed.
- **The server side.** `com.gamma.module.Entitlements` lives in `inspecto-util`, beside the manifests, which `etl`, `engine` and
  control all read.
  - It verifies Ed25519 with the JDK alone, against `META-INF/inspecto/entitlement-keys.properties` in the core jar
    (`keyId=base64 SPKI`; a classloader cannot list a folder in a jar).
  - It caches the file once per boot.
  - Enforcement points:
    - route dispatch (`requireModuleEnabled`: 503 naming the entitlement);
    - `EditionFeatures.present`, `lockedReason` and `holdsInvestigationsAddon` (the file decides once installed);
    - `JobService` (an unentitled Job type is not registered);
    - `ModuleActivator` (a third INERT reason, so dependants and Apps follow);
    - `/bootstrap` (`features`, and `entitlement` for `canAdminister`).
  - `ControlApi` refuses the boot, before binding a port, for a file that does not verify or that names another Product
    or Edition. A Preview build reports Advanced.
- **The issuer key** is `inspecto-issuer-1`. Its private half is in the shared checkout's gitignored
  `product-map/private/keys/`, and its public half is committed.
- **Deliberately not done.**
  - The other extension points (Consequences, step kinds, Collector connectors, maintenance tasks, boot hooks):
    `ENTITLEMENT-SPI-NARROWING-1`, held for demand.
  - Expiry behaviour, renewal without a restart and the Home notice: PA-7b.

**Proof (as built).**
- `EntitlementsTest` 6/6: a file signed by `entitle.mjs` verifies in Java byte for byte; a changed byte, an unknown key,
  another schema and a bad signature are refused; no file means everything is entitled; roles; the INERT reason and its
  dependants; the core jar carries `inspecto-issuer-1`.
- `ControlApiEntitlementTest` 6/6, over real HTTP on Basic:
  - left out: `/screening/check` answers 503 naming the entitlement, `/modules` shows screening INERT, and
    `/bootstrap.entitlement` holds the summary;
  - listed (with the modules it requires): it reaches the module, ACTIVE;
  - no file: today's behaviour and `{installed: false}`;
  - a tampered file, an unknown key id or a missing signature fail the boot, naming the file;
  - another Edition fails the boot;
  - the screening Job type registers with no file and when listed, and not when left out.
- `tools/entitle.test.mjs` 6/6. `EditionFeaturesTest` 7/7.
- **Gates.**
  - One full Advanced run before the review fixes: 16,142 tests, 1 failure. That failure was
    `ImportLoaderInventoryTest`, which needed `conf/entitlement.toon` added to `ALLOWED` (it lies outside every Space and
    must be signed). The modules after `inspecto-processor` were skipped once it failed.
  - The review then found `featureReasons` missing for left-out optional modules; it is now filled through
    `Entitlements.unentitledFeatures()`.
  - After the fixes, by operator call, **only targeted tests ran, not the full gate**. They passed:
    - `EntitlementsTest`, `ModuleManifestsTest`, `EditionFeaturesTest`;
    - `ApiContractTest`, `ContractFragmentsTest`, `OpenApiPathsContractTest`, `ModuleManifestGuardTest`;
    - `ControlApiBootstrapProductTest`, `ImportLoaderInventoryTest`;
    - `ControlApiEntitlementTest`, which now also asserts the reason.

**PA-7b as built (2026-11-11).** Guide: [`okf/product/layer/platform/admin-guide-entitlements.md`](../okf/product/layer/platform/admin-guide-entitlements.md) §§ Expiry
and The trial Distribution.
- **States (`Entitlements.state()`).** `NONE`, then `OK`, then `EXPIRING` (30 days or fewer left), then `GRACE` (14
  days past expiry), then `READ_ONLY` (from expiry + 15 days, or a trial build without its file).
  - `today()` reads a clock that only a code-level test seam pins (`clockForTests`). It is never a system property,
    because a customer could pin it.
- **Renewal.** `current()` checks the two files' size and mtime every 5 seconds and verifies only on a change.
  - A replacement that does not verify, or a removed file, keeps the last verified entitlement in force and names a
    `problem()`. A running server never drops to "no file".
  - The Product and Edition check moved from the boot check into every read (`expectBuild`, set by `ControlApi` at
    boot, which re-reads under it). So a renewal for another build is refused too.
  - A file before its `notBefore` is refused.
- **Read-only enforcement**, at three seams:
  - `ControlApi.requireWritable`, after the module gates: 423 `ENTITLEMENT_READ_ONLY` (a new `ErrorCodes` entry and
    OpenAPI enum value) unless GET/HEAD/OPTIONS or a route whose capability-manifest exemption category is in
    `READ_ONLY_OPEN` (`read-shaped`, `group-gated`, `stateless-compute`, `identity-flow`);
  - `PipelineScheduler.frozen()` at its five entry points (poll, acquire, upstream commit, Dataset write, stream
    drain), logged once per change;
  - `JobService.submitRun`, which records `SKIPPED` with the reason.
  - Added after the review found background writers outside those seams:
    - `CollectorService.runPipeline` refuses a real run, the funnel every caller passes (a dry run still runs);
    - the watched-folder import (`InboxWatch`) leaves waiting files for the first writable poll;
    - the scheduled Enrichment recompute (`EnrichmentService`) skips its tick;
    - `ExchangeSnapshotSweep` publishes no snapshot.
  - `self-verifying-public` joined `READ_ONLY_OPEN`, so a shared dashboard's query keeps working. So does a
    delivery-status callback, which only records what happened to a message already sent.
  - Known limit: "trial" is read from `lib/product.json`, which a customer can edit. Like every local check, it keeps
    an honest customer honest; it is not tamper-proof.
- **`/bootstrap.entitlement`.** Every signed-in caller gets `publicSummary` (state, `readOnlyFrom`, reason). An
  administrator gets `adminSummary`, which adds the file's fields, `problem` and `forAdministrator: true`.
- **The trial Distribution is built.**
  - `DISTRIBUTIONS` gains `trial`. `pilot` stays planned, re-pointed to PA-10.
  - All three Products list `trial`.
  - `package.ps1 -EntitlementFile` verifies the file with `entitle.mjs`, checks the Product, the Edition and (for
    trial) an expiry, and stages `conf/entitlement.toon` and `.sig`. It is required for trial and refused for demo.
  - A trial descriptor makes `ControlApi` call `requireFile`. Zip and folder names are unchanged; `lib/product.json`
    says `trial`.
- **The UI.**
  - `SessionService.entitlement` holds the summary.
  - `<inspecto-entitlement-banner>`, built on `<inspecto-alert>`, is mounted beside the connectivity banner in the
    console layout and in the la-app shell. Read-only is shown to everyone; expiring, grace and a renewal not taken
    are shown to administrators only.
  - Home tells an administrator when no entitlement is installed (D-PA23).

**Proof (as built), targeted tests only (operator: no full gate).**
- `EntitlementsTest` 12/12: the calendar state by state, no expiry, a trial without a file, renewal and a tampered
  or removed replacement, a not-yet-valid file, and a file for another build at boot and on a renewal.
- `JobServiceReadOnlyTest` 1/1: a fire is SKIPPED with the reason, then runs once writable. `JobServiceTest` 61/61.
- `ControlApiEntitlementTest` 7/7 over real HTTP, with the clock pinned. Besides the points below, in read-only a shared
  dashboard's query is not refused, `runPipeline` refuses a real run and a dry run still runs:
  - grace is still writable and `readOnlyFrom` is reported;
  - on the first read-only day, `PUT /settings/modules` answers 423 naming the expiry, while `GET /modules` and
    `POST /bi/query` pass;
  - a renewal lifts read-only with no restart.
- The Product tools: 116/116, with `trial` assembling.
- After the review fixes, also: `ControlApiPipelineRunTest`, `EnrichmentServiceTest` 12/12, `ExchangeSnapshotSweepTest`,
  the OpenAPI contract tests and `ImportLoaderInventoryTest`. UI: the banner, Home and la-app shell specs.
- **A packaged trial bundle, booted from its own root.** `package.ps1 -Product inspecto -Edition Basic -Distribution trial
  -EntitlementFile <f> -NoRuntime -NoUi` used an entitlement issued by `inspecto-issuer-1` that expired 2026-10-01.
  - Packaging staged `conf/entitlement.toon` and `.sig` and passed its boot smoke; `lib/product.json` says `trial`.
  - Booted: `/bootstrap.entitlement` is `read-only` (customer `trial-proof`, `readOnlyFrom` 2026-10-16).
    `PUT /settings/modules` answers 423 `ENTITLEMENT_READ_ONLY`, naming the expiry; `GET /modules` answers 200.
  - `-Distribution trial` without `-EntitlementFile` is refused before the build starts.

**PA-7c as built (2026-11-11).** The App kit signs and verifies packages (`tools/inspecto-app.mjs`, Node only).
- **`package --key <pem> [--key-id <id>]`** adds `<id>/MANIFEST.sig` (`keyId=…`, `signature=<base64 Ed25519 over MANIFEST.sha256>`).
  The key id defaults to the key file's name. A `.pem` in the App folder is never packaged. Without `--key` the
  package is still written, and the command says that a server installs only a signed one.
- **`verify --package <zip> [--trust <keys.properties>]…`** reads the zip back with the kit's own `unzip`, which
  refuses an entry that escapes its folder. It holds:
  - exactly one App folder;
  - every manifest entry present with its SHA-256, and nothing unlisted;
  - a `MANIFEST.sig` whose key is trusted and whose signature matches the manifest;
  - `app.toon`'s id equal to the folder name.
- **Trust.** The default is the issuer keys the core jar carries (first-party Apps are signed with `inspecto-issuer-1`).
  Each `--trust` file adds partner keys: an install's `conf/app-signers.properties` lists them as `keyId=base64`, the
  allowlist the server reads in PA-7d.
- **`keygen --key-id <id> --out <dir>`** gives a partner `<id>.pem` and `<id>.properties`, the line an install adds to
  that allowlist. A key id is never reused.

**Proof (as built).**
- `tools/inspecto-app.test.mjs` 11/11. The new cases: a signed package verifies; each of these is refused (an
  untrusted key, a changed file, an extra file, a changed manifest, an unsigned package, two App folders, a bad key
  id, a reused key id); `unzip` round-trips and refuses `../`.
- By hand, the sample partner App signed with a partner key verifies with `--trust`, is refused without it, and an
  unsigned package is refused.

**PA-7d as built (2026-11-11).** Guide: [`okf/product/layer/platform/admin-guide-apps.md`](../okf/product/layer/platform/admin-guide-apps.md).
- **The routes (`AppInstallRoutes`).** `GET /apps`, plus `POST /apps`, `PUT /apps/{id}` and `DELETE /apps/{id}`, each
  `canAdminister` with a `?dryRun=true` plan. They are pod-scoped, in `CapabilityManifest` and in the core OpenAPI
  fragment. The gates, in order:
  - no Spaces root: 503;
  - not a Product build of a Platform product: 409;
  - a package that does not verify: 422;
  - the App's Platform range, or an Add-on the entitlement leaves out: 422;
  - an id or template conflict: 409.

  Read-only is 423 before the handler. Every change is audited.
- **The package (`AppPackage`)** is verified with the JDK alone, as the kit signs it (PA-7c):
  - the zip is read with `ZipInputStream`, so every entry checked is an entry extracted;
  - names that escape their folder and duplicate names are refused, and the size is capped;
  - one App folder, an Ed25519 `MANIFEST.sig` by a trusted key, every file holding its manifest hash and nothing
    unlisted, and `app.toon`'s id equal to the folder name.

  Trust is the issuer keys plus `conf/app-signers.properties`, which never redefines a trusted id.
- **The registry (`InstalledApps`).**
  - The App unpacks into `apps/<id>/`, with `installed.toon` recording its version, signer and templates.
  - Its templates go into `_templates/<t>/` with a `.installed-by` marker; a shipped template is never replaced.
  - Each directory is staged and then swapped in, and the old copy is put back on failure.
  - `/bootstrap` merges installed Apps into the descriptor (`ProductDescriptor.withInstalled`; their nav from
    `app.toon`).
- **Provenance and the D-PA25 offer.** `SpaceManager` writes `<space>/.inspecto/created-from.toon` for a Space made from a
  marked template.
  - An upgrade answers, per such Space, `new`, `update`, `customised` or `dropped` per config file.
  - "Unchanged since created" reads `created-from.sha256`, written beside the provenance with each config file's hash as
    the Space was created (after seeding and stamping). So it holds a Space one or more upgrades behind.
  - A Space without hashes falls back to comparing decoded TOON with the server's Job stamp set aside.
  - Applying a change goes through the existing config routes; there is deliberately no apply route.
- **Decided here.**
  - The entitlement's `apps` list is not checked: a partner App is unknown to the issuer's Product definitions.
    Its Add-ons are checked against the entitlement when a file is installed.
  - Spaces made before PA-7d record no origin, so they are offered nothing.
  - The console host still draws its own nav (PA-6), so an installed App's nav shows where the host draws
    `/bootstrap.product.nav` (la-app).

- **Review fixes (2026-11-11).**
  - One install-wide lock (`InstalledApps.LOCK`) held across plan and apply, since each request builds its own registry.
  - Package names that Windows would rewrite are refused (`AppPackage.unsafeSegment`): a trailing dot or space,
    `?*<>|"`, control characters, device names, a hidden file (only `.gitkeep` allowed), or two names one case apart.
    So a package can never write the server's marker or record.
  - `apply` stages every directory before swapping any, so a file that cannot be written changes nothing. A failure to
    delete an old copy no longer fails the request.
  - The upgrade baseline is the creation hash.
  - `${SPACE}` is rendered only in `.toon` files, as `SpaceManager` copies.
  - The template gallery skips dot folders.
  - A Space whose record does not read is left out of the offer rather than failing the request.
  - Accepted: removing an App while a Space is being created from its template is not locked against it. Space
    creation copies the template in one step, and removal leaves Spaces alone.

**Proof (as built), targeted tests only (operator: no full gate).**
- `ControlApiAppInstallTest` 5/5 over real HTTP:
  - the full cycle (dry run, install, a Space from the template, upgrade with `update`/`new` then `customised`
    offers, remove), with every Space's config byte-identical at each step;
  - 401/403/200 on the capability;
  - each 422: unsigned, untrusted signer, tampered, not a zip, an id that disagrees, and the range rule;
  - the 409s and the 404;
  - Domain product and no descriptor give 409.
  - After the review: `update` holds one upgrade behind with no local edit, and `customised` appears only after one;
    a forged `.installed-by.` and a case-only duplicate are refused.
- **Live, on a Standard bundle** (`package.ps1 -Product inspecto -Edition Standard -NoRuntime -NoUi`), booted from
  its root with the sample partner App signed by a partner key listed in `conf/app-signers.properties`:
  - `GET /apps` reports `liveInstall` and the signers `acme-proof-1` and `inspecto-issuer-1`;
  - the dry run lists the Pipeline, Schema, Job, Datasets and sample data;
  - install 200, and `/bootstrap` serves the App's nav;
  - a Space is created from `acme-spend`;
  - the upgrade 0.1.0 to 0.2.0 offers that Space `update config/jobs/acme_spend_detect_job.toon`;
  - remove 200, with `spacesKept` `[acme-proof]`, the template and nav gone;
  - every Space's config files stay byte-identical after install, upgrade and remove;
  - a package with one byte of file data changed: 422.
- `CapabilityManifestTest`, `OpenApiPathsContractTest`, `ApiContractTest`, `ContractFragmentsTest`,
  `ControlApiBootstrapProductTest`, `ProductDescriptorTest`, `SpaceManagerTemplateGateTest` and
  `ImportLoaderInventoryTest` pass.

**PA-8 designed and sliced (2026-11-12; D-PA26…D-PA31 decided and recorded while the operator was away, per the
standing preference).** Proof (§11 table): Inspecto Telco installs on a cluster and on an offline host.

What exists to build on (measured 2026-11-12):
- **A Dockerfile in every bundle.** `package.ps1` step 6b-2 writes `deploy/Dockerfile` and its `.dockerignore`. The
  image is the unzipped bundle on a digest-pinned `debian:stable-slim`, with the bundle's own jlink `runtime/`, uid
  10001, a `HEALTHCHECK` on `/health`, and `bin/serve.sh` as entrypoint. It is valid only in the `linux_amd64` bundle,
  and it carries no Product in its name or labels.
- **One Helm chart, `deploy/helm/inspecto`.** A single-replica StatefulSet with one PVC, a read-only root filesystem
  and an exec `/tmp`, TLS in the JVM and Secret-backed environment. It names no Product. The N-pod mode
  (`SCALEOUT-HELM-NPOD-1`) is on hold with Postgres (operator, 2026-10-23).
- **Air-gap pieces.** `package.ps1` stages the DuckDB extensions per platform under `lib/duckdb/<platform>/`, and
  `-RequireExtensions` makes a missing one a failure. The loader never falls back to a networked `INSTALL` once that
  folder exists (D-8). The jlink runtime makes a bundle need no JVM on the host.
- **The assembler** lists `container` and `airgap` as `PLANNED_TARGETS` and refuses them.
- **On this desk:** Docker 29.5.3, the pinned `debian` base and an `alpine/helm` image are local; there is no Helm
  binary and no Kubernetes cluster; the Linux jmods are in `C:\.jdks\jdk-27-linux` (so `-GraalvmCache C:\.jdks`
  cross-builds the `linux_amd64` bundle); there are no DuckDB 2.0 Linux extensions.

**Decisions (decided 2026-11-12, operator away).**
- **D-PA26: the container target post-processes the Linux bundle.** The image is built from the bundle's own
  `deploy/Dockerfile`; there is no second Dockerfile. It is named `inspecto-<product>` (the Platform product stays
  `inspecto`) and tagged `<platform version>-<edition>`, plus `-trial` for a trial build. The Dockerfile gains OCI
  labels from the plan: title, version, vendor, and `io.inspecto.product`, `.edition`, `.distribution`. The demo
  Distribution is refused for `container` and `airgap`: it binds 127.0.0.1 and signs in Demo Users, so it is a desk
  build, not an install.
- **D-PA27: one chart source, stamped per Product.** `deploy/helm/inspecto` stays the only chart source.
  `tools/ship.mjs chart` writes a copy per Product build with:
  - the chart name equal to the image name, the chart version equal to the platform version, `appVersion` equal to
    the image tag;
  - `values.image.repository` set to the image name, and a `product` block (id, title, edition, distribution);
  - labels `app.kubernetes.io/name` (the chart name), `app.kubernetes.io/version`, `inspecto.io/product` and
    `inspecto.io/edition`.

  It stays single-replica. PA-8 does not lift the `SCALEOUT-HELM-NPOD-1` hold. When that row is built, it changes the
  one source, and every Product's chart inherits it.
- **D-PA28: the air-gapped bundle is one tar of everything an offline site needs.** `inspecto-<product>-<edition>-airgap.tar`
  holds:
  - the `linux_amd64` bundle zip, for a host install;
  - the image as `docker save` output, for a cluster or a container host;
  - the chart as a `.tgz`;
  - `install-offline.sh`, which checks `SHA256SUMS` first, then does `host` (unzip, optional systemd service) or
    `load` (load the image and retag it for a private registry), and prints the `helm install` line;
  - `airgap.json`, the manifest (Product, Edition, image, chart, extensions found).

  The DuckDB extensions for `linux_amd64` are required (`-Target airgap` implies `-RequireExtensions`).
  `ship.mjs airgap --allow-missing-extensions` exists only for a desk proof: it writes `complete: false` and the
  missing names into `airgap.json`, and the installer then prints a warning. No Helm, kubectl or registry binary is
  shipped; the site brings its own.
- **D-PA29: `package.ps1 -Target container|airgap` is the one entry.** It passes `--target` to the assembler. The plan
  then carries `image` and `chart`. After the zips are written, it runs `tools/ship.mjs` on the `linux_amd64` bundle,
  and refuses when none was built (that bundle needs the Linux jmods). Docker is needed only for the image; the chart
  needs only `tar`. The `windows` and `linux` targets are unchanged.
- **D-PA30: what proves it here.**
  - Charts: `helm lint` and `helm template` through the local Helm image, for every Product.
  - The image: built from a packaged Telco bundle, booted with `--read-only`, answering `/health` and `/ready`, with
    the right `lib/product.json`.
  - The offline host: a `debian` container with `--network none` runs `install-offline.sh host` from the air-gap tar
    and boots Telco; a second run loads the image from the tar after the local copy is removed.
  - A real cluster install is not possible on this desk (no cluster). It is recorded as not run, with a BACKLOG row
    that carries it.
- **D-PA31: the order of the PA-8 lanes.** The per-Product chart and the targets in the assembler (8a), the image
  through `package.ps1 -Target container` (8b), the air-gapped bundle and the guide (8c).

**Slices (each its own lane).**

| Lane | Builds | Proof |
|---|---|---|
| **PA-8a** Per-Product chart | `assemble.mjs` accepts `container` and `airgap` (plan fields `image`, `chart`; demo refused); `tools/ship.mjs chart`; the base chart's labels and `product` values | node tests; `helm lint` and `helm template` for inspecto, la and telco |
| **PA-8b** Per-Product image | OCI labels in the bundle's Dockerfile; `tools/ship.mjs image` (build, tag, `docker save`); `package.ps1 -Target container` | a Telco Standard image built and booted read-only |
| **PA-8c** Air-gapped bundle | `tools/ship.mjs airgap`, `install-offline.sh`, `package.ps1 -Target airgap`; the guide `admin-guide-container-airgap.md` | the offline-host boot from the tar, with `--network none` |
**PA-10 designed and sliced (2026-11-12; D-PA32…D-PA37 decided while the operator was away, per the standing
preference).** Proof (§11 table): a customer build of the pilot customer matches today's, plus an entitlement.
D-PA26…D-PA31 are PA-8's.

What exists to build on:
- **`tools/customer-build.mjs`** (`PRODUCT-MAP-1` S5). It resolves a private customer record (`edition` plus
  `orderCodes`) against the Offerings and manifests, writes a priced bill of materials, and with `--bundle` trims an
  Edition bundle. It knows no Product or App, and writes no entitlement.
- **The pilot customer** is the made-up `demo-telco` (D-PM6): Standard, Link Analysis, and the `telco-fraud` Solution
  pack. Its record is in `product-map/private/customers/` (gitignored).
- **The assembler's plan** still holds the whole Edition's modules (PA-2: "trimming is customer-build's job until
  PA-10").
- **`tools/entitle.mjs issue`** builds an entitlement from command-line flags. No customer record feeds it.
- **The Product Map has six kinds.** Products, Apps and Industries sit outside it, and their `docs:` borrow other
  items' hubs (`products/telco.toon` points at the `industry-telecom` Solution pack's hub).

**Decisions (decided here, 2026-11-12).**
- **D-PA32: three new Product Map kinds.** `product`, `app` and `industry` items, at `product-map/<kind>/<id>.toon`,
  link to their definition by `product:`, `app:` or `industry:`.
  - The guard requires one item per definition, and every link must resolve.
  - An App item is billable: `enforcement: build`, Order Code `INS-APP-<ID>`.
  - A Product item and an Industry item are not billable. A Domain product is priced as Edition + Apps (§10). The
    Industry's pack is already sold as a Solution pack.
  - D-PA6 asks for an Order Code per App × Edition. PA-10 gives each App one Order Code, and the bill puts it beside
    the Edition line. A price per App × Edition waits for the first priced App: board row
    `PRODUCT-MAP-APP-EDITION-PRICES-1`, held for demand.
- **D-PA33: a customer build is an assembly.**
  - The customer record names its Product (`product:`). It defaults to `inspecto`, the Platform product, so the D-PM6
    record stays valid.
  - `assemble.mjs --customer <record>` resolves the record into the Assembly Plan. The plan gains:
    - the modules, narrowed to those the customer keeps;
    - the templates, narrowed to those bought;
    - the bill of materials, without prices;
    - the entitlement to issue.
  - App Order Codes select the Product's optional Apps, with their templates and Add-ons.
  - `customer-build.mjs` still trims a Product bundle (D-PM8 unchanged), but every decision now comes from the plan.
    `package.ps1` is untouched (PA-8 is changing it).
  - The resolution itself is today's code, moved and not rewritten. That is why the pilot's jars and templates cannot
    drift.
- **D-PA34: every customer build carries a signed entitlement.**
  - Its `modules` are exactly the build's kept optional modules, including the ones kept as `required`. Its Add-ons
    are the bought ones plus those whose modules the build keeps whole. So the file and the jars agree by construction:
    a jar copied back in later stays inert.
  - The record may give `expires` and `nodes`; with neither, there is no expiry and one node.
  - The key is `--key`, defaulting to `product-map/private/keys/inspecto-issuer-1.pem`. A build with no key is
    refused, rather than shipped without a file.
- **D-PA35: the pilot Distribution is a customer build.** `pilot` means the customer's Collectors pre-wired, with no
  data.
  - The record's `pilot:` block names one bought Space Template and, for each of its Pipelines, the customer's poll
    folder.
  - The build adds the Space Template `<customer>-pilot`. It is that template with those `dirs.poll` replaced, and its
    golden corpus (`data/samples`) left out.
  - `lib/product.json` says `pilot`.
  - No Space is created at build time, so the bundle holds no data. The administrator creates the Space from that
    template on day one.
  - `assemble.mjs` refuses `pilot` without `--customer`. So `package.ps1 -Distribution pilot` is refused, and the
    refusal names customer-build.
- **D-PA36: Product documents are generated into the Product's hub.** Products, Apps and Industries get hubs at
  `docs/okf/product/{product,app,industry}/<id>/`.
  - For each Product, `render-product-docs.mjs` writes four files from the Product definition, its Apps, Add-ons and
    Editions, linking into their hubs:
    - `brochure.md`;
    - `features.md`, the datasheet;
    - `user-guide.md`;
    - `admin-guide.md`.
  - Each says it is generated, and `--check` holds it current.
  - Demo and video scripts are not generated. The demo script is `products/<id>.demo-build.txt`.
- **D-PA37: `PRODUCT-MAP-CUSTOMER-BUILD-GAPS-1` stays open.** Declaring a template's ops needs would keep `ops` and
  `case-management` for `telco-fraud`'s Case Rule. That changes the pilot's jars, and PA-10's proof is that the jars
  do not change. §9's "declared needs" moves to that row.

**Slices (one lane each, in this order):**
- **PA-10a: Product Map kinds.**
  - Build:
    - the items for the 3 Products, 4 Apps and 2 Industries;
    - the guard (`check-product-map.mjs`) and the seed for the new kinds;
    - their hubs, with each definition's `docs:` pointed at its own hub.
  - Proof: guard tests per kind, plus `render-product-docs --check` and `check-product-docs` green.
- **PA-10b: customer builds through the assembler.**
  - Build `assemble.mjs --customer`, with `customer-build.mjs` as its executor, and the entitlement issued into
    `conf/`.
  - Proof:
    - tests over synthetic records (a Platform customer, and a Domain customer buying an App);
    - the pilot customer built both ways from one Standard bundle, with the contents diffed;
    - the new bundle booted with its entitlement.
- **PA-10c: the pilot Distribution.**
  - Build the `pilot:` block, the `<customer>-pilot` template, and `pilot` in `DISTRIBUTIONS`.
  - Proof: tests, plus a pilot build of the pilot customer, booted, with a Space made from its template that polls the
    customer's folder and holds no data.
- **PA-10d: generated Product documents.**
  - Build the four generated files per Product.
  - Proof: `render-product-docs --check` and the docs guards green.

**PA-10a as built (2026-11-12).** The 9 items are in `product-map/{product,app,industry}/`; `check-product-map.mjs` rule 8
holds them; the hubs are generated. The review found the mind map (`render-product-map.mjs`) dropping the new parentless
kinds; they are now roots of their own, with their link in the CSV.

**PA-10b as built (2026-11-12).** Spec: [`okf/product/layer/platform/spec-product-map.md`](../okf/product/layer/platform/spec-product-map.md).
- `tools/customer.mjs` holds the resolution, moved unchanged apart from App Order Codes, plus `customerAssembly` and
  `customerPlan`. `assemble.mjs --customer` writes a customer plan; `customer-build.mjs` signs its entitlement and trims a
  Product bundle to it.
- The trim refuses a bundle of another Product or Edition, a jar the plan removes that is missing, and a Domain product
  whose composed Space joins Apps the customer did not buy.
- Decided here: a Platform product's optional App comes along when every Add-on it needs is bought (so the pilot keeps
  the Link Analysis App, as today); the entitlement's `addons` are the bought ones only, so an Add-on whose module ships
  as `required` (Anomaly Detection for `telco-fraud`) is not "held" by `holdsAddon`, while its module runs because it is
  listed in `modules`.

**Proof (as built).**
- `customer-build.test.mjs` 11/11: the old resolution cases; a synthetic Platform customer whose plan keeps exactly what
  `resolve()` keeps and whose file entitles exactly those modules; a Domain customer buying Apps, a missing required
  App and a foreign App refused; expiry and nodes reaching a file that verifies; the trim's effects and each refusal.
- **The pilot customer, both ways, from one bundle** (`package.ps1 -Product inspecto -Edition Standard -NoRuntime -NoUi`
  at `28e51e97b`, 47 files in `lib/`):
  - today's `customer-build.mjs demo-telco --bundle` (origin/master before this lane) and the assembled one each removed
    13 jars and 29 Space Templates;
  - a SHA-256 diff of the two trees: **227 files identical, nothing missing or changed; the assembled build adds only
    `conf/entitlement.toon` and `conf/entitlement.sig`** (`lib/product.json` is rewritten from the plan, byte for byte the
    same);
  - `entitle.mjs verify`: signed by `inspecto-issuer-1`, valid; 12 optional modules, Add-on `linkAnalysis`, App
    `link-analysis`, no expiry;
  - booted from its root (OIDC jars pointed at a dead JWKS, as the boot smoke does): `/health` 200, `/bootstrap` answers;
  - the same bundle with one byte of the entitlement changed refuses to boot ("the signature does not match").
- The pilot's record and outputs stay out of git (the record is in `product-map/private/`, the outputs in a scratch folder).

**PA-10d as built (2026-11-12).** `tools/product-documents.mjs`, called by `render-product-docs.mjs` before it draws the
hub tables, writes `brochure.md`, `features.md` (the datasheet), `user-guide.md` and `admin-guide.md` into each
`docs/okf/product/product/<id>/` hub.
- Composed from the Product definition (brand tagline and body, Editions, Distributions, the composed Space), its Apps
  (title, role, nav blurbs, version, Platform range, Add-ons, templates), and the Product Map titles of the Add-ons,
  Editions and Solution packs. Each line links into that item's hub.
- An App's guides are the user or admin guides in the hubs of its Space Templates, its Add-ons and the modules those
  Add-ons hold, so the Link Analysis App lists the `la-core` and `geo-link` guides. The Platform product's user guide
  also lists the console's own guides (`layer/product`). The admin guide starts with the entitlement, live App
  install, operations and launch-flag guides.
- Each file carries a generated marker; a file of one of those four slots in a Product hub that is not generated is
  refused (`refuseHandWritten`). `render-product-docs --check` holds them current in CI.
- A Product's own Space Templates (Inspecto's 30, Inspecto LA's 20 showcases) are listed in its datasheet, and their
  hubs' guides in a section of their own (found by the review: the LA datasheet first showed none).
- Not generated (D-PA36): demo and video scripts. An App with no guide in those hubs would say so; none does today.

**Proof (as built).** `product-documents.test.mjs` 4/4 (in the CI guards job): every Product's four files are generated,
marked and committed current; every link resolves; the Telco brochure and datasheet carry its Apps, roles and the
Edition × App table; a hand-written file in a generated slot is refused. `check-product-docs`, `check-doc-links`, the
vocabulary and doc-count guards pass.

## 12. Decisions (operator, signed 2026-11-10 and 2026-11-11 as recommended)

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
| D-PA13 | Where the Telco suite shell (App switcher, *My work*) lives | la-app becomes the suite host, built once per Product; RA/FM/CM areas lazy-load the console's pages |
| D-PA14 | How several Apps land in one Space with no new Java | the assembler composes one Space Template from the Apps' templates at build time; a file two parts ship is refused |
| D-PA15 | How Telco reaches one ingest | the `telecom` Industry owns canonical feeds and one generated golden corpus; Apps map them through views |
| D-PA16 | The order of the PA-5 lanes | compose (5a), suite shell (5b), one ingest (5c), demo (5d) |
| D-PA17 | How PA-5c reaches one ingest, given ~650 golden assertions over hand-made corpora | in the Composed Space Template only: the composer swaps the parts' usage feeds for the Industry's one; templates and their golden tests stay (signed 2026-11-10, refines D-PA15) |
| D-PA18 | Where a partner's App lives when built with the kit | any folder (`--app-root`); `apps/` stays first-party (signed 2026-11-11) |
| D-PA19 | How deep the App conformance kit goes in PA-6 | boot a built bundle per listed Edition, seed the App's template, run its Jobs, check its declared golden findings (signed 2026-11-11) |
| D-PA20 | The order of the PA-6 lanes | the App kit (6a), the sample partner App with its conformance in CI (6b), the Add-on picker (6c), the builder-kit docs (6d) (signed 2026-11-11) |
| D-PA21 | Where a picked Platform product lives and how it builds | in `product-map/private/products/<id>.toon` (gitignored, per customer). It narrows the Inspecto Platform product (`narrows: inspecto`): the same host, Apps and templates, only the picked Add-ons and their closure, and the Editions from the floor up. `assemble.mjs --product-file` and `package.ps1 -ProductFile` build it in Inspecto's place (signed 2026-11-11) |
| D-PA22 | Licence engine (supersedes D-MR3 "none") | an offline Ed25519-signed entitlement file (customer, Product, Edition, Apps, Add-ons, node count, expiry), checked at boot and on each gated route, no phone-home; node count recorded and shown, not enforced (signed 2026-11-11) |
| D-PA23 | A production bundle with no entitlement file | runs as today (every built Add-on on; Home tells the admin none is installed); only a trial build must carry one (signed 2026-11-11) |
| D-PA24 | When an entitlement or trial expires | 14 days of warnings, then read-only: ingest, Jobs, runs and config saves refused, reads, dashboards and exports kept; no data removed; a new file restores it without a restart (signed 2026-11-11) |
| D-PA25 | Upgrading an App whose templates made Spaces | the upgrade replaces the App's templates and nav; each existing Space is offered a dry-run diff applied through the normal config-change path (signed 2026-11-11) |
| D-PA26 | How the container target is built | from the `linux_amd64` bundle's own Dockerfile; image `inspecto-<product>:<version>-<edition>[-trial]` with OCI labels; no demo container (decided 2026-11-12, operator away) |
| D-PA27 | Helm chart per Product | one source (`deploy/helm/inspecto`) stamped per Product by `tools/ship.mjs chart`; single-replica until `SCALEOUT-HELM-NPOD-1` (decided 2026-11-12, operator away) |
| D-PA28 | The air-gapped bundle | one tar: the Linux bundle zip, the saved image, the chart `.tgz`, `install-offline.sh`, `SHA256SUMS`, `airgap.json`; DuckDB extensions required; no Helm/kubectl shipped (decided 2026-11-12, operator away) |
| D-PA29 | The entry point | `package.ps1 -Target container\|airgap`, through the assembler's plan; refused without a `linux_amd64` bundle (decided 2026-11-12, operator away) |
| D-PA30 | The proof bar on a desk with no cluster | Helm lint/template, a read-only image boot, an offline-host boot with `--network none`; the cluster install recorded as not run (decided 2026-11-12, operator away) |
| D-PA31 | The order of the PA-8 lanes | chart and targets (8a), image (8b), air-gapped bundle and guide (8c) (decided 2026-11-12, operator away) |
| D-PA32 | Product Map kinds for Products, Apps and Industries | `product`, `app` and `industry` items linked to their definitions; an App is billable (`INS-APP-<ID>`), a Product and an Industry are not; one Order Code per App, a price per App × Edition held for demand (decided 2026-11-12, operator away) |
| D-PA33 | How a customer build reaches the assembler | the record names its Product (default `inspecto`); `assemble.mjs --customer` narrows the plan's modules and templates with today's resolution, moved; `customer-build.mjs` trims a Product bundle from the plan; `package.ps1` unchanged (decided 2026-11-12, operator away) |
| D-PA34 | The entitlement of a customer build | always issued and signed; its modules are exactly the kept optional modules; expiry and nodes from the record; refused without a key (decided 2026-11-12, operator away) |
| D-PA35 | What the pilot Distribution is | a customer build whose record names one bought template and the customer's poll folders; the bundle adds `<customer>-pilot` (those folders wired, no golden corpus); no Space is made at build time (decided 2026-11-12, operator away) |
| D-PA36 | Generated Product documents | Product, App and Industry hubs; per Product a generated brochure, datasheet (`features`), user guide and admin guide; demo and video scripts not generated (decided 2026-11-12, operator away) |
| D-PA37 | Does PA-10 close `PRODUCT-MAP-CUSTOMER-BUILD-GAPS-1`? | no: declared ops needs change the pilot's jars; the row stays open with §9's declared needs (decided 2026-11-12, operator away) |

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
