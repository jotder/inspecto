---
type: Feature
title: "Telecom industry pack — admin guide"
description: "Select and verify the telecom domain profile for a Space."
tags: [industry-telecom, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom industry pack — admin guide

## Prerequisites

- Any Edition, Basic and up: the pack ships in the entity-store module's classpath, so there is nothing to install. Licensed by contract, order code `INS-IP-TELECOM`.
- Selecting the profile needs `canAuthorWorkbench`.

## Enable for a Space

Either from the Link Analysis settings screen, or over the Control API:

```
GET /api/v1/settings/link-analysis/domain-profile
PUT /api/v1/settings/link-analysis/domain-profile
{"domainProfile": "telecom"}
```

The GET answers `{active, stated, profile, installed[], refused[]}`. The PUT writes only `domain_profile` in the Space's `link-analysis.toon`; it answers 422 for an id that is not installed (naming the installed ones) and 409 when `link-analysis.toon` does not parse. `"generic"` or `null` returns to the neutral vocabulary (`DomainProfileRoutes.java`).

To get the paired data, create a Space from `telco-fraud` or `la-showcase` with `POST /api/v1/spaces` `{"id": "<space-id>", "template": "<template>"}` — see their admin guides.

## Verify

- `installed[]` of the GET contains `telecom`; `refused[]` is empty.
- After the PUT, `active` is `telecom`. If `active` is `generic` with a `diagnostic`, the stated pack is not installed.
