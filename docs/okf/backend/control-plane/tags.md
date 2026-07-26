# Tags — the cross-entity label graph

**Concept home for BACKLOG D7.** Status: **complete end-to-end 2026-07-26** — the central store, the
assignment routes, the CSV reconciliation, the rename/delete vocabulary routes, and the **Tags pane**.
One store is authoritative; see [The CSV is a projection](#the-csv-is-a-projection).

Related: [`control-api.md`](control-api.md) · [`api-v1.md`](api-v1.md) ·
[`../editions/auth-security.md`](../editions/auth-security.md) ·
[`../../frontend/features/objects.md`](../../frontend/features/objects.md)

## What a tag is

A **Tag** is a free-form organisational label — the Gmail metaphor. Two distinct things carry the name,
and keeping them apart is what makes the feature coherent:

| Concept | Type | Lives in | Meaning |
|---|---|---|---|
| **Tag** | `ops.tag.Tag` | registry, one `<name>_tag.toon` per tag under the write root | the label itself; exists independently of anything it labels |
| **Tag Assignment** | `ops.tag.TagAssignment` | `TagAssignmentStore` (`inspecto_ops_tag_assignments`) | the edge "this tag is applied to `(targetKind, targetId)`" |
| **Tag Rule** | `ops.tag.TagRule` | registry, `<name>_tagrule.toon` | a saved filter that auto-applies a tag when an object opens |

A tag's existence does not depend on any target, and a target's existence does not depend on any tag.
That separation predates D7 and was kept.

> ⚠ **A tag is never an access grant** — it is an organisational label and must never gate visibility.
> This is enforced structurally, not by convention; see [Authorization](#authorization).

## Addressing: `(targetKind, targetId)`, shared with notes

Targets are addressed exactly as **D10 notes** are, through the same vocabulary — `NoteTargets.KINDS` =
`"object"` + `ComponentStore.WRITABLE_TYPES`. This is deliberate and load-bearing: tags and notes are the
same shape of problem (user-authored metadata hung off an arbitrary entity), and two addressing schemes
would drift. Widening `WRITABLE_TYPES` widens both features at once, for free.

The class is called `NoteTargets` only because notes got there first. Read it as "annotation targets".
*(Residual: the name is now misleading — renaming it is tracked in BACKLOG §6.)*

## Storage: a central assignment store, not per-entity fields

`TagAssignmentStore` — `InMemoryTagAssignmentStore` (lean default) and `DbTagAssignmentStore` (DuckDB or
Postgres), selected by the same `-Dobjects.backend=memory|db` toggle as the object/link/note stores, with
its own file (`-Dobjects.tags.db.url`, default `inspecto-ops-tags.db`) for the single-writer-lock reason.
A DB open that fails degrades to in-memory rather than blocking startup.

Wired **per Space**, as a field of `CollectorService` (`tagAssignments()`), never static — one
`CollectorService` exists per `SpaceContext`, so this is the standard scoping idiom and the only
requirement on the feature was not to break it.

**Why central rather than a `tags` field on each entity** — the three reasons, all still true:
1. *"Everything tagged X across kinds"* is the point of the feature; per-entity storage makes it a
   fan-out over every store.
2. A **rename must propagate.** With copies in each entity it cannot, so a renamed tag silently splits
   into two. `rename()` is the operation the architecture exists to support, and it has a test that would
   fail under the per-entity shape.
3. A tag's existence should not depend on any entity.

### Edge identity and idempotency

The identity of an edge is the triple `(tag, targetKind, targetId)` — the composite primary key. This
gives three properties for free:

- **`add` is idempotent.** Re-applying a tag returns the edge already stored, so the UI may apply
  optimistically without checking first, and a retry after a failed request is safe.
- **Re-tagging does not rewrite provenance.** `add` returns the *stored* edge, with its original `actor`
  and `createdAt` — otherwise the audit trail would read backwards.
- **`rename` self-merges.** Two edges collapsing into one is correct, not a conflict. It is implemented
  as delete-then-insert precisely because a bulk `UPDATE` would violate the primary key for any target
  already carrying the new name and abort the whole rename.

`remove` returns whether the edge existed; already-absent is success, not an error.

## Authorization

**There is no `canAuthorTags` capability, and that is the design.** Assignment routes gate *per target*
through `AnnotationTargets.gate` — the same gate the notes routes use:

- **`object` targets** → the SEC-7d data-scope + ABAC check (`ObjectRoutes.visibleObjectCorrelationId`).
- **component targets** → the R3 sharing gate (`ComponentAccess.requireView`), deliberately `requireView`
  and not `requireEdit`: labelling a saved view is a collaboration act, not an edit of its content —
  nothing under `registry/` changes — so a view-only sharee may tag it.

Why per-target beats a capability: a capability would make "can tag" independent of "can see", which is
exactly how a tag turns into an access grant. With the per-target gate, **`GET /tags/{name}/targets` can
only ever return targets the caller could already see**, so tagging cannot widen visibility and cannot be
used to probe for the existence of something hidden. It is a structural property rather than a rule
someone must remember.

Two consequences worth knowing: two users can legitimately get **different counts** for the same tag, and
an invisible target answers **404** (existence-hiding), never 403.

Registry writes (`POST /tags`, the Tag Rule CRUD) keep their existing `canAuthorWorkbench` gate — creating
vocabulary is an authoring act.

## HTTP surface

All under `/api/v1`. Unknown target kind → **400**; absent or invisible target → **404**.

| Route | Meaning |
|---|---|
| `GET /tags` · `POST /tags` | the registry (unchanged; `POST` gated by `canAuthorWorkbench`) |
| `GET /tags/rules` · `POST /tags/rules` · `DELETE /tags/rules/{name}` · `POST /tags/rules/{name}/apply` | Tag Rules (unchanged) |
| **`GET /tags/{name}/targets`** | everything carrying this tag, across kinds, filtered to what the caller may see |
| **`GET /tags/assignments/{targetKind}/{targetId}`** | the tags on one thing, alphabetical |
| **`POST /tags/assignments/{targetKind}/{targetId}`** | apply; body `{tag, actor?}`; idempotent |
| **`DELETE /tags/assignments/{targetKind}/{targetId}/{tag}`** | remove; idempotent, returns `{removed}` |
| **`POST /tags/{name}/rename`** | body `{to}`; renames everywhere (`canAuthorWorkbench`) |
| **`DELETE /tags/{name}`** | retire the tag and all its assignments (`canAuthorWorkbench`) |

**Applying an unregistered tag is a 404**, not an implicit create — silently minting a tag on a typo is
how a tag vocabulary rots. Create it via `POST /tags` first.

## The CSV is a projection

`ObjectService.ATTR_TAGS` — the pre-D7 comma-separated string inside each object's `attributes` map — is
**still written, and still part of the object's JSON**, because the Incidents UI reads it. But it is now a
**projection of the assignment store, never a second source of truth**:

- every object tag mutation goes **store first**, then re-projects the CSV from
  `tagAssignments.tagsOf("object", id)`;
- nothing reads the CSV as authoritative — `ObjectService.tagsOf(objectId)` reads the store;
- `open()` **adopts** whatever CSV the builder or a Tag Rule produced into the store, so a newly created
  object is immediately visible to `GET /tags/{name}/targets`;
- `applyTagRule`, `mergeCases` (tag union) and `splitCase` (tag carry-over) all route through the store.

**Migration.** `ObjectService.backfillTagAssignments()` adopts tags that exist only in a legacy CSV; it is
idempotent and `CollectorService` runs it once per Space at startup, logging only when it actually adopts
something.

> Why a startup backfill rather than a lazy "if the store is empty, fall back to the CSV": lazy adoption
> makes **"no assignments" and "not yet migrated" the same state**, so removing an object's *last* tag
> would resurrect all of them on the next read. There is a test pinning exactly that case.

The comma ban on tag names comes from this shape: a comma would be one label in the store and two in the
CSV projection.

## The UI: a vocabulary pane, not an assignment pane

`inspecto-ui/src/app/modules/admin/tags/` (route `/tags`, nav entry under Operations) — a two-column
pane: the vocabulary on the left, and on the right *everything carrying the selected tag, across kinds*
(`GET /tags/{name}/targets`) in a standard-tier data table. `TagsService`
(`inspecto/api/tags.service.ts`) is the client for the cross-entity routes; the registry read and the Tag
Rule CRUD stay on `ObjectsService`, where their existing callers are.

Three deliberate shapes:

- **Applying a tag is not in this pane.** Assignment belongs next to the thing being labelled — the mail
  pane's tag menu — not in a vocabulary admin screen, which would need a cross-kind target picker to
  answer a question nobody asked. The pane *removes* assignments, the half with no other home;
  `TagsService.assign` serves the callers that label in place.
- **Counts are never cached across tags.** The target list is server-filtered to what the caller may see,
  so a count is "targets *you* can see" — a cached or shared count would be wrong for the next viewer.
  For the same reason the vocabulary list shows no count at all until a tag is selected.
- **The 409 on delete is surfaced, not pre-empted.** The pane does not check the Tag Rules itself before
  offering Delete; it shows the server's message naming the rule. A client-side pre-check would be a
  second, staler copy of the rule state.

Rename is inline in the header (one field does not warrant a dialog), rejects a comma client-side, and
treats "rename to the same name" as a no-op rather than a request. Delete is
`confirmDestructive({requireText})` — typing the tag name, because the blast radius is every target.

### Labelling in place: `TagAssignmentDialog` (2026-07-26)

`inspecto/tags/tag-assignment.dialog.ts` is the shared "apply tags to this thing" surface for **any**
target kind. It takes `{targetKind, targetId, label?}` as dialog data and persists through
`TagsService.assign`/`unassign`, so **adopting it on a new pane is a menu item, not another dialog**:

```ts
this.dialog.open(TagAssignmentDialog, { data: { targetKind: 'link-analysis-view', targetId: view.id, label: view.name } });
```

Adopters: **Link Analysis** and **Geo Map** saved views (per-view `#viewActions` submenu — Link Analysis
sits beside Comments (D10) so the two annotate-a-view actions read as one family), plus **datasets** and
**dashboards** (a Tags icon button in the card action cluster), plus **widgets** — the one adopter that
needed a migration first (see below). Datasets keeps its sibling `writesDisabled()` gate, because
assignment is a write.

### `widget` — the fifth adopter, and the only one that needed a migration (shipped 2026-07-27)

`WidgetConfig.tags` was a config-embedded string array rendered as chips on the widget card: a *different*
concept from assignment edges, in a different store, with **no vocabulary check at all** (the save dialog's
comma field minted arbitrary names, while assigning an unregistered tag is a 404). So adopting the dialog
there without migrating would have put two unrelated tag systems on one card. Operator call (c),
2026-07-26: migrate — **the chips are now a projection of the edges**, exactly as `attributes.tags` is.

* **The projection lives at the edge, in `WidgetTags` (`com.gamma.control`), not in `ObjectService`** — the
  widget is a `ComponentStore` component and the engine deliberately knows nothing about that store. This
  is the same call D6's findings gate made. `ObjectService.renameTag`/`deleteTag` therefore still move only
  the object CSVs, and `TagRoutes` composes the component half around them.
* **Every path that can change an edge re-derives the array:** `ComponentRoutes.writeComponent`,
  `TagRoutes.assign`/`unassign`, `renameTag`/`deleteTag` (both now report a `widgets` count), bundle
  import, and a per-Space backfill.
* ⚠ **Adopt on create, overwrite on update.** A widget arriving from a bundle, a seed or a template carries
  its tags *inside its config*, and dropping them would be silent data loss — so a create adopts the array
  as edges (registering unknown names via `TagRoutes.ensureTag`, or they would be dropped by the 404). An
  **update ignores the submitted array**, so a stale client re-saving a widget cannot resurrect a tag the
  operator removed through the dialog. Removal is the dialog's job, not a smaller array's.
* ⚠ **The save-as-widget dialog's comma field is GONE, deliberately.** Left in place it is a second writer
  that resurrects config-only tags on every save. `explore.component` still passes the loaded widget's
  array through, but only as carry-forward — the server re-derives it. This is a deliberate behaviour
  regression for "save and tag in one step"; the replacement is Save → Tags.
* ⚠ **The widget card reloads its list when the dialog closes** — unlike the dashboards adopter. The chips
  are a projection, so without a refetch the card keeps drawing the old ones.
* **A no-op projection writes nothing.** Every `ComponentStore.write` archives a version, so writing
  unconditionally would fill a widget's version history with tag churn; `reproject` compares first.
  A failed rewrite is **logged, not thrown**: the edge (the truth) is already stored, so the tag operation
  succeeded, and the next write or backfill re-derives the array.
* ⚠ **Bundle import must adopt, and that is a decision on the record.** Widget tags travel *inside the
  config* across a bundle while edges are per-Space, so without the adopt in `BundleRoutes` importing a
  tagged widget would silently lose its tags.
* **The backfill runs from `TagRoutes.register`** (once per `ApiContext`, i.e. once per Space) rather than
  from `CollectorService` beside the object-CSV backfill — the engine has no write root, so it cannot see
  widgets. Idempotent twice over, so a second boot adopts nothing.
* **Offline:** the mock mirrors the same split — it reads a widget's array as the projection, adopts config
  tags lazily (on `GET /tags` and on any assignment read, so a seeded store migrates itself with **no
  `MOCK_STORE_KEY` bump**) and registers the names, since the four seed packs write widget tags directly.

Two shapes worth preserving:

- **It is not `TagDialog`.** The mail pane's dialog is bulk + tri-state over a selection and writes the
  `attributes.tags` CSV; this one is single-target and writes assignment edges. They look alike
  deliberately, but one dialog spanning both persistence paths would re-create the split-brain that
  phase 2 closed — the CSV is a *projection*, not a second source of truth.
- **Creating a tag is a separate, prior write.** Assigning an unregistered tag is a 404, never an implicit
  create, so the inline "New tag" field calls `ObjectsService.createTag` first and only then pre-checks it.
  A typo must not silently mint vocabulary.

Only the checkboxes the user actually touched are applied, and a toggle returned to its original state is
no write at all — re-tagging must not rewrite an edge's provenance.

## Gotchas

- **A vocabulary change is five moves, not one.** `POST /tags/{name}/rename` goes through
  `ObjectService.renameTag`, which moves the registry entry, the assignment edges, **every affected
  object's CSV projection**, and any **Tag Rule** applying the tag — then the route rewrites the
  `*_tag.toon` / `*_tagrule.toon` files so the change survives a boot. Miss any one and the vocabulary
  splits: a store-level rename alone leaves every projection stale, and a rule left pointing at the old
  name resurrects it on the next matching object. Both are pinned by tests.
- **Renaming onto an existing tag merges them, deliberately** — the composite assignment key makes two
  edges collapsing into one correct rather than a conflict, and the source tag then stops existing.
- **`DELETE /tags/{name}` is 409 while a Tag Rule still applies the tag.** Deleting it would be silently
  undone by the next matching object, so the rule must be deleted or repointed first. Tag *deletion* does
  remove every assignment (no orphans); tag *rule* deletion never touches assignments.
- **The backfill is a full object scan at Space startup.** Fine for Incidents/Cases volumes (they are
  human-scale, not telemetry), but it is O(objects) on every boot even after migration — the store's
  idempotent `add` makes repeat runs harmless, not free. Revisit if a Space ever holds enough objects for
  it to show up in startup time.
- **No cascade on target deletion.** Following D10's precedent: assignments are filtered at read time
  against target existence rather than cascade-deleted, because component deletion has no hook to attach
  to and object hard-delete is not reachable through the API at all. A stale edge is invisible, not wrong.
  Re-creating an id resurrects its tags — the same documented residual notes have.
- **Tag names are compared exactly** — no case folding. `Q3` and `q3` are two tags.
- **The offline mock mirrors the CSV/store split deliberately.** `mock/handlers/ops.handler.ts` reads
  *object* edges out of `attributes.tags` and keeps only non-object edges in its own collection, so the
  mock cannot drift into having two answers for one object the way a second edge collection would. Its
  `TAG_TARGET_KINDS` is the client-side copy of `NoteTargets.KINDS` — widen both together. Route-order
  trap: `/tags/{name}` is a catch-all, so every more specific `/tags/…` pattern must be matched before it
  or `DELETE /tags/rules` deletes a tag named `rules`.
