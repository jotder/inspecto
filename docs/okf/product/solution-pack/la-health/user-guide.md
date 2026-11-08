---
type: Feature
title: "Link Analysis provider billing fraud showcase — user guide"
description: "A five-minute tour of the la-health Space from its four saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-health, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis provider billing fraud showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic: names, practice names, streets and bank
accounts (`SYN-…`) are invented, the procedure codes are this showcase's own (not any real code set), map positions
are random points around a city centre, and only city names are real.

## What is in the Space

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `address` | 12 860 | an address (a member's home, a provider's premises, an owner's address): street, city, map position (blank for about 3 % of homes) |
| `owner` | 1 307 | an owner from the provider enrolment file: name, `owner_type` (`person` or `organisation`), address |
| `provider` | 1 500 | a provider: name, specialty, premises address, bank account |
| `provider_owner` | 1 585 | one provider and one of its owners |
| `member` | 20 000 | an insured member: birth year, home address, `died_on` (blank while alive) |
| `claim_line` | about 81 000 (one file a month) | a billed line: provider, member, service date, procedure code, level 1–5 (office visits only), billed, paid, `received_on`, and for a reversal (negative amounts) the line it `reverses` |
| `admission` | 2 796 (one file a month) | a hospital stay: member, hospital, `from_date`, `to_date` |
| `referral` | about 14 400 (one file a month) | a referral: referring practice, provider referred to, member, date |
| `prescription` | about 29 700 (one file a month) | a filled prescription: prescriber, pharmacy, member, drug class, quantity, `paid_cash` (Y/N), fill date |

Procedure codes: `OFV` office visit, `SPV` specialist visit, `PHY` physiotherapy session, `LAB`, `IMG-XR` /
`IMG-CT` / `IMG-MR` imaging, `INP` hospital stay.

The `health_links` Job runs after every load and writes **one link Dataset**, `health_links_dataset` (`src`,
`dst`, `kind`, `ts`, `amount`, `event_date`, `via`), about 55 000 rows of six kinds. An id says what a node is:
`PR-` provider, `OW-` owner, `M-` member, `AD-` address.

| Link kind | From → to | `via` says |
|---|---|---|
| `treated` | provider → member | `lines:`, `months:`, first and last service date; `in_admission:` open lines dated inside the member's stay at another hospital (with `admitted_at:`), `after_death:` open lines dated after the member's death (with `died:`), `corrected:` such lines that were reversed (with `corrected_within_days:`); `amount` = paid, reversed lines left out |
| `referred_to` | practice → provider | `referrals:`, `of_all_referrals:` (the practice's total), `share:`, `members:` |
| `owned_by` | provider → owner | `provider:` specialty and `city:`; for a provider that bills office visits, `office_visits:`, `top_level_share:` (visits billed at level 5), `all_providers_share:`, `same_age_mix_share:` (the share its patients' ages predict, ages counted in 2026), `ratio_to_same_age_mix:` and `patients_80_plus:` |
| `prescribed` | prescriber → member | `scripts:`, `strong_opioid_scripts:`, `strong_opioid_qty:`, `cash_scripts:`, `distance_km:` from the member's home |
| `filled_at` | member → pharmacy | `fills:`, `strong_opioid_fills:`, `cash_fills:`, `distance_km:` |
| `lives_at` | owner (a person) → home | `owners_at_address:` |

The derived `health_screen_links` Job runs each time the link Dataset is rebuilt and writes
`health_screen_links_dataset`: the same columns plus `screen`, the check that picked each link. Each saved view
reads one screen, so every view draws whole, far under the canvas's 1 000 nodes:

| `screen` | Picks | Saved view |
|---|---|---|
| `referral_concentration` | every practice that sends 60 % or more of 20 or more referrals to one provider (that link only), the owners of both ends, and any home two of those owners share | `health_referral_concentration` |
| `phantom_billing` | every provider–member link with a line dated inside a stay at another hospital or after the member's death, open or reversed | `health_phantom_billing` |
| `pill_mill` | every prescriber with 40 or more strong-opioid patients, those patients, and the pharmacies where they filled strong opioids | `health_pill_mill` |
| `upcoding` | every provider with 150 or more office visits billed at level 5 at least 1.75 times as often as all providers, its owner, and everything that owner owns | `health_upcoding` |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `health_referral_concentration`.** Nine practices. Seven sit in Scarborough and
   all point at **Saltmere Bay Hospital**, the only hospital in town, owned by a public trust. Two York practices
   point at **Emberlea Imaging Centre** instead, and the owner of the centre and the owner of one practice meet again
   at one home.
2. **Open `health_phantom_billing`.** Seventeen providers. Fifteen bill one patient each, and one clinic bills
   fifteen: each of those lines was dated inside a hospital stay by mistake and reversed within days
   (`corrected:1`). One more clinic bills forty patients with nothing reversed: `in_admission:` and `after_death:`
   are open on every link.
3. **Open `health_pill_mill`.** Two prescribers with as many strong-opioid patients. One centre's patients live
   within 12 km, are insured and fill at a pharmacy near home. The other prescriber's patients live 61–127 km
   away, pay cash, take large quantities and all fill at the same two pharmacies.
4. **Open `health_upcoding`.** Every provider that bills level 5 far more often than the rest. The elderly-care
   clinics are here because all their patients are over 80: their `ratio_to_same_age_mix:` is about 1. Three
   urgent-care clinics in Hull, Lincoln and York bill about four times what their patients' ages predict, and they
   share one owner.
5. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (admin guide). The
   Pipelines land October 2026 and the Jobs rebuild the links; reopen a view to see them.

## Exercises

Work blind first: each has a hint, then the answer. The answers are not in the data.

### 1. Referral kickbacks — whose referrals are bought?

*Hint:* in `health_referral_concentration`, every practice sends most of its referrals to one place. Look at who
owns each end of the link, and where those owners live.

**Answer:** **`PR-1426` Brackenmoor Surgery** (68 of 79 referrals, `share:0.86`) and **`PR-1392` Kilnworth Medical
Practice** (182 of 216, `share:0.84`), both in York, send their referrals to **`PR-1170` Emberlea Imaging Centre**.
The centre is owned by **`OW-0434` Nell Dunbar**, who lives at **`AD-05150` (135 Sedge Close, York)** with
**`OW-1270` Xena Rowntree**, the owner of Brackenmoor Surgery: the owners share a home. Kilnworth's owner,
`OW-1286` Sela Ellery, has no such tie; the referral pattern alone joins that practice. **Not the story:**
**`PR-0828` Elder Grove Medical Practice** sends 38 of 44 referrals (`share:0.86`) to **`PR-1129` Saltmere Bay
Hospital**, the only hospital in Scarborough, owned by **`OW-0263` Saltmere Health Trust**; the other six
Scarborough practices in the view do the same (`share:0.68`–`0.82`). Concentration alone is not the finding; the
shared home is.

### 2. Phantom billing — who bills patients who could not have been there?

*Hint:* in `health_phantom_billing`, read `in_admission:`, `after_death:` and `corrected:` on the `treated` links of
each provider.

**Answer:** **`PR-1241` Sedgefold Physiotherapy** (Hull) bills **40 patients**: **89 sessions** for **28 patients**
on days they were in a hospital elsewhere (`admitted_at:` names it; for example `M-02544`, `in_admission:2`,
`admitted_at:PR-0199`), and **70 sessions** for **12 patients** after they died between January and May 2026 (for
example `M-04712`, died 2026-02-26, `after_death:7`). None of it is reversed. **Not the story:**
**`PR-0632` Willowmere Physiotherapy** (Leeds) has 15 patients with one session each dated inside a hospital stay;
every one was reversed in full within 2–6 days of being received (`corrected:1`, `corrected_within_days:`) and
re-billed with the right date after discharge. Fifteen more providers in the view made the same error once and fixed
it the same way.

### 3. Pill mill — whose strong-opioid patients are not what they seem?

*Hint:* in `health_pill_mill`, compare the two prescribers' patients: `distance_km:`, `cash_scripts:` and
`strong_opioid_qty:` on `prescribed`, then the pharmacies they fill at.

**Answer:** **`PR-1150` Flintcross Pain Clinic** (Sheffield) prescribes strong opioids to **70 patients** who live
**61–127 km** away (20 in York, 20 in Hull, 18 in Lincoln, 12 in Scarborough): 514 prescriptions averaging 135
tablets (the usual strong-opioid quantity is 56), **462 of them paid in cash**, all filled at **`PR-1060` Sedge
Square Chemist** and **`PR-0594` Larch Lane Pharmacy** in Sheffield. **Not the story:** **`PR-0031` Rowanbrook
Oncology Centre** (Leeds) has **60 strong-opioid patients**, all within 12 km, insured (5 cash prescriptions of
360), filling at the one or two Leeds pharmacies nearest their homes.

### 4. Upcoding — which clinics bill above their patients?

*Hint:* in `health_upcoding`, compare `top_level_share:` with `same_age_mix_share:` (read
`ratio_to_same_age_mix:`), then look at the owners.

**Answer:** **`PR-0173` Lark Field Walk-in Clinic** (Hull), **`PR-1117` Brook Grove Walk-in Clinic** (Lincoln) and
**`PR-1251` Quarry House Walk-in Clinic** (York) bill level 5 on 24–29 % of their office visits, where their
patients' ages predict 6–7 % (`ratio_to_same_age_mix:` 3.8, 3.9 and 4.1). Three clinics in three cities look
unrelated; all three are owned by **`OW-0441` Ivo Calloway**, who owns nothing else. **Not the story:**
**`PR-0277` Aldermoor Elderly Care Clinic** (Lincoln, owner `OW-0422` Fenna Gilchrist) bills level 5 on 28 % of its
visits, as high as the three, but every patient is over 80 (`patients_80_plus:1.0`) and their ages predict 25 %
(`ratio_to_same_age_mix:1.14`). The other elderly-care clinics in the view have the same shape.

## Tester's checklist

The ground truth is `tools/gen-la-health-data.ground-truth.json` (repository only, not in the download); the golden
tests are `tools/gen-la-health-data.test.mjs` and `HealthLinksJobGoldenTest`.

- [ ] All nine Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `health_links_dataset` has six kinds and one row per pair; one `owned_by` per provider and owner, one
      `lives_at` per owner who is a person; only `PR-1241`'s links have `in_admission:` or `after_death:` above 0.
- [ ] Each story's members form one connected group: `PR-1426`, `PR-1392`, `PR-1170`, their owners `OW-1270`,
      `OW-1286`, `OW-0434` and the home `AD-05150`; `PR-1241` and its 40 patients; `PR-1150`, `PR-1060`, `PR-0594`
      and the 70 patients; `OW-0441` and its three clinics.
- [ ] No look-alike links to its story: `PR-0828`; `PR-0632`; `PR-0031`; `PR-0277`.
- [ ] Each saved view holds its story whole and its look-alike beside it but apart, and draws without "Result cut
      short" (the largest, `health_pill_mill`, is about 390 rows and 150 nodes).
