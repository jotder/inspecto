// LA-FULL-CYCLE-TEST-1 finding 13: a showcase Investigation's name, purpose, reasons, annotations and Draft step name roles,
// never an entity id. Masked is the default (D3), and every Investigation text is masked where it names a masked id, so a
// name such as "Mule hub MULE-HUB-01" reached the demo as "Mule hub masked:6021…": a story nobody can read. The id universe
// of each seed is every id its plan sends (an op's `ids`, a list's `add`) plus the ids its exported constants hold.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const SEEDS = fs.readdirSync(here).filter((f) => /^seed-la-.+\.mjs$/.test(f) && !f.endsWith('.test.mjs'));
/** Exported constants that name the seed's own objects or settings, not entities. */
const NOT_IDS = new Set(['INV', 'DATASET', 'SPACE', 'ENTITY_TYPE', 'TYPE', 'REVIEW_INV', 'REVIEW_DATASET', 'GAP_INV', 'RULE',
  'PENDING_EXPAND', 'DRAFT_STEP', 'GAP_WINDOW', 'WINDOW', 'DAY', 'DAYS']);

const strings = (v, out = []) => {
  if (typeof v === 'string') out.push(v);
  else if (Array.isArray(v)) v.forEach((x) => strings(x, out));
  else if (v && typeof v === 'object') Object.values(v).forEach((x) => strings(x, out));
  return out;
};

async function findings(file) {
  const m = await import(pathToFileURL(path.join(here, file)).href);
  const plan = m.buildPlan();
  const ids = new Set();
  for (const s of plan) for (const k of ['ids', 'add']) if (Array.isArray(s.body?.[k])) s.body[k].forEach((x) => ids.add(String(x)));
  for (const [k, v] of Object.entries(m)) if (k === k.toUpperCase() && !NOT_IDS.has(k)) strings(v).forEach((x) => ids.add(x));
  for (const x of [...ids]) if (x.length < 3) ids.delete(x);
  const texts = [];
  for (const s of plan) {
    const b = s.body ?? {};
    if (s.path === '/inv/investigations') texts.push(['title', b.title], ['purpose', b.purpose]);
    if (/\/inv\/investigations\/[^/]+\/ops$/.test(s.path)) for (const k of ['reason', 'note', 'label']) texts.push([`${b.op} ${k}`, b[k]]);
    if (/\/inv\/investigations\/[^/]+\/references$/.test(s.path)) texts.push(['reference label', b.label]);
  }
  for (const k of ['reason', 'note', 'label']) texts.push([`Draft step ${k}`, m.DRAFT_STEP?.[k]]);
  const out = [];
  for (const [where, text] of texts)
    for (const id of ids) if (typeof text === 'string' && text.includes(id)) out.push(`${where} names ${id}: "${text}"`);
  return out;
}

test('every LA showcase seed is covered', () => {
  assert.ok(SEEDS.length >= 17, SEEDS.join(', '));
});

for (const file of SEEDS)
  test(`${file}: its Investigation text names roles, never an entity id`, async () => {
    assert.deepEqual(await findings(file), []);
  });
