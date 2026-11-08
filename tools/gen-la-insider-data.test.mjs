// Determinism and planted facts of tools/gen-la-insider-data.py. Run: node --test tools/gen-la-insider-data.test.mjs
// Runs the generator (it writes under spaces/_templates/la-insider/data and the ground truth beside itself) and asserts every
// file is byte-identical to what is committed, then checks each story's facts in the files against the ground truth: a leaver
// who reads 20x their normal customer records after notice and mails 1.2 GB to a personal domain; 4 operations staff given
// payroll and treasury by EMP-0007 with no ticket, one of whom changes a vendor's bank details; two account managers approving
// each other 40 times just under the limit, mostly at weekends - and that each look-alike has the innocent shape. That the
// stories LINK (and the look-alikes do not) over the real link Datasets is InsiderLinksJobGoldenTest (inspecto-engine).
// Skipped when python is not on the PATH.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const DATA = join(ROOT, 'spaces', '_templates', 'la-insider', 'data');
const TRUTH = join(ROOT, 'tools', 'gen-la-insider-data.ground-truth.json');
const python = ['python3', 'python'].find((p) => spawnSync(p, ['--version']).status === 0);

function files(dir = DATA, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) files(p, out);
    else out.push(p);
  }
  return out;
}
function snapshot() {
  const out = {};
  for (const p of [...files(), TRUTH]) out[p.slice(ROOT.length + 1).replaceAll('\\', '/')] = createHash('sha256').update(readFileSync(p, 'utf8').replace(/\r\n/g, '\n')).digest('hex');
  return out;
}
/** Every row of a feed (landed, staged, or both) as objects. No field holds a comma. */
function feed(name, box = '') {
  const rows = [];
  for (const p of files().filter((f) => f.replaceAll('\\', '/').includes(`${box}/${name}/`)).sort()) {
    const [head, ...lines] = readFileSync(p, 'utf8').trim().split('\n');
    const cols = head.split(',');
    for (const l of lines) {
      const cells = l.split(',');
      rows.push(Object.fromEntries(cols.map((c, i) => [c, cells[i] ?? ''])));
    }
  }
  return rows;
}
const truth = JSON.parse(readFileSync(TRUTH, 'utf8'));
const LANDED_END = '2026-09-30';
const emp = Object.fromEntries(feed('employee').map((e) => [e.employee_id, e]));
const grants = feed('access_grant', '/inbox');
const access = feed('access_daily', '/inbox');
const mail = feed('email_external', '/inbox');
const expenses = feed('expense', '/inbox');
const changes = feed('vendor_change', '/inbox');
const FINANCE = new Set(truth.finance_systems);
const weekend = (ts) => [0, 6].includes(new Date(ts.slice(0, 10) + 'T00:00:00Z').getUTCDay());
const reads = (e, s) => access.filter((a) => a.employee_id === e && a.system === s);
const median = (xs) => { const v = [...xs].sort((a, b) => a - b); return (v[(v.length - 1) >> 1] + v[v.length >> 1]) / 2; };

test('generator twice = the committed bytes; six feeds; under 10 MB', { skip: !python }, () => {
  const before = snapshot();
  const feeds = new Set(Object.keys(before).filter((k) => k.includes('/data/')).map((k) => k.split('/').at(-2)));
  assert.deepEqual([...feeds].sort(), ['access_daily', 'access_grant', 'email_external', 'employee', 'expense', 'vendor_change']);
  assert.ok(files().reduce((n, f) => n + statSync(f).size, 0) < 10_000_000, 'the corpus is under 10 MB');
  for (let i = 0; i < 2; i++) {
    assert.equal(spawnSync(python, [join(ROOT, 'tools', 'gen-la-insider-data.py')], { encoding: 'utf8' }).status, 0);
    assert.deepEqual(snapshot(), before, `run ${i + 1} changed bytes`);
  }
});

test('the corpus: 3 000 employees known by id only, daily counts not records, October 2026 staged, invented domains', () => {
  assert.equal(Object.keys(emp).length, 3000);
  assert.deepEqual(Object.keys(feed('employee')[0]), ['employee_id', 'dept', 'role', 'manager_id', 'hired_at', 'role_since', 'notice_given_at', 'left_at'], 'no names');
  assert.equal(grants.length, truth.counts.access_grant_landed);
  assert.equal(access.length, truth.counts.access_daily_landed);
  assert.equal(mail.length, truth.counts.email_external_landed);
  assert.equal(expenses.length, truth.counts.expense_landed);
  assert.equal(changes.length, truth.counts.vendor_change_landed);
  for (const f of ['access_daily', 'email_external', 'expense'])
    assert.equal(files().filter((p) => p.replaceAll('\\', '/').includes(`/inbox/${f}/`)).length, 12, `twelve monthly ${f} files`);
  const keys = new Set(access.map((a) => `${a.employee_id}|${a.system}|${a.read_date}`));
  assert.equal(keys.size, access.length, 'one count per employee, system and day: never record-level');
  assert.deepEqual(Object.keys(access[0]), ['employee_id', 'system', 'read_date', 'records_read']);
  assert.equal(new Set(mail.map((m) => `${m.employee_id}|${m.domain}|${m.sent_date}`)).size, mail.length, 'mail aggregated per employee, domain and day');
  assert.ok(mail.every((m) => m.domain.endsWith('.example')), 'every mail domain is invented');
  assert.ok(feed('access_daily', '/staged').every((a) => a.read_date.startsWith('2026-10')), 'the staged reads are October');
  assert.ok([...access, ...mail].every((r) => (r.read_date ?? r.sent_date) <= LANDED_END));
  const held = new Set(feed('access_grant').map((g) => `${g.employee_id}|${g.system}`));
  assert.ok(feed('access_daily').every((a) => held.has(`${a.employee_id}|${a.system}`)), 'every read is on a system the person holds');
  assert.equal(grants.filter((g) => FINANCE.has(g.system) && !g.ticket_id).length, 8, 'only the story grants to a finance system lack a ticket');
});

test('leaver exfiltration: 20x the normal customer reads after notice and 1.2 GB to a personal domain; the engineer\'s ticketed bulk read', () => {
  const l = truth.leaver_exfiltration;
  const p = emp[l.leaver];
  assert.equal(p.notice_given_at, l.notice_given_at);
  assert.equal(p.left_at, l.left_at);
  const crm = reads(l.leaver, 'CRM');
  const normal = median(crm.map((a) => +a.records_read));
  assert.equal(normal, l.daily_median);
  const spike = crm.filter((a) => l.spike_days.includes(a.read_date));
  assert.equal(spike.length, 15, 'every working day of the three weeks after notice');
  assert.ok(spike.every((a) => +a.records_read >= 18 * normal && a.read_date > l.notice_given_at), 'about 20x normal, after notice');
  assert.ok(crm.every((a) => a.read_date < l.left_at));
  const after = mail.filter((m) => m.employee_id === l.leaver && m.sent_date >= l.notice_given_at);
  assert.ok(after.every((m) => m.domain === l.domain) && truth.personal_domains.includes(l.domain));
  assert.equal(Math.round(after.reduce((n, m) => n + +m.attachment_mb, 0) * 10) / 10, l.after_notice_mb);
  assert.ok(l.after_notice_mb >= 1200, '1.2 GB');
  assert.ok(!grants.some((g) => g.employee_id === l.leaver && g.system === 'CUSTARCH'));
  const e = emp[l.lookalike_engineer];
  assert.equal(e.role, 'Data Migration Engineer');
  assert.equal(e.notice_given_at, '', 'the engineer is not leaving');
  const bulk = grants.filter((g) => g.employee_id === l.lookalike_engineer && g.system === 'CUSTARCH').sort((a, b) => a.granted_at.localeCompare(b.granted_at));
  assert.equal(bulk.at(-1).ticket_id, l.lookalike_ticket, 'the bulk read is ticketed');
  assert.ok(bulk.at(-1).granted_at.slice(0, 10) < l.lookalike_spike_days[0]);
  const arch = reads(l.lookalike_engineer, 'CUSTARCH');
  const en = median(arch.map((a) => +a.records_read));
  assert.ok(arch.filter((a) => l.lookalike_spike_days.includes(a.read_date)).every((a) => +a.records_read >= 18 * en), 'just as large a spike');
  assert.ok(!grants.some((g) => g.employee_id === l.lookalike_engineer && g.system === 'CRM'), 'the engineer never touches the customer system');
  const engMail = mail.filter((m) => m.employee_id === l.lookalike_engineer);
  assert.ok(engMail.every((m) => !truth.personal_domains.includes(m.domain)) && engMail.reduce((n, m) => n + +m.attachment_mb, 0) < 20, 'no mail to speak of');
});

test('access creep: EMP-0007 gives 4 operations reports payroll and treasury with no ticket; one changes a vendor\'s bank account; the mover is ticketed', () => {
  const c = truth.access_creep;
  assert.equal(emp[c.manager].role, 'Operations Manager');
  const unticketed = grants.filter((g) => FINANCE.has(g.system) && !g.ticket_id);
  assert.deepEqual(unticketed.map((g) => `${g.employee_id}|${g.system}|${g.granted_by}`).sort(),
    c.reports.flatMap((r) => [`${r}|PAYROLL|${c.manager}`, `${r}|TREASURY|${c.manager}`]).sort());
  for (const r of c.reports) {
    assert.equal(emp[r].manager_id, c.manager, `${r} reports to EMP-0007`);
    assert.equal(emp[r].dept, 'Operations', `${r}: not a finance role`);
  }
  const vc = changes.filter((x) => x.vendor_id === c.vendor);
  assert.deepEqual(vc.map((x) => [x.changed_by, x.field_name, x.changed_at]), [[c.vendor_editor, 'bank_account', c.vendor_changed_at]], 'nobody else ever touched that vendor');
  assert.ok(c.vendor_changed_at > c.grants.find((g) => g[0] === c.vendor_editor)[2], 'after the grant');
  assert.ok(changes.filter((x) => x.changed_by !== c.vendor_editor).every((x) => /Accounts Payable/.test(emp[x.changed_by].role)), 'otherwise only accounts payable edits vendors');
  const m = emp[c.lookalike_employee];
  assert.ok(m.role === 'Payroll Officer' && m.role_since === c.lookalike_role_since && m.hired_at < '2023-01-01' && m.manager_id === c.lookalike_manager, 'moved into payroll');
  const g = grants.filter((x) => x.employee_id === c.lookalike_employee && x.system === 'PAYROLL');
  assert.deepEqual(g.map((x) => [x.granted_by, x.granted_at.slice(0, 10), x.ticket_id]), [[c.lookalike_manager, '2026-03-03', c.lookalike_ticket]], 'the day after the move, ticketed');
  assert.ok(!changes.some((x) => x.changed_by === c.lookalike_employee));
});

test('mutual approvals: 40 approvals between them, just under the limit, mostly at weekends; the team lead approves a busy team normally', () => {
  const m = truth.mutual_approvals;
  const between = expenses.filter((x) => (x.requester_id === m.a && x.approver_id === m.b) || (x.requester_id === m.b && x.approver_id === m.a));
  assert.deepEqual(between.map((x) => x.expense_id).sort(), [...m.expenses].sort());
  assert.equal(between.filter((x) => x.approver_id === m.a).length, m.a_approved_b);
  assert.equal(between.filter((x) => x.approver_id === m.b).length, m.b_approved_a);
  assert.equal(m.a_approved_b + m.b_approved_a, 40);
  assert.ok(between.every((x) => +x.amount >= 450 && +x.amount < truth.limit), 'just under the limit');
  assert.equal(between.filter((x) => weekend(x.submitted_at)).length, m.weekend);
  assert.ok(m.weekend >= 30, 'mostly at weekends');
  assert.ok(emp[m.a].role === 'Account Manager' && emp[m.b].role === 'Account Manager' && emp[m.a].manager_id !== emp[m.b].manager_id, 'peers in different teams');
  const pair = new Set(expenses.map((x) => `${x.approver_id}|${x.requester_id}`));
  const recip = [...pair].filter((k) => pair.has(k.split('|').reverse().join('|')));
  assert.deepEqual(recip.sort(), [`${m.a}|${m.b}`, `${m.b}|${m.a}`].sort(), 'nobody else approves back');
  const team = expenses.filter((x) => x.approver_id === m.lookalike_team_lead);
  assert.ok(team.every((x) => emp[x.requester_id].manager_id === m.lookalike_team_lead), 'the team lead approves the own team only');
  assert.ok(team.length >= 300 && m.lookalike_heavy_reports.length >= 10, 'a busy field team');
  assert.ok(team.filter((x) => weekend(x.submitted_at)).length / team.length < 0.15, 'on weekdays');
  assert.ok(team.filter((x) => +x.amount >= 450 && +x.amount < 500).length / team.length < 0.1, 'amounts spread, not bunched under the limit');
  assert.ok(!expenses.some((x) => x.requester_id === m.lookalike_team_lead && emp[x.approver_id].manager_id === m.lookalike_team_lead), 'never approved back');
});
