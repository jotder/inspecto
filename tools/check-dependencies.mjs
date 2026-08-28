#!/usr/bin/env node
// Dependency-review guard (compliance plan C4 / matrix gap G7 — "offline: a pinned-versions diff
// check in CI, not a scanner SaaS").
//
// WHAT IT IS: a lockfile diff over the reactor's RESOLVED runtime dependency graph. It compares
// `mvn dependency:list` against the committed tools/dependencies.lock and fails when they differ.
// A change to the dependency graph then cannot land without the lock moving in the SAME commit,
// which is what makes it reviewable — the diff shows a human exactly what arrived, left, or moved.
//
// ⛔ WHAT IT IS NOT: a vulnerability scanner. It cannot tell you a version is vulnerable, only that
// nobody looked. Do not describe it to an auditor as vulnerability management (matrix G7 vs the
// separate advisory-watch process). Claiming more than a control does is how an audit finding is
// born.
//
// WHY A LOCK AT ALL, when Maven already pins versions in the parent POM: a transitive dependency
// can appear, vanish or change version without any POM in this repo changing a line — a sibling
// SNAPSHOT bumping its own dependencies is enough. The POMs pin what we ASK for; this pins what we
// actually GET.
//
// ⚠ SNAPSHOT entries are only weakly pinned BY CONSTRUCTION. `com.eoiagent:*` and `com.gamma.asn:*`
// are sibling-repo SNAPSHOTs: their CONTENT can change while the version string does not, so this
// guard cannot see it. It still catches one appearing, disappearing, or changing version — say that
// plainly rather than implying a SNAPSHOT is pinned.
//
// Zero dependencies (pure Node), like tools/check-vocabulary.mjs and tools/check-secrets.mjs.
//
//   node tools/check-dependencies.mjs            # verify (CI)
//   node tools/check-dependencies.mjs --update   # rewrite the lock after a REVIEWED change
//
// Env: MVN_CMD overrides the Maven binary; MVN_OFFLINE=1 adds `-o` (the local house rule — CI runs
// online because its cache starts cold).

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const LOCK = join(repoRoot, 'tools', 'dependencies.lock');
const update = process.argv.includes('--update');

// The reactor's OWN modules are EXCLUDED. Their version is a project version, so every release bump
// would rewrite every line for zero supply-chain signal — the churn that gets a lock ignored. They
// are in-repo code, not third-party; nothing about them is un-reviewed.
//
// ⚠ DERIVED from the same run, never hand-listed. A literal groupId constant looks obviously right
// and is obviously wrong the moment the reactor gains a module under a new group: this repo builds
// BOTH `com.gamma.inspector:*` and `com.gamma.asn:*` (asn-parser/), and the one-group constant this
// replaced silently treated seven in-repo artifacts as third-party.
const MODULE_BANNER = /^\[INFO\] --- \S+ \([^)]*\) @ ([\w.-]+) ---/;

// `dependency:list` prints one block per reactor module; a dependency shared by several modules
// appears several times, and two modules may legitimately resolve DIFFERENT versions of one
// artifact (this reactor has several such pairs today — jackson, commons-lang3, slf4j, postgresql).
// Deduping to a sorted SET keeps both versions visible rather than silently picking one.
const ENTRY = /^\[INFO\] {4,}([a-zA-Z][\w.-]*:[\w.-]+:[a-z]+:[^:\s]+:(?:compile|runtime))/;

function mvn() {
    if (process.env.MVN_CMD) return process.env.MVN_CMD;
    return process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
}

function resolve() {
    const args = ['-B', 'dependency:list', '-DincludeScope=runtime'];
    if (process.env.MVN_OFFLINE === '1') args.unshift('-o');
    let out;
    try {
        // `shell` on Windows: Maven ships as `mvn.cmd`, and Node refuses to execFile a .cmd
        // directly (the CVE-2024-27980 fix). Without this the guard dies before Maven ever runs and
        // reports it as a build failure — a wrong diagnosis, which is worse than no guard.
        out = execFileSync(mvn(), args, {
            cwd: repoRoot,
            encoding: 'utf8',
            maxBuffer: 64 * 1024 * 1024,
            shell: process.platform === 'win32',
        });
    } catch (err) {
        // A build failure here is NOT a dependency finding — reporting it as one would send a
        // reviewer hunting a diff that does not exist. Exit 2 (distinct from a real drift's 1).
        console.error('\n✖ Dependency guard: CANNOT RUN — `mvn dependency:list` failed.\n');
        const detail = String(err.stdout ?? '').split('\n').filter((l) => /ERROR/.test(l)).slice(0, 15).join('\n');
        console.error(detail || String(err.message ?? err));
        console.error('\nResolve the build first; this guard reports drift, not build breakage.\n');
        process.exit(2);
    }
    // A run that resolves NOTHING must never read as "no drift". Same failure the vocabulary guard
    // was blindfolded by: an empty scan exiting 0.
    const blocks = (out.match(/The following files have been resolved/g) ?? []).length;
    const lines = out.split('\n');
    const reactor = new Set();
    for (const line of lines) {
        const m = MODULE_BANNER.exec(line.trimEnd());
        if (m) reactor.add(m[1]);
    }
    const found = new Set();
    for (const line of lines) {
        const m = ENTRY.exec(line.trimEnd());
        if (m && !reactor.has(m[1].split(':')[1])) found.add(m[1]);
    }
    if (blocks === 0 || found.size === 0) {
        console.error('\n✖ Dependency guard: CANNOT RUN — `dependency:list` produced no resolved artifacts.');
        console.error(`  (${blocks} module block(s), ${found.size} entries). A green result here would mean`);
        console.error('  only that the guard read nothing. Check the Maven invocation.\n');
        process.exit(2);
    }
    return { entries: [...found].sort(), blocks };
}

function readLock() {
    if (!existsSync(LOCK)) return null;
    return readFileSync(LOCK, 'utf8')
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith('#'))
        .sort();
}

function writeLock(entries, blocks) {
    const header = [
        '# Resolved runtime dependency graph — the committed baseline for tools/check-dependencies.mjs.',
        '#',
        '# Regenerate with `node tools/check-dependencies.mjs --update`, and commit the result IN THE',
        '# SAME COMMIT as the change that moved it. That pairing is the whole control: the diff is what',
        '# a reviewer reads.',
        '#',
        "# Scope: every reactor module, runtime scope. The reactor's OWN modules are excluded, and that",
        '# set is DERIVED from the build rather than hand-listed (this repo builds two groups:',
        '# com.gamma.inspector and com.gamma.asn).',
        '# Format: groupId:artifactId:type:version:scope, sorted, deduped.',
        '#',
        '# ⚠ Two versions of one artifact is NOT an error here — different modules resolve differently,',
        '#   and both lines are kept so the split stays visible instead of being silently collapsed.',
        '# ⚠ SNAPSHOT entries are weakly pinned by construction: content can move under a fixed version.',
        `#`,
        `# ${entries.length} third-party artifacts across ${blocks} reactor modules.`,
        '',
    ].join('\n');
    writeFileSync(LOCK, header + entries.join('\n') + '\n');
}

const { entries, blocks } = resolve();

if (update) {
    writeLock(entries, blocks);
    console.log(`✓ Dependency lock updated: ${entries.length} third-party artifacts across ${blocks} modules.`);
    console.log('  Commit tools/dependencies.lock together with the change that moved it.');
    process.exit(0);
}

const locked = readLock();
if (locked === null) {
    console.error('\n✖ Dependency guard: tools/dependencies.lock is missing.');
    console.error('  Create it with `node tools/check-dependencies.mjs --update`.\n');
    process.exit(2);
}

const lockedSet = new Set(locked);
const foundSet = new Set(entries);
const added = entries.filter((e) => !lockedSet.has(e));
const removed = locked.filter((e) => !foundSet.has(e));

if (added.length === 0 && removed.length === 0) {
    console.log(`✓ Dependency guard: ${entries.length} third-party artifacts across ${blocks} reactor modules — unchanged.`);
    process.exit(0);
}

// An added+removed pair on the same groupId:artifactId is a VERSION CHANGE, not two events. Saying
// so is the difference between a reviewer seeing "commons-text moved 1.11.0 → 1.12.0" and seeing
// two unrelated lines they have to correlate by eye.
const key = (e) => e.split(':').slice(0, 2).join(':');
const removedBy = new Map(removed.map((e) => [key(e), e]));
const changed = [];
for (const a of added) {
    const r = removedBy.get(key(a));
    if (r) {
        changed.push([r, a]);
        removedBy.delete(key(a));
    }
}
const changedKeys = new Set(changed.map(([, a]) => key(a)));
const purelyAdded = added.filter((e) => !changedKeys.has(key(e)));
const purelyRemoved = removed.filter((e) => !changedKeys.has(key(e)));

console.error('\n✖ Dependency guard: the resolved dependency graph does not match tools/dependencies.lock.\n');
for (const [from, to] of changed) console.error(`  ~ CHANGED  ${key(to)}\n      ${from.split(':')[3]} → ${to.split(':')[3]}`);
for (const e of purelyAdded) console.error(`  + ADDED    ${e}`);
for (const e of purelyRemoved) console.error(`  - REMOVED  ${e}`);
console.error('\nIf this change is intended and reviewed, run:\n');
console.error('  node tools/check-dependencies.mjs --update\n');
console.error('and commit tools/dependencies.lock in the SAME commit, so the diff is reviewed with the');
console.error('change that caused it. If it is NOT intended, a dependency moved without anyone asking —');
console.error('which is exactly what this guard exists to surface.\n');
process.exit(1);
