# Product Map

The commercial tree of Inspecto: one TOON file per **Product Map Item**, at `<kind>/<name>.toon`, with id `<kind>.<name>`.
The design and decisions are in [`docs/superpower/product-map-plan.md`](../docs/superpower/product-map-plan.md); the words are in
`docs/GLOSSARY.md` §15.

| Folder | Item | Links to |
|---|---|---|
| `layer/` | a module directory group (`platform`, `features`, …) | — |
| `module/` | one module | `module:` = the manifest id in `META-INF/inspecto/module.toon` |
| `functionality/` | what a customer recognises inside a module (written by hand) | `parent:` + `module:` |
| `addon/` | an Add-on | `offeringAddon:` = the key under `addons:` in `offerings/*.toon` |
| `solution-pack/` | a Space Template or Industry pack | `template:` / `industryPack:` |
| `edition/` | an Edition | `offering:` = `offerings/<id>.toon` |

Rules:
- **Never copy technical facts** (roles, dependencies, which Edition holds what) here. Name the id; tools look the facts up.
- **No prices here.** Prices and customer records go in `private/`, which is gitignored.
- No `#` lines (TOON has no comments).
- `node tools/product-map-seed.mjs` adds items for new modules, Add-ons, packs and Editions. It never overwrites an existing file.
