---
type: Feature
title: "Telecom bypass (SIM-box) — admin guide"
description: "Install, size, wire the Entity Lists, run in shadow mode and verify the Telecom bypass pack."
tags: [telco-bypass, admin-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom bypass (SIM-box) — admin guide

## Prerequisites

- Edition: Standard and up (`minEdition: standard`).
- Product Map Item `solution-pack.telco-bypass` (`product-map/solution-pack/telco-bypass.toon`); commercial fields are not set (decision D-BP10 is the operator's).

## Install

1. Confirm the Space Template is offered: `GET /api/v1/spaces/templates` lists `telco-bypass`.
2. Create a Space from it (needs `canAdminister`):

   ```
   POST /api/v1/spaces
   {"id": "<space-id>", "template": "telco-bypass"}
   ```

   The first refusal creates no Space (`docs/api/openapi-v1.json`, `postSpaces`).
3. Point the operator's mediation at `data/inbox/voice_cdr` (one file per day, `VOICE_CDR_<yyyyMMdd>.csv`) and, once, load the three Reference feeds into `data/inbox/cell_site`, `data/inbox/number_plan` and `data/inbox/subscriber_profile`. No sample files ship: use the CDR contract in `config/voice_cdr/voice_cdr_schema.toon`.

## The CDR contract

Required columns: `record_id, start_ts, direction, service, a_number, b_number, duration_s, roaming, cell_id`. Optional: `answered, release_cause, imei, imsi`; leave them blank, or end the file after `cell_id`. Numbers should be bare E.164 digits. Timestamps are naive and must all be in the zone named by the Job parameter `feed_zone`; set `local_zone` to the zone whose night the pack should use. Whether your operator, or the MVNE for an MVNO, can supply IMEI, IMSI, answer or release data is the largest unknown (plan section 2.1); the pack runs without them.

## Create the Entity Lists (once, by hand)

A Space Template cannot ship an Entity List (the lists are facts in the Space's own log), so the pack ships only an empty read-only copy of each list's sidecar, `data/entity_list_tb_allow` and `data/entity_list_tb_watch`, and the Jobs read those until you create the lists. Create them with the ids below: the Jobs read the store `entity_list_<id>`, and the id uses underscores because a hyphen would need a quoted store name. The seeded folder carries the list's marker, so the list's first write claims it. Needs `canManageIncidents`.

```
POST /api/v1/entity-lists   {"id": "tb_allow", "title": "SIM-box allow list", "purpose": "allow", "entityType": "msisdn", "reason": "..."}
POST /api/v1/entity-lists   {"id": "tb_watch", "title": "SIM-box watch list", "purpose": "watch", "entityType": "msisdn", "reason": "..."}
POST /api/v1/entity-lists/tb_allow/members   {"add": ["<msisdn>"], "expiresAt": "<ISO instant>", "reason": "<registry id of the verified line>"}
```

| List | Purpose | Read by | Notes |
|---|---|---|---|
| `tb_allow` | `allow` | `tb_findings`: a SIM with a live entry (a key, a prefix or a same-length range) has status `allowed` | Entries are written by the Fraud Lead for a verified PBX, dialler or call-centre line. Give each an expiry: it is the only enforcement, an expired entry stays in the sidecar and simply stops matching. Put the registry id in `reason`. The plan recommends at most 365 days. |
| `tb_watch` | `watch` | `tb_findings`: a watched SIM needs `persist_days` minus one high nights | An entry with an expiry of 24 hours or less applies without a second approver; longer ones are held for four-eyes. |
| `tb_block` | `block` | nothing yet | Create it for confirmed boxes; the barring Action Request and the partner list push of a later slice read it. Four-eyes recommended. |
| `tb_ranges` | `watch` | nothing yet | Prefix and range entries for own-network and suspect ranges; today the own-network signal reads the `number_plan` Reference. |

**Four-eyes** is the Space's approval policy, not a pack setting: `approval: {entity-list: {required: true}}` holds `members` and `retire` for a second approver (`ControlApiEntityListAssuranceTest.aListChangeIsHeldUnderFourEyesAndAppliedByASecondApprover`). Turn it on for `tb_allow`, because the allow list is the trust boundary: an entry must come from a person with the authority and a registry id, never from a field a dealer or subscriber controls.

**Feeding `tb_watch` from the score** is opt-in: after the list exists, add `watchList: {list: tb_watch, ttlHours: 24}` to the Risk Score `tb_sim` (the model cannot ship it, because a model naming a missing list is refused at save). Every SIM at or above the high threshold is then added for at most 24 hours. With the shipped `persist_days` of 2 this changes nothing for a SIM that was high last night (it already needs two nights); it matters for entries an analyst adds and when `persist_days` is raised to 3 or more.

## Shadow mode

Set `shadow: "true"` in `config/jobs/tb_findings_job.toon` (or pass `shadow` on a run) for the first weeks on real traffic. Scores and findings are written as usual, with `status = shadow` where an alert would have been raised, `alert_score` is 0 everywhere, and the `tb_allowed_line_changed` signal is silent too, so no Alert Rule has anything to raise and nobody is paged. Count the `shadow` findings a night against what your analysts would have accepted, calibrate the weights, the high threshold, `min_signals` and `persist_days`, then switch it off. The shipped value is `false`.

## Nightly order and failure

`tb_features` 03:00, `tb_factors` 03:10, `tb_risk_score` 03:20, `tb_findings` 03:30, each reading what the one before wrote. Run them in that order for any backfilled day. `tb_factors` is a snapshot of one window, so a day that failed upstream leaves it dated for the earlier day and `tb_findings` writes nothing for the later one (it reads only rows of its own window) rather than relabelling old rows. `tb_risk_score` fails closed above its entity cap (200,000 SIMs scored in one run by default; a typical day scores under 1 % of the SIMs).

## Sizing

Peak DuckDB memory by CDR rows in one day, on the default memory cap (3,246 MiB), measured on one synthetic day: 0.7 GiB at 1 million, 1.8 GiB at 5 million, 2.5 GiB at 7.5 million, 3.1 GiB at 10 million (97 % of the cap). **Above roughly 8 million rows a day, raise `processing.duckdb.memory_limit` to at least 4 GiB.** At 5 million rows the real Job ran in 6.8 s (12 cores). Slice B0 set the budget this must stay inside: a 5 million row day in 60 s on 4 cores, peak 2.5 GiB, no spill.

## Verify

- `GET /api/v1/jobs` lists `tb_features`, `tb_factors`, `tb_risk_score` and `tb_findings`; `GET /api/v1/alerts/rules` lists `tb_sim_high` and `tb_allowed_line_changed`.
- After the first day's run, the Dataset `tb_features` has one row per SIM with at least `min_events` MO voice calls; `missing` names every omitted signal. `tb_factors` has the same SIMs; `risk_scores_tb_sim_latest` has one score per SIM with its `factors`; `tb_findings` has a row for every SIM at or above 60 and every allowed SIM.
- The golden tests are `BypassFeatureGoldenTest` (module `inspecto`) and `TelcoBypassGoldenTest`, `BypassScoreTest`, `BypassAllowListTest` (module `inspecto-scoring`, which reads the same `BypassCorpus` through the core's test-jar).

## Safety

`msisdn` is classified `MSISDN`, `imsi` `IMSI`, `imei` `PII`, and `cell_id` and the cell coordinates `CONFIDENTIAL`; `tb_features` carries `msisdn`, a dealer id and example record ids only; `tb_factors` and `tb_findings` carry the SIM (classified `MSISDN`), numbers and the stored factors (signal values, no call detail beyond the three example record ids). The pack publishes nothing and never acts on a call: barring is a later, approval-gated Action Request.
