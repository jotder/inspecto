# Route gating audit — every ungated MUTATING control-plane route

**Status:** AUDIT COMPLETE (2026-09-15), nothing changed in code. Commissioned by the operator's
"full audit of all 75 first" decision on `ROUTE-UNGATED-DEFAULT-1` (P1).

**Method.** Enumerated with the *same regex shape `CapabilityManifestTest` already trusts* — a
registration is `api.<verb>( "pattern"`, and it is **gated** iff `ApiContext.withCapability( "cap"`
follows immediately (whitespace/newline tolerant). ⛔ Deliberately not a fresh hand-rolled parse: an
earlier ad-hoc regex matched **304 of 332** registrations and under-counted the mutating set by 8,
because it missed `PATCH` and some multi-line registrations. Every count below is generated, not tallied.

| | Count |
|---|---|
| Route registrations | **332** |
| Gated (`withCapability` + manifest entry) | **91** |
| Ungated | **241** |
| **Ungated AND mutating** (POST/PUT/PATCH/DELETE) | **83** |

⚠ **Correction to the filed row:** `ROUTE-UNGATED-DEFAULT-1` first said *"~223 ungated, 75 mutating"*.
The true figures are **241 / 83**. The row has been corrected.

---

## The headline: this is a VOCABULARY gap, not 83 oversights

Of the 83, **only 12 can be gated today with an existing capability**. **22 cannot be expressed at
all** — no capability in `Roles` covers them. The rest are, on inspection, correctly ungated.

| Bucket | n | Verdict |
|---|---|---|
| 1. Identity flow | 3 | ✅ Correctly ungated — these ESTABLISH the caller |
| 2. Self-verifying public | 2 | ✅ Correctly ungated — carry their own credential |
| 3. Self-service (caller's own state) | 4 | ✅ Correctly ungated — a capability would be wrong |
| 4. Read-shaped POST | 34 | ⚠ Gate only if you decide to gate reads — same posture as GET |
| 5. **Gateable now** | **12** | 🔴 **The actionable list** — an existing capability plainly fits |
| 6. **No capability exists** | **22** | 🔴 **Needs vocabulary first** — three whole families |
| 7. Self-limiting | 6 | ⚠ Gated by their own internals, not by the spine |

🔴 **There is no admin capability.** `Roles` declares ten (`canAuthorWorkbench`, `canOperateRuns`,
`canTriageRequirements`, `canOnboardConnections`, `canConfigureAccess`, `canAuthorAlertRules`,
`canCurateMenus`, `canOfferDatasets`, `canRequestShares`, `canApproveShares`) and **not one of them
means "administrator"**. ⇒ duckle's rule as literally stated — *"an unlisted route requires admin"* —
**is not expressible in inspecto today**; there is nothing for the default to point at. Adopting it
means first deciding whether an admin capability should exist, which is a product decision, not a patch.

---

## 1. Identity flow — correctly ungated (3)

`POST /auth/exchange` · `POST /auth/logout` · `POST /auth/refresh`

⛔ These cannot be gated by construction: they are how a caller *acquires* the Subject that a capability
check would read. Gating them is a bootstrap paradox. **No action.**

## 2. Self-verifying public — correctly ungated (2)

`POST /public/delivery-status/{adapterId}` · `POST /public/dashboards/{token}/query`

`ControlApi.isSelfVerifyingPublic` (`:769-770`) exempts both deliberately: the dashboard route carries an
**HMAC share token in the path** (BI-6) and the delivery-status route is an inbound provider callback
authenticated by provider signature (D8 §4.4). **No action** — but ⚠ note these are the two routes where
the credential is in the URL, so they are the ones to re-check if share-token handling ever changes.

## 3. Self-service — correctly ungated (4)

`POST /notifications/{id}/read` · `POST /notifications/read-all` · `PUT /notifications/preferences` ·
`DELETE /notifications/{id}`

Each mutates **the calling user's own** notification state. ⛔ A capability gate here would be actively
wrong: it would let an administrator decide whether you may mark *your own* notification read.
**No action.** ⚠ The one thing to verify separately is that they are scoped to the caller and cannot
address another user's notifications — that is an *authorization-by-ownership* question, not a
capability question, and this audit did not test it. → filed as a follow-up below.

## 4. Read-shaped POST — a posture decision, not a defect (34)

Previews, tests, probes, validators, dry-runs and queries that take a body and **persist nothing**:
the eight `components/*` preview/test/describe/validate routes · the three `config/preview|suggest` +
`/validate` · the three `connections/*/test|probe` · `/db/query` · `/bi/query` · `/enrichment/preview` ·
`/parsers/{id}/preview` · `/import/preview` · `/bundle/preview` · `/bundle/export` · `/geo/projection` ·
`/geo/routes` · `/inv/projection(+/neighbors)` · `/recon/breaks` · `/recon/columns` ·
`/queries/{id}/run` · `/pipelines/authored/{id}/dry-run` · `/expectations/evaluate(+/{id})` ·
`/alerts/evaluate` · `/cases/rules/{id}/evaluate`

They are POSTs only because they need a request body. ⇒ **Their correct posture is whatever you decide
for reads**, and today reads are ungated across the board. Gating these without gating GET would be
incoherent.

⚠ **Two deserve individual thought rather than the bucket verdict:**
- **`/db/query` and `/bi/query` read arbitrary data.** They persist nothing, but they are the widest
  data-read surface in the product. If any route in this bucket gets gated first, it is these two.
- **`/queries/{id}/run` is ALREADY a decided case** — STUDIO-HALVES-1 (2026-09-14) deliberately placed it
  outside `canAuthor()` because running a stored read-only query is operational, not authoring. ⛔ Do not
  re-open it here without re-opening that decision.

## 5. 🔴 Gateable now — the actionable list (12)

An existing capability plainly expresses the intent. **This is the work `ROUTE-UNGATED-DEFAULT-1` can
start on immediately.**

| Route | Proposed capability | Why this one |
|---|---|---|
| ~~`POST /requirements`~~ | — | ⛔ **STRUCK — REFUTED 2026-09-15.** It is **SEC-7(c), deliberate**: anyone may raise a Requirement, only a triager decides, pinned by `ControlApiRequirementTest.triageIsGatedButSubmissionIsOpen`. ⚠ The reasoning in this cell — *"its own sibling is already gated"* — is the exact inference that has now failed **six** times in this table; it is left visible, struck, as the worked example. |
| `POST /import` | `canAuthorWorkbench` | Sibling `POST /bundle/import` already carries it; both write config. |
| `POST /spaces/import` | `canAuthorWorkbench` | Writes an entire Space tree. ⚠ See bucket 6 — the rest of Space lifecycle has no capability, so this one is only *half* the family. |
| `POST /recon/run` | `canOperateRuns` | Runs a saved Reconciliation — the same shape as every other trigger already gated on it. |
| `POST /recon/promote` | `canAuthorWorkbench` | Promotes a recon result into stored config. |
| `POST /tags/rules/{id}/apply` | `canOperateRuns` | Bulk-applies a rule across assets. |
| `POST /tags/assignments/{k}/{id}` | `canAuthorWorkbench` | Writes assignment edges. |
| `DELETE /tags/assignments/{k}/{id}/{tag}` | `canAuthorWorkbench` | Removes them. |
| `POST /events/views` | `canAuthorWorkbench` | Saves a stored view. |
| `POST /events/views/{id}/delete` | `canAuthorWorkbench` | Deletes one. ⚠ Note it is a POST-shaped delete. |
| `POST /assist/settings` | `canAuthorWorkbench` | **Server-wide** assistant configuration. |
| `POST /assist/settings/test` | `canAuthorWorkbench` | Exercises those settings (may reach an external provider). |

⚠ **`canAuthorWorkbench` already guards 52 routes**, so leaning on it further is cheap but broadens an
already-broad capability. If the ten-capability vocabulary is going to be refined at all, do it *before*
adding twelve more users of the broadest one.

## 6. 🔴 No capability exists — three families, 22 routes

These are ungated **because nothing in the vocabulary can express them**, which is why "someone forgot"
is the wrong diagnosis.

### (a) Space lifecycle — 3 routes, the highest severity in the audit
`POST /spaces` · `PUT /spaces/{id}` · `DELETE /spaces/{id}`

🔴 **`DELETE /spaces/{id}` is the single worst case found.** `deleteSpace` (`SpaceRoutes.java:125-139`)
checks only `requireMultiSpace`, id validity, and a 409 refusing to purge the *last* space on disk —
**no capability check at all**. On Professional any authenticated caller can deregister a Space, and with
`?purge=true` delete its tree outright (unless it is the last one). ⇒ Needs a capability of its own;
`canConfigureAccess` is the closest existing fit but means something different.

### (b) Incident / Case triage — 16 routes
`POST /objects` · `PATCH /objects/{id}` · `ack` · `assign` · `attachments` · `comments` · `links`
(POST+DELETE) · `merge` · `rca` · `resolve` · `split` · `transition` · `POST /notes/{k}/{id}/attachments`
· `POST /notes/{k}/{id}/comments`

⚠ `canTriageRequirements` is **not** this — it guards *Requirements*, a different concept (GLOSSARY).
Incidents and Cases have no capability. ⇒ Either a `canTriageIncidents` capability, or an explicit
decision that Incident triage is open to every authenticated user (defensible for an ops tool — but it
should be *written down*, which is the whole point of the row).

### (c) Agent governance — 4 routes
`PUT /agent/policy` · `POST /agent/policy/kill-switch` · `POST /agent/approvals/{id}/decision` ·
`POST /agent/cases/{id}/feedback`

🔴 **The kill-switch and the policy are the controls over what the agent may do** — the routes most in
need of a gate, and the ones with nothing to gate them with. An approvals *decision* route being open is
the same shape of problem as an unguarded approval button.

## 7. Self-limiting — gated by their own internals (6)

`POST /agent/sessions` · `/{id}/ask` · `/{id}/ask/stream` · `POST /agent/tools/{name}` ·
`/agent/tools/{name}/derive` · `POST /assist/{...}`

These create agent sessions and invoke tools. They are not on the capability spine, but they are not
wide open either: **`POST /agent/tools/{name}` refuses mutating tools with 403** by design, so the tool
surface is non-mutating by construction, and the assistant self-gates elsewhere. ⚠ **This is a claim
worth re-verifying before relying on it** — this audit read it from the documented contract, not from a
test. If that refusal ever weakens, six routes change bucket silently.

---

## Recommended order

1. **Decide whether an admin capability exists.** Everything else is downstream, and duckle's literal
   rule cannot be adopted without it.
2. **Gate bucket 5** (12 routes) — no new vocabulary needed. Start with `POST /requirements`, whose own
   sibling is already gated: that one is an inconsistency, not a judgement call.
3. **Decide bucket 6's three families** (22 routes), worst-first: Space lifecycle → agent governance →
   Incident triage. Each needs a capability invented or an explicit "open by design" ruling.
4. **Then, and only then, ship the ratchet** so a new route must join a bucket rather than defaulting
   open. ⛔ Building the ratchet *first* would freeze 83 exemptions into a baseline nobody reviewed.
5. Leave bucket 4 alone until reads have a posture.

⚠ **This audit did not test authorization-by-ownership** — whether a caller can address *another user's*
notification, note, or object by id. That is a different question from capability gating and is filed
separately. ✅ **That row was filed and then REFUTED on 2026-09-15**: the per-row owner check it called missing already exists for objects (`ObjectRoutes.java:195-243` — `visibleTo` over `Subject.dataScopes()`, with out-of-scope reading as 404), notes route through the same gate, and notifications have no owner field at all — the feed is one shared per-space inbox, so there is no per-user feed to address across. What survives is a product question about whether the feed should ever be per-user, which lands on the platform's absent ownership model, not on route gating.


---

## As-built and decisions — 2026-09-15

**Operator decisions taken on this audit:**

1. ✅ **ONE `canAdminister` capability**, not three per-family ones. Rationale recorded on
   `Roles.CAN_ADMINISTER`: three capabilities would have been least-privilege-cleaner but left **no
   catch-all for the next unlisted route**, which is the hole this audit opened on. ⚠ Accepted
   consequence: whoever can delete a Space can also govern the agent.
2. ✅ **Audit all first, ratchet last.** Building the ratchet first would have frozen 83 unreviewed
   exemptions into a baseline nobody reviewed.
3. ✅ **Record the exemption reasoning** for the correctly-ungated routes (§1-§4, §7) rather than gating
   them — this section is that record, and it is what makes a later ratchet trustworthy.

**Shipped:** `Roles.CAN_ADMINISTER` (the vocabulary is now eleven, `security.md` owns the table), and
**six** routes gated on it — `PUT /spaces/{id}`, `DELETE /spaces/{id}` (§6a), and all four of §6c agent
governance. Each carries a `CapabilityManifest` entry; `CapabilityManifestTest` pins registration and
manifest to each other in both directions, and was proven red by un-gating one.
✅ **Plus the five §5 gates on `canAuthorWorkbench`, later on 2026-09-15 (compliance plan step 1)** —
`POST /import`, both `/events/views` writes, and the `/assist/settings` pair — so **eleven** routes were
gated on 2026-09-15 in total, and two whole route classes (`EventRoutes`, `AssistRoutes`) that had no gate of
any kind gained their first. Each new test asserts THREE statuses on purpose: 401 (no credential), **403 (a
present Subject lacking the capability — the only assertion that distinguishes a gate from a login)**, and the
permitted outcome; each also pins that the sibling READ stays open by policy.
⚠ **Verified in a clean worktree, not the shared tree**: reactor **4530 / 0 / 0 / 28 over 26 test modules**
(= the recorded 4526 + the four new tests). The same command in the shared checkout was RED — 33 failures +
8 errors in `DbStatusStoreTest`, `CollectorServiceTest`, `RunLeaseContractTest`, `ControlApiTest` — because a
peer session had nine uncommitted engine files in the tree; every one of those classes is green with this
patch on HEAD alone. ⛔ A red reactor in this sandbox is not evidence until `git status` says whose
changes it compiled.
✅ **Mutation-checked, not merely green**: removing the gate on `POST /assist/settings` turned both of its tests
and `CapabilityManifestTest` red, and nothing else — a test that cannot fail proves nothing, and these can.

🔴 **§5's list of 12 is now 11 — one of its entries was WRONG, and gating it turned the build red.**
⚠ **This line said "now 10 — two of its entries" until 2026-09-15, and the arithmetic was wrong.**
`POST /spaces` was never a §5 entry — it is §6(a) Space lifecycle, and its revert belongs to that bucket,
where the shipped work gated 2 of 3 and recorded the third as the recovery route. Only **one** §5 entry
(`POST /requirements`) was refuted. ⛔ **A row about uncounted items had its own count wrong** — the
board inherited the undercount as *"the 10 unverified gateable routes"*, so it was wrong in two places.

| Route | §5 said | Ground truth |
|---|---|---|
| `POST /requirements` | *"an inconsistency, not a judgement call"* — both siblings gated | **SEC-7(c), deliberate.** Anyone may raise a requirement; only a triager decides. Pinned by `ControlApiRequirementTest.triageIsGatedButSubmissionIsOpen`. |
| `POST /spaces` | gateable | **The RECOVERY route.** Deleting the last Space leaves a server hosting none; a gate here bricks it exactly as the old `writeRoot()` resolution did — every route failing, including the one that would recover it. Pinned by `ControlApiSpacesTest`. |

⛔ **Re-ground the remaining 10 one at a time before gating them.** This audit reviewed its *correctly
ungated* buckets carefully and evidently did not review its *should be gated* bucket to the same standard.
⛔ **A route being an outlier among its siblings is not evidence that the outlier is the mistake** — it is
equally often the sibling set that is wrong, or an asymmetry someone chose on purpose and wrote a test for.

**Still open:**

- **§6b Incident / Case triage (16 routes) — needs a PRODUCT decision first**, and it is not the same call
  as §6a. Triage is day-to-day support work, `canAdminister` is deliberately coarse, and the routes live in
  the optional `inspecto-ops` module. Three options: require `canAdminister`, give triage its own
  capability after all, or leave it open by design and say so.
- The 10 unverified §5 routes.

### §5 GROUNDED — all 11 re-verified one at a time, 2026-09-15

⛔ **The result: of eleven "gateable now" routes, FIVE are real gates. Three are deliberate exemptions
with the reasoning already in the code, and three need an operator call.** ⚠ That is the sixth, seventh
and eighth time this table's *should be gated* bucket has failed inspection — ⛔ **it was never reviewed
to the standard its *correctly ungated* buckets were**, and the fix is not to re-read it but to treat
every remaining entry as a hypothesis.

| Route | Verdict | The fact that decides it |
|---|---|---|
| `POST /import` | ✅ **GATED 2026-09-15** `canAuthorWorkbench` | Parses a bundle, writes into the Space's `config/` and **hot-registers connections and pipelines live** (`DataSourceRoutes:246-312`). Both true siblings are gated — `/bundle/import` (`BundleRoutes:118`), `/pipelines/import` (`PipelineBundleRoutes:96`). No manifest entry, no defending comment, and **no test exercises it at all**. |
| `POST /events/views` | ✅ **GATED 2026-09-15** `canAuthorWorkbench` | A saved view is **server-wide, not per-user**: `SavedView` is `(name, filters, createdAt)` with no subject field (`SavedView.java:9-20`) over one `SavedViewStore` per service (`CollectorService:116`). Any caller creates what every caller sees. |
| `POST /events/views/{id}/delete` | ✅ **GATED 2026-09-15** `canAuthorWorkbench` | Same store, no ownership check — deletes another caller's view. ⚠ A POST-shaped DELETE does not change the authorization question. |
| `POST /assist/settings` | ✅ **GATED 2026-09-15** `canAuthorWorkbench` | **Server-wide** provider config, one file (`AssistModelSettings.save`), reachable in Professional/Enterprise. 🔴 Its own javadoc names a `scope: assist.write` **that the route never enforces** — documented intent, unenforced. |
| `POST /assist/settings/test` | ✅ **GATED 2026-09-15** `canAuthorWorkbench` | Performs a **real outbound call** (`p.generate(...)`) to whatever `baseUrl` was last saved. ⛔ **Gate it WITH the route above, never alone** — see the security note below. |
| `POST /recon/run` | ⛔ **DELIBERATE EXEMPTION** | 🔴 The audit called it *"the same shape as every other trigger"*. **It triggers nothing** — stateless compute, nothing persisted, no job dispatched. Siblings `/recon/columns` and `/recon/breaks` are the same shape and were never proposed; `/bi/query` is ungated for this reason (`BiRoutes:44`). The class javadoc says these routes *"are stateless compute over ReconService"* with `/recon/promote` as *"the one exception… which writes"*. |
| `POST /tags/assignments/{k}/{id}` | ⛔ **DELIBERATE EXEMPTION** | `TagRoutes.java:60-62` states it: gated **per target via `AnnotationTargets`, not by capability**, because *"a capability gate would make 'can tag' independent of 'can see' — which is exactly how a tag would turn into an access grant."* |
| `DELETE /tags/assignments/{k}/{id}/{tag}` | ⛔ **DELIBERATE EXEMPTION** | Same comment, which covers both assignment routes together. |
| `POST /spaces/import` | ❓ **OPERATOR CALL** | Additive like `POST /spaces`, whose `⛔ NOT gated … and that is a DECISION` comment sits on the **previous line** and reasons *"creating a Space is additive"*. ⚠ But that comment **never names `/spaces/import`**, and no test covers it — treating it as covered is an inference, and inference is what failed here six times. |
| `POST /tags/rules/{id}/apply` | ❓ **OPERATOR CALL** | Its own comment (`TagRoutes.java:56`) calls it deliberately ungated as *"an operational action… not config"* — but the closest structural sibling, `DecisionRoutes` `POST /decision-rules/{name}/apply` (same bulk-apply-a-rule shape), **is** gated `canOperateRuns`. Two ops-shaped routes disagree. |
| `POST /recon/promote` | ❓ **OPERATOR CALL** | It does write — opens a durable Incident (`ReconRoutes:201-205`) — so it is not exempt. But `canAuthorWorkbench` has **zero precedent for Incident creation**, and the codebase already contradicts itself: `DecisionRoutes.apply()` opens Incidents under `canOperateRuns`, `ExpectationRoutes` opens them **fully ungated**. No Incident capability exists in `Roles`. |

🔴 **SECURITY NOTE — the assist pair is request-forgery-shaped, and that is a finding this audit did
not have.** `POST /assist/settings` is ungated and writes a **server-wide** `baseUrl`/`provider`;
`POST /assist/settings/test` then makes a **real outbound request to it**. ⇒ any authenticated caller can
point the server at an arbitrary URL and make it call out. ⚠ Unlike §6(c) agent governance — whose gate is
correct but **unreached**, because nothing stages `inspecto-intelligence` — **`inspecto-agent` IS staged**
(`package.ps1:340-348`), so this pair is live in every Professional and Enterprise bundle. ⛔ **Gate both in
one commit**: gating only the test route leaves the write route as the injection point.

⚠ **`EventRoutes` and `AssistRoutes` are 100% ungated FILES**, not outliers among gated siblings — neither
appears in `CapabilityManifest` at all, so the 2026-09-15 sweep never considered them. ⛔ That is a
different failure from the one this audit was written about: not *"a route nobody listed"* but *"a file
nobody opened"*, and a ratchet over route registrations would have caught it while a re-read of this
document would not.
- The ratchet — **last**, once the above are settled.

⚠ **Reachability caveat on §6c:** `/agent/*` answers 503 in every bundle today, because no packaging step
stages `inspecto-intelligence`. The gate is correct but unreached until that changes; it is not evidence
that agent governance is protected in a shipped product.

### Step 2 as-built — 2026-09-15 (compliance plan 2b + 2c)

**Every mutating registration in every reactor module is now in exactly one recorded state**, and
`CapabilityManifestTest.everyMutatingRouteIsGatedExemptOrPending` fails the build otherwise (mutation-verified
with a never-registered exemption). The three tables live in `CapabilityManifest`:

| State | Count | Where |
|---|---|---|
| Gated (`ENTRIES`) | 110 registrations | `withCapability` sites, bidirectionally pinned as before |
| Exempt (`EXEMPTIONS`) | **60** | `record Exemption(method, pattern, category, reason)` |
| Pending operator call (`PENDING_OPERATOR_CALLS`) | **4** | `record Pending(method, pattern, question)` — may only shrink |

Exemption categories are this audit's buckets — `identity-flow` (3) · `self-verifying-public` (2) ·
`self-service` (5, incl. `POST /requirements`) · `read-shaped` (33) · `self-limiting` (6) · `recovery-route` (1) ·
`stateless-compute` (1) · `target-visibility-gated` (2) — plus **`collaboration` (7)**, the word the plan's 2b
used for the open half of triage.

**§6(b) Incident/Case triage, classified per route from the handlers:**

| Route | Verdict | Why |
|---|---|---|
| `POST /objects/{id}/ack` · `/resolve` · `/transition` · `/assign` · `/merge` · `/split` | **`canAdminister`** | change the disposition (operator decision 1) |
| `PATCH /objects/{id}` | **`canAdminister`** | edits priority / severity / assignee — disposition, not annotation (`patchObject`) |
| `POST /cases/rules/{id}/evaluate` | **`canAdminister`** | 🔴 was in §4 "read-shaped" here — it **opens a Case** (`CaseRuleEvaluation.opened`); its own comment says "like transition", and a transition is now administrative |
| `POST /objects/{id}/comments` · `/attachments` · `/links` · `DELETE /links` · `/rca` · `POST /notes/{k}/{id}/comments` · `/attachments` | exempt `collaboration` | add to the record, disposition untouched |
| `POST /objects` (create) | **PENDING** | the same question as `POST /recon/promote`: which family does manually opening an Incident belong to? Deciding it under `canAdminister` by side effect would have answered an operator question silently |

⚠ **Two §4 rows carry a caveat in their recorded reason**: `POST /expectations/evaluate` and
`/expectations/{id}/evaluate` are exempt as read-shaped, but a breach may open an Incident (the plan's 2a table
already noted `ExpectationRoutes` opens Incidents ungated). They are to be re-classified together with the
pending Incident-creation call, not before it.

⚠ `ControlApiScopedObjectsTest`'s Subjects gained `canAdminister` — the class tests the data-scope guard
beneath the gate, and the gate wraps the guard (a caller without the capability gets 403 before existence-hiding
can answer 404). `ControlApiTriageGateTest` pins both halves against a Subject that holds `canOperateRuns` but
not `canAdminister`: proving an open route open with **no** Subject proves nothing, since no check runs then.

**What remains: 2a (four operator calls, `BACKLOG.md` §1) → step 3 → 4c/4d/4e.** ⛔ Step 3's boot refusal
cannot land while `PENDING_OPERATOR_CALLS` is non-empty.
