import { ChangeDetectionStrategy, Component, OnInit, computed, forwardRef, inject, input, signal } from '@angular/core';
import { ControlValueAccessor, FormControl, NG_VALUE_ACCESSOR, ReactiveFormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SessionService, apiErrorMessage } from 'app/inspecto/api';
import { ObjectsService } from 'app/inspecto/api/objects.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent } from 'app/inspecto/components/option-picker.component';
import { CaseRef, LinkAnalysisSnapshotsService } from './link-analysis-snapshots.service';

/**
 * **Which Case, if any** — the one place that asks it, shared by the Save-analysis and Attach-to-Case
 * dialogs so the two can never drift on the distinction below.
 *
 * ⚠ Three states, and they must stay distinguishable:
 *  - **ops module present** → real Cases from `GET /objects?type=CASE`.
 *  - **ops module absent** (`bootstrap.features.ops` false) → placeholder Cases, so the flow can be
 *    exercised UI-first. A deployment fact the analyst can act on.
 *  - **the lookup FAILED** → no Case is offered at all, and the error says so. ⛔ Never fall back to the
 *    placeholders here: attaching evidence to a fabricated Case id is a silent wrong answer, and a down
 *    or unauthorised ops service would otherwise render exactly like an edition that has no ops module.
 *
 * The host decides whether a Case is *required* — it is on Attach-to-Case, optional on Save-analysis —
 * and renders its own submit-time error line, because the option picker's own `invalid()` reacts only to
 * its own interaction and can never be triggered by a host calling `markAsTouched()`.
 */
@Component({
    selector: 'inspecto-link-analysis-case-field',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, InspectoAlertComponent, InspectoOptionPickerComponent],
    providers: [
        {
            provide: NG_VALUE_ACCESSOR,
            useExisting: forwardRef(() => LinkAnalysisCaseFieldComponent),
            multi: true,
        },
    ],
    template: `
        @if (loadError()) {
            <inspecto-alert variant="error" title="Cases could not be loaded">
                {{ loadError() }} — no Case can be offered until the lookup succeeds.
            </inspecto-alert>
        } @else {
            <inspecto-option-picker
                [formControl]="control"
                [label]="label()"
                [options]="caseOptions()"
                [help]="casesHelp()"
                [required]="required()"
                [placeholder]="placeholder()"
            ></inspecto-option-picker>
        }
    `,
})
export class LinkAnalysisCaseFieldComponent implements ControlValueAccessor, OnInit {
    readonly label = input('Case');
    /** Attach-to-Case demands one; Save-analysis does not. */
    readonly required = input(false);
    readonly placeholder = input('Select');

    private readonly objects = inject(ObjectsService);
    private readonly store = inject(LinkAnalysisSnapshotsService);
    private readonly opsEnabled = inject(SessionService).opsEnabled;

    readonly cases = signal<CaseRef[]>([]);
    /** Non-empty once the lookup FAILED — distinct from the ops-absent path, which has placeholders. */
    readonly loadError = signal('');
    readonly control = new FormControl('', { nonNullable: true });

    readonly caseOptions = computed(() => this.cases().map((c) => ({ value: c.id, label: `${c.id} · ${c.title}` })));
    readonly casesHelp = computed(() =>
        this.opsEnabled()
            ? 'Open Cases from the objects store.'
            : 'Placeholder Cases — the ops module is not installed.',
    );

    private onChange: (v: string) => void = () => {};
    private onTouched: () => void = () => {};

    constructor() {
        this.control.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => {
            this.onTouched();
            this.onChange(v);
        });
    }

    ngOnInit(): void {
        if (!this.opsEnabled()) {
            this.cases.set([...this.store.mockCases]);
            return;
        }
        this.objects.list({ type: 'CASE' }).subscribe({
            next: (rows) => this.cases.set(rows.map((o) => ({ id: o.id, title: o.title }))),
            error: (err) => {
                this.cases.set([]);
                this.loadError.set(apiErrorMessage(err, 'The Cases lookup failed.'));
            },
        });
    }

    writeValue(v: string | null): void {
        // From the host's patch (e.g. the `?case=` deep link) — not a user pick, so don't echo it back.
        this.control.setValue(v ?? '', { emitEvent: false });
    }

    registerOnChange(fn: (v: string) => void): void {
        this.onChange = fn;
    }

    registerOnTouched(fn: () => void): void {
        this.onTouched = fn;
    }

    setDisabledState(disabled: boolean): void {
        if (disabled) this.control.disable({ emitEvent: false });
        else this.control.enable({ emitEvent: false });
    }
}
