import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, InvestigationLog, WorkingSet } from 'app/inspecto/api';
import { EntityProjection } from 'app/inspecto/graph';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisInvestigationComponent } from './link-analysis-investigation.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { WidgetsService } from '../widgets/widgets.service';
import { Widget } from '../widgets/widget-types';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisInvestigationComponent],
    template: `<inspecto-link-analysis-investigation
        [projection]="projection()"
        projectionIssue="Run a query first."
    ></inspecto-link-analysis-investigation>`,
})
class Host {
    readonly projection = signal<EntityProjection | null>({ datasetId: 'calls', sourceCol: 'A', targetCol: 'B' });
}

const LOG: InvestigationLog = {
    header: {
        id: 'inv-1',
        title: 'Burners',
        owner: 'alice',
        dataset: 'calls',
        sourceCol: 'A',
        targetCol: 'B',
        linkKindCol: null,
        createdAt: '',
        datasetVersion: null,
        parent: null,
    },
    entries: [
        {
            step: 1,
            kind: 'op',
            op: 'seed',
            params: { ids: ['a'] },
            author: 'alice',
            at: '2026-09-23T10:00:00Z',
            undoneBy: null,
            workingSetHash: '',
            text: '1. Seeded 1 entity: a.',
        },
        {
            step: 2,
            kind: 'op',
            op: 'expand',
            params: { ids: ['a'] },
            author: 'alice',
            at: '2026-09-23T10:01:00Z',
            undoneBy: null,
            workingSetHash: '',
            text: '2. Expanded one hop.',
        },
    ],
    total: 2,
    truncated: false,
};

const WS: WorkingSet = {
    entities: [{ id: 'a', type: null, hop: 0, seed: 'a', admittedBy: 1, hidden: false, kept: false }],
    links: [],
    excluded: [],
    hash: '',
};

function create() {
    const inv = {
        createInvestigation: vi.fn(() => of(LOG.header)),
        investigationLog: vi.fn(() => of(LOG)),
        replayInvestigation: vi.fn(() => of({ workingSet: WS })),
        appendInvestigationOp: vi.fn(() => of({})),
        undoInvestigation: vi.fn(() => of({})),
        reorderInvestigation: vi.fn(() =>
            of({ id: 'inv-fork', parent: { id: 'inv-1', order: [2, 1], parentSteps: 2 } }),
        ),
        workingSetRelation: vi.fn(() =>
            of({ head: { step: 2, workingSetHash: 'sha256:h2' }, rows: [], total: 0, truncated: false, cached: false }),
        ),
        investigationMeasures: vi.fn(() => of({ head: { step: 2, workingSetHash: 'sha256:h2' }, measures: [] })),
    };
    const widgets = { save: vi.fn((w: Widget) => of(w)) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            InvestigationSessionStore,
            { provide: InvService, useValue: inv },
            { provide: WidgetsService, useValue: widgets },
            // the data-table's real theme service walks up to GAMMA_APP_CONFIG — stub it, as its own spec does
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const store = TestBed.inject(InvestigationSessionStore);
    const el = fixture.nativeElement as HTMLElement;
    const button = (text: string) =>
        Array.from(el.querySelectorAll('button')).find((b) =>
            b.textContent?.trim().startsWith(text),
        ) as HTMLButtonElement;
    return { fixture, store, inv, widgets, el, button };
}

/** Open inv-1 as if it had been started here, and let the async refresh land. */
async function openInv(store: InvestigationSessionStore) {
    store.restore([{ id: 'inv-1', title: 'Burners' }]);
    await store.open('inv-1');
}

describe('LinkAnalysisInvestigationComponent (LA-10)', () => {
    it('start state: explains the binding, starts from the projection, and passes axe', async () => {
        const { fixture, el, inv, button } = create();
        expect(el.textContent).toContain("the query's filter is not applied");
        expect(el.textContent).toContain('no server-side list');
        await expectNoA11yViolations(el);

        button('Start Investigation').click();
        await fixture.whenStable();
        expect(inv.createInvestigation).toHaveBeenCalledWith(
            expect.objectContaining({ dataset: 'calls', sourceCol: 'A', targetCol: 'B' }),
        );
    });

    it('open Investigation: renders the ordered op log and refuses an exclusion without a reason', async () => {
        const { fixture, store, el, inv, button } = create();
        await openInv(store);
        store.selected.set({ id: 'entity:a', data: { label: 'a', kind: 'entity', spellings: ['a'] } });
        fixture.detectChanges();

        const steps = Array.from(el.querySelectorAll('[aria-label="Op log"] li')).map((li) => li.textContent);
        expect(steps).toHaveLength(2);
        expect(steps[0]).toContain('Seeded 1 entity: a.');
        expect(steps[1]).toContain('expand');
        await expectNoA11yViolations(el);

        button('Exclude').click();
        fixture.detectChanges();
        expect(inv.appendInvestigationOp).not.toHaveBeenCalled();
        expect(el.querySelector('mat-error')?.textContent).toContain('needs a reason');
    });

    it('re-order explains the fork on screen, then forks with the new order', async () => {
        const { fixture, store, el, inv, button } = create();
        await openInv(store);
        fixture.detectChanges();

        button('Re-order steps').click();
        fixture.detectChanges();
        expect(el.textContent).toContain('Re-ordering creates a fork');
        expect(el.textContent).toContain('The original Investigation stays exactly as it is');
        expect(button('Create fork').disabled).toBe(true); // an unchanged order would fork for nothing

        (el.querySelector('[aria-label="Move step 2 up"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(button('Create fork').disabled).toBe(false);
        button('Create fork').click();
        await fixture.whenStable();
        expect(inv.reorderInvestigation).toHaveBeenCalledWith('inv-1', { order: [2, 1] });
    });

    it('LA-21: pins the Working Set to a Widget at the current head — Frozen by default, Live on request', async () => {
        const { fixture, store, inv, widgets, el, button } = create();
        await openInv(store);
        fixture.detectChanges();
        const section = el.querySelector('[aria-label="Pin to a Widget"]') as HTMLElement;
        expect(section.textContent).toContain('a Live Widget cannot leave this Space');
        await expectNoA11yViolations(el);

        button('Save Widget').click(); // no name yet
        fixture.detectChanges();
        expect(widgets.save).not.toHaveBeenCalled();
        expect(section.querySelector('mat-error')?.textContent).toContain('A name is required');

        const input = section.querySelector('input[formcontrolname="name"]') as HTMLInputElement;
        input.value = 'burners_frozen';
        input.dispatchEvent(new Event('input'));
        button('Save Widget').click();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(inv.workingSetRelation).toHaveBeenCalledWith('inv-1', { of: 'entities', limit: 1 });
        const frozen = widgets.save.mock.calls[0][0];
        expect(frozen).toMatchObject({
            id: 'burners_frozen',
            datasetId: '',
            vizType: 'working-set',
            viewId: 'inv-1',
            workingSet: { relation: 'entities', mode: 'frozen', pin: { step: 2, workingSetHash: 'sha256:h2' } },
        });
        expect(el.textContent).toContain('“burners_frozen” is in the Widget library');
        expect(section.querySelector('mat-error')).toBeNull(); // the cleared name is not left in error (found in preview)

        const comp = fixture.debugElement.children[0].componentInstance;
        comp.pinForm.setValue({ name: 'burners_live', relation: 'links', mode: 'live' });
        button('Save Widget').click();
        await fixture.whenStable();
        expect(widgets.save.mock.calls[1][0].workingSet).toMatchObject({ relation: 'links', mode: 'live' });
    });
});
