// handbook-checks — the Handbook's docs-as-tests step format: parse, validate statically, evaluate (USER-HANDBOOK-1 P2
// lane E; docs/okf/product/layer/product/spec-handbook-doc-tests.md is the format's specification).
//
// A tutorial or how-to page carries its checkable steps as fenced blocks:
//   ```check      one Control API call — METHOD /path (relative to /api/v1), then `as`, `body`, `expect`, `save`, `retry`
//   ```check-ui   one or more console steps in the LA recorder's episode verbs (tools/record-la-live.mjs), one JSON per line
// Shared by tools/check-handbook.mjs (syntax and routes, no server), tools/render-handbook.mjs (the reader's view) and
// tools/run-handbook-checks.mjs (runs them against an install). No dependency (the repo's dependency set is locked).
// Tests: tools/handbook-checks.test.mjs.

import { isDeepStrictEqual } from 'node:util';

export const FENCE_API = 'check';
export const FENCE_UI = 'check-ui';
export const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
export const OPS = ['=', '!=', '>', '>=', '<', '<=', 'has', 'matches', 'exists', 'absent'];
/** Variables every run defines: `{space}` is the runner's --space. Others come from an earlier `save … as <name>`. */
export const BUILTIN_VARS = ['space'];
/** The episode verbs tools/record-la-live.mjs understands (its `episode` scenario); a parity test keeps them in step. */
export const UI_VERBS = ['scene', 'space', 'signin', 'open', 'wait', 'waitText', 'click', 'exact', 'label', 'hoverLabel', 'hoverSel',
    'log', 'button', 'sel', 'rightOf', 'selectAll', 'type', 'key', 'kb', 'drag', 'escape', 'reveal', 'download', 'upload', 'undo',
    'hover', 'move', 'pick', 'at', 'impact', 'mark', 'shot', 'dump', 'note'];

const PATH_RE = /^[A-Za-z_$][\w$-]*(?:\.[A-Za-z_$][\w$-]*|\[(?:\d+|\*)\])*$/;
const VAR_RE = /\{([A-Za-z_][\w]*)\}/g;

/**
 * The check blocks of a markdown page, in order: [{ kind: 'api'|'ui', line, lines: [{ text, line }] }]. `line` numbers
 * are 1-based. Other fenced blocks are skipped whole, so a check shown inside a ```` ```markdown ```` example is not one.
 */
export function extractBlocks(md) {
    const lines = md.replace(/\r\n/g, '\n').split('\n');
    const out = [];
    for (let i = 0; i < lines.length; i++) {
        const open = lines[i].match(/^\s*(`{3,}|~{3,})\s*(\S*)/);
        if (!open) continue;
        const fence = open[1], info = open[2];
        const body = [];
        let j = i + 1;
        for (; j < lines.length; j++) {
            const t = lines[j].trim();
            if (t.startsWith(fence[0].repeat(fence.length)) && /^(`+|~+)$/.test(t)) break;
            body.push({ text: lines[j], line: j + 1 });
        }
        if (info === FENCE_API || info === FENCE_UI) out.push({ kind: info === FENCE_API ? 'api' : 'ui', line: i + 1, lines: body });
        i = j;
    }
    return out;
}

/** `data.items[*].id` → ['data', 'items', '*', 'id']. Throws on a malformed path. */
export function parsePath(p) {
    if (!PATH_RE.test(p)) throw new Error(`'${p}' is not a path (name, .name, [0], [*])`);
    return [...p.matchAll(/([A-Za-z_$][\w$-]*)|\[(\d+|\*)\]/g)].map((m) => m[1] ?? m[2]);
}

/** A literal: JSON when it parses, else the bare text as a string (so `has o2c_orders` reads naturally). */
const literal = (s) => { try { return JSON.parse(s); } catch { return s; } };

/** One `expect` line → { kind: 'status', codes } | { kind: 'assert', path, tokens, op, value }. Throws on bad syntax. */
export function parseExpect(rest) {
    const st = rest.match(/^status\s+(\d{3}(?:\|\d{3})*)$/);
    if (st) return { kind: 'status', codes: st[1].split('|').map(Number) };
    const m = rest.match(/^(\S+)\s+(!=|>=|<=|=|>|<|has|matches|exists|absent)(?:\s+(.+))?$/);
    if (!m) throw new Error(`expect: '${rest}' is not 'status <code>' or '<path> <op> [value]' (ops: ${OPS.join(' ')})`);
    const [, path, op, raw] = m;
    const tokens = parsePath(path);
    const unary = op === 'exists' || op === 'absent';
    if (unary && raw !== undefined) throw new Error(`expect: '${op}' takes no value`);
    if (!unary && raw === undefined) throw new Error(`expect: '${op}' needs a value`);
    let value = unary ? undefined : literal(raw.trim());
    if (op === 'matches') {
        const r = String(raw).trim().match(/^\/(.*)\/([gimsuy]*)$/);
        if (!r) throw new Error(`expect: 'matches' takes /regex/flags, got ${raw}`);
        try { value = new RegExp(r[1], r[2]); } catch (e) { throw new Error(`expect: bad regex ${raw}: ${e.message}`); }
    }
    if (['>', '>=', '<', '<='].includes(op) && typeof value !== 'number') throw new Error(`expect: '${op}' needs a number, got ${raw}`);
    return { kind: 'assert', path, tokens, op, value, text: rest };
}

/**
 * Parse one block. API → { kind, line, method, path, as, body, expects, saves, retry, errors }; UI → { kind, line, steps,
 * errors }. Never throws: every syntax error lands in `errors` as { line, message }.
 */
export function parseBlock(block) {
    const errors = [];
    const rows = block.lines.filter((l) => l.text.trim() && !l.text.trim().startsWith('#'));
    if (block.kind === 'ui') {
        const steps = [];
        for (const r of rows) {
            let o;
            try { o = JSON.parse(r.text); } catch { errors.push({ line: r.line, message: `check-ui: each line is one JSON step, got ${r.text.trim().slice(0, 60)}` }); continue; }
            if (!o || typeof o !== 'object' || Array.isArray(o)) { errors.push({ line: r.line, message: 'check-ui: a step is a JSON object' }); continue; }
            if (!Object.keys(o).some((k) => UI_VERBS.includes(k))) errors.push({ line: r.line, message: `check-ui: no known verb in ${JSON.stringify(o).slice(0, 60)} (verbs: tools/record-la-live.mjs episode)` });
            steps.push(o);
        }
        if (!rows.length) errors.push({ line: block.line, message: 'check-ui: the block is empty' });
        return { kind: 'ui', line: block.line, steps, errors };
    }
    const step = { kind: 'api', line: block.line, method: null, path: null, as: null, body: undefined, expects: [], saves: [], retry: null, errors };
    if (!rows.length) { errors.push({ line: block.line, message: 'check: the block is empty' }); return step; }
    const first = rows[0].text.trim().match(/^([A-Z]+)\s+(\/\S*)$/);
    if (!first || !METHODS.includes(first[1])) errors.push({ line: rows[0].line, message: `check: the first line is 'METHOD /path' (${METHODS.join(', ')}), got '${rows[0].text.trim()}'` });
    else { step.method = first[1]; step.path = first[2]; }
    for (let k = 1; k < rows.length; k++) {
        const { text, line } = rows[k];
        const t = text.trim();
        const [word] = t.split(/\s+/, 1);
        const rest = t.slice(word.length).trim();
        try {
            if (word === 'as') {
                if (!/^[\w.-]+$/.test(rest)) throw new Error(`as: one persona name, got '${rest}'`);
                if (step.as) throw new Error('as: given twice');
                step.as = rest;
            } else if (word === 'body') {
                if (step.body !== undefined) throw new Error('body: given twice');
                let json = rest, parsed, ok = false;
                // the JSON may run over the following lines: take lines until it parses
                for (;;) {
                    try { parsed = JSON.parse(json); ok = true; break; } catch { /* not yet complete */ }
                    if (k + 1 >= rows.length) break;
                    json += '\n' + rows[++k].text;
                }
                if (!ok) throw new Error('body: not valid JSON (it may run over several lines, but must close)');
                step.body = parsed;
            } else if (word === 'expect') {
                step.expects.push({ ...parseExpect(rest), line });
            } else if (word === 'save') {
                const m = rest.match(/^(\S+)\s+as\s+([A-Za-z_]\w*)$/);
                if (!m) throw new Error(`save: '<path> as <name>', got '${rest}'`);
                step.saves.push({ path: m[1], tokens: parsePath(m[1]), name: m[2], line });
            } else if (word === 'retry') {
                const m = rest.match(/^(\d+)\s+every\s+(\d+)(ms|s)$/);
                if (!m || Number(m[1]) < 1) throw new Error(`retry: '<tries> every <n>ms|s', got '${rest}'`);
                if (step.retry) throw new Error('retry: given twice');
                step.retry = { tries: Number(m[1]), everyMs: Number(m[2]) * (m[3] === 's' ? 1000 : 1) };
            } else throw new Error(`unknown line '${t.slice(0, 60)}' (as, body, expect, save, retry)`);
        } catch (e) { errors.push({ line, message: `check: ${e.message}` }); }
    }
    return step;
}

/** The variable names a step uses (path and body strings). */
export function varsUsed(step) {
    const names = new Set();
    const scan = (v) => {
        if (typeof v === 'string') for (const m of v.matchAll(VAR_RE)) names.add(m[1]);
        else if (Array.isArray(v)) v.forEach(scan);
        else if (v && typeof v === 'object') Object.values(v).forEach(scan);
    };
    scan(step.path);
    scan(step.body);
    return [...names];
}

/** Replace `{name}` in strings (deep). An unknown name throws. */
export function substitute(v, vars) {
    if (typeof v === 'string') return v.replace(VAR_RE, (_, n) => { if (!(n in vars)) throw new Error(`variable {${n}} is not set`); return String(vars[n]); });
    if (Array.isArray(v)) return v.map((x) => substitute(x, vars));
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, substitute(x, vars)]));
    return v;
}

/** Does METHOD path name an operation of the OpenAPI document? `/spaces/{id}/<rest>` also matches `<rest>` (the space seam). */
export function routeKnown(method, path, openapi) {
    const segs = (p) => p.split('?')[0].replace(/\/+$/, '').split('/').filter(Boolean);
    const want = segs(path);
    const m = method.toLowerCase();
    const match = (a, b) => a.length === b.length && a.every((s, i) => s === b[i] || /^\{.*\}$/.test(s) || /^\{.*\}$/.test(b[i]));
    const candidates = [want];
    if (want[0] === 'spaces' && want.length > 2) candidates.push(want.slice(2));
    return Object.entries(openapi.paths || {}).some(([p, ops]) => ops && ops[m] && candidates.some((c) => match(c, segs(p))));
}

/**
 * Static findings for one page's text: syntax, routes against the OpenAPI document, and variables used before they are
 * saved. `openapi()` returns the parsed document (called only when an API block exists). Returns [{ line, message }].
 */
export function validatePage(md, openapi) {
    const out = [];
    const saved = new Set(BUILTIN_VARS);
    let doc = null;
    for (const b of extractBlocks(md)) {
        const s = parseBlock(b);
        out.push(...s.errors);
        if (s.kind !== 'api' || !s.method) continue;
        for (const n of varsUsed(s)) if (!saved.has(n)) out.push({ line: s.line, message: `check: {${n}} is used before a 'save … as ${n}' on this page` });
        doc ??= openapi();
        if (!routeKnown(s.method, s.path, doc)) out.push({ line: s.line, message: `check: ${s.method} ${s.path} is not an operation of docs/api/openapi-v1.json` });
        for (const v of s.saves) saved.add(v.name);
    }
    return out;
}

/** The value at a path; `[*]` spreads an array (the result is then a list). `length` reads an array's or string's length. */
export function evalPath(root, tokens) {
    let cur = [root], many = false;
    const get = (v, t) => {
        if (v === null || v === undefined) return undefined;
        if (Array.isArray(v) && /^\d+$/.test(t)) return v[Number(t)];
        if (typeof v === 'object' && Object.hasOwn(v, t)) return v[t];
        if (t === 'length' && (Array.isArray(v) || typeof v === 'string')) return v.length;
        return undefined;
    };
    for (const t of tokens) {
        if (t === '*') { cur = cur.flatMap((v) => (Array.isArray(v) ? v : [])); many = true; continue; }
        cur = cur.map((v) => get(v, t));
        if (many) cur = cur.filter((v) => v !== undefined);
    }
    return many ? cur : cur[0];
}

/** Evaluate one parsed `assert` expectation against a response body. Returns { ok, actual }. */
export function evaluate(expect, root) {
    const actual = evalPath(root, expect.tokens);
    const v = expect.value;
    // a [*] path yields a list: it "exists" when the list holds something
    const present = expect.tokens.includes('*') ? actual.length > 0 : actual !== undefined && actual !== null;
    let ok;
    switch (expect.op) {
        case 'exists': ok = present; break;
        case 'absent': ok = !present; break;
        case '=': ok = isDeepStrictEqual(actual, v); break;
        case '!=': ok = !isDeepStrictEqual(actual, v); break;
        case '>': ok = typeof actual === 'number' && actual > v; break;
        case '>=': ok = typeof actual === 'number' && actual >= v; break;
        case '<': ok = typeof actual === 'number' && actual < v; break;
        case '<=': ok = typeof actual === 'number' && actual <= v; break;
        case 'has':
            ok = Array.isArray(actual) ? actual.some((x) => isDeepStrictEqual(x, v))
                : typeof actual === 'string' ? actual.includes(String(v))
                    : !!actual && typeof actual === 'object' && typeof v === 'string' && Object.hasOwn(actual, v);
            break;
        case 'matches': v.lastIndex = 0; ok = typeof actual === 'string' && v.test(actual); break;   // a /g or /y regex keeps state between tries
        default: ok = false;
    }
    return { ok, actual };
}

/** One quiet line for a reader: what the check does. */
export function describe(step) {
    if (step.kind === 'ui') {
        const verbs = step.steps.map((o) => {
            const k = Object.keys(o).find((x) => UI_VERBS.includes(x) && x !== 'note');
            if (!k || k === 'wait') return null;
            return typeof o[k] === 'string' ? `${k} “${o[k]}”` : k;
        }).filter(Boolean);
        return `In the console: ${verbs.slice(0, 4).join(' · ')}${verbs.length > 4 ? ' …' : ''}`;
    }
    if (!step.method) return 'A check';
    const status = step.expects.find((e) => e.kind === 'status');
    const n = step.expects.filter((e) => e.kind === 'assert').length;
    return `${step.method} ${step.path}${step.as ? ` as ${step.as}` : ''} answers ${status ? status.codes.join(' or ') : '2xx'}` +
        (n ? `, ${n} more expectation${n > 1 ? 's' : ''}` : '');
}
