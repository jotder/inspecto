import { ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { ToastrService } from 'ngx-toastr';
import { LensService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { DomainProfileService } from '@inspecto/link-analysis/api/domain-profile.service';
import { GENERIC_PROFILE } from '@inspecto/link-analysis/graph/domain-profile';

/**
 * Settings ▸ Link Analysis ▸ **Domain Profile** (LA-EDITIONS-1, settles `LDP-D7`): which installed Industry pack
 * relabels Link Analysis for this Space, and the terms it publishes (label, abbreviation, hover definition). A pack
 * relabels; it never adds a concept. `generic` is the neutral vocabulary. Saving needs `canAuthorWorkbench`.
 */
@Component({
    selector: 'inspecto-link-analysis-domain-profile',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, InspectoAlertComponent, InspectoOptionPickerComponent],
    template: `
        <section class="flex max-w-4xl flex-col gap-3" aria-labelledby="la-domain-profile-title">
            <h2 id="la-domain-profile-title" class="m-0 text-lg font-semibold">Domain Profile</h2>
            <p class="text-secondary m-0 text-sm">
                The Industry pack whose vocabulary Link Analysis uses in this space. A pack renames concepts and Entity
                Types and explains its terms; it never adds a concept. Generic keeps the neutral words.
            </p>
            @if (state(); as s) {
                @if (s.diagnostic) {
                    <inspecto-alert variant="warning" title="Neutral vocabulary in force">{{
                        s.diagnostic
                    }}</inspecto-alert>
                }
                @for (r of s.refused; track r.id) {
                    <inspecto-alert variant="error" [title]="'Industry pack ' + r.id + ' was refused'">
                        {{ r.reason }}
                    </inspecto-alert>
                }
                <div class="flex flex-wrap items-end gap-3">
                    <inspecto-option-picker
                        class="min-w-64"
                        label="Industry pack"
                        [formControl]="choice"
                        [options]="options()"
                        [readOnly]="!canEdit()"
                    />
                    @if (canEdit()) {
                        <button
                            mat-flat-button
                            color="primary"
                            type="button"
                            data-testid="domain-profile-save"
                            [disabled]="saving() || chosen() === s.active"
                            (click)="save()"
                        >
                            Apply
                        </button>
                    } @else {
                        <p class="text-secondary m-0 text-sm">Changing the Industry pack needs the author role.</p>
                    }
                </div>
                @if (s.profile; as p) {
                    <table class="w-full text-left text-sm" data-testid="domain-profile-terms">
                        <caption class="text-secondary pb-1 text-left text-xs">
                            Terms of
                            {{
                                p.name
                            }}
                        </caption>
                        <thead>
                            <tr>
                                <th scope="col" class="pr-3">Concept</th>
                                <th scope="col" class="pr-3">Called</th>
                                <th scope="col" class="pr-3">Abbreviation</th>
                                <th scope="col">Definition</th>
                            </tr>
                        </thead>
                        <tbody>
                            @for (t of p.terms; track t.key) {
                                <tr>
                                    <td class="pr-3 align-top font-mono text-xs">{{ t.key }}</td>
                                    <td class="pr-3 align-top">{{ t.label }}</td>
                                    <td class="pr-3 align-top">{{ t.abbreviation }}</td>
                                    <td class="text-secondary align-top">{{ t.definition }}</td>
                                </tr>
                            }
                        </tbody>
                    </table>
                } @else {
                    <p class="text-secondary m-0 text-sm">Generic: the neutral vocabulary, no pack terms.</p>
                }
            } @else if (svc.state() === null && loadFailed()) {
                <inspecto-alert variant="error" title="Domain Profile unavailable">{{ loadFailed() }}</inspecto-alert>
            } @else {
                <p class="text-secondary m-0 text-sm">Reading the Domain Profile…</p>
            }
        </section>
    `,
})
export class LinkAnalysisDomainProfileComponent {
    readonly svc = inject(DomainProfileService);
    private readonly lens = inject(LensService);
    private readonly toastr = inject(ToastrService);

    readonly state = this.svc.state;
    readonly canEdit = computed(() => this.lens.canAuthorWorkbench());
    readonly saving = signal(false);
    readonly loadFailed = signal('');
    readonly choice = new FormControl<string>(GENERIC_PROFILE.id, { nonNullable: true });
    /** The picked id as a signal, so Apply enables the moment a different pack is picked (OnPush). */
    readonly chosen = toSignal(this.choice.valueChanges, { initialValue: this.choice.value });

    /** Generic plus every installed pack, with its description as the hint. */
    readonly options = computed<PickerOption[]>(() => [
        { value: GENERIC_PROFILE.id, label: GENERIC_PROFILE.label, hint: GENERIC_PROFILE.description },
        ...(this.state()?.installed ?? []).map((p) => ({ value: p.id, label: p.name, hint: p.description })),
    ]);

    constructor() {
        // the picker shows what is in force whenever the server answers (first read, a save, another Space)
        effect(() => {
            const s = this.state();
            if (s) untracked(() => this.choice.setValue(s.active));
        });
        void this.svc.ready().then(() => {
            if (!this.svc.state()) this.loadFailed.set('The server did not answer with the Domain Profile.');
        });
    }

    save(): void {
        if (!this.canEdit() || this.saving()) return;
        this.saving.set(true);
        this.svc.save(this.choice.value).subscribe({
            next: (s) => {
                this.saving.set(false);
                this.toastr.success(
                    s.active === GENERIC_PROFILE.id
                        ? 'Neutral vocabulary in force.'
                        : `${s.profile?.name ?? s.active} in force.`,
                );
            },
            error: (err: unknown) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(err, 'The Domain Profile could not be saved.'));
            },
        });
    }
}
