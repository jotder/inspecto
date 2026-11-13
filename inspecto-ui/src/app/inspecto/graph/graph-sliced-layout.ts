// LA-PERF-N4-1: the iterative layouts (Force, Force (spread)) run in time slices instead of one long task.
// G6 5.1.1 runs an iterative layout as `execute(); stop(); tick(300)` in ONE synchronous call: 4.1 s on the default
// Link Analysis graph (767 nodes / 4,000 links), during which the tab cannot paint or take input. This wrapper runs the
// SAME @antv/layout class with the SAME options and the same number of ticks, but yields to the event loop between
// slices of about SLICE_MS, so the tab keeps responding while the layout runs. The positions are the same as G6's own.
import { BaseLayout, D3ForceLayout, ExtensionCategory, FruchtermanLayout, register } from '@antv/g6';
import type { GraphData } from '@antv/g6';

/** The longest stretch one slice of ticks may hold the main thread. */
export const SLICE_MS = 40;
/** G6's own tick count for an iterative layout (it strips any `iterations` option before the layout sees it). */
const DEFAULT_ITERATIONS = 300;

interface IterativeLayout {
    execute(model: unknown, options: Record<string, unknown>): Promise<void>;
    stop(): unknown;
    tick(n: number): unknown;
    forEachNode(cb: (n: { id: string | number; x: number; y: number }) => void): void;
}

type IterativeCtor = new (options: Record<string, unknown>) => IterativeLayout;

/** Thrown by a sliced layout that a newer layout, render or the graph's destroy made stale: callers ignore it. */
export class LayoutSuperseded extends Error {
    constructor() {
        super('The layout was superseded before it finished.');
        this.name = 'LayoutSuperseded';
    }
}

/**
 * Run `step` until `total` steps ran, yielding to the event loop whenever a slice used up its time. `stale` is asked
 * after every yield; when it answers true the run stops with {@link LayoutSuperseded}.
 */
export async function runSliced(
    total: number,
    step: () => void,
    sliceMs = SLICE_MS,
    yieldFn = defaultYield,
    stale: () => boolean = () => false,
): Promise<void> {
    let done = 0;
    while (done < total) {
        if (stale()) throw new LayoutSuperseded();
        const start = performance.now();
        while (done < total && performance.now() - start < sliceMs) {
            step();
            done++;
        }
        if (done < total) await yieldFn();
    }
}

function defaultYield(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 0));
}

function slicedLayout(Inner: IterativeCtor, id: string): unknown {
    return class SlicedLayout extends BaseLayout {
        // The id G6 switches on to give `d3-force` its centre point: kept the same, so the inner layout gets it too.
        id = id;

        async execute(model: GraphData): Promise<GraphData> {
            // G6 cannot stop a layout it does not know as iterative, so this one stops itself once it is stale: the
            // graph was destroyed, another layout was set (setLayout replaces `options.layout`), or a newer run began.
            const ctx = this.context as unknown as GraphContext;
            const run = ++runSeq;
            latestRun.set(ctx, run);
            const layoutAtStart = ctx.options?.layout;
            const stale = (): boolean =>
                latestRun.get(ctx) !== run || !!ctx.graph?.destroyed || ctx.options?.layout !== layoutAtStart;
            const options = this.options as Record<string, unknown>;
            const inner = new Inner({});
            const nodes = (model.nodes ?? []).map((n) => {
                const style = (n.style ?? {}) as { x?: unknown; y?: unknown };
                return {
                    id: n.id,
                    ...(typeof style.x === 'number' ? { x: style.x } : {}),
                    ...(typeof style.y === 'number' ? { y: style.y } : {}),
                };
            });
            const edges = (model.edges ?? []).map((e) => ({ id: e.id, source: e.source, target: e.target }));
            // As G6 drives it: the synchronous part of execute() builds the simulation; stop() halts its own timer.
            void inner.execute({ nodes, edges }, { ...options, node: (d: unknown) => d, edge: (d: unknown) => d });
            inner.stop();
            try {
                // G6 strips `iterations` before a layout sees its options and runs 300 ticks: the same here.
                await runSliced(DEFAULT_ITERATIONS, () => inner.tick(1), SLICE_MS, defaultYield, stale);
            } finally {
                inner.stop();
            }
            if (stale()) throw new LayoutSuperseded();
            const out: { id: string; style: { x: number; y: number; z: number } }[] = [];
            // A node the data dropped while the layout ran is left out: G6 throws on a position for an unknown node.
            const has = (id: string): boolean => ctx.model?.hasNode?.(id) ?? true;
            inner.forEachNode((n) => {
                if (has(String(n.id))) out.push({ id: String(n.id), style: { x: n.x, y: n.y, z: 0 } });
            });
            return { nodes: out, edges: [] } as GraphData;
        }
    };
}

/** The parts of G6's runtime context a sliced layout reads. */
interface GraphContext {
    graph?: { destroyed?: boolean };
    options?: { layout?: unknown };
    model?: { hasNode?(id: string): boolean };
}

let runSeq = 0;
const latestRun = new WeakMap<object, number>();

let registered = false;

/** Register `sliced-d3-force` and `sliced-fruchterman` with G6 once. */
export function registerSlicedLayouts(): void {
    if (registered) return;
    registered = true;
    register(
        ExtensionCategory.LAYOUT,
        'sliced-d3-force',
        slicedLayout(D3ForceLayout as unknown as IterativeCtor, 'd3-force') as never,
    );
    register(
        ExtensionCategory.LAYOUT,
        'sliced-fruchterman',
        slicedLayout(FruchtermanLayout as unknown as IterativeCtor, 'fruchterman') as never,
    );
}
