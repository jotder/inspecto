---
type: Reference
title: Operations reference — utilities, batching, output, deployment — Remote deployment and onboarding a Collector
description: Part 4 of 4 of Operations reference — utilities, batching, output, deployment: Deployment — Remote Server; Onboarding a New Collector
tags: [operations, utilities, deployment, batching, output, onboarding]
timestamp: 2026-07-16T00:00:00Z
---

# Operations reference — utilities, batching, output, deployment — Remote deployment and onboarding a Collector

Part 4 of 4 of [Operations reference — utilities, batching, output, deployment](admin-guide-operations-reference.md) · previous: [Batch processing — the status backend onward](admin-guide-operations-reference-status-backend.md).

## Deployment — Remote Server

`inspecto.jar` bundles every third-party dependency and the 15 first-party core libraries ship as thin jars beside it; the launchers read the classpath from the bundle's `modules.list` (P3d) — no JVM classpath setup is needed on the target, but `java -jar inspecto.jar` no longer works.

### Build the deployment bundle

```powershell
# From the sandbox root or from inside inspecto/:
powershell -ExecutionPolicy Bypass -File inspecto\package.ps1

# Skip the Maven build if the JAR is already current:
powershell -ExecutionPolicy Bypass -File inspecto\package.ps1 -NoBuild
```

This produces **`inspecto-deploy-<platform>.zip`** in the sandbox root, one per embedded runtime (Since 2026-09-17 the zip is named after the EMBEDDED runtime's platform — `inspecto-deploy-<platform>.zip` (`windows_amd64`, `linux_amd64`), read off the jlink image, never the host OS (`RELEASE-BUNDLE-PLATFORM-MISMATCH-1`).). The script:
1. Runs `mvn clean package` to build a fresh product jar (processor + third-party) and the 15 core thin jars
2. Builds the optional operator UI (`inspecto-ui/` via npm) and bundles its `dist/` as `ui/` — skip with `-NoUi`, or omitted automatically when `inspecto-ui/` is absent
3. Assembles a self-contained bundle with the JAR, config files, and run/serve scripts
4. Rewrites `schema_file` paths in the bundled configs so they are relative to the bundle root
5. Creates all placeholder directories (inbox, database, backup, temp, errors, quarantine)
6. Zips everything into `inspecto-deploy-<platform>.zip` — on a POSIX host with Info-ZIP `zip -X` so `serve.sh`/`run.sh` keep their exec bit; on Windows with `Compress-Archive` (NTFS has no mode bits to preserve)

### Bundle contents

Since `BUNDLE-LAYOUT-1` (2026-11-06) the bundle root holds `README.md` and folders only (a `-Distribution demo` build adds
`DEMO-BUILD.txt`). `package.ps1` step 7 asserts it: an unexpected entry at the root, a missing `bin/`, `lib/`, `logs/`,
`docs/` or `sbom/`, or a staged `examples/` stops the package.

```
inspecto-deploy/
  README.md
  bin/                        ← every launcher; each cd's to the bundle ROOT (the parent of bin/) first, so it runs from any directory
    serve.sh  serve.bat       ← control plane + UI (ControlApi; reads PORT/SPACES_ROOT/CORS_ORIGIN/AUTH_OIDC_*/INSPECTO_JAVA_OPTS)
    run.sh    run.bat         ← one-shot ETL (CollectorProcessor) for spaces/*/config/<adapter>/*_pipeline.toon
    install-service.sh  install-service.ps1   ← systemd unit / Windows boot Scheduled Task (SCR-3)
    serve-demo.sh serve-demo.bat               ← -Distribution demo builds only (they replace serve.* and the installers)
  lib/
    inspecto.jar              ← the product jar: processor classes + ALL third-party dependencies
    inspecto-api.jar … inspecto-engine.jar     ← the first-party CORE libraries as thin jars
    inspecto-*.jar, postgresql.jar             ← the edition's optional modules and sidecars
    modules.list              ← the classpath the launchers read, one ROOT-relative `lib/<jar>` per line, in order
                                (written by tools/offering-classpath.mjs - the one seam between layout and classpath)
    edition.properties        ← edition=Basic|Standard|Advanced|Preview
    duckdb/<platform>/*.duckdb_extension       ← air-gap DuckDB extensions; -Dduckdb.extension.dir=lib/duckdb/<platform>
  deploy/
    Dockerfile                ← container wrapper over bin/serve.sh (PKG-3; see "Containerized deployment")
    Dockerfile.dockerignore   ← BuildKit's per-Dockerfile ignore file (keeps bin/*.bat, bin/*.ps1 and nested zips out)
    inspecto.service          ← the systemd unit bin/install-service.sh installs
  logs/                       ← captured launcher output (bin/install-service.ps1 appends serve.bat's output to logs/service.log)
  runtime/                    ← the embedded jlink JVM; every launcher prefers it over a system java
  ui/                         ← the built SPA (present when inspecto-ui/ was built); served via -Dui.dir=./ui
  spaces/_templates/          ← the Space-template gallery; drop your Space folder(s) beside it
  docs/                       ← USER_GUIDE.md, ADVANCED_GUIDE.md, EDITIONS.md and api/ ONLY (step 7's allow-list;
                                the server serves docs/api/openapi-v1.json at GET /api/v1/openapi.json, cwd-relative)
  sbom/                       ← the combined CycloneDX + SPDX SBOMs and one SBOM per jar
  licenses/                   ← third-party licence texts for bundled native libraries (Advanced/Preview)
```

The worked examples (`inspecto/examples/`) are **not** in the bundle; they stay in the repository with their
`run-example` / `serve-example` runners.

**Logging.** `ControlApi` logs to **stdout only** (logback `CONSOLE` appender + the in-process event store; there is no
file appender). A shell launch therefore logs to the console. Where a wrapper captures that output it goes to the
bundle's `logs/`: the Windows Scheduled Task appends to `logs/service.log`; the systemd unit sends it to the journal
(`journalctl -u inspecto`); the container keeps it on stdout (`docker logs`), with `/app/logs` writable. A one-shot
`bin/run.*` writes its per-run log to the pipeline's own `dirs.logs`, inside the Space.

### Deploy and run

```bash
# 1. Copy the zip to the server and extract
unzip inspecto-deploy-linux_amd64.zip
cd inspecto-deploy

# 2. Attach your Space(s): drop each Space folder into spaces/ (or set SPACES_ROOT)

# 3. Run the ETL once
bash bin/run.sh <data_source>          # Linux
bin\run.bat <data_source>              # Windows

# 4. OR run the always-on control plane + operator console
bash bin/serve.sh                      # Linux/Mac → http://localhost:8080/
bin\serve.bat                          # Windows
# Auth is an edition feature: Basic serves unauthenticated, Standard/Advanced take AUTH_OIDC_*.
```

### Containerized deployment (PKG-3, shipped 2026-08-28)

The bundle carries `deploy/Dockerfile` + `deploy/Dockerfile.dockerignore` (emitted by `package.ps1` step 6b-2, like the
launcher scripts). It wraps `bin/serve.sh` over **existing seams only** — no configuration surface of its
own: every serve.sh env var passes straight through `docker run -e` (PORT / SPACES_ROOT /
CORS_ORIGIN / AUTH_OIDC_* / INSPECTO_JAVA_OPTS …). The build context is the bundle ROOT; the Dockerfile is named with `-f`,
and BuildKit (the default builder) reads `deploy/Dockerfile.dockerignore` as that build's ignore file.

```bash
unzip inspecto-deploy-linux_amd64.zip && cd inspecto-deploy
docker build -f deploy/Dockerfile -t inspecto .
docker run -p 8080:8080 -v /srv/inspecto/spaces:/app/spaces inspecto
```

**An image per Product (PRODUCT-ASSEMBLY-1 PA-8b, 2026-11-12; D-PA26).** `package.ps1 -Product <id> -Edition <e> -Target container`
does the build above for you. It needs Docker and the `linux_amd64` zip, so pass `-GraalvmCache` a folder holding a
`*linux*` JDK with `jmods/`; the build is refused up front without Docker and after the zips without the Linux zip.
- It unpacks the Linux zip, never the staging folder, which holds the host runtime after a cross-build.
- `tools/ship.mjs image` then checks that the bundle is the plan's build: `lib/product.json` names the plan's Product,
  Edition and Distribution, `runtime/bin/java` is an ELF file, and the Dockerfile carries the Product label.
- It builds `inspecto-<product>:<version>-<edition>[-trial]` (the Platform product stays `inspecto`) and checks the
  image's `io.inspecto.product` label.
- It saves the image as `inspecto-ship/<image>-<tag>/<image>-<tag>.image.tar` and stamps the Product's Helm chart beside it.

Every bundle's Dockerfile now carries OCI labels from the Assembly Plan: `org.opencontainers.image.title`,
`.description` and `.version`, plus `io.inspecto.product`, `.edition`, `.distribution` and `.apps`. The demo
Distribution is refused for this target.

Proven 2026-11-12: `package.ps1 -Product telco -Edition Standard -Target container -NoUi -GraalvmCache C:\.jdks` built
`inspecto-telco:4.0.0-SNAPSHOT-standard` (204 MB saved). Run with `--read-only --tmpfs /tmp:exec --cap-drop ALL
--security-opt no-new-privileges` and placeholder `AUTH_OIDC_*` values, it ran as uid 10001; `/health` answered
`UP` and `/ready` `READY`, and `lib/product.json` is Inspecto Telco Standard with its four Apps.

As-built decisions:
- **The jlink module set is derived (2026-10-07, P3c).** `tools/jlink-modules.mjs` unions `jdeps` over every staged jar with `tools/jlink-runtime-extra.txt` (reflection/ServiceLoader needs, each with a WHY) and holds it against `tools/jlink-modules.lock`; since 2026-10-18 `package.ps1` links the derived set alone and stops the package on a lock mismatch or a missing edition section (the hand-kept list was retired). `--check` is the jar-free validity check. Detail: [module-taxonomy](architecture-modules.md) (Packaging).
- **The bundle's own jlink `runtime/` supplies the JVM** (changed 2026-09-17); the base is
  `debian:stable-slim` and provides only glibc + bash. It was `eclipse-temurin:24-jre` with
  `.dockerignore` excluding `runtime/`, so serve.sh's `[ -x runtime/bin/java ]` preference missed on
  purpose. At `release=27` that image cannot run the JAR, and **Adoptium has published no
  `eclipse-temurin:27-*`** (their release API topped out at 26 on 2026-09-17) — there is no vendor JRE
  image to point at, so the bundle carries its own. ⚠ The Dockerfile is therefore valid **only in the
  `linux_amd64` bundle**; it asserts this with a `runtime/bin/java -version` at build time rather than
  failing at container start.
  🔴 **This bullet was ahead of the code until 2026-09-29:** `package.ps1` still emitted
  `eclipse-temurin:24-jre` and excluded `runtime/`; fixed then (`ASSURE-OPERABILITY-1`).
- **Verified 2026-09-29 on the fixed Dockerfile** (Docker 29.5.3, Standard bundle packaged with
  `package.ps1 -Edition Standard -NoUi`, `inspecto-deploy-linux_amd64.zip`, `docker build` exit 0):
  `runtime/bin/java -version` in the image → `java version "27" 2026-09-15` (build 27+35-2325); `id` →
  `uid=10001(inspecto) gid=10001(inspecto)`; `docker top` on the running container shows the java process at
  UID/GID **10001**; `GET /health` → 200 `{"status":"UP"}`, `GET /ready` → 200, `HEALTHCHECK` → `healthy`.
  A second run with `--read-only --tmpfs /tmp:exec --cap-drop ALL --security-opt no-new-privileges` and
  `spaces/demo` mounted booted the Space (13 Pipelines) and answered `/health` + `/ready` 200. ⚠ Standard
  refuses to start without `AUTH_OIDC_ISSUER` / `AUTH_OIDC_JWKS_URI` (fail-closed); the run passed placeholder
  values — `/health` is public, so no token was validated. Containers were stopped and removed. The
  2026-08-28 line below is the old temurin image.
- **Base pinned by digest, and non-root** (operator 2026-09-29): `FROM debian@sha256:520c7157…8e2d` (the
  linux/amd64 manifest of `debian:stable-slim`; bump it deliberately), and a dedicated `inspecto` user/group
  with fixed uid/gid **10001** (`USER 10001:10001`). `/app` and `spaces/` are chowned to it; the jars, the
  runtime and `serve.sh` stay root-owned and read-only to the process.
- **HEALTHCHECK probes `/health` via a bash `/dev/tcp` HTTP GET, not curl** — the temurin JRE image
  ships **no curl or wget** (verified 2026-08-28; the original plan's curl one-liner would have
  reported unhealthy forever). `/health` is tokenless via `PUBLIC_PATHS`, correct for a healthcheck.
- Verified end-to-end 2026-08-28: build (713 MB), run, `/health` + `/ready` 200, healthcheck
  reaches `healthy`; the emitted file is byte-identical to the packer's here-string.

**Extra JVM flags — `INSPECTO_JAVA_OPTS`** (fallback `EXTRA_JAVA_OPTS`), honored by all four launchers
since `1768978f`, and by `serve-demo.sh`/`.bat` (demo bundles). Whitespace-separated, appended **after** each script's own flags (and, in
`run`, before `-jar`/`-cp`), so the mandatory `--enable-native-access=ALL-UNNAMED`, the port,
the spaces root and the auth wiring cannot be clobbered from the environment. The launch banner
echoes whatever was passed, so a flag that did not land is visible rather than silent.

```bash
INSPECTO_JAVA_OPTS="-Xmx4g" bash bin/serve.sh                                   # bigger heap for the server
INSPECTO_JAVA_OPTS="-Xmx8g" bash bin/run.sh <data_source>                        # bigger heap for one run
```

⛔ **Do not use `JAVA_OPTS`.** The scripts *assign* that name, so an exported `JAVA_OPTS` is silently
discarded — before `560e9f13` this was the only thing an operator would think to try, and a BUNDLE-1
diagnostic run once reported "no errors" purely because the flag never reached the JVM.

**`run.sh`/`run.bat` declare the path-jail root themselves (PKG-6, 2026-08-28).** The one-shot
`CollectorProcessor` runs no space discovery — unlike `serve`, where `SpaceManager` registers each
space base with `DiscoveredRoots` before loading configs — so `-Dassist.safety.roots` is its *only*
root source, and a pipeline carrying a `schema_file:`/`mapping_file:` ref used to die on a fresh
bundle with *"no allowed roots configured for 'schema_file'"* (the whole format-example pack:
`csv_example`, …). The launchers now pass `-Dassist.safety.roots=<the space dir of the pipeline they
resolved>` — bash strips at `/config/`, cmd reuses its `for /d` loop variable — so exactly the ONE
space an invocation runs out of is allowed, never the whole `spaces/` tree.

- An operator `INSPECTO_JAVA_OPTS="-Dassist.safety.roots=…"` still **wins**: it is appended after,
  and a later `-D` of the same key overrides an earlier one.
- The posture rule this follows (and why the engine CLI deliberately does *not* run discovery) is in
  [Config safety](../../module/config/spec-config-safety.md).

**Reading the control-plane log: a cancelled asset fetch is DEBUG, not an error** (`994d21ca`). A
browser aborts its in-flight requests on every reload or navigation, and the static write then fails
with a platform socket message (*"An established connection was aborted by the software in your host
machine"*, *"Connection reset by peer"*). That used to surface as `ERROR … failed` plus a ~30-frame
stack, which reads like a server fault and is the single noisiest line in a normal session's log;
`errorBoundary` now classifies it via `isClientDisconnect` and logs one DEBUG line. **So a stack trace
in this log is now signal.** ⚠ It also means an aborted request no longer produces any INFO-level
trace at all — if you are counting served assets against the BUNDLE-1 clean-load signature
(`docs/BACKLOG.md` §5), turn on `-Dui.static.log=DEBUG` or the abort will be invisible.

**Direct invocation** (without the run scripts):
```bash
java --enable-native-access=ALL-UNNAMED \
     -jar inspecto.jar \
     config/<data_source>/<data_source>_pipeline.toon
```

**Java requirement:** **Java 27** or later (`pom.xml` `maven.compiler.release` = 27; the shipped container carries the bundle's own jlink 27 runtime on a `debian:stable-slim` base). No other runtime dependencies. ✅ **One floor, not three** — as of 2026-09-17 the old split (24 product / 25+ agent modules / 26 toolchain) collapses, because 27 is above all of them.

### Performance reference (single-node, HDD, 4 threads)

| Collector | Files | Rows/file (avg) | Time/file | Total (30 days) |
|---|---|---|---|---|
| <data_source> | 30 × `.csv.gz` | ~2.3 M | ~19 min | ~2.5 hr |
| <data_source> | varies | ~420 K | ~3.4 min | — |

Note: the 20200117 <data_source> file is ~4.3 GB uncompressed (~2.97 M rows) due to ISIZE overflow in the gz header — the ETL handles it transparently via streaming.

### Pre-production checklist (<data_source>)

- [ ] Delete `inbox/<data_source>/20200101/vou_DATE_20200101.csv/` — this is an 8 GB uncompressed directory (duplicate of the `.gz`); the glob pattern would pick up the file inside it and double-process the day
- [ ] Run from the bundle root (or sandbox root locally) so relative paths resolve correctly
- [ ] Verify Java 27+ is on `PATH`: `java -version` — not needed for a bundle that kept its embedded `runtime/`

---

## Onboarding a New Collector

1. **Onboard a sample file with Add data** — it infers the schema and writes the Pipeline (the `create-schema`
   CLI that used to do this from a generation config was retired with the `ura` utilities, 2026-11-06).
   See [Onboarding](../product/user-guide-onboarding.md).

2. **Review the generated schema** — check that types are correct, adjust `partitionKey` if needed.

3. **Edit the pipeline config** if required — adjust `output.format`, thread count, or the DuckLake section.

4. **Run the ETL:**
   ```bash
   bash run.sh mysource      # after adding mysource to run.sh
   # or directly:
   java --enable-native-access=ALL-UNNAMED \
        -jar target/inspecto-processor-<version>.jar \
        config/mysource/mysource_pipeline.toon
   ```

---
