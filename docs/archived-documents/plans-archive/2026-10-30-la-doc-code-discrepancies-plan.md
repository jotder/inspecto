# Link Analysis Doc-vs-Code Discrepancy Fixes Implementation Plan

> **As built (2026-11-01, `LA-DOC-CODE-SYNC-1`, closed).** Taken over from a peer's uncommitted draft.
> - **D1 / Task 1 (code) was already fixed on master by `5bbdc9c82`**: `openNodeDetail` passes the resolved
>   `entityType` to `entityPageOf`, with a spec. Nothing was changed here.
> - **D2-D7 fixed in the la-core hub**, every number re-read from code: 97 routes in la-api's `module.toon` + 7 in
>   geo-link's = 104; 28 la-api `RouteModule`s + 4 in geo-link (`EmbedExpirySweepModule` and `IntegrationWebhooks`
>   serve no route); 30 route-serving modules override `featureIds()`; limits from `LaLimit.java`.
> - **Hypotheses the code refuted:** (a) D7 was inverted: the right-panel tabs are labelled **On screen** /
>   **Whole Dataset** in code (G16, operator 2026-10-29; ids stay `browser` / `server`), so §2 and §8 were right and
>   §4, the migration table and Part II were fixed instead. (b) The rate limit is server-wide (`la-limits.toon`,
>   `Layer.INSTALL`), not per Space. (c) `DELETE /inv/investigations/{id}` is lead-only and answers 409 while linked to
>   an open Case or holding a pending approval (not "owner or administrator"). (d) The dock's **Steps** tab lists one
>   line per step; no per-step undo was claimed.
> - **Also fixed** (same defects, outside the plan's list): the rate limit in `admin-guide-demo.md` and the "no delete
>   route" answer in `tests-stakeholder-qa.md`. Left alone: architecture §6's "live route inventory lists 78" (a
>   measurement, not re-run).

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all seven discrepancies identified between the Link Analysis OKF hub documentation (`docs/okf/product/module/la-core/`) and the Link Analysis implementation (`la/*`, `platform/inspecto-entity-store`, and `inspecto-ui/projects/link-analysis/`): fix the one code defect in `openNodeDetail` (`entityPage` resolution) and update six stale/contradictory documentation areas to match the shipped codebase.

**Architecture:**
- **Code fix (`inspecto-ui/projects/link-analysis`):** `LinkAnalysisComponent.openNodeDetail` resolves `entityType` on line 4153 as `(node.data.entityType as string | undefined) ?? this.growEntityType() ?? undefined`, but line 4168 still passes `this.profile().investigate?.entityType` to `this.entityPageOf(...)`. Passing the resolved `entityType` (and reusing `key`) makes the node detail dialog's *Entity page* link work on `generic`-profile Spaces and on multi-type graphs where a node's `entityType` differs from the profile's default `investigate.entityType`.
- **Documentation fixes (`docs/okf/product/module/la-core/`):** Bring `architecture.md`, `architecture-overview.md`, `features-link-analysis.md`, `api-link-analysis.md`, `spec.md`, `user-guide-link-analysis.md`, and `brochure-inspecto-la-whitepaper.md` in line with `module.toon`, `LaLimit.java`, `InvestigationRoutes.java`, `FsInvestigationLayout.java`, and `link-analysis.component.ts`.

**Tech Stack:** Angular 19 / TypeScript (Vitest unit tests via `npm test`), Markdown / OKF hub documentation (`node tools/check-vocabulary.mjs`, `node tools/check-doc-counts.mjs`, `node tools/check-backlog-items.mjs`).

**Spec:** `docs/okf/product/module/la-core/spec.md`, `docs/okf/product/module/la-core/architecture.md`

**Global Constraints:**
- Follow `docs/GLOSSARY.md` canonical vocabulary strictly (use `Pipeline`, `Dataset`, `Incident`, `Expectation` / `Alert Rule` / `Decision Rule`, `Measure`, `Collector`; never use banned synonyms or retired edition names `Personal` / `Professional` / `Enterprise`).
- Do not touch unrelated uncommitted changes in the working tree (`SPACE-DATA-ROOT-1` files).

**Review Focus:**
- **Task 1 (Standard):** Verify `openNodeDetail` passes the resolved `entityType` and `key` into `entityPageOf`, and that `link-analysis.component.spec.ts` covers both a `generic`-profile Space with a typed start (`genericEntityType`) and a node whose `node.data.entityType` overrides the profile default.
- **Tasks 2–3 (Light):** Verify every updated count, route, limit, and UI tab label in `docs/okf/product/module/la-core/*.md` matches the source of truth in code (`module.toon`, `LaLimit.java`, `InvestigationRoutes.java`, `link-analysis.component.ts`) and passes `node tools/check-vocabulary.mjs`.

---

## Discrepancy Classification Summary

| ID | Area | Discrepancy | Problematic Side | Root Cause |
|---|---|---|---|---|
| **D1** | Node detail dialog `entityPage` link (`link-analysis.component.ts:4168` vs `architecture.md` §3.4/§6.12, `user-guide-link-analysis.md:3410`) | Docs state a start is typed by the Entity Type it names and the node dialog links to the Entity page (`/entities/<type>/<key>`). In `openNodeDetail`, `insight.entityType` resolves `(node.data.entityType as string \| undefined) ?? this.growEntityType() ?? undefined` (line 4153), but `insight.entityPage` calls `this.entityPageOf(this.profile().investigate?.entityType, ...)` (line 4168). On `generic` profile Spaces `entityPage` is always `null`; on multi-type canvases a non-default node links to the wrong Entity Type. | **Code** | `T-042` updated `insight.entityType` in `openNodeDetail` to use `(node.data.entityType) ?? this.growEntityType()`, but missed updating `this.entityPageOf(...)` 15 lines below. |
| **D2** | Route & `RouteModule` counts (`features-link-analysis.md:30`, `architecture-overview.md:31`, `architecture.md:131,691,724,1451`) | Docs claim `87` `/inv` and `/geo` routes (`features-link-analysis.md:30`, `architecture-overview.md:31`), `21 RouteModules` (`architecture.md:131,724`), `24 RouteModules in la-api plus InvestigationMeasureRoutes in geo-link` (`architecture.md:691`), and `la-api -> RouteModule (27)` (`architecture.md:1451`). Actual code has **28** `RouteModule`s and **97** routes in `la-api` (`la/inspecto-la-api/src/main/resources/META-INF/inspecto/module.toon`) plus **4** `RouteModule` providers (`InvestigationMeasureRoutes` and `InvestigationDataChangesRoutes` serving **7** routes, plus lifecycle modules `EmbedExpirySweepModule` and `IntegrationWebhooks`) in `geo-link` (`la/inspecto-geo-link/src/main/resources/META-INF/inspecto/module.toon`), totalling **104** routes. | **Doc** | Multiple prose locations were not updated when `EntityPageRoutes`, `ExclusionListRoutes`, `UploadRoutes`, `InvestigationDataChangesRoutes`, and `POST /inv/expand/preview` were added. |
| **D3** | Unsaved Investigations (`POST /inv/expand/preview`) and dock `Steps` tab (`api-link-analysis.md:18`, `architecture.md` §3.1/§6, `user-guide-link-analysis.md:230,3387`) | `LA-UNSAVED-INVESTIGATION-1` added `POST /inv/expand/preview` (`InvestigationRoutes.java:162,688`, `la-api/module.toon:10`) and the `{ id: 'steps', label: 'Steps', unsaved: true }` dock tab (`link-analysis.component.ts:443`). Neither `api-link-analysis.md` nor `architecture.md` lists `POST /inv/expand/preview`, and `user-guide-link-analysis.md` §4 (line 230) and Part II (line 3387) omit the `Steps` dock tab. | **Doc** | `LA-UNSAVED-INVESTIGATION-1` (`f83bce424`..`226571957`, 2026-10-30) updated `user-guide-link-analysis.md` §11.1 but was not distilled into `api-link-analysis.md`, `architecture.md`, or the dock tab lists in `user-guide-link-analysis.md`. |
| **D4** | Investigation delete (`DELETE /inv/investigations/{id}`), rename (`PATCH /inv/investigations/{id}`), and FS layout (`architecture.md:284,697,769`) | `architecture.md:769` states `"There is no Investigation delete or close route today"`, `architecture.md:697` omits `PATCH` and `DELETE /inv/investigations/{id}` from the `InvestigationRoutes` table row, and `architecture.md:284` omits `investigation-details.json` and `embeds/` from the filesystem layout list (`FsInvestigationLayout.java:255,315`, `InvestigationRoutes.java:204,221`). | **Doc** | `LA-INVESTIGATION-DELETE-1` and `LA-INVESTIGATION-RENAME-1` (`PATCH /inv/investigations/{id}`) shipped without updating `architecture.md` §3.2 and §6. |
| **D5** | Configurable limits & rate-limit defaults (`architecture.md:235,241,400,430`, `user-guide-link-analysis.md:950,3295` vs `LaLimit.java`) | Prose cites pre-P3/P4 numbers: (a) `user-guide-link-analysis.md:950` says `"burst of 20 requests per user, then one every 3 seconds"` vs `LaLimit.RATE_LINK_ANALYSIS_BURST = 30`, `RATE_LINK_ANALYSIS_PER_S = 0.5` (1 per 2 s); (b) `architecture.md:235` says neighbour page default `50, ceiling 200` vs `LaLimit.NEIGHBOURS_PAGE_DEFAULT = 100`, `NEIGHBOURS_PAGE_MAX = 400`; (c) `architecture.md:241` says `connect {ids (2..20), maxHops (default and ceiling 3)}` vs `LaLimit.CONNECT_MAX_SEEDS = 40`, `CONNECT_MAX_HOPS = 6` (contradicting line 1635 in the same file); (d) `architecture.md:400` says `at most 2 000 rows` for `POST /inv/reference-rows` vs `LaLimit.REFERENCE_MAX_ROWS = 4_000`; (e) `architecture.md:430` and `user-guide-link-analysis.md:3295` say `at most 100 Case-linked Investigations and the newest 500 attachment records` vs `LaLimit.CASE_HISTORY_MAX_LINKED_INVESTIGATIONS = 200` and `CASE_HISTORY_MAX_ATTACHMENTS = 1_000`. | **Doc** | `LA-CONFIG-LIMITS-1` (P3 doubling) and the 2026-10-14 rate-limit retune updated the §9 table in `architecture.md` but left older hardcoded numbers in §3, §4, and `user-guide-link-analysis.md`. |
| **D6** | Stale warnings & edition table row (`architecture.md:289,1505`, `spec.md:68`, `brochure-inspecto-la-whitepaper.md:71`) | (a) `architecture.md:1505` warns that `entityPageOf` in `link-analysis.component.ts` does not key on `/bootstrap` `entityPages` yet (`LA-EDITION-PLACEMENT-1`), but `LA-EDITION-PLACEMENT-1` shipped on 2026-10-28 (`link-analysis.component.ts:4121`). (b) `brochure-inspecto-la-whitepaper.md:71` marks `Entity profile pages` as `yes` on Basic, whereas `D-LAE4` (`architecture.md:1479`) gates `entityPages` to Standard and above. (c) `spec.md:68` and `architecture.md:289` warn that `FsInvestigationLayout` class Javadoc still says `<audit root>/investigations/<id>`, whereas `FsInvestigationLayout.java:14` already says `<write root>/audit/snapshots/investigations/<id>/...`. | **Doc** | Follow-up code fixes landed without removing the corresponding doc caveats or splitting the whitepaper table row. |
| **D7** | Right-panel tab names in User Guide (`user-guide-link-analysis.md:87,488-530` vs `link-analysis.component.ts:440`) | `user-guide-link-analysis.md` §2 (line 87) and §8 (lines 488–530) refer to the right panel's tabs as **On screen** and **Whole Dataset**, contradicting §4 (line 221), §18 (line 1017), and `link-analysis.component.ts:440` where the tabs are **Browser**, **Server**, and **Investigation** (`LA-PANELS-REDESIGN-1`). | **Doc** | `LA-PANELS-REDESIGN-1` renamed the right-panel tabs to `Browser · Server · Investigation` and updated §4/§18, leaving §2 and §8 with the old tab names. |

---

### Task 1: Fix `openNodeDetail` `entityPage` link in `LinkAnalysisComponent` (Discrepancy D1 — Code Problem)

**Files:**
- Modify: `inspecto-ui/projects/link-analysis/src/link-analysis/link-analysis.component.ts:4145-4169`
- Test: `inspecto-ui/projects/link-analysis/src/link-analysis/link-analysis.component.spec.ts:6150-6170`

- [ ] **Step 1: Write the failing unit test in `link-analysis.component.spec.ts`**

In `inspecto-ui/projects/link-analysis/src/link-analysis/link-analysis.component.spec.ts` inside `describe('LinkAnalysisComponent - the node dialog Entity page link follows /bootstrap entityPages (LA-EDITION-PLACEMENT-1)', ...)` around line 6168, add a test proving `openNodeDetail` builds `insight.entityPage` from the node's resolved `entityType` (both `node.data.entityType` and `growEntityType()` fallback on a `generic`-profile Space):

```ts
    it('openNodeDetail builds insight.entityPage from the resolved entityType (node.data.entityType or growEntityType) rather than only profile().investigate?.entityType', () => {
        TestBed.resetTestingModule();
        const { fixture } = create({
            features: { ...STANDARD_EDITION_FEATURES, flags: signal({ entityPages: true }) },
        });
        const c = fixture.componentInstance as unknown as {
            route: unknown;
            profileId: { set: (v: string) => void };
            genericEntityType: { set: (v: string) => void };
            baseGraph: { set: (v: unknown) => void };
            dialog: { open: ReturnType<typeof vi.fn> };
            openNodeDetail: (id: string) => void;
        };
        onLinkAnalysisRoute(c);
        c.profileId.set('generic');
        c.genericEntityType.set('account');
        c.baseGraph.set({
            nodes: [
                { id: 'n1', data: { label: 'ACC-01', kind: 'entity' } },
                { id: 'n2', data: { label: 'DEV-99', kind: 'entity', entityType: 'device' } },
            ],
            edges: [],
        });
        const openSpy = vi.spyOn(c.dialog, 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);

        c.openNodeDetail('n1');
        const insight1 = (openSpy.mock.calls[0][1] as { data: { insight: { entityType?: string; entityPage?: unknown } } }).data.insight;
        expect(insight1.entityType).toBe('account');
        expect(String(insight1.entityPage)).toBe('/s1/entities/account/ACC-01');

        c.openNodeDetail('n2');
        const insight2 = (openSpy.mock.calls[1][1] as { data: { insight: { entityType?: string; entityPage?: unknown } } }).data.insight;
        expect(insight2.entityType).toBe('device');
        expect(String(insight2.entityPage)).toBe('/s1/entities/device/DEV-99');
    });
```

- [ ] **Step 2: Run the spec to verify it fails**

Run from `inspecto-ui/`:
```powershell
npx ng test link-analysis --watch=false --include src/link-analysis/link-analysis.component.spec.ts
```
Expected: FAIL — `String(insight1.entityPage)` and `String(insight2.entityPage)` are `'null'` because `this.profile().investigate?.entityType` is `undefined` under the `'generic'` profile.

- [ ] **Step 3: Fix `openNodeDetail` in `link-analysis.component.ts`**

In `inspecto-ui/projects/link-analysis/src/link-analysis/link-analysis.component.ts:4145-4169`, extract `entityType` once and pass `entityType` and `key` to `this.entityPageOf(entityType, key)`:

```ts
        const key = rawIdsOf(node)[0] ?? node.data.label;
        // the node's own type first, then the type the graph was drawn as (T-042: a start from an Entity Type has one)
        const entityType = (node.data.entityType as string | undefined) ?? this.growEntityType() ?? undefined;
        const insight: NodeInsightContext = {
            nodeId: id,
            label: node.data.label,
            key,
            listKey: ws?.exploreAliases?.[key],
            profileId: this.profileId() ?? 'generic',
            entityType,
            investigationId: invId,
            graphKey: invId && ws ? `${invId}:${ws.hash}` : null,
            serverId: ids?.serverNode(id) ?? null,
            serverNodeIds: (this.canvasData()?.nodes ?? [])
                .map((n) => ids?.serverNode(n.id))
                .filter((x): x is string => !!x),
            originLabel: (sid) => {
                const cid = ids?.node(sid);
                return cid ? labelIn(cid) : sid;
            },
            canRunGraph: this.canRunGraphOnServer(),
            canManageLists: this.lens.canManageIncidents(),
            onListChanged: () => this.investigation.entityListsChanged.update((n) => n + 1),
            canHide: !this.investigation.active(),
            entityPage: this.entityPageOf(entityType, key),
        };
```

- [ ] **Step 4: Re-run the unit test to verify it passes**

Run from `inspecto-ui/`:
```powershell
npx ng test link-analysis --watch=false --include src/link-analysis/link-analysis.component.spec.ts
```
Expected: PASS.

---

### Task 2: Reconcile `architecture.md`, `architecture-overview.md`, `features-link-analysis.md`, `api-link-analysis.md`, and `spec.md` with Code (Discrepancies D2, D3, D4, D5, D6)

**Files:**
- Modify: `docs/okf/product/module/la-core/features-link-analysis.md:30`
- Modify: `docs/okf/product/module/la-core/architecture-overview.md:31`
- Modify: `docs/okf/product/module/la-core/api-link-analysis.md:18`
- Modify: `docs/okf/product/module/la-core/spec.md:68`
- Modify: `docs/okf/product/module/la-core/architecture.md:131,235,241,284-290,400,430,691,697,724,769,1451,1505`

- [ ] **Step 1: Update route counts in `features-link-analysis.md` and `architecture-overview.md` (D2)**

In `docs/okf/product/module/la-core/features-link-analysis.md:30` and `docs/okf/product/module/la-core/architecture-overview.md:31`, change:
```markdown
| **The `/inv` and `/geo` routes (87)** |
```
to:
```markdown
| **The `/inv` and `/geo` routes (104)** |
```

- [ ] **Step 2: Add `POST /inv/expand/preview` to `api-link-analysis.md` (D3)**

In `docs/okf/product/module/la-core/api-link-analysis.md:18`, update the `Investigations` row from:
```markdown
| **Investigations** (16) | `/api/v1/inv/investigations` (GET, POST), `…/{id}` (GET, PATCH, DELETE), `…/{id}/ops` (POST), `…/{id}/expand/preview` (POST), `…/{id}/undo` (POST), `…/{id}/verify` (GET), `…/{id}/replay` (POST), `…/{id}/coverage` (GET), `…/{id}/compare` (GET), `…/{id}/dossier` (GET), `…/{id}/fork` (POST), `…/{id}/case` (POST), `…/{id}/save-as-template` (POST) |
```
to:
```markdown
| **Investigations** (17) | `/api/v1/inv/expand/preview` (POST), `/api/v1/inv/investigations` (GET, POST), `…/{id}` (GET, PATCH, DELETE), `…/{id}/ops` (POST), `…/{id}/expand/preview` (POST), `…/{id}/undo` (POST), `…/{id}/verify` (GET), `…/{id}/replay` (POST), `…/{id}/coverage` (GET), `…/{id}/compare` (GET), `…/{id}/dossier` (GET), `…/{id}/fork` (POST), `…/{id}/case` (POST), `…/{id}/save-as-template` (POST) |
```

- [ ] **Step 3: Remove stale `FsInvestigationLayout` Javadoc warning in `spec.md` (D6c)**

In `docs/okf/product/module/la-core/spec.md:68`, replace:
```markdown
  (`FsInvestigationLayout.root`: `writeRoot.resolve("audit/snapshots/investigations").resolve(id)` — note that `FsInvestigationLayout`'s class Javadoc still says `<audit root>/investigations/<id>`, which is stale):
```
with:
```markdown
  (`FsInvestigationLayout.root`: `writeRoot.resolve("audit/snapshots/investigations").resolve(id)`):
```

- [ ] **Step 4: Update `architecture.md` for D2, D3, D4, D5, and D6**

Apply the following targeted updates in `docs/okf/product/module/la-core/architecture.md`:
1. **Line 131 (`RouteModule` count in module table — D2):** Change `21 RouteModules (below)` to `28 RouteModules (97 routes, below)` and for `inspecto-geo-link` note `4 RouteModules (InvestigationMeasureRoutes, InvestigationDataChangesRoutes, EmbedExpirySweepModule, IntegrationWebhooks; 7 HTTP routes)`.
2. **Line 235 (neighbour list default/ceiling — D5b):** Change `Page default 50, ceiling 200` to `Page default 100, ceiling 400 (the Space's neighbours.page_default and neighbours.page_max)`.
3. **Line 241 (`connect` op seed/hop limits — D5c):** Change `connect {ids (2..20), maxHops (default and ceiling 3), dataset?, mapping?}` to `connect {ids (2..connect.max_seeds, default 40), maxHops (default connect.default_hops = 3, ceiling connect.max_hops = 6), dataset?, mapping?}`.
4. **Lines 284–290 (`FsInvestigationLayout` files and Javadoc note — D4, D6c):** Add `investigation-details.json` (editable title/purpose overlay from `PATCH /inv/investigations/{id}`) and `embeds/<tokenId>.json` to the layout file list, and remove the stale `⚠ Note: the class Javadoc on FsInvestigationLayout says <audit root>/investigations/<id>/...` sentence.
5. **Line 400 (`POST /inv/reference-rows` row cap — D5d):** Change `at most 2 000 rows (truncated)` to `at most reference.max_rows (default 4,000) rows (truncated)`.
6. **Line 430 (`POST /inv/case-history` linked caps — D5e):** Change `at most 100 Case-linked Investigations and the newest 500 attachment records` to `at most case_history.max_linked_investigations (default 200) Case-linked Investigations and the newest case_history.max_attachments (default 1,000) attachment records`.
7. **Line 691 & Line 697 (`RouteModule` table header & `Investigations` row — D2, D3, D4):**
   - Update line 691 heading from `24 RouteModules in la-api plus InvestigationMeasureRoutes in geo-link, 104 routes` to `28 RouteModules in la-api plus 2 HTTP RouteModules (InvestigationMeasureRoutes, InvestigationDataChangesRoutes) and 2 lifecycle modules in geo-link, 104 routes (97 in la-api + 7 in geo-link)`.
   - Update line 697 (`Investigations` row) to include `POST /inv/expand/preview` (dry-run 1..4 `seed`/`expand` steps for an unsaved Investigation, `LA-UNSAVED-INVESTIGATION-1`), `PATCH /inv/investigations/{id}` (`LA-INVESTIGATION-RENAME-1`), and `DELETE /inv/investigations/{id}` (`LA-INVESTIGATION-DELETE-1`).
8. **Line 724 (`RateLimiter.linkAnalysis()` module count — D2):** Update the historical parenthetical so it notes the current `RouteModule` count alongside the 2026-10-12 audit date.
9. **Line 769 (`DELETE /inv/investigations/{id}` contradiction — D4):** Replace `"There is no Investigation delete or close route today, so 'closed' means its linked Case closed."` with `"An Investigation can be deleted by its owner or an administrator via DELETE /inv/investigations/{id} (LA-INVESTIGATION-DELETE-1, filesystem backend), which removes its embeds along with the Investigation directory; there is no separate 'close' status on an Investigation today, so 'closed' in this sweep means its linked Case closed."`
10. **Line 1451 (`module.toon` `RouteModule` count — D2):** Change `la-api -> RouteModule (27); geo-link -> RouteModule (4)` to `la-api -> RouteModule (28); geo-link -> RouteModule (4)`.
11. **Line 1505 (`LA-EDITION-PLACEMENT-1` stale warning — D6a):** Replace `⚠ The canvas node dialog's Entity page link (entityPageOf in link-analysis.component.ts) does not key on it yet (row LA-EDITION-PLACEMENT-1).` with `The canvas node dialog's Entity page link (entityPageOf in link-analysis.component.ts) also keys on /bootstrap entityPages (LA-EDITION-PLACEMENT-1, shipped 2026-10-28), returning null on Basic.`

- [ ] **Step 5: Run vocabulary check on modified docs**

Run:
```powershell
node tools/check-vocabulary.mjs
```
Expected: PASS (exit code 0).

---

### Task 3: Reconcile `user-guide-link-analysis.md` and `brochure-inspecto-la-whitepaper.md` with Code (Discrepancies D3, D5, D6b, D7)

**Files:**
- Modify: `docs/okf/product/module/la-core/user-guide-link-analysis.md:87,230,488-530,950,3295,3387`
- Modify: `docs/okf/product/module/la-core/brochure-inspecto-la-whitepaper.md:71`

- [ ] **Step 1: Update `user-guide-link-analysis.md` (D3, D5a, D5e, D7)**

Apply the following targeted edits in `docs/okf/product/module/la-core/user-guide-link-analysis.md`:
1. **Line 87 (§2 right-panel tab name — D7):** Change `Open the right panel's **On screen** tab` to `Open the right panel's **Browser** tab`.
2. **Line 230 (§4 dock tabs — D3):** Add **Steps** (shown while working on an unsaved Investigation before saving, with per-step undo and *Save…*) alongside **Step log**.
3. **Lines 488–530 (§8 right-panel tab names — D7):** Replace **On screen** and **Whole Dataset** tab references in §8 with **Browser** and **Server** (`The right panel's **Browser** and **Server** tabs`, `8.1 Running a Browser algorithm`, `8.2 Running a Server algorithm`, `In the **Server** tab, pick an algorithm`).
4. **Line 950 (§15 rate limit — D5a):** Change `a burst of 20 requests per user, then one every 3 seconds` to `a burst of 30 requests per user, then one every 2 seconds (0.5 per second, configurable per Space via rate.link_analysis_burst and rate.link_analysis_per_s)`.
5. **Line 3295 (Part II `case-history` caps — D5e):** Change `at most 100 Case-linked Investigations and the newest 500 attachment records` to `at most 200 Case-linked Investigations (case_history.max_linked_investigations) and the newest 1,000 attachment records (case_history.max_attachments)`.
6. **Line 3387 (Part II dock tabs — D3):** Include `Steps` (unsaved Investigation steps) in the dock tabs list.

- [ ] **Step 2: Fix Basic edition `Entity profile pages` entry in `brochure-inspecto-la-whitepaper.md` (D6b)**

In `docs/okf/product/module/la-core/brochure-inspecto-la-whitepaper.md:71`, split `Entity profile pages` from `Entity Lists, identity resolution` so Basic matches `D-LAE4` (`entityPages` is Standard and above, 503 on Basic):
```markdown
| Entity Lists, identity resolution | yes | yes | yes | yes |
| Entity profile pages | — | yes | yes | yes |
```

- [ ] **Step 3: Run vocabulary and doc checks**

Run:
```powershell
node tools/check-vocabulary.mjs
node tools/check-backlog-items.mjs
```
Expected: PASS (exit code 0).

