---
type: Guide
title: Inspecto LA demo kit - runbook and stakeholder Q&A
description: The rehearsable stakeholder demo of the standalone Inspecto LA app (pre-flight, scenes S1-S15, short version, rehearsal records, real-feed lessons, the technical review cut) and the presenter Q&A leave-behind with evidence and confidence per answer.
tags: [module, la-core, link-analysis, demo, presales, admin-guide]
timestamp: 2026-11-15T00:00:00Z
---

# Inspecto LA demo kit - runbook and stakeholder Q&A

*Runbook written 2026-10-12 from the code, the seed script and the tests on `origin/master`; §0 (package flags,
Spaces, Space Templates, Demo Users) re-checked against `package.ps1`, the seed scripts and `spaces/_templates` on
2026-10-24. Q&A (§10) written 2026-10-12 against `22ccbe952`. A scene marked **UNVERIFIED (rehearse first)** has no
test or smoke probe behind one of its claims. Rehearse the whole script once on the exact bundle you will present,
then again the evening before.*

- §0-§3 pre-flight, day-of checklist, the script (S1-S15), ten-minute version · §4 claims we do not make ·
  §5 contradictions between sources · §6-§7 rehearsal records · §8 a private showcase on a real feed ·
  §9 technical / security review cut (T1-T13) · [§10 stakeholder Q&A](#10-stakeholder-qa)

What this is: the step-by-step script for presenting the Inspecto LA demo on the **standalone Inspecto LA app
bundle**. It defends the architecture page ([`architecture-la-core.md`](architecture-la-core.md), cited below as "arch §n") with the running application. The claim-to-evidence map and the requirement ids (DR-*) are in
the archived [demo readiness plan](../../../../archived-documents/plans-archive/la-demo-readiness-plan.md) (all built by 2026-10-13; history only). The
technical / security review cut of this script (T1-T13) is section 9 below: rehearsed by the
assistant alone on 2026-10-24, the sceptical-reviewer rehearsal is still owed. Every control label below is the
one on screen (see the [user guide](user-guide-la-core.md)). The variants (Personal bundle, Postgres
store) are in the [showcase variants runbook](../../solution-pack/la-showcase/admin-guide-variants-runbook.md).

---

## 0. Pre-flight (do this the day before, and again 30 minutes before)

| # | Step | Command / action | Expect |
|---|---|---|---|
| 1 | Build the bundle | `inspecto\package.ps1 -Edition Advanced -Product la -Distribution demo` | A demo bundle folder (`inspecto-demo\`, zipped as `inspecto-demo-<platform>.zip`) with `serve-demo.bat` / `serve-demo.sh` and `modules.list` naming `inspecto-demo-auth.jar` (not `inspecto-oidc.jar`). `-Distribution demo` accepts only Advanced or Preview. With `-Product la -Distribution demo` the build **bakes** a pre-seeded `la-showcase` Space into the bundle (`package-demo-bake.ps1`; needs `node`; run it from an interactive terminal, see §7); `-NoBakeDemo` skips the bake, and the bundle then ships no Space. `-SkipBootCheck` skips the post-build boot smoke (local only). |
| 2 | Attach the Spaces | From a repo checkout: `node tools/seed-la-showcase.mjs --scaffold <bundle>\spaces` | `scaffolded: _templates, la-showcase, la-showcase-off`. Needed on a `-NoBakeDemo` bundle (no Spaces); a baked bundle already holds `la-showcase` but not `la-showcase-off`. This lays down `la-showcase` (the demo) and `la-showcase-off` (LA switched off, used by the smoke runner's `MODULE_DISABLED` probe). To also show the tracked `demo` Space, copy `spaces\demo` beside them; for the other showcases see §0.3. Do it BEFORE the server starts. |
| 3 | Start | `bin\serve-demo.bat` (Windows) or `./bin/serve-demo.sh`; port is 8080, override with the `PORT` env var (`set PORT=8096`). Optional extra JVM flags: `INSPECTO_JAVA_OPTS`. | Banner `[serve-demo] DEMO BUILD on http://127.0.0.1:<PORT>`. Bound to 127.0.0.1 only. |
| 4 | Wait for the first ingest | The Space ingests `TRANSFERS_20260901..03.csv` from its inbox on boot. | The seed in step 5 retries a 422 "cannot read the columns" up to 30 times (2 s apart) until `mule_transfers_dataset` has rows. |
| 5 | Seed | `node tools/seed-la-showcase.mjs --base http://127.0.0.1:<PORT> --space la-showcase --apply` (a run without `--apply` is a dry run that prints every call) | Ends with `created: ...`, `already there: ...`, `failed: -`. Safe to re-run. See §0.1. |
| 6 | Smoke | `node tools/la-demo-smoke.mjs --base http://127.0.0.1:<PORT> --space la-showcase` | `PASS` lines then **`17/17 probes passed`** (6 gate probes + route inventory + untouched manifest verifies + tamper verify fails, and since 2026-10-12 eight more: empty-Dataset projection, read-only persona, three settings round-trip probes, two dark-embed probes, the sign-in picker; a `SKIPPED` line counts as passed). Exit 0. Any `FAIL` line: stop and fix before presenting. |
| 7 | Optional wider smoke | add `--staged <bundle>\spaces\la-showcase\data\staged\mule_transfers --inbox <bundle>\spaces\la-showcase\data\inbox\mule_transfers` for replay-versus-drift (**writes** a far-future 40-row file into the inbox, so run it BEFORE the stakeholder day, not after); `--burst` for 429/503/409 (starts runs: disposable demo only); `--telecom [--presenter manager]` for the telecom checks (triggers Jobs, creates a `smoke-telecom-<ts>` Investigation, 180 s timeout). | Each adds probes to the `N/N` count; a skipped probe prints `SKIPPED` and still counts as passing. |
| 8 | Embed (optional aside, S14) | Start with `set INSPECTO_JAVA_OPTS=-Dla.embed.enabled=true` | Without it the mint answers 503 and every public read answers 404. |

`serve-demo` flags: `--enable-native-access=ALL-UNNAMED -Dcontrol.bind=127.0.0.1 -Dauth.mode=demo -Dobjects.backend=db
-Devents.backend=parquet -Djobs.backend=duckdb` (from `package.ps1`). The two staged files in `data/staged` are NOT
ingested until you copy them into the inbox (used live only if you choose the drift scene, S2).

**Rebuilding or re-copying a demo Space: start it empty.** The audit anchor file lives OUTSIDE the config tree, in `<space>\config.secrets\audit-anchors.jsonl`. If you re-extract a bundle or copy a Space folder but keep audit event rows older than yesterday while the `.secrets` folder is missing, the anchor roll logs `ERROR AuditAnchors ... the audit anchor file is missing but the chain holds rows from <date>` every 5 minutes and writes nothing. That is deliberate: a deleted anchor file is never silently re-signed. Fix: rebuild the Space with BOTH an empty event store and no `.secrets`, or copy both together. Never delete just the anchor file to silence it.

### 0.1 What the seed creates (read from `tools/seed-la-showcase.mjs`, not guessed)

* **Investigation `mule-hub-case`** over `mule_transfers_dataset` (payer `PAYER_ACCOUNT`, payee `PAYEE_ACCOUNT`, link kind
  `CHANNEL`, time `BOOKED_AT`), created as `analyst` with 8 steps: seed `MULE-HUB-01`, `RELAY-01`, `RELAY-02`,
  `SMURF-01` (type `account`); a bounded expand (`maxFanOut` 5, either direction); exclude `RELAY-02` with a reason; annotate
  `MULE-HUB-01`; threshold 1; window 2026-09-01..2026-09-04; resolve; snapshot "showcase baseline".
* **Members:** `analyst` is the owner (lead); `reviewer` reviewer; `manager` reviewer; `admin` lead. `outsider` is
  deliberately NOT a member, and is not admitted to the Space at all (below).
* **Entity Lists:** `smurf-accounts` (SMURF-01..SMURF-12, a `SMURF-` prefix range, and `TILL-06` expiring in 30 days);
  `telecom-watch-msisdn` (`99979100001`, `99979200001`).
* **Identity assertion:** `account:MULE-HUB-01` = `account:ACC-1006` ("same KYC document").
* **A Case** "Mule ring around MULE-HUB-01" from the hub, assigned to `manager` and linked to the Investigation.
* **Investigation Template** `mule-ring-template`; **Alert Rule** `mule-pass-through` (CRITICAL, pass-through Measure over
  `AMOUNT`/`BOOKED_AT`, 2026-09-01..2026-09-04) with **standing detection** enabled.
* **From the Space Template (not the seed):** `link-analysis.toon` (`masking_mode: typed`, `four_eyes_fan_out_above: 2000`,
  `index.enabled: true`, Drafts max 10 open / hibernate 60 min / expire 14 d); Jobs `la_index_build` (on `pipeline.commit`
  of `mule_transfers`) and `la_detect` (cron every 5 minutes); saved views `mule_layering_ring`, `mule_structuring`,
  `mule_large_transfers`, `telecom_fraud_network`; telecom Datasets, packs and Alert Rules.

### 0.2 Demo Users and what each holds (`config/demo-users.toon`, `config/roles.toon`)

| Demo User | Role in the story | Capabilities that matter |
|---|---|---|
| `admin` | Platform admin; Settings; mints embed links; Reveal | admin role plus `fraud-analyst` and `la-settings` (`canAuthorWorkbench`); a lead member of the Investigation |
| `analyst` | Lead analyst, owner | `canManageIncidents`, `canWorkIncidents`, `canRunLinkGraphAnalysis`, `canBuildLinkIndex`, `canAuthorAlertRules`, `canRevealLinkEntities` (`la-analyst`, the same in every LA Space), so `analyst` can Reveal too; `admin` mints the embed links |
| `manager` | Second approver | `canManageIncidents`, `canWorkIncidents`, `canApproveLinkExpansions`, `canOperateRuns` |
| `reviewer` | Reviewer, read-only | none (`la-viewer`) |
| `outsider` | Signs in but is admitted to no Space | none (`no-access`, a role no `roles.toon` defines) |

Every Link Analysis demo Space signs in these same five Demo Users, and each kind means the same in every Space
(`tools/la-demo-roster.test.mjs`). Every LA scaffolder (`tools/seed-la-*.mjs --scaffold`) and the showcase packager
(`tools/package-la-showcase.mjs`), and the preview bake for `la-daily-feed` (`scripts/preview-la/bake-all.ps1`), write the Space **closed** (`admission: closed` in `space.toon`): it admits an
administrator and a holder of a role its own `roles.toon` defines, and answers anyone else as if it did not exist (404,
and it is left out of the Space lists). So `outsider` signs in and sees, on the la-app home page, *You have no access to
any Space* / *Ask your administrator to give you access to a Space.*, with nothing to press. A Space laid down before
2026-11-18 stays open until you add `admission: closed` to its `space.toon` and restart.

### 0.3 Other Spaces and Space Templates

`spaces/_templates/` ships the LA Space Templates below; the three vertical showcases have their own seed script and
runbook and were rehearsed together on one Demo Auth bundle on 2026-10-24 (`LA-SHOWCASE-REHEARSAL-1`; its two
seed and picker defects were fixed 2026-10-28). A Demo User id must mean the same person in every
served Space (see "if it goes wrong" below).

| Template | Use | Lay down / seed | Demo Users (`config/demo-users.toon`) |
|---|---|---|---|
| `la-showcase` | this runbook | `tools/seed-la-showcase.mjs` | `admin`, `analyst`, `manager`, `reviewer`, `outsider` |
| `la-showcase-off` | smoke `MODULE_DISABLED` probe | laid down with `la-showcase` | same roster as `la-showcase` |
| `la-mobile-money` | [mobile-money runbook](../../solution-pack/la-mobile-money/admin-guide-showcase-runbook.md) | `tools/seed-la-mm.mjs --scaffold` / `--apply` | `admin`, `analyst`, `manager`, `reviewer`, `outsider` |
| `la-audit` | [audit runbook](../../solution-pack/la-audit/admin-guide-showcase-runbook.md) | `tools/seed-la-audit.mjs --scaffold` / `--apply` | `admin`, `analyst`, `manager`, `reviewer`, `outsider` |
| `la-claims` | [claims runbook](../../solution-pack/la-claims/admin-guide-showcase-runbook.md) | `tools/seed-la-claims.mjs --scaffold` / `--apply` | `admin`, `analyst`, `manager`, `reviewer`, `outsider` |
| `la-daily-feed` | the reusable daily-feed shape (one telecom XDR CSV per UTC day; Pipeline, Collector, Dataset, freshness and index-lag Alert Rules, `la_index_build` Job); no persona demo | copy a sample day from `data/samples` into `data/inbox/daily_xdr` | the same five (so the closed Space admits them and not `outsider`) |
| `la-sample` | first-run sample of *Getting started* (every edition, no personas, no Jobs) | created by the app: `POST /spaces {template: "la-sample", loadSamples: true}` | none |

## 1. Day-of checklist

* [ ] Server up, `17/17 probes passed` (§0 step 6) within the last hour; **no other Space with a different Demo User roster in `spaces\`**
      (see "if it goes wrong" below).
* [ ] Port free (default 8080, or the `PORT` you chose); open `http://127.0.0.1:<PORT>/` (not `localhost`, not another host: the
      launcher binds loopback only).
* [ ] Browser at 1440x900 or larger (the canvas is about 506 x 708 px with both panels open at 1440x900; below 1300 px wide the
      Toolbox starts collapsed). Hide bookmarks bar; zoom 100%.
* [ ] **Sign-in order** (each persona in its own browser profile or private window so sessions do not collide):
      `analyst` (main window, scenes S1-S2, S5-S11), `manager` (S8, S9), `admin` (S3, S4 Reveal, S13, S14), `reviewer` and
      `outsider` (S7). The sign-in page lists the Demo Users under "Demo sign-in / Not secure, local only".
* [ ] Have open: a terminal at the bundle with the `curl` commands of S7 and S14 ready; the architecture page; the 10^8 slide (S15).
* [ ] **Do not run the seed during the demo** and do not leave the telecom Jobs running a long trigger.

**If it goes wrong (global):**

| Symptom | Cause and fix |
|---|---|
| Sign-in page error "The server could not start a session" | The server's `/bootstrap` answered an error, so no sign-in can work. Read the server log. A frequent cause is the next row. Fix, then press "Try again". |
| Server log/boot error "Demo User '<id>' is defined differently in Spaces '<a>' and '<b>'" | Two Spaces under `spaces\` define the same Demo User with different rosters (the picker keeps one id = one person). Remove the Space with the other roster, or align the `demo-users.toon` files. |
| Screens say the Dataset "has no rows yet" (the Data page: "Waiting for its first run") | The seed/ingest did not run: the first ingest of `mule_transfers_dataset` has not landed, or the Space was copied after the server started. Restart after copying the Space, wait for ingest, re-run the seed. |
| Link Analysis missing from the menu / "not installed" | Not an Advanced or Preview bundle, or the Space has the module switched off (`la-showcase-off` by design). |

---

## 2. The script

Times are rehearsal targets. Persona in brackets. "Arch" = the architecture page section.

### S1. The graph and Suspicion score (2 min) [analyst]
* **Click:** sign in as `analyst` > *Link Analysis*. On the starter cards press **Follow the money (example)** (shown only when the
  Space has a fitting saved view; otherwise **Open a saved view** > `mule_layering_ring`). Toolbox > **Analysis** > **Suspicion score**.
* **Audience sees:** the layering ring drawn from the Dataset; the footer states nodes and links drawn plus the caps.
* **Expect (verified):** on the showcase corpus the hub ranks first (`LaShowcaseGoldenTest`, readiness plan §4); `mule_layering_ring`
  is described as 112 accounts over the three landed days (manual §10). The 170 nodes / 1,803 links / centrality `ACC-1008` figures of the
  readiness plan are for the **`demo` Space, not `la-showcase`**: do not quote them on this Space.
* **Proves:** exploration is a live projection over a Dataset; it is not evidence (manual §1; arch §1).
* **If it goes wrong:** blank canvas or "has no rows yet" = seed/ingest not done (§1). "Truncated" banner is honest behaviour, narrow the query.

### S2. The Investigation ladder, Replay and drift (3 min) [analyst]
* **Click:** Toolbox > **Investigation** tab > **Investigations** list > **Open** `mule-hub-case` (or **Open by id**). Scroll the ops ladder (8 steps).
  Press **Replay**. Then, optionally, land a new file (copy `data/staged/mule_transfers/TRANSFERS_20260904.csv` into
  `data/inbox/mule_transfers/`, wait for ingest) and press **Replay** again; for drift tick the **Re-read current data (drift check)** checkbox, then **Replay** (rehearsed 2026-10-24: "Replay is equivalent", then a Drift table, step 2, 8 sealed rows, 8 now, "changed").
* **Expect:** 8 steps as listed in §0.1; Replay reproduces the recorded Working Set hash. After a file lands, Replay's hash is unchanged and
  Re-read reports drift **only if the landed file touches entities the Investigation holds**: the shipped staged files touch no seeded entity, so
  they will NOT show drift. `la-demo-smoke.mjs --staged/--inbox` lands a 40-row `MULE-HUB-01` file to force drift (probes "replay hash
  unchanged after a file lands" and "Re-read reports drift after a file lands"). For a live drift demo use that kind of file.
* **Proves:** append-only sealed op log; pure fold; replay needs no Dataset; a Dataset change cannot rewrite history (arch §3.1, invariant 2).
* **If it goes wrong:** *Compare and seal* historically reloaded the page (DR-D1, fixed per the plan): the plan lists a sealed Compare as still
  to be driven live, so avoid it unless rehearsed. **Compare and seal: UNVERIFIED (rehearse first).**

### S3. Masked ids: typed versus all (2 min) [analyst, then admin]
* **Click:** read the **Masking: typed** badge in the Query panel. In the Investigation, open the Working Set table. To show `all`, sign in as
  `admin` > *Settings* (left rail) > Link Analysis > masking mode `all`, save, then reload the Investigation.
* **Expect:** under `typed` only values of Entity Types marked masked appear as `masked:...` aliases; under `all` every identifying value does
  (the badge reads `Masking: all (ids shown as aliases)`); aliases are stable, so you can still group and count by them. Under masking the
  Selected line keeps the query-graph node and its Working Set alias as one entity and marks it *(masked)* (manual §11). An alias keeps
  working after a server restart.
* **Proves:** tokens out, raw stays inside; `typed` default (arch §3.4, invariant 1).
* **Which ids are masked under `typed` on la-showcase is not pinned by a test I could find: UNVERIFIED (rehearse first)** which of
  `MULE-HUB-01` / `ACC-*` show as aliases. **Put the mode back to `typed` after the scene.**
* **If it goes wrong:** a changed mode needs `admin` (`canAuthorWorkbench`); `analyst` sees the fields read-only.

### S4. Reveal is audited (1 min) [admin]
* **Click:** as `admin` (a lead of `mule-hub-case`), open the Investigation, then **Reveal…** on the line under the canvas (*N ids
  masked*), or in a masked node's details under **Masked id**; type a reason and press **Reveal**.
* **Expect:** every masked node on screen now reads its real id (canvas, node title, step log); the toast says each was recorded
  with your reason. As `reviewer` (reviewer) the same canvas still shows aliases and offers no working Reveal.
* **Proves:** one Reveal for the nodes on screen, a reason required, one `LINK_ENTITY_REVEALED` audit row per entity
  (LA-FULL-CYCLE-TEST-1 D3; arch §3.4, §6.1 step 7: `LINK_*` audit events). The showcase names no entity in its
  Investigation's name, reasons or notes, so the story reads before and after the reveal.

### S5. Graph Run on the server (2 min) [analyst]
* **Click:** Toolbox > **Analysis** > a group offering **Run on server** (for example Pattern match > **Structuring (smurfing)** > **Run on server**,
  or the **All algorithms (server)** group). **Run on index** is offered where the algorithm supports it.
* **Expect (verified):** Pattern match **Structuring** finds `MULE-HUB-01` with 96 in-band legs from 12 payers
  (`ControlApiLaShowcaseFiguresTest.sectionTenFiguresHoldOverTheShowcaseCorpus`).
* **Proves:** Graph Run is a server-side, budgeted, pooled run (pool 2 threads, queue 16; arch §5, §9). The 19-concurrent-runs 503 is
  `--burst`, not a live scene; on this corpus a SYNC algorithm finishes inline, so the pool is unreachable (the smoke runner says so and skips).
* **If it goes wrong:** server tools are gated by the *Show the query graph* checkbox and the *Run link graph analysis* capability
  (`analyst` has it; `reviewer`/`outsider` do not). Press **Seed** first, then untick *Show the query graph*.

### S6. Value Measures: pass-through and cash-out (3 min) [analyst]
* **Click:** Toolbox > Value Measures (Dataset, link columns and `BOOKED_AT` are prefilled from the open Investigation). Choose
  **Pass-through**, From `2026-09-01`, To `2026-09-04` (To is exclusive), **Run Measure**. Then the concentration Measure with cash-out kind `cash_out` (on the Telecom Domain profile of this Space the picker calls it **Withdrawal concentration**; the 97 % is checked by `node tools/la-demo-figures.mjs`).
* **Expect (verified, `ControlApiLaShowcaseFiguresTest`):** Pass-through flags **13 accounts**; the two relays sit at ratio **0.985**
  (exactly two at 0.985); `MULE-HUB-01` is in the list (manual: 0.979; `SHELL-A` above 1). Velocity lists at least 3 fast forwarders. Cash-out
  concentration: `TILL-06` holds **97 %** (test tolerance 0.97 +/- 0.005) of cash-out value from **8 payers**. Benefit-transfer isolates `SKIMMER-01`
  with **6 recipients**.
  **Names depend on masking:** under typed masking (the demo's default for `analyst`) the measure returns `masked:<hex>` ids, so `MULE-HUB-01` / `TILL-06` /
  `RELAY-*` read as tokens; the counts, the two 0.985 scores, the 97 % and the 8 payers hold unchanged. `node tools/la-demo-figures.mjs` checks them that way,
  and checks the names too when run `--user <a persona that holds reveal>`.
* **Contradiction to be aware of:** the readiness plan's DR-T6 row says `TILL-06` "at 82 %". The test and the manual say **97 %**. Quote 97 %.
* **Proves:** whole-Dataset behaviour detectors are server-computed and read the whole Dataset, not the filtered view (manual §4.3, §9.8).
* **If it goes wrong:** **Run Measure** stays disabled until the form is valid; the window is capped at 31 days, evaluated in UTC; 413/429 have plain
  sentences (burst of 30 then one request per 2 s).

### S7. Members, roles and gates (3 min) [analyst, reviewer, outsider, curl]
* **Click:** as `analyst`, Investigation header > **Members**: owner/lead `analyst`, `admin` lead, `reviewer` and `manager` reviewers. Then show
  the gates with `curl` (token from the demo exchange: `POST /api/v1/auth/exchange` with `{"code":"demo:<id>","codeVerifier":"x","redirectUri":"..."}`;
  the smoke runner does exactly this):
  * no token `GET /api/v1/spaces/la-showcase/inv/investigations` -> **401**
  * `outsider` `GET .../inv/investigations/mule-hub-case/log` -> **404** (not admitted to the Space: the whole Space answers as if it did not exist)
  * `reviewer` `GET .../inv/entity-identities` -> **403**
  * `analyst` `POST .../inv/investigations` with `{}` -> **422**
  * `analyst` `GET` on the `la-showcase-off` Space -> **404 `MODULE_DISABLED`**
  * `analyst` `GET .../mule-hub-case/log` -> **200**
* **Show:** sign in as `outsider`: the home page says *You have no access to any Space* and no Space is offered.
* **Expect:** exactly those statuses (all six are smoke probes, `17/17`). `reviewer` in the UI: the Investigation tab is read-only (Seed, Expand, Undo,
  Fork a Draft disabled with a note saying why); a mutating `curl` as `reviewer` is 403. Members: a lead can **Grant**/**Revoke**; revoking the last
  lead is refused.
* **Proves:** members and roles; non-members get 404-as-absence, a member lacking the role gets 403 only after the Dataset-visibility and policy checks
  (arch §3.1, §6.1 gate order step 6).
* **If it goes wrong:** a 401 on a persona's token means the demo exchange was refused: wrong build (not `-Distribution demo`) or the Space does not define that user.

### S8. Four-eyes: a sensitive expand waits for a second person (3 min) [analyst, manager]
* **Click:** as `analyst`, Investigation tab > select `MULE-HUB-01` > **Expand one hop** with **Maximum fan-out per entity** left **blank**
  (blank = unbounded; the Space threshold is `four_eyes_fan_out_above: 2000`, and an unbounded fan-out exceeds any fan-out threshold).
  The panel answers **Waiting for approval**. Switch to `manager` (holds `canApproveLinkExpansions`), open the same Investigation (a reviewer), Investigation
  tab > **Oversight** > the request ("Expand request ...", "Four-eyes threshold crossed: maxFanOut unbounded (threshold 2000)") > **Approve**.
* **Expect:** nothing enters the log until `manager` approves; the Oversight section shows who requested it and when; on approval it runs as a
  new step "sealed with who approved it". `analyst` never sees the request as decidable ("Waiting for a different person with the approve-expansions
  capability.") and the server refuses a self-decision. The seeded bounded expand (`maxFanOut` 5) did NOT trigger this: only the unbounded one does.
* **Proves:** four-eyes by construction: a pending request is never written to the log (arch §3.1 "Pending approvals"; `ControlApiInvestigationFourEyesRaceTest`,
  `ControlApiInvestigationApprovalInboxTest`).
* **If it goes wrong:** "no pending request visible to the approver" = the approver is not a lead/reviewer of this Investigation (`manager` is a reviewer by
  the seed) or you expanded with a fan-out at or below 2000 (it just runs). One pending request per Investigation: a second is a 409. A hub (more than 500
  distinct contacts) is added but flagged *high connectivity* and not expanded further (manual §9.2).
  **The exact on-screen text and the approve-then-step flow in the standalone la-app: UNVERIFIED (rehearse first)**: the plan records the Oversight panel
  as a gap closed on the gamma host and the live la-showcase four-eyes pass is not recorded.

### S9. Drafts: fork, step, rebase, promote (3 min) [analyst, manager]
* **Click:** as `analyst`, Investigation tab > **Drafts** > **Fork a Draft** > add a step in the Draft > **Working Set** (size) > **Conflicts** ("No conflicts - a
  rebase would carry every step.") > **Rebase** > **Promote**. For the four-eyes variant, put a sensitive expand in the Draft and **Promote**: it is held and `manager`
  approves it under **Oversight**.
* **Expect:** one live Draft per member, at most 10 open in this Space (`drafts.max_open: 10`); a Draft shows a state badge (open, hibernated) and how many steps the
  Investigation has moved. Conflict classes: no-op, changed, superseded, blocked. A **sensitive expand cannot be added to a Draft directly** (422: four-eyes
  applies to the main log); it meets four-eyes at promote.
* **Proves:** Drafts live inside their Investigation; rebase and promote are all-or-nothing under the main lock (arch §3.3).
* **UNVERIFIED (rehearse first):** the plan says the Draft cycle was driven live on 2026-10-11 ("Members, the Draft cycle and DR-D1"), but a full
  fork/step/rebase/promote with a held four-eyes promote on la-showcase in the la-app bundle has no recorded run or smoke probe.

### S10. Entity Lists and the identity head hash (2 min) [analyst]
* **Click:** the left rail > **Entity Lists** > `smurf-accounts` (12 members plus the `SMURF-` range and expiring `TILL-06`); the **Identity Facts** tab.
* **Expect:** the head (fact number and hash) and the success banner **Chain verified**. The seeded assertion `MULE-HUB-01` = `ACC-1006` appears in
  Identity resolution. Note the head hash; cutting the end of the chain leaves a shorter valid chain, so compare later. A broken chain shows **Chain NOT verified**.
* **Proves:** the Identity Fact log is append-only and hash-chained (arch §3.5). The hash is verified on every read.
* **If it goes wrong:** `reviewer` gets 403 on identities (S7). The tamper-evidence script for this log (DR-T10) is not a live scene: **tampering: UNVERIFIED (rehearse first).**

### S11. Dossier build, verify and the tamper check (3 min) [analyst]
* **Click:** Investigation tab > **Dossier** > build; **Download manifest**; **Verify** (shows "Verified" / "Log intact"). Then **Verify an uploaded manifest...**
  with a copy of the manifest in which one hash digit was changed. Optional: **Download sealed bundle** and **Verify a bundle...** on an edited copy.
* **Expect (verified by smoke):** the untouched manifest verifies; a manifest with one hash flipped fails (`verified: false` or HTTP 422; the UI shows "NOT verified" /
  "Bundle NOT verified"; an edited bundle fails the seal "edited after export"). Verify reports anything changed, missing or added.
* **Proves:** SHA-256 manifest over every stored artefact; tampering is detectable (arch §3.1 outputs).
* **If it goes wrong:** the dossier route needs a seeded Investigation (else 404). Hand-editing JSON in front of the audience is error-prone: prepare the tampered file beforehand.

### S12. Alert to Investigation (2 min) [analyst]
* **Click:** open the Alerts page, find the Alert from the Investigation rule `mule-pass-through`, press **Open Investigation**.
* **Expect:** the Alert carries aggregate facts only (rule, Measure, threshold, breach count, worst value); **no entity id, name or alias**; entities are read inside the
  Investigation under its own gates and masking (arch §7, `LA-DETECT-ALERT-AGGREGATE-1`). Standing detection runs from the `la_detect` Job every 5 minutes; the rule
  is edited => the sweep refuses with `NOT_ENABLED`.
* **UNVERIFIED (rehearse first):** (a) since 2026-10-12 the la-app has its own **Alerts** area (the console's Alerts page, reused whole), and
  **Open Investigation** stays inside the la-app (`ALERTS_INVESTIGATION_ROUTE` → `/link-analysis`); (b) the readiness plan lists "Alerts rendering of a fired
  standing-detection Alert" as not verified; (c) the Alert only exists after `la_detect` has run on a breaching rule. Alternative: Investigation tab >
  the bound Alert Rule / standing-detection panel and Monitoring.

### S13. Settings (admin) (1 min) [admin]
* **Click:** as `admin`, left rail > **Settings** (shown only with `canAuthorWorkbench`).
* **Expect:** per-Space limits: four-eyes thresholds, hub threshold (default 500), masking mode with the mode in force, the link index (enabled, build workers,
  waiting line, disk budget, `index.max_age`), the server graph-run budget with the server's ceilings, the Draft limits, the embed-link limits and the
  Entity Types in force; the active Investigation store (filesystem or PostgreSQL) read-only. Without the capability every field is read-only (try as `analyst`).
* **Proves:** limits and bounds are Space settings (arch §9). Editing live and the Edge index first-build flow: the plan lists an editing Settings pass as open:
  **editing: UNVERIFIED (rehearse first)**; show read-only if you have not rehearsed.

### Embed links behind a reverse proxy (operator requirement)
The link's token is **in the URL path**: `/embed/inv/<grantId>.<secret>` (the viewer page) and `/api/v1/public/investigations/<grantId>.<secret>` (the data call). Inspecto
redacts it from its own log lines, audit rows and `links.self` (`PublicPaths.redact`, pinned by `ControlApiInvestigationEmbedTest`), and the viewer rewrites the address bar to
`/embed/inv/shared` so it leaves the browser history. **A reverse proxy, load balancer or CDN in front of the host writes its own access log, and it will record the full path
unless you tell it not to.** Anyone who reads that log can open the link until it expires or is revoked. Before enabling `-Dla.embed.enabled=true` behind a proxy:
* mask the segment after `/embed/inv/` and after `/public/investigations/` in the access-log format (nginx: log `$uri` through a `map` that rewrites those two prefixes to `<prefix>[redacted]`,
  never `$request` or `$request_uri`; the same for any `X-Forwarded-*` copy of the path), and keep `$http_referer` out of it;
* give that log the retention you would give a credential, and do not ship it to a third-party log service unmasked;
* a leaked link is revoked at once from the Share panel (or `POST .../embeds/<grantId>/revoke`); links default to 72 h and a Space can lower `embed.max_ttl_hours`.
Not built: token delivery by a header, a fragment or a POST exchange. A fragment would hide the token from every server log but the page could then not tell the token's Space to
send the right `frame-ancestors` (see `okf/product/module/la-core/architecture-http-and-ui.md` §6.2); a POST exchange is a new route and a new client flow. Both are a larger change than the redaction rule above, so this stays a documented operator duty.

### S14. Embed API aside, API only (2 min) [admin by curl]
This aside drives the API only; the embed UI exists (mint dialog, grant list, the `/embed/inv/<token>` viewer in both the
la-app and the full SPA, since 2026-10-12). Start the server with `-Dla.embed.enabled=true` (§0 step 8). Mint needs `canShareInvestigation` AND a lead
of the Investigation: as `admin` (a lead by the seed).
```
# 1. mint (admin token in $ADMIN)
curl -s -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"ttl_hours":4,"label":"demo"}' \
  http://127.0.0.1:<PORT>/api/v1/spaces/la-showcase/inv/investigations/mule-hub-case/embeds
# response: grantId, token (shown ONCE), url "/public/investigations/<token>", expiresAt, maskingMode
# 2. public GET, NO Authorization header, and show the headers
curl -si http://127.0.0.1:<PORT>/api/v1/public/investigations/<token>
# 3. revoke, then repeat step 2
curl -s -X POST -H "Authorization: Bearer $ADMIN" \
  http://127.0.0.1:<PORT>/api/v1/spaces/la-showcase/inv/investigations/mule-hub-case/embeds/<grantId>/revoke
```
* **Expect (verified by `ControlApiInvestigationEmbedTest`):** the public GET answers 200 with the masked, read-only view and the headers
  `Content-Security-Policy: frame-ancestors 'none'`, `X-Frame-Options: DENY`, `Cache-Control: no-store`, `Referrer-Policy: no-referrer`,
  `X-Content-Type-Options: nosniff`, no cookie, no CORS. After revoke the same URL is **404** (identical to unknown, expired, tampered or disabled: one answer).
  A POST/PUT/DELETE on the public path is 405.
* **Proves:** a read-only, link-only, masked share of ONE Investigation for someone with no login; token stored hashed, shown once (arch §6.2).
* **If it goes wrong:** mint answers **503** = `-Dla.embed.enabled=true` was not set; mint **403** = the caller is not a lead or lacks `canShareInvestigation`
  (`analyst` has no such capability in this Space); **422** = a ttl above the Space cap (default cap 720 h) or a refused classification; **404** on the public
  GET straight after a mint = the flag is off on the serving JVM. The exact path prefix of the public URL (`/api/v1/public/investigations/<token>`) is taken from the
  test's request base, so confirm it once with the `url` in the mint response. **The curl transcript itself was not run by this lane: UNVERIFIED (rehearse first).**

### S15. Evidence slide: scale and fences (1 min) [slides]
Not live. Link index: full build **852 s**, append **31 s** on a synthetic **10^8-edge** corpus (1.8 M nodes), measured; **10^9 is an extrapolation** (arch §10,
`LA-INDEX-SCALE-MEASURE-1`). Fences: `tools/check-module-deps.mjs` and maven-enforcer (arch §2). Both from the readiness plan, not demonstrable on this data.

---

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

Say these before anyone asks. Confidence tags as in [§10](#10-stakeholder-qa).

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

## 10. Stakeholder Q&A

Audience: bank and telecom risk, security, IT and compliance reviewers of the LA demo. Every figure was checked
against the repo on 2026-10-12. Terms follow [`GLOSSARY.md`](../../../../GLOSSARY.md). "backend §n" below means
[architecture](architecture-la-core.md) §n (§6.1-§6.9 in [architecture-http-and-ui](architecture-http-and-ui.md)); "user
manual §n" means [user-guide](user-guide-la-core.md) §n; "Part II reference" means [architecture-records](architecture-records.md).

How to read an answer: two to five plain sentences you can repeat, then **Evidence** (where to look), then a
**Confidence** tag.

* **PROVEN** - a test or a measurement exists for it.
* **DOCUMENTED** - the design says so; it has not been shown live or by a test.
* **NOT YET** - an honest gap. Say so out loud; do not soften it.

### 10.1 Security and access

**Q1. Who can see what? Can an analyst see an Investigation they were not invited to?**
No. An Investigation is visible only to its members (and to the owner or assignee of its linked Case, read only). To
everyone else the answer is "not found", the same answer as for one that does not exist, so nobody can even confirm
it is there. A member who lacks a role gets a refusal only after the Dataset-visibility and policy checks have
passed. Visibility of the underlying Dataset is checked on every request, so losing access to a Dataset also closes
the Investigation built on it.
Evidence: [architecture-http-and-ui](architecture-http-and-ui.md) §6.1 (gate order, step 6); `ControlApiInvestigationMembersTest`; user manual §12.
Confidence: PROVEN (the gate order and members behaviour have tests); the Postgres-store variant is DOCUMENTED.

**Q2. How does masking work, and can it be bypassed by a clever query?**
Identifiers are replaced on the way out of the server by a stable alias of the form `masked:` plus 16 hex
characters (a keyed hash), so an analyst can still group, count and follow an entity without seeing the value. The
default mode is `typed`: only columns whose classification an Entity Type claims (MSISDN, IMSI, account and similar)
are masked; `all` masks every id; `none` shows raw. The same rule applies on the canvas, node lists, paths,
patterns, Value Measures and the Working Set, and a raw id typed into an alias field is refused, so the route is not
an oracle for guessing.
Evidence: [architecture](architecture-la-core.md#34-masking) §3.4; `ControlApiExplorationMaskingTest` (the `everyInvReadOnAMaskedSpaceIsScannedForRawIds`
case scans every Investigation read for raw ids); `ControlApiInvestigationOversightTest` (typed by default).
Confidence: PROVEN for the routes the tests cover. A new route must be added to that scan; say so if asked about
future routes.

**Q3. Can anyone ever see the real value? Who, and is it recorded?**
Yes, through *Reveal*: a lead of the Investigation holding its own capability (`canRevealLinkEntities`, a separate
action from approving) reveals the nodes on screen in one step with a reason, and every entity revealed writes one
audit row with that reason. Reviewers and non-members keep the aliases. In the shipped demo the `admin` persona holds it.
Evidence: [architecture](architecture-la-core.md#34-masking) §3.4 and backend §6 (Four-eyes / reveal row);
`ControlApiInvestigationRevealOnScreenTest`, `ControlApiInvestigationOversightTest
.revealIsPerEntityGatedOnItsOwnCapabilityAndAudited`.
Confidence: PROVEN.

**Q4. What is audited, and is the audit trail itself protected from edits?**
Link Analysis writes `LINK_*` audit events (reveal, embed minted, viewed or denied, four-eyes inbox reads, reference
reads, and so on), and the host records every 401, 403 and 5xx. The platform audit ledger is append-only, but it is
NOT hash-chained: the security spec states that `AuditTrail` contains no hash, chain or signature. Our tamper-evidence
claim is about the Investigation record (Q13), not about the general audit ledger.
Evidence: [architecture-http-and-ui](architecture-http-and-ui.md) §6.1 step 7, §6.2 (audit events); [`security.md`](../access/plan-security.md) row
"Tamper-evidence of the audit log" (checked against `platform/inspecto-access` `AuditTrail.java`).
Confidence: DOCUMENTED for the events; the absence of a chain on the ledger is a stated gap (see Section 8).

**Q5. How do people sign in? What does the demo use versus production?**
The demo uses Demo User authentication: a picker of seeded personas (analyst, manager, admin) on a local server.
That is a token relay, not a real identity provider. Production uses OIDC against the customer's IAM; the host-side
OIDC authenticator exists and is specified, but the Link Analysis standalone app's OIDC sign-in has never been run
against a real IAM.
Evidence: feature page "la-app signs in over Demo User auth"; [`security.md`](../access/plan-security.md)
section 3.4; `docs/BACKLOG.md` `LA-APP-REAL-SIGNIN-1`.
Confidence: DOCUMENTED for OIDC; real-IAM sign-in is NOT YET verified (Section 8).

**Q6. Can the analyst approve their own large expansion? Who holds the second key?**
No. A large expansion (above the Space's thresholds) is held as a pending request, the requester cannot decide it,
and the approver inbox never lists the caller's own requests. Self-approval is refused with 403 even for someone
holding the approve capability, and when several approvers decide at once exactly one decision lands. In the
shipped demo `manager` holds the approve capability and `admin` holds reveal.
Evidence: feature page "Oversight surface" and "Approver inbox"; `ControlApiInvestigationOversightTest
.aSensitiveExpandWaitsForADifferentPersonsApproval`; `ControlApiInvestigationFourEyesRaceTest`; demo readiness plan
section 0.
Confidence: PROVEN.

**Q7. What are the "embed links", and could one leak an Investigation?**
A lead can mint a read-only link to ONE Investigation for a person with no login. The token is 256 random bits,
shown once, and only its hash is stored; it follows the Space's masking, shows no reveal, drafts, Case, Dataset names
or SQL, expires (default 72 hours, a Space may allow up to 30 days by default and up to 8760 hours at most) and dies
automatically if the minter loses the lead role, the Case closes or masking is loosened. Every bad, expired or
revoked token gets the same "not found". The feature is OFF by default (`-Dla.embed.enabled`) and the answer is
GET-only with no cookie and no framing.
Evidence: [architecture-http-and-ui](architecture-http-and-ui.md) §6.2; `ControlApiInvestigationEmbedTest` (expiry, kill switch, same-404, auto-revoke, token in no
log or audit row).
Confidence: PROVEN for the API. There is no viewer page yet (Section 8).

### 10.2 Data handling

**Q8. Where does the data live? Does it leave the server?**
Source data stays in the customer's own Datasets on the host; Link Analysis reads it in place or from a derived
Parquet link index on the same disk. The Investigation record (op log, sealed Working Sets, Dossier inputs) is kept in
the Space's write root on the filesystem, or in the customer's PostgreSQL in Preview and Advanced. The
standalone app is a second UI served by the same host process, not a separate service, and nothing is sent to an
external service.
Evidence: backend §1, §3.2, §4 (index under `<writeRoot>/la-index/`), §8.
Confidence: DOCUMENTED (the architecture; no external-egress test is cited here).

**Q9. What does "never store values" mean for ingest?**
When data is ingested, error and failure text must not carry the offending cell. The engine's messages quote the bad
value, so the platform replaces every quoted literal and "original line" tail with a salted fingerprint, and
file-name handling avoids raw paths. The aim is that a rejected record leaves a reason, not a copy of the personal
data.
Evidence: [`ingestion.md`](../elt/plan-elt.md) "Failure text never stores a value"
(`FailureText.scrub`); [`spaces.md`](../config/plan-spaces.md) (malformed-row rejects).
Confidence: DOCUMENTED (shipped 2026-10-03; this pass did not re-run it). Scope is ingest failure text, not a claim
that no personal data exists anywhere.

**Q10. How is data classified, and does classification drive masking?**
Columns carry a classification from the Dataset registry and lineage. In `typed` mode a column is masked when an
Entity Type claims its classification; untraceable lineage fails closed (masked). A Space can also refuse to mint an
embed link when the strictest classification of the Investigation's columns is on its refusal list.
Evidence: [architecture](architecture-la-core.md#34-masking) §3.4 and [architecture-http-and-ui](architecture-http-and-ui.md) §6.2; `ControlApiInvestigationOversightTest
.aColumnClassificationIsTypedByTheEntityTypeClaimingIt`; `ControlApiInvestigationEmbedTest
.aSpaceCanRefuseToShareInvestigationsOfAClassificationAndAPolicyCanForbidTheActionEmbed`.
Confidence: PROVEN.

**Q11. How long is data kept, and how is it deleted?**
There is deliberately no purge today: the Investigation store is append-only evidence, with no retention period,
purge task or legal-hold record (operator decision D-U8, 2026-09-24). Drafts are different: they hibernate after
60 minutes and expire after 30 days by default, as a discard, with at most 50 open per Space. If a regulator
requires deletion, that decision is to be reopened, copying the existing incident purge pattern. A lead can delete
one Investigation by hand (`DELETE /inv/investigations/{id}`, `LA-INVESTIGATION-DELETE-1`; refused while it is linked
to an open Case or holds a pending approval, and audited).
Evidence: feature page D-U8; backend §3.3 (Draft defaults) and §6 (the Investigations row).
Confidence: DOCUMENTED. A retention period or scheduled purge for Investigations is NOT YET built.

**Q12. What copies of the data can leave the system, and are they masked?**
Exports a person can make are the Dossier (JSON or self-contained HTML), the sealed Dossier bundle with references, and
snapshots attached to a Case. Ids in them are masked as the Space masks them; the manifest hashes the raw store, so
the root is identical masked or not and a masked bundle still verifies. A Working Set is capped at 64 MiB per set
and 4 GiB per Investigation by default (413 above, audited).
Evidence: feature page (Dossier bundle: "Masking, R3, four-eyes"); `ControlApiDossierTest
.theHtmlDossierIsMaskedLikeTheJson`; `ControlApiDossierBundleTest`; settings in
[`configuration.md`](../config/admin-guide-configuration-reference.md) (the Link Analysis Investigations row).
Confidence: PROVEN for masked output; DOCUMENTED for the size caps.

### 10.3 Integrity and evidence

**Q13. What is "sealed", and what does "tamper-evident" actually mean here?**
Each analyst step is appended to an op log and the resulting Working Set is sealed with a SHA-256 hash; the log is
folded deterministically, so replay reproduces the same set. The Dossier adds a manifest hashed over every stored
artefact, and Verify rebuilds it from the store and names anything changed, missing or added. "Tamper-evident"
means an edit made inside the store after the fact is detected when someone verifies; it does not mean the store
cannot be edited.
Evidence: backend §3.1; feature page Dossier section; `ControlApiDossierTest
.anUntouchedStoreVerifiesAndATamperedStepIsReported`, `.theManifestCatchesWhatTheRecordedHashesCannotAndCannotBeEdited`.
Confidence: PROVEN.

**Q14. What does tamper-evident NOT mean? Could an administrator with full disk access rewrite history?**
Correct: it is not a signature and not a write-once store. Our reading of the design is that someone who can rewrite
every file AND recompute every hash consistently would produce a store that verifies against itself; the defence is
to keep the Dossier root (or a sealed bundle) outside the store, for example attached to a Case, and verify
against that. The identity fact log is a hash chain, and a truncated tail is caught only if a head hash was cited
elsewhere. We have not run a test of the "rewrite everything consistently" attack.
Evidence: backend §3.5 (fact log), feature page Dossier ("the manifest remains the custody root"). The
full-rewrite reasoning is ours and is UNVERIFIED by a test.
Confidence: DOCUMENTED, with the attacker-model limit above stated openly.

**Q15. If the data changes afterwards, does an old Investigation change?**
No. Expands store the rows they read, and compare and temporal steps store their findings with a fingerprint, so a
replay needs no Dataset and a Dataset change cannot rewrite history. *Re-read (drift check)* compares against the
current Dataset and reports drift, for example when a file lands in the bound Dataset. A stored Alert watches the
sealed Working Set, not live data.
Evidence: backend §3.1 (sealed reads, D-E3); `ControlApiReplayDriftOnFileAppendTest`; user manual §12.
Confidence: PROVEN.

**Q16. How are a team's parallel experiments kept from corrupting the record?**
An analyst works in a private Draft forked from the Investigation. Promotion rebases onto the current head, reports
conflicts, and appends to the main log under a lock, all or nothing; if the Draft carries a sensitive expansion it
becomes a four-eyes request instead. The main log is never rewritten by a Draft.
Evidence: backend §3.3; feature page (Drafts, rebase and promote).
Confidence: DOCUMENTED (Draft behaviour is covered by tests in the module; the browser UI for Drafts was driven
once, per the readiness plan).

### 10.4 Analytics

**Q17. What algorithms are there, and where do they run?**
The catalogue holds 29 graph algorithms (communities, centrality, paths, similarity, a suspicion score and a
propagated-risk score among them). Over a Working Set they run in-process on the server in a bounded pool; over a
link index only a few (neighbourhood, ego network, seeds-only degree) run, as SQL. The server routes by input type,
never by size, and says when a budget is exceeded rather than returning a partial answer.
Evidence: backend §5; `ControlApiGraphRunTest.theCatalogueListsAll29WithCeilingsAndTheSpacesDefaultsClampedAndEchoed`.
Confidence: PROVEN.

**Q18. Is a result the same every time? Does the screen agree with the server?**
Results are deterministic: ties break by a fixed canonical order, and the Java and TypeScript implementations
assert the same hand-derived fixtures. The result cache is keyed on the relation, row scope, algorithm and
parameters, and only completed results are cached.
Evidence: backend §5 (Parity); `GraphAlgorithmsParityTest`, `GraphSuspicionParityTest`, `ControlApiGraphRunParityTest`.
Confidence: PROVEN for the fixtures; a statement about every input is not claimed.

**Q19. Can you explain why an entity scored high? What about false positives?**
The suspicion score is an explainable 0 to 100 composite with a per-node breakdown, and the top decile is
highlighted. Propagated risk returns each node's top five contributing factors (origin, distance, weight,
contribution). The scores are triage aids: a high score means "look here first", not "guilty", and we publish no
false-positive or precision rate, because that depends on the customer's data and thresholds.
Evidence: user manual section 6 (Suspicion score row); backend §5 (`propagatedRisk`);
`ControlApiGraphRunTest.propagatedRiskAnswersWithScoresAndFactorsAndRefusesBadWeights422`.
Confidence: PROVEN for explainability. Precision on real data: NOT YET measured.

**Q20. What does the demo corpus show, and is it realistic?**
It is synthetic. The shipped mule-ring corpus has 112 accounts over three landed days; the pass-through Measure
flags 13 of them, and a cash-out concentration Measure shows the till `TILL-06` receiving 97 percent of cash-out
value from 8 payers. A test reproduces those figures.
Evidence: user manual section 10; `ControlApiLaShowcaseFiguresTest` (asserts `TILL-06`, share 0.97).
Confidence: PROVEN for the synthetic figures; they say nothing about real-data accuracy.

### 10.5 Scale and performance

**Q21. How big a graph can it handle?**
The server-side path search over a Dataset is certified at about one million links. Above that a Space can switch
on the link index, a versioned Parquet layout, measured on one laptop up to 10^8 edges: a one-key lookup stays near
20 ms at 10^8 (it is flat in size) and a depth-2 walk of a small frontier is about 22 ms median. 10^9 edges is an
extrapolation (an index of about 34 GB and a build of roughly 2.5 hours) and has NOT been run.
Evidence: feature page measurement table (10^6, 10^7, 10^8 rows) and "Extrapolating to 10^9 edges ... NOT measured";
backend §10; `docs/superpower/link-analysis-roadmap.md` (`LA-INDEX-SCALE-MEASURE-1`, 10^9 open).
Confidence: PROVEN to 10^8 on a synthetic corpus; 10^9 is NOT YET.

**Q22. What happens when someone asks for too much?**
It is refused or limited out loud, never silently trimmed. A Graph Run has a size and time budget (shipped default
50,000 nodes, 500,000 edges, 30 seconds; Space ceiling 500,000 nodes, 5,000,000 edges, 300 seconds) and ends
`BUDGET_EXCEEDED` with no result when exceeded. The Run pool is 2 running plus 16 waiting; the next request beyond
that is refused with 503. Expensive exploration calls share a per-person bucket of 30, refilling one per 2
seconds (429). Large expansions go to four-eyes.
Evidence: backend §5 (Budgets, Pool), [architecture-http-and-ui](architecture-http-and-ui.md) §6.1 step 2, backend §9; `ControlApiGraphRunTest
.aBudgetExceededRunIs200WithTheNumbersAndNeverAResultKey`; `LinkAnalysisRateClassCoverageTest`.
Confidence: PROVEN for the budget refusal; DOCUMENTED for the exact pool and queue figures.

**Q23. How does the link index work, and does it go stale?**
It stores each edge twice (by source and by target), in Parquet partitioned by a hash bucket, so a one-entity lookup
reads a small slice. Reads fall back to the plain Dataset read, with a stated reason, when no fresh index exists. For
a Dataset whose input files can be fingerprinted, a stale index is served and flagged. For one that cannot (a view),
the index serves only while younger than 24 hours by default (`index.max_age_hours`, 1 to 8760). The index serves
only shallow lookups (depth 2, at most 20 keys per level); deep or global algorithms use the other paths.
Evidence: backend §4, §9; feature page scope note (Path A, the 5-level walk FAILED its 1.5 s criterion).
Confidence: PROVEN for the measured cases; the age gate is DOCUMENTED.

**Q24. What are the known performance weak spots?**
At about 10^6 edges the index is not faster than the flat read, because each request pays a fresh sealed
connection of roughly 100 to 200 ms; it wins from about 10^7. Lookup cost grows with the degree of a hub entity
(the frontier cap bounds how many keys, not how heavy one key is). A 10^8 build took 447 seconds in the later
measurement under an 8 GB cap. Index build memory and time are laptop numbers, not a sizing guide.
Evidence: feature page "Where the index loses" and measurement table; backend §10.
Confidence: PROVEN as measured; sizing for a customer environment is NOT YET done.

### 10.6 Deployment and operations

**Q25. Which editions include it, and what is installed?**
Link Analysis is optional jars discovered at startup, not a separate server. Since 2026-10-17 (`LA-EDITIONS-1`, which
renamed Personal / Professional / Enterprise to Basic / Standard / Advanced) its jars are staged in every edition: Basic
carries the Link Analysis Explorer, and the Investigation surface needs Standard or up (the `investigations` edition
feature, which only `inspecto-ops` declares). The PostgreSQL Investigation store and the Advanced policy engine are
Preview and Advanced only. Per Space it needs the
`geoLink` module enabled in `modules.toon`.
Evidence: backend §8 and [architecture-http-and-ui](architecture-http-and-ui.md) §6.1 step 4; `ControlApiGeoLinkModuleGateTest`.
Confidence: PROVEN for the module gate; DOCUMENTED for packaging.

**Q26. Can it run on several servers for scale or high availability? Be honest.**
Not yet as a finished capability. A shared run lease exists and was proven against a real PostgreSQL, including a
handover between two processes on one laptop; a handover between two hosts, a standby promotion and a network
partition are NOT proven. The Kubernetes scale-out plan's own status line is stale: it says nothing is built, but the
lease is, and the extent of its Phases A and B has not been audited (row `SCALEOUT-PLAN-AUDIT-1`). For Link
Analysis specifically, the Postgres Investigation store is built, but embed links are filesystem-only (a Postgres
store answers 503 for them).
Evidence: [`../../layer/platform/admin-guide-ha-dr-drill.md`](../../layer/platform/admin-guide-ha-dr-drill.md) "What has actually been proven" and the laptop-scale
drill; [`enterprise-scale-out-plan.md`](../../../../superpower/enterprise-scale-out-plan.md) status header;
`docs/BACKLOG.md` `SCALEOUT-PLAN-AUDIT-1`; [architecture-http-and-ui](architecture-http-and-ui.md) §6.2.
Confidence: NOT YET for multi-pod; PROVEN only for the single-database lease at laptop scale.

**Q27. How do we back up, restore and upgrade? Can it run air-gapped?**
Backup and restore are covered by a runbook (Standard and above), including restoring into a new Space. Investigation
data on the filesystem store is ordinary files under the write root; on Postgres it is one schema per Space. The
product is self-contained and the bundle's install checks are specified to run offline. We have not run a
documented upgrade rehearsal for Link Analysis specifically.
Evidence: [`../backup/admin-guide-backup-restore.md`](../backup/admin-guide-backup-restore.md); `docs/okf/product/module/config/admin-guide-configuration-reference.md`
(Investigation store row); the preflight row in `docs/BACKLOG.md` (`SCR-1`, offline).
Confidence: DOCUMENTED. A Link Analysis upgrade and restore rehearsal: NOT YET (UNVERIFIED).

### 10.7 Integration

**Q28. What data can it read today?**
Files and CSV through the file Collectors, relational databases through the `db` Collector (a query with a
`:watermark`), and Parquet landing. Hive is not supported directly: the agreed route is to extract to Parquet files
and land them through a Collector, and there is no Hive or Kerberos connector. Dimension data (names, risk
indicators) is joined at read time and kept out of the index.
Evidence: `docs/BACKLOG.md` `LA-DAILY-INGEST-1` ("Confirmed today ... no Hive/Kerberos support"; decisions D-ING5,
D-ING6).
Confidence: DOCUMENTED; Hive is NOT YET.

**Q29. How do findings reach our case and alerting tools?**
An Investigation can be linked to a Case (the Case's owner or assignee then gets read-only access), a graph or
snapshot can be attached to a Case, and a standing detection watches a Measure and fires an Alert. A fired Alert is
aggregate only (Measure, threshold, breach count, worst value); entities are named only inside the Investigation
under its own gates. External references to other systems are stored as text and never opened.
Evidence: backend §7 (live detection); user manual §9.7, §9.12; `la.detect` Job in backend §7.
Confidence: DOCUMENTED (driven live in the 2026-10-12 telecom demo runs per the backlog; no single test cited).

**Q30. Is there an API, and is it controlled?**
Yes: 82 Link Analysis routes (76 in `la-api`, 6 in `geo-link`), all in the OpenAPI contract, each behind the same
gate order (authenticate, rate limit, authorize, module enabled, capability, Investigation access). A guard test
keeps the route modules behind the module gate.
Evidence: backend §6 and [architecture-http-and-ui](architecture-http-and-ui.md) §6.1; `LaApiRoutesManifestParityTest`; `ControlApiGeoLinkModuleGateTest`.
Confidence: PROVEN. (The readiness plan still says 75 routes and 28 algorithms; those figures are stale.)

### 10.8 What we do NOT claim

Moved into [§4](#4-claims-we-do-not-make-and-what-is-not-yet), one list for the whole kit.

### 10.9 Presenter notes

* Never promise a number that is not in this page; point to the Evidence line instead.
* If asked about a claim marked NOT YET, answer with the row id and say it is on the board.
* Run the pre-demo checks in the demo runbook first; this page does not replace them.

### 10.10 Claims not verified (UNVERIFIED)

* The 19th concurrent Graph Run being refused 503: stated by backend §5; no test method was located by name.
* Q14's "rewrite everything consistently" reasoning is ours; no test.
* Q16, Q29: no single test class was identified for Drafts end to end or for the standing-detection Alert path.
* Q8: that nothing leaves the host: design statement; no egress test.
* Q27: a Link Analysis upgrade or restore rehearsal.
