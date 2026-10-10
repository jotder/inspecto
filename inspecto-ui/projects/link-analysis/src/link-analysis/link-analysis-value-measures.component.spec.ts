import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ComponentsService, DbBrowserService, EventsService, LensService } from '@inspecto/core/api';
import { InvService, InvestigationHeader, ValueMeasureResult } from '@inspecto/link-analysis/api/inv.service';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisValueMeasuresComponent, valueMeasureErrorMessage } from './link-analysis-value-measures.component';
import { valueMeasureQuery, valueMeasureWindowIssue } from './value-measures';

const HEADER: InvestigationHeader = {
    id: 'inv-1',
    title: null,
    owner: 'ana',
    dataset: 'transfers',
    sourceCol: 'payer',
    targetCol: 'payee',
    linkKindCol: 'kind',
    createdAt: '2026-09-30T00:00:00Z',
    datasetVersion: null,
    parent: null,
};

const ANSWER: ValueMeasureResult = {
    measure: {
        name: 'passThrough',
        valueCol: 'amount',
        timeCol: 'ts',
        from: '2026-09-01T00:00',
        to: '2026-09-08T00:00',
        minInbound: 10000,
        minRatio: 0.9,
    },
    threshold: 'out ÷ in ≥ 0.9 with inbound ≥ 10000',
    entities: [{ entity: 'mule-7', inbound: 20000, outbound: 19000, ratio: 0.95, retention: 0.05 }],
    count: 1,
    truncated: true,
    rowsInWindow: 1200,
    unvalued: 3,
    fences: { maxEntities: 10000, timeoutSeconds: 10, maxWindowDays: 31 },
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisValueMeasuresComponent],
    template: `<inspecto-link-analysis-value-measures
        [investigation]="inv()"
        [timeCol]="timeCol()"
    ></inspecto-link-analysis-value-measures>`,
})
class HostComponent {
    readonly inv = signal<InvestigationHeader | null>(HEADER);
    readonly timeCol = signal('');
}

function setup(
    opts: {
        valueMeasures?: () => unknown;
        investigation?: InvestigationHeader | null;
        canAuthor?: boolean;
        timeCol?: string;
    } = {},
) {
    const inv = {
        valueMeasures: vi.fn((_q: unknown) => (opts.valueMeasures ? opts.valueMeasures() : of(ANSWER))),
        boundAlertRules: vi.fn(() => of({ investigation: 'inv-1', rules: [] })),
        bindValueMeasureAlertRule: vi.fn(() =>
            of({ rule: {}, current: 1, wouldFire: true, disclosure: 'Shown to Alert readers.', ...ANSWER }),
        ),
    };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: { canAuthorAlertRules: () => opts.canAuthor ?? true } },
            { provide: EventsService, useValue: { search: () => of([]) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: ComponentsService, useValue: { list: () => of([{ name: 'transfers' }]) } },
            { provide: DbBrowserService, useValue: { table: () => of({ columns: [] }) } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    if (opts.investigation !== undefined) fixture.componentInstance.inv.set(opts.investigation);
    if (opts.timeCol) fixture.componentInstance.timeCol.set(opts.timeCol);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const debug = fixture.debugElement.children[0];
    const c = debug.componentInstance as LinkAnalysisValueMeasuresComponent;
    const fill = (v: Record<string, unknown>) => {
        const { from, to, ...rest } = v;
        // The fixed window is two date pickers outside the schema form.
        if (from !== undefined) c.fromDate.setValue(String(from));
        if (to !== undefined) c.toDate.setValue(String(to));
        c.form().form.patchValue(rest);
        fixture.detectChanges();
    };
    const run = async () => {
        await c.run();
        fixture.detectChanges();
    };
    return { fixture, el, c, inv, fill, run };
}

const WINDOW = { valueCol: 'amount', timeCol: 'ts', from: '2026-09-01', to: '2026-09-08' };

describe('valueMeasureQuery', () => {
    it("sends only the chosen Measure's keys, drops blanks, and never a filter", () => {
        const q = valueMeasureQuery({
            dataset: 'transfers',
            sourceCol: 'payer',
            targetCol: 'payee',
            linkKindCol: '',
            name: 'cashOutConcentration',
            ...WINDOW,
            minShare: 0.3,
            minPayers: null,
            minRatio: 0.5, // another Measure's setting — must not travel
            cashOutKinds: ['atm', 'cash'],
            filter: 'amount >= 5000',
        });
        expect(q).toEqual({
            dataset: 'transfers',
            sourceCol: 'payer',
            targetCol: 'payee',
            name: 'cashOutConcentration',
            ...WINDOW,
            minShare: 0.3,
            cashOutKinds: ['atm', 'cash'],
        });
    });
});

describe('LinkAnalysisValueMeasuresComponent', () => {
    it('seeds the Dataset and roles from the open Investigation and sends no filter (no a11y violations)', async () => {
        const { el, c, inv, fill, run } = setup();
        expect(c.form().form.get('dataset')?.value).toBe('transfers');
        fill(WINDOW);
        await run();
        expect(inv.valueMeasures).toHaveBeenCalledTimes(1);
        const q = inv.valueMeasures.mock.calls[0][0] as unknown as Record<string, unknown>;
        expect(q).toEqual({
            dataset: 'transfers',
            sourceCol: 'payer',
            targetCol: 'payee',
            linkKindCol: 'kind',
            name: 'passThrough',
            ...WINDOW,
        });
        expect('filter' in q).toBe(false);
        await expectNoA11yViolations(el);
    });

    it('shows the threshold in force, states it in the fields, and reports truncated / unvalued honestly', async () => {
        const { el, c, fill, run } = setup();
        fill(WINDOW);
        await run();
        expect(el.querySelector('[data-test="threshold"]')?.textContent).toContain(
            'out ÷ in ≥ 0.9 with inbound ≥ 10000',
        );
        expect(c.form().form.get('minRatio')?.value).toBe(0.9);
        expect(c.form().form.get('minInbound')?.value).toBe(10000);
        const counts = el.querySelector('[data-test="counts"]')?.textContent ?? '';
        expect(counts).toContain('1200 rows in the window');
        expect(counts).toContain('3 rows without a numeric value');
        expect(el.textContent).toContain('More entities breach than are listed');
        expect(c.columns().find((col) => col.field === 'retention')?.headerName).toBe('Retention (derived: 1 − ratio)');
    });

    it("shows a 422 in the server's own words, and a 503 as an explained info notice", async () => {
        const refusal = "unknown column 'amount' — not a column of dataset 'transfers'";
        const { el, c, fill, run, inv } = setup({
            valueMeasures: () =>
                throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: refusal } } })),
        });
        fill(WINDOW);
        await run();
        const alert = el.querySelector('inspecto-alert');
        expect(alert?.textContent).toContain(refusal);
        expect(c.unavailable()).toBe(false);

        inv.valueMeasures.mockImplementation(() =>
            throwError(() => new HttpErrorResponse({ status: 503, error: { error: { message: 'no write root' } } })),
        );
        await run();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain('need the link-analysis module');
        expect(c.unavailable()).toBe(true);
    });

    it('Watch binds the answered block to the Investigation and says it fires on the COUNT (one Alert per rule)', async () => {
        const { el, fixture, inv, fill, run } = setup();
        fill(WINDOW);
        await run();
        expect(el.querySelector('[data-test="fires-on"]')?.textContent).toContain('ONE Alert');
        el.querySelector<HTMLButtonElement>(
            'form[aria-label="Watch this value Measure"] button[type="submit"]',
        )!.click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(inv.bindValueMeasureAlertRule).toHaveBeenCalledWith('inv-1', {
            name: 'inv-1-passThrough',
            valueMeasure: ANSWER.measure,
            severity: 'WARNING',
        });
        expect(el.textContent).toContain('It would fire now');
        await expectNoA11yViolations(el);
    });

    it('offers no Watch without an open Investigation', async () => {
        const { el, fill, run } = setup({ investigation: null });
        fill({ dataset: 'transfers', sourceCol: 'payer', targetCol: 'payee', ...WINDOW });
        await run();
        expect(el.querySelector('form[aria-label="Watch this value Measure"]')).toBeNull();
        expect(el.textContent).toContain('Open an Investigation');
    });

    it('runs over a rolling window, sends `last` and no From/To, and states the window the server resolved', async () => {
        const { el, inv, fill, run } = setup();
        inv.valueMeasures.mockReturnValueOnce(
            of({
                measure: { name: 'passThrough', valueCol: 'amount', timeCol: 'ts', last: '7d' },
                threshold: 'in >= 1000',
                window: { from: '2026-09-27T10:00:00', to: '2026-10-04T10:00:00', timezone: 'UTC' },
                entities: [],
                count: 0,
                truncated: false,
                rowsInWindow: 5,
                unvalued: 0,
            }),
        );
        fill({ valueCol: 'amount', timeCol: 'ts', last: '7d' });
        await run();
        const q = inv.valueMeasures.mock.calls[0][0] as unknown as Record<string, unknown>;
        expect(q['last']).toBe('7d');
        expect(q).not.toHaveProperty('from');
        expect(q).not.toHaveProperty('to');
        const w = el.querySelector('[data-test="window"]')!.textContent!;
        expect(w).toContain('2026-09-27T10:00:00 to 2026-10-04T10:00:00 (UTC)');
        expect(w).toContain('rolling, the last 7d');
    });

    it('refuses a window that is both fixed and rolling, or missing, before any call', async () => {
        const { el, inv, fill, run } = setup();
        fill({ valueCol: 'amount', timeCol: 'ts', from: '2026-09-01', to: '2026-09-08', last: '24h' });
        await run();
        expect(inv.valueMeasures).not.toHaveBeenCalled();
        expect(el.textContent).toContain('not both');
    });
});

describe('Value Measures form (DR-D4)', () => {
    const runButton = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('[data-test="vm-run"]')!;

    it('prefills the Dataset, the roles and the bound time column from the Investigation / query mapping', async () => {
        const { c, fixture } = setup({ timeCol: 'ts' });
        await fixture.whenStable();
        expect(c.form().form.value).toMatchObject({
            dataset: 'transfers',
            sourceCol: 'payer',
            targetCol: 'payee',
            linkKindCol: 'kind',
            timeCol: 'ts',
        });
    });

    it('uses date pickers for the fixed window, not free text', () => {
        const { el } = setup();
        expect(el.querySelector('input[data-test="vm-from"]')!.getAttribute('type')).toBe('date');
        expect(el.querySelector('input[data-test="vm-to"]')!.getAttribute('type')).toBe('date');
    });

    it('states the 31-day cap before anything is run', () => {
        const { el } = setup();
        expect(el.querySelector('[data-test="vm-intro"]')!.textContent).toContain('at most 31 days');
    });

    it('disables Run Measure while the window is missing or over 31 days, and enables it once valid', async () => {
        const { el, c, fixture, fill } = setup();
        await fixture.whenStable();
        fixture.detectChanges();
        fill({ valueCol: 'amount', timeCol: 'ts' });
        expect(runButton(el).disabled).toBe(true); // no window yet
        expect(el.querySelector('[data-test="vm-issue"]')!.textContent).toContain('Give a window');
        fill({ from: '2026-01-01', to: '2026-04-11' }); // 100 days
        expect(runButton(el).disabled).toBe(true);
        expect(el.querySelector('[data-test="vm-issue"]')!.textContent).toContain('at most 31 days');
        fill({ from: '2026-09-01', to: '2026-09-08' });
        expect(runButton(el).disabled).toBe(false);
        expect(c.issue()).toBeNull();
    });

    it('disables Run Measure while a column is invalid', async () => {
        const { el, fixture, fill } = setup();
        await fixture.whenStable();
        fill({ ...WINDOW, valueCol: 'not a column!' });
        expect(runButton(el).disabled).toBe(true);
        fill({ valueCol: 'amount' });
        expect(runButton(el).disabled).toBe(false);
    });

    it('clears a stale refusal as soon as an input changes', async () => {
        const { el, c, fixture, fill, run } = setup({
            valueMeasures: () =>
                throwError(
                    () => new HttpErrorResponse({ status: 422, error: { error: { message: 'unknown column' } } }),
                ),
        });
        await fixture.whenStable();
        fill(WINDOW);
        await run();
        expect(c.error()).toContain('unknown column');
        fill({ valueCol: 'amount2' });
        expect(c.error()).toBe('');
        expect(el.querySelector('inspecto-alert')).toBeNull();
    });

    it('sends the picked dates as the window', async () => {
        const { inv, fill, run } = setup();
        fill(WINDOW);
        await run();
        const q = inv.valueMeasures.mock.calls[0][0] as unknown as Record<string, unknown>;
        expect(q).toMatchObject({ from: '2026-09-01', to: '2026-09-08' });
    });
});

describe('Watch (DR-D6)', () => {
    it('is replaced by a plain sentence, never a capability id, for a user who cannot author Alert Rules', async () => {
        const { el, fill, run } = setup({ canAuthor: false });
        fill(WINDOW);
        await run();
        expect(el.querySelector('form[aria-label="Watch this value Measure"]')).toBeNull();
        const note = el.querySelector('[data-test="watch-no-cap"]')!.textContent!;
        expect(note).toContain('cannot author Alert Rules');
        expect(note).not.toMatch(/canAuthor|alerts\.author/);
    });

    it('shows the Alert Rule name constraint inline as soon as the name is edited, and says it up front', async () => {
        const { el, c, fixture, fill, run } = setup();
        fill(WINDOW);
        await run();
        expect(el.textContent).toContain('Letters, digits, dot, dash and underscore only.');
        c.watchForm.controls.name.setValue('has spaces');
        c.watchForm.controls.name.markAsDirty();
        fixture.detectChanges();
        expect(el.querySelector('mat-error')?.textContent).toContain('Letters, digits, dot, dash and underscore');
    });
});

describe('valueMeasureErrorMessage 403 (DR-D6)', () => {
    it('never shows the raw capability id', () => {
        const e = new HttpErrorResponse({
            status: 403,
            error: { error: { message: 'missing capability canAuthorAlertRules' } },
        });
        expect(valueMeasureErrorMessage(e, 'f')).not.toContain('canAuthorAlertRules');
    });
});

describe('valueMeasureWindowIssue and agentList', () => {
    it('demands exactly one window, as the server does', () => {
        expect(valueMeasureWindowIssue({ from: '2026-09-01', to: '2026-09-08' })).toBeNull();
        expect(valueMeasureWindowIssue({ last: '24h' })).toBeNull();
        expect(valueMeasureWindowIssue({ from: '2026-09-01', last: '24h' })).toContain('not both');
        expect(valueMeasureWindowIssue({ from: '2026-09-01' })).toContain('both From and To');
        expect(valueMeasureWindowIssue({ from: ' ', last: '' })).toContain('Give a window');
    });

    it('sends agentList only for cash-out concentration, and never another Measure', () => {
        const base = {
            dataset: 'd',
            sourceCol: 's',
            targetCol: 't',
            valueCol: 'v',
            timeCol: 'ts',
            last: '7d',
            agentList: 'agents',
        };
        expect(valueMeasureQuery({ ...base, name: 'cashOutConcentration' })['agentList']).toBe('agents');
        expect(valueMeasureQuery({ ...base, name: 'passThrough' })).not.toHaveProperty('agentList');
    });
});
