import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TermHelpComponent } from '@inspecto/core/onboarding/term-help.component';
import { InvestigationSessionStore } from '@inspecto/link-analysis/link-analysis/link-analysis-investigation.store';
import { LinkAnalysisEntityListsComponent } from '@inspecto/link-analysis/link-analysis/link-analysis-entity-lists.component';
import { LinkAnalysisIdentityComponent } from '@inspecto/link-analysis/link-analysis/link-analysis-identity.component';

type ListsTab = 'lists' | 'identity';

/**
 * Entity Lists on their own page. The library renders them inside an Investigation; here there is no open
 * Investigation, so this page provides the (empty) session store the section reads. Listing, creating, adding to and
 * retiring a list work without an Investigation; excluding or seeding by a list needs one and stays off.
 *
 * LA-NO-LONG-PAGES-1 (operator 2026-11-10): the lists and the Identity Fact log are two tabs over one panel that scrolls
 * on its own, so the page is the window and never grows. Both panels stay mounted (only hidden), so each keeps its state
 * across a tab switch.
 */
@Component({
    selector: 'la-app-entity-lists-page',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [LinkAnalysisEntityListsComponent, LinkAnalysisIdentityComponent, TermHelpComponent],
    providers: [InvestigationSessionStore],
    template: `
        <div
            class="mx-auto flex w-full max-w-4xl flex-col gap-4 p-6 md:h-[calc(100dvh-var(--shell-chrome-height,0rem))] md:overflow-hidden"
        >
            <h1 id="la-entity-lists-title" class="text-title m-0 font-semibold">Entity Lists</h1>
            <p class="text-secondary m-0 text-sm" data-testid="la-lists-about">
                Each <inspecto-term term="entity-list"></inspecto-term> keeps entities that matter. Start a graph from a
                list in Link Analysis, or leave a list's members out while you grow it.
            </p>
            <h2 class="sr-only">Lists and identity</h2>
            <div
                class="flex shrink-0 gap-1 border-b"
                role="tablist"
                aria-label="Entity Lists page"
                style="border-color: var(--gamma-border)"
            >
                @for (t of tabs; track t.id) {
                    <button
                        type="button"
                        role="tab"
                        class="rounded-t-md px-3 py-1.5 text-sm"
                        [id]="'la-lists-tab-' + t.id"
                        [class.font-semibold]="tab() === t.id"
                        [class.text-secondary]="tab() !== t.id"
                        [style.boxShadow]="tab() === t.id ? 'inset 0 -2px 0 var(--gamma-primary)' : null"
                        [attr.aria-selected]="tab() === t.id"
                        [attr.aria-controls]="'la-lists-panel-' + t.id"
                        [attr.tabindex]="tab() === t.id ? 0 : -1"
                        [attr.data-testid]="'la-lists-tab-' + t.id"
                        (click)="tab.set(t.id)"
                        (keydown)="onTabKey($event)"
                    >
                        {{ t.label }}
                    </button>
                }
            </div>
            <div
                id="la-lists-panel-lists"
                role="tabpanel"
                aria-labelledby="la-lists-tab-lists"
                class="min-h-0 flex-auto overflow-y-auto"
                [hidden]="tab() !== 'lists'"
            >
                <inspecto-link-analysis-entity-lists></inspecto-link-analysis-entity-lists>
            </div>
            <div
                id="la-lists-panel-identity"
                role="tabpanel"
                aria-labelledby="la-lists-tab-identity"
                class="min-h-0 flex-auto overflow-y-auto"
                [hidden]="tab() !== 'identity'"
            >
                <inspecto-link-analysis-identity></inspecto-link-analysis-identity>
            </div>
        </div>
    `,
})
export class EntityListsPageComponent {
    readonly tabs: readonly { id: ListsTab; label: string }[] = [
        { id: 'lists', label: 'Entity Lists' },
        { id: 'identity', label: 'Identity Fact log' },
    ];
    readonly tab = signal<ListsTab>('lists');

    /** Arrow keys move between the two tabs (the WAI-ARIA tabs pattern). */
    onTabKey(e: KeyboardEvent): void {
        if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
        e.preventDefault();
        const next: ListsTab = this.tab() === 'lists' ? 'identity' : 'lists';
        this.tab.set(next);
        (document.getElementById('la-lists-tab-' + next) as HTMLElement | null)?.focus();
    }
}
