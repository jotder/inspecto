# Bundle "load as draft" import — design

> **Status: DECIDED 2026-09-25 (operator answered D1–D8 in §7) — IN FLIGHT.** Build order as signed: the
> read-only preview extension (§5 row 4, built FIRST) then the editor slices (§5 rows 1–3) for Dashboard, Widget and
> Dataset, then authored Pipeline (row 5). Link Analysis / Geo views are a later slice, not started.
> **Shipped 2026-09-25:** §5 row 4 (preview `integrity`) and rows 1–3 for Dashboard, Widget and Dataset — as-built
> facts in [`metadata-bundle.md`](../okf/backend/control-plane/metadata-bundle.md) § *Import as draft* (D7).
> **Shipped 2026-09-25 (later):** §5 row 5 — authored Pipeline, as-built in the same concept section.
> **Open:** Link Analysis / Geo views; re-checking integrity against the *edited* draft just before Save
> (row 4's second half — today the check runs once, when the draft opens); a browser drive of the four
> editors (§6); the `authored-pipeline` bundle kind reads `PipelineStore` while the editor edits the
> registered config (found building row 5 — see the concept page).
> Concept pages: [`metadata-bundle.md`](../okf/backend/control-plane/metadata-bundle.md) (the import this
> changes) · [`exchange-sharing.md`](../okf/backend/control-plane/exchange-sharing.md) (the row's named owner; see D7).

## 1. The ask

An editor-hosted import that **lands as a draft** — the incoming config opens in that editor as unsaved
work the operator reviews and saves — instead of `POST /bundle/import` writing it straight into the
Space. ⛔ Not by stamping `enabled:false` on every kind (see §3 option C).

## 2. As-is

### 2.1 Backend — one write-through door, no draft seam

* **Routes.** `BundleRoutes.register` serves `POST /bundle/export`, `POST /bundle/preview` (read-only) and
  `POST /bundle/import` (gated `canAuthorWorkbench`) — `inspecto/src/main/java/com/gamma/control/BundleRoutes.java:121-128`.
* **Import writes immediately.** `importBundle` (`BundleRoutes.java:264-355`): write-root 503 gate (`:266`),
  envelope 422 (`:269`), dependency sort by `APPLY_ORDER` (`:112-115`, `:274`), referential-integrity 422 over
  registry ∪ incoming minus pre-existing findings (`:282-284`, computed in `introducedIntegrityFindings`
  `:364-383`, only for `INTEGRITY_KINDS` `:360`), then per item `src.write(id, content)` (`:318`). There is no
  state between "previewed" and "written": no staging directory, no pending record, no draft flag.
* **Uniform store seam.** Every kind reads/writes through `BundleSource` (`:397-414`), resolved by `sourceFor`
  (`:419-436`). `write` is the only persistence verb; `normalized` exists for hashing.
* **Preview is the only non-writing step** (`previewBundle`, `:206-253`): `new | unchanged | drifted |
  unsupported` per item plus `requires` classification. It does **not** report integrity findings — those are
  only known at import time.
* **The one kind that already imports as a draft is `authored-pipeline` via the ZIP door**, not this one:
  `PipelineBundleRoutes.importBundle` (`PipelineBundleRoutes.java:204`) always lands `active: false`
  (`:388`, `:411`), and `SaveGate.Referents.MAY_ARRIVE_LATER` (`SaveGate.java:47-59`) records that as a
  decision. That is a per-kind lifecycle the Pipeline actually has ("Draft → Ready → Live",
  `pipeline-editor.component.ts:650-651`), not a general seam.
* **`BundleExporter` / `BundleImporter`** (`inspecto/src/main/java/com/gamma/service/`) are the **ZIP** bundle
  pair used by `DataSourceRoutes.importBundle` (`DataSourceRoutes.java:250-283`) and whole-Space export; the
  v2 JSON envelope this design touches is `BundleRoutes`. `BundleImporter.writeConfig` (`BundleImporter.java:81-92`)
  is likewise write-through.

### 2.2 The per-kind save doors differ from the bundle door (a finding that shapes the design)

`POST|PUT /components/{kind}` (`ComponentRoutes.java:49-50`, `createComponent`/`updateComponent` `:291-320`)
and `/bundle/import` enforce **different** gate sets:

| gate | `/components` create/update | `/bundle/import` |
|---|---|---|
| structural + safety `validateKind` | yes (`ComponentRoutes.java:592`) | yes (`BundleRoutes.java:450`) |
| owner stamp / edit access (R3 `ComponentAccess`) | yes (`:299`, `:312`) | **no** |
| `If-Match` optimistic lock | yes (`:313`) | **no** |
| schema BACKWARD-compat gate | yes, update only (`:314`) | **no** |
| referential integrity (`ComponentIntegrity`) | **no** (no caller in `control/` besides `BundleRoutes`) | yes (`:282-284`) |
| connection raw-secret refusal | n/a | yes |

So "save a draft" must pick a door, and neither door is a superset. This is D2.

### 2.3 SPA — imports write through; editors already hold drafts in memory

* **One import surface.** `inspecto-transfer-menu` (`inspecto-ui/src/app/inspecto/transfer/transfer-menu.component.ts:20-28`)
  is dropped into every editor and library with `allowedKinds` (e.g. `dashboard-editor.component.html:21-25`,
  `dataset-editor.component.html:11-13`, `explore.component.html:10-12`, `pipeline-editor.component.html:270-272`).
  `openImport` opens `ImportBundleDialog` and, on a non-zero count, emits `changed` so the host reloads
  (`transfer-menu.component.ts:126-134`).
* **The dialog applies through the backend.** `ImportBundleDialog` (`import-bundle.dialog.ts:42-47`, data
  `:29-33`) previews, lets the user pick import/overwrite/skip, and `apply()` (`:144-175`) calls
  `BundleTransferService.applyImport` → `POST /bundle/import` (`bundle-transfer.service.ts:191-212`). No draft
  path exists; the host only ever sees "N written, reload".
* **Editors already have a draft-only apply idiom — for AI output.** `AiDraft` (`inspecto/ai-assist/ai-draft.ts:28-42`)
  is "config to apply + findings + prerequisites"; the tools are "draft-only … applying a draft is a separate
  human action through the pane's own validated route" (`ai-draft.ts:3-6`). `inspecto-ai-assist` emits
  `(applyDraft)` (`ai-assist.component.ts:107`, `:210-211`) and each host adopts it into its **in-memory
  model, unsaved**:
  * `DashboardEditorComponent.applyKpiReport` — creates prerequisite Widgets first, tiles them, leaves the
    Dashboard **unsaved** "so the human still presses Save (the draft-only invariant)"
    (`dashboard-editor.component.ts:372-384`).
  * `PipelineEditorComponent.applyPipelineDraft` — `captureUndo()` then replaces the graph, never activating
    or renaming a live Pipeline (`pipeline-editor.component.ts:1369-1376`).
  * Query editor previews an **unsaved draft** with no id (`studio/queries/queries.service.ts:38-39`).
* **No cross-route draft carrier.** Nothing in the SPA hands a config to an editor through navigation
  (no `history.state` / `getCurrentNavigation` use); every draft today is produced **inside** the pane that
  adopts it.

## 3. Options

**A. SPA-held draft (editor adopts incoming content unsaved).** A draft mode of `ImportBundleDialog` previews
as today but, instead of calling `/bundle/import`, returns the chosen item's `content` to the host, which
adopts it exactly as it adopts an `AiDraft`. Save goes through the pane's own route. Zero new backend state.
*Cost:* the draft is lost on reload/navigation; one item per editor; the bundle door's integrity gate does not
run on save (§2.2).

**B. Server-side staging area.** `POST /bundle/stage` persists the envelope under a per-Space staging root
(outside every loader-scanned directory), `GET /bundle/drafts`, editors open `?draft=<stageId>/<kind>/<id>`,
`POST /bundle/drafts/{id}/promote` runs the existing import loop over the staged envelope, `DELETE` discards.
*Buys:* durable, multi-session, multi-item (a whole closure as one draft), shareable between shift operators.
*Cost:* a new store + four routes (each clearing the four route gates: capability, OpenAPI entry,
`CapabilityManifestTest`, real-HTTP test), a retention/expiry policy, a new invisibility invariant (a staged
Dataset must not resolve, a staged Widget must not render, a staged Pipeline must not register), and
`importBundle` must be extracted into a reusable apply method. Genuinely multi-session.

**C. Per-kind inactive stamp (`enabled:false` / `active:false` on write).** ⛔ Rejected, as the row says. Only
some kinds have an inactive meaning (`active` on Pipelines; `enabled` on Expectations `ExpectationRoutes.java:86,143`
and Decision Rules `DecisionRoutes.java:353`). A Dataset, Widget, Dashboard, Query or Schema has none: the
stamp would be **written through** (visible in libraries, resolvable by `dataset:` refs, counted by integrity)
while carrying a key nothing reads — a persisted lie, and not a draft.

**D. Single-item `/bundle/import` as the draft's save door.** Option A's adoption, but Save posts a one-item
envelope to `/bundle/import`. Keeps integrity + secret gates; loses owner stamp, `If-Match`, and the schema
compat gate (§2.2), and turns an author's edit into an "import" in the audit trail. Listed for D2.

## 4. Recommendation

**Option A now, with an advisory integrity read; B only if D1 asks for durability.**

1. It is the idiom the SPA already has (`AiDraft` adoption, §2.3) — the same "operator is the audited actor,
   the pane's own validated route writes" invariant, so a draft import behaves like any other unsaved edit
   (undo, dirty state, Save/Discard).
2. Saving through the pane's route is correct: once the operator edits and saves, it *is* an authored save,
   and it keeps owner stamping, `If-Match` and the schema compat gate that `/bundle/import` lacks.
3. The one gate lost — referential integrity — is closed **read-only**: extend `POST /bundle/preview` to also
   return the `introducedIntegrityFindings` list it can already compute (`BundleRoutes.java:364-383`). The
   editor shows them before Save. This is advisory by design: hand-authoring the same config in that editor
   today runs no integrity check either (§2.2), so the draft is never *weaker* than typing it. Making
   `/components` enforce integrity is a separate, all-editors behaviour change (D3).
4. Prerequisites the draft needs (items of other kinds, missing `requires`) follow the `applyKpiReport`
   precedent: imported **write-through first**, explicitly, through the existing `/bundle/import` (all its
   gates), then the target opens as a draft (D4).

**Compatibility with the concurrent W5 lane** (forward reference-Dataset closure in `BundleExporter`): this
design does not touch export or `BundleExporter` at all. Draft mode consumes the v2 envelope as delivered; a
wider closure only means more rows in the prerequisite list of slice 3. Nothing here depends on W5 landing.

## 5. Slices (each one commit, each independently shippable)

| # | slice | layer | done when |
|---|---|---|---|
| 1 | `ImportBundleDialog` gains `mode: 'apply' \| 'draft'` in `ImportBundleData`. Draft mode: single target row of the host's kind, preview as today, **never calls `applyImport`**; closes with `{kind, id, content, provenance, targetExists}`. `inspecto-transfer-menu` gets an `[importDraft]` input (editors only, not libraries/Settings) that adds an "Import as draft…" item and a `draftImported` output. | SPA | dialog spec proves no `/bundle/import` request in draft mode |
| 2 | Host adoption, one editor per commit, smallest first: Dashboard → Widget (explore) → Dataset. Each gets `applyImportDraft(content)` modelled on `applyKpiReport` / `applyPipelineDraft`: undo capture where the editor has undo, model marked dirty, a banner *"Imported draft from ‹sourceSpace› — not saved"*, Save = the pane's existing route. Existing target → open that item with the incoming content as unsaved edits and the diff visible (D6). | SPA | per-editor spec: adopt ⇒ dirty, zero writes until Save; Save ⇒ exactly the pane's own request |
| 3 | Prerequisites: when the preview's `requires` has `missing` rows or the envelope carries other-kind items the target references, the draft dialog lists them and offers "Import these first" → existing `/bundle/import` over just those items; the draft opens only after that call succeeds (partial failure: stop, name what landed — the `applyKpiReport` rule). | SPA | spec: prerequisite 422 ⇒ no draft opened |
| 4 | `POST /bundle/preview` adds `integrity: [...]` (introduced findings for the previewed items, read-only). The editor calls preview with the *current draft content* before Save and shows findings in the draft banner. | backend + SPA | real-HTTP test in `ControlApiBundleTest`; `openapi-v1.json` response shape updated |
| 5 | `authored-pipeline` in the Pipeline editor via the existing `applyPipelineDraft` path (lifecycle kept; a brand-new id still needs the scaffold write the editor does for "new", `pipeline-editor.component.ts:1288-1289` — D5). | SPA | spec + browser drive — ✅ SPA + spec shipped 2026-09-25 (scaffold deferred to Save; browser drive open) |
| 6 | *Only if D1 = server staging:* Option B as its own plan (store, 4 routes, invisibility invariant, retention). Re-scope before starting. | backend + SPA | its own plan |

Out of every slice: `connection` (secrets are stripped at export and refused raw at import — a draft editor
would invite typing one; D5), and libraries/Settings imports (they stay write-through).

## 6. Test plan

* **Negative tests need a probe that would otherwise succeed.** The draft-mode dialog spec must first show the
  *apply* mode issuing `POST /bundle/import` for the same fixture, then show draft mode issuing none
  (`HttpTestingController.expectNone`). Mutation check: flip draft mode to call `apply()` and confirm the spec
  goes red for the right reason.
* **Per-editor adoption specs** (vitest via `npx ng test`, one TestBed configure per file): adopt ⇒ model equals
  incoming content, dirty flag set, no HTTP; Discard ⇒ back to stored; Save ⇒ one request to the pane's route
  (PUT with `If-Match` for an existing target, POST for a new one).
* **Backend slice 4:** real-HTTP `POST /bundle/preview` with (a) a Widget whose Dataset exists → `integrity: []`,
  (b) the same Widget pointing at an absent Dataset → one finding, (c) a registry that already has an unrelated
  broken ref → still `[]` for (a) (proves the "introduced only" subtraction). Run `-pl inspecto -Dtest=ControlApiBundleTest,ControlApiBundleImportTest`.
* **Browser drive** (preview pane) per editor for slices 2–5: import a bundle as draft, confirm nothing appears in
  the library until Save, reload and confirm the draft is gone (A's stated limit), Save and confirm it appears.
* Guards: `tools/check-vocabulary.mjs`, `tools/check-doc-links.mjs`, UI `lint:tokens` for the banner.

## 7. Decisions (operator, 2026-09-25 — all eight answered, each the recommendation)

1. **D1 — Draft durability: IN-MEMORY only**, lost on reload/navigation (Option A). No `sessionStorage`, no
   server staging; slice 6 is not built.
2. **D2 — Save door: the pane's own route** (owner stamp, `If-Match`, schema compat). No new write path; a draft
   never posts to `/bundle/import`.
3. **D3 — Integrity: ADVISORY findings** from the extended read-only `POST /bundle/preview` (`integrity: [...]`).
   `/components` is not changed to enforce integrity.
4. **D4 — Prerequisites: write-through import first**, through the existing `/bundle/import`, then the target
   opens as a draft (`applyKpiReport` precedent). A failed prerequisite import opens no draft.
5. **D5 — Kinds: Dashboard, Widget, Dataset first**; then `authored-pipeline`; then Link Analysis / Geo views
   (later slices). `connection` excluded.
6. **D6 — Existing id: open the existing item with the incoming content as unsaved edits + a diff.**
7. **D7 — Owning concept: `metadata-bundle.md`** (not `exchange-sharing.md`).
8. **D8 — Verb placement: an extra "Import as draft…" item beside "Import…"**, in editor-hosted menus only.

⛔ Unchanged: no cross-kind `enabled:false` stamp (§3 option C).
