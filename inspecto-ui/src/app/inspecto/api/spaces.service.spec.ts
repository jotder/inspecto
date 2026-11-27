import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Space, SpacesService } from './spaces.service';
import { environment } from '../../../environments/environment';

const base = environment.apiBaseUrl + '/v1'; // W7: apiUrl() builds /api/v1 paths

const SPACES: Space[] = [
    {
        id: 'alpha',
        displayName: 'Alpha',
        description: '',
        createdAt: '2026-06-23 10:00:00',
    },
    {
        id: 'beta',
        displayName: 'Beta',
        description: '',
        createdAt: '2026-06-23 11:00:00',
    },
];

describe('SpacesService', () => {
    let svc: SpacesService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        localStorage.clear();
        TestBed.configureTestingModule({
            providers: [SpacesService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(SpacesService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('refresh() loads the capability flag + list and auto-selects a space', () => {
        svc.refresh().subscribe();
        httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
        httpMock.expectOne(`${base}/spaces`).flush(SPACES);
        expect(svc.multiSpace()).toBe(true);
        expect(svc.availableSpaces().length).toBe(2);
        // D7: no 'default' → probe each Space's bootstrap; none holds a capability (anonymous) → first space
        for (const id of ['alpha', 'beta']) httpMock.expectOne(`${base}/spaces/${id}/bootstrap`).flush({ session: {} });
        expect(svc.currentSpaceId()).toBe('alpha');
    });

    it('refresh() picks the first Space where the subject holds a capability, not the alphabetical first (D7)', () => {
        svc.refresh().subscribe();
        httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
        httpMock.expectOne(`${base}/spaces`).flush(SPACES);
        httpMock.expectOne(`${base}/spaces/alpha/bootstrap`).flush({ session: { capabilities: [] } });
        httpMock.expectOne(`${base}/spaces/beta/bootstrap`).flush({ session: { capabilities: ['x'] } });
        expect(svc.currentSpaceId()).toBe('beta');
    });

    it('refresh() survives a 200 carrying a non-array body', () => {
        // Containment for the NG0201 bootstrap cascade: `spaces.some(...)` used to throw on this.
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        svc.refresh().subscribe();
        httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
        httpMock.expectOne(`${base}/spaces`).flush({ data: SPACES } as unknown as Space[]);
        expect(svc.availableSpaces()).toEqual([]);
        expect(svc.currentSpaceId()).toBeNull();
        expect(warn).toHaveBeenCalled(); // loud, not silent — the root cause is still unknown
        warn.mockRestore();
    });

    it('refresh() prefers a persisted selection that is still valid', () => {
        svc.selectSpace('beta');
        svc.refresh().subscribe();
        httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
        httpMock.expectOne(`${base}/spaces`).flush(SPACES);
        expect(svc.currentSpaceId()).toBe('beta');
    });

    it('refresh() clears the selection on a single-tenant server', () => {
        svc.selectSpace('alpha');
        svc.refresh().subscribe();
        httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: false });
        httpMock
            .expectOne(`${base}/spaces`)
            .flush([{ id: 'default', displayName: '', description: '', createdAt: '' }]);
        expect(svc.multiSpace()).toBe(false);
        expect(svc.currentSpaceId()).toBeNull();
    });

    it('create() POSTs the snake_case body to /spaces', () => {
        svc.create({ id: 'gamma', display_name: 'Gamma', description: 'x' }).subscribe();
        const req = httpMock.expectOne(`${base}/spaces`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({
            id: 'gamma',
            display_name: 'Gamma',
            description: 'x',
        });
        req.flush(SPACES[0]);
    });

    it('remove() DELETEs /spaces/{id} with ?purge=true only when purging', () => {
        svc.remove('alpha', true).subscribe();
        httpMock.expectOne((r) => r.url === `${base}/spaces/alpha` && r.params.get('purge') === 'true').flush({});
        svc.remove('alpha', false).subscribe();
        httpMock.expectOne((r) => r.url === `${base}/spaces/alpha` && !r.params.has('purge')).flush({});
    });

    it('exportSpace() GETs the whole-space zip as a blob', () => {
        svc.exportSpace('alpha').subscribe();
        const req = httpMock.expectOne(`${base}/spaces/alpha/export`);
        expect(req.request.method).toBe('GET');
        expect(req.request.responseType).toBe('blob');
        req.flush(new Blob());
    });

    it('importPreview() POSTs the zip to /import/preview', () => {
        svc.importPreview('alpha', new Blob()).subscribe();
        const req = httpMock.expectOne(`${base}/spaces/alpha/import/preview`);
        expect(req.request.method).toBe('POST');
        req.flush({
            kind: 'data_source',
            sourceSpace: null,
            dataSources: [],
            files: [],
            hasSpaceToon: false,
            conflicts: [],
            findings: {},
            valid: true,
        });
    });

    it('importBundle() adds on_conflict=overwrite only when overwriting', () => {
        svc.importBundle('alpha', new Blob(), true).subscribe();
        httpMock
            .expectOne((r) => r.url === `${base}/spaces/alpha/import` && r.params.get('on_conflict') === 'overwrite')
            .flush({});
        svc.importBundle('alpha', new Blob(), false).subscribe();
        httpMock.expectOne((r) => r.url === `${base}/spaces/alpha/import` && !r.params.has('on_conflict')).flush({});
    });

    it('createFromBundle() POSTs the zip to /spaces/import?id=', () => {
        svc.createFromBundle('delta', new Blob()).subscribe();
        const req = httpMock.expectOne((r) => r.url === `${base}/spaces/import` && r.params.get('id') === 'delta');
        expect(req.request.method).toBe('POST');
        req.flush(SPACES[0]);
    });

    // ── SPACE-LOCATIONS-1: Space folder and Data folder ──────────────────────────

    const DISABLED = {
        status: 409,
        statusText: 'Conflict',
    };
    const disabledBody = {
        error: { errorCode: 'SPACE_LOCATIONS_DISABLED', message: 'spaces.allowed.roots is not set' },
    };

    it('checkLocation() POSTs path, kind and id to /spaces/_locations/check', () => {
        let ok: boolean | undefined;
        svc.checkLocation('/srv/spaces/delta', 'space', 'delta').subscribe((r) => (ok = r.ok));
        const req = httpMock.expectOne(`${base}/spaces/_locations/check`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ path: '/srv/spaces/delta', kind: 'space', id: 'delta' });
        req.flush({ ok: true, checks: [{ check: 'allowed', ok: true, reason: 'under /srv' }], extra: 1 });
        expect(ok).toBe(true);
        expect(svc.locationsDisabled()).toBe(false);
    });

    it('checkLocation() leaves the id out when none is given', () => {
        svc.checkLocation('/srv/data/delta', 'data').subscribe();
        const req = httpMock.expectOne(`${base}/spaces/_locations/check`);
        expect(req.request.body).toEqual({ path: '/srv/data/delta', kind: 'data' });
        req.flush({ ok: false, checks: [] });
    });

    it('a 409 SPACE_LOCATIONS_DISABLED switches the folder actions off', () => {
        let failed = false;
        svc.checkLocation('/x', 'space').subscribe({ error: () => (failed = true) });
        httpMock.expectOne(`${base}/spaces/_locations/check`).flush(disabledBody, DISABLED);
        expect(failed).toBe(true);
        expect(svc.locationsDisabled()).toBe(true);
    });

    it('another 409 does not switch the folder actions off', () => {
        svc.attach('/x/delta').subscribe({ error: () => undefined });
        httpMock
            .expectOne(`${base}/spaces/attach`)
            .flush({ error: { errorCode: 'CONFLICT', message: 'taken' } }, DISABLED);
        expect(svc.locationsDisabled()).toBe(false);
    });

    it('GET /spaces/_meta locationsEnabled: false switches the folder actions off', () => {
        expect(svc.locationsDisabled()).toBe(false);
        svc.refresh().subscribe();
        httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true, locationsEnabled: false });
        httpMock.expectOne(`${base}/spaces`).flush([{ ...SPACES[0], id: 'default' }]);
        expect(svc.locationsDisabled()).toBe(true);
    });

    it('an older server without locationsEnabled leaves the folder actions on', () => {
        svc.refresh().subscribe();
        httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
        httpMock.expectOne(`${base}/spaces`).flush([{ ...SPACES[0], id: 'default' }]);
        expect(svc.locationsDisabled()).toBe(false);
    });

    it('attach() POSTs the folder and optional id to /spaces/attach', () => {
        svc.attach('/srv/elsewhere/delta', 'delta').subscribe();
        const req = httpMock.expectOne(`${base}/spaces/attach`);
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ path: '/srv/elsewhere/delta', id: 'delta' });
        req.flush(SPACES[0]);
    });

    it('detach() POSTs to /spaces/{id}/detach', () => {
        svc.detach('alpha').subscribe();
        const req = httpMock.expectOne(`${base}/spaces/alpha/detach`);
        expect(req.request.method).toBe('POST');
        req.flush({ id: 'alpha', detached: true });
    });

    it('setDataRoot() PUTs the path, or null to use the Space folder', () => {
        svc.setDataRoot('alpha', '/srv/data/alpha').subscribe();
        const a = httpMock.expectOne(`${base}/spaces/alpha/data-root`);
        expect(a.request.method).toBe('PUT');
        expect(a.request.body).toEqual({ path: '/srv/data/alpha' });
        a.flush(SPACES[0]);
        svc.setDataRoot('alpha', null).subscribe();
        const b = httpMock.expectOne(`${base}/spaces/alpha/data-root`);
        expect(b.request.body).toEqual({ path: null });
        b.flush(SPACES[0]);
    });

    it('create() sends the chosen Space folder and Data folder', () => {
        svc.create({ id: 'delta', path: '/srv/spaces/delta', dataRoot: '/srv/data/delta' }).subscribe();
        const req = httpMock.expectOne(`${base}/spaces`);
        expect(req.request.body).toEqual({ id: 'delta', path: '/srv/spaces/delta', dataRoot: '/srv/data/delta' });
        req.flush(SPACES[0]);
    });

    it('loaded() tracks refresh() and resetForSession() clears the prior user roster and selection', () => {
        expect(svc.loaded()).toBe(false);
        svc.refresh().subscribe();
        httpMock.expectOne(`${base}/spaces/_meta`).flush({ multiSpace: true });
        httpMock.expectOne(`${base}/spaces`).flush(SPACES);
        httpMock.expectOne(`${base}/spaces/alpha/bootstrap`).flush({ session: { capabilities: ['x'] } });
        expect(svc.loaded()).toBe(true);
        expect(svc.availableSpaces().length).toBe(2);
        expect(svc.currentSpaceId()).toBe('alpha');

        svc.resetForSession(true);
        expect(svc.loaded()).toBe(false);
        expect(svc.availableSpaces()).toEqual([]);
        expect(svc.currentSpaceId()).toBeNull();
    });
});
