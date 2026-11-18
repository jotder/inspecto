---
type: Feature
title: "Telecom ASN.1 decoder — features"
description: "The telecom ASN.1 decoder adds the `parser.asn1.ber` step kind for BER-encoded telecom CDRs."
tags: [telecom-asn1, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom ASN.1 decoder — features

**Product Map Item:** [`module.telecom-asn1`](../../../../../product-map/module/telecom-asn1.toon) · **Status:** ga · **Availability:** Optional module, staged in Advanced (`offerings/advanced.toon`; Standard and up until 2026-11-17, DT-12). Add-on: [telecomIndustryPack](../../addon/telecomIndustryPack/index.md).

## What it is

The telecom ASN.1 decoder adds the `parser.asn1.ber` step kind for BER-encoded telecom CDRs. It plugs into the parser family as a `ParserPlugin`.

## Who benefits

Telecom operators ingesting switch and network CDRs directly.

## Key capabilities

- ASN.1 BER telecom CDR decoder as a Pipeline step — [addon telecomIndustryPack feature-db](../../addon/telecomIndustryPack/feature-db.md)

## Related hubs

[telecomIndustryPack](../../addon/telecomIndustryPack/index.md) · [asn-decoders](../asn-decoders/index.md) · [etl](../elt/index.md)

More in this hub: [feature-db](feature-db.md)
