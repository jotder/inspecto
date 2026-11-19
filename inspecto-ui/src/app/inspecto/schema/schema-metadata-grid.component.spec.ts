import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { Classification, ClassificationsService } from 'app/inspecto/api/classifications.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, penText, pickerSet } from 'app/inspecto/testing/pen-field';
import { InspectoOptionPickerComponent } from '../components/option-picker.component';
import { BASIC_COLUMN_TYPES } from './basic-column-types';
import { SchemaFieldRow } from './schema-fields-editor.component';
import {
    InspectoSchemaMetadataGridComponent,
    SchemaTypeRow,
    SchemaTypeRowChange,
} from './schema-metadata-grid.component';

@Component({
    standalone: true,
    imports: [InspectoSchemaMetadataGridComponent],
    template: `<inspecto-schema-metadata-grid [rows]="rows()" />`,
})
class HostComponent {
    rows = signal<SchemaFieldRow[]>([]);
}

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoSchemaMetadataGridComponent],
    template: `
        <inspecto-schema-metadata-grid
            mode="types"
            [typeRows]="rows()"
            [rowErrors]="errors"
            [rowClasses]="classes"
            [rowDetail]="detail"
            (rowChange)="changes.push($event)"
        />
        <ng-template #detail let-name
            ><span data-testid="detail">why {{ name }}</span></ng-template
        >
    `,
})
class TypesHostComponent {
    rows = signal<SchemaTypeRow[]>([
        { selector: 'txn_id', name: 'txn_id', type: 'VARCHAR', leaveOut: false },
        { selector: 'amount', name: 'amount', type: 'DOUBLE', leaveOut: false },
    ]);
    readonly errors = new Map([['txn_id', 'Taken.']]);
    readonly classes = new Map([['amount', 'guess']]);
    readonly changes: SchemaTypeRowChange[] = [];
}

function createTypes() {
    TestBed.configureTestingModule({ imports: [TypesHostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(TypesHostComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
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

    // ── mode="types" (BASIC-ELT-PROFILE-1 U1a): Add data's column review ─────────────────────────────────────────────

    it('types mode: one row per column, Basic types only, the host slot under each kept row, every edit emitted (no violations)', async () => {
        const { fixture, el, host } = createTypes();
        expect(el.querySelector('table')).toBeNull(); // the metadata table is not drawn
        expect(Array.from(el.querySelectorAll('[data-column]')).map((r) => r.getAttribute('data-column'))).toEqual([
            'txn_id',
            'amount',
        ]);
        expect(penText(penByLabel(el, 'Column 1')!)).toContain('txn_id');
        const picker = fixture.debugElement.queryAll(By.directive(InspectoOptionPickerComponent))[0]
            .componentInstance as { options: () => { value: string }[] };
        expect(picker.options().map((o) => o.value)).toEqual([...BASIC_COLUMN_TYPES]);
        expect(el.querySelector('[data-column="amount"] [data-testid="detail"]')!.textContent).toBe('why amount');
        expect(el.querySelector('[data-column="amount"]')!.className).toContain('guess');
        expect(el.querySelector('[data-column="txn_id"] [role="alert"]')!.textContent).toContain('Taken.');
        await expectNoA11yViolations(el);

        penEdit(fixture, penByLabel(el, 'Column 1')!, 'transfer_id');
        pickerSet(fixture, '[data-testid="type-amount"]', 'VARCHAR');
        (el.querySelector('[data-column="amount"] [data-testid="drop-column"] input') as HTMLInputElement).click();
        fixture.detectChanges();
        expect(host.changes).toEqual([
            { selector: 'txn_id', name: 'transfer_id' },
            { selector: 'amount', type: 'VARCHAR' },
            { selector: 'amount', leaveOut: true },
        ]);
        // a left-out row: no detail, and its name and type read only
        expect(el.querySelector('[data-column="amount"] [data-testid="detail"]')).toBeNull();
        expect(el.querySelector('[data-column="amount"] .pen-pencil')).toBeNull();
    });

    it('types mode: an update of the same columns from the host applies in place and is never echoed; other columns rebuild', () => {
        const { fixture, el, host } = createTypes();
        const row = el.querySelector('[data-column="amount"]');
        host.rows.set([
            { selector: 'txn_id', name: 'id', type: 'VARCHAR', leaveOut: false },
            { selector: 'amount', name: 'amount', type: 'BIGINT', leaveOut: true },
        ]);
        fixture.detectChanges();
        expect(host.changes).toEqual([]);
        expect(el.querySelector('[data-column="amount"]')).toBe(row); // the same row, updated
        expect(penText(penByLabel(el, 'Column 1')!)).toContain('id');
        expect(
            el.querySelector<HTMLInputElement>('[data-column="amount"] [data-testid="drop-column"] input')!.checked,
        ).toBe(true);
        host.rows.set([{ selector: 'other', name: 'other', type: 'DATE', leaveOut: false }]);
        fixture.detectChanges();
        expect(Array.from(el.querySelectorAll('[data-column]')).map((r) => r.getAttribute('data-column'))).toEqual([
            'other',
        ]);
        expect(host.changes).toEqual([]);
    });
});
