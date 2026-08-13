import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { AgentService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';

/** What the pane must say to open the dialog: the screen's name and the terms it is about. */
export interface AiExplainData {
    /** The screen being explained — used in the dialog title, so it reads as "about this screen". */
    screen: string;
    /** The canonical terms this screen is built on, in the order the operator should meet them. */
    terms: string[];
}

/** One docs/ line the fallback search matched, kept with its citation so the operator can go read it. */
export interface ExplainCitation {
    file: string;
    line: number;
    snippet: string;
}

/** One term's explanation — a canonical definition, or docs citations, or an honest "not found". */
export interface ExplainEntry {
    term: string;
    /** The canonical `docs/GLOSSARY.md` definition, when the term has one. */
    definition?: string;
    /** Set when there is no canonical definition and `docs_search` found the term instead. */
    citations?: ExplainCitation[];
}

interface GlossaryResult {
    term: string;
    definition: string;
}

interface DocsSearchResult {
    hits: ExplainCitation[];
}

const CITATION_LIMIT = 3;

/**
 * "What am I looking at" — the read-only half of the inline AI surface (AGT-6a A4).
 *
 * The pane declares the canonical terms its screen is built on and this dialog resolves each one
 * through `glossary_lookup`, falling back to `docs_search` for a term the glossary does not define.
 * Both tools are non-mutating reads, so `POST /agent/tools/{name}` serves them with **no new backend
 * capability** — and there is deliberately **no model in the loop**: the operator reads
 * `docs/GLOSSARY.md`, the binding vocabulary, not a paraphrase of it.
 *
 * Three ways this differs from {@link AiAssistComponent}, which is why it is a sibling rather than a
 * mode of it:
 * - **No write path at all** — no draft, no diff, no Apply. Nothing here can be applied to anything.
 * - **Not gated on authoring.** A Business-lens user is exactly who needs the vocabulary explained, so
 *   this must never sit behind `canAuthorWorkbench()`.
 * - **The terms are declared by the pane**, never typed by the operator — a free-text box would make
 *   this a docs search engine, and would re-state what the screen already knows (A3).
 *
 * Degrades, never hard-fails: a 503 (intelligence module absent) renders an explanatory alert, and a
 * term that resolves to nothing renders as "no canonical definition" rather than vanishing.
 */
@Component({
    standalone: true,
    imports: [MatButtonModule, MatDialogModule, MatIconModule, MatProgressSpinnerModule, InspectoAlertComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>About {{ data.screen }}</h2>
        <mat-dialog-content class="w-[34rem] max-w-full">
            @if (loading()) {
                <div class="flex items-center gap-3 py-4">
                    <mat-progress-spinner diameter="20" mode="indeterminate" />
                    <span class="text-secondary text-sm">Looking up this screen's vocabulary…</span>
                </div>
            } @else if (unavailable()) {
                <inspecto-alert variant="info" title="AI assistance unavailable">
                    The intelligence module is not installed on this backend, so definitions cannot be looked up.
                    Everything else on this screen works as usual.
                </inspecto-alert>
            } @else {
                <p class="text-secondary mb-4 text-sm">
                    The canonical terms this screen is built on, from the project glossary.
                </p>
                <dl class="flex flex-col gap-4">
                    @for (entry of entries(); track entry.term) {
                        <div>
                            <dt class="font-semibold">{{ entry.term }}</dt>
                            @if (entry.definition) {
                                <dd class="mt-0.5 text-sm">{{ entry.definition }}</dd>
                            } @else if (entry.citations?.length) {
                                <!-- No canonical definition, but the docs mention it — cite, never invent. -->
                                <dd class="mt-0.5 text-sm">
                                    <span class="text-secondary">
                                        No canonical definition; found in the documentation:
                                    </span>
                                    <ul class="mt-1 flex flex-col gap-1">
                                        @for (hit of entry.citations; track hit.file + hit.line) {
                                            <li>
                                                <code class="text-xs">{{ hit.file }}:{{ hit.line }}</code>
                                                — {{ hit.snippet }}
                                            </li>
                                        }
                                    </ul>
                                </dd>
                            } @else {
                                <dd class="text-secondary mt-0.5 text-sm">No canonical definition found.</dd>
                            }
                        </div>
                    }
                </dl>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-flat-button color="primary" type="button" mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class AiExplainDialog {
    private agent = inject(AgentService);
    readonly data = inject<AiExplainData>(MAT_DIALOG_DATA);

    readonly loading = signal(true);
    readonly entries = signal<ExplainEntry[]>([]);
    /** Latched when the intelligence module is absent (503) — the whole dialog then explains itself. */
    readonly unavailable = signal(false);

    constructor() {
        // One request per term, in parallel, each independently degrading: a term that cannot be
        // resolved must not blank the terms that could be.
        forkJoin(this.data.terms.map((term) => this.explain(term))).subscribe((entries) => {
            this.loading.set(false);
            this.entries.set(entries);
        });
    }

    private explain(term: string): Observable<ExplainEntry> {
        return this.agent.runTool<GlossaryResult>('glossary_lookup', { term }).pipe(
            map((result) => ({ term, definition: result.definition })),
            // 422 = no canonical definition for this term. That is an answerable question, not a
            // failure, so fall back to the docs corpus rather than showing the operator nothing.
            catchError((err: unknown) => this.searchDocs(term, err)),
        );
    }

    private searchDocs(term: string, cause: unknown): Observable<ExplainEntry> {
        if (this.isUnavailable(cause)) return of({ term });
        return this.agent.runTool<DocsSearchResult>('docs_search', { query: term }).pipe(
            map((result) => ({
                term,
                citations: (result.hits ?? []).slice(0, CITATION_LIMIT),
            })),
            catchError((err: unknown) => {
                this.isUnavailable(err);
                return of({ term });
            }),
        );
    }

    /** A 503 is the module being absent — latch it so the dialog explains that once, not per term. */
    private isUnavailable(err: unknown): boolean {
        if ((err as { status?: number })?.status === 503) {
            this.unavailable.set(true);
            return true;
        }
        return false;
    }
}
