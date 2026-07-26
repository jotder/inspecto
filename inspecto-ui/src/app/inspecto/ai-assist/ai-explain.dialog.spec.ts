import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AiExplainDialog } from './ai-explain.dialog';

/** A term the glossary does not define — the real tool answers 422, which drives the docs fallback. */
const NO_DEFINITION = new HttpErrorResponse({ status: 422, error: { error: { message: 'no canonical definition' } } });
const MODULE_ABSENT = new HttpErrorResponse({ status: 503 });

describe('AiExplainDialog', () => {
    let runTool: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        runTool = vi.fn();
    });

    function create(terms: string[]) {
        TestBed.configureTestingModule({
            imports: [AiExplainDialog],
            providers: [
                provideNoopAnimations(),
                { provide: AgentService, useValue: { runTool } },
                { provide: MAT_DIALOG_DATA, useValue: { screen: 'Pipelines', terms } },
                { provide: MatDialogRef, useValue: { close: vi.fn() } },
            ],
        });
        const fixture = TestBed.createComponent(AiExplainDialog);
        fixture.detectChanges();
        return fixture;
    }

    it('resolves each declared term through glossary_lookup, in the pane-declared order', () => {
        runTool.mockImplementation((_tool: string, args: { term: string }) =>
            of({ term: args.term, definition: `definition of ${args.term}` }),
        );
        const fixture = create(['Pipeline', 'Step']);

        // The pane declares the terms; nothing is typed and nothing is inferred.
        expect(runTool).toHaveBeenCalledWith('glossary_lookup', { term: 'Pipeline' });
        expect(runTool).toHaveBeenCalledWith('glossary_lookup', { term: 'Step' });
        expect(fixture.componentInstance.entries().map((e) => e.term)).toEqual(['Pipeline', 'Step']);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('definition of Pipeline');
    });

    it('never invokes a mutating tool — the whole surface is two read tools', () => {
        runTool.mockReturnValue(of({ term: 'Pipeline', definition: 'a DAG of Steps' }));
        create(['Pipeline']);

        const invoked = runTool.mock.calls.map((call) => call[0]);
        expect(invoked).toEqual(['glossary_lookup']);
    });

    it('falls back to docs_search for a term with no canonical definition', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'glossary_lookup'
                ? throwError(() => NO_DEFINITION)
                : of({ hits: [{ file: 'okf/x.md', line: 12, snippet: 'a mention of Widget' }] }),
        );
        const fixture = create(['Widget']);

        expect(runTool).toHaveBeenCalledWith('docs_search', { query: 'Widget' });
        expect(fixture.componentInstance.entries()[0].citations).toHaveLength(1);
        // The citation is what makes the fallback trustworthy — it is shown, not just the snippet.
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('okf/x.md:12');
    });

    it('degrades to an explanation on 503 instead of failing, and does not retry per term', () => {
        runTool.mockReturnValue(throwError(() => MODULE_ABSENT));
        const fixture = create(['Pipeline', 'Step']);

        expect(fixture.componentInstance.unavailable()).toBe(true);
        // A 503 is the module being absent, so the docs fallback cannot help either — one call per term.
        expect(runTool).toHaveBeenCalledTimes(2);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('AI assistance unavailable');
    });

    it('renders a term that resolves to nothing rather than dropping it', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'glossary_lookup' ? throwError(() => NO_DEFINITION) : throwError(() => NO_DEFINITION),
        );
        const fixture = create(['Nonsense']);

        expect(fixture.componentInstance.entries()).toHaveLength(1);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('No canonical definition found.');
    });

    it('has no a11y violations', async () => {
        runTool.mockReturnValue(of({ term: 'Pipeline', definition: 'a DAG of Steps' }));
        const fixture = create(['Pipeline']);

        await expectNoA11yViolations(fixture.nativeElement);
    });
});
