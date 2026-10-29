import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AiExplainDialog, cleanGlossaryDefinition, guideTitle, isUserFacingDoc } from './ai-explain.dialog';

/** A term the glossary does not define — the real tool answers 422, which drives the docs fallback. */
const NO_DEFINITION = new HttpErrorResponse({ status: 422, error: { error: { message: 'no canonical definition' } } });
const MODULE_ABSENT = new HttpErrorResponse({ status: 503 });
/** A user guide as a Windows backend names it (back-slashed). */
const WINDOWS_GUIDE = ['okf', 'product', 'module', 'la-core', 'user-guide-link-analysis.md'].join('\\');

describe('AiExplainDialog', () => {
    let runTool: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        runTool = vi.fn();
    });

    function create(terms: string[], definitions?: Record<string, string>) {
        TestBed.configureTestingModule({
            imports: [AiExplainDialog],
            providers: [
                provideNoopAnimations(),
                { provide: AgentService, useValue: { runTool } },
                { provide: MAT_DIALOG_DATA, useValue: { screen: 'Pipelines', terms, definitions } },
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
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Definition of Pipeline.');
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
                : of({ hits: [{ file: 'okf/product/x/user-guide-x.md', line: 12, snippet: 'a mention of Widget' }] }),
        );
        const fixture = create(['Widget']);

        expect(runTool).toHaveBeenCalledWith('docs_search', { query: 'Widget' });
        expect(fixture.componentInstance.entries()[0].citations).toHaveLength(1);
        // The citation is what makes the fallback trustworthy: the guide is named, the path is in the tooltip.
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('No definition. Mentioned in the guides:');
        expect(el.textContent).toContain('User guide: x');
        expect(el.querySelector('li')?.getAttribute('title')).toBe('okf/product/x/user-guide-x.md:12');
    });

    it('a pane-supplied definition wins: the glossary and the docs are never asked for that term (X2)', () => {
        runTool.mockReturnValue(of({ term: 'Link', definition: 'raw row `LA-10` ⛔ never' }));
        const fixture = create(['Link'], { Link: 'A call or transaction between two Entities.' });

        expect(runTool).not.toHaveBeenCalled();
        expect(fixture.componentInstance.entries()[0].definition).toBe('A call or transaction between two Entities.');
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('No definition');
    });

    it('with the intelligence module absent (503) it still shows the definitions the pane supplied', () => {
        runTool.mockReturnValue(throwError(() => MODULE_ABSENT));
        const fixture = create(['Entity', 'Link'], { Entity: 'A thing on the graph.', Link: 'A call between two.' });
        const el = fixture.nativeElement as HTMLElement;

        expect(el.textContent).not.toContain('Definitions unavailable');
        expect(el.textContent).toContain('A thing on the graph.');
        expect(el.textContent).toContain('A call between two.');
        expect(el.querySelector('[data-testid="explain-source"]')?.textContent?.trim()).toBe('Terms used on this screen.');
    });

    it('cleans a glossary answer before showing it', () => {
        runTool.mockReturnValue(of({ term: 'Dossier', definition: 'The *report* (`GET /inv/x`) of the `LA-10` thing. ⛔ Not a Case.' }));
        const fixture = create(['Dossier']);
        expect(fixture.componentInstance.entries()[0].definition).toBe('The report of the thing.');
    });

    it('never cites a deployment, operations or internal README in the docs fallback', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'glossary_lookup'
                ? throwError(() => NO_DEFINITION)
                : of({
                      hits: [
                          { file: 'deploy/README.md', line: 3, snippet: 'Keycloak realm link' },
                          { file: 'ops\\runbook.md', line: 9, snippet: 'WSO2 gateway link' },
                          { file: WINDOWS_GUIDE, line: 40, snippet: 'a Link joins' },
                      ],
                  }),
        );
        const fixture = create(['Link']);

        const cited = fixture.componentInstance.entries()[0].citations!.map((c) => c.file);
        expect(cited).toEqual([WINDOWS_GUIDE]);
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Keycloak');
    });

    it('treats only the glossary and the user and admin guides as user-facing docs', () => {
        expect(isUserFacingDoc('GLOSSARY.md')).toBe(true);
        expect(isUserFacingDoc('USER_GUIDE.md')).toBe(true);
        expect(isUserFacingDoc('okf/product/module/la-core/admin-guide.md')).toBe(true);
        expect(isUserFacingDoc('README.md')).toBe(false);
        expect(isUserFacingDoc('okf/product/layer/platform/spec-build-test.md')).toBe(false);
        expect(isUserFacingDoc('superpower/la-plan.md')).toBe(false);
        // history is never current guidance, even a file there named like a guide
        expect(isUserFacingDoc('archived-documents/superpower-reviews/user-guide-audit.md')).toBe(false);
        expect(isUserFacingDoc('docs/archived-documents/plans-archive/admin-guide-old.md')).toBe(false);
        expect(isUserFacingDoc(['archived-documents', 'x', 'GLOSSARY.md'].join('\\'))).toBe(false);
    });

    it('degrades to an explanation on 503 instead of failing, and does not retry per term', () => {
        runTool.mockReturnValue(throwError(() => MODULE_ABSENT));
        const fixture = create(['Pipeline', 'Step']);

        expect(fixture.componentInstance.unavailable()).toBe(true);
        // A 503 is the module being absent, so the docs fallback cannot help either — one call per term.
        expect(runTool).toHaveBeenCalledTimes(2);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Definitions are unavailable here. Everything else works.');
    });

    it('renders a term that resolves to nothing rather than dropping it', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'glossary_lookup' ? throwError(() => NO_DEFINITION) : throwError(() => NO_DEFINITION),
        );
        const fixture = create(['Nonsense']);

        expect(fixture.componentInstance.entries()).toHaveLength(1);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('No definition found.');
    });

    it('has no a11y violations', async () => {
        runTool.mockReturnValue(of({ term: 'Pipeline', definition: 'a DAG of Steps' }));
        const fixture = create(['Pipeline']);

        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('cleanGlossaryDefinition (LA-UI-TEXT-PASS-1 X2)', () => {
    /** A nasty GLOSSARY row with every kind of engineer markup the About used to print. */
    const NASTY =
        '*(Link Analysis; `/inv/investigations`, `D-E1`)* — The Link Analysis **enquiry object**: an ordered, replayable log ' +
        'of analyst ops (`seed`, `expand`, `exclude`) over a graph \\| per [Space](okf/x.md), plus the Working Set it ' +
        'evaluates to (`InvestigationRoutes`, `LA-10`, GLOSSARY-CASE-1). ⛔ It is a child of a Case. ✅ Backend built ' +
        '(internal document - not shipped). Named by operator decision `D16`.';

    it('renders a nasty row as one clean, complete sentence', () => {
        const clean = cleanGlossaryDefinition(NASTY);
        expect(clean).toBe(
            'The Link Analysis enquiry object: an ordered, replayable log of analyst ops (seed, expand, exclude) over a graph per Space, plus the Working Set it evaluates to.',
        );
        for (const junk of ['`', '*', '⛔', '✅', '\\|', '|', 'D-E1', 'LA-10', 'GLOSSARY-CASE-1', '/inv', 'internal document', 'Named by', 'InvestigationRoutes', '()'])
            expect(clean, junk).not.toContain(junk);
    });

    it('keeps the first sentence only, and does not split on e.g.', () => {
        expect(cleanGlossaryDefinition('A list of keys, e.g. phone numbers. Second sentence here.')).toBe(
            'A list of keys, e.g. phone numbers.',
        );
    });

    it('adds the closing full stop, capitalises, and cuts an over-long sentence at a word with an ellipsis', () => {
        expect(cleanGlossaryDefinition('a thing on the graph')).toBe('A thing on the graph.');
        const long = cleanGlossaryDefinition('word '.repeat(80).trim());
        expect(long.length).toBeLessThanOrEqual(221);
        expect(long.endsWith('word…')).toBe(true);
    });

    it('keeps SHA-256 and answers blank for a row with nothing readable', () => {
        expect(cleanGlossaryDefinition('A SHA-256 chain of custody.')).toBe('A SHA-256 chain of custody.');
        expect(cleanGlossaryDefinition('⛔ Never this.')).toBe('');
        expect(cleanGlossaryDefinition('`LA-10` (`/inv/x`)')).toBe('');
    });

    it('names cited docs by title', () => {
        expect(guideTitle('GLOSSARY.md')).toBe('Glossary');
        expect(guideTitle(['okf', 'user-guide-link-analysis.md'].join('\\'))).toBe('User guide: link analysis');
        expect(guideTitle('okf/product/module/la-core/admin-guide.md')).toBe('Admin guide');
    });
});
