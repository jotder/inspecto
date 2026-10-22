# 5G / IoT / cloud / digital service assurance: a plan for a file-fed platform

> ⏸ **PARKED 2026-10-22 — design only, demand-gated; not in flight.** To resume: move it back to docs/superpower/ and rank its BACKLOG row.
>
> *Status before parking:* 🟡 **PLAN 2026-10-18 (`PACK-5G-IOT-ASSURANCE-1`): design only, nothing built.** Competitive gap **U** (5G, IoT, cloud and
> digital service assurance) is **N** for Inspecto in [`COMPETITIVE_CAPABILITY_MATRIX.md`](../../layer/product/brochure-competitive-matrix.md):
> LATRO, Mobileum and TEOCO sell it (TEOCO's HELIX is 5G service assurance), Subex and Neural are partial or adjacent. The
> backlog row says "not designed; build when a prospect names it". This plan designs it **for the platform Inspecto is**:
> a file- and event-feed platform with **no probes and no inline path**. It is deliberately narrow: it assures what
> **usage and session records** can prove (was it metered, entitled, billed, and did it behave like it should), and it says
> plainly what needs network counters or a real-time path and is out. Decisions **D-5G1…D-5G10 are open** (§10). Every claim
> about volume is a hypothesis until slice **U0** measures it.
>
> **House style.** Mirrors [`rerating-tariff-validation-plan.md`](../../../../archived-documents/plans-archive/rerating-tariff-validation-plan.md) and
> [`partner-settlement-wholesale-plan.md`](../../../../archived-documents/plans-archive/partner-settlement-wholesale-plan.md); this plan **reuses their `rerated_usage`
> contract** instead of re-deriving a rated charge (§4, §6.2).
>
> **Method.** Read on 2026-10-18: matrix rows U, F, X, A, P; the `PACK-5G-IOT-ASSURANCE-1` row; `docs/GLOSSARY.md`; the
> `telco-ra`, `telco-fraud` and `business-assurance` Space Templates (Pipelines, Schemas, Jobs, KPIs, Alert Rules, the
> `anomaly-models/tf_subscriber_usage.toon` model); `okf/product/module/engine/spec-parser-plugins.md`; `okf/product/module/anomaly/spec-anomaly-scores.md`;
> the two earlier plans; and the git-excluded MVNO RFP working set (`docs/superpower/rfp-mvno-assurance/`: the
> `compliance-matrix.xlsx` Compliance Matrix sheet, `gap-register.md`, `build-plan.md`, `assurance-content-catalog.xlsx`).
> Where this plan says "exists", a file was read; where it says "not run", it was read, not executed.

## 1. What exists today, and the gap

### 1.1 What the platform already gives this problem

| Need | What exists (read 2026-10-18) | Fit |
|---|---|---|
| Event-style feeds | A **Collector** per feed lands files in an inbox; a **Pipeline** parses to a **Dataset**; `telco-ra` runs eight such feeds (`switch_xdr`, `mediated_xdr`, `rated_usage`, `billed_invoice`, ...) on `glob:**/*.csv` with a duplicate-file marker. | Direct. A CHF, IoT-platform or cloud export is one more feed of the same shape. |
| Parsers | Six built-in Parser plugins (delimited, fixed-width, JSON, text-regex, XLSX, Parquet) plus the XML bridge (grammar under `ingester_config`) and `asn1` from the optional `telecom-asn1` module (ASN.1 BER with a stored grammar; `derive` / `children` for repeated records). | Covers every format in §3 **except** possibly binary IPDR; no new Parser expected for v1. |
| Usage versus billing | `telco-ra`: a 3-way completeness **Reconciliation**, `ra_rated_vs_billed`, `ra_rerating` (one `sql.template` Job), a roll-forward and an interconnect settlement; leakage and data-quality findings in separate Datasets; two Alert Rules `by: CONTROL`. | Direct, and the model to copy. |
| Behavioural baselines | **Anomaly Detection** (`features/inspecto-anomaly`): an `anomaly-model` component, Features as Measures per entity per day, a self and **Peer Group** baseline, explainable 0-100 **Anomaly Score** per entity. `telco-fraud` ships `tf_subscriber_usage` over voice, SMS and data seconds. | Direct for IoT and tenant usage. No trained model (`ANOMALY-ML-SCORING-1` is separate). |
| Measures, KPIs, Alerts | KPI definitions `{dataset, measure, timeField, grain, comparison, direction}`; Measures are `{agg, field}` pairs, so arithmetic lives in the Job; Alert Rules `by` a column; Incidents. | Direct. |
| Reference data | A **Reference** (`load: replace / upsert / scd2`) is the slow-changing counterpart of a **Stream**. | Direct for entitlements, price lists, SLA targets. |

### 1.2 The gap

Nothing in the repository knows a 5G session, a slice, an eUICC lifecycle event, an IPDR record, a cloud usage line or a
metered API event. No template, Schema, control or KPI mentions them (searched: no hit for `IPDR`, `DOCSIS`, `eUICC`
or `S-NSSAI` in the Space Templates or the engine). The platform's **mechanisms** all fit; the **content** (Schemas,
References, controls, KPIs, a golden corpus, runbooks) is absent. That is why this is a content pack, not an engine plan
(§7), and why it is demand-gated (§9).

## 2. Scope boundary

The test is: **can the finding be proved from a file of records, later than the event, without touching the network?**

| In scope (records, offline, minutes to hours late) | Out of scope, and why |
|---|---|
| 5G core charging and session records exported as files: **CHF** converged-charging records, **SMF** and **UPF** usage records, per session with data volume, QoS flow, slice and rating group | **Network probes and counter-based assurance**: RAN and core performance counters, latency or packet-loss measurement on the wire, topology (matrix rows F and X; TEOCO HELIX territory). The platform has no probe and the matrix marks X "different buyer". |
| IoT connectivity platform usage exports and **SIM / eUICC lifecycle** events (order, activate, suspend, profile switch, deactivate) | **Real-time enforcement**: block, throttle or redirect a session, live quota or policy decisions, slice admission (RFP `RT-12`...`RT-15`, `RT-19`, `RT-20`; gap `G-01`, owned by `REALTIME-DECISIONING-1` and the RTSC partner). This plan detects after the fact. |
| **IPDR / cable** usage records (modem, subscriber, volume, interval) as XML or CSV files | **Signalling and protocol stacks** (SS7, Diameter, SIP, NGAP; row F). |
| **Cloud** usage and billing exports (line items, commitments, tags) as CSV, JSON or Parquet drops | **Cloud operations monitoring**: traces, logs for uptime, autoscaling, security posture, a recommendation engine for rightsizing (FinOps advice). v1 finds leakage and drift, it does not optimise. |
| **Digital-service** metering: API and application event logs, billable-unit counts, per-tenant SLA and KPI series | **Test traffic generation and synthetic transactions** (row G). |
| Controls over the above: reconciliation, entitlement, anomaly, leakage, drift | **Trained ML scoring** (`ANOMALY-ML-SCORING-1`, `G-21`); **cybersecurity** of IoT devices (row Y). |
| | **Direct API pull** from a cloud or IoT platform: v1 takes **file drops**; an API Collector is decision D-5G5. |

⚠ **The word "real-time" is a trap in this domain.** 5G charging (Nchf) is real-time **in the network**; the records it
leaves are batch files. This plan consumes the records. A prospect who means "tell me before the quota is exceeded" wants
`REALTIME-DECISIONING-1`, not this pack.

## 3. Feeds, Parsers and canonical Schemas

Canonical, vendor-neutral Schemas (as `telco-ra` does); a real operator's field mapping is an onboarding task and is
**parked** exactly as the re-rating plan parks it. Identifiers in every fixture are obviously synthetic (test PLMN
`001-01`, ids prefixed `SYN-`, addresses in `192.0.2.0/24`). Classification: `SUBSCRIBER_ID`, `ICCID`, `IMSI`, `MSISDN`,
`EID` are **PII**; `CUSTOMER_ID`, `TENANT_ID` are **INTERNAL** (D-5G9).

| Feed (Stream) | Typical delivery | Parser | New or reused | Key columns (canonical) |
|---|---|---|---|---|
| `data_session_xdr` (5G core: CHF / SMF / UPF) | CSV or JSON export; BER file per 3GPP TS 32.298 | `delimited`, `json`; `asn1` via a stored grammar and `derive` | **Reuse**; ASN.1 grammar for the charging record is slice U2b, **demand-gated** (the repo holds 3GPP TS 32.298 only as a spec-reference grammar, not a deployable one) | `SESSION_ID, RECORD_SEQ, RECORD_TYPE (open / interim / close), SUBSCRIBER_ID, DNN, SNSSAI_SST, SNSSAI_SD, QOS_5QI, RATING_GROUP, START_TS, DURATION_S, UL_BYTES, DL_BYTES, ROAMING_FLAG, VISITED_PLMN, CLOSE_CAUSE` |
| `iot_usage_daily` (IoT connectivity platform) | CSV per day | `delimited` | New Schema | `ICCID, CUSTOMER_ID, PLAN_ID, APN, USAGE_DATE, BYTES_TOTAL, SESSION_COUNT, SMS_COUNT, ROAMING_BYTES, VISITED_PLMN` |
| `sim_lifecycle_event` | CSV or JSON events | `delimited`, `json` | New Schema | `EVENT_ID, ICCID, EID, EVENT_TYPE, EVENT_TS, PLAN_ID, CUSTOMER_ID, PROFILE_ID` |
| `ipdr_record` (cable / IPDR) | XML (IPDR/SA-style) or CSV | XML bridge; `delimited` | New Schema; **binary IPDR file format is out of v1** (D-5G6) | `RECORD_ID, MODEM_ID, SUBSCRIBER_ID, SERVICE_FLOW, INTERVAL_START, INTERVAL_END, UP_OCTETS, DOWN_OCTETS, CMTS_ID` |
| `cloud_usage_line` | CSV, JSON or Parquet drop | `delimited`, `json`, `parquet` | New Schema | `LINE_ID, ACCOUNT_ID, SERVICE, RESOURCE_ID, USAGE_DATE, USAGE_QTY, USAGE_UNIT, LIST_PRICE, BILLED_COST, COMMITMENT_ID, TAG_OWNER, TAG_PROJECT` |
| `digital_service_event` (API / app metering) | NDJSON or CSV | `json`, `delimited` | New Schema | `EVENT_ID, TENANT_ID, SERVICE, ENDPOINT, EVENT_TS, STATUS_CLASS, LATENCY_MS, BILLABLE_UNITS` |
| `digital_service_invoice` | CSV | `delimited` | New Schema | `TENANT_ID, SERVICE, PERIOD, BILLED_UNITS, BILLED_AMOUNT` |
| `data_billed_invoice` | reuse `billed_invoice` | | **Reuse** from `telco-ra` (copied, as the re-rating plan copies) | `SUBSCRIBER_ID, MONTH, AMOUNT` |

References (Datasets of the slow-changing kind): `iot_entitlement` (`CUSTOMER_ID, PLAN_ID, POOL_BYTES, PER_SIM_BYTES, ALLOWED_PLMNS, MAX_SESSION_HOURS, DORMANT_DAYS, EFFECTIVE_FROM / TO`),
`slice_entitlement` (`CUSTOMER_ID, SNSSAI_SST, SNSSAI_SD, ALLOWED_5QI, COMMITTED_BYTES_PER_DAY, CAP_BYTES_PER_MONTH, SLA_BAND`),
`cloud_commitment` and `cloud_price_list` (`COMMITMENT_ID, SERVICE, COMMITTED_QTY, UNIT_PRICE, EFFECTIVE_FROM / TO`),
`service_sla_target` (`SERVICE, KPI, TARGET, WARN_BAND, PERIOD`), `plmn_policy` (roaming steering: `PLMN, CLASS`). Entitlements are
**effective-dated** (half-open `FROM <= ts < TO`, host time zone is **not** UTC in DuckDB: the zone is a parameter, tested under two zones, as in the re-rating plan §6.1).

## 4. Assurance controls per family

Plan-local control ids `DA-C01...` (not RFP ids; the RFP catalogue's `RA-Cxx` / `FM-Rxx` are mapped in §8). Every finding row
carries `CONTROL, EVENT_DATE, ITEM_KEY, REASON, FINDING, LEAKAGE_AMOUNT`, the column list `ra_leakage` already unions, so the
existing leakage Dataset and its Alert Rule pattern read the new findings unchanged. A control that cannot judge a record
writes **data quality**, never leakage (the `telco-ra` rule of thumb).

| Id | Control | Family | Engine | Slice |
|---|---|---|---|---|
| `DA-C01` | **Data usage versus billing**: session bytes per subscriber and month (5G) against billed data amount; the independently re-rated amount is read from `rerated_usage` when D-5G7 adopts it | A usage-vs-billing | `recon.run` + `sql.template` | U3 |
| `DA-C02` | **Record completeness and chain integrity**: missing `RECORD_SEQ` in an open / interim / close chain, duplicate record, session with no close within `MAX_SESSION_HOURS`, late-arriving record after the bill run | A | `sql.template`, `transform.dedup` Step | U2 |
| `DA-C03` | **Pooled IoT usage versus invoice**: sum of `iot_usage_daily` per customer and plan against the invoiced pool and overage | A | `recon.run` | U1 |
| `DA-C04` | **Usage on a slice the customer is not entitled to** (`SNSSAI` absent from `slice_entitlement` for that `CUSTOMER_ID` on that date) | B slice / QoS vs entitlement | `sql.template` | U2 |
| `DA-C05` | **QoS tier mismatch**: `QOS_5QI` used is not in `ALLOWED_5QI` (premium QoS delivered, standard billed) | B | `sql.template` | U2 |
| `DA-C06` | **Committed-volume breach and unbilled overage**: usage above `CAP_BYTES_PER_MONTH` with no overage charge in the invoice; committed volume **under-delivered** (SLA credit candidate) | B | `sql.template` + `recon.run` | U3 |
| `DA-C07` | **Zero-usage active SIM**: billed and `ACTIVE` in lifecycle, no usage for `DORMANT_DAYS` (revenue is recurring, value is zero; the customer-exposure side of leakage) | C IoT | `sql.template` | U1 |
| `DA-C08` | **Usage after deactivate** and **active with no bill**: usage on an `ICCID` whose latest lifecycle event is `DEACTIVATED` or never `ACTIVATED`; an activated SIM with no recurring charge | C | `sql.template` (anti-join of lifecycle, usage, billing) | U1 |
| `DA-C09` | **Over-usage against profile**: per-SIM bytes above `PER_SIM_BYTES`, pool exhausted, or a day far above the SIM's own baseline | C | `sql.template` + **Anomaly Model** `iot_sim_usage` (self baseline, Peer Group by `PLAN_ID`) | U1 |
| `DA-C10` | **Stuck session**: one session open longer than `MAX_SESSION_HOURS`, or thousands of short sessions per day (reattach storm) | C | `sql.template` | U1 |
| `DA-C11` | **Roaming steering and permanent roamer**: share of bytes on `VISITED_PLMN` of class `NON_PREFERRED` above a threshold; a SIM roaming for more than N consecutive days; usage in a PLMN outside `ALLOWED_PLMNS` | C | `sql.template` over `plmn_policy` | U1 |
| `DA-C12` | **Profile-switch churn**: eUICC `PROFILE_SWITCH` count per `EID` per window above a threshold (SIM cycling to dodge a lifecycle fee or fraud) | C | `sql.template` | U1 |
| `DA-C13` | **Cloud usage not billed and billed not used**: `cloud_usage_line` usage above what `BILLED_COST` implies at `cloud_price_list`, and billed lines with no usage | D cloud leakage | `sql.template` + `recon.run` | U4 |
| `DA-C14` | **Price drift**: `BILLED_COST / USAGE_QTY` differs from the contract price in force (effective-dated) | D | `sql.template` | U4 |
| `DA-C15` | **Commitment shortfall and unused reservation**: usage below `COMMITTED_QTY` for N periods (money paid for nothing) | D | `sql.template` | U4 |
| `DA-C16` | **Untagged and orphan spend**: cost with empty `TAG_OWNER` / `TAG_PROJECT`, or a resource billed with no usage | D | **Expectation** (per-row non-null) + `sql.template` | U4 |
| `DA-C17` | **Cost spike**: a day's cost per `ACCOUNT_ID` and `SERVICE` far above its own baseline | D | **Anomaly Model** `cloud_spend` | U4 |
| `DA-C18` | **Metered events versus invoice**: `sum(BILLABLE_UNITS)` per tenant and period against `digital_service_invoice` | E digital service | `recon.run` | U5 |
| `DA-C19` | **SLA / KPI drift**: error rate, latency percentile or availability per tenant and service against `service_sla_target`, with warn and breach bands and a period-over-period comparison | E | `sql.template` writing the series, **KPI** bands over it | U5 |
| `DA-C20` | **Metering gap**: an expected hour or tenant with no events (a silent collector, a dropped feed), via `kpi.completeness` | E | `kpi.completeness` Job | U5 |
| `DA-C21` | **IPDR usage versus billing**: per modem and month, `UP_OCTETS + DOWN_OCTETS` against billed usage; modem swap and duplicate interval records collapsed first | F cable | `recon.run` + `sql.template` | U6 |

**The 5G-specific idea worth stating.** Slice and QoS assurance here is **entitlement versus use**, never "did the slice
perform". Performance needs counters (out, §2). Entitlement versus use needs only the charging record and a Reference,
and it is exactly the leakage a slice-selling operator fears: a premium slice used by a customer who bought standard.

**Re-rating is not rebuilt here.** `DA-C01` needs "what should this usage have cost". That is the `rerated_usage` contract
of the re-rating plan (`PACK-RERATING-1`). This plan **consumes** it when it exists and falls back to the rater's own
`CHARGE` (the `ra_rated_vs_billed` comparison) when it does not; it adds no rating logic (D-5G7).

## 5. Measures, KPIs and Alert Rules

Measures stay `{agg, field}` pairs; any arithmetic (a ratio, a leakage amount, a band) is computed in the Job SQL and
the Measure is a `sum` or `count` over that column (the recorded constraint behind `ra_leakage`'s `sum(LEAKAGE_AMOUNT)`).

| KPI | Measure over | Direction | Note |
|---|---|---|---|
| `data_usage_billing_gap` | `sum(GAP_BYTES)` on `da_findings` | down | `DA-C01`, `DA-C03` |
| `slice_entitlement_breaches` | `count` on `da_findings` where control is `DA-C04..06` | down | |
| `iot_zero_usage_sims` | `count` on `da_findings` `DA-C07` | down | idle revenue exposure |
| `iot_sims_flagged` | `count` of distinct `ICCID` flagged | down | |
| `cloud_leakage_amount` | `sum(LEAKAGE_AMOUNT)` on `da_findings` `DA-C13..15` | down | |
| `cloud_unused_commitment` | `sum(UNUSED_COMMIT_AMOUNT)` | down | |
| `service_sla_breach_count` | `count` on `da_sla_series` where band is `BREACH` | down | `DA-C19` |
| `feed_completeness` | `kpi.completeness` per feed | up | `DA-C20`, the same Job type `telco-ra` ships disabled |

**Alert Rules** (the `telco-ra` pattern, `by: CONTROL`, one Alert per control, severity by family): `da_leakage_found` CRITICAL
over leakage controls, `da_entitlement_breach` WARNING over `DA-C04..06`, `da_iot_anomaly` WARNING on the IoT Anomaly
Score band, `da_data_quality` WARNING, `da_sla_breach` CRITICAL on `DA-C19`. ⚠ **Per-customer, per-SIM or per-tenant
alerting is `G-42` / `WS-18`** (one Alert per offender), a separate workstream; until it ships the Alert names the
control and the findings name the entity. This plan does not wait for it and does not build it.

## 6. Reference data, Datasets out and the chain

### 6.1 Datasets out (Job-written), per template

`da_findings` (one row per finding, the unioned contract of §4), `da_data_quality`, `da_iot_sim_day` (ICCID by day with
lifecycle state joined: the shared intermediate for `DA-C07..12`), `da_slice_usage_day` (customer by slice by 5QI by day),
`da_cloud_cost_day`, `da_sla_series`, plus the Reconciliation Break lifecycle Datasets. Zero-row `schema-seed.parquet` seeds
are needed for every Dataset an Alert Rule or KPI reads, because every pack Dataset is empty at apply (the `telco-ra`
pattern). Layering through Job-written Datasets avoids cycles and lineage ambiguity (the recorded design).

### 6.2 The chain

```
feeds ─► Pipelines ─► (da_iot_sim_day | da_slice_usage_day | da_cloud_cost_day)
                      └─► sql.template controls ─► da_findings ─► Alert Rule by CONTROL
                      └─► recon.run controls     ─► Break ─► Incident
```

Chained with `on_signal: job.run.completed` and a `when` guard on `$signal.outcome == SUCCESS`, incremental by `window_date`,
as `telco-ra` and `telco-fraud` do. IoT volume is the hard part: the unit is one row per `ICCID` per day, so the working set is
SIM count times days, not raw session count. **U0 measures it** (D-5G8).

## 7. Packaging, Space Templates and edition placement

- **Recommendation (D-5G2): two Space Templates, not one.** The glossary splits a **function pack** (domain-neutral) from an
  **industry pack** (a vertical's data). Cloud and API metering have nothing to do with telecom; 5G, IoT and IPDR have
  nothing to do with anything else.
  - **`telco-data-assurance`** (industry-flavoured, `kind: solution-pack`, `enforcement: build`): `data_session_xdr`, `iot_usage_daily`,
    `sim_lifecycle_event`, `data_billed_invoice`, controls `DA-C01..12`, `DA-C21`, the IoT Anomaly Model and the cable
    feed once demanded. Sits beside `telco-ra` and `telco-fraud`.
  - **`digital-service-assurance`** (function-pack flavour): `cloud_usage_line`, `digital_service_event`, `digital_service_invoice`,
    controls `DA-C13..20`. Usable by a non-telecom buyer, which widens demand and is its main reason to exist separately.
  - **Cable / IPDR** also fits the glossary's named example of an **industry pack** (telecom mobile, fixed-line, cable / IPDR).
    Whether `DA-C21` stays a control inside `telco-data-assurance` or becomes a declarative industry-pack manifest
    (`META-INF/inspecto/industry-packs/<id>/`, as `telecom` and `mobile-money` are) is decided at U6, D-5G6.
- **Edition: config only.** No new Step Processor (the hold was lifted 2026-10-06, but nothing here needs one), no new Job
  Type, no new module. `minEdition: standard` (Alert Rules and Reconciliations, the `telco-ra` reason), listed in
  `offerings/standard.toon` `contentPacks`; the Preview edition carries everything. ⚠ Only `basic.toon` and `standard.toon`
  list `contentPacks`: verify at U7 how Advanced inherits before claiming it. Use Basic / Standard / Advanced / Preview only.
- **No new root-level `docs/*.md`.** On ship, as-built facts go into the OKF concept for Space Templates
  (`okf/product/module/config/plan-spaces.md`), solution pages under `stakeholders/solutions/`.

## 8. Traceability to the MVNO RFP working set

Read from: `docs/superpower/rfp-mvno-assurance/` (git-excluded, this checkout only; the RFP itself is not in the repository and
no client text is quoted). Compliance-matrix line ids, gap ids and workstream ids are as in `compliance-matrix.xlsx`,
`gap-register.md` and `build-plan.md`. **Honest headline:** the RFP is an MVNO assurance bid. It names **M2M / IoT** and
**data entitlement** explicitly, and it names **none** of 5G slicing, cable / IPDR, cloud cost or digital-service SLA.
So only two slices move an RFP line; the rest are beyond-RFP and justified by the competitive gap alone.

### 8.1 What each slice satisfies or moves

| Slice | Compliance-matrix line(s) | Gap | Workstream | Effect |
|---|---|---|---|---|
| **U1 IoT** | `FM-05` (B2B / M2M / IoT misuse: concentration, unauthorised use, deviation from contracted profile); `RA-07` (validate M2M / IoT products); catalogue `FM-R14` (M2M / IoT profile deviation) | `G-07` (no baseline Steps; **the Anomaly Model now exists**, and the gap-register text predates it), `G-08` | `WS-14` (windows in SQL until the hold lifts; already lifted), `WS-15` (FM typology pack), `WS-16` | **Supplies** the IoT Schemas, References, `DA-C07..12` and the Anomaly Model that `WS-15` lists as `FM-R14`; moves `FM-05` toward Full and `RA-07` toward Full. **Coordination, not duplication:** if `WS-15` builds `FM-R14` first, U1 extends it (D-5G3). |
| **U1 `DA-C03`, U3 `DA-C06`** | `RA-03` (quota allocation, consumption, expiry, exhaustion, throttling, overrun, out-of-bundle charging); catalogue `RA-C05` (quota consumption vs allowance), `RA-C24` (zero-rating abuse) | `G-09` | `WS-16` | **Detects after the fact** the over-quota and out-of-bundle leakage `RA-03` names; does not enforce. |
| **U2, U3** (`DA-C01`, `DA-C02`) | `RA-04` (charging transactions against mediation and billing); `RA-05` (missing, duplicate, delayed, incomplete usage records); `RA-08` (rating validation incl. allowance, out-of-bundle); `RA-10` (invoice verification) | `G-09`, `G-26` (Huawei CBS, HLR, PCRF formats unvalidated) | `WS-16`, `WS-03` | Adds 5G / data-session records to the usage-vs-billing family. `RA-05` is already Standard; this extends its record types. `G-26` stays open: feed samples are a customer dependency. |
| **U1, U2, U5 KPIs** | `RA-32` (accuracy KPIs); `FM-28` (dashboards with drill-down); `REP-01`, `REP-02` (KPI and variance views) | `G-11` | `WS-20` | Ships KPIs of §5 inside the template, consumed by the `WS-20` KPI catalogue. |
| **U1, U2 Alerts** | `FM-23` (automated alert creation, prioritisation, escalation, SLA tracking) | `G-14`, `G-42` | `WS-23`, `WS-18` | Uses the shipped Alert and Incident path; per-entity alerting waits for `WS-18`. |
| **U5 feed ingestion** | `INT-07`, `API-01`, `API-02` (near-real-time and API ingestion) | `G-12`, `G-02` | `WS-21` | Not moved: U5 reads **file** drops. If `WS-21` ships push ingest, `digital_service_event` is its natural first client. |
| **U1, U2 roaming** | `FM-04` (roaming fraud, abnormal location, high-velocity usage) | `G-07`, `G-08` | `WS-14`, `WS-15` | `DA-C11` adds a **data-roaming** steering control; the voice and SMS roaming typologies stay with `WS-15`. |
| **U3 `DA-C06` leakage amount** | `RA-26` (identify, quantify and root-cause leakage) | `G-03` | `WS-10` | Findings carry `LEAKAGE_AMOUNT` so the `WS-10` impact ledger can consume them; **the ledger write stays manual** (decided 2026-10-04). |
| **U4 (cloud)**, **U6 (cable)** | none | none | none | Beyond-RFP. The RFP's `DEP-10..DEP-16` are about **hosting Inspecto** (Option B, cloud in KSA), not about assuring cloud spend; a reviewer must not read them as demand for U4. |

### 8.2 RFP lines out of scope for this plan, and why

| Line(s) | Why not here |
|---|---|
| `RT-12`, `RT-13`, `RT-14`, `RT-15`, `RT-19`, `RT-20` and the rest of `B.3.3` (`G-01`) | Real-time entitlement, quota, QoS and policy control through PCRF, OCS, CBS. Needs an inline or network-element path; the RFP routes it to a partner (`WS-30`, `B-01`). This plan supplies the **after-the-fact** detection those controls complement. |
| `FM-17` (real-time scoring), `FM-01` real-time clause (`G-02`) | Detection latency is the Collector poll cycle plus batch; seconds at best after `WS-21`, never per event. |
| `INT-01` network-element and signalling integration (`G-01`, `G-26`) | Probes and signalling taps. Charging-record **files** only. |
| `RT-35`, `RT-36` (control-event KPIs) | Count events of the partner's enforcement point. |
| Any network-performance requirement (latency, packet loss, coverage, RAN or core counters) | Needs probes or a counter feed: matrix rows F and X, "different buyer". |

## 9. Phased slices, the demand gate and tests

### 9.1 The demand gate: what to build now, what waits

The row says "build when a prospect names it". Applied per family, with the RFP as the one **prospect that has already named**
two of them:

| Slice | Build now? | Gate |
|---|---|---|
| **U0 spike + corpus design** | **Yes, cheap**: facts and a fixture plan, no shipped config. | None. |
| **U1 IoT** (`DA-C03`, `DA-C07..12`, Anomaly Model) | **Only with the RFP bid**, and then as a subset of `WS-15` / `WS-16`, not a parallel lane. | The bid decision (gap register: "rows move to the board only once the bid decision is taken") **or** a prospect naming IoT. |
| **U2 5G records** (`DA-C02`, `DA-C04`, `DA-C05`) and **U3 usage-vs-billing** (`DA-C01`, `DA-C06`) | **No.** | A prospect names 5G charging records or slice entitlement. U3 `DA-C06` may ride with the RFP's `RA-03` if the bid proceeds. |
| **U2b ASN.1 charging-record grammar** | **No.** | A named operator with a sample file (customer dependency; never commit the sample). |
| **U4 cloud**, **U5 digital service**, **U6 cable / IPDR** | **No.** | A named buyer for each; the two templates are independent so they can be demanded separately. |
| **U7 package** | with whichever template ships | |

Rationale: building ahead of demand gives a golden corpus of synthetic data and no evidence the operator's fields match. The
re-rating plan's rule applies: a closed vocabulary covers common structures; anything else is named and gated, not guessed.

### 9.2 Slices

Per `CLAUDE.md`: unit tests per slice (`-pl <module> -Dtest=A,B`, commas, never `+`); the full reactor gate only at handoff or
before a push touching shared seams; `-pl` without `-am` tests the stale sibling jar. Each slice ends with a **mutation pass**:
one mutant per SQL clause that must turn a named assertion red, built with a probe that would otherwise succeed. Fixtures come from a seeded
generator (`DataAssuranceCorpus`, `DigitalCorpus`) that plants each defect **and its benign look-alike**, and a test
proves the planted and benign sets are disjoint and the output deterministic.

| Slice | Delivers | Done when (verifiable) | Tests (unit level) |
|---|---|---|---|
| **U0 spike** | Facts, no shipped config: (1) rows-per-second for the IoT SIM-by-day anti-join at 1M SIMs by 30 days and a memory ceiling; (2) `by` cardinality and `stormCap` for an Alert Rule over customers; (3) can the XML bridge read an IPDR-shaped file with repeated records; (4) which module owns the telco golden tests (the re-rating plan found `features/inspecto-reconciliation`); (5) whether an Anomaly Model Feature can read a Job-written Dataset keyed by `ICCID`. | A measured §13 table with budgets; each answer recorded; **no repository change except this document**. | Throwaway harness, numbers only. |
| **U1 IoT** | Template `telco-data-assurance` skeleton; `iot_usage_daily`, `sim_lifecycle_event`, `iot_entitlement`, `plmn_policy`; `da_iot_sim_day`; `DA-C03`, `DA-C07`..`DA-C12`; Anomaly Model `iot_sim_usage`; KPIs and Alert Rules of §5 for IoT. | The golden asserts **exactly** the planted set per control: a zero-usage SIM, usage after deactivate, an over-pool SIM, a stuck session, a permanent roamer, a profile-switch cycler; look-alikes silent (a SIM legitimately idle inside `DORMANT_DAYS`, a SIM reactivated today, a roamer in an allowed PLMN, a short test session); the same corpus under **two host time zones** gives identical findings; the Anomaly Score names the planted SIM in the top band and not a quiet Peer Group member. | `DataAssuranceIotGoldenTest` (template creates, Jobs run, Datasets equal planted set); `DataAssuranceCorpusTest`; `IotAnomalyModelTest`; time-zone test; SQL-clause mutation pass. |
| **U2 5G records** | `data_session_xdr`; `da_slice_usage_day`; `slice_entitlement`; `DA-C02`, `DA-C04`, `DA-C05`. | Planted: a missing `RECORD_SEQ`, a duplicate record, an unclosed session, an unentitled slice, a 5QI outside `ALLOWED_5QI`; look-alikes silent (an interim record legitimately late inside the bill-run window, an entitlement that changed mid-day: rated at **record start**, the house convention, half-open). | golden extension; `ChargingChainTest` (chain integrity, every record type); mutation pass. |
| **U3 usage vs billing** | `DA-C01` (and `DA-C06`); Reconciliation `da_data_usage_vs_billed` with `includeRecordCount: false` (many sessions face one invoice row, the reason `ra_rated_vs_billed` needs it) and configured tolerances; optional read of `rerated_usage`. | A planted under-billed and over-billed subscriber each open a Break; an exact match is silent; a second day's run **updates** the same Break and Incident, it does not open another; over-billing is **not** summed into leakage (the re-rating plan's D-RR4 netting trap, inherited). | golden; `DataVsBilledReconTest`; Alert test. |
| **U4 cloud** | Template `digital-service-assurance` skeleton; `cloud_usage_line`, `cloud_commitment`, `cloud_price_list`; `da_cloud_cost_day`; `DA-C13`..`DA-C17`; Anomaly Model `cloud_spend`. | Planted: a billed line with no usage, a price above contract, a shortfall below commitment, an untagged cost, a spike; look-alikes silent (a legitimate tier-price change effective-dated, a first-day partial usage line). | `CloudAssuranceGoldenTest`; `CloudCorpusTest`; mutation pass. |
| **U5 digital service** | `digital_service_event`, `digital_service_invoice`, `service_sla_target`; `DA-C18`..`DA-C20`; `da_sla_series`; KPI bands. | Planted: metered-versus-invoiced gap, a breach series, a metering hole; look-alike silent (a documented maintenance window excluded by a Reference, a tenant with zero traffic by design). | golden extension; `SlaSeriesTest` (band edges inclusive or exclusive as documented). |
| **U6 cable / IPDR** | `ipdr_record`; `DA-C21`; industry-pack manifest if D-5G6 says so. | Planted: usage versus bill gap per modem; a modem swap and a duplicate interval collapsed, not double counted. | golden extension; XML-bridge fixture test. |
| **U7 package** | Runbooks, dashboard Widgets, KPI definitions, `product-map` solution-pack items (`kind: solution-pack`), offering entries, docs distilled, matrix cell updated. | Template gallery marks `creatable` per edition; the CI guards (vocabulary, doc-links, doc-counts, product-map, secret scan) run **locally** before any push (a green reactor is not green CI). | guards; template-creation test. |

Order: U0, then U1 (the only slice with an RFP line), then U2 and U3 (sequential: they extend one corpus), U4 / U5 / U6 are
independent and can run in parallel lanes on disjoint files. Nothing here edits `telco-ra`, `telco-fraud` or `telco-rerating`,
so it cannot collide with the re-rating, settlement, SIM-box or IRSF lanes; the only shared contracts are `rerated_usage`
(read) and the `ra_leakage` column list (matched, not edited).

## 10. Open decisions for the operator

| ID | Decision | Recommendation |
|---|---|---|
| **D-5G1** | Build now, or hold everything until a prospect names it? | **Hold** U2..U6; do U0 now; U1 only with the RFP bid decision (§9.1). |
| **D-5G2** | One template or two (`telco-data-assurance` and `digital-service-assurance`)? | **Two**; cloud and API metering are domain-neutral (§7). |
| **D-5G3** | IoT overlap with `WS-15` `FM-R14` and `WS-16` `RA-C05`: who builds it? | One owner. If the RFP proceeds, `WS-15` / `WS-16` own it and U1 supplies Schemas, References and controls into that lane. |
| **D-5G4** | Over-usage and dormant-SIM thresholds (`DORMANT_DAYS`, `PER_SIM_BYTES`, `MAX_SESSION_HOURS`): who owns the defaults? | Ship as **configuration**; a prospect sets numbers. No invented industry constants. |
| **D-5G5** | Cloud and IoT platforms by **API pull** (a Collector connector) or file drop only? | **File drops** in v1; an API Collector only on named demand. |
| **D-5G6** | Cable / IPDR: a control inside `telco-data-assurance`, or a declarative industry-pack manifest? Binary IPDR in scope? | Decide at U6; binary IPDR **out** unless a named operator needs it. |
| **D-5G7** | `DA-C01` reads `rerated_usage` from the re-rating plan (a dependency) or only the rater's own `CHARGE` (no dependency)? | **Fallback now, read `rerated_usage` when it ships**; build no rating logic here. |
| **D-5G8** | IoT volume budget: 1M SIMs by 30 days as the design point? | Measure in U0; accept or lower it before U1. |
| **D-5G9** | Classification of `ICCID`, `IMSI`, `MSISDN`, `EID`, and masking in dashboards (the `telco-fraud` masked-dashboard work set the precedent). | PII for all four; masked unless the viewer can reveal. |
| **D-5G10** | Commercial: `billable`, `orderCode` for each solution pack; matrix cell U after U7 (**N** to **P** or **Y**)? | The operator's call; the matrix changes only when a slice ships. |

**ANSWERED 2026-10-20 (operator, accepted the recommended answer): D-5G1…D-5G10.** D-5G1 hold U2…U6, do U0 now, U1 only with
the RFP bid decision; D-5G3 one owner (cross-lane: `WS-15` / `WS-16` own it if the RFP proceeds); D-5G2, D-5G4…D-5G10 as
recommended (D-5G6 is still decided at U6 and D-5G10 stays the operator's call at ship time, as the column says). The row stays
`hold: demand`.

## 11. Risks

1. **No evidence of the operator's fields.** Canonical Schemas are invented by this plan; a real CHF or IoT-platform export
   differs. Mitigation: parked vendor mapping, demand gate, the ASN.1 grammar only against a customer sample (never committed).
2. **IoT volume.** SIM count times days can dwarf the telco-ra corpus (600 xDRs). Unmeasured until U0.
3. **Overclaiming 5G.** "5G assurance" in a bid suggests performance and slice SLA monitoring. This pack cannot do that; the
   wording is **entitlement and usage versus billing**, and §2 is quoted in any proposal.
4. **A competitive gap that moves no RFP line.** U4 and U6 serve no listed requirement; building them for the RFP wastes the bid's time.
5. **Overlap with sibling lanes.** SIM-box and IRSF cover voice-fraud typologies; roaming and steering for data is the only
   edge here. The IoT overlap with `WS-15` is D-5G3.
6. **Time zones and effective dating** (§3), as in the re-rating plan.
7. **Late and partial records.** A charging chain closed after the bill run turns a true finding into a false one if the
   cut-off is not a parameter; every chain control takes a `late_after_hours` setting.
8. **Vocabulary.** *Pipeline*, *Dataset*, *Incident*, *Collector*, *Expectation* / *Alert Rule* / *Decision Rule*, *Measure*,
   *Reference*; never bare "rule", never the other banned synonyms (`docs/GLOSSARY.md` §0).

## 12. Backlog and docs touched

- `BACKLOG.md`: only the **text** of row `PACK-5G-IOT-ASSURANCE-1` (now points at this plan, names the demand gate); no new row, no count change.
- `INDEX.md`: this plan's row in the in-flight table.
- On ship (U7): distil as-built facts into `okf/product/module/config/plan-spaces.md`, add solution pages under `stakeholders/solutions/`,
  update matrix row U, then `git mv` this plan to `archived-documents/plans-archive/`. Anything still open goes to `BACKLOG.md` first.

## References

- Matrix: [`okf/product/layer/product/brochure-competitive-matrix.md`](../../layer/product/brochure-competitive-matrix.md) rows U, F, X, A, P.
- Earlier plans: [`rerating-tariff-validation-plan.md`](../../../../archived-documents/plans-archive/rerating-tariff-validation-plan.md), [`partner-settlement-wholesale-plan.md`](../../../../archived-documents/plans-archive/partner-settlement-wholesale-plan.md).
- Parsers: [`okf/product/module/engine/spec-parser-plugins.md`](../../module/engine/spec-parser-plugins.md). Anomaly Detection: [`okf/product/module/anomaly/spec-anomaly-scores.md`](../../module/anomaly/spec-anomaly-scores.md).
- Canonical vocabulary: [`GLOSSARY.md`](../../../../GLOSSARY.md) (the single source of truth).
- RFP working set (git-excluded, this checkout only): `docs/superpower/rfp-mvno-assurance/` (`compliance-matrix.xlsx`, `gap-register.md`, `build-plan.md`, `assurance-content-catalog.xlsx`).

## 13. Measurements (filled by slice U0)

_Empty until U0 runs: IoT anti-join throughput and memory, Alert Rule `by` cardinality, XML-bridge IPDR read, the owning module of the golden tests, Anomaly Model over a Job-written Dataset._
