---
type: Concept
title: Acquisition — specification
description: The original requirement for remote file collection (14 numbered areas), the as-built acquisition engine (the poll cycle, phases A–F, slice frontiers, the stream lane, the control surface, observability and defaults), and a pointer to the Unpack stage (Part 3, its own page spec-acquisition-unpack.md) that turns Archives and compressed streams into plain files before Consignments are planned.
resource: platform/inspecto-acquire/src/main/java/com/gamma/acquire, platform/inspecto-etl/src/main/java/com/gamma/etl/unpack
tags: [acquisition, requirements, discovery, stability, dedup, watermark, guarantees, unpack, decompression, collector, archive, consignment]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Acquisition — specification

**What this covers.** Everything that happens before a byte becomes a record: how Inspecto reaches a remote
system, decides which files are new and complete, fetches each one exactly once and lands it in a Pipeline's
inbox, and how a compressed or archived file is unpacked into plain files on the way. Part 1 is the original
requirement in its own words; Part 2 is the engine as built, with its traps; Part 3, the Unpack stage, has its own page,
[spec-acquisition-unpack.md](spec-acquisition-unpack.md).
What is still open, what was decided and what was refused is in [the plan](plan-elt.md); the screens are in
[the user guide](user-guide-elt.md).

**Contents**

- [Part 1 — The original requirement](#part-1--the-original-requirement)
  - [1. Collector (file) connectivity](spec-acquisition-file-collection.md#1-collector-file-connectivity) · [2. File discovery](spec-acquisition-file-collection.md#2-file-discovery) ·
    [3. File stability detection](spec-acquisition-file-collection.md#3-file-stability-detection) · [4. Duplicate prevention](spec-acquisition-file-collection.md#4-duplicate-prevention) ·
    [5. File change detection](spec-acquisition-file-collection.md#5-file-change-detection) · [6. Collection guarantees](spec-acquisition-file-collection.md#6-collection-guarantees) ·
    [7. File retrieval](spec-acquisition-file-collection.md#7-file-retrieval) · [8. Parallel fetching](spec-acquisition-file-collection.md#8-parallel-fetching) ·
    [9. Post-processing actions](spec-acquisition-file-collection.md#9-post-processing-actions) · [10. Failure handling](spec-acquisition-file-collection.md#10-failure-handling) ·
    [11. Integrity verification](spec-acquisition-file-collection.md#11-integrity-verification) · [12. Observability](spec-acquisition-file-collection.md#12-observability) ·
    [13. Configuration example](spec-acquisition-file-collection.md#13-configuration-example) · [Design philosophy](spec-acquisition-file-collection.md#design-philosophy)
- [Part 2 — The acquisition engine as built](spec-acquisition-file-collection.md#part-2--the-acquisition-engine-as-built)
  - [What acquisition is, and where it stops](spec-acquisition-file-collection.md#what-acquisition-is-and-where-it-stops)
  - [The poll cycle and its two timers](spec-acquisition-file-collection.md#the-poll-cycle-and-its-two-timers)
  - [Phase A — discovery](spec-acquisition-file-collection.md#phase-a--discovery)
  - [Phase B — the readiness / stability gate](spec-acquisition-file-collection.md#phase-b--the-readiness--stability-gate)
  - [Phase C — duplicate prevention, change policy, watermarks](spec-acquisition-file-collection.md#phase-c--duplicate-prevention-change-policy-watermarks)
  - [Phase D — gap detection](spec-acquisition-file-collection.md#phase-d--gap-detection)
  - [Phase E — retrieval: plan once, stage, then land](spec-acquisition-file-collection.md#phase-e--retrieval-plan-once-stage-then-land)
  - [Phase F — retry, circuit breaker, post-action](spec-acquisition-connectors-control.md#phase-f--retry-circuit-breaker-post-action)
  - [The connector SPI and the resolvable schemes](spec-acquisition-connectors-control.md#the-connector-spi-and-the-resolvable-schemes)
  - [Connection profiles, secrets, host keys, tunnels and proxies](spec-acquisition-connectors-control.md#connection-profiles-secrets-host-keys-tunnels-and-proxies)
  - [The control surface](spec-acquisition-connectors-control.md#the-control-surface)
  - [Observability](spec-acquisition-connectors-control.md#observability)
  - [Network shares — the declined design, recorded once](spec-acquisition-connectors-control.md#network-shares--the-declined-design-recorded-once)
  - [Defaults worth not "fixing"](spec-acquisition-connectors-control.md#defaults-worth-not-fixing)
- [Part 3 — The Unpack stage](spec-acquisition-connectors-control.md#part-3--the-unpack-stage)
- [References](spec-acquisition-connectors-control.md#references)

---

# Part 1 — The original requirement

> **The requirement of record, kept for the wording of its 14 numbered areas.** *(Moved from the retired
> root-level `data_acquisition_framework.md` (docs consolidation, 2026-07-16); merged here 2026-11-18.)*
> **The framework is built — Phases A–F have all shipped on `master`.** What is built is Part 2; what is left
> and what was refused is [the plan](plan-elt.md) (the in-process NFS/SMB client is a **declined** design, not a
> pending one — mount the share and use `local`). The original phase roadmap is archived at
> [`2026-06-14-data-acquisition-framework-roadmap.md`](../../../../archived-documents/superpowers/specs/2026-06-14-data-acquisition-framework-roadmap.md),
> and the operator-facing config/runbook are
> [`configuration.md`](../config/admin-guide-configuration-reference-collector-types.md#data-acquisition--the-collector-block) and
> [`../connectors/admin-guide-connectors-runbook.md`](../connectors/admin-guide-connectors-runbook.md#remote-connectors-sftp--ftp--ftps).

**GOAL:**
* **The system guarantees that every eligible data source file is collected exactly once (or according to policy), safely, efficiently, and recoverable regardless of where the file resides**
* **There would be other source connectors (`Kafka` streaming + `DataBase Tables` export shipped; more to come) to create a Data Lake House System**

## Parts

This page is split by topic into 2 parts; each part lists the sections it holds.

1. **[File connectivity, discovery, stability and collection guarantees](spec-acquisition-file-collection.md)** — 1. Collector (File) Connectivity · 2. File Discovery · 3. File Stability Detection · 4. Duplicate Prevention · 5. File Change Detection · 6. Collection Guarantees · 7. File Retrieval · 8. Parallel Fetching · 9. Post Processing Actions · 10. Failure Handling · 11. Integrity Verification · 12. Observability · 13. Configuration Example · Design Philosophy · What acquisition is, and where it stops · The poll cycle and its two timers · Phase A — discovery · Phase B — the readiness / stability gate · Phase C — duplicate prevention, change policy, watermarks · Phase D — gap detection · Phase E — retrieval: plan once, stage, then land
2. **[Retry, the connector SPI, connection profiles and control](spec-acquisition-connectors-control.md)** — Phase F — retry, circuit breaker, post-action · The connector SPI and the resolvable schemes · Connection profiles, secrets, host keys, tunnels and proxies · The control surface · Observability · Network shares — the declined design, recorded once · Defaults worth not "fixing" · References
