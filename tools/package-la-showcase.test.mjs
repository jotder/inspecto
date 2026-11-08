// Unit tests for tools/package-la-showcase.mjs (LA-SHOWCASE-DIST-1): the Space / data split and the space.toon it writes.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { egressToon, parseArgs, plan, spaceToon, toonValue } from './package-la-showcase.mjs';

test('the Space ships an empty egress allowlist, as a Space created from a template gets', () => {
  assert.match(egressToon(), /^allow\[0\]:\ncreatedAt: "\d{4}-/);
});

test('config goes to the Space zip, data to the data zip, gallery files to neither', () => {
  const p = plan('la-claims', ['template.toon', 'config/claims/claim_pipeline.toon', 'config/roles.toon',
    'data/inbox/claim/claim.csv', 'portal/partner-portal.html']);
  assert.deepEqual(p.space.map((f) => f.to), ['la-claims/config/claims/claim_pipeline.toon', 'la-claims/config/roles.toon']);
  assert.deepEqual(p.data.map((f) => f.to), ['la-claims/data/inbox/claim/claim.csv']);
  assert.ok(p.space.every((f) => f.toon));
});

test('the Space zip never carries data/ (a Space folder holding data/ is not booted beside a data root)', () => {
  const p = plan('x', ['config/a.toon', 'data/inbox/f/a.csv', 'data/staged/f/b.csv']);
  assert.ok(p.space.every((f) => !f.to.startsWith('x/data/')));
});

test('a template with no data or no config is refused', () => {
  assert.throws(() => plan('x', ['config/a.toon']), /no committed data/);
  assert.throws(() => plan('x', ['data/inbox/a.csv']), /no committed config/);
});

test('space.toon takes name and tagline as SpaceManager.createFromTemplate does, plus min_product_version', () => {
  const tpl = 'name: Link Analysis claims showcase\ntagline: "A seeded \\"claims\\" demo"\nminEdition: advanced\n';
  const out = spaceToon(tpl, 'la-claims', '4.0.0');
  assert.match(out, /^display_name: "Link Analysis claims showcase"$/m);
  assert.match(out, /^description: "A seeded \\"claims\\" demo"$/m);
  assert.match(out, /^min_product_version: "4.0.0"$/m);
  assert.match(out, /^created_at: "\d{4}-/m);
});

test('toonValue reads bare and quoted values and misses absent keys', () => {
  assert.equal(toonValue('name: Bare value\n', 'name'), 'Bare value');
  assert.equal(toonValue('tagline: "q"\n', 'tagline'), 'q');
  assert.equal(toonValue('name: x\n', 'tagline'), '');
});

test('arguments: id required and validated', () => {
  assert.equal(parseArgs(['la-claims', '--version', '4.0.0']).version, '4.0.0');
  assert.throws(() => parseArgs([]), /usage/);
  assert.throws(() => parseArgs(['Bad_Id']), /not a Space id/);
});

// LA-INDUSTRY-SPACES-1: industry Spaces composed from use-case templates.
import { composeSources, mergeEntityTypes, recipeUseCases, MERGED } from './package-la-showcase.mjs';

test('an industry recipe names its use-case templates', () => {
  assert.deepEqual(recipeUseCases('name: Banking\nuseCases[2]: la-card,la-trade\n'), ['la-card', 'la-trade']);
  assert.throws(() => recipeUseCases('name: Empty\n'), /names no useCases/);
});

test('composing takes each file from one use case and refuses a path two use cases ship', () => {
  const owner = composeSources([
    { tpl: 'la-card', files: ['template.toon', 'config/card/a.toon', MERGED, 'data/inbox/card/x.csv', 'portal/p.html'] },
    { tpl: 'la-trade', files: ['template.toon', 'config/trade/b.toon', MERGED] },
  ]);
  assert.equal(owner.get('config/card/a.toon'), 'la-card');
  assert.equal(owner.get('config/trade/b.toon'), 'la-trade');
  assert.deepEqual(owner.get(MERGED), ['la-card', 'la-trade']);
  assert.ok(!owner.has('template.toon') && !owner.has('portal/p.html'));
  assert.throws(() => composeSources([
    { tpl: 'la-card', files: ['template.toon', 'config/company/c.toon'] }, { tpl: 'la-trade', files: ['template.toon', 'config/company/c.toon'] },
  ]), /shipped by both la-card and la-trade/);
});

test('entity types merge by id; the same id declared differently is refused', () => {
  const a = 'entity_types[1]:\n  - id: party\n    label: Party\n';
  const b = 'entity_types[2]:\n  - id: party\n    label: Party\n  - id: box\n    label: Container\n';
  assert.equal(mergeEntityTypes([a, b]), 'entity_types[2]:\n  - id: party\n    label: Party\n  - id: box\n    label: Container\n');
  assert.throws(() => mergeEntityTypes([a, 'entity_types[1]:\n  - id: party\n    label: Other\n']), /declared differently/);
});

test('top-level settings (four-eyes, drafts) are carried once; stated differently in two use cases is refused', () => {
  const adv = 'four_eyes_fan_out_above: 2000\ndrafts:\n  max_open: 10\nentity_types[1]:\n  - id: card\n    label: Card\n';
  const plain = 'entity_types[1]:\n  - id: box\n    label: Container\n';
  assert.equal(mergeEntityTypes([plain, adv]),
    'entity_types[2]:\n  - id: box\n    label: Container\n  - id: card\n    label: Card\nfour_eyes_fan_out_above: 2000\ndrafts:\n  max_open: 10\n');
  assert.equal(mergeEntityTypes([adv, adv]).match(/four_eyes/g).length, 1, 'the same setting twice ships once');
  assert.throws(() => mergeEntityTypes([adv, 'four_eyes_fan_out_above: 500\n' + plain]), /'four_eyes_fan_out_above' is stated differently/);
  assert.throws(() => mergeEntityTypes([adv, 'drafts:\n  max_open: 3\n' + plain]), /'drafts' is stated differently/);
});

test('every committed industry recipe composes from the real templates (link-analysis.toon and the roster merge)', async () => {
  const { ROSTERS, joinFile } = await import('./package-la-showcase.mjs');
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const filesOf = (dir, rel = '') => readdirSync(join(dir, rel), { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? filesOf(dir, `${rel}${e.name}/`) : [`${rel}${e.name}`]);
  for (const r of readdirSync(join(root, 'spaces', '_industries')).filter((f) => f.endsWith('.toon'))) {
    const tpls = recipeUseCases(readFileSync(join(root, 'spaces', '_industries', r), 'utf8'));
    const tplDir = (t) => join(root, 'spaces', '_templates', t);
    // every file comes from one use case, as the packer composes it (the working tree here, HEAD in the packer)
    const owner = composeSources(tpls.map((tpl) => ({ tpl, files: filesOf(tplDir(tpl)).filter((f) => f === 'template.toon' || f.startsWith('config/')) })),
      (rel, a, b) => readFileSync(join(tplDir(a), rel)).equals(readFileSync(join(tplDir(b), rel))));
    for (const rel of [MERGED, ...ROSTERS]) {
      const texts = (owner.get(rel) ?? []).map((t) => readFileSync(join(tplDir(t), rel), 'utf8'));
      if (texts.length > 1) assert.ok(joinFile(rel, texts).length > 0, `${r}: ${rel}`);
    }
  }
});

test('the roster merges by user id and role name; a shared id defined differently is refused', async () => {
  const { mergeDemoUsers, mergeRoles } = await import('./package-la-showcase.mjs');
  const head = 'users[2]{id,displayName,title,roles}:\n';
  const admin = '  admin,Demo Admin,Platform administrator,admin;fraud-analyst;la-settings\n';
  const card = head + admin + '  card.analyst,Demo Card Fraud Analyst,Fraud analyst (lead),card-analyst\n';
  const own = head + admin + '  ownership.analyst,Demo KYC Analyst,KYC analyst (lead),ownership-analyst\n';
  assert.equal(mergeDemoUsers([own, card]), 'users[3]{id,displayName,title,roles}:\n' + admin
    + '  ownership.analyst,Demo KYC Analyst,KYC analyst (lead),ownership-analyst\n  card.analyst,Demo Card Fraud Analyst,Fraud analyst (lead),card-analyst\n');
  assert.throws(() => mergeDemoUsers([card, head + '  admin,Demo Admin,Platform administrator,admin;card-analyst\n']), /demo user 'admin' is defined differently/);
  const viewer = '  - name: la-viewer\n    capabilities[0]:\n';
  const a = 'roles[2]:\n  - name: card-analyst\n    capabilities[1]: canWorkIncidents\n' + viewer;
  const b = 'roles[2]:\n  - name: ownership-analyst\n    capabilities[1]: canWorkIncidents\n' + viewer;
  assert.equal(mergeRoles([a, b]), 'roles[3]:\n  - name: card-analyst\n    capabilities[1]: canWorkIncidents\n' + viewer
    + '  - name: ownership-analyst\n    capabilities[1]: canWorkIncidents\n');
  assert.throws(() => mergeRoles([a, 'roles[1]:\n  - name: la-viewer\n    capabilities[1]: canWorkIncidents\n']), /role 'la-viewer' is defined differently/);
});

test('a recipe naming a template that does not exist is refused, not skipped', () => {
  assert.throws(() => composeSources([{ tpl: 'la-utility', files: ['template.toon', 'config/a.toon'] }, { tpl: 'la-utlity', files: [] }]),
    /'la-utlity' has no committed template.toon/);
});

test('a path two use cases ship with the same bytes is one file; different bytes are refused', () => {
  const t = [{ tpl: 'a', files: ['template.toon', 'config/shared/x.toon'] }, { tpl: 'b', files: ['template.toon', 'config/shared/x.toon'] }];
  assert.equal(composeSources(t, () => true).get('config/shared/x.toon'), 'a');
  assert.throws(() => composeSources(t, () => false), /shipped by both a and b/);
});

test('every use case may ship its own config/examples/*.md; several are joined, one ships as is', async () => {
  const { isJoined, joinExamples } = await import('./package-la-showcase.mjs');
  assert.ok(isJoined('config/examples/sla-escalation-examples.md') && isJoined(MERGED) && !isJoined('config/examples/x.toon') && !isJoined('config/shared/x.toon'));
  const owner = composeSources([
    { tpl: 'a', files: ['template.toon', 'config/examples/sla.md'] }, { tpl: 'b', files: ['template.toon', 'config/examples/sla.md'] },
  ]);
  assert.deepEqual(owner.get('config/examples/sla.md'), ['a', 'b']);
  assert.equal(joinExamples(['# A\nx\n', '# B\ny']), '# A\nx\n\n---\n\n# B\ny\n');
});
