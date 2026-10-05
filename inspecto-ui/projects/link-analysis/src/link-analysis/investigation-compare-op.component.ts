import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import type { InvestigationWindow } from '@inspecto/link-analysis/api/inv.service';
import { InvestigationWindowFieldsComponent } from './investigation-window-fields.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { windowForm, windowOf } from './investigation-rung-form';

/**
 * LA-INVESTIGATION-OPS-DEFERRED-1 — appends a `compare` op: two time windows (A and B) of the bound time column, diffed
 * over the Working Set as it stands. The server reads the Dataset once, SEALS the diff into the log entry and changes
 * nothing in the Working Set; a Dossier then carries the diff in custody. Each side is the same object the `window` op
 * sends, so the same fields (and the same timezone contract) apply; or `inherit`, the Investigation's own window at that step.
 * "By event count" also seals the per-link / per-entity count delta. The server's 422 is shown verbatim beside the form.
 */
@Component({
    selector: 'inspecto-la-compare-op',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, InspectoAlertComponent, InvestigationWindowFieldsComponent],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2"
            style="border-color: var(--gamma-border)"
            aria-labelledby="la-compare-op-title"
        >
            <h3 id="la-compare-op-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Compare two windows
            </h3>
            <form class="flex flex-col gap-2" (ngSubmit)="submit()">
                <fieldset class="m-0 flex flex-col gap-2 border-0 p-0">
                    <legend class="text-secondary p-0 text-xs font-semibold">Window A</legend>
                    <label class="flex items-center gap-2 text-xs">
                        <input type="checkbox" [checked]="inheritA()" (change)="inheritA.set(!inheritA())" />
                        Use the Investigation's window
                    </label>
                    @if (!inheritA()) {
                        <inspecto-la-window-fields [form]="formA"></inspecto-la-window-fields>
                    }
                </fieldset>
                <fieldset class="m-0 flex flex-col gap-2 border-0 p-0">
                    <legend class="text-secondary p-0 text-xs font-semibold">Window B</legend>
                    <label class="flex items-center gap-2 text-xs">
                        <input type="checkbox" [checked]="inheritB()" (change)="inheritB.set(!inheritB())" />
                        Use the Investigation's window
                    </label>
                    @if (!inheritB()) {
                        <inspecto-la-window-fields [form]="formB"></inspecto-la-window-fields>
                    }
                </fieldset>
                <label class="flex items-center gap-2 text-xs">
                    <input type="checkbox" [checked]="byCount()" (change)="byCount.set(!byCount())" />
                    By event count (also seal each link's and entity's count change)
                </label>
                <button mat-stroked-button type="submit" class="self-start" [disabled]="store.busy()">
                    Compare and seal
                </button>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Comparison refused">{{ error() }}</inspecto-alert>
            }
        </section>
    `,
})
export class InvestigationCompareOpComponent {
    readonly store = inject(InvestigationSessionStore);
    readonly formA = windowForm();
    readonly formB = windowForm();
    readonly inheritA = signal(false);
    readonly inheritB = signal(false);
    readonly byCount = signal(false);
    readonly error = signal('');

    async submit(): Promise<void> {
        this.error.set('');
        if ((!this.inheritA() && this.formA.invalid) || (!this.inheritB() && this.formB.invalid)) {
            this.formA.markAllAsTouched();
            this.formB.markAllAsTouched();
            return;
        }
        // `full` is never set here, so a side is an object or 'inherit'; an empty side already fails the form's own rule.
        const windowA = this.inheritA() ? 'inherit' : (windowOf(this.formA) as InvestigationWindow);
        const windowB = this.inheritB() ? 'inherit' : (windowOf(this.formB) as InvestigationWindow);
        if (
            await this.store.apply({
                op: 'compare',
                windowA,
                windowB,
                ...(this.byCount() ? { mode: 'activity' as const } : {}),
            })
        ) {
            this.formA.reset();
            this.formB.reset();
            this.inheritA.set(false);
            this.inheritB.set(false);
            this.byCount.set(false);
        } else this.error.set(this.store.error());
    }
}
