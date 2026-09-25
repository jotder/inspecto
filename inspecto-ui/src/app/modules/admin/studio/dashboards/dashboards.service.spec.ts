import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api';
import { DashboardsService } from './dashboards.service';
import { buildDashboard } from './dashboard-types';

function setup() {
    const create = vi.fn((_t: string, c: Record<string, unknown>) =>
        of({ type: 'dashboard', name: String(c['id']), ref: `dashboard/${c['id']}`, content: c }),
    );
    const list = vi.fn(() =>
        of([
            {
                type: 'dashboard',
                name: 'd1',
                ref: 'dashboard/d1',
                content: { name: 'd1', tiles: [{ widgetId: 'c1', span: 1 }] },
            },
        ]),
    );
    TestBed.configureTestingModule({
        providers: [
            DashboardsService,
            { provide: ComponentsService, useValue: { create, list, remove: vi.fn(() => of(null)) } },
        ],
    });
    return { svc: TestBed.inject(DashboardsService), create, list };
}

describe('DashboardsService', () => {
    it('saves a dashboard as a "dashboard" registry component', () => {
        const { svc, create } = setup();
        svc.save(buildDashboard('d1', [{ widgetId: 'c1', span: 1 }])).subscribe();
        expect(create).toHaveBeenCalledWith('dashboard', expect.objectContaining({ id: 'd1' }));
    });

    it('lists dashboards back with their tiles', () => {
        const { svc } = setup();
        let dashboards: { name: string; tiles: unknown[] }[] = [];
        svc.list().subscribe((d) => (dashboards = d));
        expect(dashboards[0].name).toBe('d1');
        expect(dashboards[0].tiles).toHaveLength(1);
    });

    it('UIE-5: the header keys survive the persistence pair; blank or mistyped ones are not carried', () => {
        const { svc } = setup();
        const d = buildDashboard('d1', [{ widgetId: 'c1', span: 1 }], null, [], {
            description: 'Which question?',
            asOf: '2026-09-23',
            illustrative: true,
        });
        const content = svc.toContent(d);
        expect(content).toMatchObject({ description: 'Which question?', asOf: '2026-09-23', illustrative: true });
        expect(svc.fromContent('d1', content)).toMatchObject({
            description: 'Which question?',
            asOf: '2026-09-23',
            illustrative: true,
        });

        const plain = svc.toContent(buildDashboard('d2', [], null, [], { description: '  ', illustrative: false }));
        expect(Object.keys(plain)).not.toContain('description');
        expect(Object.keys(plain)).not.toContain('illustrative');
        const odd = svc.fromContent('d3', { tiles: [], description: 7, illustrative: 'yes' });
        expect(odd.description).toBeUndefined();
        expect(odd.illustrative).toBeUndefined();
    });

    it('UIE-5 (d): dateField + defaultRange survive the persistence pair; blank or invalid ones write nothing', () => {
        const { svc } = setup();
        const preset = buildDashboard(
            'd1',
            [{ widgetId: 'c1', span: 1 }],
            null,
            [],
            {},
            {
                dateField: ' event_date ',
                defaultRange: 'quarter-to-date',
            },
        );
        const content = svc.toContent(preset);
        expect(content).toMatchObject({ dateField: 'event_date', defaultRange: 'quarter-to-date' });
        expect(svc.fromContent('d1', content)).toMatchObject({
            dateField: 'event_date',
            defaultRange: 'quarter-to-date',
        });

        const custom = svc.toContent(
            buildDashboard(
                'd2',
                [],
                null,
                [],
                {},
                { dateField: 'd', defaultRange: { from: '2026-01-01', to: '2026-03-31' } },
            ),
        );
        expect(svc.fromContent('d2', custom).defaultRange).toEqual({ from: '2026-01-01', to: '2026-03-31' });

        const blank = svc.toContent(buildDashboard('d3', [], null, [], {}, { dateField: '  ' }));
        expect(Object.keys(blank)).not.toContain('dateField');
        expect(Object.keys(blank)).not.toContain('defaultRange');
        const odd = svc.fromContent('d4', { tiles: [], dateField: 3, defaultRange: 'last-week' });
        expect(odd.dateField).toBeUndefined();
        expect(odd.defaultRange).toBeUndefined();
        const inverted = svc.fromContent('d5', { tiles: [], defaultRange: { from: '2026-02-01', to: '2026-01-01' } });
        expect(inverted.defaultRange).toBeUndefined();
    });
});
