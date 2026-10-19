---
type: Plan
title: ASN.1 decoders — plan of record
description: What the new-generation ASN.1 decoder is required to do, what is built and proven, and what is left or deliberately refused.
resource: providers/asn-parser/asn-decoders/
tags: [module, asn-decoders, asn1, plan]
timestamp: 2026-10-19T00:00:00Z
---

# ASN.1 decoders — plan of record

## Requirement

Replace the legacy ASN.1 stack (`asn2` / `asn3` / `transformer2` under `providers/asn-parser/src/main/java`)
with a JDK-only, per-Pipeline (no static state) decoder that reproduces the legacy output on the operator
corpus. Design of record: `providers/asn-parser/docs/REDESIGN.md`.

## Built

- Phases 0-2 of the redesign: `asn-core`, `asn-schema`, `asn-plugin-api`, `asn-transform`,
  `asn-plugin-vendors`, `asn-facade`, the `legacy-code` harness and the `asn-golden` parity check
  (`providers/asn-parser/asn-decoders/README.md`).
- Parity on the golden corpus: structural parity on 16/16 data files, content parity 1.0 on 10/16
  (`providers/asn-parser/asn-decoders/README.md`, `providers/asn-parser/docs/MIGRATION_STATUS.md`).
- Consumed by the Inspecto engine through [telecom-asn1](../telecom-asn1/index.md) (MODULE-REORG-1 P7).

## Left (open)

- The corpus itself: BACKLOG `DATA-GOV-1` — the real carrier corpus is not archived out of band, so
  the corpus tests are opt-in and live code is tested only against synthetic input.
- BACKLOG `TEST-ENV-PG-ASN-UNRUN-1` — the `asn-*` modules cannot run offline on the shared profile
  (a missing surefire provider jar); a green reactor does not cover them.
- `providers/asn-parser/docs/MIGRATION_STATUS.md` lists the legacy configs that are present but unproven,
  and the six tag-map (`Schema.csv`) configs that need an `asn migrate` converter (not built).
- `ValueDecoderProvider` and `FramingProvider` SPIs from REDESIGN §4.5 are deliberately not defined yet:
  no corpus format needs them (`TransformFunctionProvider` javadoc).

## Refused / decided

- BACKLOG `LEGACY-ASN-SRC-TREE-UNBUILT-1` twins (operator, 2026-09-25): leave the superseded legacy
  classes in `providers/asn-parser/src/main/java` as they are; never delete the tree.
- `ASN1-DERIVE-NOT-IN-GRAMMAR` stays deferred by decision (BACKLOG "Parsing (Stage-1) residual deferrals").

## References

- [architecture](architecture.md) · [spec](spec.md) · [api](api.md)
- [parser-plugin-framework](../../../../archived-documents/plans-archive/parser-plugin-framework.md) (archived plan)
