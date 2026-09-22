import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { LinkAnalysisWorkingSetComponent } from './link-analysis-overlays.component';

/**
 * Both Link Analysis notices added for decisions D-S1 and D-S4 live behind an `@if`, and a template
 * branch that never renders is the failure mode a pure-function spec cannot see — `splitIdentityGroups`
 * can be perfectly correct while the working set prints nothing. These specs mount the component and
 * assert on the rendered DOM, so the branch itself is pinned.
 */
describe('LinkAnalysisWorkingSetComponent — split-identity notice (D-S4)', () => {
    let fixture: ComponentFixture<LinkAnalysisWorkingSetComponent>;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [LinkAnalysisWorkingSetComponent],
            providers: [provideNoopAnimations()],
        }).compileComponents();
        fixture = TestBed.createComponent(LinkAnalysisWorkingSetComponent);
    });

    function text(): string {
        return (fixture.nativeElement as HTMLElement).textContent ?? '';
    }

    it('prints nothing when no identity is split', () => {
        fixture.componentRef.setInput('open', true);
        fixture.componentRef.setInput('splitIdentityCount', 0);
        fixture.detectChanges();

        expect(text()).not.toContain('split');
    });

    it('names the count in the expanded panel, singular', () => {
        fixture.componentRef.setInput('open', true);
        fixture.componentRef.setInput('splitIdentityCount', 1);
        fixture.componentRef.setInput('splitIdentityHint', 'entity:ACME Ltd / entity:acme ltd.');
        fixture.detectChanges();

        expect(text()).toContain('1 possible split identity');
        expect(text()).not.toContain('identities');
    });

    it('pluralises for more than one', () => {
        fixture.componentRef.setInput('open', true);
        fixture.componentRef.setInput('splitIdentityCount', 3);
        fixture.detectChanges();

        expect(text()).toContain('3 possible split identities');
    });

    it('carries the hint as a title, so the colliding spellings are readable', () => {
        fixture.componentRef.setInput('open', true);
        fixture.componentRef.setInput('splitIdentityCount', 2);
        fixture.componentRef.setInput('splitIdentityHint', 'entity:Bob / entity:bob');
        fixture.detectChanges();

        const el = (fixture.nativeElement as HTMLElement).querySelector('[title*="entity:Bob"]');
        expect(el).toBeTruthy();
    });

    // Minimizing must not hide the signal: the analyst who collapses the panel is the one most likely to
    // read a ranking without it.
    it('still signals when the panel is minimized', () => {
        fixture.componentRef.setInput('open', false);
        fixture.componentRef.setInput('splitIdentityCount', 2);
        fixture.detectChanges();

        expect(text()).toContain('split ids');
    });

    it('shows truncation and split identities together, not one instead of the other', () => {
        fixture.componentRef.setInput('open', false);
        fixture.componentRef.setInput('truncated', true);
        fixture.componentRef.setInput('splitIdentityCount', 2);
        fixture.detectChanges();

        expect(text()).toContain('truncated');
        expect(text()).toContain('split ids');
    });
});
