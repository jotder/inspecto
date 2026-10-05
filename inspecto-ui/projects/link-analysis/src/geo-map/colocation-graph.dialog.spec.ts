import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { G6GraphData } from '@inspecto/core/graph';
import { GraphViewComponent } from '@inspecto/core/graph/graph-view.component';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { ColocationGraphDialog } from './colocation-graph.dialog';

/** The G6 host draws on canvases jsdom lacks; the dialog's own markup is what is under test. */
@Component({ selector: 'inspecto-graph-view', standalone: true, template: '' })
class GraphViewStub {
    readonly data = input<G6GraphData | null>(null);
    readonly fill = input(false);
    readonly tooltips = input(false);
}

describe('ColocationGraphDialog (LA-A11Y-AUDIT-1)', () => {
    it('is a named dialog body with a Close button, axe-clean, and does not force a fixed width past the viewport', async () => {
        TestBed.configureTestingModule({
            imports: [ColocationGraphDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: { graph: { nodes: [], edges: [] } } },
            ],
        }).overrideComponent(ColocationGraphDialog, {
            remove: { imports: [GraphViewComponent] },
            add: { imports: [GraphViewStub] },
        });
        const fixture = TestBed.createComponent(ColocationGraphDialog);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('h2')!.textContent).toContain('Who met whom');
        expect(el.querySelector('button')!.textContent).toContain('Close');
        expect(el.querySelector('.w-\\[42rem\\]')!.classList.contains('max-w-full')).toBe(true); // 320 px reflow
        await expectNoA11yViolations(el);
    });
});
