# D7 — migrating `WidgetConfig.tags` onto the tag assignment store

**Status:** ACTIVE — call made and design pass complete 2026-07-26, build NOT started.
**The call (operator, 2026-07-26):** option **(c)** of the BACKLOG §6 widget row — `WidgetConfig.tags`
migrates onto the D7 assignment store and the widget card's chip row becomes a **projection** of assignment
edges, the same move phase 2 made for `attributes.tags`. Options (a) "leave it alone" and (b) "a second,
visually distinct chip row" are **rejected**: two tag systems on one card is the split-brain phase 2 existed
to end.
**Reads with:** `docs/okf/backend/control-plane/tags.md` (the phase-2 projection pattern).

## 1. Why this is a migration and not a menu item

The other four adopters (`link-analysis-view`, `geo-map-view`, `dataset`, `dashboard`) were menu items:
they had **no prior tag concept**, so wiring `TagAssignmentDialog` was additive. Widgets already carry tags
in a different place with a different vocabulary, so adopting the dialog *without* migrating creates
precisely the split-brain. Concretely, today:

- **There is no Java `WidgetConfig`.** A widget is a generic `ComponentStore` component of kind `widget`
  with opaque TOON/JSON content. The only backend acknowledgement of tags is the envelope field
  `FieldSpec.of("tags", …)` in `ConfigSpecs.widget()` (`inspecto-config/.../ConfigSpecs.java:440`) — **no
  Java code reads or writes it.**
- The TS model is `WidgetConfig.tags?: string[]`
  (`inspecto-ui/src/app/modules/admin/studio/widgets/widget-types.ts:20-21`), read by the chip row
  (`widgets.component.html:84-90`) and the tag filter / text search (`widgets.component.ts:82,93,96`),
  serialized in `widgets.service.ts:46,61`, and held in builder state (`explore.component.ts:88,216,263,272-275`).
- **It has a writer:** the Save-as-widget dialog's comma-separated free-text field
  (`widget-save.dialog.ts:53`, split at `:92-97`). It mints arbitrary strings with **no vocabulary check** —
  the exact opposite of D7, where assigning an unregistered tag is a 404.
- `widget` is **already a valid assignment target**: `NoteTargets.KINDS` = `"object"` +
  `ComponentStore.WRITABLE_TYPES` (`NoteTargets.java:31-39`) and the mock whitelist already lists it
  (`ops.handler.ts:602-604`). So `POST /tags/assignments/widget/{id}` works today.

## 2. The phase-2 pattern being copied

In `inspecto-engine/.../ops/ObjectService.java`: `tagsOf()` `:426-428` is the truth read;
**`projectTags(o, now)` `:558-561`** rewrites `ATTR_TAGS` from it and never the reverse; every mutation path
calls it (`applyTag` `:431`, `removeTag` `:439`, `reprojectAll` `:545` — itself called by `renameTag` `:506`
and `deleteTag` `:528`, plus `adoptTags` on create and the rule/merge/split paths).
`backfillTagAssignments()` `:455-469` is a full scan that adopts legacy CSV tags, idempotent twice over (it
skips tags already in `tagsOf()`, and the store's composite key makes `add` idempotent), run **once per
Space at construction** (`CollectorService.java:357`).

## 3. Why it is not a mechanical repeat — the four real problems

1. **There is no server-side widget service to hang the re-derive on.** Widgets go through generic
   `/components` CRUD; no Java code owns a widget write. Phase 2's entire safety argument is that the
   projection is re-derived **server-side after every mutation**. Doing it in `WidgetsService` (client-side)
   is *not* the same guarantee — a direct `POST /components/widget` would bypass it. ⇒ the projection needs
   a **per-kind hook in `writeComponent`**, i.e. the same seam D6 used for `findings-spec` validation, not
   a client-side re-derive.
2. **`renameTag`/`deleteTag` deliberately skip component targets.** `objectTargetsOf()`
   (`ObjectService.java:531-538`) filters to `NoteTargets.OBJECT` with the comment "component targets have
   no CSV to project" — which is true *today* and becomes false the moment widget chips are a projection.
   Leaving it would let a rename or tag-delete strand a stale array on every widget. And the fix cannot
   live in `ObjectService`, which has no `ComponentStore` handle. **This is the largest piece of new work.**
3. **No vocabulary alignment.** Existing widget tags (`ops`, `billing`, `ra`, `fraud`, `graph`, `audit`,
   `usage`, `irsf`, `risk`) are free text, and assignment `add` **404s on an unregistered tag**. A backfill
   must create the registry entries first or silently drop tags. Phase 2 never faced this — object CSV tags
   were already registry-backed. (`TagAssignment.java:38` also bans commas; the save dialog's field is
   comma-*delimited* so that is compatible, but an existing tag containing a comma is unmigratable.)
4. **Tags currently travel in bundles; edges do not.** `widget` is in `BundleRoutes.APPLY_ORDER`
   (`BundleRoutes.java:74`) and in `Exchange.DERIVED_KINDS` (`Exchange.java:45`), so a widget's tags cross
   Space and bundle boundaries **inside its config** today. Once they are per-Space edges, **bundle import
   silently loses them** — unless import adopts the incoming config array, which reintroduces a config-array
   writer. ⇒ import must call the same adopt path the startup backfill uses, and that must be a decision on
   the record, not an accident.

## 4. Build steps (in dependency order)

1. **A `widget` tag projection hook in the component write path** — `writeComponent` re-derives the
   content's `tags` array from `tagAssignments.tagsOf("widget", id)` for kind `widget`, mirroring D6's
   per-kind hook. Edges are truth; a `tags` array in a submitted body is **ignored on write**, not honoured.
   → verify: `POST /components/widget` with a bogus `tags` array stores the edge-derived array instead.
2. **Reprojection for component targets on rename/delete** — generalize `objectTargetsOf()`'s skip: a
   per-kind reprojector registry (object → CSV, widget → config array), so `renameTag`/`deleteTag` move
   every projection. → verify: renaming a tag carried by a widget rewrites the widget component; deleting
   it removes the chip.
3. **Backfill: registry entries first, then edges.** Scan `widget` components, register any unknown tag
   name, then `add` the assignment (`source="migration"`). Idempotent by the same two mechanisms as phase 2;
   run once per Space beside `backfillTagAssignments()`. → verify: a second boot adopts 0.
4. **Bundle import adopts the incoming `tags` array** as edges (decision on the record — the alternative is
   documented tag loss across Spaces). → verify: export/import round-trips a tagged widget's chips.
5. **UI: the card gains the Tags button, the save dialog loses the free-text field.** Copy the
   `dashboard`/`dataset` adoption verbatim (`dashboards.component.ts:41-47` + `.html:50-52`; datasets wraps
   it in `@if (!writesDisabled())` because assignment is a write) into the widget card's action cluster
   (`widgets.component.html:92-110`), before Delete. Then **remove the comma field from
   `widget-save.dialog.ts:53`** — left in place it is a second writer that resurrects config-only tags on
   every save. ⚠ That is a deliberate behaviour regression for the "save and tag in one step" flow; the
   replacement is Save → Tags. → verify: the chip row still renders after a dialog assignment, and saving a
   widget never changes its tags.
6. **Mock parity** — four seeds × three widgets write config tags directly (`telecom-ra.seed.ts:113,118,123`,
   `fraud-mgmt.seed.ts:89,94,99`, `link-analysis.seed.ts:86,91,96`, `financial-audit.seed.ts:107,112,117`).
   Either seed matching edges + registry tags, or give `ops.handler.ts` a widget read-through mirroring the
   object one. → verify: offline, a widget appears under its tag in the `/tags` pane.

## 5. On landing

Retire the "widget is deliberately not an adopter" paragraph (`docs/okf/backend/control-plane/tags.md:176-180`),
close the BACKLOG §6 widget row, refresh `.claude/skills/angular-ui/SKILL.md:107-112`, distil the as-built
into `okf/backend/control-plane/tags.md`, and `git mv` this plan to `docs/archived-documents/plans-archive/`.
