# Access Policy authoring UX — design

**Status: BUILT 2026-09-25 (S1–S4) on branch `lane-policy-guards` — all nine decisions answered (§8); as-built
facts in [`auth-security.md`](../okf/backend/editions/auth-security.md) §"Policy authoring guards". Close-out
(security.md §3.10, EDITIONS SEC-05, archive this file) waits for the lane to land on `master`.**
Row: `docs/BACKLOG.md` §3.8 *Security: policy-authoring UX* (P2, trigger fired 2026-09-15) · `EDITIONS.md`
SEC-05. Owner concept: [`okf/backend/editions/auth-security.md`](../okf/backend/editions/auth-security.md)
(mechanism) and [`okf/capabilities/security/security.md`](../okf/capabilities/security/security.md) §3.10
(capability). Related: [`route-gating-audit.md`](route-gating-audit.md).

The row's own framing is binding here: the read-only Policies tab and `GET /access/explain` already make a
bad policy **diagnosable**; this build is about **preventing** it, and it extends those two surfaces rather
than adding a parallel one. Vocabulary: **Access Policy** (GLOSSARY §1-A) — never a bare "rule".

---

## 1. What went wrong at the install — and how much of that is grounded

⚠ **The incident itself is not recorded.** Every mention of it (`BACKLOG.md:238`,
`archived-documents/backlog-snapshot-2026-09-24.md:1520-1527`) says only that "an install had hand-edited
policy TOON go wrong" on 2026-09-15. No doc, commit, or test names the file, the edit, or the symptom
(bricked API? a deny that never fired? a space leak?). ⇒ **The failure modes below are the ones the code
makes possible, found by reading it**, not a post-mortem. Decision **D1** asks for the real one; if it is
not in this list, the list is wrong, not the install.

Every failure mode is one of two shapes: **loud** (the engine denies everything) or **silent** (the doc
parses, validates, and does not mean what the author thinks). The silent ones are the dangerous half, and
**no current surface catches any of them before they take effect**.

| # | Failure mode | Shape | Grounding |
|---|---|---|---|
| F1 | Any TOON damage or any bad value in a hand edit marks the **whole doc** unreadable ⇒ every authenticated request in that Space is DENIED | loud | `AccessPolicies.parseFile` `:118-127` catches everything → `Doc(…, true)`; `PolicyEngine.decide` `:76-81` DENY on `unreadable` |
| F2 | A mistyped attribute ref (`subject.role` for `subject.roles`, `subject.capabilites`) resolves to `null`, and a comparison against `null`/type-mismatch is **false** — a deny guarded by it **never fires**; a deny guarded by `not (…)` over it **always fires** | silent | `Conditions.java:29-34` ("a missing attribute is null", type-mismatch-is-false); `Conditions.parse` validates syntax only |
| F3 | A mistyped literal (`'canConfigureAcess'`, a role name that does not exist) is equally silent — `contains` just returns false | silent | same; `AccessPolicies.validate` `:131-180` never looks inside `when` beyond parsing it |
| F4 | `target.resourceKinds` is free text — `incidents`, `Incident ` → lower-cased and trimmed, but `incidents` never matches the row kind `incident` | silent | `targetValues` `:182-195` (no vocabulary); row kinds come from `ObjectType` (`ALERT, INCIDENT, CASE, TASK`, `ObjectRoutes.java:275`), `investigation` (`InvestigationRoutes.java:1001`) and annotation target kinds (`AnnotationTargets.java:115`) |
| F5 | A `resource.*` ref on a policy with **no** `resourceKinds` target: at the route PEP there is no `resource` map, so the ref is `null` | silent | `PolicyEngine.context` `:158`, `:175-179` adds `resource` only when `resourceKind != null`; route PEP passes `null` (`ControlApi.java:884`) |
| F6 | Authoring a policy **named** `space-isolation` / `space-isolation-rows` replaces the built-in tenancy deny wholesale — including its `canConfigureAccess` exemption — with no warning | silent | `PolicyEngine.effective` `:142-148` (overlay per name); SEED `:60-66` |
| F7 | **Self-lockout.** A `deny` targeting `write` (or untargeted) that holds for the admin denies `PUT /access/policies` itself at the route PEP — the only recovery is a disk edit | loud, unrecoverable in-product | `ControlApi.authorize` `:878-890` runs before the handler for every non-public path; authored policies carry no admin exemption (only seeds do, `PolicyEngine.java:56-57`) |
| F8 | On-disk key is `resource_kinds`, wire key is `resourceKinds`; both are accepted, so a hand edit mixing styles works — but a third spelling (`resourcekinds`) is silently ignored ⇒ the target is **unconstrained** | silent | `validate` `:160-161` reads exactly two spellings; an unknown `target` key is not refused |
| F9 | An untargeted `deny` with a blank `when` denies every request by every subject | loud | `validate` `:168-169` (`ctx -> true`); `targets` `:152-156` (empty ⇒ unconstrained) |

F8 generalises: `validate` refuses unknown *values* for `effect` and `actions`, but **ignores unknown
keys** on a policy and on `target` (`:141-162`) — so `efect: deny` is a 422, but `wen: …` is silently a
policy with no condition (F9 when it is also untargeted).

## 2. As-is

**Model + parser + validator (core, every edition).** `AccessPolicies` (`inspecto/.../control/AccessPolicies.java`):
`Policy(name, effect, actions, resourceKinds, when, condition)` `:65-75`; `ACTIONS = {read, write, operate}`
`:52`; limits 200 policies / 64 target values / 2000-char `when` `:55-57`. One grammar, `validate` `:134`,
shared by the file parser (`parseFile` `:118`) and the PUT. Mtime/size cache `:100-116`; atomic canonical
write `:200-217`.

**Evaluation (Enterprise, `inspecto-policy`).** `PolicyEngine.decide` `:73-96` deny-overrides → allow →
ABSTAIN; `explain` `:112-137` — same outcome, full per-policy trace `{name, effect, source, targeted,
conditionHeld}`. Context `:158-182` = `subject.{id, capabilities, dataScopes, roles}` + allowlisted claims
(`roles.toon identity.attributeClaims`), `env.{action, route, space}`, `resource.*` at row level only.

**Routes (`AccessRoutes.java`).** `GET /access/policies` `:49,122-141` (authored + seed rows, `source`
tagged; `error` when unreadable) · `PUT /access/policies` `:50-51,195-201` (gated `canConfigureAccess`;
write-root 503; optional `If-Match` 409 via `ETags.requireMatch` `ETags.java:34-39`; `validate` 422) ·
`GET /access/explain` `:56,150-180` (**deliberately a GET and ungated** — a POST is a `write` the policy
under test could 403; see the comment at `:52-55`). The action verb comes from `ControlApi.actionFor`
`:919-923` (read / operate-if-manifest-says-`canOperateRuns` / write).

**Gating apparatus a new route must clear.** `CapabilityManifest` `ENTRIES` (`PUT /access/policies` at `:53`)
matched **by string literal** at the registration site; `CapabilityManifestTest` fails on drift both ways;
a mutating route with neither an entry nor an `EXEMPTIONS` row (category + reason) **refuses to boot**
(`ROUTE-UNGATED-DEFAULT-1`, auth-security.md §"An undeclared mutating route"); every route needs a
`docs/api/openapi-v1.json` path (`/access/policies` `:4951`, `/access/explain` `:4977`, pinned by
`OpenApiPathsContractTest`); read-shaped POSTs are an exemption category, not a free pass
(`route-gating-audit.md` §4).

**UI.** Settings ▸ Access ▸ **Policies** tab = `access-policies.component.ts/.html` — a read-only table
(name / effect / target / condition / built-in-vs-authored chip, html `:28-73`), an unreadable-doc alert
(`:13-17`), and the *Why denied?* form (route, method, resourceKind → decision banner + trace, `:77-158`).
The header copy says authoring is TOON/API (`:8`). `AccessService` has `policies()` and `explain()` but **no
save** (`access.service.ts:134-143`). The sibling **Roles** tab is the in-repo precedent for an authored
overlay editor: card list + `RoleFormDialog` (checkbox vocabulary, dirty guard, immutable name) + full-replace
save (`access-roles.component.ts`, `role-form.dialog.ts`), gated on `LensService.canConfigureAccess`
(`lens.service.ts:190`, identity capability, action node `access.configure`, `access-catalog.ts:79`).

**Tests today.** `ControlApiAccessPoliciesTest` (shape/422/round-trip/source badge/explain-disabled/
unreadable), `PolicyEngineTest` (incl. explain≡decide, seed override, unreadable), `ControlApiPolicyEnforcementTest`,
`access-policies.component.spec.ts` (6 cases, all read-only).

## 3. Options

**A. Form editor only.** Add create/edit/delete on the Policies tab (a `PolicyFormDialog` mirroring
`RoleFormDialog`) that PUTs the existing route. Closes F1 and F8 for anyone who uses the UI (the server
writes canonical TOON). Closes **none** of F2–F7, F9 — a form happily submits a typo'd ref. Cheapest; does
not meet the row's "prevent" bar.

**B. A + server-side lint + lockout guard.** Extend `AccessPolicies.validate` with the checks in §4 —
hard 422s for the unambiguous ones, **warnings** returned alongside the result for the judgement calls — and
refuse a PUT whose result would deny the saver's own next `PUT /access/policies`. Needs one new pure
function in `inspecto-util` (`Conditions.refs(source)` — the parser already tokenises `REF`s,
`Conditions.java:165,233`) and one widened SPI default (`AccessDecider.simulate`, §5). **No new route.**
Closes F2–F9 for UI and API authors alike, because the grammar is the one both paths already share.

**C. B + draft simulation matrix.** Before save, show a matrix of *seeded/authored roles × {read, write,
operate}* (and optionally × resource kind) with the decision **before → after** the draft, cells that flip
highlighted. This is the "matrix" the row names, and it is the only thing that catches a policy that is
*valid and well-typed but wrong* (the author meant "developers" and wrote the condition for everyone).
Requires evaluating a **draft** doc against **synthetic** subjects — the "arbitrary subject" feature the
explain route explicitly deferred (`AccessRoutes.java:147-149`) — so it needs a new route.

**D. A structured condition builder** (dropdowns for attribute / operator / value instead of a `when`
string). Kills F2/F3 by construction, but the grammar is recursive (`and or not ( )`), so a builder either
caps expressiveness or grows into an expression editor; and the existing seeds (`PolicyEngine.java:62-66`)
already exceed a flat builder. Recorded and **not recommended** now: lint (B) gets the same safety for the
typo class at a fraction of the cost, and a builder can be layered on later.

⛔ **Rejected: a client-side TS evaluator for the preview.** It would be a hand-mirrored copy of
`PolicyEngine`'s combining logic and `Conditions`' semantics — the exact shape that drifted four times in the
SPA already. The server is the only evaluator.

## 4. Recommendation — **B, then C**

Ship B first (it closes every grounded failure mode and adds no route), then C as its own slice (it adds the
one new route and the only new security surface). The Policies tab becomes: effective table **with row
actions** (edit / delete / "new policy", authored rows only; seed rows get "override…" behind a confirm) →
lint findings inline per row → *Why denied?* unchanged → (C) a *Preview impact* matrix inside the edit
dialog.

**Lint set (proposed; the 422-vs-warning split is D3):**

| Check | Closes | Proposed severity |
|---|---|---|
| Unknown key on a policy or on `target` (`wen`, `resourcekinds`) | F8 | **422** |
| Ref root not in `subject.` / `env.` / `resource.` | F2 | **422** |
| `subject.<k>` where `k` ∉ {`id`, `capabilities`, `dataScopes`, `roles`} ∪ `identity.attributeClaims` | F2 | **422** (the set is closed and known at save time) |
| `env.<k>` where `k` ∉ {`action`, `route`, `space`} | F2 | **422** |
| `resource.*` ref on a policy with no `resourceKinds` target | F5 | warning |
| String literal compared with `subject.capabilities` ∉ `Roles.KNOWN_CAPABILITIES` (`Roles.java:124`) | F3 | warning |
| String literal compared with `subject.roles` ∉ `Roles.effective(root)` names | F3 | warning (roles live in the IdP; an unseeded role may be legitimate) |
| `resourceKinds` value ∉ known row kinds | F4 | warning (no registry exists today — §5 S1 adds a static list) |
| Name equals a seed policy name | F6 | warning + UI confirm naming what is lost (the admin exemption) |
| Untargeted `deny` with blank `when` | F9 | warning |
| Result would DENY the saving subject's `write` on `/access/policies` | F7 | **422** `would-lock-out` |

Warnings ride the PUT response as `warnings: [{policy, code, message}]` and the GET as the same list per
row, so the table shows them for **hand-edited** docs too — that is how prevention reaches the file path
that bit the install, without inventing a file watcher. A hand edit that fails a 422-class check still marks
the doc unreadable exactly as today (F1 stays fail-closed); the difference is that the GET's `error` now
names the offending policy and check instead of a generic "unreadable" (D5).

## 5. Slices + test plan

**S1 — grammar lint (core + util, no UI).** `Conditions.refs(String)` → the set of dotted refs, plus the
literal operands per ref for `== != in contains`. `AccessPolicies.validate` gains the 422 checks and returns
`(policies, warnings)`; unknown-key refusal. A static known-row-kinds list lives next to `ACTIONS`.
*Tests:* `ConditionsTest` (refs extraction incl. nested `not`/parens); `ControlApiAccessPoliciesTest`
+ one case per 422 check and per warning; **a mutation check**: disable each check and see its case go red
(house rule — a negative test must have a probe that would otherwise succeed). A hand-edited file with `wen:`
must load as unreadable with the message naming the policy.

**S2 — lockout guard (policy SPI).** Widen `AccessDecider` with a default-empty
`simulate(ex, draft, subject, action, route, resourceKind)` → `Decision` (default `ABSTAIN` ⇒ Personal/
Professional never refuse). `PolicyEngine` implements it by running `decide`'s loop over
`effective(draft)` — refactor `decide` and `explain` to share one private evaluator so the three cannot
diverge. `savePolicies` calls it for the caller's own subject on `PUT /access/policies` before writing.
*Tests:* `PolicyEngineTest` simulate≡decide on the same doc; `ControlApiPolicyEnforcementTest` — a draft
that denies the saver is 422 and **the file on disk is unchanged**; a draft that denies someone else is
accepted; must run under `-Pedition-enterprise` (the default reactor omits `inspecto-policy`).

**S3 — Policies tab becomes an editor (UI).** `AccessService.savePolicies(authored, etag)` sending
`If-Match` from the GET's ETag; `PolicyFormDialog` (name immutable after create, effect radio, action
checkboxes over `ACTIONS`, resource-kind chips over the known list, `when` textarea with server-returned
lint shown inline, `guardDirtyClose`); row actions gated on `lens.canConfigureAccess()`; seed override behind
`InspectoConfirmService` naming the lost exemption; header copy `:8` rewritten. The table and the explain
panel are **the same component**, extended — no new tab. *Tests:* vitest specs for create / edit / delete /
409-stale / 422-lockout message / warnings rendered / read-only for a subject without the capability; axe
pass per the angular-ui skill; drive it in the preview (house rule for anything visible).

**S4 — impact matrix (new route; only if D6 = yes).** `POST /access/policies/preview` body `{policies,
probes?}` → per-role × action (× kind) `{before, after}` computed by `simulate` over synthetic subjects
built from `Roles.effective` (capabilities + role name; no claims). Must clear **all** of:
`withCapability("canConfigureAccess", …)` as a string literal · a `CapabilityManifest.ENTRIES` row in the
same commit · a `docs/api/openapi-v1.json` path (else `OpenApiPathsContractTest` fails in a module you did
not touch) · a real-HTTP test class with a Subject that **lacks** the capability (a Subject-less test makes
the gate a no-op). ⚠ It is a POST, so the route PEP evaluates it as a `write` against the **current** doc —
a subject already locked out cannot preview; that is acceptable (S2 prevents reaching that state in-product)
but must be stated in the route's comment. UI: a *Preview impact* expansion in the dialog, flipped cells
highlighted, save allowed regardless.

**Close-out:** distil into `auth-security.md` §"Policy operability" + `security.md` §3.10, flip EDITIONS
SEC-05, archive this file.

## 6. Out of scope

A structured condition builder (option D) · per-user (not per-role) simulation · simulating claims beyond
role + capabilities · authoring `roles.toon identity.attributeClaims` from this tab (it has a home: Roles) ·
a file watcher for disk edits (the GET-side lint covers it at next read).

## 7. Noticed, not fixed

`ControlApi.actionFor`'s javadoc (`ControlApi.java:916-918`) still calls the explain route
`POST /access/explain`; it is a GET (`AccessRoutes.java:56`). One-word doc fix, unrelated to this row.

## 8. Decisions owed (operator)

1. **D1 — What actually went wrong at the 2026-09-15 install?** Nothing records it. Which of F1–F9 (or
   something else) was it? If it is none of them, this design is aimed at the wrong target.
2. **D2 — Option.** B then C as recommended, or A only, or B only (no new route)?
3. **D3 — 422 vs warning.** Accept §4's split? In particular: should an unknown `subject.<k>` be a hard 422
   (breaks any hand-authored doc that references a claim not yet allowlisted in `roles.toon`) or a warning?
4. **D4 — Unknown keys become a 422** (`wen:`, `resourcekinds:`). ⚠ This makes an existing on-disk doc
   with a stray key **unreadable ⇒ deny-all** on upgrade (breaking changes are free, but this one bites at
   runtime). Accept, or make it a warning for one release?
5. **D5 — Name the failing policy in the unreadable-doc `error`.** The GET is ungated, so the message would
   be readable by any authenticated subject. Acceptable (the policies themselves already are), or show
   detail only to `canConfigureAccess` holders?
6. **D6 — Build the impact matrix (S4)?** It is the one new route and it evaluates the draft against
   synthetic role subjects — a capability the explain route deliberately deferred. Yes / later / no.
7. **D7 — Matrix axes.** Roles × {read, write, operate} only, or also × resource kind (4 object types +
   `investigation` + annotation kinds ⇒ a much wider table)?
8. **D8 — Seed override.** Keep "author a policy with the seed's name to replace it" (F6) as a confirmed UI
   action, or forbid overriding `space-isolation*` from the UI entirely (API/disk only)?
9. **D9 — Lockout guard scope.** Refuse only a draft that denies the *saver's* next `PUT /access/policies`
   (proposed), or also one that denies **every** `canConfigureAccess` holder among the seeded roles?

### Decisions of record (operator, 2026-09-25)

The operator answered D1 and delegated D2–D9 to "the design's recommendation; where it gives none, the most
fail-closed reasonable option". Every "taken on recommendation" answer below is **reversible**.

| # | Answer | How it was reached |
|---|---|---|
| D1 | The incident stays unrecorded; **guard all nine failure modes at save time** rather than wait for a report | operator |
| D2 | **B then C**, in the recommended order (S1 lint → S2 lockout → S3 editor → S4 matrix) | taken on recommendation, reversible |
| D3 | **§4's split as proposed**: unknown `subject.<k>` is a hard 422 (the claim set is closed at save time); ref-root and `env.<k>` 422; F3–F6 warnings. ⚠ One deviation, forced by the operator's requirement that F9 be refused *before* it takes effect: **F9 is a 422 (`deny-everything`), not the proposed warning** | taken on recommendation (F9: operator requirement) |
| D4 | **Unknown keys are a 422** (the §4 table's severity), on the PUT and on disk; the upgrade path (stray key ⇒ unreadable ⇒ deny-all on Enterprise) is accepted, tested, and in the release notes | taken on recommendation, reversible |
| D5 | **Name the policy and check in the unreadable-doc `error`** — but only to `canConfigureAccess` holders (no Subject = Personal = shown); others get the generic fail-closed notice. The design named the detail, not the audience; the narrower audience is the fail-closed choice | no recommendation → most fail-closed, reversible |
| D6 | **Build the matrix (S4)** — part of "B then C" | taken on recommendation, reversible |
| D7 | **Roles × {read, write, operate} at route level, plus × each known resource kind a saved or draft policy targets** — the kind axis only where a policy can bite, so every flip is visible and the table stays bounded by `RESOURCE_KINDS` | no recommendation → most fail-closed, reversible |
| D8 | **Keep seed override as a confirmed UI action** (§4: "seed rows get override… behind a confirm"); the confirm and the `seed-override` warning quote the built-in condition, and so the exemption, that the replacement drops | taken on recommendation, reversible |
| D9 | **The saver only** (proposed). A draft denying every *other* access configurer is accepted | taken on recommendation, reversible |

**Added while building (not in §4, each closes a hole the checks themselves opened or left):**
`ambiguous-key` — `resourceKinds` and `resource_kinds` both given used to let the wire spelling win
silently; `PUT /access/roles` refuses a claim-allowlist change that would make the policies doc unreadable
(F2's 422 made `roles.toon` a second way to reach F1); `GET /access/policies` serves `resourceKinds` so the
SPA carries no mirror of the F4 vocabulary. **Not built:** the preview's optional `probes` (D7 fixed the
axes instead); an optional `route` body field binds `env.route` (default `/`).
