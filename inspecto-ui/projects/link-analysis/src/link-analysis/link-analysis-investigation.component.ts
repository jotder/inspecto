import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import {
    ChangeDetectionStrategy,
    Component,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
    viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { LensService } from '@inspecto/core/api';
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
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatRadioModule } from '@angular/material/radio';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EntityProjection } from '@inspecto/core/graph';
import { firstValueFrom } from 'rxjs';
import {
    InstantiateTemplateResult,
    InvService,
    InvestigationCoverage,
    InvestigationLogEntry,
    WorkingSetRelationName,
} from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage, needsPermission } from '@inspecto/core/api';
import { WORKING_SET_PLUGIN } from '@inspecto/core/viz/plugins/view.plugins';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { DEFAULT_MAX_DEGREE, DegreePresets } from '@inspecto/link-analysis/graph/domain-profile';
import {
    PendingInvestigation,
    degreeOutcomeMessage,
    degreeState,
    presetsSummary,
    suspectTitle,
    windowLabel,
} from './investigate-number';
import { STRONGEST_NEIGHBOURS } from './neighbour-ranking';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { timeZoneOptions } from '@inspecto/core/schema/time-zones';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { LinkAnalysisDataStripComponent } from './link-analysis-data-strip.component';
import { stepReadSuffix } from './index-source';
import { expandSourceNote, idsInWorkingSet, moveStep, rawIdsOf, stepName } from './investigation-state';
import { RELATION_NOUN, pinBinding } from './working-set-widget';
import { LA_WIDGETS } from '@inspecto/link-analysis/la-host';
import {
    InvestigationSnapshotOpComponent,
    InvestigationThresholdOpComponent,
} from './investigation-band-ops.component';
import { InvestigationCompareOpComponent } from './investigation-compare-op.component';
import { InvestigationConnectOpComponent } from './investigation-connect-op.component';
import { InvestigationWindowOpComponent } from './investigation-window-op.component';
import { LinkAnalysisDossierComponent } from './link-analysis-dossier.component';
import { LinkAnalysisReferencesComponent } from './link-analysis-references.component';
import { LinkAnalysisDraftsComponent } from './link-analysis-drafts.component';
import { LinkAnalysisMembersComponent } from './link-analysis-members.component';
import { LinkAnalysisEmbedComponent } from './link-analysis-embed.component';
import { LinkAnalysisInvestigationCaseComponent } from './link-analysis-investigation-case.component';
import { LinkAnalysisOversightComponent } from './link-analysis-oversight.component';
import { LinkAnalysisTemplateMeasuresComponent } from './link-analysis-template-measures.component';
import { LinkAnalysisWorkingSetRowsComponent } from './link-analysis-working-set-rows.component';

/**
 * **Link Analysis — Investigation panel** (LA-10, SPA half). The right dock's Investigation tab: start an
 * Investigation over the current Entity/Link mapping, act on canvas entities with the five shipped ops, read
 * the ordered op log, undo, replay with a drift check, and re-order the steps into a FORK (D-E4). All state
 * lives in {@link InvestigationSessionStore}; this component is the view over it, drawn as one `section` (§5.8–§5.10).
 */
/** How often a held degree re-reads the log for its four-eyes decision. */
export const APPROVAL_POLL_MS = 15_000;

/** The parts of an Investigation: the Act panel, the dock tabs (§5.9) and the settings sections (§5.10). */
export type InvestigationSection = 'act' | 'settings' | 'oplog' | 'ws-rows' | 'coverage' | 'annotations' | 'excluded';
export const INVESTIGATION_SECTION_LABELS: Record<InvestigationSection, string> = {
    act: 'Investigation',
    settings: 'Investigation settings',
    oplog: 'Investigation step log',
    'ws-rows': 'Investigation Working Set rows',
    coverage: 'Investigation coverage',
    annotations: 'Investigation annotations',
    excluded: 'Investigation exclusions',
};
type MoreStepId = 'window' | 'threshold' | 'snapshot' | 'compare' | 'connect';
/** One heading of the Steps section and its actions (LA-UNSAVED-INVESTIGATION-1: groups of buttons, not a menu). */
export interface StepGroup {
    id: string;
    title: string;
    steps: readonly { id: MoreStepId; label: string }[];
}
/** The Steps section, in order of importance: each action directly reachable as a button under its group. */
export const STEP_GROUPS: readonly StepGroup[] = [
    { id: 'connect', title: 'Connect', steps: [{ id: 'connect', label: 'Connect start entities' }] },
    {
        id: 'time',
        title: 'Time',
        steps: [
            { id: 'window', label: 'Time window' },
            { id: 'snapshot', label: 'Snapshot marker' },
        ],
    },
    {
        id: 'compare',
        title: 'Compare',
        steps: [
            { id: 'threshold', label: 'Threshold' },
            { id: 'compare', label: 'Compare' },
        ],
    },
];
const MORE_STEPS = STEP_GROUPS.flatMap((g) => g.steps);

import { LaFoldComponent } from './la-fold.component';
import { NgTemplateOutlet } from '@angular/common';
import { DISABLED_REASON } from './disabled-reasons';
import { laLimit } from './la-limits';

@Component({
    selector: 'inspecto-link-analysis-investigation',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [LaFoldComponent, PenFieldComponent, NgTemplateOutlet,
        FormsModule,
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatIconModule,
        MatMenuModule,
        MatRadioModule,
        MatTooltipModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoOptionPickerComponent,
        LinkAnalysisDataStripComponent,
        InvestigationWindowOpComponent,
        InvestigationThresholdOpComponent,
        InvestigationSnapshotOpComponent,
        InvestigationCompareOpComponent,
        InvestigationConnectOpComponent,
        LinkAnalysisDossierComponent,
        LinkAnalysisDraftsComponent,
        LinkAnalysisReferencesComponent,
        LinkAnalysisInvestigationCaseComponent,
        LinkAnalysisMembersComponent,
        LinkAnalysisEmbedComponent,
        LinkAnalysisOversightComponent,
        LinkAnalysisTemplateMeasuresComponent,
        LinkAnalysisWorkingSetRowsComponent,
    ],
    // Back on the tab: a decision made elsewhere (an approval in Oversight) shows without reopening.
    host: { class: 'block', '(window:focus)': 'onWindowFocus()' },
    templateUrl: './link-analysis-investigation.component.html',
})
export class LinkAnalysisInvestigationComponent {
    readonly store = inject(InvestigationSessionStore);
    private inv = inject(InvService);
    private widgets = inject(LA_WIDGETS);
    private confirm = inject(InspectoConfirmService);
    private lens = inject(LensService);
    /** Steps, Undo, marks and Drafts are gated server-side on `canManageIncidents`; the tab asks the same question. */
    readonly canWrite = computed(() => this.lens.canManageIncidents());
    private laSettings = inject(LinkAnalysisSettingsService);
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

    /** A deep-linked "Investigate a number" waiting for a purpose; it replaces the start form while set. */
    readonly pending = input<PendingInvestigation | null>(null);
    /** The current profile's "Expand next degree" rung and the degree it stops at. */
    readonly degreePresets = input<DegreePresets>({});
    readonly maxDegree = input(DEFAULT_MAX_DEGREE);
    /** The pending investigation was created (or abandoned) — the host drops it. */
    readonly pendingDone = output<void>();
    /** Why the Expand section shows no form (LA-INV-EXPAND-1): expanding is a change to the Investigation. */
    readonly expandNeeds = needsPermission('canManageIncidents');
    /** Which part of the Investigation this instance draws (§5.8 / §5.9); every instance shares the one store. */
    readonly section = input<InvestigationSection>('act');
    /** "Settings…" in the header's ⋯ menu: the screen opens the Investigation's settings. */
    readonly settingsRequested = output<void>();
    /** "Open the Op log": the screen opens that dock tab. */
    readonly dockRequested = output<InvestigationSection>();
    readonly sectionLabel = computed(() => INVESTIGATION_SECTION_LABELS[this.section()]);
    /** The Steps section (§5.8 block 7): its groups, and the one step open in place, or null. */
    readonly stepGroups = STEP_GROUPS;
    readonly moreStep = signal<MoreStepId | null>(null);
    /** The open step when it belongs to this group (it opens under its own group). */
    moreStepIn(g: StepGroup): MoreStepId | null {
        const m = this.moreStep();
        return m && g.steps.some((x) => x.id === m) ? m : null;
    }

    // ── LA-UNSAVED-INVESTIGATION-1: working before Save ──
    /** How many entities are selected on the canvas: Start from selected names them. */
    readonly selectionCount = input(0);
    /** Save…: the page asks for the Name and Purpose and records the steps. */
    readonly saveRequested = output<void>();
    /** Close of the unsaved work: the page asks Save, Discard or Cancel. */
    readonly closeRequested = output<void>();
    /** Start from selected: the page draws the selection's links and records them as start entities. */
    readonly startFromSelected = output<void>();
    readonly saveNeeds = needsPermission('canManageIncidents');
    readonly pickNode = DISABLED_REASON.selectEntities;
    readonly moreStepLabel = computed(() => MORE_STEPS.find((m) => m.id === this.moreStep())?.label ?? '');

    // ── Settings ▸ General: rename and restate the purpose (LA-INVESTIGATION-RENAME-1) ──
    /** The caller's role on the open Investigation (members route): null = no Subject, nothing enforced; 'reader' = not a member; undefined = not read yet. */
    private readonly detailsRole = signal<string | null | undefined>(undefined);
    /** Title and Purpose are editable for a lead holding `canManageIncidents` — the server's own gate. */
    readonly canEditDetails = computed(
        () => this.canWrite() && (this.detailsRole() === 'lead' || this.detailsRole() === null),
    );
    /** Why Title and Purpose are read-only; '' while they are editable or the role is still being read. */
    readonly detailsReadOnlyReason = computed(() => {
        if (!this.canWrite()) return needsPermission('canManageIncidents');
        if (this.detailsRole() === undefined || this.canEditDetails()) return '';
        return 'Only a lead can change the name or purpose.';
    });
    /** The longest purpose the Space accepts (`input.purpose_length`, the server's own limit), read when the limits arrive. */
    readonly purposeMax = computed(() => laLimit('input.purpose_length'));
    readonly detailsTitle = new FormControl('', { nonNullable: true });
    readonly detailsPurpose = new FormControl('', { nonNullable: true });
    readonly detailsTitleError = signal('');
    readonly detailsPurposeError = signal('');

    /** Commit one edited field; a blank value is refused here, a server refusal puts the value in force back. */
    async saveDetails(field: 'title' | 'purpose', value: string): Promise<void> {
        const id = this.store.activeId();
        if (!id || !this.canEditDetails()) return;
        const error = field === 'title' ? this.detailsTitleError : this.detailsPurposeError;
        const v = value.trim();
        if (!v) {
            error.set(field === 'title' ? 'A name is required.' : 'A purpose is required.');
            this.showDetails(); // the field goes back to the value in force, never a bare "—" (T-034)
            return;
        }
        error.set('');
        if (!(await this.store.editDetails(id, { [field]: v }))) this.showDetails();
    }

    /** Put the values in force into the two fields (no commit fires). */
    private showDetails(): void {
        const h = this.store.header();
        this.detailsTitle.setValue(this.store.displayTitle() || h?.id || '', { emitEvent: false });
        this.detailsPurpose.setValue(h?.purpose ?? '', { emitEvent: false });
    }

    private async loadDetailsRole(id: string): Promise<void> {
        let role: string | null = 'reader';
        try {
            role = (await firstValueFrom(this.inv.investigationMembers(id))).you;
        } catch {
            // not a member (a Case member reads 404): read-only
        }
        if (this.store.activeId() === id) this.detailsRole.set(role);
    }

    /** Back on the tab: one instance (the Act panel) re-reads, so a decision made elsewhere shows without reopening. */
    onWindowFocus(): void {
        if (this.section() === 'act') void this.store.reload();
    }

    constructor() {
        // Settings ▸ General and the Act header: read the caller's role once per open Investigation, and show the values in force.
        effect(() => {
            const id = this.section() === 'settings' || this.section() === 'act' ? this.store.activeId() : null;
            this.detailsRole.set(undefined);
            if (id) untracked(() => void this.loadDetailsRole(id));
        });
        effect(() => {
            this.store.header();
            this.store.displayTitle();
            untracked(() => this.showDetails());
        });
        this.detailsTitle.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => void this.saveDetails('title', v));
        this.detailsPurpose.valueChanges.pipe(takeUntilDestroyed()).subscribe((v) => void this.saveDetails('purpose', v));
        // Prefill the time column from the canvas / Dataset default; an analyst's own edit is never overwritten.
        effect(() => {
            const col = this.timeCol();
            if (this.timeColumn.pristine) this.timeColumn.setValue(col, { emitEvent: false });
        });
        // A deep link names its suspect, unless the analyst already typed a title: "Suspect <id>" only where the Space
        // is known not to mask — the title is stored and shown as a value, so otherwise it reads "Suspect number".
        effect(() => {
            const p = this.pending();
            const mode = this.inv.masking?.()?.mode;
            if (p && this.title.pristine) this.title.setValue(suspectTitle(p.seed, mode), { emitEvent: false });
        });
        // A held degree: poll the log modestly until the four-eyes request is decided (the effect re-runs, and the
        // interval is cleared, as soon as the reconciled outcome is no longer waiting).
        effect((onCleanup) => {
            if (this.section() !== 'act' || !this.store.awaitingApproval()) return;
            const timer = setInterval(() => void this.store.reload(), APPROVAL_POLL_MS);
            onCleanup(() => clearInterval(timer));
        });
    }

    /** The window the next expand reads, in words (`30 days to 29 Sep 2026` / `all available data`); null = no window preset. */
    readonly windowNote = computed(() =>
        this.store.header()?.timeCol ? windowLabel(this.degreePresets(), this.store.windowEnd()) : null,
    );

    /** What the pending investigation's Start will do, in words. */
    readonly pendingSummary = computed(() => {
        const p = this.pending();
        return p ? presetsSummary(p.presets, p.windowEnd) : '';
    });

    /** "Investigate <id>" was pressed with the purpose missing — the required message shows until it is filled. */
    readonly pendingTried = signal(false);
    private readonly pendingPurpose = viewChild<PenFieldComponent>('pendingPurpose');

    /** "Investigate <id>": create, seed, then expand ONE degree (LA-INVESTIGATE-NUMBER-HAIRBALL-1: two drew 895 nodes on a hub-adjacent number; Choose neighbours goes further). Asks for the purpose like every Investigation. */
    async investigatePending(): Promise<void> {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        const p = this.pending();
        if (!p) return;
        if (this.title.invalid || this.purpose.invalid) {
            this.title.markAsTouched();
            this.purpose.markAsTouched();
            // The button stays enabled (house pattern): the press says what is missing and lands on the field.
            this.pendingTried.set(true);
            if (this.purpose.invalid) this.pendingPurpose()?.focus();
            return;
        }
        const ok = await this.store.investigateNumber({
            projection: p.projection,
            purpose: this.purpose.value.trim(),
            title: this.title.value.trim(),
            timeCol: p.timeCol,
            eventsCol: p.eventsCol,
            seed: p.seed,
            seeds: p.seeds,
            presets: p.presets,
            maxDegree: p.maxDegree,
            degrees: 1,
            windowEnd: p.windowEnd,
            profileId: p.profileId,
        });
        // Once created the Investigation is open — the pending form is done even when a later expand was refused.
        if (ok || this.store.active()) {
            this.title.reset('');
            this.purpose.reset('');
            this.pendingTried.set(false);
            this.pendingDone.emit();
        }
    }

    // ── "Expand next degree" ──
    readonly degree = computed(() =>
        degreeState(
            this.store.workingSet(),
            this.maxDegree(),
            this.store.degreeOutcomes(),
            false,
            this.store.overrideSteps(),
        ),
    );
    /** The blocked reason worth its own line — a held degree already says it is waiting in the results list. */
    readonly blockedNote = computed(() => (this.store.awaitingApproval() ? null : this.degree().blocked));

    // ── supernode suppression: flagged entities, shown but not expanded unless the analyst overrides ──
    /** Working Set entities a sealed read flagged high connectivity. */
    readonly hubs = computed(() => {
        const sealed = this.store.hubContacts();
        return (this.store.workingSet()?.entities ?? [])
            .filter((e) => e.highConnectivity)
            .map((e) => (e.contacts == null && sealed.has(e.id) ? { ...e, contacts: sealed.get(e.id)! } : e));
    });
    /** A flagged entity an approved/applied override already expanded through — no second "Expand anyway". */
    isOverridden(id: string): boolean {
        return this.store.overriddenHubs().has(id);
    }
    /** The badge for one flagged entity — the store's single source, shared with the node dialog and Working Set rows;
     *  without an id it is the threshold wording. */
    hubBadge(contacts?: number | null, id?: string): string {
        return this.store.hubBadge(id ?? '', contacts, this.laSettings.limits().hubThresholdInForce);
    }
    /** The selected entity's badge: its own count when it is a single flagged id, else the threshold wording. */
    readonly selectedHubBadge = computed(() => {
        const ids = this.selectedHubs();
        const one = ids.length === 1 ? this.hubs().find((e) => e.id === ids[0]) : undefined;
        return this.hubBadge(one?.contacts, one?.id);
    });
    /** The selected entity's flagged ids — "Expand one hop" would be refused for them; "Expand anyway" is offered. */
    readonly selectedHubs = computed(() => {
        const flagged = new Set(this.hubs().map((e) => e.id));
        return this.selectedInSet().filter((id) => flagged.has(id));
    });

    /** Ask before an override: what it expands through, that it is sealed, and the purpose it is recorded under. */
    private confirmOverride(ids: string[]): Promise<boolean> {
        const purpose = this.store.header()?.purpose;
        const what =
            ids.length === 1
                ? `the busy entity ${ids[0]}`
                : `${ids.length} busy entities (${ids.slice(0, 5).join(', ')}${ids.length > 5 ? ', …' : ''})`;
        return this.confirm.confirmDestructive(
            `Expand through ${what}? Busy entities can pull in many unrelated entities.\n\n` +
                'The override is logged with your name and shown in the Dossier' +
                (purpose ? `, under the stated purpose: “${purpose}”.` : '.'),
            { title: 'Expand through busy entities?', confirmText: 'Expand anyway', destructive: false },
        );
    }

    /** "Expand anyway" on one flagged entity (`expandHubs: [id]`), after the confirmation. */
    async expandAnyway(id: string): Promise<void> {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        if (!(await this.confirmOverride([id]))) return;
        await this.store.expandHub(id, this.store.withPresets(this.degreePresets(), {}));
    }
    /** 1 … maxDegree, for the indicator. */
    readonly degreeSteps = computed(() => Array.from({ length: this.maxDegree() }, (_, i) => i + 1));
    readonly outcomeMessage = degreeOutcomeMessage;

    readonly timeZones: PickerOption[] = timeZoneOptions('UTC (default)');

    readonly title = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] });
    /** D-U5: the stated purpose / legal basis — required by the server, recorded and shown in the Dossier, not enforced. */
    readonly purpose = new FormControl('', {
        nonNullable: true,
        // the Space's limit at check time, never a number of our own (it may be raised above the shipped 1000)
        validators: [Validators.required, Validators.pattern(/\S/), (c) => Validators.maxLength(laLimit('input.purpose_length'))(c)],
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
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
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
            this.timeColumn.reset(this.timeCol());
            this.timeColumnZone.reset('');
            this.title.reset('');
            this.purpose.reset('');
            // Pressing Start is the analyst's confirmation: the queued top results become the first seed step.
            await this.seedQueued();
        }
    }

    /** Apply the queued ranked nodes as ONE seed step on the open Investigation, then empty the queue. */
    async seedQueued(): Promise<void> {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        const queued = this.store.queuedSeeds();
        if (!queued.length || !this.store.active()) return;
        const entityType = this.store.activeRef()?.entityType;
        const ids = [...new Set(queued.flatMap((s) => s.ids))];
        if (await this.store.apply({ op: 'seed', ids, ...(entityType ? { entityType } : {}) })) {
            this.store.clearQueuedSeeds();
        }
    }

    switchTo(id: string | null): void {
        if (!id) return;
        if (id === this.store.activeId()) {
            // Reopening the open one refreshes it: its log and Working Set may have moved on elsewhere.
            void this.store.reload();
            return;
        }
        this.order.set(null);
        this.store.open(id);
    }

    seed(): void {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        const n = this.store.selected();
        if (!n) return;
        const entityType = this.store.activeRef()?.entityType;
        // DR-D3: seeding is what the query graph was for - draw the Working Set it just grew, so Expand/Keep/Hide act on it.
        this.store
            .apply({ op: 'seed', ids: rawIdsOf(n), ...(entityType ? { entityType } : {}) })
            .then((ok) => ok && this.store.showWorkingSet.set(true));
    }

    /**
     * The quick one-hop expands (selected entity, whole Working Set) with the profile's presets. The read settings and
     * the hub override live in the Expand dialog (LA-STUDIO-UI-REORG-1 §5.4).
     */
    async expand(all = false): Promise<void> {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        // Every expand from the panel carries the profile's preset window and budget, like "Expand next degree".
        const rung = this.store.withPresets(this.degreePresets(), {});
        const ids = all ? undefined : this.selectedInSet();
        if (await this.store.apply(ids ? { op: 'expand', ids, ...rung } : { op: 'expand', ...rung })) {
            const step = this.store.lastStep();
            if (step?.status === 'pending' && step.pending?.id) this.store.markOneHop(step.pending.id, ids);
        }
    }

    // ── LA-EXPAND-PICK-NEIGHBOURS-1: expand to the neighbours the analyst chooses ──
    readonly neighboursOpen = signal(false);
    /** Neighbour ids ticked in the list (cleared whenever a list is read or a step is added). */
    readonly picked = signal<ReadonlySet<string>>(new Set());
    /** The list for the entity selected NOW; a list read for another selection is not shown. */
    readonly neighbourList = computed(() => {
        const list = this.store.neighbours();
        return list && list.of === this.selectedInSet()[0] ? list : null;
    });
    readonly strongestCount = STRONGEST_NEIGHBOURS;

    toggleNeighbours(): void {
        const open = !this.neighboursOpen();
        this.neighboursOpen.set(open);
        if (open) void this.readNeighbours();
    }

    async readNeighbours(): Promise<void> {
        const id = this.selectedInSet()[0];
        if (!id) return;
        this.picked.set(new Set());
        await this.store.loadNeighbours(id);
    }

    togglePicked(id: string): void {
        this.picked.update((p) => {
            const next = new Set(p);
            if (!next.delete(id)) next.add(id);
            return next;
        });
    }

    /** Add the ticked neighbours, or the strongest ones (`strongest`), as ONE expand step from the selected entity. */
    async addNeighbours(strongest = false): Promise<void> {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        const from = this.selectedInSet()[0];
        const list = this.neighbourList();
        if (!from || !list) return;
        const only = strongest ? list.rows.slice(0, STRONGEST_NEIGHBOURS).map((n) => n.id) : [...this.picked()];
        if (!only.length) return;
        const rung = this.store.withPresets(this.degreePresets(), {});
        // A flagged hub is only reached through the same confirmed override as "Expand anyway".
        const throughHub = this.selectedHubs().includes(from) && !this.isOverridden(from);
        if (throughHub && !(await this.confirmOverride([from]))) return;
        if (await this.store.expandPicked(from, only, rung, throughHub)) this.picked.set(new Set());
    }

    /** The scope list's input: ids pasted one per line or comma-separated. */
    readonly scopeIds = new FormControl('', { nonNullable: true });
    readonly scopeError = signal('');

    private scopeParsed(): string[] {
        return [...new Set(this.scopeIds.value.split(/[\s,;]+/).filter(Boolean))];
    }

    /** Add ids (the box, or the selection) to the supernodes every expand goes through. */
    scopeAdd(ids: string[] = this.scopeParsed()): void {
        if (!this.canWrite() || !ids.length) return;
        const cur = this.store.scope();
        this.store.setScope({ ...cur, include: [...cur.include, ...ids] });
        this.scopeIds.reset('');
        this.scopeError.set('');
    }

    scopeRemove(id: string): void {
        const cur = this.store.scope();
        this.store.setScope({ ...cur, include: cur.include.filter((i) => i !== id) });
    }

    scopeOnly(only: boolean): void {
        this.store.setScope({ ...this.store.scope(), only });
    }

    /** Leave the listed entities out of every expand: one `exclude` step (works before they are in the Working Set). */
    async scopeExclude(): Promise<void> {
        if (!this.canWrite()) return;
        const ids = this.scopeParsed();
        if (!ids.length) return;
        const ok = await this.store.apply({ op: 'exclude', ids, reason: 'Left out of the investigated set' });
        if (ok) {
            this.scopeIds.reset('');
            this.scopeError.set('');
        } else this.scopeError.set(this.store.error());
    }

    mark(op: 'hide' | 'keep'): void {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        this.store.apply({ op, ids: this.selectedInSet() });
    }

    async exclude(): Promise<void> {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
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
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        if (this.note.invalid) {
            this.note.markAsTouched();
            return;
        }
        const ok = await this.store.apply({ op: 'annotate', ids: this.selectedInSet(), note: this.note.value.trim() });
        if (ok) this.note.reset('');
    }

    /** D-U9: a note on the picked link, named by its `linkId`. */
    async annotateLink(): Promise<void> {
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
        const linkId = this.linkPick();
        if (!linkId) return;
        if (this.linkNote.invalid) {
            this.linkNote.markAsTouched();
            return;
        }
        const ok = await this.store.apply({ op: 'annotate', links: [linkId], note: this.linkNote.value.trim() });
        if (ok) this.linkNote.reset('');
    }

    /** Whether the caller may delete this Investigation (the capability; a read-only Case member's delete is refused by the server). */
    canDelete(_id: string): boolean {
        return this.canWrite();
    }

    /** Delete an Investigation after a confirmation that says it cannot be undone. */
    async deleteInvestigation(id: string, label: string): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Delete the Investigation “${label}”?\n\nIts log, its Working Sets and its Drafts are removed from the server. ` +
                'This cannot be undone. A record that it was deleted, by whom and when, is kept in the audit trail.',
            { title: 'Delete Investigation?', confirmText: 'Delete', destructive: true },
        );
        if (!ok || !(await this.store.deleteInvestigation(id))) return;
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
        if (!this.canWrite()) return; // read-only: Enter in a field must not fire a write
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
        return stepName(e.kind === 'undo' ? 'undo' : e.op);
    }

    /** One format and one zone, labelled, as in File ▸ Open… (T-186). */
    time(at: string): string {
        const d = new Date(at);
        return isNaN(d.getTime()) ? at : `${d.toISOString().slice(0, 16).replace('T', ' ')} UTC`;
    }
}
