---
type: Feature
title: "Link Analysis crypto laundering showcase — user guide"
description: "A five-minute tour of the la-crypto Space from its three saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-crypto, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis crypto laundering showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic. Every address uses an invented format, `syn1` followed
by 26 characters, that cannot match a real blockchain. The coins are invented too: `SYC`, and the stable token `SUSD` that
merchants are paid in. The five exchanges (Larkmoor Digital, Fernhill Coin, Ashgrove, Pebblebrook and Wrenfield), the mixer,
the company, the people and the bank accounts are invented. No real chain, exchange, mixer or person appears. Cash-out amounts
are fiat with no currency.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `wallet` | 10 875 | an address: `first_seen` (its first transfer) and the `label` an analytics provider gives it: `exchange` (an exchange's hot wallet or a customer's deposit address), `mixer`, `merchant` (a business), `hacked` (reported stolen from) or `unknown` |
| `transfer` | 44 559 (one file a month) | an on-chain transfer: `from_address`, `to_address`, `amount`, `asset` (`SYC`, or `SUSD` for merchant payments) and `tx_at` |
| `cluster_hint` | 2 722 | an address and the `cluster_id` the common-input heuristic gives it: addresses spent together in one transaction are taken to have one owner. An address never spent with another has no row |
| `exchange_account` | 3 154 | an account at one of the exchanges: the KYC person who holds it (`kyc_person_id`), the `deposit_address` the exchange gave it, and `opened_at` |
| `kyc_person` | 3 021 | a person an exchange identified: `name`, `birth_year` (blank for a few) and `country` |
| `cashout` | 4 635 (one file a month) | a withdrawal of fiat money from an exchange account to a `bank_account`, with `amount` and `cashout_at` |

The `crypto_links` Job runs after every load and writes **one link Dataset**, `crypto_links_dataset` (`src`, `dst`, `kind`,
`ts`, `amount`, `event_date`, `via`), with about 39 500 rows of six kinds. An id says what a node is: `syn1…` address,
`CL-` cluster, `XA-` exchange account, `KP-` KYC person, `BK-` bank account.

| Link kind | From → to | `via` says |
|---|---|---|
| `sent_to` | address → address | `transfers:`, `asset:`, `first:`, `last:`, both labels (`src_label:`, `dst_label:`), and `src_hop:` / `dst_hop:`, which are 1 when that address has the **peel shape**: exactly one transfer in and two out, to two addresses, both within 24 hours after the one in |
| `same_cluster` | address → cluster | the address `label:`, `cluster_size:`, and `cluster_exchange:` (how many of the cluster's addresses are labelled exchange) |
| `deposit_of` | deposit address → exchange account | `exchange:`, `deposits:`, `senders:` (how many addresses paid it) |
| `kyc_of` | exchange account → KYC person | `exchange:`, `name:`, `birth_year:`, `country:`, `accounts:` (how many accounts the person holds) |
| `cashed_out_to` | exchange account → bank account | `cashouts:`, first and last, and `accounts_to_this_bank:` (how many exchange accounts cash out to that bank account) |
| `mixer_exit` | an address that paid the mixer → an address the mixer paid | worked out, not recorded: every exit within 6 hours after an input, at 90 to 100 % of it, in SYC. `candidates:`, `matched:` (`yes` when within 120 minutes and at least 98 %), `minutes:`, `ratio:`, and the two transfer ids |

The derived `crypto_screen_links` Job runs each time the link Dataset is rebuilt and writes `crypto_screen_links_dataset`.
It has the same columns plus `screen`, the check that picked each link. Each saved view reads one screen, so every view
draws whole, under the canvas's 1 000 nodes:

| `screen` | Picks | Saved view |
|---|---|---|
| `peel_chain` | every `sent_to` link in or out of a peel-shaped address that is chained to another (a link with `src_hop:1` and `dst_hop:1`), the exchange accounts such a hop paid, and who holds them | `hack_peel_chain` |
| `mixer_hop` | every `mixer_exit` link, matched or a near miss; and for a matched exit, where its recipient deposited, the account and its KYC person | `mixer_hop_timing_match` |
| `converging_cash_out` | every cluster with no exchange-labelled address whose addresses paid the deposit addresses of 10 or more exchange accounts, with those payments, the accounts, their KYC persons and the bank accounts they cashed out to | `converging_cash_out` |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `hack_peel_chain`.** Two long chains of one-in, two-out addresses. One is a big tangle
   of twelve loops that start and end at one wallet and keep paying the same 25 addresses. The other is a single line of
   80 hops. It starts at a wallet labelled `hacked` (read `src_label:`) and drips small amounts into six exchange accounts.
2. **Open `mixer_hop_timing_match`.** A handful of `mixer_exit` links. Read `matched:`, `minutes:` and `ratio:` on each.
   Only one address has matched exits, three of them, and all three end at the same exchange account and person.
3. **Open `converging_cash_out`.** Two clusters each paid many exchange accounts. Follow the accounts to the bank
   accounts. One cluster's 60 accounts fan out to about 55 bank accounts. The other's 40 accounts come back together
   in three (`accounts_to_this_bank:`).
4. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (see the admin guide). The
   Pipelines load October 2026 and the Jobs rebuild the links. Reopen a view to see the new month: the payroll gets
   one more run.

## Exercises

Work blind first: each exercise has a hint, then the answer. The answers are not in the data.

### 1. Peel chain — where did the stolen coins go?

*Hint:* in `hack_peel_chain`, find the address whose `sent_to` link says `src_label:hacked`. Follow the change from hop to
hop, and look at where the small amounts go (`dst_label:exchange`, then `deposit_of` and `kyc_of`). Compare it with the
other chain.

**Answer:** on **2026-03-14 at 02:17** the wallet labelled hacked, **`syn15z47fslrxrnhuwm4dppk77s0z7`**, sent everything it
held, **412.5 SYC**, to **`syn1mdsja4svnugvd4chxrt2xnywxr`** in one transfer. That was the first of **80 hops**. Each hop
received one transfer and, 12 to 140 minutes later, sent two: a peel of 1.6 to 4.9 SYC to an exchange deposit address,
and the change to a fresh address. The last hop, `syn1lry5j5myhzevl8wnw2zvwpr7pc`, ran on 2026-03-18 at 07:48, and the
final change address, **`syn1sye3wp0tm9zdqmlwm60gha4uma`**, still holds about 170.6 SYC. The peels, about 241.9 SYC in
all, landed at **six deposit addresses, 13 or 14 peels each**. They belong to six accounts opened in February and March
2026 by six unrelated people: **`XA-03111` to `XA-03116`** at Fernhill Coin, Ashgrove, Pebblebrook and Wrenfield, held by
**`KP-02973` to `KP-02978`**. Each account cashed the peels out to its own bank account within days (see `cashout`).
**Not the story:** the company payroll wallet **`syn1g6x78slgxx5zne6cwpmes8j554`** (label `merchant`). On the last working
day of every month it starts a run: each of 25 hops pays one staff member's salary and passes the change to a fresh
address, and the last hop sends what is left back to the company. That is the same one-in, two-out shape, 300 hops over
the year, but it pays the **same 25 personal wallets every month**, none of them an exchange deposit address. The
company is funded from its own exchange account, and nothing in it comes from a hacked wallet.

### 2. Mixer hop — whose coins came out of the mixer, and where did they go?

*Hint:* in `mixer_hop_timing_match`, read `matched:`, `minutes:` and `ratio:` on every `mixer_exit` link, then follow the
matched exits.

**Answer:** **`syn1g2sua43rg5kfcjunpv6chu7rws`** collected seven payments (about 36 SYC) from seven different people between
2026-04-04 and 04-21: a ransom collection address. It paid the mixer three times: **11.8 SYC on 2026-05-14, 9.65 on 05-21
and 8.9 on 06-02**. Each time, the mixer paid one fresh address **53 to 67 minutes later, 98.2 to 98.9 % of the input**:
`syn18fz5ulg4gsp5wz7kggk6kut9k0`, `syn1dulktk57t5xr6phna92cfek8jh` and `syn1fmfje7xr5zct9wd702nnrdme4m` (`matched:yes`). A
few hours later each fresh address deposited everything at **one deposit address, `syn13n4rff586ywj4zmslcj0tr8xzz`, of
account `XA-03118` at Ashgrove Exchange**, held by **`KP-02980`, Olga Moreau**. She cashed it out to her bank account
on 2026-05-24 and 06-03. **Not the story:** two other mixer users each have one near miss. **`syn1zngjgrnpdxuwp8trqf4pddkppf`**
paid 5.2 SYC in, and 75 minutes later the mixer paid someone else 92.5 % of that: the timing fits but the amount is 7.5 %
short. **`syn1sfr9e266n86z4c8zd7k00q4uph`** paid 3.4 SYC in, and an exit of 99.1 % of that came 281 minutes later: the
amount fits but it is over 4 hours later. Both are `matched:no`, and so are the other near misses. Their own coins came out
a day or more later, split into pieces.

### 3. Converging cash-out — who is behind the 40 accounts?

*Hint:* in `converging_cash_out`, compare the two clusters: how many accounts each one paid, how many people hold them, and
how many bank accounts the money ends in (`accounts_to_this_bank:` on `cashed_out_to`).

**Answer:** cluster **`CL-01125`**, 30 addresses that the common-input heuristic ties to one owner, took in about 200
payments from about 150 people between February and June 2026. Between 2026-04-06 and 07-30 it paid **40 exchange
accounts, `XA-03119` to `XA-03158`**: 22 at Fernhill Coin Exchange and 18 at Pebblebrook Exchange. They were all opened in
February and March 2026, in **40 different names** from many countries (**`KP-02981` to `KP-03020`**), and nothing else
pays them. Every one of them cashes out to one of **three bank accounts, `BK-49804167`, `BK-77665088` and `BK-95284540`**
(13 or 14 accounts each, `accounts_to_this_bank:` 13 or 14): one controller behind 40 straw accounts. **Not the story:**
cluster **`CL-01126`** is an over-the-counter desk. Its 8 addresses buy on its own Larkmoor account and pay **60
customers' own exchange accounts**. Those customers also deposit from their own wallets, and each cashes out to a bank
account of their own (54 bank accounts, `accounts_to_this_bank:` 1, or 2 for someone with two accounts).

## Tester's checklist

The ground truth is `tools/gen-la-crypto-data.ground-truth.json` (repository only, not in the download). The golden tests
are `tools/gen-la-crypto-data.test.mjs` and `CryptoLinksJobGoldenTest`.

- [ ] All six Pipelines land with nothing rejected, quarantined or in errors. Every address matches `syn1` followed by 26
      characters.
- [ ] `crypto_links_dataset` has six kinds and one row per pair: one `deposit_of` and one `kyc_of` per exchange account, one
      `same_cluster` per hinted address. Exactly three `mixer_exit` links say `matched:yes`, all from
      `syn1g2sua43rg5kfcjunpv6chu7rws`.
- [ ] Each story's members form one connected group. Peel chain: the hacked wallet, the 80 hops, the final change address,
      the six deposit addresses, `XA-03111` to `XA-03116` and `KP-02973` to `KP-02978`. Mixer hop: the ransom address, the
      three exits, `syn13n4rff586ywj4zmslcj0tr8xzz`, `XA-03118`, `KP-02980`. Converging cash-out: `CL-01125`, its 30
      addresses, the 40 deposit addresses, `XA-03119` to `XA-03158`, `KP-02981` to `KP-03020` and the three bank accounts.
- [ ] No look-alike links to its story: the payroll wallet `syn1g6x78slgxx5zne6cwpmes8j554`; the near misses
      `syn1zngjgrnpdxuwp8trqf4pddkppf` and `syn1sfr9e266n86z4c8zd7k00q4uph`; the desk's cluster `CL-01126`.
- [ ] Each saved view holds its story whole, with its look-alike beside it but apart, and draws without "Result cut
      short". The largest view, `hack_peel_chain`, is about 785 rows and 426 nodes; the 80-hop chain is one thin line of
      100 nodes in it. October adds one payroll run and no story activity.
