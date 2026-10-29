import { ChangeDetectionStrategy, Component, computed } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
    DomainProfile,
    DomainTerm,
    activeDomainProfileId,
    domainProfile,
} from '@inspecto/link-analysis/graph/domain-profile';
import { FormMenuDirective } from './form-menu.directive';

/** One typology term of the active Industry pack (`term.<id>`), with its key stripped. */
export interface GlossaryEntry extends DomainTerm {
    id: string;
}

/** The pack's typology terms (`term.<id>`: the fraud / risk vocabulary it defines), sorted by label. Empty for `generic`. */
export function glossaryEntries(profile: DomainProfile): GlossaryEntry[] {
    return Object.entries(profile.terms)
        .filter(([key]) => key.startsWith('term.'))
        .map(([key, t]) => ({ id: key.slice('term.'.length), ...t }))
        .sort((a, b) => a.label.localeCompare(b.label));
}

/**
 * **Glossary popover**: the active Industry pack's typology terms (SIM box, IRSF, ... — label, abbreviation, one-line
 * definition) one click from the tool rail. Display only: a pack explains words, it adds no concept. Hidden when the
 * pack states no `term.<id>` entries (`generic`, or a pack without a typology).
 */
@Component({
    selector: 'inspecto-link-analysis-glossary',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormMenuDirective, MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule],
    template: `
        @if (entries().length) {
            <button
                mat-icon-button
                type="button"
                [matMenuTriggerFor]="glossaryMenu"
                laFormMenu
                matTooltip="Industry pack glossary"
                aria-label="Industry pack glossary"
                data-testid="la-glossary"
            >
                <mat-icon svgIcon="heroicons_outline:book-open"></mat-icon>
            </button>
            <mat-menu #glossaryMenu="matMenu">
                <div class="max-w-sm px-4 pt-2 text-sm font-semibold">Industry pack glossary</div>
                <!-- eslint-disable-next-line @angular-eslint/template/click-events-have-key-events, @angular-eslint/template/interactive-supports-focus -- (click) only stops propagation so the mat-menu stays open; not a control -->
                <dl class="m-0 flex max-h-96 max-w-sm flex-col gap-2 overflow-y-auto px-4 py-2" (click)="$event.stopPropagation()">
                    @for (e of entries(); track e.id) {
                        <div>
                            <dt class="text-sm font-medium">
                                {{ e.label }}
                                @if (e.abbreviation) {
                                    <span class="text-secondary font-normal">({{ e.abbreviation }})</span>
                                }
                            </dt>
                            <dd class="text-secondary m-0 text-xs">{{ e.definition || 'No definition stated.' }}</dd>
                        </div>
                    }
                </dl>
            </mat-menu>
        }
    `,
})
export class LinkAnalysisGlossaryComponent {
    readonly entries = computed(() => glossaryEntries(domainProfile(activeDomainProfileId())));
}
