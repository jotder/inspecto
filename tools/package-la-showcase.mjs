#!/usr/bin/env node
// package-la-showcase.mjs — turn a Link Analysis showcase template into its two downloads (LA-SHOWCASE-DIST-1, 2026-11-07).
//
// The product bundle ships no Spaces; a showcase is attached beside it from two more downloads, so each can be updated
// on its own (docs/okf/product/solution-pack/la-showcase/architecture-showcase-catalogue.md, "Distribution and install
// layout"):
//
//   inspecto-la-space-<id>-<version>.zip   <id>/space.toon + <id>/config/**      -> unzip into <parent>/inspecto-la-spaces/
//   inspecto-la-data-<id>-<version>.zip    <id>/data/**                          -> unzip into <parent>/inspecto-la-data/
//
// and the product runs with SPACES_ROOT=../inspecto-la-spaces SPACES_DATA_ROOT=../inspecto-la-data.
//
// WHY THE SPLIT IS EXACT. With a data root set, a Space folder that still holds data/, audit/ or duckdb/ is NOT booted
// (SpaceDataRoot), so the Space zip must carry no data/. The data zip holds only data/: an unstamped data root holding
// only data/, audit/ and duckdb/ is adopted on first boot.
//
// WHAT A SPACE IS, mirrored from SpaceManager.createFromTemplate: config/** with ${SPACE} replaced by the id in every
// .toon, an empty config/egress.toon (a new Space is never seeded), and a space.toon written from template.toon
// (name -> display_name, tagline -> description). template.toon and
// portal/ are template-gallery files and do not ship. space.toon also names min_product_version (SpaceVersionCheck).
//
// COMMITTED CONTENT ONLY, the rule of inspecto/package-spaces.ps1: files are read from `git show HEAD:<path>`, so an
// uncommitted edit or an untracked file in the working tree never ships.
//
// Usage: node tools/package-la-showcase.mjs <template-id> [--version <v>] [--min-product <v>] [--out <dir>]
//   --version      the downloads' version (default: the reactor version in pom.xml)
//   --min-product  space.toon min_product_version (default: --version without its -SNAPSHOT/-qualifier)
//   --out          output directory (default: the repository root)

import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TEMPLATES = 'spaces/_templates';
const INDUSTRIES = 'spaces/_industries';

function git(args, opts = {}) {
  return execFileSync('git', args, { cwd: REPO, maxBuffer: 1 << 30, ...opts });
}

/** Parse argv into { id, version, minProduct, out }. */
export function parseArgs(argv) {
  const out = { id: '', version: '', minProduct: '', out: REPO };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--version') out.version = argv[++i];
    else if (a === '--min-product') out.minProduct = argv[++i];
    else if (a === '--out') out.out = resolve(argv[++i]);
    else if (!out.id) out.id = a;
    else throw new Error(`unexpected argument '${a}'`);
  }
  if (!out.id) throw new Error('usage: node tools/package-la-showcase.mjs <template-id> [--version <v>] [--min-product <v>] [--out <dir>]');
  if (!/^[a-z0-9][a-z0-9-]{0,62}$/.test(out.id)) throw new Error(`'${out.id}' is not a Space id ([a-z0-9-], 1-63 chars)`);
  return out;
}

/** The reactor version from the committed root pom.xml (the first <version> is the project's). */
function reactorVersion() {
  const pom = git(['show', 'HEAD:pom.xml'], { encoding: 'utf8' });
  const m = pom.match(/<version>([^<]+)<\/version>/);
  if (!m) throw new Error('no <version> in pom.xml');
  return m[1].trim();
}

/** A TOON string value: quoted, with \ and " escaped. */
const q = (s) => `"${String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;

/** One top-level `key: value` from template.toon (string values, quoted or bare). */
export function toonValue(text, key) {
  const m = text.match(new RegExp(`^${key}:\\s*(.*)$`, 'm'));
  if (!m) return '';
  const v = m[1].trim();
  return v.startsWith('"') ? JSON.parse(v) : v;
}

/** The space.toon a Space created from this template gets, plus min_product_version. */
export function spaceToon(templateToon, id, minProduct) {
  const name = toonValue(templateToon, 'name') || id;
  const desc = toonValue(templateToon, 'tagline');
  return `display_name: ${q(name)}\ndescription: ${q(desc)}\ncreated_at: ${q(new Date().toISOString())}\nmin_product_version: ${q(minProduct)}\n`;
}

/** config/egress.toon as EgressAllowlist.recordEmpty writes it for a new Space: an empty allowlist. Without it the server
 *  seeds one at boot from the Space's current outbound targets, allowing them unreviewed. */
export function egressToon() {
  return `allow[0]:\ncreatedAt: ${q(new Date().toISOString())}\n`;
}

/**
 * Split a template's committed files into the two downloads.
 * @param files  paths relative to the template folder
 * @returns { space: [{from, to, toon}], data: [{from, to}] }  `to` is relative to the zip root and starts with `<id>/`
 */
export function plan(id, files) {
  const space = [], data = [];
  for (const f of files) {
    if (f.startsWith('config/')) space.push({ from: f, to: `${id}/${f}`, toon: f.endsWith('.toon') });
    else if (f.startsWith('data/')) data.push({ from: f, to: `${id}/${f}` });
    // template.toon (written as space.toon instead), portal/ and anything else stay in the template gallery
  }
  if (!space.length) throw new Error(`template '${id}' has no committed config/ files`);
  if (!data.length) throw new Error(`template '${id}' has no committed data/ files - a showcase needs its data download`);
  return { space, data };
}

/**
 * LA-INDUSTRY-SPACES-1: an industry Space is composed from use-case templates named by a recipe
 * (`spaces/_industries/<id>.toon`, `useCases[n]: a,b`). Every file comes from exactly one use case: a path two use cases
 * both ship with different content is refused, so a real collision is renamed in its template on purpose. Four
 * exceptions: the same bytes in several use cases ship once; `config/link-analysis.toon` is merged by entity-type id
 * (the same id must be declared the same way); the Advanced tier's roster, `config/demo-users.toon` by user id and
 * `config/roles.toon` by role name (the shared `admin` or `la-viewer` must read the same in each); and
 * `config/examples/*.md` are joined. A lone copy of any joined file ships verbatim.
 * @param byTemplate  [{ tpl, files: [paths relative to the template folder] }]
 * @returns Map path -> template that ships it (link-analysis.toon maps to every template that has one)
 */
export const MERGED = 'config/link-analysis.toon';
/** The Advanced tier's roster: two use cases with personas each ship these, merged by user id and by role name. */
export const ROSTERS = ['config/demo-users.toon', 'config/roles.toon'];
/** Files every use case may ship, joined in the composed Space: link-analysis.toon and the roster (merged) and the inert
 *  examples (config/examples/*.md, which every template must carry under the same name, each titled for its own Space). */
export const isJoined = (f) => f === MERGED || ROSTERS.includes(f) || (f.startsWith('config/examples/') && f.endsWith('.md'));
/** Inert example documents from several use cases, one after the other. */
export const joinExamples = (texts) => texts.map((t) => t.replace(/\s+$/, '')).join('\n\n---\n\n') + '\n';
export function composeSources(byTemplate, sameBytes = () => false) {
  const owner = new Map();
  for (const { tpl, files } of byTemplate) {
    if (!files.includes('template.toon')) throw new Error(`use case '${tpl}' has no committed template.toon: not a Space template (a typo in the recipe?)`);
    for (const f of files) {
      if (f === 'template.toon' || f.startsWith('portal/')) continue;
      if (isJoined(f)) { owner.set(f, [...(owner.get(f) ?? []), tpl]); continue; }
      if (owner.has(f)) {
        // The same bytes in both (a shared governance example, for one) is one file; anything else is a real clash.
        if (sameBytes(f, owner.get(f), tpl)) continue;
        throw new Error(`'${f}' is shipped by both ${owner.get(f)} and ${tpl}: rename it in one template`);
      }
      owner.set(f, tpl);
    }
  }
  return owner;
}

/**
 * Merge several link-analysis.toon files: `entity_types` by id (a differing re-declaration is refused), and every other
 * top-level setting (`four_eyes_fan_out_above: 2000`, a `drafts:` block, ...) by key - carried once, refused when two use
 * cases state it differently. entity_types comes first, then the settings in first-seen order.
 */
export function mergeEntityTypes(texts) {
  const byId = new Map();
  const settings = new Map();
  for (const text of texts) {
    const blocks = [];
    let section = null; // 'entity_types' or a setting key
    for (const line of text.replace(/\r/g, '').split('\n')) {
      if (!line.trim()) continue;
      if (!line.startsWith(' ')) {
        if (/^entity_types\[\d+\]:\s*$/.test(line)) { section = 'entity_types'; continue; }
        const key = (line.match(/^([A-Za-z_][\w-]*)\s*:/) ?? [])[1];
        if (!key) throw new Error(`unexpected line in link-analysis.toon: ${line}`);
        section = key;
        settings.set(key, [...(settings.get(key) ?? []), [line]]);
        continue;
      }
      if (section === 'entity_types') {
        if (line.startsWith('  - ')) blocks.push([line]);
        else if (blocks.length) blocks[blocks.length - 1].push(line);
        else throw new Error(`unexpected line in link-analysis.toon: ${line}`);
      } else if (section) {
        const seen = settings.get(section);
        seen[seen.length - 1].push(line);
      } else throw new Error(`unexpected line in link-analysis.toon: ${line}`);
    }
    for (const b of blocks) {
      const id = (b[0].match(/- id:\s*(\S+)/) ?? [])[1];
      if (!id) throw new Error('an entity type without an id');
      const block = b.join('\n');
      if (byId.has(id) && byId.get(id) !== block) throw new Error(`entity type '${id}' is declared differently in two use cases`);
      byId.set(id, block);
    }
  }
  let out = `entity_types[${byId.size}]:\n${[...byId.values()].join('\n')}\n`;
  for (const [key, stated] of settings) {
    const all = stated.map((s) => s.join('\n'));
    if (new Set(all).size > 1) throw new Error(`setting '${key}' is stated differently in two use cases`);
    out += all[0] + '\n';
  }
  return out;
}

/**
 * Merge several copies of one TOON list file (`<name>[n]...:` then its items): each item is keyed by `key(firstLine)`;
 * the same key stated the same way ships once, stated differently is refused. The header (minus its count) must agree.
 */
function mergeList(texts, name, isItemStart, key, what) {
  const byKey = new Map();
  let header = null;
  for (const text of texts) {
    const lines = text.replace(/\r/g, '').split('\n').filter((l) => l.trim());
    const h = (lines.shift() ?? '').replace(/^(\w+)\[\d+\]/, '$1[]');
    if (!h.startsWith(`${name}[]`)) throw new Error(`unexpected first line in ${name}: ${h}`);
    if (header !== null && header !== h) throw new Error(`${name} has a different header in two use cases`);
    header = h;
    const items = [];
    for (const line of lines) {
      if (isItemStart(line)) items.push([line]);
      else if (items.length && line.startsWith('    ')) items[items.length - 1].push(line);
      else throw new Error(`unexpected line in ${name}: ${line}`);
    }
    for (const it of items) {
      const k = key(it[0]);
      const block = it.join('\n');
      if (byKey.has(k) && byKey.get(k) !== block) throw new Error(`${what} '${k}' is defined differently in two use cases`);
      byKey.set(k, block);
    }
  }
  return `${header.replace('[]', `[${byKey.size}]`)}\n${[...byKey.values()].join('\n')}\n`;
}

/** Merge several demo-users.toon files by user id (a tabular `users[n]{id,...}:` list, one row per user). */
export const mergeDemoUsers = (texts) => mergeList(texts, 'users', (l) => /^ {2}\S/.test(l), (l) => l.trim().split(',')[0], 'demo user');

/** Merge several roles.toon files by role name (`roles[n]:` then `  - name: x` blocks). */
export const mergeRoles = (texts) => mergeList(texts, 'roles', (l) => l.startsWith('  - '), (l) => (l.match(/- name:\s*(\S+)/) ?? [])[1], 'role');

/** The joined content of one file several use cases ship. */
export function joinFile(rel, texts) {
  if (rel === MERGED) return mergeEntityTypes(texts);
  if (rel === 'config/demo-users.toon') return mergeDemoUsers(texts);
  if (rel === 'config/roles.toon') return mergeRoles(texts);
  return joinExamples(texts);
}

/** The use-case template ids a recipe names. */
export function recipeUseCases(recipe) {
  const m = recipe.match(/^useCases\[\d+\]:\s*(.+)$/m);
  const ids = m ? m[1].split(',').map((s) => s.trim()).filter(Boolean) : [];
  if (!ids.length) throw new Error('the recipe names no useCases');
  return ids;
}

function zip(stageDir, top, zipPath) {
  rmSync(zipPath, { force: true });
  if (process.platform === 'win32') {
    execFileSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command',
      `Compress-Archive -Path '${join(stageDir, top).replace(/'/g, "''")}' -DestinationPath '${zipPath.replace(/'/g, "''")}'`], { stdio: 'inherit' });
  } else {
    execFileSync('zip', ['-qr', zipPath, top], { cwd: stageDir, stdio: 'inherit' });
  }
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const version = opts.version || reactorVersion();
  const minProduct = opts.minProduct || version.split(/[-+]/)[0];
  const listed = (dir) => git(['ls-tree', '-r', '--name-only', 'HEAD', `${dir}/`], { encoding: 'utf8' })
    .split('\n').filter(Boolean).map((p) => p.slice(dir.length + 1));
  const recipePath = `${INDUSTRIES}/${opts.id}.toon`;
  let files, show, templateToon;
  if (git(['ls-tree', '--name-only', 'HEAD', recipePath], { encoding: 'utf8' }).trim()) {
    // An industry Space: compose it from the use-case templates its recipe names.
    const recipe = git(['show', `HEAD:${recipePath}`], { encoding: 'utf8' });
    const byTemplate = recipeUseCases(recipe).map((tpl) => ({ tpl, files: listed(`${TEMPLATES}/${tpl}`) }));
    const blob = (t, rel) => git(['rev-parse', `HEAD:${TEMPLATES}/${t}/${rel}`], { encoding: 'utf8' }).trim();
    const owner = composeSources(byTemplate, (rel, a, b) => blob(a, rel) === blob(b, rel));
    files = [...owner.keys()];
    templateToon = recipe;
    // link-analysis.toon from ONE use case ships verbatim (it may hold more than entity_types, as la-claims' does);
    // from several it is merged: entity_types by id, other top-level settings by key (mergeEntityTypes); the roster by
    // user id and role name (mergeDemoUsers, mergeRoles).
    const text = (t, rel) => git(['show', `HEAD:${TEMPLATES}/${t}/${rel}`], { encoding: 'utf8' });
    show = (rel) => {
      if (!isJoined(rel)) return git(['show', `HEAD:${TEMPLATES}/${owner.get(rel)}/${rel}`]);
      const from = owner.get(rel);
      if (from.length === 1) return git(['show', `HEAD:${TEMPLATES}/${from[0]}/${rel}`]);
      const texts = from.map((t) => text(t, rel));
      return Buffer.from(joinFile(rel, texts));
    };
    console.log(`  composing ${opts.id} from ${byTemplate.map((b) => b.tpl).join(', ')}`);
  } else {
    const tplDir = `${TEMPLATES}/${opts.id}`;
    files = listed(tplDir);
    if (!files.includes('template.toon')) throw new Error(`no committed ${tplDir}/template.toon or ${recipePath} - not a Space template`);
    show = (rel) => git(['show', `HEAD:${tplDir}/${rel}`]);
    templateToon = show('template.toon').toString('utf8');
  }
  const p = plan(opts.id, files);

  const work = mkdtempSync(join(tmpdir(), 'la-showcase-'));
  try {
    const spaceStage = join(work, 'space'), dataStage = join(work, 'data');
    for (const { from, to, toon } of p.space) {
      const target = join(spaceStage, to);
      mkdirSync(dirname(target), { recursive: true });
      const bytes = show(from);
      writeFileSync(target, toon ? bytes.toString('utf8').replaceAll('${SPACE}', opts.id) : bytes);
    }
    writeFileSync(join(spaceStage, opts.id, 'space.toon'), spaceToon(templateToon, opts.id, minProduct));
    writeFileSync(join(spaceStage, opts.id, 'config', 'egress.toon'), egressToon());
    for (const { from, to } of p.data) {
      const target = join(dataStage, to);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, show(from));
    }
    mkdirSync(opts.out, { recursive: true });
    const spaceZip = join(opts.out, `inspecto-la-space-${opts.id}-${version}.zip`);
    const dataZip = join(opts.out, `inspecto-la-data-${opts.id}-${version}.zip`);
    zip(spaceStage, opts.id, spaceZip);
    zip(dataStage, opts.id, dataZip);
    console.log(`✓ ${opts.id} ${version}: ${p.space.length + 2} Space file(s) -> ${spaceZip}`);
    console.log(`  ${p.data.length} data file(s) -> ${dataZip}`);
    console.log(`  min_product_version: ${minProduct}`);
    console.log(`  install: unzip the Space into <parent>/inspecto-la-spaces/, the data into <parent>/inspecto-la-data/, then start`);
    console.log(`  the product with SPACES_ROOT=../inspecto-la-spaces SPACES_DATA_ROOT=../inspecto-la-data`);
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (e) {
    console.error(`✗ package-la-showcase: ${e.message}`);
    process.exit(1);
  }
}
