#!/usr/bin/env node
// render-product-docs — writes the documentation hub docs/okf/product/<kind>/<name>/index.md for EVERY Product Map Item
// from the files in its folder, and the hub list docs/okf/product/index.md (PRODUCT-DOCS-HUB-1,
// docs/superpower/product-docs-hub-plan.md). Each product-map item's `docs:` is pointed at its hub.
//
//   node tools/render-product-docs.mjs [--check]      --check: exit 1 if any written file would change
//
// A file belongs to a slot by name: `<slot>.md` or `<slot>-<topic>.md`. Never hand-edit a hub index — rename or add files.

import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { SLOTS } from './check-product-docs.mjs';

const HUBS = 'docs/okf/product', MAP = 'product-map';
const check = process.argv.includes('--check');
const KIND_ORDER = ['edition', 'solution-pack', 'layer', 'addon', 'module', 'functionality'];
const KIND_TITLE = { edition: 'Editions', 'solution-pack': 'Solution packs', layer: 'Layers', addon: 'Add-ons', module: 'Modules', functionality: 'Functionalities' };
let stale = 0;
// Slots that do not apply to a KIND of item (a file in the hub still wins). Per-hub exceptions: `slots-na.toon` in the hub.
const MODULES = 'see the hubs of the modules it holds';
const KIND_NA = {
    edition: Object.fromEntries(['plan', 'spec', 'architecture', 'user-guide', 'admin-guide', 'api', 'tests'].map((s) => [s, `an Edition is a build flavor — ${MODULES}`])),
    addon: Object.fromEntries(['plan', 'spec', 'architecture', 'user-guide', 'admin-guide', 'api', 'tests'].map((s) => [s, `an Add-on licenses modules — ${MODULES}`])),
    'solution-pack': Object.fromEntries(['plan', 'spec', 'api', 'tests'].map((s) => [s, `a Solution pack configures modules — ${MODULES}`])),
};
const LAYER_NA = Object.fromEntries(['plan', 'spec', 'user-guide', 'admin-guide', 'api', 'tests'].map((s) => [s, `a layer groups modules — ${MODULES}`]));

const titleOf = (text, fallback) => /^title:\s*(.+)$/m.exec(text)?.[1].trim().replace(/^["']|["']$/g, '')
    ?? /^#\s+(.+)$/m.exec(text)?.[1].trim() ?? fallback;
function put(path, text) {
    const old = existsSync(path) ? readFileSync(path, 'utf8') : undefined;
    if (old === text) return;
    stale++;
    if (check) { console.error(`  stale: ${path}`); return; }
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, text);
}

const items = [];
for (const kind of KIND_ORDER) {
    if (!existsSync(join(MAP, kind))) continue;
    for (const f of readdirSync(join(MAP, kind)).filter((f) => f.endsWith('.toon')).sort()) {
        const name = f.slice(0, -5), id = `${kind}.${name}`, mapPath = join(MAP, kind, f);
        let toon = readFileSync(mapPath, 'utf8');
        const title = /^title:\s*(.+)$/m.exec(toon)?.[1].trim() ?? id;
        const dir = join(HUBS, kind, name), hubRel = `docs/okf/product/${kind}/${name}/index.md`;
        const files = existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.md') && f !== 'index.md').sort() : [];
        const naFile = join(dir, 'slots-na.toon');
        // per-hub decisions: `<slot>: <why this slot does not apply>` — one line each
        const naOverride = existsSync(naFile) ? Object.fromEntries(readFileSync(naFile, 'utf8').split(/\r?\n/)
            .map((l) => /^([a-z-]+):\s*(.+)$/.exec(l.trim())).filter(Boolean).map((m) => [m[1], m[2].replace(/^"|"$/g, '')])) : {};
        const rows = SLOTS.map((slot) => {
            const mine = files.filter((f) => f === `${slot}.md` || f.startsWith(`${slot}-`));
            if (mine.length) return `| ${slot} | current | ${mine.map((f) => `[${titleOf(readFileSync(join(dir, f), 'utf8'), f)}](${f})`).join(' · ')} |`;
            if (naOverride[slot]) return `| ${slot} | n/a | ${naOverride[slot]} |`;
            if (slot === 'brochure' && kind !== 'edition' && kind !== 'solution-pack') return `| ${slot} | n/a | per edition / solution pack |`;
            if (slot === 'feature-db') return `| ${slot} | n/a | no feature rows derive to this item (\`tools/render-feature-db.mjs\`) |`;
            const na = KIND_NA[kind]?.[slot] ?? (kind === 'layer' && !['platform', 'product'].includes(name) ? LAYER_NA[slot] : undefined);
            if (na) return `| ${slot} | n/a | ${na} |`;
            return `| ${slot} | missing | |`;
        });
        const orphans = files.filter((f) => !SLOTS.some((s) => f === `${s}.md` || f.startsWith(`${s}-`)));
        if (orphans.length) throw new Error(`${dir}: file(s) match no slot: ${orphans.join(', ')}`);
        put(join(dir, 'index.md'), `---
type: ProductItem
title: ${title}
item: ${id}
description: Documentation hub for ${id} — every document about it, by slot.
tags: [product-item, ${kind}]
---

# ${title} (\`${id}\`)

> **Documentation hub** (\`PRODUCT-DOCS-HUB-1\`) for Product Map Item
> [\`${id}\`](../../../../../product-map/${kind}/${f}). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by \`tools/render-product-docs.mjs\` from the files in
> this folder — add or rename a file (\`<slot>.md\` / \`<slot>-<topic>.md\`), never edit this table.

| Slot | Status | Document |
|---|---|---|
${rows.join('\n')}

Status: \`current\` (in this folder) · \`missing\` (a real gap) · \`n/a\` (decided: the reason says why; per-hub decisions in \`slots-na.toon\`).
`);
        const docsLine = `docs: ${hubRel}`;
        const next = /^docs:.*$/m.test(toon) ? toon.replace(/^docs:.*$/m, docsLine) : toon.replace(/\n?$/, `\n${docsLine}\n`);
        put(mapPath, next);
        items.push({ kind, name, id, title, count: files.length });
    }
}

const list = KIND_ORDER.filter((k) => items.some((i) => i.kind === k)).map((k) => `# ${KIND_TITLE[k]}\n\n${items.filter((i) => i.kind === k)
    .map((i) => `* [${i.title}](${i.kind}/${i.name}/index.md) - \`${i.id}\` · ${i.count} doc(s)`).join('\n')}`).join('\n\n');
put(join(HUBS, 'index.md'), `---
type: Index
title: Product documentation hubs
description: One hub per Product Map Item, each with the same ten document slots — the docs tree mirrors product-map/.
tags: [index, product-item]
---

# Product documentation hubs

**Every current document about Inspecto lives in exactly one hub**: \`docs/okf/product/<kind>/<name>/\`, mirroring
\`product-map/<kind>/<name>.toon\`. Each hub's \`index.md\` lists the same ten slots — plan, spec, architecture,
features, feature-db, user-guide, admin-guide, api, tests, brochure — and file names say the slot
(\`<slot>.md\` / \`<slot>-<topic>.md\`). Cross-cutting backend docs live in \`layer/platform\`, UI-wide docs in
\`layer/product\`. Design: [\`product-docs-hub-plan.md\`](../../superpower/product-docs-hub-plan.md).
Generated by \`node tools/render-product-docs.mjs\`; checked by \`node tools/check-product-docs.mjs\`.

${list}
`);
console.log(`render-product-docs: ${items.length} hub(s); ${stale} file(s) ${check ? 'stale' : 'written'}`);
if (check && stale) process.exit(1);
