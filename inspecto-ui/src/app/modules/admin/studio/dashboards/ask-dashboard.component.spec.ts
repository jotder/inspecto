import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentService, BiQueryService, ComponentsService, LensService, SessionService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AskDashboardComponent, widgetIdFromCaption } from './ask-dashboard.component';

const CAPTION = 'Sum of revenue by region, last 8 weeks';

/** The tool's real draft shape (WidgetFromQuestion.build): kind/clean/findings/draft/query/caption/persisted. */
function toolValue(over: Record<string, unknown> = {}) {
    return {
        kind: 'widget',
        id: null,
        clean: true,
        findings: [],
        draft: {
            vizType: 'bar',
            datasetId: 'sales',
            controls: { x: [{ field: 'region' }], y: [{ agg: 'sum', field: 'revenue' }] },
            options: { title: CAPTION },
        },
        query: {
            dataset: 'sales',
            measures: [{ agg: 'sum', field: 'revenue' }],
            groupBy: ['region'],
            window: { field: 'order_date', days: 56, asOf: '2026-11-27' },
        },
        caption: CAPTION,
        persisted: false,
        ...over,
    };
}

const RESULT = {
    resultSet: {
        columns: [
            { name: 'region', type: 'VARCHAR' },
            { name: 'sum_revenue', type: 'DOUBLE' },
        ],
        rowCount: 2,
    },
    rows: [
        { region: 'North', sum_revenue: 1200 },
        { region: 'South', sum_revenue: 800 },
    ],
    statistics: { rowCount: 2, elapsedMs: 3, truncated: false },
    sql: '',
};

describe('AskDashboardComponent', () => {
    let deriveTool: ReturnType<typeof vi.fn>;
    let biRun: ReturnType<typeof vi.fn>;
    let create: ReturnType<typeof vi.fn>;
    let toastr: { success: ReturnType<typeof vi.fn>; info: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        deriveTool = vi.fn();
        biRun = vi.fn().mockReturnValue(of(RESULT));
        create = vi.fn().mockReturnValue(of({ type: 'widget', name: 'x' }));
        toastr = { success: vi.fn(), info: vi.fn(), error: vi.fn() };
    });

    function setup(features: Record<string, boolean> = { intelligence: true }, existing: string[] = []) {
        TestBed.configureTestingModule({
            imports: [AskDashboardComponent],
            providers: [
                provideNoopAnimations(),
                { provide: AgentService, useValue: { deriveTool, runTool: vi.fn() } },
                { provide: BiQueryService, useValue: { run: biRun } },
                { provide: ComponentsService, useValue: { create } },
                { provide: SessionService, useValue: { features: signal(features) } },
                { provide: LensService, useValue: { canAuthorWorkbench: signal(true) } },
                { provide: ToastrService, useValue: toastr },
            ],
        });
        const fixture = TestBed.createComponent(AskDashboardComponent);
        fixture.componentRef.setInput('existingIds', existing);
        fixture.detectChanges();
        return fixture;
    }

    const el = (f: ReturnType<typeof setup>) => f.nativeElement as HTMLElement;
    const button = (f: ReturnType<typeof setup>, text: string) =>
        Array.from(el(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === text) as
            | HTMLButtonElement
            | undefined;

    function ask(f: ReturnType<typeof setup>, sentence = 'revenue by region for the last 8 weeks') {
        const input = el(f).querySelector('input') as HTMLInputElement;
        input.value = sentence;
        input.dispatchEvent(new Event('input'));
        f.detectChanges();
        button(f, 'Draft it')!.click();
        f.detectChanges();
    }

    it('is absent without the intelligence feature in /bootstrap', () => {
        const f = setup({});
        expect(el(f).querySelector('inspecto-ai-assist')).toBeNull();
        expect(el(f).textContent?.trim()).toBe('');
    });

    it('question → draft → live preview as the caller → Save writes the Widget through the components route', () => {
        deriveTool.mockReturnValue(of({ value: toolValue(), derivedArgs: { measures: [] } }));
        const f = setup();
        ask(f);

        expect(deriveTool).toHaveBeenCalledWith('widget_from_question', 'revenue by region for the last 8 weeks', {
            preferred: [],
        });
        // the templated caption — no model-written answer
        expect(el(f).querySelector('[data-testid="ask-caption"]')?.textContent).toBe(CAPTION);
        // the preview ran the tool's /bi/query body through the caller's own request
        expect(biRun).toHaveBeenCalledWith(toolValue().query);
        expect(el(f).querySelector('[data-testid="ask-rows"]')?.textContent).toContain('1,200');

        // a Widget carries no time window: the reader is told
        expect(el(f).querySelector('[data-testid="ask-not-saved"]')?.textContent).toContain('time window');

        const saved: unknown[] = [];
        f.componentInstance.saved.subscribe((s) => saved.push(s));
        expect(create).not.toHaveBeenCalled(); // Ask never writes by itself
        button(f, 'Save as a Widget')!.click();
        expect(create).toHaveBeenCalledTimes(1);
        const [type, body] = create.mock.calls[0] as [string, Record<string, unknown>];
        expect(type).toBe('widget');
        expect(body).toMatchObject({
            id: 'sum_of_revenue_by_region_last_8_weeks',
            datasetId: 'sales',
            vizType: 'bar',
        });
        expect(saved).toEqual([{ id: 'sum_of_revenue_by_region_last_8_weeks', pending: false }]);
        expect(toastr.success).toHaveBeenCalled();
    });

    it('holds under a Pending Change policy: a 202 is said in words and reported as pending, never as saved', () => {
        deriveTool.mockReturnValue(of({ value: toolValue(), derivedArgs: {} }));
        create.mockReturnValue(of({ status: 'pending', written: false, pendingChange: { id: 'pc-1' } }));
        const f = setup();
        ask(f);
        const saved: unknown[] = [];
        f.componentInstance.saved.subscribe((s) => saved.push(s));
        button(f, 'Save as a Widget')!.click();

        expect(saved).toEqual([{ id: 'sum_of_revenue_by_region_last_8_weeks', pending: true }]);
        expect(toastr.success).not.toHaveBeenCalled();
        expect(toastr.info.mock.calls[0][0]).toContain('sent for approval');
        expect(toastr.info.mock.calls[0][0]).toContain('Nothing is saved');
    });

    it('shows a refusal from the tool in words, in place, and keeps the question', () => {
        const words = "I can't see a column called 'margin'";
        deriveTool.mockReturnValue(
            throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: words } } })),
        );
        const f = setup();
        ask(f, 'margin by region');

        expect(el(f).textContent).toContain("That can't be drafted");
        expect(el(f).textContent).toContain(words);
        expect(toastr.error).not.toHaveBeenCalled();
        expect(biRun).not.toHaveBeenCalled();
        expect((el(f).querySelector('input') as HTMLInputElement).value).toBe('margin by region');
        expect(el(f).querySelector('[data-testid="ask-preview"]')).toBeNull();
    });

    it('a failed preview is shown, and the draft can still be reviewed', () => {
        deriveTool.mockReturnValue(of({ value: toolValue(), derivedArgs: {} }));
        biRun.mockReturnValue(
            throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: 'masked column' } } })),
        );
        const f = setup();
        ask(f);
        expect(el(f).textContent).toContain('The preview could not run');
        expect(el(f).textContent).toContain('masked column');
    });

    it('zero rows says so and does not guess a filter value', () => {
        deriveTool.mockReturnValue(of({ value: toolValue(), derivedArgs: {} }));
        biRun.mockReturnValue(of({ ...RESULT, rows: [] }));
        const f = setup();
        ask(f);
        expect(el(f).querySelector('[data-testid="ask-empty"]')?.textContent).toContain('exactly as written');
    });

    it('refuses a name that is already a Widget, and suggests a free one', () => {
        deriveTool.mockReturnValue(of({ value: toolValue(), derivedArgs: {} }));
        const f = setup({ intelligence: true }, ['sum_of_revenue_by_region_last_8_weeks']);
        ask(f);
        expect(f.componentInstance.name()).toBe('sum_of_revenue_by_region_last_8_weeks_2');
        f.componentInstance.name.set('sum_of_revenue_by_region_last_8_weeks');
        f.detectChanges();
        expect(button(f, 'Save as a Widget')!.disabled).toBe(true);
        expect(f.componentInstance.nameError()).toContain('already exists');
    });

    it('a save error is toasted and nothing is reported saved', () => {
        deriveTool.mockReturnValue(of({ value: toolValue(), derivedArgs: {} }));
        create.mockReturnValue(
            throwError(() => new HttpErrorResponse({ status: 409, error: { error: { message: 'already pending' } } })),
        );
        const f = setup();
        ask(f);
        const saved: unknown[] = [];
        f.componentInstance.saved.subscribe((s) => saved.push(s));
        button(f, 'Save as a Widget')!.click();
        expect(saved).toEqual([]);
        expect(toastr.error).toHaveBeenCalled();
    });

    it('has no accessibility violations with a draft on screen', async () => {
        deriveTool.mockReturnValue(of({ value: toolValue(), derivedArgs: { dataset: 'sales' } }));
        const f = setup();
        ask(f);
        await expectNoA11yViolations(el(f));
    });

    it('builds a Widget id from the caption', () => {
        expect(widgetIdFromCaption('Sum of revenue by region, last 8 weeks')).toBe(
            'sum_of_revenue_by_region_last_8_weeks',
        );
        expect(widgetIdFromCaption('  !!  ')).toBe('widget');
    });
});
