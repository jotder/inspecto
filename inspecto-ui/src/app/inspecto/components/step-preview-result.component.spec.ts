import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { RelationsPreview } from 'app/inspecto/api';
import { StepPreviewResultComponent } from './step-preview-result.component';

@Component({
    standalone: true,
    imports: [StepPreviewResultComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-step-preview-result [result]="result()" />`,
})
class HostComponent {
    result = signal<RelationsPreview>(DERIVED);
}

/** What the SERVER returns: rows, a DESCRIBE-derived schema, and the statements it ran. */
const DERIVED: RelationsPreview = {
    inputColumns: ['id', 'amt'],
    relations: [
        {
            rel: 'data',
            rowCount: 2,
            rows: [
                { ident: '1', amt_d: 150 },
                { ident: '3', amt_d: 90 },
            ],
            columnTypes: [
                { name: 'ident', type: 'VARCHAR' },
                { name: 'amt_d', type: 'DOUBLE' },
            ],
        },
    ],
    sql: [
        'CREATE TABLE preview_m__data AS SELECT "id" AS "ident", CAST(amt AS DOUBLE) AS "amt_d" FROM "preview_input"',
    ],
};

/** What the OFFLINE MOCK returns — no SQL engine, so no derived schema and no SQL. */
const OFFLINE: RelationsPreview = {
    inputColumns: ['id', 'amt'],
    relations: [{ rel: 'data', rowCount: 2, rows: [{ id: '1', amt: '150' }], columnTypes: [] }],
    sql: [],
};

function render(result: RelationsPreview) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        // the real theme service walks up to GAMMA_APP_CONFIG — stub it, as the data-table's own spec does
        providers: [
            provideNoopAnimations(),
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.result.set(result);
    fixture.detectChanges();
    return fixture;
}

describe('StepPreviewResultComponent', () => {
    it('publishes the derived schema — the author never restates it', () => {
        const el: HTMLElement = render(DERIVED).nativeElement;
        const text = el.textContent ?? '';
        expect(text).toContain('Derived schema');
        // name AND DuckDB's own type, verbatim — a coarse mapping would lose exactly what the author needs
        expect(text).toContain('ident');
        expect(text).toContain('VARCHAR');
        expect(text).toContain('amt_d');
        expect(text).toContain('DOUBLE');
    });

    it('renders the produced rows as a table rather than a row count', () => {
        const el: HTMLElement = render(DERIVED).nativeElement;
        expect(el.querySelector('inspecto-data-table')).not.toBeNull();
        expect(el.textContent).toContain('2 row(s)');
    });

    it('shows the SQL the Step actually ran', () => {
        const el: HTMLElement = render(DERIVED).nativeElement;
        const pre = el.querySelector('pre');
        expect(pre).not.toBeNull();
        expect(pre?.textContent).toContain('CAST(amt AS DOUBLE)');
    });

    /**
     * ⚠ The offline arm must SAY it cannot derive these, not render an empty schema — a blank chip row
     * reads as "this Step produces no columns", which is a different and false claim.
     */
    it('explains itself offline instead of showing an empty schema', () => {
        const el: HTMLElement = render(OFFLINE).nativeElement;
        expect(el.textContent).toContain('Derived offline');
        expect(el.textContent).not.toContain('Derived schema');
        expect(el.querySelector('pre')).toBeNull();
    });

    it('has no accessibility violations', async () => {
        const fixture = render(DERIVED);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
