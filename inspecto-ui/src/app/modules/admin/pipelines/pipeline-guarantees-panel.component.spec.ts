import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { AuthoredNode, AuthoredPipeline } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineGuaranteesPanelComponent } from './pipeline-guarantees-panel.component';

function create(inputs: Partial<PipelineGuaranteesPanelComponent>) {
    TestBed.configureTestingModule({
        imports: [PipelineGuaranteesPanelComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(PipelineGuaranteesPanelComponent);
    Object.assign(fixture.componentInstance, inputs);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

const MODEL: AuthoredPipeline = {
    name: 'orders',
    active: false,
    nodes: [
        { id: 'acq', type: 'acquisition', config: { duplicate: { mode: 'checksum' } } },
        { id: 'gap', type: 'gap', config: { sequence: 'CDR_{yyyyMMddHH}' } },
        { id: 'parse', type: 'parser' },
        { id: 'sink', type: 'sink.persistent', config: { database: '/db', backup: '/backup' } },
    ],
    edges: [
        { from: 'acq', rel: 'gap', to: 'gap' },
        { from: 'acq', rel: 'data', to: 'parse' },
        { from: 'parse', rel: 'data', to: 'sink' },
    ],
};

describe('PipelineGuaranteesPanelComponent', () => {
    it('projects the configured Guarantees out of the graph keys, and flags the unconfigured', async () => {
        const { fixture } = create({ model: MODEL });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('File dedup');
        expect(text).toContain('mode: checksum');
        expect(text).toContain('Gap watch');
        expect(text).toContain('CDR_{yyyyMMddHH}');
        expect(text).toContain('Backup');
        expect(text).toContain('/backup');
        // markers/quarantine have no owning node in this graph — honest "not configured", never invented
        expect(text).toContain('not configured');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('editable rows emit the OWNING node — the panel itself has no write path', () => {
        const { fixture, c } = create({ model: MODEL, editable: true });
        const edited: AuthoredNode[] = [];
        c.edit.subscribe((n) => edited.push(n));
        const btn = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
            (b) => b.getAttribute('aria-label') === 'Edit File dedup',
        );
        btn!.click();
        expect(edited.map((n) => n.id)).toEqual(['acq']);
    });

    /**
     * P5-a moved marker dedup onto the acquisition node, so the card reads it from there — and its
     * Edit row must now hand back `acq`, since that is the node holding the keys.
     * (⚠ one `create()` per test: TestBed cannot be reconfigured once instantiated.)
     */
    it('reads the Markers guarantee from the acquisition node, and edits it there', () => {
        const withAcq: AuthoredPipeline = {
            ...MODEL,
            nodes: MODEL.nodes.map((n) =>
                n.id === 'acq' ? { ...n, config: { ...n.config, duplicate_check: true, markers_dir: '/markers' } } : n,
            ),
        };
        const { fixture, c } = create({ model: withAcq, editable: true });
        expect((fixture.nativeElement as HTMLElement).textContent ?? '').toContain('/markers');
        const owners: AuthoredNode[] = [];
        c.edit.subscribe((n) => owners.push(n));
        Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
            .find((b) => b.getAttribute('aria-label') === 'Edit Markers')!
            .click();
        expect(owners.map((n) => n.id)).toEqual(['acq']);
    });

    /**
     * …and a graph lifted BEFORE P5-a still carries a standalone `transform.dedup.marker` node. The
     * card going dark for that shape would tell the operator a configured Guarantee is off.
     */
    it('still reads the Markers guarantee from a legacy marker node', () => {
        const legacy: AuthoredPipeline = {
            ...MODEL,
            nodes: [
                ...MODEL.nodes,
                { id: 'dedup_marker', type: 'transform.dedup.marker', config: { markers_dir: '/old' } },
            ],
        };
        const { fixture } = create({ model: legacy });
        expect((fixture.nativeElement as HTMLElement).textContent ?? '').toContain('/old');
    });

    it('shows no Edit buttons when not editable, and renders empty rows for a null model', () => {
        const { fixture } = create({ model: MODEL, editable: false });
        expect((fixture.nativeElement as HTMLElement).querySelectorAll('button')).toHaveLength(0);

        fixture.componentRef.setInput('model', null);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelectorAll('li')).toHaveLength(0);
    });
});
