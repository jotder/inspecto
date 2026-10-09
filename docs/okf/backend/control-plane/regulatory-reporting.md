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
| `POST /regulatory-reports` | `canWorkIncidents` | draft `{template, caseId|incidentId, inputs, reason}` |
| `POST /regulatory-reports/{id}/request-approval` | `canWorkIncidents` | draft → pending (`requestedBy` becomes a maker) |
| `POST /regulatory-reports/{id}/approve` | `canApproveChanges` | pending → approved → submitted / failed |
| `POST /regulatory-reports/{id}/decline` | `canApproveChanges` | pending → declined |
| `POST /regulatory-reports/{id}/retry` | `canApproveChanges` | failed → submitted / failed |

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

## Deliberately not built (see `docs/BACKLOG.md` `REGULATORY-REPORTING-1`)
Template authoring routes and the SPA pane; regulator acknowledgement ingest (`rejected`, receipts); live regulator
delivery (SFTP / HTTPS through a Connection); nested repeating groups; sealing / signing the delivered file; a
dedicated filing capability (RBAC capability contribution is declined, P1-D3).
