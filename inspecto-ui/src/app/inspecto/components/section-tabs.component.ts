import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';
import { ChipComponent } from './chip.component';

/** One tab of an `<inspecto-section-tabs>` strip. */
export interface SectionTab {
    id: string;
    label: string;
    /** Rendered as a count pill after the label; `undefined` renders nothing, `0` renders "0". */
    count?: number;
    disabled?: boolean;
}

/**
 * A labels-only tab strip (UI consolidation plan UI-03). Panes that stacked N sections down the page
 * (Components: Grammar · Schema · Mapping · Transform · Sink, each with its own empty state) render the
 * strip and switch ONE content area on `selected`, so the pane fits a screen and the operator sees at a
 * glance which section holds anything (the count pill).
 *
 * Deliberately not `mat-tab-group` with bodies: the host owns the content and its lifecycle (a grid
 * inside a lazily-instantiated tab body is invisible to `@ViewChild` until visited — the R9 rule), so
 * the strip is presentation only and the host renders `@switch (selected)` beneath it.
 *
 * Also the `[tabs]` slot content for `<inspecto-page-header hasTabs>` (Catalog, Processing Status, Jobs).
 */
@Component({
    selector: 'inspecto-section-tabs',
    standalone: true,
    imports: [MatTabsModule, ChipComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block' },
    template: `
        <mat-tab-group
            class="inspecto-section-tabs"
            mat-stretch-tabs="false"
            animationDuration="0ms"
            [selectedIndex]="index()"
            (selectedIndexChange)="pick($event)"
        >
            @for (t of tabs; track t.id) {
                <mat-tab [disabled]="!!t.disabled">
                    <ng-template mat-tab-label>
                        <span>{{ t.label }}</span>
                        @if (t.count !== undefined) {
                            <inspecto-chip class="ml-2" variant="soft" tone="neutral">
                                <span class="tabular-nums">{{ t.count }}</span>
                            </inspecto-chip>
                        }
                    </ng-template>
                </mat-tab>
            }
        </mat-tab-group>
    `,
})
export class InspectoSectionTabsComponent {
    @Input({ required: true }) tabs: SectionTab[] = [];

    /**
     * 🔴 The selected index is held HERE, not derived from `selected` in the template binding.
     * `MatTabGroup` decides whether to emit `selectedIndexChange` in `ngAfterContentChecked`, by
     * comparing the index the click set against the one its `[selectedIndex]` input carries — so
     * binding that input to a getter over `selected` re-asserts the OLD index in the same change
     * detection pass, the emit never happens, and the tab silently springs back. Driving the input
     * from this signal and reconciling `selected` into it through the setter is what makes a click
     * stick. (Found by the spec, 2026-09-22; it fails on `HTMLElement.click()` for an unrelated
     * reason, so the regression needs the bubbling-MouseEvent idiom to stay visible.)
     */
    readonly index = signal(0);

    private _selected = '';

    /** The active tab's id. */
    @Input()
    set selected(id: string) {
        this._selected = id;
        const i = this.tabs.findIndex((t) => t.id === id);
        this.index.set(i < 0 ? 0 : i);
    }
    get selected(): string {
        return this._selected;
    }

    @Output() readonly selectedChange = new EventEmitter<string>();

    pick(index: number): void {
        const t = this.tabs[index];
        if (!t || t.id === this._selected) {
            return;
        }
        this._selected = t.id;
        this.index.set(index);
        this.selectedChange.emit(t.id);
    }
}
