# Inspecto Advanced — brochure

> **Standard, built for the organisation — shared Postgres storage for Investigations, a policy engine that narrows access, and AI-assisted intelligence.**

*Audience: customers, presales and sales. Product Map Item `edition.advanced` · status **ga**. Prices are not published here.*

## Who it is for

Organisations running Inspecto across several teams or nodes, with high-availability, data-access policy and governance requirements.

## The problems it solves

- Investigation state must survive a node failure and be shared across nodes.
- Role-based access is not fine-grained enough; access must depend on attributes such as region or business unit.
- Analysts want help reading large graphs and findings.

## What is included

- Everything in Standard.
- Investigations on Postgres (`la-store-pg`) for multi-node, highly-available deployments; operational objects on Postgres.
- Policy engine (attribute-based access control that can only narrow what roles allow).
- Intelligence module ([AI Assist & Intelligence](../../addon/aiAssist/index.md)).
- The Link Analysis showcases ([mule ring and telecom](../../solution-pack/la-showcase/brochure.md), [mobile money](../../solution-pack/la-mobile-money/brochure.md), [insurance claims](../../solution-pack/la-claims/brochure.md), [finance audit](../../solution-pack/la-audit/brochure.md)) run on Advanced or Preview builds.

## How it fits with the Editions

Advanced includes all of [Standard](../../edition/standard/brochure.md) and therefore [Basic](../../edition/basic/brochure.md). It is the top customer Edition; [Preview](../../edition/preview/brochure.md) carries the same modules for evaluation. Some Advanced items — scale-out, retention, legal hold and data residency — are **not built yet** and are on the roadmap.

## How it works

The same application, deployed as one or more nodes in front of a shared Postgres. TLS terminates at your load balancer or gateway, sign-in goes through your identity provider, and the policy engine evaluates every request after the role check.

## Next step

Bring your architecture and security requirements to a design session; we will map them to an Advanced deployment.

## Read more

- This Edition's hub: [index](index.md); the full module and Add-on matrix is in [EDITIONS.md](../../../../EDITIONS.md)
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
