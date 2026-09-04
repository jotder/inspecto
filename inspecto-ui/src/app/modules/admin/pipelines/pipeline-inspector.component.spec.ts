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
    description: 'the daily feed',
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

    it('renames in place: pencil opens the Name form seeded from the node, Save emits the trimmed name', () => {
        const { fixture, c } = create({ node: NODE, status: 'configured', category: 'PARSE' });
        const el = fixture.nativeElement as HTMLElement;
        const rename = vi.fn();
        c.rename.subscribe(rename);

        (el.querySelector('[aria-label="Rename Step"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(c.renameForm.getRawValue()).toEqual({ name: 'Parse CSV' });
        // 2026-09-04 (operator ask): no Description field on any properties panel.
        expect(el.querySelector('form [formcontrolname="description"]')).toBeNull();

        c.renameForm.setValue({ name: '  Delimited CDRs ' });
        (el.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        // The stored description travels through UNCHANGED — the host contract is the same.
        expect(rename).toHaveBeenCalledWith({ name: 'Delimited CDRs', description: 'the daily feed' });
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

        /**
         * 2026-08-22 (operator ask): identity lives ON the config page. 2026-09-04 (operator ask):
         * Name renders as a PROPERTY ROW — the schema-form flat row's anatomy (`.sf-row`: label ·
         * value text · pencil → dense `.sf-dense` inline input) — and Description is gone from every
         * properties panel.
         */
        it('renders Name as a property row (value text + pencil) and no Description field', () => {
            // ⚠ setInput, not the Object.assign helper — the seed runs in ngOnChanges, which only
            // template/setInput input writes fire (exactly how the editor binds [node]).
            const { fixture } = create({ status: 'configured', category: 'PARSE' });
            fixture.componentRef.setInput('compact', true);
            fixture.componentRef.setInput('node', NODE);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            const row = el.querySelector('.sf-row[data-key="name"]') as HTMLElement;
            expect(row).not.toBeNull();
            expect(row.classList.contains('min-h-8')).toBe(true); // 32px, like a schema-form flat row
            expect(row.querySelector('.sf-value')?.textContent?.trim()).toBe('Parse CSV');
            expect(row.querySelector('.sf-pencil')?.getAttribute('aria-label')).toBe('Edit Name');
            expect(row.querySelector('input')).toBeNull(); // no input until the pencil
            expect(el.querySelector('[formcontrolname="description"]')).toBeNull();
            expect(el.textContent).not.toContain('Description');
            expect(el.querySelector('[aria-label="Rename Step"]')).toBeNull(); // the summary's pencil is not this one
        });

        it('pencil opens the dense inline input; Enter emits the new name with the description unchanged', () => {
            const { fixture, c } = create({ status: 'configured', category: 'PARSE' });
            fixture.componentRef.setInput('compact', true);
            fixture.componentRef.setInput('node', NODE);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            const rename = vi.fn();
            c.rename.subscribe(rename);

            (el.querySelector('.sf-pencil') as HTMLButtonElement).click();
            fixture.detectChanges();
            const input = el.querySelector('.sf-row[data-key="name"] input') as HTMLInputElement;
            expect(input).not.toBeNull();
            expect(input.closest('mat-form-field')?.classList.contains('sf-dense')).toBe(true);
            expect(input.closest('mat-form-field')?.querySelector('mat-label')).toBeNull(); // no floating label
            expect(c.renameForm.getRawValue()).toEqual({ name: 'Parse CSV' });

            input.value = '  CDR parse ';
            input.dispatchEvent(new Event('input'));
            (el.querySelector('.sf-row[data-key="name"]') as HTMLFormElement).dispatchEvent(new Event('submit'));
            fixture.detectChanges();
            expect(rename).toHaveBeenCalledWith({ name: 'CDR parse', description: 'the daily feed' });
            expect(c.editingName()).toBe(false);
            expect(el.querySelector('.sf-row[data-key="name"] input')).toBeNull();

            // a commit with nothing further changed emits no second rename (no phantom dirty)
            fixture.componentRef.setInput('node', { ...NODE, name: 'CDR parse' });
            fixture.detectChanges();
            c.commitIdentity();
            expect(rename).toHaveBeenCalledTimes(1);
        });

        it('Escape cancels the Name edit without emitting and restores the stored name', () => {
            const { fixture, c } = create({ status: 'configured', category: 'PARSE' });
            fixture.componentRef.setInput('compact', true);
            fixture.componentRef.setInput('node', NODE);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            const rename = vi.fn();
            c.rename.subscribe(rename);

            c.startNameEdit();
            fixture.detectChanges();
            const input = el.querySelector('.sf-row[data-key="name"] input') as HTMLInputElement;
            input.value = 'abandoned';
            input.dispatchEvent(new Event('input'));
            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            fixture.detectChanges();
            expect(rename).not.toHaveBeenCalled();
            expect(c.editingName()).toBe(false);
            expect(c.renameForm.getRawValue()).toEqual({ name: 'Parse CSV' });
            expect(el.querySelector('.sf-value')?.textContent?.trim()).toBe('Parse CSV');
            // a blur firing AFTER the cancel (browsers differ on removal) must not emit either
            c.commitIdentity();
            expect(rename).not.toHaveBeenCalled();
        });

        /**
         * Phase 4 S4 / D-13. The switch is offered ONLY where the engine can park — the host decides
         * that (`parkable`), so an ordinary Step never shows a toggle that the save gate would refuse.
         */
        it('offers the per-Step switch only when the host says the Step is parkable', () => {
            const sink: AuthoredNode = { id: 'sink__d1', type: 'sink.persistent', config: { database: '/db/apac' } };
            const { fixture, c } = create({ compact: true });
            fixture.componentRef.setInput('node', sink);
            fixture.detectChanges();
            const el = fixture.nativeElement as HTMLElement;
            expect(el.querySelector('mat-slide-toggle')).toBeNull();

            fixture.componentRef.setInput('parkable', true);
            fixture.detectChanges();
            const toggle = el.querySelector('mat-slide-toggle');
            expect(toggle).not.toBeNull();
            expect(toggle?.textContent).toContain('Step enabled');
            expect(el.textContent).toContain('Switch off to park');

            const changed = vi.fn();
            c.enabledChange.subscribe(changed);
            c.setEnabled(false);
            expect(changed).toHaveBeenCalledWith(false);

            // A switched-off Step says what the state MEANS, not just that it is off.
            fixture.componentRef.setInput('node', { ...sink, config: { ...sink.config, enabled: false } });
            fixture.detectChanges();
            expect(el.textContent).toContain('park until it is switched back on and drained');
        });

        it('never offers the switch in the read-only lens', () => {
            const sink: AuthoredNode = { id: 'sink__d1', type: 'sink.persistent', config: {} };
            const { fixture } = create({ compact: true, readOnly: true, parkable: true });
            fixture.componentRef.setInput('node', sink);
            fixture.detectChanges();
            expect((fixture.nativeElement as HTMLElement).querySelector('mat-slide-toggle')).toBeNull();
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
