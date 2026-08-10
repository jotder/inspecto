---
type: Feature
title: Jobs
description: Scheduled jobs and run history, with a descriptor-driven authoring form.
resource: inspecto-ui/src/app/modules/admin/jobs/jobs.routes.ts
tags: [feature, jobs, operations, schedules]
timestamp: 2026-06-28T00:00:00Z
---

# Jobs

Route `/jobs` (Operations nav group). Scheduled jobs in **standard** [data-tables](../design-system/data-table.md)
(`autoHeight`); a **job-runs dialog** shows run history. Backed by `JobsService`.

## The authoring form is descriptor-driven (job-parameter-contract steps 10–16, 2026-08-10)

`job-form.dialog` is the `<inspecto-schema-form>` pilot (the renderer's own conventions and traps are the
`angular-ui` skill's Forms row plus the live `/design` gallery), and since the parameter contract shipped it
renders **nothing it invented itself**. Backend counterpart:
[`okf/backend/control-plane/jobs.md`](../../backend/control-plane/jobs.md) *§The parameter contract &
runtime Expressions*. Design of record:
[`job-parameter-contract-plan.md`](../../../archived-documents/plans-archive/job-parameter-contract-plan.md).

* **`paramDeclToSpec` IS the generation contract** (`job-parameter-specs.ts`) — one case per row of the
  plan's §7.4 table, decided by the *declaration*, never guessed from a parameter's name. The old
  `name === 'sql'` multiline sniff is retired: `ParamType.TEXT` says it instead.
* **The type picker comes from `GET /jobs/types`**, so a Job Pack's type appears with no UI change, and it
  **degrades to the declared list** rather than blanking — an empty picker makes the dialog unusable.
* **The free key/value editor is not a normal path.** It renders only when the type publishes no descriptor
  (with a warning naming the type) *or* when the job already carries untyped params — that second condition
  is not in the plan and is required, because hiding it on a legacy job strands those values invisibly while
  `save()` still writes them.
* **Whole-value token picker** — every expression-accepting field offers the runtime vocabulary from
  `GET /jobs/expressions`, filtered by three things: the parameter's type vs the token's `yields`, the Job's
  trigger kind vs `availableIn`, and the declaration's `expressions` flag. ⚠ **`expressions` is read from the
  DESCRIPTOR, not from the `AttributeSpec`** — `AttributeSpec`'s unions are server-published (a Findings
  section is authored as one), so giving a Jobs-only policy flag a spec home would force `FindingsSpec` to be
  widened with it. The renderer stays domain-agnostic: it takes tokens, not a vocabulary.
* ⚠ **A token is exempt from the field's `pattern`** (`[tokenSyntax]`), or the picker is decorative — every
  field it matters on (date, instant, email) carries a preset pattern, so the form would mark invalid the
  value it had just authored. The engine's own rule is the same: an Expression is validated *after*
  resolution.
* ⚠ **On a `multi` field the token replaces every entry.** A list is one value, and the engine evaluates a
  value only when it is a token in its entirety, so a token beside other chips silently never resolves. A
  2+-entry list is therefore still held to the literal format, deliberately.
* ⚠ **INTEGER/DECIMAL parameters have no reachable token** — `widgetFor` maps them to a native
  `type="number"` input, which shows a `$`-token as blank while the control holds it, so the picker is
  withheld there. `$now.epoch_seconds`/`$now.epoch_millis` are consequently UI-unreachable (BACKLOG §4).

## Gotchas that cost a cycle each

* **A job's write body is FLAT snake_case**, and the server absorbs unknown keys as *parameters* rather than
  rejecting them — so every UI-authored event trigger and declared parameter was silently dropped from the
  day the endpoint landed. Fixed at the `jobToWire`/`jobFromWire` seam; pinned by `ControlApiJobCrudTest`
  server-side and mirrored in the mock independently. A camelCase key is not a validation error, it is an
  untriggered job.
* **`JobView` must carry `onSignal`**, or a signal job reads as "manual" against a real backend while
  looking right offline — the same mock-is-nicer-than-the-server shape as the write-body bug.
* **Subscribe to the type picker on the FormGroup, never the `type` control** — reassigning the schema
  form's `specs` rebuilds every control, so a subscription on the old control instance goes silent and
  switching type stops reloading parameters. Re-seed live values after the swap.
* **`on_signal` is free text with a placeholder, not an autocomplete.** `GET /signals` is the ledger of
  *observed* signals, not a catalog of declarable types, and a job must stay armable for a signal that has
  never fired. Any future catalog must suggest, never constrain.
* **The `when:` guard has no author-time validation.** `WhenGuard` is a package-private *runtime* evaluator
  with no published grammar, so a bad guard is caught fail-closed at fire time. Hand-rolling a second
  grammar in TypeScript is the failure mode to avoid.
