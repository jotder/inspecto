import { ChangeDetectionStrategy, Component, EventEmitter, Output, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { ToastrService } from 'ngx-toastr';
import { ComponentsService } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { RiskScoreFormData, RiskScoreFormDialog, RiskScoreFormResult } from './risk-score-form.dialog';

/** What changed, for the page: `saved` reloads the list; `held` reloads it too (the badge comes from the list). */
export type RiskScoreChange = { kind: 'saved' | 'held'; id: string };

/**
 * Authoring actions of the Risk Scores pane (slice S2), kept out of the page component so the S1 page and the
 * preview lane touch disjoint lines. Without `model` it renders the header's *New Risk Score*; with one, the
 * detail's *Edit*. Gated on `canAuthorWorkbench` (D-RP4 a) — the server gates the write again.
 */
@Component({
    selector: 'app-risk-score-actions',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule],
    template: `
        @if (lens.canAuthorWorkbench()) {
            @if (model(); as id) {
                <button mat-stroked-button type="button" (click)="edit(id)">Edit</button>
            } @else {
                <button mat-flat-button color="primary" type="button" (click)="create()">New Risk Score</button>
            }
        }
    `,
})
export class RiskScoreActionsComponent {
    readonly lens = inject(LensService);
    private dialog = inject(MatDialog);
    private components = inject(ComponentsService);
    private toastr = inject(ToastrService);

    /** The model id; absent ⇒ the create button. */
    readonly model = input<string | null>(null);
    @Output() readonly changed = new EventEmitter<RiskScoreChange>();

    create(): void {
        this.open({});
    }

    /** A fresh read first, so `If-Match` carries the hash of what is stored now (the list's may be stale). */
    edit(id: string): void {
        this.components.get('risk-score', id).subscribe({
            next: (existing) => this.open({ existing }),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not open the Risk Score.')),
        });
    }

    private open(data: RiskScoreFormData): void {
        this.dialog
            .open(RiskScoreFormDialog, { data, width: '880px', maxHeight: '90vh' })
            .afterClosed()
            .subscribe((r?: RiskScoreFormResult) => {
                if (r?.held) {
                    this.toastr.info('Change held for approval' + (r.held.id ? ` as ${r.held.id}` : ''));
                    this.changed.emit({ kind: 'held', id: data.existing?.name ?? '' });
                } else if (r?.saved) {
                    this.toastr.success(`Risk Score "${r.saved.name}" saved`);
                    this.changed.emit({ kind: 'saved', id: r.saved.name });
                }
            });
    }
}
