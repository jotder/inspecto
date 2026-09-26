import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';
import { SessionService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { environment } from 'environments/environment';

/**
 * Professional-edition sign-in screen (W6d). A single "Sign in with SSO" action that kicks off the
 * Authorization-Code + PKCE redirect to the IAM (Keycloak/WSO2) via {@link SessionService.beginLogin}.
 * Never reached on Personal / offline: {@link authGuard} only routes here when OIDC is on and there is
 * no live session, and this component itself bounces back to the app if a session already exists (e.g.
 * a returning user whose refresh cookie was resumed at startup).
 */
@Component({
    selector: 'inspecto-sign-in',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, InspectoAlertComponent],
    template: `
        <div class="flex min-h-screen w-full flex-col md:flex-row">
            <!-- Left: whose deployment this is. Branding comes from the bootstrap payload, because
                 /settings/branding is auth-gated and would 401 on exactly this screen. -->
            <div class="bg-default flex flex-col justify-between gap-10 p-8 md:w-1/2 md:p-12 lg:p-16">
                <div class="flex items-center gap-3">
                    <img class="h-8" src="assets/images/logo/inspecto-logo.svg" alt="" />
                    <span class="text-lg font-semibold">Inspecto</span>
                </div>

                <div class="flex flex-col gap-6">
                    @if (logo() || caption()) {
                        <div class="flex items-center gap-3">
                            @if (logo(); as src) {
                                <img class="h-9" [src]="src" alt="" />
                            }
                            @if (caption(); as c) {
                                <span class="text-secondary text-lg italic">{{ c }}</span>
                            }
                        </div>
                    }
                    <!-- R2-17: a demo build is shown to business audiences — neutral copy, no builder jargon. -->
                    @if (demo()) {
                        <h1 class="max-w-xl text-4xl font-bold leading-tight tracking-tight md:text-5xl">
                            Inspecto demonstration
                        </h1>
                        <p class="text-secondary max-w-md text-lg">
                            A demonstration running on this computer only. Choose a Demo User to see Inspecto as that
                            person would.
                        </p>
                    } @else {
                        <h1 class="max-w-xl text-4xl font-bold leading-tight tracking-tight md:text-5xl">
                            Every Pipeline, every Run, one place to see it.
                        </h1>
                        <p class="text-secondary max-w-md text-lg">
                            Onboard Streams, author Pipelines, watch Expectations hold, and hand the Business Lens a
                            Dataset it can trust.
                        </p>
                    }
                </div>

                <span class="text-secondary text-sm">
                    {{ footer() }}
                    @if (version(); as v) {
                        <span class="text-hint ml-2 font-mono" data-testid="product-version">v{{ v }}</span>
                    }
                </span>
            </div>

            <!-- Right: the one action. -->
            <div class="flex flex-auto items-center justify-center p-8 md:w-1/2">
                <div class="bg-card w-full max-w-sm rounded-2xl p-8 text-center shadow-lg">
                    <div
                        class="text-primary bg-primary-50 dark:bg-primary-900 mx-auto flex h-12 w-12 items-center justify-center rounded-xl"
                    >
                        <mat-icon
                            class="icon-size-6"
                            [svgIcon]="demo() ? 'heroicons_outline:user-circle' : 'heroicons_outline:lock-closed'"
                        ></mat-icon>
                    </div>
                    <h2 class="mt-5 text-2xl font-semibold">Sign in</h2>
                    <!-- R2-17: demo sign-in is NOT secured and has no SSO — never claim either above its warning. -->
                    <p class="text-secondary mt-2">
                        @if (demo()) {
                            Choose a Demo User to continue.
                        } @else {
                            This workspace is secured. Continue with your organisation's single sign-on.
                        }
                    </p>
                    @if (failed()) {
                        <inspecto-alert class="mt-4 block text-left" variant="error" title="Sign-in failed">
                            We couldn't complete sign-in. Please try again.
                        </inspecto-alert>
                    }
                    @if (demo()) {
                        <inspecto-alert class="mt-4 block text-left" variant="warning" title="Demo sign-in">
                            Not secure, local only. Pick who you are for this demo.
                        </inspecto-alert>
                        <ul class="mt-4 flex flex-col gap-2 text-left" aria-label="Demo Users">
                            @for (u of demoUsers(); track u.id) {
                                <li>
                                    <button
                                        mat-stroked-button
                                        class="w-full"
                                        [disabled]="busy()"
                                        [attr.data-demo-user]="u.id"
                                        (click)="signIn(u.id)"
                                    >
                                        <span class="flex w-full flex-col items-start py-1">
                                            <span class="font-medium">{{ u.displayName }}</span>
                                            <span class="text-secondary text-sm">{{ u.title }}</span>
                                        </span>
                                    </button>
                                </li>
                            }
                        </ul>
                    } @else {
                        <button
                            mat-flat-button
                            color="primary"
                            class="mt-6 w-full"
                            [disabled]="busy()"
                            (click)="signIn()"
                        >
                            @if (busy()) {
                                <mat-progress-spinner diameter="20" mode="indeterminate" aria-label="Signing in" />
                            } @else {
                                <ng-container>
                                    <mat-icon svgIcon="heroicons_outline:lock-closed" />
                                    <span>Sign in with SSO</span>
                                </ng-container>
                            }
                        </button>
                    }
                    <p class="text-secondary mt-6 border-t pt-4 text-sm">Need access? Ask your space administrator.</p>
                </div>
            </div>
        </div>
    `,
})
export class SignInComponent implements OnInit {
    private session = inject(SessionService);
    private router = inject(Router);

    readonly busy = signal(false);
    readonly failed = signal(false);

    /** Deployment branding, from the bootstrap payload — `BrandingService` reads an auth-gated route and
     *  would 401 here. Each falls back to the shipped default when the operator authored none. */
    readonly logo = computed(() => this.session.branding().logoDataUrl);
    readonly caption = computed(() => this.session.branding().caption);
    readonly footer = computed(() => this.session.branding().footerText ?? environment.footerText);
    /** HOME-VERSION-1: the version the backend reports — the field a support call reads aloud. Absent until known. */
    readonly version = computed(() => this.session.version());
    /** DEMO-AUTH-1: a demo build's Demo Users — non-empty replaces the SSO button with a picker. */
    readonly demoUsers = computed(() => this.session.demoUsers());
    /** R2-17: demo sign-in is on — the page must not claim security or SSO, nor pitch builder copy. */
    readonly demo = computed(() => this.demoUsers().length > 0);

    ngOnInit(): void {
        // Already signed in (or Personal/offline where login is never required) → straight into the app.
        if (!this.session.loginRequired()) {
            this.router.navigate(['/']);
            return;
        }
        this.failed.set(sessionStorage.getItem('inspecto.signInFailed') === '1');
        sessionStorage.removeItem('inspecto.signInFailed');
    }

    async signIn(demoUserId?: string): Promise<void> {
        this.busy.set(true);
        await this.session.beginLogin(demoUserId);
    }
}
