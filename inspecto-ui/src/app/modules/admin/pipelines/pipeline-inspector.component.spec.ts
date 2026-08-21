import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { AuthoredNode } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineInspectorComponent } from './pipeline-inspector.component';

function create(inputs: Partial<PipelineInspectorComponent> = {}) {
    TestBed.configureTestingModule({
        imports: [PipelineInspectorComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(PipelineInspectorComponent);
    Object.assign(fixture.componentInstance, inputs);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

const NODE: AuthoredNode = {
    id: 'parse',
    type: 'parser.dsv',
    name: 'Parse CSV',
    use: 'grammar/cdr_csv',
    config: { delimiter: ',' },
};

describe('PipelineInspectorComponent', () => {
    it('shows the idle hint when nothing is selected', () => {
        const { fixture } = create();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Drag a Step');
    });

    it('renders a selected node SLIM: category, status, name/use — config rows only read-only', () => {
        const { fixture } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text()).toContain('Node · parse');
        expect(text()).toContain('Parser'); // categoryLabel('PARSE')
        expect(text()).toContain('Configured');
        expect(text()).toContain('name:');
        expect(text()).toContain('use:');
        // 2026-08-21 second pass: the author summary is SLIM — config detail is the pane's job.
        expect(text()).not.toContain('delimiter:');

        fixture.componentRef.setInput('readOnly', true);
        fixture.detectChanges();
        expect(text(), 'read-only has no pane, so the summary keeps the rows').toContain('delimiter:');
    });

    it('shows the last-run overlay (T17) when provided, and nothing when absent', () => {
        // One TestBed/fixture, mutated between assertions — TestBed can only be configured once per test.
        const { fixture } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Last run:');

        fixture.componentRef.setInput('lastRun', { rowCount: 1234, runTs: '2026-07-18T10:00:00Z' });
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain(
            'Last run: 1,234 row(s) · 2026-07-18T10:00:00Z',
        );
    });

    it('emits configure from the node actions', () => {
        const { fixture, c } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        const configure = vi.fn();
        c.configure.subscribe(configure);
        const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
        buttons.find((b) => b.textContent?.includes('Configure'))?.click();
        expect(configure).toHaveBeenCalledWith(NODE);
    });

    /**
     * S1 — the selection VERBS moved to the toolbar's selection cluster (they act on the selection, and
     * Delete rendered twice on screen at once). This panel holds state you read and edit; the only button
     * left is the one that opens its own successor surface.
     */
    it('carries no selection commands: Run to here / Preview data / Connect / Delete all left', () => {
        const view: AuthoredNode = { id: 'v', type: 'sink.view', name: 'orders_view' };
        const { fixture } = create({ node: view, status: 'configured', category: 'SINK' });
        const el = fixture.nativeElement as HTMLElement;
        const labels = Array.from(el.querySelectorAll('button')).map((b) => b.textContent ?? '');
        expect(labels.some((t) => t.includes('Run to here'))).toBe(false);
        expect(labels.some((t) => t.includes('Preview data'))).toBe(false);
        expect(labels.some((t) => t.includes('Connect'))).toBe(false);
        expect(el.querySelector('[aria-label="Delete Step"]')).toBeNull();
    });

    it('renders the edge relationship picker when an edge is selected', () => {
        const { fixture } = create({
            selectedEdgeId: 'a->b:data:1',
            selectedEdgeRel: 'data',
            candidateRels: ['data', 'kept', 'dropped'],
        });
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Connection');
    });

    it('emits edgeRelChange for the edge view, and carries no Delete connection (S1)', () => {
        const { fixture, c } = create({
            selectedEdgeId: 'a->b:data:1',
            selectedEdgeRel: 'data',
            candidateRels: ['data', 'kept'],
        });
        const change = vi.fn();
        c.edgeRelChange.subscribe(change);
        expect(fixture.nativeElement.querySelector('[aria-label="Delete connection"]')).toBeNull();
        c.edgeRelChange.emit('kept');
        expect(change).toHaveBeenCalledWith('kept');
    });

    it('readOnly hides Configure', () => {
        const { fixture } = create({ node: NODE, status: 'configured', category: 'PARSE', readOnly: true });
        const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
        expect(buttons.some((b) => b.textContent?.includes('Configure'))).toBe(false);
    });

    it('renames in place: pencil opens the form seeded from the node, Save emits the trimmed values', () => {
        const { fixture, c } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        const el = fixture.nativeElement as HTMLElement;
        const rename = vi.fn();
        c.rename.subscribe(rename);

        (el.querySelector('[aria-label="Rename Step"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(c.renameForm.getRawValue()).toEqual({ name: 'Parse CSV', description: '' });

        c.renameForm.setValue({ name: '  Delimited CDRs ', description: ' pipe-delimited feed ' });
        (el.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(rename).toHaveBeenCalledWith({ name: 'Delimited CDRs', description: 'pipe-delimited feed' });
        expect(c.renaming()).toBe(false);
    });

    it('cancel closes the rename form without emitting, and a selection change discards a draft', () => {
        const { fixture, c } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        const el = fixture.nativeElement as HTMLElement;
        const rename = vi.fn();
        c.rename.subscribe(rename);

        (el.querySelector('[aria-label="Rename Step"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        const cancel = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Cancel'));
        cancel?.click();
        fixture.detectChanges();
        expect(rename).not.toHaveBeenCalled();
        expect(c.renaming()).toBe(false);

        // A different selection makes an in-progress rename stale — never carry one node's draft
        // onto another node.
        c.startRename();
        expect(c.renaming()).toBe(true);
        fixture.componentRef.setInput('node', { id: 'other', type: 'sink.dataset' });
        fixture.detectChanges();
        expect(c.renaming()).toBe(false);
    });

    it('readOnly offers no rename affordance', () => {
        const { fixture } = create({ node: NODE, status: 'configured', category: 'PARSE', readOnly: true });
        expect(fixture.nativeElement.querySelector('[aria-label="Rename Step"]')).toBeNull();
    });

    /**
     * The COMPACT strip — rendered inside the definition drawer above the config pane (2026-08-21:
     * selection opens the pane directly, so the full summary would duplicate the drawer header and
     * the pane). Keeps only what the pane does not carry: status, last run, description, rename.
     */
    describe('compact mode (drawer identity strip)', () => {
        it('drops the header, type, name line, config rows and Configure', () => {
            const { fixture } = create({
                node: NODE,
                status: 'configured',
                category: 'PARSE',
                compact: true,
            });
            const el = fixture.nativeElement as HTMLElement;
            const t = el.textContent ?? '';
            expect(t).not.toContain('Node · parse');
            expect(t).not.toContain('parser.dsv'); // the type line
            expect(t).not.toContain('name:'); // the drawer header already shows the name
            expect(t).not.toContain('delimiter:'); // config rows are the pane's job now
            // ⚠ assert the BUTTON: the status chip's text 'Configured' contains 'Configure'.
            const buttons = Array.from(el.querySelectorAll('button')).map((b) => b.textContent ?? '');
            expect(buttons.some((b) => b.includes('Configure'))).toBe(false);
            expect(t).toContain('Configured'); // the status chip stays
        });

        it('keeps the rename pencil, and it still round-trips the form', () => {
            const { fixture, c } = create({ node: NODE, status: 'configured', category: 'PARSE', compact: true });
            const el = fixture.nativeElement as HTMLElement;
            const rename = vi.fn();
            c.rename.subscribe(rename);
            (el.querySelector('[aria-label="Rename Step"]') as HTMLButtonElement).click();
            fixture.detectChanges();
            c.renameForm.setValue({ name: 'CDR parse', description: '' });
            c.commitRename();
            expect(rename).toHaveBeenCalledWith({ name: 'CDR parse', description: '' });
        });
    });

    it('has no a11y violations in any of the three states', async () => {
        // One TestBed/fixture, mutated between assertions — TestBed can only be configured once per test.
        const { fixture, c } = create();
        await expectNoA11yViolations(fixture.nativeElement); // idle

        c.node = NODE;
        c.status = 'tested';
        c.category = 'PARSE';
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement); // node selected

        c.node = null;
        c.selectedEdgeId = 'a->b:data:1';
        c.selectedEdgeRel = 'data';
        c.candidateRels = ['data'];
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement); // edge selected
    });
});
