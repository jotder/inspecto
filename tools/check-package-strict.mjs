#!/usr/bin/env node
// package.ps1 runs under `Set-StrictMode -Version Latest`: READING a variable that no earlier assignment in an
// enclosing block set THROWS, and only at the bundling step of the edition that skipped the assigning branch.
// (2026-10-08: the P7 module splits added reads of variables assigned only in the non-Personal branch, so
// `package.ps1 -Edition Personal` died - and had already been dying since entity-list/LA, before the series.)
// This walks package.ps1 with the PowerShell AST (tools/check-package-strict.ps1) and fails on every such read.
// Usage: node tools/check-package-strict.mjs [path-to-script.ps1]      (needs pwsh; no build)
import { spawnSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

/** @returns {{ok: boolean, unset: string[], raw: string}} */
export function analyze(script) {
    const r = spawnSync('pwsh', ['-NoProfile', '-File', join(HERE, 'check-package-strict.ps1'), script], { encoding: 'utf8' });
    if (r.error) throw new Error(`pwsh is required: ${r.error.message}`);
    const raw = `${r.stdout}${r.stderr}`;
    if (r.status === 2) throw new Error(`cannot parse ${script}: ${raw}`);
    const unset = raw.split(/\r?\n/).filter(l => l.startsWith('UNSET-READ'));
    return { ok: r.status === 0 && unset.length === 0, unset, raw };
}

if (process.argv[1]?.endsWith('check-package-strict.mjs')) {
    const target = process.argv[2] ?? join(HERE, '..', 'inspecto', 'package.ps1');
    const { ok, unset, raw } = analyze(target);
    if (!ok) {
        console.error(`check-package-strict: ${unset.length} variable(s) read before any dominating assignment in ${target}:\n${unset.join('\n')}\n` +
            'Initialise each = $null next to the existing *JarSrc pre-initialisation block (strict mode throws otherwise).');
        if (!unset.length) console.error(raw);
        process.exit(1);
    }
    console.log('check-package-strict: OK - no variable is read before it is assigned');
}
