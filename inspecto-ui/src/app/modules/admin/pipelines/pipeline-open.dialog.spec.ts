import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { PipelineSummary } from 'app/inspecto/api';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineOpenDialog, PipelineOpenData } from './pipeline-open.dialog';

const ROW = (name: string): PipelineSummary =>
    ({ name, active: false, nodeCount: 0, edgeCount: 0, produces: [], consumes: [] }) as unknown as PipelineSummary;

const MRU_KEY = 'inspecto.pipelines.mru';
const PINNED_KEY = 'inspecto.pipelines.pinned';

describe('PipelineOpenDialog', () => {
    // Storage state leaks across specs (the LensService lesson) — start each spec clean.
    beforeEach(() => {
        localStorage.removeItem(MRU_KEY);
        localStorage.removeItem(PINNED_KEY);
    });

    function make(data: Partial<PipelineOpenData> = {}) {
        const ref = { close: vi.fn() };
        const transfer = {
            exportPipeline: vi.fn().mockReturnValue(of({ bundle: { source: { name: 'a' } }, missing: [] })),
            download: vi.fn(),
        };
        const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
        TestBed.configureTestingModule({
            imports: [PipelineOpenDialog],
            providers: [
                provideNoopAnimations(),
                provideRouter([]),
                { provide: MatDialogRef, useValue: ref },
                { provide: StreamTransferService, useValue: transfer },
                { provide: ToastrService, useValue: toast },
                {
                    provide: MAT_DIALOG_DATA,
                    useValue: { pipelines: [ROW('a'), ROW('b')], open: ['b'], ...data },
                },
            ],
        });
        const fixture = TestBed.createComponent(PipelineOpenDialog);
        fixture.detectChanges();
        return { fixture, c: fixture.componentInstance, ref, transfer, toast };
    }

    /** Item 1: the footer create — the dialog CLOSES first, then navigates to Catalog onboarding
     *  (⛔ a feature may not import the other feature's create dialog). */
    it('New pipeline… closes the dialog, then navigates to the onboarding entry', () => {
        const { c, ref } = make();
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

        c.newPipeline();

        expect(ref.close).toHaveBeenCalled();
        expect(nav).toHaveBeenCalledWith(['/catalog'], { queryParams: { onboard: 'stream' } });
        // close carried NO result — the editor's afterClosed must treat this as a cancel.
        expect(ref.close).toHaveBeenCalledWith();
    });

    /** Item 4: the per-row export — a READ of the saved server config, no tab needed. */
    it('exports a row through the shared by-name seam without touching the open set', () => {
        const { c, transfer, toast } = make();

        c.exportRow(ROW('a'), new Event('click'));

        expect(transfer.exportPipeline).toHaveBeenCalledWith('a');
        expect(transfer.download).toHaveBeenCalled();
        expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('a'));
        expect(c.picked().has('a')).toBe(false); // the click must not tick the row's checkbox
    });

    /** A row that IS open with unsaved edits keeps the editor's refusal — the export would quietly
     *  disagree with that tab's screen. A closed row has no edits to disagree with, so no gate. */
    it('refuses a dirty OPEN row with the same toast pattern the editor uses', () => {
        const { c, transfer, toast } = make({ dirty: ['b'] });

        c.exportRow(ROW('b'), new Event('click'));

        expect(transfer.exportPipeline).not.toHaveBeenCalled();
        expect(toast.warning).toHaveBeenCalled();
    });

    it('names an unreadable satellite and surfaces an export error as a toast', () => {
        const { c, transfer, toast } = make();
        transfer.exportPipeline.mockReturnValue(of({ bundle: {}, missing: ['schema "a_schema"'] }));
        c.exportRow(ROW('a'), new Event('click'));
        expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('a_schema'));

        transfer.exportPipeline.mockReturnValue(throwError(() => ({ status: 500 })));
        c.exportRow(ROW('a'), new Event('click'));
        expect(toast.error).toHaveBeenCalled();
        expect(c.exporting().has('a')).toBe(false); // in-flight marker cleared on error
    });

    it('confirm returns the full desired open set in listed order', () => {
        const { c, ref } = make();
        c.toggle('a');
        c.confirm();
        expect(ref.close).toHaveBeenCalledWith(['a', 'b']);
    });

    // ── R5: MRU + pins ──────────────────────────────────────────────────────────────────────────

    /** The per-row star: toggling persists the set and the Pinned section appears/disappears. */
    it('pin toggle persists to localStorage and renders the Pinned section', () => {
        const { fixture, c } = make();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).not.toContain('Pinned');

        c.togglePin('a', new Event('click'));
        fixture.detectChanges();
        expect(JSON.parse(localStorage.getItem(PINNED_KEY)!)).toEqual(['a']);
        expect(el.textContent).toContain('Pinned');
        expect(c.pinnedRows().map((p) => p.name)).toEqual(['a']);
        // One extra render of row 'a' (the Pinned section) on top of the full list's two rows.
        expect(el.querySelectorAll('mat-checkbox').length).toBe(3);

        c.togglePin('a', new Event('click'));
        fixture.detectChanges();
        expect(JSON.parse(localStorage.getItem(PINNED_KEY)!)).toEqual([]);
        expect(el.textContent).not.toContain('Pinned');
    });

    /** Confirm records only NEWLY-ticked ids, most-recent-first, capped at 8. */
    it('confirm updates the MRU: new ids first, prior entries deduped, capped at 8', () => {
        localStorage.setItem(MRU_KEY, JSON.stringify(['m1', 'm2', 'm3', 'm4', 'm5', 'm6', 'p1']));
        const pipelines = ['p1', 'p2', 'p3', 'b'].map(ROW);
        const { c } = make({ pipelines, open: ['b'] });

        c.toggle('p1');
        c.toggle('p2');
        c.toggle('p3');
        c.confirm();

        // p1..p3 newly ticked (b was already open — NOT recent); prior list follows, p1 deduped, cap 8.
        expect(JSON.parse(localStorage.getItem(MRU_KEY)!)).toEqual([
            'p1', 'p2', 'p3', 'm1', 'm2', 'm3', 'm4', 'm5',
        ]);
    });

    /** Confirming with nothing newly ticked (or only unticks) leaves the stored MRU alone. */
    it('confirm without newly-ticked ids leaves the MRU untouched', () => {
        localStorage.setItem(MRU_KEY, JSON.stringify(['m1']));
        const { c } = make();
        c.toggle('b'); // untick the already-open row — a close, not an open
        c.confirm();
        expect(JSON.parse(localStorage.getItem(MRU_KEY)!)).toEqual(['m1']);
    });

    /** Stored ids the server no longer lists never render — dropped on render, not scrubbed. */
    it('drops stale MRU and pinned names against the served list', () => {
        localStorage.setItem(MRU_KEY, JSON.stringify(['ghost', 'a']));
        localStorage.setItem(PINNED_KEY, JSON.stringify(['ghost2', 'b']));
        const { fixture, c } = make();

        expect(c.pinnedRows().map((p) => p.name)).toEqual(['b']);
        expect(c.recentRows().map((p) => p.name)).toEqual(['a']);
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('ghost');
        // Storage itself keeps the raw entries (another space may still serve them).
        expect(JSON.parse(localStorage.getItem(MRU_KEY)!)).toEqual(['ghost', 'a']);
    });

    /** A pinned row must not repeat in Recent, and search filters every section. */
    it('dedupes pinned rows out of Recent and search filters across sections', () => {
        localStorage.setItem(MRU_KEY, JSON.stringify(['a', 'b']));
        localStorage.setItem(PINNED_KEY, JSON.stringify(['a']));
        const { fixture, c } = make();

        expect(c.pinnedRows().map((p) => p.name)).toEqual(['a']);
        expect(c.recentRows().map((p) => p.name)).toEqual(['b']); // 'a' pinned — not repeated

        c.query.set('a');
        fixture.detectChanges();
        expect(c.pinnedRows().map((p) => p.name)).toEqual(['a']);
        expect(c.recentRows()).toEqual([]); // 'b' filtered out of Recent too
        expect(c.filtered().map((p) => p.name)).toEqual(['a']);
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Recent');
    });

    it('renders accessibly', async () => {
        const { fixture } = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders accessibly with Pinned and Recent sections shown', async () => {
        localStorage.setItem(MRU_KEY, JSON.stringify(['b']));
        localStorage.setItem(PINNED_KEY, JSON.stringify(['a']));
        const { fixture } = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
