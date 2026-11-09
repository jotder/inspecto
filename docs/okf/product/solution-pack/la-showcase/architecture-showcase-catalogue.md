---
type: Architecture
title: "Link Analysis showcase catalogue — architecture"
description: "How the 18 Link Analysis showcase Spaces are built and shipped: the three downloads and how the server finds them, the packer and industry composition, the edition rule (a Basic tier plus an inert Advanced tier), the shared Advanced-tier pattern, the data-generation rules, and the deliberate decisions."
tags: [la-showcase, architecture, showcase, catalogue, distribution]
timestamp: 2026-11-09T00:00:00Z
---

# Link Analysis showcase catalogue — architecture

Inspecto ships **18 Link Analysis showcase Spaces in 9 industries** (the list, what each teaches and where to start:
[catalogue user guide](user-guide-showcase-catalogue.md)). This page holds the rules every one of them is built and
shipped by. Each Space's own figures, personas and Alert Rule are in its hub (`solution-pack/la-<id>/`, mostly
`admin-guide-install.md`); how a release is built and boot-tested is in the
[release guide](admin-guide-showcase-release.md). Built 2026-11-07 to 2026-11-09 (plan archived:
`docs/archived-documents/plans-archive/2026-11-07-la-showcase-police-insurance-utility-plan.md`, with the realism
review of every story and the per-Space build records).

## Distribution and install layout

A showcase ships as **three downloads**, unzipped side by side into one folder the user chooses (`<parent>`), so the
product can be upgraded by replacing its folder without touching any Space or data:

| Download | File | Contents | Unzips to |
|---|---|---|---|
| Product | `inspecto-deploy-<platform>-<flavour>.zip` | the server, the UI, the embedded Java runtime and the template gallery (`spaces/_templates`). No Space is attached, but the gallery carries each template's committed data (about 45 MB in all) | `<parent>/inspecto-la/` |
| Space | `inspecto-la-space-<id>-<version>.zip` | `<id>/space.toon` and `<id>/config/` (with an empty `egress.toon`): configuration only | `<parent>/inspecto-la-spaces/` |
| Data | `inspecto-la-data-<id>-<version>.zip` | `<id>/data/<feed>/inbox/*` (landed at start) and `<id>/data/staged/*` (copied in during the demo) | `<parent>/inspecto-la-data/` |

```
<parent>/
  inspecto-la/             product (replace to upgrade)
  inspecto-la-spaces/<id>/ space.toon + config/
  inspecto-la-data/<id>/   data/, then the Space's audit/ and duckdb/
```

### How the server finds them

- **Spaces:** `SPACES_ROOT`, read by `serve.bat` / `serve.sh` / `serve-demo.*` and passed as `-Dspaces.root`.
- **Data:** `SPACES_DATA_ROOT`, passed by the same launchers as `-Dspaces.data.root`; each Space's `data/`, `audit/`
  and `duckdb/` then live under `<data root>/<id>` (`SpaceDataRoot`; the
  [configuration reference](../../module/config/admin-guide-configuration-reference.md) has the rules). Both may be
  relative: the JVM resolves them against its working directory and every launcher changes to the bundle root first,
  so the documented `..\inspecto-la-spaces` and `..\inspecto-la-data` need no launcher logic. Guarded by a
  `tools/check-launchers.mjs` scenario.
- **Why the split is exact.** With a data root set, a Space folder that still holds `data/`, `audit/` or `duckdb/` is
  not booted, so the Space zip carries no `data/`; an unstamped data root holding only those folders is adopted on the
  first boot. The data folder becomes the Space's runtime state as soon as the server starts. Deleting it and
  unzipping the data download again resets the showcase; the configuration is untouched.
- **Version check.** `space.toon` names `min_product_version`; `SpaceVersionCheck` logs a WARN at boot for a Space
  that asks for a newer product, and the Space still boots. A `dev` run checks nothing.
- **Known limits (accepted 2026-11-07):** renaming a Space in Settings rewrites `space.toon` with its four known
  fields and drops `min_product_version`; an absolute `SPACES_DATA_ROOT` with a space in it splits in `serve.bat` (as
  `SPACES_ROOT` already does; the documented relative paths avoid it); `4.1.0-rc1` counts as meeting `4.1.0`.
- **`la-sample` is the exception:** its files are in `data/samples` for the in-app *Try it with sample data*
  (`POST /spaces {template, loadSamples: true}`, which works from a data root), so its data download lands nothing
  until the files are copied into `data/inbox`. Every other showcase keeps its files in `data/<feed>/inbox`.

### The packer

`node tools/package-la-showcase.mjs <id> [--version <v>] [--min-product <v>] [--out <dir>]` writes the Space and data
zips (`tools/package-la-showcase.test.mjs`).

- **Committed content only**, the rule of `inspecto/package-spaces.ps1`: files are read with `git show HEAD:<path>`,
  so an uncommitted edit never ships. Commit first.
- **The Space zip mirrors `SpaceManager.createFromTemplate`:** `config/**` with `${SPACE}` replaced by the id, an empty
  `config/egress.toon` (without it the server would allow the Space's current outbound hosts unreviewed), and
  `space.toon` written from `template.toon` (`name`, `tagline`, `min_product_version`). `template.toon` and `portal/`
  stay in the gallery. A template with no committed `data/` is refused.
- **Version:** the reactor version in `pom.xml` (operator 2026-11-09: the downloads carry the product version as is,
  built from the same commit as the product); `min_product_version` defaults to it without its qualifier.
- **Industry composition.** `<id>` may also name an industry recipe, `spaces/_industries/<id>.toon`
  (`useCases[n]: …`), which the packer composes from the use-case templates it names. Every file comes from exactly
  one use case, and a path two use cases ship with different bytes is refused (a real collision is renamed in its
  template on purpose). Joined instead: `config/link-analysis.toon` (Entity Types merged by id, every other top-level
  setting such as `four_eyes_fan_out_above` or `drafts:` by key), the Advanced tier's roster (`config/demo-users.toon`
  by user id, `config/roles.toon` by role name) and `config/examples/*.md`. A differing re-declaration of any merged
  key, user or role is refused; the same bytes ship once. A test composes every committed recipe from the real
  templates. The industry Spaces themselves are an active plan (`docs/superpower/2026-11-08-la-industry-spaces-plan.md`).

## Edition rule: a Basic tier and an inert Advanced tier

Every showcase has a **Basic tier**: Collectors, Pipelines, Datasets, the link Dataset Job, saved views and saved Geo
Views, nothing that needs a sign-in. It loads and is explorable on a Basic build; that is the acceptance test. The
**Advanced tier** (Demo Users, roles, four-eyes approval, Drafts, Alert Rules, the seed script that creates the
Investigation, Entity List and Case) ships **in the same Space download** and stays inert on Basic. Every showcase's
`template.toon` says `minEdition: basic` (`la-sample`, which has no Advanced tier, states none), and every showcase is
in `offerings/basic.toon`
`contentPacks` (Preview carries them through `includes`; `tools/check-offerings.test.mjs` pins the placement).

What "inert" means, as seen live on Basic:

| Part | On Basic |
|---|---|
| `config/demo-users.toon`, `config/roles.toon` | ignored (no sign-in; only the demo-auth module reads the roster). The gallery no longer refuses a `minEdition: basic` template for its roster (operator 2026-11-08, "A: allow on Basic") |
| Alert Rules (`config/registry/alert-rules/`, or deferred `config/pending/alert-rules/`) | loaded, **not armed**: a WARN "Not arming alert rule '<rule>'" at boot; the `alert.evaluate` Job runs and logs "no rule breached"; 0 Alerts. The gallery and `POST /spaces` do not refuse them for a `minEdition: basic` template (operator 2026-11-08, "A: allow, unarmed"; each rule's content is still judged). Rules: [Spaces user guide](../../layer/product/user-guide-spaces.md), edition fit |
| `masking_mode` in `link-analysis.toon` | **left unset** in every showcase: the edition default (`EditionMaskingDefault`) is `none` where nobody signs in and `typed` with sign-in. A stated `typed` masked every id on Basic, where nobody can reveal it (found on `la-claims`) |
| `la.index.build`, `la.detect` Jobs | run and succeed (built in on every edition) |
| a Job whose type's module is not installed (for example `risk.score`) | listed `hosted: false`, never fires |
| the seed scripts | not run (they need Demo Auth) |

## The Advanced tier pattern (the thirteen new Spaces)

`la-police`, `la-utility` and the eleven wave C Spaces share one pattern (the older `la-showcase`, `la-mobile-money`,
`la-claims` and `la-audit` have their own Advanced tiers, described in their runbooks):

- **Personas.** A lead analyst, a second approver (manager), a read-only reviewer and an outsider who is not a member,
  named `<prefix>.analyst` and so on (`rp.*` in `la-utility`, `police.sio` as the approver in `la-police`), plus the
  shared `admin`.
- **One roster across Spaces.** `admin` and the `fraud-analyst`, `la-viewer` and `la-settings` roles are byte-identical
  in these thirteen: a user id defined differently in two Spaces of one server is refused with 409
  `ROSTER_CONFLICT` ([Demo Auth](../../module/demo-auth/admin-guide.md)). Proved 2026-11-09 with all twelve 2026-11-09
  Spaces in one server. The older templates differ in their roles (`la-showcase`'s `fraud-analyst` adds
  `canShareInvestigation`; `la-audit` and `la-mobile-money` define no `la-viewer`; `la-audit`'s roster uses
  `audit.admin`).
- **Four-eyes and Drafts.** Every `link-analysis.toon` states `four_eyes_fan_out_above: 2000` and the same Drafts limits;
  no Entity Type is masked.
- **The Alert Rule is a registry rule over a one-row counts Job** (one exception: `la-utility`'s
  `utility_transformer_loss` is `max(loss_pct) gte 15` over the multi-row monthly `transformer_loss` output, so its
  Alert carries the highest monthly loss, not a count). A `sql.template` Job on the link Job's
  `job.dataset.produced` writes one row of counts; an `alert.evaluate` Job runs after it; the rule compares one count
  with a threshold. It is **not grouped** (`by:`): `POST /spaces` checks a `by` column against the Dataset's Schema, and
  a Job output has none when the Space is created, while the deferred `pending/alert-rules/` waits only for score-model
  outputs. One Alert per entity would need such a deferral for Job outputs; not built. The rule **names a count, never
  an entity** (the saved view shows who), the count is a **running total**, so the rule stays met after every build and
  there is one open Alert, not one per build, and since `ALERT-VALUE-REFRESH-1` (2026-11-09) the open Alert is
  **refreshed in place** to the current value, keeping its first-fire time. Each rule is pinned by its golden test
  against its story's benign look-alike.
- **Four rules are deliberately narrower than the first wording,** each to keep its look-alike out: `la-trade` (only
  trades between companies that share a director), `la-logistics` (only uninsured carriers), `la-benefits` (days
  counted from the death notice, not the death) and `la-telco-ra` (two or more unbilled months, the latest still
  unbilled). `la-police` counts repeat sellers only, because a resold stolen item alone is ordinary; a per-build "new
  matches" count is a later refinement if a demo needs it.
- **The seed script** `tools/seed-la-<id>.mjs` (`--scaffold`, a dry run by default, `--apply`, idempotent; story ids
  read from the generator's ground truth): an Investigation over the link Dataset with a bounded expand, annotation,
  window or threshold, resolve and snapshot; its members (not the outsider); an Entity List; a Case assigned to the
  manager and linked; an Investigation template; one unbounded expand held for four-eyes; one open Draft. A Draft may
  only annotate an entity in the Working Set (an entity two hops out is refused, 422), so each Draft annotates the
  story's seed.

## Data generation

- **One generator per Space**, `tools/gen-la-<id>-data.py`: standard library only, a fixed seed, written straight into
  the template's `data/` (twelve landed months plus one staged month); a re-run is byte-identical. Writes go through
  `tools/la_gen_io.py`: an unchanged file is left alone, a changed one is written to a temp name and moved into place,
  and only files a run no longer writes are removed, last (`LA-GENERATOR-DELETE-FIRST-1`).
- **Ground truth** `tools/gen-la-<id>-data.ground-truth.json` (story → entity ids and look-alike ids), never shipped.
- **Golden tests:** `tools/gen-la-<id>-data.test.mjs` (the planted facts in the files) and a Java golden test that runs
  the real Pipelines and Jobs (every story one connected piece of the link Dataset, no look-alike linked to a story
  entity, every saved view under 4 000 rows and 1 000 nodes, the Alert Rule's own measure and threshold).
- **Realism first:** a background population with heavy-tailed degrees, daily and weekly cycles and 3–8 % blank
  optional fields, then each story planted by rewiring a few records; every story has a benign look-alike.
  Synthetic identity only (invented names, a reserved phone range, invented streets, real cities at city level).
  Several Spaces are smaller than first specified to keep the committed corpus small (for example `la-utility`: 1 000
  premises, not 8 000); each hub says so.
- **Link Jobs also run on `cron: "*/5 * * * *"`**, because a `pipeline.commit` that lands while a run is in flight is
  skipped, not queued (`JOB-SIGNAL-OVERLAP-SKIP-1`). Exception: `la-ownership`'s `ownership_links` has no cron yet
  (BACKLOG `LA-OWNERSHIP-NO-CRON-FALLBACK-1`). A counts or link Job that judges a month waits until that month's
  every side has landed (`la-utility`'s `transformer_loss` joins both reads; `la-telco-ra` waits for the billing file).

## Deliberate decisions and gotchas

- The version lives in `space.toon`, not a separate manifest; the launchers pass the paths through unchanged.
- Two views of the older showcases are cut at the 4 000-row default projection limit on every edition,
  `mm_money_trail` (`la-mobile-money`) and `telecom_fraud_network` (`la-showcase`); left as found.
- A product zip attaches no Space, but its template gallery still carries every template's data.
- On a Preview log, the `alert.evaluate` Job's "no rule breached" line also appears while an Alert stays open (the rule
  is edge-triggered), so it does not mean "no breach" there (`ALERT-EVALUATE-NO-BREACH-WORDING-1`).
