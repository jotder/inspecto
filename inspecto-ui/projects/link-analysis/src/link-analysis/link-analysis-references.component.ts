import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InvestigationReferences, InvService } from '@inspecto/link-analysis/api/inv.service';
import { investigationErrorMessage } from './investigation-state';

/**
 * **Link Analysis — External references** over `InvestigationReferenceRoutes`: pointers from an Investigation to a
 * record in another system (a ticket, a Case file, a report). They are append-only, never fetched, never trusted and
 * grant nothing, so this view says so, shows the `url` as TEXT (never a live link — it is caller text), and offers no
 * edit or delete: a wrong reference is superseded by adding a new one. Adding is owner-only and needs the
 * Incident-management capability; a refusal is explained in place.
 */
@Component({
    selector: 'inspecto-link-analysis-references',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, InspectoAlertComponent],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2 text-xs"
            style="border-color: var(--gamma-border)"
            aria-label="External references"
        >
            <div class="flex items-center gap-1">
                <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">References</h3>
                <button mat-stroked-button class="ml-auto" [disabled]="busy()" (click)="load()">
                    {{ data() ? 'Reload' : 'Load references' }}
                </button>
            </div>
            @if (error()) {
                <inspecto-alert variant="error" title="References">{{ error() }}</inspecto-alert>
            }
            @if (data(); as d) {
                <p class="text-secondary m-0">{{ d.note }} — {{ d.count }} of at most {{ d.max }}.</p>
                @if (d.references.length) {
                    <ol class="m-0 flex list-none flex-col gap-1 p-0" aria-label="References of this Investigation">
                        @for (r of d.references; track r.seq) {
                            <li class="break-all">
                                <strong>{{ r.system }}/{{ r.type }}</strong> {{ r.id }}
                                @if (r.label) {
                                    — {{ r.label }}
                                }
                                @if (r.url) {
                                    <span class="text-secondary"> · {{ r.url }}</span>
                                }
                                <span class="text-secondary"> · added by {{ r.addedBy || 'unknown' }}</span>
                            </li>
                        }
                    </ol>
                } @else {
                    <p class="text-secondary m-0">No references yet.</p>
                }
                <form class="flex flex-wrap items-start gap-2" aria-label="Add a reference" (submit)="$event.preventDefault(); add()">
                    <mat-form-field class="w-32" subscriptSizing="dynamic">
                        <mat-label>System</mat-label>
                        <input matInput [formControl]="system" />
                    </mat-form-field>
                    <mat-form-field class="w-32" subscriptSizing="dynamic">
                        <mat-label>Type</mat-label>
                        <input matInput [formControl]="type" />
                    </mat-form-field>
                    <mat-form-field class="w-40" subscriptSizing="dynamic">
                        <mat-label>Id</mat-label>
                        <input matInput [formControl]="refId" />
                    </mat-form-field>
                    <mat-form-field class="w-56" subscriptSizing="dynamic">
                        <mat-label>URL (optional, http or https)</mat-label>
                        <input matInput [formControl]="url" />
                    </mat-form-field>
                    <mat-form-field class="w-48" subscriptSizing="dynamic">
                        <mat-label>Label (optional)</mat-label>
                        <input matInput [formControl]="label" />
                    </mat-form-field>
                    <button mat-flat-button type="submit" [disabled]="busy() || !valid()">Add reference</button>
                </form>
            }
        </section>
    `,
})
export class LinkAnalysisReferencesComponent {
    private inv = inject(InvService);

    readonly investigationId = input.required<string>();

    readonly data = signal<InvestigationReferences | null>(null);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly system = new FormControl('', { nonNullable: true, validators: [Validators.required] });
    readonly type = new FormControl('', { nonNullable: true, validators: [Validators.required] });
    readonly refId = new FormControl('', { nonNullable: true, validators: [Validators.required] });
    readonly url = new FormControl('', { nonNullable: true });
    readonly label = new FormControl('', { nonNullable: true });

    constructor() {
        // Another Investigation opened: the list on screen belongs to the previous one.
        effect(() => {
            this.investigationId();
            untracked(() => {
                this.data.set(null);
                this.error.set('');
                for (const c of [this.system, this.type, this.refId, this.url, this.label]) c.reset('');
            });
        });
    }

    valid(): boolean {
        return this.system.valid && this.type.valid && this.refId.valid;
    }

    load(): Promise<void> {
        return this.run('Could not load the references.', async () => {
            this.data.set(await firstValueFrom(this.inv.investigationReferences(this.investigationId())));
        });
    }

    add(): Promise<void> {
        if (!this.valid()) return Promise.resolve();
        const url = this.url.value.trim();
        const label = this.label.value.trim();
        return this.run('Could not add the reference.', async () => {
            this.data.set(
                await firstValueFrom(
                    this.inv.addInvestigationReference(this.investigationId(), {
                        system: this.system.value.trim(),
                        type: this.type.value.trim(),
                        id: this.refId.value.trim(),
                        ...(url ? { url } : {}),
                        ...(label ? { label } : {}),
                    }),
                ),
            );
            this.refId.reset('');
            this.url.reset('');
            this.label.reset('');
        });
    }

    private async run(fallback: string, body: () => Promise<void>): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        try {
            await body();
        } catch (err) {
            this.error.set(investigationErrorMessage(err, fallback));
        } finally {
            this.busy.set(false);
        }
    }
}
