#!/usr/bin/env node
// run-handbook-checks — run the Handbook's docs-as-tests steps against a running install (USER-HANDBOOK-1 P2 lane E;
// format: docs/okf/product/layer/product/spec-handbook-doc-tests.md, parser: tools/handbook-checks.mjs).
//
//   node tools/run-handbook-checks.mjs --base-url <url> [--page <id>] [--manifest <file>] [--space <id>]
//        [--var name=value]… [--credentials <file>] [--ui] [--timeout <ms>]
//
// Reads the manifest, takes every page (external and internal) that carries ```check / ```check-ui blocks, and runs
// the blocks of each page in order. A page stops at its first failing step (later steps build on it) and is reported
// with the failing expectation and what the install answered. Exit 0 = every step ran and passed; 1 = a step failed;
// 2 = usage (bad flags, unknown --page, a page with no check, a block that does not parse).
//
// SIGN-IN (never from the page — a page names a persona with `as <name>`, never a credential):
//   a step with `as <name>`: INSPECTO_HANDBOOK_TOKEN_<NAME> (upper case, non-alphanumerics → _), else the credentials
//     file's tokens.<name>, else the Demo Auth exchange (`demo:<name>`, no password — the seed tools' sign-in; Standard
//     and Preview demo builds only). An exchange answered 503 means the install signs in no one (Basic, or the reactor's
//     auth-free core): the step is sent with no Authorization header.
//   a step without `as`: INSPECTO_HANDBOOK_TOKEN, else the credentials file's `token`, else no header.
//   --credentials <file> (or INSPECTO_HANDBOOK_CREDENTIALS): JSON {"token": "...", "tokens": {"analyst": "..."}}, kept
//   outside the repository. A Standard install on a real OIDC provider: put the access tokens there or in the env.
//
// UI steps (```check-ui) run only with --ui, through tools/record-la-live.mjs `episode` (headless Chrome or Edge and
// ffmpeg on the machine); without --ui each is listed as NOT RUN and the summary says how many — never as passed.
//
// Tests: tools/run-handbook-checks.test.mjs (a stub HTTP server in the test). Where it runs: by hand against a demo
// install and before each release — not in CI, which boots no install (the spec page says why and how).

import { spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdtempSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { MANIFEST, loadManifest } from './render-handbook.mjs';
import { extractBlocks, parseBlock, substitute, evalPath, evaluate } from './handbook-checks.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const short = (v) => { const s = typeof v === 'string' ? JSON.stringify(v) : JSON.stringify(v) ?? String(v); return s.length > 200 ? s.slice(0, 200) + '…' : s; };

/** The pages of a manifest that carry check blocks, each with its parsed steps. Throws on a block that does not parse. */
export function checkedPages(book, read, only = null) {
    const pages = book.chapters.flatMap((c) => c.pages).filter((p) => !only || p.id === only);
    const out = [];
    for (const p of pages) {
        const steps = extractBlocks(read(p.file)).map(parseBlock);
        const bad = steps.flatMap((s) => s.errors);
        if (bad.length) throw new Error(`${p.file}:${bad[0].line}: ${bad[0].message} — run node tools/check-handbook.mjs`);
        if (steps.length) out.push({ page: p, steps });
    }
    return out;
}

/** Sign-in per persona; see the header. `fetchFn` posts the Demo Auth exchange. */
export function signIn({ base, env = process.env, creds = {}, fetchFn = fetch }) {
    const cache = new Map();
    let authFree = false;
    return async (as) => {
        if (!as) { const t = env.INSPECTO_HANDBOOK_TOKEN || creds.token; return t ? `Bearer ${t}` : null; }
        const key = `INSPECTO_HANDBOOK_TOKEN_${as.toUpperCase().replace(/[^A-Z0-9]/g, '_')}`;
        const t = env[key] || (creds.tokens && Object.hasOwn(creds.tokens, as) ? creds.tokens[as] : undefined);
        if (t) return `Bearer ${t}`;
        if (authFree) return null;
        if (cache.has(as)) return cache.get(as);
        const r = await fetchFn(`${base}/api/v1/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ code: `demo:${as}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
        if (r.status === 503) { authFree = true; return null; }
        if (!r.ok) throw new Error(`no credential for persona '${as}': set ${key} or the credentials file's tokens.${as}, or run against a Demo Auth build (the demo exchange answered ${r.status})`);
        const j = await r.json();
        const tok = (j && typeof j === 'object' && 'data' in j ? j.data : j)?.accessToken;
        if (!tok) throw new Error(`the demo exchange for '${as}' returned no accessToken`);
        cache.set(as, `Bearer ${tok}`);
        return `Bearer ${tok}`;
    };
}

/** Run one API step. Returns { ok, message } and adds the step's saves to `vars`. */
export async function runApiStep(step, { base, vars, auth, fetchFn = fetch, sleep, timeoutMs = 30000 }) {
    const tries = step.retry?.tries ?? 1;
    let last = null;
    for (let n = 1; n <= tries; n++) {
        if (n > 1) await sleep(step.retry.everyMs);
        let url, body;
        try { url = `${base}/api/v1${substitute(step.path, vars)}`; body = step.body === undefined ? undefined : JSON.stringify(substitute(step.body, vars)); }
        catch (e) { return { ok: false, message: e.message }; }
        let res;
        try {
            const authorization = await auth(step.as);
            res = await fetchFn(url, { method: step.method, signal: AbortSignal.timeout(timeoutMs),
                headers: { accept: 'application/json', ...(body !== undefined ? { 'content-type': 'application/json' } : {}), ...(authorization ? { authorization } : {}) }, body });
        } catch (e) { last = `${step.method} ${url}: ${e.message}`; continue; }
        const text = await res.text();
        let root = text;
        try { root = text ? JSON.parse(text) : null; } catch { /* not JSON: assertions see the text */ }
        const status = step.expects.find((e) => e.kind === 'status');
        const statusOk = status ? status.codes.includes(res.status) : res.status >= 200 && res.status < 300;
        if (!statusOk) { last = `expect status ${status ? status.codes.join('|') : '2xx'} — got ${res.status} ${short(root)}`; continue; }
        const failed = step.expects.filter((e) => e.kind === 'assert').map((e) => ({ e, r: evaluate(e, root) })).find((x) => !x.r.ok);
        if (failed) { last = `expect ${failed.e.text} (line ${failed.e.line}) — got ${short(failed.r.actual)}`; continue; }
        for (const s of step.saves) {
            const v = evalPath(root, s.tokens);
            if (v === undefined || v === null) return { ok: false, message: `save ${s.path} as ${s.name} (line ${s.line}) — ${s.path} is absent` };
            vars[s.name] = typeof v === 'object' ? JSON.stringify(v) : v;
        }
        return { ok: true, message: `${res.status}${n > 1 ? ` after ${n} tries` : ''}` };
    }
    return { ok: false, message: last + (tries > 1 ? ` (after ${tries} tries)` : '') };
}

/** Run one UI step through the LA recorder. Resolves { ok, message }. */
function runUiStep(step, { base }) {
    const dir = mkdtempSync(path.join(tmpdir(), 'handbook-ui-'));
    const cfg = path.join(dir, 'episode.json');
    writeFileSync(cfg, JSON.stringify({ steps: step.steps }));
    return new Promise((resolve) => {
        const p = spawn(process.execPath, [path.join(here, 'record-la-live.mjs'), 'episode', '--base', base, '--config', cfg, '--out', dir], { stdio: ['ignore', 'pipe', 'pipe'] });
        let log = '';
        p.stdout.on('data', (d) => { log += d; });
        p.stderr.on('data', (d) => { log += d; });
        p.on('close', (code) => resolve(code === 0 ? { ok: true, message: 'passed in the console' }
            : { ok: false, message: `the recorder failed (exit ${code}): ${log.trim().split('\n').slice(-3).join(' | ').slice(0, 300)} — screenshots in ${dir}` }));
    });
}

/**
 * Run every checked page. Returns { pages: [{ id, file, ok, ran, notRun, failure }], failed, notRun }. Pure apart from
 * `fetchFn`, `sleep` and (with ui) the recorder, so the tests drive it against a stub server.
 */
export async function runChecks(pages, { base, vars = {}, env = process.env, creds = {}, ui = false, fetchFn = fetch,
    sleep = (ms) => new Promise((r) => setTimeout(r, ms)), timeoutMs = 30000, log = () => {} }) {
    const auth = signIn({ base, env, creds, fetchFn });
    const result = { pages: [], failed: 0, notRun: 0 };
    for (const { page, steps } of pages) {
        const pv = { ...vars };
        const r = { id: page.id, file: page.file, ok: true, ran: 0, notRun: 0, failure: null };
        log(`• ${page.id} (${page.file}) — ${steps.length} step(s)`);
        for (const [i, s] of steps.entries()) {
            const at = `${page.file}:${s.line}`;
            if (s.kind === 'ui' && !ui) { r.notRun++; log(`    – step ${i + 1} ${at} UI: NOT RUN (needs --ui: Chrome or Edge and ffmpeg)`); continue; }
            let out;
            try { out = s.kind === 'ui' ? await runUiStep(s, { base }) : await runApiStep(s, { base, vars: pv, auth, fetchFn, sleep, timeoutMs }); }
            catch (e) { out = { ok: false, message: e.message }; }
            if (!out.ok) { r.ok = false; r.failure = `step ${i + 1} (${at}) ${s.kind === 'ui' ? 'UI' : `${s.method} ${s.path}`}: ${out.message}`; log(`    ✗ ${r.failure}`); break; }
            r.ran++;
            log(`    ✓ step ${i + 1} ${at} ${s.kind === 'ui' ? 'UI' : `${s.method} ${s.path}`} — ${out.message}`);
        }
        if (!r.ok) result.failed++;
        result.notRun += r.notRun;
        result.pages.push(r);
    }
    return result;
}

function parseArgs(argv) {
    const o = { vars: {}, manifest: MANIFEST, ui: false, timeoutMs: 30000 };
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i], v = () => { if (i + 1 >= argv.length) throw new Error(`${a} needs a value`); return argv[++i]; };
        if (a === '--base-url') o.base = v().replace(/\/+$/, '').replace(/\/api\/v1$/, '');
        else if (a === '--page') o.page = v();
        else if (a === '--manifest') o.manifest = v();
        else if (a === '--space') o.vars.space = v();
        else if (a === '--var') { const kv = v(), e = kv.indexOf('='); if (e < 1) throw new Error(`--var name=value, got ${kv}`); o.vars[kv.slice(0, e)] = kv.slice(e + 1); }
        else if (a === '--credentials') o.credentials = v();
        else if (a === '--ui') o.ui = true;
        else if (a === '--timeout') o.timeoutMs = Number(v());
        else throw new Error(`unknown argument ${a}`);
    }
    if (!o.base) throw new Error('--base-url is required');
    if (!(o.timeoutMs > 0)) throw new Error('--timeout takes a positive number of milliseconds');
    return o;
}

async function main() {
    let o;
    try { o = parseArgs(process.argv.slice(2)); } catch (e) {
        console.error(`run-handbook-checks: ${e.message}\nusage: node tools/run-handbook-checks.mjs --base-url <url> [--page <id>] [--manifest <file>] [--space <id>] [--var name=value]… [--credentials <file>] [--ui] [--timeout <ms>]`);
        process.exit(2);
    }
    const credFile = o.credentials || process.env.INSPECTO_HANDBOOK_CREDENTIALS;
    let creds = {}, pages;
    try {
        if (credFile) creds = JSON.parse(readFileSync(credFile, 'utf8'));
        if (!existsSync(o.manifest)) throw new Error(`${o.manifest} not found — run from the repository root or pass --manifest`);
        const book = loadManifest(readFileSync(o.manifest, 'utf8'));
        if (o.page && !book.chapters.some((c) => c.pages.some((p) => p.id === o.page))) throw new Error(`no page '${o.page}' in ${o.manifest}`);
        pages = checkedPages(book, (f) => readFileSync(f, 'utf8'), o.page);
        if (o.page && !pages.length) throw new Error(`page '${o.page}' carries no check block`);
    } catch (e) { console.error(`✗ run-handbook-checks: ${e.message}`); process.exit(2); }
    if (!pages.length) { console.log('run-handbook-checks: no page in the manifest carries a check block yet — nothing to run.'); return; }
    console.log(`run-handbook-checks: ${pages.length} page(s) against ${o.base}${o.ui ? ' (UI steps on)' : ''}`);
    const r = await runChecks(pages, { base: o.base, vars: o.vars, creds, ui: o.ui, timeoutMs: o.timeoutMs, log: (s) => console.log(s) });
    const passed = r.pages.length - r.failed;
    const tail = r.notRun ? `; ${r.notRun} UI step(s) NOT RUN (pass --ui on a machine with Chrome and ffmpeg)` : '';
    if (r.failed) {
        console.error(`✗ run-handbook-checks: ${r.failed} of ${r.pages.length} page(s) failed${tail}\n` + r.pages.filter((p) => !p.ok).map((p) => `  ${p.id}: ${p.failure}`).join('\n'));
        // exitCode, not exit(): exiting with fetch's keep-alive sockets still closing aborts node on Windows (libuv assertion)
        process.exitCode = 1;
        return;
    }
    console.log(`✓ run-handbook-checks: ${passed} page(s) passed${tail}.`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
