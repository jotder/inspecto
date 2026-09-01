import { Injectable, inject } from '@angular/core';
import { Observable, forkJoin, map, of, switchMap } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ConfigService, SpacesService } from 'app/inspecto/api';
import {
    BuildStreamBundleInput,
    StreamBundle,
    StreamImportPlan,
    buildStreamBundle,
    streamBundleFileName,
} from './stream-bundle';

/**
 * The async half of Stream configuration transfer: gather a Stream's satellites off this instance to
 * build a portable bundle, and replay an import plan as writes. All shape/portability logic is pure
 * in {@link ./stream-bundle.ts} — this file only does I/O, so the rules stay unit-testable without
 * HTTP.
 *
 * Import order matters and is NOT cosmetic: satellites (schema / segment schemas / enrichment) are
 * written BEFORE the pipeline that references them, so the pipeline never names a file that does not
 * exist yet — the same ordering rule the Schema stage and segments editor already follow.
 */
@Injectable({ providedIn: 'root' })
export class StreamTransferService {
    private configApi = inject(ConfigService);
    private spaces = inject(SpacesService);

    /**
     * Build a pipeline's portable bundle from its NAME alone — the server-held config is read here,
     * so no open tab (or lifted graph) is needed. The stream-vs-reference kind comes off the config's
     * own `produces` — ⛔ not `PipelineSummary.produces`, which is the list of stores it produces.
     * One seam for every caller that starts from a name: the editor's Export configuration, the Open
     * dialog's per-row export, and Duplicate's read half.
     */
    exportPipeline(name: string): Observable<{ bundle: StreamBundle; missing: string[] }> {
        return this.configApi
            .read('pipeline', name)
            .pipe(
                switchMap((r) =>
                    this.buildExport(
                        name,
                        String(r.config['produces'] ?? '') === 'reference' ? 'reference' : 'stream',
                        r.config,
                    ),
                ),
            );
    }

    /**
     * Read the satellites a full export needs and assemble the bundle. A satellite that fails to
     * read is omitted rather than failing the export — a partial export the operator is TOLD about
     * beats no export at all (the caller surfaces `missing`).
     */
    buildExport(
        name: string,
        kind: 'stream' | 'reference',
        pipeline: Record<string, unknown>,
    ): Observable<{ bundle: StreamBundle; missing: string[] }> {
        const missing: string[] = [];
        const schemaName = this.schemaNameOf(pipeline);
        const segmentKeys = this.segmentKeysOf(pipeline);

        const schema$ = schemaName ? this.readConfig('schema', schemaName, missing) : of(null);
        const enrichment$ =
            kind === 'stream'
                ? this.readConfig('enrichment', `${name}_enrich`, []) // absent is normal, never reported
                : of(null);
        const segments$ = segmentKeys.length
            ? forkJoin(
                  segmentKeys.map((key) =>
                      this.readConfig('schema', this.segmentSchemaName(name, key), missing).pipe(
                          map((config) => ({ key, config })),
                      ),
                  ),
              )
            : of([] as { key: string; config: Record<string, unknown> | null }[]);

        return forkJoin({ schema: schema$, enrichment: enrichment$, segments: segments$ }).pipe(
            map(({ schema, enrichment, segments }) => {
                const segMap: Record<string, Record<string, unknown>> = {};
                for (const s of segments) if (s.config) segMap[s.key] = s.config;
                const input: BuildStreamBundleInput = {
                    name,
                    space: this.spaces.currentSpaceId(),
                    kind,
                    pipeline,
                    schema,
                    segments: segMap,
                    enrichment,
                };
                return { bundle: buildStreamBundle(input), missing };
            }),
        );
    }

    /** Replay a plan: satellites first, then the pipeline, then register it so the Catalog lists it. */
    applyImport(plan: StreamImportPlan): Observable<{ path: string }> {
        const satellites: Observable<unknown>[] = [];
        if (plan.schema) {
            satellites.push(this.configApi.write('schema', plan.schema.config, { overwrite: true }));
        }
        for (const seg of plan.segments) {
            satellites.push(this.configApi.write('schema', seg.config, { overwrite: true }));
        }
        if (plan.enrichment) {
            satellites.push(this.configApi.write('enrichment', plan.enrichment.config, { overwrite: true }));
        }
        const satellites$ = satellites.length ? forkJoin(satellites) : of([]);

        return satellites$.pipe(
            // No `overwrite` on the pipeline: an import must never silently replace an existing
            // stream — the dialog's unique-name check already guards this, and a 409 here is the
            // server's own last word on it.
            switchMap(() => this.configApi.write('pipeline', plan.pipeline)),
            switchMap((written) =>
                // Registration failing is not fatal: the draft exists and onboarding opens; the
                // Catalog row appears after a rescan (same degrade as the create dialog).
                this.configApi.registerPipeline(written.path).pipe(
                    catchError(() => of(null)),
                    map(() => ({ path: written.path })),
                ),
            ),
        );
    }

    /** Trigger a browser download of a stream bundle as pretty-printed JSON. */
    download(bundle: StreamBundle): void {
        const url = URL.createObjectURL(new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = streamBundleFileName(bundle.source.name, new Date(bundle.exportedAt));
        link.click();
        URL.revokeObjectURL(url);
    }

    private readConfig(
        type: 'schema' | 'enrichment',
        name: string,
        missing: string[],
    ): Observable<Record<string, unknown> | null> {
        return this.configApi.read(type, name).pipe(
            map((r) => r.config),
            catchError(() => {
                missing.push(`${type} "${name}"`);
                return of(null);
            }),
        );
    }

    /** The schema config NAME behind `processing.schema_file`'s path (`…/config/<name>.toon`). */
    private schemaNameOf(pipeline: Record<string, unknown>): string | null {
        const proc = pipeline['processing'];
        const path =
            typeof proc === 'object' && proc !== null
                ? String((proc as Record<string, unknown>)['schema_file'] ?? '').trim()
                : '';
        if (!path) return null;
        const file = path.split(/[\\/]/).pop() ?? '';
        return file.replace(/\.toon$/i, '') || null;
    }

    private segmentKeysOf(pipeline: Record<string, unknown>): string[] {
        const parsing = pipeline['parsing'];
        const plugin =
            typeof parsing === 'object' && parsing !== null ? (parsing as Record<string, unknown>)['plugin'] : null;
        const segments =
            typeof plugin === 'object' && plugin !== null ? (plugin as Record<string, unknown>)['segments'] : null;
        return typeof segments === 'object' && segments !== null && !Array.isArray(segments)
            ? Object.keys(segments as Record<string, unknown>)
            : [];
    }

    private segmentSchemaName(pipeline: string, segmentKey: string): string {
        return `${pipeline}_${segmentKey}`.replace(/[^A-Za-z0-9_]+/g, '_');
    }
}
