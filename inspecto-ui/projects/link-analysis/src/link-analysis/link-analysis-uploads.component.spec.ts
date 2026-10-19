import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService, UploadRecord, UploadTarget } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisUploadsComponent } from './link-analysis-uploads.component';
import { uploadErrorMessage, uploadFileProblem, uploadStateView } from './upload-state';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisUploadsComponent],
    template: `<inspecto-link-analysis-uploads></inspecto-link-analysis-uploads>`,
})
class Host {}

const TARGET: UploadTarget = { collector: 'up_feed', pipeline: 'UP_FEED', includes: ['glob:**/UPL_*.csv'], maxBytes: 1024 };
const PENDING: UploadRecord = {
    collector: 'up_feed', file: 'UPL_1.csv', bytes: 110, lines: 3, sha256: 'abc', uploader: 'me', replaced: false,
    landedAt: '2026-10-19T10:00:00Z', state: 'pending',
};
const DONE: UploadRecord = { ...PENDING, file: 'UPL_0.csv', state: 'ingested', rejectedRows: 2 };

const http = (status: number, message = 'server says no') =>
    throwError(() => new HttpErrorResponse({ status, error: { error: { message } } }));

interface Options {
    targets?: UploadTarget[];
    uploads?: UploadRecord[];
    canUpload?: boolean;
    geoLink?: boolean;
    confirm?: boolean;
    uploadResult?: unknown;
}

function create({ targets = [TARGET], uploads = [DONE, PENDING], canUpload = true, geoLink = true, confirm = true, uploadResult }: Options = {}) {
    const inv = {
        uploadTargets: vi.fn(() => of({ collectors: targets })),
        listUploads: vi.fn(() => of({ collector: 'up_feed', total: uploads.length, truncated: false, uploads })),
        uploadCsv: vi.fn(() => uploadResult ?? of({ collector: 'up_feed', pipeline: 'UP_FEED', file: 'UPL_2.csv', bytes: 50, sha256: 'x', replaced: false, landedAt: 't' })),
        deleteUpload: vi.fn(() => of({ collector: 'up_feed', file: 'UPL_1.csv', deleted: true })),
    };
    const confirmSvc = { confirmDestructive: vi.fn(async () => confirm) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: InspectoConfirmService, useValue: confirmSvc },
            { provide: LA_FEATURES, useValue: { ops: signal(false), exchange: signal(false), geoLink: signal(geoLink) } },
            { provide: LensService, useValue: { canUploadData: signal(canUpload) } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const cmp = fixture.debugElement.children[0].componentInstance as LinkAnalysisUploadsComponent;
    const el = fixture.nativeElement as HTMLElement;
    const settle = async () => {
        await fixture.whenStable();
        fixture.detectChanges();
    };
    return { cmp, inv, confirmSvc, el, settle };
}

const csv = (name: string, size = 50) => new File([new Uint8Array(size).fill(65)], name, { type: 'text/csv' });

describe('upload-state helpers', () => {
    it('refuses what the browser can see, in plain words', () => {
        expect(uploadFileProblem(null, TARGET)).toContain('Choose a CSV');
        expect(uploadFileProblem({ name: 'a.xlsx', size: 5 }, TARGET)).toContain('Export the sheet as CSV');
        expect(uploadFileProblem({ name: 'a.txt', size: 5 }, TARGET)).toContain('Only .csv');
        expect(uploadFileProblem({ name: 'my file.csv', size: 5 }, TARGET)).toContain('letters, digits');
        expect(uploadFileProblem({ name: 'a.csv', size: 0 }, TARGET)).toContain('empty');
        expect(uploadFileProblem({ name: 'a.csv', size: 2048 }, TARGET)).toContain('over the 1.0 KiB upload limit');
        expect(uploadFileProblem({ name: 'UPL_1.csv', size: 10 }, TARGET)).toBe('');
    });

    it('words each refusal with a next step and never a capability id', () => {
        const e = (status: number) => new HttpErrorResponse({ status, error: { error: { message: 'm' } } });
        expect(uploadErrorMessage(e(409), 'x')).toContain('still waiting');
        expect(uploadErrorMessage(e(409), 'x', 'delete')).toContain('already picked this file up');
        expect(uploadErrorMessage(e(413), 'x')).toContain('Next step');
        expect(uploadErrorMessage(e(429), 'x')).toContain('Wait a few seconds');
        expect(uploadErrorMessage(e(404), 'x')).toContain('does not accept uploads');
        expect(uploadErrorMessage(e(403), 'x', 'delete')).toContain('your own uploads');
        expect(uploadErrorMessage(e(403), 'x')).not.toContain('canUploadData');
        expect(uploadErrorMessage(e(503), 'x')).toContain('not available');
    });

    it('maps ingest state to a badge', () => {
        expect(uploadStateView({ state: 'pending' }).label).toBe('Waiting for the Collector');
        expect(uploadStateView({ state: 'ingested' })).toEqual({ tone: 'success', label: 'Ingested' });
        expect(uploadStateView({ state: 'ingested', rejectedRows: 2 }).label).toBe('Ingested, 2 rows rejected');
        expect(uploadStateView({ state: 'quarantined', quarantineReason: 'parse' }).label).toBe('Quarantined (parse)');
    });
});

describe('LinkAnalysisUploadsComponent', () => {
    it('lists own uploads with their ingest state, preselects the only Collector, and passes axe', async () => {
        const { el, inv, settle } = create();
        await settle();
        expect(inv.uploadTargets).toHaveBeenCalledTimes(1);
        expect(inv.listUploads).toHaveBeenCalledWith('up_feed');
        const rows = Array.from(el.querySelectorAll('[aria-label="Your uploads"] li'));
        expect(rows).toHaveLength(2);
        expect(rows[0].textContent).toContain('Ingested, 2 rows rejected');
        expect(rows[1].textContent).toContain('Waiting for the Collector');
        expect(el.querySelector('button[aria-label="Delete upload UPL_1.csv"]')).not.toBeNull();
        expect(el.querySelector('button[aria-label="Delete upload UPL_0.csv"]')).toBeNull();   // ingested: cannot be withdrawn
        await expectNoA11yViolations(el);
    });

    it('is absent without the capability or off the module, and calls nothing', async () => {
        for (const o of [{ canUpload: false }, { geoLink: false }]) {
            TestBed.resetTestingModule();
            const { el, inv, settle } = create(o);
            await settle();
            expect(el.querySelector('section')).toBeNull();
            expect(inv.uploadTargets).not.toHaveBeenCalled();
        }
    });

    it('explains an empty target list and passes axe', async () => {
        const { el, settle } = create({ targets: [] });
        await settle();
        expect(el.querySelector('inspecto-empty-state')?.textContent).toContain('No Collector accepts uploads');
        await expectNoA11yViolations(el);
    });

    it('uploads the chosen file, reports it, and reloads the list', async () => {
        const { cmp, el, inv, settle } = create();
        await settle();
        const f = csv('UPL_2.csv');
        cmp.file.set(f);
        await cmp.upload();
        await settle();
        expect(inv.uploadCsv).toHaveBeenCalledWith('up_feed', f, false);
        expect(el.textContent).toContain('Uploaded UPL_2.csv');
        expect(inv.listUploads).toHaveBeenCalledTimes(2);
        expect(cmp.file()).toBeNull();
    });

    it('refuses a bad file before any request and shows why', async () => {
        const { cmp, el, inv, settle } = create();
        await settle();
        cmp.file.set(csv('sheet.xlsx'));
        await cmp.upload();
        await settle();
        expect(inv.uploadCsv).not.toHaveBeenCalled();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('Export the sheet as CSV');
    });

    it('offers Replace after a 409 and sends replace=true only when clicked', async () => {
        const { cmp, el, inv, settle } = create({ uploadResult: http(409, 'already in the inbox') });
        await settle();
        const f = csv('UPL_1.csv');
        cmp.file.set(f);
        await cmp.upload();
        await settle();
        expect(el.textContent).toContain('still waiting');
        expect(el.querySelector('[data-testid="la-upload-replace"]')).not.toBeNull();
        expect(inv.uploadCsv).toHaveBeenLastCalledWith('up_feed', f, false);
        inv.uploadCsv.mockReturnValue(of({ collector: 'up_feed', pipeline: 'P', file: 'UPL_1.csv', bytes: 50, sha256: 'x', replaced: true, landedAt: 't' }));
        await cmp.upload(true);
        expect(inv.uploadCsv).toHaveBeenLastCalledWith('up_feed', f, true);
    });

    it("never offers Replace for a waiting file that is not the caller's own", async () => {
        const { cmp, el, settle } = create({ uploadResult: http(409, 'already in the inbox') });
        await settle();
        cmp.file.set(csv('UPL_7.csv'));   // not in the caller's list: someone else's waiting file
        await cmp.upload();
        await settle();
        expect(el.textContent).toContain('still waiting');
        expect(el.querySelector('[data-testid="la-upload-replace"]')).toBeNull();
        expect(cmp.canReplace()).toBe(false);
    });

    it('says the limit and the next step on a 413', async () => {
        const { cmp, el, settle } = create({ uploadResult: http(413, 'over upload.max_bytes (1024 bytes)') });
        await settle();
        cmp.file.set(csv('UPL_2.csv'));
        await cmp.upload();
        await settle();
        expect(el.textContent).toContain('over upload.max_bytes');
        expect(el.textContent).toContain('Next step');
    });

    it('deletes a waiting upload only after confirmation', async () => {
        const { cmp, inv, confirmSvc, settle } = create({ confirm: false });
        await settle();
        await cmp.remove(PENDING);
        expect(confirmSvc.confirmDestructive).toHaveBeenCalledTimes(1);
        expect(inv.deleteUpload).not.toHaveBeenCalled();
        confirmSvc.confirmDestructive.mockResolvedValue(true);
        await cmp.remove(PENDING);
        expect(inv.deleteUpload).toHaveBeenCalledWith('up_feed', 'UPL_1.csv');
        expect(inv.listUploads).toHaveBeenCalledTimes(2);   // the first read, then the reload after the delete
    });
});
