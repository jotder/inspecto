import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { Classification, ClassificationsService } from 'app/inspecto/api/classifications.service';
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

const VOCABULARY: Classification[] = [
    { class: 'CELL_ID', masked: false, source: 'entity-type', entityType: 'cell', label: 'Cell' },
    { class: 'INTERNAL', masked: false, source: 'not-masked' },
    { class: 'MSISDN', masked: true, source: 'sensitive' },
    { class: 'SUBSCRIBER', masked: true, source: 'entity-type', entityType: 'subscriber', label: 'Subscriber' },
];

function create(rows: SchemaFieldRow[], status: 'ready' | 'error' = 'ready') {
    let loads = 0;
    const vocabulary = {
        items: signal(status === 'ready' ? VOCABULARY : []),
        status: signal(status),
        ensureLoaded: () => loads++,
    };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [provideNoopAnimations(), { provide: ClassificationsService, useValue: vocabulary }],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.rows.set(rows);
    fixture.detectChanges();
    const grid = fixture.debugElement.children[0].componentInstance as InspectoSchemaMetadataGridComponent;
    const el = fixture.nativeElement as HTMLElement;
    const selects = () => Array.from(el.querySelectorAll('select')) as HTMLSelectElement[];
    const inputs = () => Array.from(el.querySelectorAll('input')) as HTMLInputElement[];
    return { fixture, grid, selects, inputs, loads: () => loads };
}

function choose(select: HTMLSelectElement, value: string): void {
    select.value = value;
    select.dispatchEvent(new Event('change'));
}

describe('InspectoSchemaMetadataGridComponent', () => {
    it('seeds from the rows, edits only the metadata keys, and applyTo merges by selector', async () => {
        const { fixture, grid, selects, inputs } = create(ROWS);
        // description + unit per row are text; the classification is a picker. Identity renders as read-only text.
        expect(inputs()).toHaveLength(4);
        expect(selects()).toHaveLength(2);
        expect(inputs()[0].value).toBe('subscriber id');

        choose(selects()[0], 'MSISDN'); // row 0 classification
        const desc1 = inputs()[2];
        desc1.value = ' call length '; // row 1 description, trimmed on read
        desc1.dispatchEvent(new Event('input'));
        expect(grid.form.dirty).toBe(true);

        // applyTo merges onto the COLUMNS TABLE's rows (which never carry metadata), by selector —
        // and a cleared field removes its key rather than writing an empty string.
        const desc0 = inputs()[0];
        desc0.value = '';
        desc0.dispatchEvent(new Event('input'));
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

    // UI-CLASSIFICATION-PICKER-1: the cell offers the Space's vocabulary, never free text a save would refuse.
    it('offers the Space vocabulary, marks the classes that mask, and None clears the value', async () => {
        const { fixture, grid, selects, loads } = create([
            { include: true, name: 'A_NUMBER', selector: '0', type: 'VARCHAR', classification: 'msisdn' },
        ]);
        expect(loads()).toBeGreaterThan(0);
        const select = selects()[0];
        expect(select.getAttribute('aria-label')).toBe('Classification for A_NUMBER');
        const groups = Array.from(select.querySelectorAll('optgroup')).map((g) => [
            g.label,
            Array.from(g.querySelectorAll('option')).map((o) => o.textContent!.trim()),
        ]);
        expect(groups).toEqual([
            // an Entity Type's label is shown only when it says more than the class itself
            ['Masks the column', ['MSISDN (masks)', 'SUBSCRIBER (masks)']],
            ['Does not mask', ['CELL_ID · Cell', 'INTERNAL']],
        ]);
        // a stored class in another case is the known class, shown selected and not flagged
        expect(select.value).toBe('MSISDN');
        expect(select.getAttribute('aria-invalid')).toBeNull();
        expect(grid.form.dirty).toBe(false);

        choose(select, '');
        fixture.detectChanges();
        expect(grid.form.dirty).toBe(true);
        expect(grid.value().get('0')).toEqual({});
        expect(select.value).toBe('');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('keeps a stored class the Space does not know, flagged, until the author picks a known one', async () => {
        const { fixture, grid, selects } = create([
            { include: true, name: 'CELL', selector: '0', type: 'VARCHAR', classification: 'CONFIDENTIAL' },
        ]);
        const select = selects()[0];
        expect(select.value).toBe('CONFIDENTIAL');
        expect(select.selectedOptions[0].textContent!.trim()).toBe('CONFIDENTIAL (unknown)');
        expect(select.querySelector('optgroup[label="Not known to this Space"]')).not.toBeNull();
        expect(select.getAttribute('aria-invalid')).toBe('true');
        expect(grid.value().get('0')).toEqual({ classification: 'CONFIDENTIAL' }); // opening never drops it
        await expectNoA11yViolations(fixture.nativeElement);

        choose(select, 'CELL_ID');
        fixture.detectChanges();
        expect(grid.value().get('0')).toEqual({ classification: 'CELL_ID' });
        expect(select.getAttribute('aria-invalid')).toBeNull();
        expect(select.querySelector('optgroup[label="Not known to this Space"]')).toBeNull();
    });

    it('falls back to text when the vocabulary cannot be read', () => {
        const { grid, selects, inputs } = create(
            [{ include: true, name: 'CELL', selector: '0', type: 'VARCHAR', classification: 'CELL_ID' }],
            'error',
        );
        expect(selects()).toHaveLength(0);
        const cls = inputs().find((i) => i.getAttribute('aria-label') === 'Classification for CELL')!;
        expect(cls.value).toBe('CELL_ID');
        cls.value = 'INTERNAL';
        cls.dispatchEvent(new Event('input'));
        expect(grid.value().get('0')).toEqual({ classification: 'INTERNAL' });
    });
});
