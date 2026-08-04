import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ConnectionsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingCollectionPaneComponent } from './collection-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const WRITE_OK = { type: 'pipeline', written: true, path: 'x.toon', name: 'x', bytes: 1, overwritten: true, findings: [] };

function create(
    config: Record<string, unknown>,
    // Stage saves go through POST /config/patch — the mock's third arg is the block patch itself.
    patch = vi.fn((_type: string, _name: string, _patch: Record<string, unknown>) => of(WRITE_OK)),
    profiles: { id: string; connector: string }[] = [],
) {
    TestBed.configureTestingModule({
        imports: [OnboardingCollectionPaneComponent],
        providers: [
            provideNoopAnimations(),
            OnboardingStateService,
            { provide: ConfigService, useValue: { patch } },
            { provide: ConnectionsService, useValue: { list: () => of(profiles), test: vi.fn(() => of({ reachable: true, detail: 'ok' })) } },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    const fixture = TestBed.createComponent(OnboardingCollectionPaneComponent);
    fixture.detectChanges();
    return { fixture, state, patch };
}

/**
 * The pane is a thin host over the shared `<inspecto-collector-config>` (2026-08-04): the collector
 * chrome (mode, fields, Connection affordances, derived connector) is asserted through
 * `componentInstance.collector`, while what stays the PANE's job — nesting, delete markers and the
 * `POST /config/patch` block write — is asserted on the `patch` spy.
 */
describe('OnboardingCollectionPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('initialises the shared component from the existing collector block (Connection mode for a non-local connector)', () => {
        const { fixture } = create({ name: 'x', collector: { connector: 'sftp', duplicate: { mode: 'checksum' } } });
        const c = fixture.componentInstance;
        expect(c.collector.mode()).toBe('connection');
        expect(c.initial['duplicate__mode']).toBe('checksum');
        expect(c.collector.schemaForm.form.get('duplicate__mode')?.value).toBe('checksum');
    });

    it('local save injects connector "local" and nests dot keys, then marks the form pristine', () => {
        const { fixture, state, patch } = create({ name: 'x' });
        const c = fixture.componentInstance;
        expect(c.collector.mode()).toBe('local');
        c.collector.schemaForm.form.get('include')?.setValue('*.csv, *.txt');
        c.save();
        expect(patch).toHaveBeenCalledTimes(1);
        const collector = (patch.mock.calls[0][2] as Record<string, unknown>)["collector"] as Record<string, unknown>;
        expect(collector['connector']).toBe('local');
        expect(collector['include']).toEqual(['*.csv', '*.txt']);
        // The cleared key travels as an explicit null delete marker (nullifyDeletes — JSON would
        // silently drop `undefined`), so the server-side merge removes it from the stored block.
        expect(collector['connection']).toBeNull();
        expect(state.isDirty()).toBe(false);
    });

    it('derives the connector from the picked Connection — it is never asked', () => {
        const { fixture, patch } = create({ name: 'x' }, undefined, [{ id: 'blob_prod', connector: 'azure' }]);
        const c = fixture.componentInstance;
        c.collector.setMode('connection');
        fixture.detectChanges();
        c.collector.schemaForm.form.get('connection')?.setValue('blob_prod');
        expect(c.collector.derivedConnector()).toBe('azure');
        c.save();
        const collector = (patch.mock.calls[0][2] as Record<string, unknown>)["collector"] as Record<string, unknown>;
        expect(collector['connector']).toBe('azure');
        expect(collector['connection']).toBe('blob_prod');
    });

    it('switching to Local inbox deletes the stored connection and writes connector "local"', () => {
        const { fixture, patch } = create(
            { name: 'x', collector: { connector: 'sftp', connection: 'sftp_prod' } },
            undefined,
            [{ id: 'sftp_prod', connector: 'sftp' }],
        );
        const c = fixture.componentInstance;
        expect(c.collector.mode()).toBe('connection');
        c.collector.setMode('local');
        fixture.detectChanges();
        c.save();
        const collector = (patch.mock.calls[0][2] as Record<string, unknown>)["collector"] as Record<string, unknown>;
        expect(collector['connector']).toBe('local');
        expect(collector['connection']).toBeNull();   // explicit delete marker for the server merge
    });

    it('blocks a Connection-mode save with no Connection picked', () => {
        const { fixture, patch } = create({ name: 'x' });
        const c = fixture.componentInstance;
        c.collector.setMode('connection');
        fixture.detectChanges();
        c.save();
        expect(patch).not.toHaveBeenCalled();
        expect(c.collector.error()).toContain('Pick a Connection');
    });

    it('blocks when the typed Connection id is not a saved profile', () => {
        const { fixture, patch } = create({ name: 'x' }, undefined, [{ id: 'blob_prod', connector: 'azure' }]);
        const c = fixture.componentInstance;
        c.collector.setMode('connection');
        fixture.detectChanges();
        c.collector.schemaForm.form.get('connection')?.setValue('ghost');
        c.save();
        expect(patch).not.toHaveBeenCalled();
        expect(c.collector.error()).toContain('"ghost" is not a saved Connection');
    });

    it('grandfathers a hand-authored non-local connector with no Connection (TOON survives)', () => {
        const { fixture, patch } = create({ name: 'x', collector: { connector: 'sftp' } });
        const c = fixture.componentInstance;
        expect(c.collector.mode()).toBe('connection');
        c.save();
        const collector = (patch.mock.calls[0][2] as Record<string, unknown>)["collector"] as Record<string, unknown>;
        expect(collector['connector']).toBe('sftp');
        expect(c.collector.error()).toBeNull();
    });

    it('a mode switch alone marks the pane dirty', () => {
        const { fixture, state } = create({ name: 'x' });
        expect(state.isDirty()).toBe(false);
        fixture.componentInstance.collector.setMode('connection');
        expect(state.isDirty()).toBe(true);
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
