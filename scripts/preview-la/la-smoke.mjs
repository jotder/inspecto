// Headless Authorization-Code + PKCE sign-in against the local WSO2 as each demo user, then probes every Space.
// usage: node la-smoke.mjs [base]   (base defaults to http://localhost:8080; redirect URI must be one the WSO2 app allows)
import crypto from 'node:crypto';
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const W = 'https://localhost:9443', I = process.argv[2] || 'http://localhost:8080', CID = 'inspecto_preview_la';
const PW = 'Preview#LA2026';
const jar = new Map();
const ck = (r) => { for (const c of r.headers.getSetCookie?.() || []) { const [kv] = c.split(';'); const i = kv.indexOf('='); jar.set(kv.slice(0, i), kv.slice(i + 1)); } };
const ch = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
async function follow(url, opt = {}) {
  let r;
  for (let i = 0; i < 10; i++) {
    r = await fetch(url, { redirect: 'manual', ...opt, headers: { ...(opt.headers || {}), cookie: ch() } });
    ck(r);
    const loc = r.headers.get('location');
    if (!(r.status >= 300 && r.status < 400 && loc)) return { r, url };
    url = new URL(loc, url).toString(); opt = {};
    if (url.startsWith(I)) return { r, url };
  }
  return { r, url };
}
async function login(user) {
  jar.clear();
  const ver = crypto.randomBytes(32).toString('base64url');
  const chal = crypto.createHash('sha256').update(ver).digest('base64url');
  const redir = `${I}/auth/callback`;
  const q = new URLSearchParams({ response_type: 'code', client_id: CID, redirect_uri: redir, scope: 'openid profile', code_challenge: chal, code_challenge_method: 'S256', state: 's' });
  const { r, url } = await follow(`${W}/oauth2/authorize?${q}`);
  const m = url.match(/sessionDataKey=([0-9a-f-]+)/) || (await r.text()).match(/sessionDataKey=([0-9a-f-]+)/);
  if (!m) throw new Error('no sessionDataKey');
  let res = await follow(`${W}/commonauth`, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ username: user, password: user === 'admin' ? (process.env.WSO2_ADMIN_PW || PW) : PW, sessionDataKey: m[1] }) });
  if (!res.url.startsWith(I)) {
    const t = await res.r.text();
    const sk = (t.match(/sessionDataKeyConsent[^>]*value="([^"]+)"/) || res.url.match(/sessionDataKeyConsent=([^&]+)/) || [])[1];
    if (sk) res = await follow(`${W}/oauth2/authorize`, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ consent: 'approve', sessionDataKeyConsent: sk, hasApprovedAlways: 'false' }) });
  }
  const code = new URL(res.url).searchParams.get('code');
  if (!code) throw new Error('no code (wrong password or user missing): ' + res.url.slice(0, 120));
  const tr = await fetch(`${W}/oauth2/token`, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'authorization_code', code, redirect_uri: redir, client_id: CID, code_verifier: ver }) });
  return tr.json();
}
const get = async (t, p) => { const r = await fetch(I + '/api/v1' + p, { headers: { authorization: 'Bearer ' + t } }); let j = null; try { j = await r.json(); } catch { /* none */ } return { s: r.status, j }; };
const users = (process.argv[3] || 'fm.analyst,fm.manager,ra.analyst,demo.manager,siu.analyst,siu.manager,partner.reviewer,outsider,mm.analyst,mm.manager,mm.reviewer,mm.outsider,audit.admin,audit.lead,audit.manager,audit.committee,audit.outsider').split(',');
let spaces = null;
for (const u of users) {
  try {
    const t = (await login(u)).access_token;
    const p = JSON.parse(Buffer.from(t.split('.')[1], 'base64url'));
    if (!spaces) { const sp = await get(t, '/spaces'); const arr = sp.j?.data?.items ?? sp.j?.data ?? []; spaces = arr.map((x) => x.id ?? x).filter((x) => String(x).startsWith('la-')); console.log('spaces:', spaces.join(', ')); }
    const out = [];
    for (const s of spaces) {
      const r = await get(t, `/spaces/${s}/inv/investigations?limit=10`);
      const items = r.j?.data?.items ?? [];
      out.push(`${s}:${r.s}${items.length ? '[' + items.map((i) => i.id + ':' + i.access).join(',') + ']' : ''}`);
    }
    console.log(`${u.padEnd(16)} sub=${p.sub} groups=${JSON.stringify(p.groups)}\n   ${out.join('  ')}`);
  } catch (e) { console.log(`${u.padEnd(16)} FAILED ${e.message}`); }
}
