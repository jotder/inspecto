import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    effect,
    inject,
    input,
    signal,
    untracked,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { AddDataAccess } from './add-data-access';
import {
    DatasetStorageState,
    RETENTION_OPTIONS,
    Retention,
    retentionKind,
    retentionWords,
    storageWords,
} from './dataset-storage.model';
import { OnboardService } from './onboard.service';

/**
 * An import Dataset's **Storage** (`LA-DATASET-COMPACT-1`, plan P4, D-7…D-9), inside the Dataset card: its files and the
 * last compaction (an import compacts on its own past the threshold, every edition), **Compact now**, and on Standard and
 * up the **retention policy** (keep N days by the rows' date, or N arrivals) for a person who may author Datasets; it
 * applies at the next compaction.
 *
 * @example <inspecto-dataset-storage [datasetId]="d.id" [locked]="locked()" [busy]="busy()" />
 */
@Component({
    selector: 'inspecto-dataset-storage',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    host: { class: 'flex flex-col gap-2' },
    template: `
        @if (state(); as s) {
            <section
                class="flex flex-col gap-1"
                [attr.aria-labelledby]="ids + '-storage'"
                data-testid="dataset-storage"
            >
                <h4 class="m-0 text-sm font-semibold" [id]="ids + '-storage'">Storage</h4>
                <p class="m-0 text-sm" data-testid="storage-line">{{ words(s) }}</p>
                <p class="text-secondary m-0 text-xs">
                    Each file that arrives is kept as its own file; past {{ s.threshold }} they are merged into one, so
                    reading stays quick.
                    @if (s.retentionAvailable) {
                        This Dataset {{ keeps(s.retention) }}.
                    }
                </p>
                <button
                    mat-stroked-button
                    type="button"
                    class="self-start"
                    data-testid="compact-now"
                    [disabled]="working() || busy() || !!locked()"
                    (click)="compactNow()"
                >
                    <mat-icon svgIcon="heroicons_outline:archive-box"></mat-icon>
                    Compact now
                </button>
                @if (s.retentionAvailable && access.canAuthor()) {
                    <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2" data-testid="retention-form">
                        <inspecto-option-picker
                            label="Keep"
                            data-testid="retention-kind"
                            [formControl]="kind"
                            [options]="retentionOptions"
                        ></inspecto-option-picker>
                        @if (kindValue() !== 'all') {
                            <inspecto-pen-field
                                type="number"
                                data-testid="retention-count"
                                [label]="kindValue() === 'days' ? 'Days' : 'Arrivals'"
                                [formControl]="amount"
                                [min]="1"
                                [error]="amountError()"
                            ></inspecto-pen-field>
                        }
                    </div>
                    <button
                        mat-stroked-button
                        type="button"
                        class="self-start"
                        data-testid="save-retention"
                        [disabled]="working() || !!locked()"
                        (click)="saveRetention()"
                    >
                        Save what to keep
                    </button>
                }
            </section>
        }
        @if (note(); as n) {
            <inspecto-alert [variant]="n.variant" data-testid="storage-note">{{ n.text }}</inspecto-alert>
        }
    `,
})
export class DatasetStorageComponent {
    private static nextId = 0;
    private readonly onboard = inject(OnboardService);
    private readonly destroyRef = inject(DestroyRef);
    protected readonly access = inject(AddDataAccess);

    readonly datasetId = input.required<string>();
    /** The card is running an import. */
    readonly busy = input(false);
    /** Why the person may not change data here, or null. */
    readonly locked = input<string | null>(null);

    protected readonly ids = `inspecto-storage-${DatasetStorageComponent.nextId++}`;
    protected readonly state = signal<DatasetStorageState | null>(null);
    protected readonly working = signal(false);
    protected readonly amountError = signal('');
    protected readonly note = signal<{ variant: 'info' | 'warning' | 'error' | 'success'; text: string } | null>(null);
    protected readonly retentionOptions = RETENTION_OPTIONS;
    protected readonly kind = new FormControl<string>('all', { nonNullable: true });
    protected readonly kindValue = toSignal(this.kind.valueChanges, { initialValue: this.kind.value });
    protected readonly amount = new FormControl<number | null>(null);
    protected readonly words = storageWords;
    protected readonly keeps = retentionWords;

    constructor() {
        effect(() => {
            const id = this.datasetId();
            untracked(() => this.load(id));
        });
    }

    private read?: Subscription;

    /** Re-read the storage (the card calls it after an import ran: it may have compacted). */
    load(id = this.datasetId()): void {
        this.read?.unsubscribe();
        this.read = this.onboard
            .storage(id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => this.adopt(s),
                error: () => this.state.set(null), // the card stays usable; only the storage is not shown
            });
    }

    private adopt(s: DatasetStorageState): void {
        this.state.set(s);
        this.kind.setValue(retentionKind(s.retention));
        this.amount.setValue(s.retention?.days ?? s.retention?.arrivals ?? null);
    }

    protected compactNow(): void {
        this.note.set(null);
        this.working.set(true);
        this.onboard
            .compactNow(this.datasetId())
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => {
                    this.working.set(false);
                    this.adopt(s);
                    this.note.set({
                        variant: 'success',
                        text:
                            s.compaction?.result === 'COMPACTED'
                                ? `Compacted: ${s.compaction.message}.`
                                : s.files
                                  ? 'Nothing to compact: the Dataset is one file already.'
                                  : 'Nothing to compact: the Dataset holds no file yet.',
                    });
                },
                error: (err: unknown) => {
                    this.working.set(false);
                    this.note.set({
                        variant: 'error',
                        text: apiErrorMessage(err, 'The files could not be compacted.'),
                    });
                },
            });
    }

    protected saveRetention(): void {
        this.note.set(null);
        this.amountError.set('');
        const kind = this.kind.value;
        let retention: Retention | null = null;
        if (kind !== 'all') {
            const n = Number(this.amount.value);
            if (!Number.isInteger(n) || n < 1) {
                this.amountError.set('A whole number, 1 or more.');
                return;
            }
            retention = kind === 'days' ? { days: n } : { arrivals: n };
        }
        this.working.set(true);
        this.onboard
            .setRetention(this.datasetId(), retention)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (s) => {
                    this.working.set(false);
                    this.adopt(s);
                    this.note.set({
                        variant: 'info',
                        text: `This Dataset now ${retentionWords(s.retention)}; it applies at the next compaction.`,
                    });
                },
                error: (err: unknown) => {
                    this.working.set(false);
                    this.note.set({ variant: 'error', text: apiErrorMessage(err, 'What to keep was not saved.') });
                },
            });
    }
}
