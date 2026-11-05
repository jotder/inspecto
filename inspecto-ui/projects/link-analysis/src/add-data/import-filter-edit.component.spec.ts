import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, describe, expect, it } from 'vitest';
import { apiUrl } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penEdit, pickerSet } from '@inspecto/core/testing/pen-field';
import { ImportFilterEditComponent } from './import-filter-edit.component';
import { ImportFilterState, RowCondition } from './add-data.model';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [ImportFilterEditComponent],
    template: `<inspecto-import-filter-edit
        datasetId="calls"
        [locked]="locked()"
        [busy]="busy()"
        [snapshot]="snapshot()"
        (saved)="saved.set($event)"
    ></inspecto-import-filter-edit>`,
})
class HostComponent {
    readonly locked = signal<string | null>(null);
    readonly busy = signal(false);
    readonly snapshot = signal(false);
    readonly saved = signal<RowCondition[] | null>(null);
    readonly panel = viewChild(ImportFilterEditComponent);
}

const STATE: ImportFilterState = {
    dataset: 'calls',
    filter: [{ column: 'via', op: 'eq', value: 'mobile' }],
    columns: [
        { name: 'via', type: 'VARCHAR' },
        { name: 'qty', type: 'BIGINT' },
    ],
};

let http: HttpTestingController;

function create() {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [provideNoopAnimations(), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const at = (el: HTMLElement, i: number, id: string) =>
    el.querySelector<HTMLElement>(`[data-condition="${i}"] [data-testid="${id}"]`)!;

describe('ImportFilterEditComponent - Edit filter on the Dataset card (ADD-DATA-FILTER-EDIT-1)', () => {
    afterEach(() => http?.verify());

    it('opens on the stored conditions, says it applies to the next files, and saves the edited ones (no violations)', async () => {
        const { fixture, el } = create();
        expect(q(el, 'filter-editor')).toBeNull();
        q(el, 'edit-filter')!.click();
        http.expectOne(apiUrl('/datasets/calls/filter')).flush(STATE);
        fixture.detectChanges();

        expect(q(el, 'filter-applies')!.textContent).toContain(
            'Applies to files added from now on; rows already imported stay.',
        );
        expect(q(el, 'filter-keeps-line')!.textContent).toBe('Keeps rows where via is mobile.');
        await expectNoA11yViolations(el);

        // a new condition holds Save back until it is complete
        (q(el, 'add-condition') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(el.querySelector('[data-condition="1"] [role="alert"]')!.textContent).toContain('Choose a column.');
        expect((q(el, 'save-filter') as HTMLButtonElement).disabled).toBe(true);
        pickerSet(fixture, '[data-condition="1"] [data-testid="condition-column"]', 'qty');
        pickerSet(fixture, '[data-condition="1"] [data-testid="condition-op"]', 'range');
        penEdit(fixture, at(el, 1, 'condition-min'), '10');
        fixture.detectChanges();
        expect((q(el, 'save-filter') as HTMLButtonElement).disabled).toBe(false);
        expect(q(el, 'filter-keeps-line')!.textContent).toBe('Keeps rows where via is mobile and qty is 10 or more.');

        (q(el, 'save-filter') as HTMLButtonElement).click();
        const put = http.expectOne(apiUrl('/datasets/calls/filter'));
        expect(put.request.method).toBe('PUT');
        const sent = [
            { column: 'via', op: 'eq', value: 'mobile' },
            { column: 'qty', op: 'range', min: '10' },
        ];
        expect(put.request.body).toEqual({ filter: sent });
        put.flush({ ...STATE, filter: sent });
        fixture.detectChanges();

        expect(fixture.componentInstance.saved()).toEqual(sent);
        expect(q(el, 'filter-editor')).toBeNull();
        expect(q(el, 'filter-note')!.textContent).toContain(
            'Saved. Keeps rows where via is mobile and qty is 10 or more. Applies to files added from now on; rows already imported stay.',
        );
    });

    it('clearing every condition sends an empty filter; a refusal is shown and the editor stays open', () => {
        const { fixture, el } = create();
        q(el, 'edit-filter')!.click();
        http.expectOne(apiUrl('/datasets/calls/filter')).flush(STATE);
        fixture.detectChanges();

        (at(el, 0, 'remove-condition') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(q(el, 'every-row')!.textContent).toContain('Every row is kept.');
        (q(el, 'save-filter') as HTMLButtonElement).click();
        const put = http.expectOne(apiUrl('/datasets/calls/filter'));
        expect(put.request.body).toEqual({ filter: [] });
        put.flush(
            { error: { message: "filter[0] (qty eq): '1 OR 1=1' is not a whole number" } },
            { status: 422, statusText: 'Unprocessable' },
        );
        fixture.detectChanges();

        expect(q(el, 'filter-note')!.textContent).toContain('is not a whole number');
        expect(q(el, 'filter-editor')).not.toBeNull();
        expect(fixture.componentInstance.saved()).toBeNull();
    });

    it('is disabled while Add data is locked here', () => {
        const { fixture, el } = create();
        fixture.componentInstance.locked.set('Adding data is not allowed here.');
        fixture.detectChanges();
        expect((q(el, 'edit-filter') as HTMLButtonElement).disabled).toBe(true);
    });

    it('a Postgres snapshot says the filter applies at the next refresh; Save waits while an import runs', () => {
        const { fixture, el } = create();
        fixture.componentInstance.snapshot.set(true);
        fixture.detectChanges();
        q(el, 'edit-filter')!.click();
        http.expectOne(apiUrl('/datasets/calls/filter')).flush(STATE);
        fixture.detectChanges();
        expect(q(el, 'filter-applies')!.textContent).toContain('Applies at the next Refresh now');
        expect(q(el, 'filter-applies')!.textContent).not.toContain('rows already imported stay');
        expect((q(el, 'save-filter') as HTMLButtonElement).disabled).toBe(false);
        fixture.componentInstance.busy.set(true);
        fixture.detectChanges();
        expect((q(el, 'save-filter') as HTMLButtonElement).disabled).toBe(true);
    });
});
