import { describe, expect, it } from 'vitest';
import type { AuthoredPipeline } from 'app/inspecto/api/pipelines.service';
import { FORMAT_EXAMPLE_PIPELINES } from 'app/inspecto/mock/seeds/format-examples.seed';
import { PARSE_NODE_FRONTENDS } from './pipeline-parse-definition.component';

/**
 * Invariants for the format-example pack (`format-examples.seed.ts`) — one small, authored
 * pipeline per DuckDB-native parser frontend, replacing the retired CS1–CS5 canvas-stress pack
 * (`docs/archived-documents/plans-archive/pipeline-case-studies.md`, 2026-08-20).
 */

const byName = new Map(FORMAT_EXAMPLE_PIPELINES.map((p) => [p.name, p]));
const example = (name: string): AuthoredPipeline => byName.get(name)!;

describe('format-example pack — one pipeline per DuckDB-native parser frontend', () => {
    it('has exactly the four format examples', () => {
        expect(FORMAT_EXAMPLE_PIPELINES.map((p) => p.name)).toEqual([
            'csv_example',
            'fixedwidth_example',
            'excel_example',
            'json_example',
        ]);
    });

    it('each pipeline is a simple collect -> parse -> map -> sink chain', () => {
        for (const p of FORMAT_EXAMPLE_PIPELINES) {
            expect(p.nodes.map((n) => n.id)).toEqual(['collect', 'parse', 'map', 'sink']);
            expect(p.edges).toEqual([
                { from: 'collect', rel: 'data', to: 'parse' },
                { from: 'parse', rel: 'data', to: 'map' },
                { from: 'map', rel: 'data', to: 'sink' },
            ]);
        }
    });

    /** Each parse node's type must be the per-format subtype PARSE_NODE_FRONTENDS says it is, and
     *  its config must declare the matching frontend — the same contract a real drawer enforces. */
    it('each parse node names the format its type means, config in agreement', () => {
        const expected: Record<string, string> = {
            csv_example: 'delimited',
            fixedwidth_example: 'fixedwidth',
            excel_example: 'xlsx',
            json_example: 'json',
        };
        for (const [name, frontend] of Object.entries(expected)) {
            const parse = example(name).nodes.find((n) => n.id === 'parse')!;
            expect(PARSE_NODE_FRONTENDS[parse.type]).toBe(frontend);
            const parsing = parse.config?.['parsing'] as { frontend?: string } | undefined;
            expect(parsing?.frontend).toBe(frontend);
        }
    });

    /** Every Grammar is authored INLINE (config.parsing) — the current model (Grammar templates
     *  are copies, never bindings) — never a `use: grammar/<id>` binding, which the retired CS pack
     *  used and which predates that reversal. */
    it('no node binds a Grammar by reference', () => {
        for (const p of FORMAT_EXAMPLE_PIPELINES) for (const n of p.nodes) expect(n.use).toBeUndefined();
    });

    it('every node is a real, currently-lowerable type', () => {
        const known = new Set([
            'acquisition',
            ...Object.keys(PARSE_NODE_FRONTENDS),
            'transform.map',
            'sink.persistent',
        ]);
        for (const p of FORMAT_EXAMPLE_PIPELINES)
            for (const n of p.nodes) expect(known.has(n.type), `${p.name}/${n.id}: ${n.type}`).toBe(true);
    });
});
