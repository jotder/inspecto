import {
    ChangeDetectionStrategy,
    Component,
    HostListener,
    ViewChild,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { AuthoredNode } from 'app/inspecto/api';
import { CollectorConfigComponent } from 'app/inspecto/collector/collector-config.component';
import { AttributeSpec, MARKER_DEDUP_ATTRIBUTES } from 'app/inspecto/component-model';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { buildConfiguredNode, splitNodeConfig } from './node-config-build';
import { nodeAttributesFor } from './node-attributes';

/**
 * The **Collector definition pane** (definition-surface P1) — the acquisition path of
 * `node-config.dialog`, re-hosted inside `<inspecto-definition-drawer>` instead of a popup. Renders
 * the shared `<inspecto-collector-config>` surface, the dedup Guarantees group, and the free-form
 * additional-config escape hatch (identity is the inspector's rename pencil, never re-asked here); {@link submit} rebuilds the node through the SAME
 * `buildConfiguredNode` the dialog uses and emits it — **pure**: nothing is persisted here (D2),
 * the host patches its in-memory model and the toolbar Save persists.
 *
 * <p>Discard is host-owned: the host recreates this component from the model (no internal reset path),
 * which is what keeps the collector surface's own mode/seed state trivially correct.
 */
@Component({
    selector: 'app-pipeline-collection-definition',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        CollectorConfigComponent,
        InspectoSchemaFormComponent,
    ],
    template: `
        <form [formGroup]="form" (ngSubmit)="submit()" class="space-y-1">
            <!-- S2/principle 5: identity is asked ONCE, on the inspector's rename pencil — never
                 re-asked inside a definition pane. Both values are carried through submit() anyway,
                 because the node is rebuilt from scratch there.
                 (no backticks in this comment: it lives inside a template literal) -->
            <div class="mb-1 text-xs font-semibold uppercase opacity-70">Config</div>
            <inspecto-collector-config
                [specs]="collectorSpecs()"
                [initial]="split().schemaInitial"
                [storedConnector]="storedConnector()"
                (submitted)="submit()"
            />

            <!--
                Duplicate handling — the file-grain Guarantees this node decides in the poll cycle.
                Its own group, NOT folded into the collector surface above: that component authors the
                collector block (its mode toggle and Test connection are about that block), while
                these keys land in processing.duplicate_check + dirs.markers. An empty served list
                hides the group — the §3.1 "no schema" contract, not a reason to invent fields.
            -->
            @if (dedupSpecs().length) {
                <div class="mb-1 mt-3 text-xs font-semibold uppercase opacity-70">Duplicate handling</div>
                <inspecto-schema-form
                    #dedup
                    [specs]="dedupSpecs()"
                    [initial]="split().schemaInitial"
                    (submitted)="submit()"
                />
            }

            <!-- Additional / free-form config: the collapsed escape hatch for keys outside the schema. -->
            <div class="mb-1 mt-2 flex items-center justify-between">
                <button
                    type="button"
                    class="flex items-center gap-1 text-xs font-semibold uppercase opacity-70"
                    [attr.aria-expanded]="freeFormOpen()"
                    (click)="freeFormOpen.set(!freeFormOpen())"
                >
                    <mat-icon
                        class="icon-size-4"
                        [svgIcon]="
                            freeFormOpen() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'
                        "
                    ></mat-icon>
                    Additional config
                    @if (configRows.length) {
                        <span class="opacity-60">({{ configRows.length }})</span>
                    }
                </button>
                @if (freeFormOpen()) {
                    <button mat-stroked-button type="button" (click)="addConfigRow()">
                        <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                        <span class="ml-1">Add</span>
                    </button>
                }
            </div>
            @if (freeFormOpen()) {
                <div formArrayName="config" class="space-y-2">
                    @for (row of configRows.controls; track $index) {
                        <div class="flex items-center gap-1" [formGroupName]="$index">
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>Key</mat-label>
                                <input matInput formControlName="key" />
                            </mat-form-field>
                            <mat-form-field subscriptSizing="dynamic" class="flex-1">
                                <mat-label>Value</mat-label>
                                <input matInput formControlName="value" />
                            </mat-form-field>
                            <button
                                mat-icon-button
                                type="button"
                                (click)="removeConfigRow($index)"
                                aria-label="Remove config entry"
                            >
                                <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                            </button>
                        </div>
                    }
                    @if (!configRows.length) {
                        <p class="text-sm opacity-60">No extra config — add a key/value entry above.</p>
                    }
                </div>
            }
        </form>
    `,
})
export class PipelineCollectionDefinitionComponent {
    private fb = inject(FormBuilder);

    /** The acquisition node being defined (identity fixed; config/use editable). */
    readonly node = input.required<AuthoredNode>();
    /**
     * The type's config vocabulary as published by the server (`GET /pipelines/node-types`).
     * `undefined` ⇒ catalog not resolved — fall back to the local table, exactly as the dialog does.
     * A served EMPTY array is honoured as "no schema" (the §3.1 contract).
     */
    readonly attributes = input<AttributeSpec[] | undefined>(undefined);

    /** The edited node, rebuilt by {@link submit} — the host applies it to the in-memory model. */
    readonly applied = output<AuthoredNode>();
    /** Whether the pane holds edits since creation / the last successful submit. */
    readonly dirtyChange = output<boolean>();

    @ViewChild(CollectorConfigComponent) private collector?: CollectorConfigComponent;
    @ViewChild('dedup') private dedup?: InspectoSchemaFormComponent;

    readonly specs = computed<AttributeSpec[]>(() => this.attributes() ?? nodeAttributesFor(this.node().type) ?? []);
    /**
     * P5-a homed marker dedup on this node, so its published spec is the `collector:` block PLUS four
     * borrowed keys. They are split back out here: the shared collector surface must keep authoring
     * only the block it names, and the drawer shows the Guarantees as their own group.
     */
    private static readonly DEDUP_KEYS = MARKER_DEDUP_ATTRIBUTES.map((a) => a.key);
    readonly collectorSpecs = computed(() =>
        this.specs().filter((s) => !PipelineCollectionDefinitionComponent.DEDUP_KEYS.includes(s.key)),
    );
    readonly dedupSpecs = computed(() =>
        this.specs().filter((s) => PipelineCollectionDefinitionComponent.DEDUP_KEYS.includes(s.key)),
    );
    /** The node's stored `connector`, so the shared component can grandfather a hand-authored one. */
    readonly storedConnector = computed(() => String(this.node().config?.['connector'] ?? ''));
    /** Schema seed + free-form rows — the same split the dialog runs (`node-config-build.ts`). */
    readonly split = computed(() => splitNodeConfig(this.node(), this.specs(), true));

    readonly freeFormOpen = signal(false);

    readonly form = this.fb.group({
        config: this.fb.array<ReturnType<PipelineCollectionDefinitionComponent['configRow']>>([]),
    });

    private lastDirty = false;

    constructor() {
        // Seed from the node input. The host recreates this component per node (and on Discard), so
        // this runs once per instance — but an input swap without recreation re-seeds correctly too.
        effect(() => {
            this.node();
            this.configRows.clear();
            for (const row of this.split().extraRows) this.configRows.push(this.configRow(row.key, row.value));
            this.freeFormOpen.set(this.split().extraRows.length > 0);
            this.form.markAsPristine();
            this.emitDirty();
        });
    }

    /**
     * Dirty is derived on interaction, not streamed: the collector surface exposes `isDirty()` as a
     * method (no output), so the pane re-derives after any user input/click inside it and reports
     * transitions to the host — which is all the drawer's badge and close-guard need.
     */
    @HostListener('input')
    @HostListener('click')
    onInteraction(): void {
        this.emitDirty();
    }

    private emitDirty(): void {
        const dirty = this.form.dirty || (this.collector?.isDirty() ?? false) || (this.dedup?.isDirty() ?? false);
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    get configRows(): FormArray {
        return this.form.get('config') as FormArray;
    }

    addConfigRow(): void {
        this.configRows.push(this.configRow('', ''));
        this.form.markAsDirty();
        this.emitDirty();
    }

    removeConfigRow(i: number): void {
        this.configRows.removeAt(i);
        this.form.markAsDirty();
        this.emitDirty();
    }

    /**
     * Validate, resolve the derived connector (a refusal explains itself inline and aborts — the
     * unsaved-Connection / blanked-picker guards live in the shared component), rebuild the node,
     * emit. Marks the pane pristine on success — Apply consumed the edits.
     */
    submit(): void {
        if (this.collector && !this.collector.validate()) return;
        if (this.dedup && !this.dedup.validate()) return;
        const connector = this.collector?.resolveConnector() ?? null;
        if (!connector) return;
        const v = this.form.getRawValue();
        // Two schema surfaces, one node: the collector block's values plus the dedup group's. Merged
        // against the FULL spec list, so buildConfiguredNode still sees every specced key and none of
        // them leaks into the free-form rows.
        const node = buildConfiguredNode({
            node: this.node(),
            specs: this.specs(),
            formValues: { ...(this.collector?.value() ?? {}), ...(this.dedup?.value() ?? {}) },
            freeRows: v.config as { key: string; value: string }[],
            name: this.node().name,
            description: this.node().description,
            isAcquisition: true,
            connector,
        });
        this.form.markAsPristine();
        this.collector?.markPristine();
        this.dedup?.form.markAsPristine();
        this.emitDirty();
        this.applied.emit(node);
    }

    private configRow(key: string, value: string) {
        return this.fb.group({
            key: this.fb.control(key, { nonNullable: true }),
            value: this.fb.control(value, { nonNullable: true }),
        });
    }
}
