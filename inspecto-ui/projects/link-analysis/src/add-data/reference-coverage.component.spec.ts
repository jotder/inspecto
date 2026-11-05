import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { apiUrl } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { ReferenceCoverage, ReferenceCoverageComponent, coverageLines } from './reference-coverage.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [ReferenceCoverageComponent],
    template: `<inspecto-la-reference-coverage [dataset]="dataset()" [refresh]="refresh()" />`,
})
class HostComponent {
    readonly dataset = signal('accounts');
    readonly refresh = signal(0);
}

const COVERAGE: ReferenceCoverage = {
    dataset: 'accounts',
    entityType: 'account',
    entityTypeLabel: 'Account',
    identifyingColumn: 'account_id',
    described: 312,
    datasets: [{ dataset: 'transfers', columns: ['from_account', 'to_account'], appear: 295, absent: 17 }],
    truncated: false,
};

describe('reference coverage (LA-REFERENCE-DETAILS-MORE-1)', () => {
    it('says how many entities the Reference describes and how many appear in each Dataset', () => {
        expect(coverageLines(COVERAGE)).toEqual(['312 Account entities described.', '295 appear in transfers; 17 do not.']);
        expect(coverageLines({ ...COVERAGE, truncated: true })[0]).toBe(
            '312 Account entities described (counted over part of the rows).',
        );
        expect(coverageLines({ ...COVERAGE, datasets: [] })).toEqual([
            '312 Account entities described; no other Dataset holds Account entities yet.',
        ]);
        expect(coverageLines({ ...COVERAGE, entityTypeLabel: undefined })[0]).toBe('312 account entities described.');
        expect(coverageLines({ ...COVERAGE, described: null, reason: 'not a Reference' })).toEqual([]);
        expect(coverageLines(null)).toEqual([]);
    });

    it('reads the counts, re-reads on refresh, and shows nothing when the read fails', async () => {
        TestBed.configureTestingModule({
            imports: [HostComponent],
            providers: [provideHttpClient(), provideHttpClientTesting()],
        });
        const http = TestBed.inject(HttpTestingController);
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        http.expectOne(apiUrl('/datasets/accounts/coverage')).flush(COVERAGE);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="reference-coverage"]')!.textContent).toContain('295 appear in transfers; 17 do not.');
        await expectNoA11yViolations(el);

        fixture.componentInstance.refresh.set(1);
        fixture.detectChanges();
        http.expectOne(apiUrl('/datasets/accounts/coverage')).flush({ error: { message: 'no' } }, { status: 500, statusText: 'x' });
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="reference-coverage"]')).toBeNull();
        http.verify();
    });
});
