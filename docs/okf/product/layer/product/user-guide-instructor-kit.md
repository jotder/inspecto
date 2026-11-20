---
type: Reference
title: "Instructor kit — Build, Ship, Manage workshops"
description: "For partners and system integrators who teach Inspecto: three workshop formats with timings, the room set-up, the slide outline, the lab guide, how to run the Lab checkers for a room, and the answer keys of Lab 1 and Lab 2."
item: layer.product
audience: training
status: current
verified: 2026-11-20 code-reviewed
---

# Instructor kit — Build, Ship, Manage workshops

This kit is for whoever teaches Inspecto in a room: a partner, a system integrator, or our own team. It holds no
teaching material of its own — the Handbook's Learning Paths, tutorials and Labs are the material — only what a
teacher needs around them: formats and timings, the room set-up, a slide outline, the lab guide and the answer keys.
It ships with the Labs, in the Preview and the training bundle, never in a sold edition's Help.

## 1. Workshop formats

| Workshop | Who | Length | Covers |
|---|---|---|---|
| **Builder day** | analysts and builders | 1 day | [builder path](user-guide-path-builder.md) stages 1–2: concepts, Tutorials 1–4, Labs 1 and 2 |
| **Operator half-day** | administrators | ½ day | [operator path](user-guide-path-operator.md): install, sign-in, Roles, backup, upgrade, audit — Tutorial *run it* |
| **Partner day** | partner developers | 1 day | [partner developer path](user-guide-path-partner.md): developer pages 5.1–5.6, Tutorial *ship it* |

Business users need no workshop: give them the [business path](user-guide-path-business.md) and half an hour on
Tutorial 4.

### Builder day — timings

| Time | Block | Material | Mode |
|---|---|---|---|
| 0:00 | Welcome, the worked example | slides 1–3 | talk |
| 0:20 | How the pieces fit | builder path stage 1, concept pages | talk + read |
| 1:00 | Tutorial 1 — your first Space | T1 | hands-on |
| 1:30 | Tutorial 2 — Pipelines and Expectations | T2 | hands-on |
| 2:15 | Break | | |
| 2:30 | **Lab 1** — trust the feeds | Lab 1 | hands-on, graded |
| 3:00 | Lab 1 debrief | answer key §5 | talk |
| 3:15 | Lunch | | |
| 4:15 | Tutorial 3 — detect and respond | T3 | hands-on |
| 5:00 | **Lab 2** — exposure per customer | Lab 2 | hands-on, graded |
| 5:30 | Lab 2 debrief | answer key §6 | talk |
| 5:45 | Tutorial 4 — the Dashboard | T4 | hands-on |
| 6:15 | Self-checks 1 and 2, questions | builder path | talk |
| 6:45 | Close | | |

Tutorial 3, step 4 changes the Case workflow and needs an administrator: do it once on the projector, or give every
learner the `admin` Role as well.

## 2. Room set-up

- **One install for the room**, Standard or higher; the Preview or training bundle carries the Labs. For a room
  without an identity provider, use a demo build (Advanced or Preview) and its Demo Users.
- **One Demo User per learner**, each with the Roles `admin` and `power`: `power` holds `canAuthorAlertRules`, which
  creating a Space from the Order-to-cash template needs — `admin` alone is refused.
- **One Space per learner**, created by the learner in Tutorial 1 from the **Order-to-cash assurance** template with its
  sample files loaded. Give each a name the learner owns (`o2c-<name>`) so the checker can be pointed at it.
- **Before the day:** create one Space yourself, run every tutorial's checks against it
  (`node tools/run-handbook-checks.mjs --base-url <url> --page tutorial_first_space --space <id>`, then the next
  tutorials) and both Labs' checkers after doing them — a green run is the proof the install is ready.

## 3. Slide outline

Twelve slides; every fact on them comes from a Handbook page, so they never drift from the product.

1. The worked example: orders against payments, with planted mistakes to find (Tutorials 1 and 2).
2. The lifecycle: Understand → Build → Ship → Manage → Develop.
3. Today's path and the two Labs.
4. Space, Dataset, Pipeline, Collector (concept pages).
5. Expectations: well-formed is not right (Tutorial 2).
6. Lab 1 brief.
7. Reconciliation and Breaks (Tutorial 3, step 1).
8. Alert Rule → Alert → Incident → Case (concept *Alert, Incident and Case*).
9. Lab 2 brief.
10. The Dashboard for the business user (Tutorial 4).
11. What comes next: Ship (partner path), Manage (operator path).
12. Where to look: the Handbook, the screen reference, the Link Analysis training videos.

## 4. Lab guide — running the checkers for the room

A Lab's checker is the `check` blocks at the end of its page. The runner signs in as the persona the page names
(`builder`) and reads the Space given with `--space`:

```
node tools/run-handbook-checks.mjs --base-url <url> --page lab_trust_the_feeds --space o2c-<name>
node tools/run-handbook-checks.mjs --base-url <url> --page lab_exposure_per_customer --space o2c-<name>
```

- On a demo build the persona signs in through the Demo Auth exchange as the Demo User named `builder`; elsewhere, set
  `INSPECTO_HANDBOOK_TOKEN_BUILDER` to a token, or pass a credentials file. That user must be able to read the
  learner's Space: a Space a learner creates is closed to others, but a user with the `admin` Role reads it (proven on
  a demo build on 2026-11-20 — `builder` graded a learner's closed Space). So give your own Demo User the name
  `builder` and the Roles `admin` and `power`.
- Each Lab grades in seconds. A failed step names the expectation it could not meet and what it found — read it out
  in the debrief; it is usually the lesson.
- Lab 1's checker runs each of the learner's three Expectations once more; that changes nothing (a still-failing check
  opens no second Incident). Lab 2's checker only reads.
- Re-running a tutorial's checks consumes state (Tutorial 3 closes a Case per run); grade Labs, not tutorials, during
  the day.

## 5. Answer key — Lab 1, Trust the feeds

| Expectation id | Attach to | Check | Column | Setting | Result |
|---|---|---|---|---|---|
| `lab_orders_customer_present` | Pipeline **orders** | Non-null | `customer_id` | — | passed, 0 violations |
| `lab_payments_method_known` | Pipeline **payments** | Regex | `method` | Pattern `^(card\|transfer\|cheque)$` | passed, 0 violations |
| `lab_orders_over_800` | Pipeline **orders** | Range | `amount` | Maximum `800` | **failed, 16 violations** |

Answers to the questions: (1) the first two pass, `lab_orders_over_800` fails; (2) **16** orders are above 800;
(3) the failure opened one **Incident** of severity **MAJOR**, as Tutorial 2's failures did.

Common mistakes: attaching to a Job instead of the Pipeline; a pattern without `^…$`, which a value such as
`card-refund` would pass; a **Minimum** of 800 instead of a **Maximum** (it fails with 105 violations — every order
row below 800).

## 6. Answer key — Lab 2, Exposure per customer

The rule: **Watch** a Dataset Measure, Dataset **o2c_open_items**, Measure `sum(unmatched_value)`, Comparator
**≥**, Threshold **300**, Severity **Critical**, **Incident per** `customer_id`, Rule id `lab_customer_exposure`.

| Customer | Total unmatched value | From | Incident? |
|---|---|---|---|
| C-007 | 620.00 | O-1022 short paid (200.00) + O-1041 unpaid (420.00) | yes — the largest |
| C-016 | 480.00 | O-1017 paid -240 against 240 | yes |
| C-011 | 310.00 | O-9001, a payment for an order that does not exist | yes |
| C-010 | 180.00 | O-1050, the duplicate order | no |
| C-004 | 135.00 | O-1088 unpaid | no |
| C-015 | 55.00 | O-9002, an orphan payment | no |
| C-014 | 4.50 | O-1064 short paid | no |

The total of the column is 1,784.50 — the Dashboard's KPI in Tutorial 4.

Answers: (1) C-007, C-016 and C-011, as above; (2) **one** — the Incident is per customer now, so C-007's two orders
add up to one exposure of 620; (3) C-010's only difference is 180, under 300; (4) **C-011** — its 310 is the orphan
payment O-9001. Step 3: accept the **C-007** Incident; it moves to *Diagnosing* and is assigned to the learner.

Common mistakes: Severity Warning (Alerts fire, no Incident opens); **Incident per** left empty (one Incident for the
whole Dataset); `count` instead of `sum(unmatched_value)`; **>** 300 instead of **≥** (same answer here — say why it
could matter); evaluating before the open-items Job ran (nothing fires).
