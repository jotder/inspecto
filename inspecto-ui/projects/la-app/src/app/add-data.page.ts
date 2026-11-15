import {
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    OnInit,
    computed,
    inject,
    signal,
    viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { apiErrorMessage, LensService } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { SessionService } from '@inspecto/core/auth/session.service';
import { ComponentsService } from '@inspecto/core/api/components.service';
import { AddDataAccess } from '@inspecto/link-analysis/add-data/add-data-access';
import { AddDataComponent } from '@inspecto/link-analysis/add-data/add-data.component';
import {
    ImportDataset,
    ImportKind,
    ImportSummary,
    rowConditions,
} from '@inspecto/link-analysis/add-data/add-data.model';
import { ImportDatasetCardComponent } from '@inspecto/link-analysis/add-data/import-dataset-card.component';
import { OnboardService } from '@inspecto/link-analysis/add-data/onboard.service';
import { FirstRun } from './first-run';

/**
 * **Add data** (`/add-data`; OB-14: the button says *Add data*, the screen title is *Onboard a file*). Hosts the one Add data
 * screen both apps use (LA-ONBOARD-1 phase 4, OF-1) and, under it, a card per Dataset already imported into this Space with
 * **Add a file** and **Run now** (UC-14; Inspecto LA has no Dataset page of its own). When the person may not add data here
 * the screen says why instead (A-15) and the cards are not offered - their actions would be refused the same way.
 *
 * When Add data is turned off in this Space and the person may administer it, the screen is one panel with **Turn on Add
 * data** in place of the locked reason (LA-FULL-CYCLE-TEST-1 D2): the button asks the server (`PUT /settings/data`,
 * `canAdminister`) and the Add data screen opens in place. A refusal is shown under the button, in the server's words.
 */
@Component({
    selector: 'la-app-add-data',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [AddDataComponent, ImportDatasetCardComponent, InspectoAlertComponent, MatButtonModule, RouterLink],
    template: `
        <section
            class="mx-auto flex w-full min-w-0 max-w-4xl flex-col gap-6 p-4 sm:p-6"
            aria-labelledby="la-add-data-title"
        >
            <header class="flex flex-col gap-1">
                <h1 #title id="la-add-data-title" class="text-title m-0 font-semibold" tabindex="-1">Onboard a file</h1>
                <p class="text-secondary m-0">
                    Add a file to this Space: it becomes a Dataset, a table Inspecto keeps, that you can explore as a
                    graph.
                </p>
            </header>
            @if (notice) {
                <inspecto-alert variant="warning" data-testid="la-first-run-notice">{{ notice }}</inspecto-alert>
            }
            @if (turnedOn()) {
                <inspecto-alert variant="success" data-testid="la-add-data-on"
                    >Add data is now on in this Space.</inspecto-alert
                >
            }
            @if (turnOnOffered()) {
                <section
                    class="bg-card flex flex-col items-start gap-3 rounded-2xl border p-4"
                    aria-labelledby="la-add-data-off-title"
                    data-testid="la-add-data-off"
                >
                    <h2 id="la-add-data-off-title" class="m-0 text-lg font-semibold">
                        Add data is turned off in this Space
                    </h2>
                    <p class="text-secondary m-0 text-sm">
                        While it is off, nobody can add files to this Space. When you turn it on, people who have the
                        right to upload data can add files here. You can turn it off again in
                        <a routerLink="/settings/add-data">Settings ▸ Add data</a>.
                    </p>
                    <button
                        type="button"
                        mat-flat-button
                        color="primary"
                        data-testid="la-turn-on-add-data"
                        [disabled]="turningOn()"
                        (click)="turnOn()"
                    >
                        Turn on Add data
                    </button>
                    @if (turnOnRefused(); as why) {
                        <inspecto-alert
                            variant="error"
                            title="Add data was not turned on"
                            data-testid="la-turn-on-refused"
                            >{{ why }}</inspecto-alert
                        >
                    }
                </section>
            } @else {
                <inspecto-add-data exploreRoute="/link-analysis" (imported)="onImported($event)"></inspecto-add-data>
            }
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
    private readonly session = inject(SessionService);
    private readonly lens = inject(LensService);
    private readonly onboard = inject(OnboardService);
    /** The person is the administrator the locked screen sends people to (OB-7): they get the switch itself. */
    protected readonly mayAdminister = computed(() => this.session.authMode() !== 'oidc' || this.lens.canAdminister());
    /**
     * Add data is off here and this person may turn it on: the screen is the button, not the locked reason. Not for
     * someone who could not add a file afterwards: the button would open a screen locked for the missing right.
     */
    protected readonly turnOnOffered = computed(
        () => this.access.selfImport() === false && this.mayAdminister() && this.access.canUpload(),
    );
    protected readonly turningOn = signal(false);
    /** Why the server refused to turn it on, in its words. */
    protected readonly turnOnRefused = signal('');
    /** Turned on from this screen just now: said once, and announced to a screen reader. */
    protected readonly turnedOn = signal(false);
    /** A step of Create a Space that was refused (FirstRun leaves it across the reload). */
    protected readonly notice = inject(FirstRun).takeNotice();
    private readonly title = viewChild.required<ElementRef<HTMLElement>>('title');
    private readonly components = inject(ComponentsService);

    /** The Datasets Add data made in this Space (`origin: import`). */
    private readonly imports = signal<ImportDataset[]>([]);
    /** The Dataset the screen above just made: its card is already shown there. */
    private readonly justImported = signal<string | null>(null);
    protected readonly others = computed(() => this.imports().filter((d) => d.id !== this.justImported()));

    ngOnInit(): void {
        // The Add data screen reads the switch when it is drawn. Opening on the button instead (the switch was read as
        // off earlier in this visit), the page reads it, so one turned on since in Settings is not shown as off.
        if (this.turnOnOffered()) this.access.load();
        this.load();
    }

    /** Turn Add data on in this Space, then open the Add data screen in place. */
    protected turnOn(): void {
        this.turningOn.set(true);
        this.turnOnRefused.set('');
        this.onboard.setDataSettings(true).subscribe({
            next: (s) => {
                this.turningOn.set(false);
                this.access.selfImport.set(s.selfImportInForce !== false);
                this.turnedOn.set(s.selfImportInForce !== false);
                // the button that held focus is gone: hand focus to the page title, not to the page body
                this.title().nativeElement.focus();
            },
            error: (e: unknown) => {
                this.turningOn.set(false);
                this.turnOnRefused.set(apiErrorMessage(e, 'The server did not accept the change. Try again.'));
            },
        });
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
