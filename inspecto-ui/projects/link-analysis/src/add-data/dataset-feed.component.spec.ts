import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, describe, expect, it } from 'vitest';
import { apiUrl, LensService } from '@inspecto/core/api';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { DatasetFeedComponent } from './dataset-feed.component';
import { DatasetFeedState, everyWords, feedStatusLine, waitingWords } from './dataset-feed.model';
import { dataStatusLines } from './add-data.model';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [DatasetFeedComponent],
    template: `<inspecto-dataset-feed
        datasetId="calls"
        [offerKeep]="offerKeep()"
        [snapshot]="snapshot()"
        (importWaiting)="imports = imports + 1"
        (feedChange)="last = $event"
    ></inspecto-dataset-feed>`,
})
class HostComponent {
    readonly offerKeep = signal(false);
    readonly snapshot = signal(false);
    readonly feed = viewChild(DatasetFeedComponent);
    imports = 0;
    last: DatasetFeedState | null = null;
}

const STATE: DatasetFeedState = {
    dataset: 'calls',
    inbox: '/srv/inspecto/data/inbox/calls',
    waiting: 0,
    files: [],
    truncated: false,
    automatic: true,
    feed: { kind: 'uploads' },
};

let http: HttpTestingController;

function create(o: { offerKeep?: boolean; canAuthor?: boolean; snapshot?: boolean } = {}) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(),
            provideHttpClientTesting(),
            {
                provide: LA_FEATURES,
                useValue: {
                    signIn: signal(true),
                    edition: signal('standard'),
                    flags: signal({}),
                    featureReasons: signal({}),
                },
            },
            {
                provide: LensService,
                useValue: { canUploadData: signal(true), canAuthorWorkbench: signal(o.canAuthor ?? true) },
            },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.offerKeep.set(o.offerKeep ?? false);
    fixture.componentInstance.snapshot.set(o.snapshot ?? false);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

describe('DatasetFeedComponent - the inbox and Keep this up to date (LA-ARRIVAL-FEEDS-1)', () => {
    afterEach(() => http?.verify());

    it('Basic: the inbox path, the files waiting and Import waiting files; nothing to keep up to date (no violations)', async () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({
            ...STATE,
            automatic: false,
            waiting: 7,
            files: Array.from({ length: 7 }, (_, i) => ({ name: `drop_${i}.csv`, size: 1, modified: '' })),
        });
        fixture.detectChanges();
        expect(q(el, 'inbox-path')!.textContent).toBe('/srv/inspecto/data/inbox/calls');
        expect(q(el, 'waiting')!.textContent).toBe('7 files waiting');
        expect(el.querySelectorAll('[data-testid="waiting-files"] li').length).toBe(6); // five names, then "and 2 more"
        expect(q(el, 'waiting-files')!.textContent).toContain('and 2 more');
        expect(q(el, 'keep-form')).toBeNull();
        expect(fixture.componentInstance.last!.waiting).toBe(7);
        q(el, 'import-waiting')!.click();
        expect(fixture.componentInstance.imports).toBe(1);
        await expectNoA11yViolations(el);
    });

    it('a file with the name of one already imported is said, with what to do (Run now would skip it)', () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({
            ...STATE,
            waiting: 2,
            alreadyImported: 1,
            files: [
                { name: 'oct.csv', size: 1, modified: '', alreadyImported: true },
                { name: 'nov.csv', size: 1, modified: '', alreadyImported: false },
            ],
        });
        fixture.detectChanges();
        expect(q(el, 'waiting')!.textContent).toBe(
            '2 files waiting (1 has the name of a file already imported: rename to import again)',
        );
        const items = Array.from(el.querySelectorAll('[data-testid="waiting-files"] li')).map((li) =>
            li.textContent!.trim(),
        );
        expect(items[0]).toContain('oct.csv - already imported under this name');
        expect(items[1]).toBe('nov.csv');
    });

    it('Watch this folder (BASIC-ONBOARD-1 P2): on, said in the inbox, and a failed import shown (no violations)', async () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({ ...STATE, automatic: false, watchable: true });
        fixture.detectChanges();
        const toggle = () => el.querySelector<HTMLButtonElement>('[data-testid="watch-folder"] button')!;
        expect(toggle().getAttribute('aria-checked')).toBe('false');
        toggle().click();
        const req = http.expectOne(apiUrl('/datasets/calls/watch'));
        expect(req.request.method).toBe('PUT');
        expect(req.request.body).toEqual({ watch: true });
        req.flush({
            ...STATE,
            automatic: false,
            watchable: true,
            watch: true,
            watchError: 'the import could not run: disk full',
        });
        fixture.detectChanges();
        expect(toggle().getAttribute('aria-checked')).toBe('true');
        expect(q(el, 'dataset-inbox')!.textContent).toContain('checked every 30 seconds');
        expect(q(el, 'watch-error')!.textContent).toContain('disk full');
        expect(fixture.componentInstance.last!.watch).toBe(true);
        await expectNoA11yViolations(el);
    });

    it('a table snapshot: no inbox and no refresh toggle - it is copied again only by Refresh now (no violations)', async () => {
        const { fixture, el } = create({ snapshot: true });
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({ ...STATE, watchable: false, snapshot: true });
        fixture.detectChanges();
        expect(q(el, 'dataset-inbox')).toBeNull();
        expect(q(el, 'watch-folder')).toBeNull();
        expect(el.querySelector('mat-slide-toggle')).toBeNull();
        expect(q(el, 'snapshot-manual')!.textContent).toContain('only when you press Refresh now');
        await expectNoA11yViolations(el);
    });

    it('Watch this folder refused: the toggle goes back and the reason is said', () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({ ...STATE, watchable: true });
        fixture.detectChanges();
        const toggle = () => el.querySelector<HTMLButtonElement>('[data-testid="watch-folder"] button')!;
        toggle().click();
        http.expectOne(apiUrl('/datasets/calls/watch')).flush(
            { error: { message: 'a Reference is replaced with Add a file' } },
            { status: 422, statusText: 'Unprocessable' },
        );
        fixture.detectChanges();
        expect(toggle().getAttribute('aria-checked')).toBe('false');
        expect(q(el, 'feed-note')!.textContent).toContain('Reference');
    });

    it('a Dataset the server says cannot be watched (a Reference, a schedule) offers no Watch this folder', () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({ ...STATE, watchable: false });
        fixture.detectChanges();
        expect(q(el, 'watch-folder')).toBeNull();
    });

    it('no file waiting: no Import waiting files button', () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/calls/feed')).flush(STATE);
        fixture.detectChanges();
        expect(q(el, 'waiting')!.textContent).toBe('No files waiting');
        expect(q(el, 'import-waiting')).toBeNull();
    });

    it('Standard: Keep this up to date watches the inbox, then saves the proposed index Job (no violations)', async () => {
        const { fixture, el } = create({ offerKeep: true });
        http.expectOne(apiUrl('/connections')).flush([
            { id: 'partner_box', connector: 'sftp' },
            { id: 'warehouse', connector: 'db' },
        ]);
        http.expectOne(apiUrl('/datasets/calls/feed')).flush(STATE);
        fixture.detectChanges();
        expect(q(el, 'keep-form')).not.toBeNull();
        await expectNoA11yViolations(el);

        q(el, 'keep-up-to-date')!.click();
        const put = http.expectOne(apiUrl('/datasets/calls/feed'));
        expect(put.request.method).toBe('PUT');
        expect(put.request.body).toEqual({ kind: 'folder', every: '15m' });
        const job = { name: 'calls_index', type: 'la.index.build', dataset: 'calls' };
        put.flush({ ...STATE, feed: { kind: 'folder', every: '15m' }, indexJob: job });
        const post = http.expectOne(apiUrl('/jobs'));
        expect(post.request.method).toBe('POST');
        expect(post.request.body).toEqual(job);
        post.flush(job);
        fixture.detectChanges();
        expect(q(el, 'feed-on')!.textContent).toContain('the inbox is watched, checked every 15 minutes');
        expect(q(el, 'feed-note')!.textContent).toContain('the Job calls_index adds them to the graph');
        expect(fixture.componentInstance.last!.feed.kind).toBe('folder');
    });

    it('Keep after Stop: the index Job already saved (409) is said as kept, not as a failure', () => {
        const { fixture, el } = create({ offerKeep: true });
        http.expectOne(apiUrl('/connections')).flush([]);
        http.expectOne(apiUrl('/datasets/calls/feed')).flush(STATE);
        fixture.detectChanges();
        q(el, 'keep-up-to-date')!.click();
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({
            ...STATE,
            feed: { kind: 'folder', every: '15m' },
            indexJob: { name: 'calls_index', type: 'la.index.build' },
        });
        http.expectOne(apiUrl('/jobs')).flush(
            { error: { message: 'exists' } },
            { status: 409, statusText: 'Conflict' },
        );
        fixture.detectChanges();
        expect(q(el, 'feed-note')!.textContent).toContain('calls_index (already saved) adds them to the graph');
    });

    it('without the right to author, the index Job is not posted: the note says whom to ask', () => {
        const { fixture, el } = create({ offerKeep: true, canAuthor: false });
        http.expectOne(apiUrl('/connections')).flush([]);
        http.expectOne(apiUrl('/datasets/calls/feed')).flush(STATE);
        fixture.detectChanges();
        q(el, 'keep-up-to-date')!.click();
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({
            ...STATE,
            feed: { kind: 'folder', every: '15m' },
            indexJob: { name: 'calls_index', type: 'la.index.build' },
        });
        fixture.detectChanges();
        http.expectNone(apiUrl('/jobs'));
        expect(q(el, 'feed-note')!.textContent).toContain('ask an administrator to add the Job calls_index');
    });

    it('an SFTP feed needs its Connection before anything is sent; the server refusal is said in place', () => {
        const { fixture, el } = create({ offerKeep: true });
        http.expectOne(apiUrl('/connections')).flush([{ id: 'partner_box', connector: 'sftp' }]);
        http.expectOne(apiUrl('/datasets/calls/feed')).flush(STATE);
        fixture.detectChanges();
        const feed = fixture.componentInstance.feed()!;
        (feed as unknown as { kind: { setValue(v: string): void } }).kind.setValue('sftp');
        fixture.detectChanges();
        expect(q(el, 'feed-connection')).not.toBeNull();
        q(el, 'keep-up-to-date')!.click();
        fixture.detectChanges();
        http.expectNone(apiUrl('/datasets/calls/feed'));
        expect(el.querySelector('[data-testid="keep-form"] [role="alert"]')!.textContent).toContain(
            'Choose the SFTP Connection',
        );

        (feed as unknown as { connection: { setValue(v: string): void } }).connection.setValue('partner_box');
        q(el, 'keep-up-to-date')!.click();
        const put = http.expectOne(apiUrl('/datasets/calls/feed'));
        expect(put.request.body).toEqual({ kind: 'sftp', every: '15m', connection: 'partner_box' });
        put.flush({ error: { message: 'no Connection' } }, { status: 422, statusText: 'Unprocessable' });
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="keep-form"] [role="alert"]')!.textContent).toContain('no Connection');
    });

    it('a kept-up-to-date Dataset says how, and Stop goes back to uploads', () => {
        const { fixture, el } = create({ offerKeep: true });
        http.expectOne(apiUrl('/connections')).flush([]);
        http.expectOne(apiUrl('/datasets/calls/feed')).flush({
            ...STATE,
            feed: { kind: 'sftp', connection: 'partner_box', every: '1h' },
        });
        fixture.detectChanges();
        expect(q(el, 'feed-on')!.textContent).toContain('SFTP, Connection partner_box, checked every hour');
        q(el, 'stop-feed')!.click();
        const del = http.expectOne(apiUrl('/datasets/calls/feed'));
        expect(del.request.method).toBe('DELETE');
        del.flush(STATE);
        fixture.detectChanges();
        expect(q(el, 'feed-on')).toBeNull();
        expect(q(el, 'keep-up-to-date')).not.toBeNull();
    });
});

describe('dataset-feed wording', () => {
    it('Data status: the feed and storage lines follow the clocks, from the ONE arrivals read', () => {
        const read = {
            dataset: 'calls',
            eventDateColumn: null,
            arrivals: [],
            truncated: false,
            reason: null,
            feed: { kind: 'folder' as const, every: '15m', waiting: 2, alreadyImported: 0 },
            storage: {
                dataset: 'calls',
                files: 1,
                bytes: 2048,
                threshold: 20,
                compactedAt: '2026-11-05T10:02:00',
                compactedArrivals: 21,
                removedArrivals: 0,
                retention: { days: 90 },
                retentionAvailable: true,
            },
        };
        expect(dataStatusLines(read)).toEqual([
            'Kept up to date: the inbox is watched, every 15 minutes · 2 files waiting',
            'Storage: 1 file · 2 KB · compacted 2026-11-05 10:02 (21 arrivals) · keeps the last 90 days',
        ]);
        expect(dataStatusLines({ ...read, feed: { kind: 'uploads', waiting: 0 }, storage: null })).toEqual([]);
    });

    it('says the interval and the files waiting in words', () => {
        expect(everyWords('15m')).toBe('every 15 minutes');
        expect(everyWords('1h')).toBe('every hour');
        expect(everyWords('1d')).toBe('every day');
        expect(everyWords(undefined)).toBe('');
        expect(waitingWords(0)).toBe('No files waiting');
        expect(waitingWords(1)).toBe('1 file waiting');
        expect(waitingWords(3, 2)).toBe(
            '3 files waiting (2 have the names of files already imported: rename to import again)',
        );
    });

    it('the Data status line: nothing for plain uploads, the files waiting, or how the Dataset is kept up to date', () => {
        expect(feedStatusLine({ ...STATE, feed: { kind: 's3', connection: 'bucket', every: '1h' } })).toBe(
            'Kept up to date: s3, Connection bucket, every hour',
        );
        expect(feedStatusLine(null)).toBeNull();
        expect(feedStatusLine(STATE)).toBeNull();
        expect(feedStatusLine({ ...STATE, watch: true })).toBe('Inbox watched, checked every 30 seconds');
        expect(feedStatusLine({ ...STATE, watch: true, waiting: 2, watchError: 'disk full' })).toBe(
            'Inbox watched, checked every 30 seconds · 2 files waiting · the last import failed: disk full',
        );
        expect(feedStatusLine({ ...STATE, waiting: 3 })).toBe('Inbox: 3 files waiting');
        expect(feedStatusLine({ ...STATE, feed: { kind: 'folder', every: '5m' } })).toBe(
            'Kept up to date: the inbox is watched, every 5 minutes',
        );
        expect(feedStatusLine({ ...STATE, waiting: 1, feed: { kind: 'sftp', connection: 'box', every: '1d' } })).toBe(
            'Kept up to date: SFTP, Connection box, every day · 1 file waiting',
        );
    });
});
