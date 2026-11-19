---
type: Plan
title: Product maturity assessment — every client-facing item — Per-item assessments — Link Analysis packs, Inspecto LA and the editions
description: Part 7 of 8 of Product maturity assessment — every client-facing item: 
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
item: layer.product
audience: internal
status: plan
verified: 2026-11-19 last-edit
---

# Product maturity assessment — every client-facing item — Per-item assessments — Link Analysis packs, Inspecto LA and the editions

Part 7 of 8 of [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · previous: [Per-item assessments — fintech and commerce solution packs](plan-maturity-assessment-packs-fintech.md) · next: [Per-item assessments — Preview edition, and references](plan-maturity-assessment-preview-references.md).

## 6. Per-item assessments (continued)

### F. Link Analysis packs, Inspecto LA and the Editions

#### solution-pack.la-showcase — Link Analysis showcase (mule ring + telecom + geo)
- **What it is (customer view):** a ready-made demo Space with synthetic money-mule and telecom-fraud data, saved views, Geo Views and five demo personas. It walks the Inspecto LA story from Alert to Investigation to Dossier.
- **Scores:** F 4 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**, as a demo asset. It is not a sellable SKU.
- **Evidence:**
  - Template: 72 files, about 40 300 CSV rows (`spaces/_templates/la-showcase/`). It covers a mule ring, 5 telecom typologies (Wangiri, IRSF, SIM box, subscription fraud, mules) over 8 sixty-day feeds, a geo scene with 3 Geo Views, 3 Alert Rules, 3 pattern packs and 4 link-analysis views (`template.toon` `contents[11]`).
  - It ships as a turnkey demo bundle. `-Edition Advanced -Product la -Distribution demo` bakes `la-showcase` pre-seeded into `inspecto-demo-<platform>.zip`: unzip, `serve-demo`, pick a persona (`solution-pack/la-showcase/admin-guide-variants-runbook.md` §0).
  - Tests: `LaShowcaseTemplateTest` 6 @Test, `LaShowcaseGoldenTest` 3, `ControlApiLaShowcaseFiguresTest` 1, `ControlApiLaShowcaseGeoSceneTest` 3. Runners: `tools/la-demo-smoke.mjs`, `seed-la-showcase.mjs`, `gen-la-showcase-data.py`, `gen-la-telecom-data.py`.
  - Live, Postgres variant (2026-10-13): `la-demo-smoke` 17/17, also after a restart, and `PgInvestigationStoreContractTest` 45/0/0 (variants runbook, "Not verified" section).
  - Live, browser, 2026-10-15: analyst coverage (`module/la-core/tests-live-drives.md#analyst-coverage-test-ui-only-impersonated-analyst-2026-10-15`) on this Space. 11 use cases: 6 PASS, 4 PARTIAL, 1 with axe 0 violations. 16 findings: 5 fixed, 11 open, all P3 except fixed P2s.
  - The stakeholder demo runbook `module/la-core/admin-guide-demo.md` (371 lines) still flags several beats "UNVERIFIED (rehearse first)": compare-and-seal, the masked-id set, and the la-app approve-then-step text.
- **Strengths vs market:**
  - A pre-seeded, one-click demo with governance on screen: masking, four-eyes on fan-out, Drafts, a sealed Dossier. i2 and Linkurious demos typically show charting, not evidential custody (general market).
  - It is multi-typology and multi-surface (graph, geo, telecom MSISDN profile) in one Space, and the planted outcomes are pinned by golden tests, so the demo cannot silently drift.
- **Gaps vs market / RFP risk:**
  - It is not in any Offering although brochures sell it with Advanced/Preview (`OFFERING-DECLARATION-DRIFT-1` D2).
  - Synthetic data only. "Recorded precision claims: none" (la-audit runbook §6 states the same policy). Buyers comparing with Quantexa or Featurespace-style references will ask for detection rates on real data: no row.
  - Some walk-through beats are still unrehearsed, and 11 analyst-coverage findings are open (F-7, F-10, F-11, F-2, F-3 and others). They are tracked only in `tests-live-drives.md`: no BACKLOG row.
  - It needs Demo Auth (Advanced/Preview). A Standard customer with real OIDC cannot use the seeded personas. The default runbook says the "Advanced or Standard bundle" works, but `minEdition: advanced` refuses Standard: no row.
- **RFP stance on the 5 most-asked requirements (LA proof-of-value / demo content):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Live demo of ring detection on representative data | Comply | pre-seeded demo zip; `LaShowcaseGoldenTest`; analyst coverage UC1 PARTIAL |
| Telecom fraud typologies (SIM box, IRSF, Wangiri) | Comply (demo) | telecom corpus + Alert Rules in template; synthetic only |
| Governance shown (masking, four-eyes, audit) | Comply | UC5 four-eyes PASS, UC10 audited reveal PASS |
| Evidence export that can be verified | Comply | UC6 Dossier "all 9 recorded hashes agree" |
| Run on the customer's own data in a PoV | Partial | views and Jobs must be re-pointed by hand; no mapping wizard for the pack |
- **To reach the next level:** list it in `offerings/preview.toon` (close D2); move the 11 open analyst findings onto BACKLOG rows; rehearse and remove every "UNVERIFIED" beat in `admin-guide-demo.md`.

#### solution-pack.la-showcase-off — Link Analysis showcase (feature off)
- **What it is (customer view):** an empty companion Space with Link Analysis switched off. It shows that a Space can disable the capability (`/inv/*` answers 404 `MODULE_DISABLED`) while the Space's other routes keep working.
- **Scores:** F 1 · B 3 · Q 2 · E 3 · R 2 → **Level L2 (Prototype)**. It is a demo prop, not sellable.
- **Evidence:**
  - Template: 5 files: `modules.toon` (geoLink disabled), roles, demo users, an SLA example and `template.toon` (`spaces/_templates/la-showcase-off/`).
  - `LaShowcaseTemplateTest` asserts the template has no registry, and that its `roles.toon` equals la-showcase's (fixed in `f1b64ce03` after it drifted; la-audit runbook §6 "Known limits").
  - Admin guide: 2 steps, create then verify (`solution-pack/la-showcase-off/admin-guide-la-showcase-off.md`, 14 lines). No live drive is recorded for this Space. The variants runbook's live record covers the Postgres variant only.
  - `OFFERING-DECLARATION-DRIFT-1` recommends leaving it unlisted in every Offering.
- **Strengths vs market:** it answers the RFP line "can a capability be disabled per tenant or business unit" in under a minute, using the platform's per-Space module switch.
- **Gaps vs market / RFP risk:**
  - Nothing asserts 404 `MODULE_DISABLED` against a Space created from this template. I found only the template-shape assertions: no row.
  - The demo users ship but no data, so it demonstrates nothing beyond a 404.
- **RFP stance on the 5 most-asked requirements (tenant feature control):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Disable a module per tenant/Space | Comply | `modules.toon`; behaviour unverified on this template |
| Other routes unaffected | Partial | stated in `template.toon`; no test found on this template |
| Same identities across Spaces | Comply | roles identical, `LaShowcaseTemplateTest` |
| Audit of the switch change | Not offered here | no audit beat in the pack |
| Self-service toggle in UI | Partial | config file; UI toggle unverified |
- **To reach the next level:** add a real-HTTP test that creates a Space from this template and asserts `/inv/*` 404 plus `GET /pipelines` 200, or fold the prop into the la-showcase runbook and retire the separate item.

#### solution-pack.la-audit — Link Analysis audit showcase (procure-to-pay and GL)
- **What it is (customer view):** an internal-audit demo that turns procure-to-pay and journal data into a graph. It surfaces a fictitious vendor, a shell cluster, split invoices, a single point of control and an intercompany round trip, and ends with a Dossier that fails verification when one byte changes.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**, as a demo asset.
- **Evidence:**
  - Template: 45 files, about 4 000 CSV rows. 5 Pipelines; 12 Datasets (6 feed, 6 link); 6 `sql.template` link Jobs plus index and detect Jobs; 3 views; 5 personas; a committee portal (`spaces/_templates/la-audit/`, `portal/committee-portal.html`).
  - Tests: `LaAuditTemplateTest` 6, `LaAuditGoldenTest` 9; `tools/la-audit-smoke.mjs`, `seed-la-audit.mjs`, `gen-la-audit-data.py`, each with `.test.mjs`.
  - Live rehearsal on 2026-10-16 (Advanced Demo Auth): smoke 30/30. In the browser: Cut points and Betweenness, Dossier verify, four-eyes. Over HTTP: 7 server Graph Runs, Draft rebase/promote, embed mint/revoke (`solution-pack/la-audit/admin-guide-showcase-runbook.md` §6).
  - The rehearsal found and fixed 5 defects: the seed silently building nothing, a refused Alert Rule, the Draft entity, the bridge plant, and portal framing. That is evidence the pack was driven, not only written.
  - Recorded "Not driven": the in-browser Jaccard, Link prediction and spanning forest; the Draft and Members panels in the browser; GL cycles in the browser.
- **Strengths vs market:**
  - Segregation-of-duties and vendor-employee identity overlap shown as structure (bridges, cut points), not as row filters. Classic audit analytics tools (Caseware IDEA, Diligent/ACL, general market) are table-first.
  - The sealed Dossier gives a workpaper with tamper evidence, which external auditors value.
- **Gaps vs market / RFP risk:**
  - Breadth is narrow next to audit analytics suites (general market: journal-entry testing libraries, Benford, sampling). It covers 6 planted stories. No row for an audit test library.
  - The neutral `generic` Domain Profile labels approval graphs generically, and masked ids leak into Investigation titles ("Vendor masked:… workpaper"). Recorded as "Observations, not fixed": no row.
  - Synthetic data, no precision claims, not in any Offering (`OFFERING-DECLARATION-DRIFT-1` D2).
- **RFP stance on the 5 most-asked requirements (audit analytics / P2P fraud):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Vendor-employee conflict detection | Comply (demo) | V-117/E-31 plant; `LaAuditGoldenTest` |
| Split-invoice / threshold avoidance | Comply (demo) | 30 invoices under 10 000 by E-23 |
| Duplicate payments | Comply (demo) | `ap_duplicate_payments` Job, 13 pairs |
| Tamper-evident workpaper | Comply | Dossier verify and fail on one altered hash (smoke 30/30) |
| Journal-entry test library / ERP connectors | Not offered | CSV feeds only; no SAP/Oracle connector in the pack |
- **To reach the next level:** add an audit Domain Profile (labels), file rows for the two observations, and list the pack in an Offering.

#### solution-pack.la-claims — Link Analysis claims showcase (insurance SIU)
- **What it is (customer view):** an insurance Special Investigations Unit demo. A staged-accident ring behind one garage, one clinic and one lawyer is hidden among 380 claimants and 520 claims, with benign look-alikes, geo clustering and identity resolution.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**, as a demo asset.
- **Evidence:**
  - Template: 44 files, about 3 350 CSV rows, 6 feeds. `claims_links` covers 7 link kinds. 3 views, 3 Geo Views, a partner portal; masked `party` Entity Type (`spaces/_templates/la-claims/`).
  - Tests: `LaClaimsTemplateTest` 5, `LaClaimsGoldenTest` 8, which pins the ground truth of every planted story; `tools/la-claims-smoke.mjs`, `seed-la-claims.mjs`, `gen-la-claims-data.py`.
  - Live rehearsal on 2026-10-16 (Advanced Demo Auth, port 8142): `la-claims-smoke --embed` 29/29. Geo, identity resolution (through the API), the masked Entity profile, Dossier with external references, and portal framing plus revoke were verified (`solution-pack/la-claims/admin-guide-showcase-runbook.md` lines 3–4, 62–86).
  - Scene 0–2 (seed and the busy clinic) is marked "UNVERIFIED on the canvas". Run times are not recorded (runbook line 107).
- **Strengths vs market:**
  - Benign look-alikes (a family sharing a phone, a busy clinic) are planted, so the presenter can show "centrality alone is not guilt". This answers the false-positive objection that SIU buyers raise against network scores (general market).
  - Masked party identities with audited reveal and an embed portal for partner review.
- **Gaps vs market / RFP risk:**
  - Claims-fraud leaders (general market: Shift Technology, FRISS, SAS) sell scoring at first notice of loss, text and image analytics, and policy-system integration. This pack is graph-only over CSV extracts: no row.
  - Identity resolution here is an asserted merge. Fuzzy merge suggestions were retired to §6 (`LA-I2-GAPS-RESIDUALS-1`, D-M1), so duplicate-claimant detection relies on the analyst.
  - Not in any Offering (`OFFERING-DECLARATION-DRIFT-1` D2). There is a mobile-money/claims "vertical" pitch, but no billable claims SKU exists.
- **RFP stance on the 5 most-asked requirements (insurance claims fraud):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Organised-ring detection across claims/parties/providers | Comply (demo) | `LaClaimsGoldenTest`; smoke 29/29 |
| Geo clustering of incidents | Comply (demo) | `staged_accident_junction_map` verified live |
| Duplicate identity resolution | Partial | asserted merge only; fuzzy suggestions retired (D-M1) |
| Real-time scoring at first notice of loss | Not offered | batch link Jobs on cron |
| Integration with claims system / case handoff | Partial | inbound/outbound integration routes exist (Standard+); not shown in this pack |
- **To reach the next level:** rehearse scene 0–2 on the canvas and record run times; add a claims-system handoff beat using `POST /inv/investigations/{id}/handoff`; decide the Offering listing.

#### solution-pack.la-mobile-money — Link Analysis mobile money
- **What it is (customer view):** a mobile-money fraud and AML demo: 420 wallets, 40 agents and 14 days with seven planted stories (agent-assisted structuring, mule chain, device farm, cycle, dormant wake, benign biller hub, Alert to Case), plus a regulator portal.
- **Scores:** F 3 · B 3 · Q 3 · E 3 · R 3 → **Level L2 (Prototype)**. The cap allows L3, but the browser walk-through was never done.
- **Evidence:**
  - Template: 41 files, about 7 000 CSV rows (14 landed days plus staged days). `mm_links` covers 6 link kinds; masked `mm_kyc` reference; `portal/regulator-portal.html` (`spaces/_templates/la-mobile-money/`).
  - Tests: `LaMobileMoneyTemplateTest` 5, `LaMobileMoneyGoldenTest` 9; `tools/la-mm-smoke.mjs`, `seed-la-mm.mjs`, `gen-la-mm-data.py`.
  - Live pass on 2026-10-16: seed `--apply` clean, smoke **10/10**. "The browser walk-through of the Graph page is not part of this pass" (`solution-pack/la-mobile-money/admin-guide-showcase-runbook.md` lines 3–4).
  - Scene M-10 (dormant wake) is UNVERIFIED: "the pack is not shipped for this vertical". M-13 (Dossier) is UNVERIFIED on this Space (runbook lines 85, 90).
  - Index build is on a 5-minute cron because `la.index.build` fires only on a clock or a Pipeline commit (runbook line 22).
- **Strengths vs market:**
  - Agent-network typologies (agent-assisted structuring, cash-out concentration) are specific to mobile money and seldom pre-packaged by general LA tools (general market).
  - The regulator portal embed shows a masked, revocable, read-only Investigation for a supervisor.
- **Gaps vs market / RFP risk:**
  - No recorded browser rehearsal. Two scenes are unverified, one of which depends on a temporal pattern pack not shipped for the vertical: no row.
  - It overlaps the billable `solution-pack.mobile-money` (Standard). The preview pack and the GA pack sit side by side, and the relationship between them is not documented in the brochures I read: no row.
  - Synthetic data only; not in any Offering (`OFFERING-DECLARATION-DRIFT-1` D2).
- **RFP stance on the 5 most-asked requirements (mobile-money fraud/AML):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Structuring under KYC tier limits | Comply (demo) | AG-18 plant; golden test |
| Mule / pass-through chain tracing | Comply (demo) | `HUB-01`→`AG-39` 3 hops; pass-through 0.98 |
| Device / shared-identity farms | Comply (demo) | `mm_device_farm` view |
| Regulator read-only access | Partial | portal embed; this Space's Dossier unverified |
| Real-time transaction monitoring | Not offered | daily files plus cron link Job |
- **To reach the next level:** do one browser rehearsal and record it; ship or drop the temporal pack beat (M-10); state in the brochure how this pack relates to `solution-pack.mobile-money`.

#### solution-pack.la-daily-feed — Link Analysis daily feed
- **What it is (customer view):** a reusable daily feed (one telecom XDR CSV per UTC day, partitioned by event date) that lands each day, checks freshness and row-count band, and appends it to the Link Analysis index. It is the only priced Link Analysis pack.
- **Scores:** F 2 · B 3 · Q 4 · E 2 · R 2 → **Level L2 (Prototype)** in its listed edition (Basic). The same pack on Standard and up would rate L3.
- **Evidence:**
  - Product map: `billable: true, orderCode INS-SP-LA-DAILY-FEED, billing flat, status ga` (`product-map/solution-pack/la-daily-feed.toon`). Listed in `offerings/basic.toon` `contentPacks`.
  - Template: 12 files. Pipeline plus schema; poll Collector (`XDR_{yyyyMMdd}.csv`); freshness and index-lag Alert Rules; row-band Expectation; `la_index_build` and `la_rows_band_evaluate` Jobs. Only **2 sample days of a few rows each** (`data/samples/daily_xdr/`).
  - Tests: `LaDailyFeedTemplateRulesTest` 6. `LaDailyIngestAcceptanceTest` covers 7 days, a late day, a re-run, a correction, a part-count change, a missing day and a dimension change. `ScheduledDailyChainTest` (geo-link) runs on the scheduler alone. Benches `LaDailyIngestScaleBench` / `DailyIndexScaleBench`: 10^7 rows a day ingest in 35–40 s, 10^8 in 489 s, APPEND 31 s onto 10^8 (BACKLOG `LA-DAILY-INGEST-1`).
  - Live drive on 2026-10-14 (`module/la-core/tests-live-drives.md#data-trust-drive-la-data-trust-1-s10-2026-10-14`): 8 steps, 1 FAIL (corrected day, since fixed by `f3a90e6a3`). At that time the template shipped no index Job; the Jobs were added later and are pinned by `theTemplateShipsTheJobsThatIndexAndEvaluateOnEveryCommit`.
  - **Edition mismatch.** The pack ships Alert Rules, but the Alert Rule dispatcher (`alert.dispatch`, declared by `inspecto-ops`) is Standard and up (`layer/platform/architecture-editions.md` §28; `OFFERING-DECLARATION-DRIFT-1` D1, closed 2026-10-20). The template has no `minEdition`, so the `tools/check-offerings.mjs` check 5 guard cannot catch it.
  - The index Job runs as `owner: admin` (`config/jobs/la_index_build_job.toon`), but Basic ships no Authenticator. By code read (2026-10-23, `LA-DAILY-INGEST-1`): on an open server the run authority is skipped (`JobAuthority.runAuthority`) and the template's Dataset has no `shares`, so `ComponentAccess.canViewAs` allows `admin`; with an Authenticator, `TemplateSeedGate.stampJobs` re-stamps the owner to the applier unless the applier holds `canConfigureAccess`. `LaIndexBuildJob` and `ScheduledLinkIndexBuilder` consult no edition feature (`platform/inspecto-engine/.../LaIndexBuildJob.java`, `la/inspecto-geo-link/.../ScheduledLinkIndexBuilder.java`), so the index probably builds on Basic while every index-consuming Investigation route is 503 there (unverified live).
  - The row-band Expectation "does not judge a day until 3 prior days exist" (`admin-guide.md`), and only 2 sample days ship. The out-of-box verify cannot show the band working.
- **Strengths vs market:**
  - The ingestion is measured to 10^8 rows a day with append-only index refresh and data-trust signals (freshness, gaps, quarantine, index lag). Neo4j Bloom and Linkurious leave this to customer ETL (general market).
  - The feed contract (stable name, one event date per file, gap detection by `{seq}`/date template) is documented for database exports too.
- **Gaps vs market / RFP risk:**
  - Sold in Basic, where its Alert Rules cannot dispatch and Investigations, the consumers of the index, are absent (`OFFERING-DECLARATION-DRIFT-1` D1).
  - No Hive/Kerberos or JDBC Collector path for Link Analysis, and no SCD2 / as-of join (deferred `D-ING4`/`D-ING5` in `LA-DAILY-INGEST-1`, P2, owner calls). Leaders ingest from warehouses directly (general market).
  - A same-name corrected file takes effect on the shipped feeds since 2026-10-14 (`2196ce8ea`: `collector.duplicate` checksum + `on_change: reprocess`; partition replaced, one `REDELIVERED`); a feed on the default path markers still skips it with `DUPLICATE_SKIPPED`.
  - Scale beyond 10^8 is unmeasured, and so are a single 10^8-row file and entity cardinality above 12 M at 10^8 rows (12.1 M nodes measured at 10^7 edges on 2026-10-20; `LA-DAILY-INGEST-1`).
- **RFP stance on the 5 most-asked requirements (scheduled LA ingestion):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Scheduled daily ingestion with incremental index refresh | Comply (Standard+) / Partial (Basic) | `ScheduledDailyChainTest`; Basic lacks the consumers |
| Late, missing and corrected file handling | Comply | `LaDailyIngestAcceptanceTest`; a corrected same-name day replaces its partition on the shipped feeds |
| Freshness / completeness alerting | Partial | Alert Rules ship; dispatch is Standard+ (D1) |
| Database / warehouse feeds (Hive, JDBC) | Roadmap | `D-ING5` deferred until a named customer feed |
| Proven scale for national telco volumes | Partial | 10^8/day on a laptop, synthetic; 10^9 not measured |
- **To reach the next level:** settle D1: set `minEdition: standard` or move the Alert Rules out of the Basic variant. Ship at least 4 sample days so the band demo works. Verify `owner: admin` behaviour on a Basic bundle and file a row for the result.

#### Inspecto LA (`la-app`) — the standalone Link Analysis product (module.la-core and its la/ modules)
- **What it is (customer view):** a graph investigation product over existing Datasets. Analysts explore who is connected to whom, run 29 graph algorithms, record the work as a sealed Investigation and hand it on as a verifiable Dossier, a Case link or a standing detection. It ships as a standalone app (`package.ps1 -Product la`) or inside the console (`docs/GLOSSARY.md` "Inspecto LA", `docs/EDITIONS.md` "UI flavor").
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**.
- **Evidence:**
  - Modules: `la-core`, `la-storage`, `la-api`, `geo-link`, `la-store-pg` (`la/`). Routes: `la-api` 90 (90 in OpenAPI), `geo-link` 7 (`module/la-api/api-routes.md`, `module/geo-link/api-routes.md`).
  - Test classes: geo-link 108 (90 real-HTTP), la-api 40, la-core 23, la-storage 12, entity-list 5 (generated `tests-inventory.md` per hub). Plus 4 golden corpora tests in `platform/inspecto-graph`. UI: `projects/link-analysis` 130 specs, `projects/la-app` 9 specs (15 non-spec files: a thin shell).
  - Live evidence:
    - analyst coverage on 2026-10-15: 11 use cases, axe 0 violations (`tests-live-drives.md`);
    - data trust drive on 2026-10-14;
    - real OIDC sign-in against WSO2 IS 7.3.0 on 2026-10-13 (`admin-guide.md`);
    - Postgres store on 2026-10-13;
    - stakeholder Q&A with 22 PROVEN-tagged answers and 16 NOT YET mentions (`admin-guide-demo.md`).
  - Edition placement is gated in code: Basic is the explorer; the Investigation surface answers 503 via `EditionFeatures.INVESTIGATIONS` (`ControlApiBasicEditionLinkAnalysisTest`, 2 tests, real HTTP).
  - Open work: P2 `LA-DAILY-INGEST-1` (owner calls only). P3s: `LA-I2-GAPS-RESIDUALS-1`, `LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1` (defect, mostly done), `LA-ENTITY-PROFILE-RESIDUALS-1`, `LA-CONFIG-LIMITS-RESIDUALS-1`, `LA-A11Y-AUDIT-1` (screen-reader reading still person-only), `LA-INVESTIGATION-STORE-DESIGN-1`, `LA-INVESTIGATION-OPS-DEFERRED-1`, `LA-EDITIONS-RESIDUALS-1` (`module/la-core/index.md`).
  - Deliberately declined (BACKLOG §6 line 774): retention/purge of Investigation evidence (`D-U8`), a G6 `timebar`, and a server-rendered Dossier PDF.
- **Strengths vs market:**
  - Evidential custody is built in: a sealed op log, replay, a Dossier verifiable byte for byte, four-eyes on expansion, and masking with audited reveal. i2 ANB / Linkurious / Bloom chart well but do not seal the analysis path (general market). This is the clearest differentiator.
  - No separate graph database. It projects directly from DuckDB/Parquet Datasets with a versioned link index (10^8 edges measured), and server and browser algorithms are kept in parity (`GraphEngineParityTest`, 29 algorithms). That is lighter than a Neo4j + Bloom or Quantexa deployment (general market).
  - The operational loop goes from Alert Rule to Investigation to Case to standing detection inside one product. Linkurious needs a separate case tool; i2 ANB needs EIA/iBase (general market).
- **Gaps vs market / RFP risk:**
  - Entity resolution is assertion-based. Fuzzy merge suggestions were retired (`LA-I2-GAPS-RESIDUALS-1`, D-M1). Quantexa's core value is probabilistic entity resolution at billions of records (general market). This is the largest functional gap: no row beyond the §6 retirement.
  - Scale and topology: 10^9 is not measured, and multi-pod is not complete. The Helm chart is `replicas: 1` (`SCALEOUT-HELM-NPOD-1` P2, `LA-INVESTIGATION-STORE-DESIGN-1` P3). The stakeholder Q&A says NOT YET for multi-pod and for a two-host drill.
  - Retention / legal hold of Investigation evidence is declined (`D-U8`), yet `docs/EDITIONS.md` and the Advanced brochure list "retention, legal hold, residency" as Advanced roadmap. That is a contradiction, and legal hold and residency have no row.
  - Analyst ergonomics vs i2: no timeline / time-bar view (the G6 `timebar` was declined; only a timeline filter exists, `la-core/user-guide-la-core.md` §6), no i2 ANX import/export, no unstructured/document ingestion: no rows. Precision on real data is not measured (stakeholder Q&A line 201).
- **RFP stance on the 5 most-asked requirements (link analysis / investigation):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Interactive graph exploration, paths, neighbours, patterns, algorithms | Comply | 90 `la-api` routes; 29 algorithms; analyst coverage UC1–UC3 |
| Entity resolution across sources | Partial | identity assertions and Entity profile pages; no fuzzy/probabilistic ER |
| Court-ready, tamper-evident evidence export | Comply | sealed log + Dossier verify (UC6); PDF via browser only (`LA-DOSSIER-OUTPUT-1` declined) |
| Scale and HA (billions of edges, multi-node) | Partial | 10^8 measured; multi-pod incomplete (`SCALEOUT-HELM-NPOD-1`) |
| Access control, masking, audit, SSO | Comply (Standard+) | OIDC proven with WSO2; enforced masking + audited reveal; Basic is display-only |
- **To reach the next level:** run the two-host Investigation-store drill and close `SCALEOUT-HELM-NPOD-1`; decide and record (with a row) fuzzy entity resolution and Investigation retention/legal hold, and fix the EDITIONS/brochure contradiction; obtain one reference pilot on real data with a measured precision figure (L5 also needs reference customers, and none are recorded).

---

#### edition.basic — Basic
- **What it is (customer view):** the entry build for one analyst on one machine, with no sign-in. It gives Collectors, Pipelines, Expectations, Datasets, SQL, dashboards, the Link Analysis explorer, Entity Lists and three content packs.
- **Scores:** F 2 · B 4 · Q 3 · E 2 · R 2 → **Level L2 (Prototype)** for enterprise RFP fitness. By design it is a single-analyst desk.
- **Evidence:**
  - Offering: `offerings/basic.toon`, with modules `connectors, entity-list, la-storage, la-core, la-api, geo-link` and contentPacks `business-assurance, la-daily-feed, orders-starter`. Posture: `authMode none`, no Postgres.
  - Product map: `INS-ED-BASIC`, `billing per-node`, `enforcement: build`, `ga` (`product-map/edition/basic.toon`). `ModuleManifest.entitlementKey` is still "reserved (D-MR3: no licence engine)… never consulted" (`platform/inspecto-util/.../ModuleManifest.java`), but D-MR3 was superseded 2026-11-11 by D-PA22 (`layer/platform/spec-module-reorg-decisions.md`): `Entitlements` verifies an offline signed `conf/entitlement.toon`, which narrows the Installed gate; with no file every installed module is entitled.
  - Gating is tested by falsification: 9 `No*ShipsInThePersonalBuildTest` classes in `inspecto/src/test/java/com/gamma/control/`, plus `NoBackupTaskShips…` and `NoChannelShips…` (engine), `AbsentModuleRoutesTest`, `BootstrapEditionTest`, `EditionFeatureGateTest`, `ControlApiBasicEditionLinkAnalysisTest`.
  - Packaging: `release.yml` builds and signs a Basic bundle (`package.ps1 -Edition Basic`, line 189). Staged jars: 23 (`docs/EDITIONS.md` "Staged jars"). The Basic 503 has "not been re-probed on a built Basic bundle" (la-showcase variants runbook).
  - Masking on Basic is display only: "anyone may reveal" (`docs/EDITIONS.md` "Basic — the Link Analysis explorer").
  - Its own content is mis-tiered. `business-assurance` and `la-daily-feed` ship Alert Rules whose dispatcher is Standard+ (`OFFERING-DECLARATION-DRIFT-1` D1).
- **Strengths vs market:**
  - The install is zero-dependency (one process, no database, no IdP), which suits a presales or analyst desk. That is comparable to i2 ANB desktop or Neo4j Desktop/Bloom free use (general market).
  - It is upgraded by swapping the build, not migrating: Spaces and configuration carry over (`edition/basic/brochure-basic.md`). This is held by the build-flavor model (`architecture-editions.md`).
- **Gaps vs market / RFP risk:**
  - No authentication at all. Competitors' entry tiers (Linkurious, Neo4j Enterprise starter, general market) still have users and roles. Basic cannot answer any SSO/RBAC RFP line (by design; no row needed, but it limits Basic to non-regulated use).
  - Per-node billing with no metering or licence key. The node count is honour-system: no row (D-MR3 decided "none").
  - Packaging ambiguity: the Basic brochure says the Link Analysis explorer is included, while `addon.linkAnalysis` is a separately priced Add-on (`INS-AO-LINK-ANALYSIS`, flat). Whether a Basic buyer pays twice is unstated: no row.
  - Content packs whose Alert Rules cannot fire (`OFFERING-DECLARATION-DRIFT-1` D1).
- **RFP stance on the 5 most-asked requirements (entry SKU):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Ingest, validate, quality-check files | Comply | core platform, all editions |
| Users, roles, SSO | Not offered | `authMode none` |
| Audit trail | Partial | local audit log readable (`AuditLogRoutes`); no authenticated actor |
| Link analysis | Partial | explorer only; Investigations/Dossier are Standard |
| Alerting on data problems | Partial | Alert Rule dispatch is Standard+ (D1) |
- **To reach the next level:** close D1 (move or gate the two packs); state in the brochure and price book whether `INS-AO-LINK-ANALYSIS` applies to Basic; decide whether a minimal local sign-in belongs in Basic.

#### edition.standard — Standard
- **What it is (customer view):** the team build. It adds OIDC sign-in, roles, secrets, enforced masking, Incidents, Cases, SLA, Reconciliation, Risk Scores, Screening, Anomaly Detection, Regulatory Reporting, full Link Analysis (Investigations, Dossiers, Drafts, four-eyes) and five content packs.
- **Scores:** F 4 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**.
- **Evidence:**
  - Offering: `offerings/standard.toon`, with 18 modules over Basic, 14 Add-ons marked `status: built`, and contentPacks `mobile-money, aml, payment-fraud, telco-fraud, telco-ra`. Posture: OIDC, parquet events, bundled Postgres sidecar.
  - Staged jars: 41 first-party, 42 total (`docs/EDITIONS.md`). `release.yml` builds a signed Standard bundle without the policy jar (lines 238–244).
  - Absent-surface parity tests per module: `AnomalyAbsentSurfaceParityTest`, `ReconAbsentSurfaceParityTest`, `ScoringAbsentSurfaceParityTest`, `ScreeningAbsentSurfaceParityTest`, `EntityListAbsentSurfaceParityTest`, `GeoLinkAbsentSurfaceParityTest`, plus `OpsEditionFeaturesTest`.
  - The `tools/check-offerings.mjs` guard (CI `guards`) asserts Offering == bundle == pom profile and checks `minEdition` against each pack (check 5).
  - Real IdP sign-in proven once against WSO2 IS 7.3.0 (`module/la-core/admin-guide-la-core.md#2-real-oidc-sign-in-runbook-la-app-local-wso2-identity-server`, 2026-10-13).
  - ~~Open: `OFFERING-DECLARATION-DRIFT-1`~~ closed 2026-10-20 (`78fc6e583`).
- **Strengths vs market:**
  - One SKU spans assurance, case and investigation, where competitors sell separate products. Examples: Linkurious + a case tool; i2 ANB + EIA; Subex or Mobileum modules priced per function (general market, and `layer/product/brochure-competitive-landscape.md`).
  - The full evidential Link Analysis is in the mid tier, following the principle "never sell weaker analysis or weaker evidence" (`docs/EDITIONS.md` "Inspecto LA feature placement").
- **Gaps vs market / RFP risk:**
  - Single node only. HA and scale-out sit in Advanced, and even there they are not built (`SCALEOUT-HELM-NPOD-1` P2).
  - No ABAC (Advanced only), which matters for multi-business-unit banks.
  - Nothing has shipped under this edition name. The last tag is `v3.9.0`, and 4.0.0 (the rename release) is pending (`git tag`; GLOSSARY migration note): no row for a first 4.0 release.
- **RFP stance on the 5 most-asked requirements (team / departmental SKU):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| SSO via corporate IdP | Comply | `inspecto-oidc`; WSO2 run 2026-10-13 |
| RBAC + masking + audited reveal | Comply | `docs/EDITIONS.md` LA placement; masking enforced from Standard |
| Case and Incident workflow with SLA | Comply | `caseManagement`, `incidents`, `workflowSla` Add-ons built |
| High availability | Not offered | single node; HA is Advanced roadmap |
| Industry content (fraud, AML, RA) | Comply | 5 content packs with `minEdition: standard` |
- **To reach the next level:** cut 4.0.0 so the edition exists as a released, signed artifact; run the real-IdP runbook against a second IdP (Keycloak/Entra). (`OFFERING-DECLARATION-DRIFT-1`, the third step here, closed 2026-10-20.)

#### edition.advanced — Advanced
- **What it is (customer view):** the organisation build. It is Standard plus attribute-based access policy (`policy`), a PostgreSQL Investigation store for multi-node use (`la-store-pg`), operational objects on Postgres, and the intelligence module.
- **Scores:** F 3 · B 3 · Q 3 · E 3 · R 2 → **Level L3 (Pilot-ready)**, as Standard plus ABAC. Its headline HA/multi-node promise is not defensible yet.
- **Evidence:**
  - Offering: `offerings/advanced.toon` adds 3 modules (`policy`, `la-store-pg`, `intelligence`); posture `objectsBackend postgres`. 44 first-party jars (`docs/EDITIONS.md`).
  - `PolicyEditionFeaturesTest`, `ObjectsBackendEditionBootTest`. `PgInvestigationStoreContractTest` ran live 45/0/0 against Postgres 18 on 2026-10-13 (la-showcase variants runbook).
  - `la-store-pg` is "inert until `-Dinvestigations.backend=db`: every edition, Advanced included, defaults to the filesystem" (`docs/EDITIONS.md` "Assembly model").
  - The brochure promises "multi-node, highly-available deployments" (`edition/advanced/brochure-advanced.md`). But the only Helm chart is a `replicas: 1` StatefulSet "NOT MULTI-REPLICA SAFE" (`SCALEOUT-HELM-NPOD-1` P2), and the D14–D16 decisions are owed (`SCALEOUT-D14-D16-DECISIONS-1` P2, `SCALEOUT-OBJSTORE-DATABASE-DIR-1` P2).
  - "Scale-out, retention, legal hold, residency (not built yet)" is in the placement table (`docs/EDITIONS.md`). Investigation retention is declined (`D-U8`, BACKLOG line 774), and legal hold and residency have no row.
  - Its own showcases (`la-*` with `minEdition: advanced`) are listed in no Offering (`OFFERING-DECLARATION-DRIFT-1` D2).
- **Strengths vs market:**
  - ABAC that "can only narrow" what roles allow is a safe-by-construction model that RFP security reviewers favour (general market). Policy is a separate module, so Standard never carries it (release.yml lines 236–239).
  - An enterprise tier without a separate graph database or cluster keeps the SBOM small (`docs/EDITIONS.md` "Security direction").
- **Gaps vs market / RFP risk:**
  - HA/DR and scale-out are the reason to buy an enterprise tier (Palantir, Quantexa and Neo4j Enterprise clustering, general market). Here they are P2 rows not yet built (`SCALEOUT-HELM-NPOD-1`, `SCALEOUT-D14-D16-DECISIONS-1`), with no two-host drill (stakeholder Q&A).
  - Retention / legal hold / residency are promised as roadmap while retention is declined (`D-U8`). Legal hold and residency have no row.
  - The intelligence module is listed, but its customer value in this tier is not described beyond "AI-assisted" (the brochure). I could not verify its scope here; it belongs to the AI group.
- **RFP stance on the 5 most-asked requirements (enterprise SKU):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| ABAC / fine-grained policy | Comply | `inspecto-policy`, `PolicyEditionFeaturesTest` |
| HA / multi-node / DR | Partial | PG store contract proven; multi-pod and Helm N-pod not built (`SCALEOUT-HELM-NPOD-1`) |
| Data retention and legal hold | Not offered | retention declined `D-U8`; legal hold no row |
| Data residency | Not offered | no row |
| Shared Postgres persistence | Comply | `la-store-pg` (opt-in), objects on Postgres |
- **To reach the next level:** build and drill N-pod (close the three SCALEOUT P2s); either build retention/legal hold or remove them from the Advanced promise and file the decision; tone the brochure down to what is proven until then.
