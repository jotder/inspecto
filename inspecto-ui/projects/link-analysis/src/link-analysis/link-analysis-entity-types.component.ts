import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { firstValueFrom } from 'rxjs';
import { LensService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { NewEntityTypeComponent } from '@inspecto/link-analysis/entities/new-entity-type.component';
import { NORMALISER_OPTIONS } from '@inspecto/link-analysis/entities/entity-columns';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';

/**
 * **⚙ Settings → Entity Types** (LA-STUDIO-UI-REORG-1 §5.10, sweep S3): the types in force, *New Entity Type*, and
 * *Delete*. New writes the Space's stated `entityTypes` list through `PUT /settings/link-analysis`; Delete calls
 * `DELETE /inv/entity-types/{id}` (LA-ENTITY-TYPE-RETIRE-1), which refuses (409) while a Dataset column, a live Entity
 * List or an Investigation uses the type, or for the last type, and its message is shown.
 */
@Component({
    selector: 'inspecto-la-entity-types',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, InspectoAlertComponent, NewEntityTypeComponent],
    template: `
        <div class="flex flex-col gap-3" data-testid="la-entity-types">
            <ul class="m-0 flex list-none flex-col gap-1 p-0" aria-label="Entity Types in force">
                @for (t of types(); track t.id) {
                    <li class="flex items-center gap-2 rounded-md border px-3 py-1.5 text-sm" style="border-color: var(--gamma-border)">
                        <span class="font-medium">{{ t.label || t.id }}</span>
                        <code class="text-secondary text-xs">{{ t.id }}</code>
                        <span class="text-secondary text-xs">· {{ normaliserLabel(t.normaliser) }}{{ t.masked ? ' · masked' : '' }}</span>
                        @if (canEdit()) {
                            <button
                                mat-button
                                class="ml-auto"
                                [disabled]="busy()"
                                [attr.data-testid]="'la-entity-type-delete-' + t.id"
                                [attr.aria-label]="'Delete the Entity Type ' + (t.label || t.id)"
                                (click)="remove(t.id, t.label || t.id)"
                            >
                                Delete
                            </button>
                        }
                    </li>
                } @empty {
                    <li class="text-secondary text-sm">No Entity Types are in force.</li>
                }
            </ul>
            @if (error()) {
                <inspecto-alert variant="error" title="Not changed">{{ error() }}</inspecto-alert>
            }
            @if (canEdit()) {
                @if (adding()) {
                    <inspecto-la-new-entity-type (added)="adding.set(false)" (cancelled)="adding.set(false)" />
                } @else {
                    <button mat-stroked-button class="self-start" data-testid="la-entity-type-new" (click)="adding.set(true)">
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                        New Entity Type
                    </button>
                }
            } @else {
                <p class="text-secondary m-0 text-xs">
                    Changing Entity Types needs permission to change this Space's Link Analysis settings.
                </p>
            }
        </div>
    `,
})
export class LinkAnalysisEntityTypesComponent {
    /** How values are compared, in the same plain words as the New Entity Type form (T-190); never the normaliser id. */
    normaliserLabel(id: string | undefined): string {
        return NORMALISER_OPTIONS.find((o) => o.value === (id || 'default'))?.label ?? id ?? '';
    }
    private readonly settings = inject(LinkAnalysisSettingsService);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly lens = inject(LensService);

    readonly types = computed(() => this.settings.limits().entityTypesInForce ?? []);
    readonly canEdit = computed(() => this.lens.canAuthorWorkbench());
    readonly adding = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');

    async remove(id: string, label: string): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Delete the Entity Type "${label}"? It cannot be undone. A type still used by a Dataset column, an Entity List or an Investigation is not deleted.`,
            { title: 'Delete Entity Type', confirmText: 'Delete' },
        );
        if (!ok) return;
        this.busy.set(true);
        this.error.set('');
        try {
            await firstValueFrom(this.settings.deleteEntityType(id));
        } catch (e) {
            this.error.set(apiErrorMessage(e, 'The Entity Type could not be deleted.'));
        } finally {
            this.busy.set(false);
        }
    }
}
