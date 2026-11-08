# Link Analysis insider threat showcase — brochure

> **An employee hands in their notice, then reads twenty times their normal number of customer records for three weeks and mails 1.2 GB to a personal mail domain. A manager gives four of their own staff payroll and treasury access with no ticket, and one of them later changes a vendor's bank account. Two colleagues approve each other's expenses forty times, each claim just under the limit, mostly at weekends. All of it is hidden among 3 000 invented employees. You can find each one from a saved view in minutes, and see why the innocent look-alikes stay apart.**

*Audience: customers, presales and sales. Product Map Item `solution-pack.la-insider` · status **preview**. This is a seeded demonstration Space on synthetic data, not a sold product. Prices are not published here.*

## Who it is for

Security, HR and internal-investigations teams, and presales teams who want to show Link Analysis on access, mail, expense and vendor data without touching anyone's real records.

## The problems it solves

- **Leaver exfiltration:** someone who is leaving takes data with them, in the weeks between giving notice and walking out.
- **Access creep:** access to sensitive systems granted outside the normal process, by someone who should not be granting it.
- **Collusion in approvals:** two people who sign off each other's claims, so neither faces a real check.

Each story has an innocent look-alike that a simple count or threshold would also flag. A migration engineer's bulk read is just as large, but it is ticketed and nobody is leaving. A person who moves into payroll needs payroll access, and gets it with a ticket the day after the move. A team lead approves a busy field team's expenses all the time. The graph shows the difference.

## Privacy by design

Insider-risk data is sensitive, so the showcase holds only what such a team would hold, at the level it would hold it. Every person is an invented id with no name. Access logs are **daily counts per person and system**, never individual records. External mail is **counted per person, domain and day**, never message by message. Systems, vendors and mail domains are invented (domains end in the reserved `.example`).

## What is included

- Six synthetic feeds: the HR record, access grants, daily access counts, external mail per day, expense claims and vendor master changes. Twelve months are loaded, and one more month can be dropped in during the demo.
- Six Collectors and Pipelines and a Dataset per feed. A Job builds one link Dataset of six link kinds after every load, and a second Job derives one Dataset of three insider-risk screens.
- Three saved views, one per story, each small enough to draw whole.
- A user guide with a five-minute tour and one exercise per story, with answers.

## How it fits with the Editions

Runs on every edition, [Basic](../../edition/basic/brochure.md) included: no sign-in, Alert Rules or Investigations are needed. An Advanced tier is planned, with personas, an Alert Rule for a grant without a ticket to a finance system, and a seeding script.

## How it works

Download the product, the Space and the data, unzip them side by side, and start the product. The Pipelines load the data on start-up. Then open a saved view and read the evidence on each link.

## Next step

Book a guided insider-risk walk-through, or install it yourself from the [admin guide](admin-guide-install.md).

## Read more

- [User guide: tour and exercises](user-guide.md)
- The template manifest is `spaces/_templates/la-insider/template.toon`.
- Whole-product sales material: [executive brief](../../layer/product/brochure-executive-brief.md) · [solution overview](../../layer/product/brochure-solution-overview.md)
