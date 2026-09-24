#!/usr/bin/env node
/**
 * Seed-path guard: every inbox, reference file and working directory `tools/seed-samples.mjs` would write
 * must land INSIDE the Space it belongs to (`spaces/<id>/…`), which is where the engine reads a relative
 * `dirs.*` value (`PathJail.resolveDataPath`, DATA-DIRS-RESOLVE-AGAINST-CWD-1).
 *
 * WHY. The seeder used to resolve `dirs.poll` against the repo root. On a fresh checkout every inbox went to
 * `<repo>/data/inbox/<pipeline>`, and the engine then refused EVERY Pipeline ("nothing exists there, while
 * <cwd>/data/inbox/X does") — the shared tree hid it only because stale inboxes already sat under
 * `spaces/<id>/data/`. Nothing checked the seeder's paths against the engine's rule, so the two drifted.
 *
 * It runs the seeder with `--dry-run` (writes nothing) and reads the plan back. Scope: every space the
 * seeder visits; a config whose `dirs:` block the seeder's narrow reader cannot parse is outside it.
 */
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = execFileSync(process.execPath, [join(REPO, 'tools', 'seed-samples.mjs'), '--all', '--dry-run'], {
    cwd: REPO,
    encoding: 'utf8',
});

const planned = [];
for (const line of out.split(/\r?\n/)) {
    const m = line.match(/^ {2}(?:d | {2})(\S.*)$/);
    if (m) planned.push(m[1].replace(/\\/g, '/'));
}

if (planned.length === 0) {
    console.error('✗ Seed-path guard: the seeder planned NOTHING — its --dry-run output format has changed; fix this parser.');
    process.exit(1);
}

const outside = planned.filter((p) => !/^spaces\/[^/]+\/./.test(p));
if (outside.length) {
    console.error(
        `✗ Seed-path guard: ${outside.length} of ${planned.length} planned path(s) fall OUTSIDE their Space, where the ` +
            `engine never reads a relative dirs.* value (it resolves under spaces/<id>/):`,
    );
    for (const p of outside.slice(0, 20)) console.error(`    ${p}`);
    console.error('  Resolve dirs.* the way PathJail.resolveDataPath does (tools/seed-samples.mjs dataPath).');
    process.exit(1);
}

console.log(
    `✓ Seed-path guard: all ${planned.length} planned path(s) (files + directories) land inside their Space — ` +
        `scope: seed-samples.mjs --all --dry-run.`,
);
