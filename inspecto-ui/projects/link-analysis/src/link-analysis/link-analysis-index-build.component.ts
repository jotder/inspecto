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
import { RouterLink } from '@angular/router';
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
    LinkIndexList,
    LinkIndexSummary,
} from '@inspecto/link-analysis/api/graph-runs.service';

const MODES: { id: LinkIndexBuildMode; label: string; help: string }[] = [
    { id: 'full', label: 'Full', help: 'Rebuild the index from the whole Dataset.' },
    { id: 'append', label: 'Append', help: 'Index only the files added since the live version.' },
    { id: 'compact', label: 'Compact', help: 'Merge the appended deltas into one sorted main.' },
];

/** One edge mapping of the loaded query: what a read of it would look up in a link index. */
export interface IndexMappingTarget {
    dataset: string;
    sourceCol: string;
    targetCol: string;
    kindCol?: string;
    label: string;
}

/**
 * DR-U5: for each loaded mapping, whether a read is served from a listed link index ("Index") or from the Dataset
 * ("Flat"), with the reason in plain words. Judged on Dataset and source/target columns - the server decides the rest
 * (kind, time, filter) per read and says so on that read's `source`.
 */
export function indexVsFlat(
    list: LinkIndexList | null,
    mappings: IndexMappingTarget[],
): { label: string; index: boolean; text: string }[] {
    if (!list) return [];
    const same = (a: string, b: string) => a.toLowerCase() === b.toLowerCase();
    return mappings.map((m) => {
        const hit = list.indexes.find(
            (i) =>
                i.dataset === m.dataset &&
                same(i.mapping.sourceCol, m.sourceCol) &&
                same(i.mapping.targetCol, m.targetCol),
        );
        if (!hit) {
            const other = list.indexes.some((i) => i.dataset === m.dataset);
            const why = other
                ? 'the link index of this Dataset was built over different columns'
                : 'no link index has been built for it';
            return { label: m.label, index: false, text: `${m.label}: reads the Dataset (flat) - ${why}.` };
        }
        if (!list.enabled)
            return {
                label: m.label,
                index: false,
                text: `${m.label}: link index v${hit.version} is built, but serving is off, so reads use the Dataset (flat).`,
            };
        if (hit.stale)
            return {
                label: m.label,
                index: false,
                text: `${m.label}: link index v${hit.version} is stale; a read may use the Dataset (flat) until it is rebuilt.`,
            };
        return { label: m.label, index: true, text: `${m.label}: reads are served from link index v${hit.version}.` };
    });
}

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
        RouterLink,
        InspectoAlertComponent,
        ChipComponent,
        InspectoSchemaFormComponent,
    ],
    template: `
        @if (known()) {
            <p class="text-sm" role="status" data-testid="index-serving">
                @if (enabled()) {
                    <strong>Link index serving is on</strong> for this Space: a read is answered from a fresh link index,
                    and from the Dataset otherwise.
                } @else {
                    <strong>Link index serving is off</strong> for this Space: every read uses the Dataset (flat). An
                    index built here is kept, and used once serving is on.
                }
                Only an administrator changes this, in
                <a routerLink="/settings/link-analysis" data-testid="index-settings-link">Settings &gt; Link Analysis</a>.
            </p>
            @for (m of mappingStates(); track m.label) {
                <p class="flex flex-wrap items-center gap-2 text-sm" data-testid="index-vs-flat">
                    <inspecto-chip variant="soft" [tone]="m.index ? 'primary' : 'neutral'">{{
                        m.index ? 'Index' : 'Flat'
                    }}</inspecto-chip>
                    <span>{{ m.text }}</span>
                </p>
            }
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
                    @if (index()) {
                        Choose the Dataset and the columns to index.
                    } @else {
                        This Space has no link index yet: choose the Dataset and the columns to index.
                    }
                    A new mapping is always built in full; append and compact only apply to a listed index.
                </p>
                <inspecto-schema-form
                    [specs]="mappingSpecs"
                    [optionLoaders]="mappingLoaders"
                    [initial]="mappingInitial()"
                ></inspecto-schema-form>
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
                    {{ index() && !newMapping() ? 'Build index' : 'Build first index (Full)' }}
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
                The server did not say which link indexes this Space has, so none can be built from here.
            </p>
        }
    `,
})
export class LinkAnalysisIndexBuildComponent {
    private readonly runs = inject(GraphRunsService);
    private sub: Subscription | null = null;

    /** May the Subject build an index (`canBuildLinkIndex`)? */
    readonly allowed = input(true);
    /** The loaded query's edge mappings - what the "Index / Flat" line judges. Empty = no query loaded. */
    readonly mappings = input<IndexMappingTarget[]>([]);

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

    /** `GET /inv/index` answered (false = the server cannot say, e.g. the Link Analysis module is absent). */
    readonly known = computed(() => !!this.runs.indexes());
    /** Index serving is on for the Space (`index.enabled`). A build needs no serving: it is allowed with serving off. */
    readonly enabled = computed(() => !!this.runs.indexes()?.enabled);
    /** For each loaded mapping: is a read of it served from a listed index, or flat - and why not. */
    readonly mappingStates = computed(() => indexVsFlat(this.runs.indexes(), this.mappings()));
    /** The first mapping of the loaded query, prefilled into the first-build form. */
    readonly mappingInitial = computed<Record<string, unknown> | undefined>(() => {
        const m = this.mappings()[0];
        return m
            ? { dataset: m.dataset, sourceCol: m.sourceCol, targetCol: m.targetCol, kindCol: m.kindCol ?? '' }
            : undefined;
    });
    /** The mapping form shows when the analyst asked for it, and always when there is no listed index to rebuild. */
    readonly choosingMapping = computed(() => this.newMapping() || !this.index());
    readonly cancellable = computed(() => {
        const s = this.build()?.status;
        return s === 'QUEUED' || s === 'RUNNING';
    });

    readonly choices = computed<LinkIndexSummary[]>(() => {
        return this.runs.indexes()?.indexes ?? [];
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
