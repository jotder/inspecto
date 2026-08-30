import { describe, expect, it } from 'vitest';
import { MockFlags } from '../mock-flags';
import { MockStore } from '../mock-store';
import { SCHEDULER_MEMORY_LIMIT_PATTERN, settingsHandler } from './settings.handler';
import { systemHandler } from './system.handler';

const flags = { mockStudio: true } as MockFlags;

function req(method: string, url: string, body: unknown = null) {
    return { method, url, body, params: {}, space: 's1' };
}

describe('settingsHandler', () => {
    it('GET /settings/geo returns the default (no tile server) before any save', () => {
        const res = settingsHandler(flags)(req('GET', '/settings/geo'), new MockStore());
        expect(res?.body).toEqual({ id: 'geo', tileServerUrl: null });
    });

    it('PUT round-trips the tile server URL and blanks fold to null', () => {
        const store = new MockStore();
        const handle = settingsHandler(flags);
        const url = 'https://tiles.example.com/{z}/{x}/{y}.png';
        handle(req('PUT', '/settings/geo', { tileServerUrl: url }), store);
        expect(handle(req('GET', '/settings/geo'), store)?.body).toEqual({ id: 'geo', tileServerUrl: url });
        handle(req('PUT', '/settings/geo', { tileServerUrl: '   ' }), store);
        expect(handle(req('GET', '/settings/geo'), store)?.body).toEqual({ id: 'geo', tileServerUrl: null });
    });

    it('GET /settings/branding returns all-null defaults before any save', () => {
        const res = settingsHandler(flags)(req('GET', '/settings/branding'), new MockStore());
        expect(res?.body).toEqual({ id: 'branding', logoDataUrl: null, caption: null, footerText: null });
    });

    it('PUT round-trips branding and blanks fold to null', () => {
        const store = new MockStore();
        const handle = settingsHandler(flags);
        handle(req('PUT', '/settings/branding', { logoDataUrl: 'data:x', caption: 'Hi', footerText: '  ' }), store);
        expect(handle(req('GET', '/settings/branding'), store)?.body).toEqual({
            id: 'branding',
            logoDataUrl: 'data:x',
            caption: 'Hi',
            footerText: null,
        });
    });

    it('scopes branding to the space id in the URL, not just the active space', () => {
        const store = new MockStore();
        const handle = settingsHandler(flags);
        // active space is 's1' (req.space); write to space 'beta' explicitly
        handle(req('PUT', '/api/v1/spaces/beta/settings/branding', { caption: 'Beta brand' }), store);
        // 's1' (active, plain path) is untouched
        expect(handle(req('GET', '/settings/branding'), store)?.body).toMatchObject({ caption: null });
        // 'beta' carries its own doc
        expect(handle(req('GET', '/api/v1/spaces/beta/settings/branding'), store)?.body).toMatchObject({
            caption: 'Beta brand',
        });
    });

    it('stays out of the way when the studio mock is off', () => {
        const res = settingsHandler({ mockStudio: false } as MockFlags)(req('GET', '/settings/geo'), new MockStore());
        expect(res).toBeUndefined();
    });

    // ── /settings/scheduler — the server's contract, mirrored strictly ─────────────────────────

    it('scheduler: defaults before any save, then a cap+cadence PUT round-trips', () => {
        const store = new MockStore();
        const handle = settingsHandler(flags);
        expect(handle(req('GET', '/settings/scheduler'), store)?.body).toMatchObject({
            maxConcurrentConsignments: 0,
            pollSeconds: null,
            effectivePollSeconds: 60,
            source: 'default',
        });
        handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: 12, pollSeconds: 7 }), store);
        expect(handle(req('GET', '/settings/scheduler'), store)?.body).toMatchObject({
            maxConcurrentConsignments: 12,
            pollSeconds: 7,
            effectivePollSeconds: 7,
            effectiveAcquirePollSeconds: 7,
            source: 'file',
        });
    });

    it('scheduler: a cap-only PUT merges — it must not destroy a stored cadence', () => {
        const store = new MockStore();
        const handle = settingsHandler(flags);
        handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: 12, pollSeconds: 7 }), store);
        handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: 3 }), store);
        expect(handle(req('GET', '/settings/scheduler'), store)?.body).toMatchObject({
            maxConcurrentConsignments: 3,
            pollSeconds: 7,
        });
        // Explicit null CLEARS: back to inherit (effective 60).
        handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: 3, pollSeconds: null }), store);
        expect(handle(req('GET', '/settings/scheduler'), store)?.body).toMatchObject({
            pollSeconds: null,
            effectivePollSeconds: 60,
        });
    });

    it('scheduler: a cadence-only PUT does not seize the cap — provenance follows the key, not the doc', () => {
        // The provenance-seizure defect (fixed 2026-08-26): the cap is no longer required, and a save
        // that never mentions it must not write it — the tier stays `default`.
        const store = new MockStore();
        const handle = settingsHandler(flags);
        const put = handle(req('PUT', '/settings/scheduler', { pollSeconds: 7 }), store);
        expect(put?.status).toBe(200);
        expect(put?.body).toMatchObject({ maxConcurrentConsignments: 0, pollSeconds: 7, source: 'default' });
        // An explicit null CLEARS a stored cap, handing the tier back to `default`.
        handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: 3 }), store);
        handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: null }), store);
        expect(handle(req('GET', '/settings/scheduler'), store)?.body).toMatchObject({
            maxConcurrentConsignments: 0,
            pollSeconds: 7,
            source: 'default',
        });
    });

    it('scheduler: the resource pair is gated exactly like the server — a bad size string is a 422', () => {
        // Until this existed the mock had NO resource-pair handling at all: it accepted 'lots' where the
        // server 422s and served neither value, so the offline Resource caps section was half-dead and
        // green-lit a body the backend refuses.
        const store = new MockStore();
        const handle = systemHandler({ mockOps: true } as MockFlags);
        const sys = (): Record<string, unknown> =>
            (handle(req('GET', '/system/scheduler'), store)?.body as Record<string, Record<string, unknown>>)['system'];

        // The pair ships ON: a bound is in force with `default` provenance before any save.
        expect(sys()).toMatchObject({
            duckdbMemoryLimit: null,
            duckdbMemoryLimitSource: 'default',
            maxConcurrentJobRuns: 4,
            maxConcurrentJobRunsSource: 'default',
            duckdbMemoryLimitPattern: SCHEDULER_MEMORY_LIMIT_PATTERN,
        });

        expect(handle(req('PUT', '/system/scheduler', { duckdbMemoryLimit: 'lots' }), store)?.status).toBe(422);
        expect(handle(req('PUT', '/system/scheduler', { maxConcurrentJobRuns: -1 }), store)?.status).toBe(422);
        // Nothing was stored by either refusal — the server validates the whole body before persisting.
        expect(sys()).toMatchObject({ duckdbMemoryLimitSource: 'default', maxConcurrentJobRunsSource: 'default' });

        expect(
            handle(req('PUT', '/system/scheduler', { duckdbMemoryLimit: '2GB', maxConcurrentJobRuns: 2 }), store)
                ?.status,
        ).toBe(200);
        expect(sys()).toMatchObject({
            duckdbMemoryLimit: '2GB',
            duckdbMemoryLimitSource: 'file',
            maxConcurrentJobRuns: 2,
            maxConcurrentJobRunsSource: 'file',
        });
        // A blank memory limit CLEARS (the server's isBlank() → null rule), reverting to `default`.
        handle(req('PUT', '/system/scheduler', { duckdbMemoryLimit: '   ' }), store);
        expect(sys()).toMatchObject({ duckdbMemoryLimit: null, duckdbMemoryLimitSource: 'default' });
    });

    it('scheduler: the served grammar accepts what the server accepts and refuses what it refuses', () => {
        const re = new RegExp(SCHEDULER_MEMORY_LIMIT_PATTERN, 'i');
        for (const ok of ['2GB', '512MB', '1.5GiB', '800 MB', '1024b', '2gb', '80%', '1%', '100%', '99.9%'])
            expect(re.test(ok), ok).toBe(true);
        // Anchoring matters: an unanchored pattern would accept '2GB of RAM'. The 1..100 percentage bound
        // is inside the grammar, so the offline form refuses an over-committing 500% just like the server.
        for (const bad of ['lots', '2GB of RAM', '-1GB', 'GB', '', '0%', '101%', '500%', '100.5%', '%', '80 %'])
            expect(re.test(bad), bad).toBe(false);
    });

    it('scheduler: refuses out-of-bounds values with 422, exactly like the server', () => {
        const store = new MockStore();
        const handle = settingsHandler(flags);
        expect(handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: -1 }), store)?.status).toBe(422);
        expect(handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: 'lots' }), store)?.status).toBe(
            422,
        );
        expect(
            handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: 1, pollSeconds: 0 }), store)?.status,
        ).toBe(422);
        expect(
            handle(req('PUT', '/settings/scheduler', { maxConcurrentConsignments: 1, pollSeconds: 'fast' }), store)
                ?.status,
        ).toBe(422);
    });
});
