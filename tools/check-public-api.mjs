#!/usr/bin/env node
// PUBLIC-API-SURFACE-1 (2026-11-12) - holds the @PublicApi surface stable between releases.
// Every tracked .java file in the repo (every module, main and test, no directory list to drift) is read; each type
// marked @PublicApi contributes its declaration header and the signature of every member a caller outside the
// package can see (public/protected members of a class, record or enum; every non-private member of an interface;
// enum constants; public nested types, recursively), and each @PublicApi method or constructor of an unmarked type
// contributes its own signature. Parameter names, annotations, method bodies and field initializers are left out,
// so renaming a parameter or changing an implementation is not a change. The lines are compared with the committed
// baseline platform/inspecto-api/public-api.txt: any difference fails, so a changed signature can only land in the
// same change that updates the baseline (`--update`), where a reviewer sees it.
// Usage: node tools/check-public-api.mjs            check against the baseline
//        node tools/check-public-api.mjs --update   rewrite the baseline from the source
// Doc: docs/okf/product/layer/platform/user-guide-builder-kit.md section 4.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const BASELINE = 'platform/inspecto-api/public-api.txt';
const MARK = /@(?:com\.gamma\.api\.)?PublicApi\b/;
const TYPE_HEADER = /(?:^|[\s>])(?:class|interface|enum|record|@interface)\s+[A-Za-z_$][\w$]*/;

/** Removes comments and blanks string, text-block and char literals (keeps the quotes), so braces only count code. */
export function clean(src) {
    let out = '', i = 0;
    const n = src.length;
    while (i < n) {
        const c = src[i], d = src[i + 1];
        if (c === '/' && d === '/') { while (i < n && src[i] !== '\n') i++; continue; }
        if (c === '/' && d === '*') { const e = src.indexOf('*/', i + 2); i = e < 0 ? n : e + 2; out += ' '; continue; }
        if (c === '"' && src.startsWith('"""', i)) {
            let j = i + 3;
            while (j < n && !(src.startsWith('"""', j) && src[j - 1] !== '\\')) j++;
            out += '""'; i = j + 3; continue;
        }
        if (c === '"' || c === '\'') {
            let j = i + 1;
            while (j < n && src[j] !== c) { if (src[j] === '\\') j++; j++; }
            out += c + c; i = j + 1; continue;
        }
        out += c; i++;
    }
    return out;
}

function matchBrace(t, i) {
    let depth = 0;
    for (let j = i; j < t.length; j++) {
        if (t[j] === '{') depth++;
        else if (t[j] === '}' && --depth === 0) return j;
    }
    throw new Error('unbalanced braces');
}

/** Top-level '=' (not part of ==, <=, >=, !=) in a member header: a field initializer starts there. */
function initializerAt(h) {
    let depth = 0;
    for (let j = 0; j < h.length; j++) {
        const c = h[j];
        if (c === '(' || c === '<' || c === '[') depth++;
        else if (c === ')' || c === '>' || c === ']') depth--;
        else if (c === '=' && depth === 0 && h[j + 1] !== '=' && !'=<>!'.includes(h[j - 1])) return j;
    }
    return -1;
}

/** Splits a type body (t[from, to)) into members: {kind: 'type'|'member', header, open, close}. */
function members(t, from, to) {
    const out = [];
    let header = '', paren = 0, i = from;
    while (i < to) {
        const c = t[i];
        if (c === '(') paren++;
        if (c === ')') paren--;
        if (c === '{') {
            const close = matchBrace(t, i);
            if (paren > 0) { header += '{}'; i = close + 1; continue; }              // anonymous class / lambda in args
            if (initializerAt(header) >= 0) { header += '{}'; i = close + 1; continue; } // field initializer brace
            out.push({ kind: TYPE_HEADER.test(stripAnnotations(header)) ? 'type' : 'member', header, open: i, close });
            header = ''; i = close + 1; continue;
        }
        if (c === ';' && paren === 0) { out.push({ kind: 'member', header }); header = ''; i++; continue; }
        header += c; i++;
    }
    if (header.trim()) out.push({ kind: 'member', header });
    return out;
}

/** Enum constants run from the body start to the first top-level ';' (or the body end). */
function enumConstants(t, from, to) {
    const names = [];
    let cur = '', paren = 0, i = from;
    for (; i < to; i++) {
        const c = t[i];
        if (c === '{' && paren === 0) { i = matchBrace(t, i); continue; }
        if (c === '(') paren++;
        if (c === ')') paren--;
        if (paren === 0 && (c === ',' || c === ';')) {
            const m = stripAnnotations(cur).trim().match(/^[\w$]+/);
            if (m) names.push(m[0]);
            cur = '';
            if (c === ';') return { names, rest: i + 1 };
            continue;
        }
        if (paren === 0) cur += c;
    }
    const m = stripAnnotations(cur).trim().match(/^[\w$]+/);
    if (m) names.push(m[0]);
    return { names, rest: to };
}

export function stripAnnotations(s) {
    let out = '', i = 0;
    while (i < s.length) {
        if (s[i] === '@' && !/^@\s*interface\b/.test(s.slice(i))) {
            const m = s.slice(i).match(/^@\s*[\w$.]+\s*/);
            i += m ? m[0].length : 1;
            if (s[i] === '(') {
                let depth = 0;
                for (; i < s.length; i++) { if (s[i] === '(') depth++; else if (s[i] === ')' && --depth === 0) { i++; break; } }
            }
            out += ' ';
            continue;
        }
        out += s[i++];
    }
    return out;
}

const tidy = s => s.replace(/\s+/g, ' ').replace(/\s*([<>,()[\]])\s*/g, '$1')
    .replace(/,/g, ', ').replace(/>(?=[\w$@?])/g, '> ').replace(/\)(?=[\w$])/g, ') ').trim();

function splitTop(s) {
    const parts = [];
    let depth = 0, cur = '';
    for (const c of s) {
        if ('<(['.includes(c)) depth++;
        if ('>)]'.includes(c)) depth--;
        if (c === ',' && depth === 0) { parts.push(cur); cur = ''; } else cur += c;
    }
    if (cur.trim()) parts.push(cur);
    return parts;
}

/** A member's signature, or null when it is not one (an initializer, a compact canonical constructor). */
export function signature(rawHeader) {
    const h = stripAnnotations(rawHeader).replace(/\s+/g, ' ').trim();
    if (!h) return null;
    const eq = initializerAt(h);
    const open = h.indexOf('(');
    if (open >= 0 && (eq < 0 || open < eq)) {
        let depth = 0, close = open;
        for (; close < h.length; close++) { if (h[close] === '(') depth++; else if (h[close] === ')' && --depth === 0) break; }
        const params = splitTop(h.slice(open + 1, close)).map(p => {
            const q = p.replace(/\bfinal\s+/g, '').trim();
            const dropped = q.replace(/(?<=[\s>\].])[\w$]+$/, '').trim();
            return dropped || q;
        });
        return tidy(h.slice(0, open)) + '(' + params.map(tidy).join(', ') + ')' + (close + 1 < h.length ? ' ' + tidy(h.slice(close + 1)) : '');
    }
    const field = eq >= 0 ? h.slice(0, eq) : h;
    const bare = field.replace(/\b(?:public|protected|private|static|final|transient|volatile)\b/g, '').trim();
    if (!/\s/.test(bare)) return null;           // `static` initializer, compact canonical constructor `public Name`
    return tidy(field);
}

const kindOf = header => (stripAnnotations(header).match(/(@interface|class|interface|enum|record)\s+[\w$]/) || [])[1];
const nameOf = header => stripAnnotations(header).match(/(?:@interface|class|interface|enum|record)\s+([\w$]+)/)[1];

/** Visible to a caller outside the package: interface members unless private; class members if public/protected. */
function visible(header, ownerKind) {
    const h = stripAnnotations(header);
    if (ownerKind === 'interface' || ownerKind === '@interface') return !/\bprivate\b/.test(h);
    return /\b(?:public|protected)\b/.test(h);
}

/** Walks one type; `marked` means its whole visible surface is recorded. */
function walkType(t, decl, fqn, marked, ownerKind, out) {
    const kind = kindOf(decl.header);
    const self = marked || MARK.test(decl.header);
    if (self) out.push(`${fqn} | ${tidy(stripAnnotations(decl.header))}`);
    let from = decl.open + 1;
    if (kind === 'enum') {
        const { names, rest } = enumConstants(t, from, decl.close);
        if (self) for (const n of names) out.push(`${fqn} | constant ${n}`);
        from = rest;
    }
    for (const m of members(t, from, decl.close)) {
        if (m.kind === 'type') {
            const nestedVisible = visible(m.header, kind) || !self;    // an unmarked type's nested types may hold marks
            if (nestedVisible) walkType(t, m, `${fqn}.${nameOf(m.header)}`, self && visible(m.header, kind), kind, out);
            continue;
        }
        if (!self && !MARK.test(m.header)) continue;
        if (!visible(m.header, kind) && !MARK.test(m.header)) continue;
        const sig = signature(m.header);
        if (sig) out.push(`${fqn} | ${sig}`);
    }
}

/** The surface lines one source file contributes (sorted by the caller). Throws when a mark is not understood. */
export function surfaceOf(src, file = '?') {
    const t = clean(src);
    if (!MARK.test(t)) return [];
    const pkg = (t.match(/^\s*package\s+([\w.]+)\s*;/m) || [])[1];
    const out = [];
    for (const m of members(t, 0, t.length)) {
        if (m.kind !== 'type') continue;
        const name = nameOf(m.header);
        walkType(t, m, pkg ? `${pkg}.${name}` : name, false, null, out);
    }
    if (!out.length) throw new Error(`${file}: names @PublicApi but no marked type or member was read`);
    return out;
}

/** Every tracked .java file under `root` (git ls-files: no directory list that could leave a module out). */
export function javaFiles(root) {
    return execFileSync('git', ['ls-files', '-z', '--', '*.java'], { cwd: root, encoding: 'utf8', maxBuffer: 64 << 20 })
        .split('\0').filter(Boolean);
}

export function surface(root, files = javaFiles(root)) {
    const lines = new Set();
    for (const f of files) {
        const p = join(root, f);
        if (!existsSync(p)) continue;                      // deleted in the working tree, not yet staged
        for (const l of surfaceOf(readFileSync(p, 'utf8'), f)) lines.add(l);
    }
    return [...lines].sort();
}

export function readBaseline(root) {
    const p = join(root, BASELINE);
    if (!existsSync(p)) return null;
    return readFileSync(p, 'utf8').split(/\r?\n/).filter(l => l && !l.startsWith('#'));
}

export function diff(baseline, current) {
    const b = new Set(baseline), c = new Set(current);
    return { removed: baseline.filter(l => !c.has(l)), added: current.filter(l => !b.has(l)) };
}

export function render(lines) {
    return '# @PublicApi surface - generated by `node tools/check-public-api.mjs --update`; never hand-edit.\n'
        + '# One line per marked type header or visible member: <type> | <signature> (parameter names left out).\n'
        + '# A changed line is a changed public signature: within a released major only additions are allowed.\n'
        + lines.join('\n') + '\n';
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const root = join(dirname(fileURLToPath(import.meta.url)), '..');
    const current = surface(root);
    if (process.argv.includes('--update')) {
        writeFileSync(join(root, BASELINE), render(current));
        console.log(`Public API guard: wrote ${BASELINE} (${current.length} lines).`);
        process.exit(0);
    }
    const baseline = readBaseline(root);
    if (!baseline) { console.error(`Public API guard FAILED: ${BASELINE} is missing; run with --update.`); process.exit(1); }
    const { removed, added } = diff(baseline, current);
    if (removed.length || added.length) {
        console.error('Public API guard FAILED: the @PublicApi surface differs from ' + BASELINE + ':');
        for (const l of removed) console.error('  - ' + l);
        for (const l of added) console.error('  + ' + l);
        console.error('If the change is intended, run `node tools/check-public-api.mjs --update` and commit the baseline in the');
        console.error('same change. Removing or changing a line of a released @PublicApi type needs a major version.');
        process.exit(1);
    }
    console.log(`Public API guard: ${current.length} @PublicApi signatures match ${BASELINE}.`);
}
