import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, Observable, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { SessionService } from 'app/inspecto/api/session.service';
import { MENU_STORAGE_KEY } from 'app/inspecto/menu';
import { NavMenusService } from 'app/inspecto/menu/menu-api';
import { MenuTree } from 'app/inspecto/menu/menu-types';
import { NavigationService } from './navigation.service';

/**
 * The sidebar sources a Space's custom Menu tree from the server (`GET /nav/menus`), not only from the
 * `localStorage` mirror the Menu Builder fills — a fresh browser must show the menus without a visit to
 * Settings ▸ Menus.
 */
describe('NavigationService — custom menus from the backend', () => {
    const tree = (title: string): MenuTree =>
        ({
            version: 1,
            nodes: [
                {
                    id: `g-${title}`,
                    title,
                    icon: 'heroicons_outline:banknotes',
                    children: [
                        { id: `l-${title}`, title: 'Overview', binding: { kind: 'dashboard', componentId: 'd1' } },
                    ],
                },
            ],
        }) as unknown as MenuTree;

    let toastError: ReturnType<typeof vi.fn>;

    function mount(get: () => Observable<MenuTree>) {
        toastError = vi.fn();
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({
            providers: [
                NavigationService,
                { provide: NavMenusService, useValue: { get: vi.fn(get) } },
                { provide: ToastrService, useValue: { error: toastError } },
                {
                    provide: SessionService,
                    useValue: { geoLinkEnabled: signal(true), eventsEnabled: signal(true), opsEnabled: signal(true) },
                },
            ],
        });
        return { nav: TestBed.inject(NavigationService), api: TestBed.inject(NavMenusService) };
    }

    const topTitles = (n: { default: { title?: string }[] }) => n.default.map((i) => i.title);

    beforeEach(() => localStorage.clear());
    afterEach(() => localStorage.clear());

    it('renders the server tree on a fresh browser (empty storage)', async () => {
        localStorage.setItem('inspecto.currentSpace', 'telco');
        const { nav } = mount(() => of(tree('Revenue Assurance')));
        const n = (await firstValueFrom(nav.get())) as never as { default: { title?: string }[] };
        expect(topTitles(n)).toContain('Revenue Assurance');
        expect(JSON.parse(localStorage.getItem(MENU_STORAGE_KEY)!).telco.nodes[0].title).toBe('Revenue Assurance');
    });

    it('loads the ACTIVE space tree, over a stale mirror', async () => {
        localStorage.setItem('inspecto.currentSpace', 'b');
        localStorage.setItem(MENU_STORAGE_KEY, JSON.stringify({ a: tree('Space A'), b: tree('Stale B') }));
        const { nav } = mount(() => of(tree('Fresh B')));
        const n = (await firstValueFrom(nav.get())) as never as { default: { title?: string }[] };
        expect(topTitles(n)).toContain('Fresh B');
        expect(topTitles(n)).not.toContain('Stale B');
        expect(topTitles(n)).not.toContain('Space A');
    });

    it('fetches once — a later rebuild keeps local edits instead of refetching', async () => {
        const { nav, api } = mount(() => of(tree('Server')));
        await firstValueFrom(nav.get());
        localStorage.setItem(MENU_STORAGE_KEY, JSON.stringify({ default: tree('Local edit') }));
        const n = (await firstValueFrom(nav.get())) as never as { default: { title?: string }[] };
        expect(api.get).toHaveBeenCalledTimes(1);
        expect(topTitles(n)).toContain('Local edit');
    });

    it('a 503 / failed fetch shows no custom groups and raises no toast', async () => {
        const { nav } = mount(() => throwError(() => new HttpErrorResponse({ status: 503 })));
        const n = (await firstValueFrom(nav.get())) as never as { default: { title?: string }[] };
        expect(topTitles(n)).not.toContain('Revenue Assurance');
        expect(n.default.length).toBeGreaterThan(0);
        expect(toastError).not.toHaveBeenCalled();
    });

    it('an empty server tree adds no custom groups', async () => {
        const { nav } = mount(() => of({ version: 1, nodes: [] } as unknown as MenuTree));
        const withEmpty = (await firstValueFrom(nav.get())) as never as { default: unknown[] };
        const { nav: nav2 } = mount(() => throwError(() => new Error('x')));
        const baseline = (await firstValueFrom(nav2.get())) as never as { default: unknown[] };
        expect(withEmpty.default.length).toBe(baseline.default.length);
    });
});
