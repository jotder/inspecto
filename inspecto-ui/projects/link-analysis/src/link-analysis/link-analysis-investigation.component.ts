import { ChangeDetectionStrategy, Component, computed, inject, input, signal, viewChild } from '@angular/core';
import {
    FormControl,
    FormGroup,
    FormGroupDirective,
    FormsModule,
    ReactiveFormsModule,
    Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EntityProjection } from '@inspecto/core/graph';
import { firstValueFrom } from 'rxjs';
import {
    InstantiateTemplateResult,
    InvService,
    InvestigationCoverage,
    InvestigationListItem,
    InvestigationLogEntry,
    WorkingSetRelationName,
} from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage } from '@inspecto/core/api';
import { WORKING_SET_PLUGIN } from '@inspecto/core/viz/plugins/view.plugins';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { timeZoneOptions } from '@inspecto/core/schema/time-zones';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { stepReadSuffix } from './index-source';
import { expandSourceNote, idsInWorkingSet, moveStep, rawIdsOf } from './investigation-state';
import { RELATION_NOUN, pinBinding } from './working-set-widget';
import { LA_WIDGETS } from '@inspecto/link-analysis/la-host';
import { InvestigationExpandRungComponent } from './investigation-expand-rung.component';
import {
    InvestigationSnapshotOpComponent,
    InvestigationThresholdOpComponent,
} from './investigation-band-ops.component';
import { InvestigationCompareOpComponent } from './investigation-compare-op.component';
import { InvestigationWindowOpComponent } from './investigation-window-op.component';
import { LinkAnalysisDossierComponent } from './link-analysis-dossier.component';
import { LinkAnalysisEntityListsComponent } from './link-analysis-entity-lists.component';
import { LinkAnalysisIdentitiesComponent } from './link-analysis-identities.component';
import { LinkAnalysisInvestigationCaseComponent } from './link-analysis-investigation-case.component';
import { LinkAnalysisOversightComponent } from './link-analysis-oversight.component';
import { LinkAnalysisTemplateMeasuresComponent } from './link-analysis-template-measures.component';
import { LinkAnalysisValueMeasuresComponent } from './link-analysis-value-measures.component';
import { LinkAnalysisWorkingSetRowsComponent } from './link-analysis-working-set-rows.component';

/**
 * **Link Analysis — Investigation panel** (LA-10, SPA half). The right dock's Investigation tab: start an
 * Investigation over the current Entity/Link mapping, act on canvas entities with the five shipped ops, read
 * the ordered op log, undo, replay with a drift check, and re-order the steps into a FORK (D-E4). All state
 * lives in {@link InvestigationSessionStore}; this component is the view over it. The LA-17 Entity Lists section
 * ({@link LinkAnalysisEntityListsComponent}) renders in both states — `excludeBy`/`seedBy` only while one is open.
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
        MatRadioModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        InvestigationExpandRungComponent,
        InvestigationWindowOpComponent,
        InvestigationThresholdOpComponent,
        InvestigationSnapshotOpComponent,
        InvestigationCompareOpComponent,
        LinkAnalysisDossierComponent,
        LinkAnalysisEntityListsComponent,
        LinkAnalysisIdentitiesComponent,
        LinkAnalysisInvestigationCaseComponent,
        LinkAnalysisOversightComponent,
        LinkAnalysisTemplateMeasuresComponent,
        LinkAnalysisValueMeasuresComponent,
        LinkAnalysisWorkingSetRowsComponent,
    ],
    host: { class: 'block' },
    templateUrl: './link-analysis-investigation.component.html',
})
export class LinkAnalysisInvestigationComponent {
    readonly store = inject(InvestigationSessionStore);
    private inv = inject(InvService);
    private widgets = inject(LA_WIDGETS);
    /** The shell has a Widget library to pin into (la-app has none: the section is not offered). */
    protected readonly canPin = this.widgets.available !== false;

    /** The last run's single Entity/Link mapping, or null when the query cannot bind an Investigation. */
    readonly projection = input<EntityProjection | null>(null);
    /** Why `projection` is null, in the analyst's words. */
    readonly projectionIssue = input('');
    /** The canvas's time column (the time slider's), the default event time of a new Investigation. */
    readonly timeCol = input('');
    /** LA-13: the Investigation's event-time column — windows and the coverage read need one (else 422). */
    readonly timeColumn = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] });
    /** The IANA zone a naive TIMESTAMP time column is in; blank = UTC (the server records that explicitly). */
    readonly timeColumnZone = new FormControl('', { nonNullable: true });
    readonly timeZones: PickerOption[] = timeZoneOptions('UTC (default)');

    readonly title = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] });
    /** D-U5: the stated purpose / legal basis — required by the server, recorded and shown in the Dossier, not enforced. */
    readonly purpose = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/\S/), Validators.maxLength(1000)],
    });
    readonly reason = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(200)],
    });
    /** LA-19: `confidence` is deliberately NOT asked — its scale is undecided (D-U9) and the server refuses it. */
    readonly note = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(2000)],
    });
    /** D-U9: a note on one LINK — its own field, because the entity note is only shown while an entity is selected. */
    readonly linkNote = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(2000)],
    });
    /** The `linkId` of the link picked for a note. */
    readonly linkPick = signal<string | null>(null);
    /** `GET /inv/investigations`: what the caller may read (own + shared through an open Case); null until asked. */
    readonly listed = signal<InvestigationListItem[] | null>(null);
    readonly listedTruncated = signal(false);
    readonly listBusy = signal(false);
    readonly listError = signal('');
    /** Open an Investigation by its id (there is no get-one route, so a bad id surfaces as the open's own error). */
    readonly openId = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] });
    readonly reread = signal(false);
    /** LA-19: the last coverage read and the Investigation it is FOR — rendered only while that one is open. */
    readonly coverage = signal<InvestigationCoverage | null>(null);
    readonly coverageFor = signal<string | null>(null);
    readonly coverageBusy = signal(false);
    readonly coverageError = signal('');
    /** LA-21: pin the Working Set to a dashboard Widget — FROZEN by default (D-E6); Live is opt-in. */
    readonly pinForm = new FormGroup({
        name: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)],
        }),
        relation: new FormControl<WorkingSetRelationName>('entities', { nonNullable: true }),
        mode: new FormControl<'frozen' | 'live'>('frozen', { nonNullable: true }),
    });
    readonly relationOptions: PickerOption[] = (['entities', 'links', 'excluded'] as const).map((r) => ({
        value: r,
        label: RELATION_NOUN[r].title,
    }));
    /** The pin form's directive — reset through it, or its `submitted` flag keeps the cleared name in error. */
    private readonly pinFormDirective = viewChild(FormGroupDirective);
    readonly pinBusy = signal(false);
    /** The name of the Widget just saved, for the confirmation line. */
    readonly pinnedWidget = signal('');
    readonly pinError = signal('');
    /** Non-null while the analyst is re-ordering: the proposed order of effective step numbers. */
    readonly order = signal<number[] | null>(null);

    readonly pickerOptions = computed<PickerOption[]>(() =>
        this.store.refs().map((r) => ({
            value: r.id,
            label: r.title ? `${r.title} (${r.id})` : r.id,
            hint: r.parentId ? `Fork of ${r.parentId}` : undefined,
        })),
    );

    /** D-U9: the Working Set's links that carry a wire id — what a link note may name. */
    readonly linkOptions = computed<PickerOption[]>(() =>
        (this.store.workingSet()?.links ?? [])
            .filter((l) => !!l.linkId)
            .map((l) => ({ value: l.linkId!, label: `${l.source} → ${l.target}${l.kind ? ` (${l.kind})` : ''}` })),
    );

    /** The selected entity's raw ids that are in the Working Set — what expand/keep/hide/exclude may name. */
    readonly selectedInSet = computed(() => {
        const n = this.store.selected();
        return n ? idsInWorkingSet(n, this.store.workingSet()) : [];
    });

    /** LA-19: the sealed notes on the selected entity's Working Set ids. */
    readonly selectedNotes = computed(() => {
        const ids = this.selectedInSet();
        return (this.store.workingSet()?.annotations ?? []).filter((a) => ids.includes(a.id));
    });

    /** Why the last expand was answered by the flat Dataset instead of the edge index; null when it was not (or no step yet). */
    readonly fallbackNote = computed(() => expandSourceNote(this.store.lastStep()?.read));
    /** DR-U4: a log step's sealed read, when the server's log view says the link index answered it. */
    readSuffix(e: InvestigationLogEntry): string | null {
        return stepReadSuffix(e.read);
    }

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
        if (!p || this.title.invalid || this.purpose.invalid) return;
        const timeCol = this.timeColumn.value.trim() || this.timeCol();
        if (
            await this.store.start(
                p,
                this.purpose.value.trim(),
                this.title.value.trim(),
                timeCol,
                this.timeColumnZone.value,
            )
        ) {
            this.timeColumn.reset('');
            this.timeColumnZone.reset('');
            this.title.reset('');
            this.purpose.reset('');
            // Pressing Start is the analyst's confirmation: the queued top results become the first seed step.
            await this.seedQueued();
        }
    }

    /** Apply the queued ranked nodes as ONE seed step on the open Investigation, then empty the queue. */
    async seedQueued(): Promise<void> {
        const queued = this.store.queuedSeeds();
        if (!queued.length || !this.store.active()) return;
        const entityType = this.store.activeRef()?.entityType;
        const ids = [...new Set(queued.flatMap((s) => s.ids))];
        if (await this.store.apply({ op: 'seed', ids, ...(entityType ? { entityType } : {}) })) {
            this.store.clearQueuedSeeds();
        }
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
        // DR-D3: seeding is what the query graph was for - draw the Working Set it just grew, so Expand/Keep/Hide act on it.
        this.store
            .apply({ op: 'seed', ids: rawIdsOf(n), ...(entityType ? { entityType } : {}) })
            .then((ok) => ok && this.store.showWorkingSet.set(true));
    }

    /** The expand form's Advanced rung fields (LA-SPA-OWED-SURFACES-1). */
    private readonly rungForm = viewChild(InvestigationExpandRungComponent);

    async expand(all = false): Promise<void> {
        const form = this.rungForm();
        const rung = form ? form.rung() : {};
        if (!rung) return;
        const ok = await this.store.apply(
            all ? { op: 'expand', ...rung } : { op: 'expand', ids: this.selectedInSet(), ...rung },
        );
        if (!ok) form?.fail(this.store.error());
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

    async annotate(): Promise<void> {
        if (this.note.invalid) {
            this.note.markAsTouched();
            return;
        }
        const ok = await this.store.apply({ op: 'annotate', ids: this.selectedInSet(), note: this.note.value.trim() });
        if (ok) this.note.reset('');
    }

    /** D-U9: a note on the picked link, named by its `linkId`. */
    async annotateLink(): Promise<void> {
        const linkId = this.linkPick();
        if (!linkId) return;
        if (this.linkNote.invalid) {
            this.linkNote.markAsTouched();
            return;
        }
        const ok = await this.store.apply({ op: 'annotate', links: [linkId], note: this.linkNote.value.trim() });
        if (ok) this.linkNote.reset('');
    }

    /** Ask the server which Investigations the caller may read. */
    async listInvestigations(): Promise<void> {
        if (this.listBusy()) return;
        this.listBusy.set(true);
        this.listError.set('');
        try {
            const res = await firstValueFrom(this.inv.listInvestigations());
            this.listed.set(res.items);
            this.listedTruncated.set(res.truncated);
        } catch (err) {
            this.listError.set(apiErrorMessage(err, 'Could not list the Investigations.'));
        } finally {
            this.listBusy.set(false);
        }
    }

    /** Open a listed Investigation — remembered in the saved view like any other. */
    openListed(item: InvestigationListItem): void {
        this.order.set(null);
        this.store.adopt(item.id, item.title ?? undefined, this.store.activeRef() ?? undefined);
    }

    openById(): void {
        const id = this.openId.value.trim();
        if (!id || this.openId.invalid) return;
        this.order.set(null);
        this.store.adopt(id, undefined, this.store.activeRef() ?? undefined);
        this.openId.reset('');
    }

    /** LA-19: which days of the Investigation's own window have no rows at all (a gap is not innocence). */
    async checkCoverage(): Promise<void> {
        const id = this.store.activeId();
        if (!id || this.coverageBusy()) return;
        this.coverageBusy.set(true);
        this.coverageError.set('');
        this.coverage.set(null);
        this.coverageFor.set(id);
        try {
            this.coverage.set(await firstValueFrom(this.inv.investigationCoverage(id)));
        } catch (err) {
            this.coverageError.set(apiErrorMessage(err, 'Could not read the coverage.'));
        } finally {
            this.coverageBusy.set(false);
        }
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

    /**
     * Save a Working Set Widget pinned at the relation's CURRENT head — the head read through the same owner-only route
     * the tile will read, so the pin is exactly what a Frozen tile re-reads (`?at=step`) and checks (`workingSetHash`).
     */
    async pinToWidget(): Promise<void> {
        const id = this.store.activeId();
        if (!id || this.pinBusy()) return;
        if (this.pinForm.invalid) {
            this.pinForm.markAllAsTouched();
            return;
        }
        const { name, relation, mode } = this.pinForm.getRawValue();
        this.pinBusy.set(true);
        this.pinError.set('');
        this.pinnedWidget.set('');
        try {
            const { head } = await firstValueFrom(this.inv.workingSetRelation(id, { of: relation, limit: 1 }));
            await firstValueFrom(
                this.widgets.saveWorkingSetWidget({
                    name,
                    vizType: WORKING_SET_PLUGIN.meta.type,
                    viewId: id,
                    workingSet: pinBinding(relation, mode, head),
                    description: `${RELATION_NOUN[relation].title} of Investigation ${id} — ${
                        mode === 'live' ? 'Live' : `Frozen at step ${head.step}`
                    }`,
                }),
            );
            this.pinnedWidget.set(name);
            this.pinFormDirective()?.resetForm({ name: '', relation, mode });
        } catch (err) {
            this.pinError.set(apiErrorMessage(err, 'Could not save the Widget.'));
        } finally {
            this.pinBusy.set(false);
        }
    }

    /** LA-23: open the Investigation a template just created, keeping the open one's entity type for drawing. */
    adoptInstantiated(res: InstantiateTemplateResult): void {
        this.order.set(null);
        this.store.adopt(res.id, res.header?.title ?? undefined, this.store.activeRef() ?? undefined);
    }

    opLabel(e: InvestigationLogEntry): string {
        return e.kind === 'undo' ? 'undo' : (e.op ?? '');
    }

    time(at: string): string {
        const d = new Date(at);
        return isNaN(d.getTime()) ? at : d.toLocaleString();
    }
}
