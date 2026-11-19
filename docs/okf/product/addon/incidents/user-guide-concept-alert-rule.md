---
type: Concept
title: Alert Rule, Expectation and Decision Rule
description: What an Alert Rule is — a watch on a number that fires an Alert when it crosses a threshold — and how it differs from an Expectation and a Decision Rule.
item: addon.incidents
audience: external
---

# Alert Rule, Expectation and Decision Rule

Inspecto has three kinds of rule, and each answers a different question. They are always named in full, because the
word "rule" alone would not say which one you mean.

| | Asks | Looks at | When it is not met |
|---|---|---|---|
| **Expectation** | Is the data right? | the records of a Dataset | counts the bad records; can quarantine them |
| **Alert Rule** | Is something out of the ordinary? | a number: an operational metric, or a Measure over a Dataset | fires an **Alert** |
| **Decision Rule** | Where should this record go? | each record, as a Pipeline reads it | routes, tags, quarantines or drops the record |

## Alert Rule

### What it is

An **Alert Rule** watches a number and fires an **Alert** when the number crosses a threshold. The number is either
an operational metric — for example how many records a Pipeline quarantined — or a business **Measure** over a
[Dataset](../../layer/product/user-guide-concept-dataset.md), such as daily revenue or the count of failed payments.
An Alert Rule on a Measure is how you turn a KPI into an alert.

An Alert Rule states the number to watch, a comparison (above, below …), the threshold, the time window, and the
Alert's severity: info, warning or critical. It can name a **runbook**: the steps the person handling the Alert
should follow.

### Why it exists

Nobody watches every Dashboard all day. An Alert Rule watches for you, and tells the right people when the number
moves out of line.

### How it relates to its neighbours

```
  Dataset Measure  ─┐
                     ├─▶ Alert Rule ─▶ Alert ─▶ Incident ─▶ Case
  operational metric┘    threshold     fired     worked     grouped
```

- Each Alert it fires can open an
  **[Incident](user-guide-concept-alert-incident-case.md)**, which someone then works to a resolution.
- In Link Analysis, **Watch** on an Investigation's Measure creates an Alert Rule over what the Investigation shows;
  the Alert names the Investigation. See
  [Link Analysis and Investigations](../../module/la-core/user-guide-concept-link-analysis.md).

### What you do with it in the console

**Operations → Alerts** lists the Alerts that fired. The **Alert Rules** section beneath the list is where you create,
edit and delete Alert Rules, and **Evaluate now** checks the armed rules on demand. The **Assistant** can help you
write one.

## Expectation

An **Expectation** checks data quality: that records are complete, in range, in the right format, or match another
Dataset. See [Expectation](../../module/elt/user-guide-concept-expectation.md).

## Decision Rule

A **Decision Rule** acts on each record while a Pipeline reads it: if a condition holds, it routes the record to a
particular Dataset, tags it, quarantines it, drops it, raises a signal, or starts another Pipeline. You author and
test Decision Rules in **Platform → Workbench → Decision Rules**, and they can also appear as a routing Step in the
Pipeline editor. See [Pipeline and Steps](../../module/elt/user-guide-concept-pipeline.md).

## Which edition has it

Writing Alert Rules, and Incidents opened from Alerts, need Standard or Advanced. On Basic an Alert Rule that comes
with your configuration is kept but not armed, so it does not fire. Delivering Alerts by e-mail or webhook comes with
the **Integration & Delivery** Add-on, offered from Standard. Expectations and Decision Rules are in every edition.
See [The editions on one page](../../../../EDITIONS.md).

## Read next

- [Alert, Incident and Case](user-guide-concept-alert-incident-case.md)
- [Expectation](../../module/elt/user-guide-concept-expectation.md)
