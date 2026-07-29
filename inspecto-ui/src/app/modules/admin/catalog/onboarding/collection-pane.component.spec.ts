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
const WRITE_OK = { type: 'pipeline', written: true, path: 'x.toon', name: 'x', bytes: 1, overwritten: false, findings: [] };

function create(
    config: Record<string, unknown>,
    write = vi.fn((_type: string, _config: Record<string, unknown>, _opts?: unknown) => of(WRITE_OK)),
    profiles: { id: string; connector: string }[] = [],
) {
    TestBed.configureTestingModule({
        imports: [OnboardingCollectionPaneComponent],
        providers: [
            provideNoopAnimations(),
            OnboardingStateService,
            { provide: ConfigService, useValue: { write } },
            { provide: ConnectionsService, useValue: { list: () => of(profiles), test: vi.fn(() => of({ reachable: true, detail: 'ok' })) } },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    const fixture = TestBed.createComponent(OnboardingCollectionPaneComponent);
    fixture.detectChanges();
    return { fixture, state, write };
}

describe('OnboardingCollectionPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('initialises from the existing collector block (Connection mode for a non-local connector)', () => {
        const { fixture } = create({ name: 'x', collector: { connector: 'sftp', duplicate: { mode: 'checksum' } } });
        const c = fixture.componentInstance;
        expect(c.mode()).toBe('connection');
        expect(c.initial['duplicate__mode']).toBe('checksum');
    });

    it('local save injects connector "local" and nests dot keys, then marks the form pristine', () => {
        const { fixture, state, write } = create({ name: 'x' });
        const c = fixture.componentInstance;
        expect(c.mode()).toBe('local');
        c.schemaForm.form.get('include')?.setValue('*.csv, *.txt');
        c.save();
        expect(write).toHaveBeenCalledTimes(1);
        const collector = (write.mock.calls[0][1] as Record<string, unknown>)['collector'] as Record<string, unknown>;
        expect(collector['connector']).toBe('local');
        expect(collector['include']).toEqual(['*.csv', '*.txt']);
        // Fresh config ⇒ the block is assigned wholesale; the cleared key survives as `undefined`
        // (JSON drops it at the wire) — only a merge over an existing block hard-deletes it.
        expect(collector['connection']).toBeUndefined();
        expect(state.isDirty()).toBe(false);
    });

    it('derives the connector from the picked Connection — it is never asked', () => {
        const { fixture, write } = create({ name: 'x' }, undefined, [{ id: 'blob_prod', connector: 'azure' }]);
        const c = fixture.componentInstance;
        c.setMode('connection');
        fixture.detectChanges();
        c.schemaForm.form.get('connection')?.setValue('blob_prod');
        expect(c.derivedConnector()).toBe('azure');
        c.save();
        const collector = (write.mock.calls[0][1] as Record<string, unknown>)['collector'] as Record<string, unknown>;
        expect(collector['connector']).toBe('azure');
        expect(collector['connection']).toBe('blob_prod');
    });

    it('switching to Local inbox deletes the stored connection and writes connector "local"', () => {
        const { fixture, write } = create(
            { name: 'x', collector: { connector: 'sftp', connection: 'sftp_prod' } },
            undefined,
            [{ id: 'sftp_prod', connector: 'sftp' }],
        );
        const c = fixture.componentInstance;
        expect(c.mode()).toBe('connection');
        c.setMode('local');
        fixture.detectChanges();
        c.save();
        const collector = (write.mock.calls[0][1] as Record<string, unknown>)['collector'] as Record<string, unknown>;
        expect(collector['connector']).toBe('local');
        expect('connection' in collector).toBe(false);
    });

    it('blocks a Connection-mode save with no Connection picked', () => {
        const { fixture, write } = create({ name: 'x' });
        const c = fixture.componentInstance;
        c.setMode('connection');
        fixture.detectChanges();
        c.save();
        expect(write).not.toHaveBeenCalled();
        expect(c.saveError()).toContain('Pick a Connection');
    });

    it('blocks when the typed Connection id is not a saved profile', () => {
        const { fixture, write } = create({ name: 'x' }, undefined, [{ id: 'blob_prod', connector: 'azure' }]);
        const c = fixture.componentInstance;
        c.setMode('connection');
        fixture.detectChanges();
        c.schemaForm.form.get('connection')?.setValue('ghost');
        c.save();
        expect(write).not.toHaveBeenCalled();
        expect(c.saveError()).toContain('"ghost" is not a saved Connection');
    });

    it('grandfathers a hand-authored non-local connector with no Connection (TOON survives)', () => {
        const { fixture, write } = create({ name: 'x', collector: { connector: 'sftp' } });
        const c = fixture.componentInstance;
        expect(c.mode()).toBe('connection');
        c.save();
        const collector = (write.mock.calls[0][1] as Record<string, unknown>)['collector'] as Record<string, unknown>;
        expect(collector['connector']).toBe('sftp');
        expect(c.saveError()).toBeNull();
    });

    it('a mode switch alone marks the pane dirty', () => {
        const { fixture, state } = create({ name: 'x' });
        expect(state.isDirty()).toBe(false);
        fixture.componentInstance.setMode('connection');
        expect(state.isDirty()).toBe(true);
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
