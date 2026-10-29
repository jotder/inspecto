# Link Analysis — next product steps (`LA-PRODUCT-NEXT-1`)

**Status:** PLAN 2026-10-29, amended the same day (phases 4 and 5 widened, phase 6 and the §5 Reach review added at
the operator's request). Nothing is built. It comes from the 2026-10-29 Link Analysis feature brainstorm. The operator
delegated every call ("balance complexity against real-world need"), so each decision below is
**assistant-decided under operator delegation, pending confirmation**. The exception is the three picks the operator
endorsed: pattern-by-example, the timeline scrubber and the entity-resolution spike.

**Board row:** `LA-PRODUCT-NEXT-1` in `docs/BACKLOG.md` §3.12. **Tracker:** listed in
[`link-analysis-roadmap.md`](link-analysis-roadmap.md) §4.

## 1. What the brainstorm found already built (do not re-plan)

Most of the brainstorm's ideas already exist. Grounded on 2026-10-29 in the la-core hub and the code:

| Idea | Already there |
|---|---|
| Time axis | *Filter edges by time* (time column + cutoff); `window` op (seals a window); `compare` op (two windows, sealed difference); `temporal` op (burst / periodicity); `snapshot` marker (a log position, no file) |
| Temporal motifs, pattern library | pattern-match builder with temporal steps (LA-14a) and branching fan-in / fan-out stages (LA-14b); whole-Dataset branching search on the server (`PatternRoutes`, `PatternQueryCompiler`, `TemporalMotifMatcher`); Space pattern packs (component kind `pattern-pack`, `pattern-packs.ts`) |
| Live detection | standing detection over a bound **value-measure** Alert Rule (LD-1…LD-7), with owner authority re-decided before every sweep (`StandingDetection`) |
| Collaboration, sign-off | Members, Share and embed, Drafts with four-eyes promote, Oversight and approvals |
| Entity resolution | Identity resolution: a manual, reasoned, reversible **Merge** into a hash-chained Identity Fact log, plus a mapping Dataset for bulk pairs and the `resolve` step |
| Evidence behind the picture | *Show the rows* (dock Rows tab), the Dossier |
| Reach | Geo Map brushed both ways with the graph (`GeoLinkBrushService`), embed links into portals, *Pin to a Widget* |
| Role administration in la-app | `access.page.ts` mounts the console's `AccessRolesComponent` unchanged (the shared-component pattern works) |

## 2. Decisions

| Id | Decision | Why |
|---|---|---|
| NX-D1 | Roles stay in the platform's ONE store (`roles.toon`, `/access/*`); the LA UI edits it. No LA-only role store. | Operator answer 2026-10-29. One source of truth. |
| NX-D2 | Who holds a role stays in the identity provider; LA says so and links there. No "recently seen holders" list. | That list would be a new store of personal data that goes stale. |
| NX-D3 | LA shows only LA capabilities, in LA words, with a *Show all* switch for admins. | An LA admin should not reason about `canAuthorWorkbench`. |
| NX-D4 | Phases 1, 2, 4, 5a, 5b and 6 are startable now. Phases 3 and 5c need one grounding or measuring pass first. §4 waits for demand. | Necessity first (security, LA-only customers), then the analyst picks the operator endorsed. |
| NX-D5 | A pattern becomes a standing detection as a **new Measure kind**, the match count of a saved pattern. It sits under the SAME `StandingDetection` authority and re-decide, not as a second detection path. | One authority model to audit. Today's sweep reads value measures only. |
| NX-D6 | Entity-resolution suggestions **never merge**. A suggestion only pre-fills the existing Merge form, and the analyst states the reason. An accepted one is recorded with `suggested` provenance. | A wrong merge puts a false link into evidence, so a person stays the decider. |
| NX-D7 | Reach adds no phase (see §5). | Map, embed and Widgets already cover it. Scheduled delivery moves investigation data off the platform, so it waits for a real ask. |

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

### Phase 4 — the timeline scrubber (UI M, no new backend)

This builds on the existing *Filter edges by time* control instead of adding a second one. The 2026-10 grounding
refused G6 `timebar` because it duplicated that control.

- **Scrub:** a handle you drag over the data's time range, with an optional window width (*the last 7 days up to
  here*). Links outside the window hide, as the cutoff hides them today.
- **Play / step:** advance the handle in fixed steps. With reduced motion, Play steps without animating.
- **Age fade:** inside the window, a link's opacity follows its age relative to the handle, so new activity
  stands out. It's a display option that is on by default, and it must keep the link-colour contrast that the a11y
  guard checks.
- **Seal this window:** inside an Investigation, one action turns the current window into the existing `window` op,
  so the moment becomes a replayable step. Outside an Investigation the scrubber is display state only, covered by Undo.
- **Done when:** driven in the browser preview on the `la-showcase` mule transfers (scrub, play, fade, seal), and
  the sealed step replays to the same Working Set.

### Phase 5 — pattern-by-example

**5a. A pattern from the selection (UI M).**
- *Use as pattern* on a canvas selection:
  - **A path** becomes linear `PatternStep[]`: node kinds, link kinds and direction come from the selection, and
    gaps come from edge times when the graph has a time column.
  - **A star or fan around one node** becomes branching `BranchStage[]`. The anchor is the most-connected selected
    node. Each stage gets its direction and its breadth (the selected counterparty count), plus a per-leg threshold
    band from the selected links' value attribute when one is mapped.
- Either one opens in the existing pattern-match builder for the analyst to loosen. *Run on server* uses
  `PatternRoutes` over the whole Dataset, so the result is not limited to the drawn graph.
- **Done when:** specs derive a three-hop path and a five-leg fan-in correctly, and on a showcase graph the
  server run finds the source shape plus every other match.

**5b. Save it as a Space pattern pack (UI S).**
- *Save as pattern pack* writes a `pattern-pack` component through the generic component route, with a name,
  a category and a description. It then appears in every analyst's pack picker.
- **Grounding owed:** which capability the generic `/components` route demands for `pattern-pack`.
  `ImportCapabilityGuard.requireKind` is the table. Show the action only to holders, per the "a capability the
  client never reads is a bug" rule.

**5c. A pattern as standing detection (backend M–L; measure first).**
- Per NX-D5, add a Measure kind *pattern matches*: the match count of a saved pattern pack over the bound
  Dataset. *Watch* on it creates an Alert Rule, and *Enable standing detection* runs it under the existing
  `StandingDetection` authority (owner re-decided, masking snapshot, aggregate-only audit, Alert evidence that
  names no entity).
- **Measure before building:** the time of one whole-Dataset `PatternRoutes` run on the largest showcase
  Dataset and at the index-scale corpus. A sweep repeats it every interval. If one run exceeds the sweep budget,
  5c stays parked and records the number.
- **Done when:** a pattern-match Alert Rule fires once on a planted ring in a synthetic fixture, and stays silent
  after the owner loses view of the Dataset (`DATASET_NOT_SHARED`).

### Phase 6 — entity-resolution suggestions: spike, then go / no-go (S spike)

The question is whether LA can suggest *these two identifiers may be one entity* well enough that analysts trust it.

- **Candidates:** pairs of the same entity type that share neighbours (shared devices, partners, counterparties),
  scored with the shared-neighbour similarity the toolbox already offers. Attribute equality (name, address) counts
  when such columns are mapped. Pairs already merged or retracted are excluded.
- **Ground truth:** a synthetic fixture with planted split identities, synthetic data only. It contains easy
  pairs, hard pairs, and decoys that share a busy hub but are different people.
- **Measure:** precision at the top 20 and 50 suggestions, and how many decoys rank above a true pair.
  **Go** if top-20 precision is at least 0.9 and no hub decoy reaches the top 20.
- **If go:** a *Possible duplicates* list in Settings ▸ Identities, where each row pre-fills the existing Merge form
  (NX-D6). **If no-go:** record the numbers here and close the idea.

## 4. Deferred, with the reason (no board row; reopen on a named ask)

| Idea | Why not now |
|---|---|
| Access change history page | Phase 1 already records held access changes as Pending Changes. A separate page waits for an ask. |
| Profiles and Policies tabs in la-app | Same mount pattern as Roles. Add them when an LA-only customer needs deny overlays. |
| Team Drafts, live co-presence | Team Drafts is already in the roadmap §2. Co-presence is XL and nobody has asked for it. |
| Auto-narrated Dossier | Needs the AI module and `INTELLIGENCE-GLOSSARY-ANNOTATED-1`. Evidence wording must be exact. |
| Next-best-expansion ranking | The ranking is unvalidated, and a wrong hint misleads an investigation. |
| Super-node sampling | No measured pain; the configurable caps and ranked tables cover it. |
| Cross-Space lookup | Legal and privacy questions first, and erasure (LDP-D11) is still open. |
| Scheduled delivery of a view or Dossier | §5. |
| Schema-driven admin config shared with the console | Large. LA settings already reuse the console components. |

## 5. Reach — review (2026-10-29)

| Reach idea | Verdict |
|---|---|
| Map and graph together | **Built.** The Geo Map and the graph brush each other (`GeoLinkBrushService`); *View on map* pivots with the same record. |
| Show the graph elsewhere | **Built.** Embed links (per-Space frame allow-list, token redaction, auto-revoke) and *Pin to a Widget* for dashboards. |
| Tell someone when something changed | **Built.** Standing detection fires Alerts through the normal alert path, and phase 5c adds patterns. |
| Scheduled delivery of a saved view or Dossier | **Deferred, hold: demand.** It copies investigation data off the platform (masking, retention and erasure follow the copy). The embed link and the Alert already cover *look at it any time* and *tell me when it changes*. Reopen when a named consumer needs a recurring file. |
| Cross-Space lookup | **Deferred** (§4). |
