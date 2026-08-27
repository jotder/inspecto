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

⚠ **They are NOT edition-gated modules.** `inspecto-agent`, `inspecto-agent-hosted`,
`inspecto-intelligence` and `inspecto-connectors` are plain default `<modules>` in the root POM — the
only profile-gated modules are `inspecto-security` and `inspecto-policy`. They build in an ordinary
`mvn test` run; they are simply never *bundled*.

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
