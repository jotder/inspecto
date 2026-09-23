---
type: Concept
title: Build & Test
description: The offline Maven verify loop, the mandatory DuckDB native-access JVM flag, and package.ps1 edition bundles.
resource: pom.xml
tags: [build, test, maven, duckdb, packaging]
timestamp: 2026-06-28T00:00:00Z
---

# Build & Test

## Verify loop (offline, authoritative)

```
mvn -o clean test          # full reactor; "verified" = this passes
mvn -o clean package -q    # → inspecto/target/inspecto-processor-*.jar (fat JAR)
```

Always offline (`-o`). Tests spin up a real `CollectorService`/[`ControlApi`](../control-plane/control-api.md) on
an ephemeral port. (Java **27** toolchain + Maven, `release=27` — moved 2026-09-17. The local JDK is
`C:\sandbox\.graalvm-cache\jdk-27-win` (Oracle 27+35-2325) and CI sets up **Oracle** JDK 27, not Temurin:
Adoptium had published no 27 build as of 2026-09-17. ⚠ The GraalVM cache still holds 25.0.3 (Windows) /
25.1.3 (Linux); the Linux entry is the jmods source for the cross-built runtime and is NOT the toolchain.
See the `build-verify` skill for exact local paths.)

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
into `data/ref/`. A Pipeline with **no** same-named sample directory is **reported**, not skipped in
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

## Packaging — `package.ps1`

`inspecto/package.ps1` emits the deployment bundle. Switches: `-NoBuild` (reuse `target/`), `-NoUi` (skip the
Angular build), `-NoRuntime` (skip the embedded jlinked JVM), and **`-Edition Personal|Standard|Enterprise`** (⚠ this omitted `Enterprise` — the only non-Personal flavour CI actually releases — until 2026-09-09) (selects
the Maven [edition](../editions/editions-model.md) profile + assembles the per-edition fat-JAR). Generated
launch scripts embed the native-access flag and the key [`-D` flags](operations.md).

### What the bundle contains — and what it deliberately does not

Verified by building both flavors 2026-08-27 (Personal 169.3 MB, Enterprise 170.3 MB, exit 0):

| Jar | Personal | Standard / Enterprise |
|---|---|---|
| `inspecto.jar` (shaded core) | ✅ | ✅ |
| `inspecto-security.jar` (OIDC `Authenticator` SPI) | — | ✅ Professional+ |
| `inspecto-policy.jar` (ABAC `AccessDecider` SPI) | — | ✅ Enterprise only |
| `inspecto-connectors.jar` (shaded sidecar — `CONNECTORS-BUNDLE-1`) | ✅ | ✅ |
| the **seven** EDG-01 sidecars (`notify-channels` shaded, `backup`, `geo-link`, `exchange`, `metrics`, `events`, `ops`) | — | ✅ Professional+ |
| `postgresql.jar` (inert until `-Dinspecto.db=postgres` — `PG-1`) | — | ✅ Professional+ |
| **`inspecto-agent` / `inspecto-intelligence`** | **never** | **never** |

> ⚠ **Staged-jar totals** (added 2026-09-09 — this table listed 3 of the 12): Personal stages **2**
> jars, Standard **11**, Enterprise **12**. The complete enumerations in code are `package.ps1`'s
> staging block and its boot-smoke classpath. Owner: [`okf/capabilities/editions/editions.md`](../../capabilities/editions/editions.md) §3.3.

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

⚠ **They are NOT edition-gated modules.** `inspecto-agent`, `inspecto-agent-hosted` and
`inspecto-intelligence` are plain default `<modules>` in the root POM. The profile-gated modules are the
**nine** edition modules: `inspecto-security`, `inspecto-policy`, and the seven EDG-01 ones
(`inspecto-notify-channels`, `inspecto-backup`, `inspecto-geo-link`, `inspecto-exchange`,
`inspecto-metrics`, `inspecto-events`, `inspecto-ops`) — see [editions model](../editions/editions-model.md). The agent modules build in an
ordinary `mvn test` run; they are simply never *bundled*.

⚠ **THREE reactor sizes, three baselines** (2026-09-08, EDG-01 complete): the default (Personal) build is
**23 modules / 3777 tests**; `-Pedition-standard` is **31 modules / 4106**; `-Pedition-enterprise` is
**32 modules / 4126** (`inspecto-ops` contributes 228, of which 11 skip — the environment-gated
`PostgresStateStoreTest`). ⚠ Run `-Pedition-standard` too, not just the other two: it is the only profile
that proves an optional module is **self-contained**. `inspecto-ops` ships in Standard while
`inspecto-policy` does not, so a dependency between them is legal in exactly one direction — and the wrong
direction still PASSED locally off a stale `~/.m2`. A run that stops at a failing module reports a PARTIAL sum and SKIPS the trailing modules —
do not read that as the total, and do not conclude a module "failed" when the build never reached it.

### 🔴 A halted reactor is a SILENT PASS — check it mechanically, not by reading

`REACTOR-HALT-IS-A-SILENT-PASS-1`, filed 2026-09-16 after it nearly landed a false verdict twice in one
day. The paragraph above ("a run that stops at a failing module reports a PARTIAL sum") has been in this
doc since 2026-09-08 and **did not prevent either occurrence** — which is why there is now a guard:

```
mvn -o clean test -Pedition-enterprise -B > build.log 2>&1     # -B, NEVER -q
node tools/check-reactor-verdict.mjs build.log --expect-modules 32
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

🔴 **Check for a live build BEFORE every `mvn`, not just when you remember.** ⚠ Recorded twice on
2026-09-08 because writing it down once did not prevent the second occurrence: a targeted
`mvn -pl asn-parser/asn-decoders/asn-core test` was fired while a full-reactor coverage run was in
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
finished. Cost: one wasted Enterprise verification during EDG-01 cell 7.

🔴 **An optional module's tests can be absent from BOTH numbers while everything looks green.** An
edition module is not in the default `<modules>`, so `mvn -o clean test` never compiles it: a broken one
leaves the everyday build fully green. And if the Enterprise reactor dies in an earlier module, the later
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
classpath the same way, each sidecar inert unless a config asks for it.

⚠ **`inspecto-security` ships SHADED too, for the same reason** (SEC-SIDECAR-BOOT-1, 2026-09-07). It is
profile-gated (`-Pedition-standard` / `-Pedition-enterprise`), but until that date `package.ps1` staged its
plain 16 KB jar — which carries **no `com/nimbusds` classes**, while `OidcAuthenticator` has nine direct
Nimbus imports and `ControlApi` resolves the `Authenticator` SPI *during startup* through an unguarded
`ServiceLoader`. Every Standard and Enterprise bundle failed to boot. It now builds an
`inspecto-security-*-sidecar.jar` (core and slf4j `provided`, so the shade carries Nimbus and nothing else),
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
`mvn clean package -pl inspecto,inspecto-connectors -am`. Miss that and the sidecar is silently absent.
`package.ps1` therefore **verifies the staged jar** (8 factories registered, sshj present, javax.mail
present) rather than trusting the copy — the connector tests all live *inside* the module, where the
classpath is trivially correct, so they can never go red for a packaging gap. That is exactly how this
survived undetected.

**To run with the assist agent**, build the module and put its jar (plus its dependencies) on the
launch classpath yourself — there is no `package.ps1` switch for it:

```bash
mvn -o clean package -pl inspecto-agent -am -DskipTests
```

✅ **The runtime-floor conflict is GONE as of 2026-09-17.** It was: the agent modules need a **JDK
25+ runtime** (their model-transport jars are class-file v69) while the `-NoRuntime` flavor documented
a **Java 24+** target server, so a `-NoRuntime` deployment on Java 24 could not load them. Moving the
compile target to **`release=27`** puts the product's own floor *above* the agent modules' — one floor,
27, for everything. A `-NoRuntime` target server must now provide **Java 27+**, and at that version the
agent modules load by construction. ⚠ The historical reasoning is kept because **PKG-5** in
[BACKLOG](../../../BACKLOG.md) §6 is written against the old two-floor framing.

⚠ The **jlink embedded-runtime step is unproven as of 2026-08-27**: a stale `java.exe` held
`runtime/bin/server/jvm.dll` and step 6c failed with an access error that was a **file lock, not a
build fault**. Both editions pass with `-NoRuntime`. Re-prove jlink on a box with no stale JVMs.


### Bundle contents — the docs tree

`package.ps1` step 7 stages the `docs/` tree into every edition bundle, file by file (not one recursive copy, so a single locked file cannot truncate the rest) — **but only the CURRENT tier**. The two non-current tiers that `CLAUDE.md` defines are withheld by name:

| tier | tree | in the bundle |
|---|---|---|
| 1 — current knowledge | `okf/` + the root canon + `stakeholders/ api/ ui/ ops/ roadmap/ wiki/` | **ships** |
| 2 — active plans | `superpower/` | **withheld** |
| 3 — history | `archived-documents/` | **withheld** |

🔴 **Until 2026-09-16 step 7 shipped all of `docs/` with nothing excluded.** Measured from the entry table of the 2026-09-15 `inspecto-deploy.zip`: **491 docs files, of which 246 were `archived-documents/` and 26 were `superpower/`** — i.e. **55 % of the shipped documentation was material the project itself declares not current**, complete with the archive's ~570 known-broken internal links, superseded designs and refuted claims. Found while REFUTING `GAP-10`, which had worried that 13 archived files were *missing* from the bundle; the real exposure was the exact inverse.

**How the exclusion is written, and why that shape.** `$docsExcludedTrees` is an explicit **list of names**, one entry per tree with the tier reason beside it, matched against the **first path segment only** — never a substring anywhere in the path, which would also have swept e.g. OKF files whose own path contains one of those words. ⛔ Do not collapse it into a pattern or a glob: the reason a tree is out is a *tier decision*, and a glob records no reason. Adding a tree to the list is a product call, not a refactor.

**It fails closed in both directions**, because a filter that also drops wanted docs is worse than the defect it fixes. After the copy, step 7 throws `DOCS TIER LEAK` if any excluded tree reached the bundle, and `DOCS OVER-FILTERED` if any of `$docsRequiredEntries` (`okf`, `stakeholders`, `api`, `ui`, `ops`, `roadmap`, `wiki`, `INDEX.md`, `GLOSSARY.md`, `USER_GUIDE.md`, `ADVANCED_GUIDE.md`, `EDITIONS.md`) is missing. Packaging then prints the staged / withheld counts.

⚠ **Known consequence, not yet decided: the shipped docs carry 323 links that resolve to nothing.** Withholding a tree does not rewrite the documents that pointed into it. **Recounted 2026-09-16 with `tools/check-bundle-doc-links.mjs`** (the first count, 198, was by hand and was low — it counted only the two *tier* trees, and only from `docs/`):

| what the link points at | links | note |
|---|---|---|
| `docs/archived-documents/**` | 181 | withheld tier |
| `docs/superpower/**` | 13 | withheld tier |
| `docs/BACKLOG.md` | 22 | withheld audience — **the first count missed these entirely** |
| `docs/PROJECT_NOTES.md` | 8 | withheld audience — likewise |
| a repo path that exists but never ships (`inspecto/**` source citations, `compliance/**`) | 66 | **not** caused by the withholding decision |
| climbs above the bundle root (`examples/README.md` moved up one level) | 4 | caused by step 4b's relocation |
| **broken in the repository too** | 29 | pre-existing rot, see below |

**224 of the 323 are the withholding decision's own doing**, across **124 distinct withheld target files**. The concentration matters more than the total: **97 are in `INDEX.md` alone** — the front door, which lists both withheld trees as sections — and 41 in `okf/backend/engine/db-layer.md`. `GLOSSARY.md` and `ADVANCED_GUIDE.md` are among the rest.

🔴 **29 of them are broken in the repo as well, and nothing was watching.** They are almost all in `inspecto/README.md` — the file step 7 copies to the **bundle root** as the customer's first page — pointing at `docs/architecture.md`, `docs/configuration.md`, `docs/operations.md`, `docs/plugins.md`, `docs/v3-agent-mvp.md` and friends — every one a dead path that does not exist since the docs consolidation. `tools/check-doc-links.mjs` cannot see them: its `ROOTS` are `docs`, `compliance`, `.claude` + root `*.md`, and `inspecto/` is in none of them. ⛔ This is **not** part of the withholding decision and must not be folded into it — it needs no product call, only the correct targets.

⛔ The remaining 224 need an operator call: rewrite/neutralise the inbound links at package time · ship a marked stub per withheld target · accept them and say so in the bundle README. → `BUNDLE-DANGLING-LINKS-1`

**`tools/check-bundle-doc-links.mjs` is how this is measured, and re-measured.** It has two modes. By default it **simulates** the bundle from `git ls-files` plus the exclusion lists **parsed out of `package.ps1` itself** — no `pwsh`, no build, so CI and a sandboxed agent can both run it; `--bundle <dir>` walks a **real** staged bundle and is the verdict. ⛔ The guard never restates the exclusion lists: a check that keeps its own copy of what it checks drifts from it silently. A failed parse or an empty list exits **2 (cannot run)**, and an emptiness floor fails a run that scanned almost nothing — the recurring failure here is a probe that could not return a hit reporting "absent" and exiting 0. ⚠ **Not wired into `ci.yml` or pre-push**: it is red on `master` today, and wiring it is part of whichever option wins, not a way to make an undecided question loud.

✅ **`BACKLOG.md` and `PROJECT_NOTES.md` are ALSO withheld, by operator decision 2026-09-16** — but through a **second, separate list**, `$docsExcludedFiles`, with its own assertion (`DOCS AUDIENCE LEAK`). ⛔ **The two lists are deliberately not merged.** These two files are current-tier by `CLAUDE.md`'s own canon list and are accurate and maintained; they are withheld because of **audience** — `BACKLOG.md` is the internal defect board, naming open P1s by identifier in the product the customer just installed. A tier exclusion and an audience exclusion are different claims, and collapsing them into one list would lose why either tree is out. ⚠ This paragraph read *"Still shipping … a separate product call"* until that decision landed the same day.

Root `compliance/` is **not** under `docs/` and has never shipped in the bundle — verified from the same zip entry table. → `BUNDLE-SHIPS-THE-ARCHIVE-1` in `BACKLOG.md`

## Open rows this concept owns

⚠ Listed here so the concept knows what it owes; the board carries the detail. A pointer from a row to
this file is a claim, checked by `tools/check-backlog-homes.mjs`.

- **`TESTCONFIGS-PREFIX-SUFFIX-TRAP-1`** — `TestConfigs.write()` emits `pipeline_<hash>.toon`, a PREFIX,
  while every directory-scanning loader matches the SUFFIX `*_pipeline.toon`. 88 test files use the
  fixture and none noticed, because they load by explicit PATH; the trap springs only for a test that
  boots by SCAN — and then it presents as a JVM crash, not a missing file. Left as-is deliberately
  (88 files to fix a trap that has sprung once); re-rank on the second scan-booting test.

- **`LIB-SYSTEM-EXIT-FROM-PUBLIC-API-1`** — `CollectorService.fromArgs` → `ServiceBootstrap.buildFrom(…,
  exitIfEmpty=true)` calls `System.exit(1)` when config discovery finds nothing. Defensible for a CLI
  `main`; `fromArgs` is a PUBLIC API that tests and embedders call, so the process dies with no exception
  to catch and no stack trace. ⚠ **Surefire reports it as "The forked VM terminated without properly
  saying goodbye. VM crash or System.exit called?" — read the second half of that question first.**
  This exact symptom consumed two sessions and produced a wrong JDK-27 diagnosis before being traced
  (`WorkflowConfigLoadTest-1`, closed `f1232b03`).

- **`OPENAPI-CONTRACT-RED-ON-MASTER-1`** — `OpenApiPathsContractTest` fails on an undocumented
  `GET /assist/skills` and halts the reactor at `inspecto-processor`, so any `-pl <module> -am` run
  currently reports a failure that is NOT the change under test. Regenerate with
  `-Dopenapi.paths.write=true` or document the route.

- **`REACTOR-VERDICT-CI-1`** — wire `check-reactor-verdict.mjs` into `ci.yml`. ⛔ Deliberately NOT
  pre-push: it judges a *build*, not repo state, and producing a log at push time means a ~20-minute
  reactor per push. CI already runs one, so the log is free there.
- **`BUNDLE-DANGLING-LINKS-1`** — 323 links in the shipped docs resolve to nothing, **224** of them
  because of the withheld trees/files (97 in `INDEX.md` alone, 124 distinct targets). Measured by
  `tools/check-bundle-doc-links.mjs`, which is NOT yet wired into CI. Rewrite at package time · ship
  marked stubs · accept and say so in the bundle README — an owed call. ⚠ A separate, *unowed* half fell
  out of the same measurement: 29 of the 323 are broken in the repo too, nearly all in
  `inspecto/README.md`, which becomes the bundle's root page and which `check-doc-links.mjs` does not
  scan.

- **`README-LINKS-BROKEN-IN-REPO-1`** — 29 links are dead in the repository itself, nearly all in
  `inspecto/README.md` (still tracked), which step 7 copies to the **bundle root as the customer's first page**. ⛔
  `check-doc-links.mjs` cannot see them: its `ROOTS` are `docs`, `compliance`, `.claude` and root `*.md`,
  and `inspecto/` is in none of them. ⚠ Fix the targets and widen the scope — do **not** let the
  package-time neutralisation swallow these, or repo rot survives behind a bundle rewrite.

- **`EDITION-GATED-TESTS-IN-WRONG-HOME-1`** — six test classes guard CORE behaviour from inside
  `inspecto-ops`, which the default reactor never builds (`mvn -o -pl inspecto-ops -am test` does not even
  resolve without `-Pedition-standard`). ⚠ **Severity is LOW:** `ci.yml:303` runs `-Pedition-enterprise`
  with tests, so they all run on CI — the exposure is the local `mvn -o clean test` loop only.
  ✅ `RepoSpacesConfigValidationTest` is closed (moved to `inspecto`). Remaining: `ControlApiDbBrowserTest`,
  `ControlApiDecisionRulesTest`, `ControlApiScopedObjectsTest`, `ControlApiAccessDeciderTest`,
  `ControlApiReconPromoteTest`, `PostgresStateStoreTest`. ⛔ Not fixable by adding the module to the
  default `<modules>` — that reverses signed decision EDG-01 cell 7.

### `DOC-COUNTS-GUARD-SCOPE-1` — the allow-list that caused the README row, removed from its sibling too

`README-LINKS-BROKEN-IN-REPO-1` was fixed at the **shape**: `check-doc-links.mjs` went from
`ROOTS = ['docs','compliance','.claude']` to the whole repo minus a deny-list (`ROOTS = ['.']`), which is what
made the customer-facing `inspecto/README.md` visible at all. Re-verified 2026-09-17 by falsifying it BOTH
ways — four dead links planted in **tracked** files across four previously-invisible trees fail it by name
(including `inspecto/README.md → ../docs/architecture.md`, the exact link the row was filed about), and the
clean tree passes at 1802 links over 529 files.

✅ **CLOSED 2026-09-17 — `tools/check-doc-counts.mjs` now has the same shape as its sibling:** `ROOTS = ['.']`
minus the identical `SKIP_DIRS` deny-list. The walk went from **493** markdown files to **529** (in-scope, after
the `docs/archived-documents` exemption: **253 → 289**), gaining 36 files in nine previously-invisible trees —
`inspecto-agent/docs/` ×14, `asn-parser/docs/` ×10, `tools/templates/*/README.md` ×3, plus `inspecto/README.md`,
`inspecto-ui/README.md`, `dev-infra/`, `spaces/`, `inspecto-engine/`, `inspecto-intelligence/`. The widening cost
**no findings and no noise**: those 36 carry ZERO `<!--count:*-->` markers, so the row's "latent, not live"
assessment held exactly. The 68 marked statements and their derived values are unchanged.

⚠ **The deny-list was re-checked against THIS guard rather than assumed to transfer, and two entries turned out
to be load-bearing for counts specifically:** `graphify-out` holds dated `GRAPH_REPORT.md` snapshots carrying **8**
`count:parser-node-types` markers frozen at the value each snapshot day derived, and `inspecto-deploy` — the
packaged bundle — carries a stale COPY of the whole docs tree, **485** markdown files with **59** markers. Widening
without the deny-list would fail the build on generated copies nobody edits. ⛔ **Neither exists in a fresh clone or
a fresh worktree**, which is the trap the sibling's own header records: the change was therefore verified a second
time against the built main checkout (535 walked, 295 in scope, 68 markers, zero leaked from build output), not
only against the clean worktree where it trivially looks green.

Falsified BOTH ways, and the negative direction is the one that proves the fix load-bearing: a deliberately wrong
`999` carrying the `count:parser-node-types` marker, appended to **tracked** `inspecto/README.md`, fails it by name
(`inspecto/README.md:491 states 999, but … derives 7`, exit 1) while the **pre-fix** guard exits **0** on the very
same marker. ⚠ Plant into a file that already exists and is tracked — `git status` must read `M`, not `??`; an
earlier lane's `>>` created the file it meant to test and proved only that untracked files are scanned.

⚠ **Writing THIS section turned the guard red, which is worth keeping:** unlike `check-doc-links.mjs` and
`check-vocabulary.mjs`, `check-doc-counts.mjs` does **not** strip fenced blocks, so a marker quoted literally in
prose — even inside backticks or a code fence — is scanned as a live marker. Documenting one by example is a
standing failure; name the id (`count:<id>`) without the comment delimiters instead. Widening the scope widened
this hazard to every markdown file in the repo.

⚠ **A trap that cost two lanes a build each:** `-DfailIfNoSpecifiedTests=false` is **silently ignored** by
this Surefire (3.5.3). The working spelling is `-Dsurefire.failIfNoSpecifiedTests=false`; without it a
filtered `-am` run dies at `asn-core` — red for the wrong reason.

### Two guard-scope rows this concept still owns (filed 2026-09-17)

**`DOC-COUNTS-FENCED-MARKER-1`** — `check-doc-counts.mjs` does **not strip fenced blocks**, unlike
`check-doc-links.mjs` and `check-vocabulary.mjs`. A count marker quoted literally in prose, in backticks, or
inside a fence is scanned as a live assertion. Found by hitting it while writing the section above; the DOC was
fixed, never the guard. ⛔ Widening the scope to the whole repo widened this trap to every markdown file in it,
so documenting markers by example is now impossible anywhere.

**`README-VOCAB-SCOPE-1`** — `check-vocabulary.mjs`'s `USER_FACING` list (`:66`) holds only
`docs/USER_GUIDE.md`, and its tree scan covers `docs/**` plus the root canon, **not module READMEs**. So
`inspecto/README.md` — the file `package.ps1` copies to the bundle root as the customer's FIRST page — is
unchecked for banned synonyms. ⚠ The sixth guard-scope blind spot in three days, and the same family as
`README-LINKS-BROKEN-IN-REPO-1` (fixed at the shape) and `DOC-COUNTS-GUARD-SCOPE-1` (fixed at the shape) —
which is the precedent for fixing this one.

### The guard-scope family, closed 2026-09-17 — and what the seventh instance cost to find

Three more guards moved from an allow-list to a deny-list, joining `check-doc-links.mjs`:

- **`check-vocabulary.mjs`** (`README-VOCAB-SCOPE-1`) — pass 3 now carries `DOC_SKIP =
  ['docs/archived-documents/']`. ⚠ Its pass 4 reads Java/TS through a **different rule set**; the prose pass is
  markdown-only, which is what made the reshape cheap. 61 files newly in scope, **three** violations, all the
  data-origin sense of that word as a bare table-column header — recorded in `DOC_ALLOW` with reasons, since
  none is a stale synonym. ⛔ One is a **golden fixture** whose text the document generator emits; renaming
  that header to satisfy a guard would have broken its test. `inspecto/README.md` is promoted to `USER_FACING`,
  the no-allowlist pass, because there is no audience below the bundle's first page.
- **`check-doc-citations.mjs`** (`CITATION-GUARD-SCOPE-1`) — the **seventh** instance of the shape. 36 newly
  scoped files carried 8 dead citations, including two type names renamed in 2026-08 still shipping on
  `inspecto/README.md`. ⛔ The `./`-strip in `slash()` is **load-bearing** under `ROOTS = ['.']`: `EXEMPT_TIERS`
  is a plain `startsWith`, so without it both never-maintained tiers silently re-enter scope.
- **`check-doc-counts.mjs`** (`DOC-COUNTS-FENCED-MARKER-1`) — now strips fenced blocks like four siblings.
  ⚠ **Inline backticks are deliberately still scanned:** the floor ratchet has drifted from its stated design
  (68 markers, floors summing to 55), so a marker hidden behind one stray backtick would stop being policed
  rather than trip the floor.

✅ **Three guards now independently derive the same current-tier corpus** — 289 markdown files
(`check-doc-citations` reaching it as 276 + the 13 `docs/superpower/` files it alone exempts, because an
in-flight plan citing an unbuilt path is the plan working).

⛔ **The proof that matters is old-green/new-red, not red-then-green.** Every one of these was falsified by
running the *previous* guard against the same planted input: red-then-green only shows a guard can fail;
old-green/new-red shows the widening does something. Two of the three were invisible on today's corpus
otherwise.

### `EDITION-GATED-TESTS-IN-WRONG-HOME-1` — three closed by SPLITTING, and the trap in the other three

`ControlApiDecisionRulesTest` (5 core / 3 ops), `ControlApiDbBrowserTest` (7 / 1) and `PostgresStateStoreTest`
(11 / 5) were **split, not moved**: the core half runs in the default reactor, an ops sibling keeps every case
needing operational objects. 32 tests before, 32 after; no package churn; no new dependency.

🔴 **Imports do not establish ops-independence.** `ControlApiDecisionRulesTest` imports nothing from
`com.gamma.ops`, yet three of its tests drive `GET /objects`. In a default build one **failed** and another
**passed vacuously** — with ops absent, the `/objects` envelope happens to satisfy its `== 1`. Moving the class
whole would have landed a vacuously-green test. The positive control caught it; the triage did not.
