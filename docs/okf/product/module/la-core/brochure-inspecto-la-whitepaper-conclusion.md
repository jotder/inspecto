---
type: Brochure
title: Inspecto LA whitepaper — Conclusion and architectural recommendation
description: Part 6 of 6 of Inspecto LA whitepaper: · Conclusion & architectural recommendation
---

# Inspecto LA whitepaper — Conclusion and architectural recommendation

Part 6 of 6 of [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · previous: [Performance, sizing, limits and the claims register](brochure-inspecto-la-whitepaper-performance-claims.md).

# 8 · Conclusion & architectural recommendation

Inspecto LA redefines the economics and operational posture of graph intelligence for organisations that can run a capable single host. By collapsing the legacy stack — extractors, an external graph database, visualisation servers and unversioned desktop clients — into one in-process runtime, an organisation gains three advantages, each with its limits stated above:

1. **Sovereignty by construction**: sensitive records stay on the customer's own host, behind keyed masking and four-eyes controls (Standard and above), and the standard distribution cannot call hosted AI endpoints. Masking is strong where tested and honestly incomplete elsewhere (Section 5.2.1).
2. **Operational simplicity**: no dedicated graph cluster or bespoke glue scripts, with the stated price that it is a single-host engine measured to $10^8$ edges, and that PostgreSQL-backed multi-pod sharing is an Advanced option rather than the default.
3. **Evidentiary rigor**: Investigations are sealed, replayable records, and the Dossier is hash-verifiable by a route; it is not proof against an attacker who can rewrite the store and recompute every hash, which is why external anchoring is recommended.

For institutions seeking to modernize financial crime, intelligence and fraud operations without moving data outside their perimeter, Inspecto LA offers a graph platform whose claims are listed, sourced and bounded in Section 7.

---

### What changed in version 1.5 (2026-10-28)

- The analyst story follows the reorganized Studio (Section 1 and Section 4): **Expand…** replaces Grow, **Collapse** and **Clean up** are added, **Save view** replaces *Save as a case*, and the shared Studio layout is described; the industry-pack relabels on the canvas legend and pattern picker are built and leave the roadmap (Sections 3.6 and 7.1); the analyst documentation is one user guide (Section 4).

### What changed in version 1.4 (2026-10-24)

- Settings now warns an administrator before a broken auto-apply category exclusion list refuses new Investigations (Section 1, *Grow and Exclude*).
- Added the onboarding story (Section 1, *From a blank install to a first finding in ten minutes*: Getting started, Add data, Start from..., Find something interesting, My work, help on every screen), Grow, Held and Exclude with the category exclusion lists, and the edition placement of Case history and the dashboard Widgets (decisions `D-LAE1`, `D-LAE2`).

### What changed in version 1.3 (2026-10-21)

- Merged the technical-stakeholder edition (renamed *Inspecto LA whitepaper*; new Sections 2.5, 4.8, 5.8, 6.5 to 6.7 and 7.1; limit count corrected to 148 and the per-delta lookup cost to about 2 ms).

### What changed in version 1.2 (2026-10-21)

- The scale runs of 2026-10-21 (full builds at $2\times10^8$ and $3\times10^8$ edges out of memory at a 6 GB cap, $5\times10^8$ at 12 GB; $10^9$ not proven), the production build-memory setting and the wait for ingest (Sections 1, 6.1 and 7); the Geo map placed in Standard and above (decision `D-LAE5`, Section 1); the route count of the Claims Register made marker-checked (97); the coverage figures cited to the documents that record them.

### What changed in version 1.1 (October 2026)

- **Naming and editions**: the product is *Inspecto LA (Link Analysis)*; editions are Basic, Standard, Advanced and Preview (Section 1, *Editions and packaging*); Basic is the explorer, and the Investigation-based content is stated to be Standard and above.
- **Corrections** against the code: five `la/` modules (the standalone `la-app` is an Angular project); the route count (93, marker-checked); all 29 algorithms listed, with minimum cut returned by maximum flow rather than a separate algorithm; the Suspicion Score and Propagated Risk formulas as shipped; the filesystem store as the default in every edition and the real PostgreSQL schema; the real index layout and the on-demand compaction; the benchmark section rebuilt with one bench per table; the gate order, capabilities and rate limits; audit event and error names; embed rules; the Dossier manifest keys and verification routes; the pattern packs and their windows; the layered limits table rebuilt from the catalogue; and the Claims Register's test names, statuses and bases.
- **Removed** because they could not be sourced: dollar-cost figures, the "air-gap certified" wording, per-algorithm complexity classes, and several unsourced latency, speed-up and memory cells.
- **Added**: Entity profile pages (Section 4.7), third-party integration (Section 5.7), industry packs (Section 3.6), the further showcase verticals (Section 3.5), the masking hardening and its open items (Section 5.2.1), and the single-day production-scale measurement (Section 6.4).
