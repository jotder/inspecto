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
    /* eslint-disable @typescript-eslint/naming-convention */
    static ngAcceptInputType_showAvatar: BooleanInput;
    /* eslint-enable @typescript-eslint/naming-convention */
    private session = inject(SessionService);

    /** Personal/offline is auth-free — the whole menu is hidden rather than offer a no-op Sign out. */
    readonly signedInEdition = this.session.authMode;

    @Input() showAvatar: boolean = true;
    user: User;
    user_name: string;
    private _unsubscribeAll: Subject<any> = new Subject<any>();

    /**
     * Constructor
     */
    constructor(
        private _changeDetectorRef: ChangeDetectorRef,
        private _router: Router,
        private _userService: UserService,
    ) {}

    // -----------------------------------------------------------------------------------------------------
    // @ Lifecycle hooks
    // -----------------------------------------------------------------------------------------------------

    /**
     * On init
     */
    ngOnInit(): void {
        // Auth-free shell: no principal to name. The user menu populates from user$ when a user
        // is loaded (Standard/OIDC edition); Personal leaves it blank.
        this.user_name = '';

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

    onMyProfileClick(): any {
        let url = environment.gatewayUrl + '/apps/profile';
        window.open(url, '_blank');
    }

    onMyNotificationClick(): any {
        let url = environment.gatewayUrl + '/apps/manageNotification/userNotifications';
        window.open(url, '_blank');
    }
}
