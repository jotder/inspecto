import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ICellRendererAngularComp } from 'ag-grid-angular';
import { ICellRendererParams, SuppressKeyboardEventParams } from 'ag-grid-community';
import { RowLinkKind } from './viz-types';

/** UIE-6: each operational object's detail route and its reader-facing name. */
const TARGETS: Record<RowLinkKind, { path: string; label: string }> = {
    case: { path: '/cases', label: 'Case' },
    incident: { path: '/incidents', label: 'Incident' },
    reconciliation: { path: '/reconciliation', label: 'Reconciliation' },
};

/** The router commands opening the object a row describes — `null` when the row carries no id (no link). */
export function rowLinkCommands(kind: RowLinkKind, id: unknown): string[] | null {
    const target = TARGETS[kind];
    if (!target || id == null) return null;
    const text = String(id).trim();
    return text ? [target.path, text] : null;
}

/**
 * Enter on a focused link cell follows the link. ag-Grid keeps focus on the gridcell, not on the anchor inside it,
 * so without this the link is reachable by arrow keys but cannot be opened from the keyboard. Used as the column's
 * `suppressKeyboardEvent`: returns true (grid ignores the key) only when it followed a link.
 */
export function followRowLinkOnEnter(p: SuppressKeyboardEventParams): boolean {
    const e = p.event;
    if (e.type !== 'keydown' || e.key !== 'Enter') return false;
    const anchor = (e.target as HTMLElement | null)?.querySelector?.<HTMLAnchorElement>('a[href]');
    if (!anchor) return false;
    anchor.click();
    return true;
}

/** Table cell rendering a row's id as a real link to the Case / Incident / Reconciliation it names (UIE-6). */
@Component({
    selector: 'inspecto-row-link-cell',
    standalone: true,
    imports: [RouterLink],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (commands; as cmds) {
            <a
                class="text-primary underline"
                [routerLink]="cmds"
                [attr.aria-label]="'Open ' + label + ' ' + text"
                [attr.title]="'Open ' + label + ' ' + text"
                (click)="$event.stopPropagation()"
                >{{ text }}</a
            >
        } @else {
            {{ text }}
        }
    `,
})
export class RowLinkCell implements ICellRendererAngularComp {
    commands: string[] | null = null;
    label = '';
    text = '';

    agInit(params: ICellRendererParams & { kind: RowLinkKind }): void {
        this.commands = rowLinkCommands(params.kind, params.value);
        this.label = TARGETS[params.kind]?.label ?? '';
        this.text = params.valueFormatted ?? (params.value == null ? '' : String(params.value));
    }

    refresh(): boolean {
        return false;
    }
}
