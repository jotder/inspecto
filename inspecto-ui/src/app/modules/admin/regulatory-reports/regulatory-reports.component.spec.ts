import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import {
    LensService,
    RegulatoryReport,
    RegulatoryReportDetail,
    RegulatoryReportsService,
    ReportTemplate,
} from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { RegulatoryReportsComponent } from './regulatory-reports.component';

const TEMPLATE: ReportTemplate = {
    id: 'sample-sar',
    title: 'Sample suspicious activity report',
    description: 'Illustrative only',
    format: 'xml',
    origin: 'built-in',
    delivery: { kind: 'file-drop', dir: 'regulatory-submissions/sample-sar' },
    inputs: ['reportingEntity', 'narrative'],
    fields: [{ name: 'narrative', source: 'input.narrative', required: true, maxLength: 20000 }],
};
const REPORT: RegulatoryReport = {
    id: 'rr-20261009120000-abcdef',
    status: 'pending',
    template: 'sample-sar',
    templateTitle: 'Sample suspicious activity report',
    format: 'xml',
    mediaType: 'application/xml',
    caseId: 'CASE-1',
    incidentId: null,
    subjectTitle: 'Structuring suspected',
    author: 'author-1',
    reason: null,
    createdAt: '2026-10-09T12:00:00Z',
    expiresAt: '2026-10-16T12:00:00Z',
    contentSha256: 'ab'.repeat(32),
    delivery: {
        kind: 'file-drop',
        dir: 'C:/space/regulatory-submissions/sample-sar',
        fileName: 'rr-20261009120000-abcdef.xml',
    },
    requestedBy: 'author-1',
    approver: null,
    submission: null,
    history: [],
};
const DETAIL: RegulatoryReportDetail = {
    ...REPORT,
    content: '<suspiciousActivityReport>\n  <narrative>Cash split</narrative>\n</suspiciousActivityReport>\n',
    inputs: { narrative: 'Cash split' },
    sourceChanged: false,
    approverCheck: 'ok',
};

async function create(
    overrides: Partial<Record<keyof RegulatoryReportsService, unknown>> = {},
    caps: { work?: boolean; approve?: boolean } = { work: true, approve: true },
) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        templates: vi.fn(() => of({ items: [TEMPLATE], problems: [] })),
        list: vi.fn(() =>
            of({ items: [REPORT, { ...REPORT, id: 'rr-old', status: 'submitted' }], total: 2, truncated: false }),
        ),
        get: vi.fn(() => of(DETAIL)),
        requestApproval: vi.fn(() => of({ ...DETAIL, status: 'pending' })),
        approve: vi.fn(() =>
            of({
                ...DETAIL,
                status: 'submitted',
                approver: 'checker-1',
                submission: {
                    kind: 'file-drop',
                    file: 'C:/drop/x.xml',
                    at: '2026-10-09T13:00:00Z',
                    alreadyPresent: false,
                },
            }),
        ),
        decline: vi.fn(() => of({ ...DETAIL, status: 'declined' })),
        retry: vi.fn(() => of({ ...DETAIL, status: 'submitted' })),
        ...overrides,
    } as unknown as RegulatoryReportsService;
    const dialog = { open: vi.fn(() => ({ afterClosed: () => of({ ...DETAIL, status: 'draft' }) })) };
    TestBed.configureTestingModule({
        imports: [RegulatoryReportsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: RegulatoryReportsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            {
                provide: InspectoConfirmService,
                useValue: { confirm: vi.fn(async () => true), confirmDestructive: vi.fn(async () => true) },
            },
            {
                provide: LensService,
                useValue: {
                    canWorkIncidents: () => caps.work ?? false,
                    canApproveChanges: () => caps.approve ?? false,
                },
            },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog }); // the data-table injects the real one otherwise
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(RegulatoryReportsComponent);
    fixture.detectChanges();
    return { fixture, api, toastr, dialog };
}

const buttons = (el: HTMLElement): string[] =>
    Array.from(el.querySelectorAll('button')).map((b) => (b.textContent ?? '').trim());

describe('RegulatoryReportsComponent', () => {
    it('Waiting lists only what a person still has to act on; Submitted asks the server for the log', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        expect(c.reports().map((r) => r.id)).toEqual([REPORT.id]);
        c.setFilter('submitted');
        expect(api.list).toHaveBeenLastCalledWith({ status: 'submitted' });
    });

    it('a pending report shows its content and lets an approver approve, which submits it', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.open({ id: REPORT.id });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('pre')?.textContent).toContain('<narrative>Cash split</narrative>');
        expect(buttons(el)).toContain('Approve and submit');
        expect(buttons(el)).not.toContain('Send for approval');
        await c.approve();
        expect(api.approve).toHaveBeenCalledWith(REPORT.id, undefined);
        fixture.detectChanges();
        expect(c.selected()?.status).toBe('submitted');
        expect(el.textContent).toContain('C:/drop/x.xml');
        await expectNoA11yViolations(el);
    });

    it('a draft offers Send for approval to a maker and no decision', async () => {
        const { fixture, api } = await create(
            { get: vi.fn(() => of({ ...DETAIL, status: 'draft', requestedBy: null })) },
            { work: true, approve: false },
        );
        const c = fixture.componentInstance;
        c.open({ id: REPORT.id });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(buttons(el)).toContain('Send for approval');
        expect(buttons(el)).not.toContain('Approve and submit');
        await c.requestApproval();
        expect(api.requestApproval).toHaveBeenCalledWith(REPORT.id, undefined);
    });

    it("shows the server's four-eyes refusal in place", async () => {
        const { fixture } = await create({
            approve: vi.fn(() =>
                throwError(
                    () =>
                        new HttpErrorResponse({
                            status: 403,
                            error: { error: { message: "four-eyes: 'author-1' made this report" } },
                        }),
                ),
            ),
        });
        const c = fixture.componentInstance;
        c.open({ id: REPORT.id });
        await c.approve();
        fixture.detectChanges();
        expect(c.actError()).toContain('four-eyes');
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('four-eyes');
    });

    it('warns the approver when the Case changed since the content was rendered, and when no one can approve', async () => {
        const { fixture } = await create({
            get: vi.fn(() => of({ ...DETAIL, sourceChanged: true, approverCheck: 'none-eligible' })),
        });
        fixture.componentInstance.open({ id: REPORT.id });
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('The source changed since this was drafted');
        expect(text).toContain('No one can approve this');
    });

    it('a failed submission offers Retry to an approver', async () => {
        const { fixture, api } = await create({
            get: vi.fn(() => of({ ...DETAIL, status: 'failed', lastError: 'a different file already sits there' })),
        });
        const c = fixture.componentInstance;
        c.open({ id: REPORT.id });
        fixture.detectChanges();
        expect(buttons(fixture.nativeElement)).toContain('Retry the submission');
        c.retry();
        expect(api.retry).toHaveBeenCalledWith(REPORT.id);
    });

    it('New report is offered only with canWorkIncidents, opens the draft dialog and shows the draft', async () => {
        const { fixture, dialog } = await create();
        const el = fixture.nativeElement as HTMLElement;
        expect(buttons(el)).toContain('New report');
        fixture.componentInstance.newReport();
        expect(dialog.open).toHaveBeenCalled();
        expect(fixture.componentInstance.selected()?.status).toBe('draft');
    });

    it('hides New report from a reader without canWorkIncidents', async () => {
        const { fixture } = await create({}, { work: false, approve: true });
        expect(buttons(fixture.nativeElement)).not.toContain('New report');
    });
});
