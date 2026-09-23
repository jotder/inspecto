import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EntityProjection } from 'app/inspecto/graph';
import { InvestigationLogEntry } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { idsInWorkingSet, moveStep, rawIdsOf } from './investigation-state';

/**
 * **Link Analysis — Investigation panel** (LA-10, SPA half). The right dock's Investigation tab: start an
 * Investigation over the current Entity/Link mapping, act on canvas entities with the five shipped ops, read
 * the ordered op log, undo, replay with a drift check, and re-order the steps into a FORK (D-E4). All state
 * lives in {@link InvestigationSessionStore}; this component is the view over it.
 */
@Component({
    selector: 'inspecto-link-analysis-investigation',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
    ],
    host: { class: 'block' },
    templateUrl: './link-analysis-investigation.component.html',
})
export class LinkAnalysisInvestigationComponent {
    readonly store = inject(InvestigationSessionStore);

    /** The last run's single Entity/Link mapping, or null when the query cannot bind an Investigation. */
    readonly projection = input<EntityProjection | null>(null);
    /** Why `projection` is null, in the analyst's words. */
    readonly projectionIssue = input('');

    readonly title = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] });
    readonly reason = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(200)],
    });
    readonly reread = signal(false);
    /** Non-null while the analyst is re-ordering: the proposed order of effective step numbers. */
    readonly order = signal<number[] | null>(null);

    readonly pickerOptions = computed<PickerOption[]>(() =>
        this.store.refs().map((r) => ({
            value: r.id,
            label: r.title ? `${r.title} (${r.id})` : r.id,
            hint: r.parentId ? `Fork of ${r.parentId}` : undefined,
        })),
    );

    /** The selected entity's raw ids that are in the Working Set — what expand/keep/hide/exclude may name. */
    readonly selectedInSet = computed(() => {
        const n = this.store.selected();
        return n ? idsInWorkingSet(n, this.store.workingSet()) : [];
    });

    readonly counts = computed(() => {
        const ws = this.store.workingSet();
        return {
            entities: ws?.entities.length ?? 0,
            links: ws?.links.length ?? 0,
            excluded: ws?.excluded.length ?? 0,
            hidden: ws?.entities.filter((e) => e.hidden).length ?? 0,
            kept: ws?.entities.filter((e) => e.kept).length ?? 0,
        };
    });

    readonly parentRemembered = computed(() => {
        const p = this.store.header()?.parent;
        return !!p && this.store.refs().some((r) => r.id === p.id);
    });

    readonly stepLabel = (step: number): string => {
        const e = this.store.log()?.entries.find((x) => x.step === step);
        return e ? e.text : `Step ${step}`;
    };

    async start(): Promise<void> {
        const p = this.projection();
        if (!p || this.title.invalid) return;
        if (await this.store.start(p, this.title.value.trim())) this.title.reset('');
    }

    switchTo(id: string | null): void {
        if (id && id !== this.store.activeId()) {
            this.order.set(null);
            this.store.open(id);
        }
    }

    seed(): void {
        const n = this.store.selected();
        if (!n) return;
        const entityType = this.store.activeRef()?.entityType;
        this.store.apply({ op: 'seed', ids: rawIdsOf(n), ...(entityType ? { entityType } : {}) });
    }

    expand(all = false): void {
        this.store.apply(all ? { op: 'expand' } : { op: 'expand', ids: this.selectedInSet() });
    }

    mark(op: 'hide' | 'keep'): void {
        this.store.apply({ op, ids: this.selectedInSet() });
    }

    async exclude(): Promise<void> {
        if (this.reason.invalid) {
            this.reason.markAsTouched();
            return;
        }
        const ok = await this.store.apply({
            op: 'exclude',
            ids: this.selectedInSet(),
            reason: this.reason.value.trim(),
        });
        if (ok) this.reason.reset('');
    }

    beginReorder(): void {
        this.order.set(this.store.effectiveSteps().map((e) => e.step));
    }

    move(index: number, delta: number): void {
        this.order.update((o) => (o ? moveStep(o, index, delta) : o));
    }

    /** Only a CHANGED order forks — the same order would copy the Investigation for nothing. */
    readonly orderChanged = computed(() => {
        const o = this.order();
        const now = this.store.effectiveSteps().map((e) => e.step);
        return !!o && o.some((s, i) => s !== now[i]);
    });

    async createFork(): Promise<void> {
        const o = this.order();
        if (!o || !this.orderChanged()) return;
        if (await this.store.fork(o)) this.order.set(null);
    }

    openParent(): void {
        const p = this.store.header()?.parent;
        if (p) this.switchTo(p.id);
    }

    opLabel(e: InvestigationLogEntry): string {
        return e.kind === 'undo' ? 'undo' : (e.op ?? '');
    }

    time(at: string): string {
        const d = new Date(at);
        return isNaN(d.getTime()) ? at : d.toLocaleString();
    }
}
