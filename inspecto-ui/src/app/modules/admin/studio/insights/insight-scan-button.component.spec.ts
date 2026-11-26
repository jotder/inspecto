import { ChangeDetectionStrategy, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ToastrService } from 'ngx-toastr';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { JobsService } from 'app/inspecto/api/jobs.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InsightScanButtonComponent } from './insight-scan-button.component';

interface Opts {
    jobs?: { name: string; type: string; profile?: string }[] | 'none';
    operate?: boolean;
    author?: boolean;
    confirm?: boolean;
}

function create(opts: Opts = {}) {
    const all =
        opts.jobs === 'none' ? [] : (opts.jobs ?? [{ name: 'scan_sales', type: 'insight.scan', profile: 'sales' }]);
    const jobs = {
        list: vi.fn(() =>
            opts.jobs === 'none'
                ? throwError(() => ({ status: 404 }))
                : of(all.map((j) => ({ name: j.name, type: j.type, enabled: true }))),
        ),
        get: vi.fn((name: string) => {
            const j = all.find((x) => x.name === name)!;
            return of({ name, type: j.type, enabled: true, params: j.profile ? { profile: j.profile } : {} });
        }),
        trigger: vi.fn(() => of({ runId: 'run-1' })),
        run: vi.fn(() => of({ status: 'SUCCESS' })),
        create: vi.fn((b: { name: string }) => of({ name: b.name, type: 'insight.scan', enabled: true })),
    };
    const confirm = { confirm: vi.fn(async () => opts.confirm ?? true) };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [InsightScanButtonComponent],
        providers: [
            { provide: JobsService, useValue: jobs },
            { provide: InspectoConfirmService, useValue: confirm },
            { provide: ToastrService, useValue: toastr },
            {
                provide: LensService,
                useValue: {
                    canOperateRuns: signal(opts.operate ?? true),
                    canAuthorWorkbench: signal(opts.author ?? true),
                },
            },
        ],
    });
    TestBed.overrideComponent(InsightScanButtonComponent, {
        set: { changeDetection: ChangeDetectionStrategy.Eager },
    });
    const fixture = TestBed.createComponent(InsightScanButtonComponent);
    fixture.componentRef.setInput('profile', 'sales');
    const finished = vi.fn();
    fixture.componentInstance.finished.subscribe(finished);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const btn = (id: string) => el.querySelector<HTMLButtonElement>(`[data-testid="${id}"]`);
    return { fixture, el, btn, jobs, confirm, finished };
}

describe('InsightScanButtonComponent (Run scan now)', () => {
    afterEach(() => vi.useRealTimers());

    it('finds the insight.scan Job naming the Profile, triggers it and reports the finished run', async () => {
        const { fixture, btn, jobs, finished, el } = create({
            jobs: [
                { name: 'other', type: 'report' },
                { name: 'scan_west', type: 'insight.scan', profile: 'west' },
                { name: 'scan_sales', type: 'insight.scan', profile: 'sales' },
            ],
        });
        expect(jobs.get).not.toHaveBeenCalledWith('other');
        const run = btn('run-scan')!;
        expect(run.disabled).toBe(false);
        expect(run.getAttribute('title')).toContain('scan_sales');
        await expectNoA11yViolations(el); // before fake timers: axe waits on real ones
        vi.useFakeTimers();
        run.click();
        expect(jobs.trigger).toHaveBeenCalledWith('scan_sales');
        await vi.advanceTimersByTimeAsync(2_000);
        fixture.detectChanges();
        expect(jobs.run).toHaveBeenCalledWith('run-1');
        expect(finished).toHaveBeenCalledTimes(1);
        expect(el.querySelector('[data-testid="scan-status"]')?.textContent).toContain('finished');
    });

    it('is disabled with the reason without the permission to run Jobs', () => {
        const { btn, el } = create({ operate: false });
        expect(btn('run-scan')!.disabled).toBe(true);
        expect(el.textContent).toContain('Needs Run operations.');
    });

    it('with no Job for the Profile, offers to create one and creates it only after the confirmation', async () => {
        const { fixture, btn, jobs, confirm } = create({ jobs: 'none' });
        expect(btn('run-scan')).toBeNull();
        btn('create-scan')!.click();
        await fixture.whenStable();
        expect(confirm.confirm).toHaveBeenCalled();
        expect(jobs.create).toHaveBeenCalledWith({
            name: 'insight_scan_sales',
            type: 'insight.scan',
            enabled: true,
            params: { profile: 'sales' },
        });
        fixture.detectChanges();
        expect(btn('run-scan')).not.toBeNull();
    });

    it('creates nothing when the confirmation is declined', async () => {
        const { fixture, btn, jobs } = create({ jobs: 'none', confirm: false });
        btn('create-scan')!.click();
        await fixture.whenStable();
        expect(jobs.create).not.toHaveBeenCalled();
    });

    it('cannot create without the permission to author, and says so', () => {
        const { btn, el } = create({ jobs: 'none', author: false });
        expect(btn('create-scan')!.disabled).toBe(true);
        expect(el.textContent).toContain('Needs Workbench author.');
    });
});
