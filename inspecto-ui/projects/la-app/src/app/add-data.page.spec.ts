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
import { SELF_IMPORT_OFF } from '@inspecto/link-analysis/add-data/add-data.model';
import { AddDataPageComponent } from './add-data.page';

let http: HttpTestingController;

function create(selfImport = true) {
    TestBed.configureTestingModule({
        imports: [AddDataPageComponent],
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
                    edition: signal('basic'),
                    flags: signal({}),
                    featureReasons: signal({}),
                },
            },
            { provide: LensService, useValue: { canUploadData: signal(true), canAuthorWorkbench: signal(true) } },
            { provide: LaLimitsService, useValue: { items: signal([]) } },
            { provide: EntityProfileService, useValue: { entityTypes: () => of({ items: [] }) } },
        ],
    });
    const fixture = TestBed.createComponent(AddDataPageComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    http.expectOne(apiUrl('/components/dataset')).flush([
        {
            type: 'dataset',
            name: 'transfers',
            ref: 'dataset/transfers',
            content: { origin: 'import', kind: 'stream', description: 'Imported from transfers.csv with Add data' },
        },
        {
            type: 'dataset',
            name: 'accounts',
            ref: 'dataset/accounts',
            content: { origin: 'import', kind: 'reference' },
        },
        { type: 'dataset', name: 'calls', ref: 'dataset/calls', content: { physicalRef: 'calls/database' } },
    ]);
    http.expectOne(apiUrl('/settings/data')).flush({
        selfImport: null,
        selfImportDefault: true,
        selfImportInForce: selfImport,
    });
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

describe('AddDataPageComponent (/add-data, LA-ONBOARD-1 phase 4)', () => {
    afterEach(() => http?.verify());

    it('titles the screen "Onboard a file", hosts Add data and lists the Datasets imported here (no violations)', async () => {
        const { el } = create();
        expect(el.querySelector('h1')!.textContent).toBe('Onboard a file');
        expect(el.querySelector('inspecto-add-data')).not.toBeNull();
        expect(el.textContent).not.toMatch(/wizard/i);
        const list = el.querySelector('[data-testid="imported-datasets"]')!;
        const titles = Array.from(list.querySelectorAll('h3')).map((h) => h.textContent);
        expect(titles).toEqual(['transfers', 'accounts']); // a Pipeline-built Dataset is not an import
        await expectNoA11yViolations(el);
    });

    it('when Add data is off here, the screen is the reason and no card offers a run that would be refused (no violations)', async () => {
        const { el } = create(false);
        expect(el.querySelector('[data-testid="add-data-locked"]')!.textContent).toContain(SELF_IMPORT_OFF);
        expect(el.querySelector('[data-testid="imported-datasets"]')).toBeNull();
        await expectNoA11yViolations(el);
    });
});
