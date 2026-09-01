import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ConfigService, SpacesService } from 'app/inspecto/api';
import { StreamTransferService } from './stream-transfer.service';

/**
 * The by-name export seam (2026-09-01) — `exportPipeline` reads the SERVER-held config itself, so
 * callers that only have a NAME (the Open dialog's per-row export, Duplicate) build the exact same
 * bundle the editor's menu item does. The kind comes off the config's own `produces` — ⛔ not
 * `PipelineSummary.produces`, which is the list of stores the pipeline produces.
 */
describe('StreamTransferService.exportPipeline', () => {
    function make(pipelineConfig: Record<string, unknown>) {
        const read = vi.fn((type: string, name: string) =>
            type === 'pipeline' && name === 'demo'
                ? of({ config: pipelineConfig, path: 'demo_pipeline.toon' })
                : // satellites (schema / enrichment) are absent — buildExport tolerates that
                  throwError(() => ({ status: 404 })),
        );
        TestBed.configureTestingModule({
            providers: [
                StreamTransferService,
                { provide: ConfigService, useValue: { read } },
                { provide: SpacesService, useValue: { currentSpaceId: () => 'demo-space' } },
            ],
        });
        return { service: TestBed.inject(StreamTransferService), read };
    }

    it('derives the reference kind from the config’s own `produces`', async () => {
        const { service, read } = make({ name: 'demo', produces: 'reference' });
        const { bundle } = await new Promise<{ bundle: { kind: string; source: { name: string; space: string | null } } }>(
            (resolve) => service.exportPipeline('demo').subscribe(resolve),
        );

        expect(read).toHaveBeenCalledWith('pipeline', 'demo');
        expect(bundle.kind).toBe('reference');
        expect(bundle.source).toEqual(expect.objectContaining({ name: 'demo', space: 'demo-space' }));
    });

    it('defaults to a stream when `produces` says nothing', async () => {
        const { service } = make({ name: 'demo' });
        const { bundle } = await new Promise<{ bundle: { kind: string } }>((resolve) =>
            service.exportPipeline('demo').subscribe(resolve),
        );

        expect(bundle.kind).toBe('stream');
    });
});
