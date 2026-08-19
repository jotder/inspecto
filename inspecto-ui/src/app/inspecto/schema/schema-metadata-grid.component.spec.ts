import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SchemaFieldRow } from './schema-fields-editor.component';
import { InspectoSchemaMetadataGridComponent } from './schema-metadata-grid.component';

@Component({
    standalone: true,
    imports: [InspectoSchemaMetadataGridComponent],
    template: `<inspecto-schema-metadata-grid [rows]="rows()" />`,
})
class HostComponent {
    rows = signal<SchemaFieldRow[]>([]);
}

const ROWS: SchemaFieldRow[] = [
    { include: true, name: 'IMSI', selector: '0', type: 'VARCHAR', description: 'subscriber id' },
    { include: true, name: 'DURATION', selector: '1', type: 'DOUBLE', unit: 'seconds' },
];

function create(rows: SchemaFieldRow[]) {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.rows.set(rows);
    fixture.detectChanges();
    const grid = fixture.debugElement.children[0].componentInstance as InspectoSchemaMetadataGridComponent;
    return { fixture, grid };
}

describe('InspectoSchemaMetadataGridComponent', () => {
    it('seeds from the rows, edits only the metadata keys, and applyTo merges by selector', async () => {
        const { fixture, grid } = create(ROWS);
        const inputs = Array.from(fixture.nativeElement.querySelectorAll('input')) as HTMLInputElement[];
        // 3 metadata inputs per row; identity renders as read-only text, not inputs.
        expect(inputs).toHaveLength(6);
        expect(inputs[0].value).toBe('subscriber id');

        inputs[2].value = 'MSISDN'; // row 0 classification
        inputs[2].dispatchEvent(new Event('input'));
        inputs[3].value = ' call length '; // row 1 description, trimmed on read
        inputs[3].dispatchEvent(new Event('input'));
        expect(grid.form.dirty).toBe(true);

        // applyTo merges onto the COLUMNS TABLE's rows (which never carry metadata), by selector —
        // and a cleared field removes its key rather than writing an empty string.
        inputs[0].value = '';
        inputs[0].dispatchEvent(new Event('input'));
        const merged = grid.applyTo([
            { include: true, name: 'IMSI_RENAMED', selector: '0', type: 'VARCHAR' },
            { include: true, name: 'DURATION', selector: '1', type: 'DOUBLE' },
        ]);
        expect(merged[0]).toEqual({
            include: true,
            name: 'IMSI_RENAMED',
            selector: '0',
            type: 'VARCHAR',
            classification: 'MSISDN',
        });
        expect(merged[1]).toEqual({
            include: true,
            name: 'DURATION',
            selector: '1',
            type: 'DOUBLE',
            description: 'call length',
            unit: 'seconds',
        });

        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('a reseed rebuilds the form pristine', () => {
        const { fixture, grid } = create(ROWS);
        const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
        input.value = 'edited';
        input.dispatchEvent(new Event('input'));
        expect(grid.form.dirty).toBe(true);

        fixture.componentInstance.rows.set([{ include: true, name: 'A', selector: '0', type: 'VARCHAR' }]);
        fixture.detectChanges();
        expect(grid.form.dirty).toBe(false);
        expect(grid.applyTo([{ include: true, name: 'A', selector: '0', type: 'VARCHAR' }])[0].description).toBe(
            undefined,
        );
    });
});
