import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { SessionService } from 'app/inspecto/api';
import { AlertVariant, InspectoAlertComponent } from 'app/inspecto/components/alert.component';

const capitalise = (s: string): string => s.charAt(0).toUpperCase() + s.slice(1);

/** One banner line, or null when there is nothing to say. Pure, so the spec covers every state without a fixture each. */
export interface EntitlementNotice {
    variant: AlertVariant;
    title: string;
    text: string;
}

/**
 * The words for an entitlement summary (PRODUCT-ASSEMBLY-1 PA-7b, D-PA24). Everyone is told when the install is read-only,
 * because their writes are refused. Only an administrator (`forAdministrator`) is warned before that - expiring,
 * in the grace, or a renewal that was not taken. No file (D-PA23) is said on Home, not here.
 */
export function entitlementNotice(e: ReturnType<SessionService['entitlement']>): EntitlementNotice | null {
    if (!e) return null;
    const admin = e.forAdministrator === true;
    if (e.state === 'read-only')
        return {
            variant: 'error',
            title: 'Read-only',
            // The server's reason already says why and how to resume (an expiry, or a trial build without its file).
            text: admin
                ? `${capitalise(e.reason ?? 'this installation is read-only')}. Nothing was removed.`
                : 'This installation is read-only until an administrator installs a renewed entitlement. You can still view and export.',
        };
    if (!admin) return null;
    if (e.problem) return { variant: 'warning', title: 'Entitlement not renewed', text: e.problem };
    if (e.state === 'grace')
        return {
            variant: 'warning',
            title: 'Entitlement expired',
            text: `It expired on ${e.expires}. Everything still runs until ${e.readOnlyFrom}, when this installation becomes read-only. Install a renewed entitlement before then.`,
        };
    if (e.state === 'expiring' && e.daysLeft != null)
        return {
            variant: 'info',
            title: 'Entitlement expires soon',
            text: `It expires on ${e.expires} (${e.daysLeft === 1 ? '1 day' : `${e.daysLeft} days`} left).`,
        };
    return null;
}

/**
 * The app-wide entitlement banner, mounted beside the connectivity banner in both shells. Renders nothing when there is nothing
 * to say, so it takes no space.
 */
@Component({
    selector: 'inspecto-entitlement-banner',
    standalone: true,
    imports: [InspectoAlertComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (notice(); as n) {
            <div class="px-4 pt-3">
                <inspecto-alert [variant]="n.variant" [title]="n.title">{{ n.text }}</inspecto-alert>
            </div>
        }
    `,
})
export class EntitlementBannerComponent {
    private readonly session = inject(SessionService);
    readonly notice = computed(() => entitlementNotice(this.session.entitlement()));
}
