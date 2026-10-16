#!/usr/bin/env node
// The insurance-claims Link Analysis showcase's scripted API probes, run against a BOOTED, SEEDED `la-claims` Space
// (tools/seed-la-claims.mjs --apply). Needs the Demo Auth build (serve-demo, loopback). Sends only `demo:<id>` codes.
// Self-contained sibling of tools/la-demo-smoke.mjs (which it only imports pure helpers from, never edits).
//
//   node tools/la-claims-smoke.mjs [--base http://127.0.0.1:8096] [--space la-claims] [--embed] [--timeout 180] [--dry-run]
//
// Probes (each PASS/FAIL): the five personas sign in; the outsider is a 404 and the partner reviewer is read-only (403 on a write);
// claims_links_dataset declares every link column; the Jobs claims_links and claims_policy_timing (siu.manager presses Run) fill their
// Datasets; a fresh Investigation seeded on the repeat passenger grows into the ring (>= 15 entities: 12 claimants + garage, clinic, lawyer);
// the Working Set is MASKED under typed masking (no raw claimant id) and the admin's audited Reveal returns it; the nine Algorithms of
// the claims showcase accept a server Graph Run and finish; the seeded Dossier verifies and a tampered manifest does not; with --embed
// (host runs -Dla.embed.enabled=true) an embed link is minted, its public view carries the link-only headers, and a revoked or mangled link
// is the same 404. Writes only a smoke-claims-<ts> Investigation and one embed grant (revoked). Exit 1 on any failed probe.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { excerpt, expectStatus, tamperManifest, publicViewVerdict, uniform404Verdict, mangleToken, triggerVerdict, denialReason } from './la-demo-smoke.mjs';
import { INV, REPEAT_PASSENGER, RING, PROVIDERS } from './seed-la-claims.mjs';

export const PERSONAS = ['admin', 'siu.analyst', 'siu.manager', 'partner.reviewer', 'outsider'];
export const LINK_COLUMNS = ['src', 'dst', 'kind', 'ts', 'amount', 'event_date', 'via'];
export const JOBS = [['claims_links', 'claims_links_dataset'], ['claims_policy_timing', 'claims_policy_timing_dataset']];
/** The nine Algorithms the plan assigns to the claims showcase (ids as the server catalogue names them). */
export const ALGORITHMS = ['louvainCommunities', 'detectCommunities', 'cliques', 'triangleCount', 'pageRank', 'hits', 'eigenvectorCentrality',
  'katzCentrality', 'closenessCentrality'];
const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

/** Null when claims_links_dataset is listed and declares every link column, else the reason. */
export function registryVerdict(list) {
  const rows = Array.isArray(list) ? list : (list && list.items) || [];
  const d = rows.find((x) => (x.name || x.id) === 'claims_links_dataset');
  if (!d) return 'claims_links_dataset is not listed';
  const cols = ((d.content || d).columns || []).map((c) => c.name);
  const missing = LINK_COLUMNS.filter((c) => !cols.includes(c));
  return missing.length ? `claims_links_dataset lacks columns: ${missing.join(', ')}` : null;
}

/** The ring grew: at least the 12 claimants and the 3 providers are in the Working Set. */
export function ringGrowthVerdict(ws) {
  if (!ws || ws.status !== 200) return `working set ${ws ? excerpt(ws) : 'missing'}`;
  const n = ((ws.data && ws.data.rows) || []).length;
  const want = RING.length + PROVIDERS.length;
  return n >= want ? null : `the Working Set holds ${n} entities, expected at least ${want} (12 ring claimants + garage, clinic, lawyer)`;
}

/** Typed masking: no raw claimant id (CL-nnnn) may appear anywhere in the Working Set answer. */
export function maskedVerdict(ws) {
  if (!ws || ws.status !== 200) return `working set ${ws ? excerpt(ws) : 'missing'}`;
  const raw = JSON.stringify(ws.data).match(/CL-\d{4}/);
  return raw ? `a raw claimant id (${raw[0]}) leaked through typed masking` : null;
}

/** A Graph Run answer: accepted (200 or 202) and, once terminal, not failed. */
export function runVerdict(res) {
  if (![200, 202].includes(res.status)) return excerpt(res);
  const s = String((res.data && (res.data.status || res.data.state)) || '');
  return /fail|error|cancel|budget/i.test(s) ? `run ended ${s}: ${excerpt(res)}` : null;
}

export async function run({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-claims', embed = false, timeoutMs = 180000, pollMs = 3000,
  dryRun = false, log = () => {}, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), now = () => Date.now() } = {}) {
  const results = [];
  const record = (id, reason) => { results.push({ id, ok: !reason, reason }); log(`${reason ? 'FAIL' : 'PASS'}  ${id}${reason ? '  - ' + reason : ''}`); };
  const skip = (id, reason) => { results.push({ id, ok: true, skipped: true, reason }); log(`SKIPPED  ${id}  - ${reason}`); };
  if (dryRun) {
    log(`  sign in as ${PERSONAS.join(', ')}`);
    log(`  outsider GET ${INV}/log -> 404; partner.reviewer GET -> 200, POST ops -> 403`);
    log(`  GET /components/dataset: claims_links_dataset declares ${LINK_COLUMNS.join(', ')}`);
    log(`  siu.manager POST /jobs/{${JOBS.map(([j]) => j).join(', ')}}/trigger, wait for rows`);
    log(`  siu.analyst seed ${REPEAT_PASSENGER} + expand x2 -> >= 15 entities; masked Working Set; admin Reveal`);
    log(`  Graph Runs: ${ALGORITHMS.join(', ')}`);
    log(`  Dossier verify, tamper verify; embed ${embed ? 'mint, public view, revoke, mangled = revoked' : '(SKIPPED: no --embed)'}`);
    return results;
  }
  const api = `${base}/api/v1`;
  const tokens = {};
  const token = async (user) => {
    if (tokens[user]) return tokens[user];
    const r = await fetchFn(`${api}/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: `demo:${user}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
    if (!r.ok) throw new Error(`demo sign-in as ${user} refused (${r.status}); is this the Demo Auth build and does the Space define ${user}?`);
    return (tokens[user] = unwrap(await r.json()).accessToken);
  };
  const call = async (user, method, p, body) => {
    const headers = { 'content-type': 'application/json' };
    if (user) headers.authorization = `Bearer ${await token(user)}`;
    const r = await fetchFn(`${api}/spaces/${space}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    let parsed = null;
    try { parsed = await r.json(); } catch { /* empty body */ }
    return { status: r.status, body: parsed, data: unwrap(parsed) };
  };
  const pub = async (p) => {
    const r = await fetchFn(`${api}${p}`, { method: 'GET' });
    let parsed = null;
    try { parsed = await r.json(); } catch { /* empty body */ }
    return { status: r.status, body: parsed, data: unwrap(parsed), headers: r.headers };
  };

  for (const u of PERSONAS) {
    try { await token(u); record(`${u} signs in`, null); } catch (e) { record(`${u} signs in`, e.message); }
  }
  const inv = `/inv/investigations/${INV}`;
  record('outsider is not a member: 404', expectStatus(await call('outsider', 'GET', `${inv}/log`), 404));
  record('partner.reviewer reads the log: 200', expectStatus(await call('partner.reviewer', 'GET', `${inv}/log`), 200));
  const denied = await call('partner.reviewer', 'POST', `${inv}/ops`, { op: 'annotate', ids: ['CLI-04'], note: 'smoke' });
  record('partner.reviewer is read-only: a write is 403', expectStatus(denied, 403));

  const ds = await call('siu.manager', 'GET', '/components/dataset');
  record('claims_links_dataset declares every link column', ds.status === 200 ? registryVerdict(ds.data) : excerpt(ds));

  for (const [job, dataset] of JOBS) {
    const t = await call('siu.manager', 'POST', `/jobs/${job}/trigger`, {});
    const d = denialReason(t, 'siu.manager');
    record(`siu.manager triggers Job ${job}`, d || triggerVerdict(t));
    const deadline = now() + timeoutMs;
    let last;
    for (;;) {
      last = await call('siu.manager', 'GET', `/datasets/${dataset}/rows?limit=1`);
      if (last.status === 200 && last.data && Array.isArray(last.data.rows) && last.data.rows.length) { last = null; break; }
      if (now() >= deadline) break;
      await sleep(pollMs);
    }
    record(`${dataset} has rows after ${job}`, last ? `no rows after ${timeoutMs} ms: ${excerpt(last)}` : null);
  }

  const id = `smoke-claims-${now()}`;
  const mine = `/inv/investigations/${id}`;
  const created = await call('siu.analyst', 'POST', '/inv/investigations', { id, title: 'smoke claims', purpose: 'la-claims-smoke probe', dataset: 'claims_links_dataset',
    sourceCol: 'src', targetCol: 'dst', linkKindCol: 'kind', timeCol: 'ts', entityType: 'party' });
  if (created.status >= 300) record('a fresh Investigation seeds the repeat passenger and the ring grows', `create ${excerpt(created)}`);
  else {
    const seed = await call('siu.analyst', 'POST', `${mine}/ops`, { op: 'seed', ids: [REPEAT_PASSENGER], entityType: 'party' });
    let ws = null;
    if (seed.status !== 200) record('a fresh Investigation seeds the repeat passenger and the ring grows', `seed ${excerpt(seed)}`);
    else {
      for (let i = 0; i < 2; i++) await call('siu.analyst', 'POST', `${mine}/ops`, { op: 'expand', direction: 'either', maxFanOut: 40 });
      ws = await call('siu.analyst', 'GET', `${mine}/working-set?of=entities`);
      record('a fresh Investigation seeds the repeat passenger and the ring grows', ringGrowthVerdict(ws));
      record('typed masking: the Working Set carries no raw claimant id', maskedVerdict(ws));
      const row = ((ws.data && ws.data.rows) || [])[0];
      const token0 = row && (row.entityId || row.id);
      if (!token0) skip('admin Reveal returns the id (audited)', 'no Working Set row to reveal');
      else {
        await call('siu.analyst', 'POST', `${mine}/members`, { subject: 'admin', role: 'lead' });
        const rv = await call('admin', 'POST', `${mine}/reveal`, { tokens: [token0] });
        record('admin Reveal returns the id (audited)', rv.status === 200 && /CL-\d{4}|GAR-|CLI-|LAW-/.test(JSON.stringify(rv.data)) ? null : excerpt(rv));
      }
      for (const algorithm of ALGORITHMS) {
        let r = await call('siu.analyst', 'POST', '/inv/graph/runs', { investigationId: id, algorithm });
        const deadline = now() + timeoutMs;
        while (r.status === 202 || (r.status === 200 && /queued|running|pending/i.test(String((r.data && (r.data.status || r.data.state)) || '')))) {
          if (now() >= deadline) break;
          await sleep(1000);
          const rid = r.data && (r.data.id || r.data.runId);
          if (!rid) break;
          r = await call('siu.analyst', 'GET', `/inv/graph/runs/${rid}`);
        }
        record(`Graph Run ${algorithm} finishes`, runVerdict(r));
      }
    }
  }

  const dossier = await call('siu.analyst', 'GET', `${inv}/dossier`);
  if (dossier.status !== 200 || !dossier.data.manifest) record('Dossier verifies and a tampered manifest does not', `no dossier manifest (HTTP ${dossier.status})`);
  else {
    const clean = await call('siu.analyst', 'POST', `${inv}/dossier/verify`, { manifest: dossier.data.manifest });
    const bad = await call('siu.analyst', 'POST', `${inv}/dossier/verify`, { manifest: tamperManifest(dossier.data.manifest) });
    const ok = clean.status === 200 && clean.data.verified === true && ((bad.status === 200 && bad.data.verified === false) || bad.status === 422);
    record('Dossier verifies and a tampered manifest does not', ok ? null : `clean ${excerpt(clean)}; tampered ${excerpt(bad)}`);
  }

  if (!embed) skip('embed link: mint, public view, revoke', 'no --embed (the host must run -Dla.embed.enabled=true)');
  else {
    const minted = await call('admin', 'POST', `${inv}/embeds`, { ttl_hours: 1, label: 'la-claims-smoke' });
    if (minted.status !== 200 || !minted.data.url) record('embed link: mint, public view, revoke', `mint ${excerpt(minted)} (is admin a lead of ${INV}?)`);
    else {
      const view = await pub(minted.data.url);
      record('embed: public GET (no Authorization) carries the link-only headers', publicViewVerdict(view));
      const mangled = await pub(mangleToken(minted.data.url));
      const revoke = await call('admin', 'POST', `${inv}/embeds/${minted.data.grantId}/revoke`, {});
      record('embed: the lead revokes the link', revoke.status === 200 ? null : excerpt(revoke));
      record('embed: a mangled token and a revoked one are the same 404', uniform404Verdict(mangled, await pub(minted.data.url)));
    }
  }
  return results;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
  run({ base: opt('--base', 'http://127.0.0.1:8096'), space: opt('--space', 'la-claims'), embed: args.includes('--embed'),
    timeoutMs: Number(opt('--timeout', 180)) * 1000, dryRun: args.includes('--dry-run'), log: console.log })
    .then((rs) => { const bad = rs.filter((r) => !r.ok); console.log(`${rs.length - bad.length}/${rs.length} probes passed`); process.exitCode = bad.length ? 1 : 0; })
    .catch((e) => { console.error(e.message); process.exitCode = 2; });
}
