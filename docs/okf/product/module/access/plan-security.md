---
type: Capability
title: Security (SEC)
description: Who may call what — the auth-free core, the Authenticator/Subject/TokenRelay/AccessDecider/SecretsProvider SPIs, the Standard OIDC + RBAC module, the Enterprise ABAC policy engine, secrets, HTTPS and the write-root gate. The requirement of record for the SEC area, its specification, its decisions, and what was refused.
resource: providers/inspecto-oidc/, providers/inspecto-policy/
tags: [sec, capability, security, oidc, rbac, abac, capabilities, roles, access-policy, secrets, https, write-gate]
timestamp: 2026-09-08T00:00:00Z
item: module.access
audience: internal
status: plan
verified: 2026-11-20 last-edit
code: platform/inspecto-access
---

# Security — capability spec (`SEC`)

> **What this page is.** The single entry point for the SEC capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Second of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §1-A, binding). A **Lens** is a self-selected *view* and is never a
> permission. A **Role** is an *assigned* authorization enforced server-side; role *assignment* lives in the
> IdP, only role *definitions* are authorable. A **Capability** is one named authorization question
> (`canAuthorWorkbench`, …) — the seam between Lens and Role; panes and routes gate on a Capability, never on
> Lens identity. **Access Catalog** / **Access Profile** / **Grant** are the gateable surface, one subject's
> sparse grant map over it, and one entry of that map. A **Share** is an intra-Space grant on one component;
> the **Exchange** family shares *across* Spaces. An **Access Policy** is an allow/deny statement over
> **Attributes**; ⛔ never bare *Rule* or *Policy Rule*. An **Edition** is a build flavour, never a branch and
> never a runtime flag (§14).
>
> ⚠ **Edition names (2026-11-17).** This spec was written before the 2026-10-17 rename (`LA-EDITIONS-1`): read
> *Personal* as **Basic** and *Enterprise* as **Advanced** (the `edition-advanced` profile, which also carries
> `inspecto-policy`); *Standard* is unchanged, and `edition-preview` carries the same security modules as Advanced.
> Dated decisions keep the names they were taken under.

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Purpose, scope and requirements of record](plan-security-scope.md)** — 1. Purpose & scope · 2. Requirements of record
2. **[Specification](plan-security-specification.md)** — 3. Specification
3. **[Decisions, not built, refused and as-built pointers](plan-security-decisions.md)** — 4. Decisions · 5. Not built · 6. Refused & superseded · 7. As-built mechanism (pointers only)
4. **[Verification](plan-security-verification.md)** — 8. Verification
