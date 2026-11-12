# Product Map

The commercial tree of Inspecto: one TOON file per **Product Map Item**, at `<kind>/<name>.toon`, with id `<kind>.<name>`.
The as-built design and decisions are in [`docs/okf/product/layer/platform/spec-product-map.md`](../docs/okf/product/layer/platform/spec-product-map.md)
(history: the archived [`product-map-plan.md`](../docs/archived-documents/plans-archive/product-map-plan.md)); the words are in
`docs/GLOSSARY.md` §15.

| Folder | Item | Links to |
|---|---|---|
| `layer/` | a module directory group (`platform`, `features`, …) | — |
| `module/` | one module | `module:` = the manifest id in `META-INF/inspecto/module.toon` |
| `functionality/` | what a customer recognises inside a module (written by hand) | `parent:` + `module:` |
| `addon/` | an Add-on | `offeringAddon:` = the key under `addons:` in `offerings/*.toon` |
| `solution-pack/` | a Space Template or Industry pack | `template:` / `industryPack:` |
| `edition/` | an Edition | `offering:` = `offerings/<id>.toon` |
| `product/` | a Product (not billable: priced as its Edition plus its Apps) | `product:` = `products/<id>.toon` |
| `app/` | an App (billable, `INS-APP-<ID>`) | `app:` = `apps/<id>/app.toon` |
| `industry/` | an Industry (not billable: its pack is a Solution pack) | `industry:` = `industries/<id>/industry.toon` |

Rules:
- **Never copy technical facts** (roles, dependencies, which Edition holds what) here. Name the id; tools look the facts up.
- **No prices here.** Prices and customer records go in `private/`, which is gitignored.
- No `#` lines (TOON has no comments).
- `node tools/render-product-map.mjs` writes the views (CSV, Excel, mind-map HTML) to `target/product-map/`, merging prices from
  `private/prices.csv` (`orderCode,listPrice,currency`) when it exists.
- `node tools/product-map-import.mjs <edited.csv|edited.xlsx> [--sheet "Price list"] [--write]` brings commercial edits back
  (title, billing, support, status, prices). It is a dry run without `--write`, and it refuses structural edits.
- `node tools/customer-build.mjs <customer|record.toon> [--bundle <product-bundle-dir>] [--key <issuer.pem>]` reads
  `private/customers/<customer>.toon` (`id`, `title`, `product` (default `inspecto`), `edition`, `orderCodes[N]`, optional
  `expires` and `nodes`), assembles it (`tools/assemble.mjs --customer`, PA-10b) and writes the Assembly Plan, the customer
  Offering, a priced bill of materials and a signed entitlement to `target/customers/<customer>/`; with `--bundle`, a copy of the
  Product bundle trimmed to the plan, with `conf/entitlement.toon` + `.sig`. The key defaults to `private/keys/inspecto-issuer-1.pem`.
- `node tools/pick-addons.mjs` lists the Platform product's Add-ons with their closure, Edition floor and price;
  `--pick a,b --id <id> --write` writes a picked Platform product to `private/products/<id>.toon`, which
  `inspecto/package.ps1 -ProductFile` builds in place of the Platform product it narrows.
- `node tools/product-map-seed.mjs` adds items for new modules, Add-ons, packs and Editions. It never overwrites an existing file.
