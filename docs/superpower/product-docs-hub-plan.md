# Product documentation hubs — plan (`PRODUCT-DOCS-HUB-1`)

> **Status:** 🟡 IN FLIGHT — design agreed with the operator 2026-10-19; pilot (`module.acquire`,
> `addon.incidents`) built in the same change. Waves 2+ not started.

## 1. Problem

Documents are organised by **audience tier** and by **when they were written**, not by what is sold and
built. One module's truth sits in up to six places, and nothing ties them together:

| What | Where today |
|---|---|
| Requirement of record (plan) | `docs/okf/capabilities/<area>/` (capability ≠ module) |
| Mechanism / implementation | `docs/okf/backend/**`, `docs/okf/frontend/features/**` |
| L3 module page (architecture, screens, API) | `docs/stakeholders/modules/` (its own slugs, e.g. `collectors` for module `acquire`) |
| User / admin guidance | sections of `USER_GUIDE.md`, `ADVANCED_GUIDE.md`, `docs/ops/` |
| API | `docs/api/openapi-v1.json` (one file, not sliced per module) |
| Feature list | `FEATURE_INVENTORY.md`, `EDITIONS.md` |

The commercial tree in `product-map/` (layer → module → add-on → solution pack → edition) already
models the onion and is kept honest by `tools/check-product-map.mjs`. Its `docs:` field points at one
page per item (20 of ~85 items set). That is the spine the docs should hang off.

## 2. Decision — one hub per Product Map Item

`docs/okf/product/<kind>/<name>/index.md` — the path mirrors `product-map/<kind>/<name>.toon`, so the docs
tree tallies with the assembled Offering by construction. The product-map item's `docs:` points at its hub.

Every hub carries the **same ten document slots** in a table, in this order (a reader and an agent learn
the shape once):

| Slot | Reader | Notes |
|---|---|---|
| `plan` | product owner, developer | requirement of record + what is open |
| `spec` | developer | implementation detail |
| `architecture` | solution architect, advanced user, troubleshooter | design, flows, troubleshooting |
| `features` | marketing, presales | customer-facing feature description |
| `feature-db` | build / release engineer | sortable feature rows — **generated, never prose** (wave 3) |
| `user-guide` | end user | screens and configuration |
| `admin-guide` | system administrator, team manager | install, permissions, operations |
| `api` | API integrator | the module's slice of `/api/v1` |
| `tests` | QA / QC | test cases and where their tests live |
| `brochure` | customer, sales | **`n/a` on modules** — brochures are per edition / solution pack |

Slot status: `current` (file in the hub, maintained) · `linked` (content lives elsewhere; the hub points
at it until migrated) · `stub` · `missing` · `n/a`.

### Decisions

- **D-PDH1 Hub first, move later.** A hub starts as an index of `linked` slots; content is moved in
  (`git mv`, then slot → `current`) one module at a time. No big-bang migration — the stakeholder L3 pages
  shipped 2026-10-18 and stay valid.
- **D-PDH2 Facts are not copied.** Editions, roles, dependencies and order codes are read from
  `product-map/` and `offerings/`; a hub names the item id and never restates them.
- **D-PDH3 Cross-cutting concepts stay in OKF sections** (`backend/`, `capabilities/`, …); hubs link them.
  A hub is for one sellable/buildable item.
- **D-PDH4 Hubs inside OKF** (`docs/okf/product/`), not a new tree — current knowledge already lives in OKF.
- **D-PDH5 Coverage is enforced, not hoped for.** `tools/check-product-docs.mjs` fails a hub that lacks a
  slot row or uses an unknown status, and reports per-slot coverage; `check-doc-links.mjs` resolves the links.

## 3. Waves

1. **Pilot (this change)** — template, `module.acquire`, `addon.incidents`, checker, product-map `docs:` links.
2. **Hubs for every module and add-on** — all `linked`/`missing`; generated skeleton from product-map
   (`node tools/check-product-docs.mjs` lists items with no hub).
3. **`feature-db`** — `features[]` rows per item, rendered to CSV/table next to `render-product-map.mjs`.
4. **Move content in** — per module: split the L3 page into `architecture` / `user-guide` / `admin-guide`,
   slice OpenAPI by tag into `api`, extract test inventory into `tests`.
5. **Brochures** — per edition and solution pack, assembled from `features` slots.
6. **Agent skills** — investigation / pipeline-authoring / incident skills read hubs by item id + slot.

## 4. Open

- Whether `functionality/` items (inside a module) get their own hubs or sections in the module's `features`.
