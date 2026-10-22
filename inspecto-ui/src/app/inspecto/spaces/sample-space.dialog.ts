import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { apiErrorMessage, SampleLoadResult, SampleSpace, SpacesService, SpaceTemplateInfo } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';

/** Dialog input: the template to pick first (the host's own sample, e.g. `la-sample` in Inspecto LA). */
export interface SampleSpaceData {
    preferred?: string;
}

/** The Pipelines of a sample load that did not load. */
export function failedSampleRuns(space: SampleSpace): SampleLoadResult[] {
    return (space.samples?.pipelines ?? []).filter((p) => p.status !== 'loaded');
}

/**
 * **New from sample** (LA-ONBOARD-1 OB-11 / A-8): pick a Space Template that ships sample data, and ONE call creates
 * the Space, loads the samples and runs its Pipelines (`POST /spaces {template, loadSamples: true}`). Lists only the
 * templates this edition can create that carry samples. Closes with the new {@link SampleSpace}; when a Pipeline did
 * not load, the dialog stays open, names it in plain words and still offers the Space (it was created).
 */
@Component({
    selector: 'inspecto-sample-space-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatRadioModule,
        InspectoAlertComponent,
        InspectoSkeletonComponent,
    ],
    template: `
        <h2 mat-dialog-title>New Space from a sample</h2>
        <mat-dialog-content class="flex flex-col gap-3">
            <p class="text-secondary m-0">
                Invented data, loaded and ready to explore. You can delete the Space later.
            </p>
            @if (templates() === null) {
                <inspecto-skeleton [lines]="3"></inspecto-skeleton>
            } @else if (templates()!.length === 0) {
                <inspecto-alert variant="info">No sample is available on this server.</inspecto-alert>
            } @else {
                <span id="sample-space-pick" class="font-medium">Which sample?</span>
                <mat-radio-group [formControl]="choice" aria-labelledby="sample-space-pick" class="flex flex-col gap-1">
                    @for (t of templates(); track t.id) {
                        <mat-radio-button [value]="t.id">
                            <span class="flex flex-col">
                                <span>{{ t.name }} </span>
                                <span class="text-secondary text-sm">{{ t.tagline }}</span>
                            </span>
                        </mat-radio-button>
                    }
                </mat-radio-group>
            }
            @if (loading()) {
                <p class="m-0 text-sm" role="status">Creating the Space and loading the sample…</p>
            }
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            }
            @if (failed().length) {
                <inspecto-alert variant="warning" title="The Space was created, but some data did not load">
                    <ul class="m-0 pl-4">
                        @for (p of failed(); track p.pipeline) {
                            <li>{{ p.pipeline }}: {{ p.error || 'did not load' }}</li>
                        }
                    </ul>
                </inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (created()) {
                <button type="button" mat-flat-button color="primary" (click)="ref.close(created())">
                    Open the Space
                </button>
            } @else {
                <button type="button" mat-button (click)="ref.close()" [disabled]="loading()">Cancel</button>
                <button
                    type="button"
                    mat-flat-button
                    color="primary"
                    [disabled]="!choice.value || loading()"
                    (click)="create()"
                >
                    Create and load
                </button>
            }
        </mat-dialog-actions>
    `,
})
export class SampleSpaceDialog {
    private readonly spaces = inject(SpacesService);
    private readonly data = inject<SampleSpaceData | null>(MAT_DIALOG_DATA, { optional: true });
    readonly ref = inject(MatDialogRef<SampleSpaceDialog, SampleSpace>);

    /** Creatable templates that ship samples; null while loading. */
    readonly templates = signal<SpaceTemplateInfo[] | null>(null);
    readonly choice = new FormControl<string | null>(null);
    readonly loading = signal(false);
    readonly error = signal<string | null>(null);
    /** The Space made by a load where a Pipeline failed (the dialog then stays open). */
    readonly created = signal<SampleSpace | null>(null);
    readonly failed = computed(() => {
        const c = this.created();
        return c ? failedSampleRuns(c) : [];
    });

    constructor() {
        this.spaces.templates().subscribe({
            next: (all) => {
                const offered = all.filter((t) => t.samples && t.creatable !== false);
                this.templates.set(offered);
                const first = offered.find((t) => t.id === this.data?.preferred) ?? offered[0];
                this.choice.setValue(first?.id ?? null);
            },
            error: (e) => {
                this.templates.set([]);
                this.error.set(apiErrorMessage(e, 'Could not list the samples.'));
            },
        });
    }

    create(): void {
        const t = this.templates()?.find((x) => x.id === this.choice.value);
        if (!t || this.loading()) return;
        this.loading.set(true);
        this.error.set(null);
        this.spaces.createSample(t.id, t.name).subscribe({
            next: (space) => {
                this.loading.set(false);
                if (failedSampleRuns(space).length) this.created.set(space);
                else this.ref.close(space);
            },
            error: (e) => {
                this.loading.set(false);
                this.error.set(apiErrorMessage(e, 'Could not create the sample Space.'));
            },
        });
    }
}
