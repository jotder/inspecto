import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { InvService } from './inv.service';
import { environment } from 'environments/environment';

const base = environment.apiBaseUrl + '/v1';

/** Pinned to `UploadRoutes` (LA-ANALYST-UPLOAD-1 + LA-I2-GAPS-RESIDUALS-1). */
describe('InvService analyst upload', () => {
    let svc: InvService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [InvService, provideHttpClient(withXhr()), provideHttpClientTesting()],
        });
        svc = TestBed.inject(InvService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('reads the targets and one Collector\'s uploads', () => {
        svc.uploadTargets().subscribe();
        httpMock.expectOne(`${base}/inv/uploads`).flush({ collectors: [] });
        svc.listUploads('up feed').subscribe();
        const r = httpMock.expectOne(`${base}/inv/uploads/up%20feed`);
        expect(r.request.method).toBe('GET');
        r.flush({ collector: 'up feed', total: 0, truncated: false, uploads: [] });
    });

    it('posts the file itself as text/csv with name, and replace only when asked', () => {
        const f = new File(['a,b\n1,2\n'], 'UPL_1.csv', { type: 'text/csv' });
        svc.uploadCsv('up_feed', f).subscribe();
        const a = httpMock.expectOne((r) => r.url === `${base}/inv/uploads/up_feed`);
        expect(a.request.method).toBe('POST');
        expect(a.request.body).toBe(f);
        expect(a.request.headers.get('Content-Type')).toBe('text/csv');
        expect(a.request.params.get('name')).toBe('UPL_1.csv');
        expect(a.request.params.has('replace')).toBe(false);
        a.flush({});

        svc.uploadCsv('up_feed', f, true).subscribe();
        const b = httpMock.expectOne((r) => r.url === `${base}/inv/uploads/up_feed`);
        expect(b.request.params.get('replace')).toBe('true');
        b.flush({});
    });

    it('deletes by Collector and file', () => {
        svc.deleteUpload('up_feed', 'UPL_1.csv').subscribe();
        const r = httpMock.expectOne(`${base}/inv/uploads/up_feed/UPL_1.csv`);
        expect(r.request.method).toBe('DELETE');
        r.flush({ collector: 'up_feed', file: 'UPL_1.csv', deleted: true });
    });
});
