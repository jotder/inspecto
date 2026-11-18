import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { forkJoin, Observable } from 'rxjs';
import {
    apiErrorMessage,
    SPACE_LOCATIONS_DISABLED_MESSAGE,
    SpaceLocationCheckResult,
    SpaceLocationKind,
    SpacesService,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { LocationChecksComponent } from './location-checks.component';

/** The folders a create sends (SPACE-LOCATIONS-1 D6), and whether they may be sent as they stand. */
export interface SpaceFolderChoice {
    path?: string;
    dataRoot?: string;
    /** True when no folder is given, or every given folder was checked as it stands now and passed. */
    ready: boolean;
}

/** One folder's last check, kept with the value it checked so an edit afterwards asks for a new check. */
interface Checked {
    value: string;
    /** The Space id the check was made for: a Space folder is named after it. */
    id: string;
    result: SpaceLocationCheckResult;
}

/**
 * **Advanced: folders** on a Space create: an optional Space folder and Data folder, each checked by the server before
 * Create (`POST /spaces/_locations/check`). Collapsed by default (Ask the minimum). The host reads `(changed)`, never
 * this component through a view query, and keeps Create disabled while `ready` is false. When the server has no
 * allowed folders the section says what the administrator must set and sends no folder.
 */
@Component({
    selector: 'app-space-folders',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        InspectoAlertComponent,
        PenFieldComponent,
        LocationChecksComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="border-t pt-2">
            <button
                type="button"
                mat-button
                data-testid="folders-toggle"
                [attr.aria-expanded]="expanded()"
                aria-controls="space-folders-panel"
                (click)="expanded.set(!expanded())"
            >
                <mat-icon
                    class="icon-size-5"
                    [svgIcon]="expanded() ? 'heroicons_outline:chevron-up' : 'heroicons_outline:chevron-down'"
                ></mat-icon>
                <span class="ml-1">Advanced: folders</span>
            </button>
            <div id="space-folders-panel" [hidden]="!expanded()" class="flex flex-col gap-2 pt-1">
                @if (spaces.locationsDisabled()) {
                    <inspecto-alert variant="info" data-testid="locations-disabled">{{
                        disabledMessage
                    }}</inspecto-alert>
                } @else {
                    <div [formGroup]="form" class="flex flex-col gap-1">
                        <inspecto-pen-field
                            label="Space folder"
                            [mono]="true"
                            formControlName="path"
                            help="Optional. A folder named after the Space's id, empty or not there yet. Blank: under the Spaces root."
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            label="Data folder"
                            [mono]="true"
                            formControlName="dataRoot"
                            help="Optional. An empty folder for the Space's data. Blank: inside the Space folder."
                        ></inspecto-pen-field>
                    </div>
                    <div class="flex items-center gap-2">
                        <button
                            type="button"
                            mat-stroked-button
                            data-testid="folders-check"
                            [disabled]="!anyFolder() || checking()"
                            (click)="check()"
                        >
                            Check folders
                        </button>
                        @if (anyFolder() && !ready()) {
                            <span class="text-secondary text-xs" data-testid="folders-unchecked">
                                Check the folders before you create the Space.
                            </span>
                        }
                    </div>
                    @if (checkError()) {
                        <p class="text-warn m-0 text-sm" role="alert">{{ checkError() }}</p>
                    }
                    @if (current('path'); as c) {
                        <app-location-checks heading="Space folder" [result]="c.result" />
                    }
                    @if (current('dataRoot'); as c) {
                        <app-location-checks heading="Data folder" [result]="c.result" />
                    }
                }
            </div>
        </div>
    `,
})
export class SpaceFoldersComponent {
    protected readonly spaces = inject(SpacesService);
    private fb = inject(FormBuilder);

    /** The id the new Space will get; a Space folder is named after it. */
    readonly spaceId = input('');
    readonly changed = output<SpaceFolderChoice>();

    protected readonly disabledMessage = SPACE_LOCATIONS_DISABLED_MESSAGE;
    readonly expanded = signal(false);
    readonly checking = signal(false);
    readonly checkError = signal('');
    private readonly checked = signal<{ path?: Checked; dataRoot?: Checked }>({});

    readonly form = this.fb.nonNullable.group({ path: '', dataRoot: '' });
    private readonly values = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

    private value(key: 'path' | 'dataRoot'): string {
        return String(this.values()[key] ?? '').trim();
    }

    readonly anyFolder = computed(() => !!this.value('path') || !!this.value('dataRoot'));

    /** The last check of a folder, only while it still describes the value in the field. */
    current(key: 'path' | 'dataRoot'): Checked | null {
        const c = this.checked()[key];
        const v = this.value(key);
        return c && v && c.value === v && c.id === this.spaceId().trim() ? c : null;
    }

    readonly ready = computed(() => {
        if (this.spaces.locationsDisabled()) return true;
        return (['path', 'dataRoot'] as const).every((k) => !this.value(k) || this.current(k)?.result.ok === true);
    });

    readonly choice = computed<SpaceFolderChoice>(() => {
        if (this.spaces.locationsDisabled()) return { ready: true };
        const path = this.value('path');
        const dataRoot = this.value('dataRoot');
        return { ...(path ? { path } : {}), ...(dataRoot ? { dataRoot } : {}), ready: this.ready() };
    });

    constructor() {
        effect(() => this.changed.emit(this.choice()));
    }

    check(): void {
        const id = this.spaceId().trim() || undefined;
        const calls: Record<string, Observable<SpaceLocationCheckResult>> = {};
        const path = this.value('path');
        const dataRoot = this.value('dataRoot');
        if (path) calls['path'] = this.spaces.checkLocation(path, 'space' satisfies SpaceLocationKind, id);
        if (dataRoot) calls['dataRoot'] = this.spaces.checkLocation(dataRoot, 'data', id);
        this.checking.set(true);
        this.checkError.set('');
        forkJoin(calls).subscribe({
            next: (r) => {
                this.checking.set(false);
                this.checked.set({
                    ...(r['path'] ? { path: { value: path, id: id ?? '', result: r['path'] } } : {}),
                    ...(r['dataRoot'] ? { dataRoot: { value: dataRoot, id: id ?? '', result: r['dataRoot'] } } : {}),
                });
            },
            error: (e) => {
                this.checking.set(false);
                this.checked.set({});
                // A server without allowed folders is shown by the section itself (spaces.locationsDisabled).
                if (!this.spaces.locationsDisabled())
                    this.checkError.set(apiErrorMessage(e, 'The folders could not be checked.'));
            },
        });
    }
}
