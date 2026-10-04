import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ImportDraftBannerComponent } from './import-draft-banner.component';
import { ImportDraft } from './import-draft';

const FINDING = 'pipeline/demo: pipeline validation failed: [UNKNOWN_USE — transform/no_such_rule]';

function pipelineDraft(integrity: string[] | null): ImportDraft {
    return {
        kind: 'pipeline',
        id: 'demo',
        content: { nodes: [], edges: [] },
        sourceSpace: 'staging',
        targetExists: false,
        integrity,
        prerequisites: [],
    };
}

/** The banner over a Pipeline draft, now that `/bundle/preview` runs the graph Save's gates (operator 2026-10-04). */
describe('ImportDraftBannerComponent — Pipeline draft findings', () => {
    function render(integrity: string[] | null): HTMLElement {
        TestBed.configureTestingModule({ imports: [ImportDraftBannerComponent], providers: [provideNoopAnimations()] });
        const f = TestBed.createComponent(ImportDraftBannerComponent);
        f.componentRef.setInput('draft', pipelineDraft(integrity));
        f.componentRef.setInput('stored', null);
        f.detectChanges();
        return f.nativeElement as HTMLElement;
    }

    it('shows the findings the Save would refuse on, and no "not checked"', async () => {
        const el = render([FINDING]);
        expect(el.textContent).toContain('Broken references this draft would introduce');
        expect(el.textContent).toContain(FINDING);
        expect(el.textContent).not.toContain('References not checked');
        await expectNoA11yViolations(el);
    });

    it('null (the preview could not run) still reads "not checked", never clean', async () => {
        const el = render(null);
        expect(el.textContent).toContain('References not checked');
        expect(el.textContent).not.toContain(FINDING);
        await expectNoA11yViolations(el);
    });

    it('a clean preview shows no findings strip', () => {
        const el = render([]);
        expect(el.textContent).not.toContain('Broken references');
        expect(el.textContent).not.toContain('References not checked');
    });
});
