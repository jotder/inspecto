# Legacy (unversioned) API surface removal — API-5 / BACKLOG D3

**Status:** in flight (started 2026-07-25) · **Decision:** BACKLOG §2 D3, authorized 2026-07-25
**Concept home on completion:** `docs/okf/backend/control-plane/api-v1.md`

## What is actually being removed

The backlog framed this as "delete the legacy sunset machinery." That framing is wrong and was
corrected before work started:

- There are **no separate legacy route classes**. `ControlApi.normalizePath` strips `/api/v1`,
  `/api`, or nothing at all into the *same* route table, so "the legacy surface" is not a set of
  files — it is a **routing behaviour**.
- Deleting the sunset machinery (`legacyRoutesOff`, `legacySunset`, `recordLegacyUsage`,
  `markDeprecated`, `sunsetHeader`) **retires nothing**. Bare unversioned paths would keep serving
  business routes, minus the kill switch, the usage metric, and the RFC 9745/8594 headers — strictly
  worse than today.

So the real change is: **business routes require `/api/v1`; only the infra probes stay unversioned.**
Machinery removal is a consequence, not the goal.

### Decided scope boundaries

| Question | Call |
|---|---|
| `/health`, `/ready`, `/metrics`, `/metrics/acquisition` | **Stay unversioned** — no v1 semantics. `isInfraRoute` therefore **stays**; it becomes the allow-list, not a metric exemption. |
| Interim `-Dapi.legacy.routes=off` flag flip + soak | **Skipped** — a detour whose only value is watching a metric for callers already established not to exist. |
| 30-day-at-zero soak criterion (signed 2026-07-08) | **Cannot be met** (17 days elapsed) and no soak evidence exists in-repo. Deliberately overridden: there is no live deployment, and every in-repo caller is being migrated in this same change. |
| Bare `GET /objects` etc. | Falls through to `serveStatic` — **correct**, these are SPA deep links. |
| Bare `/api/<anything>` non-v1 | Must return **JSON 404**, not the SPA shell. Needs a new guard; today it would `serveStatic` a 200 `text/html`. |

## Blast radius (measured, not estimated)

### Production code — the item the backlog inventory missed

`inspecto-intelligence` `ControlPlaneClient` self-calls the control plane over loopback HTTP for the
agent's *act* tools. Every path it builds is unversioned, via one choke point
(`exchange(...)`, `URI.create(base + path)`), so 9 call sites are fixed by one edit:

- `ComponentActions` — component GET/PUT/POST + version restore
- `OperationalActions` — job trigger, run reprocess, object ack, job reschedule
- `RunbookActions` — wrapper over the same calls

**Trap:** prefixing alone is not enough. v1 responses are **envelope-wrapped**
(`{data, metadata, links, permissions, diagnostics}`) and v1 errors become
`{error:{errorCode, message, recoverable, correlationId, details?}}`. `ControlPlaneClient.parse()`
returns the raw body, so consumers reading `resp.body()` would silently start seeing the envelope
instead of the DTO. **This compiles and fails at runtime** — the client must unwrap `data` and lift
the error object, i.e. do server-side what the SPA's `v1Interceptor` does.

`ControlApi.LOCAL_BASE_URL_PROP` stays a **root** base URL (no version) — the version belongs to the
client, matching how the SPA composes `apiBaseUrl + /v1 + path`.

### Tests — `inspecto/src/test/java/com/gamma/control/`

- 90 test files total; **22** already use `/api/v1`, **68** do not.
- **75** raw `"http://localhost:" + port + path` concat sites need the prefix.
- **7** files also probe `/health` / `/ready` / `/metrics` — those paths must **not** be prefixed.
- Two files are about the mechanism itself:
  - `ControlApiLegacySunsetTest` — asserts deprecation headers / the 410 off-switch. Rewritten to
    assert the new contract (unversioned business path ⇒ 404, infra probe ⇒ still 200).
  - `ControlApiV1Test` — prune its legacy-parity assertions, keep the v1 contract ones.

This is why the code+tests commit is **atomic**: the routing change red-lines ~68 files at once.

### Scripts / runbooks (tracked files only)

`file-processor-deploy/` and `.claude/worktrees/` are **untracked build output** — ignore their copies.

- `inspecto/examples/serve-example.ps1` — `Probe '/pipelines'`, `/events?limit=20`, and a printed
  `curl` hint. (`/health` at line 95 stays.)
- `inspecto/examples/06-serve/*/probes.txt` — **6 tracked files**, raw unversioned paths fed to `Probe`.
- `tools/seed-uat.ps1` — `$api = "$Base/spaces/$Space"` drives many writes. (`/health` stays.)
- `.claude/skills/smoke/SKILL.md` — instructs probing `/spaces`, `/spaces/demo/jobs`, `/spaces/demo/views`.

## Commits

**1 — code + tests (atomic; anything less is a red build)**
`normalizePath` + `routeDispatch` routing change · `serveStatic` guard for `/api/*` ·
remove `legacyRoutesOff`, `legacySunset`, `recordLegacyUsage`, `markDeprecated`, `sunsetHeader`
(keep `isInfraRoute`) · `ControlPlaneClient` prefix **+ envelope unwrap** · 68-file test sweep ·
scripts + probes.txt + smoke skill.

**2 — docs**
Reconcile `okf/backend/control-plane/api-v1.md`, `okf/backend/control-plane/events-metrics.md`
(drop `inspecto_legacy_api_requests_total`), the `REQUIREMENTS.md` API-5 row, `ADVANCED_GUIDE.md`,
`stakeholders/OPERATIONS_GUIDE.md`, `superpower/deployment-topology-plan.md`; `git mv`
`docs/ops/legacy-api-sunset-runbook.md` to `docs/archived-documents/`; update `docs/INDEX.md`;
then distil this plan into `api-v1.md` and archive it.

## Progress (2026-07-25, uncommitted — the build is RED, do not commit as-is)

**Done and compiling:**

- `ControlApi.normalizePath` — v1 strip kept; the `/api` and `/api/v1`-less branches replaced by a JSON 404.
- `ControlApi.routeDispatch` — the guard that actually retires the surface; machinery (`legacyRoutesOff`,
  `legacySunset`, `recordLegacyUsage`, `markDeprecated`, `sunsetHeader`) removed; `isInfraRoute` kept and
  re-documented as the allow-list. Class javadoc reconciled.
- `ControlPlaneClient` — `/api/v1` prefix **+ envelope unwrap** (`parse` lifts `data`), javadoc updated.
- `ControlApiLegacySunsetTest` → `git mv` to **`ControlApiVersionedSurfaceTest`**, rewritten as the new
  contract (5 tests: v1-only, no sunset signalling, infra probes unversioned, `/api/…` ≠ v1 gets JSON not
  the SPA shell, bare writes rejected).
- `ControlApiV1Test` — legacy-parity tests pruned/repurposed; uses `V1Body.envelope` where it asserts the
  envelope itself.
- **New** `V1Body` test helper (`of` = unwrap `data`, `envelope` = un-peeled).
- Mechanical sweep across the control tests: v1 prefix on every request helper, `readTree(x.body())` →
  `V1Body.of(x.body())`, redundant manual `.get("data")` removed.

**Remaining: 64 failures in `inspecto` only** (`Tests run: 533, Failures: 40, Errors: 24`). Every other
module is green. These are genuine per-test semantic migrations, not URL bugs — a scripted pass cannot
finish them safely:

| Category | Cause | Fix |
|---|---|---|
| Residual `data`-NPEs | bodies parsed by a route the regex didn't cover (body stored in a local first, `readTree` on a non-`.body()` expression, streamed/SSE bodies) | unwrap per site |
| `expected 200 but was 202` (5) | the v1 async contract answers **202 Accepted** where the unversioned surface answered 200 | assert 202 |
| `no matching run within 10s` | polling helpers keyed on the 200/body shape above | downstream of the 202 fix |
| "legacy stays a raw list" / parity assertions | tests that existed only to pin unversioned parity | delete |
| infra probes now enveloped | a probe literal picked up the helper's v1 prefix, so `/api/v1/health` returns an envelope where the test wants raw | send probes unprefixed |
| error-shape assertions (10 sites, 4 files) | `{"error":"msg"}` → `{"error":{errorCode,message,…}}` | read `error.message` |

**Lessons already paid for (do not repeat):** running the "prefix the helper" pass a second time
double-prefixed 61 files, and the collapse regex missed the `"/api/v1" + "/api/v1/"` (trailing-slash)
variant — always verify with `grep -l '/api/v1/api/v1'` after any scripted prefix pass.

## Verification

`mvn -o clean test -Pedition-enterprise` — the enterprise profile is required, not optional: a
default-profile run does not compile `inspecto-security`/`inspecto-policy` at all and still reports
BUILD SUCCESS (see `.claude/skills/build-verify/SKILL.md`). Plus `npm run test:ci` in `inspecto-ui/`
if any UI file is touched (none expected — the SPA is already fully v1).

## Out of scope (noted, not fixed)

`inspecto-ui/src/environments/environment.ts` has committed client secrets
(`appClientSecret`, `iamClientSecret`). Unrelated to D3 — belongs in BACKLOG §5.
