import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { WorkflowDraft, workflowDiagram } from 'app/inspecto/governance/governance-model';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { WorkflowDiagramComponent } from './workflow-diagram.component';

const draft = (initial: string, terminal: string, moves: [string, string, string][]): WorkflowDraft => ({
    objectType: 'INCIDENT',
    initial,
    terminal,
    transitions: moves.map(([from, action, to]) => ({ from, action, to })),
});

describe('workflowDiagram layout', () => {
    it('columns follow distance from the initial state; terminal and initial are flagged', () => {
        const g = workflowDiagram(
            draft('A', 'C', [
                ['A', 'go', 'B'],
                ['B', 'end', 'C'],
            ]),
        );
        expect(g.nodes.map((n) => [n.id, n.column])).toEqual([
            ['A', 0],
            ['B', 1],
            ['C', 2],
        ]);
        expect(g.nodes[0].initial).toBe(true);
        expect(g.nodes[2].terminal).toBe(true);
    });
    it('puts an unreachable state in its own trailing column', () => {
        const g = workflowDiagram(
            draft('A', 'B', [
                ['A', 'go', 'B'],
                ['X', 'x', 'B'],
            ]),
        );
        const x = g.nodes.find((n) => n.id === 'X');
        expect(x?.unreachable).toBe(true);
        expect(x?.column).toBe(2);
        expect(g.columns).toBe(3);
    });
    it('a cycle does not loop and a back-edge keeps both states in their columns', () => {
        const g = workflowDiagram(
            draft('A', 'B', [
                ['A', 'go', 'B'],
                ['B', 'back', 'A'],
            ]),
        );
        expect(g.nodes.map((n) => n.column)).toEqual([0, 1]);
        expect(g.edges).toHaveLength(2);
    });
    it('an empty workflow has no nodes', () => {
        expect(workflowDiagram(draft('', '', [])).nodes).toEqual([]);
    });
});

describe('WorkflowDiagramComponent', () => {
    function render(d: WorkflowDraft): ComponentFixture<WorkflowDiagramComponent> {
        TestBed.configureTestingModule({ imports: [WorkflowDiagramComponent] });
        const f = TestBed.createComponent(WorkflowDiagramComponent);
        f.componentRef.setInput('draft', d);
        f.detectChanges();
        return f;
    }

    it('draws an image with a text alternative, self-loops and back-edges included, and passes axe', async () => {
        const f = render(
            draft('A', 'C', [
                ['A', 'go', 'B'],
                ['B', 'retry', 'B'],
                ['B', 'back', 'A'],
                ['B', 'end', 'C'],
            ]),
        );
        const el: HTMLElement = f.nativeElement;
        expect(el.querySelector('svg[role="img"]')?.getAttribute('aria-label')).toContain('B to C by end');
        expect(el.querySelectorAll('path[marker-end]')).toHaveLength(4);
        expect(el.querySelectorAll('rect')).toHaveLength(3);
        await expectNoA11yViolations(el);
    });

    it('says so when empty and re-renders when the draft changes', () => {
        const f = render(draft('', '', []));
        expect(f.nativeElement.textContent).toContain('No states yet');
        f.componentRef.setInput('draft', draft('A', 'B', [['A', 'go', 'B']]));
        f.detectChanges();
        expect(f.nativeElement.querySelectorAll('rect')).toHaveLength(2);
    });
});
