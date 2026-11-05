import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { InvService, PresetList, PresetRunResult } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisPresetsComponent, PresetDraw } from './link-analysis-presets.component';
import { presetColumns, presetEntityValues, presetNumber, presetParamValues, presetSourceLine } from './presets';

const LIST: PresetList = {
    dataset: 'pays',
    pack: 'mobile-money',
    roles: { from: 'payer', to: 'payee', kind: null, time: 'ts', amount: 'amount' },
    presets: [
        {
            id: 'fan_in',
            label: 'Reached by many',
            description: 'Entities that many different others link to.',
            kind: 'aggregate',
            needs: ['from', 'to'],
            params: [
                { name: 'min', label: 'At least this many distinct senders', type: 'number', default: '5' },
                { name: 'top', label: 'How many rows', type: 'number', default: '50' },
            ],
        },
        {
            id: 'counterparts',
            label: 'Top counterparts of one entity',
            description: 'Who one entity links to most.',
            kind: 'aggregate',
            needs: ['from', 'to'],
            params: [{ name: 'entity', label: 'The entity', type: 'entity', default: '' }],
        },
    ],
};

const ANSWER: PresetRunResult = {
    preset: LIST.presets[0],
    dataset: 'pays',
    columns: [
        { id: 'entity', label: 'Entity', entity: true },
        { id: 'counterparts', label: 'Distinct counterparts', entity: false },
    ],
    rows: [
        { entity: 'masked:aaaa', counterparts: 9 },
        { entity: 'masked:bbbb', counterparts: 6 },
    ],
    count: 2,
    truncated: true,
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisPresetsComponent],
    template: `<main>
        <h1>Explore</h1>
        <inspecto-link-analysis-presets
            [datasets]="datasets()"
            [initialDataset]="initial()"
            (draw)="drawn.push($event)"
        ></inspecto-link-analysis-presets>
    </main>`,
})
class HostComponent {
    readonly datasets = signal([{ id: 'pays', name: 'Payments' }]);
    readonly initial = signal<string | null>('pays');
    readonly drawn: PresetDraw[] = [];
}

function setup(opts: { list?: () => unknown; run?: () => unknown; initial?: string | null } = {}) {
    const inv = {
        presets: vi.fn((_d: string) => (opts.list ? opts.list() : of(LIST))),
        runPreset: vi.fn((_id: string, _d: string, _p: Record<string, string>) => (opts.run ? opts.run() : of(ANSWER))),
    };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    if (opts.initial !== undefined) fixture.componentInstance.initial.set(opts.initial);
    fixture.detectChanges();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const c = fixture.debugElement.children[0].children[1].componentInstance as LinkAnalysisPresetsComponent;
    return { fixture, el, c, inv, host: fixture.componentInstance };
}

describe('preset helpers', () => {
    it('draws every entity column value once, and sends only the parameters that were set', () => {
        expect(presetEntityValues({ ...ANSWER, rows: [...ANSWER.rows, { entity: 'masked:aaaa', counterparts: 1 }] })).toEqual([
            'masked:aaaa',
            'masked:bbbb',
        ]);
        expect(presetEntityValues(null)).toEqual([]);
        expect(presetParamValues({ min: ' 3 ', top: '', entity: null })).toEqual({ min: '3' });
    });

    it('says when the link index answered, and when it is behind; the Dataset needs no line', () => {
        expect(presetSourceLine({ ...ANSWER, source: { kind: 'dataset', reason: 'no_index' } })).toBe('');
        expect(presetSourceLine({ ...ANSWER, source: { kind: 'index', version: 73, stale: false } })).toBe(
            'Answered from the link index (version 73).',
        );
        expect(presetSourceLine({ ...ANSWER, source: { kind: 'index', version: 4, stale: true, staleReason: '2 files added' } })).toContain(
            'behind the data (2 files added)',
        );
    });

    it('reads a measure with at most two decimals and leaves entity columns as they are', () => {
        expect(presetNumber(119292.33999999998)).toBe('119,292.34');
        expect(presetNumber(25)).toBe('25');
        expect(presetNumber(null)).toBe('');
        const cols = presetColumns(ANSWER);
        expect(cols[0].valueFormatter).toBeUndefined();
        expect(typeof cols[1].valueFormatter).toBe('function');
    });
});

describe('LinkAnalysisPresetsComponent', () => {
    it('lists the presets of the preselected Dataset, asks with the parameters and draws the answer', async () => {
        const { fixture, el, c, inv, host } = setup();
        expect(inv.presets).toHaveBeenCalledWith('pays');
        expect(c.chosen()?.id).toBe('fan_in');
        expect(el.querySelector('[data-testid="la-presets-param-min"]')).not.toBeNull();

        c.params.controls['min'].setValue('2');
        c.run();
        fixture.detectChanges();
        expect(inv.runPreset).toHaveBeenCalledWith('fan_in', 'pays', { min: '2', top: '50' });
        expect(el.textContent).toContain('Answer cut short');
        expect(el.querySelector('inspecto-data-table')).not.toBeNull();

        (el.querySelector('[data-testid="la-presets-draw"]') as HTMLButtonElement).click();
        expect(host.drawn).toEqual([
            { datasetId: 'pays', values: ['masked:aaaa', 'masked:bbbb'], what: 'the answer to "Reached by many"' },
        ]);
        await expectNoA11yViolations(el);
    });

    it("asks of the first Dataset when the page names none, as the Start box does", () => {
        const { inv, c } = setup({ initial: null });
        expect(inv.presets).toHaveBeenCalledWith('pays');
        expect(c.dataset.value).toBe('pays');
    });

    it('refuses a bad number and a missing entity inline, without asking the server', () => {
        const { fixture, el, c, inv } = setup();
        expect(el.querySelector('inspecto-pen-field p[role="alert"]')).toBeNull();
        c.params.controls['min'].setValue('-1');
        c.run();
        fixture.detectChanges();
        expect(inv.runPreset).not.toHaveBeenCalled();
        expect(el.querySelector('inspecto-pen-field p[role="alert"]')?.textContent).toContain('A number of 0 or more');

        c.preset.setValue('counterparts');
        fixture.detectChanges();
        c.run();
        fixture.detectChanges();
        expect(inv.runPreset).not.toHaveBeenCalled();
        expect(el.querySelector('inspecto-pen-field p[role="alert"]')?.textContent).toContain('Required');
    });

    it('says when no preset fits the Dataset', () => {
        const none = setup({ list: () => of({ ...LIST, presets: [] }) });
        expect(none.el.querySelector('[data-testid="la-presets-none"]')).not.toBeNull();
        expect(none.el.querySelector('[data-testid="la-presets-form"]')).toBeNull();
    });

    it("shows the server's refusal and offers nothing to draw", () => {
        const refused = setup({
            run: () => throwError(() => new HttpErrorResponse({ status: 422, error: { message: 'needs the Dataset to map [amount]' } })),
        });
        refused.c.run();
        refused.fixture.detectChanges();
        expect(refused.el.querySelector('inspecto-alert')).not.toBeNull();
        expect(refused.el.querySelector('[data-testid="la-presets-draw"]')).toBeNull();
    });
    it('lists the Space presets saved from the builder and runs one through the builder route', () => {
        const saved = {
            ...LIST,
            saved: [{ id: 'busiest', label: 'Busiest', description: 'The senders with the most links.', query: { by: 'from' as const } }],
        };
        const { fixture, el, c, inv } = setup({ list: () => of(saved) });
        expect(c.presetOptions().map((o) => o.value)).toContain('saved:busiest');
        const runQuery = vi.fn(() => of({ ...ANSWER, query: { by: 'from' } }));
        (inv as unknown as { runQuery: typeof runQuery }).runQuery = runQuery;
        c.preset.setValue('saved:busiest');
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-presets-saved-description"]')?.textContent).toContain('most links');
        c.run();
        fixture.detectChanges();
        expect(runQuery).toHaveBeenCalledWith('pays', { by: 'from' });
        expect(inv.runPreset).not.toHaveBeenCalled();
        expect(el.querySelector('[data-testid="la-builder"]')).not.toBeNull();
    });
    it('offers no builder over a Dataset that does not say which columns link one entity to another', () => {
        const { el } = setup({ list: () => of({ ...LIST, presets: [], roles: { ...LIST.roles, from: null, to: null } }) });
        expect(el.querySelector('[data-testid="la-builder"]')).toBeNull();
    });
});
