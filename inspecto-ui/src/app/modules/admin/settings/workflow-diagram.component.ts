import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { WorkflowDiagram, WorkflowDraft, workflowDiagram } from 'app/inspecto/governance/governance-model';

const W = 112;
const H = 34;
const GAP_X = 96;
const GAP_Y = 44;
const PAD = 28;

interface DrawnEdge {
    path: string;
    label: string;
    lx: number;
    ly: number;
}

/**
 * Read-only state diagram of a workflow (ASSURE-WORKFLOW-SLA-1 b). Draws what the transitions table says and
 * nothing else; the table stays the accessible, editable equivalent, so the SVG is a described image.
 */
@Component({
    selector: 'inspecto-workflow-diagram',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (diagram().nodes.length === 0) {
            <p class="text-secondary text-sm">No states yet. Add a transition to see the diagram.</p>
        } @else {
            <svg
                role="img"
                [attr.aria-label]="description()"
                [attr.viewBox]="'0 0 ' + width() + ' ' + height()"
                [attr.width]="width()"
                [attr.height]="height()"
                class="max-w-full text-current"
            >
                <defs>
                    <marker
                        id="wf-arrow"
                        viewBox="0 0 10 10"
                        refX="9"
                        refY="5"
                        markerWidth="7"
                        markerHeight="7"
                        orient="auto"
                    >
                        <path d="M0,0 L10,5 L0,10 z" fill="currentColor" />
                    </marker>
                </defs>
                @for (e of edges(); track $index) {
                    <path
                        [attr.d]="e.path"
                        fill="none"
                        stroke="currentColor"
                        stroke-width="1.5"
                        marker-end="url(#wf-arrow)"
                    />
                    <text
                        [attr.x]="e.lx"
                        [attr.y]="e.ly"
                        text-anchor="middle"
                        font-size="11"
                        fill="currentColor"
                        class="opacity-80"
                    >
                        {{ e.label }}
                    </text>
                }
                @for (n of positioned(); track n.id) {
                    <rect
                        [attr.x]="n.x"
                        [attr.y]="n.y"
                        [attr.width]="w"
                        [attr.height]="h"
                        rx="8"
                        fill="none"
                        stroke="currentColor"
                        [attr.stroke-width]="n.terminal ? 4 : 1.5"
                        [attr.stroke-dasharray]="n.unreachable ? '5 4' : null"
                    />
                    <text
                        [attr.x]="n.x + w / 2"
                        [attr.y]="n.y + h / 2 + 4"
                        text-anchor="middle"
                        font-size="12"
                        fill="currentColor"
                    >
                        {{ n.id }}{{ n.initial ? ' (start)' : '' }}
                    </text>
                }
            </svg>
            <p class="text-secondary text-xs">
                Thick border: terminal state. Dashed: unreachable from the initial state.
            </p>
        }
    `,
})
export class WorkflowDiagramComponent {
    readonly draft = input.required<WorkflowDraft>();
    readonly w = W;
    readonly h = H;

    readonly diagram = computed<WorkflowDiagram>(() => workflowDiagram(this.draft()));
    readonly positioned = computed(() =>
        this.diagram().nodes.map((n) => ({ ...n, x: PAD + n.column * (W + GAP_X), y: PAD + n.row * (H + GAP_Y) })),
    );
    readonly width = computed(
        () => PAD * 2 + this.diagram().columns * W + Math.max(0, this.diagram().columns - 1) * GAP_X,
    );
    readonly height = computed(() => {
        const rows = Math.max(1, ...this.positioned().map((n) => n.row + 1));
        return PAD * 2 + rows * H + (rows - 1) * GAP_Y;
    });

    readonly edges = computed<DrawnEdge[]>(() => {
        const at = new Map(this.positioned().map((n) => [n.id, n]));
        return this.diagram().edges.flatMap((e): DrawnEdge[] => {
            const a = at.get(e.from);
            const b = at.get(e.to);
            if (!a || !b) return [];
            if (a === b) {
                const x = a.x + W / 2;
                const y = a.y;
                return [
                    {
                        path: `M${x - 14},${y} C${x - 30},${y - 30} ${x + 30},${y - 30} ${x + 14},${y}`,
                        label: e.action,
                        lx: x,
                        ly: y - 24,
                    },
                ];
            }
            if (b.column > a.column) {
                const x1 = a.x + W;
                const y1 = a.y + H / 2;
                const y2 = b.y + H / 2;
                return [
                    { path: `M${x1},${y1} L${b.x},${y2}`, label: e.action, lx: (x1 + b.x) / 2, ly: (y1 + y2) / 2 - 5 },
                ];
            }
            // A back-edge (or same-column move): arc below both states so it never crosses the forward lines.
            const x1 = a.x + W / 2;
            const x2 = b.x + W / 2;
            const drop = Math.max(a.y, b.y) + H + 26;
            return [
                {
                    path: `M${x1},${a.y + H} C${x1},${drop} ${x2},${drop} ${x2},${b.y + H}`,
                    label: e.action,
                    lx: (x1 + x2) / 2,
                    ly: drop - 2,
                },
            ];
        });
    });

    readonly description = computed(() => {
        const d = this.diagram();
        const moves = d.edges.map((e) => `${e.from} to ${e.to} by ${e.action || 'an unnamed action'}`).join('; ');
        const ends = d.nodes.filter((n) => n.terminal).map((n) => n.id);
        return (
            `Workflow state diagram with ${d.nodes.length} states. ` +
            `Initial: ${d.nodes.find((n) => n.initial)?.id ?? 'none'}. Terminal: ${ends.join(', ') || 'none'}. ` +
            `Transitions: ${moves || 'none'}. The transitions table lists the same moves.`
        );
    });
}
