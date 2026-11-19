import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, Provider, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { apiUrl, LensService } from '@inspecto/core/api';
import { ADD_DATA_LINK_ANALYSIS } from '@inspecto/core/add-data/add-data-link-analysis';
import type { Arrival, ImportDataset } from '@inspecto/core/add-data/add-data.model';
import { ImportDatasetCardComponent } from '@inspecto/core/add-data/import-dataset-card.component';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { provideAddDataLinkAnalysis } from './add-data-link-analysis.providers';

/** A page placing the core Dataset card with what Link Analysis hands it (as the four host pages do). */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [ImportDatasetCardComponent],
    providers: [provideAddDataLinkAnalysis()],
    template: `<inspecto-import-dataset-card [dataset]="dataset()"></inspecto-import-dataset-card>`,
})
class HostComponent {
    readonly dataset = signal<ImportDataset>({ id: 'transfers', kind: 'stream', importPipeline: 'transfers' });
}

let http: HttpTestingController;

function configure(extra: Provider[] = []) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            ...extra,
            provideNoopAnimations(),
            provideHttpClient(),
            provideHttpClientTesting(),
            provideRouter([]),
            // the Arrivals table's grid theme reads the scheme
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'light' }), config: { scheme: 'light' } } },
            {
                provide: LensService,
                useValue: { canUploadData: signal(true), canAuthorWorkbench: signal(true), canBuildLinkIndex: signal(true) },
            },
        ],
    });
    http = TestBed.inject(HttpTestingController);
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

describe('provideAddDataLinkAnalysis - what Link Analysis hands the core Add data (BASIC-ELT-PROFILE-1 UI-0)', () => {
    afterEach(() => {
        http?.match((r) => r.url.endsWith('/arrivals')).forEach((r) => !r.cancelled && r.flush({ arrivals: [] }));
        http
            ?.match((r) => r.url.endsWith('/feed'))
            .forEach((r) => !r.cancelled && r.flush({}, { status: 404, statusText: 'Not Found' }));
        http?.match((r) => r.url.endsWith('/connections')).forEach((r) => !r.cancelled && r.flush([]));
        http
            ?.match((r) => r.url.endsWith('/storage'))
            .forEach((r) => !r.cancelled && r.flush({}, { status: 404, statusText: 'Not Found' }));
        http?.verify();
    });

    it('LA-FULL-CYCLE-TEST-1 phase 4: Graph is the link index live answer, out of date beside Rebuild, over what the import recorded (no violations)', async () => {
        configure();
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const built: Arrival = {
            file: 'transfers.csv',
            sender: 'upload',
            received: '2026-11-07 09:17:00',
            status: 'SUCCESS',
            rowsRead: 461,
            rowsRejected: 0,
            mode: 'append',
            sha256: null,
            index: 'BUILT',
            consignment: 'b1',
            kept: true,
            coversFrom: null,
            coversTo: null,
        };
        http.expectOne(apiUrl('/datasets/transfers/arrivals')).flush({
            dataset: 'transfers',
            eventDateColumn: null,
            arrivals: [built],
            truncated: false,
            reason: null,
        });
        fixture.detectChanges();
        expect(q(el, 'fact-graph')!.textContent!.trim()).toBe('up to date'); // the import's record, while nothing else is known
        expect(q(el, 'index-rebuild-button')).toBeNull();

        http.expectOne(apiUrl('/inv/index')).flush({
            enabled: true,
            indexes: [
                {
                    dataset: 'transfers',
                    mapping: { sourceCol: 'from_account', targetCol: 'to_account', kindCol: null },
                    version: 1,
                    stale: true,
                    reason: "the Dataset's definition changed after the index was built",
                    fingerprint: 'known',
                    plan: { recommended: 'full', appendable: false, reasons: [], added: 0, removed: 0, changed: 0 },
                },
            ],
        });
        fixture.detectChanges();
        expect(q(el, 'fact-graph')!.textContent!.trim()).toBe('out of date');
        expect(q(el, 'index-rebuild-button')!.textContent!.trim()).toBe('Rebuild');
        await expectNoA11yViolations(el);
    });

    it('starts no Link Analysis read until a part is asked for: the limits are read only once Add data asks', () => {
        configure(provideAddDataLinkAnalysis());
        const seam = TestBed.inject(ADD_DATA_LINK_ANALYSIS);
        expect(seam.rebuild).not.toBeNull();
        expect(http.match(() => true)).toEqual([]);
        expect(seam.limits()()).toEqual([]);
        TestBed.tick(); // the limits service reads its Space in an effect
        http.expectOne((r) => r.url.endsWith('/settings/link-analysis/limits')).flush({ items: [] });
    });
});
