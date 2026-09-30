import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import {
    ComponentsService,
    DbBrowserService,
    InvService,
    InvestigationHeader,
    ValueMeasureResult,
} from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisValueMeasuresComponent } from './link-analysis-value-measures.component';
import { valueMeasureQuery } from './value-measures';

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
    template: `<inspecto-link-analysis-value-measures [investigation]="inv()"></inspecto-link-analysis-value-measures>`,
})
class HostComponent {
    readonly inv = signal<InvestigationHeader | null>(HEADER);
}

function setup(opts: { valueMeasures?: () => unknown; investigation?: InvestigationHeader | null } = {}) {
    const inv = {
        valueMeasures: vi.fn((_q: unknown) => (opts.valueMeasures ? opts.valueMeasures() : of(ANSWER))),
        bindValueMeasureAlertRule: vi.fn(() =>
            of({ rule: {}, current: 1, wouldFire: true, disclosure: 'Shown to Alert readers.', ...ANSWER }),
        ),
    };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: ComponentsService, useValue: { list: () => of([{ name: 'transfers' }]) } },
            { provide: DbBrowserService, useValue: { table: () => of({ columns: [] }) } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    if (opts.investigation !== undefined) fixture.componentInstance.inv.set(opts.investigation);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const debug = fixture.debugElement.children[0];
    const c = debug.componentInstance as LinkAnalysisValueMeasuresComponent;
    const fill = (v: Record<string, unknown>) => {
        c.form().form.patchValue(v);
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
});
