import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { SpaceLocationCheckResult, spaceLocationCheckLabel } from 'app/inspecto/api';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';

/**
 * The checks the server ran on one folder (`POST /spaces/_locations/check`, SPACE-LOCATIONS-1 D7): one line per check,
 * passed or failed in words as well as colour, with the server's plain reason. Presentational only.
 */
@Component({
    selector: 'app-location-checks',
    standalone: true,
    imports: [StatusBadgeComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (result(); as r) {
            <div class="flex flex-col gap-1" data-testid="location-checks">
                <p class="m-0 text-sm font-medium" role="status">
                    {{ heading() ? heading() + ': ' : '' }}{{ summary() }}
                </p>
                @if (r.checks.length) {
                    <ul class="m-0 flex list-none flex-col gap-1 p-0" [attr.aria-label]="heading() || 'Checks'">
                        @for (c of r.checks; track c.check) {
                            <li class="flex items-start gap-2 text-sm" [attr.data-ok]="c.ok">
                                <inspecto-status-badge
                                    variant="dot"
                                    [value]="c.ok ? 'PASS' : 'FAIL'"
                                    [label]="c.ok ? 'Passed' : 'Failed'"
                                ></inspecto-status-badge>
                                <span class="min-w-0">
                                    <span>{{ label(c.check) }}</span>
                                    @if (c.reason) {
                                        <span class="text-secondary block text-xs">{{ c.reason }}</span>
                                    }
                                </span>
                            </li>
                        }
                    </ul>
                }
            </div>
        }
    `,
})
export class LocationChecksComponent {
    readonly result = input<SpaceLocationCheckResult | null>(null);
    /** What was checked, such as "Space folder"; named in the summary line and the list. */
    readonly heading = input('');

    protected readonly label = spaceLocationCheckLabel;

    protected readonly summary = computed(() => {
        const r = this.result();
        if (!r) return '';
        const failed = r.checks.filter((c) => !c.ok).length;
        if (r.ok) return 'every check passed.';
        if (failed === 0) return 'the folder cannot be used.';
        return failed === 1 ? '1 check failed.' : `${failed} checks failed.`;
    });
}
