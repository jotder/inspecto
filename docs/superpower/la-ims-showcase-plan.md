# Link Analysis showcase on real IMS records (analysis and plan)

**Status: BUILT and rehearsed on screen 2026-10-15 (steps B1-B5 and the dry run below) on the 2026-10-09 CSV extract; switched 2026-10-16 to one full production day in Parquet (section 6: 132 M records, links, index, thresholds recalibrated); the Space and data stay outside the repo.** Input: 20 Huawei IMS CDR extracts (`fct_huawei_ims`) at `C:\sandbox\data\IMS` (outside the repo, **never committed**:
no data or client files in commits). This page records what the data holds, what graph a Link Analysis user can draw from it, and the build steps.
Numbers in this page were measured on 2026-10-15 over the 19 distinct files; subscriber numbers are masked here on purpose.
Existing showcase assets this reuses: [demo runbook](../ops/la-demo-runbook.md), the `la-showcase` Space Template, `tools/gen-la-telecom-data.py` (the synthetic telecom feeds).

## 1. What the data is

| Fact | Measured |
|---|---|
| Files | 20 CSV, 9.2 MB; **one is a byte-identical duplicate** (`PSDCCF03_...123652 (1).csv`, cmp-equal) so 19 distinct, **29,650 records**, 84 columns |
| Origin | 6 IMS call-control nodes: `KTNCCF01-03` (Khartoum-side, ~10:36) and `PSDCCF01-03` (Port Sudan, ~00:35 and ~01:01); one day, 2026-10-09 (104 rows spill to 10-08) |
| Mix | SIP `MESSAGE` 21,390 (72 %, SMS), `BYE` 7,137 (call end records with duration), `INVITE` 1,123 (call setup) |
| Subscribers | 20,545 distinct `served_msisdn` (= `served_imsi` 20,538 = `served_imei` 20,421); 1,354 of them also appear as a counterparty |
| Counterparties | 7,552 distinct `other_msisdn`; **72 % of SMS go to non-numeric addresses** (`Bankak` 11,982 rows, `Zain` 3,955, `ZainOffers` 1,584, USSD codes `*888#`, `*1#`), i.e. service senders, not people |
| Person to person | 7,542 rows (6,972 `BYE`, 498 SMS, 72 `INVITE`) over 13,045 numbers |
| Location | `cgi_id_key` (MCC-MNC-LAC-cell) 11,095 cells, 311 LACs; 2G 8,711 / 3G 19,587; 11 cells carry 44 to 85 subscribers each |
| Roaming and international | 101 international rows (UK 50, IND 20, SAU 10, BEL 8, ...); in-roamers 69 rows non-Sudan |
| Empty columns | 17 of 84 are 100 % empty (`third_party_msisdn`, `served_msrn`, `incoming_route`, `original_c_num`, `number_of_diversions`, ...); 12 conference columns are nearly all empty |
| Duration | p50 0 s (SMS), p90 77 s, p99 1,335 s, max 3,768 s; the three longest calls are all 3,767-3,768 s (a probable call-timer cap, to confirm) |

Identity columns: `served_msisdn`, `served_imsi`, `served_imei`, `other_msisdn`; time `event_start_date_time`; cell `cgi_id_key`; result `smsresult`; length `original_dur`.
Format traps: the empty IMEI is a blank (116 subscribers share it: treat as unknown, **not** as one device), `-99` is the dimension "unknown" key, `cdr_status` is a
brace string, MSISDNs lose the `+` and start `249` (Sudan), the `other_msisdn` for USSD carries a `;phone-context=` suffix that must be stripped.

## 2. What the data already shows (the story)

1. **A coordinated SMS burst on one number (the headline).** Eight SIMs with **IMSIs from one narrow range** (`634012xxxxxxxxx`, a range of under 400, 8 of about 400 in the range, not strictly consecutive) and **distinct IMEIs sharing their first 9 digits (one handset model/batch)** (`86194007xxxxxxx`; from one 15-digit
   batch** (`86194007xxxxxxx`) sent **220 SMS to a single number (`249923xxxxxx`) in 27 minutes** (00:34:25 to 01:01:53, Port Sudan nodes). **All 220 were `FAILED`.**
   Each SIM sat on a different cell (one send point per SIM; cells 50023, 27272, 31003, ...). Per-SIM counts 46, 43, 41, 33, 24, 14, 14, 5. Reads as an SMS flood
   or test rig from one provisioned SIM batch; the graph shows it as a star with a shared-device and shared-batch halo. This is the planted-looking cluster
   a Link Analysis demo needs, and it comes from the real feed, not from a generator.
2. **A multi-SIM device.** IMEI `35464802xxxxxxx` carries **15 different MSISDNs** (a legitimate IMEI would carry 1 to 3; the next highest is 3). Shared-IMEI neighbours
   expand to 15 subscribers. Probably a SIM bank, a dev handset or a default IMEI: say which in the room only after checking with the data owner.
3. **Two collection numbers.** One number received 17 call-end records from 15 different callers (722 s total), another 7 from 8 callers (1,005 s). Small
   fan-in hubs, good for the Suspicion score and Pass-through Measures.
4. **Components.** The person-to-person graph is 13,045 nodes in **6,498 components**, largest 15, 9, 8: a sparse graph. A whole-day graph is a collection of small
   stars, so the demo must start from a **seed** (the burst target, the IMEI) and expand; an all-nodes canvas would be a dust cloud.
5. **Service traffic.** `Bankak` (mobile-money notices) is the dominant counterparty and an ideal high-degree node to **show a restricted expand** (a hub the user must not
   expand blindly) rather than a finding.
6. **Cell crowds.** Cells with 44 to 85 subscribers give a Subscriber -> Cell -> Subscriber co-location view for the Spatio-temporal story.

What the file does **not** have: no `A-number -> B-number` for the SMS service senders as people; one day only (no trend, no baseline for drift); no subscriber
identity (no KYC), so no cross-feed identity resolution beyond IMSI/IMEI.

## 3. Graph model for Link Analysis

| Entity type | Key | Notes |
|---|---|---|
| Subscriber | `served_msisdn` (masked `typed`) | 20.5 k |
| Counterparty | `other_msisdn` when 12 digits, else **Service** | strip `;phone-context=...`; service senders are their own type, never mixed with people |
| Device | `served_imei` (blank and `-99` excluded) | the shared-device link |
| SIM | `served_imsi` | narrow-IMSI-range batch signal (not modelled as links yet) |
| Cell | `cgi_id_key` | optional layer |

| Link | From -> To | Weight / attribute |
|---|---|---|
| SMS | Subscriber -> Counterparty | count, `smsresult` (FAILED ratio is the signal) |
| Call | Subscriber -> Counterparty (`BYE`; `INVITE` is setup, do **not** double-count) | duration |
| Uses device | Subscriber -> Device | first seen |
| Camps on | Subscriber -> Cell | count |

Columns to ignore: the 17 empty ones, the 12 conference ones, `online_charging_flag`, `service_context_id`, `node_address` (kept as a Collector tag only).

## 4. Showcase plan

**Pre-requisite decision: real or masked?** The records look like live subscriber data (real country code, real operator service names). Recommended: ingest them into a
**private, local `ims` Space only**, with MSISDN/IMSI/IMEI classified so `typed` masking is ON, and screenshots/recordings masked; the Space is git-excluded and the data
never leaves the machine. If this must be shown to people outside the data owner's organisation, replace the identities by a keyed alias at ingest (same structure, no real numbers).

### 4.1 Build (about 5 steps)

| # | Step | Done when |
|---|---|---|
| B1 | Space `ims` (git-excluded) with one **Collector** on the inbox and one **Pipeline** `ims_cdr` with a schema (84 columns, 17 dropped); `file_template` so a missing node file raises a sequence gap | 19 files ingest, 29,650 rows, the duplicate is rejected as already seen |
| B2 | Normalise in the `ims_links` Job (raw Pipeline stays raw): strip `;phone-context`, blank/`-99` IMEI excluded, split service senders from people | a Dataset `ims_links` with `src`, `dst`, `kind`, `ts`, `dur`, `result` |
| B3 | Entity declaration + classification (MSISDN, IMSI, IMEI classes) and Entity Lists; a **link Job** writes `ims_links` plus `ims_device_links` | Link Analysis opens a seed and draws it |
| B4 | Saved views: `ims_burst_star` (seed = the burst target), `ims_shared_device` (seed = the 15-SIM IMEI), `ims_fanin_hubs` | each opens from a landing card |
| B5 | Standing detection: Alert Rules for (a) >= 5 SIMs texting one number with failed ratio > 90 % in 30 min, (b) one IMEI > 5 MSISDNs, (c) fan-in > 10 callers; a Case Rule opens an Incident | the three fire on this day's data and not on a control day |

**B1 result (2026-10-15): DONE with two findings.** The Space lives at `C:\sandbox\ims-spaces\ims` (outside the repo; the files here are never committed), served by a second instance of the already-built Enterprise demo bundle (`inspecto-demo`, built 07:00 today by another session) with `SPACES_ROOT=C:\sandbox\ims-spaces` on port 8101. Pipeline `ims_cdr`, 70-column schema (14 always-empty or conference columns dropped, 13 identity columns classified CONFIDENTIAL). 19 batches SUCCESS; the first run stored 29,649 of 29,650 (finding 2), the schema was then fixed and a re-ingest stores **all 29,650**.

1. **The duplicate check is by file name, not content.** The byte-identical `(1)` copy ingested as its own batch (+1,784 rows, total 31,433). It was removed from the inbox and the Space re-ingested. For the later files the operator will add, a re-sent file under a new name will double its rows: dedupe at the Pipeline (a key on `edr_seq_num`, unique here) or guard the inbox.
2. **One row was rejected `TOO MANY COLUMNS`, and the cause was my schema, not the parser.** `edr_seq_num` 5138291xx (a normal `BYE`, record 1076 of `KTNCCF01_...473117`) went to `data/ims_cdr/errors/` as line 1077 and raised a `file_problem` Alert (medium). The file has 84 columns; I had dropped 14 "always empty" ones, including the **last one, `service_mode`**, which is non-empty on exactly this one row in all 19 files (value `5`). With the last declared selector at 82, a row whose 84th field is filled has more columns than declared, and DuckDB rejects it by design (`DuckDbCsvIngester` documents this; `skip_tail_columns` is the escape). Plain DuckDB 1.5.5 and the engine's own `duckdb_jdbc` 1.5.6.0 read the whole file (1,250 rows, no rejects) with default options, which is how the cause was ruled out of the parser. Fix: `service_mode` added back (71 fields); re-ingest stores 29,650 and the errors folder is gone. **Lesson for the next feed: never drop a TRAILING column because it looks empty; a 0.00 fill rounds a handful of values away.** Open and unverified: the batch event said `rejected: 0` for that file, which may only count rows routed to a `rejected` sink, not parse rejects (not checked; no backlog row filed for it). The old `file_problem` Alert is still OPEN in the Space and is stale.

**B2 result (2026-10-15): DONE.** Normalisation lives in the `ims_links` Job (`sql.template`, cron every 10 min, manual `fm.manager` trigger), not in the Pipeline, so the raw `ims_cdr` stays untouched: the `phone-context` suffix is stripped with `split_part(x, chr(59), 1)` (the single-statement guard refuses a literal semicolon, even inside a regex), a blank or `-99` IMEI is excluded, INVITE is not counted (BYE carries the duration), and edges are oriented by `event_direction_key` (1 = the served party originated, 2 = it received). Run: **19,637 edges** in 447 ms.

| link_kind | edges | what it shows |
|---|---|---|
| `sms_service` | 12,687 | short codes and sender names (`Bankak` 6,638 receivers, `Zain`, `ZainOffers`) |
| `voice` | 6,482 | person-to-person calls; fan-in hubs 14 and 7 callers |
| `sms` | 239 | person-to-person SMS |
| `co_target` | 119 | 3 to 20 senders texting one number: the **burst** (8 senders, 28 pairs, 1,540 pair-events, all failed) |
| `shared_device` | 110 | the 15-SIM IMEI as 105 pair edges |

**New lead from B2:** two **international** numbers (an Indian and a UK one) receive only FAILED SMS: one from 14 senders (260 events), one from 36 senders (44 events). They are not part of the 8-SIM burst; a second pattern of the same shape. Check before the demo whether they are one story (the same SIM batch) or two.

**B3 result (2026-10-15): DONE, burst seed opens.** Settings and Demo Users copied from the `la-showcase` template (masking `typed`, index ON, four-eyes fan-out 2000). `la_index_build` (`la.index.build`, cron 2 minutes after `ims_links`, because only `sql.template` emits `job.dataset.produced`) published **index version 1**; one saved view `ims_network` (`profile: telecom`); four Entity Lists of `msisdn` (`ims-sms-burst` 9 members, `ims-multi-sim-imei` 15, `ims-fanin-hubs` 2, `ims-intl-failed-sms` 2). Opened as `fm.analyst`: seed the burst target, expand one degree, **9 nodes and 8 links**, the 8 SIM spokes around the target, every id shown as an alias (`masked:...`), the read served from the index (`rowCount` 8, version 1, not stale).

Findings to handle before the demo:

1. **Four-eyes bites the first click.** An unbounded expand exceeds the 2000 fan-out threshold and waits for a second person (`p1`); approving as `fm.manager` admitted the 8 SIMs. This is the T7 scene working, so run it in that order, or set a Max fan-out in the expand settings to skip it.
2. **The index Job is refused until an editor owns it** ("last editor not recorded"): re-save it once as `admin` (`PUT /jobs/la_index_build`). A Space created from a template gets this stamped; a hand-built Space does not.
3. **The telecom profile's "Investigate a number" card names `telecom_links_dataset`** (hardcoded in `domain-profile.ts`), which this Space does not have; the card then asks to choose a Dataset and queues the number as a seed. Works, one extra click. Naming our Dataset `telecom_links_dataset` would remove it but couples the demo to a template name.
4. **The node dialog says the target is on none of 4 Entity Lists although it is a member of `ims-sms-burst`** (the list holds raw MSISDNs, the node id is masked). Check whether Entity List membership is matched on the masked id; if not, the "on a watch list" enrichment never fires under `typed` masking.
5. **The data-trust strip reads "as-of not known, days not assessed"**: `ims_links` has no event-day column the strip can use. Map `event_date` (or add `last_seen` day) before showing T12.
6. **Saved-view Load did not apply on the first pass** (menu item clicked, no request fired); not retried. A full page reload drops the Demo User session.

**B4 result (2026-10-15): views DONE, UI Load NOT re-confirmed.** The single `ims_network` view was replaced by the three the plan names, each with a filter on the Dataset: `ims_burst_star` (`failed_ratio >= 0.9`, force layout), `ims_shared_device` (`link_kind = shared_device`, hulls on) and `ims_fanin_hubs` (`link_kind = voice`, dagre). All three are served by `GET /components/link-analysis-view` with the filter intact and appear in the picker ("3 saved views in this Space"). The earlier "Load did nothing" could not be reproduced or fixed: the Browser pane collapsed to a 21 px strip mid-pass, so the Load click was not re-driven. Re-drive it in a normal-sized window before the demo (suspect: the submenu closes when the pointer leaves it).

**B5 result (2026-10-15): DONE, the whole chain fires.** `ims_links` -> `ims_typology_counts` (one row: **3 burst targets, 1 multi-SIM device, 1 fan-in hub**, 19,637 scored links) -> `ims_typology_alerts` -> **3 Alerts** (`ims_sms_burst_targets` CRITICAL, `ims_multi_sim_devices` WARNING, `ims_callin_fanin_hubs` WARNING; each names a count, never a number) -> a CRITICAL **Incident** -> Case Rule `ims_critical_burst` -> **Case** "Critical IMS SMS burst" (OPEN, via the `ims_caserule` Job, `caserule.evaluate`). Counts come from a Dataset, so each rule stays aggregate-only (rule 5.6). Two differences from the plan: **the burst rule fires on 3 numbers, not 1** (the 8-SIM burst, the Indian number and the UK number all pass 5+ senders at 90 %+ failed; the demo says "three numbers, one is the 8-SIM burst"), and the 30-minute window is not applied (one day of data, the day is the window). Not yet run against a control day (the plan's "does not fire on a control day"): there is only one day.

**Dry run, API level, 2026-10-15 (live Space, port 8101, all 16 scripted checks PASS; UI beats re-driven only for saved-view Load).**

| Beat | Result | Measured |
|---|---|---|
| Saved-view Load | **works in a normal window**: the picker row opens its submenu on **hover**, a plain click on the row just closes the menu (the "Load did nothing" of B3). Load fills profile, Dataset, mapping, link type and attributes, then draws | 4 s |
| Burst star | seed the target, bounded expand: 8 admitted, 8 links, read from the index (version 8: the index rebuilds each time `ims_links` re-runs, so the version number grows) | 127 ms + 294 ms |
| Expand one SIM | 0 new entities, 7 links: the 7 other SIMs (the cluster is a clique through the one target) | |
| Unbounded expand | waits for a second person (`p1` pending), as designed | |
| Multi-SIM device | seed one MSISDN, expand: 15 admitted, 15 links | |
| Service hub | seed the sender name, bounded expand (500): 500 admitted, **6,138 more capped**, `hubsFlagged` 0; unbounded waits for approval | |
| Replay, Dossier | replay twice, same hash; Dossier builds (155 ms), verifies; one flipped hash digit: `verified: false` (HTTP 200) | 83 ms replay |
| Alerts | 3 aggregate-only Alerts (burst CRITICAL, device and fan-in WARNING), no subscriber number in any text; plus the Pipeline's own `file_problem` (medium) for the rejected row | |
| Masking | no raw MSISDN anywhere in the op log or Alert text | |

Findings from the dry run: (1) the burst saved view hits the 500-node cap because `failed_ratio >= 0.9` also keeps failed service SMS: add a `link_kind` condition to the view or the demo opens on "Truncated"; (2) the **service-hub beat is the four-eyes gate, not a hub restriction** (`hubsFlagged` 0 for a 6,638-receiver sender name): say "bounded to 500, 6,138 more not shown", not "flagged as a hub"; (3) the rejected row of B1 finding 2 was a schema defect and is fixed (all 29,650 rows stored; the dry run above ran on the 29,649-row ingest, counts unchanged at 3 / 1 / 1 after the re-ingest, links 19,638); (4) the plan's "consecutive IMSIs" and "one IMEI batch" wording is corrected above. **Not done in this pass:** the full 20 minutes on screen (only Load, the loaded view and the Alerts data were driven in the browser), the Alerts page, the Dossier panel and the embed beat in the UI; a spoken run with a second person.

**On-screen rehearsal, 2026-10-15 (current `master` `1ad331c50`, Enterprise demo bundle, `la-app` UI; the Space served by it on port 8101).**

*Which bundle.* A bundle built with the default (full SPA) UI has **no Link Analysis page** (`/link-analysis` is "Page not found"; the SPA offers Home, Business, Operations); it does show Home "Needs attention", Incidents and Cases. For this demo build **`package.ps1 -Edition Advanced -DemoAuth -Ui la-app -NoBakeDemo`** (about 10 minutes; the Angular font inlining fetches Google Fonts and failed once on a transient network error: retry). The `la-app` UI has Link Analysis, Geo, Entity Lists and Alerts, but no Incidents or Cases page: show the Incident and Case through the full SPA or by API.

| Beat | On screen | Result |
|---|---|---|
| Alerts page | `la-app` /alerts | 4 fired rows (3 Space Alerts and the stale `file_problem`), 3 Alert Rules |
| Burst view (saved-view Load) | picker, hover the row, "Load view" | **228 nodes, 261 links, no "Truncated" banner** (the projection cap is now 1,000 under LA-CONFIG-LIMITS-1); legend `co_target` and `sms` only; stars of 8, 14 and 36 senders; ids masked |
| Shared-device view | same path | **22 nodes, 110 links**: the 15-MSISDN clique, a 3-clique and a pair, as community hulls |
| Start Investigation, seed by list | Investigation tab, Entity Lists "Seed by list" | "Seeded 9 entities from IMS SMS burst" (the lists only offer Seed by list once an Investigation is open); works under masking |
| Expand one degree | "Expand next degree" | **runs at once, no approval wait**: 0 new entities, 36 links (28 clique + 8 SMS), "answered from the link index v25" |
| Replay | Replay button | "Replay is equivalent: the same Working Set at every step" |
| Dossier | Build, then "Verify this manifest" | topology 9 entities, 36 links; "Log intact"; ledger names the Entity List; **"Verified, self-consistent: yes"** with the manifest root hash |
| Alert, Incident, Case | full SPA (Home, Incidents, Cases) | burst Incident on Home "Needs attention" with aggregate-only text; Incidents: 1 Identified; Cases: 1 Open, tag `fraud-desk` |
| All 16 API checks | re-run on the new bundle | 16 of 16 PASS (same as the earlier run; unbounded expand with no ids still waits for approval) |

Findings from this pass (the open ones first):

1. **The Entity List "member of" marker is a real defect under `typed` masking.** `POST /entity-lists/{id}/match` with the raw number matches (`match: key`); with the node's masked alias it does not, and the alias the list returns for the same number (`masked:6d98...`) differs from the exploration alias (`masked:f01a...`), so a masked node id can never match a list. Root cause is two aliasings: fix by matching on the same keyed alias the exploration uses, or by resolving the node to its raw key server-side. Not fixed here.
2. **Four-eyes no longer fires on a plain degree expand** (LA-CONFIG-LIMITS-1 gave the UI a bounded default): the T7 beat must use "Expand the whole Working Set" or a high-degree sender name (the API call with no ids still returns `pending`). The earlier "first click waits for approval" note is superseded.
3. **`Σ via` in the Working Set panel is a nonsense figure** (an IMEI string summed as a number: 38,468T). Cosmetic; drop `via` from the numeric attribute columns of the telecom views or label it as text.
4. **The data-trust strip still reads "Data as-of date not known, days not assessed"**, and the Dossier "Data state at export time" says "not assessed" for as-of, files waiting and days with no rows: the strip reads input-file stamps and `ims_links` is built by a Job, not landed by a Collector. Drop T12 for this Space, or point a view at `ims_cdr` itself.
5. The stale `file_problem` Alert (from the rejected row, fixed) is still OPEN in the Space: acknowledge it before the demo or it shows as a fourth Alert.

**Follow-up 2026-10-16 (the Space now runs on the Preview bundle at :8080, WSO2 sign-in with the same six user names):**

- Finding 1 **FIXED** (Entity profile lane C, `dc12afd24`): the burst target resolves to `masked:6d98e84b76714ef2` and the
  `ims-sms-burst` list matches that same alias (`match: key`); every list matches its members' aliases and refuses an
  unknown one. No raw identifier in any list answer.
- Finding 2 **handled in the script**: the 12-14 beat now uses "Expand the whole Working Set".
- Finding 3 **FIXED in code**: the Working Set tiles no longer sum an identifier column (every value 10+ digits, or any
  value with a leading zero), so `Σ via` is gone; `working-set-stats.spec.ts`. Shows on :8080 after that bundle is rebuilt.
- Finding 4 **FIXED in the Space**: `ims_links_dataset` declares `eventDate: last_seen`; its freshness read answers data as
  of 2026-10-09 10:36 UTC (it was "not known").
- Finding 5 **DONE**: the stale `file_problem` Alert is resolved (cause fixed, note recorded); the Space shows the three
  detection Alerts only.

**Not done in this pass:** the tamper check by file upload on screen (the API tamper check passes), the embed beat (API only by design), a spoken run with a second person, and the Alerts-page drill into the Investigation.

### 4.2 Demo run (20 minutes, technical audience; full runbook scenes in brackets)

| Min | Beat | Proof |
|---|---|---|
| 0-2 | Pre-flight, the data-trust strip: 19 files, 29,650 records, the duplicate rejected | "what this graph stands on" |
| 2-6 | **Burst star**: seed the target; 8 SIMs appear; Suspicion score ranks the target; failed-ratio edge colour; Pass-through not relevant [S1] | the headline finding |
| 6-9 | **Expand a SIM**: its 7 `co_target` links to the other 7 SIMs (a complete clique, 28 pairs); no shared-device link, because the IMEIs differ [S5 pattern match] | the cluster is mutually linked by one target |
| 9-12 | **Multi-SIM device**: 15 MSISDNs on one IMEI; Entity List of the 15; per-type masking `typed` [S3, S10] | device-share Pattern Pack |
| 12-14 | **Bankak restricted expand**: seed the service-hub sender name; a plain "Expand next degree" is bounded (500 admitted, the rest capped, no wait), so use **"Expand the whole Working Set"**: it waits for a second person, `fm.manager` approves [S8] | four-eyes on a hub |
| 14-16 | Open the Investigation, Replay, build and verify the Dossier [S2, S11] | sealed, tamper-checked |
| 16-18 | Alert -> Incident: the burst Alert Rule fires, aggregate only, links to the Investigation [S12] | closed loop |
| 18-20 | Claims we do NOT make (below) and questions | honesty |

### 4.3 Claims we do NOT make

* **One day, 29.6 k records**: shows behaviour, not scale (scale: 10^8 edges measured, 10^9 an extrapolation).
* **No fraud verdict**: the burst is a pattern worth an analyst's time; intent (SMS flood, SIM-box test, provisioning check) needs the operator's own context.
* **The 15-SIM IMEI** is flagged as multi-SIM, not as a crime; default/placeholder IMEIs exist.
* **No baseline**: one day cannot show drift or "new this week"; ask for further days if that beat is wanted.
* Masking: if real identities are on screen, the demo is private to the data owner.

## 5. Questions for the operator

1. Real identities or keyed aliases for the demo (4. above)? Recommended: private local Space now, aliased copy if it must travel.
2. Is the burst a **known** event (a test, an incident)? It changes the story from "detected" to "reproduced".
3. Are more days or the voice/SMS peers (the other feeds: CRM, HLR/EIR, recharge) available? CRM/HLR would unlock identity resolution and the SIM-swap typology; today the demo cannot claim them.
4. Does Link Analysis see `ims` alongside the synthetic `la-showcase`, or is `ims` a separate bundle to avoid mixing real and invented data? Recommended: separate Space.

## 6. Production day 2026-10-01 (Parquet, switched 2026-10-16)

The Space now holds one **full production day** instead of the 2026-10-09 CSV extract. Same table (`fct_huawei_ims`),
same codes; about 4,500 times the volume. The Space and the data stay outside the repo; numbers here are aggregates.

**What the data is.** 100 Parquet files, 12.25 GB, **132,165,497 records**, 2026-10-01 00:00:00 to 23:59:59, one
schema of 83 columns (native types: `event_start_date_time` TIMESTAMP, `original_dur` DOUBLE, `event_date` DATE), Hive
folder `event_date=2026-10-01`, one row group per file.

| Column | Shape |
|---|---|
| `sip_message_type` | MESSAGE 68.66 M, BYE 56.08 M, INVITE 7.42 M (the same three codes as the extract) |
| `event_direction_key` | 2 (received) 97.95 M, 1 (originated) 34.21 M, 0 1,701 |
| `smsresult` | blank 63.50 M, SUCCESS 58.28 M, FAILED 10.38 M |
| `served_msisdn` | 12 digits in 99.96 %, home prefix 249 in 99.8 %, about 9.1 M distinct |
| `other_msisdn` | 59.4 M non-numeric (sender names and short codes, about 165 k distinct), 7.3 M with a `;phone-context` suffix, 58.3 M twelve-digit |
| `served_imsi` / `served_imei` | 15 digits; about 7.4 M / 9.5 M distinct; 636 k rows with a blank IMEI |
| volume by hour | 0.4 M (03:00) to 9.9 M (18:00-19:00) |

**How it is placed.** The Parquet files were MOVED into the Pipeline's store (`data/ims_cdr/database/event_date=2026-10-01/`),
not ingested: a Dataset can only read inside the Space data root (an outside path, a `read_parquet` literal and a
junction are all refused), and a Parquet ingest would rewrite the 13 GB as VARCHAR. The CSV landing of the extract is set
aside outside the Space; the CSV Pipeline `ims_cdr` is **paused** (its CSV landing types would clash with the native ones in
the same store). `ims_cdr_dataset` reads the files directly.

**Links (the `ims_links` Job, unchanged SQL apart from the caps).** 39,411,289 links in 572 s on the server (10 GB DuckDB
cap; the dry run took 335 s at 12 GB): voice 21.65 M, sms_service 16.87 M, shared_device 490,689, sms 328,890, co_target
71,957. The pair caps were raised so the recalibrated hits stay drawable: shared_device 2 to 200 MSISDNs per IMEI (was 2 to
20), co_target 3 to 100 senders (was 3 to 20). The giant outliers stay out of the pair links: one IMEI carries 12,039
MSISDNs and one number has 7,809 senders.

**Index.** Full build in 316 s, version 70, 838 MB: 12,349,996 nodes; edges by kind voice 21.61 M, sms_service 4.05 M,
shared_device 483,586, sms 328,890, co_target 69,600; degree p50 2, p90 9, p99 22, max 1,067,398. **Sender-name edges are
not in the index:** the index keys each end through its Entity Type's normaliser (`e164`), and a sender name normalises to
nothing, so about 12.8 M sms_service edges (and about 45 k voice) are dropped as NULL ends. They remain in `ims_links`.

**Thresholds (recalibrated on the full day).**

| Alert Rule | Before | Hits on 2026-10-01 before | Now | Hits now |
|---|---|---|---|---|
| SMS burst target | 5+ senders, 90 %+ FAILED | 236 | **20+ person-to-person senders, 95 %+ FAILED** | 29 |
| Multi-SIM device | "15" counted PAIR links (about 6 MSISDNs) | 1,826 | **50+ distinct MSISDNs** (the count is now of MSISDNs) | 58 (76 in the data; the 18 above 200 MSISDNs are outside the pair cap) |
| Call fan-in hub | 10+ callers, any callee | 248,836 | **100+ callers, subscriber numbers (11-13 digits) only** | 13 |

The three Alerts fired with those counts (CRITICAL burst, WARNING fan-in and multi-SIM); the 2026-10-09 Alerts are resolved
as superseded. ⚠ A Dataset Measure rule is **edge-triggered**: it fires once per open breach and only a heal re-arms it.
Resolving its Alert by hand does not re-arm a rule whose measure is still breached; the open-breach state is rebuilt from
OPEN Alerts at boot, so a restart after resolving re-armed them.

**Entity Lists.** `ims-sms-burst` (its title named the 2026-10-09 event) is retired; `ims-sms-burst-targets` holds the 29
burst targets; `ims-multi-sim-imei` holds the 178 MSISDNs of the largest device within the cap; `ims-fanin-hubs` the 13
hubs (both 2026-10-09 hubs are hubs again); `ims-intl-failed-sms` the 20 international numbers receiving only FAILED SMS
from 5+ senders. The saved views' descriptions are day-neutral and point at the new lists.

**Schedules and limits.** `ims_links` daily 01:00, `la_index_build` daily 02:00 with a one-hour timeout (they ran every
10 minutes); `entity_profile.timeout_s` 300 (was 30). The 10 GB DuckDB cap was a launch option of one run only: the
server's default (about 3.2 GB on this 32 GB host) makes the nightly links Job slower than measured here.

**Entity profile at this scale (measured 2026-10-16, IMSI).** The cold Entity Type Profile took 568 s; the cached read
0.4 s. It came back **partial**: Identity, Where it appears, Value shapes, Time and Lineage are assessed, but **Volume
and Relationships are `not assessed: Out of Memory`**. The profile reads run under the sandboxed Dataset query budget of
about 953 MiB, far below the server's DuckDB default, and a distinct count or a per-Entity group over 132 M rows does not
fit it. The page stays honest (each section says why), but the partial result is CACHED, so the warm read keeps
returning it until the data changes, and the reason shown is a raw DuckDB message. Needs a fix before this Space
demonstrates the Entity pages: a profile memory budget (or spill) of its own, sampling for distinct counts and
relationships above a row limit, and no caching of a section that failed. P4 of the Entity profile plan (a scheduled
profile Job) would also take the cold read off the request path.
The cold IMEI profile took 728 s; the MSISDN profile (four bindings plus the links) did not answer within 25 minutes.
**Fixed in code the same day** (OKF LA backend §6.3): its own memory budget `entity_profile.memory_limit_mb` (4,096 MiB),
the dominant value from a row sample, relationships retried over an Entity sample, failed sections never cached, a
readable reason. Measured with the sandbox settings on this day: at 4 GB the two reads that failed take 58 s and 149 s.
Not yet on the :8080 bundle: it shows there after the bundle is rebuilt.

**What this changes in the demo.** The story of section 2 (one 8-SIM burst, one 15-SIM device) is the 2026-10-09 extract;
on the production day the same rules find 29 burst targets, 58 large devices and 13 call hubs, so the beats seed from the
new lists. The service-hub beat (12-14) seeds a sender name, which the index does not hold: it reads the Dataset instead,
under the projection limits. To re-check on screen: the burst and device views at this scale, and the four-eyes beat
(fan-out threshold 2,000 against a maximum degree of about 1.07 M).

## References

* Feed analysis: this page, section 1 (measured 2026-10-15)
* Synthetic telecom feeds: [`la-telecom-demo-data.md`](../archived-documents/plans-archive/la-telecom-demo-data.md)
* Runbook scenes: [`la-demo-runbook.md`](../ops/la-demo-runbook.md)
* Companion plan: [`la-whitepaper-demo-plan.md`](la-whitepaper-demo-plan.md)
