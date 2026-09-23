import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import {
    InstantiateTemplateResult,
    InvService,
    InvestigationLog,
    InvestigationMeasure,
    InvestigationMeasures,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { investigationErrorMessage } from './investigation-state';
import {
    InstantiateTemplateDialog,
    SaveTemplateDialog,
    WatchMeasureDialog,
    isUnavailable,
} from './link-analysis-template.dialogs';

/**
 * **Link Analysis — Template & Measures** (LA-23, SPA half): the Measures strip over the open Investigation's
 * Working Set (`GET …/measures`), "Watch" on each (an Alert Rule bound through `POST …/alert-rules`), and the
 * Investigation Template pair — Save as template (`POST …/template`) and Instantiate (`POST
 * /inv/investigation-templates/{id}/instantiate`), which emits the new Investigation for the host to open.
 */
@Component({
    selector: 'inspecto-link-analysis-template-measures',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-xs" aria-label="Measures and templates">
            <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Measures</h3>
            @if (error()) {
                <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Measures">{{
                    error()
                }}</inspecto-alert>
            }
            @if (measures(); as m) {
                <ul class="m-0 flex list-none flex-wrap gap-1 p-0" aria-label="Measures strip">
                    @for (x of m.measures; track x.name) {
                        <li
                            class="flex items-center gap-1 rounded-md border px-2 py-0.5"
                            style="border-color: var(--gamma-border)"
                        >
                            <span>{{ x.name }}</span>
                            <strong class="tabular-nums">{{ x.value ?? '—' }}</strong>
                            <button
                                mat-button
                                class="!min-w-0 !px-1"
                                (click)="watch(x)"
                                [attr.aria-label]="'Watch the ' + x.name + ' Measure with an Alert Rule'"
                            >
                                Watch
                            </button>
                        </li>
                    }
                </ul>
                <p class="text-secondary m-0">At step {{ m.head.step }}.</p>
            }
            <div class="flex flex-wrap gap-1">
                <button mat-stroked-button [disabled]="!log()?.entries?.length" (click)="saveTemplate()">
                    Save as template…
                </button>
                <button mat-button (click)="instantiate()">Instantiate a template…</button>
            </div>
        </section>
    `,
})
export class LinkAnalysisTemplateMeasuresComponent {
    private inv = inject(InvService);
    private dialog = inject(MatDialog);

    readonly investigationId = input.required<string>();
    readonly log = input<InvestigationLog | null>(null);
    /** A template was instantiated — the host remembers and opens the new Investigation. */
    readonly instantiated = output<InstantiateTemplateResult>();

    readonly measures = signal<InvestigationMeasures | null>(null);
    readonly error = signal('');
    readonly unavailable = signal(false);

    constructor() {
        // Re-read whenever the Investigation or its log moves — Measures are a function of the head.
        effect(() => {
            this.investigationId();
            this.log();
            untracked(() => void this.load());
        });
    }

    async load(): Promise<void> {
        const id = this.investigationId();
        this.error.set('');
        this.unavailable.set(false);
        try {
            const m = await firstValueFrom(this.inv.investigationMeasures(id));
            if (id === this.investigationId()) this.measures.set(m);
        } catch (err) {
            this.measures.set(null);
            this.unavailable.set(isUnavailable(err));
            this.error.set(investigationErrorMessage(err, 'Could not read the Measures.'));
        }
    }

    watch(measure: InvestigationMeasure): void {
        this.dialog.open(WatchMeasureDialog, {
            data: { investigationId: this.investigationId(), measure },
            width: '32rem',
        });
    }

    saveTemplate(): void {
        const log = this.log();
        this.dialog.open(SaveTemplateDialog, {
            data: { investigationId: this.investigationId(), entries: log?.entries ?? [], truncated: !!log?.truncated },
            width: '36rem',
        });
    }

    instantiate(): void {
        this.dialog
            .open(InstantiateTemplateDialog, { width: '44rem' })
            .afterClosed()
            .subscribe((res?: InstantiateTemplateResult) => {
                if (res) this.instantiated.emit(res);
            });
    }
}
