---
type: Feature
title: "Link Analysis utility showcase — user guide"
description: "A five-minute tour of the la-utility Space from its three saved views, then one exercise per planted story with the steps to find it, the answer, and the benign look-alike that must not link to it, plus the tester's checklist."
tags: [la-utility, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis utility showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic: holder names, street names and bank account
ids (`BA-…`) are invented, and coordinates are city centres (Leicester, Coventry, Nottingham) plus jitter.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `premise` | 1 000 | a supplied premise: address, city, coordinates, tariff (`residential`, `commercial`), meter (`smart`, `prepaid`), the transformer that supplies it |
| `customer_account` | 1 112 | an account at a premise: holder, opened and closed dates, the bank account it pays from (blank for cash), arrears |
| `meter_read` | 365 000 (one file a month) | a premise's registered kWh for one day, and a tamper flag (`0`, `1`, blank) |
| `transformer_read` | 4 380 (one file a month) | the kWh one of the 12 metered transformers supplied that day (40 transformers in all) |
| `field_visit` | 551 | a visit to a premise by a contractor (`C-…`): when and why |
| `service_order` | 473 | a `connect`, `disconnect`, `reconnect` or `meter_swap` order and the contractor who closed it |
| `prepaid_vend` | 10 577 | a prepaid token sale at a vending point (`V-…`): money received, kWh credited |
| `payment` | 7 368 | a bill payment by a credit account, and the bank account it came from |

The `utility_links` Job runs after every load and writes **one link Dataset**, `utility_links_dataset`
(`src`, `dst`, `kind`, `ts`, `amount`, `kwh`, `event_date`, `via`), about 4 300 rows, one per pair. An id says
what a node is: `PRM-` premise, `TX-` transformer, `ACC-` account, `BA-` bank account, `C-` contractor, `V-`
vending point.

| Link kind | From → to | `amount` · `via` |
|---|---|---|
| `supplied_by` | premise → transformer | `kwh` = the year's meter reads |
| `account_at` | account → premise | arrears · holder, opened and closed dates |
| `paid_from` | account → bank account | payments summed · number of payments |
| `visited_by` | premise → contractor | · each visit and its type |
| `order_closed_by` | premise → contractor | · each order and its type |
| `vended_by` | premise → vending point | money · sales, kWh per unit of money, night-time sales (22:00–05:00) |

Two more Jobs derive from it: `transformer_loss` (per metered transformer and month: kWh supplied, kWh metered,
`loss_pct`, `loss_band`) and `utility_bank_links` (the account links the churn view needs, so that view fits one
canvas).

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `meter_tampering_contractor`.** Premises linked to the contractors who visited
   them or closed their orders. The busiest contractors (`C-27`, `C-21`) are not the story; volume alone says
   nothing here. Seed `C-14` and expand once: most of its premises sit under just two transformers.
2. **Open `account_churn_by_bank_account`.** Bank accounts, the accounts that pay from them, and their premises. Most
   groups are small: one premise, an old and a new account, each with its own bank account (or none, for cash). Two
   bank accounts are hubs.
3. **Open `prepaid_token_fraud_vendor`.** Prepaid premises linked to the vending points that sold them tokens. Compare
   `V-01` and `V-08`: both are busy; read `kWh per unit of money` and `night` in `via`.
4. **Open the `transformer_loss` Dataset** (Discover). Sort by `loss_pct`: two transformers stand out. On Standard
   and above the saved Geo View `utility_transformer_loss_map` shows the same on a map.
5. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (admin guide). The
   Pipelines land October 2026 and the Jobs rebuild the links and the loss; reopen a view to see them.

## Exercises

Work blind first: each has the steps to find it, then the answer. The answers are not in the data.

### 1. Meter tampering — which contractor, which premises, where does the energy go?

*Steps:* start from the energy: in `transformer_loss`, find the transformers whose `loss_band` turns
`loss over threshold`. In `meter_read`, find the premises under them whose reads fall sharply part-way through the
year. Seed those premises in `meter_tampering_contractor` and expand once: they share one contractor. Compare each
premise's reads in the weeks before and after that contractor's visit day (in `via`).

**Answer:** contractor **`C-14`** visited **32 premises**; **22** of them register **40–70 % less** within two weeks
of its visit (visits between 2025-12-06 and 2026-06-28; six meters flag `tamper_flag = 1` on the visit day). All 22
are supplied by **`TX-007`** (Leicester) or **`TX-019`** (Coventry), both metered: the transformers send out as much
as before, so their loss rises from 6.2 % to **17.9 %** and from 5.1 % to **24.7 %**. The other ten metered transformers stay
under 7 %. **Not the story:** the holiday home **`PRM-0773`** (38 Rowan Close, Nottingham) uses almost nothing from
November to February every year, but no contractor has ever visited it and its transformer is not `TX-007` or
`TX-019`; it has no link to `C-14`.

### 2. Illegal reconnection — who put the supply back without payment?

*Steps:* in `meter_tampering_contractor`, seed `C-22` (three of its visits were joint with `C-14`) and read the
`order_closed_by` links: `via` names `reconnect:ORD-…`. For those premises, check `meter_read` after the disconnect
order and `payment` before the reconnect.

**Answer:** **15 premises** were disconnected for arrears between 2026-01-12 and 2026-07-16, kept registering
consumption as if the supply were on, and their **reconnect** orders were closed by **`C-22`** 5–25 days later with
**no payment** in between; they have paid nothing since. `C-22` joined `C-14` on three of the tampering visits
(`PRM-0145`, `PRM-0467`, `PRM-0765`). **Not the story:** ten premises disconnected for arrears in the same months
(for example `PRM-0013`, `PRM-0196`, `PRM-0303`) read **zero** while off, paid their arrears, and were reconnected
a day or two later by other contractors (`C-08`, `C-13`, `C-18`, `C-20`, `C-25`, `C-27`); none links to `C-22`.

### 3. Debt-dodging account churn — which bank account keeps paying for new names?

*Steps:* in `account_churn_by_bank_account`, find the bank account with the most `paid_from` links and expand twice (to its
accounts, then to their premises). Read `account_at` `via`: holder, opened, closed; and `amount`: the arrears.

**Answer:** bank account **`BA-40000032`** pays **18 accounts at 6 premises** (`PRM-0032`, `PRM-0037`, `PRM-0100`,
`PRM-0283`, `PRM-0367`, `PRM-0705`). At each premise an account closed with arrears and a new one opened within
30 days under another name, twice: **12 accounts closed with arrears**, 6 open now. At `PRM-0032`, for example,
`Eben R. Yelland` closed on 2025-12-01 owing 266.32, `Vida G. Lindqvist` opened on 2025-12-27 and closed on
2026-05-01 owing 573.81, and `Maeve A. Yelland` opened on 2026-05-05. **Not the story:** a genuine change of tenant
(for example `PRM-0256`, `PRM-0337`, `PRM-0473`, `PRM-0718`, `PRM-0907`) closes with the final bill settled and
the new tenant pays from a new bank account; each stays its own small group and none shares a bank account with
`BA-40000032`.

### 4. Commercial load on a residential tariff — which homes run like a business?

*Steps:* in `account_churn_by_bank_account`, find the second hub bank account and expand to its premises. Open `meter_read`
for them: compare a winter and a summer month, and a weekday with a weekend, against their street.

**Answer:** bank account **`BA-40000075`** pays the accounts of **6 residential premises** (`PRM-0085`, `PRM-0099`,
`PRM-0175`, `PRM-0281`, `PRM-0555`, `PRM-0748`, on six different streets) whose load is **flat day and night, winter
and summer**, at **3–5×** their street's median: the signature of a grow house. **Not the story:** **`PRM-0682`**,
a home with a heat pump and an electric car, also uses far more than its street, but its winter use is several times its summer use and
rises at weekends, and it pays from its own bank account (`BA-40040149`), so it is not in this group at all.

### 5. Prepaid token fraud — which vending point gives energy away?

*Steps:* in `prepaid_token_fraud_vendor`, compare the two busiest vending points. Read `via` on their `vended_by` links:
`kwh_per_money` and `night`.

**Answer:** **`V-08`** (Coventry) sells to 53 premises; **40** of them, from 2026-01-05, got tokens crediting about
**13 kWh per unit of money** where the price gives 4, in **718 sales**, **73 %** of them between 22:00 and 05:00.
Those 40 buy from `V-08` only. **Not the story:** **`V-01`**, a supermarket in Leicester, makes the most sales
(2 518, to 76 premises) but always at the list price (4 kWh per unit of money) and in the daytime; it shares no
premise with `V-08`.

## Tester's checklist

The ground truth is `tools/gen-la-utility-data.ground-truth.json` (repository only, not in the download); the
golden tests are `tools/gen-la-utility-data.test.mjs` and `UtilityLinksJobGoldenTest`.

- [ ] All eight Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `utility_links_dataset` has six kinds and one row per pair; no `paid_from` link to a placeholder bank account
      (`UNKNOWN`, `00000000`).
- [ ] Each story's members form one connected group: the 22 premises with `C-14`, `TX-007` and `TX-019`; the 15
      premises with `C-22`; the 18 accounts and 6 premises with `BA-40000032`; the 6 premises and their accounts
      with `BA-40000075`; the 40 premises with `V-08`.
- [ ] No look-alike links to its story: `PRM-0773`; the ten genuine reconnections; the five genuine tenant
      changes and their bank accounts; `PRM-0682`, its account and `BA-40040149`; `V-01`.
- [ ] Each saved view draws without "Result cut short" (under 1 000 nodes and 4 000 rows) and shows its stories.
- [ ] Only `TX-007` and `TX-019` leave the normal loss band in `transformer_loss`.
