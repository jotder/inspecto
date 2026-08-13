import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConnectionsService } from 'app/inspecto/api';
import { COLLECTOR_ATTRIBUTES } from 'app/inspecto/component-model';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CollectorConfigComponent } from './collector-config.component';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

function create(
    initial: Record<string, unknown> = {},
    storedConnector = '',
    profiles: { id: string; connector: string }[] = [],
): ComponentFixture<CollectorConfigComponent> {
    TestBed.configureTestingModule({
        imports: [CollectorConfigComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ConnectionsService,
                useValue: { list: () => of(profiles), test: vi.fn(() => of({ reachable: true, detail: 'ok' })) },
            },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const fixture = TestBed.createComponent(CollectorConfigComponent);
    fixture.componentRef.setInput('specs', COLLECTOR_ATTRIBUTES);
    fixture.componentRef.setInput('initial', initial);
    fixture.componentRef.setInput('storedConnector', storedConnector);
    fixture.detectChanges();
    return fixture;
}

/** Switch mode and let the embedded schema form rebuild for the new spec list. */
function toConnectionMode(fixture: ComponentFixture<CollectorConfigComponent>): CollectorConfigComponent {
    fixture.componentInstance.setMode('connection');
    fixture.detectChanges();
    return fixture.componentInstance;
}

/**
 * The ONE collector-config surface (2026-08-04) — shared by Onboarding's Collection stage and the
 * Pipelines `acquisition` node dialog. It has NO write path, so everything here is about what it
 * hands a host: the mode, the flat field values, and the DERIVED connector.
 */
describe('CollectorConfigComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('starts in Local mode and does not ask for a Connection there', () => {
        const c = create().componentInstance;
        expect(c.mode()).toBe('local');
        expect(c.visibleSpecs().some((s) => s.key === 'connection')).toBe(false);
        expect(c.resolveConnector()).toBe('local');
    });

    it('opens in Connection mode when a Connection is stored', () => {
        const c = create({ connection: 'sftp_prod' }, 'sftp').componentInstance;
        expect(c.mode()).toBe('connection');
        expect(c.visibleSpecs().some((s) => s.key === 'connection')).toBe(true);
        expect(c.schemaForm.form.get('connection')?.value).toBe('sftp_prod');
    });

    it('opens in Connection mode for a non-local connector even with no Connection', () => {
        expect(create({}, 'sftp').componentInstance.mode()).toBe('connection');
    });

    it('derives the connector from the picked Connection profile', () => {
        const c = toConnectionMode(create({}, '', [{ id: 'blob_prod', connector: 'azure' }]));
        c.schemaForm.form.get('connection')?.setValue('blob_prod');
        expect(c.derivedConnector()).toBe('azure');
        expect(c.resolveConnector()).toBe('azure');
        expect(c.error()).toBeNull();
    });

    it('refuses a Connection id that is not a saved profile', () => {
        const c = toConnectionMode(create({}, '', [{ id: 'blob_prod', connector: 'azure' }]));
        c.schemaForm.form.get('connection')?.setValue('ghost');
        expect(c.resolveConnector()).toBeNull();
        expect(c.error()).toContain('"ghost" is not a saved Connection');
    });

    it('refuses Connection mode with nothing picked and nothing stored', () => {
        const c = toConnectionMode(create());
        expect(c.resolveConnector()).toBeNull();
        expect(c.error()).toContain('Pick a Connection');
    });

    it('grandfathers a hand-authored non-local connector rather than destroying it', () => {
        const c = create({}, 'sftp').componentInstance;
        expect(c.resolveConnector()).toBe('sftp');
        expect(c.error()).toBeNull();
    });

    /**
     * Regression (2026-08-04, found extracting this component): the mode toggle swaps the spec list,
     * and reassigning `specs` rebuilds every control from its DECLARED DEFAULT. Without carrying the
     * live values across the swap, flipping the toggle silently discarded everything typed so far.
     */
    it('keeps the typed field values across a mode switch', () => {
        const fixture = create();
        fixture.componentInstance.schemaForm.form.get('include')?.setValue('*.csv');
        fixture.componentInstance.schemaForm.form.get('duplicate__mode')?.setValue('checksum');
        const c = toConnectionMode(fixture);
        expect(c.value()['include']).toBe('*.csv');
        expect(c.value()['duplicate__mode']).toBe('checksum');
    });

    it('is dirty on a mode switch alone, and pristine again after the host saves', () => {
        const fixture = create();
        expect(fixture.componentInstance.isDirty()).toBe(false);
        const c = toConnectionMode(fixture);
        expect(c.isDirty()).toBe(true);
        c.markPristine();
        expect(c.isDirty()).toBe(false);
    });

    it('has no a11y violations', async () => {
        const fixture = create({ connection: 'sftp_prod' }, 'sftp', [{ id: 'sftp_prod', connector: 'sftp' }]);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
