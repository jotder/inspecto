#!/usr/bin/env node
// render-error-catalogue — the error catalogue (ERROR-CATALOGUE-1; plan docs/superpower/2026-11-20-agent-kit-ak2-plan.md §1.3).
//
//   node tools/render-error-catalogue.mjs --write    rewrite the two generated files
//   node tools/render-error-catalogue.mjs --check    exit 1 when a file is stale, a code has no entry, or a refusal
//                                                     names a code the catalogue does not know (CI, pre-commit)
//
// Every refusal the control plane answers carries a stable code, in one of three places:
//   envelope  error.errorCode of every v1 error body          — com.gamma.spi.auth.ErrorCodes (+ the few route-local codes)
//   finding   findings[].code of a config save / validate     — com.gamma.config.spec.FindingCodes (+ ColumnRefusals, …)
//             (on a refused save: error.details.findings[].code)
//   graph     findings[].code of a Pipeline graph validate    — com.gamma.pipeline.PipelineValidator (+ PipelineGraphRoutes)
//   lowering  refusals[].code of a Pipeline graph save        — com.gamma.pipeline.PipelineEditable (REFUSAL-CODES-1)
// The code SET and each envelope code's HTTP status come from the Java source (the constant, ErrorCodes.defaultFor and
// a "NNN —" Javadoc lead); the cause and the fix an agent or a person needs come from tools/error-catalogue-entries.json,
// keyed by code. Outputs, each a pure function of those inputs:
//   docs/api/errors-v1.json                                  machine-readable; served as MCP Resource inspecto://errors (W5)
//   docs/okf/product/layer/platform/api-error-catalogue.md   the rendered page
// --check also sweeps every src/main/java file: an ERR_/WARN_ constant outside the sources below, or a
// `new ApiException(status, CODE, …)` whose CODE the catalogue does not list, fails — so a new refusal code cannot ship
// without its entry. Tests: tools/render-error-catalogue.test.mjs.

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(fileURLToPath(import.meta.url), '..', '..');
export const ENTRIES = 'tools/error-catalogue-entries.json';
export const OUT_JSON = 'docs/api/errors-v1.json';
export const OUT_PAGE = 'docs/okf/product/layer/platform/api-error-catalogue.md';
const SELF = 'node tools/render-error-catalogue.mjs';

/** Where codes are defined. `pick` filters the constant names a file contributes (null = every String constant). */
export const SOURCES = [
    { family: 'envelope', file: 'spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/ErrorCodes.java', pick: null },
    { family: 'envelope', file: 'providers/inspecto-demo-auth/src/main/java/com/gamma/demoauth/DemoUsers.java', pick: ['ROSTER_CONFLICT'] },
    { family: 'envelope', file: 'inspecto/src/main/java/com/gamma/service/SpaceLocations.java', pick: ['DISABLED', 'HOLDS_DATA'] },
    { family: 'finding', file: 'platform/inspecto-config/src/main/java/com/gamma/config/spec/FindingCodes.java', pick: null },
    { family: 'finding', file: 'inspecto/src/main/java/com/gamma/control/ColumnRefusals.java', pick: ['ERR_COLUMN_BREAKS'] },
    { family: 'finding', file: 'inspecto/src/main/java/com/gamma/service/ColumnContract.java', pick: ['WARN_COLUMN_BREAKS'] },
    { family: 'finding', file: 'inspecto/src/main/java/com/gamma/control/LinkRefusals.java', pick: ['ERR_LINK_DANGLING', 'WARN_LINK_DANGLING'] },
    { family: 'graph', file: 'platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineValidator.java', pick: 'screaming' },
    { family: 'graph', file: 'inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java', pick: ['UNKNOWN_JOIN_REFERENCE'] },
    { family: 'lowering', file: 'platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java', pick: 'screaming' },
];

/** Codes written as string literals at their only use, not as constants. The sweep finds a new one and fails. */
export const LITERALS = [
    { family: 'finding', code: 'COMPONENT_INVALID', file: 'inspecto/src/main/java/com/gamma/control/ComponentRoutes.java' },
    { family: 'finding', code: 'UNKNOWN_KEY', file: 'inspecto/src/main/java/com/gamma/control/ComponentRoutes.java' },
];

export const FAMILIES = {
    envelope: { title: 'Error codes (every v1 error)', where: 'error.errorCode' },
    finding: { title: 'Config finding codes', where: 'findings[].code — on a refused save error.details.findings[].code' },
    graph: { title: 'Pipeline graph codes', where: 'findings[].code of a Pipeline graph validate or save' },
    lowering: { title: 'Pipeline save refusals', where: 'error.details.refusals[].code of a refused PUT /pipelines/{id}/graph (refusals[].code of its validate)' },
};

const CONST = /(\/\*\*((?:(?!\*\/)[\s\S])*)\*\/\s*)?(?:public\s+|private\s+|protected\s+)?static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"\s*;/g;

/** A Javadoc body as one plain line: leading stars gone, {@code x} → `x`, {@link A#b} → `b`, tags dropped. */
export function plainJavadoc(raw) {
    if (!raw) return '';
    return raw.split('\n').map((l) => l.replace(/^\s*\*\s?/, '')).join(' ')
        .replace(/\{@(?:code|literal)\s+([^}]*)\}/g, '`$1`')
        .replace(/\{@link(?:plain)?\s+([^}\s]*)(?:\s+([^}]*))?\}/g, (_, ref, label) => '`' + (label || ref.replace(/^.*#/, '').replace(/\(.*\)$/, '')) + '`')
        .replace(/<\/?[a-z]+>/g, '').replace(/\s+/g, ' ').trim();
}

/** The String constants of one Java source: [{name, value, doc}]. */
export function constantsOf(java) {
    const out = [];
    for (const m of java.matchAll(CONST)) out.push({ name: m[3], value: m[4], doc: plainJavadoc(m[2]) });
    return out;
}

/** ErrorCodes.defaultFor's switch: constant name → status. */
export function defaultStatuses(java) {
    const out = {};
    for (const m of java.matchAll(/case\s+(\d{3})\s*->\s*([A-Z_]+)\s*;/g)) out[m[2]] ??= Number(m[1]);
    return out;
}

const picked = (src, c) => src.pick === null ? true
    : src.pick === 'screaming' ? c.name === c.value
    : src.pick.includes(c.name);

/** The catalogue's code list from the sources and the authored entries. Throws listing every problem. */
export function build(read, entries) {
    const problems = [];
    const codes = new Map();
    for (const src of SOURCES) {
        const java = read(src.file);
        const statuses = src.family === 'envelope' ? defaultStatuses(java) : {};
        const consts = constantsOf(java).filter((c) => picked(src, c));
        if (src.pick && src.pick !== 'screaming')
            for (const n of src.pick) if (!consts.some((c) => c.name === n)) problems.push(`${src.file}: constant ${n} not found`);
        for (const c of consts) {
            const prior = codes.get(c.value);
            if (prior) { prior.definedIn.push(src.file); continue; }
            const lead = /^(\d{3})\s*[—-]\s*/.exec(c.doc);
            codes.set(c.value, {
                code: c.value, family: src.family, constant: c.name, definedIn: [src.file],
                status: statuses[c.name] ?? (lead ? Number(lead[1]) : null),
                doc: lead ? c.doc.slice(lead[0].length) : c.doc,
            });
        }
    }
    for (const l of LITERALS) {
        if (!read(l.file).includes(`"${l.code}"`)) problems.push(`${l.file}: literal "${l.code}" not found — update LITERALS`);
        if (codes.has(l.code)) codes.get(l.code).definedIn.push(l.file);
        else codes.set(l.code, { code: l.code, family: l.family, constant: null, definedIn: [l.file], status: null, doc: '' });
    }
    const list = [...codes.values()];
    for (const e of list) {
        const a = entries[e.code];
        if (!a) { problems.push(`${e.code} (${e.definedIn[0]}) has no entry in ${ENTRIES} — add its cause and fix`); continue; }
        if (a.status !== undefined) e.status = a.status;
        if (e.family === 'finding') e.severity = a.severity ?? (e.code.startsWith('WARN_') ? 'WARNING' : 'ERROR');
        if (e.family === 'finding' && e.status === null) e.status = e.severity === 'ERROR' ? 422 : null;
        e.cause = a.cause;
        e.fix = a.fix;
        if (!e.cause) problems.push(`${e.code}: no cause in ${ENTRIES}`);
        if (!e.fix) problems.push(`${e.code}: no fix in ${ENTRIES}`);
        if (e.family === 'envelope' && e.status === null) problems.push(`${e.code}: no HTTP status (defaultFor, a "NNN —" Javadoc lead, or "status" in ${ENTRIES})`);
    }
    for (const k of Object.keys(entries)) if (!k.startsWith('$') && !codes.has(k)) problems.push(`${ENTRIES}: ${k} is no longer a code — remove its entry`);
    if (problems.length) throw new Error(problems.join('\n'));
    const order = Object.keys(FAMILIES);
    return list.sort((x, y) => order.indexOf(x.family) - order.indexOf(y.family)
        || (x.family === 'envelope' ? x.status - y.status : 0) || x.code.localeCompare(y.code));
}

/**
 * Codes a refusal uses that the catalogue does not list: ERR_/WARN_ constants anywhere in main code outside SOURCES,
 * and the CODE of every `new ApiException(status, CODE, …)` that is a literal or a bare constant the sources do not define.
 */
export function uncatalogued(files, read, list) {
    const known = new Set(list.map((e) => e.code));
    const knownNames = new Set(list.map((e) => e.constant));
    const sourceFiles = new Set(SOURCES.map((s) => s.file));
    const out = [];
    for (const f of files) {
        const java = read(f);
        const named = new Set();   // literals already reported by the ApiException sweep, so the finding sweep does not repeat them
        if (!sourceFiles.has(f))
            for (const c of constantsOf(java))
                if (/^(ERR|WARN)_/.test(c.value) && !known.has(c.value)) out.push(`${f}: ${c.name} = "${c.value}"`);
        // new [pkg.]ApiException(<status expression>, CODE, …) and SpaceLocations.Refused(…) — the status may be any expression
        for (const m of java.matchAll(/new\s+(?:[\w.]+\.)?(?:ApiException|Refused)\(\s*(?:[^,()"]|\([^()]*\))+,\s*("([^"]*)"|([A-Za-z_][\w.]*))\s*,/g)) {
            if (m[2] !== undefined) { if (!known.has(m[2])) { out.push(`${f}: refusal code "${m[2]}"`); named.add(m[2]); } continue; }
            const last = m[3].split('.').pop();
            if (!/^[A-Z][A-Z0-9_]+$/.test(last) || m[3].startsWith('ErrorCodes.')) continue;   // a variable, or a catalogued ErrorCodes constant
            if (!knownNames.has(last)) out.push(`${f}: refusal code ${m[3]}`);
        }
        // a literal code in a Finding, a CodedArgumentException or an ApiException's finding code (its whole argument
        // list, across lines) or in a {"code": "…"} map
        for (const m of java.matchAll(/(?:new\s+(?:[\w.]+\.)?(?:Finding|CodedArgumentException|ApiException)|\.coded)\(((?:[^()]|\((?:[^()]|\([^()]*\))*\))*)\)/g))
            for (const lit of m[1].matchAll(/"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)"/g))
                if (!known.has(lit[1]) && !named.has(lit[1])) out.push(`${f}: Finding code "${lit[1]}"`);
        for (const m of java.matchAll(/"code"\s*,\s*"([A-Z][A-Z0-9_]+)"/g))
            if (!known.has(m[1])) out.push(`${f}: "code", "${m[1]}"`);
    }
    return out;
}

// ── rendering ────────────────────────────────────────────────────────────────────────────────────

export function toJson(list) {
    return JSON.stringify({
        $comment: `Generated by \`${SELF}\` from the code constants and ${ENTRIES}. Never edit by hand.`,
        generatedBy: SELF,
        version: 1,
        page: OUT_PAGE,
        body: {
            shape: '{"error": {"errorCode", "message", "recoverable", "correlationId", "details"?}}',
            read: 'Branch on error.errorCode, never on message text. On a 422, error.details.findings[] carries one {severity, fieldPath, message, code?, guidance?} per problem: fix every ERROR finding, using its code below and its own guidance.',
        },
        families: FAMILIES,
        codes: list.map((e) => ({
            code: e.code, family: e.family, status: e.status, ...(e.severity ? { severity: e.severity } : {}),
            cause: e.cause, fix: e.fix, definedIn: e.definedIn,
        })),
    }, null, 2) + '\n';
}

const cell = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\s+/g, ' ').trim();

export function toPage(list) {
    const lines = ['---', 'type: Reference', 'title: Error catalogue',
        'description: Every refusal code the control plane answers with — its HTTP status, its cause and its fix. Generated from the code.',
        'item: layer.platform', 'audience: internal', 'status: current', 'tags: [api, errors, agents, generated]', '---', '',
        `> **Generated by \`${SELF}\`** from the code constants and \`${ENTRIES}\`. Never edit by hand — change the`,
        `> source and run \`${SELF} --write\`. The same list, machine-readable: [\`docs/api/errors-v1.json\`](../../../../api/errors-v1.json).`,
        '', '# Error catalogue', '',
        'Every refused request answers a v1 error body `{"error": {"errorCode", "message", "recoverable", "correlationId", "details"}}`.',
        '**Branch on `errorCode`, never on the message text.** A `422` adds `error.details.findings[]` — one',
        '`{severity, fieldPath, message, code, guidance}` per problem; fix every `ERROR` finding (its `code` is in the second',
        'table, its `guidance` says what to change at `fieldPath`) and resubmit. `WARNING` findings never block a save.',
        '', 'Most `422`s share the one code `CONFIG_VALIDATION_FAILED`. The refusals agents hit most also carry one finding with a',
        'code of its own (`error.details.findings[0].code`, listed under Config finding codes from `ERR_COLUMN_UNKNOWN` on);',
        'the rest carry none yet — their `message` names the key and the allowed values (board row `REFUSAL-CODES-REST-1`).',
        'Quote the `correlationId` when you report a `500`.', ''];
    for (const [fam, { title, where }] of Object.entries(FAMILIES)) {
        const rows = list.filter((e) => e.family === fam);
        lines.push(`## ${title}`, '', `Read from \`${where}\`. ${rows.length} codes.`, '');
        const sev = fam === 'finding';
        lines.push(sev ? '| Code | Severity | Status | Cause | Fix |' : '| Code | Status | Cause | Fix |',
            sev ? '|---|---|---|---|---|' : '|---|---|---|---|');
        for (const e of rows)
            lines.push(sev ? `| \`${e.code}\` | ${e.severity} | ${e.status ?? '—'} | ${cell(e.cause)} | ${cell(e.fix)} |`
                : `| \`${e.code}\` | ${e.status ?? '—'} | ${cell(e.cause)} | ${cell(e.fix)} |`);
        lines.push('');
    }
    lines.push('## Adding a code', '',
        `A new code constant, or a \`new ApiException(status, CODE, …)\` naming a code this page does not list, fails`,
        `\`${SELF} --check\` (pre-commit and CI). Add its cause and fix to \`${ENTRIES}\`, then run \`${SELF} --write\`.`,
        'The sweep reads `new ApiException(…)`, `new SpaceLocations.Refused(…)`, `new Finding(…)`, `new CodedArgumentException(…)`,',
        '`.coded(…)` and `"code", "…"` maps;',
        'a code handed through a helper method of your own is not seen, so add a code as a constant in one of the files',
        'the generator lists (`SOURCES`) and pass the constant.',
        'Codes are API: never renamed, never reused for another meaning.', '');
    return lines.join('\n');
}

// ── main ─────────────────────────────────────────────────────────────────────────────────────────

function main(argv) {
    const mode = argv.includes('--write') ? 'write' : argv.includes('--check') ? 'check' : null;
    if (!mode) { console.error('usage: node tools/render-error-catalogue.mjs --write | --check'); return 2; }
    const read = (f) => readFileSync(join(ROOT, f), 'utf8');
    let list;
    try { list = build(read, JSON.parse(read(ENTRIES))); }
    catch (e) { console.error('render-error-catalogue: ' + e.message.replace(/\n/g, '\n  ')); return 1; }
    const files = execFileSync('git', ['ls-files', '*/src/main/java/*.java'], { cwd: ROOT, encoding: 'utf8' })
        .split('\n').filter(Boolean);
    const stray = uncatalogued(files, read, list);
    if (stray.length) {
        console.error(`render-error-catalogue: ${stray.length} refusal code(s) not in the catalogue — add each to SOURCES in tools/render-error-catalogue.mjs and an entry to ${ENTRIES}:\n  ` + stray.join('\n  '));
        return 1;
    }
    const want = { [OUT_JSON]: toJson(list), [OUT_PAGE]: toPage(list) };
    let stale = 0;
    for (const [f, text] of Object.entries(want)) {
        let have = null;
        try { have = read(f).replace(/\r\n/g, '\n'); } catch { /* absent */ }
        if (have === text) continue;
        if (mode === 'write') { writeFileSync(join(ROOT, f), text); console.log('wrote ' + f); }
        else { console.error(`render-error-catalogue: ${f} is stale — run \`${SELF} --write\``); stale++; }
    }
    if (mode === 'check' && !stale) console.log(`render-error-catalogue: ${list.length} codes, both files current`);
    return stale ? 1 : 0;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) process.exitCode = main(process.argv.slice(2));
