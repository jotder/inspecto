import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { apiUrl } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { Arrival, Arrivals } from './add-data.model';
import { DataStatusComponent } from './data-status.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [DataStatusComponent],
    template: `<inspecto-la-data-status [dataset]="dataset()" [arrivals]="given()"></inspecto-la-data-status>`,
})
class HostComponent {
    readonly dataset = signal<string | null>('calls');
    readonly given = signal<Arrivals | null | undefined>(undefined);
}

const arrival: Arrival = {
    file: 'calls.csv',
    sender: 'upload',
    received: '2026-11-02 09:14:00',
    status: 'SUCCESS',
    rowsRead: 10,
    rowsRejected: 0,
    mode: 'append',
    sha256: null,
    index: 'BUILT',
    indexMode: 'append',
    consignment: 'b1',
    kept: true,
    coversFrom: '2026-10-01',
    coversTo: '2026-11-02',
};

function read(id: string, arrivals: Arrival[]): Arrivals {
    return { dataset: id, eventDateColumn: 'at', arrivals, truncated: false, reason: null };
}

function create() {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(HostComponent);
    const http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
}

const status = (el: HTMLElement) =>
    el.querySelector('[data-testid="data-status"]')?.textContent?.replace(/\s+/g, ' ').trim();

describe('DataStatusComponent', () => {
    it('reads the arrivals of its Dataset and says the three clocks; a new Dataset is read again', async () => {
        const { fixture, http, el } = create();
        http.expectOne(apiUrl('/datasets/calls/arrivals')).flush(read('calls', [arrival]));
        fixture.detectChanges();
        expect(status(el)).toBe(
            'Data status: Received 2026-11-02 09:14 · covers 2026-10-01 – 2026-11-02 · graph up to date',
        );
        await expectNoA11yViolations(el);

        fixture.componentInstance.dataset.set('sms');
        fixture.detectChanges();
        expect(status(el)).toBeUndefined(); // the old Dataset's status is never shown under the new name
        http.expectOne(apiUrl('/datasets/sms/arrivals')).flush(
            read('sms', [{ ...arrival, index: null, coversFrom: null }]),
        );
        fixture.detectChanges();
        expect(status(el)).toBe('Data status: Received 2026-11-02 09:14');
        http.verify();
    });

    it('shows nothing for a Dataset no import made, for a failed read, or with no Dataset', () => {
        const { fixture, http, el } = create();
        http.expectOne(apiUrl('/datasets/calls/arrivals')).flush({
            ...read('calls', []),
            reason: 'not made with Add data',
        });
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="data-status"]')).toBeNull();

        fixture.componentInstance.dataset.set('broken');
        fixture.detectChanges();
        http.expectOne(apiUrl('/datasets/broken/arrivals')).flush('no', { status: 503, statusText: 'Unavailable' });
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="data-status"]')).toBeNull();

        fixture.componentInstance.dataset.set(null);
        fixture.detectChanges();
        http.verify(); // no Dataset, no read
    });

    it('uses the arrivals the host passes and reads nothing itself', () => {
        const { fixture, http, el } = create();
        http.expectOne(apiUrl('/datasets/calls/arrivals')); // the first render had none passed
        fixture.componentInstance.given.set(
            read('calls', [{ ...arrival, sender: 'system', index: 'FAILED' }, arrival]),
        );
        fixture.detectChanges();
        expect(status(el)).toContain('graph 1 file behind');
        http.verify();
    });
});
