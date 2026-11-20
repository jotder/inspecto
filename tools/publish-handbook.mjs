#!/usr/bin/env node
// publish-handbook — keep both Handbook renders current, and publish them once their places are decided
// (USER-HANDBOOK-1; docs/superpower/2026-11-19-user-enablement-and-agent-kit-plan.md, D-UE8 and D-UE10).
//
//   node tools/publish-handbook.mjs
//
// Always renders the external Handbook to target/handbook/external and the internal one (Inside Inspecto) to
// target/handbook/internal (gitignored), so both are ready to look at whatever the state of the decisions.
// Then, for each place set in handbook.toon's `handbook.publish` (`external` = D-UE8, `internal` = D-UE10; `none`
// until decided), renders again into that folder — a web-root checkout, a synced share, a network path. Setting a
// place is the whole change once a decision is made. The `handoff` skill runs this at shift end, so the renders follow
// development.
//
// Safety: the internal render never goes to the external place nor anywhere inside it (compared after resolving links,
// case-insensitively on Windows); a place that is a non-empty folder this tool did not
// write (no `.handbook` marker) is refused, never overwritten.
//
// Tests: tools/publish-handbook.test.mjs.

import { existsSync, readFileSync, readdirSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { MANIFEST, loadManifest, writeRender } from './render-handbook.mjs';
import { editionIndex, gatherFacts, withEditions } from './handbook-editions.mjs';

export const LOCAL = { external: 'target/handbook/external', internal: 'target/handbook/internal' };

/** The folders to render into, `{ out, internal }` each; throws when a configured place is unsafe. */
export function plan(publish, { exists = existsSync, list = readdirSync, real = realpathSync.native, platform = process.platform } = {}) {
    const norm = (p) => {   // resolve links through the deepest folder that exists, then add back the tail
        let head = path.resolve(p), tail = '';
        while (!exists(head) && path.dirname(head) !== head) { tail = path.join(path.basename(head), tail); head = path.dirname(head); }
        const abs = path.join(exists(head) ? real(head) : head, tail);
        return platform === 'win32' ? abs.toLowerCase() : abs;
    };
    for (const [ext, int] of [[publish.external, publish.internal], [LOCAL.external, publish.internal]]) {
        if (!ext || !int) continue;
        const rel = path.relative(norm(ext), norm(int));
        const outside = rel === '..' || rel.startsWith('..' + path.sep) || path.isAbsolute(rel);
        if (!outside) {
            throw new Error(`handbook.publish: the internal place ${int} must not be or sit inside the external place ${ext}`);
        }
    }
    const runs = [{ out: LOCAL.external, internal: false }, { out: LOCAL.internal, internal: true }];
    for (const [where, internal] of [[publish.external, false], [publish.internal, true]]) {
        if (!where) continue;
        if (exists(where) && list(where).length && !exists(path.join(where, '.handbook'))) {
            throw new Error(`handbook.publish: ${where} holds files this tool did not write — refused`);
        }
        runs.push({ out: where, internal });
    }
    return runs;
}

function main() {
    if (!existsSync(MANIFEST)) { console.error(`✗ publish-handbook: ${MANIFEST} not found — run from the repository root`); process.exit(2); }
    const book = withEditions(loadManifest(readFileSync(MANIFEST, 'utf8')), editionIndex(gatherFacts('.')));
    let runs;
    try { runs = plan(book.publish); } catch (e) { console.error(`✗ publish-handbook: ${e.message}`); process.exit(1); }
    for (const { out, internal } of runs) {
        const { pages, files } = writeRender(out, book, (f) => readFileSync(f, 'utf8'), { internal });
        console.log(`publish-handbook: ${internal ? 'internal' : 'external'} render, ${pages.length} page(s), ${files.size} file(s) → ${out}`);
    }
    for (const [k, d] of [['external', 'D-UE8'], ['internal', 'D-UE10']]) {
        if (!book.publish[k]) console.log(`publish-handbook: no ${k} place yet (${d} open) — set handbook.publish.${k} in ${MANIFEST}`);
    }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
