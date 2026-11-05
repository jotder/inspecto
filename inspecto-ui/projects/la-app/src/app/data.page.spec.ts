import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';
import { apiUrl } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { DataPageComponent } from './data.page';

let http: HttpTestingController;

const DATASETS = [
    {
        type: 'dataset',
        name: 'transfers',
        ref: 'dataset/transfers',
        content: {
            origin: 'import',
            kind: 'stream',
            physicalRef: 'transfers/database',
            importPipeline: 'transfers',
            columns: [
                { name: 'from_account', type: 'string', classification: 'ACCOUNT_ID' },
                { name: 'to_account', type: 'string', classification: 'ACCOUNT_ID' },
                { name: 'channel', type: 'string' },
            ],
            defaultLinkMapping: { from: 'from_account', to: 'to_account' },
        },
    },
    {
        type: 'dataset',
        name: 'by_channel',
        ref: 'dataset/by_channel',
        content: {
            origin: 'mapping',
            basedOn: 'transfers',
            physicalRef: 'transfers/database',
            columns: [],
            defaultLinkMapping: { from: 'from_account', to: 'channel' },
        },
    },
    { type: 'dataset', name: 'mm_links', ref: 'dataset/mm_links', content: { physicalRef: 'mm/database' } },
    // LA-PIPELINE-MAPPINGS-1: a Pipeline-fed Dataset that lists its columns may be a Mapping's base
    {
        type: 'dataset',
        name: 'wallet_txn',
        ref: 'dataset/wallet_txn',
        content: {
            physicalRef: 'wallet_txn/database',
            columns: [
                { name: 'src_id', type: 'string', classification: 'ACCOUNT_ID' },
                { name: 'dst_id', type: 'string', classification: 'ACCOUNT_ID' },
                { name: 'txn_date', type: 'date' },
            ],
        },
    },
    // ... and one computed when read may not
    { type: 'dataset', name: 'computed_ds', ref: 'dataset/computed_ds', content: { sql: 'SELECT 1', columns: [] } },
    {
        type: 'dataset',
        name: 'by_agent',
        ref: 'dataset/by_agent',
        content: {
            origin: 'mapping',
            basedOn: 'wallet_txn',
            physicalRef: 'wallet_txn/database',
            columns: [],
            defaultLinkMapping: { from: 'src_id', to: 'agent_id', time: 'txn_date' },
        },
    },
];

function create() {
    TestBed.configureTestingModule({
        imports: [DataPageComponent],
        providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideNoopAnimations()],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(DataPageComponent);
    fixture.detectChanges();
    http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    http.match((r) => r.url.includes('/entity-types')).forEach((r) =>
        r.flush({
            items: [
                {
                    id: 'account',
                    label: 'Account',
                    classifications: ['ACCOUNT_ID'],
                    family: 'account',
                    definition: 'An account a party holds.',
                    example: 'ACC-1',
                },
            ],
            families: [{ id: 'account', label: 'Account', definition: 'Holds a balance.' }],
        }),
    );
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, c: fixture.componentInstance };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

describe('DataPageComponent - Datasets, Entities and Mappings (LA-DATA-PAGE-1)', () => {
    afterEach(() => {
        http?.match((r) => r.url.endsWith('/arrivals')).forEach((r) => !r.cancelled && r.flush({ arrivals: [] }));
        // ENTITY-TYPE-RECORD-ROLES-1: each base's shape marks are read once per page
        http
            ?.match((r) => r.url.endsWith('/shapes'))
            .forEach((r) => !r.cancelled && r.flush({ sampled: 0, columns: {} }));
        http?.verify();
    });

    it('lists each Add data Dataset with its Entities and Mappings, and the Pipeline-fed ones by name (no violations)', async () => {
        const { el } = create();
        expect(q(el, 'data-transfers')).not.toBeNull();
        expect(q(el, 'columns')!.textContent).toContain('from_account');
        // LA-BASIC-DISCOVER-1: each Dataset can be discovered here, read only when asked
        expect(q(el, 'discover-transfers')?.querySelector('[data-testid="la-discover-run"]')).not.toBeNull();
        const maps = q(el, 'mappings')!.textContent ?? '';
        expect(maps).toContain('from_account → to_account');
        expect(q(el, 'mapping-by_channel')!.textContent).toContain('from_account → channel');
        const others = el.querySelector('[aria-labelledby="data-others"]')!.textContent ?? '';
        expect(others).toContain('mm_links');
        expect(others).toContain('computed_ds');
        expect(others).not.toContain('wallet_txn');
        await expectNoA11yViolations(el);
    });

    it('New mapping refuses an empty name and the same From and To, then saves a Mapping of the base', () => {
        const { fixture, el, c } = create();
        (q(el, 'mapping-new') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(q(el, 'mapping-form')).not.toBeNull();
        expect(q(el, 'mapping-form')!.querySelector('[role="alert"]')).toBeNull(); // nothing shows before a save
        c.form.patchValue({ name: '', to: 'from_account' });
        c.saveMapping();
        fixture.detectChanges();
        expect(c.form.controls.name.hasError('required')).toBe(true);
        expect(c.form.controls.to.hasError('same')).toBe(true);
        // the errors render as the rows' own alert lines, after the submit only
        const alerts = Array.from(q(el, 'mapping-form')!.querySelectorAll('[role="alert"]')).map(
            (a) => a.textContent ?? '',
        );
        expect(alerts.some((t) => t.includes('A name is required.'))).toBe(true);
        expect(alerts.some((t) => t.includes('From and To must be two different columns.'))).toBe(true);
        // property rows, not boxed Material fields
        expect(q(el, 'mapping-form')!.querySelector('mat-form-field, mat-select')).toBeNull();

        c.form.patchValue({ name: 'by_account_channel', to: 'channel' });
        c.form.controls.name.updateValueAndValidity();
        c.saveMapping();
        const req = http.expectOne((r) => r.method === 'POST' && r.url === apiUrl('/components/dataset'));
        const body = req.request.body as Record<string, unknown>;
        expect(body['id']).toBe('by_account_channel');
        expect(body['origin']).toBe('mapping');
        expect(body['basedOn']).toBe('transfers');
        expect(body['physicalRef']).toBe('transfers/database');
        expect(body['defaultLinkMapping']).toEqual({ from: 'from_account', to: 'channel' });
        req.flush({ name: 'by_account_channel' });
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
        fixture.detectChanges();
        expect(c.editing()).toBeNull();
    });

    it('LA-COLUMN-ROLES-1 P1: a Mapping picks its primary Time among the Time columns, and saves it as primaryTime', () => {
        const { fixture, el, c } = create();
        const base = (c as unknown as { bases: () => { id: string }[] }).bases().find((b) => b.id === 'wallet_txn')!;
        const noneOr = (role: string | null) =>
            (c as unknown as { noneOr: (d: unknown, r: string | null) => { value: string }[] })
                .noneOr(base, role)
                .map((o) => o.value);
        expect(noneOr('temporal')).toEqual(['', 'txn_date']); // a date column reads as a Time (no role stored)
        expect(noneOr('measure')).toEqual(['']);
        expect(noneOr(null)).toEqual(['', 'src_id', 'dst_id', 'txn_date']);
        (q(el, 'mapping-new') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(q(el, 'mapping-primaryTime')).not.toBeNull();
    });

    it('Edit keeps the Mapping name and saves by replacing the entry', () => {
        const { fixture, el, c } = create();
        expect(q(el, 'mapping-form')).toBeNull(); // nothing open, so no error shows before a submit
        const base = c.imports()[0];
        c.editMapping(base, c.mappingsOf('transfers')[0]);
        fixture.detectChanges();
        expect(c.form.controls.name.disabled).toBe(true);
        expect(c.form.getRawValue().to).toBe('channel');
        c.form.patchValue({ to: 'to_account' });
        c.saveMapping();
        const req = http.expectOne((r) => r.method === 'PUT' && r.url === apiUrl('/components/dataset/by_channel'));
        expect((req.request.body as Record<string, unknown>)['defaultLinkMapping']).toEqual({
            from: 'from_account',
            to: 'to_account',
        });
        req.flush({ name: 'by_channel' });
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    });

    it('Save with no From and no To says so on both rows, and sends nothing', () => {
        const { fixture, el, c } = create();
        c.newMapping(c.bases().find((d) => d.id === 'wallet_txn')!); // a base with no link mapping: From and To start empty
        fixture.detectChanges();
        c.form.patchValue({ name: 'abc' });
        c.saveMapping();
        fixture.detectChanges();
        const alerts = Array.from(q(el, 'mapping-form')!.querySelectorAll('[role="alert"]')).map(
            (a) => a.textContent ?? '',
        );
        expect(alerts.some((t) => t.includes('Choose the column each link starts from.'))).toBe(true);
        expect(alerts.some((t) => t.includes('Choose the column each link goes to.'))).toBe(true);
        http.expectNone((r) => r.method === 'POST');
    });

    it('a Pipeline-fed Dataset gets its own Entities and Mappings, and a Mapping of it saves with it as the base', () => {
        const { fixture, el, c } = create();
        const pipe = q(el, 'data-wallet_txn')!;
        expect(pipe.querySelector('[data-testid="pipeline-card"]')!.textContent).toContain(
            'Kept up to date by a Pipeline or a Job',
        );
        expect(pipe.querySelector('inspecto-import-dataset-card')).toBeNull(); // no Add a file, Run now or arrivals
        expect(pipe.querySelector('[data-testid="columns"]')!.textContent).toContain('src_id');
        expect(pipe.querySelector('[data-testid="mapping-by_agent"]')).not.toBeNull();
        expect(c.bases().map((d) => d.id)).toEqual(['transfers', 'wallet_txn']);

        (pipe.querySelector('[data-testid="mapping-new"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        c.form.patchValue({ name: 'wallet_to_wallet', from: 'src_id', to: 'dst_id' });
        c.form.controls.name.updateValueAndValidity();
        c.saveMapping();
        const req = http.expectOne((r) => r.method === 'POST' && r.url === apiUrl('/components/dataset'));
        const body = req.request.body as Record<string, unknown>;
        expect(body['basedOn']).toBe('wallet_txn');
        expect(body['physicalRef']).toBe('wallet_txn/database');
        req.flush({ name: 'wallet_to_wallet' });
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    });

    it('picking From or To starts its "holds" at what the base says; Edit keeps what the Mapping holds', () => {
        const { c } = create();
        c.newMapping(c.bases().find((d) => d.id === 'wallet_txn')!);
        expect(c.form.getRawValue().fromType).toBe(''); // the base has no link mapping of its own yet
        c.form.controls.from.setValue('src_id');
        c.form.controls.to.setValue('txn_date');
        expect(c.form.getRawValue().fromType).toBe('account'); // src_id holds ACCOUNT_ID in the base
        expect(c.form.getRawValue().toType).toBe(''); // a detail
        c.form.controls.toType.setValue('account'); // the user may still change it
        expect(c.form.getRawValue().toType).toBe('account');

        // an existing Mapping that reads to_account as nothing keeps that on Edit, though the base classifies it
        c.editMapping(c.imports()[0], {
            ...c.mappingsOf('transfers')[0],
            columns: [],
            mapping: { from: 'from_account', to: 'to_account' },
        });
        expect(c.form.getRawValue().toType).toBe('');
    });

    it('a Mapping that reads a column its base no longer lists is marked, naming the column', async () => {
        const { el } = create();
        const drift = q(el, 'data-wallet_txn')!.querySelector('[data-testid="mapping-drift"]');
        expect(drift?.textContent).toContain('agent_id');
        expect(drift?.textContent).not.toContain('src_id');
        expect(q(el, 'data-transfers')!.querySelector('[data-testid="mapping-drift"]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    const put = (column: string) =>
        http.expectOne((r) => r.method === 'PUT' && r.url === apiUrl(`/datasets/transfers/columns/${column}`));

    it('LA-COLUMN-ROLES-1: the Columns card is a role row per column, with Holds under an Entity, read for old entries', () => {
        const { el } = create();
        const card = q(el, 'data-transfers')!.querySelector('[data-testid="columns"]')!;
        expect(card.querySelector('h3')!.textContent).toBe('Columns');
        expect(card.querySelector('mat-form-field, mat-select')).toBeNull();
        // an entry without roles: a column holding an Entity Type is an Entity, any other a Detail
        expect(card.querySelector('[data-testid="role-from_account"]')!.textContent).toContain('Entity');
        expect(card.querySelector('[data-testid="entity-from_account"]')!.textContent).toContain('Account');
        expect(card.querySelector('[data-testid="role-channel"]')!.textContent).toContain('Detail');
        expect(card.querySelector('[data-testid="entity-channel"]')).toBeNull();
        // three role rows, and a Holds row for each of the two Entity columns
        expect(card.querySelectorAll('inspecto-option-picker').length).toBe(5);
    });

    it('a role other than Entity saves at once, and an Entity column that becomes one stops holding its type', () => {
        const { c } = create();
        c.roles().get('transfers')!.get('channel')!.setValue('dimension');
        let req = put('channel');
        expect(req.request.body).toEqual({ role: 'dimension' });
        req.flush({});
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);

        c.roles().get('transfers')!.get('to_account')!.setValue('temporal');
        req = put('to_account');
        expect(req.request.body).toEqual({ role: 'temporal', entityType: null });
        req.flush({});
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    });

    it('Entity waits for what the column holds, saying so, then saves both in one write', () => {
        const { fixture, el, c } = create();
        c.roles().get('transfers')!.get('channel')!.setValue('entity');
        fixture.detectChanges();
        http.expectNone((r) => r.method === 'PUT');
        const row = el.querySelector('[data-testid="column-channel"]')!;
        expect(row.querySelector('[data-testid="entity-channel"]')).not.toBeNull();
        expect(row.querySelector('[role="alert"]')!.textContent).toContain('Pick the Entity Type it holds');
        c.holds().get('transfers')!.get('channel')!.setValue('account');
        const req = put('channel');
        expect(req.request.body).toEqual({ entityType: 'account', role: 'entity' });
        req.flush({});
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    });

    it('a Measure has a Unit row, and a unit saves with the role', () => {
        const { fixture, el, c } = create();
        c.roles().get('transfers')!.get('channel')!.setValue('measure');
        put('channel').flush({});
        const measured = structuredClone(DATASETS);
        (measured[0].content.columns as Record<string, unknown>[])[2] = {
            name: 'channel',
            type: 'string',
            role: 'measure',
        };
        http.expectOne(apiUrl('/components/dataset')).flush(measured);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="unit-channel"]')).not.toBeNull();
        c.units().get('transfers')!.get('channel')!.setValue(' USD ');
        const req = put('channel');
        expect(req.request.body).toEqual({ role: 'measure', unit: 'USD' });
        req.flush({});
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    });

    it('LA-COLUMN-ROLES-1 P2: a Time, Measure or Category has an "On each link" row; Not carried saves carried: false', () => {
        const { fixture, el, c } = create();
        expect(el.querySelector('[data-testid="carried-channel"]')).toBeNull(); // a Detail rides along anyway
        c.roles().get('transfers')!.get('channel')!.setValue('dimension');
        put('channel').flush({});
        const typed = structuredClone(DATASETS);
        (typed[0].content.columns as Record<string, unknown>[])[2] = {
            name: 'channel',
            type: 'string',
            role: 'dimension',
        };
        http.expectOne(apiUrl('/components/dataset')).flush(typed);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="carried-channel"]')!.textContent).toContain('Carried');
        c.carried().get('transfers')!.get('channel')!.setValue('no');
        const req = put('channel');
        expect(req.request.body).toEqual({ carried: false });
        req.flush({});
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    });

    it('a refused change says why and reads the stored value back', () => {
        const { fixture, el, c } = create();
        c.roles().get('transfers')!.get('channel')!.setValue('measure');
        put('channel').flush(
            { error: { message: 'column channel has role x' } },
            { status: 422, statusText: 'Unprocessable Entity' },
        );
        // nothing was written, so the read back may come from the short-read cache rather than a new request
        http.match(apiUrl('/components/dataset')).forEach((r) => r.flush(DATASETS));
        fixture.detectChanges();
        expect(el.querySelector('inspecto-alert')).not.toBeNull();
        expect(c.roles().get('transfers')!.get('channel')!.value).toBe('detail');
    });

    it('a name a Dataset already has is refused', () => {
        const { fixture, el, c } = create();
        (q(el, 'mapping-new') as HTMLButtonElement).click();
        fixture.detectChanges();
        c.form.controls.name.setValue('mm_links');
        expect(c.form.controls.name.hasError('taken')).toBe(true);
    });

    it('offers what a column holds grouped by family, with a definition and an example (ENTITY-TYPE-CATALOG-1)', () => {
        const { c, el } = create();
        expect((c as unknown as { typeOptions: () => unknown[] }).typeOptions()).toEqual([
            { value: '', label: 'Nothing: a detail' },
            { value: 'account', label: 'Account', group: 'Account', hint: 'An account a party holds. e.g. ACC-1' },
        ]);
        expect(q(el, 'record-type')).toBeNull(); // no record type in force: the question is not asked
    });

    it('asks what a row is when the Space has record types, puts its likely types first and saves a change (ENTITY-TYPE-CATALOG-1 P2)', async () => {
        TestBed.configureTestingModule({
            imports: [DataPageComponent],
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideNoopAnimations()],
        });
        http = TestBed.inject(HttpTestingController);
        const fixture = TestBed.createComponent(DataPageComponent);
        fixture.detectChanges();
        const typed = DATASETS.map((d) =>
            d.name === 'transfers' ? { ...d, content: { ...d.content, recordType: 'transfer' } } : d,
        );
        http.expectOne(apiUrl('/components/dataset')).flush(typed);
        http.match((r) => r.url.includes('/entity-types')).forEach((r) =>
            r.flush({
                items: [
                    { id: 'account', label: 'Account', classifications: ['ACCOUNT_ID'], family: 'account' },
                    { id: 'phone', label: 'Phone number', classifications: ['PHONE'], family: 'identifier' },
                ],
                families: [
                    { id: 'account', label: 'Account', definition: 'Holds a balance.' },
                    { id: 'identifier', label: 'Identifier', definition: 'A number.' },
                ],
                recordTypes: [{ id: 'transfer', label: 'Transfer', definition: 'Money sent.', likelyTypes: ['phone'] }],
            }),
        );
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const c = fixture.componentInstance as unknown as {
            holdsOptions: () => ReadonlyMap<string, { value: string; group?: string }[]>;
            recordTypeControls: () => ReadonlyMap<string, { value: string; setValue(v: string): void }>;
        };

        const card = el.querySelector<HTMLElement>('[data-testid="columns"]')!;
        expect(card.querySelector('[data-testid="record-type"]')).not.toBeNull();
        expect(c.recordTypeControls().get('transfers')!.value).toBe('transfer');
        expect(
            c
                .holdsOptions()
                .get('transfers/from_account')!
                .map((o) => [o.group, o.value]),
        ).toEqual([
            [undefined, ''],
            ['Likely in a transfer', 'phone'],
            ['Account', 'account'],
        ]);
        // a base with no record type keeps the family order
        expect(c.holdsOptions().get('wallet_txn/src_id')![1].group).toBe('Account');
        await expectNoA11yViolations(el);

        c.recordTypeControls().get('transfers')!.setValue('');
        const req = http.expectOne((r) => r.method === 'PUT' && r.url === apiUrl('/datasets/transfers/record-type'));
        expect(req.request.body).toEqual({ recordType: null });
        req.flush({ dataset: 'transfers', recordType: null });
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    });

    it("marks the types whose shape a column fits, read on the server, and proposes the record type's usual roles (ENTITY-TYPE-RECORD-ROLES-1)", async () => {
        TestBed.configureTestingModule({
            imports: [DataPageComponent],
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideNoopAnimations()],
        });
        http = TestBed.inject(HttpTestingController);
        const fixture = TestBed.createComponent(DataPageComponent);
        fixture.detectChanges();
        const typed = DATASETS.map((d) =>
            d.name === 'transfers'
                ? {
                      ...d,
                      content: {
                          ...d.content,
                          recordType: 'transfer',
                          columns: [
                              ...d.content.columns,
                              { name: 'amount', type: 'number', role: 'detail' },
                              { name: 'booked_at', type: 'timestamp' },
                              { name: 'status', type: 'string', role: 'dimension' },
                          ],
                      },
                  }
                : d,
        );
        http.expectOne(apiUrl('/components/dataset')).flush(typed);
        http.match((r) => r.url.includes('/entity-types')).forEach((r) =>
            r.flush({
                items: [
                    { id: 'account', label: 'Account', classifications: ['ACCOUNT_ID'], family: 'account' },
                    { id: 'phone', label: 'Phone number', classifications: ['PHONE'], family: 'identifier' },
                ],
                families: [
                    { id: 'account', label: 'Account', definition: '' },
                    { id: 'identifier', label: 'Identifier', definition: '' },
                ],
                recordTypes: [
                    {
                        id: 'transfer',
                        label: 'Transfer',
                        likelyTypes: [],
                        roleHints: { time: ['booked'], measure: ['amount'], category: ['channel', 'status'] },
                    },
                ],
            }),
        );
        http.expectOne(apiUrl('/inv/datasets/transfers/shapes')).flush({
            dataset: 'transfers',
            sampled: 3,
            columns: { from_account: [{ type: 'phone', strong: true }] },
        });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const c = fixture.componentInstance as unknown as {
            holdsOptions: () => ReadonlyMap<string, { value: string; hint?: string }[]>;
        };
        const from = c.holdsOptions().get('transfers/from_account')!;
        expect(from.find((o) => o.value === 'phone')?.hint).toContain('Shape matches.');
        expect(from.find((o) => o.value === 'account')?.hint ?? '').not.toContain('Shape matches.');
        expect(
            c
                .holdsOptions()
                .get('transfers/to_account')!
                .some((o) => (o.hint ?? '').includes('Shape matches.')),
        ).toBe(false);

        // a Detail and an unstated role are proposed; a role the person set (status), the mapping's ends and a role the column already reads as are not
        const line = q(el, 'record-roles')!;
        expect(line.textContent).toContain('A transfer usually has');
        expect(line.textContent).toContain('channel');
        expect(line.textContent).toContain('amount');
        expect(line.textContent).not.toContain('booked_at'); // a timestamp already reads as a Time
        expect(line.textContent).not.toContain('status');
        await expectNoA11yViolations(el);

        (q(el, 'record-roles-apply') as HTMLButtonElement).click();
        const puts = http.match((r) => r.method === 'PUT' && r.url.startsWith(apiUrl('/datasets/transfers/columns/')));
        expect(puts.map((r) => [r.request.url.split('/').pop(), r.request.body.role])).toEqual([
            ['channel', 'dimension'],
            ['amount', 'measure'],
        ]);
        puts.forEach((r) => r.flush({}));
        http.expectOne(apiUrl('/components/dataset')).flush(typed);
    });

    it('LA-COLUMN-ROLES-2: a Mapping keeps a column off its own links, whatever its base says', async () => {
        const { fixture, el, c } = create();
        const page = c as unknown as { setCarried(col: string, v: string): void; bases(): { id: string }[] };
        c.newMapping(c.bases().find((d) => d.id === 'wallet_txn')!);
        fixture.detectChanges();
        await fixture.whenStable(); // ngModel writes its value a tick later
        fixture.detectChanges();
        // txn_date is a Time on the base (a date column), so this Mapping may keep it off its links; an end may not
        expect(q(el, 'mapping-carried-txn_date')!.textContent).toContain('Carried');
        expect(q(el, 'mapping-carried-src_id')).toBeNull();
        page.setCarried('txn_date', 'no');
        c.form.patchValue({ name: 'by_dst', from: 'src_id', to: 'dst_id' });
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(q(el, 'mapping-carried-txn_date')!.textContent).toContain('Not carried');
        c.saveMapping();
        const req = http.expectOne((r) => r.method === 'POST' && r.url.includes('/components/dataset'));
        const cols = req.request.body as { content?: unknown; columns?: unknown } as Record<string, unknown>;
        const written = JSON.stringify(cols);
        expect(written).toContain('"name":"txn_date"');
        expect(written).toMatch(/"name":"txn_date"[^}]*"carried":false/);
        req.flush({});
        http.expectOne(apiUrl('/components/dataset')).flush(DATASETS);
    });
});
