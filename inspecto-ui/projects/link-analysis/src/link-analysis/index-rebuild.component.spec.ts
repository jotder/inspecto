import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    GraphRunsService,
    LinkIndexBuildView,
    LinkIndexList,
    LinkIndexSummary,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { IndexRebuildComponent } from './index-rebuild.component';

const ix = (over: Partial<LinkIndexSummary> = {}): LinkIndexSummary => ({
    dataset: 'transfers',
    mapping: { sourceCol: 'from_account', targetCol: 'to_account', kindCol: 'channel', attrCols: ['amount'] },
    version: 1,
    stale: false,
    reason: null,
    fingerprint: 'known',
    plan: { recommended: 'none', appendable: false, reasons: [], added: 0, removed: 0, changed: 0 },
    ...over,
});
const OUT_OF_DATE = ix({
    stale: true,
    reason: "the Dataset's definition changed after the index was built",
    plan: { recommended: 'full', appendable: false, reasons: [], added: 0, removed: 0, changed: 0 },
});

function setup(list: LinkIndexList | null, opt: { allowed?: boolean; start?: () => unknown } = {}) {
    const indexes = signal<LinkIndexList | null>(list);
    const watch = new Subject<LinkIndexBuildView>();
    const runs = {
        indexes,
        loadIndexes: vi.fn(),
        reloadIndexes: vi.fn(),
        startBuild: vi.fn(opt.start ?? (() => of({ buildId: 'ib-1', status: 'QUEUED' }))),
        watchBuild: vi.fn(() => watch),
    };
    TestBed.configureTestingModule({
        imports: [IndexRebuildComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GraphRunsService, useValue: runs },
            { provide: LensService, useValue: { canBuildLinkIndex: signal(opt.allowed ?? true) } },
        ],
    });
    const f: ComponentFixture<IndexRebuildComponent> = TestBed.createComponent(IndexRebuildComponent);
    f.componentRef.setInput('dataset', 'transfers');
    f.detectChanges();
    return { f, runs, watch };
}

const button = (f: ComponentFixture<IndexRebuildComponent>): HTMLButtonElement | null =>
    f.nativeElement.querySelector('[data-testid="index-rebuild-button"]');

/** LA-FULL-CYCLE-TEST-1 phase 4: Rebuild beside an out-of-date link index, for a caller allowed to build it. */
describe('IndexRebuildComponent', () => {
    it('offers nothing while the index is current, not listed, or GET /inv/index cannot answer', () => {
        const { f, runs } = setup(null);
        expect(runs.loadIndexes).toHaveBeenCalled();
        expect(button(f)).toBeNull();
        for (const list of [
            { enabled: true, indexes: [ix()] },
            { enabled: true, indexes: [] },
        ]) {
            runs.indexes.set(list);
            f.detectChanges();
            expect(button(f)).toBeNull();
        }
        runs.indexes.set({ enabled: true, indexes: [OUT_OF_DATE] });
        f.detectChanges();
        expect(button(f)).not.toBeNull();
    });

    it("rebuilds the index's own mapping in the advised mode, then reads every index again", async () => {
        const { f, runs, watch } = setup({ enabled: true, indexes: [OUT_OF_DATE] });
        const b = button(f)!;
        expect(b.textContent!.trim()).toBe('Rebuild');
        expect(b.disabled).toBe(false);
        expect(b.getAttribute('aria-label')).toBe('Rebuild the link index of transfers');
        await expectNoA11yViolations(f.nativeElement);

        b.click();
        f.detectChanges();
        expect(runs.startBuild).toHaveBeenCalledWith({
            dataset: 'transfers',
            sourceCol: 'from_account',
            targetCol: 'to_account',
            kindCol: 'channel',
            attrCols: ['amount'],
            mode: 'full',
        });
        expect(button(f)!.textContent!.trim()).toBe('Rebuilding');
        expect(button(f)!.getAttribute('aria-label')).toBe('Rebuilding the link index of transfers'); // label in name
        expect(button(f)!.disabled).toBe(true);

        watch.next({ buildId: 'ib-1', status: 'COMPLETED' });
        f.detectChanges();
        expect(runs.reloadIndexes).toHaveBeenCalledTimes(1);
        const status = f.nativeElement.querySelector('[data-testid="index-rebuild-status"]') as HTMLElement;
        expect(status.getAttribute('role')).toBe('status');
        expect(status.textContent!.trim()).toBe('The link index of transfers is rebuilt.');
        expect(button(f)!.disabled).toBe(false);
    });

    it('is disabled and names the permission for a caller who may not build', async () => {
        const { f, runs } = setup({ enabled: true, indexes: [OUT_OF_DATE] }, { allowed: false });
        const b = button(f)!;
        expect(b.disabled).toBe(true);
        expect(b.getAttribute('aria-label')).toBe('Rebuild. Needs Build link index.');
        b.click();
        expect(runs.startBuild).not.toHaveBeenCalled();
        await expectNoA11yViolations(f.nativeElement);
    });

    it('says why a refused rebuild did not start, in the build sentences', () => {
        const { f } = setup(
            { enabled: true, indexes: [OUT_OF_DATE] },
            { start: () => throwError(() => new HttpErrorResponse({ status: 503 })) },
        );
        button(f)!.click();
        f.detectChanges();
        const alert = f.nativeElement.querySelector('[data-testid="index-rebuild-error"]') as HTMLElement;
        expect(alert.getAttribute('role')).toBe('alert');
        expect(alert.textContent).toContain('Link index builds are not available here');
    });
});
