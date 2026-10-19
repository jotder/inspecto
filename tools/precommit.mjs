#!/usr/bin/env node
// precommit — runs EVERY no-build guard of the CI `guards` job before a commit, so nobody (human or agent) has to
// remember them. Called by .githooks/pre-commit; runnable by hand: `node tools/precommit.mjs [--dry-run]`.
//
// The command list is READ FROM .github/workflows/ci.yml (the `guards` job's `run: node …` steps) at run time —
// never a hand-kept subset, which would drift (a reviewed subset of 17 missed ~20 of the 36 guards a staged path
// could break). Commands run in parallel from the repo root (~10–20 s). Steps that need CI-only inputs
// ($RUNNER_TEMP, network fetches) are skipped and named. Java changes also print the affected-module test plan.
//
// ⚠ The guards read the WORKING TREE, not the index. When tracked files have unstaged edits (a peer's, in this
// shared checkout) the hook names them: those edits were judged too, and a pass may not describe the commit.

import { execFileSync, spawn, spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const CI_ONLY = /RUNNER_TEMP|GITHUB_|fetch-duckdb-extensions/;
export const CONCURRENCY = 6;

/** The guards job's `run: node …` commands, in file order. Pure over the workflow text. */
export function guardCommands(ciYml) {
    const lines = ciYml.split(/\r?\n/);
    const start = lines.findIndex((l) => /^ {2}guards:\s*$/.test(l));
    if (start < 0) throw new Error('ci.yml has no `guards:` job');
    let end = lines.findIndex((l, i) => i > start && /^ {2}[\w-]+:\s*$/.test(l));
    if (end < 0) end = lines.length;
    const run = [], skipped = [];
    for (const l of lines.slice(start, end)) {
        const m = /^\s+run:\s+(node\s.+)$/.exec(l);
        if (!m) continue;
        (CI_ONLY.test(m[1]) ? skipped : run).push(m[1].trim());
    }
    return { run, skipped };
}

/**
 * The environment a guard step runs in: the hook's, MINUS git's repository variables. In a worktree git hands the
 * hook GIT_DIR / GIT_INDEX_FILE / GIT_WORK_TREE; a step whose fixture runs `git init` / `git config` in a temp dir
 * then wrote into the REAL repository (2026-10-19: core.bare=true + a fixture identity in the shared .git/config —
 * every git command in the shared checkout failed). Steps rediscover the repo from their cwd instead.
 */
export function stepEnv(env) {
    return Object.fromEntries(Object.entries(env).filter(([k]) => !/^GIT_/i.test(k) || /^GIT_(SSH|ASKPASS|TERMINAL_PROMPT|CONFIG_NOSYSTEM)/i.test(k)));
}

function exec(cmd, cwd) {
    return new Promise((resolve) => {
        const t0 = Date.now();
        const c = spawn(cmd, { cwd, shell: true, windowsHide: true, env: stepEnv(process.env) });
        let out = '';
        c.stdout.on('data', (d) => (out += d));
        c.stderr.on('data', (d) => (out += d));
        c.on('close', (code) => resolve({ cmd, ok: code === 0, out, secs: (Date.now() - t0) / 1000 }));
    });
}

async function pool(cmds, cwd, n) {
    const results = [];
    let next = 0;
    await Promise.all(Array.from({ length: n }, async () => {
        while (next < cmds.length) { const i = next++; results[i] = await exec(cmds[i], cwd); }
    }));
    return results;
}

async function main() {
    const dry = process.argv.includes('--dry-run');
    const git = (...a) => execFileSync('git', a, { encoding: 'utf8' }).trim();
    const root = git('rev-parse', '--show-toplevel');
    // deletions count: removing a linked doc or a test breaks a guard as surely as editing one
    const staged = git('-C', root, 'diff', '--cached', '--name-only').split('\n').filter(Boolean);
    if (!staged.length) { console.log('precommit: nothing staged'); return; }
    const { run, skipped } = guardCommands(readFileSync(`${root}/.github/workflows/ci.yml`, 'utf8'));
    console.log(`precommit: ${staged.length} staged file(s) → all ${run.length} CI guard step(s), ${CONCURRENCY} at a time`
        + (skipped.length ? ` (skipped, CI-only inputs: ${skipped.length})` : ''));
    const unstaged = git('-C', root, 'diff', '--name-only').split('\n').filter(Boolean);
    if (unstaged.length) console.log(`  ⚠ ${unstaged.length} tracked file(s) have UNSTAGED edits — the guards judge them too: ${unstaged.slice(0, 8).join(', ')}${unstaged.length > 8 ? ' …' : ''}`);
    if (dry) { for (const c of run) console.log(`    ${c}`); return; }
    const t0 = Date.now();
    // steps that run `node --test` may write fixtures INTO the checkout (gen-la-showcase rewrites
    // spaces/_templates/…/data): run those one at a time after the read-only guards, never beside a reader
    const writers = run.filter((c) => /--test\b/.test(c)), readers = run.filter((c) => !/--test\b/.test(c));
    const results = [...await pool(readers, root, CONCURRENCY), ...await pool(writers, root, 1)];
    const failed = results.filter((r) => !r.ok);
    for (const r of failed) {
        console.log(`  ✗ ${r.cmd.slice(0, 110)} (${r.secs.toFixed(1)} s)`);
        console.log(r.out.split('\n').filter((l) => /✗|✖|stale|not ok|Error|does not|fail/i.test(l)).slice(0, 10).map((l) => `      ${l.trim()}`).join('\n'));
    }
    console.log(`  ${run.length - failed.length}/${run.length} guard step(s) passed in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
    if (staged.some((f) => /\.java$|pom\.xml$/.test(f))) {
        const r = spawnSync(process.execPath, ['tools/affected-modules.mjs'], { cwd: root, encoding: 'utf8', env: stepEnv(process.env) });
        console.log(`  ℹ Java changed — test plan (run it before pushing):\n${r.stdout.split('\n').filter(Boolean).map((l) => `      ${l}`).join('\n')}`);
    }
    if (failed.length) {
        console.log(`precommit: ${failed.length} step(s) failed — fix them (a stale generated file: run that tool without --check), then commit again.`);
        process.exit(1);
    }
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
