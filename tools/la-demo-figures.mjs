// DR-T6: pre-demo check that the manual's section 10 figures hold on the live mule data, READ-ONLY (two GETs):
//   node tools/la-demo-figures.mjs [--base http://127.0.0.1:8096] [--space la-showcase] [--user fm.analyst]
// Under typed masking the ids are `masked:<hex>`: the figures are then checked by count, score, share and position (ids must
// be masked tokens); pass `--user <persona that holds reveal>` (e.g. admin) to check the named accounts too.
// Pass-through flags 13 accounts, exactly two (the RELAY-* accounts) at about 0.985; TILL-06 holds 97 % of cash-out value.
// ControlApiLaShowcaseFiguresTest pins the same numbers over the shipped corpus; this checks the RUNNING demo.
import { fileURLToPath } from 'node:url';
import { makeClient } from './lib/la-client.mjs';

const Q = '/inv/value-measures?dataset=mule_transfers_dataset&sourceCol=PAYER_ACCOUNT&targetCol=PAYEE_ACCOUNT'
  + '&linkKindCol=CHANNEL&valueCol=AMOUNT&timeCol=BOOKED_AT&from=2026-09-01&to=2026-09-04';
export const PASS_THROUGH_QUERY = `${Q}&name=passThrough`;
export const CASH_OUT_QUERY = `${Q}&name=cashOutConcentration&cashOutKinds=cash_out`;

/** Under typed masking the measure's ids arrive as `masked:<hex>` tokens, so the named accounts cannot match. */
export const isMasked = (id) => /^masked:[0-9a-f]+$/i.test(String(id));
/** 'raw' (every id readable), 'masked' (every id a token) or 'mixed' (a leak or a half-applied mask). */
export function idMode(ents) {
  const n = ents.filter((e) => isMasked(e.entity)).length;
  return n === 0 ? 'raw' : n === ents.length ? 'masked' : 'mixed';
}

/** Reasons (empty = holds) the Pass-through measure no longer matches the manual: 13 flagged, MULE-HUB-01 among them, two at 0.985. */
export function passThroughVerdict(res) {
  if (res.status !== 200 || !res.data || !Array.isArray(res.data.entities)) return [`HTTP ${res.status}, no measure body`];
  const out = [];
  const ents = res.data.entities;
  if (res.data.count !== 13) out.push(`Pass-through flags ${res.data.count} accounts, the manual says 13`);
  const mode = idMode(ents);
  if (mode === 'mixed') out.push('ids are partly masked tokens and partly readable - masking is half-applied');
  // Masked: the ids carry no names, so the hub and the relay pair are checked by position and count, not by name.
  if (mode === 'raw' && !ents.some((e) => e.entity === 'MULE-HUB-01')) out.push('MULE-HUB-01 is not flagged');
  const near = ents.filter((e) => Math.abs(e.ratio - 0.985) < 0.0005);
  if (near.length !== 2) out.push(`${near.length} accounts sit at 0.985, the manual says the two relays`);
  else if (mode === 'raw' && !near.every((e) => String(e.entity).startsWith('RELAY-'))) out.push(`accounts at 0.985 are ${near.map((e) => e.entity)}, expected the RELAY-* pair`);
  return out;
}

/** Reasons the Cash-out concentration no longer matches: TILL-06 leads with about 97 % of the value, from 8 payers. */
export function cashOutVerdict(res) {
  if (res.status !== 200 || !res.data || !Array.isArray(res.data.entities)) return [`HTTP ${res.status}, no measure body`];
  const top = res.data.entities[0];
  if (!top) return ['no cash-out concentration entity at all'];
  const out = [];
  const mode = idMode([top]);
  if (mode === 'raw' && top.entity !== 'TILL-06') out.push(`top cash-out entity is ${top.entity}, the manual says TILL-06`);
  else {   // masked: the leader is the top row by share; its name is not readable, its figures still are
    if (Math.abs(top.share - 0.97) > 0.005) out.push(`TILL-06 holds ${(top.share * 100).toFixed(1)} %, the manual says 97 %`);
    if (top.payers !== 8) out.push(`TILL-06 is fed by ${top.payers} payers, the manual says 8`);
  }
  return out;
}

export async function runFigures({ fetchFn = fetch, base, space, user = 'fm.analyst', log = () => {} } = {}) {
  const call = makeClient({ fetchFn, base, space });
  const results = [];
  const record = (id, reasons) => { results.push({ id, ok: !reasons.length, reason: reasons.join('; ') }); log(`${reasons.length ? 'FAIL' : 'PASS'}  ${id}${reasons.length ? '  - ' + reasons.join('; ') : ''}`); };
  record('Pass-through: 13 accounts, the relays at 0.985', passThroughVerdict(await call(user, 'GET', PASS_THROUGH_QUERY)));
  record('Cash-out concentration: TILL-06 at 97 %', cashOutVerdict(await call(user, 'GET', CASH_OUT_QUERY)));
  return results;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
  runFigures({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-showcase'), user: opt('--user', 'fm.analyst'), log: console.log })
    .then((rs) => { const bad = rs.filter((r) => !r.ok); console.log(`${rs.length - bad.length}/${rs.length} figures hold`); process.exitCode = bad.length ? 1 : 0; })
    .catch((e) => { console.error(e.message); process.exitCode = 2; });
}
