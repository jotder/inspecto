// node --test tools/la-demo-roster.test.mjs - every Link Analysis showcase Space Template signs in with the SAME five Demo Users.
//
// Operator 2026-11-16: "only 5 demo users - Admin, Analyst, Manager, Reviewer and Outsider, that works for all spaces". DemoUsers
// refuses a user id defined differently in two attached Spaces (409 ROSTER_CONFLICT), so the five rows must read byte-for-byte the
// same in every template; each Space then decides what the shared role names (la-analyst, la-manager, la-viewer) may do in its
// own roles.toon, which must therefore define every role the roster names.
//
// Operator 2026-11-18: the four kinds mean the same in every LA Space (la-analyst, la-manager, la-viewer and la-settings grant the
// same capabilities everywhere), and `outsider` signs in but sees nothing: it holds `no-access`, a role no roles.toon defines, and
// every LA demo Space is closed (`admission: closed` in space.toon), so SpaceAdmission admits it to none of them.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const T = path.join(root, 'spaces', '_templates');
const templates = fs.readdirSync(T).filter((d) => d.startsWith('la-') && fs.existsSync(path.join(T, d, 'config', 'demo-users.toon')));
const roster = (t) => fs.readFileSync(path.join(T, t, 'config', 'demo-users.toon'), 'utf8').replace(/\r\n/g, '\n');
const rows = (t) => roster(t).trim().split('\n').slice(1).map((l) => l.trim().split(','));

/** One template's roles.toon as role name -> sorted capabilities. */
function roles(t) {
  const out = new Map();
  let name = null;
  for (const line of fs.readFileSync(path.join(T, t, 'config', 'roles.toon'), 'utf8').split(/\r?\n/)) {
    const n = /^\s*- name:\s*(\S+)/.exec(line);
    if (n) { name = n[1]; out.set(name, []); continue; }
    const c = /^\s*capabilities\[\d+\]:\s*(.*)$/.exec(line);
    if (c && name) out.set(name, c[1].split(',').map((s) => s.trim()).filter(Boolean).sort());
  }
  return out;
}

const SHARED = {
  'la-analyst': ['canManageIncidents', 'canWorkIncidents', 'canRunLinkGraphAnalysis', 'canBuildLinkIndex', 'canAuthorAlertRules',
    'canRevealLinkEntities'].sort(),
  'la-manager': ['canManageIncidents', 'canWorkIncidents', 'canApproveLinkExpansions', 'canOperateRuns'].sort(),
  'la-viewer': [],
  'la-settings': ['canAuthorWorkbench'],
};

test('every LA showcase template has a roster', () => {
  assert.ok(templates.length >= 18, templates.join(', '));
});

test('the roster is the five shared Demo Users, identical in every template', () => {
  assert.deepEqual(rows('la-showcase').map((r) => r[0]), ['admin', 'analyst', 'manager', 'reviewer', 'outsider']);
  for (const t of templates) assert.equal(roster(t), roster('la-showcase'), `${t}/config/demo-users.toon differs from la-showcase`);
});

test('each Demo User holds the role of its kind', () => {
  const byId = Object.fromEntries(rows('la-showcase').map((r) => [r[0], r[3]]));
  assert.deepEqual(byId, {
    admin: 'admin;fraud-analyst;la-settings', analyst: 'la-analyst', manager: 'la-manager', reviewer: 'la-viewer', outsider: 'no-access',
  });
});

test("every role the roster names is defined in each template's roles.toon, except the outsider's", () => {
  const named = new Set(rows('la-showcase').filter((r) => r[0] !== 'outsider').flatMap((r) => r[3].split(';')));
  named.delete('admin');   // the built-in administrator role, not a roles.toon entry
  for (const t of templates) {
    const defined = roles(t);
    for (const r of named) assert.ok(defined.has(r), `${t}: roles.toon does not define ${r}`);
  }
});

test('the outsider holds a role no LA template defines, so a closed LA Space admits it nowhere', () => {
  const outsider = rows('la-showcase').find((r) => r[0] === 'outsider')[3].split(';');
  for (const t of fs.readdirSync(T).filter((d) => fs.existsSync(path.join(T, d, 'config', 'roles.toon'))))
    for (const r of outsider) assert.ok(!roles(t).has(r), `${t}: roles.toon defines ${r}, which would admit the outsider`);
});

test('la-analyst, la-manager, la-viewer and la-settings grant the same capabilities in every template', () => {
  for (const t of templates) {
    const defined = roles(t);
    for (const [role, caps] of Object.entries(SHARED)) assert.deepEqual(defined.get(role), caps, `${t}: ${role}`);
  }
});

test('every LA scaffolder and the showcase packager write a closed Space', () => {
  const tools = path.join(root, 'tools');
  const writers = fs.readdirSync(tools).filter((f) => f.startsWith('seed-la-') && f.endsWith('.mjs') && !f.endsWith('.test.mjs'));
  assert.ok(writers.length >= 17, writers.join(', '));
  for (const f of writers) {
    const lines = fs.readFileSync(path.join(tools, f), 'utf8').split('\n').filter((l) => l.includes("'space.toon'") && l.includes('writeFileSync'));
    assert.ok(lines.length >= 1, `${f} writes no space.toon`);
    for (const l of lines) assert.ok(l.includes('admission: closed'), `${f}: its space.toon is not closed`);
  }
  // la-daily-feed has no seeder: the preview bake writes its space.toon itself
  const bake = fs.readFileSync(path.join(root, 'scripts', 'preview-la', 'bake-all.ps1'), 'utf8').split('\n').filter((l) => l.includes("'space.toon'"));
  assert.ok(bake.length >= 1 && bake.every((l) => l.includes('admission: closed')), 'bake-all.ps1 writes la-daily-feed open');
});
