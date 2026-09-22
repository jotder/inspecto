import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatMenuModule } from '@angular/material/menu';
import { ChipComponent } from './chip.component';

/** One entry of the bulk-action menu. */
export interface BulkAction {
    id: string;
    label: string;
    /** heroicons id, e.g. `heroicons_outline:check`. */
    icon?: string;
    disabled?: boolean;
    /** Renders in the warn tone and after a divider — archive/delete/reject class actions. */
    destructive?: boolean;
}

/**
 * Selection-driven bulk actions (UI consolidation plan UI-03 / UI-12). Replaces the row of six or seven
 * disabled `mat-stroked-button`s that Incidents and Cases showed above an empty grid: nothing renders
 * until `count > 0`, then a "N selected" chip and ONE `Actions ▾` menu appear, with destructive entries
 * grouped after a divider. The host keeps the per-action enablement logic and passes it as `disabled`.
 */
@Component({
    selector: 'inspecto-bulk-actions',
    standalone: true,
    imports: [MatButtonModule, MatDividerModule, MatIconModule, MatMenuModule, ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'inline-flex items-center gap-2' },
    template: `
        @if (count > 0) {
            <inspecto-chip
                variant="soft"
                tone="primary"
                removable
                removeLabel="Clear selection"
                (removed)="clear.emit()"
            >
                {{ count }} selected
            </inspecto-chip>
            <button mat-stroked-button [matMenuTriggerFor]="menu" aria-haspopup="menu">
                <span>Actions</span>
                <mat-icon class="icon-size-4 ml-1" svgIcon="heroicons_outline:chevron-down"></mat-icon>
            </button>
            <mat-menu #menu="matMenu">
                @for (a of ordinary; track a.id) {
                    <button mat-menu-item [disabled]="!!a.disabled" (click)="run.emit(a.id)">
                        @if (a.icon) {
                            <mat-icon class="icon-size-5" [svgIcon]="a.icon"></mat-icon>
                        }
                        <span>{{ a.label }}</span>
                    </button>
                }
                @if (ordinary.length && destructive.length) {
                    <mat-divider></mat-divider>
                }
                @for (a of destructive; track a.id) {
                    <button mat-menu-item class="text-warn" [disabled]="!!a.disabled" (click)="run.emit(a.id)">
                        @if (a.icon) {
                            <mat-icon class="icon-size-5 text-warn" [svgIcon]="a.icon"></mat-icon>
                        }
                        <span>{{ a.label }}</span>
                    </button>
                }
            </mat-menu>
        }
    `,
})
export class InspectoBulkActionsComponent {
    /** Number of selected rows; the component renders nothing at 0. */
    @Input({ required: true }) count = 0;
    @Input({ required: true }) actions: BulkAction[] = [];
    /** Emits the action id. */
    @Output() readonly run = new EventEmitter<string>();
    /** The ✕ on the count chip. */
    @Output() readonly clear = new EventEmitter<void>();

    get ordinary(): BulkAction[] {
        return this.actions.filter((a) => !a.destructive);
    }
    get destructive(): BulkAction[] {
        return this.actions.filter((a) => a.destructive);
    }
}
