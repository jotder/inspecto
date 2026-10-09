# Regulatory Reporting add-on — design (`REGULATORY-REPORTING-1`)

> Status: ✅ **MVP SHIPPED + ARCHIVED 2026-10-09** on lane branch `claude/lane-reg-reporting-3f96a9`. Prerequisite of
> `PACK-AML-1`. As-built truth: `docs/okf/backend/control-plane/regulatory-reporting.md`; what §5 deferred is the
> remainder of row `REGULATORY-REPORTING-1` in `docs/BACKLOG.md`. Operator away: every decision below was taken by
> the lane and is recorded with its reason (D-RR1 … D-RR12); none is irreversible. Provenance only.

## 1. What it is
A **Regulatory Report** is a regulator-format document (a SAR / STR is the motivating case) assembled from one Case or
Incident — its fields, its member Incidents and its evidence (attachment notes) — plus the values a person types in
(the narrative). A **Report Template** says which output fields there are, where each one's value comes from, and how
the document is rendered (XML, CSV or JSON). A different person must approve the report before it is **submitted**:
written once, atomically, to the template's file-drop folder. Every step is a hash-chained AUDIT row; the submitted
rows are the **submission log**.

No real regulator schema is claimed: the one built-in template (`sample-sar`) is illustrative. A pack (`PACK-AML-1`)
ships real templates as Space content, with zero platform edits.

## 2. Grounding (what exists and is reused)
- **Action Requests** (`features/inspecto-action-requests`) is the blueprint: a raise-from-Incident/Case record, its
  own MAC'd JSON store (`PendingChanges.domainMac`, domain-separated), mandatory four-eyes decide gates
  (`ApproverRoster.requireOnRoster`, `ApproverCheck`, `canApproveChanges`), expiry from `PendingChanges.expiryHours`.
- **Pending Change hold** (`PendingChanges.hold` + `REPLAYABLE`) is for config writes; it is policy-driven (off by
  default) and replays an HTTP request. Not used — see D-RR4.
- **Cases / Incidents** are `OperationalObject`s in `inspecto-ops`; a Case `CONTAINS` its Incidents; evidence is
  `ATTACHMENT` notes (pointer: name, contentType, uri; no bytes). Visibility is `ObjectRoutes.visibleTo` (SEC-7d + row
  policy). No Case export or renderer exists.
- **Audit chain**: any `EventType.AUDIT` emit through `EventLog.current()` is hash-chained (`AuditChain`).
- **Path jail**: `PathJail.requireJobPathUnderAny(PathJail.allowedRoots(), configRoot, dir, field)` — the `report`
  Job's `out_dir` rule.

## 3. Decisions (lane, 2026-10-09 — operator away; all reversible)
| # | Decision | Reason |
|---|---|---|
| D-RR1 | New optional module `features/inspecto-regulatory-reporting` (artifactId `inspecto-regulatory-reporting`, manifest id `regulatory-reporting`, feature id `regulatoryReporting`, package `com.gamma.regreporting`), bundled Professional and up, `requires: ops` | It passes the offering-map test (distinct compliance buyer; base coherent without it; separable; carries egress risk). It reads Cases, Incidents and notes far beyond the `LinkedSubjectProvider` seam, so it depends on `inspecto-ops` at compile scope exactly as `inspecto-case-management` does |
| D-RR2 | A **Report Template** is a TOON document: built-ins on the module classpath (`META-INF/inspecto/regulatory-templates/`), Space templates in the Space config root under `regulatory-report-templates/<id>.toon`, Space wins on an id clash. Read-only through the API in the MVP | Packs ship templates as content (no platform edit, binding rule of the offering map). An authoring route would add a config-writing route to the maker-checker funnel for little MVP value |
| D-RR3 | Template model: `fields` is a flat table `{name, source, required, maxLength}`; `source` is a dotted path into the **report context** (`report`, `subject`, `incidents`, `evidence`, `input`) or `const:<text>`; a field named `group/leaf` whose sources share one list root (`incidents` or `evidence`) renders as a **repeating group**; `input.<key>` names the maker's typed values (only referenced keys are accepted) | Covers a SAR-shaped document (header, subject, repeating incidents and evidence, narrative) with a table TOON represents natively; no expression language |
| D-RR4 | Maker-checker = the **Action Requests pattern** (own MAC'd record + mandatory four-eyes), NOT `PendingChanges.hold` | A report is not config; the hold is policy-optional and replays HTTP. Holding it as a Pending Change as well would need two approvals for one filing — the same reasoning Action Requests records |
| D-RR5 | Lifecycle `draft → pending → approved → submitted | failed`, plus `declined` (the checker refused), `expired` (undecided before `expiresAt`) and `invalid` (MAC fails). `failed` can be retried (same bytes, same file name). `rejected` is RESERVED for a regulator's refusal (no acknowledgement ingest yet) | The task named "rejected"; the platform's word for a checker's refusal is *declined* (Pending Changes, Action Requests), so the two meanings stay distinct |
| D-RR6 | The content is **rendered once, at draft**, and stored on the record with its SHA-256; request-approval pins it; approve submits exactly those bytes. The detail view also reports `sourceChanged` (the live context fingerprint vs the one rendered) so the approver sees a Case edited since | Approve what you read (the `ApprovalFingerprint` principle) without silently re-rendering |
| D-RR7 | Four-eyes excludes every maker: the author AND whoever requested approval | A second maker must not be able to launder their own report through a colleague's draft |
| D-RR8 | Capabilities reuse `canWorkIncidents` (draft, request approval) and `canApproveChanges` (approve, decline, retry); reads need either plus the subject's visibility. No new capability | The RBAC capability contribution is declined (P1-D3); a dedicated filing capability is a follow-up if an operator asks |
| D-RR9 | Delivery = **file drop** only: `delivery.dir` in the template, jailed like the `report` Job's `out_dir` (relative resolves against the Space config root), file `<reportId>.<ext>`, temp + atomic move, never overwritten (an existing file with the same SHA-256 counts as delivered). The resolved directory is pinned on the record at draft and shown to the approver | "File drop first; no live regulator APIs"; pinning stops a template edit from redirecting an approved filing |
| D-RR10 | Submission log = the AUDIT rows `regulatory-report.submitted` (report id, template, subject, SHA-256, file name; never the content) plus the record history; `GET /regulatory-reports?status=submitted` is its read | One hash-chained trail; no second log to keep consistent |
| D-RR11 | The record store `regulatory-reports/` is reserved against import, skipped by Space export and by backup | Same as Action Requests: operational, confidential (tipping-off), MAC'd with the Space key. The delivered file and the audit chain are the durable trail |
| D-RR12 | CSV output neutralises formula-leading cells (`= + - @`, tab, CR) with a leading `'`; XML escapes text and restricts element names; rendered size cap 1 MiB; a required field that resolves empty or a value over `maxLength` refuses the draft (422) — never truncated | A filing silently truncated or carrying a spreadsheet formula is worse than a refusal |

## 4. Surface (all under the module; Installed → Enabled → Permitted gates as every add-on)
| Route | Gate | Does |
|---|---|---|
| `GET /regulatory-reports/templates` | reader | templates with their diagnostics |
| `GET /regulatory-reports[?status=&caseId=&incidentId=&template=]` | reader | newest first, capped 500, `truncated` + true `total` |
| `GET /regulatory-reports/{id}` | reader | the record with its rendered content, `sourceChanged`, `approverCheck` |
| `POST /regulatory-reports` | `canWorkIncidents` | draft: `{template, caseId|incidentId, inputs, reason}` |
| `POST /regulatory-reports/{id}/request-approval` | `canWorkIncidents` | draft → pending |
| `POST /regulatory-reports/{id}/approve` | `canApproveChanges` | pending → approved → submitted / failed |
| `POST /regulatory-reports/{id}/decline` | `canApproveChanges` | pending → declined |
| `POST /regulatory-reports/{id}/retry` | `canApproveChanges` | failed → submitted / failed |

## 5. Deliberately not in the MVP
Template authoring API and SPA pane; regulator acknowledgement ingest (`rejected`, receipts); live regulator APIs
(SFTP/HTTPS upload — a later delivery kind reusing Connections); nested groups; sealing / signing the delivered file;
a dedicated capability; real regulator schemas (pack content).
