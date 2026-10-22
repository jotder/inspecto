import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiUrl, LensService } from '@inspecto/core/api';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { AddDataAccess } from './add-data-access';
import { AUTOMATION_FALLBACK, ImportDataset, ImportSummary, SELF_IMPORT_OFF } from './add-data.model';
import { summary } from './add-data.testing';
import { ImportDatasetCardComponent } from './import-dataset-card.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [ImportDatasetCardComponent],
    template: `<inspecto-import-dataset-card
        [dataset]="dataset()"
        [summary]="last()"
        [editorRoute]="editorRoute()"
        (imported)="got.push($event)"
    ></inspecto-import-dataset-card>`,
})
class HostComponent {
    readonly dataset = signal<ImportDataset>({ id: 'transfers', kind: 'stream', importPipeline: 'transfers' });
    readonly last = signal<ImportSummary | null>(null);
    readonly editorRoute = signal<string | null>(null);
    readonly got: ImportSummary[] = [];
}

interface Opts {
    edition?: string;
    authMode?: 'none' | 'oidc';
    features?: Record<string, boolean>;
    canAuthor?: boolean;
    editorRoute?: string | null;
    kind?: 'stream' | 'reference';
}

let http: HttpTestingController;

function create(o: Opts = {}) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(),
            provideHttpClientTesting(),
            provideRouter([]),
            {
                provide: LA_FEATURES,
                useValue: {
                    ops: signal(false),
                    exchange: signal(false),
                    geoLink: signal(true),
                    investigations: signal(false),
                    signIn: signal((o.authMode ?? 'none') === 'oidc'),
                    edition: signal(o.edition ?? 'basic'),
                    flags: signal(o.features ?? {}),
                    featureReasons: signal({}),
                },
            },
            {
                provide: LensService,
                useValue: { canUploadData: signal(true), canAuthorWorkbench: signal(o.canAuthor ?? true) },
            },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.editorRoute.set(o.editorRoute ?? null);
    if (o.kind) fixture.componentInstance.dataset.set({ id: 'accounts', kind: o.kind });
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

describe('ImportDatasetCardComponent - the Dataset card of an import (UC-06, UC-14, DT-14)', () => {
    afterEach(() => http?.verify());

    it('Basic: "one-time import", Add a file beside Run now, and Keep this up to date locked with its reason (no violations)', async () => {
        const { fixture, el } = create();
        fixture.componentInstance.last.set(summary());
        fixture.detectChanges();
        expect(el.textContent).toContain('one-time import');
        expect(el.querySelector('h3')!.textContent).toBe('transfers');
        expect(q(el, 'add-a-file')).not.toBeNull();
        expect(q(el, 'run-now')).not.toBeNull();
        expect(q(el, 'add-a-file')!.parentElement).toBe(q(el, 'run-now')!.parentElement); // side by side
        expect(el.textContent).toContain('2,215 rows were read from transfers.csv');
        const keep = q(el, 'keep-up-to-date')!;
        expect(keep.querySelector('inspecto-locked-action')!.textContent).toContain('Keep this up to date');
        expect(keep.textContent).toContain(AUTOMATION_FALLBACK);
        await expectNoA11yViolations(el);
    });

    it('Standard in the console, with the right to author: Open in editor opens the guided editor on its Pipeline', () => {
        const { el } = create({ edition: 'standard', authMode: 'oidc', editorRoute: '/pipelines' });
        const link = q(el, 'open-in-editor') as HTMLAnchorElement;
        expect(link.getAttribute('href')).toBe('/pipelines?guided=1&open=transfers');
        expect(q(el, 'ask-administrator')).toBeNull();
    });

    it('Standard in Inspecto LA (no editor) or without the right: "Ask an administrator" (no violations)', async () => {
        const { el } = create({ edition: 'standard', authMode: 'oidc', editorRoute: '/pipelines', canAuthor: false });
        expect(q(el, 'open-in-editor')).toBeNull();
        expect(q(el, 'ask-administrator')!.textContent).toContain('Ask an administrator to keep this up to date');
        await expectNoA11yViolations(el);
    });

    it('Add a file: the chosen mode, the new summary shown and emitted', () => {
        const { fixture, el } = create();
        (el.querySelectorAll('[data-testid="add-file-mode"] input[type=radio]')[1] as HTMLInputElement).click();
        fixture.detectChanges();
        const input = el.querySelector('input[type=file]') as HTMLInputElement;
        Object.defineProperty(input, 'files', { value: [new File(['a'], 'oct.csv')], configurable: true });
        input.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        expect(el.textContent).toContain('Running the import');
        const req = http.expectOne((r) => r.url === apiUrl('/datasets/transfers/files'));
        expect(req.request.params.get('mode')).toBe('append');
        expect(req.request.params.get('name')).toBe('oct.csv');
        req.flush(summary({ file: 'oct.csv', rowsBefore: 2215, rowsAfter: 4430, rowsChanged: 2215 }));
        fixture.detectChanges();
        expect(el.textContent).toContain('2,215 rows were added: 4,430 rows now.');
        expect(fixture.componentInstance.got.length).toBe(1);
    });

    it('a Reference is always replaced (no mode choice)', () => {
        const { el } = create({ kind: 'reference' });
        expect(q(el, 'add-file-mode')).toBeNull();
        expect(el.textContent).toContain('Details about entities');
    });

    it('Run now: a 202 is followed; a refusal is said in plain words', () => {
        vi.useFakeTimers();
        try {
            const { fixture, el } = create();
            q(el, 'run-now')!.click();
            http.expectOne(apiUrl('/datasets/transfers/run')).flush(
                { runId: 'r5', dataset: 'transfers', action: 'run', state: 'running' },
                { status: 202, statusText: 'Accepted' },
            );
            fixture.detectChanges();
            expect(el.textContent).toContain('runs on the server - following it');
            vi.advanceTimersByTime(1500);
            http.expectOne(apiUrl('/onboard/runs/r5')).flush({
                runId: 'r5',
                dataset: 'transfers',
                action: 'run',
                state: 'failed',
                message: 'the file was set aside',
            });
            fixture.detectChanges();
            expect(el.querySelector('inspecto-alert')!.textContent).toContain(
                'The import did not finish: the file was set aside',
            );
            expect((q(el, 'run-now') as HTMLButtonElement).disabled).toBe(false);
        } finally {
            vi.useRealTimers();
        }
    });

    it('a 403 locks Add a file and Run now with the server reason', () => {
        const { fixture, el } = create();
        q(el, 'run-now')!.click();
        http.expectOne(apiUrl('/datasets/transfers/run')).flush(
            { error: { message: SELF_IMPORT_OFF } },
            { status: 403, statusText: 'Forbidden' },
        );
        fixture.detectChanges();
        expect(q(el, 'card-locked')!.textContent).toContain(SELF_IMPORT_OFF);
        expect((q(el, 'run-now') as HTMLButtonElement).disabled).toBe(true);
        expect(TestBed.inject(AddDataAccess).lockReason()).toBe(SELF_IMPORT_OFF);
    });
});
