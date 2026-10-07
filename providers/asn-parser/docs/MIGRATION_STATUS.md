# Migration status — legacy configs → new stack

Phase 4 tail of [REDESIGN.md](REDESIGN.md). What the new stack is *proven* against, what is
merely *present*, and what must happen before `asn2`/`asn3`/`transformer2` can be deleted.

## The config inventory (as of 2026-07-29)

`config/` holds **30 `*.asn` grammars** and **26 transform JSONs** (23 decoder `*_tx`-style;
`sdpElementTree.json` and `txTempl.json` are support/templates). They are not 1:1 — the tree
has accumulated backups, spec-reference grammars and stray copies:

| Group | Grammars | Notes |
|---|---|---|
| `rtdms/mtna/*` (occ, ccn, sdp, huwIMS, huwMsc, gsn, ericGgsn) | 10 | busiest operator; `huwMsc` alone has 3 grammars (`huwMsc`, `2980-gmsc`, a stray `aftel-ims` copy) |
| `rtdms/aftel/ims`, `rtdms/roshan/*`, `rtdms/awcc/*`, `rtdms/etisalat` | 11 | includes 2 spec-reference grammars (`CDR parameter description 3GPP TS 32.298…`, `SGSN V 15.4.0`) that are documentation, not deployables |
| `zain/sudan/{ims,pgw}` | 2 | |
| `generic/{nrtrde,tap,hua_ims}` | 7 | roaming formats + backup variants |

Structural mismatches worth knowing before migrating: **4 directories have a grammar but no
transform config at all** (`rtdms/etisalat`, `rtdms/mtna/ericGgsn`, `rtdms/roshan/sgsn`,
`zain/sudan/pgw`), and **6 have a transform config but no grammar** (`generic/eric_air`,
`generic/eric_ccn`, `generic/eric_msc`, `generic/hua_gsn`, `generic/hua_mgcf`,
`rtdms/roshan/roshan_msc_huawei`) — those six decode via the legacy `Schema.csv` tag map, not
ASN.1, so they are out of scope for the grammar path and need the `asn migrate` tag-map
converter (not yet built).

## Proven: the 9 corpus cases

Only pairings with **real sample data** can be proven, and those are exactly the golden-corpus
cases. All 9 are green: 16/16 data files at structural parity, 10/16 at content parity 1.0, row
counts identical on all 8 row-bearing files (see `corpus/PARITY.md`, `asn-decoders/README.md`).

| Case | Grammar | Transform |
|---|---|---|
| mtna_occ | `rtdms/mtna/occ/mtnOCC.asn` | `mtn_occ_tx.json` |
| mtna_ccn | `rtdms/mtna/ccn/mtnCCN.asn` | `ccn_tx.json` |
| mtna_sdp | `rtdms/mtna/sdp/sdp.asn` | `sdp_tx.json` |
| mtna_huwims | `rtdms/mtna/huwIMS/huwIMS.asn` | `huwIMS.json` |
| mtna_huwmsc | `rtdms/mtna/huwMsc/2980-gmsc.asn` | `huwMsc.json` |
| aftel_ims | `rtdms/aftel/ims/aftelIMS.asn` | `ims_tx.json` |
| zain_ims | `zain/sudan/ims/huwIMS.asn` | `zain_ims_tx2.json` |
| zain_pgw | `zain/sudan/pgw/huwSgsn.asn` | — (no tx in tree) |
| awcc_sgsn | `rtdms/roshan/sgsn_roshan/gsn.asn` | `ggsn_tx.json` |

## Not proven — and why that blocks deletion

The remaining ~21 grammars have **no sample data in the repo**, so there is nothing to measure
parity against. Compiling a grammar is not evidence it decodes real files correctly: every
single parity bug found in Phases 1–3 (padding bytes, union-SET roots, wrap-around tags,
skip-only framing) was invisible at compile time and only showed up against bytes.

**Therefore `asn2`/`asn3`/`transformer2` cannot be deleted on the strength of this work.** The
gate is per-format, not global:

1. Obtain a sample file for the format (one is enough to start; the corpus captures ≤N records).
2. Add a `GoldenCapture.CaseSpec` — the (grammar, root, skipLines, framing, tx) tuple, which
   **exists nowhere in `config/`** and must be recovered from the deployment.
3. Capture the legacy baseline, run `ParityCheck`, drive the residuals to a deliberate deviation
   or 1.0, and pin a floor in `ParityCheckTest`.

Until a format has been through that loop, the legacy path is the only validated one for it.
Recommended sequencing: the 4 grammar-only directories are the cheapest wins *if* data appears
(no transform semantics to match); the `generic/*` tag-map formats are the most expensive
(they need the converter first).

Retire the two spec-reference grammars from `config/` rather than migrating them — they are
3GPP documentation that was never a deployable config.

## Deprecation plan

- **Now**: legacy stays the production path; the new stack ships behind the corpus harness.
  `asn-golden` is the only module that still depends on legacy code, and only to *measure* it —
  no vendor logic runs through legacy any more (Phase 4 retired `LegacyFunctionBridge`).
- **Per format**: as each format passes the loop above, cut it over individually.
- **Deletion**: only once every format still in service has passed. Track remaining formats in
  this file; do not delete `asn2`/`asn3`/`transformer2` while any row here is unproven.
