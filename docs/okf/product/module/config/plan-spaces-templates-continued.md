---
type: Capability
title: Spaces & tenancy (SPC) — Specification — Space Templates (continued)
description: Part 3 of 4 of Spaces & tenancy (SPC): 
tags: [spc, capability, spaces, tenancy, multi-space, space-root, migrator, templates, metadata-bundle, exchange, isolation]
timestamp: 2026-09-08T00:00:00Z
item: module.config
audience: internal
status: plan
verified: 2026-11-20 code-reviewed
code: platform/inspecto-config
---

# Spaces & tenancy (SPC) — Specification — Space Templates (continued)

Part 3 of 4 of [Spaces & tenancy (SPC)](plan-spaces.md) · previous: [Specification — Space Templates](plan-spaces-templates.md) · next: [Anomaly Model examples, bundles, decisions and verification](plan-spaces-bundles-decisions.md).

## 3. Specification (continued)

⚠ Method traps found: the browser pane scales screenshots (800 px) over a 1323 px viewport, so coordinate clicks land beside the control - use refs or
`javascript_tool` `.click()`; a viewer persona reaches the dashboard *editor* route (`/studio/dashboards/<id>`), there is no read-only view route to land on.
⚠ A mojibake scare was the shell: the server emits correct UTF-8 (`E2 80 94`) and Python's default Windows decoding mangled it.
Pins: `TelcoFraudTemplateGoldenTest.theAlertRulesAreEvaluatedOnAClockAfterTheDetectionJobs`, `.everyFeedSubscriberNumberColumnIsClassifiedMsisdn`.

**Story gaps closed 2026-10-14 (as built; open items in `TELCO-FRAUD-STORY-GAPS-1` and `TELCO-FRAUD-MASKED-DASHBOARD-1`).**
- **The alert model is BOTH (operator, 2026-10-14):** an Alert is per RULE and names no entity, an Incident promoted from it is per ENTITY (the offender sits in the `key` / `key.<column>` attributes, never in a title or message). **Built 2026-10-14 (`ALERT-PER-RULE-1`):** a `by` rule holds ONE Alert (`alertKey <rule>|*`, `breachedKeys` count, `firstBreachAt` / `lastBreachAt`, `breachedKeyDigests`) that names no entity, updated in place as keys join and leave and resolved when the last key heals; each breaching key still opens its own Incident, whose title for a caller who cannot reveal is a masked alias (`ObjectReadMasking`). The Alerts page therefore lists one row per rule with its count; the Incidents page lists one per offender. Stored per-key Alerts are folded on the first sweep. See `okf/product/addon/incidents/plan-incidents.md` (Per-entity Measure rules).
- **A missing delivery day raises `SEQUENCE_GAP` on a polled inbox** because each of the three feed pipelines declares a date-only `file_template` (`CDR_{yyyyMMdd}.csv` ...): without one `DeliveryCheck` never arms the manifest or the windowed gap check, the legacy `gap_detection.sequence` alone does nothing (`TelcoFraudFeedGapTest`).
- **Content added to the template:** 7 Expectations (non-null / range on the three feeds, `registry/expectations/`), 2 Decision Rules, a Case Rule (`config/ops/fraud_critical_burst_caserule.toon`, loaded from `*_caserule.toon`, `groupBy: msisdn`: one Case per msisdn offender holding that offender's CRITICAL Incidents in 24 h; threshold 1 because every sample offender sits in ONE typology, raise it to 2 to require a repeat offender; it filters on `severity`, NOT `priority`: a live run showed Alert-promoted Incidents carry no priority, so the first draft matched nothing; Incidents keyed by something other than msisdn, 12 of 30 on the sample, are not grouped; the Case carries a digest, never the raw number), a nightly `report` Job (`fraud_offender_export`) and a nightly `expectation.evaluate` Job (`fraud_expectation_evaluate`, 01:45, after the feeds land and before the 02:00 detection Jobs) so a bad row raises an ingest-quality Incident without anyone pressing Evaluate (`TelcoFraudTemplateGoldenTest.theNightlyExpectationJobRaisesAnIngestQualityIncidentForASeededBadRow`: a seeded out-of-range CDR duration fails exactly `tf_cdr_duration_in_range` and opens one Incident; the other six pass).
- **The sink Datasets classify their subscriber key (2026-10-18):** 10 of the 14 sinks declare their columns, `msisdn` / wangiri `caller` as `MSISDN` and identity `id_doc` as `PII`; a viewer without `canRevealLinkEntities` sees every dashboard bar with the same value under its `masked:` alias, a revealer the number (`TelcoFraudTemplateGoldenTest.everyDashboardTileRendersThePlantedOffendersAfterTheGoldenRun`). Limits: `PII` is not claimed by a telecom Entity Type, so `id_doc` stays raw in the default `typed` mode; the nightly offender export now writes aliases.
- **A masked key still charts:** a chart's category order is marked `orderBy[].cosmetic`; for a caller who cannot reveal the server drops it on a masked groupBy column and orders by the first Measure descending, so the bar Widgets answer 200 with aliases. Any other order over a masked column stays 422.
- **Row closed 2026-10-23 (live drive on an Advanced `-DemoAuth` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `-Distribution demo`)* bundle, `-NoRuntime`; a fresh Space from the gallery, the seeded feeds, all Jobs):**
  the template was REFUSED at create on every edition with Anomaly Detection (its tile Dataset `tf_subscriber_anomaly_latest` read the
  model's store under another id; the reserved-prefix gate accepts only the identity Dataset), now `anomaly_scores_tf_subscriber_usage_latest`
  (`ControlApiAnomalyTemplateApplyTest.theShippedTelcoFraudTemplateAppliesWithAnomalyDetectionPresent`); the 3 feeds ingested (3359 / 698 / 503 rows), the
  14 detection Jobs SUCCESS, `fraud_offender_export` wrote `data/exports/fraud_offender_export/*.csv` with aliased msisdns, `tf_anomaly_score`
  scored 307 lines and activated the pending Alert Rule; Home *Datasets written* read 14 / 14 writes and moved on the tick (details in
  `layer/product/plan-surfaces.md`); a Home 401 in the console is the 900 s access-token expiry, refreshed and retried (both 200).

**Every registry kind a template seeds is gated, not only `kpi`** (`ASSURE-KPI-DEFINITIONS-RESIDUALS-1` (1) ⚠,
2026-09-28). The seed gate is `TemplateSeedGate.require`, one table-driven pass over the staged tree before boot:
(1) **capability** — `ImportCapabilityGuard.checkFiles(…, newSpace=true)` over the staged `config/`, the same
kind→capability table `/spaces/import` and `/components/{kind}` use (`connection` → `canOnboardConnections`,
`alert-rule` → `canAuthorAlertRules`, `findings-spec` → `canManageIncidents`, an `event_prune`/`restore` Job →
`canAdminister`; a `registry/` file whose kind cannot be told is refused); run on the zero-Space recovery
create too (`TEMPLATE-RECOVERY-IMPORT-GATE-1`, 2026-10-03); (2) **save validation** — every `ComponentStore.WRITABLE_TYPES` component meets
what `ComponentRoutes.writeComponent` runs before its write: `validateKind(api, …)`, `AlertRoutes.parse` for an
`alert-rule` (edition feature, Investigation refusal, `by` Schema check), `DecisionRuleGuard.prepare` for a
`decision-rule` (the invoke-api gate, with the template's own `*_connection.toon` Connections counted as carried,
as `/spaces/import` counts them) — judged against the NEW Space's `config/` / `data/` through explicit-root overloads
(`validateKind`, `AlertRoutes.parse`, `RiskScoreRoutes.requireStorable`/`requireNotReserved`); a registry file of the
kind's own suffix (`ComponentStore.suffixFor` — `.csv` for a mapping) the store cannot read is refused. A seeded
Decision Rule is rewritten with `prepare`'s stamps: the applying actor is creator and maker, and the template's
`createdBy`/`updatedBy`/`restoredMakers` are dropped. The recovery create skips the kind-table capability but still
asks an invoke-api rule's `canWorkIncidents` (deliberate: outbound calls always need it); (3) `kpi` as above. First refusal → 403 / 422 naming the kind, no Space. `ControlApiSpaceTemplateSeedGateTest`
applies EVERY shipped template and pins both refusals. (4) **structure** (2026-10-04) — every staged Pipeline (`*_pipeline.toon`)
and Job (`jobs/*.toon`) runs through `SaveGate.check` with `Referents.MAY_ARRIVE_LATER` (a template's Connections are
filled in later, so a missing one is not a defect; a structurally invalid config such as an unread block is), judged from
the file's own directory; the first ERROR refuses the template. (5) **Connections** (2026-10-04, closes
`ASSURE-KPI-DEFINITIONS-RESIDUALS-1`) — every `*_connection.toon` runs `ConnectionRoutes.validateProfile`, what
`POST`/`PUT /connections` run before their write (the connector's option checks, and for `https` the egress host
check that refuses a numeric-form IP); a refusal is 422 `template connection '<file>' is refused`, no Space.

#### 3.5.1 The `payment-fraud` content pack — slice 1 (`ASSURE-PACK-PAYMENT-FRAUD-1`, 2026-09-30)

> **Closed as BUILT (operator, 2026-10-06).** Further typologies and customer sign-offs come on customer request.
> The Alert Rule over the template's own Risk Score output (`pf_high_risk_account`, `max(score) >= 60` by
> `model, entity_key`) ships as a deferred seed — see §3.5.2.

Wave 5.3 of `archived-documents/plans-archive/assurance-capability-plan.md`, generic half. `spaces/_templates/payment-fraud/` ships
config and a synthetic corpus; it uses the opt-in `processing.refusal` mode of the ingest engine. There is no new
Step Processor and no new route.

- **Corpus (WS-40).** There are three feeds over 2026-07-01..03: `payment_attempts`, `sim_changes` and `disputes`.
  `disputes` has only a day-3 file, because an empty CSV would quarantine as `empty`. The corpus is generated by
  `PaymentFraudCorpus` in the engine test sources, with the fixed seed `20260701`; `main <template>/data/samples`
  regenerates it. The golden test asserts that the committed CSVs are byte-for-byte what the generator writes. Every
  identifier is a synthetic token.
- **Runbook: a refused card-number file is deleted (operator decision 2026-10-04, PCI).** A refused file stays in
  the restricted store only for `refusal_retention_days` (default 7, 1..30; the template ships 30). After that the
  poll-cycle housekeeping deletes it, and the AUDIT event `ingest.refused.retention` records its stored name, size,
  sha256, reason and retention, never its content. To investigate a refusal, use that event and the
  `ingest.refused` event inside the window, then fix the file at its origin and resend it. Never copy a restricted
  file out of the store. Nothing replays from the store, so a deletion costs no replay
  ([Ingestion §3.6](../elt/plan-elt.md)).
- **Card-number scan at ingest (WS-40): what it covers, exactly.** The three Pipelines set
  `processing.refusal: restricted_quarantine`, `refusal_scan: card_number` and `refusal_retention_days: 30` (the
  maximum). The scan is the platform's (`RefusalQuarantine.cardScanSql`, [Ingestion §3.6](../elt/plan-elt.md)). The schemas carry
  no scan expression.
  - **What it reads:** every raw cell except the `refusal_scan_exempt` columns (default: none), before anything is
    written.
  - **Normalisation:** combining marks (Mn, Me) and format / zero-width characters (Cf) are stripped, and 41 Unicode
    Nd digit blocks are folded to ASCII. A well-formed date or timestamp is skipped.
  - **No IBAN skip.** It was removed in round 4, because a card number dressed as an IBAN evaded it
    (`DE00 4111 1111 1111 1111`, `ID12 4111111111111111`, both now golden trips). An IBAN column is exempted with
    `refusal_scan_exempt`.
  - **The scan fails CLOSED.** If it cannot run (a read or SQL error), the file is restricted with
    `INGEST_REFUSE:SCAN_FAILED`, never landed. Restricting is chosen over failing the batch because a failed batch
    leaves the unscanned file in the inbox, re-polled every cycle. (The batch error text no longer quotes cell
    values since 2026-10-03: [Ingestion](../elt/plan-elt.md).)
  - **The exempt list cannot switch the scan off.** A list covering every raw field is refused at load. A name that
    is no raw field is warned. Every control-plane write that changes a refusal key emits `pipeline.refusal.changed`
    (AUDIT, before / after).
  - **Candidates:** runs of digit groups joined by 1–5 non-alphanumerics. A candidate is one group of 13–19 digits or
    a 4-4-4-4 / 4-4-4-4-3 / 4-6-5 / 4-6-4 window.
  - **Accepted when both hold:**
    - Luhn-valid;
    - brand prefix **and** length match exactly this list, shared with the Java name/header check
      (`CardNumbers.IIN_REGEX`):

      | Brand | Prefix | Lengths |
      |---|---|---|
      | Amex | 34 / 37 | 15 |
      | Diners | 30 / 36 / 38 / 39 | 14–19 |
      | JCB | 35 | 16–19 |
      | Visa | 4 | 13 / 16 / 19 |
      | incl. Maestro | 50–59 | 13–19 |
      | Mastercard 2-series | 22–27 | 16 |
      | Discover / UnionPay / Maestro | 6 | 13–19 |

  - **Cost:** linear. A 1 MB digit cell costs under 2 s over a clean file.
  - **Golden cases.** 32 trips of published test numbers: every separator spelling, 2 and 3 separators, NBSP, en-dash,
    zero-width, a combining mark, four digit scripts, `+`, embedded text, CVV, expiry, Amex 4-6-5, JCB (plain and
    grouped), Diners 30/36/38 (incl. 4-6-4), Maestro 50/56/67, two numbers dressed as IBANs, and the number in `AMOUNT`
    (34 in all). 11 true negatives ingest,
    among them a Luhn-valid 15-digit IMEI (no JCB length), a DE IBAN in 4-digit groups and an E.164 number. The corpus's
    350 realistic references never trip.
  - **Measured false-trip rates** (`RefusalQuarantineTest.falseTripRatesAreMeasured`, 10 000 each, fixed seed, the
    production SQL):

    | Data | Rate |
    |---|---|
    | 16-digit numeric order ids | **4.39 %** |
    | DE IBANs in 4-digit groups | **3.79 %** (no IBAN skip since round 4) |
    | E.164 phone numbers | **0.95 %** (they collide with Diners 30/36/38/39 at 14 digits) |

    ⚠ Every false trip moves a WHOLE file to the restricted quarantine. The trade-off is the operator's: list a column
    known to carry no card data (an order id, an IBAN, a phone number) in `refusal_scan_exempt`. It is then not
    scanned at all, so a real number in it lands.
  - 🔴 **Not caught:** a number split across two cells; one glued to other digits with no separator; any other layout;
    a prefix or length outside the list; an unfolded digit script; anything in an exempt column.
- **File name and header line** are checked by the refusal mode itself (`CARD_NUMBER_IN_FILE_NAME` /
  `CARD_NUMBER_IN_HEADER`). The restricted row records a generated name that carries the code.
  - A number inside a *malformed* row never reaches the scan. Since 2026-10-03 (never store values) its rejects
    sidecar keeps only line, columns, reason and a salted fingerprint
    (`aCardNumberInAMalformedRowIsNeverCopiedIntoTheRejectSidecar`, a byte-wise scan of the whole Space).
  - The raw SOURCE file itself is kept (`all_or_nothing` quarantines it whole; `backup/` keeps every raw file that
    landed) until the opt-in `processing.raw_copy_retention_days` (>= 1; unset = kept forever) ages it out: after each
    backup, `RawCopyRetention` deletes files older than the window under `backup/` and `quarantine/` - never
    `backup/parked/` (awaits `drain`) nor the `.restricted` store (its own `refusal_retention_days`). The window is
    also how long a reject stays replayable. A file NAME can embed a value and is recorded at discovery, before the name check:
    `FileNames.safe` (inspecto-etl) replaces every non-timestamp run of 8+ digits with its salted
    fingerprint (`FailureText.fingerprint`, same per-Space salt). It is applied to the acquisition ledger's key AND
    name (`CollectorProcessor` lookup and `ConsignmentIngestor` record use the same transform, so dedup identity is
    unchanged), the `[DEDUP]` log line, and the remote-acquisition logs and `AcquisitionTelemetry` events. A name
    with no such run is returned unchanged, so an existing ledger still matches; losing the salt re-keys the
    value-bearing names once. Pinned by `FileNamesTest` and
    `SourceConfigIntegrationTest.aValueInAnInboxNameIsNeverLedgeredButDedupIdentityHolds`.
  - **Every other name that is only DISPLAYED or logged carries it too** (closed 2026-10-04): `CommitRetry`,
    `UnpackStage`, `NativeCsvStreamingEngine`, the lane failure logs and ingest-progress names,
    `QuarantineManager`, `DuckDbCsvIngester`, the status ledger's `filename` / `origin` / `logical_name` columns
    (`ConsignmentIngestorTest.aValueInAFileNameIsNeverWrittenToTheStatusLedgerButTheRealFileStillLands`) and the
    unpack ledger. `FailureText.scrub` (every stored or logged failure text) also runs `FileNames.safeText`, which
    transforms a file-name-shaped token (long digit run plus an extension) in an exception message that quotes a
    path - a plain count such as `12345678 bytes` is left alone. `safe` is idempotent: it skips an `<fp:...>`
    span, because a 16-hex fingerprint can itself hold 8 decimal digits in a row.
  - **Heuristic widened to dashes and spaces** (`4111-1111-1111-1111`, `91 98765 43210`: short groups, compacted
    before fingerprinting so the dashed and the plain spelling agree). A chain that starts with a valid date
    (`2026-10-03-1`) is a stamp, so a dated name keeps its ledger key. Measured over the 5428 tracked file names
    of the repo: one extra hit (a font range), none among inbox-shaped names.
  - **The OUTPUT file name is value-free too** (closed 2026-10-04, `INGEST-OUTPUT-NAME-EMBEDS-SOURCE-STEM-1`):
    `FileNames.outputStem` renders the source stem through `safe`, spelling each `<fp:X>` span `fp-X` (`<`, `>`
    and `:` are not legal in a file name). It is stable per name and Space, so an `OVERWRITE_OR_IGNORE` re-run lands
    on the same file; a name with no value run is unchanged. The status row's `output_paths` is value-free with it
    (`ConsignmentIngestorTest.aValueInAFileNameIsNeverWrittenToTheStatusLedgerButTheRealFileStillLands`). ⚠ Losing
    the salt renames those outputs once.
  - **So are the reject sidecar, the chunk scratch name and the replay input name** (2026-10-04):
    `FileNames.errorsFileName` (`<outputStem>_errors.csv`) is the ONE spelling the writers (`ParserSpec`,
    `QuarantineManager`) and the readers (`GET .../rejected-rows` in `RunRoutes`, `RecordReplay`) agree on, so
    lookups still resolve from the REAL file name the caller passes; `FileChunker` chunk files and
    `RecordReplay.replayName` use `outputStem`. `outputStem` is idempotent (an `fp-<16 hex>` span is never
    re-rendered), so a chunk's own sidecar is value-free too. ⚠ Breaking, on purpose: a value-bearing name's
    existing `_errors.csv` is no longer found under its old name; a name with no value run is unchanged. Replay
    still re-reads the SOURCE file by its real name.
  - **Residual, by design: names that RESOLVE a path stay real.** The per-Consignment manifest, `backup/` and
    quarantine paths, markers, the lineage ledger (queried by input file) and the `filename_column` data column
    hold the real
    name; they are the raw-copy surface the retention window above governs. **Not caught, by choice:** a value
    split by underscores or dots (those join ordinals and versions), and a value spelled in letters.
- **Feature Datasets (WS-41).** Five `sql.template` Jobs run on an hourly cron. Thresholds are Job parameters. Every
  window is **rolling**, anchored on each attempt, and half-open: `(t − window, t]`, so a span of exactly the window
  is outside it.
  - `pf_device_small_amounts`: distinct instruments ≤ `$small_amount` (2.00) per device, over `$window_hours` (24).
  - `pf_bin_declines`: distinct declined instruments per BIN, over 24 h.
  - `pf_instrument_velocity`: attempts per instrument over `$window_minutes` (60). Partition and grouping are both
    the instrument.
  - `pf_sim_swap_payments`: approved payments ≥ `$risky_amount` (200) within 24 h after a SIM change, on a device the
    account never used before that change.
  - `pf_account_activity`: attempts, declines, disputes and the account's own 60-minute velocity. Partition and
    grouping are both the account.

  Each sink ships a **zero-row `schema-seed.parquet`** under `data/<sink>/`, which the first run's swap-in replaces.
  🔴 Without it the seed gate REFUSES the template: an Alert Rule's `by` check and the Risk Score's factor-column
  check both read the Dataset's Schema, and a Job-produced Dataset has none until it runs. The golden test asserts
  that each seed's `DESCRIBE` equals the Job output's.
- **KPIs and Dashboard (WS-44, slice 2, 2026-10-04).** The `pf_daily_summary` Job (`sql.template`, no parameters)
  writes one row per day (`activity_date`, attempts, declined attempts, attempted amount, disputes, disputed amount),
  with its own zero-row seed. Four KPIs read it (`disputed_amount`, `disputes_opened`, `attempted_amount`,
  `declined_attempts`) as KPI tiles beside four bar Widgets (one per typology Dataset) on `payment_fraud_overview`.
  KPIs sit on the Job sink, not on the raw feeds: the seed gate reads each KPI's Dataset when the Space is created,
  and a raw feed has no data yet. `PaymentFraudDashboardTest` (inspecto) creates the Space through `POST /spaces`,
  ingests, runs the Jobs and renders every tile through `/kpis/{id}/value` and `/bi/query`. Runbooks (2026-10-04): `config/runbooks/payment-fraud-runbooks.md` (card-number tripwire, the four typologies, the Risk Score, labels and maturity), delivered to the created Space and pinned by `ControlApiSpaceTemplateSeedGateTest`.
- **Dispute labels with a maturity flag (WS-44, slice 3, 2026-10-04; operator decision).** `pf_attempt_labels` (Job
  parameter `maturity_days`, **default 120 = the chargeback window, configurable; the operator may change it**)
  writes one row per attempt: `disputed`, `mature`, and `label` = `DISPUTED` (any age), `NEGATIVE` (undisputed and
  older than the window) or `UNMATURED` (undisputed, still inside it - not yet a trustworthy negative). Age is
  measured against the newest attempt date in the data, so a replayed corpus is deterministic. It is a Dataset, not
  an Alert Rule (G-42). `PaymentFraudDashboardTest` pins the default (no negatives in a 3-day corpus) and
  `maturity_days=1` (negatives appear, disputed stay positive).
- **Typologies (WS-42).** There are four per-entity CRITICAL Alert Rules. `AlertRule` accepts only
  `WARNING · INFO · CRITICAL`, so the Risk Score concept's "`CRITICAL` or `ERROR`" does not apply here.
  - `pf_card_testing`: `by device_id`, ≥ 8.
  - `pf_bin_attack`: `by bin`, ≥ 15.
  - `pf_velocity_burst`: `by instrument_token`, ≥ 6.
  - `pf_sim_swap_takeover`: `by account_id`, ≥ 1.
- **Risk Score (WS-43).** `registry/risk-scores/payment_account.toon` scores entity `account`, with
  `highThreshold` 60. Its **default** factor table is SIM-swap payments ×60 (cap 60), account velocity ×5 (cap 40),
  declines ×4 (cap 20) and disputes ×20 (cap 40). It runs as the `pf_risk_score` `risk.score` Job.
  - 🔴 **Its Alert Rule cannot ship in the template.** A rule over `risk_scores_payment_account_latest` needs that
    store's Schema at apply time, and seeding the store would mean forging the `.risk-score-output` ownership
    marker. Add the rule after the first scoring run, as [Risk Scores](../scoring/spec-risk-scores.md)
    documents.
- **Golden test.** `PaymentFraudTemplateGoldenTest` (inspecto-engine, 11 tests) runs a verbatim copy of the template
  end to end: the ingest, the five Jobs through `JobService`, the model through `RiskScoreEvaluator`, and every Alert
  Rule through `AlertService` + `DatasetMeasureProbe`.
  - **Exact detections:**
    - card testing: {`dev_ct_01`, `dev_ct_02` (4 + 4 across midnight)};
    - BIN attack: {`498765`, `498700` (8 + 8 across midnight)};
    - velocity: {`tok_vb_01`, `tok_vb_02`, `tok_vb_03` (6 attempts spanning 59:59), `tok_shared` (one instrument, two
      accounts)};
    - SIM swap: {`acc_ss01`, `acc_ss02`};
    - high risk: {`acc_ss01` 65, `acc_ss02` 65, `acc_vb01` 87, `acc_ct_guest` 60}.
  - **Look-alikes that stay silent:**
    - a 7-instrument device, a kiosk at normal amounts, and a payroll-card BIN run;
    - 5 attempts in 48 minutes, 6 attempts spanning **exactly 60:00**, and 6 over 61 minutes;
    - SIM swaps at 50.5 h, on the usual device, below 200, and declined;
    - the shared instrument's two accounts, which score 15 each (their own 3 attempts, not the instrument's 6).
  - **Mutants.** Each of these turns the test red:
    - no digit folding;
    - no separator run;
    - `AMOUNT` left out of the scan;
    - separators `{1,5}` → `{1,1}` (the 2- and 3-separator probes land);
    - no 4-4-4-4 layout (the CVV and expiry probes land);
    - no IIN requirement (3 realistic corpus references trip);
    - no mark / zero-width stripping;
    - a `find()` instead of an anchored reason code (`RefusalQuarantineTest`);
    - no per-member probe in the union lane;
    - no chunk rollback;
    - the original name kept in `logical_name`;
    - the restricted directory derived as a SIBLING of `dirs.quarantine`;
    - dropping the 6, JCB 35 or Diners 30/36/38/39 ranges, or narrowing 50–59 to 51–55;
    - the IBAN skip put back (the two dressed numbers land);
    - `.restricted` readable through the table browser, `/db/query` or a Dataset over its ancestor;
    - a replay reading a sidecar under `.restricted`;
    - an exempt list covering every raw field accepted;
    - the scan failing open;
    - an ignored exempt list;
    - no retention audit;
    - `.restricted` not excluded from backup, from the quarantine listing or from the errors-file route;
    - the store back under `dirs.quarantine`, where a mapping expression could read it (round 5);
    - an import door not auditing a dropped `refusal_scan` (round 5);
    - the `scanFile` fail-closed branch returning null (round 5);
    - `>=` on the velocity bound;
    - calendar-day grouping for card testing and BIN attack;
    - an instrument partition for the account velocity;
    - earlier, a card-testing threshold of 7, and a SIM window of 72 h.
  - `ControlApiSpaceTemplateSeedGateTest.everyShippedTemplateApplies` applies the template through the seed gate.
- **Deferred to later slices** (open on the row): WS-44 (disputes as labels with a maturity flag, payment KPIs and
  dashboards), further typologies (operator call). WS-44 shipped as slices 2–3; runbooks shipped 2026-10-04.
 

#### 3.5.2 Deferred seed — an Alert Rule over the template's own Risk Score output (`TEMPLATE-RISK-SCORE-ALERT-RULE-1`, 2026-10-06)

**Decision (operator, 2026-10-06): DEFERRED SEED.** A per-entity Alert Rule's `by` columns are checked against its
Dataset's Schema at save, and a Risk Score's `risk_scores_<model>_latest` store has no Schema — and may not even be
named by a Dataset (`RiskScoreRoutes.requireStorable`) — until the model first runs. Seeding a Schema or the store
would forge the ownership marker, so a template declares the rule as PENDING instead, and nothing is forced.

- **Where it is recorded (in the Space).** `config/pending/alert-rules/<name>.toon`: the Alert Rule body plus
  `afterScore: {kind, model}` — `kind` is `risk-score` or `anomaly-model` (ANOMALY-DETECTION-1 D-AD7, operator
  2026-10-10: one trigger key for every score kind; the old flat `afterRiskScore: <model>` is refused). It is copied
  with the template like any config file. At apply the seed gate checks it (`TemplateSeedGate`, step after `kpi`): the
  edition carries Alert Rules, it parses (`AlertRule.fromMap`), it names a model of that kind the template seeds, and
  its `dataset` is that model's `_latest` store (`risk_scores_<model>_latest` / `anomaly_scores_<model>_latest`). The capability
  table classifies the path as `alert-rule` (`ImportCapabilityGuard`, `canAuthorAlertRules`) and so does the
  approval-policy classifier (`PendingChanges.kindOfConfigPath`).
- **What fires it.** The `risk.score` / `anomaly.score` Job emits `risk.score.produced` / `anomaly.score.produced`
  (both core `SignalType` constants) after it has written the scores; each Space's `CollectorService` subscribes to its
  own event log and calls `PendingAlertRules.onScoreProduced(kind, model)` (renamed from `onRiskScoreProduced`, operator 2026-10-10). For each pending rule naming that kind and model it registers the `_latest` Dataset (id = physicalRef) through the Dataset save
  gate if absent, then runs `AlertRoutes.parse` (the `by` Schema check included), writes `registry/alert-rules/<name>`,
  arms it in the `AlertService` and deletes the pending file. **Once:** the pending file is the only trigger, and the
  call is synchronized; an Alert Rule already under that name is never overwritten (the pending entry is dropped).
- **A refusal stays pending.** Any gate refusal — no output yet, a Schema mismatch, an edition without Alert Rules, or
  a Space approval policy that holds `alert-rule` or `dataset` changes (a writer outside any request cannot be
  approved) — keeps the file, emits an AUDIT event `alert-rule.pending.refused` with the reason, and is retried on the
  model's next run. `alert-rule.pending.created` / `.dropped` record the other outcomes (actor `system`).
- **Security inventories (operator, 2026-10-06).** `PendingAlertRules` is a *background template materializer*: it
  refuses while an approval policy governs `alert-rule`/`dataset`. `PendingAlertRules#ensureLatestDataset` is on
  `ConfigWriteFunnelTest.WRITERS` with that reason (`#onScoreProduced` reaches `PendingChanges.governs` itself, so
  the scan counts it as held). Both sites are on `DecisionRuleWritersTest.GUARDED_BY`: before any write
  `#onScoreProduced` runs `DecisionRuleGuard.refuseUnattended` — a background writer has no Subject to hold
  `canWorkIncidents`, so a rule body with an `invoke-api` consequence is refused (422, fail closed) and stays pending
  + audited like every other refusal.
- **Status.** `GET /alerts/rules/pending` lists `{name, afterScore: {kind, model}, dataset}` for what is still waiting, plus
  `lastRefusal: {reason, at}` once a run refused it (`PENDING-REFUSAL-REASON`, operator 2026-10-06). The latest refusal
  is kept beside the pending file in `<name>.refusal` (temp file + atomic move; the rule body is never touched, and the
  `.refusal` extension is never listed as a rule) and deleted with it on creation or drop; the AUDIT event is still
  emitted. The stored reason has the Space's absolute location replaced (`PendingAlertRules#scrub`) and an I/O failure
  is stored only as `write failed`, so no host path is served; gate reasons name rule/Dataset/column names, never row
  values. The Alerts page shows them read-only under
  *Pending Alert Rules* (name, the Risk Score it waits on, Dataset, an unreadable file's `error`, and *Why still pending*
  — the latest refusal's reason and time, replacing the "see the audit log" hint once any entry has one), with an empty state, an
  error state with Retry, and the section hidden when the route answers 404/503; the rule moves to the Alert Rules table
  once created.
- **Use.** `payment-fraud` ships `pf_high_risk_account` (`max(score) >= 60` by `model, entity_key`, CRITICAL) after
  `payment_account`. Tests: `PendingAlertRulesTest` (pending, refused + audited, created once, never overwritten, held
  under an approval policy, a `DecisionRuleGuard` refusal stays pending, `lastRefusal` absent before a run / served after a refusal / gone after success, path scrub), `ControlApiSpaceTemplateSeedGateTest` (capability and content refusal at apply) and
  `PaymentFraudDashboardTest` (the live Space's own `pf_risk_score` run creates and arms it).

#### 3.5.3 The `mobile-money` industry pack (`PACK-MOBILE-MONEY-1`, 2026-10-09)

Configuration only (no platform edit, no new Step Processor). Five synthetic feeds, each a Pipeline + Schema:
`wallet_txn` (the wallet-platform ledger: `CASH_IN`, `CASH_OUT`, `P2P`, `BILL_PAY`, `BANK_OUT`, `BANK_IN`, with
`fee` and agent `commission`), `bank_statement` (the trust-account statement, `bank_ref` = the platform's
`txn_id`), `partner_settlement` (biller / aggregator files, `txn_ref`), and the daily snapshots `wallets` (KYC tier,
status, `last_activity_date`) and `core_subscribers`. Money is `DECIMAL(18,2)`. Needs Standard or above; declared by
Offering placement (`offerings/standard.toon` `contentPacks`) and by the template key `minEdition: standard`, which
`tools/check-offerings.mjs` holds against every Offering that lists the pack.

- **Two Reconciliations** (`recon.run`, 04:00): `mm_bank_float` (ledger `BANK_*` rows vs the statement) and
  `mm_partner_settlement` (ledger `BILL_PAY` rows vs settlement lines). Both select their ledger side with the
  Recon `filters` key and map the counterparty's reference with `columnMap`, so no extra feed is needed;
  `cardinality: one_to_many` + `includeRecordCount: false` let one transfer settle in several lines (a duplicate
  ledger id IS a Break); amount tolerance `0.01` absolute.
- **Seven `sql.template` typologies** (03:00 over `$yesterday`..`$today`, the telco-fraud keep-earlier-windows
  shape, zero-row seed snapshot per sink), each with a per-entity Alert Rule keyed on the offender alone:
  `mm_commission` (agent; `|paid - amount x rate|` per type, `gt 5`, `direction` OVERPAID / UNDERPAID),
  `mm_fee` (wallet; overcharge vs `greatest(fee_min, amount x fee_rate)`, `gt 1`; undercharge is reported, never
  alerted), `mm_agent_split` (agent; most cash-ins in `[limit x (1 - band), limit)` into one wallet, `gt 3`),
  `mm_round_trip` (agent; wallets cashing out >= `round_trip_ratio` at the SAME agent within
  `round_trip_minutes`, `gt 2`), `mm_dormant` (wallet; outflow after `dormant_days` idle, `gt 500`),
  `mm_kyc_limit` (wallet; outflow over the tier limit, a wallet missing from the register held to tier 1 — fail
  closed; `max(breach) gte 1` because a threshold must be positive) and `mm_provisioning` (wallet; ACTIVE wallet on
  a line missing / non-ACTIVE in core, numbers compared digits-only; `gte 1`). Provisioning is not a
  Reconciliation: Recon compares aggregated numerics, not a status vocabulary.
- **Agent Risk Score** `mm_agent` (Scoring & Lists): split x10 (cap 50) + round-trip wallets x15 (cap 60) +
  commission variance x1 (cap 30), high at 60; `mm_high_risk_agent` ships PENDING (§3.5.2). Two signals each AT
  their own rule's threshold (silent there) score 60 together — the reason the score exists.
- **KPIs / dashboard / runbook.** Four KPI tiles (commission variance, fees overcharged, dormant outflow, outflow over
  KYC limits) + seven typology bars on `mobile_money_overview`; `config/runbooks/mobile-money-runbooks.md`.
- **Verification.** `MobileMoneyPackGoldenTest` (inspecto-reconciliation: `POST /spaces`, production-path ingest
  of all five feeds, both Reconciliations' exact Breaks incl. a re-run, exact offenders for 26 Alerts with zero
  look-alikes, same-window re-run duplicates nothing, day 2 keeps day 1, seed snapshots pinned, every dashboard tile
  renders) and `MobileMoneyRiskScoreGoldenTest` (inspecto-scoring: exact high-risk set A701/A702/A720, the pending
  rule's shape) — two modules because none carries both add-ons. Corpus: `MobileMoneyCorpus`, seed 20261009,
  regenerate with `-Dmobilemoney.regenerate=true`. Mutation-checked: lowering `mm_round_trip` to 1 fails on the
  at-threshold look-alikes. `ControlApiSpaceTemplateSeedGateTest` applies it with the other shipped templates.
- **Deliberately deferred.** An agent e-float statement reconciliation (no standard agent-side feed; needs a
  customer's), tiered / campaign commission and fee tables (flat rates are configuration today — the runbook warns),
  vendor feed mappings.

#### 3.5.4 The `aml` function pack (`PACK-AML-1`, 2026-10-10)

Configuration only (no platform or add-on source edit; the one build change is two TEST-scope dependencies in
`inspecto-scoring`'s pom so its golden test can run Screening and load a Report Template). Needs Standard or
Advanced; listed in `offerings/standard.toon` `contentPacks`. Two synthetic feeds: `aml_txn` (account ledger:
`CASH_DEPOSIT`, `CASH_WITHDRAWAL`, `TRANSFER`, `WIRE`; `account_id` pays `counterparty_id`; `DECIMAL(18,2)`) and
`aml_parties` (party-register snapshot). Country codes XA-XF are placeholders.

- **Four `sql.template` typologies** (03:00, keep-earlier-windows shape, zero-row seed per sink, a `lookback_days`
  of 6 for the three that read a week): `aml_structuring` (cash deposits in `[limit x (1 - band), limit)`, alert `gt 4`),
  `aml_threshold` (a day's cash total, single or split, `gt 10000`), `aml_fan_in` (distinct payers of transfers at most
  1,000 into one account, the smurfing shape, `gt 9`) and `aml_high_risk_traffic` (WIRE rows to the Job's
  `high_risk_countries` list, `gt 5000`). Every Alert Rule is per account and CRITICAL (a WARNING rule opened no Incident
  in the golden sweep).
- **Risk Score `aml_account`** (structuring x10 cap 50, smurfing payers x4 cap 40, high-risk wires x10 cap 30, high at 60) +
  PENDING `aml_high_risk_account`. Two accounts each have two signals AT their own rules' thresholds (silent) that together
  score 60 / 66.
- **Screening**: the shipped `aml_screening` Job (`screening.run` over `aml_parties`, lists `aml_sanctions,aml_pep`) ships
  `enabled: false`, because Entity Lists are facts in the Space's own log and cannot ship in a template.
- **Link Analysis**: the `aml_account_transfers` view and four money pattern packs (layering chain, pass-through, inbound
  collector, circular flow). **Reporting**: `config/regulatory-report-templates/aml-str.toon`, an ILLUSTRATIVE STR shape.
- **Verification.** `AmlPackGoldenTest` + `AmlScreeningGoldenTest` in `inspecto-scoring` (one module carries Scoring,
  Screening and Regulatory Reporting): corpus pinned to `AmlCorpus` (seed 20261010, `-Daml.regenerate=true`), exact offenders
  per typology with look-alikes AT the threshold, outside the look-back and of another kind, exact Risk Score outcomes, exact
  Screening Hits (reordered name, exact, identifier-only, PEP; two look-alikes silent; a re-run raises nothing). Mutation-checked:
  lowering the `aml_structuring` rule's `threshold` to 3 fails on the at-threshold look-alike `AC100003`.
- **Deliberately cut (a template cannot carry them).** Investigation Templates and the `structuring` / `valueWeightedLinks` value-Measure
  Alert Rule need a live Investigation (runtime state, no registry kind) - the runbook says how to bind one; Entity List seeding;
  a real regulator's report schema; counterparty-name screening of every transaction (the register is screened instead).
