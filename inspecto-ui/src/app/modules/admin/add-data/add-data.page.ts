import {
    ChangeDetectionStrategy,
    Component,
    OnInit,
    afterNextRender,
    computed,
    inject,
    signal,
    viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { LensService } from '@inspecto/core/api';
import { ComponentsService } from '@inspecto/core/api/components.service';
import { AddDataHandoff } from './add-data-drop.component';
import { AddDataAccess } from '@inspecto/link-analysis/add-data/add-data-access';
import { AddDataComponent } from '@inspecto/link-analysis/add-data/add-data.component';
import {
    ImportDataset,
    ImportKind,
    ImportSummary,
    rowConditions,
} from '@inspecto/link-analysis/add-data/add-data.model';
import { ImportDatasetCardComponent } from '@inspecto/link-analysis/add-data/import-dataset-card.component';

/**
 * The console's **Add data** (`/add-data`, ONBOARDING-FIRST-RUN-1 slice C3). Hosts the one Add data screen both apps use
 * (OF-1) with the console's settings: *what the columns hold* starts closed (`optional`), *Explore* opens Studio's Link
 * Analysis, and *Open in editor* opens the import's Pipeline in the guided editor (the expert path). The Catalog's
 * *Onboard a Stream* dialog stays the advanced start (OF-3). Under the screen, a card per Dataset already imported here
 * with **Add a file** and **Run now**; when the person may not add data the screen says why and offers no card (A-15).
 * A file dropped on Home, the Catalog or an empty Datasets pane arrives here through {@link AddDataHandoff} and is read
 * at once, as if dropped on the screen itself.
 */
@Component({
    selector: 'inspecto-console-add-data',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [AddDataComponent, ImportDatasetCardComponent, RouterLink],
    template: `
        <section class="mx-auto flex w-full max-w-4xl flex-col gap-6 p-6" aria-labelledby="add-data-title">
            <header class="flex flex-col gap-1">
                <h1 id="add-data-title" class="text-title m-0 font-semibold">Onboard a file</h1>
                <p class="text-secondary m-0">
                    Add a file to this Space: it becomes a Dataset you can query in Studio. Inspecto sets up the
                    Pipeline behind it for you.
                </p>
                @if (canOnboard()) {
                    <p class="m-0 text-sm" data-testid="advanced-start">
                        Want every stage from the outset?
                        <a routerLink="/catalog" [queryParams]="{ onboard: 'stream' }">Onboard a Stream</a> instead.
                    </p>
                }
            </header>
            <inspecto-add-data
                whatColumnsHold="optional"
                exploreRoute="/studio/link-analysis"
                editorRoute="/pipelines"
                (imported)="onImported($event)"
            ></inspecto-add-data>
            @if (!access.lockReason() && others().length) {
                <section class="flex flex-col gap-3" aria-labelledby="imports-title" data-testid="imported-datasets">
                    <h2 id="imports-title" class="m-0 text-lg font-semibold">Imported into this Space</h2>
                    <p class="text-secondary m-0 text-sm">Add the next file, or run an import again.</p>
                    @for (d of others(); track d.id) {
                        <inspecto-import-dataset-card
                            [dataset]="d"
                            editorRoute="/pipelines"
                        ></inspecto-import-dataset-card>
                    }
                </section>
            }
        </section>
    `,
})
export class ConsoleAddDataPageComponent implements OnInit {
    protected readonly access = inject(AddDataAccess);
    private readonly components = inject(ComponentsService);
    /** May open the Catalog's *Onboard a Stream* dialog (the advanced start). */
    protected readonly canOnboard = inject(LensService).canOnboardConnections;

    /** The Datasets Add data made in this Space (`origin: import`). */
    private readonly imports = signal<ImportDataset[]>([]);
    /** The Dataset the screen above just made: its card is already shown there. */
    private readonly justImported = signal<string | null>(null);
    protected readonly others = computed(() => this.imports().filter((d) => d.id !== this.justImported()));
    private readonly addData = viewChild.required(AddDataComponent);

    constructor() {
        const handoff = inject(AddDataHandoff);
        afterNextRender(() => {
            const f = handoff.take();
            if (f && !this.access.lockReason()) this.addData().read(f);
        });
    }

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
                            importSource: c.content['importSource'] as string | undefined,
                            importFilter: rowConditions(c.content['importFilter']),
                        })),
                ),
            error: () => this.imports.set([]),
        });
    }
}
