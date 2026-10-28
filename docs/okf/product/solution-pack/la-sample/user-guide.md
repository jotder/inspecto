---
type: Feature
title: "Link Analysis sample — user guide"
description: "The first-run sample Space of Inspecto LA: invented transfers with a planted money-mule ring, loaded and run from one button."
tags: [la-sample, user-guide, onboarding]
timestamp: 2026-10-22T00:00:00Z
---

# Link Analysis sample — user guide

The `la-sample` Space Template is what **Try it with sample data** on the Inspecto LA *Getting started* page creates
(LA-ONBOARD-1 phase 3, [end user guide](../../module/la-core/user-guide-link-analysis.md) § Getting started). It runs on
every edition, Basic included: it carries no Alert Rules, no Jobs and no sign-in personas. The richer, Advanced-only
demo is [`la-showcase`](../la-showcase/index.md).

## What is in it

- One Pipeline `mule_transfers` (schema with a daily partition on `BOOKED_DATE`) and its Dataset
  `mule_transfers_dataset`, whose `PAYER_ACCOUNT` and `PAYEE_ACCOUNT` columns already say they hold accounts.
- Three days of **invented** transfers in `data/samples/mule_transfers` (the same synthetic corpus as `la-showcase`,
  `tools/gen-la-showcase-data.py`): 12 SMURF accounts structure cash into `MULE-HUB-01`, which passes it through
  `RELAY-01/02` to `OFFSHORE-77`; `SHELL-A` to `SHELL-D` circulate money in a loop.
- One saved view, `mule_layering_ring`, which draws the ring.

## Loading it

- From the app: *Try it with sample data* (Getting started) or *New from sample* (Space switcher). One call,
  `POST /spaces {template: "la-sample", loadSamples: true}`, creates the Space, copies the sample files into the
  Pipeline's inbox and runs it once; the app then opens Link Analysis on the saved view.
- By hand: create the Space from the template without `loadSamples`, then copy `data/samples/mule_transfers` into
  `data/inbox/mule_transfers`.

Synthetic data only.
