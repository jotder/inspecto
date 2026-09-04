import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { SchemaFieldRow } from './schema-fields-editor.component';

/** The per-column metadata this grid edits — `raw.fields[].{description,unit,classification}`,
 *  Catalog-facing and never read by the ETL (the same tier as `synonym`). */
export interface SchemaFieldMetadata {
    description?: string;
    unit?: string;
    classification?: string;
}

/**
 * The **column-metadata grid** (D1(b), delimited-grammar-properties plan) — description / unit /
 * classification per column, projected into the grammar editor's `[tabTypes]` slot under Output
 * schema (behind the Column metadata… disclosure). The model shipped long ago; this is its first UI.
 *
 * <p><b>A second VIEW over the columns table's rows, not a second owner.</b> It seeds from the same
 * `[rows]` signal as `<inspecto-schema-fields-editor>` and edits ONLY the three metadata keys —
 * identity columns (# / name) render read-only, keyed by the row's `selector` (stable and read-only
 * in the columns table, so a rename over in tab 2 can never orphan a metadata edit). The host merges
 * {@link value} onto the columns table's rows by selector at submit.
 *
 * <p><b>Pure</b>, the fields editor's contract: no API client, hosts read {@link value} and own every
 * write. No validation — all three are free-text Catalog annotations.
 */
@Component({
    selector: 'inspecto-schema-metadata-grid',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatPaginatorModule],
    template: `
        <table class="w-full border-collapse">
            <thead>
                <tr class="text-secondary text-left text-xs font-semibold uppercase tracking-wider">
                    <th scope="col" class="w-16 pb-1">#</th>
                    <th scope="col" class="w-40 pb-1">Name</th>
                    <th scope="col" class="pb-1">Description</th>
                    <th scope="col" class="w-28 pb-1">Unit</th>
                    <th scope="col" class="w-36 pb-1">Classification</th>
                </tr>
            </thead>
            <tbody>
                @for (e of pagedEntries(); track e.selector) {
                    <tr [formGroup]="e.group" class="border-t align-middle">
                        <td class="py-1 font-mono text-xs opacity-70">{{ e.selector }}</td>
                        <td class="truncate py-1 text-sm">{{ e.name }}</td>
                        <td class="py-1 pr-2">
                            <input
                                class="w-full bg-transparent text-sm"
                                formControlName="description"
                                [attr.aria-label]="'Description for ' + (e.name || e.selector)"
                            />
                        </td>
                        <td class="py-1 pr-2">
                            <input
                                class="w-full bg-transparent text-sm"
                                formControlName="unit"
                                [attr.aria-label]="'Unit for ' + (e.name || e.selector)"
                            />
                        </td>
                        <td class="py-1">
                            <input
                                class="w-full bg-transparent text-sm"
                                formControlName="classification"
                                [attr.aria-label]="'Classification for ' + (e.name || e.selector)"
                            />
                        </td>
                    </tr>
                }
            </tbody>
        </table>
        @if (rows().length > pageSize()) {
            <mat-paginator
                [length]="rows().length"
                [pageIndex]="pageIndex()"
                [pageSize]="pageSize()"
                [pageSizeOptions]="[50, 100, 250]"
                (page)="onPage($event)"
                aria-label="Column metadata pages"
            ></mat-paginator>
        }
    `,
})
export class InspectoSchemaMetadataGridComponent {
    private fb = inject(FormBuilder);

    /**
     * The columns to annotate — the SAME signal the columns table is seeded from, so both grids
     * rebuild together on a reference change. Metadata already on the rows (a hydrated schema, a
     * CSV import) seeds the inputs.
     */
    readonly rows = input<SchemaFieldRow[]>([]);

    readonly form: FormGroup = this.fb.group({ fields: this.fb.array<FormGroup>([]) });
    private get fieldRows(): FormArray<FormGroup> {
        return this.form.controls['fields'] as FormArray<FormGroup>;
    }

    readonly pageIndex = signal(0);
    readonly pageSize = signal(50);
    private readonly structureVersion = signal(0);

    private entries(): { group: FormGroup; selector: string; name: string }[] {
        return this.fieldRows.controls.map((group) => ({
            group,
            selector: String(group.get('selector')?.value ?? ''),
            name: String(group.get('name')?.value ?? ''),
        }));
    }

    readonly pagedEntries = computed(() => {
        this.structureVersion();
        const start = this.pageIndex() * this.pageSize();
        return this.entries().slice(start, start + this.pageSize());
    });

    constructor() {
        effect(() => {
            const seed = this.rows();
            this.fieldRows.clear();
            for (const r of seed) {
                this.fieldRows.push(
                    this.fb.group({
                        selector: [{ value: r.selector, disabled: true }],
                        name: [{ value: r.name, disabled: true }],
                        description: [r.description ?? ''],
                        unit: [r.unit ?? ''],
                        classification: [r.classification ?? ''],
                    }),
                );
            }
            this.pageIndex.set(0);
            this.structureVersion.update((v) => v + 1);
            // A freshly seeded grid is pristine: the host has applied nothing yet.
            this.form.markAsPristine();
        });
    }

    onPage(e: PageEvent): void {
        this.pageIndex.set(e.pageIndex);
        this.pageSize.set(e.pageSize);
    }

    /** `selector → metadata`, trimmed; blank values are dropped so a cleared field removes its key. */
    value(): Map<string, SchemaFieldMetadata> {
        const out = new Map<string, SchemaFieldMetadata>();
        for (const g of this.fieldRows.controls) {
            const v = g.getRawValue() as Record<string, string>;
            const meta: SchemaFieldMetadata = {};
            for (const k of ['description', 'unit', 'classification'] as const) {
                const t = (v[k] ?? '').trim();
                if (t) meta[k] = t;
            }
            out.set(String(v['selector'] ?? ''), meta);
        }
        return out;
    }

    /** The metadata for these rows, merged by selector — what the host persists / exports. */
    applyTo(rows: SchemaFieldRow[]): SchemaFieldRow[] {
        const meta = this.value();
        return rows.map((r) => {
            const m = meta.get(String(r.selector));
            if (!m) return r;
            const { description: _d, unit: _u, classification: _c, ...rest } = r;
            return { ...rest, ...m };
        });
    }

    markPristine(): void {
        this.form.markAsPristine();
    }
}
