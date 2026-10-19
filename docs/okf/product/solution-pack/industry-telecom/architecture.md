---
type: Feature
title: "Telecom industry pack — architecture"
description: "How the telecom Industry pack relabels Link Analysis through a Space's domain profile."
tags: [industry-telecom, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom industry pack — architecture

Industry pack id `telecom` (`product-map/solution-pack/industry-telecom.toon`). It is classpath content of the [entity-store](../../module/entity-store/index.md) module, not a Space Template.

## Files

Under `platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs/telecom/`, indexed by `META-INF/inspecto/industry-packs.toon`:

| File | Carries |
|---|---|
| `industry-pack.toon` | manifest: name, description, `space_templates` (`telco-fraud`, `la-showcase`), `pattern_packs`, `profile` |
| `industry-pack-terms.toon` | 20 terms that relabel Link Analysis concepts (for example the node concept reads "Subscribers", the link concept "Calls") |
| `industry-pack-entity-types.toon` | 6 Entity Types: `subscriber`, `msisdn`, `imsi`, `imei`, `handset`, `cell`, each with a normaliser and a masking flag |
| `industry-pack-link-kinds.toon` | 6 link kinds: `voice`, `sms`, `forwarding`, `shared_device`, `shared_identity`, `shared_payment` |

## Profile

- Pattern packs: `forwarding-relay`, `telecom-forwarding-chain`, `telecom-payment-ring`, `telecom-subscription-ring`.
- Measure hints `duration`, `seconds`, `minutes`; time hints `start`, `_at$`, `time`, `date`; suggested tools `communities`, `explain`, `cohesion`.
- "Investigate a number" defaults: Entity Type `msisdn` over Dataset `telecom_links_dataset` (`a_msisdn` to `b_msisdn`, `link_kind`, `last_seen`, `events`), 30-day window, at least 1 event, fan-out cap 500, budget 10000, max degree 4.

## How it fits together

```
classpath packs --IndustryPacks.bundled()--> installed[] / refused[]
Space link-analysis.toon: domain_profile: telecom
   --> Link Analysis reads terms, Entity Types, link kinds, pattern packs, investigate defaults
```

Discovery is fail closed (`IndustryPacks.java`): a pack with a missing or unparseable file, or an id two jars declare, is refused whole. A Space naming a refused or absent pack reads the neutral vocabulary.

## Modules used

[entity-store](../../module/entity-store/index.md) · [la-core](../../module/la-core/index.md) · [la-api](../../module/la-api/index.md) · [Link Analysis add-on](../../addon/linkAnalysis/index.md). The ASN.1 CDR decoders are the separate [Telecom industry pack add-on](../../addon/telecomIndustryPack/index.md).
