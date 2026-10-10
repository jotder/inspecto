import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { LinkAnalysisView } from './link-analysis.service';
import { oneLine } from './la-starter';
import { msisdnError, normaliseMsisdn } from './investigate-number';

/** The MSISDN field's validator: the shared rule's message, rendered verbatim by the `<mat-error>`. */
function msisdnValidator(c: AbstractControl<string>): ValidationErrors | null {
    const message = msisdnError(c.value ?? '');
    return message ? { msisdn: message } : null;
}

/**
 * The first screen of Link Analysis: where to start, instead of an empty canvas (operator 2026-10-10 - the
 * screen gave a new analyst nothing to do). Each card is one click toward a real graph; a card whose data
 * the Space does not have is left out rather than shown dead.
 */
@Component({
    selector: 'inspecto-link-analysis-starter-cards',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, ReactiveFormsModule],
    host: { class: 'block w-full max-w-3xl p-4' },
    template: `
        <h2 class="m-0 text-lg font-semibold">No graph yet</h2>
        <p class="text-secondary mb-4 mt-1 text-sm">
            Link Analysis draws who is connected to whom from the rows of a Dataset. Pick a way to start.
        </p>
        <ul class="m-0 grid list-none gap-3 p-0 grid-cols-[repeat(auto-fit,minmax(11rem,1fr))]">
            <li>
                <form
                    class="starter-card"
                    data-testid="starter-investigate"
                    aria-labelledby="starter-investigate-title"
                    [formGroup]="numberForm"
                    (ngSubmit)="submitNumber()"
                >
                    <mat-icon class="icon-size-6" svgIcon="heroicons_outline:phone"></mat-icon>
                    <span id="starter-investigate-title" class="block text-sm font-semibold">Investigate a number</span>
                    <span class="text-secondary block text-xs">
                        Seeds the number and expands two degrees of who it is linked to.
                    </span>
                    <mat-form-field class="w-full" subscriptSizing="dynamic">
                        <mat-label>MSISDN</mat-label>
                        <input matInput formControlName="msisdn" inputmode="tel" autocomplete="off" />
                        @if (msisdn.errors?.['msisdn']; as message) {
                            <mat-error>{{ message }}</mat-error>
                        }
                    </mat-form-field>
                    <button mat-stroked-button type="submit">Investigate</button>
                </form>
            </li>
            @if (followView(); as v) {
                <li>
                    <button
                        type="button"
                        class="starter-card"
                        data-testid="starter-follow"
                        (click)="followMoney.emit()"
                    >
                        <mat-icon class="icon-size-6" svgIcon="heroicons_outline:banknotes"></mat-icon>
                        <span class="block text-sm font-semibold">Follow the money (example)</span>
                        <span class="text-secondary block text-xs">
                            Opens the saved view {{ v.name }} and draws it now.
                            {{ followAbout() }}
                        </span>
                    </button>
                </li>
            }
            <li>
                <button
                    type="button"
                    class="starter-card"
                    data-testid="starter-explore"
                    (click)="exploreDataset.emit()"
                >
                    <mat-icon class="icon-size-6" svgIcon="heroicons_outline:circle-stack"></mat-icon>
                    <span class="block text-sm font-semibold">Explore a Dataset</span>
                    <span class="text-secondary block text-xs">
                        Choose a Dataset - the ones that look like links are listed first - and its from/to columns are
                        filled in for you.
                    </span>
                </button>
            </li>
            @if (savedViewCount() > 0) {
                <li>
                    <button
                        type="button"
                        class="starter-card"
                        data-testid="starter-views"
                        (click)="openSavedViews.emit()"
                    >
                        <mat-icon class="icon-size-6" svgIcon="heroicons_outline:folder-open"></mat-icon>
                        <span class="block text-sm font-semibold">Open a saved view</span>
                        <span class="text-secondary block text-xs">
                            {{ savedViewCount() }} saved {{ savedViewCount() === 1 ? 'view' : 'views' }} in this Space.
                            A saved view re-reads live data; it is not evidence.
                        </span>
                    </button>
                </li>
            }
        </ul>
    `,
    styles: `
        .starter-card {
            display: flex;
            flex-direction: column;
            gap: 0.25rem;
            width: 100%;
            height: 100%;
            padding: 0.75rem;
            text-align: left;
            border: 1px solid var(--gamma-border);
            border-radius: 0.5rem;
            background: var(--gamma-bg-card);
            cursor: pointer;
        }
        .starter-card:hover {
            background: var(--gamma-bg-hover);
        }
        /* the number card is a form, not a button: only its controls are interactive */
        form.starter-card {
            cursor: auto;
        }
        form.starter-card:hover {
            background: var(--gamma-bg-card);
        }
        .starter-card:focus-visible {
            outline: 2px solid currentColor;
            outline-offset: 2px;
        }
    `,
})
export class LinkAnalysisStarterCardsComponent {
    /** The ready-made "follow the money" saved view, or null (the card is then not shown). */
    readonly followView = input<LinkAnalysisView | null>(null);
    readonly savedViewCount = input(0);
    readonly followMoney = output<void>();
    readonly exploreDataset = output<void>();
    readonly openSavedViews = output<void>();
    /** "Investigate a number": the MSISDN, separators dropped. */
    readonly investigateNumber = output<string>();

    readonly msisdn = new FormControl('', { nonNullable: true, validators: [msisdnValidator] });
    readonly numberForm = new FormGroup({ msisdn: this.msisdn });

    submitNumber(): void {
        if (this.msisdn.invalid) {
            this.msisdn.markAsTouched();
            return;
        }
        this.investigateNumber.emit(normaliseMsisdn(this.msisdn.value));
    }

    readonly followAbout = computed(() => oneLine(this.followView()?.description, 110));
}
