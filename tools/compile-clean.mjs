#!/usr/bin/env node
// compile-clean — the PROOF that a code cut / move / shared-interface change really compiles.
//
//   node tools/compile-clean.mjs [-Pedition-enterprise] [more mvn args…]      (default profile: edition-enterprise)
//
// Runs `mvn -o clean test-compile -DskipTests -B -fae`, ALWAYS with `clean`, then reads the log instead of trusting
// the exit code. Exit 0 only when: BUILD SUCCESS, a Reactor Summary exists with at least one module, no module is
// FAILURE or SKIPPED, no `[ERROR] …java` line, and every module actually compiled.
//
// Why this exists (D-1, 2026-10-01): after `ApiContext` lost three methods, a plain `mvn compile -pl inspecto -am`
// went green in 9 s with exit 0 — the downstream modules (`-geo-link`, `-ops`, …) were never recompiled, so their stale
// classes still matched the OLD interface. Only `clean` forces the dependants to be seen. A closure script
// (tools/java-closure.mjs) predicts a cut; this command proves it.
//
// Needs JDK 27 on JAVA_HOME (`C:/Program Files/Java/latest/jdk-27`) and the offline Maven repo.

import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Parse a Maven log into a verdict. Pure, so tools/compile-clean.test.mjs can feed it fixture logs. */
export function parseMavenLog(text) {
    const lines = text.split(/\r?\n/);
    const modules = [];
    let inSummary = false;
    for (const l of lines) {
        if (/Reactor Summary/.test(l)) { inSummary = true; continue; }
        if (!inSummary) continue;
        if (/^\[INFO\]\s+BUILD\s+(SUCCESS|FAILURE)/.test(l)) break;   // the verdict line is not a module
        const m = /^\[INFO\]\s+(.+?)\s+\.{2,}\s*(?:|[\d.]+\s*)?(SUCCESS|FAILURE|SKIPPED)\b/.exec(l)
            ?? /^\[INFO\]\s+(.+?)\s+(SUCCESS|FAILURE|SKIPPED)\b/.exec(l);
        if (m) modules.push({ name: m[1].replace(/\s+\d[\w.-]*-SNAPSHOT$/, '').trim(), status: m[2] });
    }
    const errors = [];
    const seenErr = new Set();   // Maven prints every compile error twice (inline, then again in the failure summary)
    const errRe = /^\[ERROR\]\s+\/?([A-Za-z]:)?[^\s]*?([\w.-]+)\/src\/(main|test)\/java\/.*?([\w$]+\.java):\[(\d+),(\d+)\]\s*(.*)$/;
    for (const l of lines) {
        const m = errRe.exec(l);
        if (!m) continue;
        const key = `${m[2]}|${m[3]}|${m[4]}|${m[5]}|${m[6]}|${m[7]}`;
        if (seenErr.has(key)) continue;
        seenErr.add(key);
        errors.push({ module: m[2], source: m[3], file: m[4], line: Number(m[5]), message: m[7] });
    }
    const buildSuccess = lines.some((l) => /BUILD SUCCESS/.test(l));
    const buildFailure = lines.some((l) => /BUILD FAILURE/.test(l));
    const failed = modules.filter((m) => m.status === 'FAILURE').map((m) => m.name);
    const skipped = modules.filter((m) => m.status === 'SKIPPED').map((m) => m.name);
    const problems = [];
    if (!buildSuccess) problems.push(buildFailure ? 'BUILD FAILURE' : 'no "BUILD SUCCESS" line (the build did not finish)');
    if (modules.length === 0) problems.push('no Reactor Summary — nothing was built (a non-verdict, not a pass)');
    if (failed.length) problems.push(`${failed.length} module(s) FAILED: ${failed.join(', ')}`);
    if (skipped.length) problems.push(`${skipped.length} module(s) SKIPPED (an upstream failed): ${skipped.join(', ')}`);
    if (errors.length) problems.push(`${errors.length} compile error(s)`);
    const stale = lines.filter((l) => /Nothing to compile - all classes are up to date/.test(l)).length;
    if (stale) problems.push(`${stale} module(s) reported "Nothing to compile" — the build was NOT clean, so stale classes could be hiding a break`);
    return { ok: problems.length === 0, modules, errors, failed, skipped, problems };
}

export function summarise(v) {
    const out = [];
    out.push(`${v.modules.length} reactor module(s): ${v.modules.length - v.failed.length - v.skipped.length} SUCCESS, ${v.failed.length} FAILURE, ${v.skipped.length} SKIPPED`);
    const byModule = new Map();
    for (const e of v.errors) {
        const k = `${e.module} (${e.source})`;
        if (!byModule.has(k)) byModule.set(k, new Map());
        const files = byModule.get(k);
        files.set(e.file, (files.get(e.file) ?? 0) + 1);
    }
    for (const [mod, files] of byModule) {
        out.push(`  ✖ ${mod}: ${[...files].map(([f, n]) => `${f}×${n}`).join(', ')}`);
    }
    if (v.errors.length) out.push(`  first: ${v.errors[0].file}:${v.errors[0].line} ${v.errors[0].message}`);
    out.push(v.ok ? 'PASS — a clean compile of the whole reactor' : `FAIL — ${v.problems.join('; ')}`);
    return out.join('\n');
}

function main() {
    const extra = process.argv.slice(2);
    const hasProfile = extra.some((a) => a.startsWith('-P'));
    const args = ['-o', 'clean', 'test-compile', '-DskipTests', '-B', '-fae', ...(hasProfile ? [] : ['-Pedition-enterprise']), ...extra];
    console.log(`mvn ${args.join(' ')}   (JAVA_HOME=${process.env.JAVA_HOME ?? '<unset — mvn uses PATH java>'})`);
    const r = spawnSync('mvn', args, { encoding: 'utf8', shell: true, maxBuffer: 1 << 28 });
    const log = `${r.stdout ?? ''}\n${r.stderr ?? ''}`;
    writeFileSync('compile-clean.log', log);        // *.log is gitignored
    const v = parseMavenLog(log);
    console.log(summarise(v));
    console.log('log: compile-clean.log');
    process.exit(v.ok ? 0 : 1);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
