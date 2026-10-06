import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { PendingChangesService } from 'app/inspecto/api/pending-changes.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RiskScoreActionsComponent, RiskScoreChange } from './risk-score-actions.component';
import { RiskScoreHeldBadgeComponent, RiskScoreHeldStore } from './risk-score-held';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [RiskScoreActionsComponent, RiskScoreHeldBadgeComponent],
    template: `
        <app-risk-score-actions [model]="model()" [highThreshold]="70" (changed)="seen.push($event)" />
        <app-risk-score-held-badge model="sim_box" [loads]="true" />
    `,
})
class HostComponent {
    readonly model = signal<string | null>('sim_box');
    readonly seen: RiskScoreChange[] = [];
}

function setup(opts: { author?: boolean; confirm?: boolean; removed?: unknown } = {}) {
    const remove = vi.fn(() => of(opts.removed ?? null));
    const toast = { success: vi.fn(), info: vi.fn(), error: vi.fn() };
    const list = vi.fn(() =>
        of({
            items: [
                { id: 'pc-1', kind: 'risk-score', name: 'sim_box', operation: 'update' },
                { id: 'pc-2', kind: 'dashboard', name: 'sim_box', operation: 'update' },
            ],
            total: 2,
            truncated: false,
        }),
    );
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ComponentsService, useValue: { remove, get: vi.fn() } },
            { provide: LensService, useValue: { canAuthorWorkbench: () => opts.author ?? true } },
            { provide: ToastrService, useValue: toast },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            {
                provide: InspectoConfirmService,
                useValue: { confirmDestructive: vi.fn(async () => opts.confirm ?? true) },
            },
            { provide: PendingChangesService, useValue: { list } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const actions = fixture.debugElement.children[0].componentInstance as RiskScoreActionsComponent;
    return {
        fixture,
        el: fixture.nativeElement as HTMLElement,
        host: fixture.componentInstance,
        actions,
        remove,
        toast,
        list,
    };
}

const buttons = (el: HTMLElement) => Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());

describe('RiskScoreActionsComponent', () => {
    it('offers Edit, Delete and the prefilled per-entity Alert Rule link to an author', async () => {
        const { fixture, el } = setup();
        expect(buttons(el)).toEqual(['Edit', 'Delete']);
        const link = el.querySelector('a[href^="/alerts"]') as HTMLAnchorElement;
        const q = new URL(link.href, 'http://x').searchParams;
        expect(q.get('newRule')).toBe('1');
        expect(q.get('dataset')).toBe('risk_scores_sim_box_latest');
        expect(q.get('by')).toBe('model,entity_key');
        expect(q.get('threshold')).toBe('70');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('hides the write actions from a non-author but keeps the link', () => {
        const { el } = setup({ author: false });
        expect(buttons(el)).toEqual([]);
        expect(el.querySelector('a[href^="/alerts"]')).not.toBeNull();
    });

    it('a held delete (202) says so and reports held, not deleted', async () => {
        const { host, actions, remove, toast } = setup({
            removed: { status: 'pending', written: false, pendingChange: { id: 'pc-9' } },
        });
        await actions.remove('sim_box');
        expect(remove).toHaveBeenCalledWith('risk-score', 'sim_box');
        expect(toast.info).toHaveBeenCalledWith('Delete held for approval as pc-9');
        expect(host.seen).toEqual([{ kind: 'held', id: 'sim_box' }]);
    });

    it('a written delete reports deleted', async () => {
        const { host, actions, toast } = setup();
        await actions.remove('sim_box');
        expect(toast.success).toHaveBeenCalled();
        expect(host.seen).toEqual([{ kind: 'deleted', id: 'sim_box' }]);
    });

    it('a declined confirm deletes nothing', async () => {
        const { host, actions, remove } = setup({ confirm: false });
        await actions.remove('sim_box');
        expect(remove).not.toHaveBeenCalled();
        expect(host.seen).toEqual([]);
    });

    it('shows Awaiting approval only for a risk-score change held on that model', () => {
        const { el, list } = setup();
        expect(list).toHaveBeenCalledWith('pending');
        expect(el.textContent).toContain('Awaiting approval');
        const store = TestBed.inject(RiskScoreHeldStore);
        expect(Object.keys(store.byModel())).toEqual(['sim_box']);
        expect(store.byModel()['sim_box'].id).toBe('pc-1');
    });
});
