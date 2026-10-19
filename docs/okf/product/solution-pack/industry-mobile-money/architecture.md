---
type: Feature
title: "Mobile money industry pack — architecture"
description: "How the mobile-money Industry pack relabels Link Analysis through a Space's domain profile."
tags: [industry-mobile-money, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Mobile money industry pack — architecture

Industry pack id `mobile-money` (`product-map/solution-pack/industry-mobile-money.toon`). It is classpath content of the [entity-store](../../module/entity-store/index.md) module, not a Space Template.

## Files

Under `platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs/mobile-money/`, indexed by `META-INF/inspecto/industry-packs.toon`:

| File | Carries |
|---|---|
| `industry-pack.toon` | manifest: name, description, `space_templates` (`la-mobile-money`, `mobile-money`), `pattern_packs`, `profile` |
| `industry-pack-terms.toon` | 16 terms that relabel Link Analysis concepts |
| `industry-pack-entity-types.toon` | 3 Entity Types: `wallet`, `agent`, `msisdn`, each with a normaliser and a masking flag |
| `industry-pack-link-kinds.toon` | 6 link kinds: `p2p`, `cash_in`, `cash_out`, `merchant_pay`, `shared_device`, `shared_id` |

## Profile

- Pattern packs: `pass-through`, `layering-chain`, `inbound-collector`, `circular-flow`, `structuring`.
- Measure hints `amount`, `value`, `total`; time hints `booked`, `posted`, `_at$`, `time`; suggested tools `scoring`, `pattern`, `flow`.

## How it fits together

```
classpath packs --IndustryPacks.bundled()--> installed[] / refused[]
Space link-analysis.toon: domain_profile: mobile-money
   --> Link Analysis reads terms, Entity Types, link kinds, pattern packs, hints
```

Discovery is fail closed (`IndustryPacks.java`): a pack with a missing or unparseable file, or an id two jars declare, is refused whole. A Space naming a refused or absent pack reads the neutral vocabulary.

## Modules used

[entity-store](../../module/entity-store/index.md) · [la-core](../../module/la-core/index.md) · [la-api](../../module/la-api/index.md) · [Link Analysis add-on](../../addon/linkAnalysis/index.md).
