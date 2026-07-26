import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AiStatusDialog, AiStatusData } from './ai-status.dialog';

const MODULE_ABSENT = new HttpErrorResponse({ status: 503 });
const UNKNOWN_PIPELINE = new HttpErrorResponse({ status: 422, error: { error: { message: 'unknown pipeline' } } });

const BUILT_TIMELINE = {
    count: 2,
    truncated: false,
    timeline: [
        { at: '2026-07-26T10:00:00Z', kind: 'signal', summary: 'batch committed', ref: 'orders', severity: 'info' },
        { at: '2026-07-26T11:00:00Z', kind: 'signal', summary: 'reject rate breached', ref: 'orders', severity: 'error' },
    ],
};

describe('AiStatusDialog', () => {
    let runTool: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        runTool = vi.fn();
    });

    function create(data: AiStatusData) {
        TestBed.configureTestingModule({
            imports: [AiStatusDialog],
            providers: [
                provideNoopAnimations(),
                { provide: AgentService, useValue: { runTool } },
                { provide: MAT_DIALOG_DATA, useValue: data },
                { provide: MatDialogRef, useValue: { close: vi.fn() } },
            ],
        });
        const fixture = TestBed.createComponent(AiStatusDialog);
        fixture.detectChanges();
        return fixture;
    }

    it('reads live state and the focused window for a pipeline', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'status_get'
                ? of({ name: 'orders', paused: false, committedBatches: 12 })
                : of(BUILT_TIMELINE),
        );
        const fixture = create({ label: 'reject-rate-high', pipelineId: 'orders' });

        expect(runTool).toHaveBeenCalledWith('status_get', { pipelineId: 'orders' });
        // `focus` is what makes a row's button show THAT row's activity, not the deployment's.
        expect(runTool).toHaveBeenCalledWith('timeline_build', { sinceMinutes: 1440, focus: 'orders' });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('12 committed batches');
        expect(text).toContain('reject rate breached');
    });

    it('shows newest activity first — the operator is asking about the latest state', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'status_get' ? of({ name: 'orders', paused: false, committedBatches: 1 }) : of(BUILT_TIMELINE),
        );
        const fixture = create({ label: 'orders', pipelineId: 'orders' });

        expect(fixture.componentInstance.entries().map((e) => e.summary)).toEqual([
            'reject rate breached',
            'batch committed',
        ]);
    });

    it('prefers the exact causal chain when the pane has a correlationId', () => {
        runTool.mockReturnValue(
            of({
                correlationId: 'b1',
                count: 1,
                timeline: [
                    { signalId: 's1', at: '2026-07-26T10:00:00Z', type: 'BATCH_FAILED', severity: 'error', message: 'boom', causedBy: null },
                ],
            }),
        );
        const fixture = create({ label: 'INC-42', correlationId: 'b1' });

        expect(runTool).toHaveBeenCalledWith('signal_timeline', { correlationId: 'b1' });
        // The windowed tool is NOT also called — the chain is the exact answer, not everything nearby.
        expect(runTool.mock.calls.map((c) => c[0])).toEqual(['signal_timeline']);
        expect(fixture.componentInstance.timelineHeading()).toBe('What led to this');
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('boom');
    });

    it('never invokes a mutating tool — every call is a non-mutating read', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'status_get' ? of({ name: 'orders', paused: false, committedBatches: 0 }) : of(BUILT_TIMELINE),
        );
        create({ label: 'orders', pipelineId: 'orders' });

        expect(runTool.mock.calls.map((c) => c[0]).sort()).toEqual(['status_get', 'timeline_build']);
    });

    it('skips the status half entirely when the pane has no pipeline', () => {
        runTool.mockReturnValue(of(BUILT_TIMELINE));
        const fixture = create({ label: 'INC-42' });

        expect(runTool.mock.calls.map((c) => c[0])).toEqual(['timeline_build']);
        expect(fixture.componentInstance.status()).toBeNull();
    });

    it('degrades the two halves independently — a dead status read keeps the timeline', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'status_get' ? throwError(() => UNKNOWN_PIPELINE) : of(BUILT_TIMELINE),
        );
        const fixture = create({ label: 'orders', pipelineId: 'orders' });

        expect(fixture.componentInstance.status()).toBeNull();
        expect(fixture.componentInstance.unavailable()).toBe(false);
        expect(fixture.componentInstance.entries()).toHaveLength(2);
    });

    it('explains itself once on 503 rather than hard-failing', () => {
        runTool.mockReturnValue(throwError(() => MODULE_ABSENT));
        const fixture = create({ label: 'orders', pipelineId: 'orders' });

        expect(fixture.componentInstance.unavailable()).toBe(true);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('AI assistance unavailable');
    });

    it('says nothing was recorded rather than inventing activity', () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'status_get'
                ? of({ name: 'orders', paused: true, committedBatches: 0 })
                : of({ count: 0, truncated: false, timeline: [] }),
        );
        const fixture = create({ label: 'orders', pipelineId: 'orders' });

        // Honest silence is the answer. This is the invariant that keeps the affordance trustworthy.
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nothing was recorded for orders');
    });

    it('has no a11y violations', async () => {
        runTool.mockImplementation((tool: string) =>
            tool === 'status_get' ? of({ name: 'orders', paused: false, committedBatches: 3 }) : of(BUILT_TIMELINE),
        );
        const fixture = create({ label: 'orders', pipelineId: 'orders' });

        await expectNoA11yViolations(fixture.nativeElement);
    });
});
