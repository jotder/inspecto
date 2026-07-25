---
name: build-verify
description: >
  Canonical build / test / package / run recipes for inspecto (ucc-file-processor). Use whenever you
  need to compile, run tests, build the fat JAR, produce the per-edition deployment bundle, or build/
  serve the Angular UI — and to confirm a change actually works before reporting done. Encodes the
  offline Maven reactor verify loop, the mandatory DuckDB native-access JVM flag, package.ps1 edition
  flavors, and the UI dev/build commands. Trigger on any build, test, package, or "does it work" task,
  and on the team macro word GAUNTLET (= full verify, reactor tests + UI lint/test/build).
---

# Build / Test / Package / Run

Inspecto — Java 26 build (`release=24`), Maven reactor `file-processor-parent`. UI is Angular
(`inspecto-ui/`, **not** in the Maven reactor). Toolchain: JDK `C:\.jdks\openjdk-26.0.1`, Maven
`C:\maven\apache-maven-3.9.16\bin\mvn.cmd`. **Always build offline (`-o`).**

> Durable project knowledge (module map, gotchas, engine seams & perf, decisions):
> [docs/PROJECT_NOTES.md](../../docs/PROJECT_NOTES.md).

## Verify (authoritative — the source of truth)

```powershell
mvn -o clean test                # full reactor, offline. This is what "verified" means.
mvn -o clean package -q          # → inspecto/target/file-processor-*.jar  (skip tests)
```
**Every JVM launch needs `--enable-native-access=ALL-UNNAMED`** (DuckDB JNI) — including test runs.
Tests stand up a real `SourceService`/`ControlApi` on an ephemeral port and exercise the HTTP surface.

### Full verify — "GAUNTLET"

`mvn -o clean test`, plus — when UI files changed — in `inspecto-ui/`:
```powershell
npm run lint:tokens ; npm run test:ci ; npm run build   # npm ci only if the lockfile changed
```
Compare against the current baseline, report regressions verbatim before fixing anything.
**Never stage `inspecto/pom.xml`.** Prefer the `verify-runner` agent so build logs stay out of the
main context.

### ⚠ Narrowing to specific tests — commas, never `+`

```powershell
mvn -o -pl inspecto-engine -am test -Dtest=FooTest,BarTest -Dsurefire.failIfNoSpecifiedTests=false
```
This Surefire (3.2.5) needs a **comma-separated** class list. A `+`-joined list
(`-Dtest=FooTest+BarTest`) silently runs only *some* of them and still reports **BUILD SUCCESS** —
a false green that hides a real failure (observed 2026-07-25). `-Dsurefire.failIfNoSpecifiedTests=false`
is required with `-am`, since upstream modules contain none of the named classes and would otherwise
fail the reactor before it reaches the module under test. **A narrowed run is never "verified"** —
`mvn -o clean test` is.

## Deployment bundle (per edition)

```powershell
# Run under pwsh 7 (UTF-8) — package.ps1 is BOM-less UTF-8; Windows PowerShell 5.1 garbles it.
pwsh -File inspecto\package.ps1                 # full: JAR + UI + configs + scripts + jlinked Windows JVM
pwsh -File inspecto\package.ps1 -NoBuild        # reuse target/ JAR
pwsh -File inspecto\package.ps1 -NoUi           # skip Angular UI
pwsh -File inspecto\package.ps1 -NoRuntime      # skip embedded JVM (target must provide Java 24+)
```
Editions are build flavors (Personal HTTP/no-auth · Standard HTTPS/OIDC · Enterprise = Standard + ABAC
policy) — see [docs/EDITIONS.md](../../docs/EDITIONS.md). All three flavors exist today:

```powershell
pwsh -File inspecto\package.ps1 -Edition Standard    # + file-processor-security.jar (OIDC)
pwsh -File inspecto\package.ps1 -Edition Enterprise  # + security AND file-processor-policy.jar (ABAC)
```

Enterprise is a **superset** of Standard, matching `-Pedition-enterprise` = `edition-standard` + policy;
`serve.sh`/`serve.bat` auto-detect the edition from which jars are in the bundle. Default build (no
`-Edition`) is Personal-equivalent.

## Run

```powershell
# One-shot ETL pipeline
java --enable-native-access=ALL-UNNAMED -jar inspecto\target\file-processor-*.jar `
     inspecto\config\voucher\voucher_unknown_pipeline.toon

# Long-running control plane + UI (ControlApi, default :8080)
.\file-processor-deploy\serve.bat               # then http://localhost:8080/
```

## UI (Angular SPA — inspecto-ui/)

```powershell
cd inspecto-ui
npm ci
npm start        # dev serve on :4204
npm run build    # dist/ (bundled into the deploy zip's ./ui by package.ps1)
```

Note: editing the gamma Tailwind theming plugin/tokens does NOT hot-reload — restart the dev server.

## Reporting results

Report build/test outcomes faithfully: if tests fail, quote the failing output; if a step was skipped,
say so. "Verified" means `mvn -o clean test` passed — not that the code looks right.
