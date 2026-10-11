import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import {
    WorkflowDraft,
    connectStates,
    removeState,
    renameState,
    setInitialState,
    setTerminalState,
    splitList,
    workflowProblems,
    workflowStates,
} from 'app/inspecto/governance/governance-model';
import { WorkflowDiagramComponent } from './workflow-diagram.component';

/**
 * Graphical state-diagram editor (ASSURE-WORKFLOW-SLA-RESIDUALS-1 b). Edits the same `WorkflowDraft` the transitions
 * table edits, so what is saved is exactly what the form path saves — states are implicit (initial ∪ terminal ∪
 * transition endpoints) and there is nowhere to persist a layout, so the diagram is auto-laid-out. The States table
 * and the From/To connect controls are the keyboard-accessible equivalent of clicking states in the diagram.
 * Problems shown mirror the server's rules; the server stays the judge (its 422 shows on save).
 */
@Component({
    selector: 'inspecto-workflow-state-editor',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, WorkflowDiagramComponent],
    template: `
        <div class="flex flex-col gap-3">
            <div class="flex flex-col gap-1 overflow-x-auto">
                <h3 class="text-sm font-semibold">State diagram</h3>
                <inspecto-workflow-diagram
                    [draft]="draft()"
                    [extraStates]="floating()"
                    [selected]="from()"
                    [pickable]="canEdit()"
                    (stateClick)="pick($event)"
                />
                @if (canEdit()) {
                    <p class="text-secondary text-xs">
                        Click a state, then another, to connect them — or use the controls below.
                    </p>
                }
            </div>

            <table class="w-full max-w-200 text-sm">
                <caption class="sr-only">
                    States
                </caption>
                <thead>
                    <tr class="text-secondary text-left">
                        <th scope="col" class="py-1">State</th>
                        <th scope="col">Initial</th>
                        <th scope="col">Terminal</th>
                        <th scope="col"><span class="sr-only">Remove</span></th>
                    </tr>
                </thead>
                <tbody>
                    @for (s of states(); track s) {
                        <tr>
                            <td>
                                <input
                                    class="w-full rounded border px-2 py-1"
                                    [value]="s"
                                    [disabled]="!canEdit()"
                                    [attr.aria-label]="'Name of state ' + s"
                                    (change)="rename(s, $any($event.target))"
                                />
                            </td>
                            <td>
                                <input
                                    type="radio"
                                    name="wf-initial"
                                    [checked]="isInitial(s)"
                                    [disabled]="!canEdit() || isFloating(s)"
                                    [attr.aria-label]="s + ' is the initial state'"
                                    (change)="emit(setInitial(s))"
                                />
                            </td>
                            <td>
                                <input
                                    type="checkbox"
                                    [checked]="isTerminal(s)"
                                    [disabled]="!canEdit() || isFloating(s)"
                                    [attr.aria-label]="s + ' is a terminal state'"
                                    (change)="emit(setTerminal(s, $any($event.target).checked))"
                                />
                            </td>
                            <td>
                                <button
                                    mat-icon-button
                                    type="button"
                                    [disabled]="!canEdit()"
                                    [attr.aria-label]="'Remove state ' + s"
                                    (click)="remove(s)"
                                >
                                    <mat-icon svgIcon="heroicons_outline:trash"></mat-icon>
                                </button>
                            </td>
                        </tr>
                    }
                </tbody>
            </table>
            @if (floating().length) {
                <p class="text-secondary text-xs">
                    A state with no transition is not saved until it has one (or is the initial or a terminal state).
                </p>
            }

            @if (canEdit()) {
                <div class="flex flex-wrap items-end gap-2">
                    <label class="flex flex-col text-xs">
                        New state
                        <input
                            #newState
                            class="w-40 rounded border px-2 py-1 text-sm"
                            (keydown.enter)="addState(newState); $event.preventDefault()"
                        />
                    </label>
                    <button mat-stroked-button type="button" (click)="addState(newState)">Add state</button>
                    <label class="flex flex-col text-xs">
                        Connect from
                        <select
                            class="w-40 rounded border px-2 py-1 text-sm"
                            [value]="from() ?? ''"
                            (change)="from.set($any($event.target).value || null)"
                        >
                            <option value="">—</option>
                            @for (s of states(); track s) {
                                <option [value]="s">{{ s }}</option>
                            }
                        </select>
                    </label>
                    <label class="flex flex-col text-xs">
                        to
                        <select
                            class="w-40 rounded border px-2 py-1 text-sm"
                            [value]="to() ?? ''"
                            (change)="to.set($any($event.target).value || null)"
                        >
                            <option value="">—</option>
                            @for (s of states(); track s) {
                                <option [value]="s">{{ s }}</option>
                            }
                        </select>
                    </label>
                    <button mat-stroked-button type="button" [disabled]="!from() || !to()" (click)="connect()">
                        Connect states
                    </button>
                </div>
            }
            @if (notice(); as n) {
                <p class="text-sm" role="alert">{{ n }}</p>
            }
            <div aria-live="polite">
                @if (problems().length) {
                    <p class="text-sm font-semibold">What the server would refuse</p>
                    <ul class="text-warn list-disc pl-5 text-sm">
                        @for (p of problems(); track p) {
                            <li>{{ p }}</li>
                        }
                    </ul>
                }
            </div>
        </div>
    `,
})
export class WorkflowStateEditorComponent {
    readonly draft = input.required<WorkflowDraft>();
    readonly canEdit = input(true);
    readonly draftChange = output<WorkflowDraft>();

    readonly floating = signal<string[]>([]);
    readonly from = signal<string | null>(null);
    readonly to = signal<string | null>(null);
    readonly notice = signal<string | null>(null);

    /** States the draft names, plus added ones that have no transition yet. */
    readonly states = computed(() => {
        const used = workflowStates(this.draft());
        return [...used, ...this.floating().filter((s) => !used.includes(s))];
    });
    readonly problems = computed(() => workflowProblems(this.draft()));

    isInitial(s: string): boolean {
        return this.draft().initial.trim().toUpperCase() === s;
    }
    isTerminal(s: string): boolean {
        return splitList(this.draft().terminal).includes(s);
    }
    isFloating(s: string): boolean {
        return !workflowStates(this.draft()).includes(s);
    }

    setInitial(s: string): WorkflowDraft {
        return setInitialState(this.draft(), s);
    }
    setTerminal(s: string, on: boolean): WorkflowDraft {
        return setTerminalState(this.draft(), s, on);
    }

    emit(d: WorkflowDraft): void {
        this.notice.set(null);
        this.draftChange.emit(d);
    }

    addState(input: HTMLInputElement): void {
        const name = input.value.trim().toUpperCase();
        if (!name) return;
        if (this.states().includes(name)) {
            this.notice.set(`A state named ${name} already exists.`);
            return;
        }
        this.notice.set(null);
        this.floating.update((f) => [...f, name]);
        input.value = '';
    }

    rename(s: string, input: HTMLInputElement): void {
        const name = input.value.trim().toUpperCase();
        if (!name || name === s) {
            input.value = s;
            return;
        }
        if (this.states().includes(name)) {
            this.notice.set(`A state named ${name} already exists.`);
            input.value = s;
            return;
        }
        this.floating.update((f) => f.map((x) => (x === s ? name : x)));
        this.emit(renameState(this.draft(), s, name));
    }

    remove(s: string): void {
        this.floating.update((f) => f.filter((x) => x !== s));
        this.from.update((f) => (f === s ? null : f));
        this.to.update((t) => (t === s ? null : t));
        this.emit(removeState(this.draft(), s));
    }

    /** A diagram click: the first state is the start of a connection, the second its end. */
    pick(s: string): void {
        if (!this.from()) this.from.set(s);
        else {
            this.to.set(s);
            this.connect();
        }
    }

    connect(): void {
        const a = this.from();
        const b = this.to();
        if (!a || !b) return;
        this.floating.update((f) => f.filter((x) => x !== a && x !== b));
        this.from.set(null);
        this.to.set(null);
        this.emit(connectStates(this.draft(), a, b));
        this.notice.set(`Transition ${a} to ${b} added. Name its action in the transitions table.`);
    }
}
