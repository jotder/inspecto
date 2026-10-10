import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { Dossier, DossierBundleVerifyResult, DossierVerifyResult, InvService } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
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

const BUNDLE = { format: 'inspecto-dossier-bundle/1', investigationId: 'inv-1', seal: { algorithm: 'SHA-256', value: 'x' } };

const BUNDLE_TAMPERED: DossierBundleVerifyResult = {
    id: 'inv-1',
    verified: false,
    sealIntact: false,
    rootMatches: true,
    referencesIntact: true,
    referencesAddedSince: 0,
    problems: ["the bundle's seal does not match its content — it was edited after export"],
    custody: { verified: true },
};

function create(overrides: Partial<Record<keyof InvService, unknown>> = {}) {
    const inv = {
        dossier: vi.fn(() => of(DOSSIER)),
        dossierRendering: vi.fn(() => of(new Blob(['steps']))),
        verifyDossier: vi.fn(() => of(FAILED)),
        dossierBundle: vi.fn(() => of(BUNDLE)),
        verifyDossierBundle: vi.fn(() => of(BUNDLE_TAMPERED)),
        sealedSnapshotIds: vi.fn(() =>
            of({ ids: Array.from({ length: 22 }, (_, i) => `s${i}`), total: 30, truncated: true }),
        ),
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
        expect(inv.dossier).toHaveBeenCalledWith('inv-1', { at: undefined, snapshots: [] });
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
        expect(inv.dossierRendering).toHaveBeenCalledWith('inv-1', 'method', { at: 2, snapshots: [] });
        expect(createUrl).toHaveBeenCalled();
        expect(revoke).toHaveBeenCalledWith('blob:x');
    });

    it('downloads the printable HTML rendering through the same Blob path, saved as .html', async () => {
        const { c, inv, f, el } = create();
        const createUrl = vi.fn(() => 'blob:h');
        Object.assign(URL, { createObjectURL: createUrl, revokeObjectURL: vi.fn() });
        const names: string[] = [];
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
            this: HTMLAnchorElement,
        ) {
            names.push(this.download);
        });
        await c.build();
        f.detectChanges();
        const button = Array.from((el as HTMLElement).querySelectorAll('button')).find(
            (b) => b.textContent?.trim() === 'Download HTML',
        );
        expect(button).toBeTruthy();
        await c.download('html');
        expect(inv.dossierRendering).toHaveBeenCalledWith('inv-1', 'html', { at: 2, snapshots: [] });
        expect(names).toEqual(['inv-1-html.html']);
        click.mockRestore();
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

    it('downloads the sealed bundle at the on-screen step, saved as <id>-bundle.json', async () => {
        const { c, inv } = create();
        Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:b'), revokeObjectURL: vi.fn() });
        const names: string[] = [];
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
            this: HTMLAnchorElement,
        ) {
            names.push(this.download);
        });
        await c.build();
        await c.downloadBundle();
        expect(inv.dossierBundle).toHaveBeenCalledWith('inv-1', { at: 2, snapshots: [] });
        expect(names).toEqual(['inv-1-bundle.json']);
        click.mockRestore();
    });

    it('verifies an uploaded bundle WHOLE and says a broken seal means the file was edited', async () => {
        const { f, c, el, inv } = create();
        const file = { name: 'b.json', text: () => Promise.resolve(JSON.stringify(BUNDLE)) };
        await c.uploadBundle({ target: { files: [file], value: 'x' } } as unknown as Event);
        f.detectChanges();
        expect(inv.verifyDossierBundle).toHaveBeenCalledWith('inv-1', BUNDLE);
        expect(el.textContent).toContain('Bundle NOT verified');
        expect(el.textContent).toContain('edited after export');
        expect(el.querySelector('[aria-label="Bundle verification detail"]')!.textContent).toContain(
            'no — edited after export',
        );
        await expectNoA11yViolations(el);
    });

    it('refuses a non-JSON bundle upload without calling the server', async () => {
        const { f, c, el, inv } = create();
        const file = { name: 'b.json', text: () => Promise.resolve('not json') };
        await c.uploadBundle({ target: { files: [file], value: 'x' } } as unknown as Event);
        f.detectChanges();
        expect(inv.verifyDossierBundle).not.toHaveBeenCalled();
        expect(el.textContent).toContain('b.json is not JSON');
    });

    it('builds at a chosen step with chosen snapshots, capped at 20, and refuses a step beyond the log', async () => {
        const { f, c, el, inv } = create();
        await c.build();
        await c.loadSnapshots();
        f.detectChanges();
        expect(inv.sealedSnapshotIds).toHaveBeenCalledWith(100);
        expect(el.textContent).toContain('(showing 22 of 30)');
        for (let i = 0; i < 22; i++) c.toggleSnapshot(`s${i}`, true);
        expect(c.selected().length).toBe(20);
        f.detectChanges();
        const boxes = Array.from(el.querySelectorAll<HTMLInputElement>('mat-checkbox input'));
        expect(boxes.filter((b) => b.disabled).length).toBe(2);
        await expectNoA11yViolations(el);

        c.toggleSnapshot('s0', false);
        c.atControl.setValue(3); // the log has 2 steps
        await c.build();
        f.detectChanges();
        expect(inv.dossier).toHaveBeenCalledTimes(1);
        expect(el.querySelector('mat-error')?.textContent).toContain('from 0 to 2');

        c.atControl.setValue(1);
        await c.build();
        expect(inv.dossier).toHaveBeenCalledWith('inv-1', {
            at: 1,
            snapshots: Array.from({ length: 19 }, (_, i) => `s${i + 1}`),
        });
    });

    it('explains a 503 on the snapshot list in place', async () => {
        const { f, c, el } = create({
            sealedSnapshotIds: vi.fn(() =>
                throwError(() => new HttpErrorResponse({ status: 503, error: { error: 'no write root' } })),
            ),
        });
        await c.loadSnapshots();
        f.detectChanges();
        expect(el.textContent).toContain('Investigations are not available here');
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
