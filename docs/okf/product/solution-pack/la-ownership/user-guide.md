---
type: Feature
title: "Link Analysis ownership showcase — user guide"
description: "A five-minute tour of the la-ownership Space and one exercise per planted story (hidden owner, sanctions two hops away, nominee director, circular ownership), each with a hint and an answer."
tags: [la-ownership, user-guide]
timestamp: 2026-11-07T00:00:00Z
---

# Link Analysis ownership showcase — user guide

A KYC and sanctions-compliance team at a bank checks who really owns and runs its corporate customers. This Space
holds a synthetic company register, the bank's customers and their payments, and an **invented** sanctions list. Four
stories are planted in it; each has a benign look-alike that must not be mistaken for it. Screens and controls are
the shared Link Analysis ones: see the [Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md).
Install: [admin guide](admin-guide-install.md).

Everything is synthetic. No company, person, address or sanctions-list name is real.

## What is in the Space

| Id starts with | Is a | Comes from |
|---|---|---|
| `PER-` | person in the register (name, birth year, nationality) | `person` |
| `CO-` | company (name, jurisdiction, incorporation date, registered address) | `company` |
| `CUS-` | the bank's customer, which is one company | `customer` |
| `SL-` | entry on the invented sanctions list (name, aliases, birth year, nationality) | `sanctions_list` |

One link Dataset, `ownership_links_dataset`, joins them with five kinds of link:

| Kind | From → to | Carries |
|---|---|---|
| `owns` | person or company → company | `percent`: the stake; `via`: the stake and the owned company's jurisdiction |
| `officer_of` | person → company | `via`: the role and the company's registered address |
| `customer_is` | customer → company | `via`: the onboarding date |
| `paid` | customer → company | `amount`: the payments summed; `via`: how many, and the last date |
| `name_match` | person → list entry | `score`: name similarity (0 to 1); `via`: the listed name it matched |

A `name_match` needs a name similarity of at least 0.90 (Jaro–Winkler on the lower-case letters of the name or of any
alias) **and** the same birth year **and** the same nationality. A person with a blank birth year or nationality is
never matched.

## Five-minute tour

1. Open **Link Analysis**. In **Start a graph**, pick the Dataset `ownership_links_dataset`, start from **One entity**
   and type `CUS-00528`. Choose **Start**. The customer appears with the company it is.
2. **Expand** (**G**) two or three degrees. A 50 % owner appears, then a person who owns 60 % of that owner, then a
   list entry `SL-…` joined by a `name_match`. Open the link: its `score` and `via` show the listed name it matched.
   This is the story *Sanctions two hops away*.
3. **File ▸ Open… ▸ Saved views** lists one view per story: `ownership_hidden_owner`, `ownership_sanctions_path`,
   `ownership_nominee_director`, `ownership_circular`. Each keeps only the kinds of link its story needs, and its
   description says what to look for. Open `ownership_nominee_director`.
4. Under **Preset queries**, ask **Reached by many** or **Reaching many** on the Dataset: one person stands far above
   everyone else. Choose **Draw these**.
5. Open `ownership_circular` and ask the preset **Short loops**: three companies own each other in a circle.

The exercises below take each story in turn. Try them blind first; the answers follow each hint.

## Exercises

### 1. Hidden owner

*Four of the bank's customers, `CUS-00349`, `CUS-00379`, `CUS-00591` and `CUS-01477`, each list three corporate
shareholders and no person above 25 %. Who really stands behind them, and how much of each do they control?*

- **Hint:** saved view `ownership_hidden_owner`. Start from one of the four customers and expand **upwards**
  (*Incoming* `owns`) one degree at a time. Read `percent` and the jurisdiction in `via` on each link.
- **Answer:** `PER-02104` (Rosa Vickers). She holds 24 % of `CO-05396` (Cyprus), which holds 24 % of `CO-04888`
  (British Virgin Islands), which holds 24 % of `CO-01476` (UAE), which holds 24 % of `CO-02850` (Cyprus), which holds
  24 % of each of three vehicles, `CO-03735` and `CO-04689` (British Virgin Islands) and `CO-04889` (UAE). Those three
  hold 24 + 24 + 13 = **61 %** of each of the four customer companies `CO-00864`, `CO-01036`, `CO-01566` and
  `CO-04474`. Five layers of companies, three jurisdictions, and every stake on the chain is 24 %: just under a 25 %
  disclosure threshold, so no register names her.
- **Not the answer:** `CO-04395` (Northgate Consolidated plc) also holds 61 % of four customers (`CUS-00025`,
  `CUS-00718`, `CUS-00945`, `CUS-01263`), but it is a listed company whose own shares are spread over 60 people at
  under 2 % each. Nobody stands behind it, and it has no link to the chain.

### 2. Sanctions two hops away

*Is any customer owned, directly or indirectly, by someone on the sanctions list?*

- **Hint:** saved view `ownership_sanctions_path`. There is exactly one `name_match` link in the Dataset; start from
  its list entry and walk down the `owns` links to a customer.
- **Answer:** `SL-0137`, listed as *Corvin Tammask* (alias *C. Tammask*), born 1971, nationality CY, matches
  `PER-03904` *Corvin Tamask* (one letter shorter; same birth year and nationality; score 0.986). `PER-03904` owns
  60 % of `CO-04431` (Cyprus), which owns 50 % of `CO-04313`, which is the customer **`CUS-00528`**. The listed person
  is two ownership hops above the customer's company.
- **Not the answer:** `PER-02226` *Anselm Pelivane* is as close by name to the listed *Anselm Pellivane* (`SL-0289`),
  but was born in 1983 (the list says 1959) and is Dutch (the list says MT). The match rule needs the same birth year
  and nationality, so there is no `name_match`, and his company `CO-04242` (customer `CUS-00642`) is clean. A
  name-only screen would raise a false alert here.

### 3. Nominee director

*Which person directs far more companies than anyone else, and what do those companies have in common?*

- **Hint:** saved view `ownership_nominee_director`; the preset **Reaching many** on `officer_of`. Read `via` on the
  links: it carries the role and the registered address.
- **Answer:** `PER-01592` (Dora Upton) is director of **120** companies, all incorporated in 2023–2024 and all
  registered at one formation-agent address, *Suite 4, 18 Quayside Lane, London*, written three ways (*Ste 4, 18
  Quayside Ln, London* and *Suite 4 18 Quayside Lane London*). Eight of them are the bank's customers. No other person
  directs more than 12 companies.
- **Not the answer:** `PER-00305` (Rosa Hartley), an accountant, directs four family firms, the Hartley companies
  `CO-01557`, `CO-02658`, `CO-03724` and `CO-05478`, each at its own address. Ordinary, and not linked to the nominee.

### 4. Circular ownership

*Do any companies own each other in a circle, and does money go round the same circle?*

- **Hint:** saved view `ownership_circular`; the preset **Short loops**, or the toolbox's **Cycles** on the drawn graph.
  Then look at the `paid` links between the same companies' customers.
- **Answer:** `CO-03975` (GB) owns 40 % of `CO-05331` (NL), which owns 35 % of `CO-04219` (LU), which owns 30 % of
  `CO-03975`. All three are customers (`CUS-00851`, `CUS-00363`, `CUS-00209`) and every month each pays the next one
  round the circle about 250 000, twelve payments on each link.
- **Not the answer:** `CO-00207` (Granite Capital Brothers Ltd) owns 100 % of three subsidiaries (`CO-02361`,
  `CO-03629`, `CO-05180`; customers `CUS-00329`, `CUS-00100`, `CUS-00390`) that each pay it a monthly fee. A clear
  parent at the top and money flowing one way: no circle.

## For testers

The answers above are the checklist. The generator's ground truth, `tools/gen-la-ownership-data.ground-truth.json`,
lists each story's entities and look-alikes; `tools/gen-la-ownership-data.test.mjs` checks the facts in the files and
`OwnershipLinksJobGoldenTest` checks that, in the link Dataset the Job builds, each story is one connected group and no
look-alike has a link to it.

## References

[Brochure](brochure.md) · [Install](admin-guide-install.md) · [Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md)
