---
type: Feature
title: "Decision Kernel (Decision Rules and the Condition Language) — features"
description: "The **Decision Kernel** is the one runtime every decision kind shares: the **Condition Language** (the condition tree), the when / then model and the **Consequence registry**."
tags: [scoring, decision-kernel, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Decision Kernel (Decision Rules and the Condition Language) — features

**Availability:** Offering: every edition (some consequences need add-on modules).

## What it does

The **Decision Kernel** is the one runtime every decision kind shares: the **Condition Language** (the condition
tree), the when / then model and the **Consequence registry**. A **Decision Rule** is its business-logic and
routing kind: `when` a record matches, apply `consequences`. It is not a general rule engine (decision D-MR12).

Code: `platform/inspecto-engine/src/main/java/com/gamma/query/ConditionTree.java` (in-memory evaluator),
`platform/inspecto-engine/src/main/java/com/gamma/query/ConditionSql.java` (DuckDB predicate),
`platform/inspecto-engine/src/main/java/com/gamma/query/DecisionRuleApplier.java` (per-batch record routing),
`platform/inspecto-engine/src/main/java/com/gamma/pipeline/DecisionRules.java` (rule loading),
`platform/inspecto-engine/src/main/java/com/gamma/decision/` (Consequence registry),
`inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` (HTTP).

## Where it fits

Engine base (`inspecto-engine`) plus control-plane routes. The browser twin is
`inspecto-ui/src/app/inspecto/query/query-eval.ts`; `ConditionTree` is a port of it, so a preview in the editor
and a backend count agree. The `Conditions` text notation (Access Policies only) is separate; compiling it into
the tree was declined (see [module reorganisation decisions](../../layer/platform/plan-module-reorganisation-decisions.md)).

More: [screens](user-guide-decision-kernel.md) · [API](api-decision-kernel.md) · [architecture](architecture-decision-kernel.md).
