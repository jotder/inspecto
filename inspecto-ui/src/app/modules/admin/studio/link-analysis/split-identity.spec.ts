import { describe, expect, it } from 'vitest';
import { G6GraphData } from 'app/inspecto/graph';
import { splitIdentityGroups } from './entity-projection';

/**
 * Decision D-S4: Link Analysis node ids are value-projected — `entityId` mints them from the trimmed raw
 * column value with no case fold and no alias resolution. `splitIdentityGroups` REPORTS the identities
 * that scheme has divided, so a degree count or a centrality ranking computed over a split identity space
 * is visible rather than silently wrong.
 *
 * ⛔ These specs pin that it reports and never merges. Merging would answer D-S4 in passing.
 */
function node(id: string, label = id, extra: Record<string, unknown> = {}) {
    return { id, data: { label, kind: 'entity', ...extra } } as G6GraphData['nodes'][number];
}

function graph(...nodes: G6GraphData['nodes']): G6GraphData {
    return { nodes, edges: [] };
}

describe('splitIdentityGroups', () => {
    it('groups ids that differ only by case or trailing punctuation', () => {
        const groups = splitIdentityGroups(graph(node('entity:ACME Ltd'), node('entity:acme ltd.')));

        expect(groups.length).toBe(1);
        expect(groups[0].ids).toEqual(['entity:ACME Ltd', 'entity:acme ltd.']);
        expect(groups[0].key).toBe('acme ltd');
    });

    it('groups ids that differ only by internal whitespace', () => {
        const groups = splitIdentityGroups(graph(node('entity:John  Smith'), node('entity:John Smith')));

        expect(groups.length).toBe(1);
        expect(groups[0].ids.length).toBe(2);
    });

    // The probe that would otherwise succeed: these two DO collide case-insensitively, so a detector that
    // ignored the entity-type scope would report them. Two types are two entities by construction.
    it('never groups across entity-type scopes', () => {
        const groups = splitIdentityGroups(graph(node('entity:person:bob'), node('entity:account:bob')));

        expect(groups).toEqual([]);
    });

    it('still groups within one entity-type scope', () => {
        const groups = splitIdentityGroups(graph(node('entity:person:Bob'), node('entity:person:bob')));

        expect(groups.length).toBe(1);
        expect(groups[0].scope).toBe('person');
    });

    it('reports nothing when every identity is distinct', () => {
        expect(splitIdentityGroups(graph(node('entity:alice'), node('entity:bob')))).toEqual([]);
    });

    it('skips super-node stand-ins, whose label is a count and not a name', () => {
        const groups = splitIdentityGroups(
            graph(
                node('super:hub:1', '12 more', { superMembers: ['entity:a', 'entity:b'] }),
                node('super:hub:2', '12 more', { superMembers: ['entity:c', 'entity:d'] }),
            ),
        );

        expect(groups).toEqual([]);
    });

    it('does not merge the graph it reports on', () => {
        const g = graph(node('entity:ACME Ltd'), node('entity:acme ltd.'));

        splitIdentityGroups(g);

        expect(g.nodes.length).toBe(2);
    });

    it('is empty for a null graph', () => {
        expect(splitIdentityGroups(null)).toEqual([]);
    });
});
