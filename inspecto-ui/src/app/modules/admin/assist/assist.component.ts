import { Component, ViewEncapsulation, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AssistIntent } from 'app/inspecto/api';
import { AssistPanelComponent } from 'app/inspecto/components/assist-panel.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';

interface IntentMeta {
    id: AssistIntent;
    label: string;
    placeholder: string;
}

const INTENTS: IntentMeta[] = [
    {
        id: 'kpi-to-sql',
        label: 'KPI → SQL',
        placeholder: 'e.g. how many Consignments per status for MINI_ETL',
    },
    {
        id: 'report-sql',
        label: 'Report → SQL',
        placeholder: 'e.g. daily output rows by pipeline for the last week',
    },
    {
        id: 'nl-to-schedule',
        label: 'NL → schedule',
        placeholder: 'e.g. run the events ingest every weekday at 6am',
    },
    {
        id: 'suggest-config',
        label: 'Suggest config',
        placeholder: 'e.g. a pipeline config for nightly roaming CDRs',
    },
    {
        id: 'diagnose-and-alert',
        label: 'Diagnose → alert',
        placeholder: 'e.g. warn when the error rate exceeds 5%',
    },
    {
        id: 'explain-entity',
        label: 'Explain entity',
        placeholder: 'e.g. what is the mini events table and how is it derived',
    },
    {
        id: 'report-narrative',
        label: 'Report narrative',
        placeholder: 'paste a report summary to narrate',
    },
];

/**
 * AI assist console — a single screen exposing all seven assist intents via the reusable
 * AssistPanel (ported from inspector-ui). Selecting an intent re-keys the panel (so its state
 * resets) with the right placeholder. Degrades gracefully when the agent is absent (503).
 */
@Component({
    selector: 'app-assist',
    standalone: true,
    imports: [FormsModule, InspectoOptionPickerComponent, AssistPanelComponent],
    template: `
        <div class="flex min-w-0 flex-auto flex-col">
            <div
                class="bg-card flex flex-col border-b p-6 sm:flex-row sm:items-center sm:justify-between sm:px-10 sm:py-8 dark:bg-transparent"
            >
                <div>
                    <h1 class="text-3xl font-extrabold leading-none tracking-tight">Assistant</h1>
                    <div class="text-secondary mt-1.5">Draft-only AI assist skills</div>
                </div>
                <inspecto-option-picker
                    class="mt-4 w-72 sm:mt-0"
                    label="Assist skill"
                    [options]="intentOptions"
                    [ngModel]="selected.id"
                    (ngModelChange)="selectIntent($event)"
                ></inspecto-option-picker>
            </div>

            <div class="flex flex-auto flex-col p-6 sm:p-10">
                <!-- track by id recreates the panel when the intent changes, resetting its state -->
                @for (s of [selected]; track s.id) {
                    <app-assist-panel [intent]="s.id" [placeholder]="s.placeholder"></app-assist-panel>
                }
            </div>
        </div>
    `,
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class AssistComponent {
    readonly intents = INTENTS;
    /** The picker carries string values, so it binds the intent id and `selectIntent` resolves the meta. */
    readonly intentOptions: PickerOption[] = INTENTS.map((m) => ({ value: m.id, label: m.label }));
    selected: IntentMeta = INTENTS[0];

    selectIntent(id: string): void {
        this.selected = INTENTS.find((m) => m.id === id) ?? this.selected;
    }
}
