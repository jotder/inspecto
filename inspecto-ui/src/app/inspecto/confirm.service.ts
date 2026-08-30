import { Component, inject, Injectable, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';

/**
 * One opt-in the confirm offers alongside the main action — e.g. "also delete the data".
 *
 * <p>⚠ `checked` is the STARTING state and is deliberately per-item: an additive cleanup can default
 * on, but anything that destroys data must default OFF, so that confirming the dialog without reading
 * it cannot take more than the action the operator came for.
 */
export interface ConfirmCheckbox {
    key: string;
    label: string;
    hint?: string;
    checked?: boolean;
}

/** What the operator chose: whether they confirmed, and the state of each offered checkbox. */
export interface ConfirmChoice {
    ok: boolean;
    checked: Record<string, boolean>;
}

/** Resolved data passed to the dialog (the service fills defaults). */
interface ConfirmData {
    title: string;
    message: string;
    confirmText: string;
    cancelText: string;
    destructive: boolean;
    /** When set, the user must type this exact text to enable the confirm button. */
    requireText?: string;
    checkboxes?: ConfirmCheckbox[];
}

@Component({
    selector: 'inspecto-confirm-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, FormsModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>{{ data.title }}</h2>
        <mat-dialog-content>
            <div class="whitespace-pre-wrap">{{ data.message }}</div>
            @if (data.checkboxes?.length) {
                <div class="mt-4 flex flex-col gap-2">
                    @for (c of data.checkboxes; track c.key) {
                        <div class="flex flex-col">
                            <mat-checkbox [(ngModel)]="checked[c.key]">{{ c.label }}</mat-checkbox>
                            @if (c.hint) {
                                <span class="text-secondary ml-9 text-xs">{{ c.hint }}</span>
                            }
                        </div>
                    }
                </div>
            }
            @if (data.requireText) {
                <mat-form-field class="mt-4 w-full" subscriptSizing="dynamic">
                    <mat-label>Type “{{ data.requireText }}” to confirm</mat-label>
                    <input matInput [(ngModel)]="typed" autocomplete="off" />
                </mat-form-field>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button [mat-dialog-close]="{ ok: false, checked: {} }">
                {{ data.cancelText }}
            </button>
            <button
                mat-flat-button
                [color]="data.destructive ? 'warn' : 'primary'"
                [disabled]="data.requireText ? typed !== data.requireText : false"
                [mat-dialog-close]="{ ok: true, checked: checked }"
            >
                {{ data.confirmText }}
            </button>
        </mat-dialog-actions>
    `,
})
export class InspectoConfirmDialog {
    readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
    typed = '';
    /** Seeded from each checkbox's own `checked`, so a destructive opt-in starts off. */
    readonly checked: Record<string, boolean> = Object.fromEntries(
        (this.data.checkboxes ?? []).map((c) => [c.key, c.checked === true]),
    );
}

/** Options for {@link InspectoConfirmService.confirmDestructive}. */
export interface ConfirmOptions {
    title?: string;
    confirmText?: string;
    cancelText?: string;
    destructive?: boolean;
    /** Require the user to type this exact text (e.g. the resource id) before the confirm enables. */
    requireText?: string;
    /** Optional per-item opt-ins rendered above the confirm; read back via {@link ConfirmChoice}. */
    checkboxes?: ConfirmCheckbox[];
}

/** Promise-based confirm — replaces DevExtreme's `confirm()` for the ported screens. */
@Injectable({ providedIn: 'root' })
export class InspectoConfirmService {
    private dialog = inject(MatDialog);

    /** Neutral confirm (primary button). Backward-compatible signature. */
    async confirm(message: string, title = 'Confirm'): Promise<boolean> {
        return this.ask(message, { title });
    }

    /**
     * Destructive confirm — a red ("warn") confirm button, defaulting the title/label to "Delete".
     * Pass {@link ConfirmOptions.requireText} to require typed confirmation for high-risk deletes.
     */
    async confirmDestructive(message: string, opts: ConfirmOptions = {}): Promise<boolean> {
        return this.ask(message, {
            destructive: true,
            title: 'Delete',
            confirmText: 'Delete',
            ...opts,
        });
    }

    /**
     * As {@link #confirmDestructive}, but offering per-item opt-ins and reporting which were chosen.
     *
     * <p>Use it when the destructive action has genuinely separable parts — deleting a pipeline can
     * also take its companion schema and its written data, and those are different decisions with
     * different consequences. ⚠ Anything that destroys data must be declared `checked: false`, so
     * confirming without reading cannot take more than the action the operator came for.
     */
    async confirmDestructiveWith(message: string, opts: ConfirmOptions = {}): Promise<ConfirmChoice> {
        return this.askWith(message, { destructive: true, title: 'Delete', confirmText: 'Delete', ...opts });
    }

    private async ask(message: string, opts: ConfirmOptions): Promise<boolean> {
        return (await this.askWith(message, opts)).ok;
    }

    /**
     * ⚠ The dialog closes with a {@link ConfirmChoice}, never a bare boolean — and a dismissal (Esc or
     * the backdrop) closes with `undefined`, so the result is normalised here. This is why `ask` reads
     * `.ok` rather than truthiness: `!!{ok: false}` is `true`, which would have turned every Cancel in
     * the app into a confirmation the moment the close value stopped being a boolean.
     */
    private async askWith(message: string, opts: ConfirmOptions): Promise<ConfirmChoice> {
        const data: ConfirmData = {
            title: opts.title ?? 'Confirm',
            message,
            confirmText: opts.confirmText ?? 'OK',
            cancelText: opts.cancelText ?? 'Cancel',
            destructive: opts.destructive ?? false,
            requireText: opts.requireText,
            checkboxes: opts.checkboxes,
        };
        const ref = this.dialog.open(InspectoConfirmDialog, {
            data,
            width: '420px',
        });
        const closed = (await firstValueFrom(ref.afterClosed())) as ConfirmChoice | undefined;
        return { ok: closed?.ok === true, checked: closed?.checked ?? {} };
    }
}
