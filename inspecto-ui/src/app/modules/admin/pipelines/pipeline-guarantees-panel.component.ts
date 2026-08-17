import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthoredNode, AuthoredPipeline } from 'app/inspecto/api';

/** One checklist row: a Guarantee's configured state + the node that owns its config (if any). */
interface GuaranteeRow {
    key: string;
    label: string;
    /** Human summary of the current setting, or null when the Guarantee is not configured. */
    summary: string | null;
    /** The graph node whose config dialog edits this Guarantee (absent ⇒ display-only row). */
    owner?: AuthoredNode;
}

/**
 * The Guarantees checklist (ELT amendment UI plan §2.1, S2): a FIXED panel — never draggable, never
 * cards — projecting the housekeeping family (§2.4: file dedup, gap watch, markers, quarantine,
 * backup) out of the keys the lifted graph carries: `duplicate` on the acquisition node, the `gap`
 * node, the marker node, the quarantine sink, `backup` on the primary sink. Editing opens the OWNING
 * node's existing config dialog via {@link edit} — this panel has no write path of its own, so the
 * verbatim-sections rule the node dialogs follow covers it for free. Keys the graph does not model
 * (`processing.retention_days`, a `dirs.quarantine` with no owning node) are preserved by lower's
 * ownership rules and deliberately not shown — showing a value this panel could not edit would be a
 * lie about what Edit does.
 */
@Component({
    selector: 'app-pipeline-guarantees-panel',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section class="rounded border p-3" style="background: var(--gamma-bg-card); border-color: var(--gamma-border)">
            <h2 class="mb-2 text-xs font-semibold uppercase tracking-wide opacity-60">Guarantees</h2>
            <ul class="flex flex-col gap-1.5" aria-label="Pipeline Guarantees">
                @for (row of rows(); track row.key) {
                    <li class="flex items-center gap-2 text-sm">
                        <mat-icon
                            class="icon-size-4 shrink-0"
                            [svgIcon]="
                                row.summary ? 'heroicons_outline:check-circle' : 'heroicons_outline:minus-circle'
                            "
                            [style.color]="row.summary ? 'var(--gamma-primary)' : ''"
                        ></mat-icon>
                        <span class="font-medium">{{ row.label }}</span>
                        <span class="truncate text-xs opacity-70">{{ row.summary ?? 'not configured' }}</span>
                        @if (editable && row.owner) {
                            <button
                                class="ml-auto"
                                mat-icon-button
                                (click)="edit.emit(row.owner)"
                                [matTooltip]="'Edit ' + row.label"
                                [attr.aria-label]="'Edit ' + row.label"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:pencil-square"></mat-icon>
                            </button>
                        }
                    </li>
                }
            </ul>
        </section>
    `,
})
export class PipelineGuaranteesPanelComponent {
    private readonly modelSig = signal<AuthoredPipeline | null>(null);

    @Input({ required: true }) set model(m: AuthoredPipeline | null) {
        this.modelSig.set(m);
    }
    @Input() editable = false;

    /** Open the owning node's existing config dialog — the panel's only mutation path. */
    @Output() readonly edit = new EventEmitter<AuthoredNode>();

    readonly rows = computed<GuaranteeRow[]>(() => {
        const m = this.modelSig();
        if (!m) return [];
        const acq = m.nodes.find((n) => n.type === 'acquisition');
        const gap = m.nodes.find((n) => n.type === 'gap');
        // Marker dedup rides the acquisition node since P5-a; a graph lifted before that still carries
        // its own node, so both are read (mirrors `PipelineLift.markerHome`) — the card must not go
        // dark for either shape, and its owner is whichever node actually holds the keys.
        const legacyMarker = m.nodes.find((n) => n.type === 'transform.dedup.marker');
        const marker =
            acq?.config?.['duplicate_check'] != null
                ? acq.config['duplicate_check'] === true
                    ? acq
                    : undefined
                : legacyMarker;
        const quarantine = m.nodes.find(
            (n) => n.type === 'sink.persistent' && n.config?.['dir'] != null && n.config?.['database'] == null,
        );
        const sink = m.nodes.find((n) => n.type === 'sink.persistent' && n.config?.['database'] != null);

        const dup = acq?.config?.['duplicate'] as { mode?: string } | undefined;
        const rows: GuaranteeRow[] = [
            {
                key: 'file_dedup',
                label: 'File dedup',
                summary: dup?.mode ? `mode: ${dup.mode}` : null,
                owner: acq,
            },
            {
                key: 'gap_watch',
                label: 'Gap watch',
                summary: gap ? String(gap.config?.['sequence'] ?? 'enabled') : null,
                owner: gap,
            },
            {
                key: 'markers',
                label: 'Markers',
                summary: marker
                    ? String(marker.config?.['markers_dir'] ?? marker.config?.['marker_extension'] ?? 'enabled')
                    : null,
                owner: marker,
            },
            {
                key: 'quarantine',
                label: 'Quarantine',
                summary: quarantine ? String(quarantine.config?.['dir']) : null,
                owner: quarantine,
            },
            {
                key: 'backup',
                label: 'Backup',
                // ⚠ `!= null` admitted an explicit `backup: false`, whose String() is the NON-EMPTY
                // "false" — and the template keys its green check purely off `row.summary` being
                // truthy. A pipeline that had deliberately turned backup OFF therefore rendered in
                // this checklist as a satisfied guarantee. A safety panel that reports a disabled
                // guarantee as configured is worse than one that omits it.
                summary: backupSummary(sink?.config?.['backup']),
                owner: sink,
            },
        ];
        return rows;
    });
}

/**
 * The Backup row's summary, or `null` when backup is not actually in force.
 *
 * ⚠ An explicit `false` — and the string `'false'`, which is how a TOON-authored boolean can arrive —
 * mean backup is OFF, so they must read as unconfigured. Everything else (a `true`, a directory) is a
 * real setting and shows verbatim.
 */
function backupSummary(value: unknown): string | null {
    if (value == null || value === '') return null;
    return String(value).trim().toLowerCase() === 'false' ? null : String(value);
}
