import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { AgentService, LensService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { AiDraft, AiToolName, adaptToolResult, configDiff } from './ai-draft';

/**
 * The one shared inline AI authoring surface (AGT-6a A1) — adopted by panes, never forked.
 *
 * A pane says which non-mutating agent tool to run and supplies its own context as the tool's args
 * (A3 — so the operator never re-states what the screen already knows). The surface runs the tool,
 * normalizes whatever it returned into {@link AiDraft} candidates, and renders each with its anchored
 * findings and a diff against the pane's current config. `(applyDraft)` fires only on an explicit
 * click, and the PANE performs the write through its own existing validated route — the human is the
 * actor, so the audit trail names them (decision D2).
 *
 * Three invariants it exists to preserve:
 * - **Draft-only.** Nothing is persisted here. `POST /agent/tools/{name}` refuses mutating tools with
 *   403, so this surface cannot become a second, ungated way to act.
 * - **Degrade, never hard-fail.** No pane may break because the intelligence module is absent: a 503
 *   disables the affordance and explains itself, and the pane behaves exactly as it did before.
 * - **Gated.** Authoring is behind `LensService.canAuthorWorkbench()`, the UI twin of the tools'
 *   `AUTHOR_PIPELINE` capability.
 *
 * @example
 * <inspecto-ai-assist tool="suggest_expectations"
 *                     [args]="{ table: table(), column: selectedColumn() }"
 *                     [current]="null"
 *                     label="Suggest expectations"
 *                     (applyDraft)="createExpectation($event.config)" />
 */
@Component({
    selector: 'inspecto-ai-assist',
    standalone: true,
    imports: [
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        StatusBadgeComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './ai-assist.component.html',
})
export class AiAssistComponent {
    private agent = inject(AgentService);
    private toastr = inject(ToastrService);
    readonly lens = inject(LensService);

    // Signal inputs, not decorator @Inputs — deliberately. `blocked`/`diff` are computed()s that read
    // them, and a computed only invalidates on a SIGNAL dependency: with plain @Input fields a pane
    // flipping [disabled] true→false would never re-enable the button.
    /** Which non-mutating tool to invoke. */
    readonly tool = input.required<AiToolName>();

    /** The pane's context, passed straight through as the tool's arguments (A3). */
    readonly args = input<Record<string, unknown>>({});

    /** The config the draft is compared against — null for a create (everything shows as added). */
    readonly current = input<Record<string, unknown> | null>(null);

    /** Button label; panes phrase it in their own vocabulary ("Suggest expectations", "Draft SQL"). */
    readonly label = input('Suggest with AI');

    /** Set when the pane itself knows the action is unavailable (e.g. no column selected yet). */
    readonly disabled = input(false, { transform: booleanAttribute });

    /** Why the pane disabled it — surfaced as the button tooltip so the block is never silent. */
    readonly disabledReason = input('');

    /** The operator applied this candidate. The pane writes it through its own validated route. */
    readonly applyDraft = output<AiDraft>();

    readonly running = signal(false);
    readonly drafts = signal<AiDraft[] | null>(null);
    /** Set when the intelligence module is absent (503) — the affordance stays disabled thereafter. */
    readonly unavailable = signal(false);
    readonly showUnchanged = signal(false);
    /** Which candidate is expanded; `suggest_expectations` returns several to choose between. */
    readonly selected = signal(0);

    readonly canAuthor = computed(() => this.lens.canAuthorWorkbench());

    readonly blocked = computed(
        () => this.disabled() || this.running() || this.unavailable() || !this.canAuthor(),
    );

    readonly blockedReason = computed(() => {
        if (this.unavailable()) return 'The intelligence module is not available on this backend.';
        if (!this.canAuthor()) return 'Your current lens cannot author configuration.';
        return this.disabledReason();
    });

    readonly activeDraft = computed(() => this.drafts()?.[this.selected()] ?? null);

    /** Diff rows for the selected candidate, unchanged fields folded away by default. */
    readonly diff = computed(() => {
        const draft = this.activeDraft();
        if (!draft) return [];
        const rows = configDiff(this.current(), draft.config);
        return this.showUnchanged() ? rows : rows.filter((r) => r.change !== 'same');
    });

    readonly unchangedCount = computed(() => {
        const draft = this.activeDraft();
        return draft ? configDiff(this.current(), draft.config).filter((r) => r.change === 'same').length : 0;
    });

    run(): void {
        if (this.blocked()) return;
        this.running.set(true);
        this.drafts.set(null);
        this.selected.set(0);
        this.agent.runTool<unknown>(this.tool(), this.args()).subscribe({
            next: (result) => {
                this.running.set(false);
                this.drafts.set(adaptToolResult(this.tool(), result));
            },
            error: (err: unknown) => {
                this.running.set(false);
                // 503 = the module is absent. That is not a failure of this pane, so latch it and
                // degrade the affordance rather than letting the operator retry into the same wall.
                const status = (err as { status?: number })?.status;
                if (status === 503) {
                    this.unavailable.set(true);
                    this.toastr.info('AI assistance is not available on this backend.');
                    return;
                }
                this.toastr.error(apiErrorMessage(err, 'The suggestion could not be produced.'));
            },
        });
    }

    select(index: number): void {
        this.selected.set(index);
    }

    apply(draft: AiDraft): void {
        this.applyDraft.emit(draft);
        this.dismiss();
    }

    dismiss(): void {
        this.drafts.set(null);
        this.selected.set(0);
        this.showUnchanged.set(false);
    }

    toggleUnchanged(): void {
        this.showUnchanged.update((v) => !v);
    }

    /** Findings are ordered worst-first so an ERROR is never hidden below a WARNING. */
    findingsOf(draft: AiDraft): AiDraft['findings'] {
        const rank = (s: string) => (s === 'ERROR' ? 0 : s === 'WARNING' ? 1 : 2);
        return [...draft.findings].sort((a, b) => rank(a.severity) - rank(b.severity));
    }
}
