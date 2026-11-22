import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { apiErrorMessage, ComponentsService, JoinAddedColumn } from 'app/inspecto/api';

/**
 * "Columns this Step adds" (LOOKUP-1 P1d) — a read-only table under a keyed `transform.join`: each added column's
 * output name, type, the Reference column it comes from and the input column it was looked up by. Computed by the
 * server from the Reference's declared columns (no row read); a clash or a missing column shows as the server's
 * refusal, before any run. Shown only for the keyed form (`key:`, or `from:`/`to:` for a range).
 */
@Component({
    selector: 'inspecto-join-added-columns',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoAlertComponent],
    template: `
        @if (keyed()) {
            <section class="pt-3" aria-labelledby="join-added-heading">
                <h3 id="join-added-heading" class="m-0 text-sm font-semibold">Columns this Step adds</h3>
                @if (error(); as e) {
                    <inspecto-alert variant="warning" class="mt-2">{{ e }}</inspecto-alert>
                } @else if (columns(); as cols) {
                    @if (cols.length) {
                        <table class="mt-2 w-full text-sm" data-testid="join-added-columns">
                            <thead>
                                <tr class="text-secondary text-left">
                                    <th scope="col">Output column</th>
                                    <th scope="col">Type</th>
                                    <th scope="col">From</th>
                                    <th scope="col">Looked up by</th>
                                </tr>
                            </thead>
                            <tbody>
                                @for (c of cols; track c.name) {
                                    <tr>
                                        <td class="font-mono">{{ c.name }}</td>
                                        <td>{{ c.type }}</td>
                                        <td>{{ c.from ?? detailLabel(c.detail) }}</td>
                                        <td>{{ c.lookedUpBy }} ({{ c.match }})</td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                    } @else {
                        <p class="text-secondary m-0 mt-2 text-sm">This Step adds no columns.</p>
                    }
                }
            </section>
        }
    `,
})
export class JoinAddedColumnsComponent {
    /** The join's config as saved on the node. */
    readonly config = input.required<Record<string, unknown>>();
    /** The rows' columns, when known (enables the clash check against them). */
    readonly inputColumns = input<string[]>([]);

    readonly columns = signal<JoinAddedColumn[] | null>(null);
    readonly error = signal<string | null>(null);
    readonly keyed = signal(false);

    private readonly api = inject(ComponentsService);
    private readonly destroyRef = inject(DestroyRef);
    /** The answer in flight; a newer config drops the older request so it cannot overwrite the newer answer. */
    private pending?: Subscription;

    constructor() {
        effect(() => {
            const cfg = this.config();
            const keyed = ['key', 'from', 'to'].some((k) => cfg[k] != null && String(cfg[k]).trim() !== '');
            this.keyed.set(keyed);
            this.pending?.unsubscribe();
            this.columns.set(null);
            this.error.set(null);
            if (!keyed) return;
            this.pending = this.api
                .joinAddedColumns(cfg, this.inputColumns())
                .pipe(takeUntilDestroyed(this.destroyRef))
                .subscribe({
                    next: (r) => this.columns.set(r.columns),
                    error: (err) => this.error.set(apiErrorMessage(err, 'Could not list the columns this Step adds.')),
                });
        });
    }

    detailLabel(detail: string | null): string {
        return detail === 'matched_key' ? '(the key that matched)' : detail === 'match_kind' ? '(how it matched)' : '';
    }
}
