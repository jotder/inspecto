# Backlog — every OPEN item, one page

**Updated:** 2026-09-16 (five-task parallel shift — `TYPEFLOW-DATASET-COLUMNS-1` **CLOSED** (⛔ steps 3+4 had ALREADY shipped and this page still called them unbuilt; the real gap was Q4's **declared-but-unenforced** `coarseTypes` key), `DUCKLE-C3-DEAD-PROPERTY-1` **~2/3 BUILT** and BREAKING (`version:`/`source:` now 422), the SOC 2 window recorded **NOT STARTED** so it is falsifiable, and three design passes landed — each of which **refuted its own row's premise**. Two new rows filed: `COLUMN-TYPE-SECOND-INTERPRETER-1`, `ACQUIRE-LEDGER-DUPLICATE-RESOLUTION-1`. ⚠ **Four drifted citations corrected, one of them a correction that was itself wrong** — the acquisition ledger IS an `OperationalDb.Family` member. 🔴 **Standing lesson: ground a row against the CODE before starting it, never against its board text — four of five briefings drawn from this page were stale.** Baseline at `e6b32e25`: 4228/0/0/17 over the 14 non-asn modules, module and class sums cross-checked. ✅ **Census RECOUNTED 2026-09-16 (fifth pass): 50 rows — 1 × P1 · 35 × P2 · 14 × P3.** 🔴 The header had said 71 / 53 / 17 for two days, and the queued-work paragraph underneath it carried three further numbers of its own; both are corrected and the drift named in place.) (earlier) — (✅ **the ELT §6 step-2 PARITY GATE IS MET** — first time ever: whole suite under `-Dingest.lane=graph` 4603/0/0/28, zero refusals) — both graph-lane rows shipped, and step 1's converter `inspecto migrate-configs` is built and driven. ⚠ Step 1 is not dischargeable for a space owning an enrich config: `MIGRATE-ENRICH-1` + `MIGRATE-MATERIALIZE-1` filed, both waiting on ONE §1 call, because `GLOSSARY.md` records BOTH sides of it. (earlier the same day) — `GRAPH-LANE-MULTISCHEMA-1` was filed, designed, decided by the operator and built in one sitting: `-Dingest.lane=auto` now diverts multi-schema segment writes to the graph lane, and 12 of the 13 parity-gate refusals are green. ⚠ Both readings of that gap — the row's and the design's first — were wrong; what was actually broken is that the admission asked its question at pipeline granularity while the caller works per segment. the two gaps the §6 step-2 parity gate exposed now carry rows: `GRAPH-LANE-MULTISCHEMA-1` and `GRAPH-LANE-RULE-ROUTED-1` (§3, both **design-first**, both blocking §2 Row 15's deletion half). ⚠ The gate was **re-run** before filing rather than filed off the previous shift's note — the 13 refusals and both messages reproduce, but the multi-schema one's stated cause (an arity mismatch) is **not the whole gap**: the seeding contract is violated structurally, so widening the count would admit an unsafe write. (M tier drained) — `HOME-TILES-1` CLOSED (two cheap count routes + two tiles, hidden on failure), `DUCKLE-C2-RUN-DIFF-1` CLOSED (`GET /jobs/{name}/runs/{a}/diff/{b}`, six kinds, absent≠zero, three kinds honestly "not compared"); `AGT-5` RE-GATED external (`PlatformBuilder` has no `dryRunProvider` — upstream ask recorded); the §6 step-2 **parity gate was RUN for the first time and is NOT met** (13 engine tests, two graph-lane gaps named); pre-materialise cap → §1 owed input. (earlier the same day) — five M rows CLOSED and as-built homed: `RECON-CARDINALITY-2` (rows behind a break, on demand), `STALE-TILES-PRECISION-1` (store granularity), `INCIDENT-KPI-MTTD-1` (narrower anchor, no placeholder), `DUCKLE-C4-PARAM-PROVENANCE-1` (a `params` run artifact), `AGT-ARTIFACT-1` (four draft skills answer with a `draft` artifact; `pipeline_author` stays out). (dependency pass, 2026-09-15) — a §0 dependency map over every P-ranked row (37 unblocked / 8 operator / 7 design-first / 12 external / 2 row-chained); the S-size unblocked rows taken: `DEMO-SPACE-PERSONAL-UNBOOTABLE-1`, `POLL-STATE-BLIND-TO-CONNECTOR-FAILURE-1`, `SCHEMA-FORM-EMPTY-LIST-1` SHIPPED, `SIGNIN-PREVIEW-1` closed (page seen at two widths via `run-backend.ps1 -AuthMode oidc`), `HOME-VERSION-1` SHIPPED (manifest → `/bootstrap` → sign-in footer), API v1 jlink re-verified; two shipped-but-unstruck §3 rows swept. (§4 pass, earlier) — six §4 rows CLOSED (`ENRICH-SILENT-FULL-RECOMPUTE-1` fixed, `NODE-TYPE-MIRRORS-1` three mirrors derived, `OPENAPI-GEN-1` skeleton generated + enforced, `COLLECTOR-DATASET-UNPROVEN-1` proven live, `SPEC-COUNTS-1` last count derived), `SPEC-DEADSEAM-1` → §1; two rows FILED from the live run. (§5 sweep, earlier) — seven more shipped §5 rows swept (`AIRGAP-CROSSPLAT-DEADWEIGHT-1`, `DATASET-SELF-TRIGGER-1`, `LEDGER-PRUNE-EATS-RESUME-STATE-1`, `PRUNE-PREVIEW-DRIFT-1`, `DUCKLE-C9`, `AIRGAP-S3-EXTENSIONS-1`, `SPEC-ORPHANPAGE-1`; each as-built homed in OKF first), the P1's steps 2b+2c shipped, §1 gained two owed inputs. Earlier the same day — all 20 closed rows swept off the page (~470 lines, each as-built verified homed);
every P2 grounded against code (5 closed, 2 shrank); `AUDIT-REFUSAL-GAP-1` and
`DATASET-PUBLISH-ON-FAILURE-1` BUILT; `CONSIGNMENT-OUTPUTS-NULLRUN-1` (P1),
`ENRICH-SILENT-FULL-RECOMPUTE-1`, `SPEC-JAVALANE-RATIO-1` and `COLLECTOR-DATASET-UNPROVEN-1` filed.
**2026-09-07** — §4–§7 re-grounded and drained (see each section's note).
**2026-09-06 — consolidated.** The previous page (505 KB, 3,288 lines, roughly half of its rows
already closed) is frozen as
[`archived-documents/backlog-snapshot-2026-09-06.md`](archived-documents/backlog-snapshot-2026-09-06.md);
every closed row's as-built narrative, commit SHAs and refuted premises live there and in git history.
That rewrite also folded in the open items that had been living outside this page: the gate register's
pending decisions and "simply unbuilt" list (§3/§4, 2026-08-29 — that register was itself archived
2026-09-07, its retirement trigger having fired), the remainders of every plan still in
`docs/superpower/`, and the last handoff's next steps.

> **Where the board stands — recounted 2026-09-16 (FIFTH pass, re-derived from the rows themselves) and now PINNED by `tools/check-doc-counts.mjs`.** Every number in this block and in the "queued work" paragraph below carries a `<!--count:backlog-*-->` marker; the guard derives each one from the rows themselves (§0's patterns, over the `## 3.`–`## 6.` slice) and FAILS the build if a stated figure drifts from them again — including when only ONE of the two sites is updated, which is how this very commit found the header and the paragraph below disagreeing.
> **60<!--count:backlog-rows--> rows: 0<!--count:backlog-p1--> × P1 · 29<!--count:backlog-p2--> × P2 · 31<!--count:backlog-p3--> × P3** — ⬇ **61 → 60 on 2026-09-24**: P3 `BER-VALID-BUT-HUGE-ALLOCATION-1` shipped (BER single-value cap) — ⬇ **65 → 61 on 2026-09-24 (pipelines decisions batch)**: five rows struck (4 × P2 — `RATE-LIMIT-OVERSIZE-HANGS-1`, `PREVIEW-REFERENCE-PATH-UNJAILED-1`, `GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1` (all three on the operator's 2026-09-24 decisions), `EDITION-GATED-TESTS-IN-WRONG-HOME-1` (already done); 1 × P3 — `HEAD-RESPONSE-STREAM-CLOSED-1`), one filed (P3 `DEMO-DATASET-FEED-UNSAFE-ID-1`), `DUCKLE-C7-AFFECTED-CONTRACTS-1` half shipped; P2 33 → 29. `KAFKA-OFFSET-REKEY-1` (duplicate rows from Kafka / DB-export Collectors) was found, reproduced and fixed in the same batch, so it never became a row. ⬇ **71 → 65 on 2026-09-23 (pipelines buildable batch)**: six rows struck (1 × P2 — `POST-ACTION-MOVE-RECOLLECTS-ARCHIVE-1`; 5 × P3 — `COLLECTOR-ON-CHANGE-SKIP-IS-REPROCESS-1`, `PARKED-BRANCH-LEAK-ON-FAILED-BATCH-1`, `VALIDATE-CONFIGPATH-SKIPS-SAVEGATE-1`, `CONNECTOR-TESTS-HIDE-DROPPED-ROWS-1`, `DOC-DRIFT-COLLECTOR-ALERT-1`), `PROCESSOR-RELEASE-READINESS-1` narrowed (G4 closed); P2 34 → 33, P3 37 → 32. ⬆ **65 → 71 on 2026-09-23 (pipeline-building drain, batch 5)**: three rows struck (3 × P3 — `CHUNKED-UNREADABLE-FAILS-BATCH-1`, `VALIDATE-PREPARE-WRITES-STATUS-DIR-1`, `CONFIGCODEC-CALLERS-NO-FILE-NAME-1`), `PROCESSOR-RELEASE-READINESS-1` narrowed (G1, G3, G5, G6, G7, G8, G11 closed), nine filed (3 × P2 — `GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1`, `RATE-LIMIT-OVERSIZE-HANGS-1`, `POST-ACTION-MOVE-RECOLLECTS-ARCHIVE-1`; 6 × P3 — `ENRICHMENT-MIDWALK-LANES-DISAGREE-1`, `COLLECTOR-ON-CHANGE-SKIP-IS-REPROCESS-1`, `DOC-DRIFT-COLLECTOR-ALERT-1`, `PARKED-BRANCH-LEAK-ON-FAILED-BATCH-1`, `VALIDATE-CONFIGPATH-SKIPS-SAVEGATE-1`, `CONNECTOR-TESTS-HIDE-DROPPED-ROWS-1` — see *Filed from the pipeline-building drain* in §4); P2 31 → 34, P3 34 → 37. ⬇ **66 → 65 on 2026-09-23 (independent-items drain, batch 4)**: seven rows struck (2 × P2 — `SINGLE-MEMBER-TRANSFORM-FAILURE-QUARANTINES-1`, `VALIDATE-CONFIGPATH-UNJAILED-1`; 5 × P3 — `WEBHOOK-RECIPE-PALETTE-1` (refuted), `DATE-PARTITION-ON-TEXT-SHIPPED-1`, `CONFIGCODEC-LENIENT-IS-STRICT-1`, `DATA-PATH-RESIDUALS-1`, `BUNDLE-ASN1-GRAMMAR-FILE-1`), six filed (2 × P2 — `PREVIEW-REFERENCE-PATH-UNJAILED-1`, `PROCESSOR-RELEASE-READINESS-1`; 4 × P3 — `VALIDATE-PREPARE-WRITES-STATUS-DIR-1`, `STEP-TYPES-DEAD-CLIENT-MIRRORS-1`, `CHUNKED-UNREADABLE-FAILS-BATCH-1`, `CONFIGCODEC-CALLERS-NO-FILE-NAME-1` — see *Filed from the pipeline-building drain* in §4); P2 31 → 31, P3 35 → 34. ⬇ **67 → 66 on 2026-09-23**: `LA-CASE-CREATE-IN-PLACE-1` CLOSED (P3 36 → 35). ↘ **P2 32 → 31, P3 35 → 36 on 2026-09-23: the Step Processor catalog row demoted to P3 — operator put new Step Processors ON HOLD until the existing ones are releasable.** ⬇ **70 → 67 later on 2026-09-23 (operator decisions, all built)**: six rows struck (3 × P2 — `TESTRUN-SEED-IS-MAPPED-OUTPUT-1`, `TESTRUN-FAILED-BATCH-REPORTED-EMPTY-1`, `DATA-DIRS-RESOLVE-AGAINST-CWD-1`; 3 × P3 — `DRYRUN-INVISIBLE-ON-FLAT-LANE-1`, `PIPELINE-LOAD-FAILURE-INVISIBLE-1`, `SINK-DUCKLAKE-SHARED-LAKE-DUPLICATES-1`), three filed (`SINGLE-MEMBER-TRANSFORM-FAILURE-QUARANTINES-1` P2, `DATA-PATH-RESIDUALS-1` + `FLAT-DRYRUN-COUNTS-ZERO-1` P3 — see *Filed from the pipeline-building drain* in §4). ⬆ **69 → 70 the same day**: `WEBHOOK-RECIPE-PALETTE-1` (P3) filed when `sink.api.webhook` shipped. ⬇ **71 → 69 later on 2026-09-23 (the pipeline-building drain)**: nine rows struck (2 × P2 — `SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`, `JOB-PATH-DEMO-CONFIG-REPOINT-1`; 7 × P3 — `WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1`, `EXCEL-DATES-ARRIVE-AS-SERIALS-1`, `TOON-UNQUOTED-DECIMAL-SKIPS-PIPELINE-1`, `DUCKDB-PREAMBLE-OTHER-422S-1`, `PARTITION-KEY-VALIDATION-GAPS-1`, `PIPELINE-RUN-HISTORY-OVERLAY-1`, `WORKBENCH-RESPONSIVE-FLOOR-1`), seven filed (2 × P2, 5 × P3 — see *Filed from the pipeline-building drain* in §4), plus `JOB-PATH-SINGLE-TENANT-GATE-BASE-1` filed and struck the same day and `PIPELINE-CONFIG-HISTORY-AND-LAYOUT-1` renamed `PIPELINE-CONFIG-HISTORY-1` (layout half shipped). ⬆ **65 → 71 earlier on 2026-09-23**: four rows struck (`SINKS-ENTRY-IGNORES-OUTPUT-DEFAULTS-1` P2, `TESTRUN-BINDER-ERROR-LEAKS-PREAMBLE-1`, `DUPLICATE-CHECK-GRAIN-UNSTATED-1`, `DEMO-CORPUS-FORMAT-COVERAGE-1`), ten filed (six from the domain-demo lanes — 2 × P2, 4 × P3 — plus `DUCKDB-PREAMBLE-OTHER-422S-1` and the three P3s carried over when the workbench MoSCoW was archived). Earlier history: ⬇ 59 → 54 across two passes that day, ⬆ **54 → 55 with `CI-JACOCO-JDK27-1` filed 2026-09-18**, ⬆ **55 → 56 with `CODEGRAPH-AFFECTED-UNUSABLE-1` filed 2026-09-19** (P3; its repo-side half shipped in the same commit, only the third-party defect is open), ⬆ **56 → 60 with the four UI-consolidation rows filed 2026-09-22**, ⬆ **60 → 70 with the ten `postmed_xdr` pipeline-build findings filed 2026-09-22** (3 × P2, 7 × P3 — see *Filed from the postmed_xdr pipeline build* in §4). ⬆ **70 → 71 with `DOC-GUARDS-SCAN-IGNORED-SOURCES-1` filed the same day** (P2; found by the push that published the ten). ⬆ **71 → 79 with the eight multi-domain sweep rows filed the same day** (3 × P2, 5 × P3); two v1 rows moved P2 → P3 on re-grounding against the OKF concepts (`DRYRUN-SEEDS-AFTER-PARSE-1`, `PIPELINE-NODE-TEST-STATE-STALE-1`). ⬇ **79 → 78 with `DOC-GUARDS-SCAN-IGNORED-SOURCES-1` FIXED the same day** — all three guards moved onto `git ls-files`, struck rather than deleted per §0's found-and-fixed rule. ⬇ **70 → 63 with SEVEN Sprint C rows FIXED 2026-09-22** (`WB-11`…`WB-15`, `WB-18`, `WB-19`) — 🔴 two of them were not what their rows said: the *ran* badge was already shipped, and the stale test state was a dialog that never returned its result, so the code marking nodes tested had never run. ⬇ **72 → 70 with the two Sprint B ingest-accounting rows FIXED 2026-09-22** (`WB-09` + `WB-10`; the errors report had to land FIRST because its count feeds the ledger's). ⬇ **77 → 72 with ALL FIVE Sprint A rows FIXED 2026-09-22** (`WB-03`…`WB-07`: the arming predicate, the frontend-blind rule, the unnamed reference refusal, the 500-on-a-bad-body, the silent Dataset test run) — one theme, *two surfaces that answer differently*. ⬇ **78 → 77 with `REFERENCE-EXAMPLES-NEED-UNRUN-SEED-1` FIXED 2026-09-22** by `tools/seed-samples.mjs` (`WB-16`) — 🔴 and its stated cause refuted while building the fix: the per-space seeders DO copy the reference, nothing ran them.
> ✅ **The second pass wrote NO code: it audited six rows for work that was ALREADY DONE** and found two that were
> only open as bookkeeping. That is the cheapest kind of progress available and it had not been tried. — ⬇ **DOWN 62 → 59, the first net
> decrease in five board commits**, and the shape of the decrease is the point: five rows closed, ONE filed.
> ⛔ **The growth 56→58→60→63 was real and the "honest result" framing had become a rationalisation.**
> Three causes, named so they can be checked: most rows filed were about our OWN guards and docs, not the
> product; the P1 tier had not moved in three shifts because all of it is operator-gated; and lanes were told
> refutations are valuable without any cap, so every refutation produced a residual row.
> ⇒ **Standing rules from 2026-09-17:** a lane files AT MOST ONE row — anything smaller is fixed in place or
> dropped; a residual that belongs inside an open row stays there (see `EDITION-GATED-TESTS-IN-WRONG-HOME-1`,
> which absorbed its own test-vehicle design instead of spawning a row); and work found-and-fixed the same day
> is recorded STRUCK for provenance rather than filed open (see `CITATION-GUARD-SCOPE-1`).
> ⚠ **Report P1+P2 as the owed number (43), not 59** — §0 defines P3 as demand-gated, i.e. things deliberately
> NOT being built. — ⬆ **UP again, 60 → 63, out of the four-lane
> parallel shift of 2026-09-17: three rows STRUCK as shipped, five FILED.** ⚠ **Three of the four lanes
> refuted part of their own row's premise, and a FOURTH found its row had already SHIPPED** — the residuals
> those refutations exposed are what grew the board. ⛔ **The lanes were handed a row's ORIGINAL prose that
> this file preserves as an INDENTED CHILD under its own strikethrough closure** — grepping a row ID lands on
> the open-sounding text first, and two of four lanes were mis-tasked that way. Read the PARENT bullet.
> — ⬆ **the board GREW by six on the way out of the
> five-lane parallel shift (2026-09-16 evening): eight rows FILED, two STRUCK as shipped, and one
> re-ranked P2 → P1.** ⚠ **That is the honest result of five lanes that were told to ground before
> building**: three of the five refuted part of their own row's premise, and the refutations produced
> residuals that were previously invisible. ⛔ **A parallel shift that closes more rows than it files is
> the suspicious outcome, not this one.** 🔴 **P1 tripled** — `JOB-PATH-COMPAT-SURVEY-1` was re-ranked
> after its survey found that on a deployed tree the 2026-09-16 containment change refuses EVERY relative
> path in EVERY committed job config, and `JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1` was filed as a containment
> hole beside it. ⚠ These numbers are DERIVED — set from what `tools/check-doc-counts.mjs` computes, never
> hand-counted; a hand-count during this very pass disagreed with the guard by one in two directions.
> *(recounted earlier the same day on the way OUT of the 2026-09-16 parallel
> shift, which filed four rows — it was 50 / 35 / 14 on the way in.)* ⚠ **The board grew while four items
> were worked**, and that is the honest result, not a failure: all four were already SHIPPED or REFUTED,
> and grounding them produced five residuals that were previously invisible. ✅ **The one row that was
> pending sweep is now SWEPT** (2026-09-16): `ACQUIRE-LEDGER-DUPLICATE-RESOLUTION-1`, closed as refuted,
> had its refutation distilled into [`okf/backend/engine/db-layer.md`](okf/backend/engine/db-layer.md)
> **§5.0-b** — which is its only home — and the row is deleted, hence 55 → 54 and 15 → 14 P3.
> ⚠ **Homing it found two more count drifts**, in the doc that was receiving it and in the design doc that
> carried the same claim: `OperationalDb.Family` is **fifteen** families, and both said fourteen/eleven.
>
> 🔴 **This line read “71 rows: 1 × P1 · 53 × P2 · 17 × P3” until 2026-09-16, and had been wrong for two days.**
> That census dated from the 2026-09-15 fourth pass; the two shifts since closed, swept or re-ranked
> **eighteen P2 rows and three P3 rows** without recounting. ⚠ The rows were current the whole time —
> only the census lied, which is exactly the failure mode this block warns about three paragraphs down and
> had already recorded happening on 2026-09-14 and 2026-09-15. ⛔ **Third occurrence: recount on the way
> out of EVERY shift that closes or files a row**, not only on a grounding sweep.
> ⛔ Re-derived here from the rows and nothing else — `grep -cE '^- \*\*P2\*\*'` /
> `grep -cE '^- \*\*P3( |\*)'` between the `## 3.` and `## 6.` headings, per §0's own rule.
>
> 🔴 **The headline is not any single answer: SIXTEEN P3 rows stopped being demand-gated in one sitting.**
> §0 defines P3 as *demand-gated — build only when someone asks by name*. On 2026-09-15 the operator was
> walked through every gate on the board, one at a time, and **fourteen of the sixteen demand gates put to
> them FIRED**. Twelve of those fourteen are P3 rows; the other two — a real delete-feed and a
> quarantine-replay driver — discharge sub-items inside existing P2 rows. Four further P3 rows were decided
> outright (`BREAK-DEDUPE-GRAIN-1`, `DERIVED-SCHEMA-PANEL-ORPHAN-1`, `SPEC-DEADSEAM-1`,
> `AIRGAP-CROSSPLAT-DEADWEIGHT-1`). ⛔ **A P3 whose trigger has fired is MIS-RANKED, not merely annotated** —
> all sixteen are re-ranked P2 in this same change. Leaving the rank alone is precisely how a census starts
> lying while every individual row stays true, which is the failure this block warned about a day earlier.
> ⚠ **Only TWO demand gates did not fire** — xz/zstd codecs and `D-11` relations. The demand-gated tier is
> now genuinely small, and the next shift's constraint is **capacity, not permission**.
>
> ➕ **Two rows FILED, and one of them SHIPPED the same day.** ✅ `PARAM-SECRET-LEAK-1` — filed out of
> `DUCKLE-C4-PARAM-PROVENANCE-1`, where a live secret leak was riding underneath a provenance feature and
> would have shipped at that feature's pace — is **BUILT and its row is retired**; the as-built lives in
> `okf/backend/control-plane/jobs.md` §ParameterDecl. ⛔ **The split is the whole lesson**: the leak was
> two call sites routed through a masker that already existed, and joining it to a feature that needs a
> receipt `JobRun` does not have would have held a cleartext credential in the logs for as long as the
> feature took. `PIPELINE-DRYRUN-1` (§3 — new scope, operator-asked) stays open as the thing `X4` is
> scoped against.
> 🔴 **And the leak was WIDER than the row said, in both directions.** It was filed as "a log line and a
> rejection message"; the rejection message alone feeds **four** sinks (run log, `job.run.rejected`
> Signal, persisted `JobRun.reason`, run-detail API), because `ParameterResolver` builds one string that
> `JobService` then fans out. ⚠ **Fixing it at the message source closed all four** — ⛔ had it been
> patched at the sinks instead, that would have been four patches and a fifth sink waiting.
> ➖ **One gate LEFT §2**: `EOI-7b` is a standing refusal in §6, so §2 is **14** rows, not 15.
> ⚠ **§1 IS NOT EMPTY** — it now carries nine **owed operator inputs**, which is a kind of pending item that
> section has never carried before. ⛔ An owed input stops work exactly as a pending decision does.
>
> ✅ **Two defects BUILT and closed 2026-09-15**, both found by the sweep and both proven red before being
> called fixed: `AUDIT-REFUSAL-GAP-1` (401/403 refusals on a matched route now reach the audit trail —
> `events-metrics.md` owns the as-built, including the two deliberate asymmetries) and
> `DATASET-PUBLISH-ON-FAILURE-1` (a `dataset.write` is announced only after the run completes —
> `pipeline-execution.md` §triggers). Reactor **4513 / 0 / 0 / 28 over 26 modules**, `-Pedition-enterprise`.
> ⚠ **That baseline is +8 over the 4505 last recorded, and only 5 are mine** — the other 3 came from
> `6ec10981`, which landed after the baseline was written. ⛔ Reconcile a moved baseline by counting
> `@Test` additions per commit; do not assume your own change explains the whole delta.
>
> ✅ **Every P2 was grounded against CODE on 2026-09-15**, not against its own text. **Five closed outright**
> — `RECON-CARDINALITY-1` (tier 1 shipped whole; tier 2 survives as the demand-gated
> `RECON-CARDINALITY-2`), `STUDIO-HALVES-1` (shipped end to end, example job included), `EXPECTATIONS-UI-1`
> (its premise *"zero SPA files mention it"* was false — **31** do, and the pane is routed and mounted),
> `SPEC-AGT-EDITIONS-1` (both halves refuted; the real work was a one-paragraph doc fix, now made) and
> `ROUTE-OWNERSHIP-SCOPE-1` (the per-row owner check it calls missing already exists —
> `ObjectRoutes.java:195-243`). **Two rows shrank on recount**: `SPEC-GREENCELL-1` five instances → **two**,
> `SPEC-DEPLOY-ROWS-1` fifteen → **thirteen**.
> 🔴 **And the sweep FOUND more than it closed, which is the honest result.** Two new rows, both from
> premises that were being carried as settled: `CONSIGNMENT-OUTPUTS-NULLRUN-1` (**P1** — a UNIQUE key was
> added against an explicit ⛔ in the code, and the graph lane escapes it on the DEFAULT ingest setting) and
> `ENRICH-SILENT-FULL-RECOMPUTE-1` (an incremental recompute silently running as a full one — carried for
> weeks as a *design note* while the code was already doing it).
> ⛔ **The lesson, again: a row that says "settled, unbuilt" is two claims, and both need grounding.**
> `Consignment ELT` asserted the `consignment_outputs` key was *"still not addable"* and that
> `ON CONFLICT DO UPDATE` was *"settled, unbuilt"* — both had shipped two days earlier, and shipping them
> early is what created the P1.
> 🔴 **The header said "58 rows: 0 × P1 · 35 × P2 · 23 × P3" for a day after it stopped being true** — that
> was the 2026-09-14 grounding, and the 2026-09-15 shift then filed or adopted **seventeen rows** (nine
> `DUCKLE-C*` build rows, six defects, `ROUTE-UNGATED-DEFAULT-1` and `AIRGAP-CROSSPLAT-DEADWEIGHT-1`)
> without recounting. ⚠ **The rows were current; only the census was stale** — and a stale census is the
> failure mode this very block warns about two paragraphs down. ⛔ **Recount on the way out of every
> shift that files a row**, not only on a grounding sweep.
>
> 🔴 **`CONSIGNMENT-OUTPUTS-NULLRUN-1` was filed as a P1 on 2026-09-15 and REFUTED the same day, before a
> line of its fix was written.** The claim was that the graph lane writes `run_id = null` into
> `consignment_outputs` and so escapes the UNIQUE key. The null-supplying construction is real
> (`ConsignmentGraphRunner:82`) — but it sits in a two-arg `run` overload with **no production caller**:
> the one production caller passes its own `IngestSinkWriter`, and the ingest lane registers through
> `ConsignmentIngestor`, which mints a run id. ⬛ **The row was filed on a line number, not on a call
> graph.** I verified the line existed and that the lane was reachable, and did not check WHICH OVERLOAD
> the caller used — the one question that decided it.
> ⇒ Kept as a lesson, not a row: **reachability of a FILE is not reachability of a METHOD.** What was
> real and is now fixed: two javadocs in that subsystem contradicted each other, and the test-only overload
> said nothing about being test-only. Both corrected 2026-09-15.
>
> 🔴 **P1: `ROUTE-UNGATED-DEFAULT-1`** (§5) — an unlisted route is OPEN, not locked down; 83 mutating
> routes are ungated, `DELETE /spaces/{id}` among them. Its full audit is DONE
> (`superpower/route-gating-audit.md`) and reframes it as a **vocabulary** gap: only 12 are gateable with
> an existing capability, 22 are not expressible at all, and `Roles` has **no admin capability**. ⛔ The
> next move is a decision, not code — and ⛔ NOT the ratchet, which would freeze 83 unreviewed exemptions.
>
> ✅ **All 20 SHIPPED/CLOSED entries were swept off the page 2026-09-15** — about 470 lines — each one
> first checked against the doc that owns its as-built. ⚠ **The sweep was not mechanical: 11 of the 20
> carried text that existed NOWHERE else**, which was migrated before the delete (guard lessons →
> `guard-coverage.md`, the shared-pipeline audit + its **"WARN ONLY — never refuse"** operator decision →
> `jobs.md` and §6, the `release.yml` reactor-install as-built → `editions.md`, the write-time-hooks
> deviation → `db-layer.md`, and more). ⛔ **Never bulk-delete closed rows on the strength of their
> ✅ marker** — the marker says the *work* is done, not that the *knowledge* landed anywhere.
> 🔴 **Three findings the sweep produced that a delete would have destroyed:** (1) `SBOM-RESOLVE-1`'s own
> owning doc still described it as an **open P1** four days after it shipped; (2) `SchedulerAuditTask`'s
> javadoc still says an operator decision is *owed* that was **answered 2026-09-12**, and the board entry
> was the only record of the answer; (3) `COLLECTOR-SPACE-ROOT-1` hid **live, untracked work** — now filed
> as `COLLECTOR-DATASET-UNPROVEN-1`. ⚠ A closed row is where open work goes to hide.
> ⚠ Two entries also pointed at the wrong owning doc (`MEASURE-SHORTHAND-ONE-HOME-1` →
> `catalog-vs-executors.md` and `DUCKLAKE-GRAPH-LANE-1` → `db-layer.md`, neither of which mentioned its
> subject). ⛔ **A `→` pointer is a claim, not a fact** — follow it before trusting that a row is homed.
>
> ⚠ **Three rows opened and closed the same day (2026-09-14)**, all out of scale-out §5.4:
> `AIRGAP-EXTENSIONS-CI-1`, `AIRGAP-DUCKLAKE-PG-1` and `DUCKLAKE-GRAPH-LANE-1` — so the P2 count moved far
> less than the activity did.
> 🔴 **On 2026-09-14 the surrounding prose said "36 → 35" and "the 36 P2 rows" while the header said 34** —
> three numbers for one count, none recounted. ⛔ **Count the rows, do not carry a number forward**: the
> P2/P3 totals are `grep -cE '^- \*\*P2\*\*'` / `grep -cE '^- \*\*P3( |\*)'` between the §3 and §6
> headings, and nothing else is authoritative. ⚠ The P3 pattern is the looser one on purpose: one row
> spells its rank `- **P3 · RELEASE-GATED …**`, and `^- \*\*P3\*\*` silently undercounts by one.
>
> ⚠ **Only the 0<!--count:backlog-p1--> P1 + 29<!--count:backlog-p2--> P2 rows are queued work.** §0 defines **P3 as demand-gated — "build only when
> someone asks by name"** — so those 31<!--count:backlog-p3--> are mostly a list of things deliberately *not* being built, not a
> backlog to burn down. Reading all 60<!--count:backlog-rows--> as pending work overstates what is owed by roughly half.
> ✅ **These four figures are now DERIVED and build-enforced** (`tools/check-doc-counts.mjs`, markers
> `backlog-rows` / `-p1` / `-p2` / `-p3`) — a hand-recount can no longer drift, which is what this block
> had done three times. 🔴 **It caught its author within hours:** this shift filed rows after the pin
> landed and the guard went red at 54/37 vs a derived 56/39. ⛔ The census is no longer something you
> update — it is something the build checks.
> ⚠ **One P3 is NOT demand-gated** — `GLOSSARY-CASE-1` spells its rank
> `- **P3 · RELEASE-GATED (next MAJOR), not demand-gated**`: it is queued work whose gate is the next MAJOR
> tag. ⛔ So “P3 ⇒ nobody is waiting on it” holds for 15 of the 16, not all of them — and that same
> spelling is why §0's P3 pattern is the looser one.
> 🔴 **This paragraph was internally inconsistent before this fix**, carrying “38 P2”, “those 31” and
> “all 70” against a header of 53/17/71 — four numbers for two counts, in the very block that tells the
> next shift to count the rows. ⛔ A census sentence that is not re-derived alongside the header is
> decoration, not arithmetic.
>
> The sweep deleted **10 rows whose work was already shipped** (each verified in code, not by commit
> message) and corrected stale claims inside several survivors. 🔴 **The lesson worth keeping:** a
> commit-message census flagged 28 of 36 row ids as candidates; grounding them found **10**. Acting on the
> heuristic would have deleted roughly eighteen live work items. ⛔ Never delete a row on a commit mention —
> a commit that *names* a row is very often the commit that FILED it, or did one part of it.

**What this page is.** The single board of open work, grouped by what has to happen next. Each row is
one line of *what remains* plus a pointer to the document that owns the detail. It lists **open work
only** — nothing here is done.

**Rules of use.**
- A row's stated cause, severity **and proposed remedies** are all a **hypothesis** recorded when the row
  was filed. Ground them in the code before building; the archived snapshot records how often that
  grounding overturned a row. ⚠ The remedies clause was added 2026-09-15 from
  `JAVA-INGEST-APPENDER-SERIAL-1`, where remedies (a)-(c) were never built and were never owed — the fix
  came from a cause nobody had listed.
- When an item ships: mark it in its *source* doc first (that stays authoritative), then **delete the row
  here**. No strikethrough as-built narrative — that is what the OKF concept docs and git history are for.
- New pending items discovered mid-shift get a row at handoff time (see the `handoff` skill). Keep the row
  to one line of remaining work; the detail goes to the owning OKF concept or plan.
- ⛔ marks a refusal or a precondition that must not be bypassed. 🔴 marks a live defect or a trap.
- §6 lists standing refusals so nobody re-files them. They are not work.

---

## 0. Priority order

**P1** = do next: a live defect, or decided work with user-visible value. **P2** = build after P1, or once its §1
decision lands. **P3** = demand-gated; build only when someone asks by name. Every §3/§4 row carries its rank.

**P1 is drained (2026-09-06).** The shift that consolidated this page took every P1 row: three were built
(join-reference and schema-drift refusals at save, the audit over every input, the CI examples smoke), three
were already shipped and only the row was stale (AUTHORING-WIDE-1, the torn multi-file read, the auditor
evidence note), and four were re-ranked because the row hid a gate — a design pass, an operator decision, or
a new dependency — not a build. The rule that fell out: **a P1 must name the file it changes.** A row that
cannot is a decision (§1) or a design (P2).

✅ **`ROUTE-UNGATED-DEFAULT-1` step 3 SHIPPED 2026-09-16 — the control is IN PLACE.** An undeclared
mutating route now fails the BOOT (`ControlApi.register`), with no `warn` escape hatch: “we reviewed the
routes” became “an undeclared mutating route cannot exist in a running server”, which an auditor can test
by trying to add one. All four of step 2a's operator calls were answered the same day, and
`CapabilityManifest.PENDING_OPERATOR_CALLS` is EMPTY. ✅ **Steps 4c/4d/4e SHIPPED TOO, in `6c216a56`** —
the boot inventory event (`ControlApi.java:506`, `route.inventory.snapshot`), `GET /audit/route-inventory`
(`AuditLogRoutes.java:48`, pinned by `RouteInventoryTest`), and the GENERATED evidence table in
`compliance/evidence/route-gating.md` (`tools/route-gating-report.mjs`, wired into `ci.yml:145` and
`.githooks/pre-push:211`). ⇒ **the row's remaining BUILD work is NOTHING**; the block above said 4c/4d/4e
were outstanding and was stale by a commit. ⛔ The SOC 2 observation window's state is recorded in exactly
ONE place, `controls-matrix.md` §4a — do not restate it here.
🔴 **What the grounding DID find: the derived-evidence guard was RED on the committed tree.**
`2c310d1c` moved two registrations in `ConfigWriteRoutes.java` by one line (`44→45`, `48→49`) and nobody
regenerated, so the auditor-facing table asserted line numbers the code did not have — exactly the drift
the guard exists to catch, caught only because someone RAN it. Regenerated 2026-09-16; green.
⛔ **`node tools/route-gating-report.mjs --check` is not optional after any change to a route class.**
✅ **The guard was falsified in BOTH directions before being trusted**: flipping one row's posture in the
doc fails with *“its table differs from the code”*, while adding an undeclared `api.post` fails with a
DIFFERENT message, *“1 mutating route(s) declare no posture”* ⇒ it is not merely a text diff.
Clean-tree inventory: **172 mutating routes — 115 gated, 57 exempt, 0 undeclared** (re-derived from
`tools/route-gating-report.mjs` on 2026-09-22; the `109/63` this line used to state was stale prose, not
a change — the generated table already said 115/57).
⬜ **One operator item is still owed, and it now blocks only WORDING:** which framework the evidence is
written against. It is written against **SOC 2 Type II** (CC6.1/CC6.3) as an ASSUMPTION, labelled as such
at the top of `evidence/route-gating.md`; ⛔ no ISO 27001 A.9 mapping has been invented, because the same
evidence maps onto A.9 with DIFFERENT matrix rows and inventing it would hand an auditor a claim nobody
decided. If the answer is ISO, or both, only that header block and the criterion references change.
⚠ **Plan step 4g is NOT done and is not in the evidence document**: the audit-event export procedure
(`GET /audit/export` before the 1-year prune reaches the evidence). Worth a row if the window opens.
→ `archived-documents/plans-archive/route-gating-compliance-plan.md`

Do next, in order (refreshed **2026-09-11** — four P1s queued from the whitepaper review; each names the file it changes):
0. **Sprint 8 — make the brochure true.** In this order, smallest first, each independently shippable:
   ~~**`EVENTS-DURABLE-1`**~~ ✅ **FULLY CLOSED.** The launcher half shipped 2026-09-11 (Standard+ keeps the
   audit trail across restarts); the degrade-on-failure residual was **discharged by scale-out A1** the
   same day it became buildable — `-Dinspecto.topology=partitioned` exists and `StoreHealth.record` throws
   on a DEGRADED outcome while partitioned — and its §3 row is deleted. That shift also found and fixed `SERVEBAT-OPTS-1`: Windows Standard/Enterprise bundles were
   booting **auth-free**. →
   ~~**`AIRGAP-EXTENSIONS-1`**~~ ✅ **SHIPPED 2026-09-11** — `ducklake` now loads through the same staged-file
   path as `excel` and is bundled beside it. 🔴 Two of the row's three prescriptions were **wrong on
   grounding**: `httpfs` is loaded by nothing and is REFUSED by both SQL guards, and `EgressGuardTest` is
   about the assistant's LLM classpath, not DuckDB. A third finding is filed as `AIRGAP-DUCKLAKE-PG-1`. →
   ~~**`DEPLOY-SERVICE-WRAPPER-1`**~~ ✅ **SHIPPED 2026-09-11** — systemd unit + both installers stage into
   the bundle; the `sc.exe` recommendation was refused on grounding (error 1053) and Windows gets a boot
   task instead. ⚠ Its live `kill -9` acceptance is **unrun** — see the §3 residual. →
   ~~**`BREAK-INCIDENT-1`**~~ ✅ **SHIPPED 2026-09-11** — `POST /recon/promote` + a board row action, deduped
   on `(reconciliation, key)`. 🔴 The row's premise was **half wrong**: `ReconRunJob` had always opened an
   Incident on a breach, so the gap was **granularity, not mechanism** (one aggregate Incident per run vs.
   one per Break) — the two now coexist. **Sprint 8's four P1s are DONE.** →
   then the P2s ~~**`BREAK-AGING-1`**~~ ✅ **SHIPPED 2026-09-11** (⚠ its "and the KPI report" half had no
   target — `ReconSummary`/`summarize` have **no production consumer**, only their own spec; the buckets
   render on the Board and the Breaks page instead), ~~**`INCIDENT-KPI-MTTR-1`**~~ ✅ **MTTR SHIPPED 2026-09-11** (🔴 the row said Incidents
   "already carry `resolvedAt`" — they did **not**: there is no such field, and `closedAt` is stamped on the
   TERMINAL state, which for an Incident is `ARCHIVED`, so the existing `cycleTime` measured time-to-archive
   and its UI tile was even labelled "Resolved". MTTD is split out as `INCIDENT-KPI-MTTD-1`, still
   anchorless), ~~**`SIGNAL-STALE-TILES-1`**~~ ✅ **SHIPPED 2026-09-11** — 🔴 **four of the row's
   premises were false** (a gap is an Event not a Signal; quarantine emits no signal; neither names a
   Dataset; Widgets are not Catalog nodes, so the lineage traversal it named does not exist). Built on a
   **pipeline** anchor per the operator's call; residuals filed as `STALE-TILES-PRECISION-1` and
   `CATALOG-WIDGET-NODES-1`. **⇒ SPRINT 8 IS COMPLETE — all seven rows.** →
   then the signed scale-out plan's **phases A + B**, which are the Standard DR page. ⚠ Two §1 decisions gate
   the rest of the brochure: ~~`PKG-5`~~ ✅ **RESOLVED + SHIPPED 2026-09-12** and ~~`RECON-CARDINALITY-1`~~
   🔴 **NOT a brochure gate — regrounded 2026-09-12**: the whitepaper's cardinality sentence was deleted
   by the v1.2 rewrite (`db11a412`) a day BEFORE the build-over-drop decision was recorded, so nothing in
   the brochure is untrue while it waits. It stays a P2 feature; see its §3 row. ⇒ **No §1 decision gates
   the brochure any more**
   (now the only one left).
   ⛔ Do not start v1.2 of the whitepaper until 0–4 are green: it is the claims that move, not the prose.
1. ~~**`SBOM-RESOLVE-1` (§4)**~~ ✅ **SHIPPED 2026-09-09** — `release.yml` now installs the reactor under `-Pedition-enterprise` before the packaging steps. Was the only queued P1. The bill of materials cannot generate on a clean
   runner, so the first tag fails at packaging; it names the file it changes (`release.yml`). Filed
   2026-09-09 while fixing the generator's module table, and it is **not** a regression from that fix —
   Enterprise failed identically before it. Everything else below was already drained: MERGE-ATTRS-1,
   filed hours earlier on 2026-09-07, was **refuted on grounding**
   within one shift of being written and moved to §6 — `transform.merge` is deliberately not authorable, so
   declaring attributes for it would give a config pane to a node that refuses to save. Pick from §3/§5 by
   rank, or take the two §2 rows whose first action is **not** external (the SOC-2 window start date and
   the completeness-KPI query).
2. **Release notes for the next MAJOR** — keep appending (§2).
3. **Step Processor catalog** — pick a partial by name (§3).

⚠ **`NAME-DIRS-1` was deleted unbuilt on 2026-09-07: it had already shipped** in `70473c94` the day
before ("dirs from the slug"), implemented AND pinned by a spec asserting no space, dot or dash reaches
any dir. It was filed because `pipeline-identity.md` cited a §3 row that did not exist — the *row* was
missing, the *work* was not. ⛔ A decision that "unblocks" a row does not mean the row is open: grep the
code before filing, not just the board.

✅ **SEQUENCE SET 2026-09-15 (operator): the secret-masking fix FIRST, then the P1.**
`PARAM-SECRET-LEAK-1` went before `ROUTE-UNGATED-DEFAULT-1` — not because it outranked a P1, but
because it is two call sites routed through a masker that already exists, and secrets are reaching log
files *today*. ⚠ **This is a deliberate exception to §0's own ordering, made with the P1 in view**, so
⛔ do not read it as a precedent that small work jumps a P1 in general.
🔴 **And the P1 does NOT start with code.** Its first move is grounding all 10 remaining gateable routes
and reporting a per-route verdict — two of the first four attempted were **deliberate exemptions** whose
gating turned the build red, and one of those two produced no red at all.

### Dependency map — what blocks what (analysed 2026-09-15, every P-ranked row in §3–§5 read)

**Verdict counts:** UNBLOCKED **42** · BLOCKED-OPERATOR **3** *(2026-09-16 interview: TWELVE calls answered in one sitting — the enrich/materialize pair, all four route calls, the `temporalColumn` verdict, the spreadsheet library and Job path semantics; then reads-open-by-policy affirmed for compliance and the pre-materialise cap settled on BYTES with a deferred remainder. `ROUTE-UNGATED-DEFAULT-1`, `TYPEFLOW-DATASET-COLUMNS-1`, `D-8`, `JOB-DIR-CWD-CONTAINMENT-1`, the plan's §3e and the cap are all unblocked. ⚠ What is left in §1 is what only the operator can DO, not decide: two off-repo dates, the per-tier RTO/RPO targets, the stray branch)* *(the two migrate rows were filed AND answered on 2026-09-16; three of the four route calls were answered the same day)* · DESIGN-FIRST **8** *(recounted against the list itself 2026-09-16, after both graph-lane rows were filed, designed, decided and SHIPPED the same day and struck from it. ⚠ **The `7` this line used to state never reconciled with its own list, and the discrepancy PREDATES this pass** — with `GRAPH-LANE-RULE-ROUTED-1` still live the list carried NINE entries against a stated 7; it is not being silently "fixed", it is being named. Striking that row leaves **8**, which is what this number now counts. ⛔ A tally nobody re-derives from the list underneath it is decoration, not arithmetic.)* ⚠ **The other four counts on this line were NOT re-derived on 2026-09-16** — they still date from the 2026-09-15 pass, and a shift that needs one should recount it rather than quote it. · BLOCKED-EXTERNAL (trigger not
fired / release / hardware) **12** · BLOCKED-ROW **2**. ⚠ Read the row before starting — several are unblocked
for ONE clause and blocked for the rest (noted per row).

**Unblocked, smallest first** (S = one sitting · M = a day · L = multi-day):
- S — *(none left — `SPEC-DEPLOY-ROWS-1` closed 2026-09-15: its thirteen items are named on the Deployment topology row, specified in `editions.md` §3.9–§3.14).* ⚠ The first draft of this map listed a "Consignment ELT
  `generation` clause" here — a misreading of `GenerationModeIngester`; that row's only remainder is the §7.4
  rollup cache, which is trigger-gated.
  *(S items closed the same day: `DEMO-SPACE-PERSONAL-UNBOOTABLE-1`, `POLL-STATE-BLIND-TO-CONNECTOR-FAILURE-1`,
  `SCHEMA-FORM-EMPTY-LIST-1`, `SIGNIN-PREVIEW-1`, API v1 jlink re-verify.)*
- M — ~~`TYPEFLOW-DATASET-COLUMNS-1` steps 3+4 ✅ **UNBLOCKED 2026-09-16 — the verdict came back KEEP `temporalColumn`**, which is exactly what the clause assumed~~ ✅ **CLOSED 2026-09-16** (`9d8c2365` + `901ff1bd`; stale here until 2026-09-23) —
  **the M tier is otherwise DRAINED (2026-09-16)**: `AGT-5` → BLOCKED-EXTERNAL (no `PlatformBuilder` seam) · the
  parity gate → ✅ **RUN AND MET 2026-09-16** — the whole suite under `-Dingest.lane=graph` is **4603 / 0 / 0 / 28, zero refusals**; both graph-lane gaps (`GRAPH-LANE-MULTISCHEMA-1`, `GRAPH-LANE-RULE-ROUTED-1`) were filed, designed, decided and SHIPPED the same day and are off the board · pre-materialise cap → §1 owed input.
  *(M items closed 2026-09-15/16: `HOME-VERSION-1`, `RECON-CARDINALITY-2`, `STALE-TILES-PRECISION-1`,
  `INCIDENT-KPI-MTTD-1`, `DUCKLE-C4-PARAM-PROVENANCE-1`, `AGT-ARTIFACT-1`, `HOME-TILES-1`, `DUCKLE-C2-RUN-DIFF-1`.)*
- L — `AUTHORING-REDESIGN-1` (c) only · canonical bundle export/import · Onboarding↔Pipeline W4/W5 · Parsing
  Stage-1 (ASN.1 module ref) · Platform Services Stage 2 · `EXECUTION-RESIDUALS` X1 deferrals · Completeness KPI
  K1/K2/K4 · Job framework space-to-space comparison · D6 spec-authoring UI · Signal/Decision S8 · Security
  policy-authoring UX · Deployment topology gaps · Postgres multi-user (build; acceptance needs a Postgres host)
  · `DUCKLE-C3` · `DUCKLE-C1` · `DUCKLE-C8` · `DUCKLE-C10` feature · `DUCKLE-C6` · `DUCKLE-C7`.

**Blocked on the operator (§1), by rows unblocked:** ~~the multi-schema lane flip~~ ✅ answered AND shipped 2026-09-16 (`auto` flips) · the FOUR route calls → the P1 (`ROUTE-UNGATED-DEFAULT-1`
step 3 → 4c/4d/4e; also gated by "confirm reads-open-by-policy" and "which framework(s)") · the three dead-seam
verdicts → `SPEC-DEADSEAM-1` + the `temporalColumn` clause of Consignment addressing (~~+ `TYPEFLOW-DATASET-COLUMNS-1`
Q2~~ — CLOSED 2026-09-16, `9d8c2365` + `901ff1bd`) · "a spreadsheet library" → D-8 XLSX · "access details" → `DEPLOY-SERVICE-WRAPPER-1` (a run, not a build) ·
"job path semantics" → `JOB-DIR-CWD-CONTAINMENT-1` · pick the next Step Processor partial BY NAME → the catalog row
· commission the SES/SNS adapter review → Notifications + `D8-SES-SNS-1` (re-pointed 2026-09-17: `D8-SUPPRESS-1` is CLOSED and the adapter never lived there). "Eager/deferred `s3://`", the two
dates, RTO/RPO, the stray `master`, the `ref:` pin unblock no §3–§5 row directly.

**Design-first (a decision pass before code, by the row's own words):** ~~`GRAPH-LANE-MULTISCHEMA-1`~~ ✅ **design pass DONE 2026-09-16** (`archived-documents/plans-archive/graph-lane-multischema-design.md`) — the executor question was answered empirically, the row shrank, the operator answered the one call it then needed (`auto` flips), and it SHIPPED the same day — off the board · ~~`GRAPH-LANE-RULE-ROUTED-1`~~ ✅ **design pass DONE and SHIPPED 2026-09-16** — and its question ("where does a rule-routed destination become a graph node, when the lift cannot see the rules?") was **dissolved, not answered**: `DecisionRuleApplier` writes the routed rows above the fork and then `DELETE`s them from the relation (`DecisionRuleApplier.java:233-237`), so both lanes see the identical remainder and no graph node was ever needed — off the board · AI drafting on non-schema kinds ·
Onboarding D5-ref (ground the real delete-feed first) · Branch-aware residuals (b)(c) · `PIPELINE-DRYRUN-1` (which
seam enforces the mode) · `STREAM-CONSUMER-1` (where the loop lives) · Bundle/Exchange load-as-draft ·
Completeness KPI (b) sequence-template source · `AUTHORING-REDESIGN-1` (e).

**Row → row chains:** `PIPELINE-DRYRUN-1` → `EXECUTION-RESIDUALS` X4 (replay default) · dead-seam verdict (2) →
~~`TYPEFLOW-DATASET-COLUMNS-1`~~ (CLOSED 2026-09-16), Consignment addressing · ingress path-routing (scale-out plan §5.5, **no board row**)
→ `SPACES-FROM-PARTITION-MAP-1` → remove `podScoped: true` from `/spaces`, `/bootstrap` · ~~backend cheap-count
endpoints → `HOME-TILES-1`~~ (both shipped 2026-09-16) · a soak run → Pipeline graph intake-cap flip · `DUCKLE-C10` rules (recorded) constrain
scale-out phase B → `DUCKLE-C10` feature · a Postgres host → Postgres multi-user acceptance. ~~`GRAPH-LANE-MULTISCHEMA-1`~~ **and** ~~`GRAPH-LANE-RULE-ROUTED-1`~~ → §2 Row 15's deletion
half — ✅ **BOTH shipped 2026-09-16 and the re-run is DONE, not owed**: `mvn -Dingest.lane=graph test` is **4603 / 0 / 0 / 28**, so the §6 step-2 parity gate is MET and this chain is discharged. ⚠ Row 15's deletion half is still NOT startable, but on **different** predecessors now — §6 step 1 is not dischargeable for a space owning an enrich config (`MIGRATE-ENRICH-1`), and the release gate is an operator call; see the row. Satisfied
predecessors: `BREAK-DEDUPE-GRAIN-1` → `RECON-CARDINALITY-2`; `DATASET-SELF-TRIGGER-1`'s `Ref` →
`STALE-TILES-PRECISION-1`; the at-rest decision → Platform Services Stage 2 and ELT Phase 6.

**External / trigger-gated (do not start on speculation):** Step catalog P4 parser mapping · `AGT-SEGMENT-1` ·
Unpack xz/zstd · Branch-aware (d)–(g) · Consignment ELT §7.4 rollup cache · `GLOSSARY-CASE-1` and Vocabulary
Tier 3 (next MAJOR) · D-11 hand-authored relations · Queries/BI graph/spatial/search/api · `EXPORT-1` · API v1
further adopters · Pipeline graph intake-cap flip (soak) · `SPACES-FROM-PARTITION-MAP-1` (ingress first).

## 1. Operator decisions pending

⛔ **NOT EMPTY as of 2026-09-15 (second sitting).** Thirty-eight decisions were answered in one pass — the
table further down — but the same sitting produced **six operator INPUTS that are owed and not supplied**,
and the route-gating grounding later that day added **three more** (the route calls the compliance plan needs).
⚠ **An owed input is a pending item exactly like a decision**: work stops on it just the same, and the only
reason §1 had never carried one is that nobody thought to file one here.
⛔ **The paragraph above is the HISTORY of how this table filled up — the live count is the one below it,
and only that one.** It said "four of the six" long after both numbers were false; two counts in one
section is how the drift started.

✅ **The table is FOUR live rows as of 2026-09-16 (second sweep)** — everything else in it is struck.
**Counted from the table, not asserted:** `awk '/^\| Owed input/,/^$/' docs/BACKLOG.md | grep '^| ' | grep -vc '^| ~~'`
minus the header and separator rows. ⚠ The first draft of this very paragraph said FIVE and was wrong by
one — ⛔ **derive this number, never carry it forward by hand**; every count in this table that was
hand-maintained has drifted at least once.
⚠ It read NINE that morning, and **two of those nine were never owed inputs at all**: the stray `master`
branch had **already been deleted upstream** (verified: `refs/heads/main` is the only ref) and the four
route calls were **already drained in code** (`CapabilityManifest.java:324` = `List.of()`). Two more —
the **framework** call and the **`ref:` pin** — were answered and shipped the same day, and the third,
`SPEC-DEADSEAM-1`'s last two verdicts, closed its §4 row outright.
🔴 **Three census failures in this one table in two days, each a different shape**: two answered inputs
**re-filed instead of struck** (*job path semantics*, the *pre-materialise cap* — swept earlier today);
two rows **discharged elsewhere and never swept back here** (the route calls, in code); and one row
**dissolved by an event nobody probed for**, because its only exit was someone else's action.
⛔ **The rules that follow from those three:** answer an owed input by **striking its row**, never by
adding a second one; when a §1 row's answer lands in CODE, **strike §1 in the same change**; and
**re-ground a row whose exit is external before reporting it owed** — "blocked on someone else" is not
a reason to stop checking, it is the reason a row rots unnoticed.

**What is left — FOUR, and not one of them is a question a shift can answer for you:** two dates
(`SEC-INCIDENT-1`), the per-tier RTO/RPO commitment, the three access details, and the `s3://`
`dirs.database` eager-vs-lazy design call. ⚠ **Three of the four block rows whose CODE IS ALREADY
COMPLETE** — a shift reading them will look for something to build and find nothing, which is the shape
that kept `DEPLOY-SERVICE-WRAPPER-1` looking open for days.

| Owed input | Blocks | Why only the operator can supply it |
|---|---|---|
| **Two dates** — when the off-repo backup bundle was deleted, and when the no-reuse check completed | the `SEC-INCIDENT-1` CC6.1 line (§2) | Both acts happened off-repo and leave no trace here. ⛔ A line dated *when it was written down* would misstate the evidence to an auditor |
| **Per-tier RTO/RPO targets** | `compliance/evidence/rto-rpo-statement.md` · §2 Deployment topology | ⛔ Decided 2026-09-15: do **not** transcribe the signed §3.14 numbers. In `editions.md` they are an engineering target; in `compliance/evidence/` they are a commitment an auditor holds you to, and those are not the same number by default |
| ~~**A spreadsheet library**~~ ✅ **DISSOLVED — see the struck row below; the capability was already in the bundle, so no library was ever picked.** ⚠ This is the UNSTRUCK DUPLICATE of that owed input, left here in its pre-answer wording — 🔴 a §1 table carrying both an answered and an unanswered copy of one question is how a shift reads the wrong one. | `D-8` (§3) — CLOSED | — |
| **Access details** — MinIO endpoint/key/secret, the systemd host, the elevated Windows box | `AIRGAP-S3-EXTENSIONS-1` (§5) · `DEPLOY-SERVICE-WRAPPER-1` (§3) | ✅ Operator confirmed 2026-09-15 that all three EXIST. Both rows are **code-complete and evidence-blocked** — neither needs a build, only a run |
| ~~**Pre-materialise cap — unit + remainder policy**~~ ✅ **ANSWERED 2026-09-16: cap on BYTES, and the remainder DEFERS to the next run** (not refused, not counted in files) | the Pipeline graph row's pre-materialise cap clause — now unblocked | Bytes because remote bandwidth is the scarce resource a file count does not bound — one huge file blows through a count. ⛔ Deferring makes the ledger load-bearing: it must remember what was skipped, or a file larger than the cap, or one that keeps losing the race, is **starved forever**. Design that starvation guard with the cap, not after |
| ~~**Dead-seam verdict (2) — `temporalColumn`**~~ ✅ **ANSWERED 2026-09-16: KEEP it.** ⇒ `TYPEFLOW-DATASET-COLUMNS-1` steps 3+4 were UNBLOCKED — and that row is now **CLOSED** (`9d8c2365` + `901ff1bd`, 2026-09-16); the `temporalColumn` clause of Consignment addressing stands; `SPEC-DEADSEAM-1` still owes verdicts (1) and (3) | — | Recorded here rather than struck so the other two verdicts stay visible |
| ~~**A spreadsheet library** for `D-8` XLSX export~~ ✅ **ANSWERED 2026-09-16: Apache POI**, and the feature is WANTED (the row was demand-gated and the demand arrived) | `D-8` XLSX export (§3) — now unblocked | ⚠ POI is the conventional JVM choice and the widest on format support, but it pulls a sizeable transitive tree into a reactor that today has **no spreadsheet dependency at all**, and every edition bundle grows with it. ⛔ Add it to ONE module, not the parent, and check the bundle-size and dependency-review guards before assuming it lands quietly | ✅ **SUPERSEDED BY THE BUILD, 2026-09-16 — D-8 IS SHIPPED AND NO LIBRARY WAS ADDED.** 🔴 The POI answer above was overtaken the same day: `PipelineDocumentXlsx` writes the workbook with **DuckDB's `excel` extension** (`COPY … TO … (FORMAT xlsx)`) — already bundled and already staged for air-gapped installs, because it is the same extension the `xlsx` PARSER reads with. Its javadoc states *“⛔ No new dependency”* outright. **Verified: there is NO `org.apache.poi` declaration in any pom**, and `PipelineDocumentXlsxTest` is 4/4 with `writesARealWorkbook` genuinely executing (1.297s of real I/O, no skip element). ⚠ **A `grep poi` over the poms returns NINE hits and every one is a substring of “point”/“policy”** — a false POSITIVE, the mirror of the false-zero trap; match `org.apache.poi` or `<artifactId>poi`. ⇒ The dependency question was never answered by picking a library; it was DISSOLVED by finding the capability already in the bundle. ⛔ Do not add POI.
| ~~**Job path semantics**~~ ✅ **ANSWERED 2026-09-16: a relative path in a Job config resolves against the SPACE'S CONFIG ROOT**, never the process working directory | `JOB-DIR-CWD-CONTAINMENT-1` — now unblocked | Consistent with the path-jail model and portable across hosts; ⚠ a Job whose meaning depends on how the server was launched is the defect this closes. Check for configs relying on the old behaviour before shipping |
| ~~**Delete the stray `master` branch** on `jotder/inspect-agent`~~ ✅ **DISSOLVED — VERIFIED GONE 2026-09-16.** `GET repos/jotder/inspect-agent/git/refs/heads` returns **`refs/heads/main` and nothing else**, and the repo's `default_branch` is `main`. The trap this row describes cannot fire | ~~nothing — a live trap~~ | ⛔ **This is an owed-input row that stopped being owed without anybody checking.** It asked for a permission no shift had, so no shift ever probed whether the act was still needed — a row whose only exit is someone else's action still has to be RE-GROUNDED, or it sits forever describing a world that has moved. ⚠ It was never load-bearing for the `ref:` decision either: that one turns on release **reproducibility**, not on branch ambiguity, and stands unchanged |
| ~~**FOUR route calls**~~ ✅ **ALL ANSWERED — ROW SWEPT 2026-09-16** (was three — `POST /objects` added 2026-09-15 when step 2b found manual Incident/Case creation is the `/recon/promote` question again; all four sat in `CapabilityManifest.PENDING_OPERATOR_CALLS`, and step 3 waits on them) — `POST /spaces/import` (does the `POST /spaces` "additive, recovery route" decision extend to bundle import?) · `POST /tags/rules/{id}/apply` (operate action → `canOperateRuns`, or collaboration act → open, like assignments?) · `POST /recon/promote` (which family owns *manually opening an Incident*? — `canAuthorWorkbench` has no precedent, `DecisionRoutes` uses `canOperateRuns`, `ExpectationRoutes` is ungated, no Incident capability exists) | `ROUTE-UNGATED-DEFAULT-1` step 2 → **step 3 (the fail-closed default) cannot land until these are decided**. ✅ **THREE OF THE FOUR ANSWERED 2026-09-16:** `POST /spaces/import` → **yes, the `POST /spaces` additive/recovery posture extends to bundle import** (stays open, exempted explicitly by step 3) · `POST /tags/rules/{id}/apply` → **collaboration act, leave OPEN** (consistent with assignments, not with run operation) · `POST /recon/promote` → **create the Incident capability the domain lacks** rather than borrowing `canOperateRuns` or `canAuthorWorkbench`; ⚠ it also gives the currently-ungated `ExpectationRoutes` a home, and ⛔ its dedupe lines are the ones the `(type, key, column)` decision rewrites — do both in one change. ✅ **AND `POST /objects` ANSWERED 2026-09-16: the same new Incident capability**, deliberately — it is the same act as `/recon/promote` (manually opening an Incident) and must not acquire a second precedent. ⇒ **ALL FOUR ROUTE CALLS ARE ANSWERED; `ROUTE-UNGATED-DEFAULT-1` step 3 is UNBLOCKED.** ⛔ Step 3 is still not a pure code change: the Incident capability does not exist in `Roles` yet, so it is created first, and the two Incident routes plus `ExpectationRoutes` adopt it together with the `(type, key, column)` dedupe rewrite. | ✅ **SWEPT 2026-09-16 — NOTHING IS OWED HERE ANY MORE, and the CODE says so**: `CapabilityManifest.java:324` is now `PENDING_OPERATOR_CALLS = List.of()`. All four answers landed in the manifest; what remains (create the Incident capability in `Roles`, adopt it on the two Incident routes plus `ExpectationRoutes`, do the `(type, key, column)` dedupe rewrite in the same change) is **build work already tracked on `ROUTE-UNGATED-DEFAULT-1` (§5)** — ⛔ an answered row left unstruck in §1 is exactly the shape that made a shift look for work and find none. Each was grounded 2026-09-15 and was a genuine question, not a formality — the handler, the comment and the tests pointed different ways. → `archived-documents/plans-archive/route-gating-compliance-plan.md` §2a |
| ~~**Confirm reads-open-by-policy as the COMPLIANCE position**~~ ✅ **AFFIRMED 2026-09-16** — write it into `compliance/controls-matrix.md` CC6: read routes are open **by policy**, because confidentiality is enforced at the Space/ABAC layer. ⇒ the route-gating plan's §3e is unblocked | `archived-documents/plans-archive/route-gating-compliance-plan.md` §3e · `controls-matrix.md` CC6 | ⚠ This is now an auditor-facing CLAIM, not only an engineering posture: if reads are ever gated, the matrix line moves with the code |
| ~~**Which framework(s) the evidence is written against**~~ ✅ **ANSWERED + SHIPPED 2026-09-16: SOC 2 `CC6.1`/`CC6.3` AND ISO 27001:2022 `A.5.15`/`A.5.18`/`A.8.3`**, declared on one `Control:` header line per the pattern `compliance/evidence/release-verification.md` already set (SOC 2 CC8 · ISO 27001 8.24 · NIST SI-2/SR). ⛔ **The Annex A mapping stays in `controls-matrix.md` §2 and is NOT restated in the evidence document** — §2 already declares the ISO Statement of Applicability is an *export* of that table, so a second copy is the drift its own rule 4 exists to prevent | ~~route-gating evidence · `controls-matrix.md` §4a~~ — both updated | 🔴 **This had effectively been answered in-repo for weeks and nobody noticed**: the matrix declares the multi-framework posture at its top and `release-verification.md` practises it; route-gating was the lone document still calling it an assumption. ⚠ **And the "A.9" in the question was the retired 2013 numbering** — ISO/IEC 27001:2022 dissolved old A.9 into A.5.15/5.16/5.18 and A.8.2/8.3, and `controls-matrix.md` §2 is *headed* "ISO 27001:2022", so citing A.9 would have contradicted the very table it points at. ⚠ **Still open and deliberately separate:** the HIPAA/PCI **certification** choice (§2), gated on a named prospect — which frameworks the evidence is *written against* is not which the org *certifies to* |
| ~~**Job path semantics**~~ ✅ **SWEPT 2026-09-16 — this was an UNSTRUCK DUPLICATE** of the answered entry above, and doubly stale: the call was ANSWERED 2026-09-16 *and* `JOB-DIR-CWD-CONTAINMENT-1` **SHIPPED** the same day (§5, struck) | — | Its grounding survives on the shipped row, which is why this could be struck rather than migrated: *"the row's own fix (pass `configDir`) is a no-op"* is discharged by `ConfigSafetyValidator.checkJob` now USING `configDir`, and *"the gate and the run-time tasks BOTH resolve against the CWD"* by `PathJail.resolveJobPath` being the single rule called from both sides. ⛔ Checked before striking — a duplicate is only safe to strike when the surviving copy carries its grounding too |
| **Eager or deferred resolution of an `s3://` `dirs.database`** — validate a profile at PARSE time (forces the deployment-root / pipeline-field split, because `CollectorService` parses every pipeline before `loadConnections` runs) or resolve at FIRST WRITE-TIME USE (no split; `dirs.database` is a plain `String` nothing resolves at parse) | scale-out phase C §5.4 bullet 6 (the credential surface that `AIRGAP-S3-EXTENSIONS-1` left behind when it closed 2026-09-15) | The bootstrap order is measured (`ServiceBootstrap.buildFrom:69` vs `:73-74`); which side of it to build on is a design posture only the operator sets. |
| ~~**Three verdicts for `SPEC-DEADSEAM-1`'s survivors**~~ ✅ **ALL THREE ANSWERED — (2) on 2026-09-16, (1) and (3) the same day. `SPEC-DEADSEAM-1` IS CLOSED (§4).** (1) `ExpressionProvider` → **KEEP the SPI unchanged.** 🔴 The question's own premise was wrong: "never-registered third-party extension point" is not the state. `ServiceLoader.load(ExpressionProvider.class)` is called TWICE — `JobService.java:562` (base classpath) and **`JobPackManager.java:209`, registering per-pack at `:243` through an isolated classloader** — and `JobPackManagerTest.java:421` compiles a synthetic provider into a fake pack jar to prove that path works end to end. ⇒ **zero in-repo registrants is the DESIGNED state of a hot-deploy seam**, not evidence of death; Job Packs register at runtime from outside the repo. Retiring it would have deleted a capability with a passing test. (3) `LegacyVendorFunctions` → **NOTHING TO DO — the documentation the verdict asked for already exists**: `PLUGIN_GUIDE.md:41` names the module "the worked example", `:58` and `:74` extend it, `:81` points readers at its test, and `asn-decoders/README.md:105` + `CONFIG_REFERENCE.md:77` carry it too. ⛔ Do not do doc work to satisfy a premise grounding refuted. | ~~`SPEC-DEADSEAM-1` (§4)~~ — CLOSED | 🔴 **TWO of this row's OWN supporting citations were false, and both are corrected on the §4 row**: `RTDMS_ASN_Test` is **not a test** (no `@Test`; its one uncommented `main()` path, `pgwParse()`, never touches `LegacyVendorFunctions`) — the real automated links are `asn-golden`'s runtime-scope dependency (`pom.xml:43-47`) and `LegacyVendorFunctionsTest`'s own `@Test`s; and the `*_tx.json` configs calling `ccnEventType(...)` are **untracked** (`asn-parser/.gitignore:12`). ⚠ `LegacyVendorFunctionsTest` is plain JUnit, not environment-gated, and DOES run — but in **CI's 26-module coverage run**, not in the 14-non-asn-module local baseline, so a green local gate says nothing about it. → `RTDMS-ASN-HARNESS-1` filed (§4) for the dead harness itself |
| ~~**Approve or decline pinning a `ref:`** in `ci.yml` / `release.yml`~~ ✅ **ANSWERED + SHIPPED 2026-09-16: pin `release.yml` ONLY** — `ref: a24817ba6151245c3f0ddacf9886954ef68f260d` (upstream `main` @ 2026-09-14, whose pom declares the `0.2.0-SNAPSHOT` this repo's `<eoiagent.version>` requires). **`ci.yml` stays unpinned DELIBERATELY**, and both files now carry the reason so nobody "fixes" the asymmetry | ~~nothing~~ → `compliance/evidence/release-verification.md` (SOC 2 CC8 · NIST SI-2/SR) | **CI is the canary, the release is the contract.** ⛔ The real exposure was never the stray branch — it is that **re-running the release workflow for one tag could emit different bytes**, i.e. no tagged release has ever been reproducible. Pinning both would hide upstream drift until release day, the expensive place to find it. ⚠ **Do not pin to the upstream `v0.1.0` tag**: it predates `0.2.0-SNAPSHOT` and was NOT verified to satisfy the dependency — bumping the SHA means re-checking upstream's pom first |
| ~~**Pre-materialise cap: the UNIT and the REMAINDER policy**~~ ✅ **SWEPT 2026-09-16 — this was an UNSTRUCK DUPLICATE** of the answered entry above (bytes; the remainder defers) | — | ⚠ Unlike the job-path duplicate, this one carried grounding the answered copy did **not** have, so it was **migrated, not deleted**: *"nothing exists to extend — `IntakeGovernor` caps AFTER listing, a pre-fetch cap is a connector-SPI question"* now lives on the **§3 Pipeline graph** row that has to build it |

✅ **2026-09-23: the five decisions filed here that day — `AUTHORING-REDESIGN-1` (c) Q1–Q5 — were all answered the same day** (recorded in the archived `authoring-ast-table-design` plan's §7, `420cdfcd`), and their rows were removed. §1 is back to all-answered, so the paragraph below (the 2026-09-16 state) holds again.

✅ **Every DECISION is answered.** The six queued on 2026-09-14 were answered that day; the duckle triage
(nine candidates adopted incl. C7, C5 struck), the route-gating approach and the `JAVA-INGEST-APPENDER-
SERIAL-1` closure were all decided on 2026-09-15, and the thirty-eight below were answered the same day.
Every answer is recorded on its owning row.
⚠ §1 reporting "empty" is only meaningful if decisions are FILED here when they arise — that assumption
failed once already (three sat unfiled in a plan from 2026-09-11 to 2026-09-14).

⚠ **Read the closure notes below before re-filing anything**: §1 reported itself empty in every handoff
from 2026-09-11 to 2026-09-14 while three of these six sat unfiled in a plan that reported itself blocked.
⛔ **A plan is where a decision is *described*; this section is where it is *queued*.** Describing one in
`superpower/` and not filing it here is what produced that three-day stall, and it is the only way it can
happen again.

**Answered 2026-09-15 (second sitting) — THIRTY-EIGHT, in one pass.** ⛔ **This table is an index, not the
record**: every answer is also written onto its owning row, which is where the reasoning and the *accepted
costs* live. It exists so the next shift can see the whole sitting at once instead of reconstructing it
from thirty-eight separate rows.

| # | Question | Answer | Owning row |
|---|---|---|---|
| 1 | Incident/Case triage gating (~15 routes) | **Gate the STATE-CHANGING routes only** — resolve/close/assign/reopen/promote; comment/annotate stay open, exemptions recorded | `ROUTE-UNGATED-DEFAULT-1` (§5) |
| 2 | Do reads get a capability posture? | **No — reads stay open by design, stated as policy**; confidentiality sits at the Space/ABAC layer. The ratchet covers the **83 mutating** routes; the 34 read-shaped POSTs are **EXEMPT as reads**, not deferred | same |
| 3 | The 10 remaining gateable-now routes | **Ground all 10 FIRST**, report a per-route verdict, then code — 2 of the first 4 attempts were deliberate exemptions | same |
| 4 | Ownership/identity in the auth-free core | **Owner = the authenticated `Subject` where one is attached, `"appUser"` where none is** — one design pass, mirroring how `requireCapability` degrades to a no-op on Personal | `DUCKLE-C1` · `DUCKLE-C4` (§5) |
| 5 | A Break's Incident identity | ✅ SHIPPED 2026-09-15. **`(type, key, column)` — full parity** with the client's `breakId`. ⚠ Accepted cost: a value Break and a missing-row Break on one key+column become separate Incidents | `BREAK-DEDUPE-GRAIN-1` (§3) |
| 6 | What does `producer` identify? | ✅ SHIPPED 2026-09-15. **A structured `Ref` `{kind, id}` plus the owning pipeline** — all three emit sites corrected | `DATASET-SELF-TRIGGER-1` (§5) |
| 7 | May retention prune resume state? | ✅ SHIPPED 2026-09-15. **Never prune below the high watermark** — floor the sweep; no schema change | `LEDGER-PRUNE-EATS-RESUME-STATE-1` (§5) |
| 8 | `batches` vs a per-schema row set | ✅ SHIPPED 2026-09-15. **A child table** for per-schema outputs; `batches` stays one row per ingest | Consignment ELT (§3) |
| 9 | Execute an intervening node at rest? | **YES — `EXECUTED` nodes anywhere**; fusion may break mid-graph | Platform Services Stage 2 (§3) · §2 Row 15 |
| 10 | ELT Phase 6 prerequisites | **Converter + parity gate NOW; the release is a SEPARATE call** — ⚠ including the `v3.12.0` name collision | §2 Row 15 |
| 11 | Intake caps ✅✅✅ but off by default | **Fix the CELL now**, soak separately, flip the default only on the soak result | ✅ cell fixed: `EDITIONS.md` `JOB-04`; the soak stays open on §3 Pipeline graph |
| 12 | What does a ✅ cell mean? | **"Present and usable"** — gating is documented in `security.md`'s capability vocabulary, so Personal's ✅ is correct as written and no cell changes | ✅ SHIPPED: `EDITIONS.md` §Matrix legend |
| 13 | The `.docx` generator | **Delete it; convert from the committed `.md`** — check its styling first so nothing is silently lost — ⚠ **amended on that check**, see the note below the table | ✅ SHIPPED: `scripts/generate_whitepaper_docx.py` |
| 14 | Has the stale `.docx` been distributed? | **No — it never left the team.** No corrective action; the row is purely the generator change | same |
| 15 | ~45 MB of cross-platform deadweight per zip | ✅ BUILT + EVIDENCED 2026-09-15 from the zip entry tables (as-built: `okf/capabilities/tooling/tooling.md`); row closed. **Approved — filter the EXTENSION directory only**; launchers stay cross-copied; evidence from the zip entry tables | `AIRGAP-CROSSPLAT-DEADWEIGHT-1` (§5) |
| 16 | Where do S3 endpoint/key/region live? | ⚠ CAVEAT FIRED 2026-09-15 — profiles resolve AFTER pipeline parse, so parse-time validation forces the split; deferred resolution avoids it. Operator's call. **`ConnectionProfile` for BOTH** the pipeline field and `dirs.database`. ⚠ Bootstrap ordering to be verified — if profiles cannot resolve early enough, the split is forced | `AIRGAP-S3-EXTENSIONS-1` (§5) |
| 17 | Which Step Processor partial? | ✅ SHIPPED 2026-09-15 as `transform.profile`. RE-TAKEN: **Profiler STANDS** on its own merit, the double-duty reason having been refuted (DUCKLE-C8 is an Expectation kind templated on `FileSequenceGaps`; no Profiler code exists at all). **Profiler** — it is also `DUCKLE-C8`'s prerequisite, so it does double duty | Step Processor catalog (§3) |
| 18 | Four dead seams | 🔴 REFUTED 2026-09-15 — THREE of the four are ALIVE (vendor plugin is called by production tx configs; `ExpressionProvider` IS the expression engine; `temporalColumn` is cited by an active plan). Only `AssistDialog` deleted. ~~**DELETE all four.**~~ ⚠ The vendor plugin is grounded for out-of-repo references before removal — it is the irreversible one | `SPEC-DEADSEAM-1` (§4) |
| 19 | The orphaned derived-schema panel | ✅ SHIPPED 2026-09-15. **Wire it into the schema authoring pane** | `DERIVED-SCHEMA-PANEL-ORPHAN-1` (§3) |
| 20 | SOC 2 window start date | **Not open** — record it as NOT STARTED with what must be true first, so the gap is checkable instead of silent | §2 SOC 2 |
| 21 | `SEC-INCIDENT-1` carry-forwards | **Both DONE** — write the dated CC6.1 line. ⚠ Two dates owed (§1 above) | §2 SEC-INCIDENT-1 |
| 22 | `DATA-GOV-1` archive | **Not done** — record as outstanding | §2 DATA-GOV-1 |
| 23 | RTO/RPO placeholders | ⛔ **Do NOT transcribe** the signed §3.14 numbers — the operator supplies the compliance targets | §2 Deployment topology |
| 24 | Test hosts | ✅ **ALL THREE available** — live S3/MinIO, a systemd host, an elevated Windows box | `AIRGAP-S3-EXTENSIONS-1` · `DEPLOY-SERVICE-WRAPPER-1` |
| 25 | Publish eoiagent `0.1.0` to a registry? | **NO — CI keeps building it from the upstream tree**; closed as a standing refusal. 🔴 This makes the unpinned `ref:` permanent | §6 (was §2 `EOI-7b`) |
| 26 | D13 parser field tiers session | ✅ **A real onboarding user IS available** — re-ground the kit against post-`d012f721` first, then schedule | §2 D13 |
| 27 | Authoring/UI demand gates | **ALL FOUR FIRED** — AI drafting on a non-`schema` kind · empty-list authoring · `findings-spec` UI · policy-authoring UX | four §3 rows |
| 28 | Data/format demand gates | **XLSX export** and **a real delete-feed** fired. ⛔ xz/zstd and `D-11` did **not** | §3 rows |
| 29 | Ops/analytics demand gates | **ALL FOUR FIRED** — recon pairing · space-to-space comparison · MTTD · false-stale | four §3 rows |
| 30 | Scale/cross-space demand gates | **ALL FOUR FIRED** — cross-Space consequence · multi-operator install · a second code-registered dataset producer · a quarantine-replay driver | §3 rows |
| 31 | Secrets reaching logs unmasked | ✅ RE-VERIFIED 2026-09-15 — `SecretMasking` + a 170-line test exist; JobService's run-log line and ParameterResolver's messages are both masked. Nothing to rebuild. **SPLIT OUT and fix both paths now** — filed as `PARAM-SECRET-LEAK-1`, ✅ **BUILT and retired the same day** | as-built: `okf/backend/control-plane/jobs.md` §ParameterDecl |
| 32 | Which adopted duckle row first? | ✅ RESCOPED 2026-09-15 to **the OBSERVABILITY half only** — the poll/Run conflation it named was already fixed in `1fda46d5` (2026-09-13), before the row was adopted. ⛔ The “stop conflating polls and runs” framing is struck. **`DUCKLE-C9-WATCHER-NOT-A-RUN-1`** | its §5 row |
| 33 | The unmeasured Java-lane ratio | **Soften to a qualitative statement** — no benchmark run, and the dead citation goes with it | ✅ SHIPPED: `stakeholders/COMPETITIVE_LANDSCAPE.md` §1.3 |
| 34 | D8 notification residuals | ✅ SHIPPED 2026-09-15 (`soft_bounce_retry` task; two traps the decision did not name — see the row). **Soft-bounce retry ONLY**; the SES/SNS adapter stays filed with its own review | Notifications (§3) · `D8-SUPPRESS-1` (§3) |
| 35 | The ≈44 orphan pages | ✅ DONE 2026-09-15 — ≈44 NOT confirmed (measured 57; method dominates); `SPEC-STALEREF-1` = 23 stale citations, CLOSED 2026-09-09. **Confirm the count, AND re-derive what `SPEC-STALEREF-1` was** — ⚠ it has no row anywhere, yet this row defers to it | `SPEC-ORPHANPAGE-1` (§5) |
| 36 | The graphify skill divergence | **Re-sync from the package** — ✅ **DONE**; the repo copy is now byte-identical to `site-packages/graphify/skill.md`. ⚠ The operator reaffirmed after the premise was shown false, so the PowerShell port was traded away deliberately — see the note below | `GRAPHIFY-1` (§5), now tracks whether the port comes back |
| 37 | What is "sandbox execution"? | **Dry-run: execute fully, discard ALL writes.** New scope, filed as `PIPELINE-DRYRUN-1`; `X4` is scoped against it | new §3 row |
| 38 | What leads this shift? | **The secret-masking fix, then the P1** | §0 |

🔴 **TWO of these thirty-eight were answered on a premise that did not survive contact with the
work. Both are recorded here and on their rows rather than quietly re-done.**

**(13) the `.docx` generator** was to be *deleted* and the document produced *by conversion*. ⛔ The
styling check that the answer itself called for is what found the blocker: **no converter exists in this
environment** — pandoc, soffice and libreoffice are all absent, and only `python-docx` is installed — so
"delete it and convert" was not implementable as written. ⇒ **the generator was rewritten to RENDER the
committed `.md`** through the styling helpers it already had (kept verbatim; 593 → 370 lines). That
reaches the decision's intent — the `.md` becomes the single origin, so every guard over it now covers
the `.docx` by construction — without losing the branding or depending on a tool nobody has.
✅ **Proven, not assumed:** the stale `.docx` contains **`many-to-many`**, which appears in **neither**
the current `.md` nor the new render. That is precisely the claim the v1.2 rewrite deleted on
2026-09-11 — still being made by the file a stakeholder would have been handed. ⚠ The old file was also
**abridged**, not merely stale: 26,012 characters against the markdown's 38,371.
⚠ A quieter instance of the same drift went with it: the generator printed a literal `PAGE n OF 10`
while the markdown carries **11** top-level sections. The count is now derived from the source.

**(36) the graphify skill re-sync** was ✅ **performed — after its premise was shown false and the
operator reaffirmed the decision anyway.** ⚠ Recorded because the trade is not the one the answer was
given for: the copies differ by **shell dialect**, not by a missing fix — **18 ```bash blocks in the
package against 18 ```powershell in the repo**, the same count, because the repo copy was a 1:1 Windows
port. ⇒ the re-sync **deliberately traded a working PowerShell port for the upstream bash text** on a
win32 sandbox. ⛔ Do not read this as "the divergence was drift that got cleaned up"; it was a port
that was given up, and whether it comes back is now what its row tracks.

⚠ **Three answers carry a stated cost that was ACCEPTED, not overlooked** — ⛔ do not "fix" any of them
later without reopening the decision. (5) `(type, key, column)` parity **fragments** Incidents: a value
Break and a missing-row Break on the same key and column now open separate ones. (9) allowing `EXECUTED`
nodes anywhere **breaks SQL fusion mid-graph**, at a cost the S2-2 spike has still not measured — the one
adjacent measurement spans a half to a thirteenth of native. (36) re-syncing the graphify skill **deletes a
fix the installed package does not carry**; the mitigation is that the block is recoverable from the commit
message, not that the loss is imaginary.

🔴 **One answer overturned the question's own framing and is worth keeping as a pattern.** Asked what
`X4`'s per-pipeline replay default should be, the operator answered neither option: *scope it against a
sandbox — a test/sandbox execution option would be nice*. ⇒ the config question was **premature**, because
the safe place to answer it did not exist. ⚠ **When a question forces a choice between two risky defaults,
the missing third answer is often a way to make the choice cheap** — that is `PIPELINE-DRYRUN-1`.

**Answered 2026-09-14 — the six, with where each now lives:**

| Decision | Answer | Recorded in |
|---|---|---|
| **Scale-out §5.4 — how does a PIPELINE name object-store credentials?** | **(a) a pipeline field naming an existing `ConnectionProfile` id** | `superpower/enterprise-scale-out-plan.md` §5.4 |
| **`STUDIO-HALVES-1` — how does a Dataset page invoke a materialization?** | **(a) add a materialize route** | its §3 row |
| **`AGT-ARTIFACT-1` — which artifact kind carries a draft?** | **(a) add a `draft` kind** | its §3 row |
| **`TYPEFLOW-DATASET-COLUMNS-1` Q2 — temporal tie-break** | **(a) several date columns ⇒ derive `temporal` for NONE** | `archived-documents/plans-archive/dataset-column-derivation-plan.md` §6 |
| **`TYPEFLOW-DATASET-COLUMNS-1` Q3 — a stored column the derivation stops producing** | **mark `hidden`** | same, §6 |
| **`TYPEFLOW-DATASET-COLUMNS-1` Q4 — scope of the contract pin** | **both the heuristic AND the coarse type vocabulary** | same, §6 |

⚠ **Two of these carry a stated cost that was accepted, not overlooked** — do not treat either as an
oversight to be "fixed" later without reopening the decision: pinning the coarse type vocabulary (Q4)
makes it **a compatibility surface that cannot change freely**, and `hidden` (Q3) **accumulates state with
no pruning story**, deliberately deferred until a real tree has enough hidden columns to inform one.

⚠ **The credentials answer has a prerequisite the answer itself does not carry:** route (a) reuses
`SecretResolver` and `ConnectionProfile` redaction whole, but it must still **carve an exception into the
`dirs.*` URI refusal shipped in `454d1a6a`**, which today rejects every object-store path on every
platform. ⛔ Dispatch on `PathJail.isUri` — do not delete it; a bucket URI is not containable by `Path`
comparison and needs its own rule.

*(2026-09-14: **`SBOM-EOIAGENT-LICENCE-1` DECIDED, FIXED and CLOSED same day** — the release is
unblocked. Operator took route (a), **declare the licence upstream**, and stated that `com.eoiagent`
and `com.gamma.inspector` are the **same legal entity**, which retired option (c) as unnecessary.
Licence chosen: **Apache-2.0**. Shipped as `jotder/inspect-agent@a24817b` — one `<licenses>` block on
the `eoiagent-parent` aggregator plus the verbatim 11358-byte `LICENSE` at that repo's root, so the
grant the POM advertises is actually present in the tree. ⚠ **This was a real grant, not a metadata
fix**: both repos were public with `licenseInfo: null`, i.e. all-rights-reserved by default, so
declaring Apache-2.0 conferred rights that did not previously exist. It was put to the operator in
exactly those terms and chosen twice.
**Verified end-to-end, not merely written:** `package.ps1 -Edition Standard` now completes, **real exit
code 0** — fat jar, UI, SBOM, both jlink runtimes, boot smoke (`the staged Standard bundle boots and
answers /health`), hashes and both zips. The SBOM step reports `48 third-party + 11 first-party
component(s)`, and `licenseDeclared` is `Apache-2.0` for both `eoiagent-core` and `eoiagent-model`.
⚠ The SPDX doc still shows ~192 `NOASSERTION` hits: those are `downloadLocation` and
`licenseConcluded`, which are SPDX convention for Maven-resolved deps — **the gate reads
`licenseDeclared`**, so do not read that count as a regression.
🔴 **Three traps recorded from this row, all about probes rather than the finding:**
(i) the "10-second repro" `node tools/sbom.mjs --edition Standard --bundle inspecto-deploy` is
**bundle-state-dependent** — it reports `inspecto-security.jar is not in the bundle` whenever
`inspecto-deploy/` last held a *Personal* build. The row already recorded that exact probe-fault for
Enterprise and it was then hit again from the other side. ⛔ **The resolve is the whole input to the
gate; check that, not a staged directory.** Replaying sbom.mjs's own `dependency:list` + POM licence
walk proved Standard and Enterprise resolve **the same 48 third-party components with the same 2
unlicensed** — settling, without any packaging, the "expected to fail identically but not proven"
claim the row carried.
(ii) **the cause was not an omission but a boundary.** `eoiagent-parent` excluded `com.eoiagent` from
its *own* licence plugin as "first-party, not third-party to vet" — true inside that reactor, false
outside it, where the same groupId is third-party by definition. Two locally-coherent policies
produced the failure, which is why "just add the line" understated it: it reversed a stated upstream
stance.
(iii) ⛔ **assume nothing about another repo's default branch.** The upstream push was aimed at
`master` out of this repo's habit; `jotder/inspect-agent`'s default is `main`, so the first push
created a stray `master` branch there instead of landing. Neither `ci.yml` nor `release.yml` pins a
`ref:`, so both take the default branch — the commit only reaches CI because it was re-pushed to
`main`. ⚠ **A stray `master` branch may still exist on `jotder/inspect-agent`**; deleting it needed a
permission this shift did not have. Check and remove it.
The gate itself was left alone throughout — it is `compliance/controls-matrix.md` CC9 and it did its
job.)*

*(2026-09-12: **`RECON-CARDINALITY-1` DECIDED — BUILD IT.** Operator chose to build one-to-many /
many-to-many recon matching rather than drop the phrase from whitepaper §4. Moved to §3 as a build row.
**§1 was EMPTY again** — every operator decision on the board was answered (⚠ briefly untrue on 2026-09-14, when `SBOM-EOIAGENT-LICENCE-1` was filed; it was decided and closed the same day and §1 is empty once more — see the note above).
🔴 **CORRECTION, later the same day: this decision was taken on a false premise and its two warnings are
struck.** The phrase was **already gone** — whitepaper v1.2 (`db11a412`, 2026-09-11) deleted it during a
market-focused rewrite, a day before the decision was recorded, so "build rather than drop" offered as the
alternative something already done, and "the brochure stays untrue until it lands" was never true. ⛔ The
"do not quietly soften the whitepaper" instruction had itself been violated before it was written — by a
rewrite that was not reviewed against the rows depending on that sentence. ⚠ **A doc rewrite silently
retracts the claims other rows are tracking**: when a stakeholder doc is rewritten, re-ground every board
row that cites it. The build decision stands as a feature call and deserves re-confirmation on the
corrected basis; see the §3 row.)*

*(2026-09-12: four more decisions answered in one sitting, all recorded on their own rows in §4 —
`CONSIGNMENT-ID-DETERMINISTIC-1` (paths+sizes digest, no checksum), `POD-SCOPE-DIVERGENCE-1` (declare
per-pod scope in payloads), `INBOX-REGISTRY-CROSS-POD-1` (shared ops-DB registry), and
`JOB-PIPELINE-PARAM-UNIQUE-1` (warn only — closed). Plus **D4 SIGNED**: option (ii), object store +
DuckLake catalog on Postgres — see the scale-out plan §5.4.)*

*(2026-09-12: **`PKG-5` DECIDED and SHIPPED same day.** Operator: the assistant ships **Standard and
Enterprise, as an optional component** — taken first as "all editions, optional" and narrowed once the
sidecar's weight was measured. 🔴 **The row's stated blocker was the wrong one.** It read "resolve the
JDK 25+ vs Java 24+ floor". The floor is real (every `eoiagent-*` jar is class-file major 69, measured)
but was never the blocker: `CollectorService.start()` called `ServiceLoader.load(AssistAgent.class)`
UNGUARDED, and `UnsupportedClassVersionError` is an `Error` that `ServiceLoader` propagates rather than
wrapping — so staging the jar would have made the **entire server fail to boot** on a Java 24 host because
an OPTIONAL component could not load. No `LinkageError` handling existed anywhere in `inspecto`'s main
tree. `OptionalSpi` now makes unloadable an ABSENCE at all six discovery sites; the bundle's stated Java
requirement stays **24**. `inspecto-agent` gained a shaded `sidecar` artifact (a thin jar would have been
present-but-unlinkable, i.e. installed-looking and dead) with `inspecto-processor` scoped `provided` —
without that the sidecar swallowed the whole core, 98 MB against 3.5 MB. Staging verified by BUILDING both
bundles, not by reading the generator: Standard carries `inspecto-agent.jar`, Personal does not.
⚠ The classpath list had **FIVE mirrors** in `package.ps1`, not one.)*

*(2026-09-10, later: `CONSUMER-PAIRS-1` decided **per row, in one sitting** — seven ADOPT, one KEEP-as-API, two RETIRE; verdicts in each owning spec's §2, work grouped into five §3 rows: `CLIENT-HALVES-1`, `EXPECTATIONS-UI-1`, `STUDIO-HALVES-1`, `AGT-ARTIFACT-1`, `RETIRE-HALVES-1`. **§1 is now EMPTY** — the first time since the board was consolidated.)*

*(2026-09-10: six decisions closed in one sitting — `MAPPING-SPELLING-1` both halves → §3 `MAPPING-GEN-1` + §6; OpenAPI posture → §4 `OPENAPI-GEN-1`; Enterprise self-identification → §3 `STANDARD-BUNDLE-1`; `AGT-SEGMENT-1` keeps its caveat with a named trigger; drift refusal → §6; `CONTRACT-ORPHAN-1` was moot — its "no producer, no consumer" premise was a false negative, the producer test and consumer had existed since 2026-08-15.)*

*(Previously:)* All 28 rows were decided on 2026-09-06 in one sitting; every answer is recorded in its owning doc (grep
`Decision 2026-09-06` / `Decided 2026-09-06` / `Ratified 2026-09-06`), and the work each unblocked is ranked below — P1 where it was
decided and buildable, §2 where it became an org action, §6 where the answer was "keep as designed". Three rows
turned out to be already answered by shipped code (`description`, `duplicate_check` owner, `engine: auto`) and one
half of a fourth (the id slug). New decisions get a row here at handoff time.

## 2. Externally gated

Nothing a shift can close from this checkout. Listed so the gate is named, not guessed.

**Gates RUN on 2026-09-07** (second pass), not just read. 🔴 **Tally corrected the same day — the first
version of this line said "13 of 15" and was wrong twice.** This table has **16** rows, and **4 of them
cannot be checked from this checkout at all**, so counting them as "run" was the same over-claim the pass
was supposed to remove.

**Swept again 2026-09-08 — and one gate FELL.** **Accurate: 13 of the 14 gates were RUN here.** Plus 2
gates outside this section (§3 Platform Services Stage 2, §5 D8-SUPPRESS-1), also run. The 1 not run, and
why: **Completeness KPI hold** has nothing to run, because its gate turned out to be an engineering action,
not a check.

🔴 **Recounted 2026-09-15 — the table is 14 rows, not 15, and "all still gated" is no longer true.**
`EOI-7b` LEFT this section: the operator decided CI keeps building eoiagent from its upstream tree, so it is
a standing refusal in §6, not a gate awaiting an event. And **D13's gate has FIRED** — a real onboarding
user is available, which is the one thing that row has waited on since it was written. ⛔ **A section that
reports "N still gated" must recount when a gate fires, not only when a row is added** — the arithmetic
guard below checks run-vs-not-run, and would have let "all 13 still gated" through while it was false.

🔴 **AGT-5's gate was DISCHARGED and the row moved to §3** — the upstream seam it waited on has shipped.
And 🔴 **the command this section told a shift to run could never have found it.** `gh search code
'DryRunProvider' --repo jotder/inspect-agent` returns **zero hits for every term**, including a control
search for `class` against a repo with 375 `.java` files — GitHub's code-search index does not answer for
that repo, so "no hit ⇒ still gated" was **unfalsifiable by construction**. This is the *second* instance
of that defect in this very section (Row 15's was the first). ✅ **Replacement check that CAN return a true
positive** — the git-tree API, which needs only read access:
`gh api 'repos/jotder/inspect-agent/git/trees/main?recursive=1' -q '.tree[].path' | grep -c DryRunProvider`
(control: `| grep -c '\.java$'` must be ~375, else the probe itself is broken).
⚠ Both AGT rows' `gh search code` checks are replaced below. **Re-run every gate with a probe that can
succeed before trusting a 0.**
⚠ A gate you cannot run is not a gate that holds — it is a gate with no evidence either way, and the two
must not be summed.

**This arithmetic is now enforced.** `tools/check-gate-tally.mjs` fails the build when the sentence above
disagrees with the table below — it caught this very paragraph going stale within minutes of being
written. It counts rows, counts `NOT RUN` markers, and requires run + not-run to account for every row
exactly once. ⛔ Fix the SENTENCE to match the rows, never the rows to match the sentence.

🔴 **Two of the four "unrunnable" rows were fixed rather than accepted** (2026-09-07). **NFR-7** was the
only gate on the board with no repo-side check at all; it now has one landing row per sub-item in
`compliance/controls-matrix.md` §4, checked by `grep -c '^| NFR-7 ·.*⬜ open'` (7 today, closes at 0).
**AGT-5/AGT-6b** were never "external" — the upstream is a public repo CI clones on every run; they were
merely un-run, and now name the command. ⚠ The anchoring in that grep is load-bearing: unanchored it
returns **9** for **7** rows, because the prose stating the check matches the check's own pattern.

There was **1** correction, not 2 (Row 15's, below). The `git tag` gates are unmoved (newest master-ancestor tag is `v3.11.0`;
`v3.12.0` is on the retired `3.x` line). OPS-5's outcome log exists but says `_(empty — awaiting the first
live deployment)_`. `rto-rpo-statement.md` still carries 2 `<OPERATOR TO STATE>` placeholders. The
controls-matrix has **no** SOC-2 window start date and **no** dated CC6.1 line for the SEC-INCIDENT-1
carry-forwards. `PROJECT_NOTES.md` §DATA-GOV-1 records the decision but no archive location or fetch-script
path. `ci.yml` still builds `jotder/inspect-agent` from source (3 references). The interview plan has no
session record. No prospect is named anywhere. 🔴 **The correction that matters: Row 15's gate was
unfalsifiable BY CONSTRUCTION** — see its row. Writing a gate as a command is not enough; the command has to
be one that CAN return 0.

**Rewritten 2026-09-07.** Every gate now states something a shift can CHECK from this repo. Before, most
named an event nobody watches for ("a live deployment", "demand", "a client policy") — unfalsifiable by
construction, so the row could never move and nobody could tell whether it should. Where the trigger is
genuinely external, it is now phrased as **"when X is recorded in \<file\>"**: naming the landing place
turns an unwatchable event into a file check. ⚠ Two rows also cited evidence that was not where they said
it was; both are corrected below.

| Item | Remains | Gate — how a shift CHECKS it |
|---|---|---|
| **Row 15 — ELT Phase 6 deletion half** | Delete the legacy flat read path (amendment §6 step 4). 🔴 **Two earlier §6 steps were never on this board and are added 2026-09-10 (Sprint 7.5): step 1, the one-shot converter, is UNBUILT** — the plan claimed *"✅ the converter exists (`inspecto migrate-configs`)"* and that command appears in **no source file**; the commit it cited was a `RecipeConverter` projection fix. **And step 2, the parity gate, is unverified** — ⛔ do not read `RecipeConverterTest` as meeting it: that proves *round-trip parity of the projection*, while step 2 asks for the full suite **EXECUTING** through the compiled-recipe path. Different claims, only the first evidenced. The `-Dingest.lane=auto\|graph\|flat` flag exists (`ConsignmentIngestStrategy.admittedLift`, 2026-09-02); the verification minor must SHIP first. ⛔ Not closable by code; ⛔ do not start it on momentum. Then `withMappingContext` → `PipelineLift` comes due only if the graph lane executes the map node. The waves board is 16 of 17. Also absorbs RECORD-TRANSFORMER-1 (d): the ingest lane runs exactly one projection slot, so a second `transform.sql` cascades only once the graph lane carries ingest. | 🔴 **Gate CORRECTED 2026-09-07 (second pass) — the one I wrote a few hours earlier was itself unfalsifiable.** `git merge-base --is-ancestor v3.12.0 master` can never return 0: **`v3.12.0` is a tag on `origin/3.x`**, a retired line, cut 2026-06-05 — it is not an ancestor of master and never will be. The newest master-ancestor tag is **`v3.11.0`**. D-2 (`elt-final-amendment-plan.md` §9) says "Converter + **one flagged verification minor**, then the legacy readers are deleted", so the gate is a MINOR **on master** after v3.11.0 that ships the converter and the `-Dingest.lane` flag. **Check: `git tag --merged master --sort=-v:refname \| head -1` — still `v3.11.0` on 2026-09-07 ⇒ gated.** 🔴 **Step 2 EXECUTED 2026-09-16 — parity NOT met**: `mvn -Dingest.lane=graph test` (switch wired through the root pom) turns 13 engine tests red for two named reasons — sink-count mismatch on the multi-schema fixtures, and a Decision Rule routing rows. Both are graph-lane gaps, not test defects; the flat lane stays. ✅ **BOTH SHIPPED 2026-09-16 — and STEP 2 IS MET**: the whole suite under `-Dingest.lane=graph` is **4603 / 0 / 0 / 28, BUILD SUCCESS, zero refusals**. ✅ **STEP 1 IS BUILT the same day**: `inspecto migrate-configs` exists (`ConfigMigrator` + `MainApp`), plans by default, refuses rather than loses, and was driven on a copy of the real demo space (9 conversions applied, all 5 recipes compiled). ⚠ **But step 1 is not yet DISCHARGEABLE for a space that owns an enrich config**: the converter refuses `*_enrich.toon` and a refusal fails the whole migration, so the demo space cannot migrate — see `MIGRATE-ENRICH-1` / `MIGRATE-MATERIALIZE-1` (§3) and the one §1 call they share. ⛔ So the deletion half is still NOT startable — and 🔴 **the release gate itself became UNREACHABLE AS WRITTEN on 2026-09-16, re-grounded the same day.** D-2 says *“Converter + **one flagged verification minor**, then the legacy readers are deleted”* and §6 step 4 puts the deletion **at the MAJOR** — so the flag is meant to SHIP in a release, be exercised, and only then does the deletion land. Two facts now collide: (a) no release has been cut since the flag existed — `git tag --merged master --sort=-v:refname | head -1` is still **`v3.11.0`**, so the verification interval has never happened; (b) master now carries a **`feat!`** (the route-gating control, `3bb3e1d9`), so under SemVer + Conventional Commits the next release from master **must be a MAJOR** — a “verification minor” can no longer be cut from master at all. ⇒ **This is an operator call, not a shift's:** amend D-2 so the MAJOR itself is the flagged verification release and the deletion waits for the one after it · or cut the MINOR from a branch point before the breaking commit · or defer the break out of this release. ⛔ Do NOT cut a release labelled a MINOR from master while a `feat!` sits on it, and ⛔ do not read “the flag exists” as “the verification minor shipped” — they are different claims and only the first is true. 🔴 **CORRECTED the same day, and the correction matters: the `feat!` was NOT the binding reason.** `pom.xml` on master reads **`4.0.0-SNAPSHOT`** and did so at every commit in this run — so the next release from master is **4.0.0, a MAJOR**, and a “verification minor” was **never available from master at any point**, with or without a breaking change. The only line that could carry a `3.12.0` is the **retired `3.x`**, which `BRANCHING.md` forbids tagging outright. ⚠ And `v4.0.0` / `v4.0.0-RC1` were themselves **tagged and deleted** with the `4.x` branch (operator, 2026-08-17) because 4.0.0 never reached production — so that number has been used and withdrawn once already. ⇒ **Exactly one path remains and it is an operator call: amend D-2 so the MAJOR itself is the flagged verification release, with the deletion waiting for the release AFTER it.** That satisfies what D-2 actually wants — converter + flag shipped and exercised in a real release before the legacy readers go — and ⛔ tagging an earlier commit buys nothing, since it would build the same 4.0.0 artifacts. |
| **Release notes for the next MAJOR** | **Drafted** in `okf/backend/control-plane/api-stability.md` §Release notes; append there with every further `feat!:`. ⚠ The `batch_id` rename trio rides this release and is NOT enumerated in that list — see §7. 🔴 **`RETIRE-HALVES-1`'s route removals (2026-09-14) ride this MAJOR and are typed `docs:` in git** — they landed in `519673a7`, a commit whose message describes a whitepaper change, because a concurrent session committed the staged tree under its own heading. They are enumerated under *Breaking — HTTP routes REMOVED*; ⛔ a `git log` scan for `feat!:` will not find them. | `git tag` — the next MAJOR tag. |
| **X5 cross-lane drill-down + StepInfo envelope** | One-Consignment drill-down across lanes; ~1 KB pointer+schema+diagnostics envelope, failure routed by PORT. Phase-7 convergence. | `git tag` — the next MAJOR (pipeline-spec §13 D2). → `okf/backend/pipeline-graph/execution-lanes.md` |
| **X-Actor full removal** | Remove the header path entirely (already rejected outright on Standard/Enterprise). | 🔴 **Gate restated 2026-09-07.** It used to read "client migration with the API-v1 sunset" — but that apparatus was **deleted 2026-07-25**, and `api-v1.md` contains **zero** occurrences of "Actor", so the gate pointed at something that no longer exists. The only remaining exposure is Personal; a MAJOR is the sanctioned break. **Gate: the next MAJOR tag.** → `okf/backend/editions/auth-security.md` · `EDITIONS.md` SEC-11 |
| **OPS-5 provenance conservation** | Live-feed soak only — no code left; feature built, off by default. The discharge criterion is well written (`docs/ops/provenance-conservation-verification.md` steps 1–3, ground-truth `recordsIn`/`recordsOut`). | **Close when that file gains a dated results section.** Nothing in-repo would otherwise show the soak had been run — the work could be done and the row would still read open. |
| **Deployment topology live validation** | T2/T3/T4 reference deployments, the D8 IAM pair, GAP-7 blueprints, grammar-config live smoke. ⚠ **Evidence pointer corrected 2026-09-07:** the RTO/RPO statement with its `<OPERATOR TO STATE>` placeholders and empty drill table is `compliance/evidence/rto-rpo-statement.md`, **not** `docs/ops/backup-restore-runbook.md`, which the row cited and which contains none of it. | A reference deployment. **Repo-side half is checkable now:** close it when `rto-rpo-statement.md` carries stated targets (⚠ the signed per-tier D6 numbers are recorded in `okf/capabilities/editions/editions.md` §3.14 — transcribe from there; the drill record still has to be produced) and ≥1 drill row. 🔴 **CORRECTED 2026-09-15 — the operator REFUSED the transcription, and the "transcribe from there" instruction above is struck.** ⛔ Do **not** copy the signed §3.14 numbers into `compliance/evidence/`. In `editions.md` they are an engineering target; in an evidence file they become a commitment an auditor holds you to, and the two are not the same number by default. The placeholders stay and **the operator supplies the compliance targets** (filed as an owed input in §1). ⚠ The drill record is unaffected and still has to be produced — and the host to produce it on now exists (§1). → `archived-documents/plans-archive/deployment-topology-plan.md` (§10 D1–D8 signed 2026-09-06) |
| **Compliance program (NFR-7)** | External only: C1 applicability statements, ISMS boundary, auditor engagement, pen test, C5 policy content, C6 FedRAMP package + FIPS leg (demand-gated), the ISO 8.8 advisory-watch process. | ✅ **Gate MADE RUNNABLE 2026-09-07 (second pass).** It was the one row with no repo-side check at all, so it could never be evidenced either way. It now has a landing place per sub-item: **each of the seven closes when its own row in `compliance/controls-matrix.md` §4 carries a dated line.** **Check: `grep -c '^| NFR-7 ·.*⬜ open' compliance/controls-matrix.md`** — **7** today; the row closes at 0. 🔴 The check must be **line-anchored**: the unanchored `grep -c "NFR-7 ·"` returns **9**, because the surrounding prose (including the sentence stating the check) matches its own pattern. A check that counts its own documentation is not a check. ⚠ The five REPO-SIDE artifacts this row used to carry (customer verification runbook, CI-evidence doc, recorded restore drill, G8 RBAC evidence, G9 FIPS) are file-existence checks, not external gates — they moved to §5 on the first pass. → `compliance/controls-matrix.md` §4 |
| **SOC 2 Type II window** | Opening the 6-month observation window was **decided 2026-09-06**. The HIPAA/PCI framework choice stays deferred until a prospect is named. | 🔴 **No start date is recorded anywhere**, so the 6-month end cannot be computed and nobody can tell whether the window is running. **First action is not external: record the start date in `compliance/controls-matrix.md`.** The gate then becomes `start + 6 months` — arithmetic. ✅ **ANSWERED 2026-09-15: the window is NOT OPEN.** Record it in the controls matrix as *not started*, together with what must be true before it opens. ⛔ Do not backdate it to the 2026-09-06 decision: an auditor will ask what was being observed on day one, and nothing was. ⚠ The value of writing "not started" is that it converts an invisible gap into a checkable state — the row stays open either way, but it stops being unfalsifiable. ✅ **THE NON-EXTERNAL HALF IS DISCHARGED 2026-09-16** (`90b169ea`): the window is recorded as **NOT STARTED** in `compliance/controls-matrix.md` **§4a**, with an operator-fill start-date field and the preconditions that must be true before it opens — so `start + 6 months` is now arithmetic the moment a date lands, and "is the window running?" has a checkable answer today. ⛔ **What remains is genuinely external and nothing a shift can close**: auditor engaged (firm named, window agreed) plus the operator's HIPAA/PCI framework call. ⚠ **The window is now described in exactly ONE place** — `NFR-7 · N2` in the same matrix was trimmed to a pointer at §4a, and §4a says so itself; ⛔ do not restate the window's state here or in N2, or the copies drift, which is the defect the trim exists to end. |
| **D13 parser field tiers** | Run the onboarding-observation session. Second question for the session: every `tier:'required'` field ships `required:false` validators — should "required" validate? ⛔ Explicitly NOT an engineering guess. The **pre-agreed analysis rule** (M / T-in-3-lanes → required; never-touched-and-never-asked → advanced; one **M** on a required field files a validator, two decide it) is in `okf/frontend/features/grammar-config.md` — ⛔ do not re-derive it after the session, that destroys the whole point of agreeing it in advance. The kit is now at `superpower/parser-field-tiers-interview-plan.md` (✅ moved back to the ACTIVE tier 2026-09-16 and RE-GROUNDED there — every correction marked in place), but 🔴 it had needed its inventory and task script re-grounded first — both predate `d012f721` (2026-09-04), which dissolved the `files` section and turned tabs into sections. | A real onboarding user. **Close when `parsing-attributes.ts` carries `tier:` values annotated with the observations that earned them, plus a question-2 decision note in `okf/frontend/features/grammar-config.md`.** 🔴 Restated 2026-09-09: this read "close when `superpower/…interview-plan.md` gains a dated session record", which archiving would have made uncheckable — no shift may maintain a file in the archive tier. The deliverable is the plan's own, and it is checkable in the current tier. ✅ **KIT RE-GROUNDED 2026-09-16 (before scheduling, as the row demanded) — and the drift is WORSE than “inventory and task script”.** 🔴 **(1) The kit's central premise is now FALSE on the pane it observes:** tier no longer controls DISCLOSURE on the Parse pane, only ORDER — `grammar-editor.component.html:141-148` renders `<inspecto-schema-form [flat]="true">` per section and *“every tier renders in one single column, in tier order”* (`schema-form.component.ts:79-80`). The kit's question 1 defines the decision as *required (always visible) vs optional (second disclosure) vs advanced (collapsed)*; today **all three are always visible**, so the analysis rule's “advanced = never touched” bucket scores a field the participant can always see. The tiered non-flat path survives only in job/alert dialogs. 🔴 **(2) THIS ROW'S OWN SECOND QUESTION IS REFUTED.** It claims *“every `tier:'required'` field ships `required:false` validators”*. It does not: the derivation is `spec.required ?? spec.tier === 'required'` (`attribute-spec.ts:131`), so an OMITTED `required:` means ENFORCED — and `text_regex__pattern` (`parsing-attributes.ts:689-695`) omits it, as do **9 node attributes** (`transform.route.mode`, `transform.sql.sql`, `transform.dedup.keys`, `transform.summarize.group_by`/`measures`, `transform.join.reference`/`on`, `transform.lookup.column`/`mappings`). ⚠ So the kit's grounding *“the lone hard default is `transform.route`'s mode”* is stale twice over, and question 2 must be RE-STATED before it is asked — it currently asks about a state the code left. 🔴 **(3) Half the deliverable is impossible:** it asks for updated `tier:` in `node-attributes.ts`, which since NODE-TYPE-MIRRORS-1 (2026-09-15) is 35 lines importing a generated contract and holds **zero** `tier:` — node tiers are authored in Java (`NodeAttributes.java`). A PR editing it changes nothing. ⚠ **(4) Inventory gaps:** `source_timezone` (`tier:'optional'`, ~418 options) exists on **all four** scripted lanes and the kit never mentions it — the largest single gap; the kit's `where` (pre-parse SQL) was DELETED by `d012f721`; the robustness keys are `delimited__*`-prefixed on every lane, not bare. ⚠ **(5) The script sends participants to UI that is gone:** “tabs: Dialect / Types / Robustness / Files” is now THREE collapsible sections labelled *“How the file is written” / “How values are understood” / “When a row looks wrong”*; the only real tabs left are Sample|Parsed, which the kit never mentions; the dissolved `files` section's content moved (encoding/compression → first section, collection pointer → read-only text edited on the Collector pane, partitioning → Sink pane). Nine default values in the kit's table are wrong. ⚠ **(6) The dominant surface of the pane today — the “Columns that come out” grid — has NO row on the capture sheet**, and a 60-90 min session will spend much of its time there. ⚠ **(7) `text_regex` has no `*_example` pack**, so the 4-task script never exercises the ONE lane whose required-tier field is genuinely enforced. ✅ Still accurate and needing no change: the session protocol (participant, duration, facilitator rule), the capture marks except **F** (“wrong tab” → wrong SECTION; `AttributeSpec.tab` was renamed `.section`), the plugin-lane caveat, the three deliberate no-default fields, every per-lane TIER assignment (no tier changed), and the sample environment. ⛔ **The standing instruction still holds: the analysis rule was agreed IN ADVANCE and must not be re-derived after the observations.** ⛔ But question 2 cannot be asked as written — restating a refuted premise is not re-deriving the rule. 🔴 **THE GATE HAS FIRED, 2026-09-15: a real onboarding user IS available.** ⛔ **Re-ground the kit BEFORE scheduling** — its inventory and task script predate `d012f721` (2026-09-04), which dissolved the `files` section and turned tabs into sections, so the archived script would observe a UI that no longer exists. ⛔ And the standing instruction holds with more force now that the session is real: **do not re-derive the analysis rule afterwards.** It is already written in `okf/frontend/features/grammar-config.md`; agreeing it in advance is the entire point, and re-deriving it once the observations are in hand destroys the evidence value of the session. |
| **SEC-INCIDENT-1 carry-forwards** | Incident CLOSED BY DECOMMISSION 2026-08-29. 🔴 Due on closure and NOT done: delete the off-repo pre-rewrite backup bundle (five cleartext secrets; retention condition lapsed); confirm none of the five values was reused elsewhere. Internal hostnames/IPs still published in-repo (lower severity). | Operator, off-repo. **Close when `compliance/controls-matrix.md` CC6.1 carries a dated line confirming the deletion and the no-reuse check.** ⚠ Deleting an off-repo bundle leaves no repo trace, so without that line this overdue item can never be marked done or chased. ✅ **ANSWERED 2026-09-15: BOTH ARE DONE** — the bundle was deleted and the no-reuse check completed. ⛔ **The CC6.1 line is NOT yet written**, because it must carry the dates the acts HAPPENED, not the date they were reported; both dates are owed by the operator and filed in §1. ⚠ Until that line exists this row reads identically to the state where nothing was done — which is the precise defect the gate was written to prevent. |
| **DATA-GOV-1 archive** | Move the real carrier corpus to an encrypted out-of-band archive on company storage with a fetch script; access held by the data-agreement owner. | Org action. **Close when `PROJECT_NOTES.md` §DATA-GOV-1 carries the dated archive location and the fetch-script path.** 🔴 **ANSWERED 2026-09-15: NOT DONE** — neither the archive nor the fetch script exists; recorded as outstanding in `PROJECT_NOTES.md` so it stops reading as decided-and-handled. ⚠ Consequence worth naming: `asn-parser`'s corpus tests are opt-in and data-gated on this row, and `asn-parser/src/main/java` is **not dead** (it is compiled by `legacy-code/pom.xml`) — so live code is tested only against synthetic input until this lands. |
| **AGT-6b multi-step agent graphs** | First cut = generalize `RunbookActions`, never free-form ReAct over mutating tools. | ✅ **RUN 2026-09-08 (network), and it HOLDS — but only on its second precondition.** ⚠ The old check (`gh search code`) was unfalsifiable — see the section header. Probed instead via the git-tree API. **(1) The per-tool `DryRunProvider` seam HAS SHIPPED** upstream: `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunProvider.java` + `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunResult.java` + four per-tool `*DryRun` tools (`AuthorPipelineDryRun`, `EditConfigDryRun`, `RunPipelineDryRun`, `TriggerJobDryRun`), under an **Accepted** ADR-0008 ("all mutating actions require an ApprovalGate + dry-run, enforced in the runtime", 2026-06-19). **(2) The approval gate is STILL synchronous per-call** — `eoiagent-core/.../host/ApprovalHandler.java` is a single blocking `ApprovalDecision onApprovalRequested(ApprovalRequest)`, no future/callback — so nested gates still deadlock and this row stays gated on that alone. **Check: `gh api 'repos/jotder/inspect-agent/contents/eoiagent-core/src/main/java/com/eoiagent/host/ApprovalHandler.java' -q .content | base64 -d`** — reopen when that signature stops being synchronous. → `archived-documents/plans-archive/agt-6-plan.md` §4 |
| **E1 Enterprise distributed tier / Stage-2 streaming** | **Design SIGNED 2026-09-10** — T5 partitioned scale-out by Space (`superpower/enterprise-scale-out-plan.md` §9 D1′–D13); phases A–B are also Standard's T4 DR. | 🔴 **No longer gated — phase A HAS STARTED.** Spikes S1–S5 reported 2026-09-10 (3 closed, 2 partial); **A0 + A1 shipped 2026-09-11** (store-family health + the topology switch) and **A3 shipped 2026-09-12** (`DbEventStore`, D6). ⛔ What still gates the phase is **external, not a decision**: its own acceptance is `PostgresStateStoreTest` 12/12 and this checkout has **no Postgres**, so that class skips entirely — the coverage exists and has never executed. A2 (the connection pool) is re-scoped as **Enterprise, deferrable**. Stage-2 streaming stays unscoped (`STREAM-CONSUMER-1` is its first design pass). |
| **Completeness KPI hold** | K2 wiring / K4 / K5 held: whether `{seq}` restarts per hour is a carrier fact. | ⬜ **NOT RUN — there is nothing to run.** 🔴 **Gate CORRECTED 2026-09-07 (second pass) — my own first-pass annotation was wrong.** It said "a query over one month of landed filenames answers it … roughly half an hour of work". **There is no such query to run:** the plan's own §2b records that **no default-on durable store holds processed filenames** — `-Dfile.stages.backend` defaults to `none` (`ServiceStores:141`), the acquisition ledger to `memory` (`OperationalDb:92`), and the status CSV is buffered, un-fsync'd and one file per run. Analysing `file_stages` unflagged would report "no gaps" on a stock deployment — the `ConservationCheck` trap. ⚠ So this row is **not** carrier-gated and **not** query-gated: **its first action is engineering** — give the filename history a durable default-on home (or make the analysis REFUSE when the store is `none`). Only then does Q2 (`{seq}` per-bucket vs continuous) become answerable from data. 🔴 **REGROUNDED 2026-09-14, and the parenthetical is not buildable as written: there is NO analysis to make refuse.** K4 — the reader that would turn `file_stages` into a completeness answer — **was never built**. `FileSequenceGaps.analyze` (K2, `FileSequenceGaps.java:117`) and `VolumeBaseline.assess` (K3) are pure functions with **no production caller** (only their own tests), and they take names/series as arguments, so they never see a store at all. `DbConsignmentOutputStore.dailyVolume` (K1, `:304` — ⚠ **corrected 2026-09-16 from `:299`, which is inside the javadoc**; it throws at `:305-307` and `:331-332`) is already fail-closed — it throws on a blank producer and on a query failure rather than returning an empty list, with a comment saying an empty list is indistinguishable from a breach — and it too has no production caller. ⇒ **the refusal has to be designed INTO K4 as it is written, not retrofitted**, which makes this one action, not two. ⚠ **The store kind does not survive on the object a reader holds:** `ServiceStores.openFileStageStore` returns `null` at the default (pinned by `ServiceStoresDefaultsTest:67`), so nullness is the only signal there, while `AcquisitionLedgers.shared()` **never returns null** — it falls back to `InMemoryAcquisitionLedger`, so a caller cannot tell memory from durable by nullness and must ask `StoreHealth.of(spaceId)`, the one place the resolved backend is still known. ⚠ Two citations in this row have **drifted and are wrong**: the defaults are `ServiceStores.java:237` / `OperationalDb.java:97` for file stages, and the acquisition ledger is `-Dacquire.ledger.backend` in `AcquisitionLedgers.java:152` — 🔴 **and THIS correction was itself WRONG, re-corrected 2026-09-16:** it **IS** an `OperationalDb.Family` member (`OperationalDb.java:147` declares `ACQUISITION_LEDGER(…, "acquire.ledger.backend", "memory", Mode.DB_FLAG, …)`). The real defect is a **DUPLICATED** definition — `AcquisitionLedgers` re-resolves the same property via its own `System.getProperty`, bypassing `OperationalDb.resolve()` — filed as `ACQUIRE-LEDGER-DUPLICATE-RESOLUTION-1`. ⚠ A correction that is not itself grounded just moves the error. ⛔ The live instance of the trap is `ConservationCheck.imbalances` (`PipelineJobRunner:540` — ⚠ corrected 2026-09-16 from `:536`), and the archived plan explicitly says **do not conscript it into the completeness KPI** — it is the cautionary precedent, not the seam. Imitate `RowShaper.java:373` (refuse a windowed dedup with no ledger) instead. ✅ **DESIGN PASS DONE 2026-09-16** → `superpower/completeness-kpi-k4-design.md`: the refusal is `requireDurable(spaceId, family, toggle, forWhat)` reading `StoreHealth.of(spaceId)` as K4's **first act, before any store is opened**. ⛔ **Nullness provably cannot express the rule** — two stores return `null` at the default but `AcquisitionLedgers` returns a WORKING in-memory ledger; and per `StoreHealth`'s own javadoc a family with **no entry was never opened**, which is not `NOT_CONFIGURED` and **must refuse identically**, so a reader testing only `status != UP` passes it. ⚠ **Two new findings**: neither candidate filename store can be scanned over a window (`DbFileStageStore.stages` `:159` and `AcquisitionLedger.find` `:23` are point lookups — K2's wiring needs a NEW read method), and `file_stages` is a **best-effort index** (`record` logs write failures and never throws), so whether it is an acceptable KPI substrate at all is now an explicit operator call. **Nine operator calls open** in the design's §7. → `archived-documents/plans-archive/completeness-kpi-plan.md` §2b |

## 3. Product features — decided or unblocked, simply unbuilt

Grouped by area. A row with lettered items keeps the letters of its source doc so the two stay aligned.

### Authoring (Parse / Transform / pipeline editor)

- **P2** · **AUTHORING-REDESIGN-1** — open letters (⚠ the old "(j)(l)(n2)(o) are in §1" clause was stale in all four: (o) SHIPPED 2026-09-07 as WORKBENCH-S4 — all three slices, (l) and (n2) SHIPPED, (j) `engine: auto` was already answered by shipped code; (f)(g)(m) SHIPPED 2026-09-06 — `JOIN_REFERENCE_MISSING`/`JOIN_ON_MISSING`/`UNKNOWN_JOIN_REFERENCE` at save, `SchemaMappingDrift` on all three schema save paths, `?pipeline=` sent by the UI): (c) v2 structured AST table over the SQL for WHERE/JOIN editing — ✅ **precondition DISCHARGED 2026-09-07: it does.** `json` is statically linked into the DuckDB JDBC artifact, so nothing is installed or auto-loaded and the seal is irrelevant to it: `json_extract`, `json_structure` and — the one that matters — **`json_serialize_sql`**, which returns the whole parsed AST as JSON, all work on a sealed connection while `INSTALL excel` and re-opening `enable_external_access` still fail. Pinned by `SqlSandboxTest.jsonWorksOnASealedConnection`. ⚠ So (c) reads an engine-produced AST rather than re-implementing a SQL parser in TypeScript — the same refusal the step workbench made for reference detection. ✅ **(c) Step 0 SHIPPED 2026-09-23 (`7f6e85f8`)**: the `json_deserialize_sql` write half is pinned on a sealed `SqlSandbox` (R1 round trip, R2 mutated AST), with traps T2 (bare `?` refused / `?::VARCHAR`), T3 (`skip_null`/`skip_empty` one-way, `cte_map`) and T4 (start offsets only). ✅ **(c) COMPLETE 2026-09-23** — Q1–Q5 answered by the operator (re-home to `transform.filter`; AST read-only, plan stops at step 4 for good; no extra guard; sealed `SqlSandbox`; contract of only the keys the SPA reads). Steps 1–4 shipped `b2abe387` + `7328a251`; step 5 (`transform.join`) out of scope by design → `archived-documents/plans-archive/authoring-ast-table-design.md`, truth in `okf/frontend/features/pipeline-editor.md` § *Filter: the row predicate's structure*; (d) v3 macros as the UDF registry (per-connection re-creation in `EnrichmentEngine`, `PipelineJobRunner`, `ConsignmentIngestStrategy`, preview) — demand-gated; (e) column metadata editing on the Transform pane (Parse D2) — needs a backend home for metadata on a `transform.sql` node first; (i) per-row "sample resolves to" line — no host resolves a sample against an `AttributeSpec`. Still open on (f): which COLUMNS the reference carries is the dry-run's question (it reads the store); the save checks existence and `on` presence only. → `okf/frontend/features/schema-mapping-authoring.md` §0
- **P3** · **Step Processor catalog** — ⛔ **ON HOLD, demoted P2→P3 (operator, 2026-09-23): no NEW Step Processors — neither the 67 planned nor completing the partials — until the existing ones are releasable.** Build only when the operator names one again. 119 processors: **34**<!--count:processors-delivered--> delivered / **18**<!--count:processors-partial--> partial / 67 planned (✅ `sink.api.webhook` DELIVERED 2026-09-23 as `sink.webhook` (`f9c102ac`): the top-level `webhook:` block sends JSON batches to an `https` Connection, at rest only, Professional+, retries via `RetryPolicy` with a stable `Idempotency-Key`; `processor-catalog.contract.json`, counted after the Profiler Step landed 2026-09-15 `d00db72b` — *the "counted 2026-09-10" this said was wrong, corrected 2026-09-23* — `quality.schema.drift` DELIVERED 2026-09-10 as a per-batch `quality.schema_drift` Signal; the earlier count was 34/18 on 2026-09-08 — the earlier "69 planned" was a grep artefact) (`transform.lookup` DELIVERED 2026-09-06). Each partial is a product decision (Kafka consumer, XPath grammar, ~~drift report, profiler~~ *(both delivered)*, resampler, KPI layer, Jinja, graph tagging, commit controller, SLA object, view/email sinks…) — pick one by name. → `EDITIONS.md` §Step Processors · `okf/backend/pipeline-graph/step-catalog.md`

  🔴 **GROUNDED 2026-09-15 — the “double duty” rationale for picking PROFILER is FALSE.** §1 row 17
  chose Profiler because “it is also `DUCKLE-C8`'s prerequisite”. It is not.
  `DUCKLE-C8-BASELINE-EXPECTATION-1` is an **Expectation kind** — server-built SQL over AT-REST data,
  alongside `non_null`/`range`/`regex`/`referential`/`condition` — and its own row names
  **`FileSequenceGaps`** (acquisition code) as the template to model it on. Nothing in that path touches
  `RecipeCompiler`/`BuiltinNodeType`. The two share the word “profile” and nothing else.
  ⚠ **And “partial” overstates what exists:** `quality.profiler.inline`'s own catalog note reads
  *“storage/completeness KPIs exist; no per-column profile step”* — there is **no `Profiler` class anywhere
  in the repo**. It is unbuilt, not half-built; the “partial” marks a related KPI capability elsewhere.
  ⚠ **Shape correction for whoever builds it:** a Step Processor catalog entry is a `BuiltinNodeType` enum
  case compiled to SQL by `RecipeCompiler`/`ProcessorCatalog` — **not** the `ConsignmentProcessor`
  ServiceLoader SPI (that is the whole-Consignment third-party pack seam, a different capability).
  ⇒ **The pick stands or falls on Profiler's own merit.** ⛔ Nothing was built — the stated reason for
  choosing it over the other 16 partials has evaporated, and that is the operator's call to re-take.

  ✅ **RE-TAKEN AND SHIPPED 2026-09-15 — the operator confirmed Profiler on its own merit.**
  `quality.profiler.inline` is now **DELIVERED**, backed by a real node type `transform.profile`
  (`BuiltinNodeType` → `RecipeCompiler` verb `profile:`, BOTH dispatch sites → `RowShaper.profile` →
  `PipelineEditable` LOWERABLE/STEP_KIND → `NodeAttributes`) and a full FLAT-CONFIG HOME
  (`processing.profile`: `PipelineConfig.Profile` + `Step.PROFILE` + the projection, `PipelineConfigParser`,
  `PipelineLift`, and a DECLARED `ConfigSpecs` field joined to `stage-two-blocks-require-output-store`).
  🔴 The namespace is `transform.*`, NOT `quality.*` — `NodeTypeStepKinds.isKnown` only recognises
  `transform.<kind>`, and the first pass compiled clean under the wrong one (see `NODE-TYPE-MIRRORS-1`).
  ⚠ Like `summarize`, it executes AT REST, so an active pipeline carrying it needs `output_store:`.
  ⚠ An EMPTY `columns[]` is authored content meaning "profile every column" — it is written on lower and
  kept by the projection, because dropping it would lower back a narrower document than was authored. Output: one row per column — `column_name`, `row_count`, `null_count`,
  `distinct_count`, `min_value`, `max_value`. `columns:` restricts it; omitted profiles every column.
  🔴 **DuckDB's own `SUMMARIZE` was deliberately NOT used.** It would have been one line and richer,
  but its column set belongs to DuckDB — so an engine upgrade would silently change this node's OUTPUT
  SCHEMA and break whatever reads it downstream. A Step's output shape is a contract; it does not get to
  drift with a dependency. The hand-rolled SQL costs ~15 lines and pins the columns.
  ⛔ An unknown column in `columns:` is REFUSED, not skipped — profiling four of five requested columns
  would hand back a clean-looking profile of a typo.
  ⚠ **Four guarded counts moved, not the two expected.** `processors-delivered` 35→36 and
  `processors-partial` 17→16 were foreseen; `node-types` 30→31 (the enum case) and
  `node-types-with-attributes` 11→12 (the attribute spec) were not — `check-doc-counts.mjs` named all
  13 sites across 6 files. ⚠ Both contract JSONs under `inspecto-ui/` are GENERATED from Java
  (`-Dprocessor.catalog.write=true`, `-Dnode.attributes.write=true`) and must be regenerated in the same
  change, or their diff-check tests fail.
  ⚠ `planned` stays **67**: a PARTIAL was promoted, not a planned one.


  ✅ **DECIDED 2026-09-15:** **build the PROFILER partial next.** Picked by name, as this row requires. ⚠ The reason
  it was picked over Kafka-consumer and the three sinks is that it is **not standalone work**:
  `DUCKLE-C8-BASELINE-EXPECTATION-1` needs profile storage (row count; per-column null count/rate,
  distinct, min, max, mean) and cannot start without it, so the processor is that row's prerequisite
  either way. ⛔ Do not treat the remaining 16 partials as ranked by this choice — they are not; each is
  still its own product decision and still needs picking by name.
- **P3** · **P4 Test mapping on a generic `parser` node** — **RE-SCOPED and DEMOTED P2→P3 2026-09-09**, which is what the row's own "re-scope this row before building" asked for. Grounded against source:
  • ⚠ **The (l) discharge does not unblock THIS row.** What shipped 2026-09-06 unblocked Test-mapping on a **dangling per-format** grammar binding (`isDrawerParse` admits it, the pane flags "template missing"). A **generic** `parser` is the case the owner doc calls **unmappable**, and it still falls to `GrammarEditorDialog`. The gate is discharged for a *different* node.
  • 🔴 **Demand-gated because the authoring surface is gone.** Test mapping is **read-path only since 2026-09-05** — the Load pane was deleted and nothing authors a parse node's mapping any more (measured: **zero** files match `pipeline-load-definition`). Mappings *are* still authored, but as the standalone **Mapping component** (`mapping-editor.dialog.ts`), which is a different surface with its own editor. So building this would test a mapping an operator cannot author on that node. ⛔ Per §0's rule, that is P3: build only when someone asks by name.
  • ✅ **The offline caveat is deleted, not carried:** "non-`DIRECT` types show blank (mock has no SQL engine)" cited the offline mock backend, which was **removed 2026-08-31**. There is no mock to be blank.
  → `okf/frontend/features/pipeline-editor.md`
- ~~**P2**~~ · ✅ **CLOSED (grounding, 2026-09-17) — this row was stale; the wanted feature already shipped as TRANSFER-ARCH-1/R2 on 2026-09-01/02.** `PipelineBundleRoutes` (`GET /pipelines/{name}/bundle`, `POST /pipelines/import`) is exactly the selective dependency-closure export/import this row asked for — schemas, per-segment schemas, grammar/enrichment companions via `PipelineConfig.referencedFiles()`, and a bound Connection travels as a secret-free `requirements[]` entry (2026-09-06 operator decision honored: `schema` in `BundleRoutes` manifests stays the registry id; a pipeline-owned satellite travels under the pipeline bundle, not as `schema`). Covered by `ControlApiPipelineBundleTest`. The editor's export/duplicate flows already call this route (`pipeline-editor.component.ts:1742`). **The row's other clause — "retire/repoint the `authored-pipeline` kind" — was considered and explicitly REFUSED by the same plan**, not left undone: `BundleRoutes`' `authored-pipeline` kind keeps serving grandfathered `*_flow.toon` `PipelineStore` flows on purpose, because a canonical pipeline's satellites are config-namespace paths that collide with the id-based `BundleRef` on the word `schema` (`PipelineBundleRoutes` class javadoc; `docs/archived-documents/plans-archive/authoring-residuals-plan.md` §R2). `PipelineStore`/`authored-pipeline` is also NOT dead code — it backs live CRUD, run and lineage routes (`PipelineListRoutes`, `PipelineGraphRoutes`, `LineageRoutes`, `CollectorService`), so repointing it standalone would also not be a small, safe change even if it were still wanted. **Residual (if ever revisited): a real cross-namespace rename/merge of `authored-pipeline` into the canonical bundle would need a new manifest kind that resolves the `schema` collision — not attempted here, no board item filed, since the current split is a deliberate design decision, not a gap.** → `okf/backend/pipeline-graph/editable-round-trip.md` §21 (R2) · `okf/frontend/features/onboarding.md`

- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** an author asks for AI drafting on a **non-`schema` kind**. The capability
  was removed because its one applicable kind lost its `ConfigSpec`, so restoring it is worth the design cost only
  if someone wants it where none exists. · **AI drafting has no applicable component kind** — restore `<inspecto-ai-assist>`/`component_draft` for a kind: either give `grammar`/`transform`/`sink` a backend `ConfigSpec` (none has one; `ConfigSpecs.TYPES` excludes them) or rework `SchemaEditorDialog`. No low-risk slice survives — design first. → `okf/frontend/features/inline-ai-authoring.md`

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** An author wants AI drafting on a non-`schema` kind.
  ⛔ **This does NOT make it a small change** — the row's own finding stands: no low-risk slice
  survives, because the capability was removed when its one applicable kind lost its `ConfigSpec`.
  ⇒ **design first**: either give `grammar`/`transform`/`sink` a backend `ConfigSpec` (none has one;
  `ConfigSpecs.TYPES` excludes all three) or rework `SchemaEditorDialog`. ⚠ Demand answers *whether*,
  not *how*.
- **P3** · **`AGT-SEGMENT-1` — the assistant's commercial framing is an unvalidated product read.** The tier packaging (A Explain / B Author-with-approval / C Bounded autonomy), the "Tier A is the wedge" moat argument and the SHADOW-first on-ramp were written as a read of the codebase + roadmap and **never validated against a client segment** — the archived plan's own words. Telecom vs general regulated enterprise changes the emphasis. ⛔ **Decided 2026-09-10: keep the caveat — reopen on the first customer conversation**, not before; guessing a segment now would replace one unvalidated read with another. ⚠ Needs **product input**, not engineering; nothing in the product depends on it, but the framing is now quoted in a stakeholder-facing doc, so it must carry its caveat until this closes. (Was `agt-6-plan.md` D6, which had no board home at all.) → `stakeholders/PRODUCT_CAPABILITIES.md` §"How the ladder is packaged" · `archived-documents/plans-archive/agt-6-plan.md` §2
### Onboarding, Catalog, Parsing


- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the first `.xz`/`.zst` delivery arrives (it forces the decompression-library
  sign-off at the same moment). ⛔ **Two items were removed from this row 2026-09-13** — the "stale
  `META-INF/services` ORDER MATTERS header" was **grounded FALSE** (it correctly describes the live linear-scan
  order, TarGz before Gzip), and the schema-form empty-list opt-out is a **UI defect** that was riding in a codec
  row where nobody would find it — filed separately in §3. · **Unpack (11) absent codecs** — (xz/zstd need a new decompression library: a dependency sign-off, not a build) — xz and zstd have no plugin; `.Z` has no round-trip test; multi-part/split archives (`.z01`, `.part1.rar`) unhandled — arrival-completeness is a Collector question. ⛔ ~~The UI cannot author the explicit empty-list `data_extensions[0]:` opt-out~~ — **split out 2026-09-13** as its own §3 row (`SCHEMA-FORM-EMPTY-LIST-1`). ⛔ ~~Stale `META-INF/services` "ORDER MATTERS" header~~ — **grounded FALSE 2026-09-13**: the header is current and correctly describes the live linear-scan order. → `okf/backend/engine/unpack-stage.md`

  ⬜ **RE-CONFIRMED NOT FIRED 2026-09-15** — put to the operator directly and declined. Stays P3.
  ⚠ Worth recording that this was *asked and answered*, not merely unreviewed: the next sweep should
  not re-raise it as an oversight. The decompression-library sign-off remains bundled with the
  trigger by design.
- **P2** · **Onboarding (Stream/Reference)** — D5-ref: how a `delete` tombstone *enters* the reference store (reserved column? Decision Rule consequence?) — wait for a real delete-feed; D6-ref: within-batch same-key tie-break is arbitrary — add an optional latest-by-`order_by` column only when needed; optional templates entry (space-template-gallery precedent). ⚠ Enrichment/job configs still derive identity from name. ⚠ Do not implement name-deferral by holding the draft client-side. → `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md`

  🔴 **D5-ref IS NOW ANSWERABLE — the gate FIRED 2026-09-15: a real delete-feed exists.** The row
  deliberately said *wait for a real delete-feed*, because how a `delete` tombstone ENTERS the reference
  store (a reserved column? a Decision Rule consequence?) is decided by the shape a real feed sends.
  ⛔ **Ground the actual feed before choosing** — the whole point of waiting was to avoid picking the
  representation from first principles, and that value is lost if the arrival is treated merely as
  permission to start. ⚠ D6-ref (within-batch same-key tie-break) is **not** discharged by this and
  still adds an optional latest-by-`order_by` column only when needed.
- **P2** · **Onboarding ↔ Pipeline unification — W5 forward reference-Dataset closure** *(narrowed 2026-09-23: the owning plan records W0–W5 shipped; this is the one W5 residual)* — ✅ **W5 reverse-reference closure CLOSED 2026-09-23 (`1f0ca080`)**: `DataSourceBundleResolver.findComponentsFor` already bundled Decision Rules + Datasets (the "decision rules" half below was stale since W3, 2026-08-01); it now also carries Expectations (`targetType`/`target` → the Pipeline or a bundled job) and Alert Rules (`onPipeline` → the Pipeline, or `dataset` → a bundled Dataset); a global Alert Rule stays behind. Test: `ControlApiBundleImportTest.anExportCarriesTheAlertRulesAndExpectationsThatReferenceItAndAnImportRoundTripsThem`. ⇒ **Still OPEN (operator decision): the forward reference-Dataset closure.** 🔴 **CORRECTION 2026-09-23:** the clause below "Engine has no grouping transform" is WRONG — `transform.summarize` exists (`processing.summarize {group_by, measures}`, `RowShaper.summarize` via `MeasureCompiler`, since 2026-08-11). Earlier history follows. — (W0 PROVEN 2026-09-06: `LiftLowerFixtureSweepTest` runs every `spaces/**/*_pipeline.toon` through the editor's own seam — `PipelineEditable.toMap` → codec → STRICT lower — and all 22 survive verbatim; it stays as the standing gate.) W4: ✅ **CLOSED 2026-09-17** — `EnrichmentService` incremental-vs-full recompute — never silently convert one into the other. The service-side event path was already fixed (`ENRICH-SILENT-FULL-RECOMPUTE-1`, 2026-09-15: `onConsignmentEvent` REFUSES rather than silently widening when a committed partition matches no column the job partitions by). Grounding found one remaining silent conversion in the CLI half (`EnrichmentProcessor.main`): `--partitions` with a spec that parses to zero usable maps (no `col=val` pair) silently fell back to a FULL recompute. Fixed to log a WARN and note the audit row (`fallbackNote`) whenever the actual mode diverges from what was requested; no recompute logic changed. Test: `EnrichmentEngineTest.cliMalformedPartitionsSpecNotesTheSilentFallbackToFull`. W5 promotion-grade export: extend `BundleExporter`/`DataSourceBundleResolver` to decision rules + reference datasets (**still OPEN**); import-time referential integrity ✅ **CLOSED 2026-09-20** — grounding split this clause in two. The "not caught until first poll" claim was already FALSE for two of the three import surfaces: `POST /spaces/{id}/import` has had an ERROR-level 422 gate since 2026-08-14 (`DataSourceRoutes.referentialFindings`, `control/DataSourceRoutes.java:177-217`, called at `:283`, with the preview agreeing at `:101-121`), and `POST /bundle/import` has its own MNT-16 gate (`control/BundleRoutes.java:262-270`). It was TRUE only for the third, `POST /pipelines/import`: that route echoed the manifest's connection `requirements` back verbatim and resolved nothing (`control/PipelineBundleRoutes.java:390` pre-change), and `registerPipeline` does not resolve `collector.connection` either (`service/CollectorService.java:1173`). Now `PipelineBundleRoutes.classifyRequirements` resolves every declared connection against the target space's registry, stamps each requirement `satisfied`/`missing`, and adds a WARNING `Finding` (`FindingCodes.WARN_UNRESOLVED_CONNECTION`, `fieldPath: collector.connection`) to the envelope the route already returns. **REPORT, not REFUSE, deliberately** — unlike its whole-space sibling: a pipeline bundle never carries the connection profile (secrets never travel; the binding travels as a `requirements[]` entry), so a 422 would make promotion into any space that does not already hold the profile impossible — the exact workflow `requirements` exists to serve — and the import always lands `active: false`, so nothing polls before an operator acts. Test: `ControlApiPipelineBundleTest.aMissingConnectionIsReportedAtImportTime`. Export behaviour unchanged. Engine has no grouping transform (rollup honestly = `sink.materialized`). ⛔ `PipelineCompiler.toConfigMap` deliberately not migrated. → `archived-documents/plans-archive/onboarding-pipeline-unification.md` · `okf/backend/pipeline-graph/editable-round-trip.md`
- **P2** · **Parsing (Stage-1)** — ~~ASN.1 grammar source: a reference to a stored schema module instead of pasted module text~~ ✅ **CLOSED 2026-09-23 (`f860c414` + `d8ce162e`)**: a stored module is a path-jailed `.asn`/`.asn1` FILE (operator decision — not a registry kind); preview, `frontend: asn1` (`asn1.grammar_file`) and the drawer take it through ONE resolver, `Asn1GrammarSource` (text wins; extension check first; one jail); proven by `DemoCorpusIngestTest.mscCdrWithItsGrammarInAnAsnFilePreviewsAndIngestsIdenticallyToInline`; bundle residual → `BUNDLE-ASN1-GRAMMAR-FILE-1`, ✅ **closed 2026-09-23 (`61062252`)** — a bundle re-points `asn1.grammar_file` and preview resolves it beside the Pipeline. **Still open:** a per-vendor transform config home (the grammar file was its prerequisite); drop-in `plugins/` jar directory (JobPackManager classloader precedent) so a customer parser deploys without a rebuild (needs a trust decision first). ⚠ `asn-parser/src/main/java` is NOT dead (compiled by `legacy-code/pom.xml`); corpus tests are opt-in and data-gated (DATA-GOV-1). → `okf/backend/engine/parser-plugins.md`
### Execution, Consignments, Pipeline graph


- **P2** · **Branch-aware executor residuals** — ((b) is a design pass before code — (c) has since shipped; (d)–(g) wait for a real need) — (b) multi-schema + route needs a **segment-scoped lift** (`writeAndTrace` runs once per segment while the divert lifts the whole graph) — ⛔ do NOT just lift the refusal; ~~(c) mid-branch transforms in the recipe route verb — no per-branch scaffolding in `RecipeCompiler.route()` / `PipelineLift.branch()`, design pass written~~ ✅ **STALE — SHIPPED** (R3 mid-branch Steps `ce2fe675` 2026-09-02, plus sql mid-branch `70473c94`; corrected 2026-09-23); (d) still unimplemented anywhere: `adapter`, `alert`, `event`; still refused at lowering as flat homes: `transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest; (e) acquisition-side "listed remotely, not yet fetched" gauge — name it first; (f) `acquire.maxFilesPerCycle` — ~~only if overshoot is real~~ 🔴 **MOSTLY SHIPPED UNDER ANOTHER NAME — corrected 2026-09-16.** A per-cycle **file** cap is live today as `-Dingest.maxFilesPerCycle` (`IntakeGovernor.capFor` → `CollectorProcessor.admit`, oldest-first, off by default), and a per-cycle **byte** cap shipped 2026-09-16 (`-Dingest.maxBytesPerCycle`, `CollectorProcessor.admitBytes`) — see the Pipeline graph row below, which records both. ⇒ the description this residual was filed under ("a per-cycle intake cap") is satisfied. ⚠ What is **genuinely still open** is narrower and must be re-stated before anyone builds it: both caps run **after listing, on local files**, so for a Collector that fetches remotely before that point they save materialisation, not fetch bandwidth — the row's original acquisition-side intent needs a **listing-with-sizes seam that does not exist**. ⛔ Do not build (f) as written; it would duplicate `IntakeGovernor`. ✅ `okf/capabilities/acquisition/acquisition.md` (three sites) no longer lists `acquire.maxFilesPerCycle` as unbuilt — corrected 2026-09-23 to the narrower fetch-bandwidth residual; (g) `sinks:` follow-ups: ~~per-sink `ducklake` block in flat `.toon`~~ ✅ **SHIPPED 2026-09-23 (`2cc95151`, operator decision "honour per sink")**: `DuckLakeRegistrar.register` attributes each written file to its sink by `database` root (deepest wins, a stray file is refused) and registers into the resolved lake (entry → `output.*` → none); the shorthand is unchanged; the partitioned check runs per sink before any ATTACH. Before, only `output.duckLake()` was read (measured live: `lake_out` = `A=3 B=5`, `lake_a` empty). ⚠ Two sinks inheriting one lake both register into its table → `SINK-DUCKLAKE-SHARED-LAKE-DUPLICATES-1` (operator question). Still open in (g): decision-rule routing with `sinks>1`, versioned reference store with `sinks>1`, and a `ConfigSpecs`/`ConfigJsonSchema` structural spec for `sinks:`. ((a) `mode: clone` **shipped 2026-09-06** — `RouteArming` no longer refuses it.) → `okf/backend/engine/branch-aware-ingest.md` · `okf/backend/engine/output-sinks.md` · `archived-documents/plans-archive/mid-branch-transforms-design.md` ✅ **(b) design pass DONE 2026-09-24** → `superpower/branch-aware-segment-lift-design.md` (Option A: a segment-scoped view of the one lift + an all-segments predicate-bind arming check); its §Q1–Q5 await the operator before S1.
- **P2** · **Platform Services Stage 2 / 3** — (gated on the at-rest execution decision + the S2-2 spike) — Stage 2 open Step-kind registry (`StepTypeProvider` with `LOWERED`/`EXECUTED`, `StepContext`, failure mapping, watchdog) — ✅ **gate RUN 2026-09-07: holds**, no `StepTypeProvider` exists in any module: needs the decision to execute an intervening node at rest (the `graphLaneCarries` boundary = Phase 6 precondition) plus the S2-2 bridge spike (rows/s through a no-op `EXECUTED` Step vs fused) before GA; ⛔ third-party `LOWERED` stays closed until a SQL-fragment guard exists. Stage 3 pack-contributed services (`ServiceProvider` SPI; collision fails the pack atomically; reference-tracked quiesce). `DatasetAccess` after the Consignment Selector. No Job-side watchdog (R1) — a hanging Job is a recorded gap. Filtered `services()` on `ProcessorContext` (D4) and a devkit jar (D5) only on demand. → `okf/backend/control-plane/platform-services.md`

  🔴 ✅ **DECIDED 2026-09-15:** **YES — an intervening node MAY execute at rest; `EXECUTED` steps are allowed
  ANYWHERE, not only at lane boundaries.** This discharges the first of the row's two GA preconditions
  and, with it, **ELT Phase 6's `graphLaneCarries` precondition** — one answer, two rows.
  ⚠ **The accepted cost is a fusion break mid-graph, at a price nobody has measured.** The S2-2 bridge
  spike (rows/s through a no-op `EXECUTED` Step vs fused) is **no longer blocking** but is still worth
  running, because the only adjacent measurement spans a **half to a thirteenth** of the native rate
  (the Java-lane A/B, whose row closed 2026-09-15 without a measurement) — a range too wide to design against. ⛔ Unchanged by this decision:
  third-party `LOWERED` steps stay closed until a SQL-fragment guard exists.
- **P2** · **Consignment addressing** — (torn multi-file reads: CLOSED 2026-08-29 by the pinned `ConsignmentSelector` list — this row said "open" for a week; the two readers that still re-globbed, `DbBrowserRoutes.browseStore` and `ExpectationEvaluator`, were pinned 2026-09-06) 🔴 `generation` was called "a dead field (always 0, never read)" — **the never-read half is FALSE** (`DbConsignmentOutputStore.java:723` reads it back into the record; `:242` binds it on the insert — *line refs re-grounded 2026-09-16; the row read `:655`/`:219`, both drifted, the claim holds*), corrected 2026-09-14; ~~⚠ `retire_superseded` must be configured or every full recompute leaves a complete extra copy on disk~~ **CLOSED 2026-09-20 as ALREADY SHIPPED — the premise this clause carried (a *silent* disk cost) was refuted by grounding, and no new code was written.** The disk fact is still true and is *deliberate* policy (nothing deletes at flip time, so an in-flight read finishes on the revision it started with — `PipelineJobRunner.java:515-524` javadoc), but it stopped being silent on 2026-08-29: `PipelineJobRunner.supersedeEarlierRevisions` (`:525-543`) already emits an INFO per store it superseded and then a **WARN naming the stores** — *"no enabled 'retire_superseded' maintenance job is configured … every future full recompute of these stores adds another permanent copy on disk"* (`:538-542`) — fed by `JobService.retireSupersededConfigured` (`JobService.java:767-771`), which re-reads the live job config per run (not cached) and matches `type: maintenance` + `enabled` + `params.task == retire_superseded`. ⚠ The supplier is asked **only once a recompute actually superseded something** — a first run over an empty registry asks nothing, on purpose, so the warning is not noise on every ordinary run. Both halves are pinned: `PipelineJobRunnerTest#asksWhetherRetireSupersededIsConfiguredOnlyAfterARecomputeSupersedesSomething` (`:124-125`) and `JobServiceTest#retireSupersededCheckIsWiredFromLiveJobConfigNotJustBuildTime` (`:799`, which registers the job mid-test and watches the verdict flip) — 83/83 green 2026-09-20 (`-pl inspecto-engine -Dtest=PipelineJobRunnerTest,JobServiceTest`). ⛔ **The default was NOT flipped and must not be**: enabling retirement by default would delete bytes operators may rely on — that is an operator call, not a code change. ✅ **Demo example SHIPPED DISABLED 2026-09-23 (`657fafdb`)** — `spaces/demo/config/jobs/retire_superseded_job.toon` (`enabled: false`, `retention_days "7"`), a copyable example, so the earlier "no committed config sets `retire_superseded`" finding is superseded; ⛔ the default is still NOT flipped; the task has no path/`store` key (it walks the process-wide registry); pinned by `DemoRetireSupersededJobShipsDisabledTest`; ingest-side Consignment-scoped accessor waits for a consumer; ⚠ `DatasetRelation.temporalColumn` has no caller and cannot safely gain one on a write path. → `okf/backend/engine/consignment-addressing.md`
- **P2** · **EXECUTION-RESIDUALS X4 + X1 deferrals** — X4 record-level replay from quarantine: sidecar error manifests (offset/reason), all-or-nothing vs eject-and-continue as per-pipeline CONFIG — ⛔ no build without a driver (same item as the run-detail "reprocess is whole-batch only" note). X1 deferrals: per-pipeline `processing.retry` block (regenerate node-attributes + step-types contracts); operator cancel / retry-now affordance (today: delete the sidecar under `<status_dir>/retries/`, or `reprocess`). → `okf/backend/pipeline-graph/execution-lanes.md` · `archived-documents/plans-archive/execution-residuals-plan.md`

  🔴 **X4's driver EXISTS as of 2026-09-15 — and the operator refused the question this row asks.**
  Put the per-pipeline choice (all-or-nothing vs eject-and-continue), the answer was *neither yet*:
  **scope it against a sandbox**, which is now `PIPELINE-DRYRUN-1` (§3). ⇒ ⛔ **Do not pick the replay
  default before the dry-run lands.** Both options are risky to default to, and a dry-run makes the
  choice observable instead of theoretical — which is why the config question was premature, not merely
  unanswered. ⚠ Keep the repo's recorded distinction in scope when it IS answered: *"skip the bad file"
  is not "skip the file that throws"* — a validation failure and an exception are different events, and
  conflating them is how eject-and-continue silently swallows a real fault.
  ✅ **RE-GROUNDED 2026-09-20 — the dry run LANDED and X4's gate is STILL CLOSED, now for a precise
  reason.** `PIPELINE-DRYRUN-1` closed the same day, so "the sandbox exists" is true — **but the sandbox
  it built cannot show the thing X4 was waiting to observe.** Evidence, read on `5af44d2f`:
  `ConsignmentIngestor.process` takes a `dryRun` branch (`:86-95`) that **never calls `strategy.ingest`
  (`:96`)** — the only statement in the lane that parses a member — and instead **fabricates**
  `new IngestOutcome(now, "SUCCESS", null, List.of(), List.of(), …, 0L, …)` (`:92-93`): an all-empty,
  unconditional SUCCESS carrying **zero `memberAudits`**. `memberAudits` is the per-file carrier
  (`MemberAudit(srcId, filename, MemberStatus, parsedRows, errorRows, error, …)`,
  `inspecto-etl/src/main/java/com/gamma/etl/MemberAudit.java:13-15`), so under `?dryRun=true` every
  per-file and per-record outcome is **not merely unreported — it is never computed**, and the run
  reports SUCCESS/0 rows for members that would in fact quarantine.
  What a dry run **does** evaluate is the **cycle plan only**: eligibility
  (`CollectorProcessor.ingest` → `collect(cfg, !dryRun)`, `:150` — the read-only form, so no
  `FILE_STABLE` readiness Signal escapes), schema *selection* (the `SchemaResolver`, `:193-196`), and
  batching (`ConsignmentPlanner.plan`, `:197`). ⚠ It does not even plan the true shapes for compressed
  inputs: `UnpackStage.expand` is skipped (`:175`), so Archives are planned **pre-expansion**.
  ⇒ **NO — the shipped dry run does not deliver X4's observability.** ⛔ **The gate stays closed; do not
  read "the dry run landed" as "X4 is unblocked".**

  🔴 **THE GATE, RE-STATED.** X4's per-pipeline default may be picked only once a run exists that
  **parses real members without landing anything and reports, per member, WHICH KIND of failure
  occurred** — because all-or-nothing vs eject-and-continue is a choice about *what to do with a member
  the run rejects*, and a run that rejects nothing exhibits neither option. ⚠ The repo's recorded
  distinction is load-bearing here and must **not** be collapsed — *"skip the bad file" is not "skip the
  file that throws"*. They are already two kinds in the model and stay two in the gate:
  - a **validation rejection** = a *planned* member that failed on the way in, carried as a
    `MemberStatus` (`QUARANTINED_*`); the batch around it can still be `SUCCESS`. This is the event
    eject-and-continue exists for. ⚠ `SKIPPED_UNREADABLE` is a *third* thing again — an Archive entry
    never planned into a batch, `srcId = -1` (`MemberStatus.java:43-50`) — not a rejected member.
  - a **thrown fault** = an exception out of `strategy.ingest`, which fails the **whole batch**
    (`CollectorProcessor.java:246-251`) and is a framework/schema fault, not a bad member.
    Eject-and-continue must never swallow it, and a default chosen off evidence that conflates the two
    will do exactly that.
  ⚠ `MemberStatus`'s own javadoc already warns that **three status vocabularies meet here and none may
  be cross-mapped** (`MemberStatus.java:18-27`): member vs batch (`IngestOutcome.status`) vs Archive
  (`UnpackStatus`). Any X4 sidecar manifest must key on the **member** vocabulary.

  **What would have to be built — NOT built here; this row is a gate, not a feature.** The general
  answer is a **parse-validating dry-run mode**, flagged at ship time as a separate, larger build and
  stated as the accepted cost: *a dry run answers "what would this cycle touch", not "would these files
  parse"* (`archived-documents/plans-archive/pipeline-dryrun-design.md` §"Step 5 — AS BUILT"; the
  as-built now lives in `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.11). ⚠ **A nearer starting point
  EXISTS and the earlier framing missed it**: `PipelineTestRun`
  (`inspecto-engine/src/main/java/com/gamma/inspector/PipelineTestRun.java`) already parses **real**
  inbox files through the **real** `strategy.ingest` with zero production side effects, under two
  independent containments (it calls only `strategy.ingest`, never `commit`/`writeAudit`/
  `recordProvenance`; and it **copies** the picked files into a scratch root, because
  `QuarantineManager` *moves* the original). It surfaces `FileResult(filename, status, parsedRows,
  errorRows, error)` per file and is exposed today at `POST /pipelines/authored/{id}/run?to={nodeId}`
  (`PipelineGraphRoutes.java:498-542`). ⇒ the honest gap is **granularity, not existence**: that path
  reaches **per-FILE** outcomes with an `errorRows` *count*, while X4's sidecar manifest needs
  **per-RECORD offset + reason**. So the build X4 waits on is *"carry per-record reject offsets and
  reasons out of the ingest strategies, then surface them on a run that lands nothing"* — and it should
  be scoped against `PipelineTestRun`, **not** against `?dryRun=true`. ⛔ **No default picked** — that
  remains the operator's call, and the evidence it needs still does not exist.
- ~~**P3**~~ · ✅ **CLOSED 2026-09-23 (`85975343`) — both halves.** (a) operator decision: the flat-lane
  no-op writes **ONE provenance record flagged simulated** — its only durable write — with the same
  parse/sink rows as a real run via `ProvenanceStores`; the counts are the truthful zeros of a skipped
  parse (filed as `FLAT-DRYRUN-COUNTS-ZERO-1`). The dry run no longer creates the commit-log header
  file; with provenance disabled nothing is written. Pinned by `FlatLaneDryRunTest`. (b) shipped in
  `e02eeab2` (below). Original row: `DRYRUN-INVISIBLE-ON-FLAT-LANE-1` — a flat-lane dry run is INVISIBLE in the very overlay
  gate 4 built for it. ⚠ Carried out of `PIPELINE-DRYRUN-1` at archive time (2026-09-20) rather than
  dropped: it lived only inside that row’s now-closed narrative. **Two halves, one cause.**
  (a) Step 5’s full no-op gates `recordProvenance` off in `ConsignmentIngestor`, so a
  `?dryRun=true` flat-lane run writes **no `inspecto_pipeline_provenance` row at all** — while the
  job/graph lane still records a marked one (`PipelineJobRunner`, before its early return). So the
  `simulated BOOLEAN` column and the gate-3 decision *“a dry run should be visible in the
  Lineage/Sankey overlay”* are reachable on one lane only, and **not** on the lane the feature
  actually shipped for. ⚠ Decide, do not patch: either the no-op writes a marked provenance row (a
  deliberate exception to “skip the pass whole”, and the ONLY write a dry run makes — say so loudly),
  or the overlay promise is retracted and the log becomes the sole report.
  (b) The **UI badge was never added**. `GET /provenance` already carries `"simulated": true|false`
  per row for the UI to key off; nothing renders it, so even a graph-lane dry run’s rows are drawn
  as if real. Half (b) is moot for the flat lane until (a) is answered.
  ✅ **(b) SHIPPED 2026-09-23 (`e02eeab2`)**: `GET /provenance/batches` carries a per-batch `simulated`;
  the editor's run overlay labels (*· dry run*), annotates (*dry run — nothing landed*) and dashes a
  dry-run batch (the edge *(simulated)* label and the Sankey dash had shipped in `d42ef120`).
  ⇒ **Still open: (a) only, and it is an OPERATOR DECISION** — the flat-lane no-op writes one marked
  provenance row, OR the overlay promise is retracted for the flat lane.
  → `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.11
- ~~**P2**~~ · ✅ **`PIPELINE-DRYRUN-1` CLOSED 2026-09-20 — step 5 BUILT as the FULL flat-lane no-op.**
  `POST /runs/{name}/trigger?dryRun=true` now runs a whole pipeline and **lands nothing**, for **every**
  pipeline, with no per-pipeline caveats: no partition outputs, no quarantine/backup moves, no manifest,
  no markers, no audit or commit-log rows, no provenance row *(superseded 2026-09-23 by `DRYRUN-INVISIBLE-ON-FLAT-LANE-1` (a): one simulated provenance record)*, no fingerprint/watermark ledger entries, no
  unpack scratch — each suppressed mutation logged `dry run: would …`. It **implies** `?skipPostAction`
  (hazard (a): a dry run must never ack the remote source), while `?skipPostAction=true` stays the
  separate, narrower capability it was renamed to describe; both are documented on the route in
  `docs/api/openapi-v1.json` and pinned by `ControlApiAsyncV1Test`.
  🔴 **The previously-recorded decisions (a) and (b) are SUPERSEDED because they were INCOHERENT.**
  `ConsignmentAuditWriter` is constructed at `CollectorProcessor.java:168`, **inside `ingest(...)`** — so
  (a)'s "the authoritative site is the one the defective route reaches" **presupposed that ingest runs and
  writes**, and (b) then said the dry run publishes nothing. Together they described a dry run that writes
  everything and merely stays quiet: the same overpromise `9e101cdc` removed. ⛔ Also rejected and
  recorded: rerouting through the job lane (covers only `pipeline`-type Jobs ⇒ silently writes for
  flat-lane pipelines), a narrow build + another rename, and parking step 5.
  ✅ **It PUBLISHES, marked — and that is safe because every consumer was enumerated and handled.** There
  is exactly one fan-out (`ConsignmentAuditWriter:178`), so the set is closed: `JobService.fireOnCommit`
  (`:822`) and `onSignalEvent` (`:847`) **honour** it — ⚠ by **different** mechanisms, which the plan's
  text wrongly read as one fix: the first has the event in hand, the second builds its `Firing` from
  `sig.payload()`, so the flag is put in the payload by `PipelineConsignmentSignal.emit` + `commitPayload`
  and read back by `signalDryRun` (which fails **closed to "real"** for a payload with no flag). The other
  four — `AlertService.onEvent`, `CollectorService`'s event-store bridge,
  `PipelineScheduler.onUpstreamCommit`, `EnrichmentService.onConsignmentEvent` — **refuse loudly**.
  ⚖ **Deliberate cost:** `strategy.ingest` is skipped **whole** rather than substituted (~20 durable sites
  live inside it; substituting each is the "every sink honours a flag" shape that misses one), so a dry
  run answers *"what would this cycle touch"*, not *"would this file parse"*. Tests:
  `FlatLaneDryRunTest` (3), `JobServiceTest.aChainedOnPipelineFiringInheritsTheUpstreamBatchesDryRunFlag`,
  `ControlApiAsyncV1Test.pipelineTriggerDryRunImpliesSkipPostAction`. Release note in
  `okf/backend/control-plane/api-stability.md`. *(Historical record below.)*
  **P2** · ➕ **`PIPELINE-DRYRUN-1` — gates 1-4 SHIPPED; the residual is SCOPED as plan step 5 (2026-09-19)
  and is M, not the S–M this row implied.** 🔴 **The live defect, stated plainly: `POST
  /runs/{name}/trigger?skipPostAction=true` (renamed from `?dryRun=true` 2026-09-20) puts ACQUISITION in
  dry run, and the chained execution job then writes
  FOR REAL** — `fireOnCommit` hardcodes `dryRun=false` (`JobService.java:818-823`). ⛔ The obvious fix is
  closed off: `PipelineJobRunner` returns early under dry run (`:381`) BEFORE publishing the
  `ConsignmentEvent` (`:391`), so there is no event to carry the flag. `ConsignmentEvent` is a fixed-field
  `@PublicApi` record with **34 construction sites** and no attribute slot, so the flag must be ADDED.
  ✅ **ALL THREE DECISIONS ANSWERED by the operator 2026-09-20** — (a) the authoritative publish site is
  **`ConsignmentAuditWriter:178`** (it is what the defective route actually reaches and it fans to both the
  bus and the Signal ledger, covering `JobService:822` `on_pipeline` and `:847` `on_signal`;
  `PipelineJobRunner:391` rejected as unreachable under dry run); (b) an **acquisition-only dry run does
  NOT publish a `ConsignmentEvent`** — the chain stops cleanly (publishing-marked rejected: every consumer
  would have to honour the flag or silently act for real, and it would overturn the invariant at
  `JobService.java:947-949`); (c) **no version call needed** — `ConsignmentEvent` is confirmed absent from
  `v3.11.0`, so only a release-notes line is owed. ⇒ **The row's remaining scope is now the step-5 BUILD
  only**: carry the flag on `ConsignmentEvent`, set it at `ConsignmentAuditWriter:178`, have `fireOnCommit`
  read it instead of the hardcoded `false`, and the two-phase end-to-end test. **The row stays OPEN.**
  ✅ **INTERIM SHIPPED 2026-09-20** — the route's query parameter is renamed `?dryRun=true` →
  **`?skipPostAction=true`** (and the v1 response field with it), because the flag's real scope never
  suppressed the ingest write and the name overpromised exactly the guarantee this row proves it cannot
  make; it is now documented in `docs/api/openapi-v1.json`, where it was previously absent entirely. The
  capability is unchanged, and the job framework's unrelated `JobContext.dryRun()` (MNT-1) keeps its name.
  🔴 **RE-GROUNDED 2026-09-20 — the defect above is UNDERSTATED and one decision is already ANSWERED.**
  (i) **The `?dryRun=true` run writes for real BY ITSELF, with no chained job configured.**
  `CollectorProcessor.run(cfg, onCommit, dryRun)` (`:92-95`) dry-runs `acquire` and then calls
  `ingest(cfg, onCommit)` **with no flag** — ingest always commits and always publishes. The chained-job
  hole is a SECOND instance downstream of the first, not the defect. ⚠ And it is **documented as
  deliberate** (`CollectorService.java:1667-1668`), so the flag's real scope is *"do not ack the remote
  source"* while its name promises *"dry run"*.
  (ii) **There are TWO hardcoded firings, not one** — `JobService.java:822` (`on_pipeline`) *and* `:847`
  (`on_signal`); the dry-run ingest emits the `pipeline.batch.committed` Signal too
  (`CollectorProcessor.java:176`, wired unconditionally). Fixing only `fireOnCommit` leaves half the
  chaining unprotected.
  (iii) **Decision (c) is ANSWERED and free** — `okf/backend/control-plane/api-stability.md` §"Release
  baseline": `since = "4.0.0"` has never shipped, so the type may be changed freely; `git ls-tree -r
  --name-only v3.11.0` confirms `ConsignmentEvent.java` did not exist in the last released ancestor. The
  only obligation is a release-notes line. ⛔ That page records this same premise being refuted **three**
  times before; this row was the fourth.
  (iv) **Decision (a)'s answer is forced by (i)**: the authoritative site is `ConsignmentAuditWriter:178`
  (the one the route reaches, and the one that feeds BOTH chain paths) — **not** `PipelineJobRunner:391`,
  which is unreachable under dry run and not on this route.
  ⚠ A decision-free **interim mitigation** is scoped in the plan (refuse `dryRun=true` at
  `RunRoutes.java:159` with a 422, or rename the parameter to `skipPostAction`) — **operator's call, not
  implemented**. ✅ Confirmed unchanged: `:381`/`:391`, the record shape, and the **34** construction sites
  (4 main / 30 test). → `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.11 · `archived-documents/plans-archive/pipeline-dryrun-design.md`
  (the as-built now lives in the OKF concept; the plan is archived for provenance, 2026-09-20).
  *(Original:)* run a whole pipeline and discard
  every write. **FILED
  2026-09-15, new scope, operator-asked.** Nothing on the board covered it: the repo can preview a
  *node* (34 read-shaped `preview`/`test`/`probe` POSTs), test a *job pack* (`PackTestHarness`), seal a
  *SQL connection* (`SqlSandboxTest` — `INSTALL` and external access refused while `json_serialize_sql`
  still works), and switch a *lane* (`-Dingest.lane`), but **nothing runs an entire pipeline in a mode
  where its writes do not land**. ⛔ Chosen over two cheaper shapes — a throwaway Space, and a bounded
  sample run — because both of those genuinely write, and the point is to answer "what WOULD happen"
  without it happening.
  🔴 **TWO write paths make this dangerous to build naively, and neither is obvious from the sink list.**
  (a) **Land-then-ack must not fire.** A source-side `post` deletes the remote original once the local
  copy is committed — a dry run that reaches it would **delete a customer's source file** while
  reporting that it wrote nothing. This is the single worst failure this feature can have.
  (b) **Signals, events and notifications must not escape.** A dry run that emits a `dataset.write`
  triggers downstream pipelines — and until 2026-09-15 (`DATASET-SELF-TRIGGER-1`, now closed; as-built in
  `okf/capabilities/pipeline-execution/pipeline-execution.md` §self-loop guard) that path could re-trigger the
  *running* pipeline; the guard now exists, and both emit sites sit behind their callers' dry-run gates, so a dry
  run is fenced structurally rather than by an asserted flag.
  ⇒ the design question is **which seam the mode is enforced at**: every sink honouring a flag is the
  shape that misses one, so prefer a single interception point every write already funnels through.
  ✅ **The precedent already exists and this row's inventory omitted it — added 2026-09-16 by the ground-truth
  sweep.** A **Job**-level dry run is live: `Job.dryRun()` swaps the granted `PlatformServices` for
  `DryRunServices` (`inspecto-engine/.../job/DryRunServices.java`), where every *mutating* service records its
  would-be effect to the `RunLog` and performs nothing while read-only services pass through untouched. It is
  wired on both Job paths — `JobService.java:1266` and `PackTestHarness.java:174` — and pinned by
  `DryRunServicesTest`. ⇒ the "single interception point" shape this row asks for has a working in-repo
  precedent to copy, including its own stated failure mode: *"every mutating service added to the menu must be
  substituted here, or `dryRun()` becomes a lie the moment the Job calls it"* — the exact hazard as the
  every-sink-honours-a-flag shape. ⛔ This does **not** close the row: it fences Platform Services inside a Job,
  not a pipeline's data writes, and neither hazard (a) nor (b) above runs through it.
  ⚠ `X4` is scoped against this row and ⛔ must not pick its replay default first. → `X4` above ·
  `okf/backend/pipeline-graph/execution-lanes.md`

  🟡 **PARTIALLY SHIPPED 2026-09-18 — execution-phase dry run works end-to-end; acquisition-phase now has
  both the mechanism AND an operator trigger (`POST /runs/{name}/trigger?dryRun=true`, same-day follow-up
  below). Still not full closure: no single route dry-runs both phases together.**

  **Job-flow finding (the plumbing question this row's design doc asked to resolve first):**
  `CollectorProcessor`/`MultiCollectorProcessor` (acquisition — `CollectorService` schedules them) and
  `PipelineJobRunner` (execution — `JobService`/`JobContext` schedules it) are **genuinely separate call
  stacks with no shared `JobContext`**, not one Job's `dryRun()` covering both. `CollectorProcessor` never
  references `JobContext` at all (confirmed zero references, and `JobService.java:1071-1073`'s own comment
  says so: *"the paths that never reach this scheduler — `CollectorProcessor`, `EnrichJob` — need Run ids
  that mean the same thing as these, not a second dialect of them"*). The two are chained only through the
  `ConsignmentEvent`/Signal bus (`JobService.onConsignmentEvent`/`onSignalEvent`), and every firing mints
  its **own fresh** `JobContext` (`ctx.dryRun(firing.dryRun())` set once per `submitRun`). So "dry-run the
  whole pipeline" cannot be one ambient flag — it has to be threaded explicitly into each side, per the
  design doc's own fallback instruction.

  **Shipped:**
  1. **Gate 1 (`RemoteAcquisitionHandler.applyPostAction`)** — skips `connector.post(...)` under dry run,
     logs what it would have done. Threaded via new overloads (`CollectorProcessor.acquire(cfg, boolean
     dryRun)`, `RemoteAcquisitionHandler.materializeRemote(..., boolean dryRun)`) — every existing call
     site keeps calling the old overload (`dryRun=false`), so behaviour is unchanged today. Pinned by
     `RemoteAcquisitionStagingTest#dryRunSkipsThePostActionButStillLandsTheFile` +
     `#aRealRunDoesApplyThePostAction` (the control).
  2. **Gate 2 (`PipelineJobRunner` → `PipelineExecutor.execute`)** — under `ctx.dryRun()` (manual trigger
     only), a new no-op `DryRunSinkWriter` (writes no bytes, previews a row count via `SELECT count(*)`)
     replaces `PartitionSinkWriter`, the `BranchCommitCoordinator` is built over a throwaway scratch
     `BranchCommitLog` (never touches the real per-batch audit file), and watermark-advance,
     supersede-earlier-revisions, DuckLake registration and the `ConsignmentEvent` publish (which is what
     a downstream `on_pipeline` job reacts to) are all skipped. `PipelineExecutor.dryRun()` (T18) itself is
     untouched, per instruction. Pinned by
     `PipelineJobRunnerTest#dryRunWritesNoBytesFiresNoEventAndRecordsASimulatedRow`.
  3. **Gate 3 (`DatasetWriteSignal.emit` × 2)** — both `ConsignmentProcessJobType.java` and
     `MaterializeTask.java` now gate their emit call directly on the resolved dry-run flag (explicit, even
     though the `ConsignmentProcessJobType` site was already vacuously safe — its `pending` map is never
     populated under dry run because `persistSummaries`/`persistDerivedTables` already returned early).
  4. **Gate 4 (provenance marker)** — `ConsignmentOutput.State` gained a new `SIMULATED` value (excluded
     from every `state='LIVE'` readability/selection check in `DbConsignmentOutputStore`, so a dry run's
     row can never be read as real data, superseded, or counted in a KPI); `DryRunSinkWriter` records one
     such row per sink branch. 🔴 **Design-doc correction**: the doc named `DbConsignmentOutputStore` as
     what `GET /provenance` renders — traced and confirmed **wrong**: `/provenance` is backed exclusively
     by `DbProvenanceStore` (table `inspecto_pipeline_provenance`), a different store entirely
     (`DbConsignmentOutputStore`/`consignment_outputs` is the ingest-side output-file registry, unrelated
     to that route). Fixed by *also* adding a `simulated BOOLEAN` column to `inspecto_pipeline_provenance`
     (additive migration) and a `simulated` field to `ProvenanceRow`, threaded through
     `DbProvenanceStore.record`/`query` so a dry run's per-node counts render distinctly in the actual
     Sankey overlay. UI badge NOT added (frontend change; the JSON now carries `"simulated": true|false`
     per row for the UI to key off — filing as a residual below rather than attempting it inline).

  ✅ **Residual closed 2026-09-18 (same day, follow-up commit).** `CollectorService`'s manual pipeline
  trigger now carries a `dryRun` param: `runPipeline`/`triggerRunAsync`/`runPipelineOffThread` all gained
  `dryRun` overloads threaded through `MultiCollectorProcessor.runAll(..., boolean dryRun)` into
  `CollectorProcessor.acquire(cfg, dryRun)`. `POST /runs/{name}/trigger?dryRun=true`
  (`RunRoutes.triggerPipeline`) is the operator-facing route — mirrors `JobRoutes`'s existing `?dryRun=true`
  convention exactly. Scope stays deliberately narrow: this closes gate 1 only (the remote source is never
  deleted/moved/renamed), not a preview of the ingest write — acquisition still lands real files and ingest
  still commits, per the job-flow finding above (acquisition and execution remain separate call stacks with
  no shared `JobContext`, so this is not "dry-run the whole pipeline" in one flag, only the acquisition
  half of it). Pinned by `RemoteAcquisitionStagingTest#dryRunLandsTheFileButNeverAppliesTheDeletePostAction`
  (merged into the existing `dryRunSkipsThePostActionButStillLandsTheFile`/`aRealRunDoesApplyThePostAction`
  pair above — same assertion, two independent authors landed it the same day).

  **Still open:** no combined "dry-run this whole pipeline" route fires both the acquisition and execution
  phases under one explicit flag — an operator wanting both must call the pipeline trigger (`?dryRun=true`,
  acquisition-only) and the job trigger (`?dryRun=true`, execution-only) separately, and only when a
  `pipeline`-type job is what actually processes that pipeline's ingest. No end-to-end test spans both
  phases in one dry run, for the same reason.

  **Tests:** `PipelineJobRunnerTest` 34/34, `RemoteAcquisitionStagingTest` 6/6, `DatasetWriteSignalTest`
  2/2 — unit-level per CLAUDE.md's convention, not the full reactor gate.
- **P2** · **`STREAM-CONSUMER-1` — adapter stream-consumer runtime** (filed 2026-09-10 — it was committed in `roadmap/ROADMAP.md` §3.4 and listed in `okf/capabilities/acquisition/acquisition.md` §"Open elsewhere on the board" with **no board row**, the id column pointing back at the ROADMAP paragraph). The land-then-ack seam exists (a source-side `post` that deletes the remote original runs only after the local copy is committed); the **consumer loop** that keeps an adapter draining a streaming source with at-least-once semantics does not. Not demand-gated: the ROADMAP commits to it. First action is a design pass on where the loop lives (Collector scan vs a long-running job), not code. → `okf/capabilities/acquisition/acquisition.md` ✅ **Design pass DONE 2026-09-24** → `superpower/stream-consumer-design.md` (loop stays in the Collector scan; its §2.2 defect was reproduced and slice 1 FIXED the same day — `KAFKA-OFFSET-REKEY-1`, see `okf/capabilities/acquisition/acquisition.md`); §6 Q1–Q4 await the operator.
- **P2** · **Pipeline graph** — flip the intake cap on by default (needs a soak — **the only open clause**); ~~a pre-materialise cap to save remote-fetch bandwidth (cap applies post-dedup)~~ ✅ SHIPPED `20ff050d` 2026-09-16 (see the byte-cap clause below); ~~🔴 **THREE** kinds still last-one-wins, deliberately out of A2 scope: `acquisition`, `gap`, `dedup.marker`~~ ✅ **CLOSED 2026-09-23 (`50954398`)**: a second `acquisition` / `gap` / `dedup.marker` node refuses by name — `MULTI_ACQUISITION` / `MULTI_GAP` / `MULTI_MARKER` — exactly like `MULTI_PARSER` (the same 422 `refusals[]`, strict and lenient), and the SPA's `lowerGraph` mirrors it → `okf/backend/pipeline-graph/editable-round-trip.md` — *corrected 2026-09-09: `parser` was in this list and does NOT belong; a second parser is REFUSED by name (`MULTI_PARSER`, `PipelineEditable.java:65,739` — *line ref re-grounded 2026-09-23; it read `:720`, then `:696`, which the file has since drifted past*), which is the opposite of last-one-wins. `pipeline-editor.md` §Multiplicity states it correctly.*; 🔴 ~~`BatchGraphRunner` has zero production callers~~ **WRONG ON BOTH COUNTS — corrected 2026-09-09.** (a) **There is no class of that name** — it was renamed in the 2026-08-31 Consignment commit. (b) The class that DOES exist, `ConsignmentGraphRunner`, has **production callers**: `engages()` drives the live lane admission (`ConsignmentIngestStrategy.admittedLift`) and **`run(...)` executes on the ingest path** (`inspecto-engine/.../inspector/ConsignmentIngestStrategy.java:394` — *re-grounded 2026-09-16; it read `:355`, and the class is under `com.gamma.inspector`, not `com.gamma.consignment`. The caller pair is `admittedLift` at `:196` (`ConsignmentGraphRunner.engages`) and the `run(...)` at `:394`; both confirmed live*). ⛔ This row was cited as Row 15's parity blocker, so re-derive that gate before using it. ~~What IS still owed is §6 step 2, the parity gate through the compiled-recipe path.~~ ~~🔴 **The parity gate was RUN 2026-09-16 and is NOT MET**~~ ⛔ **SUPERSEDED — the gate is MET; see the ✅ below. This clause is kept only for the trail and must not be read as current.** — the root pom now passes `-Dingest.lane` to surefire (`mvn -Dingest.lane=graph test` = the whole suite with the flat lane disabled); 13 `inspecto-engine` tests refuse, for exactly two reasons the lane itself names: a **sink-count mismatch** (the multi-schema/plugin-ingester fixtures `events_etl`, `typed_record_etl` lift to 3 sinks against 1 declared) and **a Decision Rule routed rows** (`test_etl`), which the graph lane does not implement. Those two are the remaining work before Row 15's deletion half can start; re-run the one flag after each. ✅ **Each got its own row 2026-09-16, and the multi-schema one SHIPPED the same day** — 12 of the 13 are green and `-Dingest.lane=auto` now diverts multi-schema segment writes to the graph lane; ~~`GRAPH-LANE-RULE-ROUTED-1` is the only one left.~~ ✅ **STALE — corrected 2026-09-17: that row SHIPPED 2026-09-16 too** (`DecisionRuleApplier.java:233-237` deletes the routed rows above the fork, so both lanes see the identical remainder and no graph node was ever needed). ⇒ **the §6 step-2 parity gate is MET** — the whole suite under `-Dingest.lane=graph` is 4603/0/0/28, zero refusals — and **the only surviving clause on this row is the soak-gated intake-cap default flip**, verified still off (`IntakeGovernor.java:74`, `ingest.maxFilesPerCycle` defaults to `0` = `UNBOUNDED`, declared at `:50` — re-grounded 2026-09-23). ⚠ Its stated cause (an arity mismatch) was NOT the whole gap — read the row before re-deriving it. **~~Pre-materialise cap: design-first~~** ✅ **SHIPPED 2026-09-16** — the §1 input was answered (operator: cap on **BYTES**, remainder **DEFERRED** to the next cycle) and built at the one choke point where candidate sizes are known: `-Dingest.maxBytesPerCycle` (off by default, so no existing install starts throttling) on `IntakeGovernor.Policy`, enforced oldest-first in `CollectorProcessor.admitBytes`. ⛔ **The starvation guard is part of the rule, not a follow-up**: a file LARGER than the cap fits in no cycle ever, so when nothing fits the oldest file is admitted **ALONE** with the overrun logged — otherwise it sits in the inbox permanently while smaller files overtake it, a silent stall that looks like a working cap. Mutation-proved (removing the guard strands it). 🔴 **Two defects found and fixed while building, either of which would have shipped silently:** the per-pipeline `processing.intake` override would have switched the GLOBAL byte cap OFF for any pipeline declaring an intake block (the legacy 3-arg `Policy` constructor defaults it to unbounded — it now inherits explicitly), and a negative/malformed property clamps to *off* rather than to *admit nothing*. ⚠ **Limitation, stated rather than papered over:** the cap runs after listing, on local files, so for a collector that fetches remotely BEFORE that point the saving is on materialisation, not on the fetch itself; moving it earlier needs a listing-with-sizes seam that does not exist. Pinned by `CollectorProcessorByteCapTest` + `IntakeByteCapTest`. As-built: `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.4 · §2.3; → `okf/backend/pipeline-graph/pipeline-graph-design.md` §8

  ✅ **DECIDED 2026-09-15:** **flip the intake cap on by default ONLY after a soak** — the soak stays a real
  precondition and is not waived. ⚠ **But the DOC half is not waiting for it**: `EDITIONS.md:164`
  advertises `JOB-04` as ✅✅✅ with an empty notes cell over a feature that is off by default
  (`IntakeGovernor.java:64-68`, `UNBOUNDED = 0`), and that cell is being corrected now. ⇒ the claim and
  the default are being fixed on **different clocks, deliberately**. ✅ The cell was corrected 2026-09-15
  (`EDITIONS.md` `JOB-04`) and `SPEC-GREENCELL-1` retired with it; ⛔ **the soak and the default flip are
  THIS row's and remain open** — the doc fix does not discharge them.

  ✅ **The pre-materialise cap's two owed inputs are ANSWERED 2026-09-16 (operator): cap on BYTES, and the
  remainder DEFERS to the next run** — not refused, and not counted in files. Bytes because remote
  bandwidth is the scarce resource a file count does not bound: one huge file blows through a count.
  ⚠ **But the clause is not therefore startable, and the grounding says why** (checked 2026-09-16, moved
  here from §1 when its answered entry was swept): **nothing exists to extend.** `IntakeGovernor` caps
  files **AFTER listing**, and a *pre-fetch* cap needs the connector to expose a size **before** download
  — ⛔ that is a **connector-SPI** question, not a governor setting. ⇒ the answer unblocks the product
  call, and the SPI question is what remains.
- ~~**P2** · **`GRAPH-LANE-MULTISCHEMA-1` — the graph lane cannot carry a multi-schema write**~~ ✅ **SHIPPED 2026-09-16** (filed, designed, decided and built the same day; `auto` flipped per the operator's call — multi-schema segment writes now divert to the graph lane by DEFAULT). 12 of the 13 parity-gate refusals are green; the fix was `ConsignmentIngestStrategy` alone (`segmentWrite` / `writeSinks` / `seedOfWrite` + three `segKey` overloads), pinned by `GraphLaneSegmentAdmissionTest`. As-built + the fixture gotcha (`segments:` is only parsed when `processing.ingester:` is set) in `archived-documents/plans-archive/graph-lane-multischema-design.md` §6. Original row text follows.
  - **P2** · **`GRAPH-LANE-MULTISCHEMA-1` — the graph lane cannot carry a multi-schema write** (filed 2026-09-16 from the parity-gate run; ⛔ **design-first**). 12 of the 13 refusals the §6 step-2 gate produced are this one gap: `events_etl` and `typed_record_etl` refuse with *the lifted graph's sink count (3) differs from sinks[] (1)*. **Grounded by re-running the gate, not read off the previous shift's note** — `mvn -o -pl inspecto-engine -am -Dingest.lane=graph -Dtest=… -Dsurefire.failIfNoSpecifiedTests=false test` ⇒ 20 run / 10 failures / 3 errors in `TypedRecordIngesterTest`, `ConsignmentIngestorPluginTest`, `ConsignmentIngestorPluginDeepTest`. ⚠ **The refusal message names an arity, but the gap is structural and bigger than the count.** A multi-schema config lifts to one `map → sink` chain PER SCHEMA (`PipelineLift.java:22-24`), so three sink NODES stand against one declared DESTINATION — and `graphLaneCarries` (`ConsignmentIngestStrategy.java:407-424`) then requires **every** sink to hang directly off **one** seed (`seedFeedingTheWrite:433`), which a per-schema lift cannot satisfy by construction: each branch has its own map. ⛔ So do **not** "fix the count" — widening line 417/209 to accept N sinks would admit a write whose seeding contract is still violated. The design question is whether the graph lane seeds PER BRANCH, and 🔴 **it is NOT established that the executor can already run per-schema trees once seeded** — `ConsignmentGraphRunner.hasRouteFedChain` (`:154-167`) deliberately EXCLUDES a multi-schema parser's `route:<key>` dispatch edges, calling them *"the flat lane's own per-schema trees"*, so per-schema execution on this lane is unproven either way. ✅ **ESTABLISHED 2026-09-16 by spike — and it refutes BOTH readings above, including my own ‘structural seeding’ one written the same day.** (1) The executor is **not** the gap: `PipelineExecutor.execute` already takes a MAP of seeds (multi-source shipped for the job lane, T32 Phase C), and seeded `{map_CALL, map_SMS}` it walked both trees and committed both branches (`committedBranches=[sink_CALL, sink_SMS]`, quarantine correctly skipped as control-fed). (2) 🔴 **And no multi-seed is needed at all: `UnionModeIngester:122-168` already loops PER SEGMENT**, materialising `transformed_<KEY>` and calling `writeAndTrace(… dbDir=database/<segKey>, writeScope=segKey)` once per segment — so every call is already ONE seed to ONE sink. The defect is only that the admission lifts the WHOLE pipeline on each such call and compares 3 sink nodes against 1 destination: **it asks at pipeline granularity while the caller works at segment granularity.** ⇒ the row is **no longer design-first and is much smaller than filed** — thread the segment key into the admission and admit the `map_<segKey> → sink_<segKey>` sub-chain. ⚠ `writeScope` is NOT usable as that key unguarded: it is `""` whole-batch (`CsvIngestStrategy:182`), the chunk base name when chunked (`NativeCsvStreamingEngine:279`), the segment key only in `UnionModeIngester:157` — discriminate on `cfg.schemas().segments().keySet()`. ⛔ **ONE operator call is owed first (§1): does `auto` start diverting multi-schema writes to the graph lane, or does only `-Dingest.lane=graph` carry them?** The gate goes green either way; the first flips the live write path for every `segments:` pipeline, the second creates a path only the gate exercises. Design: `archived-documents/plans-archive/graph-lane-multischema-design.md`. Files: `ConsignmentIngestStrategy.java` (`admittedLift`, `graphLaneCarries`, `seedFeedingTheWrite`, `flatReason`). **Verify:** `ConsignmentIngestorPluginTest`, `ConsignmentIngestorPluginDeepTest`, `TypedRecordIngesterTest` green under `-Dingest.lane=graph` (12 of the 13; `DecisionRuleWiringTest` waits on the sibling row). Blocks §2 Row 15's deletion half. → `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.4 · `okf/backend/pipeline-graph/pipeline-graph-design.md` §8
> ⚠ **READING RULE for struck rows — the INDENTED bullet under a struck row is the ORIGINAL row text,
> kept for provenance.** It reads BLOCKED / “not startable” because it was written BEFORE the answer.
> **Status is the STRUCK line ABOVE it, never the bullet below.** 🔴 Added 2026-09-16 after a briefing
> read the nested text as live and spent a round-trip re-deciding a closed call — the SECOND time that has
> happened. ⛔ A closed row whose superseded text is preserved verbatim underneath is indistinguishable
> from an open row to anyone grepping the identifier, so grep the STRUCK marker, not just the id.

- ~~**P3** · **`MIGRATE-ENRICH-1`**~~ ✅ **ANSWERED + CLOSED 2026-09-16 (operator): the 2026-08-06 reversal cancelled the FILE-FORMAT migration too, not only the vocabulary — `*_enrich.toon` stays a Job.** The amendment §6 step-1 clause is **STRUCK**; `GLOSSARY.md` §Enrichment corrected (it was carrying the pre-reversal position against §Job and the §13 row in the same file); `ConfigMigrator` now passes enrich configs over in silence and leaves them where `EnrichJob` expects them — **not** archived. ⇒ a space owning an enrich config migrates again, so **§6 step 1 is dischargeable**. Original row follows.
  - **P3** · **`MIGRATE-ENRICH-1` — `*_enrich.toon` has no conversion target, and the two the docs name CONTRADICT each other** (filed 2026-09-16 while building the §6 step-1 converter). `inspecto migrate-configs` REFUSES an enrichment config, and because a refusal fails the whole migration, **the demo space cannot be migrated today** (driven live: 9 conversions, 1 refusal, per demo space). 🔴 **The blocker is not missing code — it is that the target was REVERSED and `GLOSSARY.md` still carries both positions.** §*Enrichment* (`GLOSSARY.md:398`) says the file kind *"becomes a **table-entry Pipeline** (amendment Phases 3/6)"* and that existing enrich files *"keep running until migration"*; §*Job* (`:456`) and the §13 row (`:908`) say the Job retirement was **REVERSED by operator decision 2026-08-06** precisely because its replacement path *"hung on the amendment's Phase 3 S3 (table-entry `collect`), **deferred by its own design spike as genuine new design**"*, and that table-entry Pipelines are *"an **additive complement**, never the Job's replacement"*. ⇒ the conversion the amendment's §6 step 1 asks for is **blocked on a slice that was deliberately deferred**, and a periodic enrich is a **Job** under the reversal. ⛔ Do not build a table-entry conversion on the §6 wording alone; ⛔ do not "fix" the GLOSSARY by picking a side — the vocabulary is binding and this is an operator call, filed in §1. Two outcomes are possible and they differ in kind: **(a)** `*_enrich.toon` stays a Job ⇒ strike the clause from §6 step 1, and the converter should SKIP enrich files as out of scope (the demo space then migrates); **(b)** it really does become a table-entry Pipeline ⇒ this row waits on S3's design and the converter's refusal is correct as it stands. Files when (a): `ConfigMigrator.java` (the refusal becomes a skip) + `GLOSSARY.md` §Enrichment. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §6 step 1 · `okf/backend/control-plane/jobs.md`
- ~~**P3** · **`MIGRATE-ENRICH-DRIVE-1`**~~ ✅ **DRIVEN + CLOSED 2026-09-17 — all four assertions PASS, and
  the drive found TWO defects that are not the enrich skip.**
  Driven on a COPY of `spaces/demo` (never the original; `git status -- spaces/` empty afterwards). The
  expected count was derived INDEPENDENTLY before reading any output — 5 `*_pipeline.toon` + 4 `*_schema.toon`
  = 9 — so the tool's number was checked against a known answer, not accepted from it.
  ✅ **9 conversions · 0 refusals · enrich file byte-identical (sha256 unchanged) · not archived.** The
  post-`ea5a9225` skip is confirmed by a real run.
  ⚠ **Assertion 4 is weaker than it reads, by the lane's own correction:** `ServiceBootstrap.resolveBySuffix`
  walks the config tree with **no `archived-config/` exclusion** — only `ConfigMigrator.legacyFiles` has one —
  so archiving would not have hidden the file from `EnrichJob` anyway. It is a claim about operator
  expectations, not about reachability.
  ⚠ The row's `inspecto migrate-configs` spelling is not a binary: the entry point is
  `java -cp inspecto-processor-*.jar com.gamma.inspector.MainApp migrate-configs <config_root> [<registry_root>] [--apply]`.
  ⇒ Both defects filed together as `CONFIG-MIGRATOR-LOSES-MAPPINGS-1` (P1). Original row follows.
  - **P3** · ⚠ **`MIGRATE-ENRICH-DRIVE-1` — the post-skip live drive is NOT evidenced.** Filed 2026-09-16.
    `ea5a9225` turned the converter's enrich REFUSAL into a skip and its commit message asserts *“a space
    owning one migrates again”*, but cites **no run** — and the live drive it points back to (9 conversions,
    1 refusal) PREDATES the change, so it evidences the old behaviour. The unit test
    `ConfigMigratorTest.anEnrichConfigIsPassedOverAndDoesNotBlockTheSpace` is real and passes, but
    ⛔ **a unit test is not a drive**: this repo's standing lesson is that an example proves nothing until it
    is DRIVEN. ⇒ One clean-checkout run of `inspecto migrate-configs` against a **COPY** of `spaces/demo`
    (⛔ never the original) should show **9 conversions, 0 refusals, the enrich file untouched and
    unarchived** so `EnrichJob` still finds it. Cheap, and it closes the last open thing in the §6 step-1 lane.

- ~~**P1** · **`CONFIG-MIGRATOR-LOSES-MAPPINGS-1`**~~ ✅ **SHIPPED 2026-09-17 — and the exposure was the WHOLE
  corpus, not the four demo files.** Re-measured: **25 of 25** committed `*_schema.toon` carry
  `mapping.fields[]`; **zero** carry `rules[]` — including `spaces/_templates/` and `inspecto/examples/`.
  🔴 **Root cause, which is what decided the fix:** `mapping.rules[]` is the LEGACY spelling.
  `MappingMigrator` had already moved the entire corpus to `fields[]` and `transform.map` was deleted
  2026-09-05 — **the converter was written against a shape that no longer exists on disk.**
  ✅ **Shape chosen: carry the block through.** Only what the CSV actually carries is removed; the CSV is
  written only for a legacy `rules[]` block. ⛔ **Translate was rejected as provably NON-TOTAL** — the CSV is
  the legacy `targetColumn,sourceExpression,transformType` triple and only **4 of 23** Record Transformer
  catalog functions have any back-mapping, losing `args` even then; a lossy translation that looks complete is
  worse than the silent loss it replaces. ⛔ **Refuse was rejected because nothing is lost** — a schema
  component is loaded whole and `RowShaper.mappingSchemaOf` already reads `schema.mapping.fields`, so refusing
  would reject every schema in the repo and extracting a CSV would DOWNGRADE the current spelling.
  ✅ Also found: the `remove` dropped `canonicalName`/`rawName` even in the `rules[]` path, which the CSV has no
  column for. Half (b) — `--apply` falling into the positional list and becoming a directory named `--apply` —
  reproduced, then fixed by stripping it alongside `--dry-run`.
  ⚠ **The regression test INVERTS the one that let this ship:** it walks the committed corpus off `../spaces`
  and asserts non-emptiness first, so it cannot pass by finding nothing. The old test passes only because its
  `legacySpace()` fixture is the sole `rules[]`-shaped corpus in the repo. Original row follows.
  - **P1** · 🔴 **`CONFIG-MIGRATOR-LOSES-MAPPINGS-1` — the migration tool silently DROPS every schema's
    mapping block, and its documented `--apply` form writes to a directory called `--apply`.** Filed 2026-09-17
    from the `MIGRATE-ENRICH-DRIVE-1` drive — **found by DRIVING it, not by reading it.** Two halves, one commit's
    worth of work, filed together because one run surfaced both.
    🔴 **(a) Silent data loss.** `ConfigMigrator.java:185` does `schema.remove("mapping")` unconditionally, then
    writes the Mapping CSV only when `mapping.rules[]` is a non-empty list. **All four committed demo schemas
    carry `mapping.fields[]`, ZERO carry `rules`** (`MappingCsv.encode` reads `targetColumn`/`sourceExpression`/
    `transformType`; the corpus authors `name`/`from`/`fn`/`args`). On the live drive `registry/mappings/` was
    **never created** and `registry/schemas/orders.toon` lost all 8 field mappings — including two SQL
    expressions — while the original was archived away. **Exit 0, no refusal, no warning.**
    ⛔ The class javadoc at `:24` says *"**Refuses rather than loses.**"* Here it loses, on 4 of 4 real files.
    ⚠ **Why no test caught it:** `ConfigMigratorTest.anApplyWritesTheComponentsAndArchivesTheOriginals` passes
    because its `legacySpace()` fixture is **the only corpus in the repo using `mapping.rules[]`**. The standing
    lesson, reproduced exactly — *the unit test agrees with itself, not with the space.* ⇒ any fix must be
    pinned against a COMMITTED schema, not a fixture.
    🔴 **(b) `--apply` is not stripped from the positional args.** `MainApp.java:55-62` strips `--dry-run`
    only, so `--apply` falls into `subArgsList`, `subArgs.length > 1`, and `outRoot = Paths.get("--apply")` — a
    relative directory in the process CWD. The tool's OWN usage text (`MainApp.java:298`) prints this form. It
    still reports `9 file(s) converted` and exit 0, and it still archives the originals, so an operator loses the
    legacy files and finds the registry nowhere near the Space.
    ✅ Both verified on a copy of `spaces/demo` at `36f554ae`; `spaces/` never opened for writing.
    → `okf/backend/config/configuration.md`

- ~~**P1** · **`BUNDLE-ESCAPE-TEST-IS-ENVIRONMENT-DEPENDENT-1`**~~ ✅ **SETTLED 2026-09-17 — verdict: the GATE
  HOLDS, the TEST was fragile. There is no security hole.** The row demanded those two be separated before
  anything was touched, and they were.
  ✅ **The gate was probed DIRECTLY, with no HTTP and no test harness** — `PathJail.resolveJobPath` +
  `ConfigSafetyValidator.check("job", …)` called by hand. It REFUSES a relative value resolving outside the
  allowed roots, REFUSES an absolute value outside them, and correctly ACCEPTS one landing inside a declared
  root. Its answer is a pure function of (resolved path, allowed roots) and is right in every case.
  🔴 **The test's verdict depended on THREE machine facts, none of them the security property:** the roots are
  `${session.executionRootDirectory};${java.io.tmpdir}` (`pom.xml:418`); the value resolved against the
  **process working directory**, because the test helper clears `assist.write.root` before the request so
  `SpaceConfigRoot.current()` is null; and six `..` clamps at the drive root on Windows.
  ✅ **Reproduced, not argued:** at the same commit with NO code change, `-Djava.io.tmpdir=C:\` produces exactly
  the reported symptom (`failed: 0`, escaper `imported`) — because the escape is then genuinely inside a
  declared root, so importing it is the CORRECT answer.
  ✅ The test now pins `assist.safety.roots` to its own `@TempDir` and escapes to an absolute SIBLING of it, and
  **asserts that precondition** so root leakage fails loudly instead of going green on a security property.
  Mutation-proved against the **gate**, not the test; green under `-Djava.io.tmpdir=C:\` and a deep tmpdir,
  both of which flipped the old one.
  ⚠ **Recorded because it is a real coverage gap, not a defect:** `SpaceConfigRoot.current()` is null
  throughout this test, so the gate's **Space-config-root resolution branch is not exercised by it at all**.
  ⛔ **My earlier "master is RED on a security guard" framing was WRONG and is retracted here** — master was
  red on a test whose premise did not hold on a fresh checkout. Original row follows.
  - **P1** · 🔴 **`BUNDLE-ESCAPE-TEST-IS-ENVIRONMENT-DEPENDENT-1` — master's only guard against a bundle
    planting an escaping job path passes or fails depending on WHERE the test's temp directory sits.** Filed
    2026-09-17. `ControlApiBundleNewKindsTest.jobImportRefusesAnEscapingPathValueWithoutAbortingTheBatch` asserts
    a job whose `dir` is `../../../../../../evil_escape` is refused per-item.
    ✅ **Measured, three ways:** it **FAILS** in a clean detached worktree at `c62b46a4` AND at `218d0cff`
    (`failed: 0`, the escaper reported `imported`), and **PASSES** 11/11 in the main checkout at the same
    commits. ⛔ The difference is not a peer's uncommitted work — that hypothesis was formed and then REFUTED:
    the peer commit in between (`ad29e683`) touches no bundle, `PathJail` or `ConfigSafetyValidator` code.
    🔴 **The likely mechanism is the escape DEPTH.** The test uses `@TempDir` and climbs exactly six `..`, so
    whether the resolved path lands outside an allowed root depends on how deep the temp directory is — which
    differs between checkouts. ⇒ **a security property is being pinned by a test whose verdict depends on the
    filesystem layout of the machine running it.**
    ⚠ **Two possibilities and they need separating, in this order:** (1) the GATE genuinely does not refuse the
    escape, and the main checkout only appears green by accident — in which case `JOB-CONFIG-THIRD-PRODUCER-1`'s
    fix is incomplete and a bundle can plant an escaping job path today; or (2) the gate holds and only the TEST
    is fragile. ⛔ Do not "fix" the test to make it green until that is settled — that is how a real gate gets
    papered over.
    ⚠ **This also means CI's verdict and a local verdict can disagree on a security control**, and a fresh clone
    is the CI case. → `okf/backend/config/config-safety.md`

- ~~**P3** · **`CITATION-GUARD-SCOPE-1`**~~ ✅ **FILED AND SHIPPED 2026-09-17 — recorded struck so the
  provenance exists without adding an open row.** The **seventh** instance of the allow-list shape in
  `tools/`: `check-doc-citations.mjs` carried `ROOTS = ['docs','compliance','.claude']` and now scopes the
  whole repo minus a deny-list, like its three fixed siblings.
  ✅ **Measured before fixing: 36 newly-scoped files, 8 dead citations in 4 files, ZERO in already-scoped
  files.** Including 🔴 **two renamed event types in `inspecto/README.md`** (`BatchEvent`→`ConsignmentEvent`, `BatchEventBus`→`ConsignmentEventBus`),
  dead since the 2026-08-31 Consignment rename, shipping on the page `package.ps1` copies to the bundle root
  as the customer's first page — with the guard green throughout.
  ✅ **The scope number reconciles exactly rather than approximately:** 276 current-tier + **13**
  `docs/superpower/` = 289, matching the siblings. The difference is an exemption this guard has deliberately,
  because an in-flight plan citing an unbuilt path *is the plan working*.
  ⛔ **The `./`-strip in `slash()` is LOAD-BEARING** under `ROOTS = ['.']`: `EXEMPT_TIERS` is a plain
  `startsWith`, so without it both never-maintained tiers silently re-enter scope — a widening that would have
  quietly un-exempted the archive.
  ⚠ Also fixed: a cross-repo `docs/ARCHITECTURE.md` in the agent-kernel plan (disambiguated, not deleted — it
  names a repo that does not exist yet), and two demo Space configs the demo README has promised since
  `ff0e8ccf` but which were **never authored** (`git log -S` puts both strings only in the README).
  → `okf/backend/build-run/build-test.md`

- ~~**P3** · **`MIGRATE-MATERIALIZE-1`**~~ ✅ **ANSWERED + CLOSED 2026-09-16 (operator), same call: the clause is STRUCK and `materialize` stays a Job task.** The row's grounding held — a Dataset-registering task is not a `transform.summarize` node, and there was no config file for the converter to walk. Original row follows.
  - **P3** · **`MIGRATE-MATERIALIZE-1` — "every `materialize` task → a `summarize` recipe" is not a conversion the code can express** (filed 2026-09-16, same build). §6 step 1's fourth clause has **no file for the converter to walk**: `materialize` is a *maintenance JOB task* (`MaintenanceJob.java:220` → `MaterializeTask`), declared in a Job, not in a config file a migration of `*_pipeline.toon` / `*_schema.toon` / `*_enrich.toon` ever sees. 🔴 **And the two are not the same operation, so the clause is wrong on grounding as well as on reach:** `MaterializeTask` compiles a **measure spec** (`measures`/`group_by`, BI-7 `MeasureCompiler`) over a source **Dataset**'s trusted relation, `COPY`s Parquet under the data root and **registers/refreshes a `dataset` component**; `transform.summarize` (`BuiltinNodeType.java:156`) is a TRANSFORM node over a DATA relation inside a Pipeline, declaring its own output columns and registering nothing. A recipe cannot carry "and then a Dataset exists". ⚠ The same 2026-08-06 reversal applies: dataset operations are explicitly named as Job work. ⇒ the honest options are **strike the clause** (materialize stays a Job task — the likely answer) or **design a Dataset-registering sink**, which is new design, not a migration. ⛔ Not startable as written. → `MaterializeTask.java` · `okf/backend/control-plane/jobs.md` · the §1 call below
- ~~**P2** · **`GRAPH-LANE-RULE-ROUTED-1` — the graph lane does not implement rule-routed outputs**~~ ✅ **SHIPPED 2026-09-16**, and 🔴 **its stated cause was wrong — the third refutation this gap has produced.** It was filed as *representational* (“`PipelineLift` never sees a Decision Rule, so routed destinations cannot appear in a lifted graph”). True, and irrelevant: `DecisionRuleApplier` runs ABOVE the fork, writes the routed rows itself and then **`DELETE`s them from the relation** (`DecisionRuleApplier.java:233-237`), so both lanes see the identical remainder and **no graph node was ever needed** — the routed outputs only had to be MERGED into the graph lane's `Written`, which is exactly what the flat path does by seeding its list with `applied.outputs()`. Quarantine and drop rules, which also remove rows but produce no outputs, had been running on this lane all along for the same reason — the precedent was already in the code. What stays refused is the pair the flat path also refuses: routing combined with route branches, or with a multi-destination `sinks[]`. ✅ **⇒ THE §6 STEP-2 PARITY GATE IS MET, for the first time ever**: the whole suite under `-Dingest.lane=graph` is **4603 / 0 / 0 / 28, BUILD SUCCESS, zero refusals**. Pinned by two tests in `IngestLaneFlagTest`. Original row text follows.
  - **P2** · **`GRAPH-LANE-RULE-ROUTED-1` — the graph lane does not implement rule-routed outputs** (filed 2026-09-16 from the same run; ⛔ **design-first**). The 13th refusal: `test_etl` fails with *a Decision Rule routed rows, which the graph lane does not implement* (`DecisionRuleWiringTest.routeMovesMatchingRowsToDestinationSubdirWithOutputsAndLineage`, 6 run / 1 error). The refusal is stated at `ConsignmentIngestStrategy.java:204` and enforced in the admission at `:181` — a non-route pipeline diverts only when `applied.outputs().isEmpty()`. 🔴 **The blocker is representational, not a missing executor step, and that is why it is design-first: `PipelineLift` contains ZERO occurrences of `DecisionRule`** — Decision Rules are a **space-registry** fact resolved at run time (`DecisionRuleApplier.java:156`, `DecisionRules.forTarget`), not a config property, so there is nothing in the config for the lift to turn into a node and the routed destinations cannot appear in the lifted graph at all. ⚠ `transform.route` exists only for an **authored** route. So the decision owed first is **where a rule-routed destination becomes a graph node** — lifted late from `DecisionRuleApplier.Result.outputs()` (`:79`) after the rules run, or left off the graph with the routed write kept above the fork as today. ⛔ Do not start by widening the admission: admitting a write the graph cannot represent loses the routed rows silently, which is the one failure mode the whole lane fork exists to prevent. ⚠ Its dedupe lines are the same ones the `(type, key, column)` decision and `/recon/promote` rewrite — check §1 before touching them. Files: `ConsignmentIngestStrategy.java`, `PipelineLift.java`, `query/DecisionRuleApplier.java`. **Verify:** `DecisionRuleWiringTest` green under `-Dingest.lane=graph`. Blocks §2 Row 15's deletion half. → `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.4
- **P2** · **Consignment ELT** — ✅ *Stale opening, marked 2026-09-23:* the "`batches` is structurally singular … an open decision" clause below was **DECIDED and SHIPPED 2026-09-15** (see the indented ✅ blocks under this row), and every pointer here to `CONSIGNMENT-OUTPUTS-NULLRUN-1` is **dead** — that row was refuted on 2026-09-15 (see the census block at the top). ⇒ The surviving item is `generation`, which **stays OPEN by operator choice 2026-09-23**: it is always a literal `0` (`ConsignmentOutputs.java:375`), nothing reads it for staging, and revisions use batch-id file names instead. Earlier text: (three items added 2026-09-07 from the archived plan's §11.2/§11.7/§15, which BACKLOG never carried: **`batches` is structurally singular** — its `schema_name`/`output_table` are one-per-row while a Consignment's EL emits a row set *per schema*, so this needs either one row per `(consignment, schema)` or a child table, an open decision; ~~whether a **durable `DeliveryReceiptStore`** exists beyond the in-memory one is a one-grep check still owed~~ — ✅ **ANSWERED 2026-09-14: it exists** (`inspecto-engine/.../notify/DbDeliveryReceiptStore.java`, wired through `ServiceStores`/`OperationalDb`); and §8.4's SLA config object is dropped with sealing, not pending.) `generation` is on the registry but compaction does not stage generations — ⚠ **and it is never incremented at all** (`ConsignmentOutputs.java:375` writes a literal `0` into the `ConsignmentOutput` constructor — *re-grounded 2026-09-16; the row's `:336` has drifted, the claim itself holds*), so "dead field" was closer to true than the 2026-09-14 correction allowed; ~~`run_id` is `null` everywhere~~ ~~✅ CLOSED 2026-09-13 — `run_id` carries a real attempt on every production path~~ 🔴 **BOTH of this row's key claims are REFUTED, regrounded 2026-09-15, and are now `CONSIGNMENT-OUTPUTS-NULLRUN-1` (§4, P1): the key was not "still not addable" — it was ADDED on 2026-09-13 (`DbConsignmentOutputStore.java:127`, `UNIQUE (consignment_id, path, run_id)`) together with the `ON CONFLICT DO UPDATE` this row calls unbuilt (`:230`); and `run_id` is NOT supplied on every production path, so those rows escape the key.** ⚠ *Line refs re-grounded 2026-09-16 — the row read `:122`/`:225` and both had drifted.* 🔴 **And the null-`run_id` CITATION does not check out:** the row named `ConsignmentGraphRunner.java:83`; the class lives at `inspecto-engine/.../pipeline/exec/ConsignmentGraphRunner.java`, and a grep of the whole file for `runId`/`run_id` returns **nothing** — line 83 is unrelated, and the `null` at `:108` is the `onAllBranchesDurable` bypass argument, not a run id. ⇒ the *claim* may still hold (this lane plainly never threads one), but this citation does not prove it. ⛔ Re-derive the null-`run_id` path from the write site before acting on `CONSIGNMENT-OUTPUTS-NULLRUN-1` (§4), which is where the claim is tracked. ⛔ Do not re-file either claim from this row; §7.4 rollup cache deliberately unbuilt until read-time aggregation is measurably slow; §7.3 unpartitioned fallback stands by operator call — revisit if flat summary targets appear. → `okf/backend/engine/db-layer.md` §3.9

  ✅ **DECIDED 2026-09-15:** **`batches` gains a CHILD TABLE for per-schema outputs; it stays one row per ingest.**
  Chosen over widening the natural key to `(consignment, schema)`. Reason for the record: widening the
  key changes what "a batch" MEANS, so every existing count silently re-denominates — and ⚠ a key
  widening on this table's sibling is exactly what produced the `CONSIGNMENT-OUTPUTS-NULLRUN-1` P1
  scare on 2026-09-15, two days after `consignment_outputs` gained its UNIQUE key early. The child
  table costs a join on reads that want output tables, and changes no row's identity.

  🔴 **RE-GROUNDED 2026-09-15 before building — the child table ALREADY EXISTS, and building a new one
  would have duplicated it.** Two premises in this row were wrong:
  (a) **`batches` is not a SQL table.** It is a per-run CSV audit ledger (`ConsignmentAuditWriter`, header
  `consignment_id,pipeline,schema_name,output_table,…`), mirrored into DuckDB as
  `inspecto_status_batches(pipeline, seq, payload)` where the whole row is an opaque JSON blob — not
  columns. "Add a child table" therefore had no relational thing to be a child OF.
  (b) **`consignment_outputs` is already a per-Consignment child table, written on the ORDINARY ingest
  path** — `ConsignmentIngestor.finalizeSource:473`, not only by the processor/summary path — and it is
  default-ON (`consignment.outputs.backend=duckdb`) so it exists in **Personal** too, with an established
  UPSERT, state lifecycle and read API.
  ⇒ **The real gap is representational, not structural:** `ConsignmentOutputs.fromLineage` takes ONE
  `tableName` for the whole batch and `ConsignmentIngestor` passes `batch.table()`, so a segmented ingest's
  rows are all stamped with the batch's single table. The segment identity survives only inside each output
  file's PATH. ⇒ Make the existing child table per-schema accurate; do not add a sibling ledger.
  ⚠ **The two segmented ingesters are NOT structurally alike**, which the row's framing implies:
  `UnionModeIngester` has `segKey` right in its write loop, but `GenerationModeIngester` writes per MEMBER
  through `DuckDbRecordSink`, which fans out to segments internally — there the segment is known only inside
  `generationFlush(Seg)`. Any attribution must be collected in BOTH places or one path silently keeps the
  flattened value.
  ⚠ `PartitionOutput` is the wrong place to carry it — ~14 construction sites across two modules. The
  established idiom is `IngestOutcome`'s existing `bounds` map, keyed by output file, built in that same
  loop.

  ✅ **SHIPPED 2026-09-15 — the EXISTING child table made per-schema accurate; no new ledger.**
  `IngestOutcome.schemaByOutput` (output file → segment key) threads from the ingesters through
  `commit`/`finalizeSource` into `ConsignmentOutputs.fromLineage`, which stamps each registry row with the
  schema that wrote it instead of the batch's single `batch.table()`. **`batches` is untouched — still one
  row per ingest**, exactly as decided.
  ⚠ Collected in **two** places, because the two segmented ingesters are not alike: `UnionModeIngester`
  has `segKey` in its own write loop; `GenerationModeIngester` writes per MEMBER through `DuckDbRecordSink`
  and the pairing exists only inside `generationFlush(Seg)`, so the sink now exposes it. Missing either
  leaves that path silently flattened.
  ⚠ A file with no attribution keeps `batch.table()` — which is every file on a single-schema path — so the
  default ingest is byte-identical. The no-map overloads are kept for exactly that reason.
  ⛔ **What this does NOT do:** no route, no UI, and no per-schema `member_count`/`rejected_count`. Those
  are per-BATCH facts that were never computed per segment, and inventing them would need new aggregation
  in `writeAudit`, not a new sink. Row-, byte- and file-counts per schema ARE answerable now, by grouping
  `consignment_outputs` on `table_name`.
- **P2** · **Completeness KPI (when the hold lifts)** — K2 wiring (`FileSequenceGaps` analysis shipped `14c6ef0e`, wiring not built; ⚠ **"needs `SeqScope`" is STALE as a blocker — regrounded 2026-09-15: `SeqScope` already ships** as a nested enum at `FileSequenceGaps.java:74-79` (`PER_BUCKET`/`CONTINUOUS`). The type exists; only the wiring does not. ⚠ **K1 is unwired too**, which this row never said: `DbConsignmentOutputStore.dailyVolume()` has zero call sites, same as `VolumeBaseline`/`FileSequenceGaps`); K4 `kpi.completeness` job type (`JobTypeProvider` + descriptor + `ParameterDecl`s, cron'd, one config per pipeline, signal + deduped Incident on breach, must refuse loudly when `-Dconsignment.outputs.backend=none`). ✅ **K5 SHIPPED 2026-09-07** — 🔴 corrected 2026-09-09: this row and `INDEX.md` both listed K5 as remaining while the plan's own slice table and §5 recorded it done, a three-way split. Non-blocking: signal type naming `kpi.completeness.evaluated`/`.breached` (🔴 **"do not grow the `EventType` enum" is wrong in KIND — corrected 2026-09-15: there is no enum.** `EventType.java:19` is a class of `public static final String` constants, deliberately open per its own javadoc, and no `kpi.*` entry exists. The constants-class guidance still applies; the thing it warns about does not exist. 🔴 **And it is wrong in KIND a SECOND time — re-corrected 2026-09-16:** `kpi.completeness.*` is a **Signal** type, not an Event type at all. `EventType`'s vocabulary is `Event.type` (`UPPER_SNAKE`), and `EventType.SIGNAL`'s own javadoc says the dotted signal type rides in a `SIGNAL` Event's **attributes**. ⚠ There is **no `SignalType` class anywhere** — ~10 dotted literals are scattered across `JobService`, `ReconRunJob`, `AlertEvaluateJob`, `PipelineConsignmentSignal`, `SchedulerAuditTask` and `SignalIngress`, so "add a constant" has no home to add it to; whether to create one is an open call in the K4 design), K3 baseline-window default as a job parameter. ⚠ `VolumeBaseline`/`FileSequenceGaps` have no production caller today. 🔴 **Three items had no board home at all until 2026-09-09**, found when archiving the plan: (a) **`KPI-UNKNOWN-1`** — a null-`bounds` sink's daily count is **UNKNOWN, not zero**, and the KPI must carry that end to end (only the registry-off trap was ever filed); (b) where the sequence **template** itself comes from — the Collector's existing one, a job parameter, or the Collector's with an override — still undecided; (c) K1's and K3's acceptance criteria, now in `okf/capabilities/observability/observability.md` §3.9. → `okf/capabilities/observability/observability.md` §3.9 · `archived-documents/plans-archive/completeness-kpi-plan.md`
- ~~**P2**~~ · ✅ **CLOSED 2026-09-17 (bookkeeping) — the row's own content below already says SHIPPED
  2026-09-15; only the outer header had never been struck.** TRIGGER (operator, 2026-09-13): an author needs to declare "this Collector takes NO data
  extensions". · ~~**`SCHEMA-FORM-EMPTY-LIST-1` — the UI cannot author an explicit empty list**~~ ✅ **ALREADY
  SHIPPED — found by the 2026-09-16 ground-truth sweep of this range.** `schema-form.component.ts` now renders an
  **"Explicitly none (the default does not apply)"** toggle on every non-required `list` control
  (`isExplicitlyNone` / `setExplicitlyNone`, `schema-form.component.ts:315-325,965-995`), pinned by three specs in
  `schema-form.component.spec.ts:252-282` that keep cleared-`null` and explicit-`[]` apart in both directions.
  Landed in `9d3dbfce` *(2026-09-15 23:12)* — **before** this row was last touched, which is how it survived.
  🔴 `§0` still lists this id as pending (line ~297); that census line is outside this sweep's edit range and is
  reported to the caller rather than hand-edited. Superseded text follows. (filed 2026-09-13,
  split out of the Unpack codecs row, where it was a UI defect nobody would have found). schema-form's `list`
  control writes an empty list as `null`, so the explicit `data_extensions[0]:` opt-out is unauthorable from the
  UI — `null` means "unset, use the default", which is the opposite of "deliberately none". ⚠ Hand-authored TOON
  can still express it, so this is an authoring gap, not a capability gap. → `okf/backend/engine/unpack-stage.md`

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** An author needs to declare a Collector that takes NO data extensions.
  ⚠ **Smallest of the four authoring rows by a wide margin** — it is one control's null-vs-empty-list
  semantics: schema-form writes an empty list as `null`, and `null` already means "unset, use the
  default", which is the opposite of "deliberately none". ⛔ Do not fix it by making `null` mean empty
  somewhere downstream; the two states must stay distinguishable at the control.
  ✅ **SHIPPED 2026-09-15** — an "Explicitly none" toggle on a non-required `list` control writes `[]`; clearing
  entries still writes `null`. Neither path produces the other. As-built `okf/backend/engine/unpack-stage.md`;
  pinned by two new `schema-form.component.spec.ts` cases.
- ~~**P2** · **`TYPEFLOW-DATASET-COLUMNS-1` — a Dataset's columns are never derived from the pipeline that
  fills it**~~ ✅ **CLOSED 2026-09-16** (`9d8c2365` + `901ff1bd`) — see the verdict inside the row; ⛔ two of
  the row's own assertions were refuted on grounding. Original row text follows. (filed 2026-09-11, split out of `TYPEFLOW-CONSUMERS-1` (b)). A `DatasetColumn` is
  `{name, type, role}`; `TypeFlow.sinkColumns` yields only `{name, type}`, and the role heuristic lives
  **client-side**, wired to live-query results rather than to a derived schema. 🔴 **`MaterializeTask` is the
  one place a dataset is registered by code rather than a human, and it writes NO `columns` at all** — so
  auto-population is absent even where auto-registration already happens; that is the natural first consumer.
  ⚠ `GET /config/schema/derived` is wired end-to-end (`ConfigService.derivedSchema` →
  `DerivedSchemaPanelComponent:29`) but the panel is **ORPHANED** — its selector appears only in its own spec
  and the `schema/index.ts` barrel, in no template or route, so nothing mounts it. *(Re-grounded 2026-09-13 —
  still true.)*

  ✅ **BOTH of the design's first two steps have SHIPPED, and this row's statement of them was STALE**
  (re-grounded 2026-09-13; the same two sentences were still present-tense in
  `archived-documents/plans-archive/dataset-column-derivation-plan.md` §2 and are corrected there too):
  - ⛔ ~~"the role heuristic exists in three unpinned copies"~~ — **collapsed to one per language and
    PINNED.** `inspecto/viz/result-set.ts:49` owns `roleFor`; `studio/datasets/dataset-types.ts:85-91`
    now *delegates* to it and says so in its own comment. Pinned by `column-role.contract.json` +
    `ColumnRoleContractTest` (Java) + `column-role.spec.ts` (TS).
  - ⛔ ~~"`MaterializeTask`'s refresh replaces the whole document, destroying authored roles"~~ — it is a
    **keys-by-owner MERGE** (`MaterializeTask.java:118-128`): the stored document is seeded first and only
    four job-owned keys are restated, so authored `columns`/roles/labels ride through untouched. The code
    carries the rule as a comment so a future field list cannot silently start dropping keys.

  ✅ **TRIGGER (operator, 2026-09-13):** a **second code-registered dataset producer** appears. With one producer (`MaterializeTask`), a
  human authors columns once and they now survive a refresh — the merge fix shipped; the pain begins when code
  registers datasets in more than one place and hand-authoring stops scaling.

  ✅ **CLOSED 2026-09-16 — steps 3+4 SHIPPED and the contract pin is whole.** 🔴 **Two assertions this row
  carried until then were WRONG**, and both would have sent a shift down the wrong path: (a) ⛔ ~~"no
  DuckDB-type → coarse-type mapping exists anywhere"~~ — it is `ResultSetDescriptor.columnType`
  (`ResultSetDescriptor.java:54-73`), with the Q2 tie-break in `describeTypeNames` (`:99-110`) and Q3's
  `hidden` in `MaterializeTask.mergeColumns` (`:218-250`); (b) ⛔ ~~"carry `role` through
  `TypeFlow.Column`/`sinkColumns`"~~ — **the design explicitly REJECTS this.** Option A needs no `TypeFlow`
  at all: the task already holds the real relation, so `DESCRIBE` over the **written Parquet** is truthful
  where a static shape is not. `TypeFlow.Column` stays `(name, type)` deliberately. The build was
  `9d8c2365`; ⚠ **Q4's second half was declared but NOT enforced by it** — `"coarseTypes"` was added to the
  contract file and *nothing on either side read the key* (one grep hit, the declaration itself), so the pin
  covered the heuristic only, the exact half-contract Q4 exists to rule out. Closed by `901ff1bd` (23
  `duckdbTypeCases`, vocabulary closure across both `columnType` overloads, JDBC↔DuckDB agreement —
  previously an unchecked javadoc claim — and the TS union closure). ⇒ **A declared contract key that no
  checker reads is not a pin.** Residuals filed as their own rows: `COLUMN-TYPE-SECOND-INTERPRETER-1` below,
  and the plan's accepted deferral that hidden columns accumulate with no pruning story.
  → `okf/backend/engine/catalog-vs-executors.md`

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** A second code-registered dataset producer exists, so hand-authoring columns
  stops scaling — which is exactly the pain the trigger named.
  ⚠ **Steps 1 and 2 already shipped** (the role heuristic collapsed to one copy per language and
  pinned; `MaterializeTask`'s refresh proven a keys-by-owner MERGE), so ⛔ do not re-derive them.
  **What is owed is steps 3+4 only**: `TypeFlow.Column` is still `record Column(String name, String
  type)` (`TypeFlow.java:30`), `sinkColumns` (`:75`) yields no role, its only consumer is
  `ConfigPreviewRoutes.java:110`, and no DuckDB-type → coarse-type mapping exists anywhere.
  ⚠ Q4 of 2026-09-14 pinned **both** the heuristic and the coarse type vocabulary as a compatibility
  surface — so the mapping built here **cannot change freely afterwards**.
- **P3 · RELEASE-GATED (next MAJOR), not demand-gated** · **`GLOSSARY-CASE-1` — split the two `Case`s in code**
  ✅ **Re-gated 2026-09-13 (operator).** It is not waiting for anyone to ask — it renames **four published
  routes** (`AgentRoutes.java:133,138,144,150`), so it waits for the version that may break them, like the
  Tier-3 vocabulary rows. ⛔ Do not do the non-breaking half alone: two spellings for one concept during the
  interim reads worse than the collision does. ⚠ Re-grounded 2026-09-13 — both senses still collide
  (`ObjectType.CASE` vs `intelligence/investigation/Case.java`), in the UI too. (filed 2026-09-10 by Sprint 7.3, `SPEC-GLOSSARY-1`). The
  glossary now defines both senses and says which keeps the word: `ObjectType.CASE` (groups Incidents — the pane, the
  user guide and the controls matrix all use it) stays `Case`; the Assistant's `com.gamma.intelligence.investigation.Case`
  — one RCA playbook run against one Incident, and the **only** type in the repo actually named `Case` — becomes
  **`TriageRun`**. 🔴 **RETARGETED 2026-09-22 (operator, decision `D-E1`): this sense becomes **Triage** / **Triage Run**, NOT *Investigation*.** *Investigation* was claimed by the Link Analysis enquiry object, which had the stronger claim on it — the routes are `/inv/*`, the capability is `INV-1` and `InvRoutes` spells it out. This side moved because its rename is unstarted. ⚠ **And its stated gate premise did not survive verification:** the row is RELEASE-GATED because it "renames four published routes", but `v3.11.0` contains no `AgentRoutes` and no `/agent/cases` at all — "published" there means *registered in the control API*, not *shipped*, so the breakage set is empty. ⛔ Re-gating is an operator call and is NOT made here; only the target noun is. ⚠ **Four published routes are in scope** (`/agent/cases*`), so this needs an
  alias or a deprecation window, not a silent flip — that is why 7.3 filed it instead of applying it. Touchpoint list in
  `GLOSSARY.md` §13. ⛔ Do **not** also rename `mode: case` (route branching) or `caseType` (line of business): different
  words that merely look alike, and `caseType` feeds RBAC data scopes.
- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** the first operator who asks for a spreadsheet download — which is
  also when the new-dependency question gets answered, rather than in advance. · **D-8 XLSX export** — zero groundwork (no spreadsheet library in any pom); gated only by a bare label — state the operator question before answering it. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §9 D-8

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** An operator wants a spreadsheet download.
  ⛔ **The new-dependency question is NOT answered by the trigger** — that was the point of gating it
  this way. There is **no spreadsheet library in any pom**, and picking one is an operator sign-off
  filed as an owed input in §1. ~~⇒ this row cannot start until that name exists.~~
  ✅ **STALE AS A BLOCKER — corrected 2026-09-16 by the ground-truth sweep of this range.** §1 records the owed
  input as **ANSWERED 2026-09-16: Apache POI**, and marks `D-8` "now unblocked" — while this row, ~600 lines
  away in the same file, still read "cannot start". That is the headline-vs-detail split this sweep exists to
  catch. ⚠ The *first* clause still holds and is the work: **no pom declares a spreadsheet dependency**
  (verified 2026-09-16 — `grep -rn "poi\|xlsx" --include=pom.xml` over the reactor ROOT returns no dependency),
  so adding POI is step one. ⛔ §1's conditions travel with the answer: add it to **ONE** module, never the
  parent, and re-check the bundle-size and dependency-review guards — POI's transitive tree grows every edition
  bundle. 🔴 §1 also still carries an **unstruck duplicate** of this same owed input (line ~350, the
  pre-answer wording); that line is outside this sweep's edit range and is reported to the caller.

  ✅ **SHIPPED 2026-09-16 — and NO LIBRARY WAS ADDED, so the POI paragraph above is superseded, not
  pending.** `PipelineDocumentXlsx` writes the workbook with DuckDB's `excel` extension
  (`COPY … TO … (FORMAT xlsx)`), the same extension the `xlsx` PARSER reads with. ⛔ **Do not add POI** —
  verified there is no `org.apache.poi` declaration in any pom. ⚠ `grep poi` over the poms returns nine
  hits and **every one is a substring of "point"/"policy"**; match `org.apache.poi` or `<artifactId>poi`.

  🔴 **RESIDUAL, FOUND 2026-09-16 WHEN THE SUITE FIRST RAN IN CI — the premise this row was closed on is
  NOT PROVEN.** The dependency question was dissolved by the claim that the capability is *"already
  bundled and already staged for air-gapped installs"*. On a clean CI runner the extension **cannot
  load**: `PipelineDocumentXlsxTest.writesARealWorkbook` errored with *"DuckDB's 'excel' extension is
  required for frontend 'xlsx' but could not be loaded"* (`DuckDbExtension.ensureLoaded:55`). The
  original 4/4 green — 1.3s of real I/O — was a **warm developer cache**, which is exactly the evidence
  shape that cannot distinguish "bundled" from "cached here".
  ✅ **Operator call 2026-09-16: GATE THE TEST on extension availability**, done — the method now calls
  `requireExcelExtension()` and SKIPS rather than fails, mirroring `XlsxParsingTest#open`; the other three
  methods stay ungated. ⛔ **The gate buys an honest build, not a working feature.** XLSX export is now
  proven only where the extension is already warm, and **whether it works in a shipped or air-gapped
  bundle is OPEN and owed to the operator** — two standing lessons point the same way: no release has ever
  shipped a DuckDB extension because CI populates no cache, and *staged is not loadable* (autoload ignores
  `-Dduckdb.extension.dir`). ⚠ A green reactor is **not** evidence this works for a customer.
- **P3** · **D-11 hand-authored `relations` component** — deferred until a business relation exists that no Pipeline exercises. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §3.4

  ⬜ **RE-CONFIRMED NOT FIRED 2026-09-15** — every business relation in play is already expressed by
  a Pipeline, so the row stays correctly deferred. ⚠ Asked and answered, not overlooked.

### Control plane, jobs, notifications, queries

- ~~**P2** · **API v1**~~ ✅ **CLOSED 2026-09-17 — this is a STANDING POSTURE, not a row.** Both surviving
  clauses are phrased *"as demanded"*, and `okf/backend/control-plane/api-v1.md` **already states them as
  binding policy** (`:100-102` — a new list endpoint over an *unbounded* table MUST use `Cursor.encode/decode`,
  a bounded one MAY use `ApiContext.paged`; `:62` — further singleton reads adopt `ETags.respond` as demanded).
  ⛔ **A row that can never be finished is not a backlog item** — the doc owns the rule and the guard-free
  posture with it.
  ✅ Verified rather than assumed: `Cursor.encode` has **4 adopters across 3 files** (`JobRoutes` twice, plus
  `inspecto-ops/ObjectRoutes` and `inspecto-events/EventRoutes`), matching `api-v1.md:75-99`; `ETags.respond`
  has 13 call sites; the jlink/Nimbus clause was already struck and its as-built is homed in
  `okf/capabilities/editions/editions.md:222-223`. Original row follows.
  - **P2** · **API v1** — adopt the cursor-pagination seam on further list families as demanded (4 adopters live); adopt `ETags.respond` on further singleton reads as demanded; ~~Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed)~~ ✅ RE-VERIFIED 2026-09-15 — Standard package with embedded runtime boots and answers `/health` (as-built `okf/capabilities/editions/editions.md` §3.3). The remaining adopters are demand-gated. → `okf/backend/control-plane/api-v1.md`
- **P2** · **Bundle / Exchange** — ~~`requires` present-but-different classification~~ 🔴 **ALREADY SHIPPED 2026-07-18 (`8770cda9`, whose subject is this clause verbatim: *"bundle 'requires' gains present-but-different classification"*) — this row carried it as open for two months.** `BundleRoutes.java:212-228` classifies every `requires` ref as `missing` / `different` / `satisfied`, comparing the ref's carried `originHash` against a recomputed `targetHash` (`:221-225`), and deliberately reports `satisfied` rather than `different` when the bundle is v1/older and carries no `originHash` to disagree with. What remains open is only the second clause: per-editor **"load as draft" import** — design first, likely multi-session. ⛔ Do not fake it with a cross-kind `enabled:false` stamp. 🔴 **The symbol this row named does not exist**: there is no `BundleTransferService` anywhere in the tree (grep from the repo root, all modules). The straight-through write is `BundleRoutes.importBundle` over `BundleImporter`/`BundleExporter` (`inspecto/src/main/java/com/gamma/service/`), and that half of the claim is confirmed — no draft seam, no staging state. *(Corrected 2026-09-16 by grep of the symbol, not of the row.)* → `okf/backend/control-plane/exchange-sharing.md`
- **P2** · **`D8-SES-SNS-1` (Notifications)** — ✅ **soft-bounce retry scheduling SHIPPED 2026-09-15** (`12ebbe47`: `SoftBounceRetryTask` → dispatched by `MaintenanceJob:169`, calling `NotificationService.retrySoftBounce`; both traps built — `lastAttemptAt()` as a separate retry clock and `softBouncedAndUnresolved()` in place of a bare `containsKey`). 🔴 **This headline said *“distinction recorded, nothing retries”* for a day after it stopped being true, while row 34 at §0 said SHIPPED — the SAME FILE stated both. ⛔ When a row's work ships, fix the HEADLINE, not only the detail block: a reader triaging by headline picks up finished work.** What remains: SES/SNS adapter (needs SNS subscription confirmation + a cert-chain fetch from a validated `amazonaws.com` URL — ⚠ outbound fetch from an unauthenticated callback path deserves its own review); GeoIP; auth-gated per-user prefs / security triggers. (Auto-disable policy is a §1 decision.) → `okf/backend/control-plane/events-metrics.md`

  ✅ **DECIDED 2026-09-15:** **build soft-bounce retry scheduling ONLY.** ⛔ The SES/SNS adapter stays filed and
  does **not** ride along: it needs SNS subscription confirmation plus a cert-chain fetch from a
  validated `amazonaws.com` URL, i.e. **an outbound request induced by an unauthenticated callback** —
  a request-forgery-shaped surface that gets its own review and its own commit. ⚠ The retry half is the
  correctness gap: a soft bounce is deliberately never suppressed, and today nothing retries it either,
  so it is simply dropped in silence.

- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** the first **space-to-space comparison request**. ⚠ The trigger governs the
  comparison half only — predictive maintenance stays deferred to AGT-5 regardless. · **Job framework** — Maintenance COULD tier: space-to-space comparison; predictive maintenance (AGT-5 territory) deliberately deferred. → `okf/backend/control-plane/jobs.md`

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** A space-to-space comparison has been asked for.
  ⚠ **The trigger governs the comparison half ONLY** — predictive maintenance stays deferred to AGT-5
  regardless, exactly as the row states. ⛔ Do not let one firing trigger discharge the other half.

  ⛔ **POSTPONED by the operator 2026-09-16 (`b8030912`) — DESIGN ONLY, do not start it.** The design
  landed at `superpower/space-comparison-design.md` and its own header now reads *"POSTPONED … lower
  priority"*, with its four §5 decisions **parked, not owed** — nobody is to be chased for them. 🔴 This
  row said *"no longer demand-gated, re-ranked P2"* and said nothing about the postponement, which is the
  headline-outlives-its-detail defect: **a fired trigger removes a GATE, it does not set a RANK.** ⚠ No
  code exists (`SpaceComparison` / `/spaces/compare` grep clean across Java and TS); the grounding in the
  design stays valid until `storage_trend` or `SpaceConfigRoot` changes shape.
- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** the first **non-engineer** authors a `findings-spec`. TOON-through-CRUD is
  workable for someone who reads the schema; for an analyst the editor is the difference between usable and not.
  · **D6 spec-authoring UI** — a matrix/editor for `findings-spec`; today authored as TOON through generic `/components` CRUD. Nothing broken without it. → `okf/frontend/features/objects.md`

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** A non-engineer needs to author a `findings-spec`.
  ⚠ Note the trigger was specifically **a non-engineer**, not an engineer finding TOON-through-CRUD
  tedious — so the deliverable is judged by whether an analyst can use it, not by whether it is
  faster than the generic `/components` path.
- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** the **first cross-Space consequence** — a Signal in one Space
  that must cause something in another, which none of today's `/apply`-only machinery can express. ⚠ All three
  items below are speculative extensions of a backbone that works today, so one gate covers them.
  · **Signal / Decision networks** — optional S8 (connector-direct emission + cross-space controller); a general event-triggered consequence policy gate (still `/apply`-only); RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet). → `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md`

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** A Signal in one Space must cause something in another.
  ⚠ **The single gate covered three speculative items**, and firing it does not commission all three:
  the cross-space controller and connector-direct emission (S8) are what the trigger names; the
  **RFC 6902 JSON Patch state deltas for AG-UI still have no consumer** and should not ride along.
  ⛔ Scope to the consequence that was actually asked for.

  🔴 **RE-GROUNDED 2026-09-17 — still open, needs a design pass, NOT a quick build.** Confirmed against
  current code: cross-Space consequence is **structurally absent today, not merely unguarded**. Each
  Space is fully isolated at the runtime-instance level, not by a checkable flag: `EventLog` holds one
  ledger per Space in a `SPACES` map keyed by id (`inspecto-event/src/main/java/com/gamma/event/
  EventLog.java:77`, routed by thread MDC, `:100-110`), and `SpaceContext` gives each Space its own
  `CollectorService`/`JobService` instance (`inspecto/src/main/java/com/gamma/service/
  SpaceContext.java:17-19`, "each fully isolated"). `Signal.space` (`inspecto-engine/src/main/java/
  com/gamma/signal/Signal.java:27-29`, persisted via `ATTR_SPACE`) is descriptive metadata only — nothing
  reads it to gate anything. `DecisionRoutes.apply`'s consequences (`emit-signal`, `start-job`,
  `trigger-pipeline`, `create-incident`, `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:
  36-41`) and `AlertEvaluateJob` ("runs **this space's** authored Alert Rules",
  `inspecto-engine/src/main/java/com/gamma/job/AlertEvaluateJob.java:12`) always bind to the request's
  own Space instance — there is **no target-space parameter anywhere in the apply path** (grepped
  `DecisionRoutes.java`: no match on space-id params), so there is nothing to author a refusal for yet.
  ⚠ **Correcting a stale premise:** Space/ABAC row-level enforcement (`PolicyEngine`/`AccessDecider`) IS
  Enterprise-only (`inspecto-policy/src/main/java/com/gamma/policy/PolicyEngine.java:19`; with no
  decider on the classpath, Personal/Standard show every row — `inspecto/src/main/java/com/gamma/
  control/RowScope.java:9-14`) — but that ABAC layer is irrelevant to this row: the Space-instance
  isolation above is structural plumbing, not the ABAC PDP, and it applies to every edition.
  ⛔ **No small safe increment exists to ship here.** There is no missing validation/refusal to add
  because no code path can currently even name a different Space to target — the gap is the absence of
  the whole cross-space controller (S8), which is a genuine cross-boundary design (does a consequence
  need its own authorization independent of the triggering Space's caller? does the target Space's
  EventLog need to accept externally-originated Signals, and under what identity?). Left **open, P2,
  ungated for build** pending that design pass — no code change made this session.
  → `okf/backend/control-plane/signal-backbone.md` §"Open / deferred" · `okf/backend/control-plane/
  decision-rules.md`
- **P3** · **Queries / BI** — `graph`/`spatial`/`search`/`api` QueryTypes; more `$`-resolvers. (DuckDB `spatial` extension itself: zero demand re-verified 2026-08-26 — do not re-open on speculation.) → `okf/backend/control-plane/queries.md`
- **P3** · **EXPORT-1 outbound object-storage export (S3 / HDFS)** — sequence of record: operator `aws s3 sync`/rclone of `data/<store>/database/` first (zero code); build the push post-action (outbound mirror of the connector SPI reusing `AwsSigV4`) only on demand; HDFS only via an S3-compatible gateway — ⛔ never `hadoop-client`. → `okf/backend/engine/object-storage-export.md`
- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** the first install where hand-edited policy TOON goes wrong. ⚠ The
  read-only Policies tab and the "why denied?" endpoint already make such a mistake diagnosable, which is what
  bounds the cost of waiting. · **Security: policy-authoring UX** — a matrix/create editor beyond hand-authored TOON (seed visibility, "why denied?" endpoint and read-only Policies tab already shipped). Non-blocking. → `okf/backend/editions/auth-security.md`

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** An install has had hand-edited policy TOON go wrong.
  ⚠ The read-only Policies tab and the "why denied?" endpoint already shipped, so the mistake is
  **diagnosable** today — which means the build is about preventing the error, not explaining it, and
  the existing diagnosis surfaces are the thing to extend rather than duplicate.
- ~~**P2**~~ · ✅ **CLOSED 2026-09-17 (bookkeeping) — the row's own content below already says SHIPPED
  2026-09-15 with a test added for the new grain; only the outer header had never been struck.**
  TRIGGER (operator, 2026-09-13): the first operator who promotes two Breaks on one key and
  finds only one Incident. · ~~**`BREAK-DEDUPE-GRAIN-1`**~~ ✅ **SHIPPED 2026-09-15 (`5d4c5e61`, `(type, key, column)` parity; as-built `incidents.md` §promoted read-back) — the text below is kept as history only** · **`BREAK-DEDUPE-GRAIN-1` — the server dedupes promotion on `key` alone, but a
  Break's identity includes its COLUMN** (filed 2026-09-13 while shipping `BREAK-INCIDENT-RESOLVE-1`;
  pre-existing, not introduced by it). `ReconRoutes` stores `breakKey = key` and dedupes on that
  attribute, while the client's own `breakId` is `type|key|column`
  (`reconciliation-types.ts:173`). So two value Breaks on one key differing only by column — `amount`
  vs `count` — are **ONE Incident** to the server: the second promote is suppressed with *"an Incident
  for key … is already open"*. ⚠ **Untested in either direction**: `aDifferentBreakInTheSameReconciliation
  OpensItsOwnIncident` varies only the key, so nothing pins which behaviour is intended.
  ⚠ It may well be RIGHT — one key being worked is arguably one Incident — which is why this is a
  product question, not a bug fix. ⛔ Whoever answers it must change the dedupe attribute and the client
  key together: the promote-offer read is keyed on `b.key` precisely to match the write
  (`incidents.md:161`), and changing one alone would make the offer and the dedupe disagree.
  → `okf/capabilities/incidents/incidents.md`

  ✅ **DECIDED 2026-09-15:** **a Break's Incident identity is `(type, key, column)` — FULL PARITY with the
  client's own `breakId`** (`reconciliation-types.ts:173`). The server's dedupe attribute adopts that
  shape, so the two stop disagreeing by construction.
  ⛔ **Change the dedupe attribute and the client key in ONE commit.** The promote-offer read is keyed
  on `b.key` precisely to match the write (`incidents.md:161`); moving one alone makes the offer and
  the dedupe disagree, which is a worse failure than the one being fixed.
  ⚠ **Accepted cost, stated and chosen:** this FRAGMENTS Incidents. A value Break and a missing-row
  Break on the same key *and* column now open separate Incidents, and a row broken in six columns
  opens six. ⛔ Do not "improve" this back toward key-only dedupe without reopening the decision.
  ⚠ Nothing pins the behaviour in either direction today — `aDifferentBreakInTheSameReconciliation
  OpensItsOwnIncident` varies only the key — so the new grain needs a test that varies the COLUMN.

  ✅ **SHIPPED 2026-09-15.** Both halves moved in one change, as required: `promote` writes a composite
  **`breakId`** Incident attribute and dedupes on it, `promoted` indexes by it, and the SPA's `breakId()`
  renders the byte-identical string (`isPromoted`/`incidentFor` now look up by it, not by `b.key`).
  ⚠ **A single composite attribute, not three matched ones.** `ObjectAccess.hasActiveMatching` takes a map
  and *could* match three, but `activeAttributeIndex` indexes ONE attribute — a three-attribute dedupe
  would leave the offer read unable to express the grain, and the symmetry is the load-bearing part.
  🔴 **The decision said "parity with the client's `breakId`" — and the client's `breakId` was NOT
  injective.** It joined with `KEY_SEP`, which is `''`, while a recon `key` is itself a join of the key
  columns' values, so keys routinely contain the separator (the repo's own fixtures use `EU|voice`).
  Mirroring it verbatim would have made `(break, "EU|voice", "amount")` and `(break, "EU", "voice|amount")`
  ONE identity — the very collision this row removes, reintroduced one level down. Both sides now escape
  `\` then `|`. ⛔ `KEY_SEP` itself was left alone: it belongs to `keyOf`, and changing it would have
  altered every break's `key` **value**, not merely its identity.
  ⚠ **One-time upgrade effect, accepted:** Incidents opened before this carry no `breakId`, so each such
  Break can be promoted once more before the new grain governs. Chosen over carrying a legacy predicate,
  which would have re-introduced the key-only grain being replaced.
  ⛔ **The shared widening with `FEATURE-RECON-CARDINALITY` (§3) is NOT done.** That row wanted the Incident
  attribute shape widened once for both; this widened the *identity*, not the *evidence*. Carrying WHICH
  ROWS formed a break is still its own work — on top of the `breakId`/`breakKey`/`breakType`/`column` set
  now written, not instead of it.
  → `ControlApiReconPromoteTest` (11 tests, 4 new) · `reconciliation-types.spec.ts` (5 new)
- ~~**P2** · **`CATALOG-WIDGET-NODES-1` — Widgets and Dashboards are not Catalog nodes**~~
  ✅ **SHIPPED 2026-09-13.** `NodeKind` gained `DATASET` / `WIDGET` / `DASHBOARD`, `EdgeKind` gained
  `BINDS_TO`, and `MetadataGraphBuilder.addStudioLayer` wires `DASHBOARD → WIDGET → DATASET` with
  `CONSUMES` plus `DATASET →` its catalog origin with `BINDS_TO`. Covered by
  `MetadataGraphStudioLayerTest` (12 tests, mutation-checked against four mutants). The as-built facts
  — the new `ConfigSource.components(type)` channel, the unresolved-binding model, and the FOUR-way
  mirror of the binding rule — are in `okf/capabilities/metamodel/metamodel.md` §3.5.
  🔴 **Three of this row's premises were WRONG and are corrected there, not here.** (a) A Widget's
  `datasetId` does **not** name a catalog node — it names a Studio component, a separate persistence
  system, which is why a new `ConfigSource` channel was needed at all. (b) The bridge does not resolve
  cleanly: a Studio Dataset's `physicalRef` head names an **origin**, sometimes a Job output store the
  catalog has no node kind for, sometimes nothing (`kind: virtual`) — hence the `resolved` attr rather
  than a silent omission. (c) The urgency premise was overstated: `PipelineDependents` **already** walked
  `physicalRef → widget.datasetId → dashboard tile.widgetId` and was live on the delete-impact and
  dependents routes, so the place users actually ask "what breaks?" already covered BI consumers.
  ✅ **The styling residual is CLOSED** (`CATALOG-KIND-STYLING-1`, 2026-09-14): every wire kind now has a
  colour, a shape and a GLOSSARY label, and the survey found the gap was **wider than filed** — the wire
  kind `RAW_SCHEMA` was modelled nowhere in the SPA, while `KIND_GLYPH` turned out unreachable for catalog
  kinds. `isStore()` in
  `node-detail.dialog.ts` deliberately excludes `DATASET`: a Studio Dataset is the **binding**, not a
  store. → `okf/capabilities/metamodel/metamodel.md`

- ~~**P3**~~ · ✅ **CLOSED 2026-09-23 — `LA-CASE-CREATE-IN-PLACE-1`: the Save-analysis dialog now CREATES a Case in place, minted from graph nodes.** The ONE question the row left open was answered by the operator on 2026-09-23: **mint from the node** — an empty Case was offered and NOT chosen, so the 2026-07-22 rule (*a case CONTAINS its members*) stands. As built: the dialog's Case box offers *A new Case, from graph nodes* with a node picker pre-ticked from the canvas emphasis; each picked Entity becomes an **INCIDENT** (a Case's Contents are Incidents — an `ENTITY` object type would have had to teach merge/split/Case Rules/workflow/FindingsSpec too) keyed by `entityKey` (node id, so Entity type + D-S4 key) + `entityDataset`, and minting the same pair again **reuses** the object (mutation-checked). Server side is ONE new route, `POST /cases/from-entities` (`canManageIncidents`; OpenAPI, `CapabilityManifest`, `AbsentObjectRoutes` + the Personal test's copy, route-gating table all updated), because `POST /objects` cannot compose it — it needs an existing link target. Fails closed: refusals are checked before the first write, and a failed write rolls back every object the call created (`ObjectService.openCaseFromEntities`). The dialog seals → creates → attaches, keeping itself open with the seal remembered when a later step fails, so a retry never seals twice or opens a second Case. ⚠ **Deliberately deferred:** minted Incidents land in the Incidents inbox like any manual Incident (the price of reusing INCIDENT rather than a new type); and a Personal bundle can still only attach to the placeholder Cases. → `okf/frontend/features/link-analysis.md`.

### Deployment & packaging

- ~~**P2** · **D8-SUPPRESS-1**~~ ✅ **CLOSED 2026-09-17 — every clause verified IN THE CODE, not from this
  row's own ✅ marks.** `SuppressionList` (`inspecto-engine/…/notify/`) consulted from `NotificationService`;
  `GET`/`DELETE /notifications/suppressions` registered at `DeliveryStatusRoutes.java:66,68` and covered over
  real HTTP by `ControlApiSuppressionsTest`; `SoftBounceRetryTask` dispatched from `MaintenanceJob`;
  `DbDeliveryReceiptStore` present.
  ⚠ **The residual is NOT dropped — it is RE-POINTED.** "The SES/SNS adapter is OUT" meant out of THIS row's
  build, not out of the product: it is live open work on **`D8-SES-SNS-1`**, which still carries it plus GeoIP
  and per-user prefs. `grep` for `SesAdapter`/`SnsAdapter`/`amazonaws` over all Java hits only S3 connector
  tests — no adapter exists. §1's "commission the SES/SNS adapter review" pointer is re-pointed accordingly in
  this same edit, so it does not name a closed row. Original row follows.
  - **P2** · **D8-SUPPRESS-1** — per-recipient suppression list (TTL for hard bounces, permanent for complaints). ✅ **Its gate — a DB-backed `DeliveryReceiptStore` — was DISCHARGED 2026-09-07** (the same day it was verified still holding): `DbDeliveryReceiptStore` shipped in `inspecto-engine/.../notify/`, wired `SpaceRoot.deliveryReceiptsDbUrl` → `OperationalDb.Family.DELIVERY_RECEIPTS` → `ServiceStores.openDeliveryReceiptStore` → `CollectorService`, behind `-Ddelivery.receipts.backend`. ⛔ Default `none` — an absent receipt DB is the shipped behaviour, not degraded correctness, and a default-ON family creates a DB file in the CWD for every Personal install. Schema + rationale: `okf/backend/engine/db-layer.md` §3.12. ✅ **The suppression policy SHIPPED the same day** — `SuppressionList` (complaint ⇒ permanent · hard bounce ⇒ `-Dnotify.suppression.bounce.ttl`, default `P30D` · ⛔ soft bounce never · off via `-Dnotify.suppression=off`), consulted in `NotificationService`'s ChannelConfig delivery loop. 🔴 It **arms only over a durable store** (`DeliveryReceiptStore.durable()`) and WARNs when a TTL is set over one that cannot honour it — suppressing nothing while appearing configured is the `ConservationCheck` trap. ✅ **`GET/DELETE /notifications/suppressions` SHIPPED too** — the 2026-09-06 decision is fully discharged. `DELETE` records an **override** (operator call 2026-09-07) that forgives history up to its timestamp; a later bounce re-suppresses on its own, and the receipts survive as the audit trail. ⛔ Rejected: pruning the target's receipts — audit loss AND a permanent mask over a dead address. ✅ **SOFT-BOUNCE RETRY SHIPPED 2026-09-15** — the `soft_bounce_retry` maintenance task (`max_attempts` default 3, `backoff_minutes` default 60), re-delivering through `NotificationService.retrySoftBounce`. ⚠ **The first maintenance task that SENDS** rather than prunes or reads, so `JobService` gained a `notificationService()` seam beside its store seams. 🔴 **Two traps the decision did not name.** (a) `DeliveryReceipt.withStatus` keeps the FIRST observation of each status, so `statusAt[BOUNCED_SOFT]` never advances — a backoff measured from it would measure from a fixed point in the past and fire every remaining attempt in one sweep, a retry storm shaped like a backoff. The receipt therefore carries its own `lastAttemptAt` clock (+ `attemptCount`), two additive columns via the `ADD COLUMN IF NOT EXISTS` idiom. (b) Selecting on a bare `containsKey(BOUNCED_SOFT)` re-sends to recipients who ALREADY received the message, because a receipt that soft-bounced then delivered keeps both stamps forever — hence `softBouncedAndUnresolved()`, where DELIVERED/BOUNCED_HARD/COMPLAINED settle it and ⛔ UNKNOWN deliberately does not. ⚠ An attempt is counted whether or not it succeeded, or a permanently unreachable transport spins forever. **What remains on D8: the SES/SNS adapter** (the latter needs subscription confirmation + an outbound cert fetch from a callback path — its own review). Covers EDITIONS `CP-15` (Standard+). → `okf/backend/control-plane/events-metrics.md` §Decision

  ✅ **DECIDED 2026-09-15:** **of the two named residuals, soft-bounce retry is IN and the SES/SNS adapter is OUT**
  (kept filed, with its own review). See the Notifications row for the reasoning; recorded here too
  because this row states the residual pair and would otherwise read as if both were queued.

- **P2** · **AGT-5 per-tool dry-run seam — 🔴 RE-GATED 2026-09-16: BLOCKED-EXTERNAL again, on a DIFFERENT fact.** The 2026-09-08 discharge verified the TYPE ships; it never checked the SEAM. `javap` on the pinned `eoiagent-platform` jar: `PlatformBuilder` has `approvalHandler(...)`, `approvalDecisionStore(...)` and **no `dryRunProvider(...)`** — the only setter lives on `CallbackApprovalGate.Builder`, which `PlatformBuilder` constructs internally, so inspecto cannot supply a per-tool `DryRunProvider` without an upstream change. **Upstream ask (to `jotder/inspect-agent`): expose `PlatformBuilder.dryRunProvider(DryRunProvider)` and thread it to the gate builder.** Nothing built here; `AgentApprovals` stays as the previewer. ⛔ Do not re-discharge on the presence of the type — check the builder. *(Original text, kept for the trail:)* This sat in §2 as externally gated on eoiagent shipping a per-tool `DryRunProvider`. **It has shipped**: `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunProvider.java`, `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunResult.java`, and four per-tool dry-run tools, under an **Accepted** ADR-0008 enforcing approval + dry-run in the runtime (upstream `jotder/inspect-agent`, verified via the git-tree API 2026-09-08). ⚠ The gate was not "waiting" — it was **held shut by a broken check**: the `gh search code` probe it named returns 0 for every term in that repo, control included. **What this unblocks:** inspecto can now drop its parallel `AgentApprovals` previewer and consume the upstream per-tool seam on `PlatformBuilder`. ⛔ Still separately gated: `incident_explain` waits on the eoiagent **host** seam, and the local-models-only scope cut stands. → `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2
- **P2** · **Deployment topology gaps** — ~~GAP-3 service wrappers (SCR-3)~~ → own P1 row `DEPLOY-SERVICE-WRAPPER-1` (2026-09-11) · GAP-4 DuckDB `memory_limit` default · ~~GAP-5 T15 surge admission~~ 🔴 **REFUTED 2026-09-16 — ALREADY SHIPPED.** The plan's *“the admission cap + hysteresis controller is deliberately deferred”* is FALSE: `IntakeGovernor` implements the per-cycle cap (`capFor:186`, `-Dingest.maxFilesPerCycle`) AND the hysteresis controller (`observeCycle:212-229`, 2× band), plus per-pipeline `processing.intake` overrides, Space-keying and hot-apply via `PUT /system/scheduler`. Its javadoc `:39-49` records the DELIBERATE divergence from §3.5's sketch — inbox lag is *positive* feedback and would pin a healthy pipeline at the floor, so it closes the loop on cycle overrun instead. ⚠ The only residual is `baseCap=0` (off by default), which is **already tracked on the §3 Pipeline graph row** (*“flip the intake cap on by default — needs a soak”*) ⇒ ⛔ do not re-file it here. · ~~GAP-6 Vault/KMS (SEC-8)~~ → **DEMAND-GATED, not buildable** (2026-09-16). `okf/capabilities/security/security.md` §5 is the owner and says Enterprise-only, **only when a client policy requires it**, *“nothing else is designed”*; building needs a live Vault/KMS host this repo does not have. ⚠ The seam ALREADY EXISTS (`SecretsProvider` SPI + `SecretResolver.java:59-67` ServiceLoader discovery), so there is **no preparatory work** either — a provider drops in as another ServiceLoader module with no core change. ⛔ Reopen only on a NAMED client policy; do not carry as open build work. · ~~GAP-10 bundle missing 13 archived docs (SCR-10)~~ → 🔴 **REFUTED 2026-09-16 — the ROW TITLE MISREAD ITS OWN SPEC.** `plans-archive/deployment-topology-plan.md:420` describes a machine-local NTFS **deny-ACL** on 13 files, whose remedy was Administrator `takeown`+`icacls` — **never a repo change and never “add docs to the bundle”**. `package.ps1:1585-1602` already stages the WHOLE `docs/` tree recursively with nothing excluded, and a byte-level read of all **498** docs files returns exit 0, zero unreadable (`plans-archive/` = 162 entries, all readable). Condition observed once on one host at `d998ae8b` (2026-07-24), not reproducible. ⚠ A real bundle diff is **verification owed**, but confirmatory only — the failure mode it named (unreadable sources) demonstrably no longer exists.

- ~~**P2**~~ · ✅ **`BUNDLE-SHIPS-THE-ARCHIVE-1` — CLOSED 2026-09-17: BOTH residuals resolved.**
  **Residual 1 (AFTER bundle build) RUN 2026-09-17** in a clean worktree via `pwsh ./package.ps1 -NoUi
  -SkipBootCheck -AllowPartialRuntime -Edition Personal`: log line `docs: staged 216 files; withheld 280
  (tiers: archived-documents, superpower; audience: BACKLOG.md, PROJECT_NOTES.md)` plus `docs: neutralised
  224 link(s) into withheld trees`. Verified directly against the produced
  `inspecto-deploy-windows_amd64.zip`: **zero** `archived-documents` entries, **zero** `superpower/`
  entries, neither `BACKLOG.md` nor `PROJECT_NOTES.md` present. **Residual 2 (`BUNDLE-DANGLING-LINKS-1`)**
  was already closed as its own row 2026-09-17 (re-measured 194 links, confirmed the neutralisation
  mechanism above already handles them). GAP-4 (DuckDB `memory_limit` default), which this row only ever
  inherited historically, stays tracked on the "Deployment topology gaps" / D11 rows, not here.
  *(Superseded status text below, kept for provenance:)* ✅ **`BUNDLE-SHIPS-THE-ARCHIVE-1` — BUILD HALF SHIPPED 2026-09-16 (`f8ede68a`); the row stays open on two residuals only.** The operator answered on 2026-09-16: **ship none of the non-current tiers**, and **withhold `BACKLOG.md` + `PROJECT_NOTES.md` as well** on audience grounds — they are current-tier and accurate, but the defect board names open P1s in the product the customer just installed. Two SEPARATE named lists (tier vs audience) so the reasons are never conflated, matched on the FIRST path segment only, with the reason inline per entry — ⛔ not a glob, because a glob records no reason. Fails closed BOTH ways: `DOCS TIER LEAK`, `DOCS AUDIENCE LEAK`, `DOCS OVER-FILTERED`, each mutation-proven to fire. Result: **staged 217, withheld 280**; zero archive, zero `superpower`, neither internal file, every required current-tier tree present. 🔴 **The exposure was WIDER than this row measured: 272 of 491 shipped docs files (55%) were non-current** — the archive *plus* 26 in-flight `superpower/` plans, which the row had only flagged as worth checking. ⛔ **Residual 1 — the AFTER bundle build is UNRUN**: PowerShell is blocked in the agent sandbox, so step 7 was extracted and driven verbatim against the live tree and the script parse-checked whole, but no `package.ps1` end-to-end run has happened. The next shift with `pwsh` must run it once and confirm the `docs: staged N … withheld N` line and a zip with zero `archived-documents` entries. ⛔ **Residual 2 — `BUNDLE-DANGLING-LINKS-1`** (new row): 198 inbound links from current-tier docs into the withheld trees. ⚠ Also settled in passing: root `compliance/` is **not** in the bundle at all (it is not under `docs/`). *(Original row text follows.)* Filed 2026-09-16, found while REFUTING GAP-10 — which had worried that 13 of these files were *missing*. ⚠ **The real exposure is the exact inverse, and it is 248 files wide:** `package.ps1:1585-1602` stages all of `docs/` recursively, and **248 of the 498 files — half the tree — are `docs/archived-documents/`**, the tier CLAUDE.md defines as *“kept for provenance, never maintained, never linked as current”*, carrying ~570 known-broken internal links, superseded designs and refuted claims. ⛔ **Needs an operator call BEFORE any code** — three options: ship none of the archive · ship it under a clearly-marked subtree with a *not maintained* banner · keep shipping as-is. The filter in `package.ps1` step 7 is cheap once decided; **the decision is the work.** ⇒ §1. Phases 0–5 all unbuilt. ✅ **This row is ALSO the board home of the thirteen plan items `SPEC-DEPLOY-ROWS-1` counted (closed 2026-09-15 by naming them here rather than filing thirteen rows that would duplicate the spec):** the preflight tool · the acceptance script · the off-site backup copy · upgrade/rollback automation · the sizing table · the disaster-recovery pack · phases 0–5 (six) · the platform list · the government-variant refusal · **`SCR-4`** (nginx/IIS proxy + TLS reference configs — TLS, HSTS, static-UI gzip, `/metrics` + `/health/details` restricted to the monitoring network; ⚠ still the one item whose only statement anywhere else is its acceptance line). Their durable specification is `okf/capabilities/editions/editions.md` §3.9–§3.14; this row tracks the BUILD. *(Re-grounded 2026-09-08. The "(after §1 D1–D8 are signed)" gate is dropped — §1 records all 28 decided 2026-09-06, which §7 already flagged. **GAP-2 and GAP-8 were shipped work this row had inherited as open** and are struck: Enterprise is a real `package.ps1` flavour (EDG-01) and the Postgres driver rides the bundle as `postgresql.jar` (PG-1). ⚠ **GAP-4 verified STILL OPEN** — D11 shipped as a pair and only the concurrency half is on by default; `DuckDbUtil.memoryLimit(null)` is `null`, no `scheduler.toon` ships, and the committed corpus sets `memory_limit: ""`. Do not close it off the D11 row.)* → `okf/backend/build-run/build-test.md` §11

  ⚠ **GAP-4 RE-GROUNDED 2026-09-16 — still OPEN, but it is an OWED §1 CALL, not buildable work, and the
  signed D3 VALUE is REFUTED.** The gap is real: `DuckDbUtil.memoryLimit(configured)` (`:215-221`) falls
  through pipeline → installed → `-Dprocessing.duckdb.memory_limit` → **`null`**, `applyDuckDbSettings`
  (`:108-118`) only issues `SET memory_limit` when non-blank, no `scheduler.toon` is staged by
  `package.ps1`, and the only `memory_limit` in the committed corpus is the EMPTY STRING
  (`spaces/ucc/config/voucher/voucher_pipeline.toon:34`). ⚠ D11 shipped as a PAIR and only the
  concurrency half has a default (`JobService:193`, `DEFAULT_MAX_CONCURRENT_RUNS = 4`).
  🔴 **2 GB is NOT DERIVABLE.** The repo's only sizing rule (`editions.md:560,564`) is
  `memory_limit ≈ 25–50 % RAM ÷ concurrency`; at the shipped concurrency of 4 it yields **500 MB–1 GB on
  T1**, 1–4 GB on T2 and **2–8 GB on T3** ⇒ a fixed 2 GB is **2–4× too HIGH on T1 and up to 4× too LOW
  on T3**, and **2 GB × 4 = 8 GB = the ENTIRE T1 host** — precisely what the rule's `÷ concurrency` term
  exists to prevent. ⛔ The repo grounds a FORMULA, not a CONSTANT; do not reinstate the number.
  🔴 **And GAP-4 CANNOT BE CLOSED ALONE**: capping memory converts an over-large run from OOM into
  SPILL, and the pipeline-job/enrichment path has **no default spill directory and no size cap** —
  `DuckDbUtil:236-240` passes the raw `-D`s, both null — unlike batch ingest, which defaults
  `tempDirectory` to `dirs.temp` (`PipelineConfig:373-376`). ⇒ `max_temp_directory_size` and a spill
  directory need defaults **in the same change**, or the cap trades OOM for disk-fill.
  ⇒ **Three options for the operator, none small:** **(a)** derive at boot from the stated rule
  (`hostRAM × 25–50 % ÷ effectiveMaxConcurrentRuns`, via `OperatingSystemMXBean.getTotalMemorySize()`) —
  self-scales T1–T4 but changes behaviour on every existing install · **(b)** a per-edition constant in the
  Maven-profile/ServiceLoader machinery — matches editions-are-build-flavors, still ignores the real host ·
  **(c)** leave it opt-in and close GAP-4 WONTFIX, making the mandatory `-D` flags a preflight row.
  ✅ **One piece was fixed standalone 2026-09-16: the EIGHTH assertion of the phantom default, and the
  FIRST in Java.** `SchedulerRoutes.java:135` commented *“else the built-in default that now ships on”*
  eleven lines above `:138-141`, which puts `null`. Corrected in place, with a note that
  `duckdbMemoryLimitSource: "default"` means *nothing was configured*, NOT *a default applied*.
  → §1 D3 (RE-OPENED).
- **P2** · **Postgres multi-user** — ⛔ **PARKED by §6** until a multi-operator install exists; the old "(after the §1 decision)" heading outlived its decision, which was *park it*. Kept for the shape when it lifts: ~~P1 pool behind `JdbcDrivers` (each `Db*Store` holds ONE `synchronized` connection); P2 replace `browseConnection()` (F2: it hands out the store's long-lived connection, a pool has no such thing)~~ 🔴 **P1 AND P2 BOTH ALREADY SHIPPED 2026-09-14 (`3844fc0f`, `OPS-03`) — verified 2026-09-16 by grepping the symbols, and this row was re-ranked P2 on 2026-09-15, the day AFTER, still listing them as the shape to take.** P1: `JdbcDrivers` returns a **HikariCP `PooledConnectionSource`** for `jdbc:postgresql:` (`inspecto-util/.../JdbcDrivers.java:81-93`) under `-Ddb.pool.size` / `-Ddb.pool.timeoutMs`; DuckDB stays on `SingleConnectionSource` deliberately (one connection behind one monitor — ⚠ `-Ddb.pool.size` does NOT apply there, `:80`). Stores borrow per operation via `AbstractJdbcStore.withConn`/`runConn`, so *"each `Db*Store` holds ONE `synchronized` connection"* is no longer true of any store. P2: **`browseConnection()` was REMOVED, not replaced** — it and `browseMonitor()` are gone (`okf/backend/engine/db-layer.md:1001`), exclusion moved onto the source, and browse reads now go through `util/BrowsableStore.java` over each store's `ConnectionSource`. ⛔ Grep confirms `browseConnection` survives **only in prose** — zero Java hits repo-wide, nine doc files including this one. P3 **schema**-per-space URL wiring (NOT db-per-space) — ✅ still open, no `search_path`/`currentSchema` wiring anywhere; P4 `CaseStore` interface + PG impl — ✅ still open, `CaseStore.java:18` is `public final class CaseStore extends DurableJsonlRing<Case>`, a concrete JSONL ring with no interface; `PostgresStateStoreTest` over the three uncovered stores + a concurrency test — ✅ still open. Keep events on Parquet. ⚠ Not the same work as `OperationalDb`/PG-1 (shipped). → `archived-documents/plans-archive/postgres-multi-user-plan.md` §5–6

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** A multi-operator install exists. 🔴 **The §6 PARK IS LIFTED** — that line is
  struck in §6 and the §7 duplicate-map row updated, so all three places agree.
  ⚠ **Take the shape as written and do not improvise it** — ⚠ **but only P3/P4 are left of it: P1 and P2
  shipped 2026-09-14, the day BEFORE this trigger block was written, and it did not notice** (see the
  strike above; `PooledConnectionSource` + `-Ddb.pool.size`, and `browseConnection()` removed outright
  under `OPS-03`). ⛔ Anyone taking "the shape as written" would rebuild a live pool.
  Remaining: P3 **schema**-per-space URL wiring, ⛔ NOT db-per-space; P4 a `CaseStore`
  interface + PG impl (a JSONL ring today); plus `PostgresStateStoreTest` over the three uncovered
  stores and a concurrency test. ⚠ Keep events on Parquet. ⚠ Not the same work as `OperationalDb`/PG-1,
  which shipped — a sweep that conflates them will report this half-done.
  ⚠ **And its acceptance needs the Postgres that this checkout lacks** — `PostgresStateStoreTest`
  skips entirely here, so the coverage exists and has never executed.


- **P2** · **`DEPLOY-SERVICE-WRAPPER-1` residual — the live acceptance is UNRUN.** ✅ **The wrappers
  SHIPPED 2026-09-11** (`SCR-3`): `package.ps1` stages `inspecto.service` + `install-service.sh` (systemd)
  and `install-service.ps1` (Windows Scheduled Task at boot as SYSTEM, with restart-on-failure). 🔴 The
  row's **`sc.exe` recommendation was REFUSED on grounding** — a Windows service binary must reach the
  service control dispatcher shortly after start and `java.exe` never does, so an `sc.exe` service fails
  every start with error 1053; that installer would have looked installed and restarted nothing. WinSW is
  documented as the alternative. **What remains is evidence, not code:** the unit renders with zero
  placeholders and both installers parse, but the row's actual test — **`kill -9` → back on `/health`**,
  plus the reboot leg — is **unrun**, because it needs a systemd host and an elevated Windows box and this
  checkout is neither. ⛔ Do not mark `SCR-3`'s acceptance met until someone runs both; the installers
  print the exact commands. → `okf/capabilities/editions/editions.md` §3.14 · `inspecto/package.ps1`

  ✅ **UNBLOCKED 2026-09-15 — the hosts EXIST.** The operator confirmed both a systemd Linux host and
  an elevated Windows box are available, which is the only thing this row has ever needed.
  ⛔ **This row needs a RUN, not a build** — the installers already print the exact commands; the
  acceptance is `kill -9` → back on `/health`, **plus the reboot leg**, on both platforms.
  ⚠ Access details are an owed input in §1. ⛔ `SCR-3`'s acceptance stays unmet until both are run —
  a shift that reads this row looking for code to write will find none and may mark it done.
## 4. Engineering / tech-debt

- ~~**P1** · **`CI-JACOCO-JDK27-1`**~~ ✅ **SHIPPED 2026-09-18 — option (b), widened past the row's own
  scope after grounding.** The row named `asn-core` only because it's the first module Maven reaches;
  building past it (once skipped) proved the SAME failure recurs at every subsequent module in BOTH
  reactors — confirmed live: `asn-schema` failed identically right after `asn-core`, and once
  `asn-decoders` was fully skipped, `inspecto-util` failed identically in the MAIN reactor, because the
  root `pom.xml`'s own `maven.compiler.release` was independently bumped to 27 (a same-day, unrelated
  commit) — every module in both reactors compiles to class file v71, not just the nine `asn-decoders`
  ones. **Fix, in two places, one per reactor**: `<skip>true</skip>` added to the `jacoco-maven-plugin`
  `<configuration>` in both `pom.xml`'s and `asn-parser/asn-decoders/pom.xml`'s `coverage` profiles —
  ⛔ NOT per-submodule (an `asn-core`-only skip was tried first, confirmed insufficient by driving the
  build past it into `asn-schema`, then reverted in favour of the parent-level fix so all 8+21 modules
  inherit it in two places instead of needing it repeated per module). Option (a) (bump jacoco) was
  rejected as unverifiable offline this soon after a JDK 27 GA (2026-09-15); option (c) (drop the JDK
  version) was rejected as it would just break compilation instead, since the root `release=27` bump and
  the `eoiagent` JDK 25+ bytecode requirement are both already load-bearing. Verified: the full reactor
  (`mvn -o -Pcoverage,edition-enterprise clean test`) now runs every module's real tests with no jacoco
  error anywhere — it proceeds past the point this row's own diagnosis stops at, all the way through
  `inspecto-engine`, before hitting the unrelated `WorkflowConfigLoadTest-1` crash below (a DIFFERENT
  defect this row's fix simply unblocked visibility into — do not conflate the two).
  → `pom.xml` (`coverage` profile) · `asn-parser/asn-decoders/pom.xml` (`coverage` profile)

- ~~**P1** · **`WorkflowConfigLoadTest-1`**~~ ✅ **CLOSED 2026-09-19 `f1232b03` — and EVERY diagnostic claim in
  the row below was wrong.** Nothing crashed: `TestConfigs.write()` names its fixture `pipeline_<hash>.toon`,
  a PREFIX, while `ServiceBootstrap` discovers by the SUFFIX `*_pipeline.toon`, so the scan found nothing,
  the registry came up empty, and `CollectorService.fromArgs` — the CLI entry point, `exitIfEmpty=true` —
  answered that with `System.exit(1)` (`ServiceBootstrap.java:67`). The crash log's last line is that
  method's own message. Surefire's "VM crash **or System.exit called?**" was asking the right question.
  🔴 **Three refutations worth keeping:** (1) NOT JDK 27 — it reproduces identically on JDK 26 and 27;
  (2) NOT fork startup — the fork starts, prints `Running …` and two log lines, then dies mid-run;
  (3) the row's own repro command **cannot produce the symptom it describes** — with `-am`, `-Dtest=`
  applies reactor-wide, so `asn-core` (module 2/17) halts the build on "No tests matching pattern" and
  `inspecto-ops` is SKIPPED, never forking. Add `-Dsurefire.failIfNoSpecifiedTests=false` to reach it.
  ⛔ **The proposed fix — bump surefire 3.2.5 → 3.5.3 — would have changed nothing**, and is aimed at a
  startup mechanism that was never involved. Verified 2/2 green. Fixed test-local: 88 test files use
  `TestConfigs` and all pass because they load by explicit path; only this scan-booting test was exposed.
  *(Original, kept for provenance:)* — `inspecto-ops`'s forked test JVM crashes outright on JDK 27,
  found only because `CI-JACOCO-JDK27-1`'s fix let the build reach this far.** Reproducible with or
  without `-Pcoverage` (rules out jacoco as the cause): `mvn -o -pl inspecto-ops -am -Pedition-enterprise
  -Dtest=WorkflowConfigLoadTest test` fails at fork STARTUP, before any test runs (`Tests run: 0`) —
  *"The forked VM terminated without properly saying goodbye. VM crash or System.exit called?"*,
  `Process Exit Code: 1`. No dump/`hs_err` file was produced. 🔴 **Ungrounded past this point — filed
  on the operator's instruction to stop and record rather than dig further this session.** Strongest lead
  found so far, not yet confirmed as the cause: the root `pom.xml` pins `maven-surefire-plugin` at
  **3.2.5** (line ~468) — noticeably older than the `3.5.3` pinned for `asn-decoders`'s own submodules,
  which build and run cleanly on the same JDK 27 in the same session. Surefire's fork-booter launch
  mechanism has a known history of JDK-version sensitivity; a version mismatch this large against a JDK
  that GA'd 2026-09-15 is worth checking before looking elsewhere. The same `<configuration>` block also
  carries `--enable-native-access=ALL-UNNAMED`, commented as *"silences DuckDB JNI warnings on Java 24"*
  — worth checking whether that flag's behavior changed by JDK 27, though the fork fails before any
  native call would execute, which weakly points away from it. Fix: bump `maven-surefire-plugin` to
  match the `3.5.3` already proven to work elsewhere, or root-cause the fork failure directly with `-X`.
  → `pom.xml` (surefire plugin pin, ~line 468) · `inspecto-ops/src/test/java/com/gamma/opsboot/WorkflowConfigLoadTest.java`

- ~~**P2** · **`LIB-SYSTEM-EXIT-FROM-PUBLIC-API-1`**~~ ✅ **CLOSED 2026-09-20 — `fromArgs` now throws,
  the exit moved to the CLI mains.** `ServiceBootstrap.buildFrom(…, exitIfEmpty=true)` no longer calls
  `System.exit(1)` on an empty registry; it throws a new `com.gamma.service.EmptyConfigException`
  (unchecked, so `fromArgs`'/`build`'s existing `throws IOException` signature is untouched). Both CLI
  entry points — `CollectorService.main` and `ControlApi.main` — now wrap their `fromArgs`/`buildFrom`
  call in a `catch (EmptyConfigException e)` that prints the same message and calls `System.exit(1)`
  itself, so the CLI's observable behaviour (still exits 1 on empty config) is unchanged; only *where*
  the exit happens moved. `SpaceBootstrap` already passed `exitIfEmpty=false` and is unaffected.
  Regression: `FromArgsEmptyConfigTest` (new) proves `CollectorService.fromArgs` and
  `ServiceBootstrap.buildFrom(..., true)` throw a catchable exception on an empty dir instead of
  killing the JVM — exactly the scenario that produced `WorkflowConfigLoadTest-1`'s false JDK-27
  diagnosis. The CLI mains' `System.exit(1)` retention is verified by inspection, not a runnable test
  (driving a real `System.exit` from a unit test would kill the test JVM). Verified:
  `mvn -o -pl inspecto -am -Dtest=FromArgsEmptyConfigTest,ServiceBootstrapLedgerTest test` — 5/5 green.
  ⚠ **Scope narrowed from the filed row**: only `fromArgs`/`buildFrom` were changed, per this pass's
  task. The row's own suggestion to also check `SpaceMigrator`, `EnrichmentProcessor`, and
  `CollectorProcessor`'s `System.exit` sites was NOT done here — those are separate call chains, not
  reachable from `fromArgs`, and out of scope for this fix; leaving as a residual if anyone wants to
  file it separately.
  → `okf/backend/build-run/build-test.md` ·
  `inspecto/src/main/java/com/gamma/service/EmptyConfigException.java` (new) ·
  `inspecto/src/main/java/com/gamma/service/ServiceBootstrap.java` ·
  `inspecto/src/main/java/com/gamma/service/CollectorService.java` ·
  `inspecto/src/main/java/com/gamma/control/ControlApi.java` ·
  `inspecto/src/test/java/com/gamma/service/FromArgsEmptyConfigTest.java` (new)
  · `CollectorService.java:1869`

- **P3** · ➕ **`TESTCONFIGS-PREFIX-SUFFIX-TRAP-1` — the shared fixture writes a name the production scanner
  cannot discover.** **FILED 2026-09-19.** `TestConfigs.write()` emits `pipeline_<hash>.toon`; every loader
  that scans a directory matches the SUFFIX `*_pipeline.toon`. 88 test files use the fixture and none
  noticed, because they load by explicit path — the trap only springs for a test that boots by SCAN, and
  then it presents as a JVM crash (see `LIB-SYSTEM-EXIT-FROM-PUBLIC-API-1`). Left as-is deliberately:
  renaming touches 88 files to fix a trap that has sprung once. ⚠ Re-rank to P2 the moment a second
  scan-booting test is written.
  → `okf/backend/build-run/build-test.md` · `inspecto-etl/src/test/java/com/gamma/etl/TestConfigs.java:113`

- ~~**P2** · 🔴 ➕ **`OPENAPI-CONTRACT-RED-ON-MASTER-1`**~~ ✅ **CLOSED 2026-09-19 — and it was FAR worse than
  this row said.** ⛔ **Filed as "misattributes the next failure"; MEASURED, one undocumented route was
  suppressing ~850 tests across TWELVE modules — every Professional and Enterprise module in the build.**
  Two full-reactor runs at `653a4121` put a number on it: the normal gate
  (`mvn -o clean test -Pedition-enterprise --fail-at-end`) built **20** modules / **4012** tests with 1
  failure; the same gate with only `OpenApiPathsContractTest` excluded built **32** modules / **4864**
  tests, **0 failures, 0 errors**. ⚠ **`--fail-at-end` does NOT rescue a failed module's DEPENDENTS** —
  Maven bans them outright, so the 12 were not failing, they were never exercised: security (43),
  Operational objects (206), Embedded Intelligence (203), Assist Agent (158 + 8 hosted), connectors (129),
  exchange (23), policy (20), geo/link (19), backup/restore (17), notifications (15), event viewer (4),
  metrics (2) — all green once actually run.
  Fixed by the sanctioned path: `-Dopenapi.paths.write=true` added a `get` skeleton for `/assist/skills`
  (`AssistRoutes.java:35`) in the same `x-generated` shape as its siblings — **+14 lines, one path key,
  nothing dropped or modified** (the write mode's drop-stale behaviour did not fire). Verified WITHOUT
  write mode: 1/1 green.
  🔴 **The lesson is the shape, not the route:** a single doc-contract failure in an upstream module is a
  VERIFICATION OUTAGE for everything downstream, and it presents as one red test. Same shape as the stale
  doc guard at `ci.yml:64` that suppressed the entire reactor for a day behind six "known red" runs.
  → `okf/backend/build-run/build-test.md`
  *(Original:)* — `-am` builds halt at `inspecto-processor`.**
  **FILED 2026-09-19**, observed while verifying an unrelated fix.
  `OpenApiPathsContractTest.everyLiveRouteHasAnOperationInTheContract:116` fails: *1 live route has NO
  operation in `docs/api/openapi-v1.json` — `[GET /assist/skills]`*. ⚠ **This halts the reactor before any
  downstream module**, so any `-pl <module> -am` verification currently reports a failure that is not the
  change under test — exactly the false-red that has cost this board several wrong verdicts.
  Fix: regenerate with `-Dopenapi.paths.write=true` and fill the schema by hand, or document the route.
  ⚠ Not grounded beyond the failure text — confirm whether `GET /assist/skills` is newly added or newly
  matched before assuming which side is wrong.
  → `okf/backend/build-run/build-test.md`

- **P3** · ➕ **`BOARD-STALE-HEADS-1` — §0's narrative is staler than the rows, and closed rows keep their
  unstruck heads.** **FILED 2026-09-19** after a shift in which **six of nine grounded rows were already
  shipped, blocked, or duplicates.** Three distinct shapes, each cost real time today:
  (a) §0 annotates rows as "unblocked" that the body closes — `TYPEFLOW-DATASET-COLUMNS-1` is called the
  only startable M-row at `:330`/`:411` and CLOSED at `:1165`; (b) a superseded analysis keeps an unstruck
  head twelve lines under the row that closed it (`FENCE-STORE-SILENTLY-INERT-1`, struck `e666bafe`) — it
  caused an operator to be asked a second time for a call already made and built; (c) rows generated from
  one doc's prose without checking a sibling doc in the same repo — `STREAM-CONSUMER-1` was filed off
  `ROADMAP.md:720` while `okf/capabilities/acquisition/acquisition.md:60` records the same capability
  `✅ SHIPPED 2026-07-08` (`KafkaConnector` IS the draining consumer loop).
  ⚠ **This is a P3 only because it is tooling, not because it is minor** — the cost is measured in whole
  shifts. Candidate guard: fail when a row id appears both struck and unstruck, or when §0 asserts a row is
  open whose body carries a CLOSED marker. Today that guard would have saved two of four opening lanes.
  → also owed: strike `STREAM-CONSUMER-1`, correct `ROADMAP.md:720`, and move
  `SPACES-FROM-PARTITION-MAP-1` to §2 (blocked on D16/ingress — `grep -rn ingress inspecto/src/main` is
  EMPTY, so it is not startable by anyone).

- **P2** · **`SPACES-FROM-PARTITION-MAP-1` — answer `/spaces` from the partition map, not a disk scan.**
  ⚠ **RE-GROUNDED 2026-09-16 — still open, still NOT startable, and NOT already shipped.**
  `SpaceRoutes.java:50-56` still answers from `api.spaces().all()` (this Pod's roster) and still calls
  `ApiContext.podScoped(e)` at `:51`; `podScoped` is declared in exactly three places
  (`BootstrapRoutes:44`, `SchedulerRoutes:70`, `SpaceRoutes:51`), so the row's “remove it from `/spaces`
  and `/bootstrap`, leave it on the scheduler” clause is still accurate and still owed.
  ⛔ **The blocker was CHECKED, not assumed:** `grep -rn "ingress" inspecto/src/main --include=*.java`
  is **EMPTY** — the ingress path-routing this depends on does not exist in any form. Building the
  `/spaces` half alone would ship a UI offering Spaces it cannot open. Leave trigger-gated.
  Filed 2026-09-13, replacing `UI-POD-SCOPE-UNION-1`. ✅ **This is the remedy the architecture already
  sanctions**, and it is SERVER-side, so it fixes the partial roster for *every* client with no UI union:
  `enterprise-scale-out-plan.md` §5.5 — *"Answer `/spaces` from the partition map, not a disk scan. The
  map declares every Space and its owner, so this needs no fan-out."* `partition.toon`
  (`SpacePartition.java:66,86`) already declares every Space and its owner; `SpaceManager.all()`
  (`:93,104,120`) holds only what THIS Pod booted, which is the whole defect.
  ⚠ **Not buildable in isolation — it needs ingress path-routing first** (same §5.5): once `/spaces`
  lists Spaces this Pod does not own, their detail routes must reach the owner or the UI offers Spaces it
  cannot open. ⛔ And the rules must be GENERATED from the map — hand-maintained ingress rules make the
  ingress a second copy of the map, and the two drift.
  ⚠ **Interaction with what shipped:** once `/spaces` and `/bootstrap` answer fleet-wide, their
  `podScoped: true` declaration becomes WRONG and must be removed — leaving it only on
  `GET /system/scheduler`, which stays genuinely per-Pod. → `superpower/enterprise-scale-out-plan.md` §5.3, §5.5

*(Drained 2026-09-07. Three of the five rows here were standing refusals wearing a tech-debt label — a
"LEAVE unless someone is already in the file" is not work — and moved to §6. A fourth was already closed by
a test that post-dates it. What was left was one release-gated wire change; `SBOM-RESOLVE-1` joined it
2026-09-09.)*

- **P3** · **Vocabulary rollout, Tier 3 — the release-gated remainder.** The **UI half SHIPPED 2026-09-07**:
  every emitter was verified to dual-emit (`LineageRoutes:112/113`, `ViewRoutes:100/101`,
  `PipelineProjection:198/207/242`), so the DTO fields now read the canonical key — `DownstreamPipeline.pipeline`,
  `PipelineViewSummary.pipeline`, `CombinedNode.pipeline` / `CombinedEdge.pipeline` / `PipelineCombined.pipelines`.
  What remains is **wire and needs the MAJOR** (§2 release notes): dropping `flow` from the Java JSON
  (`ViewDefinition.toMap:52`, `LineageRoutes:113`, `PipelineProjection:199/208/243`) and renaming the
  `ViewDefinition.flow` record component, which carries `@PublicApi(since="4.0.0")` — GLOSSARY §13 already
  puts an `@PublicApi` member rename at Tier 2 as "a tier boundary on its own". ⛔ Do **not** rename the
  `"flow"` test literals: `ViewStoreTest:66/71` and `ControlApiViewsTest:98` are the only proof the dual-read
  compatibility path works, and `PipelineJobRunnerTest`/`JobServiceTest` cover the legacy job param. The
  agent-tool `flow` argument stays until it dual-accepts (`SOURCE_ALLOW` records it as tracked debt).
  ⚠ When the dual-emit ends, delete `CONFIG_ALLOW['spaces/ucc/config/views/sites_active_view.toon::flow-key']`
  — the guard's stale-allowlist rule fails the build until you do, which is the rename announcing itself.
  → `GLOSSARY.md` §13 · `PROJECT_NOTES.md`


### Module audit 2026-09-17, layer 2 — API contract, Java module structure, database, files

**Where we stand.** The API layer is centrally enveloped: every handler's return value passes through one
dispatch chain, no route can answer 200 with an `error` key, `Idempotency-Key` covers retryable
POST/PUT/DELETE, CORS is off unless `-Dcontrol.cors` is set, and `/openapi.json` is byte-equal to the
committed spec by build guard. Its weak spots are UNDER-specification, not structure: error codes are
defaulted from the HTTP status at ~96% of throw sites (633 bare vs 15 coded `ApiException`s), so a bare 403
is always labelled `PATH_JAIL_VIOLATION` whatever caused it; `If-Match` protects config and components only;
nothing throttles the expensive routes. The DB layer is mature for its age — one 14-family roster, HikariCP
for Postgres and a single locked connection for DuckDB, insert-wins-on-PK instead of read-modify-write,
a fenced `RunLease`, seven prune tasks that are opt-in by stated policy — but it has no schema-version stamp
(migration is a per-store `ALTER … ADD COLUMN IF NOT EXISTS` discipline verified in 2 of ~10 stores), no
`space_id` on any operational table (isolation is per-space DuckDB FILE, which a shared Postgres URL
defeats), and no backup path for Postgres at all. The file layer's crash-safety core, `AtomicFiles.write`
(temp + `ATOMIC_MOVE`), is used by ~25 writers; `PathJail` is the single containment authority. Two live
findings were FIXED in this pass rather than filed: `PipelineWatermarkStore.put` was the one store writing
in place (torn watermark ⇒ silent full re-read), and `GET /signals` was the one list route with no ceiling
on `limit`. Both are pinned by tests. ⚠ One agent finding was REFUTED before filing: "the acquisition ledger has no prune caller" — `LedgerPruneTask.java:17-24` calls `AcquisitionLedgers.shared().prune(cutoff, source)`. Grep the caller, not the callee.

- ~~**P2** · **`DB-BACKUP-POSTGRES-1`**~~ ✅ **CLOSED 2026-09-17 — documentation-only fix, `docs/EDITIONS.md`
  OPS-06.** Grounded: `BackupTask`'s one DB reference (`catalogRow`, line 358 confirmed unchanged) opens a
  throwaway in-memory DuckDB scratch just to write its own catalog Parquet row — it never touches the
  operational stores, so the premise held. Took the documentation leg, not the `pg_dump`/COPY leg: a real
  fix would need a JDBC `COPY`/dump per `OperationalDb.Family` (`inspecto/src/main/java/com/gamma/service/
  OperationalDb.java`), each with its own URL and credential grain by design (see that class's roster
  Javadoc) — infrastructure-plumbing scope disproportionate to one maintenance task, and this codebase has
  no `pg_dump`-shelling precedent to reuse (the runtime is deliberately JDBC-driver-free, OPS-02). `docs/
  EDITIONS.md`'s OPS-06 row now states plainly that `backup` covers files only and a Postgres operational
  database needs an external DBA backup path. Original row: `BackupTask` knows DuckDB and files only — its
  one DB reference opens an in-memory DuckDB scratch (`inspecto-backup/.../BackupTask.java:358`), so a
  Professional/Enterprise deployment on Postgres has NO backup path for job runs, the dedup and acquisition
  ledgers, status, or the run lease. Fix offered: a `pg_dump`/COPY leg when `OperationalDb.postgres()` is
  true, or document the gap in `EDITIONS.md`.
- ~~**P2** · **`STORE-CONFLICT-DETECTION-1`**~~ ✅ **SHIPPED 2026-09-17 — the row's cited store lines were
  stale; the real gap was one route hop over.** *(Original: `ComponentStore.java:164-175`,
  `PipelineStore.java:80-84` and `ViewStore.java:88-91` overwrite blindly with no `If-Match`/version.)*
  Grounded: `ComponentStore.write` (still no version check inside the store itself) IS already protected —
  `ComponentRoutes.updateComponent` (`inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:265`)
  calls `ETags.requireMatch` before calling `store.write`, exactly like `ConfigWriteRoutes`; the row's own
  cited fix (extend `ETags`/`CONFLICT_STALE_VERSION`) was already true for components at the route layer.
  `PipelineStore.write` is **not** the live pipeline-editor write path any more (W5): the graph editor's
  `PUT /pipelines/{name}/graph` (`PipelineGraphRoutes.saveGraph`) writes the canonical `*_pipeline.toon`
  directly via `AtomicFiles`, bypassing `PipelineStore` entirely — `PipelineStore.write` only backs the
  grandfathered `*_flow.toon` bundle-import path (`BundleRoutes.PipelineBundleSource`), whose per-item
  `actions` map (skip/overwrite/unchanged-by-hash) is a deliberate, different conflict contract for a batch
  import, not a live two-editor race. `ViewStore.write` is written only by `MaterializeTask` after a
  pipeline run — one writer, no HTTP `PUT` route exists for a view at all, so there is no concurrent-editor
  scenario to protect. **Fix applied where the real race lives**: `PUT /pipelines/{name}/graph` now honours
  an optional `If-Match` against the existing `*_pipeline.toon`'s content hash (409
  `CONFLICT_STALE_VERSION` on a stale save), and `GET /pipelines/{name}/graph/raw` now publishes the
  matching `ETag` — the same `ETags` pattern `ComponentRoutes` uses, applied to the route that was actually
  missing it. See `ControlApiPipelineGraphIfMatchTest`.
- ~~**P2** · **`NO-RATE-LIMIT-EXPENSIVE-ROUTES-1`**~~ ✅ **SHIPPED 2026-09-17.** Grounding confirmed: no
  throttling existed anywhere in `com.gamma.control` on `/db/query`, `/bi/query`, `/recon/*` or `/agent/*`.
  Added `RateLimiter`, a fixed-budget (burst 20, refill 1/3 req/s) in-memory token bucket keyed per
  authenticated Subject (falling back to caller IP), wired into `ControlApi.routeDispatch` right after
  the AuthN gate, scoped to exactly those four prefixes. An exhausted bucket answers `429 RATE_LIMITED`
  in the existing error-body shape. `ControlApiRateLimitTest` + regression sweep
  (`ControlApiDbBrowserTest`, `ControlApiBiQueryTest`, `ControlApiReconTest`, `CapabilityManifestTest`,
  33 tests total) all pass, verified in a clean worktree at HEAD (the shared tree carries an uncommitted
  `release=27` pom WIP that breaks local `-am` builds under this JDK 26 toolchain).
- **P3** · 🔶 **`ERRORCODE-DEFAULTED-1` — the 403 SLICE IS DONE 2026-09-17; the file sweep remains, and
  this row's OWN NUMBERS for it were WRONG.** Premise confirmed: `ErrorCodes.defaultFor(403)` is
  `PATH_JAIL_VIOLATION`, so a share mismatch and a bad provider signature were both telling the client a
  path escaped a jail. ⚠ **The row named two sites; there were TWELVE bare of twenty** — all now explicit,
  and **zero bare 403 sites remain repo-wide**: `PERMISSION_DENIED` at `ShareRoutes:111`,
  `DeliveryStatusRoutes:137`, `AgentRoutes:98/:125`, `ExchangeRoutes:293/:318`; and `PATH_JAIL_VIOLATION`
  now STATED at the six that genuinely are jail refusals (`ConfigPreviewRoutes:74`,
  `ConnectionRoutes:145/:161`, `DbBrowserRoutes:182`, `PipelineGraphRoutes:514`, `RunRoutes:208`,
  `WriteGates:57`) — the default was right there, and saying so stops the next reader re-deriving it.
  ✅ **No new constant, and no UI risk:** `PERMISSION_DENIED` already exists and is already in the SPA's
  `V1ErrorCode` union; the SPA maps only `CONFLICT_STALE_VERSION` to behaviour, everything else renders
  generically ⇒ no client degradation.
  ⚠ **Structural change, deliberate:** `ErrorCodes` and its constants became **public** (`defaultFor` stays
  package-private). It was package-private, so `ExchangeRoutes` — in another module — literally could not
  name a code despite `ApiException`'s public 3-arg constructor. Not annotated `@PublicApi`, which in this
  repo marks INTENT rather than exposure. `ApiContractTest` still pins the catalog, 5/5.
  🔴 **The row's sweep figure is REFUTED and must not be used to plan the rest:** it says
  *“`RunRoutes`, `ComponentRoutes`, `AgentRoutes` lead with 9 bare sites each”*. Re-derived:
  `ComponentRoutes` **36** · `inspecto-ops/ObjectRoutes` **34** (a module the row missed ENTIRELY) ·
  `PipelineGraphRoutes` 30 · `ExchangeRoutes` 30 · `ReconRoutes` 26 · `AgentRoutes` 22 · `RunRoutes` 21 —
  wrong by ~3×. **620 of 648 sites remain bare, none of them 403.** ⛔ Re-derive the counts per file rather
  than trusting this row.

  - **P3** · **`ERRORCODE-DEFAULTED-1` (original row)** — `ApiException` carries an explicit `ErrorCodes` constant at 15 of 648
  throw sites; the rest take `ErrorCodes.defaultFor(status)` (`ErrorCodes.java:29-41`), whose `case 403`
  is `PATH_JAIL_VIOLATION` — so `ShareRoutes.java:111` (a dataset/share mismatch) and
  `DeliveryStatusRoutes.java:137` (a bad provider signature) tell the client a path escaped a jail. Fix: code
  the 403 sites first (`PERMISSION_DENIED` or a new constant), then sweep by file (`RunRoutes`,
  `ComponentRoutes`, `AgentRoutes` lead with 9 bare sites each).
- ~~**P3** · **`IFMATCH-COVERAGE-GAP-1`**~~ ✅ **PARTIALLY SHIPPED 2026-09-17 — audited file-by-file, only 2 of
  5 named areas were genuinely exposed.** Verdict: `RunRoutes` (action-verbs, no client-replayed full state)
  and `ShareRoutes` (append-only token issue) need nothing. `JobRoutes` `PUT /jobs/{name}` and all four
  `AccessRoutes` writers (`/access/roles`, `/policies`, `/catalog`, `/profiles/{id}`) were genuine
  read-modify-write with **no precondition check at all** — fixed with the same `ETags.of`/`ETags.requireMatch`
  idiom as the pipeline-graph save (`26a2a2f0`): 409 `CONFLICT_STALE_VERSION` on a stale `If-Match`, no-op when
  absent. ⚠ **`AlertRoutes` `PUT /alerts/rules/{name}` is ALSO genuinely exposed but deliberately left** — no
  per-item GET exists yet to hand a client a baseline ETag (only the list route does), so it needs a smaller
  design step first, not a mechanical extension of this fix. Filed as a residual if picked up again.
  `ControlApiJobIfMatchTest` (3/3) + `ControlApiAccessIfMatchTest` (4/4) + the 4 pre-existing CRUD suites they
  sit beside, 23/23 total, BUILD SUCCESS. The row's own "7 of 95 files" count was not re-derived (out of
  scope) — treat it as unverified if reopened.
  - **P3** · **`IFMATCH-COVERAGE-GAP-1` (original row)** — only 7 of 95 files in `com.gamma.control` reference
    `If-Match`; Run, Job, Alert, Access and Share writes are read-modify-write with no version check. Fix:
    audit each for real double-write exposure and extend `ETags` where one exists (append-only routes need
    none).
- ~~**P3** · **`DB-SCHEMA-VERSION-1`**~~ ✅ **CLOSED 2026-09-17 — audited, no-op: no drift exists to guard.**
  Checked `git log -p --follow` on all ~10 other `Db*Store` classes against the `DbAcquisitionLedger`/
  `DbRunLease` guarded-`ALTER` idiom: every one of them has shipped the identical column set since its first
  commit — nothing was ever added post-creation, so `CREATE TABLE IF NOT EXISTS` already covers every
  install. Writing speculative no-op `ALTER ... ADD COLUMN IF NOT EXISTS` statements for columns that have
  existed since day one would be dead code with nothing to protect against. No files changed. **Reopen this
  the day any of these stores' column set actually changes** — that's the point at which the guard earns its
  keep, not before.
  - **P3** · **`DB-SCHEMA-VERSION-1` (original row)** — no `schema_version` table or stamp exists (repo-wide
    grep: none); correctness on upgrade rests on every store pairing a new column with a guarded `ALTER`, a
    discipline `DbRunLease` itself records failing once (`last_run_at`). Verified present in
    `DbAcquisitionLedger.java:364` and `DbRunLease`; unverified in the other ~8 `Db*Store` classes. Fix:
    audit those, then a per-table version row so a breaking change fails loudly.
- ~~**P3** · **`DB-STATUS-INDEX-1`**~~ ✅ **CLOSED 2026-09-17.** Premise CONFIRMED — `DbStatusStore.initSchema`
  created six tables and **zero** indexes while the class javadoc already claimed *“plus the few columns we
  actually index on”*; the DDL contradicted its own doc. ✅ **Indexed from the CLOSED predicate set, not from
  guesswork:** `committedBatches` (`:123`) and `readRows` (`:165-166`) are the ONLY SQL read sites in the
  class, and every list endpoint reaches these tables through the `StatusStore` seam (`RunRoutes:65/67/68`,
  `:318`, `ReportService:132/133/161/177`, `MetricsService:81`) ⇒ `_commits (pipeline)` ·
  `_batches`/`_files`/`_quarantine`/`_unpack (pipeline, seq)` · `_lineage (pipeline, batch_id, seq)`.
  ⚠ The leading `pipeline` column is earned by the WRITE path too (`deletePipeline`'s per-sync DELETE and
  `renamePipeline` hit all six tables), so no index rests on a read alone. `seq` trails deliberately:
  Postgres reads the sort off the composite, DuckDB's ART index serves the lookup and sorts after, so the
  extra column is not a DuckDB-only wager. Pinned in BOTH directions — a predicate without an index and an
  index without a predicate each fail. `DbStatusStoreTest` 11/11, `FileStatusStoreTest` 2/2.
  ⚠ **DuckDB-only coverage**: the assertion reads `duckdb_indexes()`, and Postgres skips in this checkout.
  → `okf/backend/engine/db-layer.md` §3.4

  - **P3** · **`DB-STATUS-INDEX-1`** — `DbStatusStore.java` creates no index beyond the primary keys while its
  batches/lineage/quarantine tables serve the list endpoints; `DbJobRunStore.java:98` and
  `DbEventStore.java:222` index their hot columns. Fix: check the list predicates and index them.
- ~~**P3** · **`STATUS-CSV-RETENTION-1`**~~ ✅ **SHIPPED 2026-09-17 — new `status_prune` maintenance task.**
  Premise confirmed, with two corrections: the row undercounted the families (**FOUR**, not three — `_unpack_`
  too, `PipelineConfigParser:169-197`), and its *“28 exist today”* is **unverifiable in a clean checkout**
  (`spaces/*/data/` is gitignored runtime data) — the mechanism claim stands regardless.
  🔴 **The load-bearing detail the row MISSED:** `<pipeline>_commits.log` lives in the SAME directory and is
  **NOT run-timestamped** — it is the durable *“did this batch finish”* ledger — as does per-batch
  `manifests/`. ⛔ So the filter is **POSITIVE** (the four `_<family>_*.csv` names) and the walk one level
  deep; a glob-by-age would have eaten the ledger. Mutation-tested: widening the filter to `.+` fails the
  commit-log guard.
  ⚠ **Deliberately NOT a `cleanup` job**, and the reason is scope: `cleanup` takes one `dir`, so it needs one
  job per Pipeline and **silently misses every Pipeline added later**; `status_prune` walks
  `<dataDir>/*/status/`. `retention_days` required with no default (matching every other prune — `cleanup`
  stays the only one with a default), dry-run preview, unreadable mtime ⇒ never pruned. Registered in
  `MaintenanceJob` (switch arm, `BUILT_IN_TASKS`, ids 21→22); no new `ParameterDecl` needed — the descriptor
  is derived from `availableTasks()`, so the authoring form picks it up. Engine **131/0/0**.
  → `okf/backend/control-plane/jobs.md`

  - **P3** · **`STATUS-CSV-RETENTION-1`** — every run creates `<pipeline>_status_<ts>.csv` plus `_batches_`/
  `_lineage_` siblings (`PipelineConfigParser.java:170-176`) and nothing prunes them (`LedgerPruneTask`
  prunes the DB ledger only); 28 exist under `spaces/*/data/*/status/` today. `BACKLOG` line ~706 mentions
  this as an aside inside the Completeness-KPI row, never as work. Fix: a maintenance task that ages them out
  by `retention_days`.
- ~~**P3** · **`BACKUP-MANIFEST-ATOMIC-1`**~~ ✅ **SHIPPED 2026-09-17 — the sidecar is staged +
  `ATOMIC_MOVE`d via `AtomicFiles.write`.** Premise confirmed; the cited lines had drifted (region is
  `BackupTask.java:118-155`, the sidecar write was at `:147`, not `:148`).
  🔴 **Why a torn manifest is WORSE than a missing one, which is the point of the row:** `verify()` and
  `restore()` are **fail-closed ON the sidecar** — it records per-file SHA-256 plus the archive hash so
  verification never trusts the archive it is verifying ⇒ a half-written manifest fails a GOOD archive. The
  failure mode is now **absent or complete**, never partial. The staging temp is `.manifest-*.tmp`, which
  `verify()`'s `.zip` listing and `backup()`'s own walk both ignore.
  ⚠ **Pinned by a SOURCE-SCAN test**, because no *successful* run can distinguish the two implementations —
  mutation-tested by reverting to `Files.writeString`. ⚠ The first draft of that assertion was itself broken
  (`contains("Files.write(sidecar")` matches inside `AtomicFiles.write(sidecar`) and was caught only by
  running it; it is now a `(?<!Atomic)` regex with the trap recorded in a comment.
  ⛔ **`inspecto-backup` compiles ONLY under `-Pedition-professional`/`-enterprise`** — a bare `mvn -o test` never
  compiles this change at all. Backup **17/0/0**. → `okf/backend/control-plane/jobs.md`

  - **P3** · **`BACKUP-MANIFEST-ATOMIC-1`** — the backup zip is staged and `ATOMIC_MOVE`d
  (`BackupTask.java:117-148`) but its sidecar manifest at `:148` is a direct `Files.writeString`; a crash
  between the two leaves a zip with a missing or stale manifest. Fix: `AtomicFiles.write`.

- ~~**P2** · **`SQLIDENT-NINE-COPIES-1`**~~ ✅ **SHIPPED 2026-09-17 — the row was wrong in BOTH directions and
  its prescribed home was a MODULE CYCLE.**
  🔴 **16 copies across FIVE modules, not nine across four** — four of them **inline**, with no helper method,
  which is why a grep for a signature missed them. And **two of the nine were never copies**: `RowShaper:848`
  and `ScratchTables:107` are the delegating helpers JAVA-5 left behind, so the row filed the line numbers of
  the FIX as the defect.
  🔴 **The premise failed too:** `com.gamma.util.SqlBuilder.quoteIdent` was **already public** and already
  imported by three modules — there were TWO rival canonicals, not one package-private one.
  ⛔ **`inspecto-sql` cannot be the home: it DEPENDS ON `inspecto-util`**, where two copies live, so the
  prescribed move was a cycle and could not have consolidated the two copies the row did not know about. The
  canonical is now `inspecto-util`, reachable by all five holding modules — confirmed by a green 32-module
  `test-compile`, not by reading poms.
  ✅ **Nine sites deliberately NOT consolidated, each a BEHAVIOUR difference:** `RecordTransform.quoteIdentifier`
  returns a plain identifier **unquoted** and mirrors the SPA's `sql-functions.ts` in two languages;
  `DuckDbCsvIngester.escapeIdent` returns the escape **without** surrounding quotes, so delegating would
  double-quote; `DbConnectionWorkbench.quoteIdent` uses the **JDBC driver's** own quote string; and six are
  RFC-4180 **CSV field** escapes — same bytes, different concept. ⚠ `ReportJob` contains BOTH kinds, so a
  file-level sweep would have corrupted it.
  ✅ **The mutation proof crosses a module boundary** — dropping the escape in `inspecto-util` reds
  `ConditionSqlTest` in `inspecto-engine`, which is what proves real delegation. ⚠ The nine pre-existing
  `ConditionSqlTest` cases did not notice the mutant at all.
  ⚠ **Stated plainly: the other TEN delegating callers are NOT individually mutation-proven** — all private
  static with no test reaching the quoted output, and five validate against `SAFE_IDENT` first so a quote can
  never arrive in a test. Their delegation rests on a read diff; those are the ones that could regrow a copy.
  ⇒ Residual filed: `NODETYPE-SCAFFOLD-EMITS-A-COPY-1`. Original row follows.
  - **P2** · **`SQLIDENT-NINE-COPIES-1`** — SQL identifier quoting (`"\"" + ident.replace("\"", "\"\"") + "\""`) is
    re-implemented byte-identically in nine classes across four modules while the canonical `SqlIdent.q`
    (`inspecto-engine/src/main/java/com/gamma/pipeline/exec/SqlIdent.java:19`) is package-private:
    `DbBrowserRoutes.java:331`, `GeoRoutes.java:268`, `InvRoutes.java:267`, `MaterializeTask.java:185`,
    `QueryExecutor.java:260`, `MeasureCompiler.java:258`, `ReconService.java:753`, `RowShaper.java:848`,
    `ScratchTables.java:107`. `SqlIdent`'s own javadoc calls drift here "an injection or a mangled identifier";
    a hardening change can only ever reach one copy. Fix: promote `SqlIdent` to `inspecto-sql` and delegate all
    nine, as `JAVA-5` did inside `pipeline.exec`.

- **P3** · 🔴 **`NODETYPE-SCAFFOLD-EMITS-A-COPY-1` — the node-type scaffold plants the next SQL-quoting
  copy into every generated Executor.** Filed 2026-09-17 while consolidating `SQLIDENT-NINE-COPIES-1`.
  `tools/templates/nodetype/src/main/java/__packageDir__/__className__Executor.java:111` emits its own private
  `"\"" + ident.replace("\"", "\"\"") + "\""`, so **that row regrows from the template** however many call
  sites are consolidated.
  ⚠ Not fixed with the rest because the generated module's pom is not visible from the template — it cannot be
  confirmed that a scaffolded module would have `inspecto-util` on its classpath — and the template's javadoc
  points at "the contract note above", **which is not in the file**; that note appears to have been lost in an
  earlier edit. ⇒ Fixing this properly means settling the scaffold's dependency contract first.
  → `okf/backend/engine/node-types.md`

- ~~**P3** · **`QUEUES-USER-FACING-COPY-1`**~~ ✅ **SHIPPED 2026-09-17 — confirmed against `EDITIONS.md`/
  `api-stability.md` that "queues" is genuinely a deleted route family (`RETIRE-HALVES-1`, 2026-09-14), not a
  live capability.** Dropped "and queues"/"tags and queues" → "and tags" in all three sites:
  `AbsentObjectRoutes.java:27` (the Personal-edition 503 body), `object-mail.component.html:4`,
  `tags.component.html:4`. No spec asserted the old string in either language. UI: `object-mail.component.spec.ts`
  + `tags.component.spec.ts` via `npx ng test`, 22/22 passed.
- **P3** · **`QUEUES-USER-FACING-COPY-1` (original row)** — three shipped strings still promise a capability
  deleted in 2026-09. Filed 2026-09-17 out of `ROOT-POM-QUEUES-ROUTE-CLAIM-1`, which corrected the comments.
  ⚠ **These are product copy, not comments, and none is test-asserted:**
  `AbsentObjectRoutes.java:27` — the Personal-edition **503 body** — plus
  `inspecto-ui/…/admin/objects/object-mail.component.html:4` and `…/admin/tags/tags.component.html:4`, all
  reading "notes, links, tags **and queues** are provided by the…". ⇒ **A 503 telling an operator that a
  deleted feature is available in Professional is a small but real defect.**
  ⚠ Left out of the pom row deliberately: changing shipped copy and two Angular templates is a different
  change class and needs the `angular-ui` skill. → `okf/capabilities/editions/editions.md`
- ~~**P2** · **`PACK-SPI-LOAD-NOT-FAULT-TOLERANT-1`**~~ ✅ **SHIPPED 2026-09-17 — the row's MECHANISM is
  refuted and the real hole is worse.**
  🔴 **`ServiceConfigurationError` was already contained** — `load()` catches it per jar, so a provider that is
  missing or throws in its constructor rejects only its own pack. **`LinkageError` is what escaped**:
  `ServiceLoader` wraps a class it cannot FIND in an SCE, but a class it finds and cannot DEFINE throws
  `LinkageError` straight out of `hasNext()`. Neither an `Exception` nor an SCE — so it left `load()`, left
  `rescan()`'s per-pack loop, killed discovery of **every other pack**, and at `scanAtStartup()` killed the
  boot. A pack compiled for a newer Java is the everyday case.
  ⛔ **The prescribed fix is not implementable:** `OptionalSpi` lives in `inspecto-processor`, which **depends
  on** `inspecto-engine`, so `JobPackManager` cannot reference it. Adopting it means relocating a class used by
  five call sites — a separate row, not a detail of this one.
  ⛔ **Warn-and-skip per element was also REJECTED** — a pack is all-or-nothing by a decision stated three
  times in that code, and `OptionalSpi`'s own javadoc says swallowing a per-provider failure "would turn a bug
  into a silent absence". Skipping one broken provider would half-load a third-party pack.
  ✅ The fix is ONE catch clause: `Exception | ServiceConfigurationError | LinkageError` — not bare `Error`,
  so `OutOfMemoryError`/`StackOverflowError` still propagate.
  ✅ **The ~20 core loops were CHECKED, not assumed**: all use single-arg `load()`, and `setContextClassLoader`
  appears nowhere in `inspecto*/src/main/java`, so none runs over an operator-supplied loader. Scope held.
  ✅ The test pins the **blast radius** — two jars, one with an 8-byte class file, asserting the OTHER still
  registers. Mutation-proved: it reds as an **ERROR, not an assertion**, and the stack trace is the finding.
  Original row follows.
  - **P2** · **`PACK-SPI-LOAD-NOT-FAULT-TOLERANT-1`** — four `ServiceLoader` loops over an OPERATOR-SUPPLIED job-pack
    class loader iterate raw (`JobPackManager.java:203,209,216,219`), so one broken class in one third-party
    pack throws `ServiceConfigurationError` out of `hasNext()` and kills discovery of every other pack; the
    tolerant loader already exists (`OptionalSpi.java:73-91`). ~20 further raw loops sit in core code over the
    app class loader (lower risk; the entries ship with the build). Fix: an `OptionalSpi.all(spi, loader)`
    overload for pack discovery, warn-and-skip per element.
- ~~**P2** · **`CONNECTOR-SIDECAR-SHADES-LOGGING-1`**~~ ✅ **CLOSED 2026-09-17.** Grounding confirmed the gap:
  `inspecto-connectors/pom.xml` (then lines 174-180) and `inspecto-notify-channels/pom.xml` and
  `inspecto-security/pom.xml` each excluded `logback.xml` but not `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`,
  `org/slf4j/impl/**` or `ch/qos/logback/**`, unlike `inspecto-agent/pom.xml:145-147`. As-built: copied
  `inspecto-agent`'s three-line exclude block into all three sidecar poms' shade filters; built each under
  `-Pedition-professional` and confirmed by `unzip -l` on the shaded `*-sidecar.jar` that none carries those
  paths. Added the packaging smoke assertion in `inspecto/package.ps1` step 6e (before the boot-smoke
  classpath is built): scans every staged jar for `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`
  and throws if more than one jar registers it.
- **P3** · 🔴 **`LEGACY-ASN-SRC-TREE-UNBUILT-1` — PREMISE REFUTED 2026-09-17, and its REMEDY WOULD HAVE
  DELETED COMPILED SOURCE.** ⛔ **45 of the 66 files ARE compiled**, and not in theory: `legacy-code/pom.xml`
  declares `<sourceDirectory>../../src/main/java</sourceDirectory>`, which resolves from
  `asn-parser/asn-decoders/legacy-code/` to exactly **`asn-parser/src/main/java`**; `legacy-code` is an
  UNCONDITIONAL `<module>` of `asn-parser/asn-decoders/pom.xml` (not profile-gated), and
  `legacy-code/target/classes` holds **41 `.class` files** from a real build. ⇒ *“no pom compiles”* is false.
  ⚠ **The row's own disclaimer is exactly backwards.** It says *“Not the same subject as the refuted
  `BACKLOG-STALE-LEGACY-POM-1` (that was `legacy-code/pom.xml`)”* — but `legacy-code/pom.xml` is precisely
  what compiles this tree, so it is the SAME subject, and this row repeats the very claim that was retracted.
  🔴 **Two false zeros in one lineage now**: the first from `ls -d legacy-code` at the repo root (a nested
  path), this one from *“`asn-parser/pom.xml` does not exist”* — true, and irrelevant, because the compiling
  pom is one level down with a `..`-relative source root. ⛔ **A module's source root need not live under its
  own directory**; grep `<sourceDirectory>` before concluding a tree is unbuilt.
  ✅ **What survives, narrowed to what is true:** the **21 files under `asn-parser/src/test/`** are genuinely
  unbuilt — `legacy-code/pom.xml` sets no `testSourceDirectory` and has no `target/test-classes`. And the
  original irritant stands: the compiled `main` tree still shadows current types with superseded
  `ByteSource`/`TxConfig`/`Tag` twins that greps and refactors keep hitting.
  ⇒ **Re-scoped:** decide the 21 test files (wire them or delete them), and treat the shadowing twins as a
  rename/deprecation question. ⛔ **Do NOT delete `asn-parser/src/main/java`** — 41 classes ship from it.

  - **P3** · **`LEGACY-ASN-SRC-TREE-UNBUILT-1` (original row)** — `asn-parser/src/` holds 66 Java files that no pom compiles (`asn-parser/pom.xml` does not exist;
  the root aggregates `asn-parser/asn-decoders` only), shadowing current types
  with superseded `ByteSource`/`TxConfig`/`Tag` twins that greps and refactors keep hitting. ⚠ Not the same
  subject as the refuted `BACKLOG-STALE-LEGACY-POM-1` (that was `legacy-code/pom.xml`). Fix: delete the tree
  (history keeps it) and say so beside the root `<modules>`.
- ~~**P3** · **`PARENT-UNMANAGED-CHILD-VERSIONS-1`**~~ ✅ **SHIPPED 2026-09-17 — the fix direction is right and
  the row's DIAGNOSIS is wrong.** These did not "bypass the parent's `dependencyManagement`": the parent
  managed **none** of the three. Nothing was being overridden — they were simply never hoisted.
  ⚠ **The row missed a site:** `inspecto-engine/pom.xml:74` pins `com.gamma.asn:asn-facade`. ⛔ Deliberately
  NOT moved — `asn-decoders` is a standalone reactor aggregated for build ordering only, documented as outside
  the root parent's version management; hoisting it would contradict a recorded boundary.
  ✅ **No artifact was pinned at CONFLICTING versions** — both logback literals 1.5.18, all five shade pins
  3.5.2, so nothing had to be chosen between.
  ⚠ **opencsv was moved AGAINST its own recorded comment** ("single-owner … so it stays pinned here"). That
  premise had already become false: root `pom.xml:195` **reasons from the number** — `commons-lang3` is pinned
  to 3.18.0 precisely because opencsv 5.9 drags an older transitive — so the value had two homes and a bump
  would silently stale the parent's rationale. The comment now says so rather than being deleted.
  ✅ **Inertness PROVEN, not asserted:** a reactor-wide `dependency:tree` before/after is **byte-identical**,
  1083 lines across all 32 enterprise modules; `effective-pom` and a real `package` run confirm shade still
  resolves 3.5.2 into all five shaded jars. ⇒ that diff is the cheap, strong proof for any future relocation.
  Original row follows.
  - **P3** · **`PARENT-UNMANAGED-CHILD-VERSIONS-1`** — `inspecto-util/pom.xml:58` (opencsv 5.9), `inspecto-etl/pom.xml:96`
    and `inspecto-event/pom.xml:68` (the same logback 1.5.18 literal twice) bypass the parent's
    `dependencyManagement`, and the shade plugin version is child-local in five poms — a bump half-lands. Fix:
    move them to parent properties / `pluginManagement`.
- ~~**P3** · **`ROOT-POM-QUEUES-ROUTE-CLAIM-1`**~~ ✅ **SHIPPED 2026-09-17 — `/queues` DID exist and was
  deliberately RETIRED, so correcting the comment deletes no commitment.** Full lifecycle from `git log -S`:
  created in `a5b89a89`, **moved to `inspecto-ops` in `e8d98918`** (so the root-pom comment was TRUE when
  written), deleted in `519673a7` by RETIRE-HALVES-1. The retirement is already recorded in `EDITIONS.md:328`,
  `api-stability.md:128` and `incidents.md:86`. ⇒ EDG-01 cell 7 is **three** route families, not four.
  ⚠ **Provenance trap:** `git log --grep="RETIRE-HALVES"` does **NOT** find the commit that removed the routes
  — it landed under a whitepaper message when a concurrent session committed the staged tree. Search the
  symbol, or `--diff-filter=D`.
  ⚠ Corrected at every site carrying the claim, not just the named one: the root pom, `inspecto-ops`'s
  `<description>`, and two **comment-only** javadoc/line comments in `ControlApi` and `OpsEngine`.
  ⇒ Residual filed: `QUEUES-USER-FACING-COPY-1`. Original row follows.
  - **P3** · **`ROOT-POM-QUEUES-ROUTE-CLAIM-1`** — the root pom's EDG-01 cell-7 comment says the `/queues` routes moved
    to `inspecto-ops`, but no `QueueRoutes` class exists anywhere and the module's `RouteModule` service file
    lists `ObjectRoutes, NoteRoutes, TagRoutes` only. Fix: correct the comment, or file the missing surface if it
    was meant to ship.

### Module audit 2026-09-17 — frontend first, then the end-user request path down to acquisition and packaging

**Where we stand (grounded in code and a LIVE server, not board text).** The UI↔API contract is intact: every
one of the SPA's 252 `apiUrl(...)` paths matches a registered route once the `/spaces/{id}` prefix and the
infra probes are accounted for — zero dead calls. The UI suite is 3005/0 (5 skipped) with a production
build at 1.01 MB raw, well under budget, and the design-token guard is green; 143 of 158 components have a
spec. The control plane's write-gate chain is centralised in `WriteGates.java`; the optional modules
(security, policy, ops, backup, agent) are fail-closed where the classic defects live — RS256 pinned,
`at+jwt` allow-list, prepared statements throughout `inspecto-ops`, zip-slip pinned, mutating tools refused
before any model call. The acquisition stack is protocol-tested for SFTP/FTP (real in-process servers) and
stub-tested only for S3/GCS/Azure/Kafka, by disclosed design. `asn-parser` IS consumed (two engine
ingesters), depth-bounded, and free of infinite loops. **What is still problematic clusters in four places:**
(1) the release path has NEVER executed and its one published zip pairs a Linux runtime with Windows-only
DuckDB extensions; (2) the ASN.1 length arithmetic trusts attacker-declared sizes; (3) silent failure in the
core authoring journey and on session loss; (4) test posture where it matters — 94% of route tests never
arm an Authenticator, `RunRoutes` has none, and `ng lint` is not configured at all. Two live P1s found by
this audit were FIXED in the same shift rather than filed: `SqlGuard` let a bare path literal in `FROM`
position read any CSV/Parquet/JSON on the server (DuckDB replacement scan — returned rows of
`spaces/demo/audit/jobs_runs.csv` to an ungated call, now refused by `SqlGuard.RELATION_REF`, 17/17), and
`SessionService.onAuthLost` dropped state without navigating to sign-in (now navigates).

- ~~**P1**~~ · **`RELEASE-BUNDLE-PLATFORM-MISMATCH-1`** — ✅ **SHIPPED 2026-09-17** — `package.ps1` reads the platform off the EMBEDDED jlink image (`Get-RuntimePlatform`: `java.exe` vs an ELF `bin/java`, `e_machine 0x3E`) and names each zip `inspecto-deploy-<platform>.zip`; a Linux cross-build failure THROWS unless `-AllowPartialRuntime`; on a POSIX host the zip is written with Info-ZIP `zip -X` after `chmod +x *.sh` so the exec bit survives (Windows hosts cannot preserve modes — stated at the site); `release.yml` collects every `inspecto-deploy-*.zip` through `tools/release-collect.sh`, FAILS when the runner's own platform is missing, and verifies per zip that the runtime binary, the extension directory and (on Linux) `-rwx serve.sh` agree. Guard `tools/check-bundle-platform.mjs` (ci.yml, `guards` job) falsified three ways. Local Personal package produced both zips with matching entry tables. ⚠ NOT verifiable from Windows: the POSIX zip branch, the ELF read of a host-linked temurin image, and `release-collect.sh` end-to-end — the first tag is their first run (`RELEASE-PIPELINE-NEVER-EXECUTED-1` stays open for exactly that). *(Original:)* on `ubuntu-latest` `$env:OS` is unset so the jlink
  image is LINUX, yet the only zip a tag publishes is built by `Compress-BundleForPlatform -Platform
  'windows_amd64'` (`inspecto/package.ps1:1746`; collect steps at `.github/workflows/release.yml:168-210`):
  the published bundle pairs a Linux JVM with Windows-only DuckDB extensions — the exact air-gap failure
  `-RequireExtensions` exists to prevent. Fix: derive the extension platform from the runtime actually
  embedded and emit one artifact per target platform; the file it changes is `inspecto/package.ps1`.
- ~~**P1** · **`BER-LENGTH-OVERFLOW-1`**~~ ✅ **SHIPPED 2026-09-17 — CONFIRMED by constructing the bytes, and
  WORSE than the row stated.** `02 88 7F FF FF FF FF FF FF FF` (long-form 8-byte length = `Long.MAX_VALUE`)
  wrapped `valueOffset + valueLength` negative, so `end > limit` passed and `BerReader` returned a Tlv with
  **`endOffset = -9223372036854775799`**. `RecordReader` then counted it as `recordsOk++` and used the negative
  value as its cursor — so the `ArithmeticException` escaped `hasNext()` ITSELF. **A malformed record was being
  recorded as successfully parsed.**
  ✅ Both sites now compare against a remaining-bytes budget (`valueLength > limit - valueOffset`), both
  operands non-negative, subtraction cannot overflow. `BerParseException` chosen because `BerFuzzTest`'s own
  javadoc states the contract: malformed input must never crash with anything else.
  ⚠ One row detail imprecise: `RecordReader` never calls `Tlv.value()`, so the unchecked throw actually escapes
  from `SchemaBinder` — outside the reader's recovery entirely.
  🔴 **A blind spot in the existing fuzz guard closed on the way past:** it asserted `endOffset <= length`,
  which a NEGATIVE endOffset satisfies, and which measured `true` on the overflowing input. ⚠ Stated honestly —
  that test stayed GREEN under the mutant because its generator never emits an 8-byte length, so the added
  lower bound is belt-and-braces, not the guard.
  ⇒ Residual filed: `BER-VALID-BUT-HUGE-ALLOCATION-1`. Original row follows.
  - **P1** · **`BER-LENGTH-OVERFLOW-1`** — `long end = valueOffset + valueLength` with `valueLength` accepted
    up to `Long.MAX_VALUE` overflows negative and passes the `end > limit` guard (`BerReader.java:102` and
    `:128`); `RecordReader.java:249` catches only `BerParseException`, so `Tlv.java:33`'s
    `Math.toIntExact` escapes as an unchecked `ArithmeticException` — or, just under 2 GB, allocates a
    `byte[]` sized by the input. Fix: `Math.addExact` (or `valueLength > limit - valueOffset`) at both sites,
    raising `BerParseException`; the file it changes is `BerReader.java`.

- ~~**P3** · **`BER-VALID-BUT-HUGE-ALLOCATION-1`**~~ ✅ **SHIPPED 2026-09-24 — a cap, per the operator's 2026-09-24 decision.** `BerReader` refuses a PRIMITIVE value declaring more than `max_value_bytes` (default `BerReader.DEFAULT_MAX_VALUE_BYTES` = 64 MiB) at parse time, as a `BerParseException` naming tag, length and cap; it fails its record through `RecordReader`'s `ErrorListener`. Configured as `asn1.max_value_bytes` / `ingester_config.max_value_bytes`. *(Original:)* **a length that is VALID but enormous still allocates.**
  Filed 2026-09-17 out of `BER-LENGTH-OVERFLOW-1`, and **measured rather than reasoned**: with a stub
  `ByteSource` reporting 3 GB, `04 84 95 02 F9 00` (2,500,000,000 bytes) **parses cleanly** — `endOffset`
  genuinely within the source — and `Tlv.value()` then throws `ArithmeticException` on `Math.toIntExact`. Just
  under 2 GB it would instead SUCCEED and allocate a `byte[]` sized by attacker-controlled input.
  ⛔ **No bounds check can reject this** — the length is legitimate. It needs a cap or a streaming accessor,
  which is a design call on `Tlv.value()`'s `byte[]` return type. Reachable only via `ByteSource.map`
  (`MappedSource` is explicitly >2 GB capable); `HeapSource` cannot exceed 2 GB.
  → `okf/backend/engine/parser-plugins.md`

- ~~**P2** · 🔴 **`NOTIFY-SMTP-STARTTLS-OPPORTUNISTIC-1`**~~ ✅ **CLOSED 2026-09-17 — same call made as the
  parent row, in the same direction.** `SmtpEmailChannel.buildMessage` now sets
  `mail.smtp.starttls.required=true` alongside the existing `.enable`, gated on the SAME `notify.smtp.starttls`
  flag the parent row's fix already used — no new config surface was added. *(Original:)* identity
  verification does not help if TLS never starts: `mail.smtp.starttls.enable` is opportunistic, so a MITM who
  simply declines to advertise `STARTTLS` got a plaintext session, the hostname check never ran, and SMTP AUTH
  credentials went out in the clear regardless of `NOTIFY-SMTP-TLS-VERIFY-1`'s fix.
  ⚠ **Operator call made explicitly:** this converts the silent weakness into a loud failure — an install that
  set `starttls=true` against a relay which never actually offered `STARTTLS` (and so has been silently
  sending plaintext) now fails to deliver instead. That is the intended outcome, consistent with the house
  style of `NO-RATE-LIMIT-EXPENSIVE-ROUTES-1` and `UI-CAPABILITY-AFFORDANCE-1`: a security feature that
  silently no-ops under MITM is worse than a loud, noticeable failure. No escape hatch was added.
  ⚠ **Release-note-worthy**, documented in `SmtpEmailChannel`'s javadoc (replacing the "known gap" comment)
  and in `okf/backend/control-plane/events-metrics.md` (replacing the "remains open" note) — not in
  `docs/EDITIONS.md`, which carries no per-fix release-note section for this module.
  ✅ Proved by new `SmtpEmailChannelStarttlsRequiredTest`: a fake relay that never advertises `STARTTLS` is
  refused (no plaintext fallback, no `AUTH` line ever reaches it); a relay with `starttls=false` (unaffected
  control) carries no `required` property at all. Existing `SmtpEmailChannelTlsIdentityTest` (STARTTLS relay
  that DOES support it, valid cert) still passes unmodified, confirming supported relays are unaffected.
  Verified: `mvn -o -Pedition-professional -pl inspecto-notify-channels
  -Dtest=SmtpEmailChannelStarttlsRequiredTest,SmtpEmailChannelTlsIdentityTest,SmtpEmailChannelTest test` — 11/0/0.
  → `okf/backend/control-plane/events-metrics.md`
- ~~**P2** · **`BER-FRAMING-UNCHECKED-READ-1`**~~ ✅ **SHIPPED 2026-09-17 — held, with BOTH cited line numbers
  drifted by ~145 lines** (the files are 118 and 117 lines long). `00 03 02 01 05 00` — one good record plus a
  stray byte — threw `ArrayIndexOutOfBoundsException` AFTER a record had already been delivered.
  ✅ `Framing.Fixed.recordLength` now bounds its header read against `size() - trailerLength`, and
  `RecordReader` takes the framing calls inside the recovery `try` with `declared` pre-initialised to `-1` so
  `canSkip` is false when the header itself failed. **Both halves are independently load-bearing** — reverting
  either alone reds tests.
  ⚠ **The row's wording is narrowed:** a truncated TAIL reports `ParseError(STOP_FILE)` even under
  `RecoveryPolicy.SKIP_RECORD`, because a header that cannot be read yields no boundary to resync to. Records
  already read are still delivered. SKIP_RECORD cannot "save" a truncated tail; it converts a crash into a
  reported error with prior records intact. Original row follows.
  - **P2** · **`BER-FRAMING-UNCHECKED-READ-1`** — `framing.recordLength(...)`/`recordHeaderLength(...)` are
    called OUTSIDE the recovery `try` (`RecordReader.java:234-235` vs `:236`) and `Framing.java:375` reads
    header bytes with no bound against `contentEnd`, so a truncated last record crashes the ingester with
    `IndexOutOfBoundsException` instead of a skippable `ParseError`; `RecoveryPolicy.SKIP_RECORD` cannot save
    the file. Fix: move both calls inside the `try` and bounds-check the header first.
- **P2** · **`RELEASE-PIPELINE-NEVER-EXECUTED-1` — the BUILDABLE half SHIPPED 2026-09-19; what remains is an
  OPERATOR ACT, not engineering.** `release.yml` now carries `workflow_dispatch`, and every step that signs
  (`Import the release signing key`, the `-Sign` flag on all three `package.ps1` calls) or that publishes
  (`Verify checksums and signatures`, `Publish the GitHub release`) is gated on
  `github.event_name == 'push'` — so a dispatch run builds, packages and SBOMs all three editions and
  stops, holding no key and creating no release. ⚠ **It therefore proves the reactor-install →
  extension-cache → package → SBOM → smoke chain ONLY — not signing, not publication.**
  🔴 **The row's headline premise was already half-stale when re-grounded:** "every step is unexecuted" no
  longer held — `c0b3e8f9` ("actually RUN a job-bearing example") and `51da1451` had exercised parts of the
  chain since. What IS still true is that no `v*` tag has been pushed since `v3.9.0` (2026-06-01), so the
  canonical trigger has never fired end to end.
  ⛔ **The residual belongs in §2, not here:** "push a real tag" is an operator/release act no shift can do
  from this checkout — carrying it as a P2 engineering row is what kept it looking buildable.
  🔴 **PARKED INDEFINITELY 2026-09-20 — the operator states there are practically no releases after 3.x:
  *"just carry on master."*** So the tag-push half is not merely *owed*, it is **not going to happen on any
  foreseeable schedule**, and no shift can close this row by working on it. ⛔ Do not pick this row up
  expecting to finish it, and do not re-file the tag-push as engineering work. It re-enters play only if a
  release is ever cut, at which point the first `v*` tag IS the test. (Same standing model as
  `docs/BRANCHING.md` §0-A: master is the only line, the merge-forward set is permanently empty.)
  ⇒ **The row's ONLY buildable remainder is the checksum/signature split**, below — everything else here is
  waiting on an event that is not scheduled.
  ⚠ Known gap, deliberately not fixed: the checksum half of `Verify checksums and signatures` WOULD be
  meaningful on a dispatch run; it is push-gated only because the `.asc` files do not exist there. Splitting
  the two is tracked here, not forgotten.
  → `.github/workflows/release.yml`
- ~~**P2**~~ · **`RELEASE-LINUX-ZIP-NEVER-PUBLISHED-1`** — ✅ **SHIPPED 2026-09-17 with `RELEASE-BUNDLE-PLATFORM-MISMATCH-1`** (`tools/release-collect.sh` collects every platform zip and fails short). *(Original:)* `inspecto-deploy-linux.zip` is built only when a
  Linux jmods cache is found (`inspecto/package.ps1:1421-1440`) and the three collect steps in
  `release.yml` name only the `inspecto-deploy.zip*` trio, so no Linux-labelled artifact has ever been
  released. Fix: name it in each collect step and fail the release when the per-platform set is short.
- ~~**P2**~~ · **`RELEASE-LAUNCHERS-NOT-EXECUTABLE-1`** — ✅ **SHIPPED 2026-09-17 with `RELEASE-BUNDLE-PLATFORM-MISMATCH-1`** (POSIX host: `chmod +x` + Info-ZIP `zip -X`; release verify asserts `-rwx serve.sh`). ⚠ A Linux zip CROSS-BUILT on Windows still cannot carry modes — `install-service.sh` chmods `serve.sh`. *(Original:)* `Compress-Archive` stores no POSIX mode bits
  (`inspecto/package.ps1:1736`) and the only `chmod +x` lives inside the emitted `install-service.sh`, so a
  customer who unzips gets a non-executable `serve.sh`/`run.sh`. Fix: zip the Linux leg with a mode-
  preserving tool (or `tar.gz`) and assert the bit in the release verify step.
- ~~**P2** · **`NOTIFY-SMTP-TLS-VERIFY-1`**~~ ✅ **SHIPPED 2026-09-17 — held, and the row's IMPACT is corrected.**
  Verified in the actual jar rather than from general knowledge: this module pins `com.sun.mail:javax.mail
  1.6.2`, whose `SocketFetcher.java:632` reads `ssl.checkserveridentity` with a default of **false**.
  🔴 **"Accepts any certificate" is WRONG.** `SocketFetcher` already uses `SSLSocketFactory.getDefault()`, so
  the certificate CHAIN was always validated — only the HOSTNAME check was missing. It is "any **CA-valid**
  certificate, for any name": still a credential-interception path, but a narrower blast radius AND a narrower
  upgrade risk than filed.
  ⛔ **No escape hatch, deliberately.** The `FtpConnector` `tls_trust: all` precedent would have permitted one
  but does not apply — the chain was already validated, so a self-signed relay already FAILS today, and a hatch
  buys nothing `-Djavax.net.ssl.trustStore` does not serve better with authentication left ON.
  ✅ **The test pins BEHAVIOUR, not configuration:** a real STARTTLS server driven through the session the
  channel builds, two cases differing in exactly one variable — the certificate's SAN. Mutation-proved.
  ⚠ **Release-note-worthy:** verification is now always on and not configurable; a relay whose cert does not
  name `notify.smtp.host` goes from silently trusted to failing to deliver, and **that will not be noticed** —
  notification failures are logged and isolated, never surfaced.
  ⇒ Residual filed: `NOTIFY-SMTP-STARTTLS-OPPORTUNISTIC-1`. Original row follows.
  - **P2** · **`NOTIFY-SMTP-TLS-VERIFY-1`** — `SmtpEmailChannel.java:154` sets `mail.smtp.starttls.enable`
    but never `mail.smtp.ssl.checkserveridentity`, which legacy `javax.mail` defaults to `false`: a STARTTLS
    session accepts any certificate for `notify.smtp.host`, so SMTP AUTH credentials can be intercepted by
    whoever answers that name. Fix: set `checkserveridentity=true` unconditionally.
- ~~**P2**~~ · **`PIPELINE-EDITOR-SILENT-ERRORS-1`** — ✅ **CLOSED 2026-09-17 (refuted on re-ground, no
  code change): every cited `.subscribe({ next })` already carries an `error:` handler wired to the
  shared toast pattern.** Re-checked all cited lines across all 7 files (all `.subscribe({...})` object
  forms, brace-matched, not line-window matched): `pipeline-editor.component.ts` — all 13 cited call
  sites (914, 934, 1450/1452, 1637/1639, 1677, 1807/1812, 1857/1859, 1888/1895, 2047, 3058, 3088) already
  call `error:` → `this.toast.error(...)`/`onWriteError(...)`/`apiErrorMessage(...)`.
  `pipeline-parse-definition.component.ts` — the one remaining bare `.subscribe({ next })` (line 1613) is
  preceded by `.pipe(catchError(() => of(null)))`, i.e. deliberately pre-handled, not silent.
  `jobs.component.ts:348`, `job-detail.component.ts:257`, `expectations.component.ts:188`,
  `decision-rules.component.ts:195`, `connections.component.ts:176` are each either an HTTP call with an
  `error:` handler already present, or a `dialog.open(...).afterClosed().subscribe(...)` — a dialog-close
  observable that structurally never errors, so no handler is missing. `DatasetRegistrationService.ensure`
  (used bare at `pipeline-editor.component.ts:3095`) is documented "never errors" and converts every
  failure into a `{status:'failed'}` value the caller already branches on. The original row was generated
  by a 600-character observer-window script that both went stale (the handlers were added in a later
  shift) and, per its own caveat, mis-counts multi-line RxJS observers. No fix needed; no files changed.
- ~~**P2**~~ · **`UI-LINT-NOT-CONFIGURED-1`** — ✅ **CLOSED 2026-09-17: the target exists, all 177 findings are
  DRAINED (zero), and `ui.yml` runs it as a HARD GATE.** Split into three reviewable commits by risk:
  (1) mechanical — unused imports/locals/directives, `prefer-const`, useless escapes, side-effect ternaries
  rewritten as if/else; (2) structural — constructor→`inject()` in the layout shell (22 params, same tokens),
  `any`→typed (14), four outputs named after DOM events renamed with every caller (`select`→`nodeSelect` ×2,
  `toggle`→`drillToggle`, `focus`→`focusChange`); (3) accessibility — 32 template fixes under the WCAG 2.2 AA
  preset (keyboard handlers + `tabindex` on interactive elements, label/control associations, tree-item ARIA),
  16 `stopPropagation`-only menu guards exempted per line with a reason. Three rule customisations, each a
  REVIEWED DECISION with the operator's answer recorded in `eslint.config.mjs`: `_`-prefixed intent markers
  (25 rest-sibling omissions / arity-pinned mock params), `prefer-on-push` off in spec files (all 12 hits were
  test hosts), `template/eqeqeq` with `allowNullOrUndefined` (all 8 hits were `!= null`; `!== null` would have
  broken `undefined` handling). 🔴 One real bug surfaced: the design-system showcase's date `pattern` was
  `'\d{4}-…'` in a plain string — `\d` is just `d`, so it rejected its own placeholder. ⚠ Lesson for lane
  work: never junction `node_modules` into a worktree you will `git worktree remove` — it deleted half the
  shared tree's `node_modules` mid-shift (repaired with `npm install`). *(Original row:)* `npx ng lint` fails with "Cannot find 'lint' target": no
  angular-eslint builder is registered in `inspecto-ui/angular.json` and `package.json` has no lint script
  beyond `lint:tokens`, so the `angular-ui` skill's "lint" leg of GAUNTLET has never run anything. Fix: add
  angular-eslint with the repo's rules and wire it into `ui.yml`.
  ✅ **HALF SHIPPED 2026-09-17 — the target now EXISTS AND RUNS; it does not pass, and that is deliberate.**
  `angular-eslint 22.5.0` / `typescript-eslint 8.70.0` (peer range admits our TS 6.0.3) / `eslint 10.10.0`,
  with `@eslint/js` added explicitly since ESLint 10 no longer supplies it transitively. Wired into `ui.yml`
  **reporting-only**, matching the precedent the coverage step already sets there.
  ⛔ **A hard gate today would put a known-red step in front of the whole UI workflow** — the pattern that
  suppressed the reactor for a day on 2026-09-16. ⛔ Flip it by draining the findings, never by muting rules.
  🔴 **Measured BEFORE any source change: 177 problems (173 errors) across 80 of 972 files.** No source file
  was touched and no rule disabled to improve that. Rules are the upstream `recommended` presets verbatim —
  there were no house rules to port, and inventing a ruleset would have been the large unreviewed change this
  row must not smuggle in. `templateAccessibility` was **added** (+32) rather than omitted, because the
  `angular-ui` skill makes WCAG 2.2 AA non-negotiable and omitting it would be choosing a weaker ruleset to
  look greener.
  ⬜ **OWED: the drain, as THREE reviewable changes, not one sweep.** (1) ~76 mechanical — `no-unused-vars` 64,
  `prefer-const` 3, `no-useless-escape` 3, 4 dead `eslint-disable` directives, `no-useless-assignment` 2;
  largely `--fix`-able. (2) ~53 behavioural — `prefer-inject` 22, `prefer-on-push` 12 (exactly the v22
  `ChangeDetectionStrategy.Eager` shims the skill calls legacy), `no-explicit-any` 15, `no-input-rename` 3,
  `no-output-native` 4. (3) ~32 real WCAG findings — belongs with `docs/ui/accessibility-audit.md`.
  ⚠ `template/eqeqeq` (8) is the only group that can change BEHAVIOUR and must not be bulk-applied.
  🔴 **Evidence against a mechanical drain, found while measuring:** the source already carries four
  `eslint-disable` directives naming rules in NO preset (a config existed upstream in the Fuse template and was
  never tracked), and `job-parameter-specs.ts:61`'s `no-fallthrough` is an INTENTIONAL case group flagged only
  because an explanatory comment sits between the labels.
- ~~**P2** · **`UI-CAPABILITY-AFFORDANCE-1`**~~ ✅ **CLOSED 2026-09-17.** Grounded against the actual backend
  gates (line citations had drifted, capabilities re-verified): connections' Test/Probe → `canOnboardConnections`
  (`ConnectionRoutes.java:44,49,57`); expectations'/alerts' Evaluate → `canOperateRuns`
  (`ExpectationRoutes.java:53-55`, `AlertRoutes.java:42`) — a stale code comment claiming evaluation was
  unconditional is now corrected; Space import → `canAdminister` (`SpaceRoutes.java:74,160-162`, gated only
  once ≥1 Space exists — the recovery-route exemption). Each affordance now hides behind the matching
  `LensService` signal, matching the CRUD-button pattern. vitest coverage added/extended across all four
  components: 26/26 passing. ⚠ Noted but out of scope: the rest of the Spaces page (New space, per-space
  Edit/Delete/Import) still has no capability gating at all.
- ~~**P2** · **`CONTROL-AUTHGATE-TESTCOVERAGE-1`**~~ ✅ **GUARD SHIPPED 2026-09-17** (guard only — the
  underlying gap is NOT backfilled; see follow-up row below). Re-grounded before building: 9 of 138
  files under `inspecto/src/test/java/com/gamma/control/` called `Authenticators.forTest` (drifted up
  from 8/136 as other shifts landed `ControlApiRunRoutesTest` etc. in parallel this session). Building
  an armed-Authenticator variant for every one of the ~94 gated routes was out of proportion for one
  change, so this shipped the row's SECOND option: `tools/check-authgate-coverage.mjs`, wired into
  `.github/workflows/ci.yml`'s `guards` job, following the same mechanical-scan convention as
  `tools/route-gating-report.mjs`. It scans every `*Routes.java` for `withCapability("cap", …)`
  registrations, scans every `*Test.java` under `com/gamma/control` for a file that calls
  `Authenticators.forTest(`, and matches each gated route's pattern against the armed files' quoted
  path literals (simple path-match, not method+capability — same simplification tradeoff
  `route-gating-report.mjs` makes with its own regex scan). Grounded run found 94 gated routes, 15
  covered, **79 uncovered** — proven live by injecting a throwaway ungated-test route, which raised
  the guard's own count to 80 and failed it, then reverting. It is a RATCHET
  (`BASELINE_UNCOVERED = 79`), not a hard zero: CI stays green today and only goes red if a NEW gated
  route ships with no armed test, i.e. it catches future regressions of this exact class, it does not
  raise today's 15/94 coverage. **Follow-up:** the 79-route backfill itself is real, uncompleted work —
  if it's wanted, file it as its own row (e.g. `CONTROL-AUTHGATE-BACKFILL-1`) rather than reopening this
  one, since the guard and the backfill are separable deliverables.
- ~~**P2** · **`RUN-ROUTES-TEST-1`**~~ ✅ **SHIPPED 2026-09-17.** Grounding confirmed: `RunRoutes.java`
  (register/trigger/pause/resume/status/report of pipelines) had no referencing test file under
  `inspecto/src/test/java/com/gamma/control/`. Added `ControlApiRunRoutesTest` mirroring
  `ControlApiRequirementTest`'s `Authenticators.forTest` idiom through the real gate chain: register
  (`canAuthorWorkbench`) and trigger/pause/resume (`canOperateRuns`) each proven 401 → 403 (wrong/other
  capability) → success; status/report confirmed 401 unauthenticated but ungated by capability. 4/4
  passing (`ControlApiRunRoutesTest`, verified in a clean worktree at HEAD since the shared tree carries
  an uncommitted `release=27` pom WIP that breaks local `mvn -am` builds under this JDK 26 toolchain).
- ~~**P3**~~ · **`PKG-LINUX-RUNTIME-WARNS-1`** — ✅ **SHIPPED 2026-09-17** (throws unless `-AllowPartialRuntime`). *(Original:)* a failed Linux runtime build is `Write-Warning`, not `throw`
  (`inspecto/package.ps1:1436`), so a release quietly loses a platform. Fix: throw unless an explicit
  `-AllowPartialRuntime` is passed.
- ~~**P3** · **`BUNDLE-MODULE-COUNT-COMMENT-1`**~~ ✅ **SHIPPED 2026-09-17 — and BOTH options were taken.**
  Real counts re-derived from the MODULES table rather than copied from the row: **Personal 2 / Professional 11 /
  Enterprise 12**, cross-checked against `check-doc-counts`'s `enterprise-first-party-jars=12`. The drift dates
  to PKG-5 adding `inspecto-agent`.
  ⛔ **Correcting the comment alone would have moved the drift to a second place**, so `check-sbom-modules.mjs`
  now PARSES that sentence out of `bundle-modules.mjs` and asserts it against the table — the prose itself is
  the checked artifact. Falsified twice: a wrong count is named, and rewording the sentence away fails as an
  unparseable claim rather than passing by absence.
  ⚠ **The row's "`ci.yml` agrees with the table" is WRONG** — `ci.yml:184-185` quotes EDG-01-era jar counts as
  dated history, correct for when written. Left alone. Original row follows.
  - **P3** · **`BUNDLE-MODULE-COUNT-COMMENT-1`** — `tools/bundle-modules.mjs:77` says "Personal 2, Standard
    10, Enterprise 11" while its own table yields 2/11/12 (and `ci.yml` agrees with the table). Fix: correct
    the comment or make `tools/check-sbom-modules.mjs` assert the stated counts.
- ~~**P3** · **`SFTP-RESUME-CHECKSUM-1`**~~ ✅ **SHIPPED 2026-09-17.** Confirmed at
  `SftpConnector.java:166` (line drifted from the row's 155-179). On a size match, `fetchTo` now re-fetches
  the remote to a sibling `.resume-verify` temp file and compares SHA-256 (via the module's existing
  `Checksums`) against the local file before accepting it; a mismatch falls through to a full re-fetch. New
  test `fetchToRefusesAnEqualSizeButTruncatedThenReplacedRemote`. `SftpConnectorTest` 23/23 passed.
  - **P3** · **`SFTP-RESUME-CHECKSUM-1` (original row)** — a resumed fetch treats "local size == remote
    length" as complete (`SftpConnector.java:155-179`) with no checksum, so a truncated-then-replaced remote
    file of equal size is accepted. Fix: verify with the module's existing `Checksums` on resume.
- ~~**P3** · **`DESIGN-TOKEN-SCOPE-LAYOUT-1`**~~ ✅ **SHIPPED 2026-09-17 — and the blind spot was MEASURED, not
  inferred.** With a hex colour planted in `layout.component.scss` and an `rgba()` in `navigation-data.ts`,
  both TRACKED files, the OLD `ROOTS` reported **green, exit 0**. The new scope names both.
  ✅ **Scope is `src/app`, not `src/app/layout`, and not a deny-list** — justified from the guard's own header,
  which already defined its subject as "inspecto-authored source" and described its exclusions AS exclusions.
  Every excluded tree sits OUTSIDE `src/app` (`src/@gamma/**` is a sibling; `src/app/modules/auth` no longer
  exists), so the parent scope states the intent exactly and an empty deny-list would be speculative.
  Measured 0 violations in `layout`, 0 in `core`, 0 overall — widening cost nothing, so nothing was narrowed.
  ⚠ `src/styles/splash-screen.css` (3 hex values) stays OUT deliberately: it paints the pre-boot splash, before
  any `--gamma-*` var exists to read. Original row follows.
  - ~~**P3** · **`DESIGN-TOKEN-SCOPE-LAYOUT-1`**~~ ✅ **CLOSED 2026-09-17 — RANK DRIFT, already shipped.**
  `check-design-tokens.mjs:31` already reads `ROOTS = ['src/app']`, widened in `edf6a8c0` earlier the same
  day — **wider than this row asked**: scoping the parent covers `layout/**` AND `core/**`, and makes
  in-scope the DEFAULT, so a new directory under `src/app` cannot be silently unguarded again. Zero edits.
  ✅ **The green was not taken at face value** — because a guard's scope is a silent exemption, it was
  mutation-probed: an injected `#abcdef` in `layout.component.scss` drives the guard to **exit 1** naming
  the file, so it genuinely reaches the shell rather than merely reporting absence. Probe reverted.

  - **P3** · **`DESIGN-TOKEN-SCOPE-LAYOUT-1` (original row)** — `inspecto-ui/tools/check-design-tokens.mjs` scans
    `src/app/inspecto` and `src/app/modules/admin` only, so `src/app/layout/**` (the shell every user sees)
    may hardcode colours unguarded; zero violations today. Fix: add `src/app/layout` to `ROOTS`.
- **P3** · **`API-DEAD-METHODS-1`** — five exported service methods have no caller in the SPA:
  `access.service.ts:128` `deleteProfile`, `collectors.service.ts:41` `notify`, `config.service.ts:180,
  187, 203` `previewParsing`/`previewSchema`/`previewEnrichment`. The three previews look like an intended
  feature that never got a pane. Fix: a product call — wire or delete; not a mechanical delete.
- ~~**P3** · **`SIGN-IN-NO-SPEC-1`**~~ ✅ **CLOSED 2026-09-17 — and the row named the WRONG FILE.**
  🔴 The **authorize** and **mock-code** branches are not in `sign-in.component.ts` at all: that component's
  `signIn()` delegates unconditionally, and the branch is `SessionService.beginLogin`
  (`session.service.ts:189`). Both were **untested ANYWHERE** — the only prior `beginLogin` reference in any
  spec was a `vi.fn()` call-count assert. ⛔ Writing the spec where the row pointed would have tested a
  delegation and left the branches uncovered.
  ⚠ **The `mockAuthMode` dev switch named in an older javadoc DOES NOT EXIST** and was deliberately not
  tested — `session.service.ts:64` documents its absence; `auth.mock` arrives on the `/bootstrap` payload.
  ✅ Added `sign-in.component.spec.ts` (5 tests: the `ngOnInit` bounce, the one-shot `signInFailed` flag, the
  busy latch) and 2 tests in `session.service.spec.ts` for the authorize URL (**`code_challenge_method=S256`
  asserted**, challenge ≠ stored verifier, state matching storage) and the mock branch. **Both
  mutation-tested** — disabling the `removeItem` and forcing `oidc?.mock` false each fail the right test.
  ⚠ Unit specs only; the sign-in screen has still never been driven in a browser. UI 3012/0 (5 skipped),
  eslint 0, typecheck clean.

  - **P3** · **`SIGN-IN-NO-SPEC-1` (original row)** — `modules/admin/session/sign-in.component.ts` has an a11y spec only;
  no behaviour spec covers the authorize/mock-code branches. Fix: add one.
- ~~**P3** · **`JOBRUN-STORE-SWALLOWED-WRITES-1`**~~ ✅ **CLOSED 2026-09-17 — and the row's COUNT was
  REFUTED.** It claimed eight swallowed audit writes in `DbJobRunStore` plus three in `JobRunLedger`. Verified
  by reading every catch: `DbJobRunStore` has **12** `catch (SQLException)`, of which only **TWO are in write
  methods** (`recordSources:126`, `record:190`); **six are READ queries** returning an empty list or 0
  (`:145`, `:165`, `:228`, `:268`, `:287`, `:309`), `initSchema:101` and `prune:346` **throw**, `:326` is
  CHECKPOINT/VACUUM and `:363` is a read. And `JobRunLedger:105/131/156` are
  `catch (RuntimeException ignore) /* skip a malformed row */` on the **READ** path. ⇒ **3 real write
  swallows, not 11 — and only ONE of them is an audit.** ⛔ The row counted read swallows as audit writes.
  ✅ **Resolved BOTH ways the row offered, applied to the right sites.** The two `DbJobRunStore` projection
  writes stay **log-only BY DECISION** — the `file_stages` best-effort-index precedent: its own javadoc says
  *“the CSV audit is the record”*, `jobs_runs.csv` is what `lastStartTimes`/`lastSuccessTime` read back as the
  misfire baseline, a missed projection row is a reporting gap a re-projection repairs, and no caller could
  act on a throw. `JobRunLedger.record:71` — which **is** the record of truth — now emits `audit.write_failed`
  at ERROR via the new `AuditWriteSignal` on the existing Signal bus, reaching `/signals` and any Alert Rule.
  ⛔ **Deliberately does NOT throw**: the run has already completed, and failing it would turn an audit
  hiccup into a failed job.
  ⚠ **`StoreHealth` was considered and REJECTED, reason recorded in the class doc so nobody re-litigates it:**
  it is per-OPEN and one entry per family REPLACES the last, so a per-write failure would be overwritten by
  the next success or falsely pin the family DEGRADED — and under `-Dinspecto.topology=partitioned`
  `StoreHealth.record` **throws**, the exact outcome this row forbids.
  `DbJobRunStoreTest` 7/7, `JobRunLedgerAuditSignalTest` 2/2. → `okf/backend/engine/db-layer.md` §3.5

  - **P3** · **`JOBRUN-STORE-SWALLOWED-WRITES-1`** — `DbJobRunStore.java:126-309` (eight sites) and
  `JobRunLedger.java:71,107,133` log-and-swallow `SQLException` on audit writes with no signal, so an
  audit gap is invisible. Fix: emit a metric/signal on write failure, or record a design decision that
  best-effort is acceptable and close.
- **P3** · **`AUDIT-LOG-UNBOUNDED-READ-1`** — `JobRunLedger.java:98,124,149`, `PartitionCompactor.java:163`,
  `ReferenceCompactor.java:292`, `RunArtifactStore.java:60`, `RunLogStore.java:50` and `CommitLog.java:85`
  read whole journal files into memory on every read with no cap. Demand-gated: stream or tail once a
  file is measured to matter.
- ~~**P3** · **`AUDIT-AUTH-DUPLICATE-ROW-1`**~~ ✅ **FIXED 2026-09-17.** Confirmed exactly as filed: the
  generic interceptor (`ControlApi.routeDispatch:790` → `AuditTrail.record`) classified
  `POST /auth/{exchange,refresh,logout}` as `auth.created` / `data_mutation` **beside** the typed
  `authentication` row, so every successful sign-in, refresh and sign-out wrote **TWO auditor-facing rows**.
  `AuditTrail.classify` now skips `/auth/`; the surviving row is the TYPED one (better classification, and
  the one `compliance/evidence/audit-log-extraction.md:26-27` already describes).
  ✅ **Checked before deleting, not after:** a repo-wide grep for `auth.created` found **zero** references in
  code, tests, docs or compliance, and no report asserted the doubled number. ⚠ **Refusals were never
  doubled** — a 401 throws before `record` is reached — so the over-count was exactly one extra row per
  SUCCESSFUL session event. `AuditTrailTest` 4/4 + a no-`auth.created` assertion in
  `ControlApiAuthSessionV1Test` 8/8. → `okf/capabilities/observability/observability.md` §Layer 3

  - **P3** · **`AUDIT-AUTH-DUPLICATE-ROW-1` (original row)** — the generic `AuditTrail.record` interceptor also classifies
  the auth routes as `auth.created <route>` (`data_mutation`) beside the typed `auth.exchange`/`auth.refresh`/
  `auth.logout` rows added 2026-09-17, so every sign-in writes two rows. Fix: teach `AuditTrail.classify`
  to skip `/auth/*`.
- ~~**P3** · **`MAIL-RELEASE-NOTES-STATUS-1`**~~ ✅ **SHIPPED 2026-09-17 — and the note was wrong a SECOND way
  the row did not name.** It attributed the SUCCESS-with-nothing-sent behaviour to *no recipients*, which is a
  different branch that returns **FAILED** and was untouched by `ad29e683`. Both halves corrected.
  ⚠ `mail.send` has three outcomes: `ok` on delivery, **SKIPPED** with no channel configured (deliberately not
  FAILED, so a transport-less deployment is inert rather than red on every fire), **FAILED** when `to` resolves
  to no addresses. The pending-MAJOR release notes live in `okf/backend/control-plane/api-stability.md`; there
  is no `RELEASE_NOTES`/`CHANGELOG` file in the repo. Original row follows.
  - **P3** · **`MAIL-RELEASE-NOTES-STATUS-1`** — the pending-MAJOR release notes still describe `mail.send`
    as returning SUCCESS with no channel; it returns `SKIPPED` since `ad29e683`. Fix: correct the note.
- ~~**P3** · **`NAV-MOCK-COMMENT-STALE-1`**~~ ✅ **SHIPPED 2026-09-17 — held for TWO of its three sites and
  REFUTED for the third.** The mock really is gone (no `navigation/api.ts`, no `MockApi` anywhere), and the two
  stale attributions in `navigation-data.ts` are fixed.
  🔴 **`navigation.service.ts:91` is NOT stale** — it already records the mock's removal and is the only place
  explaining why the sidebar is built client-side. The row's instruction to delete it would have removed the
  explanation. ⚠ Only the attribution was rewritten in the other two: both comments also carry still-true
  reasons (why the divider exists; why each layout gets its own array). Original row follows.
  - **P3** · **`NAV-MOCK-COMMENT-STALE-1`** — `core/navigation/navigation-data.ts:7,302` and
    `navigation.service.ts:91` still describe a mock navigation API removed at the shell re-plumb. Fix:
    delete the comments when the file is next touched.

### Filed from the 17-spec consolidation, 2026-09-09 (Sprint 2)

These four are the **classes** behind roughly half of the consolidation's findings. Each is one guard, not N
fixes — that is the point, and it is Sprint 3 of `archived-documents/plans-archive/post-consolidation-sprints.md`.

- ~~**P2** · **`SPEC-DEADSEAM-1` — four declared seams with no implementation or no caller.**~~
  ✅ **CLOSED 2026-09-16 — three KEEP, one deleted. Kept for the two corrected citations at the end.**
  `ExpressionProvider` has no registration in any module; `DatasetRelation.temporalColumn` has no caller; a
  vendor-transform plugin registers **30** legacy functions through a real seam (🔴 this said "~40" until 2026-09-09; counted from `LegacyVendorFunctions`' 30 `f.put(` registrations — ⚠ and note this is a DIFFERENT set from the 23 SQL mapping functions, which is why `check-doc-counts.mjs` names its id `sql-mapping-functions` rather than the ambiguous noun) and **reaches no bundle and no
  document**; `AssistDialog` is dead code whose doc comment describes an unwired flow. Each needs a
  keep-or-delete verdict, not a build. ⛔ Demand-gated: do not "tidy" them without one, because at
  least one (the vendor plugin) may be deliberately operator-side.

  ✅ **DECIDED 2026-09-15:** **DELETE all four.** Verdicts given per seam, not as a sweep: `ExpressionProvider`
  (no registration in any module), `DatasetRelation.temporalColumn` (no caller, and ⛔ it *cannot
  safely gain one on a write path*, so its natural use is already ruled out), the **30-function
  legacy vendor plugin**, and `AssistDialog` (dead code whose doc comment describes an unwired flow).
  🔴 **Ground the vendor plugin BEFORE removing it — it is the one irreversible choice of the four.**
  This row's own suspicion is that it may be *deliberately operator-side*: a real, working seam that
  reaches no bundle and no document can be shipped out-of-band rather than dead. ⇒ grep for
  out-of-repo references and establish why it was added; ⛔ if that turns up an operator-side
  purpose, stop and re-ask rather than proceeding on the standing verdict.
  ⚠ The `MOCK-DEAD-COMPUTE-1` precedent — closed as a **RETAIN** because dead code here was a
  deliberate test vehicle — is why the other three are also confirmed individually, not assumed.
  ⚠ Counting note that must survive the deletion: the vendor plugin's **30** functions are a
  DIFFERENT set from the 23 SQL mapping functions, which is why `check-doc-counts.mjs` names its id
  `sql-mapping-functions` and not the ambiguous noun.

  🔴 **GROUNDED 2026-09-15 — the verdict does NOT hold: THREE of the four are ALIVE. Only
  `AssistDialog` was deleted.** The row's own gate (“ground the vendor plugin before removing it”) is
  what caught the worst of it, and the same check then refuted two more.
  ⛔ **`LegacyVendorFunctions` — “reaches no bundle and no document” is FLATLY WRONG.** It is registered
  through `META-INF/services/com.gamma.asn.plugin.TransformFunctionProvider`, discovered by
  `FunctionRegistry` via `ServiceLoader`, and **called by real tx configs** — `ccnEventType(...)` and
  siblings appear in three `*_tx.json` under the `rtdms/mtna` tree.
  ⚠ **Those configs are UNTRACKED** — `asn-parser/.gitignore:12` ignores `config/`, so they are operator
  data present in a working copy, not shipped repo content (the citation guard caught me quoting their
  paths as if they were). ⇒ This does not weaken the verdict, it **confirms the row's own suspicion**:
  the plugin really is *deliberately operator-side*, which is the case the row said to stop for.
  It is documented as **the canonical worked example** of the plugin SPI in `asn-parser/docs/PLUGIN_GUIDE.md`
  (also `asn-decoders/README.md`, `CONFIG_REFERENCE.md`), and `.github/workflows/ci.yml` names
  `asn-plugin-vendors` in its test coverage. ~~Deleting it breaks `RTDMS_ASN_Test` and the asn-golden parity
  run.~~ ⛔ **RETRACTED — see the correction below and on `RTDMS-ASN-HARNESS-1`: `RTDMS_ASN_Test` is compiled
  by NO module** (`asn-parser/` has no `pom.xml`; no `testSourceDirectory` exists anywhere in the repo), so
  deleting anything cannot "break" it. The real automated link is `asn-golden`'s runtime-scope dependency plus
  `LegacyVendorFunctionsTest`'s own `@Test`s. ⇒ It is not operator-side and not dead — it is **in-repo
  load-bearing**, on that evidence rather than this sentence's.
  ⛔ **`ExpressionProvider` — deleting the interface deletes the expression engine.** `BuiltinExpressions`
  implements it, `ExpressionRegistry.withBuiltins()` registers it, and `ParameterResolver`/`JobService`/
  `JobPackManager` consume it (the last via `ServiceLoader` for Job Packs). ⚠ What is actually unused is
  narrower and worth stating precisely: **no `META-INF/services` file registers an EXTERNAL provider**. The
  dead thing is the third-party extensibility, not the seam.
  ⛔ **`DatasetRelation.temporalColumn` — an ACTIVE plan depends on it.**
  `archived-documents/plans-archive/dataset-column-derivation-plan.md` cites it as a design constraint (its §2.3 turns on
  `temporalColumn` throwing on two temporal columns), **already records that it has zero production
  callers**, and carries an operator decision of 2026-09-14 (Q2, temporal tie-break) built on that
  behaviour. ⚠ It also has a live javadoc cross-reference from `SinkPartitions.java:30`. ⇒ It is
  **unwired, not dead** — the same shape as `DERIVED-SCHEMA-PANEL-ORPHAN-1`.
  ✅ **`AssistDialog` DELETED** — the only one that survived checking: no caller, selector
  `app-assist-dialog` used nowhere, named only in the never-maintained archive tier. ⚠ Its
  `AssistPanelComponent` is alive with three other consumers (`assist.component`, `node-detail.dialog`,
  `diagnosis-detail.dialog`), so removing this host orphaned nothing.
  ⇒ **The row needs re-deciding, not re-running.** “Four dead seams” was true of one. The three
  survivors are three DIFFERENT questions: retire an unused extension point (Expression), keep or wire an
  unwired reader (temporal), and document a live-but-undocumented plugin (vendor).

  ~~⛔ **BLOCKED on the operator (2026-09-15 §4 pass)**~~ ✅ **ALL THREE VERDICTS IN — ROW CLOSED
  2026-09-16. KEEP ALL THREE SURVIVORS; NO CODE CHANGES.** (1) `ExpressionProvider` **KEEP the SPI**;
  (2) `DatasetRelation.temporalColumn` **KEEP unwired** (answered earlier the same day); (3)
  `LegacyVendorFunctions` **KEEP, and it is already documented** — nothing to write.
  ⇒ Of four seams the standing verdict said to DELETE, **exactly one was deletable**, and it was deleted.
  🔴 **The lesson is not "three were alive" — it is that the ROW'S OWN EVIDENCE was wrong twice while
  reaching the right conclusion**, and both errors are corrected here rather than left to be re-cited:
  - ⛔ **"Deleting it breaks `RTDMS_ASN_Test`" is FALSE.** `asn-parser/src/test/java/com/gamma/skybase/
    decoder/asn2/RTDMS_ASN_Test.java` has **no `@Test` method at all** — it is a `main()` harness with
    hardcoded `/home/gamma/...` paths whose only uncommented call, `pgwParse()`, never builds a
    `Transformer` and never touches `LegacyVendorFunctions`. The real automated links are `asn-golden`'s
    **runtime-scope dependency** (`asn-parser/asn-decoders/asn-golden/pom.xml:43-47`) and
    `LegacyVendorFunctionsTest`'s own `@Test`s, including `discoveredViaServiceLoader`, which asserts the
    `META-INF/services` entry resolves. ⚠ That test is plain JUnit, **not** environment-gated — but it runs
    in **CI's 26-module coverage build**, not in the 14-non-asn-module local baseline, so a green local
    gate proves nothing about it. → the dead harness is now `RTDMS-ASN-HARNESS-1` below.
  - ⚠ **`ExpressionProvider`'s SPI is not a speculative third-party hook — it is the Job Pack seam.**
    `ServiceLoader.load(ExpressionProvider.class)` runs at `JobService.java:562` (base classpath, finds
    none by design) **and at `JobPackManager.java:209`, registering per-pack at `:243` through an isolated
    classloader**; `JobPackManagerTest.java:421` compiles a synthetic provider into a fake pack jar to
    prove that path end to end. ⇒ **zero in-repo registrants is the DESIGNED state of a hot-deploy seam.**
    ⛔ Do not re-file this as a dead SPI — "no `META-INF/services` file in the repo" is the expected
    reading, not a finding. `BuiltinExpressions` never depended on ServiceLoader anyway: it is registered
    by direct call at `ExpressionRegistry.java:34-35`.
  ⚠ **Offered and NOT taken** (no build was authorised, and neither pays for itself today): a test pinning
  that the base build discovers zero providers, so `JobService.java:561`'s comment stops being an unchecked
  claim; and dropping that base-classpath loop, which would cut a real capability — a bundled edition module
  could ship a provider through it — for no measured gain.

- **P3** · **`RTDMS-ASN-HARNESS-1` — `RTDMS_ASN_Test` is a dead manual harness shaped like a test.**
  `asn-parser/src/test/java/com/gamma/skybase/decoder/asn2/RTDMS_ASN_Test.java` sits under `src/test/java`
  and is named `*Test`, but carries **no `@Test` annotation**: it is a package-private class with a
  `public static void main(String[] args)` (`:15`) dispatching to manual harness methods, **most of them
  commented out**, against hardcoded absolute `/home/gamma/...` paths that exist on no machine this team
  runs (the sandbox is win32). The one active call is `pgwParse()` (`:117`).
  🔴 **Why this is worth a row rather than a shrug: it has already caused one false citation.**
  `SPEC-DEADSEAM-1` recorded "deleting `LegacyVendorFunctions` breaks `RTDMS_ASN_Test`" as evidence the
  plugin was load-bearing — a claim that reads as an automated-test dependency and is not one. The name
  did the lying; anything under `src/test/java` called `*Test` is assumed to run.
  **Verdict needed, not a build** — and unlike the `SPEC-DEADSEAM-1` survivors this one is genuinely
  low-stakes: (a) delete it; (b) rename to `*Harness` / move out of `src/test/java` so the name stops
  claiming coverage; (c) keep verbatim as an operator-side reproduction aid, documented as such.
  ⚠ **Ground it before deleting** — the same gate that saved the vendor plugin. Its commented-out methods
  build a `Transformer(txConf)` over the **untracked** `asn-parser/config/rtdms/mtna` tx configs
  (`.gitignore:12`), so it may be a deliberate operator-side harness, which is precisely the case that
  refuted three of four deletions last time. ⛔ Do not sweep it in with a tidy-up.
  ✅ **GROUNDED 2026-09-17 (no files changed — the verdict is still owed). The row UNDERSTATES it: the file is
  not "a dead harness on the test-compile path", it is on NO path.**
  🔴 **`asn-parser/` has no `pom.xml`** (deleted 2026-07-31 — `asn-decoders/README.md:198-200`). `legacy-code/pom.xml:58`
  rescues the main half via `<sourceDirectory>../../src/main/java</sourceDirectory>`, and **no `testSourceDirectory`
  exists anywhere in the repo**. ⇒ **all 21 tracked files under `asn-parser/src/test/java` are compiled by
  nothing.** Proven empirically, not from poms: `legacy-code` logs *"No sources to compile"* / *"No tests to
  run"*, has no `target/test-classes`, is the only asn module with **no `surefire-reports` directory at all**,
  and `RTDMS_ASN_Test*.class` exists nowhere in the repo. It also has **no `org.junit` import at all**.
  ⛔ **This refutes the "it keeps the module green" premise** — it cannot go red, so deleting it changes no
  build outcome. The `SPEC-DEADSEAM-1` reachability trap does NOT apply: the six `TestASNFiles` methods only it
  calls are in the same uncompiled tree.
  🔴 **The real dependency is DOCUMENTARY, and it is in compiled, shipping code.**
  `asn-golden/…/GoldenCapture.java:55-57` declares itself the durable home for tuples that *"live nowhere in
  config — only in the legacy test drivers"*, then cites `RTDMS_ASN_Test.<method>` as provenance for **7 of its
  9 golden cases**, plus `TestASNFiles.parseGMSC`. Deleting or renaming dangles **8 citations**.
  ✅ **Not a live operator aid:** `asn-parser/config/` is gitignored AND absent, `git log --follow` returns ONE
  commit (a 2026-07-30 vendored import by Gamma Dev, never touched since), and the `/home/gamma/…` paths appear
  in no other file. `pgwParse():118` even points at `asn-parser-v2`, the artifactId deleted 2026-07-31.
  ⚠ **Two row citations are off by a line or a kind:** `main` is at `:17` (`:15` is a static field), and
  `:117` is `pgwParse`'s DEFINITION — the active CALL is `:32`.
  ⬜ **RECOMMENDED: (c), widened — keep verbatim and document the TREE, not the file.** Keeping costs nothing
  (it compiles nowhere); deleting is the only irreversible option and the only one that loses information; and
  the defect is the doc gap, not the file — `asn-decoders/README.md:190-193` rescues `src/main/java` by name and
  is SILENT on `src/test/java`, which is why the filename was the only evidence available. **Five more names in
  that tree claim coverage they do not have** (`Test.java`, `ASNFileReaderTest`, `BERDecoderTest`,
  `SbinHuaMscAsnTest`, `FixedLengthFileReaderTest`), so deleting one leaves five.
  ⚠ **(b) is defensible** if a lying name outweighs resolvable provenance — but rename all five AND update
  `GoldenCapture`'s 8 comments in the same commit. **(a) delete** only with those comments rewritten to stand
  alone. ⛔ **Operator's call — do not act on this unasked.**
  ✅ The false citation this row was filed over is now **retracted at its source** (§4's `SPEC-DEADSEAM-1` row),
  not only 28 lines below it.
  ✅ **CLOSED 2026-09-17 — verdict (c): kept verbatim, class-level javadoc added** stating plainly it is a
  manual operator harness (not an automated test), that it compiles on no build path, and that it is kept as
  documented provenance for `asn-golden` GoldenCapture cases. No rename/move/delete: the tree-wide naming
  defect (five sibling files with the same lying `*Test` shape) is left for a separate operator-scoped decision,
  as recommended above — not swept in here.

- **P3** · **`CODEGRAPH-AFFECTED-UNUSABLE-1` — `codegraph affected` over-reports to uselessness; the
  in-repo half is already fixed, only the upstream defect is open.** CodeGraph was adopted 2026-09-19
  (`e4ba74a2`) and `CLAUDE.md` advertised `codegraph affected <files...>` as *"which tests a change
  touches"*. **It does not answer that.** Driven the same day on v1.6.0 against a clean tree:
  `codegraph affected inspecto-ui/src/app/inspecto/api/config.service.ts` — an **Angular TypeScript**
  service — returns **953 Java test files**, the first five of them `asn-parser` ASN.1 BER/schema
  decoder tests, which no change to an Angular service can reach; and
  `codegraph affected inspecto-sql/src/main/java/com/gamma/sql/SqlGuard.java` returns **1078** test
  files when the whole checkout contains **1059** (`find` over the indexed trees, excluding
  `node_modules`/`target`/`worktrees`). ⛔ **It fails in the direction of PASSING** — the same shape as
  the five guards found failing that way on 2026-09-16. Anyone selecting `-Dtest=` targets from it
  would quietly run the full reactor on every edit, defeating the unit-tests-per-change rule.
  ✅ **The repo-side remedy SHIPPED with this row**: `CLAUDE.md`'s CodeGraph section now strikes
  `affected` outright, and records two lesser caveats found in the same pass — `callers` reports
  importing files rather than call sites and is not exhaustive (10 files for `SqlGuard` where
  `grep -rl` finds 40), and `codegraph_explore`'s ranking is substantially lexical, so a concept-phrased
  query ranked `RowShaper.java` (methods named `validate`/`route`) above the actual gating code.
  ⬜ **What is left is NOT ours to fix**: the defect is in the third-party CLI. Remaining work is to
  report it upstream and to re-drive the two commands above on any codegraph upgrade before the
  guidance is relaxed. **Check: the two commands above stop returning cross-language and
  larger-than-the-corpus results.** ⚠ **Demand-gated on purpose** — `impact` and `query` were verified
  sound in the same pass and cover the relationship questions we actually ask, so nothing is blocked.
  ✅ What the pass CONFIRMED, so it is not re-litigated: `codegraph_explore`'s source is **byte-exact**
  against disk (diffed for `ConfigSafetyValidator.java` lines 52–58 / 70–78), so the Read-equivalence
  rule in `CLAUDE.md` is sound; and the SPA **is** indexed — a single call returned Java and TypeScript
  together, and a UI-only query returned `status-badge.component.ts` with 30 accurate callers.

### Filed from the UI consolidation, 2026-09-22

- ~~**P2**~~ · ✅ **FIXED 2026-09-23 (`d8ce162e`)** — a config's refs to other config files resolve **beside the config**, never against the CWD, through ONE resolver `PathJail.resolveConfigRef` (an ambiguous CWD-only ref is refused naming both paths). The four hand-kept copies (the loader, the 422 gate, `ConfigRoutes.resolvedPath`, `copySchemaFile`) now call it. Keys: `schema_file`, `schemas[].schema_file`, `mapping_file`, `grammar`, every `segments` value, and ASN.1 `ingester_config.grammar` (→ `Schemas.ingesterGrammar`). 31 shipped configs respelled as sibling names; `SchemaExtractor` emits bare names. Measured: **31 of 32** shipped Pipelines failed to load from a non-root CWD. Reconciled with the ASN.1 grammar-file change (`f860c414`): `Asn1GrammarSource` keeps text-wins, the `.asn`/`.asn1` check first and a single jail; preview resolves against the Space config root. Pinned by `ShippedPipelinesLoadFromAnyWorkingDirectoryTest` (falsified). ⚠ Not driven live; only half of the `inspecto-deploy/` launch is fixed → `DATA-DIRS-RESOLVE-AGAINST-CWD-1`. → `okf/backend/config/config-safety.md`. Original row: **`SCHEMA-FILE-RESOLVES-AGAINST-CWD-1` — a pipeline's `schema_file` relative path resolves against the process CWD, not the space root.** Found driving the UI against the auth-free Professional bundle launched from `inspecto-deploy/` with `-Dspaces.root=..\spaces` (`.claude/launch.json` → `inspecto-geolink*`): every demo pipeline fails to load (`ConfigRegistry` WARN "path 'spaces\demo\config\…schema.toon' … resolves to `inspecto-deploy\spaces\…`, outside the root"), so Pipelines, Runs and Processing Status render empty and the WARNs flood the Signal Ledger. ⚠ Not a UI defect — it blanks half the UI in that launch mode. Remedy hypothesis: resolve `schema_file` against the config file's own directory (the satellite convention the UI editor already assumes, SATELLITE-WRITE-1). → `archived-documents/plans-archive/ui-consolidation-plan.md` §5 `UIB-05`.
- **P2** · **`MAT-SELECT-SWEEP-1` — 23 templates still ask for a single choice with `mat-select`.** The angular-ui skill names `<inspecto-option-picker>` as the ONE single-choice control; decision D8 of the UI consolidation scoped the 2026-09-22 sweep to Geo Map, the Link Analysis dock and toolbox, and Assistant (27 pickers). The rest is mechanical: bind the picker where the select was, keep table cells, grid toolbars and genuine multi-selects as dropdowns by rule. → `okf/frontend/conventions/page-chrome.md`.
- **P3** · **`EMPTY-GRID-HSCROLL-1` — Incidents and Cases draw a horizontal scrollbar on an EMPTY grid.** Their column minimum widths exceed the pane at narrower widths, so the empty state sits over a scrollbar with nothing to scroll. Give the grid `suppressHorizontalScroll` while `rows.length === 0`, or flex-size the columns. → `okf/frontend/conventions/page-chrome.md`.
- **P3** · **`JOB-RUNS-DIALOG-DEAD-1` — `modules/admin/jobs/job-runs.dialog.ts` has no opener.** Only its own spec references `JobRunsDialog`. Decide retain-as-test-vehicle (the `MOCK-DEAD-COMPUTE-1` precedent) or delete; do not leave it looking like a live surface. → `okf/frontend/conventions/page-chrome.md`.

### Filed from the postmed_xdr pipeline build, 2026-09-22

Ten findings from building a post-mediated telecom xDR Pipeline from scratch over a local inbox and
driving the Pipelines workbench over the result — 1200 records run end to end, output reconciled
against the written Parquet. Every row's evidence, and the two apparent defects that were **refuted
by a control** rather than filed, are in the owning document.

- ~~**P2**~~ · ✅ **FIXED 2026-09-22 (`WB-09`, decision `D2`)** — `rejected_count` is split into `rejected_files` **and** `rejected_rows`, and `total_input_rows` now counts what ARRIVED (parsed + rejected), so the ledger reconciles to the file instead of to itself. 🔴 **The header had SEVEN construction/mirror sites, not the four the plan listed** — the extras (`DrainCommand` plus four test fixtures in three modules) were found by the COMPILER, which is the case for the typed `ConsignmentRow` over the string header. ⚠ **`error_rate` changes meaning deliberately:** a file that silently dropped a record used to report 0% because in and out both excluded it; alert thresholds tuned against that may now fire. ✅ The alert measure was ALREADY called `rejected_files` while reading `rejected_count` — the rename ends that mismatch too. Original row: **`INGEST-REJECT-ACCOUNTING-1` — a file that LOST a record reports `rejected_count=0`, and the input count excludes the loss.** Measured on a 20-data-row file carrying one row truncated mid-write to 9 of 18 fields: the batch audit row says `status=SUCCESS · rejected_count=0 · total_input_rows=19 · total_output_rows=19`, so the counters **reconcile (19 = 19) while a record is missing**. The drop is recorded only in `<dirs.errors>/<file>_errors.csv`, which nothing in the audit row points at, and `dirs.errors` is itself an engine-read/authoring-invisible leaf. ⚠ An operator reconciling mediation output against the store by these counters concludes nothing was lost. Decide first what `rejected_count` means — records the parser refused, or records not persisted (the errors CSV and the batch row currently answer differently) — then make the input count include the dropped row. ✅ **`D2` SIGNED 2026-09-22 — split, don't redefine:** `rejected_files` (members not `SUCCESS`) **and** `rejected_rows` (sum of `IngestResult.errorRows`), with `total_input_rows` = parsed + rejected rows. Written into `okf/backend/engine/consignment-status-flow.md` the same day; build tracked as `WB-09`. → `okf/capabilities/ingestion/ingestion.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-11` + `WB-12`)** — and 🔴 **the cause was not what this row described.** The *badge* half was already shipped (`nodeLastRun()` renders *“Last run: N row(s) · ts”* in the inspector); the finding now reads *“not yet tested in this session”*. The REAL cause: `RunToHereDialog` closed without its result, so `applyRunOutcomes` — the only code that marks nodes tested — had **never run**. Nodes read *not yet tested* even in the session that had just tested them. Original row: **`PIPELINE-NODE-TEST-STATE-STALE-1` — "not yet tested" on a Pipeline with completed runs is a RECORDED DECISION, not a defect; changing it needs `D6`.** ⚠ **Rescoped 2026-09-22 (was P2, filed as a gap).** `pipeline-editor.md:701` — *"the editor claims no validation it never ran"*; `:693` — *"never a second readiness opinion"*; and the test lane omits provenance **as its safety property** (`pipeline-test-run.md:43`). The measurement stands (four nodes read *not yet tested* over three completed runs and 1219 rows), and it still costs trust — a signal that is wrong for every Pipeline in production is a signal readers learn to ignore. But the fix overturns two written decisions and can only ever join the *operate* lane's `/provenance`. ✅ **`D6` SIGNED 2026-09-22 — NOT into `tested`:** a distinct *ran* badge from `lastRunCounts` plus a reworded finding (*“not yet tested in this session”*), so the two written decisions stand and the badge is a third source, not a second opinion. Written beside `pipeline-editor.md:693/701` the same day; build tracked as `WB-11`. → `okf/frontend/features/pipeline-editor.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-12`)** — the run's own rows seed the tab's sample thread, so *“Use the captured sample”* is one click after a test run. 🔴 Fixing it exposed a DEAD SEAM: the dialog's Close button carried no value, so `afterClosed()` always emitted `undefined` and every consumer of the run result was unreachable. Original row: **`DRYRUN-SEEDS-AFTER-PARSE-1` — the dry-run PANEL and the test run are two test instruments that do not hand off.** ⚠ **Rescoped 2026-09-22 (was P2, "cannot exercise the parse stage") — that claim was WRONG.** The parse stage IS testable in the builder: *Run to here* (`POST …/run?to=`) copies picked inbox files into a jailed scratch root and runs the **real frontend** — measured over 24 shipped Pipelines across six frontends (400 rows delimited, 5 JSON, 5 Excel, 3 fixed-width, 7 text_regex, 3 BER). v1 drove only the dry-run panel, which seeds *after* the parse **by design**. What remains: the panel that shows per-node samples still starts from hand-typed JSON; *"Use the captured sample"* appears only once a Parse-drawer thread exists (`pipeline-editor.md:593`), two hops away and invisible from the panel. Let the panel seed from the last test run or a picked inbox file. → `okf/frontend/features/pipeline-editor.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-10`)** — one errors row per bad LINE, naming the offending columns as a range, with the raw line once. 🔴 **This was `WB-09`'s PREREQUISITE, which the plan had backwards:** `writeRejects` counted once per offending COLUMN and that count IS `IngestResult.errorRows()`, so `rejected_rows` would have shipped inflated 9× in the measured case had this landed second. Original row: **`INGEST-ERRORS-CSV-PER-COLUMN-1` — the errors report emits one row per missing column, each repeating the whole raw line.** One line truncated to 9 of 18 fields produced **nine** rows (`c9`…`c17`) with identical `reason` (`MISSING COLUMNS`) and identical `raw_line`. A file with many short rows yields an errors CSV that is mostly duplicated payload. Emit one row per bad line naming the column range. → `okf/capabilities/ingestion/ingestion.md`.
- ~~**P3**~~ · ✅ **CLOSED 2026-09-23 (`83e78256`)** — the switch now reads **“Marker dedup (file)”** and its help names the record-grain alternative, the **Dedup (record)** Step (`transform.dedup`). Changed at the one server source `NodeAttributes.MARKER_DEDUP` and its UI mirror `MARKER_DEDUP_ATTRIBUTES`, contracts regenerated; pinned by `pipeline-collection-definition.component.spec.ts` (mutation-tested). ⚠ **Unit-tested only — not driven in the preview.** Original row: **`DUPLICATE-CHECK-GRAIN-UNSTATED-1` — the grain is documented four times; only the pane's label is silent.** ⚠ **Rescoped 2026-09-22 (was "state the grain in the ingestion concept" — it already is).** `step-catalog.md:171` — *"Not file dedup: that is the Collector's `duplicate:` policy / marker `duplicate_check`, a Guarantee that rides the Collector"*; `:447`; `pipeline-editor.md:270,272`. Measured: an exact duplicate record passed through with `duplicate_check.enabled: true`, correctly. The fix is help text beside the toggle naming the grain and the alternative (`transform.dedup`, which needs `output_store:`). → `okf/frontend/features/pipeline-editor.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-14`, decision `D8`)** — with a node selected an added Step is wired `sel → new → old`, rewiring the selection's outgoing **`data`** edge only. ⛔ Nothing selected → the bare add stands: guessing an anchor is how an “add” silently rewires a graph the author was not editing. Original row: **`PALETTE-ADD-DROPS-ORPHAN-1` — a Step added from the palette lands DISCONNECTED, and there is no one-gesture insert anywhere.** ⚠ **Re-edited 2026-09-22, twice.** The first rescope (same day) said *"the Recipe view already has insert-between; the ask is canvas parity"* — that rested on `pipeline-editor.md:550`, which is **stale**: `<app-pipeline-step-cards>` was deleted in `6d3c68fa` (2026-09-18) and `inspecto-ui/src` has no Recipe view. Measured: `insertNode` (`pipeline-editor.component.ts:2511-2517`) is a bare `addNodeToModel`; selection is unused; with `Record Transformer` selected, *Add Row filter* dropped `transform_filter_1` unconnected. Validation caught it (*"has no input connection."*) — fails safe — but inserting mid-chain is three graph operations. The decline recorded at `pipeline-editor.md:336` assumed an alternative that no longer exists, so it was reopened as `D8`. ✅ **`D8` SIGNED 2026-09-22 — reopen narrowly:** wire an added Step after the **selected** node only (rewiring its outgoing `data` edge); bare add when nothing is selected. The decline is amended in place at `pipeline-editor.md:336`; build tracked as `WB-14`. → `archived-documents/plans-archive/workbench-trust-plan.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-18`, decision `D9`)** — `GET …/graph` now carries `readOnlyProjection: true` and `links.roundTrip`. ⚠ The link is derived from the REQUEST path, because the route is also reached under `/spaces/{id}` and a hardcoded path would point a space-scoped caller outside its own space. Original row: **`GRAPH-READ-SHAPE-NOT-WRITE-SHAPE-1` — `/graph` is the read-only projection by DELIBERATE design; the ask is naming, not a fix.** ⚠ **Rescoped 2026-09-22.** `pipeline-authoring.md:173` — *"The round-trip seam is `toMap` and `lower`, not `lift`"*; `:550` — *"do not cite it as the round trip"*; the authoring pair is `GET …/graph/raw` + `PUT …/graph` (`editable-round-trip.md:122`). Measured across **26 of 26** shipped Pipelines: PUTting the `/graph` body back is refused 422 (`NO_PERSISTENT_SINK` + `PARSER_NO_SCHEMA`, or `JOIN_REFERENCE_MISSING`), always with `written:false`. It fails closed everywhere; it is also the natural wrong guess for any client, and nothing at the route says so. ✅ **`D9` SIGNED 2026-09-22 — no rename:** the `GET …/graph` response gains `links.roundTrip` to `…/graph/raw` and a `readOnlyProjection` flag, plus one sentence at the route; renaming would break every client for a gain a link provides. Written into `pipeline-authoring.md` §4/§7 the same day; build tracked as `WB-18`. → `okf/capabilities/pipeline-authoring/pipeline-authoring.md`.
- ~~**P3**~~ · ✅ **CLOSED 2026-09-22 (`WB-19` + `WB-01`)** — all three parts, none of them a writer: `D4` accepted the churn, `editable-round-trip.md` states *verbatim = the decoded map, never the bytes*, and the round-trip guard through the real `PUT …/graph` now exists (it was the missing half this row named). Original row: **`GRAPH-SAVE-REFORMATS-CONFIG-1` — a workbench save rewrites the whole TOON file, so every save is a noisy git diff.** `GET /graph/raw` → `PUT /graph` returned `written:true, findings:[]` and **preserved every key**, including the four undeclared `dirs` leaves and `description` — the passthrough contract holds. 🔴 The rewritten quoting (`delimiter: "|"` → `delimiter: |`, `comment: "#"` → `comment: #`) **looks like** the classic comment-character loss and was first read as corruption; it is not — the TOON reader parses those back identically (re-read values still `"|"`, `"#"`, `["NULL","N/A"]`) and a re-run produced the same 400/400 rows. What is genuinely lost is blank lines, column alignment, quoting, key order within `collector`, and the trailing newline. ✅ **`D4` SIGNED 2026-09-22 — the churn is ACCEPTED** as the cost of UI authoring; a format-preserving writer is **not** funded (it would add a second drift surface). *Verbatim = the decoded map, never the bytes* is now stated in `okf/backend/pipeline-graph/editable-round-trip.md` beside the standing gate. ⚠ The round-trip guard that would catch a real regression here (assert the key set AND the re-read values) **does not exist**. → `superpower/postmed-xdr-pipeline-build.md`.
- ~~**P3**~~ · ⛔ **CLOSED 2026-09-23 — REFUSED, and `D3` reversed with it, because the premise was false.** Counted on disk: `config/orders/` holds **three** Pipelines and the directory names are DOMAIN words, not ids (`config/postmed/` → `postmed_xdr`). The shipped convention is a per-domain directory an author chose, so `config/<id>/` would have added a THIRD layout — the opposite of this row's goal of picking one. ✅ What the row cared about already holds: a created Pipeline and its satellites land TOGETHER, and `subdir:` gives an author the domain home when they want it. ✅ The attempt still paid for itself — its read-path fix SHIPPED (below) and stands on its own. ✅ A config in `config/<id>/` is now reachable to read/patch/delete **without being registered** (`ConfigFileSupport.resolveConfigFile` falls back to the subdirectory; flat forms still win; falsification-probed). That was the blocker the first attempt hit. 🔴 **The second attempt found the real one:** a Pipeline's bare `schema_file: <name>.toon` resolves BESIDE ITS OWN CONFIG, so nesting the Pipeline while its schema is written flat separates them and the save warns its own schema does not resolve. `D3` says *“satellites get the subdir too”* and **nothing has scoped that work** — a `/config/write` for a schema with no `subdir:` still lands flat. Do the satellite half first, then re-land the redirect. Original row: **`UI-CREATED-PIPELINE-FLAT-HOME-1` — a Pipeline created in the workbench lands flat at the space config root.** *New pipeline* wrote `spaces/demo/config/postmed_suspense_pipeline.toon`, while all eight existing Pipelines in that space live in a per-Pipeline directory (`config/postmed/`, `config/orders/`, `config/roaming/`…), and a `<name>_schema.toon` sibling would land at the root too. The scaffold itself is good — `active: false`, `id` stamped, all nine `dirs` leaves derived from the name, `duplicate_check` on, `parsing.frontend` from the chosen format. ✅ **`D3` SIGNED 2026-09-22 — `config/<id>/`, chosen server-side in the write handler** (the client sends no path), with every SATELLITE-WRITE-1 call site passing the same subdir. Written into `pipeline-authoring.md` §4 and `pipeline-editor.md` the same day; build tracked as `WB-15`. → `okf/capabilities/ingestion/ingestion.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-13`)** — the palette takes a `readOnly` input and both add-button families render disabled (and undraggable) in the read-only lens, with a spec asserting zero enabled controls. Original row: **`READONLY-LENS-PALETTE-ENABLED-1` — the View lens renders 36 enabled "Add …" controls that do nothing.** In the read-only lens every palette add button reports `disabled:false`. ✅ The handler guard holds — clicking one mutated nothing (no dirty flag, no node), so this is presentation rather than integrity — but a keyboard or screen-reader user gets no disabled cue for 36 controls. Disable them in the read-only lens. → `okf/capabilities/ingestion/ingestion.md`.
### Filed from the first domain demo, 2026-09-23

- ~~**P2**~~ · ✅ **FIXED 2026-09-23** — and 🔴 **it was NEITHER of the two causes this row named.** The write was never duplicated and the lineage never over-counted: the test run was **not routing at all**. Branch↔sink pairing is by the branch's declared `database`; `PipelineConfig.forScratchRun` re-rooted the `sinks[]` under the scratch root and left `route.branches[].database` naming production directories, so `PipelineLift.branchKeyForDatabase` paired nothing, emitted a plain data edge per destination, and the run **degraded to a fan-out — every sink received every row**. Hence exactly rows × branches. ⚠ **The counts were the symptom; the CONTENT was the damage** — a builder testing a route saw the whole feed under every branch with nothing to signal the routing had not run. Fix: `forScratchRun` re-roots the branch databases through the same map as the sinks (`scratchRoute`), pinned by `RouteIngestEndToEndTest.aTestRunOfARoutedPipelineRoutesRatherThanFanningOut`, which asserts per-destination CONTENT — a fan-out and a correct route agree on `outputs().size()`, so a shape-only assertion passes against both. Falsified: reverting one line turns it red (6 vs 3). ✅ **The preview half of this row has the SAME single cause**, confirmed by arithmetic: `route_step`'s domestic branch (`REGION IN ('NORTH','SOUTH')`) matches 4 of the 9 rows and other matches 5, so routing a **fanned-out 18-row sample** yields exactly the 8 + 10 this row reported the graph preview showing. The preview was not a second defect — it was faithfully routing a doubled sample. **The production ingest lane was always correct** — on-disk `route_step` outputs and its lineage CSV both sum to 9; only the workbench test run lied. Original row: **`ROUTED-WRITE-COUNTS-PER-BRANCH-1` — a routed Pipeline writes/reports each row ONCE PER BRANCH.** Measured on two independent configs, one of them SHIPPED: `route_step` (2 branches) turns **9 input rows into `output.rowCount` 18**, and the new `premed_events` (3 branches) turns **12 into 36**. The factor is exactly the branch count in both. ⚠ `output.rowCount` is the ingest's own lineage sum, not a preview artefact — the graph preview then splits the inflated figure (`route_step`: domestic 8 + other 10 = 18 over 9 real rows), so the per-branch counts a builder reads are wrong too. 🔴 **The first thing to establish is which it is:** the lineage over-counting one physical write per branch, or each branch sink physically receiving EVERY row (true duplication, and a data-loss-shaped defect in the other direction). Both fit the numbers; they are very different bugs. ⚠ Found by building a domain demo for `route`, not by the sweep — the sweep read row counts without an independent expectation to compare them against, which is exactly what a hand-authored corpus provides. → `archived-documents/plans-archive/workbench-trust-plan.md`.

- ~~**P2**~~ · ✅ **FIXED 2026-09-23 (`b1da53ca`)** — **`output:` is the default layer for every `sinks[]` entry** (operator decision 2026-09-23). `format`, `compression`, `ducklake` and `filename_column` resolve **entry → `output.*` → hard default**; `PipelineEditable.lower` preserves `output:` verbatim while a plural `sinks:` block exists (so a no-edit save no longer rebuilds it from the primary sink); `RecipeConverter` takes the shorthand keys from its sinks entry. Both red sweeps (`LiftLowerFixtureSweepTest`, `RecipeConverterTest`) are green; etl 547 / engine 1746 / processor 1122, 0 failures. ⚠ **This row's “writes all three uncompressed” was inexact:** a null compression passes NO `COMPRESSION` option and DuckDB's parquet default is snappy, so `premed_events` wrote snappy **by coincidence** — the defect was real for any other codec (`ConsignmentIngestorSinksTest` asserts zstd read back from disk). `da71cb4c` then let `in_recharges` declare snappy through `output:`. Original row: **`SINKS-ENTRY-IGNORES-OUTPUT-DEFAULTS-1` — a `sinks[]` entry takes NOTHING from `output:`, so an authored `compression:` is silently dropped, and master is RED on it.** `PipelineConfigParser` builds each entry from its own columns alone — `compression` from `sink.get("compression")` (null when the group has no such column) and `format` from `getOrDefault("format", "CSV")`, a hard default that is **not** `output.format`. `PipelineConfig.resolveSinks` then uses the declared list verbatim; only the no-`sinks[]` shorthand reads `output.*`. ⇒ the SHIPPED `premed_events` declares `output.compression: snappy` above three `sinks[3]{database,format}` destinations and **writes all three uncompressed** — config written but never read. 🔴 **It is already failing on master** (committed `7e20aafe`, verified 2026-09-23 by stashing every local edit): `LiftLowerFixtureSweepTest` (1 of 28 fixtures — lowering rebuilds `output:` from the primary sink, whose compression is null, so the authored `snappy` is LOST on a no-edit save) and `RecipeConverterTest` (the projection stamps `compression=snappy` onto each per-branch sink instead). ⚠ **The fix needs a DECISION first, not a patch**: either `output:` is the default layer for `sinks[]` entries (then the lift must not re-emit the inherited value per sink, or every save rewrites the file) or it is meaningless beside a `sinks[]` block (then the parser should REFUSE the combination rather than accept and ignore it) — a round-trip that merely preserves bytes would leave the write path still ignoring `snappy`. → `okf/backend/engine/output-sinks.md`.

Six rows filed 2026-09-23 by the two lanes that built the remaining domain demos (`gl_journal`, `stock_movements`, `msc_cdr`, `in_recharges`). ⚠ **Reported by the demo lanes, not yet re-grounded** — each cause below is the lane's hypothesis, not a verified diagnosis; reproduce before building against it.

- ~~**P2**~~ · ✅ **CLOSED 2026-09-23 (`36b83806`)** — operator decision: seed with the **RAW parsed rows**. `PipelineTestRun` captures the parser's relation per segment through `DataTransformer.RAW_INPUT` (a `ScopedValue` checked in `materialize`; unbound in production) before mapping; `sampleRowsBySegment` removed. `in_recharges`, `msc_cdr` and `premed_events` now match a real ingest in counts AND values (`ControlApiPipelineTestRunDemoTest`, 3, real HTTP). Original row: **`TESTRUN-SEED-IS-MAPPED-OUTPUT-1` — the test run seeds `parse`'s output with rows that have ALREADY been mapped, so the mappers run twice.** Found **independently by both demo lanes.** `PipelineTestRun.sampleRowsBySegment` reads back the rows the ingest WROTE (post-mapping, canonical columns) and `PipelineGraphRoutes.testRun` hands them to `PipelineDryRun.runSeeded` as the parse node's output, so `map` / `map_<segment>` re-apply their expressions to columns that are already canonical. **Loud:** `in_recharges` (`AMOUNT_MINOR`), `msc_cdr` (`EVENT_TIME`) and `gl_journal` (`POSTING_SERIAL`) refuse 422 with a binder error, while `?to=parse` succeeds — so the test run **cannot reach route or sinks** on those Pipelines. 🔴 **Silent, and worse:** `premed_events` answers 200 with **every `EVENT_TS` NULL** in the preview while a real ingest of the same file writes all 12. ⚠ **Evidence update 2026-09-23:** `gl_journal` no longer reproduces — its `POSTING_SERIAL` column is gone after `6f2a6eb7` (Excel dates land as dates); `in_recharges`, `msc_cdr` and `premed_events` remain. Fix direction **undecided**: seed from the raw parsed relation, or seed downstream of `map` and skip it. → `okf/backend/engine/pipeline-test-run.md`.
- ~~**P2**~~ · ✅ **CLOSED 2026-09-23 (`36b83806`, same commit)** — a FAILED batch answers **422** *"the batch FAILED after N row(s) parsed: &lt;error&gt;"*; `RunToHereDialog` renders it (spec). ⚠ Before, with the raw seed, a non-mapping failure would even have returned a clean preview. Original row: **`TESTRUN-FAILED-BATCH-REPORTED-EMPTY-1` — a batch that FAILED after the file parsed is reported as “no rows were parsed”.** Run to here returns 200 with *“no rows were parsed from the chosen file(s)”*; `PipelineTestRun.Result.status()` / `error()` never reach the response. Seen with a binder error while 11 rows had parsed — the one message that would explain the failure is dropped and replaced by a false one. → `okf/backend/engine/pipeline-test-run.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`4ff2ffb0`)** — (a) `ConfigValidator` reports a partition `source` that is not in `raw.fields[]` (startup WARN; `POST /validate {configPath}` → `clean:false`) — a report, not a refusal, because a plugin ingester may `define` extra raw columns. (b) was **worse than filed**, measured: DuckDB renamed the partition column `account_class_1`, `PARTITION_BY` bound the MAPPED column, and `account_class_1` stayed in the files; `DataTransformer.selectFor` now **refuses** a partition column equal ignoring case to a mapped column (and the validator reports it). Six test fixtures plus the `plugins.md` example had that shape (`event_type` vs `EVENT_TYPE`) → now `record_type`. (c) an unparsed `DATE_*` value is NULL → `__HIVE_DEFAULT_PARTITION__`; the `1900/01/01` sentinel never existed. `excel_example` now partitions `CATEGORY` as `VARCHAR` (`item_category`), pinned by `DemoCorpusIngestTest`. Residual filed: `DATE-PARTITION-ON-TEXT-SHIPPED-1`. Original row: **`PARTITION-KEY-VALIDATION-GAPS-1` — three ways a partition key validates clean and then misbehaves.** (a) A `partitionKey` naming a **mapped-only** column validates clean, then fails at run time with a binder error. (b) A partition column colliding **by case** with a mapped column (`account_class` vs `ACCOUNT_CLASS`) is silently renamed `account_class_1`, and the folders split on the other column. (c) The shipped `excel_example` partitions on the text column `CATEGORY` → every row lands under `__HIVE_DEFAULT_PARTITION__`, not the `1900/01/01` placeholder the owning doc describes. → `okf/backend/config/configuration.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`6f2a6eb7`)** — cause confirmed: `read_xlsx(all_varchar=true)` yields the serial. For a raw field **declared** `DATE`/`TIMESTAMP`/`TIMESTAMPTZ`, `DuckDbCsvIngester.xlsxSerialAsText` converts a bare number ≤ 2958465 (epoch 1899-12-30) and lands text in the Pipeline's first date/timestamp format (ISO if none). `VARCHAR` fields and text cells are untouched. ⚠ A 1–7-digit TEXT cell in a date-declared field reads as a serial. `gl_journal` dropped its serial arithmetic; pinned by `DemoCorpusIngestTest.glJournalSplitsFiveThreeThreeTwoByAccountClass` and `XlsxParsingTest` (+3). Original row: **`EXCEL-DATES-ARRIVE-AS-SERIALS-1` — Excel date cells arrive as serial numbers.** The Excel reader forces every column to text, so a date cell arrives as `46237`; each date needs a mapping conversion, and a raw Excel date cannot drive date partitioning. → `okf/backend/config/parsing-options-reference.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`e1523c10`)** — the load always refused (the comma is also the row delimiter, so the row is genuinely ambiguous), but the refusal was JToon's bare *"Tabular row value count (4) does not match header field count (3)"*, logged against the Pipeline path while the bad line was in the schema file, and `POST /validate {configPath}` answered 500. Now `ConfigCodec` names line, table, counts and the quoted fix; `PipelineConfigParser.readToon` prefixes the file path; `/validate` answers **422** (openapi updated). Pinned by `SchemaTabularRowWidthTest`, `ConfigCodecTest` (+4), `ControlApiConfigSpecTest.configPathWhoseSchemaDoesNotDecodeIs422NamingTheFileAndLine`. Residuals filed: `PIPELINE-LOAD-FAILURE-INVISIBLE-1`, `CONFIGCODEC-LENIENT-IS-STRICT-1`. Original row: **`TOON-UNQUOTED-DECIMAL-SKIPS-PIPELINE-1` — an unquoted `DECIMAL(18,2)` in a TOON table row makes the loader skip the WHOLE Pipeline.** The only signal is a server-log WARN; the Pipeline simply is not there. → `okf/backend/config/toon-config.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`e7f8e2aa`)** — the Windows path was only the trigger: `NativeCsvStreamingEngine.streamingIngest` held read + transform + partition write in ONE catch, so ANY sink failure read as `QUARANTINED_UNREADABLE` (in production that moved a good file out of the inbox). The write is now wrapped in `SinkFlushException` and rethrown: the batch FAILS *"partition write failed for …"* and nothing is quarantined; a genuine `read_csv` failure is unchanged. Scratch prefix `inspecto_testrun_` → `itr_`. Pinned by `PipelineTestRunTest.aFailedPartitionWriteFailsTheBatchAndNeverBlamesTheInput` (mutant red). ⚠ Residual, kept here rather than filed: a *transform* failure on that lane still reports UNREADABLE (the view is lazy). Original row: **`WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1` — a long scratch path on Windows quarantines a readable file.** A scratch path near 250 characters failed the partition write, and the member was marked `QUARANTINED_UNREADABLE` although it read fine — a path-length failure reported as a content verdict. → `okf/backend/engine/pipeline-test-run.md`.

### Filed from the live workbench verification, 2026-09-23

A read-only sweep of **all 26 shipped Pipelines** against a running control plane (`GET …/graph`, `…/graph/raw`, `POST /validate`, and a real `…/run` over each inbox), after the trust register's work landed. ✅ 26/26 lift and validate with **zero ERROR findings and zero `csv_settings` warnings** (6 of 26 used to be born `clean:false` for a rule that could not apply); every projection carries `links.roundTrip` + `readOnlyProjection`; a Dataset-fed Pipeline refuses a file test run **501** by name. 🔴 Two things the sweep found:

- ~~**P2**~~ · ✅ **FIXED 2026-09-23** — **`ROLLUP-ORDERS-BY-ABSENT-COLUMN-1`: a shipped example could never run.** `orders_enriched_rollup`'s `dedup.order_by: EVENT_TS DESC` names a column its own `orders_schema.toon` does not declare (the binder's candidate list was exactly the seven declared fields), so the test run died **422** on every input. ⚠ It was hidden behind `REFERENCE-EXAMPLES-NEED-UNRUN-SEED-1`: while the reference file was missing the run never reached the dedup, so seeding the reference is what exposed it — **a fix that removes one failure can reveal the next one behind it, and the row looks worse before it looks better.** Now `ORDER_DATE DESC`; the chain runs `map → join → dedup (12 kept, 0 duplicate) → summarize (4 groups)`. → `archived-documents/plans-archive/workbench-trust-plan.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`1bcedf55`)** — DuckDB JDBC 1.5.2.1 writes the preamble, `\nError: ` and the real error as **ONE native message** (no cause, no suppressed, no SQLState — probed), so there was nothing to unwrap. One shared seam, `DuckDbUtil.withoutPendingQueryPreamble`, used by `testRun`, `dryRunFlow` and `POST /components/transform/describe`. Pinned by `ControlApiPipelineTestRunTest.anAbsentColumnIsReportedAsTheBinderErrorNotTheDriverPreamble` and by `DuckDbPendingQueryPreambleTest` on the real driver. Follow-up: `DUCKDB-PREAMBLE-OTHER-422S-1`. Original row: **`TESTRUN-BINDER-ERROR-LEAKS-PREAMBLE-1` — a genuine SQL error still arrives wrapped in an engine-internal preamble.** The 422 above read *“Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result”* **before** the part that helps (*“Binder Error: Referenced column ‘EVENT_TS’ not found … Candidate bindings: …”*). ✅ Unlike the reference case `WB-05` fixed, the actionable half IS present — which is why this is P3, not P2 — but the first clause is DuckDB's internal bookkeeping and an author has to read past it. Strip the preamble on the test paths, keeping the cause. → `okf/backend/engine/pipeline-test-run.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`c580de0a`)** — probed: the preamble appears on ANY plain `Statement` (`execute` AND `executeQuery`) for a bind-time failure (Binder, Catalog, table-function IO Error), never on a `PreparedStatement`, a Parser Error or an execution-time error. `QueryExecutor.run` registers views on a plain `Statement`, so all five candidates reproduced **plus nine more families**: `QueryRoutes`, `ShareRoutes`, `RuleRoutes`, `ViewRoutes`, `ReconRoutes` (×4), `RouteErrors.mapPreviewErrors`, and geo-link `GeoRoutes`/`InvRoutes` (×3). All adopt `DuckDbUtil.withoutPendingQueryPreamble`; one real-HTTP test per family, each red before. Original row: **`DUCKDB-PREAMBLE-OTHER-422S-1` — four more routes build a 422 from a raw DuckDB message and likely carry the same preamble.** `BiRoutes.java:91`, `DbBrowserRoutes.java:225` and `:257`, `EnrichmentRoutes.java:150`, `ExpectationRoutes.java:166` each concatenate `getMessage()` into the refusal. ⚠ **Unverified — reproduce per route first**: a route whose SQL runs through a prepared statement reports the Binder Error cleanly and needs nothing. Where it reproduces, the fix is a one-line adoption of `DuckDbUtil.withoutPendingQueryPreamble`, never a second stripper. → `okf/backend/engine/pipeline-test-run.md`.

### Filed from the pipeline-building drain, 2026-09-23

The residuals of the 2026-09-23 drain that closed the domain-demo rows above (`e7f8e2aa` … `d8ce162e`). ⚠ Each is the lane's finding; `VALIDATE-CONFIGPATH-UNJAILED-1` was explicitly **unverified** when filed (since reproduced and fixed, `57432516`).

- ~~**P3**~~ · ✅ **REFUTED 2026-09-23 (no code)** — the Recipe view was deleted in `6d3c68fa` (2026-09-18) and the offline mock in `f1553136` (2026-08-31); all three lists the row names (client `RECIPE_VERBS` fallback, write-only `servedVerbs`, mock `LOWERABLE`/`lowerGraph`) have no production reader, and the canvas palette already offers both types via processor-catalog `addable`. `step-catalog.md` corrected (it still said the Recipe-view palette "does not list it yet"). The dead mirrors are filed as `STEP-TYPES-DEAD-CLIENT-MIRRORS-1`. Original row: **`WEBHOOK-RECIPE-PALETTE-1` — the `webhook` verb compiles but the Recipe-view palette does not list it.** `sink.webhook` (`f9c102ac`) can be added from the canvas and `RecipeCompiler` accepts `webhook:`, but the Recipe view's verb list has client-side copies: the server's `RECIPE_VERBS`, the matching list in `pipeline-graph.ts`, and the mock `LOWERABLE` set + lower in `pipeline-editable.ts` — all need the type (the Profiler Step has the same gap). Dropped from the shipping change after it broke a UI parity test. → `okf/backend/pipeline-graph/step-catalog.md`
- ~~**P2**~~ · ✅ **SHIPPED 2026-09-23 (`41c84095`), filed and closed the same day** — found by closing `JOB-PATH-DEMO-CONFIG-REPOINT-1`. **`JOB-PATH-SINGLE-TENANT-GATE-BASE-1` — single-tenant, the save gates judged every job key from the WRITE root while `PipelineJobRunner` reads `pipeline_config`/`data_dir` from the config READ root**, so a value that runs was refused (`PathJail.Escape` → 422 on re-save). FOUR producers: `JobRoutes.parseJob`, the `BundleRoutes` job import, `/config/write`, `/config/patch`. Now ONE resolver, `SpaceConfigRoot.jobPathBase(key)` (`CONFIG_READ_ROOT_JOB_KEYS` → the read root, else the Space config root), used by the runner and all four gates via `ConfigSafetyValidator.checkJob(raw, policy, baseForKey)`. Multi-Space unchanged. ⛔ A key joining `JOB_PATH_KEYS` must be placed in or out of `CONFIG_READ_ROOT_JOB_KEYS` by what its reader calls. Pinned by `ControlApiJobPathSingleTenantBaseTest` (4, real HTTP) + `SpaceConfigRootTest`. → `okf/backend/control-plane/jobs.md`.
- ~~**P2**~~ · ✅ **FIXED 2026-09-23 (`5aac431e`)** — operator decision: relative DATA paths resolve under the **Space DIRECTORY** (configs say `data/…`) via ONE resolver, `PathJail.resolveDataPath` / `dataPath` (Space dir = parent of the nearest `config/` ancestor; an ambiguous old-CWD value is refused naming both paths; no Space → the working-directory reading, the single-tenant equivalent per `SpaceConfigRoot.jobPathBase`). Keys: `dirs.*`, `processing.duckdb.temp_directory`, `output.ducklake.data_path`, `sinks[].database` (+ ducklake), `route.branches[].database`, the join path reference, enrichment `input`/`output.database` + `references.<n>.path`, and a LOCAL connection's `base_path`; the 422 gate, `PipelineDataDirs`, the `ura` CLI and `PipelineJobRunner` share it. 302 values in 34 shipped configs respelled; SPA scaffolds write `data/…`; space-bundle rebasing and `source_prefix` retired (breaking, no shim); the template sandbox moved to `data/templates/<id>`. Pinned by `ShippedPipelinesWriteUnderTheirSpaceDirectoryTest` + `DataPathResolutionTest` (falsified). Not changed: a view's `derived_sql` literal (`sites_active_view`), enrichment `transform_file`. Residuals filed: `DATA-PATH-RESIDUALS-1`. Original row: **`DATA-DIRS-RESOLVE-AGAINST-CWD-1` — a Pipeline's DATA paths still resolve against the process CWD.** `dirs.*`, enrichment `references.<n>.path`, connection `base_path`, `output.ducklake.data_path`, `processing.duckdb.temp_directory` — spelled `spaces/<space>/data/…` in every shipped config. From `inspecto-deploy/` with `-Dspaces.root=..\spaces` the demo Pipelines now register (`SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`), but their data/status dirs land under the launch dir; loading shipped configs in tests creates status dirs under module dirs, which fools repo-root walkers like `MappingMigrationTest` (observed stray `inspecto/spaces`, `inspecto/out`, `inspecto/jobs_audit`, `inspecto/templates`). Needs an operator call on the base: the Space data root, or the Space dir. → `okf/backend/config/config-safety.md`.
- ~~**P2**~~ · ✅ **FIXED 2026-09-23 (`57432516`)** — REPRODUCED over real HTTP first: a file outside the roots was read and parsed (422), a missing one answered 500 naming the full server path (an existence oracle), and relative paths used the CWD. `configPath` is now jailed under `PathJail.allowedRoots()` before any filesystem access: relative with no write root → 400, escape → 403 (identical for present and missing), not a file → 404. Pinned by `ControlApiValidateConfigPathJailTest` (6). Sibling routes audited: none share the hole. Residuals filed: `PREVIEW-REFERENCE-PATH-UNJAILED-1`, `VALIDATE-PREPARE-WRITES-STATUS-DIR-1`. Original row: **`VALIDATE-CONFIGPATH-UNJAILED-1` — `POST /validate {configPath}` loads any server path the caller names, with no path jail** (security). ⚠ **Unverified — reproduce first**, then jail `configPath` like every other config read. → `okf/backend/config/config-safety.md`.
- ~~**P3**~~ · ✅ **CLOSED 2026-09-23 (`800ffe89`)** — operator decision: a Pipeline that fails to load **stays a row in `GET /pipelines`** — `{name (file stem), path, loadError {file, line?, message}}` after the healthy rows; no new route. `ConfigRegistry` keeps a per-Space `LoadFailure` list; `pipelines()` / `configs()` / `configForPath()` are unchanged, so the scheduler, run/trigger, `/runs`, `/ready`, combined topology, lineage, metrics and catalog never see it (trigger and graph answer 404). SPA: `list()` drops broken rows; the editor's Open dialog shows them (error badge, message, path; not openable). Pinned by `ControlApiPipelineLoadErrorTest` + `ConfigRegistryTest` + specs. ⚠ Residual: `file`/`line` are parsed from the message prefix. Original row: **`PIPELINE-LOAD-FAILURE-INVISIBLE-1` — a Pipeline that fails to load is only a server-log WARN.** `ConfigRegistry.rebuild` drops it; it is absent from `GET /pipelines` with no API or SPA surface saying why. Operator decision: a load-errors route + SPA surface, or a field on `GET /pipelines`. → `okf/backend/config/toon-config.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`7e115f2c`)** — kept **strict** by evidence (a non-strict decode silently truncates or pads rows). `toMapStrict` / `isStrictDecodable` deleted; the one decode is `ToonHelper.decode` (`inspecto-util`, the leaf; `ConfigCodec.toMap` delegates; new dependency config → util); `ToonHelper.load` prefixes the path; `SchemaExtractor`, `MainApp` and the exchange `Ledger` / `ExchangeSnapshots` routed through it. Pinned by `ToonHelperTest` + `ConfigCodecTest`. Residual filed: `CONFIGCODEC-CALLERS-NO-FILE-NAME-1`. Original row: **`CONFIGCODEC-LENIENT-IS-STRICT-1` — `ConfigCodec.toMap` is documented lenient but decodes strict.** JToon 1.0.9's `decode(String)` uses `DecodeOptions.DEFAULT` (`strict=true`), the same as `toMapStrict`. Fix the javadoc, or make it lenient after checking its dependents. Also: `ToonHelper.load`, `SchemaExtractor`, `MainApp` and `inspecto-exchange` call `JToon.decode` directly and miss the named-line message. → `okf/backend/config/toon-config.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`61062252`)** — `rewriteSatelliteRefs` re-points `asn1.grammar_file` to the bundled name (before, an import with a non-sibling spelling answered 200 and registered, then failed at first ingest); `POST /parsers/{id}/preview` takes the Pipeline's `subdir` and resolves beside it, the Space root only with no Pipeline context. Proof: `ControlApiPipelineBundleTest.anAsn1GrammarFileTravelsAndIsRepointedBesideTheImportedPipeline` + `ControlApiParsersTest.asn1PreviewResolvesAGrammarFileBesideThePipelineSubdir`. Original row: **`BUNDLE-ASN1-GRAMMAR-FILE-1` — a Pipeline bundle does not rewrite `parsing.asn1.grammar_file`.** `PipelineBundleRoutes.rewriteSatelliteRefs` skips it, so after import only a bare sibling name resolves. Also: preview and Pipeline spell the ref differently for a Pipeline in a subdirectory (preview resolves from the Space config root: `msc/msc_cdr.asn`; the Pipeline resolves beside itself: `msc_cdr.asn`), because the drawer does not send the Pipeline's location to the preview route. → `okf/backend/engine/parser-plugins.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`1bd13dad`)** — `ConfigValidator` reports a `partitionKey` / `DATE_*` partition whose raw source is declared non-date (WARN; `/validate {configPath}` → `clean:false`; a report, not a refusal). `ShippedDatePartitionsAreDateTypedTest` sweeps every shipped Space + `inspecto/examples`; its first run found **7**, not 2: `asn1_example` + `examples/asn1-frontend` → `served_imsi VARCHAR`; `orders_by_region_feed` → `sales_region VARCHAR`; `examples/xlsx-frontend` → `item_category VARCHAR`; `msc_cdr` ×3 `EVENT_TIME` declared `TIMESTAMP` (already partitioned right; folders unchanged). ⚠ `served_imsi` is one folder per subscriber — fine for the example, wrong for a production CDR store. Pinned by `DemoCorpusIngestTest` (+2), `ConfigValidatorTest` (+2). Original row: **`DATE-PARTITION-ON-TEXT-SHIPPED-1` — two shipped Pipelines DATE-partition a text column.** `asn1_example` (`partitionKey: IMSI`) and `orders_by_region_feed` (`partitionKey: REGION`) → every row lands under `__HIVE_DEFAULT_PARTITION__`. The residual of `PARTITION-KEY-VALIDATION-GAPS-1`. → `okf/backend/config/configuration.md`.
- ~~**P3**~~ · ✅ **CLOSED 2026-09-23 (`e3977da4`)** — operator decision: **REFUSE**. Two `sinks[]` whose effective ducklake resolves to the same `catalog_url` + schema + registered table (`batch.table()` on multi-schema, else the block's `table`) are refused: `PipelineConfig.prepare()` throws unconditionally naming both sinks and the fix; the save path (`/config/write`, `/config/patch`, graph PUT, bundle import, `/validate` draft) reports `ERR_SINK_DUCKLAKE_SHARED_TABLE` at ERROR regardless of `active`; `/validate {configPath}` now answers 422 (not 500) for any `prepare()` refusal. The rule lives in `SinkLakeCollisions` (not `ConfigSafetyValidator` — module direction). Pinned by `SinkDuckLakeSharedTableTest` (8) + `ControlApiSinkDuckLakeSharedTableTest` (4). ⚠ Residual: draft-side ingester detection misses a grammar component / `frontend: asn1` beside `schemas[]`; `catalog_url` is compared verbatim. Original row: **`SINK-DUCKLAKE-SHARED-LAKE-DUPLICATES-1` — two sinks inheriting one lake both register into its one table**, so a replicate fan-out puts every row there once per destination. Unchanged by `2cc95151` (per-sink registration). Operator question: keep, or dedupe. → `okf/backend/engine/output-sinks.md`.
- ~~**P2**~~ · ✅ **FIXED 2026-09-23 (`5426942a`)** — when `materialize` fails, `streamUnit` re-runs the read alone (`COUNT(*)` over `raw_input`, the union lane's check): readable → the batch FAILS *"transform failed for …"* and the file stays in the inbox; unreadable → still `QUARANTINED_UNREADABLE`. No cost on a successful batch (the probe runs only on the failure path). Other lanes checked: the Java parse lane and both plugin lanes already classified correctly. Pinned by `ConsignmentIngestorTest` (+2). Residual filed: `CHUNKED-UNREADABLE-FAILS-BATCH-1`. Original row: **`SINGLE-MEMBER-TRANSFORM-FAILURE-QUARANTINES-1` — the single-member native CSV lane still quarantines a readable file whose TRANSFORM fails.** `NativeCsvStreamingEngine.streamingIngest` still catches a `materialize` (transform) failure as `QUARANTINED_UNREADABLE`, so a readable file with a bad `partitionKey` or mapping is moved out of the inbox in production. The write half was split out by `WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1` (`e7f8e2aa`); the transform half was not — the view is lazy, so read and transform fail in one statement: it needs a probe that forces the read first, or a cheap pre-read. → `okf/backend/engine/pipeline-test-run.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`83e0fa52`)** — all five: (a) `resolveDataPath` refuses a value whose leading ≥2 segments repeat its Space's own path; (b) `TarInboxPreparer.main` + its path constructor removed — `ura prepare-inbox` is the only entry; (c) `ConnectionProfile.authoredBasePath` — `GET /connections`, bundles and re-save carry the authored `base_path`; (d) `SpaceConfigRoot.jobPathBase("data_dir")` = the Space dir in a Space (single-tenant unchanged) and `PipelineJobRunner` runs on the resolved `data_dir`; (e) `SchemaExtractor` emits `data/inbox/<x>`, `data/<x>/<kind>`. Original row: **`DATA-PATH-RESIDUALS-1` — the residuals of `DATA-DIRS-RESOLVE-AGAINST-CWD-1` (`5aac431e`).** (a) A config still spelled `spaces/x/data/…`, loaded where that CWD path does not exist, silently resolves to `spaces/x/spaces/x/data/…` (the same limit as `resolveJobPath`). (b) `TarInboxPreparer.main` still reads `dirs.*` from the CWD (`ura prepare-inbox` is fixed). (c) `GET /connections` now shows a local connection's RESOLVED absolute `base_path`, so re-saving from the UI stores it absolute. (d) A multi-Space job's `data_dir` still resolves against the Space config root, not the Space dir (no shipped Space job sets it). (e) `SchemaExtractor` still emits `inbox/<x>` paths. → `okf/backend/config/config-safety.md`.
- **P3** · **`FLAT-DRYRUN-COUNTS-ZERO-1` — a flat-lane dry run's provenance record shows 0 parsed / 0 landed**, because the dry run skips parsing: the overlay shows THAT it ran, not what it would move. Real counts need a parse pass on a dry run (reverses the skip). Demand-gated. → `okf/capabilities/pipeline-execution/pipeline-execution.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`438b895d`)** — REPRODUCED over real HTTP first (the root listing gained the status dir). `prepare()` split into `requireRunnable()` (no I/O) + `createStatusDir()`; `/validate` uses the new `PipelineConfig.loadForValidation()`; `load()` is unchanged for run callers. No other read-shaped route calls `load()`. Pinned by `ControlApiValidateConfigPathJailTest` + `ConfigFromMapTest`. Original row: **`VALIDATE-PREPARE-WRITES-STATUS-DIR-1` — `POST /validate {configPath}` is not strictly read-only.** → `okf/backend/config/config-safety.md`.
- **P2** · **`PROCESSOR-RELEASE-READINESS-1` — releasability audit (2026-09-23) of the 37 DELIVERED processors against a draft 7-point bar** (documented · real-path test · workbench preview/lift/lower · UI palette · edition gating · fails clearly at save · demo). ~22 pass nearly all. Gap groups: ~~**G1**~~ ✅ FIXED `973d943b` — `parser.asn1.ber` maps onto `parser.asn1` (addable); per-family + planned counts are derived markers (`processors-<fam>-<status>`, `processors-planned`); `schema.drift` listed Delivered to match the contract, status still UNDER REVIEW; Standard+ → Professional+ in `step-catalog.md` · ~~**G3**~~ ✅ FIXED `ccda98a8` — one shared `SaveGate` for `/config/write`, `/config/patch`, graph PUT, bundle import and the `/validate` draft (reproduced: graph save skipped unknown-Connection + census; import skipped census/route/summarize; the `/validate` draft skipped five checks; EVERY path accepted a stray `webhook.url:`) + `ERR_WEBHOOK_INVALID` / `_CONNECTION_UNKNOWN` / `_CONNECTION_NOT_HTTPS`; bundle import keeps `WARN_UNRESOLVED_CONNECTION` for a missing Connection. ⚠ Breaking: `/validate`'s `safety` flag removed (always checked). The row's "config write lacks route/summarize" half was false. Pinned by `ControlApiSaveGateParityTest` (5 faults × 5 paths); residual filed: `VALIDATE-CONFIGPATH-SKIPS-SAVEGATE-1` · ~~**G4**~~ ✅ FIXED 2026-09-23 — `ConfigRoutes.stepConfigFindings` in `SaveGate` refuses at save what `RowShaper` refused only at run: a lookup with no `column`, no `mappings` or a mapping that is not `key=value`; a filter with a blank `where`; a dedup with no `keys`; a lookup column, dedup key or profile column the declared schema does not carry (`ERR_`/`WARN_STEP_CONFIG_INVALID`, ERROR when active). Pinned by `StepConfigSaveFindingsTest`; `route:` branch `steps[]` sub-chains ARE walked (`ConfigRoutes.walkBranches` — an earlier "not walked" residual here was false); its `ConfigSpecs` clause closed by G5 · ~~**G5 + G11**~~ ✅ SHIPPED `bb6977b3` — collector `retry` / `circuit_breaker` / `fetch.rate_limit` declared in `ConfigSpecs` (save-time 422) and the canvas form; `ducklake.*` on `sink.persistent`; the Collect key table + four non-Step processor key sections in `step-catalog.md` · ~~**G6**~~ ✅ tests shipped `d3da351b` — `CollectorProcessorRemoteCycleTest` (MINA SFTP + DuckDB JDBC: breaker, rate limit, MOVE on success/failure, exact sink rows) + `CollectorProcessorDatasetFeedTest`; found two defects (`RATE-LIMIT-OVERSIZE-HANGS-1`, `POST-ACTION-MOVE-RECOLLECTS-ARCHIVE-1`); `db.jdbc` drops off the questionable list (now asserts exact rows); throttle stays questionable until `RATE-LIMIT-OVERSIZE-HANGS-1` is fixed · ~~**G7**~~ ✅ closed `abe20bae` — `PipelineJobRunnerTest` runs the profile / lookup / webhook branch end to end asserting written output (webhook batches, `Idempotency-Key`, a rejected POST ⇒ branch failed + source not finalised, re-run same keys); profile round trip in `NodeConfigNameContractTest` · ~~**G8**~~ ✅ closed `e27591ea` — a reached-but-unexecuted node (enrichment) is warned by name with the nodes it starved (not run: it is a post-commit Stage-2 job over committed data; not refused: that would block previewing any graph containing one); the graph dry run + run-to-here refuse `sink.webhook` via `WebhookSink.plan` like `DryRunSinkWriter` · **G9** edition board vs code (`alert.dispatch`, archive, ducklake not gated as `EDITIONS.md` says — operator decision) · ~~**G10**~~ ✅ 2026-09-24 — a demo for every DELIVERED processor (`07-steps/profile`, `07-steps/dedup-window`, `03-schema-transform/schema-drift`, `05-acquisition/intake-throttle`, `_reference/` `sftp-collector` / `db-export` / `webhook-sink` / `ducklake-sink`) · ~~**bar + questionable seven**~~ ✅ 2026-09-24 — bar ADOPTED (operator sign-off); `schema.drift`, throttle, circuit breaker, `sink.archive`, `db.jdbc` PASS; `constraint.check` / `alert.dispatch` / `sink.quarantine` set **PARTIAL** in `ProcessorCatalog` (not Steps); throttle / breaker / archive Collector keys now fail at save; a Connection's connector option checks run at save (`CollectorConnectorFactory.validate`, `db` refuses a missing `query` / `export_name` / `:watermark` with 422 on `POST/PUT /connections` + bundle import — `DbExportConnectorFactoryValidateTest`, `ControlApiCollectorsAndConnectionsTest`). **Still open: G9 only** (operator decision on the three edition cells in `step-catalog.md` *Edition notes*). → `okf/backend/pipeline-graph/step-catalog.md`.
- **P3** · **`STEP-TYPES-DEAD-CLIENT-MIRRORS-1` — `GET /pipelines/step-types` has no live UI consumer since the Recipe view was removed (`6d3c68fa`).** The client `RECIPE_VERBS`, the write-only `servedVerbs` + its fetch (`pipeline-editor.component.ts` ~:426 / :760) and the mock lift/lower port in `pipeline-editable.ts` (dead since `f1553136`; drifted — lookup + chain `sql` drop silently, profile + webhook refuse) are held up only by specs. Operator decisions: (A) keep the endpoint as the published recipe vocabulary (add profile + webhook server-side only, drop the client parity spec) or retire it with its contract + count guard; (B) delete the dead client code (keep `isProjectionSlot`). Found refuting `WEBHOOK-RECIPE-PALETTE-1`. → `okf/backend/pipeline-graph/step-catalog.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`3f5183d4`)** — per chunk, `streamUnit`'s `COUNT(*)` read check classifies a failed materialize (transform → FAILED, the file stays; read → unreadable); `FileChunker` raises source-read failures (a corrupt/truncated `.gz` — the realistic case) as `UnreadableSourceException`, separate from scratch-write `IOException`s (still FAILED); on an unreadable chunk `rollBackChunks` deletes the earlier chunks' outputs + the branch-commit log, then `QUARANTINED_UNREADABLE` names the chunk and the rolled-back count (all-or-nothing, like a single member — dated decision in the doc). ⚠ The row's "retries forever" was inexact: `CommitRetry` eventually quarantined it as `retry_exhausted`, rewriting the readable chunks on every retry. Pinned by `ChunkedStreamingTest` (+3). Residual filed: `PARKED-BRANCH-LEAK-ON-FAILED-BATCH-1`. Original row: **`CHUNKED-UNREADABLE-FAILS-BATCH-1` — the chunked native CSV lane never quarantines an unreadable file.** → `okf/backend/engine/pipeline-test-run.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-23 (`20a64513`)** — **15 sites in 11 classes** (not ~20) routed through `ToonHelper.load`: `DataSourceRoutes`, `DataSourceBundleResolver`, `ConnectionProfile`, `AlertRule`, `RcaTemplate`, `MetadataValidateTask`, `ConfigMigrator`, ops Tag / TagRule / CaseRule / Workflow; string decodes stay `ConfigCodec.toMap`. Guarded by `NoHandRolledToonFileDecodeContractTest` + `TagRuleTest`. Original row: **`CONFIGCODEC-CALLERS-NO-FILE-NAME-1` — ~20 readers decode TOON themselves and their errors do not name the file.** → `okf/backend/config/toon-config.md`.
- **P3** · **`ENRICHMENT-MIDWALK-LANES-DISAGREE-1` — an enrichment authored mid-walk runs differently in the two lanes.** `PipelineExecutor.execute` skips it (the graph lane starves downstream sinks) while `PipelineEditable.lower` drops the node (the flat lane feeds them). The save gate should refuse a mid-walk enrichment, or one lane should change. → `okf/backend/engine/pipeline-test-run.md`.
- **P3** · **`DEMO-DATASET-FEED-UNSAFE-ID-1` — the shipped `orders_by_region_feed` cannot poll: `collector.dataset: datasets/orders_by_region`.** `DatasetCollectorConnectorFactory.resolveDatasetDir` calls `ComponentStore.get("dataset", id)`, which refuses any id containing `/` as "unsafe component id" — so the demo's Dataset-fed Pipeline fails at poll time, not with the README's `unknown dataset`. Found by `DUCKLE-C7` (it lists the value as UNCERTAIN). Fix the demo value to the plain id `orders_by_region` **after** confirming what `trigger.from` (also `datasets/orders_by_region`) expects — the two may need different spellings. → `okf/backend/pipeline-graph/step-catalog.md`.

### Filed from the multi-domain workbench sweep, 2026-09-22

Eight findings from sweeping **all 26 shipped Pipelines** (`default` 15 · `demo` 8 · `ucc` 3) through the
workbench API — lift, validate, lossless round-trip, display-projection PUT — and running the editor's own
test run over real sample bytes for the **24** that ship one, across six parse frontends and six Step kinds.
None is telecom-specific; the owning document's §3.1 coverage matrix is where each row is grounded.

- ~~**P2**~~ · ✅ **FIXED 2026-09-22 (`WB-08`, decision `D5`)** — the dry-run seeds one relation per `route:<segment>`, so the walk leaves a segment-routed parser without the walker being special-cased. 🔴 **The plan's own blocker for this item was wrong:** it said the segment attribution was unrecoverable because `PartitionOutput` carries no label. `IngestOutcome.schemaByOutput` has carried it since the union-mode ingester wrote it; nothing was reading it out. ✅ **VERIFIED END TO END 2026-09-23 against a live control plane over real bytes**: `asn1_example` (BER) returns `map_moCallRecord · data · 3 rows` with the decoded values (IMSI 42/77/91) and writes 3 rows under the `moCallRecord` partition; `xml_example` (plugin) returns `map_order · data · 3 rows`. Both previously returned `relations: []`. ⚠ Only the segment the sample actually carries appears — an unrouted segment is ABSENT, not zero, as designed. The DRYRUN-2 *“nothing downstream consumed it”* warning is gone. Original row: **`TESTRUN-SEGMENT-ROUTE-NO-FLOW-1` — the test run parses a segment-routed frontend and then reaches NOTHING downstream.** `asn1_example` (BER) and `xml_example` (plugin) both lift as `parse →(route:<segment>)→ map_<segment> → sink_<segment>` plus `unmatched → quarantine`. *Run to here* decoded 3 records from each and returned `relations: []` with the honest DRYRUN-2 warning *"the sample reached no node past the seed 'parse' — nothing downstream consumed it"*. The walk does not follow a `route:` edge out of a parser. This is the pre-mediation telecom shape, the XML shape and every multi-record-type feed: for them the builder's only test instrument tests the decoder and nothing after it. ✅ **`D5` SIGNED 2026-09-22 — WALK the edge:** seed one relation per segment, keyed as production's multi-seed `execute` does, plus `unmatched`; `liveInbound` then follows the existing edges unchanged. ⛔ The walker is not special-cased. Refusing by name was declined — it would leave 2/8 frontends and every multi-record feed untestable past the decoder. Written into `okf/backend/engine/pipeline-test-run.md` the same day; build tracked as `WB-08`. → `okf/backend/engine/pipeline-test-run.md`.
- ~~**P2**~~ · ✅ **FIXED 2026-09-22 (`WB-03`)** — ONE predicate, `ConfigRoutes.hasSchemaSource`, called by the write gate AND by `POST /validate`. 🔴 **The disagreement was deeper than this row said:** the predicate ignored `segments{}` **and** `/validate` never ran the arming check in EITHER branch, so fixing only the predicate would have left the two surfaces disagreeing about every other active-but-schemaless config. Both halves landed; the predicate scans any `parsing.*` sub-block rather than matching frontend names. Proof: `WB-01`'s HTTP round-trip sweep is green with an EMPTY pin — **26 of 26** save losslessly. Original row: **`SAVE-GATE-VS-VALIDATE-DISAGREE-1` — the arming gate and the validator give different answers on the same config; a shipped ASN.1 Pipeline can be opened but never saved.** For `asn1_example`, `POST /validate` returns **no ERROR-level finding**, while `PUT /graph` with the editor's own `graph/raw` payload refuses **422 `ERR_ARMED_WITHOUT_SCHEMA`** — *"active: true but no schema is configured (processing.schema_file, processing.schemas[], or a plugin ingester)"*. The gate does not count `parsing.asn1.segments{}` as a schema; the validator does. Measured on the only ASN.1 example; the same block shape is what every grammar/segment frontend authors. ✅ Fails closed (`written:false`, file byte-identical). ✅ **`D7` SIGNED 2026-09-22 — YES, a non-empty `parsing.<frontend>.segments{}` IS a schema source** for the arming gate: it is the only one a segment-routed frontend has. One predicate, both call sites (`ConfigRoutes` + `ConfigPreviewRoutes`). Written into `okf/backend/pipeline-graph/pipeline-config-keys.md` the same day; build tracked as `WB-03`. → `okf/backend/pipeline-graph/pipeline-config-keys.md`.
- ~~**P2**~~ · **`REFERENCE-EXAMPLES-NEED-UNRUN-SEED-1`** — ✅ **FIXED 2026-09-22 by `tools/seed-samples.mjs` (`WB-16`)**, and 🔴 **the row's stated cause was WRONG.** It said the reference file "ships at `data/samples/ref/` and **nothing copies it**". Something does: each space's `data/samples/seed-inbox.sh` (and its `.ps1` twin) copy `ref/*` into `../ref/`, in all three spaces that have one — verified on disk. The real defect was that **nothing RUNS them**: seeding was a manual step no launch path, no skill and no README invoked, and those six scripts carry a HAND-WRITTEN pipeline list, so a Pipeline added to a space is silently unseeded until someone edits two files. The new tool derives the list from each Pipeline's own `dirs.poll`, seeds all four spaces (23 inboxes, 2 reference sets, 225 working dirs), is idempotent (proved: a second run changes no bytes), and reports the Pipelines that ship **no** sample rather than skipping them in silence — which is how `lookup_step` stays visible. Wired into the four space-serving launchers in `.claude/launch.json` and step 2 of the `smoke` skill. ⚠ The measured symptom stands and was the right thing to chase: both examples fail **422** with a leaked DuckDB internal on a fresh checkout. *(Original cause text, kept because a wrong cause is worth seeing:)* the file ships at `data/samples/ref/region_dim.csv` and **nothing copies it** (`data/**` is gitignored bar `samples/`). Both paths fail **422** with a leaked DuckDB internal — *"Attempting to execute an unsuccessful or closed pending query result … No files found that match the pattern"* — reproduced twice with a fresh seed and an immediate call, so it is not a poller race. Seed the reference in the same step that seeds the inbox. → `okf/backend/build-run/build-test.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-04`)** — the `csv_settings` rules are delimited-only. ⚠ The **delimiter** rule was gated too, not just the two format rules: same defect, same block. Falsification-probed — forcing the gate open turns the new test red. Original row: **`VALIDATE-CSV-RULE-FRONTEND-BLIND-1` — a delimited-only rule fires on every non-delimited Pipeline.** *"csv_settings.date_formats is empty — TRY_STRPTIME will return NULL for any DATE column"* (and its `timestamp_formats` twin) is emitted for **6 of 26** Pipelines that carry **no `csv_settings` block at all**: `json_example`, `excel_example`, `asn1_example`, `fixedwidth_example`, `xml_example` and the parquet re-ingest `orders_by_region_feed`. Each is born `clean:false` for a rule that cannot apply to it, which trains authors to skip warnings — including the real ones the same route emits. Gate the rule on `parsing.frontend == delimited`. → `okf/backend/pipeline-graph/pipeline-config-keys.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-05`)** — the view creation moved inside the guard; a missing reference now refuses 422 `JOIN_REFERENCE_MISSING` carrying the resolved target, the same name the save path uses. Original row: **`TESTRUN-REFERENCE-REFUSAL-UNNAMED-1` — the save path names a missing reference; the test paths leak DuckDB.** On the same `join_step`, the projection PUT refuses with **`JOIN_REFERENCE_MISSING — Node 'join' names no 'reference'`**, while test run and dry-run surface *"Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result"*. One condition, two vocabularies; the second is not actionable by an author. Route the test paths' reference resolution through the same refusal. → `okf/backend/pipeline-graph/editable-round-trip.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-06`) at the SHARED `ControlApi.body` seam, not in the one route that surfaced it** — the 500 was every POST route's defect. A route's own more specific 400 still reaches the caller, pinned by test. Original row: **`DRYRUN-MALFORMED-BODY-500-1` — a bare JSON array as the dry-run body is a 500, not a 400.** The natural first guess for "sample rows" (`[{…}]`) produces `500 Cannot deserialize value of type java.util.LinkedHashMap…`; the contract is `{sampleRows:[…], pipeline?}` (`pipelines.service.ts` `dryRunAuthored`). Every other malformed shape correctly returns `400 at least one sample row is required`. Catch the deserialization at the route and answer 400 with the expected shape. → `okf/backend/engine/pipeline-test-run.md`.
- ~~**P3**~~ · ✅ **FIXED 2026-09-22 (`WB-07`)** — a Dataset-fed Pipeline refuses a file test run 501 and names the Dataset, instead of 200 *“no rows were parsed”*. Original row: **`TESTRUN-DATASET-COLLECTOR-SILENT-1` — a file-based test run over a Dataset-fed Pipeline returns 200 "no rows" instead of refusing.** `orders_by_region_feed` (`collector: dataset`) answered a `{files:[…]}` test run with `200` and *"no rows were parsed from the chosen file(s), so no step could be previewed"*. `pipeline-test-run.md` says non-`local` connectors are **501**; a Dataset feed is not local either, and the honest answer is the refusal, not an empty success. → `okf/backend/engine/pipeline-test-run.md`.
- ~~**P3**~~ · ✅ **CLOSED 2026-09-23** — all four remaining formats now have a domain-shaped demo in `spaces/demo`, each with an **independent** expectation. `c1621799`: `gl_journal` (Excel, `config/ledger/` — 13 journal lines split 5:3:3:2, amounts summing to 0) and `stock_movements` (XML, `config/warehouse/` — 5:4:2 plus 1 skipped). `a6492868`: `msc_cdr` (ASN.1, `config/msc/` — 5:4:3 plus 1 discarded record type) and `in_recharges` (fixed-width, `config/recharge/` — 10:4, amounts summing 177.50), all pinned by `DemoCorpusIngestTest` on the real ingest. `da71cb4c` restored snappy on `in_recharges` once sinks inherited `output:`. ✅ The row's argument held again: building them found `TESTRUN-SEED-IS-MAPPED-OUTPUT-1` and five more rows (filed above). Previously: ⚠ **HALF FIXED 2026-09-22 (`WB-17`)** — `lookup_step` now ships a sample (all three `mappings` plus an unmapped status), so every file-fed Pipeline can be test-run as shipped; `tools/seed-samples.mjs` reports a genuinely empty inbox instead of guessing from directory names. ✅ **`route` now has a domain-shaped demo** (`premed_events` in `spaces/demo`: a pre-mediation event feed routed by record type into voice/sms/other, `active: false`), and building it immediately found `ROUTED-WRITE-COUNTS-PER-BRANCH-1` on a SHIPPED example — the argument for this row in one step. **Still open: four domain-shaped demos** (ASN.1, Excel, fixed-width, XML), the Could-tier bulk. Original row: **`DEMO-CORPUS-FORMAT-COVERAGE-1` — five authoring surfaces exist only as synthetic `spaces/default` examples, and one ships no sample at all.** ASN.1, Excel, fixed-width, XML and `route` have no domain-shaped demo; `lookup_step` has **no sample file**, so the sweep — and any builder — cannot test it as shipped. Every finding above for those surfaces rests on one file each. A realistic demo per surface is what would have found `TESTRUN-SEGMENT-ROUTE-NO-FLOW-1` before a sweep did. → `okf/backend/build-run/build-test.md`.

Carried over 2026-09-23 when the MoSCoW spec (`archived-documents/plans-archive/pipelines-workbench-moscow.md`) was archived — its Should/Could items that no `WB-nn` built. Demand-gated, as every P3 is.

- ~~**P3**~~ · ✅ **CLOSED 2026-09-23 (`e02eeab2`)** — the toolbar run fact is a picker over `GET /provenance/batches` (recent 20, no new storage); picking a run repaints the edge counts and the inspector/drawer (*Selected run* vs *Last run*). Compare = switching runs; a per-edge delta view was not built. Unit-tested only. Original row: **`PIPELINE-RUN-HISTORY-OVERLAY-1` — the canvas overlays the LAST run only.** Moscow `S8`. The run-counts overlay and the *ran* badge read the last batch; an author comparing runs goes to `/runs`. Show more than one run where the overlay already is. → `okf/frontend/features/pipeline-editor.md`.
- ~~**P3**~~ · ✅ **SHIPPED 2026-09-23 (`9eed24f8`)** — below a 1024px viewport (`PipelineEditorComponent.RESPONSIVE_FLOOR_PX`) the palette collapses to its rail; it acts only on a crossing and never overrides the author's own choice. Unit-tested; not preview-driven. Original row: **`WORKBENCH-RESPONSIVE-FLOOR-1` — the three-pane Pipeline shell has no responsive floor.** Moscow `S9`, measured in the postmed build: at ~660px wide the canvas is a sliver. Collapse a side pane below a floor width. → `okf/frontend/features/pipeline-editor.md`.
- **P3** · **`PIPELINE-CONFIG-HISTORY-1` — a Pipeline has no persisted config history.** *(Renamed 2026-09-23 from `PIPELINE-CONFIG-HISTORY-AND-LAYOUT-1`: the layout half SHIPPED in `9eed24f8` — positions in browser `localStorage` per space + Pipeline, applied only when they cover every node, plus *Auto-arrange Steps*.)* Proposal: a server-side snapshot per save + a version list + a diff; it needs a route and a retention decision. Original text, Moscow Could-tier: In-session undo/redo is capped at 50 per tab and lost on reload, so there is no diff of a config across saves; the recomputed layout is fine at four nodes and less so for a routed graph. → `okf/frontend/features/pipeline-editor.md`.

## 5. Docs & hygiene

- ~~**P1**~~ · **`ROUTE-UNGATED-DEFAULT-1`** — ✅ **CLOSED 2026-09-17 — grounded against the code, every remaining item was already decided and shipped.** The ratchet is the boot refusal (§0). Item (a)'s "product decision first" was taken 2026-09-15/16: Incident/Case DISPOSITION (`ack/resolve/transition/assign/merge/split/PATCH`, Case-Rule `evaluate`) is `canAdminister`; opening an Incident (`POST /objects`) is `canManageIncidents`; comments/attachments/links/RCA/tag assignments are recorded `collaboration` / `target-visibility-gated` exemptions; agent governance (`feedback`, `approvals/{id}/decision`, `PUT /agent/policy`, `kill-switch`) is `canAdminister` at `AgentRoutes.java:150,177,194,199`. `CapabilityManifest.PENDING_OPERATOR_CALLS` is EMPTY. The only residue was a stale comment in the manifest still calling `POST /objects` PENDING — corrected. 🔴 **Lesson: this row sat at P1 for two days after its own work finished**, because its head paragraph was never rewritten when the sub-items closed. *(Original head:)* 🔴 **an unlisted route is OPEN, not locked down.**
  ✅ **The blocking half is BUILT 2026-09-15.** `Roles.CAN_ADMINISTER` exists — **one** coarse capability per
  the operator's call, not three per-family ones — so duckle's "unlisted ⇒ admin" rule is **expressible for
  the first time**. `PUT /spaces/{id}` and `DELETE /spaces/{id}` are gated on it; `DELETE /spaces/{id}` was
  the worst single case the audit found, reachable by any authenticated caller with only a
  more-than-one-Space check. Vocabulary is now **eleven**; `security.md` §capability-vocabulary owns it.
  🔴 **And building it REFUTED two of the audit's own "gateable 12" — both were deliberately open, and
  gating them turned the build red.** ⛔ `POST /requirements`: the audit called it *"an inconsistency, not a
  judgement call"* because both its siblings are gated. It is **SEC-7(c)**, deliberate and pinned by
  `ControlApiRequirementTest.triageIsGatedButSubmissionIsOpen` — anyone may raise a requirement, only a
  triager decides. ⛔ `POST /spaces`: it is the **recovery route**. Deleting the last Space leaves a server
  hosting none, and a capability gate there bricks it exactly as the old `writeRoot()` resolution did —
  every route failing, including the one that would recover it. Both reverted; both now say so at the
  registration site, and the recovery guarantee has its own assertion.
  ⇒ ⛔ **Re-ground the remaining 10 of the 12 one at a time before gating them.** An audit's "correctly
  ungated" bucket was reviewed; its "should be gated" bucket evidently was not, and a route being an
  outlier among its siblings is not evidence that the outlier is the mistake.
  **What remains:** (a) the other ~19 inexpressible routes — Incident/Case triage (~15, in the optional
  `inspecto-ops` module) and agent governance (4, `AgentRoutes`); ⚠ **the audit itself says the triage
  family needs a product decision first** — whether Incident triage should stay open even now that
  `canAdminister` exists — so it is NOT simply "gate all 22"; (b) ~~the 10 unverified gateable routes~~ ✅ **GROUNDED 2026-09-15 — and there were ELEVEN, not ten** (`POST /spaces` was §6(a), not §5, so the refutation was subtracted from the wrong bucket). **Result: 5 GATE · 3 deliberate exemptions · 3 operator calls** — per-route verdicts in the audit's §5 GROUNDED table. 🔴 Two of the five are a **request-forgery-shaped pair** (`POST /assist/settings` writes a server-wide `baseUrl`, `POST /assist/settings/test` calls out to it) and ⛔ must be gated in ONE commit; `inspecto-agent` IS staged, so unlike §6(c) this one is live in shipped bundles. 🔴 **And the grounding produced a REFRAMING that outranks the five: the enforcement model is fail-OPEN** — `withCapability` is opt-in and an undeclared route is simply open, so fixing routes one by one passes a point-in-time review and fails a Type II window. ⇒ the P1's remaining work is now **`archived-documents/plans-archive/route-gating-compliance-plan.md`** (2026-09-15, operator: *"goal is to pass compliance"*): step 1 gate the five (✅ **SHIPPED 2026-09-15** — all five, verified in a clean worktree because the shared tree carried a peer's nine uncommitted engine files that turned the same reactor red in classes this change never touched) · step 2 every mutating route ends as a capability OR a categorized exemption · **step 3 flip the default** (a marked `Gated` handler gives the router the capability with zero call-site changes; undeclared mutating ⇒ refuse at boot AND fail CI) · step 4 capability on audit events, one inventory event per boot, and a **derived, CI-enforced** evidence report. ⛔ Step 3 waits for step 2 — *ratchet last* still holds; ✅ **steps 4a/4b SHIPPED 2026-09-15** — an `ACCESS_DENIED` row names the capability that was missing, a permitted `AUDIT` write carries the one it passed, and absence means *not checked* (Personal stamps nothing); as-built in `okf/backend/control-plane/events-metrics.md`;
  (c) ✅ **operator decided 2026-09-15: record the exemption reasoning for the 49 correctly-ungated routes**
  in the audit doc, so the ratchet starts from a reviewed baseline; (d) the ratchet itself, **last**.
  → `superpower/route-gating-audit.md` · `okf/capabilities/security/security.md`

  *(Original row, for the grounding it still carries:)*
  🔴 **— 75 mutating routes
  are ungated, `DELETE /spaces/{id}` among them.** Filed 2026-09-15 from duckle candidate S5 ("a route with
  no entry in the permission table requires admin, so a later-added route is locked down rather than left
  open"). **Grounded, not assumed** — measured on this tree:
  ✅ **FULL AUDIT DONE 2026-09-15 (operator chose "audit all first") → `superpower/route-gating-audit.md`.**
  🔴 **It reframes the row: this is a VOCABULARY gap, not 83 oversights.** Of the 83 ungated mutating
  routes, **only 12 can be gated with an existing capability**; **22 cannot be expressed at all** (three
  families: Space lifecycle, Incident/Case triage, agent governance); the remaining 49 are correctly
  ungated (identity flow, self-verifying public, self-service, read-shaped POSTs) or self-limiting.
  🔴 **And `Roles` has NO admin capability** — ten exist, none means "administrator" — so duckle's rule as
  stated (*"an unlisted route requires admin"*) **is not expressible today**. That decision comes first.
  ⛔ **Do NOT ship the ratchet first**: it would freeze 83 unreviewed exemptions into a baseline.
  ⚠ **CORRECTED COUNTS 2026-09-15.** This row first said "~223 ungated, 75 mutating". The true figures are
  **332 registrations · 91 gated · 241 ungated · 83 mutating** — the first pass used an ad-hoc regex that
  matched only 304 of 332 and **missed `PATCH` entirely**. The audit re-derives them with the shape
  `CapabilityManifestTest` already trusts. ⇒ *the probe was wrong in the direction that understates.*
  - **332 route registrations · 91 gated · 91 manifest entries ⇒ 241 ungated**, of which **83 MUTATE**
    (POST/PUT/PATCH/DELETE). Capability enforcement is **opt-in**: `ApiContext.requireCapability` runs only when a
    handler is wrapped in `withCapability`, and `CapabilityManifest.capabilityFor` documents `null` =
    "the route is ungated" as a legitimate outcome (`CapabilityManifest.java:157-164`).
  - ⛔ **The other gate does not cover it.** `ControlApi.authorize` (`:809-821`) is ABAC via
    `AccessDeciders.active()`, which resolves EMPTY on Personal **and Professional** (`AccessDeciders.java:23-30`
    — neither ships a `META-INF/services` registration), so on Professional that stage returns immediately.
  - 🔴 **Verified example, opened and read rather than inferred:** `api.delete("/spaces/([^/]+)", …)`
    (`SpaceRoutes.java:72`) has **no capability gate** — `deleteSpace` (`:125-139`) checks only
    `requireMultiSpace`, id validity, and a last-space-purge 409. ⇒ on Professional **any authenticated caller
    can deregister a Space**. ⚠ Other entries in the 75 are legitimately ungated (`/auth/exchange|refresh|
    logout` ARE the login flow; the `preview`/`test`/`probe` POSTs are read-shaped) — **the number is not a
    count of defects**, and triaging which of the 75 should be gated is the operator's call.
  - ⛔ **`CapabilityManifestTest` cannot catch this and is not at fault**: it asserts the manifest and the
    `withCapability` call sites agree with each other, bidirectionally. A route in **neither** is a third
    case it has no notion of. ⇒ *a guard's scope is a silent exemption* — the same lesson as
    `guard-scope-is-a-silent-exemption`.
  **Order recommended by the audit** (its §"Recommended order"): (1) decide whether an **admin capability**
  should exist — everything else is downstream; (2) gate the **12 gateable-now** routes, starting with
  `POST /requirements`, which is an inconsistency rather than a judgement call (its own sibling
  `/requirements/{id}/decision` is already gated); (3) decide the **three families** worst-first — Space
  lifecycle, then agent governance (the kill-switch and policy routes are the controls over what the agent
  may do, and nothing gates them), then Incident triage; (4) **only then** the build-time ratchet, on the
  scanner `CapabilityManifestTest` already trusts; (5) leave the 34 read-shaped POSTs until reads have a
  posture — gating them without gating GET would be incoherent.
  → `CapabilityManifest.java:157` · `ApiContext.java:168` · `SpaceRoutes.java:72` · `AccessDeciders.java:23`

  ✅ **THREE DECISIONS 2026-09-15 — the row's whole decision surface is now closed; what is left is
  work.**
  **(1) Incident/Case triage: gate the STATE-CHANGING routes only.** Resolve/close/assign/reopen/
  promote require `canAdminister`; comment/annotate and read-shaped triage stay open, with the
  exemption recorded. ⇒ triage is treated as daily operator work, but changing an Incident's
  disposition is administrative. ⚠ This needs a **per-route classification pass over the ~15**, which
  the audit wanted regardless — it is not a blanket flag.
  **(2) Reads stay OPEN by design, stated as policy.** Confidentiality sits at the Space/ABAC layer,
  not at capability gating. ⇒ **the ratchet covers the 83 MUTATING routes only**, and the 34
  read-shaped POSTs are **EXEMPT as reads** — ⛔ not "deferred", which is how they would quietly
  become a second unreviewed bucket. This also settles the audit's step (5), which had parked them
  indefinitely.
  **(3) The remaining 10 gateable routes: GROUND ALL TEN FIRST, report per-route, then code.**
  ⛔ No gating commits until that pass is done. The reason is measured, not cautious: of the first
  four attempted, **two were deliberate exemptions** — and one of those two (`POST /spaces`, the
  recovery route) produced **no red at all**, so "gate it and see if tests fail" would have shipped
  the brick. ⚠ A route being an outlier among its siblings is not evidence that the outlier is the
  mistake.
  ⇒ **Remaining order:** ground the 10 → gate them → classify and gate the ~15 triage routes →
  record the 49 exemptions → **the ratchet LAST**, over mutating routes only.

  ✅ **STEPS 2b + 2c SHIPPED 2026-09-15 — "ungated" is now a RECORDED state, and the build fails on a mutating
  route in no state.** `CapabilityManifest` gained `record Exemption(method, pattern, category, reason)` +
  `EXEMPTIONS` (**60** rows, categories = the audit's own bucket taxonomy plus `collaboration`, the word the
  plan's 2b used) and `record Pending(method, pattern, question)` + `PENDING_OPERATOR_CALLS` (**4** rows).
  `CapabilityManifestTest.everyMutatingRouteIsGatedExemptOrPending` scans every `api.post|put|patch|delete`
  registration in every reactor module (**77** ungated + the gated ones) and asserts each is in EXACTLY one of
  ENTRIES / EXEMPTIONS / PENDING, that nothing exempt or pending is unregistered, and that PENDING can only
  shrink (≤ 4). Mutation-verified: a never-registered exemption turns it red on the stale-route assertion.
  **2b, the triage classification (`ObjectRoutes`, `NoteRoutes`)**: 8 routes now take `canAdminister` — `ack`,
  `resolve`, `transition`, `assign`, `merge`, `split`, `PATCH /objects/{id}` (it edits priority / severity /
  assignee — disposition) and **`POST /cases/rules/{id}/evaluate`**; 7 stay open as `collaboration` —
  comments, attachments, links (POST + DELETE), RCA seed, and the two `/notes/…` routes.
  🔴 **Two findings from reading the handlers rather than the audit:** (1) `/cases/rules/{id}/evaluate` was
  in the audit's §4 "read-shaped" bucket; it **opens a Case** (`ObjectService.CaseRuleEvaluation.opened`), so
  that bucket was wrong for it and it is gated, not exempt. (2) `/expectations/evaluate` and
  `/expectations/{id}/evaluate` are exempt as read-shaped **with the caveat written into the reason**: a breach
  may open an Incident, so they are re-classified together with the pending Incident-creation call.
  ⚠ **`POST /objects` (create) joined PENDING** — it is the same question as `POST /recon/promote` (*which
  family does manually opening an Incident belong to?*); gating it under `canAdminister` would have decided
  an operator question by side effect. ⇒ PENDING is now **four**, not three, and §1's owed-input row says so.
  ⚠ `ControlApiScopedObjectsTest`'s Subjects gained `canAdminister`: that class tests the data-scope guard
  BENEATH the new gate, and the gate wraps the guard (403 before the existence-hiding 404).
  `ControlApiTriageGateTest` (new, real HTTP) pins both halves against a Subject that HOLDS a capability but
  not this one — an open route proven open with no Subject at all proves nothing, since no check runs then.
  ⇒ **What remains: 2a (four operator calls, §1) → step 3 (the fail-closed default: `Gated` handler marker +
  boot refusal + `absent-module-stub`) → 4c/4d/4e.** ⛔ Step 3 cannot land while PENDING is non-empty — a
  boot refusal over four undecided routes either bricks them or forces them into the table unreviewed.
  → `CapabilityManifest.java` · `CapabilityManifestTest.java` · `ObjectRoutes.java:56-75` ·
  `superpower/route-gating-audit.md` §"Step 2 as-built" · `okf/capabilities/security/security.md` §capability-vocabulary

- ~~**P2** · **`DUCKLE-C3-DEAD-PROPERTY-1` — a config key no component reads must FAIL validation.**~~
  ✅ **ROW CLOSED 2026-09-17 — this row's own scope is complete.** Live-grounded (not read off the prose):
  `POST /config/write` (and `/config/patch`) 422 with `ERR_UNKNOWN_CONFIG_KEY` on an unrecognized key for
  ALL FOUR completed census kinds — `pipeline`, `alert`, `meta`, `enrichment` — proven over real HTTP.
  `ControlApiDeadPropertyTest` already covered `pipeline` live; `aDeadAlertKeyIsRefused`,
  `aDeadMetaKeyIsRefused` and `aDeadEnrichmentKeyIsRefused` were added to close the gap that the other
  three kinds were only unit/ratchet-tested (`AlertKeyCoverageContractTest`, `MetaKeyCoverageContractTest`,
  `EnrichmentKeyCoverageContractTest`) and never proven over the actual route — all 8 tests pass
  (`inspecto` module, `ControlApiDeadPropertyTest`, 8/0/0/0). No enforcement gap found; nothing else to fix.
  **Everything else this row ever touched has its own home, and none of it is this row's remaining scope:**
  `job` and `schema` are ruled out on enumerability (reasons above, unchanged); `widget`/`dashboard`/
  `expectation` moved to `COMPONENT-KIND-KEY-CENSUS-1`; the job residual is `JOB-PARAM-UNDECLARED-UNREPORTED-1`;
  the expectation residual is `EXPECTATION-SPEC-STALE-VS-CONDITION-1`; the `RecipeCompiler` WARNING seam and
  `PipelineGraphRoutes` migration-pass blocker remain named but unbuilt deferrals, not open threads of THIS
  row — `superpower/dead-property-validation-plan.md` can retire once those are triaged onto their own rows
  or explicitly dropped.
  (superseded history below)
  ✅ **THE CENSUS HALF OF THIS ROW IS CLOSED 2026-09-17. `AcceptedConfigKeys` is DONE at FOUR types** —
  `pipeline`, `alert`, `meta`, `enrichment`. `widget`/`dashboard`/`expectation` belong to
  `COMPONENT-KIND-KEY-CENSUS-1`; `schema` and `job` are **ruled out on enumerability**, with reasons.
  🔴 **`job`'s stated blocker was REFUTED, and the causality was backwards.** The row said it was "blocked
  on a job-type registry". **The registry exists** (`JobTypeRegistry` + `JobTypeProvider`/
  `JobTypeDescriptor` in `inspecto-engine`, served by `GET /jobs/types`) — ⛔ **and its existence is what
  rules the census OUT, not what would enable it.** `JobConfig.fromMap` funnels every non-frame key into an
  open `params` bag, so the accepted set is **per job type**, and per-type is not statically enumerable
  four ways: the registry is **runtime-mutable** (Job Packs register *and deregister*), **extensible by
  `ServiceLoader`** (`inspecto-ops` ships `caserule.evaluate`/`objects.analytics`, and `spaces/demo`
  commits a config using the latter), **config-derived** for `sql.template` (the `$name` tokens in the
  authored SQL *are* its parameter contract), and **edition-varying** (`maintenance`'s task list comes from
  whatever the classpath contributed, so a static table would 422 valid Enterprise configs on Personal).
  ⚠ **Measured, not argued: 34 of 34 committed job configs carry ≥1 non-frame key**, across 34 distinct
  param keys — a frame-only census refuses every one of them, and a top-level-only census catches nothing.
  **Both available granularities are wrong and there is no third.** ⇒ That is also why `JOB_PATH_KEYS` is a
  hand-maintained list rather than a derived one. The false rationale is corrected in
  `AcceptedConfigKeys`'s own javadoc.
  ⇒ Residual filed: `JOB-PARAM-UNDECLARED-UNREPORTED-1`. ⚠ Also found, NOT fixed (a parallel lane owns that
  file): `ConfigSpecs.job()` describes the built-ins as *"enrich, report, maintenance, pipeline"* — there
  are **ten**.
  ✅ **THE `expectation` KIND LANDED 2026-09-16 (`300e8c7a`, BREAKING) — and the component census now
  covers THREE kinds** (`widget`, `dashboard`, `expectation`) beside the four in `AcceptedConfigKeys`.
  ⇒ **`AcceptedConfigKeys` has exactly ONE type left: `job`.**
  🔴 **The reframing that sent that work was right but UNDER-COUNTED THE ROUTES.** TWO routes persist an
  expectation and they lost keys in **opposite** ways: `/expectations` rebuilds from `toMap()` so an
  unknown key was **silently dropped** (200, gone, no diagnostic); `/components/expectation` persists the
  **raw body**, so the key was **stored dead** and then served back by `GET /expectations`. Proven live
  before anything changed. ⛔ Gating only the named route would have left the back door open. Both now
  share one `ComponentRoutes.refuseUnknownComponentKeys`.
  ⚠ **The envelope is NINETEEN keys, not the three the reframing named** — dumped empirically over real
  HTTP rather than read off `toMap()`. 🔴 **`when` is the `dashboard.description` trap repeating:**
  `ConfigSpecs.expectation()` predates the 2026-07-18 `condition` promotion, so a spec-derived accepted set
  would have 422'd every condition expectation **and the Studio's own save shape** (mutation-proven).
  ⚠ **Recorded because it weakens the guard:** the two enforcement sites are INDEPENDENT, so dropping
  `expectation` from `CENSUSED_COMPONENT_KINDS` turns only ONE test red. Each site carries its own test.
  ⇒ Residual filed: `EXPECTATION-SPEC-STALE-VS-CONDITION-1`.
  ✅ **THE `enrichment` TYPE LANDED 2026-09-16 (BREAKING) — census is now FOUR of nine**: `pipeline`,
  `alert`, `meta`, `enrichment`. Ratchet `EnrichmentKeyCoverageContractTest` (12 tests);
  `ENRICHMENT_PARSER_ONLY = {references}`; censused parents `input`/`output`/`triggers` — which is where
  the value is, since a top-level-only census would accept `input: {databse: …}` whole.
  🔴 **This row's premise was wrong for enrichment, and so was the OKF doc:** both listed several
  "undeclared-but-engine-read" keys, but `ConfigSpecs.enrichment()` declares all of them except **one**
  (`references`). That is why enrichment was cheap — not because it was next in line. ⚠ Every other reader
  of an enrich map was checked too (`PipelineGraphRoutes`, `PipelineBundleRoutes`, `PipelineRenameRoutes`),
  not just the parser.
  ⚠ **All four mutations went red here, unlike `meta`** — adding/removing a censused parent DOES fail,
  because `input`/`output`/`triggers` have declared sub-blocks. And the falsify-the-scan test earned its
  place for real rather than by mutation: the first run failed because the nested scan matched only
  `local.get("…")` and missed reads going through a `req(in, …)` helper.
  ⛔ **`schema` and `expectation` should be RE-FRAMED, not merely "still needs a census".** `schema` has no
  single parser — a dozen readers across `inspecto-etl` — so the ratchet idiom cannot be written for it.
  `expectation` is authored through `/expectations*` and its persisted content carries
  `lastResult`/`createdAt`/`updatedAt`, so it belongs under `COMPONENT-KIND-KEY-CENSUS-1` beside
  `widget`/`dashboard`. ⇒ **On that reading `AcceptedConfigKeys` has exactly ONE genuinely remaining type:
  `job`** (still gated on a job-type registry).
  ⇒ Regression found and filed separately: `PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1`.
  ✅ **THE `meta` TYPE LANDED 2026-09-16 (`c3b3fc5f`, BREAKING)** — census now covers **three of nine**:
  `pipeline`, `alert`, `meta`. `meta` has **no parser-only list at all**: all five of `SemanticModel.load`'s
  top-level reads are already spec-declared. Ratchet: `MetaKeyCoverageContractTest`. Verified in the MAIN
  checkout — `inspecto-config` 155/0/0/0, `inspecto-engine` 1656/0/0/0, both new test classes observed to
  RUN, both mutation-proven RED first.
  ⚠ **The subtlety worth keeping:** `SemanticModel.load` DOES call `entrySet()` three times — but one
  level **down**, over `tables`/`kpis`/`reports`, whose keys are **author-invented names**. So `meta` is
  censused at the top level and deliberately is **not** a censused parent; descending would refuse every
  KPI anyone names. ⛔ **A scan that merely asked "does this file contain `entrySet`?" would have refused
  the type for the wrong reason** — apply the reusable test at the granularity the checker actually uses.
  🔴 **Recorded because it weakens a guard I shipped:** adding `meta` to `censusedParents` *alone* does
  **not** go red — `unknownKeyFindings` skips a parent with no accepted sub-blocks, so the descent-guard
  test cannot return a hit for that mutation on its own. The scope limit is written into the test comment
  rather than left to read as a stronger guard than it is.
  ⛔ **`widget` and `dashboard` are STRUCK from "the remaining seven"** — they never reach `/config/write`;
  see the new `COMPONENT-KIND-KEY-CENSUS-1`. ⇒ **Still open: four types** (`enrichment`, `job`, `schema`,
  `expectation`), plus the unchanged `RecipeCompiler` WARNING seam and `PipelineGraphRoutes` blockers,
  both of which were re-grounded 2026-09-16 and are still genuinely blocked.
  ✅ **THE `alert` TYPE LANDED 2026-09-16 (`f0ad2ffc`, BREAKING), verified 16 modules / no skips.** The
  census now covers **two of nine** config types: `pipeline` and `alert`. An `alert` config carrying a key
  that neither `ConfigSpecs.alert()` declares nor `AlertRule.fromMap` reads now produces an unknown-key
  finding where it previously validated clean. `CENSUSED_PARENTS` became a per-type `censusedParents(type)`,
  because the whole alert file is ONE block and a census stopping at the top level would accept every alert
  config whole and catch nothing.
  🔴 **Why `alert` could be censused and the other seven still cannot — this is the reusable test.** The
  fail-open is not squeamishness: deriving an accepted set from `ConfigSpecs` ALONE would refuse the keys a
  hand-written parser reads but the spec never declared. That objection is answerable exactly where the
  parser's reads are **ENUMERABLE**, and `AlertRule.fromMap` is a flat literal list of ten `alert.get("…")`
  calls with **no dynamic key access** (verified: `keySet`/`entrySet` appear nowhere in the class). Seven of
  the ten are spec-declared, three sit in `ALERT_PARSER_ONLY` ⇒ ten reads, ten accounted for, so no key the
  parser reads can be wrongly refused. ⛔ **Apply that same test before censusing any further type.**
  ⚠ **`job` may be the one type that can NEVER be censused**: it funnels every unrecognised key into an open
  `params` bag, so it needs a job-type registry first. `expectation`, `schema` and `enrichment` each have
  confirmed undeclared-but-engine-read keys today.
  ⚠ **Expectations are not protected by this at all** — `Expectation`'s own record constructor is the whole
  validator, so `AcceptedConfigKeys` never sees them (found while grounding `DUCKLE-C8`).
  ⚠ A new key must be **DECLARED in `ConfigSpecs`** or added to `ALERT_PARSER_ONLY`, never both — that rule
  bit immediately: `alert.maximumAge` (the freshness shape, `a9c97369`) is declared, deliberately.
  ⇒ **Still open:** the remaining seven types, and the plan's §4 deferral (the WARNING seam at
  `RecipeCompiler`). → `superpower/dead-property-validation-plan.md` stays ACTIVE for those.
  (original row) — Adopted
  by the operator 2026-09-15 from duckle §1 C3.

  ✅ **~TWO-THIRDS BUILT 2026-09-16 (`2c310d1c`) — the row STAYS OPEN.** Shipped: the accepted-names map
  (`AcceptedConfigKeys`), near-name suggestion **and** the no-suggestion case, the `x-` escape hatch,
  `ERR_`/`WARN_UNKNOWN_CONFIG_KEY`, the STRICT seam on `/config/write` **and** `/config/patch`, and the
  generated accepted-names table with a drift test. 🔴 **The row's grounding missed that the census ALREADY
  EXISTED** — `PipelineKeyCoverageContractTest.UNDECLARED_BLOCKS` (2026-08-31) already derived *"blocks the
  parser reads that the spec does not declare"* from source, so `declared ∪ parser-only` **was** the map. It
  was **moved into production** and the ratchet re-pointed at that field — one list, enforced by the checker
  and rendered into the doc, so the doc cannot drift from the gate. A second hand-written table would have
  been the exact drift the ratchet exists to stop. ⛔ **Granularity is the BLOCK, and that is a correctness
  constraint, not a shortcut**: descending on "this block has a declared sub-block" flagged `dirs.quarantine`
  and `dirs.markers`, which the engine reads — caught by a failing test, **not by review**. `dirs`,
  `collector`, `parsing`, `output` and `reference` are accepted whole. ⚠ **Partial refutation of the row's
  wording**: "the map MUST be per node type" does not fit the strict seam — `/config/write` and
  `/config/patch` carry a **flat config keyed by `type`**, never graph node types, and `collect:` reaches no
  write route at all (`RecipeCompiler.compile` is called only from `ConfigMigrator.java:156` + tests). The
  `collect:` constraint is honoured because `collector` is accepted whole, pinned by
  `RecipeCollectRoundTripTest`. ⚠ **BREAKING**: `version:` and `source:` — the two keys
  `pipeline-config-keys.md` itself lists as *"appear in docs, read by nothing"* — now **422** at
  `/config/write`; configs carrying them previously saved silently. Recorded in `api-stability.md` under the
  pending MAJOR. **Remaining, each with its blocker named rather than hand-waved:** the `RecipeCompiler`
  WARNING seam — ⛔ blocked because `PipelineCompileException.Refusal` has **no non-fatal channel** and
  building that sink is bigger than the warning itself; `PipelineGraphRoutes` — blocked on a config
  migration pass, since `lower` preserves unmodeled keys and legacy files would start 422-ing on untouched
  blocks; and a census for the other **eight** config types, fail-open by omission today.
  → `superpower/dead-property-validation-plan.md` §4/§6 Stable error code + near-name suggestion (no suggestion
  when nothing is close); strict at validate, warning at run; `x-` keys round-trip untouched; and the
  accepted-names doc is **generated from the same map the checker enforces**, so it cannot drift.
  🔴 **Strongest case of the eight adopted**: `PROJECT_NOTES` records several past **silent config-loss**
  defects that are exactly this class, and today's behaviour is inconsistent three ways — `ComponentStore`
  refuses unknown keys, `RecipeCompiler`'s own comment admits others "stay", `ArgumentDeriver` silently
  drops. ⇒ the value is turning a silent loss into a refusal. → `ConfigSafetyValidator` · node attribute
  specs / `step-types.contract.json`

  🔴 **GROUNDED 2026-09-15 (not built) — there is NO enforcement point today, and the "same map the checker
  enforces" does not exist yet either.** `ConfigSafetyValidator.check` inspects a fixed list of dangerous
  fields and never iterates `raw.keySet()`; `NodeAttributes` (mirrored 1:1 into
  `node-attributes.contract.json`, drift-pinned by `NodeAttributesContractTest`) IS a type → accepted-keys
  table but is advisory/UI-only — its javadoc says an absent type *"falls back to the dialog's free-form
  key/value editor"*; `ConfigSpecs` validates envelopes with explicit open-map escape hatches (viz channels).
  `ComponentStore.encode` (`:186-230`) is the one place that already does this row's job — allow-lists +
  `IllegalArgumentException("… cannot persist key(s) …")` — but only for CSV-backed component kinds, and it
  frames itself as fixing *"this repo's recurring loss mode"*. No `x-` convention and no edit-distance helper
  exist anywhere. ⇒ **The seams:** strict = a new `Finding` producer in `ConfigWriteRoutes` (write `:69-84`,
  patch `:364+`), the list every ERROR→422 finding already rides; warning = `RecipeCompiler` at compile.
  ⛔ `RecipeCompiler:254-260` explicitly REFUSED a blanket unknown-key refusal on `collect:` blocks because
  `RecipeConverter` round-trips arbitrary collector keys — so the accepted-names map must be per node type
  and must include the converter's pass-through keys, or the gate breaks the flat→graph round trip.
  ⚠ `Finding` already carries an optional stable `code` (`FindingCodes`, `ERR_`/`WARN_`), so "stable error
  code" is infrastructure, not new design. Size: a §3 feature (new map, generator, two seams), not hygiene.

- ~~**P3** · **`COLUMN-TYPE-SECOND-INTERPRETER-1`**~~ ✅ **SHIPPED 2026-09-17 — it was ONE substring bug, not
  five product decisions.** `dbColumnType` matched with **unanchored** regexes over the whole string, so `INT`
  matched inside `INTERVAL`, `BIGINT[]` and `STRUCT(a INTEGER)`, and `TIME` inside `MAP(VARCHAR, TIMESTAMP)`.
  Nobody chose those buckets. The body is now `ResultSetDescriptor.columnType`'s algorithm token for token
  (including the `cut > 0` guard), with the already-coarse pass-through kept as explicit cases because
  `/db/query` sends pre-normalized names and that is the LIVE path.
  ✅ Pinned to `column-role.contract.json`'s `duckdbTypeCases` — the same fixture the Java
  `ColumnRoleContractTest` reads — with a length guard so an emptied fixture cannot make the loop vacuous.
  ⛔ The contract was NOT widened and the Java reader was NOT touched, which is what keeps this inside the
  row's own ⛔.
  🔴 **The recorded rationale was false.** `column-role.spec.ts` justified the divergence with *"the client
  never maps DuckDB names for a stored Dataset"* — contradicted by `dataset-rows.service.ts` in the same folder.
  Paragraph deleted. ⇒ **a stated blocker is a hypothesis too.**
  ⚠ **Severity corrected DOWN, honestly:** all three callers hit `/db` routes that already return the coarse
  names; raw DuckDB spellings reach the client only on `ops:*` groups, which no caller uses. An earlier claim
  that `INTERVAL` was a LIVE broken filter was overstated — this is defence-in-depth on a live seam.
  ⚠ Left open deliberately: under Postgres, `ops:*` groups return `int4`/`int8`/`float8`, which BOTH readers
  now map to `string` (the old regex got them right by accident). Unreachable today; fixing it means widening
  the contract. Original row follows.
  - **P3** · **`COLUMN-TYPE-SECOND-INTERPRETER-1` — a second client-side reader of the same DuckDB type
    spellings, and it disagrees** (filed 2026-09-16 from `TYPEFLOW-DATASET-COLUMNS-1`'s closing pass).
    `inspecto-ui/src/app/inspecto/query/query-columns.ts:60` `dbColumnType` interprets the same DuckDB type
    names as the Java `ResultSetDescriptor.columnType` and **disagrees on four**: `BIGINT[]`, `STRUCT(...)`
    and `MAP(...)` → `number` where Java says `string`, and `LOGICAL` → `string` where Java says `boolean`.
    ⚠ **It is NOT unguarded** — it is pinned as a **documented exclusion** in both `column-role.spec.ts` and
    the derivation plan, deliberately, because it serves the **query builder** and not stored Datasets, so
    reconciling it is a query-builder behaviour change rather than a contract widening. ⛔ Do not "fix" it by
    widening the column-role contract: that would silently change what the query builder renders. The
    decision owed first is whether the two readers should share one vocabulary at all.
    → `okf/backend/engine/catalog-vs-executors.md`

- ~~**P3** · **`ACQUIRE-LEDGER-SHARED-URL-1`**~~ ✅ **BUILT + VERIFIED 2026-09-16** (3805/0/0/8 in the MAIN checkout, `ServiceBootstrapLedgerTest` 3/3 observed to RUN). Fixed at `ServiceBootstrap.buildFrom` by resolving through `OperationalDb.urlFor` and registering, gated on `root.config() == null` so a per-space boot does not double-register. 🔴 **Reachability is what made it a live bug, not a theoretical one:** `SpaceBootstrap.java:39` was the ONLY registration site in the repo, and the legacy/CLI space never passes through it (`SpaceManager.single():74-79` ← `ServiceBootstrap.build:41` ← `CollectorService.fromArgs:1815`) ⇒ a single-tenant Professional deployment on a shared operational DB kept its dedup ledger in a local working-directory DuckDB file, SILENTLY, while every other family moved. ⚠ **The parent row's Maven cycle is REAL and was re-confirmed** (`inspecto/pom.xml:96`; `inspecto-acquire` is a leaf and `SpaceRoot` is invisible to it) — it does NOT block this row, because the resolution happens on the `inspecto` side and never inside the leaf. ⇒ **That distinction is the lesson: a refuted PARENT does not refute a child row; ground the child's own seam.** Original row follows.
  - **P3** · **`ACQUIRE-LEDGER-SHARED-URL-1` — a second source of truth for the ledger URL** (filed
  2026-09-16 from `ACQUIRE-LEDGER-DUPLICATE-RESOLUTION-1` — which stood here until it was refuted and swept
  to [`okf/backend/engine/db-layer.md`](okf/backend/engine/db-layer.md) §5.0-b — and genuinely distinct
  from it: it is the **URL**, not the backend).
  `AcquisitionLedgers.shared()` (`:39-41`) lazily builds from
  `System.getProperty("acquire.ledger.db.url", DEFAULT_DB_URL)`, **bypassing `OperationalDb.urlFor`** — so
  a space that was never run through `SpaceBootstrap` resolves a DIFFERENT url: no `-Dinspecto.db.url`
  shared fallback, and a working-directory-relative `jdbc:duckdb:inspecto-acquisition.db`.
  ⚠ Unlike its parent row this one does **not** require crossing the module boundary — `urlFor` is already
  called from `SpaceBootstrap`, so the question is which seam `shared()` should use, not where the
  declaration lives. ⛔ Ground it before starting: the parent row was wrong three times.

- **P2** · ✅ **`DUCKLE-C1-DATASET-FRESHNESS-1` — CORE SHIPPED + VERIFIED 2026-09-16** (all 16 modules,
  no skips; `FreshnessAlertTest` 8/8, `DatasetFreshnessProbeTest` 5/5, `AlertKeyCoverageContractTest` 3/3).
  Freshness is a new Alert Rule shape — `dataset:` + `maximumAge:` (`Ns/Nm/Nh/Nd`) — evaluated each sweep
  against the last `dataset.write` Signal (`DatasetFreshnessProbe`).
  🔴 **The row's own substrate was REFUTED:** a Dataset is a READ (`DatasetRelation:53-90`) with no
  producing pipeline and **no schedule**, and `CatalogOverlay:60` gives `NodeKind.DATASET`
  `OperationalOverlay.NONE` ⇒ no `latestRunTime`. ⛔ So **`expectedAfterSchedule`, and “a disabled or
  missing schedule makes the Dataset stale”, are NOT BUILDABLE as written** and are not built — reopen only
  behind a Dataset→producer link, which does not exist.
  ⚠ **“A failed or partial run does not count as a refresh” needed NO code** —
  `DATASET-PUBLISH-ON-FAILURE-1` already moved `dataset.write` to after the whole chain completes, so the
  check INHERITS it. ⛔ Building a second implementation would have been a second answer to one question.
  ✅ **An all-clear EXISTS now — the first recovery path `AlertService` has ever had.** `clear(...)` emits
  `ALERT_CLEARED` + an INFO `alert-rule.cleared` Signal on the same correlation key as `alert-rule.fired`,
  is never cooldown-held, and **RESETS** the firing key so a second outage inside the first cooldown still
  alerts. ⛔ **It cannot resolve the managed ALERT object**: `ObjectAccess` has `open`/`hasActive*`/`link`
  and **no transition method at all** — adding one is a design pass on that interface, deliberately not
  smuggled in here. `stale_since` is in-memory ON PURPOSE (the `stale-tiles.ts:1-34` objection stands).
  🔴 **A defect was found ONLY by running the tests, and it is the lesson of this batch.** The lane
  reported “core shipped” on a clean **compile**; the first real run was **5 errors** — `AlertService`'s
  ledger loop processed freshness rules, which carry no `window:` by construction, so
  `inWindow → batchWindow` NPE'd on a null window. `isMeasureRule()` does NOT cover them: it was narrowed
  to `dataset != null && maximumAge == null` because BOTH shapes use `dataset:`, so the skip had to be
  stated separately. ⛔ **A compile is not a verification**, and a worktree cannot supply one here
  (`REACTOR-HALT-IS-A-SILENT-PASS-1`).
  ✅ **ALL FOUR RESIDUALS TRIAGED 2026-09-20; one of them BUILT.** Verdicts, in the order they were listed:

  1. ✅ **The once-a-minute dedicated thread — REFUTED and CLOSED, replaced by a narrower sweep.** The
  constraint duckle wrote ("on its own thread, not the scheduler's") is already met without a thread:
  `AlertEvaluateJob:33` is the cadence seam, `CronExpression:16-20` accepts a minute field so
  `* * * * *` is legal today, and the Job body runs on `JobService:135`'s virtual-thread executor —
  `Scheduler:46`'s two `inspecto-scheduler` threads only TRIGGER, so a long sweep never blocks the
  timer, and a fire landing on an in-flight run records SKIPPED (`JobService:66-68`). ⛔ **A dedicated
  thread would duplicate a cadence that already exists, which is worse than none.**
  🔴 **What the minute cadence actually cost was SCOPE, not threading** — and that is now fixed.
  `evaluateRules()` also runs the full per-pipeline ledger pass (`AlertService:226-249`, one
  `status.batches(cfg)` read per Pipeline), so putting it on a one-minute timer to compare a clock
  re-read every Pipeline's ledger 60× an hour. **SHIPPED 2026-09-20:**
  `AlertService.evaluateFreshnessRules()` (freshness pass only, still fires/cools down/clears — narrower,
  not gentler) · `AlertAccess.evaluateFreshnessRules()` (a **default** that answers with the FULL sweep, so
  a stand-in without a narrow form does more rather than less and can never answer with a silence it did
  not earn) · `alert.evaluate` gains `scope: freshness`; any other value and none keep today's behaviour
  exactly. Pinned by `FreshnessAlertTest` 11/11 — the new cases assert **zero** ledger reads through a
  counting `StatusStore`, that the grant does not widen the sweep, and that an absent engine still fails
  loudly. ⚠ The one residue is **ownership, not threading**: nothing forces a `maximumAge` rule to have a
  minute-cadence instance armed. Auto-arming one when a freshness rule exists is the follow-up, and it is a
  scheduling-policy question, not a thread.
  2. ⛔ **Owner-routed alerting — STILL BLOCKED, and the "blocker is answered" clause above overstates
  what was decided.** The 2026-09-15 operator decision settled the *design* (owner = the authenticated
  `Subject`, `"appUser"` where none), and that remains the answer — ⛔ do not re-open it. But the
  *substrate* is absent and the decision did not create it: `AlertRule:60-62` has no owner component;
  `NotificationRule:112-113` still hardcodes the recipient; `ChannelConfig:23-24` routes by a flat
  `target`, with no per-owner destination. 🔴 **And the decision does not reach this code path at all** —
  `Subject` (`Subject.java:26`) is attached per HTTP request by `ControlApi#dispatch` and is "absent
  entirely on Personal edition", whereas Alert Rules are loaded from config at service construction
  (`CollectorService:340-353`) and evaluated on a background sweep with no exchange. ⇒ there is no moment
  at which a `Subject` could be captured for a config-authored rule. **This is a multi-seam design pass
  (record + notification routing + a capture point), not a residual of this row.**
  3. ⚠ **duckle S2 retention — IN SCOPE, still open, and the stated harm is RESTATED.**
  `duckle-concepts-candidates.md:74` explicitly rules that S2 stays attached to C1 rather than being
  ranked separately, and it has no row of its own — ⛔ filing one would reproduce the "retrofit the
  exemption afterwards" order that line warns against. 🔴 **But S2's stated harm cannot occur here.** It
  was filed as a false breach ("a 30-day window reports a 90-day SLA breached 45 days early"), which
  would need the evaluator to read a pruned absence as *stale*; `DatasetFreshnessProbe:46-50` answers
  **unknown** and `AlertService.evaluateFreshness` returns **silently**. ⇒ the real defect is the
  opposite one: after `EventPruneTask` drops the day-partition holding a Dataset's last publication
  **and** the process restarts, a genuine breach becomes invisible. Under-reporting, not a false alarm.
  ⚠ It is also **not a flag**: `EventPruneTask:32-34` deletes whole `level/year/month/day` partitions, so
  a per-Dataset exemption is not expressible in that shape — honouring S2 needs a durable last-publication
  record or a prune floor. Carry it as a C1 acceptance criterion, not as buildable work.
  4. ⚠ **The Catalog Dataset badge (UI) — BUILDABLE NOW, not built this pass; the path is grounded.**
  ⛔ It does **not** need the in-memory state persisted, so `stale-tiles.ts:1-34` is honoured rather than
  breached: the `dataset.write` Signal is already durable in the event store — `DatasetFreshnessProbe` is
  a cache over it, not the record — so the badge **derives** at read time exactly as that objection
  demands. `GET /signals?type=dataset.write&source=dataset:<id>` already serves it
  (`SignalRoutes.java:80-93`; `DatasetWriteSignal:82-84` sets the compact `dataset:<id>` join key), and
  `EventsService.signals` already calls that type (`dashboard-editor.component.ts:321`), needing only a
  `source` filter added. ⚠ **Two real traps for whoever builds it:** `type`/`source` are **post-filters
  over the store page**, so `limit=1` usually returns `[]` and a busy ledger can push the target off the
  page — failing to *unknown*, the same safe direction the probe chose. ⚠ Note the Dataset list is a
  registry surface (`modules/admin/studio/datasets/`), **not** the Catalog module, and `latestRunTime`
  stays unavailable (`CatalogOverlay:54-62` maps `DATASET` to `OperationalOverlay.NONE`) — the badge must
  come from the Signal, never from the overlay. Render through `<inspecto-status-badge>`.
  ⚠ Pre-existing and untouched: `ConfigSpecs.alert()` marks `metric`/`threshold`/`window` **required**,
  which a freshness rule omits — exactly as a BI-5 measure rule already does. The flat `alert-rule`
  component is written through `AlertRoutes`, not `/config/write`, so that required-ness never gates it;
  ⛔ if a freshness rule ever goes through `/config/write`, the mismatch bites BOTH shapes at once.

  - **P2** · **`DUCKLE-C1-DATASET-FRESHNESS-1` (original row, kept for provenance — status is the line ABOVE)** — Adopted
  2026-09-15 from duckle §1 C1.
  ⛔ **SHARED BLOCKER — do not answer it here.** This row, `DUCKLE-C4-PARAM-PROVENANCE-1` and the deleted
  `ROUTE-OWNERSHIP-SCOPE-1` all bottom out in the **same missing thing: there is no ownership/identity model
  in the auth-free core** (`NotificationRule.java:112-113` hardcodes one `"appUser"` recipient; `AlertRule`
  has no owner field at all). ✅ **Decided 2026-09-15 (operator): record the convergence, do NOT decide the
  model yet** — answering it per-row would produce three incompatible answers. Owner-routed alerting is
  therefore **out of scope for this row** until that one design lands.
  ⚠ Also grounded 2026-09-15: `AlertService.evaluate:176-210` is **fire-only** — there is no recovery or
  all-clear path in any form, and cooldown only suppresses re-fires. A clock-based freshness rule needs one,
  and building it is not a sub-case of this row's `maximumAge` check.
  ⚠ And the codebase carries a standing objection this row must answer before persisting anything:
  `stale-tiles.ts:1-34` — *"There is no stored 'stale' flag anywhere, and deliberately so… ⛔ Do not
  'improve' this by persisting a flag."* A rule declares `maximumAge` or `expectedAfterSchedule`; **no declared
  limit ⇒ `unknown`, never `fresh`**; a failed *or partial* run does not count as a refresh; a disabled or
  missing schedule makes the Dataset stale; `fresh→stale` alerts and `stale→fresh` sends an all-clear
  through the same Alert Rules, and **the all-clear is never held by a cooldown**; `stale_since` carries
  across evaluations; evaluated once a minute **on its own thread, not the scheduler's**.
  ⚠ **Two dependencies, both real:** (a) duckle **S2** — the last publication of an SLA-bearing Dataset
  must survive retention, or a 30-day window reports a 90-day SLA breached 45 days early; cheapest to
  honour while building this, not after. (b) duckle **S13** — owner-routed alerting needs an ownership
  model, and **there is none** (`AlertRule` carries no owner; the auth-free core hardcodes the recipient to
  `"appUser"`). ⇒ either scope this to the existing flat `ChannelConfig` routing, or take S13 first.
  → Alert Rule / Incident · Catalog Dataset badge

  ✅ **THE SHARED BLOCKER IS ANSWERED 2026-09-15 — and this row's "out of scope" clause is lifted.**
  The ownership model is decided: **owner = the authenticated `Subject` where one is attached,
  `"appUser"` where none is**, mirroring how `requireCapability` already degrades to a no-op on
  Personal. ⇒ **owner-routed alerting is back IN scope** for this row, and it no longer needs
  scoping down to the flat `ChannelConfig` routing. Duckle **S13** is discharged by that decision.
  ⚠ **What has NOT changed is the size of the rest.** `AlertService.evaluate:176-210` is still
  **fire-only** — there is no recovery or all-clear path in any form, and cooldown only suppresses
  re-fires — so a clock-based freshness rule must build one, and ⛔ that is not a sub-case of the
  `maximumAge` check. Duckle **S2** (the last publication of an SLA-bearing Dataset must survive
  retention) is still cheapest to honour while building this, not after. And the standing objection
  stands: `stale-tiles.ts:1-34` — ⛔ *"Do not 'improve' this by persisting a flag."*

- **P3** · 🔴 **`DUCKLE-C8-BASELINE-EXPECTATION-1` — GROUNDED 2026-09-16: still STORAGE-BLOCKED, and
  `transform.profile` did NOT discharge it.** `RowShaper:483-518` does `CREATE TABLE <prefix_DATA> AS
  <select>` and returns an ordinary `Relation(PipelineRel.DATA, …)`, so a profile flows to the pipeline's
  normal output store. ⛔ **There is NO profile-history store, no accepted-profile store, and no
  `accept`/`clear` op anywhere** — `com.gamma.expectation` has neither verb ⇒ the row's *“median of the
  last N **accepted** profiles”* has **no substrate**, and this row's first action is **profile storage**,
  not the Expectation kind. ⚠ The kind itself is cheap once storage exists — four hand sites:
  `Expectation.java:46` `KINDS`, its compact-constructor switch at `:69-77`,
  `ExpectationEvaluator.columnPredicate:69-79`, and `expectation-attributes.ts`.
  ⚠ **Expectations are NOT covered by `AcceptedConfigKeys`** — the record's own constructor is the whole
  validator, so the `alert`/`pipeline` census does not protect this type. Original row follows.
  - **P3** · **`DUCKLE-C8-BASELINE-EXPECTATION-1` — a baseline QA Expectation kind.** Adopted 2026-09-15 from
  duckle §1 C8. Profile the current input against the **median of the last N *accepted* profiles** (row
  count; per column null count/rate, distinct, min, max, mean); limits in either direction, % or absolute;
  `groupBy` + `requireExistingGroups` catches a missing partition when totals look normal; a profile is
  **accepted only if the whole run succeeds**; explicit `accept`/`clear` ops audited with the replaced
  value; **a refused run still records its profile**.
  ⚠ Largest of the adopted set — a new Expectation kind *plus* profile storage *plus* accept/clear ops.
  `FileSequenceGaps` is the nearest cousin to model the kind on. → Expectation kinds

- **P3** · **`DUCKLE-C10-ADMISSION-POOLS-1` — named execution pools are ADMISSION ONLY.** Adopted
  2026-09-15 from duckle §1 C10. A pool answers "may this start now" and **never widens thread or memory
  caps**; a Pipeline may *choose* a pool but never define one the server lacks (unknown ⇒ `default`); a
  queued run gets a durable id **immediately** with `queueReason`, becoming `running` with `queueMs`; a
  supervisor takes **no slot** — holding one while waiting for a child needing the same pool deadlocks;
  metric = free permits per pool.
  ⚠ **Adopt the RULE SET now even if the feature waits**: it is a design constraint on scale-out phase B,
  whose `RunLease` (fenced db lease) is already the seam. The deadlock rule in particular is cheap to
  honour up front and expensive to retrofit. → `superpower/enterprise-scale-out-plan.md` phase B

  ✅ **RULE SET RECORDED 2026-09-15** in `superpower/enterprise-scale-out-plan.md` §4.1 (under U2, beside the
  `ConcurrencyBroker` seam it constrains) — the row's actionable half. ⚠ The FEATURE (named pools, durable
  queued-run ids with `queueReason`/`queueMs`, the free-permits metric) is NOT built and this row stays P3
  for it. 🔴 One grounding note recorded with the rules: the "supervisor takes no slot" rule is already
  honoured by accident, not by design — `ConcurrencyBroker` admits Consignments, not the pipeline-level
  dispatcher, so nothing today holds a slot while waiting on a child. The rule exists to keep it that way.

- **P3** · **`DUCKLE-C6-POLICY-NARROWING-1` — a workspace policy that can only NARROW.** Adopted
  2026-09-15 from duckle §1 C6. Denies union, allowlists intersect, permissions AND; `mode` comes from the
  server file only; enforced **at plan time AND at the point of the act** (network: every hop plus DuckDB
  itself; state mutation: every watermark/offset advance); **prefixes match at a path boundary, not as
  strings**; and a named policy file that **cannot be read refuses the run**.
  ⚠ Parts exist — `PathJail`, `ConfigSafetyValidator`, `DataRef` — but the structural narrowing rule and
  the unreadable-policy refusal do not. 🔴 The prefix-boundary rule is **the exact defect corrected in
  scale-out phase C §5.4**, which is evidence this rule set earns its keep rather than a reason to skip it.
  → Config safety · sealed sandbox · edition gating

- **P3** · **`DUCKLE-C7-AFFECTED-CONTRACTS-1` — which Pipelines does a change reach, and which contracts
  break?** Adopted by the operator 2026-09-15 from duckle §1 C7, **with the git question ruled: IN SCOPE.**
  The standing "no in-app git integration" exclusion covers an in-app git *feature*; this is a **CI-shaped
  check over a diff**, which is a different thing. Recorded so the exclusion is not re-applied to it later.
  What it is: given a revision, report which Pipelines a change reaches, **each carrying the chain that
  reached it**; asset edges *and* parent→child ref edges (the reverse direction); **deleting a producer is
  a change**; canvas geometry ignored; dynamic paths listed as **uncertain** rather than resolved.
  🔴 **The contract verdicts are the subtle half and the reason to build it at all — they depend on the
  READER:** removing a column somebody reads is **breaking**; removing one nobody reads is **"possibly
  breaking", never "compatible"**; anything downstream of a transform is a **"revalidate" tier**, because
  there is no column lineage to prove either way. ⛔ Do not collapse those three verdicts into
  breaking/non-breaking — the honesty of the middle tier is the feature.
  ⚠ Lineage/impact code already exists (42 Java / 25 TS hits); **what is absent is the gate over a diff**.
  ◐ **Affected half SHIPPED 2026-09-24**: `com.gamma.service.AffectedPipelines` (`analyze` + a `main <configRoot> <baseRev> [--fail-on-affected]` entry) reports the Pipelines a diff reaches, each with its chain, through existing links only (`PipelineConfig.referencedFiles`, `PipelineDependents` Dataset links, `collector.dataset`); deleting a producer is a change; a parse-identical edit reaches nothing; unloadable Pipelines and non-plain `collector.dataset` values are UNCERTAIN (→ `okf/backend/control-plane/pipeline-related.md`). **Still open, and the reason the row exists:** the reader-dependent contract verdicts above; also non-Pipeline dependents in the output, enrichment-ref / job `on_pipeline` links, CI wiring.
  → Lineage · `docs/api` breaking-change record · CI

- ~~**P3** · **`JOB-DIR-CWD-CONTAINMENT-1`**~~ ✅ **SHIPPED 2026-09-16.** The semantics call was answered (operator: a job's relative path resolves against the **Space config root**, never the process working directory) and built on BOTH sides in one change, as the 2026-09-15 re-grounding said it had to be: `PathJail.resolveJobPath` is the single rule, called by the 422 gate (`ConfigSafetyValidator.checkJob`, which now USES `configDir` instead of ignoring it) and by all four run-time jail sites (`CleanupTask:35,46`, `PartitionPruneTask:39`, `StorageReportTask:45`). ⛔ **The ambiguous case REFUSES** — a value that does not exist under the Space root but DOES where the old rule would have put it throws naming BOTH paths, so a change in what an existing job MEANS is fixed deliberately rather than silently read elsewhere. A null Space root keeps legacy behaviour. 🔴 **Consequence worth keeping: relative `..` traversal is no longer an escape by itself**, because it climbs from a deeper base — containment is still enforced, the jail just judges a different resolved path. `ControlApiJobCrudTest`'s probe had to change with it: `../../outside` resolved from the Space root lands back INSIDE the allowed roots, so asserting 422 on it would have asserted a falsehood — and it was the layout-dependent probe that created this row (escaped from `%TEMP%`, did not from `C:/sandbox`). It now uses an absolute path off the filesystem root, which escapes under every layout. Pinned by `JobPathResolutionTest` (6 tests). Original row follows.
  🔴 **TWO RESIDUALS FOUND 2026-09-16 by grounding this row AFTER it shipped — the “all four run-time
  jail sites” claim above is WRONG, and both the commit message and this row carry it.** Filed as the two
  rows below. ⚠ The claim counts four call sites in `inspecto-engine` and misses **five in
  `inspecto-backup`**; and the compatibility survey the commit's own message called a precondition was
  never performed. ⛔ **A row struck as SHIPPED is where open work hides** — this is the second time that
  has been recorded on this board.

- ~~**P2** · **`JOB-PATH-BACKUPTASK-SPLIT-1`**~~ ✅ **SHIPPED 2026-09-16** (`3f384182`). All five backup
  sites now call `PathJail.requireJobPathUnderAny(…, SpaceConfigRoot.current(), …)` — `dir` +
  `backup_dir` (backup), `backup_dir` (verify), `archive` + `target_dir` (restore). No second rule
  invented; the ambiguous-case refusal and the null-Space legacy branch are `resolveJobPath`'s, reached
  unchanged and pinned. ⛔ **One site deliberately NOT moved** — verify's `archive` (`:191`) names a file
  *inside* the already-resolved `backup_dir` and is jailed against **that**, not the Space root; moving it
  would reopen the `../outside.zip` read-out. Verified in the MAIN checkout under the profile that
  actually compiles the module (a default build never does): `mvn -o -pl inspecto-backup -am test
  -Pedition-professional` = **15/0/0/0**, all three test classes observed to RUN. Mutation-proven: reverting
  to `requireUnderAny` gives 1 failure + 4 errors, each field failing at its own act line.
  🔴 **The row's stated blocker was REFUTED, and it was the reason the row sat deferred.** The
  module-graph question was never open: the `inspecto-engine` mention in `inspecto-backup/pom.xml` is in
  the `<description>` **prose**, not a `<dependency>`, and `BackupTask` **already imported**
  `com.gamma.pipeline.ComponentStore` — same package and jar as `SpaceConfigRoot`. No `inspecto-config`
  push-down was needed. ⛔ **A blocker stated as two contradicting reports is a question nobody asked the
  compiler.** ⚠ Line numbers on this row were also off by one on restore.
  ⚠ **`JOB-DIR-CWD-CONTAINMENT-1`'s struck row and commit `a7ab607b`'s message both still say "all four
  run-time jail sites". It is now NINE** (4 engine + 5 backup) — corrected here; the commit message
  cannot be.
  → `okf/backend/control-plane/jobs.md` · original row follows.
  - **P2** · 🔴 **`JOB-PATH-BACKUPTASK-SPLIT-1` — the gate and the backup RUNTIME now disagree, which
  `resolveJobPath`'s own javadoc says must never happen.** Filed 2026-09-16. `BackupTask`
  (`inspecto-backup/.../BackupTask.java`) handles **four of the five keys the operator decision names** and
  still resolves every one CWD-relative through the plain jail — `:81-83` (`dir`, `backup_dir`),
  `:171-172` (`backup_dir`, verify), `:257-258` (`archive`, `target_dir`, restore) — calling
  `PathJail.requireUnderAny(PathJail.allowedRoots(), …)`, **not** `PathJail.resolveJobPath`.
  `BackupTaskProvider` is a live ServiceLoader job type shipping on Professional+
  (`NoBackupTaskShipsInThePersonalBuildTest`). ⇒ the 422 SAVE gate resolves `backup_dir`/`archive`/
  `target_dir` against the Space config root while the backup RUNTIME resolves them against the CWD.
  ⚠ **Not a mechanical fix, and this is why it was not just done:** `SpaceConfigRoot` lives in
  `inspecto-engine` (`com.gamma.pipeline`). Whether `inspecto-backup` may depend on it is a **module-graph
  call** — ⛔ ground it before starting: one report says there is no such dependency, while a grep finds a
  single `inspecto-engine` mention in `inspecto-backup/pom.xml` that nobody has confirmed is a dependency
  rather than a comment. The alternative is pushing the Space-root lookup DOWN into `inspecto-config`
  beside `PathJail`, the push-don't-pull shape `DiscoveredRoots` already uses.

- ~~**P1**~~ · **`JOB-PATH-COMPAT-SURVEY-1`** — ✅ **CLOSED 2026-09-17 — re-DRIVEN over the real `PathJail.resolveJobPath` (positive control fired): the six demo backup/report/compact values RESOLVE, every one of the row's "four defects" is shipped, and `probes.txt`'s "two jobs broken right now" was FALSE for this tree (single-tenant never reached the Space-root rule).** 🔴 **What the re-drive found instead: SIX committed `pipeline_config` values the row never listed — `spaces/default/config/jobs/{dedup,filter,join,sql,summarize}_step_rollup_job.toon:4` and `spaces/demo/config/jobs/orders_rollup_job.toon:4` — were spelled repo-relative (`spaces/<x>/config/...`) and REFUSE under the Space-root rule (the path doubles).** Re-pointed to Space-relative (`dedup_step/dedup_step_pipeline.toon`, `orders/orders_pipeline.toon`) in the same commit that jails the runner's reads (`JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1`), because the runner read CWD-relative until then and the two changes only work together. `JOB_PATH_KEYS` is **nine** today (`ConfigSafetyValidator.java:135-137`), not six. *(Original head:)* SURVEY DONE 2026-09-16 (`6c3dbd6e`), and it RE-RANKS this
  row from P2 to P1: on a deployed tree the change refuses EVERY relative path in EVERY committed job
  config.** Full survey: [`superpower/job-path-compat-survey.md`](superpower/job-path-compat-survey.md).
  37 configs, 24 carrying 33 relative values, **zero absolute values anywhere**. Deployed: **28 NOW
  REFUSE** — that is every covered value, because once the old path exists no committed value can resolve
  identically, so the ambiguous-case branch fires for all of them. Fresh checkout: **15 silently
  re-point**. **UNAFFECTED = 0 in both columns.**
  ⇒ **Ranked P1 because it is a regression in work that shipped the SAME DAY** (`JOB-DIR-CWD-CONTAINMENT-1`),
  not a longstanding gap — and because two of the five jobs `inspecto/examples/06-serve/maintenance-library`
  advertises in its own `probes.txt` are broken right now, not merely at save.
  ⚠ **Method — the reason these numbers are trustworthy:** `PathJail` was **compiled from the tree and the
  real `resolveJobPath` CALLED** twice per value, rather than re-implemented in a script; a hand-mirrored
  copy of a rule has drifted four times in this repo. Four positive controls, each proven to fire — the
  load-bearing one being that the **zero** hits for `archive`/`target_dir` are a *proven* absence, since
  the same regex finds 35 `backup_dir` hits.
  🔴 **Three of this row's own claims are REFUTED.** (1) `config_backup_job.toon` is **not** "unsavable" —
  its values sit under `params:` and the gate reads `RawConfig.str(raw, "job."+k)`, a dotted path from the
  root, so `job.params.dir` is invisible to it; it saves fine and the refusal is run-time only. (2) The
  `inspecto/examples/**` values do **not** re-point silently — `serve-example.sh:50` pre-creates the old
  paths before boot, so they REFUSE. (3) `spaces/default`'s **five** `pipeline_config` values are missing
  from this row entirely and refuse unconditionally.
  ⛔ **`JOB_PATH_KEYS` is SIX keys, not the five the operator decision named** (`ConfigSafetyValidator.java:122`,
  verified independently) — the extra one is `pipeline_config`, the most common path key in committed
  configs (12 of 33) and the largest single source of refusals. **A decision that enumerates its own scope
  can still be narrower than the code that implements it.**
  ⇒ Remedy is `JOB-PATH-DEMO-CONFIG-REPOINT-1` plus the four defects below. Not verified: no job was
  actually RUN — consequences are read off call sites, and a live `SMOKE` over `spaces/demo` would settle
  it. → `okf/backend/control-plane/jobs.md` · original row follows.
  - **P2** · 🔴 **`JOB-PATH-COMPAT-SURVEY-1` — committed job configs changed meaning, and most of them
  changed SILENTLY.** Filed 2026-09-16; the survey below is the first on record, though `a7ab607b`'s own
  message called it a precondition. ⛔ **The loud-refusal guard only fires when the OLD path EXISTS on
  disk** — every value pointing at a not-yet-created directory re-points under `config/` with no refusal:
  • `spaces/demo/config/jobs/config_backup_job.toon:6-7` (`dir: spaces/demo/config`) ⇒ **LOUD REFUSAL —
  this committed job is now UNSAVABLE** • `backup_retention_job.toon:5`, `backup_verify_job.toon:6`,
  `orders_weekly_compact_job.toon:7` ⇒ **silent re-point** • `inspecto/examples/**/*_job.toon` (11 files,
  `data_dir: out`, `dir: out/backup`, …) ⇒ **silent re-point**.
  ⚠ **Three of the four demo rows are BACKUP jobs**, so they are exactly the ones hitting
  `JOB-PATH-BACKUPTASK-SPLIT-1` — refused at save while still running CWD-relative. ⇒ do both rows in one
  change, and re-point the demo/example configs to space-relative values as part of it.

- ~~**P1**~~ · **`JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1`** — ✅ **SHIPPED 2026-09-17 under the operator's design call (Option 1, additive read root).** `SpaceConfigRoot` gains `registerConfigReadRoot` / `currentConfigReadRoot` / `forSpaceConfigReadRoot`: an explicit read root wins; a `SpaceBootstrap`-registered space's config root serves both roles; the DEFAULT space falls back to the launch dir (the twin of `LegacySpaceRoot.base()`, which the engine cannot import) — exactly today's resolution rule, so nothing re-points; `current()`/`currentRegistry()`/`forSpace()` are byte-identical and a test pins `currentRegistry()` unchanged after registering a different read root. `SpaceManager.single()` registers `legacy.base()` as the default read root. `PipelineJobRunner` now resolves `pipeline_config` through `PathJail.requireJobPathUnderAny(allowedRoots, readRoot, …)` and CHECKS `data_dir` the same way without rewriting it (the authored string is baked into durable view SQL). New `PipelineJobRunnerPathJailTest` (4) + 5 `SpaceConfigRootTest` cases; mutation: dropping the `pipeline_config` jail reddens 2 tests, dropping the `data_dir` jail exactly 1. **DRIVEN live**: `spaces/demo` `orders_rollup` and `spaces/default` `dedup_step_rollup`/`sql_step_rollup` triggered over HTTP against the re-pointed `pipeline_config` values (`JOB-PATH-COMPAT-SURVEY-1`) — SUCCESS, 21 rows → store. *(Original head:)* CONFIRMED, and the BLOCKER CHANGED TWICE. It is a
  two-root DESIGN call, not a policy one.** 🔴 **The recorded blocker was refuted 2026-09-16 (`da55eaca`):**
  it said single-tenant serve registers no allowed roots, so the jail would throw *"no allowed roots
  configured"* and the only workaround was fail-open, which `SafetyPolicy` forbids. **False.**
  `serve-example.sh:65` has passed `-Dassist.safety.roots=$(pwd)` since `296e4fc7`; a real JVM over a
  replica of the serve layout yields `defaultPolicy()` **size=1**, with a negative control proving the
  probe CAN return the predicted empty result. ⛔ **The fail-open dilemma never existed.**
  ⇒ **The actual obstacle is the BASE.** `SpaceManager.single():74` never calls `SpaceBootstrap.load`, so
  single-tenant registers nothing in `SpaceConfigRoot`, and `forSpace("default")` falls through to
  `-Dassist.write.root` = `out/write`. Job paths then resolve under `<example>/out/write/…`.
  ⛔ **Owed call: single-tenant needs a config-READ root distinct from its WRITE root.** Registering the
  launch dir naively moves `currentRegistry()` from `out/write/registry` to `<dir>/registry` and breaks
  API-authored components. `SpaceRoot.legacy().config()` returns `null` precisely because legacy
  single-tenant has no single config root — the codebase already admits this is two roots, not one.
  ⚠ Blast radius if the reader moves: **19 of 19 committed values break, 0 unaffected in either column**
  (driven, three positive controls). Only **6** traverse the save gate; the other 13 live in single-tenant
  examples that never reach it — a different and worse class, ungated AND unjailed.
  ✅ **The `:508` javadoc half is DONE** (`d433ac0a`): the javadoc was wrong, not the validator — stale
  since `ad558216` added the `job` arm. ⚠ A census correction from the same grounding: "19 committed
  values" could not be reproduced; the sweep derives **27** across both trees, reducing to 5 distinct
  strings. → `okf/backend/control-plane/jobs.md` · original row follows.
  - **P1** · 🔴 **`JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1` — 19 of 33 committed path values are gated under one
  rule and RUN under another, and it is also a containment hole.** Filed 2026-09-16 from the survey;
  **bigger than `JOB-PATH-BACKUPTASK-SPLIT-1`, which is now shipped.** `PipelineJobRunner:242` passes
  `pipeline_config` straight to `PipelineConfig.load(flatPath)` and `:266` passes `data_dir` on, **with no
  `PathJail` call at either site** — verified independently, not taken from the survey. ⚠ Its own javadoc
  (`:508`) states *"Job configs bypass `ConfigSafetyValidator`"*, which contradicts that validator having
  a `checkJob`; one of the two is wrong and the discrepancy is itself the finding. ⇒ Ranked P1: an
  unjailed path read is a containment defect, not a consistency nit. → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`JOB-PATH-COMPACTOR-UNJAILED-1`**~~ ✅ **SHIPPED 2026-09-16.** Both sites now use the JOB
  rule — `requireJobPathUnderAny(…, SpaceConfigRoot.current(), …)` — matching the three siblings
  (`CleanupTask`, `PartitionPruneTask`, `StorageReportTask`) reached from the same `MaintenanceJob` switch.
  ⛔ Plain `requireUnderAny` was rejected deliberately: it would make `dir:` mean one thing in `compact`
  and another in `cleanup`, which is the split the seam exists to prevent. ⛔ `ReferenceCompactor.compact(Path,long)`
  is deliberately left UNJAILED — `CollectorService:1276` feeds it a pipeline's own resolved dirs, not an
  operator-authored value. `inspecto-engine` 1668/0/0/0; mutation red on exactly the two refusal
  assertions while the two "still runs" controls stayed green. ⚠ **This row is rare: every claim in it
  held up, including the "every other `dir` reader jails" half** — worth noting against the base rate.
  🔴 **Its committed-config check was wrong, and I corrected it.** The lane reported none of three
  candidate paths existed; `spaces/demo/data/orders` **does**. Per `PathJail.java:182-189` the refusal
  fires when the Space-relative path is absent AND the CWD-relative one exists — so
  `orders_weekly_compact_job.toon`'s bare `dir: data/orders` refuses or not **depending on the launch
  directory**: safe from the repo root (what was tested), refusing when served from `spaces/demo`, which
  is where that data actually lives. ⇒ **"No committed config refuses" was CWD-dependent and stated as
  unconditional.** Add it to `JOB-PATH-DEMO-CONFIG-REPOINT-1` — it is the only *bare* relative `dir` among
  the demo jobs. ⚠ `JobService.java:437` cites `PartitionCompactor:59`, which this change shifts.
  → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`JOB-PATH-PATCH-ROUTE-WRONG-BASE-1`**~~ ✅ **SHIPPED 2026-09-16 — and the defect was NOT the
  one this row named.** `ConfigWriteRoutes.safetyBase(type, writeRoot, target)` now dispatches on TYPE:
  a job is judged from the Space config root (`api.writeRoot()` — the same value `SpaceBootstrap:46`
  registers for `JobRoutes` to read back), a pipeline/schema keeps `target.getParent()` so its
  `schema_file`/`grammar` refs resolve as the loader resolves them. ⛔ **"Make them the same" was the wrong
  fix** — `configDir` legitimately carries two meanings by config type, which is why the call site looks
  correct at a glance.
  🔴 **The real third base was elsewhere in the same file: `/config/write:71` passed NO base at all**
  (`null`), judging a job against the **process working directory** — the exact rule the operator retired.
  Fixed in the same change; leaving it would have kept this row's own defect alive one call site over.
  ⚠ **The row's description only reproduces in one shape:** `target.getParent()` equals `…/config/jobs`
  only when the caller passes `subdir:"jobs"`; with no subdir it *coincidentally equals* the config root.
  ⇒ **Severity was lower than filed and that is now established, not assumed:** `/config/patch` has **no
  production caller at all** and nothing anywhere sends `type:"job"` to either route — both are armed but
  uncalled for jobs, so this was latent, never live. **No committed config changes meaning.** Evidence:
  pre-fix `PUT /jobs/sweep` → 422 while `/config/patch` → 200 `written:true` for the same escaping
  `backup_dir`. Pinned by `ControlApiJobPathBaseTest`; `inspecto` 3828/0/0/8, 545 surefire reports fresh.
  ⚠ **Two probes passed for the WRONG reason first:** the parent POM grants `java.io.tmpdir` as an allowed
  root (`pom.xml:418`) so every `@TempDir` resolved contained, and `DiscoveredRoots` is process-global
  static and leaks roots between test classes. → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`JOB-PATH-GATE-BLIND-KEYS-1`**~~ ✅ **PARTLY SHIPPED, PARTLY REFUTED 2026-09-16 — and the
  refuted part was MY error in filing it.**
  ✅ **`archive_dir` ADDED to `JOB_PATH_KEYS` (now seven).** It is the one key where gate and jail already
  agreed and only the gate was not looking: `CleanupTask:47` resolves it through the identical
  `requireJobPathUnderAny`. Blast radius **zero** — no committed config carries it.
  🔴 **The "11 values under `params:`" claim is REFUTED, wrong twice over.** The real figure is **5** real
  values plus 4 `${…}` placeholders. ⛔ **The 11 was the survey's TOTAL gate-blind count across all
  causes**, which I copied onto the dotted-path cause alone — a derived row inheriting no grounding, again.
  ⛔ **And the defect is UNREACHABLE anyway:** all five live in Job Template instances, which carry no
  `job.type`, and `job.type` is `FieldSpec.required` (`ConfigSpecs.java:507`) run beside the safety gate on
  every gated route — the body is refused first. Widening `checkJob` to `job.params.*` would refuse nothing
  any route can accept. Not widened; the reason is pinned by a test instead.
  🔴 **`out_dir` "covered by neither" is HALF refuted** — it IS jailed (`ReportJob:125`,
  `requireUnderAny`), just not Space-relative. ⛔ Adding it to the gate would MANUFACTURE the very split
  the list exists to close, and would refuse one committed config. Held out deliberately.
  ⇒ **The sweep is the lasting value: there are TEN path-shaped job keys, and both the operator decision
  (five) and this row undercounted.** 🔴 It also surfaced a key nobody had named — **`config` on
  `EnrichJob:59` is ungated at save AND unjailed at run**, a containment hole with zero committed values.
  ⇒ Residual re-filed as `JOB-PATH-REPORT-ENRICH-SPLIT-1`. `inspecto-config` 157/0/0/0, both mutants
  meaningful. → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`JOB-CONFIG-THIRD-PRODUCER-1`**~~ ✅ **SHIPPED 2026-09-16.** `JobBundleSource.write` ran
  `JobConfig.fromMap` (structural only) and jailed the FILE via `WriteGates.jail`, but never called
  `ConfigSafetyValidator` — while its sibling `JobRoutes.parseJob` runs both. Now gated.
  🔴 **Sharper than the row: this is direct residue of `JOB-DIR-CWD-CONTAINMENT-1` shipping onto `/jobs`
  the SAME DAY and leaving the bundle sibling behind** — not an independent finding.
  ✅ **The abort-vs-per-item question needed no operator call** — the existing contract already answers it:
  the whole-bundle 422 is reserved for CROSS-ITEM concerns checked before any write, so a bad path value,
  intrinsic to one item, lands as a per-item `failed` before the write and before hot-registration.
  Nothing half-applies and a clean sibling still imports. RED proof: with the gate off, a bundle carrying
  `dir: ../../../../../../evil_escape` reported `"status":"imported"`.
  ⚠ Severity is lower than "ungated" implies — all three routes require `canAuthorWorkbench` — ⛔ but that
  gate is documented as **a no-op on Personal**, which is the ceiling. Original row follows.
  - **P2** · **`JOB-CONFIG-THIRD-PRODUCER-1` — bundle import writes job configs whose path values no gate
  ever sees.** Filed 2026-09-16 while closing `JOB-PATH-PATCH-ROUTE-WRONG-BASE-1`, which found it.
  `BundleRoutes.java:477` writes `<write-root>/jobs/<name>_job.toon` directly on import (reached from
  `bundle-transfer.service.ts:211`). ⚠ **Narrower than first reported:** the FILE's location *is* contained
  — `WriteGates.jail(api.writeRoot(), …)` — so "bypasses both gates entirely" is wrong. What is missing is
  that the job's own path VALUES (`dir`, `backup_dir`, …) never reach `ConfigSafetyValidator`. ⇒ A bundle
  can land a job config whose paths point anywhere the file jail does not cover. **A third producer of job
  config files that was on no row.** → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`COMPONENT-BULK-WRITERS-UNGATED-1`**~~ ✅ **FULLY CLOSED 2026-09-17. The bundle half shipped 2026-09-16; the other half's
  refutation does NOT hold.** `ComponentBundleSource.write` — the only `BundleSource` whose write
  validated nothing — now calls `ComponentRoutes.validateKind`, made **package-private** for exactly this
  (one modifier, no logic change). ⛔ **Reuse over a second accepted-set: two copies of an accepted-set is
  how the widget/dashboard census came to be needed in the first place.** It matters because `schema` and
  `mapping` are both in `WRITABLE_TYPES` and both gated on the authoring route, so a bundle could plant a
  component that `POST /components/schema/{id}` refuses with 422 — and a registry schema is engine-parsed.
  ⚠ **`BiTemplates` was reported REFUTED, and that does not hold on master.** The lane argued
  `validateKind` covers only `findings-spec`/`schema`/`mapping`, making a widget/dashboard route-through a
  no-op. True of its tree; false here — `afaa4005` added the census at `ComponentRoutes:640`.
  🔴 **The lane was not careless: its worktree branched from `a6c30568` and genuinely lacked that commit.**
  Its second argument (hardcoded template content, only `dataset`/`prefix` substituted) still holds and
  keeps severity low.
  ✅ **THE `BiTemplates` HALF SHIPPED 2026-09-17 — and what held it open was a FALSE CLAIM RECORDED IN THE
  CODE.** `BiTemplates.apply` now calls `ComponentRoutes.validateKind` in its **resolve** loop — before any
  write, so apply stays all-or-nothing and never plants a partial board — mapping `IllegalArgumentException`
  to 422 as `writeComponent` does (a bare IAE would have been a 500: only `ApiException` maps to a status in
  `ControlApi`).
  🔴 **`ControlApiBiTemplatesTest`'s javadoc justified its build-time-only shape with *“the gate cannot be
  called from here (`validateKind` is private)”* — FALSE, and already false when written.** It is
  package-private (widened for `BundleRoutes`) and `BiTemplates` sits in that very package. A stale blocker
  nobody re-checked WAS the remaining work; the javadoc now retracts it in place.
  ⚠ **Severity stays defence-in-depth, and was proven BY CONSTRUCTION rather than by reading the
  accepted-set:** `substituteTree` copies keys verbatim and `substituteAny` rewrites only String *values*, and
  every key originates in a hardcoded `Map.of(...)` — so `dataset`/`prefix` can never introduce a top-level
  key and a KEY census cannot fire on today's templates. The gate is for the NEXT curated template.
  ⛔ **No new test, deliberately.** No reachable input can trip the gate, so a new test would be one that
  CANNOT FAIL — this repo's most-repeated failure mode. The gate was mutation-proven instead (bogus key ⇒ 422
  and nothing written; gate removed ⇒ 200 and all four components planted), and the build-time
  `everyTemplateWritesABodyTheAuthoringRouteAccepts` stays the guard that can actually go red.
  ⇒ Residual filed: `BITEMPLATES-GATE-ORDER-1`. Original row follows.
  - **P2** · **`COMPONENT-BULK-WRITERS-UNGATED-1` — two bulk writers bypass every `validateKind` gate.**
  Filed 2026-09-16 from the `widget`/`dashboard` census. `BiTemplates.apply` (`BiTemplates.java:125`) and
  bundle import (`BundleRoutes.java:425`) call `store.write` directly, so no component gate runs — not the
  new key census and not the pre-existing `schema` validation. ⚠ Same "gate on one route, not its sibling"
  shape as the finding that started this thread. The authoring route is the UI's only door, so the
  reachable half is closed; this is the rest. → `okf/backend/config/config-safety.md`

- ~~**P2** · **`PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1`**~~ ✅ **SHIPPED 2026-09-16 — and this row was
  RIGHT, count and 24/12 split both, which is worth recording against the base rate.**
  ✅ **The 422 was DRIVEN, not inferred** — the gap it was filed with. A real-HTTP `POST /config/write` of
  three committed samples, one per tree, returned 422 with a single ERROR (`ERR_UNKNOWN_CONFIG_KEY` on
  `version`), and the strip-and-repost control returned **200 `written:true`**. **Nothing reads a
  pipeline's `version`** — parser, specs, SPA and tooling all checked, with a positive control proving the
  grep finds the real reader of `active`. ⇒ option (a) followed from evidence; 36 files, one line each.
  ⛔ **The guard was the deliverable, not the 36 edits:** the pipeline census had shipped with **no
  "no committed config regresses" test** — `meta` escaped only because it happens to declare `version`.
  `PipelineKeyCoverageContractTest` now WALKS both trees (rather than hardcoding paths as the `meta` and
  `enrichment` guards do, which would miss a newly added sample), plus a falsification test so an empty
  sweep fails loudly. Mutation-proven RED.
  ⚠ Two facts for the next row: `okf/backend/engine/plugins.md` was **teaching** the dead key in a sample
  (fixed); and `mvn -pl inspecto-ops -am` **does not resolve without `-Pedition-professional`**, so the repo's
  only sweep over every committed space config never runs in a default-profile reactor — the third
  edition-gating blind spot found today. Original row follows.
  - **P2** · 🔴 **`PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1` — 36 committed sample pipelines cannot be
  re-saved.** Filed 2026-09-16 from the `enrichment` census. `*_pipeline.toon` samples carry a top-level
  `version:` that the pipeline spec does NOT declare (`version` is declared on `meta()`,
  `ConfigSpecs.java:737`, not `pipeline()`), so **re-saving any shipped sample pipeline through
  `/config/write` has 422'd since the pipeline census landed at `2c310d1c`**. ⚠ **24 under `spaces/` and
  12 more under `inspecto/examples/`** — the lane that found it scoped to `spaces/` and undercounted by a
  third; the examples tree is the same one whose advertised jobs were found broken a day earlier.
  ⛔ **The root cause is a missing guard, not a missing fix:** the pipeline census shipped with no
  "no committed config regresses" test. `meta` escaped only because it happens to declare `version`;
  `enrichment` added that test and cleaned its one sample. ⇒ Owed call: strip the key from 36 files, or
  declare it on the pipeline spec and say why. → `okf/backend/config/config-safety.md`

- ~~**P2** · **`JOB-PATH-REPORT-ENRICH-SPLIT-1`**~~ ✅ **FILED AND SHIPPED 2026-09-16.** The last two
  path-shaped job keys whose readers disagreed with the gate. `ReportJob:122` resolved `out_dir` through
  the plain `PathJail.requireUnderAny` (containment only, working-directory-relative) when
  `JOB-DIR-CWD-CONTAINMENT-1` moved everything else; `EnrichJob:59` handed `config` to
  `EnrichmentConfig.load` with **no `PathJail` call at all** — a containment hole, not merely a base
  mismatch, and independent of that row. Both now call
  `PathJail.requireJobPathUnderAny(allowedRoots(), SpaceConfigRoot.current(), …)`, and **only then** were
  `out_dir` + `config` added to `JOB_PATH_KEYS` — reader first, then the list, which is the order the
  field's javadoc now states for any further addition.

  🔴 **The row's own blast-radius claim was WRONG, and wrong in the more dangerous direction.** It said
  `maintenance_report_job.toon:6` (`out_dir: spaces/demo/data/reports`, the ONE committed `out_dir`, and
  `config` appears in zero — both re-confirmed, the latter against a `target:` positive control) *refuses*
  under the Space-root rule. **It does not refuse on a fresh tree: it silently re-points.** Driven, not
  mirrored — `PathJail` compiled from the working tree (`-sourcepath inspecto-config;inspecto-api`) and
  the real `resolveJobPath` called: the old value under the new rule returns
  `…/spaces/demo/config/spaces/demo/data/reports`, the doubled path, with **no throw**, because
  `resolveJobPath` only refuses when the CWD-relative path *exists* — and `spaces/demo/data/reports` is
  absent in this checkout. A report delivered to a path nobody watches, reporting SUCCESS, is worse than
  a refusal. ⇒ The ⛔ "land it with the re-point or it breaks in between" held for a different reason
  than the one stated.

  ⇒ Landed with the repoint of **that one value** (`out_dir: ../data/reports`), *not* all 33:
  `JOB-PATH-DEMO-CONFIG-REPOINT-1`'s other 32 belong to runtimes that had **not** moved
  *(29 as of later that day — the three backup values followed their reader, see that row)*
  (`PipelineJobRunner`, the compactors), and re-pointing those now would break them at run — which is
  exactly what that row's own ⛔ warns against. Driven: the new value resolves to
  `…/spaces/demo/data/reports`, **byte-identical** to what the legacy CWD rule produced from the repo
  root, so the re-point is behaviour-preserving.

  ⚠ **One instruction in the row could not be carried out: there is no ⛔ paragraph to delete.** The row
  said `JOB_PATH_KEYS`' javadoc "explains why they are held out"; it never did — it said only that
  unnamed keys "still get their run-time check", which for `config` was false. The javadoc now records
  the ordering constraint instead. Pinned by `ConfigSafetyValidatorTest.theReportAndEnrichPathKeysAreContained`
  (gate membership), `ReportJobDeliveryTest.aRelativeOutDirResolvesAgainstTheSpaceRootNotTheWorkingDirectory`
  (asserts the CWD path stays ABSENT, not merely that the Space path is populated) and
  `JobPathContainmentTest.enrichRefusesAConfigOutsideTheAllowedRoots` (a REAL readable file outside the
  jail, so the loader cannot refuse it for the wrong reason). → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`README-LINKS-BROKEN-IN-REPO-1`**~~ ✅ **SHIPPED 2026-09-16 (`0e6f9a78`).** ⚠ **Thirteen dead
  targets, not the five this row named** — every one a doc `f6faeae3` relocated into `okf/`, traced by git
  **rename detection** (93-98% similarity) rather than guessed. Where the content genuinely has no
  successor the link was REMOVED, not pointed somewhere plausible.
  ⛔ **The guard was the real fix, and it was fixed at the SHAPE.** `check-doc-links.mjs` had
  `ROOTS = ['docs','compliance','.claude']` — an **allow-list** — so `inspecto/` was never scanned and the
  customer's first page rotted green. Now `ROOTS = ['.']`, the whole repo minus a deny-list. Adding a
  fourth tree would only have postponed the fifth blind spot.
  🔴 **Two traps inside the widening, one caught late:** (1) with `ROOTS = ['.']` every path reads
  `./docs/…`, so the archive exemption's `startsWith` stopped matching and **570 ignored links silently
  became visible** — caught because the scope line still printed the exemption count; (2) `inspecto-deploy`,
  the gitignored bundle output holding a **stale copy of the whole docs tree**, was not in `SKIP_DIRS`, so
  the widening was green in a tree that had never built a bundle and **2417-red** in one that had.
  ⛔ **A deny-list validated on a tree that lacks the thing it should deny looks complete.**
  Falsified by planting a break in `asn-parser/docs/` — a tree the old scope could never reach.
  ⇒ Residual filed: `README-HARDWARE-PROFILE-CLAIM-1`.
  → `okf/backend/build-run/guard-coverage.md` · original row follows.
  - **P2** · 🔴 **`README-LINKS-BROKEN-IN-REPO-1` — the bundle's front page points at five documents that do
  not exist, and our doc-link guard structurally cannot see it.** Filed 2026-09-16 out of the bundle
  re-measurement. 29 links are dead **in the repository itself**, nearly all in `inspecto/README.md` — the
  file `package.ps1` step 7 copies to the **bundle root as the customer's first page** — pointing at
  five targets under `docs/` that no longer exist (architecture, configuration, operations,
  plugins and v3-agent-mvp — all deleted in the docs consolidation). ⛔ **`check-doc-links.mjs` has been green throughout because its
  `ROOTS = ['docs', 'compliance', '.claude']` and `inspecto/` is in none of them** — the fourth
  guard-scope blind spot in two days, after the bolded-count regex, surefire freshness and edition-gated
  modules. ⚠ Needs NO product call: fix the targets and widen the scope. ⛔ Deliberately NOT folded into
  the package-time neutralisation — hiding repo rot behind a bundle rewrite is how it survives.
  → `okf/backend/build-run/build-test.md`

- ~~**P2** · **`FENCE-STORE-SILENTLY-INERT-1`**~~ ✅ **CLOSED 2026-09-17 — check-time, no new wiring, as the
  row called it.** `CollectorService.checkDeletion` (the existing `STORE_DELETE_CONFLICT` log/event path,
  `:1122-1132`) now looks up `DeletionFence.coverage(...)` for every target store and labels the reason:
  a conflict's event/log line carries `reason=FENCED` (the only value it can ever carry, since `check()`
  only conflicts a resting producer); a target store that is NOT in conflict but is also not `FENCED` gets
  a new WARN log line plus a new `STORE_DELETE_UNFENCED` event (`store`, `reason` attrs) naming
  `VIEW_ONLY` / `CONSUMED_ONLY` / `UNMATCHED` — so the typo class the row filed against is no longer silent.
  `check()`'s conflict/no-conflict outcome, and the delete-proceeds-regardless behaviour, are unchanged —
  observability only. Registration-time was NOT chosen, per the row's own reasoning (new wiring,
  false-positives on a not-yet-authored pipeline).
  ✅ `DeletionFenceReasonTest` (`inspecto/src/test/java/com/gamma/service/`) pins both ends: a simulated
  active producer (via the same `running` set a live run populates) yields a `STORE_DELETE_CONFLICT` with
  `reason=FENCED`; an unmatched store yields `STORE_DELETE_UNFENCED` with `reason=UNMATCHED`. 2/2 green
  (`mvn -o -pl inspecto-event,inspecto-engine,inspecto -am test -Dtest=DeletionFenceReasonTest`).
  → `okf/backend/control-plane/jobs.md` · superseded analysis below, kept for provenance.
- ~~**P2** · **`FENCE-STORE-SILENTLY-INERT-1` — DETECTION SHIPPED 2026-09-17 (`29065137`); the PLACEMENT of
  a warning is an owed call.**~~ ✅ **SUPERSEDED BY THE CLOSED ROW ABOVE — struck 2026-09-19.** The placement
  call it declares "owed" was MADE and BUILT the same day it was filed (check-time), so this head was the
  only thing still saying otherwise. 🔴 **Left unstruck, it cost a real operator question**: a grounding pass
  on 2026-09-19 read this head, reported the call as still owed, and the decision was put to the operator a
  second time — they chose check-time, which is what had already shipped. ⛔ **A superseded analysis kept for
  provenance must be STRUCK AT THE HEAD, not merely followed by a closed row** — the head is what a reader
  and an agent match on. `DeletionFence.coverage(...)` now separates the three reasons a store is
  skipped — `FENCED` / `VIEW_ONLY` / `CONSUMED_ONLY` / `UNMATCHED` — sharing one private `Topology` record
  with `check` so "has a resting producer" is defined once and cannot drift. Purely additive; nothing
  consumes it yet.
  🔴 **This row's premise is REFUTED.** It said the silent skip exists because a store may legitimately
  not exist yet. **The fence never touches the filesystem** — it is derived from configuration alone, so a
  configured pipeline that has NEVER RUN still arms it. That reason does not reach the `continue` at all.
  Only `UNMATCHED` is genuinely ambiguous, conflating a typo with a not-yet-authored pipeline — and that
  residual is what makes the warning a decision rather than an obvious fix.
  ⚠ **Severity is LOWER than this row implied, and should stay stated that way:** the fence is
  **advisory** — `CollectorService:1122-1132` logs and emits `STORE_DELETE_CONFLICT`, then **the delete
  proceeds regardless**. Two months of inertness cost **observability, not data integrity**.
  ⛔ **Owed call — placement:** registration-time warn is NEW WIRING (`fenceDelete` runs at
  `JobService:1205`, run time only) and false-positives on a not-yet-authored pipeline; check-time needs no
  wiring but repeats per run. ⚠ **The corpus cannot break the tie: there is exactly ONE job-level `store:`
  in the whole repo** — the broken one. N=1, and no number was invented from it.
  ⚠ **Whatever the call, the message must print the PRODUCED SET:** store ids are *derived at lift*
  (`PipelineLift:433-434`) from the schema, never written literally in the config, so an operator cannot
  grep their own configs to check the match. → `okf/backend/control-plane/jobs.md` · original row follows.
  - **P2** · 🔴 **`FENCE-STORE-SILENTLY-INERT-1` — a job's `store:` that names nothing disarms the delete
  fence, with no error, no warning and no event.** Filed 2026-09-16 from the ten-key sweep, and found the
  hard way: `DeletionFence.check` (`DeletionFence.java:67`) `continue`s past a target store with no
  resting producer, so a typo, a renamed pipeline or a miscopied `dir:` silently turns the fence off.
  ⛔ **It shipped that way.** `compact_job.toon` carried `store: out/database` — a copy of the `dir:` line
  above it — from `ffeb95dc` (2026-07-08) until `819e597b`, while the only produced store is `sales`, and
  the example's own `probes.txt` advertised fencing that could never fire. **Over two months dead.**
  ✅ The committed value is fixed and `ShippedJobStoreKeyNamesARealStoreTest` now walks every shipped
  `*_job.toon`, but **operator configs have no such guard**. ⚠ The hard part is that a store may
  LEGITIMATELY have no producer yet — a pipeline not run, a store created later — which is why the silent
  skip was written. ⇒ Owed call: warn on an unmatched store at job registration, or leave it silent.
  ⛔ Silently-inert safety is the one option the evidence already rules out.
  → `okf/backend/control-plane/jobs.md`

- ~~**P3** · **`COMPACT-SUCCEEDS-ON-A-MISSING-DIR-1`**~~ ✅ **SHIPPED 2026-09-17 — the row's CENTRAL CLAIM
  was REFUTED, and the defect underneath it was real and FOUR times wider.**
  🔴 **“An UNRESOLVABLE dir is distinguishable from an EMPTY one” does NOT hold.** Every case the row calls
  unresolvable — blank, URI (`s3://…`), UNC, unparseable, outside the jail — ALREADY throws `PathJail.Escape`
  from `requireJobPathUnderAny` and never reaches the check. The row's own scenario (a stale-but-contained
  relative path) is **byte-for-byte indistinguishable** from a store nothing has written to yet, and erroring
  there would refuse every legitimate FIRST RUN. ⇒ the fix the row asked for is not implementable and was
  not built.
  ✅ **What IS distinguishable, and was genuinely broken: `dir` EXISTS but is a regular file.**
  `!Files.isDirectory(dir)` is true, so the task reported *“directory not present, nothing to do”* — a config
  no run can ever satisfy, reported as SUCCESS. `Files.exists(dir) && !Files.isDirectory(dir)` decides it
  exactly; ABSENT and EMPTY both stay a successful no-op.
  ⚠ **The row named ONE file; the shape was in FOUR** — `PartitionCompactor`, `CleanupTask`,
  `StorageReportTask`, `PartitionPruneTask`, **two of which DELETE**. (Again: grep the SYMBOL, not the row's
  file list.)
  ✅ **Mutation-proven by the cleanest control available — the unmodified tree.** `MaintenanceDirNotADirectoryTest`
  (6 tests) run against `928fd2ab` with the fix absent is **4 failures**, one per task; with the fix, 0. Its two
  positive controls (absent dir, empty dir) pass in BOTH runs — which is what shows the refusal was not bought
  by turning the legitimate no-op into an error.
  ⇒ Residual filed: `REFERENCE-COMPACTOR-SAME-SHAPE-1`. Original row follows.
  - **P3** · **`COMPACT-SUCCEEDS-ON-A-MISSING-DIR-1` — a broken compactor config reports SUCCESS.**
    Filed 2026-09-17 while building the job smoke. `PartitionCompactor:69` returns
    `JobResult.ok("directory not present, nothing to do")` when `dir` is absent. ⚠ Today's regression threw
    only because `serve-example.sh:52` **pre-creates** `out/database`, which made `resolveJobPath` refuse —
    **on a tree where the directory does not exist, the same broken config returns SUCCESS.** ⇒ The third
    "silently inert" case in this subsystem in one day, after `DeletionFence` and the non-failing `probe()`.
    ⛔ The fix is not obviously "throw": a compactor with nothing to compact is a legitimate no-op. The
    question is whether an UNRESOLVABLE dir is distinguishable from an EMPTY one — it is, and only the
    first should be an error. → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`JOB-PARAM-UNDECLARED-UNREPORTED-1`**~~ ✅ **SHIPPED 2026-09-17 — WARNING-only, and THREE of
  the row's five named examples were REFUTED.**
  `ParameterResolver.Resolution` gained `undeclared`, derived by diffing the authored `config` layer against
  the decl names; `JobService` warns on the run log beside the three REJECTED diagnostics. `JobRun.status`
  and `reason` are untouched — it never fails closed.
  🔴 **Refuted:** `pipeline_config` **IS** declared (`JobService.java:472`, on the `pipeline` descriptor)
  · `store` **IS** declared (on `maintenance`) · `template` never reaches `JobConfig.params()` at all —
  `JobTemplate.instantiate:102` strips `template`/`params` as resolution machinery before `fromMap` ever sees
  them, so it is **not a param**. Only `data_dir` and `on_pipeline_gate` survived.
  ✅ **And the asymmetry is WIDER than the row said.** Diffing every `cfg.require()`/`opt()` key against every
  `ParameterDecl` across `inspecto-engine` + `inspecto-ops` found **NINE** undeclared-but-read keys, not five:
  `data_dir`, `batch_id`, `flow` (`PipelineJobRunner`), `on_pipeline_gate` (`JobService:769`), `sleep_ms`
  (`MaintenanceJob:224`), `top` (`StorageReportTask`/`StorageTrendTask`), `history_days`
  (`ReferenceCompactor:98`), `max_attempts`/`backoff_minutes` (`SoftBounceRetryTask`). Fail-closed would
  indeed have refused working configs on day one.
  ✅ **Blast radius MEASURED, not argued: ZERO configs and ZERO keys warn.** All **21** committed `*_job.toon`
  under `spaces/` were expanded (both `job_template` instances resolved, and `SqlParamScanner`'s `$`-tokens
  counted for the two `sql.template` jobs) and diffed against their type's declarations. The diagnostic is
  quiet **by construction, not by suppression** — which is why the exclusion list stayed at ONE entry
  (`on_pipeline_gate`, read by the framework for every type), plus `flow` excused only when a `pipeline` decl
  exists, i.e. exactly when the resolver's own `config:flow` rung reads it. The other seven were deliberately
  NOT excluded: they are genuine type params a descriptor COULD declare and doesn't.
  ⚠ **The mutation proof is honest about its own limit:** stubbing `undeclared()` to `List.of()` reds 3 of the
  5 new tests; the other two assert empty lists, so they pin the EXCLUSIONS, not the detection.
  ⇒ Residuals filed: `JOB-DESCRIPTORS-LIE-TO-THE-FORM-1`, `PACKHARNESS-NO-UNDECLARED-1`. Original row follows.
  - **P2** · **`JOB-PARAM-UNDECLARED-UNREPORTED-1` — nothing reports a job param no descriptor declares.**
    Filed 2026-09-17 from the `job` census ruling. `ParameterResolver` reports `missingRequired`,
    `invalidType` and `unknownExpression` — but an **undeclared** param is silently accepted, which is the
    dead-property risk the census would have covered and cannot. The registry-aware place for it is
    `JobService`/`ParameterResolver`, where the type's `JobTypeDescriptor.parameters()` is actually known.
    ⛔ **It must be WARNING, never fail-closed:** built-in jobs read params their own descriptors never
    declare — `pipeline_config`, `data_dir`, `on_pipeline_gate`, `template`, `store` all go through
    `config.require()`/`opt()` directly — so a strict version refuses working configs on day one.
    ⚠ That asymmetry is itself worth recording: a descriptor is a UI/API contract, not the read set.
    → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`JOB-DESCRIPTORS-LIE-TO-THE-FORM-1`**~~ ✅ **SHIPPED 2026-09-17 — seven declared, two
  deliberately not.** Each `defaultValue` is the literal the reading code already passes to `opt()`, and a
  bound appears ONLY where that code already throws (`max_attempts >= 1`, `backoff_minutes >= 0`).
  ⛔ **`top` is declared UNBOUNDED on purpose** — neither `StorageReportTask` nor `StorageTrendTask` refuses any
  value, so `.min(1)` would be a new refusal invented by a declaration rather than a description of one.
  `data_dir`/`batch_id` carry NO `defaultValue`: their fallbacks are computed at run time, so the fallback is
  stated in the `description`.
  ✅ **A declaration is NOT inert** — `itemViolation` runs only over declared decls, so declaring newly switches
  on type parsing, bounds and `$`-expression resolution, where an unregistered token ⇒ REJECTED. The corpus was
  therefore re-derived, and **the design pass's own glob was wrong**: `*_job.toon` structurally misses
  `*_job_template.toon` and undercounts by three files. Re-run over all **218** tracked `*.toon`: `data_dir` × 7
  (all literal `out`, all `type: pipeline`), the other six × 0, no `$` values ⇒ **zero rejections**.
  ✅ **Two mutants proving two INDEPENDENT things:** deleting declarations reds the per-key assertions; changing
  `top` to `.min(1)` with the declaration intact reds *"'top' must stay UNBOUNDED"* — so the DECISION is
  guarded, not merely the declaration. ⚠ Stated honestly: the `defaultValue` equalities are not separately
  mutation-proven. Original row follows.
  - **P2** · **`JOB-DESCRIPTORS-LIE-TO-THE-FORM-1` — nine job params are read but declared by nobody.**
    Filed 2026-09-17 out of `JOB-PARAM-UNDECLARED-UNREPORTED-1`, which MEASURED them rather than inferring them:
    `data_dir`, `batch_id`, `flow` (`PipelineJobRunner`), `on_pipeline_gate` (`JobService:769`), `sleep_ms`
    (`MaintenanceJob:224`), `top` (`StorageReportTask`/`StorageTrendTask`), `history_days`
    (`ReferenceCompactor:98`), `max_attempts`/`backoff_minutes` (`SoftBounceRetryTask`). Each is a descriptor
    that **lies to the authoring form**: `MaintenanceJob`'s `sleep_ms` and `StorageReportTask`'s `top` cannot be
    offered, typed or bounded by any UI because no `ParameterDecl` names them. Same defect class the `min_files`
    and `materialize` comments in `JobService.java` record as already fixed — these are the survivors.
    ⛔ **Not a drive-by:** declaring them changes the published `GET /jobs/types/{id}` contract, so it needs its
    own call. → `okf/backend/control-plane/jobs.md`

- ~~**P3** · **`PACKHARNESS-NO-UNDECLARED-1`**~~ ✅ **SHIPPED 2026-09-17 — and the row held IN FULL, which is
  not this board's usual outcome.** `PackTestHarness.fire` already held a real `ParameterResolver.Resolution`
  and simply discarded `pr.undeclared()`, so the fix was the shape the row assumed. The warning now goes to the
  harness **Run Log** — `Outcome.log()`/`logged(fragment)` is the harness's existing diagnostic channel — not a
  new structured `Outcome` field, because the harness's claim is to be a faithful proxy for a real Run.
  ✅ **ONE message, TWO callers.** `ParameterResolver.undeclaredWarning(typeId, undeclared)` is now the single
  source of the text, called by `JobService` and the harness. ⛔ Two hand-written copies of one warning is the
  mirror-drift shape this repo has paid for repeatedly. The run-log warning shipped in `48784b88` is NOT moved,
  weakened or removed — same channel, same words, same ladder position.
  ✅ **Deliberate fidelity:** the warn sits AFTER the `rejection()` early return, exactly as in `JobService`, so
  a REJECTED run reports no undeclared warning in EITHER place. `rejection()` still excludes `undeclared`.
  ⚠ **The mutation proof names which half of the test is load-bearing and which is not.** Stubbing the warn
  reds exactly one assertion — `logged("thershold")`, the typo'd key by name — on an empty run log, 11/12 of the
  class still green. Its neighbour `assertEquals("SUCCESS", …)`, which pins "never becomes a rejection",
  **PASSED under the mutant** and cannot detect the regression; that is recorded in the test javadoc so nobody
  later mistakes it for a guard. Original row follows.
  - **P3** · **`PACKHARNESS-NO-UNDECLARED-1` — a Job Pack author never sees the undeclared-param warning.**
    Filed 2026-09-17. `PackTestHarness.rejection()` correctly EXCLUDES `undeclared` (it is not a rejection), but
    nothing surfaces it in the harness `Outcome` either — so the diagnostic shipped on 2026-09-17 reaches the
    run log and not the pack author, who is exactly the person authoring params against a descriptor.
    → `okf/backend/control-plane/jobs.md`

- ~~**P3** · **`REFERENCE-COMPACTOR-SAME-SHAPE-1`**~~ ✅ **SHIPPED 2026-09-17 — the row was right about the
  site and WRONG about the difficulty.**
  🔴 **"A different shape" is overstated.** It is the SAME shape as the four-site precedent: in all four,
  the check sits in the method that reads `cfg`, immediately after `PathJail.requireJobPathUnderAny`.
  `run(JobConfig)` IS that method here — a genuine one-liner, not a restructure. The only unusual thing is that
  `ReferenceCompactor` splits adapter from algorithm.
  ✅ **The file had already argued the case.** `run(cfg)`'s own PRE-EXISTING comment says the jail belongs on
  the config adapter and not on `compact(Path,..)`, because that overload is also called by
  `CollectorService.compactReferenceStore` with a pipeline's derived `dirs.database`. The refusal belongs
  there for the identical reason. The `compact(…)` guard is untouched, so the timer-driven lane keeps its
  silent no-op.
  ✅ Mutation control is again the unmodified tree: exactly ONE failure (the refusal test), with both positive
  controls green in BOTH runs.
  ⚠ **Not widened, deliberately:** the derived caller can also be handed a non-directory, but
  `CollectorService.compactReferenceStore` wraps the call in `catch → log.warn` and its javadoc says it must
  never throw (it would kill the timer) — so promoting it there buys only a warn line. That is a
  pipeline-config validation question at a different seam. Original row follows.
  - **P3** · **`REFERENCE-COMPACTOR-SAME-SHAPE-1` — the fifth `!Files.isDirectory` site, with a different
    blast radius.** Filed 2026-09-17 alongside `COMPACT-SUCCEEDS-ON-A-MISSING-DIR-1`, which fixed FOUR sites and
    deliberately left this one. `ReferenceCompactor.java:115` returns `Result.NOTHING` on a non-directory, so an
    operator-authored `reference_compact` `dir` that names a FILE reports success.
    ⛔ **The one-line fix does not transfer:** the guard sits in `public static compact(root, historyDays)`,
    shared with a NON-operator caller (`CollectorService.java:1276`, the pipeline lane, where the root is
    DERIVED, not authored). Fixing it means lifting the check into `ReferenceCompactor.run(cfg)` — a different
    shape. → `okf/backend/control-plane/jobs.md`

- ~~**P3** · **`BITEMPLATES-GATE-ORDER-1`**~~ ✅ **SHIPPED 2026-09-17 — and there were TWO inversions, not
  one.** A single interleaved loop let component 1's 409 beat its own 422 (the row's case) **and** component
  1's 409 beat component 2's 422. The `endpoint` skill's gates are properties of the REQUEST, not of a loop
  iteration — which is the part the row missed. Now pass 1 validates every component (422), pass 2 checks every
  conflict (409), then the writes run; all-or-nothing is tightened, since both refusal passes complete before
  the first `store.write`.
  ⛔ **No test, deliberately** — reaching the 422 branch means faking the gallery, and a test that cannot fail
  is this repo's most-repeated failure mode. The build-time `everyTemplateWritesABodyTheAuthoringRouteAccepts`
  already pins what keeps the branch dark.
  🔴 **A false claim was caught BEFORE it shipped:** the lane asserted `validateKind` does not constrain
  `widget`/`dashboard` at all. It does — its LAST line is the `CENSUSED_COMPONENT_KINDS` census, and a bogus
  key on a template widget was mutation-proven to 422. The gate is dark because the BODIES are hardcoded and
  valid, not because the kinds are unguarded. ⚠ That same file had already lost a day to a false claim recorded
  in a javadoc; the corrected text is now in the code. Original row follows.
  - **P3** · **`BITEMPLATES-GATE-ORDER-1` — `BiTemplates.apply` runs 409 before 422.**
    Filed 2026-09-17 when the `validateKind` gate landed there. The `endpoint` skill mandates spec/422 BEFORE
    conflict/409; `apply` checks the existing-id conflict first, so validation was placed after it to keep that
    change surgical. ⚠ **Observable only when a template is BOTH conflicting and invalid**, which no curated
    template can be today — severity is ordering-consistency, not behaviour.
    → `okf/backend/config/config-safety.md`

- ~~**P3** · **`DOC-COUNTS-GUARD-SCOPE-1`**~~ ✅ **SHIPPED 2026-09-17 — fixed at the SHAPE, and falsified
  THREE ways, not two.** `check-doc-counts.mjs` now walks `ROOTS = ['.']` minus the sibling's `SKIP_DIRS`,
  matching `check-doc-links.mjs`; the root `*.md` special case is subsumed and both scope lines print the
  deny-list.
  ✅ **The third run is the one that matters:** the SAME planted marker against the OLD guard exits **0**.
  Red-then-green only proves a guard can fail; old-green/new-red proves the widening is LOAD-BEARING.
  ✅ **The deny-list was measured, not copied.** Two entries are load-bearing on a BUILT checkout:
  `graphify-out` holds 8 real `parser-node-types` markers in dated snapshots, `inspecto-deploy` 59 in a stale
  bundle copy. ⚠ **Neither directory exists in a fresh worktree**, so a lane's own tree looks green either way
  — re-verified against the built main checkout: 68 markers in scope, **ZERO leaked** from the 67 in build
  output. Independently re-falsified there too (planted marker in `inspecto-agent/docs/adr/`, red by name,
  reverted clean).
  ⚠ **Honest correction to this row's own numbers:** 493→529 is files WALKED; in scope it is 253→289. The row
  stated only the first pair. 36 files became visible across nine trees.
  ⇒ Residual filed: `DOC-COUNTS-FENCED-MARKER-1`. Original row follows.
  - **P3** · 🔴 **`DOC-COUNTS-GUARD-SCOPE-1` — the allow-list that caused `README-LINKS-BROKEN-IN-REPO-1`
    still lives in its SIBLING guard.** Filed 2026-09-17 while re-verifying that row. `check-doc-links.mjs` was
    fixed at the SHAPE (allow-list → whole repo minus a deny-list, `ROOTS = ['.']`); `tools/check-doc-counts.mjs`
    still carries the pre-fix `const TREES = ['docs', 'compliance', '.claude']` plus a root `*.md` pass. Its own
    scope line prints **493** files where the doc-link guard now sees **529** — precisely the old blind count.
    ✅ **Latent, NOT live, and that was checked rather than assumed:** a sweep for `<!--count:*-->` markers
    outside those three trees returns ZERO, so nothing is currently unpoliced. A marked count placed in
    `inspecto/README.md` or `asn-parser/docs/` would be. ⚠ The fifth guard-scope blind spot in three days.
    → `okf/backend/build-run/build-test.md`

- ~~**P3** · **`DOC-COUNTS-FENCED-MARKER-1`**~~ ✅ **SHIPPED 2026-09-17 — and it found WHY the row existed.**
  `check-doc-counts.mjs` now carries the same `inFence` strip as `check-doc-links`, `check-doc-citations` and
  `check-bundle-doc-links` (``` and `~~~`), so a marker inside a fence is text being SHOWN, not a count being
  asserted. ⚠ **This guard was the odd one out of FIVE, not three** — the row undercounted its siblings.
  🔴 **The row said "the doc was fixed, never the guard" — the fix was a MANGLED ID, not a rewording.**
  `build-test.md` had to spell the marker with a `*` so it fell outside the `[a-z0-9-]+` id class. A doc was
  carrying a deliberate typo as a workaround.
  ⛔ **Inline backticks are deliberately STILL scanned** — a separate decision from fences despite arriving in
  one sentence of the row. The reason is measured: **the floor ratchet has drifted from its stated design**
  (68 markers against floors summing to 55), so a live marker hidden behind one stray backtick would not trip
  the floor — it would just stop being policed. That is the exclusion-masquerading-as-a-fix shape this guard's
  own header warns about. ⚠ There is also no "reuse the sibling" answer for inline: `check-vocabulary` strips
  inline spans and `check-doc-citations` REQUIRES them.
  ✅ **Measured before changing anything: 0 of 68 markers sit in a fence or backticks today**, so the change
  adds and removes no findings — what changed is a doc's WRITABILITY, and the proof is old-red/new-green, not
  red-then-green. A fourth check was added unasked: a wrong marker after the closing fence still fails, so the
  fence state cannot swallow the rest of the file. Original row follows.
  - **P3** · 🔴 **`DOC-COUNTS-FENCED-MARKER-1` — `check-doc-counts.mjs` scans fenced and quoted markers as
    live.** Filed 2026-09-17, discovered by HITTING it: writing the doc section that records
    `DOC-COUNTS-GUARD-SCOPE-1` turned the guard red, because a `<!--count:*-->` marker quoted literally in prose
    — even inside backticks or a fenced block — is counted as an assertion. ⚠ Unlike `check-doc-links.mjs` and
    `check-vocabulary.mjs`, this guard does **not strip fenced blocks**. The doc was fixed, never the guard.
    ⛔ **Widening the scope to the whole repo widened this trap to every markdown file in it**, so documenting
    markers by example is now impossible anywhere. → `okf/backend/build-run/build-test.md`

- ~~**P3** · **`README-VOCAB-SCOPE-1`**~~ ✅ **SHIPPED 2026-09-17 — fixed at the SHAPE.** Pass 3 no longer
  carries `DOC_TREES` + a named `ROOT_CANON`; it carries `DOC_SKIP = ['docs/archived-documents/']`, so every
  tracked markdown file is scanned unless there is a stated reason to skip it. `inspecto/README.md` is promoted
  into `USER_FACING` — pass 1, the NO-ALLOWLIST set — because it measured pristine and there is no audience
  below the bundle's first page; pass 3 excludes `USER_FACING` so the two cannot double-report.
  🔴 **One row claim corrected: "its tree scan covers Java/TS too" is misleading.** It does, but through
  **pass 4**, a different rule set reading identifiers and string literals. The PROSE pass reads markdown only
  — which is exactly what made the whole-repo reshape cheap rather than risky.
  ✅ **Measured before writing: 61 files newly in scope, exactly THREE violations**, all the data-origin sense
  of that word as a bare table-column header where the rule's trailing-noun lookahead cannot reach. All three
  recorded in `DOC_ALLOW` with reasons rather than "fixed" — none is a stale synonym.
  ⛔ **One is a GOLDEN FIXTURE** (`pipeline-document.golden.md`) whose text is emitted by the document
  generator and must stay byte-exact: renaming that header to satisfy a guard would have broken its test.
  ⚠ `DOC_ALLOW` was chosen over the inline `vocab-allow` marker partly because an HTML comment after a table
  row's final pipe risks breaking GFM rendering. All three are covered by the existing self-retirement check.
  ✅ The resulting scope (287 + 2 = **289**) is byte-identical to `check-doc-counts.mjs`'s independently derived
  "289 current-tier markdown files" — two guards now agree out loud on what the current doc tier is.
  Original row follows.
  - **P3** · 🔴 **`README-VOCAB-SCOPE-1` — the customer's first page is outside the vocabulary guard.**
    Filed 2026-09-17 while correcting `inspecto/README.md`. `check-vocabulary.mjs`'s `USER_FACING` list
    (`:68`) holds only `docs/USER_GUIDE.md`, and its tree scan covers `docs/**` plus the root canon — **not
    module READMEs**. So the file `package.ps1` copies to the bundle root as the customer's first page is
    unchecked for banned synonyms, and its wording had to be hand-checked against the bans.
    ⚠ **The sixth guard-scope blind spot in three days**, and the same family as
    `README-LINKS-BROKEN-IN-REPO-1` and `DOC-COUNTS-GUARD-SCOPE-1` — both of which were fixed at the SHAPE, which
    is the precedent here. ⛔ Adding it to `USER_FACING` will likely surface pre-existing violations; that is the
    work, not a reason to skip it. → `okf/backend/build-run/build-test.md`

- ~~**P3** · **`MODELPROFILE-DUPLICATE-TIERS-1`**~~ ✅ **SETTLED 2026-09-17 — intended ALIAS, not drift, and
  the evidence decided it.** `git log --follow` on `ModelProfile.java` returns **exactly ONE commit**
  (`2ecce6b6`): the file was born with identical maps and has never been edited. Drift requires two edits with
  one missed. Corroborating: the two javadocs were **independently written** (a copy-paste would have
  duplicated the prose, not written two different reasons for the same outcome), and the design space is one
  axis wide — SMALL/MEDIUM are identical across all three bundles including `PRODUCTION`, only LARGE varies
  (`7b`/`14b`), so with three bundles over a binary axis two MUST coincide.
  ✅ Both constants now carry a javadoc sentence naming the other; `DEV_LAPTOP` is kept as a distinct declared
  name so an operator's `-Dagentkernel.profile` records intended hardware and the maps can diverge later
  without a config change. `inspecto/README.md` now says the two currently map to the same models.
  ⛔ **No test, deliberately.** The only assertable fact — `CPU_ONLY.models().equals(DEV_LAPTOP.models())` —
  is **a guard that fires in the WRONG DIRECTION**: it would go red the day someone legitimately differentiates
  the maps, punishing the correct future product call. `ModelSeamTest` already pins the real invariant.
  ⚠ Two row corrections: they are `static final` **record** constants, not enum constants; and the duplication
  IS operator-visible, at `inspecto/README.md`. Original row follows.
  - **P3** · **`MODELPROFILE-DUPLICATE-TIERS-1` — two assist bundles are behaviourally identical.**
    Filed 2026-09-17 out of `README-HARDWARE-PROFILE-CLAIM-1`. `ModelProfile.CPU_ONLY` and `DEV_LAPTOP`
    (`:40-45`) carry the **same** tier map (`qwen2.5:3b/7b/7b`) despite distinct javadoc rationales, so declaring
    one or the other changes nothing. Possibly intended (a 4GB GPU cannot hold a 7B either), possibly a
    copy-paste. ⚠ Either way the operator-facing choice is currently a distinction without a difference, and one
    of the two should be documented as an alias or given its own map.
    → `okf/capabilities/assistant/assistant.md`

- ~~**P2** · **`EXPECTATION-SPEC-STALE-VS-CONDITION-1`**~~ ✅ **SHIPPED 2026-09-17 (`f30d39c5`).**
  `when` is declared `FieldType.MAP` — the `widget.controls` / `dashboard.filter` precedent, which
  validates an open map's envelope and leaves the inner tree to the parser — and `condition` is in the
  `kind` enum.
  🔴 **This row said "if anyone ever runs the spec's VALUE rules" — they ARE run, and it was LIVE.**
  `POST /validate` and `POST /config/write` both resolve `ConfigSpecs.forType("expectation")` from the
  request body, with no allow-list narrowing either. Driven over real HTTP: pre-fix a condition body
  returned `clean:false` on **two** errors, post-fix `clean:true`. ⛔ **I filed this as latent; it was
  refusing real saves.**
  ⚠ **The second error was a contradiction nobody had named:** `column` was `FieldSpec.required` while
  `Expectation:58-59` exempts exactly the `condition` kind — so fixing only `when` and the enum, which is
  all this row asked for, would have left condition expectations refused anyway. Moved to a
  `column-needed-unless-condition` cross-field rule. Strictly FEWER refusals.
  🔴 **And the fix caused a regression only the FULL reactor caught:** re-homing `column` re-anchored the
  missing-column finding from `column` to `kind`, because `CrossFieldRule` anchors on
  `affectedPaths.get(0)`. `InspectoToolsTest` — three modules downstream, and named for asserting these
  findings are *anchored* — went red. A form would have highlighted the field that is already correct.
  ⛔ **A per-module green is not a tree green, for the fourth time this shift.**
  → `okf/backend/config/config-safety.md` · original row follows.
  - **P2** · **`EXPECTATION-SPEC-STALE-VS-CONDITION-1` — `ConfigSpecs.expectation()` predates the kind it is
  supposed to describe.** Filed 2026-09-16 out of the expectation census. The spec declares **no `when`
  field** and its `kind` enum still omits `condition`, though the `condition` kind was promoted
  2026-07-18; `when` is its predicate tree, read at `Expectation.java:95` and compiled by `ConditionSql`.
  ⚠ Today this costs only a parser-only census entry — but **if anyone ever runs the spec's VALUE rules on
  an expectation, every condition expectation 422s**, and the Studio sends `when` on every save.
  ⛔ Do not "fix" it by declaring `when` as a scalar: it is a TREE, and a spec that misdescribes its shape
  is a new lie for an old one. → `okf/backend/config/config-safety.md`

- ~~**P3** · **`README-HARDWARE-PROFILE-CLAIM-1`**~~ ✅ **ANSWERED + SHIPPED 2026-09-17 — and the answer is a
  THIRD outcome the row did not offer.** The row framed it as "the behaviour exists" OR "the claim is stale".
  🔴 **The profiles are REAL and the word "auto" is the lie.** `ModelProfile.java:39-48` defines `CPU_ONLY`,
  `DEV_LAPTOP`, `PRODUCTION` with exactly those names — but `fromEnvironment()` (`:68-74`) reads
  `-Dagentkernel.profile` (env fallback `AGENTKERNEL_PROFILE`, default `cpu-only`) and hands it to a
  case-insensitive `switch`. **The operator DECLARES the profile; nothing probes the machine.** No
  `availableProcessors`, `maxMemory`, `os.arch` or GPU probe anywhere on the chain — the repo's
  `availableProcessors` call sites all size ETL thread pools and are unreachable from model selection.
  ⚠ **The trap that makes this easy to re-derive:** `ModelProfile`'s javadoc quotes hardware sizes
  ("~4GB GPU", "16GB+ GPU") as guidance for PICKING a bundle, which reads like detection.
  ⚠ **It was a transcription error, not a removed feature.** The archived `v3-agent-mvp.md:362` said the agent
  "auto-selects **tiers per profile**" — accurate. The README compressed it to "Tiers auto-select per hardware
  profile". `ModelProfile.java` has ONE commit and never contained detection code. ⇒ the row's "the only
  source was the archive" is wrong: the primary home is live code.
  ✅ Also found: `ModelProfile` is now the **legacy fallback** — `ModelProviderFactory.fromPersisted():30-34`
  prefers persisted `AssistModelSettings` and only falls back to `fromEnvironment()`. Both paths documented in
  `assistant.md` §3.4a, in precedence order.
  ⇒ Residuals filed: `README-VOCAB-SCOPE-1`, `MODELPROFILE-DUPLICATE-TIERS-1`. Original row follows.
  - **P3** · **`README-HARDWARE-PROFILE-CLAIM-1` — the README claims a behaviour no current doc supports.**
    Filed 2026-09-16 while repairing the bundle's front page. `inspecto/README.md` states assist model tiers
    "auto-select per hardware profile (dev-laptop / cpu-only / production)". **No current-tier doc mentions
    any of those three profiles**; the only source was the archived `v3-agent-mvp.md`. The citation was
    removed rather than pointed at a doc that does not say it. ⇒ Either the behaviour exists and is
    undocumented, or the claim is stale — one grep of the assist tier selection settles it.
    → `okf/capabilities/assistant/assistant.md`

- ~~**P2**~~ · ✅ **CLOSED 2026-09-23 (no code) — nothing is left to re-point**, and the "BLOCKED ON SEQUENCING" head below had been stale since 2026-09-17. Over all 37 job configs: the 6 Space `pipeline_config` values were re-pointed with `JOB-PATH-PIPELINEJOBRUNNER-SPLIT-1`; the 13 single-tenant example values were already correct (the runner resolves against `currentConfigReadRoot()` = the launch dir, and `serve-example.sh` cd's into the example dir — driven over the real `PathJail` from all 7 dirs); `store` was never a path (`819e597b`); the compactor dir moved in `d433ac0a`; the 4 `${…}` are template slots. Found on the way: `JOB-PATH-SINGLE-TENANT-GATE-BASE-1` (filed and closed the same day, see *Filed from the pipeline-building drain*). ⚠ `superpower/job-path-compat-survey.md` §4/§5(a) still says 26 — a candidate for archiving. → `okf/backend/control-plane/jobs.md`. Original row (its "24 left" and the child's "remaining 29" are both superseded by the 0 above): **`JOB-PATH-DEMO-CONFIG-REPOINT-1` — 24 left, and the remainder is BLOCKED ON SEQUENCING, not
  effort.** ✅ **Five re-pointed 2026-09-16**: the three `retention-sweep` instances, plus the **two
  compactor** values the lane held back because in ITS worktree `PartitionCompactor` was still unjailed —
  ⛔ **on master `ea862d1b` had already moved it hours earlier, so those two were orphaned by US.** Each
  driven to land byte-identically on its legacy target; pinned by
  `RetentionSweepJobPathsResolveUnderTheSpaceRootTest` (4 tests, mutation-proven — the old value resolves
  to the doubled `spaces/demo/config/data/orders`).
  🔴 **That is the one-sided break for the THIRD time in one day, and every time the reader was ours:**
  `3f384182` → `BackupTask`, `JOB-DIR-CWD-CONTAINMENT-1` → `CleanupTask`, `ea862d1b` → the compactors.
  ⛔ **Moving a reader is not done until its committed configs move with it** — and a lane on a stale base
  cannot see that it is the one who moved it.
  ⚠ **The examples' base is NOT a Space config root** — `serve-example.sh:66` passes
  `-Dassist.write.root=out/write` and registers no Space, so the base is `<example>/out/write` and the
  correct spelling is `../backup`, not `backup`. That is the trap for whoever re-points the rest.
  ⇒ **Remaining 24 = 20 real values + 4 `${…}` placeholders that are not values at all.** 19 belong to
  `PipelineJobRunner` (unmoved, and blocked itself) and 1 is `store`, which **no rule covers at all**.
  *(Original wording follows.)*
  - **P2** · **`JOB-PATH-DEMO-CONFIG-REPOINT-1` — re-point the remaining **29** committed values
  space-relative.** The remedy half of the survey. ✅ **Three of the 32 were DISCHARGED 2026-09-16** —
  see the struck block below. ⛔ Do it in the same change as whichever runtime row lands last, or the configs refuse in
  between. *(33 → 32 on 2026-09-16: `maintenance_report_job.toon:6` was re-pointed with
  `JOB-PATH-REPORT-ENRICH-SPLIT-1`, whose runtime moved in the same change. ⛔ Do **not** read that as
  licence to re-point the rest early — their readers are still on the old rule.)*
  ~~🔴 **The ⛔ above was VIOLATED the same day, in the other direction.**~~
  ✅ **CLOSED 2026-09-16 — the three backup values are RE-POINTED** (`backup_verify_job.toon:6` and
  `config_backup_job.toon:7` → `../data/backups`; `config_backup_job.toon:6` → `.`). Each new value was
  driven to land **byte-identically** on the target the legacy CWD rule produced from the repo root — the
  same behaviour-preservation proof `maintenance_report_job.toon:6` used — and each was read back through
  the real `ConfigCodec` before being trusted, because a TOON edit that parses while losing its value has
  happened in this repo before. Now pinned by `DemoBackupJobPathsResolveUnderTheSpaceRootTest`
  (`inspecto-config`, 5 tests, runs in the DEFAULT build). Mutation-proven: reverting `dir: .` alone turns
  `configBackupBacksUpTheSpaceConfigRootItself` red with the doubled path in the message.
  Verified `mvn -o -pl inspecto-config -am test` **161/0/0/0** (all 5 observed to RUN) and
  `mvn -o -pl inspecto-backup -am test -Pedition-professional` **BUILD SUCCESS** with
  `BackupJobPathResolutionTest` 6, `BackupPathContainmentTest` 3, `BackupTaskTest` 6 observed to RUN.
  🔴 **The first version of that pin was RED, and the reason is the trap this whole row is about.**
  Its control asserted the OLD value still throws `PathJail.Escape` — but `resolveJobPath` refuses only
  when the **CWD-relative** path EXISTS, and surefire's working directory is the MODULE directory, where
  `spaces/demo/config` does not exist. ⛔ **A refusal assertion pins the test's working directory, not
  the rule.** The control now asserts the *doubling* (true in both columns) plus a separate reachability
  control — one value that DOES exist CWD-relative (refuses), one that does not (re-points) — so the
  branch cannot go dead without a test going red.
  ⚠ **`docs/ops/backup-restore-runbook.md` taught the broken spelling** and was corrected in the same
  change: its backup example, its retention bullet, its containment preamble, and — the one that cannot
  be fixed by symmetry — **restore-into-a-new-space**, where the base is the config root of the Space the
  restore JOB lives in, not of the Space being restored into, so crossing spaces means `../../<new>/config`
  or an absolute path under a declared root. *(Original finding follows.)*
  ~~`JOB-PATH-BACKUPTASK-SPLIT-1` (`3f384182`) moved `BackupTask` onto
  `PathJail.requireJobPathUnderAny(…, SpaceConfigRoot.current(), …)` **without** re-pointing the three
  committed values that runtime reads. A runtime that moves ahead of its configs is the same break as
  configs that move ahead of their runtime — this marker only ever named one of the two orders.
  **Driven 2026-09-16, not mirrored:** `PathJail` compiled from the working tree
  (`javac -sourcepath "inspecto-config/src/main/java;inspecto-api/src/main/java"`), the real
  `PathJail.resolveJobPath(base, value, field)` called with `base = spaces/demo/config`; both positive
  controls re-fired (the `spaces/demo/config/jobs` refusal, and the absolute no-op). ⚠ `resolveJobPath`
  refuses **only when the CWD-relative path EXISTS**, so `Files.exists` was run on each — the column a
  value falls in is a property of the TREE, not of the value:
  - `config_backup_job.toon:6` `params.dir: spaces/demo/config` — CWD-relative path **exists** (it is a
    committed directory), so it **REFUSES in both columns**, state-independently. 🔴 **`config_backup`
    now FAILS AT RUN**, at `BackupTask:93` on field `dir`, where before `3f384182` it succeeded. It is
    **gate-blind** (under `params:`), so nothing refused it at save and nothing will.
  - `backup_verify_job.toon:6` `backup_dir: spaces/demo/data/backups` — CWD-relative path **absent on
    this tree**, so **fresh column**: a silent re-point to `…/config/spaces/demo/data/backups`.
    `BackupTask.verify` (`:182`) finds no `.zip` there and returns `JobResult.ok("no archive to
    verify…")`. 🔴 **A green pass that verifies nothing** — worse than the refusal, and the failure
    class §4 of the survey names.
  - `config_backup_job.toon:7` `params.backup_dir` — same value, same **fresh** column, but ⚠ **never
    reached**: `:93` resolves `dir` first and throws, masking it. A fix for `:6` alone would expose it.
  - *(`backup_retention_job.toon:5` `params.dir` is the fourth backup value but is **not** affected by
    `3f384182` — its reader is `CleanupTask`, moved by `JOB-DIR-CWD-CONTAINMENT-1`. Fresh column,
    silent re-point, unchanged.)*
  ⚠ `restore`'s two moved keys (`archive`, `target_dir`, `BackupTask:269-270`) classify **no committed
  value** — the survey's §2.1 proven absence stands. ⚠ `BackupTask:191` is deliberately **not** moved.
  ⇒ **Those three values were unblocked and owed**, by this row's own stated pattern (a value moves
  when its reader moves) — and are now done.~~ ⛔ **Still not licence for the other 29** —
  `PipelineJobRunner` and the compactors have not moved, so their values stay as authored.
  §3.1 of `docs/superpower/job-path-compat-survey.md` carries the driven table.
  → `okf/backend/control-plane/jobs.md`

- ~~**P2** · **`COMPONENT-KIND-KEY-CENSUS-1`**~~ ✅ **SHIPPED 2026-09-16** — the `widget`/`dashboard`
  top-level key census landed in `ComponentRoutes.validateKind` (`:640`, helpers `:654-720`), where it
  *can* work; `AcceptedConfigKeys` stays correctly a no-op for both. Accepted = `ConfigSpec` fields ∪ store
  envelope (`name`/`owner`/`shares`) ∪ a documented parser-only set ∪ the `x-` extension marker.
  🔴 **This row's envelope list was incomplete in exactly the way that mattered:** `dashboard.description`
  is read by `MetadataGraphBuilder:308` and declared by NO `ConfigSpec`, so a spec-derived accepted set
  would have refused a live read — mutation-proven, not theoretical.
  🔴 **And copying the `schema` branch's idiom would have shipped a gate that refuses nothing:**
  `ConfigLoader.validate` (`:57-69`) walks DECLARED fields only and never emits an unknown-key finding.
  The census had to be written explicitly. ⛔ **Two seams that look equivalent are not** — this is the
  second time today a "just extend the existing pattern" reading was wrong.
  ✅ **The gate immediately found SEVEN dead-key fixtures in the repo's own control-plane tests** — every
  widget fixture in four test classes saved `kind`/`title`, which nothing reads (`kind` is the bundle
  manifest's field; `title` lives under `options.title`). Repaired. ⇒ **the row's defect demonstrated on
  committed code, not hypothetically.** `inspecto` module green, 1033/0/0/0 in `inspecto-processor`; two
  mutations each red on their own assertion.
  ⚠ Deliberately NOT done: running `ConfigLoader.validate`'s field-type/cross-field rules here, which
  would start refusing drafts (a widget with no `vizType` saves today). This row is about keys nothing
  reads, not required fields. ⚠ Census is ONE level only, matching `AcceptedConfigKeys`' "an accepted
  block is accepted whole". ⇒ Residual filed as `COMPONENT-BULK-WRITERS-UNGATED-1`.
  → `okf/backend/config/config-safety.md` · original row follows.
  - **P2** · **`COMPONENT-KIND-KEY-CENSUS-1` — `widget` and `dashboard` can never be censused by
  `AcceptedConfigKeys`.** Filed 2026-09-16 out of `DUCKLE-C3-DEAD-PROPERTY-1`, which had listed them among
  its "remaining seven". They never reach `/config/write` at all: the UI saves both through
  `POST|PUT /components/{kind}` (`components.service.ts:173,194` → `ComponentRoutes`). A table in
  `AcceptedConfigKeys` is a **no-op** for them. ⚠ And their persisted body carries `name`, `owner` and
  `shares` (`ComponentAccess.java:54-55,95-96`), which no `ConfigSpec` declares — a naive spec-derived
  refusal would reject essentially every real save. ✅ **Smaller than first reported, though:** the agent
  called this "a different and bigger change", but `ComponentRoutes.java:605` **already calls**
  `ConfigSafetyValidator.check("schema", …)`, so the per-kind validation seam exists at `:602-611` and
  this is an extension of a live pattern, not a new one. → `okf/backend/config/config-safety.md`

- **P3** · **`REACTOR-VERDICT-CI-1` — wire `check-reactor-verdict.mjs` into `ci.yml`.** Residual of
  `REACTOR-HALT-IS-A-SILENT-PASS-1`. ⛔ **NOT pre-push**, deliberately: every other guard is a ~1s
  argument-free repo-state check, this one judges a BUILD, and producing a log at push time means a
  20-minute reactor per push — which the hook's own header says gets `core.hooksPath` unset entirely. CI
  already runs a full reactor, so the log is free there. Operator's call to wire.
  → `okf/backend/build-run/build-test.md`

- ~~**P2** · **`BUNDLE-DANGLING-LINKS-1`**~~ ✅ **RE-MEASURED AND FIXED 2026-09-16 under option (a).**
  🔴 **The row's 198 was wrong three ways — it is 323.** Wrong on its own terms (181/13, not 187/11); it
  counted **no audience links at all** though `BACKLOG.md` (22) + `PROJECT_NOTES.md` (8) were withheld the
  same day by the same decision; and it missed 70 that break because the bundle ships no source tree.
  **97 of the 323 are in `INDEX.md` alone** — the customer's front door.
  ⛔ **Option (b) was REFUTED by this project's own assertions**, not merely costed out: every marked stub
  would land under `docs/archived-documents/` or `docs/superpower/`, which the `DOCS TIER LEAK` /
  `DOCS AUDIENCE LEAK` throws shipped the day before exist to forbid. ⇒ **a fail-closed guard refusing a
  DESIGN, not just a bug** — worth knowing they do that.
  ✅ **(a) shipped**: `package.ps1` step 7 rewrites a markdown link whose target is withheld, keeping the label and
  appending `(internal document - not shipped)` in place of the link, fence-aware so quoted examples are not corrupted. Driven
  against the live tree: **224 neutralised** — the exact figure measured independently — and
  `tools/check-bundle-doc-links.mjs` (new, falsified both ways) goes **323 → 67** over the staged bundle.
  ⚠ **Deliberately scoped to the withheld set only.** The remaining 67 cite repo source paths that never
  ship — a customer reading *"see `ControlApi.java`"* loses nothing — and papering those over at package
  time would hide real rot from the repo's own guard. ⇒ The guard is **NOT wired into CI**: red on master
  by design until the source-path class is decided. Original row follows.
  - ~~**P2** · **`BUNDLE-DANGLING-LINKS-1` — the shipped INDEX points at documents the customer does not
  have.**~~ ✅ **CLOSED 2026-09-17 — already resolved, this is stale duplicate text.** Re-measured with
  `tools/check-bundle-doc-links.mjs` (simulated mode, no `pwsh` needed): the raw repo-level count into the
  two withheld trees is now **194** (181 `archived-documents` + 13 `superpower`, `docs/INDEX.md` still the
  worst offender at 97) — essentially the same ballpark as this row's original 198, not the 323 the sibling
  entry above measured (that figure also folded in the 31 audience-file links and the 4 root-climbing ones,
  which this row never counted). The owed call this row asked for was **already made and shipped**, one
  entry up in this same file: option (a), rewrite at package time — `package.ps1` step 7 neutralises every
  link into a withheld tree/file to `label (internal document - not shipped)`, fence-aware, driven against
  the live tree with 224 neutralised. This simulator intentionally does not model that text-rewrite (it
  only simulates the bundle's FILE SET, per its own header comment), so it still reports the pre-rewrite
  count — that is the tool working as designed, not a regression. No further code change needed; this row
  is a leftover unstruck copy of the "Original row follows" text the sibling entry already reproduced and
  closed. Options (b) stays refused (would land stubs under the very trees the tier/audience guards forbid
  staging) and a fresh (c)-style README caveat would only restate what (a) already fixed.
  → `okf/backend/build-run/build-test.md`

- ~~**P3** · **`WORKTREE-PROVISIONING-1`**~~ ✅ **CLOSED 2026-09-16 — one half FIXED, the other half
  REFUTED, and the refuted half was MY OWN claim.**
  🔴 **Defect (1) NEVER EXISTED.** `asn-parser/asn-decoders` is **fully tracked — 70/70 files**, nothing
  under `asn-parser/` is untracked at all, it is root-reactor module `pom.xml:44`, and both live worktrees
  were checked and DO contain it (`pom.xml` + 58 java files each). ⛔ **The “proof” was a SILENT ZERO:**
  `git ls-tree HEAD asn-parser/` run from a MODULE SUBDIRECTORY prints nothing and **exits 0**, because
  `ls-tree` pathspecs are CWD-relative. Reproduced deliberately. ⚠ **I propagated that false claim into
  five agent briefings, this row and a commit message, and five agents concurred with it** — concurrence is
  not corroboration when every agent ran the same broken probe. ⇒ **Run `ls-tree` from the repo ROOT, or
  with `-r --name-only -- <path>`, and treat an exit-0 empty result as UNPROVEN, never as absence.**
  (This is the same class as the grep-helper literal-quote false zeros recorded earlier.) No hand-copy was
  ever needed, and since nothing had to be newly tracked, **`DATA-GOV-1` was never at risk** —
  `asn-parser/.gitignore` is deny-by-default over `corpus/*`, with `corpus-synthetic/` the tracked exception.
  ✅ **Defect (2) WAS real and is FIXED.** The probe was built as
  `Path.of("").toAbsolutePath().relativize(<@TempDir>)`, encoding depth-of-CWD `..` segments; where module
  CWD depth **==** TEMP depth (both **6** here) the ladder climbs to the filesystem root and reconstructs
  the authored path, so the value EXISTS under the Space root and `resolveJobPath` returns instead of
  refusing. ⚠ **Correction to the original filing: the suppression is at `PathJail.java:183`**
  (`if (Files.exists(spaceRelative)) return spaceRelative;`), **not** the `!equals` guard at `:186` — and
  `:186`'s `!spaceRelative.equals(cwdRelative)` is therefore **dead**, since `:183` has already returned
  whenever the two are equal (pre-existing, left untouched, worth a row if anyone cares).
  ⛔ **Fixed in the PROBE, not the assertion** — the standing refusal is honoured and all three assertions
  are byte-for-byte unchanged; the value is now a plain CWD-relative `target/job-path-probe-<uuid>`.
  ⚠ **A shallower worktree root was considered and REJECTED as unsound:** the collision is
  `module-CWD-depth == TEMP-depth`, and TEMP depth is a property of the MACHINE (6 on this Windows profile,
  1 on Linux `/tmp`), so any fixed depth is a different lottery number — the main checkout passed at depth 3
  by luck, not design. A preflight check was also rejected: after this fix there is no mis-provisioned
  state left for it to detect.

- ~~**P2** · **`REACTOR-HALT-IS-A-SILENT-PASS-1`**~~ ✅ **SHIPPED 2026-09-16** (`eae6fd7f`) as
  `tools/check-reactor-verdict.mjs` — it cross-checks exit code, Reactor Summary and surefire-report
  **mtimes** against the build window read from Maven's own footer. `NON-VERDICT` is now a distinct
  required outcome for the `verify-runner` agent: **a SKIPPED module is UNVERIFIED, not passing.**
  🔴 **The premise was real and WIDER than filed, and the third mechanism is the one that matters:
  `mvn clean` only cleans modules the reactor REACHES**, so a halted build leaves every downstream
  module's `target/surefire-reports/*.txt` from the PREVIOUS run — green, plausible, unmarked.
  ⛔ **Which makes this project's own authoritative verify recipe the delivery mechanism.** `build-verify`
  said *"do not parse the log — SUM THE SUREFIRE REPORTS"*; measured on the real 32-module reactor, a
  build forced to die at `asn-core` with **30 modules SKIPPED** reported `MODULES=25 TOTAL=4667
  failures=0` against a true green of `MODULES=26 TOTAL=4694`. A clean sheet, 27 tests off baseline. That
  recipe is **demoted to an exploratory count, never a verdict**.
  ⚠ **The row's own remedy was insufficient**: requiring a per-module `Tests run` total does not help —
  the halted tree HAS those totals for 25 modules, they are merely stale. **Report FRESHNESS is the
  load-bearing check** and the row never mentioned it.
  🔴 **The guard shipped the very trap it exists to catch, and only testing on reality found it:** its
  first row regex required a `…` dot leader, but Maven emits **zero dots** when a module name is long, so
  it parsed 12 of 32 rows and called them all SUCCESS — blind to 20 modules. It now self-checks its parse
  against Maven's *Reactor Build Order* and fails rather than under-report. Falsified both ways on the
  real reactor plus four synthetic reds.
  ⚠ `build-test.md` had carried this warning **as prose since 2026-09-08** and it prevented neither
  2026-09-16 occurrence. ⛔ **A third prose warning was never going to be the fix.**
  ⇒ Residual filed as `REACTOR-VERDICT-CI-1` (wiring). Not verified: `-fae` with multiple failing
  modules; non-English Maven locale. → `okf/backend/build-run/build-test.md` · original row follows.
  - **P2** · 🔴 **`REACTOR-HALT-IS-A-SILENT-PASS-1` — an upstream red makes downstream tests report as
  “no failures”.** Filed 2026-09-16, and it nearly landed a false verdict TWICE today. A red in an upstream
  module (e.g. `inspecto-config`) **halts the reactor**, so every module under test is `SKIPPED` — and under
  `mvn -q` that reads as a clean run. One agent came within a sentence of reporting a pass on tests that
  **never executed**. ⚠ Related trap, hit twice: **`-DfailIfNoTests=false` is NOT the flag** —
  `-Dsurefire.failIfNoSpecifiedTests=false` is, and the wrong one turns every upstream module into a
  reactor-halting red. ⇒ Remedy: make `verify-runner` and the `build-verify` skill require a **per-module
  `Tests run` total**, and treat a SKIPPED module as a **non-verdict**, never as a pass.
  ⛔ This is the verification-discipline half of what `WORKTREE-PROVISIONING-1` exposed, and it OUTLIVES
  that row — it is not worktree-specific.

  - **P3** · ⚠ **`WORKTREE-PROVISIONING-1` — every fresh git worktree is a FALSE RED, twice over.** Filed
  2026-09-16 after four parallel agents each hit one. **(1)** `asn-parser/asn-decoders/` is **untracked**
  (`git ls-tree HEAD` returns 0 files under `asn-parser`), so worktree creation never populates it and
  `inspecto-engine` fails to compile with *“package com.gamma.asn.facade does not exist”* until the subtree
  is copied in by hand. **(2)** Any checkout at `.claude/worktrees/<name>/<module>` sits at **path depth 6**,
  which coincides with the `@TempDir` depth, so `JobPathResolutionTest.theAmbiguousCaseIsRefusedAndNames
  BothPaths` reconstructs the authored path byte-for-byte and its `!spaceRelative.equals(cwdRelative)` guard
  at `PathJail.java:186` suppresses the throw ⇒ **red in any worktree, green in the main checkout** (depth 3)
  and green in CI. ⛔ **Do NOT make either test robust to its own location** — that hides the seam.
  ⇒ Either track the asn corpus, or teach worktree creation to copy it, and pick a worktree root whose
  depth does not collide. ⚠ Standing rule meanwhile: **isolate the CONTENT but keep the checkout under the
  same parent path**, and treat these two reds as environmental until proven otherwise.
  - **P3** · **`JOB-DIR-CWD-CONTAINMENT-1` — a job's `dir` containment resolves against the JVM's CWD, not the Space root, and is decided by a process-wide static set.** Filed 2026-09-15 after `ControlApiJobCrudTest.snakeCaseTriggersAndFlatParamsRoundTrip` failed (422 expected, 200 returned) in every clean worktree created under `%TEMP%` — at `41aa5267`, `87a4d97c` AND `a831172f`, i.e. it predates all of that day's work.
  🔴 **It is NOT a flaky test and NOT a live containment defect — it is a real seam worth fixing.** `JobRoutes.parseJob` calls `ConfigSafetyValidator.check("job", raw, SafetyPolicy.defaultPolicy())` with **no `configDir`**, so `ConfigSafetyValidator.resolveRef` (`:489-495`) resolves a relative `dir` via `toAbsolutePath()` — against the **process working directory**. The candidate is then tested against `SafetyPolicy.defaultPolicy()`'s allowed roots, which include `DiscoveredRoots` — a **process-wide static set** every test's `SpaceManager` populates with space bases under `java.io.tmpdir`. When the checkout (hence surefire's CWD) is itself under `%TEMP%`, `../../outside` normalises to a path that can share a prefix with another test's leaked `%TEMP%` root ⇒ **accepted**. Under `C:\sandbox` it cannot ⇒ refused.
  ✅ **Proven by relocation, not by argument:** a worktree at the *same* commit with *zero* uncommitted changes PASSES when created under `C:\sandbox` and FAILS under `%TEMP%`. ✅ GitHub CI at `6770d031` refuses correctly, so **the shipped-shape path is sound — P3, not a security regression**.
  ⇒ **The durable fix is passing the Space root as `configDir` at that call site**, which makes the check independent of BOTH the CWD and `DiscoveredRoots`. ⛔ Do not "fix" this by making the test robust to its own location — that hides the seam the test is accidentally reporting.
  ⚠ **Method lesson, for anyone verifying in a worktree:** isolate the CONTENT but keep the checkout under the SAME parent path, or a path-containment test legitimately reads a `%TEMP%` checkout as "outside". → `JobRoutes.java:369` · `inspecto-config/.../safety/ConfigSafetyValidator.java:489` · `inspecto-config/.../safety/DiscoveredRoots.java:41`

  🔴 **REGROUNDED 2026-09-15 — the row's "durable fix" CANNOT WORK, and the seam is a semantics choice, not a
  call-site edit.** *"Pass the Space root as `configDir` at that call site"* was checked against the code:
  (a) `checkJob` (`ConfigSafetyValidator.java:124-126`) validates its six path keys through `checkPath`, which
  **ignores `configDir` entirely** — the 4-arg overload's own javadoc says `configDir` is *"used ONLY for config
  refs… `dirs.*` are data directories and stay working-directory-relative"*; (b) even routed through
  `resolveRef` (`:489-497`), a config-relative candidate wins only when it `startsWith(base) && Files.exists`,
  and `../../outside` satisfies neither — so it would fall straight back to `toAbsolutePath()` against the CWD,
  the very resolution the row objects to. ⇒ **Passing `configDir` changes nothing for a job.**
  ⚠ **And the run-time side resolves the same way**: `CleanupTask:35`, `PartitionPruneTask:39` and
  `StorageReportTask:45` go through `PathJail.requireUnderAny` → `PathJail.require` (`PathJail.java:165`),
  which is `Paths.get(s).toAbsolutePath()` — CWD-relative. The 422 gate and the run-time jail therefore
  AGREE today; a validator that resolved job paths against the Space root while the tasks kept resolving
  against the CWD would pass a draft the run then jails (or the reverse) — the exact split `resolveRef`'s
  javadoc was written to end. ⛔ So the fix is not one call site: it is **making job path values
  Space-root-relative at BOTH the gate and every task**, which changes what every existing job's relative
  `dir` means. That is a config-semantics decision → filed in §1.
  ✅ What stays true: the trigger is `DiscoveredRoots` being a process-wide static set (the
  `SPACE-UNKEYED-STATICS-1` family), and the shipped-shape path is sound (CI refuses correctly). P3 holds.

- ~~**P3** · **`BACKLOG-STALE-LEGACY-POM-1`**~~ 🔴 **REFUTED 2026-09-16 — the pom EXISTS, and the row is
  itself an instance of the false-zero it sits next to.** `legacy-code/pom.xml` is real: it is
  **`asn-parser/asn-decoders/legacy-code/pom.xml`**, a declared `<module>` of `asn-parser/asn-decoders/pom.xml:21`,
  which is itself root-reactor module `pom.xml:44`. Its `<sourceDirectory>../../src/main/java</sourceDirectory>`
  resolves to exactly `asn-parser/src/main/java` — **so the two rows this row accused (§2 `DATA-GOV-1` and
  §3 Parsing/Stage-1) are CORRECT and must be left alone**, and `asn-parser/src` is live, compiled code.
  ⛔ **The row's probe looked for a TOP-LEVEL `legacy-code/` directory** and read the empty result as
  absence — the same class as `WORKTREE-PROVISIONING-1`'s `git ls-tree` zero directly above. A pom is
  found by a repo-ROOT `pom.xml` sweep, never by `ls` at one guessed depth.
  ✅ Re-measured: `asn-parser/asn-decoders` = **70 tracked files** (the row's own figure, accurate);
  `asn-parser/src` = **66 tracked files**, all reached through that `sourceDirectory`.
  ⚠ The pom's own description is worth carrying forward: *Phase 0 only… deleted with them after Phase 4* —
  so `asn-parser/src` is **deliberately temporary**, not dead. That is a lifecycle fact, not a defect.

  - **P3** · ⚠ **`BACKLOG-STALE-LEGACY-POM-1` — two rows cite a `legacy-code/pom.xml` that DOES NOT EXIST.**
    Filed 2026-09-16. Two places on this page assert *“`asn-parser/src/main/java` is NOT dead (it is compiled
    by `legacy-code/pom.xml`)”*. There is **no `legacy-code/` directory in the repo** (verified) and no pom
    references `asn-parser/src`. ⇒ Either the claim is stale or `asn-parser/src` is genuinely dead code with
    no build home — ⛔ ground WHICH before actioning either row, because **both use this claim to justify
    keeping the tree**. ⚠ Note `asn-parser/asn-decoders` (the reactor module, 70 files) is a DIFFERENT thing
    and is definitely live; do not conflate them.

- ~~**Doc-lifecycle archival OWED from 2026-09-16**~~ ✅ **DISCHARGED 2026-09-16 — both plans are ARCHIVED.**
  `docs/archived-documents/plans-archive/route-gating-compliance-plan.md` (`f000598f`, *"archive the route-gating plan, distil the control into auth-security"*) and
  `docs/archived-documents/plans-archive/dataset-column-derivation-plan.md` (`7dbdc75c`, *"archive the dataset-column derivation plan, distilled"*).
  Re-measured: **neither name exists under `docs/superpower/` any more** (9 entries remain there, none of them these two).
  ⚠ The row's deferral reasoning was sound and is worth keeping — it is the *state* that moved on. Original row follows.

  - **Doc-lifecycle archival OWED from 2026-09-16** — two plans whose work SHIPPED that day are still in `docs/superpower/`: `route-gating-compliance-plan.md` (steps 1 · 2a/2b/2c · 3 · 4a–4e ALL done) and `dataset-column-derivation-plan.md` (all four steps done). ⛔ **Deferred deliberately, not forgotten**: archiving a plan requires editing `docs/INDEX.md`, and at handoff a **peer session held INDEX dirty AND was editing the derivation plan itself** — moving either would have swept their uncommitted work into someone else's commit, which has happened in this tree before. ⇒ do it on a clean tree: distil the as-built into the matching `okf/` concept (the route-gating control is already written up in `compliance/evidence/route-gating.md`), `git mv` both to `plans-archive/`, update `INDEX.md`, then `graphify update .`.
- **Doc-lifecycle violations** (shipped work still in `docs/superpower/`; the rule is distil → `git mv` to
  `plans-archive/` → update `INDEX.md`). Re-grounded 2026-09-07 — **two of the four listed rows were wrong**:
  - ✅ `living-operational-system.md` — **DISTILLED + ARCHIVED 2026-09-07.** The north star is now the OKF
    concept `okf/living-operational-system.md`; every citation (`GLOSSARY.md`, `REQUIREMENTS.md` ×2, this
    page) was repointed in the same change. 🔴 Its "what exists today" column was deliberately **not**
    carried — it described gaps its own R4/R5 slices had closed and cited two files deleted with the mock
    backend, which is the general lesson: a north star states shape, a state column rots.
  - ✅ `consignment-elt-architecture.md` — **DISTILLED + ARCHIVED 2026-09-07**, and **completeness-KPI K5 is
    discharged with it**. 🔴 K5's filed blocker was itself wrong: §11.4 had carried a SUPERSEDED banner since
    `51ca57f7`, the same commit as §8's. The real debt was the ~12 OTHER sites still reading as live sealing
    design (§8.4's SLA object — which §8's own banner never named — §9.3's `sealed-complete` baseline rule,
    §10.2's seal-policy 422, §11.5's `partition.sealed` signals, §11.6's two rows, §13's three, the header
    status table). Discharged by ONE authoritative archive banner enumerating every superseded site, which is
    more robust than a dozen scattered edits that can each miss one. Thirteen durable facts distilled into six
    OKF concepts.
  - ~~`compliance-certifications-plan.md`~~ — **NOT a violation** *(as of 2026-09-07)*. ⚠ **Superseded 2026-09-09: it was
    ARCHIVED anyway** — `docs/archived-documents/plans-archive/compliance-certifications-plan.md`, and `INDEX.md:177`
    records it there with a banner enumerating what it got wrong (⛔ *"do not quote it as a decision of record"*), the
    distillation targets (`okf/capabilities/compliance/compliance.md` §3.10/§3.2) and the still-open `N1`–`N7` on §2.
    ⇒ Re-measured: **nothing named `compliance-certifications-plan.md` exists under `docs/superpower/`.** The 2026-09-07
    text below ("it stays live") is kept only to show why the earlier pass declined to move it.
  - ~~`step-workbench-design.md`~~ — **was already archived 2026-09-06.** Row was doubly stale (file moved; decision made).
  - ✅ `pipeline-spec.md` + `pipeline-waves-drain-plan.md` + `elt-final-amendment-plan.md` — **ARCHIVED
    2026-09-10** (operator: archive now, after a distillation diff; row 15 stays on this board). ⚠ The
    earlier rule here — *archive together when Row 15 closes* — was superseded: a plan is archived when its
    durable content is distilled, not when the last release gate clears.
  - ✅ `gate-register.md` — **ARCHIVED 2026-09-07.** Its own retirement trigger had fired and it had become
    actively misleading (§3.5 and §3.3 still framed items resolved weeks earlier as open calls). Its one durable
    note is now `okf/index.md` §*How to read this tier*.
- **REQUIREMENTS MoSCoW / edition columns** — §3.1 ACQ-4, §3.9 SPC-5 and §3.15 UI-8 were **fixed 2026-09-07**
  (all three were contradicted by their own §5 and by the code; UI-8 had read "IN-FLIGHT, uncommitted, another
  session" for two months over a pane that shipped 2026-07-07). An authority note now says `EDITIONS.md`'s matrix
  wins for the Edition column. Still open, same root cause — the column predates the 2026-09-02 "not for Personal"
  decisions: ~~SEC-8~~ (reconciled 2026-09-08 — `okf/capabilities/security/security.md` §2 owns the row),
  ~~**OPS-2**~~ (reconciled 2026-09-08 — `okf/capabilities/observability/observability.md` §2 owns the row; its cell said `S/E`, not `All`), ~~**INV-2**~~ (reconciled 2026-09-08 — `okf/capabilities/studio/studio.md` §2 owns the row; like `OPS-2` its cell said `S/E`, not `All`, and EDITIONS `CP-09` had already gated it; ~~INC-2/3/4~~ reconciled 2026-09-08 — `okf/capabilities/incidents/incidents.md` §2 owns those rows), and ~~**DAT-6** wants a caveat that
  the multi-user half is unbuilt~~ (the caveat is in the REQUIREMENTS cell; retired 2026-09-08). ⚠ ACQ-4's *other* half is unresolved and needs grounding, not a doc edit:
  ~~EDITIONS' generated board marks `SP-ACQ-06`/`SP-ACQ-08` (S3/GCS) planned while the **connectors** ship with tests
  — check whether the *Step processor* exists before flipping `ProcessorCatalog`, because a connector is not a Step.~~
  ✅ **DISCHARGED 2026-09-16 — re-measured, and the board does NOT say what the row says.** `EDITIONS.md:193`/`:195`
  mark both **🟡 PARTIAL, not 🔲 planned**, in all three edition columns, and the source of that generated board —
  `ProcessorCatalog.java:66,68` — already carries the exact grounding this row asked for:
  *"Connection kind exists (… connector …); no proven end-to-end acquisition-node run"*. ⇒ Nothing to flip and
  nothing to check: the connector-is-not-a-Step distinction is already encoded in the cell. ACQ-4 is closed whole.
  → `REQUIREMENTS.md` · `EDITIONS.md`
- **Compliance repo-side artifacts (moved out of §2, 2026-09-07)** — these are file-existence checks, not
  external gates, and sitting in "externally gated" made them look unactionable: the **customer
  verification runbook** (G2 half), the **CI-evidence doc**, a **recorded restore drill** (G6 — the
  statement exists at `compliance/evidence/rto-rpo-statement.md` with operator-fill targets and an empty
  drill table), **G8** RBAC R5 evidence and **G9** FIPS. Each closes when its file says so.
  → `compliance/controls-matrix.md` §4
- **Template seed-pack enrichment (frontend C7)** — ongoing, not a discrete item: `kpi-overview`,
  `quality-monitor`, `trend-monitor` today. → `okf/frontend/features/studio.md`
- **`-Dassist.token` / `-Dassist.read.token` mentions in the docs — LEAVE THEM** (from `DOC-DEADTOKEN-1`,
  closed 2026-09-14; recorded here 2026-09-15 when that row was deleted). ⚠ **Every remaining mention in
  the tree is a HISTORICAL record and was deliberately left alone** — which is true and worth keeping.
  ⛔ A symbol sweep that "fixes" them is undoing the closure.
  🔴 **The row's file list was WRONG and is corrected here (re-measured 2026-09-16, repo-root sweep for
  `assist.token` / `assist.read.token`).** Stated: five files — `security.md`, `control-api.md`,
  `auth-security.md`, `EDITIONS.md`, `incidents.md`. Derived: **one of those five** carries the symbol
  (`EDITIONS.md:61`). The current-tier mention sites are `EDITIONS.md:61` ·
  `okf/backend/build-run/operations-reference.md` · `PROJECT_NOTES.md`, plus two LIVE scripts —
  `inspecto/package.ps1:917` and `tools/run-backend.ps1:97` (both comments recording the 2026-09-14 removal).
  Three archived files also carry it and are out of scope by tier.
  ⛔ **Why the wrong list is dangerous, not merely untidy:** `security.md`, `auth-security.md` and
  `control-api.md` match on `assist` only because they document **`-Dassist.write.root`** — a DIFFERENT,
  **shipped and live** key (`security.md` §`SEC-9` marks it ✅ SHIPPED, all editions). A sweep steered by
  this row's list would have edited the live write-gate docs while missing three of the five real sites.
  ⇒ The rule stands, the addresses did not: **grep the symbol from the repo root, never re-use a list a
  previous pass wrote down.**
  → `okf/backend/build-run/operations-reference.md`
- **A capability with no committed example is a capability nobody has ever run** (rule recorded 2026-09-15
  from `COLLECTOR-SPACE-ROOT-1`). Twice in one shift the same shape appeared — `task: materialize` had no
  committed job, and `collector.dataset` has no committed pipeline — and both were space-blind in ways
  only a live multi-Space run could show. ⇒ **The missing example is the risk signal, not a documentation
  gap.** ⛔ Do not read "the tests pass" as "the path has been exercised" for any capability that nothing
  in `spaces/` or `inspecto/examples/` declares. (`acquisition.md` §8.4 and `ingestion.md` are instances
  of this rule; this is the rule itself.)
- **GRAPHIFY-1 tool sync** — ⚠ the row's own check is blind: `.graphify_version` and `graphify --version` both
  read `0.9.53` while `.claude/skills/graphify/SKILL.md` (since DELETED — see the closure below) differed from the installed package's copy by ~300 lines
  (the repo copy carries a uv/pipx detection block labelled "fixes #831" that 0.9.53 does not). Either re-sync from
  the package or record why the repo copy deliberately diverges — comparing version markers will never tell you.
  (The optional `graphifyy[sql]` half is **already satisfied** — `tree-sitter-sql 0.3.11` is installed.)

  ✅ **DECIDED 2026-09-15:** **RE-SYNC from the installed package.** ⛔ **Capture the `fixes #831` uv/pipx
  detection block in the commit message BEFORE overwriting**, and verify `graphify` still runs
  afterwards — re-syncing **deletes a fix the package does not carry**, so the mitigation is that the
  block stays one revert away, not that the loss is imaginary.
  ⚠ The concern was raised and the operator chose re-sync anyway; recorded so the trade is visible
  rather than looking like an oversight. ⚠ Note `.claude/` is checked in **deliberately** in this
  repo — a skill-file divergence is a team-wide fact, not one machine's drift.
  ⛔ And the row's own lesson stands regardless of the outcome: **comparing version markers will
  never tell you this** — both read `0.9.53` while the files differ by ~300 lines.

  ✅ **RE-SYNCED 2026-09-15 — the repo copy is now byte-identical to the installed package** (755 → 713
  lines). The row's original ask (*"either re-sync from the package or record why the repo copy
  deliberately diverges"*) is discharged. ⚠ **It was executed AFTER its premise was refuted, on the
  operator's reaffirmation** — the refutation stands and is kept below, because it names what the
  re-sync cost.
  🔴 **THE ROW'S STATED PREMISE WAS FALSE.** It claimed the repo copy carries a uv/pipx block
  *"that 0.9.53 does not"*. ⛔ **REFUTED.** Measured: the installed `site-packages/graphify/skill.md`
  mentions `uv`/`pipx`/`#3028` **6 times** — it has the same fixes, in its own dialect.
  🔴 **The real divergence is a PLATFORM PORT, not a missing fix.** The package copy contains **18
  ```bash blocks**; the repo copy contains **18 ```powershell blocks** — the same count, because the
  repo copy is a 1:1 Windows port of the same skill. The two-way diff is **171 lines only in the repo,
  129 only in the package** (the "~300 lines" this row quoted is their sum, not a one-way gap).
  ⇒ **The re-sync therefore replaced every PowerShell block with bash** on a win32, PowerShell-primary
  sandbox. ⚠ The repo copy also carried a second named fix the row never mentioned: `#3028`, where
  PowerShell 5.1's `Out-File -Encoding utf8` writes a BOM that rides into the saved interpreter path and
  fails the hook rebuild with **WinError 123**. Both that and the `#831` uv/pipx detection are preserved
  verbatim in the re-sync commit's message, and the pre-re-sync file is one `git show` away.
  ✅ **Verified after the swap**: the frontmatter is intact so the skill still loads, and `graphify
  --version` still reports `0.9.53`.
  ✅ **CLOSED 2026-09-19 — graphify UNINSTALLED at the operator's request, so the remaining question
  (does the team need the PowerShell port back?) is moot.** Removed: the `graphifyy` CLI (both the pip
  0.9.53 and a second pipx 0.8.44 install — ⚠ **there were two**, and `pip uninstall` alone left the
  binary on PATH), `.claude/skills/graphify/`, `scripts/setup-graphify.*`, `graphify-out/` (311 MB,
  gitignored), the two PreToolUse advisory hooks, and every live doc/config reference.
  ⚠ **Deliberately kept**: `graphify-out` stays in the SKIP_DIRS of `tools/check-doc-{citations,counts,links}.mjs`
  and `tools/check-secrets.mjs` — a stale checkout may still hold the 311 MB tree, and the guards should
  keep skipping it rather than scan it. The PowerShell port is one `git show` away in history.
  ⚠ The row's own lesson survives intact and is now doubly earned: **comparing version markers will
  never tell you this** — both read `0.9.53`.



- ~~**P2** · **`DOC-GUARDS-SCAN-IGNORED-SOURCES-1`**~~ ✅ **FIXED 2026-09-22, all three guards** (`check-doc-citations.mjs` first, then `check-doc-links.mjs` and `check-doc-counts.mjs`). Each now derives its SUBJECT set from `trackedPaths()` (`git ls-files`), exactly as the row prescribed and as *guard-coverage.md* §*A fourth shape* already required; each scope line now NAMES how many untracked files it skipped, because an unprinted scope is an unaudited one. ⚠ **Falsified in BOTH directions before trusting it**: a gitignored `PROBE.local.md` carrying a dead citation, a dead link AND a wrong count is ignored by all three, while the same dead citation and link in a TRACKED doc still fail by file and line. ⚠ What it gives up, stated plainly: a NEW doc is unchecked until `git add`ed — the same condition the link guard already had. **Kept STRUCK for provenance per §0's found-and-fixed rule.** Original report: **three doc guards read UNTRACKED, GITIGNORED markdown as a subject, so another session's local note can block your push.** `git push` on `master` was refused 2026-09-22 by the pre-push citation guard over `SESSION_STATUS.local.md:20` — a file that is untracked and gitignored (`.gitignore:124`, `*.local.md`), rewritten by a stop hook on every session, and owned by a peer working something unrelated. Measured with a gitignored `PROBE.local.md` at the repo root: **`check-doc-citations.mjs`, `check-doc-links.mjs` and `check-doc-counts.mjs` all name it and exit 1**, while `check-vocabulary.mjs` (`git ls-files` only) and `check-secrets.mjs` / `check-nul-bytes.mjs` (tracked-only, by design) do not. Root cause is the subject side, not the target side: `tracked-paths.mjs` already moved path RESOLUTION to `git ls-files` for exactly this reason (`LINKGUARD-CASE-1`, 2026-09-14, whose header names `.claude/sessions/snapshot.md` as the same file class), but `check-doc-citations.mjs:227` still collects its markdown with `readdirSync` + a `SKIP_DIRS` deny-list that cannot exclude what git would not hand you. ⛔ Two consequences: such a guard is **not reproducible between shifts** — red for one session and green for another on the same commit, the property a gate exists to deny — and it is **unfixable by its own rules**, since editing the offending line does not survive the hook (verified: the correction that unblocked this push was gone within the hour). Remedy: derive the subject set from `git ls-files` like `check-vocabulary.mjs` already does. The rule is already written down — *guard-coverage.md* §*A fourth shape* says to prefer `git ls-files` over `readdirSync`; this is that rule not yet applied to the subject half. → `okf/backend/build-run/guard-coverage.md`.

## 6. Standing refusals and won't-do (not work — keep so nobody re-files)

One line each; the reasoning is in the pointer. Reopen only on the stated trigger.
*(Triggers audited 2026-09-07 — every countable one was recounted against the code; none had fired.)*
🔴 **That parenthetical describes 2026-09-07 ONLY, and is no longer the board's state.** On 2026-09-15
**fourteen triggers FIRED in a single sitting** (§1) — ⛔ so *"audited, none had fired"* must never be read
as a standing property of this section. ⚠ A trigger audit is a photograph, not a guarantee; re-run it
whenever someone asks what is still gated.

- **Publishing eoiagent `0.1.0` to a registry (`EOI-7b`)** — ⛔ decided 2026-09-15: **CI keeps building it
  from the upstream tree.** Moved here from §2, where it had sat as an externally-gated item implying
  someone was waiting to publish; nobody was. ⚠ The licence blocker that would have prevented publishing is
  gone either way — `eoiagent-parent` declares **Apache-2.0** since 2026-09-14 — so this is a deliberate
  choice, not an inherited constraint. 🔴 **The cost is now PERMANENT rather than temporary**: neither
  `ci.yml` nor `release.yml` pins a `ref:`, so both follow that repo's default branch, and a commit pushed
  to the wrong branch there reaches no CI while looking landed — which has already happened once. Whether
  to pin the `ref:` is an owed input in §1. Trigger: a consumer outside this repo needs the artifacts, or
  the upstream build time becomes a CI constraint. → §1 owed inputs

- **A removed Job pack leaving a stored pipeline unloadable** — ⛔ decided 2026-09-13: **accepted risk, not
  work.** `JobPackManager.java:278-279` already carries the exposure as its own inline comment and states the
  workaround in the same breath: *"the same exposure a Job typed on an unloaded pack already has, which is why
  a pack is normally REPLACED rather than removed."* ⚠ It sat at P3 instead, so every sweep re-grounded a
  question that had already been answered in the code. Trigger: an operator removes rather than replaces a pack
  in a live install, or a guard is wanted at unload time. *(Was `PACK-UNLOAD-EXPOSURE-1` in §3; the original
  row's grounding is preserved in git history at `fa3780e4`.)*
- **Mapping sidecars for the committed schemas** — ⛔ decided 2026-09-10: inline `mapping:` is the norm; **0** of 24
  schemas use a `*_mapping.csv` and nothing depends on one (the single sidecar in the tree is untracked evidence).
  Trigger: an operator picks the sidecar form in the mapping editor for a committed pipeline. → `MAPPING-GEN-1` (§3)
- **`quality.schema.drift` refusing a file** — ⛔ decided 2026-09-10: **detection only**. A width change already
  rejects rows or quarantines; refusing a header renamed at equal width would block feeds that parse fine. Trigger:
  an operator asks for a refuse policy by name. → `okf/backend/pipeline-graph/step-catalog.md` DQ row

- **ARCH-OPS-SCC** LEAVE (85-file ripple — recounted 2026-09-07, still **exactly** 85) · **ARCH-F-CARVEOUT**
  LEAVE (148 refs / 28 files — recounted, 27 files, module split unchanged) · `{etl, etl.unpack}` and
  `{agent.kernel.*}` SCCs LEAVE · intra-module `ops↔ops.link/workflow`, `catalog↔catalog.spi` cycles are
  same-family · **M2** `CollectorService` decomposition → `okf/backend/modules/reactor.md`
- **C2** store-pair base — reopen at the **7th** store; recounted 2026-09-07: **4 true `InMemory*`/`Db*` pairs**
  (Object, Link, Note, TagAssignment), 5 counting `DbStatusStore` by shape. Not close. · **C4** BOM — reopen on
  an external consumer; there is none and **nothing is published as a Maven artifact** (releases ship zip
  bundles; eoiagent is an upstream dependency, not a consumer). · **C6** connection reuse — warm open **24 ms**
  (min 23 / max 27, n=20), no contradicting measurement exists. ✅ **HOMED 2026-09-10 (Sprint 7.2)** in
  [`okf/capabilities/editions/editions.md`](okf/capabilities/editions/editions.md) §6.1 — they were archived
  without being distilled, and `reactor.md`, which this row pointed at, never held them. 🔴 **Two counts here
  were wrong and are corrected at the new home:** `C2` is **half shipped** — the `Db*` side landed 2026-08-18
  as `AbstractJdbcStore` (`JAVA-5`), *20 days before* the recount that called it untouched, with **five**
  subclasses; and the true-pair count is **5**, not 4 (`DeliveryReceiptStore` became a pair on 2026-09-07, the
  recount's own date). What is actually left is the **`InMemory*` half** — all ten implement their interface
  with no shared base. ⛔ Anchor any future reading on the item's TEXT: that plan uses `C2` for two different
  items and `reactor.md` uses `C2`/`C4` for unrelated things.
- **PATH-2 residual** (moved from §4 2026-09-07 — it is a LEAVE, not work) — the `BackupTask.restore` zip-slip
  jail is PINNED by `MaintenanceLibraryTest.restoreRefusesAnArchiveEntryThatEscapesTheTargetBeforeWritingAnything`
  (the page cited a `…ASidecarEntry…` variant that does not exist — a tampered sidecar is refused a layer
  earlier). Family (a), the three store `fileFor` helpers — `ViewStore:100`, `PipelineStore:101`, `ComponentStore:351`
  and `PipelineWatermarkStore:56` — **four sites, not three** (recounted 2026-09-07), none importing `PathJail` — LEAVE unless someone is in those files anyway; their line
  numbers have now drifted three times, which is itself the argument. ⛔ Routing `ControlApi.serveStatic:866`
  through `PathJail.contains` is a posture change needing an operator call — grounded 2026-08-26 "do not build it".
  → `okf/backend/config/config-safety.md`
- **Unpack (10) crash mid-archive** (moved from §4) — re-ingests committed members; relies on
  `OVERWRITE_OR_IGNORE` idempotence (`PartitionWriter:186`, documented at `UnpackOrigins:32` and
  `ConsignmentIngestor:284/508`). By design; revisit only with a measured cost. ⚠ X1's `CommitRetry` does **not**
  cover this — it records only a *returned* FAILED, and a crash writes no attempt record.
  → `okf/backend/engine/unpack-stage.md`
- **`inspecto-query`/`inspecto-job`/`inspecto-enrich` module extraction** (moved from §4; the row said
  `fp-*`, stale since the artifactIds became `inspecto-*`) — build only on explicit request. Measured
  2026-09-07: `job` → `signal` + `ops`, but **`query` → `signal` only** and **`enrich` → neither**, so the
  old "`query`/`job` depend on `signal` + `ops`" claim was over-broad and `enrich` is the one clean
  candidate. `SharedDottedPathGrammarTest` still up-imports `notify` and would still need cutting.
  → `okf/backend/modules/reactor.md`
- **Vocabulary: the living-system terms are ADOPTED, not proposed** (row corrected 2026-09-07) — `GLOSSARY.md`
  already carries **Signal**, **Consequence**, **Decision Engine** and **Result Set** as binding (the last three
  annotated "§6-proposed → binding (R5)"). Only *Query* and *Parameter* were never formally adopted; use them
  as ordinary words, not as capitalized concepts. → `okf/living-operational-system.md` §Vocabulary
- **PKG-5** agent-absent is the intended shipped default; ⛔ no `package.ps1` switch until the JDK 25+ vs Java 24+ floor is resolved → `okf/backend/build-run/build-test.md`
- **D11 caps** — `max_temp_directory_size` gets no default; preview/dry-run connections stay uncapped; the semaphore-computed cap is rejected → `okf/backend/engine/duckdb.md`
- **D7 startup backfill** full object scan (`ObjectService.backfillTagAssignments:479`, called from
  `CollectorService:475`) — deliberately unfixed. ⚠ **The stated trigger cannot fire as written**: "shows up in
  measured startup time", but nothing in the repo measures startup — no JMH, no timing test, no recorded
  baseline. Reopening it means *first* adding a startup measurement. → `okf/backend/control-plane/tags.md`
- **MNT-14** — no UI surface / no shipped Job instance (operator opts in); retention derived not stamped; scoped to `ObjectType.INCIDENT`; ⚠ `ObjectQuery`'s 9-arg constructor is load-bearing → `okf/backend/control-plane/jobs.md`
- **`transform.merge` attributes — REFUSED 2026-09-07, the same day MERGE-ATTRS-1 was filed as a defect.**
  The row was right that `RowShaper.merge` reads `type` (`union`|`inner`|`left`) and `on` off the node config
  and that `NodeAttributes` declares neither. Its **cause and severity were both wrong.** `transform.merge`
  is absent from `PipelineEditable.LOWERABLE` and from `RECIPE_VERBS` **by decision** — the same set as
  `transform.split`/`select`/`derive`/`validate`, and `PipelineEditable:196` says admitting them would
  "silently reverse all of those". A graph carrying one therefore **refuses at save** with
  `UNSUPPORTED_NODE` (422; pinned by `ControlApiPipelineCrudTest` on the sibling `transform.derive`), and
  neither `PipelineJobRunner` graph source can carry one: the flat `pipeline_config:` path has no home for
  it, and the `pipeline:` path reads the store that refused it. So the node is executable code with **no
  authoring or persistence route at all** — it is not "stuck on union", it is unreachable. ⛔ Declaring
  attributes would hand a config pane to a node that cannot be saved, which is the exact defect the
  `transform.sql` flat-config-home lesson records. The authorable successor is the PLANNED Step Processor
  `transform.join.merge`, whose catalog entry already calls `transform.merge` "the grandfathered … read-only
  ancestor". Reopen only as an operator decision to make merge authorable — that is the four-registration
  recipe plus a deliberate reversal of a standing refusal, not a one-line attribute table.
- **`mail.send` has no true CC** — needs a CC-aware `@PublicApi` SPI overload, not until a second caller asks; ⛔ never a second SMTP transport
- **WRITE-1** — the implicit adoption ambiguity stays, documented at the code; ⛔ never teach the server the UI slug rule
- **`AiDraft.prerequisites` shared applier** — single producer; extract only when a second tool gains prerequisites
- **AGT-6a tool `args` runtime validation** declined (contract test instead) — revisit after all **23** tool
  schemas are audited; still 23 (`InspectoPackTest:61` pins the count) and the audit has **not** run: the
  2026-07-27 cross-adopter pass covers **5 of 23** — its 6 payloads span 5 distinct tools (`query_author`
  twice), and the test asserts nothing about its own list's size. Precondition unmet.
- **AGT-5 embedding recall** parked (`CaseStore` is a 256-cap ring)
- **D8** digest deliveries correlate to the digest, not per notification; `deliverWithReceipt` escape hatch only if a provider won't echo `Message-ID`
- **Time zone of incoming data (a)** — no editor for `raw.fields[].timezone_column` by decision; a fifth "data offset wins" tier is a separate build; ⛔ never reached by relaxing the `%z`/`%Z` gate → `okf/backend/engine/duckdb.md`
- **Unpack (5)(8)** — a partial archive never fails its Consignment; nested archives refused (`depth` = 1); with `processing.unpack.enabled: false` the same-file engine divergence returns (operator opt-out)
- **Collector rename residual** — the pipeline TOON `source:` block stays (renaming breaks authored TOON); `'SOURCE'` stage category unchanged → `okf/backend/gotchas/cross-cutting.md`
- **Geo map** — DuckDB `spatial` extension deferred (zero `ST_*` demand); progressive loading obsoleted by `GEO_POINT_CAP = 5000` → `okf/frontend/features/geo-map.md`
- **Catalog** — offline `/db/query` returns 501 (honest degrade); `EntityProjectionGraphSource`, Geo point/route sources, `ReconExecService` stay offline arms; ⛔ "backfill the `table` attr" REFUTED — do not re-file → `okf/frontend/features/catalog.md`
- **`endSessionUrl` / server-published OIDC config** — not buildable as scoped; 🔴 if ever built, `session.service.ts` uses `??` so a server-sent empty string beats `environment.oidc` → `okf/backend/editions/auth-security.md`
- **The connector sidecar is NOT edition-gated** (CONNECTORS-BUNDLE-1, shipped 2026-09-07) — remote
  acquisition is a core capability and `EDITIONS.md` marks SFTP shipped in all three editions, so
  `inspecto-connectors.jar` rides every bundle. ⚠ It costs ~32 MB (BouncyCastle via sshj, plus
  kafka-clients). Reopen ONLY if Personal must be leaner than that — the copy is one `if` in
  `package.ps1`, but gating it means correcting the SP-ACQ rows to match.
- ~~**`mail.send` returns SUCCESS when no channel is configured**~~ ✅ **REVERSED 2026-09-17 (operator:
  "fix all of them")** — it now returns `JobResult.skipped("no email channel configured — nothing sent")`:
  `SKIPPED`, neither green nor a red run on every fire. The refusal had kept a scheduled mail job reading
  green while delivering nothing — the false-green shape this board records as its commonest defect. The
  pending-MAJOR release notes need the status word corrected. → `okf/capabilities/incidents/incidents.md` §3.8
- **Kafka is not the data path** — decided in the consignment-ELT design and never re-opened: urgency is a *parameter on one node*, not a second execution model. ⛔ Do not re-file "add a Kafka lane"; a Kafka **Collector** (SP-ACQ-09) is a different, open question.
- **EXPR-1** — expression interpolation inside a longer string only ever per-declaration opt-in, never global
- **BUNDLE-1 perf question** — `no-cache` on content-hashed chunks vs `immutable`; unmeasured; only if a revalidation storm is observed
- **Decided 2026-09-06, keep as designed:** the fetch lane stays FIFO (revisit on the first observed fetch-lane wait) · `requireTopLevelSinks` is a depth rule, not a jail · bounce/complaint handling stays manual until receipts persist · JAVA-SIMP-2 stops at seam #2 (no defect hangs on the sink casts) · the `batch_id` trio rides the MAJOR (release notes hold it) · D-7 `materialized` is done-by-absence · ~~**Postgres multi-user is PARKED** until a multi-operator install exists~~ 🔴 **PARK LIFTED 2026-09-15 — the trigger FIRED**: the operator confirmed a multi-operator install, so the row is re-ranked P2 in §3. ⛔ Do not re-file this as a park · unpack roll-up + entry grain ratified · SEC-07 Vault/KMS only when a client policy requires it · deployment D1–D8 signed as recommended (🔴 **D3 was signed as a 2GB default that DOES NOT EXIST** — corrected 2026-09-09; `DuckDbUtil.memoryLimit(null)` returns `null`, no `scheduler.toon` ships, and this file's own GAP-4 row says so. See `okf/capabilities/editions/editions.md` §5.3 and `okf/capabilities/pipeline-execution/pipeline-execution.md` §2.4).
- **Working as designed** (from the archived gate register §5): write-root 503 · `ConfigSafetyValidator` 422 · `PathJail` 403 · 409 conflict · `ExpressionGuard` · `SqlGuard` · BI share tokens · active-pipeline delete refusal · Incident resolution backend-gated · editions = build flavors · ~~`AuditTrail` has no *authentication* events~~ ✅ **REVERSED 2026-09-17**: `AuditTrail.authentication` records `auth.exchange` / `auth.refresh` / `auth.logout`, refusals as `ACCESS_DENIED` (`ControlApiAuthSessionV1Test.sessionLifecycleIsAuditedRefusalsIncluded`); the IdP's own credential check (MFA, password) stays out of scope; *authorization* decisions were always in (`access.denied`/`access.granted`, ABAC A5) · air-gap CI · append-only registry · manifest owns existence · nothing prunes by default · 🔴 ~~`-Djobs.maxConcurrentRuns` is the only bound~~ **STALE — refuted by D11 and by a second bound** (corrected 2026-09-09): the Run cap is **ON by default at 4** in code (`JobService.DEFAULT_MAX_CONCURRENT_RUNS`), owned by `scheduler.toon`, with the flag a bootstrap default only; and the INGEST engine has its **own** semaphore plus a per-pipeline `PipelineRunGuard`. Two bounds, neither governed solely by that flag — owner §2.5/§3.3 · refused: Spring/Quarkus, distributed-by-default, per-record lineage, Lens-as-permission, PIP-1, sink-owned `partitions`, Decision-Rule + `route:`, raw `Connection`, `CREATE MACRO` outside AUTHORING-REDESIGN-1 (d), step-workbench S3.

## 7. Duplicate map (same work, several names — update all when closing)

*(Rebuilt 2026-09-07. The previous table carried six dead aliases, two canonical rows pointing at the now-empty
§1, and four duplicate pairs it never recorded. A dead alias is worse than none: it makes a closed row look open.)*

| Canonical row | Also appears as |
|---|---|
| Row 15 — ELT Phase 6 deletion half (§2) | pipeline-spec §12 row 15 · Platform Services Stage 2 precondition (§3) · 🔴 ~~`BatchGraphRunner` parity blocker~~ **THAT CLASS DOES NOT EXIST** (the live one is `ConsignmentGraphRunner`) (corrected 2026-09-09 — see §3) · §5 "archive pipeline-spec + waves-plan when Row 15 closes" |
| D13 parser field tiers (§2) | `superpower/parser-field-tiers-interview-plan.md` (the interview-#2 kit, back in the active tier 2026-09-16) · `okf/frontend/features/grammar-config.md` (the pre-agreed analysis rule) |
| AGT-5 `DryRunProvider` (§2) | AGT-6b row (§2) · `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2 |
| EXECUTION-RESIDUALS X4 record-level replay (§3) | `okf/frontend/features/run-detail.md` + `USER_GUIDE.md` "reprocess is whole-batch only" · `INDEX.md`'s `EXECUTION-RESIDUALS-SKETCHES` pointer (which cites §4 — the row is in §3) |
| `batch_id` rename trio — §6 (decided: rides the MAJOR) + §2 release notes | `okf/backend/control-plane/api-stability.md` §Release notes (D-12) · `archived-documents/plans-archive/consignment-elt-architecture.md` deferred renames · `elt-final-amendment-plan.md` D-12 / Phase 7 · `okf/backend/engine/db-layer.md` (cites §4 — no such row) |
| X-Actor full removal (§2) | `okf/backend/editions/auth-security.md` §Still-open · `EDITIONS.md` SEC-11 · `REQUIREMENTS.md` R4. 🔴 The §2 row's stated gate ("the API-v1 sunset") names apparatus **deleted 2026-07-25**, and `api-v1.md` never mentions X-Actor — re-state the gate before working it |
| Completeness KPI hold (§2) | Completeness KPI K2/K4/K5 (§3) · `archived-documents/plans-archive/completeness-kpi-plan.md` |
| Compliance program NFR-7 (§2) | SOC 2 Type II window (§2) — the same observation window, twice in one table |
| Deployment topology live validation (§2) | Deployment topology gaps (§3) · §6 "D1–D8 signed as recommended" — the §3 row's gate is already discharged |
| Postgres multi-user — 🔴 §6 park LIFTED 2026-09-15 (trigger fired), now a P2 build row | §3 Postgres multi-user row — now says PARKED and points at `plans-archive/` (this row's "contradicts §6 / dead pointer" note was stale by 2026-09-08); `EDITIONS.md` OPS-03 |

**Deleted 2026-09-07:** *Three disagreeing name rules* — resolved **2026-08-17**
(`okf/backend/control-plane/pipeline-identity.md`), not on 2026-09-06, and all three of its aliases were dead or
stale. Its live successor is the new §3 row **NAME-DIRS-1**.

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays authoritative),
then **delete the row here**. Do not leave a strikethrough as-built narrative behind — the previous page
grew to 505 KB that way before the 2026-09-06 consolidation. This page lists **open work only**.
