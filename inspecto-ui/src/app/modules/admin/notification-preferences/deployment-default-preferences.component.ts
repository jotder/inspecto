import { ChangeDetectionStrategy, Component, OnInit, ViewEncapsulation, inject, output, signal } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ToastrService } from 'ngx-toastr';
import { NotificationPrefRow, NotificationsService, PrefChange } from 'app/inspecto/api';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';

const CHANNELS = ['inApp', 'email'] as const;

/**
 * The deployment-default notification grid (`PUT /notifications/preferences/default`, `canAdminister`) —
 * what every user inherits wherever they have not set their own value. Shown to administrators only, under
 * their personal grid; sends only the cells that changed. Critical categories stay locked on.
 */
@Component({
    selector: 'app-deployment-default-preferences',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
    imports: [ReactiveFormsModule, MatButtonModule, MatSlideToggleModule, InspectoSkeletonComponent],
    template: `
        <section class="flex flex-col gap-3" aria-labelledby="deployment-default-heading">
            <div>
                <h3 id="deployment-default-heading" class="text-xl font-semibold">Deployment default</h3>
                <p class="text-secondary mt-1">
                    What every user receives unless they have chosen otherwise. Changes apply to everyone who inherits
                    the setting.
                </p>
            </div>
            @if (loading()) {
                <inspecto-skeleton [lines]="4" />
            } @else {
                <form [formGroup]="form" (ngSubmit)="save()">
                    <table class="w-full text-left">
                        <caption class="sr-only">
                            Deployment-default notification channels by category
                        </caption>
                        <thead>
                            <tr class="border-b">
                                <th scope="col" class="py-2">Category</th>
                                <th scope="col" class="w-28 py-2 text-center">In-app</th>
                                <th scope="col" class="w-28 py-2 text-center">Email</th>
                            </tr>
                        </thead>
                        <tbody formArrayName="rows">
                            @for (row of rows.controls; track row.value.category; let i = $index) {
                                <tr [formGroupName]="i" class="border-b">
                                    <th scope="row" class="py-3 font-normal">
                                        {{ row.value.label }}
                                        @if (row.value.critical) {
                                            <span class="text-secondary ml-2 text-xs">Always on</span>
                                        }
                                    </th>
                                    <td class="py-3 text-center">
                                        <mat-slide-toggle formControlName="inApp">
                                            <span class="sr-only"
                                                >Default in-app notifications for {{ row.value.label }}</span
                                            >
                                        </mat-slide-toggle>
                                    </td>
                                    <td class="py-3 text-center">
                                        <mat-slide-toggle formControlName="email">
                                            <span class="sr-only"
                                                >Default email notifications for {{ row.value.label }}</span
                                            >
                                        </mat-slide-toggle>
                                    </td>
                                </tr>
                            }
                        </tbody>
                    </table>
                    <div class="mt-4 flex gap-2">
                        <button mat-flat-button color="primary" type="submit" [disabled]="form.pristine || saving()">
                            Save default
                        </button>
                    </div>
                </form>
            }
        </section>
    `,
})
export class DeploymentDefaultPreferencesComponent implements OnInit {
    private fb = inject(FormBuilder);
    private svc = inject(NotificationsService);
    private toastr = inject(ToastrService);

    /** Emitted after the default changed, so the host can re-read the grid its user inherits. */
    readonly saved = output<void>();

    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly form = this.fb.group({ rows: this.fb.array([]) });

    get rows(): FormArray {
        return this.form.get('rows') as FormArray;
    }

    ngOnInit(): void {
        this.svc.defaultPreferences().subscribe({
            next: (grid) => {
                this.build(grid);
                this.loading.set(false);
            },
            error: () => {
                this.loading.set(false);
                this.toastr.error('Failed to load the deployment default');
            },
        });
    }

    save(): void {
        const changes: PrefChange[] = [];
        for (const row of this.rows.controls) {
            const channels: Record<string, boolean> = {};
            for (const ch of CHANNELS) {
                const c = row.get(ch);
                if (c?.dirty) channels[ch] = c.value;
            }
            if (Object.keys(channels).length) changes.push({ category: row.value.category, channels });
        }
        this.saving.set(true);
        this.svc.saveDefaultPreferences(changes).subscribe({
            next: (grid) => {
                this.build(grid);
                this.saving.set(false);
                this.toastr.success('Deployment default saved');
                this.saved.emit();
            },
            error: () => {
                this.saving.set(false);
                this.toastr.error('Save failed');
            },
        });
    }

    private build(grid: NotificationPrefRow[]): void {
        this.rows.clear();
        for (const r of grid) {
            this.rows.push(
                this.fb.group({
                    category: [r.category],
                    label: [r.label],
                    critical: [r.critical],
                    inApp: [{ value: r.channels.inApp, disabled: r.critical }],
                    email: [{ value: r.channels.email, disabled: r.critical }],
                }),
            );
        }
        this.form.markAsPristine();
    }
}
