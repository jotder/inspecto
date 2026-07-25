# Generic tags — cross-entity labelling plan (BACKLOG D7)

**Status:** **PHASES 1 + 2 SHIPPED 2026-07-26** — backend store + routes are in (`ops.tag.TagAssignment*`,
`/tags/assignments/…`, `/tags/{name}/targets`). Q1–Q4 were answered from the code; **Q5 dissolved and Q6
was scoped down** at build time (see §3). **Phase 2 — the CSV reconciliation — is now
built too**: the assignment store is the source of truth and `attributes.tags` is a projection of it, with
an idempotent startup backfill. **Still open: the rename/delete routes and the UI**; residuals are in
BACKLOG §6. Concept home:
[`../okf/backend/control-plane/tags.md`](../okf/backend/control-plane/tags.md). **Owner:** unassigned.
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
| Assignment storage | `ObjectService.ATTR_TAGS` (`= "tags"`) | **A comma-separated CSV string in the object's own `attributes` map.** Five write sites, all in `ObjectService`: `applyTagRule` :416, `autoApplyTagRules` :433, rule-raised Case creation :506, `mergeCases` union :827, `splitCase` :863. **All reads funnel through one private helper, `csvTags()` :437-445** |
| HTTP surface | `inspecto/…/control/TagRoutes.java` | `/tags` + `/tags/rules`; capability-gated via `CapabilityManifest` |
| UI | `objects.md` — Tags folder in the mail nav, bulk `tag` verb (optimistic) | Incidents/Cases only |

> **⚠ Correction to an earlier draft of this table (verified 2026-07-25):** there is **no single-object
> "manually apply a tag" endpoint** — no `/objects/{id}/tags` route exists. Tagging happens only via
> `applyTagRule` (bulk, `POST /tags/rules/{name}/apply`) and the four internal lifecycle paths. If v1 is
> meant to let a user tag one thing from a list, **that endpoint is new work**, not a generalization of
> something shipped. This is the single biggest scope correction in this plan.

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

## 3. Open questions

**Q1–Q4 were answered by codebase investigation on 2026-07-25** (findings + line refs below). **Q5 and Q6
remain genuine product calls and still need the operator** — they are not derivable from the code.

### ✅ Q1 — assignment store shape: **a dedicated ops store, NOT a `ComponentStore` kind**

Mirror `ops.note`, which D10 built for the structurally identical problem three weeks ago: a new
`com.gamma.ops.tag` package with a `TagAssignment` record, a `TagStore` interface, `InMemoryTagStore` +
`DbTagStore` (table `inspecto_ops_tag_assignments`), reached via `ObjectService.tagStore()` exactly as
`noteStore()` is (`ObjectService.java:967-969`). It joins the established `inspecto_ops_*` family
alongside `DbObjectStore` and `DbLinkStore`.

`ComponentStore` is the wrong fit on every axis at assignment cardinality — it is **file-per-component**
(`registry/<typeDir>/<id>.toon`, `ComponentStore.java:24-36`), `list()` does a **full directory walk on
every call** (:100-104), and `write()` **copies the entire prior file into `.history/` before every save**
(:121-143, keep-10 pruning at :75-88). That design is right for a small population of authored,
individually-versioned artifacts and actively wrong for 50k high-cardinality edges: per-save history
copies are pure waste (assignments are not edited versions of each other), there is no query-by-entity,
and bundle transport would ship 50k tiny files instead of one queryable table.

### ✅ Q2 — CSV migration: **option (a), and it is much cheaper than this plan assumed**

Take the one-time backfill; do not dual-write. The plan's worry that "the read path must be switched in
one change, not per-caller" turns out to be nearly free: **every read already funnels through a single
private helper**, `ObjectService.csvTags()` (`:437-445`), called from :413, :425, :803, :807. There is no
second CSV parser anywhere in the backend. Switching reads means reimplementing one helper.

**There is also no server-side tag filter to migrate** — `TagRule.Filter` (`TagRule.java:97-157`) filters
on `type/q/status/priority/severity/category` only, and `ObjectQuery`/`DbObjectStore`/
`InMemoryObjectStore` have **zero** tag-aware query support. Two consequences: the migration surface is
smaller than feared, **and** "show me everything tagged X" — the feature's entire point — is genuinely new
query work, not a rewiring. ⚠ Any tag filtering visible in the UI today is therefore client-side over the
`tags` string already in each object's JSON; confirm on the UI side before assuming a server filter exists
to preserve.

### ✅ Q3 — Space scoping: **already per-Space, correctly, via the standard mechanism — no new work**

`ObjectService` (which holds the `tags`/`tagRules` maps) is a field of `CollectorService`
(`CollectorService.java:118`), and one `CollectorService` is constructed per `SpaceContext` by
`ServiceBootstrap`, which scans that Space's own `*_tag.toon` / `*_tagrule.toon`
(`ServiceBootstrap.java:82-85, 203-226`). Runtime creation via `POST /tags` (`TagRoutes.java:44-57`)
persists under `api.writeRoot()`, which resolves to the bound Space's `config()` root
(`ControlApi.java:769-771, 784-788`, keyed on the request's `SpaceId`). This is the same idiom connections
and notification channels use. **The only requirement on D7 is not to break it**: wire the new store
per-`CollectorService`/per-`SpaceContext`, never as a static or global registry.

### ✅ Q4 — deletion: **follow D10's precedent (leave them), because no delete hook exists to attach to**

Two findings, and both point the same way:

- **Component deletion has no seam.** `DELETE /components/{type}/{id}` (`ComponentRoutes.java:154-177`)
  runs its pipeline-reference and Exchange-consumer 409 fences and then calls `store.delete(type, id)`
  directly. There is no listener, no hook, nothing pluggable. A cascade would mean **inventing a new seam**.
- **Object deletion is not reachable at all.** `com.gamma.ops.ObjectStore.delete(String)` exists
  (`ObjectStore.java:50`, added by `12cf20eb` "add physical delete to the ObjectStore SPI") and is
  exercised by `DbObjectStoreTest` / `InMemoryObjectStoreTest` — but it has **no production caller and no
  route**. Incidents/Cases are closed, merged, or split; never hard-deleted through the API. ⚠ *This
  corrects a breadcrumb claiming `ObjectStore.delete` "shipped" — the SPI method shipped, the capability
  did not.*

So the dangling-assignment scenario the question worries about is **mostly unreachable today**, and where
it is reachable (components), D10 already made the call: notes are deliberately **not** cascade-deleted,
which is why "re-creating an id resurrects the thread" is a documented D10 residual. D7 should match that
— **filter at read time against target existence rather than cascade on delete.** Reasons to prefer it:
it needs no new seam, it degrades safely (a stale row is invisible, not wrong), and it keeps tags and
notes behaving identically, which was the stated goal in §2. Revisit only if a hard-delete route lands,
at which point notes and tags should get the same cleanup in one change.

### ✅ Q5 — capability model — **the question dissolved: NO new capability, gate per target**

Neither option. Investigating D10 for the addressing scheme surfaced that **`NoteRoutes` has no capability
gate at all** — it authorizes per *target*, delegating `object` targets to the SEC-7d/ABAC scope check and
component targets to the R3 sharing gate. Tags took the same route, and it is strictly better than either
Q5 option for the reason §4 already stated: **a capability would make "can tag" independent of "can see"**,
which is precisely how a tag becomes an access grant. Per-target gating makes "you can only tag, and only
*find*, what you could already see" a structural property instead of a rule someone has to remember.

Implemented as `control/AnnotationTargets` — extracted from `NoteRoutes` when tags became the second
consumer, so the two features cannot drift into disagreeing about who may touch what. `CapabilityManifest`
needed no entry, and `CapabilityManifestTest` stayed green unchanged, which is the congruence proof.

Registry writes (`POST /tags`, Tag Rule CRUD) keep their existing `canAuthorWorkbench` gate — creating
vocabulary really is an authoring act.

### ✅ Q6 — filtering — **split in two; the read ships, the Gmail layer does not**

Answered by the operator as "no filtering in v1", refined at build time into a distinction the question
had conflated:

- **`GET /tags/{name}/targets` — the plain "everything tagged X" read — SHIPPED.** It is not optional: a
  central assignment store buys nothing over the pre-existing CSV without it, §2's whole architectural
  argument rests on it, and §5 requires it as a verification. Deferring it would have meant building the
  store and none of its payoff.
- **The Gmail layer — saved searches, tag-scoped views, filter chips in list surfaces — DEFERRED.** That
  is the part that materially sized the build, and it is additive later.

## 3b. What phase 1 actually shipped (2026-07-26)

`TagAssignment` · `TagAssignmentStore` + in-memory and DuckDB/Postgres implementations · per-Space wiring
on `CollectorService.tagAssignments()` · four routes · `AnnotationTargets` (extracted shared gate) · 14
store tests driving **both** implementations through identical assertions + 4 HTTP route tests.

**Deliberately deferred, all in BACKLOG §6:**

- ~~**The CSV path was not migrated.**~~ **DONE in phase 2** — Q2's one-time backfill shipped as
  `ObjectService.backfillTagAssignments()`, run once per Space at startup by `CollectorService`. The
  split-brain is gone: the store is authoritative and the CSV is re-projected from it on every mutation
  path (open/adopt, `applyTagRule`, merge union, split carry-over, and the new `applyTag`/`removeTag`).
  Q2 said "do not dual-write", and this is not dual-write — there is exactly one writer of record and one
  derived copy kept for the object JSON the UI reads.
- **`rename()` and `removeTag()` have no route.** They are implemented and tested — the architecture is
  justified by rename, so it needed a test that would fail under the per-entity shape — but nothing calls
  them, so deleting a tag from the registry leaves its assignments behind.
- **No UI.**

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
