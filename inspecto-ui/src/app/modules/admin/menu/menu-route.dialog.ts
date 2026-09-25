import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import { routeError } from 'app/inspecto/menu';

/** Platform screens a business menu most often links to (UIE-7, demo-experience-review §4.1). Suggestions only —
 *  any in-app route the server accepts can be typed. */
export const MENU_ROUTE_SUGGESTIONS: { route: string; label: string }[] = [
    { route: '/cases', label: 'Cases' },
    { route: '/incidents', label: 'Incidents' },
    { route: '/alerts', label: 'Alerts' },
    { route: '/reconciliation', label: 'Reconciliation' },
    { route: '/kpi-reports', label: 'KPI & Reports' },
    { route: '/catalog', label: 'Catalog' },
    { route: '/home', label: 'Home' },
];

export interface MenuRouteDialogData {
    heading: string;
    title?: string;
    route?: string;
    /** Existing sibling titles (excluding this node) — for the inline duplicate block. */
    takenTitles: string[];
}

export interface MenuRouteDialogResult {
    title: string;
    route: string;
}

/** The client mirror of the server's route check, as a form validator carrying its own message. */
function routeValidator(c: AbstractControl): ValidationErrors | null {
    const message = routeError(String(c.value ?? '').trim());
    return message ? { route: message } : null;
}

/** UIE-7: add or edit a Menu item that opens an in-app screen (a `route` binding) — a name and a route. */
@Component({
    selector: 'app-menu-route-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatAutocompleteModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ data.heading }}</h2>
        <form [formGroup]="form" (ngSubmit)="save()">
            <mat-dialog-content class="flex flex-col gap-2 pt-1">
                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="title" cdkFocusInitial placeholder="e.g. Cases" />
                    @if (form.controls.title.hasError('required')) {
                        <mat-error>A name is required.</mat-error>
                    }
                    @if (form.controls.title.hasError('duplicate')) {
                        <mat-error>A menu item with this name already exists here.</mat-error>
                    }
                </mat-form-field>

                <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Screen</mat-label>
                    <input
                        matInput
                        formControlName="route"
                        [matAutocomplete]="auto"
                        placeholder="/cases"
                        aria-label="In-app route"
                    />
                    <mat-autocomplete #auto="matAutocomplete">
                        @for (s of suggestions(); track s.route) {
                            <mat-option [value]="s.route">{{ s.label }} · {{ s.route }}</mat-option>
                        }
                    </mat-autocomplete>
                    <mat-hint>An in-app path, for example /cases or /alerts.</mat-hint>
                    @if (form.controls.route.hasError('route')) {
                        <mat-error>{{ form.controls.route.getError('route') }}</mat-error>
                    }
                </mat-form-field>
            </mat-dialog-content>
            <mat-dialog-actions align="end">
                <button mat-button type="button" (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" type="submit">Save</button>
            </mat-dialog-actions>
        </form>
    `,
})
export class MenuRouteDialog {
    readonly data = inject<MenuRouteDialogData>(MAT_DIALOG_DATA);
    readonly ref = inject<MatDialogRef<MenuRouteDialog, MenuRouteDialogResult>>(MatDialogRef);
    private fb = inject(FormBuilder);
    private confirm = inject(InspectoConfirmService);

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding a dirty form. */
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    readonly form = this.fb.group({
        title: [this.data.title ?? '', [Validators.required, uniqueNameValidator(() => this.data.takenTitles)]],
        route: [this.data.route ?? '', [routeValidator]],
    });

    private readonly routeValue = toSignal(this.form.controls.route.valueChanges, {
        initialValue: this.form.controls.route.value,
    });

    /** Suggestions narrowed by the typed text (matches the route or its label). */
    readonly suggestions = computed(() => {
        const q = String(this.routeValue() ?? '')
            .trim()
            .toLowerCase();
        return q
            ? MENU_ROUTE_SUGGESTIONS.filter((s) => s.route.includes(q) || s.label.toLowerCase().includes(q))
            : MENU_ROUTE_SUGGESTIONS;
    });

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { title, route } = this.form.getRawValue();
        this.ref.close({ title: String(title).trim(), route: String(route).trim() });
    }
}
