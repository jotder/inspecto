import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
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
    recordTypes?: { id: string; label: string; likelyTypes: string[]; roleHints?: { category?: string[] } }[];
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
                                { id: 'account', label: 'Account', family: 'account' },
                                {
                                    id: 'msisdn',
                                    label: 'Phone number',
                                    family: 'identifier',
                                    example: '+44 7700 900123',
                                    shape: 'e164',
                                },
                            ],
                            families: [
                                { id: 'identifier', label: 'Identifier', definition: 'A number that names something.' },
                                { id: 'account', label: 'Account', definition: 'Holds a balance.' },
                            ],
                            ...(o.recordTypes ? { recordTypes: o.recordTypes } : {}),
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

    it('what a column holds: grouped by family, a proposal the Space does not know under Other (ENTITY-TYPE-CATALOG-1)', () => {
        const { fixture } = create();
        const add = fixture.debugElement.children[0].componentInstance as unknown as {
            entityOptions: () => unknown[];
            draft: { set(d: unknown): void };
        };
        add.draft.set({
            entityTypes: { columns: [{ entityType: { value: 'iban' } }, { entityType: { value: 'msisdn' } }] },
        });
        expect(add.entityOptions()).toEqual([
            { value: '', label: 'Nothing: a detail', hint: 'Shown on links or entities, not drawn as a node' },
            { value: 'msisdn', label: 'Phone number', group: 'Identifier', hint: 'e.g. +44 7700 900123' },
            { value: 'account', label: 'Account', group: 'Account' },
            { value: 'iban', label: 'iban', group: 'Other' },
        ]);
    });

    const RECORDS = [{ id: 'transfer', label: 'Transfer', likelyTypes: ['account'] }];
    const proposedTransfer = (): OnboardDraft => {
        const d = transfersDraft();
        return {
            ...d,
            entityTypes: { ...d.entityTypes, recordType: { value: 'transfer', evidence: 'x', confidence: 'high' } },
        };
    };

    it('the commit sends the proposed record type (ENTITY-TYPE-CATALOG-1 P2)', () => {
        const { fixture, el } = create({ recordTypes: RECORDS });
        upload(fixture, el, proposedTransfer());
        const req = commit(fixture, el);
        expect(bodyOf(req).recordType).toBe('transfer');
        req.flush(summary());
    });

    it('the commit sends no record type once the person clears it, not the proposal (ENTITY-TYPE-CATALOG-1 P2)', () => {
        const { fixture, el } = create({ recordTypes: RECORDS });
        upload(fixture, el, proposedTransfer());
        const add = fixture.debugElement.children[0].componentInstance as unknown as {
            form: { controls: { recordType: { setValue(v: string): void } } };
        };
        add.form.controls.recordType.setValue(''); // "Not said"
        fixture.detectChanges();
        const req = commit(fixture, el);
        expect(bodyOf(req).recordType).toBeNull();
        req.flush(summary());
    });

    it('another record type re-proposes the roles still as proposed, and back (ENTITY-TYPE-RECORD-ROLES-1)', () => {
        const { fixture, el } = create({
            recordTypes: [
                { id: 'transfer', label: 'Transfer', likelyTypes: ['account'], roleHints: { category: ['txn'] } },
            ],
        });
        upload(fixture, el, transfersDraft()); // drafted with no record type
        const add = fixture.debugElement.children[0].componentInstance as unknown as {
            form: {
                controls: {
                    recordType: { setValue(v: string): void };
                    columns: { controls: { controls: { name: { value: string }; role: { value: string } } }[] };
                };
            };
        };
        const role = (n: string) =>
            add.form.controls.columns.controls.find((g) => g.controls.name.value === n)!.controls.role.value;
        expect(role('txn_id')).toBe('detail');
        add.form.controls.recordType.setValue('transfer');
        fixture.detectChanges();
        expect(role('txn_id')).toBe('dimension');
        expect(el.textContent).toContain("a transfer usually has a Category named like 'txn'");
        add.form.controls.recordType.setValue('');
        fixture.detectChanges();
        expect(role('txn_id')).toBe('detail');
        add.form.controls.recordType.setValue('transfer');
        fixture.detectChanges();
        const req = commit(fixture, el);
        expect(bodyOf(req).columns.find((c) => c.name === 'txn_id')).toMatchObject({ role: 'dimension' });
        req.flush(summary());
    });

    it('a Space without record types sends none and is not asked (ENTITY-TYPE-CATALOG-1 P2)', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        expect(q(el, 'record-type')).toBeNull();
        const req = commit(fixture, el);
        expect('recordType' in bodyOf(req)).toBe(false);
        req.flush(summary());
    });

    it('each column: the record type likely types first, and a type the column sample fits marked (ENTITY-TYPE-CATALOG-1 P2)', async () => {
        const { fixture, el } = create({
            recordTypes: [{ id: 'transfer', label: 'Transfer', likelyTypes: ['account'] }],
        });
        const add = fixture.debugElement.children[0].componentInstance as unknown as {
            entityOptionsByColumn: () => ReadonlyMap<string, { value: string; group?: string; hint?: string }[]>;
            form: { controls: { recordType: { setValue(v: string): void } } };
        };
        const base = transfersDraft();
        const d = transfersDraft({
            columns: [
                ...base.columns,
                {
                    name: 'payer_phone',
                    header: 'payer_phone',
                    type: { ...base.columns[0].type },
                    sample: ['+447700900123', ''],
                },
            ],
            entityTypes: {
                ...base.entityTypes,
                recordType: {
                    value: 'transfer',
                    evidence: '2 columns hold types a transfer usually holds',
                    confidence: 'high',
                },
            },
        });
        upload(fixture, el, d);
        expect(q(el, 'record-type')).not.toBeNull();
        const from = add.entityOptionsByColumn().get('from_account')!;
        expect(from.map((o) => [o.group, o.value])).toEqual([
            [undefined, ''],
            ['Likely in a transfer', 'account'],
            ['Identifier', 'msisdn'],
        ]);
        const phone = add.entityOptionsByColumn().get('payer_phone')!;
        expect(phone.find((o) => o.value === 'msisdn')!.hint).toBe('Shape matches. e.g. +44 7700 900123');
        // ACC-… values fit no phone number
        expect(from.find((o) => o.value === 'msisdn')!.hint).toBe('e.g. +44 7700 900123');

        add.form.controls.recordType.setValue('');
        fixture.detectChanges();
        expect(add.entityOptionsByColumn().get('from_account')![1].group).toBe('Identifier');
        await expectNoA11yViolations(el);
    });

    it('step 1: formats and this Space limits are shown BEFORE upload; a database is locked on Basic (no violations)', async () => {
        const { el } = create();
        expect(q(el, 'formats-and-limits')!.textContent).toContain('Reads CSV or TSV, Parquet, JSON or NDJSON.');
        expect(q(el, 'limits-line')!.textContent).toContain(
            'Up to 500 MB and 10,000,000 rows per file; 20 imports per Space.',
        );
        expect(q(el, 'excel-line')!.textContent).toContain('otherwise save the sheet as CSV');
        const locked = el.querySelector('inspecto-locked-action')!;
        expect(locked.textContent).toContain('Read from another database or a remote system');
        expect(locked.textContent).toContain(REMOTE_FALLBACK);
        expect(q(el, 'step-columns')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('BASIC-ONBOARD-1 P3: step 1 offers a Postgres table on Basic; its draft reads as a file draft (no violations)', async () => {
        const { fixture, el } = create();
        const postgres = q(el, 'data-origin')!.querySelectorAll<HTMLInputElement>('input[type="radio"]')[1];
        postgres.click();
        fixture.detectChanges();
        expect(el.querySelector('inspecto-postgres-table')).not.toBeNull();
        expect(q(el, 'drop-zone')).toBeNull();
        await expectNoA11yViolations(el);

        const d = transfersDraft();
        const snap = {
            ...d,
            snapshot: {
                source: 'postgres' as const,
                connection: {
                    host: 'h',
                    port: 5432,
                    database: 'sales',
                    user: 'reader',
                    password: '${ENV:INSPECTO_PG_SALES}',
                },
                schema: 'public',
                table: 'transfers',
            },
        };
        (
            fixture.debugElement.children[0].componentInstance as unknown as { adoptSnapshot(x: unknown): void }
        ).adoptSnapshot(snap);
        fixture.detectChanges();
        expect(q(el, 'chosen-file')!.textContent).toContain('sales.public.transfers');
        expect(q(el, 'choose-another')!.textContent).toContain('Choose another table');
        expect(q(el, 'step-columns')).not.toBeNull();

        // after Create, the result card is the snapshot's: Refresh now, no Add a file
        commit(fixture, el).flush(summary());
        fixture.detectChanges();
        expect(q(el, 'import-dataset-card')!.textContent).toContain('Postgres snapshot');
        expect(q(el, 'add-a-file')).toBeNull();
    });

    it('step 1 on Standard: the database is not locked (a hand-off line instead)', () => {
        const { el } = create({ edition: 'standard' });
        expect(el.querySelector('inspecto-locked-action')).toBeNull();
        expect(q(el, 'remote-hand-off')).not.toBeNull();
    });

    it('LA-COLUMN-ROLES-1 P1: a role row per column that holds no Entity Type; the primaries offer only their role', async () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        expect(q(el, 'role-from_account')).toBeNull(); // an Entity: its Holds row says so
        expect(q(el, 'role-ts')!.textContent).toContain('Time');
        expect(q(el, 'role-amount')!.textContent).toContain('Measure');
        expect(q(el, 'role-channel')!.textContent).toContain('Category');
        const cmp = fixture.debugElement.query(By.directive(AddDataComponent)).componentInstance as unknown as {
            linkColumnOptions: () => Record<string, { value: string }[]>;
            roleOptions: (t: string) => { value: string }[];
        };
        expect(cmp.linkColumnOptions()['primaryTime'].map((o) => o.value)).toEqual(['', 'ts']);
        expect(cmp.linkColumnOptions()['primaryMeasure'].map((o) => o.value)).toEqual(['', 'amount']);
        expect(cmp.roleOptions('VARCHAR').map((o) => o.value)).not.toContain('measure');
        expect(cmp.roleOptions('DOUBLE').map((o) => o.value)).toContain('measure');
        await expectNoA11yViolations(el);
    });

    it('BASIC-ONBOARD-1 P1: with no follow-on registered the review sentence promises no link index', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft({ followOns: [] }));
        const line = q(el, 'create-line')!.textContent!;
        expect(line).toContain('runs it once.');
        expect(line).not.toContain('link index');
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
        expect(q(el, 'create-line')!.textContent).toContain('runs it once, and builds its link index.');
        await expectNoA11yViolations(el);
        const req = commit(fixture, el);
        const body = bodyOf(req);
        expect(body.name).toBe('transfers');
        expect(body.link).toEqual({
            from: 'from_account',
            to: 'to_account',
            kind: 'channel',
            primaryTime: 'ts',
            primaryMeasure: 'amount',
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
        expect(body.filter).toBeUndefined(); // no row condition: every row (ADD-DATA-ROW-FILTER-1)
        req.flush(summary());
    });

    it('ADD-DATA-ROW-FILTER-1: row conditions on the review step are said in words and sent as the commit filter (no violations)', async () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        expect(q(el, 'keeps-line')).toBeNull();

        (q(el, 'add-condition') as HTMLButtonElement).click();
        fixture.detectChanges();
        // an empty condition holds Create back, with its reason on the row
        expect(el.querySelector('[data-condition="0"] [role="alert"]')!.textContent).toContain('Choose a column.');
        expect((q(el, 'create') as HTMLButtonElement).disabled).toBe(true);
        pickerSet(fixture, '[data-condition="0"] [data-testid="condition-column"]', 'channel');
        expect(el.querySelector('[data-condition="0"] [role="alert"]')!.textContent).toContain('Enter a value.');
        penEdit(
            fixture,
            el.querySelector<HTMLElement>('[data-condition="0"] [data-testid="condition-value"]')!,
            ' mobile ',
        );

        (q(el, 'add-condition') as HTMLButtonElement).click();
        fixture.detectChanges();
        pickerSet(fixture, '[data-condition="1"] [data-testid="condition-column"]', 'amount');
        // a number column offers a range, and no "contains"
        const ops = fixture.debugElement.queryAll(By.css('[data-condition="1"] [data-testid="condition-op"]'))[0]
            .componentInstance as { options: () => { value: string }[] };
        expect(ops.options().map((o) => o.value)).toEqual(['eq', 'ne', 'range']);
        pickerSet(fixture, '[data-condition="1"] [data-testid="condition-op"]', 'range');
        penEdit(fixture, el.querySelector<HTMLElement>('[data-condition="1"] [data-testid="condition-min"]')!, '100');

        // the sentence names the column by its new name
        penEdit(fixture, penByLabel(el, 'Column 5')!, 'via');
        expect(q(el, 'keeps-line')!.textContent!.trim()).toBe(
            'Keeps rows where via is mobile and amount is 100 or more.',
        );
        expect(el.querySelector('[data-testid="row-filter"] [role="alert"]')).toBeNull();
        await expectNoA11yViolations(el);

        const req = commit(fixture, el);
        expect(bodyOf(req).filter).toEqual([
            { column: 'channel', op: 'eq', value: 'mobile' },
            { column: 'amount', op: 'range', min: '100' },
        ]);
        req.flush(summary({ rowsFilteredOut: 15, filter: [{ column: 'via', op: 'eq', value: 'mobile' }] }));
        fixture.detectChanges();
        expect(el.textContent).toContain('15 rows were left out by the row filter.');
        expect(q(el, 'keeps-rows')!.textContent).toContain('Keeps rows where via is mobile');
    });

    it('ADD-DATA-ROW-FILTER-1: contains offers Match case, sent and said only when ticked (operator 2026-11-05)', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        (q(el, 'add-condition') as HTMLButtonElement).click();
        fixture.detectChanges();
        pickerSet(fixture, '[data-condition="0"] [data-testid="condition-column"]', 'channel');
        expect(q(el, 'condition-match-case')).toBeNull(); // "is" has no case switch
        pickerSet(fixture, '[data-condition="0"] [data-testid="condition-op"]', 'contains');
        penEdit(
            fixture,
            el.querySelector<HTMLElement>('[data-condition="0"] [data-testid="condition-value"]')!,
            'Agent',
        );
        expect(q(el, 'keeps-line')!.textContent!.trim()).toBe("Keeps rows where channel contains 'Agent'.");
        (el.querySelector('[data-testid="condition-match-case"] input') as HTMLInputElement).click();
        fixture.detectChanges();
        expect(q(el, 'keeps-line')!.textContent!.trim()).toBe(
            "Keeps rows where channel contains 'Agent' (exact case).",
        );
        const req = commit(fixture, el);
        expect(bodyOf(req).filter).toEqual([{ column: 'channel', op: 'contains', value: 'Agent', matchCase: true }]);
        req.flush(summary());
    });

    it('ADD-DATA-ROW-FILTER-1: leaving out a column a condition names holds Create back; removing the condition frees it', () => {
        const { fixture, el } = create();
        upload(fixture, el, transfersDraft());
        (q(el, 'add-condition') as HTMLButtonElement).click();
        fixture.detectChanges();
        pickerSet(fixture, '[data-condition="0"] [data-testid="condition-column"]', 'channel');
        penEdit(
            fixture,
            el.querySelector<HTMLElement>('[data-condition="0"] [data-testid="condition-value"]')!,
            'agent',
        );
        (el.querySelector('[data-column="channel"] [data-testid="drop-column"] input') as HTMLInputElement).click();
        fixture.detectChanges();
        expect(el.querySelector('[data-condition="0"] [role="alert"]')!.textContent).toContain('left out');
        expect((q(el, 'create') as HTMLButtonElement).disabled).toBe(true);
        (q(el, 'remove-condition') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(el.querySelector('[data-condition="0"]')).toBeNull();
        expect((q(el, 'create') as HTMLButtonElement).disabled).toBe(false);
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
