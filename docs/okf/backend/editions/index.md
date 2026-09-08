---
type: Concept
title: Editions — index
description: How Inspecto ships as Personal / Standard / Enterprise, and the binding branch and release policy.
---

# Editions

How Inspecto ships as Personal / Standard / Enterprise — and the binding branch & release policy. The key
idea: **editions are build flavors, never git branches.**

> ⚠ **The capability spec is the requirement-of-record for this area:**
> [`okf/capabilities/editions/editions.md`](../../capabilities/editions/editions.md). It owns the staged-jar set, the absence
> contract, release integrity and the deployment topologies, and it CORRECTS `REQUIREMENTS.md` §3.16.

## Concepts

* [Editions model](editions-model.md) - build flavors via Maven profiles + ServiceLoader + -D flags.
* [Auth & security](auth-security.md) - auth-free core; the Authenticator/Subject/AccessDecider SPIs; Standard RBAC (data-driven roles, Access-Profile + sharing enforcement, OIDC/gateway); the Enterprise `inspecto-policy` ABAC engine (authored Access Policies, space isolation, decision audit); the separate write-gate.
* [Branching & release](branching-release.md) - versions=branches, merge-forward propagation, SemVer + Conventional Commits.
