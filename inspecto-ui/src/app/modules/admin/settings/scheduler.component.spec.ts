import { describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';

import { LensService, SchedulerSettingsService, SchedulerView } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SchedulerSettingsComponent } from './scheduler.component';

const VIEW: SchedulerView = {
    system: { maxConcurrentConsignments: 16, source: 'file' },
    space: { id: 'default', maxConcurrentConsignments: 0, source: 'default' },
    cores: 8,
    live: {
        system_cap: 16,
        system_in_flight: 2,
        space_in_flight: { default: 2 },
        pipelines: { cdr_ingest: { space: 'default', in_flight: 2, waiting: 5, priority: 3 } },
    },
};

describe('SchedulerSettingsComponent', () => {
    const setup = async (opts?: { canOperate?: boolean; view?: SchedulerView; fail?: boolean }) => {
        const api = {
            view: vi.fn(() =>
                opts?.fail ? throwError(() => ({ status: 503 })) : of(opts?.view ?? VIEW),
            ),
            saveSystem: vi.fn((cap: number) =>
                of({ ...VIEW, system: { maxConcurrentConsignments: cap, source: 'file' as const } }),
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
        const inputs = Array.from(el.querySelectorAll('input[type="number"]'));
        // system cap · intake max · intake floor · space cap · poll cadence · acquire cadence
        expect(inputs).toHaveLength(6);
        await expectNoA11yViolations(el);
    });

    it('saves the server cap through the service and reports the hot-apply', async () => {
        const { fixture, api, toastr } = await setup();
        const c = fixture.componentInstance;
        c.form.patchValue({ system: 12 });
        c.saveSystem();
        expect(api.saveSystem).toHaveBeenCalledWith(12, {
            maxFilesPerCycle: null,
            minFilesPerCycle: null,
            adaptive: null,
        });
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
});
