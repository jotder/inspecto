import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoSamplePanelComponent } from './sample-panel.component';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

/** The thread is an INPUT, not an injection — the host owns one per editor tab. */
function create() {
    TestBed.configureTestingModule({
        imports: [InspectoSamplePanelComponent],
        providers: [provideNoopAnimations(), { provide: ToastrService, useValue: TOASTR }],
    });
    const state = new DefinitionStateService();
    const fixture = TestBed.createComponent(InspectoSamplePanelComponent);
    fixture.componentRef.setInput('state', state);
    fixture.detectChanges();
    return { fixture, state };
}

describe('InspectoSamplePanelComponent', () => {
    it('captures pasted text as the session sample and resets the parse thread', () => {
        const { fixture, state } = create();
        state.parsePreview.set({ frontend: 'delimited', columns: [], rowCount: 0, rows: [], rejectedRows: 0 });
        const c = fixture.componentInstance;
        c.pasting.set(true);
        c.pasteText = 'a,b\n1,2';
        c.usePasted();
        expect(state.sample()).toEqual({ name: 'pasted sample', text: 'a,b\n1,2' });
        expect(state.parsePreview()).toBeNull();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('2 lines');
        expect(fixture.nativeElement.textContent).toContain('not parsed yet');
    });

    it('collapses and re-expands the raw preview', () => {
        const { fixture, state } = create();
        state.captureSample('s.csv', 'a,b\n1,2\n');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('pre')).toBeTruthy();
        fixture.componentInstance.expanded.set(false);
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('pre')).toBeNull();
    });

    it('clear removes the sample and the downstream results', () => {
        const { fixture, state } = create();
        state.captureSample('s.csv', 'x\n');
        fixture.detectChanges();
        fixture.componentInstance.clear();
        expect(state.sample()).toBeNull();
    });

    /** 🔴 The whole point of the input: two tabs must not share one sample. */
    it('renders only the thread it was handed', () => {
        const { fixture, state } = create();
        const other = new DefinitionStateService();
        other.captureSample('other-tab.csv', 'x\ny\nz\n');
        state.captureSample('mine.csv', 'a\n');
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('mine.csv');
        expect(fixture.nativeElement.textContent).not.toContain('other-tab.csv');
    });

    /**
     * S4 — the parse verb belongs beside the chips whose state it changes. The panel owns the sample,
     * never a parser, so the button exists only when a host supplies the label and takes the output.
     */
    describe('the host-supplied parse action (S4)', () => {
        it('is absent until a host labels it', () => {
            const { fixture, state } = create();
            state.captureSample('s.csv', 'a,b');
            fixture.detectChanges();
            expect(fixture.nativeElement.textContent).not.toContain('Parse sample');
        });

        it('renders beside the chips and emits when pressed', () => {
            const { fixture, state } = create();
            state.captureSample('s.csv', 'a,b');
            fixture.componentRef.setInput('parseLabel', 'Parse sample');
            fixture.detectChanges();
            const parsed = vi.fn();
            fixture.componentInstance.parse.subscribe(parsed);
            const btn = Array.from(
                (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
            ).find((b) => b.textContent?.includes('Parse sample'));
            expect(btn).toBeTruthy();
            btn!.click();
            expect(parsed).toHaveBeenCalled();
        });

        it('disables on the host’s say-so', () => {
            const { fixture, state } = create();
            state.captureSample('s.csv', 'a,b');
            fixture.componentRef.setInput('parseLabel', 'Parse sample');
            fixture.componentRef.setInput('parseDisabled', true);
            fixture.detectChanges();
            const btn = Array.from(
                (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
            ).find((b) => b.textContent?.includes('Parse sample')) as HTMLButtonElement;
            expect(btn.disabled).toBe(true);
        });
    });

    it('has no a11y violations (empty and captured states)', async () => {
        const { fixture, state } = create();
        await expectNoA11yViolations(fixture.nativeElement);
        state.captureSample('s.csv', 'a,b\n1,2\n');
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
