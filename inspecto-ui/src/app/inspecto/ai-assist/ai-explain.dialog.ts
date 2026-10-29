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
    /**
     * The pane's own definition per term, used when the glossary lookup has none for it (UI test 2026-10-29: the
     * Link Analysis About showed unrelated docs lines for four of its terms). A term with a definition here never
     * falls back to the docs search.
     */
    definitions?: Readonly<Record<string, string>>;
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
 * The docs the fallback may cite: user-facing guides and the glossary only. ⛔ Never a deployment, operations or
 * internal README — `docs_search` walks the whole docs folder the backend was started with, and a deployment's own
 * README (identity-provider and gateway set-up) answered "Link" with lines about Keycloak and WSO2 (UI test
 * 2026-10-29). Nothing under `archived-documents/` either: history, never current guidance, even when a file there is
 * named like a guide. The path is matched with either separator (a Windows backend answers back-slashed paths).
 */
const USER_FACING_DOC = /(^|\/)(GLOSSARY|USER_GUIDE|ADVANCED_GUIDE)\.md$|(^|\/)(user-guide|admin-guide)[^/]*\.md$/i;

/** True when a docs hit is from a user-facing doc and may be shown to the operator. */
export function isUserFacingDoc(file: string): boolean {
    const path = file.replace(/\\/g, '/');
    return !/(^|\/)archived-documents\//i.test(path) && USER_FACING_DOC.test(path);
}

/** A cited doc's readable name: "Glossary", "User guide", "User guide: link analysis". */
export function guideTitle(file: string): string {
    const base = file.replace(/\\/g, '/').split('/').pop()!.replace(/\.md$/i, '');
    const named: Record<string, string> = { GLOSSARY: 'Glossary', USER_GUIDE: 'User guide', ADVANCED_GUIDE: 'Advanced guide' };
    if (named[base.toUpperCase()]) return named[base.toUpperCase()];
    const m = /^(user|admin)-guide-?(.*)$/i.exec(base);
    const words = (s: string) => s.replace(/[-_]+/g, ' ').trim();
    if (m) return `${m[1][0].toUpperCase()}${m[1].slice(1).toLowerCase()} guide${m[2] ? `: ${words(m[2])}` : ''}`;
    const plain = words(base);
    return plain ? plain[0].toUpperCase() + plain.slice(1) : file;
}

/** Where an engineer's note starts in a GLOSSARY row: everything from here on is not for the analyst. */
const ENGINEER_MARKERS = ['⛔', '✅', '⚠', '🔴', '(internal document', 'Named by operator decision', 'Named by decision'];
/** Abbreviations whose full stop does not end a sentence. */
const NOT_A_SENTENCE_END = /(?:\be\.g|\bi\.e|\bvs|\betc|\bcf|\bapprox)\.$/i;
/** About this long, a definition still reads as one short paragraph. */
const DEFINITION_MAX = 220;

/**
 * Turns a raw `docs/GLOSSARY.md` row into one clean, complete paragraph an analyst can read (LA-UI-TEXT-PASS-1 X2).
 *
 * A GLOSSARY row is binding vocabulary written for engineers: it carries ticket ids (`D-E1`, `LA-10`), routes
 * (`/inv/*`), class names in backticks, ⛔ / ✅ / ⚠ notes, "(internal document - not shipped)" links, escaped table
 * pipes (`\|`) and "Named by operator decision…" tails. This keeps the first COMPLETE sentence of the plain text before
 * the first such marker and drops every code span and id.
 *
 * ⚠ The glossary lookup sends only the FIRST LINE of a row, which often stops mid-sentence ("…a database, cloud"). A
 * fragment is never presented as complete: with no sentence end in the text, this returns '' and the dialog shows the
 * term with no definition (review 2026-10-29). A complete sentence longer than about 220 characters is cut at a word
 * and ends with "…", so the cut shows. Returns '' when nothing readable is left.
 */
export function cleanGlossaryDefinition(raw: string): string {
    const s = stripGlossaryMarkup(raw);
    // The first complete sentence only.
    const ends = /[.!?](?=\s+[A-Z(“"]|\s*$)/g;
    let m: RegExpExecArray | null;
    let sentence = '';
    while ((m = ends.exec(s))) {
        if (NOT_A_SENTENCE_END.test(s.slice(0, m.index + 1))) continue;
        sentence = s.slice(0, m.index + 1).trim();
        break;
    }
    if (!/[A-Za-z]/.test(sentence)) return '';
    if (sentence.length > DEFINITION_MAX) {
        const room = sentence.slice(0, DEFINITION_MAX);
        sentence = room.slice(0, room.lastIndexOf(' ')).replace(/[\s,;:(—–-]+$/, '') + '…';
    }
    return sentence[0].toUpperCase() + sentence.slice(1);
}

/** A docs line cited as it stands, markup stripped; a line that does not end a sentence ends with "…". */
export function cleanDocsSnippet(raw: string): string {
    const s = stripGlossaryMarkup(raw).replace(/[\s,;:—–-]+$/, '');
    if (!/[A-Za-z]/.test(s)) return '';
    return /[.!?…]$/.test(s) ? s : s + '…';
}

/** Everything an engineer wrote for engineers, removed: ids, routes, code spans, marks, emphasis, escaped pipes. */
function stripGlossaryMarkup(raw: string): string {
    let s = raw.replace(/\\\|/g, '|').replace(/\r?\n/g, ' ');
    // A leading annotation the loader left in: "*(Link Analysis)* — ".
    s = s.replace(/^\s*\*?\([^)]*\)\*?\s*[—–-]*\s*/, '');
    const cut = Math.min(...ENGINEER_MARKERS.map((m) => s.indexOf(m)).filter((i) => i >= 0), s.length);
    s = s.slice(0, cut);
    s = s.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1'); // [text](target) -> text
    s = s.replace(/`[^`]*`/g, ' '); // code spans: op ids, class names, keys - never analyst words
    s = s.replace(/\bops\b/g, 'steps').replace(/\bop\b/g, 'step');
    s = s.replace(/\*\*|\*|(?<=^|\s)_|_(?=\s|[.,;:)]|$)/g, '');
    s = s.replace(/(^|[\s(])\/[\w/*{}.:-]+/g, '$1'); // routes
    // ticket ids, with the word that pointed at them ("since BI-5" goes whole, never leaving "since ,")
    s = s.replace(/(?:\b(?:since|per|under|see|in|by|from)\s+)?\b([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+)\b/g, (all, id: string) =>
        /\d/.test(id) && !/^(SHA|UTF|ISO)-/.test(id) ? ' ' : all,
    );
    s = s.replace(/[|]/g, ' ');
    for (let i = 0; i < 3; i++) {
        s = s
            .replace(/\(\s*(?:[,;:/—–-]|\band\b|\bor\b|\s)*\)/g, ' ') // a bracket left empty
            .replace(/\(\s*[,;:]\s*/g, '(')
            .replace(/\s*[,;:]\s*\)/g, ')')
            .replace(/\s+([,.;:)])/g, '$1')
            .replace(/([,;:])(?:\s*[,;:])+/g, '$1')
            .replace(/\b(or|and),\s/g, '$1 ') // "or, since BI-5, a …" lost its aside: "or a …"
            .replace(/\s{2,}/g, ' ');
    }
    return s.trim().replace(/^[—–\-:,;\s]+/, '');
}

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
            } @else if (unavailable() && !hasOwnDefinitions()) {
                <inspecto-alert variant="info" title="Definitions unavailable">
                    Definitions are unavailable here. Everything else works.
                </inspecto-alert>
            } @else {
                <p class="text-secondary mb-4 text-sm" data-testid="explain-source">{{ sourceNote() }}</p>
                <dl class="flex flex-col gap-4">
                    @for (entry of entries(); track entry.term) {
                        <div>
                            <dt class="font-semibold">{{ entry.term }}</dt>
                            @if (entry.definition) {
                                <dd class="mt-0.5 text-sm">{{ entry.definition }}</dd>
                            } @else if (entry.citations?.length) {
                                <!-- No definition, but a guide mentions it: cite, never invent. -->
                                <dd class="mt-0.5 text-sm">
                                    <span class="text-secondary">No definition. Mentioned in the guides:</span>
                                    <ul class="mt-1 flex flex-col gap-1">
                                        @for (hit of entry.citations; track hit.file + hit.line) {
                                            <li [title]="hit.file + ':' + hit.line">
                                                <span class="font-medium">{{ guideTitle(hit.file) }}</span>: {{ hit.snippet }}
                                            </li>
                                        }
                                    </ul>
                                </dd>
                            } @else {
                                <dd class="text-secondary mt-0.5 text-sm">No definition found.</dd>
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
    /** The pane supplied its own definitions: shown even when the module is absent (they need no lookup). */
    hasOwnDefinitions(): boolean {
        return Object.keys(this.data.definitions ?? {}).length > 0;
    }
    /** The line above the terms: one plain sentence, whichever source answered. */
    sourceNote(): string {
        return 'Terms used on this screen.';
    }
    /** A cited doc's readable name ("User guide: link analysis"); the path stays in the row's tooltip. */
    guideTitle(file: string): string {
        return guideTitle(file);
    }

    constructor() {
        // One request per term, in parallel, each independently degrading: a term that cannot be
        // resolved must not blank the terms that could be.
        forkJoin(this.data.terms.map((term) => this.explain(term))).subscribe((entries) => {
            this.loading.set(false);
            this.entries.set(entries);
        });
    }

    private explain(term: string): Observable<ExplainEntry> {
        // The pane's own hand-written definition wins (LA-UI-TEXT-PASS-1 X2): a GLOSSARY row is written for
        // engineers and carries ticket ids, routes and warnings no analyst should read.
        const own = this.data.definitions?.[term];
        if (own) return of({ term, definition: own });
        return this.agent.runTool<GlossaryResult>('glossary_lookup', { term }).pipe(
            map((result) => {
                const definition = cleanGlossaryDefinition(result.definition ?? '');
                return definition ? { term, definition } : { term };
            }),
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
                citations: (result.hits ?? [])
                    .filter((h) => isUserFacingDoc(h.file))
                    .map((h) => ({ ...h, snippet: cleanDocsSnippet(h.snippet ?? '') }))
                    .filter((h) => h.snippet)
                    .slice(0, CITATION_LIMIT),
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
