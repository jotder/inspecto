import { ChangeDetectionStrategy, Component, Input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AttributeToken } from '../component-model';

/**
 * The whole-value token affordance for one field: an icon button opening the list of
 * {@link AttributeToken}s a host offers, each with its preview. Emits the chosen token; the host writes it.
 *
 * <p>Used with `matSuffix` inside a `<mat-form-field>` — which is the reason it is a component at all
 * rather than a template in {@link InspectoSchemaFormComponent}. **Material resolves content projection
 * statically, at the declaration site**: markup carrying `matSuffix` inside an `<ng-template>` that is
 * instantiated into the form field by `*ngTemplateOutlet` is not a static child of it, so the suffix slot
 * never claims it and the button lands in the infix, beside the input. It still renders, so a unit test
 * asserting the button exists passes either way — this was caught in the preview by reading the button's
 * parent class. One element per widget case keeps the projection static.
 */
@Component({
    selector: 'inspecto-token-picker',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (tokens.length) {
            <button
                mat-icon-button
                type="button"
                matTooltip="Insert a runtime token"
                [attr.aria-label]="'Insert a runtime token into ' + fieldLabel"
                [matMenuTriggerFor]="menu"
            >
                <mat-icon svgIcon="heroicons_outline:variable" />
            </button>
            <!-- The panel class is styled globally (styles/styles.scss): a menu renders in an overlay
                 outside this component, so its own styles cannot reach it. -->
            <mat-menu #menu="matMenu" class="inspecto-token-menu">
                <div class="text-secondary max-w-80 px-4 py-2 text-xs">
                    Replaces the whole value — a token inside a longer value stays literal.
                </div>
                @for (t of tokens; track t.token) {
                    <button mat-menu-item type="button" (click)="picked.emit(t)">
                        <span class="font-mono">{{ t.token }}</span>
                        @if (t.preview) {
                            <span class="text-secondary text-xs"> → {{ t.preview }}</span>
                        }
                        @if (t.description) {
                            <span class="text-secondary block text-xs">{{ t.description }}</span>
                        }
                    </button>
                }
            </mat-menu>
        }
    `,
})
export class InspectoTokenPickerComponent {
    /** The field's label, for the button's accessible name (there is one picker per field). */
    @Input() fieldLabel = '';
    /** The tokens to offer. Empty ⇒ nothing renders, which is how a host says "literal only". */
    @Input() tokens: AttributeToken[] = [];
    readonly picked = output<AttributeToken>();
}
