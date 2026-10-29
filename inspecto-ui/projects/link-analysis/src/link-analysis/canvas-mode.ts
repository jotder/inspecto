import type { GraphViewInteraction, GraphViewPlugins } from '@inspecto/core/graph/graph-view.component';

/**
 * The Studio's Canvas modes (LA-STUDIO-UI-REORG-1 §5.2/§5.3, UIR-D2/D3). Exactly one owns the drag on the empty canvas:
 * Pan (drag pans, Shift+drag lassos), Lasso (drag lassos, for touchpads), Fisheye and Link filter (the lens owns it).
 */
export type CanvasMode = 'pan' | 'lasso' | 'fisheye' | 'linkFilter';

export const CANVAS_MODES: { id: CanvasMode; label: string; icon: string; key: string; hint: string }[] = [
    { id: 'pan', label: 'Pan', icon: 'heroicons_outline:hand-raised', key: 'Esc', hint: 'drag to pan, Shift+drag to lasso' },
    { id: 'lasso', label: 'Lasso', icon: 'heroicons_outline:cursor-arrow-rays', key: 'L', hint: 'drag to lasso' },
    {
        id: 'fisheye',
        label: 'Fisheye',
        icon: 'heroicons_outline:magnifying-glass-plus',
        key: 'F',
        hint: 'drag the lens over the graph',
    },
    {
        id: 'linkFilter',
        label: 'Link filter',
        icon: 'heroicons_outline:adjustments-horizontal',
        key: '',
        hint: 'show only links inside the lens',
    },
];

/** The shared graph host's pointer model for a mode. Shift+click toggles a node in the selection in every mode. */
export function canvasInteractionFor(mode: CanvasMode): GraphViewInteraction {
    return {
        panByDrag: mode === 'pan',
        lasso: mode === 'pan' ? 'shift' : mode === 'lasso' ? 'drag' : undefined,
        shiftClickToggle: true,
        doubleClick: true,
        fitOnDataChange: true,
        fitOnResize: true,
    };
}

/**
 * A saved view's plugin flags made consistent with one mode: the rectangle `brush-select` and the old `lasso-select`
 * toggle are gone (the view opens as Pan), and Fisheye wins over Link filter if both were saved on.
 */
export function canvasModeOptions<P extends Pick<GraphViewPlugins, 'behaviors' | 'fisheye' | 'edgeFilterLens'>>(
    plugins: P,
): P {
    const behaviors = (plugins.behaviors ?? []).filter((b) => b !== 'brush-select' && b !== 'lasso-select');
    return { ...plugins, behaviors, edgeFilterLens: !plugins.fisheye && !!plugins.edgeFilterLens };
}

/** L = Lasso, F = Fisheye, Esc = back to Pan (only when another mode is on, so Esc still clears a selection in Pan). */
export function canvasModeForKey(e: KeyboardEvent, current: CanvasMode): CanvasMode | null {
    if (e.ctrlKey || e.metaKey || e.altKey || e.repeat) return null;
    const key = e.key.toLowerCase();
    if (key === 'l') return 'lasso';
    if (key === 'f') return 'fisheye';
    if (key === 'escape' && current !== 'pan') return 'pan';
    return null;
}
