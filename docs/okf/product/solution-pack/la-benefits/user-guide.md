---
type: Feature
title: "Link Analysis benefits and tax fraud showcase — user guide"
description: "A five-minute tour of the la-benefits Space from its four saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-benefits, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis benefits and tax fraud showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic: names, streets, phones (Ofcom drama ranges)
and bank accounts (`SYN-…`) are invented, map positions are random points around a city centre, and only city names
are real. The 200-a-month limit on permitted earnings is this showcase's own figure, not any country's.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `address` | 6 500 | an address: street, city, map position (blank for about 3 %) |
| `person` | 12 000 | a person: name, birth year, home address id (`AD-…`), phone (blank when not given) |
| `household` | 5 258 | a household declaration: address, who declared it (blank for a care home), members declared |
| `claim` | 7 534 | a benefit claim: claimant, benefit type, `start_date`, `end_date` (blank while it runs), monthly amount, the account it is paid into, the overpayment recovered after a death |
| `death_register` | 300 (one file a month) | a death notice: person, `died_on`, `notified_on` |
| `payroll` | about 36 500 (one file a month) | an employer's payroll line: employer, person, pay period, wages |
| `tax_return` | about 7 700 (one file a month) | a tax return: taxpayer, preparer (blank when filed alone), refund, refund account, day filed |

The `benefits_links` Job runs after every load and writes **one link Dataset**, `benefits_links_dataset`
(`src`, `dst`, `kind`, `ts`, `amount`, `event_date`, `via`), about 39 000 rows of six kinds. An id says what a node
is: `CIT-` person, `CL-` claim, `AD-` address, `EMP-` employer, `P-` tax preparer, `SYN-` bank account.

| Link kind | From → to | `via` says |
|---|---|---|
| `claims` | person → claim | benefit type, start and end (`amount` = monthly amount) |
| `paid_into` | claim → account | benefit and monthly amount; for a claimant on the death register paid after the death: `died:`, `notified:`, `paid_after_death_days:` (`ongoing` while still paid) and `recovered:` |
| `lives_at` | person → address | `residents:`, `claimants:`, `households_declared:` and `declared_members:` at that address |
| `employed_by` | person → employer | months, first and last pay period, `jobseeker_months:` paid while on Jobseeker benefit, `over_limit_months:` above 200, `wages_while_claiming:` |
| `prepared_by` | taxpayer → preparer | returns and refund |
| `refund_to` | taxpayer → account | returns and the preparer (or `self`); `amount` = the refund |

The derived `benefits_screen_links` Job runs each time the link Dataset is rebuilt and writes
`benefits_screen_links_dataset`: the same columns plus `screen`, the integrity check that picked each link. Each
saved view reads one screen, so every view draws whole, under the canvas's 1 000 nodes:

| `screen` | Picks | Saved view |
|---|---|---|
| `shared_address` | every address with 8 or more claimants, its residents, their claims and the accounts those are paid into | `benefits_shared_addresses` |
| `after_death` | every account that took a claim paid 30 or more days after the claimant's death (or still paid), every claim paid into those accounts, and its claimant | `benefits_paid_after_death` |
| `work_while_claiming` | everyone paid by an employer while on Jobseeker benefit, and their Jobseeker claims | `benefits_work_while_claiming` |
| `refund_concentration` | the clients of every preparer who filed 60 or more returns, and every refund account shared by 3 or more taxpayers | `benefits_refund_concentration` |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `benefits_shared_addresses`.** Four addresses. Three are care homes, each one
   big star: one declaration, 24–31 residents, every resident's claim paid into their own account. The fourth looks
   like a house, yet 14 people live there, each declaring a household of one, and their claims meet again in only
   three accounts.
2. **Open `benefits_paid_after_death`.** About 50 deaths. Most are small stars: a pension paid for a month or two
   after a late death notice, `recovered:` set. One cluster is different: nine pensions with
   `paid_after_death_days:ongoing`, three accounts, and one living person whose own three claims land in the same
   accounts.
3. **Open `benefits_work_while_claiming`.** About 80 people paid while on Jobseeker benefit. Most are single links
   with `over_limit_months:0` (permitted part-time earnings) or `over_limit_months:1` (they started work in the
   month their claim closed). Two employers each hold a crowd of claimants paid full wages for months.
4. **Open `benefits_refund_concentration`.** Two preparers. `P-03` has over 240 clients, and their refunds fan out
   to as many accounts. `P-12` has 80 clients whose refunds, several times the usual size, converge on six
   accounts.
5. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (admin guide). The
   Pipelines land October 2026 and the Jobs rebuild the links; reopen a view to see them.

## Exercises

Work blind first: each has a hint, then the answer. The answers are not in the data.

### 1. Address farm — which address is not what it claims to be?

*Hint:* in `benefits_shared_addresses`, compare `households_declared:` with `residents:` on the `lives_at` links
of each address, then follow the claims to the accounts.

**Answer:** **`AD-03141` (100 Cobble Lane, Bristol)** has **14 residents, all claimants, and 14 household
declarations of one person each**. They carry only **5 surnames** (Varga, Dunbar, Oddie, Kettering, Zamora), their
claims (mostly Housing) started between May 2025 and June 2026, and all 14 are paid into **three accounts:
`SYN-57707034`, `SYN-19642862` and `SYN-47766627`**, which nobody else uses. **Not the story:** the care homes
**`AD-00412` Linden Court (31 residents), `AD-02718` Harbour View (24) and `AD-05077` Orchard House (27)** each make
**one** declaration for all their residents, and every resident's pension is paid into their own account.

### 2. Payments to the deceased — who is collecting the pensions of the dead?

*Hint:* in `benefits_paid_after_death`, find the `paid_into` links whose `via` reads
`paid_after_death_days:ongoing`, then look at what else is paid into the same accounts.

**Answer:** **nine pensioners** who died between November 2025 and April 2026 (`CIT-00329`, `CIT-01771`,
`CIT-01984`, `CIT-05915`, `CIT-06772`, `CIT-06904`, `CIT-08314`, `CIT-11396`, `CIT-11490`) are on the death
register within two weeks of each death, yet their pensions **were never stopped**. They are paid into **three
accounts, `SYN-13618314`, `SYN-96993903` and `SYN-23138817`**, which also receive the three claims (Disability,
Carer, Housing) of one living person, **`CIT-08276` (Flynn Prentice)**. **Not the story:** **`CIT-05289` (Piers
Ellery)** died on 2026-02-09 and the notice arrived **40 days** later; the pension stopped two days after the
notice (`paid_after_death_days:42`) and the overpayment of about 1 115 was recovered. Most of the other deaths in
the view are the same honest shape, only shorter.

### 3. Undeclared work — which jobseekers are working, and for whom?

*Hint:* in `benefits_work_while_claiming`, sort out the `employed_by` links whose `over_limit_months:` is more than
1, then look at which employers they point to.

**Answer:** **20 people on Jobseeker benefit** are paid **1 100–2 000 a month for 6 months or more** while
claiming, by **`EMP-0217` and `EMP-0388`**; four of them (`CIT-03707`, `CIT-04588`, `CIT-08132`, `CIT-09860`) are
on both payrolls. Nobody else in the data is over the limit for more than one month. **Not the story:**
**`CIT-02003` (Isla Oddie)** has been paid by `EMP-0441` for 11 months while claiming, but always under the
200-a-month limit (`over_limit_months:0`); about 30 more jobseekers have permitted earnings like hers, and about 30
people were paid in the month their claim closed because they had just started work (`over_limit_months:1`).

### 4. Tax-preparer ring — whose refunds are going where?

*Hint:* in `benefits_refund_concentration`, compare the two preparers: how many accounts do their clients'
refunds go to, and how large are they?

**Answer:** preparer **`P-12`** filed **80 returns** in January and February 2026, each claiming a refund of
**1 800–3 400** (the usual refund is about 330), and every refund is paid into one of **six accounts:
`SYN-48624341`, `SYN-42781233`, `SYN-46751014`, `SYN-28564011`, `SYN-61269335` and `SYN-58935733`**, none of them
a taxpayer's own. **Not the story:** **`P-03`** is the largest preparer (250 clients, 244 of them in the landed
months), but each client's refund goes to that client's own account (or a joint account with their partner), and
the refunds are the usual size. A few genuine refunds elsewhere are larger than the ring's: size alone does not find
it.

## Tester's checklist

The ground truth is `tools/gen-la-benefits-data.ground-truth.json` (repository only, not in the download); the
golden tests are `tools/gen-la-benefits-data.test.mjs` and `BenefitsLinksJobGoldenTest`.

- [ ] All seven Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `benefits_links_dataset` has six kinds and one row per pair; one `lives_at` per person and one `paid_into` per
      claim; only the nine story claims read `paid_after_death_days:ongoing`.
- [ ] Each story's members form one connected group: `AD-03141`, its 14 claimants, their claims and the 3 accounts;
      the 9 deceased, `CIT-08276`, their 12 claims and the 3 accounts; `EMP-0217`, `EMP-0388`, the 20 claimants and
      their Jobseeker claims; `P-12`, its 80 taxpayers and the 6 accounts.
- [ ] No look-alike links to its story: `AD-00412`, `AD-02718`, `AD-05077`; `CIT-05289`; `CIT-02003`; `P-03`.
- [ ] Each saved view holds its story whole and its look-alike beside it, and draws without "Result cut short"
      (the largest, `benefits_refund_concentration`, is about 600 rows and 520 nodes; a little more with October in).
