import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { InvService, WorkingSetRelation, WorkingSetRelationQuery } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { WorkingSetWidgetComponent } from './working-set-widget.component';

const PIN_HASH = 'sha256:pinned';

function relation(step: number, hash: string, ids: string[], truncated = false): WorkingSetRelation {
    return {
        id: 'case-a',
        relation: 'entities',
        columns: ['entityId', 'hop'],
        rows: ids.map((entityId, hop) => ({ entityId, hop })),
        total: ids.length,
        offset: 0,
        limit: 1000,
        truncated,
        head: { step, workingSetHash: hash },
        key: 'k',
        cached: false,
    };
}

const binding = (mode: 'frozen' | 'live', step = 4) => ({
    relation: 'entities',
    mode,
    pin: { step, workingSetHash: PIN_HASH, pinnedAt: '2026-09-23T10:00:00Z' },
});

/** One TestBed per test; `answer` stands in for the Investigation-scoped route and records every query. */
function create(answer: (q: WorkingSetRelationQuery) => Observable<WorkingSetRelation>) {
    const calls: WorkingSetRelationQuery[] = [];
    TestBed.configureTestingModule({
        imports: [WorkingSetWidgetComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: InvService,
                useValue: {
                    workingSetRelation: (_id: string, q: WorkingSetRelationQuery) => {
                        calls.push(q);
                        return answer(q);
                    },
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(WorkingSetWidgetComponent);
    return { fixture, calls };
}

function text(el: HTMLElement): string {
    return (el.textContent ?? '').replace(/\s+/g, ' ');
}

describe('WorkingSetWidgetComponent', () => {
    it('Frozen: reads ONLY the pinned step, states its kind and pin, and lists the pinned rows', async () => {
        const { fixture, calls } = create(() => of(relation(4, PIN_HASH, ['alice', 'carol', 'frank'])));
        fixture.componentRef.setInput('viewId', 'case-a');
        fixture.componentRef.setInput('binding', binding('frozen'));
        fixture.detectChanges();

        const t = text(fixture.nativeElement);
        expect(t).toContain('Frozen');
        expect(t).not.toContain('Live');
        expect(t).toContain('Frozen at step 4');
        expect(t).toContain('it never moves');
        expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(3);
        expect(t).toContain('frank');
        expect(calls.length).toBe(1);
        expect(calls[0]).toMatchObject({ of: 'entities', at: 4 });
        expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull(); // no drift line on a Frozen tile
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('Live: reads the head AND the pin, states its kind, and shows the drift since the pin', async () => {
        const { fixture, calls } = create((q) =>
            of(
                q.at === undefined
                    ? relation(6, 'sha256:head', ['alice', 'carol', 'dave', 'erin'])
                    : relation(4, PIN_HASH, ['alice', 'carol', 'frank']),
            ),
        );
        fixture.componentRef.setInput('viewId', 'case-a');
        fixture.componentRef.setInput('binding', binding('live'));
        fixture.detectChanges();

        const t = text(fixture.nativeElement);
        expect(t).toContain('Live');
        expect(t).toContain('Live at step 6 · pinned at step 4');
        expect(fixture.nativeElement.querySelector('[role="status"]').textContent.trim()).toBe(
            'Pinned step 4: 3 entities · now step 6: 4 · 2 added · 1 removed since the pin',
        );
        expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(4); // the HEAD's rows
        expect(calls.map((c) => c.at)).toEqual([4, undefined]);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('a viewer who is not the owner (404) sees an honest "not available to you" tile — never data', async () => {
        const { fixture } = create(() =>
            throwError(() => new HttpErrorResponse({ status: 404, statusText: 'Not Found' })),
        );
        fixture.componentRef.setInput('viewId', 'case-a');
        fixture.componentRef.setInput('binding', binding('live'));
        fixture.detectChanges();

        const t = text(fixture.nativeElement);
        expect(t).toContain('Not available to you');
        expect(t).toContain('only the Investigation’s owner can read');
        expect(fixture.nativeElement.querySelector('table')).toBeNull();
        expect(fixture.nativeElement.querySelector('inspecto-stat-tile')).toBeNull();
        expect(t).toContain('Live'); // the tile still states its kind
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('Frozen: a pin whose hash no longer matches shows a broken pin and withholds the rows', () => {
        const { fixture } = create(() => of(relation(4, 'sha256:rewritten', ['mallory'])));
        fixture.componentRef.setInput('viewId', 'case-a');
        fixture.componentRef.setInput('binding', binding('frozen'));
        fixture.detectChanges();

        const t = text(fixture.nativeElement);
        expect(t).toContain('The pin no longer holds');
        expect(t).not.toContain('mallory');
        expect(fixture.nativeElement.querySelector('table')).toBeNull();
    });

    it('a pinned step past the head (422) says the pin is gone; no binding says how to make one', () => {
        const { fixture } = create(() => throwError(() => new HttpErrorResponse({ status: 422 })));
        fixture.componentRef.setInput('viewId', 'case-a');
        fixture.componentRef.setInput('binding', binding('frozen', 9));
        fixture.detectChanges();
        expect(text(fixture.nativeElement)).toContain('The pinned step (9) is no longer in the Investigation’s log.');

        fixture.componentRef.setInput('binding', { relation: 'entities', mode: 'sometimes' });
        fixture.detectChanges();
        expect(text(fixture.nativeElement)).toContain('has no Working Set binding');
    });
});
