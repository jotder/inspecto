import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import {
    ComponentsService,
    ConfigService,
    IconMapService,
    LensService,
    PipelineNodeType,
    PipelinesService,
} from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BundleTransferService } from 'app/inspecto/transfer';
import { PipelineEditorComponent } from './pipeline-editor.component';
import { PipelinesComponent } from './pipelines.component';

const TYPES: PipelineNodeType[] = [
    { type: 'acquisition', category: 'SOURCE', label: 'Acquisition', description: 'collect', accepts: [], emits: [], emitsNamedRoutes: false, lowerable: true },
    { type: 'sink.view', category: 'SINK', label: 'Sink (view)', description: 'logical', accepts: [], emits: [], emitsNamedRoutes: false, lowerable: false },
];

/**
 * The host is now a thin wrapper around the editor, so these render it for real. Safe in jsdom
 * precisely because nothing is open on arrival: no tab ⇒ no G6 canvas ⇒ the empty state renders.
 */
function build() {
    const stub = {
        list: () => of([{ name: 'cdr_etl', active: true, nodeCount: 1, edgeCount: 0, produces: [], consumes: [] }]),
        nodeTypes: () => of(TYPES),
        provenanceBatches: () => of([]),
    } as unknown as PipelinesService;
    TestBed.configureTestingModule({
        imports: [PipelinesComponent],
        providers: [
            provideNoopAnimations(),
            { provide: PipelinesService, useValue: stub },
            { provide: ConfigService, useValue: { write: vi.fn(), registerPipeline: vi.fn(), remove: vi.fn() } },
            { provide: ComponentsService, useValue: { list: () => of([]) } },
            { provide: IconMapService, useValue: { get: () => of({}) } },
            { provide: ToastrService, useValue: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } },
            { provide: MatDialog, useValue: { open: vi.fn() } },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(true) } },
            { provide: BundleTransferService, useValue: { loadAll: () => of([]), buildExport: () => ({ bundle: { items: [] }, missing: [] }), download: vi.fn(), write: () => of({}) } },
        ],
    });
    return TestBed.createComponent(PipelinesComponent);
}

describe('PipelinesComponent', () => {
    it('defaults to View, and View is the editor shell with authoring withheld', () => {
        const fixture = build();
        fixture.detectChanges();
        const c = fixture.componentInstance;
        expect(c.mode()).toBe('combined');

        const editor = fixture.debugElement.children[0].query(
            (n) => n.componentInstance instanceof PipelineEditorComponent,
        )!.componentInstance as PipelineEditorComponent;
        // Same component, same shell — the ONLY difference is that it cannot mutate or save.
        expect(editor.readOnly).toBe(true);
        expect(editor.canAuthor()).toBe(false);
    });

    it('switching to Edit hands the same editor authoring rights', () => {
        const fixture = build();
        fixture.detectChanges();
        fixture.componentInstance.setMode('editor');
        fixture.detectChanges();

        const editor = fixture.debugElement.children[0].query(
            (n) => n.componentInstance instanceof PipelineEditorComponent,
        )!.componentInstance as PipelineEditorComponent;
        expect(editor.readOnly).toBe(false);
        expect(editor.canAuthor()).toBe(true);
    });

    it('opens nothing on arrival and offers an accessible way to open something', async () => {
        const fixture = build();
        fixture.detectChanges();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('No pipeline open');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
