import { ChangeDetectionStrategy, Component, booleanAttribute, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
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
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
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

    /**
     * Opt in to natural-language input (AGT-6a A5.1): a prompt box whose sentence the backend turns into
     * the tool's arguments before running it deterministically.
     *
     * **Opt-in per pane, deliberately (plan D10) — not a default.** Four of the five L1 tools take
     * structured input the pane already holds, and a prompt box on those is theatre: on Expectations, for
     * instance, the dialog already knows `table` and `column`, and profiling them is deterministic SQL.
     * A box that asks for what the screen can see makes the feature look clever and be worse.
     */
    readonly prompting = input(false, { transform: booleanAttribute });

    /** Placeholder for the prompt box — panes phrase an example in their own domain. */
    readonly promptHint = input('Describe what you want');

    /** Set when the pane itself knows the action is unavailable (e.g. no column selected yet). */
    readonly disabled = input(false, { transform: booleanAttribute });

    /** Why the pane disabled it — surfaced as the button tooltip so the block is never silent. */
    readonly disabledReason = input('');

    /** The operator applied this candidate. The pane writes it through its own validated route. */
    readonly applyDraft = output<AiDraft>();

    readonly running = signal(false);
    readonly drafts = signal<AiDraft[] | null>(null);
    /** The operator's sentence, when {@link prompting} is on. */
    readonly prompt = signal('');
    /** What the sentence became — shown before Apply, so a model-derived draft is never magic. */
    readonly derivedArgs = signal<Record<string, unknown> | null>(null);
    /** Set when there is no model to interpret a sentence — distinct from {@link unavailable}. */
    readonly noModel = signal(false);
    /** Set when the intelligence module is absent (503) — the affordance stays disabled thereafter. */
    readonly unavailable = signal(false);
    readonly showUnchanged = signal(false);
    /** Which candidate is expanded; `suggest_expectations` returns several to choose between. */
    readonly selected = signal(0);

    readonly canAuthor = computed(() => this.lens.canAuthorWorkbench());

    readonly blocked = computed(
        () =>
            this.disabled() ||
            this.running() ||
            this.unavailable() ||
            !this.canAuthor() ||
            // In prompt mode there is nothing to send until the operator has written a sentence, and a
            // no-model backend can never interpret one — but the pane's other affordances stay untouched.
            (this.prompting() && (this.noModel() || this.prompt().trim().length === 0)),
    );

    readonly blockedReason = computed(() => {
        if (this.unavailable()) return 'The intelligence module is not available on this backend.';
        if (!this.canAuthor()) return 'Your current lens cannot author configuration.';
        if (this.prompting() && this.noModel())
            return 'No local model is configured on this backend, so a written request cannot be interpreted.';
        if (this.prompting() && this.prompt().trim().length === 0) return 'Describe what you want first.';
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
        this.derivedArgs.set(null);
        this.selected.set(0);
        const call = this.prompting()
            ? this.agent.deriveTool<unknown>(this.tool(), this.prompt().trim(), this.args())
            : this.agent.runTool<unknown>(this.tool(), this.args());
        call.subscribe({
            next: (result) => {
                this.running.set(false);
                // The derive route wraps the tool's own value alongside derivedArgs; the A1 route returns
                // the value bare. Unwrap so every downstream adapter and the diff stay identical.
                if (this.prompting()) {
                    const wrapped = result as { value: unknown; derivedArgs: Record<string, unknown> };
                    this.derivedArgs.set(wrapped?.derivedArgs ?? null);
                    this.drafts.set(adaptToolResult(this.tool(), wrapped?.value));
                } else {
                    this.drafts.set(adaptToolResult(this.tool(), result));
                }
            },
            error: (err: unknown) => {
                this.running.set(false);
                const status = (err as { status?: number })?.status;
                // 503 = the module is absent, OR (prompt mode) no local model is configured. Both are
                // deployment facts rather than failures of this pane, so latch and explain instead of
                // letting the operator retry into the same wall — but they are DIFFERENT walls: with no
                // model the deterministic affordance still works, so only the prompt box degrades.
                if (status === 503) {
                    if (this.prompting()) {
                        this.noModel.set(true);
                        this.toastr.info('No local model is configured, so written requests cannot be interpreted.');
                    } else {
                        this.unavailable.set(true);
                        this.toastr.info('AI assistance is not available on this backend.');
                    }
                    return;
                }
                // 422 in prompt mode = the model produced nothing usable. Retryable, and the operator's
                // own words are the thing to change, so the message is theirs and the box keeps its text.
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
        this.derivedArgs.set(null);
        this.selected.set(0);
        this.showUnchanged.set(false);
    }

    setPrompt(value: string): void {
        this.prompt.set(value);
    }

    /** The derived arguments as label/value rows, so the echo renders without a JSON dump. */
    readonly derivedRows = computed(() => {
        const derived = this.derivedArgs();
        return derived
            ? Object.entries(derived).map(([key, value]) => ({
                  key,
                  value: typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value),
              }))
            : [];
    });

    toggleUnchanged(): void {
        this.showUnchanged.update((v) => !v);
    }

    /** Findings are ordered worst-first so an ERROR is never hidden below a WARNING. */
    findingsOf(draft: AiDraft): AiDraft['findings'] {
        const rank = (s: string) => (s === 'ERROR' ? 0 : s === 'WARNING' ? 1 : 2);
        return [...draft.findings].sort((a, b) => rank(a.severity) - rank(b.severity));
    }
}
