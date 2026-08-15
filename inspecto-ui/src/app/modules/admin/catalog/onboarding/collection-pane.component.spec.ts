import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConnectionsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingCollectionPaneComponent } from './collection-pane.component';

/** The PANE no longer toasts — but the shared <inspecto-collector-config> child still injects this. */
const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

function create(collector: Record<string, unknown> | null, profiles: { id: string; connector: string }[] = []) {
    TestBed.configureTestingModule({
        imports: [OnboardingCollectionPaneComponent],
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
    const fixture = TestBed.createComponent(OnboardingCollectionPaneComponent);
    fixture.componentRef.setInput('collector', collector);
    // The pure contract is observed through the outputs — nothing here persists anything.
    const applied: Record<string, unknown>[] = [];
    const dirty: boolean[] = [];
    fixture.componentInstance.applied.subscribe((v) => applied.push(v));
    fixture.componentInstance.dirtyChange.subscribe((v) => dirty.push(v));
    fixture.detectChanges();
    return { fixture, applied, dirty, c: fixture.componentInstance };
}

/**
 * The pane is a thin host over the shared `<inspecto-collector-config>`: the collector chrome (mode,
 * fields, Connection affordances, derived connector) is asserted through `collectorRef`, while what
 * stays the PANE's job — nesting, delete markers, the resolved connector — is asserted on what it
 * EMITS. Since D2 the pane does not save; the host does, so there is no write spy here at all.
 */
describe('OnboardingCollectionPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('initialises the shared component from the collector input (Connection mode for a non-local connector)', () => {
        const { c } = create({ connector: 'sftp', duplicate: { mode: 'checksum' } });
        expect(c.collectorRef!.mode()).toBe('connection');
        expect(c.initial()['duplicate__mode']).toBe('checksum');
        expect(c.collectorRef!.schemaForm.form.get('duplicate__mode')?.value).toBe('checksum');
    });

    it('emits the block with connector "local" and nested dot keys instead of saving it', () => {
        const { c, applied } = create(null);
        expect(c.collectorRef!.mode()).toBe('local');
        c.collectorRef!.schemaForm.form.get('include')?.setValue('*.csv, *.txt');
        c.submit();
        expect(applied).toHaveLength(1);
        expect(applied[0]['connector']).toBe('local');
        expect(applied[0]['include']).toEqual(['*.csv', '*.txt']);
        // The cleared key travels as an explicit undefined delete marker; the HOST converts it to a
        // wire null (nullifyDeletes) because JSON would silently drop undefined.
        expect('connection' in applied[0]).toBe(true);
        expect(applied[0]['connection']).toBeUndefined();
    });

    it('derives the connector from the picked Connection — it is never asked', () => {
        const { fixture, c, applied } = create(null, [{ id: 'blob_prod', connector: 'azure' }]);
        c.collectorRef!.setMode('connection');
        fixture.detectChanges();
        c.collectorRef!.schemaForm.form.get('connection')?.setValue('blob_prod');
        expect(c.collectorRef!.derivedConnector()).toBe('azure');
        c.submit();
        expect(applied[0]['connector']).toBe('azure');
        expect(applied[0]['connection']).toBe('blob_prod');
    });

    it('switching to Local inbox deletes the stored connection and emits connector "local"', () => {
        const { fixture, c, applied } = create({ connector: 'sftp', connection: 'sftp_prod' }, [
            { id: 'sftp_prod', connector: 'sftp' },
        ]);
        expect(c.collectorRef!.mode()).toBe('connection');
        c.collectorRef!.setMode('local');
        fixture.detectChanges();
        c.submit();
        expect(applied[0]['connector']).toBe('local');
        expect(applied[0]['connection']).toBeUndefined();
    });

    it('emits nothing for a Connection-mode submit with no Connection picked', () => {
        const { fixture, c, applied } = create(null);
        c.collectorRef!.setMode('connection');
        fixture.detectChanges();
        c.submit();
        expect(applied).toHaveLength(0);
        expect(c.collectorRef!.error()).toContain('Pick a Connection');
    });

    it('emits nothing when the typed Connection id is not a saved profile', () => {
        const { fixture, c, applied } = create(null, [{ id: 'blob_prod', connector: 'azure' }]);
        c.collectorRef!.setMode('connection');
        fixture.detectChanges();
        c.collectorRef!.schemaForm.form.get('connection')?.setValue('ghost');
        c.submit();
        expect(applied).toHaveLength(0);
        expect(c.collectorRef!.error()).toContain('"ghost" is not a saved Connection');
    });

    it('grandfathers a hand-authored non-local connector with no Connection (TOON survives)', () => {
        const { c, applied } = create({ connector: 'sftp' });
        expect(c.collectorRef!.mode()).toBe('connection');
        c.submit();
        expect(applied[0]['connector']).toBe('sftp');
        expect(c.collectorRef!.error()).toBeNull();
    });

    it('reports a mode switch as dirty to the host', () => {
        const { c, dirty } = create(null);
        expect(dirty).toEqual([]);
        c.collectorRef!.setMode('connection');
        c.onInteraction();
        expect(dirty).toEqual([true]);
    });

    it('re-seeding the collector input returns the pane to pristine — this is how a saved pane resets', () => {
        const { fixture, c, dirty } = create(null);
        c.collectorRef!.setMode('connection');
        c.onInteraction();
        expect(dirty).toEqual([true]);

        // What the host does after a SUCCESSFUL save: hand back the newly-persisted block.
        fixture.componentRef.setInput('collector', { connector: 'sftp' });
        fixture.detectChanges();

        expect(dirty).toEqual([true, false]);
    });

    it('a failed save leaves the input identical, so the pane stays dirty and the guard still fires', () => {
        const { fixture, c, dirty } = create(null);
        c.collectorRef!.setMode('connection');
        c.onInteraction();
        expect(dirty).toEqual([true]);

        // No re-seed happens on failure — the host's config never advanced.
        fixture.detectChanges();

        expect(dirty).toEqual([true]);
    });

    it('has no a11y violations', async () => {
        const { fixture } = create(null);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
