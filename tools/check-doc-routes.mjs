#!/usr/bin/env node
/**
 * Doc-route guard — every `/api/v1/...` route path a CURRENT doc mentions must be a route the product serves.
 *
 * WHY THIS EXISTS. The link, citation, vocabulary and count guards prove a doc's paths, words and numbers; none
 * of them sees `curl …/api/v1/runs/{id}/retry` in a guide. A route that was renamed or never built 404s only when
 * an operator follows the doc.
 *
 * ── CHECKS ────────────────────────────────────────────────────────────────────────────────────────
 *
 * Every `/api/v1/<path>` in a current doc (fenced blocks INCLUDED — a curl example is the claim) passes when:
 *
 *   A. **KNOWN** — it matches a path of `docs/api/openapi-v1.json` (its server url is `/api/v1`), or a route
 *      registered in code as found by `tools/route-gating-report.mjs`'s scanner (`api.get("/x", …)` — one
 *      scanner, not two). Placeholders are normalised: `{id}`, `:id`, `<id>` in the doc, `{id}` in the
 *      contract and a regex group `([^/]+)` in code all mean "one segment"; a concrete id (`/runs/r-42`) matches
 *      a placeholder segment. A trailing `*`, `...` or `…` makes the mention a PREFIX: it passes when some known
 *      route starts with it.
 *      A `/spaces/<id>/<rest>` mention is ALSO tried as `/<rest>` — `ControlApi.bindSpace` strips that prefix
 *      before dispatch, so `/api/v1/spaces/acme/pipelines` is the `/pipelines` route.
 *   B. **MARKED** — the same line states the route is planned, absent or removed (ABSENCE_MARKER of
 *      `check-doc-flags.mjs`, one vocabulary for both guards).
 *
 * ── SCOPE (printed on every run — a silent scope is an exemption) ─────────────────────────────────
 *
 *   - Sources: `docs/**\/*.md` and `compliance/**\/*.md`, MINUS `docs/archived-documents/` and `docs/superpower/`.
 *   - Only the `/api/v1/` spelling is checked. A bare `GET /runs` in prose is not — it is indistinguishable from
 *     a file path or a UI route, and a guard matching it would be mostly exemption.
 *   - The METHOD is not checked: docs rarely put it next to the path in a parseable way.
 *   - A mention made only of placeholders (`/api/v1/<path>`, a grammar line) is a TEMPLATE, counted, not checked.
 *   - A query string and `#fragment` are dropped; the path is what is checked.
 *
 * Pure Node, no dependencies. Run by `.github/workflows/ci.yml` (guards job).
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ABSENCE_MARKER, currentDocs } from './check-doc-flags.mjs';
import { scan } from './route-gating-report.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

/** `/api/v1` then path characters, including placeholder brackets and a trailing wildcard. */
const ROUTE = /\/api\/v1((?:\/[\w{}:<>.~$*…-]*)*)/g;

/** ControlApi.bindSpace's per-space seam (multi-space): the prefix is stripped before route matching. */
const SPACE_PREFIX = /^\/spaces\/[^/]+(?=\/)/;

const MIN_FILES = 150;
const MIN_ROUTES = 50;

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** A contract path `/runs/{id}` → an anchored regex. */
const fromContract = (p) => new RegExp('^' + p.split(/\{[^}]*\}/).map(escape).join('[^/]+') + '$');

/** A code pattern is already a regex (`/runs/([^/]+)`); a pattern that fails to compile is taken literally. */
function fromCode(p) {
    try { return new RegExp('^(?:' + p + ')$'); } catch { return new RegExp('^' + escape(p) + '$'); }
}

/** Canonical one-segment form, for prefix comparison: every placeholder or group becomes `{}`. */
const shape = (p) => p.replace(/\(\?[!=][^()]*\)/g, '').replace(/\{[^}]*\}/g, '{}')
    .replace(/\((?:[^()]|\([^()]*\))*\)/g, '{}');

/** Doc path → { path (concrete probe), prefix (bool), shaped }. Null for the bare `/api/v1` root. */
export function normalise(raw) {
    let p = raw.replace(/[.,;:)]+$/, '');
    let prefix = false;
    if (/(?:\*|\.\.\.|…)$/.test(p)) { prefix = true; p = p.replace(/\/?(?:\*+|\.\.\.|…)$/, ''); }
    p = p.replace(/\/+$/, '');
    if (!p) return null;
    const segs = p.split('/').map((s) => (/^(?:\{[^}]*\}|:\w+|<[^>]*>)$/.test(s) ? '\u0000' : s));
    return { path: segs.join('/').replaceAll('\u0000', 'P4R4M'), prefix, shaped: segs.join('/').replaceAll('\u0000', '{}') };
}

/** world: { docs: {path: text}, contract: [path], code: [pattern] } → { failures, counts }. */
export function check({ docs, contract, code }) {
    const matchers = [...contract.map(fromContract), ...code.map(fromCode)];
    const shapes = [...contract, ...code].map(shape);
    const counts = { contract: 0, code: 0, prefix: 0, marked: 0, template: 0, total: 0 };
    const nContract = contract.length;
    const failures = [];
    for (const [file, text] of Object.entries(docs)) {
        text.split('\n').forEach((line, i) => {
            for (const m of line.matchAll(ROUTE)) {
                const n = normalise(m[1]);
                if (!n) continue;
                // ControlApi.bindSpace: `/spaces/{id}/<rest>` is dispatched as `/<rest>`.
                const probe = n.path.replace(SPACE_PREFIX, '');
                if (/^(?:\/P4R4M)+$/.test(probe)) { counts.template++; continue; }
                counts.total++;
                const hit = matchers.findIndex((re) => re.test(n.path) || (probe !== n.path && re.test(probe)));
                if (hit >= 0) counts[hit < nContract ? 'contract' : 'code']++;
                else if (n.prefix && shapes.some((s) => s.startsWith(n.shaped))) counts.prefix++;
                else if (ABSENCE_MARKER.test(line)) counts.marked++;
                else failures.push(`${file}:${i + 1}  /api/v1${m[1]}`);
            }
        });
    }
    return { failures, counts };
}

function main() {
    const read = (p) => readFileSync(join(ROOT, p), 'utf8');
    const docs = Object.fromEntries(currentDocs().map((p) => [p, read(p)]));
    const contract = Object.keys(JSON.parse(read('docs/api/openapi-v1.json')).paths);
    const code = [...new Set(scan(ROOT).map((r) => r.pattern))];
    const { failures, counts } = check({ docs, contract, code });

    console.log(`Doc-route guard — scope: ${Object.keys(docs).length} current docs (docs/**, compliance/**; ` +
        `archived-documents/ and superpower/ excluded); only the /api/v1/ spelling, path only (method not checked); ` +
        `known routes: ${contract.length} OpenAPI paths + ${code.length} code registrations.`);
    console.log(`  ${counts.total} /api/v1 mentions: ${counts.contract} in the contract, ${counts.code} registered ` +
        `in code only, ${counts.prefix} prefix (…/*) mentions, ${counts.marked} marked planned/absent on their line; ` +
        `${counts.template} all-placeholder templates not checked.`);
    if (Object.keys(docs).length < MIN_FILES || counts.total < MIN_ROUTES || contract.length < 100) {
        console.error(`✗ Doc-route guard: scope below floor (files ≥ ${MIN_FILES}, mentions ≥ ${MIN_ROUTES}, ` +
            'contract paths ≥ 100) — ran over nothing?');
        process.exit(1);
    }
    if (failures.length) {
        console.error(`✗ Doc-route guard: ${failures.length} /api/v1 route(s) neither the contract nor the code ` +
            'serves. Correct the route from the code, or state on the line that it is planned/absent:');
        for (const f of failures) console.error(`  ${f}`);
        process.exit(1);
    }
    console.log('✓ Doc-route guard: every /api/v1 route in the current docs is served, or marked.');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === join(process.argv[1])) main();
