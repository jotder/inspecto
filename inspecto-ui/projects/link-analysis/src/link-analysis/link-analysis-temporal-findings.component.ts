import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { TemporalSeries } from '@inspecto/link-analysis/api/inv.service';
import { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';
import { PlacedTemporalFinding, TemporalFindingsState, allTemporalSelection } from './temporal-findings';
import { DISABLED_REASON, NEEDS_TIME_COLUMN } from './disabled-reasons';

/** "1 burst" / "3 bursts": a real plural, never "burst(s)". */
const plural = (n: number, one: string, many: string): string => `${n.toLocaleString('en')} ${n === 1 ? one : many}`;

/** A period in the largest whole unit ("every hour", "every 90 seconds"). */
function every(seconds: number): string {
    const units: [number, string][] = [
        [86_400, 'day'],
        [3_600, 'hour'],
        [60, 'minute'],
    ];
    for (const [size, unit] of units) {
        if (seconds >= size && seconds % size === 0) {
            const n = seconds / size;
            return n === 1 ? `every ${unit}` : `every ${n} ${unit}s`;
        }
    }
    return seconds === 1 ? 'every second' : `every ${seconds} seconds`;
}

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
    imports: [FormsModule, MatButtonModule, MatTooltipModule, InspectoAlertComponent, InspectoOptionPickerComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2" aria-labelledby="la-temporal-heading" data-test="temporal">
            <h3 id="la-temporal-heading" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Burst and periodicity
            </h3>
            @if (!timeColumn()) {
                <p class="text-secondary m-0" data-test="temporal-no-time">
                    {{ needsTimeColumn }}
                </p>
            } @else if (!mappings().length) {
                <p class="text-secondary m-0" data-test="temporal-no-mapping">
                    Draw a graph from an Entity/Link query first.
                </p>
            } @else {
                <p class="text-secondary m-0">
                    Scans event times across the whole Dataset. Each link, or each entity (all its events together), is
                    checked.
                </p>
                <div class="flex flex-wrap items-end gap-2">
                    @if (mappings().length > 1) {
                        <inspecto-option-picker
                            class="w-56"
                            label="Link Dataset"
                            [options]="mappings()"
                            [ngModel]="mapping()"
                            (ngModelChange)="mapping.set($any($event))"
                        />
                    }
                    <inspecto-option-picker
                        class="w-40"
                        label="Check each"
                        [options]="seriesOptions"
                        [ngModel]="series()"
                        (ngModelChange)="series.set($any($event))"
                    />
                    <inspecto-option-picker
                        class="w-40"
                        label="Find"
                        [options]="modeOptions"
                        [ngModel]="mode()"
                        (ngModelChange)="mode.set($any($event))"
                    />
                    @if (mode() === 'burst') {
                        <label class="flex flex-col text-xs">
                            Burst window (seconds)
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
                            Largest irregularity (0 to 1)
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
                        Fewest events
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
                        {{ busy() ? 'Scanning…' : 'Scan Dataset' }}
                    </button>
                </div>
            }
            @if (error()) {
                <inspecto-alert variant="error" title="Scan failed">{{ error() }}</inspecto-alert>
            }
            @if (state(); as s) {
                <div role="status" class="flex flex-col gap-1" data-test="temporal-summary">
                    <p class="m-0 text-xs">{{ summary(s) }}</p>
                    @if (s.truncated) {
                        <p class="text-warn m-0 text-xs" data-test="temporal-truncated">
                            {{
                                s.rowCapped
                                    ? 'Only part of the Dataset was scanned (row limit), so more may exist.'
                                    : 'Result cut short: the strongest findings are shown first.'
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
                            [disabledInteractive]="true"
                            [matTooltip]="canSeal() ? '' : disabledReason.openInvestigation"
                            aria-describedby="la-temporal-seal-note"
                            (click)="canSeal() && !sealBusy() && sealRequested.emit()"
                        >
                            {{ sealBusy() ? 'Sealing…' : 'Seal these findings' }}
                        </button>
                    </div>
                    <p class="text-secondary m-0 text-xs" id="la-temporal-seal-note" data-test="temporal-seal-note">
                        @if (canSeal()) {
                            Records these findings in the Investigation’s log. The Working Set does not change.
                        } @else {
                            {{ disabledReason.openInvestigation }}
                        }
                    </p>
                    @if (sealed(); as z) {
                        <p role="status" class="m-0 text-xs" data-test="temporal-sealed">
                            Sealed at step {{ z.step }}: {{ z.count }} {{ z.count === 1 ? 'finding' : 'findings' }} in
                            the Working Set{{ z.outsideWorkingSet ? ' (' + z.outsideWorkingSet + ' outside it were left out)' : '' }}.
                            Fingerprint {{ z.fingerprint.slice(0, 12) }}.
                        </p>
                    }
                    @if (sealError()) {
                        <inspecto-alert variant="error" title="Seal failed">{{ sealError() }}</inspecto-alert>
                    }
                </div>
                @if (s.findings.length) {
                    <div>
                        <button
                            mat-stroked-button
                            type="button"
                            data-test="temporal-all"
                            [disabled]="!hasDrawn()"
                            [disabledInteractive]="true"
                            [matTooltip]="hasDrawn() ? '' : noneOnCanvas"
                            [attr.aria-describedby]="hasDrawn() ? null : 'la-temporal-all-why'"
                            (click)="hasDrawn() && highlight.emit(all())"
                        >
                            Highlight all on canvas
                        </button>
                        @if (!hasDrawn()) {
                            <p class="text-secondary m-0 text-xs" id="la-temporal-all-why" data-test="temporal-all-why">
                                {{ noneOnCanvas }}
                            </p>
                        }
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
                                        {{ detail(p) }}{{ p.onCanvas ? '' : '. Not on the canvas' }}
                                    </span>
                                </button>
                            </li>
                        }
                    </ul>
                } @else {
                    <p class="text-secondary m-0 text-xs" data-test="temporal-none">
                        Nothing matched. Try {{ mode() === 'burst' ? 'a larger burst window' : 'a larger irregularity' }} or
                        fewer events.
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
    protected readonly needsTimeColumn = NEEDS_TIME_COLUMN;
    protected readonly disabledReason = DISABLED_REASON;
    protected readonly noneOnCanvas = 'None of these findings are on the canvas.';

    /** "3 bursts found. 1 not on the canvas. 2 rows skipped (no readable time)." */
    summary(s: TemporalFindingsState): string {
        const n = s.findings.length;
        const found =
            s.mode === 'burst'
                ? plural(n, 'burst', 'bursts')
                : s.series === 'entity'
                  ? plural(n, 'regular entity', 'regular entities')
                  : plural(n, 'regular link', 'regular links');
        return [
            `${found} found.`,
            ...(s.offCanvas ? [`${s.offCanvas.toLocaleString('en')} not on the canvas.`] : []),
            ...(s.skippedNoTime ? [`${plural(s.skippedNoTime, 'row', 'rows')} skipped (no readable time).`] : []),
        ].join(' ');
    }

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
            ? `${f.events} events about ${every(f.periodSeconds)} (irregularity ${f.cv?.toFixed(2)}), ${f.first} to ${f.last}`
            : `${f.events} events from ${f.start} to ${f.end}`;
    }

    subject(p: PlacedTemporalFinding): string {
        return p.finding.entity !== undefined ? p.finding.entity : `${p.finding.source} → ${p.finding.target}`;
    }

    describe(p: PlacedTemporalFinding): string {
        return `${p.finding.entity !== undefined ? p.finding.entity : `${p.finding.source} to ${p.finding.target}`}: ${this.detail(p)}`;
    }
}
