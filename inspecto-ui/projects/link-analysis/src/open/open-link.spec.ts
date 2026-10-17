import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';
import routes from './open.routes';
import { OpenInLaComponent } from './open-in-la.component';
import { parseOpenLink } from './open-link';

const parse = (p: Record<string, string>) => parseOpenLink(convertToParamMap(p));

describe('parseOpenLink (the Open in Inspecto LA contract)', () => {
    it('takes an entity, a window and an Investigation', () => {
        expect(
            parse({
                entity: 'msisdn:masked:f01a9b8ca40c4eb1',
                from: '2026-10-01T00:00:00Z',
                to: '2026-10-04T00:00:00+02:00',
                investigation: 'ext-0123',
            }),
        ).toEqual({
            link: {
                entity: 'msisdn:masked:f01a9b8ca40c4eb1',
                investigation: 'ext-0123',
                window: { from: '2026-10-01T00:00:00Z', to: '2026-10-04T00:00:00+02:00' },
            },
        });
        expect(parse({ investigation: 'ext-0123' })).toEqual({ link: { investigation: 'ext-0123' } });
        expect(parse({ entity: 'cell:CELL-7' })).toEqual({ link: { entity: 'cell:CELL-7' } });
    });

    it('refuses an unknown or invalid parameter with a readable reason', () => {
        const error = (p: Record<string, string>) => (parse(p) as { error: string }).error;
        expect(error({ entity: 'cell:x', seed: '1' })).toContain('does not know: seed');
        expect(error({})).toContain('names no entity and no Investigation');
        expect(error({ entity: 'no-colon' })).toContain('"<type>:<key>"');
        expect(error({ entity: ':key' })).toContain('"<type>:<key>"');
        expect(error({ entity: 'msisdn:' })).toContain('"<type>:<key>"');
        expect(error({ investigation: '../x' })).toContain('not a valid id');
        expect(error({ entity: 'cell:x', from: '2026-10-01' })).toContain('"from"');
        expect(error({ entity: 'cell:x', to: 'yesterday' })).toContain('"to"');
        expect(error({ entity: 'cell:x', from: '2026-10-04T00:00:00Z', to: '2026-10-01T00:00:00Z' })).toContain(
            'ends before it starts',
        );
    });
});

describe('open routes', () => {
    it('mounts the landing page lazily', async () => {
        expect(routes.map((r) => r.path)).toEqual(['']);
        expect(await routes[0].loadComponent!()).toBe(OpenInLaComponent);
    }, 60_000);
});
