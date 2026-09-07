import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService, SessionService } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { MenuAttachDialog } from './menu-attach.dialog';

/**
 * EDG-01 cell 3b (EDITIONS CP-09): the Geo view / Link view placeables follow the backend module. The
 * falsifiable form is WHICH kinds the dialog asks the registry for — a dialog that offered them and then
 * failed on the query would be the dishonest half of the same edition boundary.
 */
describe('MenuAttachDialog — optional-module placeables', () => {
    function mount(geoLinkEnabled: boolean) {
        TestBed.resetTestingModule();
        // Typed parameter so `mock.calls[i][0]` is a string, not `never` — the whole assertion reads it.
        const list = vi.fn((_kind: string) => of([]));
        TestBed.configureTestingModule({
            imports: [MenuAttachDialog],
            providers: [
                provideNoopAnimations(),
                // The result grid's real theme service walks up to GAMMA_APP_CONFIG — stub it, as the
                // config-pane spec does.
                { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
                { provide: MatDialogRef, useValue: { close: vi.fn() } },
                { provide: ComponentsService, useValue: { list } },
                { provide: SessionService, useValue: { geoLinkEnabled: signal(geoLinkEnabled) } },
            ],
        });
        const fixture = TestBed.createComponent(MenuAttachDialog);
        fixture.detectChanges();
        return list.mock.calls.map((c) => c[0]);
    }

    it('asks the registry for geo-map-view and link-analysis-view when the module is present', () => {
        const asked = mount(true);
        expect(asked).toContain('geo-map-view');
        expect(asked).toContain('link-analysis-view');
        expect(asked).toContain('dashboard');
    });

    it('does not offer them — and still offers everything else — when the module is absent', () => {
        const asked = mount(false);
        expect(asked).not.toContain('geo-map-view');
        expect(asked).not.toContain('link-analysis-view');
        // Both directions: the other kinds must still be requested, or an over-broad filter would also pass.
        expect(asked).toContain('dashboard');
        expect(asked).toContain('widget');
    });
});
