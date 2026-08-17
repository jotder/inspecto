import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { PipelineDryRunPanelComponent } from './pipeline-dry-run-panel.component';
import { PipelineDryRunResult, PipelinesService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';

describe('PipelineDryRunPanelComponent', () => {
    let api: { dryRunAuthored: ReturnType<typeof vi.fn> };

    beforeEach(() => {
        api = {
            dryRunAuthored: vi
                .fn()
                .mockReturnValue(of({ seedNode: 'src', nodes: [], sinks: [] } as PipelineDryRunResult)),
        };
        TestBed.configureTestingModule({
            imports: [PipelineDryRunPanelComponent],
            providers: [provideNoopAnimations(), { provide: PipelinesService, useValue: api }],
        });
    });

    it('parses the sample and maps the result', () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'demo');
        const cmp = fixture.componentInstance;
        cmp.sampleText.setValue('[{"amt":"150"}]');
        cmp.run();
        expect(api.dryRunAuthored).toHaveBeenCalledWith('demo', [{ amt: '150' }]);
        expect(cmp.result()?.seedNode).toBe('src');
    });

    it('rejects invalid JSON without calling the API', () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'demo');
        const cmp = fixture.componentInstance;
        cmp.sampleText.setValue('not json');
        cmp.run();
        expect(api.dryRunAuthored).not.toHaveBeenCalled();
        expect(cmp.error()).toBeTruthy();
    });

    /**
     * DRYRUN-2: a warning must be VISIBLE. The whole complaint was that an empty result reads as success
     * in the UI, so asserting the signal alone would not close it — this asserts the rendered text.
     */
    it('renders a warning from a run that reached nothing', () => {
        api.dryRunAuthored.mockReturnValue(
            of({
                seedNode: 'src',
                nodes: [],
                sinks: [],
                warnings: ['the sample reached no node past the seed'],
            } as PipelineDryRunResult),
        );
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'demo');
        fixture.componentInstance.run();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('the sample reached no node past the seed');
    });

    /** An older server omits `warnings` entirely — the panel must not render an empty alert for it. */
    it('renders no warning when the server sends none', () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'demo');
        fixture.componentInstance.run();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[role="status"]')).toBeNull();
    });

    /**
     * The sample strip promises the captured sample follows the builder through every test. Before this
     * the dry-run was the one test that broke it: a builder who had just parsed their own file had to
     * hand-type JSON rows here.
     */
    it('fills the sample box from the rows the parse step produced', () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'demo');
        fixture.componentRef.setInput('capturedRows', [{ acct: 'A1' }, { acct: 'A2' }]);
        fixture.detectChanges();
        const button = [...fixture.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) =>
            b.textContent?.includes('Use the captured sample'),
        ) as HTMLButtonElement;
        expect(button.textContent).toContain('2 rows');
        button.click();
        fixture.componentInstance.run();
        expect(api.dryRunAuthored).toHaveBeenCalledWith('demo', [{ acct: 'A1' }, { acct: 'A2' }]);
    });

    /** No thread, or a thread that never parsed: the affordance must not appear at all. */
    it('offers no captured sample when the thread has no rows', () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'demo');
        fixture.detectChanges();
        const labels = [...fixture.nativeElement.querySelectorAll('button')].map(
            (b: HTMLButtonElement) => b.textContent ?? '',
        );
        expect(labels.some((t: string) => t.includes('Use the captured sample'))).toBe(false);
    });

    /**
     * The panel is mounted once and outlives the tab switch. Found by driving the editor: after a
     * dry-run on one pipeline, opening another still showed "sink → <the other pipeline>" under the new
     * tab's name.
     */
    it('drops a result when the selected pipeline changes', () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.componentRef.setInput('pipelineId', 'first');
        fixture.detectChanges();
        fixture.componentInstance.run();
        expect(fixture.componentInstance.result()).not.toBeNull();

        fixture.componentRef.setInput('pipelineId', 'second');
        fixture.detectChanges();
        expect(fixture.componentInstance.result()).toBeNull();
    });

    it('is a11y-clean with no pipeline selected', async () => {
        const fixture = TestBed.createComponent(PipelineDryRunPanelComponent);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
