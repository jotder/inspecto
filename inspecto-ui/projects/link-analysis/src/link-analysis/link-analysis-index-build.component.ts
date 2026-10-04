import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    computed,
    inject,
    input,
    signal,
    viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api/api-base';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { columnOptionLoader, datasetOptionLoader } from '@inspecto/core/components/entity-option-loaders';
import { InspectoSchemaFormComponent } from '@inspecto/core/components/schema-form.component';
import { indexMappingAttributes, indexMappingRequest } from './index-mapping';
import {
    GraphRunsService,
    LinkIndexBuildMode,
    LinkIndexBuildRequest,
    LinkIndexBuildView,
    LinkIndexSummary,
} from '@inspecto/link-analysis/api/graph-runs.service';

const MODES: { id: LinkIndexBuildMode; label: string; help: string }[] = [
    { id: 'full', label: 'Full', help: 'Rebuild the index from the whole Dataset.' },
    { id: 'append', label: 'Append', help: 'Index only the files added since the live version.' },
    { id: 'compact', label: 'Compact', help: 'Merge the appended deltas into one sorted main.' },
];

/** The sentence a refused build start reads as; each status means something specific on `POST /inv/index/builds`. */
export function indexBuildErrorMessage(err: unknown): string {
    if (!(err instanceof HttpErrorResponse)) return 'The index build could not be started.';
    const server = apiErrorMessage(err, '');
    switch (err.status) {
        case 403:
            return 'You are not allowed to build an index (it needs the Build link index capability).';
        case 404:
            return 'The Dataset was not found, or it is not yours to read.';
        case 409:
            return `That build cannot run now: ${server || 'one is already running for this index, or the mode does not apply.'}`;
        case 422:
            return `The server refused the build: ${server || 'check the mapping and the mode.'}`;
        case 503:
            return 'Index builds are not available here (no writable Space) or the build queue is full. Try again in a moment.';
        default:
            return server || 'The index build could not be started.';
    }
}

/**
 * **Build the edge index** (`LA-INDEX-SPA-SURFACES-1`): rebuilds a listed index with the same mapping in one of three
 * modes (`full` | `append` | `compact`, `POST /inv/index/builds`) and shows the server's advice (`plan` of
 * `GET /inv/index`) beside the choice. The advice never builds anything and never blocks: a mode the server cannot
 * apply is a 409 that names why, put in words here. Polling is bound to this component.
 */
@Component({
    selector: 'inspecto-link-analysis-index-build',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        DecimalPipe,
        MatButtonModule,
        MatIconModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoSchemaFormComponent,
    ],
    template: `
        @if (index() || enabled()) {
            @if (index(); as ix) {
                <div class="flex flex-wrap items-center gap-2">
                    @if (choices().length > 1) {
                        <label class="text-secondary text-sm">
                            Index
                            <select
                                class="ml-1 rounded border px-1"
                                data-testid="build-index-pick"
                                (change)="pick($any($event.target).value)"
                            >
                                @for (c of choices(); track c.dataset) {
                                    <option [value]="c.dataset" [selected]="c.dataset === ix.dataset">
                                        {{ c.dataset }}
                                    </option>
                                }
                            </select>
                        </label>
                    }
                    <span class="text-sm" data-testid="build-index-version">
                        {{ ix.dataset }}: version {{ ix.version }}{{ ix.deltas ? ', ' + ix.deltas + ' appended' : '' }}
                    </span>
                    @if (ix.stale) {
                        <inspecto-chip variant="soft" tone="warning" [title]="ix.reason ?? ''"
                            >Index stale</inspecto-chip
                        >
                    }
                </div>
                @if (ix.plan; as plan) {
                    <div class="mt-1 text-sm" data-testid="build-plan">
                        <p>
                            Advice:
                            <strong data-testid="build-plan-recommended">{{
                                plan.recommended === 'none' ? 'nothing to do' : plan.recommended
                            }}</strong
                            >. {{ plan.added }} file(s) added, {{ plan.removed }} removed, {{ plan.changed }} changed
                            since this version.
                        </p>
                        @if (plan.reasons.length) {
                            <ul class="list-inside list-disc" aria-label="Why this advice">
                                @for (r of plan.reasons; track $index) {
                                    <li>{{ r }}</li>
                                }
                            </ul>
                        }
                        @for (g of sampleGroups(); track g.label) {
                            <p class="text-secondary">{{ g.label }}: {{ g.paths.join(', ') }}</p>
                        }
                    </div>
                }
            }
            @if (index()) {
                <label class="mt-2 flex items-center gap-1 text-sm">
                    <input
                        type="checkbox"
                        data-testid="new-mapping"
                        [checked]="newMapping()"
                        [disabled]="busy()"
                        (change)="newMapping.set($any($event.target).checked)"
                    />
                    Index a different mapping instead
                </label>
            }
            @if (choosingMapping()) {
                <p class="text-secondary mt-1 text-sm" data-testid="new-mapping-help">
                    Choose the Dataset and the columns to index. A new mapping is always built in full; append and
                    compact only apply to a listed index.
                </p>
                <inspecto-schema-form [specs]="mappingSpecs" [optionLoaders]="mappingLoaders"></inspecto-schema-form>
            } @else {
                <fieldset class="mt-2 flex flex-wrap items-center gap-3" [disabled]="busy()">
                    <legend class="text-secondary text-sm">Build mode</legend>
                    @for (m of modes; track m.id) {
                        <label class="flex items-center gap-1 text-sm">
                            <input
                                type="radio"
                                name="index-build-mode"
                                [value]="m.id"
                                [attr.data-testid]="'mode-' + m.id"
                                [checked]="effectiveMode() === m.id"
                                (change)="mode.set(m.id)"
                            />
                            {{ m.label }}
                        </label>
                    }
                </fieldset>
                <p class="text-secondary mt-1 text-sm" data-testid="mode-help">{{ modeHelp() }}</p>
            }
            <div class="mt-1 flex flex-wrap items-center gap-2">
                <button
                    mat-stroked-button
                    data-testid="start-build"
                    [disabled]="!!blockedReason() || busy()"
                    (click)="start()"
                >
                    <mat-icon svgIcon="heroicons_outline:server-stack"></mat-icon>
                    Build index
                </button>
                @if (cancellable()) {
                    <button mat-stroked-button data-testid="cancel-build" [disabled]="cancelling()" (click)="cancel()">
                        <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                        Cancel build
                    </button>
                }
            </div>
            @if (blockedReason(); as why) {
                <p class="text-secondary mt-1 text-sm" role="status" data-testid="build-blocked">{{ why }}</p>
            }
            @if (error()) {
                <inspecto-alert variant="error" title="Index build">{{ error() }}</inspecto-alert>
            }
            @if (build(); as b) {
                @switch (b.status) {
                    @case ('QUEUED') {
                        <p class="mt-1 text-sm" role="status" data-testid="build-progress">
                            Queued on the server.{{ cancelling() ? ' Cancelling…' : '' }}
                        </p>
                    }
                    @case ('RUNNING') {
                        <p class="mt-1 text-sm" role="status" data-testid="build-progress">
                            Building{{
                                b.progress
                                    ? ' - ' + b.progress.phase + ' (' + b.progress.step + '/' + b.progress.steps + ')'
                                    : ''
                            }}.{{ cancelling() ? ' Cancelling…' : '' }}
                        </p>
                    }
                    @case ('COMPLETED') {
                        <p class="mt-1 text-sm" role="status" data-testid="build-done">
                            Built version {{ b.result?.version }}: {{ b.result?.edges | number: '1.0-0' }} links over
                            {{ b.result?.nodes | number: '1.0-0' }} nodes in
                            {{ b.result?.totalMs | number: '1.0-0' }} ms.
                        </p>
                    }
                    @case ('CANCELLED') {
                        <inspecto-alert variant="info" title="Cancelled">The build was cancelled.</inspecto-alert>
                    }
                    @case ('FAILED') {
                        <inspecto-alert variant="error" title="Build failed">
                            The build failed{{ b.failure ? ' (' + b.failure + ')' : '' }}. The live version is
                            unchanged.
                        </inspecto-alert>
                    }
                }
            }
        } @else {
            <p class="text-secondary text-sm" data-testid="no-index">
                No index to build: the Space serves none yet, or index support is off.
            </p>
        }
    `,
})
export class LinkAnalysisIndexBuildComponent {
    private readonly runs = inject(GraphRunsService);
    private sub: Subscription | null = null;

    /** May the Subject build an index (`canBuildLinkIndex`)? */
    readonly allowed = input(true);

    readonly modes = MODES;
    readonly mode = signal<LinkIndexBuildMode | null>(null);
    private readonly pickedDataset = signal('');
    readonly build = signal<LinkIndexBuildView | null>(null);
    readonly error = signal('');
    private readonly starting = signal(false);

    /** LA-INDEX-SPA-SURFACES-1: index a mapping no listed index has, instead of rebuilding a listed one. */
    readonly newMapping = signal(false);
    readonly mappingSpecs = indexMappingAttributes();
    readonly mappingLoaders = {
        dataset: datasetOptionLoader(),
        ...Object.fromEntries(
            ['sourceCol', 'targetCol', 'kindCol', 'timeCol', 'weightCol', 'attrCols'].map((k) => [
                k,
                columnOptionLoader('dataset'),
            ]),
        ),
    };
    private readonly mappingForm = viewChild(InspectoSchemaFormComponent);
    readonly cancelling = signal(false);

    /** Index support is on for the Space (`index.enabled`) — a build of a new mapping needs no listed index. */
    readonly enabled = computed(() => !!this.runs.indexes()?.enabled);
    /** The mapping form shows when the analyst asked for it, and always when there is no listed index to rebuild. */
    readonly choosingMapping = computed(() => this.newMapping() || !this.index());
    readonly cancellable = computed(() => {
        const s = this.build()?.status;
        return s === 'QUEUED' || s === 'RUNNING';
    });

    readonly choices = computed<LinkIndexSummary[]>(() => {
        const l = this.runs.indexes();
        return l?.enabled ? l.indexes : [];
    });
    readonly index = computed<LinkIndexSummary | null>(() => {
        const all = this.choices();
        return all.find((i) => i.dataset === this.pickedDataset()) ?? all[0] ?? null;
    });
    /** The mode in force: the analyst's pick, else the server's advice, else a full build. */
    readonly effectiveMode = computed<LinkIndexBuildMode>(() => {
        const rec = this.index()?.plan?.recommended;
        return this.mode() ?? (rec && rec !== 'none' ? rec : 'full');
    });
    readonly modeHelp = computed(() => MODES.find((m) => m.id === this.effectiveMode())?.help ?? '');
    readonly busy = computed(() => {
        const s = this.build()?.status;
        return this.starting() || s === 'QUEUED' || s === 'RUNNING';
    });
    readonly blockedReason = computed(() =>
        this.allowed() ? '' : 'Building an index needs the Build link index capability, which your role does not hold.',
    );
    readonly sampleGroups = computed(() => {
        const s = this.index()?.plan?.samples;
        return s
            ? [
                  { label: 'Added', paths: s.added ?? [] },
                  { label: 'Removed', paths: s.removed ?? [] },
                  { label: 'Changed', paths: s.changed ?? [] },
              ].filter((g) => g.paths.length)
            : [];
    });

    constructor() {
        inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
        this.runs.loadIndexes();
    }

    pick(dataset: string): void {
        this.pickedDataset.set(dataset);
        this.mode.set(null);
    }

    /** Ask the server to stop the build; the polling that is already running then reads it as `CANCELLED`. */
    cancel(): void {
        const b = this.build();
        if (!b || this.cancelling() || !this.cancellable()) return;
        this.cancelling.set(true);
        this.error.set('');
        this.runs.cancelBuild(b.buildId).subscribe({
            error: (err) => {
                this.cancelling.set(false);
                this.error.set(indexBuildCancelMessage(err));
            },
        });
    }

    start(): void {
        if (this.blockedReason() || this.busy()) return;
        const req = this.choosingMapping() ? this.mappingRequest() : this.listedRequest();
        if (!req) return;
        this.sub?.unsubscribe();
        this.error.set('');
        this.build.set(null);
        this.cancelling.set(false);
        this.starting.set(true);
        this.sub = this.runs.startBuild(req).subscribe({
            next: (first) => {
                this.starting.set(false);
                this.build.set(first);
                this.sub = this.runs.watchBuild(first.buildId).subscribe({
                    next: (v) => {
                        this.build.set(v);
                        if (v.status === 'COMPLETED') this.runs.reloadIndexes();
                    },
                    error: () => this.error.set('The build could not be read back from the server.'),
                });
            },
            error: (err) => {
                this.starting.set(false);
                this.error.set(indexBuildErrorMessage(err));
            },
        });
    }

    /** A listed index rebuilt with its OWN mapping, in the chosen mode. */
    private listedRequest(): LinkIndexBuildRequest | null {
        const ix = this.index();
        if (!ix) return null;
        const m = ix.mapping;
        return {
            dataset: ix.dataset,
            sourceCol: m.sourceCol,
            targetCol: m.targetCol,
            ...(m.kindCol ? { kindCol: m.kindCol } : {}),
            ...(m.timeCol ? { timeCol: m.timeCol } : {}),
            ...(m.timeColZone ? { timeColZone: m.timeColZone } : {}),
            ...(m.weightCol ? { weightCol: m.weightCol } : {}),
            ...(m.attrCols?.length ? { attrCols: m.attrCols } : {}),
            mode: this.effectiveMode(),
        };
    }

    /** A mapping the analyst chose; null (and the form's own messages) until its Dataset and two columns are set. */
    private mappingRequest(): LinkIndexBuildRequest | null {
        const form = this.mappingForm();
        if (!form || !form.validate()) return null;
        return indexMappingRequest(form.value());
    }
}

/** A refused cancel in words: 403 / 404 / 409 each mean something specific on `POST /inv/index/builds/{id}/cancel`. */
export function indexBuildCancelMessage(err: unknown): string {
    if (!(err instanceof HttpErrorResponse)) return 'The build could not be cancelled.';
    switch (err.status) {
        case 403:
            return "Only the build's starter or an administrator can cancel it.";
        case 404:
            return 'That build was not found - it is not yours to cancel, or it was never started.';
        case 409:
            return 'The build had already finished, so there was nothing to cancel.';
        default:
            return apiErrorMessage(err, 'The build could not be cancelled.');
    }
}
