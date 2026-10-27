import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { GraphSource } from '@inspecto/core/graph';
import { GraphSourcesService } from './graph-sources';
import { LinkAnalysisComponent } from './link-analysis.component';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';

/**
 * S6 (MFS sweep 2026-10-27): opening the Studio must write NOTHING — in particular no `PUT /settings/link-analysis…`.
 * Real services over HttpTestingController, so "no write" is a fact about the wire, not a spy's opinion.
 */
const SETTINGS = {
    projectionNodeCap: null,
    analysisNodeCap: null,
    suspicionNodeCap: null,
    entityTypes: null,
    entityTypesInForce: [{ id: 'msisdn', label: 'MSISDN', normaliser: 'digits', masked: false, classifications: [] }],
    maskingMode: null,
    maskingModeInForce: 'off',
};

function answer(url: string): unknown {
    if (/\/settings\/link-analysis$/.test(url)) return SETTINGS;
    if (url.endsWith('/settings/link-analysis/domain-profile')) return { active: 'generic', installed: [] };
    return [];
}

describe('LinkAnalysisComponent — load writes nothing (S6)', () => {
    it('sends no PUT/POST/DELETE while the page loads and its reads answer', async () => {
        const source: GraphSource = { id: 'entity-projection', label: 'x', query: async () => ({ nodes: [], edges: [] }) };
        TestBed.configureTestingModule({
            imports: [LinkAnalysisComponent],
            providers: [
                ...provideLaHostServices(),
                provideNoopAnimations(),
                provideRouter([]),
                provideHttpClient(withXhr()),
                provideHttpClientTesting(),
                { provide: GraphSourcesService, useValue: { sources: [source], byId: () => source } },
                { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
                { provide: ToastrService, useValue: { warning: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn() } },
                {
                    provide: ActivatedRoute,
                    useValue: { snapshot: { queryParamMap: convertToParamMap({}) }, queryParamMap: of(convertToParamMap({})) },
                },
            ],
        });
        const fixture = TestBed.createComponent(LinkAnalysisComponent);
        fixture.detectChanges();
        const http = TestBed.inject(HttpTestingController);
        const writes: string[] = [];
        for (let round = 0; round < 5; round++) {
            for (const req of http.match(() => true)) {
                if (req.request.method !== 'GET') writes.push(`${req.request.method} ${req.request.url}`);
                if (!req.cancelled) req.flush(answer(req.request.url) as object);
            }
            await new Promise((r) => setTimeout(r));
            fixture.detectChanges();
        }
        expect(writes).toEqual([]);
    });
});
