---
type: Concept
title: Build & Test — Packaging — package.ps1
description: Part 2 of 3 of Build & Test: Packaging — package.ps1
tags: [build, test, maven, duckdb, packaging]
timestamp: 2026-06-28T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-20 last-edit
---

# Build & Test — Packaging — package.ps1

Part 2 of 3 of [Build & Test](spec-build-test.md) · previous: [The verify loop, affected modules and CI](spec-build-test-verify-loop.md) · next: [Packaging — bundle contents; open rows](spec-build-test-bundle-contents.md).

## Packaging — `package.ps1`

`inspecto/package.ps1` emits the deployment bundle. Switches: `-NoBuild` (reuse `target/`), `-NoUi` (skip the
Angular build), `-NoRuntime` (skip the embedded jlinked JVM), and **`-Edition Basic|Standard|Advanced`** (⚠ this omitted `Advanced` — the only non-Basic flavour CI actually releases — until 2026-09-09) (selects
the Maven [edition](architecture-editions.md) profile + assembles the per-edition fat-JAR). Generated
launch scripts embed the native-access flag and the key [`-D` flags](admin-guide-launch-flags.md).

**`-Product inspecto|la` (`PRODUCT-ASSEMBLY-1` PA-2, 2026-11-10; was `-Ui gamma|la-app` from D-5 step 7, 2026-10-02; default `inspecto`) picks WHICH single-page application `ui/` holds** - the Product's `host`,
not an edition ([`spec-editions.md`](spec-editions.md)): any `-Edition` ships either. `gamma` is the Inspecto console and the bundle
exactly as it always was; `la-app` is the Link Analysis application (`inspecto-ui/projects/la-app`). The script builds the chosen one
(`npm run build -- <name>`) and copies `inspecto-ui/dist/<name>/` **by name**, never "the first `index.html` under `dist/`" (two
applications mean two `index.html` files; "first" is whichever sorts first). A bundle's `ui/` never holds both, and `ControlApi` still
has one `-Dui.dir`. An unknown value stops at parameter binding with PowerShell's `ValidateSet` message. ⚠ The IAM client id the
chosen application signs in with (`AUTH_OIDC_CLIENT_ID`, `inspecto-ui` in the launchers) is a DEPLOYMENT decision: register a client
for `la-app` in the customer's IAM and set it there; the packaging does not invent one.

### UI bundle guard: `check-bundle-shape` plus `angular.json` budgets (2026-10-02)

Angular budgets cap SIZE; they cannot see which module landed in which chunk. In D-5 step 3 a library barrel that re-exported
widget components pulled MapLibre (1.2 MB) and the Link Analysis widgets into `main`, and only a manual `stats.json` comparison
caught it. Two layers now pin the shape (both apply to `gamma` and `la-app`):

- **`angular.json` budgets** (`initial` and `bundle` named `main`, warning then error). Initial 2.58 / 2.65 MB (gamma) and 2.68 / 2.75 MB
  (la-app); `main` 1.94 / 2.0 MB (gamma) and 2.04 / 2.1 MB (la-app) (Angular's `mb` is decimal). The Angular CLI counts only `main` + `polyfills` + `styles`
  as initial (gamma 2.50 MB measured).
- **`inspecto-ui/tools/check-bundle-shape.mjs`**, thresholds in `inspecto-ui/tools/bundle-budget.json`. It reads the esbuild metafile
  `dist/<app>/stats.json` (module names per chunk, not a grep of minified output), so a build must run with `--stats-json` first:
  `npm run build -- --stats-json`, `npm run build -- la-app --stats-json`, then `npm run check:bundle-shape` (CI does exactly this in
  `ui.yml`). It fails when: `main` or the eager set (main plus every chunk `main` imports statically, plus polyfills and styles)
  exceeds its ceiling; `maplibre-gl`, or (la-app) `@antv/g6`, or any Link Analysis / Geo library module other than the `la-host` seam,
  `public-api.ts` and the `*.viz.ts` registrations appears in `main` or an eagerly-imported chunk; the `link-analysis.routes.ts` or
  `geo-map.routes.ts` entry is no longer its own lazy chunk; the eager-file or lazy-chunk count drifts past the stated tolerance.
  The FAIL line names the module, the chunk and the sizes. Baseline 2026-10-02: gamma `main` 1,877,959 B, eager 4,888,709 B in 29 files,
  114 lazy JS chunks; la-app `main` 1,980,572 B, eager 3,037,577 B in 8 files, 15 lazy JS chunks.
- **Re-baseline deliberately**: a change that legitimately grows or reshapes a bundle edits `bundle-budget.json` (and the
  `angular.json` budget if it trips) in the same change, ceilings about +3% over the new measured size, new numbers in the commit message.
  The JSON's `_doc` field carries the procedure. Never raise a ceiling to silence an unexplained jump; find the module first.
- Cannot catch: growth inside a chunk that stays under its ceiling, a heavy module moving between two lazy chunks, transfer-size
  (gzip) regressions, runtime cost of a module that is legitimately eager, and anything a build without `--stats-json` skipped
  (the guard fails loudly if the file is missing).

### UI theme-token guard: `check-theme-tokens` (2026-10-22)

`src/@gamma/styles/themes.scss` rebuilds each Material theme as `(color: …) + colours` to merge the gamma base colours, which
drops the `_mat-system` map Material 22's M2 token functions read. Every system-derived colour token therefore comes out null:
it is omitted, or emitted as an empty `color-mix(in srgb, , transparent)`. So `--mat-button-filled-container-color` was never declared and every `mat-flat-button color="primary|accent|warn"`
rendered transparent in both apps and both schemes, with every build green. A first fix (`3fa647719`) restored only the
filled-button fill; `UI-THEME-NULL-TOKENS-1` (2026-10-28) merges the base colours INTO the generated theme, so `_mat-system`
survives and every component colour token (disabled fills, state layers, checkboxes, tabs, form fields) resolves from the
palettes: the global stylesheet declares 747 distinct `--mat-*` tokens, up from 441. **`inspecto-ui/tools/check-theme-tokens.mjs`** reads both apps' built
`dist/<app>/browser/styles-*.css` after the two builds (`npm run check:theme-tokens`, CI `ui.yml` after the bundle-shape guard).
It fails when any `.theme-*` × light/dark × primary/accent/warn filled button lacks a concrete container or label colour.
`tools/check-theme-tokens.test.mjs` falsifies it.

The SBOM step (`tools/sbom.mjs`) runs `mvn package dependency:list -DskipTests` over the edition's modules with
`-am`, so reactor siblings (and their `tests` test-jars) resolve from `target/` outputs: **no prior `mvn install`
into `~/.m2` is needed** (a bare `dependency:list -am` died "Could not find artifact ...inspecto-audit-spi" on a
machine whose `~/.m2` lacked newly extracted modules). `MVN_CMD` / `MVN_OFFLINE=1` still apply; the step re-runs
the package phase (about 2 min), it does not reuse a `-NoBuild` jar untouched.

Since P3f the same run also writes one SBOM per shipped jar, `sbom/<jar-basename>.sbom.cdx.json` (root = the jar with its
SHA-256 and build id; components = that module's own third-party closure from its `dependency:list` block).
`tools/sbom-modules.mjs --verify <bundle>` (run by package.ps1 after `modules.list` is written, and by
`check-sbom-modules.mjs --bundle <dir>`) fails on a listed jar without an SBOM, a hash that is not the staged jar's, or a
component the combined SBOM does not carry.

Per-jar signing (P3h, opt-in): `package.ps1 -SignJars -JarKeystore <p12> -JarAlias <a> [-TsaUrl <url>]` signs every first-party
`inspecto*.jar` with `jarsigner` BEFORE the SBOM step (so the per-module hashes cover the signed bytes), then
`tools/check-jar-signatures.mjs <bundle>` asserts every one verifies and all share one signer certificate. The store password comes
only from `$env:INSPECTO_JARSIGN_STOREPASS`. Without the switch the bundle is unchanged. `*.p12|*.pfx|*.jks|*.keystore` are git-ignored.
`release.yml` enables it only when the repository secrets `JARSIGN_KEYSTORE_B64`, `INSPECTO_JARSIGN_STOREPASS` and `JARSIGN_ALIAS` exist (optional variable `JARSIGN_TSA_URL`); otherwise its command lines are unchanged. `tools/check-release-signing.mjs` guards that wiring.

### What the bundle contains — and what it deliberately does not

Verified by building both flavors 2026-08-27 (Basic 169.3 MB, Advanced 170.3 MB, exit 0):

| Jar | Basic | Standard / Advanced |
|---|---|---|
| `inspecto.jar` (shaded core) | ✅ | ✅ |
| `inspecto-oidc.jar` (OIDC `Authenticator` SPI) | — | ✅ Standard+ |
| `inspecto-policy.jar` (ABAC `AccessDecider` SPI) | — | ✅ Advanced only |
| `inspecto-connectors.jar` (shaded sidecar — `CONNECTORS-BUNDLE-1`) | ✅ | ✅ |
| the **seven** EDG-01 sidecars (`notify-channels` shaded, `backup`, `geo-link`, `exchange`, `metrics`, `events`, `ops`) | — | ✅ Standard+ |
| `postgresql.jar` (inert until `-Dinspecto.db=postgres` — `PG-1`) | — | ✅ Standard+ when Maven's local repository holds it; **required** only by a bundle that carries `inspecto-la-store-pg` (Advanced, Preview, not `-Distribution demo`) — see *Packaging prerequisites* below |
| **`inspecto-agent` / `inspecto-intelligence`** | **never** | **never** |

> **Packaging prerequisites (`PACKAGE-DEMOAUTH-ADVANCED-1`, 2026-10-23).** (1) **Maven's local repository is resolved, never assumed**:
> `tools/maven-repo.mjs` = `$M2_REPO`, else `<localRepository>` in `~/.m2/settings.xml`, else `~/.m2/repository` (the same function
> `tools/sbom.mjs` uses); `package.ps1` used to read `%USERPROFILE%\.m2` and failed on a box whose repository is `C:/.m2/repository`.
> (2) **The PostgreSQL driver is needed only by a bundle that carries a Postgres module** — `needsPostgresDriver()` in
> `tools/bundle-modules.mjs` (`inspecto-la-store-pg`: Advanced and Preview; the `-Distribution demo` build is loopback + DuckDB, so no). Basic,
> Standard and the demo bundle do not fail at the `postgresql.jar` step without it: the driver rides along when the repository holds
> it (`modules.list` then names it, `tools/offering-classpath.mjs` drops it from the list when it was not staged) and is skipped with
> a note when it does not; Advanced/Preview fail there with a message naming the repository path and the fix. ⚠ Not the whole
> story: `inspecto-connectors` (every edition) shades the driver at compile scope, so Maven needs it to build at all and
> `tools/sbom.mjs` hashes it from the repository for every edition; a repository without it fails the SBOM step (`no hash`) after
> the skip, which is correct and cannot arise on a box that has built the connectors sidecar. (3) **The jlink module set follows the
> bundle**: `node tools/jlink-modules.mjs --edition Advanced --demo --staged-dir …` expects `inspecto-demo-auth.jar` where the
> OIDC, secrets and geo-country jars were (`DEMO_JAR` / `DEMO_REPLACES` in `bundle-modules.mjs`). (4) `-Distribution demo -NoBuild` fails
> at the top, naming `mvn package -pl :inspecto-demo-auth -am -DskipTests`, when that jar was never built. Tests:
> `tools/maven-repo.test.mjs`, `tools/offering-classpath.test.mjs`, `tools/jlink-modules.test.mjs`.

> ⚠ **Staged-jar totals** (added 2026-09-09 — this table listed 3 of the 12): Basic stages **2**
> jars, Standard **11**, Advanced **12**. The complete enumerations in code are `package.ps1`'s
> staging block and its boot-smoke classpath. Owner: [`okf/product/layer/platform/plan-editions.md`](plan-editions.md) §3.3.

**Bundles ship NO Spaces — the `spaces/` tree holds only `spaces/_templates`** (operator decision
2026-09-25, every edition — **except the demo-auth la-app flavor**, see the 2026-10-12 decision below). `_templates` is the Space-template gallery, an underscore sentinel never booted
as a Space. Spaces are **attached** at deploy time: drop each Space folder (a dir with a `config/` subtree)
into the bundle's `spaces/` — or point `SPACES_ROOT` (launchers) / `-Dspaces.root` at a folder holding them —
and restart, or create one in Settings → Spaces (`POST /spaces`, no restart). Step 4 is
`Copy-SpaceTemplates` in `inspecto/package-spaces.ps1`, dot-sourced by `package.ps1`: it lists
`git ls-tree HEAD -- spaces`, keeps only `spaces/_templates/**`, and writes those blobs with
`git checkout-index` from a throwaway index, so .gitattributes eol rules apply as in a fresh checkout.
Every other top-level entry (the committed `default`/`demo`/`ucc`, untracked or git-excluded dirs, `uat`,
`_shared`) is named in the build log as not bundled.
- ⚖ **Decision 2026-10-12 (operator): the Link Analysis demo bundle ships `la-showcase` PRE-SEEDED.** `package.ps1 -Edition Advanced
  -Product la -Distribution demo` (and only that flavor) runs step 6f, `Invoke-DemoSpaceBake` (`inspecto/package-demo-bake.ps1`): it scaffolds
  `la-showcase` into a temp spaces root (`node tools/seed-la-showcase.mjs --scaffold`), boots the staged bundle on a loopback port
  with the `serve-demo` flags, runs the seed script `--apply`, stops the server gracefully (Ctrl+C, so DuckDB checkpoints; a leftover
  `.wal` fails the build), scans the baked folder for the build-time path (any hit fails the build), and copies it to
  `spaces\la-showcase`, so a presenter unzips, runs `serve-demo` and signs in with no node and no seed step. Any failure throws; `-NoBakeDemo`
  skips it for a fast local package. `tools/check-bundle-spaces.mjs` allows exactly that one Space, only for a demo build whose
  Assembly Plan names it as `seedSpace` (`tools/bundle-spaces-rule.mjs`, `--bundle <dir> --seed-space <id>`; `PRODUCT-ASSEMBLY-1`
  PA-2 made the Space the Product's `demoSpace` and the seed script `tools/seed-<space>.mjs`), and still fails every other bundle
  shape. Every other bundle still ships none.
- 🔴 **Why no Spaces.** A hand-over built as "the bundle plus ONE Space folder" opened the sample `default`
  Space instead of the one dropped in beside it (2026-09-25 rehearsal); `0228534d0` pruned the samples for
  `-DemoAuth` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `-Distribution demo`)* only, and this decision made it the rule for every edition.
- 🔴 **Why committed content** (`BUNDLE-UNTRACKED-SPACES-1`). Step 4 used to copy the working-tree
  `Get-ChildItem spaces/` listing, and a demo bundle built in the shared sandbox checkout shipped a
  git-excluded client working set and a peer's untracked pilot Space.
- ⚖ **A locally modified tracked template file ships as COMMITTED**, and a file only `git add`ed does not
  ship — reproducible from the commit. Modified files are named in a warning, never mixed in silently.
- **Git is required** — there is no directory-listing fallback (that fallback *is* the leak); no git or not a
  work tree throws.
- **Zero Spaces is a clean state.** `ControlApi.main` no longer exits when `-Dspaces.root` holds no Space (it
  logs a WARN); `/health`, `/ready` (`pipelines: 0`) and `/spaces` answer, and a Space-scoped route answers
  **503** `CAPABILITY_UNAVAILABLE` "No Space is attached …" (`SpaceManager.NoSpaceHostedException`, mapped in
  `ControlApi.errorBoundary`) instead of a 500 + stack trace per request. The SPA already renders an empty
  state for it in Settings → Spaces. `run.(sh|bat)` and `serve.(sh|bat)` print a "no Space attached" line.
- Pinned by `tools/check-bundle-spaces.mjs` (CI `test` job): a throwaway repo holding every trap (including a
  COMMITTED Space), staged for real under pwsh, exact templates-only file set asserted; and by
  `ControlApiSpacesTest.zeroSpacesIsACleanStateNotA500`.

⚠ **`/assist/*` is inert in every bundle `package.ps1` produces, and that is the intended default.**
The core fat JAR carries the two SPI *interfaces* (`com.gamma.assist.spi.AssistAgent`,
`com.gamma.intelligence.spi.IntelligenceAgent`) but no implementor and no `META-INF/services` entry, so
`ServiceLoader` finds nothing and the assist routes answer **503** — the documented absent-module
behaviour (`ADVANCED_GUIDE` §5.7; the same optional-module pattern `EDITIONS.md` uses as its reference
example). A bundle without the agent is a **valid deployment, not a broken one**.

**Why it cannot arrive by accident.** The core build step is `mvn clean package -pl inspecto -am`, and
`-am` builds *upstream* dependencies only. `inspecto-agent` and `inspecto-intelligence` depend **on**
`inspecto`, i.e. downstream, so that command never reaches them. This is deliberate: the core JAR
"stays dependency-lean" (`inspecto/pom.xml`, `AssistAgent`'s class javadoc) and the agent modules pull
the vendored kernel + eoiagent model transport.

⚠ **They are opt-in: the `ai` profile (`AI-OPTIN-1`, operator 2026-11-24).** `inspecto-agent`,
`inspecto-agent-hosted` and `inspecto-intelligence` — the only modules that need `com.eoiagent` (built from source,
jotder/inspect-agent) — sit in the root POM's `ai` profile, not in the default `<modules>` and not in any edition
profile. A plain `mvn test` or `-Pedition-advanced` run builds **no AI** and needs no eoiagent; `-Pai` adds them
(`-Pedition-advanced,ai`). Not `activeByDefault`: that activation switches off whenever another `-P` is given. Since
Standard and up *ship* the assistant (and Advanced/Preview the Intelligence agent), every caller that builds a
shipped edition passes `ai` itself: `package.ps1` (`-P<edition>,ai` for every non-Basic edition — without it
`-pl :inspecto-agent` fails loudly), `tools/sbom.mjs` (`mavenProfiles()` in `tools/bundle-modules.mjs`),
`tools/check-dependencies.mjs` (the lock covers the eoiagent tree), and `ci.yml` / `release.yml`, which install
eoiagent first. `tools/check-offerings.mjs` accepts a shipped module that the `ai` profile builds. See
[editions model](architecture-editions.md).

⚠ **THREE reactor sizes, three baselines** (2026-09-08, EDG-01 complete): the default (Basic) build is
**23 modules / 3777 tests**; `-Pedition-standard` is **31 modules / 4106**; `-Pedition-advanced` is
**32 modules / 4126** (`inspecto-ops` contributes 228, of which 11 skip — the environment-gated
`PostgresStateStoreTest`). ⚠ Run `-Pedition-standard` too, not just the other two: it is the only profile
that proves an optional module is **self-contained**. `inspecto-ops` ships in Standard while
`inspecto-policy` does not, so a dependency between them is legal in exactly one direction — and the wrong
direction still PASSED locally off a stale `~/.m2`. A run that stops at a failing module reports a PARTIAL sum and SKIPS the trailing modules —
do not read that as the total, and do not conclude a module "failed" when the build never reached it.

### 🔴 Proving a cut or a shared-interface change: `compile-clean`, never an incremental `compile`

Filed 2026-10-01 (D-1 of the Link Analysis separation). Two things looked like proof and were not:

- **An incremental compile after a shared-interface change is a false green.** After `ApiContext` lost three methods,
  `mvn compile -pl inspecto -am` finished in 9 s with exit 0 — the dependants (`-geo-link`, `-ops`, …) were never
  recompiled, so their stale classes still matched the OLD interface. Only `clean` makes the dependants get seen.
- **A closure script is a prediction.** The first one said a contract was 20 classes; it was 46, and the compiler found four
  fully-qualified inline references (`com.gamma.spi.OptionalSpi.first(…)`) that no import scan can see.

```
node tools/java-closure.mjs --seeds ApiContext,Subject --stop CollectorService,SpaceManager   # PREDICT what a cut drags along
node tools/compile-clean.mjs                                                                # PROVE it: clean test-compile, whole reactor
```

`compile-clean` always runs `mvn -o clean test-compile -DskipTests -B -fae -Pedition-advanced` (pass `-P…` to change the profile),
reads the log rather than the exit code, and exits 0 only on BUILD SUCCESS with every reactor module SUCCESS, none SKIPPED, no
`[ERROR] …java` line (de-duplicated — Maven prints each twice) and no "Nothing to compile". It prints errors grouped by module and
file. JDK 27 on `JAVA_HOME`; about 2 min for 38 modules. `java-closure` strips comments with a tokenizer (a `/*` inside a string
literal cannot open a fake comment) and resolves inline fully-qualified names; it still cannot see reflection, `ServiceLoader`
providers or a class named only inside a string. Both are fixture-tested (`tools/*.test.mjs`, each blind spot planted and the
test confirmed RED against the old behaviour) and run in CI.

### 🔴 A halted reactor is a SILENT PASS — check it mechanically, not by reading

`REACTOR-HALT-IS-A-SILENT-PASS-1`, filed 2026-09-16 after it nearly landed a false verdict twice in one
day. The paragraph above ("a run that stops at a failing module reports a PARTIAL sum") has been in this
doc since 2026-09-08 and **did not prevent either occurrence** — which is why there is now a guard:

```
mvn -o clean test -Pedition-advanced -B > build.log 2>&1     # -B, NEVER -q
node tools/check-reactor-verdict.mjs build.log --expect-modules 42   # 41 until inspecto-la-storage was added (D-3 step 2, 2026-10-02); 39 until D-1 step 5b added inspecto-la-core and inspecto-la-api; 38 until D-1 step 5a added inspecto-entity-store; 33 until D-1 (2026-10-01) added inspecto-audit-spi, -la-graph, -auth-spi, -http-spi; 37 until SEP-08 added inspecto-entity-list
```

Reproduced deliberately on 2026-09-16 with the real toolchain (Maven 3.9.16 / surefire 3.2.5). Three
distinct mechanisms, all of which read as "no failures":

1. **`mvn -q` on a green build writes a ZERO-BYTE log and exits 0** — indistinguishable from a build
   that never started (this repo has already recorded a no-op build doing exactly that).
2. **`-q` suppresses the Reactor Summary**, which is the *only* place Maven names the `SKIPPED`
   modules. Without it, a halted reactor and a full green run produce the same evidence.
3. 🔴 **The load-bearing one — `mvn clean` only cleans modules the reactor REACHES.** When an upstream
   module fails, every downstream module keeps `target/surefire-reports/*.txt` from the *previous*
   run: green reports, plausible counts, no marker of any kind. Measured with an upstream **compile**
   error (which writes no report of its own, so the failure leaves no trace in the reports either),
   the `build-verify` skill's own authoritative recipe — *"don't parse the log at all, SUM THE SUREFIRE
   REPORTS"* — reported `failures=0 errors=0` for a tree on which the downstream module ran not one
   test. ⛔ **The recipe that exists to defeat log-parsing traps is the delivery mechanism for this
   one**, because summing reports silently mixes two different runs.

⇒ **A `SKIPPED` module is UNVERIFIED, never passing, and a surefire report older than the build is a
leftover, never evidence.** The guard cross-checks all three sources — exit code, Reactor Summary, and
report mtimes against the build window it computes from Maven's own `Finished at` / `Total time`
footer — and refuses a pass unless every module is `SUCCESS` and every report was written by *that*
build. ⚠ `-fae` does not help: `--fail-at-end` still skips modules that *depend* on a failed one.

**CI runs this guard on every push/PR** (`REACTOR-VERDICT-CI-1`, operator 2026-09-25 — CI, ⛔ not
pre-push). `ci.yml`'s `test` job is the ONLY workflow reactor that runs tests (`release.yml` builds with
`-DskipTests`; `launchers-windows` runs no Maven), so it is the one wired: the `reactor` step tees its
`--batch-mode` run to `$RUNNER_TEMP/reactor.log`, and a following `if: always()` step runs the guard on
it with `--root .`. ⚠ **`shell: bash` on that step is load-bearing**: an unspecified shell runs as
`bash -e` WITHOUT pipefail, so `mvn … | tee` would hand back tee's exit 0 and a red reactor would go
green; an explicit `shell: bash` is `bash -eo pipefail` (restated with `set -o pipefail`). The guard
step is skipped only when the reactor step never started (no log exists). ⛔ **No `--expect-modules` in
CI** — the count moves with every edition module and a hard-coded number would drift; the guard's own
Reactor-Build-Order cross-check already fails on an under-parse. The residual it leaves is the one its
header states: a reactor that LOST a module (a dropped `-Pedition-*`) is invisible to it. The job pins
Node 22 for this step because the guard uses `fs.globSync`.

🔴 **Check for a live build BEFORE every `mvn`, not just when you remember.** ⚠ Recorded twice on
2026-09-08 because writing it down once did not prevent the second occurrence: a targeted
`mvn -pl providers/asn-parser/asn-decoders/asn-core test` was fired while a full-reactor coverage run was in
flight, so both wrote `asn-core/target/` at once and that module's numbers had to be treated as
suspect. A `-pl` run feels harmless precisely because it is small — but it shares `target/` with
whatever the reactor is doing to the same module. `ps -W | grep -iE 'java\.exe|mvn'` costs nothing;
run it as a reflex, not as a recovery step.

🔴 **Never edit the tree while a verification is running, and do not trust a process check to tell you it
finished.** A verify pass runs several builds in sequence, so an empty `ps -W | grep java` between them
looks identical to "done". Editing into that gap produces two distinct false failures: a *transient*
mid-refactor compile error reported as a real defect, and then a Windows **file-lock** on a `target/*.jar`
held by the competing JVM (`maven-clean-plugin ... Failed to delete ...asn-core-0.1.0-SNAPSHOT.jar`), which
aborts the reactor in under two seconds before compiling anything. Only the completion notification means
finished. Cost: one wasted Advanced verification during EDG-01 cell 7.

🔴 **An optional module's tests can be absent from BOTH numbers while everything looks green.** An
edition module is not in the default `<modules>`, so `mvn -o clean test` never compiles it: a broken one
leaves the everyday build fully green. And if the Advanced reactor dies in an earlier module, the later
one is merely SKIPPED, which a summary counting only failures reports as nothing wrong. During cell 6 two
verifications passed (4004 and 4122 tests) while `inspecto-events` had never once compiled. **Ask whether
the module CONTRIBUTED TESTS — by name, from its own `Results:` block — not whether the build passed.**
⚠ And check the name carefully: `inspecto-event` (the core event store, position 16, 26 tests) and
`inspecto-events` (the optional Event Viewer, position 31, 3 tests) differ by one letter, and a verify
agent misattributed one for the other on this very build.

⚠ **Every bundled launcher uses `-cp`, never `java -jar`** (RUNSH-CP-1, 2026-09-07). `-jar` ignores
`-cp` and `CLASSPATH` outright, and `inspecto.jar`'s manifest carries no `Class-Path`, so a `-jar`
launcher can reach **no sidecar at all**. `serve.sh`/`serve.bat` were already on a classpath while
`run.sh`/`run.bat` — the one-shot ETL path, whose main class `CollectorProcessor` is the very caller that
resolves collector connectors — were still on `-jar`. The connectors fix therefore worked for a served
deployment and silently did nothing for a one-shot run. 🔴 The divergence is the lesson: two launchers
with two different classpath rules meant fixing one looked like fixing both. All four now build the
classpath the same way, each sidecar inert unless a config asks for it. ✅ **Since P3d (2026-10-08) "the same way" is
one file:** package.ps1 step 3a-ter runs `tools/offering-classpath.mjs`, which resolves the Offering, refuses a staged
jar missing from the list (or a listed jar not staged) and writes `modules.list` + `edition.properties` into the bundle;
`run.*`, `serve.*`, `serve-demo.*` and the boot smoke all READ `modules.list` (`CP=$(tr ...)` in sh; a `call set` loop
in cmd — never delayed expansion, SERVEBAT-OPTS-1). Fallback for a hand-assembled directory: no list ⇒ `inspecto.jar`
then every `*.jar`; no `edition.properties` ⇒ the old oidc/policy-presence heuristic. The order is `serve.*`'s historic
one; `run.*` (which never carried `inspecto-policy`), the smoke and the demo launcher each used a different hand-kept
order before and now share it. `tools/check-launchers.mjs` runs `serve.sh`/`serve.bat` over the generator's list per edition
and compares the `-cp` IN ORDER. ✅ **BUNDLE-LAYOUT-1 (2026-11-06):** the launchers live in `bin/` and `cd` to the bundle root (the parent of `bin/`) before Java starts; every jar and `modules.list` live in `lib/`, and the list names each jar as a root-relative `lib/<jar>` line (`listEntries` in `tools/offering-classpath.mjs` — the ONE seam between layout and classpath; jar identity, `bundleFile` and the SBOM names stay bare). The fallback globs `lib/*.jar`; `check-launchers.mjs` starts each launcher from the bundle's PARENT by a relative path, proving the cd. The `ura.sh`/`ura.bat` pre-ETL launchers and their `core.list` classpath were retired 2026-11-06; the Dockerfile, `inspecto.service` and `install-service.sh` all launch `serve.sh`, which carries the full list.

⚠ **`inspecto-oidc` ships SHADED too, for the same reason** (SEC-SIDECAR-BOOT-1, 2026-09-07). It is
profile-gated (`-Pedition-standard` / `-Pedition-advanced`), but until that date `package.ps1` staged its
plain 16 KB jar — which carries **no `com/nimbusds` classes**, while `OidcAuthenticator` has nine direct
Nimbus imports and `ControlApi` resolves the `Authenticator` SPI *during startup* through an unguarded
`ServiceLoader`. Every Standard and Advanced bundle failed to boot. It now builds an
`inspecto-oidc-*-sidecar.jar` (core and slf4j `provided`, so the shade carries Nimbus and nothing else),
`package.ps1` stages that one, and verifies the STAGED artifact for `com/nimbusds` plus all three SPI
registrations. `inspecto-policy` needs none of this — it has no third-party dependencies, so its thin jar
is genuinely complete.

🔴 **`inspecto-connectors` was in that list until 2026-09-07, and that was a defect, not a design.** It is
still a plain default module, but it is now **bundled in every edition** as `inspecto-connectors.jar`
(CONNECTORS-BUNDLE-1). Before that it was built and unit-tested by CI and shipped by nothing, so SFTP,
FTP/FTPS, S3, GCS, Azure Blob, Kafka and `SmtpEmailChannel` were unreachable in every deployment — for 85
days, while `EDITIONS.md` marked SFTP shipped in all three editions. Two details make it work:

* **It ships SHADED** (Maven classifier `sidecar`), because a thin jar is worse than useless — sshj,
  commons-net, kafka-clients and javax.mail would be missing, and `NotificationService.discoverChannels`
  finding `SmtpEmailChannel` without javax.mail kills boot with `NoClassDefFoundError: javax/mail/Message`.
* **Its dependency on the core is `provided`**, the same idiom `tools/templates/processor/pom.xml` uses for
  third-party plugin modules. Compile scope would drag the whole ~97 MB core into the sidecar. The shaded
  jar is ~32 MB, dominated by BouncyCastle (via sshj) and kafka-clients, and contains **zero** core classes.

⚠ `-am` walks **upstream only**, and `inspecto-connectors` depends *on* the core — so it is unreachable
from `-pl inspecto -am` and has to be named: the packaging build is now
`mvn clean package -pl inspecto,:inspecto-connectors -am`. Miss that and the sidecar is silently absent.
`package.ps1` therefore **verifies the staged jar** (8 factories registered, sshj present, javax.mail
present) rather than trusting the copy — the connector tests all live *inside* the module, where the
classpath is trivially correct, so they can never go red for a packaging gap. That is exactly how this
survived undetected.

**To run with the assist agent**, build the module and put its jar (plus its dependencies) on the
launch classpath yourself — there is no `package.ps1` switch for it:

```bash
mvn -o clean package -pl :inspecto-agent -am -DskipTests
```

✅ **The runtime-floor conflict is GONE as of 2026-09-17.** It was: the agent modules need a **JDK
25+ runtime** (their model-transport jars are class-file v69) while the `-NoRuntime` flavor documented
a **Java 24+** target server, so a `-NoRuntime` deployment on Java 24 could not load them. Moving the
compile target to **`release=27`** puts the product's own floor *above* the agent modules' — one floor,
27, for everything. A `-NoRuntime` target server must now provide **Java 27+**, and at that version the
agent modules load by construction. ⚠ The historical reasoning is kept because **PKG-5** in
[BACKLOG](../../../../BACKLOG.md) §6 is written against the old two-floor framing.

⚠ The **jlink embedded-runtime step is unproven as of 2026-08-27**: a stale `java.exe` held
`runtime/bin/server/jvm.dll` and step 6c failed with an access error that was a **file lock, not a
build fault**. Both editions pass with `-NoRuntime`. Re-prove jlink on a box with no stale JVMs.
