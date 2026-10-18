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
(`autoHeight`); a **job-runs dialog** shows run history. Backed by `JobsService`. Since 2026-10-11 (`5158ebe94`)
`JobsService.run(runId)` / `JobRunById` type `GET /jobs/runs/{runId}` (fields `startedAt` / `finishedAt`, unlike the
list row `JobRunRow`'s `startTime` / `endTime`). The run-detail dialog (`job-run-detail.dialog.ts`) opens from the list row, then reads `JobsService.run(runId)` itself and
fills `derivedFrom` (the one field only the by-id read carries) when it returns; a failed read keeps the row and shows nothing
(`JOB-RUN-DETAIL-DERIVEDFROM-1`, shipped 2026-10-11).

## System jobs are badged and read-only (DUCKLE-C1, 2026-09-25)

`GET /jobs` carries `system: true` on a platform-armed job (today only `system.freshness-sweep`, derived
from the Alert Rules that declare `maximumAge`). `JobView.system` mirrors it; the list's Job cell shows a
neutral **System** pill (`statusBadgeHtml('system', 'System')`, the name itself set as text, never HTML), and
the row offers the **Run verbs only** (Run now, Run with parameters…). That reflects the backend, not a UI policy: `JobRoutes.refuseSystemJob` 409s
`PUT`/`DELETE /jobs/{name}` and `POST …/enable|disable|reschedule` on a system job, while `…/trigger` stays
open. ⚠ **`GET /jobs/{name}` does not carry the flag**, so the detail panel cannot tell on its own; it is
safe today only because the panel is always mounted `embedded`, and its write buttons render only in the
full-page header, which no route reaches. A future full-page detail needs the flag served there first.

## Run with parameters (TELCO-FRAUD-DEMO-DATE-1, 2026-10-14; row closed 2026-10-18)

Row action and detail-pane button **Run with parameters…** (2026-10-17: the embedded side panel's compact header now
carries it beside the close X, gated on `LensService.canOperateRuns()`; the full-page header keeps its button under the
same gate. The panel has no Run-now button, so this is its only run verb. ⚠ the list row action is still ungated by
lens, so a Business-lens subject sees it on the row but not in the panel) open `JobRunParamsDialog`
(`job-run-params.dialog.ts`). It reads the type's declarations (`GET /jobs/types/{id}`) plus the Job's configured
values (`GET /jobs/{name}`), renders them through the same `paramDeclsToSpecs` → `<inspecto-schema-form>` path as
the authoring form, and posts `POST /jobs/{name}/trigger {"params":{…}}` with **only the dirty fields** (a `multi`
value is joined to CSV, the resolver's wire form). A refusal (422) shows inline and the dialog stays open; **The trigger route never 422s on a bad typed value** (as built: `JobRoutes.triggerJob` only parses `params` to strings; `ParameterResolver` type/required/expression failures are raised inside the async Run and recorded as a `REJECTED` run with the reason, after the 202 — so a bad `as_of` shows in the run history, not inline). The inline 422 is therefore reachable only for other refusals (module disabled, malformed body); the dialog spec pins the inline display with a field-naming message. success
reuses the Run-now toast + reload. Same gate as Run now (the route's `canOperateRuns`; the UI adds none).
Deliberately **no free-form key=value rows**: a Job's contract is its declaration, and a type with none shows
"declares no runtime parameters".

**Config-only declarations are not offered (2026-10-17).** The dialog lists only `!configOnly` declarations. A
`ParameterDecl` is `configOnly` when its Job reads the key from the saved config (`cfg.opt/require/params()`), never
from `ctx.params()`, so a trigger arg would be silently ignored (live drive: `fraud_irsf` Sink dataset, a `report`
Job's `format`/`out_dir`). The flag is served on `GET /jobs/types/{id}` (`configOnly`) and the authoring form still
renders those fields. As built: `enrich`, `report`, `maintenance`, `pipeline`, `kpi.completeness`,
`space.comparison`, `la.index.build`, `caserule.evaluate`, `risk.score`, `screening.run`, `objects.analytics` are
wholly config-only; `sql.template` has `sql`/`sink_dataset`/`sources` config-only (its scanned `$name` tokens and
incremental `day` stay runtime); `anomaly.score` `model` and `recon.run` `reconciliation` are config-only while
`as_of` / `day` are runtime. Runtime (honoured via `ctx.params()`): `alert.evaluate`, `incident.open`,
`expectation.evaluate`, `mail.send`, `sample.hello`, `consignment.process`, `publish.postgres`,
`objectstore.export`. A new Job Type's declaration must set `configOnly` for any key its Job does not read from
`ctx.params()` (pinned for the built-ins by `ConfigOnlyParametersTest`). `sources` is labelled **Input Datasets**.
`alert.evaluate`'s `rule` evaluates ONLY that rule (`AlertAccess.evaluateRule`; unknown name fails the Run).

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
  server-side. A camelCase key is not a validation error, it is an
  untriggered job.
* **`JobView` must carry `onSignal`**, or a signal job reads as "manual" against the backend — the same
  shape as the write-body bug, and one the deleted offline mock used to hide by being more permissive.
* **Subscribe to the type picker on the FormGroup, never the `type` control** — reassigning the schema
  form's `specs` rebuilds every control, so a subscription on the old control instance goes silent and
  switching type stops reloading parameters. Re-seed live values after the swap.
* **`on_signal` is free text with a placeholder, not an autocomplete.** `GET /signals` is the ledger of
  *observed* signals, not a catalog of declarable types, and a job must stay armable for a signal that has
  never fired. Any future catalog must suggest, never constrain.
* **The `when:` guard has no author-time validation.** `WhenGuard` is a package-private *runtime* evaluator
  with no published grammar, so a bad guard is caught fail-closed at fire time. Hand-rolling a second
  grammar in TypeScript is the failure mode to avoid.
