import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { LookupRow, LookupsPage, RunsService } from 'app/inspecto/api';
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

async function create(page: LookupsPage | Error) {
    const toastr = { error: vi.fn() };
    const api = {
        lookups: vi.fn(() => (page instanceof Error ? throwError(() => page) : of(page))),
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
    return { fixture, toastr };
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

    it('degrades to an empty list and a toast when the load fails', async () => {
        const { fixture, toastr } = await create(new Error('boom'));
        expect(fixture.componentInstance.rows()).toEqual([]);
        expect(toastr.error).toHaveBeenCalled();
    });
});
