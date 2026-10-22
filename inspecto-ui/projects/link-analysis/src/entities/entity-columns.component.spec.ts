import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { pickerSet } from '@inspecto/core/testing/pen-field';
import { DatasetColumnsService } from '@inspecto/link-analysis/api/dataset-columns.service';
import type { EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import { COLUMNS_LOCKED_REASON, EntityColumnsComponent } from './entity-columns.component';
import type { DatasetEntry, EntitiesChange } from './entity-columns';

const headline = {
    distinct: null,
    approximate: false,
    rows: null,
    dataAsOf: null,
    computedAt: null,
    assessed: false,
    reason: null,
};
const ACCOUNT: EntityTypeSummary = {
    id: 'account',
    label: 'Account',
    normaliser: 'upper-trim',
    masked: false,
    classifications: ['ACCOUNT'],
    bindings: [{ dataset: 'transfers', column: 'from_account', role: 'source' }],
    headline,
};
const DEVICE: EntityTypeSummary = {
    id: 'device',
    label: 'Device',
    normaliser: 'digits',
    masked: false,
    classifications: ['IMEI'],
    bindings: [],
    headline,
};
const TRANSFERS: DatasetEntry = {
    id: 'transfers',
    contentHash: 'hash-1',
    content: {
        physicalRef: 'transfers/database',
        columns: [{ name: 'from_account', classification: 'ACCOUNT' }, { name: 'to_account' }, { name: 'amount' }],
        defaultLinkMapping: { from: 'from_account', to: 'to_account' },
    },
};
const BARE: DatasetEntry = { id: 'bare', contentHash: 'h', content: { physicalRef: 'x' } };

@Component({
    standalone: true,
    imports: [EntityColumnsComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-entity-columns
        [types]="types()"
        [datasets]="datasets()"
        [canEdit]="canEdit()"
        [result]="result()"
        (changed)="changes.push($event)"
    ></inspecto-la-entity-columns>`,
})
class Host {
    readonly types = signal<EntityTypeSummary[]>([ACCOUNT, DEVICE]);
    readonly datasets = signal<DatasetEntry[] | null>([TRANSFERS, BARE]);
    readonly canEdit = signal(true);
    readonly result = signal('');
    readonly changes: EntitiesChange[] = [];
}

function make(opts: { setFails?: boolean; updateFails?: boolean } = {}) {
    const setEntityType = vi.fn(() =>
        opts.setFails
            ? throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: 'no such column' } } }))
            : of({ dataset: 'transfers', column: 'to_account', classification: 'ACCOUNT', columns: [] }),
    );
    const update = vi.fn(() =>
        opts.updateFails
            ? throwError(
                  () => new HttpErrorResponse({ status: 409, error: { error: { message: 'changed elsewhere' } } }),
              )
            : of({}),
    );
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: DatasetColumnsService, useValue: { setEntityType } },
            { provide: ComponentsService, useValue: { update } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, host: fixture.componentInstance, setEntityType, update };
}

/** A cell's value: the picker trigger's text when it holds one (its label is visually hidden), else the cell text. */
const cellValue = (cell: Element) => (cell.querySelector('button') ?? cell).textContent!.trim();
const rowsText = (el: HTMLElement) =>
    Array.from(el.querySelectorAll('[data-testid=column-row]')).map((r) =>
        [
            r.querySelector('[data-testid=column-name]')!.textContent!.trim(),
            cellValue(r.querySelector('[data-testid=column-type]')!),
            r.querySelector('[data-testid=column-note]')!.textContent!.trim(),
        ].join(' | '),
    );

describe('EntityColumnsComponent', () => {
    it('lists each identifier column with what it holds or Not set, and why it is listed', () => {
        const { el } = make();
        const sections = el.querySelectorAll('[data-testid=dataset-columns]');
        expect(sections).toHaveLength(2);
        expect(rowsText(el)).toEqual([
            'from_account | Account | Links start here',
            'to_account | Not set | Links end here · Looks like Account',
        ]);
        expect(sections[1].querySelector('[data-testid=no-columns]')).toBeTruthy();
    });

    it('Show every column lists the rest', () => {
        const { el, fixture } = make();
        (el.querySelector('[data-testid=show-all] input') as HTMLInputElement).click();
        fixture.detectChanges();
        expect(rowsText(el).map((r) => r.split(' | ')[0])).toEqual(['from_account', 'to_account', 'amount']);
    });

    it('setting what a column holds writes it at once and reports the change', () => {
        const { fixture, host, setEntityType } = make();
        pickerSet(fixture, '[data-column="transfers/to_account"]', 'account');
        expect(setEntityType).toHaveBeenCalledWith('transfers', 'to_account', 'account');
        expect(host.changes).toEqual([
            { kind: 'column', dataset: 'transfers', column: 'to_account', entityType: 'account', previous: null },
        ]);
    });

    it('Not set clears it (null, never an empty id)', () => {
        const { fixture, setEntityType, host } = make();
        pickerSet(fixture, '[data-column="transfers/from_account"]', '');
        expect(setEntityType).toHaveBeenCalledWith('transfers', 'from_account', null);
        expect(host.changes[0]).toMatchObject({ entityType: null, previous: 'account' });
    });

    it('a refused change goes back to what is stored and says why', () => {
        const { fixture, el, host } = make({ setFails: true });
        pickerSet(fixture, '[data-column="transfers/to_account"]', 'device');
        fixture.detectChanges();
        expect(host.changes).toEqual([]);
        expect(el.querySelector('[data-testid=columns-error]')!.textContent).toContain('no such column');
        expect(rowsText(el)[1]).toContain('Not set');
    });

    it('the primary Entity Type is written on the registry entry with its hash; None removes the key', () => {
        const { fixture, update, host } = make();
        pickerSet(fixture, '[data-testid=primary-picker][data-dataset=transfers]', 'account');
        expect(update).toHaveBeenCalledWith(
            'dataset',
            'transfers',
            { ...TRANSFERS.content, primaryEntityType: 'account' },
            { ifMatch: 'hash-1' },
        );
        expect(host.changes).toEqual([{ kind: 'primary', dataset: 'transfers', entityType: 'account' }]);

        // fresh inputs (the host re-read the registry) rebuild the controls from what is stored
        host.datasets.set([
            { ...TRANSFERS, contentHash: 'hash-2', content: { ...TRANSFERS.content, primaryEntityType: 'account' } },
        ]);
        fixture.detectChanges();
        pickerSet(fixture, '[data-testid=primary-picker][data-dataset=transfers]', '');
        const [, , content, opts] = update.mock.calls[1] as unknown as [
            string,
            string,
            Record<string, unknown>,
            unknown,
        ];
        expect('primaryEntityType' in content).toBe(false);
        expect(opts).toEqual({ ifMatch: 'hash-2' });
    });

    it('a refused primary change says why and keeps what is stored', () => {
        const { fixture, el, host } = make({ updateFails: true });
        pickerSet(fixture, '[data-testid=primary-picker][data-dataset=transfers]', 'device');
        fixture.detectChanges();
        expect(host.changes).toEqual([]);
        expect(el.querySelector('[data-testid=columns-error]')!.textContent).toContain('changed elsewhere');
    });

    it('without the right the values read as text and the action is locked with its reason', () => {
        const { fixture, el, host } = make();
        host.canEdit.set(false);
        fixture.detectChanges();
        expect(el.querySelector('inspecto-option-picker')).toBeNull();
        expect(el.querySelector('[data-testid=locked-reason]')!.textContent).toContain(COLUMNS_LOCKED_REASON);
        expect(rowsText(el)[0]).toContain('Account');
        expect(el.querySelector('[data-testid=primary-text]')!.textContent).toContain('none');
    });

    it('states the host result in a live region, and says when there is no Dataset yet', () => {
        const { fixture, el, host } = make();
        host.result.set('"to_account" in transfers now holds Account.');
        fixture.detectChanges();
        expect(el.querySelector('[role=status]')!.textContent).toContain('now holds Account');
        host.datasets.set([]);
        fixture.detectChanges();
        expect(el.querySelector('inspecto-empty-state')!.textContent).toContain('No Dataset yet');
        // its one next action: the Add data screen (LA-ONBOARD-1 phase 8)
        const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
        (el.querySelector('[data-testid=columns-no-dataset] button') as HTMLButtonElement).click();
        expect(navigate).toHaveBeenCalledWith('/add-data');
        host.datasets.set(null);
        fixture.detectChanges();
        expect(el.querySelector('inspecto-skeleton')).toBeTruthy();
    });

    it('has no axe violations, editable and locked', async () => {
        const { fixture, el, host } = make();
        await expectNoA11yViolations(el);
        host.canEdit.set(false);
        fixture.detectChanges();
        await expectNoA11yViolations(el);
    }, 30_000);
});
