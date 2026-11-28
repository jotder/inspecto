import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef } from 'ag-grid-community';
import { EventsService, SignalBoard, SignalBoardType } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime } from 'app/inspecto/grid';

type Unheard = SignalBoard['unheard'][number];
type Unposted = SignalBoard['unposted'][number];

/** The windows the board offers, in hours (the route allows 1 to 168). */
const WINDOWS = [
    { hours: 24, label: 'Last 24 hours' },
    { hours: 72, label: 'Last 3 days' },
    { hours: 168, label: 'Last 7 days' },
] as const;

/**
 * The Signal Board (SIGNAL-BOARD-1 SB-3, `GET /signals/board`): each Signal Type of the Space, what posts it, which
 * Jobs listen and how listening went over a window. The two findings come first: a type posted that no Job hears,
 * and a Job listening to a type nothing posts. A tab of the Signal Ledger page; the host shows it only to a person
 * with `canAuthorWorkbench`, the route's gate.
 */
@Component({
    selector: 'app-signal-board',
    standalone: true,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
        DataTableComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-col gap-6">
            <div class="flex flex-wrap items-center gap-3">
                <!-- form-allow: a toolbar select (the board's window), not a value of a record --><mat-form-field
                    class="gamma-mat-dense w-44"
                    subscriptSizing="dynamic"
                >
                    <mat-label>Window</mat-label>
                    <!-- form-allow: a toolbar select (the board's window) --><mat-select
                        [ngModel]="hours()"
                        (ngModelChange)="setHours($event)"
                        aria-label="Signal Board window"
                    >
                        @for (w of windows; track w.hours) {
                            <mat-option [value]="w.hours">{{ w.label }}</mat-option>
                        }
                    </mat-select>
                </mat-form-field>
                <button mat-icon-button (click)="load()" matTooltip="Refresh the board" aria-label="Refresh the board">
                    <mat-icon svgIcon="heroicons_outline:arrow-path"></mat-icon>
                </button>
                @if (board(); as b) {
                    <span class="text-secondary text-sm">
                        {{ b.typesTotal }} types · {{ fmt(b.since) }} to {{ fmt(b.until) }}
                    </span>
                }
            </div>

            @if (error()) {
                <inspecto-alert variant="error" title="The Signal Board could not be read">{{ error() }}</inspecto-alert>
            } @else if (board(); as b) {
                @if (b.capped || b.guardsCapped) {
                    <inspecto-alert variant="warning" title="Some counts are lower bounds">
                        @if (b.capped) {
                            More Signals were posted in this window than one read holds, so posting, Run, refusal and
                            chain-cut counts are at least what is shown.
                        }
                        @if (b.guardsCapped) {
                            Guards that said no are read from each Job's recent Runs kept since the server started, which
                            do not reach back over the whole window.
                        }
                    </inspecto-alert>
                }

                <section aria-labelledby="board-unheard-title">
                    <h2 id="board-unheard-title" class="mb-1 text-lg font-semibold">Posted, but no Job hears it</h2>
                    <p class="text-secondary mb-3 text-sm">
                        Signal Types posted in this window that no enabled Job listens to.
                        @if (b.unheardTruncated) {
                            Showing {{ b.unheard.length }} of {{ b.unheardTotal }}.
                        }
                    </p>
                    <inspecto-data-table
                        tier="standard"
                        sourceName="signal_board_unheard"
                        exportName="signal-board-unheard"
                        [rows]="b.unheard"
                        [columns]="unheardColumns"
                        [pageSize]="10"
                        noRowsTitle="Every type posted in this window is heard by a Job"
                    />
                </section>

                <section aria-labelledby="board-unposted-title">
                    <h2 id="board-unposted-title" class="mb-1 text-lg font-semibold">
                        Listening, but nothing posts it
                    </h2>
                    <p class="text-secondary mb-3 text-sm">
                        Jobs whose On signal matches no catalogued Signal Type and nothing posted in this window.
                        @if (b.unpostedTruncated) {
                            Showing {{ b.unposted.length }} of {{ b.unpostedTotal }}.
                        }
                    </p>
                    <inspecto-data-table
                        tier="standard"
                        sourceName="signal_board_unposted"
                        exportName="signal-board-unposted"
                        [rows]="b.unposted"
                        [columns]="unpostedColumns"
                        [pageSize]="10"
                        noRowsTitle="Every Job listens to a type that is posted"
                    />
                </section>

                <section aria-labelledby="board-types-title">
                    <h2 id="board-types-title" class="mb-1 text-lg font-semibold">Signal Types</h2>
                    <p class="text-secondary mb-3 text-sm">
                        What posts each type, which Jobs listen, and what listening did in this window.
                        @if (b.typesTruncated) {
                            Showing {{ b.types.length }} of {{ b.typesTotal }}.
                        }
                    </p>
                    <inspecto-data-table
                        tier="pro"
                        sourceName="signal_board"
                        exportName="signal-board"
                        stateKey="signal-board"
                        [rows]="b.types"
                        [columns]="typeColumns()"
                        [pageSize]="25"
                        noRowsTitle="No Signal Types in this Space"
                    />
                </section>
            } @else {
                <p class="text-secondary text-sm" role="status">Reading the Signal Board…</p>
            }
        </div>
    `,
})
export class SignalBoardComponent implements OnInit {
    private api = inject(EventsService);

    readonly windows = WINDOWS;
    readonly hours = signal<number>(24);
    readonly board = signal<SignalBoard | null>(null);
    readonly error = signal<string | null>(null);

    readonly unheardColumns: ColDef<Unheard>[] = [
        { field: 'type', headerName: 'Signal Type', flex: 2, minWidth: 200 },
        { field: 'posted', headerName: 'Posted', minWidth: 100 },
        {
            field: 'lastPosted',
            headerName: 'Last posted',
            minWidth: 180,
            valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : '—'),
        },
        {
            field: 'postedBy',
            headerName: 'Posted by',
            flex: 2,
            minWidth: 180,
            valueFormatter: (p) => (p.value ?? []).join(', ') || '—',
        },
    ];

    readonly unpostedColumns: ColDef<Unposted>[] = [
        { field: 'job', headerName: 'Job', flex: 1, minWidth: 180 },
        { field: 'onSignal', headerName: 'On signal', flex: 2, minWidth: 200 },
        {
            field: 'enabled',
            headerName: 'Enabled',
            minWidth: 110,
            valueFormatter: (p) => (p.value ? 'Yes' : 'No'),
        },
    ];

    /** The Missed column appears once SB-2b records missed Signals (`missedTracked`). */
    readonly typeColumns = computed<ColDef<SignalBoardType>[]>(() => {
        const cols: ColDef<SignalBoardType>[] = [
            { field: 'type', headerName: 'Signal Type', minWidth: 220, flex: 2 },
            {
                headerName: 'Posted by',
                minWidth: 180,
                flex: 1,
                valueGetter: (p) => p.data?.postedBy?.join(', ') || p.data?.poster || '—',
            },
            {
                headerName: 'Listening Jobs',
                minWidth: 200,
                flex: 2,
                valueGetter: (p) => listenerText(p.data),
            },
            { field: 'posted', headerName: 'Posted', minWidth: 100 },
            {
                field: 'lastPosted',
                headerName: 'Last posted',
                minWidth: 180,
                valueFormatter: (p) => (p.value ? fmtDateTime(p.value) : '—'),
            },
            { field: 'runsStarted', headerName: 'Runs started', minWidth: 130 },
            { field: 'guardsSaidNo', headerName: 'Guard said no', minWidth: 140 },
            { field: 'refused', headerName: 'Refused', minWidth: 110 },
            { field: 'chainCuts', headerName: 'Chain cuts', minWidth: 120 },
        ];
        if (this.board()?.missedTracked) cols.push({ field: 'missed', headerName: 'Missed', minWidth: 110 });
        return cols;
    });

    ngOnInit(): void {
        this.load();
    }

    setHours(h: number): void {
        this.hours.set(h);
        this.load();
    }

    load(): void {
        this.error.set(null);
        this.api.signalBoard(this.hours()).subscribe({
            next: (b) => this.board.set(b),
            error: (e: { status?: number }) => {
                this.board.set(null);
                this.error.set(
                    e?.status === 403
                        ? 'Reading the Signal Board needs the right to author Jobs.'
                        : 'The server did not answer. Try Refresh.'
                );
            },
        });
    }

    fmt(iso: string): string {
        return fmtDateTime(iso);
    }
}

/** "job_a, job_b (off)" — a disabled listener is marked, since it hears nothing. */
function listenerText(row: SignalBoardType | undefined): string {
    if (!row || row.listenersTotal === 0) return '—';
    const names = row.listeners.map((l) => (l.enabled ? l.job : `${l.job} (off)`));
    const more = row.listenersTotal - row.listeners.length;
    return names.join(', ') + (more > 0 ? ` and ${more} more` : '');
}
