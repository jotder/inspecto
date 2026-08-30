import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ConfigService, DerivedSchemaResult, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';

/**
 * **The derived output schema, read-only, beside the authored one** (step-workbench S5).
 *
 * <p>The engine already knows what a pipeline writes — `TypeFlow` derives it by DuckDB `DESCRIBE` over
 * the transform's own SELECT, without executing anything. This panel shows that, so an author stops
 * restating a schema the engine can compute.
 *
 * <p>🔴 **It always states which source path it assumed.** On the CSV path every raw column is
 * `VARCHAR`; on the plugin path the declared field types stand — the same Schema derives different
 * types depending on which applies. The server reports `sourcePath`/`typedSource` precisely so the
 * answer is never mistaken for authoritative when it rests on an assumption, and hiding that here
 * would re-create the trap the route was built to close.
 *
 * <p>⚠ A 422 is the **most useful** thing this route says: the Schema/Mapping does not bind, and
 * DuckDB's binder error names the offending column. It is rendered as a warning the author can act
 * on, never swallowed into "nothing to show".
 *
 * <p>Read-only by construction — no write path, no form. The authored side stays the editor.
 */
@Component({
    selector: 'inspecto-derived-schema-panel',
    standalone: true,
    imports: [CommonModule, InspectoAlertComponent, InspectoEmptyStateComponent, InspectoSkeletonComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="flex flex-col gap-3" [attr.aria-busy]="loading()">
            <header class="flex flex-wrap items-baseline justify-between gap-2">
                <h3 class="text-base font-medium">Derived output schema</h3>
                @if (result(); as r) {
                    <!-- Never silently imply the types are unconditional: say which path produced them. -->
                    <p class="text-secondary text-xs">
                        {{
                            r.typedSource
                                ? 'Plugin path — declared field types'
                                : 'CSV path — raw columns read as VARCHAR'
                        }}
                    </p>
                }
            </header>

            @if (loading()) {
                <inspecto-skeleton [lines]="4" />
            } @else if (bindError()) {
                <!--
                    ⚠ One title for every 422, because the route refuses for more than one reason — a
                    Schema/Mapping that does not bind, AND a pipeline that declares no schema at all.
                    Titling both "does not compile" told an author to go fix a transform that may not
                    exist yet. The server's own message says which; the heading must not pre-empt it.
                -->
                <inspecto-alert variant="warning" title="The output schema could not be derived">
                    {{ bindError() }}
                </inspecto-alert>
            } @else if (loadError()) {
                <inspecto-alert variant="error" title="Could not derive the schema">
                    {{ loadError() }}
                </inspecto-alert>
            } @else if (result(); as r) {
                @if (r.schemas.length === 0) {
                    <inspecto-empty-state
                        icon="heroicons_outline:table-cells"
                        message="This pipeline declares no schema, so there is nothing to derive."
                    />
                } @else {
                    @for (s of r.schemas; track s.key) {
                        <div class="flex flex-col gap-1">
                            @if (r.schemas.length > 1) {
                                <p class="text-secondary text-xs font-medium">{{ s.table || s.key }}</p>
                            }
                            <table class="w-full text-sm">
                                <caption class="sr-only">
                                    Derived output columns for
                                    {{
                                        s.table || s.key
                                    }}
                                </caption>
                                <thead>
                                    <tr class="border-b">
                                        <th scope="col" class="py-1 text-left font-medium">Column</th>
                                        <th scope="col" class="py-1 text-left font-medium">Type</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    @for (c of s.columns; track c.name) {
                                        <tr class="border-b">
                                            <td class="py-1 font-mono text-xs">{{ c.name }}</td>
                                            <td class="text-secondary py-1 font-mono text-xs">{{ c.type }}</td>
                                        </tr>
                                    }
                                </tbody>
                            </table>
                        </div>
                    }
                    <!-- The footer half of the honesty rule: the written table is not the file footer. -->
                    <p class="text-secondary text-xs">
                        The written table's shape. Partition columns appear here but not in the Parquet file footer,
                        where Hive encodes them as directories.
                    </p>
                }
            }
        </section>
    `,
})
export class DerivedSchemaPanelComponent {
    private config = inject(ConfigService);

    /** The saved pipeline to derive for. Blank ⇒ nothing is fetched (a draft has nothing to derive from). */
    readonly pipeline = input<string>('');

    readonly loading = signal(false);
    readonly result = signal<DerivedSchemaResult | null>(null);
    /** A 422 — the author's config does not bind. Distinct from a transport failure on purpose. */
    readonly bindError = signal<string>('');
    readonly loadError = signal<string>('');

    readonly columnCount = computed(() => (this.result()?.schemas ?? []).reduce((n, s) => n + s.columns.length, 0));

    constructor() {
        effect(() => {
            const name = this.pipeline().trim();
            this.bindError.set('');
            this.loadError.set('');
            if (!name) {
                this.result.set(null);
                return;
            }
            this.loading.set(true);
            this.config.derivedSchema(name).subscribe({
                next: (r) => {
                    this.result.set(r);
                    this.loading.set(false);
                },
                error: (err: HttpErrorResponse) => {
                    this.result.set(null);
                    // 422 is the author's own config failing to compile — actionable, and phrased as such.
                    if (err.status === 422)
                        this.bindError.set(apiErrorMessage(err, 'The pipeline refused to derive a schema.'));
                    else this.loadError.set(apiErrorMessage(err, 'Could not derive the output schema.'));
                    this.loading.set(false);
                },
            });
        });
    }
}
