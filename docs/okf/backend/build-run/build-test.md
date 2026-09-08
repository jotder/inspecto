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
an ephemeral port. (Java 26 toolchain + Maven; see the `build-verify` skill for exact local paths.)

## Mandatory DuckDB native-access flag

Every JVM launch (engine, tests, serve scripts) **must** pass:

```
--enable-native-access=ALL-UNNAMED
```

It's wired into the root `pom.xml` Surefire config as `<argLine>@{argLine} --enable-native-access=ALL-UNNAMED</argLine>`
(the `@{argLine}` prefix lets JaCoCo prepend its agent). Omitting it fails DuckDB's native init.

## Packaging — `package.ps1`

`inspecto/package.ps1` emits the deployment bundle. Switches: `-NoBuild` (reuse `target/`), `-NoUi` (skip the
Angular build), `-NoRuntime` (skip the embedded jlinked JVM), and **`-Edition personal|standard`** (selects
the Maven [edition](../editions/editions-model.md) profile + assembles the per-edition fat-JAR). Generated
launch scripts embed the native-access flag and the key [`-D` flags](operations.md).

### What the bundle contains — and what it deliberately does not

Verified by building both flavors 2026-08-27 (Personal 169.3 MB, Enterprise 170.3 MB, exit 0):

| Jar | Personal | Standard / Enterprise |
|---|---|---|
| `inspecto.jar` (shaded core) | ✅ | ✅ |
| `inspecto-security.jar` (OIDC `Authenticator` SPI) | — | ✅ Standard+ |
| `inspecto-policy.jar` (ABAC `AccessDecider` SPI) | — | ✅ Enterprise only |
| **`inspecto-agent` / `inspecto-intelligence`** | **never** | **never** |

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
**seven** edition modules: `inspecto-security`, `inspecto-policy`, and the five EDG-01 ones
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

🔴 **Check the runtime floor before you do.** The agent modules need a **JDK 25+ runtime** (their
model-transport jars are class-file v69) per
[api-stability.md](../control-plane/api-stability.md) §*Current Java floor*, while the `-NoRuntime`
flavor documents a **Java 24+** target server. The bundled jlink runtime satisfies both; a
`-NoRuntime` deployment on Java 24 does not. Adding a packaging switch is therefore a real decision,
not a missing line — tracked as **PKG-5** in [BACKLOG](../../../BACKLOG.md) §6.

⚠ The **jlink embedded-runtime step is unproven as of 2026-08-27**: a stale `java.exe` held
`runtime/bin/server/jvm.dll` and step 6c failed with an access error that was a **file lock, not a
build fault**. Both editions pass with `-NoRuntime`. Re-prove jlink on a box with no stale JVMs.
