import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { DashboardHeader, asOfLabel } from './dashboard-types';

/**
 * UIE-5 — the line under a Dashboard's title in every viewer (Menu viewer, public share viewer): the question
 * the page answers, the day its figures are as of, and an *Illustrative data* chip on a STAGED page. The host
 * owns the title (and the page's `<h1>`); this renders no heading, and nothing at all when the Dashboard
 * declares none of the three.
 */
@Component({
    selector: 'app-dashboard-header',
    standalone: true,
    imports: [ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (shown()) {
            <div class="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-sm" data-testid="dashboard-header">
                @if (header()?.description; as description) {
                    <p class="text-secondary min-w-0 truncate" [title]="description">{{ description }}</p>
                }
                @if (asOf(); as label) {
                    <span class="text-secondary whitespace-nowrap">As of {{ label }}</span>
                }
                @if (header()?.illustrative) {
                    <inspecto-chip variant="soft" tone="primary">
                        <span title="The figures on this page are synthetic, shown to illustrate the view"
                            >Illustrative data</span
                        >
                    </inspecto-chip>
                }
            </div>
        }
    `,
})
export class DashboardHeaderComponent {
    readonly header = input<DashboardHeader | null | undefined>(null);

    readonly asOf = computed(() => {
        const v = this.header()?.asOf;
        return v ? asOfLabel(v) : '';
    });

    readonly shown = computed(() => {
        const h = this.header();
        return !!(h?.description || h?.asOf || h?.illustrative);
    });
}
