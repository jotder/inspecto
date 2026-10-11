import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { GraphEmphasis } from '@inspecto/core/graph/graph-view.component';
import {
    GraphAlgorithm,
    GraphAlgorithmParam,
    GraphParamType,
    GraphRunResult,
    GraphRunsService,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { DomainProfileId } from '@inspecto/link-analysis/graph/domain-profile';
import { ServerIdMap } from './graph-run-apply';
import { GraphRunSummary, PropagatedRiskRow, propagatedRiskRows, summarizeGraphRun } from './graph-run-summary';
import { LinkAnalysisServerRunComponent } from './link-analysis-server-run.component';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { nodeScoresFor, parseWeights, riskMappingFor } from './node-risk';
import { NodeRiskService } from './node-risk.service';

/** The scalar parameter types this panel has a field for: a number field or a choice. */
const EDITABLE: ReadonlySet<GraphParamType> = new Set<GraphParamType>(['INT', 'DOUBLE', 'ENUM']);
/** The list / map types with their own control: a number list, a node list, a node-score map. */
const STRUCTURED: ReadonlySet<GraphParamType> = new Set<GraphParamType>(['DOUBLE_LIST', 'ID_LIST', 'SCORE_MAP']);

export function isEditableParam(p: GraphAlgorithmParam): boolean {
    return EDITABLE.has(p.type);
}

/**
 * The parameters of `a` the panel has no control for (a type it does not know). Non-empty = the algorithm is API only
 * here: leaving them out would run on defaults the analyst never saw - so Run is held instead.
 */
export function apiOnlyParams(a: GraphAlgorithm): string[] {
    return a.params.filter((p) => !isEditableParam(p) && !STRUCTURED.has(p.type)).map((p) => p.name);
}

/**
 * **All algorithms (server)** (DR-U11): every algorithm the server catalogue lists, runnable whatever the Working Set's
 * size. The form is DRIVEN BY THE CATALOGUE (`GET /inv/graph/algorithms`): each parameter descriptor becomes a number
 * field or a choice, `needsSource` / `needsTarget` / `needsNode` become node pickers. The run and its states are the
 * shared {@link LinkAnalysisServerRunComponent}; the answer is read by its result kind alone ({@link summarizeGraphRun}),
 * so a new algorithm needs no panel here.
 */
@Component({
    selector: 'inspecto-link-analysis-server-algorithms',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, InspectoOptionPickerComponent, LinkAnalysisServerRunComponent],
    template: `
        @if (!algorithms().length) {
            <p class="text-secondary text-sm" data-testid="algo-none">The server's algorithm list is not available.</p>
        } @else {
            <p class="text-secondary text-sm" data-testid="algo-count">
                {{ algorithms().length }} algorithms on the server.
            </p>
            <inspecto-option-picker
                class="w-72"
                label="Algorithm"
                [options]="algorithmOptions()"
                [ngModel]="picked()"
                (ngModelChange)="pick($any($event))"
            />
            @if (current(); as a) {
                <div class="flex flex-wrap items-center gap-2" data-testid="algo-form">
                    @if (a.needsSource) {
                        <inspecto-option-picker
                            class="w-56"
                            label="From"
                            [options]="nodeChoices()"
                            [ngModel]="nodes().from ?? ''"
                            (ngModelChange)="setNode('from', $any($event))"
                        />
                    }
                    @if (a.needsTarget) {
                        <inspecto-option-picker
                            class="w-56"
                            label="To"
                            [options]="nodeChoices()"
                            [ngModel]="nodes().to ?? ''"
                            (ngModelChange)="setNode('to', $any($event))"
                        />
                    }
                    @if (a.needsNode) {
                        <inspecto-option-picker
                            class="w-56"
                            label="Node"
                            [options]="nodeChoices()"
                            [ngModel]="nodes().node ?? ''"
                            (ngModelChange)="setNode('node', $any($event))"
                        />
                    }
                    @for (p of structuredParams(); track p.name) {
                        @if (p.type === 'DOUBLE_LIST') {
                            <label class="text-secondary text-sm">
                                {{ p.name }}
                                <input
                                    type="text"
                                    class="ml-1 w-40 rounded border px-1"
                                    [attr.data-testid]="'param-' + p.name"
                                    [attr.aria-describedby]="'hint-' + p.name"
                                    [value]="listText(p)"
                                    (change)="setList(p.name, $any($event.target).value)"
                                />
                                <span class="ml-1 text-xs" [id]="'hint-' + p.name">per hop, 0..1</span>
                            </label>
                            @if (listErrors()[p.name]; as e) {
                                <p class="text-warn text-xs" role="alert">{{ e }}</p>
                            }
                        } @else if (p.type === 'ID_LIST') {
                            <div class="flex flex-wrap items-center gap-1" [attr.data-testid]="'param-' + p.name">
                                <inspecto-option-picker
                                    class="w-56"
                                    [label]="'Add to ' + p.name"
                                    [options]="nodeChoices()"
                                    [ngModel]="''"
                                    (ngModelChange)="addId(p.name, $any($event))"
                                />
                                @for (id of idList(p.name); track id) {
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        [attr.aria-label]="'Remove ' + labelOfCanvas(id) + ' from ' + p.name"
                                        (click)="removeId(p.name, id)"
                                    >
                                        {{ labelOfCanvas(id) }} ✕
                                    </button>
                                }
                            </div>
                        } @else {
                            <div class="flex flex-wrap items-center gap-2" [attr.data-testid]="'param-' + p.name">
                                <button
                                    mat-stroked-button
                                    type="button"
                                    [attr.data-testid]="'fill-' + p.name"
                                    [disabled]="!riskMapping() || filling()"
                                    (click)="fillScores(p)"
                                >
                                    Fill {{ p.name }} from indicators
                                </button>
                                <span class="text-secondary text-xs" role="status">{{
                                    scoreNote()[p.name] ??
                                        (riskMapping()
                                            ? ''
                                            : 'No indicators Dataset is mapped for this domain profile.')
                                }}</span>
                            </div>
                        }
                    }
                    @for (p of editableParams(); track p.name) {
                        @if (p.type === 'ENUM') {
                            <label class="text-secondary text-sm">
                                {{ p.name }}
                                <select
                                    class="ml-1 rounded border px-1"
                                    [attr.data-testid]="'param-' + p.name"
                                    (change)="setParam(p.name, $any($event.target).value)"
                                >
                                    @for (c of p.allowed ?? []; track c) {
                                        <option [value]="c" [selected]="c === valueOf(p.name, p.default)">
                                            {{ c }}
                                        </option>
                                    }
                                </select>
                            </label>
                        } @else {
                            <label class="text-secondary text-sm">
                                {{ p.name }}
                                <input
                                    type="number"
                                    class="ml-1 w-24 rounded border px-1"
                                    [attr.data-testid]="'param-' + p.name"
                                    [attr.min]="p.min"
                                    [attr.max]="p.max"
                                    [attr.step]="p.type === 'INT' ? 1 : 'any'"
                                    [value]="valueOf(p.name, p.default)"
                                    (change)="setParam(p.name, $any($event.target).value, true)"
                                />
                            </label>
                        }
                    }
                </div>
                <inspecto-link-analysis-server-run
                    [algorithm]="a.id"
                    [params]="params()"
                    [hold]="hold()"
                    [investigationId]="investigationId()"
                    [investigationOpen]="investigationOpen()"
                    [allowed]="allowed()"
                    (completed)="onCompleted($event)"
                />
                @if (summary(); as s) {
                    <div class="mt-1 text-sm" data-testid="algo-result">
                        <p>{{ s.headline }}</p>
                        @if (riskRows().length) {
                            <table class="mt-1 w-full text-left text-sm" data-testid="risk-table">
                                <caption class="sr-only">
                                    Propagated risk, highest first
                                </caption>
                                <thead>
                                    <tr>
                                        <th scope="col">#</th>
                                        <th scope="col">Node</th>
                                        <th scope="col" class="text-right">Score</th>
                                        <th scope="col" class="text-right">Raw</th>
                                        <th scope="col" class="text-right">Own</th>
                                        <th scope="col" class="text-right">Contributors</th>
                                        <th scope="col"><span class="sr-only">Factors</span></th>
                                    </tr>
                                </thead>
                                <tbody>
                                    @for (r of riskRows(); track r.id; let i = $index) {
                                        <tr>
                                            <td>{{ i + 1 }}</td>
                                            <td>{{ r.label }}</td>
                                            <td class="text-right font-semibold">{{ r.score }}</td>
                                            <td class="text-right">{{ r.raw }}</td>
                                            <td class="text-right">{{ r.own }}</td>
                                            <td class="text-right">{{ r.contributors }}</td>
                                            <td>
                                                @if (r.factors.length) {
                                                    <button
                                                        mat-button
                                                        type="button"
                                                        [attr.aria-expanded]="expanded() === r.id"
                                                        [attr.aria-label]="'Factors of ' + r.label"
                                                        (click)="toggleFactors(r.id)"
                                                    >
                                                        {{ expanded() === r.id ? 'Hide' : 'Factors' }}
                                                    </button>
                                                }
                                            </td>
                                        </tr>
                                        @if (expanded() === r.id) {
                                            <tr [attr.data-testid]="'factors-' + r.id">
                                                <td></td>
                                                <td colspan="6">
                                                    <ul class="list-inside list-disc">
                                                        @for (f of r.factors; track $index) {
                                                            <li>{{ f }}</li>
                                                        }
                                                    </ul>
                                                </td>
                                            </tr>
                                        }
                                    }
                                </tbody>
                            </table>
                        } @else if (s.lines.length) {
                            <ol class="mt-1 list-inside list-decimal">
                                @for (l of s.lines; track $index) {
                                    <li>{{ l }}</li>
                                }
                            </ol>
                        }
                        @if (s.nodeIds.length || s.edgeIds.length) {
                            <button mat-stroked-button class="mt-1" data-testid="algo-highlight" (click)="highlight(s)">
                                Highlight on canvas
                            </button>
                        }
                    </div>
                }
            }
        }
    `,
})
export class LinkAnalysisServerAlgorithmsComponent {
    private readonly runs = inject(GraphRunsService);
    private readonly settings = inject(LinkAnalysisSettingsService);
    private readonly risk = inject(NodeRiskService);

    readonly investigationId = input<string | null>(null);
    readonly investigationOpen = input(false);
    readonly allowed = input(true);
    readonly nodeOptions = input<{ id: string; label: string }[]>([]);
    readonly serverIds = input<ServerIdMap | null>(null);
    /** The domain profile: names the indicators Dataset "Fill nodeScores" reads (`riskMappingFor`). */
    readonly profileId = input<DomainProfileId | null>(null);
    readonly emphasisChange = output<GraphEmphasis | null>();
    /** Every completed answer, so the host can feed a ranking or a partition into its own ranking / community views. */
    readonly resultChange = output<GraphRunResult>();

    readonly algorithms = computed<GraphAlgorithm[]>(() => this.runs.catalogue()?.algorithms ?? []);
    readonly algorithmOptions = computed<PickerOption[]>(() =>
        this.algorithms().map((a) => ({
            value: a.id,
            label: apiOnlyParams(a).length ? `${a.label} (API only)` : a.label,
        })),
    );
    readonly picked = signal('');
    readonly current = computed(() => {
        const all = this.algorithms();
        return all.find((a) => a.id === this.picked()) ?? all[0] ?? null;
    });
    readonly editableParams = computed(() => (this.current()?.params ?? []).filter(isEditableParam));
    readonly structuredParams = computed(() => (this.current()?.params ?? []).filter((p) => STRUCTURED.has(p.type)));
    readonly riskMapping = computed(() => riskMappingFor(this.profileId()));
    readonly listErrors = signal<Record<string, string>>({});
    readonly scoreNote = signal<Record<string, string>>({});
    readonly filling = signal(false);
    readonly expanded = signal<string | null>(null);
    /** The Space default of `propagatedRisk`'s weights (`graph_run.propagated_risk_weights`), else null. */
    private readonly spaceWeights = computed(() => this.settings.limits()?.propagatedRiskWeightsInForce ?? null);
    readonly nodeChoices = computed<PickerOption[]>(() =>
        this.nodeOptions().map((n) => ({ value: n.id, label: n.label })),
    );

    /** Edited parameter values (canvas node picks and tunables), keyed by name; reset when the algorithm changes. */
    private readonly edited = signal<Record<string, unknown>>({});
    readonly nodes = signal<Record<string, string>>({});
    readonly summary = signal<GraphRunSummary | null>(null);
    readonly riskRows = signal<PropagatedRiskRow[]>([]);

    /** The request parameters: every edited tunable, plus the picked nodes translated to the server's ids. */
    readonly params = computed<Record<string, unknown>>(() => {
        const out: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(this.edited())) {
            if (!k.startsWith('__ids_')) {
                out[k] = v;
                continue;
            }
            const sids = (v as string[]).map((c) => this.serverIds()?.serverNode(c)).filter((x): x is string => !!x);
            if (sids.length) out[k.slice('__ids_'.length)] = sids;
        }
        for (const [k, canvasId] of Object.entries(this.nodes())) {
            const sid = canvasId && this.serverIds()?.serverNode(canvasId);
            if (sid) out[k] = sid;
        }
        return out;
    });
    readonly hold = computed(() => {
        const a = this.current();
        if (!a) return '';
        const apiOnly = apiOnlyParams(a);
        if (apiOnly.length)
            return `API only: this panel cannot edit ${apiOnly.join(', ')} yet - run it through POST /inv/graph/runs.`;
        const missing = [a.needsSource && 'from', a.needsTarget && 'to', a.needsNode && 'node'].filter(
            (k): k is string => !!k && !(k in this.params()),
        );
        if (missing.length) return `Pick ${missing.join(' and ')} first.`;
        const badList = Object.keys(this.listErrors())[0];
        if (badList) return `Correct ${badList} first.`;
        // an empty score map scores every node 0: an answer that says nothing
        const emptyMap = a.params.find(
            (p) => p.type === 'SCORE_MAP' && !Object.keys((this.params()[p.name] as object) ?? {}).length,
        );
        return emptyMap ? `Fill ${emptyMap.name} first.` : '';
    });

    constructor() {
        this.runs.loadCatalogue();
    }

    pick(id: string): void {
        this.picked.set(id);
        this.edited.set({});
        this.nodes.set({});
        this.summary.set(null);
        this.riskRows.set([]);
        this.listErrors.set({});
        this.scoreNote.set({});
        this.expanded.set(null);
    }

    /** A number list's text: what was typed, else the Space default (`propagatedRisk` weights), else the catalogue's. */
    listText(p: GraphAlgorithmParam): string {
        const v = this.edited()[p.name] ?? (p.name === 'weights' && this.spaceWeights()) ?? p.default;
        return Array.isArray(v) ? v.join(', ') : '';
    }

    setList(name: string, text: string): void {
        const r = parseWeights(text);
        const { [name]: _drop, ...rest } = this.listErrors();
        if ('error' in r) {
            this.listErrors.set({ ...rest, [name]: r.error });
            return;
        }
        this.listErrors.set(rest);
        this.edited.update((e) => ({ ...e, [name]: r.weights }));
    }

    /** Canvas ids of an ID_LIST parameter (sent as server ids, see {@link params}). */
    idList(name: string): string[] {
        return (this.edited()['__ids_' + name] as string[] | undefined) ?? [];
    }

    addId(name: string, canvasId: string): void {
        if (!canvasId || this.idList(name).includes(canvasId)) return;
        this.edited.update((e) => ({ ...e, ['__ids_' + name]: [...this.idList(name), canvasId] }));
    }

    removeId(name: string, canvasId: string): void {
        this.edited.update((e) => ({ ...e, ['__ids_' + name]: this.idList(name).filter((i) => i !== canvasId) }));
    }

    labelOfCanvas(canvasId: string): string {
        return this.nodeOptions().find((n) => n.id === canvasId)?.label ?? canvasId;
    }

    /** Fill a SCORE_MAP from the profile's indicators Dataset: every drawn node with a positive score. */
    async fillScores(p: GraphAlgorithmParam): Promise<void> {
        const m = this.riskMapping();
        const ids = this.serverIds();
        if (!m) return;
        // /db/query applies no entity masking: under any mode but `none` (or an unknown one) no reference row is read
        if (this.settings.limits()?.maskingModeInForce !== 'none') {
            this.scoreNote.update((n) => ({
                ...n,
                [p.name]: 'Scores are unavailable while entity masking is on.',
            }));
            return;
        }
        this.filling.set(true);
        try {
            const table = await this.risk.table(m.indicators);
            if (table.error) {
                this.scoreNote.update((n) => ({ ...n, [p.name]: `The indicators could not be read: ${table.error}` }));
                return;
            }
            const serverIds = this.nodeOptions()
                .map((n) => ids?.serverNode(n.id))
                .filter((x): x is string => !!x);
            const r = nodeScoresFor(serverIds, table.byKey, m.indicators.scoreCol, p.maxSize);
            this.setNodeScores(p.name, r.scores);
            this.scoreNote.update((n) => ({
                ...n,
                [p.name]:
                    `${r.matched} of ${serverIds.length} nodes have a score in ${m.indicators.dataset}` +
                    (r.dropped ? ` (${r.dropped} left out at the ${p.maxSize}-entry cap).` : '.') +
                    (table.truncated ? ' The Dataset was only partly read: some scores may be missing.' : ''),
            }));
        } finally {
            this.filling.set(false);
        }
    }

    setNodeScores(name: string, scores: Record<string, number>): void {
        this.edited.update((e) => ({ ...e, [name]: scores }));
    }

    toggleFactors(id: string): void {
        this.expanded.update((x) => (x === id ? null : id));
    }

    valueOf(name: string, dflt: unknown): unknown {
        return name in this.edited() ? this.edited()[name] : dflt;
    }

    setParam(name: string, raw: string, numeric = false): void {
        const n = Number(raw);
        this.edited.update((e) => ({ ...e, [name]: numeric ? (Number.isFinite(n) ? n : undefined) : raw }));
    }

    setNode(key: 'from' | 'to' | 'node', canvasId: string): void {
        this.nodes.update((n) => ({ ...n, [key]: canvasId }));
    }

    onCompleted(r: GraphRunResult): void {
        this.summary.set(summarizeGraphRun(r, this.serverIds()));
        this.riskRows.set(propagatedRiskRows(r, this.serverIds(), (id) => this.labelOfCanvas(id)));
        this.expanded.set(null);
        this.resultChange.emit(r);
    }

    highlight(s: GraphRunSummary): void {
        this.emphasisChange.emit({ nodeIds: s.nodeIds, edgeIds: s.edgeIds });
    }
}
