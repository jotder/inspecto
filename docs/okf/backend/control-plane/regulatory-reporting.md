---
type: Concept
title: Regulatory Reporting — regulator-format reports from Cases, four-eyes, file drop
description: A regulator-format report (SAR / STR shaped) rendered once from a Report Template over a Case or Incident, its member Incidents and evidence, approved by a different person, then submitted to a jailed file drop; the AUDIT rows are the submission log.
resource: features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/RegulatoryReportRoutes.java
tags: [control-plane, regulatory-reporting, maker-checker, four-eyes, sar, case, incident, add-on]
timestamp: 2026-10-09T00:00:00Z
---

# Regulatory Reporting

A **Regulatory Report** (`REGULATORY-REPORTING-1`, prerequisite of `PACK-AML-1`) is a regulator-format document
assembled from ONE Case or Incident and approved by a different person before it is **submitted**: written once to a
file-drop folder. It is the optional **`inspecto-regulatory-reporting`** add-on (manifest id `regulatory-reporting`,
feature id `regulatoryReporting`, package `com.gamma.regreporting`, `requires: ops`), bundled from Professional up; on
an install without it every `/regulatory-reports*` path answers 503 naming the module (synthesised from its manifest;
`NoRegulatoryReportingShipsInThePersonalBuildTest`). No real regulator schema is claimed — the one built-in template,
`sample-sar`, is illustrative; real formats are pack content.

Code (`features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/`): `ReportTemplate` (model, parse,
catalog), `ReportRenderer` (pure render + source fingerprint), `RegulatoryReports` (record + signed store + audit),
`FileDrop` (delivery), `RegulatoryReportRoutes` (HTTP). The design and its decisions D-RR1 … D-RR12 are recorded in the
archived plan `docs/archived-documents/plans-archive/regulatory-reporting-addon-plan.md`.

## Report Template
A TOON document — built-ins on the module classpath (`META-INF/inspecto/regulatory-templates/<id>.toon`, listed in
`ReportTemplate.BUILT_INS`), a Space's own in its config root under `regulatory-report-templates/<id>.toon` (the file
name is the id; the Space's wins on a clash; a Space override that does not load is NOT replaced by the built-in).

```
id: sample-sar
title: Sample suspicious activity report
format: xml                                  (xml | csv | json)
rootElement: suspiciousActivityReport        (required for xml; wraps json when given)
schema: sample-sar.xsd                       (xml only, optional: the XSD a draft must validate against)
delivery:
  kind: file-drop                            (the only kind built)
  dir: regulatory-submissions/sample-sar     (jailed like the report Job's out_dir)
fields[N]{name,source,required,maxLength}:
  reportId,report.id,true,64
  incident/title,incidents.title,false,400   (group/leaf: one repeating group per list item)
  narrative,input.narrative,true,20000       (input.<key>: the maker types it)
  schema,"const:inspecto-sample-sar/1",true,0
```

The **report context** a source path walks: `report {id, createdAt, author}`, `subject` (the Case / Incident's object
view, timestamps ISO-8601, `impact` included), `incidents` (a Case's `CONTAINS` members, oldest first; the Incident
itself when raised from one), `evidence` (`ATTACHMENT` notes of the subject and every member: `objectId, name, uri,
contentType, caption, author, createdAt`) and `input`. Through a list a path maps over the items. A template that does
not validate is listed by `GET /regulatory-reports/templates` with ALL its problems and can draft nothing.

**Rendering refuses, never patches** (`ReportRenderer`): a required field that resolves empty, a value over its
`maxLength` (never truncated), a source landing on an object, a control character XML 1.0 cannot carry, or a document
over 1 MiB → 422 listing every problem. XML escapes text; element names are ASCII NCNames checked at load. CSV is one
header row plus one data row (lists joined `"; "`, RFC 4180 quoting) with formula-leading cells (`= + - @`, tab, CR)
prefixed `'`.

## Lifecycle and gates
`draft → pending → approved → submitted | failed`, plus `declined`, `expired` (a draft or pending report past
`expiresAt`, from `PendingChanges.expiryHours`) and `invalid` (MAC fails). `failed` → `retry` re-submits the SAME bytes
to the SAME file name. `rejected` is reserved for a regulator's refusal (nothing records one yet).

| Route | Gate | Does |
|---|---|---|
| `GET /regulatory-reports/templates` | reader | templates + load problems |
| `GET /regulatory-reports[?status=&template=&caseId=&incidentId=]` | reader | newest first, capped 500, `truncated` + true `total`; no content |
| `GET /regulatory-reports/{id}` | reader | the record with its content; live `sourceChanged` (draft / pending) and `approverCheck` (pending) |
| `PUT /regulatory-reports/templates/{id}` | `canApproveChanges` | author (create / replace) a Space template from its JSON body; a Space template of a built-in id overrides it |
| `DELETE /regulatory-reports/templates/{id}` | `canApproveChanges` | remove a Space template; 409 if built-in-only or still referenced by a report |
| `POST /regulatory-reports` | `canWorkIncidents` | draft `{template, caseId|incidentId, inputs, reason}` |
| `POST /regulatory-reports/{id}/request-approval` | `canWorkIncidents` | draft → pending (`requestedBy` becomes a maker) |
| `POST /regulatory-reports/{id}/approve` | `canApproveChanges` | pending → approved → submitted / failed |
| `POST /regulatory-reports/{id}/decline` | `canApproveChanges` | pending → declined |
| `POST /regulatory-reports/{id}/retry` | `canApproveChanges` | failed → submitted / failed |
| `GET /regulatory-reports/{id}/verify` | `canWorkIncidents` or `canApproveChanges` | re-hash the delivered file and its `.sha256` sidecar (2026-10-18) |

*Reader* = `canWorkIncidents` OR `canApproveChanges` (checked in the handler) AND the subject visible: a report is
visible exactly when its Case / Incident is (`ObjectRoutes.visibleTo`: SEC-7d scope + row policy); invisible reads 404.

**Draft gates, in order:** capability → write root 503 → unknown body key 422 → template unknown / not loading 422 → not
exactly one of `caseId` / `incidentId` 422 → an input the template does not read, non-scalar, or over 20 000 chars 422 →
no object engine 503 → subject absent or out of scope 404 → a member Incident out of the caller's scope **403** (a
partial filing is refused, never silently thinned) → drop directory refused by the path jail 422 → render refused 422
→ saved `draft`.

**Decide gates, in order** (the Action Request pattern): capability → authenticated Subject 403 → write root 503 → a
body key other than `reason` 422 → unsafe id 422 → absent / invisible 404 → MAC fails 409 → wrong status (incl. just
expired) 409 → **a maker deciding 403** (the author AND whoever requested approval) → not on the approver roster
(`ApproverRoster.requireOnRoster`) 403. ALWAYS four-eyes; never `PendingChanges.hold` (a report is not config, and a
second hold would need two approvals for one filing).

## What is fixed, and what the approver sees
The content is rendered ONCE, at draft, and stored with `contentSha256`, the resolved absolute drop directory and the
file name `<id>.<format>`; nothing a later request carries can change them, and the MAC covers them. Submission
re-hashes the stored content against `contentSha256` before writing. The detail view's `sourceChanged` compares the
live context fingerprint (`ReportRenderer.sourceFingerprint`: the context less `report` and `input`) with the one
recorded at render, so an approver sees a Case edited (or an attachment added) since — the pinned content is never
re-rendered; a stale report is declined and redrafted.

## Delivery and the submission log
`FileDrop.deliver` re-jails the pinned directory (`PathJail.requireJobPathUnderAny` over `PathJail.allowedRoots()`;
the roots may have narrowed since the draft), writes a temp file in the same directory and moves it atomically. A file
is never overwritten: one already there with the same SHA-256 is the same submission (`alreadyPresent: true`), any
other refuses → `failed` with `lastError`. Every step emits a hash-chained AUDIT row (`regulatory-report.drafted |
approval-requested | no-eligible-approver | approved | declined | submitted | submission-failed | retried | expired`)
carrying the id, template, subject, `contentSha256` and file name — never the content or inputs (a SAR is
confidential). `regulatory-report.submitted` rows plus `GET /regulatory-reports?status=submitted` are the submission
log.

## Storage
One JSON document per report at `<write-root>/regulatory-reports/<id>.json` (`rr-<yyyyMMddHHmmss>-<6hex>`), atomic,
jailed, fail closed on an unreadable document, signed with the Space's Pending Change key under domain
`regulatory-report` (`PendingChanges.domainMac`). The directory is reserved against import (`ReservedConfigPaths`),
skipped by Space export (`BundleExporter`) and by backup (`BackupTask.secret`): operational and confidential; the
delivered file and the audit chain are the durable trail.

## The SPA pane (`/regulatory-reports`)
`inspecto-ui/src/app/modules/admin/regulatory-reports/` over `RegulatoryReportsService` (`inspecto/api/regulatory-reports.service.ts`);
nav item *Operations ▸ Regulatory Reports*, shown only when `/bootstrap` reports `features.regulatoryReporting` (the module
registered its routes). The Action Requests inbox pattern: a list with **Waiting** (draft, pending and failed — what a
person still has to act on, filtered client-side from the capped list), **Submitted** (`?status=submitted`, the submission
log) and **All**; selecting a row opens its detail — template, subject link, format, drop folder, file name, SHA-256,
who sent it for approval / approved / declined, the submission, and the exact content in a scrollable `<pre>`. Warnings
in place: `sourceChanged` ("decline it and draft again"), `approverCheck: none-eligible`, an `invalid` record.
Actions by capability (the server decides): **New report** and **Send for approval** with `canWorkIncidents`; **Approve
and submit** / **Decline** on a pending report and **Retry the submission** on a failed one with `canApproveChanges`;
the server's refusal (four-eyes, a moved status, integrity) is shown in place, never only toasted. **New report** is a
dirty-guarded dialog (`regulatory-report-draft.dialog.ts`): the Report Template and the Case / Incident through
`<inspecto-option-picker>` (subjects from `GET /objects?type=`), one textarea per `input.*` key the template reads —
required and max length derived from its fields (`requiredInputs`, `inputMaxLength`), labelled as words (`inputLabel`) —
and the server's render refusal shown in the dialog, which stays open. Picker errors appear only after a submit attempt.
**Report Templates** section (same pane): the templates with origin, and - with `canApproveChanges` - **New template** / Edit / Delete (a built-in is editable, saved as the Space override, but not deletable). `report-template.dialog.ts` edits the template as JSON beside its id (create only); client-side JSON check, then the server's 422 / 403 / 409 text shown verbatim.

## Demo rehearsal 2026-10-14 (`REGULATORY-REPORTING-1`, no code row)
A fresh `package.ps1 -Edition Advanced -DemoAuth -Ui gamma -NoBakeDemo` bundle (the module jar is in `modules.list`;
`/bootstrap` reports `features.regulatoryReporting: true`), the `telco-fraud` Space (`node tools/seed-telco-fraud-demo.mjs`, 15 Jobs
triggered, `POST /alerts/evaluate` -> 30 CRITICAL Incidents), one synthetic Case (`POST /objects {type: CASE, links: [{to, relationship: CONTAINS}]}`
over three staff-fraud Incidents, two `ATTACHMENT` notes, an `impact` set with `PUT /objects/{id}/impact {impact: {...}}`), driven as `fm.analyst`
(maker, `operations`), `admin` (approver: seed `admin;super` holds `canApproveChanges`; **`fm.manager` (`power`) does NOT**) and `ra.viewer`.
The Space switcher resets to the first Space on every sign-in: pick the Space again, then open `/regulatory-reports`.
**GO for the presenter who follows this list; no schema validation, sealing, schedule or masking exists, so do not claim them.**

| # | Beat | Result | Evidence |
|---|---|---|---|
| 1 | Module present, pane in the nav for makers | **PASS** | `modules.list` has `inspecto-regulatory-reporting.jar`; pane lists the built-in `sample-sar` |
| 2 | Author a Report Template (CSV / JSON / XML) | **PASS** | `PUT /regulatory-reports/templates/{id}` as `admin` -> 200, `origin: space`; a viewer gets 403 `canApproveChanges` |
| 3 | Draft from a Case with its Incidents and evidence | **PASS** | UI dialog (Template + Case pickers, one textarea per `input.*`; client message *The template requires Narrative*) and API; 3 Incidents + 2 attachments rendered |
| 4 | XML output | **PASS** well-formed (parsed); **N/A** schema: no XSD ships, `sample-sar` claims no regulator schema | text escaped (`&lt;b&gt;&amp;`) |
| 5 | CSV output | **PASS** 2 rows x 8 columns (RFC 4180); lists joined `"; "`; a leading `=` cell is written `'=HYPERLINK(...)` | |
| 6 | JSON output | **PASS** parses; repeating groups become arrays | |
| 7 | Four-eyes approve -> submit | **PASS** | analyst Send for approval; `fm.analyst` approve 403; `admin` Approve and submit -> SUBMITTED; file on disk SHA-256 equals the pane's `contentSha256` |
| 8 | Sealed / signed output | **NOT BUILT** (listed under *Deliberately not built*); the record is MAC-signed, the delivered file is not | `contentSha256` is the only seal; the audit `submitted` row carries it |
| 9 | Regenerate idempotent | **PARTIAL, by design**: a second draft of the same Case + inputs has the same `sourceFingerprint` but a different SHA (`report.id` and `createdAt` are in the document); `retry` re-sends the SAME bytes to the SAME file name | say "pinned", not "reproducible" |
| 10 | Amended report after the Case changes | **PASS as a procedure**: an attachment added while PENDING sets `sourceChanged: true`; `admin` Decline, the maker drafts again and the new report carries the late evidence. There is no in-place *amend* | |
| 11 | Field-level masking for a non-privileged persona | **NOT BUILT / consistent**: `ra.viewer` cannot read reports at all (list and detail 403 `canWorkIncidents or canApproveChanges`, no nav item, in-pane refusal text); but the Case / Incident view itself is unmasked for the viewer (`GET /objects/{id}` returns the MSISDN key), so a report adds no exposure | open decision, backlog |
| 12 | Schedule | **NOT BUILT** (a report is always drafted by a person) | |
| 13 | Audit trail | **PASS** | hash-chained AUDIT rows `drafted`, `approval-requested`, `approved`, `submitted`, `declined`, template `updated`; `GET /audit/verify` -> `ok: true, checked: 74`; content and inputs are never in the rows |
| 14 | Unhappy paths | **PASS** (each a clear 4xx) | required `narrative` empty 422; unknown input key 422 naming what the template reads; both `caseId` and `incidentId` 422; unknown Case 404; unknown template 422; input over 20000 chars 422; control character in XML 422; required source that resolves empty (`subject.nope`) 422 at draft; source root not in `[incidents, input, report, subject, evidence]` 422 at template save; `delivery.dir` outside the jail 403; DELETE of a template used by a report 409, of a built-in with no override 409; viewer draft 403; approve of a draft 409 |
| 15 | Huge Case | **PASS at unit level** (2026-10-14): the 1 MiB document cap (`ReportRenderer.MAX_BYTES`) refuses, never truncates; a real 1 MiB Case was not driven | |

**Re-driven 2026-10-14 after the fixes** on a fresh Enterprise bundle (`telco-fraud`, 30 Incidents, one Case over 3 of them with attachments and an `impact`): XML (escaped `&lt;b&gt;` `&amp;`), CSV (formula cell prefixed) and JSON (parses) each drafted by `fm.analyst`, approved by `admin` -> `submitted`, the file on disk hashing to `contentSha256`, no absolute path anywhere in the response (`delivery.dir` is `regulatory-submissions/<id>`); a late attachment on a PENDING report -> `sourceChanged: true`, decline -> `declined`, the redraft carries it; the maker approving 403; `ra.viewer` 403 (pane shows the refusal alone, no *No Report Templates.*); route-level AUDIT rows now `regulatory-report.declined` and `regulatory-report.approval_requested`; `GET /audit/verify` `ok: true`. Still not claimable (then): schema validation, sealing, schedule, masking - see the decisions below. **GO to present the add-on with that scope stated** (decisions below are the ways to widen it).

Small defects found, all cosmetic - **three fixed 2026-10-14**: (1) the route-level AUDIT row of a decline / retry / send-for-approval read
`regulatory-report.created` (the POST default in `AuditTrail.classify`); it now reads `regulatory-report.declined | retried | approval_requested`
(the same verbs fix the other four-eyes inboxes); (2) a response no longer carries the server's absolute drop path - `delivery.dir` is the folder as
the template wrote it (`dirLabel`, stored beside the pinned absolute `dir` the delivery and the MAC use) and `submission.file` is the file name
(`RegulatoryReports.withoutServerPaths`; `lastError` text can still name a path); (3) a reader the server refuses sees the refusal, not
*No Report Templates.* above it. Beat 15 is now covered at unit level (`ReportRendererTest.aDocumentOverTheCapIsRefusedNotTruncated`: over the
1 MiB cap refuses with the cap in the message, half the cap renders whole). Not fixable: a required template field that names a Case
attribute that does not exist saves (it cannot be known statically) and fails at draft with a 422 naming it.

**Decisions taken 2026-10-18** (owed since the rehearsal; confirmed by the operator the same day; each the
recommendation, per the decide-when-away rule):

1. **XSD validation of XML reports** - BUILT (below).
2. **Sealing the delivered file** - BUILT as option (a): a `.sha256` sidecar plus a verify route (below). A detached
   HMAC (verifiable only by us) is not built; XMLDSig / CMS with an operator-supplied key waits for a named regulator
   that requires it.
3. **Masking report content** - DECLARED unmasked by design: a filing needs the real identifiers, the approver must see
   exactly what is filed, and `ra.viewer` cannot read a report at all. Closed.
4. **In-place amend** - NO: decline and redraft keeps one immutable document per filing.
5. **Scheduled drafting** - NO: a person drafts, by design.
6. **Acknowledgement ingest / live SFTP or HTTPS delivery** - left until a regulator is named (its receipt format and
   credentials are the input).
7. **Reproducible regenerate** - NO: say "pinned", not "reproducible".

## XSD validation (built 2026-10-18)
An xml template may name `schema: <file>.xsd` (`ReportTemplate.SCHEMA_FILE`; refused on csv / json). `SchemaCheck`
finds it beside the Space's templates (`<config root>/regulatory-report-templates/<file>`) first, then beside the
built-ins on the classpath, so a Space can tighten a built-in's schema by dropping a file of the same name. The factory
runs with secure processing and `ACCESS_EXTERNAL_SCHEMA` / `ACCESS_EXTERNAL_DTD` empty: one self-contained file, no
`xs:import` by URL. Gates: `PUT /regulatory-reports/templates/{id}` 422 when the schema is absent or does not compile;
the draft renders, then validates, and refuses 422 `does not validate against schema '<file>'` with every violation as
`line L:C message` - nothing is saved. The built-in `sample-sar` names the shipped synthetic `sample-sar.xsd` (element
order, required elements, `preparedAt` an `xs:dateTime`, `amountSuspected` an `xs:decimal`, `currency` three capitals).
It follows no real regulator: a real XSD is pack content from the operator (`PACK-AML-1`,
`REGULATORY-SAR-STR-TEMPLATES-1`).

## Sealing: sidecar and verify (built 2026-10-18)
`FileDrop.deliver` writes `<file>.sha256` beside the file, in `sha256sum` format (`<hex>  <file>` and a newline), so a receiver
checks it with a stock tool. Written once, atomically, never overwritten (a different sidecar already present fails the
submission); an idempotent retry after a crash between the two writes adds the missing sidecar.
`GET /regulatory-reports/{id}/verify` (reader gate and visibility as the detail route; not `submitted` 409; a malformed
id 422; unknown 404) re-jails the pinned folder, re-hashes the file and reads the sidecar against `contentSha256`:
`{file, expectedSha256, actualSha256, fileState, sidecar, ok, integrity}`, each state `match | mismatch | missing`,
`ok` only when both match; `integrity` is the record's MAC. A read: no AUDIT row. Names the file, never the absolute
path. SPA: *Verify the delivered file* on a submitted report shows the result in place. A sidecar seals against
accidental change and an outside-the-system edit noticed later; it is not a signature (anyone who can rewrite the file
can rewrite the sidecar) - that is XMLDSig / CMS, still demand-gated.

## Deliberately not built (see `docs/BACKLOG.md` `REGULATORY-REPORTING-RESIDUALS-1`)
Regulator acknowledgement ingest (`rejected`, receipts); live regulator delivery (SFTP / HTTPS through a Connection);
nested repeating groups; signing the delivered file (XMLDSig / CMS); a dedicated filing capability (RBAC capability
contribution is declined, P1-D3).

## Template authoring (shipped 2026-10-11)
`PUT` / `DELETE /regulatory-reports/templates/{id}` write `<write root>/regulatory-report-templates/<id>.toon` (the
Space store `ReportTemplate.load` already reads; `ConfigCodec.toToon`, `AtomicFiles`). Gates, in order: capability
(`canApproveChanges` - the oversight capability reused; a template decides what is filed and where it is dropped, and no
filing capability exists) -> write root 503 -> unsafe id / id differing from the path / `ReportTemplate.parse` problems
422 (all named) -> file or `delivery.dir` outside the jail 403 -> DELETE: no Space template 404, built-in with no
override 409, referenced by any report 409 -> atomic write / delete under the store lock. AUDIT rows
`regulatory-report-template.created|updated|deleted` carry the id only. Not routed through `PendingChanges.hold`
(rows in `ConfigWriteFunnelTest` `NO_SAVE_GATE` / `NO_HOLD`: the kind is not governable).
