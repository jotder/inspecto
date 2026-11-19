# Inspecto LA whitepaper
> **Sovereign Graph Operations: In-Process Entity Investigation, High-Scale Parquet Indexing, and Forensic Integrity Without Cluster or Cloud Sprawl**
> *Audience: Chief Risk Officers (CRO), Chief Information Security Officers (CISO), Heads of Financial Crime & AML, Telecom Fraud Operations, Lead Enterprise Architects, Forensic Investigators, and the technical stakeholders who evaluate the system*
> *Publication: October 2026 · Document version **1.5** of 2026-10-28 (supersedes 1.4)*

**Inspecto LA (Link Analysis)** lets fraud and revenue-assurance investigators trace relationships across call records, wallets, devices and accounts, and turn what they find into court-grade evidence, inside the same platform that ingests and assures the data. This paper covers what the system does, how it is built, and what it has been measured to do.

> **Release basis & evidentiary grounding.** This whitepaper specifies **Inspecto LA (Link Analysis)** — the product name; inside the Inspecto console the capability, its routes, configuration keys and modules keep the name **Link Analysis** — as delivered in the **Basic, Standard and Advanced** editions of Inspecto (a fourth edition, **Preview**, carries everything with a demo sign-in and is never sold). It reflects the committed codebase: the five `la/` Maven modules (`inspecto-geo-link`, `inspecto-la-api`, `inspecto-la-core`, `inspecto-la-storage`, `inspecto-la-store-pg`), the two platform modules they stand on (`platform/inspecto-graph`, `platform/inspecto-entity-store`), the shared Angular library `@inspecto/link-analysis`, and the standalone `la-app` shell (an Angular project, `inspecto-ui/projects/la-app`, not a Maven module). Every performance figure names the benchmark that produced it, the host and the date; where a number comes from a single run it says so. Section 7 is a Claims Register that classifies each capability as **PROVEN**, **MEASURED**, **DOCUMENTED** or **NOT YET**. What changed since version 1.0 is listed at the end of this document.

---

## Parts

This page is split by topic into 6 parts; each part lists the sections it holds.

1. **[Executive summary and the forensic lifecycle](brochure-inspecto-la-whitepaper-summary-lifecycle.md)** — 1 · Executive summary — graph intelligence without the cluster tax · 2 · The forensic investigation lifecycle & evidentiary chain of custody
2. **[Analytical depth, graph algorithms and explainable detection](brochure-inspecto-la-whitepaper-analytics.md)** — 3 · Analytical depth, graph algorithms & explainable detection
3. **[Systems architecture and data plane engineering](brochure-inspecto-la-whitepaper-architecture.md)** — 4 · Systems architecture & data plane engineering
4. **[Enterprise governance, security and access control](brochure-inspecto-la-whitepaper-governance.md)** — 5 · Enterprise governance, security & access control
5. **[Performance, sizing, limits and the claims register](brochure-inspecto-la-whitepaper-performance-claims.md)** — 6 · Performance benchmarks, sizing & operational limits · 7 · Transparent Claims Register (Evidentiary disclosure)
6. **[Conclusion and architectural recommendation](brochure-inspecto-la-whitepaper-conclusion.md)** — 8 · Conclusion & architectural recommendation
