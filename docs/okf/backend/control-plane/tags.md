# Tags — the cross-entity label graph

**Concept home for BACKLOG D7.** Status: **phase 1 + phase 2 shipped 2026-07-26** — the central store,
the routes, and the CSV reconciliation. One store is now authoritative; see
[The CSV is a projection](#the-csv-is-a-projection).

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

## Gotchas

- **`rename()` and `removeTag()` have no route yet.** They exist on the store, with tests, because the
  architecture is justified by them — but no endpoint calls them, so deleting a tag from the registry
  currently leaves its assignments behind. ⚠ **When a rename route is wired it must re-project every
  affected object's CSV** — the store rename alone leaves the projection stale, which
  `ObjectTagProjectionTest.renamingATagReachesTheObjectCsvOnceReprojected` pins deliberately. Tracked in
  BACKLOG §6.
- **The backfill is a full object scan at Space startup.** Fine for Incidents/Cases volumes (they are
  human-scale, not telemetry), but it is O(objects) on every boot even after migration — the store's
  idempotent `add` makes repeat runs harmless, not free. Revisit if a Space ever holds enough objects for
  it to show up in startup time.
- **No cascade on target deletion.** Following D10's precedent: assignments are filtered at read time
  against target existence rather than cascade-deleted, because component deletion has no hook to attach
  to and object hard-delete is not reachable through the API at all. A stale edge is invisible, not wrong.
  Re-creating an id resurrects its tags — the same documented residual notes have.
- **Tag names are compared exactly** — no case folding. `Q3` and `q3` are two tags.
