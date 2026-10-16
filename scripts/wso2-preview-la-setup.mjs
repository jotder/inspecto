#!/usr/bin/env node
// Provisions a LOCAL WSO2 IS (Docker, https://localhost:9443, admin/admin) for the Preview LA bundle:
// one public PKCE application, the role groups, and the six Demo Users - idempotent, safe to re-run.
//
//   node scripts/wso2-preview-la-setup.mjs [--base https://localhost:9443] [--callback http://localhost:8080]...
//
// Subject: the application asserts the USERNAME as `sub`, so seeded ownership (fm.analyst, ...) matches the signed-in Subject.
// Roles: a user's WSO2 groups are the Inspecto role names (`groups` claim, in the ACCESS token via accessTokenAttributes).
// Synthetic users, TEST password only, local development host only. Never run against a shared IdP.
const arg = (k, d) => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : d; };
const BASE = arg('--base', 'https://localhost:9443');
if (!/^https:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(BASE)) throw new Error(`refusing a non-local IdP: ${BASE}`);
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';   // the local WSO2's self-signed cert; this script talks to localhost only
const ports = [8080, 18080, 18090];
const origins = process.argv.filter((a, i) => process.argv[i - 1] === '--callback');
if (!origins.length) for (const p of ports) origins.push(`http://localhost:${p}`, `http://127.0.0.1:${p}`);

export const CLIENT_ID = 'inspecto_preview_la';
export const PASSWORD = 'Preview#LA2026';   // test value for the synthetic demo users, local only
export const USERS = [
  ['demo.manager', 'Demo', 'Manager', ['manager', 'la-viewer']],
  ['ra.manager', 'Demo RA', 'Manager', ['manager']],
  ['ra.analyst', 'Demo RA', 'Analyst', ['operations', 'la-viewer']],
  ['fm.manager', 'Demo FM', 'Manager', ['manager', 'fraud-manager']],
  ['fm.analyst', 'Demo FM', 'Analyst', ['operations', 'fraud-analyst']],
  ['admin', 'Demo', 'Admin', ['super', 'admin', 'fraud-analyst', 'la-settings']],
];
const GROUPS = [...new Set(USERS.flatMap((u) => u[3]))];
const auth = 'Basic ' + Buffer.from('admin:admin').toString('base64');
const call = async (method, path, body) => {
  const r = await fetch(BASE + path, { method, headers: { authorization: auth, 'content-type': path.startsWith('/scim2') ? 'application/scim+json' : 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text(); let j; try { j = JSON.parse(t); } catch { j = t; }
  return { status: r.status, data: j };
};
const scimId = async (kind, filter) => { const r = await call('GET', `/scim2/${kind}?filter=${encodeURIComponent(filter)}`); return r.data.Resources?.[0]?.id; };

const gid = {};
for (const g of GROUPS) {
  gid[g] = await scimId('Groups', `displayName eq PRIMARY/${g}`) ?? await scimId('Groups', `displayName eq ${g}`);
  if (!gid[g]) { const r = await call('POST', '/scim2/Groups', { displayName: g }); gid[g] = r.data.id; console.log('group', g, r.status); }
}
for (const [u, given, family, groups] of USERS) {
  let id = await scimId('Users', `userName eq ${u}`);
  if (!id) {
    const r = await call('POST', '/scim2/Users', { userName: u, password: PASSWORD, name: { givenName: given, familyName: family }, emails: [{ primary: true, value: `${u}@preview.invalid` }] });
    id = r.data.id; console.log('user', u, r.status, r.status >= 300 ? JSON.stringify(r.data).slice(0, 200) : '');
  }
  for (const g of groups) {
    const r = await call('PATCH', `/scim2/Groups/${gid[g]}`, { schemas: ['urn:ietf:params:scim:api:messages:2.0:PatchOp'], Operations: [{ op: 'add', value: { members: [{ value: id, display: u }] } }] });
    if (r.status >= 300 && !/already/i.test(JSON.stringify(r.data))) console.log('  member', u, g, r.status, JSON.stringify(r.data).slice(0, 160));
  }
}
const claims = ['groups', 'username', 'givenname', 'lastname'].map((c) => ({ claim: { uri: `http://wso2.org/claims/${c}` }, mandatory: false }));
const list = await call('GET', `/api/server/v1/applications?filter=${encodeURIComponent('name eq inspecto-preview-la')}`);
let appId = list.data.applications?.[0]?.id;
if (appId) console.log('application exists', appId);
else {
  // The expiry fields are mandatory (omitting them is a server-side NPE); claims + subject cannot ride the create call (400).
  const r = await call('POST', '/api/server/v1/applications', {
    name: 'inspecto-preview-la',
    inboundProtocolConfiguration: { oidc: { clientId: CLIENT_ID, grantTypes: ['authorization_code', 'refresh_token'], publicClient: true,
      pkce: { mandatory: true, supportPlainTransformAlgorithm: false }, callbackURLs: origins.map((o) => `${o}/auth/callback`), allowedOrigins: origins,
      accessToken: { type: 'JWT', userAccessTokenExpiryInSeconds: 3600, applicationAccessTokenExpiryInSeconds: 3600, accessTokenAttributes: ['groups', 'username'] } } },
  });
  console.log('application', r.status, r.status >= 300 ? JSON.stringify(r.data).slice(0, 400) : 'created');
  appId = (await call('GET', `/api/server/v1/applications?filter=${encodeURIComponent('name eq inspecto-preview-la')}`)).data.applications?.[0]?.id;
}
const p = await call('PATCH', `/api/server/v1/applications/${appId}`, { claimConfiguration: { dialect: 'LOCAL', requestedClaims: claims,
  subject: { claim: { uri: 'http://wso2.org/claims/username' }, includeUserDomain: false, includeTenantDomain: false } } });
console.log('claims + subject=username', p.status);
console.log(`done. client id ${CLIENT_ID}; roles = group names; users: ${USERS.map((u) => u[0]).join(', ')}`);
