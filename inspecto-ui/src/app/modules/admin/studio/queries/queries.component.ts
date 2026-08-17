import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { LensService, ParameterContextService, apiErrorMessage } from 'app/inspecto/api';
import {
    BUILTIN_PARAMS,
    ParameterDef,
    QueryChange,
    QueryModel,
    QueryPanelComponent,
    QuerySource,
    emptyGroup,
    findParameters,
    resolveParameters,
} from 'app/inspecto/query';
import { ResultColumn, ResultSet, describeResultSet, recommend } from 'app/inspecto/viz';
import { registerBuiltinViz } from 'app/inspecto/viz/plugins';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiDraft } from 'app/inspecto/ai-assist/ai-draft';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ComponentHistoryDialog } from 'app/inspecto/components/component-history.dialog';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { uniqueNameValidator } from 'app/inspecto/investigation/unique-name';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { DatasetRows, DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { Query, QueryType, buildQuery } from './query-types';
import { QueriesService } from './queries.service';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import './query.kind'; // ensure the query kind is registered

/** The default (empty) structured model — a fresh Query Core builder state. */
function emptyModel(): QueryModel {
    return { projection: '*', where: emptyGroup('AND'), sqlOverride: null };
}

// The Show-Me recommender (used in the preview) scores against the registered viz plugins; register the
// built-ins here so the Query Library works standalone (guarded — no-op if the widgets feature loaded first).
registerBuiltinViz();

/** The outcome of a preview run — the resolved SQL, plus (on success) the described result set. */
interface PreviewState {
    resolvedSql: string;
    resultSet?: ResultSet;
    rows?: Record<string, unknown>[];
    recommended?: string[];
    error?: string;
    /** The store held more rows than the page the preview describes. */
    truncated?: boolean;
}

/** Strip a `$token` (e.g. `$day(-7)`) to its name (`day`). */
function tokenName(raw: string): string {
    return raw.replace(/^\$/, '').replace(/\(.*\)$/, '');
}

/**
 * **Query Library** — R3 of the living-operational-system roadmap (§4): author reusable `query`
 * components. A query reads a source dataset and is either **SQL** (text, may reference `$`-parameters;
 * resolve params → {@link DatasetRowsService.sql}) or **structured** (the shared Query Core builder,
 * `<inspecto-query-panel>` → the model sent as the dataset's query; no `$`-parameters in this slice —
 * there is no SQL text to scan them from). Either way the preview is {@link describeResultSet} over what
 * came back. One saved query can then be bound by many widgets. Both runs go through the rows seam, so
 * live they execute against the real store and offline against its sample page. Follows the house form
 * rules (ask-the-minimum, duplicate-name = inline block).
 */
@Component({
    selector: 'app-queries',
    standalone: true,
    imports: [
        ChipComponent,
        AiExplainComponent,
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
        QueryPanelComponent,
        AiAssistComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './queries.component.html',
})
export class QueriesComponent implements OnInit {
    private fb = inject(FormBuilder);
    private queriesApi = inject(QueriesService);
    private datasetsApi = inject(DatasetsService);
    private datasetRows = inject(DatasetRowsService);
    private paramCtx = inject(ParameterContextService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private lens = inject(LensService);
    private destroyRef = inject(DestroyRef);
    private dialog = inject(MatDialog);

    /** Authoring gate — Business lens views read-only (capability seam, not lens identity). */
    readonly canAuthor = this.lens.canAuthorWorkbench;

    readonly queries = signal<Query[]>([]);
    readonly datasets = signal<Dataset[]>([]);
    readonly loading = signal(false);
    readonly editing = signal(false);
    readonly editingExisting = signal(false);
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly running = signal(false);
    readonly preview = signal<PreviewState | null>(null);

    readonly form = this.fb.group({
        name: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
        description: [''],
        datasetId: this.fb.nonNullable.control('', Validators.required),
        text: this.fb.nonNullable.control(''),
        type: this.fb.nonNullable.control<QueryType>('sql'),
    });

    /** `type: 'structured'` — the live model + compiled SQL emitted by `<inspecto-query-panel>`. */
    readonly structuredModel = signal<QueryModel>(emptyModel());
    readonly structuredSql = signal('');

    /** The builder panel's data source — one PAGE of the selected dataset's store, plus its columns. The
     *  panel previews a filter in-browser as it is built, so it needs rows; the authoritative run below
     *  goes back to the store. */
    readonly panelSource = signal<QuerySource>({ name: 'data', rows: [] });

    /** Live mirror of the SQL text (drives parameter detection) + the user-declared param defaults/types.
     *  Types are preserved from a loaded/seeded query (no type picker in the MVP — new tokens default to string). */
    readonly text = signal('');
    readonly paramDefaults = signal<Record<string, string>>({});
    readonly paramTypes = signal<Record<string, ParameterDef['type']>>({});

    /** The distinct built-in `$`-tokens present (resolved from context — shown read-only). */
    readonly builtinTokens = computed(() =>
        findParameters(this.text()).filter((t) =>
            BUILTIN_PARAMS.includes(tokenName(t) as (typeof BUILTIN_PARAMS)[number]),
        ),
    );
    /** The distinct user-declared `$`-token names present (each gets an editable default). */
    readonly userParamNames = computed(() => [
        ...new Set(
            findParameters(this.text())
                .map(tokenName)
                .filter((n) => !BUILTIN_PARAMS.includes(n as (typeof BUILTIN_PARAMS)[number])),
        ),
    ]);

    ngOnInit(): void {
        this.form.controls.text.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((t) => this.text.set(t));
        // The builder panel previews in-browser while a filter is built, so it needs a page of the picked
        // store's rows; re-read it whenever the pick changes.
        this.form.controls.datasetId.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => void this.loadPanelSource());
        this.datasetsApi.list().subscribe({
            next: (d) => this.datasets.set(d),
            error: () => this.toastr.warning('Could not load datasets.'),
        });
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.queriesApi.list().subscribe({
            next: (q) => {
                this.queries.set(q);
                this.loading.set(false);
            },
            // A bare `loading.set(false)` cleared the spinner and let the "no queries yet" empty state
            // render, so a failed load was indistinguishable from an empty library — the operator
            // concludes their queries are gone and re-authors ones that already exist. Both sibling
            // libraries (widgets, datasets) already say so out loud; this one now matches.
            error: () => {
                this.queries.set([]);
                this.loading.set(false);
                this.toastr.warning('Could not load queries — is ControlApi running?');
            },
        });
    }

    newQuery(): void {
        this.form.reset({ name: '', description: '', datasetId: '', text: '', type: 'sql' });
        this.form.controls.name.enable();
        this.form.controls.name.setValidators([
            Validators.required,
            Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
            uniqueNameValidator(() => this.queries().map((q) => q.id)),
        ]);
        this.form.controls.name.updateValueAndValidity({ emitEvent: false });
        this.paramDefaults.set({});
        this.paramTypes.set({});
        this.text.set('');
        this.structuredModel.set(emptyModel());
        this.structuredSql.set('');
        this.preview.set(null);
        this.editingExisting.set(false);
        this.editing.set(true);
    }

    editQuery(q: Query): void {
        this.form.reset({
            name: q.name,
            description: q.description ?? '',
            datasetId: q.datasetId ?? '',
            text: q.text ?? '',
            type: q.type,
        });
        this.form.controls.name.setValidators([Validators.required]);
        this.form.controls.name.disable(); // id is immutable on edit
        this.paramDefaults.set(Object.fromEntries(q.parameters.map((p) => [p.name, p.default ?? ''])));
        this.paramTypes.set(Object.fromEntries(q.parameters.map((p) => [p.name, p.type])));
        this.text.set(q.text ?? '');
        // Deep-clone: the condition-group editor mutates the bound `ConditionGroup` in place, and this
        // must not corrupt the cached list item before Save.
        this.structuredModel.set(q.model ? structuredClone(q.model) : emptyModel());
        this.structuredSql.set('');
        this.preview.set(null);
        this.editingExisting.set(true);
        this.editing.set(true);
    }

    /** Live update from `<inspecto-query-panel>` — its projection/filter builder emits both the model and
     *  its compiled SQL (already resolved against the current source), so nothing here recomputes it. */
    onStructuredChange(e: QueryChange): void {
        this.structuredModel.set(e.model);
        this.structuredSql.set(e.sql);
    }

    cancel(): void {
        this.editing.set(false);
        this.preview.set(null);
    }

    /** Show version history for a saved query; reload the list after a restore (MET-5). If that query is
     *  open in the edit form, close it — a stale form left open would silently overwrite the restore on save. */
    history(q: Query): void {
        this.dialog
            .open(ComponentHistoryDialog, { data: { type: 'query', id: q.id, label: q.name } })
            .afterClosed()
            .subscribe((restored) => {
                if (!restored) return;
                if (this.editingExisting() && this.form.getRawValue().name === q.name) this.cancel();
                this.load();
            });
    }

    setParamDefault(name: string, value: string): void {
        this.paramDefaults.set({ ...this.paramDefaults(), [name]: value });
    }

    /** The declared parameters = each user token + its (possibly empty) default and preserved type. */
    private paramDefs(): ParameterDef[] {
        const defaults = this.paramDefaults();
        const types = this.paramTypes();
        return this.userParamNames().map((name) => ({
            name,
            type: types[name] ?? 'string',
            default: defaults[name] ?? '',
        }));
    }

    private async loadPanelSource(): Promise<void> {
        const ds = this.selectedDataset();
        if (!ds) {
            this.panelSource.set({ name: 'data', rows: [] });
            return;
        }
        const page = await this.datasetRows.rows(ds);
        this.panelSource.set({ name: ds.sourceName, rows: page.rows, columns: page.columns });
    }

    private selectedDataset(): Dataset | undefined {
        return this.datasets().find((d) => d.id === this.form.controls.datasetId.value);
    }

    async run(): Promise<void> {
        const ds = this.selectedDataset();
        const hints = (ds?.columns ?? []).map((c) => ({ name: c.name, type: c.type, role: c.role }));
        this.running.set(true);

        if (this.form.controls.type.value === 'structured') {
            // The model goes to the store as the dataset's own query, so the preview is the real result
            // rather than the model evaluated over whatever page the panel happened to hold.
            const page = await this.datasetRows.rows({
                sourceName: ds?.sourceName ?? 'data',
                columns: ds?.columns,
                query: this.structuredModel(),
            });
            this.setPreview(this.structuredSql(), page, hints);
            return;
        }

        const resolvedSql = resolveParameters(this.form.controls.text.value, this.paramDefs(), this.paramCtx.context());
        this.setPreview(resolvedSql, await this.datasetRows.sql(ds?.sourceName ?? 'data', resolvedSql), hints);
    }

    /** One preview from a resolved page: the error when it failed, otherwise the described result set. */
    private setPreview(resolvedSql: string, page: DatasetRows, hints: ResultColumn[]): void {
        if (page.error) {
            this.preview.set({ resolvedSql, error: page.error });
        } else {
            const resultSet = describeResultSet(page.rows, hints);
            this.preview.set({
                resolvedSql,
                resultSet,
                rows: page.rows.slice(0, 20),
                recommended: recommend(resultSet)
                    .slice(0, 3)
                    .map((p) => p.meta.label),
                truncated: page.truncated,
            });
        }
        this.running.set(false);
    }

    /**
     * AGT-6a A2/A3: the pane's own state as `query_author`'s arguments — the dataset the operator picked
     * and the condition tree they already built, so nothing is re-stated. `when` is the structured tree
     * only; the server renders the predicate, so no SQL text ever crosses the wire inbound.
     */
    aiQueryArgs(): Record<string, unknown> {
        const name = String(this.form.controls.name.value ?? '').trim();
        return {
            dataset: this.form.controls.datasetId.value,
            when: this.structuredModel().where ?? {},
            ...(name ? { name } : {}),
        };
    }

    /**
     * The args for the natural-language variant (AGT-6a A5.1) — **identity only, deliberately.**
     *
     * ⚠ The pane's args are applied AFTER the model's and win, which is right for `dataset` (the screen
     * knows it, a model can hallucinate it) and fatal for `when`: passing the condition tree here would
     * overwrite the condition the sentence just derived, and the feature would silently do nothing while
     * looking like it worked. That is why this is a separate accessor and not {@link aiQueryArgs}.
     */
    aiPromptArgs(): Record<string, unknown> {
        const name = String(this.form.controls.name.value ?? '').trim();
        return {
            dataset: this.form.controls.datasetId.value,
            ...(name ? { name } : {}),
        };
    }

    /** The current SQL as the diff baseline — `query_author` returns a `{type,text,datasetId}` draft. */
    aiCurrentQuery(): Record<string, unknown> | null {
        const text = this.form.controls.type.value === 'sql' ? this.form.controls.text.value : this.structuredSql();
        if (!text) return null;
        return { type: 'sql', text, datasetId: this.form.controls.datasetId.value };
    }

    /**
     * Adopt drafted SQL into the form (AGT-6a A2). It stops at the form: the operator still presses the
     * existing Save, so the write goes through `save()`/`queriesApi.save` with the human as the actor
     * (decision D2). Switches the editor to `sql` because the draft IS rendered SQL — leaving the type on
     * `structured` would silently discard it on save (the model, not the text, is persisted there).
     */
    applyQueryDraft(draft: AiDraft): void {
        const text = draft.config['text'];
        if (typeof text !== 'string' || !text.trim()) return;
        this.form.controls.type.setValue('sql');
        this.form.controls.text.setValue(text);
        this.form.controls.text.markAsDirty();
    }

    save(): void {
        // The name control is disabled on edit; `.value` still exposes it, and validity is only enforced
        // on create (the shared duplicate/pattern rules are create-only).
        const name = String(this.form.controls.name.value ?? '').trim();
        if (
            !name ||
            !this.form.controls.datasetId.value ||
            (this.form.controls.name.enabled && this.form.controls.name.invalid)
        ) {
            this.form.markAllAsTouched();
            return;
        }
        const ds = this.selectedDataset();
        const type = this.form.controls.type.value;
        const q = buildQuery(name, type, {
            datasetId: this.form.controls.datasetId.value,
            sourceName: ds?.sourceName,
            text: type === 'sql' ? this.form.controls.text.value : null,
            model: type === 'structured' ? this.structuredModel() : null,
            parameters: type === 'sql' ? this.paramDefs() : [],
        });
        q.description = this.form.controls.description.value || undefined;
        this.saving.set(true);
        this.queriesApi.save(q, { update: this.editingExisting() }).subscribe({
            next: () => {
                this.saving.set(false);
                this.toastr.success(`Query "${q.name}" saved`);
                this.editing.set(false);
                this.load();
            },
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                this.toastr.error(
                    e?.status === 503
                        ? 'Writes are disabled (no write root configured).'
                        : apiErrorMessage(e, `Could not save "${q.name}"`),
                );
            },
        });
    }

    async remove(q: Query): Promise<void> {
        if (!(await this.confirm.confirmDestructive(`Delete query "${q.name}"?`))) return;
        this.queriesApi.remove(q.id).subscribe({
            next: () => {
                this.toastr.success(`Query "${q.name}" deleted`);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, `Could not delete "${q.name}"`)),
        });
    }

    /** The generic `duplicate` message the shared validator raises (mirrors the other authoring forms). */
    errorFor(control: 'name'): string | null {
        const c = this.form.controls[control];
        if (!c.touched) return null;
        if (c.hasError('required')) return 'Required.';
        if (c.hasError('pattern')) return 'Letters, digits, dot, dash, underscore; start alphanumeric.';
        if (c.hasError('duplicate')) return 'That name is already taken.';
        return null;
    }
}
