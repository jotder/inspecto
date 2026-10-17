# Inspecto LA (Link Analysis) showcase B: Finance and audit (runbook, synthetic data only)

**Status: BUILT and LIVE-REHEARSED 2026-10-16 on a booted Space (Enterprise Demo Auth bundle, port 8141): smoke 30/30, the HTTP routes and the headline browser scenes driven; what was not driven is listed in section 6.** Vertical B of the
[multi-vertical showcase plan](../superpower/la-multi-vertical-showcase-plan.md): "procure-to-pay and the journal". The analyst's questions: is this vendor real, who approved,
is the control chain sound? The headline is the **Dossier as an audit workpaper**: seal it, verify it, change one byte, watch Verify fail.
Everything is invented (names, bank accounts, addresses, ids). The plants are labelled as planted; nothing here is a detection-rate claim.
The stakeholder-facing sibling script is the [demo runbook](la-demo-runbook.md) (mule transfers and telecom); this Space is separate and shares none of its files.

## 0. Prerequisites

| # | Step | Command / expectation |
|---|---|---|
| 1 | Bundle: the Demo Auth build with the la-app UI | `inspecto\package.ps1 -Edition Advanced -DemoAuth -Ui la-app -NoBakeDemo` (`-DemoAuth` refuses every edition but Enterprise, Preview included); unzip it inside the checkout, not under `%TEMP%` |
| 2 | Lay the Space down (offline, before the server starts) | `node tools/seed-la-audit.mjs --scaffold <spacesRoot>` creates `la-audit` and the template gallery |
| 3 | Start the server with `-Dla.embed.enabled=true` (only for the Embed scene) | without it the mint answers 503 |
| 4 | Wait for the Jobs: `ap_links` runs on a 10-minute cron; its three derived Jobs and `la_index_build` follow it by signal | or press Run on each as `audit.manager` |
| 5 | Seed the runtime state | `node tools/seed-la-audit.mjs --apply` (dry run without `--apply`; safe to re-run). It first presses Run on the link Jobs (a fresh Space has no link rows until the 10-minute cron), then creates two Investigations: `vendor-v117-workpaper` (the workpaper) and `approval-control-review` (the algorithm scenes: E-07 and V-001 expanded twice, 163 nodes and 281 links, so Run on server has a Working Set) |
| 6 | Smoke | `node tools/la-audit-smoke.mjs` (pins every planted fact against the live Datasets, then builds, verifies and tampers the Dossier) |

Regenerate the corpus any time with `python tools/gen-la-audit-data.py` (byte-identical each run; `LaAuditGoldenTest` pins every plant, `node --test tools/gen-la-audit-data.test.mjs`
pins determinism). Size: 150 vendors, 60 employees, 1 804 invoices and as many approvals over 12 months (Oct 2025 to Sep 2026), 40 journals, under 0.3 MB of CSV.
The next month (`data/staged`, 40 invoices on existing vendor and approver pairs only) is copied into `data/inbox/ap_invoice` and `data/inbox/ap_approval` for a live "new data lands" beat.

## 1. The cast (Demo Users)

Ids are `audit.*` on purpose: the demo-auth roster refuses one id defined differently in two Spaces, so these never collide with `la-showcase`.

| Demo User | Role | Can | Cannot |
|---|---|---|---|
| `audit.lead` | audit-lead (Lead auditor) | work Investigations, run Graph Runs, build the index, author Alert Rules; a lead member | approve her own expand; Reveal |
| `audit.manager` | audit-manager (second approver) | approve held expands, operate Jobs | Reveal; author |
| `audit.committee` | audit-committee (read-only) | read the Investigation (a reviewer member) | write anything |
| `audit.outsider` | audit-committee | nothing: not a member (404) | |
| `audit.admin` | admin + audit-lead + audit-settings | Reveal, Settings, mint embed links; a lead member | operate Jobs (administering is not operating) |

Masking is `typed`: Dataset columns classified `PARTY` (vendors, employees, bank accounts, addresses) are masked for everyone without Reveal; `LEGAL_ENTITY` (the journal) is not masked.
The Investigation is `vendor-v117-workpaper`, bound to `ap_links_dataset` (`src`, `dst`, `kind`, `ts`).

## 2. The data and the seven plants

Datasets: `ap_links_dataset` (kinds `approved`, `paid_to`, `reports_to`, `shares_account`, `shares_address`), its three derived subsets `ap_approval_links_dataset`,
`ap_identity_links_dataset`, `ap_org_links_dataset`, `gl_links_dataset` (kind `journal_flow`) and `ap_duplicate_payments_dataset`. Saved views: `ap_approval_graph`, `ap_vendor_identity`, `gl_intercompany`.

| # | Plant | Ground truth (pinned by `LaAuditGoldenTest` and `la-audit-smoke.mjs`) |
|---|---|---|
| 1 | Fictitious vendor | V-117 shares bank account BA-9031 and address AD-9031 with employee E-31; 14 invoices, all approved by E-31's manager E-15 |
| 2 | Shell cluster | V-131..V-135 on one account BA-7701 (10 `shares_account` pairs, one component of 5); V-136 has its own account, shares an address with V-131 and V-132, and is **not** on `shares_account`; Link prediction proposes V-136 with V-133, V-134, V-135 |
| 3 | Threshold splitting | 30 invoices to V-099 by E-23, each 97 % to 99 % of the 10 000 limit, within 42 days; the only invoices in the 97 to 100 % band; none at or over the limit |
| 4 | Single point of control | E-07 (with E-08, E-09) signs 70 % of the invoices of V-021..V-040; E-07 to V-041 is the only bridge at a vendor with more than one approver; the org chart is a tree |
| 5 | Intercompany round trip | LE-05, LE-06, LE-07, LE-08 pass 2 400 000 around at three quarter ends: the only cycle in `gl_links`; maximum flow LE-05 to LE-07 is 7 200 000 |
| 6 | Duplicate payments | 13 pairs: same vendor, same amount, same ISO week, two invoice numbers (`ap_duplicate_payments_dataset`) |
| 7 | Benign look-alikes | the utilities vendor V-001 (the busiest, 100 invoices, all 18 ordinary approvers) and the CFO E-01 (40 approvals): they lead Betweenness and are not findings |

Design notes worth saying aloud: every ordinary vendor has exactly two approvers, so the ordinary approval graph has no bridge and no 3-core by construction, and every planted vendor
with a single approver is a pendant. Three vendors carry the placeholder account `PENDING` and four a blank address key; they make no shared-attribute link (a placeholder is not evidence), and a key held by more than 10 parties makes none either.

## 3. The 12-minute version

| min | Scene | Who | The point |
|---|---|---|---|
| 0:00 | A. Open the workpaper, the cast, masking | audit.lead, audit.committee | masked ids for the committee, the same Investigation |
| 1:30 | B. Vendor V-117: expand, Connect seeds, Entity profile | audit.lead | conflict of interest in two hops |
| 3:30 | C. Algorithms on the approval graph (S1 to S5) | audit.lead | client run, then Run on server |
| 7:00 | D. Four-eyes expand of the CFO, Draft what-if | audit.lead, audit.manager | a second person; a safe sandbox |
| 9:00 | E. **Dossier: build, verify, change one byte, verify fails** | audit.lead | the headline |
| 10:30 | F. Members and roles, Settings | audit.admin | who sees what, every limit is a setting |
| 11:30 | G. Embed to the committee portal (optional) | audit.admin | read-only masked view in their own page |

## 4. Scenes with beat-level checks

### A. Open, cast, masking (1.5 min)
1. Sign in as `audit.lead`, open Investigations, open `vendor-v117-workpaper`. **Expect:** seeds V-117 and E-31 and their neighbours; a snapshot named "workpaper baseline".
2. Sign in as `audit.committee` (another window). **Expect:** the same graph, ids masked, every write control disabled; a write via the API answers 403 (`la-audit-smoke.mjs` checks it).
3. `audit.outsider`: the Investigation is not listed; its API answers 404.
* **If it goes wrong:** an empty Investigation list means the seed has not run, or the first ingest has not landed (the create answers 422 until it does; the seed script waits).

### B. V-117, Connect seeds, Entity profile (2 min)
1. On `ap_vendor_identity` seed V-117, expand: **E-31** appears joined by both `shares_account` (BA-9031) and `shares_address` (AD-9031). Say: two independent attributes, one person.
2. Seed E-31 and V-117 and run **Connect seeds**: the shortest route is one hop. Switch to `ap_approval_graph`, seed V-117: its only approver is E-15, E-31's own manager (hop 2 from E-31 through the org chart in `ap_org_links_dataset`).
3. Open the **Entity profile** of V-117 (node panel): reference rows from `vendor_master_dataset` (bank account, address key, onboarding date) and 14 invoice links, the payment account, and the shared links.
* **Beat check:** the committee sees the same profile with masked ids, and Reveal is `audit.admin` only.

### C. Algorithms (3.5 min). Each: client run first (instant, small), then **Run on server** for the Job-class ones, progress and result parity
Plan section 3.1 assigns eight algorithms to this vertical. Always seed first; an all-nodes canvas is a dust cloud.

| # | Algorithm | View and seed | Expect | Server run |
|---|---|---|---|---|
| S1 | **k-core** | `ap_approval_graph`, seed E-07 (expand to degree 2) | E-07, E-08, E-09 and V-021..V-040 are the only 3-core (core number 3); everything else is core 2 or less | client; Run on server: inline, core 3 |
| S2 | **Articulation points** | `ap_approval_graph`, whole component of E-07 | E-07 and V-041 are cut vertices (the two ends of the one bridge between the V-021..V-040 portfolio and the rest); the other cut vertex is the shell approver E-45 (removing it strands six vendors). Measured in the app: **3 nodes, 1 bridge** | client; server inline |
| S3 | **Bridges** | same | the edge E-07 to V-041 is the only bridge at a vendor with more than one approver; the rest are the single-approver vendors' pendant edges (V-117, V-099, V-131..V-136). Say: single approver is the finding, not the count. The app draws one link per invoice, so a vendor with several invoices from one approver is no bridge: V-041 has exactly one E-07 invoice on purpose | client; server inline |
| S4 | **Betweenness centrality** | `ap_approval_graph`, seed E-07, E-01, V-001 | V-001 leads (7 255 in the app, benign: busy), then the two cut vertices E-07 and V-041 (about 3 200 each); the CFO E-01 is busy by degree but not a cut vertex. Say: high betweenness alone is not guilt; it is the cut vertex that is the finding | **Run on server** (Job class, `betweennessCentrality`): 47 ms on 163 nodes, the same scores as the client |
| S5 | **Is forest** | `ap_approval_graph` then `ap_org_links_dataset` | false on the approval graph (cycles), **true** on the org chart (59 `reports_to` links, manager to report: parent to child, the orientation Is forest reads). The clean positive. Server run on the review Investigation: `false` | client; server inline |
| S6 | **Jaccard similarity** | `ap_vendor_identity`, seed V-131, but run on the approved plus paid_to graph | V-132, V-133, V-134, V-135 at **1.0** (same approver E-45, same account BA-7701); V-136 at **0.33** (same approver, different account) | client |
| S7 | **Link prediction** (common neighbours) | `ap_vendor_identity` shares links only | the top three predicted links are V-136 with V-133, V-134, V-135: the unlinked sixth shell vendor is proposed. Ground truth is planted | **Run on server** (Job class, `linkPrediction`): 4 ms on 163 nodes |
| S8 | **Maximum spanning forest** | `ap_approval_graph`, weight = AMOUNT | one tree per connected component: the approval graph has **4** components (the main one of 164 nodes, E-15 with V-117, E-45 with the six shell vendors, E-23 with V-099). Single-approver islands are visible as their own trees | client; server inline |

Recording parity (plan S6): note seed, top result and run time in the table above after each rehearsal; the TypeScript and Java implementations are expected to agree.
Also useful here, from the plan's coverage matrix: **Recursive paths** (approval chain depth through `ap_org_links_dataset`: E-31's chain to the CFO is E-15, E-07, E-03, E-01), the temporal and branching **pattern packs** on the 30 split invoices (branching: one approver to one vendor, TS ascending; band 9 700 to 10 000), and **Find cycles** with **Maximum flow** on `gl_intercompany` (S9 below).

### D. Four-eyes and the Draft what-if (2 min)
1. The seed already staged one **pending** four-eyes request (an unbounded expand of the Investigation, over `four_eyes_fan_out_above`). As `audit.lead`, also seed E-01 (the CFO) and press Expand without a fan-out cap. **Expect:** held as a pending request, because the CFO's 40 vendors are over the threshold of 30; `audit.lead` cannot approve it.
2. Sign in as `audit.manager`, open the approval inbox and approve it. **Expect:** E-01's 40 vendors appear. The CFO and the utilities vendor V-001 (18 approvers, under the threshold, so it expands without a hold) are the benign look-alikes: busy, explained, never flagged.
3. **Draft** (labelled "Draft: approval limit what-if", seeded): fork, step, rebase, promote or discard. The what-if: a limit of 9 000 would have caught all 30 invoices; a threshold filter AMOUNT >= 9 000 on `ap_approval_graph` shows exactly E-23 to V-099. Discard it; the Investigation is untouched.

### E. The Dossier as an audit workpaper (1.5 min), the headline
1. As `audit.lead`, open the Investigation's **Dossier**: build it. **Expect:** a manifest with hashes of the op log, Working Set and links.
2. **Verify** the untouched manifest: **verified**.
3. Change **one byte**: open the manifest in an editor and flip one hex digit of any hash, or use the scripted tamper in `tools/la-audit-smoke.mjs` (`tamperManifest`). Verify again: **fails**, and says which part differs. Say: the committee, the external auditor or a regulator can check the workpaper without trusting the system.
* **Prepare the altered file beforehand**: hand-editing JSON in front of an audience is error-prone.
* API (as `audit.lead`): `GET .../inv/investigations/vendor-v117-workpaper/dossier`, then `POST .../dossier/verify` with `{manifest}`.

### F. Members, roles, Settings (1 min) [audit.admin]
Members: `audit.lead` lead, `audit.admin` lead, `audit.manager` and `audit.committee` reviewers, `audit.outsider` absent. Settings > Link Analysis: masking `typed`, four-eyes above 30, index ON, Draft limits, the embed allow-list, Max fan-out. Entity types: `party` (masked) and `legal_entity` (not masked).

### G. Embed, optional (1 min) [audit.admin]
Mint an embed link on the Investigation (`POST .../embeds` or the Share panel; needs `-Dla.embed.enabled=true` and a lead member, hence `audit.admin`). Serve the portal from the allowed origin and open it:
```
python -m http.server 8100 --bind 127.0.0.1 --directory spaces/_templates/la-audit/portal
# open http://127.0.0.1:8100/committee-portal.html and paste the viewer link http://<host>/embed/inv/<grantId>.<secret>
```
`link-analysis.toon` allows `http://127.0.0.1:8100` and `http://localhost:8100` as frame ancestors (`embed.frame_ancestors`); a page opened from disk presents the `null` origin and is refused, and the viewer answers `frame-ancestors 'none'` to any other page. **Expect:** the committee sees the read-only masked Investigation (aliases, graph, step 6) inside their own page. Revoke it (`POST .../embeds/<grantId>/revoke`): the public read answers a uniform 404 and the frame stays blank. A mangled token answers 404 too.

### S9. Intercompany round trip (extra 1.5 min)
Open `gl_intercompany` (journal flows; `LEGAL_ENTITY` ids are not masked). **Find cycles** (server run): exactly one cycle LE-05, LE-06, LE-07, LE-08. **Maximum flow** LE-05 to LE-07: **7 200 000** (three quarter ends of 2 400 000). Hover a link: `via` names the journal and GL account (`JNL-RT-.../1900-IC-RECEIVABLE`).

### S10. Duplicate payments (extra 1 min)
`ap_duplicate_payments_dataset` has 13 rows. Same vendor, same amount, same week, different invoice numbers: a Dataset beat (Measure: count and sum), not a graph one.

## 5. Decisions recorded while building (operator may overrule)

1. **Template and personas are `la-audit` and `audit.*`.** Existing Spaces keep `admin`, `fm.analyst` and friends; the roster refuses one id with two definitions, so this Space never reuses them.
2. **One classification, `PARTY`, for every vendor, employee, account and address column.** Typed masking is per column, and a link endpoint column holds several of those, so one masked type covers them; `LEGAL_ENTITY` is the one unmasked type (the journal), which also demonstrates `typed` masking.
3. **`reports_to` is oriented manager to report (parent to child).** The row reads "src is the manager of dst". `Is forest` and Descendants read the parent-to-child direction; employee to manager would make a tree read as not a forest.
4. **Master-data links (`reports_to`, `shares_*`) carry the as-of day 2026-09-30** as `ts` and `event_date`, so the data-trust strip is not stretched back to a 2012 hire date.
5. **The `la_index_build` Job fires on `job.dataset.produced` of `ap_links`**, not on a Pipeline commit, because `ap_links` is a Job output.
6. **Derived link Datasets are Jobs** (`ap_approval_links`, `ap_identity_links`, `ap_org_links`), not virtual Datasets, so each algorithm scene has a clean single-kind graph.
7. **Betweenness leads with the benign hubs**, by design (plan ground rule 4): the cut vertex, not the score, is the finding.

## 6. What was verified live, what was not

Rehearsal 2026-10-16 on an Enterprise Demo Auth bundle built from this branch (port 8141, `-Dla.embed.enabled=true`), Space created with `seed-la-audit.mjs --scaffold`, all five feeds ingested on boot (1 804 invoices and approvals, 150, 60 and 72 rows, no rejects).

**Verified:**
* `la-audit-smoke.mjs`: **30/30** (Job outputs carry every plant; the Dossier verifies and one altered hash fails; committee read-only 403 and 200; outsider 404; pending request in the manager's inbox; a server Graph Run is accepted).
* The five link Jobs ran and every derived Dataset has rows; a replaced feed file re-ingests in place (counts did not double).
* Browser as `audit.lead`: the saved view `ap_approval_graph` (175 nodes, 1 804 links, ids masked as aliases, the E-07 portfolio visibly joined to the rest by one link), **Cut points: 3 nodes and 1 bridge**, **Betweenness** ranking (V-001 first at 7 255), the Investigation list, the workpaper Investigation opened, **Build dossier** and **Verify this manifest** ("All 6 recorded hashes still agree with the sealed log"), the four-eyes warning ("above 30, four_eyes_fan_out_above").
* HTTP: server Graph Runs `articulationPoints`, `bridges`, `kCore` (core 3), `isForest` (false), `betweennessCentrality` (47 ms, 163 nodes and 281 links), `maximumSpanningForest`, `linkPrediction` (4 ms), all COMPLETED with masked ids. Four-eyes: `audit.lead` and `audit.committee` get 403 on approve, `audit.manager` approves and the expand runs as step 7. Draft: conflicts (behind 1, none), rebase, promote. Entity profile of V-117 (resolve the alias first: the type is masked). Embed: mint, public read (200, `frame-ancestors 'none'`), the portal on :8100 framing the masked read-only view, revoke gives 404.

**Not driven:** the in-browser clicks for Jaccard, Link prediction, Maximum spanning forest and Is forest on the client (their server twins ran and the golden test pins the results); the Draft, Members and Settings panels and the Entity profile page in the browser (their routes were driven); tamper through the Dossier file upload (the smoke runs it through the route); `gl_intercompany` Find cycles and Maximum flow in the browser (pinned by the golden test).

**Defects found and fixed by this rehearsal:**
1. The seed treated "ap_links_dataset has no data yet" (a 409 on create) as "the Investigation already exists", so a first seed silently built nothing; it now presses Run on the link Jobs and waits.
2. The seeded Alert Rule used a Measure name, a severity and a window the server refuses (closed Measure set; INFO, WARNING, CRITICAL; at most 31 days).
3. The Draft step named an entity that was not in the Working Set.
4. V-041 had two E-07 invoices, so the app (one link per invoice) found no bridge; it now has one, and the golden test builds the graph the way the app draws it.
5. The committee portal could not frame the viewer: the template now allows the `:8100` origin and the page is served from `portal/`.

**Observations, not fixed (outside this lane):** the Domain profile "Financial crime" labels the Working Set tiles Accounts, Transfers and Transactions for an approvals graph, and the placeholders in the expand settings are telecom values (call, sms); an Investigation title carries the id it was made for (V-117), which is masked inside it, so the title reads "Vendor masked:... workpaper"; the Centrality picker is labelled Metric (the glossary says Measure).

**Known limits:**
* The Alert and Case beats are secondary here (plan matrix, "o"): the seed binds an Alert Rule `ap-split-invoices` (the closed value-Measure set has `structuring`, window 2026-03-02 to 2026-04-02) and opens a Case for V-117; the primary Alert scene is vertical A.
* The `la-showcase-off` template's `roles.toon` differs from `la-showcase`'s (Reveal on the analyst), so `LaShowcaseTemplateTest` fails on master as of this build: a pre-existing defect of the sibling template, not touched here.
* Recorded precision claims: none. The plants are planted, the counts are the corpus, and no detection rate is implied.
