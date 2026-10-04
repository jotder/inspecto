# Link Analysis live detection: who does a scheduled sweep run as? (design)

**Status: LD-1, LD-2 and LD-3 BUILT 2026-10-04 (option A); LD-4 to LD-6 not started.** D-LD1 was decided by the
operator (option A); D-LD2 to D-LD7 and four new calls were decided by the assistant, pending operator confirmation
(section 8). Unblocks backlog row `LA-LIVE-DETECTION-1` (`docs/BACKLOG.md` §3.12). Vocabulary follows `docs/GLOSSARY.md`: Investigation Template,
Working Set, Alert Rule, Job Type, Dataset, Incident.

## 1. The problem in one paragraph

An analyst turns an Investigation into an Investigation Template and binds an Alert Rule to a Measure of its
Working Set. Both are one-offs. The Alert Rule watches the **sealed** Working Set, so it moves when the log moves
and never when the Dataset grows. Standing detection needs a scheduled job that, on a clock, re-instantiates the
template over the grown Dataset and re-evaluates the Alert Rule. A scheduled run has no caller. Every Dataset read
an analyst makes passes a gate that is a function of the caller (R3 sharing and the Enterprise PDP). With no
Subject that gate has nothing to judge.

## 2. What the code does today (grounded)

### 2.1 The gates a read passes

* **R3 (`ComponentAccess`)** `inspecto-auth-spi/src/main/java/com/gamma/control/ComponentAccess.java`.
  `canView` (line 63) delegates to `level` (line 141). With **no Subject attached it returns OWN**, i.e. everyone
  allowed (line 142, the Personal fail-open). With a Subject: the owner id match, or the `canConfigureAccess`
  capability, gives OWN; a Dataset without a `shares` key is unrestricted (line 146); otherwise `user` shares match
  `Subject.id()` and `role` shares match `heldRoles(ex)` (lines 150-163). `heldRoles` (line 192) are stamped on the
  exchange at token validation by the authenticator and are **never** stored, so a role share cannot be re-resolved
  off-request. Role names never leave the authenticator (the class Javadoc, lines 14-32).
* **Dataset read** `InvRoutes.relationFor` (`inspecto-la-api/.../InvRoutes.java:853`): unknown Dataset 404, then
  `ComponentAccess.canView` false gives the same 404, then relation SQL. Every projection shares this order.
* **Investigation open** `InvestigationRoutes.open` (`inspecto-la-api/.../InvestigationRoutes.java:1798`): member
  role (1807-1824), the Dataset `canView` (1827), then the Enterprise PDP `RowScope.visible(ex, "investigation", ...)`
  (1830; `inspecto-auth-spi/.../RowScope.java:30`). The PDP can only NARROW: a DENY hides the Investigation even
  from a lead; an ALLOW or ABSTAIN grants nothing (javadoc 1834-1840).
* **Sensitive reads** `InvRoutes.refuseIfSensitive` (`InvRoutes.java:835`): above the four-eyes row or fan-out
  bound a route with no Investigation answers 403, because nobody could approve it. A sweep is such a caller.

### 2.2 Template instantiation

`InvestigationTemplateRoutes` (`inspecto-la-api/.../InvestigationTemplateRoutes.java`):

* Save (`save`, ~line 107) opens the source through `InvestigationRoutes.open`, so owner, R3 and PDP apply.
* `instantiate` (line 202) calls `openTemplate` (line 281: **owner-only**, 404 for anyone else), then
  `InvestigationRoutes.instantiate` (`InvestigationRoutes.java:601`), which applies the R3 Dataset gate to the NEW
  binding through `InvRoutes.relationFor` and re-resolves `excludeBy` / `seedBy` Entity Lists at the log head at
  that moment. The writes sit behind `canManageIncidents` (lines 90-93). Templates are write-once in
  `SnapshotStore`; there is **no list route and no sharing** (`link-analysis.md` lines 816-818).
* A template carries **no Alert Rule**. The rule is bound per Investigation afterwards, which is why the chain is a
  one-off today.

### 2.3 Alert Rule evaluation over a Working Set

* `POST /inv/investigations/{id}/alert-rules` (`InvestigationMeasureRoutes.bind`,
  `inspecto-geo-link/.../InvestigationMeasureRoutes.java:111`; gated on `canAuthorAlertRules`) opens the
  Investigation (owner, R3, PDP), writes an ordinary `alert-rule` component (line 163), then records a **binding**
  beside the Investigation: rule name, `ruleHash`, investigation, `owner`, `boundBy`, `boundAt` (lines 170-175) and
  only then arms the rule (`alerts.upsert`, line 177).
* The sweep evaluates through `AlertService`'s `investigationProbe` (`AlertService.java:103`, wired as
  `InvestigationMeasureProbe`, `inspecto-engine/.../InvestigationMeasureProbe.java`). The SPI contract: an
  implementation answers EMPTY (never a value, never fires) unless the rule was bound by the Investigation's owner
  and is unchanged since.
* `WorkingSetMeasures.read` (`inspecto-geo-link/.../WorkingSetMeasures.java:166`) checks that binding
  (`ruleHash` equal and header `owner` equal to `binding.owner`, lines 178-186), then evaluates. For a plain rule
  it reads the **sealed Working Set** (`WorkingSetRoutes.relation`, line 190): no Dataset read at all, so nothing to
  gate and nothing that sees growth. Its Javadoc states what the binding does NOT carry: a later PDP DENY, or the
  owner losing sight of the Dataset (`InvestigationMeasureRoutes.java:46-49`).
* **Already today, one path does read the live Dataset with no Subject.** An LA-18 value-measure rule
  (`WorkingSetMeasures.valueMeasure`, line 133) reads the WHOLE bound Dataset at every sweep via
  `DatasetRead.dataset` / `relationSql` and the comment says "The R3 gate was applied at binding (a sweep has no
  caller)". So the platform has already accepted **option B below** for that one rule shape; this design makes the
  choice explicit and bounds it.
* An AlertRule over an Investigation cannot be authored through `POST /alerts/rules`
  (`AlertRoutes.java:215`), so the binding is the only door.
* What an Alert discloses: the Investigation id, the relation, the Measure, its value and the threshold, never an
  entity id (`InvestigationMeasureRoutes.java:51-56`). A value-measure firing also records the agent-list version
  as evidence, not ids.

### 2.4 Job Types and the Subject

`alert.evaluate` (`inspecto-engine/.../AlertEvaluateJob.java`) is the model: a Job Type reaches the platform only
through a declared `requires:` grant (`AlertAccess`, line 62 via `ctx.services()`), is pack-shippable, and fails the
Run closed when the service is absent. `JobContext` (`JobContext.java:14`) carries parameters, artifacts and the
signal payload; **it has no Subject and no run-as identity**. The Alert engine itself is evented and scheduled
(`AlertService` header, line 34 onward: terminal-batch subscriber line 314, `evaluateAll` line 333).

### 2.5 Masking

`EntityMasking` (`inspecto-la-api/.../EntityMasking.java`, LA-19 / D-U6) masks **at render time only**, per Space
`maskingMode` and Entity Type, using an HMAC key private to each Investigation (`mask.key`, never served). It is
not a function of the viewer, and nothing masked is stored: sealed logs and hashes are over raw ids. Column class
comes from the Dataset registry and, since 2026-10-04, from schema lineage with strictest-wins; an untraceable
lineage over a masked class masks every id (fail closed).

Consequence for this design: **a sweep cannot widen masking as long as it persists nothing new that a human reads
as ids and its Alert evidence stays aggregate.** Masking can only widen if the sweep (a) writes an unmasked copy,
(b) puts ids into an Alert or event, or (c) creates an Investigation under a different owner or maskingMode.

## 3. Options: who the sweep runs as

### Option A: a delegated service principal, bound at bind time, re-checked at sweep time (recommended)

The sweep is a distinct actor, `sweep:<template id>`, that holds **no capabilities of its own**. Its authority is a
**Standing Detection binding** recorded when the analyst enables it (extends the binding in
`InvestigationMeasureRoutes.bind`): template id, the owner's id, the Dataset id, the template roles, the
Alert Rule hash, plus a **grant snapshot**: a hash of the Dataset's current sharing envelope, and the list of
masked Entity Types / columns in force.

At every sweep, before any read, a pure function re-decides: *"does the binding's owner, as a user id, still have
view on this Dataset's CURRENT envelope; is the owner still a member lead of the template's Investigation; is the
Alert Rule hash unchanged; is the masking basis unchanged or stricter?"* Anything it cannot decide is a refusal.
The sweep only ever computes the existing aggregate Measures and Alert evidence.

* New seam: a Subject-less overload next to `ComponentAccess.canView` (e.g. `canViewAs(ownerId, content)`)
  that evaluates only the owner id and `user` shares and the `canConfigureAccess` capability stored on the binding.
  **A `role` share cannot be re-resolved** (section 2.1: roles live only on the exchange), so a Dataset whose only
  grant to the owner is a role share is **refused at enable time and at sweep time** (D-LD2).
* PDP: consulted with a synthetic Subject built from the owner's id and the stored capability set, tagged as a
  sweep. A DENY refuses the sweep; ABSTAIN grants nothing, exactly as for a request. Whether that is faithful
  enough is D-LD3.
* Security properties: the sweep authority is a strict SUBSET of the owner's live authority (it cannot exceed what
  the owner could do right now through the same gate, and it loses access the moment the owner does). Revoking the
  owner's share, a PDP DENY, a Dataset re-share, an edited Alert Rule or a stricter masking basis all stop the sweep
  closed on the next run, which is the property bind-time-only checking lacks.
* Cost: one new auth-spi decider, one binding extension, one Job Type, an audit event. The limit is the role-share
  blind spot and the synthetic PDP Subject.

### Option B: the binding analyst's snapshot (check once at bind, trust at sweep)

What the value-measure path does today (section 2.3). The binding is the whole authorisation: the owner passed
R3 and the PDP once. Cheapest (no new seam). Security trade-offs: a later un-share, a PDP DENY, an owner who
leaves, or a Dataset newly classified as masked **does not stop the sweep**; the system keeps reading data the
owner can no longer see. That is the one failure mode the Alert disclosure text cannot excuse, so B is only
acceptable as a stepping stone and only for aggregate output. If chosen, the masking snapshot is "whatever was in
force at bind" and masking could WIDEN later (a column reclassified masked is still read), which this design
forbids.

### Option C: refuse what cannot be proven public (the floor)

The sweep runs only where no gate could say no: the Dataset has **no `shares` key** (unrestricted under R3, line
146), **no Enterprise PDP policy** for kind `investigation` is configured, and **no column in the template roles
carries a masked Entity Type** (strictest-wins lineage, `EntityMasking` 2.5). Everything else is refused at enable
time with the reason. Safest and trivially correct, but it excludes exactly the restricted Datasets (telco CDR,
payment fraud) where live detection is wanted.

### Comparison

| | A delegated, re-checked | B bind-time snapshot | C refuse restricted |
|---|---|---|---|
| Stops when the owner loses access | yes, next sweep | no | n/a (never ran on restricted) |
| Masking can widen | no (re-checked, fail closed) | yes | no |
| Role-share Datasets | refused (cannot re-resolve) | allowed (stale) | refused |
| New seams | 1 decider + binding + Job Type | Job Type only | Job Type + predicate |
| Fits restricted Datasets | owner-share / unrestricted only | yes, but unsafe | no |

## 4. Recommendation

**Option A, with Option C as its refusal floor** (anything A cannot decide is C's refusal). Reasons: it keeps the
platform's own invariant (masking and R3 only ever narrow) true for a caller-less run, it makes the already-shipped
value-measure path honest instead of silently B, and it needs one small pure decider rather than an identity
provider integration.

Invariants the slices must keep:

1. **Narrow only.** Every refusal path answers "not evaluated" (EMPTY reading, a recorded reason), never a value.
2. **Aggregate output only.** The sweep writes no entity ids to an Alert, event, log line or Signal. A sweep that
   creates an Investigation (D-LD4) creates it owner-only, under the template owner, with `maskingMode` read at
   render as for any Investigation, so nothing is stored unmasked.
3. **Masking basis may only tighten between bind and sweep.** If a masked class appears that was not in the
   snapshot, or lineage cannot be traced, the sweep refuses (it does not widen, and does not silently continue).
4. **The sweep never approves anything.** A read over the four-eyes bound is refused (as `refuseIfSensitive`
   already does for a no-Investigation caller); a sweep cannot be the second person.
5. **Audited.** One event per enable, per sweep outcome and per refusal, naming the binding and the reason code,
   best-effort after the act like the template events.

## 5. What the sealed-Working-Set Alert Rule does today

Bound through `POST /inv/investigations/{id}/alert-rules`, armed in `AlertService`, evaluated on the normal
Alert sweeps by reading the **sealed log** only, after the binding check. It fires when the analyst's own log
moves (an expand, an exclude, an undo), never when new rows arrive in the Dataset, so it is a watch on the
analyst's work, not detection. Editing it is delete-then-rebind (the binding hash breaks). It is inert without
the `inspecto-geo-link` module. This design does not change it; Standing Detection adds a sibling path.

## 6. Slices (LD-1 to LD-3 built; section 9 is the as-built shape)

| Slice | Content | Verifies |
|---|---|---|
| LD-1 ✅ | `ComponentAccess.canViewAs(owner, caps, content)` pure decider in auth-spi, mirroring `level` for owner and `user` shares only; explicit "role share is undecidable" answer | unit tests incl. unrestricted, owner, user share, role-only (refused), `canConfigureAccess` |
| LD-2 ✅ | Binding extension (template id, grant-snapshot hash, masked-class snapshot) and an enable route that applies the C floor and refuses role-only grants | real-HTTP test, every gate |
| LD-3 ✅ | Sweep-time re-check in `WorkingSetMeasures.read` for the value-measure path (closes today's silent option B) | un-share / DENY / reclassify each stop the sweep |
| LD-4 | Job Type `la.detect` (declared `requires:` grant, fail closed when absent) that re-evaluates (or re-instantiates, D-LD4) per binding | Job Type tests, no ids in output |
| LD-5 | Template list route and sharing (D-LD6), edit a bound rule in place | owner-only default kept |
| LD-6 | Monitoring surface: live evaluation status per binding and the refusal reason | SPA a11y gate |

## 7. The seven decisions (as asked)

* **D-LD1 Principal model.** A (recommended), B (stepping stone only) or C (floor only)?
* **D-LD2 Role-share Datasets.** Refuse (recommended), or require a `user` share for the owner, or accept an
  IdP directory lookup (reopens the "no user directory" decision, §8 Q4 of the RBAC plan)?
* **D-LD3 PDP at sweep time.** Synthetic Subject from the owner's stored capabilities (recommended), or treat any
  configured Investigation policy as a refusal (stricter, simpler)?
* **D-LD4 Evaluate in place or re-instantiate.** Each sweep creates a fresh owner-only Investigation (evidence, more
  storage, owner clutter) or only evaluates the Measure over a transient Working Set and persists nothing
  (recommended: no Investigation unless the Alert fires).
* **D-LD5 Owner leaves or loses `canManageIncidents`.** Pause the binding and notify (recommended) or transfer.
* **D-LD6 Template sharing.** Stay owner-only (recommended until D-LD1 ships) or share via the registry envelope?
* **D-LD7 Cadence and edition.** Default schedule and minimum interval, and whether Standing Detection is
  Professional-and-above only (matches the module).

## 8. Decision table

| # | Decision | Status | What it means in the build |
|---|---|---|---|
| D-LD1 | **Option A**, a service principal `sweep:<id>` with no capability of its own | **Decided by the operator 2026-10-04** | Authority is a binding recorded at enable time and re-decided at every sweep; always a subset of the owner's live authority. Anything undecidable is a refusal (C is the floor). |
| D-LD2 | **Refuse** a Dataset whose only grant to the owner is a role share | Decided by the assistant 2026-10-04 pending operator confirmation (the design's recommendation) | `ROLE_SHARE_ONLY`, at enable (422) and at every sweep. The fix is a `user` share to the owner. |
| D-LD3 | **Synthetic Subject** built from the owner's id and the grants recorded at enable | Decided by the assistant 2026-10-04 pending operator confirmation (the design's recommendation) | Also records the owner's data scopes and IdP attributes (a DENY keyed on `subject.space` must not be skipped) and tags the Subject `sweepPrincipal`. A decider that throws is a refusal. **Residuals:** the snapshot cannot refresh off-request, and it carries no role names (guideline 13), so a DENY keyed on `subject.roles` cannot be reproduced. |
| D-LD4 | **Evaluate in place**, persist nothing unless the Alert fires | Decided by the assistant 2026-10-04 pending operator confirmation (the design's recommendation) | The value-measure path already works this way (an aggregate count over the whole Dataset). Re-instantiation belongs to the Job Type (LD-4). |
| D-LD5 | **Pause**, never transfer | Decided by the assistant 2026-10-04 pending operator confirmation | A sweep refuses `NOT_LEAD` once the owner is no longer a lead of the Investigation. The owner's `canManageIncidents` and `canAuthorAlertRules` cannot be re-checked off a request (capabilities are token-time); only lead membership can. "Notify" is the recorded refusal event. |
| D-LD6 | **Owner-only**, no template sharing | Decided by the assistant 2026-10-04 pending operator confirmation (the design's recommendation) | The list route (LD-5) lists the caller's own templates only; see D-LD17. |
| D-LD7 | **Professional-and-above only** (the route lives in the optional `inspecto-geo-link` module); cadence is the existing Alert sweep cadence | Decided by the assistant 2026-10-04 pending operator confirmation | No new schedule or minimum interval: the `la.detect` Job's own cron is the cadence (D-LD13). |
| D-LD8 (new) | The R3 admin hatch `canConfigureAccess` is **never** honoured by a sweep | Decided by the assistant 2026-10-04 pending operator confirmation | It is a live capability the sweep cannot confirm, so a snapshot would exceed the owner's live authority. Only the owner id and `user` shares count. |
| D-LD9 (new) | Only masking **tightening** stops a sweep; loosening does not | Decided by the assistant 2026-10-04 pending operator confirmation | The operator asked to refuse on tightening. A sweep returns aggregates only, so a looser basis exposes nothing more. |
| D-LD10 (new) | Only the **owner** may enable; only a **value-measure** rule may be enabled; a rule edited since binding is refused | Decided by the assistant 2026-10-04 pending operator confirmation | A co-lead cannot lend the owner's authority. A sealed-Working-Set rule reads no Dataset, so there is nothing to authorise. |
| D-LD11 (new) | A value-measure rule is **not evaluated** unless standing detection is enabled | Decided by the assistant 2026-10-04 pending operator confirmation; breaking changes are free (operator 2026-09-20) | Rules bound before this change stop sweeping until the owner enables them. The bind response says so. The SPA owes an enable affordance (LD-6). |
| D-LD12 (new) | The principal is named after the **Investigation** (`sweep:<investigation id>`), not a template id | Decided by the assistant 2026-10-04 pending operator confirmation | A template carries no Alert Rule today (section 2.2), so the rule's binding on its Investigation is the only record there is. |

## 9. As built (LD-1 to LD-3, 2026-10-04)

* **LD-1** `ComponentAccess.canViewAs(id, capabilities, content)` in auth-spi returns `ALLOWED | DENIED | ROLE_ONLY`
  (owner id and `user` shares; a role share it cannot resolve is `ROLE_ONLY`, never `DENIED`). `RowScope.visibleAs`
  asks the active PDP for a caller-less run through an inert package-private exchange (`SweepExchange`); a decider
  that throws is a refusal.
* **LD-2** `POST /inv/investigations/{id}/standing-detection {rule}` (`InvestigationMeasureRoutes`, gated on
  `canAuthorAlertRules`, owner only) records `StandingDetection.Authority` under `standing` in the Alert Rule binding
  (the same record, so the store interface and its three implementations are unchanged): principal, owner id,
  capabilities, data scopes, attributes, Dataset, a masking-basis snapshot `{mode, columns[]}`. It runs the sweep's own
  check first, so what enable accepts is exactly what a sweep would accept. Re-enabling re-snapshots (the way back
  after a refusal). The rule is read as ARMED (`AlertService.rules()`), the one whose hash the binding holds; a stored
  copy round-trips numbers through TOON and hashes differently.
* **LD-3** `WorkingSetMeasures.read` for a value-measure rule calls `StandingDetection.check` before any Dataset read.
  Refusal codes: `NOT_ENABLED · NO_OWNER · BINDING_CHANGED · DATASET_GONE · DATASET_NOT_SHARED · ROLE_SHARE_ONLY ·
  NOT_LEAD · MASKING_TIGHTENED · POLICY_DENIED · UNDECIDABLE`. Events `LINK_STANDING_DETECTION_ENABLED`, `…_SWEPT`
  (aggregate count only) and `…_REFUSED` (code only) are best-effort and name no entity id.
* **Gates cleared:** `CapabilityManifest`, `docs/api/openapi-v1.json` (skeleton), `AbsentGeoLinkRoutes.SURFACE`,
  `compliance/evidence/route-gating.md`, and the auth-gate baseline (the new route is covered by an armed test). The
  route is not a Job writer, so it has no `JobWritersTest` row (an entry there fails: it names no writer route).
* **Tests:** `ComponentAccessAsOwnerTest` (auth-spi), `StandingDetectionTest` (la-api), and the real-HTTP
  `ControlApiStandingDetectionTest` (every gate; each stop reason, with the probe that would otherwise succeed: the
  same owner still opens the Investigation on a request). `ControlApiValueMeasureTest` now enables first.
* **LD-4** `la.detect` (`inspecto-engine`: `LaDetectJob`, registered in `JobService.registerBuiltins`; no loader file or
  registry kind, so no reserved file name) calls the new `AlertAccess.evaluateInvestigationRules()` ->
  `AlertService.evaluateInvestigationRules()`, the Investigation pass of the full sweep extracted into one private method both
  share (so the Job and the ordinary sweep cannot disagree). It throws, failing the Run, when no probe is wired.
* **LD-5** `GET /inv/investigation-templates` (`InvestigationTemplateRoutes`, over the new `InvestigationStore.templates()`,
  implemented for the filesystem and Postgres stores and pinned in the shared store contract), `DELETE .../standing-detection/{rule}`
  and `PUT .../alert-rules/{rule}` (`InvestigationMeasureRoutes`, whose bind body validation is now one shared `parse`).
* **Gates cleared for the two new mutating routes:** `CapabilityManifest` (literal paths), `AbsentGeoLinkRoutes.SURFACE`,
  `docs/api/openapi-v1.json` (regenerated skeleton), `compliance/evidence/route-gating.md`, the auth-gate baseline (unchanged,
  armed tests), and a `JobWritersTest` row for the PUT only (it writes an `alert-rule` component, like the bind it edits; the
  DELETE writes no component).
* **Tests:** `ControlApiStandingDetectionLd5Test` (real HTTP: the Job run through `/jobs/{name}/trigger`, with the sweep refusing
  before enable and firing after; list owner-filtering and the 503; disable gates, idempotence, resume; edit gates, failed edits
  changing nothing, authority dropped and re-enable), `SampleJobTypesTest` (the Job), `AlertServiceTest` (the narrowed sweep),
  the store contract (`templates()`).
* **Not built here:** LD-6 (SPA monitoring and the enable affordance; another lane).

### LD-4 and LD-5 wire contract (fixed 2026-10-04; the SPA lane builds against exactly this)

Every route below lives in Professional-and-above (optional `inspecto-geo-link`), is listed in
`AbsentGeoLinkRoutes.SURFACE`, and answers the standard envelope (`{data: ...}`); errors are the usual
`{error: {code, message}}`.

| Route | Gate | Request | Response `data` |
|---|---|---|---|
| `GET /inv/investigation-templates` | none beyond owner filter (as `GET .../{id}`) | none | `{templates: [{id, title, createdAt, dataset, investigation, parameters, ops}]}` newest first; `investigation` is the source Investigation id, `parameters` / `ops` are COUNTS. Only the caller's own templates (a Personal host with no Subject lists all). Never ops, ids or parameter names. |
| `DELETE /inv/investigations/{id}/standing-detection/{rule}` | `canAuthorAlertRules`, then the Investigation's `open` (404 for a non-member) | none | `{rule, investigation, enabled: false, wasEnabled: boolean}`. 404 when the rule is not bound to that Investigation. Idempotent (`wasEnabled:false` the second time). The rule stays bound and armed; the sweep then refuses `NOT_ENABLED`. |
| `PUT /inv/investigations/{id}/alert-rules/{rule}` | `canAuthorAlertRules`, then `open` | the same body as `POST .../alert-rules` (`name`, if given, must equal `{rule}`) | the same shape as `POST .../alert-rules` plus `replaced: true` and `standingDetection: "disabled: ..."`. 404 not bound here; 409 when the armed rule was edited out of band since binding. |

The Job Type: `type: la.detect`, **no parameters**, `requires: [alerts]`, emits the Signal `la.detect.completed`
(`{job, fired, rules[]}`: a count and Alert Rule names, never an Investigation or entity id). Author it as an
ordinary Job (`config/jobs/<name>.toon`, `job: {name, type: la.detect, schedule: "<cron>"}`); the cadence is that
cron (D-LD7: there is no separate schedule or minimum).

New audit events: `LINK_STANDING_DETECTION_DISABLED` (`rule`, `investigationId`, `wasEnabled`) and
`LINK_INVESTIGATION_ALERT_RULE_EDITED` (`rule`, `investigationId`, `standingDetectionDropped`).

### LD-4 and LD-5 decisions (decided by the assistant 2026-10-04, pending operator confirmation)

| # | Decision | What it means in the build |
|---|---|---|
| D-LD13 | `la.detect` is a **clock over the existing evaluation**, not a second evaluator, and evaluates **only Investigation-bound rules** (sealed-Working-Set and value-measure alike) | It calls `AlertAccess.evaluateInvestigationRules()` through the `alerts` grant (the one new method; default = the full sweep, so a stand-in stays correct by doing more). The authority re-decision stays in `WorkingSetMeasures.read` / `StandingDetection`, so the Job and the ordinary Alert sweep cannot disagree. D-LD4 holds: nothing is re-instantiated or persisted unless the Alert fires. |
| D-LD14 | The Job **fails the Run closed** when the `alerts` service is absent, and when the Link Analysis module is not wired (the engine throws) | An empty Run that reads as "nothing breached" would claim health nobody checked. A dry run evaluates nothing and says so. |
| D-LD15 | **Disabling** standing detection is allowed to anyone who may bind alert rules and open the Investigation, and is idempotent | It only narrows (the sweep refuses `NOT_ENABLED`), so no owner-only restriction is needed; enabling stays owner-only (D-LD10). |
| D-LD16 | **Editing a bound rule in place drops standing detection** | An edit changes what the sweep computes and what its Alert discloses, but the owner's recorded authority was granted for the old rule. The new binding carries the new hash and NO `standing`, so the owner must re-enable (a re-snapshot), the same way as after a refusal. Order of writes: component, then binding, then re-arm, so in every interleaving a sweep sees a hash mismatch and refuses rather than evaluates. The name is immutable. |
| D-LD17 | The template list is **owner-only and summary-only** (D-LD6 stands: no sharing) | Counts, not ops; template sharing is not built. |

## References

* `docs/okf/frontend/features/link-analysis.md` (Templates and Measures, Masking, R3 and PDP sections).
* `docs/archived-documents/plans-archive/rbac-abac-plan.md` (R3 sharing, role-name guideline).
* `docs/BACKLOG.md` §3.12 row `LA-LIVE-DETECTION-1`.
