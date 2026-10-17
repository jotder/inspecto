# Operator mutation checklist

A green suite only means something if the guard CAN go red. The auto-mode classifier refuses an agent that
edits a guard or a safety test to prove it fails, so these one-line mutations are run **by the operator, by
hand**. Each item: break one line, run the named test or guard, see it go red for the stated reason, revert.
Queued in `SESSION_STATUS.local.md` (embed API, 2026-10-12; assurance store checks, 2026-10-04) and the
guard inventories approved in 2026-10.

## How to run safely (read first)

1. **Clean tree.** `git status -sb` shows nothing before you start; do not mutate in a checkout a peer is editing.
   Prefer a sibling worktree (for example `C:\sandbox\mut`, cut from `origin/master`), NOT under
   `.claude/worktrees/` (repo-scanning tests would read the main checkout) and NOT under `%TEMP%`.
2. **One mutation at a time.** Revert with `git checkout -- <file>` and confirm `git status -sb` is clean before
   the next. Never commit a mutation; never stage one.
3. **Java.** Maven needs JDK 27: `unset JAVA_HOME` (Git Bash) so `PATH`'s java 27 wins, or set it to the JDK 27 home.
   A "release version 27 not supported" failure means the wrong JDK, not a result.
4. **Command shape** (comma-separate classes, never `+`; `-am` so sibling jars are fresh):
   `unset JAVA_HOME; mvn -o test -pl <module> -am -Pedition-advanced -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
   Read **`Tests run:`**: `0` tests means the filter matched nothing (a false green), not a pass.
5. **Red for the RIGHT reason.** Read the failure text against the "Expect" column. A compile error or a different
   test failing is not the proof. In a fresh worktree copy the DuckDB extensions from the main checkout
   (`inspecto-deploy/duckdb-extensions/windows_amd64/`, gitignored) before a Maven run.
6. Tick the box only when the expected test failed AND the revert left the tree clean.

Line numbers are as of `origin/master` `22ccbe952` and drift: locate by the quoted text. Module paths in `-pl`:
`inspecto`, `la/inspecto-geo-link`, `la/inspecto-la-core`, `providers/inspecto-oidc`, `features/inspecto-ops`.
Edits in `platform/inspecto-access` or `spi/inspecto-auth-spi` are exercised through the module named in Run (`-am`).

## A. Investigation embed API (LA-EMBED-VIEW-1)

| Done | ID | ~Min | Guard protects | Mutation (file) | Run (`-pl ... -am`) | Expect |
|---|---|---|---|---|---|---|
| [ ] | EMB-1 | 6 | Only a GET reaches the public embed path | In `platform/inspecto-access/.../access/CapabilityManifest.java`, in `EXEMPTIONS` (~448) add `new Exemption("POST","/public/investigations/([^/]+)","self-verifying-public","x"),`; AND in `la/inspecto-la-api/.../api/PublicInvestigationRoutes.java` after `api.get("/public/investigations/([^/]+)"...` (~64) add an `api.post(...)` on that path answering 200 | `la/inspecto-geo-link -Dtest=ControlApiInvestigationEmbedTest` | `theTokenOpensNothingElseAndOnlyAGetReachesThePublicPath`: expected 405, was 200 on the POST |
| [ ] | EMB-2 | 5 | The public path is exempt from platform auth | `PublicInvestigationRoutes.java` ~63: delete `PublicPaths.registerSelfVerifying(EmbedGrants.PREFIX);` | same | most `ControlApiInvestigationEmbedTest` tests red; the public GET answers 401 |
| [ ] | EMB-3 | 6 | Every mutating route is classified (fail closed) | `CapabilityManifest.java` ~61: delete the `new Entry("POST", "/inv/investigations/([^/]+)/embeds", Roles.CAN_SHARE_INVESTIGATION),` row | `inspecto -Dtest=CapabilityManifestTest` | red naming the undeclared `POST /inv/investigations/{id}/embeds`; a host boot refuses too |
| [ ] | EMB-4 | 4 | `embed` is an authorable access-policy action | `spi/inspecto-auth-spi/.../auth/AccessPolicies.java` line 33: drop `, "embed"` from `ACTIONS` | `inspecto -Dtest=ControlApiAccessPoliciesTest` | `embedIsAnAuthorableActionAndAnUnknownVerbIsStillRefused` red |
| [ ] | EMB-5 | 4 | The anonymous embed view is throttled (EXPENSIVE rate class) | `inspecto/.../control/LinkAnalysisRateClasses.java` ~39: delete `{"GET", "/public/investigations/([^/]+)"},` | `inspecto -Dtest=LinkAnalysisRateClassCoverageTest` | red: the route is in no rate class |
| [ ] | EMB-6 | 3 | Admin holds the share-an-Investigation capability | `platform/inspecto-access/.../access/Roles.java` ~247 (the `"admin"` Def): delete `, CAN_SHARE_INVESTIGATION` | `providers/inspecto-oidc -Dtest=OidcAuthenticatorTest` | `adminRoleGrantsOnboardConnectionsAndNotWorkbench` red (set equality, `canShareInvestigation` missing) |

## B. Store and concurrency guards (assurance, 2026-10-04)

| Done | ID | ~Min | Guard protects | Mutation (file) | Run (`-pl ... -am`) | Expect |
|---|---|---|---|---|---|---|
| [ ] | STO-1 | 6 | Postgres Space isolation (schema per Space); needs `INSPECTO_TEST_PG_URL` (env var only, never in a file) | `inspecto/.../service/OperationalDb.java`, the Space-scoped `urlFor` overload (~453): return the unscoped URL, dropping `currentSchema=` | `inspecto -Dtest=PostgresSchemaPerSpaceTest` | the isolation probe red; **without the env var the test SKIPS, which is not red** |
| [ ] | STO-2 | 5 | Optimistic append: a stale writer writes nothing | `la/inspecto-la-core/.../core/FsInvestigationStore.java` ~328: `if (actual != expectedVersion)` becomes `if (false && actual != expectedVersion)` | `la/inspecto-la-core -Dtest=FsInvestigationStoreContractTest` | `aStaleVersionWritesNothing`, `racingOneStepExactlyOneWriterWins` red |
| [ ] | STO-3 | 6 | Promote / replace Draft refuses when either log moved | same file, `promoteDraft` (~619) and `replaceDraft` (~692): drop the version/hash comparison | same | `promoteIsRefusedAndWritesNothingWhenEitherLogMovedUnderIt`, `twoDraftsPromotedOntoOneMainExactlyOneWins` red |
| [ ] | STO-4 | 8 | Audit chain: a second writer on a shared store is refused | `platform/inspecto-event/.../event/DbEventStore.java` `claimChainWriter` ~148: `if (!rs.next() \|\| !rs.getBoolean(1))` becomes `if (false)`; then separately ~133 `if (lockConn == null \|\| ...)` becomes `if (false)` | `inspecto -Dtest=DbEventStoreChainWriterTest` | first: `aSecondWriterOnASharedPostgresStoreIsRefusedAndTheFirstKeepsWorking`, `theSecondPodsAuditRowsAreNeverLinkedSoTheChainDoesNotFork`, `startupRefusesTheSecondWriter...` red; second: `aPostgresStoreWithoutItsDedicatedLockConnectionRefusesRatherThanLinksUnguarded` red (Postgres-gated) |
| [ ] | STO-5 | 8 | Anchor reconcile advances the record and detects tampering | `inspecto/.../control/AuditAnchors.java`, one at a time: (a) delete `recordStarted(root, last, false);` (~279); (b) `file.firstProblem() != null` becomes `false` (~276); (c) `if (last.mac().equals(s.latestMac())) return false;` becomes `if (false) return false;` (~278) | `inspecto -Dtest=AuditAnchorsReconcileTest` | (a) `reconcileAdvancesTheRecordSoTheTruncatedLastAnchorIsNowDetected`; (b) the tampered / removed mid-file tests; (c) `reconcileIsIdempotentAndANoOpWhenTheRecordIsCurrent` |
| [ ] | STO-6 | 4 | ObjectStore optimistic version column | `features/inspecto-ops/.../ops/DbObjectStore.java` ~121: remove `AND version=?` from the UPDATE (and its bind) | `features/inspecto-ops -Dtest=ObjectStoreContractTest,DbObjectStoreTest` | version-race tests red (a lane saw 9 of 12 go red) |

## C. Guard inventories (each exemption needed operator approval; prove the guard still bites)

Each inventory is a hand-written allow-list. **Deleting one entry must turn the test red, naming that entry.**
If the test stays green, the inventory is dead weight.

| Done | ID | ~Min | Guard protects | Mutation (file) | Run (`-pl inspecto -am`) | Expect |
|---|---|---|---|---|---|---|
| [ ] | INV-1 | 5 | Every Job-writing route is a Job writer or explained | `inspecto/src/test/.../control/JobWritersTest.java`: delete one entry from `NOT_A_JOB_WRITER` (~29) | `-Dtest=JobWritersTest` | red naming that route |
| [ ] | INV-2 | 5 | A new fixed loader file is import-reserved or allowed with a reason | `inspecto/src/test/.../service/ImportLoaderInventoryTest.java`: remove one `ALLOWED.put(...)` (~62-66) | `-Dtest=ImportLoaderInventoryTest` | red naming the loader-read file as neither reserved nor allowed |
| [ ] | INV-3 | 5 | Every config-writing site uses the maker-checker funnel or is listed | `inspecto/src/test/.../control/ConfigWriteFunnelTest.java`: delete one entry from `WRITERS` (~241) | `-Dtest=ConfigWriteFunnelTest` | red: the `open` set is non-empty, naming the site |
| [ ] | INV-4 | 5 | Every Decision Rule writer is guarded or explained | `inspecto/src/test/.../control/DecisionRuleWritersTest.java`: delete one `NOT_A_RULE_WRITER` entry (~39) or one `GUARDED_BY` row (~27) | `-Dtest=DecisionRuleWritersTest` | red naming the writer |
| [ ] | INV-5 | 5 | An import cannot overwrite a reserved path (approvals, safety policy, ...) | `inspecto/src/main/.../service/ReservedConfigPaths.java`: remove `"safety-policy.toon"` from `FILES` (~44) | `-Dtest=ImportLoaderInventoryTest,ConfigWriteFunnelTest,ControlApiPublicationTest` | at least one red naming the path; see "could not verify" |
| [ ] | INV-6 | 5 | Every mutating route has a capability or a written exemption | `platform/inspecto-access/.../CapabilityManifest.java`: delete one `new Exemption(...)` from `EXEMPTIONS` (~448), for example `/requirements` | `-Dtest=CapabilityManifestTest` | red naming the now-undeclared route |

## D. Documentation guards (no Maven; run from the repo root, revert with `git checkout -- <file>`)

| Done | ID | ~Min | Guard protects | Mutation | Run | Expect |
|---|---|---|---|---|---|---|
| [ ] | DOC-1 | 2 | A stated count is derived from its contract | `docs/okf/backend/engine/catalog-vs-executors.md` line 12: edit the number 119 that sits directly before its `count:processors` marker to 118 | `node tools/check-doc-counts.mjs` | exit 1; marker `processors` states 118, the contract says 119 |
| [ ] | DOC-2 | 2 | Route-gating evidence table matches the code | `compliance/evidence/route-gating.md`: delete one table row between `<!--route-gating:begin-->` and `<!--route-gating:end-->` | `node tools/route-gating-report.mjs --check` | exit 1, table stale (regenerating is the fix, not part of the mutation) |
| [ ] | DOC-3 | 2 | Doc links resolve | in a tracked doc add a markdown link whose target file does not exist (an `[x]` label followed by a parenthesised missing path) | `node tools/check-doc-links.mjs` | exit 1 naming the broken link |
| [ ] | DOC-4 | 2 | Canonical vocabulary | add the heading `## Sources` to a `docs/ops/*.md` (the known trip) | `node tools/check-vocabulary.mjs` | exit 1 naming the file and the banned term |
| [ ] | DOC-5 | 2 | No secrets in the tree | add an obvious AWS-style key literal to a scratch doc (do not commit) | `node tools/check-secrets.mjs` | exit 1 if the pattern matches; if it passes, the pattern is the finding |
| [ ] | DOC-6 | 2 | A cited path exists | in a tracked doc cite a path under a REAL first segment that does not exist, for example `inspecto/src/main/java/NoSuch.java:1` | `node tools/check-doc-citations.mjs` | exit 1 naming the dead citation (a path with an unreal first segment never trips it, so it is a useless probe) |
| [ ] | DOC-7 | 2 | Authgate coverage counts only a BARE route literal | in a `*Routes.java` with a gated route, change its path literal to include `?x=` | `node tools/check-authgate-coverage.mjs` | exit 1 (this guard is NOT in `.githooks/pre-push`; run it by hand after adding gated routes) |

## Items dropped as stale

None. Every queued target in `SESSION_STATUS.local.md` still exists at `22ccbe952`; no test or guard name had drifted.
A stale-looking line in the 2026-10-04 list, "ObjectStore version guard (remove `WHERE version = ?`)", had no literal
`WHERE version = ?`: the real text is `WHERE id=? AND version=?` (STO-6).

## Items I could not verify

- **INV-5:** `ReservedConfigPaths` is referenced by four test classes; I did not confirm which one goes red for a
  removed `safety-policy.toon`. If none of the three named does, record it: that is a coverage finding, not a pass.
- **STO-1 and the second half of STO-4** need a live PostgreSQL (`INSPECTO_TEST_PG_URL`); without one they skip.
- **STO-3:** I did not read the exact comparison lines inside `promoteDraft` / `replaceDraft`; weaken the version/hash compare.
- **DOC-5 / DOC-6:** I did not read the pattern rules of `check-secrets.mjs` / `check-doc-citations.mjs`; the expected
  shape is inferred from CLAUDE.md and the memory notes.
- **EMB-3:** whether a host-boot test beyond `CapabilityManifestTest` fails at startup was not run.
- **EMB-1:** the added POST handler must compile; mirror the signature of the neighbouring `api.get`.
- **INV-1..4:** which entry to delete is your choice; prefer one whose writer class the guard's scan visibly finds.
