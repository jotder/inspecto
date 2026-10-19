#!/usr/bin/env node
// Advisory docs-touched check (PRODUCT-DOCS-HUB-1, operator 2026-10-19). For a commit range, every Product Map Item whose module
// main code changed (`<module dir>/src/main/**`, or an obvious `inspecto-ui/src/app/inspecto/<name>/` feature folder) but whose
// hub `docs/okf/product/<kind>/<name>/` got no hand-written change (generated slots — index, feature-db, tests/api inventories —
// don't count) gets a WARNING naming the commits, the hub and the slots most likely due, guessed from the added diff lines:
//   new route registration → api · new System.getProperty / config key → admin-guide + spec · new public SPI interface → api + spec
//   · UI change → user-guide.
// Many code changes need no doc change, so this NEVER fails: it always exits 0. On GitHub Actions it emits `::warning` annotations.
//
// Usage: node tools/check-docs-touched.mjs [<from-ref>] [<to-ref>]
//   default range: origin/<GITHUB_BASE_REF>..HEAD on a PR, else origin/master~20..HEAD.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { parseToon } from './check-offerings.mjs';

const GENERATED = /(^|\/)(index|feature-db|tests-[a-z-]*inventory|api-routes)\.md$|\.toon$/;
const UI_ROOT = 'inspecto-ui/src/app/inspecto/';

/** Slot heuristics over the ADDED lines of one file's diff. */
export function likelySlots(path, added) {
    const s = new Set();
    if (path.startsWith('inspecto-ui/')) { s.add('user-guide'); return s; }
    const text = added.join('\n');
    if (/\bapi\.(get|post|put|patch|delete)\(\s*"\/|\b(register|add)\(\s*"(GET|POST|PUT|PATCH|DELETE)"\s*,\s*"\//.test(text)) s.add('api');
    if (/\b(System\.getProperty|Boolean\.getBoolean|Integer\.getInteger|Long\.getLong|System\.getenv)\s*\(/.test(text)) { s.add('admin-guide'); s.add('spec'); }
    if (/\b(config|cfg|conf|settings)\.(get|getString|getInt|getLong|getBoolean|string|integer|bool|opt)\w*\s*\(\s*"[\w.-]+"/.test(text)) { s.add('admin-guide'); s.add('spec'); }
    if (/^\s*public\s+(sealed\s+|non-sealed\s+)?interface\s+\w+/m.test(text) && /\/spi\/|Spi|Provider|Plugin/.test(path + text)) { s.add('api'); s.add('spec'); }
    if (/META-INF\/inspecto\/module\.toon$/.test(path)) s.add('features');
    return s;
}

/** Map a changed path to an item key 'kind/name' (or null). moduleDirs: [[dir, itemKey]] longest dir first; uiItems: Set of names. */
export function itemOf(path, moduleDirs, uiItems) {
    for (const [dir, key] of moduleDirs) if (path.startsWith(dir + '/src/main/')) return key;
    if (path.startsWith(UI_ROOT) && !/\.spec\.ts$/.test(path)) {
        const name = path.slice(UI_ROOT.length).split('/')[0];
        if (uiItems.has(name) && path.slice(UI_ROOT.length).includes('/')) return `module/${name}`;
    }
    return null;
}

/** commits: [{ sha, subject, files: [{ path, added: [lines] }] }] → warnings [{ item, hub, commits, slots }]. */
export function analyse(commits, moduleDirs, uiItems) {
    const code = {}, touched = new Set();
    for (const c of commits) for (const f of c.files) {
        const hub = /^docs\/okf\/product\/([a-z-]+\/[a-z0-9-]+)\//.exec(f.path);
        if (hub && !GENERATED.test(f.path)) touched.add(hub[1]);
        const key = itemOf(f.path, moduleDirs, uiItems);
        if (!key) continue;
        const e = (code[key] ??= { commits: new Map(), slots: new Set() });
        e.commits.set(c.sha, c.subject);
        for (const s of likelySlots(f.path, f.added)) e.slots.add(s);
    }
    return Object.entries(code).filter(([k]) => !touched.has(k)).sort(([a], [b]) => a.localeCompare(b))
        .map(([item, e]) => ({ item, hub: `docs/okf/product/${item}/`, commits: [...e.commits].map(([sha, subject]) => ({ sha, subject })), slots: [...e.slots].sort() }));
}

/** Parse `git log -p --format=...` output (unified=0) into the commits shape above. */
export function parseLog(text) {
    const commits = [];
    let c = null, f = null;
    for (const line of text.split('\n')) {
        const h = /^\x1e([0-9a-f]{7,40})\x1f(.*)$/.exec(line);
        if (h) { commits.push(c = { sha: h[1].slice(0, 9), subject: h[2], files: [] }); f = null; continue; }
        if (!c) continue;
        const d = /^diff --git a\/(.+?) b\/(.+)$/.exec(line);
        if (d) { c.files.push(f = { path: d[2], added: [] }); continue; }
        if (f && line.startsWith('+') && !line.startsWith('+++')) f.added.push(line.slice(1));
    }
    return commits;
}

function moduleMap(root) {
    const git = (...a) => execFileSync('git', a, { cwd: root, encoding: 'utf8', maxBuffer: 1 << 30 });
    const dirOf = {};
    for (const f of git('ls-files', '*META-INF/inspecto/module.toon').split('\n').filter(Boolean))
        dirOf[parseToon(readFileSync(join(root, f), 'utf8')).id] = f.slice(0, f.indexOf('/src/main/'));
    const dirs = [], uiItems = new Set();
    const pm = join(root, 'product-map/module');
    for (const f of existsSync(pm) ? readdirSync(pm).filter((n) => n.endsWith('.toon')) : []) {
        const name = f.slice(0, -5), id = parseToon(readFileSync(join(pm, f), 'utf8')).module;
        uiItems.add(name);
        if (id && dirOf[id]) dirs.push([dirOf[id], `module/${name}`]);
    }
    return [dirs.sort((a, b) => b[0].length - a[0].length), uiItems];
}

function main() {
    const [from = process.env.GITHUB_BASE_REF ? `origin/${process.env.GITHUB_BASE_REF}` : 'origin/master~20', to = 'HEAD'] = process.argv.slice(2);
    const root = '.';
    let log;
    try {
        log = execFileSync('git', ['log', '--no-merges', '-p', '--unified=0', '--no-color', '--format=%x1e%H%x1f%s', `${from}..${to}`],
            { cwd: root, encoding: 'utf8', maxBuffer: 1 << 30 });
    } catch (e) {
        console.log(`check-docs-touched: cannot read ${from}..${to} (${String(e.message).split('\n')[0]}) — skipped`);
        return;
    }
    const commits = parseLog(log);
    const ws = analyse(commits, ...moduleMap(root));
    const gha = process.env.GITHUB_ACTIONS === 'true';
    for (const w of ws) {
        const slots = w.slots.length ? w.slots.join(', ') : 'any (check features / spec)';
        const shas = w.commits.map((c) => c.sha).join(' ');
        const msg = `${w.item}: code changed in ${w.commits.length} commit(s) but ${w.hub} was not touched — likely slots: ${slots}`;
        if (gha) console.log(`::warning title=Docs not touched (${w.item})::${msg} [${shas}]`);
        else {
            console.log(`⚠ ${msg}`);
            for (const c of w.commits) console.log(`    ${c.sha} ${c.subject}`);
        }
    }
    console.log(`check-docs-touched: ${commits.length} commit(s) in ${from}..${to}, ${ws.length} item(s) with code but no hub change (advisory)`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
