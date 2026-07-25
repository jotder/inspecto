# Generic tags — cross-entity labelling plan (BACKLOG D7)

**Status:** DRAFT 2026-07-25 — scope + storage decided, phasing open. **Owner:** unassigned.
**Origin:** BACKLOG **D7**, rescoped by the operator during the 2026-07-25 decision session from
"a `tags` filter on `GET /objects`" to *"use tags for grouping different items from lists, generic
functionality, applied to most groupable items — streams, rules, alerts, etc., like a mail tag in Gmail."*

> **Read this first — the old backlog row was wrong twice.** It claimed tags were not a first-class concept
> and that "nothing writes `attributes.tags`, so a `GET /objects` filter would silently match nothing."
> Both are false. A working object-scoped tag system ships today (see §1), and `attributes.tags` is written
> in at least four `ObjectService` paths. The original narrow filter would have worked fine. **This plan is
> a generalization of a working feature, not a greenfield build** — the cost is in migration and in widening
> the addressable surface, not in inventing tagging.

## 1. What already ships (do not rebuild)

| Piece | Where | Shape |
|---|---|---|
| Tag registry | `inspecto-engine/…/ops/tag/Tag.java` | `record Tag(String name, long createdAt)`; authored as a `*_tag.toon` (`tag { … }` block) loaded at bootstrap, or created at runtime via `POST /tags` which persists the same file under the write root. Rejects blank names and commas |
| Auto-application rules | `inspecto-engine/…/ops/tag/TagRule.java` | Evaluated when an object opens |
| Assignment storage | `ObjectService.ATTR_TAGS` (`= "tags"`) | **A comma-separated CSV string in the object's own `attributes` map.** Written on manual apply (~line 405), tag-rule merge (~414–422), rule-raised creation (~495), merge union (~792–816), and split (~850) |
| HTTP surface | `inspecto/…/control/TagRoutes.java` | `/tags` + `/tags/rules`; capability-gated via `CapabilityManifest` |
| UI | `objects.md` — Tags folder in the mail nav, bulk `tag` verb (optimistic) | Incidents/Cases only |

**The constraint that forces this plan:** tags live *inside the tagged object*, as CSV, in a store that only
`OperationalObject`s use. Nothing about that generalizes — a Stream or an Alert Rule has no
`attributes` CSV to hang a tag on, and "show me everything tagged `q3-audit`" would mean scanning every
store and splitting strings.

## 2. Decided (2026-07-25)

- **Scope:** tags become **cross-entity** — the target set starts with the groupable component kinds
  (Streams, Alert Rules, Alerts, Datasets, Incidents/Cases) and must be open to more without a schema change.
- **Storage: a central tag registry + a `(tag, entity_kind, entity_id)` assignment store.** Chosen over
  per-entity `tags` fields because:
  * "everything tagged X across kinds" is the *point* of the feature, and the per-entity shape makes it a
    fan-out over every store;
  * a **rename** must propagate — with CSV copies it cannot, so a renamed tag silently splits into two;
  * a tag's existence should not depend on any entity, which the registry already gets right today.
- **Related decisions from the same session** — keep these aligned, they are the same shape of problem:
  **D10** generalizes notes to any `(kind, id)` target. Tags and notes should address components the same
  way; if one of them ships an addressing scheme, the other adopts it rather than inventing a second.

## 3. Open questions — answer before building

1. **Is the assignment store a new `ComponentStore` kind, or its own store?** A `tag-assignment` kind gets
   CRUD/versioning/bundle transport free, but assignments are high-cardinality edges, not authored
   components — likely the wrong fit. Needs a look at how `ComponentStore.WRITABLE_TYPES` behaves at edge
   volume.
2. **Migration of the existing CSV.** `ATTR_TAGS` has live data. Options: (a) one-time backfill into the
   assignment store then stop writing CSV; (b) dual-write for a release. **(a) is preferred** — dual-write
   invites the two representations to diverge, which is the exact failure the central store exists to
   prevent. Either way the read path must be switched in one change, not per-caller.
3. **Space scoping.** Is a tag installation-wide or per-Space? Almost certainly per-Space (Spaces are the
   isolation boundary), but the current `*_tag.toon` under the write root needs checking against that.
4. **Does a tag survive its entity's deletion?** The assignment must be cleaned up, so the store needs a
   delete hook per kind — or a periodic reconcile. Do not leave dangling assignments; the "everything tagged
   X" query is exactly where they surface.
5. **Capability model.** One `canAuthorTags` for the registry, or per-kind (tagging a Dataset ≠ tagging an
   Incident)? Note the **D4** precedent from the same session: a capability that spans two genuinely
   different activities should be split, not bundled.
6. **Does the Gmail metaphor extend to filtering/saved searches**, or is v1 just apply + list-by-tag? Keep
   v1 narrow.

## 4. Non-goals (v1)

Hierarchical/nested tags · tag colours or per-user tag views · tags as an access-control mechanism (they are
organizational labels, **never** a security boundary — a tag must not gate visibility) · auto-tagging beyond
the existing `TagRule` mechanism · cross-Space tag sharing.

## 5. Verification

- The existing Incidents/Cases tag behaviour is **regression-tested first** — it ships today and users rely
  on it; the migration is the risky part, not the new kinds.
- A round-trip test per newly-taggable kind (apply → list-by-tag → delete entity → assignment gone).
- A rename test proving propagation across at least two kinds — this is the reason the architecture was
  chosen, so it needs a test that would fail under the per-entity shape.

**When this ships:** distil the as-built into an OKF concept (tags span backend + frontend, so likely
`okf/backend/control-plane/` with a pointer from `okf/frontend/features/objects.md`), move any residuals to
`docs/BACKLOG.md`, then `git mv` this plan to `docs/archived-documents/plans-archive/`.
