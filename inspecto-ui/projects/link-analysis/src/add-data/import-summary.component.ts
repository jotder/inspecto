import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { statusIconClasses } from '@inspecto/core/components/status-badge.component';
import { ImportSummary, SummaryLine, summaryLines } from './add-data.model';

const ICONS: Record<SummaryLine['tone'], string> = {
    success: 'heroicons_outline:check-circle',
    info: 'heroicons_outline:information-circle',
    warning: 'heroicons_outline:exclamation-triangle',
    error: 'heroicons_outline:exclamation-circle',
};

/**
 * A run summary **in plain words** (LA-ONBOARD-1 UC-03 step 5, H3): rows read and kept, rows set aside and why, whether
 * the link index was built, each Expectation. One line each, an icon shape beside the words (never colour alone).
 */
@Component({
    selector: 'inspecto-import-summary',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatIconModule],
    template: `
        <ul class="m-0 flex list-none flex-col gap-1 p-0 text-sm" data-testid="import-summary">
            @for (l of lines(); track $index) {
                <li class="flex items-start gap-2" [attr.data-tone]="l.tone">
                    <mat-icon
                        class="icon-size-4 mt-0.5 shrink-0"
                        [class]="iconTone(l.tone)"
                        [svgIcon]="icons[l.tone]"
                        aria-hidden="true"
                    ></mat-icon>
                    <span>{{ l.text }}</span>
                </li>
            }
        </ul>
    `,
})
export class ImportSummaryComponent {
    readonly summary = input.required<ImportSummary>();
    protected readonly lines = computed(() => summaryLines(this.summary()));
    protected readonly icons = ICONS;

    protected iconTone(tone: SummaryLine['tone']): string {
        return statusIconClasses(tone);
    }
}
