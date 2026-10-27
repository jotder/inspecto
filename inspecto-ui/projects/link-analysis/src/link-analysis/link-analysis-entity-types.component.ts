import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { firstValueFrom } from 'rxjs';
import { LensService, apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { NewEntityTypeComponent } from '@inspecto/link-analysis/entities/new-entity-type.component';
import { settingsBodyWithTypes } from '@inspecto/link-analysis/entities/entity-columns';
import { LinkAnalysisLimits, LinkAnalysisSettingsService } from './link-analysis-settings.service';

/**
 * **⚙ Settings → Entity Types** (LA-STUDIO-UI-REORG-1 §5.10, sweep S3): the types in force, *New Entity Type*, and
 * *Retire*. Both write the Space's stated `entityTypes` list through `PUT /settings/link-analysis` — the stated list
 * replaces the defaults, so retiring one states the types in force minus it. The server validates (a type a column
 * still claims, or the last type, is refused there) and its message is shown.
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
                        <span class="text-secondary text-xs">· {{ t.normaliser }}{{ t.masked ? ' · masked' : '' }}</span>
                        @if (canEdit()) {
                            <button
                                mat-button
                                class="ml-auto"
                                [disabled]="busy()"
                                [attr.data-testid]="'la-entity-type-retire-' + t.id"
                                [attr.aria-label]="'Retire the Entity Type ' + (t.label || t.id)"
                                (click)="retire(t.id, t.label || t.id)"
                            >
                                Retire
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
    private readonly settings = inject(LinkAnalysisSettingsService);
    private readonly confirm = inject(InspectoConfirmService);
    private readonly lens = inject(LensService);

    readonly types = computed(() => this.settings.limits().entityTypesInForce ?? []);
    readonly canEdit = computed(() => this.lens.canAuthorWorkbench());
    readonly adding = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');

    async retire(id: string, label: string): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Retire the Entity Type "${label}"? Columns can no longer be classified as it; saved Investigations keep their steps.`,
            { title: 'Retire Entity Type', confirmText: 'Retire' },
        );
        if (!ok) return;
        this.busy.set(true);
        this.error.set('');
        try {
            // The PUT replaces the document: start from what is stored now.
            const served = await firstValueFrom(this.settings.get());
            const kept = (served.entityTypesInForce ?? []).filter((t) => t.id !== id);
            await firstValueFrom(this.settings.save(settingsBodyWithTypes(served, kept) as unknown as LinkAnalysisLimits));
        } catch (e) {
            this.error.set(apiErrorMessage(e, 'The Entity Type could not be retired.'));
        } finally {
            this.busy.set(false);
        }
    }
}
