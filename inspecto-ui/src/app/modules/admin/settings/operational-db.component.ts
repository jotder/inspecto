import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';

import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import {
    apiErrorMessage,
    OperationalDbFamily,
    OperationalDbReport,
    OperationalDbTestResult,
    SystemService,
} from 'app/inspecto/api';

/**
 * Settings ▸ **Operational database** (PG-1 Open 2, Stage 1) — what this deployment is actually using
 * for its transactional stores, and whether a proposed connection works.
 *
 * <p>⚠ **Read and validate only, by decision (2026-08-15).** This screen deliberately has no Save. The
 * process serving it is the one that needs the database, so it cannot configure its own dependency and
 * no change could take effect without a restart; persisting from here would create a second declaration
 * of the same fact beside `-D`. So it reports, it validates, and it shows the operator the exact flag to
 * set in their own deployment tooling. ⛔ Do not "finish" it with a Save button.
 *
 * <p>⛔ The password field takes a secret REFERENCE only — the server 422s a literal.
 */
@Component({
    selector: 'inspecto-operational-db',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    template: `
        <div class="flex flex-col gap-6">
            <header>
                <h1 class="text-2xl font-semibold">Operational database</h1>
                <p class="text-secondary mt-1 text-sm">
                    Where this deployment keeps its transactional stores. Business data is never here — it stays Parquet.
                </p>
            </header>

            <inspecto-alert variant="info" title="Reported here, changed in your deployment tooling">
                This process is served by the database it would be configuring, so a change can only take effect on
                restart. Set the flags below where you launch Inspecto — this screen tells you what is in force and
                whether a connection works.
            </inspecto-alert>

            @if (loading()) {
                <div class="flex items-center gap-2 py-6">
                    <mat-spinner diameter="20"></mat-spinner>
                    <span class="text-secondary text-sm">Reading the current configuration…</span>
                </div>
            } @else if (!report()) {
                <inspecto-empty-state
                    icon="heroicons_outline:circle-stack"
                    title="Configuration unavailable"
                    [message]="loadError()"
                ></inspecto-empty-state>
            } @else {
                <section class="flex flex-col gap-3">
                    <h2 class="text-lg font-medium">Engine</h2>
                    <div class="flex flex-wrap items-center gap-3">
                        <inspecto-status-badge [value]="report()!.engine"></inspecto-status-badge>
                        <span class="font-mono text-sm">-D{{ report()!.engineProperty }}={{ report()!.engine }}</span>
                        @if (report()!.engine === 'postgres' && !report()!.driverAvailable) {
                            <inspecto-alert variant="error" title="PostgreSQL driver missing">
                                Drop <code>postgresql.jar</code> beside <code>inspecto.jar</code> — the
                                Standard/Enterprise bundle ships it as a sidecar.
                            </inspecto-alert>
                        }
                    </div>
                </section>

                <section class="flex flex-col gap-3">
                    <h2 class="text-lg font-medium">Store families</h2>
                    <p class="text-secondary text-sm">
                        Each family reports where its value came from, so you can tell which flag is in charge.
                    </p>
                    <div class="overflow-x-auto">
                        <table class="w-full text-sm">
                            <thead>
                                <tr class="text-secondary text-left">
                                    <th scope="col" class="py-2 pr-4 font-medium">Family</th>
                                    <th scope="col" class="py-2 pr-4 font-medium">State</th>
                                    <th scope="col" class="py-2 pr-4 font-medium">Connection</th>
                                    <th scope="col" class="py-2 font-medium">Set with</th>
                                </tr>
                            </thead>
                            <tbody>
                                @for (f of report()!.families; track f.family) {
                                    <tr class="border-t">
                                        <th scope="row" class="py-2 pr-4 text-left font-normal">{{ f.label }}</th>
                                        <td class="py-2 pr-4">
                                            <inspecto-status-badge [value]="stateOf(f)"></inspecto-status-badge>
                                        </td>
                                        <td class="py-2 pr-4 font-mono text-xs break-all">{{ f.url ?? '—' }}</td>
                                        <td class="py-2 font-mono text-xs break-all">
                                            -D{{ f.enabled ? f.urlProperty : f.backendProperty }}
                                        </td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                    </div>
                </section>

                <section class="flex flex-col gap-3">
                    <h2 class="text-lg font-medium">Test a connection</h2>
                    <p class="text-secondary text-sm">
                        Opens the connection for real and runs <code>SELECT 1</code>. Nothing is saved.
                    </p>
                    <form [formGroup]="form" (ngSubmit)="test()" class="flex flex-col gap-2">
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>JDBC URL</mat-label>
                            <input matInput formControlName="url" placeholder="jdbc:postgresql://host:5432/inspecto" />
                            @if (form.controls.url.hasError('required')) {
                                <mat-error>A JDBC URL is required.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>User</mat-label>
                            <input matInput formControlName="user" />
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Password reference</mat-label>
                            <input matInput formControlName="password" [placeholder]="refExample" />
                            <mat-hint>{{ refHint }}</mat-hint>
                            @if (form.controls.password.hasError('literal')) {
                                <mat-error>Use a secret reference, not the password itself.</mat-error>
                            }
                        </mat-form-field>
                        <div>
                            <button mat-flat-button color="primary" type="submit" [disabled]="testing()">
                                @if (testing()) {
                                    <mat-spinner diameter="16" class="mr-2"></mat-spinner>
                                }
                                Test connection
                            </button>
                        </div>
                    </form>
                    @if (result(); as r) {
                        <inspecto-alert
                            [variant]="r.outcome === 'OK' ? 'success' : 'warning'"
                            [title]="r.outcome === 'OK' ? 'Connected' : r.outcome"
                        >
                            {{ r.detail }}
                        </inspecto-alert>
                    }
                </section>
            }
        </div>
    `,
})
export class OperationalDbComponent {
    private system = inject(SystemService);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);

    /** ⚠ Carried as properties, not template text: Angular parses a bare `${…}` as an ICU message. */
    readonly refExample = '${ENV:PGPASSWORD}';
    readonly refHint = 'A reference only — ${ENV:…}, ${KEYSTORE:…} or ${FILE:…}. A literal is refused.';

    readonly loading = signal(true);
    readonly loadError = signal('Could not read the operational-database configuration.');
    readonly report = signal<OperationalDbReport | null>(null);
    readonly testing = signal(false);
    readonly result = signal<OperationalDbTestResult | null>(null);

    /**
     * ⚠ The literal-password guard is mirrored here so the operator is told inline rather than by a 422.
     * It is NOT the enforcement — the server refuses independently, which is what actually protects them.
     */
    readonly form = this.fb.nonNullable.group({
        url: this.fb.nonNullable.control('', Validators.required),
        user: this.fb.nonNullable.control(''),
        password: this.fb.nonNullable.control('', (c) =>
            c.value && !String(c.value).startsWith('${') ? { literal: true } : null,
        ),
    });

    constructor() {
        this.system.operationalDb().subscribe({
            next: (r) => {
                this.report.set(r);
                this.loading.set(false);
            },
            error: (e) => {
                this.loadError.set(apiErrorMessage(e, 'Could not read the operational-database configuration.'));
                this.loading.set(false);
            },
        });
    }

    /** The badge value — text, never colour alone, and it says WHY a family is off. */
    stateOf(f: OperationalDbFamily): string {
        return f.enabled ? 'active' : 'disabled';
    }

    test(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.testing.set(true);
        this.result.set(null);
        const v = this.form.getRawValue();
        this.system
            .testOperationalDb({ url: v.url, user: v.user || undefined, password: v.password || undefined })
            .subscribe({
                next: (r) => {
                    this.result.set(r);
                    this.testing.set(false);
                },
                error: (e) => {
                    this.toastr.error(apiErrorMessage(e, 'The connection test could not run.'));
                    this.testing.set(false);
                },
            });
    }
}
