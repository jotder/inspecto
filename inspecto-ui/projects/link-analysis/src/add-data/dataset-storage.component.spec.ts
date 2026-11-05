import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, describe, expect, it } from 'vitest';
import { apiUrl, LensService } from '@inspecto/core/api';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { DatasetStorageComponent } from './dataset-storage.component';
import { DatasetStorageState, retentionWords, storageStatusLine, storageWords } from './dataset-storage.model';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [DatasetStorageComponent],
    template: `<inspecto-dataset-storage datasetId="calls"></inspecto-dataset-storage>`,
})
class HostComponent {
    readonly storage = viewChild(DatasetStorageComponent);
}

const STATE: DatasetStorageState = {
    dataset: 'calls',
    files: 3,
    bytes: 1536,
    threshold: 20,
    compactedAt: null,
    compactedArrivals: 0,
    removedArrivals: 0,
    retention: null,
    retentionAvailable: false,
};

let http: HttpTestingController;

function create(o: { canAuthor?: boolean } = {}) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(),
            provideHttpClientTesting(),
            {
                provide: LA_FEATURES,
                useValue: {
                    signIn: signal(true),
                    edition: signal('standard'),
                    flags: signal({}),
                    featureReasons: signal({}),
                },
            },
            {
                provide: LensService,
                useValue: { canUploadData: signal(true), canAuthorWorkbench: signal(o.canAuthor ?? true) },
            },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

describe('DatasetStorageComponent - files, Compact now and retention (LA-DATASET-COMPACT-1)', () => {
    afterEach(() => http?.verify());

    it('Basic: the files and Compact now; no retention to set (no violations)', async () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/storage')).flush(STATE);
        fixture.detectChanges();
        expect(q(el, 'storage-line')!.textContent).toBe('3 files · 1.5 KB');
        expect(q(el, 'retention-form')).toBeNull();
        await expectNoA11yViolations(el);

        q(el, 'compact-now')!.click();
        const post = http.expectOne(apiUrl('/datasets/calls/compact'));
        expect(post.request.method).toBe('POST');
        post.flush({
            ...STATE,
            files: 1,
            compactedAt: '2026-11-05T10:02:11', // zoneless: the line must not depend on the runner's zone
            compactedArrivals: 3,
            compaction: {
                result: 'COMPACTED',
                filesBefore: 3,
                filesAfter: 1,
                rowsRemoved: 0,
                message: '3 files merged into 1',
            },
        });
        fixture.detectChanges();
        expect(q(el, 'storage-line')!.textContent).toBe('1 file · 1.5 KB · compacted 2026-11-05 10:02 (3 arrivals)');
        expect(q(el, 'storage-note')!.textContent).toContain('Compacted: 3 files merged into 1.');
    });

    it('Standard, may author: the policy is shown as stored and saved as keep N days (no violations)', async () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/storage')).flush({
            ...STATE,
            retentionAvailable: true,
            retention: { arrivals: 12 },
        });
        fixture.detectChanges();
        expect(el.textContent).toContain('This Dataset keeps the last 12 arrivals.');
        expect(q(el, 'retention-form')).not.toBeNull();
        expect(q(el, 'retention-count')).not.toBeNull();
        await expectNoA11yViolations(el);

        const cmp = fixture.componentInstance.storage() as unknown as {
            kind: { setValue(v: string): void };
            amount: { setValue(v: number | null): void };
        };
        cmp.kind.setValue('days');
        cmp.amount.setValue(0);
        fixture.detectChanges();
        q(el, 'save-retention')!.click();
        fixture.detectChanges();
        http.expectNone(apiUrl('/datasets/calls/storage'));
        expect(el.textContent).toContain('A whole number, 1 or more.');

        cmp.amount.setValue(90);
        q(el, 'save-retention')!.click();
        const put = http.expectOne(apiUrl('/datasets/calls/storage'));
        expect(put.request.method).toBe('PUT');
        expect(put.request.body).toEqual({ retention: { days: 90 } });
        put.flush({ ...STATE, retentionAvailable: true, retention: { days: 90 } });
        fixture.detectChanges();
        expect(q(el, 'storage-note')!.textContent).toContain(
            'now keeps the last 90 days; it applies at the next compaction',
        );
    });

    it('Keep every arrival saves null; without the right to author there is no form', () => {
        const { fixture, el } = create({ canAuthor: false });
        http.expectOne(apiUrl('/datasets/calls/storage')).flush({
            ...STATE,
            retentionAvailable: true,
            retention: { days: 7 },
        });
        fixture.detectChanges();
        expect(q(el, 'retention-form')).toBeNull();
        expect(el.textContent).toContain('This Dataset keeps the last 7 days.');
    });

    it('a refused Compact now is said in place', () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/storage')).flush(STATE);
        fixture.detectChanges();
        q(el, 'compact-now')!.click();
        http.expectOne(apiUrl('/datasets/calls/compact')).flush(
            { error: { message: "'calls' is being imported right now" } },
            { status: 409, statusText: 'Conflict' },
        );
        fixture.detectChanges();
        expect(q(el, 'storage-note')!.textContent).toContain('is being imported right now');
    });
});

describe('dataset-storage wording', () => {
    it('says the files, the last compaction and what the policy keeps', () => {
        expect(storageWords({ ...STATE, files: 1, bytes: 0 })).toBe('1 file · 0 bytes');
        expect(retentionWords(null)).toBe('keeps every arrival');
        expect(retentionWords({ days: 1 })).toBe('keeps the last 1 day');
        expect(retentionWords({ arrivals: 3 })).toBe('keeps the last 3 arrivals');
        expect(storageStatusLine(null)).toBeNull();
        expect(storageStatusLine(STATE)).toBe('Storage: 3 files · 1.5 KB');
        expect(storageStatusLine({ ...STATE, retentionAvailable: true, retention: { days: 30 } })).toBe(
            'Storage: 3 files · 1.5 KB · keeps the last 30 days',
        );
    });
});
