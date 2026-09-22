import { signal } from '@angular/core';
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

/**
 * A stand-in for the snapshot store with the real one's OBSERVABLE contract (LA-03): `save`/`attachTo`
 * return Observables and the signal updates only after they emit, exactly as the service does after the
 * server confirms. The real service injects HttpClient, so constructing it here would need the HTTP
 * testing harness for tests that are about the DIALOG, not about transport.
 *
 * ⚠ `save` and `attachTo` are overridable so a spec can make one FAIL — the dialog's behaviour on a
 * failed seal (stay open, show the error, keep the analyst's work) is the point of wiring it at all.
 */
function fakeSnapshots(over: Partial<Record<'save' | 'attachTo', unknown>> = {}) {
    const snapshots = signal<GraphSnapshot[]>([]);
    return {
        snapshots,
        count: () => snapshots().length,
        // The real service's placeholders, verbatim: a spec indexes mockCases[0], and a fake that
        // answers an EMPTY list fails with 'cannot read id of undefined' — a fake must mimic the
        // shape it stands in for, not just its method names.
        mockCases: [
            { id: 'CASE-2026-0318', title: 'Suspected layering network (placeholder Case)' },
            { id: 'CASE-2026-0322', title: 'Burner rotation cluster (placeholder Case)' },
        ],
        save: (s: GraphSnapshot) => {
            snapshots.update((all) => [s, ...all.filter((x) => x.id !== s.id)]);
            return of(s);
        },
        attachTo: (id: string, caseId: string) => {
            snapshots.update((all) => all.map((x) => (x.id === id ? { ...x, attachedTo: [caseId] } : x)));
            return of([caseId]);
        },
        ...over,
    } as unknown as LinkAnalysisSnapshotsService;
}

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
                { provide: LinkAnalysisSnapshotsService, useValue: fakeSnapshots() },
                { provide: SessionService, useValue: { opsEnabled: () => true } },
                { provide: ObjectsService, useValue: { list: () => of([{ id: 'CASE-9', title: 'Real case' }]) } },
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

        fixture.componentInstance.form.setValue({ title: 'Chain', description: '', caseId: '' });
        fixture.componentInstance.save();
        const snap = close.mock.calls[0][0] as GraphSnapshot;
        expect(snap.nodes.map((n) => n.id)).toEqual(['a', 'b']);
        expect(verifySnapshot(snap)).toBe(true);
        expect(TestBed.inject(LinkAnalysisSnapshotsService).snapshots()).toEqual([snap]);
        await expectNoA11yViolations(el);
    });
});

describe('LinkAnalysisSnapshotDialog — the Case is optional', () => {
    function create(list: () => unknown, caseId = '') {
        const close = vi.fn();
        const store = fakeSnapshots();
        TestBed.configureTestingModule({
            imports: [LinkAnalysisSnapshotDialog],
            providers: [
                provideNoopAnimations(),
                { provide: LinkAnalysisSnapshotsService, useValue: store },
                { provide: SessionService, useValue: { opsEnabled: () => true } },
                { provide: ObjectsService, useValue: { list } },
                { provide: MatDialogRef, useValue: { close, backdropClick: () => of(), keydownEvents: () => of() } },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: {
                        graph: G,
                        predicate: null,
                        origin: { sourceId: 'entity-projection', dataset: 'tx', query: {} },
                        layout: 'dagre',
                        suggestedTitle: 'Chain',
                        caseId,
                    },
                },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisSnapshotDialog);
        fixture.detectChanges();
        return { fixture, close, store };
    }

    it('saves the analysis with no Case at all', () => {
        const { fixture, close, store } = create(() => of([{ id: 'CASE-9', title: 'Real case' }]));
        fixture.componentInstance.save();
        const snap = close.mock.calls[0][0] as GraphSnapshot;
        expect(verifySnapshot(snap)).toBe(true);
        expect(snap.attachedTo).toEqual([]); // saved, unattached — a legitimate outcome
        expect(store.snapshots()).toHaveLength(1);
    });

    it('attaches in the same action when a Case IS chosen', () => {
        const { fixture, close, store } = create(() => of([{ id: 'CASE-9', title: 'Real case' }]));
        fixture.componentInstance.form.patchValue({ caseId: 'CASE-9' });
        fixture.componentInstance.save();
        const snap = close.mock.calls[0][0] as GraphSnapshot;
        expect(snap.attachedTo).toEqual(['CASE-9']);
        expect(store.snapshots()[0].attachedTo).toEqual(['CASE-9']);
    });

    it('still SAVES when the Case lookup failed — the error costs the attachment, not the analysis', async () => {
        const { fixture, close, store } = create(() => throwError(() => new Error('down')));
        const el: HTMLElement = fixture.nativeElement;

        // The Case cannot be offered, and is not faked...
        const errorAlert = Array.from(el.querySelectorAll('inspecto-alert')).find((a) =>
            a.textContent?.includes('Cases could not be loaded'),
        );
        expect(errorAlert).toBeTruthy();
        expect(el.querySelector('inspecto-option-picker')).toBeNull();
        for (const mock of store.mockCases) expect(el.textContent).not.toContain(mock.id);

        // ...but Save is NOT disabled, and the analysis is kept. This is the 2026-09-22 decision: the
        // analysis stands on its own, so a failed Case lookup must never be a dead end.
        expect(el.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(false);
        fixture.componentInstance.save();
        const snap = close.mock.calls[0][0] as GraphSnapshot;
        expect(verifySnapshot(snap)).toBe(true);
        expect(snap.attachedTo).toEqual([]);
        expect(store.snapshots()).toHaveLength(1);
        await expectNoA11yViolations(el);
    });

    it('pre-selects the Case it was opened from (the ?case= deep link)', () => {
        const { fixture } = create(() => of([{ id: 'CASE-9', title: 'Real case' }]), 'CASE-9');
        expect(fixture.componentInstance.form.getRawValue().caseId).toBe('CASE-9');
    });
});

describe('LinkAnalysisAttachCaseDialog', () => {
    function create(opsEnabled: boolean, list = () => of([{ id: 'CASE-9', title: 'Real case' }])) {
        const close = vi.fn();
        const store = fakeSnapshots();
        const snapshot = {
            id: 's1',
            title: 'T',
            manifestHash: 'abcdef0123456789',
            attachedTo: [],
        } as unknown as GraphSnapshot;
        store.snapshots.set([snapshot]);
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
        const el: HTMLElement = fixture.nativeElement;
        // The placeholder path still renders a picker to choose from (the field states it is a placeholder).
        expect(el.querySelector('inspecto-option-picker')).not.toBeNull();
        expect(el.textContent).toContain('ops module is not installed');

        c.attach();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(el.querySelector('p[role="alert"]')?.textContent).toContain('Pick a Case');

        c.form.patchValue({ caseId: store.mockCases[0].id });
        c.attach();
        expect(close).toHaveBeenCalledWith({ caseId: store.mockCases[0].id });
        expect(store.snapshots()[0].attachedTo).toEqual([store.mockCases[0].id]);
        expect(snapshot.attachedTo).toEqual([]); // the store replaced the entry rather than mutating it
        await expectNoA11yViolations(el);
    });

    it('lists real Cases with the ops module', () => {
        const { fixture } = create(true);
        expect(fixture.nativeElement.textContent).toContain('Open Cases from the objects store');
    });

    it('surfaces an errored Case lookup and offers NO attachable case', async () => {
        const { fixture, close, store, snapshot } = create(true, () => throwError(() => new Error('down')) as never);
        const c = fixture.componentInstance;
        const el: HTMLElement = fixture.nativeElement;

        // The error state is honest: nothing attachable, and no placeholder leaked in as a real Case.
        for (const mock of store.mockCases) expect(el.textContent).not.toContain(mock.id);
        // Pick the alert by its TITLE, not by document order: the dialog's pre-existing "UI-first"
        // notice is also an `<inspecto-alert>` carrying role="alert" (the component gives warning and
        // error the same role), so a positional query silently depends on which one comes first.
        const errorAlert = Array.from(el.querySelectorAll('inspecto-alert')).find((a) =>
            a.textContent?.includes('Cases could not be loaded'),
        );
        expect(errorAlert).toBeTruthy();
        expect(errorAlert?.querySelector('[role="alert"]')?.textContent).toContain('no Case can be offered');
        expect(el.querySelector('inspecto-option-picker')).toBeNull();
        expect(el.querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(true);

        // Even a caseId forced onto the form cannot be attached while the lookup is errored: nothing
        // offered it, so nothing vouches that the Case exists.
        c.form.patchValue({ caseId: store.mockCases[0].id });
        c.attach();
        expect(close).not.toHaveBeenCalled();
        expect(store.snapshots()[0].attachedTo).toEqual([]);
        expect(snapshot.attachedTo).toEqual([]);
        await expectNoA11yViolations(el);
    });
});

/**
 * The reason LA-03's SPA half was worth doing at all: a save that fails must not look like one that
 * worked. Before this, `add()` was a synchronous signal mutation that could not fail, so the dialog
 * always closed and the analyst always believed the analysis was kept.
 */
describe('LinkAnalysisSnapshotDialog — a failed seal keeps the work on screen', () => {
    function create(save: unknown) {
        const close = vi.fn();
        const store = fakeSnapshots({ save });
        TestBed.configureTestingModule({
            imports: [LinkAnalysisSnapshotDialog],
            providers: [
                provideNoopAnimations(),
                { provide: LinkAnalysisSnapshotsService, useValue: store },
                { provide: SessionService, useValue: { opsEnabled: () => true } },
                { provide: ObjectsService, useValue: { list: () => of([{ id: 'CASE-9', title: 'Real case' }]) } },
                { provide: MatDialogRef, useValue: { close, backdropClick: () => of(), keydownEvents: () => of() } },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: {
                        graph: G,
                        predicate: null,
                        origin: { sourceId: 'entity-projection', dataset: 'tx', query: {} },
                        layout: 'dagre',
                        suggestedTitle: 'Chain',
                    },
                },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisSnapshotDialog);
        fixture.detectChanges();
        return { fixture, close };
    }

    it('does not close, and says why, when the seal is refused', () => {
        const { fixture, close } = create(() => throwError(() => new Error('snapshot already exists')));
        fixture.componentInstance.form.setValue({ title: 'Chain', description: '', caseId: '' });

        fixture.componentInstance.save();
        fixture.detectChanges();

        expect(close).not.toHaveBeenCalled();
        expect(fixture.componentInstance.saveError()).toContain('already exists');
        // Content-based lookup, never positional: several alerts in this dialog carry role="alert".
        const alerts = [...(fixture.nativeElement as HTMLElement).querySelectorAll('inspecto-alert')];
        expect(alerts.some((a) => a.textContent?.includes('already exists'))).toBe(true);
    });

    it('keeps the analyst’s typed work when the seal is refused', () => {
        const { fixture } = create(() => throwError(() => new Error('write root unavailable')));
        fixture.componentInstance.form.setValue({ title: 'Layering chain', description: 'notes', caseId: '' });

        fixture.componentInstance.save();
        fixture.detectChanges();

        expect(fixture.componentInstance.form.getRawValue().title).toBe('Layering chain');
        expect(fixture.componentInstance.form.getRawValue().description).toBe('notes');
        expect(fixture.componentInstance.saving()).toBe(false);
    });

    // The seal succeeded; only the Case link failed. Reporting "not saved" would be a lie about evidence
    // that demonstrably exists on disk.
    it('closes when the snapshot sealed but the attachment failed', () => {
        const close = vi.fn();
        const store = fakeSnapshots({ attachTo: () => throwError(() => new Error('ops unavailable')) });
        TestBed.configureTestingModule({
            imports: [LinkAnalysisSnapshotDialog],
            providers: [
                provideNoopAnimations(),
                { provide: LinkAnalysisSnapshotsService, useValue: store },
                { provide: SessionService, useValue: { opsEnabled: () => true } },
                { provide: ObjectsService, useValue: { list: () => of([{ id: 'CASE-9', title: 'Real case' }]) } },
                { provide: MatDialogRef, useValue: { close, backdropClick: () => of(), keydownEvents: () => of() } },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: {
                        graph: G,
                        predicate: null,
                        origin: { sourceId: 'entity-projection', dataset: 'tx', query: {} },
                        layout: 'dagre',
                        suggestedTitle: 'Chain',
                    },
                },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisSnapshotDialog);
        fixture.detectChanges();
        fixture.componentInstance.form.setValue({ title: 'Chain', description: '', caseId: 'CASE-9' });

        fixture.componentInstance.save();

        expect(close).toHaveBeenCalledTimes(1);
        const closed = close.mock.calls[0][0] as GraphSnapshot;
        expect(closed.attachedTo).toEqual([]);
        expect(fixture.componentInstance.saveError()).toBe('');
    });
});
