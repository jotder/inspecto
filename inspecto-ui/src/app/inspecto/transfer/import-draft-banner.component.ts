import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { DiffRow, configDiff } from 'app/inspecto/ai-assist/ai-draft';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ImportDraft } from './import-draft';

/**
 * The one "this is an unsaved imported draft" strip every editor shows while it holds an {@link ImportDraft}:
 * where it came from, that nothing is saved, the ADVISORY integrity findings (D3), and — when the draft
 * landed on an existing item — what it changes against the stored copy (D6). Presentational: the host owns
 * the draft, the Save and the Discard.
 */
@Component({
    selector: 'inspecto-import-draft-banner',
    standalone: true,
    imports: [MatButtonModule, InspectoAlertComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <inspecto-alert variant="info" [title]="title()">
            <div>
                Nothing is saved yet — review it, then Save. Discarding or leaving this page drops the draft.
                @if (draft().prerequisites.length) {
                    Imported first, because it needs them: {{ draft().prerequisites.join(', ') }}.
                }
            </div>
            <button type="button" mat-stroked-button class="mt-2" (click)="discard.emit()">Discard draft</button>
        </inspecto-alert>
        @if (draft().integrity === null) {
            <inspecto-alert class="mt-2 block" variant="warning" title="References not checked">
                The reference check could not run, so broken references are unknown until you save.
            </inspecto-alert>
        } @else if (draft().integrity!.length) {
            <inspecto-alert class="mt-2 block" variant="warning" title="Broken references this draft would introduce">
                @for (f of draft().integrity; track f) {
                    <div class="font-mono text-xs">{{ f }}</div>
                }
            </inspecto-alert>
        }
        @if (changes().length) {
            <details class="mt-2">
                <summary class="cursor-pointer text-sm">
                    Changes against the stored {{ draft().kind }} ({{ changes().length }})
                </summary>
                <div class="overflow-x-auto">
                    <table class="mt-1 w-full text-xs">
                        <thead>
                            <tr class="text-secondary border-b text-left">
                                <th scope="col" class="py-1 pr-4">Field</th>
                                <th scope="col" class="py-1 pr-4">Stored</th>
                                <th scope="col" class="py-1">Incoming</th>
                            </tr>
                        </thead>
                        <tbody>
                            @for (r of changes(); track r.path) {
                                <tr class="border-b align-top">
                                    <td class="py-1 pr-4 font-mono">{{ r.path }}</td>
                                    <td class="break-all py-1 pr-4 font-mono">{{ r.before }}</td>
                                    <td class="break-all py-1 font-mono">{{ r.after }}</td>
                                </tr>
                            }
                        </tbody>
                    </table>
                </div>
            </details>
        }
    `,
})
export class ImportDraftBannerComponent {
    readonly draft = input.required<ImportDraft>();
    /** The stored content the draft replaces (D6); null when the id is new here. */
    readonly stored = input<Record<string, unknown> | null>(null);
    readonly discard = output<void>();

    readonly title = computed(
        () => `Imported draft from ${this.draft().sourceSpace ?? 'another instance'} — not saved`,
    );
    readonly changes = computed<DiffRow[]>(() => {
        const stored = this.stored();
        return stored ? configDiff(stored, this.draft().content).filter((r) => r.change !== 'same') : [];
    });
}
