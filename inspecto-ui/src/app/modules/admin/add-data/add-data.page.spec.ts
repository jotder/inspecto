import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { apiUrl, LensService } from '@inspecto/core/api';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import { LaLimitsService } from '@inspecto/link-analysis/link-analysis/la-limits.service';
import { REMOTE_FALLBACK, SELF_IMPORT_OFF } from '@inspecto/link-analysis/add-data/add-data.model';
import { ConsoleAddDataPageComponent } from './add-data.page';

let http: HttpTestingController;

interface Opts {
    edition?: 'basic' | 'standard';
    selfImport?: boolean;
    canOnboard?: boolean;
}

function create(o: Opts = {}) {
    TestBed.configureTestingModule({
        imports: [ConsoleAddDataPageComponent],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(),
            provideHttpClientTesting(),
            provideRouter([]),
            {
                provide: LA_FEATURES,
                useValue: {
                    ops: signal(false),
                    exchange: signal(false),
                    geoLink: signal(true),
                    investigations: signal(false),
                    signIn: signal(false),
                    edition: signal(o.edition ?? 'basic'),
                    flags: signal({}),
                    featureReasons: signal({}),
                },
            },
            {
                provide: LensService,
                useValue: {
                    canUploadData: signal(true),
                    canAuthorWorkbench: signal(true),
                    canOnboardConnections: signal(o.canOnboard ?? true),
                },
            },
            { provide: LaLimitsService, useValue: { items: signal([]) } },
            { provide: EntityProfileService, useValue: { entityTypes: () => of({ items: [] }) } },
        ],
    });
    const fixture = TestBed.createComponent(ConsoleAddDataPageComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne(apiUrl('/components/dataset')).flush([
        { type: 'dataset', name: 'orders', ref: 'dataset/orders', content: { origin: 'import', kind: 'stream' } },
        { type: 'dataset', name: 'calls', ref: 'dataset/calls', content: { physicalRef: 'calls/database' } },
    ]);
    http.expectOne(apiUrl('/settings/data')).flush({
        selfImport: null,
        selfImportDefault: true,
        selfImportInForce: o.selfImport ?? true,
    });
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

describe('ConsoleAddDataPageComponent (/add-data, ONBOARDING-FIRST-RUN-1 C3)', () => {
    afterEach(() => {
        // the Dataset card reads its arrivals (LA-ARRIVAL-LEDGER-1), its inbox and feed (LA-ARRIVAL-FEEDS-1); not looked at here
        http?.match((r) => r.url.endsWith('/arrivals')).forEach((r) => !r.cancelled && r.flush({ arrivals: [] }));
        // LA-ARRIVAL-FEEDS-1: and its inbox and feed (and, where Keep this up to date is offered, the Connections)
        http
            ?.match((r) => r.url.endsWith('/feed'))
            .forEach((r) => !r.cancelled && r.flush({}, { status: 404, statusText: 'Not Found' }));
        http?.match((r) => r.url.endsWith('/connections')).forEach((r) => !r.cancelled && r.flush([]));
        // LA-DATASET-COMPACT-1: and its storage
        http
            ?.match((r) => r.url.endsWith('/storage'))
            .forEach((r) => !r.cancelled && r.flush({}, { status: 404, statusText: 'Not Found' }));
        http?.verify();
    });

    it('Basic: hosts the shared Add data screen, files only (a remote system is locked with its reason) (no violations)', async () => {
        const { el } = create({ edition: 'basic' });
        expect(el.querySelector('h1')!.textContent).toBe('Onboard a file');
        expect(el.querySelector('inspecto-add-data')).not.toBeNull();
        expect(el.textContent).not.toMatch(/wizard/i);
        expect(el.querySelector('inspecto-locked-action')!.textContent).toContain(REMOTE_FALLBACK);
        await expectNoA11yViolations(el);
    });

    it('Standard: the remote system is not locked', () => {
        const { el } = create({ edition: 'standard' });
        expect(el.querySelector('inspecto-locked-action')).toBeNull();
    });

    it('Standard: lists only imported Datasets, each with Open in editor onto the guided Pipeline editor (the expert path)', () => {
        const { el } = create({ edition: 'standard' });
        const list = el.querySelector('[data-testid="imported-datasets"]')!;
        expect(Array.from(list.querySelectorAll('h3')).map((h) => h.textContent)).toEqual(['orders']);
        const editor = list.querySelector('[data-testid="open-in-editor"]')!;
        expect(editor.getAttribute('href')).toBe('/pipelines?guided=1&open=orders');
    });

    it('Basic: the card keeps the manual run; Keep this up to date is locked with its reason instead of the editor', () => {
        const list = create({ edition: 'basic' }).el.querySelector('[data-testid="imported-datasets"]')!;
        expect(list.querySelector('[data-testid="open-in-editor"]')).toBeNull();
        expect(list.querySelector('[data-testid="keep-up-to-date"] inspecto-locked-action')).not.toBeNull();
    });

    it('keeps the Catalog dialog as the advanced start, for those who may onboard connections (OF-3)', () => {
        expect(create().el.querySelector('[data-testid="advanced-start"] a')!.getAttribute('href')).toBe(
            '/catalog?onboard=stream',
        );
        TestBed.resetTestingModule();
        expect(create({ canOnboard: false }).el.querySelector('[data-testid="advanced-start"]')).toBeNull();
    });

    it('when Add data is off here, the screen is the reason and no card is offered', () => {
        const { el } = create({ selfImport: false });
        expect(el.querySelector('[data-testid="add-data-locked"]')!.textContent).toContain(SELF_IMPORT_OFF);
        expect(el.querySelector('[data-testid="imported-datasets"]')).toBeNull();
    });
});
