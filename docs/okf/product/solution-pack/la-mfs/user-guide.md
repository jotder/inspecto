---
type: Feature
title: "Link Analysis mobile financial services fraud showcase — user guide"
description: "A five-minute tour of the la-mfs Space from its three saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-mfs, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis mobile financial services fraud showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic. Wallet numbers (MSISDNs) are in the unassigned `+999`
range. Agent, merchant and area names are invented, and so are the bank accounts. No real operator, agent, merchant or bank
appears. Amounts have no currency. Wallet anti-money-laundering (structuring, mule chains, a device farm) is in the
[`la-mobile-money`](../la-mobile-money/index.md) Space, not here, and the two Spaces share no data or ids.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `agent` | 300 | an agent (a shop that cashes customers in and out and registers wallets): name, `area`, the `super_agent_id` it reports to, its `float_account` |
| `merchant` | 400 | a merchant that takes wallet payments: name, `category`, `opened_at`, the bank account it settles to (`settlement_account`) |
| `wallet` | 7 908 | a wallet: `msisdn`, `kyc_level` (1 to 3), `opened_at`, and the agent that registered it (`registered_by_agent`, blank for about a third, who registered in the app) |
| `wallet_txn` | about 101 000 (one file a month) | a transaction: `type` (`cash_in` agent to wallet, `cash_out` wallet to agent, `p2p`, `merchant_pay`, and `settlement`, a merchant's sweep to its bank account), `amount`, `txn_at`, and the handset that approved it (`device_id`, blank for a cash-in, which the agent's till starts) |
| `sim_event` | about 1 150 (one file a month) | a SIM swap or port-out on a wallet's number: `event_at`, `channel` (`store` or `call_centre`) and the `staff_login` that did it |
| `device_login` | about 69 000 (one file a month) | the first time in a day a wallet's app was used on a handset (`login_at`) |
| `agent_commission` | 3 600 (one file a month) | an agent's month: cash-ins and cash-outs handled, and the commission earned (0.40 a transaction up to 50, 0.80 up to 200, 1.50 above) |

The `mfs_links` Job runs after every load and writes **one link Dataset**, `mfs_links_dataset` (`src`, `dst`, `kind`,
`ts`, `amount`, `event_date`, `via`), with about 47 000 rows of seven kinds. An id says what a node is: `WL-` wallet,
`A-` agent, `MR-` merchant, `HS-` handset, `S-` staff login, `BA-` bank account.

| Link kind | From → to | `via` says |
|---|---|---|
| `registered_by` | wallet → agent | `kyc:`, the wallet's `cash_ins:`, how many of them were at the agent that registered it (`cash_ins_at_registrar:`), `cash_outs:`, `p2p_sent:`, `p2p_received:`, `merchant_pays:` |
| `paid` | wallet → wallet or merchant | `type:`, `payments:`, `first:`, `last:`, and `within_60m_of_cash_in:` (payments made within 60 minutes after the wallet was cashed in) |
| `cashed_in_at` | wallet → agent | `cash_ins:`, `days:`, `per_day:`, `average:`, and `merchant_pay_within_60m:` (merchant payments that followed a cash-in there within 60 minutes) |
| `cashed_out_at` | wallet → agent | `cash_outs:`, first and last |
| `logged_in_on` | wallet → handset | `login_days:`, first and last |
| `swapped_by` | wallet → staff login | `msisdn:`, `channel:`, `swaps:`, `new_device_minutes:` (minutes from the swap to the first login on a handset the wallet never used), `out_within_2h:` (paid out within 2 hours after the swap; also the link's `amount`), `out_txns_within_2h:`, `login_max_swaps_10d:` (the most swaps that login made in any 10 days) |
| `settles_to` | merchant → bank account | `category:`, `merchant_opened:`, `settlements:`, `settlement_days:`, `first_settlement:` |

The derived `mfs_screen_links` Job runs each time the link Dataset is rebuilt and writes `mfs_screen_links_dataset`. It
has the same columns plus `screen`, the fraud check that picked each link. Each saved view reads one screen, so every
view draws whole, under the canvas's 1 000 nodes:

| `screen` | Picks | Saved view |
|---|---|---|
| `swap_burst` | every SIM swap by a staff login that made 15 or more swaps within 10 days; the handsets those wallets were first used on within 24 hours after the swap; the wallets or merchants they first paid within 24 hours after it; and where any payee of two or more of those wallets cashed out | `mfs_sim_swap_bursts` |
| `split_deposit` | every cash-in at an agent that handled 1 000 or more cash-ins averaging under 25, and that agent's registration of those wallets | `mfs_split_deposits` |
| `merchant_quick_pay` | every payment to a merchant paid by 30 or more wallets within 60 minutes after a cash-in, the merchant's settlement account, and the agents where those quick payers were cashed in | `mfs_merchant_quick_pay` |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `mfs_sim_swap_bursts`.** Two staff logins swapped SIMs in bursts. One is a store,
   `S-72`, with about 160 customers. Most of them moved to a new handset within a day, and nobody's money moved. The other
   is a call-centre login, `S-41`. Behind it, handsets are shared between customers, payments converge on a few wallets,
   and two agents sit at the end.
2. **Open `mfs_split_deposits`.** Two agents took 1 000 or more small cash-ins. Compare `per_day:` on their
   `cashed_in_at` links, and what the wallets do besides (`via` on `registered_by`).
3. **Open `mfs_merchant_quick_pay`.** Two merchants are often paid straight after a cash-in. One has 200 shoppers paying
   all year. The other is paid by 60 wallets that do nothing else, funded at three agents, and its `settles_to` link says
   when it opened.
4. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (see the admin guide). The
   Pipelines load October 2026 and the Jobs rebuild the links. Reopen a view to see the new month.

## Exercises

Work blind first: each exercise has a hint, then the answer. The answers are not in the data.

### 1. SIM-swap wallet takeover — whose wallets were emptied, and through whom?

*Hint:* in `mfs_sim_swap_bursts`, read `channel:`, `new_device_minutes:` and `out_within_2h:` on the `swapped_by` links of
each login. Then follow the handsets and the payments.

**Answer:** call-centre login **`S-41`** swapped the SIMs of **30 customers in 10 days, 2026-06-08 to 06-17**. Within the
hour (`new_device_minutes:` 60 or less) each wallet was logged into from one of **six handsets it had never used,
`HS-08913` to `HS-08918`**, each shared by five victims. Within 2 hours it was drained (`out_within_2h:` 460 to 1 840) by
P2P payments to **five mule wallets, `WL-07936` to `WL-07940`**. The mules were opened in the app in May 2026 and paid by
nobody else. They cashed everything out the same day at two agents, **`A-088` (Willow Agency) and `A-093` (Pebble Cash
Hub), both in Eastfield**. Each customer got the number back at a store 1 to 3 days later; those swaps are not in the
view. `S-41` also made ten ordinary swaps in other months; those wallets lost nothing. **Not the story:** store login
**`S-72`** in Riverside made **160 swaps**, 110 of them during a SIM upgrade drive in March 2026. About three in four of
those customers moved to a new handset of their own within a day, which is what people do with a new SIM. Nobody's
money moved (`out_within_2h:0.0`), and no handset or payee is shared with anyone else.

### 2. Agent split deposits — which agent is earning commission on deposits it splits?

*Hint:* in `mfs_split_deposits`, compare the two agents' `cashed_in_at` links (`cash_ins:`, `days:`, `per_day:`) and the
`registered_by` links: who registered the wallets, and what else do they do?

**Answer:** agent **`A-052` (Hawthorn Express, Brickfield)** registered **120 wallets itself** between December 2025 and
January 2026, all at KYC level 1. Every cash-in into them, 6 267 in all averaging about 17, happened at `A-052`. On each
visit a deposit of 60 to 180 was split into 4 to 15 cash-ins of 5 to 25 a few minutes apart (`per_day:` 5 or more). A
commission is paid on each one, so `A-052` earned about 2 900 in commission over the year, against a median of about 50
an agent (see `agent_commission`). The wallets do little else: an occasional cash-out at `A-052`, never a merchant
payment (`merchant_pays:0`), and at most one P2P. **Not the story:** **`A-131` (Old Market Traders Cash Point)** took
2 779 small cash-ins too, from **250 market traders** depositing a day's takings: about one deposit a visit
(`per_day:` 1.3 or less). Most traders registered elsewhere or in the app, and they pay merchants and each other.

### 3. Merchant cash-out collusion — which merchant is a cash-out channel?

*Hint:* in `mfs_merchant_quick_pay`, look at `within_60m_of_cash_in:` against `payments:` on each merchant's `paid` links,
where the payers were cashed in, and `merchant_opened:` and `settlement_days:` on `settles_to`.

**Answer:** merchant **`MR-077` (Brightline Electronics Wholesale)** opened on **2026-09-07**. From 09-09 it was paid by
**60 new wallets, `WL-07941` to `WL-08000`**, each opened in the three weeks before at one of **three agents in Station
Road: `A-207` (Kestrel Cash Point), `A-208` (Bramble Mobile Cash) and `A-219` (Kestrel Pay Corner)**. Every payment came
3 to 25 minutes after a cash-in of 150 to 480 at the wallet's own agent, and passed on 95 to 100 % of it. The wallets do
nothing else. `MR-077` settles **every day** (22 settlements on 22 days) to one account, **`BA-4194142`**. **Not the
story:** **`MR-214` (Hillcrest Family Grocers)**, open since 2023, is paid by **200 regular shoppers all year**. About 50
of them sometimes cash in at an agent next door and pay at once, but most of their visits are not after a cash-in. It
settles weekly, on Mondays, to `BA-3034434`. None of its shoppers is cashed in at the three Station Road agents.

## Tester's checklist

The ground truth is `tools/gen-la-mfs-data.ground-truth.json` (repository only, not in the download). The golden tests
are `tools/gen-la-mfs-data.test.mjs` and `MfsLinksJobGoldenTest`.

- [ ] All seven Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `mfs_links_dataset` has seven kinds and one row per pair: one `registered_by` per wallet an agent registered and
      one `settles_to` per merchant. Only `S-41` and `S-72` have `login_max_swaps_10d:` of 15 or more.
- [ ] Each story's members form one connected group. SIM-swap takeover: `S-41`, the 30 victims, `HS-08913` to
      `HS-08918`, the five mules and `A-088`, `A-093`. Split deposits: `A-052` and its 120 wallets. Merchant cash-out:
      `MR-077`, `BA-4194142`, `A-207`, `A-208`, `A-219` and the 60 wallets.
- [ ] No look-alike links to its story: `S-72`; `A-131`; `MR-214`.
- [ ] Each saved view holds its story whole, with its look-alike beside it but apart, and draws without "Result cut
      short". The largest view, `mfs_split_deposits`, is about 610 rows and 370 nodes. October adds no story activity.
