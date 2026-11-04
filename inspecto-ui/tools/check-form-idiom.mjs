#!/usr/bin/env node
// Form-idiom guard (UI-FORM-ROWS-1; as built: docs/okf/product/layer/product/spec-forms-state.md "Property rows").
//
// One form idiom app-wide: a typed value is an `<inspecto-pen-field>` row, a choice an `<inspecto-option-picker>` row
// (label · value · ✎ / ▾). The older boxed Material field (`<mat-form-field>`) and dropdown (`<mat-select>`) may stay
// only where they already are - the allow-list next to this file - and the list only SHRINKS:
//   - a file that holds either tag and is not on the list fails ("use pen-field / option-picker");
//   - a listed file that no longer holds either tag fails too ("remove it from the list"), so a converted screen
//     cannot quietly go back.
// Escape hatch, for what the plan keeps boxed on purpose (code / SQL / JSON editors, grid filters, search boxes):
// `form-allow` in a comment on the tag's line, with the reason.
//
// Zero dependencies. Run from inspecto-ui/: node tools/check-form-idiom.mjs. FORM_IDIOM_ROOT aims it at a throwaway
// tree (the test does), and FORM_IDIOM_ALLOWLIST at another list.
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const uiRoot = process.env.FORM_IDIOM_ROOT ?? join(here, '..');
const listFile = process.env.FORM_IDIOM_ALLOWLIST ?? join(here, 'form-idiom-allowlist.txt');
const toPosix = (p) => p.split(sep).join('/');

// inspecto-authored UI: the console app and the libraries / applications under projects/. The vendored gamma / Fuse
// scaffolding lives at src/@gamma, outside these roots.
const ROOTS = ['src/app', 'projects'];
const SKIP_DIRS = new Set(['node_modules', 'dist', '.angular']);
const TAG = /<mat-(form-field|select)\b/;

function* files(dir) {
    if (!existsSync(dir)) return;
    for (const name of readdirSync(dir)) {
        const p = join(dir, name);
        if (statSync(p).isDirectory()) {
            if (!SKIP_DIRS.has(name)) yield* files(p);
        } else if ((name.endsWith('.ts') || name.endsWith('.html')) && !name.endsWith('.spec.ts')) {
            yield p;
        }
    }
}

const allowed = new Set(
    existsSync(listFile)
        ? readFileSync(listFile, 'utf8')
              .split(/\r?\n/)
              .map((l) => l.trim())
              .filter((l) => l && !l.startsWith('#'))
        : [],
);

const holding = new Set();
for (const root of ROOTS)
    for (const f of files(join(uiRoot, root))) {
        const lines = readFileSync(f, 'utf8').split(/\r?\n/);
        // a tag named in a comment (a doc line, a removed-field note) is not a field
        const comment = (l) => /^\s*(\/\/|\/\*|\*|<!--)/.test(l);
        if (lines.some((l) => TAG.test(l) && !comment(l) && !l.includes('form-allow')))
            holding.add(toPosix(relative(uiRoot, f)));
    }

const added = [...holding].filter((f) => !allowed.has(f)).sort();
const stale = [...allowed].filter((f) => !holding.has(f)).sort();

if (added.length || stale.length) {
    for (const f of added)
        console.error(
            `  ${f}: a boxed <mat-form-field> / <mat-select> - ask for a value with <inspecto-pen-field> and a ` +
                'choice with <inspecto-option-picker> (or mark a deliberate exception `form-allow` with the reason)',
        );
    for (const f of stale)
        console.error(`  ${f}: holds no boxed field any more - remove it from tools/form-idiom-allowlist.txt`);
    console.error(
        `✖ Form-idiom guard: ${added.length} new file(s) with boxed fields, ${stale.length} stale allow-list ` +
            'entr' +
            (stale.length === 1 ? 'y' : 'ies') +
            ' (UI-FORM-ROWS-1)',
    );
    process.exit(1);
}
console.log(`✓ Form-idiom guard: ${holding.size} file(s) still hold boxed fields, all on the shrinking allow-list`);
