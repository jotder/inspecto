#!/usr/bin/env node
// Which Handbook pages a bundle's /help may carry (TRAINING-BUNDLE-1; decision D-UE7 in
// docs/superpower/2026-11-19-user-enablement-and-agent-kit-plan.md §9).
//
// THE RULE. A sold edition (Basic, Standard, Advanced — production or trial) ships the EXTERNAL render: `audience:
// external` pages only. The Preview edition, and any demo build (`-Distribution demo`: Demo Users, no customer, never
// sold), ship the TRAINING render: the external pages plus the `audience: training` ones (the Labs, the instructor kit).
// No bundle ever carries an `audience: internal` page. `handbookAudience()` below is the only place the rule lives:
// inspecto/package.ps1 asks it which render to make.
//
//   node tools/check-bundle-handbook.mjs --audience --edition <E> --distribution <D>   prints `external` or `training`
//   node tools/check-bundle-handbook.mjs --bundle <help-dir> --edition <E> --distribution <D>
//        judges a rendered /help folder (package.ps1 runs this on the folder it is about to zip): its `.handbook` marker
//        names the expected render, every training page is there exactly when the rule says so, no internal page is
//   node tools/check-bundle-handbook.mjs                                                 (CI)
//        pins package.ps1's render block to this rule, then RUNS that block under pwsh (required) for a sold edition and
//        the Preview into temp folders and judges each one: a sold edition's render holds no training page, the Preview's holds every one, and each fails the
//        other flavor's verdict (so the check can tell them apart).
//
// Tests: tools/check-bundle-handbook.test.mjs. tools/check-handbook.mjs keeps its own leak rule (the external render
// holds no internal or training page) — this guard is about which render a bundle gets.

import { readFileSync, readdirSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { execFileSync } from 'node:child_process';
import { MANIFEST, loadManifest } from './render-handbook.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PACKAGE_PS1 = join(ROOT, 'inspecto', 'package.ps1');

/** The render a bundle of this edition and Distribution gets: 'training' (Preview, or a demo build) or 'external'. */
export function handbookAudience({ edition, distribution }) {
    return edition === 'Preview' || distribution === 'demo' ? 'training' : 'external';
}

/**
 * Judge a rendered /help folder. `marker` is its `.handbook` text, `files` the names in it, `book` the loaded manifest.
 * Returns { ok: true } or { ok: false, reason }.
 */
export function bundleHandbookVerdict({ marker, files }, book, flavor) {
    const want = handbookAudience(flavor);
    const pages = book.chapters.flatMap((c) => c.pages);
    const has = new Set(files);
    const ids = (aud) => pages.filter((p) => p.audience === aud).map((p) => p.id);
    const problems = [];
    if ((marker || '').trim() !== want) problems.push(`its .handbook marker says '${(marker || '').trim() || 'nothing'}', expected '${want}'`);
    const internal = ids('internal').filter((id) => has.has(`${id}.html`));
    if (internal.length) problems.push(`it carries internal page(s) ${internal.join(', ')}`);
    const training = ids('training');
    if (want === 'training') {
        const missing = training.filter((id) => !has.has(`${id}.html`));
        if (missing.length) problems.push(`it lacks training page(s) ${missing.join(', ')}`);
    } else {
        const leaked = training.filter((id) => has.has(`${id}.html`));
        if (leaked.length) problems.push(`it carries training page(s) ${leaked.join(', ')}, which a sold edition never ships (D-UE7)`);
    }
    const label = `${flavor.edition} x ${flavor.distribution}`;
    return problems.length ? { ok: false, reason: `${label} /help: ${problems.join('; ')}.` } : { ok: true };
}

const readBundle = (dir) => ({
    marker: existsSync(join(dir, '.handbook')) ? readFileSync(join(dir, '.handbook'), 'utf8') : '',
    files: readdirSync(dir),
});
const loadBook = () => loadManifest(readFileSync(join(ROOT, MANIFEST), 'utf8'));

function main(argv) {
    const fail = (msg) => { console.error(`✗ Bundle-handbook guard: ${msg}`); process.exit(1); };
    const opt = (name) => (argv.includes(name) ? argv[argv.indexOf(name) + 1] : null);
    const flavor = { edition: opt('--edition'), distribution: opt('--distribution') || 'production' };

    if (argv.includes('--audience')) {
        if (!flavor.edition) fail('--audience needs --edition.');
        console.log(handbookAudience(flavor));
        return;
    }
    if (argv.includes('--bundle')) {
        const dir = opt('--bundle');
        if (!dir || !flavor.edition) fail('--bundle needs a directory and --edition.');
        const v = bundleHandbookVerdict(readBundle(dir), loadBook(), flavor);
        if (!v.ok) fail(v.reason);
        console.log(`✓ Bundle-handbook guard: ${dir} holds the ${handbookAudience(flavor)} render (allowed for ${flavor.edition} x ${flavor.distribution}).`);
        return;
    }

    // ── static: package.ps1 takes the render from this rule and judges what it wrote ───────────────────────────
    const pkg = readFileSync(PACKAGE_PS1, 'utf8');
    const renders = pkg.match(/render-handbook\.mjs'\)[^\r\n]*/g) || [];
    if (renders.length !== 1) fail(`inspecto/package.ps1 calls render-handbook.mjs ${renders.length} time(s); expected exactly once.`);
    if (!/--out \$helpOut @helpArgs\s*$/.test(renders[0])) fail(`inspecto/package.ps1's render call is \`${renders[0].trim()}\`; expected \`--out $helpOut @helpArgs\`.`);
    if (/--internal/.test(pkg.replace(/^\s*#.*$/gm, ''))) fail('inspecto/package.ps1 passes --internal somewhere — internal pages must never reach a bundle.');
    if (!/\$helpAudience = & node \(Join-Path \$sandboxRoot 'tools\\check-bundle-handbook\.mjs'\) --audience --edition \$Edition --distribution \$Distribution/.test(pkg))
        fail('inspecto/package.ps1 no longer asks check-bundle-handbook.mjs --audience which render to make.');
    // @(...) around the `if`: without it a one-element array unrolls to a string and @helpArgs splats its characters
    // (the first real Preview package run rendered the external Handbook that way).
    if (!/\$helpArgs = @\(if \(\$helpAudience -eq 'training'\) \{ '--training' \}\)/.test(pkg))
        fail("inspecto/package.ps1 no longer derives the render flag from the rule's answer alone.");
    if (!/check-bundle-handbook\.mjs'\) --bundle \$helpOut --edition \$Edition --distribution \$Distribution/.test(pkg))
        fail('inspecto/package.ps1 no longer judges the rendered /help folder with check-bundle-handbook.mjs --bundle.');

    // ── dynamic: RUN package.ps1's own render block under pwsh for a sold edition and the Preview, judge each ──────
    // Reading the block is not enough: the first real Preview package run rendered the external Handbook through a
    // PowerShell splatting trap that the text looked fine for.
    const book = loadBook();
    const trainingIds = book.chapters.flatMap((c) => c.pages).filter((p) => p.audience === 'training').map((p) => p.id);
    if (!trainingIds.length) fail('the manifest has no training page — nothing for this guard to prove.');
    const lines = pkg.split(/\r?\n/);
    const from = lines.findIndex((l) => /^\s*\$helpOut = Join-Path \$uiOut 'help'\s*$/.test(l));
    const to = lines.findIndex((l, i) => i > from && /Handbook rendered \(\$helpAudience\)/.test(l));
    if (from < 0 || to < 0) fail("inspecto/package.ps1's /help render block ($helpOut = Join-Path $uiOut 'help' ... Handbook rendered) is gone.");
    const snippet = lines.slice(from, to + 1).join('\n');
    const sold = { edition: 'Advanced', distribution: 'production' };
    const preview = { edition: 'Preview', distribution: 'production' };
    const work = mkdtempSync(join(tmpdir(), 'inspecto-bundle-handbook-'));
    const q = (s) => "'" + s.replace(/'/g, "''") + "'";
    try {
        const dirs = {};
        for (const [name, flavor] of [['sold', sold], ['preview', preview]]) {
            const uiOut = join(work, name);
            dirs[name] = join(uiOut, 'help');
            const script = `$ErrorActionPreference='Stop'; Set-Location ${q(ROOT)}; $sandboxRoot=${q(ROOT)}; $uiOut=${q(uiOut)}; ` +
                `$Edition=${q(flavor.edition)}; $Distribution=${q(flavor.distribution)}\n${snippet}`;
            try {
                execFileSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', script], { encoding: 'utf8', stdio: 'pipe' });
            } catch (e) {
                if (e.code === 'ENOENT') { console.error('✗ Bundle-handbook guard could not run: pwsh (PowerShell 7) is required and was not found on PATH.'); process.exit(2); }
                fail(`package.ps1's render block failed for ${flavor.edition} x ${flavor.distribution}:\n${e.stdout ?? ''}${e.stderr ?? ''}`);
            }
        }
        for (const [name, flavor] of [['sold', sold], ['preview', preview]]) {
            const v = bundleHandbookVerdict(readBundle(dirs[name]), book, flavor);
            if (!v.ok) fail(v.reason);
        }
        if (bundleHandbookVerdict(readBundle(dirs.preview), book, sold).ok) fail('the Preview render passed as a sold edition’s — the verdict cannot tell them apart.');
        if (bundleHandbookVerdict(readBundle(dirs.sold), book, preview).ok) fail('the sold-edition render passed as the Preview’s — the verdict cannot tell them apart.');
    } finally {
        rmSync(work, { recursive: true, force: true });
    }
    console.log(`✓ Bundle-handbook guard: package.ps1 renders /help by the rule; a sold edition's render holds none of the ` +
        `${trainingIds.length} training page(s) (${trainingIds.join(', ')}), the Preview's holds all of them.`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main(process.argv.slice(2));
