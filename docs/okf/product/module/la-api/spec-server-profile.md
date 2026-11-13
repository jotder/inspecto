# LA server profile — N7 / N8 / N9 (LA-PERF-SERVER-1, 2026-11-12)

Evidence for the LA performance plan §0b (archived: `docs/archived-documents/plans-archive/la-performance-plan.md`). Claims here are measurements, not code reading.

**Method.** The Preview bundle of 2026-11-09 booted over a copy of the `la-showcase` Space (one Investigation), demo
sign-in, port 8085, JDK 27 with JFR (`settings=profile`). A `curl` loop drove each request, `jcmd JFR.dump` after each
loop, then `jfr print` of `jdk.ExecutionSample` and `jdk.NativeMethodSample` aggregated by frame. After the fix only
the four changed classes were patched into a copy of the bundle's `inspecto-engine.jar`, so the before/after differ by
exactly this change.

## Stage costs before the fix
| Request | p50 | p90 | Where the time went (share of request samples) |
|---|---|---|---|
| `GET /inv/investigations` (150 calls) | 174 ms | 308 ms | 80 % in `EntityMasking.maskedColumns` → `EvidenceMasker.schemaClassification` → `PipelineSchemas.forStore` → `PipelineConfig.loadForValidation`: every pipeline file re-parsed per store per request |
| `GET /bootstrap` | ~20 ms | | reference |
| `POST /inv/projection` → 304 (150 calls, warm) | 38 ms | 373 ms | first calls ~200 ms; masking 41 %, `ComponentRegistry.scan` (via `DatasetRead.dataset` → `ComponentStore.get`) 50 %, audit event → `NotificationRules.persistedRules` → registry scan 20 % |
| `POST /inv/projection` → 200 (60 calls) | 209 ms | 378 ms | p99 2.09 s, max **15.99 s** (one call) |

## Suspects
| Suspect | Verdict |
|---|---|
| Pipeline-schema lineage re-parsed per request (masking) | **PROVEN, fixed** |
| Registry scan per `ComponentStore.get` / `list` (also the audit event's notification-rules read) | **PROVEN, fixed** |
| Rate limiter queueing | **REFUTED**: `RateLimiter.tryConsume` never blocks; past the burst it answers 429 (60 of 120 calls did) |
| Settings / limits read per request, capability / ABAC | not visible in the profile (no frame above 1 %) |
| Fingerprint listing in the projection cache key | `ProjectionCache.key` 33 % of 304 samples, but most of that is the masking it calls; remeasure after the fix before touching it |
| Audit append on the request thread | **PROVEN COST, NOT FIXED**: in the projection handler `ParquetEventStore.journalLocked` is 36 % and `flushLocked` 24 % of native samples, under the store's `synchronized` lock that every reader shares. The synchronous `force` per audit event is the audit trail's durability rule, so moving it is a design change (BACKLOG `LA-PERF-N9-1`) |
| Lazy first open of a DuckDB file, native-thread oversubscription (N10) | not isolated; the 15.99 s call was not caught by an execution sample |

## The fix
`StampedCache` (`com.gamma.pipeline`): a per-directory cache of a parsed result, keyed by every file's relative path, size
and modified time under that directory. **No time-to-live**: a changed, added or removed file changes the key, so a
reclassified column is seen on the next read (a masking decision is never served stale). A tree with any file modified
in the last 2 s is parsed fresh and not remembered (the same-size, same-tick rewrite gap, as git closes it). Used by
`ComponentRegistry.scan` (and `ComponentStore.write/delete` forget their root) and by `PipelineSchemas.producers` (not
cached when a pipeline read a schema file outside the config root). Masking and row scope still run before any
projection cache.

## After the fix (same server, same Space)
| Request | Before | After |
|---|---|---|
| `GET /inv/investigations` p50 / p90 | 174 / 308 ms | **32 / 41 ms** |
| `POST /inv/projection` 200, p50 | 209 ms | **52 ms** (max 0.71 s over 32 calls) |

Tests: `StampedCacheTest` (5), `EvidenceMaskerTest` (21, incl. `aReclassifiedSchemaIsSeenAtOnceEvenWhenTheTreeWasServedFromMemory`),
`ComponentStoreTest` (13), and the real-HTTP masking suites `ControlApiExplorationMaskingTest`, `ControlApiEntityListMaskedMatchTest`,
`ControlApiEntityProfileScaleTest`, `EngineDatasetProviderFingerprintTest`. The full gate was NOT run.

## N10 / N11 / N9 tail (LA-PERF-N10-1, LA-PERF-N11-1, LA-PERF-N9-1, 2026-11-12)

**Method.** The Preview demo install (`inspecto-preview/inspecto-la`, 9 Spaces including the three shared ones) booted
with JFR from launch (`INSPECTO_JAVA_OPTS=-XX:StartFlightRecording=...,settings=profile`). The "after" boots use a copy
of the same install with only `lib/inspecto-util.jar` replaced, over the same `spaces` folder. Probe: demo sign-in as
`admin`, then `POST /spaces/la-showcase/inv/projection` (`mule_transfers_dataset`, PAYER/PAYEE, limit 500-506 so the
projection cache cannot answer 304), one call every 1.1 s. Native threads were attributed by their Win32 start
address (`NtQueryInformationThread` class 9) mapped to loaded modules. The box was running parallel Maven builds, so
absolute timings are noisy. Compare runs only within this section.

### N10: who owns the native threads
| | Before | After `async_threads=1` |
|---|---|---|
| OS threads in the JVM (at the probe) | 5,332 (the native dump a minute earlier: 5,338, of which 5,251 start in `libduckdb_java` and 79 are Java threads) | **1,158** |
| Working set after 60 projections | 1,844 MB | **1,483 MB** (1,436-1,655 over later runs) |
| Projection p50 / p90 / max (60 calls) | 579 / 1,393 / 1,982 ms | **450 / 1,249 / 10,299** ms; then 392 / 833 / 1,300 and 455 / 1,318 / 1,659 |

Cause, measured in isolation with the bundled `duckdb_jdbc` 2.0.0.0 on 12 cores: each database instance starts
**59 threads**: 11 workers (`threads=12`) and **48 async I/O threads (`async_threads`, 4 × cores)**. The server held 89
open DuckDB connections (`GC.class_histogram`), and 89 × 59 = 5,251. `SET threads` saves only 8-11 per instance (8 at `threads=4`, 11 at `threads=1`). `SET
async_threads=1` leaves 12, and it works after open. A 20 M-row parquet group-by: best of 5 is 592-667 ms by default and
623-706 ms with `async_threads` 0 or 1, within noise. The fix is `DuckDbUtil.applyInstanceCaps`, called from
`applyCorrectnessSettings` and from `JdbcDrivers.connect` for `jdbc:duckdb:` URLs (the per-Space stores). Its default is
1 and `-Dprocessing.duckdb.async_threads` overrides it. `threads` and `memory_limit` were left unchanged; see the plan
§0b N10. Test: `DuckDbSettingsTest.everyOpenPathCapsTheAsyncThreadPool`.

### N11: boot
Three warm restarts: first application log line at 9-11 s after launch, `/health` at **37, 55 and 83 s**. The 3 min 35 s
(185 s before the first log line) of 2026-10-25 followed a fresh `/MIR` swap of every jar, so it is recorded as a
cold-only cost. It was not reproduced and is not fixed.

### N9 tail
220 projections in four runs. **One 10.3 s call**, in the run that began at 19:10:02, the first `*/10` job burst
after boot. In that burst `system.freshness-sweep` took **11,435 ms**; at later bursts it took 13-16 ms. A 40-call
run straddling a later burst (20:40, the same ~20 jobs including two `la_index_build`) peaked at 2.2 s. JFR shows no
Java wait over 2 s on a request (virtual) thread, and no GC or file event above 1 s, so the time was native or CPU.
Verdict: **the tail goes with the first job burst after boot.** N10 did not remove it, and its exact stage was not caught.

## As built when the LA performance plan closed (2026-11-13)

- **Projection cache + ETag/304 (P4-2):** `ProjectionCache` is looked up AFTER every gate and the masking; per-entry cap
  5,000 rows / 2 MB, 32 MB total. Key = Dataset fingerprint + masking mode, Entity Types, Space masking key and masking
  description + the whole viewer (Subject id, capabilities, data scopes, attributes, roles), so it is stricter than
  PERF-D2 asks: two Subjects never share an entry. Pinned by `ControlApiProjectionCacheKeyTest`. The Working Set relation
  cache holds the RAW log after `openForRead` and masks per request; the masking profile is in its ETag
  (`WorkingSetEtag`). Every error drops the ETag (`ControlApi.respond` strips it at status >= 400).
- **Compact edge wire (P10, PERF-D3):** `POST /inv/projection` and `/inv/projection/neighbors` answer
  `application/vnd.inspecto.la-edges.v2+json` = `{masking, columns, attrCols, rows, truncated, columnTypes, source?}`,
  rows as arrays (`InvRoutes.edgeArrays`, ends masked in place). A media type, not a `v2` path, so every path-keyed table
  (rate classes, capability manifest, OpenAPI, cache keys) stays put. `ApiContext.respondJson` keeps a `…+json` type only
  on success; errors are plain JSON with no ETag. Gotcha: an `Idempotency-Key` replay is sent as `application/json`. The
  SPA decodes in `edge-wire.ts` BEFORE its conditional-read cache. Measured: raw bytes −41 %, gzip only −1 to −7 %, no
  server or parse-time change (gzip had already removed the repetition). `columnTypes` is not cached (no measured cost).
- **N9 decision:** audit append stays per-event durable; the Parquet flush moved off the lock as journal segments
  ([`../event/architecture.md`](../event/architecture.md)). The first-job-burst-after-boot tail is accepted as a cold-start cost.
- History and every run: `archived-documents/plans-archive/la-performance-plan.md`. Browser side and the measured
  no-gos: [`../la-core/spec-performance.md`](../la-core/spec-performance.md).
