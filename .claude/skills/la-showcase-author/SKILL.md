---
name: la-showcase-author
description: >
  Build or change a Link Analysis showcase Space Template (spaces/_templates/la-*): its synthetic data
  generator, ground truth, link Job, saved views, Advanced tier (personas, four-eyes, Drafts, counts Job +
  Alert Rule), seed script, golden tests, packaging and the Preview/Basic demo installs. Trigger on "new LA
  showcase / demo Space", "regenerate la-<id> data", "add an Alert Rule to a showcase", "seed la-<id>",
  "package a showcase", "redeploy the LA preview", or any edit under spaces/_templates/la-* or
  tools/gen-la-*, tools/seed-la-*, tools/package-la-showcase.mjs.
---

# Link Analysis showcase Spaces — how they are built

Canonical facts: `docs/okf/product/solution-pack/la-showcase/architecture-showcase-catalogue.md` (pattern,
data generation, gotchas) and each Space's own hub `docs/okf/product/solution-pack/la-<id>/`. Read the
catalogue before changing a pattern; update it (and the Space hub) in the same change. Demo installs and
redeploys: `.claude/memory/preview-la-demo.md`.

## What one showcase is (the files that move together)

| Piece | Path |
|---|---|
| Template | `spaces/_templates/la-<id>/template.toon` (name, tagline, description, `minEdition`, `contents[]`) + `config/**` + `data/` |
| Generator | `tools/gen-la-<id>-data.py` — stdlib only, fixed seed, byte-identical re-run, 12 landed months in `data/inbox` + 1 staged |
| Ground truth | `tools/gen-la-<id>-data.ground-truth.json` (story → entity ids and look-alike ids), never shipped |
| Data test | `tools/gen-la-<id>-data.test.mjs` (planted facts in the files) |
| Java tests | `<X>LinksJobGoldenTest` (real Pipelines + Jobs) and `La<X>TemplateTest`; `ControlApiSpaceTemplatesTest` counts templates |
| Seed | `tools/seed-la-<id>.mjs` + `.test.mjs` (`--scaffold`, dry run by default, `--apply`, idempotent) |
| Docs | hub `brochure.md`, `user-guide.md`, `admin-guide-install.md`; catalogue `user-guide-showcase-catalogue.md` |

## Rules learned the hard way

- **Generators write through `tools/la_gen_io.py`** (`Outputs().open/write_text/prune`): unchanged files are left
  alone, changed ones go temp-then-move, pruning runs last. Never delete committed CSVs first — on Windows a
  scanner/watcher holds the file and a second run left the data gone (`LA-GENERATOR-DELETE-FIRST-1`).
- **Realism first, then plant.** Heavy-tailed background population, daily/weekly cycles, 3–8 % blank optional
  fields; each story planted by rewiring a few records, and **every story has a benign look-alike** the golden test
  proves stays unlinked. Synthetic identity only (invented names, reserved phone range, real cities at city level) —
  see `.claude/memory/no-data-or-client-files-in-commits.md`. Keep the corpus small; say so in the hub.
- **Golden test limits:** every story is one connected piece of the link Dataset, no look-alike touches a story
  entity, every saved view stays under 4 000 rows and 1 000 nodes.
- **Link Jobs also carry `cron: "*/5 * * * *"`** — a `pipeline.commit` during a running Job is skipped, not queued
  (`JOB-SIGNAL-OVERLAP-SKIP-1`). A Job judging a month waits until every side of that month has landed.
- **Advanced tier, inert on Basic:** personas `<prefix>.analyst` (lead), `.manager` (second approver), `.reviewer`
  (read-only), `.outsider` (not a member) + shared `admin`; `four_eyes_fan_out_above: 2000` and the shared Drafts
  limits in `link-analysis.toon`.
- **One roster across Spaces:** `admin` and the `fraud-analyst` / `la-viewer` / `la-settings` roles must be
  byte-identical to the other showcases, or one server refuses the second Space with 409 `ROSTER_CONFLICT`. Copy
  them from an existing wave-C template; never redefine.
- **The Alert Rule is a registry rule over a one-row counts Job** (`sql.template` on the link Job's
  `job.dataset.produced`, then `alert.evaluate`). Not grouped (`by:` is checked against a Schema a Job output does
  not have at `POST /spaces`). It names a running-total count, never an entity, so one Alert stays open and is
  refreshed in place. Narrow the rule until the look-alike is out, and pin it in the golden test.
- **A Draft may only annotate an entity in the Working Set** (two hops out → 422); seeds annotate the story's seed.
- **Names:** Space name = the use case alone ("Crypto laundering"), never "Link Analysis … showcase"; the seed
  script's `display_name` must match `template.toon`.

## Packaging and installs

- `node tools/package-la-showcase.mjs <id>` reads **committed content only** (`git show HEAD:`) and emits a Space zip
  (`space.toon` + `config/**`, no `data/`) and a data zip (`data/**`); commit before packaging.
- The Preview (:8080) and Basic (:8098) demo installs share real-data Spaces via `spaces\<id>.ref` — **one install
  at a time**. Start/stop servers in the Terminal panel (`run_in_terminal`) so Ctrl+C is real; a detached stop is
  abrupt and leaves DuckDB `.wal` files.
- Seeds need an Advanced or Preview build with Demo Auth; trigger the data-build Jobs before seeding, or the seed
  gets `409 no data yet`.

## Done means

Generator re-run is byte-identical · `node --test tools/gen-la-<id>-data.test.mjs tools/seed-la-<id>.test.mjs` passes ·
the Space's golden + template Java tests pass (`node tools/affected-modules.mjs` for the command) · hub and catalogue
updated · open items tagged on `docs/BACKLOG.md` · commit scope `la-<id>` (or `la-showcase` for cross-Space work).
