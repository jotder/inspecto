import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { ParserRowsPreview } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ParserDerivedRowsComponent } from './parser-derived-rows.component';

const DERIVED: ParserRowsPreview = {
    kind: 'rows',
    segments: [
        {
            segment: 'Record',
            child: false,
            columns: ['ID', 'USAGE_COUNT', 'EVENT_TYPE'],
            derivedColumns: ['USAGE_COUNT'],
            rows: [{ ID: '5', USAGE_COUNT: '3', EVENT_TYPE: 'Record' }],
            rowCount: 2,
        },
        {
            segment: 'Usage',
            child: true,
            columns: ['PARENT_ID', 'IDX', 'EVENT_TYPE'],
            derivedColumns: [],
            rows: [{ PARENT_ID: '5', IDX: null, EVENT_TYPE: 'Usage' }],
            rowCount: 1,
        },
    ],
};

function create(derived: ParserRowsPreview | null, error: string | null = null) {
    TestBed.configureTestingModule({
        imports: [ParserDerivedRowsComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(ParserDerivedRowsComponent);
    fixture.componentRef.setInput('derived', derived);
    fixture.componentRef.setInput('error', error);
    fixture.detectChanges();
    return fixture;
}

describe('ParserDerivedRowsComponent', () => {
    it('renders one table per segment with its sample rows and the row counts', () => {
        const el: HTMLElement = create(DERIVED).nativeElement;
        expect(el.querySelector('[data-test="derived-segment-Record"]')).toBeTruthy();
        expect(el.querySelector('[data-test="derived-segment-Usage"]')).toBeTruthy();
        expect(el.textContent).toContain('1 of 2 rows');
        expect(el.querySelectorAll('tbody tr').length).toBe(2);
    });

    it('highlights exactly the derived columns, header and cells', () => {
        const el: HTMLElement = create(DERIVED).nativeElement;
        const marked = Array.from(el.querySelectorAll('[data-derived="true"]'));
        expect(marked.length).toBe(2); // the USAGE_COUNT header + its one cell
        expect(marked.every((n) => n.textContent?.includes('USAGE_COUNT') || n.textContent?.trim() === '3')).toBe(true);
        expect(el.querySelector('th[data-derived="true"] .sr-only')?.textContent).toContain('derived');
    });

    it('tags a child segment and prints NULL for a missing value', () => {
        const el: HTMLElement = create(DERIVED).nativeElement;
        const usage = el.querySelector('[data-test="derived-segment-Usage"]')!;
        expect(usage.textContent).toContain('child rows');
        expect(usage.textContent).toContain('NULL');
        expect(el.querySelector('[data-test="derived-segment-Record"]')!.textContent).not.toContain('child rows');
    });

    it('says so in one sentence when nothing is declared', () => {
        const el: HTMLElement = create(null).nativeElement;
        expect(el.querySelector('[data-test="derived-empty"]')?.textContent).toContain('declares no derived columns');
        expect(el.querySelector('table')).toBeNull();
    });

    it('shows the server reason instead of the empty sentence when the rows could not be built', () => {
        const el: HTMLElement = create(null, 'segment schema for Usage not found').nativeElement;
        expect(el.querySelector('[data-test="derived-error"]')?.textContent).toContain('not found');
        expect(el.querySelector('[data-test="derived-empty"]')).toBeNull();
    });

    it('has no a11y violations (tables and the empty state)', async () => {
        await expectNoA11yViolations(create(DERIVED).nativeElement);
        TestBed.resetTestingModule();
        await expectNoA11yViolations(create(null).nativeElement);
    });
});
