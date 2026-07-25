import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { NotesService, ObjectNote } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisCommentsDialog } from './link-analysis-comments.dialog';

const COMMENTS: ObjectNote[] = [
    { id: 'n2', objectId: 'triage-view', kind: 'COMMENT', author: 'ana', body: 'Second pass', createdAt: 2 },
    { id: 'n1', objectId: 'triage-view', kind: 'COMMENT', author: 'bo', body: 'First pass', createdAt: 1 },
];

function create(opts: { comments?: ObjectNote[] } = {}) {
    const close = vi.fn();
    const comments = vi.fn(() => of(opts.comments ?? COMMENTS));
    const addComment = vi.fn(() => of(COMMENTS[0]));
    TestBed.configureTestingModule({
        imports: [LinkAnalysisCommentsDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { id: 'triage-view', label: 'Triage view' } },
            { provide: MatDialogRef, useValue: { close } },
            { provide: NotesService, useValue: { comments, addComment } },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {}, warning: () => {} } },
        ],
    });
    const f = TestBed.createComponent(LinkAnalysisCommentsDialog);
    f.detectChanges();
    return { f, c: f.componentInstance, close, comments, addComment };
}

describe('LinkAnalysisCommentsDialog', () => {
    it('loads comments for the (link-analysis-view, id) target', () => {
        const { c, comments } = create();
        expect(comments).toHaveBeenCalledWith('link-analysis-view', 'triage-view');
        expect(c.loading()).toBe(false);
        expect(c.comments().map((n) => n.body)).toEqual(['Second pass', 'First pass']);
    });

    it('posts a new comment and reloads the thread', () => {
        const { c, addComment, comments } = create();
        c.commentControl.setValue('Looks good');
        c.addComment();
        expect(addComment).toHaveBeenCalledWith('link-analysis-view', 'triage-view', 'Looks good');
        expect(comments).toHaveBeenCalledTimes(2);
    });

    it('does not submit a blank comment', () => {
        const { c, addComment } = create();
        c.commentControl.setValue('   ');
        c.addComment();
        expect(addComment).not.toHaveBeenCalled();
    });

    it('shows an empty state with no comments', () => {
        const { c, f } = create({ comments: [] });
        expect(c.comments().length).toBe(0);
        expect(f.nativeElement.querySelector('inspecto-empty-state')).toBeTruthy();
    });

    it('has no a11y violations', async () => {
        const { f } = create();
        await expectNoA11yViolations(f.nativeElement);
    });
});
