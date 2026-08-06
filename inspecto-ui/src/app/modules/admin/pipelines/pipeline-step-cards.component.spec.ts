import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { AuthoredNode } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineStepCardsComponent } from './pipeline-step-cards.component';
import { StepRow } from './pipeline-graph';

function create(inputs: Partial<PipelineStepCardsComponent>) {
    TestBed.configureTestingModule({
        imports: [PipelineStepCardsComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(PipelineStepCardsComponent);
    Object.assign(fixture.componentInstance, inputs);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

const COLLECT: AuthoredNode = { id: 'collect-1', type: 'acquisition', name: 'Collect orders', config: { files: '*.csv' } };
const PARSE: AuthoredNode = { id: 'parse-1', type: 'parser', config: { grammar: 'grammar/pipe' } };

describe('PipelineStepCardsComponent', () => {
    it('renders one card per node row, in order, with its display name and category', async () => {
        const rows: StepRow[] = [
            { kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 },
            { kind: 'node', rowId: PARSE.id, node: PARSE, depth: 0 },
        ];
        const typeCat = new Map([['acquisition', 'SOURCE'], ['parser', 'PARSE']]);
        const { fixture } = create({ rows, typeCat });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Collect orders');
        expect(text).toContain('parse-1'); // no name set — falls back to id
        expect(text).toContain('Source');
        expect(text).toContain('Parser');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders a branch-header row indented and shows its predicate + default flag', () => {
        const rows: StepRow[] = [
            { kind: 'node', rowId: 'route-1', node: { id: 'route-1', type: 'transform.route' }, depth: 0 },
            { kind: 'branch', rowId: 'branch:emea:0', key: 'emea', where: "region = 'EU'", isDefault: false, depth: 0 },
            { kind: 'node', rowId: 'sink-emea', node: { id: 'sink-emea', type: 'sink.persistent' }, depth: 1 },
            { kind: 'branch', rowId: 'branch:other:0', key: 'other', isDefault: true, depth: 0 },
            { kind: 'node', rowId: 'sink-other', node: { id: 'sink-other', type: 'sink.persistent' }, depth: 1 },
        ];
        const typeCat = new Map([['transform.route', 'TRANSFORM'], ['sink.persistent', 'SINK']]);
        const { fixture } = create({ rows, typeCat });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('branch: emea');
        expect(text).toContain("when region = 'EU'");
        expect(text).toContain('branch: other');
        expect(text).toContain('default');
    });

    it('shows a status chip only when statusOf is provided', () => {
        // One TestBed/fixture, mutated between assertions — TestBed can only be configured once per test.
        const rows: StepRow[] = [{ kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 }];
        const typeCat = new Map([['acquisition', 'SOURCE']]);
        const { fixture } = create({ rows, typeCat, statusOf: () => 'configured' });
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Configured');

        fixture.componentRef.setInput('statusOf', undefined);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Configured');
    });

    it('renders nothing (an empty list) for an empty chain, without error', async () => {
        const { fixture } = create({ rows: [], typeCat: new Map() });
        expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(0);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('S2: editable cards emit open / remove / move, and branch rows stay trunk-only', async () => {
        const rows: StepRow[] = [
            { kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 },
            { kind: 'node', rowId: 'deep', node: { id: 'deep', type: 'sink.persistent' }, depth: 1 },
        ];
        const typeCat = new Map([['acquisition', 'SOURCE'], ['sink.persistent', 'SINK']]);
        const { fixture, c } = create({ rows, typeCat, editable: true });
        const opened: unknown[] = [];
        const removed: string[] = [];
        const moved: unknown[] = [];
        c.open.subscribe((n) => opened.push(n));
        c.remove.subscribe((id) => removed.push(id));
        c.move.subscribe((m) => moved.push(m));

        const el = fixture.nativeElement as HTMLElement;
        const btn = (label: string): HTMLButtonElement | undefined =>
            Array.from(el.querySelectorAll('button')).find((b) => b.getAttribute('aria-label') === label);

        btn('Configure collect-1')!.click();
        expect(opened).toEqual([COLLECT]);
        btn('Remove collect-1')!.click();
        expect(removed).toEqual(['collect-1']);
        btn('Move collect-1 up')!.click();
        expect(moved).toEqual([{ id: 'collect-1', dir: 'up' }]);

        // trunk-only editing (S2 scope): the depth-1 card configures, but never removes/moves
        expect(btn('Configure deep')).toBeTruthy();
        expect(btn('Remove deep')).toBeUndefined();
        expect(btn('Move deep up')).toBeUndefined();

        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('S2: hides every editing affordance when not editable', () => {
        const rows: StepRow[] = [{ kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 }];
        const { fixture } = create({ rows, typeCat: new Map([['acquisition', 'SOURCE']]), editable: false });
        expect((fixture.nativeElement as HTMLElement).querySelectorAll('button')).toHaveLength(0);
    });
});
