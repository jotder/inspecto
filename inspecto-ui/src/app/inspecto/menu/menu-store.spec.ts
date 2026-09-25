import { describe, expect, it } from 'vitest';
import { MenuStore } from './menu-store';
import { emptyTree } from './menu-types';

/** A store with a deterministic id counter (n1, n2, …) so ids are assertable. */
function store(): MenuStore {
    let i = 0;
    return new MenuStore(emptyTree('s1'), () => `n${++i}`);
}

describe('MenuStore', () => {
    it('adds top-level menus and nested sub-menus', () => {
        const s = store();
        const rev = s.addMenu('Revenue', 'heroicons_outline:banknotes');
        const top = s.addSubMenu(rev, 'TopX');
        expect(s.nodes()).toHaveLength(1);
        expect(s.find(rev)?.title).toBe('Revenue');
        expect(s.find(rev)?.icon).toBe('heroicons_outline:banknotes');
        expect(s.find(rev)?.children?.[0].id).toBe(top);
    });

    it('seeds the Telecom starter example: Revenue (Overview + TopX) and FMS (menu-builder-plan O3)', () => {
        const s = store();
        const top = s.seedExample();
        const revenue = s.find(top)!;
        expect(s.nodes()).toHaveLength(2);
        expect(revenue.title).toBe('Revenue');
        const [overview, topx] = revenue.children!;
        expect(overview.title).toBe('Overview');
        expect(overview.children![0].binding).toEqual({ kind: 'dashboard', componentId: 'revenue_overview' });
        expect(topx.title).toBe('TopX');
        expect(topx.children!.map((n) => n.title)).toEqual(['Top usages', 'Top billed', 'Top roamers']);
        expect(topx.children!.every((n) => n.binding?.kind === 'widget')).toBe(true);
        const fms = s.nodes()[1];
        expect(fms.title).toBe('FMS');
        expect(fms.children!.map((n) => n.binding?.kind)).toEqual(['dashboard', 'widget']);
    });

    it('attaches a leaf bound to a component (no children)', () => {
        const s = store();
        const rev = s.addMenu('Revenue');
        const top = s.addSubMenu(rev, 'TopX');
        const leaf = s.attach(top, 'Top usages', { kind: 'dashboard', componentId: 'dash_top_usage' });
        const node = s.find(leaf)!;
        expect(node.binding).toEqual({ kind: 'dashboard', componentId: 'dash_top_usage' });
        expect(node.children).toBeUndefined();
    });

    it('renames and sets an icon', () => {
        const s = store();
        const id = s.addMenu('Rev');
        s.rename(id, 'Revenue').setIcon(id, 'heroicons_outline:banknotes');
        expect(s.find(id)?.title).toBe('Revenue');
        expect(s.find(id)?.icon).toBe('heroicons_outline:banknotes');
    });

    it('removes a node and its descendants', () => {
        const s = store();
        const rev = s.addMenu('Revenue');
        const top = s.addSubMenu(rev, 'TopX');
        s.attach(top, 'Top usages', { kind: 'dashboard', componentId: 'd1' });
        s.remove(rev);
        expect(s.nodes()).toHaveLength(0);
        expect(s.find(top)).toBeUndefined();
    });

    it('reorders siblings (unnamed ids kept, appended)', () => {
        const s = store();
        const a = s.addMenu('A');
        const b = s.addMenu('B');
        const c = s.addMenu('C');
        s.reorder(null, [c, a]);
        expect(s.nodes().map((n) => n.id)).toEqual([c, a, b]);
    });

    it('moves a subtree to a new parent', () => {
        const s = store();
        const rev = s.addMenu('Revenue');
        const fms = s.addMenu('FMS');
        const top = s.addSubMenu(rev, 'TopX');
        s.move(top, fms);
        expect(s.find(rev)?.children).toHaveLength(0);
        expect(s.find(fms)?.children?.[0].id).toBe(top);
    });

    it('refuses to move a node into its own descendant (no cycle)', () => {
        const s = store();
        const rev = s.addMenu('Revenue');
        const top = s.addSubMenu(rev, 'TopX');
        s.move(rev, top);
        expect(s.nodes().map((n) => n.id)).toEqual([rev]);
        expect(s.find(rev)?.children?.[0].id).toBe(top);
    });

    it('detects duplicate sibling titles (case-insensitive), scoped to the parent', () => {
        const s = store();
        const rev = s.addMenu('Revenue');
        s.addSubMenu(rev, 'TopX');
        expect(s.hasSiblingTitle(rev, 'topx')).toBe(true);
        expect(s.hasSiblingTitle(rev, 'Fraud')).toBe(false);
        expect(s.hasSiblingTitle(null, 'Revenue')).toBe(true);
        expect(s.hasSiblingTitle(null, 'TopX')).toBe(false); // TopX is under Revenue, not top-level
    });
});

describe('MenuStore — UIE-7 landing and route leaves', () => {
    it('sets, replaces and clears the Space landing', () => {
        const s = store();
        const g = s.addMenu('Fraud');
        const cases = s.attach(g, 'Cases', { kind: 'route', route: '/cases' });
        expect(s.setLanding(cases).snapshot().landing).toBe(cases);
        expect('landing' in s.setLanding(undefined).snapshot()).toBe(false);
    });

    it('clears the landing when its item, or a group holding it, is removed — and only then', () => {
        const s = store();
        const g = s.addMenu('Fraud');
        const cases = s.attach(g, 'Cases', { kind: 'route', route: '/cases' });
        const other = s.attach(g, 'Ops', { kind: 'dashboard', componentId: 'fm_ops' });
        s.setLanding(cases);
        s.remove(other);
        expect(s.snapshot().landing).toBe(cases);
        s.remove(g);
        expect(s.snapshot().landing).toBeUndefined();
    });

    it('re-points a route leaf, keeping its id and title', () => {
        const s = store();
        const cases = s.attach(null, 'Cases', { kind: 'route', route: '/cases' });
        s.setRoute(cases, '/incidents');
        expect(s.find(cases)).toMatchObject({
            id: cases,
            title: 'Cases',
            binding: { kind: 'route', route: '/incidents' },
        });
    });
});
