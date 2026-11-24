import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { LookupRow, LookupsPage, LookupTestAnswer, RunsService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { LookupsComponent } from './lookups.component';
import { toGridRow } from './lookups-view';

const ROW: LookupRow = {
    pipeline: 'cdr',
    step: 'join',
    reference: 'reference/dial_digit',
    match: 'longest_prefix',
    lookedUpBy: ['SERVED_MSISDN', 'OTHER_MSISDN'],
    lastRun: {
        consignmentId: 'c-1',
        outcome: 'OK',
        startedAt: '2026-11-23T10:15:00.000Z',
        rowsIn: 10,
        counts: {
            'lookup.SERVED_MSISDN.hit': 8,
            'lookup.SERVED_MSISDN.miss': 2,
            'lookup.OTHER_MSISDN.hit': 9,
            'lookup.OTHER_MSISDN.miss': 1,
            'reference.rows': 40,
            'reference.null_keys': 3,
        },
        truncated: false,
    },
};

const ANSWER: LookupTestAnswer = {
    pipeline: 'cdr',
    step: 'join',
    match: 'longest_prefix',
    compared: '249915555',
    hit: true,
    matchedKey: '24991',
    matchKind: 'prefix',
    mappings: [{ on: 'SERVED_MSISDN', columns: { SERVED_MSISDN_provider_desc: 'Operator A' } }],
    index: {
        rows: 2,
        bytes: 400,
        loadedAt: '2026-11-24T10:00:00Z',
        buildMs: 5,
        reloaded: 'reference changed',
        watchesReference: true,
    },
};

async function create(page: LookupsPage | Error, answer: LookupTestAnswer | Error = ANSWER) {
    const toastr = { error: vi.fn() };
    const api = {
        lookups: vi.fn(() => (page instanceof Error ? throwError(() => page) : of(page))),
        testLookup: vi.fn(() => (answer instanceof Error ? throwError(() => answer) : of(answer))),
    } as unknown as RunsService;
    TestBed.configureTestingModule({
        imports: [LookupsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: RunsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: { open: vi.fn() } });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(LookupsComponent);
    fixture.detectChanges();
    return { fixture, toastr, api };
}

describe('toGridRow', () => {
    it('sums every mapping and reads the Reference counts', () => {
        const r = toGridRow(ROW);
        expect(r.hits).toBe(17);
        expect(r.misses).toBe(3);
        expect(r.missRate).toBeCloseTo(0.15);
        expect(r.referenceRows).toBe(40);
        expect(r.nullKeys).toBe(3);
        expect(r.lookedUpBy).toBe('SERVED_MSISDN, OTHER_MSISDN');
        expect(r.match).toBe('longest prefix');
    });

    it('shows no sums when the receipt cut its counts', () => {
        const r = toGridRow({ ...ROW, lastRun: { ...ROW.lastRun!, truncated: true } });
        expect([r.hits, r.misses, r.missRate]).toEqual([null, null, null]);
        expect(r.referenceRows).toBe(40);
    });

    it('reads the test index state, size and refusal', () => {
        expect(toGridRow(ROW).indexState).toBe('cold');
        const ready = toGridRow({ ...ROW, index: { state: 'ready', rows: 40, bytes: 2_097_152 } });
        expect([ready.indexState, ready.indexBytes]).toEqual(['ready', 2_097_152]);
        const failed = toGridRow({ ...ROW, index: { state: 'failed', error: 'over the cap' } });
        expect([failed.indexState, failed.indexError]).toEqual(['failed', 'over the cap']);
    });

    it('leaves the counts blank before the first run', () => {
        const r = toGridRow({ ...ROW, lastRun: null });
        expect([r.hits, r.misses, r.missRate, r.referenceRows, r.lastRunAt]).toEqual([null, null, null, null, null]);
    });
});

describe('LookupsComponent', () => {
    it('lists the lookups with no notice when receipts are on', async () => {
        const { fixture } = await create({ lookups: [ROW], total: 1, known: { lastRun: true } });
        expect(fixture.componentInstance.rows().length).toBe(1);
        expect(fixture.nativeElement.querySelector('inspecto-alert')).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('explains when receipts are off and when the list was cut', async () => {
        const { fixture } = await create({ lookups: [ROW], total: 600, known: { lastRun: false } });
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Step Receipts are switched off');
        expect(text).toContain('Showing the first 1 of 600 lookups');
    });

    it("shows a row's values not found, masked, when it is selected", async () => {
        const row = {
            ...ROW,
            unmatched: [
                {
                    mapping: 'SERVED_MSISDN',
                    fingerprint: 'fp:0123456789abcdef',
                    length: 12,
                    shape: '4479••••••••',
                    n: 5,
                },
            ],
        };
        const { fixture } = await create({ lookups: [row], total: 1, known: { lastRun: true } });
        expect(fixture.nativeElement.querySelector('#lookup-unmatched-title')).toBeNull();
        fixture.componentInstance.select(fixture.componentInstance.rows()[0]);
        fixture.detectChanges();
        await fixture.whenStable();
        expect(fixture.nativeElement.querySelector('#lookup-unmatched-title').textContent).toContain('cdr ▸ join');
        expect(fixture.componentInstance.selected()!.unmatched[0].shape).toBe('4479••••••••');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('refuses an empty value inline and tests a value through the selected lookup', async () => {
        const { fixture, api } = await create({ lookups: [ROW], total: 1, known: { lastRun: true } });
        const c = fixture.componentInstance;
        c.select(c.rows()[0]);
        fixture.detectChanges();
        await fixture.whenStable();
        expect(fixture.nativeElement.textContent).not.toContain('Enter a value');
        (fixture.nativeElement.querySelector('#lookup-test-title + p + form') as HTMLFormElement).dispatchEvent(
            new Event('submit'),
        );
        fixture.detectChanges();
        expect(api.testLookup).not.toHaveBeenCalled();
        expect(fixture.nativeElement.querySelector('inspecto-pen-field').textContent).toContain('Enter a value');

        c.testValue.setValue('249915555');
        c.test(true);
        fixture.detectChanges();
        expect(api.testLookup).toHaveBeenCalledWith('cdr', 'join', '249915555', true, null);
        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('Found: key 24991 (prefix)');
        expect(text).toContain('Operator A');
        expect(text).toContain('Index of 2 Reference rows');
        expect(text).toContain('the Reference changed');
        expect(text).not.toContain('not detected');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('asks for a time for an asof lookup and sends it', async () => {
        const asof: LookupRow = { ...ROW, match: 'asof', lookedUpBy: ['CODE'] };
        const { fixture, api } = await create(
            { lookups: [asof], total: 1, known: { lastRun: true } },
            {
                ...ANSWER,
                match: 'asof',
                matchedKey: 'A@2026-01-01',
                matchKind: 'asof',
            },
        );
        const c = fixture.componentInstance;
        c.select(c.rows()[0]);
        fixture.detectChanges();
        await fixture.whenStable();
        expect(fixture.nativeElement.textContent).toContain('Time');
        c.testValue.setValue('A');
        c.testAt.setValue('2026-02-15');
        c.test();
        fixture.detectChanges();
        expect(api.testLookup).toHaveBeenCalledWith('cdr', 'join', 'A', false, '2026-02-15');
        expect(fixture.nativeElement.textContent).toContain('Found: key A@2026-01-01 (effective date)');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows a refusal from the point engine inline', async () => {
        const { fixture } = await create(
            { lookups: [ROW], total: 1, known: { lastRun: true } },
            new Error('testing a value covers match: exact and longest_prefix so far, not range'),
        );
        const c = fixture.componentInstance;
        c.select(c.rows()[0]);
        c.testValue.setValue('3');
        c.test();
        fixture.detectChanges();
        expect(c.answer()).toBeNull();
        expect(fixture.nativeElement.querySelector('inspecto-alert').textContent).toBeTruthy();
        expect(c.testError()).toBeTruthy();
    });

    it('degrades to an empty list and a toast when the load fails', async () => {
        const { fixture, toastr } = await create(new Error('boom'));
        expect(fixture.componentInstance.rows()).toEqual([]);
        expect(toastr.error).toHaveBeenCalled();
    });
});
