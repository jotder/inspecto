# Link Analysis — next product steps (`LA-PRODUCT-NEXT-1`)

**Status:** PLAN 2026-10-29, nothing built. From the 2026-10-29 Link Analysis feature brainstorm. The operator
delegated every call ("balance complexity against real-world need"), so each decision below is
**assistant-decided under operator delegation, pending confirmation**.

**Board row:** `LA-PRODUCT-NEXT-1` in `docs/BACKLOG.md` §3.12. **Tracker:** listed in
[`link-analysis-roadmap.md`](link-analysis-roadmap.md) §4.

## 1. What the brainstorm found already built (do not re-plan)

Most of the brainstorm's ideas already exist. Grounded on 2026-10-29 in the la-core hub and the code:

| Idea | Already there |
|---|---|
| Time axis | *Filter edges by time* (time column + cutoff); `compare` op (two windows, sealed difference); `temporal` op (burst / periodicity); `snapshot` marker |
| Temporal motifs, pattern library | pattern-match builder, temporal steps (LA-14a), branching motifs (LA-14b), Space pattern packs (`pattern-packs.ts`, `registry/pattern-packs/`) |
| Collaboration, sign-off | Members, Share and embed, Drafts with four-eyes promote, Oversight and approvals |
| Entity resolution | Identity resolution (declaring two identifiers are one entity), the Investigation alias book |
| Evidence behind the picture | *Show the rows* (dock Rows tab), the Dossier |
| Role administration in la-app | `access.page.ts` mounts the console's `AccessRolesComponent` unchanged (the shared-component pattern works) |

## 2. Decisions

| Id | Decision | Why |
|---|---|---|
| NX-D1 | Roles stay in the platform's ONE store (`roles.toon`, `/access/*`); the LA UI edits it. No LA-only role store. | Operator answer 2026-10-29. One source of truth. |
| NX-D2 | Who holds a role stays in the identity provider; LA says so and links there. No "recently seen holders" list. | That list would be a new store of personal data that goes stale. |
| NX-D3 | LA shows only LA capabilities, in LA words, with a *Show all* switch for admins. | An LA admin should not reason about `canAuthorWorkbench`. |
| NX-D4 | Phases 1, 2, 4, 5 are startable now; phase 3 needs one grounding pass first. Everything in §4 waits for demand. | Necessity first (security, LA-only customers), then the cheapest analyst wins. |

## 3. Phases (in build order)

### Phase 1 — maker-checker covers role and access-policy edits (platform, S–M)

**Gap (verified 2026-10-29):** `AccessRoutes.saveCatalog` and `saveProfile` / `deleteProfile` call
`PendingChanges.hold`, but `saveRoles` (`AccessRoutes.java` line 114) and `savePolicies` write straight to disk.
`ConfigWriteFunnelTest.WRITERS` records `savePolicies` as not governed only because the document "is not a
ComponentStore kind". That reason is about storage, not security. So an Approval Policy cannot hold the most privileged
access write: the one that grants capabilities, `canApproveChanges` included. The weaker deny overlays are held.

- Add governable kinds `access-roles` and `access-policies` to `ApprovalPolicy.GOVERNABLE`. Route both
  writes through `PendingChanges.hold`, add both routes to `PendingChanges.REPLAYABLE`, and drop the
  `savePolicies` exemption from `ConfigWriteFunnelTest`.
- Keep the existing guards on apply: the ETag match, `Roles.validate`, and the would-lock-out check on policies,
  which re-runs at apply time as the author.
- Recovery when no approver is left is the host administrator editing the file, as today. Document it in the
  access admin guide.
- **Done when:** a real-HTTP test, with approval required on `access-roles`, gets 202 on `PUT /access/roles`,
  403 when the author approves their own change, and an applied change when a second admin approves. The funnel
  test passes without the exemption.

### Phase 2 — the LA Access page speaks LA (backend S + UI M)

- Tag each capability with the products it applies to (`CapabilityManifest` is the single source;
  `CapabilityManifestTest` pins the tags), and publish the tags on `GET /access/roles`.
- In la-app's Access page, filter `AccessRolesComponent` to LA capabilities and show them with LA labels. This
  extends the existing `labels` input from role names to capabilities. Add a *Show all* switch.
- Add a **"What can this role do?"** read: `GET /access/roles/{name}/effective`. It resolves one role through the
  role table and the Access Profiles (plus Access Policies on Advanced) and returns each capability as
  allowed or denied with its reason: which grant, which Profile deny, which Policy. The read is ungated like
  its `/access` siblings. It works on Standard, where today's `/access/explain` returns `enabled: false`.
- **Done when:** the route passes the new-route checklist (manifest, `openapi-v1.json`, a real-HTTP test),
  and an analyst-only role shows exactly the LA capabilities, with a Profile deny struck through and named.

### Phase 3 — Investigation access review (M; one grounding pass first)

- One admin list across the Space's Investigations: Members, view-only shares and live embed links per
  Investigation, with Revoke. It reuses the per-Investigation routes for each action.
- **Grounding owed before building:** whether a cross-Investigation read exists, and which capability
  gates an admin's view of Investigations they are not a member of. The answer follows the RowScope /
  ABAC posture; it never widens what an admin can read.
- **Done when:** an admin sees and revokes a member, a share and an embed link from one screen, and every
  revoke writes the same audit row the per-Investigation action writes.

### Phase 4 — play the time cutoff (UI S)

- Add **Play / step** next to the existing *Filter edges by time* cutoff. It advances the cutoff in fixed steps
  over the data's time range, so the graph is seen growing. This is display state only: nothing is sealed, and
  Undo covers it.
- It does not adopt G6 `timebar`. The 2026-10 grounding refused it because it duplicates the existing control.
- **Done when:** driven in the browser preview on the `la-showcase` mule transfers. It must respect reduced
  motion: when the user prefers it, Play steps the cutoff without animating.

### Phase 5 — a pattern from the selection (UI S–M)

- Select a path on the canvas, then *Use as pattern*. That turns it into `PatternStep[]`, taking node kinds,
  link kinds and direction from the selected elements (and gaps from the edge times when the graph has a time
  column), and opens it in the existing pattern-match builder, ready to run. Linear paths only.
- **Done when:** a spec turns a three-hop path into three steps, and the builder finds the source path plus
  every other matching path in a showcase graph.

## 4. Deferred, with the reason (no board row; reopen on a named ask)

| Idea | Why not now |
|---|---|
| Access change history page | Phase 1 already records held access changes as Pending Changes. A separate page waits for an ask. |
| Profiles and Policies tabs in la-app | Same mount pattern as Roles. Add them when an LA-only customer needs deny overlays. |
| Save a selection-derived pattern as a Space pattern pack | Wait for analysts to use phase 5 first. |
| Team Drafts, live co-presence | Team Drafts is already in the roadmap §2. Co-presence is XL and nobody has asked for it. |
| Auto-narrated Dossier | Needs the AI module and `INTELLIGENCE-GLOSSARY-ANNOTATED-1`. Evidence wording must be exact. |
| Next-best-expansion ranking | The ranking is unvalidated, and a wrong hint misleads an investigation. |
| Entity-resolution *suggestions* | Identity resolution exists. A suggested merge risks a false link in evidence. |
| Super-node sampling | No measured pain; the configurable caps and ranked tables cover it. |
| Cross-Space lookup | Legal and privacy questions first, and erasure (LDP-D11) is still open. |
| Schema-driven admin config shared with the console | Large. LA settings already reuse the console components. |
