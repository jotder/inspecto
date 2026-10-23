# Inspecto LA (Link Analysis) demo runbook (rehearsable, stakeholder presentation)

*Status: written 2026-10-12 from the code, the seed script and the tests on `origin/master`. A scene marked
**UNVERIFIED (rehearse first)** has no test or smoke probe behind one of its claims. Rehearse the whole script once
on the exact bundle you will present, then again the evening before.*

What this is: the step-by-step script for presenting the Inspecto LA demo on the **standalone Inspecto LA app
bundle**. It defends the architecture page ([`okf/product/module/la-core/architecture.md`](architecture.md),
cited below as "arch §n") with the running application. The claim-to-evidence map and the requirement ids (DR-*) are in
the archived [demo readiness plan](../../../../archived-documents/plans-archive/la-demo-readiness-plan.md) (all built by 2026-10-13; history only). The
technical / security review cut of this script (T1-T13) is section 9 below: rehearsed by the
assistant alone on 2026-10-24, the sceptical-reviewer rehearsal is still owed. Every control label below is the
one on screen (see the [user manual](user-guide-manual.md)). The variants (Personal bundle, Postgres
store) are in the [showcase variants runbook](../../solution-pack/la-showcase/admin-guide-variants-runbook.md).

---

## 0. Pre-flight (do this the day before, and again 30 minutes before)

| # | Step | Command / action | Expect |
|---|---|---|---|
| 1 | Build the bundle | `inspecto\package.ps1 -Edition Advanced -DemoAuth -Ui la-app` | A demo bundle folder with `serve-demo.bat` / `serve-demo.sh` and `modules.list` naming `inspecto-demo-auth.jar` (not `inspecto-oidc.jar`). `-DemoAuth` accepts only Advanced or Preview. |
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
| Link Analysis missing from the menu / "not installed" | Not an Advanced or Preview bundle, or the Space has the module switched off (`la-showcase-off` by design). |

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
  `data/inbox/mule_transfers/`, wait for ingest) and press **Replay** again; for drift tick the **Re-read current data (drift check)** checkbox, then **Replay** (rehearsed 2026-10-24: "Replay is equivalent", then a Drift table, step 2, 8 sealed rows, 8 now, "changed").
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
  Selected line keeps the query-graph node and its Working Set alias as one entity and marks it *(masked)* (manual §11). An alias keeps
  working after a server restart.
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
  **Pass-through**, From `2026-09-01`, To `2026-09-04` (To is exclusive), **Run Measure**. Then the concentration Measure with cash-out kind `cash_out` (on the Telecom Domain profile of this Space the picker calls it **Withdrawal concentration**; the 97 % is checked by `node tools/la-demo-figures.mjs`).
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
send the right `frame-ancestors` (see `okf/product/module/la-core/architecture.md` §6.2); a POST exchange is a new route and a new client flow. Both are a larger change than the redaction rule above, so this stays a documented operator duty.

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
* **Embed UI:** built (mint dialog, grant list, `/embed/inv/<token>` viewer, `-Dcontrol.frameDeny`, Space `embed.frame_ancestors`); this S14 script drives only the API. For a real deployment read *Embed links behind a reverse proxy* below.
* **Fences:** the "engine names no la-* class" rule is guarded in CI by `tools/check-engine-names-no-la.mjs` (DR-T3); the dependency fences are shown by a named guard, not live.
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

## 6. Rehearsal record: fresh full bake, 2026-10-13

Bundle: `package.ps1 -Edition Advanced -DemoAuth -Ui la-app` WITH the built-in bake, built from `origin/master` (`79212c613` plus later docs); served with `serve-demo.bat PORT=8107` and `-Dla.embed.enabled=true`; driven in the built-in browser as the Demo Users. Scripted smoke: `15/16` plain and `29/30` with `--telecom --embed`; the single FAIL is the `la-showcase-off` probe, because a baked bundle ships no `la-showcase-off` Space (expected, see §0 step 2).

| # | Beat | Result | Evidence |
|---|---|---|---|
| 1 | Bake path | PASS | Bake booted, seeded, rebased 357 files; the old spurious 409 now logs "investigation: 409, now readable (head step 0)" and carries on |
| 2 | Sign in as `fm.analyst` (full reload returns to the deep link) | PASS | picker lists 5 Demo Users; Link Analysis menu present |
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
| 14 | Masked persona view | PASS | `fm.analyst` sees `masked:<hex>` everywhere; `ra.analyst` read-only 403 and non-member 404 are smoke probes. Admin Reveal not re-driven this pass |
| 15 | Four-eyes (S8) | PASS | the bake leaves a pending request `p1`; `fm.analyst` approve is 403, `fm.manager` approve is 200 and runs the expand |
| 16 | *Investigate a number* `99979100001` | PASS with a note | two degrees in seconds, but 895 nodes / 2,901 links (hairball); fixed: the card expands one degree, *Choose neighbours* goes further (`LA-INVESTIGATE-NUMBER-HAIRBALL-1`) |

Not rehearsed this pass: Drafts cycle, Compare and seal, Settings editing, embed curl transcript (S14), Postgres/Personal variants.


## 7. Final go/no-go rehearsal: fresh full bake, 2026-10-14

Bundle: `package.ps1 -Edition Advanced -DemoAuth -Ui la-app` with the built-in bake, from `origin/master` `66f63ef73`, unzipped to a fresh folder and served with `serve-demo.bat` (`PORT=8119`, `-Dla.embed.enabled=true`); driven in the built-in browser as the five Demo Users. Scripted smoke `la-demo-smoke.mjs --embed --telecom`: **29/30**, the single FAIL is the expected `la-showcase-off` probe (a baked bundle ships no such Space).

> **Build trap (new):** the bake's graceful stop sends Ctrl+C by attaching to the server's console, so `package.ps1` run from a console-less host (an agent shell, a CI step without a console) fails with "the server did not stop gracefully ... within 45 s" **every time**. Run the build from a real interactive terminal.

| # | Beat | Result | Evidence |
|---|---|---|---|
| 1 | Sign in as `fm.analyst` | PASS | picker lists 5 Demo Users; menu has Link Analysis, Geo, Entity Lists, Alerts |
| 2 | *Investigate a number* `99979100001` (purpose required) | PASS | one degree: 403 nodes, 403 links, a star, not a hairball (that number has 402 contacts) |
| 3 | Legend hide/show a link kind | PASS | `forwarding` hidden 403 -> 402 links, shown 403 |
| 4 | Unexpanded marker and *Only unexpanded* | PASS | legend count 402; only-unexpanded draws 402 nodes, 0 links; off restores |
| 5 | Expand one node with direction and link kind | PASS | step text "direction out; link kinds voice", 2 link rows |
| 6 | *Choose neighbours* (small node, then the hub) | PASS | 6 neighbours; *Add the 10 strongest* step "restricted to 6 chosen neighbours"; hub list "402 neighbours", *Show more* pages 50 per press to 402 |
| 7 | *Connect seeds* (5 numbers) | PASS | "8 of 10 pairs connected within 3 hops; 2 not connected", routes tabulated, budget banner shown |
| 8 | *Expand from these nodes (8)* | PASS | one sealed step, 407 -> 782 nodes, "161 more left out by the fan-out cap" stated |
| 9 | *All algorithms (server)* | PASS | 29 algorithms; PageRank 782 nodes in 28 ms; Louvain 8 communities |
| 10 | Undo, then reload | PASS | "Undid step 6", 782 -> 407; after reload the same 7-step log and Working Set |
| 11 | Members | PASS | grant `demo.manager`, revoke it, last-lead revoke refused with the server wording |
| 12 | Drafts | PASS | fork, step, Conflicts ("No conflicts"), Rebase, Promote -> step 8 on the Investigation |
| 13 | Dossier build and verify | PASS | "Log intact", "Verified", sealed bundle download 200 |
| 14 | References | PASS | `jira/ticket FM-1042` added, "1 of at most 200" |
| 15 | Compare and seal | PASS | needs From/To on both windows (inline hint says so); sealed step 9 with fingerprint |
| 16 | Embed: mint, open without a session, revoke (admin) | PASS | mint dialog shows the link once; the viewer opens masked, read-only, token scrubbed from the address bar; after confirming *Revoke* the public GET is 404 |
| 17 | Admin Reveal | PASS | `MULE-HUB-01 (reveal audited)` |
| 18 | Four-eyes (S8) | PASS | the baked request `p1` waits; `fm.manager` *Approve* -> step 9 "requested by fm.analyst, approved by fm.manager", 9 -> 15 entities |
| 19 | Detection and an Alert | PASS | Alerts page lists `mule-pass-through` (13 breaching); `fm.analyst` *Open Investigation* lands on `mule-hub-case` |
| 20 | Masked persona view | PASS | `ra.analyst`: 30 `masked:<hex>` ids, `MULE-HUB-01` absent; mutating buttons disabled with reasons |
| 21 | Coverage display | PASS | "Every day has data", `mule_transfers` 3 of 3 |
| 22 | Settings as non-admin | PASS | `fm.analyst` and `ra.analyst`: no rail link, `/settings` has no editable field and no Save; `admin`: 26 editable fields |

Noted, not filed (cosmetic, no functional effect): the console logs "Unable to preventDefault inside passive event listener" on canvas wheel/zoom; the Add-reference form keeps System and Type after a successful add; the *Open Investigation* action was not seen in the `fm.manager` Alerts view (not investigated: `fm.analyst` has it).

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
* **Four-eyes beat.** A plain degree expand is bounded by the UI default and does not wait for approval. Use *Expand
  the whole Working Set* (or an expand with no ids) to show the held request.
* **Bundle.** `package.ps1 -Edition Advanced -DemoAuth -Ui la-app -NoBakeDemo`. The la-app UI has no Incidents or Cases
  page, so show the Alert, Incident and Case through the full SPA or by API. The saved-view picker opens its submenu on
  hover; a plain click on the row only closes the menu.

## 9. Technical / security review cut (T1-T13) and its rehearsal record

For a CISO, lead architect and forensic reviewer: every PROVEN row of the [whitepaper](brochure-inspecto-la-whitepaper.md)'s Claims Register shown live or by a named test, every NOT YET row said out loud. It reuses this runbook's scenes (S1-S15), personas and seed. Run of show: 45 minutes at rehearsal pace; the 35-minute cut drops T10 and T12 and shortens T3; never cut T5, T6, T7, T9. (Moved here from the roadmap §D2 on 2026-10-24; section numbers are the whitepaper's current ones, checked against its headings that day.)

| # | Claim (whitepaper §) | Scene | Evidence if challenged | Rehearsal 2026-10-24 |
|---|---|---|---|---|
| T1 | In-process, no external graph database (§4.1) | S1 | dependency fences: `tools/check-module-deps.mjs`, `tools/check-engine-names-no-la.mjs` | PASS: both guards green (13 extracted modules, enforcer rules match); the bundle's jars hold `inspecto-graph` and `inspecto-la-*`, no graph database |
| T2 | Explainable Suspicion score, Propagated Risk (§3.2-§3.3) | S1 | `GraphSuspicionParityTest`, `ControlApiGraphRunTest` | **PARTIAL**: `mule_layering_ring` (113 nodes, 225 links after the drift file) ranks the hub first (the same token as its 0.979 pass-through row); the per-node **Factors** column appears only when a Risk Score model name is typed and no showcase Space ships one (row `LA-SUSPICION-FACTORS-DEMO-1`) |
| T3 | 29 algorithms, TypeScript and Java parity (§3.1) | S5 | `GraphAlgorithmsParityTest` | PASS: "29 algorithms on the server"; client and server agree on every algorithm compared in the [tour](../../solution-pack/la-showcase/admin-guide-cross-vertical-tour.md) section 1.3 |
| T4 | Value Measures, the 97 % cash-out (§3.4) | S6 | `ControlApiLaShowcaseFiguresTest` | PASS: Pass-through 2026-09-01 to 2026-09-04: 13 entities, the two relays at 0.98499982, the hub at 0.9793; the 97 % is `node tools/la-demo-figures.mjs` (2/2: Pass-through 13, TILL-06 at 97 %), not driven in the form (the picker resets while selecting) |
| T5 | Keyed masking, audited Reveal (§5.2, §5.4) | S3, S4 | `ControlApiExplorationMaskingTest`, `ControlApiInvestigationOversightTest` | PASS: tokens only for `fm.analyst`; `admin` Reveal answers "→ MULE-HUB-01 (reveal audited)"; Settings > Link Analysis masking `all` saved and "In force: all", then restored to `typed`. Under `typed` every Account id is already masked on this Space, so typed versus all differ only for an unmasked Entity Type |
| T6 | Gate order, strict 404 (§5.1) | S7 | `ControlApiGeoLinkModuleGateTest`, `ControlApiInvestigationMembersTest` | PASS: smoke 401 no token, 403 `ra.analyst`, 404 non-member, 404 `MODULE_DISABLED`, 422 bad create, 200 member (the six probes; 20/20 with the drift and embed probes) |
| T7 | Four-eyes, self-approval refused (§5.3) | S8 | `ControlApiInvestigationOversightTest` | PASS: `fm.manager` Approve: step 9 "requested by fm.analyst, approved by fm.manager", 9 to 15 entities; the requester sees "Waiting for a different person"; `audit.lead` and `audit.committee` approve answer 403 `canApproveLinkExpansions` (the same-person refusal is the test) |
| T8 | Op log, replay, drift on file append (§2.2) | S2 | `ControlApiReplayDriftOnFileAppendTest` | PASS: *Replay* "Replay is equivalent"; ticking *Re-read current data (drift check)* then *Replay* adds a Drift table (step 2, 8 sealed rows, 8 now, "changed"). The smoke's far-future file moves the Data strip to "27 Apr 2033": run it before the day |
| T9 | SHA-256 Dossier, tamper-evident (§2.3) | S11 | `ControlApiDossierTest` | PASS: untouched manifest Verified; one flipped digit uploaded: NOT verified, Changed `log.jsonl#2` (B) and `mm-structuring-case` (A) |
| T10 | Drafts, atomic promotion (§2.4) | S9 | Draft tests | PASS: Conflicts "No conflicts", Rebase, Promote (step 7), Discard; Discard takes no confirmation |
| T11 | Ephemeral embed, revoke (§5.5) | S14 | `ControlApiInvestigationEmbedTest` | PASS: link shown once, viewer masked and read-only at `/embed/inv/shared`, revoke asks to confirm, public read 404; smoke headers `frame-ancestors 'none'`, DENY, no-store; the claims partner portal frames the view |
| T12 | Data trust strip (§4.6) | - | [`tests-data-trust-live-drive.md`](tests-data-trust-live-drive.md) | PASS: "Data as of … · 3 of 3 days · index v3" on `mule-hub-case`; on a Space whose index is still building: "days not assessed · no index" |
| T13 | Scale and limits (§6) | S15 | `InvTraversalBench`; [`tests-index-scale-run.md`](../la-storage/tests-index-scale-run.md) | evidence only (a slide): the bench class and the scale-run page exist; nothing driven live |

Say out loud: 10^9 edges, retention purge (D-U8), precision rates, multi-pod HA, native Hive / Kerberos (NOT YET); the demo build signs in Demo Users, so it is no SSO demonstration; a full-disk consistent rewrite (anchor the Dossier root externally). Prepare a tampered manifest (T9), a late file (T8), an admin token (T6, T11), two browser profiles (T7) and a recorded fallback for T8, T9, T11.

**Rehearsal record, 2026-10-24 (assistant alone, no second person).** Bundle `package.ps1 -Edition Advanced -DemoAuth -Ui la-app -NoBakeDemo -SkipBootCheck` from `31539d9c5`, four Spaces scaffolded on one server (port 8171, `-Dla.embed.enabled=true`), Demo Users only, synthetic data only; driven in the built-in browser. The T-scenes ran as written except where the table says otherwise. Defects found are on the board (`LA-INVESTIGATE-CARD-FOUR-EYES-HOLD-1`, `LA-BOUND-RULE-AUTHOR-READONLY-1`, `LA-SUSPICION-FACTORS-DEMO-1`, `LA-SEED-FIRST-RUN-1`, `LA-REVOKE-DISCARD-NO-CONFIRM-1`, `LA-DEMO-PICKER-NAMES-1`, `LA-CLAIMS-PROVIDER-MONEY-LAYOUT-1`, `LA-SAVED-VIEW-DOMAIN-PROFILE-1`); runbook errors found were corrected in place (S2 drift is a checkbox, S6 label, the audit Maximum flow of 3, the mobile-money index trigger persona).

**Still owed (row `LA-WHITEPAPER-DEMO-1`):** (1) one spoken rehearsal with a sceptical second person, timed against 45 and 35 minutes: an assistant cannot be its own reviewer, and nothing here was timed against an audience; (2) the operator's confirmation of the two calls below.

**Calls decided by the assistant, pending operator confirmation (2026-10-24):**

* **IdP or Demo Users: Demo Users for a rehearsal.** It is the safest reading: no credential is typed and no external identity provider has to be reachable in the room. Say out loud that this build is no SSO demonstration. For the real review the operator may choose a real IdP sign-in instead (verified on la-app 2026-10-13, `LA-APP-REAL-SIGNIN-1`; [`admin-guide-real-signin.md`](admin-guide-real-signin.md)).
* **T12 stays in the technical cut** (it is dropped only in the 35-minute cut, with T10).
