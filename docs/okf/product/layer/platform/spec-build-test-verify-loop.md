---
type: Concept
title: Build & Test — The verify loop, affected modules and CI
description: Part 1 of 3 of Build & Test: Verify loop (offline, authoritative); Affected-module protocol — build/test only what a change reaches (AFFECTED-BUILD-1, 2026-10-19); When GitHub CI runs (operator, 2026-10-09); Nightly Postgres-gated tests (nightly-postgres.yml, ASSURE-OPERABILITY-1 D2, 2026-10-20); Mandatory DuckDB native-access flag; Seeding a fresh checkout — tools/seed-samples.mjs
tags: [build, test, maven, duckdb, packaging]
timestamp: 2026-06-28T00:00:00Z
---

# Build & Test — The verify loop, affected modules and CI

Part 1 of 3 of [Build & Test](spec-build-test.md) · next: [Packaging — package.ps1](spec-build-test-packaging.md).

## Verify loop (offline, authoritative)

```
mvn -o clean test          # full reactor; "verified" = this passes
mvn -o clean package -q    # → inspecto/target/inspecto-processor-*.jar (processor + third-party; the 15 core libraries are thin jars in platform/*/target and spi/*/target)
```

Always offline (`-o`). Tests spin up a real `CollectorService`/[`ControlApi`](../../module/processor/api-control-api.md) on
an ephemeral port. (Java **27** toolchain + Maven, `release=27` — moved 2026-09-17. The local JDK is
`C:\.jdks\jdk-27-win` (Oracle 27+35-2325) and CI sets up **Oracle** JDK 27, not Temurin:
Adoptium had published no 27 build as of 2026-09-17. ⚠ A GraalVM cache (`.graalvm-cache`, 25.0.3 Windows /
25.1.3 Linux) is only the jmods source for the cross-built runtime and is NOT the toolchain; it is optional
and absent on some machines (`package.ps1` then embeds no Linux runtime, or use `-NoRuntime`).
See the `build-verify` skill for exact local paths.)

**Build without tests** (installs every module's jar, e.g. after a fresh checkout; ~3 min):
`mvn -o -B -DskipTests clean install -Pedition-advanced`. In a lane worktree add a private repo with the shared
one as fallback: `-Dmaven.repo.local=C:/sandbox/.m2-<lane> -Dmaven.repo.local.tail=C:/.m2/repository`.

## Affected-module protocol — build/test only what a change reaches (`AFFECTED-BUILD-1`, 2026-10-19)

The Advanced reactor is 57 modules (2026-11-01) and a full `install` is the slowest thing the repo does. **Default: verify the
affected set; run the full reactor only when it is unavoidable.** One tool decides, locally and in CI:

```bash
node tools/affected-modules.mjs                  # your working tree vs merge-base with origin/master
node tools/affected-modules.mjs <base> <head>    # a commit range;  --json for machines;  --full to force
```

It prints a **mode** and the exact commands:

| Mode | When | What runs |
|---|---|---|
| `none` | only docs, product map, offerings, `.claude/`, node tools, Space content no test reads | the node guards (`ci.yml` `guards` job, or locally) |
| `affected` | module sources changed | `mvn install -DskipTests -Pedition-advanced -pl <set> -am && mvn test -Pedition-advanced -pl <set>` — upstream compiled + installed without tests (test-jars still built), then the set tested; ⛔ chained: if the install fails the tests must not run (they would resolve stale sibling jars from ~/.m2) |
| `full` | root/aggregator/module `pom.xml` or `.mvn/` changed beyond comments · an unclassified path (fail-safe) · the set is >70% of the reactor · `--full` | `mvn install -Pedition-advanced` (+ `-Pcoverage` in CI) |

**The test set** = changed modules **+ every module that depends on them, transitively** (read from the poms,
test-jar dependencies included) **+ modules whose tests READ a changed file**. The last group is discovered, not
listed: string literals in `src/test/**` that name a tracked repo path (e.g. `docs/api/openapi-v1.json`,
`docs/GLOSSARY.md`, `spaces/…`). Such a reader runs its own tests only — its dependents do not read the file.

Rules it encodes (each one a past false green or wasted hour):
- ⛔ **Never `-pl X -am` alone to decide a change is safe** — `-am` builds UPSTREAM only; dependents stay untested.
- **Comment/whitespace-only edits to `.java` or `pom.xml` are not build changes** (a doc-path rewrite touched 40
  Java files' comments and would otherwise have forced 34 modules).
- **A real pom change always plans FULL**: it may move the resolved dependency graph, and the lockfile, SBOM and
  lean-core guards resolve the whole reactor.
- **Untracked files count only as new source inside a module** (`*/src/**`); `target/`, scratch and runtime state do not.
- `inspecto-ui/` changes flag the UI lane (`ui.yml` already runs only on `inspecto-ui/**`); `package.ps1`,
  `deploy/`, `scripts/` flag a packaging smoke instead of tests.

**CI** (`ci.yml` `test` job): a pull request runs the AFFECTED plan against its base; the nightly schedule, a `v*`
tag and a manual run (default `scope: full`) run FULL; a manual run with `scope: affected` (+ optional `base`)
runs the plan on demand. Repo-wide coverage floors, the lean-core guard and the dependency-lock guard run on full
runs only (they need every module). The reactor-verdict guard judges whichever log was produced.
**Locally**: per change, run the tool and its commands (narrow further with `-Dtest=A,B` while iterating); the
full gate (GAUNTLET) is for the operator asking, a pom change, or a release.
Measured on the last 60 Java-touching commits (2026-10-19): 7 needed FULL; the rest tested a median of ~30 of 54
modules, and leaf-module fixes tested 1–9.

## When GitHub CI runs (operator, 2026-10-09)
- **A branch push runs only `branch-policy.yml`** (~10 s: commit-subject lint, retired-line refusal). The no-build
  guards were taken off push too (operator, same day): run them locally before pushing.
- **All of `ci.yml`** (the `guards` job, the Maven reactor + Postgres `test` job, `launchers-windows`) and **`ui.yml`**
  (the SPA lint/test/build) run **nightly** (01:30 / 01:45 UTC), **on demand** (GitHub -> Actions -> CI or UI ->
  *Run workflow*, or `gh workflow run CI --ref master` / `gh workflow run UI --ref master`), on a pull request, and
  (`ci.yml`) on a `v*` release tag.
- **Why:** parallel lanes pushed several times an hour and each push paid ~15-20 min of reactor. Commits now stay LOCAL
  until a batch of features passes its local tests (unit tests per change; the full local gate before the batch push),
  and the nightly run is the backstop. ⚠ A broken push is therefore found by the next nightly or an ad-hoc run, not at
  push time: run the full local gate before every push, and trigger CI by hand after a push that touched shared seams.

## Nightly Postgres-gated tests (`nightly-postgres.yml`, ASSURE-OPERABILITY-1 D2, 2026-10-20)
- **What:** 16 Postgres-gated classes run against a `postgres:18` service container, with `INSPECTO_TEST_PG_URL` set
  (`-Pedition-advanced`, modules `inspecto-processor`, `inspecto-ops`, `inspecto-case-management`, `inspecto-engine`,
  `inspecto-la-store-pg` plus `-am`). They assume-skip without a server, so a green local run says nothing about them;
  this is what covers the pgjdbc bump every night. `ci.yml` already runs most of them once per full run on a
  `postgres:16` service via the `-Dinspecto.test.pg.url` property, but the `inspecto-la-store-pg` classes read only the
  env var and so skipped there.
- **Skip safeguard:** the last step reads every `TEST-<class>.xml` under `target/surefire-reports` and FAILS when a
  named class has no report or any skipped test (the one allowed skip is the TLS test in `PostgresPublishPgTest`,
  which needs a locally pulled TLS Postgres image). Surefire counts a skip as green; this step is what makes it red.
- **Trigger:** nightly 02:47 UTC, or `gh workflow run "Nightly postgres tests" --ref master`. Reports upload as
  `nightly-postgres-reports` on failure. Not run on GitHub yet: the first scheduled or dispatched run is the proof.
- Not in the list: `PostgresPublishJobTest` (DuckDB stand-in) and `ConnectionSourceTest` (no env var read).

## Mandatory DuckDB native-access flag

Every JVM launch (engine, tests, serve scripts) **must** pass:

```
--enable-native-access=ALL-UNNAMED
```

It's wired into the root `pom.xml` Surefire config as `<argLine>@{argLine} --enable-native-access=ALL-UNNAMED</argLine>`
(the `@{argLine}` prefix lets JaCoCo prepend its agent). Omitting it fails DuckDB's native init.

## Seeding a fresh checkout — `tools/seed-samples.mjs`

```
node tools/seed-samples.mjs [<space> | --all] [--dry-run]
```

🔴 **A fresh clone cannot run a single Pipeline until this runs.** `.gitignore` ignores everything
under `spaces/<space>/data/` and force-tracks `data/samples/**` only, so the samples are present and
every **inbox**, every **`data/ref/`** and all eight per-Pipeline working directories are absent.
`PipelineConfig.prepare()` creates only the status dir, so the rest must exist on disk before a run.
The visible symptom of skipping it is the two reference-join examples (`join_step`,
`orders_enriched_rollup`) failing **422** with a leaked DuckDB internal — *“No files found that match
the pattern”* — on both the test run and the dry-run, which reads as an engine defect and is not one.

⚠ **It derives the work from each Pipeline's own `dirs.poll`**, never a hand-kept list: it reads every
`<space>/config/**` file ending `_pipeline.toon`, creates each `dirs.*` leaf, copies
`data/samples/<pipeline>/` (recursively) into that Pipeline's inbox, and copies `data/samples/ref/*`
into `data/ref/`. 🔴 **Every relative `dirs.*` value resolves under the Pipeline's Space directory** —
the exact rule the engine applies (`PathJail.resolveDataPath`: the parent of the nearest `config`
ancestor; absolute paths and URIs as written; a value repeating the Space's own path is refused and
reported). Until 2026-09-24 the seeder resolved against the repo root, so a fresh checkout got every
inbox in `<repo>/data/inbox/` and the engine then **refused every Pipeline** (*“nothing exists there,
while <cwd>/data/inbox/X does”*); the shared tree hid it with stale inboxes under `spaces/*/data`.
`tools/check-seed-paths.mjs` (CI + pre-push) now fails if any planned path lands outside its Space.
A Pipeline with **no** same-named sample directory is **reported**, not skipped in
silence — that line is how `lookup_step`, which shipped no sample at all, stayed visible
(`DEMO-CORPUS-FORMAT-COVERAGE-1`). *(It has shipped one since `WB-17`, 2026-09-22; the row CLOSED
2026-09-23 once ASN.1, Excel, fixed-width and XML each gained a domain-shaped demo in `spaces/demo`
— `msc_cdr`, `gl_journal`, `in_recharges`, `stock_movements` — pinned by `DemoCorpusIngestTest`.)*

It is **idempotent and not a sync**: it creates and overwrites, it never deletes, so re-running after
the engine has consumed an inbox re-seeds it and a file you dropped in by hand survives.

Wired into the four space-serving launchers in `.claude/launch.json` and step 2 of the `smoke` skill,
so the ordinary paths seed themselves.

⚠ **The per-space `data/samples/seed-inbox.sh` / `.ps1` still exist and still work**, but each carries a
HAND-WRITTEN pipeline name list in two files, so a Pipeline added to a space is silently unseeded
until someone edits both. Prefer the derived tool; reach for the shell scripts only to seed one space
without Node. *(Shipped 2026-09-22 as `WB-16`; row `REFERENCE-EXAMPLES-NEED-UNRUN-SEED-1`, whose
stated cause — “nothing copies it” — was refuted while building this: the shell scripts do copy the
reference, and nothing ran them.)*
