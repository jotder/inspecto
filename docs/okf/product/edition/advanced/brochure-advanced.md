---
type: Brochure
title: Inspecto Advanced — brochure
description: Sales brochure for the Advanced edition: who it is for, and what it adds to Standard — Investigations and operational objects on Postgres, the attribute-based policy engine and the Intelligence module.
item: edition.advanced
audience: internal
status: current
verified: 2026-11-18 last-edit
code: offerings/advanced.toon
---

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
- The [Telecom industry pack](../../addon/telecomIndustryPack/index.md): native ASN.1 CDR decoding with the telecom vendor decoders.
- The Link Analysis showcases ([mule ring and telecom](../../solution-pack/la-showcase/brochure-la-showcase.md), [mobile money](../../solution-pack/la-mobile-money/brochure-la-mobile-money.md), [insurance claims](../../solution-pack/la-claims/brochure-la-claims.md), [finance audit](../../solution-pack/la-audit/brochure-la-audit.md)) run on Advanced or Preview builds.

## How it fits with the Editions

Advanced includes all of [Standard](../standard/brochure-standard.md) and therefore [Basic](../basic/brochure-basic.md). It is the top customer Edition; [Preview](../preview/brochure-preview.md) carries the same modules for evaluation. Some Advanced items — scale-out, retention and data residency — are **not built yet** and are on the roadmap. **Legal hold is built:** an Incident under legal hold keeps its evidence — its notes and attachments, its Alerts, its links and Case membership, the Case it is in and that Case's Investigations, and its audit history — out of reach of every retention task and delete until the hold is released, and every refused attempt is audited. The hold is set through the API (`PATCH /objects/{id}`); there is no screen for it yet, and a Pipeline's own data partitions are not covered ([what a hold protects](../../layer/platform/admin-guide-operations-reference.md)).

## How it works

The same application, deployed as one or more nodes in front of a shared Postgres. TLS terminates at your load balancer or gateway, sign-in goes through your identity provider, and the policy engine evaluates every request after the role check.

## Next step

Bring your architecture and security requirements to a design session; we will map them to an Advanced deployment.

## Read more

- This Edition's hub: [index](index.md); the full module and Add-on matrix is in [spec-editions-placement.md](../../layer/platform/spec-editions-placement.md#add-ons-by-edition)
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md) · [enterprise whitepaper](../../layer/product/brochure-enterprise-whitepaper.md) · [competitive landscape](../../layer/product/brochure-competitive-landscape.md)
