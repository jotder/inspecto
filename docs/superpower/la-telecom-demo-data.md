# Link Analysis telecom demo: seed data (Sprint 0)

**Status: BUILT 2026-10-11 (Sprint 0 of `la-telecom-fraud-demo-storyboard.md`).** These are the notes for the synthetic
telecom corpus in the `la-showcase` Space Template. Every number is invented. Country codes 999 and 998 are not
assigned to any real network.

## 1. What ships where

| Part | Path |
|---|---|
| Generator (deterministic, seed `20261011`) | `tools/gen-la-telecom-data.py` |
| Raw extracts (one 60-day file per feed, 2026-08-01 to 2026-09-29) | `spaces/_templates/la-showcase/data/inbox/<feed>/<FEED>_20260929.csv` |
| 8 Collector + Pipeline + schema pairs | `spaces/_templates/la-showcase/config/telecom/<feed>_pipeline.toon`, `<feed>_schema.toon` |
| 2 `sql.template` Jobs | `config/jobs/telecom_links_job.toon`, `config/jobs/telecom_msisdn_indicators_job.toon` |
| Dataset registry entries | `config/registry/datasets/telecom_links_dataset.toon`, `telecom_msisdn_indicators_dataset.toon` (MSISDN columns classified `MSISDN`) |
| Saved Link Analysis view | `config/registry/link-analysis-views/telecom_fraud_network.toon` |
| Golden test | `platform/inspecto-engine/src/test/java/com/gamma/job/TelecomLinksGoldenTest.java` |

Raw Datasets: `voice_cdr` (a_number, b_number, start_time, duration_s, call_type, redirecting_number, imsi, imei,
destination_type), `sms_cdr` (orig_msisdn, term_msisdn, ts, message_type), `hlr_eir` (msisdn, imsi, imei,
forwarding_to, sim_swap_at), `crm_kyc` (msisdn, id_doc_hash, address_hash, account_holder, activation_date,
subscriber_type, kyc_complete), `recharge` (msisdn, voucher_id, agent_id, card_hash, ts) and `provisioning` (msisdn,
old_subscriber, new_subscriber, event, ts). Two small feeds were **added** because the indicators need them:
`fraud_blocklist` (msisdn, listed_at, reason) and `fraud_alarms` (msisdn, raised_at, alarm_type).

`destination_type` is one of `onnet`, `international` or `premium_intl`. `call_type` is `outgoing`, or `forwarded` for a
leg re-routed by call forwarding: the CDR then has a = the caller, b = the forwarding target and redirecting_number =
the forwarding party.

Volumes: 20 342 voice rows, 7 131 SMS, 2 544 HLR/EIR, 2 423 CRM, 4 541 recharges, 2 577 provisioning events. That is
2 400 background subscribers plus the HUB and 22 planted lines.

## 2. How to run the seed

1. Optional: `python tools/gen-la-telecom-data.py` rewrites the CSVs. The output is byte-identical on every run.
2. Create a Space from the `la-showcase` template, or use `node tools/seed-la-showcase.mjs --scaffold …` or the
   `.claude/launch.json` entry. The eight telecom Pipelines ingest their inbox files when the Space boots.
3. The two Jobs run every 10 minutes. You can also start them now with *Run* on `telecom_links` and
   `telecom_msisdn_indicators`. Each Job rebuilds its whole snapshot, so a re-run gives the same result.
4. Open the saved view `telecom_fraud_network`, or start an Investigation over `telecom_links_dataset` (source
   `a_msisdn`, target `b_msisdn`, link kind `link_kind`) seeded with the suspect `99979100001`.

## 3. Planted MSISDNs

| Typology | MSISDNs | What makes it stand out |
|---|---|---|
| **Suspect / Wangiri** | **`99979100001`** (WANGIRI-A, the suspect), `99979100002` (WANGIRI-B, already blocklisted) | Both share one handset (IMEI). A rings 400 victims for 0–3 s and none of them call back on-net. 40 victims call back, and their callbacks are forwarded (HLR `forwarding_to` plus CDR redirect) to the premium number `99891000001`. |
| Premium international range (IRSF/Wangiri destinations) | `99891000001`–`99891000012` (PREMIUM-01..12) | Not subscribers, so they have no indicator row |
| **IRSF** | `99979200001`–`99979200004` | Activated 2026-09-08, all four at one address. They make 70–90 long night calls each (600–3 600 s), all to the premium range. IRSF-01 and IRSF-02 call PREMIUM-01. IRSF-01 is registered on the subscription-fraud identity. |
| **Subscription fraud** | `99979300001`–`99979300005` | One identity document, shared with IRSF-01. Activated 2026-09-12 to 09-16, KYC incomplete, all traffic international. |
| **SIM box** | `99979400001`–`99979400006` | Six SIMs rotate across one 8-slot gateway, so each SIM shows 4 IMEIs and each IMEI shows up to 4 SIMs, with 3 SIM swaps each. They make 160–200 outbound calls each and take 1 stray inbound call in total. SIMBOX-03 is blocklisted. SIMBOX-01 is topped up with the same card as SUBFRAUD-03. |
| **Mules** | `99979500001`–`99979500005` | Forwarding chain MULE-01 → MULE-02 → MULE-03 (HLR plus 30 forwarded calls). All five top up only at rogue agent `AGT-666` (SUBFRAUD-05 topped up there once too), with cloned vouchers `VMULE00000A1` (MULE-01..03) and `VMULE00000A2` (MULE-03..05). MULE-01's customer `CUST-M01` took over SIMBOX-06 and SUBFRAUD-04 (ownership transfers, sim_history). |
| **Supernode** | `99970000100` (taxi dispatch) | 650 distinct callers, more than 500 contacts |
| Stale background block-list entry | `99971002000` | Blocklisted in March for bad debt. The only loud background number. |

### Degree ladder from the suspect `99979100001`

These are shortest paths over all link kinds, treated as undirected, **with the hub not expanded**. The hub is reached
and flagged high-connectivity, but it is not expanded (the hub guard on expand, `hubThreshold` default 500). If the hub
were expanded, it would shortcut the chain: suspect → victim → HUB → a SIM-box callee → SIMBOX-01 is also 4 hops. The
test computes distances with the HUB as a non-expanded node and pins the ladder below.

| Degree | Reached | Via |
|---|---|---|
| 1 | WANGIRI-B, PREMIUM-01, the 400 Wangiri victims | shared_device, forwarding, voice |
| 2 | IRSF-01 (and IRSF-02), HUB `99970000100` (shown, not expanded) | voice to PREMIUM-01; a victim calls the hub |
| 3 | SUBFRAUD-01..05 | shared_identity (`id_doc:` of IRSF-01) |
| 4 | SIMBOX-01, SIMBOX-06, MULE-01..05 | `card:` of SUBFRAUD-03; `subscriber:CUST-M01` (SUBFRAUD-04's line); `agent:AGT-666` (SUBFRAUD-05) |
| 5 | SIMBOX-02..05 | shared_device (the gateway IMEIs) |

The intended path is suspect →(forwarding) PREMIUM-01 →(voice) IRSF-01 →(shared_identity) SUBFRAUD-03 →(shared_payment,
card) SIMBOX-01.

No other shortcut exists because of how the background is built. Subscribers sit in 100 closed calling clusters of 24, and a
cluster never calls outside itself except to the HUB. Wangiri victims come only from clusters 0–39. The SIM box calls
only into clusters 60–99. Mule callers come from clusters 40–59.

## 4. `telecom_links` (Job `telecom_links`)

Columns, in order: `a_msisdn, b_msisdn, link_kind, first_seen, last_seen, events, total_duration_s, via`, then
`avg_duration_s`. `avg_duration_s` is appended after the contract columns at the main session's request, for
per-event pattern bands. There is one row per ordered (a, b, link_kind). The four shared kinds are emitted **in both
directions** (a→b and b→a, same `via` and `events`), so direction filters out / in / reciprocal all work.

| link_kind | From | a → b | events | total_duration_s | via |
|---|---|---|---|---|---|
| voice | `voice_cdr` (all legs) | caller → called | calls | sum of durations | NULL |
| sms | `sms_cdr` | orig → term | messages | 0 | NULL |
| forwarding | `voice_cdr.redirecting_number → b_number` and `hlr_eir.msisdn → forwarding_to` | forwarding party → target | forwarded legs + HLR settings | seconds forwarded | `cdr`, `hlr` or `cdr\|hlr` |
| shared_device | IMEI in `voice_cdr` (A party) and `hlr_eir` | both directions | distinct shared keys | 0 | `imei:<imei>` |
| shared_identity | `crm_kyc` id document, address | both directions | " | 0 | `id_doc:<hash>`, `address:<hash>` |
| shared_payment | `recharge` voucher, agent, card | both directions | " | 0 | `voucher:<id>`, `agent:<id>`, `card:<hash>` |
| sim_history | `provisioning` real ownership transfers / reassignments only (event `ownership_transfer`, `sim_swap` or `number_reassignment` with old_subscriber ≠ new_subscriber); activations and same-customer swaps are ignored, and a customer simply holding two lines is shared_identity, not sim_history | both directions | " | 0 | `subscriber:<customer id>` |

The `via` column holds IMEIs and hashed identifiers, so the Dataset registry classifies it `PII`.

How the fields are filled:

- When several keys link the same pair, `via` lists them sorted and separated by `|`. The SQL guard refuses any `;`,
  even inside a string literal.
- For the shared kinds, first_seen and last_seen span the evidence timestamps of both MSISDNs on the key: call time,
  `sim_swap_at`, activation date, top-up time or event time.
- An HLR row has no timestamp, so it is stamped with the as-of instant (the newest `voice_cdr.start_time`).

**Pair-explosion cap (`max_key_msisdns`, default 10).** A shared key held by more than 10 distinct MSISDNs is treated as
common, not as evidence, and makes no pairs. Examples are a retail agent serving about 60 lines or a shop's address.
So the worst case is 45 pairs per key. On the shipped corpus all 40 background agents (`AGT-001..040`) are dropped, and
the rogue `AGT-666` (5 lines) is kept.

Row counts on the shipped corpus (shared kinds counted in both directions): voice 10 721, sms 4 932,
shared_identity 880 (households and two-line customers), sim_history 56, shared_payment 42, shared_device 28,
forwarding 24.

## 5. `telecom_msisdn_indicators` (Job `telecom_msisdn_indicators`)

The Job writes one row per `crm_kyc` MSISDN (2 423 rows). The as-of instant is the newest `voice_cdr.start_time`, so
the shipped corpus scores the same on any day.

Factor columns:

- `on_blocklist`: the MSISDN is in `fraud_blocklist`.
- `alarms_recent`: alarms in the last `alarm_days` (14).
- `recent_activation_high_intl`: all three hold:
  - activated within `recent_activation_days` (30);
  - at least `min_calls` (10) outbound calls;
  - `premium_share` ≥ `high_intl_share` (0.3).
- `shares_imei_with_flagged`: an IMEI is shared with another MSISDN that is blocklisted or has a recent alarm.
- `premium_destination_share`: outbound calls to `premium_intl`, as a share of all outbound calls.
- `short_call_ratio`: outbound calls of at most `short_call_s` (5) seconds, as a share of all outbound calls.
- `one_way_ratio`: the share of distinct called numbers that never call back.

Typology columns (requested by the pattern-engine audit; the pattern stages cannot aggregate these):

- `out_distinct_b` and `inbound_distinct_b`: distinct numbers called, and distinct callers.
- `avg_dur_s`: outbound voice seconds divided by outbound calls.
- `out_in_ratio`: out_events / (in_events + 1).
- `premium_share`: outbound calls to `premium_intl` or `international`, as a share of all outbound calls.
- `imei_per_msisdn`: distinct IMEIs this MSISDN used.
- `msisdn_per_imei`: the largest number of MSISDNs seen on any one of its IMEIs.
- `activated_at`.

`destination_type` stays out of `telecom_links`. These columns read it from `voice_cdr`.

**Score (0–100, a transparent weighted sum):**

```
indicator_score = 25 × on_blocklist
                + 15 × min(alarms_recent, 3) / 3
                + 15 × recent_activation_high_intl
                + 15 × shares_imei_with_flagged
                + [only with ≥ min_calls outbound calls]
                  10 × premium_destination_share + 10 × short_call_ratio + 10 × one_way_ratio
```

The result is clamped to [0, 100] and rounded to 1 decimal place. The weights sum to 100. Every ratio divides by
`NULLIF(…, 0)` and falls back to 0, and calls with a NULL or empty b_number are not counted as outbound. The volume gate stops a background line with three
calls from scoring on its ratios.

How each typology stands out on the shipped corpus:

| Typology | Rule | Matches |
|---|---|---|
| Wangiri | `out_distinct_b ≥ 50` and `avg_dur_s < 5` | exactly the two Wangiri lines (A: 400 called, 0 inbound, short_call_ratio 1.0) |
| IRSF | `premium_destination_share ≥ 0.8` and `avg_dur_s ≥ 600` | exactly the four IRSF lines |
| SIM box | `imei_per_msisdn ≥ 3`, `msisdn_per_imei ≥ 3` and `out_in_ratio ≥ 50` | exactly the six SIM-box lines |
| Subscription fraud | `recent_activation_high_intl` | exactly SUBFRAUD-01..05 and IRSF-01..04 |

All 17 Wangiri, IRSF, subscription-fraud and SIM-box lines score at least 25. The only background line scoring 15 or
more is the stale block-list entry `99971002000`. The background median is 0 and its 99th percentile is 6.0. The `indicator_score` column carries no classification in the
registry.

## 6. Tests

`TelecomLinksGoldenTest` (inspecto-engine) copies the template and runs it end to end:

1. The real `CollectorProcessor` ingests the 8 feeds, and nothing is quarantined.
2. The real `JobService` runs both `sql.template` Jobs.

It then asserts:

- the exact column contracts;
- all seven link kinds have more than 0 rows, with one row per ordered pair and kind;
- the shared kinds have a mirror row with the same `via` and `events`;
- sim_history comes only from real transfers;
- each planted ring's links and their `via`;
- the supernode has more than 500 callers;
- the cap drops the retail agents;
- the degree ladder 1..4 from the suspect with the hub not expanded, and the intended path hop by hop;
- the typology stand-out sets in §5;
- the score equals the documented formula on every row.

Run it with:

`mvn -o -B -pl :inspecto-engine -am test -Dtest=TelecomLinksGoldenTest -Dsurefire.failIfNoSpecifiedTests=false`

Use JDK 27 (`JAVA_HOME=C:\sandbox\.graalvm-cache\jdk-27-win`).

## 7. Demo gotchas

1. **Expand counts rows, not `events`.** Investigation expand aggregates `COUNT(*)` per (s, t, k)
   (`InvestigationRoutes.java:1126`). `telecom_links` is already aggregated, so every pair counts as 1. Keep
   `minEvents` at 1, and expect ranking to ignore `events` until a platform fix lands (being planned).
2. **Four-eyes on the first expand.** `config/link-analysis.toon` sets `four_eyes_fan_out_above: 5`, and the suspect has
   about 400 neighbours, so the first expand waits for an approver. Either run the persona flow (`fm.analyst` requests,
   `fm.manager` approves in Oversight), or set `maxFanOut` ≤ 5 on the first step.
3. **The Jobs run on a `*/10` cron.** After seeding a Space, press *Run* on `telecom_links` and
   `telecom_msisdn_indicators` rather than waiting up to 10 minutes.

## 8. Notes and open points

- The four "shared X" kinds use option (a) of storyboard §3: MSISDN–MSISDN pairs derived in SQL, with the key in `via`.
- The Jobs are `sql.template` Jobs on a 10-minute cron, not Pipeline `sql` steps. A Pipeline step sees only its own
  input file and cannot join six Datasets. A `pipeline.commit` trigger would fire before all eight feeds have landed.
- A separate `telco-fraud` Space Template (13 typologies, per-offender Alert Rules) already exists. It has its own feeds
  and was not reused, because this corpus is shaped for Link Analysis traversal.
