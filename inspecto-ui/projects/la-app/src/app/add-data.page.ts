import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { ComponentsService } from '@inspecto/core/api/components.service';
import { AddDataAccess } from '@inspecto/link-analysis/add-data/add-data-access';
import { AddDataComponent } from '@inspecto/link-analysis/add-data/add-data.component';
import { ImportDataset, ImportKind, ImportSummary } from '@inspecto/link-analysis/add-data/add-data.model';
import { ImportDatasetCardComponent } from '@inspecto/link-analysis/add-data/import-dataset-card.component';

/**
 * **Add data** (`/add-data`; OB-14: the button says *Add data*, the screen title is *Onboard a file*). Hosts the one Add data
 * screen both apps use (LA-ONBOARD-1 phase 4, OF-1) and, under it, a card per Dataset already imported into this Space with
 * **Add a file** and **Run now** (UC-14; Inspecto LA has no Dataset page of its own). When the person may not add data here
 * the screen says why instead (A-15) and the cards are not offered - their actions would be refused the same way.
 */
@Component({
    selector: 'la-app-add-data',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [AddDataComponent, ImportDatasetCardComponent],
    template: `
        <section class="mx-auto flex w-full max-w-4xl flex-col gap-6 p-6" aria-labelledby="la-add-data-title">
            <header class="flex flex-col gap-1">
                <h1 id="la-add-data-title" class="text-title m-0 font-semibold">Onboard a file</h1>
                <p class="text-secondary m-0">
                    Add a file to this Space: it becomes a Dataset, a table Inspecto keeps, that you can explore as a
                    graph.
                </p>
            </header>
            <inspecto-add-data exploreRoute="/link-analysis" (imported)="onImported($event)"></inspecto-add-data>
            @if (!access.lockReason() && others().length) {
                <section class="flex flex-col gap-3" aria-labelledby="la-imports-title" data-testid="imported-datasets">
                    <h2 id="la-imports-title" class="m-0 text-lg font-semibold">Imported into this Space</h2>
                    <p class="text-secondary m-0 text-sm">Add the next file, or run an import again.</p>
                    @for (d of others(); track d.id) {
                        <inspecto-import-dataset-card [dataset]="d"></inspecto-import-dataset-card>
                    }
                </section>
            }
        </section>
    `,
})
export class AddDataPageComponent implements OnInit {
    protected readonly access = inject(AddDataAccess);
    private readonly components = inject(ComponentsService);

    /** The Datasets Add data made in this Space (`origin: import`). */
    private readonly imports = signal<ImportDataset[]>([]);
    /** The Dataset the screen above just made: its card is already shown there. */
    private readonly justImported = signal<string | null>(null);
    protected readonly others = computed(() => this.imports().filter((d) => d.id !== this.justImported()));

    ngOnInit(): void {
        this.load();
    }

    protected onImported(s: ImportSummary): void {
        this.justImported.set(s.dataset);
        this.load();
    }

    private load(): void {
        this.components.list('dataset').subscribe({
            next: (all) =>
                this.imports.set(
                    all
                        .filter((c) => c.content['origin'] === 'import')
                        .map((c) => ({
                            id: c.name,
                            kind: (c.content['kind'] === 'reference' ? 'reference' : 'stream') as ImportKind,
                            description:
                                typeof c.content['description'] === 'string' ? c.content['description'] : undefined,
                            primaryEntityType: (c.content['primaryEntityType'] as string | undefined) ?? null,
                            importPipeline: (c.content['importPipeline'] as string | undefined) ?? c.name,
                        })),
                ),
            error: () => this.imports.set([]),
        });
    }
}
