import { ChangeDetectionStrategy, Component, Input, inject, input, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { ParserTreeNode } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import {
    EVENT_TYPE_COLUMN,
    IDENTIFIER_RE,
    SEGMENT_COLUMN_TYPES,
    SegmentColumnType,
    SegmentDraft,
    columnNameFor,
    deriveSegments,
} from './segment-drafts';

/**
 * Segments editor — maps a hierarchical parser's decoded record tree onto flat segment schemas, the
 * step that turns an `ingestable` plugin parser from previewable into loadable. One segment becomes
 * one schema toon and one Table.
 *
 * <p>Bespoke by necessity: `FieldSpec` cannot express "a list of segments, each with a list of
 * columns" (`ConfigSpecs.schema()` hits the same wall and says so), so this is a nested `FormArray`
 * in the host, the `fixedwidth.fields[]` / schema-stage `fieldRows` precedent.
 *
 * <p>Editing only — the host owns persistence, because saving a segment means writing its schema
 * toon *and* patching the pipeline's `parsing.plugin` block, which is the host's transaction.
 */
@Component({
    selector: 'app-onboarding-segments-editor',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
    ],
    templateUrl: './segments-editor.component.html',
})
export class OnboardingSegmentsEditorComponent {
    private fb = inject(FormBuilder);

    /** The plugin preview's record forest — the source "Derive from preview" reads. */
    readonly tree = input<ParserTreeNode[] | null>(null);
    /** Whether the operator may edit (lens gate lives in the host). */
    readonly editable = input(true);

    readonly types = SEGMENT_COLUMN_TYPES;
    readonly eventTypeColumn = EVENT_TYPE_COLUMN;

    readonly form: FormGroup = this.fb.group({ segments: this.fb.array<FormGroup>([]) });
    get segments(): FormArray<FormGroup> {
        return this.form.controls['segments'] as FormArray<FormGroup>;
    }

    /** Set when the last derive found nothing to propose — surfaced instead of silently no-op'ing. */
    readonly deriveNote = signal<string | null>(null);

    /** Seed from the saved config; marks pristine so a resumed draft is not "dirty" on arrival. */
    @Input() set initial(drafts: SegmentDraft[] | null) {
        if (!drafts) return;
        this.segments.clear();
        for (const d of drafts) this.addSegment(d);
        this.form.markAsPristine();
    }

    columnsOf(segment: FormGroup): FormArray<FormGroup> {
        return segment.controls['columns'] as FormArray<FormGroup>;
    }

    addSegment(draft?: SegmentDraft): void {
        const columns = this.fb.array<FormGroup>([]);
        const group = this.fb.group({
            key: [draft?.key ?? '', [Validators.required]],
            columns,
        });
        this.segments.push(group);
        for (const c of draft?.columns ?? []) this.addColumn(group, c.name, c.selector, c.type);
        if (!draft) this.addColumn(group);
    }

    removeSegment(i: number): void {
        this.segments.removeAt(i);
        this.form.markAsDirty();
    }

    addColumn(segment: FormGroup, name = '', selector = '', type: SegmentColumnType = 'VARCHAR'): void {
        this.columnsOf(segment).push(
            this.fb.group({
                name: [name, [Validators.required, Validators.pattern(IDENTIFIER_RE)]],
                selector: [selector, [Validators.required]],
                type: [type],
            }),
        );
    }

    removeColumn(segment: FormGroup, i: number): void {
        this.columnsOf(segment).removeAt(i);
        this.form.markAsDirty();
    }

    /** Fill the column name from the selector, so typing a path is enough for the common case. */
    nameFromSelector(column: FormGroup): void {
        const selector = String(column.value['selector'] ?? '').trim();
        if (!selector || String(column.value['name'] ?? '').trim()) return;
        column.get('name')?.setValue(columnNameFor(selector));
        column.markAsDirty();
    }

    /**
     * Replace the current segments with one proposal per record type in the preview. Destructive by
     * design — it is the "start from my data" action, and the operator edits from there.
     */
    deriveFromPreview(): void {
        const drafts = deriveSegments(this.tree());
        if (drafts.length === 0) {
            this.deriveNote.set(
                'Nothing to derive — run Test parse first, and check the preview shows records with fields.',
            );
            return;
        }
        this.deriveNote.set(null);
        this.segments.clear();
        for (const d of drafts) this.addSegment(d);
        this.form.markAsDirty();
    }

    /** True when the form is complete AND every segment key / column name is unique. */
    validate(): boolean {
        this.form.markAllAsTouched();
        if (this.segments.length === 0 || this.form.invalid) return false;
        const keys = this.value().map((s) => s.key);
        if (new Set(keys).size !== keys.length) return false;
        return this.value().every((s) => {
            const names = s.columns.map((c) => c.name);
            return names.length > 0 && new Set(names).size === names.length
                && !names.includes(EVENT_TYPE_COLUMN);   // the ingester derives this one
        });
    }

    /** The first reason `validate()` would fail, for an actionable message. */
    problem(): string | null {
        if (this.segments.length === 0) return 'Add at least one segment.';
        if (this.form.invalid) return 'Every segment needs a key, and every column a name and selector.';
        const drafts = this.value();
        const keys = drafts.map((s) => s.key);
        const dupKey = keys.find((k, i) => keys.indexOf(k) !== i);
        if (dupKey) return `Duplicate segment key "${dupKey}" — keys must be unique.`;
        for (const s of drafts) {
            const names = s.columns.map((c) => c.name);
            if (names.length === 0) return `Segment "${s.key}" needs at least one column.`;
            const dup = names.find((n, i) => names.indexOf(n) !== i);
            if (dup) return `Duplicate column "${dup}" in segment "${s.key}".`;
            if (names.includes(EVENT_TYPE_COLUMN))
                return `${EVENT_TYPE_COLUMN} is added automatically — remove it from "${s.key}".`;
        }
        return null;
    }

    value(): SegmentDraft[] {
        return this.segments.controls.map((g) => ({
            key: String(g.value['key'] ?? '').trim(),
            columns: this.columnsOf(g).controls.map((c) => ({
                name: String(c.value['name'] ?? '').trim(),
                selector: String(c.value['selector'] ?? '').trim(),
                type: (c.value['type'] ?? 'VARCHAR') as SegmentColumnType,
            })),
        }));
    }

    isDirty(): boolean {
        return this.form.dirty;
    }

    markPristine(): void {
        this.form.markAsPristine();
    }
}
