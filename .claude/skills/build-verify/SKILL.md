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

### ⚠ …and `mvn -o clean test` alone is a false green for the edition modules

`inspecto-security` and `inspecto-policy` enter the reactor **only** under `-Pedition-standard` /
`-Pedition-enterprise` (root `pom.xml`, the profile-gated `<modules>`). So a plain `mvn -o clean test`
does not compile or run them at all and still reports **BUILD SUCCESS** — the module-level analogue of
the `-Dtest` trap above. **If a change touches auth, roles/capabilities, OIDC, or ABAC policy, the
default-profile green means nothing:**

```powershell
mvn -o clean test -Pedition-enterprise   # superset: pulls in BOTH security and policy
```

Observed 2026-07-25: `OidcAuthenticatorTest.adminRoleGrantsOnboardConnectionsAndNotWorkbench` had been
**failing on `master` since 7e90f53d (2026-07-24)** — that commit added `canTriageRequirements` to
`admin`'s seed without updating the test's *equality* assertion, and nobody saw it because every
default-profile run skipped the module. Two lessons: run the enterprise profile for any
`Roles.SEED` change, and remember the reactor is **fail-fast** — a failure in `inspecto-security`
leaves `inspecto-policy` **SKIPPED**, i.e. unverified, not passing.

### ⚠ …and on the UI, `npm run build` is a false green for anything a SPEC references

The production build does **not** compile `*.spec.ts`, so a change to a shared model type can leave
`npm run build` exit 0 while `npm run test:ci` is red on a stale spec that still constructs the old shape.

Observed 2026-07-26 (§2 D9, renaming `ExchangeOffer.dataset` → `datasets`): `build` passed while `test:ci`
failed to bundle at all — three TS2561 errors in `catalog/sharing.component.spec.ts`, a file the change never
touched. Because the bundle failed, **0 tests ran and no vitest summary printed**, so a pass-count glance
would also have missed it.

**For any rename or type change to a shared interface, `test:ci` is the type gate — not `build`.** Run all
three, and read the **exit code** of each (an unhandled jsdom/canvas error makes vitest exit non-zero even
with 0 test failures):

```powershell
npm run lint:tokens ; npm run test:ci ; npm run build
```

### ⚠ …and piping a gate into `head`/`tail` reports the WRONG exit code

`node tools/check-secrets.mjs | tail -5; echo $?` prints **`tail`'s** status (always `0`), not the guard's — so
a failing gate reads as a clean pass. Observed 2026-07-27: the secrets guard was nearly filed as green this way.
Capture the status from the command itself, redirecting instead of piping:

```bash
node tools/check-secrets.mjs > /dev/null 2>&1; echo "EXIT=$?"
```

(In Bash you can also read `${PIPESTATUS[0]}`, or set `set -o pipefail`.) This applies to **every** gate whose
output you trim — the Maven reactor, the two Node guards, and the npm scripts.

⚠ **Known pre-existing FALSE RED — `check-secrets.mjs` exits 1 on a clean tree.** Its 4 hits are all in
`file-processor-deploy/ui/chunk-*.js`, which is **gitignored with zero tracked files** (`.gitignore:44`), and are
minified library property assignments (`withCredentials`, `apiKey`), not credentials. CI is unaffected (a fresh
clone has no such directory). Don't chase it as a regression, and never silence it with `secret-allow` on
generated bundle files. → `docs/BACKLOG.md` §6.

### ⚠ …and `-pl <module> -am` is a false green for everything DOWNSTREAM

`-pl X -am` builds X plus its **upstream** dependencies — never its **dependents**. So a control-plane
change verified with `-pl inspecto -am` leaves every module that consumes `inspecto` unbuilt, and
reports BUILD SUCCESS.

Observed 2026-07-25 (API-5, retiring the unversioned API surface): `-pl inspecto -am` went green at
`inspecto` 530/0/0 while **three downstream modules were still red** — `inspecto-agent` (11 E2E tests),
`inspecto-intelligence` (11 recorded-path assertions) and `inspecto-policy` (8). Each round-trip through
the narrow command cost a full run and still under-reported.

**Use `-pl X -am` only to iterate on a failure you have already located.** To decide "is this change
done", run the whole reactor:

```powershell
mvn -o clean test -Pedition-enterprise          # the verdict
mvn -o clean test -Pedition-enterprise -fae     # add -fae to see EVERY module's failures in one pass
```

Plain `-Pedition-enterprise` is fail-fast, so a single broken module hides the rest; `-fae`
(`--fail-at-end`) is what you want while draining a multi-module breakage. Note `-fae` still skips
modules that *depend* on a failed one — those are unverified, not passing.

### ⚠ …and a red reactor is not always YOUR red — one known FALSE RED (fixed 2026-07-27)

`ControlApiShareTest.tamperedAndUnknownTokensAreIndistinguishable404s` failed
`expected: <404> but was: <200>` roughly **1 run in 4096**, in a module a change need not have touched.
Cause was the test, not the product: it forged a "tampered" token as
`token.substring(0, len - 2) + "zz"`, and a share token is
`base64url(payload) + "." + base64url(HMAC)` (`ShareTokens.java:33-40`) — so whenever the digest already
ended in `zz` the tamper was a **no-op**, the signature still verified, and the server correctly returned
200. Fixed by flipping the **first** character of the signature segment instead, plus an
`assertNotEquals` guard that the tamper changed anything.

⚠ **The obvious fix is also wrong**: flipping the token's LAST base64 character is unsound even when the
string changes. A 32-byte digest encodes to 43 unpadded chars, so the final character carries 2 unused low
bits and some flips (`'z'`→`'y'`) decode to the **same bytes**. Tamper where every bit is significant.

**The lesson generalises:** before believing a reactor red belongs to your diff, check whether the failing
module is even downstream of what you touched, then re-run the class ~3× and once with your work
`git stash`ed. (⚠ plain `git stash` does **not** stash untracked files, so brand-new files stay present —
usually fine, but know it before drawing conclusions.)

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

### ⚠ Count the reactor total mechanically — the eyeballed sum is wrong

The recorded baseline was **2049 for months and that number is WRONG** (found 2026-07-27). Summing
per-module `Tests run:` lines by hand silently drops modules, and it dropped two (57 and 178).

⚠ **The mechanism, confirmed 2026-07-27 — the two dropped modules are the ones Maven prefixes
`[WARNING]`, not `[INFO]`,** because they have skipped tests. So **never anchor the grep on `[INFO]`**:
`grep "^\[INFO\] Tests run:"` loses exactly those two modules and silently under-reports by 235, which
looks like "227 tests vanished" once your own new tests are in. Match the *suffix*, never the prefix.
(This trap was re-hit that same day by a sub-agent that had this section available — it is the default
mistake, so the recipe below is the one to paste.)

Maven prints no grand total, so **always compute it**, from the module-summary lines only (they have no
`-- in` suffix — the `-- in <class>` lines are per-class and double-count):

```bash
grep -E "Tests run:.*Skipped: [0-9]+$" full.log \
  | sed 's/.*Tests run: \([0-9]*\).*Skipped: \([0-9]*\)$/\1 \2/' \
  | awk '{t+=$1; s+=$2} END {print "TOTAL:", t, "skipped:", s, "modules:", NR}'
```

**Correct baseline as of 2026-07-27: 2296 tests, 0 failures, 0 errors, 3 skipped, across 14 test-bearing
modules (16 reactor modules)** — 2288 earlier the same day, +8 for `RetentionSweepSeamTest` (MNT-14). The
3 skips are pre-existing (1 in `ConfigSafetyValidatorTest`, 2 in one `inspecto-etl`-tier module), not a
regression — and they are *why* those two module lines carry the `[WARNING]` prefix above.

⚠ Note `bc` and `grep -P` are unavailable in this Git-Bash environment — use `awk` and POSIX classes.
⚠ And a total is not a verdict: confirm your own new test classes appear in the log with the counts you
expect. A green reactor that never ran your test is the quietest false green there is.
