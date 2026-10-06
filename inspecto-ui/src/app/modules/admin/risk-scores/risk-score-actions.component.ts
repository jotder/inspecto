import { ChangeDetectionStrategy, Component, EventEmitter, Output, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { ComponentsService } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { perEntityAlertRuleParams } from 'app/inspecto/risk/risk-score-form';
import { RiskScoreFormData, RiskScoreFormDialog, RiskScoreFormResult, heldChange } from './risk-score-form.dialog';

/** What changed, for the page: `saved` reloads the list; `held` reloads it too (the badge comes from the list). */
export type RiskScoreChange = { kind: 'saved' | 'held' | 'deleted'; id: string };

/**
 * Authoring actions of the Risk Scores pane (slice S2), kept out of the page component so the S1 page and the
 * preview lane touch disjoint lines. Without `model` it renders the header's *New Risk Score*; with one, the
 * detail's *Edit*, *Delete* and the prefilled per-entity Alert Rule link (D-RP9: a link, never a second write). Gated on `canAuthorWorkbench` (D-RP4 a) — the server gates the write again.
 */
@Component({
    selector: 'app-risk-score-actions',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, RouterLink],
    template: `
        @if (model(); as id) {
            @if (lens.canAuthorWorkbench()) {
                <button mat-stroked-button type="button" (click)="edit(id)">Edit</button>
                <button mat-stroked-button type="button" (click)="remove(id)">Delete</button>
            }
            <a mat-stroked-button routerLink="/alerts" [queryParams]="alertRuleParams(id)">Add per-entity Alert Rule</a>
        } @else if (lens.canAuthorWorkbench()) {
            <button mat-flat-button color="primary" type="button" (click)="create()">New Risk Score</button>
        }
    `,
})
export class RiskScoreActionsComponent {
    readonly lens = inject(LensService);
    private dialog = inject(MatDialog);
    private components = inject(ComponentsService);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);

    /** The model id; absent ⇒ the create button. */
    readonly model = input<string | null>(null);
    /** The model's High threshold, prefilled into the per-entity Alert Rule (D-RP9). */
    readonly highThreshold = input<number | null>(null);
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

    alertRuleParams(id: string): Record<string, string> {
        return perEntityAlertRuleParams(id, this.highThreshold());
    }

    /** Delete is a change like any other: the server may hold it (202) or refuse it (409 in use). */
    async remove(id: string): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Delete Risk Score "${id}"? Its derived Datasets stay; History can restore it.`,
            { title: 'Delete Risk Score?', confirmText: 'Delete' },
        );
        if (!ok) return;
        this.components.remove('risk-score', id).subscribe({
            next: (res) => {
                const held = heldChange(res);
                if (held) {
                    this.toastr.info('Delete held for approval' + (held.id ? ` as ${held.id}` : ''));
                    this.changed.emit({ kind: 'held', id });
                } else {
                    this.toastr.success(`Risk Score "${id}" deleted`);
                    this.changed.emit({ kind: 'deleted', id });
                }
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not delete the Risk Score.')),
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
