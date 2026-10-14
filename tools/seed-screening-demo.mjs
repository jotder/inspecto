#!/usr/bin/env node
// Demo bootstrap for the Screening add-on (SCREENING-1), over the AML Space Template. SYNTHETIC names only.
//   node tools/seed-screening-demo.mjs --spaces inspecto-demo/spaces [--base http://127.0.0.1:8126]
// Step 1 (no server needed): writes <spaces>/screening-boot/config/demo-users.toon so the Demo Auth sign-in picker is not empty.
// Step 2 (server up, run again): signs in as `admin`, creates the Space `screening-demo` from the `aml` template, copies the roster
//   into it, drops a party register (the template sample + planted names) into data/inbox/aml_parties, creates the two Entity Lists
//   `aml_sanctions` / `aml_pep` with synthetic entries, and arms nothing else: the walk-through enables the Job.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeClient } from './lib/la-client.mjs';

export const ROSTER = `users[5]{id,displayName,title,roles}:
  admin,Demo Admin,Platform administrator,admin;super
  compliance.officer,Demo Compliance Officer,Screening reviewer,operations
  compliance.manager,Demo Compliance Manager,Second reviewer,power
  ops.analyst,Demo Operations Analyst,Operations analyst,operations
  ra.viewer,Demo Viewer,Non-privileged viewer,business
`;

/** Planted register rows (party_id, full_name, national_id): exact, fuzzy, transliterated, identifier-only and clean. */
export const PLANTED = [
  ['P900001', 'Viktor Dmitrenko Sorokin', 'NID-7700001'], // exact (sanctions)
  ['P900002', 'Viktor Dmitrenco Sorokin', 'NID-7700002'], // fuzzy (one letter)
  ['P900003', 'Kadir Almansuri', 'NID-7700003'],          // transliterated (Qadhir Almansouri)
  ['P900004', 'Husain Alrashidi', 'NID-7700004'],         // transliterated (Hussein Al Rashidi)
  ['P900005', 'Ordinary Customer', 'NID-9000001'],        // identifier-only match (national id on the list)
  ['P900006', 'Maren Velloso', 'NID-7700006'],            // exact (PEP list)
  ['P900007', 'Marena Velloso', 'NID-7700007'],           // fuzzy (PEP list)
  ['P900008', 'Tobias Lindqvist', 'NID-7700008'],         // clean
];
export const SANCTIONS = ['Viktor Dmitrenko Sorokin', 'Qadhir Almansouri', 'Hussein Al Rashidi', 'Ilse Brandvold Novak', 'NID-9000001'];
export const PEP = ['Maren Velloso', 'Joaquim Estrada Ferreira'];

export function register(templateDir) {
  const f = path.join(templateDir, 'data', 'samples', 'aml_parties', 'AML_PARTIES_20260707.csv');
  const lines = fs.readFileSync(f, 'utf8').trimEnd().split(/\r?\n/);
  return lines.concat(PLANTED.map(([id, name, nid]) => `2026-07-07,${id},${name},${nid},XD`)).join('\n') + '\n';
}

export async function run(spaces, base) {
  const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
  const boot = path.join(spaces, 'screening-boot', 'config');
  fs.mkdirSync(boot, { recursive: true });
  fs.writeFileSync(path.join(boot, 'demo-users.toon'), ROSTER);
  if (!base) return { step: 'bootstrap' };
  const call = makeClient({ base, space: 'screening-demo' });
  const tok = await (await fetch(`${base}/api/v1/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: 'demo:admin', codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) })).json();
  const bearer = (tok.data ?? tok).accessToken;
  const r = await fetch(`${base}/api/v1/spaces`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${bearer}` },
    body: JSON.stringify({ id: 'screening-demo', display_name: 'Screening demo', template: 'aml' }) });
  if (![200, 201, 409].includes(r.status)) throw new Error(`create Space refused (${r.status}): ${await r.text()}`);
  const space = path.join(spaces, 'screening-demo');
  fs.writeFileSync(path.join(space, 'config', 'demo-users.toon'), ROSTER);
  const inbox = path.join(space, 'data', 'inbox', 'aml_parties');
  fs.mkdirSync(inbox, { recursive: true });
  fs.writeFileSync(path.join(inbox, 'AML_PARTIES_20260707.csv'), register(path.join(root, 'spaces', '_templates', 'aml')));
  const out = [];
  // The default Entity Types are all name-masking or id-shaped; a sanctions list is public data, so the Space states an unmasked `party`
  // type (the stated list replaces the defaults wholesale, so it is the defaults plus `party`). The PEP list stays on the masked
  // `subscriber` type on purpose: it is the masking beat.
  const cur = await call('admin', 'GET', '/settings/link-analysis');
  const types = cur.data.entityTypesInForce.filter((t) => t.id !== 'party').concat([{ id: 'party', label: 'Party', normaliser: 'default', masked: false, classifications: ['PARTY'] }]);
  const put = await call('admin', 'PUT', '/settings/link-analysis', { ...Object.fromEntries(Object.entries(cur.data).filter(([k]) => !k.endsWith('InForce') && k !== 'entityTypes')), entityTypes: types });
  out.push(`entity types: PUT ${put.status}`);
  for (const [id, title, purpose, entries, entityType] of [['aml_sanctions', 'AML sanctions (synthetic)', 'block', SANCTIONS, 'party'], ['aml_pep', 'AML politically exposed (synthetic)', 'watch', PEP, 'subscriber']]) {
    const c = await call('admin', 'POST', '/entity-lists', { id, title, purpose, entityType, reason: 'demo seed' });
    const m = await call('admin', 'POST', `/entity-lists/${id}/members`, { add: entries, reason: 'demo seed' });
    out.push(`${id}: create ${c.status}, members ${m.status}`);
  }
  return { step: 'seeded', out };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const a = process.argv.slice(2);
  const get = (k) => (a.includes(k) ? a[a.indexOf(k) + 1] : undefined);
  if (!get('--spaces')) { console.error('usage: seed-screening-demo.mjs --spaces <dir> [--base http://127.0.0.1:8126]'); process.exit(2); }
  const r = await run(get('--spaces'), get('--base'));
  console.log(r.step === 'bootstrap' ? 'Roster written. Boot the bundle, then run again with --base.' : r.out.join('\n'));
}
