import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { environment } from '../../../environments/environment';
import { AccessService } from './access.service';
import { ComponentsService } from './components.service';
import { SpacesService } from './spaces.service';

const base = environment.apiBaseUrl + '/v1';
const space = (id: string) => ({ id, displayName: id, description: '', createdAt: '' });

describe('Spaces / Components writes clear the short-lived reads (N5)', () => {
    let http: HttpTestingController;
    let spaces: SpacesService;
    let components: ComponentsService;

    beforeEach(() => {
        localStorage.clear();
        TestBed.configureTestingModule({ providers: [provideHttpClient(withXhr()), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        spaces = TestBed.inject(SpacesService);
        components = TestBed.inject(ComponentsService);
    });

    /** Answer a pending `refresh()` on a single-tenant server. */
    function answerRefresh(): void {
        http.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: false });
        http.expectOne(`${base}/spaces`).flush([]);
    }

    const writes: [string, () => Observable<unknown>, string][] = [
        ['create', () => spaces.create({ id: 'n' }), '/spaces'],
        ['createSample', () => spaces.createSample('t', 'N'), '/spaces'],
        ['update', () => spaces.update('a', { display_name: 'A' }), '/spaces/a'],
        ['remove', () => spaces.remove('a', false), '/spaces/a'],
        ['createFromBundle', () => spaces.createFromBundle('n', new Blob()), '/spaces/import'],
        ['importBundle', () => spaces.importBundle('a', new Blob(), false), '/spaces/a/import'],
        [
            'setDomainProfile',
            () => spaces.setDomainProfile('a', null),
            '/spaces/a/settings/link-analysis/domain-profile',
        ],
    ];
    for (const [name, write, url] of writes) {
        it(`Spaces ${name} makes the next refresh ask the server again`, () => {
            spaces.refresh().subscribe();
            answerRefresh();
            spaces.refresh().subscribe();
            expect(http.match(`${base}/spaces`).length).toBe(0); // fresh: shared
            write().subscribe();
            http.expectOne((r) => r.url === base + url && r.method !== 'GET').flush({});
            spaces.refresh().subscribe();
            answerRefresh();
        });
    }

    it('Components update and remove clear the list read (create is covered in short-reads.spec)', () => {
        const cases: ['dataset' | 'link-analysis-view', () => Observable<unknown>][] = [
            ['dataset', () => components.update('dataset', 'd', { id: 'd' })],
            ['link-analysis-view', () => components.remove('link-analysis-view', 'd')],
        ];
        for (const [type, write] of cases) {
            components.list(type).subscribe();
            http.expectOne(`${base}/components/${type}`).flush([]);
            write().subscribe();
            http.expectOne(`${base}/components/${type}/d`).flush({});
            components.list(type).subscribe();
            http.expectOne(`${base}/components/${type}`).flush([]);
        }
    });

    it('Components restore clears the list read', () => {
        components.list('dataset').subscribe();
        http.expectOne(`${base}/components/dataset`).flush([]);
        components.restore('dataset', 'd', 3).subscribe();
        http.expectOne(`${base}/components/dataset/d/versions/3/restore`).flush({});
        components.list('dataset').subscribe();
        http.expectOne(`${base}/components/dataset`).flush([]);
    });

    it('saving the access roles clears the cached Space bootstrap', () => {
        spaces.spaceBootstrap('a').subscribe();
        http.expectOne(`${base}/spaces/a/bootstrap`).flush({});
        spaces.spaceBootstrap('a').subscribe();
        expect(http.match(`${base}/spaces/a/bootstrap`).length).toBe(0);
        TestBed.inject(AccessService).saveRoles([]).subscribe();
        http.expectOne(`${base}/access/roles`).flush({});
        spaces.spaceBootstrap('a').subscribe();
        http.expectOne(`${base}/spaces/a/bootstrap`).flush({});
    });

    it('the first-Space probe goes one Space at a time and stops at the first the subject holds', () => {
        spaces.refresh().subscribe();
        http.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
        http.expectOne(`${base}/spaces`).flush([space('alpha'), space('beta'), space('gamma')]);
        // only the first Space is asked; the others wait
        expect(http.match(`${base}/spaces/beta/bootstrap`).length).toBe(0);
        expect(http.match(`${base}/spaces/gamma/bootstrap`).length).toBe(0);
        http.expectOne(`${base}/spaces/alpha/bootstrap`).flush({ session: { capabilities: [] } });
        expect(http.match(`${base}/spaces/gamma/bootstrap`).length).toBe(0);
        http.expectOne(`${base}/spaces/beta/bootstrap`).flush({ session: { capabilities: ['x'] } });
        expect(spaces.currentSpaceId()).toBe('beta');
        expect(http.match(`${base}/spaces/gamma/bootstrap`).length).toBe(0); // stopped after the winner
    });
});
