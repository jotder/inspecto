---
type: Plan
title: Telecom ASN.1 decoder — plan of record
description: What the telecom ASN.1 provider is required to do, what shipped, and what is left or deferred.
resource: providers/inspecto-telecom-asn1/
tags: [module, telecom-asn1, asn1, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom ASN.1 decoder — plan of record

## Requirement

EDITIONS row SP-PRS-06: an ASN.1 BER telecom CDR decoder as a Pipeline step (`parser.asn1.ber`), Standard
and above, as the Telecom industry pack (`docs/EDITIONS.md`). Design of record: the archived
[parser-plugin-framework](../../../../archived-documents/plans-archive/parser-plugin-framework.md) and
[parser-plugins-trust-design](../../../../archived-documents/plans-archive/parser-plugins-trust-design.md).

## Built

- `Asn1ParserPlugin` with grammar-less structural preview and grammar-bound preview; `Asn1RecordIngester`
  loading to Tables through `parsing.plugin`.
- Grammar as inline text or a stored path-jailed `.asn` file (operator decision 2026-09-23;
  `BUNDLE-ASN1-GRAMMAR-FILE-1`).
- Moved out of the engine into this optional module (MODULE-REORG-1 P7, 2026-10-07) and the module-owned
  package `com.gamma.telecom.asn1` (P3g, 2026-10-08).
- `provides.stepKinds` (BACKLOG `MODULE-REORG-P4-1`, closed 2026-10-09): a Pipeline using `parser.asn1.ber`
  on an install without the module is reported with `missingModule`.
- Derived columns and child rows (Parsing D9 slices 1-2) and their preview (`PARSING-PREVIEW-DERIVED-1`,
  2026-10-12); the `repeated_child_nested` corpus case shipped (`PARSING-CHILD-DERIVE-CORPUS-1`, closed 2026-10-18).
- Edition placement re-confirmed (BACKLOG `MODULE-REORG-REVIEW-1` (c), 2026-10-09).

## Left / deferred

- `ASN1-DERIVE-NOT-IN-GRAMMAR`, the out-of-process parser host (D8) and signer anchoring (T2) stay
  deferred by decision (BACKLOG "Parsing (Stage-1) residual deferrals"); the derive counters stay log-only.
- Segments are authored in TOON; a segments editor is not built (`Asn1ParserPlugin.ingesterClass` javadoc).
- Library-level open items (corpus, offline test run) are on the [asn-decoders plan](../asn-decoders/plan.md).

## References

- [architecture](architecture.md) · [spec](spec.md) · [engine spec-parser-plugins](../engine/spec-parser-plugins.md)
