// node --test tools/la-demo-roster.test.mjs - every Link Analysis showcase Space Template signs in with the SAME five Demo Users.
//
// Operator 2026-11-16: "only 5 demo users - Admin, Analyst, Manager, Reviewer and Outsider, that works for all spaces". DemoUsers
// refuses a user id defined differently in two attached Spaces (409 ROSTER_CONFLICT), so the five rows must read byte-for-byte the
// same in every template; each Space then decides what the shared role names (la-analyst, la-manager, la-viewer) may do in its
// own roles.toon, which must therefore define every role the roster names.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const T = path.join(root, 'spaces', '_templates');
const templates = fs.readdirSync(T).filter((d) => d.startsWith('la-') && fs.existsSync(path.join(T, d, 'config', 'demo-users.toon')));
const roster = (t) => fs.readFileSync(path.join(T, t, 'config', 'demo-users.toon'), 'utf8').replace(/\r\n/g, '\n');

test('every LA showcase template has a roster', () => {
  assert.ok(templates.length >= 18, templates.join(', '));
});

test('the roster is the five shared Demo Users, identical in every template', () => {
  const rows = roster('la-showcase').trim().split('\n').slice(1).map((l) => l.trim().split(',')[0]);
  assert.deepEqual(rows, ['admin', 'analyst', 'manager', 'reviewer', 'outsider']);
  for (const t of templates) assert.equal(roster(t), roster('la-showcase'), `${t}/config/demo-users.toon differs from la-showcase`);
});

test("every role the roster names is defined in each template's roles.toon", () => {
  const named = new Set(roster('la-showcase').trim().split('\n').slice(1).flatMap((l) => l.trim().split(',')[3].split(';')));
  named.delete('admin');   // the built-in administrator role, not a roles.toon entry
  for (const t of templates) {
    const defined = new Set([...fs.readFileSync(path.join(T, t, 'config', 'roles.toon'), 'utf8').matchAll(/- name:\s*(\S+)/g)].map((m) => m[1]));
    for (const r of named) assert.ok(defined.has(r), `${t}: roles.toon does not define ${r}`);
  }
});
