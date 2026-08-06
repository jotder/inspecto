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
});
