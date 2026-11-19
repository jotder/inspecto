import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EMPTY, filter, map } from 'rxjs';
import { helpUrlFor } from './help-chapter';

/**
 * The page-level Help link (USER-HANDBOOK-1): one icon link that opens, in a new tab, the Handbook chapter for the
 * current screen (`helpUrlFor`, the ONE route-to-chapter map). Its icon is the book, not the `?`, because the `?`
 * beside a title already means "explain this screen's words" (`<inspecto-ai-explain>`). Rendered by
 * `<inspecto-page-header>`, so every routed pane carries it without a per-pane edit.
 */
@Component({
    selector: 'inspecto-help-link',
    standalone: true,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <a
            mat-icon-button
            data-testid="help-link"
            [href]="href()"
            target="_blank"
            rel="noopener"
            aria-label="Help for this screen (opens the Handbook in a new tab)"
            matTooltip="Help for this screen"
        >
            <mat-icon svgIcon="heroicons_outline:book-open" />
        </a>
    `,
})
export class HelpLinkComponent {
    private readonly router = inject(Router, { optional: true });
    private readonly url = toSignal(
        this.router
            ? this.router.events.pipe(
                  filter((e) => e instanceof NavigationEnd),
                  map(() => this.router!.url),
              )
            : EMPTY,
        { initialValue: this.router?.url ?? '/' },
    );
    readonly href = computed(() => helpUrlFor(this.url()));
}
