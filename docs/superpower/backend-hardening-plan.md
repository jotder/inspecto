# Backend Hardening — Implementation Plan / Spec

**Status:** PROPOSED (not started) · **Origin:** backend analysis review 2026-08-24, corrected per §0
**Constraint:** every change must be non-breaking — no route, payload, config key, or log-file
consumer may observe a behavioral change except where a section explicitly promises one.
**Verification backbone:** root `mvn test` (reactor was green 3547/0/0/5 at plan time);
`ApiContractTest` guards the `/api/v1` surface; UI clients already send `?limit=`/`?offset=`
(`reports.service.ts:16`, `views.service.ts:20`) so paging params must stay compatible.

## 0. Corrections applied to the original review

Four findings were withdrawn or restated after source verification; they are **not** work items:

1. **Rate limiting (#2) — WITHDRAWN.** Throttling belongs at the WSO2 gateway edge on the higher
   edition. Only residual advice is network-level (keep `/metrics`, `/bootstrap`, `/health` off the
   raw public internet) — deployment config, not app code. No item below touches it.
2. **"Metrics not Prometheus-scrapeable" (#3) — WRONG.** `inspecto-event`'s
   `com.gamma.metrics.MetricRegistry` already emits Prometheus text exposition (`scrape()` served at
   `GET /metrics`, ControlApi.java:383-384). Nothing to do.
3. **"`catch (Exception ignored)` swallows ingest errors" (#4) — DOWNGRADED to logging polish.**
   The swallow sites in `BatchIngestStrategy.dropTable/dropView` are documented best-effort DDL
   cleanup; real ingest failures already land in the outcome as `status="FAILED"`. Item 2 adds warn
   logs only; semantics untouched.
4. **"No OpenAPI contract" (#1) — RESTATED.** `docs/api/openapi-v1.json` exists and
   `ApiContractTest` enforces it against live routes + examples + ErrorCodes. The gap is narrower:
   the contract is not *served at runtime* and there is no regeneration path. Item 3 addresses that.

---

## Item 1 — SpaceMigrator: println → slf4j (safest)

**Goal:** stop writing progress/errors to raw stdout in a class reachable from server code.

**Exact changes**
- `inspecto/src/main/java/com/gamma/service/SpaceMigrator.java`
  - l.92, l.101: `System.out.println(...)` → logger.info/warn.
  - Add `private static final Logger log = LoggerFactory.getLogger(SpaceMigrator.class);`
    (slf4j-api is already on inspecto's compile classpath — pom.xml l.112-115).
- The standalone `public static void main` (l.151) keeps working unchanged: without a logback
  config slf4j falls back to its NOP/simple provider, which prints to stdout just like today.

**Why it cannot break:** output channel only; no signature, control flow, or return values change.
Only external reference to the class is a javadoc mention (`SpaceLayoutContract` l.21).

**Verify:** `mvn -q -pl inspecto -am test -Dtest='*Space*'` then full `-pl inspecto` suite.

**Rollback:** single-file revert.

---

## Item 2 — Best-effort drop failures get a warn line

**Goal:** operators can see *why* a stale table/view survived, without changing swallow semantics.

**Exact changes**
- `inspecto-engine/src/main/java/com/gamma/inspector/inspector/BatchIngestStrategy.java`
  - `dropTable` (l.54 area) and `dropView` (l.61 area): replace `catch (Exception ignored) { }`
    with `catch (Exception e) { log.warn("best-effort DROP {} failed: {}", kind, name, e); }`
  - Update both methods' javadoc from "swallowing any error" to "logging any error at WARN".
- inspecto-engine already has jtoon + slf4j transitively via inspecto-util (pom l.86/l.91-95); if
  the compiler complains, add slf4j-api to inspecto-engine/pom.xml explicitly.

**Why it cannot break:** the catch still swallows — execution continues identically. The only new
observable is a log line. Interface contract ("ingest never throws", l.41-46) untouched.

**Verify:** `mvn -q -pl inspecto-engine -am test`; grep test output for no new failures.

**Rollback:** single-file revert.

---

## Item 3 — printStackTrace() → slf4j in shared modules

**Goal:** exception diagnostics go through the logging pipeline instead of bare stderr.

**Scope (11 files, all in the separate asn-parser reactor unless noted):**
- `asn-parser/src/main/java/com/gamma/skybase/decoder/asn2/{Asn1Parser,ASN1Reader,BERTags,CSVConf,TagHelper,TagReader}.java`,
  `.../asn2/reader/BerDecoder.java`, `.../asn2/utils/DateTimeUtils.java`,
  `.../asn3/ASNStreamReader.java`, `.../transformer2/Transformer.java` (+ TransformUtils)
- plus `inspecto-util`: FileMoverByDate, IntegratedProcessor, TarExtractor, TarInboxPreparer;
  and `inspecto-engine`: MainApp (CLI usage paths may stay on stdout — see note).

**Design decision required before editing asn-parser:** asn-core's pom declares ONLY junit-jupiter
(verified). Two options:
- **Option A (chosen): add `org.slf4j:slf4j-api` to `asn-parser/asn-decoders/asn-core/pom.xml`.**
  Pure additive dependency; parent manages versions? — confirm, else pin same version property as
  inspecto-util uses. asn-decoders targets release 25 (its own reactor) — unaffected by our release
  24 core; we are not changing bytecode, just source.
- Option B: mechanical swap to `Exception.printStackTrace(PrintStream)` wrapper — rejected, it is
  the same anti-pattern with extra steps.

**MainApp caveat:** `MainApp.main` legitimately prints CLI usage/suite banners to stdout. Convert
only the *error-path* `printStackTrace` calls (l. around catch blocks), leave usage text alone.

**Why it cannot break:** stderr text becomes structured log lines. No API change. The golden
corpus tests `assumeTrue`-skip without data, so log-format assertions cannot exist there (verify
with `grep -rn "printStackTrace" asn-parser/src/test || true` → expected empty).

**Verify:** `mvn -q -pl asn-parser/asn-decoders -am test && mvn -q -pl inspecto-util -am test &&
mvn -q -pl inspecto-engine -am test`.

**Rollback:** per-module reverts; do asn-parser last so a failure there doesn't block items 1-2.

---

## Item 4 — Serve the OpenAPI contract at runtime (read-only)

**Goal:** `GET /api/v1/openapi.json` returns the exact artifact CI tests against.

**Exact changes**
- `inspecto/src/main/java/com/gamma/control/ControlApi.java`
  - Load `docs/api/openapi-v1.json` once at startup. Because the file lives outside the JAR,
    resolve like ApiContractTest does (walk up from `Path.of("")`) with fallbacks
    (`INSPECTO_DOCS_DIR` env → repo-root walk), else serve 404 with a log line — never crash.
  - Register `api.get("/api/v1/openapi.json", ...)` next to the existing infra routes (~l.383):
    `respondText(e, json)` with `Content-Type: application/json`. Add the path to `PUBLIC_PATHS`
    (l.195-197) **only if** we want it tokenless — recommendation: keep it authenticated (NOT in
    PUBLIC_PATHS); tooling exchanges credentials anyway.
- RouteModule docs seam stays authoritative long-term: each module "owns its own routes + docs"
  (ControlApi l.386-387). Serving the static artifact is phase 1; a later item could generate the
  doc from RouteModules — out of scope here.

**Compatibility guarantees**
- No existing route changes; new path only. `ApiContractTest` gains one assertion: served body
  byte-equals the file (skipped when the docs dir isn't found, mirroring the corpus-skip pattern).
- Envelope shaping: respondText bypasses `Envelope.shape`, so no `{data:...}` wrapper surprises.

**Verify:** `mvn -q -pl inspecto -am test -Dtest=ApiContractTest`; manual: start jar, `curl -s
localhost:8080/api/v1/openapi.json | diff - docs/api/openapi-v1.json`.

**Rollback:** remove the registration block; nothing else references it.

---

## Item 5 — Paging adoption policy (no code now)

**Decision recorded so future PRs don't relitigate:**
- New list endpoints MUST use `Cursor.encode/decode` keyset paging for unbounded tables
  (pattern: EventRoutes l.56/66, JobRoutes, ObjectRoutes).
- Bounded/in-memory lists MAY use `ApiContext.paged` (limit/offset slice, l.333-340).
- Existing endpoints keep current behavior; RunRoutes' `paged()` (l.36) migrates only if a caller
  reports truncation pain — BACKLOG.md l.228 already tracks this as demand-driven.
- The `/runs/runs/{id}` Location-header quirk stays until v2 of the API; renaming would break
  every stored client link (documented workaround comment RunRoutes l.43-45).

**Deliverable:** one paragraph added to `docs/okf/backend/control-plane/api-v1.md`.

---

## Item 6 — Optional: Dockerfile wrapping serve.sh

**Only if containerized deployment is wanted now** (BACKLOG tracks jlink `-NoRuntime` flavor
separately; don't conflate).

- `inspecto-deploy/Dockerfile`: eclipse-temurin:24-jre base, COPY fat jar, `ENV PORT=8080`,
  ENTRYPOINT `./serve.sh`, HEALTHCHECK curl `/health` (PUBLIC_PATHS l.195 → tokenless, correct for
  healthcheck).
- Uses existing seams only: `-Dcontrol.port`, `-Dspaces.root`, PORT default 8080 (serve.sh).

**Verify:** `docker build && docker run -P` + `curl /health`, `curl /ready`.

**Rollback:** delete two files (Dockerfile, optional compose).

---

## Execution order & global gate

1 → 2 → 3(inspecto-util/engine first, asn-parser last) → 4 → 5(doc-only) → 6(optional).

After each item: scoped `mvn test` above. Before closing the whole plan: root `mvn test` green +
`ApiContractTest` + one boot smoke (`serve.sh` + `curl /health`, `/metrics`, `/bootstrap`).
UI needs no regression pass — none of these touch request/response shapes.

Each shipped item distills durable facts into `docs/okf/backend/*` and this plan moves to
`docs/archived-documents/plans-archive/` per the documentation lifecycle.
