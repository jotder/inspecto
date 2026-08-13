import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';

/**
 * **Definition drawer** — the shared shell for defining one pipeline object in the editor's right dock
 * (definition-surface unification, D1/D2): title + kind header, an "unapplied" dirty badge, projected
 * definition content, and an Apply/Discard footer. The host owns everything the shell does not:
 * open/close state, what Apply patches (always in-memory — persistence is the editor toolbar Save's
 * job, D2), and recreating the content on Discard.
 *
 * <p>Deliberately a **non-modal** dialog (`role="dialog"` without `aria-modal`, no focus trap): it is a
 * persistent dock the canvas stays interactive next to, resized by the host's `[inspectoSplit]` handle,
 * not an overlay. Closing while dirty confirms first — the shell owns that guard so no host forgets it.
 */
@Component({
    selector: 'inspecto-definition-drawer',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    template: `
        <div
            class="flex h-full min-h-0 flex-col"
            role="dialog"
            [attr.aria-label]="'Define ' + kindLabel() + ' · ' + title()"
        >
            <div class="flex shrink-0 items-center gap-2 border-b px-3 py-2" style="border-color: var(--gamma-border)">
                <mat-icon class="icon-size-5 shrink-0" [svgIcon]="icon()"></mat-icon>
                <div class="min-w-0">
                    <div class="truncate text-sm font-semibold">{{ title() }}</div>
                    <div class="text-xs uppercase tracking-wide opacity-60">{{ kindLabel() }}</div>
                </div>
                @if (dirty()) {
                    <span class="ml-1 shrink-0 text-xs" style="color: var(--gamma-warn)" role="status">
                        ● unapplied
                    </span>
                }
                <button
                    class="ml-auto shrink-0"
                    mat-icon-button
                    type="button"
                    matTooltip="Close"
                    [attr.aria-label]="'Close the ' + kindLabel() + ' definition'"
                    (click)="requestClose()"
                >
                    <mat-icon class="icon-size-5" svgIcon="heroicons_outline:x-mark"></mat-icon>
                </button>
            </div>

            <div class="min-h-0 flex-1 overflow-y-auto p-3">
                <ng-content></ng-content>
            </div>

            <div
                class="flex shrink-0 items-center justify-end gap-2 border-t px-3 py-2"
                style="border-color: var(--gamma-border)"
            >
                <button mat-button type="button" [disabled]="!dirty()" (click)="discard.emit()">Discard</button>
                <button mat-flat-button color="primary" type="button" [disabled]="!dirty()" (click)="apply.emit()">
                    Apply
                </button>
            </div>
        </div>
    `,
})
export class DefinitionDrawerComponent {
    private confirm = inject(InspectoConfirmService);

    /** The object being defined — usually the node's display name or id. */
    readonly title = input.required<string>();
    /** The definition kind ("Collector", "Parser", …) — header subtitle + aria-label. */
    readonly kindLabel = input.required<string>();
    /** Header glyph (heroicons svgIcon name). */
    readonly icon = input<string>('heroicons_outline:cube');
    /** Whether the projected content holds edits not yet Applied — drives the badge, footer and close guard. */
    readonly dirty = input<boolean>(false);

    /** Apply the drawer's edits to the host's in-memory model (D2 — never a persistence call). */
    readonly apply = output<void>();
    /** Throw the unapplied edits away; the host recreates the content from the model. */
    readonly discard = output<void>();
    /** The user closed the drawer (already dirty-confirmed by {@link requestClose}). */
    readonly closed = output<void>();

    /** Close, confirming first when edits would be lost — the shell-owned dirty guard. */
    async requestClose(): Promise<void> {
        if (this.dirty()) {
            const ok = await this.confirm.confirmDestructive(
                'This definition has edits that have not been applied. Closing the drawer discards them.',
                { title: 'Discard unapplied edits?', confirmText: 'Discard' },
            );
            if (!ok) return;
        }
        this.closed.emit();
    }
}
