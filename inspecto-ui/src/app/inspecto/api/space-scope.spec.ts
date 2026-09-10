import { describe, expect, it } from 'vitest';
import { SERVER_GLOBAL, spaceScopedUrl } from './space-scope';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

/**
 * The space-scoping RULE, extracted from `spaceInterceptor` so that server-sent-events callers can apply
 * it too — `EventSource` never passes through an `HttpInterceptorFn`, so before this existed every SSE
 * URL was unscoped and a multi-space deployment tailed the wrong space.
 */
describe('spaceScopedUrl (the one statement of the space-scoping rule)', () => {
    it('prefixes a feature path with the active space, after the version segment', () => {
        expect(spaceScopedUrl(`${base}/signals/stream`, 'acme')).toBe(`${base}/spaces/acme/signals/stream`);
    });

    it('is a NO-OP with no active space, so single-tenant behaviour is byte-identical', () => {
        expect(spaceScopedUrl(`${base}/signals/stream`, null)).toBe(`${base}/signals/stream`);
        expect(spaceScopedUrl(`${base}/signals/stream`, '')).toBe(`${base}/signals/stream`);
        expect(spaceScopedUrl(`${base}/signals/stream`, undefined)).toBe(`${base}/signals/stream`);
    });

    it('leaves every server-global path unscoped — prefixing one would 404 it', () => {
        for (const p of SERVER_GLOBAL) {
            expect(spaceScopedUrl(`${base}${p}`, 'acme'), `${p} exact`).toBe(`${base}${p}`);
            expect(spaceScopedUrl(`${base}${p}/x`, 'acme'), `${p} subpath`).toBe(`${base}${p}/x`);
        }
    });

    it('does not touch a URL that is not a control-plane call', () => {
        expect(spaceScopedUrl('/assets/i18n/en.json', 'acme')).toBe('/assets/i18n/en.json');
        expect(spaceScopedUrl('https://example.test/x', 'acme')).toBe('https://example.test/x');
    });

    it('encodes a space id that needs it, so an odd id cannot break the path', () => {
        expect(spaceScopedUrl(`${base}/events/search`, 'a b/c')).toBe(`${base}/spaces/a%20b%2Fc/events/search`);
    });

    it('keeps the unversioned rewrite for a legacy /api caller', () => {
        const legacy = environment.apiBaseUrl + '/pipelines';
        expect(spaceScopedUrl(legacy, 'acme')).toBe(`${environment.apiBaseUrl}/spaces/acme/pipelines`);
    });

    it('preserves a query string, which is how the stream carries its filters', () => {
        expect(spaceScopedUrl(`${base}/signals/stream?type=AUDIT`, 'acme')).toBe(
            `${base}/spaces/acme/signals/stream?type=AUDIT`,
        );
    });
});
