# LA server profile — N7 / N8 / N9 (LA-PERF-SERVER-1, 2026-11-12)

Evidence for `docs/superpower/la-performance-plan.md` §0b. Claims here are measurements, not code reading.

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
