---
type: Guide
title: Inspecto LA demo kit - runbook and stakeholder Q&A — Short version, claims, rehearsals and requirements
description: Part 2 of 3 of Inspecto LA demo kit - runbook and stakeholder Q&A: Ten-minute short version; Claims we do NOT make, and what is not yet; Contradictions found between sources (2026-10-12); Rehearsal record: fresh full bake, 2026-10-13; Final go/no-go rehearsal: fresh full bake, 2026-10-14; Building a private showcase on a customer's real feed (lessons, 2026-10-15/16); Technical / security …
tags: [module, la-core, link-analysis, demo, presales, admin-guide]
timestamp: 2026-11-15T00:00:00Z
---

# Inspecto LA demo kit - runbook and stakeholder Q&A — Short version, claims, rehearsals and requirements

Part 2 of 3 of [Inspecto LA demo kit - runbook and stakeholder Q&A](admin-guide-demo.md) · previous: [Pre-flight, day-of checklist and the script](admin-guide-demo-preflight-script.md) · next: [Stakeholder Q&A](admin-guide-demo-stakeholder-qa.md).

## 3. Ten-minute short version

| Min | Scene | Persona |
|---|---|---|
| 0-1 | Pre-flight already green (`17/17 probes passed`); sign in | `analyst` |
| 1-3 | S1 Follow the money + Suspicion score; S5 Pattern match > Structuring > Run on server (hub, 96 legs, 12 payers) | `analyst` |
| 3-5 | S6 Value Measures: Pass-through (13 accounts, relays 0.985) and Cash-out (`TILL-06` 97 %) | `analyst` |
| 5-7 | S2 open `mule-hub-case`, Replay; S8 unbounded expand -> Waiting for approval -> `manager` approves | `analyst`, `manager` |
| 7-8 | S11 Dossier: Verify, then the prepared tampered manifest -> NOT verified | `analyst` |
| 8-9 | S7 the 401/404/403/422 `curl` block | terminal |
| 9-10 | S15 evidence slide; take questions; S14 only if asked | slides |

## 4. Claims we do NOT make, and what is not yet

Say these before anyone asks. Confidence tags as in [§10](admin-guide-demo-stakeholder-qa.md#10-stakeholder-qa).

* **Scale:** proven to 10^8 edges (measured, synthetic corpus), **10^9 is an extrapolation**. The live demo corpus is small (about 1,800 ordinary
  transfers plus the planted ring); it demonstrates behaviour, not scale.
* **Real IAM:** no real-IAM sign-in is verified on this bundle. Demo Users sign in with no password on a loopback-only, `-Distribution demo` build, which refuses
  to run bound elsewhere. Do not call it a security demonstration of SSO.
* **Embed UI:** built (mint dialog, grant list, `/embed/inv/<token>` viewer, `-Dcontrol.frameDeny`, Space `embed.frame_ancestors`); this S14 script drives only the API. For a real deployment read *Embed links behind a reverse proxy* below.
* **Fences:** the "engine names no la-* class" rule is guarded in CI by `tools/check-engine-names-no-la.mjs` (DR-T3); the dependency fences are shown by a named guard, not live.
* **Postgres store, Personal 503, 429/503 bursts:** other variants (see the variants runbook) or `--burst`, not part of this script.
* **A fired Alert names entities:** it does not, by design (aggregate only).
* **Alerts inside the standalone app:** the la-app has its own Alerts area since 2026-10-12, and Open Investigation stays in the la-app.
* **Real-IAM sign-in** was proven once, end to end, against a local WSO2 Identity Server on 2026-10-13
  ([admin guide §2](admin-guide-la-core.md#2-real-oidc-sign-in-runbook-la-app-local-wso2-identity-server)); the demo bundle
  itself never shows it. Confidence: PROVEN on that run, not on the demo bundle.
* **Embed links** are built and driven live on both the file and the Postgres store (viewer page, frame headers,
  revoke); a few edge paths are unit-tested only (`LA-EMBED-VIEW-1` remainder). OFF unless the host runs with
  `-Dla.embed.enabled=true`.
* **No Hive or Kerberos connector;** feeds go in as files or database queries. Confidence: NOT YET.
* **Slowly changing dimensions (SCD2) are deferred.** The setting is accepted but never honoured (decision D-ING4).
  Confidence: NOT YET.
* **Multi-pod operation is not complete** (Q26); a two-host drill has not been run. Confidence: NOT YET.
* **Measurements are from a laptop on a synthetic corpus.** Confidence: NOT YET for production hardware.
* **The general audit ledger is not hash-chained** (Q4), and the Investigation seal is not a signature (Q14).
* **No retention or purge for Investigations** (Q11).
* **Accessibility:** the WCAG 2.2 AA audit is at specification and automated-scan level; the Link Analysis items
  were checked with axe in a browser with two vendor grid findings left, and a full assistive-technology audit is
  not done (`LA-A11Y-AUDIT-1`). Confidence: NOT YET.
* **No published precision or false-positive rate** for any score (Q19).

## 5. Contradictions found between sources (2026-10-12)

This kit follows the figure named; the others should be corrected at source.

1. **`TILL-06` share:** readiness plan DR-T6 says 82 %; `ControlApiLaShowcaseFiguresTest` and the manual say 97 % (8 payers). The test wins.
2. **Step 1 figures:** the plan's "170 nodes, 1,803 links, centrality `ACC-1008`" belong to the `demo` Space; the plan's own seed note says on the
   showcase the hub ranks first. Do not quote 170 / 1,803 on `la-showcase`.
3. **Seeded four-eyes:** the plan's DR-S2 acceptance says "an unbounded expand of the hub creates a pending request"; the seed's own 8 steps use a bounded
   expand (`maxFanOut` 5) so none is pending after seeding. The scene S8 therefore creates the request live.
4. **Smoke wording:** the task brief expects "N/N pass"; the runner prints `N/N probes passed`.
5. **Alert -> Open Investigation** is described by the plan and manual as part of the product; since 2026-10-12 the la-app renders it too (its Alerts area).
6. **Algorithms:** 29 in code and in [architecture](architecture-la-core.md) §5; the readiness plan's defence matrix says 28.
7. **Routes:** the readiness plan says 75; the route count today is in [architecture](architecture-la-core.md) §6.
8. **10^8 build:** the first spike says 368 s (17.9 GB peak), the later table 447 s (8 GB cap), the roadmap "24 min
   under an 8 GB cap"; index size 3.43 GB versus 2.23 GB across the two measurements. Quote the later table.
9. **Accessibility:** the stakeholder framing "spec-level" versus the board, which records axe runs in a browser.

## 6. Rehearsal record: fresh full bake, 2026-10-13

Bundle: `package.ps1 -Edition Advanced -DemoAuth -Ui la-app` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Advanced -Product la -Distribution demo`)* WITH the built-in bake, built from `origin/master` (`79212c613` plus later docs); served with `serve-demo.bat PORT=8107` and `-Dla.embed.enabled=true`; driven in the built-in browser as the Demo Users. Scripted smoke: `15/16` plain and `29/30` with `--telecom --embed`; the single FAIL is the `la-showcase-off` probe, because a baked bundle ships no `la-showcase-off` Space (expected, see §0 step 2).

| # | Beat | Result | Evidence |
|---|---|---|---|
| 1 | Bake path | PASS | Bake booted, seeded, rebased 357 files; the old spurious 409 now logs "investigation: 409, now readable (head step 0)" and carries on |
| 2 | Sign in as `analyst` (full reload returns to the deep link) | PASS | picker lists 5 Demo Users; Link Analysis menu present |
| 3 | Start an Investigation from *Explore a Dataset* | FAIL then PASS | first press refused (422, DATE time column prefilled); typing `BOOKED_AT` works. Fixed: Time column now defaults blank (`LA-EXPLORE-TIMECOL-DATE-1`) |
| 4 | Seed ONE entity, **Expand one hop** | PASS | Working Set 1 -> 7 entities, 6 links |
| 5 | Unexpanded marker | PASS | 6 dotted nodes, legend "Dotted outline: not expanded yet", count in the legend |
| 6 | **Choose neighbours** | PASS | 2 neighbours listed; *Add the 10 strongest* -> step "restricted to 2 chosen neighbours", 7 -> 8 entities |
| 7 | Direction | PASS | set Outgoing; step text "direction out" |
| 8 | Hide / show a link kind | PASS | legend chip `crypto` on `mule-hub-case`: 7 -> 6 -> 7 links drawn |
| 9 | Several seeds + **Connect seeds** | PASS | 5 pasted ids: "6 of 10 pairs connected within 3 hops; 4 not connected", the unknown id named under "No links found for" |
| 10 | **Expand from these nodes (4)** | PASS | one sealed step, 25 -> 26 entities, not held for four-eyes |
| 11 | Undo, then reopen | PASS | "Undid step 7", 26 -> 25; reopening keeps the log |
| 12 | Dossier build and verify | PASS | "Verified", "Log intact"; tampered manifest is the smoke probe (PASS) |
| 13 | Detection / Alert path | PASS | Alert `mule-pass-through` (13 breaching, aggregate only, no ids); *Open Investigation* lands on `mule-hub-case`. The Alerts page IS in the la-app now (S12's claim is out of date) |
| 14 | Masked persona view | PASS | `analyst` sees `masked:<hex>` everywhere; `reviewer` read-only 403 and non-member 404 are smoke probes. Admin Reveal not re-driven this pass |
| 15 | Four-eyes (S8) | PASS | the bake leaves a pending request `p1`; `analyst` approve is 403, `manager` approve is 200 and runs the expand |
| 16 | *Investigate a number* `99979100001` | PASS with a note | two degrees in seconds, but 895 nodes / 2,901 links (hairball); fixed: the card expands one degree, *Choose neighbours* goes further (`LA-INVESTIGATE-NUMBER-HAIRBALL-1`) |

Not rehearsed this pass: Drafts cycle, Compare and seal, Settings editing, embed curl transcript (S14), Postgres/Personal variants.


## 7. Final go/no-go rehearsal: fresh full bake, 2026-10-14

Bundle: `package.ps1 -Edition Advanced -DemoAuth -Ui la-app` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Advanced -Product la -Distribution demo`)* with the built-in bake, from `origin/master` `66f63ef73`, unzipped to a fresh folder and served with `serve-demo.bat` (`PORT=8119`, `-Dla.embed.enabled=true`); driven in the built-in browser as the five Demo Users. Scripted smoke `la-demo-smoke.mjs --embed --telecom`: **29/30**, the single FAIL is the expected `la-showcase-off` probe (a baked bundle ships no such Space).

> **Build trap (new):** the bake's graceful stop sends Ctrl+C by attaching to the server's console, so `package.ps1` run from a console-less host (an agent shell, a CI step without a console) fails with "the server did not stop gracefully ... within 45 s" **every time**. Run the build from a real interactive terminal.

| # | Beat | Result | Evidence |
|---|---|---|---|
| 1 | Sign in as `analyst` | PASS | picker lists 5 Demo Users; menu has Link Analysis, Geo, Entity Lists, Alerts |
| 2 | *Investigate a number* `99979100001` (purpose required) | PASS | one degree: 403 nodes, 403 links, a star, not a hairball (that number has 402 contacts) |
| 3 | Legend hide/show a link kind | PASS | `forwarding` hidden 403 -> 402 links, shown 403 |
| 4 | Unexpanded marker and *Only unexpanded* | PASS | legend count 402; only-unexpanded draws 402 nodes, 0 links; off restores |
| 5 | Expand one node with direction and link kind | PASS | step text "direction out; link kinds voice", 2 link rows |
| 6 | *Choose neighbours* (small node, then the hub) | PASS | 6 neighbours; *Add the 10 strongest* step "restricted to 6 chosen neighbours"; hub list "402 neighbours", *Show more* pages 50 per press to 402 |
| 7 | *Connect seeds* (5 numbers) | PASS | "8 of 10 pairs connected within 3 hops; 2 not connected", routes tabulated, budget banner shown |
| 8 | *Expand from these nodes (8)* | PASS | one sealed step, 407 -> 782 nodes, "161 more left out by the fan-out cap" stated |
| 9 | *All algorithms (server)* | PASS | 29 algorithms; PageRank 782 nodes in 28 ms; Louvain 8 communities |
| 10 | Undo, then reload | PASS | "Undid step 6", 782 -> 407; after reload the same 7-step log and Working Set |
| 11 | Members | PASS | grant `outsider`, revoke it, last-lead revoke refused with the server wording |
| 12 | Drafts | PASS | fork, step, Conflicts ("No conflicts"), Rebase, Promote -> step 8 on the Investigation |
| 13 | Dossier build and verify | PASS | "Log intact", "Verified", sealed bundle download 200 |
| 14 | References | PASS | `jira/ticket FM-1042` added, "1 of at most 200" |
| 15 | Compare and seal | PASS | needs From/To on both windows (inline hint says so); sealed step 9 with fingerprint |
| 16 | Embed: mint, open without a session, revoke (admin) | PASS | mint dialog shows the link once; the viewer opens masked, read-only, token scrubbed from the address bar; after confirming *Revoke* the public GET is 404 |
| 17 | Admin Reveal | PASS | `MULE-HUB-01 (reveal audited)` |
| 18 | Four-eyes (S8) | PASS | the baked request `p1` waits; `manager` *Approve* -> step 9 "requested by analyst, approved by manager", 9 -> 15 entities |
| 19 | Detection and an Alert | PASS | Alerts page lists `mule-pass-through` (13 breaching); `analyst` *Open Investigation* lands on `mule-hub-case` |
| 20 | Masked persona view | PASS | `reviewer`: 30 `masked:<hex>` ids, `MULE-HUB-01` absent; mutating buttons disabled with reasons |
| 21 | Coverage display | PASS | "Every day has data", `mule_transfers` 3 of 3 |
| 22 | Settings as non-admin | PASS | `analyst` and `reviewer`: no rail link, `/settings` has no editable field and no Save; `admin`: 26 editable fields |

Noted, not filed (cosmetic, no functional effect): the console logs "Unable to preventDefault inside passive event listener" on canvas wheel/zoom; the Add-reference form keeps System and Type after a successful add; the *Open Investigation* action was not seen in the `manager` Alerts view (not investigated: `analyst` has it).

**GO.** 22/22 beats pass on the customer-zip path; no FAIL to fix or file.

## 8. Building a private showcase on a customer's real feed (lessons, 2026-10-15/16)

A showcase was built by hand on a real IMS call-and-message record feed: first a 29,650-record extract, then one production
day of 132 M records. It ran in a private local Space outside the repo, and the data was never committed. The aggregate
timings are in the [whitepaper](brochure-inspecto-la-whitepaper.md) §6.4. What carries over to the next real feed:

* **Keep real identities private.** Use a local Space outside the repo (git-excluded), classify MSISDN, IMSI and IMEI
  so `typed` masking is on, and mask recordings. If the demo must travel outside the data owner, alias the identities at
  ingest. Make no fraud verdict: a burst is a pattern worth an analyst's time, and a multi-SIM handset may be a default IMEI.
* **Never drop a trailing column because it looks empty.** One row filled the last of 84 columns. With the schema cut at
  82, that row was rejected as `TOO MANY COLUMNS`, by design (`skip_tail_columns` is the escape). A 0.00 fill rate can
  round a handful of values away.
* **The Collector's duplicate check was by file name**, so a byte-identical copy under a new name ingested twice.
  Dedupe on a record key, or guard the inbox.
* **Normalise in a `sql.template` Job, not in the Pipeline**, so the raw Dataset stays raw. The single-statement guard
  refuses a literal semicolon even inside a regex: strip a `;phone-context=` suffix with `split_part(x, chr(59), 1)`.
  Exclude blank and placeholder identifiers (an empty IMEI is "unknown", not one shared device). Do not count a call
  twice (setup and end records).
* **Index Job timing.** Only `sql.template` emits `job.dataset.produced`, so put `la.index.build` on a cron after the
  links Job. On a hand-built Space (not from a template) the Job's run is refused until it has a recorded last editor
  (`JobAuthority`): an administrator re-saves it once (`PUT /jobs/<name>`).
* **Non-numeric ends drop out of the index.** The index keys each end through its Entity Type's normaliser (`e164` for
  a number), so sender names and short codes become NULL ends and are dropped and counted (`droppedNull`; about
  12.8 M of 39.4 M links on the production day). A beat that seeds a sender name reads the Dataset instead, under the
  projection limits.
* **Place large Parquet inside the Space data root.** A Dataset reads only inside that root: an outside path, a
  `read_parquet` literal and a junction are all refused. A Parquet ingest would rewrite every column as VARCHAR, so
  the day's files were moved into the Pipeline's store instead.
* **Give every link Dataset an event-day column** (`eventDate`), or the data-trust strip and the Dossier say "as-of not
  known". A Dataset built by a Job carries no input-file stamps.
* **Recalibrate thresholds on a full day.** The extract's thresholds fired hundreds to hundreds of thousands of times on
  the production day. A Dataset Measure Alert Rule is edge-triggered: resolving its Alert by hand does not re-arm it while the
  measure stays breached, and a restart rebuilds the open-breach state from OPEN Alerts.
* **Four-eyes beat.** An expand carries the active Domain Profile's fan-out cap; where the profile sets none, the
  expand is unbounded and is held above the Space's `four_eyes_fan_out_above` (the *Investigate* card too). The held
  degree says so and what to do: ask an approver, or use *Expand…* with a fan-out at or below the threshold.
* **Bundle.** `package.ps1 -Edition Advanced -Product la -Distribution demo -NoBakeDemo`. The la-app UI has no Incidents or Cases
  page, so show the Alert, Incident and Case through the full SPA or by API. The saved-view picker opens its submenu on
  hover; a plain click on the row only closes the menu.

## 9. Technical / security review cut (T1-T13) and its rehearsal record

For a CISO, lead architect and forensic reviewer: every PROVEN row of the [whitepaper](brochure-inspecto-la-whitepaper.md)'s Claims Register shown live or by a named test, every NOT YET row said out loud. It reuses this runbook's scenes (S1-S15), personas and seed. Run of show: 45 minutes at rehearsal pace; the 35-minute cut drops T10 and T12 and shortens T3; never cut T5, T6, T7, T9. (Moved here from the roadmap §D2 on 2026-10-24; section numbers are the whitepaper's current ones, checked against its headings that day.)

| # | Claim (whitepaper §) | Scene | Evidence if challenged | Rehearsal 2026-10-24 |
|---|---|---|---|---|
| T1 | In-process, no external graph database (§4.1) | S1 | dependency fences: `tools/check-module-deps.mjs`, `tools/check-engine-names-no-la.mjs` | PASS: both guards green (13 extracted modules, enforcer rules match); the bundle's jars hold `inspecto-graph` and `inspecto-la-*`, no graph database |
| T2 | Explainable Suspicion score, Propagated Risk (§3.2-§3.3) | S1 | `GraphSuspicionParityTest`, `ControlApiGraphRunTest` | **PARTIAL**: `mule_layering_ring` (113 nodes, 225 links after the drift file) ranks the hub first (the same token as its 0.979 pass-through row); for the per-node **Factors**, type `mule_account` in the *Risk Score model* field: the `la-showcase` template ships that Risk Score model and its `mule_risk_score` Job (every 10 minutes; trigger it once after the first ingest), and MULE-HUB-01 scores 100 from three factors (cash deposits in, distinct payers in, value wired out) |
| T3 | 29 algorithms, TypeScript and Java parity (§3.1) | S5 | `GraphAlgorithmsParityTest` | PASS: Analysis tab > *Server* lists 33 cards: the 29 algorithms plus *Find paths (server)*, *Edge index*, *Value Measures* and *Burst and periodicity* (there is no "29 algorithms on the server" line since the 2026-10-28 reorganization: count the cards); client and server agree on every algorithm compared in the [tour](../../solution-pack/la-showcase/admin-guide-cross-vertical-tour.md) section 1.3 |
| T4 | Value Measures, the 97 % cash-out (§3.4) | S6 | `ControlApiLaShowcaseFiguresTest` | PASS: Pass-through 2026-09-01 to 2026-09-04: 13 entities, the two relays at 0.98499982, the hub at 0.9793; the 97 % is `node tools/la-demo-figures.mjs` (2/2: Pass-through 13, TILL-06 at 97 %), not driven in the form (the picker resets while selecting) |
| T5 | Keyed masking, audited Reveal (§5.2, §5.4) | S3, S4 | `ControlApiExplorationMaskingTest`, `ControlApiInvestigationOversightTest` | PASS: tokens only for `analyst`; Reveal is under the Investigation's *⋯* > *Settings…* > *Investigation settings* > *Oversight and approvals* > *Masked entities*. The template's `fraud-analyst` role holds `canRevealLinkEntities`, so `analyst` may Reveal too (audited, 2026-10-28: "→ MULE-HUB-01 (reveal audited)"); `admin` Reveal answers "→ MULE-HUB-01 (reveal audited)"; Settings > Link Analysis masking `all` saved and "In force: all", then restored to `typed`. Under `typed` every Account id is already masked on this Space, so typed versus all differ only for an unmasked Entity Type |
| T6 | Gate order, strict 404 (§5.1) | S7 | `ControlApiGeoLinkModuleGateTest`, `ControlApiInvestigationMembersTest` | PASS: smoke 401 no token, 403 `reviewer`, 404 non-member, 404 `MODULE_DISABLED`, 422 bad create, 200 member (the six probes; 20/20 with the drift and embed probes) |
| T7 | Four-eyes, self-approval refused (§5.3) | S8 | `ControlApiInvestigationOversightTest` | PASS: the request is under *⋯* > *Settings…* > *Investigation settings* > *Oversight and approvals*; `manager` Approve: step 9 "requested by analyst, approved by manager", 9 to 15 entities; the requester sees "Waiting for a different person"; `analyst` and `reviewer` approve answer 403 `canApproveLinkExpansions` (the same-person refusal is the test) |
| T8 | Op log, replay, drift on file append (§2.2) | S2 | `ControlApiReplayDriftOnFileAppendTest` | PASS: *Replay* "Replay is equivalent"; ticking *Re-read current data (drift check)* then *Replay* adds a Drift table (step 2, 8 sealed rows, 8 now, "changed"). The smoke's far-future file moves the Data strip to "27 Apr 2033": run it before the day |
| T9 | SHA-256 Dossier, tamper-evident (§2.3) | S11 | `ControlApiDossierTest` | PASS: untouched manifest Verified; one flipped digit uploaded: NOT verified, Changed `log.jsonl#2` (B) and `mm-structuring-case` (A) |
| T10 | Drafts, atomic promotion (§2.4) | S9 | Draft tests | PASS: Conflicts "No conflicts", Rebase, Promote (step 7), Discard; Discard takes no confirmation |
| T11 | Ephemeral embed, revoke (§5.5) | S14 | `ControlApiInvestigationEmbedTest` | PASS: link shown once, viewer masked and read-only at `/embed/inv/shared`, revoke asks to confirm, public read 404; smoke headers `frame-ancestors 'none'`, DENY, no-store; the claims partner portal frames the view |
| T12 | Data trust strip (§4.6) | - | [`tests-live-drives.md#data-trust-drive-la-data-trust-1-s10-2026-10-14`](tests-live-drives.md#data-trust-drive-la-data-trust-1-s10-2026-10-14) | PASS: "Data as of … · 3 of 3 days · index v3" on `mule-hub-case`; on a Space whose index is still building: "days not assessed · no index" |
| T13 | Scale and limits (§6) | S15 | `InvTraversalBench`; [`tests-index-scale-run.md`](../la-storage/tests-index-scale-run.md) | evidence only (a slide): the bench class and the scale-run page exist; nothing driven live |

Say out loud: 10^9 edges, retention purge (D-U8), precision rates, multi-pod HA, native Hive / Kerberos (NOT YET); the demo build signs in Demo Users, so it is no SSO demonstration; a full-disk consistent rewrite (anchor the Dossier root externally). Prepare a tampered manifest (T9), a late file (T8), an admin token (T6, T11), two browser profiles (T7) and a recorded fallback for T8, T9, T11.

**Rehearsal record, 2026-10-24 (assistant alone, no second person).** Bundle `package.ps1 -Edition Advanced -DemoAuth -Ui la-app -NoBakeDemo -SkipBootCheck` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Advanced -Product la -Distribution demo -NoBakeDemo -SkipBootCheck`)* from `31539d9c5`, four Spaces scaffolded on one server (port 8171, `-Dla.embed.enabled=true`), Demo Users only, synthetic data only; driven in the built-in browser. The T-scenes ran as written except where the table says otherwise. Defects found are on the board (`LA-INVESTIGATE-CARD-FOUR-EYES-HOLD-1`, `LA-BOUND-RULE-AUTHOR-READONLY-1`, `LA-SUSPICION-FACTORS-DEMO-1`, `LA-SEED-FIRST-RUN-1`, `LA-REVOKE-DISCARD-NO-CONFIRM-1`, `LA-DEMO-PICKER-NAMES-1`, `LA-CLAIMS-PROVIDER-MONEY-LAYOUT-1`, `LA-SAVED-VIEW-DOMAIN-PROFILE-1`); runbook errors found were corrected in place (S2 drift is a checkbox, S6 label, the audit Maximum flow of 3, the mobile-money index trigger persona).

**Still owed (row `LA-WHITEPAPER-DEMO-1`):** (1) one spoken rehearsal with a sceptical second person, timed against 45 and 35 minutes: an assistant cannot be its own reviewer, and nothing here was timed against an audience; (2) the operator's confirmation of the two calls below.

**Rehearsal record, 2026-10-28 (re-walk on the reorganized Studio, assistant alone).** Advanced Demo Auth console bundle of `9d543b35f` (`C:\sandbox\inspecto-mfs-bundle`), a second server on `:8099` with `-Dla.embed.enabled=true` over a fresh Spaces root laid down by the four seeders (`--scaffold`, then `--apply`: every stage created, nothing failed). Driven as `analyst` in the built-in browser until the pane was closed mid-run; the rest by API and the smokes, as the Evidence column says. Click paths: the per-Investigation panels (Members, Share and embed, Drafts, Replay and drift, Oversight and approvals, Dossier) are folds of the Investigation's *⋯* > *Settings…* > *Investigation settings*; saved views open from *File* > *Open…*; the toolbox Analysis tab is one catalogue with a *Browser* / *Server* toggle.

| # | Result | Evidence (2026-10-28) |
|---|---|---|
| T1 | PASS | `check-module-deps` (13 extracted modules, enforcer rules match) and `check-engine-names-no-la` green |
| T2 | **FAIL** | `mule_layering_ring` 113 nodes, 225 links, top suspicion 61; `mule_risk_score` triggered as `manager`: "96 entit(ies) scored, 1 high", but the *Risk Score* column stays blank and *Factors* 404s: the Studio asks for the masked alias (defect `LA-SUSPICION-FACTORS-MASKED-1`, fixed the same day for exploration views: the risk-score read now resolves the Space alias; an Investigation canvas is `LA-SUSPICION-FACTORS-INVESTIGATION-1`). By raw key the API gives MULE-HUB-01 100 with three factors. Until fixed, show the factors from the API or say NOT shown live |
| T3 | PASS | 33 *Server* cards = 29 algorithms + 4 (row above); parity as the tour's 1.3 (re-driven today for Shortest path and Maximum flow) |
| T4 | PASS | `la-demo-figures`: 2/2 (Pass-through 13, TILL-06 at 97 %); smoke Measures PASS |
| T5 | PASS | Working Set all `masked:<hex>`; Reveal as `analyst` inline "→ MULE-HUB-01 (reveal audited)"; smoke `reviewer` 403 on identities |
| T6 | PASS | `la-demo-smoke --staged --inbox --embed` 20/20: 401, 403, 404 non-member, 404 `MODULE_DISABLED`, 422, 200 |
| T7 | PASS (API for the approve) | requester sees "Waiting for a different person with the approve-expansions capability" in the browser; approve via API: `analyst` and `analyst` 403 `canApproveLinkExpansions`, `manager` 200, step 10 "approved by manager", 6 entities admitted (9 to 15) |
| T8 | PASS | *Replay*: "Replay is equivalent"; with *Re-read current data (drift check)*: Drift table step 2, 8 sealed, 8 now, "changed". Data strip "19 Sep 2034" after the smoke's far-future file |
| T9 | PASS | *Build dossier* > *Verify this manifest*: Verified, root `sha256:fd88ab6e…`; *Verify an uploaded manifest…* with one flipped digit: NOT verified, "the manifest itself was edited", Self-consistent no, Changed `log.jsonl#1` |
| T10 | PASS | seeded Draft: *Conflicts* "No conflicts", *Rebase* "Rebased onto step 8", *Promote* "the Draft's steps are now in the Investigation"; a fresh *Fork a Draft* > *Discard* now asks to confirm ("This cannot be undone") |
| T11 | PASS (viewer: FAIL in the console) | *Share and embed* > *Create link* shows the link once; the console opens it as "Page not found" (no `/embed/inv` route outside `la-app`: defect `LA-CONSOLE-EMBED-VIEWER-1`; show T11 on an `-Product la` bundle); public read 200 with `frame-ancestors 'none'`, DENY, no-store; revoke, then 404 |
| T12 | PASS | "Data as of 19 Sep 2034 · 3 of 3 days · index v2" on `mule-hub-case` |
| T13 | evidence only | `InvTraversalBench` and `tests-index-scale-run.md` exist |

**Rehearsal record, 2026-11-01 (T2 only, after the Factors fix `LA-SUSPICION-FACTORS-INVESTIGATION-1`).** Preview Demo Auth la-app bundle of `a24fca153` on `:8097` over a copy of the live demo's `la-showcase`, driven as `analyst` in the built-in browser. **T2 PASS:** `mule-hub-case` > toolbox *On screen* > *Suspicion score*, *Risk model* `mule_account`, *Find suspicious nodes*: top 9, the hub alias first at 80 (next 29); its *Factors* sends `?investigation=mule-hub-case` and answers 200: "Risk Score 100 High · mule_account. High from 60", cash deposits in 40 (capped), distinct payers in 36, value wired out 30 (capped). The *Risk Score* column holds the *Factors* button only, never a number (by design). **Before the day:** a Space baked before the model shipped has neither `config/registry/risk-scores/mule_account.toon` nor `config/jobs/mule_risk_score_job.toon` (the live demo's `la-showcase` lacked both: *Factors* 404 "no Risk Score"); copy both from `spaces\_templates\la-showcase`, restart, and trigger `mule_risk_score` once as `manager` ("95 entit(ies) scored, 1 high").

**Calls decided by the assistant, pending operator confirmation (2026-10-24):**

* **IdP or Demo Users: Demo Users for a rehearsal.** It is the safest reading: no credential is typed and no external identity provider has to be reachable in the room. Say out loud that this build is no SSO demonstration. For the real review the operator may choose a real IdP sign-in instead (verified on la-app 2026-10-13, `LA-APP-REAL-SIGNIN-1`; [`admin-guide-la-core.md#2-real-oidc-sign-in-runbook-la-app-local-wso2-identity-server`](admin-guide-la-core.md#2-real-oidc-sign-in-runbook-la-app-local-wso2-identity-server)).
* **T12 stays in the technical cut** (it is dropped only in the 35-minute cut, with T10).
