import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfigService, ConnectionsService, LensService, PipelinesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CollectorCreateDialog } from './collector-create.dialog';

describe('CollectorCreateDialog', () => {
    let ref: { close: ReturnType<typeof vi.fn> };
    let configApi: {
        write: ReturnType<typeof vi.fn>;
        registerPipeline: ReturnType<typeof vi.fn>;
        patch: ReturnType<typeof vi.fn>;
    };
    let toastr: { success: ReturnType<typeof vi.fn>; warning: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

    beforeEach(() => {
        ref = { close: vi.fn() };
        configApi = {
            write: vi.fn(() => of({ path: 'orders_ingest_pipeline.toon' })),
            registerPipeline: vi.fn(() => of({ id: 'orders_ingest' })),
            patch: vi.fn(() => of({ path: 'existing_pipe_pipeline.toon' })),
        };
        toastr = { success: vi.fn(), warning: vi.fn(), error: vi.fn() };

        TestBed.configureTestingModule({
            imports: [CollectorCreateDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MatDialogRef, useValue: ref },
                { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
                { provide: ConfigService, useValue: configApi },
                {
                    provide: PipelinesService,
                    useValue: { list: () => of([{ name: 'existing_pipe', template: false }]) },
                },
                {
                    provide: ConnectionsService,
                    useValue: { list: () => of([{ id: 'prod_sftp', connector: 'sftp' }]), test: vi.fn() },
                },
                { provide: LensService, useValue: { canAuthorWorkbench: () => true, screenLocks: () => ({}) } },
                { provide: ToastrService, useValue: toastr },
            ],
        });
    });

    it('creates a new pipeline with dirs.poll and collector block, then registers it', () => {
        const fixture = TestBed.createComponent(CollectorCreateDialog);
        fixture.detectChanges();
        const cmp = fixture.componentInstance;

        cmp.form.controls.name.setValue('Orders Ingest');
        expect(cmp.form.controls.poll.value).toBe('data/inbox/orders_ingest');
        cmp.form.controls.poll.setValue('custom/orders_in');
        cmp.form.controls.poll.markAsDirty();

        cmp.save();

        expect(configApi.write).toHaveBeenCalledTimes(1);
        const [type, written] = configApi.write.mock.calls[0] as [string, Record<string, unknown>];
        expect(type).toBe('pipeline');
        expect(written['id']).toBe('orders_ingest');
        expect((written['dirs'] as Record<string, unknown>)['poll']).toBe('custom/orders_in');
        expect((written['collector'] as Record<string, unknown>)['connector']).toBe('local');
        expect(configApi.registerPipeline).toHaveBeenCalledWith('orders_ingest_pipeline.toon');
        expect(ref.close).toHaveBeenCalledWith({ created: true, pipeline: 'orders_ingest' });
    });

    it('rejects duplicate pipeline names', () => {
        const fixture = TestBed.createComponent(CollectorCreateDialog);
        fixture.detectChanges();
        const cmp = fixture.componentInstance;

        cmp.form.controls.name.setValue('existing_pipe');
        cmp.save();

        expect(cmp.form.controls.name.hasError('duplicate')).toBe(true);
        expect(configApi.write).not.toHaveBeenCalled();
    });

    it('patches an existing pipeline with collector and optional dirs.poll', () => {
        const fixture = TestBed.createComponent(CollectorCreateDialog);
        fixture.detectChanges();
        const cmp = fixture.componentInstance;

        cmp.setTargetMode('existing');
        cmp.form.controls.existingPipeline.setValue('existing_pipe');
        cmp.form.controls.poll.setValue('inbox/retargeted');
        cmp.save();

        expect(configApi.patch).toHaveBeenCalledWith(
            'pipeline',
            'existing_pipe',
            expect.objectContaining({
                dirs: { poll: 'inbox/retargeted' },
                collector: expect.objectContaining({ connector: 'local' }),
            }),
        );
        expect(ref.close).toHaveBeenCalledWith({ created: true, pipeline: 'existing_pipe' });
    });

    it('surfaces API errors inline without closing', () => {
        configApi.write.mockReturnValue(
            throwError(() => new HttpErrorResponse({ status: 500, error: { error: { message: 'Disk full' } } })),
        );
        const fixture = TestBed.createComponent(CollectorCreateDialog);
        fixture.detectChanges();
        const cmp = fixture.componentInstance;

        cmp.form.controls.name.setValue('fresh_pipe');
        cmp.save();

        expect(cmp.error()).toContain('Disk full');
        expect(ref.close).not.toHaveBeenCalled();
    });

    it('has no a11y violations', async () => {
        const fixture = TestBed.createComponent(CollectorCreateDialog);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
