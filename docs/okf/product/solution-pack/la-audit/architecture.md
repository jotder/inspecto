# Link Analysis audit showcase — architecture

The `la-audit` Space Template (`spaces/_templates/la-audit/template.toon`). The studio, index and store seams it runs on are the same as in the [la-showcase architecture](../la-showcase/architecture.md) and the [la-core architecture](../../module/la-core/architecture.md); this page covers only what is specific to audit.

## Data flow

1. Five daily-poll Collectors and Pipelines (`config/audit/`) land `ap_invoice`, `ap_approval`, `vendor_master`, `employee_master` and `gl_journal` from `data/inbox`. The next month waits in `data/staged`.
2. `sql.template` Jobs (`config/jobs/`) fold them into link Datasets: `ap_links` runs on a 10-minute cron; `ap_approval_links`, `ap_identity_links` and `ap_org_links` follow by signal; `gl_links` and `ap_duplicate_payments` are separate Jobs.
3. `la_index_build` runs after `ap_links`; `la_detect` runs every 5 minutes.
4. Investigations, members, Entity Lists, a Case and a bound Alert Rule are runtime state created by `node tools/seed-la-audit.mjs --apply`.

## Datasets

| Dataset | Role |
|---|---|
| `ap_invoice_dataset`, `ap_approval_dataset`, `vendor_master_dataset`, `employee_master_dataset`, `gl_journal_dataset` | landed feeds; the masters supply reference rows on the Entity profile |
| `ap_links_dataset` (`src`, `dst`, `kind`, `ts`) | link kinds `approved`, `paid_to`, `reports_to`, `shares_account`, `shares_address` |
| `ap_approval_links_dataset`, `ap_identity_links_dataset`, `ap_org_links_dataset` | derived subsets of `ap_links` |
| `gl_links_dataset` | kind `journal_flow` between legal entities |
| `ap_duplicate_payments_dataset` | same vendor, amount and ISO week, two invoice numbers |

Placeholder keys (`PENDING` accounts, blank addresses) and any key held by more than 10 parties make no shared-attribute link.

## Settings and access

- `config/link-analysis.toon`: masking `typed` (`PARTY` columns masked, `LEGAL_ENTITY` not), four-eyes above 30, link index ON, Drafts.
- Saved views: `ap_approval_graph`, `ap_vendor_identity`, `gl_intercompany`.
- `config/roles.toon` and `config/demo-users.toon`: `audit.lead`, `audit.manager`, `audit.committee`, `audit.outsider`, `audit.admin`.
- `portal/` holds the committee portal page for the embed scene.
