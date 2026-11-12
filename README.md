# Inspecto

> "Inspecto takes data from where it lives, makes it trustworthy, shows it, and turns what it finds into work
> that someone owns." — [Solution Overview](docs/okf/product/layer/product/brochure-solution-overview.md)

This is the engineering front door. It points; the linked documents are the source of truth. The product itself
is described in the [Executive Brief](docs/okf/product/layer/product/brochure-executive-brief.md) and the
[User Guide](docs/USER_GUIDE.md); every concept's name is fixed by the [Glossary](docs/GLOSSARY.md) (binding).
The customer bundle's first page is [`inspecto/README.md`](inspecto/README.md), not this file: it is written for the
operator who installs and runs a bundle, and carries no developer material.

## Repo layout

| Folder | What it holds |
|---|---|
| [`inspecto/`](inspecto/) | the processor application, its examples and the packaging scripts (`package.ps1`) |
| [`inspecto-ui/`](inspecto-ui/) | the Angular web app (not in the Maven reactor) |
| [`platform/`](platform/) | core platform modules (engine, ETL, config, access, SQL, …) |
| [`spi/`](spi/) | service-provider interfaces |
| [`features/`](features/) | feature modules (agent, Case Management, reconciliation, …) |
| [`providers/`](providers/) | pluggable providers (connectors, parsers, OIDC, secrets, …) |
| [`la/`](la/) | Link Analysis modules |
| [`offerings/`](offerings/) | one offering definition per edition |
| [`product-map/`](product-map/) | the Product Map; each item has a docs hub under `docs/okf/product/` |
| [`spaces/`](spaces/) | Space configurations and templates, with sample data |
| [`docs/`](docs/) | all current documentation — start at [`docs/INDEX.md`](docs/INDEX.md) |
| [`compliance/`](compliance/) | the compliance tree (controls matrix, evidence, licenses) |
| [`tools/`](tools/) | repo tooling: guards, generators, `dev-doctor`, `affected-modules` |
| [`deploy/`](deploy/) · [`dev-infra/`](dev-infra/) | Helm chart · local development infrastructure (Docker Compose) |
| [`scripts/`](scripts/) | one-off helper scripts |
| [`.claude/`](.claude/) · [`.githooks/`](.githooks/) · [`.github/`](.github/) | team Claude Code setup · git hooks · CI workflows |

The root [`pom.xml`](pom.xml) is the Maven reactor parent.

## Build

Prerequisites: JDK 27 (on the team box `C:\.jdks\jdk-27-win`), Maven, Node.js. Once per clone:

```
git config core.hooksPath .githooks   # pre-commit guards and pre-push checks
node tools/dev-doctor.mjs             # names environment traps before they look like code failures
```

Build every module without tests, then the web app:

```
mvn -o -B -DskipTests clean install -Pedition-advanced
cd inspecto-ui && npm ci && npm run build
```

A fresh clone cannot run a Pipeline until `node tools/seed-samples.mjs` has created the working directories.

Tests, the affected-build protocol (`node tools/affected-modules.mjs`), the DuckDB launch flag and packaging:
[`spec-build-test.md`](docs/okf/product/layer/platform/spec-build-test.md). Everyday commands:
[`.claude/QUICK_START.md`](.claude/QUICK_START.md).

## Editions and branches

Editions are build flavors over one codebase, never branches — see [`docs/EDITIONS.md`](docs/EDITIONS.md).
Work lands on `master` — see [`docs/BRANCHING.md`](docs/BRANCHING.md) and the team rules in
[`CLAUDE.md`](CLAUDE.md).

## Where the docs live

- [`docs/INDEX.md`](docs/INDEX.md) — the map of every current document.
- [`docs/okf/product/`](docs/okf/product/index.md) — one hub per Product Map Item (plan, spec, guides, api, tests).
- [`docs/BACKLOG.md`](docs/BACKLOG.md) — the one board of open work.
- [`docs/GLOSSARY.md`](docs/GLOSSARY.md) — the canonical vocabulary.

## Developer docs

- Build, test and packaging: [`spec-build-test.md`](docs/okf/product/layer/platform/spec-build-test.md); what a bundle
  contains, logging and deployment: [operations reference](docs/okf/product/layer/platform/admin-guide-operations-reference.md).
- Architecture: [platform layers](docs/okf/product/layer/platform/architecture.md) and the
  [Stage-1 ingest design](docs/okf/product/module/engine/architecture-stage1.md); configuration:
  [configuration reference](docs/okf/product/module/config/admin-guide-configuration-reference.md).
- The Assistant and its skill catalog: [`spec-assist-agent.md`](docs/okf/product/module/agent/spec-assist-agent.md).
- The HTTP API: [`docs/api/`](docs/api/README.md); the web app's dev server: [`inspecto-ui/README.md`](inspecto-ui/README.md).
