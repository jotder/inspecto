import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ComponentsService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import {
    ColumnMeta,
    ConditionGroup,
    QueryConditionGroupComponent,
    SqlAstNode,
    SqlAstTableComponent,
    astToConditionGroup,
    compileWhere,
    hasSqlComment,
    sameSqlStructure,
} from 'app/inspecto/query';

/** How long the predicate must be still before it is re-read — the describe route's cadence. */
const DEBOUNCE_MS = 300;

/** The degrade state of the read (design §5): tier 1 is `parseError`; an unreachable route is not a tier. */
type ReadState =
    | { kind: 'empty' }
    | { kind: 'loading' }
    | { kind: 'ok'; ast: SqlAstNode }
    | { kind: 'parseError'; message: string; position: number | null }
    | { kind: 'unavailable' };

/**
 * "What gets kept" — the structured view of a Filter Step's row predicate (`transform.filter.where`),
 * AUTHORING-REDESIGN-1 (c), design Steps 2-4 under the operator's 2026-09-23 decisions.
 *
 * <ul>
 *   <li>**Step 2 — read.** The stored text goes to `POST /components/sql/ast` (debounced) and DuckDB's tree
 *       renders as the read-only {@link SqlAstTableComponent}. The free-text `where` field above stays the
 *       ONLY way the text itself changes; a parse failure is a warning, and never blocks saving.</li>
 *   <li>**Step 3 — recognise.** {@link astToConditionGroup} says whether the predicate fits the Query Core;
 *       the reason when it does not is shown, never guessed around.</li>
 *   <li>**Step 4 — edit, via the SHIPPED editor.** Offered only when recognition succeeds, the text carries
 *       no comment, AND the Query Core's own `compileWhere` re-parses to the SAME tree (the admission
 *       test). The edited SQL comes from `compileWhere`, shown as an exact before/after, and is written
 *       into the `where` field only on the author's accept.</li>
 * </ul>
 *
 * ⛔ **The tree is never written back (Q2).** No path here turns a tree into SQL; the plan stops at step 4
 * for good. So an untouched predicate keeps its author's text byte for byte, and a commented one is never
 * offered for structured editing, because the tree has already dropped the comment.
 */
@Component({
    selector: 'app-pipeline-filter-predicate',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent, QueryConditionGroupComponent, SqlAstTableComponent],
    template: `
        <section class="mt-3" aria-labelledby="filter-predicate-heading">
            <h3 id="filter-predicate-heading" class="m-0 mb-1 text-xs font-semibold uppercase opacity-70">
                What gets kept
            </h3>
            @switch (read().kind) {
                @case ('empty') {
                    <p class="text-secondary m-0 text-sm">No row predicate — every row is kept.</p>
                }
                @case ('loading') {
                    <p class="text-secondary m-0 text-sm" role="status">Reading the condition…</p>
                }
                @case ('unavailable') {
                    <p class="text-secondary m-0 text-sm" role="status">
                        The condition's structure could not be read right now. The text above is unaffected.
                    </p>
                }
                @case ('parseError') {
                    <inspecto-alert variant="warning" title="This condition does not parse">
                        {{ parseMessage() }}. It can still be saved — the text above stays editable.
                    </inspecto-alert>
                }
                @case ('ok') {
                    @if (editing(); as ed) {
                        <inspecto-query-condition-group
                            [group]="ed.group"
                            [columns]="ed.columns"
                            [root]="true"
                            (changed)="pending.set(null); noChange.set(false)"
                        />
                        @if (pending(); as p) {
                            <div class="mt-2 text-sm">
                                <div class="text-secondary text-xs">Before</div>
                                <pre class="m-0 whitespace-pre-wrap font-mono">{{ p.before }}</pre>
                                <div class="text-secondary mt-1 text-xs">After</div>
                                <pre class="m-0 whitespace-pre-wrap font-mono">{{
                                    p.after || '(no condition — every row is kept)'
                                }}</pre>
                                <p class="text-secondary m-0 mt-1 text-xs">
                                    The text is replaced exactly as shown; nothing else changes.
                                </p>
                            </div>
                            <div class="mt-2 flex gap-2">
                                <button mat-flat-button color="primary" type="button" (click)="accept()">
                                    Use this condition
                                </button>
                                <button mat-button type="button" (click)="pending.set(null)">Keep editing</button>
                            </div>
                        } @else {
                            @if (noChange()) {
                                <p class="text-secondary m-0 mt-2 text-sm" role="status">No change yet.</p>
                            }
                            <div class="mt-2 flex gap-2">
                                <button mat-stroked-button type="button" (click)="review()">Review change</button>
                                <button mat-button type="button" (click)="stopEditing()">Cancel</button>
                            </div>
                        }
                    } @else {
                        <inspecto-sql-ast-table [ast]="okAst()!" />
                        @if (!readOnly()) {
                            @if (editBlocked(); as why) {
                                <p class="text-secondary m-0 mt-2 text-xs">{{ why }} Edit the text above instead.</p>
                            } @else {
                                <button
                                    class="mt-2"
                                    mat-stroked-button
                                    type="button"
                                    [disabled]="checking()"
                                    (click)="startEditing()"
                                >
                                    Edit as conditions
                                </button>
                            }
                            @if (editRefusal(); as why) {
                                <p class="text-warn m-0 mt-1 text-xs" role="alert">{{ why }}</p>
                            }
                        }
                    }
                }
            }
        </section>
    `,
})
export class PipelineFilterPredicateComponent {
    private components = inject(ComponentsService);

    /** The live `where` text — the host re-feeds it on every edit of the free-text field. */
    readonly where = input('');
    readonly readOnly = input(false);
    /** Column names the upstream sample offers, so the editor can add a condition on a column not yet used. */
    readonly extraColumns = input<string[]>([]);
    /** The new predicate text, emitted ONLY on the author's explicit accept of the shown before/after. */
    readonly rewrite = output<string>();

    readonly read = signal<ReadState>({ kind: 'empty' });
    readonly okAst = computed(() => {
        const r = this.read();
        return r.kind === 'ok' ? r.ast : null;
    });
    readonly parseMessage = computed(() => {
        const r = this.read();
        if (r.kind !== 'parseError') return '';
        return r.position != null ? `${r.message} (at character ${r.position + 1})` : r.message;
    });

    /** Step 3's recognition, or null while there is no tree. */
    readonly recognition = computed(() => {
        const ast = this.okAst();
        return ast ? astToConditionGroup(ast) : null;
    });
    /** Why editing is not offered at all, or null when it may be offered. */
    readonly editBlocked = computed<string | null>(() => {
        if (hasSqlComment(this.where())) {
            return 'This condition carries a comment, which a structured edit would delete.';
        }
        const r = this.recognition();
        return r && 'unsupported' in r ? r.unsupported : null;
    });

    readonly checking = signal(false);
    readonly editRefusal = signal<string | null>(null);
    readonly editing = signal<{ group: ConditionGroup; columns: ColumnMeta[] } | null>(null);
    readonly pending = signal<{ before: string; after: string } | null>(null);
    readonly noChange = signal(false);

    private timer: ReturnType<typeof setTimeout> | null = null;
    /** Only the answer to the LATEST read may land — an older, slower one would describe stale text. */
    private seq = 0;

    constructor() {
        effect(() => {
            const text = this.where().trim();
            this.editing.set(null);
            this.pending.set(null);
            this.editRefusal.set(null);
            if (this.timer) clearTimeout(this.timer);
            const mine = ++this.seq;
            if (!text) {
                this.read.set({ kind: 'empty' });
                return;
            }
            this.read.set({ kind: 'loading' });
            this.timer = setTimeout(() => {
                this.components.sqlAst(text, 'predicate').subscribe({
                    next: (res) => {
                        if (mine !== this.seq) return;
                        if (res.ok === true) this.read.set({ kind: 'ok', ast: res.ast });
                        else if (res.ok === false) {
                            this.read.set({
                                kind: 'parseError',
                                message: res.error.message,
                                position: res.error.position,
                            });
                        }
                    },
                    // Offline, a 404 from an older control plane, a 503: none of it says anything about the
                    // predicate, so it is a quiet line — never a toast, never a block on saving.
                    error: () => {
                        if (mine === this.seq) this.read.set({ kind: 'unavailable' });
                    },
                });
            }, DEBOUNCE_MS);
        });
        inject(DestroyRef).onDestroy(() => {
            if (this.timer) clearTimeout(this.timer);
        });
    }

    /**
     * The admission test: `compileWhere` over the recognised group must re-parse to the SAME tree as the
     * stored text. Only then is the editor offered — so what it shows IS the stored predicate.
     */
    startEditing(): void {
        const r = this.recognition();
        const ast = this.okAst();
        if (!r || 'unsupported' in r || !ast) return;
        const compiled = compileWhere(r.group, r.columns);
        const refuse = () =>
            this.editRefusal.set(
                'Editing here would change what this condition means, so it is offered as text only. Edit the text above instead.',
            );
        if (!compiled) {
            refuse();
            return;
        }
        this.checking.set(true);
        this.editRefusal.set(null);
        const mine = this.seq;
        this.components.sqlAst(compiled, 'predicate').subscribe({
            next: (res) => {
                this.checking.set(false);
                if (mine !== this.seq) return;
                if (!res.ok || !sameSqlStructure(res.ast, ast)) {
                    refuse();
                    return;
                }
                this.noChange.set(false);
                // Deep-cloned: the condition-group editor mutates its bound group IN PLACE.
                this.editing.set({ group: structuredClone(r.group), columns: this.editorColumns(r.columns) });
            },
            error: () => {
                this.checking.set(false);
                this.editRefusal.set('The condition could not be checked right now. Edit the text above instead.');
            },
        });
    }

    /** The recognised (typed) columns first, then any upstream column not yet used, as text. */
    private editorColumns(recognised: ColumnMeta[]): ColumnMeta[] {
        const seen = new Set(recognised.map((c) => c.name));
        return [
            ...recognised,
            ...this.extraColumns()
                .filter((n) => !seen.has(n))
                .map((name) => ({ name, type: 'string' as const })),
        ];
    }

    review(): void {
        const ed = this.editing();
        if (!ed) return;
        const before = this.where().trim();
        const after = compileWhere(ed.group, ed.columns);
        const unchanged = after === compileWhere(this.recognisedGroup(), ed.columns);
        this.noChange.set(unchanged);
        this.pending.set(unchanged ? null : { before, after });
    }

    accept(): void {
        const p = this.pending();
        if (!p) return;
        this.rewrite.emit(p.after);
        this.stopEditing();
    }

    stopEditing(): void {
        this.editing.set(null);
        this.pending.set(null);
        this.noChange.set(false);
    }

    private recognisedGroup(): ConditionGroup {
        const r = this.recognition();
        return r && !('unsupported' in r) ? r.group : { kind: 'group', op: 'AND', items: [] };
    }
}
