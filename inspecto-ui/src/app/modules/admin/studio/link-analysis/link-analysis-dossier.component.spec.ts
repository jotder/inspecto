import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Dossier, DossierVerifyResult, InvService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisDossierComponent } from './link-analysis-dossier.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisDossierComponent],
    template: `<inspecto-link-analysis-dossier investigationId="inv-1"></inspecto-link-analysis-dossier>`,
})
class Host {}

const MANIFEST = {
    algorithm: 'SHA-256',
    investigation: 'inv-1',
    at: 2,
    snapshots: [],
    artefacts: [{ path: 'log/0001.json', bytes: 10, sha256: 'sha256:a' }],
    content: { entities: 'sha256:e' },
    root: 'sha256:root',
};

const DOSSIER: Dossier = {
    id: 'inv-1',
    generatedAt: '',
    summary: {
        investigation: 'inv-1',
        title: 'Burners',
        owner: 'alice',
        dataset: 'calls',
        at: 2,
        steps: 2,
        entities: 5,
        links: 4,
        excluded: 1,
        hidden: 0,
        kept: 1,
        snapshots: [],
    },
    topology: { entities: 5, links: 4, degreeTotal: 5 },
    scores: { computedBy: 'client-side', tables: [], note: 'no score vectors in the evidence' },
    ledger: [
        {
            step: 1,
            at: '',
            author: 'alice',
            kind: 'op',
            op: 'seed',
            text: '1. Seeded 1 entity: a.',
            undoneBy: null,
            entitiesAfter: 1,
            workingSetHash: '',
        },
        {
            step: 2,
            at: '',
            author: 'alice',
            kind: 'op',
            op: 'expand',
            text: '2. Expanded one hop.',
            undoneBy: null,
            entitiesAfter: 5,
            workingSetHash: '',
            truncated: true,
        },
    ],
    negativeSpace: {},
    integrity: { intact: true, stepsChecked: 2, failures: [] },
    manifest: MANIFEST,
    renderings: { json: {}, steps: [], method: '' },
};

const FAILED: DossierVerifyResult = {
    id: 'inv-1',
    verified: false,
    selfConsistent: true,
    intact: true,
    submittedRoot: 'sha256:root',
    currentRoot: 'sha256:other',
    changed: ['log/0002.json'],
    missing: [],
    added: ['log/0003.json'],
    contentChanged: ['entities'],
};

function create(overrides: Partial<Record<keyof InvService, unknown>> = {}) {
    const inv = {
        dossier: vi.fn(() => of(DOSSIER)),
        dossierRendering: vi.fn(() => of(new Blob(['steps']))),
        verifyDossier: vi.fn(() => of(FAILED)),
        ...overrides,
    };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [provideNoopAnimations(), { provide: InvService, useValue: inv }],
    });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    const c = f.debugElement.children[0].componentInstance as LinkAnalysisDossierComponent;
    const el = f.nativeElement as HTMLElement;
    return { f, c, el, inv };
}

describe('LinkAnalysisDossierComponent (LA-12)', () => {
    it('builds the dossier and renders summary, integrity, ledger and the manifest root', async () => {
        const { f, c, el, inv } = create();
        await c.build();
        f.detectChanges();
        expect(inv.dossier).toHaveBeenCalledWith('inv-1');
        expect(el.querySelector('[aria-label="Dossier summary"]')?.textContent).toContain('5 entities');
        expect(el.textContent).toContain('Log intact');
        expect(el.querySelectorAll('table[aria-label="Ledger"] tbody tr').length).toBe(2);
        expect(el.textContent).toContain('(read truncated)');
        expect(el.textContent).toContain('sha256:root');
        expect(el.textContent).toContain('no score vectors');
        await expectNoA11yViolations(el);
    });

    it('downloads a rendering at the SAME step as the dossier on screen, as a Blob', async () => {
        const { c, inv } = create();
        const createUrl = vi.fn(() => 'blob:x');
        const revoke = vi.fn();
        Object.assign(URL, { createObjectURL: createUrl, revokeObjectURL: revoke });
        await c.build();
        await c.download('method');
        expect(inv.dossierRendering).toHaveBeenCalledWith('inv-1', 'method', { at: 2 });
        expect(createUrl).toHaveBeenCalled();
        expect(revoke).toHaveBeenCalledWith('blob:x');
    });

    it('verifies the issued manifest and reports changed / added / content changed honestly', async () => {
        const { f, c, el, inv } = create();
        await c.build();
        await c.verifyIssued();
        f.detectChanges();
        expect(inv.verifyDossier).toHaveBeenCalledWith('inv-1', MANIFEST);
        expect(el.textContent).toContain('NOT verified');
        expect(el.textContent).toContain('submitted sha256:root, now sha256:other');
        const detail = el.querySelector('[aria-label="Verification detail"]')!.textContent!;
        expect(detail).toContain('log/0002.json');
        expect(detail).toContain('log/0003.json');
        expect(detail).toContain('entities');
        await expectNoA11yViolations(el);
    });

    it('verifies an uploaded whole dossier by sending its manifest', async () => {
        const { c, inv } = create();
        const file = { name: 'd.json', text: () => Promise.resolve(JSON.stringify({ manifest: MANIFEST })) };
        await c.upload({ target: { files: [file], value: 'x' } } as unknown as Event);
        expect(inv.verifyDossier).toHaveBeenCalledWith('inv-1', MANIFEST);
    });

    it('explains a 404 as not-yours-or-absent', async () => {
        const { f, c, el } = create({
            dossier: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 404, error: { error: 'nope' } }))),
        });
        await c.build();
        f.detectChanges();
        expect(el.textContent).toContain('not available');
        expect(el.textContent).toContain('not yours');
    });
});
