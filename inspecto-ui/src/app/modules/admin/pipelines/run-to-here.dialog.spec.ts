import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import {
    ConnectionProbeService,
    PipelineInboxListing,
    PipelineInboxUpload,
    PipelineRunResult,
    PipelinesService,
    ResourceNode,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RunToHereData, RunToHereDialog } from './run-to-here.dialog';

const RUN_RESULT: PipelineRunResult = {
    seedNode: 'collect',
    toNode: 'parse',
    files: ['inbox/feed_001.csv.gz'],
    relations: [
        { node: 'parse', rel: 'success', rowCount: 3, rows: [{ id: 1 }, { id: 2 }, { id: 3 }] },
        { node: 'parse', rel: 'unmatched', rowCount: 1, rows: [{ line: 7 }] },
    ],
    output: {
        store: 'Parse CSV',
        format: 'PARQUET',
        path: 'data/_scratch/cdr_ingest/parse/part-0001.parquet',
        rowCount: 3,
    },
    warnings: [],
};

const INBOX = (names: string[]): PipelineInboxListing => ({
    pipeline: 'cdr_ingest',
    inbox: '/space/data/inbox/cdr_ingest',
    total: names.length,
    truncated: false,
    files: names.map((name) => ({ name, size: 12, modifiedAt: '2026-09-25T00:00:00Z' })),
});

interface InboxFakes {
    inboxFiles?: () => Observable<PipelineInboxListing>;
    uploadToInbox?: (id: string, file: File, overwrite?: boolean) => Observable<PipelineInboxUpload>;
    confirm?: () => Promise<boolean>;
}

function create(
    data: Partial<RunToHereData> = {},
    runToNode: () => Observable<PipelineRunResult> = () => of(RUN_RESULT),
    fakes: InboxFakes = {},
) {
    const inboxFiles = fakes.inboxFiles ?? (() => of(INBOX([])));
    const uploadToInbox = fakes.uploadToInbox ?? (() => throwError(() => new Error('not stubbed')));
    TestBed.configureTestingModule({
        imports: [RunToHereDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: () => {} } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: {
                    pipelineId: 'cdr_ingest',
                    node: { id: 'parse', type: 'parser.dsv' },
                    connectionId: null,
                    ...data,
                },
            },
            { provide: PipelinesService, useValue: { runToNode, inboxFiles, uploadToInbox } },
            { provide: InspectoConfirmService, useValue: { confirm: fakes.confirm ?? (() => Promise.resolve(false)) } },
            { provide: ConnectionProbeService, useValue: { explore: () => of([]) } },
        ],
    });
    const fixture = TestBed.createComponent(RunToHereDialog);
    fixture.detectChanges();
    return fixture;
}

describe('RunToHereDialog', () => {
    it('runs the subgraph and exposes the per-relation result + Parquet output', () => {
        const c = create().componentInstance;
        c.run();
        expect(c.result()?.output?.rowCount).toBe(3);
        expect(c.result()?.relations.map((r) => `${r.node}/${r.rel}`)).toEqual(['parse/success', 'parse/unmatched']);
    });

    // TESTRUN-FAILED-BATCH-REPORTED-EMPTY-1: a FAILED batch answers 422 carrying the batch's own error; the
    // dialog must SHOW it (rendered alert, not just a signal) and show no result that could read as success.
    it('renders a failed batch as the error the server gave, with no result', () => {
        const message = 'test run failed: the batch FAILED after 2 row(s) parsed: Binder Error: NO_SUCH_COLUMN';
        const fixture = create({}, () =>
            throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message } } })),
        );
        fixture.componentInstance.run();
        fixture.detectChanges();
        const alert = fixture.nativeElement.querySelector('inspecto-alert[variant="error"]');
        expect(alert?.textContent).toContain(message);
        expect(fixture.componentInstance.result()).toBeNull();
    });

    it('toggles a file into and out of the selection', () => {
        const c = create().componentInstance;
        const file: ResourceNode = {
            name: 'feed_001.csv.gz',
            path: 'inbox/feed_001.csv.gz',
            kind: 'file',
            hasChildren: false,
        };
        c.onSelect(file);
        expect(c.selectedFiles()).toEqual(['inbox/feed_001.csv.gz']);
        c.onSelect(file);
        expect(c.selectedFiles()).toEqual([]);
    });

    // INBOX-UPLOAD-1: a pipeline with no connection lists its dirs.poll and can take an uploaded file.
    it('lists the inbox of a pipeline with no connection, and a listed file toggles into the selection', () => {
        const fixture = create({}, undefined, { inboxFiles: () => of(INBOX(['matches.csv'])) });
        const el: HTMLElement = fixture.nativeElement;
        const row = el.querySelector<HTMLButtonElement>('ul[aria-label="Inbox files"] button');
        expect(row?.textContent).toContain('matches.csv');
        row!.click();
        fixture.detectChanges();
        expect(fixture.componentInstance.selectedFiles()).toEqual(['matches.csv']);
        expect(row!.getAttribute('aria-pressed')).toBe('true');
    });

    it('uploads a picked file, refreshes the inbox list and selects the upload', () => {
        let listing = INBOX([]);
        const uploads: { name: string; overwrite?: boolean }[] = [];
        const fixture = create({}, undefined, {
            inboxFiles: () => of(listing),
            uploadToInbox: (_id, file, overwrite) => {
                uploads.push({ name: file.name, overwrite });
                listing = INBOX([file.name]);
                return of({ pipeline: 'cdr_ingest', file: file.name, size: file.size, replaced: false });
            },
        });
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('The inbox is empty');

        const input = el.querySelector<HTMLInputElement>('input[type="file"]')!;
        const file = new File(['ID,AMT\n1,2\n'], 'matches.csv', { type: 'text/csv' });
        Object.defineProperty(input, 'files', { value: [file] });
        input.dispatchEvent(new Event('change'));
        fixture.detectChanges();

        expect(uploads).toEqual([{ name: 'matches.csv', overwrite: false }]);
        expect(el.querySelector('ul[aria-label="Inbox files"]')?.textContent).toContain('matches.csv');
        expect(fixture.componentInstance.selectedFiles()).toEqual(['matches.csv']);
    });

    it('asks before replacing an existing inbox file, then retries with overwrite', async () => {
        const calls: (boolean | undefined)[] = [];
        const c = create({}, undefined, {
            uploadToInbox: (_id, file, overwrite) => {
                calls.push(overwrite);
                return overwrite
                    ? of({ pipeline: 'cdr_ingest', file: file.name, size: file.size, replaced: true })
                    : throwError(() => new HttpErrorResponse({ status: 409 }));
            },
            confirm: () => Promise.resolve(true),
        }).componentInstance;
        c.upload(new File(['x'], 'a.csv'), false);
        await Promise.resolve();
        await Promise.resolve();
        expect(calls).toEqual([false, true]);
        expect(c.uploadError()).toBeNull();
    });

    it('renders a refused upload as the server error', () => {
        const message = "'file' must be a bare file name, not a path: ../x.csv";
        const fixture = create({}, undefined, {
            uploadToInbox: () =>
                throwError(() => new HttpErrorResponse({ status: 403, error: { error: { message } } })),
        });
        fixture.componentInstance.upload(new File(['x'], 'x.csv'), false);
        fixture.detectChanges();
        const alert = fixture.nativeElement.querySelector('inspecto-alert[variant="error"]');
        expect(alert?.textContent).toContain(message);
    });

    it('offers no upload for a connection-bound source', () => {
        const fixture = create({ connectionId: 'sftp_in' });
        expect(fixture.nativeElement.querySelector('input[type="file"]')).toBeNull();
    });

    it('has no a11y violations', async () => {
        const fixture = create({}, undefined, { inboxFiles: () => of(INBOX(['matches.csv'])) });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
