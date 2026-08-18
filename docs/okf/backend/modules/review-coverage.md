---
type: Reference
title: Java Review Coverage
description: Which Java surfaces have been read line-by-line, when, and the defect classes that keep recurring — so a later sweep starts from what is unread rather than re-reading what is clean.
resource: docs/archived-documents/plans-archive/java-codebase-review-sweep.md
tags: [review, coverage, defect-classes, control-plane, engine]
timestamp: 2026-08-18T00:00:00Z
---

# Java Review Coverage (as-built)

Distilled from `java-codebase-review-sweep.md` (completed 2026-08-18, archived in
`../../../archived-documents/plans-archive/`) — that plan holds the full per-file evidence base.
This page keeps only what stays useful: **what has been read, what has not, and the defect classes
worth hunting first.**

## The recurring defect classes, most productive first

1. **A gate enforced on one route but not its sibling.** This project's single most repeated defect.
   Every sweep has found at least one. Three shipped examples: `ObjectRoutes.scoped()` guarded the URL
   `{id}` but not object ids arriving in the BODY (5 routes); `/components/schema/{id}` skipped the
   structural/safety/compat gates its `/config/write` twin enforces on the same file; `AUTHOR-1`'s
   save-path rule missed the second gate on the same path.
2. **An unmodelled key lost on a read-modify-write round trip** — has caused real data loss here.
3. **A durable side effect ordered before the thing that authorises it**, or an exception path that
   leaves a half-finished artifact (JAVA-1's orphaned Parquet generations, JAVA-4's post-commit FAILED).
4. **A process-wide per-key map with no eviction hook** (JAVA-3). Check every `shared()` singleton.
5. **`Objects.equals(null, null) == true` in a dedup/match predicate** — an absent key matching another
   absent key silently swallowed Incidents (JAVA-6).

## Read line-by-line (do not re-read without a reason)

| When | Surface |
|---|---|
| 2026-08-18 | `inspecto-{api,util,config,sql,etl,event,acquire,agent-hosted,security,policy}` — every file |
| 2026-08-18 | engine `job`, `pipeline`(+`exec`), `consignment`, `inspector`, `enrich`, `parse`, `ingester` |
| 2026-08-18 | engine `ops/**` (incl. `FindingsSpec`, `link`/`note`/`queue`/`tag`/`workflow`) + the whole `signal` package |
| 2026-08-18 | control plane: `PipelineRoutes`, `ObjectRoutes`, `BundleRoutes`, `ConfigRoutes`, `ComponentRoutes`, `Roles`, `AccessPolicies`, `AccessRoutes`, `RowScope`, `WriteGates`, `AccessDecider(s)`, `ControlApi` dispatch/`authorize` |
| 2026-08-18 | `inspecto-policy` `PolicyEngine`; `inspecto-intelligence` `InspectoTools` + `InspectoIntelligenceAgent` + `ComponentActions`/`OperationalActions`/`RunbookActions`/`AgentApprovals` |

## Still unread (where a third sweep should start)

- Most of `inspecto/src/main/java/com/gamma/service` — only `CollectorService`, `PipelineScheduler`,
  `ConfigRegistry`, `SpaceManager`, `BundleImporter`/`BundleExporter` have been read, and several of
  those only around a specific call path.
- `AccessDecider`'s *other* implementors and `AuditTrail` internals.
- Engine `catalog`, `query`, `notify`, `alert` — sampled in the first sweep, never exhausted.
- `ConfigSafetyValidator`, `SchemaCompatibility`, `MappingCsv`, `PipelineDependents`,
  `PipelineReferences`, `ComponentAccess`, `WidgetTags` — used from reviewed call sites, internals unread.

## Verified-clean findings worth NOT re-deriving

- **The ABAC stack is fail-closed end to end.** An unparseable `access-policies.toon` denies loudly
  (never "no policies"); a bad `when` 422s at write time; a condition that cannot evaluate yields false,
  and a non-matching `deny` falls through to the capability gates. `ALLOW`/`ABSTAIN` never bypass a
  capability gate. `authorize` runs in the dispatch loop for **every** matched route.
- **No mutating route is exposed over GET.** Worth re-probing after adding routes: `actionFor` maps
  GET→`read`, so a mutating GET would dodge a policy denying `write`.
- **Every mutating agent tool routes through `AgentApprovals`** (7 of 22 tools are mutating), and
  `runTool`/`deriveTool` refuse a mutating tool before the model is called. The `ops_monitor` autonomy
  driver bypasses that gate **by design**, substituting `AutonomyPolicyEngine` — opt-in, off by default.
- **`PathJail.contains` is the single containment verdict** shared by the enforcing and advisory
  surfaces; `WriteGates.jail` delegates to it. See [path containment](../config/index.md).

## Coverage honesty

A "clean" row above means *read with intent to find defects*, not *proven correct*. Two sweeps have each
found defects in surfaces the previous one called clean by sampling — treat "sampled" as unread.
