#!/usr/bin/env node
// docs-relocate — moves docs by a mapping and rewrites every reference to them (PRODUCT-DOCS-HUB-1 wave 4,
// docs/superpower/product-docs-hub-plan.md).
//
//   node tools/docs-relocate.mjs <mapping.csv> [--write]      dry run without --write
//
// mapping.csv: `old/path.md|new/path.md` per line (repo-relative). A new path of `-` drops the file (git rm);
// links to it are pointed at the hub list docs/okf/product/index.md.
// 1 git mv each file (git rm for drops)
// 2 every tracked text file outside docs/archived-documents/: a repo-relative mention of an old path becomes the new path
// 3 every tracked *.md: relative links `](x)` are re-resolved from the file's OLD location and re-relativised from its NEW one;
//   a link to a directory that no longer exists after the moves is pointed at the hub list

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, posix } from 'node:path';

const HUB_LIST = 'docs/okf/product/index.md';
const TEXT = /\.(md|mjs|js|ts|java|toon|json|ya?ml|ps1|cmd|sh|xml|txt|html|css|scss|py|properties)$/i;
const git = (...a) => execFileSync('git', a, { encoding: 'utf8', maxBuffer: 1 << 28 });

const [csv, ...flags] = process.argv.slice(2);
const write = flags.includes('--write');
const map = new Map();
for (const line of readFileSync(csv, 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const [o, n] = line.split('|').map((s) => s.trim());
    map.set(o, n === '-' ? null : n);
}
const tracked = git('ls-files').split('\n').filter(Boolean);
const trackedSet = new Set(tracked);
for (const o of map.keys()) if (!trackedSet.has(o)) throw new Error(`not tracked: ${o}`);
const targets = [...map.values()].filter(Boolean);
const dup = targets.find((t, i) => targets.indexOf(t) !== i);
if (dup) throw new Error(`two docs map to ${dup}`);

const newOf = (p) => (map.has(p) ? map.get(p) ?? HUB_LIST : p);
// directories that hold at least one tracked file after the moves
const after = tracked.filter((p) => !map.has(p)).concat(targets);
const liveDirs = new Set();
for (const p of after) for (let d = posix.dirname(p); d !== '.'; d = posix.dirname(d)) liveDirs.add(d);

const isDir = (p) => p === '' || p === '.' || liveDirs.has(p) || (existsSync(p) && statSync(p).isDirectory());
function relink(fileOld, fileNew, target) {
    if (/^([a-z][a-z0-9+.-]*:|#|\/|<)/i.test(target)) return target;
    const hashAt = target.search(/[#?]/);
    const path = hashAt < 0 ? target : target.slice(0, hashAt), rest = hashAt < 0 ? '' : target.slice(hashAt);
    if (!path) return target;
    let abs = posix.normalize(posix.join(posix.dirname(fileOld), decodeURI(path))).replace(/\/$/, '');
    if (abs.startsWith('..')) return target;
    let moved = map.has(abs) ? newOf(abs) : abs;
    let keepRest = rest;
    if (map.has(abs) && map.get(abs) === null) keepRest = '';
    if (!map.has(abs) && !trackedSet.has(abs) && !liveDirs.has(abs) && !existsSync(abs)) {
        // an old directory that is now empty (index pages and folders the moves removed)
        const wasDir = tracked.some((p) => p.startsWith(abs + '/'));
        if (!wasDir) return target; // already broken — not ours to fix
        moved = HUB_LIST; keepRest = '';
    } else if (isDir(abs) && !liveDirs.has(abs) && tracked.some((p) => p.startsWith(abs + '/'))) {
        moved = HUB_LIST; keepRest = '';
    }
    if (moved === abs && fileOld === fileNew) return target;
    let rel = posix.relative(posix.dirname(fileNew), moved) || posix.basename(moved);
    if (path.endsWith('/') && !rel.endsWith('/') && moved !== HUB_LIST) rel += '/';
    return rel + keepRest;
}

const olds = [...map.keys()].sort((a, b) => b.length - a.length);
const mentionRe = new RegExp(olds.map((o) => o.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'g');
let changed = 0;
const edits = new Map();
for (const f of tracked) {
    if (f.startsWith('docs/archived-documents/') || !TEXT.test(f) || !existsSync(f)) continue;
    const fileNew = newOf(f);
    if (fileNew === HUB_LIST && map.get(f) === null) continue; // dropped
    let s = readFileSync(f, 'utf8'), t = s;
    if (f.endsWith('.md')) {
        t = t.replace(/(\]\()([^)\s]+)((?:\s+"[^"]*")?\))/g, (m, a, target, c) => a + relink(f, fileNew, target) + c);
        t = t.replace(/^(\[[^\]]+\]:\s*)(\S+)/gm, (m, a, target) => a + relink(f, fileNew, target));
    }
    t = t.replace(mentionRe, (o) => map.get(o) ?? HUB_LIST);
    if (t !== s) { edits.set(f, t); changed++; }
}
console.log(`docs-relocate: ${map.size} mapped (${targets.length} move, ${map.size - targets.length} drop); ${changed} file(s) to rewrite`);
if (!write) process.exit(0);

for (const [f, t] of edits) writeFileSync(f, t);
for (const [o, n] of map) {
    if (n === null) { git('rm', '-q', o); continue; }
    mkdirSync(dirname(n), { recursive: true });
    git('mv', o, n);
}
console.log('docs-relocate: written');
