import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { Router } from '@angular/router';
import { apiErrorMessage } from '@inspecto/core/api';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';

/**
 * **Open an Entity**: a typed or pasted value is resolved on the server first (`POST /inv/entities/{type}/resolve`, the
 * value in the body) and the Entity page is opened with the key it returns - an alias for a masked type. A raw
 * identifier never goes in a URL (operator, 2026-10-15). A refusal (422) shows its reason on the row.
 */
@Component({
    selector: 'inspecto-la-entity-lookup',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, PenFieldComponent],
    template: `
        <form
            class="flex flex-wrap items-start gap-3"
            aria-label="Open an Entity"
            [formGroup]="form"
            (ngSubmit)="open()"
        >
            <inspecto-pen-field
                class="min-w-0 flex-1"
                label="Open an Entity"
                placeholder="Type or paste a value"
                data-testid="lookup-value"
                formControlName="value"
                [maxlength]="200"
                [error]="error()"
            ></inspecto-pen-field>
            <button mat-stroked-button type="submit" data-testid="lookup-open" [disabled]="busy()">Open</button>
        </form>
    `,
})
export class EntityLookupComponent {
    private readonly svc = inject(EntityProfileService);
    private readonly router = inject(Router);

    /** The Entity Type the value is looked up as. */
    readonly type = input.required<string>();
    /** The Entities base route (`['/', 'entities']` or `['/', 'studio', 'entities']`). */
    readonly base = input.required<string[]>();

    readonly form = new FormGroup({ value: new FormControl('', { nonNullable: true }) });
    readonly error = signal('');
    readonly busy = signal(false);

    open(): void {
        const raw = this.form.controls.value.value.trim();
        if (!raw) {
            this.error.set('Type a value to open.');
            return;
        }
        this.error.set('');
        this.busy.set(true);
        this.svc.resolveEntity(this.type(), raw).subscribe({
            next: (r) => {
                this.busy.set(false);
                void this.router.navigate([...this.base(), r.type || this.type(), r.key]);
            },
            error: (e) => {
                this.busy.set(false);
                this.error.set(apiErrorMessage(e, 'This value could not be resolved.'));
            },
        });
    }
}
