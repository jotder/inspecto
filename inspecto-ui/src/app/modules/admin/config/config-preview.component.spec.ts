import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ConfigService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ConfigPreviewComponent } from './config-preview.component';
import { parseDraft, parseRows } from './config-preview';

function create(svc: Partial<ConfigService>) {
    TestBed.configureTestingModule({
        imports: [ConfigPreviewComponent],
        providers: [
            provideNoopAnimations(),
            { provide: ConfigService, useValue: svc },
            // The grid's real theme service walks up to GAMMA_APP_CONFIG — stub it, as the data-table's own spec does.
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
        ],
    });
    const fixture = TestBed.createComponent(ConfigPreviewComponent);
    fixture.detectChanges();
    return fixture;
}

describe('config preview input parsing', () => {
    it('accepts a JSON object draft and refuses anything else', () => {
        expect(parseDraft('{"a":1}')).toEqual({ value: { a: 1 } });
        expect('error' in parseDraft('[1]')).toBe(true);
        expect('error' in parseDraft('{oops')).toBe(true);
    });
    it('accepts a non-empty array of objects and refuses anything else', () => {
        expect('error' in parseRows('[{"a":1}]')).toBe(false);
        expect('error' in parseRows('[]')).toBe(true);
        expect('error' in parseRows('[1]')).toBe(true);
        expect('error' in parseRows('{"a":1}')).toBe(true);
    });
});

describe('ConfigPreviewComponent', () => {
    it('starts on the empty state and has no a11y violations', async () => {
        const f = create({});
        expect(f.nativeElement.textContent).toContain('Nothing previewed yet');
        await expectNoA11yViolations(f.nativeElement);
    });

    it('runs the parsing preview with the draft and the sample text and shows counts', () => {
        const previewParsing = vi.fn(() =>
            of({ frontend: 'delimited', columns: ['a'], rowCount: 2, rows: [{ a: 1 }, { a: 2 }], rejectedRows: 1 }),
        );
        const f = create({ previewParsing } as unknown as Partial<ConfigService>);
        f.componentInstance.draftCtrl.setValue('{"parsing":{}}');
        f.componentInstance.sampleCtrl.setValue('a\n1\n2');
        f.componentInstance.run();
        f.detectChanges();
        expect(previewParsing).toHaveBeenCalledWith({ parsing: {} }, 'a\n1\n2');
        const text = f.nativeElement.textContent as string;
        expect(text).toContain('Rows parsed');
        expect(text).toContain('Rows rejected');
        expect(text).toContain('delimited');
    });

    it('runs the schema preview over JSON rows and shows the cast counts', () => {
        const previewSchema = vi.fn(() =>
            of({ columns: ['a'], okCount: 3, rejectedCount: 1, rejectedRows: [{ a: 'x' }] }),
        );
        const f = create({ previewSchema } as unknown as Partial<ConfigService>);
        f.componentInstance.kindCtrl.setValue('schema');
        f.componentInstance.sampleCtrl.setValue('[{"a":"x"}]');
        f.componentInstance.run();
        f.detectChanges();
        expect(previewSchema).toHaveBeenCalledWith({}, [{ a: 'x' }]);
        expect(f.nativeElement.textContent).toContain('Rows that cast');
        expect(f.nativeElement.textContent).toContain('Rejected rows');
    });

    it('flags a cut enrichment result', () => {
        const previewEnrichment = vi.fn(() => of({ columns: ['a'], rows: [{ a: 1 }], truncated: true }));
        const f = create({ previewEnrichment } as unknown as Partial<ConfigService>);
        f.componentInstance.kindCtrl.setValue('enrichment');
        f.componentInstance.sampleCtrl.setValue('[{"a":1}]');
        f.componentInstance.run();
        f.detectChanges();
        expect(f.nativeElement.textContent).toContain('the result was cut');
    });

    it('refuses bad input without calling the backend and announces why', () => {
        const previewParsing = vi.fn();
        const f = create({ previewParsing } as unknown as Partial<ConfigService>);
        f.componentInstance.draftCtrl.setValue('nope');
        f.componentInstance.run();
        f.detectChanges();
        expect(previewParsing).not.toHaveBeenCalled();
        expect(f.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('not valid JSON');
    });

    it('surfaces a 422 reason and a 403 as an explained alert, never a blank pane', async () => {
        const unprocessable = new HttpErrorResponse({
            status: 422,
            error: { error: { message: 'sample does not parse' } },
        });
        const forbidden = new HttpErrorResponse({ status: 403 });
        const previewParsing = vi.fn(() => throwError(() => unprocessable));
        const previewEnrichment = vi.fn(() => throwError(() => forbidden));
        const f = create({ previewParsing, previewEnrichment } as unknown as Partial<ConfigService>);
        f.componentInstance.sampleCtrl.setValue('x');
        f.componentInstance.run();
        f.detectChanges();
        expect(f.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('sample does not parse');
        await expectNoA11yViolations(f.nativeElement);

        f.componentInstance.kindCtrl.setValue('enrichment');
        f.componentInstance.sampleCtrl.setValue('[{"a":1}]');
        f.componentInstance.run();
        f.detectChanges();
        expect(f.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Workbench authoring');
    });
});
