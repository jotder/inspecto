import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import type { ConnectPair } from '@inspecto/link-analysis/api/inv.service';
import type { DegreePresets } from '@inspecto/link-analysis/graph/domain-profile';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { MAX_CONNECT_HOPS, MAX_CONNECT_SEEDS, parseSeedList } from './seed-list';

/**
 * LA-SEED-CONNECT-1 — paste several numbers, then *Connect seeds*: ONE sealed `connect` step that seeds them and adds the
 * shortest connecting route for EVERY pair within {@link MAX_CONNECT_HOPS} hops (a pair with none is shown as not connected
 * within the limit, never searched deeper). The result grid is the step's pair table (aliases when the Space masks entity ids);
 * *Expand from these nodes* then expands one hop from every node on the connecting routes, as one `expand` step. The grid
 * shows the latest `connect` of this session; the step itself is in the op log and the Dossier for good.
 */
@Component({
    selector: 'inspecto-la-connect-op',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, InspectoAlertComponent],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2"
            style="border-color: var(--gamma-border)"
            aria-labelledby="la-connect-op-title"
        >
            <h3 id="la-connect-op-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Connect several numbers
            </h3>
            <form class="flex flex-col gap-2" (ngSubmit)="connect()">
                <label class="flex flex-col gap-1 text-xs">
                    Numbers to connect (paste a list: one per line, or separated by commas)
                    <textarea
                        name="seeds"
                        rows="4"
                        class="rounded border px-2 py-1"
                        style="border-color: var(--gamma-border)"
                        [ngModel]="text()"
                        (ngModelChange)="text.set($event)"
                    ></textarea>
                </label>
                @if (list().ids.length || list().invalid.length) {
                    <p class="text-secondary m-0 text-xs" data-testid="seed-summary">
                        {{ list().ids.length }} number{{ list().ids.length === 1 ? '' : 's' }} to connect (up to
                        {{ maxSeeds }}, within {{ maxHops }} hops).
                        @if (list().duplicates) {
                            {{ list().duplicates }} repeated.
                        }
                    </p>
                }
                @if (list().invalid.length) {
                    <inspecto-alert variant="warning" title="Not numbers, left out">{{
                        list().invalid.join(', ')
                    }}</inspecto-alert>
                }
                @if (list().overCap.length) {
                    <inspecto-alert variant="warning" title="Over the limit of {{ maxSeeds }}, left out">{{
                        list().overCap.join(', ')
                    }}</inspecto-alert>
                }
                <button
                    mat-stroked-button
                    type="submit"
                    class="self-start"
                    [disabled]="store.busy() || list().ids.length < 2"
                >
                    Connect seeds
                </button>
            </form>
            @if (error()) {
                <inspecto-alert variant="error" title="Connect refused">{{ error() }}</inspecto-alert>
            }
            @if (result(); as r) {
                <p class="m-0 text-xs" data-testid="connect-summary">
                    {{ r.joined }} of {{ r.pairs.length }} pairs connected within {{ maxHops }} hops;
                    {{ r.pairs.length - r.joined }} not connected.
                </p>
                @if (r.truncated) {
                    <inspecto-alert variant="warning" title="A search hit its budget">
                        A connecting route may be missing from this table.
                    </inspecto-alert>
                }
                @if (r.noLinks.length) {
                    <inspecto-alert variant="info" title="No links found for">{{ r.noLinks.join(', ') }}</inspecto-alert>
                }
                <div class="overflow-x-auto">
                    <table class="w-full text-left text-xs" aria-label="Connection of every pair of seeds">
                        <thead>
                            <tr>
                                <th scope="col">A</th>
                                <th scope="col">B</th>
                                <th scope="col">Connection</th>
                                <th scope="col">Example route</th>
                            </tr>
                        </thead>
                        <tbody>
                            @for (p of r.pairs; track p.a + '|' + p.b) {
                                <tr>
                                    <td>{{ p.a }}</td>
                                    <td>{{ p.b }}</td>
                                    <td>{{ verdict(p) }}</td>
                                    <td>{{ p.route ? p.route.join(' → ') : '' }}</td>
                                </tr>
                            }
                        </tbody>
                    </table>
                </div>
                <button
                    mat-stroked-button
                    type="button"
                    class="self-start"
                    [disabled]="store.busy() || !r.pathNodes.length"
                    (click)="expandFromRoutes(r.pathNodes)"
                >
                    Expand from these nodes ({{ r.pathNodes.length }})
                </button>
            }
        </section>
    `,
})
export class InvestigationConnectOpComponent {
    readonly store = inject(InvestigationSessionStore);
    readonly maxSeeds = MAX_CONNECT_SEEDS;
    readonly maxHops = MAX_CONNECT_HOPS;
    /** The profile's presets (window, budget, fan-out cap): an expand without them is unbounded and held for four-eyes. */
    readonly presets = input<DegreePresets>({});
    readonly text = signal('');
    readonly list = computed(() => parseSeedList(this.text()));
    readonly error = signal('');

    /** The latest `connect` step of this session: its pair table, and every node on a connected pair's route. */
    readonly result = computed(() => {
        const step = this.store.lastStep();
        const pairs = step?.op === 'connect' ? step.read?.pairs : undefined;
        if (!step || !pairs) return null;
        const nodes = new Set<string>();
        for (const p of pairs) for (const n of p.route ?? []) nodes.add(n);
        return {
            pairs,
            joined: pairs.filter((p) => p.connected).length,
            pathNodes: [...nodes],
            truncated: !!step.truncated,
            noLinks: step.read?.noLinks ?? [],
        };
    });

    verdict(p: ConnectPair): string {
        if (!p.connected) {
            const held = p.notWalked?.length ? ` (stopped at high-connectivity ${p.notWalked.join(', ')})` : '';
            return `Not connected within ${this.maxHops} hops${held}`;
        }
        return `${p.hops} hop${p.hops === 1 ? '' : 's'} · ${p.routes} route${p.routes === 1 ? '' : 's'}`;
    }

    async connect(): Promise<void> {
        this.error.set('');
        const ids = this.list().ids;
        if (ids.length < 2) return;
        if (!(await this.store.apply({ op: 'connect', ids, maxHops: this.maxHops }))) this.error.set(this.store.error());
    }

    /** One `expand` step from the nodes on the connecting routes (they are all in the Working Set). */
    async expandFromRoutes(ids: string[]): Promise<void> {
        this.error.set('');
        if (!(await this.store.apply({ op: 'expand', ids, ...this.store.withPresets(this.presets(), {}) }))) this.error.set(this.store.error());
    }
}
