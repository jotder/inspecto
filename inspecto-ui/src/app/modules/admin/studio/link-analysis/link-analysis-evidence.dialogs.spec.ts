import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';
import { SessionService } from 'app/inspecto/api';
import { ObjectsService } from 'app/inspecto/api/objects.service';
import { G6GraphData, GraphSnapshot, verifySnapshot } from 'app/inspecto/graph';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    LinkAnalysisAttachCaseDialog,
    LinkAnalysisSnapshotDialog,
    describePredicate,
} from './link-analysis-evidence.dialogs';
import { LinkAnalysisSnapshotsService } from './link-analysis-snapshots.service';

const G: G6GraphData = {
    nodes: [
        { id: 'a', data: { label: 'A', kind: 'entity' } },
        { id: 'b', data: { label: 'B', kind: 'entity' } },
        { id: 'z', data: { label: 'Z', kind: 'entity', missing: true } },
    ],
    edges: [
        { id: 'ab', source: 'a', target: 'b', data: { kind: 'wire' } },
        { id: 'az', source: 'a', target: 'z', data: { kind: 'wire' } },
    ],
};

describe('describePredicate', () => {
    it('renders a tree as one line, nested groups in parentheses', () => {
        expect(describePredicate(null)).toBe('none');
        expect(
            describePredicate({
                kind: 'group',
                op: 'AND',
                items: [
                    { kind: 'condition', field: 'amount', operator: '>=', value: '1' },
                    {
                        kind: 'group',
                        op: 'OR',
                        items: [
                            { kind: 'condition', field: 'a', operator: '=', value: 'x' },
                            { kind: 'condition', field: 'b', operator: 'between', value: '1', value2: '2' },
                        ],
                    },
                ],
            }),
        ).toBe('amount >= 1 AND (a = x OR b between 1 … 2)');
    });
});

describe('LinkAnalysisSnapshotDialog', () => {
    it('shows the frozen counts without stranded nodes, requires a title, and stores a verifiable snapshot', async () => {
        const close = vi.fn();
        TestBed.configureTestingModule({
            imports: [LinkAnalysisSnapshotDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MatDialogRef, useValue: { close, backdropClick: () => of(), keydownEvents: () => of() } },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: {
                        graph: G,
                        predicate: null,
                        origin: { sourceId: 'entity-projection', dataset: 'tx', query: {} },
                        layout: 'dagre',
                        suggestedTitle: '',
                    },
                },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisSnapshotDialog);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('2 nodes · 1 links');
        expect(el.textContent).toContain('1 stranded nodes excluded');

        fixture.componentInstance.save(); // empty title — refused, error rendered
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(el.querySelector('mat-error')?.textContent).toContain('title');

        fixture.componentInstance.form.setValue({ title: 'Chain', description: '' });
        fixture.componentInstance.save();
        const snap = close.mock.calls[0][0] as GraphSnapshot;
        expect(snap.nodes.map((n) => n.id)).toEqual(['a', 'b']);
        expect(verifySnapshot(snap)).toBe(true);
        expect(TestBed.inject(LinkAnalysisSnapshotsService).snapshots()).toEqual([snap]);
        await expectNoA11yViolations(el);
    });
});

describe('LinkAnalysisAttachCaseDialog', () => {
    function create(opsEnabled: boolean, list = () => of([{ id: 'CASE-9', title: 'Real case' }])) {
        const close = vi.fn();
        const store = new LinkAnalysisSnapshotsService();
        const snapshot = {
            id: 's1',
            title: 'T',
            manifestHash: 'abcdef0123456789',
            attachedTo: [],
        } as unknown as GraphSnapshot;
        store.add(snapshot);
        TestBed.configureTestingModule({
            imports: [LinkAnalysisAttachCaseDialog],
            providers: [
                provideNoopAnimations(),
                { provide: LinkAnalysisSnapshotsService, useValue: store },
                { provide: SessionService, useValue: { opsEnabled: () => opsEnabled } },
                { provide: ObjectsService, useValue: { list } },
                { provide: MatDialogRef, useValue: { close, backdropClick: () => of(), keydownEvents: () => of() } },
                { provide: MAT_DIALOG_DATA, useValue: { snapshot } },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisAttachCaseDialog);
        fixture.detectChanges();
        return { fixture, close, store, snapshot };
    }

    it('offers placeholder Cases without the ops module, refuses without a pick, and records the attachment', async () => {
        const { fixture, close, store, snapshot } = create(false);
        const c = fixture.componentInstance;
        expect(c.cases().map((x) => x.id)).toEqual(store.mockCases.map((x) => x.id));
        expect(c.casesHelp()).toMatch(/Placeholder/);

        c.attach();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Pick a Case');

        c.form.patchValue({ caseId: store.mockCases[0].id });
        c.attach();
        expect(close).toHaveBeenCalledWith({ caseId: store.mockCases[0].id });
        expect(store.snapshots()[0].attachedTo).toEqual([store.mockCases[0].id]);
        expect(snapshot.attachedTo).toEqual([]); // the store replaced the entry rather than mutating it
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('lists real Cases with the ops module', () => {
        const { fixture } = create(true);
        expect(fixture.componentInstance.cases()).toEqual([{ id: 'CASE-9', title: 'Real case' }]);
    });

    it('falls back to placeholders when the objects call fails', () => {
        const { fixture } = create(true, () => throwError(() => new Error('down')) as never);
        expect(fixture.componentInstance.cases().length).toBe(2);
    });
});
