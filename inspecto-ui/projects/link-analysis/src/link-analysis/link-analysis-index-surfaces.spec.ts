import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { GammaConfigService } from '@gamma/services/config';
import { Observable, Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    GraphAlgorithmCatalogue,
    GraphRunRequest,
    GraphRunView,
    GraphRunsService,
    LinkIndexBuildRequest,
    LinkIndexBuildView,
    LinkIndexList,
} from '@inspecto/link-analysis/api/graph-runs.service';
import { WorkingSet } from '@inspecto/link-analysis/api/inv.service';
import { EntityProjection } from '@inspecto/core/graph';
import { buildServerIdMap } from './graph-run-apply';
import { workingSetToGraph } from './investigation-state';
import { LinkAnalysisIndexBuildComponent, indexBuildErrorMessage } from './link-analysis-index-build.component';
import { LinkAnalysisToolboxComponent } from './link-analysis-toolbox.component';

/** LA-INDEX-SPA-SURFACES-1: the toolbox hosts for `egoNetwork` and seeds-only `degreeCentrality`, and the build `mode` control. */
const CATALOGUE = {
    ceilings: { maxNodes: 100000, maxEdges: 1000000, timeoutMs: 600000 },
    algorithms: [
        { id: 'neighborhood', engines: ['memory', 'index'] },
        { id: 'egoNetwork', engines: ['memory', 'index'] },
        { id: 'degreeCentrality', engines: ['memory', 'index'] },
    ],
} as unknown as GraphAlgorithmCatalogue;

const index = (over: Record<string, unknown> = {}) => ({
    dataset: 'calls',
    mapping: { sourceCol: 'a', targetCol: 'b', kindCol: 'k', timeCol: 't', attrCols: ['x'] },
    version: 3,
    stale: false,
    reason: null,
    deltas: 1,
    plan: {
        recommended: 'append',
        appendable: true,
        reasons: ['2 files were added'],
        added: 2,
        removed: 0,
        changed: 0,
        samples: { added: ['f1.parquet', 'f2.parquet'] },
    },
    ...over,
});
const LIST = { enabled: true, indexes: [index()] } as unknown as LinkIndexList;

const P: EntityProjection = {
    datasetId: 'calls',
    sourceCol: 'A',
    targetCol: 'B',
    linkKindCol: 'K',
    entityType: 'acct',
};
const WS: WorkingSet = {
    entities: ['a', 'b', 'c'].map((id) => ({
        id,
        type: null,
        hop: 0,
        seed: id,
        admittedBy: 1,
        hidden: false,
        kept: false,
    })),
    links: [],
    excluded: [],
    hash: 'h',
};
const CANVAS = workingSetToGraph(WS, P);
const IDS = buildServerIdMap(WS, P, CANVAS);
const nodeId = (label: string) => CANVAS.nodes.find((n) => n.data.label === label)!.id;

function runView(): GraphRunView {
    return {
        runId: 'r1',
        status: 'COMPLETED',
        investigationId: 'inv-1',
        algorithm: 'egoNetwork',
        engine: 'index',
        budget: { maxNodes: 5000, maxEdges: 50000, timeoutMs: 30000 },
        budgetClamped: false,
        consumed: { nodes: 1, edges: 0, elapsedMs: 1, work: 0 },
        progress: { work: 0, fraction: 1 },
        cancelRequested: false,
        cached: false,
        createdAt: 't',
        source: { kind: 'index', version: 3, stale: false, fingerprint: 'known' },
        result: { algorithm: 'egoNetwork', kind: 'GRAPH', dropped: 0, elapsedMs: 1, nodes: [], edges: [] },
    } as unknown as GraphRunView;
}

function runsMock(over: Record<string, unknown> = {}) {
    return {
        catalogue: signal(CATALOGUE),
        indexes: signal<LinkIndexList | null>(LIST),
        loadCatalogue: vi.fn(),
        loadIndexes: vi.fn(),
        reloadIndexes: vi.fn(),
        run: vi.fn((_r: GraphRunRequest): Observable<GraphRunView> => of(runView())),
        cancel: vi.fn(),
        startBuild: vi.fn(),
        watchBuild: vi.fn(),
        ...over,
    };
}

function makeToolbox(runs = runsMock()) {
    TestBed.configureTestingModule({
        imports: [LinkAnalysisToolboxComponent],
        providers: [
            provideNoopAnimations(),
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
            { provide: ComponentsService, useValue: { list: () => of([]) } },
            { provide: GraphRunsService, useValue: runs },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisToolboxComponent);
    fixture.componentRef.setInput('graph', CANVAS);
    fixture.componentRef.setInput(
        'nodeOptions',
        CANVAS.nodes.map((n) => ({ id: n.id, label: n.data.label })),
    );
    fixture.componentRef.setInput('serverIds', IDS);
    fixture.componentRef.setInput('investigationId', 'inv-1');
    return { fixture, c: fixture.componentInstance, runs, el: fixture.nativeElement as HTMLElement };
}

describe('Toolbox Explain tab - egoNetwork and seeds-only degreeCentrality on the index', () => {
    it('hosts an Ego network run on the index for the picked Node, with no Working Set button', () => {
        const { fixture, c, runs, el } = makeToolbox();
        c.tab.set('explain');
        c.explainFor.set(nodeId('a'));
        fixture.detectChanges();
        const hosts = [...el.querySelectorAll('inspecto-link-analysis-server-run')];
        expect(hosts).toHaveLength(3);
        const ego = hosts[1];
        expect(ego.querySelector('[data-testid="run-on-server"]')).toBeNull();
        (ego.querySelector('[data-testid="run-on-index"]') as HTMLButtonElement).click();
        expect(runs.run).toHaveBeenCalledWith(
            expect.objectContaining({
                algorithm: 'egoNetwork',
                input: 'index',
                dataset: 'calls',
                params: { node: IDS.serverNode(nodeId('a')) },
            }),
        );
    });

    it('states "Pick a node first" for Ego network until a Node is picked', () => {
        const { fixture, c, el } = makeToolbox();
        c.tab.set('explain');
        fixture.detectChanges();
        const ego = el.querySelectorAll('inspecto-link-analysis-server-run')[1];
        expect((ego.querySelector('[data-testid="run-on-index"]') as HTMLButtonElement).disabled).toBe(true);
        expect(ego.textContent).toContain('Pick a node first.');
    });

    it('degree centrality scores only the picked nodes: none picked states why, picked ones go as seeds', () => {
        const { fixture, c, runs, el } = makeToolbox();
        c.tab.set('explain');
        fixture.detectChanges();
        const deg = () => el.querySelectorAll('inspecto-link-analysis-server-run')[2];
        const go = () => deg().querySelector('[data-testid="run-on-index"]') as HTMLButtonElement;
        expect(go().disabled).toBe(true);
        expect(deg().textContent).toContain('Pick 1 to 20 nodes to score');
        c.scoreSeedPick.set(nodeId('a'));
        c.addScoreSeed();
        c.scoreSeedPick.set(nodeId('b'));
        c.addScoreSeed();
        c.scoreSeedPick.set(nodeId('b')); // a duplicate is not added twice
        c.addScoreSeed();
        fixture.detectChanges();
        expect(c.scoreSeeds()).toEqual([nodeId('a'), nodeId('b')]);
        expect(go().disabled).toBe(false);
        go().click();
        expect(runs.run).toHaveBeenCalledWith(
            expect.objectContaining({
                algorithm: 'degreeCentrality',
                input: 'index',
                seeds: [IDS.serverNode(nodeId('a')), IDS.serverNode(nodeId('b'))],
            }),
        );
        c.removeScoreSeed(nodeId('a'));
        expect(c.scoreSeeds()).toEqual([nodeId('b')]);
    });

    it('offers neither index run when the Space serves no index', () => {
        const { fixture, c, el } = makeToolbox(runsMock({ indexes: signal(null) }));
        c.tab.set('explain');
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="run-on-index"]')).toBeNull();
    });

    it('the Edge index tool opens and hosts the build control', () => {
        const { fixture, c, el } = makeToolbox();
        c.toggleTool('index-build');
        fixture.detectChanges();
        expect(el.querySelector('inspecto-link-analysis-index-build')).not.toBeNull();
        expect(el.querySelector('[data-testid="start-build"]')).not.toBeNull();
    });
});

function makeBuild(opts: { list?: LinkIndexList | null; allowed?: boolean; runs?: ReturnType<typeof runsMock> } = {}) {
    const runs = opts.runs ?? runsMock({ indexes: signal(opts.list === undefined ? LIST : opts.list) });
    TestBed.configureTestingModule({
        imports: [LinkAnalysisIndexBuildComponent],
        providers: [provideNoopAnimations(), { provide: GraphRunsService, useValue: runs }],
    });
    const fixture = TestBed.createComponent(LinkAnalysisIndexBuildComponent);
    if (opts.allowed === false) fixture.componentRef.setInput('allowed', false);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);
    return { fixture, runs, el, q, c: fixture.componentInstance };
}

const bv = (over: Partial<LinkIndexBuildView> = {}): LinkIndexBuildView => ({
    buildId: 'b1',
    status: 'QUEUED',
    ...over,
});

describe('LinkAnalysisIndexBuildComponent - the build mode control', () => {
    it('says so when the index list is unknown', () => {
        expect(makeBuild({ list: null }).q('no-index')).not.toBeNull();
    });

    it('says so when index.enabled is off', () => {
        const list = { enabled: false, indexes: [index()] } as unknown as LinkIndexList;
        expect(makeBuild({ list }).q('no-index')).not.toBeNull();
    });

    it('shows the version, the server advice and its reasons, and preselects the advised mode', () => {
        const { q, el } = makeBuild();
        expect(q('build-index-version')!.textContent).toContain('version 3, 1 appended');
        expect(q('build-plan-recommended')!.textContent).toBe('append');
        expect(q('build-plan')!.textContent).toContain('2 file(s) added');
        expect(el.textContent).toContain('2 files were added');
        expect(el.textContent).toContain('f1.parquet, f2.parquet');
        expect((q('mode-append') as HTMLInputElement).checked).toBe(true);
        expect(q('mode-help')!.textContent).toContain('added since the live version');
    });

    it('defaults to a full build when the advice is none', () => {
        const none = index({
            plan: { recommended: 'none', appendable: false, reasons: [], added: 0, removed: 0, changed: 0 },
        });
        const { q } = makeBuild({ list: { enabled: true, indexes: [none] } as unknown as LinkIndexList });
        expect((q('mode-full') as HTMLInputElement).checked).toBe(true);
    });

    it('sends the index own mapping and the picked mode, then follows the build to its result and refreshes the list', () => {
        const stream = new Subject<LinkIndexBuildView>();
        const runs = runsMock({
            startBuild: vi.fn((_r: LinkIndexBuildRequest) => of(bv())),
            watchBuild: vi.fn(() => stream),
        });
        const { q, fixture } = makeBuild({ runs });
        (q('mode-compact') as HTMLInputElement).click();
        fixture.detectChanges();
        q('start-build')!.click();
        fixture.detectChanges();
        expect(runs.startBuild).toHaveBeenCalledWith({
            dataset: 'calls',
            sourceCol: 'a',
            targetCol: 'b',
            kindCol: 'k',
            timeCol: 't',
            attrCols: ['x'],
            mode: 'compact',
        });
        expect(q('build-progress')!.textContent).toContain('Queued');
        expect((q('start-build') as HTMLButtonElement).disabled).toBe(true);
        stream.next(bv({ status: 'RUNNING', progress: { phase: 'sort', step: 1, steps: 3 } }));
        fixture.detectChanges();
        expect(q('build-progress')!.textContent).toContain('sort (1/3)');
        stream.next(
            bv({ status: 'COMPLETED', result: { version: 4, rows: 9, edges: 9, nodes: 5, bytes: 1, totalMs: 12 } }),
        );
        fixture.detectChanges();
        expect(q('build-done')!.textContent).toContain('Built version 4');
        expect(runs.reloadIndexes).toHaveBeenCalledTimes(1);
        expect((q('start-build') as HTMLButtonElement).disabled).toBe(false);
    });

    it('puts a refused build (409) in words and never retries it as another mode', () => {
        const err = new HttpErrorResponse({ status: 409, error: { error: 'no new file since version 3' } });
        const runs = runsMock({ startBuild: vi.fn(() => throwError(() => err)) });
        const { q, fixture } = makeBuild({ runs });
        q('start-build')!.click();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('That build cannot run now');
        expect(runs.startBuild).toHaveBeenCalledTimes(1);
    });

    it('states a FAILED build without a message from the server', () => {
        const runs = runsMock({
            startBuild: vi.fn(() => of(bv({ status: 'FAILED', failure: 'IOException' }))),
            watchBuild: vi.fn(),
        });
        const { q, fixture } = makeBuild({ runs });
        q('start-build')!.click();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('The build failed (IOException)');
    });

    it('disables the build and says why without the capability', () => {
        const { q, runs } = makeBuild({ allowed: false });
        expect((q('start-build') as HTMLButtonElement).disabled).toBe(true);
        expect(q('build-blocked')!.textContent).toContain('Build link index capability');
        q('start-build')!.click();
        expect(runs.startBuild).not.toHaveBeenCalled();
    });

    it('maps each status to its own sentence', () => {
        const e = (status: number) => indexBuildErrorMessage(new HttpErrorResponse({ status }));
        expect(e(403)).toContain('Build link index capability');
        expect(e(404)).toContain('not found');
        expect(e(422)).toContain('refused the build');
        expect(e(503)).toContain('not available');
        expect(indexBuildErrorMessage(new Error('x'))).toContain('could not be started');
    });

    it('is axe-clean', async () => {
        const { fixture } = makeBuild();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
