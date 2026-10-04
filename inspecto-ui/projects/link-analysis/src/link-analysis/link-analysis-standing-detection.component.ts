import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { LensService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InvService, InvestigationHeader, StandingDetectionEnabled } from '@inspecto/link-analysis/api/inv.service';
import { isUnavailable } from './link-analysis-template.dialogs';
import { standingDetectionErrorMessage } from './standing-detection';

/**
 * **Standing detection — enable** (LA-LIVE-DETECTION-1 LD-6). A bound value-measure Alert Rule is INERT until the
 * Investigation's owner enables it: the server then records the sweep principal `sweep:<id>` (no capability of its
 * own) and the masking basis, and re-decides the owner's access before every read. Owner-only (the server's
 * 403 is the owner check — the SPA host edge exposes no actor), and gated on `canAuthorAlertRules`. ⚠ There is no read-back route yet, so the status shown is this session's answer;
 * Disable (LD-5) is offered once enabled in this session. Editing a rule in place and listing bound rules are still owed.
 */
@Component({
    selector: 'inspecto-link-analysis-standing-detection',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2" aria-labelledby="la-standing-heading" data-test="standing">
            <h4 id="la-standing-heading" class="m-0 text-xs font-semibold">Standing detection</h4>
            @if (!canAuthor()) {
                <p class="text-secondary m-0" data-test="standing-no-cap">
                    Enabling standing detection needs the Alert Rule authoring capability. Until it is enabled this rule
                    is not evaluated.
                </p>
            } @else {
                <p class="text-secondary m-0" data-test="standing-status">
                    Status: {{ enabled() ? 'Enabled' : 'Not enabled — this rule is not evaluated yet' }}.
                </p>
                <p class="text-secondary m-0">
                    Only the Investigation's owner can enable this (the server refuses anyone else). Enabling lets a
                    scheduled sweep read Dataset "{{ investigation().dataset }}" with your access, checked again before
                    every read. It computes and discloses counts only, and stops (recorded) if your access, your lead
                    role, an access policy or the masking changes.
                </p>
                <div>
                    <button
                        mat-stroked-button
                        type="button"
                        data-test="standing-enable"
                        [disabled]="busy()"
                        (click)="enable()"
                    >
                        {{ enabled() ? 'Re-enable standing detection' : 'Enable standing detection' }}
                    </button>
                    @if (enabled()) {
                        <button
                            mat-stroked-button
                            type="button"
                            data-test="standing-disable"
                            [disabled]="busy()"
                            (click)="disable()"
                        >
                            Disable standing detection
                        </button>
                    }
                </div>
            }
            @if (error()) {
                <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Standing detection">{{
                    error()
                }}</inspecto-alert>
            }
            @if (disabledNote()) {
                <p class="text-secondary m-0" data-test="standing-disabled">
                    Standing detection is off. The rule stays bound; a sweep refuses it as NOT_ENABLED until the owner
                    enables it again.
                </p>
            }
            @if (enabled(); as e) {
                <inspecto-alert variant="success" title="Standing detection enabled">
                    <span data-test="standing-principal">Sweep principal: {{ e.principal }}.</span>
                    <span data-test="standing-masking">
                        Masking recorded: mode {{ e.masking.mode }},
                        {{
                            e.masking.columns.length
                                ? e.masking.columns.length + ' masked column(s): ' + e.masking.columns.join(', ')
                                : 'no masked columns'
                        }}.
                    </span>
                    Enabled {{ e.enabledAt }}{{ e.replaced ? ' (re-enabled: the earlier snapshot was replaced)' : '' }}.
                </inspecto-alert>
            }
        </section>
    `,
})
export class LinkAnalysisStandingDetectionComponent {
    private inv = inject(InvService);
    private lens = inject(LensService);

    readonly investigation = input.required<InvestigationHeader>();
    /** The bound value-measure Alert Rule's name. */
    readonly rule = input.required<string>();

    readonly canAuthor = computed(() => this.lens.canAuthorAlertRules());

    readonly busy = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly enabled = signal<StandingDetectionEnabled | null>(null);
    readonly disabledNote = signal(false);

    async enable(): Promise<void> {
        if (this.busy()) return;
        this.busy.set(true);
        this.error.set('');
        this.unavailable.set(false);
        this.disabledNote.set(false);
        try {
            this.enabled.set(
                await firstValueFrom(this.inv.enableStandingDetection(this.investigation().id, this.rule())),
            );
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            this.error.set(standingDetectionErrorMessage(err));
        } finally {
            this.busy.set(false);
        }
    }

    /** LD-5 disable: narrows only, idempotent; any author of Alert Rules who can open the Investigation may press it. */
    async disable(): Promise<void> {
        if (this.busy()) return;
        this.busy.set(true);
        this.error.set('');
        this.unavailable.set(false);
        try {
            await firstValueFrom(this.inv.disableStandingDetection(this.investigation().id, this.rule()));
            this.enabled.set(null);
            this.disabledNote.set(true);
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            this.error.set(standingDetectionErrorMessage(err));
        } finally {
            this.busy.set(false);
        }
    }
}
