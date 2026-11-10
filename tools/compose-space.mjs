#!/usr/bin/env node
// compose-space - compose a Product's Space Template from its Apps' templates (PRODUCT-ASSEMBLY-1 PA-5, decision D-PA14;
// plan docs/superpower/2026-11-10-product-assembly-plan.md §11).
//
// A Space is created from exactly ONE Space Template (POST /spaces {template}), and PA-5 adds no Java. So a Domain product
// whose Apps share one Space ships a template made of theirs: every part's config/ and data/ files side by side, plus the
// two files written here (COMPOSER_OWNED in tools/products.mjs):
//   template.toon                              the gallery entry, named for the Product, listing each part's contents
//   config/examples/sla-escalation-examples.md each part's examples under its App's heading
// Any other file two parts both ship is refused. tools/check-products.mjs refuses the same collision statically, so a
// refusal here means the staged copies differ from the committed definitions.
//
//   node tools/compose-space.mjs --plan <assembly-plan.json> --templates <dir>
//       composes <dir>/<plan.space.id> from <dir>/<part> for each of plan.space.parts. package.ps1 runs it on the
//       bundle's staged spaces/_templates, so only committed content is composed.

import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { COMPOSER_OWNED } from './products.mjs';

const EDITION_ORDER = ['basic', 'standard', 'advanced', 'preview'];
const SLA_DOC = 'config/examples/sla-escalation-examples.md';
const fail = (msg) => { throw new Error(msg); };

/** `"a \"b\""` -> `a "b"`; an unquoted value is trimmed as is. */
const unquote = (v) => (v.startsWith('"') ? v.slice(1, -1).replace(/\\(["\\])/g, '$1') : v);
const quote = (s) => `"${String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

/**
 * A template.toon (flat `key: value` lines, values quoted or not, and `contents[N]: "a", "b"`) as an object. Its own reader:
 * tools/toon-subset.mjs splits a list on every comma, and a contents item holds commas inside its quotes.
 */
export function readTemplateMeta(text) {
    const out = {};
    for (const line of text.split(/\r?\n/)) {
        const m = /^([A-Za-z_]\w*)(?:\[(\d+)\])?:\s*(.*)$/.exec(line.trim());
        if (!m) continue;
        const [, key, n, val] = m;
        if (n === undefined) { out[key] = unquote(val.trim()); continue; }
        const items = [...val.matchAll(/"((?:[^"\\]|\\.)*)"|([^,"]+)/g)].map((x) => (x[1] !== undefined ? x[1].replace(/\\(["\\])/g, '$1') : x[2].trim())).filter((x) => x !== '');
        if (items.length !== Number(n)) fail(`${key}[${n}] has ${items.length} items`);
        out[key] = items;
    }
    return out;
}

/** The composed template.toon text. */
export function templateToon({ title, parts }) {
    const apps = [...new Map(parts.map((p) => [p.app, p.appTitle])).values()];
    const minEdition = parts.map((p) => p.meta.minEdition ?? 'basic').reduce((a, b) => (EDITION_ORDER.indexOf(b) > EDITION_ORDER.indexOf(a) ? b : a), 'basic');
    const contents = parts.flatMap((p) => (p.meta.contents ?? []).map((c) => `${p.appTitle}: ${c}`));
    const description = `The ${title} Space, composed from the Space Templates of its Apps: `
        + `${apps.map((a) => `${a} (${parts.filter((p) => p.appTitle === a).map((p) => p.template).join(', ')})`).join('; ')}. `
        + parts.map((p) => `${p.meta.name ?? p.template}: ${p.meta.description ?? ''}`).join(' ');
    return [
        `name: ${quote(title)}`,
        `tagline: ${quote(`${apps.join(', ')} in one Space`)}`,
        `description: ${quote(description)}`,
        `minEdition: ${minEdition}`,
        `icon: ${parts[0]?.meta.icon ?? 'heroicons_outline:squares-2x2'}`,
        `contents[${contents.length}]: ${contents.map(quote).join(', ')}`,
        '',
    ].join('\n');
}

/** The composed SLA examples: each part's file under its App's heading, its own first-level heading dropped. */
export function slaDoc({ title, parts }) {
    const sections = parts
        .filter((p) => p.sla !== null)
        .map((p) => `## ${p.appTitle} (${p.template})\n\n${p.sla.replace(/\r\n/g, '\n').replace(/^# .*\n+/, '').trimEnd()}\n`);
    return [`# EXAMPLE SLA and escalation values - ${title}`, '', 'Composed from the Space Templates of its Apps: each part\'s examples as its own template ships them.', '', ...sections].join('\n');
}

/** Every file under `dir`, as `/`-separated paths relative to it. */
function walk(dir, rel = '') {
    return readdirSync(join(dir, rel), { withFileTypes: true }).flatMap((d) => {
        const r = rel ? `${rel}/${d.name}` : d.name;
        return d.isDirectory() ? walk(dir, r) : [r];
    });
}

/**
 * Compose `<templatesDir>/<space.id>` from `<templatesDir>/<part.template>` for each part. Refuses an existing target, a
 * missing part and a file two parts both ship (other than COMPOSER_OWNED). Returns { id, files, parts }.
 */
export function composeSpace(space, templatesDir) {
    const out = join(templatesDir, space.id);
    if (existsSync(out)) fail(`${out} already exists - a composed Space is written once, into a fresh staging folder`);
    if (!space.parts?.length) fail(`Space '${space.id}' has no parts - none of the selected Apps ships a template`);
    const parts = space.parts.map((p) => {
        const dir = join(templatesDir, p.template);
        if (!existsSync(join(dir, 'template.toon'))) fail(`part '${p.template}' (App '${p.app}') is not a staged template under ${templatesDir}`);
        const sla = join(dir, SLA_DOC);
        return { ...p, dir, meta: readTemplateMeta(readFileSync(join(dir, 'template.toon'), 'utf8')), sla: existsSync(sla) ? readFileSync(sla, 'utf8') : null };
    });
    const owner = new Map();
    for (const p of parts) {
        for (const f of walk(p.dir)) {
            if (COMPOSER_OWNED.includes(f)) continue;
            if (owner.has(f)) fail(`templates '${owner.get(f)}' and '${p.template}' both ship ${f} - the composed Space '${space.id}' cannot hold both`);
            owner.set(f, p.template);
        }
    }
    for (const [f, t] of owner) {
        const to = join(out, ...f.split('/'));
        mkdirSync(dirname(to), { recursive: true });
        copyFileSync(join(templatesDir, t, ...f.split('/')), to);
    }
    writeFileSync(join(out, 'template.toon'), templateToon({ title: space.title, parts }));
    if (parts.some((p) => p.sla !== null)) {
        mkdirSync(dirname(join(out, SLA_DOC)), { recursive: true });
        writeFileSync(join(out, ...SLA_DOC.split('/')), slaDoc({ title: space.title, parts }));
    }
    return { id: space.id, files: owner.size + 1 + (parts.some((p) => p.sla !== null) ? 1 : 0), parts: parts.map((p) => p.template) };
}

function main() {
    const a = process.argv.slice(2);
    const val = (k) => { const i = a.indexOf(k); return i >= 0 ? a[i + 1] : undefined; };
    try {
        const planFile = val('--plan') ?? fail('--plan <assembly-plan.json> is required');
        const templates = val('--templates') ?? fail('--templates <dir> is required');
        const plan = JSON.parse(readFileSync(planFile, 'utf8'));
        if (!plan.space) { console.log(`compose-space: Product '${plan.product?.id}' composes no Space - nothing to do`); return; }
        const r = composeSpace(plan.space, templates);
        console.log(`compose-space: composed '${r.id}' from ${r.parts.join(', ')} (${r.files} files)`);
    } catch (e) {
        console.error(`compose-space: ${e.message}`);
        process.exit(1);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
