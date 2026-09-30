import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';

import { LensService, apiErrorMessage } from 'app/inspecto/api';
import { LinkAnalysisLimits, LinkAnalysisSettingsService } from 'app/inspecto/api/link-analysis-settings.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/** `SettingsRoutes.MAX_NODE_CAP` — every integer setting on this route is 1..100 000 or refused (422). */
const MAX = 100_000;
const intOrBlank = [Validators.min(1), Validators.max(MAX), Validators.pattern('\\d*')];

type Key = 'fourEyesBudgetAbove' | 'fourEyesFanOutAbove' | 'mergedDistinctCap' | 'seedByDistinctCap';

/**
 * Settings ▸ **Link Analysis** — the Investigation controls of `GET|PUT /settings/link-analysis` the SPA had no
 * surface for: the two four-eyes thresholds (D-U7), `mergedDistinctCap` (LA-17 merged traversal, with the value
 * in force) and — only when the server reports it — `seedByDistinctCap`. Blank = inherit the shipped default.
 *
 * <p>⚠ The PUT REPLACES the whole document, so a save sends back every other stated key exactly as read (the node
 * caps, masking mode, Entity Types) — this section edits four keys and must not erase the rest.
 */
@Component({
    selector: 'inspecto-link-analysis-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
    ],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Link Analysis"
                subtitle="Four-eyes thresholds and traversal caps for Investigations in this space."
                [inset]="false"
            />
            @if (loading()) {
                <p class="text-secondary text-sm">Reading the Link Analysis settings…</p>
            } @else if (loadError()) {
                <inspecto-alert variant="error" title="Settings unavailable">{{ loadError() }}</inspecto-alert>
            } @else {
                <form [formGroup]="form" class="flex max-w-160 flex-col gap-3" (ngSubmit)="save()">
                    @for (f of fields(); track f.key) {
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>{{ f.label }}</mat-label>
                            <input
                                matInput
                                type="number"
                                min="1"
                                [formControlName]="f.key"
                                [readonly]="!canEdit()"
                                [placeholder]="f.placeholder"
                            />
                            <mat-hint>{{ f.hint }}</mat-hint>
                            @if (form.controls[f.key].invalid) {
                                <mat-error>A whole number from 1 to {{ max }}, or blank for the default.</mat-error>
                            }
                        </mat-form-field>
                    }
                    @if (saveError()) {
                        <inspecto-alert variant="error" title="Not saved">{{ saveError() }}</inspecto-alert>
                    }
                    @if (writesDisabled()) {
                        <inspecto-alert variant="warning" title="Changes cannot be saved here">{{
                            writesDisabled()
                        }}</inspecto-alert>
                    }
                    @if (canEdit()) {
                        <div>
                            <button mat-flat-button color="primary" type="submit" [disabled]="saving()">
                                Save Link Analysis settings
                            </button>
                        </div>
                    } @else {
                        <inspecto-alert variant="info" title="Read only">
                            Changing these settings needs the Workbench authoring capability.
                        </inspecto-alert>
                    }
                </form>
            }
        </div>
    `,
})
export class LinkAnalysisSettingsComponent implements OnInit {
    private api = inject(LinkAnalysisSettingsService);
    private lens = inject(LensService);
    private toastr = inject(ToastrService);

    readonly max = MAX;
    readonly canEdit = computed(() => this.lens.canAuthorWorkbench());
    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly loadError = signal<string | null>(null);
    readonly saveError = signal<string | null>(null);
    readonly writesDisabled = signal<string | null>(null);
    private readonly served = signal<LinkAnalysisLimits | null>(null);

    readonly form = new FormGroup({
        fourEyesBudgetAbove: new FormControl<number | null>(null, intOrBlank),
        fourEyesFanOutAbove: new FormControl<number | null>(null, intOrBlank),
        mergedDistinctCap: new FormControl<number | null>(null, intOrBlank),
        seedByDistinctCap: new FormControl<number | null>(null, intOrBlank),
    });

    readonly fields = computed(() => {
        const s = this.served();
        const out: { key: Key; label: string; hint: string; placeholder: string }[] = [
            {
                key: 'fourEyesBudgetAbove',
                label: 'Four-eyes: expand budget above',
                hint: 'An expand asking for more than this needs a second approver. Blank = no threshold.',
                placeholder: 'no threshold',
            },
            {
                key: 'fourEyesFanOutAbove',
                label: 'Four-eyes: fan-out above',
                hint: 'An expand reaching more entities than this needs a second approver. Blank = no threshold.',
                placeholder: 'no threshold',
            },
            {
                key: 'mergedDistinctCap',
                label: 'Merged expand: distinct values per column',
                hint: `In force: ${s?.mergedDistinctCapInForce ?? '—'}. Above it a merged expand is refused, never sampled.`,
                placeholder: `default ${s?.mergedDistinctCapInForce ?? ''}`,
            },
        ];
        if (s && 'seedByDistinctCap' in s)
            out.push({
                key: 'seedByDistinctCap',
                label: 'Seed by Entity List: distinct values',
                hint: `In force: ${s.seedByDistinctCapInForce ?? '—'}.`,
                placeholder: `default ${s.seedByDistinctCapInForce ?? ''}`,
            });
        return out;
    });

    ngOnInit(): void {
        this.api.get().subscribe({
            next: (s) => {
                this.loading.set(false);
                this.apply(s);
            },
            error: (err) => {
                this.loading.set(false);
                this.loadError.set(apiErrorMessage(err, 'The Link Analysis settings route did not answer.'));
            },
        });
    }

    save(): void {
        const served = this.served();
        if (!served || this.saving()) return;
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        const num = (x: number | null): number | null => (x === null || (x as unknown) === '' ? null : Number(x));
        // Everything stated stays as read; the server-computed *InForce keys are not part of the document.
        const body: Record<string, unknown> = {};
        for (const [k, x] of Object.entries(served)) if (!k.endsWith('InForce')) body[k] = x;
        body['fourEyesBudgetAbove'] = num(v.fourEyesBudgetAbove);
        body['fourEyesFanOutAbove'] = num(v.fourEyesFanOutAbove);
        body['mergedDistinctCap'] = num(v.mergedDistinctCap);
        if ('seedByDistinctCap' in served) body['seedByDistinctCap'] = num(v.seedByDistinctCap);
        else delete body['seedByDistinctCap'];
        this.saving.set(true);
        this.saveError.set(null);
        this.api.save(body as unknown as LinkAnalysisLimits).subscribe({
            next: (s) => {
                this.saving.set(false);
                this.writesDisabled.set(null);
                this.apply(s);
                this.toastr.success('Link Analysis settings saved.');
            },
            error: (err) => {
                this.saving.set(false);
                const message = apiErrorMessage(err, 'Saving the Link Analysis settings failed.');
                if (err?.status === 422) this.saveError.set(message);
                else if (err?.status === 403)
                    this.saveError.set('You are not allowed to change these settings. Server: ' + message);
                else if (err?.status === 503) this.writesDisabled.set(message);
                else this.toastr.error(message);
            },
        });
    }

    private apply(s: LinkAnalysisLimits): void {
        this.served.set(s);
        this.form.reset({
            fourEyesBudgetAbove: s.fourEyesBudgetAbove ?? null,
            fourEyesFanOutAbove: s.fourEyesFanOutAbove ?? null,
            mergedDistinctCap: s.mergedDistinctCap ?? null,
            seedByDistinctCap: s.seedByDistinctCap ?? null,
        });
    }
}
