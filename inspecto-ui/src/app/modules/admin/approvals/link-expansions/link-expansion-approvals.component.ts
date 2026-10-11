import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { fmtDateTime } from 'app/inspecto/grid';
import {
    LinkExpansionApprovalsService,
    LinkExpansionRequest,
    LinkExpansionStatusFilter,
} from './link-expansion-approvals.service';

/**
 * Link expansions (D-U7 four-eyes) — the approver's inbox. A Link Analysis expand above the Space's thresholds does not
 * run: it waits as a pending request until a DIFFERENT person holding `canApproveLinkExpansions` decides it. Before this
 * section an approver who was not a member of the analyst's Investigation had no way to reach the request at all.
 *
 * The server lists only what the caller may decide (`GET /inv/pending`): never their own request, never one on an
 * Investigation whose membership leaves them without a lead/reviewer role. The host renders this section only for a
 * holder of the capability. A 503 (Link Analysis not installed, or a read-only control plane) is an expected state,
 * explained in place.
 */
@Component({
    selector: 'app-link-expansion-approvals',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './link-expansion-approvals.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LinkExpansionApprovalsComponent implements OnInit {
    private api = inject(LinkExpansionApprovalsService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);

    readonly requests = signal<LinkExpansionRequest[]>([]);
    readonly total = signal(0);
    readonly loading = signal(false);
    readonly unavailable = signal(false);
    readonly filter = signal<LinkExpansionStatusFilter>('pending');
    /** The request whose deny form is open (`investigationId/requestId`), or null. */
    readonly denying = signal<string | null>(null);
    /** A decision in flight, by key — its buttons disable so it cannot be sent twice. */
    readonly busy = signal<string | null>(null);
    readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] });

    readonly fmt = fmtDateTime;

    ngOnInit(): void {
        this.load();
    }

    key(r: LinkExpansionRequest): string {
        return r.investigationId + '/' + r.requestId;
    }

    setFilter(f: LinkExpansionStatusFilter): void {
        this.filter.set(f);
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.inbox(this.filter()).subscribe({
            next: (inbox) => {
                this.requests.set(inbox.items);
                this.total.set(inbox.total);
                this.unavailable.set(false);
                this.loading.set(false);
            },
            error: (err) => {
                this.requests.set([]);
                this.total.set(0);
                this.loading.set(false);
                if (err?.status === 503) {
                    this.unavailable.set(true);
                } else {
                    this.unavailable.set(false);
                    this.toastr.error(apiErrorMessage(err, 'Failed to load link expansion requests'));
                }
            },
        });
    }

    async approve(r: LinkExpansionRequest): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `${r.summary}\n\nRequested by ${r.requestedBy ?? 'unknown'} on ${r.title ?? r.investigationId}.` +
                (r.purpose ? `\nPurpose: ${r.purpose}` : '') +
                '\n\nApproving runs it now, as the requester’s step, and records you as the approver.',
            { title: 'Approve this link expansion?', confirmText: 'Approve', destructive: false },
        );
        if (!ok) return;
        this.busy.set(this.key(r));
        this.api.approve(r).subscribe({
            next: () => this.decided('Expansion approved — it ran'),
            error: (err) => this.failed(err, 'approve'),
        });
    }

    openDeny(r: LinkExpansionRequest): void {
        this.reason.reset('');
        this.denying.set(this.key(r));
    }

    cancelDeny(): void {
        this.denying.set(null);
    }

    deny(r: LinkExpansionRequest): void {
        if (this.reason.invalid) {
            this.reason.markAsTouched();
            return;
        }
        this.busy.set(this.key(r));
        this.api.deny(r, this.reason.value.trim() || undefined).subscribe({
            next: () => this.decided('Expansion denied — nothing was read'),
            error: (err) => this.failed(err, 'deny'),
        });
    }

    private decided(message: string): void {
        this.busy.set(null);
        this.denying.set(null);
        this.toastr.success(message);
        this.load();
    }

    private failed(err: unknown, verb: string): void {
        this.busy.set(null);
        this.toastr.error(apiErrorMessage(err, `Could not ${verb} the request (it may already be decided).`));
        this.load();
    }
}
