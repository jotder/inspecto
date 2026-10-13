# Link Analysis demo runbook (rehearsable, stakeholder presentation)

*Status: written 2026-10-12 from the code, the seed script and the tests on `origin/master`. A scene marked
**UNVERIFIED (rehearse first)** has no test or smoke probe behind one of its claims. Rehearse the whole script once
on the exact bundle you will present, then again the evening before.*

What this is: the step-by-step script for presenting the Link Analysis (LA) demo on the **standalone Link Analysis app
bundle**. It defends the architecture page ([`okf/backend/modules/link-analysis.md`](../okf/backend/modules/link-analysis.md),
cited below as "arch §n") with the running application. The claim-to-evidence map and the requirement ids (DR-*) are in
[`superpower/la-demo-readiness-plan.md`](../superpower/la-demo-readiness-plan.md); every control label below is the
one on screen (see the [user manual](../ui/link-analysis-user-manual.md)). The variants (Personal bundle, Postgres
store) are in the [showcase variants runbook](la-showcase-variants-runbook.md).

---

## 0. Pre-flight (do this the day before, and again 30 minutes before)

| # | Step | Command / action | Expect |
|---|---|---|---|
| 1 | Build the bundle | `inspecto\package.ps1 -Edition Enterprise -DemoAuth -Ui la-app` | A demo bundle folder with `serve-demo.bat` / `serve-demo.sh` and `modules.list` naming `inspecto-demo-auth.jar` (not `inspecto-oidc.jar`). `-DemoAuth` refuses any edition but Enterprise. |
| 2 | Attach the Spaces | From a repo checkout: `node tools/seed-la-showcase.mjs --scaffold <bundle>\spaces` | `scaffolded: _templates, la-showcase, la-showcase-off`. **Bundles ship NO Spaces**; this lays down `la-showcase` (the demo) and `la-showcase-off` (LA switched off, used by the smoke runner's `MODULE_DISABLED` probe). To also show the tracked `demo` Space, copy `spaces\demo` beside them. Do it BEFORE the server starts. |
| 3 | Start | `serve-demo.bat` (Windows) or `./serve-demo.sh`; port is 8080, override with the `PORT` env var (`set PORT=8096`). Optional extra JVM flags: `INSPECTO_JAVA_OPTS`. | Banner `[serve-demo] DEMO BUILD on http://127.0.0.1:<PORT>`. Bound to 127.0.0.1 only. |
| 4 | Wait for the first ingest | The Space ingests `TRANSFERS_20260901..03.csv` from its inbox on boot. | The seed in step 5 retries a 422 "cannot read the columns" up to 30 times (2 s apart) until `mule_transfers_dataset` has rows. |
| 5 | Seed | `node tools/seed-la-showcase.mjs --base http://127.0.0.1:<PORT> --space la-showcase --apply` (a run without `--apply` is a dry run that prints every call) | Ends with `created: ...`, `already there: ...`, `failed: -`. Safe to re-run. See §0.1. |
| 6 | Smoke | `node tools/la-demo-smoke.mjs --base http://127.0.0.1:<PORT> --space la-showcase` | `PASS` lines then **`9/9 probes passed`** (6 gate probes + route inventory + untouched manifest verifies + tamper verify fails). Exit 0. Any `FAIL` line: stop and fix before presenting. |
| 7 | Optional wider smoke | add `--staged <bundle>\spaces\la-showcase\data\staged\mule_transfers --inbox <bundle>\spaces\la-showcase\data\inbox\mule_transfers` for replay-versus-drift (**writes** a far-future 40-row file into the inbox, so run it BEFORE the stakeholder day, not after); `--burst` for 429/503/409 (starts runs: disposable demo only); `--telecom [--presenter fm.manager]` for the telecom checks (triggers Jobs, creates a `smoke-telecom-<ts>` Investigation, 180 s timeout). | Each adds probes to the `N/N` count; a skipped probe prints `SKIPPED` and still counts as passing. |
| 8 | Embed (optional aside, S14) | Start with `set INSPECTO_JAVA_OPTS=-Dla.embed.enabled=true` | Without it the mint answers 503 and every public read answers 404. |

`serve-demo` flags: `--enable-native-access=ALL-UNNAMED -Dcontrol.bind=127.0.0.1 -Dauth.mode=demo -Dobjects.backend=db
-Devents.backend=parquet -Djobs.backend=duckdb` (from `package.ps1`). The two staged files in `data/staged` are NOT
ingested until you copy them into the inbox (used live only if you choose the drift scene, S2).

**Rebuilding or re-copying a demo Space: start it empty.** The audit anchor file lives OUTSIDE the config tree, in `<space>\config.secretsudit-anchors.jsonl`. If you re-extract a bundle or copy a Space folder but keep audit event rows older than yesterday while the `.secrets` folder is missing, the anchor roll logs `ERROR AuditAnchors ... the audit anchor file is missing but the chain holds rows from <date>` every 5 minutes and writes nothing. That is deliberate: a deleted anchor file is never silently re-signed. Fix: rebuild the Space with BOTH an empty event store and no `.secrets`, or copy both together. Never delete just the anchor file to silence it.

### 0.1 What the seed creates (read from `tools/seed-la-showcase.mjs`, not guessed)

* **Investigation `mule-hub-case`** over `mule_transfers_dataset` (payer `PAYER_ACCOUNT`, payee `PAYEE_ACCOUNT`, link kind
  `CHANNEL`, time `BOOKED_AT`), created as `fm.analyst` with 8 steps: seed `MULE-HUB-01`, `RELAY-01`, `RELAY-02`,
  `SMURF-01` (type `account`); a bounded expand (`maxFanOut` 5, either direction); exclude `RELAY-02` with a reason; annotate
  `MULE-HUB-01`; threshold 1; window 2026-09-01..2026-09-04; resolve; snapshot "showcase baseline".
* **Members:** `fm.analyst` is the owner (lead); `ra.analyst` reviewer; `fm.manager` reviewer; `admin` lead. `demo.manager` is
  deliberately NOT a member.
* **Entity Lists:** `smurf-accounts` (SMURF-01..SMURF-12, a `SMURF-` prefix range, and `TILL-06` expiring in 30 days);
  `telecom-watch-msisdn` (`99979100001`, `99979200001`).
* **Identity assertion:** `account:MULE-HUB-01` = `account:ACC-1006` ("same KYC document").
* **A Case** "Mule ring around MULE-HUB-01" from the hub, assigned to `fm.manager` and linked to the Investigation.
* **Investigation Template** `mule-ring-template`; **Alert Rule** `mule-pass-through` (CRITICAL, pass-through Measure over
  `AMOUNT`/`BOOKED_AT`, 2026-09-01..2026-09-04) with **standing detection** enabled.
* **From the Space Template (not the seed):** `link-analysis.toon` (`masking_mode: typed`, `four_eyes_fan_out_above: 2000`,
  `index.enabled: true`, Drafts max 10 open / hibernate 60 min / expire 14 d); Jobs `la_index_build` (on `pipeline.commit`
  of `mule_transfers`) and `la_detect` (cron every 5 minutes); saved views `mule_layering_ring`, `mule_structuring`,
  `mule_large_transfers`, `telecom_fraud_network`; telecom Datasets, packs and Alert Rules.

### 0.2 Demo Users and what each holds (`config/demo-users.toon`, `config/roles.toon`)

| Demo User | Role in the story | Capabilities that matter |
|---|---|---|
| `admin` | Platform admin; Settings; mints embed links; Reveal | admin role plus `la-settings` (`canAuthorWorkbench`); a lead member of the Investigation |
| `fm.analyst` | Lead analyst, owner | `canManageIncidents`, `canWorkIncidents`, `canRunLinkGraphAnalysis`, `canBuildLinkIndex`, `canAuthorAlertRules` |
| `fm.manager` | Second approver | `canManageIncidents`, `canWorkIncidents`, `canApproveLinkExpansions`, `canOperateRuns` |
| `ra.analyst` | Reviewer, read-only | none (`la-viewer`) |
| `demo.manager` | Not a member | none (`la-viewer`) |

## 1. Day-of checklist

* [ ] Server up, `9/9 probes passed` (§0 step 6) within the last hour; **no other Space with a different Demo User roster in `spaces\`**
      (see "if it goes wrong" below).
* [ ] Port free (default 8080, or the `PORT` you chose); open `http://127.0.0.1:<PORT>/` (not `localhost`, not another host: the
      launcher binds loopback only).
* [ ] Browser at 1440x900 or larger (the canvas is about 506 x 708 px with both panels open at 1440x900; below 1300 px wide the
      Toolbox starts collapsed). Hide bookmarks bar; zoom 100%.
* [ ] **Sign-in order** (each persona in its own browser profile or private window so sessions do not collide):
      `fm.analyst` (main window, scenes S1-S2, S5-S11), `fm.manager` (S8, S9), `admin` (S3, S4 Reveal, S13, S14), `ra.analyst` and
      `demo.manager` (S7). The sign-in page lists the Demo Users under "Demo sign-in / Not secure, local only".
* [ ] Have open: a terminal at the bundle with the `curl` commands of S7 and S14 ready; the architecture page; the 10^8 slide (S15).
* [ ] **Do not run the seed during the demo** and do not leave the telecom Jobs running a long trigger.

**If it goes wrong (global):**

| Symptom | Cause and fix |
|---|---|
| Sign-in page error "The server could not start a session" | The server's `/bootstrap` answered an error, so no sign-in can work. Read the server log. A frequent cause is the next row. Fix, then press "Try again". |
| Server log/boot error "Demo User '<id>' is defined differently in Spaces '<a>' and '<b>'" | Two Spaces under `spaces\` define the same Demo User with different rosters (the picker keeps one id = one person). Remove the Space with the other roster, or align the `demo-users.toon` files. |
| Screens say the Dataset "has no data yet" | The seed/ingest did not run: the first ingest of `mule_transfers_dataset` has not landed, or the Space was copied after the server started. Restart after copying the Space, wait for ingest, re-run the seed. |
| Link Analysis missing from the menu / "not installed" | Not an Enterprise bundle, or the Space has the module switched off (`la-showcase-off` by design). |

---

## 2. The script

Times are rehearsal targets. Persona in brackets. "Arch" = the architecture page section.

### S1. The graph and Suspicion score (2 min) [fm.analyst]
* **Click:** sign in as `fm.analyst` > *Link Analysis*. On the starter cards press **Follow the money (example)** (shown only when the
  Space has a fitting saved view; otherwise **Open a saved view** > `mule_layering_ring`). Toolbox > **Analysis** > **Suspicion score**.
* **Audience sees:** the layering ring drawn from the Dataset; the footer states nodes and links drawn plus the caps.
* **Expect (verified):** on the showcase corpus the hub ranks first (`LaShowcaseGoldenTest`, readiness plan §4); `mule_layering_ring`
  is described as 112 accounts over the three landed days (manual §10). The 170 nodes / 1,803 links / centrality `ACC-1008` figures of the
  readiness plan are for the **`demo` Space, not `la-showcase`**: do not quote them on this Space.
* **Proves:** exploration is a live projection over a Dataset; it is not evidence (manual §1; arch §1).
* **If it goes wrong:** blank canvas or "has no data yet" = seed/ingest not done (§1). "Truncated" banner is honest behaviour, narrow the query.

### S2. The Investigation ladder, Replay and drift (3 min) [fm.analyst]
* **Click:** Toolbox > **Investigation** tab > **Investigations** list > **Open** `mule-hub-case` (or **Open by id**). Scroll the ops ladder (8 steps).
  Press **Replay**. Then, optionally, land a new file (copy `data/staged/mule_transfers/TRANSFERS_20260904.csv` into
  `data/inbox/mule_transfers/`, wait for ingest) and press **Replay** again, then **Re-read current data (drift check)**.
* **Expect:** 8 steps as listed in §0.1; Replay reproduces the recorded Working Set hash. After a file lands, Replay's hash is unchanged and
  Re-read reports drift **only if the landed file touches entities the Investigation holds**: the shipped staged files touch no seeded entity, so
  they will NOT show drift. `la-demo-smoke.mjs --staged/--inbox` lands a 40-row `MULE-HUB-01` file to force drift (probes "replay hash
  unchanged after a file lands" and "Re-read reports drift after a file lands"). For a live drift demo use that kind of file.
* **Proves:** append-only sealed op log; pure fold; replay needs no Dataset; a Dataset change cannot rewrite history (arch §3.1, invariant 2).
* **If it goes wrong:** *Compare and seal* historically reloaded the page (DR-D1, fixed per the plan): the plan lists a sealed Compare as still
  to be driven live, so avoid it unless rehearsed. **Compare and seal: UNVERIFIED (rehearse first).**

### S3. Masked ids: typed versus all (2 min) [fm.analyst, then admin]
* **Click:** read the **Masking: typed** badge in the Query panel. In the Investigation, open the Working Set rows. To show `all`, sign in as
  `admin` > *Settings* (left rail) > Link Analysis > masking mode `all`, save, then reload the Investigation.
* **Expect:** under `typed` only values of Entity Types marked masked appear as `masked:...` aliases; under `all` every identifying value does
  (the badge reads `Masking: all (ids shown as aliases)`); aliases are stable, so you can still group and count by them. Under masking the
  Selected line keeps the query-graph node and its Working Set alias as one entity and marks it *(masked)* (manual §11). After a server restart
  an old alias is refused: re-run the query.
* **Proves:** tokens out, raw stays inside; `typed` default (arch §3.4, invariant 1).
* **Which ids are masked under `typed` on la-showcase is not pinned by a test I could find: UNVERIFIED (rehearse first)** which of
  `MULE-HUB-01` / `ACC-*` show as aliases. **Put the mode back to `typed` after the scene.**
* **If it goes wrong:** a changed mode needs `admin` (`canAuthorWorkbench`); `fm.analyst` sees the fields read-only.

### S4. Reveal is audited (1 min) [admin]
* **Click:** as `admin`, Investigation tab > **Oversight** > **Masked entities** > **Reveal** on one token.
* **Expect:** the raw id and the note "(reveal audited)". The Oversight section appears only when there is a request or masked entities and
  needs the reveal capability (`canRevealLinkEntities`, which `admin` holds; `fm.analyst` does not).
* **Proves:** Reveal is one entity at a time and recorded in the audit trail (arch §3.4, §6.1 step 7: `LINK_*` audit events).

### S5. Graph Run on the server (2 min) [fm.analyst]
* **Click:** Toolbox > **Analysis** > a group offering **Run on server** (for example Pattern match > **Structuring (smurfing)** > **Run on server**,
  or the **All algorithms (server)** group). **Run on index** is offered where the algorithm supports it.
* **Expect (verified):** Pattern match **Structuring** finds `MULE-HUB-01` with 96 in-band legs from 12 payers
  (`ControlApiLaShowcaseFiguresTest.sectionTenFiguresHoldOverTheShowcaseCorpus`).
* **Proves:** Graph Run is a server-side, budgeted, pooled run (pool 2 threads, queue 16; arch §5, §9). The 19-concurrent-runs 503 is
  `--burst`, not a live scene; on this corpus a SYNC algorithm finishes inline, so the pool is unreachable (the smoke runner says so and skips).
* **If it goes wrong:** server tools are gated by the *Show the query graph* checkbox and the *Run link graph analysis* capability
  (`fm.analyst` has it; `ra.analyst`/`demo.manager` do not). Press **Seed** first, then untick *Show the query graph*.

### S6. Value Measures: pass-through and cash-out (3 min) [fm.analyst]
* **Click:** Toolbox > Value Measures (Dataset, link columns and `BOOKED_AT` are prefilled from the open Investigation). Choose
  **Pass-through**, From `2026-09-01`, To `2026-09-04` (To is exclusive), **Run Measure**. Then **Cash-out concentration** with cash-out kind `cash_out`.
* **Expect (verified, `ControlApiLaShowcaseFiguresTest`):** Pass-through flags **13 accounts**; the two relays sit at ratio **0.985**
  (exactly two at 0.985); `MULE-HUB-01` is in the list (manual: 0.979; `SHELL-A` above 1). Velocity lists at least 3 fast forwarders. Cash-out
  concentration: `TILL-06` holds **97 %** (test tolerance 0.97 +/- 0.005) of cash-out value from **8 payers**. Benefit-transfer isolates `SKIMMER-01`
  with **6 recipients**.
  **Names depend on masking:** under typed masking (the demo's default for `fm.analyst`) the measure returns `masked:<hex>` ids, so `MULE-HUB-01` / `TILL-06` /
  `RELAY-*` read as tokens; the counts, the two 0.985 scores, the 97 % and the 8 payers hold unchanged. `node tools/la-demo-figures.mjs` checks them that way,
  and checks the names too when run `--user <a persona that holds reveal>`.
* **Contradiction to be aware of:** the readiness plan's DR-T6 row says `TILL-06` "at 82 %". The test and the manual say **97 %**. Quote 97 %.
* **Proves:** whole-Dataset behaviour detectors are server-computed and read the whole Dataset, not the filtered view (manual §4.3, §9.8).
* **If it goes wrong:** **Run Measure** stays disabled until the form is valid; the window is capped at 31 days, evaluated in UTC; 413/429 have plain
  sentences (burst of 20 then one request per 3 s).

### S7. Members, roles and gates (3 min) [fm.analyst, ra.analyst, demo.manager, curl]
* **Click:** as `fm.analyst`, Investigation header > **Members**: owner/lead `fm.analyst`, `admin` lead, `ra.analyst` and `fm.manager` reviewers. Then show
  the gates with `curl` (token from the demo exchange: `POST /api/v1/auth/exchange` with `{"code":"demo:<id>","codeVerifier":"x","redirectUri":"..."}`;
  the smoke runner does exactly this):
  * no token `GET /api/v1/spaces/la-showcase/inv/investigations` -> **401**
  * `demo.manager` `GET .../inv/investigations/mule-hub-case/log` -> **404** (non-member: 404-as-absence, same answer as "does not exist")
  * `ra.analyst` `GET .../inv/entity-identities` -> **403**
  * `fm.analyst` `POST .../inv/investigations` with `{}` -> **422**
  * `fm.analyst` `GET` on the `la-showcase-off` Space -> **404 `MODULE_DISABLED`**
  * `fm.analyst` `GET .../mule-hub-case/log` -> **200**
* **Expect:** exactly those statuses (all six are smoke probes, `9/9`). `ra.analyst` in the UI: the Investigation tab is read-only (Seed, Expand, Undo,
  Fork a Draft disabled with a note saying why); a mutating `curl` as `ra.analyst` is 403. Members: a lead can **Grant**/**Revoke**; revoking the last
  lead is refused.
* **Proves:** members and roles; non-members get 404-as-absence, a member lacking the role gets 403 only after the Dataset-visibility and policy checks
  (arch §3.1, §6.1 gate order step 6).
* **If it goes wrong:** a 401 on a persona's token means the demo exchange was refused: wrong build (not `-DemoAuth`) or the Space does not define that user.

### S8. Four-eyes: a sensitive expand waits for a second person (3 min) [fm.analyst, fm.manager]
* **Click:** as `fm.analyst`, Investigation tab > select `MULE-HUB-01` > **Expand one hop** with **Maximum fan-out per entity** left **blank**
  (blank = unbounded; the Space threshold is `four_eyes_fan_out_above: 2000`, and an unbounded fan-out exceeds any fan-out threshold).
  The panel answers **Waiting for approval**. Switch to `fm.manager` (holds `canApproveLinkExpansions`), open the same Investigation (a reviewer), Investigation
  tab > **Oversight** > the request ("Expand request ...", "Four-eyes threshold crossed: maxFanOut unbounded (threshold 2000)") > **Approve**.
* **Expect:** nothing enters the log until `fm.manager` approves; the Oversight section shows who requested it and when; on approval it runs as a
  new step "sealed with who approved it". `fm.analyst` never sees the request as decidable ("Waiting for a different person with the approve-expansions
  capability.") and the server refuses a self-decision. The seeded bounded expand (`maxFanOut` 5) did NOT trigger this: only the unbounded one does.
* **Proves:** four-eyes by construction: a pending request is never written to the log (arch §3.1 "Pending approvals"; `ControlApiInvestigationFourEyesRaceTest`,
  `ControlApiInvestigationApprovalInboxTest`).
* **If it goes wrong:** "no pending request visible to the approver" = the approver is not a lead/reviewer of this Investigation (`fm.manager` is a reviewer by
  the seed) or you expanded with a fan-out at or below 2000 (it just runs). One pending request per Investigation: a second is a 409. A hub (more than 500
  distinct contacts) is added but flagged *high connectivity* and not expanded further (manual §9.2).
  **The exact on-screen text and the approve-then-step flow in the standalone la-app: UNVERIFIED (rehearse first)**: the plan records the Oversight panel
  as a gap closed on the gamma host and the live la-showcase four-eyes pass is not recorded.

### S9. Drafts: fork, step, rebase, promote (3 min) [fm.analyst, fm.manager]
* **Click:** as `fm.analyst`, Investigation tab > **Drafts** > **Fork a Draft** > add a step in the Draft > **Working Set** (size) > **Conflicts** ("No conflicts - a
  rebase would carry every step.") > **Rebase** > **Promote**. For the four-eyes variant, put a sensitive expand in the Draft and **Promote**: it is held and `fm.manager`
  approves it under **Oversight**.
* **Expect:** one live Draft per member, at most 10 open in this Space (`drafts.max_open: 10`); a Draft shows a state badge (open, hibernated) and how many steps the
  Investigation has moved. Conflict classes: no-op, changed, superseded, blocked. A **sensitive expand cannot be added to a Draft directly** (422: four-eyes
  applies to the main log); it meets four-eyes at promote.
* **Proves:** Drafts live inside their Investigation; rebase and promote are all-or-nothing under the main lock (arch §3.3).
* **UNVERIFIED (rehearse first):** the plan says the Draft cycle was driven live on 2026-10-11 ("Members, the Draft cycle and DR-D1"), but a full
  fork/step/rebase/promote with a held four-eyes promote on la-showcase in the la-app bundle has no recorded run or smoke probe.

### S10. Entity Lists and the identity head hash (2 min) [fm.analyst]
* **Click:** the left rail > **Entity Lists** > `smurf-accounts` (12 members plus the `SMURF-` range and expiring `TILL-06`); the **Identity Fact log** panel.
* **Expect:** the head (fact number and hash) and the success banner **Chain verified**. The seeded assertion `MULE-HUB-01` = `ACC-1006` appears in
  Identity resolution. Note the head hash; cutting the end of the chain leaves a shorter valid chain, so compare later. A broken chain shows **Chain NOT verified**.
* **Proves:** the Identity Fact log is append-only and hash-chained (arch §3.5). The hash is verified on every read.
* **If it goes wrong:** `ra.analyst` gets 403 on identities (S7). The tamper-evidence script for this log (DR-T10) is not a live scene: **tampering: UNVERIFIED (rehearse first).**

### S11. Dossier build, verify and the tamper check (3 min) [fm.analyst]
* **Click:** Investigation tab > **Dossier** > build; **Download manifest**; **Verify** (shows "Verified" / "Log intact"). Then **Verify an uploaded manifest...**
  with a copy of the manifest in which one hash digit was changed. Optional: **Download sealed bundle** and **Verify a bundle...** on an edited copy.
* **Expect (verified by smoke):** the untouched manifest verifies; a manifest with one hash flipped fails (`verified: false` or HTTP 422; the UI shows "NOT verified" /
  "Bundle NOT verified"; an edited bundle fails the seal "edited after export"). Verify reports anything changed, missing or added.
* **Proves:** SHA-256 manifest over every stored artefact; tampering is detectable (arch §3.1 outputs).
* **If it goes wrong:** the dossier route needs a seeded Investigation (else 404). Hand-editing JSON in front of the audience is error-prone: prepare the tampered file beforehand.

### S12. Alert to Investigation (2 min) [fm.analyst; needs the full SPA]
* **Click:** open the Alerts page, find the Alert from the Investigation rule `mule-pass-through`, press **Open Investigation**.
* **Expect:** the Alert carries aggregate facts only (rule, Measure, threshold, breach count, worst value); **no entity id, name or alias**; entities are read inside the
  Investigation under its own gates and masking (arch §7, `LA-DETECT-ALERT-AGGREGATE-1`). Standing detection runs from the `la_detect` Job every 5 minutes; the rule
  is edited => the sweep refuses with `NOT_ENABLED`.
* **UNVERIFIED (rehearse first):** (a) the **Alerts page and its Open Investigation action live in the gamma host SPA, not in the la-app** (the la-app has only
  Link Analysis, Geo, Entity Lists and Settings), so this scene cannot be shown inside the standalone app; (b) the readiness plan lists "Alerts rendering of a fired
  standing-detection Alert" as not verified; (c) the Alert only exists after `la_detect` has run on a breaching rule. Alternative inside la-app: Investigation tab >
  the bound Alert Rule / standing-detection panel and Monitoring.

### S13. Settings (admin) (1 min) [admin]
* **Click:** as `admin`, left rail > **Settings** (shown only with `canAuthorWorkbench`).
* **Expect:** per-Space limits: four-eyes thresholds, hub threshold (default 500), masking mode with the mode in force, the link index (enabled, build workers,
  waiting line, disk budget, `index.max_age`), the server graph-run budget with the server's ceilings, the Draft limits, the embed-link limits and the
  Entity Types in force; the active Investigation store (filesystem or PostgreSQL) read-only. Without the capability every field is read-only (try as `fm.analyst`).
* **Proves:** limits and bounds are Space settings (arch §9). Editing live and the Edge index first-build flow: the plan lists an editing Settings pass as open:
  **editing: UNVERIFIED (rehearse first)**; show read-only if you have not rehearsed.

### S14. Embed API aside, API only (2 min) [admin by curl]
No SPA exists for embed links (the SPA shell is deferred). Start the server with `-Dla.embed.enabled=true` (§0 step 8). Mint needs `canShareInvestigation` AND a lead
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
  (`fm.analyst` has no such capability in this Space); **422** = a ttl above the Space cap (default cap 720 h) or a refused classification; **404** on the public
  GET straight after a mint = the flag is off on the serving JVM. The exact path prefix of the public URL (`/api/v1/public/investigations/<token>`) is taken from the
  test's request base, so confirm it once with the `url` in the mint response. **The curl transcript itself was not run by this lane: UNVERIFIED (rehearse first).**

### S15. Evidence slide: scale and fences (1 min) [slides]
Not live. Link index: full build **852 s**, append **31 s** on a synthetic **10^8-edge** corpus (1.8 M nodes), measured; **10^9 is an extrapolation** (arch §10,
`LA-INDEX-SCALE-MEASURE-1`). Fences: `tools/check-module-deps.mjs` and maven-enforcer (arch §2). Both from the readiness plan, not demonstrable on this data.

---

## 3. Ten-minute short version

| Min | Scene | Persona |
|---|---|---|
| 0-1 | Pre-flight already green (`9/9 probes passed`); sign in | `fm.analyst` |
| 1-3 | S1 Follow the money + Suspicion score; S5 Pattern match > Structuring > Run on server (hub, 96 legs, 12 payers) | `fm.analyst` |
| 3-5 | S6 Value Measures: Pass-through (13 accounts, relays 0.985) and Cash-out (`TILL-06` 97 %) | `fm.analyst` |
| 5-7 | S2 open `mule-hub-case`, Replay; S8 unbounded expand -> Waiting for approval -> `fm.manager` approves | `fm.analyst`, `fm.manager` |
| 7-8 | S11 Dossier: Verify, then the prepared tampered manifest -> NOT verified | `fm.analyst` |
| 8-9 | S7 the 401/404/403/422 `curl` block | terminal |
| 9-10 | S15 evidence slide; take questions; S14 only if asked | slides |

## 4. Claims we do NOT make

* **Scale:** proven to 10^8 edges (measured, synthetic corpus), **10^9 is an extrapolation**. The live demo corpus is small (about 1,800 ordinary
  transfers plus the planted ring); it demonstrates behaviour, not scale.
* **Real IAM:** no real-IAM sign-in is verified on this bundle. Demo Users sign in with no password on a loopback-only, `-DemoAuth` build, which refuses
  to run bound elsewhere. Do not call it a security demonstration of SSO.
* **Embed UI:** not built. Embed links are API-only (S14); the SPA shell and host-wide frame headers are deferred.
* **Fences:** the "engine names no la-* class" rule is grep-true but unguarded (DR-T3); the dependency fences are shown by a named guard, not live.
* **Postgres store, Personal 503, 429/503 bursts:** other variants (see the variants runbook) or `--burst`, not part of this script.
* **A fired Alert names entities:** it does not, by design (aggregate only).
* **Alerts inside the standalone app:** the Alerts page is in the full SPA, not the la-app.

## 5. Contradictions found between sources (as of 2026-10-12)

1. **`TILL-06` share:** readiness plan DR-T6 says 82 %; `ControlApiLaShowcaseFiguresTest` and the manual say 97 % (8 payers). The test wins.
2. **Step 1 figures:** the plan's "170 nodes, 1,803 links, centrality `ACC-1008`" belong to the `demo` Space; the plan's own seed note says on the
   showcase the hub ranks first. Do not quote 170 / 1,803 on `la-showcase`.
3. **Seeded four-eyes:** the plan's DR-S2 acceptance says "an unbounded expand of the hub creates a pending request"; the seed's own 8 steps use a bounded
   expand (`maxFanOut` 5) so none is pending after seeding. The scene S8 therefore creates the request live.
4. **Smoke wording:** the task brief expects "N/N pass"; the runner prints `N/N probes passed`.
5. **Alert -> Open Investigation** is described by the plan and manual as part of the product but is rendered by the gamma host SPA only, not the la-app.
