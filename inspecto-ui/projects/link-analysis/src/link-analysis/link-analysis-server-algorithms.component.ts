import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { GraphEmphasis } from '@inspecto/core/graph/graph-view.component';
import { GraphAlgorithm, GraphRunResult, GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import { ServerIdMap } from './graph-run-apply';
import { GraphRunSummary, summarizeGraphRun } from './graph-run-summary';
import { LinkAnalysisServerRunComponent } from './link-analysis-server-run.component';

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
                    @for (p of a.params; track p.name) {
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
                        @if (s.lines.length) {
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

    readonly investigationId = input<string | null>(null);
    readonly investigationOpen = input(false);
    readonly allowed = input(true);
    readonly nodeOptions = input<{ id: string; label: string }[]>([]);
    readonly serverIds = input<ServerIdMap | null>(null);
    readonly emphasisChange = output<GraphEmphasis | null>();

    readonly algorithms = computed<GraphAlgorithm[]>(() => this.runs.catalogue()?.algorithms ?? []);
    readonly algorithmOptions = computed<PickerOption[]>(() =>
        this.algorithms().map((a) => ({ value: a.id, label: a.label })),
    );
    readonly picked = signal('');
    readonly current = computed(() => {
        const all = this.algorithms();
        return all.find((a) => a.id === this.picked()) ?? all[0] ?? null;
    });
    readonly nodeChoices = computed<PickerOption[]>(() =>
        this.nodeOptions().map((n) => ({ value: n.id, label: n.label })),
    );

    /** Edited parameter values (canvas node picks and tunables), keyed by name; reset when the algorithm changes. */
    private readonly edited = signal<Record<string, unknown>>({});
    readonly nodes = signal<Record<string, string>>({});
    readonly summary = signal<GraphRunSummary | null>(null);

    /** The request parameters: every edited tunable, plus the picked nodes translated to the server's ids. */
    readonly params = computed<Record<string, unknown>>(() => {
        const out: Record<string, unknown> = { ...this.edited() };
        for (const [k, canvasId] of Object.entries(this.nodes())) {
            const sid = canvasId && this.serverIds()?.serverNode(canvasId);
            if (sid) out[k] = sid;
        }
        return out;
    });
    readonly hold = computed(() => {
        const a = this.current();
        if (!a) return '';
        const missing = [a.needsSource && 'from', a.needsTarget && 'to', a.needsNode && 'node'].filter(
            (k): k is string => !!k && !(k in this.params()),
        );
        return missing.length ? `Pick ${missing.join(' and ')} first.` : '';
    });

    constructor() {
        this.runs.loadCatalogue();
    }

    pick(id: string): void {
        this.picked.set(id);
        this.edited.set({});
        this.nodes.set({});
        this.summary.set(null);
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
    }

    highlight(s: GraphRunSummary): void {
        this.emphasisChange.emit({ nodeIds: s.nodeIds, edgeIds: s.edgeIds });
    }
}
