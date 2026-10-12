// Tiny Demo-Auth client shared by la-demo-burst.mjs and la-demo-figures.mjs (la-demo-smoke.mjs keeps its own inline copy).
export const unwrap = (r) => (r && typeof r === 'object' && 'data' in r ? r.data : r);

export function makeClient({ fetchFn = fetch, base = 'http://127.0.0.1:8096', space = 'la-showcase' } = {}) {
  const api = `${base}/api/v1`;
  const tokens = {};
  const token = async (user) => {
    if (tokens[user]) return tokens[user];
    const r = await fetchFn(`${api}/auth/exchange`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: `demo:${user}`, codeVerifier: 'x', redirectUri: `${base}/auth/callback` }) });
    if (!r.ok) throw new Error(`demo sign-in as ${user} refused (${r.status}); is this the Demo Auth build?`);
    return (tokens[user] = unwrap(await r.json()).accessToken);
  };
  return async (user, method, p, body) => {
    const headers = { 'content-type': 'application/json', authorization: `Bearer ${await token(user)}` };
    const r = await fetchFn(`${api}/spaces/${space}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    let parsed = null;
    try { parsed = await r.json(); } catch { /* empty body */ }
    return { status: r.status, body: parsed, data: unwrap(parsed) };
  };
}
