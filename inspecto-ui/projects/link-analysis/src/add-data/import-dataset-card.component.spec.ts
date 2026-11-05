import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiUrl, LensService } from '@inspecto/core/api';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { AddDataAccess } from './add-data-access';
import {
    Arrival,
    AUTOMATION_FALLBACK,
    ImportDataset,
    ImportSummary,
    SELF_IMPORT_OFF,
    arrivalLine,
    dataStatusLine,
} from './add-data.model';
import { summary } from './add-data.testing';
import { ImportDatasetCardComponent } from './import-dataset-card.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [ImportDatasetCardComponent],
    template: `<inspecto-import-dataset-card
        [dataset]="dataset()"
        [summary]="last()"
        [editorRoute]="editorRoute()"
        (imported)="got.push($event)"
    ></inspecto-import-dataset-card>`,
})
class HostComponent {
    readonly dataset = signal<ImportDataset>({ id: 'transfers', kind: 'stream', importPipeline: 'transfers' });
    readonly last = signal<ImportSummary | null>(null);
    readonly editorRoute = signal<string | null>(null);
    readonly got: ImportSummary[] = [];
}

interface Opts {
    edition?: string;
    authMode?: 'none' | 'oidc';
    features?: Record<string, boolean>;
    canAuthor?: boolean;
    editorRoute?: string | null;
    kind?: 'stream' | 'reference';
}

let http: HttpTestingController;

function create(o: Opts = {}) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
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
                    signIn: signal((o.authMode ?? 'none') === 'oidc'),
                    edition: signal(o.edition ?? 'basic'),
                    flags: signal(o.features ?? {}),
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
    fixture.componentInstance.editorRoute.set(o.editorRoute ?? null);
    if (o.kind) fixture.componentInstance.dataset.set({ id: 'accounts', kind: o.kind });
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el };
}

const FEED_STATE = {
    dataset: 'transfers',
    inbox: '/srv/inspecto/data/inbox/transfers',
    waiting: 0,
    files: [],
    truncated: false,
    automatic: false,
    feed: { kind: 'uploads' },
};

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

describe('ImportDatasetCardComponent - the Dataset card of an import (UC-06, UC-14, DT-14)', () => {
    afterEach(() => {
        // LA-ARRIVAL-LEDGER-1: the Dataset card reads its arrivals; a test that does not look at them answers none
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
        http?.verify();
    });

    it('Basic: "one-time import", Add a file beside Run now, and Keep this up to date locked with its reason (no violations)', async () => {
        const { fixture, el } = create();
        fixture.componentInstance.last.set(summary());
        fixture.detectChanges();
        expect(el.textContent).toContain('one-time import');
        expect(el.querySelector('h3')!.textContent).toBe('transfers');
        expect(q(el, 'add-a-file')).not.toBeNull();
        expect(q(el, 'run-now')).not.toBeNull();
        expect(q(el, 'add-a-file')!.parentElement).toBe(q(el, 'run-now')!.parentElement); // side by side
        expect(el.textContent).toContain('2,215 rows were read from transfers.csv');
        const keep = q(el, 'keep-up-to-date')!;
        expect(keep.querySelector('inspecto-locked-action')!.textContent).toContain('Keep this up to date');
        expect(keep.textContent).toContain(AUTOMATION_FALLBACK);
        await expectNoA11yViolations(el);
    });

    it('Standard in the console, with the right to author: Open in editor opens the guided editor on its Pipeline', () => {
        const { el } = create({ edition: 'standard', authMode: 'oidc', editorRoute: '/pipelines' });
        const link = q(el, 'open-in-editor') as HTMLAnchorElement;
        expect(link.getAttribute('href')).toBe('/pipelines?guided=1&open=transfers');
    });

    it('Standard without the editor or the right to author: Keep this up to date is offered on the card itself (no violations)', async () => {
        const { fixture, el } = create({
            edition: 'standard',
            authMode: 'oidc',
            editorRoute: '/pipelines',
            canAuthor: false,
        });
        expect(q(el, 'open-in-editor')).toBeNull();
        http.expectOne(apiUrl('/datasets/transfers/feed')).flush(FEED_STATE);
        fixture.detectChanges();
        expect(q(el, 'keep-form')).not.toBeNull();
        expect(q(el, 'keep-form')!.querySelector('inspecto-locked-action')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('LA-ARRIVAL-FEEDS-1: the inbox shows the files waiting, and Import waiting files is Run now (re-read after it)', () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/transfers/feed')).flush({
            ...FEED_STATE,
            waiting: 2,
            files: [
                { name: 'drop_a.csv', size: 10, modified: '2026-11-05T09:00:00Z' },
                { name: 'drop_b.csv', size: 5, modified: '2026-11-05T09:01:00Z' },
            ],
        });
        fixture.detectChanges();
        expect(q(el, 'waiting')!.textContent).toBe('2 files waiting');
        expect(q(el, 'keep-form')).toBeNull(); // Basic: no Keep this up to date (D-1)
        q(el, 'import-waiting')!.click();
        fixture.detectChanges();
        http.expectOne(apiUrl('/datasets/transfers/run')).flush(summary());
        fixture.detectChanges();
        expect(fixture.componentInstance.got.length).toBe(1);
        http.expectOne(apiUrl('/datasets/transfers/feed')).flush(FEED_STATE); // re-read after the run
        fixture.detectChanges();
        expect(q(el, 'waiting')!.textContent).toBe('No files waiting');
    });

    it('Add a file: the chosen mode, the new summary shown and emitted', () => {
        const { fixture, el } = create();
        (el.querySelectorAll('[data-testid="add-file-mode"] input[type=radio]')[1] as HTMLInputElement).click();
        fixture.detectChanges();
        const input = el.querySelector('input[type=file]') as HTMLInputElement;
        Object.defineProperty(input, 'files', { value: [new File(['a'], 'oct.csv')], configurable: true });
        input.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        expect(el.textContent).toContain('Running the import');
        const req = http.expectOne((r) => r.url === apiUrl('/datasets/transfers/files'));
        expect(req.request.params.get('mode')).toBe('append');
        expect(req.request.params.get('name')).toBe('oct.csv');
        req.flush(summary({ file: 'oct.csv', rowsBefore: 2215, rowsAfter: 4430, rowsChanged: 2215 }));
        fixture.detectChanges();
        expect(el.textContent).toContain('2,215 rows were added: 4,430 rows now.');
        expect(fixture.componentInstance.got.length).toBe(1);
    });

    it('LA-ARRIVAL-MODES-1: "replaces the days it covers" is offered only with a When column, and sent as mode=period', async () => {
        const { fixture, el } = create();
        expect(q(el, 'add-file-period')).toBeNull(); // the arrivals (and so the When column) are not read yet
        http.expectOne(apiUrl('/datasets/transfers/arrivals')).flush({
            dataset: 'transfers',
            eventDateColumn: 'booked_at',
            arrivals: [],
            truncated: false,
            reason: null,
        });
        fixture.detectChanges();
        (q(el, 'add-file-period')!.querySelector('input[type=radio]') as HTMLInputElement).click();
        fixture.detectChanges();
        const input = el.querySelector('input[type=file]') as HTMLInputElement;
        Object.defineProperty(input, 'files', { value: [new File(['a'], 'week.csv')], configurable: true });
        input.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        const req = http.expectOne((r) => r.url === apiUrl('/datasets/transfers/files'));
        expect(req.request.params.get('mode')).toBe('period');
        req.flush(
            summary({
                file: 'week.csv',
                rowsBefore: 100,
                rowsAfter: 79,
                rowsChanged: -21,
                period: { days: 7, from: '2026-09-02', to: '2026-09-08', rowsReplaced: 28, filesRewritten: 1 },
                newColumns: ['fee'],
            }),
        );
        fixture.detectChanges();
        expect(el.textContent).toContain(
            'It replaced 2026-09-02 – 2026-09-08 (7 days): 28 older rows were removed, 79 rows now.',
        );
        expect(el.textContent).toContain('New column not kept: fee.');
        await expectNoA11yViolations(el);
    });

    it('LA-ARRIVAL-NEW-COLUMNS-1: new columns are kept by default; unticked, they are left out and the reason is said', async () => {
        const { fixture, el } = create();
        http.expectOne(apiUrl('/datasets/transfers/arrivals')).flush({
            dataset: 'transfers',
            eventDateColumn: null,
            arrivals: [],
            truncated: false,
            reason: null,
        });
        fixture.detectChanges();
        const keep = q(el, 'keep-new-columns')!.querySelector('input[type=checkbox]') as HTMLInputElement;
        expect(keep.checked).toBe(true);
        const input = el.querySelector('input[type=file]') as HTMLInputElement;
        const choose = (name: string) => {
            Object.defineProperty(input, 'files', { value: [new File(['a'], name)], configurable: true });
            input.dispatchEvent(new Event('change'));
            fixture.detectChanges();
        };
        choose('wider.csv');
        const kept = http.expectOne((r) => r.url === apiUrl('/datasets/transfers/files'));
        expect(kept.request.params.get('newColumns')).toBe('keep');
        kept.flush(summary({ file: 'wider.csv', keptColumns: ['fee'] }));
        fixture.detectChanges();
        expect(el.textContent).toContain('New column fee: kept as a detail.');

        keep.click();
        fixture.detectChanges();
        choose('taxed.csv');
        const left = http.expectOne((r) => r.url === apiUrl('/datasets/transfers/files'));
        expect(left.request.params.get('newColumns')).toBe('leave');
        left.flush(summary({ file: 'taxed.csv', newColumns: ['tax'], newColumnsReason: 'you chose to leave new columns out' }));
        fixture.detectChanges();
        expect(el.textContent).toContain('New column not kept: tax (you chose to leave new columns out).');
        await expectNoA11yViolations(el);
    });

    it('BASIC-ONBOARD-1 P3: a Postgres snapshot is refreshed from its table - no Add a file, no inbox (no violations)', async () => {
        const { fixture, el } = create();
        fixture.componentInstance.dataset.set({
            id: 'transfers',
            kind: 'stream',
            importPipeline: 'transfers',
            importSource: 'postgres',
        });
        fixture.detectChanges();
        expect(el.textContent).toContain('Postgres snapshot');
        expect(q(el, 'add-a-file')).toBeNull();
        expect(q(el, 'add-file-mode')).toBeNull();
        // the feed is mounted in snapshot mode: never an inbox, and no refresh toggle (manual only, operator 2026-11-05)
        expect(el.querySelector('inspecto-dataset-feed')).not.toBeNull();
        expect(q(el, 'dataset-inbox')).toBeNull();
        expect(q(el, 'run-now')!.textContent).toContain('Refresh now');
        expect(q(el, 'snapshot-note')!.textContent).toContain('replaces everything');
        q(el, 'run-now')!.click();
        http.expectOne(apiUrl('/datasets/transfers/run')).flush(summary());
        fixture.detectChanges();
        expect(fixture.componentInstance.got.length).toBe(1);
        await expectNoA11yViolations(el);
    });

    it('ADD-DATA-FILTER-EDIT-1: Edit filter on the card saves new conditions and the card says them', () => {
        const { fixture, el } = create();
        fixture.componentInstance.dataset.set({
            id: 'transfers',
            kind: 'stream',
            importPipeline: 'transfers',
            importFilter: [{ column: 'channel', op: 'eq', value: 'mobile' }],
        });
        fixture.detectChanges();
        expect(q(el, 'keeps-rows')!.textContent!.trim()).toBe('Keeps rows where channel is mobile.');
        q(el, 'edit-filter')!.click();
        http.expectOne(apiUrl('/datasets/transfers/filter')).flush({
            dataset: 'transfers',
            filter: [{ column: 'channel', op: 'eq', value: 'mobile' }],
            columns: [{ name: 'channel', type: 'VARCHAR' }],
        });
        fixture.detectChanges();
        expect(q(el, 'filter-applies')!.textContent).toContain('rows already imported stay');
        el.querySelector<HTMLButtonElement>('[data-condition="0"] [data-testid="remove-condition"]')!.click();
        fixture.detectChanges();
        q(el, 'save-filter')!.click();
        http.expectOne(apiUrl('/datasets/transfers/filter')).flush({ dataset: 'transfers', filter: [], columns: [] });
        fixture.detectChanges();
        expect(q(el, 'keeps-rows')).toBeNull(); // every row is kept from now on
        // a fresh entry from the host carries its own conditions again
        fixture.componentInstance.dataset.set({
            id: 'transfers',
            kind: 'stream',
            importPipeline: 'transfers',
            importFilter: [{ column: 'channel', op: 'ne', value: 'branch' }],
        });
        fixture.detectChanges();
        expect(q(el, 'keeps-rows')!.textContent!.trim()).toBe('Keeps rows where channel is not branch.');
    });

    it('ADD-DATA-ROW-FILTER-1: an import that keeps only some rows says which, and the arrivals how many were left out (no violations)', async () => {
        const { fixture, el } = create();
        expect(q(el, 'keeps-rows')).toBeNull(); // no filter: no line
        fixture.componentInstance.dataset.set({
            id: 'transfers',
            kind: 'stream',
            importPipeline: 'transfers',
            importFilter: [
                { column: 'channel', op: 'eq', value: 'mobile' },
                { column: 'amount', op: 'range', min: '100' },
                { column: 'booked_on', op: 'range', min: '2026-01-01', max: '2026-03-31' },
                { column: 'note', op: 'contains', value: 'REFUND', matchCase: true },
            ],
        });
        fixture.detectChanges();
        expect(q(el, 'keeps-rows')!.textContent!.trim()).toBe(
            "Keeps rows where channel is mobile and amount is 100 or more and booked_on is between 2026-01-01 and 2026-03-31 and note contains 'REFUND' (exact case).",
        );
        const reads = http.match((r) => r.url.endsWith('/arrivals')).filter((r) => !r.cancelled);
        expect(reads.length).toBeGreaterThan(0);
        reads.forEach((r) =>
            r.flush({
                dataset: 'transfers',
                eventDateColumn: null,
                truncated: false,
                reason: null,
                arrivals: [
                    {
                        file: 'october.csv',
                        sender: 'upload',
                        received: '2026-11-05 10:00:00',
                        status: 'SUCCESS',
                        rowsRead: 40,
                        rowsRejected: 0,
                        rowsFilteredOut: 60,
                        mode: 'replace',
                        sha256: null,
                        index: null,
                        consignment: 'c1',
                        kept: true,
                        coversFrom: null,
                        coversTo: null,
                    },
                ],
            }),
        );
        fixture.detectChanges();
        expect(q(el, 'arrivals')!.textContent).toContain('60 rows left out by the filter');
        await expectNoA11yViolations(el);
    });

    it('a Reference is always replaced (no mode choice)', () => {
        const { fixture, el } = create({ kind: 'reference' });
        expect(q(el, 'add-file-mode')).toBeNull();
        expect(el.textContent).toContain('Details about entities');
        // LA-REFERENCE-DETAILS-MORE-1: a Reference's card says how many of its entities the other data holds
        http.expectOne(apiUrl('/datasets/accounts/coverage')).flush({
            dataset: 'accounts',
            entityType: 'account',
            entityTypeLabel: 'Account',
            identifyingColumn: 'account_id',
            described: 312,
            datasets: [{ dataset: 'transfers', columns: ['to_account'], appear: 295, absent: 17 }],
            truncated: false,
        });
        fixture.detectChanges();
        expect(q(el, 'reference-coverage')!.textContent).toContain('295 appear in transfers; 17 do not.');
    });

    it('an events Dataset reads no coverage', () => {
        const { el } = create();
        http.expectNone(apiUrl('/datasets/transfers/coverage'));
        expect(q(el, 'reference-coverage')).toBeNull();
    });

    it('Run now: a 202 is followed; a refusal is said in plain words', () => {
        vi.useFakeTimers();
        try {
            const { fixture, el } = create();
            q(el, 'run-now')!.click();
            http.expectOne(apiUrl('/datasets/transfers/run')).flush(
                { runId: 'r5', dataset: 'transfers', action: 'run', state: 'running' },
                { status: 202, statusText: 'Accepted' },
            );
            fixture.detectChanges();
            expect(el.textContent).toContain('runs on the server - following it');
            vi.advanceTimersByTime(1500);
            http.expectOne(apiUrl('/onboard/runs/r5')).flush({
                runId: 'r5',
                dataset: 'transfers',
                action: 'run',
                state: 'failed',
                message: 'the file was set aside',
            });
            fixture.detectChanges();
            expect(el.querySelector('inspecto-alert')!.textContent).toContain(
                'The import did not finish: the file was set aside',
            );
            expect((q(el, 'run-now') as HTMLButtonElement).disabled).toBe(false);
        } finally {
            vi.useRealTimers();
        }
    });

    it('a 403 locks Add a file and Run now with the server reason', () => {
        const { fixture, el } = create();
        q(el, 'run-now')!.click();
        http.expectOne(apiUrl('/datasets/transfers/run')).flush(
            { error: { message: SELF_IMPORT_OFF } },
            { status: 403, statusText: 'Forbidden' },
        );
        fixture.detectChanges();
        expect(q(el, 'card-locked')!.textContent).toContain(SELF_IMPORT_OFF);
        expect((q(el, 'run-now') as HTMLButtonElement).disabled).toBe(true);
        expect(TestBed.inject(AddDataAccess).lockReason()).toBe(SELF_IMPORT_OFF);
    });

    it('LA-ARRIVAL-LEDGER-1: shows the Data status and the newest arrivals, five at first, a set-aside one marked (no violations)', async () => {
        const { fixture, el } = create();
        const a = (i: number, over: Partial<Arrival> = {}): Arrival => ({
            file: `f${i}.csv`,
            sender: 'upload',
            received: `2026-11-0${i} 09:1${i}:00`,
            status: 'SUCCESS',
            rowsRead: 10 * i,
            rowsRejected: 0,
            mode: 'append',
            sha256: null,
            index: null,
            consignment: `b${i}`,
            kept: true,
            coversFrom: `2026-09-0${i} 00:00:00`,
            coversTo: `2026-09-1${i} 00:00:00`,
            ...over,
        });
        const list = [
            a(7, { sender: 'system', mode: null, file: 'drop.csv' }),
            a(6, { index: 'BUILT' }),
            a(5),
            a(4),
            a(3),
            a(2, { kept: false }),
            a(1, { mode: 'replace', kept: false }),
        ];
        http.expectOne(apiUrl('/datasets/transfers/arrivals')).flush({
            dataset: 'transfers',
            eventDateColumn: 'booked_at',
            arrivals: list,
            truncated: false,
            reason: null,
        });
        fixture.detectChanges();
        const status = q(el, 'data-status')!.textContent ?? '';
        expect(status).toContain('Received 2026-11-07 09:17');
        expect(status).toContain('covers 2026-09-03 – 2026-09-17'); // the kept arrivals only
        const items = () => Array.from(q(el, 'arrivals')!.querySelectorAll('li')).map((li) => li.textContent ?? '');
        expect(items().length).toBe(5);
        expect(items()[0]).toContain('drop.csv');
        expect(items()[0]).toContain('from a system');
        expect(items()[1]).toContain('uploaded · added its rows · 60 rows');
        (q(el, 'arrivals-more') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(items().length).toBe(7);
        expect(items()[6]).toContain('replaced the data');
        expect(items()[6]).toContain('set aside by a later replace');
        await expectNoA11yViolations(el);
    });

    it('LA-ARRIVAL-LEDGER-1: the graph part of the Data status is said only when the import recorded it', () => {
        const base: Arrival = {
            file: 'x.csv',
            sender: 'upload',
            received: '2026-11-02 08:00:00',
            status: 'SUCCESS',
            rowsRead: 1,
            rowsRejected: 0,
            mode: 'append',
            sha256: null,
            index: null,
            consignment: 'b',
            kept: true,
            coversFrom: null,
            coversTo: null,
        };
        expect(dataStatusLine([base])).toBe('Received 2026-11-02 08:00');
        expect(dataStatusLine([{ ...base, index: 'BUILT' }])).toBe('Received 2026-11-02 08:00 · graph up to date');
        expect(dataStatusLine([])).toBeNull();
        // the graph clock: how many files arrived after the newest one the graph took (Run now records its own outcome)
        const drop: Arrival = { ...base, sender: 'system', index: 'FAILED' };
        expect(dataStatusLine([drop, { ...base, index: 'BUILT' }])).toBe(
            'Received 2026-11-02 08:00 · graph 1 file behind',
        );
        expect(dataStatusLine([drop, drop, { ...base, index: 'UP_TO_DATE' }])).toContain('graph 2 files behind');
        expect(
            dataStatusLine([
                { ...drop, index: 'BUILT' },
                { ...base, index: 'FAILED' },
            ]),
        ).toContain('graph up to date');
        // an arrival with no recorded outcome is not counted as behind: nothing is said rather than guessed
        expect(dataStatusLine([base, { ...base, index: 'BUILT' }])).toBe('Received 2026-11-02 08:00');
        expect(dataStatusLine([drop])).toContain('the graph was not updated');
        // a Dataset with no link mapping has no graph to speak of
        expect(dataStatusLine([{ ...base, index: 'SKIPPED' }])).toBe('Received 2026-11-02 08:00');
        // LA-ARRIVAL-MODES-1: a period arrival, a file's new columns, and a refused drop (never in the Data status)
        // LA-ARRIVAL-NEW-COLUMNS-1: a column the run kept
        expect(arrivalLine({ ...base, keptColumns: ['fee'] })).toContain('kept new: fee');
        expect(arrivalLine({ ...base, mode: 'period', newColumns: ['fee'] })).toBe(
            'uploaded · replaced the days it covers · 1 row · not kept: fee',
        );
        const refused: Arrival = {
            ...base,
            sender: 'system',
            status: 'REFUSED',
            rowsRead: null,
            kept: false,
            received: '2026-11-03 10:00:00',
            reason: "'x.csv' does not have the columns of 't': 'fee' is missing.",
            setAside: 'refused/x.csv',
        };
        expect(arrivalLine(refused)).toBe(
            "from a system · refused: 'x.csv' does not have the columns of 't': 'fee' is missing. · set aside in refused/x.csv",
        );
        expect(dataStatusLine([refused, { ...base, index: 'BUILT' }])).toBe(
            'Received 2026-11-02 08:00 · graph up to date',
        );
        expect(arrivalLine({ ...base, rowsRead: 5, rowsRejected: 2 })).toBe(
            'uploaded · added its rows · 5 rows (2 rejected)',
        );
    });
});
