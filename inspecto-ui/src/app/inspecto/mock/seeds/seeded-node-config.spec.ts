import { describe, expect, it } from 'vitest';
import type { AuthoredNode, AuthoredPipeline } from '../../api';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from './default-space.seed';
import { seedFinancialAudit } from './financial-audit.seed';
import { seedFraudMgmt } from './fraud-mgmt.seed';
import { seedLinkAnalysis } from './link-analysis.seed';
import { seedFormatExamplePipelines } from './format-examples.seed';
import { seedTelecomRa } from './telecom-ra.seed';

/**
 * D5/D2 regression (2026-08-03) — **no seeded node may carry a config key the backend does not read.**
 *
 * The mock rule (`inspecto/mock/`) is that a mock must never be more lenient than the server; a *seed* that
 * ships a phantom key is the same failure wearing different clothes, because the offline preview then shows
 * a populated control that does nothing, and the next author copies it as the reference shape. `partition_by`
 * had spread to **five** seed files this way while being read by zero Java — real partitioning is
 * schema-level (`partitions[]{column, source, type}` / legacy `partitionKey`, `PartitionDef.java`), never a
 * sink-node key. `route_column` had spread to two, while `RowShaper.route` routes on `branches[]{key, where}`
 * (`RowShaper.java:99-145`).
 *
 * ⚠ **`PHANTOM` may only hold keys dead for EVERY node type** — this check is not type-aware. `mode` and
 * `table` are deliberately absent even though they are dead on a *sink*: `mode` is engine-real for
 * `transform.route` (`RowShaper.java:104`, `ConservationCheck.java:78`) and `table` is round-tripped for
 * `sink.persistent` (`PipelineEditable.java:149`), so banning either here would be wrong. Making the ban
 * per-type is plan §3.3's job, not this guard's.
 *
 * ⚠ **A key with zero readers is not automatically phantom — it may be MISNAMED.** `key_columns` and
 * `mode: 'upsert'` on a `sink.materialized` node have zero Java readers, but the capability they
 * describe is real and lives at pipeline level as `reference: {load: upsert, key: [...]}`
 * (`PipelineConfigParser.java:406-412`). That is a D1-class misnaming, tracked as **D8** —
 * deliberately NOT banned here (the right fix is to rename to the engine's shape, not to ban the
 * key). Confirm "no reader" AND "no differently-named equivalent" before adding a key below.
 * (The CS1/CS2 case-study pack this note originally cited was retired 2026-08-20 in favor of the
 * `format-examples.seed.ts` pack — the D8 rationale above stands independent of that narrative.)
 *
 * ⚠ This checks the seeds only. The bidirectional UI-spec ↔ engine-reader contract is a separate, larger
 * check — see `docs/okf/frontend/features/pipelines.md` (§3.3 of the archived plan), which must bind a node type
 * to the runtime that executes the file the editor actually saves.
 */
const PHANTOM = ['partition_by', 'route_column', 'min_age_seconds'] as const;

const SEEDERS: Array<[string, (store: MockStore, space: string) => void]> = [
    ['default-space', seedDefaultSpace],
    ['format-examples', seedFormatExamplePipelines],
    ['telecom-ra', seedTelecomRa],
    ['financial-audit', seedFinancialAudit],
    ['link-analysis', seedLinkAnalysis],
    ['fraud-mgmt', seedFraudMgmt],
];

describe('seeded pipeline node config', () => {
    for (const [name, seed] of SEEDERS) {
        it(`${name} seeds no phantom config keys`, () => {
            const space = `t_${name.replace(/-/g, '_')}`;
            const store = new MockStore();
            seed(store, space);

            const pipelines = store.list(space, PIPELINES_COLL) as unknown as AuthoredPipeline[];
            const offenders: string[] = [];
            for (const p of pipelines) {
                for (const n of (p.nodes ?? []) as AuthoredNode[]) {
                    for (const key of Object.keys(n.config ?? {})) {
                        if ((PHANTOM as readonly string[]).includes(key)) offenders.push(`${p.name}/${n.id}: ${key}`);
                    }
                }
            }
            expect(offenders).toEqual([]);
        });
    }

    it('covers every seeder that authors pipelines', () => {
        // A new seed pack must be added above, or it can reintroduce a phantom key unchecked.
        expect(SEEDERS.length).toBe(6);
    });
});
