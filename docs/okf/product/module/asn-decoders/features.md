---
type: Feature
title: "ASN.1 decoders — features"
description: "The ASN.1 decoders are the decoding library behind Inspecto's ASN.1 parsing."
tags: [asn-decoders, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# ASN.1 decoders — features

**Product Map Item:** [`module.asn-decoders`](../../../../../product-map/module/asn-decoders.toon) · **Status:** ga · **Availability:** Provider module under `providers/asn-parser`; not listed by name in `offerings/*.toon` (see [telecom-asn1](../telecom-asn1/index.md) for the Edition-staged telecom decoder).

## What it is

The ASN.1 decoders are the decoding library behind Inspecto's ASN.1 parsing. They let encoded telecom and network records be read as ordinary rows.

## What this enables

- Customers whose records arrive ASN.1-encoded can ingest them without a separate conversion step; the engine's processor catalog lists the ASN.1 PER / XER / DER decoder — [engine feature-db](../engine/feature-db.md)

## Related hubs

[telecom-asn1](../../module/telecom-asn1/index.md) · [telecomIndustryPack](../../addon/telecomIndustryPack/index.md) · [engine](../../module/engine/index.md)
