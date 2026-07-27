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

    it('initialises the form from the existing collector block', () => {
        const { fixture } = create({ name: 'x', collector: { connector: 'sftp', duplicate: { mode: 'checksum' } } });
        const c = fixture.componentInstance;
        expect(c.initial['connector']).toBe('sftp');
        expect(c.initial['duplicate__mode']).toBe('checksum');
    });

    it('save nests dot keys into the collector block and marks the form pristine', () => {
        const { fixture, state, write } = create({ name: 'x' });
        const c = fixture.componentInstance;
        c.schemaForm.form.get('connector')?.setValue('sftp');
        c.schemaForm.form.get('connection')?.setValue('sftp_prod');
        c.schemaForm.form.get('include')?.setValue('*.csv, *.txt');
        c.save();
        expect(write).toHaveBeenCalledTimes(1);
        const written = write.mock.calls[0][1] as Record<string, unknown>;
        const collector = written['collector'] as Record<string, unknown>;
        expect(collector['connector']).toBe('sftp');
        expect(collector['connection']).toBe('sftp_prod');
        expect(collector['include']).toEqual(['*.csv', '*.txt']);
        expect(state.isDirty()).toBe(false);
    });

    it('registers its dirty check with the session state', () => {
        const { fixture, state } = create({ name: 'x' });
        const c = fixture.componentInstance;
        expect(state.isDirty()).toBe(false);
        c.schemaForm.form.get('connector')?.setValue('sftp');
        c.schemaForm.form.get('connector')?.markAsDirty();
        expect(state.isDirty()).toBe(true);
    });

    it('adopts the picked Connection\'s own connector instead of trusting the select', async () => {
        // The two fields can otherwise disagree, and CollectorConnectors.forConfig dispatches on
        // `connector` while handing that factory the profile named by `connection`.
        const { fixture, write } = create({ name: 'x', collector: { connector: 'sftp' } }, undefined, [
            { id: 'blob_prod', connector: 'azure' },
        ]);
        const c = fixture.componentInstance;
        c.schemaForm.form.get('connection')?.setValue('blob_prod');
        await Promise.resolve();
        expect(c.schemaForm.form.get('connector')?.value).toBe('azure');
        c.save();
        expect((write.mock.calls[0][1]['collector'] as Record<string, unknown>)['connector']).toBe('azure');
    });

    it('leaves the connector select alone when no Connection is picked (local inbox)', async () => {
        const { fixture } = create({ name: 'x', collector: { connector: 'local' } }, undefined, [
            { id: 'blob_prod', connector: 'azure' },
        ]);
        await Promise.resolve();
        expect(fixture.componentInstance.schemaForm.form.get('connector')?.value).toBe('local');
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
