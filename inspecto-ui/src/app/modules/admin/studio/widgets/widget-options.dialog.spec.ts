import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { WidgetOptions } from './widget-types';
import { WidgetOptionsData, WidgetOptionsDialog } from './widget-options.dialog';

function create(options: WidgetOptions = {}, vizType?: string) {
    const data: WidgetOptionsData = { options, vizType };
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [WidgetOptionsDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
        ],
    });
    const fixture = TestBed.createComponent(WidgetOptionsDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref };
}

describe('WidgetOptionsDialog', () => {
    it('re-nests the flat form values into axis/legend on save', () => {
        const data: WidgetOptions = {
            title: 'My chart',
            legend: { show: false, position: 'bottom' },
            sort: 'desc',
            limit: 5,
            stacked: true,
            axis: { xTitle: 'X' },
        };
        const { c, ref } = create(data);
        c.save();
        expect(ref.close).toHaveBeenCalledTimes(1);
        const saved = ref.close.mock.calls[0][0] as WidgetOptions;
        expect(saved.title).toBe('My chart');
        expect(saved.legend).toEqual({ show: false, position: 'bottom' });
        expect(saved.axis).toEqual({ xTitle: 'X', yTitle: undefined });
        expect(saved.sort).toBe('desc');
        expect(saved.limit).toBe(5);
        expect(saved.stacked).toBe(true);
    });

    it('applies sane defaults when opened with empty options', () => {
        const { c, ref } = create({});
        c.save();
        const saved = ref.close.mock.calls[0][0] as WidgetOptions;
        expect(saved.title).toBeUndefined();
        expect(saved.legend).toBeUndefined(); // Auto at the top position: the chart theme decides
        expect(saved.axis).toBeUndefined();
        expect(saved.sort).toBeUndefined();
        expect(saved.stacked).toBe(false);
    });

    // The dialog rebuilt the options from its own form alone, so a save wiped every key it does not model —
    // a table's columnLabels and badgeColumns included.
    it('keeps the options it does not model (columnLabels, badgeColumns, columnFormats) through a save', () => {
        const { c, ref } = create({
            columnLabels: { sum_exposure_sar: 'Exposure (SAR)' },
            badgeColumns: ['disposition'],
            columnFormats: { sum_exposure_sar: { style: 'currency', currency: 'SAR' } },
        });
        c.save();
        const saved = ref.close.mock.calls[0][0] as WidgetOptions;
        expect(saved.columnLabels).toEqual({ sum_exposure_sar: 'Exposure (SAR)' });
        expect(saved.badgeColumns).toEqual(['disposition']);
        expect(saved.columnFormats?.['sum_exposure_sar']?.currency).toBe('SAR');
    });

    it('round-trips the number format and the KPI target (UIE-1, UIE-4)', () => {
        const { c, ref } = create({
            format: { style: 'currency', currency: 'SAR', compact: true, decimals: 1 },
            kpi: { target: 0.3, better: 'lower' },
        });
        c.save();
        const saved = ref.close.mock.calls[0][0] as WidgetOptions;
        expect(saved.format).toEqual({ style: 'currency', currency: 'SAR', compact: true, decimals: 1 });
        expect(saved.kpi).toEqual({ target: 0.3, better: 'lower' });
    });

    it('round-trips the heatmap scale and midpoint; the default sequential scale writes nothing', () => {
        const { c, ref } = create({ heatmap: { scale: 'diverging', midpoint: 99 } });
        c.save();
        expect((ref.close.mock.calls[0][0] as WidgetOptions).heatmap).toEqual({ scale: 'diverging', midpoint: 99 });
        c.schemaForm.form.patchValue({ heatmapScale: 'sequential', heatmapMidpoint: null });
        c.save();
        expect((ref.close.mock.calls[1][0] as WidgetOptions).heatmap).toBeUndefined();
    });

    it('keeps a table row link through a save (UIE-6)', () => {
        const { c, ref } = create({ rowLink: { kind: 'reconciliation', idField: 'recon_id' } });
        c.save();
        const saved = ref.close.mock.calls[0][0] as WidgetOptions;
        expect(saved.rowLink).toEqual({ kind: 'reconciliation', idField: 'recon_id' });
    });

    it('authors a row link, and "Nothing" or a blank column removes it (UIE-6)', () => {
        const { c, ref } = create({ rowLink: { kind: 'case', idField: 'case_id' } });
        const form = c.schemaForm.form;
        form.patchValue({ rowLinkKind: 'incident', rowLinkIdField: ' incident_id ' });
        c.save();
        expect((ref.close.mock.calls[0][0] as WidgetOptions).rowLink).toEqual({
            kind: 'incident',
            idField: 'incident_id',
        });
        form.patchValue({ rowLinkKind: '' });
        c.save();
        expect((ref.close.mock.calls[1][0] as WidgetOptions).rowLink).toBeUndefined();
        form.patchValue({ rowLinkKind: 'case', rowLinkIdField: '' });
        c.save();
        expect((ref.close.mock.calls[2][0] as WidgetOptions).rowLink).toBeUndefined();
    });

    it('round-trips the row link text column, and a blank one leaves the link on the id cell (UIE-6)', () => {
        const link = { kind: 'reconciliation' as const, idField: 'recon_id', labelField: 'control' };
        const { c, ref } = create({ rowLink: link });
        const form = c.schemaForm.form;
        expect(form.value['rowLinkLabelField']).toBe('control');
        c.save();
        expect((ref.close.mock.calls[0][0] as WidgetOptions).rowLink).toEqual(link);
        form.patchValue({ rowLinkLabelField: '  ' });
        c.save();
        expect((ref.close.mock.calls[1][0] as WidgetOptions).rowLink).toEqual({
            kind: 'reconciliation',
            idField: 'recon_id',
        });
    });

    it('round-trips the Waterfall and Combo options, a hidden total included; defaults save no block at all', () => {
        const { c, ref } = create({
            waterfall: { start: 'Opening exposure', totalLabel: '', order: 'desc' },
            combo: { secondaryAxis: false },
            axis: { y2Title: 'Recovery rate' },
            format2: { style: 'percent' },
        });
        c.save();
        const saved = ref.close.mock.calls[0][0] as WidgetOptions;
        expect(saved.waterfall).toEqual({ start: 'Opening exposure', totalLabel: '', order: 'desc' });
        expect(saved.combo).toEqual({ secondaryAxis: false });
        expect(saved.axis?.y2Title).toBe('Recovery rate');
        expect(saved.format2).toEqual({ style: 'percent' }); // not modelled here, kept

        c.schemaForm.form.patchValue({
            waterfallStart: '',
            waterfallShowTotal: true,
            waterfallTotalLabel: '',
            waterfallOrder: 'data',
            comboSecondaryAxis: true,
        });
        c.save();
        const reset = ref.close.mock.calls[1][0] as WidgetOptions;
        expect(reset.waterfall).toBeUndefined();
        expect(reset.combo).toBeUndefined();
    });

    // ── Gauge scale ──
    it('offers the Gauge scale only for a Gauge, round-trips it, and a blank end writes nothing', () => {
        const { fixture, c, ref } = create({ gauge: { min: 0, max: 500 } }, 'gauge');
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Gauge: minimum');
        expect(text).toContain('Gauge: maximum');
        c.save();
        expect((ref.close.mock.calls[0][0] as WidgetOptions).gauge).toEqual({ min: 0, max: 500 });
        c.schemaForm.form.patchValue({ gaugeMin: null });
        c.save();
        expect((ref.close.mock.calls[1][0] as WidgetOptions).gauge).toEqual({ max: 500 });
        c.schemaForm.form.patchValue({ gaugeMax: null });
        c.save();
        expect((ref.close.mock.calls[2][0] as WidgetOptions).gauge).toBeUndefined();
    });

    it('hides the Gauge scale for other types and keeps a stored one as-is', () => {
        const { fixture, c, ref } = create({ gauge: { max: 10 } }, 'bar');
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Gauge: minimum');
        c.save();
        expect((ref.close.mock.calls[0][0] as WidgetOptions).gauge).toEqual({ max: 10 });
    });
    // ── end Gauge scale ──

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });

    // ── Legend Auto / Show / Hide ─────────────────────────────────────────────────────────────────────────────
    describe('legend', () => {
        const saved = (ref: { close: ReturnType<typeof vi.fn> }, i = 0) => ref.close.mock.calls[i][0] as WidgetOptions;

        it('a stored show: true / false loads as Show / Hide; no show loads as Auto', () => {
            expect(create({ legend: { show: true } }).c.initialValue['legendShow']).toBe('show');
            TestBed.resetTestingModule();
            expect(create({ legend: { show: false } }).c.initialValue['legendShow']).toBe('hide');
            TestBed.resetTestingModule();
            expect(create({ legend: { position: 'left' } }).c.initialValue['legendShow']).toBe('auto');
            TestBed.resetTestingModule();
            expect(create({}).c.initialValue['legendShow']).toBe('auto');
        });

        it('Auto writes no show (the theme decides) and keeps a non-default position; Show / Hide map to true / false', () => {
            const { c, ref } = create({ legend: { show: false, position: 'bottom' } });
            const form = c.schemaForm.form;
            form.patchValue({ legendShow: 'auto' });
            c.save();
            expect(saved(ref, 0).legend).toEqual({ position: 'bottom' });
            expect('show' in (saved(ref, 0).legend ?? {})).toBe(false);
            form.patchValue({ legendShow: 'show', legendPosition: 'top' });
            c.save();
            expect(saved(ref, 1).legend).toEqual({ show: true, position: 'top' });
            form.patchValue({ legendShow: 'hide' });
            c.save();
            expect(saved(ref, 2).legend).toEqual({ show: false, position: 'top' });
            form.patchValue({ legendShow: 'auto', legendPosition: 'top' });
            c.save();
            expect(saved(ref, 3).legend).toBeUndefined();
        });
    });

    // ── Per-Visualization-Type options: kpi-trend / progress-list / treemap / combo ──────────────────────────────
    describe('per-type options', () => {
        const keys = (c: WidgetOptionsDialog) => c.attributes.map((a) => a.key);
        const last = (ref: { close: ReturnType<typeof vi.fn> }) => ref.close.mock.lastCall![0] as WidgetOptions;
        const perType = ['trendCompareBack', 'progressMax', 'treemapLimit', 'format2Style', 'format2Decimals'];
        const shown = (vizType: string) => keys(create({}, vizType).c).filter((k) => perType.includes(k));

        it('shows each field only for its vizType', () => {
            expect(shown('bar')).toEqual([]);
            TestBed.resetTestingModule();
            expect(shown('kpi-trend')).toEqual(['trendCompareBack']);
            TestBed.resetTestingModule();
            expect(shown('progress-list')).toEqual(['progressMax']);
            TestBed.resetTestingModule();
            expect(shown('treemap')).toEqual(['treemapLimit']);
            TestBed.resetTestingModule();
            const combo = create({}, 'combo');
            expect(keys(combo.c).filter((k) => perType.includes(k))).toEqual(['format2Style', 'format2Decimals']);
            expect(combo.fixture.nativeElement.textContent).toContain('Right axis format');
        });

        it('kpi-trend: compareBack loads, edits and saves; 1 (the default) or blank writes nothing', () => {
            const { c, ref } = create({ trend: { compareBack: 7 } }, 'kpi-trend');
            c.save();
            expect(last(ref).trend).toEqual({ compareBack: 7 });
            c.schemaForm.form.patchValue({ trendCompareBack: 3 });
            c.save();
            expect(last(ref).trend).toEqual({ compareBack: 3 });
            c.schemaForm.form.patchValue({ trendCompareBack: 1 });
            c.save();
            expect(last(ref).trend).toBeUndefined();
            c.schemaForm.form.patchValue({ trendCompareBack: null });
            c.save();
            expect(last(ref).trend).toBeUndefined();
        });

        it('progress-list: max round-trips; blank removes it but keeps progress.limit (not modelled)', () => {
            const { c, ref } = create({ progress: { limit: 5, max: 100 } }, 'progress-list');
            c.save();
            expect(last(ref).progress).toEqual({ limit: 5, max: 100 });
            c.schemaForm.form.patchValue({ progressMax: 250 });
            c.save();
            expect(last(ref).progress).toEqual({ limit: 5, max: 250 });
            c.schemaForm.form.patchValue({ progressMax: null });
            c.save();
            expect(last(ref).progress).toEqual({ limit: 5 });
        });

        it('progress-list: a blank max on a widget with no progress block writes none', () => {
            const { c, ref } = create({}, 'progress-list');
            c.save();
            expect(last(ref).progress).toBeUndefined();
        });

        it('treemap: limit round-trips; 20 (the default) or blank writes nothing', () => {
            const { c, ref } = create({ treemap: { limit: 8 } }, 'treemap');
            c.save();
            expect(last(ref).treemap).toEqual({ limit: 8 });
            c.schemaForm.form.patchValue({ treemapLimit: 12 });
            c.save();
            expect(last(ref).treemap).toEqual({ limit: 12 });
            c.schemaForm.form.patchValue({ treemapLimit: 20 });
            c.save();
            expect(last(ref).treemap).toBeUndefined();
            c.schemaForm.form.patchValue({ treemapLimit: null });
            c.save();
            expect(last(ref).treemap).toBeUndefined();
        });

        it('combo: the right-axis format round-trips separately from the main format; all-blank removes it', () => {
            const { c, ref } = create(
                { format: { style: 'currency', currency: 'SAR' }, format2: { style: 'percent', decimals: 1 } },
                'combo',
            );
            c.save();
            expect(last(ref).format2).toEqual({ style: 'percent', decimals: 1 });
            expect(last(ref).format).toEqual({ style: 'currency', currency: 'SAR' });
            c.schemaForm.form.patchValue({ format2Style: 'currency', format2Currency: ' usd ', format2Compact: true });
            c.save();
            expect(last(ref).format2).toEqual({ style: 'currency', currency: 'USD', compact: true, decimals: 1 });
            c.schemaForm.form.patchValue({
                format2Style: '',
                format2Currency: '',
                format2Compact: false,
                format2Decimals: null,
            });
            c.save();
            expect(last(ref).format2).toBeUndefined();
            expect(last(ref).format).toEqual({ style: 'currency', currency: 'SAR' });
        });

        it('a field hidden for this vizType keeps its stored value through a save', () => {
            const stored: WidgetOptions = {
                trend: { compareBack: 4 },
                progress: { max: 9 },
                treemap: { limit: 5 },
                format2: { style: 'percent' },
            };
            const { c, ref } = create(stored, 'bar');
            c.save();
            const out = last(ref);
            expect(out.trend).toEqual(stored.trend);
            expect(out.progress).toEqual(stored.progress);
            expect(out.treemap).toEqual(stored.treemap);
            expect(out.format2).toEqual(stored.format2);
        });

        it('unmodelled keys survive a save with the per-type fields shown', () => {
            const { c, ref } = create(
                { columnLabels: { a: 'A' }, badgeColumns: ['s'], trend: { compareBack: 2 } },
                'kpi-trend',
            );
            c.save();
            expect(last(ref).columnLabels).toEqual({ a: 'A' });
            expect(last(ref).badgeColumns).toEqual(['s']);
        });

        it('renders the combo fields with no a11y violations', async () => {
            const { fixture } = create({ format2: { style: 'currency', currency: 'SAR' } }, 'combo');
            await expectNoA11yViolations(fixture.nativeElement);
        });
    });
});
