import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { ObjectsService, Tag, TagsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { TagAssignmentDialog } from './tag-assignment.dialog';

const REGISTRY: Tag[] = [
    { name: 'billing', createdAt: 1 },
    { name: 'fraud', createdAt: 2 },
];

function create(opts: { registry?: Tag[]; assigned?: string[]; failLoad?: boolean; failAssign?: boolean } = {}) {
    const close = vi.fn();
    const error = vi.fn();
    const tags = vi.fn(() => (opts.failLoad ? throwError(() => new Error('down')) : of(opts.registry ?? REGISTRY)));
    const createTag = vi.fn((name: string) => of({ name, createdAt: 9 }));
    const assignments = vi.fn(() =>
        of({ targetKind: 'link-analysis-view', targetId: 'triage-view', tags: opts.assigned ?? ['billing'] }),
    );
    const assign = vi.fn((k: string, i: string, t: string) =>
        opts.failAssign ? throwError(() => new Error('boom')) : of({ tag: t, targetKind: k, targetId: i, createdAt: 1 }),
    );
    const unassign = vi.fn((_k: string, _i: string, t: string) => of({ tag: t, removed: true }));
    TestBed.configureTestingModule({
        imports: [TagAssignmentDialog],
        providers: [
            provideNoopAnimations(),
            {
                provide: MAT_DIALOG_DATA,
                useValue: { targetKind: 'link-analysis-view', targetId: 'triage-view', label: 'Triage view' },
            },
            { provide: MatDialogRef, useValue: { close } },
            { provide: ObjectsService, useValue: { tags, createTag } },
            { provide: TagsService, useValue: { assignments, assign, unassign } },
            { provide: ToastrService, useValue: { success: () => {}, error, warning: () => {} } },
        ],
    });
    const f = TestBed.createComponent(TagAssignmentDialog);
    f.detectChanges();
    return { f, c: f.componentInstance, close, error, tags, createTag, assignments, assign, unassign };
}

describe('TagAssignmentDialog', () => {
    it('loads the registry and the target’s current tags', () => {
        const { c, assignments } = create();
        expect(assignments).toHaveBeenCalledWith('link-analysis-view', 'triage-view');
        expect(c.loading()).toBe(false);
        expect(c.tags().map((t) => t.name)).toEqual(['billing', 'fraud']);
        expect(c.isOn('billing')).toBe(true);
        expect(c.isOn('fraud')).toBe(false);
    });

    it('shows a tag the target carries even when it is missing from the registry', () => {
        const { c } = create({ registry: [], assigned: ['orphan'] });
        expect(c.tags().map((t) => t.name)).toEqual(['orphan']);
        expect(c.isOn('orphan')).toBe(true);
    });

    it('applies only the checkboxes the user actually changed', () => {
        const { c, assign, unassign, close } = create();
        c.toggle('fraud', true); // newly added
        c.toggle('billing', false); // removed
        c.apply();
        expect(assign).toHaveBeenCalledTimes(1);
        expect(assign).toHaveBeenCalledWith('link-analysis-view', 'triage-view', 'fraud');
        expect(unassign).toHaveBeenCalledWith('link-analysis-view', 'triage-view', 'billing');
        expect(close).toHaveBeenCalledWith(['fraud']);
    });

    it('re-checking an already-applied tag is not a write', () => {
        const { c, assign, unassign, close } = create();
        c.toggle('billing', false);
        c.toggle('billing', true); // back to where it started
        c.apply();
        expect(assign).not.toHaveBeenCalled();
        expect(unassign).not.toHaveBeenCalled();
        expect(close).toHaveBeenCalledWith(null);
    });

    it('registers a new tag before it can be assigned, and pre-checks it', () => {
        const { c, createTag, assign } = create();
        c.newTag.setValue('urgent');
        c.createTag();
        expect(createTag).toHaveBeenCalledWith('urgent');
        expect(c.isOn('urgent')).toBe(true);
        c.apply();
        expect(assign).toHaveBeenCalledWith('link-analysis-view', 'triage-view', 'urgent');
    });

    it('does not create a blank tag', () => {
        const { c, createTag } = create();
        c.newTag.setValue('   ');
        c.createTag();
        expect(createTag).not.toHaveBeenCalled();
    });

    it('keeps the dialog open and toasts when saving fails', () => {
        const { c, error, close } = create({ failAssign: true });
        c.toggle('fraud', true);
        c.apply();
        expect(error).toHaveBeenCalled();
        expect(close).not.toHaveBeenCalled();
        expect(c.saving()).toBe(false);
    });

    it('surfaces a load failure instead of rendering an empty vocabulary as truth', () => {
        const { c, error } = create({ failLoad: true });
        expect(error).toHaveBeenCalled();
        expect(c.loading()).toBe(false);
    });

    it('has no a11y violations', async () => {
        const { f } = create();
        await expectNoA11yViolations(f.nativeElement);
    });
});
