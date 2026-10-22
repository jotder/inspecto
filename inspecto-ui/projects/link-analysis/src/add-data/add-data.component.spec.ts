import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiUrl, LensService } from '@inspecto/core/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penByLabel, penEdit, pickerSet } from '@inspecto/core/testing/pen-field';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import { LaLimitsService } from '@inspecto/link-analysis/link-analysis/la-limits.service';
import { AddDataComponent } from './add-data.component';
import { REMOTE_FALLBACK, SELF_IMPORT_OFF, NO_UPLOAD_RIGHT } from './add-data.model';
import { accountsDraft, lowDraft, summary, transfersDraft } from './add-data.testing';
import type { CommitBody, ImportSummary, OnboardDraft } from './add-data.model';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [AddDataComponent],
    template: `<inspecto-add-data
        [whatColumnsHold]="mode()"
        exploreRoute="/link-analysis"
        (imported)="imported.push($event)"
    ></inspecto-add-data>`,
})
class HostComponent {
    readonly mode = signal<'ask' | 'optional'>('ask');
    readonly imported: ImportSummary[] = [];
}

interface Opts {
    authMode?: 'none' | 'oidc';
    canUpload?: boolean;
    edition?: string;
    features?: Record<string, boolean>;
    reasons?: Record<string, string>;
    selfImport?: boolean | 'unknown';
    mode?: 'ask' | 'optional';
    limits?: { key: string; inForce: number }[];
}

const LIMITS = [
    { key: 'import.max_bytes', inForce: 524288000 },
    { key: 'import.max_rows', inForce: 10000000 },
    { key: 'import.max_datasets', inForce: 20 },
];

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
                    featureReasons: signal(o.reasons ?? {}),
                },
            },
            {
                provide: LensService,
                useValue: { canUploadData: signal(o.canUpload ?? true), canAuthorWorkbench: signal(true) },
            },
            { provide: LaLimitsService, useValue: { items: signal(o.limits ?? LIMITS) } },
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            {
                provide: EntityProfileService,
                useValue: {
                    entityTypes: () =>
                        of({
                            items: [
                                { id: 'account', label: 'Account' },
                                { id: 'msisdn', label: 'Phone number' },
                            ],
                        }),
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.mode.set(o.mode ?? 'ask');
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    const settings = http.expectOne(apiUrl('/settings/data'));
    if (o.selfImport === 'unknown') settings.flush({}, { status: 404, statusText: 'Not Found' });
    else settings.flush({ selfImport: null, selfImportDefault: true, selfImportInForce: o.selfImport ?? true });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
const file = (name = 'transfers.csv') => new File(['txn_id,from_account\nT1,A\n'], name, { type: 'text/csv' });

/** Choose a file through the real chooser, and answer the draft. */
function upload(
    fixture: ComponentFixture<HostComponent>,
    el: HTMLElement,
    d: OnboardDraft,
    f = file(d.file.name),
): void {
    const input = q(el, 'file-input') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [f], configurable: true });
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === apiUrl('/onboard/files'));
    expect(req.request.params.get('kind')).toBe(d.kind);
    req.flush(d);
    fixture.detectChanges();
}

/** Press Create and run; the commit request (answer it with `flush`). */
function commit(fixture: ComponentFixture<HostComponent>, el: HTMLElement): TestRequest {
    (q(el, 'create') as HTMLButtonElement).click();
    fixture.detectChanges();
    return http.expectOne((r) => r.url.endsWith('/commit'));
}

const bodyOf = (req: TestRequest) => req.request.body as CommitBody;

describe('AddDataComponent - the one Add data screen (LA-ONBOARD-1 phase 4, OF-1)', () => {
    afterEach(() => http?.verify());

    it('step 1: formats and this Space limits are shown BEFORE upload; a database is locked on Basic (no violations)', async () => {
        const { el } = create();
        expect(q(el, 'formats-and-limits')!.textContent).toContain('Reads CSV or TSV, Parquet, JSON or NDJSON.');
        expect(q(el, 'limits-line')!.textContent).toContain(
            'Up to 500 MB and 10,000,000 rows per file; 20 imports per Space.',
        );
        expect(q(el, 'excel-line')!.textContent).toContain('otherwise save the sheet as CSV');
        const locked = el.querySelector('inspecto-locked-action')!;
        expect(locked.textContent).toContain('Read from a database or a remote system');
        expect(locked.textContent).toContain(REMOTE_FALLBACK);
        expect(q(el, 'step-columns')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('step 1 on Standard: the database is not locked (a hand-off line instead)', () => {
        const { el } = create({ edition: 'standard' });
        expect(el.querySelector('inspecto-locked-action')).toBeNull();
        expect(q(el, 'remote-hand-off')).not.toBeNull();
    });

    it('a sure draft: every choice pre-applied with its evidence, nothing to confirm, one click commits it (no violations)', async () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        expect(q(el, 'chosen-file')!.textContent).toContain('2,215 rows');
        expect(q(el, 'excel-line')!.textContent).toContain('are read too'); // known from the draft now
        expect(q(el, 'limits-line')!.textContent).toContain('(0 so far)');
        expect(el.textContent).toContain("every one of 1000 sampled lines splits into 6 fields on ','");
        expect(el.textContent).toContain('the names say from_account → to_account');
        expect(el.querySelector('inspecto-chip')).toBeNull(); // no "A guess"
        expect(q(el, 'pending')).toBeNull();
        expect((q(el, 'create') as HTMLButtonElement).disabled).toBe(false);
        expect(q(el, 'create-line')!.textContent).toContain('Creates the Dataset transfers (events between entities)');
        await expectNoA11yViolations(el);
        const req = commit(fixture, el);
        const body = bodyOf(req);
        expect(body.name).toBe('transfers');
        expect(body.link).toEqual({
            from: 'from_account',
            to: 'to_account',
            kind: 'channel',
            time: 'ts',
            amount: 'amount',
        });
        expect(body.primaryEntityType).toBe('account');
        req.flush(summary());
    });

    it('low confidence: highlighted, listed, and Create waits until each is confirmed or changed (OF-2; no violations)', async () => {
        const { fixture, el } = create();
        upload(fixture, el, lowDraft());
        const createBtn = q(el, 'create') as HTMLButtonElement;
        expect(createBtn.disabled).toBe(true);
        expect(createBtn.getAttribute('aria-describedby')).toBe('add-data-pending');
        expect(q(el, 'pending')!.textContent).toContain('The header line');
        expect(q(el, 'pending')!.textContent).toContain('Type of amount');
        expect(q(el, 'pending')!.textContent).toContain('Which way the link goes');
        expect(el.querySelectorAll('inspecto-chip').length).toBe(3);
        expect(el.querySelector('[data-column="amount"]')!.className).toContain('border-l-4');
        await expectNoA11yViolations(el);

        // confirm one, change another: both count; the third still holds Create back
        (el.querySelector('[data-testid="confirm"][data-key="format.header"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        pickerSet(fixture, '[data-testid="type-amount"]', 'VARCHAR');
        expect(q(el, 'pending')!.textContent).not.toContain('The header line');
        expect(q(el, 'pending')!.textContent).not.toContain('Type of amount');
        expect(createBtn.disabled).toBe(true);
        (el.querySelector('[data-testid="confirm"][data-key="link.from"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(q(el, 'pending')).toBeNull();
        expect(createBtn.disabled).toBe(false);
        expect(el.querySelector('[data-column="amount"]')!.className).not.toContain('border-l-4');
        const req = commit(fixture, el);
        const body = bodyOf(req);
        expect(body.columns.find((c) => c.name === 'amount')!.type).toBe('VARCHAR');
        req.flush(summary());
    });

    it('a small CSV (under 50 lines): its file type is a guess too, shown with a Confirm, and Create follows', () => {
        const { fixture, el } = create();
        const d = transfersDraft();
        // what the server answers for a 3-line CSV: every format choice low, the type included (OnboardDraft MIN_SAMPLE)
        for (const k of ['type', 'delimiter', 'header', 'quote']) d.format[k]!.confidence = 'low';
        upload(fixture, el, d);
        expect(q(el, 'pending')!.textContent).toContain('The file type');
        for (const b of Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="confirm"]'))) b.click();
        fixture.detectChanges();
        expect(q(el, 'pending')).toBeNull();
        expect((q(el, 'create') as HTMLButtonElement).disabled).toBe(false);
    });

    it('a failed re-read keeps the draft and puts the format choice back', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        pickerSet(fixture, '[data-testid="format-delimiter"]', ';');
        http.expectOne((r) => r.url === apiUrl('/onboard/files')).flush(
            { error: { message: "nothing could be read with ';'" } },
            { status: 422, statusText: 'Unprocessable' },
        );
        fixture.detectChanges();
        expect(q(el, 'step-columns')).not.toBeNull();
        expect(q(el, 'format-delimiter')!.textContent).toContain('Comma');
        expect(el.textContent).toContain("nothing could be read with ';'");
    });

    it('a different separator re-reads the file with the choice stated (the commit cannot change it)', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        pickerSet(fixture, '[data-testid="format-delimiter"]', ';');
        const req = http.expectOne((r) => r.url === apiUrl('/onboard/files'));
        expect(req.request.params.get('delimiter')).toBe(';');
        expect(req.request.params.get('header')).toBe('true');
        const again = transfersDraft();
        again.format.delimiter = { value: ';', evidence: "you chose ';'", confidence: 'high' };
        req.flush(again);
        fixture.detectChanges();
        expect(el.textContent).toContain("you chose ';'");
    });

    it('rename and leave out columns; the preview and the commit follow', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        penEdit(fixture, penByLabel(el, 'Column 1')!, 'transfer_id');
        const drop = el.querySelector('[data-column="channel"] [data-testid="drop-column"] input') as HTMLInputElement;
        drop.click();
        fixture.detectChanges();
        expect(el.querySelector('[data-entity-column="channel"]')).toBeNull(); // gone from step 3
        const req = commit(fixture, el);
        const body = bodyOf(req);
        expect(body.columns.find((c) => c.name === 'txn_id')!.rename).toBe('transfer_id');
        expect(body.columns.find((c) => c.name === 'channel')).toEqual({ name: 'channel', drop: true });
        expect(body.link!.kind).toBeUndefined(); // the kind column was left out
        req.flush(summary());
    });

    it('an invalid rename holds Create back with the reason on the row', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        penEdit(fixture, penByLabel(el, 'Column 1')!, 'from_account');
        expect(el.textContent).toContain('Two columns would have this name.');
        expect((q(el, 'create') as HTMLButtonElement).disabled).toBe(true);
    });

    it('step 3 changes: another Entity Type, another link end, no link at all warns', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        pickerSet(fixture, '[data-testid="entity-txn_id"]', 'msisdn');
        pickerSet(fixture, '[data-testid="link-to"]', '');
        expect(q(el, 'no-link')!.textContent).toContain('cannot draw this Dataset as a graph');
        const req = commit(fixture, el);
        const body = bodyOf(req);
        expect(body.columns.find((c) => c.name === 'txn_id')!.entityType).toBe('msisdn');
        expect(body.link).toBeNull();
        req.flush(summary());
    });

    it('Details about entities: the identifying column; the commit names it', () => {
        const { fixture, el } = create();
        (el.querySelectorAll('[data-testid="import-kind"] input[type=radio]')[1] as HTMLInputElement).click();
        fixture.detectChanges();
        upload(fixture, el, accountsDraft());
        expect(q(el, 'identifying')!.textContent).toContain('every one of its 312 sampled values is different');
        expect(q(el, 'link-choices')).toBeNull();
        expect(q(el, 'excel-line')!.textContent).toContain('not read by this installation');
        const req = commit(fixture, el);
        const body = bodyOf(req);
        expect(body.identifyingColumn).toBe('account_id');
        req.flush(summary({ dataset: 'accounts', kind: 'reference' }));
    });

    it('the console: step 3 starts closed and a guess left there is not applied', () => {
        const { fixture, el } = create({ mode: 'optional' });
        upload(fixture, el, lowDraft());
        expect(q(el, 'open-hold')).not.toBeNull();
        expect(q(el, 'link-choices')).toBeNull();
        expect(q(el, 'pending')!.textContent).not.toContain('Which way the link goes');
        (el.querySelector('[data-testid="confirm"][data-key="format.header"]') as HTMLButtonElement).click();
        (el.querySelector('[data-testid="confirm"][data-key="type.amount"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        const req = commit(fixture, el);
        const body = bodyOf(req);
        expect(body.link).toBeNull();
        req.flush(summary());
    });

    it('a large file: 202, the run is followed, and the result shows when it ends', () => {
        vi.useFakeTimers();
        try {
            const { fixture, el } = create();
            upload(fixture, el, transfersDraft());
            commit(fixture, el).flush(
                { runId: 'r1', dataset: 'transfers', action: 'commit', state: 'running' },
                { status: 202, statusText: 'Accepted' },
            );
            fixture.detectChanges();
            expect(q(el, 'running')!.textContent).toContain('runs on the server - following it');
            vi.advanceTimersByTime(1500);
            http.expectOne(apiUrl('/onboard/runs/r1')).flush({
                runId: 'r1',
                dataset: 'transfers',
                action: 'commit',
                state: 'succeeded',
                summary: summary(),
            });
            fixture.detectChanges();
            expect(q(el, 'add-data-result')!.textContent).toContain('transfers is ready');
            expect(fixture.componentInstance.imported).toEqual([summary()]);
        } finally {
            vi.useRealTimers();
        }
    });

    it('the result: plain words, the Dataset card and the next actions (no violations)', async () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        commit(fixture, el).flush(summary({ rowsRejected: 12 }));
        fixture.detectChanges();
        const result = q(el, 'add-data-result')!;
        expect(result.querySelector('h2')!.textContent).toContain('transfers is ready');
        expect(result.textContent).toContain('12 rows were set aside');
        expect(result.textContent).toContain('The link index was built: 2,215 links between 312 entities.');
        expect(q(el, 'import-dataset-card')).not.toBeNull();
        expect(q(el, 'next-actions')!.textContent).toContain('Explore in Link Analysis');
        await expectNoA11yViolations(el);

        const router = TestBed.inject(Router);
        const nav = vi.spyOn(router, 'navigate').mockResolvedValue(true);
        q(el, 'explore')!.click();
        expect(nav).toHaveBeenCalledWith(['/link-analysis'], { queryParams: { dataset: 'transfers' } });
    });

    it('"Add details about these entities" starts over for a file that describes them', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        commit(fixture, el).flush(summary());
        fixture.detectChanges();
        q(el, 'add-details')!.click();
        fixture.detectChanges();
        const radios = el.querySelectorAll<HTMLInputElement>('[data-testid="import-kind"] input[type=radio]');
        expect(radios[1].checked).toBe(true);
        expect(q(el, 'drop-zone')).not.toBeNull();
    });

    it('a refused commit (422) says why and keeps the choices', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        commit(fixture, el).flush(
            { error: { message: "a Dataset 'transfers' already exists; choose another name or use Add a file on it" } },
            { status: 409, statusText: 'Conflict' },
        );
        fixture.detectChanges();
        expect(q(el, 'create-error')!.textContent).toContain("a Dataset 'transfers' already exists");
        expect(q(el, 'step-columns')).not.toBeNull();
    });

    it('a 403 at upload locks the screen with the server reason (never a failing upload twice)', () => {
        const { fixture, el } = create({ selfImport: 'unknown' });
        const input = q(el, 'file-input') as HTMLInputElement;
        Object.defineProperty(input, 'files', { value: [file()], configurable: true });
        input.dispatchEvent(new Event('change'));
        http.expectOne((r) => r.url === apiUrl('/onboard/files')).flush(
            { error: { message: SELF_IMPORT_OFF } },
            { status: 403, statusText: 'Forbidden' },
        );
        fixture.detectChanges();
        expect(q(el, 'add-data-locked')!.textContent).toContain(SELF_IMPORT_OFF);
        expect(q(el, 'drop-zone')).toBeNull();
    });

    it('a 503 (no place to keep data) is explained, not an error', () => {
        const { fixture, el } = create();
        const input = q(el, 'file-input') as HTMLInputElement;
        Object.defineProperty(input, 'files', { value: [file()], configurable: true });
        input.dispatchEvent(new Event('change'));
        http.expectOne((r) => r.url === apiUrl('/onboard/files')).flush(
            { error: { message: 'this Space has no data directory, so nothing can be imported' } },
            { status: 503, statusText: 'Unavailable' },
        );
        fixture.detectChanges();
        expect(el.textContent).toContain('This Space cannot take data');
        expect(el.textContent).toContain('no data directory');
        expect(q(el, 'file-error')).toBeNull();
    });

    it('an Excel refusal (422 "save as CSV") is said, and the Excel line learns it', () => {
        const { fixture, el } = create();
        const input = q(el, 'file-input') as HTMLInputElement;
        Object.defineProperty(input, 'files', { value: [file('book.xlsx')], configurable: true });
        input.dispatchEvent(new Event('change'));
        http.expectOne((r) => r.url === apiUrl('/onboard/files')).flush(
            { error: { message: "this installation does not read Excel: save 'book.xlsx' as CSV" } },
            { status: 422, statusText: 'Unprocessable' },
        );
        fixture.detectChanges();
        expect(q(el, 'file-error')!.textContent).toContain("save 'book.xlsx' as CSV");
        expect(q(el, 'excel-line')!.textContent).toContain('not read by this installation');
    });

    it('locked: Add data turned off in this Space - the whole screen is the reason (A-15; no violations)', async () => {
        const { el } = create({ selfImport: false });
        expect(q(el, 'add-data-locked')!.textContent).toContain(SELF_IMPORT_OFF);
        expect(q(el, 'file-input')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('locked: signed in without canUploadData', () => {
        const { el } = create({ authMode: 'oidc', canUpload: false });
        expect(q(el, 'add-data-locked')!.textContent).toContain(NO_UPLOAD_RIGHT);
    });

    it('no sign-in passes the right even when the lens says no (the person is their own administrator)', () => {
        const { el } = create({ authMode: 'none', canUpload: false });
        expect(q(el, 'add-data-locked')).toBeNull();
    });

    it('leaving with a file read but no Dataset made counts the step where the person stopped (OB-13)', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        fixture.destroy();
        const r = http.expectOne((q) => q.url.endsWith('/onboarding/events'));
        expect(r.request.body).toEqual({ event: 'step_abandoned', step: 'add_data:columns' });
        r.flush({ recorded: 'step_abandoned' });
    });

    it('leaving before choosing a file reports nothing', () => {
        const { fixture } = create();
        fixture.destroy();
        http.expectNone((q) => q.url.endsWith('/onboarding/events'));
    });
});
