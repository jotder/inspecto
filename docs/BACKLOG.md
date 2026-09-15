# Backlog — every OPEN item, one page

**Updated:** 2026-09-15 — all 20 closed rows swept off the page (~470 lines, each as-built verified homed);
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

> **Where the board stands — recounted 2026-09-15 (third pass, after the grounding sweep and two builds).**
> **71 rows: 2 × P1 · 38 × P2 · 31 × P3.**
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
> 🔴 **P1 #2: `CONSIGNMENT-OUTPUTS-NULLRUN-1`** (§4, filed 2026-09-15) — the output registry's UNIQUE key was
> added while one lane still writes `run_id = null`, so those rows silently escape it. ✅ Operator decided
> the fix: **thread a real run id into the graph lane**, not drop the constraint.
>
> 🔴 **P1 #1: `ROUTE-UNGATED-DEFAULT-1`** (§5) — an unlisted route is OPEN, not locked down; 83 mutating
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
> ⚠ **Only the 2 P1 + 38 P2 rows are queued work.** §0 defines **P3 as demand-gated — "build only when
> someone asks by name"** — so those 31 are a list of things deliberately *not* being built, not a backlog
> to burn down. Reading all 71 as pending work overstates what is owed by roughly 40%.
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

⚠ **P1 is no longer drained — `ROUTE-UNGATED-DEFAULT-1` (§5) was filed 2026-09-15 and comes before
everything below.** ⛔ Its first move is the admin-capability decision, not code; see the header block.

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

## 1. Operator decisions pending

✅ **EMPTY again as of 2026-09-15.** The six queued on 2026-09-14 were answered that day; the duckle triage
(nine candidates adopted incl. C7, C5 struck), the route-gating approach and the `JAVA-INGEST-APPENDER-
SERIAL-1` closure were all decided on 2026-09-15. Every answer is recorded on its owning row.
⚠ §1 reporting "empty" is only meaningful if decisions are FILED here when they arise — that assumption
failed once already (three sat unfiled in a plan from 2026-09-11 to 2026-09-14).

⚠ **Read the closure notes below before re-filing anything**: §1 reported itself empty in every handoff
from 2026-09-11 to 2026-09-14 while three of these six sat unfiled in a plan that reported itself blocked.
⛔ **A plan is where a decision is *described*; this section is where it is *queued*.** Describing one in
`superpower/` and not filing it here is what produced that three-day stall, and it is the only way it can
happen again.

**Answered 2026-09-14 — the six, with where each now lives:**

| Decision | Answer | Recorded in |
|---|---|---|
| **Scale-out §5.4 — how does a PIPELINE name object-store credentials?** | **(a) a pipeline field naming an existing `ConnectionProfile` id** | `superpower/enterprise-scale-out-plan.md` §5.4 |
| **`STUDIO-HALVES-1` — how does a Dataset page invoke a materialization?** | **(a) add a materialize route** | its §3 row |
| **`AGT-ARTIFACT-1` — which artifact kind carries a draft?** | **(a) add a `draft` kind** | its §3 row |
| **`TYPEFLOW-DATASET-COLUMNS-1` Q2 — temporal tie-break** | **(a) several date columns ⇒ derive `temporal` for NONE** | `superpower/dataset-column-derivation-plan.md` §6 |
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

**Swept again 2026-09-08 — and one gate FELL.** **Accurate: 14 of the 15 gates were RUN here — all 14
still gated.** Plus 2 gates outside this section (§3 Platform Services Stage 2, §5 D8-SUPPRESS-1), also
run, also holding. The 1 not run, and why: **Completeness KPI hold** has nothing to run, because its gate
turned out to be an engineering action, not a check.

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
| **Row 15 — ELT Phase 6 deletion half** | Delete the legacy flat read path (amendment §6 step 4). 🔴 **Two earlier §6 steps were never on this board and are added 2026-09-10 (Sprint 7.5): step 1, the one-shot converter, is UNBUILT** — the plan claimed *"✅ the converter exists (`inspecto migrate-configs`)"* and that command appears in **no source file**; the commit it cited was a `RecipeConverter` projection fix. **And step 2, the parity gate, is unverified** — ⛔ do not read `RecipeConverterTest` as meeting it: that proves *round-trip parity of the projection*, while step 2 asks for the full suite **EXECUTING** through the compiled-recipe path. Different claims, only the first evidenced. The `-Dingest.lane=auto\|graph\|flat` flag exists (`ConsignmentIngestStrategy.admittedLift`, 2026-09-02); the verification minor must SHIP first. ⛔ Not closable by code; ⛔ do not start it on momentum. Then `withMappingContext` → `PipelineLift` comes due only if the graph lane executes the map node. The waves board is 16 of 17. Also absorbs RECORD-TRANSFORMER-1 (d): the ingest lane runs exactly one projection slot, so a second `transform.sql` cascades only once the graph lane carries ingest. | 🔴 **Gate CORRECTED 2026-09-07 (second pass) — the one I wrote a few hours earlier was itself unfalsifiable.** `git merge-base --is-ancestor v3.12.0 master` can never return 0: **`v3.12.0` is a tag on `origin/3.x`**, a retired line, cut 2026-06-05 — it is not an ancestor of master and never will be. The newest master-ancestor tag is **`v3.11.0`**. D-2 (`elt-final-amendment-plan.md` §9) says "Converter + **one flagged verification minor**, then the legacy readers are deleted", so the gate is a MINOR **on master** after v3.11.0 that ships the converter and the `-Dingest.lane` flag. **Check: `git tag --merged master --sort=-v:refname \| head -1` — still `v3.11.0` on 2026-09-07 ⇒ gated.** |
| **Release notes for the next MAJOR** | **Drafted** in `okf/backend/control-plane/api-stability.md` §Release notes; append there with every further `feat!:`. ⚠ The `batch_id` rename trio rides this release and is NOT enumerated in that list — see §7. 🔴 **`RETIRE-HALVES-1`'s route removals (2026-09-14) ride this MAJOR and are typed `docs:` in git** — they landed in `519673a7`, a commit whose message describes a whitepaper change, because a concurrent session committed the staged tree under its own heading. They are enumerated under *Breaking — HTTP routes REMOVED*; ⛔ a `git log` scan for `feat!:` will not find them. | `git tag` — the next MAJOR tag. |
| **X5 cross-lane drill-down + StepInfo envelope** | One-Consignment drill-down across lanes; ~1 KB pointer+schema+diagnostics envelope, failure routed by PORT. Phase-7 convergence. | `git tag` — the next MAJOR (pipeline-spec §13 D2). → `okf/backend/pipeline-graph/execution-lanes.md` |
| **X-Actor full removal** | Remove the header path entirely (already rejected outright on Standard/Enterprise). | 🔴 **Gate restated 2026-09-07.** It used to read "client migration with the API-v1 sunset" — but that apparatus was **deleted 2026-07-25**, and `api-v1.md` contains **zero** occurrences of "Actor", so the gate pointed at something that no longer exists. The only remaining exposure is Personal; a MAJOR is the sanctioned break. **Gate: the next MAJOR tag.** → `okf/backend/editions/auth-security.md` · `EDITIONS.md` SEC-11 |
| **OPS-5 provenance conservation** | Live-feed soak only — no code left; feature built, off by default. The discharge criterion is well written (`docs/ops/provenance-conservation-verification.md` steps 1–3, ground-truth `recordsIn`/`recordsOut`). | **Close when that file gains a dated results section.** Nothing in-repo would otherwise show the soak had been run — the work could be done and the row would still read open. |
| **Deployment topology live validation** | T2/T3/T4 reference deployments, the D8 IAM pair, GAP-7 blueprints, grammar-config live smoke. ⚠ **Evidence pointer corrected 2026-09-07:** the RTO/RPO statement with its `<OPERATOR TO STATE>` placeholders and empty drill table is `compliance/evidence/rto-rpo-statement.md`, **not** `docs/ops/backup-restore-runbook.md`, which the row cited and which contains none of it. | A reference deployment. **Repo-side half is checkable now:** close it when `rto-rpo-statement.md` carries stated targets (⚠ the signed per-tier D6 numbers are recorded in `okf/capabilities/editions/editions.md` §3.14 — transcribe from there; the drill record still has to be produced) and ≥1 drill row. → `archived-documents/plans-archive/deployment-topology-plan.md` (§10 D1–D8 signed 2026-09-06) |
| **Compliance program (NFR-7)** | External only: C1 applicability statements, ISMS boundary, auditor engagement, pen test, C5 policy content, C6 FedRAMP package + FIPS leg (demand-gated), the ISO 8.8 advisory-watch process. | ✅ **Gate MADE RUNNABLE 2026-09-07 (second pass).** It was the one row with no repo-side check at all, so it could never be evidenced either way. It now has a landing place per sub-item: **each of the seven closes when its own row in `compliance/controls-matrix.md` §4 carries a dated line.** **Check: `grep -c '^| NFR-7 ·.*⬜ open' compliance/controls-matrix.md`** — **7** today; the row closes at 0. 🔴 The check must be **line-anchored**: the unanchored `grep -c "NFR-7 ·"` returns **9**, because the surrounding prose (including the sentence stating the check) matches its own pattern. A check that counts its own documentation is not a check. ⚠ The five REPO-SIDE artifacts this row used to carry (customer verification runbook, CI-evidence doc, recorded restore drill, G8 RBAC evidence, G9 FIPS) are file-existence checks, not external gates — they moved to §5 on the first pass. → `compliance/controls-matrix.md` §4 |
| **SOC 2 Type II window** | Opening the 6-month observation window was **decided 2026-09-06**. The HIPAA/PCI framework choice stays deferred until a prospect is named. | 🔴 **No start date is recorded anywhere**, so the 6-month end cannot be computed and nobody can tell whether the window is running. **First action is not external: record the start date in `compliance/controls-matrix.md`.** The gate then becomes `start + 6 months` — arithmetic. |
| **D13 parser field tiers** | Run the onboarding-observation session. Second question for the session: every `tier:'required'` field ships `required:false` validators — should "required" validate? ⛔ Explicitly NOT an engineering guess. The **pre-agreed analysis rule** (M / T-in-3-lanes → required; never-touched-and-never-asked → advanced; one **M** on a required field files a validator, two decide it) is in `okf/frontend/features/grammar-config.md` — ⛔ do not re-derive it after the session, that destroys the whole point of agreeing it in advance. The kit is archived at `archived-documents/plans-archive/parser-field-tiers-interview-plan.md` and is still runnable, but 🔴 **re-ground its inventory and task script first** — both predate `d012f721` (2026-09-04), which dissolved the `files` section and turned tabs into sections. | A real onboarding user. **Close when `parsing-attributes.ts` carries `tier:` values annotated with the observations that earned them, plus a question-2 decision note in `okf/frontend/features/grammar-config.md`.** 🔴 Restated 2026-09-09: this read "close when `superpower/…interview-plan.md` gains a dated session record", which archiving would have made uncheckable — no shift may maintain a file in the archive tier. The deliverable is the plan's own, and it is checkable in the current tier. |
| **SEC-INCIDENT-1 carry-forwards** | Incident CLOSED BY DECOMMISSION 2026-08-29. 🔴 Due on closure and NOT done: delete the off-repo pre-rewrite backup bundle (five cleartext secrets; retention condition lapsed); confirm none of the five values was reused elsewhere. Internal hostnames/IPs still published in-repo (lower severity). | Operator, off-repo. **Close when `compliance/controls-matrix.md` CC6.1 carries a dated line confirming the deletion and the no-reuse check.** ⚠ Deleting an off-repo bundle leaves no repo trace, so without that line this overdue item can never be marked done or chased. |
| **DATA-GOV-1 archive** | Move the real carrier corpus to an encrypted out-of-band archive on company storage with a fetch script; access held by the data-agreement owner. | Org action. **Close when `PROJECT_NOTES.md` §DATA-GOV-1 carries the dated archive location and the fetch-script path.** |
| **EOI-7b** | Publish eoiagent `0.1.0` artifacts to a registry; CI rebuilds from tag meanwhile. | ✅ **Checkable in one grep:** close when `.github/workflows/ci.yml` no longer checks out and builds `jotder/inspect-agent` from source (3 references today). |
| **AGT-6b multi-step agent graphs** | First cut = generalize `RunbookActions`, never free-form ReAct over mutating tools. | ✅ **RUN 2026-09-08 (network), and it HOLDS — but only on its second precondition.** ⚠ The old check (`gh search code`) was unfalsifiable — see the section header. Probed instead via the git-tree API. **(1) The per-tool `DryRunProvider` seam HAS SHIPPED** upstream: `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunProvider.java` + `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunResult.java` + four per-tool `*DryRun` tools (`AuthorPipelineDryRun`, `EditConfigDryRun`, `RunPipelineDryRun`, `TriggerJobDryRun`), under an **Accepted** ADR-0008 ("all mutating actions require an ApprovalGate + dry-run, enforced in the runtime", 2026-06-19). **(2) The approval gate is STILL synchronous per-call** — `eoiagent-core/.../host/ApprovalHandler.java` is a single blocking `ApprovalDecision onApprovalRequested(ApprovalRequest)`, no future/callback — so nested gates still deadlock and this row stays gated on that alone. **Check: `gh api 'repos/jotder/inspect-agent/contents/eoiagent-core/src/main/java/com/eoiagent/host/ApprovalHandler.java' -q .content | base64 -d`** — reopen when that signature stops being synchronous. → `archived-documents/plans-archive/agt-6-plan.md` §4 |
| **E1 Enterprise distributed tier / Stage-2 streaming** | **Design SIGNED 2026-09-10** — T5 partitioned scale-out by Space (`superpower/enterprise-scale-out-plan.md` §9 D1′–D13); phases A–B are also Standard's T4 DR. | 🔴 **No longer gated — phase A HAS STARTED.** Spikes S1–S5 reported 2026-09-10 (3 closed, 2 partial); **A0 + A1 shipped 2026-09-11** (store-family health + the topology switch) and **A3 shipped 2026-09-12** (`DbEventStore`, D6). ⛔ What still gates the phase is **external, not a decision**: its own acceptance is `PostgresStateStoreTest` 12/12 and this checkout has **no Postgres**, so that class skips entirely — the coverage exists and has never executed. A2 (the connection pool) is re-scoped as **Enterprise, deferrable**. Stage-2 streaming stays unscoped (`STREAM-CONSUMER-1` is its first design pass). |
| **Completeness KPI hold** | K2 wiring / K4 / K5 held: whether `{seq}` restarts per hour is a carrier fact. | ⬜ **NOT RUN — there is nothing to run.** 🔴 **Gate CORRECTED 2026-09-07 (second pass) — my own first-pass annotation was wrong.** It said "a query over one month of landed filenames answers it … roughly half an hour of work". **There is no such query to run:** the plan's own §2b records that **no default-on durable store holds processed filenames** — `-Dfile.stages.backend` defaults to `none` (`ServiceStores:141`), the acquisition ledger to `memory` (`OperationalDb:92`), and the status CSV is buffered, un-fsync'd and one file per run. Analysing `file_stages` unflagged would report "no gaps" on a stock deployment — the `ConservationCheck` trap. ⚠ So this row is **not** carrier-gated and **not** query-gated: **its first action is engineering** — give the filename history a durable default-on home (or make the analysis REFUSE when the store is `none`). Only then does Q2 (`{seq}` per-bucket vs continuous) become answerable from data. 🔴 **REGROUNDED 2026-09-14, and the parenthetical is not buildable as written: there is NO analysis to make refuse.** K4 — the reader that would turn `file_stages` into a completeness answer — **was never built**. `FileSequenceGaps.analyze` (K2, `FileSequenceGaps.java:117`) and `VolumeBaseline.assess` (K3) are pure functions with **no production caller** (only their own tests), and they take names/series as arguments, so they never see a store at all. `DbConsignmentOutputStore.dailyVolume` (K1, `:299`) is already fail-closed — it throws on a blank producer and on a query failure rather than returning an empty list, with a comment saying an empty list is indistinguishable from a breach — and it too has no production caller. ⇒ **the refusal has to be designed INTO K4 as it is written, not retrofitted**, which makes this one action, not two. ⚠ **The store kind does not survive on the object a reader holds:** `ServiceStores.openFileStageStore` returns `null` at the default (pinned by `ServiceStoresDefaultsTest:67`), so nullness is the only signal there, while `AcquisitionLedgers.shared()` **never returns null** — it falls back to `InMemoryAcquisitionLedger`, so a caller cannot tell memory from durable by nullness and must ask `StoreHealth.of(spaceId)`, the one place the resolved backend is still known. ⚠ Two citations in this row have **drifted and are wrong**: the defaults are `ServiceStores.java:237` / `OperationalDb.java:97` for file stages, and the acquisition ledger is `-Dacquire.ledger.backend` in `AcquisitionLedgers.java:152` — **not** an `OperationalDb.Family` member at all. ⛔ The live instance of the trap is `ConservationCheck.imbalances` (`PipelineJobRunner:536`), and the archived plan explicitly says **do not conscript it into the completeness KPI** — it is the cautionary precedent, not the seam. Imitate `RowShaper.java:373` (refuse a windowed dedup with no ledger) instead. → `archived-documents/plans-archive/completeness-kpi-plan.md` §2b |

## 3. Product features — decided or unblocked, simply unbuilt

Grouped by area. A row with lettered items keeps the letters of its source doc so the two stay aligned.

### Authoring (Parse / Transform / pipeline editor)

- **P2** · **AUTHORING-REDESIGN-1** — open letters (⚠ the old "(j)(l)(n2)(o) are in §1" clause was stale in all four: (o) SHIPPED 2026-09-07 as WORKBENCH-S4 — all three slices, (l) and (n2) SHIPPED, (j) `engine: auto` was already answered by shipped code; (f)(g)(m) SHIPPED 2026-09-06 — `JOIN_REFERENCE_MISSING`/`JOIN_ON_MISSING`/`UNKNOWN_JOIN_REFERENCE` at save, `SchemaMappingDrift` on all three schema save paths, `?pipeline=` sent by the UI): (c) v2 structured AST table over the SQL for WHERE/JOIN editing — ✅ **precondition DISCHARGED 2026-09-07: it does.** `json` is statically linked into the DuckDB JDBC artifact, so nothing is installed or auto-loaded and the seal is irrelevant to it: `json_extract`, `json_structure` and — the one that matters — **`json_serialize_sql`**, which returns the whole parsed AST as JSON, all work on a sealed connection while `INSTALL excel` and re-opening `enable_external_access` still fail. Pinned by `SqlSandboxTest.jsonWorksOnASealedConnection`. ⚠ So (c) reads an engine-produced AST rather than re-implementing a SQL parser in TypeScript — the same refusal the step workbench made for reference detection; (d) v3 macros as the UDF registry (per-connection re-creation in `EnrichmentEngine`, `PipelineJobRunner`, `ConsignmentIngestStrategy`, preview) — demand-gated; (e) column metadata editing on the Transform pane (Parse D2) — needs a backend home for metadata on a `transform.sql` node first; (i) per-row "sample resolves to" line — no host resolves a sample against an `AttributeSpec`. Still open on (f): which COLUMNS the reference carries is the dry-run's question (it reads the store); the save checks existence and `on` presence only. → `okf/frontend/features/schema-mapping-authoring.md` §0
- **P2** · **Step Processor catalog** — 119 processors: **35**<!--count:processors-delivered--> delivered / **17**<!--count:processors-partial--> partial / 67 planned (`processor-catalog.contract.json`, counted 2026-09-10 — `quality.schema.drift` DELIVERED 2026-09-10 as a per-batch `quality.schema_drift` Signal; the earlier count was 34/18 on 2026-09-08 — the earlier "69 planned" was a grep artefact) (`transform.lookup` DELIVERED 2026-09-06). Each partial is a product decision (Kafka consumer, XPath grammar, drift report, profiler, resampler, KPI layer, Jinja, graph tagging, commit controller, SLA object, view/email/webhook sinks…) — pick one by name. → `EDITIONS.md` §Step Processors · `okf/backend/pipeline-graph/step-catalog.md`
- **P3** · **P4 Test mapping on a generic `parser` node** — **RE-SCOPED and DEMOTED P2→P3 2026-09-09**, which is what the row's own "re-scope this row before building" asked for. Grounded against source:
  • ⚠ **The (l) discharge does not unblock THIS row.** What shipped 2026-09-06 unblocked Test-mapping on a **dangling per-format** grammar binding (`isDrawerParse` admits it, the pane flags "template missing"). A **generic** `parser` is the case the owner doc calls **unmappable**, and it still falls to `GrammarEditorDialog`. The gate is discharged for a *different* node.
  • 🔴 **Demand-gated because the authoring surface is gone.** Test mapping is **read-path only since 2026-09-05** — the Load pane was deleted and nothing authors a parse node's mapping any more (measured: **zero** files match `pipeline-load-definition`). Mappings *are* still authored, but as the standalone **Mapping component** (`mapping-editor.dialog.ts`), which is a different surface with its own editor. So building this would test a mapping an operator cannot author on that node. ⛔ Per §0's rule, that is P3: build only when someone asks by name.
  • ✅ **The offline caveat is deleted, not carried:** "non-`DIRECT` types show blank (mock has no SQL engine)" cited the offline mock backend, which was **removed 2026-08-31**. There is no mock to be blank.
  → `okf/frontend/features/pipeline-editor.md`
- **P2** · **Canonical-pipeline selective bundle export/import** — the metadata bundle's `authored-pipeline` kind still targets the RETIRED `*_flow.toon` `PipelineStore`; a canonical `*_pipeline.toon` transfers only via the datasource zip or the client-side stream-config bundle. Wanted: one selective export/import with dependency closure (schemas, per-segment schemas, grammar/enrichment companions, Connection as secret-free requirement) and retire/repoint the `authored-pipeline` kind. **Decided 2026-09-06 (operator): in bundle manifests `schema` = the REGISTRY id (`registry/schemas/<id>`); a pipeline-owned `<name>_schema.toon` (+ its `_mapping.csv`/`_structure.csv` siblings) travels under its own kind, not as `schema`.** Apply this in `BundleRoutes`/`transfer/bundle.ts` when the row is built → `okf/frontend/features/onboarding.md`

- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** an author asks for AI drafting on a **non-`schema` kind**. The capability
  was removed because its one applicable kind lost its `ConfigSpec`, so restoring it is worth the design cost only
  if someone wants it where none exists. · **AI drafting has no applicable component kind** — restore `<inspecto-ai-assist>`/`component_draft` for a kind: either give `grammar`/`transform`/`sink` a backend `ConfigSpec` (none has one; `ConfigSpecs.TYPES` excludes them) or rework `SchemaEditorDialog`. No low-risk slice survives — design first. → `okf/frontend/features/inline-ai-authoring.md`
- **P3** · **`AGT-SEGMENT-1` — the assistant's commercial framing is an unvalidated product read.** The tier packaging (A Explain / B Author-with-approval / C Bounded autonomy), the "Tier A is the wedge" moat argument and the SHADOW-first on-ramp were written as a read of the codebase + roadmap and **never validated against a client segment** — the archived plan's own words. Telecom vs general regulated enterprise changes the emphasis. ⛔ **Decided 2026-09-10: keep the caveat — reopen on the first customer conversation**, not before; guessing a segment now would replace one unvalidated read with another. ⚠ Needs **product input**, not engineering; nothing in the product depends on it, but the framing is now quoted in a stakeholder-facing doc, so it must carry its caveat until this closes. (Was `agt-6-plan.md` D6, which had no board home at all.) → `stakeholders/PRODUCT_CAPABILITIES.md` §"How the ladder is packaged" · `archived-documents/plans-archive/agt-6-plan.md` §2
### Onboarding, Catalog, Parsing


- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the first `.xz`/`.zst` delivery arrives (it forces the decompression-library
  sign-off at the same moment). ⛔ **Two items were removed from this row 2026-09-13** — the "stale
  `META-INF/services` ORDER MATTERS header" was **grounded FALSE** (it correctly describes the live linear-scan
  order, TarGz before Gzip), and the schema-form empty-list opt-out is a **UI defect** that was riding in a codec
  row where nobody would find it — filed separately in §3. · **Unpack (11) absent codecs** — (xz/zstd need a new decompression library: a dependency sign-off, not a build) — xz and zstd have no plugin; `.Z` has no round-trip test; multi-part/split archives (`.z01`, `.part1.rar`) unhandled — arrival-completeness is a Collector question. ⛔ ~~The UI cannot author the explicit empty-list `data_extensions[0]:` opt-out~~ — **split out 2026-09-13** as its own §3 row (`SCHEMA-FORM-EMPTY-LIST-1`). ⛔ ~~Stale `META-INF/services` "ORDER MATTERS" header~~ — **grounded FALSE 2026-09-13**: the header is current and correctly describes the live linear-scan order. → `okf/backend/engine/unpack-stage.md`
- **P2** · **Onboarding (Stream/Reference)** — D5-ref: how a `delete` tombstone *enters* the reference store (reserved column? Decision Rule consequence?) — wait for a real delete-feed; D6-ref: within-batch same-key tie-break is arbitrary — add an optional latest-by-`order_by` column only when needed; optional templates entry (space-template-gallery precedent). ⚠ Enrichment/job configs still derive identity from name. ⚠ Do not implement name-deferral by holding the draft client-side. → `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md`
- **P2** · **Onboarding ↔ Pipeline unification W4/W5** — (W0 PROVEN 2026-09-06: `LiftLowerFixtureSweepTest` runs every `spaces/**/*_pipeline.toon` through the editor's own seam — `PipelineEditable.toMap` → codec → STRICT lower — and all 22 survive verbatim; it stays as the standing gate.) W4: `EnrichmentService` incremental-vs-full recompute — never silently convert one into the other. W5 promotion-grade export: extend `BundleExporter`/`DataSourceBundleResolver` to decision rules + reference datasets; import-time referential integrity (a missing connection is not caught until first poll). Engine has no grouping transform (rollup honestly = `sink.materialized`). ⛔ `PipelineCompiler.toConfigMap` deliberately not migrated. → `archived-documents/plans-archive/onboarding-pipeline-unification.md` · `okf/backend/pipeline-graph/editable-round-trip.md`
- **P2** · **Parsing (Stage-1)** — ASN.1 grammar source: a reference to a stored schema module instead of pasted module text (also the prerequisite for a per-vendor transform config home); drop-in `plugins/` jar directory (JobPackManager classloader precedent) so a customer parser deploys without a rebuild. ⚠ `asn-parser/src/main/java` is NOT dead (compiled by `legacy-code/pom.xml`); corpus tests are opt-in and data-gated (DATA-GOV-1). → `okf/backend/engine/parser-plugins.md`
### Execution, Consignments, Pipeline graph


- **P2** · **Branch-aware executor residuals** — ((b) and (c) are design passes before code; (d)–(g) wait for a real need) — (b) multi-schema + route needs a **segment-scoped lift** (`writeAndTrace` runs once per segment while the divert lifts the whole graph) — ⛔ do NOT just lift the refusal; (c) mid-branch transforms in the recipe route verb — no per-branch scaffolding in `RecipeCompiler.route()` / `PipelineLift.branch()`, design pass written; (d) still unimplemented anywhere: `adapter`, `alert`, `event`; still refused at lowering as flat homes: `transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest; (e) acquisition-side "listed remotely, not yet fetched" gauge — name it first; (f) `acquire.maxFilesPerCycle` — only if overshoot is real; (g) `sinks:` follow-ups: per-sink `ducklake` block in flat `.toon`, decision-rule routing with `sinks>1`, versioned reference store with `sinks>1`, and a `ConfigSpecs`/`ConfigJsonSchema` structural spec for `sinks:`. ((a) `mode: clone` **shipped 2026-09-06** — `RouteArming` no longer refuses it.) → `okf/backend/engine/branch-aware-ingest.md` · `okf/backend/engine/output-sinks.md` · `archived-documents/plans-archive/mid-branch-transforms-design.md`
- **P2** · **Platform Services Stage 2 / 3** — (gated on the at-rest execution decision + the S2-2 spike) — Stage 2 open Step-kind registry (`StepTypeProvider` with `LOWERED`/`EXECUTED`, `StepContext`, failure mapping, watchdog) — ✅ **gate RUN 2026-09-07: holds**, no `StepTypeProvider` exists in any module: needs the decision to execute an intervening node at rest (the `graphLaneCarries` boundary = Phase 6 precondition) plus the S2-2 bridge spike (rows/s through a no-op `EXECUTED` Step vs fused) before GA; ⛔ third-party `LOWERED` stays closed until a SQL-fragment guard exists. Stage 3 pack-contributed services (`ServiceProvider` SPI; collision fails the pack atomically; reference-tracked quiesce). `DatasetAccess` after the Consignment Selector. No Job-side watchdog (R1) — a hanging Job is a recorded gap. Filtered `services()` on `ProcessorContext` (D4) and a devkit jar (D5) only on demand. → `okf/backend/control-plane/platform-services.md`
- **P2** · **Consignment addressing** — (torn multi-file reads: CLOSED 2026-08-29 by the pinned `ConsignmentSelector` list — this row said "open" for a week; the two readers that still re-globbed, `DbBrowserRoutes.browseStore` and `ExpectationEvaluator`, were pinned 2026-09-06) 🔴 `generation` was called "a dead field (always 0, never read)" — **the never-read half is FALSE** (`DbConsignmentOutputStore.java:655` reads it; `:219` writes it), corrected 2026-09-14; ⚠ `retire_superseded` must be configured or every full recompute leaves a complete extra copy on disk; ingest-side Consignment-scoped accessor waits for a consumer; ⚠ `DatasetRelation.temporalColumn` has no caller and cannot safely gain one on a write path. → `okf/backend/engine/consignment-addressing.md`
- **P2** · **EXECUTION-RESIDUALS X4 + X1 deferrals** — X4 record-level replay from quarantine: sidecar error manifests (offset/reason), all-or-nothing vs eject-and-continue as per-pipeline CONFIG — ⛔ no build without a driver (same item as the run-detail "reprocess is whole-batch only" note). X1 deferrals: per-pipeline `processing.retry` block (regenerate node-attributes + step-types contracts); operator cancel / retry-now affordance (today: delete the sidecar under `<status_dir>/retries/`, or `reprocess`). → `okf/backend/pipeline-graph/execution-lanes.md` · `archived-documents/plans-archive/execution-residuals-plan.md`
- **P2** · **`STREAM-CONSUMER-1` — adapter stream-consumer runtime** (filed 2026-09-10 — it was committed in `roadmap/ROADMAP.md` §3.4 and listed in `okf/capabilities/acquisition/acquisition.md` §"Open elsewhere on the board" with **no board row**, the id column pointing back at the ROADMAP paragraph). The land-then-ack seam exists (a source-side `post` that deletes the remote original runs only after the local copy is committed); the **consumer loop** that keeps an adapter draining a streaming source with at-least-once semantics does not. Not demand-gated: the ROADMAP commits to it. First action is a design pass on where the loop lives (Collector scan vs a long-running job), not code. → `okf/capabilities/acquisition/acquisition.md`
- **P2** · **Pipeline graph** — flip the intake cap on by default (needs a soak); a pre-materialise cap to save remote-fetch bandwidth (cap applies post-dedup); 🔴 **THREE** kinds still last-one-wins, deliberately out of A2 scope: `acquisition`, `gap`, `dedup.marker` — *corrected 2026-09-09: `parser` was in this list and does NOT belong; a second parser is REFUSED by name (`MULTI_PARSER`, `PipelineEditable.java:65,696`), which is the opposite of last-one-wins. `pipeline-editor.md` §Multiplicity states it correctly.*; 🔴 ~~`BatchGraphRunner` has zero production callers~~ **WRONG ON BOTH COUNTS — corrected 2026-09-09.** (a) **There is no class of that name** — it was renamed in the 2026-08-31 Consignment commit. (b) The class that DOES exist, `ConsignmentGraphRunner`, has **production callers**: `engages()` drives the live lane admission (`ConsignmentIngestStrategy.admittedLift`) and **`run(...)` executes on the ingest path** (`ConsignmentIngestStrategy:355`). ⛔ This row was cited as Row 15's parity blocker, so re-derive that gate before using it. What IS still owed is §6 step 2, the parity gate through the compiled-recipe path. Owner: `okf/capabilities/pipeline-execution/pipeline-execution.md` §2.3; → `okf/backend/pipeline-graph/pipeline-graph-design.md` §14
- **P2** · **Consignment ELT** — (three items added 2026-09-07 from the archived plan's §11.2/§11.7/§15, which BACKLOG never carried: **`batches` is structurally singular** — its `schema_name`/`output_table` are one-per-row while a Consignment's EL emits a row set *per schema*, so this needs either one row per `(consignment, schema)` or a child table, an open decision; ~~whether a **durable `DeliveryReceiptStore`** exists beyond the in-memory one is a one-grep check still owed~~ — ✅ **ANSWERED 2026-09-14: it exists** (`inspecto-engine/.../notify/DbDeliveryReceiptStore.java`, wired through `ServiceStores`/`OperationalDb`); and §8.4's SLA config object is dropped with sealing, not pending.) `generation` is on the registry but compaction does not stage generations — ⚠ **and it is never incremented at all** (`ConsignmentOutputs.java:336` writes a literal `0`), so "dead field" was closer to true than the 2026-09-14 correction allowed; ~~`run_id` is `null` everywhere~~ ~~✅ CLOSED 2026-09-13 — `run_id` carries a real attempt on every production path~~ 🔴 **BOTH of this row's key claims are REFUTED, regrounded 2026-09-15, and are now `CONSIGNMENT-OUTPUTS-NULLRUN-1` (§4, P1): the key was not "still not addable" — it was ADDED on 2026-09-13 (`DbConsignmentOutputStore.java:122`) together with the `ON CONFLICT DO UPDATE` this row calls unbuilt (`:225`); and `run_id` is NOT supplied on every production path — `ConsignmentGraphRunner.java:83` still threads null, so those rows escape the key.** ⛔ Do not re-file either claim from this row; §7.4 rollup cache deliberately unbuilt until read-time aggregation is measurably slow; §7.3 unpartitioned fallback stands by operator call — revisit if flat summary targets appear. → `okf/backend/engine/db-layer.md` §3.9
- **P2** · **Completeness KPI (when the hold lifts)** — K2 wiring (`FileSequenceGaps` analysis shipped `14c6ef0e`, wiring not built; ⚠ **"needs `SeqScope`" is STALE as a blocker — regrounded 2026-09-15: `SeqScope` already ships** as a nested enum at `FileSequenceGaps.java:74-79` (`PER_BUCKET`/`CONTINUOUS`). The type exists; only the wiring does not. ⚠ **K1 is unwired too**, which this row never said: `DbConsignmentOutputStore.dailyVolume()` has zero call sites, same as `VolumeBaseline`/`FileSequenceGaps`); K4 `kpi.completeness` job type (`JobTypeProvider` + descriptor + `ParameterDecl`s, cron'd, one config per pipeline, signal + deduped Incident on breach, must refuse loudly when `-Dconsignment.outputs.backend=none`). ✅ **K5 SHIPPED 2026-09-07** — 🔴 corrected 2026-09-09: this row and `INDEX.md` both listed K5 as remaining while the plan's own slice table and §5 recorded it done, a three-way split. Non-blocking: signal type naming `kpi.completeness.evaluated`/`.breached` (🔴 **"do not grow the `EventType` enum" is wrong in KIND — corrected 2026-09-15: there is no enum.** `EventType.java:19` is a class of `public static final String` constants, deliberately open per its own javadoc, and no `kpi.*` entry exists. The constants-class guidance still applies; the thing it warns about does not exist), K3 baseline-window default as a job parameter. ⚠ `VolumeBaseline`/`FileSequenceGaps` have no production caller today. 🔴 **Three items had no board home at all until 2026-09-09**, found when archiving the plan: (a) **`KPI-UNKNOWN-1`** — a null-`bounds` sink's daily count is **UNKNOWN, not zero**, and the KPI must carry that end to end (only the registry-off trap was ever filed); (b) where the sequence **template** itself comes from — the Collector's existing one, a job parameter, or the Collector's with an override — still undecided; (c) K1's and K3's acceptance criteria, now in `okf/capabilities/observability/observability.md` §3.9. → `okf/capabilities/observability/observability.md` §3.9 · `archived-documents/plans-archive/completeness-kpi-plan.md`
- **P2** · **`WHITEPAPER-DOCX-DRIFT-1` — the `.docx` generator re-authors the prose instead of reading the
  `.md`** (filed 2026-09-11 at handoff, verified not merely suspected). `scripts/generate_whitepaper_docx.py`
  (593 lines, tracked) names `INSPECTO_ENTERPRISE_WHITEPAPER.md`'s path **only as its OUTPUT `.docx`**
  (`:592`) and never reads the markdown — the document body is built from ~12 hand-written
  `add_paragraph`/`add_heading` blocks. 🔴 **So the v1.2 rewrite (`db11a412`) did not reach the `.docx` at
  all: the generated file still carries v1.1 prose**, and the `.docx` is gitignored, so nothing flags the
  divergence. A stakeholder handed the `.docx` gets a different document from the one the guards check —
  and `check-doc-counts` only validates the `.md`. ⚠ Also hardcodes an absolute
  `c:\sandbox\inspecto-clean\…` output path, so it cannot run on another checkout.
  ⇒ Either make it render the committed `.md`, or delete it and produce the `.docx` by conversion. ⛔ Do not
  hand the `.docx` to anyone until this is settled. → `docs/stakeholders/README.md`
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** an author needs to declare "this Collector takes NO data
  extensions". · **`SCHEMA-FORM-EMPTY-LIST-1` — the UI cannot author an explicit empty list** (filed 2026-09-13,
  split out of the Unpack codecs row, where it was a UI defect nobody would have found). schema-form's `list`
  control writes an empty list as `null`, so the explicit `data_extensions[0]:` opt-out is unauthorable from the
  UI — `null` means "unset, use the default", which is the opposite of "deliberately none". ⚠ Hand-authored TOON
  can still express it, so this is an authoring gap, not a capability gap. → `okf/backend/engine/unpack-stage.md`
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** decide it on its own terms — wire the panel somewhere, or
  delete it with its endpoint's only consumer. · **`DERIVED-SCHEMA-PANEL-ORPHAN-1` — a live endpoint's only UI is
  mounted nowhere** (filed 2026-09-13, split out of `TYPEFLOW-DATASET-COLUMNS-1` where it was a sub-clause).
  `GET /config/schema/derived` is wired end to end (`ConfigService.derivedSchema`), but
  `DerivedSchemaPanelComponent:29`'s selector appears only in its own spec and the `schema/index.ts` barrel — in no
  template and no route — so nothing mounts it. ⛔ **Do not delete on sight:** `MOCK-DEAD-COMPUTE-1` was closed as
  a **RETAIN** precisely because dead code here can be a deliberate test vehicle. Establish which this is first.
  → `okf/frontend/features/schema-mapping-authoring.md`
- **P3** · **`TYPEFLOW-DATASET-COLUMNS-1` — a Dataset's columns are never derived from the pipeline that
  fills it** (filed 2026-09-11, split out of `TYPEFLOW-CONSUMERS-1` (b)). A `DatasetColumn` is
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
  `superpower/dataset-column-derivation-plan.md` §2 and are corrected there too):
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

  ⚠ **The HEADLINE is still open and this row stays P3** — steps 3+4: `TypeFlow.Column` is still
  `record Column(String name, String type)` (`TypeFlow.java:30`), `sinkColumns` (`:75`) yields no role, its
  only consumer is `ConfigPreviewRoutes.java:110`, and no DuckDB-type → coarse-type mapping exists.
  → `okf/backend/engine/catalog-vs-executors.md`
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
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the first operator who asks for a spreadsheet download — which is
  also when the new-dependency question gets answered, rather than in advance. · **D-8 XLSX export** — zero groundwork (no spreadsheet library in any pom); gated only by a bare label — state the operator question before answering it. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §9 D-8
- **P3** · **`RECON-CARDINALITY-2` — row-level pairing for a cardinality break.** All that survives of
  `RECON-CARDINALITY-1`, which was **CLOSED 2026-09-15**: tier 1 shipped whole and was verified in code —
  `ReconService.java:76` (the `Cardinality` enum), `:448-451` (the `cardinality_break` column, emitted only
  when the spec is not `MANY_TO_MANY`), `:515` `cardinalityViolation` shared by both the summary and list
  SQL so the two cannot diverge, `ReconConfigLoader.java:58-62` reached by **both** production callers
  (`ReconRunJob:78`, `ReconRoutes:301`), and the client end at `recon-board.ts:86,160-163`.
  Tier 2 is the demand-gated remainder: **which rows on each side formed the break**, not just that one
  occurred. ⚠ It is not a display change — `ReconRoutes:194-195` promotes only `breakKey`/`breakType`, so
  carrying pairs needs a wider Incident attribute shape first. ⛔ Demand-gated per §0: build when someone
  asks by name.
  → `okf/capabilities/incidents/incidents.md` · `archived-documents/plans-archive/recon-cardinality-plan.md`

- **P3** · **D-11 hand-authored `relations` component** — deferred until a business relation exists that no Pipeline exercises. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §3.4

### Control plane, jobs, notifications, queries

- **P2** · **`AGT-ARTIFACT-1` — produce `AgentAskResult.artifact`** (the inverse pair: a live client consumer, no producer;
  decided 2026-09-10: BUILD): the draft skills (`component_draft`, `pipeline_author`, `query_author`, `projection_author`,
  `kpi_report_builder`) return their draft as the artifact the assistant UI already renders, so an answer is actionable
  rather than prose. → `okf/capabilities/assistant/assistant.md` §3
  **⚠ GROUNDED 2026-09-14 — the skeleton is CONFIRMED, the causal claim is WRONG, and the row understates the work.**
  - ✅ The field exists: `AgentAskResult(..., Map<String,Object> artifact)` (`AgentAskResult.java:21`), nullable raw map.
  - ✅ The client consumer is genuinely live, not aspirational: SSE `event: artifact` → `agent.service.ts:244`
    → `models.ts:610` → `assist-panel.component.ts:142` renders `<inspecto-a2ui-render>` (`.html:43`).
    The SSE relay `AgentRoutes.java:265` is already generic and needs no change.
  - ✅ No producer: `InspectoIntelligenceAgent.toResult` (`:698`) is the only construction site, and its own
    comment records that no eoiagent tool or session can emit an `INLINE_ARTIFACT` today. The only place
    `AnswerKind.INLINE_ARTIFACT` is constructed is a test (`InspectoIntelligenceAgentTest.java:199`).
  - ✅ All five skills exist **in this repo**, not upstream — `InspectoTools.java` at `:789` `component_draft`,
    `:834` `query_author`, `:934` `projection_author`, `:1075` `kpi_report_builder`, `:1449` `pipeline_author`.
  - 🔴 **"Return their draft as the artifact" does not describe a wiring change.** Those tools return
    `{kind:"query"|"expectation"|…, draft:{…}}`, and `parseArtifact` whitelists
    `ARTIFACT_KINDS = {text, kpi, chart, data-table}` (`InspectoIntelligenceAgent.java:74`) — so a draft
    payload is **dropped as an unknown kind** even if it reached the seam. ⇒ the row needs a **translation
    layer** from draft shape to a renderable artifact kind (or a new allowed kind), plus a producer step in
    `toResult` / the `askStream` override (`:431-448`). ⛔ Do not scope this as "populate a field".
  - ⚠ Not established: whether the external eoiagent SPI auto-promotes a tool return value into an
    `InlineArtifact`. That code is outside this repo. The in-repo evidence makes "no producer" safe regardless.
  ✅ **DECIDED 2026-09-14 — ADD A `draft` KIND.** The operator chose to widen `ARTIFACT_KINDS`
  (`InspectoIntelligenceAgent.java:74`) rather than translate drafts into an existing kind. Reason for the
  record: translating loses the draft's **structure**, and the structure is the whole point — a draft that
  arrives as `text` or `data-table` can be read but never accepted, so "apply this draft" could not be
  built later without redoing this. ⚠ The accepted cost: **the SPA's `a2ui` renderer must handle the new
  kind**, so this row is no longer server-only — it is a producer step in `toResult` / the `askStream`
  override (`:431-448`), the kind whitelist, **and** UI work. ⛔ Still not "populate a field"; the
  grounding above stands unchanged.
- **P2** · **API v1** — adopt the cursor-pagination seam on further list families as demanded (4 adopters live); adopt `ETags.respond` on further singleton reads as demanded; Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed). → `okf/backend/control-plane/api-v1.md`
- **P2** · **Bundle / Exchange** — `requires` present-but-different classification; per-editor "load as draft" import — design first, likely multi-session (`BundleTransferService.write` commits straight through; no generic draft seam). ⛔ Do not fake it with a cross-kind `enabled:false` stamp. → `okf/backend/control-plane/exchange-sharing.md`
- **P2** · **Notifications** — D8 residuals: soft-bounce retry scheduling (distinction recorded, nothing retries); SES/SNS adapter (needs SNS subscription confirmation + a cert-chain fetch from a validated `amazonaws.com` URL — ⚠ outbound fetch from an unauthenticated callback path deserves its own review); GeoIP; auth-gated per-user prefs / security triggers. (Auto-disable policy is a §1 decision.) → `okf/backend/control-plane/events-metrics.md`

- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the first **space-to-space comparison request**. ⚠ The trigger governs the
  comparison half only — predictive maintenance stays deferred to AGT-5 regardless. · **Job framework** — Maintenance COULD tier: space-to-space comparison; predictive maintenance (AGT-5 territory) deliberately deferred. → `okf/backend/control-plane/jobs.md`
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the first **non-engineer** authors a `findings-spec`. TOON-through-CRUD is
  workable for someone who reads the schema; for an analyst the editor is the difference between usable and not.
  · **D6 spec-authoring UI** — a matrix/editor for `findings-spec`; today authored as TOON through generic `/components` CRUD. Nothing broken without it. → `okf/frontend/features/objects.md`
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the **first cross-Space consequence** — a Signal in one Space
  that must cause something in another, which none of today's `/apply`-only machinery can express. ⚠ All three
  items below are speculative extensions of a backbone that works today, so one gate covers them.
  · **Signal / Decision networks** — optional S8 (connector-direct emission + cross-space controller); a general event-triggered consequence policy gate (still `/apply`-only); RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet). → `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md`
- **P3** · **Queries / BI** — `graph`/`spatial`/`search`/`api` QueryTypes; more `$`-resolvers. (DuckDB `spatial` extension itself: zero demand re-verified 2026-08-26 — do not re-open on speculation.) → `okf/backend/control-plane/queries.md`
- **P3** · **EXPORT-1 outbound object-storage export (S3 / HDFS)** — sequence of record: operator `aws s3 sync`/rclone of `data/<store>/database/` first (zero code); build the push post-action (outbound mirror of the connector SPI reusing `AwsSigV4`) only on demand; HDFS only via an S3-compatible gateway — ⛔ never `hadoop-client`. → `okf/backend/engine/object-storage-export.md`
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the first install where hand-edited policy TOON goes wrong. ⚠ The
  read-only Policies tab and the "why denied?" endpoint already make such a mistake diagnosable, which is what
  bounds the cost of waiting. · **Security: policy-authoring UX** — a matrix/create editor beyond hand-authored TOON (seed visibility, "why denied?" endpoint and read-only Policies tab already shipped). Non-blocking. → `okf/backend/editions/auth-security.md`
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the first operator who promotes two Breaks on one key and
  finds only one Incident. · **`BREAK-DEDUPE-GRAIN-1` — the server dedupes promotion on `key` alone, but a
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
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** an **SLA commitment names MTTD**. Until then it is a metric nobody reads,
  and the "first signal" instant is a modelling choice better made against a real definition. ⚠ Re-grounded
  2026-09-13: MTTR's anchor DID ship (`ATTR_RESOLVED_AT`) and does **not** confer one on MTTD — that is a
  self-stamped transition, while MTTD needs a *pre-Incident* instant; `detectedAt`/`firstSignal` have zero hits.
  · **`INCIDENT-KPI-MTTD-1` — MTTD still has no anchor.** Split out of `INCIDENT-KPI-MTTR-1` when its
  MTTR half shipped 2026-09-11. Detection time needs a *first-signal* instant and nothing records one on an
  Incident. The proposed anchor stands: **earliest Signal at the Incident's `causationId` root → the
  Incident's `createdAt`**. ⚠ Adopting it means reading the event store from the analytics path, a seam
  `ObjectService` does not have — it emits through `EventLog` but never queries. ⛔ Do **not** publish a
  placeholder meanwhile: an undefined KPI is indistinguishable from a measured one once it is on a
  dashboard, which is the failure the MTTR half was filed against. → `okf/capabilities/incidents/incidents.md`
- **P3** · ✅ **TRIGGER (operator, 2026-09-13):** the first **false-stale complaint** at pipeline granularity. The shipped
  badge is correct but coarse; coarse-but-correct is only imprecise, so it waits until someone is actually misled.
  · **`STALE-TILES-PRECISION-1` — narrow the stale badge below pipeline granularity.** ✅ The badge
  SHIPPED 2026-09-11 on a **pipeline** anchor (operator's call over two larger options). ⚠ The residual is
  the granularity that follows: a disruption marks **every** Dataset fed by that pipeline, not only the
  rows or column that gapped. Narrowing it is a BACKEND change, not a resolver change — the emitters must
  carry a store/Dataset identity on the Signal's `subject` `Ref` (the `Ref` vocabulary already reserves a
  `tiles` relation, unused). ⛔ Also unbuilt and deliberately so: **quarantine emits no Signal at all**, so
  a quarantined file is only caught via the `FILE_QUARANTINED` event. Take this only if operators report
  the over-approximation as noise — it is a correct-but-wide badge, not a wrong one.
  → `okf/capabilities/observability/observability.md` §3.1
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

- **P2** · **D8-SUPPRESS-1** — per-recipient suppression list (TTL for hard bounces, permanent for complaints). ✅ **Its gate — a DB-backed `DeliveryReceiptStore` — was DISCHARGED 2026-09-07** (the same day it was verified still holding): `DbDeliveryReceiptStore` shipped in `inspecto-engine/.../notify/`, wired `SpaceRoot.deliveryReceiptsDbUrl` → `OperationalDb.Family.DELIVERY_RECEIPTS` → `ServiceStores.openDeliveryReceiptStore` → `CollectorService`, behind `-Ddelivery.receipts.backend`. ⛔ Default `none` — an absent receipt DB is the shipped behaviour, not degraded correctness, and a default-ON family creates a DB file in the CWD for every Personal install. Schema + rationale: `okf/backend/engine/db-layer.md` §3.12. ✅ **The suppression policy SHIPPED the same day** — `SuppressionList` (complaint ⇒ permanent · hard bounce ⇒ `-Dnotify.suppression.bounce.ttl`, default `P30D` · ⛔ soft bounce never · off via `-Dnotify.suppression=off`), consulted in `NotificationService`'s ChannelConfig delivery loop. 🔴 It **arms only over a durable store** (`DeliveryReceiptStore.durable()`) and WARNs when a TTL is set over one that cannot honour it — suppressing nothing while appearing configured is the `ConservationCheck` trap. ✅ **`GET/DELETE /notifications/suppressions` SHIPPED too** — the 2026-09-06 decision is fully discharged. `DELETE` records an **override** (operator call 2026-09-07) that forgives history up to its timestamp; a later bounce re-suppresses on its own, and the receipts survive as the audit trail. ⛔ Rejected: pruning the target's receipts — audit loss AND a permanent mask over a dead address. **What remains on D8: soft-bounce retry scheduling and the SES/SNS adapter** (the latter needs subscription confirmation + an outbound cert fetch from a callback path — its own review). Covers EDITIONS `CP-15` (Standard+). → `okf/backend/control-plane/events-metrics.md` §Decision

- **P2** · **AGT-5 per-tool dry-run seam — GATE DISCHARGED 2026-09-08, now actionable.** This sat in §2 as externally gated on eoiagent shipping a per-tool `DryRunProvider`. **It has shipped**: `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunProvider.java`, `eoiagent-core/src/main/java/com/eoiagent/safety/DryRunResult.java`, and four per-tool dry-run tools, under an **Accepted** ADR-0008 enforcing approval + dry-run in the runtime (upstream `jotder/inspect-agent`, verified via the git-tree API 2026-09-08). ⚠ The gate was not "waiting" — it was **held shut by a broken check**: the `gh search code` probe it named returns 0 for every term in that repo, control included. **What this unblocks:** inspecto can now drop its parallel `AgentApprovals` previewer and consume the upstream per-tool seam on `PlatformBuilder`. ⛔ Still separately gated: `incident_explain` waits on the eoiagent **host** seam, and the local-models-only scope cut stands. → `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2
- **P2** · **Deployment topology gaps** — ~~GAP-3 service wrappers (SCR-3)~~ → own P1 row `DEPLOY-SERVICE-WRAPPER-1` (2026-09-11) · GAP-4 DuckDB `memory_limit` default · GAP-5 T15 surge admission · GAP-6 Vault/KMS (SEC-8) · GAP-10 bundle missing 13 archived docs (SCR-10). Phases 0–5 all unbuilt. *(Re-grounded 2026-09-08. The "(after §1 D1–D8 are signed)" gate is dropped — §1 records all 28 decided 2026-09-06, which §7 already flagged. **GAP-2 and GAP-8 were shipped work this row had inherited as open** and are struck: Enterprise is a real `package.ps1` flavour (EDG-01) and the Postgres driver rides the bundle as `postgresql.jar` (PG-1). ⚠ **GAP-4 verified STILL OPEN** — D11 shipped as a pair and only the concurrency half is on by default; `DuckDbUtil.memoryLimit(null)` is `null`, no `scheduler.toon` ships, and the committed corpus sets `memory_limit: ""`. Do not close it off the D11 row.)* → `archived-documents/plans-archive/deployment-topology-plan.md` §11
- **P3** · **Postgres multi-user** — ⛔ **PARKED by §6** until a multi-operator install exists; the old "(after the §1 decision)" heading outlived its decision, which was *park it*. Kept for the shape when it lifts: P1 pool behind `JdbcDrivers` (each `Db*Store` holds ONE `synchronized` connection); P2 replace `browseConnection()` (F2: it hands out the store's long-lived connection, a pool has no such thing); P3 **schema**-per-space URL wiring (NOT db-per-space); P4 `CaseStore` interface + PG impl (JSONL ring today); `PostgresStateStoreTest` over the three uncovered stores + a concurrency test. Keep events on Parquet. ⚠ Not the same work as `OperationalDb`/PG-1 (shipped). → `archived-documents/plans-archive/postgres-multi-user-plan.md` §5–6


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
- **P2** · **`SPEC-GREENCELL-1` — a board cell is green over something absent or bounded.** 🔴 **Recounted
  2026-09-15: TWO live instances, not five** — three of the original five are now false, and a row about
  unverified claims had carried its own for days.
  **Still live:** **intake caps** are advertised by `JOB-04` as ✅✅✅ with an empty notes cell
  (`EDITIONS.md:164`) and are **off by default** (`IntakeGovernor.java:64-68`, `UNBOUNDED = 0`); and
  `BI-4`/`BI-6`/`BI-7` promise Standard+ over code that is ungated on Personal.
  ⚠ **The `BI-*` instance is a DOC defect, not a security hole** — corrected 2026-09-15. `ShareRoutes:46`
  does gate on `canAuthorWorkbench`, but `ApiContext.requireCapability:168-171` enforces only when a
  `Subject` is attached, and its javadoc states the intent: *"A no-op on Personal edition — no
  `Authenticator` is ever present there… every route stays open, unchanged."* Personal is auth-free **by
  design**; the defect is the edition table marking those rows ✅ for it. ⛔ Do not "fix" this in
  `ShareRoutes`.
  **Refuted and struck 2026-09-15:** ⛔ ~~no Standard artifact is built~~ — `release.yml:150-152` packages
  Standard `-Sign` and **fails the release** if the zip carries `inspecto-policy` (`:154-165`);
  ⛔ ~~the trimmed runtime ships in nothing / every release passes `-NoRuntime`~~ — `-NoRuntime` survives in
  `release.yml` only inside the comment explaining its **removal** (`:111-119`), and that cell is no longer
  green anyway (`EDITIONS.md:350` is 🟡🟡🟡, citing this row); and replay was fixed 2026-09-09 (`2cd1661b`).
  → the rule this needs is one line: **a green cell must name the evidence that makes it true**, and a cell
  whose evidence is "the script can do it" is not describing the bundle a customer receives.
- **P2** · **`SPEC-DEPLOY-ROWS-1` — THIRTEEN deployment items have no board row.** ⚠ **This row has now been
  recounted THREE times — fourteen → fifteen (2026-09-09) → thirteen (2026-09-15).** A row whose whole
  subject is items nobody counted is exactly the row that must be re-derived, never carried forward.
  🔴 **2026-09-09: this row said fourteen and its own enumeration missed `SCR-4` (the nginx/IIS proxy + TLS reference configs — TLS, HSTS, static-UI gzip, and restricting `/metrics` and `/health/details` to the monitoring network). A row that exists to catch items with no home had an item with no home.** Its only statement anywhere is its acceptance line in `okf/capabilities/editions/editions.md` §5.2. Thirteen from the deployment
  plan (the preflight tool, the acceptance script, the off-site backup copy, upgrade/rollback automation, the
  sizing table, the disaster-recovery pack, phases 0–5, the platform list, the government-variant refusal)
  ~~plus two board cells with no home at all (distributed scheduler coordination, the shared object store)~~
  🔴 **Recounted 2026-09-15: THIRTEEN, not fifteen — both board cells are now homed** and are struck.
  *Distributed scheduler coordination* is tracked under the signed scale-out plan (§2 E1, and phase B's
  design constraint) and is **built** — `RunLease.java:39`; *the shared object store* is **D4 SIGNED**
  (option (ii), object store + DuckLake catalog on Postgres). ⚠ The thirteen plan items are unchanged, and
  re-verified 2026-09-15: every occurrence of `preflight`, `standby`, `disaster recovery`, `off-site`,
  `rollback`, `sizing`, `government` and `SCR-4` in this file is **inside this row's own text** — so the
  core claim still holds, and `SCR-4` is still the sole item whose only statement anywhere is its
  acceptance line. ⚠ The words `preflight`,
  `standby` and `disaster recovery` appear **nowhere** in this file. Their only durable home today is
  `okf/capabilities/editions/editions.md` §3.9–§3.13, which is why that spec had to be written before the
  plan could move — ✅ **and it did move, 2026-09-09**, with the six tables the narrative could not carry
  now in that spec's §3.14 (sizing, failure→tier, the signed RPO/RTO SLOs, the nine remaining preflight
  rows, VER-1…VER-12, and the phase sequencing incl. the T4 promote order).
  → `okf/capabilities/editions/editions.md` §3.14 · `archived-documents/plans-archive/deployment-topology-plan.md` §11.
## 4. Engineering / tech-debt

- **P1** · 🔴 **`CONSIGNMENT-OUTPUTS-NULLRUN-1` — a UNIQUE key was added against its own code's ⛔, and
  the graph lane silently escapes it.** Filed 2026-09-15 by grounding `Consignment ELT`, whose claim that
  this was *"settled, unbuilt"* is refuted — it shipped, and shipped early.
  `PartitionSinkWriter`'s six-arg constructor carries the rule verbatim: *"it is why the registry still
  carries NO unique key: NULL ≠ NULL in a UNIQUE constraint on both DuckDB and Postgres, so a single
  remaining null path would silently exempt its rows. **⛔ Do not add the constraint until every path
  supplies this.**"* The constraint was added anyway on 2026-09-13 —
  `DbConsignmentOutputStore.java:122` `UNIQUE (consignment_id, path, run_id)` with
  `ON CONFLICT … DO UPDATE` at `:225` — on the stated basis that *"every production path has supplied one
  since slice 3"* (`:57-58`).
  🔴 **That basis is FALSE.** `ConsignmentGraphRunner.java:83` still calls the **4-arg**
  `PartitionSinkWriter` constructor, which threads `runId = null`; only `PipelineJobRunner.java:316` supplies
  a real one. And the graph lane is reachable on the **default** setting — `-Dingest.lane=auto`
  (`ConsignmentIngestStrategy.java:168`) admits it whenever an authored route engages or
  `graphLaneCarries(cfg)` holds. ⇒ Graph-lane rows are exempt from the key, so a re-run appends a duplicate
  row instead of updating the `row_count`, in the one table the §11.3 output registry is meant to be
  authoritative for. ⚠ Two javadocs in the same subsystem **directly contradict each other** today; whichever
  fix lands must correct the loser, not leave both standing.
  ✅ **DECIDED 2026-09-15 (operator): thread a real run id into the graph lane** — that is the end state
  both javadocs already assume, and it removes the exemption at its source. ⛔ Not by dropping the
  constraint, and ⛔ not by `NOT NULL`, which `:59-62` shows is strictly worse (`record` is fail-open, so a
  violation would demote a **landed** file to a WARN, and a pre-slice-3 registry could not be rebuilt).
  **First step: find whether a Run identity is in scope at `ConsignmentGraphRunner:83`** — the run-model
  plan's slice 3 is where that seam was supposed to land. ⚠ Verify by RE-RUN, not by unit test: the
  exemption is invisible to any test that writes one row once.
  → `okf/backend/engine/db-layer.md` §3.9 · `archived-documents/plans-archive/run-model-plan.md`

- **P2** · **`ENRICH-SILENT-FULL-RECOMPUTE-1` — an incremental recompute silently becomes a FULL one.**
  Filed 2026-09-15 by grounding `Onboarding ↔ Pipeline unification W4`, which carried this as a *design
  note* (*"never silently convert one into the other"*) when it is **a live defect already doing exactly
  that**. `EnrichmentService.doRecompute:194` decides the mode with
  `boolean full = (filter == null || filter.isEmpty())`, and `toFilter:307-319` drops any partition path
  whose keys are not in `job.input().partitions()` (`:316`), contributing nothing when the map ends empty
  (`:319`). ⇒ An **event-triggered incremental** recompute whose partition columns do not match the job's
  declared ones produces an empty filter and runs a **full-window** recompute — dispatched at `:178-180`,
  with nothing logged to say the mode changed.
  **Fix: distinguish "no partitions requested" from "requested partitions matched nothing", and refuse the
  second.** ⛔ Do not make it fall back to full — silently widening the blast radius of a triggered job is
  the defect, not the remedy. ⚠ The repo already has the idiom to copy: `DedupScope` refuses a windowed
  dedup that has no ledger rather than quietly running unwindowed.
  → `okf/backend/control-plane/onboarding-authoring.md`

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

- **P2** · **`OPENAPI-GEN-1` — generate the OpenAPI path/method skeleton from the route table** (⛔ decided 2026-09-10
  over "exemplar coverage, deliberately" and "document the rest by hand"). `openapi-v1.json` documents 24 operations
  against 266 live registrations (9.0 %, measured and ratcheted by `ApiContractTest`). Derive every path + method from
  the registrations so structural coverage is 100 % and a route can never be undocumented; the hand-written exemplars
  keep the request/response schemas. → `okf/capabilities/control-api/control-api.md` §2 `API-2` / §5

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

- **P2** · **`COLLECTOR-DATASET-UNPROVEN-1` — the `dataset` collector has never been driven end to end,
  because nothing committed declares it.** Filed 2026-09-15, recovered from inside the closed
  `COLLECTOR-SPACE-ROOT-1` entry when it was deleted — it was live work with no row of its own.
  🔴 **`collector.dataset` appears in no pipeline under `spaces/` and none under `inspecto/examples/`.**
  `acquisition.md` §8.4 records only that every committed `collector.connector` is `local` (20
  occurrences), which is adjacent to this and does not state it. So the 2026-09-15 space-root fix to
  `DatasetCollectorConnectorFactory` was **verified by test, not by execution** — unlike its parent
  `MATERIALIZE-SPACE-ROOT-1`, which was proven on a live multi-Space run (202 → SUCCESS, 7 rows,
  Dataset registered). ⇒ Treat the connector's end-to-end behaviour as **unproven, exactly as it was
  before that change**.
  **Fix: author one committed `collector.dataset` pipeline and drive it on a live multi-Space server**,
  the same way the materialize job was proven. ⛔ Do not close this on a passing `SpaceConfigRootTest` —
  that suite is what already passes while the path stays unexercised. ⚠ This is an instance of the §6
  rule *"a capability with no committed example is a capability nobody has ever run"*.
  → `okf/capabilities/acquisition/acquisition.md` §8.4 · `okf/capabilities/data-plane/data-plane.md` §3.6

- **P2** · **`SPEC-JAVALANE-RATIO-1` — a stakeholder doc states a Java-lane throughput ratio that no
  measurement has ever produced.** Filed 2026-09-15. `stakeholders/COMPETITIVE_LANDSCAPE.md:102` bounds
  every headline figure with *"messy files fall to the Java lane at roughly a third of the native rate"*,
  citing `BACKLOG.md` §4 `JAVA-INGEST-APPENDER-SERIAL-1` — **a row that is now CLOSED**, so the claim
  cites a board entry rather than a measurement, and will shortly cite nothing at all.
  🔴 **"A third" matches neither side of the only run that exists.** Same harness, 6-core/12-thread
  laptop, 48 files × 250K rows × 100 cols, 24 batches, `processing.threads=12`: **before** the
  2026-09-14 checkpoint fix the Java lane did **5.2K rows/s against the native lane's 69K** — about a
  *thirteenth*; **after** it, 12 batches reached **34.5K rows/s**, which is the opposite error. ⚠ The two
  Java numbers are not at equal batch counts, so ⛔ **do not simply substitute "half"** — that would
  replace one unmeasured ratio with another, which is the whole defect.
  **Fix: one A/B at equal batch count and column width on the current tree, then state the measured
  ratio with its conditions** — `PerfDeepDiveBenchmark#concurrencyAndAutoDerive` reproduces it via
  `-Dbench.threads` / `-Dbench.cols` / `-Dbench.engine`. ⚠ The result does **not** go in
  `performance.md` — the operator instructed 2026-09-14 that these measurements are not recorded there;
  the landing place is the sentence itself. ⚠ Single site — no other doc repeats the ratio.
  → `docs/stakeholders/COMPETITIVE_LANDSCAPE.md` §1.3 · `okf/backend/build-run/performance.md`

- **P2** · **`SPEC-COUNTS-1` — eight facts, each counted two to six ways, and the narrative doc is wrong every
  time.** Measured 2026-09-09: builtin node types **30** (docs said 20/28/20/29 — five ways); parser frontends
  **six ways** (3/5/6/7/9/10 across five pages and the user guide); maintenance tasks **19+4** (docs said
  4/16/13, and the *served descriptor* was one of the wrong ones — fixed `f0e4dee2`); job types **12** (docs
  said 4/9/4/10); transform functions **23** (24, and "~20" in the same file); processors **119** (121); the
  dependency count **95 across 25 modules** generated against **94** in four prose sites; and the staged-jar
  set stated in **eight** places with five wrong. ⚠ In every case the *generated or catalogued* artifact
  was right. **The fix is to cite the generated file, and to add a counting guard only where no generated
  artifact exists.**
  ✅ **CLASS CLOSED 2026-09-09 for every count a contract owns** — `tools/check-doc-counts.mjs`, wired
  into `ci.yml` + `.githooks/pre-push`, falsified in four directions. Twenty statements across eleven
  documents now carry a `<!--count:ID-->` marker and are **derived** from the owning contract at run
  time: processors **119** (8 sites), step types **16** (5), node types **30** (3, from the
  `BuiltinNodeType` enum — no contract owns the roster), SQL mapping functions **23** (2), processor
  families **8**, and node types carrying an attribute spec **11**.
  🔴 **The ambiguity was the root cause, not the arithmetic.** "Node types" denoted THREE sets — 30 in
  the enum, 11 in `node-attributes.contract.json`, 16 in `step-types.contract.json` — so a single
  number could not be right; that distinction is now stated in `pipeline-editor.md`. Likewise
  "transform functions" denotes two unrelated registries (23 SQL mapping vs 30 ASN vendor), which is
  why the marker id is `sql-mapping-functions` and not the noun.
  ⛔ **`job types` and `maintenance tasks` are deliberately NOT guarded**, and this is a measurement
  result: both are assembled from a built-in list plus `ServiceLoader` discovery, so both totals are
  **edition-dependent** (job types 10 on Personal, 12 with `inspecto-ops`; maintenance 20 built-in ids
  across 19 switch arms plus 4 contributed). "The count" does not exist until the classpath is fixed,
  so a guard asserting one number would assert a falsehood in the name of ending wrong counts. A doc
  stating either must say which shape it means — a writing rule, not something a guard can settle.
  ✅ **Parser frontends CLOSED 2026-09-09** — the "six ways" were FOUR sets sharing one noun: **10**<!--count:parsing-frontend-tokens-->
  `parsing.frontend` tokens (`PipelineConfigParser.FRONTENDS`), **6**<!--count:builtin-parsers--> DuckDB-native built-ins
  (`BuiltinParsers.IDS`), **7**<!--count:parser-node-types--> `parser.*` node types (`step-types.contract.json`), and three
  byte→row *mechanisms* — a prose taxonomy with no owner in code, so written as *mechanisms* and not guarded.
  "Eight formats" (tokens minus two aliases) is not derivable without mirroring the alias pairs, so prose binds
  it to the marked ten. → `okf/capabilities/ingestion/ingestion.md` §3.3 / §7.
  ⚠ Still hand-typed and unguarded, for want of a generated artifact: the dependency count and the
  staged-jar set. → `tools/check-doc-counts.mjs` · the owning specs' §2
  tables, which carry the measured number.
- **P3** · **`SPEC-DEADSEAM-1` — four declared seams with no implementation or no caller.**
  `ExpressionProvider` has no registration in any module; `DatasetRelation.temporalColumn` has no caller; a
  vendor-transform plugin registers **30** legacy functions through a real seam (🔴 this said "~40" until 2026-09-09; counted from `LegacyVendorFunctions`' 30 `f.put(` registrations — ⚠ and note this is a DIFFERENT set from the 23 SQL mapping functions, which is why `check-doc-counts.mjs` names its id `sql-mapping-functions` rather than the ambiguous noun) and **reaches no bundle and no
  document**; `AssistDialog` is dead code whose doc comment describes an unwired flow. Each needs a
  keep-or-delete verdict, not a build. ⛔ Demand-gated: do not "tidy" them without one, because at
  least one (the vendor plugin) may be deliberately operator-side.
## 5. Docs & hygiene

- **P2** · **`AIRGAP-S3-EXTENSIONS-1` — ✅ STAGING DONE 2026-09-14 (operator call); the LOADING half is open.**
  Filed 2026-09-14, measured against a live MinIO the same day. `$duckdbExtNames` in `inspecto/package.ps1`
  is `excel`, `ducklake`, `postgres_scanner` — **`httpfs` and `aws` are absent**, and scale-out phase C
  bullet 6 turns `dirs.database` into an `s3://` URI, which needs both. This is the SAME defect class as
  `AIRGAP-EXTENSIONS-CI-1`, closed hours earlier: a release that stages no extension fails only on the
  air-gapped host nobody tests on.
  🔴 **Why it will not be caught by a probe on a dev box.** A measured `COPY … TO 's3://…'` plus a read back
  BOTH SUCCEEDED here with **no `LOAD httpfs` statement at all** — DuckDB 1.5.2 AUTOLOADED it from the
  developer's own `~/.duckdb/extensions`, which this box has and a bundle does not. ⛔ So the local green is
  an artifact of the workstation, exactly the trap `AIRGAP-EXTENSIONS-CI-1` recorded one layer down.
  ⚠ **Two corrections to assumptions this finding overturns**, both worth keeping:
  (a) the SQL guard's refusal of `LOAD httpfs` (pinned by `SqlGuardTest`) does **not** keep httpfs out —
  autoload emits no statement for a guard to see, so the real control is `enable_external_access`, not the
  guard. (b) `httpfs` was therefore never the phase C blocker it looked like; **staging it is.**
  ✅ **`httpfs` and `aws` added to `$duckdbExtNames` 2026-09-14** on the operator's call; the fetch guard
  reads the list out of `package.ps1` and now verifies 10 files (5 × 2 platforms), every one published.
  ✅ **SIZE SETTLED BY BUILDING IT, 2026-09-14** — `package.ps1 -Edition Enterprise` run to completion,
  **real exit code 0**, every step green including SBOM, both jlink runtimes and the boot smoke
  (`the staged Enterprise bundle boots and answers /health`). 🔴 **The estimate this row carried was
  wrong in BOTH directions and the larger error was the one that mattered.**
  - ⛔ **The "+104 MB" was ~3× the real shipped cost.** It doubled the *Windows* raw sizes across both
    platforms; Linux `httpfs` is **19.92 MB**, not 28. Raw for the two new names is **93.32 MB** across
    both platforms, not 104. But a bundle is a **ZIP**, and `.duckdb_extension` binaries deflate to
    ~0.34–0.38 — measured from the built zip's own entry table, not assumed. ⇒ the addition costs
    **+33.5 MB compressed in every zip** (win `httpfs` 10.26 + `aws` 8.60; linux 6.84 + 7.77).
    ⚠ **Never quote a raw staged size as a bundle cost** — that is the whole 3×.
  - Per-platform totals for all five, raw → zipped: **windows 121.91 → 45.26 MB**,
    **linux 125.95 → 43.25 MB**. A release stages both, so each zip carries **~88.5 MB** of extensions.
  - Measured zips from this run: `inspecto-deploy.zip` **238.86 MB**, `inspecto-deploy-linux.zip`
    **241.82 MB** — ⚠ **both carrying the WINDOWS extensions only**, because a desk build stages what the
    local cache holds (see the next bullet). A release build, which stages both platforms, lands at
    **≈282 / ≈285 MB**.
  - ⚠ **A desk build stages only the platforms the local `~/.duckdb/extensions` cache happens to hold**,
    warns per missing file, and still exits 0 — by design, so a developer without a cache can build.
    ⛔ **This is NOT the released shape and must not be read as one:** all three `release.yml` jobs pass
    `-RequireExtensions` after a per-platform fetch step, which turns each of those warnings into a
    refusal. That gate is `AIRGAP-EXTENSIONS-CI-1`, closed 2026-09-14, and it held here.
  - 🔴 **Found while measuring — every zip carries the OTHER platform's binaries, which its own launcher
    never probes.** Both platforms stage into the same bundle dir and both zips are cut from it, so
    `inspecto-deploy-linux.zip` ships `duckdb-extensions/windows_amd64/` while `run.sh` looks only at
    `duckdb-extensions/linux_amd64/`. `package.ps1` calls this "harmless — like run.sh sitting unused in
    the Windows zip", and at 27 MB it was. **It is now ~43–45 MB of unreachable payload per zip, ~16 % of
    the download**, and `httpfs`+`aws` are what moved it. ⇒ Cutting each zip against its own platform dir
    is the cheapest ~45 MB on the board, but it is a **packaging change, not a measurement** — filed, not
    taken, see `AIRGAP-CROSSPLAT-DEADWEIGHT-1` below.
  ⚠ **`httpfs` ALONE is sufficient for an S3-compatible endpoint with EXPLICIT credentials** (measured
  against MinIO with `aws` absent); `aws` buys only the AWS credential CHAIN — profiles, environment,
  IMDS. ⇒ if bundle size ever binds, drop `aws` first and nothing that passes explicit keys notices; it
  is **16.37 MB compressed per bundle** (8.60 windows + 7.77 linux), now measured rather than estimated.
  🔴 **WHAT REMAINS, and it is the half that makes staging mean anything.** A flat
  `duckdb-extensions/<plat>/<name>.duckdb_extension` is reachable ONLY by `DuckDbExtension`'s explicit
  `LOAD '<file>'`. DuckDB's AUTOLOAD ignores `-Dduckdb.extension.dir` and reads its own
  `extension_directory`, which this product never sets. Measured four ways: autoload over an empty dir
  FAILS (correct), autoload over the FLAT staged dir FAILS, autoload over a `<version>/<platform>` tree
  WORKS, explicit `LOAD '<flat file>'` WORKS. ⇒ `httpfs`/`aws` are staged but INERT until phase C's
  `s3://` seam calls `DuckDbExtension.ensureLoaded` by name.
  ✅ **The sibling defect `AIRGAP-PGSCANNER-LOAD-1` is CLOSED 2026-09-14** — `postgres_scanner` was
  shipped in exactly that inert state and is now loaded by name at both attach sites, so the pattern
  `httpfs` must follow already exists: `LakehouseCatalog.backendExtension` +
  `DuckDbExtension.ensureLoaded`. → `superpower/enterprise-scale-out-plan.md` §5.4 bullet 6
  🔴 **Regrounded 2026-09-14 when starting bullet 6 was proposed: there is no `s3://` seam to attach that
  `ensureLoaded` call to yet, and bullet 6 cannot start without bullet 1.** `PartitionWriter` writes via
  DuckDB `COPY` (`:192`), which already speaks S3 — measured end to end against the live MinIO, `httpfs`
  autoloading and `aws` never loading. But the staging dir, the `Files.walk` cleanup and the two-hop
  `ATOMIC_MOVE` reveal around it (`:174,176-177,201,212,229-236`) are `java.nio.file` with no object-store
  equivalent and no strategy seam, `dirs.database` has ~10 further `Path.of` consumers outside that class,
  and **no config surface carries an endpoint, key or region** (the probe needed five `SET s3_*`
  statements). ⇒ three pieces, and only the first is bullet 6's own.
  ✅ **What shipped instead, ahead of all of it: a fail-closed URI refusal.** 🔴 The status quo was worse
  than unsupported — `Paths.get("s3://bucket/data")` **throws on Windows and does not on Linux**, where it
  yields `/s3:/bucket/data`, a local directory named `s3:` under the CWD that `PathJail.contains` then
  judges confidently and wrongly. An operator authoring that value got a refusal on the box they probe from
  and silent local writes on the box it ships to. `PathJail.isUri` is now the one definition, enforced by
  both the jail and the 422 write gate, mutation-verified in both directions. ⚠ **Dispatch on it when
  bullets 1 and 6 land; do not delete it** — a bucket URI is not containable by `Path` comparison.

- **P1** · 🔴 **`ROUTE-UNGATED-DEFAULT-1` — an unlisted route is OPEN, not locked down; 75 mutating routes
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

- **P2** · **`DUCKLE-C3-DEAD-PROPERTY-1` — a config key no component reads must FAIL validation.** Adopted
  by the operator 2026-09-15 from duckle §1 C3. Stable error code + near-name suggestion (no suggestion
  when nothing is close); strict at validate, warning at run; `x-` keys round-trip untouched; and the
  accepted-names doc is **generated from the same map the checker enforces**, so it cannot drift.
  🔴 **Strongest case of the eight adopted**: `PROJECT_NOTES` records several past **silent config-loss**
  defects that are exactly this class, and today's behaviour is inconsistent three ways — `ComponentStore`
  refuses unknown keys, `RecipeCompiler`'s own comment admits others "stay", `ArgumentDeriver` silently
  drops. ⇒ the value is turning a silent loss into a refusal. → `ConfigSafetyValidator` · node attribute
  specs / `step-types.contract.json`

- **P2** · **`DUCKLE-C9-WATCHER-NOT-A-RUN-1` — a polling session is not a Run.** Adopted 2026-09-15 from
  duckle §1 C9. A polling session gets its own identity (`lastPollAt`, `pollCount`, `lastError`); **only a
  poll that moves rows or fails mints a Run**, naming the session as parent; `pollCount − runCount` is
  quiet time; a killed watcher reconciles to `interrupted` on next start, like a receipt.
  ⚠ The grounding called this "cheap to separate" — the collector loop currently conflates polls and runs,
  which makes run counts misleading in exactly the place operators read them. → Collector / Consignment
  scheduler

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

- **P3** · **`DUCKLE-C8-BASELINE-EXPECTATION-1` — a baseline QA Expectation kind.** Adopted 2026-09-15 from
  duckle §1 C8. Profile the current input against the **median of the last N *accepted* profiles** (row
  count; per column null count/rate, distinct, min, max, mean); limits in either direction, % or absolute;
  `groupBy` + `requireExistingGroups` catches a missing partition when totals look normal; a profile is
  **accepted only if the whole run succeeds**; explicit `accept`/`clear` ops audited with the replaced
  value; **a refused run still records its profile**.
  ⚠ Largest of the adopted set — a new Expectation kind *plus* profile storage *plus* accept/clear ops.
  `FileSequenceGaps` is the nearest cousin to model the kind on. → Expectation kinds

- **P2** · **`DUCKLE-C4-PARAM-PROVENANCE-1` — record where a parameter value came from, and what it
  overrode.** Adopted 2026-09-15 from duckle §1 C4.
  🔴 **Regrounded 2026-09-15 — this row's `secret` claim is REFUTED and its "cheapest of the eight" ranking
  is wrong.** It says *"It needs a `secret` ParamType, which does not exist"*: `ParameterDecl.java:30-38`
  already carries a `secret` **boolean** — orthogonal to type, which is the better design — with a working
  masker at `JobRoutes.maskSecrets:207-222`. ⚠ **The real gap is that masking is applied to the job-detail
  GET only**: `JobService.java:1236` logs the fully resolved **unmasked** map, and
  `ParameterResolver.itemViolation:117-134` embeds raw values in rejection messages. That is a leak on two
  paths, and it is the part worth doing first.
  ⚠ The provenance half is larger than "cheap": the winning layer **is** computed in
  `ParameterResolver.value():160-192` and then **discarded** — `Resolution` is a plain map — and there is no
  receipt to attach it to (`JobRun` carries no params field). ⛔ Secret masking is in scope; **owner-routed
  anything is not** — see the shared ownership blocker on `DUCKLE-C1-DATASET-FRESHNESS-1`.
  ✅ The row's one confirmed claim: `ParameterResolver.resolve` really is the single boundary — exactly two
  production callers (`JobService.java:1209`, `PackTestHarness.java:162`). Two surfaces binding one parameter: **later wins** (a
  documented rule, not an emergent one), and the receipt records `{source, overrode:[…]}`; only a
  *differing* value counts as an override; **`secret` is a declared type replaced with `***` in history and
  never dropped**, so "was a token supplied?" stays answerable; all problems reported at once with stable
  codes (`param:unknown`, `param:missing`); undeclared names **refused, not ignored**.
  ✅ **Cheapest real win of the eight** — the parameter contract already shipped and
  `ParameterResolver.resolve` is the single boundary every surface funnels through (confirmed 2026-09-15
  by duckle S8), so this is additive at one seam. ⚠ It needs a `secret` ParamType, which does not exist —
  the same missing type the scale-out credentials decision ran into. → Job parameter contract · Run receipt

- **P3** · **`DUCKLE-C10-ADMISSION-POOLS-1` — named execution pools are ADMISSION ONLY.** Adopted
  2026-09-15 from duckle §1 C10. A pool answers "may this start now" and **never widens thread or memory
  caps**; a Pipeline may *choose* a pool but never define one the server lacks (unknown ⇒ `default`); a
  queued run gets a durable id **immediately** with `queueReason`, becoming `running` with `queueMs`; a
  supervisor takes **no slot** — holding one while waiting for a child needing the same pool deadlocks;
  metric = free permits per pool.
  ⚠ **Adopt the RULE SET now even if the feature waits**: it is a design constraint on scale-out phase B,
  whose `RunLease` (fenced db lease) is already the seam. The deadlock rule in particular is cheap to
  honour up front and expensive to retrofit. → `superpower/enterprise-scale-out-plan.md` phase B

- **P3** · **`DUCKLE-C2-RUN-DIFF-1` — diff two Runs from recorded facts, with rule-derived explanations.**
  Adopted 2026-09-15 from duckle §1 C2. Compare two Run receipts **by kind** (code, runtime, invocation,
  inputs, execution, output); **every explanation line traces to a listed difference** — no generated
  prose; "not compared" is stated explicitly; **absent is not zero** (a run that died at node 2 has no
  counts after it); secrets compared as `***` / digest.
  ⚠ Receipts, ledgers and provenance rows already exist (`RunArtifactStore`), so the work is the *surface*
  and the *rules*, not the data. The agent diagnosers (`HeuristicDiagnoser`, `ModelDiagnoser`) are the
  natural consumers. ⛔ The "no generated prose" constraint is the point — do not implement it as a model
  summarising two receipts. → Run ledger

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

- **P2** · 🔴 **`DATASET-SELF-TRIGGER-1` — a pipeline can trigger itself through `on: dataset`, and the
  javadoc says it cannot.** Filed 2026-09-15 from duckle candidate S3 ("no self-subscription").
  `PipelineScheduler.onUpstreamCommit` has an explicit self-loop guard (`:445`); **`onDatasetWrite`
  (`:469`) has none**, and its javadoc (`:465-467`) states one is unnecessary because *"the producer is a
  Dataset write (a job/materialize), never the triggered pipeline's own commit."*
  ⛔ **That claim is false for the consignment path**: `ConsignmentProcessJobType:412` emits
  `DatasetWriteSignal.emit(store, n, processorId)` **from inside a pipeline's own run**. A pipeline whose
  processor writes store `S` and declares `{on: dataset, from: datasets/S}` re-triggers itself —
  coalesced, so it degrades to a hot loop rather than a stack overflow.
  🔴 **DO NOT ship the obvious one-line guard — it CANNOT FIRE.** The natural fix (pass `producer` through
  and skip when it equals the pipeline name) fails precisely on the case that needs it, because
  **`producer` is not a pipeline name and is not even consistent**: `MaterializeTask:132` passes
  `cfg.name()` (a JOB name) while `ConsignmentProcessJobType:412` passes `chain.get(i)` (a **processor
  component id**). ⚠ `CollectorService:1026` also drops `producer` entirely before calling
  `onDatasetWrite`, so today the value never even reaches the scheduler. ⇒ the real work is **deciding
  what `producer` identifies** and populating it consistently; the guard is downstream of that.
  → `PipelineScheduler.java:469` · `CollectorService.java:1026` · `ConsignmentProcessJobType.java:412`

- **P2** · **`LEDGER-PRUNE-EATS-RESUME-STATE-1` — retention deletes resume position, not just history.**
  Filed 2026-09-15 from duckle candidate S1 ("saved state — watermarks, resume positions — is NEVER
  touched by retention"). `AcquisitionLedger.highWatermark()` is **derived from the fingerprints the
  ledger holds — there is no separate watermark column** (`AcquisitionLedger.java:22-41`), and
  `ledger_prune` deletes those fingerprints (`LedgerPruneTask.java:11-27`). So pruning deletes the
  source's resume state; the task's own doc that a pruned file *"re-ingests as NEW"* is that loss, stated
  as deliberate forgetting. ⚠ Partial, not total: the row-level DB-export watermark
  (`DbAcquisitionLedger.java:200-221`) is a separate table and is **not** touched by `prune()`.
  ⇒ The call to make is whether "deliberate forgetting" should be **opt-in per category** rather than a
  consequence of an age-based sweep. → `AcquisitionLedger.java:64` · `LedgerPruneTask.java:11`

- **P3** · **`PRUNE-PREVIEW-DRIFT-1` — dry-run and the real prune are two different predicates.**
  Filed 2026-09-15 from duckle candidate S1 ("`--dry-run` and the real prune share one planning
  function"). ✅ The file-partition tasks already do it right — `ParquetEventStore.prune(before, dryRun)`
  (`:349`) and `PartitionPruneTask.run` (`:38-64`) walk one loop and branch only at the delete
  (`if (dryRun) continue;`). 🔴 The store-backed tasks do not: preview calls `countPrunable(cutoff)` and
  the act calls `prune(cutoff)`, **each with its own independently written WHERE clause**
  (`AcquisitionLedger.java:72-83`, `DbAcquisitionLedger.java:225-258`; same shape in `ReceiptPruneTask` /
  `NotificationPruneTask`) — two definitions of "what is prunable" that can drift.
  ⛔ Worst case found: **`DedupPruneTask`'s dry run does not count matching rows at all** — it reports the
  ledger's *total* size as the preview, which is not a plan. ⇒ unify each pair onto one predicate; the
  partition tasks are the template. → `AcquisitionLedger.java:72` · `DedupPruneTask.java`

- **P3** · **`AIRGAP-CROSSPLAT-DEADWEIGHT-1` — every zip ships the other platform's DuckDB extensions,
  ~45 MB it can never load.** Filed 2026-09-14, **measured from the built zips' own entry tables**, not
  estimated. `package.ps1` stages both platforms into one `$bundleDir` and cuts both zips from it, so
  `inspecto-deploy-linux.zip` contains `duckdb-extensions/windows_amd64/` while the `run.sh` it ships
  probes only `duckdb-extensions/linux_amd64/` (and the reverse for the Windows zip + `serve.bat`).
  The script states the trade — *"harmless — like run.sh sitting unused in the Windows zip"* — and **that
  judgement was made when the payload was three extensions**. It is now five: windows **45.26 MB**
  zipped, linux **43.25 MB** zipped, so a release bundle is **~16 % unreachable payload** and
  `httpfs`+`aws` contributed **33.5 MB** of it. ⇒ Cut each zip against its own platform directory. ⚠ Two
  things to check before touching it, because the current shape is deliberate: (a) the **launchers are
  cross-copied on purpose** (`run.sh` in the Windows zip), so "filter by platform" must filter the
  extension directory **only**, not sweep the launchers out with it; (b) the **boot smoke runs against
  `$bundleDir`, before zipping** — it will stay green whatever the zips contain, so this change needs its
  evidence from the zip entry tables, exactly as the measurement did. ⛔ Not urgent and not a correctness
  defect: every deployment loads what it needs today, it just downloads twice the extensions to do it.
  → `inspecto/package.ps1` step 6d · `AIRGAP-S3-EXTENSIONS-1` above

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


### Filed from the 17-spec consolidation, 2026-09-09 (Sprint 2)

- **P3** · **`SPEC-ORPHANPAGE-1` — shipped surfaces with no concept page.** 🔴 **The "roughly twenty" was
  wrong and contradicted the row's OWN enumeration, which sums to 33** (re-grounded 2026-09-13). A recount
  against `docs/okf/frontend/**` and the `resource:` front-matter map measured **≈44**: 17 routed admin panes,
  4 unrouted shell surfaces, 12 shared components, 11 shared libraries. ✅ **GATE (operator, 2026-09-13): confirm the ≈44 before building anything from it** — so the next person
  sizes from a real number instead of re-litigating the estimate. ⚠ Treat ≈44 as a measurement needing
  its own confirmation pass before anyone sizes the work from it — ⛔ but never carry "roughly twenty" forward
  again. The enumerated areas: panes in the shell tier (two of them the very rows that area owns), shared
  components and shared libraries, three Ops Lens screens (audit log, processing status, the scheduler), the
  Notification Center, the operational objects domain, and a guarantees panel whose own docblock cites a plan
  its page does not link.
  ⛔ **One listed item is FALSE and is struck:** ~~the Catalog read model (`com.gamma.catalog`) is "owned by
  none"~~ — it is a declared `resource:` of
  [`okf/capabilities/metamodel/metamodel.md`](okf/capabilities/metamodel/metamodel.md) (front matter, line 5). ⚠ Filed
  as P3 deliberately — an undocumented pane is a smaller problem than a *wrongly* documented one, and
  `SPEC-STALEREF-1` is the same budget better spent.
## 6. Standing refusals and won't-do (not work — keep so nobody re-files)

One line each; the reasoning is in the pointer. Reopen only on the stated trigger.
*(Triggers audited 2026-09-07 — every countable one was recounted against the code; none had fired.)*

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
- **Decided 2026-09-06, keep as designed:** the fetch lane stays FIFO (revisit on the first observed fetch-lane wait) · `requireTopLevelSinks` is a depth rule, not a jail · bounce/complaint handling stays manual until receipts persist · JAVA-SIMP-2 stops at seam #2 (no defect hangs on the sink casts) · the `batch_id` trio rides the MAJOR (release notes hold it) · D-7 `materialized` is done-by-absence · **Postgres multi-user is PARKED** until a multi-operator install exists · unpack roll-up + entry grain ratified · SEC-07 Vault/KMS only when a client policy requires it · deployment D1–D8 signed as recommended (🔴 **D3 was signed as a 2GB default that DOES NOT EXIST** — corrected 2026-09-09; `DuckDbUtil.memoryLimit(null)` returns `null`, no `scheduler.toon` ships, and this file's own GAP-4 row says so. See `okf/capabilities/editions/editions.md` §5.3 and `okf/capabilities/pipeline-execution/pipeline-execution.md` §2.4).
- **Working as designed** (from the archived gate register §5): write-root 503 · `ConfigSafetyValidator` 422 · `PathJail` 403 · 409 conflict · `ExpressionGuard` · `SqlGuard` · BI share tokens · active-pipeline delete refusal · Incident resolution backend-gated · editions = build flavors · `AuditTrail` has no *authentication* events — sign-in/sign-out are not audited; *authorization* decisions ARE (`access.denied`/`access.granted`, ABAC A5) (⚠ disclose the first half; corrected 2026-09-08) · air-gap CI · append-only registry · manifest owns existence · nothing prunes by default · 🔴 ~~`-Djobs.maxConcurrentRuns` is the only bound~~ **STALE — refuted by D11 and by a second bound** (corrected 2026-09-09): the Run cap is **ON by default at 4** in code (`JobService.DEFAULT_MAX_CONCURRENT_RUNS`), owned by `scheduler.toon`, with the flag a bootstrap default only; and the INGEST engine has its **own** semaphore plus a per-pipeline `PipelineRunGuard`. Two bounds, neither governed solely by that flag — owner §2.5/§3.3 · refused: Spring/Quarkus, distributed-by-default, per-record lineage, Lens-as-permission, PIP-1, sink-owned `partitions`, Decision-Rule + `route:`, raw `Connection`, `CREATE MACRO` outside AUTHORING-REDESIGN-1 (d), step-workbench S3.

## 7. Duplicate map (same work, several names — update all when closing)

*(Rebuilt 2026-09-07. The previous table carried six dead aliases, two canonical rows pointing at the now-empty
§1, and four duplicate pairs it never recorded. A dead alias is worse than none: it makes a closed row look open.)*

| Canonical row | Also appears as |
|---|---|
| Row 15 — ELT Phase 6 deletion half (§2) | pipeline-spec §12 row 15 · Platform Services Stage 2 precondition (§3) · 🔴 ~~`BatchGraphRunner` parity blocker~~ **THAT CLASS DOES NOT EXIST** (the live one is `ConsignmentGraphRunner`) (corrected 2026-09-09 — see §3) · §5 "archive pipeline-spec + waves-plan when Row 15 closes" |
| D13 parser field tiers (§2) | `archived-documents/plans-archive/parser-field-tiers-interview-plan.md` (the interview-#2 kit, archived 2026-09-09) · `okf/frontend/features/grammar-config.md` (the pre-agreed analysis rule) |
| AGT-5 `DryRunProvider` (§2) | AGT-6b row (§2) · `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2 |
| EXECUTION-RESIDUALS X4 record-level replay (§3) | `okf/frontend/features/run-detail.md` + `USER_GUIDE.md` "reprocess is whole-batch only" · `INDEX.md`'s `EXECUTION-RESIDUALS-SKETCHES` pointer (which cites §4 — the row is in §3) |
| `batch_id` rename trio — §6 (decided: rides the MAJOR) + §2 release notes | `okf/backend/control-plane/api-stability.md` §Release notes (D-12) · `archived-documents/plans-archive/consignment-elt-architecture.md` deferred renames · `elt-final-amendment-plan.md` D-12 / Phase 7 · `okf/backend/engine/db-layer.md` (cites §4 — no such row) |
| X-Actor full removal (§2) | `okf/backend/editions/auth-security.md` §Still-open · `EDITIONS.md` SEC-11 · `REQUIREMENTS.md` R4. 🔴 The §2 row's stated gate ("the API-v1 sunset") names apparatus **deleted 2026-07-25**, and `api-v1.md` never mentions X-Actor — re-state the gate before working it |
| Completeness KPI hold (§2) | Completeness KPI K2/K4/K5 (§3) · `archived-documents/plans-archive/completeness-kpi-plan.md` |
| Compliance program NFR-7 (§2) | SOC 2 Type II window (§2) — the same observation window, twice in one table |
| Deployment topology live validation (§2) | Deployment topology gaps (§3) · §6 "D1–D8 signed as recommended" — the §3 row's gate is already discharged |
| Postgres multi-user — §6 PARKED | §3 Postgres multi-user row — now says PARKED and points at `plans-archive/` (this row's "contradicts §6 / dead pointer" note was stale by 2026-09-08); `EDITIONS.md` OPS-03 |

**Deleted 2026-09-07:** *Three disagreeing name rules* — resolved **2026-08-17**
(`okf/backend/control-plane/pipeline-identity.md`), not on 2026-09-06, and all three of its aliases were dead or
stale. Its live successor is the new §3 row **NAME-DIRS-1**.

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays authoritative),
then **delete the row here**. Do not leave a strikethrough as-built narrative behind — the previous page
grew to 505 KB that way before the 2026-09-06 consolidation. This page lists **open work only**.
