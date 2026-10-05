import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { TemporalSeries } from '@inspecto/link-analysis/api/inv.service';
import { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';
import { PlacedTemporalFinding, TemporalFindingsState, allTemporalSelection } from './temporal-findings';

/** What the analyst asked for; the host turns it into `POST /inv/pattern/temporal`. Numbers are already in range. */
export interface TemporalRunRequest {
    mapping: number;
    mode: 'burst' | 'periodicity';
    series: TemporalSeries;
    windowSeconds: number;
    minEvents: number;
    maxCv: number;
}

const clamp = (v: number, lo: number, hi: number, fallback: number): number =>
    Number.isFinite(v) ? Math.min(hi, Math.max(lo, Math.round(v * 1000) / 1000)) : fallback;

/**
 * **Burst and periodicity findings** (LA-INVESTIGATION-OPS-DEFERRED-1, SPA slice). Runs the stateless
 * `POST /inv/pattern/temporal` read over ONE edge mapping's whole Dataset and lists each finding — a link whose events
 * cluster (burst) or repeat at a steady interval (periodicity). Picking a finding highlights that link on the canvas;
 * nothing is added to the graph. By default a series is ONE directed source→target link AS THE DATASET SPELLS IT;
 * "Each entity" instead pools every event an entity takes part in, as source or as target. The Dataset's own times are shown with no zone claimed — the panel says so rather than implying UTC.
 */
@Component({
    selector: 'inspecto-link-analysis-temporal-findings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, InspectoAlertComponent, InspectoOptionPickerComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2" aria-labelledby="la-temporal-heading" data-test="temporal">
            <h3 id="la-temporal-heading" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Burst and periodicity
            </h3>
            @if (!timeColumn()) {
                <p class="text-secondary m-0" data-test="temporal-no-time">
                    Pick a time column in the query panel to look for bursts and regular intervals.
                </p>
            } @else if (!mappings().length) {
                <p class="text-secondary m-0" data-test="temporal-no-mapping">
                    This graph has no Dataset edge mapping to scan. Run an entity projection first.
                </p>
            } @else {
                <p class="text-secondary m-0">
                    Looks at event times over the whole Dataset. A link is a source → target pair as the Dataset spells
                    it; an entity pools every event it takes part in, as source or as target.
                </p>
                <div class="flex flex-wrap items-end gap-2">
                    @if (mappings().length > 1) {
                        <inspecto-option-picker
                            class="w-56"
                            label="Edge mapping"
                            [options]="mappings()"
                            [ngModel]="mapping()"
                            (ngModelChange)="mapping.set($any($event))"
                        />
                    }
                    <inspecto-option-picker
                        class="w-40"
                        label="Series per"
                        [options]="seriesOptions"
                        [ngModel]="series()"
                        (ngModelChange)="series.set($any($event))"
                    />
                    <inspecto-option-picker
                        class="w-40"
                        label="Look for"
                        [options]="modeOptions"
                        [ngModel]="mode()"
                        (ngModelChange)="mode.set($any($event))"
                    />
                    @if (mode() === 'burst') {
                        <label class="flex flex-col text-xs">
                            Window (seconds)
                            <input
                                class="w-24 rounded border px-2 py-1"
                                type="number"
                                min="1"
                                max="86400"
                                data-test="temporal-window"
                                [value]="windowSeconds()"
                                (input)="windowSeconds.set(num($event))"
                            />
                        </label>
                    } @else {
                        <label class="flex flex-col text-xs">
                            Max irregularity (0 to 1)
                            <input
                                class="w-24 rounded border px-2 py-1"
                                type="number"
                                min="0"
                                max="1"
                                step="0.05"
                                data-test="temporal-maxcv"
                                [value]="maxCv()"
                                (input)="maxCv.set(num($event))"
                            />
                        </label>
                    }
                    <label class="flex flex-col text-xs">
                        Min events
                        <input
                            class="w-20 rounded border px-2 py-1"
                            type="number"
                            [min]="mode() === 'burst' ? 2 : 3"
                            max="1000"
                            data-test="temporal-min"
                            [value]="minEvents()"
                            (input)="minEvents.set(num($event))"
                        />
                    </label>
                    <button
                        mat-stroked-button
                        type="button"
                        data-test="temporal-run"
                        [disabled]="busy()"
                        (click)="run()"
                    >
                        {{ busy() ? 'Scanning…' : 'Find on the whole Dataset' }}
                    </button>
                </div>
            }
            @if (error()) {
                <inspecto-alert variant="error" title="Burst and periodicity">{{ error() }}</inspecto-alert>
            }
            @if (state(); as s) {
                <div role="status" class="flex flex-col gap-1" data-test="temporal-summary">
                    <p class="m-0 text-xs">
                        {{ s.findings.length }}
                        {{
                            s.mode === 'burst'
                                ? 'burst(s)'
                                : s.series === 'entity'
                                  ? 'regular entity(ies)'
                                  : 'regular link(s)'
                        }}
                        found
                        @if (s.offCanvas) {
                            · {{ s.offCanvas }} not drawn on this canvas
                        }
                        @if (s.skippedNoTime) {
                            · {{ s.skippedNoTime }} row(s) skipped (no readable time)
                        }
                    </p>
                    @if (s.truncated) {
                        <p class="text-warn m-0 text-xs" data-test="temporal-truncated">
                            {{
                                s.rowCapped
                                    ? 'Only part of the Dataset was scanned (row limit), so more may exist.'
                                    : 'The list is cut at its limit — the strongest findings are shown first.'
                            }}
                        </p>
                    }
                    <p class="text-secondary m-0 text-xs" data-test="temporal-note">{{ s.timeNote }}</p>
                </div>
                <div class="flex flex-col gap-1" data-test="temporal-seal">
                    <div>
                        <button
                            mat-stroked-button
                            type="button"
                            data-test="temporal-seal-button"
                            [disabled]="!canSeal() || sealBusy()"
                            (click)="sealRequested.emit()"
                        >
                            {{ sealBusy() ? 'Sealing…' : 'Seal these findings' }}
                        </button>
                    </div>
                    <p class="text-secondary m-0 text-xs" data-test="temporal-seal-note">
                        @if (canSeal()) {
                            Records a step in the open Investigation's log: the scan is re-run over its own edge
                            columns, kept to the links or entities of its Working Set, and sealed with a fingerprint.
                            The Working Set does not change.
                        } @else {
                            Open an Investigation to seal these findings into its log.
                        }
                    </p>
                    @if (sealed(); as z) {
                        <p role="status" class="m-0 text-xs" data-test="temporal-sealed">
                            Sealed at step {{ z.step }}: {{ z.count }} finding(s) on the Working Set
                            @if (z.outsideWorkingSet) {
                                ({{ z.outsideWorkingSet }} outside it, not kept)
                            }
                            · fingerprint {{ z.fingerprint.slice(0, 12) }}
                        </p>
                    }
                    @if (sealError()) {
                        <inspecto-alert variant="error" title="Seal these findings">{{ sealError() }}</inspecto-alert>
                    }
                </div>
                @if (s.findings.length) {
                    <div>
                        <button
                            mat-stroked-button
                            type="button"
                            data-test="temporal-all"
                            [disabled]="!hasDrawn()"
                            (click)="highlight.emit(all())"
                        >
                            Highlight all on canvas
                        </button>
                    </div>
                    <ul class="m-0 flex list-none flex-col gap-1 p-0" aria-label="Findings">
                        @for (p of s.findings; track $index) {
                            <li>
                                <button
                                    type="button"
                                    class="w-full rounded border px-2 py-1 text-left text-xs"
                                    data-test="temporal-finding"
                                    [disabled]="!p.onCanvas"
                                    [attr.aria-label]="describe(p)"
                                    (click)="highlight.emit(p.selection)"
                                >
                                    <span class="font-medium">{{ subject(p) }}</span>
                                    <span class="text-secondary block">
                                        {{ detail(p) }}{{ p.onCanvas ? '' : ' — not drawn on this canvas' }}
                                    </span>
                                </button>
                            </li>
                        }
                    </ul>
                } @else {
                    <p class="text-secondary m-0 text-xs" data-test="temporal-none">
                        Nothing matched. Try a wider window or fewer minimum events.
                    </p>
                }
            }
        </section>
    `,
})
export class LinkAnalysisTemporalFindingsComponent {
    /** The edge mappings a scan can read (the host's `traversalMappingOptions`). */
    readonly mappings = input<PickerOption[]>([]);
    /** The query's time column; the route needs one. */
    readonly timeColumn = input('');
    readonly state = input<TemporalFindingsState | null>(null);
    readonly busy = input(false);
    readonly error = input('');
    /** An Investigation is open, so the findings can be sealed into its log. */
    readonly canSeal = input(false);
    readonly sealBusy = input(false);
    /** What the last seal answered (counts and fingerprint), cleared by a new scan. */
    readonly sealed = input<{ step: number; count: number; outsideWorkingSet: number; fingerprint: string } | null>(
        null,
    );
    readonly sealError = input('');

    readonly runRequested = output<TemporalRunRequest>();
    /** Seal the findings just scanned into the open Investigation's log (a `temporal` op). */
    readonly sealRequested = output<void>();
    /** Highlight on the canvas (a finding's, or every drawn finding's, nodes and links). */
    readonly highlight = output<GraphSelection>();

    readonly modeOptions: PickerOption[] = [
        { value: 'burst', label: 'Bursts' },
        { value: 'periodicity', label: 'Regular intervals' },
    ];
    readonly seriesOptions: PickerOption[] = [
        { value: 'link', label: 'Each link' },
        { value: 'entity', label: 'Each entity' },
    ];
    readonly series = signal<TemporalSeries>('link');
    readonly mapping = signal('0');
    readonly mode = signal<'burst' | 'periodicity'>('burst');
    readonly windowSeconds = signal(60);
    readonly minEvents = signal(5);
    readonly maxCv = signal(0.1);

    readonly hasDrawn = computed(() => !!this.state()?.findings.some((f) => f.onCanvas));

    num(e: Event): number {
        return (e.target as HTMLInputElement).valueAsNumber;
    }

    all(): GraphSelection {
        const s = this.state();
        return s ? allTemporalSelection(s) : { nodeIds: [], edgeIds: [] };
    }

    run(): void {
        const idx = Number(this.mapping());
        const burst = this.mode() === 'burst';
        this.runRequested.emit({
            mapping: idx >= 0 && idx < this.mappings().length ? idx : 0,
            mode: this.mode(),
            series: this.series(),
            windowSeconds: clamp(this.windowSeconds(), 1, 86_400, 60),
            minEvents: Math.round(clamp(this.minEvents(), burst ? 2 : 3, 1_000, 5)),
            maxCv: clamp(this.maxCv(), 0, 1, 0.1),
        });
    }

    detail(p: PlacedTemporalFinding): string {
        const f = p.finding;
        return f.periodSeconds !== undefined
            ? `${f.events} events about every ${f.periodSeconds} s (irregularity ${f.cv?.toFixed(3)}), ${f.first} to ${f.last}`
            : `${f.events} events from ${f.start} to ${f.end}`;
    }

    subject(p: PlacedTemporalFinding): string {
        return p.finding.entity !== undefined ? p.finding.entity : `${p.finding.source} → ${p.finding.target}`;
    }

    describe(p: PlacedTemporalFinding): string {
        return `${p.finding.entity !== undefined ? p.finding.entity : `${p.finding.source} to ${p.finding.target}`}: ${this.detail(p)}`;
    }
}
