# Link Analysis — next product steps (`LA-PRODUCT-NEXT-1`)

**Status:** SHIPPED 2026-10-30 (`bb85cc532`, `29d8c6e0f`, merge `ddf10a369`); 5c measured and parked. Was: PLAN 2026-10-29, amended the same day (phases 4 and 5 widened, phase 6 and the §5 Reach review added at
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

**BUILT 2026-10-30** (lane `mc-access-roles`, independently verified). It also makes every Access screen read a held `202`
as *held*, via the shared `heldChange()`. As-built: the access admin guide, maker-checker section.

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

**BUILT 2026-10-30** (same lane; review findings fixed and re-checked). As built, it differs from the plan below in
three ways:
- The tag is `CapabilityManifest.PRODUCTS`, published as `products` on `GET /access/roles`.
- The path is `/access/roles/{roleId}/effective`, declared in the module OpenAPI fragment.
- The la-app page reuses the console Roles editor's existing strike-through view, so no separate "What can this role
  do?" screen was built.

`canAuthorWorkbench` stays visible in LA, because it gates Datasets, Entity Types and saved views there. As-built:
the access admin guide, R1 paragraph.

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

**BUILT 2026-10-30 as "leads only"** (operator decision 2026-10-30, the same lane). The grounding found that a non-member
gets 404 on an Investigation by design (D19), so an admin-wide review would WIDEN access. Built instead: la-app
Settings ▸ My settings ▸ *Investigation access*, over the Investigations the caller can already open, with Revoke where
they are the lead. It is UI only and uses the existing routes, with no new backend route. Investigation access is
members plus embed links; a "view-only share" belongs to saved views. Admin oversight across the Space is NOT built:
it would need a new capability that reverses D19, so reopen it only on a named ask. As-built: the la-core user guide.

- One admin list across the Space's Investigations: Members, view-only shares and live embed links per
  Investigation, with Revoke. It reuses the per-Investigation routes for each action.
- **Grounding owed before building:** whether a cross-Investigation read exists, and which capability
  gates an admin's view of Investigations they are not a member of. The answer follows the RowScope /
  ABAC posture; it never widens what an admin can read.
- **Done when:** an admin sees and revokes a member, a share and an embed link from one screen, and every
  revoke writes the same audit row the per-Investigation action writes.

### Phase 4 — the timeline scrubber (UI M, no new backend)

> **BUILT 2026-10-30 (uncommitted, lane `mc-access-roles`).** Scrub, Play and step were already shipped as the
> timeline playback strip (2026-10-05), so this phase extended it. It added a Window picker (slice / 1 / 7 / 30 days /
> from the start), an age fade that is on by default with a 0.45 floor above the 0.2 dim (the new
> `GraphEmphasis.edgeOpacity`), and *Seal this window* (the `window` op, offered only when the Investigation's
> `timeCol` is the timeline's column and the caller holds `canManageIncidents`).
>
> Deviations from the bullets below:
> - links outside the window are **dimmed, not hidden**, so the layout does not re-run on every tick;
> - the scrubber is a highlight, not Undo-recorded state.
>
> Still owed: the browser drive on `la-showcase` and the "replays to the same Working Set" check.

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

> **BUILT 2026-10-30 (uncommitted, lane `mc-access-roles`):** `pattern-from-selection.ts` plus *Use selection as
> pattern* in the toolbox, and a removable *After the previous hop* chip on a linear step.
>
> Deviations from the bullets below:
> - **Times give the order only, not gap ceilings.** The builder can't edit a gap, and an exact gap fits one example.
> - **No value attribute is mapped anywhere,** so a fan gets a band only when its legs carry one numeric column.
> - **Server runs are unchanged.** *Search whole Dataset* stays on a truncated graph, and the Investigation server run
>   stays pack-only.
>
> Still owed: the showcase drive.
>
> **5b BUILT 2026-10-30 (uncommitted):** *Save as pattern pack* (`save-pattern-pack.dialog.ts`, `patternPackToContent`).
> - **Capability:** the grounding found `pattern-pack` absent from `ImportCapabilityGuard.KIND_CAPABILITY`, so the
>   generic door demands `canAuthorWorkbench`. The action shows only to holders of it.
> - **Visibility:** the pack has no `shares`, so everyone in the Space sees it.
> - **Not built:** saving over an existing pack.
>
> **5c is not started:** it needs measuring first.
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

> **MEASURED 2026-10-30: PARKED, by this section's own rule.**
>
> **Method:**
> - One whole-Dataset `POST /inv/pattern/branching` over real HTTP, using a throwaway probe test that was not kept.
> - The motif was a 5-leg fan-in with a `900 ≤ AMOUNT < 1000` band and a 72 h window, then a 2-leg fan-out after it.
> - The feed was a synthetic transfer view, `range(n)` over n/20 accounts, with a planted ring. `pattern.timeout_s` was
>   raised to 300 s, and each size was run twice on the dev laptop.
>
> **Results:**
>
> | Rows | Runs | Found the planted ring? |
> |---|---|---|
> | 10⁵ | 1.8 s, 1.6 s | yes |
> | 10⁶ | 12.3 s, 13.5 s | yes |
> | 10⁷ | 108 s, 140 s | yes |
> | 10⁸ | interrupted at the 300 s cap, both runs | — |
>
> Cost is about linear, roughly 11–14 µs per row.
>
> **Verdict:**
> - Already at 10⁶ rows, one run exceeds the route's own default budget (`pattern.timeout_s` = 10 s).
> - At 10⁷ rows, one run takes about two minutes. A sweep repeating it every interval is not viable on the real
>   Datasets this would watch. The index is proven to 10⁸ edges.
>
> **Caveat:** the view computes every row, its string concatenation and its timestamp cast on each run. A Parquet-backed
> Dataset may scan faster, but not by the 10–100× needed.
>
> **What would reopen it:** an incremental sweep that reads only the legs since the last run (per-day delta, or the
> index's delta files) instead of the whole Dataset. That is its own design item, not 5c.
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

**MEASURED 2026-10-30 — GO, with conditions; the list is not built yet.**

*Method.* A throwaway vitest probe, deleted after the run. It built a synthetic graph of about 950 nodes (under the
1 000-node `linkPrediction` browser cap) and ran three seeds. The graph holds:
- 60 single accounts, each with its own device, phone and address, 3–6 counterparties drawn with a popularity skew,
  and three hubs (bank, telco, shop) at 60 % each;
- 20 **easy** splits that share device, phone and address, plus most counterparties;
- 20 **hard** splits that share only a phone and one counterparty;
- 15 **household** decoys: two people at one address, half of them also sharing the home device;
- 15 **hub** decoys: two people sharing every hub and the two busiest counterparties.

Candidates are account pairs with at least one shared neighbour (about 16–17k per seed). They are ranked by the
toolbox's existing scores, with no attribute matching.

| Score | Top-20 precision | Top-50 precision | Hub decoys in top 20 | Household decoys in top 50 | Hard pairs in top 50 |
|---|---|---|---|---|---|
| Adamic-Adar | **1.00 / 1.00 / 1.00** | 0.68 / 0.74 / 0.68 | 0 / 0 / 0 | 10 / 9 / 13 | 14 / 17 / 14 |
| Common neighbours | 0.75 / 0.85 / 0.90 | 0.32 / 0.34 / 0.40 | 0 | 1 | 1–2 |
| Jaccard | 0.90 / 0.90 / 1.00 | 0.40 / 0.42 / 0.40 | 0 | 0 | 0–1 |

*Verdict.* Adamic-Adar meets the gate on every seed: top-20 precision is 1.0, and no hub decoy reaches the top 20.
It is also the only score that finds the hard pairs. Common neighbours and Jaccard fail the gate.

*Conditions* (they are what the numbers say about real use):
1. **Show only the top 20.** Below the top 20, about one suggestion in four is a household: two people sharing an
   address or a home device.
2. **Every row shows its reason.** List the shared neighbours by kind, for example *shares phone, device, address*.
   That lets the analyst reject a household at a glance, and a *shares an address only* row reads as weak.
3. **Name-attribute matching** would separate households from splits. It was not measured, because the fixture
   maps no name column.

*Caveat.* The fixture is ours, and its 20 easy pairs are exactly the top 20, so the gate measures the easy case.
Real data has more households and shared corporate devices than this fixture does.

**6b BUILT 2026-10-30 (uncommitted).** The operator chose the current canvas as the candidate source, so the list lives
where the canvas is: Analysis ▸ Similarity ▸ *Possible duplicates*, not Settings ▸ Identities. It shows the top 20
pairs with their shared-neighbour reason. Each row's **Merge…** prefills the existing Merge form through the
*Merge with...* path. The browser cap of 1 000 nodes applies. A server-side source is not built; reopen it on a named
ask. Specs: `graph-analysis.spec.ts` (*possible duplicates…*) and `link-analysis-toolbox.component.spec.ts` (*phase 6…*).

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
