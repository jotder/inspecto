import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { EventsService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { StandingSweepSummary, standingCodeMeans, summariseStandingEvents } from './standing-detection';

const PAGE = 500;

/**
 * **Standing detection — Monitoring** (LA-LIVE-DETECTION-1 LD-6): how many sweeps ran and how many were refused,
 * and why, from the `LINK_STANDING_DETECTION_SWEPT` / `_REFUSED` audit events. Aggregate only — counts and reason
 * codes; the events name an Investigation and a rule, and this surface shows neither, never an entity id.
 */
@Component({
    selector: 'inspecto-link-analysis-standing-monitor',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-xs" aria-labelledby="la-standing-monitor-heading">
            <h3
                id="la-standing-monitor-heading"
                class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide"
            >
                Standing detection — Monitoring
            </h3>
            <div>
                <button
                    mat-stroked-button
                    type="button"
                    data-test="monitor-refresh"
                    [disabled]="busy()"
                    (click)="load()"
                >
                    {{ summary() ? 'Refresh' : 'Show sweep counts' }}
                </button>
            </div>
            @if (error()) {
                <inspecto-alert variant="info" title="Monitoring">{{ error() }}</inspecto-alert>
            }
            @if (summary(); as s) {
                <p class="m-0" data-test="monitor-counts">
                    {{ s.swept }} {{ s.swept === 1 ? 'sweep' : 'sweeps' }} read their Dataset · {{ s.refused }}
                    {{ s.refused === 1 ? 'sweep' : 'sweeps' }} refused{{
                        s.capped ? ' (at least — the newest ' + page + ' events were counted)' : ''
                    }}.
                </p>
                @if (!s.swept && !s.refused) {
                    <p class="text-secondary m-0" data-test="monitor-none">
                        No events yet: no sweep has run for any bound Alert Rule. A rule is only swept after its
                        Investigation's owner enables standing detection.
                    </p>
                }
                @if (s.refusedByCode.length) {
                    <ul class="m-0 list-disc pl-4" data-test="monitor-refusals">
                        @for (r of s.refusedByCode; track r.code) {
                            <li>
                                <span class="font-semibold">{{ r.code }}</span> × {{ r.count }} — {{ means(r.code) }}
                            </li>
                        }
                    </ul>
                }
            }
        </section>
    `,
})
export class LinkAnalysisStandingMonitorComponent {
    private events = inject(EventsService);

    readonly page = PAGE;
    readonly busy = signal(false);
    readonly error = signal('');
    readonly summary = signal<StandingSweepSummary | null>(null);

    means = standingCodeMeans;

    async load(): Promise<void> {
        if (this.busy()) return;
        this.busy.set(true);
        this.error.set('');
        try {
            const [swept, refused] = await Promise.all([
                firstValueFrom(this.events.search({ type: 'LINK_STANDING_DETECTION_SWEPT', limit: PAGE })),
                firstValueFrom(this.events.search({ type: 'LINK_STANDING_DETECTION_REFUSED', limit: PAGE })),
            ]);
            this.summary.set(summariseStandingEvents(swept, refused, PAGE));
        } catch {
            this.summary.set(null);
            this.error.set('The sweep events could not be read — reading the event log needs its own capability.');
        } finally {
            this.busy.set(false);
        }
    }
}
