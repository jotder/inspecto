import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ServedFieldSpec } from 'app/inspecto/component-model';
import { apiUrl } from './api-base';
import { ParserPreview } from './components.service';

export type { ServedFieldSpec };

/**
 * One entry of the parser catalog (`GET /parsers`): a self-describing Parser — id/label, whether
 * its records are tree-shaped, whether it can load to Tables TODAY (`ingestable: false` =
 * preview-only until the flatten configuration), and the grammar schema its options form renders.
 */
export interface ParserDef {
    id: string;
    label: string;
    hierarchical: boolean;
    ingestable: boolean;
    /**
     * FQCN of the `StreamingFileIngester` a guided Save writes to `parsing.plugin.ingester`.
     * Absent for the built-ins (they ingest through the engine's own DuckDB path, not a named
     * class) and for preview-only plugins — so `ingestable && ingesterClass` is what a segments
     * editor gates on.
     */
    ingesterClass?: string;
    grammarSchema: ServedFieldSpec[];
}

/**
 * The parser-plugin catalog + stateless grammar preview (v5.3.0) — the served, self-describing
 * side of the parsing experience: any parser deployed as a plugin appears here with its options
 * schema, no UI change needed. `preview` is the grammar-shaped sibling of
 * `ConfigService.previewParsing` (which stays the draft-true path for the four built-ins) and
 * returns the same `ParserPreview` union — a flat table, or a record tree for hierarchical
 * formats (XML, ASN.1, …).
 */
@Injectable({ providedIn: 'root' })
export class ParsersService {
    private http = inject(HttpClient);

    /** The registered parsers, built-ins first (`GET /parsers`). */
    list(): Observable<ParserDef[]> {
        return this.http.get<ParserDef[]>(apiUrl('/parsers'));
    }

    /**
     * Parse `sampleText` with an in-progress `grammar` (the nested options map) — stateless,
     * scratch-only. Caller errors (bad grammar, unparseable sample) come back as 422 with the
     * reason.
     */
    preview(id: string, grammar: Record<string, unknown>, sampleText: string): Observable<ParserPreview> {
        return this.http.post<ParserPreview>(apiUrl(`/parsers/${encodeURIComponent(id)}/preview`), {
            grammar,
            sample_text: sampleText,
        });
    }
}
