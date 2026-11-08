---
type: Feature
title: "Link Analysis insider threat showcase — user guide"
description: "A five-minute tour of the la-insider Space from its three saved views, then one exercise per planted story with a hint and an answer, and the tester's checklist of look-alikes that must not join a story."
tags: [la-insider, user-guide, showcase]
timestamp: 2026-11-08T00:00:00Z
---

# Link Analysis insider threat showcase — user guide

Screens and controls are the shared Link Analysis ones: see the
[Link Analysis user guide](../../module/la-core/user-guide-link-analysis.md). Installing the Space is in the
[admin guide](admin-guide-install.md). Everything here is synthetic. Every person is an invented id with no name.
Systems are generic invented names, vendors are `VEN-` ids, and every mail domain ends in the reserved `.example`.

## What is in the Space

The data is what a security, HR and internal-investigations team holds about the 3 000 employees of one invented
company, at the level such a team holds it: access as **daily counts per person and system**, external mail as
**counts per person, domain and day**, never record by record or message by message.

| Feed (Pipeline and Dataset) | Rows landed | What a row is |
|---|---|---|
| `employee` | 3 000 | the HR record: `dept`, `role`, `manager_id`, `hired_at`, `role_since` (later than the hire date after an internal move), and for a leaver `notice_given_at` and `left_at`. No names |
| `access_grant` | about 12 200 | an access grant: `system`, `granted_by` (who approved it), `granted_at`, `ticket_id` (an access request `REQ-` or change `CHG-`; blank when there was none) |
| `access_daily` | about 106 000 (one file a month) | how many records one person read in one record-holding system on one day (`read_date`, `records_read`). Systems: `CRM` (customer records), `CUSTARCH` (the customer archive), `HRIS`, and the finance systems `AP`, `PAYROLL`, `TREASURY`. A day with no reads has no row |
| `email_external` | about 28 700 (one file a month) | mail one person sent to one external domain on one day: `messages` and `attachment_mb` |
| `expense` | about 18 100 (one file a month) | an expense claim: `requester_id`, `approver_id`, `amount`, `submitted_at` |
| `vendor_change` | 569 | a change to the vendor master: `vendor_id`, `field_name` (`address`, `phone`, `contact_email`, `bank_account`, `payment_terms`), `changed_by`, `changed_at` |

The **single-approver expense limit is 500**. The finance systems are `AP`, `PAYROLL` and `TREASURY`. The personal
webmail domains are `inboxly.example`, `mailnest.example` and `postmail.example`; every other domain is a business
contact.

The `insider_links` Job runs after every load and writes **one link Dataset**, `insider_links_dataset` (`src`, `dst`,
`kind`, `ts`, `amount`, `event_date`, `via`), with about 26 000 rows of six kinds. An id says what a node is: `EMP-`
employee, `SYS-` system, `VEN-` vendor, and a domain name for an external mail domain.

| Link kind | From → to | `amount` and `via` |
|---|---|---|
| `reports_to` | employee → manager | `dept:`, `role:`, `role_since:`, `manager_dept:`, `manager_role:` |
| `granted_by` | employee → the person who approved their grants | amount = grants; `systems:`, `grants:`, `no_ticket:`, `finance_grants:`, `finance_after_onboarding:` (finance grants made more than 30 days after the hire date), `finance_no_ticket:`, `last_finance_grant:`, `last_finance_ticket:` |
| `accessed` | employee → system they hold | amount = records read in the year; `read_days:`, `daily_median:`, `peak_21d_avg:` (the largest 21-day average of daily reads, over windows of 5 read days or more), `peak_ratio:` (that peak over the median), `peak_window_end:`, `last_grant:`, `last_ticket:`, `notice_given:`, `left:` |
| `mailed_domain` | employee → external domain | amount = attachment MB; `domain_type:` (`personal` or `business`), `mail_days:`, `messages:`, `attachment_mb:`, `after_notice_mb:` |
| `approved_for` | approver → employee whose expenses they approved | amount = total; `approvals:`, `near_limit:` (450 up to the 500 limit), `over_limit:`, `weekend:`, `largest:`, `approved_back:` (how many of the approver's own claims the employee approved), both roles |
| `changed_vendor` | employee → vendor | amount = changes; `fields:`, `bank_changes:`, `changer_dept:`, `changer_role:`, `vendor_editors:` (how many people ever changed that vendor) |

The derived `insider_screen_links` Job runs each time the link Dataset is rebuilt and writes
`insider_screen_links_dataset`. It has the same columns plus `screen`, the check that picked each link. Each saved view
reads one screen, so every view draws whole, under the canvas's 1 000 nodes:

| `screen` | Picks | Saved view |
|---|---|---|
| `leaver_exfiltration` | every `accessed` link with `peak_ratio:` 10 or more, with that person's `mailed_domain` links of 100 MB of attachments or more | `leaver_exfiltration` |
| `access_creep` | every `granted_by` link with a finance grant made more than 30 days after the hire date, with the bank-detail vendor changes of those employees | `access_creep` |
| `mutual_approvals` | every `approved_for` link where the employee also approved the approver (`approved_back:` over 0), or with 20 approvals or more | `mutual_approvals` |

## Five-minute tour

1. **File ▸ Open… ▸ Saved views ▸ `leaver_exfiltration`.** Five nodes in two pieces. Each piece is a person whose
   reads in one system jumped more than tenfold for a few weeks. One piece also reaches a mail domain. Compare the
   `notice_given:` and `last_ticket:` on the two `accessed` links.
2. **Open `access_creep`.** About 26 nodes. Most are finance staff picking up a second finance system from their own
   finance manager, each with a ticket. One manager outside finance has four people hanging off them, and a vendor
   hangs off one of those.
3. **Open `mutual_approvals`.** About 130 nodes. Most are stars: a sales team lead with the field staff whose frequent
   claims they approve. One pair of nodes is linked both ways.
4. **New data arrives.** Copy the files of `data/staged/<feed>/` into `data/inbox/<feed>/` (see the admin guide). The
   Pipelines load October 2026 and the Jobs rebuild the links. Reopen a view to see the new month.

## Exercises

Work blind first: each exercise has a hint, then the answer. The answers are not in the data.

### 1. Leaver exfiltration — who is taking data on the way out?

*Hint:* in `leaver_exfiltration`, read `daily_median:`, `peak_ratio:`, `peak_window_end:`, `last_ticket:` and
`notice_given:` on each `accessed` link. Then read `domain_type:` and `after_notice_mb:` on the `mailed_domain` link.

**Answer:** **`EMP-0031`**, a customer account specialist, gave notice on **2026-06-01** (last day 2026-06-30). Their
normal day is about **43** customer records in **`SYS-CRM`**. On every working day from **2026-06-02 to 06-22** they
read 860–960, **20 times their normal** (`peak_ratio:20.0`). On nine of those days they mailed **1 228.8 MB** of
attachments to **`postmail.example`**, a personal webmail domain they had used only three times all year, for a few MB.
No new grant or ticket explains the reads. **Not the story:** **`EMP-0386`**, a data migration engineer, read about
**22 times their normal** in the customer archive **`SYS-CUSTARCH`** from 2026-04-06 to 04-24. That read follows a
grant on 2026-04-02 under change ticket **`CHG-163782`**, the engineer has given no notice, and mailed nothing large
and nothing personal. The two never share a system, a domain or a manager.

### 2. Access creep — who is handing out finance access?

*Hint:* in `access_creep`, compare `dept:`, `role:` and `role_since:` with `finance_no_ticket:` and
`last_finance_ticket:` on each `granted_by` link. Then read the `changed_vendor` link.

**Answer:** **`EMP-0007`**, an operations manager (the spec's "M-7"), granted **`PAYROLL` and `TREASURY`** to four of
their own reports between 2026-01-12 and 02-10: **`EMP-0482`** and **`EMP-1415`** (operations analysts),
**`EMP-0636`** (logistics coordinator) and **`EMP-1059`** (facilities coordinator). None works in finance, and
**none of the eight grants has a ticket** (`finance_no_ticket:2`, `last_finance_ticket:none`). On **2026-05-14**
`EMP-0482` changed the **bank account of vendor `VEN-0264`**. Nobody else has ever changed that vendor
(`vendor_editors:1`); everywhere else, only accounts payable staff change vendors. **Not the story:**
**`EMP-0062`** moved into a payroll officer role on **2026-03-02** (`role_since:`) and was granted `PAYROLL` the next
day by the payroll manager **`EMP-1802`**, under ticket **`REQ-163776`**. That is the normal process for a move. The
other finance staff in the view each picked up a second finance system from their own manager, with a ticket.

### 3. Mutual approvals — who signs off each other's claims?

*Hint:* in `mutual_approvals`, look for a pair linked in both directions. Read `approvals:`, `near_limit:`,
`weekend:` and `approved_back:` on both links, and the roles.

**Answer:** **`EMP-0022`** and **`EMP-0040`** are account managers in different sales teams (their managers are
`EMP-1526` and `EMP-2140`). Between 2025-10-21 and 2026-08-29 `EMP-0022` approved 21 of `EMP-0040`'s claims and
`EMP-0040` approved 19 of `EMP-0022`'s: **40 approvals, every one between 455 and 498**, just under the 500 limit
(`near_limit:` equals `approvals:`), and **32 submitted at a weekend**. They are the only pair in the company who
approve each other. **Not the story:** **`EMP-1613`**, a sales team lead, approved about **520 claims** from a team of
20 field sales representatives, 19 of them 20 times or more. The claims were on weekdays, spread over amounts up to
480 (only 3 near the limit), and nobody in the team ever approves the team lead (`approved_back:0`).

## Tester's checklist

The ground truth is `tools/gen-la-insider-data.ground-truth.json` (repository only, not in the download). The golden
tests are `tools/gen-la-insider-data.test.mjs` and `InsiderLinksJobGoldenTest`.

- [ ] All six Pipelines land with nothing rejected, quarantined or in errors.
- [ ] `access_daily` holds one count per employee, system and day, and no other kind of row.
- [ ] `insider_links_dataset` has six kinds and one row per pair; one `reports_to` per employee but the chief
      executive; one `accessed` per system held. Only two `accessed` links have `peak_ratio:` of 10 or more
      (`EMP-0031` → `SYS-CRM`, `EMP-0386` → `SYS-CUSTARCH`), and no other reaches 4.
- [ ] Only the 8 grants of story 2 give a finance system without a ticket.
- [ ] Each story's members form one connected group. Leaver: `EMP-0031`, `SYS-CRM`, `postmail.example`. Access creep:
      `EMP-0007`, `EMP-0482`, `EMP-0636`, `EMP-1059`, `EMP-1415`, `VEN-0264`. Mutual approvals: `EMP-0022`, `EMP-0040`.
- [ ] No look-alike links to its story: `EMP-0386` and `SYS-CUSTARCH`; `EMP-0062` and `EMP-1802`; `EMP-1613`.
- [ ] Each saved view holds its story whole, with its look-alike beside it but not joined to it, and draws without
      "Result cut short". The largest view, `mutual_approvals`, has about 100 rows and 130 nodes.
- [ ] After the staged October lands, the stories stay as they are: no new spike, unticketed finance grant or mutual
      approver appears.
