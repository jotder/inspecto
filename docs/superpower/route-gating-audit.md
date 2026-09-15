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
| `POST /requirements` | `canTriageRequirements` | 🔴 **Its own sibling is already gated** — `/requirements/{id}/decision` and `/deliver` both carry it. Creating a Requirement is ungated while deciding one is not. |
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
**no capability check at all**. On Standard any authenticated caller can deregister a Space, and with
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

🔴 **§5's list of 12 is now 10 — two of its entries were WRONG, and gating them turned the build red.**

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
- The ratchet — **last**, once the above are settled.

⚠ **Reachability caveat on §6c:** `/agent/*` answers 503 in every bundle today, because no packaging step
stages `inspecto-intelligence`. The gate is correct but unreached until that changes; it is not evidence
that agent governance is protected in a shipped product.
