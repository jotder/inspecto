# Telecom bypass (SIM-box) — brochure

> **See which SIMs behave like part of a SIM-box, from the CDRs you already have, with every signal and every decision explained.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.telco-bypass` · status **preview** (signals, score, lists, the SIM Alert and the box Incident). Prices are not published here.*

## Who it is for

Fraud and revenue-assurance teams at operators and MVNOs that want bypass evidence from their own voice CDRs.

## The problems it solves

- A box SIM looks ordinary one call at a time: it is the shape of a whole day (many different numbers, short even calls, no inbound, no SMS or data, one cell, round the clock) that gives it away.
- A single yes-or-no rule scores a box that rotates SIMs or keeps a few calls alive as zero, and cannot say which signal fired.
- A busy fraud team stops trusting a pack that pages on every PBX and call centre: here a verified line goes on an allow list that expires, a high score needs enough independent signals, and an alert needs more than one night.
- Analysts re-derive the reasons by hand: every score carries the contribution of each signal.

## What is included

- Four Pipelines: the voice CDR feed and three small Reference feeds.
- A nightly chain of six Jobs: twelve signals per SIM, the score inputs, an explainable 0 to 100 Risk Score with protective factors, the findings, and the boxes: SIMs that share a device, a cell or an activation batch become one suspected box.
- One Incident and one Case per suspected box instead of an alert storm of a thousand SIMs, a Link Analysis view of its members, and a runbook.
- An allow list with an expiry and a watch list, a lower-severity Alert when a verified line changes shape, and a shadow mode that measures precision before anyone is paged.
- Signals degrade one by one: a missing optional column or Reference is named, never an error.
- A synthetic corpus with planted boxes and the look-alikes that must stay silent (call centre, alarm line, couriers, a new heavy user, a dense building, a visiting roamer), and golden tests that hold the pack to each planted outcome and reason.

## What it does not do

It is CDR-based only: no probes, signalling or test calls, and nothing is blocked or decided on a live call. A CDR pattern is evidence of consistency with a box, never proof; a quiet PBX looks the same, so the pack asks for a verification step. A box whose SIMs share no device, cell or activation batch cannot be linked from CDRs. The 7-day device signals and an Anomaly Model factor follow in later slices.

## How it fits with the Editions

Needs [Standard](../../edition/standard/brochure.md), [Advanced](../../edition/advanced/brochure.md) or [Preview](../../edition/preview/brochure.md).

## Next step

Run it on an anonymised extract of your voice CDRs in a proof of value, in shadow mode, and calibrate the weights and thresholds against what your analysts would have accepted.

## Read more

- Architecture: [architecture.md](architecture.md)
- The template manifest is `spaces/_templates/telco-bypass/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
