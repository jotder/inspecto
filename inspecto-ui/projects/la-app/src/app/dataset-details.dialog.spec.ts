import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { STALE_WRITE_MESSAGE, apiUrl } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { DatasetDetailsData, DatasetDetailsDialog } from './dataset-details.dialog';

let http: HttpTestingController;

const STORED = {
    type: 'dataset',
    name: 'transfers',
    ref: 'dataset/transfers',
    contentHash: 'abc123',
    content: { name: 'transfers', physicalRef: 'transfers/database', columns: [{ name: 'a', type: 'string' }] },
};

function create(data: DatasetDetailsData = { id: 'transfers', name: '', description: '' }) {
    const close = vi.fn();
    TestBed.configureTestingModule({
        imports: [DatasetDetailsDialog],
        providers: [
            provideHttpClient(),
            provideHttpClientTesting(),
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: data },
            {
                provide: MatDialogRef,
                useValue: {
                    close,
                    disableClose: false,
                    backdropClick: () => ({ subscribe() {} }),
                    keydownEvents: () => ({ subscribe() {} }),
                },
            },
        ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DatasetDetailsDialog);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, c: fixture.componentInstance, close };
}

describe('DatasetDetailsDialog (LA-DATA-DETAILS-1)', () => {
    afterEach(() => http?.verify());

    it('starts from the id when no name is stated, shows the fixed id, and has no violations', async () => {
        const { el, c } = create();
        expect(c.form.controls.name.value).toBe('transfers');
        expect(el.querySelector('[data-testid="dataset-details-id"]')!.textContent!.trim()).toBe('transfers');
        expect(el.textContent).toContain('renaming changes only what is shown');
        await expectNoA11yViolations(el);
    });

    it('saves the name as title and the description over the stored Dataset, every other key kept, against its version', () => {
        const { c, close } = create({ id: 'transfers', name: 'Transfers', description: '' });
        c.form.setValue({ name: '  Card transfers ', description: 'One row per transfer.' });
        c.save();
        http.expectOne(apiUrl('/components/dataset/transfers')).flush(STORED);
        const put = http.expectOne((r) => r.method === 'PUT' && r.url === apiUrl('/components/dataset/transfers'));
        expect(put.request.body).toEqual({
            ...STORED.content,
            title: 'Card transfers',
            description: 'One row per transfer.',
        });
        expect(put.request.headers.get('If-Match')).toBe('"sha256:abc123"');
        put.flush({ ...STORED });
        expect(close).toHaveBeenCalledWith({ name: 'Card transfers', description: 'One row per transfer.' });
    });

    it('refuses a blank name on screen and sends nothing', () => {
        const { fixture, el, c, close } = create();
        c.form.controls.name.setValue('   ');
        c.save();
        fixture.componentRef.changeDetectorRef.markForCheck();
        fixture.detectChanges();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('Give the Dataset a name.');
        expect(close).not.toHaveBeenCalled();
    });

    it('a change made meanwhile is refused and explained, not overwritten', () => {
        const { fixture, el, c, close } = create();
        c.save();
        http.expectOne(apiUrl('/components/dataset/transfers')).flush(STORED);
        http.expectOne((r) => r.method === 'PUT').flush(
            { error: { errorCode: 'CONFLICT_STALE_VERSION', message: 'stale' } },
            { status: 409, statusText: 'Conflict' },
        );
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')?.textContent).toContain(STALE_WRITE_MESSAGE.slice(0, 30));
        expect(close).not.toHaveBeenCalled();
    });
});
