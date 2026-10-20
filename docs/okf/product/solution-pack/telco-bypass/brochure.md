# Telecom bypass (SIM-box) — brochure

> **See which SIMs behave like part of a SIM-box, from the CDRs you already have, with every signal explained.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.telco-bypass` · status **preview** (signals only). Prices are not published here.*

## Who it is for

Fraud and revenue-assurance teams at operators and MVNOs that want bypass evidence from their own voice CDRs.

## The problems it solves

- A box SIM looks ordinary one call at a time: it is the shape of a whole day (many different numbers, short even calls, no inbound, no SMS or data, one cell, round the clock) that gives it away.
- A single yes-or-no rule scores a box that rotates SIMs or keeps a few calls alive as zero.
- Analysts re-derive the reasons by hand.

## What is included

- Four Pipelines: the voice CDR feed and three small Reference feeds.
- One daily Job that writes one row per SIM per day with twelve signals and a `box_like` flag; every threshold is configuration.
- Signals degrade one by one: a missing optional column or Reference is named, never an error.
- A synthetic corpus with planted boxes and the look-alikes that must stay silent, and a golden test that holds the Job to them.

## What it does not do

It is CDR-based only: no probes, signalling or test calls, and nothing is blocked or decided on a live call. A CDR pattern is evidence of consistency with a box, never proof. Scoring, lists, Alert Rules and the box-level Incident follow in later slices.

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md).

## Next step

Run it on an anonymised extract of your voice CDRs in a proof of value, and calibrate the thresholds in a shadow period.

## Read more

- Architecture: [architecture.md](architecture.md)
- The template manifest is `spaces/_templates/telco-bypass/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
