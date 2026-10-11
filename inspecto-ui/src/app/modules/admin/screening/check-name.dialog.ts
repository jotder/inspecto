import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import {
    apiErrorMessage,
    SCREENING_CHECK_LIMITS as LIMITS,
    ScreeningCheckResponse,
    ScreeningListOption,
    ScreeningService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

/** One scored match of a check, flattened under the subject it was found for. */
export interface CheckRow {
    subject: string;
    listId: string;
    purpose: string;
    entry: string;
    method: string;
    score: string;
}

/** The subjects typed one per line: blank lines dropped, each trimmed. */
export function parseSubjects(text: string): string[] {
    return text
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l.length > 0);
}

/**
 * **Check a name** (SCREENING-1 residual 6): scores one or several Subject names against Entity Lists through the
 * read-only `POST /screening/check`. Nothing is raised — the check creates no Screening Hit and persists nothing —
 * and the dialog says so persistently. The server is the boundary: its 422 / 403 / 409 text is shown verbatim.
 */
@Component({
    selector: 'app-check-name-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoSkeletonComponent,
    ],
    templateUrl: './check-name.dialog.html',
})
export class CheckNameDialog implements OnInit {
    private readonly api = inject(ScreeningService);
    private readonly ref = inject(MatDialogRef<CheckNameDialog>);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly fb = inject(FormBuilder);

    protected readonly limits = LIMITS;

    readonly form = this.fb.nonNullable.group({
        names: [''],
        threshold: this.fb.control<number | null>(null, [Validators.min(LIMITS.minThreshold), Validators.max(1)]),
        maxMatches: this.fb.control<number | null>(null, [
            Validators.min(1),
            Validators.max(LIMITS.maxMatchesCeiling),
            Validators.pattern(/^\d+$/),
        ]),
    });

    readonly lists = signal<ScreeningListOption[]>([]);
    readonly listsLoading = signal(true);
    readonly listsError = signal<string | null>(null);
    readonly picked = signal<ReadonlySet<string>>(new Set());
    readonly submitted = signal(false);
    readonly running = signal(false);
    readonly error = signal<string | null>(null);
    readonly response = signal<ScreeningCheckResponse | null>(null);

    private readonly namesText = signal('');
    /** The subjects typed, one per line. */
    readonly names = computed(() => parseSubjects(this.namesText()));

    readonly rows = computed<CheckRow[]>(() =>
        (this.response()?.results ?? []).flatMap((r) =>
            r.matches.map((m) => ({
                subject: r.name ?? r.identifier ?? r.key,
                listId: m.listId,
                purpose: m.purpose,
                entry: m.entry,
                method: m.method,
                score: `${Math.round(m.score * 100)} %`,
            })),
        ),
    );

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty && !this.response(), this.confirm);

    ngOnInit(): void {
        this.form.controls.names.valueChanges.subscribe((v) => this.namesText.set(v));
        this.api.entityLists().subscribe({
            next: (r) => {
                this.lists.set(r.lists.filter((l) => !l.retired));
                this.listsLoading.set(false);
            },
            error: (err) => {
                this.listsError.set(apiErrorMessage(err, 'The Entity Lists could not be read'));
                this.listsLoading.set(false);
            },
        });
    }

    /** The reason the typed names cannot be sent, shown inline once a submit was tried. */
    namesError(): string | null {
        const n = this.names();
        if (n.length === 0) return 'Enter at least one name.';
        if (n.length > LIMITS.maxSubjects) return `At most ${LIMITS.maxSubjects} names per check.`;
        if (n.some((x) => x.length > LIMITS.maxValueLength))
            return `Each name is at most ${LIMITS.maxValueLength} characters.`;
        return null;
    }

    listsPickError(): string | null {
        const n = this.picked().size;
        if (n === 0) return 'Pick at least one Entity List.';
        return n > LIMITS.maxLists ? `At most ${LIMITS.maxLists} Entity Lists per check.` : null;
    }

    toggle(id: string, on: boolean): void {
        const next = new Set(this.picked());
        if (on) next.add(id);
        else next.delete(id);
        this.picked.set(next);
    }

    run(): void {
        this.submitted.set(true);
        this.form.markAllAsTouched();
        if (this.form.controls.threshold.invalid || this.form.controls.maxMatches.invalid) return;
        if (this.namesError() || this.listsPickError()) return;
        const { threshold, maxMatches } = this.form.getRawValue();
        this.running.set(true);
        this.error.set(null);
        this.response.set(null);
        this.api
            .check({
                subjects: this.names().map((name, i) => ({ key: String(i + 1), name })),
                lists: Array.from(this.picked()),
                ...(threshold !== null ? { threshold } : {}),
                ...(maxMatches !== null ? { maxMatches } : {}),
            })
            .subscribe({
                next: (r) => {
                    this.response.set(r);
                    this.running.set(false);
                },
                error: (err) => {
                    // The server's own words (422 limits, 403, 409); the fallback covers an unreachable backend.
                    this.error.set(apiErrorMessage(err, 'The check could not be run'));
                    this.running.set(false);
                },
            });
    }
}
