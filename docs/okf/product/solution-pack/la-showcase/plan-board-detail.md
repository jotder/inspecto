---
type: Plan
title: solution-pack.la-showcase - board row detail
description: The narrative behind solution-pack.la-showcase's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [solution-pack, la-showcase, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
---

# solution-pack.la-showcase - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### LA-SHOWCASE-RELEASE-RESIDUALS-1

- `LA-SHOWCASE-RELEASE-RESIDUALS-1` — **what the 2026-11-09 showcase release and its live proofs did not cover.** (1) The Linux product zip: `package.ps1` built the `windows_amd64` products only, because no Linux GraalVM jmods cache (`-GraalvmCache`, `GRAALVM_CACHE` or `<repo>/.graalvm-cache`) was on the build box. (2) The Link Analysis UI on the thirteen new Advanced tiers: the personas, the held expand and the Drafts were checked through the API only. (3) An OIDC sign-in build (Demo Auth stood in; both make masking `typed`). (4) The saved Geo Views on Standard. (5) The staged month landing with the Advanced tier armed (not run for `la-police`, `la-trade`, `la-logistics` and `la-insider`; `la-benefits` re-computed in DuckDB only). Publishing the release is the operator's (built locally only, by decision). The composed industry Spaces booted with these tiers belong to the industry Spaces plan (`superpower/2026-11-08-la-industry-spaces-plan.md`). **2026-11-16 (operator, MoSCoW C13):** kept on hold for now, the Linux product zip included; `hold: operator`. → `okf/product/solution-pack/la-showcase/admin-guide-showcase-release.md`

### LA-INDUSTRY-FOLD-1

- `LA-INDUSTRY-FOLD-1` — **fold `la-showcase` into *Banking and finance* and `la-mobile-money` into *Telecom*, so only industry Spaces ship.** Operator, 2026-11-10: ship only industry Spaces, remove duplicates and example-only Spaces; `la-audit` joined *Enterprise* and `la-sample` left *Banking* that day, and these two ship on their own meanwhile. What blocks each fold (found with `stage-industry`/`mergeEntityTypes`): (1) neither template declares Entity Types in its `link-analysis.toon`; they use built-in classification codes (`ACCOUNT`, `MSISDN`, `IMSI`, `WALLET`, `AGENT`) where the industry declares other ids or codes (Telecom's `wallet`/`agent`/`subscriber_number` with `WALLET_ID`/`AGENT_ID`/`SUBSCRIBER_NUMBER`), and the packer requires an Entity Type stated by two use cases to be byte-identical; their Datasets also lack `primaryEntityType`/`defaultLinkMapping`; (2) `la-mobile-money` and `la-mfs` both ship a differing `wallet_txn` feed: rename `la-mfs`'s to `mfs_wallet_txn` (tried: Job SQL and sources, `MfsLinksJobGoldenTest`, `gen-la-mfs-data.py` and its test, two guides; the generator test passed 5/5); (3) four-eyes fan-out 100 (`la-mobile-money`) vs 2000 (Telecom), and `domain_profile` (`la-showcase` telecom, `la-mobile-money` mobile-money) would set the vocabulary of the whole industry Space: decide whether an industry states its own profile. Videos 1 and 4 use `la-mobile-money`; 3.2 B uses `la-showcase`. Also from the LA full-cycle drive (2026-11-16): finding 8, Create a Space offers only Mobile Money, Telecom and Generic while twenty industry templates ship; finding 28, the `la-showcase` Space's `domain_profile: telecom` makes the bank mule case read "subscribers" and "calls" (confirmed live), and an extra pack relabels core types ("Billing account").
