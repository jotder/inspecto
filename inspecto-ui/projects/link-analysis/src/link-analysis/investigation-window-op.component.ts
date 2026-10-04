import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InvestigationWindowFieldsComponent } from './investigation-window-fields.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { windowForm, windowOf } from './investigation-rung-form';

/**
 * LA-SPA-OWED-SURFACES-1 — appends a `window` op (LA-13, `InvestigationTime.window`): an absolute `[from, to)`
 * range of ISO instants WITH an offset, an intraday slot `[start, end)` that may cross midnight, and a day mask.
 * The timezone contract is explicit: a slot or mask is wall-clock time and the server refuses it without an IANA
 * zone — there is no default, because the default would be the host's. "All time" sends `window: 'full'`.
 * The server's 422 is shown verbatim beside the form.
 */
@Component({
    selector: 'inspecto-la-window-op',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        InspectoAlertComponent,
        InvestigationWindowFieldsComponent,
    ],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2"
            style="border-color: var(--gamma-border)"
            aria-labelledby="la-window-op-title"
        >
            <h3 id="la-window-op-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Time window
            </h3>
            <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="submit()">
                <mat-checkbox formControlName="full">All time (clear the window)</mat-checkbox>
                @if (!form.controls.full.value) {
                    <inspecto-la-window-fields [form]="form"></inspecto-la-window-fields>
                }
                <button mat-stroked-button type="submit" class="self-start" [disabled]="store.busy()">
                    Apply window
                </button>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Window refused">{{ error() }}</inspecto-alert>
            }
        </section>
    `,
})
export class InvestigationWindowOpComponent {
    readonly store = inject(InvestigationSessionStore);
    readonly form = windowForm();
    readonly error = signal('');

    async submit(): Promise<void> {
        this.error.set('');
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        if (await this.store.apply({ op: 'window', window: windowOf(this.form) })) this.form.reset();
        else this.error.set(this.store.error());
    }
}
