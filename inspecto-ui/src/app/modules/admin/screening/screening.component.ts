import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ColDef, ICellRendererParams } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    LensService,
    ScreeningDecision,
    ScreeningHit,
    ScreeningHitState,
    ScreeningService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { StatusBadgeComponent, statusBadgeHtml } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

/** Which Screening Hits the pane lists. */
export type ScreeningFilter = 'open' | 'escalated' | 'all';

/** The status-badge tone of a hit state: open work is info, escalated warns, a confirmed match is an error. */
export function hitTone(state: ScreeningHitState): string {
    if (state === 'confirmed') return 'error';
    if (state === 'dismissed') return 'success';
    return state === 'escalated' ? 'warning' : 'info';
}

/** The decisions a hit in `state` still allows (the server's state machine; final states allow none). */
export function allowedDecisions(state: ScreeningHitState): ScreeningDecision[] {
    if (state === 'open') return ['escalate', 'dismiss', 'confirm'];
    return state === 'escalated' ? ['dismiss', 'confirm'] : [];
}

/** A Match Score (0..1) as a whole percentage. */
export function scorePercent(score: number | null | undefined): string {
    return typeof score === 'number' ? `${Math.round(score * 100)} %` : '';
}

/**
 * **Screening Hits** (SCREENING-1): the matches a `screening.run` Job raised against Entity Lists, for a person to
 * decide — dismiss a false positive, confirm a true match, or escalate to a senior reviewer.
 *
 * Deciding shows only with {@link LensService.canWorkIncidents}; the server is the boundary (capability, a reason,
 * the hit's version, its state machine and the record's integrity check). When the optional `inspecto-screening`
 * module is absent the list answers 503, latched into an explained notice rather than an error toast.
 */
@Component({
    selector: 'app-screening',
    standalone: true,
    imports: [
        DatePipe,
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        DataTableComponent,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
        StatusBadgeComponent,
    ],
    templateUrl: './screening.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScreeningComponent implements OnInit {
    private readonly api = inject(ScreeningService);
    private readonly toastr = inject(ToastrService);
    protected readonly lens = inject(LensService);

    readonly filter = signal<ScreeningFilter>('open');
    readonly hits = signal<ScreeningHit[]>([]);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    readonly unavailable = signal(false);
    readonly selected = signal<ScreeningHit | null>(null);
    readonly deciding = signal(false);
    readonly decideError = signal<string | null>(null);
    readonly reason = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(500)],
    });

    readonly decisions = computed<ScreeningDecision[]>(() => {
        const h = this.selected();
        return h && !h.integrity && this.lens.canWorkIncidents() ? allowedDecisions(h.state) : [];
    });

    protected readonly hitTone = hitTone;
    protected readonly scorePercent = scorePercent;

    readonly columnDefs: ColDef<ScreeningHit>[] = [
        {
            field: 'raisedAt',
            headerName: 'Raised',
            minWidth: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
        {
            headerName: 'Subject',
            flex: 1,
            valueGetter: (p) => p.data?.subjectName ?? p.data?.subjectIdentifier ?? p.data?.subjectKey ?? '',
        },
        { field: 'entry', headerName: 'Matched entry', flex: 1 },
        { field: 'listId', headerName: 'Entity List', minWidth: 140 },
        {
            field: 'score',
            headerName: 'Match Score',
            minWidth: 120,
            maxWidth: 130,
            valueFormatter: (p) => scorePercent(p.value),
        },
        {
            field: 'state',
            headerName: 'State',
            minWidth: 130,
            maxWidth: 140,
            cellRenderer: (p: ICellRendererParams<ScreeningHit>) =>
                statusBadgeHtml(hitTone(p.value as ScreeningHitState), String(p.value)),
        },
    ];

    ngOnInit(): void {
        this.load();
    }

    setFilter(f: ScreeningFilter): void {
        this.filter.set(f);
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.error.set(null);
        const f = this.filter();
        this.api.hits(f === 'all' ? undefined : f).subscribe({
            next: (r) => {
                this.hits.set(r.hits);
                this.loading.set(false);
            },
            error: (err) => {
                this.hits.set([]);
                this.loading.set(false);
                // An absent optional module is a deployment state, not a failure.
                if (err?.status === 503) this.unavailable.set(true);
                else this.error.set(apiErrorMessage(err, 'The Screening Hits could not be read'));
            },
        });
    }

    open(row: Record<string, unknown>): void {
        this.decideError.set(null);
        this.reason.reset('');
        this.api.hit(String(row['id'])).subscribe({
            next: (h) => this.selected.set(h),
            error: (err) => this.toastr.error(apiErrorMessage(err, 'The Screening Hit could not be read')),
        });
    }

    close(): void {
        this.selected.set(null);
    }

    label(d: ScreeningDecision): string {
        if (d === 'confirm') return 'Confirm match';
        return d === 'dismiss' ? 'Dismiss' : 'Escalate';
    }

    decide(decision: ScreeningDecision): void {
        const h = this.selected();
        if (!h) return;
        if (this.reason.invalid) {
            this.reason.markAsTouched();
            return;
        }
        this.deciding.set(true);
        this.decideError.set(null);
        this.api.decide(h.id, decision, this.reason.value.trim(), h.version).subscribe({
            next: (d) => {
                this.deciding.set(false);
                this.toastr.success(`Screening Hit ${d.state}`);
                this.selected.set(d);
                this.reason.reset('');
                this.load();
            },
            error: (err) => {
                this.deciding.set(false);
                // The server's own words: a stale version, a final state, an integrity failure.
                this.decideError.set(apiErrorMessage(err, 'The Screening Hit could not be decided'));
                this.load();
            },
        });
    }
}
