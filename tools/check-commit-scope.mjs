#!/usr/bin/env node
// commit-msg convention check (opt-in hook): a feat / fix / perf commit's scope must name a Product Map
// Item (`reconciliation`, `module.reconciliation`, …) or an allowed general scope, so
// tools/release-notes.mjs can attribute it. ADVISORY: always exits 0, prints a warning when it would not.
//
//   node tools/check-commit-scope.mjs <msg-file>
//
// Enable locally (never installed into .git by the repo):
//   printf '#!/bin/sh\nnode tools/check-commit-scope.mjs "$1"\n' > .git/hooks/commit-msg   (or core.hooksPath)
import { readFileSync } from 'node:fs';
import { index, loadWorld, parseSubject } from './release-notes.mjs';

/** Scopes that are not items but are legitimate for feat/fix/perf. */
export const GENERAL = ['ci', 'deps', 'docs', 'tools', 'release', 'build', 'guards', 'skills', 'deploy', 'package'];

/** → warning text, or null when the message is fine. */
export function checkMessage(msg, idx) {
    const subject = msg.split(/\r?\n/).find((l) => l.trim() && !l.startsWith('#')) ?? '';
    const s = parseSubject(subject.trim());
    if (!['feat', 'fix', 'perf'].includes(s.type)) return null;
    if (!s.scope) return `commit-scope: '${s.type}' has no scope — use a Product Map Item name, e.g. ${s.type}(reconciliation): …`;
    const bad = s.scope.split(/[,/ ]+/).filter(Boolean).filter((x) => !GENERAL.includes(x) && !idx.scope(x).length);
    return bad.length ? `commit-scope: scope '${bad.join(', ')}' is not a Product Map Item (product-map/) or a general scope (${GENERAL.join(', ')})` : null;
}

function main() {
    const file = process.argv[2];
    if (!file) { console.error('usage: node tools/check-commit-scope.mjs <msg-file>'); return; }
    try {
        const w = checkMessage(readFileSync(file, 'utf8'), index(loadWorld('.')));
        if (w) console.error(`⚠ ${w}\n  (advisory — commit kept; rule: .claude/skills/release-workflow/SKILL.md)`);
    } catch (e) { console.error(`check-commit-scope: skipped (${e.message})`); }
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
