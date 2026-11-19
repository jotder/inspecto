#!/usr/bin/env node
// fill-doc-headers — add the missing D-IR4 header fields to hand-written hub pages that already carry the base header
// (INFO-RESTRUCTURE-1 wave W4, docs/superpower/2026-11-19-information-restructure-and-maintenance-plan.md §2.4, D-IR4).
//
//   node tools/fill-doc-headers.mjs [--write] [--hub <kind/name>]
//
// Dry run by default: prints what each page would gain. --write edits the pages. Re-running changes nothing.
//
// SCOPE: tracked `docs/okf/product/<kind>/<name>/<file>.md` pages that are not generated (isGenerated from
// tools/docs-health.mjs) and HAVE `type`, `title` and `description`. A page without that base header is left alone.
// Only MISSING keys are added, appended in D-IR4 order just before the closing `---`; an existing value is never
// changed and the body is never touched.
//
// WHAT EACH FIELD GETS
//   item      the hub's Product Map id `<kind>.<name>`.
//   audience  `external` when docs/okf/product/layer/product/handbook.toon lists the page with audience external,
//             else `internal`.
//   status    `plan` for a `plan-*` page, else `current`.
//   verified  `<date of the page's last git commit> last-edit` (today for a page not committed yet). ⚠ HONEST, NOT A REVIEW: it records when the page was
//             last touched, not that anyone checked its content against the code. A real review replaces the suffix.
//   code      the repo paths the item's code lives in, derived ONLY from what the Product Map links — never guessed:
//               module         the directory whose module manifest (…/META-INF/inspecto/module.toon) declares the item's
//                              `module:` id (tools/bundle-modules.mjs readModuleSources)
//               addon          the directories of the modules offerings/*.toon list under `addons.<offeringAddon>`
//               solution-pack  spaces/_templates/<template>; an industry pack (`industryPack:`) its
//                              …/META-INF/inspecto/industry-packs/<id> directory, as tools/check-product-map.mjs resolves it
//               app            apps/<app> and spaces/_templates/<t> for each template its app.toon names
//               edition        offerings/<offering>.toon
//               product        products/<product>.toon
//               industry       industries/<industry>
//               layer          left out — a layer links no code of its own
//             Every path must be tracked; an untracked one is dropped. When nothing remains, `code` is left out and
//             the item is listed in the run's report.
//
// Tests: tools/fill-doc-headers.test.mjs.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { frontmatter, isGenerated } from './docs-health.mjs';
import { parseToon } from './toon-subset.mjs';
import { readModuleSources } from './bundle-modules.mjs';

export const FILL_KEYS = ['item', 'audience', 'status', 'verified', 'code'];
const BASE_KEYS = ['type', 'title', 'description'];
const HUB_PAGE = /^docs\/okf\/product\/([^/]+)\/([^/]+)\/[^/]+\.md$/;
export const HANDBOOK = 'docs/okf/product/layer/product/handbook.toon';

/** `{kind, name}` of a hub page path, or null when the path is not a page directly in a hub folder. */
export function hubOf(path) {
    const m = HUB_PAGE.exec(path);
    return m ? { kind: m[1], name: m[2] } : null;
}

/** The handbook's page files mapped to their audience: walks every object that has `file` and `audience`. */
export function handbookAudiences(toonText) {
    const out = new Map();
    const walk = (o) => {
        if (!o || typeof o !== 'object' || Array.isArray(o)) return;
        if (typeof o.file === 'string' && typeof o.audience === 'string') out.set(o.file, o.audience);
        for (const v of Object.values(o)) walk(v);
    };
    walk(parseToon(toonText));
    return out;
}

/**
 * The code paths of a Product Map item, or [] when the map links none. ctx: { manifests: {moduleId: {dir}},
 * offerings: {id: parsed}, apps: {appId: parsed app.toon}, industryPacks: {packId: [dir]}, isTracked(path) }.
 */
export function codeFor(item, ctx) {
    const kind = item.kind;
    let paths = [];
    if (kind === 'module') {
        const dir = ctx.manifests[item.module]?.dir;
        if (dir) paths = [dir];
    } else if (kind === 'addon') {
        const mods = new Set();
        for (const o of Object.values(ctx.offerings)) for (const m of o.addons?.[item.offeringAddon]?.modules ?? []) mods.add(m);
        paths = [...mods].map((m) => ctx.manifests[m]?.dir).filter(Boolean).sort();
    } else if (kind === 'solution-pack') {
        if (item.template) paths = [`spaces/_templates/${item.template}`];
        if (item.industryPack) paths = [...(ctx.industryPacks?.[item.industryPack] ?? [])];
    } else if (kind === 'app') {
        if (item.app) paths = [`apps/${item.app}`, ...(ctx.apps[item.app]?.templates ?? []).map((t) => `spaces/_templates/${t}`)];
    } else if (kind === 'edition') {
        if (item.offering) paths = [`offerings/${item.offering}.toon`];
    } else if (kind === 'product') {
        if (item.product) paths = [`products/${item.product}.toon`];
    } else if (kind === 'industry') {
        if (item.industry) paths = [`industries/${item.industry}`];
    }
    return [...new Set(paths)].filter((p) => ctx.isTracked(p));
}

/** The fields a page lacks, given what the hub resolves to. Pure. */
export function missingFields(fm, page) {
    const add = {};
    // A key that is present at all — even empty — is the author's: adding it again would duplicate the key.
    const has = (k) => fm[k] !== undefined;
    if (!has('item')) add.item = page.item;
    if (!has('audience')) add.audience = page.audience;
    if (!has('status')) add.status = page.status;
    if (!has('verified') && page.lastEdit) add.verified = `${page.lastEdit} last-edit`;
    if (!has('code') && page.code.length) add.code = page.code;
    return add;
}

/** Insert `add` into the page's frontmatter just before its closing `---`. Lines and the body stay byte-identical. */
export function applyFields(text, add) {
    const keys = FILL_KEYS.filter((k) => add[k] !== undefined);
    if (!keys.length) return text;
    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    const lines = text.split(eol);
    const end = lines.indexOf('---', 1);
    if (lines[0] !== '---' || end < 0) throw new Error('page has no frontmatter block');
    const fmt = (v) => (Array.isArray(v) ? (v.length === 1 ? v[0] : `[${v.join(', ')}]`) : v);
    lines.splice(end, 0, ...keys.map((k) => `${k}: ${fmt(add[k])}`));
    return lines.join(eol);
}

/**
 * Plan the fill. in: { paths, read(path), items: {'<kind>.<name>': parsed item}, audiences: Map, lastEdit(path),
 * ctx (see codeFor), hub? '<kind>/<name>' }. out: { changes: [{path, add, text}], noCode: Set<item id>, noItem: Set }.
 */
export function planFill({ paths, read, items, audiences, lastEdit, ctx, hub }) {
    const changes = [], noCode = new Set(), noItem = new Set();
    for (const path of paths) {
        const h = hubOf(path);
        if (!h || (hub && `${h.kind}/${h.name}` !== hub)) continue;
        const text = read(path);
        if (isGenerated(path, text)) continue;
        const fm = frontmatter(text);
        if (!fm || !BASE_KEYS.every((k) => fm[k] !== undefined && fm[k] !== '')) continue;
        const id = `${h.kind}.${h.name}`;
        const item = items[id];
        if (!item) noItem.add(id);
        const code = item ? codeFor(item, ctx) : [];
        if (!code.length) noCode.add(id);
        const file = path.split('/').pop();
        const add = missingFields(fm, {
            item: id,
            audience: audiences.get(path) === 'external' ? 'external' : 'internal',
            status: file.startsWith('plan-') ? 'plan' : 'current',
            lastEdit: lastEdit(path),
            code,
        });
        if (Object.keys(add).length) changes.push({ path, add, text: applyFields(text, add) });
    }
    return { changes, noCode, noItem };
}

/** Latest commit date (YYYY-MM-DD) per path under docs/okf/product, from one `git log` pass. */
function lastEdits(root) {
    const out = new Map();
    const log = execFileSync('git', ['log', '--format=%x01%cs', '--name-only', '--', 'docs/okf/product'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 });
    let date = null;
    for (const l of log.split('\n')) {
        if (l.startsWith('\x01')) date = l.slice(1).trim();
        else if (l && !out.has(l)) out.set(l, date);
    }
    return out;
}

function main() {
    const argv = process.argv.slice(2);
    const write = argv.includes('--write');
    const hi = argv.indexOf('--hub');
    const hub = hi >= 0 ? argv[hi + 1] : undefined;
    const root = process.cwd();
    const tracked = execFileSync('git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 }).split('\0').filter(Boolean);
    const trackedSet = new Set(tracked);
    const dirs = new Set();
    for (const t of tracked) for (let i = t.indexOf('/'); i > 0; i = t.indexOf('/', i + 1)) dirs.add(t.slice(0, i));
    const isTracked = (p) => trackedSet.has(p) || dirs.has(p.replace(/\/$/, ''));

    const items = {};
    for (const f of tracked.filter((t) => /^product-map\/[^/]+\/[^/]+\.toon$/.test(t))) {
        const it = parseToon(readFileSync(join(root, f), 'utf8'));
        if (it.id) items[it.id] = it;
    }
    const { manifests, offerings } = readModuleSources(root);
    const apps = {};
    for (const f of tracked.filter((t) => /^apps\/[^/]+\/app\.toon$/.test(t))) apps[f.split('/')[1]] = parseToon(readFileSync(join(root, f), 'utf8'));
    const industryPacks = {};
    for (const d of dirs) {
        const m = /\/META-INF\/inspecto\/industry-packs\/([^/]+)$/.exec(d);
        if (m) (industryPacks[m[1]] ??= []).push(d);
    }
    const audiences = handbookAudiences(readFileSync(join(root, HANDBOOK), 'utf8'));
    const edits = lastEdits(root);

    const paths = tracked.filter((p) => hubOf(p) && existsSync(join(root, p)));
    const { changes, noCode, noItem } = planFill({
        paths, read: (p) => readFileSync(join(root, p), 'utf8'), items, audiences,
        // A page never committed is being written now: its last edit is today.
        lastEdit: (p) => edits.get(p) ?? new Date().toISOString().slice(0, 10), ctx: { manifests, offerings, apps, industryPacks, isTracked }, hub,
    });
    for (const c of changes) {
        console.log(`${c.path}: + ${Object.keys(c.add).join(', ')}`);
        if (write) writeFileSync(join(root, c.path), c.text);
    }
    console.log(`fill-doc-headers: ${changes.length} page(s) ${write ? 'filled' : 'would gain fields (dry run; --write to apply)'}`);
    if (noItem.size) console.log(`  hubs with no Product Map item: ${[...noItem].sort().join(', ')}`);
    if (noCode.size) console.log(`  items with no derivable code (code left out): ${[...noCode].sort().join(', ')}`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
