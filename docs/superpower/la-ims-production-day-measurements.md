# IMS production day: measurements, entity and link profile

**Status: working notes, NOT committed (operator, 2026-10-16).** Companion to
[`la-ims-showcase-plan.md`](la-ims-showcase-plan.md) section 6. Every figure is an aggregate over the private IMS Space;
no identifier appears here. "Measured" = timed on this host; "~" = estimate.

**Host:** Windows laptop, 12 logical cores, 31.7 GB RAM, one C: SSD (220 GB free at the start).
**Data:** `fct_huawei_ims`, one day 2026-10-01 00:00:00-23:59:59, 100 Parquet files, 12.25 GB, **132,165,497 records**,
83 columns, one schema.

## 1. Time per activity

| # | Activity | Volume in → out | Where | Time |
|---|---|---|---|---|
| 1 | Schema and row count from Parquet metadata | 100 files | laptop, Python DuckDB | < 1 s |
| 2 | Value profile of the used columns (8 code columns, 4 identifier shapes) | 132 M rows | laptop | ~45 s total (2-9 s per query) |
| 3 | Move the day into the Space store | 12.25 GB | same volume | instant (rename) |
| 4 | Threshold calibration (SMS, voice, device distributions) | 132 M → 3 tables | laptop, 10 threads, 12 GB | 72 s |
| 5 | `ims_links` dry run | 132 M → 39.0 M links | laptop, 10 threads, 12 GB | 335 s |
| 6 | **`ims_links` Job** | 132 M → 39.4 M links | server, DuckDB cap 10 GB | **572 s** (9.5 min) |
| 7 | **Index build, FULL** | 39.4 M links → 12.35 M nodes, 838 MB | server, 1 build thread | **316 s** (5.3 min) |
| 8 | `ims_typology_counts` Job | 39.4 M links → 1 row | server | 7-45 s |
| 9 | Alert evaluation | 1 row | server | < 1 s |
| 10 | Entity List refresh: compute / apply (4 lists, 240 members) | 39.4 M links | laptop / API | ~30 s / ~2 s |
| 11 | Entity profile IMSI, cold (old code: 1 GB, no sampling) | 132 M rows | server | 568 s; Volume and Relationships out of memory |
| 12 | Entity profile IMEI, cold (old code) | 132 M rows | server | 728 s |
| 13 | Entity profile MSISDN, cold (old code) | 132 M rows + 39.4 M links | server | > 25 min (client gave up) |
| 14 | Entity profile, warm (cache) | - | server | 0.4 s |
| 15 | The two failing profile reads, 1 GB / 2 threads (sandbox settings) | 132 M rows | laptop | out of memory at 31 s and 131 s |
| 16 | The same two reads at 4 GB (the fix's default) | 132 M rows | laptop | 58 s and 149 s |
| 17 | The same two reads hash-sampled at 1 GB | 132 M rows | laptop | 41 s and 123 s |
| 18 | Entity and link profile below (section 2-3) | 132 M rows + 39.4 M links | laptop, 10 threads | 166 s |
| 19 | FAILED-SMS analysis (section 5) | 68.7 M MESSAGE records | laptop, 8 threads | ~60 s |
| 20 | Redeploy: worktree + `npm ci` + `package.ps1 -Edition Preview -Ui la-app` | master `2ea72fad0` | laptop | ~25 min (npm 33 s) |
| 21 | Redeploy: back up, copy over the live bundle, restart | 467 MB backed up | laptop | ~1 min, plus a restart |
| 22 | **Entity profile IMSI, cold, NEW code** (4 GB, sampling) | 132 M rows | server | **669 s**; Volume and IMSI→IMEI assessed; →MSISDN timed out at 300 s |
| 23 | **Entity profile IMEI, cold, NEW code** | 132 M rows | server | **731 s**; Volume and IMEI→IMSI assessed; →MSISDN timed out at 300 s |
| 24 | IMSI relationships again with `entity_profile.timeout_s` 600 | 132 M rows | server | 697 s; →IMEI assessed again (about 97 s); **→MSISDN still ran past 600 s** |
| 25 | Same, with the timeout-retry fix `d53445504` (la-api jar swapped in) | 132 M rows | server | 1,340 s; →IMEI assessed; **→MSISDN: the exact read AND the 1-in-64 sampled retry each ran past 600 s** |
| 26 | Same, with same-party pairing `f3e1c9b47` and `columns[].party` set on `ims_cdr_dataset` | 132 M rows | server | **453 s; →IMEI and →MSISDN both assessed, exact (no sampling)** |

"Old code" = the Entity profile before commit `2ea72fad0`; "new code" = the redeployed :8080 bundle (2026-10-16 15:00).

**Estimates.**
- Nightly `ims_links` at the server's default DuckDB memory (about 3.2 GB here, not the 10 GB above): ~15-25 min.
- Nightly chain (links → index → typology → Alerts): ~25-35 min.
- Cold Entity profile after the fix: IMSI or IMEI measured 11-12 min (rows 22-23), MSISDN ~20-30 min; warm reads under a second.
- Disk: index 0.84 GB, links Dataset ~1-2 GB, DuckDB spill up to ~4 GB during the links Job.

## 2. Entity profile

**Distinct Entities (approximate).**

| Entity Type | Distinct |
|---|---|
| MSISDN, served party | 9,143,341 |
| MSISDN, other party (subscriber numbers, 11-13 digits) | 7,738,458 |
| MSISDN, either side | 11,737,554 |
| IMSI | 7,411,322 |
| IMEI | 9,473,150 |
| distinct (MSISDN, IMSI, IMEI) triples of the served party | 9,361,689 |

**How the identifiers relate** (served party, per Entity: how many of the other type it carries).

| Relationship | Entities | exactly 1 | 2 | 3-5 | 6+ | max |
|---|---|---|---|---|---|---|
| IMSI → IMEIs (one SIM in several handsets) | 8,978,724 | 97.92 % | 1.90 % | 0.18 % | 0.0005 % | 8 |
| IMEI → IMSIs (one handset, several SIMs) | 9,035,380 | 98.86 % | 1.04 % | 0.08 % | 0.0151 % | 12,039 |
| IMEI → MSISDNs | 9,035,792 | 98.84 % | 1.06 % | 0.08 % | 0.0151 % | 12,039 |
| MSISDN → IMSIs | 8,982,740 | 100.0 % (rounded) | 0 | 0 | 0 | 1,695 |
| IMSI → MSISDNs | 8,983,052 | 99.98 % | 0.02 % | 0 | 0 | 3 |

Read: SIM ↔ number is one-to-one; the handset is where sharing shows (about 1.1 % of IMEIs carry 2+ SIMs, about 1,370
carry 6+). Two outliers need explaining before a demo: one IMEI with 12,039 SIMs (a placeholder IMEI, a test rig or a
SIM-box) and one MSISDN with 1,695 IMSIs (a placeholder or technical number).

**Activity per served MSISDN (records in the day):** p50 9, p90 34, p99 78, max 41,676.

**Value shapes** (from the earlier value profile): served MSISDN 12 digits in 99.96 %, home prefix in 99.8 %; IMSI and
IMEI 15 digits; 636 k rows with a blank IMEI; the other party is non-numeric in 59.4 M rows (sender names and short codes,
about 165 k distinct) and carries a `;phone-context` suffix in 7.3 M.

## 3. Link profile (`ims_links`, 39,411,289 links)

| link_kind | Links | Distinct sources | Distinct targets | Events | Events per link | FAILED events |
|---|---|---|---|---|---|---|
| voice | 21,654,749 | 6,650,428 | 6,924,964 | 40,605,477 | 1.88 | 0 |
| sms_service | 16,865,004 | 55,182 | 7,931,934 | 59,487,455 | 3.53 | 8,643,284 |
| shared_device | 490,689 | 132,718 | 103,238 | 12,140,966 | 24.74 | 0 |
| sms (person to person) | 328,890 | 217,996 | 333,279 | 2,206,781 | 6.71 | 1,621,872 |
| co_target | 71,957 | 7,457 | 9,529 | 17,842,907 | 247.97 | 17,757,066 |

**Degree (both ends, all links):** 12,349,996 nodes; p50 3, p90 11, p99 27, p99.9 52, max 5,568,151; 23.9 % of nodes
have a single link; **99 nodes above the hub threshold (500)**.

**Non-numeric nodes** (sender names, short codes): 18,024 nodes holding 16,965,791 link ends - almost all of
`sms_service`. They are in `ims_links` but **not in the index** (the index keys ends through the MSISDN normaliser and
drops what does not normalise: about 12.8 M sms_service and about 45 k voice edges).

**Voice:** total duration per link p50 95 s, p90 607 s, p99 3,191 s; 59.4 % of voice links carry one call.

**Person-to-person SMS:** 47.6 % of links are fully FAILED; 48.0 % at 90 % FAILED or more - the burst rule needs its
sender-count condition (20+) to stay selective.

**Index (version 70):** 12,349,996 nodes, 838 MB; edges by kind voice 21.61 M, sms_service 4.05 M, shared_device
483,586, sms 328,890, co_target 69,600; degree p50 2, p90 9, p99 22, max 1,067,398 (index degree differs from the table
above because sender-name edges are not indexed).

**Links by first_seen hour:** 0.10 M (03:00) to 2.92 M (13:00); busy 07:00-20:00 (≥ 1.5 M per hour).

## 4. Typology outcome (recalibrated thresholds)

| Rule | Hits |
|---|---|
| SMS burst target (20+ person-to-person senders, 95 %+ FAILED) | 29 |
| Multi-SIM device (50+ distinct MSISDNs; within the 200 pair cap) | 58 (76 in the data) |
| Call fan-in hub (100+ callers, subscriber numbers) | 13 |

## 5. Entity profile on the new code (live, :8080)

| Section | IMSI | IMEI |
|---|---|---|
| Volume | assessed: 7,411,322 distinct over 132,165,497 rows; dominant value read from a row sample; only the blank sentinel (0.04 %) | assessed: 9,473,150 distinct; blank sentinel 0.48 % |
| Relationship to IMEI / IMSI (exact, not sampled) | 97.92 % one-to-one, max 8, 47 IMSIs at 6+ | 98.86 % one-to-one, max 12,039, 1,361 IMEIs at 6+ |
| Relationship to MSISDN | first: not assessed (timeout); **after the party fix (row 26): 99.98 % one-to-one, max 3, 0 at 6+, 8,983,052 IMSIs, exact** | not re-read yet (expected the same shape: served columns only) |
| Raw identifier in the response | none | none |

The figures agree with the offline profile (section 2). The MSISDN relationship reads two bound columns (served and other
party) over 132 M rows; `entity_profile.timeout_s` for the `ims` Space was raised to the 600 s maximum (2026-10-16, config
only). At 600 s it still times out (row 24): 600 s is the item's ceiling, so configuration alone cannot finish
this read on a full day. Left open: retry a timed-out relationship read over the Entity sample (as out of memory
already is), or narrow the read; until then the section says "not assessed" with the timeout reason.
The sampled retry was deployed (row 25) and did not help this read: the Entity sample cuts the grouping, not the
scan - every one of the 132 M rows is still read and normalised for BOTH MSISDN columns (served and other party), at
the sandbox's 2 threads. Pairing the served IMSI with the OTHER party's MSISDN is also not a meaningful relationship
(it counts contacts, not numbers on the SIM). Options: pair only columns of the same party, more profile threads, or
P4's scheduled profile off the request path.
**Resolved 2026-10-16 (row 26):** a Dataset column may name its `party`; relationships pair only columns of the same
party. `ims_cdr_dataset` marks `served_*` as `served` and `other_msisdn` as `other`, so the served IMSI pairs with the
served MSISDN only - one column instead of two, and the meaningful relationship. Exact in 453 s for the whole section.

## 6. Why so many FAILED SMS

Measured on the 68.7 M MESSAGE records of the day (direction 1 = the served party sent, 2 = it received).

| | Records | FAILED | FAILED share |
|---|---|---|---|
| Sent (direction 1) | 2.37 M | 1.98 M | **84 %** (to home numbers 80 %, international 99 %) |
| Received (direction 2) | 66.29 M | 8.40 M | 13 % |

1. **A FAILED record is a delivery ATTEMPT.** Undeliverable received SMS are retried: FAILED records per sender-receiver pair
   p50 2, p90 6, p99 13, max 315; gap between attempts p50 329 s, p90 7,200 s. The 10.38 M FAILED records belong to 3.97 M
   sessions (about 2.6 attempts each). Not concentrated: the top 1 % of receivers hold 8.6 % of the FAILED records (phones
   off or unreachable, not a few targets). `termination_reason_key` is 0 on every record, so the cause is not recorded.
2. **Sent SMS mostly fail on the IMS leg.** 80-99 % FAILED on direction 1 fits a handset trying SMS over IMS first and
   falling back to the circuit-switched path, where it may be delivered - outside this feed. Not provable from this table
   alone; the SMSC/MSC records would show the final outcome.
3. **The link construction amplifies it.**
   - `sms` (person to person, 73 % FAILED events) needs both ends in 11-13 digits; received SMS carry the sender as a
     7-digit number starting with 2 (13.6 M records), so those go to `sms_service` and `sms` is built mostly from SENT
     records - the ones failing on the IMS leg. 98 % FAILED to international targets, 68 % to home targets.
   - `co_target` (99.5 %) is built from those same `sms` links and sums each sender's events once per co-sender pair, so
     the same failures are counted several times. 99.6 % FAILED on home targets, 97 % on international.
   - `sms_service` (14.5 %) is A2P traffic (banks, operator) to unreachable phones plus retries - a normal rate.
4. FAILED share by hour: 9.8 % (05:00) to 19.5 % (11:00).

**Consequences.** The burst rule (20+ senders, 95 %+ FAILED) is likely to flag IMS-fallback and retry patterns rather
than fraud: present it as "failed delivery attempts". Possible links-Job changes (not made): count distinct `session_id`
instead of records (drops retries); fold 7-digit national senders into person-to-person SMS; stop the per-pair double
count in `co_target`. A fraud claim needs the CS SMS feed.

## 7. Deployment (2026-10-16)

The demo server is the Preview `la-app` bundle at `C:\sandbox\inspecto-preview\inspecto-la` (:8080, WSO2 sign-in). It was
rebuilt from master `2ea72fad0` (every fix of this session) and swapped in place: jars, `ui\`, `docs\`, `runtime\` and the
template gallery replaced; the Spaces, `serve-preview-la.bat`, the WSO2 setup script and `runtime-natives\` kept. Backup:
`C:\sandbox\inspecto-preview\backup\predeploy-20261016-1454`. ⚠ The first start failed (`SSLHandshakeException` on the
OIDC discovery): the fresh `runtime\lib\security\cacerts` lacked the imported local WSO2 certificate; restoring it from
the backup fixed the boot. Keep that file on every redeploy.
