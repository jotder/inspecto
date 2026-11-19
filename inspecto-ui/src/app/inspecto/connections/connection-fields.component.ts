import { ChangeDetectionStrategy, ChangeDetectorRef, Component, computed, effect, inject, input } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { InspectoOptionPickerComponent, pickerOptions } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { ConnAttr, SECRET_HINT, connAttrError } from './connection-types';

/**
 * A Connection's typed attribute rows (BASIC-ELT-PROFILE-1 U2): one row per {@link ConnAttr} - a pen-field, an option
 * picker, a checkbox, or a boxed field for a secret reference (a mistyped literal must never show in a closed row). The
 * host owns the form (`connAttrsGroup`) and what it sends; this draws the rows and their error lines. Hosts: the Connection
 * dialog (every type) and Add data's *Choose a table* (`POSTGRES_CONNECTION_ATTRS`).
 *
 * @example <inspecto-connection-fields [group]="form" [attrs]="attrs" />
 */
@Component({
    selector: 'inspecto-connection-fields',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    host: { class: 'block' },
    template: `
        <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2" [formGroup]="group()">
            @for (a of attrs(); track a.key) {
                @switch (a.control) {
                    @case ('checkbox') {
                        <div class="flex items-center py-2">
                            <mat-checkbox [formControlName]="a.key">{{ a.label }}</mat-checkbox>
                        </div>
                    }
                    @case ('select') {
                        <inspecto-option-picker
                            class="block"
                            [label]="a.label"
                            [formControlName]="a.key"
                            [options]="pickerChoices()[a.key]"
                            [help]="a.hint ?? ''"
                        />
                    }
                    @case ('number') {
                        <inspecto-pen-field
                            [label]="a.label"
                            type="number"
                            [formControlName]="a.key"
                            [required]="!!a.required"
                            [help]="a.hint ?? ''"
                            [error]="rowError(a)"
                        ></inspecto-pen-field>
                    }
                    @case ('password') {
                        <!-- form-allow: a secret field (a secret reference; a mistyped literal must never show in a closed row) --><mat-form-field
                            subscriptSizing="dynamic"
                        >
                            <mat-label>{{ a.label }}</mat-label>
                            <input matInput [formControlName]="a.key" />
                            <mat-hint>{{ a.hint || secretHint }}</mat-hint>
                            @if (boxError(a); as e) {
                                <mat-error>{{ e }}</mat-error>
                            }
                        </mat-form-field>
                    }
                    @default {
                        <inspecto-pen-field
                            [label]="a.label"
                            [formControlName]="a.key"
                            [required]="!!a.required"
                            [help]="a.hint ?? ''"
                            [error]="rowError(a)"
                        ></inspecto-pen-field>
                    }
                }
            }
        </div>
    `,
})
export class ConnectionFieldsComponent {
    /** The host's form, built by `connAttrsGroup(attrs)`. */
    readonly group = input.required<FormGroup>();
    readonly attrs = input.required<readonly ConnAttr[]>();

    protected readonly secretHint = SECRET_HINT;
    /** Each select attribute's choices for its picker, by attribute key. */
    protected readonly pickerChoices = computed(() =>
        Object.fromEntries(this.attrs().map((a) => [a.key, pickerOptions(a.options ?? [])] as const)),
    );

    constructor() {
        // The host submits (markAllAsTouched) outside these rows: re-draw their error lines under OnPush.
        const cdr = inject(ChangeDetectorRef);
        effect((onCleanup) => {
            const sub = this.group().events.subscribe(() => cdr.markForCheck());
            onCleanup(() => sub.unsubscribe());
        });
    }

    /** A pen row's error line, once the row was left or the form submitted. */
    protected rowError(a: ConnAttr): string {
        const c = this.group().get(a.key);
        return c?.touched ? connAttrError(a, c) : '';
    }

    /** A boxed row's error: the form field shows it once touched or submitted. */
    protected boxError(a: ConnAttr): string {
        return connAttrError(a, this.group().get(a.key));
    }
}
