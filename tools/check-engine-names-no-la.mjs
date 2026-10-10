#!/usr/bin/env node
// LA-DEMO-GUARDS-1 / DR-T3 - "the engine names no la-* class" (docs/okf/backend/modules/link-analysis.md section 2).
// The host reaches Link Analysis only through ServiceLoader SPIs it owns; one `import com.gamma.la.*` (or a fully
// qualified use) in inspecto/src/main would silently make the optional modules mandatory. Any mention of the two
// packages in a main-source .java file - import, qualified name, even a comment - fails. No exemptions.
// Usage: node tools/check-engine-names-no-la.mjs

import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const FORBIDDEN = /\bcom\.gamma\.(la|geolink)\b/;

function* javaFiles(dir) {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
        const p = join(dir, e.name);
        if (e.isDirectory()) yield* javaFiles(p);
        else if (e.name.endsWith('.java')) yield p;
    }
}

/** Violations under `root`/inspecto/src/main: [{file, line, text}]. */
export function check(root) {
    const out = [];
    for (const f of javaFiles(join(root, 'inspecto', 'src', 'main'))) {
        readFileSync(f, 'utf8').split('\n').forEach((text, i) => {
            if (FORBIDDEN.test(text)) out.push({ file: f, line: i + 1, text: text.trim() });
        });
    }
    return out;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const root = join(dirname(fileURLToPath(import.meta.url)), '..');
    const bad = check(root);
    if (bad.length) {
        console.error('Engine-names-no-LA guard FAILED: inspecto/src/main references the optional Link Analysis packages:');
        for (const b of bad) console.error(`  ${b.file}:${b.line}  ${b.text}`);
        process.exit(1);
    }
    console.log('Engine-names-no-LA guard: inspecto/src/main names no com.gamma.la.* / com.gamma.geolink.* class.');
}
