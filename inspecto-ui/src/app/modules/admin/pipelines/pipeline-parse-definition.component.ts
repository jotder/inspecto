import {
    ChangeDetectionStrategy,
    Component,
    computed,
    DestroyRef,
    effect,
    HostListener,
    inject,
    input,
    output,
    signal,
    untracked,
    ViewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable, forkJoin, of, throwError, timer } from 'rxjs';
import { catchError, map, switchMap, tap } from 'rxjs/operators';
import {
    Asn1DecoderDef,
    Asn1ValidationResult,
    AuthoredNode,
    ConfigService,
    DatasetRegistrationService,
    JobRunById,
    JobsService,
    ParserDef,
    ParserPreview,
    ParserRowSet,
    ParserTablePreview,
    ParserTreeNode,
    SchemaDrift,
    ParsersService,
    STALE_WRITE_MESSAGE,
    apiErrorMessage,
    apiUrl,
    datasetManualHint,
    isStaleVersionError,
} from 'app/inspecto/api';
import { DatasetColumn, inferRoles } from 'app/modules/admin/studio/datasets/dataset-types';
import { ColumnMeta, ColumnType } from 'app/inspecto/query';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { KEY_SEP, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { downloadCsv } from 'app/inspecto/data-table/core/csv';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { InspectoSamplePanelComponent } from 'app/inspecto/definition/sample-panel.component';
import {
    InspectoSchemaFieldsEditorComponent,
    SchemaFieldRow,
    SchemaPartitionRow,
    deriveSelector,
    sanitizeIdentifier,
} from 'app/inspecto/schema';
import { derivedSchemaRows, inferredSchemaFormats, inferredSchemaTypes, writeParseSchema } from './parse-output-schema';
import {
    decimalMarkOf,
    GrammarEditorComponent,
    ParsingFrontend,
    grammarCsvFilename,
    grammarToCsv,
    parseGrammarCsv,
    parsingAttributesFor,
} from 'app/inspecto/grammar';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import {
    InspectoSegmentsEditorComponent,
    SegmentDraft,
    EVENT_TYPE_COLUMN,
    effectiveColumnName,
    schemaDraftFor,
    companionSchemaName,
    portableConfigRef,
    schemaNameFromPath,
    segmentDraftFrom,
    segmentPathsOf,
} from 'app/inspecto/segments';

/**
 * The per-format parse node types the drawer serves → the frontend each one means. This is the ONE
 * list that decides both which parse nodes reach the drawer (the host's `isDrawerParse`) and which
 * format the editor is then locked to, so the two can never disagree.
 *
 * ⛔ A parse type absent here keeps the dialog. `parser` (the generic reader) has no single format to
 * lock, and binary fixed-width — which lifts to `parser.fixedwidth` all the same — carries its layout
 * in `processing.ingester_config`, which this pane cannot author (P3b operator decision).
 *
 * `asn1` (P3c) is not a built-in `ParsingFrontend` — the editor hosts it as the served parser it is,
 * schema-driven off `GET /parsers` — but the node type locks it exactly like the built-ins.
 *
 * `json` / `text_regex` (P3d slice C) close the gap between the plan's six-format icon table and the
 * four node types B6 named: they are ordinary built-ins the shared editor already rendered, so they
 * needed only their entry here plus the node type behind it.
 *
 * `plugin` (P3d slice D) is the generic custom-plugin path: unlike `asn1`, its node type does not name
 * ONE served parser — the pane offers a picker over whichever ingestable plugins the catalog serves,
 * excluding ones already homed by their own entry above (today, just `asn1`).
 */
export const PARSE_NODE_FRONTENDS: Record<string, ParsingFrontend | 'asn1' | 'plugin'> = {
    'parser.delimited': 'delimited',
    'parser.fixedwidth': 'fixedwidth',
    'parser.asn1': 'asn1',
    'parser.json': 'json',
    'parser.text_regex': 'text_regex',
    'parser.xlsx': 'xlsx', // multiformat X4 — read_xlsx via the DuckDB excel extension
    'parser.plugin': 'plugin',
};

/**
 * Does this node type occupy the pipeline's single **parse slot**? The generic `parser` node and every
 * per-format subtype both do — the flat config has one `parsing:` block, so a second one of EITHER
 * spelling is refused at lowering with `MULTI_PARSER`. Callers that reason about "the parse node"
 * (the Load pane's schema context, the palette's add rule) must ask this, not just the subtype map.
 */
export const isParseNodeType = (type: string): boolean => type === 'parser' || type in PARSE_NODE_FRONTENDS;

/**
 * The saved `asn1:` keys that make a preview serve derived rows (PARSING-PREVIEW-DERIVED-1): the `derive` /
 * `children` blocks, their limits, and the `segments` whose schemas name the columns. None is a grammar-form field.
 */
export const DERIVE_PREVIEW_KEYS = [
    'derive',
    'children',
    'max_list_elements',
    'max_derived_columns',
    'max_child_rows_per_record',
    'segments',
] as const;

/** The saved block's {@link DERIVE_PREVIEW_KEYS} the preview body does not already carry; `{}` when there are none. */
export function deriveKeysToCarry(
    own: Record<string, unknown>,
    prior: Record<string, unknown> | undefined,
): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const k of DERIVE_PREVIEW_KEYS) if (own[k] === undefined && isSetValue(prior?.[k])) out[k] = prior![k];
    return out;
}

/**
 * The `asn1:` keys a Pipeline backed by a **Decode Profile** (`asn1.profile_file`) actually overrides.
 * The served form holds every field at its default, and on the server every key the Pipeline sets wins
 * over the profile — so persisting (or previewing) the whole form would silently fork the Pipeline off
 * its vendor profile (`record_header_length: 0` over the profile's `4`). Kept: `profile_file`,
 * `segments`, keys the saved block already set (non-blank), and keys the user changed; blanks never.
 * `dirty` is the editor's own {@link GrammarEditorComponent#dirtyKeys} — FLAT control keys (`asn1__strictness`).
 * Without a profile the block is returned unchanged.
 */
export function decodeProfileOverrides(
    asn1: Record<string, unknown>,
    prior: Record<string, unknown> | undefined,
    dirty: Set<string>,
): Record<string, unknown> {
    const profile = asn1['profile_file'];
    if (typeof profile !== 'string' || profile.trim() === '') return asn1;
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(asn1)) {
        if (!isSetValue(v)) continue;
        // A key SAVED blank is unset (the server's rule), so a profile value the form was seeded with is not
        // re-written as an override just because the Pipeline once carried the key empty.
        if (k === 'profile_file' || k === 'segments' || isSetValue(prior?.[k]) || dirty.has('asn1' + KEY_SEP + k))
            out[k] = v;
    }
    return out;
}

/** Blank = unset, the Decode Profile overlay's own rule (`DecodeProfile.overlay`). */
function isSetValue(v: unknown): boolean {
    return v !== undefined && v !== null && !(typeof v === 'string' && v.trim() === '');
}

/** Where an `asn1` key's effective value comes from on a profile-backed Pipeline. */
export type DecodeProfileSource = 'profile' | 'here' | 'default';
export interface DecodeProfileRow {
    key: string;
    label: string;
    value: unknown;
    source: DecodeProfileSource;
}

/**
 * Each served `asn1` key's EFFECTIVE value and its provenance, by the server's overlay rule: a key the
 * Pipeline sets wins (`overrides` — exactly what {@link decodeProfileOverrides} would Save), then the
 * profile's own value, then the served default. Blank is unset at every level.
 */
export function decodeProfileProvenance(
    keys: { key: string; label: string; defaultValue?: unknown }[],
    profile: Record<string, unknown>,
    overrides: Record<string, unknown>,
): DecodeProfileRow[] {
    return keys.map(({ key, label, defaultValue }): DecodeProfileRow => {
        if (isSetValue(overrides[key])) return { key, label, value: overrides[key], source: 'here' };
        if (isSetValue(profile[key])) return { key, label, value: profile[key], source: 'profile' };
        return { key, label, value: defaultValue, source: 'default' };
    });
}

/**
 * A Decode Profile's own `segments` (`segment key → schema ref`, relative to the profile, as authored), or
 * `{}` when it names none. A Pipeline with no `segments` of its own inherits these whole (`segments` is
 * replaced whole, never merged — D5), so they are what the drawer shows and what lets such a Pipeline Apply.
 */
export function profileSegmentsOf(profile: Record<string, unknown> | null): Record<string, string> {
    const s = profile?.['segments'];
    if (!s || typeof s !== 'object' || Array.isArray(s)) return {};
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(s as Record<string, unknown>))
        if (typeof v === 'string' && v.trim()) out[k] = v;
    return out;
}

/** One derived column of a Decode Profile's `derive` block, for the read-only drawer list. */
export interface DerivedColumnRow {
    segment: string;
    column: string;
    /** The operation in words (`select ...` / `sum of ... over ...`), never an expression. */
    operation: string;
}

/**
 * The columns a `derive` block (Parsing D9: `segment key -> column -> {select | reduce ...}`, as authored) adds,
 * flattened to rows in authored order, or `[]` for no / a malformed block. Read-only: a Pipeline replaces the
 * whole key, never one column, and the server validates the block; this only renders it.
 */
export function derivedColumnsOf(derive: unknown): DerivedColumnRow[] {
    if (!derive || typeof derive !== 'object' || Array.isArray(derive)) return [];
    const rows: DerivedColumnRow[] = [];
    for (const [segment, cols] of Object.entries(derive as Record<string, unknown>)) {
        if (!cols || typeof cols !== 'object' || Array.isArray(cols)) continue;
        for (const [column, op] of Object.entries(cols as Record<string, unknown>)) {
            if (!op || typeof op !== 'object') continue;
            const o = op as Record<string, unknown>;
            let operation: string;
            if (o['select'] !== undefined) {
                const w = o['where'] && typeof o['where'] === 'object' ? Object.entries(o['where'] as object) : [];
                const nth = o['nth'] !== undefined && String(o['nth']) !== '0' ? ` (match ${o['nth']})` : '';
                operation =
                    `select ${o['select']}` +
                    (w.length ? ` where ${w[0][0]} = ${w[0][1]}` : '') +
                    ` take ${o['take'] ?? ''}${nth}`;
            } else {
                operation = `${o['op'] ?? '?'}${o['of'] !== undefined ? ' of ' + o['of'] : ''} over ${o['reduce'] ?? ''}`;
            }
            rows.push({ segment, column, operation });
        }
    }
    return rows;
}

/**
 * The block the form is SEEDED from on a profile-backed Pipeline: the profile's keys under the Pipeline's
 * own (a blank own key is unset, so the profile's shows). `segments` stays the Pipeline's — it is not a form
 * field, and the segments editor re-hydrates from the Pipeline's own refs. Seeding never makes a value an
 * override: Save keeps only what {@link decodeProfileOverrides} keeps.
 */
export function withDecodeProfile(
    block: Record<string, unknown>,
    profile: Record<string, unknown> | null,
): Record<string, unknown> {
    const own = block['asn1'];
    if (!profile || !own || typeof own !== 'object') return block;
    const merged: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(profile)) if (k !== 'segments' && isSetValue(v)) merged[k] = v;
    for (const [k, v] of Object.entries(own as Record<string, unknown>))
        if (isSetValue(v) || !(k in merged)) merged[k] = v;
    return { ...block, asn1: merged };
}

/**
 * The **Parse definition pane** (definition-surface P3a; fixed width P3b, ASN.1 P3c) — the per-format
 * path of the parse node, re-hosted inside `<inspecto-definition-drawer>` instead of
 * `grammar-editor.dialog`. Renders the shared `<inspecto-grammar-editor>` locked to the node's own
 * format (Name/Description moved to the canvas inspector's rename affordance): a per-format node's format IS its type (B6), so the picker would
 * only author a block the save path refuses with PARSER_FRONTEND_MISMATCH.
 *
 * <p>**One pane serves every per-format subtype**, keyed on {@link frontend}. B6 banned a generic
 * parser NODE TYPE with format tabs — not component reuse; the type still locks the format, so no
 * tabs appear and each format stays its own palette entry. A second copy of this file would only
 * drift.
 *
 * <p>**Pure** (D2), with ONE write of its own: {@link submit} rebuilds the node with the Grammar in
 * its inline `parsing:` home and emits it — the host patches its in-memory model and the toolbar Save
 * persists. The exception is an ASN.1 node's **segment schemas**, which this pane writes itself before
 * emitting a block that references them: P2's rule is that a stage's OWN companion artifact stays a
 * pane write (the reusable `grammar` component is a THIRD entity, which is why THAT one is emitted to
 * the host instead). ⚠ Applying therefore hits the server even though the node change is still only
 * in-memory — the same ordering, and the same orphan-on-discard tradeoff, as Onboarding's `savePlugin`.
 * Grammar-BOUND
 * nodes (`use: grammar/<id>`) do not reach this pane: updating a reusable component is its own write
 * route, which the dialog still owns — the host routes those to `grammar-editor.dialog`.
 *
 * <p>Discard is host-owned: the host recreates this component from the model (the drawer epoch), the
 * same contract as the Collection pane.
 */
@Component({
    selector: 'app-pipeline-parse-definition',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        NgTemplateOutlet,
        FormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatIconModule,
        MatTooltipModule,
        GrammarEditorComponent,
        InspectoAlertComponent,
        ChipComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        InspectoSamplePanelComponent,
        InspectoSegmentsEditorComponent,
        InspectoSchemaFieldsEditorComponent,
    ],
    template: `
        <!--
            The sample thread (§4.3), mounted where the sample is CONSUMED — choose the file, see it,
            then pick a format and options below. One thread per editor tab; the host owns it, so
            switching tabs never shows another pipeline's sample.
        -->
        @if (sample(); as thread) {
            <div class="mb-3">
                <!--
                    S4: the parse verb lives HERE, beside the chips it changes ("not parsed yet" →
                    "parsed · N cols"), not below the option tabs where its only feedback landed
                    silently on another tab. The shared editor hides its own button when the host owns
                    the sample, so there is exactly one.
                    (no backticks in this comment: it lives inside a template literal)
                -->
                <inspecto-sample-panel
                    [state]="thread"
                    parseLabel="Parse sample"
                    [parseDisabled]="!thread.sample()"
                    (parse)="parseSample()"
                >
                    <ng-container sampleActions>
                        <ng-container [ngTemplateOutlet]="csvActions" />
                    </ng-container>
                </inspecto-sample-panel>
            </div>
        }
        <!-- Name/Description live on the canvas inspector's rename affordance now, not here: this
             pane defines the Grammar, and the node's display identity is a graph concern. -->
        <!--
            D2 (redesign 2026-09-03): "which files this parse reads" is owned by the upstream
            Collector step now — this is a READ-ONLY display sourced from there, never an edit path.
        -->
        @if (collectorInclude(); as ci) {
            <p class="text-secondary m-0 mb-2 text-xs">
                Reads: <span class="font-mono">{{ ci.value || '(all files)' }}</span> — from the
                <span class="font-medium">{{ ci.source }}</span> collector step.
            </p>
        }
        <div class="space-y-1">
            <!--
                CSV export/import replaced "Save as template…" (U4): the file IS the portable
                template — options AND the columns table, Excel-editable, diff-friendly. Since the
                2026-08-22 operator ask the "Start from a stored Grammar" picker is gone too, so the
                three remaining ways to seed this surface (upload a sample · paste sample text ·
                import a Grammar CSV) are ONE icon row, projected into the sample panel above.
                Declared as a template because the sample panel is only mounted when the host keeps a
                thread — without one the icons render in this row instead, rather than vanishing.
            -->
            <ng-template #csvActions>
                @if (csvCapable()) {
                    @if (!readOnly()) {
                        <button
                            mat-icon-button
                            type="button"
                            aria-label="Import Grammar from CSV"
                            matTooltip="Import a Grammar CSV — known options and columns repopulate this surface"
                            (click)="csvInput.click()"
                        >
                            <mat-icon class="icon-size-5" svgIcon="heroicons_outline:document-arrow-up"></mat-icon>
                        </button>
                        <input
                            #csvInput
                            type="file"
                            accept=".csv,text/csv"
                            class="hidden"
                            aria-label="Import Grammar from CSV"
                            (change)="importCsv($event)"
                        />
                    }
                    <button
                        mat-icon-button
                        type="button"
                        aria-label="Export Grammar as CSV"
                        matTooltip="Export the whole property set as <pipeline>_parser.csv"
                        (click)="exportCsv()"
                    >
                        <mat-icon class="icon-size-5" svgIcon="heroicons_outline:document-arrow-down"></mat-icon>
                    </button>
                }
            </ng-template>
            @if (grammarMissing(); as missing) {
                <inspecto-alert
                    class="mb-2 mt-2 block"
                    variant="warning"
                    title="Grammar template missing"
                    data-testid="grammar-missing"
                >
                    This Step binds the Grammar template '{{ missing }}', which no longer exists. The settings below
                    start from a blank {{ frontend() }} Grammar; Apply writes them inline in place of the binding.
                </inspecto-alert>
            }
            <div class="mb-1 mt-2 flex items-center gap-2">
                <span class="text-xs font-semibold uppercase opacity-70">Grammar</span>
                @if (!sample()) {
                    <span class="ml-auto"></span>
                    <ng-container [ngTemplateOutlet]="csvActions" />
                }
            </div>
            @if (importWarning(); as warn) {
                <inspecto-alert class="mb-2 block" variant="warning" title="Imported with unknown options">
                    {{ warn }}
                </inspecto-alert>
            }
            <!--
                The generic plugin's own picker (P3d slice D). Unlike every other subtype, this node's
                TYPE does not name one served parser — it spans whichever ingestable, non-dedicated
                plugin the catalog serves, so the pane offers its own selector rather than unlocking the
                shared editor's built-in toggle (which would also expose the four built-in formats and
                every preview-only plugin).
            -->
            @if (frontend() === 'plugin') {
                <inspecto-option-picker
                    class="mb-2 block w-full"
                    label="Parser plugin"
                    [options]="pluginOptions()"
                    [ngModel]="plugin()?.id ?? null"
                    (ngModelChange)="pickPlugin($event)"
                    [readOnly]="readOnly()"
                    [help]="pluginChoices().length ? '' : 'No ingestable plugin is deployed on this server.'"
                />
            }
            <!--
                Decode Profile read-back: a profile-backed Pipeline's form is seeded with the profile's own
                values, and this list says where each effective value comes from. "set here" is exactly
                what Apply keeps — a value that came from the profile is never written into the Pipeline.
            -->
            @if (decodeProfileFile()) {
                <section class="mb-3" data-test="decode-profile" aria-labelledby="decode-profile-heading">
                    <div id="decode-profile-heading" class="mb-1 text-xs font-semibold uppercase opacity-70">
                        Decode Profile · <span class="font-mono normal-case">{{ decodeProfileFile() }}</span>
                    </div>
                    @if (profileError(); as err) {
                        <inspecto-alert variant="warning">
                            {{ err }} The fields below show the served defaults, not the profile's values.
                        </inspecto-alert>
                    } @else if (asn1Provenance().length) {
                        <table class="w-full table-fixed text-sm">
                            <caption class="sr-only">
                                Effective value of each ASN.1 key and where it comes from
                            </caption>
                            <thead class="sr-only">
                                <tr>
                                    <th scope="col">Key</th>
                                    <th scope="col">Effective value</th>
                                    <th scope="col">From</th>
                                </tr>
                            </thead>
                            <tbody>
                                @for (row of asn1Provenance(); track row.key) {
                                    <tr>
                                        <td class="w-1/3 truncate py-0.5 pr-2">{{ row.label }}</td>
                                        <td class="truncate py-0.5 pr-2 font-mono" [title]="provenanceText(row.value)">
                                            {{ provenanceText(row.value) }}
                                        </td>
                                        <td class="w-40 py-0.5 text-right">
                                            @switch (row.source) {
                                                @case ('here') {
                                                    <inspecto-chip variant="soft" tone="primary"
                                                        >set here</inspecto-chip
                                                    >
                                                }
                                                @case ('profile') {
                                                    <inspecto-chip variant="soft"
                                                        >from profile {{ decodeProfileName() }}</inspecto-chip
                                                    >
                                                }
                                                @default {
                                                    <inspecto-chip>default</inspecto-chip>
                                                }
                                            }
                                        </td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                    } @else {
                        <p class="text-secondary text-xs">Reading the Decode Profile…</p>
                    }
                    @if (derivedColumnRows().length) {
                        <div class="mb-1 mt-2 text-xs font-semibold uppercase opacity-70" id="derived-columns-heading">
                            Derived columns
                            <span class="normal-case font-normal"
                                >· {{ derivedColumnSummary() }}
                                @if (ownDeriveSet()) {
                                    · set by this Pipeline, replacing the profile's
                                }
                            </span>
                        </div>
                        <table
                            class="w-full table-fixed text-sm"
                            data-test="derived-columns"
                            aria-labelledby="derived-columns-heading"
                        >
                            <thead class="sr-only">
                                <tr>
                                    <th scope="col">Segment</th>
                                    <th scope="col">Column</th>
                                    <th scope="col">Operation</th>
                                </tr>
                            </thead>
                            <tbody>
                                @for (row of derivedColumnRows(); track row.segment + '.' + row.column) {
                                    <tr>
                                        <td class="w-1/4 truncate py-0.5 pr-2 font-mono">{{ row.segment }}</td>
                                        <td class="w-1/4 truncate py-0.5 pr-2 font-mono">{{ row.column }}</td>
                                        <td class="truncate py-0.5 font-mono" [title]="row.operation">
                                            {{ row.operation }}
                                        </td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                    }
                    @if (profileSegmentRows().length) {
                        <div class="mb-1 mt-2 text-xs font-semibold uppercase opacity-70" id="profile-segments-heading">
                            Profile segments
                            @if (ownSegmentsSet()) {
                                <span class="normal-case font-normal">· replaced by this Pipeline's own</span>
                            }
                        </div>
                        <table
                            class="w-full table-fixed text-sm"
                            data-test="profile-segments"
                            aria-labelledby="profile-segments-heading"
                        >
                            <thead class="sr-only">
                                <tr>
                                    <th scope="col">Segment key</th>
                                    <th scope="col">Schema</th>
                                </tr>
                            </thead>
                            <tbody>
                                @for (row of profileSegmentRows(); track row[0]) {
                                    <tr>
                                        <td class="w-1/3 truncate py-0.5 pr-2 font-mono">{{ row[0] }}</td>
                                        <td class="truncate py-0.5 font-mono" [title]="row[1]">{{ row[1] }}</td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                    }
                </section>
            }
            <inspecto-grammar-editor
                [initial]="seedBlock()"
                [type]="frontend() === 'plugin' ? pickedPluginId() : frontend()"
                [configuredIngester]="configuredIngesterFqcn()"
                [lockType]="true"
                [sampleMode]="sample() ? 'host' : 'own'"
                [sample]="sample()?.sample()?.text"
                [sampleBytes]="sample()?.sample()?.b64"
                [previewFn]="sample() ? previewFn : undefined"
                [readOnly]="readOnly()"
                (pluginChange)="plugin.set($event)"
                (previewed)="onPreviewed($event)"
                (treeNodeSelected)="onTreeNodeSelected($event)"
                (submitted)="submit()"
            >
                <!--
                    Segments need one schema .toon written per segment BEFORE the block that
                    references them, so the editor stays write-free and this is projected in — the
                    same contract the Onboarding Parsing stage uses. Shown only once the served parser
                    has resolved: without it there is no record tree to derive from.
                -->
                <div grammarExtras>
                    @if (authorsSegments()) {
                        <div class="mb-1 mt-3 flex items-center gap-2">
                            <span class="text-xs font-semibold uppercase opacity-70">Segments</span>
                            @if (segmentsLoading()) {
                                <span class="text-secondary text-xs">Loading the saved segment columns…</span>
                            }
                        </div>
                        <inspecto-segments-editor
                            [tree]="previewTree()"
                            [rows]="derivedRowSets()"
                            [initial]="initialSegments()"
                            (changed)="emitDirty()"
                        />
                    }
                    @if (frontend() === 'asn1') {
                        <section class="mt-4 space-y-3 border-t border-gray-200 pt-3 dark:border-gray-700" data-test="asn1-interactive-panel">
                            <div class="flex flex-wrap items-center gap-3">
                                <mat-checkbox
                                    data-test="asn1-raw-tlv-toggle"
                                    [checked]="rawTlvDump()"
                                    [disabled]="readOnly()"
                                    (change)="rawTlvDump.set($event.checked)"
                                >
                                    Raw TLV Dump
                                </mat-checkbox>
                                <mat-checkbox
                                    data-test="asn1-simplify-aliases-toggle"
                                    [checked]="simplifyAliases()"
                                    [disabled]="readOnly()"
                                    (change)="onSimplifyAliasesToggle($event.checked)"
                                >
                                    Simplify Aliases
                                </mat-checkbox>
                                <button
                                    mat-stroked-button
                                    type="button"
                                    data-test="asn1-validate-btn"
                                    [disabled]="validatingGrammar()"
                                    (click)="validateAsn1Grammar()"
                                >
                                    Validate grammar
                                </button>
                                <button
                                    mat-stroked-button
                                    type="button"
                                    data-test="create-populate-sink-btn"
                                    [disabled]="readOnly() || sinkPopulating()"
                                    (click)="createAndPopulateSink()"
                                >
                                    Create &amp; Populate Sink
                                </button>
                            </div>

                            @if (sinkStatus(); as st) {
                                <inspecto-alert [variant]="st.ok ? 'info' : 'warning'" data-test="sink-populate-status">
                                    {{ st.message }}
                                </inspecto-alert>
                            }

                            @if (grammarValidation(); as gv) {
                                <inspecto-alert
                                    [variant]="gv.valid ? 'info' : 'warning'"
                                    data-test="asn1-validation-diagnostics"
                                >
                                    @if (gv.valid && !gv.diagnostics.length) {
                                        Grammar compiled cleanly.
                                    } @else {
                                        <ul class="m-0 list-disc pl-4 text-xs">
                                            @for (d of gv.diagnostics; track $index) {
                                                <li>
                                                    <span class="font-semibold">{{ d.severity }}</span>
                                                    @if (d.line > 0) {
                                                        <span> ({{ d.line }}:{{ d.column }})</span>
                                                    }
                                                    : {{ d.message }}
                                                </li>
                                            }
                                        </ul>
                                    }
                                </inspecto-alert>
                            }

                            <!-- $header.* pin bar -->
                            <div class="rounded border border-gray-200 p-2 dark:border-gray-700" data-test="asn1-header-pin-bar">
                                <div class="mb-1 text-xs font-semibold uppercase opacity-70">$header.* Pin Bar</div>
                                <div class="flex flex-wrap items-center gap-1.5">
                                    @for (hdr of pinnedHeaders(); track hdr) {
                                        <span class="inline-flex items-center gap-1 rounded bg-gray-100 px-2 py-0.5 font-mono text-xs dark:bg-gray-800">
                                            {{ hdr }}
                                            @if (!readOnly()) {
                                                <button
                                                    type="button"
                                                    class="opacity-70 hover:opacity-100"
                                                    [attr.aria-label]="'Unpin ' + hdr"
                                                    (click)="unpinHeader(hdr)"
                                                >
                                                    ×
                                                </button>
                                            }
                                        </span>
                                    }
                                    @if (!readOnly()) {
                                        <input
                                            type="text"
                                            class="rounded border border-gray-300 px-2 py-0.5 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                            placeholder="$header.recordType"
                                            aria-label="Pin header field"
                                            data-test="asn1-header-pin-input"
                                            [value]="headerPinDraft()"
                                            (input)="headerPinDraft.set($any($event.target).value)"
                                            (keyup.enter)="pinHeader()"
                                        />
                                        <button mat-stroked-button type="button" class="!text-xs" (click)="pinHeader()">
                                            Pin
                                        </button>
                                    }
                                </div>
                            </div>

                            <!-- Event Roots router -->
                            <div class="rounded border border-gray-200 p-2 dark:border-gray-700" data-test="asn1-event-roots">
                                <div class="mb-1 flex items-center justify-between">
                                    <span class="text-xs font-semibold uppercase opacity-70">Event Roots</span>
                                    @if (!readOnly()) {
                                        <button mat-stroked-button type="button" class="!text-xs" (click)="addEventRoot()">
                                            Add event root
                                        </button>
                                    }
                                </div>
                                @for (er of eventRoots(); track $index; let i = $index) {
                                    <div class="mt-1 flex items-center gap-2">
                                        <input
                                            type="text"
                                            class="flex-1 rounded border border-gray-300 px-2 py-1 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                            placeholder="Event path (e.g. callEvent.moCall)"
                                            aria-label="Event root path"
                                            [readonly]="readOnly()"
                                            [ngModel]="er.path"
                                            (ngModelChange)="updateEventRoot(i, 'path', $event)"
                                        />
                                        <input
                                            type="text"
                                            class="w-40 rounded border border-gray-300 px-2 py-1 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                            placeholder="Segment key"
                                            aria-label="Event root segment"
                                            [readonly]="readOnly()"
                                            [ngModel]="er.segment"
                                            (ngModelChange)="updateEventRoot(i, 'segment', $event)"
                                        />
                                        @if (!readOnly()) {
                                            <button mat-icon-button type="button" aria-label="Remove event root" (click)="removeEventRoot(i)">
                                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:x-mark"></mat-icon>
                                            </button>
                                        }
                                    </div>
                                }
                            </div>

                            <!-- Leaf-only V-Decoder picker + Rename/Prefix/Postfix -->
                            @if (leafTreeNodes().length) {
                                <div class="rounded border border-gray-200 p-2 dark:border-gray-700" data-test="asn1-leaf-decoders">
                                    <div class="mb-1 text-xs font-semibold uppercase opacity-70">
                                        Leaf Value Decoders &amp; Naming
                                    </div>
                                    <div class="overflow-x-auto">
                                        <table class="w-full text-left text-xs">
                                            <thead>
                                                <tr>
                                                    <th class="px-1.5 py-1">Field Path</th>
                                                    <th class="px-1.5 py-1">V-Decoder</th>
                                                    <th class="px-1.5 py-1">Rename</th>
                                                    <th class="px-1.5 py-1">Prefix</th>
                                                    <th class="px-1.5 py-1">Postfix</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                @for (leaf of leafTreeNodes(); track leaf.path) {
                                                    <tr
                                                        [attr.data-test]="'leaf-row-' + leaf.path"
                                                        [class.bg-blue-50]="selectedTreePath() === leaf.path"
                                                        [class.dark:bg-blue-950]="selectedTreePath() === leaf.path"
                                                    >
                                                        <td class="px-1.5 py-1 font-mono">{{ leaf.path }}</td>
                                                        <td class="px-1.5 py-1">
                                                            <select
                                                                class="rounded border border-gray-300 px-1.5 py-0.5 text-xs dark:border-gray-600 dark:bg-transparent"
                                                                data-test="leaf-decoder-select"
                                                                [attr.aria-label]="'Decoder for ' + leaf.path"
                                                                [disabled]="readOnly()"
                                                                [ngModel]="leafOverrideOf(leaf.path).decoder ?? leaf.decoderId ?? ''"
                                                                (ngModelChange)="setLeafOverride(leaf.path, 'decoder', $event)"
                                                            >
                                                                <option value="">Default</option>
                                                                @for (dec of asn1Decoders(); track dec.id) {
                                                                    <option [value]="dec.id">{{ dec.id }} ({{ dec.sqlType }})</option>
                                                                }
                                                            </select>
                                                        </td>
                                                        <td class="px-1.5 py-1">
                                                            <input
                                                                type="text"
                                                                class="w-28 rounded border border-gray-300 px-1.5 py-0.5 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                                                data-test="leaf-rename-input"
                                                                placeholder="Rename"
                                                                [readonly]="readOnly()"
                                                                [ngModel]="leafOverrideOf(leaf.path).rename ?? ''"
                                                                (ngModelChange)="setLeafOverride(leaf.path, 'rename', $event)"
                                                            />
                                                        </td>
                                                        <td class="px-1.5 py-1">
                                                            <input
                                                                type="text"
                                                                class="w-20 rounded border border-gray-300 px-1.5 py-0.5 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                                                data-test="leaf-prefix-input"
                                                                placeholder="Prefix"
                                                                [readonly]="readOnly()"
                                                                [ngModel]="leafOverrideOf(leaf.path).prefix ?? ''"
                                                                (ngModelChange)="setLeafOverride(leaf.path, 'prefix', $event)"
                                                            />
                                                        </td>
                                                        <td class="px-1.5 py-1">
                                                            <input
                                                                type="text"
                                                                class="w-20 rounded border border-gray-300 px-1.5 py-0.5 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                                                data-test="leaf-postfix-input"
                                                                placeholder="Postfix"
                                                                [readonly]="readOnly()"
                                                                [ngModel]="leafOverrideOf(leaf.path).postfix ?? ''"
                                                                (ngModelChange)="setLeafOverride(leaf.path, 'postfix', $event)"
                                                            />
                                                        </td>
                                                    </tr>
                                                }
                                            </tbody>
                                        </table>
                                    </div>
                                </div>
                            }

                            <!-- SET_OF / SEQUENCE_OF Member Fields Table -->
                            @for (rep of repeatedTreeNodes(); track rep.path) {
                                <div class="rounded border border-gray-200 p-2 dark:border-gray-700" data-test="asn1-setof-table">
                                    <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
                                        <span class="font-mono text-xs font-semibold">{{ rep.path }} ({{ rep.type }})</span>
                                        <div class="flex items-center gap-2 text-xs">
                                            <label class="flex items-center gap-1">
                                                <input
                                                    type="radio"
                                                    [name]="'mode-' + rep.path"
                                                    value="join"
                                                    [disabled]="readOnly()"
                                                    [checked]="repeatedConfigOf(rep.path).mode === 'join'"
                                                    (change)="setRepeatedMode(rep.path, 'join')"
                                                />
                                                Join with parent
                                            </label>
                                            <label class="flex items-center gap-1">
                                                <input
                                                    type="radio"
                                                    [name]="'mode-' + rep.path"
                                                    value="fold"
                                                    [disabled]="readOnly()"
                                                    [checked]="repeatedConfigOf(rep.path).mode === 'fold'"
                                                    (change)="setRepeatedMode(rep.path, 'fold')"
                                                />
                                                Fold
                                            </label>
                                        </div>
                                    </div>

                                    @if (repeatedConfigOf(rep.path).mode === 'fold') {
                                        <div class="mb-2 flex flex-wrap items-center gap-2 text-xs" data-test="fold-delimiters">
                                            <label class="flex items-center gap-1">
                                                <input
                                                    type="checkbox"
                                                    [disabled]="readOnly()"
                                                    [checked]="repeatedConfigOf(rep.path).asJson"
                                                    (change)="setRepeatedFoldOption(rep.path, 'asJson', $any($event.target).checked)"
                                                />
                                                JSON
                                            </label>
                                            @if (!repeatedConfigOf(rep.path).asJson) {
                                                <input
                                                    type="text"
                                                    class="w-28 rounded border border-gray-300 px-1.5 py-0.5 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                                    placeholder="Item delimiter (,)"
                                                    aria-label="Fold item delimiter"
                                                    data-test="fold-item-delimiter"
                                                    [readonly]="readOnly()"
                                                    [ngModel]="repeatedConfigOf(rep.path).itemDelimiter"
                                                    (ngModelChange)="setRepeatedFoldOption(rep.path, 'itemDelimiter', $event)"
                                                />
                                                <input
                                                    type="text"
                                                    class="w-28 rounded border border-gray-300 px-1.5 py-0.5 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                                    placeholder="KV delimiter (=)"
                                                    aria-label="Fold key-value delimiter"
                                                    data-test="fold-kv-delimiter"
                                                    [readonly]="readOnly()"
                                                    [ngModel]="repeatedConfigOf(rep.path).kvDelimiter"
                                                    (ngModelChange)="setRepeatedFoldOption(rep.path, 'kvDelimiter', $event)"
                                                />
                                            }
                                        </div>
                                    }

                                    <table class="w-full text-left text-xs">
                                        <thead>
                                            <tr>
                                                <th class="px-1.5 py-1">Use</th>
                                                <th class="px-1.5 py-1">Member Field</th>
                                                <th class="px-1.5 py-1">Type</th>
                                                <th class="px-1.5 py-1">Group By</th>
                                                <th class="px-1.5 py-1">Aggregation</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            @for (m of rep.members; track m.name) {
                                                <tr [attr.data-test]="'member-row-' + m.name">
                                                    <td class="px-1.5 py-1">
                                                        <input
                                                            type="checkbox"
                                                            [disabled]="readOnly()"
                                                            [checked]="memberConfigOf(rep.path, m.name).use"
                                                            (change)="setMemberConfig(rep.path, m.name, 'use', $any($event.target).checked)"
                                                        />
                                                    </td>
                                                    <td class="px-1.5 py-1 font-mono">{{ m.name }}</td>
                                                    <td class="px-1.5 py-1 font-mono opacity-80">{{ m.type }}</td>
                                                    <td class="px-1.5 py-1">
                                                        <input
                                                            type="radio"
                                                            data-test="member-groupby-radio"
                                                            [name]="'groupby-' + rep.path"
                                                            [value]="m.name"
                                                            [disabled]="readOnly()"
                                                            [checked]="repeatedConfigOf(rep.path).groupBy === m.name"
                                                            (change)="setRepeatedGroupBy(rep.path, m.name)"
                                                        />
                                                    </td>
                                                    <td class="px-1.5 py-1">
                                                        <select
                                                            class="rounded border border-gray-300 px-1.5 py-0.5 text-xs dark:border-gray-600 dark:bg-transparent"
                                                            data-test="member-agg-select"
                                                            [disabled]="readOnly()"
                                                            [ngModel]="memberConfigOf(rep.path, m.name).agg"
                                                            (ngModelChange)="setMemberConfig(rep.path, m.name, 'agg', $event)"
                                                        >
                                                            @for (op of aggOps; track op) {
                                                                <option [value]="op">{{ op }}</option>
                                                            }
                                                        </select>
                                                    </td>
                                                </tr>
                                            }
                                        </tbody>
                                    </table>
                                </div>
                            }

                            <!-- Sink Table Preview vs Table Metadata tabs -->
                            @if (derivedRowSets(); as rowSets) {
                                @if (rowSets.length) {
                                    <div class="rounded border border-gray-200 p-2 dark:border-gray-700" data-test="sink-preview-panel">
                                        <div class="mb-2 flex flex-wrap items-center gap-2">
                                            <button
                                                mat-stroked-button
                                                type="button"
                                                data-test="tab-sink-preview"
                                                [class.!font-semibold]="sinkPreviewTab() === 'preview'"
                                                (click)="sinkPreviewTab.set('preview')"
                                            >
                                                Sink Table Preview
                                            </button>
                                            <button
                                                mat-stroked-button
                                                type="button"
                                                data-test="tab-table-metadata"
                                                [class.!font-semibold]="sinkPreviewTab() === 'metadata'"
                                                (click)="sinkPreviewTab.set('metadata')"
                                            >
                                                Table Metadata
                                            </button>
                                            @if (rowSets.length > 1) {
                                                <select
                                                    class="ml-auto rounded border border-gray-300 px-2 py-1 font-mono text-xs dark:border-gray-600 dark:bg-transparent"
                                                    aria-label="Select routed segment"
                                                    data-test="sink-rowset-select"
                                                    [ngModel]="selectedRowSetIndex()"
                                                    (ngModelChange)="selectRowSet(+$event)"
                                                >
                                                    @for (rs of rowSets; track rs.segment; let i = $index) {
                                                        <option [value]="i">{{ rs.segment }} ({{ rs.rowCount }})</option>
                                                    }
                                                </select>
                                            }
                                        </div>

                                        @if (activeDerivedRowSet(); as set) {
                                            @if (sinkPreviewTab() === 'preview') {
                                                <div class="overflow-x-auto" data-test="sink-table-preview">
                                                    <table class="w-full text-left text-xs">
                                                        <thead>
                                                            <tr>
                                                                @for (col of set.columns; track col) {
                                                                    <th class="px-2 py-1 font-mono">{{ col }}</th>
                                                                }
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            @for (row of set.rows; track $index) {
                                                                <tr>
                                                                    @for (col of set.columns; track col) {
                                                                        <td class="px-2 py-0.5 font-mono">{{ row[col] ?? 'NULL' }}</td>
                                                                    }
                                                                </tr>
                                                            }
                                                        </tbody>
                                                    </table>
                                                </div>
                                            } @else {
                                                <div class="overflow-x-auto" data-test="table-metadata-view">
                                                    <table class="w-full text-left text-xs">
                                                        <thead>
                                                            <tr>
                                                                <th class="px-2 py-1">Column</th>
                                                                <th class="px-2 py-1">SQL Type</th>
                                                                <th class="px-2 py-1">Source Path</th>
                                                                <th class="px-2 py-1">Decoder</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody>
                                                            @for (col of columnMetaRows(set); track col.name) {
                                                                <tr>
                                                                    <td class="px-2 py-0.5 font-mono">{{ col.name }}</td>
                                                                    <td class="px-2 py-0.5 font-mono">{{ col.sqlType }}</td>
                                                                    <td class="px-2 py-0.5 font-mono">{{ col.sourcePath }}</td>
                                                                    <td class="px-2 py-0.5 font-mono">{{ col.decoder }}</td>
                                                                </tr>
                                                            }
                                                        </tbody>
                                                    </table>
                                                </div>
                                            }
                                        }
                                    </div>
                                }
                            }
                        </section>
                    }
                </div>
                <!--
                    Output schema (P4-2a-ii), re-homed onto the "Types & columns" tab (§4.1 U3) —
                    projected into [tabTypes] so the delimited 4-tab surface shows the columns table
                    inside tab 2; untabbed formats render this below the flat options, as before.
                    Same two-hop write as segments: the toon lands before the node names it.
                -->
                <div tabTypes>
                    @if (authorsSchema()) {
                        <!--
                            R11 (parse-pane redesign): ONE table over raw.fields[] — the mockup's "Columns
                            that come out" (Use · # · Name · Type · Sample value · Also known as), rendered
                            by the shared columns table, which owns the heading, count and search box.
                            The metadata grid left this pane (D2: description / unit / classification belong
                            to Transform); a hydrated schema's metadata is carried through by selector from
                            the loaded rows instead — see withMetadata().
                        -->
                        <!--
                            §4.4 Data types as ONE property row (R11): the persisted concept is unchanged
                            (raw.types auto | declared); only the Auto/Declared toggle and the "Apply
                            suggested types" chip are gone — rows now seed from the detected types in BOTH
                            modes, so "declared" simply unlocks the type menu over that starting point.
                        -->
                        <inspecto-option-picker
                            class="mb-2 mt-3 block w-full"
                            label="Detect column types"
                            [options]="typesModeOptions"
                            [ngModel]="typesMode()"
                            (ngModelChange)="setTypesMode($event)"
                            [readOnly]="readOnly()"
                        />
                        @if (schemaLoading()) {
                            <p class="text-secondary m-0 mb-1 text-xs">Loading the saved schema…</p>
                        }
                        @if (schemaSeed().length) {
                            <inspecto-schema-fields-editor
                                [rows]="schemaSeed()"
                                [autoTypes]="typesMode() === 'auto'"
                                [sampleValues]="sampleValues()"
                                [filenameColumn]="filenameRow()"
                                [nameBasedSelectors]="
                                    frontend() === 'json' || frontend() === 'text_regex' || frontend() === 'xlsx'
                                "
                            />
                            <!-- Real states, kept — but BELOW the table so the table is the first thing
                                 under the heading (R11). -->
                            @if (schemaStale()) {
                                <p class="text-warn m-0 mt-2 text-sm" role="status" aria-live="polite">
                                    These columns came from an earlier test — the settings above no longer parse the
                                    sample. Fix them and test again, or Apply keeps the columns shown.
                                </p>
                            }
                            @if (schemaDrift(); as d) {
                                @if (d.drifted) {
                                    <div
                                        class="mt-2 flex flex-wrap items-center gap-2 text-sm"
                                        role="status"
                                        aria-live="polite"
                                    >
                                        <span class="text-secondary">
                                            This sample no longer matches the saved schema:
                                            {{ d.added.length }} new, {{ d.missing.length }} missing,
                                            {{ d.typeChanged.length }} changed type.
                                        </span>
                                        @if (d.added.length) {
                                            <button mat-stroked-button type="button" (click)="addDriftedFields()">
                                                Add {{ d.added.length }} new
                                            </button>
                                        }
                                        <!--
                                            S4/D7 — the third verb of the generate loop. Add-new can only
                                            append, and type changes are deliberately never applied, so a
                                            schema whose sample has genuinely moved on had NO way back to a
                                            derived one short of a CSV import. Destructive by nature, so the
                                            confirm names exactly what it replaces.
                                        -->
                                        <button mat-stroked-button type="button" (click)="rederiveSchema()">
                                            Re-derive from this sample
                                        </button>
                                    </div>
                                    @if (d.typeChanged.length) {
                                        <p class="text-secondary m-0 mt-1 text-xs">
                                            Type changes are reported, not applied — a saved type may be a deliberate
                                            override. Change them yourself if the sample is right:
                                            @for (t of d.typeChanged; track t.name) {
                                                <span class="font-mono">{{ t.name }}</span> ({{ t.declared }} →
                                                {{ t.suggested }})
                                                @if (!$last) {
                                                    <span>, </span>
                                                }
                                            }
                                        </p>
                                    }
                                }
                            }
                            <!--
                                BUILDER-1b. One pipeline has ONE output schema (pipeline_schema), and a save
                                that drops or narrows columns is refused by the BACKWARD gate. Changing a
                                pipeline's parse format legitimately does exactly that, so without this the
                                operator hit a wall with no way forward. compatibility:none is the write
                                route's own documented escape hatch — offered explicitly, never applied
                                automatically, because the gate exists to make the loss a decision.
                            -->
                            @if (schemaReplaceNeeded()) {
                                <inspecto-alert class="mt-3 block" variant="warning">
                                    <p class="m-0 text-sm">
                                        The saved output schema has columns this parse no longer produces, so it was not
                                        overwritten. Replacing it is the right move when you have changed what this
                                        pipeline reads — anything downstream that expects the old columns will need
                                        updating.
                                    </p>
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        class="!text-xs mt-2"
                                        [disabled]="writing() || readOnly()"
                                        (click)="replaceOutputSchema()"
                                    >
                                        Replace the output schema
                                    </button>
                                </inspecto-alert>
                            }
                        } @else if (!schemaLoading()) {
                            <p class="text-secondary m-0 text-sm">
                                Parse the sample above — the columns that come out are derived from it, never
                                hand-typed.
                            </p>
                        }
                    } @else if (foreignSchema()) {
                        <p class="text-secondary m-0 mt-3 text-sm">
                            This parser's schema file ({{ existingSchemaFile() }}) doesn't match the editor's naming
                            convention — author it in the pipeline TOON directly. Applying here leaves it untouched.
                        </p>
                    }
                    <!--
                        Source-filename lineage (operator ask 2026-08-22; R11 wording from the mockup):
                        output.filename_column lives on the SINK node, not this one — [filenameColumnTarget]
                        is null whenever the host cannot name a single sink unambiguously, so the field
                        simply does not render rather than guess. A blank commit clears the column. When on,
                        the columns table above shows the column as its read-only last row.
                    -->
                    @if (filenameColumnTarget(); as t) {
                        <div class="mb-3 mt-3">
                            <mat-checkbox
                                [checked]="!!t.value"
                                [disabled]="readOnly()"
                                (change)="onFilenameColumnToggle($event.checked)"
                            >
                                Add a column with the source file name
                            </mat-checkbox>
                            <!-- One value bound to the host's target, no form: the [ngModel] bridge. A committed
                                 change (Enter / blur) is the old input's change event. -->
                            <inspecto-pen-field
                                class="mt-1"
                                label="Column name"
                                ariaLabel="Source filename column"
                                placeholder="file_name"
                                [mono]="true"
                                [readOnly]="readOnly()"
                                [error]="filenameColumnError() ?? ''"
                                [ngModel]="t.value"
                                (ngModelChange)="onFilenameColumnBlur($event ?? '')"
                            ></inspecto-pen-field>
                            <!-- ONE short lineage hint (R11): the column is real in the WRITTEN rows but is
                                 not a parsed column — stamped at write by the sink, never saved into
                                 raw.fields[]. -->
                            <p class="text-secondary m-0 mt-1 text-xs">
                                Written by {{ t.target }} as a lineage column, stamped at write with each row's source
                                file — not one of the parsed columns. Unchecked = no column (lineage stays in the ledger
                                only).
                            </p>
                        </div>
                    }
                    <!--
                        Partitioning moved to the Sink pane (redesign S5, D4) — pure UI relocation, the
                        schema toon's partitions[] storage contract is untouched: this pane still holds
                        partitionSeed (read by loadSavedSchema) and still carries partitions[] through
                        the SAME schema write below (submitWithSchema) — only the editing CONTROL
                        moved. See PipelineConfigDefinitionComponent's own partitioning section, which
                        reads/writes the same schema toon's partitions[] key directly so an edit there
                        does not require reopening this pane.
                    -->
                </div>
            </inspecto-grammar-editor>
        </div>
    `,
})
export class PipelineParseDefinitionComponent {
    private configApi = inject(ConfigService);
    private parsersApi = inject(ParsersService);
    private confirm = inject(InspectoConfirmService);
    private datasetReg = inject(DatasetRegistrationService, { optional: true });
    private datasetsApi = inject(DatasetsService, { optional: true });
    private jobsApi = inject(JobsService, { optional: true });
    private destroyRef = inject(DestroyRef);
    private http = inject(HttpClient, { optional: true });

    /** The per-format parse node being defined (identity fixed; config editable — name/description carried verbatim). */
    readonly node = input.required<AuthoredNode>();
    readonly readOnly = input(false);

    /**
     * The format this node's type means — the editor is locked to it. Derived from the node type
     * rather than passed in, so the host cannot desync the two.
     */
    readonly frontend = computed<ParsingFrontend | 'asn1' | 'plugin'>(
        () => PARSE_NODE_FRONTENDS[this.node().type] ?? 'delimited',
    );

    /**
     * The pipeline this node belongs to — only used to name the segment schema toons, by the SAME
     * `<pipeline>_<segmentKey>` convention the Onboarding Parsing stage uses, so a stream onboarded
     * there and then edited here rewrites its own schemas instead of growing a second set.
     */
    readonly pipelineName = input('');

    /**
     * PARSE-HOME-1 (2026-09-06): the `grammar/<id>` this node binds when that template no longer exists —
     * the host resolves it, the Grammar section names it. The editor below is then seeded with a blank
     * Grammar of this node's frontend; Apply replaces the dangling binding with what the operator set.
     */
    readonly grammarMissing = input<string | null>(null);

    /**
     * The directory the open pipeline's own config file lives in, relative to the write root (`''` at the
     * root). Supplied by the host, which resolves it once per open pipeline — see
     * `PipelineEditorComponent.configSubdir` for why it is a host fact and why the server must not guess
     * it on a write.
     */
    readonly configSubdir = input('');

    /**
     * Where this pipeline's SATELLITE configs are read and written: its own config directory.
     *
     * <p>🔴 Every satellite call here used to pass nothing, so a write always landed at the write ROOT —
     * orphaning a duplicate schema (plus the `_mapping.csv` the server splits out beside it) for any
     * pipeline in a subdirectory, and leaving a root-level file that then WINS the read, so this drawer
     * edited a schema the engine never loads (BACKLOG SATELLITE-WRITE-1).
     *
     * <p>⚠ `undefined` rather than `''` when the pipeline sits at the root: a blank keeps the read's
     * server-side fallback scan (`resolveSatelliteForRead`) available, and keeps the param off the URL.
     * A non-blank subdir deliberately DISABLES that scan — the caller saying WHERE is never second-guessed.
     */
    protected satelliteSubdir(): string | undefined {
        return this.configSubdir().trim() || undefined;
    }

    /**
     * This tab's sample thread, or null when the host keeps none — in which case the grammar editor
     * falls back to its own sample box and nothing downstream sees the sample, exactly as before.
     * Passed in (never injected) because the thread belongs to the TAB, not to this pane.
     */
    readonly sample = input<DefinitionStateService | null>(null);

    /** The edited node, rebuilt by {@link submit} — the host applies it to the in-memory model. */
    readonly applied = output<AuthoredNode>();
    /** Whether the pane holds edits since creation / the last successful submit. */
    readonly dirtyChange = output<boolean>();

    /**
     * The pipeline's single qualifying output SINK, offered here so `output.filename_column` (source-
     * file lineage) can be set from the parse side (operator ask, 2026-08-22) — the field genuinely
     * lives on a DIFFERENT node than this pane edits, so the host resolves it the same way
     * `enrichmentHost` resolves the Stage-1 output: only when exactly one sink declares `database`.
     * `null` ⇒ ambiguous or no sink yet ⇒ the field does not render (a host passes nothing when the
     * fact is ambiguous, per the enrichment-wiring rule — never guess which sink the operator meant).
     */
    readonly filenameColumnTarget = input<{ value: string; target: string } | null>(null);

    /**
     * D2 (redesign 2026-09-03): the upstream Collector step's `include` pattern, for the read-only
     * "Reads: …" line above — the Parse pane no longer anchors "which files this parse reads", it only
     * displays what the Collector step declares. `null` ⇒ no single qualifying Collector node, in
     * which case the line does not render rather than guess (same rule as `filenameColumnTarget`).
     */
    readonly collectorInclude = input<{ value: string; source: string } | null>(null);
    /** Commits straight to the SINK node, bypassing this pane's own Apply/Discard — the same
     *  immediate-write precedent the canvas rename affordance already set for cross-node identity. */
    readonly filenameColumnChange = output<string | null>();
    /** Set only while the last edit failed the identifier pattern — cleared on a valid or blank commit. */
    readonly filenameColumnError = signal<string | null>(null);

    /** Commit (Enter / blur): blank clears the column, an invalid identifier is refused with the row's
     *  error line. */
    onFilenameColumnBlur(raw: string): void {
        if (this.readOnly()) return;
        const value = raw.trim();
        if (!value) {
            this.filenameColumnError.set(null);
            this.filenameColumnChange.emit(null);
            return;
        }
        if (!/^[A-Za-z][A-Za-z0-9_-]*$/.test(value)) {
            this.filenameColumnError.set('Must start with a letter — letters, digits, _ and - only.');
            return;
        }
        this.filenameColumnError.set(null);
        this.filenameColumnChange.emit(value);
    }

    /**
     * The checkbox half of the control (S4, redesign 2026-09-03): unchecking clears the column
     * (same as a blank commit); checking writes the DEFAULT name `file_name` unless a name is
     * already set (e.g. re-checking after only just having unchecked in this same session — the
     * name field's own value is authoritative, so a real edit is never clobbered by a stray toggle).
     */
    onFilenameColumnToggle(checked: boolean): void {
        if (this.readOnly()) return;
        if (!checked) {
            this.filenameColumnError.set(null);
            this.filenameColumnChange.emit(null);
            return;
        }
        const current = this.filenameColumnTarget()?.value.trim();
        this.filenameColumnError.set(null);
        this.filenameColumnChange.emit(current || 'file_name');
    }
    // U4: the `saveAsTemplate` output is gone — the Grammar CSV is the portable template now
    // (grammar templates are created in the Components registry; "Start from a template" stays).

    @ViewChild(GrammarEditorComponent) private editor?: GrammarEditorComponent;
    @ViewChild(InspectoSegmentsEditorComponent) private segmentsEditor?: InspectoSegmentsEditorComponent;
    @ViewChild(InspectoSchemaFieldsEditorComponent) private schemaGrid?: InspectoSchemaFieldsEditorComponent;

    /** The stored `partitions[]` of this node's schema toon — seeded by {@link loadSavedSchema}. */
    readonly partitionSeed = signal<SchemaPartitionRow[]>([]);
    /**
     * Every top-level key of the stored schema toon this pane does NOT model, carried verbatim
     * through the rewrite. The write is a whole-file overwrite, so any key not re-emitted is
     * destroyed — the partitions[] data-loss lesson, generalised to unknown/future keys.
     */
    private schemaExtras: Record<string, unknown> = {};

    /**
     * The three NAMES a stored schema declares, retained across an edit.
     *
     * <p>🔴 `mapping.canonicalName` is not decoration — `PipelineLift` reads it as the pipeline's declared
     * output **store** (falling back to the pipeline name), which is the join key a downstream job,
     * enrichment or Dataset matches its source against, and what `GET /pipelines` reports as `produces`.
     * `raw.name` is the RAW/source identity that `ConsignmentIngestor.resolvedSchema` matches a selector or
     * segment descriptor by. This pane used to rebuild all three from the schema FILE's name, so an Apply
     * silently renamed the store from `csv_example` to `csv_example_schema` and broke every downstream
     * binding — measured on a fresh boot: `produces` flipped with it. All eleven committed schemas
     * declare `canonicalName` = the PIPELINE name, and `raw.name` as a separate source identity
     * (`ORDERS`, `CALL`, `SITES`), so the file name was never the convention.
     */
    private schemaIdentity: { rawName?: string; canonicalName?: string; mappingRawName?: string } = {};

    // ── segments (asn1 / plugin) ─────────────────────────────────────────────────

    /**
     * The served parser the editor resolved, mirrored from `(pluginChange)`.
     *
     * ⚠ Mirrored, NOT read through the `@ViewChild` in the template: content projected INTO the editor
     * evaluates in THIS component's context and the query is unresolved on first render, so a template
     * read would decide "no segments editor" before the catalog ever arrived.
     */
    readonly plugin = signal<ParserDef | null>(null);

    // ── the plugin picker (P3d slice D) ──────────────────────────────────────────

    /**
     * The full served catalog, fetched independently of the shared editor's own copy — a stateless
     * read, the same tier as the segment-schema reads this pane already makes via `ConfigService`, not
     * the per-pane STATE service P2 forbids. Fetched separately (rather than reached through a
     * `@ViewChild` into the editor's own signal) so the picker's first paint does not depend on the
     * editor's view-init timing.
     */
    private readonly servedPlugins = signal<ParserDef[] | null>(null);

    /**
     * Ingestable plugins this generic node may pick, excluding every id already homed by its own
     * dedicated entry in {@link PARSE_NODE_FRONTENDS} (today, just `asn1`) — offering one of those here
     * would create a second authoring path to the same subtype.
     */
    readonly pluginChoices = computed<ParserDef[]>(() => {
        if (this.frontend() !== 'plugin') return [];
        const dedicated = new Set<string>(Object.values(PARSE_NODE_FRONTENDS));
        return (this.servedPlugins() ?? []).filter((p) => p.ingestable && p.ingesterClass && !dedicated.has(p.id));
    });

    /** The same choices in the shared picker's shape — the served label reads better as a hint. */
    readonly pluginOptions = computed<PickerOption[]>(() =>
        this.pluginChoices().map((p) => ({ value: p.id, label: p.label ?? p.id })),
    );

    /** The operator's manual pick, feeding the shared editor's `[type]`. Rehydration on load goes
     *  through `[configuredIngester]` instead, so the two paths never race each other. */
    readonly pickedPluginId = signal<string | null>(null);

    /** The FQCN already authored on this node's `parsing.plugin.ingester`, if any — rehydrates the
     *  editor's selection on load without marking it dirty (mirrors the editor's own contract). */
    readonly configuredIngesterFqcn = computed(() => {
        if (this.frontend() !== 'plugin') return '';
        const p = this.parsingBlock()['plugin'] as Record<string, unknown> | undefined;
        return typeof p?.['ingester'] === 'string' ? (p['ingester'] as string) : '';
    });

    pickPlugin(id: string): void {
        this.pickedPluginId.set(id);
    }

    /** The decoded record forest from the last Test parse — what "Derive from preview" proposes from. */
    readonly previewTree = signal<ParserTreeNode[] | null>(null);
    /** Derived RowSet[] from the last hierarchical Test parse (`preview.derived.segments`). */
    readonly derivedRowSets = signal<ParserRowSet[] | null>(null);
    readonly sinkPreviewTab = signal<'preview' | 'metadata'>('preview');
    readonly selectedRowSetIndex = signal(0);
    readonly activeDerivedRowSet = computed<ParserRowSet | null>(() => {
        const sets = this.derivedRowSets();
        if (!sets?.length) return null;
        return sets[this.selectedRowSetIndex()] ?? sets[0];
    });

    // ── ASN.1 interactive authoring state ────────────────────────────────────────
    readonly rawTlvDump = signal(false);
    readonly simplifyAliases = signal(false);
    readonly validatingGrammar = signal(false);
    readonly grammarValidation = signal<Asn1ValidationResult | null>(null);
    readonly asn1Decoders = signal<Asn1DecoderDef[]>([]);
    readonly pinnedHeaders = signal<string[]>([]);
    readonly headerPinDraft = signal('');
    readonly eventRoots = signal<{ path: string; segment: string }[]>([]);
    readonly leafOverrides = signal<Record<string, LeafOverride>>({});
    readonly repeatedConfigs = signal<Record<string, RepeatedNodeConfig>>({});
    readonly sinkPopulating = signal(false);
    readonly sinkStatus = signal<{ ok: boolean; message: string } | null>(null);
    readonly selectedTreePath = signal<string | null>(null);
    readonly sinkDatasetRows = signal<{ dataset: string; columns: string[]; rows: Record<string, unknown>[] } | null>(
        null,
    );
    readonly aggOps = ['keep', 'sum', 'min', 'max', 'mean', 'count'] as const;

    readonly leafTreeNodes = computed<LeafTreeEntry[]>(() => collectLeafTreeNodes(this.previewTree()));
    readonly repeatedTreeNodes = computed<RepeatedTreeEntry[]>(() => collectRepeatedTreeNodes(this.previewTree()));

    onTreeNodeSelected(node: ParserTreeNode): void {
        const path =
            typeof node.meta?.['fieldPath'] === 'string' && node.meta['fieldPath']
                ? String(node.meta['fieldPath'])
                : node.label;
        this.selectedTreePath.set(path || null);
    }

    selectRowSet(index: number): void {
        const sets = this.derivedRowSets();
        if (!sets?.length) return;
        const clamped = Math.max(0, Math.min(index, sets.length - 1));
        this.selectedRowSetIndex.set(clamped);
        this.bridgeRowSetToThread(sets[clamped], this.frontend());
    }

    private bridgeRowSetToThread(set: ParserRowSet | undefined, frontend: string): void {
        const thread = this.sample();
        if (!thread || !set) return;
        thread.parsePreview.set({
            frontend,
            columns: set.columns,
            rows: set.rows,
            rowCount: set.rowCount,
            rejectedRows: 0,
            columnTypes: columnTypesFromRowSet(set),
        });
        thread.schemaPreview.set(null);
        thread.schemaError.set(null);
    }

    onSimplifyAliasesToggle(checked: boolean): void {
        this.simplifyAliases.set(checked);
        this.asn1InteractiveDirty = true;
        this.emitDirty();
    }

    validateAsn1Grammar(): void {
        const asn1 = (this.editor?.grammar()['asn1'] ?? this.parsingBlock()['asn1'] ?? {}) as Record<string, unknown>;
        const grammarText = String(asn1['grammar'] ?? '');
        const rootType = String(asn1['root_type'] ?? '').trim() || undefined;
        this.validatingGrammar.set(true);
        this.parsersApi.validateGrammar(grammarText, rootType, this.simplifyAliases()).subscribe({
            next: (res) => {
                this.validatingGrammar.set(false);
                this.grammarValidation.set(res);
            },
            error: (e) => {
                this.validatingGrammar.set(false);
                this.grammarValidation.set({
                    valid: false,
                    diagnostics: [
                        { line: 0, column: 0, severity: 'ERROR', message: apiErrorMessage(e, 'Validation failed.') },
                    ],
                });
            },
        });
    }

    pinHeader(): void {
        const raw = this.headerPinDraft().trim();
        if (!raw) return;
        const normalized = raw.startsWith('$header.') ? raw : `$header.${raw}`;
        if (!this.pinnedHeaders().includes(normalized)) {
            this.pinnedHeaders.update((list) => [...list, normalized]);
            this.asn1InteractiveDirty = true;
            this.emitDirty();
        }
        this.headerPinDraft.set('');
    }

    unpinHeader(hdr: string): void {
        this.pinnedHeaders.update((list) => list.filter((h) => h !== hdr));
        this.asn1InteractiveDirty = true;
        this.emitDirty();
    }

    addEventRoot(): void {
        this.eventRoots.update((list) => [...list, { path: '', segment: '' }]);
        this.asn1InteractiveDirty = true;
        this.emitDirty();
    }

    updateEventRoot(index: number, field: 'path' | 'segment', value: string): void {
        this.eventRoots.update((list) => list.map((item, i) => (i === index ? { ...item, [field]: value } : item)));
        this.asn1InteractiveDirty = true;
        this.emitDirty();
    }

    removeEventRoot(index: number): void {
        this.eventRoots.update((list) => list.filter((_, i) => i !== index));
        this.asn1InteractiveDirty = true;
        this.emitDirty();
    }

    leafOverrideOf(path: string): LeafOverride {
        return this.leafOverrides()[path] ?? {};
    }

    setLeafOverride(path: string, field: keyof LeafOverride, value: string): void {
        this.leafOverrides.update((map) => {
            const cur = { ...(map[path] ?? {}) };
            if (value) cur[field] = value;
            else delete cur[field];
            return { ...map, [path]: cur };
        });
        this.asn1InteractiveDirty = true;
        this.emitDirty();
    }

    repeatedConfigOf(path: string): RepeatedNodeConfig {
        const map = this.repeatedConfigs();
        const resolved = this.resolveRepeatedKey(map, path);
        return (
            map[resolved] ?? {
                mode: 'join',
                asJson: false,
                itemDelimiter: ',',
                kvDelimiter: '=',
                members: {},
            }
        );
    }

    private updateRepeatedConfig(path: string, updater: (cur: RepeatedNodeConfig) => RepeatedNodeConfig): void {
        const { stripped } = this.stripRootPrefix(path, this.asn1RootPrefixes());
        this.repeatedConfigs.update((map) => {
            const cur = this.repeatedConfigOf(path);
            const nextMap = { ...map };
            if (stripped !== path) delete nextMap[stripped];
            nextMap[path] = updater(cur);
            return nextMap;
        });
        this.asn1InteractiveDirty = true;
        this.emitDirty();
    }

    setRepeatedMode(path: string, mode: 'join' | 'fold'): void {
        this.updateRepeatedConfig(path, (cur) => ({ ...cur, mode }));
    }

    setRepeatedFoldOption(path: string, field: 'asJson' | 'itemDelimiter' | 'kvDelimiter', value: boolean | string): void {
        this.updateRepeatedConfig(path, (cur) => ({ ...cur, [field]: value }));
    }

    setRepeatedGroupBy(path: string, memberName: string): void {
        this.updateRepeatedConfig(path, (cur) => ({ ...cur, groupBy: memberName }));
    }

    memberConfigOf(path: string, member: string): MemberFieldConfig {
        return this.repeatedConfigOf(path).members[member] ?? { use: true, agg: 'keep' };
    }

    setMemberConfig(path: string, member: string, field: 'use' | 'agg', value: boolean | string): void {
        this.updateRepeatedConfig(path, (cur) => {
            const curMember = cur.members[member] ?? { use: true, agg: 'keep' };
            return {
                ...cur,
                members: {
                    ...cur.members,
                    [member]: { ...curMember, [field]: value },
                },
            };
        });
    }

    columnMetaRows(set: ParserRowSet): { name: string; sqlType: string; sourcePath: string; decoder: string }[] {
        const meta = set.columnMeta ?? {};
        const types = (meta['columnTypes'] ?? {}) as Record<string, string>;
        const paths = (meta['sourcePaths'] ?? {}) as Record<string, string>;
        const decs = (meta['decoders'] ?? {}) as Record<string, string>;
        return set.columns.map((name) => ({
            name,
            sqlType: String(types[name] ?? 'VARCHAR'),
            sourcePath: String(paths[name] ?? '—'),
            decoder: String(decs[name] ?? '—'),
        }));
    }

    createAndPopulateSink(): void {
        const store = (this.pipelineName().trim() || this.node().id).replace(/[^A-Za-z0-9_-]/g, '_');
        const display = this.pipelineName().trim() || this.node().name || store;
        if (!this.datasetReg || !this.datasetsApi) {
            this.sinkStatus.set({ ok: false, message: `Dataset service unavailable — ${datasetManualHint(store)}` });
            return;
        }
        const rowSets = this.derivedRowSets();
        const segDrafts = this.segmentsEditor?.value() ?? [];
        const hasSegments = Boolean(rowSets?.length || segDrafts.length);
        const segmentKeys =
            rowSets?.length
                ? rowSets.map((r) => r.segment)
                : segDrafts.length
                  ? segDrafts.map((d) => d.key)
                  : [store];
        const targets = segmentKeys.map((segKey) => {
            const safeSeg = segKey.replace(/[^A-Za-z0-9_-]/g, '_');
            const id = segmentKeys.length === 1 && segKey === store ? store : `${store}_${safeSeg}`;
            const label = segmentKeys.length === 1 && segKey === store ? display : `${display} · ${segKey}`;
            const rowSet = rowSets?.find((r) => r.segment === segKey);
            const draft = segDrafts.find((d) => d.key === segKey);
            const physicalRef = hasSegments ? `${store}/database/${segKey}` : store;
            const columns = this.sinkDatasetColumns(rowSet, draft);
            const partitions = this.sinkDatasetPartitions(rowSet);
            return { segKey, id, label, rowSet, physicalRef, columns, partitions };
        });

        this.sinkPopulating.set(true);
        this.sinkStatus.set(null);
        this.sinkDatasetRows.set(null);

        forkJoin(
            targets.map((t) =>
                this.datasetReg!.ensure(t.id, t.label, {
                    physicalRef: t.physicalRef,
                    sourceName: store,
                    ...(t.columns.length ? { columns: t.columns } : {}),
                    ...(t.partitions.length ? { partitions: t.partitions } : {}),
                }),
            ),
        )
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (ensures) => {
                    const failed = ensures.find((r) => r.status === 'failed');
                    if (failed) {
                        this.sinkPopulating.set(false);
                        this.sinkStatus.set({
                            ok: false,
                            message: `Could not register dataset — ${datasetManualHint(failed.store)}`,
                        });
                        return;
                    }
                    forkJoin(targets.map((t) => this.datasetsApi!.materialize(t.id, `${t.id}_snapshot`)))
                        .pipe(
                            switchMap((runs) =>
                                this.jobsApi
                                    ? forkJoin(
                                          runs.map((r) =>
                                              r?.runId ? this.pollRunStatus(r.runId) : of<JobRunById | null>(null),
                                          ),
                                      ).pipe(map((statuses) => ({ runs, statuses })))
                                    : of({ runs, statuses: runs.map(() => null as JobRunById | null) }),
                            ),
                            takeUntilDestroyed(this.destroyRef),
                        )
                        .subscribe({
                            next: ({ runs, statuses }) => {
                                this.sinkPopulating.set(false);
                                const failedRun = statuses.find((s) => s?.status === 'FAILED' || s?.status === 'SKIPPED');
                                if (failedRun) {
                                    this.sinkStatus.set({
                                        ok: false,
                                        message:
                                            failedRun.message ||
                                            `Materialization ${failedRun.status.toLowerCase()} (run ${failedRun.runId}).`,
                                    });
                                    return;
                                }
                                const allDone = statuses.some((s) => s !== null) && statuses.every((s) => s?.status === 'SUCCESS');
                                const summary = targets
                                    .map((t, i) => `"${t.id}" (run ${runs[i]?.runId ?? 'ok'})`)
                                    .join(', ');
                                this.sinkStatus.set({
                                    ok: true,
                                    message: allDone
                                        ? `Registered & materialized ${targets.length} sink dataset(s): ${summary}.`
                                        : `Registered & materializing ${targets.length} sink dataset(s): ${summary}.`,
                                });
                                const primary = targets[this.selectedRowSetIndex()] ?? targets[0];
                                if (this.http && primary) {
                                    this.loadSinkDatasetRows(primary.id, primary.rowSet);
                                }
                            },
                            error: (e) => {
                                this.sinkPopulating.set(false);
                                this.sinkStatus.set({
                                    ok: false,
                                    message: apiErrorMessage(e, `Dataset(s) registered, but populate failed.`),
                                });
                            },
                        });
                },
                error: (e) => {
                    this.sinkPopulating.set(false);
                    this.sinkStatus.set({ ok: false, message: apiErrorMessage(e, 'Could not register sink dataset.') });
                },
            });
    }

    private sinkDatasetColumns(rowSet: ParserRowSet | undefined, draft: SegmentDraft | undefined): DatasetColumn[] {
        const metaCols: ColumnMeta[] = rowSet
            ? this.columnMetaRows(rowSet).map((c) => ({ name: c.name, type: sqlTypeToColumnType(c.sqlType) }))
            : draft
              ? [
                    ...draft.columns.map((c) => ({
                        name: effectiveColumnName(c),
                        type: sqlTypeToColumnType(c.type),
                    })),
                    ...(this.frontend() === 'asn1' || this.eventRoots().length > 0
                        ? [{ name: EVENT_TYPE_COLUMN, type: 'string' as ColumnType }]
                        : []),
                ]
              : [];
        return metaCols.length ? inferRoles(metaCols) : [];
    }

    private sinkDatasetPartitions(rowSet: ParserRowSet | undefined): SchemaPartitionRow[] {
        if (
            this.authorsSegments() ||
            this.frontend() === 'asn1' ||
            this.eventRoots().length > 0 ||
            rowSet?.columns.includes(EVENT_TYPE_COLUMN)
        ) {
            return [{ column: 'event_type', source: EVENT_TYPE_COLUMN, type: 'VARCHAR' }];
        }
        return this.partitionSeed();
    }

    private pollRunStatus(runId: string, attempt = 0): Observable<JobRunById | null> {
        if (!this.jobsApi) return of(null);
        return this.jobsApi.run(runId).pipe(
            switchMap((run) => {
                if ((run.status === 'PENDING' || run.status === 'RUNNING') && attempt < 10) {
                    return timer(300).pipe(switchMap(() => this.pollRunStatus(runId, attempt + 1)));
                }
                return of(run);
            }),
            catchError(() => of(null)),
        );
    }

    private loadSinkDatasetRows(datasetId: string, rowSet?: ParserRowSet): void {
        this.http
            ?.get<{
                columns?: (string | { name: string })[];
                rows?: Record<string, unknown>[];
            }>(apiUrl(`/datasets/${encodeURIComponent(datasetId)}/rows`))
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (res) => {
                    if (!res?.rows) return;
                    const cols = res.columns?.length
                        ? res.columns.map((c) => (typeof c === 'string' ? c : c.name))
                        : (rowSet?.columns ?? []);
                    this.sinkDatasetRows.set({ dataset: datasetId, columns: cols, rows: res.rows });
                },
                error: () => {
                    // Dataset rows preview is best-effort while materialize runs asynchronously.
                },
            });
    }

    // ── Output schema (P4-2a-ii) ─────────────────────────────────────────────────
    /** The last FLAT parse preview — the columns an output schema is derived from. */
    readonly previewTable = signal<ParserTablePreview | null>(null);
    /** Rows for the shared grid; a stable reference, since it rebuilds on identity change. */
    readonly schemaSeed = signal<SchemaFieldRow[]>([]);

    /**
     * The columns table's "Sample value" column (R11): the FIRST parsed row's value per column, keyed
     * the way this frontend's selectors address a column (position for delimited/fixedwidth, name
     * otherwise) so a hydrated schema's rows line up with the sample without a name match.
     */
    readonly sampleValues = computed<Record<string, string>>(() => {
        const p = this.previewTable();
        const row = p?.rows?.[0];
        if (!p || !row) return {};
        const out: Record<string, string> = {};
        p.columns.forEach((col, i) => {
            const v = row[col];
            if (v !== undefined && v !== null) out[deriveSelector(this.frontend(), i, col)] = String(v);
        });
        return out;
    });

    /**
     * The sink's source-filename column as the columns table's read-only last row (R11) — only while
     * the checkbox below is on. Its sample value is the captured sample's own file name when the tab
     * keeps a thread. ⛔ Never a `schemaSeed` row: it is stamped by the sink at write time.
     */
    readonly filenameRow = computed<{ name: string; sample: string } | null>(() => {
        const name = this.filenameColumnTarget()?.value.trim();
        if (!name) return null;
        return { name, sample: this.sample()?.sample()?.name ?? '' };
    });
    readonly schemaLoading = signal(false);
    /** A saved schema was read back, so a fresh parse must NOT re-derive over the operator's edits. */
    private readonly schemaHydrated = signal(false);

    // ── Data types: Auto / Declared (§4.4, U3; D2: Auto is the default for new parse steps) ──
    /** Persisted as `raw.types` on the schema companion; existing schemas without the marker load Declared. */
    readonly typesMode = signal<'auto' | 'declared'>('auto');
    /** The last parse's INFERRED types by column position, narrowed to the grid's vocabulary; null until
     *  a parse returns them (an old server never does — everything then behaves exactly as before). */
    readonly inferredTypes = signal<string[] | null>(null);
    /** The format each inferred date / date-with-a-time type was read with, by position (U3); null when not served. */
    private readonly inferredFormats = signal<(string | undefined)[] | null>(null);
    private typesModeTouched = false;

    /** The "Detect column types" property's two choices (R11) — the mockup's wording over `raw.types`. */
    readonly typesModeOptions: PickerOption[] = [
        { value: 'auto', label: 'Automatically from the sample' },
        { value: 'declared', label: 'I will set them myself' },
    ];

    setTypesMode(mode: 'auto' | 'declared'): void {
        if (mode === this.typesMode()) return;
        this.typesMode.set(mode);
        this.typesModeTouched = true;
        // Entering Auto with a fresh inference re-snapshots the icons; entering Declared keeps
        // whatever is shown — the detected types are already the rows' starting point (R11), so the
        // operator changes only what they disagree with.
        if (mode === 'auto') this.applyInferredToGrid();
        this.emitDirty();
    }

    /**
     * Stamp the inferred types onto the grid rows, matched by POSITION (delimited selectors are
     * 0-based positions — the same order the sniff's columns come back in). Types only: names,
     * include flags and synonyms are the operator's.
     */
    private applyInferredToGrid(): void {
        const inferred = this.inferredTypes();
        if (!inferred) return;
        const formats = this.inferredFormats();
        const grid = this.schemaGrid;
        if (grid) {
            for (const g of grid.fieldRows.controls) {
                const pos = Number(g.getRawValue()['selector']);
                if (Number.isInteger(pos) && inferred[pos] !== undefined) g.get('type')?.setValue(inferred[pos]);
            }
            grid.form.markAsDirty();
            // The grid does not model `format`, and re-seeding it would reset the person's edits: the stamped formats
            // are kept aside and applied on write (withMetadata, by selector) - U3, one type answer with its format.
            const stamped = new Map<string, string | undefined>();
            for (const g of grid.fieldRows.controls) {
                const pos = Number(g.getRawValue()['selector']);
                if (Number.isInteger(pos) && inferred[pos] !== undefined)
                    stamped.set(String(pos), stampedFormat(inferred[pos], formats?.[pos]));
            }
            this.stampedFormats = stamped;
        } else {
            this.schemaSeed.update((rows) =>
                rows.map((r) => {
                    const pos = Number(r.selector);
                    if (!Number.isInteger(pos) || inferred[pos] === undefined) return r;
                    const { format: _old, ...rest } = r;
                    const format = stampedFormat(inferred[pos], formats?.[pos]);
                    return { ...rest, type: inferred[pos], ...(format ? { format } : {}) };
                }),
            );
        }
    }

    /**
     * The format each row stamped from a parse while the grid was showing is read with, by selector (`undefined`: the
     * stamped type takes none); null when nothing was stamped since the seed was last derived. Applied over the seed's
     * own format on write ({@link withMetadata}).
     */
    private stampedFormats: Map<string, string | undefined> | null = null;

    /** A new seed (a parse, a load, an import): no stamped format outlives the rows it was stamped on. */
    private reseed(rows: SchemaFieldRow[]): void {
        this.stampedFormats = null;
        this.schemaSeed.set(rows);
    }
    /** How the saved schema differs from what THIS sample suggests (B3) — null until a parse runs. */
    readonly schemaDrift = signal<SchemaDrift | null>(null);

    readonly existingSchemaFile = computed(() => String(this.node().config?.['schema_file'] ?? '').trim());
    /**
     * The saved schema's optimistic-concurrency handle, paired with the name it was read from
     * (`CLIENT-HALVES-1` (a)).
     *
     * ⚠ This is what makes {@link schemaExtras}' carry-everything-forward rule safe. That Apply write
     * **replaces the whole file** and re-emits the unmodeled keys verbatim from the last read, so without
     * a precondition a concurrent edit to any of them is silently destroyed. ⛔ The name is part of the
     * handle on purpose: `schemaName()` can change between the read and the Apply, and a handle from a
     * different config refuses a valid save — strictly worse than sending none.
     */
    private schemaRead: { name: string; etag?: string } | null = null;

    private schemaName(): string {
        return companionSchemaName(this.pipelineName() || this.node().id, 'schema');
    }
    /**
     * A `schema_file` this editor did not write — hand-authored in the TOON; never touched here.
     *
     * <p>Compared by NAME, not by path: this pane writes the PORTABLE bare `<name>.toon` since W3,
     * but every config written before that carries `spaces/<space>/config/<name>.toon` and is just
     * as much ours. A path comparison would have declared every pre-W3 pipeline's own schema foreign
     * and quietly stopped maintaining it.
     */
    readonly foreignSchema = computed(
        () => this.existingSchemaFile() !== '' && schemaNameFromPath(this.existingSchemaFile()) !== this.schemaName(),
    );
    /**
     * Whether this node authors an output schema. Segment-authoring nodes (ASN.1 / plugin) carry their
     * schemas per segment instead, and a foreign `schema_file` is left alone — so this is the flat
     * formats, which is exactly what §4b's icon table listed "+ output schema" against.
     */
    readonly authorsSchema = computed(() => !this.authorsSegments() && !this.foreignSchema());

    /**
     * Test parse, with the result mirrored into the tab's sample thread so the strip's chips and every
     * downstream step see it. ⛔ It does NOT change WHERE the parse runs: the stateless
     * `POST /parsers/{id}/preview` the editor already used stays the request — (`POST /config/preview/parsing`,
     * `ConfigService.previewParsing`, takes a draft config and is called by the Configuration pane's Preview tab; this editor
     * holds a graph, not a config.)
     *
     * <p>⚠ It exists at all because the grammar editor's `previewed` output fires on SUCCESS only: a
     * failing re-parse would otherwise leave the previous "parsed · N cols" chip standing over a
     * grammar that no longer parses. The failure path is the reason this is a `previewFn` and not two
     * lines in {@link onPreviewed}.
     *
     * <p>⚠ Only a TABLE result feeds the thread. A tree (ASN.1 / plugin) leaves it untouched rather
     * than clearing it — the thread's parsed hop means "rows a downstream step can cast", and a record
     * tree is not that.
     */
    readonly previewFn = (
        type: string,
        grammar: Record<string, unknown>,
        text: string,
        b64?: string,
    ): Observable<ParserPreview> => {
        const thread = this.sample();
        thread?.parseError.set(null);
        // The Pipeline's own config dir travels, so a grammar_file ref previews with the spelling the
        // Pipeline resolves it by — beside itself (BUNDLE-ASN1-GRAMMAR-FILE-1).
        // A Decode Profile previews with only the keys this Pipeline overrides — what a save would persist.
        if (type === 'asn1' && grammar['asn1'] && typeof grammar['asn1'] === 'object' && this.editor) {
            const prior = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
            grammar = {
                ...grammar,
                asn1: decodeProfileOverrides(
                    grammar['asn1'] as Record<string, unknown>,
                    prior,
                    this.editor.dirtyKeys(),
                ),
            };
        }
        // PARSING-PREVIEW-DERIVED-1: `derive` / `children` (and the segment refs they name) are not grammar-form
        // fields, so the form never carries them; the saved block's own are sent so the preview can serve the rows
        // they load. A Pipeline that sets none sends none and previews exactly as before.
        if (type === 'asn1' && this.editor) {
            const prior = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
            const own = (grammar['asn1'] ?? {}) as Record<string, unknown>;
            const carried = deriveKeysToCarry(own, prior);
            const segKeys = this.segmentsEditor?.value().map((d) => d.key) ?? [];
            const interactive = this.compiledAsn1InteractiveConfig(segKeys);
            const mergedAsn1: Record<string, unknown> = { ...own, ...carried, ...interactive };
            if (this.rawTlvDump()) {
                delete mergedAsn1['grammar'];
                delete mergedAsn1['grammar_file'];
                delete mergedAsn1['root_type'];
            }
            if (this.simplifyAliases()) mergedAsn1['simplify_aliases'] = true;
            grammar = { ...grammar, asn1: mergedAsn1 };
        }
        return this.parsersApi.preview(type, grammar, text, b64, this.configSubdir().trim()).pipe(
            tap((p) => {
                if (p.kind === 'tree') {
                    this.syncTreeRowSets(p.derived?.segments ?? null, type);
                    return;
                }
                if (p.kind !== 'table') return;
                this.schemaStale.set(false); // these columns and these settings agree again
                if (!thread) return;
                thread.parsePreview.set({
                    frontend: type,
                    columns: p.columns,
                    rows: p.rows,
                    rowCount: p.rowCount,
                    rejectedRows: p.rejectedRows,
                    columnTypes: p.columnTypes,
                });
                // Re-parsing invalidates any cast checked against the old rows.
                thread.schemaPreview.set(null);
                thread.schemaError.set(null);
            }),
            catchError((e) => {
                // The grid still shows the columns of the LAST parse that worked; the settings under it
                // have since stopped parsing. Apply stays available (blocking it is the dead end
                // BUILDER-1a fixed) — but the schema must say it no longer describes these settings.
                if (this.schemaSeed().length) this.schemaStale.set(true);
                thread?.parsePreview.set(null);
                thread?.parseError.set(apiErrorMessage(e, 'The sample does not parse with these settings.'));
                return throwError(() => e);
            }),
        );
    };

    /**
     * The seeded columns outlived the parse that produced them — the last Test parse FAILED. Never
     * blocks anything; it exists so a builder cannot apply columns from a superseded grammar believing
     * the product still stands behind them.
     */
    readonly schemaStale = signal(false);

    /** Keep both halves of the discriminated preview: the tree feeds segments, the table feeds schema. */
    onPreviewed(p: ParserPreview): void {
        this.previewTree.set(p.kind === 'tree' ? p.nodes : null);
        this.syncTreeRowSets(p.kind === 'tree' ? (p.derived?.segments ?? null) : null, this.frontend());
        if (p.kind !== 'table') return;
        this.previewTable.set(p);
        // U3: keep the parse's inferred types (narrowed to the grid vocabulary), by position.
        // Backward-compatible: an old server serves no columnTypes and everything reads as before.
        this.inferredTypes.set(inferredSchemaTypes(p));
        this.inferredFormats.set(inferredSchemaFormats(p));
        // ⛔ Never re-derive over a schema read back from disk: the operator's saved names, types and
        // include flags are the truth, and a fresh sample would silently replace them on Apply.
        // Instead, ASK what changed — that is exactly the drift question (B3).
        if (this.schemaHydrated()) {
            // …except the TYPES in Auto mode, which are the sniffer's by definition (§4.4).
            if (this.typesMode() === 'auto') this.applyInferredToGrid();
            return this.checkDrift(p.rows);
        }
        // The derived schema is unapplied work — see {@link parsedSinceApply}. Set HERE and not in
        // `previewFn`, which only runs for a node that has a sample thread, and not on the drift
        // path above, which deliberately re-derives nothing.
        this.parsedSinceApply = true;
        this.emitDirty();
        // S4: make the derivation VISIBLE. It lands on the Types & columns tab, which on a tabbed
        // format is not the tab the operator is looking at — so the first one steers them there.
        if (!this.revealedSchema) {
            this.revealedSchema = true;
            this.editor?.showTab('types');
        }
        // R11: the detected types seed the rows in BOTH modes — Auto keeps them read-only, Declared
        // unlocks the menu over the same starting point (what "Apply suggested types" used to do).
        this.reseed(derivedSchemaRows(this.frontend(), p.columns, this.inferredTypes(), this.inferredFormats()));
    }

    /**
     * S4 — the parse verb, driven from the sample strip. The host already holds the editor's
     * `@ViewChild`, so this is a pass-through rather than a second parse path.
     */
    parseSample(): void {
        this.editor?.test();
    }

    /**
     * Whether the Types tab has already been revealed for this pane instance (S4). The FIRST parse to
     * derive a schema steers the operator to it — the derivation used to land there in silence. Every
     * later parse leaves the tab alone: yanking the view out from under someone who is editing another
     * tab is worse than the silence it would be fixing.
     */
    private revealedSchema = false;

    /** Segment drafts re-hydrated from the node's saved `asn1.segments`, keys AND columns. */
    readonly initialSegments = signal<SegmentDraft[]>([]);
    readonly segmentsLoading = signal(false);
    /** A segment-schema write is in flight — Apply is a server round-trip for an ASN.1 node. */
    readonly writing = signal(false);
    /**
     * The saved `<pipeline>_schema` refused this parse's columns under the BACKWARD gate — the state
     * a builder reaches by changing a pipeline's parse FORMAT (BUILDER-1b). Armed only by that exact
     * refusal, and cleared on every fresh attempt, so a stale banner can never offer a destructive
     * write against a different draft.
     */
    readonly schemaReplaceNeeded = signal(false);

    /**
     * Whether this node authors segments: an ASN.1 or generic-plugin node whose served parser has
     * resolved and can actually load to Tables. A preview-only parser has no ingester to feed, so
     * segments would be an elaborate way to author nothing.
     */
    readonly authorsSegments = computed(() => {
        const f = this.frontend();
        const p = this.plugin();
        return (f === 'asn1' || f === 'plugin') && !!p?.ingestable && !!p.ingesterClass;
    });

    /** The node's inline `parsing:` block, seeding (and re-seeding) the editor. */
    readonly parsingBlock = computed<Record<string, unknown>>(() => {
        const p = this.node().config?.['parsing'];
        return p && typeof p === 'object' && !Array.isArray(p) ? { ...(p as Record<string, unknown>) } : {};
    });

    /** A seed that replaces the node's own block until Apply — set by a Grammar CSV import. */
    private readonly templateBlock = signal<Record<string, unknown> | null>(null);

    /**
     * What the editor is seeded from: an imported copy, else the node's own block — with a Decode Profile's
     * own values under it once read back, so the form shows each key's EFFECTIVE value, not the default.
     */
    readonly seedBlock = computed<Record<string, unknown>>(
        () => this.templateBlock() ?? withDecodeProfile(this.parsingBlock(), this.profileSeed()),
    );

    // ── Decode Profile read-back (asn1.profile_file) ─────────────────────────────

    /** The `asn1.profile_file` this node names, as authored — `''` when none (or not an asn1 node). */
    readonly decodeProfileFile = computed(() => {
        if (this.frontend() !== 'asn1') return '';
        const a = this.parsingBlock()['asn1'];
        const f = a && typeof a === 'object' ? (a as Record<string, unknown>)['profile_file'] : undefined;
        return typeof f === 'string' ? f.trim() : '';
    });
    /** The profile's file name, for the per-key "from profile …" label (the full ref heads the section). */
    readonly decodeProfileName = computed(() => this.decodeProfileFile().split(/[\\/]/).pop() ?? '');
    /** The profile's own `asn1:` block (`GET /parsers/asn1/profile`), or null until read / on failure. */
    readonly profileValues = signal<Record<string, unknown> | null>(null);
    /** The same block, but only when it may re-seed the form — never over edits already made. */
    private readonly profileSeed = signal<Record<string, unknown> | null>(null);
    readonly profileError = signal<string | null>(null);
    /** The profile's own `segments`, as `[key, ref]` rows for the read-only list. */
    readonly profileSegmentRows = computed(() => Object.entries(profileSegmentsOf(this.profileValues())));
    /**
     * The derived columns in effect: this Pipeline's own `asn1.derive` when it sets one (the key is replaced
     * whole), else the profile's. Read-only (Parsing D9 slice 3).
     */
    readonly derivedColumnRows = computed(() => {
        const own = (this.parsingBlock()['asn1'] as Record<string, unknown> | undefined)?.['derive'];
        const ownRows = derivedColumnsOf(own);
        return ownRows.length ? ownRows : derivedColumnsOf(this.profileValues()?.['derive']);
    });
    /** Whether the Pipeline's own `asn1.derive` is the one in effect (it replaces the profile's whole). */
    readonly ownDeriveSet = computed(
        () =>
            derivedColumnsOf((this.parsingBlock()['asn1'] as Record<string, unknown> | undefined)?.['derive']).length >
            0,
    );
    /** "N derived columns on M segments". */
    readonly derivedColumnSummary = computed(() => {
        const rows = this.derivedColumnRows();
        const m = new Set(rows.map((r) => r.segment)).size;
        return `${rows.length} derived column${rows.length === 1 ? '' : 's'} on ${m} segment${m === 1 ? '' : 's'}`;
    });
    /** Whether the Pipeline's saved block carries its own `segments` (which replace the profile's whole). */
    readonly ownSegmentsSet = computed(() => Object.keys(this.savedSegmentPaths()).length > 0);
    /** The form's asn1 values and dirty keys, snapshotted on interaction (the editor exposes methods, not signals). */
    private readonly asn1Edit = signal<{ form: Record<string, unknown>; dirty: Set<string> } | null>(null);

    /** Each served `asn1` key's effective value and where it comes from — what Save would keep is "set here". */
    readonly asn1Provenance = computed<DecodeProfileRow[]>(() => {
        const profile = this.profileValues();
        const served = this.plugin();
        const file = this.decodeProfileFile();
        if (!file || !profile || served?.id !== 'asn1') return [];
        const keys = served.grammarSchema
            .filter((s) => s.path.startsWith('asn1.') && s.path !== 'asn1.profile_file')
            .map((s) => ({ key: s.path.slice('asn1.'.length), label: s.label, defaultValue: s.defaultValue }));
        const prior = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
        const edit = this.asn1Edit();
        const overrides = decodeProfileOverrides(
            { ...(edit?.form ?? prior ?? {}), profile_file: file },
            prior,
            edit?.dirty ?? new Set(),
        );
        return decodeProfileProvenance(keys, profile, overrides);
    });

    /** Display text for a provenance row's value — long module text is cut, the full value is its title. */
    protected provenanceText(v: unknown): string {
        if (v === undefined || v === null || (typeof v === 'string' && v.trim() === '')) return '—';
        return typeof v === 'object' ? JSON.stringify(v) : String(v);
    }

    private loadDecodeProfile(): void {
        this.profileValues.set(null);
        this.profileSeed.set(null);
        this.profileError.set(null);
        this.asn1Edit.set(null);
        const file = this.decodeProfileFile();
        if (!file) return;
        // `subdir` always sent ('' = a Pipeline at the root): the ref resolves beside the Pipeline, as it loads.
        this.parsersApi.decodeProfile(file, this.configSubdir().trim()).subscribe({
            next: (r) => {
                const block = r?.asn1 ?? {};
                this.profileValues.set(block);
                // Re-seeding `[initial]` rebuilds the form, so a late read must not land over edits.
                if (!this.editor?.isDirty()) this.profileSeed.set(block);
            },
            error: (e) => this.profileError.set(apiErrorMessage(e, 'Could not read the Decode Profile.')),
        });
    }

    private lastDirty = false;
    /**
     * A picked template is an EDIT, but re-seeding `[initial]` marks the editor pristine — so
     * `editor.isDirty()` reads false right after the pick and the drawer's Apply would stay disabled
     * on a real change. Tracked here instead of inferred from the editor.
     */
    private templateDirty = false;
    /**
     * 🔴 A successful Test parse is an EDIT of the pane's unapplied state: it derives the OUTPUT
     * SCHEMA, and Apply is what writes that schema and names it on the node. None of the other
     * dirty inputs see it — the schema grid is seeded PROGRAMMATICALLY (so its form stays pristine)
     * and the captured sample is not a form at all. Without this, a builder who pasted a sample,
     * ran the parse and got a full derived schema found **Apply greyed out**, and had to hand-edit
     * and blur a grid cell to persist work the product had already done for them (BUILDER-1a,
     * found by driving the real UI 2026-08-17).
     */
    private parsedSinceApply = false;
    private asn1InteractiveDirty = false;

    constructor() {
        // A stateless catalog fetch, same as the shared editor's own — used only to build the picker's
        // choice list, never to decide what gets applied (that stays `this.plugin()`, mirrored from the
        // editor's own resolution).
        this.parsersApi.list().subscribe({
            next: (list) => this.servedPlugins.set(list),
            error: () => this.servedPlugins.set([]),
        });

        // Seed from the node input. The host recreates this component per node (and on Discard), so
        // this runs once per instance — but an input swap without recreation re-seeds correctly too
        // (the [initial] binding re-seeds the editor, which is what marks it pristine again).
        effect(() => {
            this.node();
            // ⚠ untracked: an effect tracks every signal read synchronously in its body, INCLUDING through
            // the methods it calls — and `loadSavedSchema()` reaches `plugin()` via
            // authorsSchema→authorsSegments. So the seed re-ran when the served parser catalog landed (or
            // when the child editor emitted its plugin pick), reverting typed Name/Description, discarding
            // the chosen Grammar template, clearing derived schema rows and segment drafts, and then
            // calling markAsPristine + emitDirty — Apply greyed out over work just done. The node input is
            // the ONLY thing that should re-seed this pane.
            untracked(() => this.seedFromNode());
        });
    }

    /** The one-shot seed body — see the effect above for why it must not be tracked. */
    private seedFromNode(): void {
        {
            // A different node (or a Discard-driven re-seed) makes any imported seed stale — the
            // seed must fall back to the node's own block, or the previous node's copy lingers.
            this.templateBlock.set(null);
            this.templateDirty = false;
            // A manual plugin pick is the same kind of stale state — the FQCN carried on THIS node
            // rehydrates instead, through [configuredIngester].
            this.pickedPluginId.set(null);
            this.initialSegments.set([]);
            this.reseed([]);
            this.partitionSeed.set([]);
            this.schemaHydrated.set(false);
            this.schemaDrift.set(null);
            this.previewTable.set(null);
            this.derivedRowSets.set(null);
            this.grammarValidation.set(null);
            this.sinkStatus.set(null);
            this.sinkDatasetRows.set(null);
            this.selectedTreePath.set(null);
            const asn1Block = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
            const hasGrammar = Boolean(
                String(asn1Block?.['grammar'] ?? '').trim() ||
                    String(asn1Block?.['grammar_file'] ?? '').trim() ||
                    String(asn1Block?.['profile_file'] ?? '').trim(),
            );
            this.rawTlvDump.set(
                this.frontend() === 'asn1' && !hasGrammar && Object.keys(this.parsingBlock()).length > 0,
            );
            this.asn1InteractiveDirty = false;
            this.seedAsn1InteractiveState();
            this.emitDirty();
            this.loadSavedSegments();
            this.loadSavedSchema();
            this.deriveFromCarriedParse();
            this.loadDecodeProfile();
            this.loadAsn1Decoders();
        }
    }

    private loadAsn1Decoders(): void {
        if (this.frontend() !== 'asn1') return;
        this.parsersApi.decoders().subscribe({
            next: (list) => this.asn1Decoders.set(list ?? []),
            error: () => this.asn1Decoders.set([]),
        });
    }

    private syncTreeRowSets(rowSets: ParserRowSet[] | null, frontend: string): void {
        this.derivedRowSets.set(rowSets);
        if (!rowSets?.length) {
            this.selectedRowSetIndex.set(0);
            return;
        }
        const idx = Math.max(0, Math.min(this.selectedRowSetIndex(), rowSets.length - 1));
        this.selectedRowSetIndex.set(idx);
        this.bridgeRowSetToThread(rowSets[idx], frontend);
    }

    private seedAsn1InteractiveState(): void {
        const asn1 = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
        this.simplifyAliases.set(Boolean(asn1?.['simplify_aliases']));
        const headers = asn1?.['header_fields'];
        this.pinnedHeaders.set(
            Array.isArray(headers)
                ? headers
                      .map((h) => String(h).trim())
                      .filter(Boolean)
                      .map((h) => (h.startsWith('$header.') ? h : `$header.${h}`))
                : [],
        );
        const roots = asn1?.['event_roots'];
        if (Array.isArray(roots)) {
            this.eventRoots.set(
                roots
                    .filter((r): r is Record<string, unknown> => !!r && typeof r === 'object')
                    .map((r) => ({ path: String(r['path'] ?? ''), segment: String(r['segment'] ?? '') })),
            );
        } else if (roots && typeof roots === 'object') {
            this.eventRoots.set(
                Object.entries(roots as Record<string, unknown>).map(([path, segment]) => ({
                    path,
                    segment: String(segment ?? ''),
                })),
            );
        } else {
            this.eventRoots.set([]);
        }
        const fieldDecoders = asn1?.['field_decoders'];
        const legacyLeaves = asn1?.['leaf_overrides'];
        const seededLeaves: Record<string, LeafOverride> =
            legacyLeaves && typeof legacyLeaves === 'object' && !Array.isArray(legacyLeaves)
                ? { ...(legacyLeaves as Record<string, LeafOverride>) }
                : {};
        if (fieldDecoders && typeof fieldDecoders === 'object' && !Array.isArray(fieldDecoders)) {
            for (const [path, dec] of Object.entries(fieldDecoders as Record<string, unknown>)) {
                if (dec != null && String(dec).trim()) {
                    seededLeaves[path] = { ...(seededLeaves[path] ?? {}), decoder: String(dec).trim() };
                }
            }
        }
        this.leafOverrides.set(seededLeaves);
        const seededRepeated: Record<string, RepeatedNodeConfig> = {};
        const rep = asn1?.['repeated_fields'];
        if (rep && typeof rep === 'object' && !Array.isArray(rep)) {
            Object.assign(seededRepeated, rep as Record<string, RepeatedNodeConfig>);
        }
        const children = asn1?.['children'];
        if (children && typeof children === 'object' && !Array.isArray(children)) {
            for (const lists of Object.values(children as Record<string, unknown>)) {
                if (!lists || typeof lists !== 'object' || Array.isArray(lists)) continue;
                for (const [listPath, rawSpec] of Object.entries(lists as Record<string, unknown>)) {
                    seededRepeated[listPath] = repeatedConfigFromChildSpec(rawSpec);
                }
            }
        }
        const derive = asn1?.['derive'];
        if (derive && typeof derive === 'object' && !Array.isArray(derive)) {
            for (const cols of Object.values(derive as Record<string, unknown>)) {
                if (!cols || typeof cols !== 'object' || Array.isArray(cols)) continue;
                for (const rawSpec of Object.values(cols as Record<string, unknown>)) {
                    const parsed = repeatedConfigFromDeriveFold(rawSpec);
                    if (parsed) seededRepeated[parsed.listPath] = parsed.config;
                }
            }
        }
        this.repeatedConfigs.set(seededRepeated);
    }

    /**
     * A node with NO schema yet, opened over a table parse the tab's thread already holds (the Grammar
     * dialog's Test parse, or this pane's own before a Discard): derive the output schema from it exactly
     * as a fresh parse would — through {@link onPreviewed}, the ONE derivation — so Apply is live and
     * writes it. 🔴 Without this the Sample card read "parsed · 6 cols · 4 rows" over a DISABLED Apply:
     * the thread's parse is not a form edit, so nothing was dirty and the schema had no way to be created
     * short of pressing Parse sample again (found driving a new Pipeline, 2026-09-25).
     *
     * <p>Only when this pane authors the schema and none is named — a saved schema is re-read instead and
     * never re-derived over — and only for a parse of THIS pane's frontend.
     */
    private deriveFromCarriedParse(): void {
        if (!this.authorsSchema() || this.existingSchemaFile()) return;
        const p = this.sample()?.parsePreview();
        if (!p || p.frontend !== this.frontend()) return;
        this.onPreviewed({
            kind: 'table',
            columns: p.columns,
            rows: p.rows,
            rowCount: p.rowCount,
            rejectedRows: p.rejectedRows,
            columnTypes: p.columnTypes,
        });
    }

    /** The rows the grid currently holds — ALL of them, excluded ones included, so a merge never drops. */
    private currentSchemaRows(): SchemaFieldRow[] {
        const grid = this.schemaGrid;
        if (!grid) return this.schemaSeed();
        return grid.fieldRows.controls.map((g) => g.getRawValue() as SchemaFieldRow);
    }

    /**
     * Rows with the keys the columns table's form does NOT carry — `description` / `unit` /
     * `classification` (Catalog-facing, D1(b)) and a hand-authored `timezone_column` — carried through
     * by SELECTOR from the rows as loaded ({@link schemaSeed}). What persists and exports.
     *
     * <p>R11: the metadata grid that used to supply these left the Parse pane (they belong to
     * Transform), so without this a hydrated schema's metadata would be dropped on Apply and on CSV
     * export — the same silent-loss shape the grid's merge existed to prevent. A key already present on
     * the current row wins (the columns table's `value()` merges its seed by index the same way).
     */
    private withMetadata(rows: SchemaFieldRow[]): SchemaFieldRow[] {
        const bySelector = new Map(this.schemaSeed().map((r) => [String(r.selector), r]));
        return rows.map((r) => {
            const seed = bySelector.get(String(r.selector));
            if (!seed) return r;
            const out: SchemaFieldRow = { ...r };
            for (const k of CARRIED_FIELD_KEYS) {
                if ((out[k] === undefined || out[k] === '') && seed[k] !== undefined && seed[k] !== '')
                    out[k] = seed[k];
            }
            const sel = String(r.selector);
            if (this.stampedFormats?.has(sel)) {
                const f = this.stampedFormats.get(sel);
                if (f && (out.type === 'DATE' || out.type === 'TIMESTAMP')) out.format = f;
                else delete out.format;
            }
            return out;
        });
    }

    /**
     * Ask the server how the saved schema differs from what this sample now votes for (B3). Only for a
     * HYDRATED schema: a freshly-derived one was built from this very sample, so it cannot have drifted.
     */
    private checkDrift(rows: Record<string, unknown>[]): void {
        const fields = this.currentSchemaRows().map((r) => ({ name: r.name, selector: r.selector, type: r.type }));
        if (!fields.length) return;
        const mark = decimalMarkOf(this.editor?.value());
        this.configApi.suggestSchema(rows, { raw: { fields } }, this.frontend(), mark).subscribe({
            next: (s) => this.schemaDrift.set(s.drift ?? null),
            // Drift is advisory: failing to compute it must not disturb an otherwise working pane.
            error: () => this.schemaDrift.set(null),
        });
    }

    /**
     * Append the columns the sample has and the schema does not. ⚠ This is the ONLY half of §5.2's
     * "re-sync" that can be done without clobbering: a type change is NOT auto-applied because nothing
     * distinguishes a deliberate operator override from a stale derivation, and a missing field is not
     * auto-removed because the sample may simply be a narrow one. Both stay reported for a human call.
     */
    addDriftedFields(): void {
        const added = this.schemaDrift()?.added ?? [];
        if (!added.length) return;
        // The reseed rebuilds BOTH grids, so the metadata edits must ride the rows or they vanish.
        const current = this.withMetadata(this.currentSchemaRows());
        const columns = this.previewTable()?.columns ?? [];
        this.reseed([
            ...current,
            ...added.map((f) => {
                // The selector must address the column the way this frontend does — by POSITION for
                // delimited/fixedwidth — so it is derived from the sample's own column order.
                const i = columns.indexOf(f.name);
                return {
                    include: true,
                    name: sanitizeIdentifier(f.name, i < 0 ? current.length : i),
                    selector: deriveSelector(this.frontend(), i < 0 ? current.length : i, f.name),
                    type: f.type,
                };
            }),
        ]);
        this.schemaDrift.set(null);
        // ⚠ Adding derived columns IS unapplied work, and nothing else here says so: the drift path
        // returns before `parsedSinceApply` is set, and the shared grid rebuilds itself PRISTINE on the
        // new `schemaSeed` reference — so every term of the dirty expression was false and Apply stayed
        // greyed out over columns the product had just derived. The operator had to hand-edit and blur
        // a cell to keep them.
        this.parsedSinceApply = true;
        this.emitDirty();
    }

    /**
     * S4/D7 — throw the hydrated schema away and derive a fresh one from the sample now on screen.
     *
     * <p>Implementation is deliberately "clear the hydrated flag and re-run the parse", so the derive
     * branch of {@link onPreviewed} is the ONE place a schema is ever derived. A second derivation
     * here would be free to drift from it.
     *
     * <p>Destructive, and the confirm says so in full: names, types, synonyms and the column metadata
     * are all the operator's work, and none of it survives. The CSV import stays the file-based
     * wholesale path — this one is the sample-based one.
     */
    async rederiveSchema(): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            'The saved output schema is replaced by one derived from the sample now captured. Column names, ' +
                'declared types, synonyms and the description / unit / classification metadata are all lost.',
            { title: 'Re-derive the output schema?', confirmText: 'Re-derive' },
        );
        if (!ok) return;
        this.schemaHydrated.set(false);
        this.schemaDrift.set(null);
        this.revealedSchema = false; // the fresh derivation is worth revealing again
        this.parseSample();
    }

    /**
     * Read this node's saved output schema back, so re-opening a defined parser edits the schema that
     * exists rather than proposing a new one. Only for our own convention path — a hand-authored
     * `schema_file` is reported and left alone (the Onboarding stage's rule, ported).
     */
    private loadSavedSchema(): void {
        this.schemaExtras = {}; // a re-seed must not carry a previous node's stored keys
        this.schemaIdentity = {}; // …nor a previous node's declared names
        this.schemaRead = null; // …nor a previous node's concurrency handle, for the same reason
        if (!this.authorsSchema() || !this.existingSchemaFile()) return;
        this.schemaLoading.set(true);
        const readName = this.schemaName();
        this.configApi.read('schema', readName, this.satelliteSubdir()).subscribe({
            next: (r) => {
                this.schemaLoading.set(false);
                this.schemaRead = { name: readName, etag: r.etag };
                const raw = (r.config?.['raw'] ?? {}) as Record<string, unknown>;
                // Retain the unmodeled top-level keys BEFORE any early return, so a later Apply
                // (whose write replaces the whole file) carries them verbatim. `partitionKey` is
                // modeled too: it is migrated into partitions[] below, so re-emitting it verbatim
                // would resurrect the legacy spelling beside the current one.
                const extras = { ...(r.config ?? {}) } as Record<string, unknown>;
                delete extras['raw'];
                delete extras['mapping'];
                delete extras['partitions'];
                delete extras['partitionKey'];
                this.schemaExtras = extras;
                // Retain the declared names too, and BEFORE the no-fields early return below: the draft
                // must re-emit them rather than rebuild them from the file name (see `schemaIdentity`).
                const storedMapping = (r.config?.['mapping'] ?? {}) as Record<string, unknown>;
                this.schemaIdentity = {
                    rawName: declaredName(raw['name']),
                    canonicalName: declaredName(storedMapping['canonicalName']),
                    mappingRawName: declaredName(storedMapping['rawName']),
                };
                const fields = Array.isArray(raw['fields']) ? (raw['fields'] as Record<string, unknown>[]) : [];
                if (!fields.length) return;
                this.reseed(
                    fields.map((f) => ({
                        include: true,
                        name: String(f['name'] ?? ''),
                        selector: String(f['selector'] ?? ''),
                        type: String(f['type'] ?? 'VARCHAR'),
                        ...(f['synonym'] ? { synonym: String(f['synonym']) } : {}),
                        ...(f['description'] ? { description: String(f['description']) } : {}),
                        ...(f['unit'] ? { unit: String(f['unit']) } : {}),
                        ...(f['classification'] ? { classification: String(f['classification']) } : {}),
                    })),
                );
                // §4.4: the mode is the schema's own `raw.types` marker; a schema without one predates
                // the marker and loads as Declared — its stored types are the operator's.
                this.typesMode.set(raw['types'] === 'auto' ? 'auto' : 'declared');
                // The stored `partitions[]` (top-level, sibling of raw) — until 2026-08-22 the draft
                // this pane writes silently DROPPED it, hand-authored-only as it was. Read → edit →
                // rewrite is the round-trip that closes that hole.
                const partitions = Array.isArray(r.config?.['partitions'])
                    ? (r.config!['partitions'] as Record<string, unknown>[])
                    : [];
                const legacyKey = String(r.config?.['partitionKey'] ?? '').trim();
                this.partitionSeed.set(
                    partitions.length
                        ? partitions.map((p) => ({
                              column: String(p['column'] ?? ''),
                              source: String(p['source'] ?? ''),
                              type: String(p['type'] ?? 'VARCHAR')
                                  .toUpperCase()
                                  .replace('-', '_'),
                          }))
                        : legacyKey
                          ? // The legacy `partitionKey:` spelling — surfaced as the trio the engine
                            // synthesises from it (PartitionDef.fromSchema case 2), so a rewrite
                            // carries the same semantics forward in the current spelling.
                            [
                                { column: 'year', source: legacyKey, type: 'DATE_YEAR' },
                                { column: 'month', source: legacyKey, type: 'DATE_MONTH' },
                                { column: 'day', source: legacyKey, type: 'DATE_DAY' },
                            ]
                          : [],
                );
                this.schemaHydrated.set(true);
            },
            // A 404 is ordinary: the node names a schema whose file was never written. Deriving from
            // the next parse is exactly right there, so leave the seed alone and stay un-hydrated.
            error: () => this.schemaLoading.set(false),
        });
    }

    /**
     * `segment key → schema-toon path`, as stored in the node's `parsing.<asn1|plugin>.segments`.
     * Read through the shared {@link segmentPathsOf} so this walk and the delete cascade's cannot
     * drift — a frontend one of them knows and the other doesn't is how schema files get orphaned.
     */
    private savedSegmentPaths(): Record<string, string> {
        return segmentPathsOf({ parsing: this.parsingBlock() });
    }

    /**
     * Read each saved segment's schema toon back, so re-editing an existing pipeline does not force a
     * destructive re-derive. Per-segment and non-fatal: a failed read leaves that segment keys-only.
     * A 404 is expected and silent — a config may legitimately reference a schema never written.
     */
    private loadSavedSegments(): void {
        const paths = this.savedSegmentPaths();
        const keys = Object.keys(paths);
        if (keys.length === 0) return;
        const reads = keys.map((key) => {
            const name = schemaNameFromPath(paths[key]);
            if (!name) return of<SegmentDraft>({ key, columns: [] });
            return this.configApi.read('schema', name, this.satelliteSubdir()).pipe(
                map((r) => segmentDraftFrom(key, r.config)),
                catchError(() => of<SegmentDraft>({ key, columns: [] })),
            );
        });
        this.segmentsLoading.set(true);
        forkJoin(reads).subscribe((drafts) => {
            this.segmentsLoading.set(false);
            // The editor's `initial` setter REBUILDS the FormArray, so a late read landing over edits
            // the operator already made would silently discard them.
            if (this.segmentsEditor?.isDirty()) return;
            this.initialSegments.set(drafts);
        });
    }

    /** One schema toon per segment, named by the Onboarding Parsing stage's convention. */
    private schemaNameFor(segmentKey: string): string {
        return companionSchemaName(this.pipelineName() || this.node().id, segmentKey);
    }
    private schemaPathFor(segmentKey: string): string {
        return portableConfigRef(this.schemaNameFor(segmentKey));
    }

    /**
     * Dirty is derived on interaction, not streamed: the Grammar editor exposes `isDirty()` as a
     * method (no output), so the pane re-derives after any user input/click inside it and reports
     * transitions to the host — which is all the drawer's badge and close-guard need.
     */
    @HostListener('input')
    @HostListener('click')
    // 🔴 `document:click` is NOT redundant with the host `click`. A `type: 'select'` renders
    // `<inspecto-option-picker>`, which asks in a MatDialog — an overlay attached to document.body,
    // OUTSIDE this pane's subtree — so choosing an option never bubbles a click through the host and
    // `emitDirty()` never ran. Apply stayed greyed out over a choice the operator had just made, until
    // some unrelated click in the pane happened to re-derive it (measured in the preview: the pick
    // DOES dirty the form, so only the notification was missing). Any overlay-hosted control has the
    // same shape, so the listener is document-level rather than a guess at which event escapes;
    // `emitDirty` already returns early unless the value actually transitions, so the cost is a few
    // boolean reads per click, and the pane is only mounted while the drawer is open.
    @HostListener('document:click')
    onInteraction(): void {
        this.emitDirty();
    }

    protected emitDirty(): void {
        if (this.decodeProfileFile() && this.editor) {
            const form = this.editor.grammar()['asn1'];
            this.asn1Edit.set({
                form: form && typeof form === 'object' ? (form as Record<string, unknown>) : {},
                dirty: this.editor.dirtyKeys(),
            });
        }
        const dirty =
            this.templateDirty ||
            this.typesModeTouched ||
            this.parsedSinceApply ||
            this.asn1InteractiveDirty ||
            (this.editor?.isDirty() ?? false) ||
            (this.segmentsEditor?.isDirty() ?? false) ||
            (this.schemaGrid?.form.dirty ?? false);
        if (dirty === this.lastDirty) return;
        this.lastDirty = dirty;
        this.dirtyChange.emit(dirty);
    }

    /**
     * Validate and hand the block to the host to store as a template. Does NOT mark the pane pristine:
     * saving a template neither consumes the operator's unapplied edits nor persists them to the node,
     * so a dirty pane must stay dirty.
     */
    // ── Grammar CSV round-trip (§4.5, U4 — the portable template) ────────────────

    /** Only the BUILT-IN frontends round-trip as CSV; a plugin's options are served, not authored. */
    readonly csvCapable = computed(() => {
        const f = this.frontend();
        return f !== 'asn1' && f !== 'plugin';
    });
    /** Unknown option keys the last import listed — shown, never applied (§2's silent-drop trap). */
    readonly importWarning = signal<string | null>(null);

    /** Export the whole property set — options + columns — as `<pipeline>_parser.csv`. */
    exportCsv(): void {
        if (!this.csvCapable() || !this.editor) return;
        const frontend = this.frontend() as ParsingFrontend;
        const csv = grammarToCsv(
            {
                format: frontend,
                pipeline: this.pipelineName() || this.node().id,
                ...(this.authorsSchema() && this.schemaSeed().length ? { types: this.typesMode() } : {}),
            },
            parsingAttributesFor(frontend),
            flattenBlock(this.editor.value()),
            this.withMetadata(this.currentSchemaRows()),
        );
        downloadCsv(grammarCsvFilename(this.pipelineName() || this.node().id), csv);
    }

    /**
     * Import a Grammar CSV: refuse outright on a format mismatch, repopulate the options (the
     * template-pick mechanism, so the editor re-seeds), list unknown keys without applying them, and
     * replace the columns table WHOLESALE — behind a confirm when the current state is dirty.
     */
    async importCsv(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        await this.importCsvText(await file.text());
    }

    /** The import core, file-reading shell removed — what the specs drive. */
    async importCsvText(text: string): Promise<void> {
        if (!this.csvCapable()) return;
        const frontend = this.frontend() as ParsingFrontend;
        let parsed: ReturnType<typeof parseGrammarCsv>;
        try {
            parsed = parseGrammarCsv(text, parsingAttributesFor(frontend));
        } catch (e) {
            this.editor?.error.set(e instanceof Error ? e.message : 'Could not read the file as a Grammar CSV.');
            return;
        }
        if (parsed.meta.format !== frontend) {
            this.editor?.error.set(`That file is a '${parsed.meta.format}' Grammar — this node parses '${frontend}'.`);
            return;
        }
        if (parsed.columns && (this.lastDirty || this.schemaSeed().length)) {
            const ok = await this.confirm.confirm(
                `The file carries ${parsed.columns.length} column(s), which replace the current table wholesale.`,
                'Replace the columns table?',
            );
            if (!ok) return;
        }
        const block = nestKeys(parsed.options);
        block['frontend'] = frontend;
        this.templateBlock.set(block);
        this.templateDirty = true;
        if (parsed.columns) {
            this.reseed(parsed.columns);
            this.schemaHydrated.set(false);
        }
        if (parsed.meta.types) this.typesMode.set(parsed.meta.types);
        this.importWarning.set(
            parsed.unknownKeys.length
                ? `Not applied (the engine reads no such options): ${parsed.unknownKeys.join(', ')}`
                : null,
        );
        this.emitDirty();
    }

    /**
     * The asn1 form is SERVED — its fields exist only if `GET /parsers` returned the plugin. When it
     * did not (jar not deployed, catalog fetch failed), the schema form holds no `asn1.*` keys at
     * all, so building the block from it would write an EMPTY grammar over a deployed one and answer
     * "applied". Refuse instead, and say why: an unauthorable pane must not look like a successful save.
     */
    private asn1Unavailable(): boolean {
        if (this.frontend() !== 'asn1' || this.editor?.pluginDef()) return false;
        this.editor?.error.set(
            'The ASN.1 parser is not available from this server, so its grammar cannot be edited here.',
        );
        return true;
    }

    /**
     * The generic-plugin twin of {@link asn1Unavailable}: this node has no single served identity, so
     * "unavailable" covers both nothing picked yet and a served-but-not-ingestable pick (the catalog can
     * change between opens). Either way, Applying would write a hollow `plugin:` block over a deployed
     * one and must refuse rather than look like a successful save.
     */
    private pluginUnavailable(): boolean {
        if (this.frontend() !== 'plugin') return false;
        const p = this.plugin();
        if (p?.ingestable && p.ingesterClass) return false;
        this.editor?.error.set(
            this.pluginChoices().length
                ? 'Pick a parser plugin before applying.'
                : 'No ingestable parser plugin is deployed on this server, so this node cannot be edited here.',
        );
        return true;
    }

    /**
     * The `parsing:` block to persist. A built-in comes from the editor's own {@link
     * GrammarEditorComponent#value} (authored keys + cleared sibling roots + the frontend word). asn1
     * and plugin are SERVED parsers, not built-ins — `value()` would stamp the editor's internal
     * frontend (delimited) — so their blocks are assembled here from the schema-form's keys plus the
     * frontend word the node type means.
     *
     * <p>`segments` come from {@link segmentPaths} when this node authors them; otherwise the node's
     * own are carried VERBATIM — a submit that dropped them would silently turn an ingest-capable
     * config preview-only.
     */
    private compiledAsn1InteractiveConfig(segmentKeys: string[]): Record<string, unknown> {
        const out: Record<string, unknown> = {};
        if (this.simplifyAliases()) out['simplify_aliases'] = true;

        const headerFields = this.pinnedHeaders()
            .map((h) => (h.startsWith('$header.') ? h.slice('$header.'.length) : h).trim())
            .filter(Boolean);
        if (headerFields.length) out['header_fields'] = headerFields;

        const eventRootsMap: Record<string, string> = {};
        for (const r of this.eventRoots()) {
            const p = r.path.trim();
            const s = r.segment.trim();
            if (p && s) eventRootsMap[p] = s;
        }
        if (Object.keys(eventRootsMap).length) out['event_roots'] = eventRootsMap;

        const fieldDecoders: Record<string, string> = {};
        for (const [path, ov] of Object.entries(this.leafOverrides())) {
            if (ov.decoder?.trim()) fieldDecoders[path] = ov.decoder.trim();
        }
        if (Object.keys(fieldDecoders).length) out['field_decoders'] = fieldDecoders;

        const rootPrefixes = this.asn1RootPrefixes();
        const sortedEventRoots = Object.entries(eventRootsMap).sort((a, b) => b[0].length - a[0].length);
        const defaultParent = segmentKeys[0] ?? Object.values(eventRootsMap)[0] ?? rootPrefixes[0] ?? 'Record';
        const deriveMap: Record<string, Record<string, unknown>> = {};
        const childrenMap: Record<string, Record<string, unknown>> = {};
        const seen = new Set<string>();
        for (const [repPath, cfg] of Object.entries(this.repeatedConfigs())) {
            const { stripped, matchedRoot } = this.stripRootPrefix(repPath, rootPrefixes);
            let listPath = stripped;
            let parentSeg =
                (matchedRoot ? segmentKeys.find((k) => k.toLowerCase() === matchedRoot.toLowerCase()) : undefined) ??
                defaultParent;
            for (const [evPath, evSeg] of sortedEventRoots) {
                if (repPath.startsWith(`${evPath}.`)) {
                    parentSeg = evSeg;
                    listPath = repPath.slice(evPath.length + 1);
                    break;
                }
                if (stripped.startsWith(`${evPath}.`)) {
                    parentSeg = evSeg;
                    listPath = stripped.slice(evPath.length + 1);
                    break;
                }
            }
            const dedupeKey = `${parentSeg}::${listPath}`;
            if (seen.has(dedupeKey)) continue;
            seen.add(dedupeKey);

            const usedFields = Object.entries(cfg.members)
                .filter(([, m]) => m.use)
                .map(([name]) => name);
            const aggs: Record<string, string> = {};
            for (const [name, m] of Object.entries(cfg.members)) {
                if (m.use && m.agg && m.agg !== 'keep') aggs[name] = m.agg;
            }
            const leafName = listPath.split('.').pop() ?? listPath;
            const matchedChild = segmentKeys.find((k) => k !== parentSeg && k.toLowerCase() === leafName.toLowerCase());
            if (cfg.mode === 'fold' && !matchedChild) {
                const colName = sanitizeIdentifier(leafName, 0);
                (deriveMap[parentSeg] ??= {})[colName] = {
                    fold: listPath,
                    mode: cfg.asJson ? 'json' : 'delim',
                    ...(usedFields.length ? { fields: usedFields } : {}),
                    ...(cfg.groupBy ? { group_by: cfg.groupBy } : {}),
                    ...(Object.keys(aggs).length ? { aggs } : {}),
                    ...(!cfg.asJson ? { item_delim: cfg.itemDelimiter, field_delim: cfg.kvDelimiter } : {}),
                };
            } else if (matchedChild) {
                (childrenMap[parentSeg] ??= {})[listPath] = {
                    segment: matchedChild,
                    join_mode: 'left',
                    ...(cfg.mode === 'fold'
                        ? {
                              fold: cfg.asJson
                                  ? 'json'
                                  : { mode: 'delim', item_delim: cfg.itemDelimiter, field_delim: cfg.kvDelimiter },
                          }
                        : {}),
                    ...(usedFields.length ? { fields: usedFields } : {}),
                    ...(cfg.groupBy ? { group_by: cfg.groupBy } : {}),
                    ...(Object.keys(aggs).length ? { aggs } : {}),
                };
            }
        }
        if (Object.keys(deriveMap).length) out['derive'] = deriveMap;
        if (Object.keys(childrenMap).length) out['children'] = childrenMap;
        return out;
    }

    private asn1RootPrefixes(): string[] {
        const prefixes: string[] = [];
        const add = (raw: unknown): void => {
            const s = typeof raw === 'string' ? raw.trim() : '';
            if (s && !prefixes.includes(s)) prefixes.push(s);
        };
        const editorAsn1 = this.editor?.grammar()['asn1'] as Record<string, unknown> | undefined;
        const priorAsn1 = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
        add(editorAsn1?.['root_type']);
        add(priorAsn1?.['root_type']);
        for (const n of this.previewTree() ?? []) {
            add(typeof n.meta?.['fieldPath'] === 'string' ? n.meta['fieldPath'] : n.label);
            add(n.label);
        }
        return prefixes;
    }

    private stripRootPrefix(path: string, prefixes: string[]): { stripped: string; matchedRoot?: string } {
        for (const root of prefixes) {
            if (path.startsWith(`${root}.`)) {
                return { stripped: path.slice(root.length + 1), matchedRoot: root };
            }
        }
        return { stripped: path };
    }

    private resolveRepeatedKey(map: Record<string, RepeatedNodeConfig>, path: string): string {
        if (path in map) return path;
        const { stripped } = this.stripRootPrefix(path, this.asn1RootPrefixes());
        return stripped !== path && stripped in map ? stripped : path;
    }

    private applyLeafAffixesToDrafts(drafts: SegmentDraft[]): SegmentDraft[] {
        const overrides = this.leafOverrides();
        if (!Object.keys(overrides).length) return drafts;
        return drafts.map((d) => ({
            ...d,
            columns: d.columns.map((c) => {
                const ov =
                    overrides[c.selector] ??
                    Object.entries(overrides).find(([k]) => k.endsWith(`.${c.selector}`))?.[1];
                if (!ov) return c;
                return {
                    ...c,
                    ...(ov.rename?.trim() ? { rename: ov.rename.trim() } : {}),
                    ...(ov.prefix?.trim() ? { prefix: ov.prefix.trim() } : {}),
                    ...(ov.postfix?.trim() ? { postfix: ov.postfix.trim() } : {}),
                };
            }),
        }));
    }

    private parsingValue(segments?: Record<string, string>): Record<string, unknown> {
        const f = this.frontend();
        const base = this.editor!.value();
        if (f === 'plugin') {
            const prior = this.parsingBlock()['plugin'] as Record<string, unknown> | undefined;
            const block: Record<string, unknown> = { ...((base['plugin'] as Record<string, unknown> | undefined) ?? {}) };
            if (block['ingester_config'] === undefined && prior?.['ingester_config'] !== undefined) {
                block['ingester_config'] = prior['ingester_config'];
            }
            if (segments) block['segments'] = segments;
            else if (prior?.['segments'] !== undefined) block['segments'] = prior['segments'];
            return { ...base, frontend: 'plugin', plugin: block };
        }
        if (f !== 'asn1') return base;
        const prior = this.parsingBlock()['asn1'] as Record<string, unknown> | undefined;
        const segKeys = segments ? Object.keys(segments) : Object.keys((prior?.['segments'] as object | undefined) ?? {});
        const interactive = this.compiledAsn1InteractiveConfig(segKeys);
        const a: Record<string, unknown> = {
            ...((base['asn1'] as Record<string, unknown> | undefined) ?? {}),
            ...interactive,
        };
        if (segments) a['segments'] = segments;
        else if (prior?.['segments'] !== undefined) a['segments'] = prior['segments'];
        const dirty = new Set(this.editor!.dirtyKeys());
        for (const k of Object.keys(interactive)) dirty.add(`asn1${KEY_SEP}${k}`);
        return { ...base, frontend: 'asn1', asn1: decodeProfileOverrides(a, prior, dirty) };
    }

    /** `segment key → schema-toon path` for the drafts currently in the editor. */
    private segmentPaths(drafts: SegmentDraft[]): Record<string, string> {
        const paths: Record<string, string> = {};
        for (const d of drafts) paths[d.key] = this.schemaPathFor(d.key);
        return paths;
    }

    /**
     * Validate, rebuild the node with the Grammar in its inline `parsing:` home, emit. The frontend
     * key is stamped explicitly — it is what makes the file lift back to this node type — and the
     * editor is marked pristine because Apply consumed the edits.
     */
    submit(): void {
        if (!this.editor?.validate() || this.asn1Unavailable() || this.pluginUnavailable()) return;
        if (!this.authorsSegments()) return this.submitWithSchema();

        // Segments: write one schema toon per segment FIRST, then emit a block referencing them. Two
        // hops in this order because the config must never name a schema file that does not exist yet
        // — the Schema stage's rule, and Onboarding's `savePlugin` does exactly the same.
        const segments = this.segmentsEditor;
        // A profile-backed Pipeline with NO segments of its own inherits the profile's whole (D5), so an
        // empty editor is not "no segments" there: apply without a `segments` key. Only once the profile
        // has been read AND names segments — an unread or segment-less profile still meets the gate.
        if (
            segments &&
            segments.value().length === 0 &&
            this.frontend() === 'asn1' &&
            this.profileSegmentRows().length > 0
        ) {
            return this.applyWith(this.parsingValue());
        }
        if (!segments || !segments.validate()) {
            this.editor.error.set(segments?.problem() ?? 'Add at least one segment.');
            return;
        }
        const drafts = this.applyLeafAffixesToDrafts(segments.value());
        this.writing.set(true);
        forkJoin(
            drafts.map((d) =>
                this.configApi.write('schema', schemaDraftFor(d, this.schemaNameFor(d.key)), {
                    overwrite: true,
                    subdir: this.satelliteSubdir(),
                }),
            ),
        ).subscribe({
            next: () => {
                this.writing.set(false);
                segments.markPristine();
                this.applyWith(this.parsingValue(this.segmentPaths(drafts)));
            },
            error: (e) => {
                this.writing.set(false);
                // Nothing is applied: a node pointing at schemas that failed to write is the state
                // this ordering exists to prevent, and the pane stays dirty so the edits survive.
                this.editor?.error.set(apiErrorMessage(e, 'Could not save the segment schemas.'));
            },
        });
    }

    /**
     * The flat-format path: write the output schema toon FIRST, then emit a node naming it — the same
     * two-hop ordering segments use, for the same reason (the config must never name a file that does
     * not exist yet). A node with no schema grid in play applies straight through, so a parser can
     * still be defined before its schema is.
     */
    private submitWithSchema(replace = false): void {
        const grid = this.schemaGrid;
        // §4.4's "block Auto-save without a parse" guard is deliberately NOT here: with no derived
        // columns nothing is written at all (a parser may be defined before its schema — the
        // BUILDER-1a rule), and once columns exist they always came from a parse. The one residue —
        // an OLD server that serves no columnTypes — saves `types: auto` over VARCHAR, which honestly
        // records everything the sniff was able to say.
        if (!this.authorsSchema() || !grid || !this.schemaSeed().length) return this.applyWith(this.parsingValue());
        if (!grid.validate()) {
            this.editor?.error.set(grid.problem() ?? 'Fix the output schema before applying.');
            return;
        }
        // The editing CONTROL moved to the Sink pane (redesign S5, D4) — this pane no longer validates
        // or reads a live `InspectoSchemaPartitionsEditorComponent`, but the rows still travel through
        // this SAME write (before 2026-08-22 the draft carried raw+mapping only, so overwrite:true
        // silently DROPPED a hand-authored partitions[] on every Apply).
        // Stale-Apply race fix: re-read the schema immediately before writing so any partitions[]
        // or top-level extras written concurrently by the Sink pane survive this write.
        const fields = this.withMetadata(grid.value());
        const name = this.schemaName();
        // The declared names: what the stored schema says, else the PIPELINE name — never the schema
        // file's name, which is what silently renamed the output store (see `schemaIdentity`). The file
        // name survives only as the last resort, for a host that knows no pipeline (the custody dialog),
        // where emitting a blank would be worse than today's behaviour.
        const identity = this.pipelineName().trim() || name;
        const rawBlockName = this.schemaIdentity.rawName ?? identity;
        const canonicalName = this.schemaIdentity.canonicalName ?? identity;
        const mappingRawName = this.schemaIdentity.mappingRawName ?? rawBlockName;

        this.writing.set(true);
        this.schemaReplaceNeeded.set(false);
        // The ONE output-schema write, shared with the Grammar dialog's Save (`parse-output-schema.ts`).
        // It re-reads the schema immediately before writing (the stale-Apply race fix), so partitions[]
        // or top-level extras written concurrently by the Sink pane survive — the editing CONTROL moved
        // to the Sink pane (redesign S5, D4), but the rows still travel through this SAME write.
        writeParseSchema(this.configApi, {
            name,
            subdir: this.satelliteSubdir(),
            fields,
            rawName: rawBlockName,
            canonicalName,
            mappingRawName,
            typesMode: this.typesMode(),
            partitions: this.partitionSeed(),
            extras: this.schemaExtras,
            replace,
            // Only when the handle belongs to THIS schema — see `schemaRead`.
            ifMatch: this.schemaRead?.name === name ? this.schemaRead.etag : undefined,
        }).subscribe({
            next: (written) => {
                this.writing.set(false);
                // The handle moved with the content; keep it or the next Apply is refused as stale.
                this.schemaRead = { name, etag: written.etag };
                // ⚠ Clear the refusal this write just answered. Without it the BACKWARD message
                // ("schema edit is not BACKWARD-compatible; not written") stayed on screen after
                // the operator took the override and the write SUCCEEDED — the pane reporting a
                // failure for a schema it had just replaced.
                this.editor?.error.set('');
                grid.markPristine();
                this.applyWith(this.parsingValue(), portableConfigRef(name));
            },
            error: (e) => {
                this.writing.set(false);
                // Nothing is applied: a node naming a schema that failed to write is the state this
                // ordering exists to prevent, and the pane stays dirty so the edits survive.
                // A lost race is neither a bad draft nor a BACKWARD refusal: this write would have
                // replaced the whole file, including the unmodeled keys it carried from a now-stale
                // read, so the only safe advice is to reload first.
                this.editor?.error.set(
                    isStaleVersionError(e)
                        ? STALE_WRITE_MESSAGE
                        : apiErrorMessage(e, 'Could not save the output schema.'),
                );
                // …but a BACKWARD refusal is recoverable, so offer the override instead of a dead end.
                if (isBackwardRefusal(e)) this.schemaReplaceNeeded.set(true);
            },
        });
    }

    /**
     * Operator-confirmed override of the schema BACKWARD gate (BUILDER-1b) — the same write, with the
     * route's `compatibility: 'none'` escape hatch. Only reachable from the banner that a real refusal
     * armed, so this cannot silently replace a schema nobody was warned about.
     */
    replaceOutputSchema(): void {
        this.submitWithSchema(true);
    }

    /** Rebuild the node around the finished block, emit it, and consume the pane's edits. */
    private applyWith(block: Record<string, unknown>, schemaFile?: string): void {
        // `use` is dropped, never carried: this pane's whole contract is that the node owns its
        // Grammar inline. A node opened here BOUND to a `grammar/<id>` component is materialised into
        // an independent copy by Applying (D4) — one place decides that, and it is this one.
        // Name/Description are carried VERBATIM — renaming lives on the canvas inspector.
        const { use: _unbound, ...n } = this.node();
        const node: AuthoredNode = {
            ...n,
            config: { ...(n.config ?? {}), parsing: block, ...(schemaFile ? { schema_file: schemaFile } : {}) },
        };
        this.editor?.markPristine();
        this.templateDirty = false; // Apply consumed the pick, same as any other edit
        this.parsedSinceApply = false; // …and the derived schema it wrote
        this.typesModeTouched = false; // …and the mode switch, now persisted as raw.types
        this.asn1InteractiveDirty = false;
        this.emitDirty();
        this.applied.emit(node);
    }
}

/** The format a stamped type is read with: a date's or a date-with-a-time's own, else none (U3). */
function stampedFormat(type: string, format: string | undefined): string | undefined {
    return type === 'DATE' || type === 'TIMESTAMP' ? format : undefined;
}

/** The `raw.fields[]` keys the columns table's form does not model — see `withMetadata`. */
const CARRIED_FIELD_KEYS = ['description', 'unit', 'classification', 'timezone_column', 'format'] as const;

/**
 * The schema BACKWARD save-gate's 422, told apart from every other write failure. Matched on the
 * server's own phrase because the route returns no machine code for it; a wrong match here would
 * offer a destructive "replace" for an unrelated error, so it is deliberately narrow.
 */
/** A name a stored schema declares, or `undefined` when it declares none — never a blank string. */
function declaredName(v: unknown): string | undefined {
    const t = String(v ?? '').trim();
    return t || undefined;
}

function isBackwardRefusal(e: unknown): boolean {
    const err = e as { status?: number; error?: { error?: { message?: string } } };
    if (err?.status !== 422) return false;
    return /BACKWARD-compatible/i.test(err.error?.error?.message ?? '');
}

export interface LeafOverride {
    decoder?: string;
    rename?: string;
    prefix?: string;
    postfix?: string;
}

export interface MemberFieldConfig {
    use: boolean;
    agg: string;
}

export interface RepeatedNodeConfig {
    mode: 'join' | 'fold';
    groupBy?: string;
    asJson: boolean;
    itemDelimiter: string;
    kvDelimiter: string;
    members: Record<string, MemberFieldConfig>;
}

export interface LeafTreeEntry {
    path: string;
    label: string;
    decoderId?: string;
}

export interface RepeatedTreeEntry {
    path: string;
    type: string;
    members: { name: string; type: string }[];
}

function collectLeafTreeNodes(nodes: ParserTreeNode[] | null): LeafTreeEntry[] {
    if (!nodes?.length) return [];
    const seen = new Set<string>();
    const out: LeafTreeEntry[] = [];
    const walk = (list: ParserTreeNode[], prefix: string): void => {
        for (const n of list) {
            const path =
                typeof n.meta?.['fieldPath'] === 'string' && n.meta['fieldPath']
                    ? String(n.meta['fieldPath'])
                    : prefix
                      ? `${prefix}.${n.label}`
                      : n.label;
            if (n.meta?.['nodeRole'] === 'leaf') {
                if (!seen.has(path)) {
                    seen.add(path);
                    out.push({
                        path,
                        label: n.label,
                        decoderId: typeof n.meta['decoderId'] === 'string' ? n.meta['decoderId'] : undefined,
                    });
                }
            }
            if (n.children?.length) walk(n.children, path);
        }
    };
    walk(nodes, '');
    return out;
}

function collectRepeatedTreeNodes(nodes: ParserTreeNode[] | null): RepeatedTreeEntry[] {
    if (!nodes?.length) return [];
    const seen = new Set<string>();
    const out: RepeatedTreeEntry[] = [];
    const walk = (list: ParserTreeNode[], prefix: string): void => {
        for (const n of list) {
            const path =
                typeof n.meta?.['fieldPath'] === 'string' && n.meta['fieldPath']
                    ? String(n.meta['fieldPath'])
                    : prefix
                      ? `${prefix}.${n.label}`
                      : n.label;
            const role = n.meta?.['nodeRole'];
            const isRepeated = role === 'repeated' || /SET[_\s]OF|SEQUENCE[_\s]OF/i.test(n.type ?? '');
            if (isRepeated && !seen.has(path)) {
                seen.add(path);
                const members = extractMembers(n);
                out.push({ path, type: n.type ?? 'SEQUENCE_OF', members });
            }
            if (n.children?.length) walk(n.children, path);
        }
    };
    walk(nodes, '');
    return out;
}

function extractMembers(repeated: ParserTreeNode): { name: string; type: string }[] {
    const out: { name: string; type: string }[] = [];
    const seen = new Set<string>();
    for (const child of repeated.children ?? []) {
        const candidates = child.children?.length ? child.children : [child];
        for (const m of candidates) {
            if (!seen.has(m.label)) {
                seen.add(m.label);
                out.push({ name: m.label, type: m.type ?? 'VARCHAR' });
            }
        }
    }
    return out;
}

function columnTypesFromRowSet(set: ParserRowSet): { name: string; type: string }[] | undefined {
    const ct = set.columnMeta?.['columnTypes'];
    if (!ct) return undefined;
    if (Array.isArray(ct)) return ct as { name: string; type: string }[];
    if (typeof ct === 'object') {
        return Object.entries(ct as Record<string, string>).map(([name, type]) => ({ name, type }));
    }
    return undefined;
}

function sqlTypeToColumnType(sqlType: string): ColumnType {
    const t = sqlType.trim().toUpperCase();
    if (/^(BIGINT|INT|INTEGER|SMALLINT|TINYINT|HUGEINT|DOUBLE|FLOAT|REAL|DECIMAL|NUMERIC)/.test(t)) return 'number';
    if (/^(DATE|TIMESTAMP|TIMESTAMPTZ|TIME)/.test(t)) return 'date';
    if (/^(BOOL|BOOLEAN)/.test(t)) return 'boolean';
    return 'string';
}

function repeatedConfigFromSpec(mode: 'join' | 'fold', asJson: boolean, spec: Record<string, unknown>): RepeatedNodeConfig {
    const aggsRaw = spec['aggs'];
    const aggs =
        aggsRaw && typeof aggsRaw === 'object' && !Array.isArray(aggsRaw)
            ? (aggsRaw as Record<string, unknown>)
            : {};
    const members: Record<string, MemberFieldConfig> = {};
    if (Array.isArray(spec['fields'])) {
        for (const f of spec['fields']) {
            const name = String(f ?? '').trim();
            if (!name) continue;
            members[name] = { use: true, agg: aggs[name] != null ? String(aggs[name]) : 'keep' };
        }
    }
    for (const [k, v] of Object.entries(aggs)) {
        if (!(k in members)) members[k] = { use: true, agg: v != null ? String(v) : 'keep' };
    }
    const groupRaw = spec['group_by'];
    const groupBy =
        typeof groupRaw === 'string'
            ? groupRaw.trim() || undefined
            : Array.isArray(groupRaw) && groupRaw.length
              ? String(groupRaw[0] ?? '').trim() || undefined
              : undefined;
    const foldObj =
        spec['fold'] && typeof spec['fold'] === 'object' && !Array.isArray(spec['fold'])
            ? (spec['fold'] as Record<string, unknown>)
            : undefined;
    const itemDelimiter =
        foldObj?.['item_delim'] != null
            ? String(foldObj['item_delim'])
            : spec['item_delim'] != null
              ? String(spec['item_delim'])
              : ',';
    const kvDelimiter =
        foldObj?.['field_delim'] != null
            ? String(foldObj['field_delim'])
            : spec['field_delim'] != null
              ? String(spec['field_delim'])
              : '=';
    return {
        mode,
        ...(groupBy ? { groupBy } : {}),
        asJson,
        itemDelimiter,
        kvDelimiter,
        members,
    };
}

function repeatedConfigFromDeriveFold(raw: unknown): { listPath: string; config: RepeatedNodeConfig } | null {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
    const spec = raw as Record<string, unknown>;
    let listPath = '';
    let asJson = false;
    if (typeof spec['list'] === 'string' && spec['list'].trim()) {
        listPath = spec['list'].trim();
        asJson = String(spec['fold'] ?? '').trim().toLowerCase() === 'json';
    } else if (typeof spec['fold'] === 'string' && spec['fold'].trim()) {
        listPath = spec['fold'].trim();
        asJson = String(spec['mode'] ?? '').trim().toLowerCase() === 'json';
    } else {
        return null;
    }
    return { listPath, config: repeatedConfigFromSpec('fold', asJson, spec) };
}

function repeatedConfigFromChildSpec(raw: unknown): RepeatedNodeConfig {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
        return { mode: 'join', asJson: false, itemDelimiter: ',', kvDelimiter: '=', members: {} };
    }
    const spec = raw as Record<string, unknown>;
    let mode: 'join' | 'fold' = 'join';
    let asJson = false;
    const foldRaw = spec['fold'];
    if (typeof foldRaw === 'string') {
        mode = 'fold';
        asJson = foldRaw.trim().toLowerCase() === 'json';
    } else if (foldRaw && typeof foldRaw === 'object' && !Array.isArray(foldRaw)) {
        mode = 'fold';
        asJson = String((foldRaw as Record<string, unknown>)['mode'] ?? '').trim().toLowerCase() === 'json';
    } else if (typeof spec['mode'] === 'string') {
        mode = 'fold';
        asJson = spec['mode'].trim().toLowerCase() === 'json';
    }
    return repeatedConfigFromSpec(mode, asJson, spec);
}
