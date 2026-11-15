import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it } from 'vitest';
import { apiUrl, LensService } from '@inspecto/core/api';
import { SessionService } from '@inspecto/core/auth/session.service';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import { LaLimitsService } from '@inspecto/link-analysis/link-analysis/la-limits.service';
import { SELF_IMPORT_OFF } from '@inspecto/link-analysis/add-data/add-data.model';
import { AddDataAccess } from '@inspecto/link-analysis/add-data/add-data-access';
import { AddDataPageComponent } from './add-data.page';
import { FirstRun } from './first-run';

let http: HttpTestingController;

/**
 * `who`: nobody signs in by default (the person is their own administrator); `signIn` puts sign-in on with that
 * person's rights. `notice`: what Create a Space left for this page, if anything. `knownOff`: the switch was already
 * read as off earlier in this visit.
 */
function create(
    selfImport = true,
    who: { signIn?: { canAdminister: boolean }; notice?: string; knownOff?: boolean } = {},
) {
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
            {
                provide: LensService,
                useValue: {
                    canUploadData: signal(true),
                    canAuthorWorkbench: signal(true),
                    canAdminister: signal(who.signIn?.canAdminister ?? false),
                },
            },
            { provide: FirstRun, useValue: { takeNotice: () => who.notice ?? null } },
            { provide: LaLimitsService, useValue: { items: signal([]) } },
            { provide: EntityProfileService, useValue: { entityTypes: () => of({ items: [] }) } },
        ],
    });
    if (who.signIn) TestBed.inject(SessionService).authMode.set('oidc');
    if (who.knownOff) TestBed.inject(AddDataAccess).selfImport.set(false);
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
    afterEach(() => {
        // LA-ARRIVAL-LEDGER-1: each Dataset card reads its arrivals; these tests do not look at them
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
        // a Reference's card reads what its rows cover (the accounts Dataset here)
        http
            ?.match((r) => r.url.endsWith('/coverage'))
            .forEach((r) => !r.cancelled && r.flush({}, { status: 404, statusText: 'Not Found' }));
        http?.verify();
    });

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

    it('with Add data on, nothing offers to turn it on', () => {
        const { el } = create(true);
        expect(el.querySelector('[data-testid="la-add-data-off"]')).toBeNull();
        expect(el.querySelector('[data-testid="la-turn-on-add-data"]')).toBeNull();
    });

    describe('Add data is turned off in this Space (LA-FULL-CYCLE-TEST-1 D2)', () => {
        const button = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('[data-testid="la-turn-on-add-data"]');
        const expectPut = () => {
            const put = http.expectOne((r) => r.method === 'PUT' && r.url === apiUrl('/settings/data'));
            expect(put.request.body).toEqual({ selfImport: true });
            return put;
        };

        it('someone who may not administer sees who can turn it on, and no button; no card offers a refused run (no violations)', async () => {
            const { el } = create(false, { signIn: { canAdminister: false } });
            expect(el.querySelector('[data-testid="add-data-locked"]')!.textContent).toContain(SELF_IMPORT_OFF);
            expect(SELF_IMPORT_OFF).toBe('Add data is turned off in this Space; an administrator can turn it on');
            expect(button(el)).toBeNull();
            expect(el.querySelector('[data-testid="la-add-data-off"]')).toBeNull();
            expect(el.querySelector('[data-testid="imported-datasets"]')).toBeNull();
            await expectNoA11yViolations(el);
        });

        it('an administrator gets one panel with a primary Turn on Add data button in place of the locked reason (no violations)', async () => {
            const { el } = create(false, { signIn: { canAdminister: true } });
            const panel = el.querySelector('[data-testid="la-add-data-off"]')!;
            expect(panel.querySelector('h2')!.textContent!.trim()).toBe('Add data is turned off in this Space');
            expect(button(el)!.textContent!.trim()).toBe('Turn on Add data');
            expect(button(el)!.getAttribute('color')).toBe('primary');
            expect(button(el)!.hasAttribute('mat-flat-button')).toBe(true);
            // the way back, in the menu path's words; no config key or capability id is shown
            expect(panel.querySelector('a')!.getAttribute('href')).toBe('/settings/add-data');
            expect(panel.textContent).not.toMatch(/self_import|selfImport|canAdminister|canUploadData/);
            // one message, not two: the locked reason and the wizard are not drawn beside the button
            expect(el.querySelector('inspecto-add-data')).toBeNull();
            expect(el.querySelector('[data-testid="imported-datasets"]')).toBeNull();
            await expectNoA11yViolations(el);
        });

        it('with no sign-in the person is their own administrator and gets the button too', () => {
            const { el } = create(false);
            expect(button(el)).not.toBeNull();
        });

        it('Turn on Add data asks the server, then opens the Add data screen in place and says so', async () => {
            const { fixture, el } = create(false, { signIn: { canAdminister: true } });
            button(el)!.click();
            fixture.detectChanges();
            expect(button(el)!.disabled).toBe(true); // one request at a time
            expectPut().flush({ selfImport: true, selfImportDefault: false, selfImportInForce: true });
            fixture.detectChanges();
            // the Add data screen is drawn (and reads the switch itself, as it does on every visit)
            http.expectOne(apiUrl('/settings/data')).flush({
                selfImport: true,
                selfImportDefault: false,
                selfImportInForce: true,
            });
            fixture.detectChanges();
            expect(el.querySelector('[data-testid="la-add-data-off"]')).toBeNull();
            expect(el.querySelector('inspecto-add-data')).not.toBeNull();
            expect(el.querySelector('[data-testid="add-data-locked"]')).toBeNull();
            expect(el.querySelector('[data-testid="step-file"]')).not.toBeNull();
            expect(el.querySelector('[data-testid="imported-datasets"]')).not.toBeNull();
            const said = el.querySelector('[data-testid="la-add-data-on"]')!;
            expect(said.textContent!.trim()).toBe('Add data is now on in this Space.');
            expect(said.querySelector('[role="status"]')).not.toBeNull();
            // the button that held focus is gone: focus is on the page title, not lost to the body
            expect(document.activeElement).toBe(el.querySelector('h1'));
            await expectNoA11yViolations(el);
        });

        it('a refusal is shown under the button in the server’s words, and the button can be pressed again (no violations)', async () => {
            const { fixture, el } = create(false, { signIn: { canAdminister: true } });
            button(el)!.click();
            expectPut().flush(
                { error: { message: 'An Access Policy does not allow this change.' } },
                { status: 403, statusText: 'Forbidden' },
            );
            fixture.detectChanges();
            const refused = el.querySelector('[data-testid="la-turn-on-refused"]')!;
            expect(refused.textContent).toContain('Add data was not turned on');
            expect(refused.textContent).toContain('An Access Policy does not allow this change.');
            expect(refused.querySelector('[role="alert"]')).not.toBeNull();
            expect(button(el)!.disabled).toBe(false);
            expect(el.querySelector('inspecto-add-data')).toBeNull(); // still off: nothing pretends otherwise
            await expectNoA11yViolations(el);
            // pressing again asks again, and the old refusal is cleared while it is asked
            button(el)!.click();
            fixture.detectChanges();
            expect(el.querySelector('[data-testid="la-turn-on-refused"]')).toBeNull();
            expectPut().flush({ selfImport: true, selfImportDefault: false, selfImportInForce: true });
            fixture.detectChanges();
            http.expectOne(apiUrl('/settings/data')).flush({
                selfImport: true,
                selfImportDefault: false,
                selfImportInForce: true,
            });
        });

        it('opening on the button reads the switch again: one turned on since in Settings is not shown as off', () => {
            const { fixture, el } = create(true, { signIn: { canAdminister: true }, knownOff: true });
            // the page's own read answered "on": the Add data screen is drawn, and reads the switch as it always does
            http.expectOne(apiUrl('/settings/data')).flush({
                selfImport: true,
                selfImportDefault: false,
                selfImportInForce: true,
            });
            fixture.detectChanges();
            expect(button(el)).toBeNull();
            expect(el.querySelector('inspecto-add-data')).not.toBeNull();
        });

        it('names the Create a Space step that was refused, and still offers the button', () => {
            const notice =
                'The Space was created, but Add data was not turned on: the server did not accept the change.';
            const { el } = create(false, { signIn: { canAdminister: true }, notice });
            const said = el.querySelector('[data-testid="la-first-run-notice"]')!;
            expect(said.textContent!.trim()).toBe(notice);
            expect(said.querySelector('[role="alert"]')).not.toBeNull();
            expect(button(el)).not.toBeNull();
        });
    });
});
