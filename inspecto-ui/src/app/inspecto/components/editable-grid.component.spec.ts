import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { EditableGridComponent, parseCsv } from './editable-grid.component';

const COLUMNS = [
    { key: 'targetColumn', label: 'Target column' },
    { key: 'sourceExpression', label: 'Source expression' },
    { key: 'transformType', label: 'Transform type', options: ['DIRECT', 'EXPR'] },
];

function create(inputs: Partial<EditableGridComponent>) {
    TestBed.configureTestingModule({
        imports: [EditableGridComponent],
        // the real theme service walks up to GAMMA_APP_CONFIG — stub it like data-table's spec does
        providers: [provideNoopAnimations(), { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } }],
    });
    const fixture = TestBed.createComponent(EditableGridComponent);
    Object.assign(fixture.componentInstance, inputs);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

describe('EditableGridComponent', () => {
    it('renders the column headers and row values', async () => {
        const { fixture } = create({
            columns: COLUMNS,
            rows: [{ targetColumn: 'AMOUNT', sourceExpression: 'amt', transformType: 'DIRECT' }],
        });
        await fixture.whenStable();
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Target column');
        expect(text).toContain('AMOUNT');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('addRow appends an empty row keyed by the columns and emits the full list', () => {
        const { c } = create({ columns: COLUMNS, rows: [] });
        const emitted: Record<string, string>[][] = [];
        c.rowsChange.subscribe((r) => emitted.push(r));
        c.addRow();
        expect(emitted).toEqual([[{ targetColumn: '', sourceExpression: '', transformType: '' }]]);
    });

    it('hides the toolbar when not editable', () => {
        const { fixture } = create({ columns: COLUMNS, rows: [], editable: false });
        expect((fixture.nativeElement as HTMLElement).querySelectorAll('button')).toHaveLength(0);
    });
});

describe('parseCsv', () => {
    it('parses quoted cells carrying commas and doubled quotes (how EXPR expressions travel)', () => {
        const rows = parseCsv('targetColumn,sourceExpression,transformType\nA,"concat(x, ""y"")",EXPR\r\nB,b,\n');
        expect(rows).toEqual([
            ['targetColumn', 'sourceExpression', 'transformType'],
            ['A', 'concat(x, "y")', 'EXPR'],
            ['B', 'b', ''],
        ]);
    });

    it('drops blank lines and handles a missing trailing newline', () => {
        expect(parseCsv('a,b\n\n1,2')).toEqual([['a', 'b'], ['1', '2']]);
    });
});
