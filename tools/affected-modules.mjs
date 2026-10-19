#!/usr/bin/env node
// affected-modules — decides the SMALLEST build/test that verifies a change (AFFECTED-BUILD-1, protocol in
// docs/okf/product/layer/platform/spec-build-test.md "Affected-module protocol").
//
//   node tools/affected-modules.mjs [<base>] [<head>] [--json] [--full]
//     base default: merge-base with origin/master (falls back to HEAD~1); head default: working tree (incl. uncommitted)
//
// Decides one of three modes:
//   none      — nothing a Java test can see changed (docs, guards, product map …): run the node guards only
//   affected  — build the changed Maven modules' upstream WITHOUT tests, then test the changed modules AND every module
//               that depends on them (transitively, test-jar deps included) — never only `-pl X -am` (false green downstream)
//   full      — the whole reactor: root/aggregator pom, .mvn/, an unclassified path (fail-safe), or the closure is most of the reactor
// plus `ui: true` when inspecto-ui/ changed (UI lane: lint, typecheck, test, build).
//
// A file OUTSIDE src/ can still feed a Java test: tests read repo files such as docs/api/openapi-v1.json or spaces/…
// Those consumers are DISCOVERED (string literals in src/test that name a tracked path), never hand-listed.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { posix } from 'node:path';

const git = (...a) => execFileSync('git', a, { encoding: 'utf8', maxBuffer: 1 << 28 }).trim();
export const FULL_THRESHOLD = 0.7;

/** Paths that no Java build or test reads unless a test literally names them (then the consumer scan wins). */
const NO_BUILD = [/^docs\//, /^\.claude\//, /^product-map\//, /^offerings\//, /^compliance\//, /^tools\/(?!templates\/)/,
    /^\.github\//, /^\.githooks\//, /\.md$/i, /^\.gitignore$/, /^\.gitattributes$/, /^\.claudeignore$/, /^\.mcp\.json$/,
    /^CLAUDE\.md$/, /^dev-infra\//, /^spaces\//, /^tools\/templates\//, /\.xlsx$/i, /^warehouse_[\w]+\.sql$/, /^test-run\.ps1$/];
/** Paths that matter for PACKAGING (bundle smoke), not for unit tests. */
const PACKAGE = [/^inspecto\/package\.ps1$/, /^deploy\//, /^scripts\//, /^inspecto\/(launchers|bundle)\//];

/** Reactor model from the tracked poms: { dir → { artifactId, deps:Set<artifactId>, aggregator } }. */
export function readReactor(pomFiles, read) {
    const mods = {};
    for (const f of pomFiles) {
        const dir = posix.dirname(f);
        if (dir.startsWith('tools/templates')) continue;
        const x = read(f).replace(/<!--[\s\S]*?-->/g, '');
        const body = x.replace(/<parent>[\s\S]*?<\/parent>/, '');
        const artifactId = /<artifactId>([^<]+)<\/artifactId>/.exec(body)?.[1];
        const deps = new Set([...body.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)]
            .map((m) => /<artifactId>([^<]+)<\/artifactId>/.exec(m[1])?.[1]).filter(Boolean));
        mods[dir] = { dir, artifactId, deps, aggregator: /<packaging>\s*pom\s*<\/packaging>/.test(x) };
    }
    const ids = new Set(Object.values(mods).map((m) => m.artifactId));
    for (const m of Object.values(mods)) m.deps = new Set([...m.deps].filter((d) => ids.has(d) && d !== m.artifactId));
    return mods;
}

/** Transitive dependents of a set of artifactIds (including themselves). */
export function dependentsClosure(mods, start) {
    const out = new Set(start);
    let grew = true;
    while (grew) {
        grew = false;
        for (const m of Object.values(mods)) {
            if (m.aggregator || out.has(m.artifactId)) continue;
            if ([...m.deps].some((d) => out.has(d))) { out.add(m.artifactId); grew = true; }
        }
    }
    return out;
}

/** The module owning a path = the deepest reactor dir that prefixes it. */
export function owner(mods, path) {
    let best;
    for (const dir of Object.keys(mods)) if (dir !== '.' && (path === dir || path.startsWith(dir + '/')) && (!best || dir.length > best.length)) best = dir;
    return best;
}

/** { trackedPrefix → Set<moduleDir> } from repo-relative string literals inside src/test of each module. */
export function discoverConsumers(mods, testFiles, read, isTracked) {
    const out = new Map();
    for (const f of testFiles) {
        const dir = owner(mods, f);
        if (!dir) continue;
        for (const m of read(f).matchAll(/"((?:\.\.\/)*(?:[\w.-]+\/)+[\w.-]*)"/g)) {
            const p = m[1].replace(/^(\.\.\/)+/, '').replace(/\/$/, '');
            if (!p.includes('/') || p.startsWith(dir + '/') || !isTracked(p)) continue;
            if (!out.has(p)) out.set(p, new Set());
            out.get(p).add(dir);
        }
    }
    return out;
}

/** Pure decision. changed: repo-relative paths. Returns the plan. */
export function decide({ mods, changed, consumers, forceFull = false, pomChanged = () => true }) {
    // start: modules whose CODE changed (their dependents are tested too); readers: modules whose tests READ a changed
    // file (only their own tests run — a reader's dependents do not consume that file)
    const reasons = [], start = new Set(), readers = new Set(), notes = new Set();
    let full = forceFull, ui = false, pkg = false;
    if (forceFull) reasons.push('--full requested');
    const real = Object.values(mods).filter((m) => !m.aggregator && m.dir !== '.');
    for (const p of changed) {
        // readers first: a test that READS this file sees even a comment edit
        let consumed = false;
        for (const [prefix, ds] of consumers) if (p === prefix || p.startsWith(prefix + '/')) { consumed = true; for (const d of ds) { readers.add(mods[d].artifactId); notes.add(`${p} → read by ${d} tests`); } }
        if ((p.endsWith('pom.xml') || p.endsWith('.java')) && !pomChanged(p)) { notes.add(`${p}: comments/whitespace only — not compiled differently`); continue; }
        if (p === 'pom.xml' || p.startsWith('.mvn/')) { full = true; reasons.push(`${p}: reactor-wide build config`); continue; }
        const dir = owner(mods, p);
        if (dir && mods[dir].aggregator && p === `${dir}/pom.xml`) { full = true; reasons.push(`${p}: aggregator pom`); continue; }
        // a module pom that changed beyond comments may move the RESOLVED dependency graph: the lockfile, SBOM and
        // lean-core guards resolve the whole reactor, so this is one of the unavoidable full runs
        if (p.endsWith('/pom.xml')) { full = true; reasons.push(`${p}: dependency graph may change (lock/SBOM guards need the full reactor)`); continue; }
        if (dir && !mods[dir].aggregator) {
            if (/\.md$/i.test(p) && !p.includes('/src/')) continue;
            start.add(mods[dir].artifactId);
            continue;
        }
        if (p.startsWith('inspecto-ui/')) { ui = true; continue; }
        if (PACKAGE.some((r) => r.test(p))) { pkg = true; continue; }
        if (consumed || NO_BUILD.some((r) => r.test(p))) continue;
        if (dir && mods[dir].aggregator) { full = true; reasons.push(`${p}: inside aggregator ${dir} but no module`); continue; }
        full = true; reasons.push(`${p}: not classified — fail-safe full`);
    }
    const closure = dependentsClosure(mods, start);
    for (const r of readers) closure.add(r);
    if (!full && closure.size > FULL_THRESHOLD * real.length) { full = true; reasons.push(`closure ${closure.size}/${real.length} modules > ${FULL_THRESHOLD * 100}%`); }
    const mode = full ? 'full' : closure.size ? 'affected' : 'none';
    const pl = [...closure].sort().map((a) => `:${a}`).join(',');
    const commands = mode === 'full'
        ? ['mvn -B install -Pedition-advanced']
        : mode === 'affected'
            ? [`mvn -B install -DskipTests -Pedition-advanced -pl ${pl} -am`, `mvn -B test -Pedition-advanced -pl ${pl}`]
            : [];
    return { mode, reasons, changedModules: [...start].sort(), testModules: mode === 'full' ? real.map((m) => m.artifactId).sort() : [...closure].sort(),
        reactorSize: real.length, ui, package: pkg, notes: [...notes].sort(), commands };
}

function main() {
    const args = process.argv.slice(2), json = args.includes('--json'), forceFull = args.includes('--full');
    const refs = args.filter((a) => !a.startsWith('--'));
    let base = refs[0];
    if (!base) { try { base = git('merge-base', 'HEAD', 'origin/master'); } catch { base = 'HEAD~1'; } }
    const head = refs[1];
    const changed = new Set(git('diff', '--name-only', base, ...(head ? [head] : [])).split('\n').filter(Boolean));
    const strip = (x) => x.replace(/<!--[\s\S]*?-->/g, '').replace(/\s+/g, ' ').trim();
    // a file added or deleted in the range has no blob on one side: that side reads as empty
    const show = (ref, f) => { try { return execFileSync('git', ['show', `${ref}:${f}`], { encoding: 'utf8', maxBuffer: 1 << 28, stdio: ['ignore', 'pipe', 'ignore'] }); } catch { return ''; } };
    // comment-only edits (doc-path rewrites, wording) cannot change a build result; strings are kept intact
    const stripJava = (x) => x.replace(/("(?:[^"\\\n]|\\.)*")|\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (m, str) => str ?? '').replace(/\s+/g, ' ').trim();
    const now = (f) => (head ? show(head, f) : (existsSync(f) ? readFileSync(f, 'utf8') : ''));
    const pomChanged = (f) => (f.endsWith('.java') ? stripJava(show(base, f)) !== stripJava(now(f)) : strip(show(base, f)) !== strip(now(f)));
    const untracked = [];
    if (!head) for (const l of git('status', '--porcelain', '--untracked-files=all').split('\n').filter(Boolean)) {
        const p = l.slice(3).replace(/^.* -> /, '').replace(/^"|"$/g, '');
        if (l.startsWith('??')) untracked.push(p); else changed.add(p);
    }
    const tracked = git('ls-files').split('\n');
    const trackedSet = new Set(tracked);
    const dirs = new Set();
    for (const t of tracked) for (let d = posix.dirname(t); d !== '.'; d = posix.dirname(d)) dirs.add(d);
    const read = (f) => (existsSync(f) ? readFileSync(f, 'utf8') : '');
    const mods = readReactor(tracked.filter((f) => f === 'pom.xml' || f.endsWith('/pom.xml')), read);
    // an untracked file counts only as NEW SOURCE inside a module; scratch, target/ output and runtime state are not changes
    for (const p of untracked) if (owner(mods, p) && /\/src\//.test(p)) changed.add(p);
    const consumers = discoverConsumers(mods, tracked.filter((f) => /\/src\/test\/.*\.java$/.test(f)), read, (p) => trackedSet.has(p) || dirs.has(p));
    const plan = decide({ mods, changed: [...changed], consumers, forceFull, pomChanged });
    plan.base = base; plan.head = head ?? 'WORKTREE'; plan.changedFiles = changed.size;
    if (json) { console.log(JSON.stringify(plan, null, 2)); return; }
    console.log(`affected-modules: ${plan.changedFiles} changed file(s) ${base.slice(0, 9)}..${plan.head} → mode ${plan.mode.toUpperCase()}`);
    for (const r of plan.reasons) console.log(`  why full: ${r}`);
    if (plan.mode === 'affected') {
        console.log(`  changed modules (${plan.changedModules.length}): ${plan.changedModules.join(', ')}`);
        console.log(`  test modules incl. dependents (${plan.testModules.length}/${plan.reactorSize}): ${plan.testModules.join(', ')}`);
    }
    for (const n of plan.notes) console.log(`  ${n}`);
    if (plan.ui) console.log('  UI changed → npm run lint && npm run typecheck && npx ng test && npm run build (in inspecto-ui/)');
    if (plan.package) console.log('  packaging inputs changed → pwsh inspecto/package.ps1 smoke for one edition');
    if (plan.mode === 'none') console.log('  no Java module affected → node guards only');
    for (const c of plan.commands) console.log(`  $ ${c}`);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) main();
