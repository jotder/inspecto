#!/usr/bin/env node
// Supply-chain denylist guard: fails when package-lock.json resolves a package version we have ruled out.
//
// Zero dependencies (pure Node). Run via `npm run lint:denylist`; wired into the UI CI workflow right after
// `npm ci`. Add an entry the same way as the first one: name, the exact bad versions, and WHY (with a source).
//
// ⚠ The first entries come from a reported 2026 maintainer-account compromise of the @antv namespace
// (credential-stealing payload in the install step). They were NOT verified against an advisory database when
// added — confirm against OSV / GHSA and put the advisory id in `why`. None of these packages is a dependency
// today, so the guard is insurance against a future `npm install` pulling one in.
//
// DENYLIST_LOCK lets tools/check-npm-denylist.test.mjs aim the guard at a throwaway lockfile.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

export const DENYLIST = [
    { name: '@antv/g6-plugins', versions: ['1.1.9', '1.2.9'], why: 'reported @antv maintainer-account compromise (2026)' },
    { name: '@antv/g6-plugin-map-view', versions: ['0.1.4', '0.2.4'], why: 'reported @antv maintainer-account compromise (2026)' },
];

/** Every `{name, version}` the lockfile resolves, including nested copies. */
export function resolved(lock) {
    return Object.entries(lock.packages ?? {})
        .filter(([path]) => path.includes('node_modules/'))
        .map(([path, p]) => ({ name: path.split('node_modules/').pop(), version: p.version }));
}

export function violations(lock, denylist = DENYLIST) {
    const found = resolved(lock);
    return denylist.flatMap((d) =>
        found.filter((f) => f.name === d.name && d.versions.includes(f.version)).map((f) => ({ ...f, why: d.why })),
    );
}

const here = dirname(fileURLToPath(import.meta.url));
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const lockPath = process.env.DENYLIST_LOCK ?? join(here, '..', 'package-lock.json');
    const bad = violations(JSON.parse(readFileSync(lockPath, 'utf8')));
    if (bad.length) {
        console.error('✗ Supply-chain denylist: package-lock.json resolves a denied package version:');
        for (const b of bad) console.error(`  ${b.name}@${b.version} — ${b.why}`);
        process.exit(1);
    }
    console.log(`✓ Supply-chain denylist: none of ${DENYLIST.length} denied packages is resolved in package-lock.json.`);
}
