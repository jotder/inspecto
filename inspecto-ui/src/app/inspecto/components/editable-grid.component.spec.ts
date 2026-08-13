import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CsvImport, EditableGridComponent, parseCsv } from './editable-grid.component';

const COLUMNS = [
    { key: 'targetColumn', label: 'Target column' },
    { key: 'sourceExpression', label: 'Source expression' },
    { key: 'transformType', label: 'Transform type', options: ['DIRECT', 'EXPR'] },
];

function create(inputs: Partial<EditableGridComponent>) {
    TestBed.configureTestingModule({
        imports: [EditableGridComponent],
        // the real theme service walks up to GAMMA_APP_CONFIG — stub it like data-table's spec does
        providers: [
            provideNoopAnimations(),
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
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

    describe('importCsv (S6b)', () => {
        const EXISTING = [{ targetColumn: 'AMOUNT', sourceExpression: 'amt', transformType: 'DIRECT' }];

        /** Drive importCsv with `text` as the chosen file, returning the emitted import. */
        async function importText(c: EditableGridComponent, text: string, name = 'rules.csv') {
            const file = { name, text: () => Promise.resolve(text) };
            const input = { files: [file], value: name } as unknown as HTMLInputElement;
            let emitted: CsvImport | undefined;
            c.imported.subscribe((e) => (emitted = e));
            await c.importCsv({ target: input } as unknown as Event);
            return emitted!;
        }

        it('matches header cells by key and replaces the rows', async () => {
            const { c } = create({ columns: COLUMNS, rows: EXISTING });
            const e = await importText(c, 'targetColumn,sourceExpression,transformType\nMSISDN,msisdn,DIRECT\n');
            expect(e.applied).toBe(true);
            expect(e.rows).toEqual([{ targetColumn: 'MSISDN', sourceExpression: 'msisdn', transformType: 'DIRECT' }]);
            expect(c.gridRows()).toEqual(e.rows);
        });

        it('also matches by label, since Export writes keys but the operator sees labels', async () => {
            const { c } = create({ columns: COLUMNS, rows: [] });
            const e = await importText(c, 'Target column,Source expression,Transform type\nA,a,EXPR\n');
            expect(e.rows).toEqual([{ targetColumn: 'A', sourceExpression: 'a', transformType: 'EXPR' }]);
            expect(e.missingColumns).toEqual([]);
        });

        it('REFUSES an import whose header matches nothing, leaving the rows untouched', async () => {
            // The old behaviour replaced every row with blanks — a wrong file that looked like a
            // successful import of an empty mapping.
            const { c } = create({ columns: COLUMNS, rows: EXISTING });
            const e = await importText(c, 'foo,bar\n1,2\n', 'wrong.csv');
            expect(e.applied).toBe(false);
            expect(e.rows).toEqual([]);
            expect(e.unknownHeaders).toEqual(['foo', 'bar']);
            expect(c.gridRows()).toEqual(EXISTING);
        });

        it('reports a partial header match: unknown columns ignored, absent ones blank', async () => {
            const { c } = create({ columns: COLUMNS, rows: [] });
            const e = await importText(c, 'targetColumn,notes\nA,hello\n');
            expect(e.applied).toBe(true);
            expect(e.unknownHeaders).toEqual(['notes']);
            expect(e.missingColumns).toEqual(['sourceExpression', 'transformType']);
            expect(e.rows).toEqual([{ targetColumn: 'A', sourceExpression: '', transformType: '' }]);
        });
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
        expect(parseCsv('a,b\n\n1,2')).toEqual([
            ['a', 'b'],
            ['1', '2'],
        ]);
    });
});
