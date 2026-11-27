import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    booleanAttribute,
    computed,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ToastrService } from 'ngx-toastr';
import { Subscription } from 'rxjs';
import {
    BiQueryBody,
    BiQueryResult,
    BiQueryService,
    ComponentsService,
    SessionService,
    apiErrorMessage,
} from 'app/inspecto/api';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiDraft } from 'app/inspecto/ai-assist/ai-draft';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { ControlValues } from 'app/inspecto/viz';
import { formatNumber } from 'app/inspecto/viz/number-format';
import { Dataset } from '../datasets/dataset-types';
import { WidgetOptions, buildWidget } from '../widgets/widget-types';
import { WidgetsService } from '../widgets/widgets.service';

/** How many result rows the preview draws; the engine's own cap (and `truncated`) still applies to the query. */
const PREVIEW_ROWS = 8;

interface PreviewState {
    state: 'loading' | 'ready' | 'error';
    columns: string[];
    rows: Record<string, unknown>[];
    truncated: boolean;
    error: string;
}

/** A Widget id from the caption: lower-case words joined by underscores, at most 60 characters. */
export function widgetIdFromCaption(caption: string): string {
    const slug = caption
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '_')
        .replace(/^_+|_+$/g, '')
        .slice(0, 60)
        .replace(/_+$/g, '');
    return slug || 'widget';
}

/**
 * **Ask the Dashboard** (BI-INSIGHTS-1 P4, AQ-1; plan `2026-11-27-bi-insights-p4-language-plan.md` §6). One question in
 * words becomes a DRAFT Widget: `<inspecto-ai-assist tool="widget_from_question" prompting>` derives the closed-vocabulary
 * arguments, the tool validates them and returns a draft, a templated one-line caption and the `/bi/query` body.
 *
 * - **No model-written answer.** What is shown is the caption naming the spec in words, and the rows the ENGINE returns:
 *   the preview runs that body through `POST /bi/query` AS THE CALLER (this session's own request), so masking, caps and
 *   Dataset visibility apply exactly as on any Widget. The preview never reads the model's text for a number.
 * - **Save is the gated write.** It posts the Widget through `POST /components/widget`, the route every Widget save takes,
 *   so Draft / version history and a Space's maker-checker policy hold: under a Pending Change policy the answer is a
 *   202 and nothing is written until it is approved — said in words, never shown as saved. Ask never writes by itself.
 * - **Absent module → absent box.** Shown only while `/bootstrap` reports the `intelligence` feature.
 */
@Component({
    selector: 'app-ask-dashboard',
    standalone: true,
    imports: [AiAssistComponent, FormsModule, InspectoAlertComponent, PenFieldComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (enabled()) {
            <inspecto-ai-assist
                tool="widget_from_question"
                prompting
                inlineRefusal
                hideDiff
                label="Ask a question"
                promptHint="total revenue by region, last 8 weeks"
                applyLabel="Save as a Widget"
                [args]="args()"
                [disabled]="disabled()"
                [disabledReason]="disabledReason()"
                [applyDisabled]="nameError() !== ''"
                (drafted)="onDrafted($event)"
                (applyDraft)="save($event)"
            >
                <div aiDraftPreview class="mt-3" data-testid="ask-preview">
                    @if (draft(); as d) {
                        <p class="text-sm" data-testid="ask-caption">{{ d.label }}</p>

                        @if (unsavedParts().length > 0) {
                            <inspecto-alert variant="warning" class="mt-2 block" data-testid="ask-not-saved">
                                The preview applies the {{ unsavedParts().join(' and ') }}, but a Widget does not carry
                                {{ unsavedParts().length === 1 ? 'one' : 'them' }}. Set it with the Dashboard filter or
                                date range after you save.
                            </inspecto-alert>
                        }

                        @if (preview(); as p) {
                            @switch (p.state) {
                                @case ('loading') {
                                    <p class="text-secondary mt-2 text-sm" role="status">Running the preview…</p>
                                }
                                @case ('error') {
                                    <inspecto-alert variant="error" class="mt-2 block">{{ p.error }}</inspecto-alert>
                                }
                                @default {
                                    @if (p.rows.length === 0) {
                                        <p class="text-secondary mt-2 text-sm" data-testid="ask-empty">
                                            No rows match. A filter value is used exactly as written, so check its
                                            spelling.
                                        </p>
                                    } @else {
                                        <table class="mt-2 w-full text-left text-sm" data-testid="ask-rows">
                                            <caption class="sr-only">
                                                Preview of the drafted Widget, run as you
                                            </caption>
                                            <thead>
                                                <tr class="border-b border-gray-200 dark:border-gray-700">
                                                    @for (c of p.columns; track c) {
                                                        <th scope="col" class="py-1 pr-3 font-medium">{{ c }}</th>
                                                    }
                                                </tr>
                                            </thead>
                                            <tbody>
                                                @for (row of p.rows; track $index) {
                                                    <tr
                                                        class="border-b border-gray-100 last:border-0 dark:border-gray-800"
                                                    >
                                                        @for (c of p.columns; track c) {
                                                            <td class="py-1 pr-3 tabular-nums">{{ cell(row[c]) }}</td>
                                                        }
                                                    </tr>
                                                }
                                            </tbody>
                                        </table>
                                        @if (p.truncated) {
                                            <p class="text-secondary mt-1 text-xs">
                                                Showing the first rows — the result was cut.
                                            </p>
                                        }
                                    }
                                }
                            }
                        }

                        <inspecto-pen-field
                            class="mt-3 block"
                            label="Widget name"
                            [required]="true"
                            [error]="nameError()"
                            [ngModel]="name()"
                            (ngModelChange)="name.set($event)"
                            [ngModelOptions]="{ standalone: true }"
                        />
                    }
                </div>
            </inspecto-ai-assist>
        }
    `,
})
export class AskDashboardComponent {
    private bi = inject(BiQueryService);
    private components = inject(ComponentsService);
    private widgets = inject(WidgetsService);
    private toastr = inject(ToastrService);
    private session = inject(SessionService);
    private running: Subscription | null = null;

    /** The Dashboard's own Datasets come first in the question's Dataset choice (the tool's `preferred`). */
    readonly datasets = input<Dataset[]>([]);
    /** Widget ids already taken, so a name is unique before the write is tried. */
    readonly existingIds = input<string[]>([]);
    readonly disabled = input(false, { transform: booleanAttribute });
    readonly disabledReason = input('');
    /** A Widget was written (`pending: false`) or sent for approval (`pending: true`). */
    readonly saved = output<{ id: string; pending: boolean }>();

    /** Hidden entirely while `/bootstrap` does not report the `intelligence` feature. */
    readonly enabled = computed(() => this.session.features()['intelligence'] === true);
    readonly args = computed(() => ({ preferred: this.datasets().map((d) => d.id) }));

    readonly draft = signal<AiDraft | null>(null);
    readonly preview = signal<PreviewState | null>(null);
    readonly name = signal('');

    /** The parts of the question a Widget cannot carry (they live on the Dashboard), named so the reader is not surprised. */
    readonly unsavedParts = computed(() => {
        const q = this.draft()?.previewQuery;
        const parts: string[] = [];
        if (Array.isArray(q?.['filters']) && q['filters'].length) parts.push('filter');
        if (q?.['window']) parts.push('time window');
        return parts;
    });

    readonly nameError = computed(() => {
        const n = this.name().trim();
        if (n === '') return 'A name is required.';
        if (this.existingIds().includes(n)) return 'A Widget with this name already exists.';
        return '';
    });

    constructor() {
        inject(DestroyRef).onDestroy(() => this.running?.unsubscribe());
    }

    onDrafted(draft: AiDraft | null): void {
        this.running?.unsubscribe();
        this.draft.set(draft);
        this.preview.set(null);
        if (!draft) return;
        this.name.set(this.uniqueName(widgetIdFromCaption(draft.label)));
        const query = draft.previewQuery as BiQueryBody | undefined;
        if (!query) return;
        this.preview.set({ state: 'loading', columns: [], rows: [], truncated: false, error: '' });
        this.running = this.bi.run(query).subscribe({
            next: (r: BiQueryResult) =>
                this.preview.set({
                    state: 'ready',
                    columns: r.resultSet.columns.map((c) => c.name),
                    rows: r.rows.slice(0, PREVIEW_ROWS),
                    truncated: r.statistics.truncated || r.rows.length > PREVIEW_ROWS,
                    error: '',
                }),
            error: (e: unknown) =>
                this.preview.set({
                    state: 'error',
                    columns: [],
                    rows: [],
                    truncated: false,
                    error: `The preview could not run: ${apiErrorMessage(e, 'the query was refused')}`,
                }),
        });
    }

    cell(v: unknown): string {
        if (v == null) return '—';
        return typeof v === 'number' ? formatNumber(v) : String(v);
    }

    /** The gated write: `POST /components/widget`, which a Space's approval policy may hold as a Pending Change (202). */
    save(draft: AiDraft): void {
        const id = this.name().trim();
        if (this.nameError() !== '') return;
        const c = draft.config;
        const widget = buildWidget(
            id,
            String(c['datasetId'] ?? ''),
            String(c['vizType'] ?? 'bar'),
            (c['controls'] ?? {}) as ControlValues,
            { options: c['options'] as WidgetOptions | undefined },
        );
        this.components.create('widget', { id, ...this.widgets.toContent(widget) }).subscribe({
            next: (res) => {
                const pending = (res as unknown as { status?: string })?.status === 'pending';
                this.toastr[pending ? 'info' : 'success'](
                    pending
                        ? `"${id}" was sent for approval as a Pending Change. Nothing is saved until it is approved.`
                        : `Saved the Widget "${id}".`,
                );
                this.saved.emit({ id, pending });
            },
            error: (e: unknown) => this.toastr.error(apiErrorMessage(e, 'The Widget could not be saved')),
        });
    }

    private uniqueName(base: string): string {
        const taken = new Set(this.existingIds());
        if (!taken.has(base)) return base;
        for (let i = 2; ; i++) {
            const candidate = `${base.slice(0, 56)}_${i}`;
            if (!taken.has(candidate)) return candidate;
        }
    }
}
