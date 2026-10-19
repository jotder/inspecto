#!/usr/bin/env node
// Release notes from git history, per Product Map Item and for the whole product.
//
//   node tools/release-notes.mjs <from-ref> [<to-ref>] [--item module.x] [--out dir]
//
// Each non-merge commit between the refs is attributed to Product Map Item(s):
//   1. by its Conventional-Commit scope when the scope names an item (`module.reconciliation`,
//      `reconciliation`, kebab/camel insensitive, plus a few historic aliases in ALIASES);
//   2. otherwise by the files it changed — a module directory (found via its
//      META-INF/inspecto/module.toon manifest, as tools/render-feature-db.mjs does) → module.<id>,
//      docs/okf/product/<kind>/<name>/… and product-map/<kind>/<name>.toon → that item.
// Merges and pure regenerations (every file a generated hub index.md / feature-db.md) are skipped.
// Output (default target/release-notes/): RELEASE-NOTES.md grouped by item then type, plus <item>.md
// per item. The scope rule itself: .claude/skills/release-workflow/SKILL.md.
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { parseToon } from './check-offerings.mjs';

/** Historic free-form scopes → item id. Only scopes that cannot be matched by name. */
export const ALIASES = {
    recon: 'module.reconciliation', la: 'layer.la', 'la-ui': 'layer.la', ui: 'layer.product',
    regreporting: 'module.regulatory-reporting', 'telco-fraud': 'solution-pack.telco-fraud',
    pipeline: 'module.etl', ingest: 'module.acquire', 'db-layer': 'module.sql', query: 'module.sql',
    control: 'module.api', jobs: 'module.ops', masking: 'module.access',
};
/** Types shown in the notes, in order. BREAKING is any `!` or `BREAKING CHANGE:` footer. */
export const SECTIONS = [['breaking', 'BREAKING changes'], ['feat', 'Features'], ['fix', 'Fixes'], ['perf', 'Performance'], ['docs', 'Documentation']];
const GENERATED = [/^docs\/okf\/product\/(.+\/)?index\.md$/, /^docs\/okf\/product\/.+\/feature-db(-[^/]*)?\.md$/];

/** A file-attributed commit touching more items than this is a sweep, listed once under CROSS. */
export const MAX_FAN = 5;
export const CROSS = '(cross-cutting)';

const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

/** world: { items: [{id, title}], moduleDirs: {dir: manifestId} } → resolvers. */
export function index(world) {
    const byId = new Map(world.items.map((i) => [i.id, i]));
    const byName = new Map();
    // module.* wins a name clash (`reconciliation` → module.reconciliation, not addon.reconciliation).
    const rank = (id) => (id.startsWith('module.') ? 0 : 1);
    for (const i of [...world.items].sort((a, b) => rank(b.id) - rank(a.id))) {
        byName.set(norm(i.id), i.id);
        byName.set(norm(i.id.slice(i.id.indexOf('.') + 1)), i.id);
    }
    const dirs = Object.entries(world.moduleDirs).sort((a, b) => b[0].length - a[0].length);
    return {
        byId,
        scope(scope) {
            if (!scope) return [];
            return [...new Set(scope.split(/[,/ ]+/).filter(Boolean).map((s) => (byId.has(s) ? s : byId.has(ALIASES[s]) ? ALIASES[s] : byName.get(norm(s)))).filter(Boolean))];
        },
        file(path) {
            let m = /^docs\/okf\/product\/([^/]+)\/([^/]+)\//.exec(path) ?? /^product-map\/([^/]+)\/([^/]+)\.toon$/.exec(path);
            if (m) return byName.get(norm(`${m[1]}.${m[2]}`)) ?? null;
            for (const [dir, id] of dirs) if (path.startsWith(`${dir}/`)) return byId.has(`module.${id}`) ? `module.${id}` : null;
            if (path.startsWith('inspecto-ui/')) return byId.has('layer.product') ? 'layer.product' : null;
            return null;
        },
    };
}

/** Raw `git log --format=%x1e%H%x1f%P%x1f%s%x1f%b%x1f --name-only` output → commits. */
export function parseLog(raw) {
    return raw.split('\x1e').filter((c) => c.trim()).map((c) => {
        const [hash, parents, subject, body, files] = c.split('\x1f');
        return { hash, parents: parents.trim().split(' ').filter(Boolean), subject: subject.trim(), body: body.trim(), files: (files ?? '').split('\n').map((f) => f.trim()).filter(Boolean) };
    });
}

/** Subject → { type, scope, breaking, text } (type null when not Conventional). */
export function parseSubject(subject, body = '') {
    const m = /^([a-z]+)(?:\(([^)]*)\))?(!)?:\s*(.*)$/.exec(subject);
    if (!m) return { type: null, scope: null, breaking: /^BREAKING[ -]CHANGE:/m.test(body), text: subject };
    return { type: m[1], scope: m[2] ?? null, breaking: !!m[3] || /^BREAKING[ -]CHANGE:/m.test(body), text: m[4] };
}

/** commits + index → { entries: [{hash, type, breaking, text, scope, items, by}], skipped: {merge, generated} } */
export function attribute(commits, idx) {
    const skipped = { merge: 0, generated: 0 };
    const entries = [];
    for (const c of commits) {
        if (c.parents.length > 1 || /^Merge /.test(c.subject)) { skipped.merge++; continue; }
        // A regeneration: generated outputs plus, at most, files no item owns (a plan, an inventory).
        const own = c.files.filter((f) => !GENERATED.some((g) => g.test(f)));
        if (own.length < c.files.length && own.every((f) => !idx.file(f))) { skipped.generated++; continue; }
        const s = parseSubject(c.subject, c.body);
        let items = idx.scope(s.scope), by = 'scope';
        if (!items.length) { items = [...new Set(c.files.map((f) => idx.file(f)).filter(Boolean))].sort(); by = items.length ? 'files' : 'none'; }
        if (by === 'files' && items.length > MAX_FAN) { items = [CROSS]; by = 'wide'; }
        entries.push({ hash: c.hash, ...s, items, by });
    }
    return { entries, skipped };
}

const section = (e) => (e.breaking ? 'breaking' : e.type);
const line = (e) => `- ${e.scope ? `**${e.scope}:** ` : ''}${e.text} (\`${e.hash.slice(0, 9)}\`)`;

function sections(entries, level) {
    const out = [];
    for (const [key, title] of SECTIONS) {
        const es = entries.filter((e) => section(e) === key);
        if (es.length) out.push(`${'#'.repeat(level)} ${title}`, '', ...es.map(line), '');
    }
    return out;
}

/** → { 'RELEASE-NOTES.md': text, '<item>.md': text, ... } */
export function render({ entries, skipped }, idx, { from, to, only } = {}) {
    const files = {};
    const notable = entries.filter((e) => SECTIONS.some(([k]) => k === section(e)));
    const groups = new Map();
    for (const e of notable) for (const id of e.items.length ? e.items : ['(unattributed)']) {
        if (only && id !== only) continue;
        if (!groups.has(id)) groups.set(id, []);
        groups.get(id).push(e);
    }
    const ids = [...groups.keys()].sort((a, b) => a.startsWith('(') - b.startsWith('(') || a.localeCompare(b));
    const range = `\`${from}\`..\`${to}\``;
    const attributed = entries.filter((e) => e.items.length && e.items[0] !== CROSS).length;
    const wide = entries.filter((e) => e.items[0] === CROSS).length;
    const pct = entries.length ? Math.round((100 * attributed) / entries.length) : 100;
    const title = (id) => (idx.byId.get(id)?.title ? `${idx.byId.get(id).title} (\`${id}\`)` : id);
    const head = [`# Release notes ${range}`, '',
        `> Generated by \`tools/release-notes.mjs\` from git history. ${entries.length} commits considered, ` +
        `${attributed} (${pct}%) attributed to a Product Map Item, ${wide} cross-cutting (> ${MAX_FAN} items); ${skipped.merge} merges and ${skipped.generated} pure regenerations skipped.`, ''];
    const whole = [...head];
    for (const id of ids) {
        whole.push(`## ${title(id)}`, '', ...sections(groups.get(id), 3));
        if (!id.startsWith('(')) files[`${id}.md`] = [`# ${title(id)} — release notes ${range}`, '', '> Generated by `tools/release-notes.mjs`.', '', ...sections(groups.get(id), 2)].join('\n');
    }
    if (!ids.length) whole.push('_No feat / fix / perf / docs / BREAKING commits in range._', '');
    files['RELEASE-NOTES.md'] = whole.join('\n');
    return { files, coverage: { considered: entries.length, attributed, wide, pct } };
}

export function loadWorld(root) {
    const items = [];
    const pm = join(root, 'product-map');
    for (const d of readdirSync(pm, { withFileTypes: true })) {
        if (!d.isDirectory() || d.name === 'private') continue;
        for (const f of readdirSync(join(pm, d.name)).filter((n) => n.endsWith('.toon'))) {
            const t = parseToon(readFileSync(join(pm, d.name, f), 'utf8'));
            if (t.id) items.push({ id: t.id, title: t.title });
        }
    }
    const moduleDirs = {};
    for (const f of execFileSync('git', ['ls-files', '*META-INF/inspecto/module.toon'], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean))
        moduleDirs[f.replace(/\/src\/main\/resources\/META-INF\/inspecto\/module\.toon$/, '')] = parseToon(readFileSync(join(root, f), 'utf8')).id;
    return { items, moduleDirs };
}

function main() {
    const args = process.argv.slice(2);
    const opt = (k) => (args.includes(k) ? args.splice(args.indexOf(k), 2)[1] : undefined);
    const only = opt('--item'), out = opt('--out') ?? 'target/release-notes';
    const [from, to = 'HEAD'] = args;
    if (!from) { console.error('usage: node tools/release-notes.mjs <from-ref> [<to-ref>] [--item module.x] [--out dir]'); process.exit(2); }
    const raw = execFileSync('git', ['log', '--format=%x1e%H%x1f%P%x1f%s%x1f%b%x1f', '--name-only', `${from}..${to}`], { encoding: 'utf8', maxBuffer: 1 << 28 });
    const idx = index(loadWorld('.'));
    if (only && !idx.byId.has(only)) { console.error(`unknown item ${only}`); process.exit(2); }
    const { files, coverage } = render(attribute(parseLog(raw), idx), idx, { from, to, only });
    if (!existsSync(out)) mkdirSync(out, { recursive: true });
    for (const [name, text] of Object.entries(files)) writeFileSync(join(out, name), text);
    console.log(`release-notes: ${Object.keys(files).length} file(s) → ${out}; ${coverage.attributed}/${coverage.considered} commits attributed (${coverage.pct}%), ${coverage.wide} cross-cutting`);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
