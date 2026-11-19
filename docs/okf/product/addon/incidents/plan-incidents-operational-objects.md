---
type: Capability
title: Alerts & Incidents (INC) — Specification — Incidents as operational objects
description: Part 3 of 5 of Alerts & Incidents (INC): 
tags: [inc, capability, alerts, alert-rules, incidents, cases, queues, escalation, sla, notifications, diagnosis, findings, retention]
timestamp: 2026-09-08T00:00:00Z
---

# Alerts & Incidents (INC) — Specification — Incidents as operational objects

Part 3 of 5 of [Alerts & Incidents (INC)](plan-incidents.md) · previous: [Specification](plan-incidents-specification.md) · next: [Notifications, decisions and not built](plan-incidents-notifications-decisions.md).

## 3. Specification (continued)

### 3.4 Incidents — the operational-objects domain

`ObjectType` = `INCIDENT | CASE | TASK` (the `ALERT` value was retired 2026-10-08: an Alert is a record of the Alert store, `AlertLifecycle` holds its `OPEN → ACKNOWLEDGED → RESOLVED`; a stored legacy `ALERT` row, or a row of any type this build does not know, loads **inert** — listed by `GET /objects` with `inert:true` + a diagnostic, readable by id, every write on it a `409` naming the type, skipped by the SLA sweep / analytics / purge, never rewritten or dropped), one table `inspecto_ops_objects` when durable, else
`InMemoryObjectStore` (`OpsEngineProvider.java` picks per store on `durable()`).
Updates are **optimistic** (2026-10-04): every object carries a monotonic `version`, a stale write fails with `ObjectVersionConflictException`, `ObjectService` retries pure read-modify-writes and the edge answers a persistent loss as `409 CONFLICT_STALE_VERSION` — see `backend/engine/db-layer.md` §3.1. No client change is needed for server-side races; `PATCH /objects/{id}` does not yet accept an expected `version` (deferred — a client editing a whole object from a stale read still merges, it does not fail).

- **Workflows** (`platform/inspecto-workflow/src/main/java/com/gamma/workflow/Workflow.java`): **INCIDENT `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`**, `reopen`
  from `RESOLVED|ARCHIVED → DIAGNOSING`, only `ARCHIVED` terminal; CASE `OPEN → INVESTIGATING → ESCALATED →
  RESOLVED → CLOSED`. `GET /workflows/{type}` serves the BFS-ordered states so a TOON-overridden workflow
  (`*_workflow.toon`, a boot-time scan) drives the same panes; `assign` **does not move status** (no
  `ASSIGNED` state); `POST /objects/{id}/ack` has no action on any object type since the ALERT retirement (it answers the ordinary 422; Alerts are acknowledged on `POST /alerts/{id}/ack`).
- **Categorization** is a 3-layer `attributes.category` path `L1/L2/L3` (the `PUT /objects/{id}/category` route),
  enforced by the UI at latest on *Accept* (the `CategorizeDialog` is forced before the transition).
  **Priority** `CRITICAL · MAJOR · MINOR · LOW`. One **assignee** (the Incident Commander), optional at
  creation, set at triage — assignment is **direct**; queue-based routing at creation was deferred
  (product sign-off 2026-07-22).
- **Create contract** (2026-07-22): `POST /objects` requires a title (≤400) and a `links: [{to,
  relationship?} | id…]` array of **at least one** entry; every target is validated before `open()` so a
  dangling link cannot orphan the object; `relationship` defaults to `RELATED_TO`. `LinkRelationship` ∈
  `CONTAINS · ESCALATED_FROM · CAUSED_BY · RELATED_TO · MERGED_INTO · SPLIT_FROM`; the correlation graph is
  served depth-1.
- **The resolution pattern is a hard gate server-side** (I1, 2026-07-24): `ObjectService.commit`
  (through `incidentResolutionGaps`) rejects `INCIDENT → RESOLVED` unless `attributes.postmortem` holds a non-blank **timeline**
  entry, a **cause analysis** entry (`causeAnalysis[]` + `causeMethod`, "5 Whys" default), a **corrective
  action**, `dueAt` is set, **and `attributes.disposition` holds a ladder value** (WS-10, 2026-09-26)
  (`incidentResolutionGaps`). The Disposition rides the resolve itself — `POST /objects/{id}/resolve` or
  `/transition` body `disposition` (case-insensitive, stored in the ladder's spelling); an off-ladder value,
  or a `disposition` on any move but an Incident's resolve (a Case's lives in its Findings) → 422. The resolve's
  `OBJECT_ACTIVITY` event carries the `disposition`. **A reopen clears it** (blanked — the bag merge cannot
  delete a key), so a re-resolve needs a fresh decision, never a stale one. 🔴 **The gate is keyed on what
  DECIDES an Incident, not on the literal `RESOLVED`** (`SEC-IMPORT-OPS-CONFIGS-1`, 2026-09-26;
  `ObjectService.decidesIncident`): `RESOLVED` **and every terminal state of the registered workflow except
  `ARCHIVED`**. Before, a `*_workflow.toon` with a `CLOSED` terminal state (which an import could plant — now
  refused, see [`auth-security.md`](../../module/access/admin-guide-auth-security.md)) finished an Incident in one move
  with no Disposition and no postmortem. ⚠ `ARCHIVED` stays ungated **on purpose**: the default lifecycle
  archives from anywhere (`IDENTIFIED|DIAGNOSING → ARCHIVED`, the mail Trash — `WorkflowTest`), a dismissal
  that records no outcome, and `RESOLVED → ARCHIVED` already passed the gate. So a custom workflow buys nothing
  the default does not already allow; the Disposition may ride a custom terminal move exactly as it rides a
  resolve, and leaving that state clears it (`ObjectServiceTest.aCustomTerminalStateCannotFinishAnIncidentWithoutTheResolutionGate`).
  The `disposition` body field (and the `Disposition` ladder enum) is documented on both operations in
  `docs/api/openapi-v1.json` since 2026-09-28 — they are hand-written, no longer generated skeletons.
  ⛔ A machine actor never resolves
  an Incident (the per-entity Alert heal, §3.2). The UI's `postmortemGaps` soft-warn
  is a *mirror* of the same four checks, not the gate. ⚠ `objects.md` and the archived design still call
  the backend gate "a follow-up"; it shipped.
- **SLA and escalation.** `dueAt` / `dueInMinutes` at creation; the sweep (`sweepIncidentSla`,
  `ObjectService.java`, cadence `-Dobjects.sla.sweep.seconds`, default 60, `0` disables) stamps
  `slaBreachedAt` once and emits `OBJECT_SLA_BREACH`. **The clock stops at `RESOLVED` and at every terminal
  state of the Incident's registered workflow** (`IMPORT-RESIDUALS-1` (4), 2026-09-28) — the resolution gate's
  set (`decidesIncident`) **plus** `ARCHIVED`: the gate exempts `ARCHIVED` because archiving records no outcome,
  but an archived Incident is no longer worked either. Keyed on the state as well as `closedAt`, because an
  Incident can sit in a terminal state with no `closedAt` (a workflow file that later made its state terminal,
  an imported object) (`ObjectServiceTest.slaSweepSkipsEveryWorkflowTerminalStateButStillBreachesOpenOnes`). ⛔ **The `EscalationPolicy` that once ran on breach
  was retired (`RETIRE-HALVES-1`, 2026-09-14)**, and its job came back as authored **Escalation Rules**
  (`ASSURE-WORKFLOW-SLA-1`, 2026-09-29, next bullet): on an SLA breach or at an age, a matching rule marks the
  Incident `escalated`, can raise its priority and reassign it, and emits `OBJECT_ESCALATED` (WARN when the rule
  notifies) — fire-once per rule and breach (`ObjectService`). The Case `targetDate` is a **loose** SLA — overdue
  hint only, no sweep.
- **Incident governance — authored Workflow, SLA policy, Escalation Rules** (`ASSURE-WORKFLOW-SLA-1`, 2026-09-29). ✅ 2026-10-07: the Add Escalation Rule form has an "Advanced match (condition tree)" disclosure authoring the rule's optional `when` over the match context (`ESCALATION_CONTEXT_COLUMNS`); `escalationRuleContent(draft, stored)` carries every unmodelled key of a stored rule forward, so a save never drops `when`.
  🔁 **This REVERSES part of `RETIRE-HALVES-1`.** INC-4's retirement deleted the escalation engine because it was a
  backend nobody could see or author ("re-file when a customer names on-call escalation"). D-P4 (operator) named it:
  **escalation comes back, with its authoring UI; queues and watcher routes stay deleted.** Three component kinds,
  all written only through their literal `canAdminister` routes (`/components/workflow|sla-policy|escalation-rule`,
  a governance change), versioned by `ComponentStore` (`.history/`, restore), held by the maker-checker policy like
  every governable kind (`ApprovalPolicy.GOVERNABLE` derives from `WRITABLE_TYPES`), and **refused by every import**
  — the generic `/components` door, `/bundle/import` items and a new Space's files included
  (`ImportCapabilityGuard.GOVERNANCE_ONLY`; the dirs are out of `ImportPaths.REGISTRY_DIRS`, pinned by
  `ImportLoaderInventoryTest.theGovernanceRegistryDirsAreRefusedToEveryImport`). The `*_workflow.toon` /
  `*_escalation.toon` suffix refusals are unchanged.
  - **Workflow** (`Workflow.fromComponent` / `problems()`, moved to `inspecto-workflow` `com.gamma.workflow` (formerly engine `com.gamma.objects`) so core can
    validate at save): exactly one initial state, ≥ 1 terminal state, every state reachable, every non-terminal
    state has a way out, no two transitions leave a state by one action; for an Incident a `RESOLVED` state,
    `ARCHIVED` (if present) terminal, every non-terminal state able to FINISH (a path to `RESOLVED` or `ARCHIVED`
    for an Incident, to a terminal state otherwise — a self-loop or a closed cycle is refused), and **no transition into a terminal state other than `ARCHIVED` except from
    `RESOLVED`**. That is defence in depth: the runtime gate (`decidesIncident`) already gates every custom terminal
    state, so a workflow cannot describe a path around the Disposition / postmortem gate AND could not use one. The
    same validation now runs on `*_workflow.toon` at boot. Precedence: authored component > `*_workflow.toon` >
    built-in. **Hot reload:** `GovernanceRegistry` (`inspecto-ops`) lists the three registry dirs on every read
    and re-parses only a file whose mtime or size moved — whichever door wrote it (route, approval replay,
    restore, hand edit). A file that fails validation at load keeps its **last valid version** in force; one
    that was never valid contributes nothing (the lower layer serves). ⚠ The legacy single-tenant root has
    no config dir, so `ControlApi` points its engine at `<assist.write.root>/registry`.
    A PUT / restore / DELETE that drops a state live objects of that type occupy is refused **409**, naming each
    state and its count (`ObjectAccess.countByStatus`; a delete is judged against the built-in, since core cannot
    see `*_workflow.toon`) — `ControlApiWorkflowStrandTest`.
    ⚠ **Hand edits are operator scope.** A file written straight into `registry/workflows|sla-policies|escalation-rules/`
    takes effect on the next read like a routed save, but it bypasses the `canAdminister` gate, the maker-checker
    hold, the 409 strand check and the audit trail — only the save-time validation is re-run at load. Filesystem
    access to the config tree is an operator's, and that is where the control lives.
  - **SLA policy** (`SlaPolicy`): per object type, targets per priority (`responseMinutes`, `resolutionMinutes`,
    `*` = fallback) in a business calendar — working days, a same-day `start`–`end` window (no overnight
    windows), ISO-date holidays, and an **IANA zone id, required** (`ZoneId.getAvailableZoneIds()`; an offset or
    the host zone is refused). Windows are wall-clock, so a DST day keeps 09:00–17:00 and a 24x7 day loses its hour
    (`SlaPolicyTest`). The sweep stamps `dueAt` / `responseDueAt` from `createdAt` (plus `slaPolicy`, `slaPriority`);
    an operator-set `dueAt` (no `slaPolicy`) is left alone; a priority change recomputes until a breach is
    recorded. A **response breach** = `responseDueAt` passed while the object is still in its workflow's initial
    state — stamped `slaResponseBreachedAt`, emitted as `OBJECT_SLA_BREACH` with `target: response`, once. Every
    object type with a policy (or an Escalation Rule) is swept, not only Incidents.
  - **Escalation Rules** (`EscalationRule`, GLOSSARY §9): `on: breach` (`target: resolution|response`) or
    `on: age` (`afterMinutes` since open), optional `priority` filter; actions `reassign`, `notify`,
    `raisePriority`. Idempotent: the firing `<rule>@<breach stamp>` is written to `attributes.escalations` in the
    same store write as the effect, so a rule never repeats for one breach. Audited: one `OBJECT_ESCALATED` per
    firing (actor `escalation-rule:<id>`, WARN iff `notify`, which `builtin-escalation` turns into a
    notification) plus `OBJECT_ASSIGNED` on a reassign; `escalated=true` is set, so the Incident shows in the
    Escalated folder. Bounded: ≤ 50 rules loaded (`GovernanceRegistry.MAX_RULES`), ≤ 500 firings per sweep.
    Resolved / archived / terminal objects are never escalated.
    **Cases (operator 2026-10-03):** a Case is swept by the SAME `sweepIncidentSla` — its own `CASE` policy stamps
    `dueAt` / `responseDueAt`, a breach is raised once, `CASE` Escalation Rules fire once per breach, and a closed /
    terminal Case's clock is stopped (`GovernanceSweepTest.aCaseIsStampedBreachedAndEscalatedByItsOwnPolicyAndRulesOnly`).
    No separate Case sweep exists or is needed. **`reassign` vs the IAM (operator 2026-10-03):** `PrincipalDirectory`
    (`inspecto-auth-spi`, an edition SPI) is asked on every write door (`ComponentRoutes.validateKind`, so the route,
    a version restore and a Space-Template seed alike). With a directory registered, an unknown user id OR a lookup
    that throws is refused 422 (fail closed); with none registered — the offline editions, Basic / Standard / the
    demo build — the id-shape check alone applies, the same standing as `POST /objects/{id}/assign`. ⚠ The core ships
    NO implementation: no SCIM / user-lookup client exists in `inspecto-oidc` (it only validates tokens), so on
    every shipped edition today the check is the id shape. It is checked at SAVE time only: a user deleted from the
    IAM later is still assigned by the sweep. **Examples per Space Template (operator 2026-10-03):** each template
    carries `config/examples/sla-escalation-examples.md` — labelled, INERT markdown (an Incident and a Case policy
    plus Escalation Rules) because a template can never carry live governance (`GOVERNANCE_ONLY`, refused on every
    import a new Space's included); `TemplateGovernanceExamplesTest` runs every example through the real parsers.
    **Closed 2026-10-04 (operator):** `ASSURE-WORKFLOW-SLA-1` is closed. Residuals were P3 `ASSURE-WORKFLOW-SLA-RESIDUALS-1`, closed 2026-10-18 (the recheck retired to BACKLOG §6 under operator delegation): the
    sweep-time deleted-assignee recheck (recommended policy when built: skip the reassign, still notify / raise priority, one
    WARN per breach) waits for an IAM customer and a real `PrincipalDirectory`; a graphical state-diagram *editor* — operator asked
    2026-10-11, shipped `0d6e0280e` (see *UI* below). The live-preview pass of Settings ▸ Incident governance was done 2026-10-04 (see *UI* below).
  - **UI:** Settings ▸ *Incident governance* (`settings/incident-governance.component.ts`, model
    `inspecto/governance/governance-model.ts`): the effective workflow as a list editor (initial, terminal,
    transition rows), the SLA policy form and the Escalation Rules list; read-only without `canAdminister`; a 422
    refusal is shown inline, a held write says it was submitted for approval. **Without `inspecto-ops` (2026-10-04, live preview):** `GET
    /workflows/{type}` answers 503 `CAPABILITY_UNAVAILABLE`; the pane then shows *Not available in this edition* with
    the server's reason and renders no editor — before, it offered an empty, savable form. **SLA on the object (2026-10-03):**
    `slaBadges()` (`modules/admin/objects/mail-model.ts`) reads the server stamps only — never computes a clock —
    and the detail header shows *Respond by* / *Due* while open and *Response breached* / *SLA breached* always;
    the Incident/Case list prefixes a breach badge to the Description cell (no new column, so the list's width
    floors hold). Specs `mail-model.spec.ts` (5) + `object-detail.component.spec.ts` (header + axe).
    **Read-only state diagram (2026-10-03):** `settings/workflow-diagram.component.ts` draws the workflow beside the
    transitions table from the form's live value (`workflowDraftView` signal, fed by `valueChanges`). Layout is the
    pure `workflowDiagram()` in `governance-model.ts` (a column per BFS distance from the initial state; unreachable
    states in a trailing dashed column; terminal = thick border; back-edges arc below, self-loops above). Design-token
    colours only (`currentColor`). It is a described image (`role="img"` + a text alternative listing every move);
    the table stays the accessible, editable equivalent.
    **State-diagram editor (2026-10-11, `ASSURE-WORKFLOW-SLA-RESIDUALS-1` b):** `settings/workflow-state-editor.component.ts`
    wraps the diagram with a States table (rename, initial radio, terminal checkbox, remove, add state) and From/To
    *Connect states* selects; clicking two states on the diagram is a pointer shortcut for the same connect (the SVG stays a
    described image, so the keyboard path is the table + selects). Edits are pure `WorkflowDraft → WorkflowDraft` functions in
    `governance-model.ts` (`renameState`/`removeState`/`setInitialState`/`setTerminalState`/`connectStates`) written back into the
    SAME form (`applyDraft`), so the saved `workflow` content is exactly what the table path saves — states are implicit
    (initial ∪ terminal ∪ transition endpoints), there is no stored state list or layout, hence **auto-layout only** and a
    just-added state with no transition is UI-local ("not saved until it has one"). A new connection has a blank action the
    author names in the table. `workflowProblems()` mirrors `Workflow.problems()`/`fromMap` (same wording) as live,
    advisory feedback — the server stays the judge and its 422 shows verbatim on save. ⚠ Keep it in step with the Java rules.
  - **Tests:** `WorkflowValidationTest`, `SlaPolicyTest` (engine) · `GovernanceSweepTest` (ops: hot reload, a
    hand-planted bypass file is not served, the gate still holds on a custom terminal state, policy stamping,
    response breach, escalation once per breach, age + priority cap, the 50-rule bound) ·
    `ControlApiGovernanceComponentsTest` (armed Subject: 401 / 403 `developer`+`operations` / 200 `admin` on all
    twelve routes, the generic door refused even to `super`, 422s, history + restore, a held write approved by a
    second administrator) · `ImportCapabilityGuardTest.aGovernanceKindIsRefusedOnEveryImportANewSpacesIncluded`.
    Mutations run: dropping the "around RESOLVED" clause, the idempotency check, or the change detection each turn
    the named tests red.
- ⛔ **Queues and watcher routes are RETIRED** (`RETIRE-HALVES-1`, 2026-09-14). `*_queue.toon`,
  `POST /queues`, `QueueRouter` and the three `watch|unwatch|watchers` routes are deleted; nothing had ever
  called them. `POST /objects/{id}/assign` survives **person-only** (body `{assignee, actor?}`) and still
  emits `OBJECT_ASSIGNED`. ⚠ The `watchers` **attribute** is NOT retired — `POST /objects/{id}/merge`
  unions the watchers of the merged cases, so the field is live with no route to read it.
- **RCA templates** (`RcaTemplate`, `RcaTemplateRegistry`, `GET /rca/templates`, 1 committed example
  `orders_rca.toon`) seed the postmortem's cause-analysis structure; the **Postmortem** is the Incident's
  resolution artifact, the **Findings** are the Case's (§3.5).
- **`analytics(type)` reads every object** (`ASSURE-IMPACT-LEDGER-RESIDUALS-1` (1), 2026-09-28). It walks
  `ObjectService.allMatching(filter)` — a lazy, oldest-first iterator holding one `ObjectQuery.MAX_LIMIT`
  (10 000) page at a time — where it used to read ONE page and silently stop, so `total`, the breakdowns and
  the impact sums undercounted past 10 000 objects of a type. `DbObjectStore` orders by `created_at, id` so
  OFFSET pages neither overlap nor skip on a tie. ⚠ Offset paging, not a snapshot: an object purged mid-walk
  can shift another across a page boundary. Pinned in both backends by
  `ObjectServiceTest.analyticsCountsEveryObjectBeyondOneQueryPage*` (10 007 objects sharing one `createdAt`).
  The same complete read now backs `sweepIncidentSla` (a newest-first single page never reached the OLDEST
  overdue Incidents — `slaSweepBreachesTheOldestOverdueIncidentBeyondOneQueryPage`), `active()`,
  `evaluateCaseRule` (+ its open-Case lookup), `applyTagRule` and `backfillTagAssignments`; the list-returning
  ones keep their newest-first order and hold only their filtered hits.
  One walk yields each id once (a per-walk Set), because a backdated insert mid-walk shifts the page boundary;
  the sweep also re-reads the stored object before stamping, so a breach fires once and never overwrites a newer
  status. `ARCHIVED` is skipped by name as well, so it stops the clock even in a workflow where it is not terminal.
  `active()` (and `openCaseRaisedBy`) query with `ObjectQuery.openOnly` — `closed_at = 0` pushed into SQL — so the
  terminal history is never read; the Java `isTerminal` filter stays for a state made terminal after its objects
  were left open. ⚠ The reverse case — a state that STOPS being terminal while its objects keep `closed_at > 0` —
  now drops those objects from `active()` until they are moved again.

### An Entity's case history — `GET /objects/by-entity` (2026-10-11)

`GET /objects/by-entity?type=<Entity Type>&id=<value>[&status=open|closed|all][&limit=1..200, default 50]` lists the
Incidents and Cases that reference one Entity (an MSISDN, …), newest first:
`[{kind: incident|case, id, title, status, severity?, createdAt, matchedOn, via?}]`, `metadata.pagination.total` = the
visible match count. `id` and every stored value are folded with the space's Entity Type normaliser
(`EntityTypes.normalise`; typed spellings `msisdn:+…` / `entity:msisdn:+…` accepted; under `e164` a leading `+` is not
significant; an `id` typed as a DIFFERENT known Entity Type is a 422; the folded key must be ≥ 5 digits for a number
type, ≥ 2 characters otherwise). `matchedOn`: `key.<column>` (a keyed Alert Rule attribute one of whose `_ . -`
tokens IS the type id — `caller_msisdn` names msisdn, `excellent` does not name cell — or `key.entity_key`, the Risk / Anomaly Score rules),
`entityKey` (an Incident minted from a Link Analysis Entity; a prefix counts as a type only when it is a KNOWN Entity
Type id, so `entity:host:8080` matches as untyped), `member` (a Case CONTAINING a matched Incident; `via`
names it). Ungated like `GET /objects`, under the same SEC-7d / ABAC post-filter: a hidden object — and a Case reached
only through it — is absent and uncounted. 422 on an unknown type, missing / oversized / empty-folding `id`, bad
`status` or `limit`. ⚠ Cost (2026-10-12): `DbObjectStore` keeps a derived side table `inspecto_ops_object_entity_ref`
(`object_id, attr_key, value_lc` — the lower-cased `key.*` / `entityKey` values, plus one placeholder row per object;
rewritten on create/update, deleted on delete, backfilled for unindexed rows when the store opens), and
`ObjectStore.findByAttributeFragment` is one substring `LIKE` over THAT narrow table, then the objects by id in the
`createdAt DESC, id DESC` window (newest 2 000 candidates). Results are unchanged because `EntityHistory` still re-checks
every row exactly; candidates are only objects whose reference VALUE contains the fragment (the old scan also hit keys
and unrelated attributes). Still O(rows in the side table): the store does not know the Entity Type normaliser, so an
exact-key index would miss `00…` / separator spellings. The in-memory store keeps the JSON scan. When the window hits that cap, `metadata.pagination` carries
`truncated: true` + a `message` and the response the `X-Inspecto-Truncated: true` header (`total` is then a lower
bound). The route is on the standard per-subject rate-limit bucket (`ControlApi.isRateLimited`, 429 `RATE_LIMITED`); a stored value with
separators inside the digits is not found. Not covered: an LA Investigation linked to a Case
(`PUT /inv/investigations/{id}/case`) — that link lives in the Standard LA module, which `inspecto-ops` cannot see.
No read audit (no object read is audited today).
**Masked keys:** the same read is on the engine seam as `ObjectAccess.byEntity(writeRoot, exchange, type, key, status,
limit)` (`ObjectServiceAccess` → `EntityHistory.query`, the route's own code: same matching, visibility and `truncated`),
so Link Analysis's `POST /inv/case-history` can resolve an alias server-side and look it up without the raw id ever
reaching the browser — see the Link Analysis module concept.

### 3.5 Cases

A Case's **Contents** are the Incidents it `CONTAINS` — links, never an `attributes.caseId` (refused, §6).
**Merge** moves members, tags and watchers to the survivor and closes the absorbed Cases with a
`MERGED_INTO` link and marker (`CaseOperations.mergeCases`, module case-management); **Split** carves members into a new Case tied
back by `SPLIT_FROM` (`CaseOperations.splitCase`). Comments and attachments stay where they happened. A **Team** is
`attributes.assignees` (the lead stays `assignee`); **Findings** = **Disposition** (`confirmed ·
false-positive · recovered · written-off · inconclusive · duplicate · accepted-risk` —
`FindingsSpec.DISPOSITIONS`, the ladder Incidents resolve with too) + impact + summary, with a *soft* no-disposition
prompt on resolve and a *soft* open-member warning on close (both deliberately not hard gates).

**Findings sections are deployment-authored** (D6 / C3, 2026-07-26): a `findings-spec` `ComponentStore`
kind per `ObjectType`, served by `GET /findings/{type}`, rendered by `<inspecto-schema-form>`; absent ⇒
`FindingsSpec.defaultFor()` reproduces today's exact shape; present ⇒ **fully replaces** the default
(field-level merge refused because it makes "remove a section" inexpressible). Values are validated on
`PATCH /objects/{id}` → `422` for a *declared* key only; an undeclared key is never rejected because
`attributes` is a shared bag. The `AttributeSpec` vocabulary is pinned by a cross-language contract
(`inspecto-ui/src/app/inspecto/contracts/attribute-spec.contract.json`, compared by `FindingsSpecContractTest` (Java) and `attribute-spec.contract.spec.ts` (TS)).

**A Case type may have its own Findings form — optional** (`FINDINGS-PER-CASE-TYPE-1`, operator, 2026-10-06).
A Case type is the existing `attributes.caseType` value (the SEC-7d data-scope dimension). Its form is a
`findings-spec` component with id `case.<caseType>` (`caseType` = letters, digits, `-`, `_`; Case-only;
id and content must agree, else `422`). `GET /findings/case.<caseType>` serves it, else the generic `case`
spec, else the built-in default; the served `name` says which. `PATCH /objects/{id}` and
`PUT /objects/{id}/findings` judge a Case's blob against the spec for its own `caseType` (fail closed,
`422`). A type with no spec, or an unreadable one, keeps the generic form: additive, no migration. The SPA
mail pane fetches the open Case's type spec (`findingsCaseType` in `mail-model.ts`) and falls back to the
generic one. Authoring is the generic `/components/findings-spec` CRUD, which the *Findings fields* dialog drives through
its *Form* picker (shared form, an existing Case type, or a new one; `FINDINGS-EDITOR-PER-CASE-TYPE-1`).

**Every Case Findings form carries a built-in Impact field** (`FINDINGS-EDITOR-PER-CASE-TYPE-1`, option (c),
operator, 2026-10-06). The section `impact` (`FindingsSpec.IMPACT_KEY`, type `number`, tier `required`,
`required:false`) is appended to the built-in Case default, and `FindingsSpec.fromMap` refuses (`422`) a Case
spec (generic or `case.<caseType>`) that drops it, re-keys it, retypes it or changes any property but its
**label** (`builtInImpact(label)`). Its value **is the Case's typed `Impact.confirmed`**, never a Findings value:
`PUT /objects/{id}/findings` takes `impact` out of the blob and, when it differs from the stored confirmed amount,
writes it through `ObjectService.saveImpact` — keeping the stored currency, period, basis and other amounts —
with the impact route's rules: `canWorkIncidents` (403; an unchanged re-send needs nothing, so Findings stay
collaboration), `Impact.fromBody` validation (422, including "currency is required" on a Case with none yet),
the closed-books rule (409) and the `action: impact` before/after audit. The Findings blob never holds it, and
`PATCH /objects/{id}` refuses a Case blob carrying it (422), so the Impact panel and the Findings field are one
stored value; the SPA's `parseFindings` reads the field from `impact.confirmed`. Incident specs are unaffected.
Pinned by `ControlApiFindingsImpactFieldTest` and `FindingsSpecTest`.

**Case Rules** (`CaseRule`, `/cases/rules`) are saved searches that auto-group: when ≥ *threshold* Incidents
match within a *window* they are grouped under one Case, opened or attached idempotently. Evaluation is on
demand from the pane **and** schedulable as the `caserule.evaluate` Job Type (`CaseRuleEvalJob`) — the
archived design's "scheduler auto-evaluation is a follow-up" is stale. `objects.analytics` samples
per-category counts into tall Parquet under `<dataDir>/ops_analytics/` as a `dataset` Run Artifact
(2026-07-25); binding it as a Studio Dataset is unbuilt (§5). ⚠ **No committed `*_case_rule.toon` or
`*_tag_rule.toon` exists.**

**`groupBy` - one Case per offender (2026-10-14, `TELCO-FRAUD-STORY-GAPS-1`).** A Case Rule may name a key column (`groupBy: msisdn`); the rule then groups Incidents by the value of their `key.<column>` attribute (the per-entity key an Alert Rule stamps), so ONE offender's Incidents form one Case and two offenders never merge. `threshold` and `windowMinutes` apply per offender, an Incident without that attribute never matches, a late Incident joins only its own offender's open Case (`caseGroup` attribute on the Case). The Case carries only a digest of the value - a 12-hex `caseGroup` attribute and the first 6 in the title - never the raw key, since a Case is read by non-revealing viewers. (The Incidents' own `key.<column>` attributes are the masking lane's concern.) Absent `groupBy` nothing changes (one Case per rule). SPA: *Group by key* in the Case rules dialog. Pinned by `CaseRuleGroupByTest`.

**Impact (WS-10, `ASSURE-IMPACT-LEDGER-1`, 2026-09-26).** An Incident and a Case carry one typed financial
impact, `com.gamma.ops.Impact`: four non-negative decimals — **suspected · confirmed · recovered · prevented** —
in one ISO 4217 **currency** (required once any amount is set), plus free-text **period** (≤ 64) and **basis**
(≤ 2000). Stored as the JSON blob `attributes.impact`, amounts as plain decimal strings (≤ 6 places, < 10^15), so
no `double` ever touches a stored value — and every read view returns them as **strings** too (2026-09-28, §4:
a JSON number came back through the SPA as a rounded `double`). **`outstanding = confirmed − recovered` is derived on read and never
stored**: `OperationalObject.toMap()` adds a top-level `impact` block with it on every read, and a body that
sends `outstanding` is refused. Written only by `PUT /objects/{id}/impact {impact:{…}}` on `canWorkIncidents`
(the `postmortem`/`category` narrow-route precedent — recording what an Incident cost is part of finishing it,
and the `canAdminister` PATCH must not become the analyst's catch-all): `{}` clears it; 400 no/non-object
`impact`; 422 an invalid value (an amount's text is bounded to 40 characters before it is parsed), any other
body key, or an Alert/Task; **409 on an `ARCHIVED` Incident**, and — **late recoveries** (operator, 2026-09-26) —
on a `RESOLVED` Incident or a `CLOSED` Case only `recovered` and `prevented` may still change, any other field
→ 409 (reopen to change the rest); 404 unknown or out-of-scope. Audited as `OBJECT_ACTIVITY` `action: impact`
with the stored value **`before` and `after`** and the request's actor. ⛔ **`PATCH /objects/{id}` and `POST /objects` refuse
`attributes.impact` and `attributes.disposition` (422 naming the key)** — their free attribute bags would bypass the
validation, the closed-books rule and the audit. **On a Case this IS the Findings impact** — the built-in Findings spec dropped its
`impactAmount` section and the route its flat `impactAmount` copy (only `recordsAffected` remains), so there
is one home for the money, not two. ⚠ A deployment-authored `findings-spec` may still declare an `impactAmount`
key; it is then an ordinary scalar in the blob that nothing sums. `analytics()` sums the typed impact **per
currency** (`impact.byCurrency.<ISO>` = `count` + the four amounts + `outstanding`) — never across currencies —
and `objects.analytics` samples it as axis `impact.<ISO>`. **The impact ledger Dataset** (`impact_ledger`) is
written by the same `objects.analytics` run — one row per Incident/Case with an impact per run, exact DECIMALs,
`outstanding` and the Disposition included — so any Measure / Widget can read "money found / recovered /
prevented" without a new seam (as-built + the freshness caveat: `okf/product/module/processor/spec-jobs.md`).

### 3.6 Annotations — Notes and Tags

Both address their subject as an **Annotation Target** `(targetKind, targetId)` from one shared vocabulary
(`AnnotationKinds`, core; `AnnotationTargets` resolves them and applies `RowScope`). A **Note** is a comment
or attachment *reference* (true upload refused, §6). **Tags** are a **central assignment store**, not a field:
`attributes.tags` is a *projection* since D7 (2026-07-26) — written on manual apply, Tag-Rule merge,
rule-raised creation, merge union and split, but never the source of truth. A **Tag Rule** is a saved
search that auto-tags matching Incidents/Cases on open and can be applied in bulk. The mail pane's tag menu
is still the only place a tag is *applied*; the `/tags` pane is a vocabulary pane. Mechanism: `tags.md`.

### 3.7 Retention — `incident_purge` and the D5 tiers

Retention is a **tier**, not a terminal state (D5, 2026-07-25): `CLOSED → ARCHIVED` and `ARCHIVED → purge`
are two separate windows. `incident_purge` (a maintenance task, `IncidentPurgeTask`, `inspecto-ops`):
`retention_days` **required, no default**; `max_count` default 1000; dry-run first; retention **derived**
from `closedAt + retention_days`, never stamped; a legal hold is fail-safe and re-checked inside `purge()`;
dependents (notes, links, tag assignments) are deleted **before** the object through **abstract** store
methods — a silent no-op `default` was refused because it would orphan rows quietly. Scoped to
`ObjectType.INCIDENT` because only its workflow has `ARCHIVED`. **G3 stance:** a purge is not "all trace
removed" — the append-only ledger keeps the Incident's `OBJECT_ACTIVITY`, including the purge record.
⛔ **Nothing schedules `incident_purge`, and nothing should**: a shipped default that hard-deletes business
records is indefensible; the operator opts in with a Job.

**Alert retention — `alert_purge` (2026-10-09, MODULE-REORG-P7-INCIDENTS).** The Alert-owned store
(`AlertStore`, the `ALERTS` family) never deleted anything, so resolved Alert rows grew without bound on every
edition. `alert_purge` is a **base** built-in task of the `maintenance` Job Type (`AlertPurgeTask`,
`inspecto-engine`; Alerts carry no module gate, so it is a case of `MaintenanceJob`'s switch like `event_prune`,
not a `MaintenanceTaskProvider`). **Schedule it** with a maintenance Job: `task: alert_purge`,
`retention_days: 90` (cron as you like; run a dry run first). **Defaults:** `retention_days` absent = 90; present
but blank / `0` / negative / non-numeric is **refused** (a mistyped window must never read as "delete everything");
**nothing ships scheduled** — no default Job, so existing installs lose nothing until an operator opts in. **What is
purged:** `RESOLVED` rows whose own `closedAt` is strictly before `now - retention_days`. **What is never purged:**
`OPEN` and `ACKNOWLEDGED` Alerts (they carry the de-duplication state — a deleted active row would let its rule fire
again), a resolved row closed exactly at the cutoff or inside the window, and a resolved row with no close time. Age
only: an Alert's `incident_id` is a text reference into another store and nothing reads an Alert row back through an
Incident (the Incident's `ESCALATED_FROM` edge has no node behind it), so a purged Alert leaves a harmless dangling
reference. A purge also drops the purged Alerts from the live `GET /alerts` history, and a real purge that deleted
rows writes one `AUDIT` row `alerts.purged` (`alerts_removed`, `purge_before`, `retention_days`). Without an Alert
engine attached the task **fails** rather than reporting a silent success.

## Changes

- 2026-11-01: the SLA breach section corrected — it said a breach mutated nothing and `OBJECT_ESCALATED` was emitted by nothing.
