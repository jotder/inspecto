import { ChangeDetectionStrategy, Component, Injectable, OnInit, computed, inject, input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { AddDataAccess } from '@inspecto/link-analysis/add-data/add-data-access';

/** The console route that hosts the shared Add data screen. */
export const ADD_DATA_ROUTE = '/add-data';

/**
 * Carries a file dropped on a console pane to the Add data screen (`ONBOARDING-FIRST-RUN-1` C3). A `File` cannot travel
 * in a URL, so the drop leaves it here and `/add-data` takes it once.
 */
@Injectable({ providedIn: 'root' })
export class AddDataHandoff {
    private file: File | null = null;

    put(f: File): void {
        this.file = f;
    }

    /** The waiting file, once: a reload or a second visit to `/add-data` starts empty. */
    take(): File | null {
        const f = this.file;
        this.file = null;
        return f;
    }
}

/** Whether a drag carries files (not text or a link dragged from the page). */
function carriesFiles(event: DragEvent): boolean {
    return Array.from(event.dataTransfer?.types ?? []).includes('Files');
}

/**
 * A pane that takes a dropped file (`ONBOARDING-FIRST-RUN-1` B1: Home, the Catalog, an empty Datasets pane). Dropping a
 * file opens **Add data** with it. Only armed when the person may add data here ({@link AddDataAccess}: `canUploadData`
 * with sign-in, and the Space's `data.self_import`); otherwise the content renders as it is and a drop does nothing.
 * Dropping is a mouse gesture; the keyboard path is the *Add data* link (Home's first-run panel, the empty Datasets
 * pane) or the `/add-data` route itself. While the Space's switch is still unread the zone is armed and the server
 * decides (a 403 locks the screen with its reason).
 *
 * @example <inspecto-add-data-drop [enabled]="firstRun()"> …pane… </inspecto-add-data-drop>
 */
@Component({
    selector: 'inspecto-add-data-drop',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule],
    host: {
        // the host gives the display and layout classes: the drop zone replaces the pane's own root element
        class: 'relative',
        '(dragenter)': 'onDragOver($event)',
        '(dragover)': 'onDragOver($event)',
        '(dragleave)': 'onDragLeave($event)',
        '(drop)': 'onDrop($event)',
    },
    template: `
        <ng-content></ng-content>
        @if (dragging()) {
            <div
                class="bg-card border-primary pointer-events-none absolute inset-0 z-20 flex flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed opacity-95"
                data-testid="add-data-drop-overlay"
            >
                <mat-icon
                    class="text-primary icon-size-10"
                    svgIcon="heroicons_outline:arrow-up-tray"
                    aria-hidden="true"
                ></mat-icon>
                <p class="m-0 text-lg font-semibold">Drop the file to add it as data</p>
                <p class="text-secondary m-0 text-sm">
                    Add data reads it and shows its columns before anything is made.
                </p>
            </div>
        }
    `,
})
export class AddDataDropComponent implements OnInit {
    private readonly access = inject(AddDataAccess);
    private readonly handoff = inject(AddDataHandoff);
    private readonly router = inject(Router);

    /** The host's own condition (an empty Datasets pane); the person's right and the Space's switch are added here. */
    readonly enabled = input(true);
    protected readonly dragging = signal(false);
    /** Armed: the host allows it and nothing locks Add data for this person in this Space. */
    readonly armed = computed(() => this.enabled() && !this.access.lockReason());

    ngOnInit(): void {
        this.access.load();
    }

    protected onDragOver(event: DragEvent): void {
        if (!this.armed() || !carriesFiles(event)) return;
        event.preventDefault();
        if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
        this.dragging.set(true);
    }

    protected onDragLeave(event: DragEvent): void {
        // leaving into a child of the pane is not leaving the pane
        const to = event.relatedTarget as Node | null;
        if (to && (event.currentTarget as Node | null)?.contains(to)) return;
        this.dragging.set(false);
    }

    protected onDrop(event: DragEvent): void {
        this.dragging.set(false);
        if (!this.armed()) return;
        const f = event.dataTransfer?.files?.[0];
        if (!f) return;
        event.preventDefault();
        this.handoff.put(f);
        void this.router.navigateByUrl(ADD_DATA_ROUTE);
    }
}
