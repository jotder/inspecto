---
type: How-to
title: "Detect in real time on a stream"
description: "Land a stream (for example a Kafka topic) through a Pipeline with a stream trigger, and write a windowed Alert Rule that fires within seconds — through configuration or the Control API, since the console has no form for windows yet."
item: module.realtime
audience: external
status: current
verified: 2026-11-20 code-reviewed
code: [features/inspecto-realtime, inspecto/src/main/java/com/gamma/control/AlertRoutes.java]
---

# Detect in real time on a stream

## Goal

Raise an Alert within seconds of a pattern in a stream — say, one caller dialling 30 different numbers within 10
minutes — instead of waiting for a batch.

## Before you start

- **Edition:** Standard, Advanced or Preview with the **Real-time Detection** Add-on; a Kafka stream also needs the
  Premium Connectors Add-on. Not in Basic.
- **Who:** `canAuthorAlertRules`, and a builder who can change the Pipeline (`canAuthorWorkbench`).
- **What you need:** a Pipeline that lands the stream into a Dataset with an event-time column (a `TIMESTAMP`).
- **Console:** windowed and pattern Alert Rules, and a Pipeline's stream trigger, have **no form in the console yet**.
  You write them in the Space's configuration or through the Control API; the console shows the Alerts and Incidents
  they raise.

Concept: [Alert Rule, Expectation and Decision Rule](../../addon/incidents/user-guide-concept-alert-rule.md).

## Steps

1. **Make the Pipeline land in small batches as records arrive.** In its configuration set a stream trigger, and let
   a run take several files at once:

   ```toon
   trigger:
     type: stream
     records: 1000
     max_wait: 5s
   ```

   with `processing.batch.max_files` of at least 2. For Kafka, its Collector uses a Connection with
   `connector: kafka` and the topic in `options.topic`.
2. **Write the windowed Alert Rule** — a Measure over a sliding window on the event time, per key:

   ```json
   {
     "name": "many_numbers_dialled",
     "dataset": "calls",
     "measure": "countDistinct(b_number)",
     "by": ["a_number"],
     "comparator": "gte",
     "threshold": 30,
     "severity": "CRITICAL",
     "window": { "type": "sliding", "size": "10m", "slide": "1m", "time": "event_ts",
                 "distinctOn": ["topic", "partition", "offset"] }
   }
   ```

   `POST` it to `/api/v1/spaces/<space>/alerts/rules`, or save it as `registry/alert-rules/many_numbers_dialled.toon`.
   `distinctOn` names the columns that make a record unique, so a record read twice is counted once. A sequence of
   events uses `pattern` (`type: sequence` with a tumbling window) and an expected event that never comes uses
   `pattern` `type: absence`.
3. **Watch it fire.** Alerts appear in **Operations → Alerts**; a critical one opens an Incident in
   **Operations → Incidents**. The target is an Alert within five seconds of the record that crosses the line.

## Check it worked

On your stream Dataset the save answers 200 and the rule is listed armed; a rule the edition cannot run is listed
but marked inert, *needs the Real-time Detection Add-on*. On the worked example, which has no stream, the same
validation shows itself: the `payments` Dataset's date is a `DATE`, not an event-time `TIMESTAMP`, so a window over
it is refused — and the refusal is about the column, not the edition, so the Add-on is present:

```check
POST /spaces/{space}/alerts/rules
as builder
body {"name": "many_payments", "dataset": "payments", "measure": "count", "by": ["customer_id"], "comparator": "gte", "threshold": 30, "severity": "CRITICAL", "window": {"type": "sliding", "size": "10m", "slide": "1m", "time": "payment_date"}}
expect status 422
expect error.message matches /needs an event-time TIMESTAMP column/
```

## What can go wrong

All of these refuse the save with 422:

- the Add-on is missing or not entitled (`ERR_EDITION_FEATURE`, `INS-AO-REALTIME-DETECTION`);
- `alert.window.time 'x' is not in the Schema of dataset…`, or is not a `TIMESTAMP`: *a Window needs an event-time
  TIMESTAMP column*;
- the Pipeline writing the Dataset has no `trigger {type: stream}`, or its `processing.batch.max_files` is below 2;
- the window is too large: size plus lateness at most one hour, and at most 60 slides per window; a sequence needs a
  tumbling window.

## Next

- [Write an Alert Rule](../../addon/incidents/user-guide-howto-write-an-alert-rule.md) — the console form for
  Alert Rules without a window.
- [Open and work a Case](../case-management/user-guide-howto-open-and-work-a-case.md)
