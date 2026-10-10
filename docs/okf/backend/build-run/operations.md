---
type: Concept
title: Operations & Launch Flags
description: The key -D launch flags, run modes, and the production-investigation guide.
resource: inspecto/package.ps1
tags: [operations, launch, flags, run, troubleshooting]
timestamp: 2026-06-28T00:00:00Z
---

# Operations & Launch Flags

The server entry is [`ControlApi.main`](../control-plane/control-api.md) (the `com.gamma.inspector.MainApp` CLI is a
separate pre-ETL tool suite — search/copy/extract/backup/prepare-inbox/create-schema/reprocess).

## Key `-D` launch flags

| Flag | Purpose |
|---|---|
| `-Dcontrol.port=<n>` | HTTP port for the control API (default `8080`). |
| `-Dcontrol.bind=<host-or-IP>` | 🔴 **Listen address. Unset = EVERY interface, in every edition** (`ControlApi.java:292-293`) — a deliberate call, not an oversight, because narrowing it would strand existing installs on upgrade. Personal ships **no `Authenticator`**, so a Personal install left on the default binds an **unauthenticated** control plane to every interface. Set `-Dcontrol.bind=127.0.0.1`, or firewall the port, for any single-user install. An unresolvable value **fails the boot** rather than falling back (`:295-296`). |
| `-Dspaces.root=<dir>` | [Multi-space](../control-plane/multi-space.md) root. **No default** (`ControlApi.java:382` reads it with no fallback): unset ⇒ single-tenant, one `default` space built from the CLI config args. The `spaces` you see in a bundle is `serve.sh:8`/`serve.bat:9`'s shell default, not a JVM one. A bundle ships **no Spaces** there (only `_templates`): drop your Space folder(s) in, or set `SPACES_ROOT`; an empty root boots and Space-scoped routes answer 503 until one is attached. |
| `-Dui.dir=./ui` | Serve the bundled Angular SPA; omit to disable UI serving. |
| `-Dcontrol.cors=<origin>` | CORS origin allow-list for the control plane. |
| `-Dassist.write.root=<dir>` | Enable config/pipeline/connection write-back; absent → mutations return `503` (see [auth & security](../editions/auth-security.md)). |
| `-Dacquire.ledger.backend=db` | Use the durable [DB acquisition ledger](../acquisition/framework.md) instead of in-memory. |
| `-Dauth.mode=none\|oidc` | **Label only** — read at `BootstrapRoutes.java:55,83` to fill `/bootstrap`'s `edition`/`features.authMode`; **nothing branches on it**. The switch is the classpath: `Authenticators.active()` is a `ServiceLoader` lookup, empty on Personal, so dispatch skips auth entirely. `serve.sh`/`serve.bat` set it beside `inspecto-oidc.jar`, which is what actually decided the edition. |

Plus `--enable-native-access=ALL-UNNAMED` is always required (see [build & test](build-test.md)).

## Investigating production

The living production-investigation guide (process/events/metrics/state/Control API/troubleshooting) is
`docs/ADVANCED_GUIDE.md`. Observability primitives: [events & metrics](../control-plane/events-metrics.md)
(`/metrics` Prometheus text, `/events/search` — both OPTIONAL modules since EDG-01, 503 on Personal; the core audit read is `/audit/search`). Performance tuning: [`performance.md`](performance.md);
the full utilities/batching/output/deployment reference is [`operations-reference.md`](operations-reference.md).

## Offline vulnerability scan (ASSURE-OPERABILITY-1, D-P3)

`tools/vuln-scan.mjs` is a zero-dependency Node matcher — no scanner binary is installed or downloaded. It
reads `tools/dependencies.lock` (default) or a bundle SBOM (`--sbom <bundle>/sbom/inspecto-<edition>.cdx.json`)
and matches every Maven coordinate against an **OSV-format snapshot** at `$VULN_DB_DIR` (or `--db`).

- **Verdict:** exit 1 on any unwaived HIGH, CRITICAL or UNKNOWN-severity finding (unknown fails closed);
  exit 0 otherwise; exit **2 = could not run** (no snapshot, no `snapshot.json`, zero records, any
  unparseable record file, a range type it cannot evaluate on a matching package, empty component list, a
  snapshot dated in the future or older than `--max-age-days`, default 30). ⛔ Exit 2 is never a pass.
- **Ranges:** `ECOSYSTEM` uses Maven version ordering, `SEMVER` uses SemVer 2.0 precedence, commit-hash
  ranges (type `GIT`) are skipped because they cannot be matched against a version; any other type exits 2.
- **Severity** comes from each record's `database_specific.severity` (the GitHub advisory field;
  `MODERATE` = MEDIUM). Records with none are UNKNOWN.
- **Waivers:** `compliance/vuln-waivers.json`, an array of `{id, package, reason, expires}`. `id` may be the
  OSV id or any alias (CVE). Missing a field, or past `expires`, waives nothing.
- **CI:** the `test` job runs `node --test tools/vuln-scan.test.mjs` always, and the scan when the repository
  variable `VULN_DB_DIR` names a snapshot on the runner. Without it the step logs a *NOT SCANNED* warning — a
  hosted runner has no snapshot, so today the scan is wired but has never run against real data.
- **Release gate:** `release.yml` runs the same scan before packaging and, on a `v*` tag push, FAILS the
  release when `VULN_DB_DIR` is unset or the scan exits non-zero. A dispatch dry run warns instead. ⚠ Until
  a snapshot is configured, no tag can be released from a hosted runner.

**Snapshot refresh (scripted, operator-approved per run - decision 2026-10-10):** `tools/vuln-db-refresh.mjs`
(Node built-ins only) fetches the OSV `Maven` export into `$VULN_DB_DIR` in the layout `tools/vuln-scan.mjs` reads.
It **never touches the network without `--confirm`**.

1. Dry run (default; prints the URL it would GET and where it would write, fetches nothing):
   `node tools/vuln-db-refresh.mjs --db <dir>`
2. Approve, then fetch: `node tools/vuln-db-refresh.mjs --db <dir> --confirm`
   (`https://osv-vulnerabilities.storage.googleapis.com/Maven/all.zip`; size is not queried by the dry run and
   not measured here). `--ecosystems Maven,npm` also fetches npm records, but `vuln-scan` matches Maven only.
3. Air-gapped path: fetch on a connected host, carry `all.zip` across as `<dir>/Maven/all.zip`, then
   `node tools/vuln-db-refresh.mjs --db <target> --source <dir>` (also `file://`) - no network, same verification.
4. It writes `snapshot.json` (`taken`, `source`, `fetchedAt`, and per file: URL, ETag, bytes, `sha256` of the
   archive, a `treeSha256` over the extracted records, record count), keeps the archive under `_archives/`, and
   builds everything in a sibling temp dir renamed over `<db>` - a failed or corrupt fetch leaves the previous
   snapshot untouched. A zero-record archive or an unparseable record fails the refresh.
5. Freshness / integrity gate, no network: `node tools/vuln-db-refresh.mjs --check --db <dir> --max-age-days 30`
   exits 0 when `snapshot.json` is present, every hash matches and the snapshot is not older than N days (or
   future-dated), else 1. It is NOT wired into any workflow yet; to wire it, add the line before
   `node tools/vuln-scan.mjs` in the `release.yml` gate (and `ci.yml` if wanted), inside the
   `if [ -n "$VULN_DB_DIR" ]` branch. `vuln-scan` itself does not re-verify the hashes; `--check` does.
   Tests: `node --test tools/vuln-db-refresh.test.mjs`.
6. Point `VULN_DB_DIR` at it and run `node tools/vuln-scan.mjs`. Refresh at least every 30 days - older
   snapshots are refused.

## Run-lease database reachability (ASSURE-OPERABILITY-1, 2026-10-03)

With a shared run lease (`-Drun.lease.backend=postgres|db|jdbc:`), `DbRunLease`'s heartbeat (every TTL/3 =
20 s) checks the lease database — a `SELECT 1` when idle, the fenced renewals when holding — and publishes the
verdict three ways: `GET /health/details` → `live.runLease.<scope>` (`DOWN`, detail `unreachable since <instant> -
last error: <msg>`; it also makes the overall status `DOWN`), the gauge `inspecto_run_lease_db_reachable{space,scope}`
(1/0, alertable), and a WARN/INFO log line on each flip. It clears by itself on the first good tick.

- ⛔ **`GET /ready` deliberately ignores it (operator 2026-10-03).** A lease-DB outage hits every node at once;
  draining them all would also stop read-only serving (dashboards, Investigations), which needs no lease. Starting
  a run already fails closed without the lease, so readiness stays 200 and the outage is an alert, not a drain.
  (A first cut on 2026-10-03 made `/ready` 503 — reverted the same day by this decision.)
- ⛔ Live verdicts are a **separate map** from the open-time records (`StoreHealth.live`): `StoreHealth.record`
  throws on DEGRADED under `-Dinspecto.topology=partitioned`, which on the heartbeat thread would cancel the renewer.
- The heap lease (the default) registers no check, so Personal and single-node installs see no change.
- **Failure kind + down duration (operator option C, 2026-10-04).** The detail reads `unreachable since <instant>
  (no successful tick for <n>s, kind DNS|REFUSED|TIMEOUT|AUTH|OTHER) - last error: <msg>`; the kind comes from
  `DbRunLease.failureKind` (exception chain, then message/SQLState). The log flip line carries the kind too. The
  gauge is unchanged (no new labels). It is a hint only.
- Not built (open): telling a node-local partition from a shared outage needs a **second vantage point** (a node
  cannot read other holders while cut off). Options recorded: A peer-heartbeat table in a second store, B an
  external witness probe, D drop it (`/ready` ignores the verdict anyway). No operator choice yet.

## Kubernetes: single-replica Helm chart (ASSURE-OPERABILITY-1, 2026-09-29)

`deploy/helm/inspecto/` is a StatefulSet pinned to **`replicas: 1`** in the template (no value can change
it), one `ReadWriteOnce` PVC mounted at `/app/spaces`, liveness on `GET /health` and readiness on
`GET /ready`, secret-backed environment via `envFrom` (an existing Secret, or one the chart creates from
`secrets.values` for tests), and JVM-terminated TLS (`tls.enabled` mounts a keystore Secret and sets
`HTTPS_KEYSTORE` / `HTTPS_KEYSTORE_PASSWORD`; probes switch to HTTPS). The image is the bundle's own
Dockerfile, built and pushed by the operator.

**Security posture (operator 2026-09-29: non-root):** the pod runs as uid/gid **10001** (`runAsNonRoot`,
`runAsUser`/`runAsGroup`/`fsGroup` 10001, `RuntimeDefault` seccomp), the container drops **ALL** capabilities,
disallows privilege escalation and sets **`readOnlyRootFilesystem: true`**. That last one is grounded, not
assumed: the image was booted with `docker run --read-only --cap-drop ALL` and the repo's `spaces/demo` Space
(13 Pipelines) mounted, and it reached `/health` 200 and `/ready` 200 writing only `/app/spaces` and `/tmp`.
⚠ `/tmp` is an **exec-able** `emptyDir`: DuckDB extracts its JNI library there, and a `noexec` tmpfs fails the
boot with *failed to map segment from shared object*. ⚠ Boot only — a full ingest run under a read-only root
has not been exercised. A **startupProbe** on `/health` (30 × 10 s = 5 min) guards slow first boots; the
liveness/readiness delays are therefore 0. The image tag defaults to the chart's `appVersion`
(`4.0.0-SNAPSHOT`, the reactor version), never `latest`.

⛔ **Not multi-replica safe.** Each pod owns a private Spaces volume and the default run lease is
per-process. Scale-out is the Enterprise partitioned topology, not this chart.

⚠ **Validation was a YAML sanity check only** — `helm` is not installed on the build host. `Chart.yaml`
and `values.yaml` parse and carry the expected keys; the templates were checked for balanced `{{ }}` and
`if/with/range/define` … `end` blocks, but **never rendered** by `helm template` or linted by `helm lint`,
and never installed on a cluster. Run `helm lint deploy/helm/inspecto && helm template t deploy/helm/inspecto`
on a host that has it before first use.

✅ **Image blocker fixed and PROVEN 2026-09-29:** `inspecto/package.ps1` step 6b-2 had still written
`FROM eclipse-temurin:24-jre` with `runtime/` excluded; it now emits the digest-pinned `debian:stable-slim` +
the bundle's own jlinked runtime, non-root. Built from a freshly packaged **Professional** bundle (`-NoUi`) and
run — see [operations-reference.md](operations-reference.md), *Containerized deployment*, for the evidence.
