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
> **54<!--count:backlog-rows--> rows: 1<!--count:backlog-p1--> × P1 · 37<!--count:backlog-p2--> × P2 · 16<!--count:backlog-p3--> × P3** — recounted again on the way OUT of the 2026-09-16 parallel
> shift, which filed four rows (it was 50 / 35 / 14 on the way in). ⚠ **The board grew while four items
> were worked**, and that is the honest result, not a failure: all four were already SHIPPED or REFUTED,
> and grounding them produced five residuals that were previously invisible. ⛔ One row
> (`ACQUIRE-LEDGER-DUPLICATE-RESOLUTION-1`, closed as refuted) is still counted above and is **pending
> sweep** — its refutation text must land in an owning doc BEFORE the row is deleted.
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
> ⚠ **Only the 1<!--count:backlog-p1--> P1 + 37<!--count:backlog-p2--> P2 rows are queued work.** §0 defines **P3 as demand-gated — "build only when
> someone asks by name"** — so those 16<!--count:backlog-p3--> are mostly a list of things deliberately *not* being built, not a
> backlog to burn down. Reading all 54<!--count:backlog-rows--> as pending work overstates what is owed by roughly 42%.
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
Clean-tree inventory: **172 mutating routes — 109 gated, 63 exempt, 0 undeclared.**
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
- M — `TYPEFLOW-DATASET-COLUMNS-1` steps 3+4 ✅ **UNBLOCKED 2026-09-16 — the verdict came back KEEP `temporalColumn`**, which is exactly what the clause assumed —
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
verdicts → `SPEC-DEADSEAM-1` + the `temporalColumn` clause of Consignment addressing + `TYPEFLOW-DATASET-COLUMNS-1`
Q2 · "a spreadsheet library" → D-8 XLSX · "access details" → `DEPLOY-SERVICE-WRAPPER-1` (a run, not a build) ·
"job path semantics" → `JOB-DIR-CWD-CONTAINMENT-1` · pick the next Step Processor partial BY NAME → the catalog row
· commission the SES/SNS adapter review → Notifications + `D8-SUPPRESS-1`. "Eager/deferred `s3://`", the two
dates, RTO/RPO, the stray `master`, the `ref:` pin unblock no §3–§5 row directly.

**Design-first (a decision pass before code, by the row's own words):** ~~`GRAPH-LANE-MULTISCHEMA-1`~~ ✅ **design pass DONE 2026-09-16** (`archived-documents/plans-archive/graph-lane-multischema-design.md`) — the executor question was answered empirically, the row shrank, the operator answered the one call it then needed (`auto` flips), and it SHIPPED the same day — off the board · ~~`GRAPH-LANE-RULE-ROUTED-1`~~ ✅ **design pass DONE and SHIPPED 2026-09-16** — and its question ("where does a rule-routed destination become a graph node, when the lift cannot see the rules?") was **dissolved, not answered**: `DecisionRuleApplier` writes the routed rows above the fork and then `DELETE`s them from the relation (`DecisionRuleApplier.java:233-237`), so both lanes see the identical remainder and no graph node was ever needed — off the board · AI drafting on non-schema kinds ·
Onboarding D5-ref (ground the real delete-feed first) · Branch-aware residuals (b)(c) · `PIPELINE-DRYRUN-1` (which
seam enforces the mode) · `STREAM-CONSUMER-1` (where the loop lives) · Bundle/Exchange load-as-draft ·
Completeness KPI (b) sequence-template source · `AUTHORING-REDESIGN-1` (e).

**Row → row chains:** `PIPELINE-DRYRUN-1` → `EXECUTION-RESIDUALS` X4 (replay default) · dead-seam verdict (2) →
`TYPEFLOW-DATASET-COLUMNS-1`, Consignment addressing · ingress path-routing (scale-out plan §5.5, **no board row**)
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
reason §1 has never carried one is that nobody thought to file one here. Four of the six block rows whose
CODE IS ALREADY COMPLETE — ⛔ so a shift reading those rows will look for something to build and find
nothing, which is the shape that kept `DEPLOY-SERVICE-WRAPPER-1` looking open for days.

| Owed input | Blocks | Why only the operator can supply it |
|---|---|---|
| **Two dates** — when the off-repo backup bundle was deleted, and when the no-reuse check completed | the `SEC-INCIDENT-1` CC6.1 line (§2) | Both acts happened off-repo and leave no trace here. ⛔ A line dated *when it was written down* would misstate the evidence to an auditor |
| **Per-tier RTO/RPO targets** | `compliance/evidence/rto-rpo-statement.md` · §2 Deployment topology | ⛔ Decided 2026-09-15: do **not** transcribe the signed §3.14 numbers. In `editions.md` they are an engineering target; in `compliance/evidence/` they are a commitment an auditor holds you to, and those are not the same number by default |
| **A spreadsheet library** | `D-8` XLSX export (§3) | There is **no spreadsheet dependency in any pom**. Picking one IS the gate — the row was deliberately written so the new-dependency question gets answered when someone needs the feature, not in advance |
| **Access details** — MinIO endpoint/key/secret, the systemd host, the elevated Windows box | `AIRGAP-S3-EXTENSIONS-1` (§5) · `DEPLOY-SERVICE-WRAPPER-1` (§3) | ✅ Operator confirmed 2026-09-15 that all three EXIST. Both rows are **code-complete and evidence-blocked** — neither needs a build, only a run |
| ~~**Pre-materialise cap — unit + remainder policy**~~ ✅ **ANSWERED 2026-09-16: cap on BYTES, and the remainder DEFERS to the next run** (not refused, not counted in files) | the Pipeline graph row's pre-materialise cap clause — now unblocked | Bytes because remote bandwidth is the scarce resource a file count does not bound — one huge file blows through a count. ⛔ Deferring makes the ledger load-bearing: it must remember what was skipped, or a file larger than the cap, or one that keeps losing the race, is **starved forever**. Design that starvation guard with the cap, not after |
| ~~**Dead-seam verdict (2) — `temporalColumn`**~~ ✅ **ANSWERED 2026-09-16: KEEP it.** ⇒ `TYPEFLOW-DATASET-COLUMNS-1` steps 3+4 are UNBLOCKED and are again the only **M**-size row on the board; the `temporalColumn` clause of Consignment addressing stands; `SPEC-DEADSEAM-1` still owes verdicts (1) and (3) | — | Recorded here rather than struck so the other two verdicts stay visible |
| ~~**A spreadsheet library** for `D-8` XLSX export~~ ✅ **ANSWERED 2026-09-16: Apache POI**, and the feature is WANTED (the row was demand-gated and the demand arrived) | `D-8` XLSX export (§3) — now unblocked | ⚠ POI is the conventional JVM choice and the widest on format support, but it pulls a sizeable transitive tree into a reactor that today has **no spreadsheet dependency at all**, and every edition bundle grows with it. ⛔ Add it to ONE module, not the parent, and check the bundle-size and dependency-review guards before assuming it lands quietly |
| ~~**Job path semantics**~~ ✅ **ANSWERED 2026-09-16: a relative path in a Job config resolves against the SPACE'S CONFIG ROOT**, never the process working directory | `JOB-DIR-CWD-CONTAINMENT-1` — now unblocked | Consistent with the path-jail model and portable across hosts; ⚠ a Job whose meaning depends on how the server was launched is the defect this closes. Check for configs relying on the old behaviour before shipping |
| **Delete the stray `master` branch** on `jotder/inspect-agent` | nothing directly — it is a live trap | Needs a permission no shift has had. ⚠ Neither `ci.yml` nor `release.yml` pins a `ref:`, so both follow that repo's **default** branch; a commit pushed to the stray branch reaches no CI and looks landed |
| **FOUR route calls** (was three — `POST /objects` added 2026-09-15 when step 2b found manual Incident/Case creation is the `/recon/promote` question again; all four sit in `CapabilityManifest.PENDING_OPERATOR_CALLS`, and step 3 waits on them) — `POST /spaces/import` (does the `POST /spaces` "additive, recovery route" decision extend to bundle import?) · `POST /tags/rules/{id}/apply` (operate action → `canOperateRuns`, or collaboration act → open, like assignments?) · `POST /recon/promote` (which family owns *manually opening an Incident*? — `canAuthorWorkbench` has no precedent, `DecisionRoutes` uses `canOperateRuns`, `ExpectationRoutes` is ungated, no Incident capability exists) | `ROUTE-UNGATED-DEFAULT-1` step 2 → **step 3 (the fail-closed default) cannot land until these are decided**. ✅ **THREE OF THE FOUR ANSWERED 2026-09-16:** `POST /spaces/import` → **yes, the `POST /spaces` additive/recovery posture extends to bundle import** (stays open, exempted explicitly by step 3) · `POST /tags/rules/{id}/apply` → **collaboration act, leave OPEN** (consistent with assignments, not with run operation) · `POST /recon/promote` → **create the Incident capability the domain lacks** rather than borrowing `canOperateRuns` or `canAuthorWorkbench`; ⚠ it also gives the currently-ungated `ExpectationRoutes` a home, and ⛔ its dedupe lines are the ones the `(type, key, column)` decision rewrites — do both in one change. ✅ **AND `POST /objects` ANSWERED 2026-09-16: the same new Incident capability**, deliberately — it is the same act as `/recon/promote` (manually opening an Incident) and must not acquire a second precedent. ⇒ **ALL FOUR ROUTE CALLS ARE ANSWERED; `ROUTE-UNGATED-DEFAULT-1` step 3 is UNBLOCKED.** ⛔ Step 3 is still not a pure code change: the Incident capability does not exist in `Roles` yet, so it is created first, and the two Incident routes plus `ExpectationRoutes` adopt it together with the `(type, key, column)` dedupe rewrite. | Each was grounded 2026-09-15 and is a genuine question, not a formality — the handler, the comment and the tests point different ways. ⚠ `/recon/promote`'s dedupe lines are the same ones the `(type, key, column)` decision rewrites; do both in one change. → `archived-documents/plans-archive/route-gating-compliance-plan.md` §2a |
| ~~**Confirm reads-open-by-policy as the COMPLIANCE position**~~ ✅ **AFFIRMED 2026-09-16** — write it into `compliance/controls-matrix.md` CC6: read routes are open **by policy**, because confidentiality is enforced at the Space/ABAC layer. ⇒ the route-gating plan's §3e is unblocked | `archived-documents/plans-archive/route-gating-compliance-plan.md` §3e · `controls-matrix.md` CC6 | ⚠ This is now an auditor-facing CLAIM, not only an engineering posture: if reads are ever gated, the matrix line moves with the code |
| **Which framework(s) the evidence is written against** | the route-gating evidence report under `compliance/evidence/` (step 4e, not yet written) · the `controls-matrix.md` row mapping | SOC 2 Type II is assumed throughout the plan; ISO 27001 A.9 maps onto the same evidence but the matrix rows differ |
| **Job path semantics** — should a job's relative `dir` / `data_dir` / `backup_dir` / `archive` / `target_dir` resolve against the **Space root** instead of the JVM's working directory? | `JOB-DIR-CWD-CONTAINMENT-1` (§5) | 🔴 Regrounded 2026-09-15: the row's own fix (pass `configDir`) is a no-op for jobs, and the gate and the run-time tasks BOTH resolve against the CWD today, so they agree. Moving only the gate splits them; moving both changes what every existing job's relative path means. That is a semantics call, not a bug fix. |
| **Eager or deferred resolution of an `s3://` `dirs.database`** — validate a profile at PARSE time (forces the deployment-root / pipeline-field split, because `CollectorService` parses every pipeline before `loadConnections` runs) or resolve at FIRST WRITE-TIME USE (no split; `dirs.database` is a plain `String` nothing resolves at parse) | scale-out phase C §5.4 bullet 6 (the credential surface that `AIRGAP-S3-EXTENSIONS-1` left behind when it closed 2026-09-15) | The bootstrap order is measured (`ServiceBootstrap.buildFrom:69` vs `:73-74`); which side of it to build on is a design posture only the operator sets. |
| **Three verdicts for `SPEC-DEADSEAM-1`'s survivors** — (1) `ExpressionProvider`: retire the never-registered THIRD-PARTY extension point (the interface itself is the live expression engine) or keep it as SPI; (2) `DatasetRelation.temporalColumn`: keep unwired (an active plan's Q2 tie-break depends on its throw-on-two behaviour) or wire it; (3) `LegacyVendorFunctions`: it is in-repo load-bearing (ServiceLoader-registered, called by `RTDMS_ASN_Test`, the PLUGIN_GUIDE's worked example) — document it as the canonical plugin, or nothing | `SPEC-DEADSEAM-1` (§4) | The standing verdict ("DELETE all four") was refuted for three of four; two deletions would have removed live code. Each survivor is a different kind of question and no default is safe. |
| **Approve or decline pinning a `ref:`** in `ci.yml` / `release.yml` | nothing yet — filed here so the question is not lost | 🔴 The 2026-09-15 decision to keep building eoiagent from its upstream tree makes the unpinned `ref:` **permanent rather than temporary**, which changes it from a tolerable shortcut into a standing exposure. Offered at the sitting; not answered |
| **Pre-materialise cap: the UNIT and the REMAINDER policy** — bytes or files per cycle before the remote fetch; and whether files over the cap wait for the next cycle or are refused | the Pipeline graph row's pre-materialise cap (§3) | Nothing exists to extend (checked 2026-09-16): `IntakeGovernor` caps files AFTER listing; a pre-fetch cap needs the connector to expose size before download, which is a connector-SPI question, and the remainder policy is a product call. Filed 2026-09-16 |

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

- **P2** · **AUTHORING-REDESIGN-1** — open letters (⚠ the old "(j)(l)(n2)(o) are in §1" clause was stale in all four: (o) SHIPPED 2026-09-07 as WORKBENCH-S4 — all three slices, (l) and (n2) SHIPPED, (j) `engine: auto` was already answered by shipped code; (f)(g)(m) SHIPPED 2026-09-06 — `JOIN_REFERENCE_MISSING`/`JOIN_ON_MISSING`/`UNKNOWN_JOIN_REFERENCE` at save, `SchemaMappingDrift` on all three schema save paths, `?pipeline=` sent by the UI): (c) v2 structured AST table over the SQL for WHERE/JOIN editing — ✅ **precondition DISCHARGED 2026-09-07: it does.** `json` is statically linked into the DuckDB JDBC artifact, so nothing is installed or auto-loaded and the seal is irrelevant to it: `json_extract`, `json_structure` and — the one that matters — **`json_serialize_sql`**, which returns the whole parsed AST as JSON, all work on a sealed connection while `INSTALL excel` and re-opening `enable_external_access` still fail. Pinned by `SqlSandboxTest.jsonWorksOnASealedConnection`. ⚠ So (c) reads an engine-produced AST rather than re-implementing a SQL parser in TypeScript — the same refusal the step workbench made for reference detection; (d) v3 macros as the UDF registry (per-connection re-creation in `EnrichmentEngine`, `PipelineJobRunner`, `ConsignmentIngestStrategy`, preview) — demand-gated; (e) column metadata editing on the Transform pane (Parse D2) — needs a backend home for metadata on a `transform.sql` node first; (i) per-row "sample resolves to" line — no host resolves a sample against an `AttributeSpec`. Still open on (f): which COLUMNS the reference carries is the dry-run's question (it reads the store); the save checks existence and `on` presence only. → `okf/frontend/features/schema-mapping-authoring.md` §0
- **P2** · **Step Processor catalog** — 119 processors: **36**<!--count:processors-delivered--> delivered / **16**<!--count:processors-partial--> partial / 67 planned (`processor-catalog.contract.json`, counted 2026-09-10 — `quality.schema.drift` DELIVERED 2026-09-10 as a per-batch `quality.schema_drift` Signal; the earlier count was 34/18 on 2026-09-08 — the earlier "69 planned" was a grep artefact) (`transform.lookup` DELIVERED 2026-09-06). Each partial is a product decision (Kafka consumer, XPath grammar, drift report, profiler, resampler, KPI layer, Jinja, graph tagging, commit controller, SLA object, view/email/webhook sinks…) — pick one by name. → `EDITIONS.md` §Step Processors · `okf/backend/pipeline-graph/step-catalog.md`

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
- **P2** · **Canonical-pipeline selective bundle export/import** — the metadata bundle's `authored-pipeline` kind still targets the RETIRED `*_flow.toon` `PipelineStore`; a canonical `*_pipeline.toon` transfers only via the datasource zip or the client-side stream-config bundle. Wanted: one selective export/import with dependency closure (schemas, per-segment schemas, grammar/enrichment companions, Connection as secret-free requirement) and retire/repoint the `authored-pipeline` kind. **Decided 2026-09-06 (operator): in bundle manifests `schema` = the REGISTRY id (`registry/schemas/<id>`); a pipeline-owned `<name>_schema.toon` (+ its `_mapping.csv`/`_structure.csv` siblings) travels under its own kind, not as `schema`.** Apply this in `BundleRoutes`/`transfer/bundle.ts` when the row is built → `okf/frontend/features/onboarding.md`

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
- **P2** · **Onboarding ↔ Pipeline unification W4/W5** — (W0 PROVEN 2026-09-06: `LiftLowerFixtureSweepTest` runs every `spaces/**/*_pipeline.toon` through the editor's own seam — `PipelineEditable.toMap` → codec → STRICT lower — and all 22 survive verbatim; it stays as the standing gate.) W4: `EnrichmentService` incremental-vs-full recompute — never silently convert one into the other. W5 promotion-grade export: extend `BundleExporter`/`DataSourceBundleResolver` to decision rules + reference datasets; import-time referential integrity (a missing connection is not caught until first poll). Engine has no grouping transform (rollup honestly = `sink.materialized`). ⛔ `PipelineCompiler.toConfigMap` deliberately not migrated. → `archived-documents/plans-archive/onboarding-pipeline-unification.md` · `okf/backend/pipeline-graph/editable-round-trip.md`
- **P2** · **Parsing (Stage-1)** — ASN.1 grammar source: a reference to a stored schema module instead of pasted module text (also the prerequisite for a per-vendor transform config home); drop-in `plugins/` jar directory (JobPackManager classloader precedent) so a customer parser deploys without a rebuild. ⚠ `asn-parser/src/main/java` is NOT dead (compiled by `legacy-code/pom.xml`); corpus tests are opt-in and data-gated (DATA-GOV-1). → `okf/backend/engine/parser-plugins.md`
### Execution, Consignments, Pipeline graph


- **P2** · **Branch-aware executor residuals** — ((b) and (c) are design passes before code; (d)–(g) wait for a real need) — (b) multi-schema + route needs a **segment-scoped lift** (`writeAndTrace` runs once per segment while the divert lifts the whole graph) — ⛔ do NOT just lift the refusal; (c) mid-branch transforms in the recipe route verb — no per-branch scaffolding in `RecipeCompiler.route()` / `PipelineLift.branch()`, design pass written; (d) still unimplemented anywhere: `adapter`, `alert`, `event`; still refused at lowering as flat homes: `transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest; (e) acquisition-side "listed remotely, not yet fetched" gauge — name it first; (f) `acquire.maxFilesPerCycle` — only if overshoot is real; (g) `sinks:` follow-ups: per-sink `ducklake` block in flat `.toon`, decision-rule routing with `sinks>1`, versioned reference store with `sinks>1`, and a `ConfigSpecs`/`ConfigJsonSchema` structural spec for `sinks:`. ((a) `mode: clone` **shipped 2026-09-06** — `RouteArming` no longer refuses it.) → `okf/backend/engine/branch-aware-ingest.md` · `okf/backend/engine/output-sinks.md` · `archived-documents/plans-archive/mid-branch-transforms-design.md`
- **P2** · **Platform Services Stage 2 / 3** — (gated on the at-rest execution decision + the S2-2 spike) — Stage 2 open Step-kind registry (`StepTypeProvider` with `LOWERED`/`EXECUTED`, `StepContext`, failure mapping, watchdog) — ✅ **gate RUN 2026-09-07: holds**, no `StepTypeProvider` exists in any module: needs the decision to execute an intervening node at rest (the `graphLaneCarries` boundary = Phase 6 precondition) plus the S2-2 bridge spike (rows/s through a no-op `EXECUTED` Step vs fused) before GA; ⛔ third-party `LOWERED` stays closed until a SQL-fragment guard exists. Stage 3 pack-contributed services (`ServiceProvider` SPI; collision fails the pack atomically; reference-tracked quiesce). `DatasetAccess` after the Consignment Selector. No Job-side watchdog (R1) — a hanging Job is a recorded gap. Filtered `services()` on `ProcessorContext` (D4) and a devkit jar (D5) only on demand. → `okf/backend/control-plane/platform-services.md`

  🔴 ✅ **DECIDED 2026-09-15:** **YES — an intervening node MAY execute at rest; `EXECUTED` steps are allowed
  ANYWHERE, not only at lane boundaries.** This discharges the first of the row's two GA preconditions
  and, with it, **ELT Phase 6's `graphLaneCarries` precondition** — one answer, two rows.
  ⚠ **The accepted cost is a fusion break mid-graph, at a price nobody has measured.** The S2-2 bridge
  spike (rows/s through a no-op `EXECUTED` Step vs fused) is **no longer blocking** but is still worth
  running, because the only adjacent measurement spans a **half to a thirteenth** of the native rate
  (the Java-lane A/B, whose row closed 2026-09-15 without a measurement) — a range too wide to design against. ⛔ Unchanged by this decision:
  third-party `LOWERED` steps stay closed until a SQL-fragment guard exists.
- **P2** · **Consignment addressing** — (torn multi-file reads: CLOSED 2026-08-29 by the pinned `ConsignmentSelector` list — this row said "open" for a week; the two readers that still re-globbed, `DbBrowserRoutes.browseStore` and `ExpectationEvaluator`, were pinned 2026-09-06) 🔴 `generation` was called "a dead field (always 0, never read)" — **the never-read half is FALSE** (`DbConsignmentOutputStore.java:655` reads it; `:219` writes it), corrected 2026-09-14; ⚠ `retire_superseded` must be configured or every full recompute leaves a complete extra copy on disk; ingest-side Consignment-scoped accessor waits for a consumer; ⚠ `DatasetRelation.temporalColumn` has no caller and cannot safely gain one on a write path. → `okf/backend/engine/consignment-addressing.md`
- **P2** · **EXECUTION-RESIDUALS X4 + X1 deferrals** — X4 record-level replay from quarantine: sidecar error manifests (offset/reason), all-or-nothing vs eject-and-continue as per-pipeline CONFIG — ⛔ no build without a driver (same item as the run-detail "reprocess is whole-batch only" note). X1 deferrals: per-pipeline `processing.retry` block (regenerate node-attributes + step-types contracts); operator cancel / retry-now affordance (today: delete the sidecar under `<status_dir>/retries/`, or `reprocess`). → `okf/backend/pipeline-graph/execution-lanes.md` · `archived-documents/plans-archive/execution-residuals-plan.md`

  🔴 **X4's driver EXISTS as of 2026-09-15 — and the operator refused the question this row asks.**
  Put the per-pipeline choice (all-or-nothing vs eject-and-continue), the answer was *neither yet*:
  **scope it against a sandbox**, which is now `PIPELINE-DRYRUN-1` (§3). ⇒ ⛔ **Do not pick the replay
  default before the dry-run lands.** Both options are risky to default to, and a dry-run makes the
  choice observable instead of theoretical — which is why the config question was premature, not merely
  unanswered. ⚠ Keep the repo's recorded distinction in scope when it IS answered: *"skip the bad file"
  is not "skip the file that throws"* — a validation failure and an exception are different events, and
  conflating them is how eject-and-continue silently swallows a real fault.
- **P2** · ➕ **`PIPELINE-DRYRUN-1` — run a whole pipeline and discard every write.** **FILED
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
  ⚠ `X4` is scoped against this row and ⛔ must not pick its replay default first. → `X4` above ·
  `okf/backend/pipeline-graph/execution-lanes.md`
- **P2** · **`STREAM-CONSUMER-1` — adapter stream-consumer runtime** (filed 2026-09-10 — it was committed in `roadmap/ROADMAP.md` §3.4 and listed in `okf/capabilities/acquisition/acquisition.md` §"Open elsewhere on the board" with **no board row**, the id column pointing back at the ROADMAP paragraph). The land-then-ack seam exists (a source-side `post` that deletes the remote original runs only after the local copy is committed); the **consumer loop** that keeps an adapter draining a streaming source with at-least-once semantics does not. Not demand-gated: the ROADMAP commits to it. First action is a design pass on where the loop lives (Collector scan vs a long-running job), not code. → `okf/capabilities/acquisition/acquisition.md`
- **P2** · **Pipeline graph** — flip the intake cap on by default (needs a soak); a pre-materialise cap to save remote-fetch bandwidth (cap applies post-dedup); 🔴 **THREE** kinds still last-one-wins, deliberately out of A2 scope: `acquisition`, `gap`, `dedup.marker` — *corrected 2026-09-09: `parser` was in this list and does NOT belong; a second parser is REFUSED by name (`MULTI_PARSER`, `PipelineEditable.java:65,696`), which is the opposite of last-one-wins. `pipeline-editor.md` §Multiplicity states it correctly.*; 🔴 ~~`BatchGraphRunner` has zero production callers~~ **WRONG ON BOTH COUNTS — corrected 2026-09-09.** (a) **There is no class of that name** — it was renamed in the 2026-08-31 Consignment commit. (b) The class that DOES exist, `ConsignmentGraphRunner`, has **production callers**: `engages()` drives the live lane admission (`ConsignmentIngestStrategy.admittedLift`) and **`run(...)` executes on the ingest path** (`ConsignmentIngestStrategy:355`). ⛔ This row was cited as Row 15's parity blocker, so re-derive that gate before using it. ~~What IS still owed is §6 step 2, the parity gate through the compiled-recipe path.~~ 🔴 **The parity gate was RUN 2026-09-16 and is NOT MET** — the root pom now passes `-Dingest.lane` to surefire (`mvn -Dingest.lane=graph test` = the whole suite with the flat lane disabled); 13 `inspecto-engine` tests refuse, for exactly two reasons the lane itself names: a **sink-count mismatch** (the multi-schema/plugin-ingester fixtures `events_etl`, `typed_record_etl` lift to 3 sinks against 1 declared) and **a Decision Rule routed rows** (`test_etl`), which the graph lane does not implement. Those two are the remaining work before Row 15's deletion half can start; re-run the one flag after each. ✅ **Each got its own row 2026-09-16, and the multi-schema one SHIPPED the same day** — 12 of the 13 are green and `-Dingest.lane=auto` now diverts multi-schema segment writes to the graph lane; `GRAPH-LANE-RULE-ROUTED-1` is the only one left. ⚠ Its stated cause (an arity mismatch) was NOT the whole gap — read the row before re-deriving it. **~~Pre-materialise cap: design-first~~** ✅ **SHIPPED 2026-09-16** — the §1 input was answered (operator: cap on **BYTES**, remainder **DEFERRED** to the next cycle) and built at the one choke point where candidate sizes are known: `-Dingest.maxBytesPerCycle` (off by default, so no existing install starts throttling) on `IntakeGovernor.Policy`, enforced oldest-first in `CollectorProcessor.admitBytes`. ⛔ **The starvation guard is part of the rule, not a follow-up**: a file LARGER than the cap fits in no cycle ever, so when nothing fits the oldest file is admitted **ALONE** with the overrun logged — otherwise it sits in the inbox permanently while smaller files overtake it, a silent stall that looks like a working cap. Mutation-proved (removing the guard strands it). 🔴 **Two defects found and fixed while building, either of which would have shipped silently:** the per-pipeline `processing.intake` override would have switched the GLOBAL byte cap OFF for any pipeline declaring an intake block (the legacy 3-arg `Policy` constructor defaults it to unbounded — it now inherits explicitly), and a negative/malformed property clamps to *off* rather than to *admit nothing*. ⚠ **Limitation, stated rather than papered over:** the cap runs after listing, on local files, so for a collector that fetches remotely BEFORE that point the saving is on materialisation, not on the fetch itself; moving it earlier needs a listing-with-sizes seam that does not exist. Pinned by `CollectorProcessorByteCapTest` + `IntakeByteCapTest`. As-built: `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.4 · §2.3; → `okf/backend/pipeline-graph/pipeline-graph-design.md` §8

  ✅ **DECIDED 2026-09-15:** **flip the intake cap on by default ONLY after a soak** — the soak stays a real
  precondition and is not waived. ⚠ **But the DOC half is not waiting for it**: `EDITIONS.md:164`
  advertises `JOB-04` as ✅✅✅ with an empty notes cell over a feature that is off by default
  (`IntakeGovernor.java:64-68`, `UNBOUNDED = 0`), and that cell is being corrected now. ⇒ the claim and
  the default are being fixed on **different clocks, deliberately**. ✅ The cell was corrected 2026-09-15
  (`EDITIONS.md` `JOB-04`) and `SPEC-GREENCELL-1` retired with it; ⛔ **the soak and the default flip are
  THIS row's and remain open** — the doc fix does not discharge them.
- ~~**P2** · **`GRAPH-LANE-MULTISCHEMA-1` — the graph lane cannot carry a multi-schema write**~~ ✅ **SHIPPED 2026-09-16** (filed, designed, decided and built the same day; `auto` flipped per the operator's call — multi-schema segment writes now divert to the graph lane by DEFAULT). 12 of the 13 parity-gate refusals are green; the fix was `ConsignmentIngestStrategy` alone (`segmentWrite` / `writeSinks` / `seedOfWrite` + three `segKey` overloads), pinned by `GraphLaneSegmentAdmissionTest`. As-built + the fixture gotcha (`segments:` is only parsed when `processing.ingester:` is set) in `archived-documents/plans-archive/graph-lane-multischema-design.md` §6. Original row text follows.
  - **P2** · **`GRAPH-LANE-MULTISCHEMA-1` — the graph lane cannot carry a multi-schema write** (filed 2026-09-16 from the parity-gate run; ⛔ **design-first**). 12 of the 13 refusals the §6 step-2 gate produced are this one gap: `events_etl` and `typed_record_etl` refuse with *the lifted graph's sink count (3) differs from sinks[] (1)*. **Grounded by re-running the gate, not read off the previous shift's note** — `mvn -o -pl inspecto-engine -am -Dingest.lane=graph -Dtest=… -Dsurefire.failIfNoSpecifiedTests=false test` ⇒ 20 run / 10 failures / 3 errors in `TypedRecordIngesterTest`, `ConsignmentIngestorPluginTest`, `ConsignmentIngestorPluginDeepTest`. ⚠ **The refusal message names an arity, but the gap is structural and bigger than the count.** A multi-schema config lifts to one `map → sink` chain PER SCHEMA (`PipelineLift.java:22-24`), so three sink NODES stand against one declared DESTINATION — and `graphLaneCarries` (`ConsignmentIngestStrategy.java:407-424`) then requires **every** sink to hang directly off **one** seed (`seedFeedingTheWrite:433`), which a per-schema lift cannot satisfy by construction: each branch has its own map. ⛔ So do **not** "fix the count" — widening line 417/209 to accept N sinks would admit a write whose seeding contract is still violated. The design question is whether the graph lane seeds PER BRANCH, and 🔴 **it is NOT established that the executor can already run per-schema trees once seeded** — `ConsignmentGraphRunner.hasRouteFedChain` (`:154-167`) deliberately EXCLUDES a multi-schema parser's `route:<key>` dispatch edges, calling them *"the flat lane's own per-schema trees"*, so per-schema execution on this lane is unproven either way. ✅ **ESTABLISHED 2026-09-16 by spike — and it refutes BOTH readings above, including my own ‘structural seeding’ one written the same day.** (1) The executor is **not** the gap: `PipelineExecutor.execute` already takes a MAP of seeds (multi-source shipped for the job lane, T32 Phase C), and seeded `{map_CALL, map_SMS}` it walked both trees and committed both branches (`committedBranches=[sink_CALL, sink_SMS]`, quarantine correctly skipped as control-fed). (2) 🔴 **And no multi-seed is needed at all: `UnionModeIngester:122-168` already loops PER SEGMENT**, materialising `transformed_<KEY>` and calling `writeAndTrace(… dbDir=database/<segKey>, writeScope=segKey)` once per segment — so every call is already ONE seed to ONE sink. The defect is only that the admission lifts the WHOLE pipeline on each such call and compares 3 sink nodes against 1 destination: **it asks at pipeline granularity while the caller works at segment granularity.** ⇒ the row is **no longer design-first and is much smaller than filed** — thread the segment key into the admission and admit the `map_<segKey> → sink_<segKey>` sub-chain. ⚠ `writeScope` is NOT usable as that key unguarded: it is `""` whole-batch (`CsvIngestStrategy:182`), the chunk base name when chunked (`NativeCsvStreamingEngine:279`), the segment key only in `UnionModeIngester:157` — discriminate on `cfg.schemas().segments().keySet()`. ⛔ **ONE operator call is owed first (§1): does `auto` start diverting multi-schema writes to the graph lane, or does only `-Dingest.lane=graph` carry them?** The gate goes green either way; the first flips the live write path for every `segments:` pipeline, the second creates a path only the gate exercises. Design: `archived-documents/plans-archive/graph-lane-multischema-design.md`. Files: `ConsignmentIngestStrategy.java` (`admittedLift`, `graphLaneCarries`, `seedFeedingTheWrite`, `flatReason`). **Verify:** `ConsignmentIngestorPluginTest`, `ConsignmentIngestorPluginDeepTest`, `TypedRecordIngesterTest` green under `-Dingest.lane=graph` (12 of the 13; `DecisionRuleWiringTest` waits on the sibling row). Blocks §2 Row 15's deletion half. → `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.4 · `okf/backend/pipeline-graph/pipeline-graph-design.md` §8
- ~~**P3** · **`MIGRATE-ENRICH-1`**~~ ✅ **ANSWERED + CLOSED 2026-09-16 (operator): the 2026-08-06 reversal cancelled the FILE-FORMAT migration too, not only the vocabulary — `*_enrich.toon` stays a Job.** The amendment §6 step-1 clause is **STRUCK**; `GLOSSARY.md` §Enrichment corrected (it was carrying the pre-reversal position against §Job and the §13 row in the same file); `ConfigMigrator` now passes enrich configs over in silence and leaves them where `EnrichJob` expects them — **not** archived. ⇒ a space owning an enrich config migrates again, so **§6 step 1 is dischargeable**. Original row follows.
  - **P3** · **`MIGRATE-ENRICH-1` — `*_enrich.toon` has no conversion target, and the two the docs name CONTRADICT each other** (filed 2026-09-16 while building the §6 step-1 converter). `inspecto migrate-configs` REFUSES an enrichment config, and because a refusal fails the whole migration, **the demo space cannot be migrated today** (driven live: 9 conversions, 1 refusal, per demo space). 🔴 **The blocker is not missing code — it is that the target was REVERSED and `GLOSSARY.md` still carries both positions.** §*Enrichment* (`GLOSSARY.md:398`) says the file kind *"becomes a **table-entry Pipeline** (amendment Phases 3/6)"* and that existing enrich files *"keep running until migration"*; §*Job* (`:456`) and the §13 row (`:908`) say the Job retirement was **REVERSED by operator decision 2026-08-06** precisely because its replacement path *"hung on the amendment's Phase 3 S3 (table-entry `collect`), **deferred by its own design spike as genuine new design**"*, and that table-entry Pipelines are *"an **additive complement**, never the Job's replacement"*. ⇒ the conversion the amendment's §6 step 1 asks for is **blocked on a slice that was deliberately deferred**, and a periodic enrich is a **Job** under the reversal. ⛔ Do not build a table-entry conversion on the §6 wording alone; ⛔ do not "fix" the GLOSSARY by picking a side — the vocabulary is binding and this is an operator call, filed in §1. Two outcomes are possible and they differ in kind: **(a)** `*_enrich.toon` stays a Job ⇒ strike the clause from §6 step 1, and the converter should SKIP enrich files as out of scope (the demo space then migrates); **(b)** it really does become a table-entry Pipeline ⇒ this row waits on S3's design and the converter's refusal is correct as it stands. Files when (a): `ConfigMigrator.java` (the refusal becomes a skip) + `GLOSSARY.md` §Enrichment. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §6 step 1 · `okf/backend/control-plane/jobs.md`
- ~~**P3** · **`MIGRATE-MATERIALIZE-1`**~~ ✅ **ANSWERED + CLOSED 2026-09-16 (operator), same call: the clause is STRUCK and `materialize` stays a Job task.** The row's grounding held — a Dataset-registering task is not a `transform.summarize` node, and there was no config file for the converter to walk. Original row follows.
  - **P3** · **`MIGRATE-MATERIALIZE-1` — "every `materialize` task → a `summarize` recipe" is not a conversion the code can express** (filed 2026-09-16, same build). §6 step 1's fourth clause has **no file for the converter to walk**: `materialize` is a *maintenance JOB task* (`MaintenanceJob.java:220` → `MaterializeTask`), declared in a Job, not in a config file a migration of `*_pipeline.toon` / `*_schema.toon` / `*_enrich.toon` ever sees. 🔴 **And the two are not the same operation, so the clause is wrong on grounding as well as on reach:** `MaterializeTask` compiles a **measure spec** (`measures`/`group_by`, BI-7 `MeasureCompiler`) over a source **Dataset**'s trusted relation, `COPY`s Parquet under the data root and **registers/refreshes a `dataset` component**; `transform.summarize` (`BuiltinNodeType.java:156`) is a TRANSFORM node over a DATA relation inside a Pipeline, declaring its own output columns and registering nothing. A recipe cannot carry "and then a Dataset exists". ⚠ The same 2026-08-06 reversal applies: dataset operations are explicitly named as Job work. ⇒ the honest options are **strike the clause** (materialize stays a Job task — the likely answer) or **design a Dataset-registering sink**, which is new design, not a migration. ⛔ Not startable as written. → `MaterializeTask.java` · `okf/backend/control-plane/jobs.md` · the §1 call below
- ~~**P2** · **`GRAPH-LANE-RULE-ROUTED-1` — the graph lane does not implement rule-routed outputs**~~ ✅ **SHIPPED 2026-09-16**, and 🔴 **its stated cause was wrong — the third refutation this gap has produced.** It was filed as *representational* (“`PipelineLift` never sees a Decision Rule, so routed destinations cannot appear in a lifted graph”). True, and irrelevant: `DecisionRuleApplier` runs ABOVE the fork, writes the routed rows itself and then **`DELETE`s them from the relation** (`DecisionRuleApplier.java:233-237`), so both lanes see the identical remainder and **no graph node was ever needed** — the routed outputs only had to be MERGED into the graph lane's `Written`, which is exactly what the flat path does by seeding its list with `applied.outputs()`. Quarantine and drop rules, which also remove rows but produce no outputs, had been running on this lane all along for the same reason — the precedent was already in the code. What stays refused is the pair the flat path also refuses: routing combined with route branches, or with a multi-destination `sinks[]`. ✅ **⇒ THE §6 STEP-2 PARITY GATE IS MET, for the first time ever**: the whole suite under `-Dingest.lane=graph` is **4603 / 0 / 0 / 28, BUILD SUCCESS, zero refusals**. Pinned by two tests in `IngestLaneFlagTest`. Original row text follows.
  - **P2** · **`GRAPH-LANE-RULE-ROUTED-1` — the graph lane does not implement rule-routed outputs** (filed 2026-09-16 from the same run; ⛔ **design-first**). The 13th refusal: `test_etl` fails with *a Decision Rule routed rows, which the graph lane does not implement* (`DecisionRuleWiringTest.routeMovesMatchingRowsToDestinationSubdirWithOutputsAndLineage`, 6 run / 1 error). The refusal is stated at `ConsignmentIngestStrategy.java:204` and enforced in the admission at `:181` — a non-route pipeline diverts only when `applied.outputs().isEmpty()`. 🔴 **The blocker is representational, not a missing executor step, and that is why it is design-first: `PipelineLift` contains ZERO occurrences of `DecisionRule`** — Decision Rules are a **space-registry** fact resolved at run time (`DecisionRuleApplier.java:156`, `DecisionRules.forTarget`), not a config property, so there is nothing in the config for the lift to turn into a node and the routed destinations cannot appear in the lifted graph at all. ⚠ `transform.route` exists only for an **authored** route. So the decision owed first is **where a rule-routed destination becomes a graph node** — lifted late from `DecisionRuleApplier.Result.outputs()` (`:79`) after the rules run, or left off the graph with the routed write kept above the fork as today. ⛔ Do not start by widening the admission: admitting a write the graph cannot represent loses the routed rows silently, which is the one failure mode the whole lane fork exists to prevent. ⚠ Its dedupe lines are the same ones the `(type, key, column)` decision and `/recon/promote` rewrite — check §1 before touching them. Files: `ConsignmentIngestStrategy.java`, `PipelineLift.java`, `query/DecisionRuleApplier.java`. **Verify:** `DecisionRuleWiringTest` green under `-Dingest.lane=graph`. Blocks §2 Row 15's deletion half. → `okf/capabilities/pipeline-execution/pipeline-execution.md` §3.4
- **P2** · **Consignment ELT** — (three items added 2026-09-07 from the archived plan's §11.2/§11.7/§15, which BACKLOG never carried: **`batches` is structurally singular** — its `schema_name`/`output_table` are one-per-row while a Consignment's EL emits a row set *per schema*, so this needs either one row per `(consignment, schema)` or a child table, an open decision; ~~whether a **durable `DeliveryReceiptStore`** exists beyond the in-memory one is a one-grep check still owed~~ — ✅ **ANSWERED 2026-09-14: it exists** (`inspecto-engine/.../notify/DbDeliveryReceiptStore.java`, wired through `ServiceStores`/`OperationalDb`); and §8.4's SLA config object is dropped with sealing, not pending.) `generation` is on the registry but compaction does not stage generations — ⚠ **and it is never incremented at all** (`ConsignmentOutputs.java:336` writes a literal `0`), so "dead field" was closer to true than the 2026-09-14 correction allowed; ~~`run_id` is `null` everywhere~~ ~~✅ CLOSED 2026-09-13 — `run_id` carries a real attempt on every production path~~ 🔴 **BOTH of this row's key claims are REFUTED, regrounded 2026-09-15, and are now `CONSIGNMENT-OUTPUTS-NULLRUN-1` (§4, P1): the key was not "still not addable" — it was ADDED on 2026-09-13 (`DbConsignmentOutputStore.java:122`) together with the `ON CONFLICT DO UPDATE` this row calls unbuilt (`:225`); and `run_id` is NOT supplied on every production path — `ConsignmentGraphRunner.java:83` still threads null, so those rows escape the key.** ⛔ Do not re-file either claim from this row; §7.4 rollup cache deliberately unbuilt until read-time aggregation is measurably slow; §7.3 unpartitioned fallback stands by operator call — revisit if flat summary targets appear. → `okf/backend/engine/db-layer.md` §3.9

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
- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** an author needs to declare "this Collector takes NO data
  extensions". · **`SCHEMA-FORM-EMPTY-LIST-1` — the UI cannot author an explicit empty list** (filed 2026-09-13,
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
  `Investigation`. The code's own package is already `…intelligence.investigation`, so the rename moves toward the
  code's naming rather than away from it. ⚠ **Four published routes are in scope** (`/agent/cases*`), so this needs an
  alias or a deprecation window, not a silent flip — that is why 7.3 filed it instead of applying it. Touchpoint list in
  `GLOSSARY.md` §13. ⛔ Do **not** also rename `mode: case` (route branching) or `caseType` (line of business): different
  words that merely look alike, and `caseType` feeds RBAC data scopes.
- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** the first operator who asks for a spreadsheet download — which is
  also when the new-dependency question gets answered, rather than in advance. · **D-8 XLSX export** — zero groundwork (no spreadsheet library in any pom); gated only by a bare label — state the operator question before answering it. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §9 D-8

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** An operator wants a spreadsheet download.
  ⛔ **The new-dependency question is NOT answered by the trigger** — that was the point of gating it
  this way. There is **no spreadsheet library in any pom**, and picking one is an operator sign-off
  filed as an owed input in §1. ⇒ this row cannot start until that name exists.
- **P3** · **D-11 hand-authored `relations` component** — deferred until a business relation exists that no Pipeline exercises. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §3.4

  ⬜ **RE-CONFIRMED NOT FIRED 2026-09-15** — every business relation in play is already expressed by
  a Pipeline, so the row stays correctly deferred. ⚠ Asked and answered, not overlooked.

### Control plane, jobs, notifications, queries

- **P2** · **API v1** — adopt the cursor-pagination seam on further list families as demanded (4 adopters live); adopt `ETags.respond` on further singleton reads as demanded; ~~Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed)~~ ✅ RE-VERIFIED 2026-09-15 — Standard package with embedded runtime boots and answers `/health` (as-built `okf/capabilities/editions/editions.md` §3.3). The remaining adopters are demand-gated. → `okf/backend/control-plane/api-v1.md`
- **P2** · **Bundle / Exchange** — `requires` present-but-different classification; per-editor "load as draft" import — design first, likely multi-session (`BundleTransferService.write` commits straight through; no generic draft seam). ⛔ Do not fake it with a cross-kind `enabled:false` stamp. → `okf/backend/control-plane/exchange-sharing.md`
- **P2** · **Notifications** — D8 residuals: soft-bounce retry scheduling (distinction recorded, nothing retries); SES/SNS adapter (needs SNS subscription confirmation + a cert-chain fetch from a validated `amazonaws.com` URL — ⚠ outbound fetch from an unauthenticated callback path deserves its own review); GeoIP; auth-gated per-user prefs / security triggers. (Auto-disable policy is a §1 decision.) → `okf/backend/control-plane/events-metrics.md`

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
- **P3** · **Queries / BI** — `graph`/`spatial`/`search`/`api` QueryTypes; more `$`-resolvers. (DuckDB `spatial` extension itself: zero demand re-verified 2026-08-26 — do not re-open on speculation.) → `okf/backend/control-plane/queries.md`
- **P3** · **EXPORT-1 outbound object-storage export (S3 / HDFS)** — sequence of record: operator `aws s3 sync`/rclone of `data/<store>/database/` first (zero code); build the push post-action (outbound mirror of the connector SPI reusing `AwsSigV4`) only on demand; HDFS only via an S3-compatible gateway — ⛔ never `hadoop-client`. → `okf/backend/engine/object-storage-export.md`
- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** the first install where hand-edited policy TOON goes wrong. ⚠ The
  read-only Policies tab and the "why denied?" endpoint already make such a mistake diagnosable, which is what
  bounds the cost of waiting. · **Security: policy-authoring UX** — a matrix/create editor beyond hand-authored TOON (seed visibility, "why denied?" endpoint and read-only Policies tab already shipped). Non-blocking. → `okf/backend/editions/auth-security.md`

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** An install has had hand-edited policy TOON go wrong.
  ⚠ The read-only Policies tab and the "why denied?" endpoint already shipped, so the mistake is
  **diagnosable** today — which means the build is about preventing the error, not explaining it, and
  the existing diagnosis surfaces are the thing to extend rather than duplicate.
- **P2** · ✅ **TRIGGER (operator, 2026-09-13):** the first operator who promotes two Breaks on one key and
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

### Deployment & packaging

- **P2** · **D8-SUPPRESS-1** — per-recipient suppression list (TTL for hard bounces, permanent for complaints). ✅ **Its gate — a DB-backed `DeliveryReceiptStore` — was DISCHARGED 2026-09-07** (the same day it was verified still holding): `DbDeliveryReceiptStore` shipped in `inspecto-engine/.../notify/`, wired `SpaceRoot.deliveryReceiptsDbUrl` → `OperationalDb.Family.DELIVERY_RECEIPTS` → `ServiceStores.openDeliveryReceiptStore` → `CollectorService`, behind `-Ddelivery.receipts.backend`. ⛔ Default `none` — an absent receipt DB is the shipped behaviour, not degraded correctness, and a default-ON family creates a DB file in the CWD for every Personal install. Schema + rationale: `okf/backend/engine/db-layer.md` §3.12. ✅ **The suppression policy SHIPPED the same day** — `SuppressionList` (complaint ⇒ permanent · hard bounce ⇒ `-Dnotify.suppression.bounce.ttl`, default `P30D` · ⛔ soft bounce never · off via `-Dnotify.suppression=off`), consulted in `NotificationService`'s ChannelConfig delivery loop. 🔴 It **arms only over a durable store** (`DeliveryReceiptStore.durable()`) and WARNs when a TTL is set over one that cannot honour it — suppressing nothing while appearing configured is the `ConservationCheck` trap. ✅ **`GET/DELETE /notifications/suppressions` SHIPPED too** — the 2026-09-06 decision is fully discharged. `DELETE` records an **override** (operator call 2026-09-07) that forgives history up to its timestamp; a later bounce re-suppresses on its own, and the receipts survive as the audit trail. ⛔ Rejected: pruning the target's receipts — audit loss AND a permanent mask over a dead address. ✅ **SOFT-BOUNCE RETRY SHIPPED 2026-09-15** — the `soft_bounce_retry` maintenance task (`max_attempts` default 3, `backoff_minutes` default 60), re-delivering through `NotificationService.retrySoftBounce`. ⚠ **The first maintenance task that SENDS** rather than prunes or reads, so `JobService` gained a `notificationService()` seam beside its store seams. 🔴 **Two traps the decision did not name.** (a) `DeliveryReceipt.withStatus` keeps the FIRST observation of each status, so `statusAt[BOUNCED_SOFT]` never advances — a backoff measured from it would measure from a fixed point in the past and fire every remaining attempt in one sweep, a retry storm shaped like a backoff. The receipt therefore carries its own `lastAttemptAt` clock (+ `attemptCount`), two additive columns via the `ADD COLUMN IF NOT EXISTS` idiom. (b) Selecting on a bare `containsKey(BOUNCED_SOFT)` re-sends to recipients who ALREADY received the message, because a receipt that soft-bounced then delivered keeps both stamps forever — hence `softBouncedAndUnresolved()`, where DELIVERED/BOUNCED_HARD/COMPLAINED settle it and ⛔ UNKNOWN deliberately does not. ⚠ An attempt is counted whether or not it succeeded, or a permanently unreachable transport spins forever. **What remains on D8: the SES/SNS adapter** (the latter needs subscription confirmation + an outbound cert fetch from a callback path — its own review). Covers EDITIONS `CP-15` (Standard+). → `okf/backend/control-plane/events-metrics.md` §Decision

  ✅ **DECIDED 2026-09-15:** **of the two named residuals, soft-bounce retry is IN and the SES/SNS adapter is OUT**
  (kept filed, with its own review). See the Notifications row for the reasoning; recorded here too
  because this row states the residual pair and would otherwise read as if both were queued.

- **P2** · **AGT-5 per-tool dry-run seam — 🔴 RE-GATED 2026-09-16: BLOCKED-EXTERNAL again, on a DIFFERENT fact.** The 2026-09-08 discharge verified the TYPE ships; it never checked the SEAM. `javap` on the pinned `eoiagent-platform` jar: `PlatformBuilder` has `approvalHandler(...)`, `approvalDecisionStore(...)` and **no `dryRunProvider(...)`** — the only setter lives on `CallbackApprovalGate.Builder`, which `PlatformBuilder` constructs internally, so inspecto cannot supply a per-tool `DryRunProvider` without an upstream change. **Upstream ask (to `jotder/inspect-agent`): expose `PlatformBuilder.dryRunProvider(DryRunProvider)` and thread it to the gate builder.** Nothing built here; `AgentApprovals` stays as the previewer. ⛔ Do not re-discharge on the presence of the type — check the builder. *(Original text, kept for the trail:)* This sat in §2 as externally gated on eoiagent shipping a per-tool `DryRunProvider`. **It has shipped**: `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunProvider.java`, `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunResult.java`, and four per-tool dry-run tools, under an **Accepted** ADR-0008 enforcing approval + dry-run in the runtime (upstream `jotder/inspect-agent`, verified via the git-tree API 2026-09-08). ⚠ The gate was not "waiting" — it was **held shut by a broken check**: the `gh search code` probe it named returns 0 for every term in that repo, control included. **What this unblocks:** inspecto can now drop its parallel `AgentApprovals` previewer and consume the upstream per-tool seam on `PlatformBuilder`. ⛔ Still separately gated: `incident_explain` waits on the eoiagent **host** seam, and the local-models-only scope cut stands. → `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2
- **P2** · **Deployment topology gaps** — ~~GAP-3 service wrappers (SCR-3)~~ → own P1 row `DEPLOY-SERVICE-WRAPPER-1` (2026-09-11) · GAP-4 DuckDB `memory_limit` default · GAP-5 T15 surge admission · GAP-6 Vault/KMS (SEC-8) · GAP-10 bundle missing 13 archived docs (SCR-10). Phases 0–5 all unbuilt. ✅ **This row is ALSO the board home of the thirteen plan items `SPEC-DEPLOY-ROWS-1` counted (closed 2026-09-15 by naming them here rather than filing thirteen rows that would duplicate the spec):** the preflight tool · the acceptance script · the off-site backup copy · upgrade/rollback automation · the sizing table · the disaster-recovery pack · phases 0–5 (six) · the platform list · the government-variant refusal · **`SCR-4`** (nginx/IIS proxy + TLS reference configs — TLS, HSTS, static-UI gzip, `/metrics` + `/health/details` restricted to the monitoring network; ⚠ still the one item whose only statement anywhere else is its acceptance line). Their durable specification is `okf/capabilities/editions/editions.md` §3.9–§3.14; this row tracks the BUILD. *(Re-grounded 2026-09-08. The "(after §1 D1–D8 are signed)" gate is dropped — §1 records all 28 decided 2026-09-06, which §7 already flagged. **GAP-2 and GAP-8 were shipped work this row had inherited as open** and are struck: Enterprise is a real `package.ps1` flavour (EDG-01) and the Postgres driver rides the bundle as `postgresql.jar` (PG-1). ⚠ **GAP-4 verified STILL OPEN** — D11 shipped as a pair and only the concurrency half is on by default; `DuckDbUtil.memoryLimit(null)` is `null`, no `scheduler.toon` ships, and the committed corpus sets `memory_limit: ""`. Do not close it off the D11 row.)* → `archived-documents/plans-archive/deployment-topology-plan.md` §11
- **P2** · **Postgres multi-user** — ⛔ **PARKED by §6** until a multi-operator install exists; the old "(after the §1 decision)" heading outlived its decision, which was *park it*. Kept for the shape when it lifts: P1 pool behind `JdbcDrivers` (each `Db*Store` holds ONE `synchronized` connection); P2 replace `browseConnection()` (F2: it hands out the store's long-lived connection, a pool has no such thing); P3 **schema**-per-space URL wiring (NOT db-per-space); P4 `CaseStore` interface + PG impl (JSONL ring today); `PostgresStateStoreTest` over the three uncovered stores + a concurrency test. Keep events on Parquet. ⚠ Not the same work as `OperationalDb`/PG-1 (shipped). → `archived-documents/plans-archive/postgres-multi-user-plan.md` §5–6

  🔴 **TRIGGER FIRED 2026-09-15 — no longer demand-gated, re-ranked P2.** A multi-operator install exists. 🔴 **The §6 PARK IS LIFTED** — that line is
  struck in §6 and the §7 duplicate-map row updated, so all three places agree.
  ⚠ **Take the shape as written and do not improvise it**: P1 a pool behind `JdbcDrivers` (each
  `Db*Store` holds ONE `synchronized` connection today); P2 replace `browseConnection()` — ⛔ it hands
  out the store's long-lived connection and **a pool has no such thing**, so this is not a
  find-and-replace; P3 **schema**-per-space URL wiring, ⛔ NOT db-per-space; P4 a `CaseStore`
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

- **P2** · **`SPACES-FROM-PARTITION-MAP-1` — answer `/spaces` from the partition map, not a disk scan.**
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


### Filed from the 17-spec consolidation, 2026-09-09 (Sprint 2)

These four are the **classes** behind roughly half of the consolidation's findings. Each is one guard, not N
fixes — that is the point, and it is Sprint 3 of `archived-documents/plans-archive/post-consolidation-sprints.md`.

- **P2** · **`SPEC-DEADSEAM-1` — four declared seams with no implementation or no caller.**
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
  `asn-plugin-vendors` in its test coverage. Deleting it breaks `RTDMS_ASN_Test` and the asn-golden parity
  run. ⇒ It is not operator-side and not dead — it is **in-repo load-bearing**.
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

  ⛔ **BLOCKED on the operator (2026-09-15 §4 pass) — the three questions are filed in §1 as one owed
  decision.** Nothing here is buildable until they are answered: two of the three "deletions" would have
  removed live code, so the standing DELETE verdict is void and no default is safe to assume.
## 5. Docs & hygiene

- **P1** · 🔴 **`ROUTE-UNGATED-DEFAULT-1` — an unlisted route is OPEN, not locked down.**
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
    `AccessDeciders.active()`, which resolves EMPTY on Personal **and Standard** (`AccessDeciders.java:23-30`
    — neither ships a `META-INF/services` registration), so on Standard that stage returns immediately.
  - 🔴 **Verified example, opened and read rather than inferred:** `api.delete("/spaces/([^/]+)", …)`
    (`SpaceRoutes.java:72`) has **no capability gate** — `deleteSpace` (`:125-139`) checks only
    `requireMultiSpace`, id validity, and a last-space-purge 409. ⇒ on Standard **any authenticated caller
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

- **P2** · **`DUCKLE-C3-DEAD-PROPERTY-1` — a config key no component reads must FAIL validation.** Adopted
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

- **P3** · **`ACQUIRE-LEDGER-DUPLICATE-RESOLUTION-1` — one setting, two sources of truth** (filed
  2026-09-16 from the Completeness KPI K4 design pass). `AcquisitionLedgers.build(...)` (`:152`) resolves
  `acquire.ledger.backend` through its **own** `System.getProperty`, duplicating the `OperationalDb.Family`
  declaration at `OperationalDb.java:147` (`ACQUISITION_LEDGER(…, "acquire.ledger.backend", "memory",
  Mode.DB_FLAG, …)`) and bypassing `OperationalDb.resolve()`. 🔴 **This also corrects a correction**: this
  page previously asserted the acquisition ledger is *"not an `OperationalDb.Family` member at all"* — it
  **is**; the real defect was always the duplicated resolution, not an absent declaration. ⚠ The practical
  consequence is the one K4 tripped over: `AcquisitionLedgers.shared()` **never returns null** (it falls
  back to `InMemoryAcquisitionLedger`), so a caller cannot tell memory from durable by nullness and must ask
  `StoreHealth.of(spaceId)` — the one place the resolved backend is still known.

  🔴 **REFUTED 2026-09-16 — the remedy is IMPOSSIBLE and the defect framing is wrong. This is the
  THIRD successive framing of this row to fail grounding** (first *“not a `Family` member at all”*, then
  *“duplicated resolution bypassing `resolve()`”*). The narrow fact is true — `AcquisitionLedgers.java:152`
  and `OperationalDb.java:147` both name `acquire.ledger.backend`. Everything built on top of it is not:
  ① **Routing the leaf through `OperationalDb` is a MODULE CYCLE.** `inspecto-acquire` is a deliberate
  leaf (its pom says so); `OperationalDb` lives in `inspecto/`, whose `pom.xml:96` depends **on
  `inspecto-acquire`** — `OperationalDb.java:3` even imports `com.gamma.acquire.SecretResolver`.
  ② **There is no public API to route through**: `resolve()` is `public` but returns
  `record Resolved` (`OperationalDb.java:209`), which is **package-private** ⇒ effectively
  `com.gamma.service`-only.
  ③ **“Bypassing `OperationalDb.resolve()`” is not a defect signature — it describes ALL ELEVEN
  families.** `resolve()`/`resolveAll()` have exactly ONE caller in the repo, the diagnostic
  `OperationalDbReport.java:47`; **no store opener calls it**, and `ServiceStores.java:62` says outright
  *“Mirrors OperationalDb.resolve”*. `resolve()` is a MIRROR of the openers, not their implementation, so
  the acquisition ledger is the eleventh instance of the house idiom, not an outlier.
  ④ **The URL half is already centralised**: `SpaceBootstrap.java:37-39` resolves it via
  `OperationalDb.urlFor(Family.ACQUISITION_LEDGER, …)` like every other family ⇒ the ledger already
  participates in the roster at the production seam.
  ⚠ Also not value-identical: `Resolved` discards the raw backend string, which `:154-156` needs verbatim
  for its `StoreHealth` message, so even inside `com.gamma.service` the substitution would change an
  invalid-value message.
  ⇒ **What a real row would say:** one source of truth for `*.backend` is a **systemic 11-site** change
  needing a public, `SpaceRoot`-free accessor (e.g. `backendOf(Family)` returning the raw trimmed string)
  in a module **below** `inspecto-acquire`, since `Family` cannot today be referenced from a leaf.
  ⛔ That is an architecture decision, not a de-duplication, and nothing on this board authorises it.
  ⇒ **Disposition: this row is CLOSED as refuted.** Re-file only as the systemic question above.
  ⚠ The same text in `superpower/completeness-kpi-k4-design.md` §R3 carries the refuted framing too.

- **P3** · **`ACQUIRE-LEDGER-SHARED-URL-1` — a second source of truth for the ledger URL** (filed
  2026-09-16 from the row above, and genuinely distinct from it: it is the **URL**, not the backend).
  `AcquisitionLedgers.shared()` (`:39-41`) lazily builds from
  `System.getProperty("acquire.ledger.db.url", DEFAULT_DB_URL)`, **bypassing `OperationalDb.urlFor`** — so
  a space that was never run through `SpaceBootstrap` resolves a DIFFERENT url: no `-Dinspecto.db.url`
  shared fallback, and a working-directory-relative `jdbc:duckdb:inspecto-acquisition.db`.
  ⚠ Unlike its parent row this one does **not** require crossing the module boundary — `urlFor` is already
  called from `SpaceBootstrap`, so the question is which seam `shared()` should use, not where the
  declaration lives. ⛔ Ground it before starting: the parent row was wrong three times.

- **P2** · **`DUCKLE-C1-DATASET-FRESHNESS-1` — Dataset freshness on a CLOCK, not on failures.** Adopted
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
  → Lineage · `docs/api` breaking-change record · CI

- ~~**P3** · **`JOB-DIR-CWD-CONTAINMENT-1`**~~ ✅ **SHIPPED 2026-09-16.** The semantics call was answered (operator: a job's relative path resolves against the **Space config root**, never the process working directory) and built on BOTH sides in one change, as the 2026-09-15 re-grounding said it had to be: `PathJail.resolveJobPath` is the single rule, called by the 422 gate (`ConfigSafetyValidator.checkJob`, which now USES `configDir` instead of ignoring it) and by all four run-time jail sites (`CleanupTask:35,46`, `PartitionPruneTask:39`, `StorageReportTask:45`). ⛔ **The ambiguous case REFUSES** — a value that does not exist under the Space root but DOES where the old rule would have put it throws naming BOTH paths, so a change in what an existing job MEANS is fixed deliberately rather than silently read elsewhere. A null Space root keeps legacy behaviour. 🔴 **Consequence worth keeping: relative `..` traversal is no longer an escape by itself**, because it climbs from a deeper base — containment is still enforced, the jail just judges a different resolved path. `ControlApiJobCrudTest`'s probe had to change with it: `../../outside` resolved from the Space root lands back INSIDE the allowed roots, so asserting 422 on it would have asserted a falsehood — and it was the layout-dependent probe that created this row (escaped from `%TEMP%`, did not from `C:/sandbox`). It now uses an absolute path off the filesystem root, which escapes under every layout. Pinned by `JobPathResolutionTest` (6 tests). Original row follows.
  🔴 **TWO RESIDUALS FOUND 2026-09-16 by grounding this row AFTER it shipped — the “all four run-time
  jail sites” claim above is WRONG, and both the commit message and this row carry it.** Filed as the two
  rows below. ⚠ The claim counts four call sites in `inspecto-engine` and misses **five in
  `inspecto-backup`**; and the compatibility survey the commit's own message called a precondition was
  never performed. ⛔ **A row struck as SHIPPED is where open work hides** — this is the second time that
  has been recorded on this board.

- **P2** · 🔴 **`JOB-PATH-BACKUPTASK-SPLIT-1` — the gate and the backup RUNTIME now disagree, which
  `resolveJobPath`'s own javadoc says must never happen.** Filed 2026-09-16. `BackupTask`
  (`inspecto-backup/.../BackupTask.java`) handles **four of the five keys the operator decision names** and
  still resolves every one CWD-relative through the plain jail — `:81-83` (`dir`, `backup_dir`),
  `:171-172` (`backup_dir`, verify), `:257-258` (`archive`, `target_dir`, restore) — calling
  `PathJail.requireUnderAny(PathJail.allowedRoots(), …)`, **not** `PathJail.resolveJobPath`.
  `BackupTaskProvider` is a live ServiceLoader job type shipping on Standard+
  (`NoBackupTaskShipsInThePersonalBuildTest`). ⇒ the 422 SAVE gate resolves `backup_dir`/`archive`/
  `target_dir` against the Space config root while the backup RUNTIME resolves them against the CWD.
  ⚠ **Not a mechanical fix, and this is why it was not just done:** `SpaceConfigRoot` lives in
  `inspecto-engine` (`com.gamma.pipeline`). Whether `inspecto-backup` may depend on it is a **module-graph
  call** — ⛔ ground it before starting: one report says there is no such dependency, while a grep finds a
  single `inspecto-engine` mention in `inspecto-backup/pom.xml` that nobody has confirmed is a dependency
  rather than a comment. The alternative is pushing the Space-root lookup DOWN into `inspecto-config`
  beside `PathJail`, the push-don't-pull shape `DiscoveredRoots` already uses.

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
  - ~~`compliance-certifications-plan.md`~~ — **NOT a violation.** Only C2 of six workstreams is delivered; C1/C3/C5/C6
    are open and org-gated (§2). It stays live; `INDEX.md` already records the C2 half correctly.
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
  EDITIONS' generated board marks `SP-ACQ-06`/`SP-ACQ-08` (S3/GCS) planned while the **connectors** ship with tests
  — check whether the *Step processor* exists before flipping `ProcessorCatalog`, because a connector is not a Step.
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
  the tree is a HISTORICAL record and was deliberately left alone** — `security.md`, `control-api.md`,
  `auth-security.md`, `EDITIONS.md` and `incidents.md` all describe the flag as *removed*, which is true
  and worth keeping. ⛔ A symbol sweep that "fixes" those five files is undoing the closure.
  → `okf/backend/build-run/operations-reference.md`
- **A capability with no committed example is a capability nobody has ever run** (rule recorded 2026-09-15
  from `COLLECTOR-SPACE-ROOT-1`). Twice in one shift the same shape appeared — `task: materialize` had no
  committed job, and `collector.dataset` has no committed pipeline — and both were space-blind in ways
  only a live multi-Space run could show. ⇒ **The missing example is the risk signal, not a documentation
  gap.** ⛔ Do not read "the tests pass" as "the path has been exercised" for any capability that nothing
  in `spaces/` or `inspecto/examples/` declares. (`acquisition.md` §8.4 and `ingestion.md` are instances
  of this rule; this is the rule itself.)
- **GRAPHIFY-1 tool sync** — ⚠ the row's own check is blind: `.graphify_version` and `graphify --version` both
  read `0.9.53` while `.claude/skills/graphify/SKILL.md` differs from the installed package's copy by ~300 lines
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
  ⇒ **WHAT THIS ROW NOW TRACKS — one question, not the old sync-or-diverge pair: does the team need the
  PowerShell port back?** If the bash steps turn out to be unusable on this sandbox, the answer is a
  **merge story** (the port re-applied over the package's newer text), ⛔ not another straight re-sync in
  either direction — that is the loop this row has now been round once.
  ⚠ The row's own lesson survives intact and is now doubly earned: **comparing version markers will
  never tell you this** — both read `0.9.53`.



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
- **`mail.send` returns SUCCESS when no channel is configured** — `JobResult.ok("no email channel
  configured — nothing sent")`. Deliberate and already in the pending-MAJOR release notes; ⛔ do not
  "fix" it to a failure without an operator call, but know that a scheduled mail job reads green
  while delivering nothing.
- **Kafka is not the data path** — decided in the consignment-ELT design and never re-opened: urgency is a *parameter on one node*, not a second execution model. ⛔ Do not re-file "add a Kafka lane"; a Kafka **Collector** (SP-ACQ-09) is a different, open question.
- **EXPR-1** — expression interpolation inside a longer string only ever per-declaration opt-in, never global
- **BUNDLE-1 perf question** — `no-cache` on content-hashed chunks vs `immutable`; unmeasured; only if a revalidation storm is observed
- **Decided 2026-09-06, keep as designed:** the fetch lane stays FIFO (revisit on the first observed fetch-lane wait) · `requireTopLevelSinks` is a depth rule, not a jail · bounce/complaint handling stays manual until receipts persist · JAVA-SIMP-2 stops at seam #2 (no defect hangs on the sink casts) · the `batch_id` trio rides the MAJOR (release notes hold it) · D-7 `materialized` is done-by-absence · ~~**Postgres multi-user is PARKED** until a multi-operator install exists~~ 🔴 **PARK LIFTED 2026-09-15 — the trigger FIRED**: the operator confirmed a multi-operator install, so the row is re-ranked P2 in §3. ⛔ Do not re-file this as a park · unpack roll-up + entry grain ratified · SEC-07 Vault/KMS only when a client policy requires it · deployment D1–D8 signed as recommended (🔴 **D3 was signed as a 2GB default that DOES NOT EXIST** — corrected 2026-09-09; `DuckDbUtil.memoryLimit(null)` returns `null`, no `scheduler.toon` ships, and this file's own GAP-4 row says so. See `okf/capabilities/editions/editions.md` §5.3 and `okf/capabilities/pipeline-execution/pipeline-execution.md` §2.4).
- **Working as designed** (from the archived gate register §5): write-root 503 · `ConfigSafetyValidator` 422 · `PathJail` 403 · 409 conflict · `ExpressionGuard` · `SqlGuard` · BI share tokens · active-pipeline delete refusal · Incident resolution backend-gated · editions = build flavors · `AuditTrail` has no *authentication* events — sign-in/sign-out are not audited; *authorization* decisions ARE (`access.denied`/`access.granted`, ABAC A5) (⚠ disclose the first half; corrected 2026-09-08) · air-gap CI · append-only registry · manifest owns existence · nothing prunes by default · 🔴 ~~`-Djobs.maxConcurrentRuns` is the only bound~~ **STALE — refuted by D11 and by a second bound** (corrected 2026-09-09): the Run cap is **ON by default at 4** in code (`JobService.DEFAULT_MAX_CONCURRENT_RUNS`), owned by `scheduler.toon`, with the flag a bootstrap default only; and the INGEST engine has its **own** semaphore plus a per-pipeline `PipelineRunGuard`. Two bounds, neither governed solely by that flag — owner §2.5/§3.3 · refused: Spring/Quarkus, distributed-by-default, per-record lineage, Lens-as-permission, PIP-1, sink-owned `partitions`, Decision-Rule + `route:`, raw `Connection`, `CREATE MACRO` outside AUTHORING-REDESIGN-1 (d), step-workbench S3.

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
