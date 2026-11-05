import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ComponentsService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { BuilderQueryResult, InvService, PresetList } from '@inspecto/link-analysis/api/inv.service';
import {
    LinkAnalysisQueryBuilderComponent,
    SaveQueryPresetDialog,
    SaveQueryPresetData,
} from './link-analysis-query-builder.component';
import type { PresetDraw } from './link-analysis-presets.component';
import {
    BuilderForm,
    builderQuery,
    builderSentence,
    measureOptions,
    savedPresetId,
    timeOptions,
} from './query-builder';

const LIST: PresetList = {
    dataset: 'pays',
    pack: 'mobile-money',
    roles: { from: 'payer', to: 'payee', kind: 'kind', time: 'ts', amount: 'amount' },
    presets: [],
    columns: {
        time: ['ts', 'settled_at'],
        measure: [
            { column: 'amount', unit: 'USD' },
            { column: 'fee', unit: null },
        ],
    },
    saved: [],
};

const ANSWER: BuilderQueryResult = {
    dataset: 'pays',
    query: { by: 'from', rank: 'sum', sumColumn: 'amount' },
    columns: [
        { id: 'entity', label: 'Entity', entity: true },
        { id: 'counterparts', label: 'Distinct counterparts', entity: false },
        { id: 'links', label: 'Links', entity: false },
        { id: 'total', label: 'Total amount', entity: false },
    ],
    rows: [
        { entity: 'masked:eeee', counterparts: 1, links: 1, total: 2000 },
        { entity: 'masked:aaaa', counterparts: 2, links: 3, total: 35 },
    ],
    count: 2,
    truncated: false,
};

const BLANK: BuilderForm = {
    by: 'from',
    rank: 'links',
    sumColumn: null,
    from: null,
    to: null,
    kinds: null,
    timeColumn: 'ts',
    after: null,
    before: null,
    measureColumn: 'amount',
    measureMin: null,
    measureMax: null,
    min: null,
    top: null,
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisQueryBuilderComponent],
    template: `<main>
        <h1>Explore</h1>
        <h2>Questions</h2>
        <inspecto-link-analysis-query-builder
            dataset="pays"
            [list]="list()"
            [drawWhyNot]="why()"
            [takenIds]="['busiest']"
            (draw)="drawn.push($event)"
            (saved)="saves = saves + 1"
        ></inspecto-link-analysis-query-builder>
    </main>`,
})
class HostComponent {
    readonly list = signal<PresetList | null>(LIST);
    readonly why = signal('');
    readonly drawn: PresetDraw[] = [];
    saves = 0;
}

function setup(run: () => unknown = () => of(ANSWER), closeWith: unknown = { label: 'Top payers', held: false }) {
    const inv = { runQuery: vi.fn((_d: string, _q: unknown) => run()) };
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of(closeWith) })) };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const c = fixture.debugElement.query(By.directive(LinkAnalysisQueryBuilderComponent))
        .componentInstance as LinkAnalysisQueryBuilderComponent;
    return { fixture, el, c, inv, dialog, host: fixture.componentInstance };
}

describe('query builder helpers', () => {
    it('sends only what was set: numbers as numbers, kinds as a list, a column only with its filter', () => {
        expect(builderQuery(BLANK)).toEqual({ by: 'from' });
        expect(
            builderQuery({
                ...BLANK,
                by: 'to',
                rank: 'sum',
                sumColumn: 'amount',
                from: ' masked:aaaa ',
                kinds: 'p2p, cash_out, p2p,',
                after: '2026-10-01',
                measureMin: '10',
                min: '2',
                top: '20',
            }),
        ).toEqual({
            by: 'to',
            rank: 'sum',
            sumColumn: 'amount',
            from: 'masked:aaaa',
            kinds: ['p2p', 'cash_out'],
            after: '2026-10-01',
            timeColumn: 'ts',
            measureColumn: 'amount',
            measureMin: 10,
            min: 2,
            top: 20,
        });
        // a sum column without rank sum is not sent
        expect(builderQuery({ ...BLANK, sumColumn: 'amount' })).toEqual({ by: 'from' });
    });

    it('says the question in words, and makes an id from a name', () => {
        expect(builderSentence({ by: 'to', rank: 'sum', sumColumn: 'amount', kinds: ['p2p'] })).toBe(
            'The receivers with the largest total of amount over p2p.',
        );
        expect(
            builderSentence({
                by: 'from',
                from: 'masked:aaaa',
                after: '2026-10-01',
                before: '2026-10-08',
                measureColumn: 'amount',
                measureMin: 10,
                measureMax: 500,
            }),
        ).toBe(
            'The senders with the most links sent by masked:aaaa on or after 2026-10-01 before 2026-10-08 amount at least 10 amount at most 500.',
        );
        expect(savedPresetId('  Top payers / week ')).toBe('top-payers-week');
        expect(savedPresetId('!!!')).toBe('');
    });

    it('offers the Dataset role columns, a Measure with its unit', () => {
        expect(measureOptions(LIST).map((o) => o.label)).toEqual(['amount (USD)', 'fee']);
        expect(timeOptions(LIST).map((o) => o.value)).toEqual(['ts', 'settled_at']);
        expect(measureOptions(null)).toEqual([]);
    });
});

describe('LinkAnalysisQueryBuilderComponent', () => {
    it('asks the question, shows the answer and draws its entities', async () => {
        const { fixture, el, c, inv, host } = setup();
        c.form.patchValue({ rank: 'sum' });
        c.ask();
        fixture.detectChanges();
        expect(inv.runQuery).toHaveBeenCalledWith('pays', { by: 'from', rank: 'sum', sumColumn: 'amount', top: 50 });
        expect(el.querySelector('inspecto-data-table')).not.toBeNull();
        c.drawThese();
        expect(host.drawn).toEqual([
            {
                datasetId: 'pays',
                values: ['masked:eeee', 'masked:aaaa'],
                what: 'the answer to "The senders with the largest total of amount."',
            },
        ]);
        await expectNoA11yViolations(el);
    });

    it('offers no total without a Measure, and refuses a malformed day before asking', () => {
        const { fixture, c, inv, host } = setup();
        host.list.set({ ...LIST, roles: { ...LIST.roles, amount: null }, columns: { time: ['ts'], measure: [] } });
        fixture.detectChanges();
        expect(c.rankOptions().map((o) => o.value)).toEqual(['links', 'counterparts']);
        c.form.patchValue({ after: 'yesterday' });
        c.ask();
        expect(inv.runQuery).not.toHaveBeenCalled();
        // more rows than the server returns is refused here too
        c.form.patchValue({ after: null, top: '501' });
        c.ask();
        expect(inv.runQuery).not.toHaveBeenCalled();
        c.form.patchValue({ top: '500' });
        c.ask();
        expect(inv.runQuery).toHaveBeenCalledTimes(1);
    });

    it('shows a refused question, and saves the last one asked as a preset', () => {
        const { fixture, el, c, dialog, host } = setup(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 422,
                        error: { error: { message: "'kinds' needs a kind of link" } },
                    }),
            ),
        );
        c.ask();
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain('kind of link');

        c.result.set(ANSWER);
        (c as unknown as { asked: unknown }).asked = { by: 'from' };
        c.saveAsPreset();
        fixture.detectChanges();
        const calls = dialog.open.mock.calls as unknown as [unknown, { data: SaveQueryPresetData }][];
        expect(calls[0][1].data).toEqual({ query: { by: 'from' }, takenIds: ['busiest'] });
        expect(host.saves).toBe(1);
        expect(el.textContent).toContain('Saved "Top payers"');

        // a host that cannot draw says why, and the button points at the reason
        host.why.set('Pick a Dataset you can view');
        fixture.detectChanges();
        const draw = el.querySelector('[data-testid="la-builder-draw"]');
        expect(draw?.getAttribute('aria-describedby')).toBe('la-builder-draw-why');
        expect(el.querySelector('#la-builder-draw-why')?.textContent).toContain('Pick a Dataset you can view');
        c.drawThese();
        expect(host.drawn).toEqual([]);
    });
});

describe('SaveQueryPresetDialog', () => {
    function open() {
        const create = vi.fn((_t: string, _c: Record<string, unknown>) =>
            of({ id: 'top-payers', name: 'top-payers', content: {} }),
        );
        const close = vi.fn();
        TestBed.configureTestingModule({
            imports: [SaveQueryPresetDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: { query: { by: 'from', rank: 'links' }, takenIds: ['busiest'] } },
                {
                    provide: MatDialogRef,
                    useValue: { close, disableClose: false, backdropClick: () => of(), keydownEvents: () => of() },
                },
                { provide: ComponentsService, useValue: { create } },
                { provide: InspectoConfirmService, useValue: { confirm: () => Promise.resolve(true) } },
            ],
        });
        const fixture = TestBed.createComponent(SaveQueryPresetDialog);
        fixture.detectChanges();
        return { fixture, c: fixture.componentInstance, create, close };
    }

    it('needs a name not taken, and stores the question as a preset-query component', async () => {
        const { c, create, close } = open();
        await c.submit();
        expect(c.labelError()).toBe('Name the preset.');
        c.form.controls.label.setValue('Busiest');
        await c.submit();
        expect(c.labelError()).toContain('exists');
        expect(create).not.toHaveBeenCalled();

        c.form.controls.label.setValue('Top payers');
        await c.submit();
        expect(create).toHaveBeenCalledWith('preset-query', {
            id: 'top-payers',
            label: 'Top payers',
            description: 'The senders with the most links.',
            query: { by: 'from', rank: 'links' },
        });
        expect(close).toHaveBeenCalledWith({ label: 'Top payers', held: false });
    });
});
