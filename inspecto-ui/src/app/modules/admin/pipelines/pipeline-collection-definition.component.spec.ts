import { Component, ChangeDetectionStrategy } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { AuthoredNode, ConnectionsService, LensService } from 'app/inspecto/api';
import { CollectorConfigComponent } from 'app/inspecto/collector/collector-config.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineCollectionDefinitionComponent } from './pipeline-collection-definition.component';

const TOASTR = {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
};

/**
 * Host so the required signal `node` input binds naturally and outputs are captured — these specs
 * RE-PIN the acquisition cases of `node-config.dialog.spec.ts` on the definition drawer's pane
 * (definition-surface P1): the same behaviours, the same shared collector surface, the popup gone.
 */
@Component({
    standalone: true,
    imports: [PipelineCollectionDefinitionComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <app-pipeline-collection-definition [node]="node" (applied)="applied = $event" (dirtyChange)="dirty = $event" />
    `,
})
class HostComponent {
    node: AuthoredNode = { id: 'acq', type: 'acquisition' };
    applied?: AuthoredNode;
    dirty = false;
}

async function create(node: AuthoredNode) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: MatDialog,
                useValue: { open: () => ({ afterClosed: () => of(undefined) }) },
            },
            // The shared collector component loads these — `connector` included, since it DERIVES
            // `collector.connector` from the picked profile rather than asking for it.
            {
                provide: ConnectionsService,
                useValue: {
                    list: () =>
                        of([
                            { id: 'prod_sftp', connector: 'sftp' },
                            { id: 'lake_blob', connector: 'azure' },
                        ]),
                    test: vi.fn(() => of({ reachable: true, detail: 'ok' })),
                },
            },
            { provide: LensService, useValue: { canAuthorWorkbench: () => true } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.node = node;
    fixture.detectChanges();
    return fixture;
}

function pane(fixture: ComponentFixture<HostComponent>): PipelineCollectionDefinitionComponent {
    return fixture.debugElement.query(By.directive(PipelineCollectionDefinitionComponent)).componentInstance;
}

function collector(fixture: ComponentFixture<HostComponent>): CollectorConfigComponent {
    return fixture.debugElement.query(By.directive(CollectorConfigComponent)).componentInstance;
}

/** The schema form INSIDE the shared collector surface — the control set these tests mean. */
function form(fixture: ComponentFixture<HostComponent>): InspectoSchemaFormComponent {
    return fixture.debugElement.query(By.directive(InspectoSchemaFormComponent)).componentInstance;
}

function pickConnectionMode(fixture: ComponentFixture<HostComponent>): void {
    collector(fixture).setMode('connection');
    fixture.detectChanges();
}

describe('PipelineCollectionDefinitionComponent', () => {
    beforeEach(() => Object.values(TOASTR).forEach((f) => f.mockClear()));

    /** The unification's structural guarantee, re-pinned: the drawer renders the SAME shared surface. */
    it('renders the shared collector surface', async () => {
        const fixture = await create({ id: 'acq', type: 'acquisition' });
        expect(fixture.debugElement.query(By.directive(CollectorConfigComponent))).not.toBeNull();
    });

    /**
     * P5-b: marker dedup is homed on this node but is NOT a `collector:` key, so it renders as its
     * own group rather than inside the shared collector surface — which authors that block and only
     * that block. This also closes H2: the create scaffold injects `duplicate_check` silently, and
     * this group is the first place an operator can see or change what it wrote.
     */
    it('renders the dedup Guarantees as their own group, outside the collector surface', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            config: { duplicate_check: true, marker_extension: '.processed' },
        });
        const p = pane(fixture);
        const dedupKeys = p.dedupSpecs().map((s) => s.key);
        expect(dedupKeys).toEqual(['duplicate_check', 'marker_extension', 'retention_days', 'markers_dir']);
        // the collector surface is handed the block's keys only — no marker key reaches it
        const collectorKeys = p.collectorSpecs().map((s) => s.key);
        for (const k of dedupKeys) expect(collectorKeys).not.toContain(k);
        expect(collectorKeys).toContain('include'); // …and it still gets the whole block
        // the group is rendered, not merely computed
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Duplicate handling');
    });

    it('carries both surfaces into the applied node, and the group seeds from the node', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            config: { include: ['*.csv'], duplicate_check: true, marker_extension: '.processed' },
        });
        const forms = fixture.debugElement.queryAll(By.directive(InspectoSchemaFormComponent));
        expect(forms.length).toBe(2); // [0] = inside the collector surface, [1] = the dedup group
        const dedupForm = forms[1].componentInstance as InspectoSchemaFormComponent;
        expect(dedupForm.form.getRawValue()['marker_extension']).toBe('.processed');

        dedupForm.form.patchValue({ retention_days: 45 });
        pane(fixture).submit();

        const config = fixture.componentInstance.applied!.config as Record<string, unknown>;
        expect(config['duplicate_check']).toBe(true);
        expect(config['retention_days']).toBe(45);
        expect(config['include']).toEqual(['*.csv']); // the collector surface's values survive the merge
    });

    it('applies an acquisition Connection onto use:, never into config', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            config: { include: ['*.csv'] },
        });
        pickConnectionMode(fixture);
        form(fixture).form.patchValue({ connection: 'prod_sftp' });
        pane(fixture).submit();
        const applied = fixture.componentInstance.applied!;
        expect(applied.use).toBe('connection/prod_sftp');
        expect((applied.config as Record<string, unknown>)['connection']).toBeUndefined();
        expect((applied.config as Record<string, unknown>)['connector']).toBe('sftp');
    });

    it('writes connector "local" when collecting from the inbox', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            config: { include: ['*.csv'] },
        });
        expect(collector(fixture).mode()).toBe('local');
        pane(fixture).submit();
        const applied = fixture.componentInstance.applied!;
        expect((applied.config as Record<string, unknown>)['connector']).toBe('local');
        expect(applied.use).toBeUndefined();
    });

    it('refuses to apply a node naming an unsaved Connection', async () => {
        const fixture = await create({ id: 'acq', type: 'acquisition' });
        pickConnectionMode(fixture);
        form(fixture).form.patchValue({ connection: 'ghost' });
        pane(fixture).submit();
        expect(fixture.componentInstance.applied).toBeUndefined();
        expect(collector(fixture).error()).toContain('"ghost" is not a saved Connection');
    });

    it('seeds the Connection attribute from an existing use: binding', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            use: 'connection/lake_blob',
        });
        expect(pane(fixture).split().schemaInitial['connection']).toBe('lake_blob');
        expect(collector(fixture).mode()).toBe('connection');
    });

    it('clears the binding when switched back to the local inbox', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            use: 'connection/lake_blob',
        });
        collector(fixture).setMode('local');
        fixture.detectChanges();
        pane(fixture).submit();
        const applied = fixture.componentInstance.applied!;
        expect(applied.use).toBeUndefined();
        expect((applied.config as Record<string, unknown>)['connector']).toBe('local');
    });

    it('refuses a Connection-mode apply with the Connection blanked', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            use: 'connection/lake_blob',
        });
        form(fixture).form.patchValue({ connection: '' });
        pane(fixture).submit();
        expect(fixture.componentInstance.applied).toBeUndefined();
        expect(collector(fixture).error()).toContain('Pick a Connection');
    });

    /** D4 re-pin: nested blocks seed the schema form, unmodeled sub-keys survive, `__` never leaks. */
    it('nests and preserves the duplicate block through an apply', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            config: { duplicate: { mode: 'checksum', algorithm: 'SHA256' } },
        });
        expect(pane(fixture).split().schemaInitial['duplicate__mode']).toBe('checksum');
        pane(fixture).submit();
        const cfg = fixture.componentInstance.applied!.config as Record<string, unknown>;
        expect(cfg['duplicate']).toEqual({ mode: 'checksum', algorithm: 'SHA256' });
        expect(Object.keys(cfg).filter((k) => k.includes('__'))).toEqual([]);
    });

    /** Unknown roots become literal free-form rows — never stringified into the schema form. */
    it('splits unknown roots into free-form rows and keeps them through an apply', async () => {
        const fixture = await create({
            id: 'acq',
            type: 'acquisition',
            config: { stability: { window: '30s' }, mystery: '42' },
        });
        const p = pane(fixture);
        expect(p.split().schemaInitial['stability__window']).toBe('30s');
        expect(p.configRows.value).toEqual([{ key: 'mystery', value: '42' }]);
        p.submit();
        const cfg = fixture.componentInstance.applied!.config as Record<string, unknown>;
        expect(cfg['mystery']).toBe('42');
        expect(cfg['stability']).toMatchObject({ window: '30s' });
    });

    it('reports dirty transitions and returns pristine after a successful apply', async () => {
        const fixture = await create({ id: 'acq', type: 'acquisition' });
        expect(fixture.componentInstance.dirty).toBe(false);
        // A mode switch alone counts as dirty (the collector surface's own contract).
        collector(fixture).setMode('connection');
        fixture.detectChanges();
        pane(fixture).onInteraction();
        expect(fixture.componentInstance.dirty).toBe(true);
        form(fixture).form.patchValue({ connection: 'prod_sftp' });
        pane(fixture).submit();
        expect(fixture.componentInstance.dirty).toBe(false);
    });

    it('has no a11y violations', async () => {
        const fixture = await create({ id: 'acq', type: 'acquisition' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
