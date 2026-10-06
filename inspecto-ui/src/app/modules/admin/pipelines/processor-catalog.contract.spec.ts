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
        // Floors SET FROM MEASUREMENT (35 addable, 2026-09-07). `>= 10` allowed 25 palette entries to
        // disappear with the spec still green.
        expect(catalog.processors.filter((p) => p.addable).length).toBeGreaterThanOrEqual(30);
        // ⚠ A floor on PLANNED work used to sit here (`> 50`, actual 67) — perverse: delivering 17 more
        // processors would have turned it red for a good reason. What it was reaching for is that the
        // statuses partition the catalog, which is checkable without punishing progress.
        const byStatus = ['delivered', 'partial', 'planned'].map(
            (s) => catalog.processors.filter((p) => p.status === s).length,
        );
        expect(byStatus.reduce((a, b) => a + b, 0)).toBe(catalog.processors.length);
    });

    it('a processor delivered by an optional module names it, and the module-absent state is never addable', () => {
        // MODULE-REORG-1 P7: the contract describes a fully-equipped install; `requires` names the module whose absence
        // flips the served status to planned (and withholds the node type, so the palette cannot add it).
        const ber = catalog.processors.find((p) => p.id === 'parser.asn1.ber');
        expect(ber?.requires).toBe('inspecto-telecom-asn1');
        expect(ber?.status).toBe('delivered');
        for (const p of catalog.processors.filter((x) => x.requires)) {
            expect(p.requires, p.id).toMatch(/^inspecto-[a-z0-9-]+$/);
            if (p.status === 'planned') expect(p.addable, p.id).toBe(false);
        }
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
