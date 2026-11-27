#!/usr/bin/env node
/**
 * Signal Type name guard (SIGNAL-BOARD-1 SB-1a, 2026-11-27).
 *
 * WHY THIS EXISTS. Every Signal Type is declared, with its payload fields, by a `SignalTypeProvider`
 * (`com.gamma.signal.CoreSignalTypes` for core, one per module beside its constants class). The post-time check
 * (`-Dsignals.checkFields=true`) only sees the Signals a test happens to post; a dotted name typed straight into the
 * posting line can still reach production unseen. This guard closes that: a literal Signal type in main code must be
 * declared by some provider.
 *
 * WHAT IT CHECKS, over every tracked `src/main/java` file (comments stripped):
 *   1. A dotted string literal passed straight as the type of `new Signal(id, "<type>", ...)`,
 *      `emit("<type>", Severity.X, ...)` or `emitSignal("<type>", ...)` is declared by a provider: named in a provider
 *      file as a literal, or as a `Class.CONSTANT` whose value is that literal. A `prefix.*` family covers
 *      `prefix.<anything>`.
 *   2. Each PENDING entry (a literal not declared yet) still occurs in its file and is still undeclared:
 *      a stale entry is a silent exemption.
 *
 * Usage: node tools/check-signal-types.mjs [--root <dir>]   (--root is for tools/check-signal-types.test.mjs).
 */

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Literals allowed although no provider declares them yet: type -> { file, why }. Keep it empty: every module declares
 *  its own types in its provider (SIGNAL-BOARD-1 SB-1b); an entry is a temporary, reasoned exemption. */
export const PENDING = {};

const DOTTED = /^[a-z0-9][a-z0-9_-]*(\.[a-z0-9][a-z0-9_-]*)+$/;
const FAMILY = /^[a-z0-9][a-z0-9_-]*(\.[a-z0-9][a-z0-9_-]*)*\.\*$/;
const SITES = [
    // new [com.gamma.signal.]Signal(<id>, "<type>": the id may hold one level of brackets, e.g. UUID.randomUUID().toString()
    /\bnew\s+(?:[\w.]+\.)?Signal\s*\(\s*(?:[^,()"]|\([^()"]*\))*,\s*"([^"\\\n]*)"/g,
    // SignalEmitter.emit(type, severity, payload): the Severity second argument tells it from other emit methods
    /\bemit\s*\(\s*"([^"\\\n]*)"\s*,\s*(?:[\w.]*\.)?Severity\./g,
    /\bemitSignal\s*\(\s*"([^"\\\n]*)"/g,
];
const CONSTANT = /\b(?:static\s+final|final\s+static)\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"\\\n]*)"/g;
const PROVIDER = /\bimplements\s+(?:[\w.]+\s*,\s*)*(?:com\.gamma\.signal\.)?SignalTypeProvider\b/;

/** Comments blanked (line breaks kept, so line numbers hold), string literals kept. */
export function stripComments(text) {
    return text.replace(/"(?:\\.|[^"\\\n])*"|\/\*[\s\S]*?\*\/|\/\/[^\n]*/g,
        (m) => (m.startsWith('"') ? m : m.replace(/[^\n]/g, ' ')));
}

const simpleName = (f) => f.slice(f.lastIndexOf('/') + 1, -'.java'.length);

export function check(root) {
    const files = execFileSync('git', ['ls-files', '-z', '--', '*.java'], { cwd: root, encoding: 'utf8' })
        .split('\0').filter((f) => f && /(^|\/)src\/main\/java\//.test(f));
    const code = new Map(files.map((f) => [f, stripComments(readFileSync(join(root, f), 'utf8'))]));

    // Class.CONSTANT -> value, for every String constant in main code
    const constants = new Map();
    for (const [f, text] of code)
        for (const m of text.matchAll(CONSTANT)) constants.set(`${simpleName(f)}.${m[1]}`, m[2]);

    // what the providers declare
    const declared = new Set();
    const families = [];
    for (const text of code.values()) {
        if (!PROVIDER.test(text)) continue;
        const names = [...text.matchAll(/"([^"\\\n]*)"/g)].map((m) => m[1]);
        for (const m of text.matchAll(/\b([A-Z]\w*)\.([A-Z][A-Z0-9_]*)\b/g)) {
            const v = constants.get(`${m[1]}.${m[2]}`);
            if (v !== undefined) names.push(v);
        }
        for (const n of names) {
            if (DOTTED.test(n)) declared.add(n);
            else if (FAMILY.test(n)) families.push(n.slice(0, -1));   // keep the trailing dot
        }
    }
    const isDeclared = (t) => declared.has(t) || families.some((p) => t.startsWith(p) && t.length > p.length);

    const problems = [];
    const pendingSeen = new Set();
    for (const [f, text] of code) {
        for (const re of SITES) {
            for (const m of text.matchAll(re)) {
                const type = m[1];
                if (!DOTTED.test(type) || isDeclared(type)) continue;
                if (PENDING[type] && PENDING[type].file === f) { pendingSeen.add(type); continue; }
                const line = text.slice(0, m.index).split('\n').length;
                problems.push(`${f}:${line}: Signal type '${type}' is posted as a literal but no SignalTypeProvider declares it`
                    + ' - declare it (with its payload fields) in the provider of this module, or in CoreSignalTypes for core');
            }
        }
    }
    for (const [type, p] of Object.entries(PENDING)) {
        if (isDeclared(type)) problems.push(`PENDING '${type}' is declared now - remove it from PENDING in tools/check-signal-types.mjs`);
        else if (!pendingSeen.has(type)) problems.push(`PENDING '${type}' is no longer posted as a literal in ${p.file} - remove the entry`);
    }
    return problems;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    const at = process.argv.indexOf('--root');
    const root = at > 0 ? resolve(process.argv[at + 1]) : join(dirname(fileURLToPath(import.meta.url)), '..');
    const problems = check(root);
    if (problems.length) {
        for (const p of problems) console.error(`✗ signal-type guard: ${p}`);
        process.exit(1);
    }
    console.log(`✓ signal-type guard: every literal Signal type in main code is declared by a provider (${Object.keys(PENDING).length} pending)`);
}
