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
        r.flush({ items: [{ id: 'account', label: 'Account', classifications: ['ACCOUNT_ID'] }] }),
    );
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, c: fixture.componentInstance };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

describe('DataPageComponent - Datasets, Entities and Mappings (LA-DATA-PAGE-1)', () => {
    afterEach(() => {
        http?.match((r) => r.url.endsWith('/arrivals')).forEach((r) => !r.cancelled && r.flush({ arrivals: [] }));
        http?.verify();
    });

    it('lists each Add data Dataset with its Entities and Mappings, and the Pipeline-fed ones by name (no violations)', async () => {
        const { el } = create();
        expect(q(el, 'data-transfers')).not.toBeNull();
        expect(q(el, 'entities')!.textContent).toContain('from_account');
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
        c.form.patchValue({ name: '', to: 'from_account' });
        c.saveMapping();
        fixture.detectChanges();
        expect(c.form.controls.name.hasError('required')).toBe(true);
        expect(c.form.controls.to.hasError('same')).toBe(true);
        // the error renders in the field's error slot, after the submit only
        const slot = q(el, 'mapping-form')!.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error');
        expect(slot?.textContent).toContain('A name is required.');

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

    it('a Pipeline-fed Dataset gets its own Entities and Mappings, and a Mapping of it saves with it as the base', () => {
        const { fixture, el, c } = create();
        const pipe = q(el, 'data-wallet_txn')!;
        expect(pipe.querySelector('[data-testid="pipeline-card"]')!.textContent).toContain('Kept up to date by a Pipeline or a Job');
        expect(pipe.querySelector('inspecto-import-dataset-card')).toBeNull(); // no Add a file, Run now or arrivals
        expect(pipe.querySelector('[data-testid="entities"]')!.textContent).toContain('src_id');
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

    it('a Mapping that reads a column its base no longer lists is marked, naming the column', async () => {
        const { el } = create();
        const drift = q(el, 'data-wallet_txn')!.querySelector('[data-testid="mapping-drift"]');
        expect(drift?.textContent).toContain('agent_id');
        expect(drift?.textContent).not.toContain('src_id');
        expect(q(el, 'data-transfers')!.querySelector('[data-testid="mapping-drift"]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('a name a Dataset already has is refused', () => {
        const { fixture, el, c } = create();
        (q(el, 'mapping-new') as HTMLButtonElement).click();
        fixture.detectChanges();
        c.form.controls.name.setValue('mm_links');
        expect(c.form.controls.name.hasError('taken')).toBe(true);
    });
});
