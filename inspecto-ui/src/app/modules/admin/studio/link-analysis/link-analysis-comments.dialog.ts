import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, NotesService, ObjectNote } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';

export interface LinkAnalysisCommentsData {
    id: string;
    /** Optional display label (defaults to the id). */
    label?: string;
}

/**
 * Per-view comments dialog (D10) — the Link Analysis half of the generalized note model
 * (`docs/BACKLOG.md` D10, `okf/frontend/features/link-analysis.md`). Talks to
 * `/notes/link-analysis-view/{id}/comments` via {@link NotesService}; mirrors {@code ComponentHistoryDialog}'s
 * shape so the two "actions on a saved view" dialogs feel like one family.
 */
@Component({
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        MatProgressSpinnerModule,
        InspectoEmptyStateComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Comments</h2>
        <mat-dialog-content class="w-[34rem] max-w-full">
            <div class="text-secondary mb-3 text-sm">
                Comments on the saved view <strong>{{ data.label || data.id }}</strong
                >.
            </div>
            <form class="mb-4 flex items-start gap-3" (ngSubmit)="addComment()">
                <mat-form-field class="flex-auto" subscriptSizing="dynamic">
                    <mat-label>Add a comment</mat-label>
                    <textarea matInput rows="2" [formControl]="commentControl" required></textarea>
                    @if (commentControl.hasError('required') && commentControl.touched) {
                        <mat-error>Enter a comment before adding.</mat-error>
                    }
                </mat-form-field>
                <button type="submit" mat-flat-button color="primary" class="mt-1" [disabled]="posting()">Add</button>
            </form>
            @if (loading()) {
                <div class="flex items-center gap-3 py-2 text-sm">
                    <mat-spinner diameter="20"></mat-spinner><span>Loading comments…</span>
                </div>
            } @else if (!comments().length) {
                <inspecto-empty-state icon="heroicons_outline:chat-bubble-left-right" message="No comments yet." />
            } @else {
                <div class="flex flex-col gap-3">
                    @for (c of comments(); track c.id) {
                        <div class="bg-card rounded-lg border p-4">
                            <div class="text-secondary mb-1 text-sm">
                                {{ c.author || 'unknown' }} · {{ savedAt(c) }}
                            </div>
                            <div class="whitespace-pre-wrap">{{ c.body }}</div>
                        </div>
                    }
                </div>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class LinkAnalysisCommentsDialog {
    readonly data = inject<LinkAnalysisCommentsData>(MAT_DIALOG_DATA);
    private notes = inject(NotesService);
    private toastr = inject(ToastrService);
    private ref = inject(MatDialogRef<LinkAnalysisCommentsDialog>);

    private static readonly TARGET_KIND = 'link-analysis-view';

    readonly loading = signal(true);
    readonly posting = signal(false);
    readonly comments = signal<ObjectNote[]>([]);
    readonly commentControl = new FormControl('', { nonNullable: true, validators: [Validators.required] });

    constructor() {
        this.load();
    }

    private load(): void {
        this.notes.comments(LinkAnalysisCommentsDialog.TARGET_KIND, this.data.id).subscribe({
            next: (c) => {
                this.comments.set(c);
                this.loading.set(false);
            },
            error: (e) => {
                this.comments.set([]);
                this.loading.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not load comments.'));
            },
        });
    }

    savedAt(c: ObjectNote): string {
        if (!c.createdAt) return '—';
        return new Date(c.createdAt).toLocaleString();
    }

    addComment(): void {
        if (this.commentControl.invalid) {
            this.commentControl.markAsTouched();
            return;
        }
        const body = this.commentControl.value.trim();
        if (!body) return;
        this.posting.set(true);
        this.notes.addComment(LinkAnalysisCommentsDialog.TARGET_KIND, this.data.id, body).subscribe({
            next: () => {
                this.commentControl.reset('');
                this.posting.set(false);
                this.load();
            },
            error: (e) => {
                this.posting.set(false);
                this.toastr.error(apiErrorMessage(e, 'Could not add comment.'));
            },
        });
    }
}
