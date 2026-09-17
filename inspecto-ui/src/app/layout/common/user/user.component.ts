import { BooleanInput } from '@angular/cdk/coercion';
import {
    ChangeDetectionStrategy,
    ChangeDetectorRef,
    Component,
    inject,
    Input,
    OnDestroy,
    OnInit,
    ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { Router } from '@angular/router';
import { UserService } from 'app/core/user/user.service';
import { User } from 'app/core/user/user.types';
import { SessionService } from 'app/inspecto/api';
import { environment } from 'environments/environment';
import { Subject, takeUntil } from 'rxjs';

@Component({
    selector: 'user',
    templateUrl: './user.component.html',
    encapsulation: ViewEncapsulation.None,
    changeDetection: ChangeDetectionStrategy.OnPush,
    exportAs: 'user',
    imports: [MatButtonModule, MatMenuModule, MatIconModule, MatDividerModule],
})
export class UserComponent implements OnInit, OnDestroy {
    static ngAcceptInputType_showAvatar: BooleanInput;
    private session = inject(SessionService);

    /** Personal/offline is auth-free — the whole menu is hidden rather than offer a no-op Sign out. */
    readonly signedInEdition = this.session.authMode;
    /** The signed-in principal from `bootstrap.session.actor` (wired 2026-09-15 — this used to be a
     *  hardcoded '' fed by a `UserService.user$` that nothing ever populated). */
    readonly actor = this.session.actor;

    @Input() showAvatar: boolean = true;
    user: User;
    private _unsubscribeAll: Subject<null> = new Subject<null>();

    private readonly _changeDetectorRef = inject(ChangeDetectorRef);
    private readonly _router = inject(Router);
    private readonly _userService = inject(UserService);

    // -----------------------------------------------------------------------------------------------------
    // @ Lifecycle hooks
    // -----------------------------------------------------------------------------------------------------

    /**
     * On init
     */
    ngOnInit(): void {
        this._userService.user$.pipe(takeUntil(this._unsubscribeAll)).subscribe((user: User) => {
            this.user = user;

            // Mark for check
            this._changeDetectorRef.markForCheck();
        });
    }

    /**
     * On destroy
     */
    ngOnDestroy(): void {
        // Unsubscribe from all subscriptions
        this._unsubscribeAll.next(null);
        this._unsubscribeAll.complete();
    }

    // -----------------------------------------------------------------------------------------------------
    // @ Public methods
    // -----------------------------------------------------------------------------------------------------

    /**
     * Update the user status
     *
     * @param status
     */
    updateUserStatus(status: string): void {
        // Return if user is not available
        if (!this.user) {
            return;
        }

        // Update the user
        this._userService
            .update({
                ...this.user,
                status,
            })
            .subscribe();
    }

    /**
     * Sign out — the one path, delegating to {@link SessionService.logout}: revoke the refresh cookie at
     * the backend, drop the in-memory token, then end the SSO session at the provider when an
     * `endSessionUrl` is configured.
     *
     * Replaces two broken predecessors (2026-07-26, BACKLOG §5). `signOut()` navigated to `/logout` — a
     * route that does not exist and has no wildcard fallback, so the navigation simply errored and the
     * user stayed signed in — and `onclicklogout()` first called `localStorage.clear()`, which took every
     * unrelated `inspecto.*` preference (grid layouts, lens, current space, SQL history) with it. Neither
     * ever told the backend, so the httpOnly refresh cookie survived a "sign out".
     */
    signOut(): void {
        this.session.logout();
    }

    onMyProfileClick(): void {
        const url = environment.gatewayUrl + '/apps/profile';
        window.open(url, '_blank');
    }

    onMyNotificationClick(): void {
        const url = environment.gatewayUrl + '/apps/manageNotification/userNotifications';
        window.open(url, '_blank');
    }
}
