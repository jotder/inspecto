import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { DataTableComponent } from 'app/inspecto/data-table';
import { RelationsPreview } from 'app/inspecto/api';
import { ChipComponent } from './chip.component';

/**
 * The result of testing a Step against sample rows: for every produced relation its **derived output
 * schema** and its rows, plus the SQL the config compiled to.
 *
 * <p>The point is that the author never restates the schema — DuckDB derives it (`DESCRIBE` over the
 * produced relation) and this renders it. Before this component the drawer showed two count lines and
 * discarded the rows and types the route had already computed.
 *
 * <p>⚠ **The schema is sample-derived, and the copy says so.** It is production-faithful for the
 * delimited path (the scratch table is seeded all-VARCHAR, the shape `read_csv` produces), but not for
 * typed plugin ingesters — so it is presented as what this sample produced, never as a contract.
 *
 * <p>⚠ **Offline there is no SQL engine**, so `columnTypes`/`sql` come back empty and this says so
 * explicitly rather than rendering an empty schema that reads as "no columns".
 */
@Component({
    selector: 'inspecto-step-preview-result',
    standalone: true,
    imports: [DataTableComponent, ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="mt-2 flex flex-col gap-4" role="status" aria-live="polite">
            <p class="text-secondary m-0 text-sm">
                {{ inputSummary() }}
            </p>

            @for (rel of result().relations; track rel.rel) {
                <section class="flex flex-col gap-2">
                    <h4 class="m-0 flex items-center gap-2 text-sm font-semibold">
                        <span class="font-mono">{{ rel.rel }}</span>
                        <span class="text-secondary font-normal">{{ rel.rowCount }} row(s)</span>
                    </h4>

                    @if (rel.columnTypes?.length) {
                        <div class="flex flex-wrap items-center gap-1">
                            <span class="text-secondary mr-1 text-xs uppercase">Derived schema</span>
                            @for (col of rel.columnTypes; track col.name) {
                                <inspecto-chip variant="soft">
                                    <span class="font-mono">{{ col.name }}</span>
                                    <span class="text-secondary ml-1 font-mono">{{ col.type }}</span>
                                </inspecto-chip>
                            }
                        </div>
                    } @else {
                        <p class="text-secondary m-0 text-xs">{{ unavailable }}</p>
                    }

                    @if (rel.rows.length) {
                        <inspecto-data-table tier="mini" [rows]="rel.rows" />
                    }
                </section>
            }

            @if (result().sql?.length) {
                <section class="flex flex-col gap-1">
                    <h4 class="m-0 text-sm font-semibold">SQL this Step ran</h4>
                    <pre
                        class="bg-hover m-0 overflow-x-auto rounded p-2 font-mono text-xs"
                        tabindex="0"
                        aria-label="The SQL this Step compiled to"
                        >{{ sqlText() }}</pre
                    >
                </section>
            }
        </div>
    `,
})
export class StepPreviewResultComponent {
    readonly result = input.required<RelationsPreview>();

    /** Why a derived schema is missing. One sentence, because the operator's next question is "why". */
    readonly unavailable =
        'Derived offline — the schema and SQL need the query engine, so they are not available here.';

    readonly inputSummary = computed(() => {
        const n = this.result().inputColumns.length;
        return `in: ${n} column(s) — ${this.result().inputColumns.join(', ')}`;
    });

    readonly sqlText = computed(() => (this.result().sql ?? []).join(';\n\n') + ';');
}
