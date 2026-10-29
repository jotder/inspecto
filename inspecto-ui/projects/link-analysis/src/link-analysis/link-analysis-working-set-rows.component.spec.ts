import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { InvService, WorkingSetRelation, WorkingSetRelationQuery } from '@inspecto/link-analysis/api/inv.service';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from '@inspecto/core/grid';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    LinkAnalysisWorkingSetRowsComponent,
    WORKING_SET_PAGE,
    expandedRow,
    hubRow,
} from './link-analysis-working-set-rows.component';

describe('expandedRow (LA-UNEXPANDED-MARKER-1)', () => {
    it('words the flag and leaves other relations and an older server untouched', () => {
        expect(expandedRow({ entityId: 'a', expanded: true })['expanded']).toBe('Yes');
        expect(expandedRow({ entityId: 'b', expanded: false })['expanded']).toBe('Not yet');
        const link = { source: 'a', target: 'b' };
        expect(expandedRow(link)).toBe(link);
    });
});

describe('hubRow (telecom S3)', () => {
    it('walk-through 6 D1: names the threshold and the override through the store-backed badge', () => {
        const badge = (id: string, contacts: number | null) => `${id}:${contacts}`;
        expect(hubRow({ entityId: 'h', highConnectivity: true, contacts: 628 }, badge)['highConnectivity']).toBe(
            'h:628',
        );
    });

    it('words an entities row flag as the badge, blanks an unflagged one, and passes other relations through', () => {
        expect(hubRow({ entityId: 'h', highConnectivity: true })['highConnectivity']).toBe(
            'Busy: not expanded',
        );
        expect(hubRow({ entityId: 'h', highConnectivity: true, contacts: 650 })['highConnectivity']).toBe(
            'Busy: 650 linked entities, not expanded',
        );
        expect(hubRow({ entityId: 'h', highConnectivity: true, contacts: null })['highConnectivity']).toBe(
            'Busy: not expanded',
        );
        expect(hubRow({ entityId: 'a', highConnectivity: false })['highConnectivity']).toBe('');
        const link = { source: 'a', target: 'b' };
        expect(hubRow(link)).toBe(link);
    });
});

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisWorkingSetRowsComponent],
    template: `<inspecto-link-analysis-working-set-rows
        investigationId="inv-1"
    ></inspecto-link-analysis-working-set-rows>`,
})
class Host {}

function page(q: WorkingSetRelationQuery, n: number, total: number): WorkingSetRelation {
    const offset = q.offset ?? 0;
    return {
        id: 'inv-1',
        relation: q.of ?? 'entities',
        columns: ['entityId'],
        rows: Array.from({ length: n }, (_, i) => ({ entityId: `e${offset + i}` })),
        total,
        offset,
        limit: q.limit ?? 1000,
        truncated: offset + n < total,
        head: { step: 4, workingSetHash: 'sha256:h' },
        key: 'sha256:k',
        cached: true,
    };
}

async function create(relation?: (id: string, q: WorkingSetRelationQuery) => unknown) {
    const workingSetRelation = vi.fn(
        relation ?? ((_: string, q: WorkingSetRelationQuery) => of(page(q, q.offset ? 50 : WORKING_SET_PAGE, 250))),
    );
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: { workingSetRelation } },
            // the data-table's real theme service walks up to GAMMA_APP_CONFIG — stub it, as its own spec does
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    await f.whenStable();
    f.detectChanges();
    const c = f.debugElement.children[0].componentInstance as LinkAnalysisWorkingSetRowsComponent;
    return { f, c, el: f.nativeElement as HTMLElement, workingSetRelation };
}

describe('LinkAnalysisWorkingSetRowsComponent (LA-20)', () => {
    beforeEach(() => localStorage.clear());

    it('reads the first entities page and shows truncated, the head step and cached', async () => {
        const { c, el, workingSetRelation } = await create();
        expect(workingSetRelation).toHaveBeenCalledWith('inv-1', {
            of: 'entities',
            offset: 0,
            limit: WORKING_SET_PAGE,
        });
        expect(c.rows().length).toBe(WORKING_SET_PAGE);
        const status = el.querySelector('[aria-label="Relation status"]')!.textContent!;
        expect(status).toContain('200 of 250 rows');
        expect(status).toContain('at step 4');
        expect(status).toContain('cached');
        expect(el.querySelector('[aria-label="Relation status"] [class*="opacity"]'), 'opacity dims the status below 4.5:1 (LA-A11Y-AUDIT-1, 3.55:1 live)').toBeNull();
        expect(el.textContent).toContain('More rows exist');
        await expectNoA11yViolations(el);
    });

    it('Load more fetches the NEXT offset and appends', async () => {
        const { f, c, workingSetRelation } = await create();
        await c.loadMore();
        f.detectChanges();
        expect(workingSetRelation).toHaveBeenLastCalledWith('inv-1', {
            of: 'entities',
            offset: WORKING_SET_PAGE,
            limit: WORKING_SET_PAGE,
        });
        expect(c.rows().length).toBe(250);
        expect(c.rows()[200]).toEqual({ entityId: 'e200' });
        expect(c.page()!.truncated).toBe(false);
        await expectNoA11yViolations(f.nativeElement); // axe, LA-A11Y-AUDIT-1: the fully paged state
    });

    it('a relation switch refetches that relation from offset 0', async () => {
        const { f, c, workingSetRelation } = await create();
        c.relation.set('links');
        f.detectChanges();
        await f.whenStable();
        expect(workingSetRelation).toHaveBeenLastCalledWith('inv-1', {
            of: 'links',
            offset: 0,
            limit: WORKING_SET_PAGE,
        });
    });

    it('explains a 404 instead of rendering an empty grid', async () => {
        const { f, el } = await create(() =>
            throwError(() => new HttpErrorResponse({ status: 404, error: { error: 'no investigation' } })),
        );
        f.detectChanges();
        expect(el.textContent).toContain('not yours');
        expect(el.querySelector('inspecto-data-table')).toBeNull();
        await expectNoA11yViolations(el);
    });
});
