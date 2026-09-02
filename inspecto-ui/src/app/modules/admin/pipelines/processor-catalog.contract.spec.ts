import { describe, expect, it } from 'vitest';
import PROCESSOR_CATALOG from 'app/inspecto/contracts/processor-catalog.contract.json';
import { ProcessorCatalog } from 'app/inspecto/api';
import { familyCategory, groupByFamily } from './pipeline-graph';

/**
 * The served Step Processor catalog, pinned as `processor-catalog.contract.json` by the Java
 * `ProcessorCatalogContractTest` (regenerate there with -Dprocessor.catalog.write=true). This side checks
 * the shape the palette relies on, so a Java change that breaks the UI's assumptions fails HERE too.
 */
describe('processor-catalog contract', () => {
    const catalog = PROCESSOR_CATALOG as unknown as ProcessorCatalog;

    it('carries the eight families and every processor names one of them', () => {
        expect(catalog.families).toHaveLength(8);
        const codes = new Set(catalog.families.map((f) => f.code));
        for (const p of catalog.processors) expect(codes.has(p.family), p.id).toBe(true);
        // every family tints as a known node category (the palette reuses categoryColor)
        for (const f of catalog.families)
            expect(['SOURCE', 'PARSE', 'TRANSFORM', 'CONTROL', 'SINK']).toContain(familyCategory(f.code));
    });

    it('ids are unique, a planned processor is never addable, and an addable one always names its node type', () => {
        const ids = catalog.processors.map((p) => p.id);
        expect(new Set(ids).size).toBe(ids.length);
        for (const p of catalog.processors) {
            if (p.status === 'planned') expect(p.addable, p.id).toBe(false);
            if (p.addable) expect(p.nodeType, p.id).toBeTruthy();
        }
        expect(catalog.processors.filter((p) => p.addable).length).toBeGreaterThanOrEqual(10);
        expect(catalog.processors.filter((p) => p.status === 'planned').length).toBeGreaterThan(50);
    });

    it('every processor carries its own heroicons_outline icon', () => {
        for (const p of catalog.processors) expect(p.icon, p.id).toMatch(/^heroicons_outline:[a-z0-9-]+$/);
        // meaningful, not a family placeholder: the catalog uses many distinct glyphs
        expect(new Set(catalog.processors.map((p) => p.icon)).size).toBeGreaterThan(40);
    });

    it('groups into non-empty family sections in catalog order', () => {
        const groups = groupByFamily(catalog);
        expect(groups.map((g) => g.family.code)).toEqual(catalog.families.map((f) => f.code));
        for (const g of groups) expect(g.processors.length).toBeGreaterThan(0);
    });
});
