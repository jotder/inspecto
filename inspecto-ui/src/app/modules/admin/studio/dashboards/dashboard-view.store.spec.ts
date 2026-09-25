import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ConditionGroup } from 'app/inspecto/query';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { WidgetsService } from '../widgets/widgets.service';
import { DashboardViewStore } from './dashboard-view.store';

function ds(id: string, columns: string[], calculated: string[] = []): Dataset {
    return {
        id,
        name: id,
        kind: 'virtual',
        sourceName: id,
        columns: columns.map((name) => ({ name, type: 'string', role: 'dimension' })),
        measures: [],
        calculated: calculated.map((name) => ({ name, expr: 'x' })),
    };
}
const DATED = ds('dated', ['event_date', 'region']);
const CALC = ds('calc', ['region'], ['event_date']);
const UNDATED = ds('undated', ['region']);
const SAVED: ConditionGroup = {
    kind: 'group',
    op: 'AND',
    items: [{ kind: 'condition', field: 'region', operator: '=', value: 'EU' }],
};
const SEPT_TO_DATE = {
    kind: 'group',
    op: 'AND',
    items: [
        { kind: 'condition', field: 'event_date', operator: '>=', value: '2026-09-01' },
        { kind: 'condition', field: 'event_date', operator: '<', value: '2026-09-25' },
    ],
};

function store(): DashboardViewStore {
    TestBed.configureTestingModule({
        providers: [
            DashboardViewStore,
            { provide: WidgetsService, useValue: { list: () => of([]) } },
            { provide: DatasetsService, useValue: { list: () => of([]) } },
            { provide: DatasetRowsService, useValue: { rows: () => Promise.resolve({ rows: [], truncated: false }) } },
        ],
    });
    const s = TestBed.inject(DashboardViewStore);
    s.datasets.set([DATED, CALC, UNDATED]);
    return s;
}

describe('DashboardViewStore — date range per-tile scoping (UIE-5 d)', () => {
    it('adds the range only to tiles whose Dataset has the date column; others keep the plain cross-filter', () => {
        const s = store();
        s.seed({
            id: 'd',
            name: 'd',
            tiles: [],
            filter: SAVED,
            dateField: 'event_date',
            asOf: '2026-09-24',
            defaultRange: 'month-to-date',
        });
        expect(s.filterFor(DATED)).toEqual({ kind: 'group', op: 'AND', items: [SAVED, SEPT_TO_DATE] });
        expect(s.filterFor(CALC)).toEqual({ kind: 'group', op: 'AND', items: [SAVED, SEPT_TO_DATE] });
        expect(s.filterFor(UNDATED)).toBe(s.filter()); // untouched — same object, no re-query
    });

    it('with no cross-filter the range is the whole filter; All dates or no date field means no range', () => {
        const s = store();
        s.seed({
            id: 'd',
            name: 'd',
            tiles: [],
            dateField: 'event_date',
            asOf: '2026-09-24',
            defaultRange: 'month-to-date',
        });
        expect(s.filterFor(DATED)).toEqual(SEPT_TO_DATE);

        s.range.set(null);
        expect(s.filterFor(DATED)).toBe(s.filter());

        s.range.set('last-7-days');
        s.dateField.set('');
        expect(s.filterFor(DATED)).toBe(s.filter());
    });

    it('a drill still reaches every tile, and the range stays scoped on top of it', () => {
        const s = store();
        s.seed({
            id: 'd',
            name: 'd',
            tiles: [],
            dateField: 'event_date',
            asOf: '2026-09-24',
            defaultRange: 'month-to-date',
        });
        s.onDrill({ field: 'region', value: 'US' });
        const drilled = {
            kind: 'group',
            op: 'AND',
            items: [{ kind: 'condition', field: 'region', operator: '=', value: 'US' }],
        };
        expect(s.filterFor(UNDATED)).toEqual(drilled);
        expect(s.filterFor(DATED)).toEqual({ kind: 'group', op: 'AND', items: [drilled, SEPT_TO_DATE] });
    });

    it('keeps a tile’s filter identity between reads, so a tile re-queries only when its own filter changes', () => {
        const s = store();
        s.seed({
            id: 'd',
            name: 'd',
            tiles: [],
            dateField: 'event_date',
            asOf: '2026-09-24',
            defaultRange: 'year-to-date',
        });
        expect(s.filterFor(DATED)).toBe(s.filterFor(DATED));
    });
});

describe('DashboardViewStore — treemap subgroup drill (group AND subgroup)', () => {
    const eq = (field: string, value: string) => ({ kind: 'condition', field, operator: '=', value });
    const online = (group: string) => ({
        field: 'channel',
        value: 'Online',
        and: [{ field: 'typology', value: group }],
    });

    it('a subgroup click selects exactly its rectangle; one in another group moves it; the same click clears both', () => {
        const s = store();
        s.seed({ id: 'd', name: 'd', tiles: [] });
        s.onDrill(online('SIM box'));
        expect(s.filter().items).toEqual([eq('channel', 'Online'), eq('typology', 'SIM box')]);
        s.onDrill(online('Wangiri'));
        expect(s.filter().items).toEqual([eq('channel', 'Online'), eq('typology', 'Wangiri')]);
        s.onDrill(online('Wangiri'));
        expect(s.filter().items).toEqual([]);
    });

    it('a group click stays a one-field drill that toggles on its own', () => {
        const s = store();
        s.seed({ id: 'd', name: 'd', tiles: [] });
        s.onDrill({ field: 'typology', value: 'IRSF' });
        expect(s.filter().items).toEqual([eq('typology', 'IRSF')]);
        s.onDrill({ field: 'typology', value: 'IRSF' });
        expect(s.filter().items).toEqual([]);
    });
});
