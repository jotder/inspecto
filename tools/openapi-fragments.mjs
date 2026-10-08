// OpenAPI contract fragments (MODULE-REORG-1 P1): the HTTP contract of a module lives WITH the module, in
// <module>/src/main/resources/META-INF/inspecto/openapi.fragment.json, and docs/api/openapi-v1.json is the
// GENERATED merge of the core fragment (inspecto/) and every module fragment.
//
// The merge works on TEXT, not on parsed JSON: the document is part Jackson-written skeleton, part
// hand-formatted (inline `{ "type" : "string" }` objects), so re-serialising it can never be byte-identical.
// A path entry is therefore moved verbatim as the run of lines between two 4-space-indented `"key" : ` lines.
//
// Fragment shapes
//   module fragment  { "paths" : { <path entries> } }                 (nothing else: a stray member would be dropped)
//   core fragment    the whole document minus the module-owned paths, plus a leading `"x-path-order"` member:
//                    the frozen order the paths had when the fragments were first cut. The merged document lists
//                    those first, in that order, then any path not listed, sorted. A new route is never added to the
//                    list; it lands at the end, sorted. The member is stripped from the merged output.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { parseJson } from './openapi-json.mjs';

export const FRAGMENT = 'src/main/resources/META-INF/inspecto/openapi.fragment.json';
export const MANIFEST = 'src/main/resources/META-INF/inspecto/module.toon';
export const CORE_DIR = 'inspecto';
/** Module groups scanned for a `module.toon` (same list as check-family-count). */
export const MODULE_GROUPS = ['spi', 'platform', 'features', 'la', 'providers', 'inspecto'];

const ORDER_MEMBER = '  "x-path-order" : [';
const MEMBER = /^ {2}"((?:[^"\\]|\\.)*)" : /;
const CHILD = /^ {4}"((?:[^"\\]|\\.)*)" : /;

/** Split a document/fragment text into its `paths` entries: lines before, [{key, text}], lines after. */
export function pathsOf(text) {
  const lines = text.split('\n');
  const start = lines.findIndex((l) => l === '  "paths" : {');
  if (start < 0) throw new Error('no top-level "paths" member');
  let end = start + 1;
  while (end < lines.length && !/^ {2}\}(,)?$/.test(lines[end])) end++;
  if (end >= lines.length) throw new Error('unterminated "paths" member');
  const entries = [];
  for (let i = start + 1; i < end; i++) {
    const m = CHILD.exec(lines[i]);
    if (m) entries.push({ key: JSON.parse('"' + m[1] + '"'), from: i });
  }
  entries.forEach((e, n) => {
    const to = n + 1 < entries.length ? entries[n + 1].from : end;
    const body = lines.slice(e.from, to);
    body[body.length - 1] = body[body.length - 1].replace(/,$/, '');
    e.text = body.join('\n');
  });
  return { before: lines.slice(0, start + 1), entries, after: lines.slice(end) };
}

const assemble = (before, entries, after) =>
  [...before, ...(entries.length ? [entries.map((e) => e.text).join(',\n')] : []), ...after].join('\n');

const compare = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

/** Cut a document into the core fragment text and one fragment text per owner; `ownerOf(pathKey)` -> module id | null. */
export function splitDocument(text, ownerOf) {
  const { before, entries, after } = pathsOf(text);
  const owned = new Map();
  const core = [];
  for (const e of entries) {
    const o = ownerOf(e.key);
    if (o) { if (!owned.has(o)) owned.set(o, []); owned.get(o).push(e); } else core.push(e);
  }
  const order = [ORDER_MEMBER, entries.map((e) => '    ' + JSON.stringify(e.key)).join(',\n'), '  ],'];
  const coreText = ['{', ...order, ...before.slice(1)].join('\n');
  const out = { core: assemble(coreText.split('\n'), core, after), modules: new Map() };
  for (const [id, list] of owned) out.modules.set(id, assemble(['{', '  "paths" : {'], list, ['  }', '}', '']));
  return out;
}

/** Merge the core fragment text and [{name, text}] module fragments into the document text; throws on a collision. */
export function mergeFragments(coreText, fragments) {
  const lines = coreText.split('\n');
  let order = [];
  const o = lines.indexOf(ORDER_MEMBER);
  if (o >= 0) {
    let e = o + 1;
    while (lines[e] !== '  ],') e++;
    order = lines.slice(o + 1, e).map((l) => JSON.parse(l.trim().replace(/,$/, '')));
    lines.splice(o, e - o + 1);
  }
  const { before, entries: coreEntries, after } = pathsOf(lines.join('\n'));
  const seen = new Map();
  const collisions = [];
  const all = [];
  const take = (name, e) => {
    if (seen.has(e.key)) collisions.push(`${e.key} (${seen.get(e.key)} and ${name})`);
    else { seen.set(e.key, name); all.push(e); }
  };
  coreEntries.forEach((e) => take('core', e));
  for (const f of fragments) pathsOf(f.text).entries.forEach((e) => take(f.name, e));
  if (collisions.length) throw new Error('path in two fragments: ' + collisions.join('; '));
  const rank = new Map(order.map((k, i) => [k, i]));
  const listed = all.filter((e) => rank.has(e.key)).sort((a, b) => rank.get(a.key) - rank.get(b.key));
  const rest = all.filter((e) => !rank.has(e.key)).sort((a, b) => compare(a.key, b.key));
  return assemble(before, [...listed, ...rest], after);
}

// ── manifests: which routes a module declares, so a path can be assigned to its module ─────────────────────

/** Structural shape of a route regex or an OpenAPI template: every parameter collapses to `{}`. */
export function routeShape(pattern) {
  return pattern.replaceAll('\\.', '.').replace(/(?:\(\?![^)]*\))?\(([^()]*)\)/g, '{}').replace(/\{[^}]*\}/g, '{}');
}

/** [{id, dir, routes:[{method, shape}]}] for every manifest under `root`, sorted by dir. */
export function readManifests(root) {
  const out = [];
  const take = (dir) => {
    const f = join(root, dir, MANIFEST);
    if (!existsSync(f)) return;
    const t = readFileSync(f, 'utf8');
    const id = /^id:\s*(\S+)/m.exec(t)?.[1];
    const r = /^\s*routes\[\d+\]:\s*(.*)$/m.exec(t);
    const routes = r ? [...r[1].matchAll(/"([A-Z]+) ([^"]+)"/g)].map((m) => ({ method: m[1].toLowerCase(), shape: routeShape(m[2]) })) : [];
    out.push({ id, dir, routes });
  };
  for (const g of MODULE_GROUPS) {
    const d = join(root, g);
    if (!existsSync(d)) continue;
    take(g);
    for (const m of readdirSync(d)) take(g + '/' + m);
  }
  return out.sort((a, b) => compare(a.dir, b.dir));
}

/** Everything the merge and the guard need from the working tree. */
export function loadTree(root) {
  const manifests = readManifests(root);
  const core = readFileSync(join(root, CORE_DIR, FRAGMENT), 'utf8');
  const fragments = [];
  for (const m of manifests) {
    const f = join(root, m.dir, FRAGMENT);
    if (m.dir !== CORE_DIR && existsSync(f)) fragments.push({ name: m.id, dir: m.dir, text: readFileSync(f, 'utf8') });
  }
  return { manifests, core, fragments };
}

/** Problems that a merge cannot see: ownership, completeness, stray members. Empty when the tree is consistent. */
export function ownershipProblems({ manifests, core, fragments }) {
  const problems = [];
  const byId = new Map(manifests.map((m) => [m.id, m]));
  const declared = new Set(manifests.flatMap((m) => m.routes.map((r) => r.shape)));
  const shapesIn = (text) => new Map(pathsOf(text).entries.map((e) => [routeShape(e.key), e.key]));
  for (const f of fragments) {
    const mod = byId.get(f.name);
    const keys = [...parseJson(f.text).keys()];
    if (keys.length !== 1 || keys[0] !== 'paths') problems.push(`${f.dir}: fragment members must be exactly "paths", found ${keys.join(', ')}`);
    const have = shapesIn(f.text);
    const own = new Set(mod.routes.map((r) => r.shape));
    for (const [s, k] of have) if (!own.has(s)) problems.push(`${f.dir}: path ${k} is not declared by provides.routes of ${f.name}`);
    const doc = parseJson(f.text).get('paths');
    for (const r of mod.routes) {
      const k = have.get(r.shape);
      if (!k) problems.push(`${f.dir}: route ${r.method.toUpperCase()} ${r.shape} of ${f.name} has no path in the fragment`);
      else if (!doc.get(k).has(r.method)) problems.push(`${f.dir}: ${k} has no ${r.method} operation (declared by ${f.name})`);
    }
  }
  const owners = new Set(fragments.map((f) => f.name));
  for (const m of manifests) if (m.routes.length && !owners.has(m.id)) problems.push(`${m.dir}: declares ${m.routes.length} route(s) but ships no openapi.fragment.json`);
  for (const [s, k] of shapesIn(core)) if (declared.has(s)) problems.push(`core fragment documents ${k}, which a module manifest declares — it belongs in that module's fragment`);
  return problems;
}
