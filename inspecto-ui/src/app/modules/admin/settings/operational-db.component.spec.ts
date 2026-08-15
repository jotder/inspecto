import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { apiUrl } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OperationalDbComponent } from './operational-db.component';

/**
 * PG-1 Open 2 Stage 1's pane. ⚠ The behaviour worth pinning is what this screen REFUSES to do: it has
 * no Save (persisting would create a second declaration of the database beside `-D`), and it will not
 * send a literal password.
 */
describe('OperationalDbComponent', () => {
    let fixture: ComponentFixture<OperationalDbComponent>;
    let http: HttpTestingController;

    const REPORT = {
        engine: 'postgres',
        engineProperty: 'inspecto.db',
        driverAvailable: false,
        families: [
            {
                family: 'JOB_RUNS',
                label: 'Job runs',
                enabled: true,
                source: 'FAMILY_PROPERTY',
                url: 'jdbc:postgresql://jobs:5432/j',
                user: 'svc',
                backendProperty: 'jobs.backend',
                urlProperty: 'jobs.db.url',
                userProperty: null,
                passwordProperty: null,
            },
            {
                family: 'PROVENANCE',
                label: 'Provenance',
                enabled: false,
                source: 'DISABLED',
                url: null,
                user: null,
                backendProperty: 'provenance.backend',
                urlProperty: 'provenance.db.url',
                userProperty: null,
                passwordProperty: null,
            },
        ],
    };

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [OperationalDbComponent],
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                { provide: ToastrService, useValue: { error: vi.fn(), success: vi.fn() } },
            ],
        }).compileComponents();
        http = TestBed.inject(HttpTestingController);
        fixture = TestBed.createComponent(OperationalDbComponent);
        fixture.detectChanges();
        http.expectOne(apiUrl('/system/operational-db')).flush(REPORT);
        fixture.detectChanges();
    });

    it('reports every family with the property that governs it', () => {
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Job runs');
        expect(text).toContain('jdbc:postgresql://jobs:5432/j');
        expect(text).toContain('jobs.db.url');
        // A disabled family points at the BACKEND property — that is the flag that would turn it on.
        expect(text).toContain('provenance.backend');
    });

    /** A postgres deployment with no driver is the sidecar mistake — say so, don't just show a URL. */
    it('warns when postgres is selected without the driver', () => {
        expect(fixture.nativeElement.textContent).toContain('PostgreSQL driver missing');
    });

    /** ⛔ The screen has no write path at all. A Save button here would be the split-brain. */
    it('offers no save affordance', () => {
        const labels = Array.from(fixture.nativeElement.querySelectorAll('button')).map((b) =>
            (b as HTMLButtonElement).textContent?.trim().toLowerCase(),
        );
        expect(labels.some((l) => l?.includes('save'))).toBe(false);
    });

    /** ⛔ A literal password is never sent — the guard is inline as well as server-side. */
    it('refuses to send a literal password, and sends a reference', () => {
        const c = fixture.componentInstance;
        c.form.setValue({ url: 'jdbc:postgresql://h:5432/d', user: 'u', password: 'hunter2' });
        c.test();
        http.expectNone(apiUrl('/system/operational-db/test'));
        expect(c.form.controls.password.hasError('literal')).toBe(true);

        c.form.controls.password.setValue('${ENV:PGPASSWORD}');
        c.test();
        const req = http.expectOne(apiUrl('/system/operational-db/test'));
        expect(req.request.body.password).toBe('${ENV:PGPASSWORD}');
        req.flush({ url: 'jdbc:postgresql://h:5432/d', outcome: 'OK', detail: 'connected' });
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('Connected');
    });

    it('has no accessibility violations', async () => {
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
