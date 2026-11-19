---
type: Feature
title: "Mobile money industry pack — user guide"
description: "What changes for an analyst when a Space uses the mobile-money domain profile."
tags: [industry-mobile-money, user-guide]
timestamp: 2026-10-19T00:00:00Z
item: solution-pack.industry-mobile-money
audience: internal
status: current
verified: 2026-11-18 last-edit
code: platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs/mobile-money
---

# Mobile money industry pack — user guide

The pack has no screens, Pipelines or dashboards of its own. Once a Space's domain profile is `mobile-money`, Link Analysis in that Space:

- names concepts in wallet and agent terms;
- normalises and masks wallet, agent, MSISDN and the pack's other identifiers (13 Entity Types; see [architecture](architecture-industry-mobile-money.md));
- offers the five money-flow pattern packs and the suggested tools scoring, pattern and flow;
- reads amount and booking-time columns using the pack's hints.

Day-to-day work happens in the paired Space Templates — follow their guides:

- [Link Analysis mobile money user guide](../la-mobile-money/user-guide-la-mobile-money.md)
- [Mobile money user guide](../mobile-money/user-guide-mobile-money.md)
