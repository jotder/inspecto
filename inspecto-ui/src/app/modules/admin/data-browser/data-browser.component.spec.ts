import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { provideRouter } from '@angular/router';
import { ComponentsService, DbBrowserService, LensService, SessionService } from 'app/inspecto/api';
import { DataBrowserComponent } from './data-browser.component';

describe('DataBrowserComponent', () => {
    const svc = {
        catalog: () =>
            of({
                groups: [
                    {
                        id: 'stores',
                        label: 'Data Stores',
                        kind: 'parquet',
                        tables: [{ name: 'orders', format: 'PARQUET', dataset: 'orders_ds' }],
                    },
                    {
                        id: 'ops:objects',
                        label: 'Operational · Objects',
                        kind: 'operational',
                        live: true,
                        tables: [{ name: 'inspecto_ops_objects' }],
                    },
                ],
            }),
        table: () =>
            of({
                columns: [
                    { name: 'id', type: 'INTEGER' },
                    { name: 'name', type: 'VARCHAR' },
                ],
                rows: [{ id: 1, name: 'alice' }],
                statistics: { rowCount: 1, elapsedMs: 1, truncated: false },
            }),
        query: () => of({ columns: [], rows: [], statistics: { rowCount: 0, elapsedMs: 1, truncated: false } }),
    };

    const canAuthor = signal(true);
    const exchange = signal(true);
    const shared = {
        type: 'dataset',
        name: 'partner_orders',
        ref: 'dataset/partner_orders',
        content: { name: 'partner_orders', physicalRef: 'shared/acme/orders_v1' },
    };
    const local = { type: 'dataset', name: 'orders_ds', ref: 'dataset/orders_ds', content: { physicalRef: 'orders' } };
    const comps = { list: vi.fn((_t: string) => of([shared, local])) };

    beforeEach(() => {
        canAuthor.set(true);
        exchange.set(true);
        comps.list.mockClear();
        TestBed.configureTestingModule({
            imports: [DataBrowserComponent],
            providers: [
                provideNoopAnimations(),
                provideRouter([]),
                { provide: ComponentsService, useValue: comps },
                { provide: SessionService, useValue: { exchangeEnabled: exchange } },
                { provide: DbBrowserService, useValue: svc },
                { provide: LensService, useValue: { canAuthorWorkbench: canAuthor } },
            ],
        });
    });

    it('hides operational groups from a caller without canAuthorWorkbench (DB-QUERY-UNGATED-1)', () => {
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('inspecto_ops_objects');
        canAuthor.set(false);
        fixture.detectChanges();
        expect(el.textContent).not.toContain('inspecto_ops_objects');
        expect(el.textContent).toContain('orders');
        expect(fixture.componentInstance.storeCount()).toBe(1);
    });

    it('lists the catalog stores', () => {
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Data Stores');
        expect(text).toContain('orders');
        expect(text).toContain('Select a table'); // right pane before selection
    });

    it('renders with no accessibility violations', async () => {
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('Load more widens the limit and re-runs the last read (table browse, or server SQL if used) — R6a', () => {
        const table = vi.fn(() =>
            of({
                columns: [{ name: 'id', type: 'INTEGER' }],
                rows: [{ id: 1 }],
                statistics: { rowCount: 1, elapsedMs: 1, truncated: true },
            }),
        );
        const query = vi.fn(() =>
            of({ columns: [], rows: [], statistics: { rowCount: 0, elapsedMs: 1, truncated: true } }),
        );
        TestBed.overrideProvider(DbBrowserService, { useValue: { ...svc, table, query } });
        const fixture = TestBed.createComponent(DataBrowserComponent);
        const c = fixture.componentInstance;
        fixture.detectChanges();
        c.select(
            { id: 'stores', label: 'Data Stores', kind: 'parquet', tables: [] },
            { name: 'orders', format: 'PARQUET' },
        );
        expect(c.limit()).toBe(200);

        c.loadMore();
        expect(table).toHaveBeenLastCalledWith(expect.objectContaining({ limit: 400 }));

        c.onServerSql('SELECT * FROM orders');
        c.loadMore();
        expect(query).toHaveBeenLastCalledWith(expect.objectContaining({ sql: 'SELECT * FROM orders', limit: 600 }));
    });

    it('lists bound shared Datasets with a badge and links, and never fires a raw-row request on click', () => {
        const table = vi.fn(svc.table);
        const query = vi.fn(svc.query);
        TestBed.overrideProvider(DbBrowserService, { useValue: { ...svc, table, query } });
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const sec = el.querySelector('[data-testid="shared-datasets"]') as HTMLElement;
        expect(sec.textContent).toContain('Shared with this Space');
        expect(sec.textContent).toContain('partner_orders');
        expect(sec.textContent).toContain('acme / orders_v1');
        expect(sec.textContent).toContain('not browsable as raw rows');
        expect(sec.querySelector('inspecto-status-badge')?.textContent).toContain('shared');
        expect(sec.textContent).not.toContain('orders_ds');
        const links = Array.from(sec.querySelectorAll('a'));
        expect(links.map((a) => a.getAttribute('href'))).toEqual([
            '/catalog/datasets/partner_orders',
            '/catalog?tab=shared-with-me',
        ]);
        (sec.querySelector('li') as HTMLElement).click();
        fixture.detectChanges();
        expect(table).not.toHaveBeenCalled();
        expect(query).not.toHaveBeenCalled();
        expect(fixture.componentInstance.selected()).toBeNull();
    });

    it('gates the links: no editor link without canAuthorWorkbench, no Catalog link without Exchange', () => {
        canAuthor.set(false);
        exchange.set(false);
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        const sec = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="shared-datasets"]');
        expect(sec).not.toBeNull();
        expect(sec!.querySelectorAll('a').length).toBe(0);
    });

    it('hides the section when no Dataset is bound', () => {
        comps.list.mockReturnValueOnce(of([local]));
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="shared-datasets"]')).toBeNull();
    });

    it('hides the section without error when listing Datasets fails (Exchange absent)', () => {
        comps.list.mockReturnValueOnce(throwError(() => new Error('503')));
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid="shared-datasets"]')).toBeNull();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('orders');
    });

    it('shared section renders with no accessibility violations', async () => {
        const fixture = TestBed.createComponent(DataBrowserComponent);
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-testid="shared-datasets"]')).not.toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
