import { Component, input } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ComponentDef, ComponentsService } from '@inspecto/core/api';
import { GeoSettingsService } from '@inspecto/link-analysis/api/geo-settings.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { DatasetsService } from 'app/modules/admin/studio/datasets/datasets.service';
import { MapViewComponent } from '@inspecto/link-analysis/geo';
import { GeoViewWidgetComponent } from './geo-view-widget.component';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';

/** MapLibre never mounts in these paths (no data), so the host is jsdom-safe — same discipline as the
 *  studio spec: the pure projection logic is covered by geo-projection.spec. */
/** Stands in for MapLibre's host when a spec draws data: the widget's own template around it is what is read. */
@Component({ selector: 'inspecto-map-view', standalone: true, template: '' })
class StubMapViewComponent {
    readonly data = input<unknown>(null);
    readonly display = input<unknown>(null);
    readonly camera = input<unknown>(null);
    readonly tileServerUrl = input<string | null>(null);
    readonly fill = input(false);
}

function create(components: Partial<ComponentsService>, stubMap = false) {
    TestBed.configureTestingModule({
        imports: [GeoViewWidgetComponent],
        providers: [
            ...provideLaHostServices(),
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: components },
            { provide: GeoSettingsService, useValue: { get: () => of({ tileServerUrl: null }) } },
            { provide: DatasetsService, useValue: {} },
        ],
    });
    if (stubMap)
        TestBed.overrideComponent(GeoViewWidgetComponent, {
            remove: { imports: [MapViewComponent] },
            add: { imports: [StubMapViewComponent] },
        });
    return TestBed.createComponent(GeoViewWidgetComponent);
}

const viewDef = (content: Record<string, unknown>): ComponentDef => ({
    type: 'geo-map-view',
    name: 'v1',
    ref: 'geo-map-view/v1',
    content,
});

describe('GeoViewWidgetComponent', () => {
    it('shows the unbound empty state (accessible) when no viewId is set', async () => {
        const fixture = create({});
        fixture.detectChanges();
        expect(fixture.componentInstance.loaded()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('No saved Geo view bound');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the empty state when the saved view does not exist (mock GET returns null)', () => {
        const fixture = create({ get: () => of(null as unknown as ComponentDef) });
        fixture.componentRef.setInput('viewId', 'missing');
        fixture.detectChanges();
        expect(fixture.componentInstance.view()).toBeNull();
        expect(fixture.nativeElement.textContent).toContain('No saved Geo view bound');
    });

    it('surfaces an unknown geo source as an inline warning', () => {
        const fixture = create({ get: () => of(viewDef({ name: 'V', sourceId: 'bogus', query: {} })) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toContain('Unknown geo source');
        expect(fixture.nativeElement.textContent).toContain('Unknown geo source');
    });

    // LA-CONFIG-LIMITS-RESIDUALS-1 (5): the server caps the points at the Space's display.geo_point_cap; the tile said nothing.
    it("says when the server's point cap cut the map short, in the Geo page's words", async () => {
        const fixture = create({}, true);
        const c = fixture.componentInstance;
        fixture.detectChanges(); // the binding effect settles first (no view bound)
        c.data.set({ points: [], routes: [], truncated: true });
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain(
            'The projection hit the point cap — narrow the dataset to see everything.',
        );
        await expectNoA11yViolations(fixture.nativeElement);
        c.data.set({ points: [], routes: [], truncated: false });
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).not.toContain('hit the point cap');
    });

    it('surfaces a failed view fetch as an inline warning', () => {
        const fixture = create({ get: () => throwError(() => new Error('down')) });
        fixture.componentRef.setInput('viewId', 'v1');
        fixture.detectChanges();
        expect(fixture.componentInstance.error()).toContain('Could not load the saved view');
    });
});
