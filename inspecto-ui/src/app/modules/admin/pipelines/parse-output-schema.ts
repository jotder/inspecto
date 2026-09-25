import { Observable, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { ConfigService, ConfigWriteResult, ParserTablePreview } from 'app/inspecto/api';
import { SchemaFieldRow, deriveSelector, narrowToSchemaType, sanitizeIdentifier } from 'app/inspecto/schema';

/**
 * The Parse step's OUTPUT SCHEMA — derived from a table test parse and written as the companion
 * `<pipeline>_schema.toon` the parse node's `schema_file` names. ONE derivation and ONE write, shared by
 * the two surfaces that configure a parse node: the Parse drawer's Apply
 * (`PipelineParseDefinitionComponent.submitWithSchema`) and the Grammar dialog's Save.
 *
 * <p>🔴 It exists because the dialog's Save used to patch the node in memory ONLY (found driving a new
 * Pipeline, 2026-09-25): the builder test-parsed there, saved, and was left with "Schema: Not
 * configured", an Activate refused on Schema, and a drawer whose Apply was disabled — no visible way to
 * create the schema. A second copy of the write in the dialog would drift from this one (the
 * SCHEMA-FILE-NAME-1 `file:` naming and the partitions carry-through are both in here for a reason).
 */

/** The parse's inferred types by column position, narrowed to the grid vocabulary; null when not served. */
export function inferredSchemaTypes(p: Pick<ParserTablePreview, 'columnTypes'>): string[] | null {
    return p.columnTypes ? p.columnTypes.map((ct) => narrowToSchemaType(ct.type)) : null;
}

/** One output-schema row per parsed column: an identifier-safe name, this frontend's selector, the type. */
export function derivedSchemaRows(
    frontend: string,
    columns: readonly string[],
    inferred: readonly string[] | null,
): SchemaFieldRow[] {
    return columns.map((col, i) => ({
        include: true,
        name: sanitizeIdentifier(col, i),
        selector: deriveSelector(frontend, i, col),
        type: inferred?.[i] ?? 'VARCHAR',
    }));
}

/** Everything one output-schema write needs — what the Parse pane knows about the schema it holds. */
export interface ParseSchemaWrite {
    /** The companion schema's config name (`<pipeline>_schema`) — also the FILE the node names. */
    name: string;
    subdir?: string;
    fields: SchemaFieldRow[];
    rawName: string;
    canonicalName: string;
    mappingRawName: string;
    typesMode: 'auto' | 'declared';
    /** The partitions / unmodelled top-level keys as last read — used only when the re-read finds no file. */
    partitions: unknown[];
    extras: Record<string, unknown>;
    /** The BACKWARD-gate override (`compatibility: 'none'`) — only ever after a real refusal. */
    replace?: boolean;
    ifMatch?: string;
}

/**
 * Re-read the schema, then write it over itself (`overwrite: true`). The re-read is the stale-Apply race
 * fix: partitions[] or top-level extras written meanwhile by the Sink pane survive this write, which
 * replaces the whole file. A failed read falls back to the caller's own seed.
 */
export function writeParseSchema(configApi: ConfigService, w: ParseSchemaWrite): Observable<ConfigWriteResult> {
    return configApi.read('schema', w.name, w.subdir).pipe(
        catchError(() => of(null)),
        switchMap((r) => {
            let partitions = w.partitions;
            let extras = w.extras;
            if (r?.config) {
                const cfg = r.config as Record<string, unknown>;
                if (Array.isArray(cfg['partitions'])) partitions = cfg['partitions'];
                const latest = { ...cfg };
                delete latest['raw'];
                delete latest['mapping'];
                delete latest['partitions'];
                delete latest['partitionKey'];
                extras = latest;
            }
            const draft = {
                ...extras,
                ...(Array.isArray(partitions) && partitions.length ? { partitions } : {}),
                raw: {
                    name: w.rawName,
                    format: 'CSV',
                    // §4.4: the Auto/Declared marker rides the schema companion (additive, ETL-ignored);
                    // in Auto the written types ARE the inferred snapshot — declared = inferred by
                    // construction, so downstream stays deterministic.
                    types: w.typesMode,
                    fields: w.fields.map((f) => ({
                        name: f.name,
                        selector: f.selector,
                        type: f.type,
                        ...(f.synonym ? { synonym: f.synonym } : {}),
                        ...(f.description ? { description: f.description } : {}),
                        ...(f.unit ? { unit: f.unit } : {}),
                        ...(f.classification ? { classification: f.classification } : {}),
                    })),
                },
                mapping: {
                    canonicalName: w.canonicalName,
                    rawName: w.mappingRawName,
                    // MAPPING-GEN-1 (2026-09-10): the Record Transformer field list, like every committed
                    // schema and the two server generators — never the legacy rules[] the engine reads only
                    // through a bridge. `fn: keep` is the marker RecordTransform.isFieldList keys on.
                    fields: w.fields.map((f) => ({ name: f.name, from: f.name, fn: 'keep' })),
                },
            };
            return configApi.write('schema', draft, {
                overwrite: true,
                // 🔴 SCHEMA-FILE-NAME-1: the FILE is the one the node's `schema_file` will name. The server
                // otherwise names a schema's file by `raw.name` — which SCHEMA-NAME-1 rightly keeps as the
                // pipeline/source identity — so every Apply wrote `<pipeline>.toon` while the node it
                // Applied referenced `<pipeline>_schema.toon`: a pipeline that saved, validated and
                // activated clean, then never loaded ("Schema file not found").
                file: w.name,
                subdir: w.subdir,
                ...(w.replace ? { compatibility: 'none' as const } : {}),
                ...(w.ifMatch ? { ifMatch: w.ifMatch } : {}),
            });
        }),
    );
}
