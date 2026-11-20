// handbook-editions — which edition a Handbook page applies to, derived from the Product Map and the Offerings, never
// typed per page (USER-HANDBOOK-1 P3; docs/superpower/2026-11-19-user-enablement-and-agent-kit-plan.md §4.2).
//
// A hub page (docs/okf/product/<kind>/<name>/…) belongs to the Product Map Item product-map/<kind>/<name>.toon. That
// Item's FIRST edition — editions are cumulative: Standard includes Basic, Advanced includes Standard — is read from:
//   module         its manifest's offeringRole base/internal → Basic; else the first Offering that resolves it
//   addon          the first Offering (with its includes) that lists `offeringAddon`
//   solution-pack  the first Offering (with its includes) whose contentPacks list `template`; an `industryPack` → the
//                  module whose resources ship META-INF/inspecto/industry-packs/<pack>/
//   app            apps/<app>/app.toon requires.addons — the latest of their first editions (Basic when none)
//   product        products/<product>.toon editions — the first
//   edition        the edition itself
//   layer          the first edition of any module Item whose parent is the layer
// Anything else (industry, a root-canon page) resolves nothing: the manifest then declares `edition:` on the page.
// A declared edition may narrow the derived one, never widen it — check-handbook.mjs refuses an earlier one.
//
// Shared by tools/render-handbook.mjs (the badge), tools/check-handbook.mjs (every page resolves one) and
// tools/render-handbook-reference.mjs (the edition matrix, the API's edition column). Tests: tools/handbook-editions.test.mjs.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { gather } from './check-offerings.mjs';
import { resolveOffering } from './offering-classpath.mjs';
import { parseToon } from './toon-subset.mjs';

/** Edition order, first to last; each includes the one before. Preview is the demo and training build over Advanced. */
export const EDITIONS = ['Basic', 'Standard', 'Advanced', 'Preview'];
/** The editions a customer buys — what a badge names. */
export const SOLD = ['Basic', 'Standard', 'Advanced'];

const rank = (e) => EDITIONS.indexOf(e);
const first = (es) => es.filter(Boolean).sort((a, b) => rank(a) - rank(b))[0] ?? null;
const last = (es) => es.sort((a, b) => rank(b) - rank(a))[0] ?? null;

/** 'standard' | 'Standard' → 'Standard'; anything else → null. */
export const editionName = (s) => EDITIONS.find((e) => e.toLowerCase() === String(s ?? '').trim().toLowerCase()) ?? null;

/** The badge text: the sold editions from `edition` on ("Standard · Advanced"); Preview alone when only Preview has it. */
export function editionLabel(edition) {
    const r = rank(edition);
    if (r < 0) return null;
    const sold = SOLD.filter((e) => rank(e) >= r);
    return sold.length ? sold.join(' · ') : edition;
}

/** Is `declared` earlier than `derived` (a page claiming an edition its item does not ship in)? */
export const widens = (declared, derived) => rank(declared) < rank(derived);

/**
 * Everything the derivation reads, from a repository root: { offerings, manifests, items, apps, products }.
 * `items` maps a hub key `<kind>/<name>` to its parsed Product Map Item.
 */
export function gatherFacts(root) {
    const { offerings, manifests } = gather(root);
    const items = {};
    const pm = join(root, 'product-map');
    for (const kind of readdirSync(pm, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name)) {
        for (const f of readdirSync(join(pm, kind)).filter((n) => n.endsWith('.toon'))) {
            items[`${kind}/${f.slice(0, -5)}`] = parseToon(readFileSync(join(pm, kind, f), 'utf8'));
        }
    }
    const read = (dir, file) => {
        const out = {};
        const d = join(root, dir);
        for (const n of existsSync(d) ? readdirSync(d) : []) {
            const f = file ? join(d, n, file) : join(d, n);
            if (f.endsWith('.toon') && existsSync(f)) out[file ? n : n.slice(0, -5)] = parseToon(readFileSync(f, 'utf8'));
        }
        return out;
    };
    // an industry pack ships inside the module whose resources hold META-INF/inspecto/industry-packs/<pack>/
    const industryPacks = {};
    const marker = '/src/main/resources/META-INF/inspecto/industry-packs/';
    for (const f of execFileSync('git', ['ls-files', `*${marker}*`], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean)) {
        const [dir, rest] = f.split(marker);
        const pack = rest.split('/')[0];
        const toon = join(root, dir, 'src/main/resources/META-INF/inspecto/module.toon');
        if (rest.includes('/') && !industryPacks[pack] && existsSync(toon)) industryPacks[pack] = parseToon(readFileSync(toon, 'utf8')).id;
    }
    return { offerings, manifests, items, apps: read('apps', 'app.toon'), products: read('products'), industryPacks };
}

/** Build the derivation over `facts` (gatherFacts). Returns { module(id), item(key), hub(file) } → edition | null. */
export function editionIndex(facts) {
    const { offerings, manifests, items, apps, products, industryPacks } = facts;
    const g = { offerings, manifests };
    const closure = (id) => [id, ...(offerings[id]?.includes ?? []).flatMap(closure)];
    const offeringOf = (e) => e.toLowerCase();
    const resolved = Object.fromEntries(EDITIONS.filter((e) => offerings[offeringOf(e)]).map((e) => [e, resolveOffering(e, g)]));
    const lists = (e, pick) => closure(offeringOf(e)).some((o) => pick(offerings[o] ?? {}));

    const module = (id) => {
        const m = manifests[id];
        if (!m) return null;
        if (['base', 'internal'].includes(m.offeringRole)) return EDITIONS[0];
        return EDITIONS.find((e) => resolved[e]?.has(id)) ?? null;
    };
    const addon = (key) => EDITIONS.find((e) => offerings[offeringOf(e)] && lists(e, (o) => o.addons && key in o.addons)) ?? null;
    const pack = (t) => EDITIONS.find((e) => offerings[offeringOf(e)] && lists(e, (o) => (o.contentPacks ?? []).includes(t))) ?? null;

    const memo = new Map();
    const item = (key) => {
        if (memo.has(key)) return memo.get(key);
        memo.set(key, null);
        const it = items[key];
        let e = null;
        if (it) {
            switch (it.kind) {
                case 'module': e = module(it.module); break;
                case 'addon': e = addon(it.offeringAddon); break;
                case 'solution-pack':
                    e = it.industryPack ? (industryPacks?.[it.industryPack] ? module(industryPacks[it.industryPack]) : null) : pack(it.template);
                    break;
                case 'edition': e = editionName(it.offering); break;
                case 'product': e = first((products[it.product]?.editions ?? []).map(editionName)); break;
                case 'app': {
                    const app = apps[it.app];
                    if (!app) break;
                    const need = (app.requires?.addons ?? []).map(addon);
                    e = need.includes(null) ? null : last(need) ?? EDITIONS[0];
                    break;
                }
                case 'layer': e = first(Object.entries(items).filter(([, c]) => c.parent === it.id).map(([k]) => item(k))); break;
                default: e = null;
            }
        }
        memo.set(key, e);
        return e;
    };
    const HUB = 'docs/okf/product/';
    const hub = (file) => {
        if (!String(file ?? '').startsWith(HUB)) return null;
        const [kind, name] = file.slice(HUB.length).split('/');
        return name ? item(`${kind}/${name}`) : null;
    };
    return { module, item, hub };
}

/**
 * Attach each page's edition: `derived` from its hub, `edition` = the declared one (manifest `edition:`) or else the
 * derived one. Returns the same book, its pages annotated.
 */
export function withEditions(book, index) {
    for (const c of book.chapters) {
        for (const p of c.pages) {
            p.derivedEdition = index.hub(p.file);
            p.edition = p.declaredEdition ? editionName(p.declaredEdition) : p.derivedEdition;
        }
    }
    return book;
}
