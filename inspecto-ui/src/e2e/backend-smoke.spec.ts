import { beforeAll, describe, expect, it } from 'vitest';

/**
 * Thin end-to-end smoke against a *running* bundle (ControlApi serving the built SPA), for every edition. It
 * verifies the real HTTP contract the UI depends on — liveness, the bootstrap, the bearer-token gate and that the
 * SPA shell is served — without a browser, so there are no new dependencies and CI stays fast.
 *
 * Every business route lives under {@code /api/v1} (API-5); a bare path falls through to the SPA shell or a 404,
 * so the probes below always carry the prefix (route names: {@code docs/api/openapi-v1.json}).
 *
 * Disabled by default: it only runs when {@code E2E_BASE_URL} is set, otherwise the whole block is skipped (CI is
 * green with no backend). Start a bundle ({@code bin\serve.bat}) and point this at it:
 *
 *   E2E_BASE_URL=http://localhost:8080 npx ng test --no-watch --include src/e2e/backend-smoke.spec.ts
 *
 * Optional env: {@code E2E_TOKEN} (a bearer token; enables the authorized check on a signed-in edition).
 */
const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {};
const RAW_BASE = env['E2E_BASE_URL'];
const BASE = RAW_BASE?.replace(/\/$/, '');
const TOKEN = env['E2E_TOKEN'];

describe.skipIf(!BASE)('backend smoke (e2e)', () => {
    const api = (p: string) => `${BASE}/api/v1${p}`;
    const auth = (t?: string): HeadersInit => (t ? { Authorization: `Bearer ${t}` } : {});

    // Basic has no sign-in (no Authenticator is registered), so no route answers 401 there. The bootstrap is public
    // on every edition and reports `edition: "basic"` exactly when nobody signs in, so the auth-gate checks below
    // are armed only when it says otherwise.
    let signsIn = false;
    beforeAll(async () => {
        const res = await fetch(api('/bootstrap'));
        const body = (await res.json()) as { data?: { edition?: string } };
        signsIn = (body.data?.edition ?? 'basic') !== 'basic';
    });

    it('GET /api/v1/health is public and reports UP', async () => {
        const res = await fetch(api('/health'));
        expect(res.status).toBe(200);
        const body = (await res.json()) as { data?: { status?: string } };
        expect(body.data?.status).toBe('UP');
    });

    it('GET /api/v1/bootstrap is public and names the edition', async () => {
        const res = await fetch(api('/bootstrap'));
        expect(res.status).toBe(200);
        const body = (await res.json()) as { data?: { edition?: string } };
        expect(['basic', 'standard', 'advanced']).toContain(body.data?.edition);
    });

    it('GET /api/v1/runs is locked without a token on a signed-in edition, not auth-gated on Basic', async () => {
        const res = await fetch(api('/runs'));
        // On Basic the answer depends on the Space, not on auth: 200 with a Space, 503 CAPABILITY_UNAVAILABLE without.
        if (signsIn) expect(res.status).toBe(401);
        else expect([401, 403]).not.toContain(res.status);
    });

    it('POST /api/v1/config/write is gated without a token on a signed-in edition (401)', async ({ skip }) => {
        // On Basic the write runs its own gate chain (503/400/422...) instead, which depends on the Space, not on auth.
        if (!signsIn) skip();
        const res = await fetch(api('/config/write'), { method: 'POST', body: '{}' });
        expect(res.status).toBe(401);
    });

    it('serves the SPA shell at / (text/html)', async () => {
        const res = await fetch(`${BASE}/`);
        expect(res.status).toBe(200);
        expect(res.headers.get('content-type') ?? '').toContain('text/html');
    });

    it.skipIf(!TOKEN)('GET /api/v1/runs returns a list with a valid token', async () => {
        const res = await fetch(api('/runs'), { headers: auth(TOKEN) });
        expect(res.status).toBe(200);
        const body = (await res.json()) as { data?: unknown };
        expect(Array.isArray(body.data)).toBe(true);
    });
});
