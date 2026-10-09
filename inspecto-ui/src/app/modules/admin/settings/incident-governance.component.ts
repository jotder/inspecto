import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Observable, catchError, of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';

import {
    ComponentDef,
    ComponentType,
    ComponentsService,
    LensService,
    ObjectsService,
    apiErrorMessage,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, pickerOptions } from 'app/inspecto/components/option-picker.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { validateGroup } from 'app/inspecto/query/condition-rules';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { emptyGroup } from 'app/inspecto/query/query-types';
import { WorkflowDiagramComponent } from './workflow-diagram.component';
import {
    ESCALATION_CONTEXT_COLUMNS,
    EscalationRuleDraft,
    GOVERNED_OBJECT_TYPES,
    WEEK_DAYS,
    describeEscalationRule,
    escalationRuleContent,
    escalationRuleDraft,
    governanceId,
    slaContent,
    slaDraft,
    workflowContent,
    workflowDraft,
    WorkflowDraft,
} from 'app/inspecto/governance/governance-model';

/**
 * Settings ▸ **Incident governance** (ASSURE-WORKFLOW-SLA-1): the object lifecycle (Workflow), the SLA policy per
 * (object type, priority) with its business calendar, and the Escalation Rules. Reads are open; every write goes
 * to a `canAdminister` route (`/components/workflow|sla-policy|escalation-rule`), so without that grant the
 * section is read-only. The server validates fail-closed and may HOLD a write for approval (202); either answer
 * is shown here. A saved Workflow takes effect on the next transition — no restart.
 */
@Component({
    selector: 'inspecto-incident-governance',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        InspectoPageHeaderComponent,
        QueryConditionGroupComponent,
        WorkflowDiagramComponent,
    ],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Incident governance"
                subtitle="Lifecycle, SLA targets and Escalation Rules for operational objects."
                [inset]="false"
            />
            @if (!canEdit()) {
                <inspecto-alert variant="info" title="Read-only">
                    Changing a workflow, an SLA policy or an Escalation Rule needs the administer capability.
                </inspecto-alert>
            }
            <form [formGroup]="typeForm" class="max-w-80">
                <inspecto-option-picker label="Object type" formControlName="objectType" [options]="typeOptions" />
            </form>
            @if (lastError()) {
                <inspecto-alert variant="error" title="Not saved">{{ lastError() }}</inspecto-alert>
            }

            @if (unavailable(); as reason) {
                <inspecto-alert variant="warning" title="Not available in this edition">{{ reason }}</inspecto-alert>
            } @else {
                <section class="flex flex-col gap-3" aria-labelledby="gov-workflow-heading">
                    <h2 id="gov-workflow-heading" class="text-lg font-semibold">Workflow</h2>
                    <p class="text-secondary text-sm">
                        One initial state, the terminal states, and every transition. An Incident may finish only
                        through RESOLVED (where the Disposition and postmortem are required) or by archiving.
                    </p>
                    <form [formGroup]="workflow" class="flex flex-col gap-3" (ngSubmit)="saveWorkflow()">
                        <div class="flex flex-wrap gap-3">
                            <mat-form-field class="w-56">
                                <mat-label>Initial state</mat-label>
                                <input matInput formControlName="initial" />
                                @if (workflow.controls.initial.hasError('required')) {
                                    <mat-error>An initial state is required.</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field class="w-96">
                                <mat-label>Terminal states (comma-separated)</mat-label>
                                <input matInput formControlName="terminal" />
                                @if (workflow.controls.terminal.hasError('required')) {
                                    <mat-error>Declare at least one terminal state.</mat-error>
                                }
                            </mat-form-field>
                        </div>
                        <div class="flex flex-wrap items-start gap-6">
                            <table class="w-full max-w-200 text-sm">
                                <caption class="sr-only">
                                    Transitions
                                </caption>
                                <thead>
                                    <tr class="text-secondary text-left">
                                        <th scope="col" class="py-1">From</th>
                                        <th scope="col">Action</th>
                                        <th scope="col">To</th>
                                        <th scope="col"><span class="sr-only">Remove</span></th>
                                    </tr>
                                </thead>
                                <tbody formArrayName="transitions">
                                    @for (row of transitions.controls; track row; let i = $index) {
                                        <tr [formGroupName]="i">
                                            <td>
                                                <input
                                                    class="w-full rounded border px-2 py-1"
                                                    formControlName="from"
                                                    [attr.aria-label]="'Transition ' + (i + 1) + ' from'"
                                                />
                                            </td>
                                            <td>
                                                <input
                                                    class="w-full rounded border px-2 py-1"
                                                    formControlName="action"
                                                    [attr.aria-label]="'Transition ' + (i + 1) + ' action'"
                                                />
                                            </td>
                                            <td>
                                                <input
                                                    class="w-full rounded border px-2 py-1"
                                                    formControlName="to"
                                                    [attr.aria-label]="'Transition ' + (i + 1) + ' to'"
                                                />
                                            </td>
                                            <td>
                                                <button
                                                    mat-icon-button
                                                    type="button"
                                                    [disabled]="!canEdit()"
                                                    (click)="transitions.removeAt(i)"
                                                    [attr.aria-label]="'Remove transition ' + (i + 1)"
                                                >
                                                    <mat-icon svgIcon="heroicons_outline:trash"></mat-icon>
                                                </button>
                                            </td>
                                        </tr>
                                    }
                                </tbody>
                            </table>
                            <div class="flex flex-col gap-1 overflow-x-auto">
                                <h3 class="text-sm font-semibold">State diagram (read-only)</h3>
                                <inspecto-workflow-diagram [draft]="workflowDraftView()" />
                            </div>
                        </div>
                        <div class="flex gap-2">
                            <button mat-stroked-button type="button" [disabled]="!canEdit()" (click)="addTransition()">
                                Add transition
                            </button>
                            <button mat-flat-button color="primary" type="submit" [disabled]="!canEdit()">
                                Save workflow
                            </button>
                        </div>
                    </form>
                </section>

                <section class="flex flex-col gap-3" aria-labelledby="gov-sla-heading">
                    <h2 id="gov-sla-heading" class="text-lg font-semibold">SLA policy</h2>
                    <p class="text-secondary text-sm">
                        Response and resolution targets per priority, counted in working time. "*" is the fallback
                        priority.
                    </p>
                    <form [formGroup]="sla" class="flex flex-col gap-3" (ngSubmit)="saveSla()">
                        <div class="flex flex-wrap gap-3">
                            <mat-form-field class="w-64">
                                <mat-label>Time zone (IANA, e.g. Europe/London)</mat-label>
                                <input matInput formControlName="zone" />
                                @if (sla.controls.zone.hasError('required')) {
                                    <mat-error>An explicit time zone is required.</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field class="w-32">
                                <mat-label>Day starts</mat-label>
                                <input matInput formControlName="start" placeholder="09:00" />
                            </mat-form-field>
                            <mat-form-field class="w-32">
                                <mat-label>Day ends</mat-label>
                                <input matInput formControlName="end" placeholder="17:00" />
                            </mat-form-field>
                            <mat-form-field class="w-96">
                                <mat-label>Holidays (yyyy-mm-dd, comma-separated)</mat-label>
                                <input matInput formControlName="holidays" />
                            </mat-form-field>
                        </div>
                        <fieldset class="flex flex-wrap gap-3">
                            <legend class="text-secondary mb-1 text-sm">Working days</legend>
                            @for (d of weekDays; track d) {
                                <mat-checkbox
                                    [checked]="hasDay(d)"
                                    [disabled]="!canEdit()"
                                    (change)="toggleDay(d, $event.checked)"
                                    >{{ d }}</mat-checkbox
                                >
                            }
                        </fieldset>
                        <table class="w-full max-w-160 text-sm">
                            <caption class="sr-only">
                                SLA targets
                            </caption>
                            <thead>
                                <tr class="text-secondary text-left">
                                    <th scope="col" class="py-1">Priority</th>
                                    <th scope="col">Response (min)</th>
                                    <th scope="col">Resolution (min)</th>
                                    <th scope="col"><span class="sr-only">Remove</span></th>
                                </tr>
                            </thead>
                            <tbody formArrayName="targets">
                                @for (row of targets.controls; track row; let i = $index) {
                                    <tr [formGroupName]="i">
                                        <td>
                                            <input
                                                class="w-full rounded border px-2 py-1"
                                                formControlName="priority"
                                                [attr.aria-label]="'Target ' + (i + 1) + ' priority'"
                                            />
                                        </td>
                                        <td>
                                            <input
                                                type="number"
                                                min="1"
                                                class="w-full rounded border px-2 py-1"
                                                formControlName="responseMinutes"
                                                [attr.aria-label]="'Target ' + (i + 1) + ' response minutes'"
                                            />
                                        </td>
                                        <td>
                                            <input
                                                type="number"
                                                min="1"
                                                class="w-full rounded border px-2 py-1"
                                                formControlName="resolutionMinutes"
                                                [attr.aria-label]="'Target ' + (i + 1) + ' resolution minutes'"
                                            />
                                        </td>
                                        <td>
                                            <button
                                                mat-icon-button
                                                type="button"
                                                [disabled]="!canEdit()"
                                                (click)="targets.removeAt(i)"
                                                [attr.aria-label]="'Remove target ' + (i + 1)"
                                            >
                                                <mat-icon svgIcon="heroicons_outline:trash"></mat-icon>
                                            </button>
                                        </td>
                                    </tr>
                                }
                            </tbody>
                        </table>
                        <div class="flex gap-2">
                            <button mat-stroked-button type="button" [disabled]="!canEdit()" (click)="addTarget()">
                                Add target
                            </button>
                            <button mat-flat-button color="primary" type="submit" [disabled]="!canEdit()">
                                Save SLA policy
                            </button>
                        </div>
                    </form>
                </section>

                <section class="flex flex-col gap-3" aria-labelledby="gov-rules-heading">
                    <h2 id="gov-rules-heading" class="text-lg font-semibold">
                        Escalation Rules ({{ rules().length }})
                    </h2>
                    <p class="text-secondary text-sm">Each rule fires at most once per breach of each object.</p>
                    @if (rules().length) {
                        <ul class="flex max-w-200 flex-col divide-y rounded-lg border">
                            @for (r of rules(); track r.name) {
                                <li class="flex items-center justify-between gap-3 px-3 py-1.5">
                                    <span class="text-sm"
                                        ><span class="font-mono">{{ r.name }}</span> — {{ describe(r) }}</span
                                    >
                                    @if (canEdit()) {
                                        <button
                                            mat-icon-button
                                            type="button"
                                            class="ml-auto"
                                            (click)="editRule(r)"
                                            [attr.aria-label]="'Edit Escalation Rule ' + r.name"
                                        >
                                            <mat-icon svgIcon="heroicons_outline:pencil"></mat-icon>
                                        </button>
                                        <button
                                            mat-icon-button
                                            type="button"
                                            (click)="removeRule(r.name)"
                                            [attr.aria-label]="'Delete Escalation Rule ' + r.name"
                                        >
                                            <mat-icon svgIcon="heroicons_outline:trash"></mat-icon>
                                        </button>
                                    }
                                </li>
                            }
                        </ul>
                    } @else {
                        <p class="text-secondary text-sm">No Escalation Rules.</p>
                    }
                    @if (canEdit()) {
                        @if (editing(); as e) {
                            <p class="text-sm font-medium" role="status" data-testid="rule-editing">
                                Editing Escalation Rule <span class="font-mono">{{ e.name }}</span>
                            </p>
                        }
                        <form [formGroup]="rule" class="flex flex-wrap items-start gap-3" (ngSubmit)="addRule()">
                            <mat-form-field class="w-48">
                                <mat-label>Rule id</mat-label>
                                <input matInput formControlName="id" />
                                @if (rule.controls.id.hasError('required')) {
                                    <mat-error>An id is required.</mat-error>
                                }
                            </mat-form-field>
                            <div class="w-48">
                                <inspecto-option-picker label="Fires" formControlName="on" [options]="onOptions" />
                            </div>
                            @if (rule.controls.on.value === 'age') {
                                <mat-form-field class="w-40">
                                    <mat-label>After minutes</mat-label>
                                    <input matInput type="number" min="1" formControlName="afterMinutes" />
                                </mat-form-field>
                            } @else {
                                <div class="w-48">
                                    <inspecto-option-picker
                                        label="Target"
                                        formControlName="target"
                                        [options]="targetOptions"
                                    />
                                </div>
                            }
                            <mat-form-field class="w-40">
                                <mat-label>Only priority</mat-label>
                                <input matInput formControlName="priority" />
                            </mat-form-field>
                            <mat-form-field class="w-48">
                                <mat-label>Reassign to</mat-label>
                                <input matInput formControlName="reassign" />
                            </mat-form-field>
                            <mat-checkbox formControlName="notify">Notify</mat-checkbox>
                            <mat-checkbox formControlName="raisePriority">Raise priority</mat-checkbox>
                            <div class="w-full">
                                <button
                                    type="button"
                                    mat-button
                                    [attr.aria-expanded]="whenOpen()"
                                    aria-controls="gov-rule-when"
                                    [attr.aria-describedby]="whenShowsErrors() ? 'gov-rule-when-err' : null"
                                    (click)="whenOpen.set(!whenOpen())"
                                    data-testid="when-toggle"
                                >
                                    <mat-icon
                                        [svgIcon]="
                                            whenOpen()
                                                ? 'heroicons_outline:chevron-down'
                                                : 'heroicons_outline:chevron-right'
                                        "
                                    ></mat-icon>
                                    <span class="ml-1">Advanced match (condition tree)</span>
                                    @if (whenGroup.items.length; as n) {
                                        <span class="text-secondary ml-1 text-xs">({{ n }})</span>
                                    }
                                </button>
                                @if (whenShowsErrors()) {
                                    <ul
                                        class="text-warn mt-1 list-none text-xs"
                                        role="alert"
                                        id="gov-rule-when-err"
                                        data-testid="when-error"
                                    >
                                        @for (m of whenIssues(); track m) {
                                            <li>{{ m }}</li>
                                        }
                                    </ul>
                                }
                                <div
                                    id="gov-rule-when"
                                    role="region"
                                    aria-label="Advanced match"
                                    [hidden]="!whenOpen()"
                                >
                                    <p class="text-secondary mb-2 text-xs">
                                        The Only priority field above is a shortcut for one priority. Use the advanced
                                        match to narrow a rule further on the object being escalated: type, status,
                                        priority, severity, category, assignee, age, minutes to due, whether a target is
                                        breached, or whether it was already escalated. Both apply together.
                                    </p>
                                    @if (whenOpen()) {
                                        <inspecto-query-condition-group
                                            [group]="whenGroup"
                                            [columns]="contextColumns"
                                            [root]="true"
                                            (changed)="whenChanged()"
                                        />
                                    }
                                </div>
                            </div>
                            <button mat-flat-button color="primary" type="submit">
                                {{ editing() ? 'Save Escalation Rule' : 'Add Escalation Rule' }}
                            </button>
                            @if (editing()) {
                                <button mat-stroked-button type="button" (click)="cancelEdit()">Cancel</button>
                            }
                        </form>
                    }
                </section>
            }
        </div>
    `,
})
export class IncidentGovernanceComponent implements OnInit {
    private readonly components = inject(ComponentsService);
    private readonly objects = inject(ObjectsService);
    private readonly lens = inject(LensService);
    private readonly toastr = inject(ToastrService);
    private readonly fb = inject(FormBuilder);

    readonly canEdit = computed(() => this.lens.canAdminister());
    readonly typeOptions = pickerOptions(GOVERNED_OBJECT_TYPES);
    readonly weekDays = WEEK_DAYS;
    readonly onOptions = [
        { value: 'breach', label: 'On an SLA breach' },
        { value: 'age', label: 'At an age' },
    ];
    readonly targetOptions = [
        { value: 'resolution', label: 'Resolution target' },
        { value: 'response', label: 'Response target' },
    ];
    readonly rules = signal<ComponentDef[]>([]);
    /** The stored rule the form is editing (null = the form adds a new rule). */
    readonly editing = signal<ComponentDef | null>(null);
    /** The match context an Escalation Rule's `when` is evaluated over (server `escalationContext`). */
    readonly contextColumns = ESCALATION_CONTEXT_COLUMNS;
    /** The new rule's `when` tree; the condition-group editor mutates it in place. */
    whenGroup = emptyGroup('AND');
    /** True while editing a rule whose stored `when` is not a condition group the editor can show. */
    private whenOpaque = false;
    readonly whenOpen = signal(false);
    readonly whenIssues = signal<string[]>([]);
    private readonly whenTouched = signal(false);
    readonly lastError = signal<string | null>(null);
    /** Set when the bundle has no operational-objects module (503) — the editor is then not offered at all. */
    readonly unavailable = signal<string | null>(null);
    private readonly days = signal<string[]>(['MON', 'TUE', 'WED', 'THU', 'FRI']);

    readonly typeForm = this.fb.nonNullable.group({ objectType: 'INCIDENT' });
    readonly workflow = this.fb.nonNullable.group({
        initial: ['', Validators.required],
        terminal: ['', Validators.required],
        transitions: this.fb.array<FormGroup>([]),
    });
    readonly sla = this.fb.nonNullable.group({
        zone: ['', Validators.required],
        start: '09:00',
        end: '17:00',
        holidays: '',
        targets: this.fb.array<FormGroup>([]),
    });
    readonly rule = this.fb.nonNullable.group({
        id: ['', Validators.required],
        on: 'breach' as 'breach' | 'age',
        target: 'resolution' as 'resolution' | 'response',
        afterMinutes: 60 as number | null,
        priority: '',
        reassign: '',
        notify: true,
        raisePriority: false,
    });

    /** The workflow as currently edited — feeds the read-only state diagram. */
    readonly workflowDraftView = signal<WorkflowDraft>({ objectType: '', initial: '', terminal: '', transitions: [] });

    get transitions(): FormArray<FormGroup> {
        return this.workflow.controls.transitions;
    }

    get targets(): FormArray<FormGroup> {
        return this.sla.controls.targets;
    }

    private syncDiagram(): void {
        const v = this.workflow.getRawValue();
        this.workflowDraftView.set({
            objectType: this.type,
            initial: v.initial,
            terminal: v.terminal,
            transitions: v.transitions as never,
        });
    }

    private get type(): string {
        return this.typeForm.controls.objectType.value;
    }

    ngOnInit(): void {
        this.workflow.valueChanges.subscribe(() => this.syncDiagram());
        this.typeForm.controls.objectType.valueChanges.subscribe(() => this.load());
        this.load();
    }

    load(): void {
        const type = this.type;
        this.lastError.set(null);
        this.objects
            .workflow(type)
            .pipe(
                catchError((err: HttpErrorResponse) => {
                    if (err.status === 503) {
                        this.unavailable.set(apiErrorMessage(err, 'Incident governance is not installed.'));
                    }
                    return of(null);
                }),
            )
            .subscribe((wf) => {
                const d = workflowDraft(type, wf as unknown as Record<string, unknown>);
                this.workflow.patchValue({ initial: d.initial, terminal: d.terminal });
                this.transitions.clear();
                d.transitions.forEach((t) => this.transitions.push(this.fb.nonNullable.group(t)));
            });
        this.components
            .get('sla-policy', governanceId(type))
            .pipe(catchError(() => of(null)))
            .subscribe((c) => {
                const d = slaDraft(type, c?.content);
                this.sla.patchValue({ zone: d.zone, start: d.start, end: d.end, holidays: d.holidays });
                this.days.set(d.workingDays);
                this.targets.clear();
                d.targets.forEach((t) => this.targets.push(this.fb.group(t)));
            });
        this.components
            .list('escalation-rule')
            .pipe(catchError(() => of([] as ComponentDef[])))
            .subscribe((all) => this.rules.set(all));
    }

    hasDay(d: string): boolean {
        return this.days().includes(d);
    }

    toggleDay(d: string, on: boolean): void {
        this.days.update((ds) => (on ? [...ds.filter((x) => x !== d), d] : ds.filter((x) => x !== d)));
    }

    addTransition(): void {
        this.transitions.push(this.fb.nonNullable.group({ from: '', action: '', to: '' }));
    }

    addTarget(): void {
        this.targets.push(this.fb.group({ priority: '', responseMinutes: null, resolutionMinutes: null }));
    }

    describe(r: ComponentDef): string {
        return describeEscalationRule(r.content);
    }

    saveWorkflow(): void {
        if (this.workflow.invalid) {
            this.workflow.markAllAsTouched();
            return;
        }
        const v = this.workflow.getRawValue();
        this.upsert(
            'workflow',
            workflowContent({
                objectType: this.type,
                initial: v.initial,
                terminal: v.terminal,
                transitions: v.transitions as never,
            }),
            'Workflow',
        );
    }

    saveSla(): void {
        if (this.sla.invalid) {
            this.sla.markAllAsTouched();
            return;
        }
        const v = this.sla.getRawValue();
        this.upsert(
            'sla-policy',
            slaContent({
                objectType: this.type,
                zone: v.zone,
                workingDays: WEEK_DAYS.filter((d) => this.days().includes(d)),
                start: v.start,
                end: v.end,
                holidays: v.holidays,
                targets: v.targets as never,
            }),
            'SLA policy',
        );
    }

    whenShowsErrors(): boolean {
        return this.whenTouched() && this.whenIssues().length > 0;
    }

    whenChanged(): void {
        this.whenIssues.set(validateGroup(this.whenGroup));
        this.whenTouched.set(true);
    }

    addRule(): void {
        const issues = validateGroup(this.whenGroup);
        this.whenIssues.set(issues);
        if (this.rule.invalid || issues.length) {
            this.rule.markAllAsTouched();
            this.whenTouched.set(true);
            // An error renders only where its control renders: open the section that blocks the save.
            if (issues.length) this.whenOpen.set(true);
            return;
        }
        const editing = this.editing();
        if (editing) {
            const stored = editing.content;
            const draft: EscalationRuleDraft = {
                ...this.rule.getRawValue(),
                objectType: String(stored['objectType'] ?? this.type),
                // A stored `when` the editor cannot show stays as stored unless the author built a new one.
                when: this.whenOpaque && !this.whenGroup.items.length ? undefined : this.whenGroup,
            };
            this.report(
                this.components.update('escalation-rule', editing.name, escalationRuleContent(draft, stored), {
                    ifMatch: editing.contentHash,
                }),
                'Escalation Rule',
                () => this.cancelEdit(),
            );
            return;
        }
        const draft: EscalationRuleDraft = { ...this.rule.getRawValue(), objectType: this.type, when: this.whenGroup };
        this.report(this.components.create('escalation-rule', escalationRuleContent(draft)), 'Escalation Rule');
        this.resetWhen();
    }

    /** Load a stored rule into the form; its id is fixed while editing (a rename is delete + add). */
    editRule(r: ComponentDef): void {
        const d = escalationRuleDraft(r.content);
        this.editing.set(r);
        this.rule.reset({
            id: d.id || r.name,
            on: d.on,
            target: d.target,
            afterMinutes: d.afterMinutes ?? 60,
            priority: d.priority,
            reassign: d.reassign,
            notify: d.notify,
            raisePriority: d.raisePriority,
        });
        this.rule.controls.id.disable();
        this.resetWhen();
        this.whenOpaque = d.when === undefined && r.content['when'] !== undefined;
        if (d.when) {
            this.whenGroup = d.when;
            this.whenOpen.set(d.when.items.length > 0);
        }
    }

    cancelEdit(): void {
        this.editing.set(null);
        this.rule.controls.id.enable();
        this.rule.reset();
        this.resetWhen();
    }

    private resetWhen(): void {
        this.whenGroup = emptyGroup('AND');
        this.whenOpaque = false;
        this.whenIssues.set([]);
        this.whenTouched.set(false);
        this.whenOpen.set(false);
    }

    removeRule(id: string): void {
        this.report(this.components.remove('escalation-rule', id), 'Escalation Rule deletion');
    }

    /** Create the per-type document, or replace it when it already exists. */
    private upsert(kind: ComponentType, content: Record<string, unknown>, what: string): void {
        const id = String(content['id']);
        this.components
            .get(kind, id)
            .pipe(catchError(() => of(null)))
            .subscribe((current) => {
                this.report(
                    current
                        ? this.components.update(kind, id, content, { ifMatch: current.contentHash })
                        : this.components.create(kind, content),
                    what,
                );
            });
    }

    private report(call: Observable<unknown>, what: string, onSaved?: () => void): void {
        this.lastError.set(null);
        call.subscribe({
            next: (res) => {
                const held = (res as { status?: string } | null)?.status === 'pending';
                this.toastr.success(held ? `${what} submitted for approval` : `${what} saved`);
                onSaved?.();
                this.load();
            },
            error: (err: HttpErrorResponse) => this.lastError.set(apiErrorMessage(err, `${what} was refused`)),
        });
    }
}
