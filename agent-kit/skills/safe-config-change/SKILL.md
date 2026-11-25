---
name: safe-config-change
description: The one way an agent changes a running Inspecto - validate, preview, dry-run, propose, then wait for another person to approve. Use before ANY create, update or delete of a Pipeline, Expectation, Alert Rule, Job, Widget, Dashboard, Decision Rule, Notification Rule or Notification Channel, the KPI of a delivered Requirement, a Pipeline restore, and whenever a change is refused, held or needs to be taken back.
---

# Change Inspecto safely

Every other builder skill ends here. You never apply a change yourself: you **propose** it, and **another person**
approves it. That person is never you and never the person you act for (four eyes, always).

## Before the first change

1. Call `edition_and_modules`. The MCP endpoint exists from the Standard edition up; if a module you need is missing,
   its tools answer 503 — tell the person, do not work around it.
2. Call `list_pending_changes` with `status: pending`. If a proposal for the same thing is already waiting, do not
   propose it twice: read it with `get_pending_change` and tell the person.

## The loop — for every change

1. **Draft** the content. Start from what exists (`get_pipeline`, `list_expectations`, `list_alert_rules`,
   `list_jobs`, `describe_dataset`) and change as little as the goal needs. Use the canonical words: Pipeline,
   Dataset, Expectation, Alert Rule, Incident, Measure, Collector, Widget, Dashboard (read the resource
   `inspecto://glossary` if unsure).
2. **Validate** with `validate_config`: `type` is `expectation`, `alert`, `job`, `widget` or `dashboard`, `config` is
   the draft. The answer is `{findings, clean}`. Fix every finding whose severity is an error and validate again.
   Never propose a draft that is not clean. A Decision Rule, a Notification Rule or a Notification Channel has no `validate_config` type: its
   own route checks the content before it holds it, so a bad draft comes back as a 422 naming the field.
3. **Preview** the parts a draft is made of with `preview_config` before you propose, rather than proposing and
   reading the refusal: `kind: parsing` parses a raw sample with a Pipeline draft's parsing settings (body
   `{config, sample_text}`); `kind: suggest-schema` gives the types the server infers from parsed rows (body
   `{sampleRows}`); `kind: schema` casts rows against a schema draft and runs its mapping (body `{config,
   sampleRows}`: read `rejectedCount` and `rejectedRows`); `kind: mapping` checks mapping rules (body `{rules}`);
   `kind: lookup` tests one value through a Pipeline's keyed join (body `{pipeline, step, value}`). Nothing is
   written. Put the draft inline: a draft that names a file is refused (422 "names a file"), and a body is at most
   256 KiB — send a sample of rows, not a whole file. Preview until clean.
4. **Dry-run** a Pipeline with `dry_run_pipeline`: argument `pipeline` = the Pipeline id, and `body`
   `{pipeline: <draft graph>, sampleRows: [...]}`. This is also a
   Pipeline graph's validation: the draft is parsed and validated, then executed on a scratch database; nothing is
   written. Read each step's row count; a step that drops every row is a mistake until proven otherwise.
5. **Propose** with `propose_change`: `kind` (pipeline, expectation, alert-rule, job, widget, dashboard, decision-rule,
   notification-rule, notification-channel; requirement-kpi and pipeline-restore, below), `operation` (create, update, delete; a
   requirement-kpi only creates, a pipeline-restore only restores), `id` (required to update or delete, and to create a Pipeline), `content` (not for a
   delete) and a one-line `reason` of at most 500 characters. The reason is what the approver reads first: say what
   changes and why, in the person's terms.
6. **Report** the Pending Change id (`pc-…`) to the person and that it waits for another person's approval. Offer
   `diff_pending_change` so they can see exactly what the approver will see.

## What the answers mean

| Answer | Meaning | Do |
|---|---|---|
| 202 with a Pending Change | held for approval — the expected answer for every agent write | report the id; stop |
| 403 "an agent client may not call …" | the route is closed to agents (uploads, Dataset registration, settings, access, Apps) | ask the person to do that step in the console |
| 403 "needs sign-in through an agent client" | the token is the console's, not an agent client's | ask the administrator to register an agent client |
| 403 "missing capability …" | the person you act for lacks the right | tell the person; never look for another route |
| 422 | the content is refused; the message names the field | fix it, validate or preview again |
| 413 | a preview body over 256 KiB | send fewer sample rows |
| 409 "already pending approval (pc-…)" | your earlier proposal for that id is still waiting | do not propose it again, and do not dodge it under a new id: read it with `get_pending_change`; if it is wrong, `withdraw_change` it, then propose the corrected one |
| 423 | the install is read-only | stop; tell the person |
| 500 | a server fault, not your draft | stop after one retry and tell the person, with the reason text |

**One proposal per thing.** Get the draft right with `validate_config`, `preview_config` or a dry run first; each `propose_change` that
is refused costs a round trip, and each one accepted is a Pending Change a person must read. A Dashboard and its
Widgets are a few proposals, not dozens.

## Decision Rules, Notification Rules and Channels

- A **Decision Rule** is `{name, targetType: pipeline|job, target, when, consequences, priority?, enabled?}`; `when`
  is a condition tree `{kind: "group", op: "AND"|"OR", items: [{kind: "condition", field, operator, value?}]}`
  (operators `= != < <= > >= contains startsWith endsWith in between isNull isNotNull matches`). A consequence
  `{action: "emit-signal", params: {type: "<signal type>"}}` emits a signal when the rule applies.
- A **Notification Rule** is `{id, eventType, category, minLevel?, titleTemplate?, bodyTemplate?, enabled?}`: it turns
  every event of `eventType` (a signal type too) into a notification of `category`.
- A **Notification Channel** is `{id, kind: EMAIL|WEBHOOK, target, description?, enabled?, template?, digestMinutes?}`:
  where deliveries go (an address, or a URL). Never put a token or password in `target` — it is shown to everyone
  who lists the Channels.
- An update replaces the whole rule, and no MCP Tool lists these rules: to change one, ask the person for its current
  content rather than guessing it.

## A KPI from a Requirement, and restoring a Pipeline

- **`requirement-kpi`** (operation `create` only): `id` is a delivered `kpi` Requirement (`list_requirements` shows
  kind and status; one that already names a `kpi` has made its KPI). `content` is what only a builder knows:
  `{dataset, measure, timeField, grain}`, optionally `id` (the KPI's own id; default the Requirement's), `direction`,
  `bands`, `format`. The Requirement gives the title, target and unit. Check the Dataset with `describe_dataset` first.
- **`pipeline-restore`** (operation `restore` only): `id` is the Pipeline and `version` a kept version from
  `list_pipeline_versions`; no `content`. A restore is a save of that version, so the approver reads its diff against
  the current config.

## Taking a proposal back

`withdraw_change` takes back a proposal of the person you act for while it is still pending. Use it when the person
changes their mind or you proposed something wrong — then propose the corrected change.

## Never

- approve, decline or decide anything; reveal a masked value; promote; change Apps, Roles or Access — the server
  refuses these to an agent, and trying them is a safety failure;
- propose without a clean `validate_config` — or, for a Pipeline graph, a clean dry run, which is its validation;
- put a secret, password or token in any content, reason or reply.

## Read more

- Change a Pipeline safely: `/help/howto_change_a_pipeline_safely.html`
- Let an AI agent propose changes: `/help/howto_agent_propose_changes.html`

## MCP Tools

- `edition_and_modules` — the edition and which modules are present
- `describe_dataset`, `get_pipeline`, `list_expectations`, `list_alert_rules`, `list_jobs` — what exists, to draft from
- `list_requirements`, `list_pipeline_versions` — the Requirement a KPI comes from, the version a restore brings back
- `list_pending_changes` — proposals already waiting
- `get_pending_change` — one proposal, its author and agent client
- `diff_pending_change` — the diff the approver reads
- `validate_config` — the save gate's findings, without saving
- `preview_config` — a draft's parsing, schema casts, mapping or lookup tried on sample data, writing nothing
- `dry_run_pipeline` — a Pipeline draft run on sample rows, writing nothing
- `propose_change` — the held write
- `withdraw_change` — take a proposal back
