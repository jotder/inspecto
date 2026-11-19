---
type: Plan
title: layer.la - board row detail
description: The narrative behind layer.la's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [layer, la, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
---

# layer.la - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### LA-UI-TEXT-PASS-2

- `LA-UI-TEXT-PASS-2` — **la-app wording that waits on an operator call.** What the 2026-11-15 pass of the Basic UI test's D-14 left, each a product decision: (1) Settings ▸ Link Analysis ▸ Embed links names the host flag `-Dla.embed.enabled=true`, which an administrator may need; (2) the Add data screen is titled *Onboard a file* while the rail and buttons say *Add data*, as `docs/GLOSSARY.md` (OB-14) sets it; (3) Settings ▸ Access says *capabilities*, the glossary's RBAC word; (4) an answer from the link index names its version (*Answered from the link index (version 10)*, *v3* elsewhere); (5) every showcase Dataset description, shown on the Data page, starts *"<use case> Link Analysis showcase - …"*, says *ONE link Dataset* and names the Job or Pipeline that builds it (about 190 files in `spaces/_templates/la-*/config/registry/datasets`). Also left by the 2026-11-15 pass (no decision needed, just not done): showcase views that still name actions the toolbox lacks (`gl_intercompany` *Find cycles*, *Maximum flow*; `ap_vendor_identity` *Link prediction*, *Jaccard similarity*; `drug_supply_handset_network` *Link prediction*), `aml_account_transfers` still says *seed with* and *(runbook)*, `claims_network` lower-case *held on expand*, and docs quoting the old text (`processor/api-onboarding-authoring.md` and `la-core` *Pipelines - ask*, `la-showcase/admin-guide-cross-vertical-tour.md` *switch space*, the hand-written la-* hub titles *Link analysis …*). → `superpower/2026-11-10-la-basic-ui-test-report.md`
