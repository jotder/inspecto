import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import {
    AlertSeverity,
    BoundAlertRule,
    EditAlertRuleRequest,
    InvService,
    InvestigationHeader,
} from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage } from '@inspecto/core/api';
import { LinkAnalysisStandingDetectionComponent } from './link-analysis-standing-detection.component';
import { isUnavailable } from './link-analysis-template.dialogs';

/**
 * **Bound rules** (LA-LIVE-DETECTION-1 LD-7): the Alert Rules bound to the open Investigation, read back from
 * `GET /inv/investigations/{id}/alert-rules` so a reload shows each rule's real standing-detection state. A rule can be
 * edited in place (severity, and the threshold of a Working-Set rule) through the existing `PUT …/alert-rules/{rule}` —
 * which drops the owner's recorded authority, so the owner enables it again. The name never changes.
 */
@Component({
    selector: 'inspecto-link-analysis-bound-rules',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        LinkAnalysisStandingDetectionComponent,
    ],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-3" aria-labelledby="la-bound-heading" data-test="bound-rules">
            <h4 id="la-bound-heading" class="m-0 text-xs font-semibold">Bound Alert Rules</h4>
            @if (error()) {
                <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Bound Alert Rules">{{
                    error()
                }}</inspecto-alert>
            }
            @if (loaded() && !rules().length && !error()) {
                <p class="text-secondary m-0" data-test="bound-none">No Alert Rule is bound to this Investigation.</p>
            }
            @for (r of rules(); track key(r)) {
                <div class="flex flex-col gap-2" data-test="bound-rule">
                    <p class="m-0 font-semibold" data-test="bound-name">
                        {{ r.rule['name'] }} — {{ r.rule['severity'] }}
                        <span class="text-secondary font-normal">
                            ({{ r.valueMeasure ? 'value Measure over the live Dataset' : 'Working Set Measure' }})
                        </span>
                    </p>
                    @if (r.edited) {
                        <inspecto-alert variant="warning" title="Changed since it was bound">
                            This rule was changed outside the Investigation, so a sweep refuses it. Edit and save it
                            here to bind it again.
                        </inspecto-alert>
                    }
                    @if (editing() === r.rule['name']) {
                        <form class="flex flex-col gap-2" aria-label="Edit Alert Rule" (ngSubmit)="save(r)">
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>Severity</mat-label>
                                <select
                                    matNativeControl
                                    name="severity"
                                    [(ngModel)]="severity"
                                    data-test="edit-severity"
                                >
                                    <option value="INFO">Info</option>
                                    <option value="WARNING">Warning</option>
                                    <option value="CRITICAL">Critical (also opens an Incident)</option>
                                </select>
                            </mat-form-field>
                            @if (!r.valueMeasure) {
                                <mat-form-field subscriptSizing="dynamic">
                                    <mat-label>Threshold</mat-label>
                                    <input
                                        matInput
                                        type="number"
                                        name="threshold"
                                        [(ngModel)]="threshold"
                                        data-test="edit-threshold"
                                    />
                                </mat-form-field>
                            }
                            <p class="text-secondary m-0">
                                Saving drops standing detection for this rule: the owner enables it again.
                            </p>
                            <div>
                                <button
                                    mat-flat-button
                                    color="primary"
                                    type="submit"
                                    data-test="edit-save"
                                    [disabled]="busy()"
                                >
                                    Save
                                </button>
                                <button mat-button type="button" data-test="edit-cancel" (click)="editing.set('')">
                                    Cancel
                                </button>
                            </div>
                        </form>
                    } @else {
                        <div>
                            <button mat-stroked-button type="button" data-test="edit-open" (click)="open(r)">
                                Edit rule
                            </button>
                        </div>
                    }
                    @if (r.valueMeasure) {
                        <inspecto-link-analysis-standing-detection
                            [investigation]="investigation()"
                            [rule]="$any(r.rule['name'])"
                            [initial]="r.standingDetection"
                        ></inspecto-link-analysis-standing-detection>
                    }
                </div>
            }
        </section>
    `,
})
export class LinkAnalysisBoundRulesComponent {
    private inv = inject(InvService);

    readonly investigation = input.required<InvestigationHeader>();
    /** Bump (or change) to re-read the list — e.g. after a new rule is bound. */
    readonly reloadKey = input<unknown>(null);

    readonly rules = signal<BoundAlertRule[]>([]);
    readonly loaded = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly busy = signal(false);
    readonly editing = signal('');
    severity: AlertSeverity = 'WARNING';
    threshold = 0;

    constructor() {
        effect(() => {
            const id = this.investigation().id;
            this.reloadKey();
            void this.load(id);
        });
    }

    /** A row is rebuilt when its severity, threshold or standing state changes, so the embedded panel re-reads `initial`. */
    key(r: BoundAlertRule): string {
        return `${r.rule['name']}|${r.rule['severity']}|${r.rule['threshold']}|${r.standingDetection.enabled}|${r.edited}`;
    }

    async load(id = this.investigation().id): Promise<void> {
        try {
            this.rules.set((await firstValueFrom(this.inv.boundAlertRules(id))).rules);
            this.error.set('');
            this.unavailable.set(false);
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            this.error.set(apiErrorMessage(err, 'Could not list the bound Alert Rules.'));
        } finally {
            this.loaded.set(true);
        }
    }

    open(r: BoundAlertRule): void {
        this.severity = r.rule['severity'] as AlertSeverity;
        this.threshold = Number(r.rule['threshold'] ?? 0);
        this.editing.set(String(r.rule['name']));
    }

    async save(r: BoundAlertRule): Promise<void> {
        if (this.busy()) return;
        const name = String(r.rule['name']);
        const body = (
            r.valueMeasure
                ? { name, valueMeasure: r.rule['valueMeasure'], severity: this.severity }
                : {
                      name,
                      relation: r.rule['relation'],
                      measure: r.rule['measure'],
                      comparator: r.rule['comparator'],
                      threshold: Number(this.threshold),
                      severity: this.severity,
                  }
        ) as EditAlertRuleRequest;
        this.busy.set(true);
        this.error.set('');
        try {
            await firstValueFrom(this.inv.editAlertRule(this.investigation().id, name, body));
            this.editing.set('');
            await this.load();
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            this.error.set(apiErrorMessage(err, 'Could not edit the Alert Rule.'));
        } finally {
            this.busy.set(false);
        }
    }
}
