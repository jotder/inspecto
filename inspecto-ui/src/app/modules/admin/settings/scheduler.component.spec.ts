import { describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';

import { LensService, SchedulerSettingsService, SchedulerView } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SchedulerSettingsComponent } from './scheduler.component';

const VIEW: SchedulerView = {
    system: {
        maxConcurrentConsignments: 16,
        source: 'file',
        // BACKLOG D11's pair, in the on-by-default state: a bound is in force with nothing stored.
        duckdbMemoryLimit: null,
        duckdbMemoryLimitSource: 'default',
        maxConcurrentJobRuns: 4,
        maxConcurrentJobRunsSource: 'default',
    },
    space: { id: 'default', maxConcurrentConsignments: 0, source: 'default' },
    cores: 8,
    live: {
        system_cap: 16,
        system_in_flight: 2,
        system_free: 14,
        space_in_flight: { default: 2 },
        pipelines: { cdr_ingest: { space: 'default', in_flight: 2, waiting: 5, priority: 3 } },
        throttled: {
            pipelines: [{ pipeline: 'noisy_feed', cap: 250, baseCap: 1000, floor: 50 }],
            total: 1,
            truncated: false,
        },
    },
};

describe('SchedulerSettingsComponent', () => {
    const setup = async (opts?: { canOperate?: boolean; view?: SchedulerView; fail?: boolean }) => {
        const api = {
            view: vi.fn(() =>
                opts?.fail ? throwError(() => ({ status: 503 })) : of(opts?.view ?? VIEW),
            ),
            // The real PUT answers with systemShape() — the values it just stored, with their provenance
            // flipped to `file`. Echo that here: a mock that dropped the saved fields would make the
            // component's (correct) re-seed from the response look like a bug.
            saveSystem: vi.fn(
                (
                    cap: number | null,
                    _intake?: unknown,
                    resources?: { duckdbMemoryLimit: string | null; maxConcurrentJobRuns: number | null },
                ) =>
                    of({
                        ...VIEW,
                        system: {
                            ...VIEW.system,
                            maxConcurrentConsignments: cap,
                            source: 'file' as const,
                            duckdbMemoryLimit: resources?.duckdbMemoryLimit ?? null,
                            duckdbMemoryLimitSource: (resources?.duckdbMemoryLimit
                                ? 'file'
                                : 'default') as SchedulerView['system']['duckdbMemoryLimitSource'],
                            maxConcurrentJobRuns: resources?.maxConcurrentJobRuns ?? 4,
                            maxConcurrentJobRunsSource: (resources?.maxConcurrentJobRuns != null
                                ? 'file'
                                : 'default') as SchedulerView['system']['maxConcurrentJobRunsSource'],
                        },
                    }),
            ),
            saveSpace: vi.fn(() => of(VIEW.space)),
        };
        const toastr = { success: vi.fn(), error: vi.fn() };
        TestBed.configureTestingModule({
            imports: [SchedulerSettingsComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SchedulerSettingsService, useValue: api },
                { provide: ToastrService, useValue: toastr },
                { provide: LensService, useValue: { canOperateRuns: () => opts?.canOperate !== false } },
            ],
        });
        const fixture = TestBed.createComponent(SchedulerSettingsComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        return { fixture, api, toastr };
    };

    it('renders both tiers with their provenance and the live occupancy, accessibly', async () => {
        const { fixture } = await setup();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('Server-wide cap');
        expect(el.textContent).toContain('file');            // system provenance chip
        expect(el.textContent).toContain("This space's cap");
        expect(el.textContent).toContain('cdr_ingest');      // live occupancy row
        expect(el.textContent).toContain('priority 3');
        expect(el.textContent).toContain('14 slot(s) free');
        // S8: a throttled pipeline is visible with the cap it is actually admitting at.
        expect(el.textContent).toContain('Throttled by intake control');
        expect(el.textContent).toContain('noisy_feed');
        expect(el.textContent).toContain('admitting 250 of 1000 files/cycle');
        const inputs = Array.from(el.querySelectorAll('input[type="number"]'));
        // system cap · intake max · intake floor · max concurrent Runs · space cap · poll cadence ·
        // acquire cadence. (The memory limit is a text input — a size string, not a number.)
        expect(inputs).toHaveLength(7);
        await expectNoA11yViolations(el);
    });

    it('saves the server cap through the service and reports the hot-apply', async () => {
        const { fixture, api, toastr } = await setup();
        const c = fixture.componentInstance;
        c.form.patchValue({ system: 12 });
        c.saveSystem();
        expect(api.saveSystem).toHaveBeenCalledWith(
            12,
            {
                maxFilesPerCycle: null,
                minFilesPerCycle: null,
                adaptive: null,
            },
            // The resource pair travels with every server-wide save; nulls are an explicit "keep
            // inheriting the launch default", not an omission.
            { duckdbMemoryLimit: null, maxConcurrentJobRuns: null },
        );
        expect(toastr.success).toHaveBeenCalled();
    });

    it('refuses an out-of-bounds value client-side without calling the server', async () => {
        const { fixture, api } = await setup();
        const c = fixture.componentInstance;
        c.form.patchValue({ system: -1 });
        c.saveSystem();
        expect(api.saveSystem).not.toHaveBeenCalled();
    });

    it('disables both saves without the operate-runs capability and says why', async () => {
        const { fixture } = await setup({ canOperate: false });
        const el: HTMLElement = fixture.nativeElement;
        const buttons = Array.from(el.querySelectorAll('button[type="submit"]')) as HTMLButtonElement[];
        expect(buttons).toHaveLength(2);
        for (const b of buttons) expect(b.disabled).toBe(true);
        expect(el.textContent).toContain('Operate-runs capability required');
    });

    it('degrades to an empty state when the routes do not answer', async () => {
        const { fixture } = await setup({ fail: true });
        expect(fixture.nativeElement.textContent).toContain('Scheduler state unavailable');
    });

    // ── BACKLOG D11: the resource pair ───────────────────────────────────────────────

    it('shows the on-by-default resource caps and names DuckDB’s default when nothing is stored', async () => {
        const { fixture } = await setup();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('Resource caps');
        expect(el.textContent).toContain('4');
        // An operator must be able to tell "a cap is in force but nothing was configured" apart from
        // "no cap at all" — the second is what this pane used to imply.
        expect(el.textContent).toContain('DuckDB’s own default');
    });

    it('does not seed the cap from an inherited value, so a resource-only save cannot seize its provenance', async () => {
        // The provenance-seizure defect (fixed 2026-08-26): -Dscheduler.max.consignments=16 in force,
        // nothing stored. An operator saving ONLY the memory limit must not write the cap into the file.
        const { fixture, api } = await setup({
            view: { ...VIEW, system: { ...VIEW.system, maxConcurrentConsignments: 16, source: 'property' } },
        });
        const c = fixture.componentInstance;
        expect(c.form.controls.system.value).toBeNull();
        // …but the effective value + provenance still render for the operator.
        expect(fixture.nativeElement.textContent).toContain('property');
        c.form.patchValue({ memoryLimit: '2GB' });
        c.saveSystem();
        expect(api.saveSystem).toHaveBeenCalledWith(null, expect.anything(), {
            duckdbMemoryLimit: '2GB',
            maxConcurrentJobRuns: null,
        });
    });

    it('does not seed the space cap from an inherited value either', async () => {
        const { fixture } = await setup(); // VIEW.space is source 'default'
        expect(fixture.componentInstance.spaceForm.controls.space.value).toBeNull();
    });

    it('does not seed the inputs from an inherited value, so a save cannot silently claim ownership', async () => {
        // Served as `property` (a -D flag is set). If the box were pre-filled, the next Save would write
        // that value into scheduler.toon and take ownership away from the flag without the operator
        // ever typing it.
        const { fixture } = await setup({
            view: {
                ...VIEW,
                system: {
                    ...VIEW.system,
                    duckdbMemoryLimit: '512MB',
                    duckdbMemoryLimitSource: 'property',
                    maxConcurrentJobRuns: 6,
                    maxConcurrentJobRunsSource: 'property',
                },
            },
        });
        const c = fixture.componentInstance;
        expect(c.form.controls.memoryLimit.value).toBeNull();
        expect(c.form.controls.jobRuns.value).toBeNull();
        // …but the effective value is still reported on screen, with its provenance.
        expect(fixture.nativeElement.textContent).toContain('512MB');
        expect(fixture.nativeElement.textContent).toContain('property');
    });

    it('seeds the inputs from a stored value so an edit starts from what is in force', async () => {
        const { fixture } = await setup({
            view: {
                ...VIEW,
                system: {
                    ...VIEW.system,
                    duckdbMemoryLimit: '2GB',
                    duckdbMemoryLimitSource: 'file',
                    maxConcurrentJobRuns: 4,
                    maxConcurrentJobRunsSource: 'file',
                },
            },
        });
        const c = fixture.componentInstance;
        expect(c.form.controls.memoryLimit.value).toBe('2GB');
        expect(c.form.controls.jobRuns.value).toBe(4);
    });

    it('sends the trimmed pair, and a blank memory limit as an explicit null', async () => {
        const { fixture, api } = await setup();
        const c = fixture.componentInstance;
        c.form.patchValue({ system: 8, memoryLimit: '  2GB  ', jobRuns: 4 });
        c.saveSystem();
        expect(api.saveSystem).toHaveBeenCalledWith(8, expect.anything(), {
            duckdbMemoryLimit: '2GB',
            maxConcurrentJobRuns: 4,
        });

        // The save re-seeded the form from the response, so the Run bound is still 4 here — clearing the
        // memory limit must not silently drop the other half of the pair.
        api.saveSystem.mockClear();
        c.form.patchValue({ memoryLimit: '   ' });
        c.saveSystem();
        expect(api.saveSystem).toHaveBeenCalledWith(8, expect.anything(), {
            duckdbMemoryLimit: null,
            maxConcurrentJobRuns: 4,
        });
    });

    it('refuses a malformed size string client-side without calling the server', async () => {
        const { fixture, api } = await setup();
        const c = fixture.componentInstance;
        c.form.patchValue({ memoryLimit: 'lots' });
        c.saveSystem();
        expect(api.saveSystem).not.toHaveBeenCalled();
        expect(c.form.controls.memoryLimit.invalid).toBe(true);
    });

    it('keeps the resource-caps section accessible', async () => {
        const { fixture } = await setup();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
